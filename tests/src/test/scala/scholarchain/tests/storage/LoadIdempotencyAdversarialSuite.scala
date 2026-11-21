package scholarchain.tests.storage

import java.nio.file.{Files, StandardCopyOption}

import scholarchain.core.TargetStore
import scholarchain.storage.{Fixtures, OpeningFixture, TestData}
import scholarchain.storage.OpeningFixture.{World, config, sources}
import scholarchain.storage.fake.{InMemoryKeyValueStore, RecordingKeyValueStore}
import scholarchain.storage.load.*
import scholarchain.storage.model.{ItemCodecs, StoreError, StoreOwnership, TableRole}

import StorageAdversarial.*

/**
 * TE-P7-01 case 3 (checklist L2, T1): reruns and idempotency across all four stores through the
 * public [[OpeningLoad.run]]: a full load, a rerun with the same ledger (zero writes), a rerun with
 * a fresh ledger (every element already present, zero new elements), and a second, different run
 * refused by publication or by store ownership with zero writes. No second analysis-run fixture
 * exists, so "different run" is the run-2 `_parts.json` (same run ID, other bytes) and planted
 * store-owner rows.
 */
class LoadIdempotencyAdversarialSuite extends munit.FunSuite {

  private val Expected = Vector(
    TargetStore.NeptuneBulkLoad -> 1258L,
    TargetStore.DynamoDb -> 1302L,
    TargetStore.S3 -> 31L,
    TargetStore.S3Vectors -> 249L
  )

  /** One world, loaded once; the tests below run in declaration order on it. */
  private lazy val loaded: World = {
    val w = new World
    val res = OpeningFixture.run(w)
    println("TE-P7-01 full load:\n" + show(res.report))
    assert(res.report.complete, show(res.report))
    assertEquals(
      res.report.stores.map(s => (s.target, s.status, s.written, s.alreadyPresent)),
      Expected.map((t, n) => (t, StoreLoadStatus.Loaded, n, 0L))
    )
    assert(res.runtime.isDefined)
    w
  }

  private def contents(w: World) =
    (
      w.graph.store.vertices,
      w.graph.store.edges,
      w.objectStore.size,
      w.vectorCount,
      w.kvStore.itemCount(TableRole.ResearcherMetricCache),
      w.kvStore.itemCount(TableRole.ArtifactRegistry)
    )

  test("full load, then a rerun with the same ledger: skipped everywhere, zero write calls") {
    val w = loaded
    val before = contents(w)
    val callsBefore = w.dataWriteCalls
    val ledgerWritten = w.ledger.counts.written
    val ledgerRows = w.ledgerStore.itemCount(TableRole.RunLedger)
    val embedded = w.embedder.calls
    val res = OpeningFixture.run(w)
    Stores.foreach { t =>
      val s = res.report.store(t)
      assertEquals(
        (s.status, s.written, s.alreadyPresent),
        (StoreLoadStatus.Skipped, 0L, 0L),
        t.tag
      )
    }
    assertEquals(w.dataWriteCalls, callsBefore, "no write call on any data store")
    assertEquals(w.ledger.counts.written, ledgerWritten, "no ledger row created or updated")
    assertEquals(w.ledgerStore.itemCount(TableRole.RunLedger), ledgerRows)
    assertEquals(w.embedder.calls, embedded, "no embedding")
    assertEquals(contents(w), before)
    assert(res.runtime.isDefined, "a skipped (already loaded) load still hands out its view")
  }

  test("rerun against a fresh ledger: every element AlreadyPresent, zero new data elements") {
    val w = loaded
    val before = contents(w)
    val (g0, kv0, _, o0, v0, _) = written(w)
    val freshRaw = new InMemoryKeyValueStore(1000)
    val fresh = new RecordingKeyValueStore(freshRaw)
    val res = OpeningLoad.run(sources(), config, w.targets(ledgerStore = fresh))
    println("TE-P7-01 fresh-ledger rerun:\n" + show(res.report))
    assertEquals(
      res.report.stores.map(s => (s.target, s.status, s.written, s.alreadyPresent)),
      Expected.map((t, n) => (t, StoreLoadStatus.Loaded, 0L, n))
    )
    val (g1, kv1, _, o1, v1, _) = written(w)
    assertEquals((g1, kv1, o1, v1), (g0, kv0, o0, v0), "no data store gained an element")
    assertEquals(contents(w), before)
    assertEquals(
      freshRaw.itemCount(TableRole.RunLedger),
      9,
      "4 owner rows, 1 publication row, 4 load rows"
    )
    assert(res.runtime.isDefined)
  }

