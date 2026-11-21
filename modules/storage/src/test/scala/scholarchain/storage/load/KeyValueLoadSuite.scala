package scholarchain.storage.load

import scholarchain.core.*
import scholarchain.storage.Fixtures.*
import scholarchain.storage.{RunSettingsFixture, TestData, UpstreamFixture}
import scholarchain.storage.fake.*
import scholarchain.storage.model.*
import scholarchain.storage.port.{KeyQuery, KeyValueReader, KeyValueStore, SortKeyCondition}

/**
 * SE-P7-07 items 4, 5 (checklist D1-D4, N4, L2, L3; CR-P7-07 n7): the DynamoDB loader into the
 * analysis-results table.
 *
 * Stated plainly, as the human acceptance rule requires: in PRODUCTION (every check really
 * performed, upstream read by the production readers) the gate refuses `dynamodb` because
 * paths-run-chain needs the benchmark pair list, which is not in the fixtures. The first test
 * asserts that. Every other test is an IN-PACKAGE GATE-MECHANICS test: it starts from that
 * production report and marks ONLY paths-run-chain passed. Not evidence of a complete load.
 */
class KeyValueLoadSuite extends munit.FunSuite {

  private val RunId: String = scholarchain.storage.Fixtures.RunId
  private val Dynamo = TargetStore.DynamoDb
  private val Table = TableRole.ResearcherMetricCache
  private val codec = ItemCodecs.AnalysisResultCodec

  private lazy val production: VerificationReport = LoadVerifier.verify(
    source(runDir(run1Root)),
    source(loadDir(run1Root), loadBounds),
    KeyValueReader.readOnly(new InMemoryKeyValueStore(100)),
    Some(UpstreamFixture.context)
  )

  /** Gate mechanics only: the production report plus paths-run-chain, marked here. */
  private lazy val marked: VerificationReport = {
    val v = production.verified.get
    production.copy(verified =
      Some(
        new VerifiedRun(
          v.runId,
          v.partManifestSha256,
          v.partManifest,
          v.runManifest,
          v.loads,
          v.passedChecks + (Dynamo -> (v.passedFor(Dynamo) + DeferredCheck.PathsRunChain)),
          source(runDir(run1Root)),
          v.partManifestSchemaVersion
        )
      )
    )
  }

  private val limits = KeyValueLoadLimits.of(400 * 1024).toOption.get
  private lazy val items = production.verified.get.load(Dynamo).get.items
  private lazy val loadId = production.verified.get.load(Dynamo).get.loadId
  private lazy val totalRows = items.map(_.rowCount).sum

  /** A table whose every conditional write first runs `before(callsSoFar)`. */
  private final class Hooked(underlying: KeyValueStore, before: Int => Unit) extends KeyValueStore {
    var calls = 0
    def get(t: TableRole, k: PrimaryKey) = underlying.get(t, k)
    def query(t: TableRole, q: KeyQuery) = underlying.query(t, q)
    def putIfAbsent(t: TableRole, i: Item) = {
      before(calls); calls += 1; underlying.putIfAbsent(t, i)
    }
    def putIfVersion(t: TableRole, i: Item, v: Long) = underlying.putIfVersion(t, i, v)
  }

  private final class World {
    val store = new InMemoryKeyValueStore(1000)
    val table = new RecordingKeyValueStore(store)
    val ledger = new RecordingKeyValueStore(new InMemoryKeyValueStore(100))
    val clock = new ManualClock(1_700_000_000_000L)
  }

  private def load(
      w: World,
      table: KeyValueStore,
      l: KeyValueLoadLimits = limits,
      report: VerificationReport = marked
  ): Either[StoreError, KeyValueLoadReport] =
    LoadGate.proceed(report, Dynamo)(p =>
      KeyValueLoad.load(p, RunSettingsFixture.parquetBounds, l, table, w.ledger, w.clock)
    ) match {
      case Left(rs) => fail(s"gate refused: $rs")
      case Right(out) => out
    }

  private def entry(w: World): LoadEntry =
    (for {
      k <- TableKeys.RunLedger.load(RunId, loadId)
      i <- w.ledger.get(TableRole.RunLedger, k)
      e <- ItemCodecs.LoadEntryCodec.decode(i.get)
    } yield e).fold(e => fail(e.toString), identity)

