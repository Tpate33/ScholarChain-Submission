package scholarchain.spark.testkit

import java.nio.file.{Files, Path, Paths}

import scala.collection.immutable.SortedMap

import org.apache.spark.sql.SparkSession

import scholarchain.core.*
import scholarchain.spark.analysis.{AnalysisJobConfig, AnalysisRun, BenchmarkPairs, EdgeRunResult}
import scholarchain.spark.graph.WindowInputSummary
import scholarchain.spark.input.{ArchiveRuns, ReaderLimits, VerifiedArchiveReader, VerifiedInput}
import scholarchain.spark.output.{CoauthorEdgeRows, RunPublisher}

/**
 * The body of the first archive-backed integration suite (SP-P6-02 step 1). Abstract, so routine
 * `sbt check` compiles it (it lives in the Test tree) but never instantiates or runs it, and never
 * reads the archive; the only concrete subclass is
 * `modules/spark/src/it/scala/scholarchain/spark/DevArchiveGraphFoundationSuite.scala`, run only by
 * `sbt sparkIT`.
 *
 * DEV inputs, unevaluated: every count below is a dev diagnostic, not a quality claim.
 *
 * Input: the verified dev archive through the fixed `scholarchain.sparkit.*` system properties
 * (curated run run:2881724b..., decision run run:736cb170..., grouping run run:84dd896f...), read
 * only. Output: one published analysis run under `scholarchain.sparkit.outputDir` (the attempt's
 * `out/`). Spark local[1], UI off, loopback listeners only, every scratch directory under
 * `java.io.tmpdir` (the attempt's `tmp/`). No skip, ignore, assumption or flaky marker: a missing
 * property fails the suite.
 *
 * Expected counts were derived by hand from the archived grouping run's 41 teams (all with a year,
 * 1974..2026, no unresolved mention) for the two windows of the committed dev configuration.
 */
abstract class DevArchiveGraphFoundationChecks extends munit.FunSuite {

  private def prop(name: String): String = {
    val v = System.getProperty(s"scholarchain.sparkit.$name")
    if (v == null || v.isEmpty) fail(s"system property scholarchain.sparkit.$name is not set")
    v
  }

  private val GroupingRunId =
    "run:84dd896fda43977b934008f10955191e78c2b93b80ec57c00f55dbb137dbd7d2"
  private val DecisionRunId =
    "run:736cb1706210a34f0958e658c061054c4e6b27b80847528db8619da84495b76a"

  /**
   * The one pinned Hadoop curated dev run (human decision Option A, prompt-curated-compat-option-
   * a.md; the same ID as build-engineer's `SparkItRuns.PinnedCuratedRunHex`). It is handed to the
   * reader as the pinned curated run ID, so the HD-P6-01 curated-run contract applies to this run
   * only and any other curated run is refused (CheckStep.CuratedRun).
   */
  private val CuratedRunId =
    "run:2881724b5c86b561a235836d51946fbd884beed36a14f929b27c278cb5de8baa"

  /**
   * Envelope bounds of this dev run (test configuration, not data policy). `maxTreeDepth` bounds
   * the decision and grouping runs (unchanged); the curated run is walked to the HD-P6-01 depths
   * (files at most 5, directories at most 4 levels), with the same 20000-entry bound as the sparkIT
   * preflight.
   */
  private val limits =
    ReaderLimits(
      maxTreeEntries = 20000,
      maxTreeDepth = 4,
      maxDriverValidationRows = 2000,
      maxDriverValidationBytes = 64L * 1024 * 1024,
      maxMetadataBytes = 1024L * 1024
    )

  /** The sparkIT fork receives no commit: the all-zero code version marks the run as unpinned. */
  private val UnpinnedCode = "0" * 40

  private var tmp: Path = null
  private var spark: SparkSession = null
  private var input: VerifiedInput = null
  private var result: EdgeRunResult = null
  private var edges: Vector[CoauthorEdgeV2] = null
  private var pairs: BenchmarkPairList = null

  override def beforeAll(): Unit = {
    val runs = ArchiveRuns
      .of(
        prop("archiveRoot"),
        prop("curatedRun"),
        prop("decisionRun"),
        prop("groupingRun"),
        CuratedRunId
      )
      .fold(e => fail(e.mkString("\n")), identity)
    val out = Paths.get(prop("outputDir"))
    Files.createDirectories(out)
    tmp = Files.createTempDirectory("scholarchain-sparkit-")
    spark = LocalSpark.start("scholarchain-sparkit-dev", tmp.resolve("spark"))
    input = VerifiedArchiveReader.read(runs, limits).fold(r => fail(r.mkString("\n")), identity)
    pairs = BenchmarkPairs
      .load(
        LocalSpark.resourceText(LocalSpark.DevBenchmarkPairsResource),
        input.grouping.runId,
        BenchmarkPairs.identifiedOf(input.graphInput.researchers)
      )
      .fold(e => fail(e.mkString("\n")), identity)
    val cfg = AnalysisJobConfig(
      LocalSpark.devSettings(),
      UnpinnedCode,
      1,
      SortedMap("codeVersionStatus" -> "unpinned-sparkit-dev", "outputs" -> "coauthor-edges"),
      1
    )
    result = AnalysisRun
      .runEdges(spark, cfg, input, pairs, out)
      .fold(e => fail(e.mkString("\n")), identity)
    edges = spark.read
      .schema(CoauthorEdgeRows.schema)
      .parquet(result.published.partsOf(SchemaVersions.CoauthorEdgeV2).map(_.toUri.toString)*)
      .collect() // dev only: 259 edges; production code never collects edges
      .toVector
      .map(r => CoauthorEdgeRows.fromRow(r).fold(fail(_), identity))
    println(
      s"SP-P6-02 sparkIT: analysisRunId=${result.runId} dir=${result.published.dir} edges=${result.edgeCount} " +
        s"configDigest=${cfg.settings.configDigest} benchmarkPairsDigest=${BenchmarkPairList.digest(pairs)}"
    )
    result.summaries.foreach(s => println(s"SP-P6-02 sparkIT window: $s"))
  }

