package scholarchain.spark.graph

import java.nio.file.{Files, Path}

import org.apache.spark.sql.SparkSession

import scholarchain.core.*
import scholarchain.core.config.PathLengthSampling
import scholarchain.spark.testkit.LocalSpark

/**
 * Stored shortest paths and the sampled path-length estimate of [[PathJob]] on small hand graphs,
 * one per window index (windows never interact; letters are researcher IDs here, UTF-8 order a < b
 * < ... < z):
 * {{{
 *   w  graph                               searches (source, target) and hand result
 *   0  a-b-e, a-c-e, a-d-e (three 2-hop    (a, e): [a b e] [a c e] [a d e] in that order; k = 2
 *      ties)                               keeps the first two
 *   1  chain a-b-c-d                       (a, d): 3 hops; with maxHops 2 none, with 3 [a b c d]
 *   2  a-b and c-d (two components)        (a, c): none (different components)
 *   3  a-b-d-f, a-b-e-f, a-c-d-f           (a, f): k = 2 keeps [a b d f] [a b e f]; the prefix
 *      (prefix pruning)                    [a c] is kept after step 1 and pruned after step 2
 *   4  a-b, z absent                       (a, z): none (z is not a vertex); (b, b): none
 * }}}
 * Every result is also compared with core `AnalysisValidation.selectShortestPaths` (the executable
 * definition), including a deterministic pseudo-random graph with many pairs, at 1 and 3
 * partitions.
 *
 * Path-length (sc researcher IDs, seed 7): an exact case, the 3-path x1-x2-x3 with sample size 5
 * (every member a source: distance 1: 4 ordered pairs, distance 2: 2); and a sampled case, the
 * 6-member component of the PathFixtures 2015-2019 graph (A-B, A-C, B-D, C-D, D-E, B-I, C-I) with
 * sample size 4, whose counts are the sum of the hand distance rows of the four sources of
 * `PathSampleKey.sample`, and the 2-member component F-G below the minimum size 3 (no estimate).
 */
class PathJobSuite extends munit.FunSuite {

  private var tmp: Path = null
  private var spark: SparkSession = null
  private var runs = 0

  override def beforeAll(): Unit = {
    tmp = Files.createTempDirectory("scholarchain-paths-")
    spark = LocalSpark.start("scholarchain-paths-test", tmp.resolve("spark"))
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
    graph("a b c d e", "ab ac ad be ce de"),
    graph("a b c d", "ab bc cd"),
    graph("a b c d", "ab cd"),
    graph("a b c d e f", "ab ac bd be cd df ef"),
    graph("a b", "ab")
  )

  private def windowsFor(n: Int) = Vector.tabulate(n)(i => TimeWindow(2001 + i, 2001 + i))

  private def structure(
      gs: Vector[(Vector[String], Vector[(String, String)])],
      partitions: Int = 1,
      reverse: Boolean = false
  ): GraphStructure = {
    runs += 1
    val vs = for { (g, w) <- gs.zipWithIndex; v <- g._1 } yield (w, v)
    val es = for { (g, w) <- gs.zipWithIndex; (a, b) <- g._2 } yield {
      val (x, y) = if (Canonical.compareUtf8(a, b) < 0) (a, b) else (b, a)
      (w, x, y)
    }
    val sc = spark.sparkContext
    GraphStructureJob
      .compute(
        sc,
        sc.parallelize(if (reverse) vs.reverse else vs, partitions),
        sc.parallelize(if (reverse) es.reverse else es, partitions),
        windowsFor(gs.size),
        20,
        Checkpointing(tmp.resolve(s"checkpoints-$runs").toString, 2),
        partitions
      )
      .fold(e => fail(e.mkString("\n")), identity)
  }

  private def withStructure[T](s: GraphStructure)(body: GraphStructure => T): T =
    try body(s)
    finally assertEquals(s.release(spark.sparkContext), Vector.empty)

  private def paths(
      s: GraphStructure,
      searches: Vector[PathSearch],
      maxHops: Int,
      k: Int,
      partitions: Int = 1
  ): (Map[Int, Vector[Vector[String]]], PathSearchStats) = {
    val r = PathJob
      .shortestPaths(
        spark.sparkContext,
        s.arcs,
        s.degrees.map(_._1),
        searches,
        maxHops,
        k,
        1,
        partitions
      )
      .fold(e => fail(e.mkString("\n")), identity)
    try {
      val got = r.paths.collect().toVector.groupBy(_.search).map { case (i, ps) =>
        i -> ps.sortBy(_.ordinal).map(_.nodes)
      }
      (got, r.stats)
    } finally r.retained.foreach(_.unpersist(blocking = false))
  }

