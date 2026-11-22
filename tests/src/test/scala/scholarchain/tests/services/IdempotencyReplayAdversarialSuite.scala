package scholarchain.tests.services

import java.util.concurrent.atomic.AtomicInteger

import scholarchain.rag.agents.WorkflowResult
import scholarchain.services.QueryWorkflow

/**
 * TE-P11-02 T8 (Phase 11 category 18, replayed workflow and idempotency): one Idempotency-Key
 * reused with the same question but a different mode is a different request (`QueryRequest.digest`
 * binds the mode): 409 `idempotency-key-reused`, and the workflow runs once.
 */
class IdempotencyReplayAdversarialSuite extends munit.FunSuite {
  import Http.*

  private val Question = "Who are the coauthors of Ada Lovelace?"

  private final class Counting(inner: QueryWorkflow) extends QueryWorkflow {
    val calls = new AtomicInteger(0)
    def answer(queryId: String, question: String): WorkflowResult = {
      calls.incrementAndGet()
      inner.answer(queryId, question)
    }
  }

  test("T8 same key and question, sync then async: 409 idempotency-key-reused, one workflow run") {
    val w = new ApiWorld
    val wf = new Counting(w.ragWorkflow())
    val s = w.service(workflow = wf)
    try {
      val first = post(s.router, "/v1/query", query(Question, "sync"), idempotencyKey = Some("k-1"))
      assertEquals(first.status, 200, first.bodyText)
      val second =
        post(s.router, "/v1/query", query(Question, "async"), idempotencyKey = Some("k-1"))
      assertEquals(second.status, 409, second.bodyText)
      assertEquals(json(second)("error")("code").str, "idempotency-key-reused")
      // An exact replay is still served from the record, flagged, without a second run.
      val replay =
        post(s.router, "/v1/query", query(Question, "sync"), idempotencyKey = Some("k-1"))
      assertEquals(replay.status, 200)
      assertEquals(replay.header("Idempotent-Replayed"), Some("true"))
      assertEquals(json(replay)("queryId"), json(first)("queryId"))
      assertEquals(wf.calls.get, 1, "the workflow ran more than once")
    } finally s.close()
  }

  test("T8 same key and question, async then sync: 409, and the job is not re-run") {
    val w = new ApiWorld
    val wf = new Counting(w.ragWorkflow())
    val s = w.service(workflow = wf)
    try {
      val first =
        post(s.router, "/v1/query", query(Question, "async"), idempotencyKey = Some("k-2"))
      assertEquals(first.status, 202, first.bodyText)
      val jobId = json(first)("jobId").str
      assert(eventually(10000)(json(get(s.router, s"/v1/jobs/$jobId"))("state").str == "succeeded"))
      val second =
        post(s.router, "/v1/query", query(Question, "sync"), idempotencyKey = Some("k-2"))
      assertEquals(second.status, 409, second.bodyText)
      assertEquals(json(second)("error")("code").str, "idempotency-key-reused")
      // An omitted mode is sync: also a different request from the async one.
      val implicitSync = post(
        s.router,
        "/v1/query",
        ujson.Obj("question" -> Question).render(),
        idempotencyKey = Some("k-2")
      )
      assertEquals(implicitSync.status, 409, implicitSync.bodyText)
      assertEquals(wf.calls.get, 1)
      // Another client's identical key is its own record (keys are per client).
      val other = post(
        s.router,
        "/v1/query",
        query(Question, "sync"),
        key = Some(Keys.OtherKey),
        idempotencyKey = Some("k-2")
      )
      assertEquals(other.status, 200, other.bodyText)
      assertEquals(wf.calls.get, 2)
    } finally s.close()
  }
}
