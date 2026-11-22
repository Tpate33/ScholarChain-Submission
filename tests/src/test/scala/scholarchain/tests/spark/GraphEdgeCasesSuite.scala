package scholarchain.tests.spark

import java.nio.file.{Files, Path}

import org.apache.spark.sql.SparkSession

import scholarchain.core.{CanonicalPair, Canonical, TimeWindow}
import scholarchain.spark.graph.{Checkpointing, GraphStructure, GraphStructureJob}

/**
 * TE-P11-02 T18 (Phase 11 categories 6, 7, 8): the real `GraphStructureJob.compute` on degenerate
 * and inconsistent inputs, in a local Spark session of the tests tree (spark's `LocalSpark` test
 * helper is not on this classpath; the same settings are used: local[1], UI off, loopback driver,
 * Snappy for every codec because lz4 and zstd are excluded from this classpath).
 *
 * Required: an empty graph is a Right with zero counts (never an exception); a coauthor edge whose
 * endpoint is no vertex of its window (an unresolved researcher), a self-pair, a non-canonical pair
 * and an edge of an unknown window are refused with counts before any algorithm runs; a window of
 * isolated researchers has singleton components and coreness 0.
 */
class GraphEdgeCasesSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(600, "s")

  private var root: Path = null
  private var spark: SparkSession = null
  private var runs = 0

  override def beforeAll(): Unit = {
    val base = java.nio.file.Paths.get("target", "test-work").toAbsolutePath
    Files.createDirectories(base)
    root = Files.createTempDirectory(base, "te-p11-spark-")
    Files.createDirectories(root.resolve("local"))
    val compression = Vector(
      "spark.io.compression.codec" -> "snappy",
      "spark.shuffle.mapStatus.compression.codec" -> "snappy",
      "spark.eventLog.compression.codec" -> "snappy",
      "spark.sql.streaming.stateStore.compression.codec" -> "snappy",
      "spark.sql.parquet.compression.codec" -> "snappy"
    )
    spark = compression
      .foldLeft(SparkSession.builder()) { case (b, (k, v)) => b.config(k, v) }
      .master("local[1]")
      .appName("te-p11-graph-edge-cases")
      .config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", "1")
      .config("spark.default.parallelism", "1")
      .config("spark.sql.warehouse.dir", root.resolve("warehouse").toUri.toString)
      .config("spark.local.dir", root.resolve("local").toString)
      .config("spark.hadoop.hadoop.tmp.dir", root.resolve("hadoop").toString)
      .config("spark.driver.host", "127.0.0.1")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .config("spark.sql.session.timeZone", "UTC")
      .getOrCreate()
    spark.sparkContext.setLogLevel("WARN")
  }

  override def afterAll(): Unit = if (spark != null) spark.stop()

  private val W = Vector(TimeWindow(2018, 2022), TimeWindow(2019, 2019))

  private def compute(
      vertices: Vector[(Int, String)],
      edges: Vector[(Int, String, String)]
  ): Either[Vector[String], GraphStructure] = {
    runs += 1
    val sc = spark.sparkContext
    GraphStructureJob.compute(
      sc,
      sc.parallelize(vertices, 1),
      sc.parallelize(edges, 1),
      W,
      10,
      Checkpointing(root.resolve(s"checkpoints-$runs").toString, 2),
      1
    )
  }

  private def released[T](s: GraphStructure)(body: GraphStructure => T): T =
    try body(s)
    finally assertEquals(s.release(spark.sparkContext), Vector.empty)

  private val a = "researcher:sc:v1:" + "a" * 64
  private val b = "researcher:sc:v1:" + "b" * 64
  private val c = "researcher:sc:v1:" + "c" * 64
  private val ghost = "researcher:sc:v1:" + "e" * 64

  test("T18 an empty graph (no vertex, no edge): Right with zero counts, never an exception") {
    val r =
      try compute(Vector.empty, Vector.empty)
      catch { case e: Exception => fail(s"the empty graph threw $e") }
    released(r.fold(e => fail(s"empty graph refused: $e"), identity)) { s =>
      assertEquals(s.vertexCount, 0L)
      val sum = GraphStructureJob.summaries(s, GraphStructureJob.bridgeCounts(s, 1))
      assertEquals(sum.size, W.size)
      sum.foreach { x =>
        assertEquals(
          (x.researchers, x.edges, x.components, x.communities, x.maxCoreness, x.corenessHistogram),
          (0L, 0L, 0L, 0L, 0L, Vector.empty[(Long, Long)])
        )
        assertEquals(x.largestComponentFraction, 0.0)
        assert(x.labelPropagation.converged, x.labelPropagation.toString)
      }
    }
  }

  test("T18 isolated researchers only: singleton components, coreness 0, no community edge") {
    val r = compute(Vector(0 -> a, 0 -> b, 0 -> c, 1 -> a), Vector.empty)
    released(r.fold(e => fail(e.mkString("; ")), identity)) { s =>
      assertEquals(
        s.components.collect().toMap,
        Map((0, a) -> a, (0, b) -> b, (0, c) -> c, (1, a) -> a)
      )
      assert(s.coreness.collect().forall(_._2 == 0L))
      assert(s.degrees.collect().forall(_._2 == 0L))
      val sum = GraphStructureJob.summaries(s, GraphStructureJob.bridgeCounts(s, 1))
      assertEquals(
        sum.map(x => (x.researchers, x.components, x.singletonComponents)),
        Vector((3L, 3L, 3L), (1L, 1L, 1L))
      )
      assertEquals(sum.map(_.crossCommunityEdges), Vector(0L, 0L))
    }
  }

  test(
    "T18 unresolved, self-pair, non-canonical and unknown-window edges are refused with counts"
  ) {
    val vs = Vector(0 -> a, 0 -> b, 0 -> c)
    def refused(tag: String, es: Vector[(Int, String, String)], expect: String) = {
      val r = compute(vs, es)
      r match {
        case Left(ps) =>
          assert(ps.exists(_.contains(expect)), s"$tag: $ps")
          assert(ps.forall(_.startsWith("graph structure integrity: ")), s"$tag: $ps")
        case Right(s) =>
          s.release(spark.sparkContext)
          fail(s"$tag: an inconsistent edge set was accepted")
      }
    }
    assert(Canonical.compareUtf8(a, ghost) < 0 && Canonical.compareUtf8(b, a) > 0)
    // An unresolved endpoint: arcs hold both directions, so the one arc whose SOURCE is the
    // missing researcher is counted (GraphStructureJob.scala:335-336).
    refused(
      "dangling endpoint",
      Vector((0, a, b), (0, a, ghost)),
      "1 arcs whose endpoint is not a vertex"
    )
    // Both endpoints exist, but only in another window: both arcs are dangling in window 1.
    refused(
      "endpoint of another window",
      Vector((1, a, b)),
      "2 arcs whose endpoint is not a vertex"
    )
    refused("self-pair", Vector((0, a, a)), "1 self-pair edge keys")
    refused("non-canonical", Vector((0, b, a)), "1 non-canonical edge keys")
    refused("unknown window", Vector((7, a, b)), "1 edge keys of an unknown window")
    val r = compute(vs :+ (5 -> a), Vector.empty)
    r match {
      case Left(ps) => assert(ps.exists(_.contains("1 vertices of an unknown window")), ps.toString)
      case Right(s) =>
        s.release(spark.sparkContext); fail("a vertex of an unknown window was accepted")
    }
    // Control: the same vertices with a consistent edge compute.
    released(compute(vs, Vector((0, a, b))).fold(e => fail(e.mkString("; ")), identity)) { s =>
      assertEquals(s.degrees.collect().toMap.apply((0, a)), 1L)
    }
    // Duplicated identical edge keys are one edge (repeats are allowed and collapse).
    released(
      compute(vs, Vector((0, a, b), (0, a, b), (0, b, c)))
        .fold(e => fail(e.mkString("; ")), identity)
    ) { s =>
      val sum = GraphStructureJob.summaries(s, GraphStructureJob.bridgeCounts(s, 1))
      assertEquals(sum.head.edges, 2L)
    }
    // A canonical pair built by core is always accepted by the structure job's canonical check.
    val p = CanonicalPair.researchers(b, a).fold(e => fail(e.message), identity)
    assertEquals((p.first, p.second), (a, b))
  }
}
