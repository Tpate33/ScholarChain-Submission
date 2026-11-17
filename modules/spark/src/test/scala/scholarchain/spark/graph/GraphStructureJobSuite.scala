package scholarchain.spark.graph

import java.net.URI
import java.nio.file.{Files, Path, Paths}

import scala.collection.mutable

import org.apache.spark.sql.SparkSession

import scholarchain.core.{BridgeMeasure, Canonical, TimeWindow}
import scholarchain.spark.testkit.LocalSpark

/**
 * Connected components, label-propagation communities, bridge counts and k-core coreness of
 * [[GraphStructureJob]] on six small hand-checkable graphs, one per window index (so the windows
 * are computed together and must not interact; the same letters are reused in every window):
 * {{{
 *   w  graph                                   vertices             edges
 *   0  disconnected, with a singleton          a b c d e            ab cd
 *   1  triangle plus pendant                   a b c d              ab ac bc cd
 *   2  two K4 joined by one bridge edge        a..h                 K4(abcd) K4(efgh) de
 *   3  label-propagation tie case              a b c e f g z        ab ac bc ef eg fg cz ez
 *   4  known coreness 0..3                     a..i                 K4(abcd) ef eg fg ag eh, i alone
 *   5  hub reaching two other communities      a..i z               ab ac bc de df ef gh gi hi cz fz iz
 * }}}
 * Hand derivations (labels are letters; UTF-8 order a < b < ... < z):
 *
 * Components (hash-min; label = smallest member): w0 {a,b}=a {c,d}=c {e}=e; w1-w3 and w5 one
 * component labelled a; w4 {a..h}=a {i}=i. Iterations: w5 needs 5 (a reaches h over a-c-z-i-h in 4
 * label hops, plus one iteration without change), the most of all windows, so 5 in total.
 *
 * Label propagation (synchronous, self vote, ties to the smallest label):
 *   - w0: it1 a:{a,b}->a b:{b,a}->a c:{c,d}->c d:{d,c}->c; it2 no change: converged at 2;
 *   - w1: it1 a,b,c->a d:{d,c}->c; it2 d:{c,a} tie->a; it3 no change: converged at 3;
 *   - w2: it1 a,b,c,d->a e:{e,d,f,g,h}->d f,g,h->e; it2 e:{d,a,e,e,e}->e; it3 none: 3;
 *   - w3 (tie): it1 a,b,c->a e,f,g->e z:{z,c,e}->c; it2 z:{c,a,e} three-way tie->a (the smallest
 *     label decides; with the largest it would be e); it3 none: 3; communities {a,b,c,z} {e,f,g};
 *   - w4: it1 a,b,c,d->a g:{g,e,f,a}->a e,f,h->e i->i; it2 g:{a,e,e,a} tie->a, no change: 2;
 *     communities {a,b,c,d,g} {e,f,h} {i};
 *   - w5: it1 abc->a def->d ghi->g z:{z,c,f,i}->c; it2 z:{c,a,d,g}->a; it3 none: 3; communities
 *     {a,b,c,z} {d,e,f} {g,h,i}.
 *
 * Bridge counts (degree, cross-community degree, communities reached, sum of squares): w2 d and e
 * (4,1,1,9+1); w3 z (2,1,1,1+1), e (3,1,1,4+1); w4 g (3,2,1,4+1), e (3,1,1,4+1), f (2,1,1,1+1); w5
 * z (3,2,2,1+1+1), f and i (3,1,1,4+1); every other vertex (d,0,0,d*d).
 *
 * Coreness (peeling): w0 a,b,c,d 1, e 0; w1 a,b,c 2, d 1; w2 all 3; w3 all 2; w4 i 0, h 1, e,f,g 2,
 * a,b,c,d 3 (rounds: i; h; e,f; g; a,b,c,d = 5, the most of all windows); w5 all 2.
 *
 * Every exact result (components, coreness) and the propagation are also compared with independent
 * sequential reference implementations on a deterministic pseudo-random graph.
 */
class GraphStructureJobSuite extends munit.FunSuite {

  private var tmp: Path = null
  private var spark: SparkSession = null
  private var runs = 0

  override def beforeAll(): Unit = {
    tmp = Files.createTempDirectory("scholarchain-structure-")
    spark = LocalSpark.start("scholarchain-structure-test", tmp.resolve("spark"))
  }