  test("a second publication of the run ID (run-2 _parts.json) is refused: zero writes") {
    val w = loaded
    val before = contents(w)
    val calls = w.writeCalls
    Fixtures.withCopy { (run, loads) =>
      Files.copy(
        Fixtures.runDir(Fixtures.run2Root).resolve("_parts.json"),
        run.resolve("_parts.json"),
        StandardCopyOption.REPLACE_EXISTING
      )
      val res = OpeningFixture.run(w, sources(run = run, loads = loads))
      val r = res.report
      println("TE-P7-01 second publication: " + tags(r))
      assertEquals(r.partManifestSha256, Some(Fixtures.Run2PartsSha256))
      val pub = r.rejections.headOption.getOrElse(fail("no rejection"))
      assertEquals(pub.check, VerificationCheck.Publication, show(r))
      assert(
        pub.error.isInstanceOf[StoreError.Conflict] &&
          pub.error.message.contains(Fixtures.PartsSha256) &&
          pub.error.message.contains("one publication per run ID"),
        pub.toString
      )
      Stores.foreach(t => assertEquals(r.store(t).status, StoreLoadStatus.Refused, t.tag))
      assertEquals(w.writeCalls, calls, "zero write calls on every store, ledger included")
      assertEquals(contents(w), before)
      assertEquals(res.runtime, None)
    }
  }

  test("store owners recorded for the same run ID with another _parts.json: all four refused") {
    val w = new World
    Stores.foreach { t =>
      ItemCodecs.StoreOwnershipCodec
        .encode(
          StoreOwnership(StoreOwnership.SchemaVersion, t, Fixtures.RunId, Fixtures.Run2PartsSha256)
        )
        .flatMap(w.ledgerStore.putIfAbsent(TableRole.RunLedger, _))
        .fold(e => fail(e.toString), identity)
    }
    val res = OpeningFixture.run(w)
    println("TE-P7-01 same run ID, other owner sha:\n" + show(res.report))
    Stores.foreach { t =>
      val s = res.report.store(t)
      assertEquals(s.status, StoreLoadStatus.Failed, t.tag)
      assertEquals(s.conflicts, 1L, t.tag)
      assert(
        s.error.exists(e =>
          e.isInstanceOf[StoreError.Conflict] &&
            e.message.contains("one analysis run per local store instance")
        ),
        s"${t.tag}: ${s.error}"
      )
    }
    assertEquals(w.writeCalls, 0, "zero write calls (the planted rows bypassed the recorder)")
    assertEquals(w.ledgerStore.itemCount(TableRole.RunLedger), 4, "only the planted owner rows")
    assertEquals((w.graph.vertexIds.size, w.objectStore.size, w.vectorCount), (0, 0, 0))
    assertEquals(w.embedder.calls, 0)
    assertEquals(res.runtime, None)
  }

  test("a loaded store set owned by this run refuses a load claimed by another run's owner rows") {
    // The loaded world's owner rows name this run; a ledger whose rows name another run in front of
    // the SAME populated stores refuses every store with zero data writes.
    val w = loaded
    val before = contents(w)
    val (g0, kv0, _, o0, v0, b0) = written(w)
    val dataCalls = w.dataWriteCalls
    val raw = new InMemoryKeyValueStore(1000)
    Stores.foreach { t =>
      ItemCodecs.StoreOwnershipCodec
        .encode(
          StoreOwnership(StoreOwnership.SchemaVersion, t, TestData.otherRunId, TestData.hex("B"))
        )
        .flatMap(raw.putIfAbsent(TableRole.RunLedger, _))
        .fold(e => fail(e.toString), identity)
    }
    val ledger = new RecordingKeyValueStore(raw)
    val res = OpeningLoad.run(sources(), config, w.targets(ledgerStore = ledger))
    Stores.foreach { t =>
      val s = res.report.store(t)
      assertEquals((s.status, s.conflicts, s.written), (StoreLoadStatus.Failed, 1L, 0L), t.tag)
    }
    assertEquals(ledger.counts.calls, 0, "nothing claimed in the foreign ledger")
    assertEquals(raw.itemCount(TableRole.RunLedger), 4, "only the planted owner rows")
    val (g1, kv1, _, o1, v1, b1) = written(w)
    assertEquals((g1, kv1, o1, v1, b1), (g0, kv0, o0, v0, b0), "no data write")
    assertEquals(w.dataWriteCalls, dataCalls, "no data write call at all")
    assertEquals(contents(w), before)
    assertEquals(res.runtime, None)
  }
}
