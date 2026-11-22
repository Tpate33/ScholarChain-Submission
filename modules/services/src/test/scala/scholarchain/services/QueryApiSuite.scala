package scholarchain.services

import java.util.concurrent.atomic.AtomicInteger

import scholarchain.rag.agents.WorkflowResult

/** Query API and Jobs API (SE-P10-01): sync, async, validation, idempotency, authn/authz. */
class QueryApiSuite extends munit.FunSuite {
  import TestHttp.*

  private val Question = "Who are the coauthors of Ada Lovelace?"
  private def body(q: String = Question, mode: String = "sync") =
    ujson.Obj("question" -> q, "mode" -> mode).render()

  /** The rag workflow, counting calls. */
  private final class Counting(inner: QueryWorkflow) extends QueryWorkflow {
    val calls = new AtomicInteger(0)
    def answer(queryId: String, question: String): WorkflowResult = {
      calls.incrementAndGet()
      inner.answer(queryId, question)
    }
  }

  test("successful synchronous query: 200, evidence-backed answer, frozen example") {
    val w = new ServiceWorld
    val s = w.service()
    try {
      val r = post(s.router, "/v1/query", resource("query-sync.request.json"))
      assertEquals(r.status, 200, r.bodyText)
      val j = json(r)
      assertEquals(ApiContract.check("query-sync.response.json", j), Vector.empty)
      assert(j("queryId").str.startsWith("query:"))
      assertEquals(j("answer")("queryId").str, j("queryId").str)
      val claims = j("answer")("claims").arr
      assert(claims.nonEmpty)
      val bundleIds = j("answer")("evidenceBundle")("items").arr.map(_("evidenceId").str).toSet
      assert(claims.forall(c => c("evidenceIds").arr.nonEmpty))
      assert(claims.forall(c => c("evidenceIds").arr.forall(e => bundleIds(e.str))))
      assert(claims.forall(_("verificationStatus").str == "supported"))
      assert(r.header("X-Request-Id").exists(_.startsWith("req-")))
      assert(r.header("X-Trace-Id").nonEmpty)
      assertEquals(r.header("Content-Type"), Some("application/json; charset=utf-8"))
    } finally s.close()
  }

  test("asynchronous query path: 202 job reference, job reaches succeeded, result is the answer") {
    val w = new ServiceWorld
    val s = w.service()
    try {
      val r = post(s.router, "/v1/query", resource("query-async.request.json"))
      assertEquals(r.status, 202, r.bodyText)
      val j = json(r)
      assertEquals(ApiContract.check("query-async.response.json", j), Vector.empty)
      val jobId = j("jobId").str
      assertEquals(r.header("Location"), Some(s"/v1/jobs/$jobId"))
      assert(
        eventually(10000)(json(get(s.router, s"/v1/jobs/$jobId"))("state").str == "succeeded")
      )
      val status = json(get(s.router, s"/v1/jobs/$jobId"))
      assertEquals(ApiContract.check("job-succeeded.response.json", status), Vector.empty)
      assertEquals(status("queryId").str, j("queryId").str)
      val result = get(s.router, s"/v1/jobs/$jobId/result")
      assertEquals(result.status, 200)
      assertEquals(ApiContract.check("query-sync.response.json", json(result)), Vector.empty)
      assertEquals(json(result)("queryId").str, j("queryId").str)
    } finally s.close()
  }

  test("a job's result before it finishes is 409 job-not-finished; unknown job 404; bad ID 400") {
    val w = new ServiceWorld
    val gate = new java.util.concurrent.CountDownLatch(1)
    val slow = new QueryWorkflow {
      val inner = w.ragWorkflow
      def answer(q: String, question: String): WorkflowResult = {
        gate.await(10, java.util.concurrent.TimeUnit.SECONDS)
        inner.answer(q, question)
      }
    }
    val s = w.service(workflow = slow)
    try {
      val jobId = json(post(s.router, "/v1/query", body(mode = "async")))("jobId").str
      val early = get(s.router, s"/v1/jobs/$jobId/result")
      assertEquals(early.status, 409)
      assertEquals(json(early)("error")("code").str, "job-not-finished")
      assertEquals(get(s.router, "/v1/jobs/job-99999").status, 404)
      assertEquals(
        json(get(s.router, "/v1/jobs/not-a-job"))("error")("code").str,
        "invalid-parameter"
      )
      gate.countDown()
      assert(eventually(10000)(get(s.router, s"/v1/jobs/$jobId/result").status == 200))
    } finally { gate.countDown(); s.close() }
  }

  test("a job is visible only to the client that created it") {
    val w = new ServiceWorld
    val s = w.service()
    try {
      val jobId = json(post(s.router, "/v1/query", body(mode = "async")))("jobId").str
      assertEquals(get(s.router, s"/v1/jobs/$jobId", Some(FakeKeys.OtherKey)).status, 404)
      assert(eventually(10000)(get(s.router, s"/v1/jobs/$jobId").status == 200))
    } finally s.close()
  }