  override def afterAll(): Unit =
    try if (spark != null) spark.stop()
    finally LocalSpark.deleteTree(tmp)

  test("the verified reader accepts the archived dev chain with the pinned run IDs") {
    assertEquals(input.grouping.runId, GroupingRunId)
    assertEquals(input.decision.runId, DecisionRunId)
    assertEquals(input.curated.runId, CuratedRunId)
    assertEquals(input.curated.partManifest, None)
    assertEquals(input.curated.manifest.stage, "hadoop.pipeline")
    assertEquals(input.grouping.rowsOf(SchemaVersions.ResearcherAssignment), 292L)
    assertEquals(input.grouping.rowsOf(SchemaVersions.WorkAssignment), 90L)
    assertEquals(input.grouping.rowsOf(SchemaVersions.AuthorshipRecordV2), 143L)
    assertEquals(input.grouping.rowsOf(SchemaVersions.PublicationTeam), 41L)
    assertEquals(input.decisions.size, 374)
  }

  test("graph input: 41 counted works, 41 teams, no unresolved team mention") {
    val counted = input.graphInput.works.count(_.graphEligibility == GraphEligibility.Counted)
    assertEquals(counted, 41)
    assertEquals(AnalysisValidation.countedTeams(input.graphInput).size, 41)
    assertEquals(input.graphInput.teams.map(_.unresolvedMentionIds.size).sum, 0)
    assert(input.graphInput.teams.forall(_.year.isDefined))
  }

  test("per-window counts of the dev graph (hand-derived)") {
    assertEquals(
      result.summaries,
      Vector(
        WindowInputSummary(TimeWindow(1970, 2026), 41, 112, 0, 163, 182),
        WindowInputSummary(TimeWindow(2020, 2024), 24, 64, 0, 96, 107)
      )
    )
    assertEquals(result.edgeCount, 259L)
    assertEquals(edges.size, 259)
    assertEquals(edges.count(_.jointPublicationCount == 2L), 19 + 11)
    assertEquals(edges.map(_.jointPublicationCount).max, 2L)
  }

  test("the produced edges pass AnalysisValidation (checkEdges and checkRunChain)") {
    val settings = LocalSpark.devSettings()
    assertEquals(AnalysisValidation.checkEdges(edges, input.graphInput, settings), Vector.empty)
    val outputs = AnalysisOutputs(
      Vector.empty,
      edges,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty
    )
    assertEquals(
      AnalysisValidation.checkRunChain(
        result.published.manifest,
        input.grouping.manifest,
        outputs,
        settings,
        pairs
      ),
      Vector.empty
    )
  }

  test("the written run lies under outputDir and re-verifies against its part manifest") {
    val out = Paths.get(prop("outputDir"))
    assertEquals(result.published.dir.getParent, out)
    assertEquals(
      result.published.dir.getFileName.toString,
      "run=" + result.runId.stripPrefix("run:")
    )
    val again = RunPublisher.verifyPublished(
      spark,
      result.published.dir,
      result.runId,
      limits.maxMetadataBytes
    )
    assert(again.isRight, again)
    val pm = again.toOption.get.partManifest
    assertEquals(pm.schemaVersion, SchemaVersions.PartManifestV2)
    assertEquals(
      pm.files.filter(_.recordSchemaVersion == SchemaVersions.CoauthorEdgeV2).map(_.rowCount).sum,
      259L
    )
    assert(
      pm.files
        .filter(_.format == PartFormat.Parquet)
        .forall(_.rowCountSource == RowCountSource.ParquetFooter)
    )
  }

  test("the benchmark pair list names identified researchers of the dev grouping run") {
    assertEquals(pairs.listVersion, "benchmark-pairs-dev.v1")
    assertEquals(pairs.pairs.size, 5)
    assertEquals(pairs.provenance.runId, GroupingRunId)
    assertEquals(
      result.published.manifest.parameters.get("benchmarkPairsDigest"),
      Some(BenchmarkPairList.digest(pairs))
    )
  }
}