  private def adjacency(g: Graph): Map[String, Set[String]] =
    g._1
      .map(v => v -> g._2.collect { case (a, b) if a == v => b; case (a, b) if b == v => a }.toSet)
      .toMap

  private def core(g: Graph, s: PathSearch, maxHops: Int, k: Int) =
    AnalysisValidation.selectShortestPaths(adjacency(g), s.source, s.target, maxHops, k)

  private val searches = Vector(
    PathSearch(0, "a", "e"),
    PathSearch(1, "a", "d"),
    PathSearch(2, "a", "c"),
    PathSearch(3, "a", "f"),
    PathSearch(4, "a", "z"),
    PathSearch(4, "b", "b")
  )

  test("hand graphs: ties in node order, truncation to k, bound, components, absent vertex") {
    withStructure(structure(hand)) { s =>
      val v = Vector
      val (k2, stats2) = paths(s, searches, 3, 2)
      assertEquals(
        k2,
        Map(
          0 -> v(v("a", "b", "e"), v("a", "c", "e")),
          1 -> v(v("a", "b", "c", "d")),
          3 -> v(v("a", "b", "d", "f"), v("a", "b", "e", "f"))
        )
      )
      // searches 2 (different components), 4 (z absent) and 5 (b to itself) store nothing.
      assertEquals((stats2.searches, stats2.present, stats2.connected), (6, 4, 3))
      val (k3, _) = paths(s, searches, 3, 3)
      assertEquals(k3(0), v(v("a", "b", "e"), v("a", "c", "e"), v("a", "d", "e")))
      assertEquals(k3(3), v(v("a", "b", "d", "f"), v("a", "b", "e", "f"), v("a", "c", "d", "f")))
      // The hop bound: the 3-hop chain is not connected within 2 hops.
      val (h2, _) = paths(s, searches, 2, 3)
      assertEquals(h2.get(1), None)
      assertEquals(h2(0).size, 3)
      // Exactly the core definition for every search, bound and k.
      for { maxHops <- 1 to 4; k <- 1 to 3 } {
        val (got, _) = paths(s, searches, maxHops, k)
        searches.zipWithIndex.foreach { case (q, i) =>
          assertEquals(
            got.getOrElse(i, Vector.empty),
            core(hand(q.window), q, maxHops, k),
            s"$q maxHops=$maxHops k=$k"
          )
        }
      }
    }
  }

  test("three partitions and reversed input give the same stored paths") {
    val one = withStructure(structure(hand))(s => paths(s, searches, 3, 2)._1)
    val three =
      withStructure(structure(hand, partitions = 3, reverse = true))(s =>
        paths(s, searches, 3, 2, partitions = 3)._1
      )
    assertEquals(three, one)
  }

  test("a pseudo-random graph: every pair search equals selectShortestPaths") {
    val rnd = new scala.util.Random(41L)
    val names = Vector.tabulate(40)(i => f"v$i%02d")
    val edges = (for {
      i <- names.indices
      j <- (i + 1) until names.size
      if rnd.nextDouble() < 0.07
    } yield (names(i), names(j))).toVector
    val g: Graph = (names, edges)
    val qs = (for {
      i <- names.indices by 3
      j <- (i + 1) until names.size by 5
    } yield PathSearch(0, names(i), names(j))).toVector
    withStructure(structure(Vector(g), partitions = 3)) { s =>
      val (got, stats) = paths(s, qs, 5, 3, partitions = 3)
      qs.zipWithIndex.foreach { case (q, i) =>
        assertEquals(got.getOrElse(i, Vector.empty), core(g, q, 5, 3), q.toString)
      }
      println(
        s"SP-P6-03 step 2C random path graph: ${names.size} vertices, ${edges.size} edges, " +
          s"${qs.size} searches, ${stats.connected} connected, supersteps ${stats.supersteps}, " +
          s"walk steps ${stats.walkSteps}, checkpoints ${stats.checkpoints}"
      )
      assert(stats.checkpoints > 0, "interval 1: every level is checkpointed")
    }
  }

