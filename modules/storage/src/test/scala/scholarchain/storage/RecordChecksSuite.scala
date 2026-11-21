package scholarchain.storage

import java.nio.file.Path

import scholarchain.core.*
import scholarchain.core.config.AnalysisRunSettings
import scholarchain.storage.fake.*
import scholarchain.storage.load.*
import scholarchain.storage.model.*
import scholarchain.storage.port.KeyValueReader
import Fixtures.*

/**
 * SE-P7-06 items 2-5 and 8: the record-level checks performed by the PRODUCTION verifier
 * ([[LoadVerifier.verify]] with a [[VerificationContext]]) on the real fixture bytes, and the
 * production gate. Nothing here marks a check passed by hand: every pass below is what the verifier
 * itself recorded after running the check.
 */
class RecordChecksSuite extends munit.FunSuite {

  private val Neptune = TargetStore.NeptuneBulkLoad

  private def ledger: KeyValueReader = KeyValueReader.readOnly(new InMemoryKeyValueStore(100))

  private def verify(
      run: Path,
      load: Path,
      ctx: Option[VerificationContext] = Some(RunSettingsFixture.context)
  ): VerificationReport =
    LoadVerifier.verify(source(run), source(load, loadBounds), ledger, ctx)

  private lazy val report = verify(runDir(run1Root), loadDir(run1Root))

  test("the test settings are the fixture run's configuration (config digest)") {
    assertEquals(RunSettingsFixture.settings.configDigest, RunSettingsFixture.RunConfigDigest)
    val rm = report.verified.get.runManifest
    assertEquals(rm.configDigest, RunSettingsFixture.RunConfigDigest)
  }

  test("the real fixture passes every record-level check the run directory allows") {
    import DeferredCheck.*
    assertEquals(report.rejections, Vector.empty)
    val v = report.verified.getOrElse(fail("verified"))
    assertEquals(
      TargetStore.values.toVector.map(t => t -> v.passedFor(t)).toMap,
      Map(
        Neptune -> Set(RowContracts, GraphIds, GraphIdParity, EvidenceTrace),
        TargetStore.DynamoDb -> Set(ParquetRows, RowContracts, EvidenceTrace),
        TargetStore.S3 -> Set(ParquetRows, RowContracts, GraphIdParity, EvidenceTrace),
        TargetStore.S3Vectors -> Set(ParquetRows, RowContracts)
      )
    )
    // Without the upstream (this context has none), every upstream-dependent check stays open.
    assertEquals(
      TargetStore.values.toVector.map(t => t -> report.outstandingFor(t)).toMap,
      Map(
        Neptune -> Vector(AnalysisRecompute, CanonicalTables),
        TargetStore.DynamoDb -> Vector(AnalysisRecompute, PathsRunChain),
        TargetStore.S3 -> Vector(
          SummaryEvidence,
          AnalysisRecompute,
          CanonicalTables,
          PathsRunChain
        ),
        TargetStore.S3Vectors -> Vector(SummaryEvidence)
      )
    )
  }

  test("verifying with a context twice gives an identical report") {
    assertEquals(verify(runDir(run1Root), loadDir(run1Root)), report)
  }

  test("without a context only steps 1-11 run: neptune keeps parity and evidence-trace open") {
    val r = verify(runDir(run1Root), loadDir(run1Root), None)
    assertEquals(r.rejections, Vector.empty)
    assertEquals(
      r.outstandingFor(Neptune),
      Vector(
        DeferredCheck.GraphIdParity,
        DeferredCheck.EvidenceTrace,
        DeferredCheck.AnalysisRecompute,
        DeferredCheck.CanonicalTables
      )
    )
    assert(!r.acceptedFor(Neptune))
  }

  // ------------------------------------------------------------------ the production gate

  private final class World {
    val graph = new InMemoryGraphStore
    val kv = new RecordingKeyValueStore(new InMemoryKeyValueStore(100))
    val clock = new ManualClock(1_700_000_000_000L)
  }

  /** SE-P7-07: the production report with the upstream read by the production readers. */
  private lazy val fullReport: VerificationReport =
    verify(runDir(run1Root), loadDir(run1Root), Some(UpstreamFixture.context))

  test("item 8: neptune-bulk-load proceeds through the production gate and loads 459 + 799") {
    assert(
      !this.report.acceptedFor(Neptune),
      "without the upstream neptune is refused (CR-P7-08 M2)"
    )
    val report = fullReport
    assert(report.acceptedFor(Neptune))
    val w = new World
    val out =
      LoadGate.proceed(report, Neptune)(p => GraphLoad.load(p, w.graph, w.kv, w.clock)) match {
        case Left(rs) => fail(s"gate refused: $rs")
        case Right(r) => r.fold(e => fail(e.toString), identity)
      }
    assertEquals((out.vertices, out.edges, out.written), (459, 799, 1258L))
    assertEquals((w.graph.vertexIds.size, w.graph.edgeIds.size), (459, 799))
    assertEquals(out.finalState, LoadState.Loaded)
    // Rerun through the same gate: zero new writes.
    val again = LoadGate.proceed(report, Neptune)(p => GraphLoad.load(p, w.graph, w.kv, w.clock))
    assertEquals(again.map(_.map(r => (r.written, r.skipped))), Right(Right((0L, true))))
    assertEquals(w.graph.executedTemplates.size, 1258)
  }

