package scholarchain.storage

import scholarchain.storage.fake.{InMemoryKeyValueStore, RecordingKeyValueStore}
import scholarchain.storage.model.*
import scholarchain.storage.model.ItemCodecs.given
import scholarchain.storage.port.{KeyQuery, KeyValueReader, KeyValueWriter, SortKeyCondition}
import TestData.*

/** Suite 9 (SE-P7-01): DynamoDB conditional-write semantics on the fake (README L1388, L1416). */
class ConditionalWritesSuite extends munit.FunSuite {

  private def store() = new InMemoryKeyValueStore(maxQueryLimit = 100)
  private def enc[A](a: A)(using c: ItemCodec[A]): Item = c.encode(a).toOption.get

  test("putIfAbsent: absent is Written, equal record is AlreadyPresent, different is Conflict") {
    val kv = store()
    val first = enc(publication(hex("parts-a")))
    assertEquals(kv.putIfAbsent(TableRole.RunLedger, first), Right(PutOutcome.Written))
    assertEquals(kv.putIfAbsent(TableRole.RunLedger, first), Right(PutOutcome.AlreadyPresent))
    val second = enc(publication(hex("parts-b")))
    kv.putIfAbsent(TableRole.RunLedger, second) match {
      case Left(StoreError.Conflict(_)) => ()
      case other => fail(s"expected Conflict, got $other")
    }
    val key = TableKeys.RunLedger.publication(runId).toOption.get
    assertEquals(
      kv.get(TableRole.RunLedger, key),
      Right(Some(first)),
      "the stored record is unchanged"
    )
  }

  test("a second publication of one run ID with other _parts.json bytes is refused (R1)") {
    val kv = store()
    assertEquals(
      kv.putIfAbsent(TableRole.RunLedger, enc(publication(hex("run-1-local")))),
      Right(PutOutcome.Written)
    )
    assert(kv.putIfAbsent(TableRole.RunLedger, enc(publication(hex("run-2-local-cluster")))).isLeft)
  }

  test("putIfVersion: wins only on the stored version, and only to version + 1") {
    val kv = store()
    val v1 = RunLedgerStates
      .start(runId, loadId, scholarchain.core.TargetStore.DynamoDb, hex("p"), 21, 10L)
      .toOption
      .get
    assertEquals(kv.putIfAbsent(TableRole.RunLedger, enc(v1)), Right(PutOutcome.Written))
    val v2 = RunLedgerStates.advance(v1, LoadState.Verified, LoadCounts(0, 0, 0), 20L).toOption.get
    assertEquals(kv.putIfVersion(TableRole.RunLedger, enc(v2), expectedVersion = 1), Right(()))
    kv.putIfVersion(TableRole.RunLedger, enc(v2), expectedVersion = 1) match {
      case Left(StoreError.StaleVersion(_)) => ()
      case other => fail(s"a replay of the same transition must be StaleVersion, got $other")
    }
    val skip = v2.copy(version = 5)
    kv.putIfVersion(TableRole.RunLedger, enc(skip), expectedVersion = 2) match {
      case Left(StoreError.Refused(_)) => ()
      case other => fail(s"a version jump must be Refused, got $other")
    }
  }

  test("putIfVersion on an absent item is StaleVersion (create with putIfAbsent)") {
    val kv = store()
    val v1 = RunLedgerStates
      .start(runId, loadId, scholarchain.core.TargetStore.S3, hex("p"), 29, 1L)
      .toOption
      .get
    val v2 = RunLedgerStates.advance(v1, LoadState.Verified, LoadCounts(0, 0, 0), 2L).toOption.get
    kv.putIfVersion(TableRole.RunLedger, enc(v2), 1) match {
      case Left(StoreError.StaleVersion(_)) => ()
      case other => fail(s"expected StaleVersion, got $other")
    }
  }

  test("items without the table key, with a half GSI key or for an unknown table are Refused") {
    val kv = store()
    val item = enc(publication())
    assert(kv.putIfAbsent(TableRole.RunLedger, item - "sk").isLeft)
    assert(kv.putIfAbsent(TableRole.RunLedger, item.updated("pk", AttrValue.n(1))).isLeft)
    val load = enc(
      RunLedgerStates
        .start(runId, loadId, scholarchain.core.TargetStore.S3, hex("p"), 1, 1L)
        .toOption
        .get
    )
    assert(kv.putIfAbsent(TableRole.RunLedger, load - "byLoadSk").isLeft, "GSI keys come in pairs")
    val onlyLedger = new InMemoryKeyValueStore(10, Vector(TableSpecs.RunLedger))
    assert(onlyLedger.putIfAbsent(TableRole.QuerySession, enc(session())).isLeft)
    assertEquals(kv.itemCount(TableRole.RunLedger), 0, "nothing was written")
  }