  private def row(w: World, k: Either[StoreError, PrimaryKey]): AnalysisResultRecord =
    k.flatMap(w.store.get(Table, _))
      .flatMap(i => codec.decode(i.getOrElse(fail(s"no item at $k"))))
      .fold(e => fail(e.toString), identity)

  private def query(w: World, q: KeyQuery): Vector[AnalysisResultRecord] =
    w.store
      .query(Table, q)
      .flatMap(_.foldLeft[Either[StoreError, Vector[AnalysisResultRecord]]](Right(Vector.empty)) {
        (acc, i) => acc.flatMap(xs => codec.decode(i).map(xs :+ _))
      })
      .fold(e => fail(e.toString), identity)

  test("production: the gate refuses dynamodb (paths-run-chain only); nothing is written") {
    assertEquals(production.rejections, Vector.empty)
    assertEquals(production.outstandingFor(Dynamo), Vector(DeferredCheck.PathsRunChain))
    val w = new World
    val out = LoadGate.proceed(production, Dynamo)(p =>
      KeyValueLoad.load(p, RunSettingsFixture.parquetBounds, limits, w.table, w.ledger, w.clock)
    )
    assert(out.isLeft)
    assertEquals((w.table.counts.calls, w.ledger.counts.calls), (0, 0))
  }

  test("mechanics: every dynamodb row loads once, with run, load, source part and schema (L3)") {
    val w = new World
    val r = load(w, w.table).fold(e => fail(e.toString), identity)
    assertEquals(totalRows, 1302L)
    assertEquals((r.items, r.written, r.alreadyPresent), (1302, 1302L, 0L))
    assertEquals(r.finalState, LoadState.Loaded)
    assertEquals(r.rowsByRole.keySet, KeyValueLoad.RoleOf.values.toSet, "12 roles, all mapped")
    assertEquals(r.rowsByRole.size, 12)
    items.groupBy(_.targetRole).foreach { case (role, xs) =>
      assertEquals(r.rowsByRole(role).toLong, xs.map(_.rowCount).sum, role)
    }
    assertEquals(w.store.itemCount(Table), 1302)
    assertEquals(w.table.byTable.keySet, Set(Table), "only the analysis-results table")
    // Observed item sizes (reported; the limit is configuration, the AWS value UNVERIFIED).
    println(s"SE-P7-07 observed largest dynamodb item: ${r.largestItemBytes} bytes")
    assert(r.largestItemBytes < 400 * 1024)
    // SE-P7-13 item 4: the value the storage docs cite, pinned (KeyValueLoad.itemBytes estimate of
    // this fixture; DynamoDB's own size accounting is UNVERIFIED, CR-P7-09 U5).
    assertEquals(r.largestItemBytes, 3617)
    val paths = items.map(i => i.path -> i.recordSchemaVersion).toMap
    val all = KeyValueLoad
      .rows(
        LoadGate.proceed(marked, Dynamo)(p => Right(p)).toOption.get.toOption.get,
        RunSettingsFixture.parquetBounds
      )
      .fold(e => fail(e.toString), identity)
    all.foreach { x =>
      assertEquals((x.runId, x.loadId), (RunId, loadId))
      assertEquals(paths.get(x.sourcePath), Some(x.recordSchemaVersion), x.sourcePath)
      assertEquals(x.recordSha256, Canonical.sha256HexUtf8(x.record))
    }
    assertEquals(entry(w).state, LoadState.Loaded)
  }

  test("TTL: the analysis-results table has none (D4: analysis results are not disposable)") {
    assertEquals(TableSpecs.ResearcherMetricCache.ttlAttribute, None)
  }