  test("invalid JSON: 400 invalid-json with request and trace IDs, workflow not called") {
    val w = new ServiceWorld
    val wf = new Counting(w.ragWorkflow)
    val s = w.service(workflow = wf)
    try {
      Vector("{\"question\": ", "not json", "", "[1,2").foreach { b =>
        val r = post(s.router, "/v1/query", b)
        assertEquals(r.status, 400, b)
        val j = json(r)
        assertEquals(ApiContract.check("error.response.json", j), Vector.empty)
        assertEquals(j("error")("code").str, "invalid-json")
        assertEquals(j("error")("requestId").str, r.header("X-Request-Id").get)
        assertEquals(j("error")("traceId").str, r.header("X-Trace-Id").get)
      }
      // Bytes that are not UTF-8 are invalid JSON too.
      val raw = s.router.handle(
        HttpRequest(
          "POST",
          "/v1/query",
          None,
          HttpRequest.headersOf(
            auth(FakeKeys.FullKey) :+ ("Content-Type" -> "application/json")
          ),
          Array[Byte](0x7b, 0x22, 0xc3.toByte, 0x28, 0x22, 0x7d)
        )
      )
      assertEquals(json(raw)("error")("code").str, "invalid-json")
      assertEquals(wf.calls.get, 0)
    } finally s.close()
  }

  test("invalid requests: 400 invalid-request for shape errors, media type and body bound") {
    val w = new ServiceWorld
    val s = w.service()
    try {
      Vector(
        "[]",
        "{}",
        """{"question": 7}""",
        """{"question": "   "}""",
        """{"question": "Who are the coauthors of Ada Lovelace?", "extra": 1}""",
        """{"question": "Who are the coauthors of Ada Lovelace?", "mode": 1}""",
        """{"question": "Who are the coauthors of Ada Lovelace?", "constraints": []}""",
        ujson.Obj("question" -> ("x" * 3000)).render()
      ).foreach { b =>
        val r = post(s.router, "/v1/query", b)
        assertEquals((r.status, json(r)("error")("code").str), (400, "invalid-request"), b)
      }
      val media = post(s.router, "/v1/query", body(), contentType = "text/plain")
      assertEquals(json(media)("error")("code").str, "unsupported-media-type")
      val big = post(s.router, "/v1/query", " " * 20000 + body())
      assertEquals(json(big)("error")("code").str, "body-too-large")
    } finally s.close()
  }

  test("unsupported query constraint and mode: 422 unsupported-constraint (frozen example)") {
    val w = new ServiceWorld
    val s = w.service()
    try {
      val r = post(s.router, "/v1/query", resource("query-unsupported-constraint.request.json"))
      assertEquals(r.status, 422)
      assertEquals(
        ApiContract.check("query-unsupported-constraint.response.json", json(r)),
        Vector.empty
      )
      val mode = post(s.router, "/v1/query", body(mode = "stream"))
      assertEquals(mode.status, 422)
      assertEquals(json(mode)("error")("constraint").str, "mode")
    } finally s.close()
  }

  test("a question with no supported analysis: 422 unsupported-query with the planner reason") {
    val w = new ServiceWorld
    val s = w.service()
    try {
      val r = post(s.router, "/v1/query", body("What is the weather like today?"))
      assertEquals(r.status, 422, r.bodyText)
      assertEquals(
        ApiContract.check("query-unsupported-query.response.json", json(r)),
        Vector.empty
      )
      assert(!r.bodyText.contains("weather"), "the question text is never echoed")
    } finally s.close()
  }

  test("idempotent repeated request: same query, workflow called once, replay header") {
    val w = new ServiceWorld
    val wf = new Counting(w.ragWorkflow)
    val s = w.service(workflow = wf)
    try {
      val a = post(s.router, "/v1/query", body(), idempotencyKey = Some("key-1"))
      val b = post(s.router, "/v1/query", body(), idempotencyKey = Some("key-1"))
      assertEquals((a.status, b.status), (200, 200))
      assertEquals(json(a), json(b))
      assertEquals(b.header("Idempotent-Replayed"), Some("true"))
      assertEquals(a.header("Idempotent-Replayed"), None)
      assertEquals(wf.calls.get, 1)
      // Whitespace-only differences canonicalize to the same request.
      val c = post(
        s.router,
        "/v1/query",
        s"""{ "mode":"sync",  "question": "$Question" }""",
        idempotencyKey = Some("key-1")
      )
      assertEquals(c.status, 200)
      assertEquals(wf.calls.get, 1)
      // Another client's identical key is its own record.
      post(
        s.router,
        "/v1/query",
        body(),
        key = Some(FakeKeys.OtherKey),
        idempotencyKey = Some("key-1")
      )
      assertEquals(wf.calls.get, 2)
    } finally s.close()
  }

  test("same Idempotency-Key with a different body: 409 idempotency-key-reused") {
    val w = new ServiceWorld
    val wf = new Counting(w.ragWorkflow)
    val s = w.service(workflow = wf)
    try {
      assertEquals(post(s.router, "/v1/query", body(), idempotencyKey = Some("k2")).status, 200)
      val r = post(
        s.router,
        "/v1/query",
        body("Who are the coauthors of John Smith?"),
        idempotencyKey = Some("k2")
      )
      assertEquals(r.status, 409)
      assertEquals(json(r)("error")("code").str, "idempotency-key-reused")
      assertEquals(wf.calls.get, 1)
      val badKey = post(s.router, "/v1/query", body(), idempotencyKey = Some("bad key!"))
      assertEquals(json(badKey)("error")("code").str, "invalid-parameter")
    } finally s.close()
  }