  test("query serves the table key with begins_with, exact and between, in byte order") {
    val kv = store()
    Vector(3, 1, 2).foreach(i => kv.putIfAbsent(TableRole.QuerySession, enc(step(i))))
    kv.putIfAbsent(TableRole.QuerySession, enc(session()))
    val p = TableKeys.QuerySession.partition(queryId).toOption.get
    def q(c: SortKeyCondition, limit: Int = 10, asc: Boolean = true) =
      kv.query(TableRole.QuerySession, KeyQuery(None, p, c, limit, asc)).toOption.get
    def ordinals(items: Vector[Item]) =
      items.map(ItemCodecs.QueryStepCodec.decode(_).toOption.get.ordinal)
    assertEquals(ordinals(q(SortKeyCondition.BeginsWith("step#"))), Vector(1, 2, 3))
    assertEquals(ordinals(q(SortKeyCondition.BeginsWith("step#"), asc = false)), Vector(3, 2, 1))
    assertEquals(ordinals(q(SortKeyCondition.BeginsWith("step#"), limit = 2)), Vector(1, 2))
    assertEquals(ordinals(q(SortKeyCondition.Between("step#0002", "step#0003"))), Vector(2, 3))
    assertEquals(q(SortKeyCondition.Equals("session")).size, 1)
    assertEquals(q(SortKeyCondition.All).size, 4)
  }

  test("query serves a GSI, and GSIs are sparse") {
    val kv = store()
    kv.putIfAbsent(
      TableRole.ResearcherMetricCache,
      enc(metric(researcherA, group = Some(communityId)))
    )
    kv.putIfAbsent(
      TableRole.ResearcherMetricCache,
      enc(metric(researcherB, group = Some(communityId)))
    )
    kv.putIfAbsent(TableRole.ResearcherMetricCache, enc(metric(orcidResearcher)))
    val g = TableKeys.ResearcherMetricCache.byGroup(communityId, 2015, 2020).toOption.get
    val members = kv
      .query(
        TableRole.ResearcherMetricCache,
        KeyQuery(Some("byGroup"), g, SortKeyCondition.All, 10)
      )
      .toOption
      .get
    assertEquals(
      members.map(ItemCodecs.AnalysisResultCodec.decode(_).toOption.get.scopeId).toSet,
      Set(researcherA, researcherB),
      "the row without a group is not in the index"
    )
  }

  test("ranked top-k through the rank GSI in ascending rank order") {
    val kv = store()
    Vector(researcherA -> 2, researcherB -> 1, orcidResearcher -> 10).foreach { case (s, r) =>
      kv.putIfAbsent(TableRole.ResearcherMetricCache, enc(metric(s, rank = Some(r))))
    }
    val p = TableKeys.ResearcherMetricCache.rank("researcher-metrics", 2015, 2020).toOption.get
    val top = kv
      .query(TableRole.ResearcherMetricCache, KeyQuery(Some("rank"), p, SortKeyCondition.All, 2))
      .toOption
      .get
    assertEquals(
      top.map(ItemCodecs.AnalysisResultCodec.decode(_).toOption.get.scopeId),
      Vector(researcherB, researcherA)
    )
  }

  test("query refuses an unknown index, an unbounded limit and an empty partition") {
    val kv = store()
    assert(
      kv.query(TableRole.RunLedger, KeyQuery(Some("byNothing"), "x", SortKeyCondition.All, 1))
        .isLeft
    )
    assert(kv.query(TableRole.RunLedger, KeyQuery(None, "x", SortKeyCondition.All, 0)).isLeft)
    assert(kv.query(TableRole.RunLedger, KeyQuery(None, "x", SortKeyCondition.All, 101)).isLeft)
    assert(kv.query(TableRole.RunLedger, KeyQuery(None, "", SortKeyCondition.All, 1)).isLeft)
  }

  test("the key-value ports and the fake have no scan and no delete") {
    val forbidden = Set("scan", "delete", "deleteitem", "batchwrite", "batchwriteitem", "truncate")
    Vector(classOf[KeyValueReader], classOf[KeyValueWriter], classOf[InMemoryKeyValueStore])
      .foreach { c =>
        val names = c.getMethods.map(_.getName.toLowerCase).toSet
        assert(
          names.intersect(forbidden).isEmpty,
          s"${c.getSimpleName}: ${names.intersect(forbidden)}"
        )
      }
  }

  test("injected failures surface unchanged and write nothing") {
    val kv = store()
    kv.failNextWrites(StoreError.Transient("throttled"))
    assertEquals(
      kv.putIfAbsent(TableRole.RunLedger, enc(publication())),
      Left(StoreError.Transient("throttled"))
    )
    assertEquals(kv.itemCount(TableRole.RunLedger), 0)
    assertEquals(kv.putIfAbsent(TableRole.RunLedger, enc(publication())), Right(PutOutcome.Written))
  }

  test("the recording wrapper counts every write and outcome") {
    val kv = new RecordingKeyValueStore(store())
    val item = enc(publication())
    kv.putIfAbsent(TableRole.RunLedger, item)
    kv.putIfAbsent(TableRole.RunLedger, item)
    kv.putIfAbsent(TableRole.RunLedger, enc(publication(hex("other"))))
    assertEquals(
      (kv.counts.calls, kv.counts.written, kv.counts.alreadyPresent, kv.counts.failed),
      (3, 1, 1, 1)
    )
    assertEquals(kv.byTable(TableRole.RunLedger), 3)
  }
}