  test("N4: bounded lookups serve every stated access pattern (no scan)") {
    val w = new World
    load(w, w.table).fold(e => fail(e.toString), identity)
    val v = production.verified.get
    val out = ParquetChecks.run(
      v.runId,
      v.runManifest,
      v.loads,
      None,
      it => Right(Bytes.of(bytes(runDir(run1Root).resolve(it.path)))), // expected values only
      RunSettingsFixture.context
    )
    val all = ParquetChecks.outputsOf(out.parts)
    val m = all.metrics.head
    val w0 = f"${m.window.startYear}%04d-${m.window.endYear}%04d"
    // MC-2: metrics of a researcher in a window (GetItem).
    val got = row(
      w,
      TableKeys.ResearcherMetricCache.subjectResult(m.researcherId, "researcher-metrics", w0, None)
    )
    assertEquals(Json.decode[ResearcherMetricsV2](got.record), Right(m))
    // MC-1: every row of the researcher (bounded query).
    val ofR = query(
      w,
      KeyQuery(None, s"subject#${m.researcherId}", SortKeyCondition.BeginsWith("metric#"), 50)
    )
    assert(ofR.map(_.role).toSet.contains("researcher-metrics"))
    assert(ofR.forall(_.scopeId == m.researcherId))
    // MC-4: pair connectivity in a window.
    val e = all.edges.head
    val ew = f"${e.window.startYear}%04d-${e.window.endYear}%04d"
    val pair = query(
      w,
      KeyQuery(
        Some("byPair"),
        s"pair#${e.researcherA}#${e.researcherB}",
        SortKeyCondition.BeginsWith(s"window#$ew#"),
        20
      )
    )
    assert(
      pair.exists(r => r.role == "coauthor-edges" && r.scopeId == e.edgeId),
      pair.map(_.scopeId).toString
    )
    // MC-8: resolve an evidence ID (the coauthor edge cited by COAUTHORED_WITH sourceEvidence).
    assertEquals(
      row(
        w,
        TableKeys.ResearcherMetricCache.subjectResult(e.edgeId, "coauthor-edges", ew, None)
      ).record,
      Json.write(e)
    )
    // MC-3: members of a community in a window.
    val c = all.communities.head
    val cw = f"${c.window.startYear}%04d-${c.window.endYear}%04d"
    val members =
      query(w, KeyQuery(Some("byGroup"), s"group#${c.communityId}#$cw", SortKeyCondition.All, 500))
    assertEquals(
      members.map(_.scopeId).toSet,
      all.communities
        .filter(x => x.communityId == c.communityId && x.window == c.window)
        .map(_.researcherId)
        .toSet
    )
    // MC-5: top-3 bridges of a window: descending crossCommunityDegree.
    val top = query(w, KeyQuery(Some("rank"), s"rank#bridge-measures#$cw", SortKeyCondition.All, 3))
    val decoded =
      top.map(t => Json.decode[BridgeMeasure](t.record).fold(x => fail(x.toString), identity))
    assertEquals(top.map(_.rank), Vector(Some(1), Some(2), Some(3)).take(top.size))
    assertEquals(
      decoded.map(_.crossCommunityDegree),
      decoded.map(_.crossCommunityDegree).sorted.reverse
    )
    assertEquals(
      decoded.headOption.map(_.crossCommunityDegree),
      all.bridges.filter(_.window == c.window).map(_.crossCommunityDegree).maxOption
    )
    // MC-6: window-level rows.
    val win = query(w, KeyQuery(None, s"window#$cw", SortKeyCondition.BeginsWith("result#"), 10))
    assert(
      win
        .map(_.role)
        .toSet
        .subsetOf(Set("analysis-windows", "window-structures", "window-comparisons"))
    )
    assert(win.exists(_.role == "analysis-windows") && win.exists(_.role == "window-structures"))
    // MC-7: a temporal change.
    val t = all.temporalChanges.head
    val tk = TableKeys.ResearcherMetricCache.subjectResult(
      t.researcherId,
      "temporal-changes",
      f"${t.fromWindow.startYear}%04d-${t.fromWindow.endYear}%04d",
      Some(f"${t.toWindow.startYear}%04d-${t.toWindow.endYear}%04d")
    )
    assertEquals(Json.decode[ResearcherTemporalChange](row(w, tk).record), Right(t))
    // Every pattern is bounded: an unbounded limit is refused by the store.
    assert(w.store.query(Table, KeyQuery(None, s"window#$cw", SortKeyCondition.All, 100000)).isLeft)
  }

  test("D3/L2: a rerun with the same ledger is skipped with zero writes") {
    val w = new World
    load(w, w.table).fold(e => fail(e.toString), identity)
    val before = w.table.counts.calls
    val again = load(w, w.table).fold(e => fail(e.toString), identity)
    assertEquals((again.skipped, again.written), (true, 0L))
    assertEquals(w.table.counts.calls, before)
  }

  test("D3: against a fresh ledger every row is AlreadyPresent (equal recordSha256)") {
    val w = new World
    load(w, w.table).fold(e => fail(e.toString), identity)
    val w2 = new World
    val r = LoadGate.proceed(marked, Dynamo)(p =>
      KeyValueLoad.load(p, RunSettingsFixture.parquetBounds, limits, w.table, w2.ledger, w2.clock)
    )
    assertEquals(r.map(_.map(x => (x.written, x.alreadyPresent))), Right(Right((0L, 1302L))))
  }