  test("refusals: a hop bound, a path bound and a checkpoint interval below 1") {
    withStructure(structure(hand)) { s =>
      val r = PathJob.shortestPaths(
        spark.sparkContext,
        s.arcs,
        s.degrees.map(_._1),
        searches,
        0,
        0,
        0,
        1
      )
      assert(r.left.exists(_.exists(_.contains("maxHops 0"))), r)
      assert(r.left.exists(_.exists(_.contains("maxPathsPerPair 0"))), r)
      assert(r.left.exists(_.exists(_.contains("interval 0"))), r)
    }
  }

  // ---------------------------------------------------------------- path-length

  private def sc(n: String): String =
    RunFixturesIds.sc(n)

  private def distances(
      gs: Vector[Graph],
      sampling: PathLengthSampling,
      partitions: Int = 1
  ): Vector[ComponentDistances] =
    withStructure(structure(gs, partitions)) { s =>
      val r = PathJob
        .componentDistances(
          spark.sparkContext,
          s.arcs,
          s.components,
          windowsFor(gs.size),
          sampling,
          1,
          partitions,
          s.vertexCount + 1
        )
        .fold(e => fail(e.mkString("\n")), identity)
      try r.components.collect().toVector.sortBy(c => (c.window, c.label))
      finally r.retained.foreach(_.unpersist(blocking = false))
    }

  private def named(vertices: Vector[String], edges: Vector[(String, String)]): Graph =
    (vertices.map(sc), edges.map { case (a, b) => (sc(a), sc(b)) })

  private val fixture2015: Graph = named(
    Vector("A", "B", "C", "D", "E", "I", "F", "G"),
    Vector(
      "A" -> "B",
      "A" -> "C",
      "B" -> "D",
      "C" -> "D",
      "D" -> "E",
      "B" -> "I",
      "C" -> "I",
      "F" -> "G"
    )
  )

  private val handRows: Map[String, Vector[Long]] = Map(
    sc("A") -> Vector(2L, 2L, 1L),
    sc("B") -> Vector(3L, 2L),
    sc("C") -> Vector(3L, 2L),
    sc("D") -> Vector(3L, 2L),
    sc("E") -> Vector(1L, 2L, 2L),
    sc("I") -> Vector(2L, 2L, 1L)
  )

  test("path length, exact case: the 3-path, every member a source") {
    val g = named(Vector("x1", "x2", "x3"), Vector("x1" -> "x2", "x2" -> "x3"))
    val got = distances(Vector(g), PathLengthSampling(5, 7L, 2))
    assertEquals(got.size, 1)
    val c = got.head
    assertEquals(c.size, 3L)
    assertEquals(
      c.sources,
      Vector("x1", "x2", "x3").map(sc).sortWith(Canonical.compareUtf8(_, _) < 0)
    )
    assertEquals(c.counts, Vector(DistanceCount(1, 4L), DistanceCount(2, 2L)))
    assertEquals(PathLengthEstimate.average(c.counts), 8.0 / 6.0)
    assertEquals(PathLengthEstimate.effectiveDiameter90(c.counts), 2)
  }

  test("path length, sampled case: four sources of six, hand rows; the small component skipped") {
    val sampling = PathLengthSampling(4, 7L, 3)
    val w = TimeWindow(2001, 2001)
    val members = Vector("A", "B", "C", "D", "E", "I").map(sc)
    val sources = PathSampleKey.sample(7L, w, members, 4).fold(e => fail(e.toString), identity)
    assertEquals(sources.size, 4)
    val rows = sources.map(handRows)
    val expected = (0 until rows.map(_.size).max).toVector.map(i =>
      DistanceCount(i + 1, rows.map(_.lift(i).getOrElse(0L)).sum)
    )
    Vector(1, 3).foreach { p =>
      val got = distances(Vector(fixture2015), sampling, p)
      assertEquals(got.size, 1, s"only the 6-member component reaches the minimum size 3 ($p)")
      assertEquals(got.head.size, 6L)
      assertEquals(got.head.sources, sources)
      assertEquals(got.head.counts, expected)
      // Independent recomputation by core's sequential breadth-first search.
      assertEquals(
        got.head.counts,
        StructureValidation.distanceCounts(adjacency(fixture2015), sources)
      )
      assertEquals(got.head.counts.map(_.pairs).sum, 4L * 5L)
    }
  }
}

/** Synthetic sc researcher IDs for the path-length cases (anchored on `t:<name>`). */
object RunFixturesIds {
  def sc(n: String): String =
    ResearcherId
      .scholarChain("v1", "dblp-pid", s"99/$n")
      .fold(e => throw new AssertionError(e.toString), identity)
}
