package scholarchain.storage

import scholarchain.core.Canonical
import scholarchain.storage.fake.{InMemoryKeyValueStore, InMemoryObjectStore, InMemoryVectorStore}
import scholarchain.storage.model.*
import scholarchain.storage.port.*
import TestData.*

/** SE-P9-02: the create-once audit row of a query (QuerySession, sort key `audit`, QS-3). */
class QueryAuditSuite extends munit.FunSuite {

  private val Expires = 2000000000L
  private val AuditSchema = "scholarchain.rag.audit-record.v1"

  private def auditJson(answer: String = "a1"): String =
    s"""{"schemaVersion":"$AuditSchema","queryId":"$queryId","answerSha256":"${hex(answer)}"}"""

  private def row(json: String = auditJson(), expires: Long = Expires): QueryAuditRecord =
    QueryAuditRecord.of(queryId, AuditSchema, json, expires)

  private val bounds = AuditBounds.of(4096L).toOption.get
  private val Codec = ItemCodecs.QueryAuditCodec
  private val auditKey = TableKeys.QuerySession.audit(queryId).toOption.get

  private def runtime(kv: InMemoryKeyValueStore): RuntimeStores = {
    val vectors = new InMemoryVectorStore(Vector.empty, limits)
    RuntimeStores(
      new InMemoryObjectStore(10),
      kv,
      kv,
      (_: BoundQuery) => Right(Vector.empty),
      vectors,
      vectors,
      EmbeddingPolicy.RealModelsOnly
    )
  }

  test("round trip: the runtime writer stores the row under query#<id>/audit and it decodes") {
    val kv = new InMemoryKeyValueStore(10)
    val a = row()
    assertEquals(a.auditSha256, Canonical.sha256HexUtf8(a.auditJson))
    assertEquals(runtime(kv).querySessions.putAudit(a, bounds), Right(PutOutcome.Written))
    assertEquals(auditKey, PrimaryKey(s"query#$queryId", "audit"))
    val item = kv.get(TableRole.QuerySession, auditKey).toOption.flatten.get
    assertEquals(Codec.decode(item), Right(a))
    assertEquals(Codec.decode(item).map(_.auditJson), Right(auditJson()), "body stored verbatim")
    // QS-3 is a stated access pattern, and the audit row is never a QS-2 step row.
    val qs3 = TableSpecs.QuerySession.accessPatterns.find(_.id == "QS-3").get
    assertEquals(
      (qs3.partitionForm, qs3.sortUse, qs3.sortForm),
      ("query#<queryId>", SortUse.Exact, "audit")
    )
    val steps = kv
      .query(
        TableRole.QuerySession,
        KeyQuery(None, s"query#$queryId", SortKeyCondition.BeginsWith("step#"), 10)
      )
      .toOption
      .get
    assertEquals(steps, Vector.empty)
  }

  test("hash mismatch refused on encode, and a tampered stored item is Corrupt on decode") {
    val bad = row().copy(auditSha256 = hex("something else"))
    val kv = new InMemoryKeyValueStore(10)
    assert(runtime(kv).querySessions.putAudit(bad, bounds) match {
      case Left(StoreError.Refused(m)) => m.contains("auditSha256: not the SHA-256 of auditJson")
      case _ => false
    })
    assertEquals(kv.itemCount(TableRole.QuerySession), 0, "nothing written")
    // A stored body whose audit hash no longer matches never decodes.
    val item = Codec.encode(row()).toOption.get
    val tamperedBody = upickle.default.write(bad)
    val tampered = item
      .updated(Attrs.Body, AttrValue.S(tamperedBody))
      .updated(Attrs.RecordSha256, AttrValue.S(Canonical.sha256HexUtf8(tamperedBody)))
    assert(Codec.decode(tampered).left.exists(_.isInstanceOf[StoreError.Corrupt]))
  }

