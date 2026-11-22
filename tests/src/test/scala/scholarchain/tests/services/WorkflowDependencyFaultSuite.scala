package scholarchain.tests.services

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import scholarchain.core.QueryId
import scholarchain.storage.model.{
  AttrValue,
  Attrs,
  BoundQuery,
  GraphRow,
  Item,
  PutOutcome,
  StoreError,
  TableKeys,
  TableRole,
  TableSpecs,
  VectorFilter
}
import scholarchain.storage.port.{GraphReader, KeyValueWriter, VectorReader}

/**
 * TE-P11-02 T4, T5, T6 (Phase 11 categories 15 Neptune timeout, 17 S3 Vectors throttling or
 * transient failure, 16 DynamoDB conditional-write conflict): fault-injecting store ports UNDER the
 * real `CollaborationWorkflow` (not a stub workflow), composed into the services runtime and called
 * through POST /v1/query. The ports are the ones the production adapters will implement
 * (`GraphReader`, `VectorReader`, `KeyValueWriter`), so these are the local (L) halves of the
 * categories; the adapters' own timeout, backoff and condition mapping are (P) items.
 */
class WorkflowDependencyFaultSuite extends munit.FunSuite {
  import Http.*

  override val munitTimeout = scala.concurrent.duration.Duration(120, "s")

  private val Coauthors = "Who are the coauthors of Ada Lovelace?"
  private val StoreDetail =
    Vector("neptune", ".internal", "Transient", "SlowDown", "s3vectors", "Conflict")

  /** A graph reader that blocks on a latch (bounded) and then fails, like a hung Neptune call. */
  private final class HangingGraph(inner: GraphReader) extends GraphReader {
    val calls = new AtomicInteger(0)
    val entered = new CountDownLatch(1)
    val gate = new CountDownLatch(1)
    def run(q: BoundQuery): Either[StoreError, Vector[GraphRow]] = {
      calls.incrementAndGet()
      entered.countDown()
      gate.await(20, TimeUnit.SECONDS)
      Left(StoreError.Transient("neptune-1.cluster.internal: read timed out"))
    }
  }

  // ------------------------------------------------------------------------- T4

  test(
    "T4 Neptune timeout inside the real workflow: sync 504 timeout within the bound, no detail"
  ) {
    var g: HangingGraph = null
    val w = new ApiWorld(graphWrap = inner => { g = new HangingGraph(inner); g })
    val s = w.service(config = ApiWorld.config(syncTimeoutMillis = 400L))
    try {
      val t0 = System.nanoTime()
      val r = post(s.router, "/v1/query", query(Coauthors))
      val ms = (System.nanoTime() - t0) / 1000000L
      assert(g.calls.get >= 1, "the workflow never reached the graph (attack path not exercised)")
      assertEquals(r.status, 504, r.bodyText)
      assertEquals(json(r)("error")("code").str, "timeout")
      assert(json(r)("error")("queryId").str.startsWith("query:"))
      StoreDetail.foreach(d => assert(!r.bodyText.contains(d), s"store detail '$d': ${r.bodyText}"))
      assert(ms < 5000, s"took $ms ms (sync bound 400 ms)")
      // Accepted residual N5 (recorded, not asserted): the hung workflow holds the serialized store
      // lock, so an evidence read now waits for it.
      val t1 = System.nanoTime()
      val e = get(s.router, "/v1/evidence/ev:" + "1" * 64)
      println(
        s"TE-P11-02 T4 (N5 observation): evidence read during the hang -> ${e.status} " +
          s"${json(e)("error").obj.get("code").map(_.str).getOrElse("-")} in " +
          s"${(System.nanoTime() - t1) / 1000000L} ms; graph calls ${g.calls.get}"
      )
    } finally { if (g != null) g.gate.countDown(); s.close() }
  }

  test("T4 Neptune timeout inside the real workflow: the async job fails with code timeout") {
    var g: HangingGraph = null
    val w = new ApiWorld(graphWrap = inner => { g = new HangingGraph(inner); g })
    val s = w.service(config = ApiWorld.config(asyncTimeoutMillis = 300L))
    try {
      val r = post(s.router, "/v1/query", query(Coauthors, "async"))
      assertEquals(r.status, 202, r.bodyText)
      val jobId = json(r)("jobId").str
      assert(g.entered.await(10, TimeUnit.SECONDS), "the job never reached the graph")
      assert(eventually(10000)(json(get(s.router, s"/v1/jobs/$jobId"))("state").str == "failed"))
      val j = json(get(s.router, s"/v1/jobs/$jobId"))
      assertEquals(j("error")("code").str, "timeout")
      val res = get(s.router, s"/v1/jobs/$jobId/result")
      assertEquals(res.status, 504)
      StoreDetail.foreach(d =>
        assert(!res.bodyText.contains(d), s"store detail '$d': ${res.bodyText}")
      )
    } finally { if (g != null) g.gate.countDown(); s.close() }
  }

  // ------------------------------------------------------------------------- T5

  /** A vector reader whose queries fail with `error` (getVectors passes through). */
  private final class FailingVectors(inner: VectorReader, error: StoreError) extends VectorReader {
    val queries = new AtomicInteger(0)
    def query(index: String, v: Vector[Float], k: Int, f: Option[VectorFilter]) = {
      queries.incrementAndGet()
      Left(error)
    }
    def getVectors(index: String, keys: Vector[String]) = inner.getVectors(index, keys)
  }