  override def afterAll(): Unit =
    try if (spark != null) spark.stop()
    finally LocalSpark.deleteTree(tmp)

  private type Graph = (Vector[String], Vector[(String, String)])

  private def graph(vertices: String, edges: String): Graph =
    (
      vertices.split(" ").toVector,
      edges.split(" ").toVector.filter(_.nonEmpty).map(e => (e.substring(0, 1), e.substring(1, 2)))
    )

  private val hand: Vector[Graph] = Vector(
    graph("a b c d e", "ab cd"),
    graph("a b c d", "ab ac bc cd"),
    graph("a b c d e f g h", "ab ac ad bc bd cd ef eg eh fg fh gh de"),
    graph("a b c e f g z", "ab ac bc ef eg fg cz ez"),
    graph("a b c d e f g h i", "ab ac ad bc bd cd ef eg fg ag eh"),
    graph("a b c d e f g h i z", "ab ac bc de df ef gh gi hi cz fz iz")
  )

  private def windowsFor(n: Int) = Vector.tabulate(n)(i => TimeWindow(2001 + i, 2001 + i))

  private def nextDir(): String = { runs += 1; tmp.resolve(s"checkpoints-$runs").toString }

  private def compute(
      gs: Vector[Graph],
      maxIterations: Int = 20,
      partitions: Int = 1,
      interval: Int = 2,
      reverse: Boolean = false,
      extraEdges: Vector[(Int, String, String)] = Vector.empty
  ): Either[Vector[String], GraphStructure] = {
    val vs = for { (g, w) <- gs.zipWithIndex; v <- g._1 } yield (w, v)
    val es = (for { (g, w) <- gs.zipWithIndex; (a, b) <- g._2 } yield (w, a, b)) ++ extraEdges
    val sc = spark.sparkContext
    GraphStructureJob.compute(
      sc,
      sc.parallelize(if (reverse) vs.reverse else vs, partitions),
      sc.parallelize(if (reverse) es.reverse else es, partitions),
      windowsFor(gs.size),
      maxIterations,
      Checkpointing(nextDir(), interval),
      partitions
    )
  }

  private def ok(r: Either[Vector[String], GraphStructure]): GraphStructure =
    r.fold(e => fail(e.mkString("\n")), identity)

  private def withStructure[T](s: GraphStructure)(body: GraphStructure => T): T =
    try body(s)
    finally assertEquals(s.release(spark.sparkContext), Vector.empty)

  /** "a:x b:y" in window w -> Map((w, a) -> x, (w, b) -> y). */
  private def table[T](w: Int, s: String)(value: String => T): Map[(Int, String), T] =
    s.split(" ")
      .toVector
      .map { kv =>
        kv.split(":") match {
          case Array(k, v) => (w, k) -> value(v)
          case _ => fail(s"bad table entry $kv")
        }
      }
      .toMap

  private def labels(rows: (Int, String)*): Map[(Int, String), String] =
    rows.flatMap { case (w, s) => table(w, s)(identity) }.toMap

  private def longs(rows: (Int, String)*): Map[(Int, String), Long] =
    rows.flatMap { case (w, s) => table(w, s)(_.toLong) }.toMap

  test("connected components of the hand graphs: labels, iterations, sizes and singletons") {
    withStructure(ok(compute(hand))) { s =>
      assertEquals(
        s.components.collect().toMap,
        labels(
          0 -> "a:a b:a c:c d:c e:e",
          1 -> "a:a b:a c:a d:a",
          2 -> "a:a b:a c:a d:a e:a f:a g:a h:a",
          3 -> "a:a b:a c:a e:a f:a g:a z:a",
          4 -> "a:a b:a c:a d:a e:a f:a g:a h:a i:i",
          5 -> "a:a b:a c:a d:a e:a f:a g:a h:a i:a z:a"
        )
      )
      assertEquals(s.componentIterations, 5)
      assertEquals(s.vertexCount, 5L + 4 + 8 + 7 + 9 + 10)
      val sum = GraphStructureJob.summaries(s, GraphStructureJob.bridgeCounts(s, 1))
      assertEquals(
        sum.map(x =>
          (x.researchers, x.edges, x.components, x.largestComponent, x.singletonComponents)
        ),
        Vector(
          (5L, 2L, 3L, 2L, 1L),
          (4L, 4L, 1L, 4L, 0L),
          (8L, 13L, 1L, 8L, 0L),
          (7L, 8L, 1L, 7L, 0L),
          (9L, 11L, 2L, 8L, 1L),
          (10L, 12L, 1L, 10L, 0L)
        )
      )
      assertEquals(sum.map(_.largestComponentFraction).take(2), Vector(2.0 / 5.0, 1.0))
      assertEquals(sum(4).largestComponentFraction, 8.0 / 9.0)
    }
  }