  test("size bound refused: the configured bound, and the storage ceiling") {
    val kv = new InMemoryKeyValueStore(10)
    val small = AuditBounds.of(auditJson().length.toLong - 1).toOption.get
    assert(runtime(kv).querySessions.putAudit(row(), small) match {
      case Left(StoreError.Refused(m)) => m.contains("over the configured bound")
      case _ => false
    })
    assertEquals(kv.itemCount(TableRole.QuerySession), 0)
    // Exactly at the bound is accepted (UTF-8 bytes, not characters).
    val exact = AuditBounds.of(auditJson().getBytes("UTF-8").length.toLong).toOption.get
    assertEquals(runtime(kv).querySessions.putAudit(row(), exact), Right(PutOutcome.Written))
    // Bounds outside 1..ceiling are refused, and the codec enforces the ceiling on its own.
    assert(AuditBounds.of(0L).isLeft)
    assert(AuditBounds.of(AuditBounds.CeilingBytes + 1).isLeft)
    assert(AuditBounds.of(AuditBounds.CeilingBytes).isRight)
    val pad = "x" * AuditBounds.CeilingBytes.toInt
    val huge = row(s"""{"queryId":"$queryId","pad":"$pad"}""")
    assert(Codec.encode(huge).left.exists(_.message.contains("exceed the ceiling")))
    assert(
      runtime(kv).querySessions
        .putAudit(huge, AuditBounds.of(AuditBounds.CeilingBytes).toOption.get)
        .isLeft
    )
  }

  test("other refusals: invalid queryId, non-token schema version, non-object or foreign body") {
    assert(Codec.encode(row().copy(queryId = "query: bad")).isLeft)
    assert(Codec.encode(row().copy(queryId = researcherA)).isLeft, "foreign ID kind")
    assert(Codec.encode(row().copy(auditSchemaVersion = "has space")).isLeft)
    assert(Codec.encode(row().copy(auditSchemaVersion = "")).isLeft)
    assert(Codec.encode(row("[1,2]")).isLeft, "not an object")
    assert(Codec.encode(row("{not json")).isLeft)
    assert(Codec.encode(row("")).isLeft)
    val foreign = s"""{"queryId":"query:q-0002"}"""
    assert(Codec.encode(row(foreign)).left.exists(_.message.contains("not the row's queryId")))
    assert(Codec.encode(row(expires = 0L)).isLeft, "TTL must be a positive epoch second")
    assert(runtime(new InMemoryKeyValueStore(10)).querySessions.putAudit(row(), null).isLeft)
  }

  test("conflict on a different body under the same query; identical put is idempotent") {
    val kv = new InMemoryKeyValueStore(10)
    val w = runtime(kv).querySessions
    assertEquals(w.putAudit(row(), bounds), Right(PutOutcome.Written))
    assertEquals(w.putAudit(row(), bounds), Right(PutOutcome.AlreadyPresent), "identical retry")
    assert(
      w.putAudit(row(auditJson("a2")), bounds).left.exists(_.isInstanceOf[StoreError.Conflict])
    )
    assert(
      w.putAudit(row(expires = Expires + 1), bounds)
        .left
        .exists(_.isInstanceOf[StoreError.Conflict]),
      "a different TTL is a different row"
    )
    assertEquals(kv.itemCount(TableRole.QuerySession), 1)
    val stored = kv.get(TableRole.QuerySession, auditKey).toOption.flatten.get
    assertEquals(Codec.decode(stored), Right(row()), "the first row is kept")
  }

  test("TTL equals the session TTL: same expiresAt attribute in the same TTL table") {
    val kv = new InMemoryKeyValueStore(10)
    val w = runtime(kv).querySessions
    assertEquals(w.putSession(session(Expires)), Right(PutOutcome.Written))
    assertEquals(w.putStep(step(0)), Right(PutOutcome.Written))
    assertEquals(w.putAudit(row(expires = Expires), bounds), Right(PutOutcome.Written))
    assertEquals(Codec.table.ttlAttribute, TableSpecs.QuerySession.ttlAttribute)
    assertEquals(Codec.table.ttlAttribute, Some(Attrs.ExpiresAt))
    def ttl(k: PrimaryKey) =
      AttrValue.long(kv.get(TableRole.QuerySession, k).toOption.flatten.get, Attrs.ExpiresAt)
    val sessionTtl = ttl(TableKeys.QuerySession.session(queryId).toOption.get)
    assertEquals(sessionTtl, Right(Expires))
    assertEquals(ttl(auditKey), sessionTtl)
    assertEquals(ttl(TableKeys.QuerySession.step(queryId, 0).toOption.get), sessionTtl)
    assertEquals(kv.itemCount(TableRole.QuerySession), 3)
    TableRole.values
      .filterNot(_ == TableRole.QuerySession)
      .foreach(t => assertEquals(kv.itemCount(t), 0, t.tag))
  }
}
