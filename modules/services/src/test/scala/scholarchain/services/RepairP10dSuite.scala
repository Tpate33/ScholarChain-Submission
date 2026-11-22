package scholarchain.services

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*

import scholarchain.rag.agents.WorkflowResult
import scholarchain.storage.model.StoreError

/** SE-P10-05 (CR-P10-05 repair 04): F3 readiness latch, F4 ownership outside the lock, U-b. */
class RepairP10dSuite extends munit.FunSuite {
  import TestHttp.*

  private val Coauthors = "Who are the coauthors of Ada Lovelace?"
  private def body(q: String = Coauthors) = ujson.Obj("question" -> q).render()
  private def readyz(s: ServiceRuntime) = get(s.router, "/readyz", key = None)
  private def check(s: ServiceRuntime, name: String) = json(readyz(s))("checks")(name).str

  /** Blocks until released, ignoring interruption (a call that pins its thread). */
  private def pin(gate: CountDownLatch): Unit = {
    var done = false
    while (!done) {
      try done = { gate.await(10, TimeUnit.SECONDS); true }
      catch { case _: InterruptedException => () }
    }
  }

  // ------------------------------------------------------------------------------------- F3

  test("F3: the unproven-trial mark expires one open period later without any traffic") {
    val clock = new TestClock
    val b = new CircuitBreaker("x", 1, 100L, clock)
    assert(b.allow()); b.failure()
    clock.advance(100L)
    assert(b.allow()); b.notStarted()
    assert(!b.ready, "the trial never reached the dependency")
    clock.advance(99L)
    assert(!b.ready, "still within the period")
    clock.advance(1L)
    assert(b.ready, "the mark expired: an ordinary half-open circuit again")
    assertEquals(b.state, "half-open")
    assert(b.allow(), "its next call is the trial")
    b.success()
    assertEquals(b.state, "closed")
  }

  test(
    "F3: workflow overload -> NotStarted trial -> pinned call ends -> /readyz 200 after the period"
  ) {
    val w = new ServiceWorld
    val clock = new TestClock
    val gate = new CountDownLatch(1)
    val pinning = new AtomicBoolean(true)
    val inner = w.ragWorkflow
    val wf = new QueryWorkflow {
      def answer(q: String, question: String): WorkflowResult =
        if (pinning.getAndSet(false)) { pin(gate); throw new IllegalStateException("released") }
        else inner.answer(q, question)
    }
    val s = w.service(
      config = ServiceWorld.config(
        syncTimeoutMillis = 100L,
        workflowWorkers = 1,
        breakerFailureThreshold = 1,
        breakerOpenMillis = 1000L,
        queueWaitMillis = 100L
      ),
      workflow = wf,
      clock = clock
    )
    try {
      // 1. The one workflow thread is pinned: 504, breaker open.
      assertEquals(post(s.router, "/v1/query", body()).status, 504)
      clock.advance(1000L)
      // 2. Half-open: the trial cannot start (the thread is pinned): 503 overloaded, unproven.
      val t = post(s.router, "/v1/query", body())
      assertEquals((t.status, json(t)("error")("code").str), (503, "overloaded"))
      // 3. The pinned call ends; no further traffic arrives.
      gate.countDown()
      assert(eventually(5000)(check(s, "workflow-workers") == "ok"))
      val r = readyz(s)
      assertEquals(r.status, 503, r.bodyText)
      assertEquals(json(r)("checks")("circuit-workflow").str, "failing")
      // 4. One open period later, without traffic, readiness recovers.
      clock.advance(1000L)
      val ok = readyz(s)
      assertEquals(ok.status, 200, ok.bodyText)
      // And the next query is the trial; it succeeds and closes the circuit.
      assertEquals(post(s.router, "/v1/query", body()).status, 200)
      assertEquals(s.breakers.find(_.name == "workflow").get.state, "closed")
    } finally { gate.countDown(); s.close() }
  }

  /** An ownership record whose lookups can pin the key-value thread. */
  private final class PinningOwnership(inner: QueryOwnership) extends QueryOwnership {
    val pinning = new AtomicBoolean(false)
    val gate = new CountDownLatch(1)
    val returned = new CountDownLatch(1)
    def record(q: String, c: String) = inner.record(q, c)
    def owner(q: String): Either[StoreError, Option[String]] =
      if (pinning.getAndSet(false)) { pin(gate); returned.countDown(); inner.owner(q) }
      else inner.owner(q)
    def ownedBy(c: String, qs: Vector[String]) = inner.ownedBy(c, qs)
  }

