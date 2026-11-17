package scholarchain.spark.testkit

import java.nio.file.{Files, Path, Paths}

import scala.collection.immutable.SortedMap

import org.apache.spark.sql.SparkSession

import scholarchain.core.*
import scholarchain.spark.analysis.{
  AnalysisJobConfig,
  AnalysisRun,
  BenchmarkPairs,
  CollaborationRunResult
}
import scholarchain.spark.graph.{MetricsWindowSummary, WindowInputSummary}
import scholarchain.spark.input.{ArchiveRuns, ReaderLimits, VerifiedArchiveReader, VerifiedInput}
import scholarchain.spark.output.{CoauthorEdgeRows, ResearcherMetricsRows, RunPublisher}

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
 * 1974..2026, no unresolved mention) for the two windows of the committed dev configuration. Step
 * 1B adds researcher metrics v2: per-window totals and four researchers are hand-derived from the
 * same teams; the identity confidences are computed only (printed) and checked by core
 * `AnalysisValidation.checkIdentity` against the archived grouping and decision runs. The dev graph
 * has no unresolved mention, so the unresolved fields are 0 here; the unresolved cases are covered
 * by the routine fixture suite (ResearcherMetricsJobSuite).
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
  private var result: CollaborationRunResult = null
  private var edges: Vector[CoauthorEdgeV2] = null
  private var metrics: Vector[ResearcherMetricsV2] = null
  private var pairs: BenchmarkPairList = null
  private var cfg: AnalysisJobConfig = null

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
    // The run itself sets the `outputs` parameter (coauthor-edges,researcher-metrics).
    cfg = AnalysisJobConfig(
      LocalSpark.devSettings(),
      UnpinnedCode,
      1,
      SortedMap("codeVersionStatus" -> "unpinned-sparkit-dev"),
      1
    )
    result = AnalysisRun
      .runCollaboration(spark, cfg, input, pairs, out)
      .fold(e => fail(e.mkString("\n")), identity)
    edges = spark.read
      .schema(CoauthorEdgeRows.schema)
      .parquet(result.published.partsOf(SchemaVersions.CoauthorEdgeV2).map(_.toUri.toString)*)
      .collect() // dev only: 259 edges; production code never collects edges
      .toVector
      .map(r => CoauthorEdgeRows.fromRow(r).fold(fail(_), identity))
    metrics = spark.read
      .schema(ResearcherMetricsRows.schema)
      .parquet(
        result.published.partsOf(SchemaVersions.ResearcherMetricsV2).map(_.toUri.toString)*
      )
      .collect() // dev only: 176 records; production code never collects metrics
      .toVector
      .map(r => ResearcherMetricsRows.fromRow(r).fold(fail(_), identity))
    println(
      s"SP-P6-02 sparkIT: analysisRunId=${result.runId} dir=${result.published.dir} edges=${result.edgeCount} " +
        s"metrics=${result.metricsCount} configDigest=${cfg.settings.configDigest} " +
        s"benchmarkPairsDigest=${BenchmarkPairList.digest(pairs)}"
    )
    result.summaries.foreach(s => println(s"SP-P6-02 sparkIT window: $s"))
    result.metricsSummaries.foreach(s => println(s"SP-P6-02 sparkIT metrics window: $s"))
    // Computed only (not hand-derived): the identity status distribution per window.
    metrics
      .groupBy(m => (m.window, m.identity.status.tag, m.identity.lowestDecisionConfidence.kind.tag))
      .toVector
      .map { case (k, v) => (k._1.startYear, k._1.endYear, k._2, k._3, v.size) }
      .sorted
      .foreach(x => println(s"SP-P6-02 sparkIT identity (computed only): $x"))
  }

  private def metric(r: String, w: TimeWindow): Option[ResearcherMetricsV2] =
    metrics.find(m => m.researcherId == r && m.window == w)

  private val W1 = TimeWindow(1970, 2026)
  private val W2 = TimeWindow(2020, 2024)

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

  test(
    "the produced edges and metrics pass AnalysisValidation (edges, metrics, identity, run chain)"
  ) {
    val settings = LocalSpark.devSettings()
    assertEquals(AnalysisValidation.checkEdges(edges, input.graphInput, settings), Vector.empty)
    assertEquals(AnalysisValidation.checkMetrics(metrics, input.graphInput, settings), Vector.empty)
    assertEquals(
      AnalysisValidation.checkIdentity(
        metrics,
        input.graphInput.researchers,
        input.decisions,
        input.grouping.manifest
      ),
      Vector.empty
    )
    val outputs = AnalysisOutputs(
      metrics,
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
    assertEquals(
      pm.files
        .filter(_.recordSchemaVersion == SchemaVersions.ResearcherMetricsV2)
        .map(_.rowCount)
        .sum,
      112L + 64L
    )
    assertEquals(
      result.published.manifest.parameters.get(AnalysisRun.OutputsParameter),
      Some(AnalysisRun.Outputs)
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

  /**
   * Hand-derived from the 41 archived teams (all with a year, no unresolved mention, so teamSize =
   * identified researchers). Team sizes: 1 team of 1, 12 of 2, 10 of 3, 10 of 4, 8 of 5 in
   * 1970-2026; 1, 8, 5, 4 and 6 in 2020-2024. Sum P = sum of team sizes (135; 78). Sum T = sum
   * n(n-1) (2 * 182 = 364; 2 * 107 = 214). U = 0. Sum W = 2 * the joint-publication sum (364; 214).
   * Sum C = 2 * edges (326; 192). Records = identified researchers (112; 64). Max M = 5 - 1.
   */
  test("metrics totals of the dev graph per window (hand-derived)") {
    assertEquals(
      result.metricsSummaries,
      Vector(
        MetricsWindowSummary(W1, 112, 135, 364, 0, 0, 326, 364, 4),
        MetricsWindowSummary(W2, 64, 78, 214, 0, 0, 192, 214, 4)
      )
    )
    assertEquals(result.metricsCount, 176L)
    assertEquals(metrics.size, 176)
  }

  /**
   * Hand-derived researcher values (archived teams file, rows 3, 10, 11, 19, 21, 26, 33):
   *   - orcid 0000-0002-2664-4694: only the solo work jikm/Thabtah20 (2020): P 1, T 0, A 0.0, M 0,
   *     C 0, W 0, in both windows;
   *   - sc 6ce3728b...: siggraph/AlemparteCSW74 (1974) and cg/AlemparteCSW75 (1975), each with the
   *     same 4 researchers: P 2, T 6, A 3.0, M 3 on both works, C 3, W 6; no 2020-2024 record;
   *   - orcid 0000-0001-5952-232X: apcc/LuYJWL17 (2017, 5 authors) and spawc/0003WJSZ20 (2020, 5
   *     authors), one shared coauthor (0000-0003-0271-6021): 1970-2026 P 2, T 8, A 4.0, M 4 on
   *     both, C 7, W 8; 2020-2024 P 1, T 4, A 4.0, M 4, C 4, W 4;
   *   - orcid 0000-0003-3922-8419: espmm/DaissSDKP22 and europar/DiehlMBGK23 (5 authors each), one
   *     shared coauthor (0000-0002-8712-2806): P 2, T 8, A 4.0, M 4 on both, C 7, W 8 in both.
   */
  test("hand-derived metrics of four dev researchers (solo, one window only, both windows)") {
    def check(r: String, w: TimeWindow, v: (Long, Long, Double, Long, Long, Long), works: String*) =
      metric(r, w) match {
        case None => fail(s"$r has no metrics record in $w")
        case Some(m) =>
          assertEquals(
            (
              m.publicationCount,
              m.totalCoauthorOccurrences,
              m.averageCoauthorsPerPaper,
              m.maximumCoauthorsOnOnePaper,
              m.distinctCoauthorCount,
              m.weightedDegree
            ),
            v,
            s"$r in $w"
          )
          assertEquals(m.maximumTeamWorkIds, works.toVector, s"$r in $w")
          assertEquals(
            (m.unresolvedCoauthorOccurrences, m.worksWithUnresolvedCoauthors),
            (0L, 0L)
          )
      }
    val solo = "researcher:orcid:0000-0002-2664-4694"
    Vector(W1, W2).foreach(w =>
      check(solo, w, (1L, 0L, 0.0, 0L, 0L, 0L), "work:dblp:journals/jikm/Thabtah20")
    )
    val sig = "researcher:sc:v1:6ce3728b5a83a2eef01abc6d96fc08a8200d3535ec9ed2e1a79128a39cd4ddc3"
    check(
      sig,
      W1,
      (2L, 6L, 3.0, 3L, 3L, 6L),
      "work:dblp:conf/siggraph/AlemparteCSW74",
      "work:dblp:journals/cg/AlemparteCSW75"
    )
    assertEquals(metric(sig, W2), None)
    val lu = "researcher:orcid:0000-0001-5952-232X"
    check(
      lu,
      W1,
      (2L, 8L, 4.0, 4L, 7L, 8L),
      "work:dblp:conf/apcc/LuYJWL17",
      "work:dblp:conf/spawc/0003WJSZ20"
    )
    check(lu, W2, (1L, 4L, 4.0, 4L, 4L, 4L), "work:dblp:conf/spawc/0003WJSZ20")
    val diehl = "researcher:orcid:0000-0003-3922-8419"
    Vector(W1, W2).foreach(w =>
      check(
        diehl,
        w,
        (2L, 8L, 4.0, 4L, 7L, 8L),
        "work:dblp:conf/espmm/DaissSDKP22",
        "work:dblp:conf/europar/DiehlMBGK23"
      )
    )
  }

  /**
   * Carried forward from sparkIT run 04: the archived input is built a second time in this run with
   * 3 partitions into `outputDir/repro-partitions-3`, and both published datasets must have the
   * same run ID, the same StructType and the same complete row multiset (duplicates counted) as the
   * first build. Byte equality is printed as a diagnosis only.
   */
  test("logical reproducibility on the archived input: two builds, same schema and row multiset") {
    val root = Files.createDirectories(Paths.get(prop("outputDir")).resolve("repro-partitions-3"))
    val again = AnalysisRun
      .runCollaboration(spark, cfg.copy(partitions = 3), input, pairs, root)
      .fold(e => fail(e.mkString("\n")), identity)
    assertEquals(again.runId, result.runId)
    Vector(SchemaVersions.CoauthorEdgeV2, SchemaVersions.ResearcherMetricsV2).foreach { s =>
      val r = LogicalReproducibility.compare(
        spark,
        result.published.partsOf(s),
        again.published.partsOf(s)
      )
      println(
        s"SP-P6-02 sparkIT reproducibility $s: rows=${r.rowsA}/${r.rowsB} sameSchema=${r.sameSchema} " +
          s"logicalEqual=${r.equal} sameBytes=${r.sameBytes} (bytes: diagnosis only)"
      )
      assert(r.equal, s"$s:\n${r.describe}")
    }
  }
}
