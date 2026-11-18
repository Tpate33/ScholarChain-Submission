package scholarchain.spark.graph

import java.nio.file.{Files, Path}

import scala.collection.immutable.SortedMap

import org.apache.spark.sql.SparkSession

import scholarchain.core.*
import scholarchain.spark.analysis.{AnalysisJobConfig, AnalysisRun, CollaborationRunResult}
import scholarchain.spark.input.{ReaderLimits, VerifiedArchiveReader, VerifiedInput}
import scholarchain.spark.output.{CoauthorEdgeRows, ResearcherMetricsRows, RunPublisher}
import scholarchain.spark.testkit.{LocalSpark, MetricsFixtures, RunFixtures}
import scholarchain.spark.testkit.MetricsFixtures.*

/**
 * Researcher metrics v2 (contract C1) and identity confidence (C5) on the hand-checkable
 * [[MetricsFixtures]] chain: every published value equals the hand-derived table in the fixture's
 * comment, the records pass `AnalysisValidation.checkMetrics`, `checkIdentity`, `checkEdges` and
 * `checkRunChain`, and counter-tests show that merging unresolved occurrences into identified
 * counts, a wrong identity, or a missing supporting decision is detected or refused. DEV fixture,
 * unevaluated. The driver collects at most nine fixture records (test only).
 */
class ResearcherMetricsJobSuite extends munit.FunSuite {

  private var tmp: Path = null
  private var spark: SparkSession = null
  private var written: RunFixtures.Written = null
  private var input: VerifiedInput = null

  private val limits = ReaderLimits(1000, 6, 10000, 1L << 20, 1L << 16)
  private val settings = RunFixtures.settings
  private val code = "89abcdef0123456789abcdef0123456789abcdef"
  private def cfg = AnalysisJobConfig(
    settings,
    code,
    1,
    SortedMap.empty,
    1,
    Checkpointing(tmp.resolve("checkpoints").toString, 2)
  )

  override def beforeAll(): Unit = {
    tmp = Files.createTempDirectory("scholarchain-metrics-")
    spark = LocalSpark.start("scholarchain-metrics-test", tmp.resolve("spark"))
    written = RunFixtures.write(tmp.resolve("fixture"), MetricsFixtures.spec())
    input =
      VerifiedArchiveReader.read(written.runs, limits).fold(r => fail(r.mkString("\n")), identity)
  }

  override def afterAll(): Unit =
    try if (spark != null) spark.stop()
    finally LocalSpark.deleteTree(tmp)

  private def pairs(g: String) = RunFixtures
    .benchmarkPairs(g)
    .copy(
      pairs = Vector(BenchmarkPair(rA, rD)).map(p =>
        if (Canonical.compareUtf8(p.researcherA, p.researcherB) < 0) p
        else BenchmarkPair(p.researcherB, p.researcherA)
      )
    )

  private def run(out: String, in: VerifiedInput, g: String) = {
    val root = Files.createDirectories(tmp.resolve(out))
    AnalysisRun.runCollaboration(spark, cfg, in, pairs(g), root)
  }

  private lazy val result: CollaborationRunResult =
    run("out", input, written.groupingRunId).fold(e => fail(e.mkString("\n")), identity)

  private lazy val metrics: Vector[ResearcherMetricsV2] = spark.read
    .schema(ResearcherMetricsRows.schema)
    .parquet(
      result.published.partsOf(SchemaVersions.ResearcherMetricsV2).map(_.toUri.toString)*
    )
    .collect()
    .toVector
    .map(r => ResearcherMetricsRows.fromRow(r).fold(fail(_), identity))

  private lazy val edges: Vector[CoauthorEdgeV2] = spark.read
    .schema(CoauthorEdgeRows.schema)
    .parquet(result.published.partsOf(SchemaVersions.CoauthorEdgeV2).map(_.toUri.toString)*)
    .collect()
    .toVector
    .map(r => CoauthorEdgeRows.fromRow(r).fold(fail(_), identity))