  test("F3: key-value overload -> NotStarted trial -> /readyz 200 after the period") {
    val w = new ServiceWorld
    val clock = new TestClock
    val owners = new PinningOwnership(new InMemoryQueryOwnership(100))
    val s = w.service(
      config = ServiceWorld
        .config(
          readTimeoutMillis = 150L,
          readMaxAttempts = 1,
          breakerFailureThreshold = 1,
          breakerOpenMillis = 1000L,
          queueWaitMillis = 100L
        )
        .copy(keyValueWorkers = 1),
      clock = clock,
      ownership = owners
    )
    try {
      val q = json(post(s.router, "/v1/query", body()))("queryId").str
      owners.pinning.set(true)
      // 1. The one key-value thread is pinned: 504, breaker open.
      assertEquals(get(s.router, s"/v1/explain/$q").status, 504)
      assertEquals(s.breakers.find(_.name == "key-value").get.state, "open")
      clock.advance(1000L)
      // 2. Half-open: the trial cannot start: 503 overloaded, unproven.
      val t = get(s.router, s"/v1/explain/$q")
      assertEquals((t.status, json(t)("error")("code").str), (503, "overloaded"))
      // 3. The pinned call ends; no traffic.
      owners.gate.countDown()
      assert(owners.returned.await(5, TimeUnit.SECONDS))
      val r = readyz(s)
      assertEquals(r.status, 503, r.bodyText)
      assertEquals(json(r)("checks")("circuit-key-value").str, "failing")
      // 4. One open period later, without traffic, readiness recovers.
      clock.advance(1000L)
      assertEquals(readyz(s).status, 200)
      assertEquals(get(s.router, s"/v1/explain/$q").status, 200)
      assertEquals(s.breakers.find(_.name == "key-value").get.state, "closed")
    } finally { owners.gate.countDown(); s.close() }
  }

  // ------------------------------------------------------------------------------------- F4

  test("F4: a query's ownership record does not wait for another query's workflow lock") {
    val w = new ServiceWorld
    val entered = new CountDownLatch(1)
    val first = new AtomicBoolean(true)
    val inner = w.ragWorkflow
    // The first workflow holds the serialized store lock for 800 ms (well past 2 x 100 ms).
    val slow = new QueryWorkflow {
      def answer(q: String, question: String): WorkflowResult =
        if (first.getAndSet(false))
          w.access { entered.countDown(); Thread.sleep(800L); inner.answer(q, question) }
        else inner.answer(q, question)
    }
    val s = w.service(
      config = ServiceWorld.config(
        readTimeoutMillis = 100L,
        readMaxAttempts = 2,
        breakerFailureThreshold = 1
      ),
      workflow = slow
    )
    implicit val ec: ExecutionContext = ExecutionContext.global
    try {
      val a = Future(post(s.router, "/v1/query", body()))
      assert(entered.await(5, TimeUnit.SECONDS))
      val b = post(s.router, "/v1/query", body(), key = Some(FakeKeys.OtherKey))
      assertEquals(b.status, 200, b.bodyText)
      assertEquals(Await.result(a, 10.seconds).status, 200)
      assertEquals(s.breakers.find(_.name == "key-value").get.state, "closed")
      assertEquals(readyz(s).status, 200)
      // Ownership of both was recorded: each client explains its own query.
      val qb = json(b)("queryId").str
      assertEquals(get(s.router, s"/v1/explain/$qb", key = Some(FakeKeys.OtherKey)).status, 200)
    } finally s.close()
  }

  // ------------------------------------------------------------------------------------- U-b

  test("U-b: refused submissions record no owner, so they cannot evict older owners") {
    val w = new ServiceWorld
    val failing = new AtomicBoolean(false)
    val inner = w.ragWorkflow
    val wf = new QueryWorkflow {
      def answer(q: String, question: String): WorkflowResult =
        if (failing.get) throw new IllegalStateException("down") else inner.answer(q, question)
    }
    val s = w.service(
      config = ServiceWorld.config(breakerFailureThreshold = 1),
      workflow = wf,
      ownership = new InMemoryQueryOwnership(2)
    )
    try {
      val q1 = json(post(s.router, "/v1/query", body()))("queryId").str
      assertEquals(get(s.router, s"/v1/explain/$q1").status, 200)
      failing.set(true)
      assertEquals(post(s.router, "/v1/query", body()).status, 502) // admitted: recorded
      // The circuit is open (the test clock never advances): five refused submissions.
      (1 to 5).foreach { _ =>
        val r = post(s.router, "/v1/query", body())
        assertEquals((r.status, json(r)("error")("code").str), (503, "circuit-open"))
      }
      // Capacity 2 holds q1 and the admitted failure; the refusals evicted nothing.
      assertEquals(get(s.router, s"/v1/explain/$q1").status, 200)
    } finally s.close()
  }
}