  test("label-propagation communities of the hand graphs, including the tie case") {
    withStructure(ok(compute(hand))) { s =>
      assertEquals(
        s.communities.collect().toMap,
        labels(
          0 -> "a:a b:a c:c d:c e:e",
          1 -> "a:a b:a c:a d:a",
          2 -> "a:a b:a c:a d:a e:e f:e g:e h:e",
          3 -> "a:a b:a c:a z:a e:e f:e g:e",
          4 -> "a:a b:a c:a d:a g:a e:e f:e h:e i:i",
          5 -> "a:a b:a c:a z:a d:d e:d f:d g:g h:g i:g"
        )
      )
      assertEquals(
        s.communityOutcomes.map(o => (o.iterations, o.converged, o.lastIterationChanges)),
        Vector(
          (2, true, 0L),
          (3, true, 0L),
          (3, true, 0L),
          (3, true, 0L),
          (2, true, 0L),
          (3, true, 0L)
        )
      )
      assertEquals(s.communityOutcomes.map(_.window), windowsFor(6))
      val sum = GraphStructureJob.summaries(s, GraphStructureJob.bridgeCounts(s, 1))
      assertEquals(
        sum.map(x =>
          (x.communities, x.largestCommunity, x.singletonCommunities, x.crossCommunityEdges)
        ),
        Vector(
          (3L, 2L, 1L, 0L),
          (1L, 4L, 0L, 0L),
          (2L, 4L, 0L, 1L),
          (2L, 4L, 0L, 1L),
          (3L, 5L, 1L, 2L),
          (3L, 4L, 0L, 2L)
        )
      )
    }
  }

  test("bridge counts and participation coefficients of the hand graphs") {
    withStructure(ok(compute(hand))) { s =>
      val got = GraphStructureJob.bridgeCounts(s, 1).collect().toMap
      val degrees = s.degrees.collect().toMap
      val special: Map[(Int, String), BridgeCounts] = Map(
        (2, "d") -> BridgeCounts(4, 1, 1, 10),
        (2, "e") -> BridgeCounts(4, 1, 1, 10),
        (3, "z") -> BridgeCounts(2, 1, 1, 2),
        (3, "e") -> BridgeCounts(3, 1, 1, 5),
        (4, "g") -> BridgeCounts(3, 2, 1, 5),
        (4, "e") -> BridgeCounts(3, 1, 1, 5),
        (4, "f") -> BridgeCounts(2, 1, 1, 2),
        (5, "z") -> BridgeCounts(3, 2, 2, 3),
        (5, "f") -> BridgeCounts(3, 1, 1, 5),
        (5, "i") -> BridgeCounts(3, 1, 1, 5)
      )
      val expected = degrees.map { case (v, d) =>
        v -> special.getOrElse(v, BridgeCounts(d, 0L, 0L, d * d))
      }
      assertEquals(got, expected)
      def pc(v: (Int, String)) = BridgeMeasure.participation(got(v).degree, got(v).sumOfSquares)
      assertEquals(pc((5, "z")), 1.0 - 3.0 / 9.0)
      assertEquals(pc((2, "d")), 0.375)
      assertEquals(pc((3, "z")), 0.5)
      assertEquals(pc((4, "g")), 1.0 - 5.0 / 9.0)
      assertEquals(pc((2, "a")), 0.0)
      assertEquals(pc((0, "e")), 0.0, "no coauthor: participation 0.0")
      assertEquals(
        degrees.filter(_._1._1 == 4),
        longs(4 -> "a:4 b:3 c:3 d:3 e:3 f:2 g:3 h:1 i:0")
      )
    }
  }