  test("item 8: dynamodb, s3 and s3-vectors stay refused, naming exactly what is open") {
    TargetStore.values.toVector.foreach { t =>
      var called = false
      val out = LoadGate.proceed(report, t) { _ =>
        called = true; Right(())
      }
      assert(!called, t.tag)
      assertEquals(
        out.left.toOption.map(_.map(_.check)),
        Some(Vector(VerificationCheck.DeferredChecks)),
        t.tag
      )
      assert(
        out.left.toOption.get.head.error.message
          .endsWith(report.outstandingFor(t).map(_.tag).mkString(", ")),
        t.tag
      )
    }
  }

  // ------------------------------------------------------------------ failures through the verifier

  private def assertRefused(
      r: VerificationReport,
      check: VerificationCheck,
      fragment: String
  ): Unit = {
    assert(r.verified.isEmpty, "nothing is verified")
    assert(r.rejections.nonEmpty, "rejected")
    assert(
      r.rejections.exists(j =>
        j.check == check && (j.subject + " " + j.error.message).contains(fragment)
      ),
      s"expected $check with '$fragment' in:\n" + r.rejections.take(10).mkString("\n")
    )
    TargetStore.values.foreach(t => assert(!r.acceptedFor(t), t.tag))
  }

  test("graph-id-parity: a CSV count that differs from the graph-edges record is refused") {
    withCopy { (run, load) =>
      val path = "coauthored-with-edges/part-00000.csv"
      resealCsv(run, load, path) { text =>
        val lines = text.split("\n", -1).toVector
        val f = lines(1).split(",", -1)
        // header: ~id,~from,~to,~label,firstCollaborationYear,jointPublicationCount,...
        f(5) = (f(5).toInt + 1).toString
        (lines.updated(1, f.mkString(","))).mkString("\n")
      }
      assertRefused(verify(run, load), VerificationCheck.GraphIdParity, s"$path#1")
    }
  }

  test("parquet-rows: a part whose bytes are another schema's layout is refused") {
    withCopy { (run, load) =>
      val other = bytes(run.resolve("bridges/part-00000.parquet"))
      reseal(run, load, "edges/part-00000.parquet", other, 98L)
      assertRefused(
        verify(run, load),
        VerificationCheck.ParquetRowCount,
        "edges/part-00000.parquet"
      )
    }
  }

  test("row-contracts: a part duplicated over another changes the window structures' counts") {
    withCopy { (run, load) =>
      val other = bytes(run.resolve("edges/part-00001.parquet"))
      reseal(run, load, "edges/part-00000.parquet", other, 122L)
      assertRefused(verify(run, load), VerificationCheck.ParquetRowContract, "edgeCount")
    }
  }

  test("run-settings: settings of another configuration are refused before any record check") {
    val other = AnalysisRunSettings
      .from(
        RunSettingsFixture.config
          .copy(graph = RunSettingsFixture.config.graph.copy(policyVersion = "sc-graph.v2"))
      )
      .fold(e => fail(e.toString), identity)
    val r = verify(
      runDir(run1Root),
      loadDir(run1Root),
      Some(VerificationContext(other, RunSettingsFixture.parquetBounds, None))
    )
    assertEquals(r.rejections.map(_.check), Vector(VerificationCheck.RunSettings))
    TargetStore.values.foreach(t => assert(!r.acceptedFor(t), t.tag))
  }

  test("tight Parquet bounds refuse the run (parquet-rows), they are never skipped") {
    val tight = scholarchain.storage.parquet.ParquetReadBounds
      .of(1024 * 1024, 1L << 24, 10L, 1 << 20)
      .toOption
      .get
    val r = verify(
      runDir(run1Root),
      loadDir(run1Root),
      Some(VerificationContext(RunSettingsFixture.settings, tight, None))
    )
    assertRefused(r, VerificationCheck.ParquetRowCount, "exceeds maxRowsPerPart 10")
  }

  // ------------------------------------------------------------------ the checks on changed inputs

  private lazy val v = report.verified.get
  private def readItem(it: LoadItem): Either[StoreError, Bytes] =
    Right(Bytes.of(bytes(runDir(run1Root).resolve(it.path))))
  private lazy val graph: NeptuneGraph =
    NeptuneRows
      .parse(
        v.load(Neptune).get,
        Fixtures.RunId,
        p => Right(Bytes.of(bytes(runDir(run1Root).resolve(p))))
      )
      .fold(ps => fail(ps.toString), identity)