  test("D3: a different record under a key is a Conflict, never an overwrite") {
    val w = new World
    val all = LoadGate
      .proceed(marked, Dynamo)(p => KeyValueLoad.rows(p, RunSettingsFixture.parquetBounds))
      .toOption
      .get
      .toOption
      .get
    val target = all(10)
    val other = target.copy(
      record = target.record + " ",
      recordSha256 = Canonical.sha256HexUtf8(target.record + " ")
    )
    val item = codec.encode(other).fold(e => fail(e.toString), identity)
    assertEquals(w.store.putIfAbsent(Table, item), Right(PutOutcome.Written))
    load(w, w.table) match {
      case Left(StoreError.Conflict(_)) => ()
      case x => fail(s"expected Conflict, got $x")
    }
    assertEquals(codec.key(target).flatMap(w.store.get(Table, _)), Right(Some(item)), "unchanged")
    assertEquals(entry(w).state, LoadState.Failed)
  }

  test(
    "CR-P7-09 M1: another run's rows under LATE keys refuse the whole load: zero writes, failed"
  ) {
    val w = new World
    val all = LoadGate
      .proceed(marked, Dynamo)(p => KeyValueLoad.rows(p, RunSettingsFixture.parquetBounds))
      .toOption
      .get
      .toOption
      .get
    // A second, different run (other run and load IDs, other values) holding the last two keys
    // of this run's write order: the first 1300 keys are new to the table.
    val planted = all.takeRight(2).map { r =>
      val body = r.record.replace(RunId, scholarchain.storage.TestData.otherRunId)
      r.copy(
        record = body,
        recordSha256 = Canonical.sha256HexUtf8(body),
        runId = scholarchain.storage.TestData.otherRunId,
        loadId = scholarchain.storage.TestData.otherLoadId
      )
    }
    planted.foreach { r =>
      val i = codec.encode(r).fold(e => fail(e.toString), identity)
      assertEquals(w.store.putIfAbsent(Table, i), Right(PutOutcome.Written))
    }
    load(w, w.table) match {
      case Left(StoreError.Conflict(d)) => assert(d.contains("pre-write probe"), d)
      case x => fail(s"expected Conflict, got $x")
    }
    assertEquals(w.table.counts.calls, 0, "no data write was attempted")
    assertEquals(w.store.itemCount(Table), 2, "only the other run's rows")
    planted.foreach(r => assertEquals(row(w, codec.key(r)), r, "the other run's row is intact"))
    assertEquals(entry(w).state, LoadState.Failed)
  }

  test("L2: an interrupted load resumes without duplicates") {
    val w = new World
    val failing = new Hooked(
      w.store,
      n => if (n == 500) w.store.failNextWrites(StoreError.Transient("throttled"))
    )
    assertEquals(load(w, failing), Left(StoreError.Transient("throttled")))
    assertEquals(w.store.itemCount(Table), 500)
    assertEquals((entry(w).state, entry(w).written), (LoadState.Failed, 500L))
    val resumed = load(w, w.table).fold(e => fail(e.toString), identity)
    assertEquals((resumed.written, resumed.alreadyPresent), (802L, 500L))
    assertEquals(w.store.itemCount(Table), 1302)
    assertEquals((entry(w).state, entry(w).written), (LoadState.Loaded, 1302L))
  }

  test("T2: two interleaved loaders write each row once; one loaded row") {
    val w = new World
    var second: Option[Either[StoreError, KeyValueLoadReport]] = None
    val b = new RecordingKeyValueStore(w.store)
    val a = new Hooked(w.store, n => if (n == 400 && second.isEmpty) second = Some(load(w, b)))
    val first = load(w, a).fold(e => fail(e.toString), identity)
    val other = second.get.fold(e => fail(e.toString), identity)
    assertEquals((first.finalState, other.finalState), (LoadState.Loaded, LoadState.Loaded))
    assertEquals(first.written + other.written, 1302L, "each row written exactly once")
    assertEquals(w.store.itemCount(Table), 1302)
    assertEquals(entry(w).state, LoadState.Loaded)
  }