  test("k-core coreness by peeling: known coreness 0..3 and every hand graph") {
    withStructure(ok(compute(hand))) { s =>
      assertEquals(
        s.coreness.collect().toMap,
        longs(
          0 -> "a:1 b:1 c:1 d:1 e:0",
          1 -> "a:2 b:2 c:2 d:1",
          2 -> "a:3 b:3 c:3 d:3 e:3 f:3 g:3 h:3",
          3 -> "a:2 b:2 c:2 e:2 f:2 g:2 z:2",
          4 -> "a:3 b:3 c:3 d:3 e:2 f:2 g:2 h:1 i:0",
          5 -> "a:2 b:2 c:2 d:2 e:2 f:2 g:2 h:2 i:2 z:2"
        )
      )
      assertEquals(s.corenessRounds, 5)
      val sum = GraphStructureJob.summaries(s, GraphStructureJob.bridgeCounts(s, 1))
      assertEquals(sum.map(_.maxCoreness), Vector(1L, 2L, 3L, 2L, 3L, 2L))
      assertEquals(sum(4).corenessHistogram, Vector((0L, 1L), (1L, 1L), (2L, 3L), (3L, 4L)))
    }
  }

  test("deterministic: three partitions and reversed input order give the same results") {
    def snapshot(s: GraphStructure) = (
      s.components.collect().toMap,
      s.communities.collect().toMap,
      s.coreness.collect().toMap,
      GraphStructureJob.bridgeCounts(s, 3).collect().toMap,
      s.communityOutcomes,
      s.componentIterations,
      s.corenessRounds
    )
    val a = withStructure(ok(compute(hand)))(snapshot)
    val b = withStructure(ok(compute(hand, partitions = 3, reverse = true)))(snapshot)
    assertEquals(b, a)
  }

  test("the iteration bound: windows not converged within community.maxIterations are recorded") {
    withStructure(ok(compute(hand, maxIterations = 2))) { s =>
      assertEquals(
        s.communityOutcomes.map(o => (o.iterations, o.converged, o.lastIterationChanges)),
        Vector(
          (2, true, 0L),
          (2, false, 1L),
          (2, false, 1L),
          (2, false, 1L),
          (2, true, 0L),
          (2, false, 1L)
        )
      )
    }
  }

  test("checkpointing: every interval-th iteration is checkpointed and the directory is released") {
    val one = ok(compute(hand, interval = 1))
    // 5 component iterations + 3 propagation iterations + 5 peeling rounds.
    assertEquals(one.checkpoints, 13)
    val dir = one.checkpointDirectory.map(d => Paths.get(new URI(d)))
    assert(dir.exists(Files.isDirectory(_)), dir)
    assert(dir.exists(_.startsWith(tmp)), dir)
    assertEquals(one.release(spark.sparkContext), Vector.empty)
    assert(dir.exists(d => !Files.exists(d)), s"$dir is removed")
    // Interval 2: component iterations 2, 4; propagation 2; peeling 2, 4.
    withStructure(ok(compute(hand, interval = 2)))(s => assertEquals(s.checkpoints, 5))
  }

  test("integrity refusals: self-pair, non-canonical key, dangling endpoint, settings") {
    def refused(r: Either[Vector[String], GraphStructure], what: String) = r match {
      case Right(s) =>
        s.release(spark.sparkContext)
        fail(s"expected a refusal naming '$what'")
      case Left(e) => assert(e.exists(_.contains(what)), e)
    }
    refused(compute(hand, extraEdges = Vector((0, "a", "a"))), "1 self-pair edge keys")
    refused(compute(hand, extraEdges = Vector((0, "e", "d"))), "1 non-canonical edge keys")
    refused(
      compute(hand, extraEdges = Vector((0, "a", "x"))),
      "1 arcs whose endpoint is not a vertex"
    )
    refused(compute(hand, extraEdges = Vector((9, "a", "b"))), "1 edge keys of an unknown window")
    refused(compute(hand, maxIterations = 0), "community.maxIterations 0 is below 1")
    refused(compute(hand, interval = 0), "checkpoint interval 0 is below 1")
    val sc = spark.sparkContext
    val relative = GraphStructureJob.compute(
      sc,
      sc.parallelize(Vector((0, "a"))),
      sc.parallelize(Vector.empty[(Int, String, String)]),
      windowsFor(1),
      20,
      Checkpointing("relative/dir", 1),
      1
    )
    refused(relative, "neither an absolute local path")
  }