  test("idempotent async request replays the same job; an in-flight sync key is 409 in-progress") {
    val w = new ServiceWorld
    val s = w.service()
    try {
      val a = json(post(s.router, "/v1/query", body(mode = "async"), idempotencyKey = Some("ak")))
      val b = post(s.router, "/v1/query", body(mode = "async"), idempotencyKey = Some("ak"))
      assertEquals(b.status, 202)
      assertEquals(json(b)("jobId").str, a("jobId").str)
      assert(eventually(10000)(get(s.router, s"/v1/jobs/${a("jobId").str}/result").status == 200))
    } finally s.close()
    val gate = new java.util.concurrent.CountDownLatch(1)
    val entered = new java.util.concurrent.CountDownLatch(1)
    val w2 = new ServiceWorld
    val slow = new QueryWorkflow {
      val inner = w2.ragWorkflow
      def answer(q: String, question: String): WorkflowResult = {
        entered.countDown()
        gate.await(10, java.util.concurrent.TimeUnit.SECONDS)
        inner.answer(q, question)
      }
    }
    val s2 = w2.service(workflow = slow)
    try {
      val first = new Thread(() => {
        post(s2.router, "/v1/query", body(), idempotencyKey = Some("sk")); ()
      })
      first.start()
      // Only after the first request holds the key (it is inside the workflow) is the repeat sent.
      assert(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
      val repeat = post(s2.router, "/v1/query", body(), idempotencyKey = Some("sk"))
      assertEquals(repeat.status, 409)
      assertEquals(json(repeat)("error")("code").str, "idempotency-in-progress")
      gate.countDown()
      first.join(10000)
      assertEquals(post(s2.router, "/v1/query", body(), idempotencyKey = Some("sk")).status, 200)
    } finally { gate.countDown(); s2.close() }
  }

  test("unauthorized request: 401 without or with a wrong key, 403 without the scope") {
    val w = new ServiceWorld
    val wf = new Counting(w.ragWorkflow)
    val log = new RecordingLog
    val s = w.service(workflow = wf, log = log)
    try {
      val none = post(s.router, "/v1/query", body(), key = None)
      assertEquals(none.status, 401)
      assertEquals(none.header("WWW-Authenticate"), Some("Bearer"))
      assertEquals(ApiContract.check("error.response.json", json(none)), Vector.empty)
      assertEquals(json(none)("error")("code").str, "unauthenticated")
      val wrong = post(s.router, "/v1/query", body(), key = Some("wrong-key-0123456789abcdef"))
      assertEquals(wrong.status, 401)
      val malformed = post(
        s.router,
        "/v1/query",
        body(),
        key = None,
        headers = Vector("Authorization" -> "Basic dXNlcjpwYXNz")
      )
      assertEquals(malformed.status, 401)
      val reader = post(s.router, "/v1/query", body(), key = Some(FakeKeys.ReaderKey))
      assertEquals(reader.status, 403)
      assertEquals(json(reader)("error")("code").str, "forbidden")
      assertEquals(get(s.router, "/v1/evidence/ev:" + "0" * 64, key = None).status, 401)
      assertEquals(get(s.router, "/metrics", key = Some(FakeKeys.ReaderKey)).status, 403)
      assertEquals(wf.calls.get, 0)
      // No response or log line carries a key.
      val texts = Vector(none, wrong, malformed, reader).map(_.bodyText)
      import scala.jdk.CollectionConverters.*
      val lines = log.events.asScala.toVector.map(_.render)
      (texts ++ lines).foreach { t =>
        assert(!t.contains("wrong-key"), t)
        assert(!t.contains(FakeKeys.ReaderKey), t)
        assert(!t.contains("dXNlcjpwYXNz"), t)
      }
    } finally s.close()
  }

  test("trace IDs: a valid X-Trace-Id or traceparent is propagated, an invalid one replaced") {
    val w = new ServiceWorld
    val s = w.service()
    try {
      val t = "abcdef0123456789abcdef0123456789"
      val a =
        get(s.router, "/v1/jobs/job-1", headers = Vector("X-Trace-Id" -> "trace-from-client-1"))
      assertEquals(a.header("X-Trace-Id"), Some("trace-from-client-1"))
      assertEquals(json(a)("error")("traceId").str, "trace-from-client-1")
      val b =
        get(s.router, "/healthz", headers = Vector("traceparent" -> s"00-$t-0123456789abcdef-01"))
      assertEquals(b.header("X-Trace-Id"), Some(t))
      val c = get(s.router, "/healthz", headers = Vector("X-Trace-Id" -> "bad trace\u0000"))
      assert(!c.header("X-Trace-Id").contains("bad trace\u0000"))
    } finally s.close()
  }
}