  private def checks(rm: RunManifest): RecordCheckOutcome =
    ParquetChecks.run(
      Fixtures.RunId,
      rm,
      v.loads,
      Some(graph),
      readItem,
      RunSettingsFixture.context
    )

  test("control: the checks called directly on the fixture find nothing") {
    val out = checks(v.runManifest)
    assertEquals(out.rejections, Vector.empty)
    assertEquals(out.parts.size, 29)
    import DeferredCheck.*
    assertEquals(
      out.passed,
      Map(
        Neptune -> Set(GraphIdParity, EvidenceTrace),
        TargetStore.DynamoDb -> Set(ParquetRows, RowContracts, EvidenceTrace),
        TargetStore.S3 -> Set(ParquetRows, RowContracts, GraphIdParity, EvidenceTrace),
        TargetStore.S3Vectors -> Set(ParquetRows, RowContracts)
      )
    )
  }

  test("row-contracts: every record's lineage must equal the run manifest's run chain") {
    val out = checks(v.runManifest.copy(codeVersion = "0" * 40))
    assert(out.rejections.nonEmpty)
    assert(
      out.rejections.forall(_.check == VerificationCheck.ParquetRowContract),
      out.rejections.take(3).toString
    )
    assert(out.rejections.exists(_.error.message.contains("lineage.codeVersion")))
    assertEquals(out.passed, Map.empty[TargetStore, Set[DeferredCheck]])
  }

  test("row-contracts: window structures must equal the manifest's community outcome") {
    val k = AnalysisValidation.AnalysisManifestKeys.communityIterations(TimeWindow(1970, 2026))
    val rm = v.runManifest.copy(parameters = v.runManifest.parameters.updated(k, "4"))
    val out = checks(rm)
    assert(
      out.rejections.exists(r =>
        r.check == VerificationCheck.ParquetRowContract && r.error.message.contains(k)
      ),
      out.rejections.take(5).toString
    )
  }

  test("row-contracts: a provenance run other than the verified run is refused") {
    val out = ParquetChecks.run(
      "run:" + "1" * 64,
      v.runManifest,
      v.loads,
      Some(graph),
      readItem,
      RunSettingsFixture.context
    )
    assert(
      out.rejections.exists(_.error.message.contains("provenance.runId is not the verified run"))
    )
  }

  // ------------------------------------------------------------------ summary-evidence (item 6)

  private val noGraphInput = GraphInput(Vector.empty, Vector.empty, Vector.empty)

  test("upstream-binding: an upstream that is not the run's grouping run is refused") {
    val K = AnalysisValidation.AnalysisManifestKeys
    val rm = v.runManifest.copy(parameters =
      v.runManifest.parameters.updated(K.GroupingRunId, "run:" + "2" * 64)
    )
    // Through the verifier, row-contracts already refuses such a manifest (every lineage names
    // the real grouping run), so the binding itself is shown on the binding function.
    val out =
      ParquetChecks.run(Fixtures.RunId, rm, v.loads, Some(graph), readItem, UpstreamFixture.context)
    assert(out.rejections.nonEmpty)
    assertEquals(out.passed, Map.empty[TargetStore, Set[DeferredCheck]])
    assertEquals(SummaryEvidenceCheck.bound(UpstreamFixture.upstream, v.runManifest), Vector.empty)
    val problems = SummaryEvidenceCheck.bound(UpstreamFixture.upstream, rm)
    assert(
      problems.exists(_.contains("is not the analysis run's groupingRunId")),
      problems.toString
    )
    val otherInput = v.runManifest.copy(parameters =
      v.runManifest.parameters.updated(K.InputRunId, "run:" + "3" * 64)
    )
    assert(
      SummaryEvidenceCheck
        .bound(UpstreamFixture.upstream, otherInput)
        .exists(_.contains("is not the analysis run's inputRunId"))
    )
  }

  test("CR-P7-08 m2: no caller can construct an upstream; only the verified-run readers can") {
    assert(
      compileErrors(
        "new scholarchain.storage.load.SummaryUpstream(null, null, None)"
      ).nonEmpty
    )
  }

  test("summary-evidence runs the core validators: the fixture's inputs cite records absent here") {
    val all = ParquetChecks.outputsOf(checks(v.runManifest).parts)
    assertEquals((all.summaryInputs.size, all.entitySummaryInputs.size) != ((0, 0)), true)
    val problems = SummaryEvidenceCheck.check(
      SummaryEvidenceInputs(
        all.summaryInputs,
        all,
        noGraphInput,
        all.entitySummaryInputs,
        all.nodes,
        all.graphEdges,
        Vector.empty,
        Vector.empty,
        RunSettingsFixture.settings
      )
    )
    // Without the grouping and curated records, cited counted works and source works resolve to
    // nothing: the check fails closed. With them (new fixtures), it decides.
    assert(problems.nonEmpty)
    assert(problems.exists(_.message.contains("resolves to no")), problems.take(5).toString)
  }
}