  // ------------------------------------------------------------ sequential references

  /** A deterministic pseudo-random graph: 60 vertices, 110 edge draws, 4 isolated vertices. */
  private val random: Graph = {
    var x = 12345L
    def next(n: Int): Int = {
      x = (x * 6364136223846793005L + 1442695040888963407L); ((x >>> 33) % n).toInt
    }
    val vs = Vector.tabulate(60)(i => f"v$i%02d")
    val es = mutable.LinkedHashSet.empty[(String, String)]
    (0 until 110).foreach { _ =>
      val a = vs(next(56))
      val b = vs(next(56))
      if (a != b) es += (if (Canonical.compareUtf8(a, b) < 0) (a, b) else (b, a))
    }
    (vs, es.toVector)
  }

  private def adjacency(g: Graph): Map[String, Set[String]] = {
    val m = mutable.HashMap.empty[String, Set[String]]
    g._1.foreach(v => m(v) = Set.empty)
    g._2.foreach { case (a, b) => m(a) = m(a) + b; m(b) = m(b) + a }
    m.toMap
  }

  private def min(xs: Iterable[String]) = xs.reduce(GraphStructureJob.minUtf8)

  private def refComponents(g: Graph): Map[String, String] = {
    val adj = adjacency(g)
    val label = mutable.HashMap.empty[String, String]
    g._1.foreach { v =>
      if (!label.contains(v)) {
        val seen = mutable.LinkedHashSet(v)
        var frontier = Vector(v)
        while (frontier.nonEmpty) {
          frontier = frontier.flatMap(adj).filterNot(seen.contains).distinct
          seen ++= frontier
        }
        val m = min(seen)
        seen.foreach(label(_) = m)
      }
    }
    label.toMap
  }

  private def refCoreness(g: Graph): Map[String, Long] = {
    val adj = adjacency(g)
    val deg = mutable.HashMap.from(adj.map { case (v, ns) => v -> ns.size })
    val core = mutable.HashMap.empty[String, Long]
    var k = 0
    while (core.size < adj.size) {
      val (v, d) = deg.filterNot(e => core.contains(e._1)).minBy(e => (e._2, e._1))
      k = math.max(k, d)
      core(v) = k.toLong
      adj(v).filterNot(core.contains).foreach(n => deg(n) = deg(n) - 1)
    }
    core.toMap
  }

  /** (labels, iterations performed, converged) of the same synchronous self-vote rule. */
  private def refPropagation(g: Graph, maxIterations: Int): (Map[String, String], Int, Boolean) = {
    val adj = adjacency(g)
    var labels = g._1.map(v => v -> v).toMap
    var i = 0
    var changed = true
    while (changed && i < maxIterations) {
      i += 1
      val next = labels.map { case (v, own) =>
        val votes = (adj(v).toVector.map(labels) :+ own).groupBy(identity).map { case (l, xs) =>
          (l, xs.size.toLong)
        }
        v -> votes.reduce(GraphStructureJob.better)._1
      }
      changed = next != labels
      labels = next
    }
    (labels, i, !changed)
  }

  test("a pseudo-random graph matches sequential reference implementations") {
    val g = random
    withStructure(ok(compute(Vector(g), partitions = 3))) { s =>
      def of[T](m: Map[(Int, String), T]) = m.map { case ((_, v), x) => v -> x }
      assertEquals(of(s.components.collect().toMap), refComponents(g))
      assertEquals(of(s.coreness.collect().toMap), refCoreness(g))
      val (lp, iterations, converged) = refPropagation(g, 20)
      assertEquals(of(s.communities.collect().toMap), lp)
      assertEquals(s.communityOutcomes.head.iterations, iterations)
      assertEquals(s.communityOutcomes.head.converged, converged)
      assert(refComponents(g).values.toSet.size > 1, "the random graph is disconnected")
      assert(refCoreness(g).values.max >= 2, "the random graph has a 2-core")
      println(
        s"SP-P6-03 step 2A random graph: ${g._1.size} vertices, ${g._2.size} edges, " +
          s"${refComponents(g).values.toSet.size} components, ${lp.values.toSet.size} communities, " +
          s"max coreness ${refCoreness(g).values.max}, propagation iterations $iterations"
      )
    }
  }
}
