package scholarchain.spark.analysis

import java.nio.file.{Files, Path}

import scala.collection.immutable.SortedMap

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.{col, lit, when}

import scholarchain.core.*
import scholarchain.spark.input.{ReaderLimits, VerifiedArchiveReader, VerifiedInput}
import scholarchain.spark.testkit.{LocalSpark, LogicalReproducibility, MetricsFixtures, RunFixtures}

/**
 * Logical reproducibility of the published edges and metrics (carried forward from sparkIT run 04,
 * where two runs of one run ID wrote different Parquet bytes and row equivalence was not
 * established). The same verified fixture input is built and published three times, in separate
 * runs into separate output roots: run 1 with 1 partition, run 2 with 3 partitions (so every
 * shuffle, the input splits and the number of output parts differ), run 3 with 1 partition again.
 * Each pair of runs must have the same run ID, the same StructType read back from the files, and
 * the same complete row multiset with duplicate counts ([[LogicalReproducibility]]). A negative
 * control shows that the comparison reports a changed row and a changed duplicate count exactly.
 *
 * Byte equality is printed as a diagnosis only (run 1 against run 3: same partitioning), never
 * asserted.
 */
class LogicalReproducibilitySuite extends munit.FunSuite {

  private var tmp: Path = null
  private var spark: SparkSession = null
  private var input: VerifiedInput = null
  private var g: String = null

  private val limits = ReaderLimits(1000, 6, 10000, 1L << 20, 1L << 16)
  private val code = "fedcba9876543210fedcba9876543210fedcba98"

  override def beforeAll(): Unit = {
    tmp = Files.createTempDirectory("scholarchain-repro-")
    spark = LocalSpark.start("scholarchain-repro-test", tmp.resolve("spark"))
    val w = RunFixtures.write(tmp.resolve("fixture"), MetricsFixtures.spec())
    g = w.groupingRunId
    input = VerifiedArchiveReader.read(w.runs, limits).fold(r => fail(r.mkString("\n")), identity)
  }

  override def afterAll(): Unit =
    try if (spark != null) spark.stop()
    finally LocalSpark.deleteTree(tmp)

  private def pairs = RunFixtures
    .benchmarkPairs(g)
    .copy(pairs =
      Vector(MetricsFixtures.rA, MetricsFixtures.rD)
        .sortWith((a, b) => Canonical.compareUtf8(a, b) < 0) match {
        case Vector(a, b) => Vector(BenchmarkPair(a, b))
        case _ => Vector.empty
      }
    )

  private def run(out: String, partitions: Int): CollaborationRunResult = {
    val root = Files.createDirectories(tmp.resolve(out))
    AnalysisRun
      .runCollaboration(
        spark,
        AnalysisJobConfig(RunFixtures.settings, code, 1, SortedMap.empty, partitions),
        input,
        pairs,
        root
      )
      .fold(e => fail(e.mkString("\n")), identity)
  }

  private lazy val run1 = run("run-1-p1", 1)
  private lazy val run2 = run("run-2-p3", 3)
  private lazy val run3 = run("run-3-p1", 1)

  private def parts(r: CollaborationRunResult, schema: String) = r.published.partsOf(schema)

  private val datasets = Vector(SchemaVersions.CoauthorEdgeV2, SchemaVersions.ResearcherMetricsV2)

  test("the three runs have one run ID; the 3-partition run writes more than one part") {
    assertEquals(run2.runId, run1.runId)
    assertEquals(run3.runId, run1.runId)
    assertEquals((run1.edgeCount, run1.metricsCount), (8L, 9L))
    assert(
      parts(run2, SchemaVersions.CoauthorEdgeV2).size > 1,
      parts(run2, SchemaVersions.CoauthorEdgeV2)
    )
  }

  test("edges and metrics: same schema and same row multiset across partitionings") {
    datasets.foreach { s =>
      val r = LogicalReproducibility.compare(spark, parts(run1, s), parts(run2, s))
      assert(r.equal, s"$s run 1 vs run 2 (1 vs 3 partitions):\n${r.describe}")
      val r3 = LogicalReproducibility.compare(spark, parts(run1, s), parts(run3, s))
      assert(r3.equal, s"$s run 1 vs run 3:\n${r3.describe}")
      println(
        s"SP-P6-02 step 1B reproducibility $s: rows=${r.rowsA} logicalEqual(p1,p3)=${r.equal} " +
          s"logicalEqual(p1,p1)=${r3.equal} sameBytes(p1,p1)=${r3.sameBytes} sameBytes(p1,p3)=${r.sameBytes} (diagnosis only)"
      )
    }
  }

  test("negative control: a changed row and a duplicated row are reported exactly") {
    val a =
      spark.read.parquet(parts(run1, SchemaVersions.ResearcherMetricsV2).map(_.toUri.toString)*)
    val changed = a.withColumn(
      "publicationCount",
      when(col("researcherId") === lit(MetricsFixtures.rF), lit(2L))
        .otherwise(col("publicationCount"))
    )
    val (_, _, onlyA, onlyB) = LogicalReproducibility.compareFrames(a, changed)
    assertEquals((onlyA.size, onlyB.size), (1, 1))
    assert(onlyA.head.contains(MetricsFixtures.rF) && onlyB.head.contains(MetricsFixtures.rF))
    // Same distinct rows, one duplicated: only the multiplicity differs (1 vs 2).
    val dup = a.union(a.filter(col("researcherId") === lit(MetricsFixtures.rF)))
    val (na, nd, dupA, dupB) = LogicalReproducibility.compareFrames(a, dup)
    assertEquals((na, nd), (9L, 10L))
    assertEquals((dupA.size, dupB.size), (1, 1))
    assert(dupA.head.endsWith(",1]") && dupB.head.endsWith(",2]"), s"$dupA $dupB")
  }
}