  private def outputs = AnalysisOutputs(
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

  test("the metrics row layout has the record's JSON keys, in order, at every level") {
    def keys(v: ujson.Value) = v match {
      case o: ujson.Obj => o.value.keys.toVector
      case _ => Vector.empty
    }
    val m = metrics.head
    val json = ujson.read(Json.write(m))
    assertEquals(ResearcherMetricsRows.schema.fieldNames.toVector, keys(json))
    assertEquals(ResearcherMetricsRows.IdentityType.fieldNames.toVector, keys(json("identity")))
    assertEquals(
      ResearcherMetricsRows.DecisionConfidenceType.fieldNames.toVector,
      keys(json("identity")("lowestDecisionConfidence"))
    )
  }

  test("one metrics record per researcher and window with a counted work in the window") {
    val got = metrics.map(m => (m.window, m.researcherId)).toSet
    assertEquals(got, expected.keySet)
    assertEquals(metrics.size, 9)
    assertEquals(result.metricsCount, 9L)
    assert(!metrics.exists(_.researcherId == rG), "G is only on the yearless m8")
    assert(
      !metrics.exists(m => m.researcherId == rD && m.window == RunFixtures.window1),
      "D has no counted work in 2015-2019"
    )
  }

  test("every metric equals the hand-derived value (P, C, T, U, Wu, A, M, max works, W)") {
    metrics.foreach { m =>
      val e = expected((m.window, m.researcherId))
      val got = Expected(
        m.publicationCount,
        m.distinctCoauthorCount,
        m.totalCoauthorOccurrences,
        m.unresolvedCoauthorOccurrences,
        m.worksWithUnresolvedCoauthors,
        m.averageCoauthorsPerPaper,
        m.maximumCoauthorsOnOnePaper,
        m.maximumTeamWorkIds,
        m.weightedDegree
      )
      assertEquals(got, e, s"${m.researcherId} in ${m.window}")
    }
    // A_r is IEEE-754 T_r / P_r exactly (7 / 3 for B and D in 2020-2024).
    val b2 = metrics.find(m => m.researcherId == rB && m.window == RunFixtures.window2).get
    assertEquals(b2.averageCoauthorsPerPaper, ResearcherMetricsV2.average(7L, 3L))
    assertEquals(b2.averageCoauthorsPerPaper, 2.3333333333333335)
  }

  test("unresolved coauthors are kept in their own fields and never counted as identified") {
    // E's only coauthors on m3 are two unresolved mentions: T 2, U 2, C 0, W 0.
    val e = metrics.find(_.researcherId == rE).get
    assertEquals(
      (e.totalCoauthorOccurrences, e.unresolvedCoauthorOccurrences, e.distinctCoauthorCount),
      (2L, 2L, 0L)
    )
    assertEquals(e.weightedDegree, 0L)
    // A solo work: F has P 1, T 0, M 0 attained by m4, A_r 0.0.
    val f = metrics.find(_.researcherId == rF).get
    assertEquals(
      (f.publicationCount, f.totalCoauthorOccurrences, f.maximumCoauthorsOnOnePaper),
      (1L, 0L, 0L)
    )
    assertEquals((f.averageCoauthorsPerPaper, f.maximumTeamWorkIds), (0.0, Vector(m4)))
    // W_r = T_r - unresolved on every record, and no edge touches an unresolved mention.
    metrics.foreach(m =>
      assertEquals(m.weightedDegree, m.totalCoauthorOccurrences - m.unresolvedCoauthorOccurrences)
    )
    val endpoints = edges.flatMap(e => Vector(e.researcherA, e.researcherB)).toSet
    assert(endpoints.subsetOf(Set(rA, rB, rC, rD)), endpoints)
    assertEquals(edges.size, 8)
  }

  test("identity confidence is carried from the grouping and decision runs, unknown explicit") {
    metrics.foreach(m => assertEquals(m.identity, expectedIdentity(m.researcherId), m.researcherId))
    val f = metrics.find(_.researcherId == rF).get.identity.lowestDecisionConfidence
    assertEquals((f.kind, f.value), (ConfidenceKind.Unknown, None))
    assertEquals(f.unknownReason, Some(ConfidenceUnknownReason.NoSupportingDecision))
  }

  test("the metrics pass AnalysisValidation: checkMetrics, checkIdentity, checkEdges, run chain") {
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
    assertEquals(AnalysisValidation.checkEdges(edges, input.graphInput, settings), Vector.empty)
    assertEquals(
      AnalysisValidation.checkRunChain(
        result.published.manifest,
        input.grouping.manifest,
        outputs,
        settings,
        pairs(written.groupingRunId)
      ),
      Vector.empty
    )
    metrics.foreach { m =>
      assertEquals(m.lineage, result.lineage)
      assertEquals(m.provenance.runId, result.runId)
      assertEquals(m.provenance.sourceRecordId, m.researcherId)
      assertEquals(m.provenance.policyVersion, Some(settings.graphPolicyVersion))
    }
  }

  test("counter-tests: merged unresolved counts or a changed identity are detected") {
    val b2 = metrics.find(m => m.researcherId == rB && m.window == RunFixtures.window2).get
    // Counting B's unresolved coauthor u9a as an identified coauthor: C 4, W 7.
    val merged = b2.copy(distinctCoauthorCount = 4L, weightedDegree = 7L)
    assert(ResearcherMetricsV2.contract.validate(merged).exists(_.toString.contains("W_r")))
    val swapped = metrics.map(m => if (m == b2) b2.copy(distinctCoauthorCount = 4L) else m)
    val errs = AnalysisValidation.checkMetrics(swapped, input.graphInput, settings)
    assert(errs.exists(_.toString.contains("distinctCoauthorCount")), errs)
    // P_r that included A's uncounted copy mc1 is detected.
    val a1 = metrics.find(m => m.researcherId == rA && m.window == RunFixtures.window1).get
    val inflated = metrics.map(m =>
      if (m == a1) a1.copy(publicationCount = 3L, averageCoauthorsPerPaper = 5.0 / 3.0) else m
    )
    val pErrs = AnalysisValidation.checkMetrics(inflated, input.graphInput, settings)
    assert(pErrs.exists(_.toString.contains("publicationCount")), pErrs)
    // A default identity instead of the derived one is detected.
    val defaulted = metrics.map(m =>
      if (m == b2)
        b2.copy(identity =
          b2.identity.copy(lowestDecisionConfidence = DecisionConfidence.measured(1.0))
        )
      else m
    )
    val idErrs = AnalysisValidation.checkIdentity(
      defaulted,
      input.graphInput.researchers,
      input.decisions,
      input.grouping.manifest
    )
    assert(idErrs.exists(_.toString.contains("identity")), idErrs)
  }

  test("per-window metric totals equal the hand-derived totals") {
    assertEquals(
      result.metricsSummaries,
      Vector(
        MetricsWindowSummary(RunFixtures.window1, 5, 6, 12, 8, 5, 4, 4, 3),
        MetricsWindowSummary(RunFixtures.window2, 4, 10, 24, 4, 4, 12, 20, 3)
      )
    )
    // Team-level unresolved mentions of the windows: m1 1 + m2 2 + m3 2 and m7 1 + m9 1.
    assertEquals(result.summaries.map(_.unresolvedMentionOccurrences), Vector(5L, 2L))
    assertEquals(result.summaries.map(_.edges), Vector(2L, 6L))
    assertEquals(result.summaries.map(_.jointPublications), Vector(2L, 10L))
  }

  test("one run publishes every dataset (Parquet and Neptune CSV) with the outputs parameter") {
    val pm = result.published.partManifest
    val node = SchemaVersions.GraphNodeV2
    val edge = SchemaVersions.GraphEdgeV2
    // SP-P6-03 step 2B adds the canonical tables, their twelve Neptune CSV datasets and the
    // window structures to the six step-2A datasets; step 2C adds the analysis windows, evidence
    // paths, window comparisons, temporal changes and both summary-input datasets (no path-length
    // estimate: the fixture configuration sets no analysis.pathLength* key).
    assertEquals(
      pm.files
        .map(f => (f.path.takeWhile(_ != '/'), f.recordSchemaVersion, f.format))
        .distinct
        .sortBy(_._1),
      Vector(
        ("_run-manifest.json", SchemaVersions.RunManifest, PartFormat.Jsonl),
        ("affiliated-with-edges", edge, PartFormat.Csv),
        ("analysis-windows", SchemaVersions.AnalysisWindow, PartFormat.Parquet),
        ("author-mention-vertices", node, PartFormat.Csv),
        ("authored-edges", edge, PartFormat.Csv),
        ("bridges", SchemaVersions.BridgeMeasure, PartFormat.Parquet),
        ("coauthored-with-edges", edge, PartFormat.Csv),
        ("communities", SchemaVersions.CommunityMembership, PartFormat.Parquet),
        ("components", SchemaVersions.ComponentMembership, PartFormat.Parquet),
        ("coreness", SchemaVersions.Coreness, PartFormat.Parquet),
        ("edges", SchemaVersions.CoauthorEdgeV2, PartFormat.Parquet),
        ("entity-summary-inputs", SchemaVersions.EntitySummaryInput, PartFormat.Parquet),
        ("evidence-paths", SchemaVersions.EvidencePath, PartFormat.Parquet),
        ("graph-edges", edge, PartFormat.Parquet),
        ("graph-nodes", node, PartFormat.Parquet),
        ("has-topic-edges", edge, PartFormat.Csv),
        ("institution-vertices", node, PartFormat.Csv),
        ("metrics", SchemaVersions.ResearcherMetricsV2, PartFormat.Parquet),
        ("published-in-edges", edge, PartFormat.Csv),
        ("researcher-vertices", node, PartFormat.Csv),
        ("resolves-to-edges", edge, PartFormat.Csv),
        ("summary-inputs", SchemaVersions.SemanticSummaryInput, PartFormat.Parquet),
        ("temporal-changes", SchemaVersions.ResearcherTemporalChange, PartFormat.Parquet),
        ("topic-vertices", node, PartFormat.Csv),
        ("venue-vertices", node, PartFormat.Csv),
        ("window-comparisons", SchemaVersions.WindowComparison, PartFormat.Parquet),
        ("window-structures", SchemaVersions.WindowStructure, PartFormat.Parquet),
        ("work-vertices", node, PartFormat.Csv)
      )
    )
    assert(
      pm.files.filter(_.format == PartFormat.Csv).forall(_.rowCountSource == RowCountSource.Writer)
    )
    assertEquals(
      pm.files
        .filter(_.recordSchemaVersion == SchemaVersions.ResearcherMetricsV2)
        .map(_.rowCount)
        .sum,
      9L
    )
    assertEquals(
      result.published.manifest.parameters.get(AnalysisRun.OutputsParameter),
      Some(AnalysisRun.Outputs)
    )
    assert(
      RunPublisher
        .verifyPublished(
          spark,
          result.published.dir.localPath,
          result.runId,
          limits.maxMetadataBytes
        )
        .isRight
    )
  }

  test("the caller may not replace the outputs parameter") {
    val root = Files.createDirectories(tmp.resolve("out-clash"))
    val clash = cfg.copy(extraParameters = SortedMap(AnalysisRun.OutputsParameter -> "edges"))
    val r = AnalysisRun.runCollaboration(spark, clash, input, pairs(written.groupingRunId), root)
    assert(r.left.exists(_.exists(_.contains("may not replace"))), r)
  }

  test("a supporting decision missing from the decision run refuses the run (no default)") {
    val w = RunFixtures.write(
      tmp.resolve("fixture-missing"),
      MetricsFixtures.spec(decisionsWithout = Set("metrics dB"))
    )
    val in =
      VerifiedArchiveReader.read(w.runs, limits).fold(r => fail(r.mkString("\n")), identity)
    val r = run("out-missing", in, w.groupingRunId)
    assert(r.left.exists(_.exists(m => m.contains(rB) && m.contains("has no confidence"))), r)
    assertEquals(Files.list(tmp.resolve("out-missing")).count(), 0L, "nothing is published")
  }
}