  test(
    "T5 S3 Vectors throttling or corruption on the runtime read: no 5xx, no detail, nothing semantic"
  ) {
    val errors = Vector(
      StoreError.Transient("SlowDown: s3vectors throttled paper-abstract-v1 (request 7f3a)"),
      StoreError.Corrupt("s3vectors researcher-profile-v1 key x: dataSha256 mismatch"),
      StoreError.Permanent(
        "s3vectors AccessDenied on index arn:aws:s3vectors:us-east-1:123456789012"
      )
    )
    errors.foreach { err =>
      var fv: FailingVectors = null
      val w = new ApiWorld(vectorWrap = inner => { fv = new FailingVectors(inner, err); fv })
      w.addSemanticDocs()
      val s = w.service()
      try {
        val r = post(
          s.router,
          "/v1/query",
          query("Explain the research on graph methods by Ada Lovelace")
        )
        assert(fv.queries.get >= 1, s"$err: no vector query was issued (attack path not exercised)")
        assertEquals(r.status, 200, s"$err: ${r.bodyText}")
        (StoreDetail ++ Vector("arn:aws", "AccessDenied", "dataSha256", "7f3a")).foreach(d =>
          assert(!r.bodyText.contains(d), s"$err: detail '$d' in ${r.bodyText}")
        )
        val j = json(r)
        j("status").str match {
          case "answered" =>
            val items = j("answer")("evidenceBundle")("items").arr
            assert(
              items.forall(_("evidenceType").str != "text-chunk"),
              s"$err: semantic items in a bundle whose vector reads failed"
            )
            println(s"TE-P11-02 T5 [$err]: answered without semantic context")
          case "abstained" =>
            println(s"TE-P11-02 T5 [$err]: abstained ${j("abstention")("reason").str}")
          case other => fail(s"$err: status $other")
        }
      } finally s.close()
    }
  }

  // ------------------------------------------------------------------------- T6

  /** A writer that answers `Conflict` for the QuerySession audit row (a lost conditional write). */
  private final class ConflictingAudit(inner: KeyValueWriter) extends KeyValueWriter {
    val conflicts = new AtomicInteger(0)
    def putIfAbsent(table: TableRole, item: Item): Either[StoreError, PutOutcome] =
      if (table == TableRole.QuerySession && item.get("sk").contains(AttrValue.S("audit"))) {
        conflicts.incrementAndGet()
        Left(StoreError.Conflict("QuerySession query#x audit: holds a different record"))
      } else inner.putIfAbsent(table, item)
    def putIfVersion(table: TableRole, item: Item, expectedVersion: Long) =
      inner.putIfVersion(table, item, expectedVersion)
  }

  private def checkUnaudited(tag: String, r: scholarchain.services.HttpResponse)(using
      munit.Location
  ) = {
    assertEquals(r.status, 200, s"$tag: ${r.bodyText}")
    val j = json(r)
    assertEquals(
      j("status").str,
      "abstained",
      s"$tag: an answer was returned unaudited: ${r.bodyText}"
    )
    assertEquals(j("abstention")("reason").str, "audit-not-persisted", tag)
    assertEquals(j("auditPersisted").bool, false, tag)
    assertEquals(j("links")("explain"), ujson.Null, tag)
    assert(!r.bodyText.contains("holds a different record"), s"$tag: store detail ${r.bodyText}")
  }

  test("T6 DynamoDB conditional-write Conflict on the audit row: the answer is withheld") {
    var cw: ConflictingAudit = null
    val w = new ApiWorld(writerWrap = inner => { cw = new ConflictingAudit(inner); cw })
    val s = w.service()
    try {
      val r = post(s.router, "/v1/query", query(Coauthors))
      assert(cw.conflicts.get >= 1, "the audit row was never written (attack path not exercised)")
      checkUnaudited("injected conflict", r)
    } finally s.close()
  }

  test("T6 a pre-planted foreign audit row under the same query ID: withheld, row unchanged") {
    val token = "0" * 31 + "7"
    val qid = QueryId.of(token).fold(e => fail(s"fixture: $e"), identity)
    val w = new ApiWorld
    val spec = TableSpecs.all.find(_.role == TableRole.QuerySession).get
    val key = TableKeys.QuerySession.audit(qid).fold(e => fail(e.message), identity)
    val planted: Item = Map(
      spec.partitionKey -> AttrValue.S(key.pk),
      spec.sortKey -> AttrValue.S(key.sk),
      Attrs.RecordSha256 -> AttrValue.S("f" * 64)
    )
    assertEquals(w.kv.putIfAbsent(TableRole.QuerySession, planted), Right(PutOutcome.Written))
    val s = w.service(ids = new FixedIds(token))
    try {
      val r = post(s.router, "/v1/query", query(Coauthors))
      assertEquals(json(r).obj.get("queryId").map(_.str), Some(qid), "the planted ID was not hit")
      checkUnaudited("planted row", r)
      assertEquals(
        w.kv.get(TableRole.QuerySession, key),
        Right(Some(planted)),
        "planted row changed"
      )
      // Explain of this query ID must not serve the foreign row as this client's audit.
      val ex = get(s.router, s"/v1/explain/$qid")
      assert(ex.status != 200, s"explain served a foreign audit row: ${ex.status} ${ex.bodyText}")
    } finally s.close()
  }
}
