package scholarchain.spark.analysis

import java.nio.file.{Files, Path}

import scala.collection.immutable.SortedMap

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions.{col, lit, when}

import scholarchain.core.*
import scholarchain.core.config.{AnalysisConfig, AnalysisRunSettings}
import scholarchain.spark.graph.Checkpointing
import scholarchain.spark.input.{ReaderLimits, VerifiedArchiveReader, VerifiedInput}
import scholarchain.spark.output.NeptuneCsvParts
import scholarchain.spark.testkit.{LocalSpark, LogicalReproducibility, MetricsFixtures, RunFixtures}

/**
 * Logical reproducibility of the published edges, metrics, component and community memberships,
 * bridge measures and coreness (SP-P6-03 step 2A adds the last four; carried forward from sparkIT
 * run 04, where two runs of one run ID wrote different Parquet bytes and row equivalence was not
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

  /**
   * SP-P6-03 step 2C: the fixture settings plus the path-length keys (sample size 2, seed 11,
   * minimum component size 2), so the estimates are part of the comparison.
   */
  private val settings = RunFixtures.ok(
    AnalysisRunSettings.from(
      RunFixtures.config.copy(analysis =
        AnalysisConfig(Some(4), Some(3), Some(3), Some("parquet"), Some(2), Some(11L), Some(2))
      )
    )
  )

  private def run(out: String, partitions: Int): CollaborationRunResult = {
    val root = Files.createDirectories(tmp.resolve(out))
    AnalysisRun
      .runCollaboration(
        spark,
        AnalysisJobConfig(
          settings,
          code,
          1,
          SortedMap.empty,
          partitions,
          Checkpointing(tmp.resolve(s"checkpoints-$out").toString, 1)
        ),
        input,
        pairs,
        root
      )
      .fold(e => fail(e.mkString("\n")), identity)
  }

  private lazy val run1 = run("run-1-p1", 1)
  private lazy val run2 = run("run-2-p3", 3)
  private lazy val run3 = run("run-3-p1", 1)

  private def parts(r: CollaborationRunResult, schema: String) =
    r.published.parquetPartsOf(schema)

  /** Every Parquet dataset (SP-P6-03 step 2B adds the canonical tables and window structures). */
  private val datasets = Vector(
    SchemaVersions.CoauthorEdgeV2,
    SchemaVersions.ResearcherMetricsV2,
    SchemaVersions.ComponentMembership,
    SchemaVersions.CommunityMembership,
    SchemaVersions.BridgeMeasure,
    SchemaVersions.Coreness,
    SchemaVersions.GraphNodeV2,
    SchemaVersions.GraphEdgeV2,
    SchemaVersions.WindowStructure,
    // SP-P6-03 step 2C
    SchemaVersions.AnalysisWindow,
    SchemaVersions.EvidencePath,
    SchemaVersions.PathLengthEstimate,
    SchemaVersions.WindowComparison,
    SchemaVersions.ResearcherTemporalChange,
    SchemaVersions.SemanticSummaryInput,
    SchemaVersions.EntitySummaryInput
  )

  /** The twelve Neptune CSV datasets, compared as row multisets. */
  private val csvDatasets =
    NeptuneCsvParts.vertexDatasets.map(_._2) ++ NeptuneCsvParts.edgeDatasets.map(_._2)

  test("the three runs have one run ID; the 3-partition run writes more than one part") {
    assertEquals(run2.runId, run1.runId)
    assertEquals(run3.runId, run1.runId)
    assertEquals((run1.edgeCount, run1.metricsCount), (8L, 9L))
    assertEquals(
      (run1.componentCount, run1.communityCount, run1.bridgeCount, run1.corenessCount),
      (9L, 9L, 9L, 9L)
    )
    assertEquals(run2.structureSummaries, run1.structureSummaries)
    assert(run1.checkpoints > 0, "interval 1: every iteration is checkpointed")
    assert(
      parts(run2, SchemaVersions.CoauthorEdgeV2).size > 1,
      parts(run2, SchemaVersions.CoauthorEdgeV2)
    )
  }

  test("all sixteen Parquet datasets: same schema and same row multiset across partitionings") {
    assertEquals(datasets.size, 16)
    datasets.foreach(s => assert(parts(run1, s).nonEmpty, s"$s is published"))
    // Every step-2C dataset holds records here (so equality is not vacuous).
    assertEquals(
      (
        run1.step2C.pathCount,
        run1.step2C.pathLength.map(_._1),
        run1.step2C.temporalChangeCount,
        run1.step2C.summaryInputCount,
        run1.step2C.entitySummaryCount
      ),
      (1L, Some(2L), 6L, 14L, 1L)
    )
    // The load manifests name other part paths per partitioning, but the same roles per store.
    def roles(r: CollaborationRunResult) =
      r.step2C.loads.manifests.map(m => (m.targetStore, m.items.map(_.targetRole).toSet))
    assertEquals(roles(run2), roles(run1))
    datasets.foreach { s =>
      val r = LogicalReproducibility.compare(spark, parts(run1, s), parts(run2, s))
      assert(r.equal, s"$s run 1 vs run 2 (1 vs 3 partitions):\n${r.describe}")
      val r3 = LogicalReproducibility.compare(spark, parts(run1, s), parts(run3, s))
      assert(r3.equal, s"$s run 1 vs run 3:\n${r3.describe}")
      println(
        s"SP-P6-03 step 2B reproducibility $s: rows=${r.rowsA} logicalEqual(p1,p3)=${r.equal} " +
          s"logicalEqual(p1,p1)=${r3.equal} sameBytes(p1,p1)=${r3.sameBytes} sameBytes(p1,p3)=${r.sameBytes} (diagnosis only)"
      )
    }
  }

  test("all twelve Neptune CSV datasets: same header and same row multiset across partitionings") {
    // One CSV file per partition of the sorted records: 1 part at 1 partition; at 3 partitions
    // as many as the range partitioner made (an empty partition holds the header only).
    val split = csvDatasets.map(n => run2.published.datasetParts(n).size)
    assert(split.exists(_ > 1), s"the 3-partition run splits the CSV files: $split")
    csvDatasets.foreach { n =>
      val a = run1.published.datasetParts(n)
      val b = run2.published.datasetParts(n)
      assertEquals(a.size, 1, n)
      val r = LogicalReproducibility.compareCsv(spark, a, b)
      assert(r.equal, s"$n run 1 vs run 2 (1 vs 3 partitions):\n${r.describe}")
      val r3 = LogicalReproducibility.compareCsv(spark, a, run3.published.datasetParts(n))
      assert(r3.equal, s"$n run 1 vs run 3:\n${r3.describe}")
      println(
        s"SP-P6-03 step 2B reproducibility csv $n: rows=${r.rowsA} logicalEqual(p1,p3)=${r.equal} " +
          s"logicalEqual(p1,p1)=${r3.equal} sameBytes(p1,p1)=${r3.sameBytes} (diagnosis only)"
      )
    }
  }

  test("negative control (CSV): a changed row is reported exactly") {
    val a = LogicalReproducibility.readCsv(
      spark,
      run1.published.datasetParts("researcher-vertices")
    )
    val changed = a.withColumn(
      "memberCount:Int",
      when(col("~id") === lit(MetricsFixtures.rF), lit("2")).otherwise(col("memberCount:Int"))
    )
    val (_, _, onlyA, onlyB) = LogicalReproducibility.compareFrames(a, changed)
    assertEquals((onlyA.size, onlyB.size), (1, 1))
    assert(onlyA.head.contains(MetricsFixtures.rF), onlyA)
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