  test("T2: another _parts.json recorded for the run ID is a Conflict with zero data writes") {
    val w = new World
    ItemCodecs.RunPublicationCodec
      .encode(RunPublication(RunPublication.SchemaVersion, RunId, Run2PartsSha256, loadId))
      .flatMap(w.ledger.putIfAbsent(TableRole.RunLedger, _))
      .fold(e => fail(e.toString), identity)
    load(w, w.table) match {
      case Left(StoreError.Conflict(d)) => assert(d.contains(Run2PartsSha256), d)
      case x => fail(s"expected Conflict, got $x")
    }
    assertEquals(w.table.counts.calls, 0)
  }

  // ------------------------------------------------------------------ n7: more interleavings

  private def putEntry(w: World, e: LoadEntry, expected: Option[Long]): Unit = {
    val i = ItemCodecs.LoadEntryCodec.encode(e).fold(x => fail(x.toString), identity)
    expected match {
      case None =>
        assertEquals(w.ledger.putIfAbsent(TableRole.RunLedger, i).map(_ => ()), Right(()))
      case Some(v) => assertEquals(w.ledger.putIfVersion(TableRole.RunLedger, i, v), Right(()))
    }
  }

  private def started(w: World): LoadEntry =
    RunLedgerStates
      .start(RunId, loadId, Dynamo, PartsSha256, items.size, w.clock.nowMillis())
      .fold(e => fail(e.toString), identity)

  test("n7: another loader left the row Planned: this loader adopts it and loads") {
    val w = new World
    putEntry(w, started(w), None)
    val r = load(w, w.table).fold(e => fail(e.toString), identity)
    assertEquals((r.written, r.finalState), (1302L, LoadState.Loaded))
  }

  test("n7: another loader left the row Verified: this loader adopts it and loads") {
    val w = new World
    val s = started(w)
    putEntry(w, s, None)
    val v = RunLedgerStates
      .advance(s, LoadState.Verified, LoadCounts(0, 0, 0), w.clock.nowMillis())
      .fold(e => fail(e.toString), identity)
    putEntry(w, v, Some(s.version))
    val r = load(w, w.table).fold(e => fail(e.toString), identity)
    assertEquals((r.written, r.finalState), (1302L, LoadState.Loaded))
  }

  test("n7: the row is failed by another loader while this one writes: it still ends loaded") {
    val w = new World
    var failedIt = false
    val t = new Hooked(
      w.store,
      n =>
        if (n == 700 && !failedIt) {
          failedIt = true
          val cur = entry(w)
          assertEquals(cur.state, LoadState.Writing)
          val f = RunLedgerStates
            .advance(
              cur,
              LoadState.Failed,
              LoadCounts(cur.written, cur.alreadyPresent, cur.rejected),
              w.clock.nowMillis()
            )
            .fold(e => fail(e.toString), identity)
          putEntry(w, f, Some(cur.version))
        }
    )
    val r = load(w, t).fold(e => fail(e.toString), identity)
    assert(failedIt)
    assertEquals((r.written, r.finalState), (1302L, LoadState.Loaded))
    assertEquals(entry(w).state, LoadState.Loaded)
    assertEquals(w.store.itemCount(Table), 1302)
  }

  // ------------------------------------------------------------------ refusals

  test("an item larger than maxItemBytes refuses the load before any write") {
    val w = new World
    load(w, w.table, KeyValueLoadLimits.of(200).toOption.get) match {
      case Left(StoreError.Refused(d)) => assert(d.contains("exceeds maxItemBytes 200"), d)
      case x => fail(s"expected Refused, got $x")
    }
    assertEquals((w.table.counts.calls, w.ledger.counts.calls), (0, 0))
    assert(KeyValueLoadLimits.of(0).isLeft)
  }

  test("permits for other stores are refused by the dynamodb loader and the Neptune plan") {
    val w = new World
    val n = LoadGate.proceed(production, TargetStore.NeptuneBulkLoad)(p =>
      KeyValueLoad.load(p, RunSettingsFixture.parquetBounds, limits, w.table, w.ledger, w.clock)
    )
    assert(
      n.exists(_.left.exists(_.message.contains("the permit is for neptune-bulk-load"))),
      n.toString
    )
    val settings = NeptuneLoaderSettings
      .of("arn:aws:iam::123456789012:role/test", "us-east-1", true, LoaderParallelism.Low, true)
      .toOption
      .get
    val d =
      LoadGate.proceed(marked, Dynamo)(p => NeptuneBulkLoad.plan(p, TestData.layout, settings))
    assert(d.exists(_.left.exists(_.message.contains("the permit is for dynamodb"))), d.toString)
    assertEquals(w.table.counts.calls, 0)
  }
}
