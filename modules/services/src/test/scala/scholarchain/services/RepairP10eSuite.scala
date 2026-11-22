package scholarchain.services

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import scholarchain.rag.agents.WorkflowResult
import scholarchain.storage.model.StoreError

/** SE-P10-06 (CR-P10-06 repair 05): m-A, a hanging ownership record is a key-value failure. */
class RepairP10eSuite extends munit.FunSuite {
  import TestHttp.*

  private def body = ujson.Obj("question" -> "Who are the coauthors of Ada Lovelace?").render()

  /** An ownership record whose `record` hangs, ignoring interruption, until released. */
  private final class HangingRecord(inner: QueryOwnership) extends QueryOwnership {
    val gate = new CountDownLatch(1)
    def record(q: String, c: String): Either[StoreError, Unit] = {
      var done = false
      while (!done) {
        try done = { gate.await(10, TimeUnit.SECONDS); true }
        catch { case _: InterruptedException => () }
      }
      inner.record(q, c)
    }
    def owner(q: String) = inner.owner(q)
    def ownedBy(c: String, qs: Vector[String]) = inner.ownedBy(c, qs)
  }

  test("m-A: a hanging ownership record is a key-value 504, never a workflow timeout") {
    val w = new ServiceWorld
    val owners = new HangingRecord(new InMemoryQueryOwnership(100))
    val ran = new AtomicInteger(0)
    val inner = w.ragWorkflow
    val counting = new QueryWorkflow {
      def answer(q: String, question: String): WorkflowResult = {
        ran.incrementAndGet(); inner.answer(q, question)
      }
    }
    // Derived defaults: request bound 4/5 of the 1000 ms workflow timeout (800 ms); two attempts
    // of 300 ms both run in full, so the final one is a key-value breaker failure (threshold 1).
    val config = ServiceWorld.config(
      syncTimeoutMillis = 1000L,
      readTimeoutMillis = 300L,
      readMaxAttempts = 2,
      breakerFailureThreshold = 1
    )
    assert(ServiceConfig.problems(config).isEmpty, ServiceConfig.problems(config).mkString("; "))
    val s = w.service(config = config, workflow = counting, ownership = owners)
    try {
      val r = post(s.router, "/v1/query", body)
      assertEquals(r.status, 504, r.bodyText)
      val e = json(r)("error")
      assertEquals(e("code").str, "timeout")
      assert(e("message").str.startsWith("recording query ownership"), e("message").str)
      assert(!r.bodyText.contains("workflow did not finish"))
      assertEquals(ran.get, 0, "the workflow never started")
      assertEquals(s.breakers.find(_.name == "workflow").get.state, "closed")
      assertEquals(s.breakers.find(_.name == "key-value").get.state, "open")
      val checks = json(get(s.router, "/readyz", key = None))("checks")
      assertEquals(checks("circuit-workflow").str, "ok")
      assertEquals(checks("circuit-key-value").str, "failing")
    } finally { owners.gate.countDown(); s.close() }
  }

  test("m-A config: the read request bound must be below both workflow timeouts") {
    val c = ServiceWorld.config()
    assert(ServiceConfig.problems(c).isEmpty, ServiceConfig.problems(c).mkString("; "))
    val rule = "readRequestTimeoutMillis: must be < syncTimeoutMillis and < asyncTimeoutMillis"
    Vector(
      c.copy(readRequestTimeoutMillis = c.syncTimeoutMillis),
      c.copy(asyncTimeoutMillis = c.readRequestTimeoutMillis),
      c.copy(syncTimeoutMillis = c.readRequestTimeoutMillis - 1)
    ).foreach(bad => assert(ServiceConfig.problems(bad).contains(rule), bad.toString))
  }
}
