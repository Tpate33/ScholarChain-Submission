package scholarchain.spark.testkit

import java.nio.file.{Files, Path, Paths}

import scala.collection.immutable.SortedMap

import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.sql.types.StructType

import scholarchain.core.*
import scholarchain.spark.analysis.{
  AnalysisJobConfig,
  AnalysisRun,
  BenchmarkPairs,
  CollaborationRunResult
}
import scholarchain.spark.graph.{Checkpointing, MetricsWindowSummary, WindowInputSummary}
import scholarchain.spark.input.{ArchiveRuns, ReaderLimits, VerifiedArchiveReader, VerifiedInput}
import scholarchain.spark.input.HadoopCuratedRun
import scholarchain.spark.store.StorePath
import scholarchain.spark.output.{
  AnalysisWindowRows,
  BridgeMeasureRows,
  CoauthorEdgeRows,
  CommunityMembershipRows,
  ComponentMembershipRows,
  CorenessRows,
  EntitySummaryInputRows,
  EvidencePathRows,
  GraphEdgeRows,
  GraphNodeRows,
  NeptuneCsvParts,
  PathLengthEstimateRows,
  ResearcherMetricsRows,
  RunPublisher,
  SummaryInputRows,
  TemporalChangeRows,
  WindowComparisonRows,
  WindowStructureRows
}

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
 *
 * SP-P6-03 step 2A adds component memberships, community memberships, bridge measures and coreness.
 * Hand-derived (from the step-1B facts above): one record per metrics record in each dataset (112
 * and 64); the structure's per-window researcher and edge counts equal the verified 112/163 and
 * 64/96; the solo researcher 0000-0002-2664-4694 is a singleton component and community with
 * coreness 0 and bridge counts 0 in both windows; sc 6ce3728b... (in 1970-2026 only on the same
 * 4-person team twice, C 3) has coreness 3 = degree 3 (a K4, and coreness <= degree); orcid
 * 0000-0001-5952-232X in 2020-2024 (one 5-author work, C 4) has coreness 4 = degree 4 (a K5); each
 * window holds a 5-author team, so its maximum coreness is at least 4. Everything else (component,
 * community and coreness distributions, label-propagation iterations) is computed only, printed,
 * and checked by `AnalysisValidation.checkComponents`, `checkCommunities`, `checkBridges` and
 * `checkCoreness`, which recompute components, bridge counts and coreness exactly.
 *
 * SP-P6-03 step 2B adds the curated sources (the consumed normalize leaves, crc-verified and
 * decoded, equal to the archived EMITTED counters), the canonical tables (node and edge counts tied
 * to the dev-run-01 grouping diagnostics and the verified counts; the rest computed only and
 * checked by `CanonicalGraphValidation.checkStructure` and `checkTables`), the Neptune CSV parts,
 * and the window structures (researchers and edges tied to 112/163 and 64/96; the rest computed
 * only and checked by `StructureValidation.checkWindowStructures`). The reproducibility test covers
 * every Parquet dataset and every Neptune CSV dataset (row multisets).
 *
 * SP-P6-03 step 2C adds the analysis windows, stored evidence paths, path-length estimates (the dev
 * configuration sets sample size 8, seed 20261117, minimum component size 3), the window comparison
 * and temporal changes, the semantic-summary and entity-summary inputs and the load manifests:
 * hand-tied where derivable from the facts above (see that test), otherwise computed only, printed
 * and checked by the core validators.
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
   * a.md; the same ID as build-engineer's `SparkItRuns.PinnedCuratedRunHex`), restated by hand.
   * CR-P6-21 n1: the reader's pin is the main-code constant `HadoopCuratedRun.PinnedRunId`
   * (`ArchiveRuns.of` takes no pin); the first test asserts that both are this literal.
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
  private var components: Vector[ComponentMembership] = null
  private var communities: Vector[CommunityMembership] = null
  private var bridges: Vector[BridgeMeasure] = null
  private var coreness: Vector[Coreness] = null
  private var pairs: BenchmarkPairList = null
  private var cfg: AnalysisJobConfig = null

  private var runsPin: String = null

  override def beforeAll(): Unit = {
    val runs = ArchiveRuns
      .of(
        prop("archiveRoot"),
        prop("curatedRun"),
        prop("decisionRun"),
        prop("groupingRun")
      )
      .fold(e => fail(e.mkString("\n")), identity)
    runsPin = runs.pinnedCuratedRunId
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
    // The run itself sets the `outputs` parameter (six datasets) and the computed
    // label-propagation outcome parameters (community.*). Checkpoints go under the suite's own
    // temporary directory, inside java.io.tmpdir (the attempt's tmp/), never under outputDir.
    cfg = AnalysisJobConfig(
      LocalSpark.devSettings(),
      UnpinnedCode,
      1,
      SortedMap("codeVersionStatus" -> "unpinned-sparkit-dev"),
      1,
      Checkpointing(tmp.resolve("checkpoints").toString, 2)
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
    // dev only: 176 records each; production code never collects these datasets
    components = readAll(
      SchemaVersions.ComponentMembership,
      ComponentMembershipRows.schema,
      ComponentMembershipRows.fromRow
    )
    communities = readAll(
      SchemaVersions.CommunityMembership,
      CommunityMembershipRows.schema,
      CommunityMembershipRows.fromRow
    )
    bridges =
      readAll(SchemaVersions.BridgeMeasure, BridgeMeasureRows.schema, BridgeMeasureRows.fromRow)
    coreness = readAll(SchemaVersions.Coreness, CorenessRows.schema, CorenessRows.fromRow)
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
    // Computed only (not hand-derived), checked by AnalysisValidation: the structure totals.
    result.structureSummaries.foreach { s =>
      println(
        s"SP-P6-03 sparkIT structure window (computed only, checked by validator): window=${s.window.startYear}-${s.window.endYear} " +
          s"researchers=${s.researchers} edges=${s.edges} components=${s.components} largestComponent=${s.largestComponent} " +
          s"largestComponentFraction=${s.largestComponentFraction} singletonComponents=${s.singletonComponents} " +
          s"communities=${s.communities} largestCommunity=${s.largestCommunity} singletonCommunities=${s.singletonCommunities} " +
          s"crossCommunityEdges=${s.crossCommunityEdges} maxCoreness=${s.maxCoreness} " +
          s"corenessHistogram=${s.corenessHistogram.map { case (k, n) => s"$k:$n" }.mkString(",")} " +
          s"labelPropagation=${s.labelPropagation.iterations}/${s.labelPropagation.converged}/${s.labelPropagation.lastIterationChanges}"
      )
    }
    println(
      s"SP-P6-03 sparkIT structure iterations (computed only): componentIterations=${result.componentIterations} " +
        s"corenessRounds=${result.corenessRounds} checkpoints=${result.checkpoints}"
    )
    // Computed only (step 2B), checked by StructureValidation.checkWindowStructures.
    result.windowStructures.foreach { s =>
      println(
        s"SP-P6-03 sparkIT window structure (computed only, checked by validator): " +
          s"window=${s.window.startYear}-${s.window.endYear} ordinal=${s.ordinal} " +
          s"researchers=${s.researcherCount} edges=${s.edgeCount} components=${s.componentCount} " +
          s"largest=${s.largestComponentSize} fraction=${s.largestComponentFraction} " +
          s"singletons=${s.singletonCount} communities=${s.communityCount} " +
          s"largestCommunity=${s.largestCommunitySize} crossCommunityEdges=${s.crossCommunityEdgeCount} " +
          s"iterations=${s.communityIterations} converged=${s.communityConverged} maxCoreness=${s.maxCoreness}"
      )
    }
    // Computed only: the distributions of the bridge measures per window.
    bridges
      .groupBy(b =>
        (b.window.startYear, b.window.endYear, b.crossCommunityDegree, b.communitiesReached)
      )
      .toVector
      .map { case (k, v) => (k, v.size) }
      .sorted
      .foreach(x => println(s"SP-P6-03 sparkIT bridge (computed only): $x"))
  }

  private def readAll[T](label: String, schema: StructType, from: Row => Either[String, T]) =
    spark.read
      .schema(schema)
      .parquet(result.published.parquetPartsOf(label).map(_.toUri.toString)*)
      .collect()
      .toVector
      .map(r => from(r).fold(fail(_), identity))

  // SP-P6-03 step 2B; dev only (a few hundred records each), production code never collects them.
  private lazy val nodes =
    readAll(SchemaVersions.GraphNodeV2, GraphNodeRows.schema, GraphNodeRows.fromRow)
  private lazy val graphEdges =
    readAll(SchemaVersions.GraphEdgeV2, GraphEdgeRows.schema, GraphEdgeRows.fromRow)
  private lazy val windowStructures = readAll(
    SchemaVersions.WindowStructure,
    WindowStructureRows.schema,
    WindowStructureRows.fromRow
  ).sortBy(_.ordinal)

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
    // CR-P6-21 n1: the reader's pin is the committed main-code constant, the literal above.
    assertEquals(HadoopCuratedRun.PinnedRunId, CuratedRunId)
    assertEquals(runsPin, CuratedRunId)
  }

  /**
   * SP-P6-03 step 2B. Hand-tied: the decoded curated records equal the archived
   * `normalize/_counters.json` EMITTED counters (works 90, mentions 286, venues 64, institutions
   * 60, topics 30; works 90 and mentions 286 are also the dev-run-01 grouping members, 286 mentions
   * plus 6 profiles = 292 researcher assignments), and the 143 authorship records are the grouping
   * part manifest's rows.
   */
  test("the curated sources decode with the archived EMITTED counts; 143 authorships") {
    val s = input.curatedSources
    assertEquals(
      (s.works.size, s.mentions.size, s.venues.size, s.institutions.size, s.topics.size),
      (90, 286, 64, 60, 30)
    )
    assertEquals(
      HadoopCuratedRun.ConsumedLeaves.map(input.curatedRowsOf),
      Vector(90L, 286L, 64L, 60L, 30L)
    )
    assertEquals(input.authorships.size, 143)
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
    "the produced datasets pass AnalysisValidation (edges, metrics, identity, components, communities, bridges, coreness, run chain)"
  ) {
    val settings = LocalSpark.devSettings()
    val g = input.graphInput
    assertEquals(AnalysisValidation.checkEdges(edges, g, settings), Vector.empty)
    assertEquals(AnalysisValidation.checkMetrics(metrics, g, settings), Vector.empty)
    assertEquals(AnalysisValidation.checkComponents(components, g, settings), Vector.empty)
    assertEquals(
      AnalysisValidation.checkCommunities(communities, components, settings),
      Vector.empty
    )
    assertEquals(AnalysisValidation.checkBridges(bridges, communities, g, settings), Vector.empty)
    assertEquals(AnalysisValidation.checkCoreness(coreness, g, settings), Vector.empty)
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
      components,
      communities,
      bridges,
      coreness,
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
    assertEquals(result.published.dir.getParent, StorePath.local(out))
    assertEquals(
      result.published.dir.getFileName.toString,
      "run=" + result.runId.stripPrefix("run:")
    )
    val again = RunPublisher.verifyPublished(
      spark,
      result.published.dir.localPath,
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
    Vector(
      SchemaVersions.ComponentMembership,
      SchemaVersions.CommunityMembership,
      SchemaVersions.BridgeMeasure,
      SchemaVersions.Coreness
    ).foreach { s =>
      assertEquals(pm.files.filter(_.recordSchemaVersion == s).map(_.rowCount).sum, 176L, s)
    }
    assertEquals(
      result.published.manifest.parameters.get(AnalysisRun.OutputsParameter),
      Some(AnalysisRun.OutputsWithPathLength)
    )
    Vector(W1, W2).foreach { w =>
      assert(
        result.published.manifest.parameters.contains(AnalysisRun.convergedKey(w)),
        s"the label-propagation outcome of $w is a run parameter"
      )
    }
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

  /** Hand-derived structure facts (see the class comment); the rest is computed only. */
  test("hand-derived structure of the dev graph: record counts, singletons, coreness bounds") {
    Vector(components.size, communities.size, bridges.size, coreness.size).foreach(n =>
      assertEquals(n, 176)
    )
    assertEquals(
      (result.componentCount, result.communityCount, result.bridgeCount, result.corenessCount),
      (176L, 176L, 176L, 176L)
    )
    assertEquals(
      result.structureSummaries.map(s => (s.window, s.researchers, s.edges)),
      Vector((W1, 112L, 163L), (W2, 64L, 96L))
    )
    def one[T](xs: Vector[T], r: String, w: TimeWindow)(id: T => String, win: T => TimeWindow): T =
      xs.filter(x => id(x) == r && win(x) == w) match {
        case Vector(x) => x
        case other => fail(s"$r in $w: ${other.size} records")
      }
    val solo = "researcher:orcid:0000-0002-2664-4694"
    Vector(W1, W2).foreach { w =>
      assertEquals(one(components, solo, w)(_.researcherId, _.window).componentSize, 1L)
      assertEquals(one(communities, solo, w)(_.researcherId, _.window).communitySize, 1L)
      val c = one(coreness, solo, w)(_.researcherId, _.window)
      assertEquals((c.coreness, c.degree), (0L, 0L))
      val b = one(bridges, solo, w)(_.researcherId, _.window)
      assertEquals((b.degree, b.crossCommunityDegree, b.communitiesReached), (0L, 0L, 0L))
      assertEquals((b.score, b.participationCoefficient), (0.0, 0.0))
    }
    val sig = "researcher:sc:v1:6ce3728b5a83a2eef01abc6d96fc08a8200d3535ec9ed2e1a79128a39cd4ddc3"
    val sc = one(coreness, sig, W1)(_.researcherId, _.window)
    assertEquals((sc.coreness, sc.degree), (3L, 3L))
    assert(!coreness.exists(c => c.researcherId == sig && c.window == W2))
    val lu = one(coreness, "researcher:orcid:0000-0001-5952-232X", W2)(_.researcherId, _.window)
    assertEquals((lu.coreness, lu.degree), (4L, 4L))
    Vector(W1, W2).foreach { w =>
      assert(coreness.filter(_.window == w).map(_.coreness).max >= 4L, s"a K5 lies in $w")
    }
    // Every record's degree is its C_r.
    coreness.foreach { c =>
      val m = metric(c.researcherId, c.window).getOrElse(fail(s"${c.researcherId} has no metrics"))
      assertEquals(c.degree, m.distinctCoauthorCount)
    }
    // Convergence is computed only (printed in beforeAll); a non-converged window is recorded in
    // the run manifest, not refused.
  }

  /**
   * SP-P6-03 step 2B, the canonical tables. Hand-tied (dev-run-01 grouping diagnostics and the
   * verified counts above): Researcher nodes 117 (researcherIds=117, unresolved=0, so every
   * researcher ID is identified); Work nodes 45 (canonicalWorks=45, one representative each); every
   * mention is identified and the grouping writes one authorship record per identified mention on a
   * representative, so AuthorMention nodes = RESOLVES_TO = AUTHORED = the 143 authorships (no
   * unresolved AUTHORED); COAUTHORED_WITH = the 259 verified coauthor edges; Venue 64, Institution
   * 60, Topic 30 nodes (one curated record per ID). PUBLISHED_IN, HAS_TOPIC, AFFILIATED_WITH and
   * the unmatched references are computed only (printed), checked by `CanonicalGraphValidation`.
   */
  test("canonical tables: hand-tied counts; checkStructure, checkTables and the run chain pass") {
    val byLabel = nodes.groupBy(_.label.tag).view.mapValues(_.size.toLong).toMap
    val byClass = graphEdges.groupBy(_.edgeType.tag).view.mapValues(_.size.toLong).toMap
    assertEquals(
      Vector("Researcher", "Work", "AuthorMention", "Venue", "Institution", "Topic").map(byLabel),
      Vector(117L, 45L, 143L, 64L, 60L, 30L)
    )
    assertEquals(
      Vector("AUTHORED", "RESOLVES_TO", "COAUTHORED_WITH").map(byClass),
      Vector(143L, 143L, 259L)
    )
    assertEquals(result.canonical.nodesByLabel.toMap, byLabel)
    assertEquals(result.canonical.invariantFailures.map(_._2).toSet, Set(0L))
    println(
      s"SP-P6-03 sparkIT canonical (computed only, checked by validator): nodes=${result.canonical.nodesByLabel} " +
        s"edges=${result.canonical.edgesByClass} unmatched=${result.canonical.unmatched} " +
        s"csvParts=${result.canonical.csvParts.map { case (n, k) => s"$n:$k" }.mkString(",")}"
    )
    assertEquals(CanonicalGraphValidation.checkStructure(nodes, graphEdges), Vector.empty)
    assertEquals(
      CanonicalGraphValidation.checkTables(
        nodes,
        graphEdges,
        input.canonicalInput,
        edges,
        LocalSpark.devSettings()
      ),
      Vector.empty
    )
    val pm = result.published.partManifest
    NeptuneCsvParts.vertexDatasets.foreach { case (label, name, _, _) =>
      assertEquals(
        pm.files.filter(_.path.startsWith(name + "/")).map(_.rowCount).sum,
        byLabel.getOrElse(label.tag, 0L),
        name
      )
    }
    NeptuneCsvParts.edgeDatasets.foreach { case (label, name, _, _) =>
      assertEquals(
        pm.files.filter(_.path.startsWith(name + "/")).map(_.rowCount).sum,
        byClass.getOrElse(label.tag, 0L),
        name
      )
    }
  }

  /**
   * SP-P6-03 step 2B, the window structures. Hand-tied: one record per window in configured order
   * with researchers and edges 112/163 and 64/96 (verified above) and the configured algorithm; the
   * remaining counts are computed only (printed in beforeAll), equal to the structure totals, and
   * recomputed by `StructureValidation.checkWindowStructures`, which also ties the outcome to the
   * run manifest's `community.*` parameters.
   */
  test("window structures: hand-tied counts; checkWindowStructures and the run chain pass") {
    assertEquals(
      windowStructures.map(s => (s.window, s.ordinal, s.researcherCount, s.edgeCount)),
      Vector((W1, 0, 112L, 163L), (W2, 1, 64L, 96L))
    )
    assert(windowStructures.forall(_.communityAlgorithm == "label-propagation"))
    assertEquals(windowStructures, result.windowStructures)
    val settings = LocalSpark.devSettings()
    val outputs = AnalysisOutputs(
      metrics,
      edges,
      components,
      communities,
      bridges,
      coreness,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      nodes = nodes,
      graphEdges = graphEdges,
      windowStructures = windowStructures
    )
    assertEquals(
      StructureValidation
        .checkWindowStructures(windowStructures, outputs, settings, result.published.manifest),
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

  // SP-P6-03 step 2C; dev only (at most a few hundred records each), production code never
  // collects them.
  private lazy val paths =
    readAll(SchemaVersions.EvidencePath, EvidencePathRows.schema, EvidencePathRows.fromRow)
  private lazy val estimates = readAll(
    SchemaVersions.PathLengthEstimate,
    PathLengthEstimateRows.schema,
    PathLengthEstimateRows.fromRow
  )
  private lazy val analysisWindows =
    readAll(SchemaVersions.AnalysisWindow, AnalysisWindowRows.schema, AnalysisWindowRows.fromRow)
      .sortBy(_.ordinal)
  private lazy val comparisons = readAll(
    SchemaVersions.WindowComparison,
    WindowComparisonRows.schema,
    WindowComparisonRows.fromRow
  )
  private lazy val changes = readAll(
    SchemaVersions.ResearcherTemporalChange,
    TemporalChangeRows.schema,
    TemporalChangeRows.fromRow
  )
  private lazy val summaries =
    readAll(SchemaVersions.SemanticSummaryInput, SummaryInputRows.schema, SummaryInputRows.fromRow)
  private lazy val entities = readAll(
    SchemaVersions.EntitySummaryInput,
    EntitySummaryInputRows.schema,
    EntitySummaryInputRows.fromRow
  )

  /**
   * SP-P6-03 step 2C. Hand-derived from facts verified above: analysis windows 41/24 counted works,
   * 0 without a year, 112/64 researchers, 163/96 edges, 0 unresolved occurrences; the solo
   * researcher 0000-0002-2664-4694 (no coauthor) is on no stored path, and sc 6ce3728b... (no
   * 2020-2024 record) is on no 2020-2024 path; 2020-2024 lies inside 1970-2026, so every 2020-2024
   * researcher and edge is also a 1970-2026 one: the comparison has 64 researchers in both, 48 only
   * in 1970-2026, none only in 2020-2024, 96 edges in both, 163 - 96 = 67 only in 1970-2026, none
   * only in 2020-2024, overlap 5 years, and states overlapping-windows, unequal-window-lengths and
   * grouping-unevaluated (no counted work lacks a year), plus community-not-converged exactly when
   * a window's label propagation did not converge; 112 temporal changes, none with a gained
   * coauthor; one researcher profile per metrics record (176). Everything else (paths, estimates,
   * communities summarised, entity summaries, load manifests) is computed only, printed, and
   * checked by `checkPaths`, `checkWindows`, `checkPathLengthEstimates`, `checkTemporal`,
   * `checkSummaryInputs`, `checkEntitySummaries`, `checkLoadManifests` and `checkRunChain` over
   * every dataset.
   */
  test("step 2C: paths, estimates, temporal comparison, summaries, load manifests (validators)") {
    val settings = LocalSpark.devSettings()
    val g = input.graphInput
    assertEquals(
      analysisWindows.map(w =>
        (
          w.window,
          w.countedWorkCount,
          w.countedWorksWithoutYear,
          w.researcherCount,
          w.edgeCount,
          w.unresolvedCoauthorOccurrences
        )
      ),
      Vector((W1, 41L, 0L, 112L, 163L, 0L), (W2, 24L, 0L, 64L, 96L, 0L))
    )
    val solo = "researcher:orcid:0000-0002-2664-4694"
    assert(!paths.exists(_.nodeIds.contains(solo)), "the solo researcher has no coauthor")
    val sig = "researcher:sc:v1:6ce3728b5a83a2eef01abc6d96fc08a8200d3535ec9ed2e1a79128a39cd4ddc3"
    assert(!paths.exists(p => p.window == W2 && p.nodeIds.contains(sig)))
    assert(paths.size <= pairs.pairs.size * 2 * settings.pathBounds.maxPathsPerPair)
    val c = comparisons match {
      case Vector(x) => x
      case other => fail(s"${other.size} window comparisons")
    }
    assertEquals(
      (
        c.researchersInBoth,
        c.researchersOnlyFrom,
        c.researchersOnlyTo,
        c.edgesInBoth,
        c.edgesOnlyFrom,
        c.edgesOnlyTo,
        c.overlapYears
      ),
      (64L, 48L, 0L, 96L, 67L, 0L, 5)
    )
    val converged = windowStructures.forall(_.communityConverged)
    import ComparisonLimitation.*
    assertEquals(
      c.limitations,
      (Vector(GroupingUnevaluated, OverlappingWindows, UnequalWindowLengths) ++
        Option.when(!converged)(CommunityNotConverged)).sortBy(_.tag)
    )
    assertEquals(changes.size, 112)
    assert(changes.forall(_.gainedCoauthors == 0L), "no 2020-2024 coauthor is new")
    assertEquals(
      summaries.count(_.documentType == DocumentType.ResearcherProfile),
      176
    )
    val outputs = AnalysisOutputs(
      metrics,
      edges,
      components,
      communities,
      bridges,
      coreness,
      paths,
      analysisWindows,
      summaries,
      nodes = nodes,
      graphEdges = graphEdges,
      windowStructures = windowStructures,
      pathLengthEstimates = estimates,
      windowComparisons = comparisons,
      temporalChanges = changes,
      entitySummaryInputs = entities
    )
    assertEquals(AnalysisValidation.checkPaths(paths, metrics, g, settings, pairs), Vector.empty)
    assertEquals(
      AnalysisValidation.checkWindows(analysisWindows, outputs, g, settings),
      Vector.empty
    )
    assertEquals(
      StructureValidation.checkPathLengthEstimates(estimates, g, settings),
      Vector.empty
    )
    assertEquals(
      StructureValidation.checkTemporal(comparisons, changes, outputs, g, settings),
      Vector.empty
    )
    assertEquals(
      AnalysisValidation.checkSummaryInputs(summaries, outputs, g, settings),
      Vector.empty
    )
    assertEquals(
      StructureValidation.checkEntitySummaries(
        entities,
        nodes,
        graphEdges,
        input.canonicalInput.works,
        input.canonicalInput.sourceWorks,
        settings
      ),
      Vector.empty
    )
    val loads = result.step2C.loads
    assertEquals(
      StructureValidation
        .checkLoadManifests(
          loads.manifests,
          Right(result.published.partManifest),
          loads.partManifestSha256
        ),
      Vector.empty
    )
    assertEquals(loads.manifests.map(_.targetStore).toSet, TargetStore.values.toSet)
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
    val st = result.step2C.pathStats
    println(
      s"SP-P6-03 sparkIT paths (computed only, checked by validator): stored=${paths.size} " +
        s"searches=${st.searches} present=${st.present} connected=${st.connected} supersteps=${st.supersteps} " +
        s"walkSteps=${st.walkSteps} checkpoints=${st.checkpoints} maxLevel=${st.maxLevel} maxPartialPaths=${st.maxPartialPaths} " +
        s"hops=${paths.groupBy(_.hopCount).toVector.sortBy(_._1).map { case (h, v) => s"$h:${v.size}" }.mkString(",")}"
    )
    result.step2C.pathLength.foreach { case (n, s) =>
      println(
        s"SP-P6-03 sparkIT path-length (computed only, checked by validator): estimates=$n components=${s.components} " +
          s"sources=${s.sources} supersteps=${s.supersteps} checkpoints=${s.checkpoints} maxLevel=${s.maxLevel}"
      )
    }
    estimates.sortBy(e => (e.window.startYear, -e.componentSize, e.componentId)).foreach { e =>
      println(
        s"SP-P6-03 sparkIT path-length estimate (computed only): window=${e.window.startYear}-${e.window.endYear} " +
          s"size=${e.componentSize} exact=${e.exact} sources=${e.sourceIds.size} average=${e.averageDistance} " +
          s"max=${e.maxObservedDistance} effectiveDiameter90=${e.effectiveDiameter90}"
      )
    }
    println(
      s"SP-P6-03 sparkIT temporal (hand-derived counts above): limitations=${c.limitations.map(_.tag).mkString(",")} " +
        s"changes=${changes.groupBy(_.presence.tag).toVector.sortBy(_._1).map { case (k, v) => s"$k:${v.size}" }.mkString(",")}"
    )
    println(
      s"SP-P6-03 sparkIT summaries (computed only, checked by validator): " +
        summaries
          .groupBy(_.documentType.tag)
          .toVector
          .sortBy(_._1)
          .map { case (k, v) => s"$k=${v.size}" }
          .mkString(" ") +
        " " + entities
          .groupBy(_.documentType.tag)
          .toVector
          .sortBy(_._1)
          .map { case (k, v) => s"$k=${v.size}" }
          .mkString(" ")
    )
    loads.manifests.foreach { m =>
      println(
        s"SP-P6-03 sparkIT load manifest (computed only, checked by validator): store=${m.targetStore.tag} " +
          s"items=${m.items.size} rows=${m.items.map(_.rowCount).sum} roles=${m.items.map(_.targetRole).distinct.size} " +
          s"loadId=${m.loadId}"
      )
    }
    println(
      s"SP-P6-03 sparkIT load manifests dir=${loads.dir} partManifestSha256=${loads.partManifestSha256}"
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
    assertEquals(again.structureSummaries, result.structureSummaries)
    assertEquals(again.canonical.unmatched, result.canonical.unmatched)
    assertEquals(again.windowStructures, result.windowStructures)
    assertEquals(again.step2C.analysisWindows, result.step2C.analysisWindows)
    assertEquals(again.step2C.windowComparisons, result.step2C.windowComparisons)
    assertEquals(
      again.step2C.loads.manifests.map(m => (m.targetStore, m.items.map(_.targetRole).toSet)),
      result.step2C.loads.manifests.map(m => (m.targetStore, m.items.map(_.targetRole).toSet))
    )
    Vector(
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
    ).foreach { s =>
      val r = LogicalReproducibility.compare(
        spark,
        result.published.parquetPartsOf(s),
        again.published.parquetPartsOf(s)
      )
      println(
        s"SP-P6-02 sparkIT reproducibility $s: rows=${r.rowsA}/${r.rowsB} sameSchema=${r.sameSchema} " +
          s"logicalEqual=${r.equal} sameBytes=${r.sameBytes} (bytes: diagnosis only)"
      )
      assert(r.equal, s"$s:\n${r.describe}")
    }
    // SP-P6-03 step 2B: the Neptune CSV datasets, compared as row multisets.
    (NeptuneCsvParts.vertexDatasets.map(_._2) ++ NeptuneCsvParts.edgeDatasets.map(_._2)).foreach {
      n =>
        val r = LogicalReproducibility.compareCsv(
          spark,
          result.published.datasetParts(n),
          again.published.datasetParts(n)
        )
        println(
          s"SP-P6-03 sparkIT reproducibility csv $n: rows=${r.rowsA}/${r.rowsB} " +
            s"sameHeader=${r.sameSchema} logicalEqual=${r.equal} sameBytes=${r.sameBytes} (bytes: diagnosis only)"
        )
        assert(r.equal, s"$n:\n${r.describe}")
    }
  }
}
