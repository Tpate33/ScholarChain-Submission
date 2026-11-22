package scholarchain.services

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import scholarchain.rag.agents.WorkflowResult
import scholarchain.storage.model.{BoundQuery, GraphRow, Item, PrimaryKey, StoreError, TableRole}
import scholarchain.storage.port.{GraphReader, KeyQuery, KeyValueReader}

/**
 * Timeouts, bounded retries and circuit breakers (SE-P10-01), with fakes that fail or hang. Every
 * hang waits on a latch with a bounded await and is released in `finally`; no long sleep.
 */
class ResilienceSuite extends munit.FunSuite {
  import TestHttp.*

  private val Body = ujson.Obj("question" -> "Who are the coauthors of Ada Lovelace?").render()

  private final class Hanging extends QueryWorkflow {
    val gate = new CountDownLatch(1)
    val calls = new AtomicInteger(0)
    def answer(queryId: String, question: String): WorkflowResult = {
      calls.incrementAndGet()
      gate.await(10, TimeUnit.SECONDS)
      throw new IllegalStateException("released")
    }
  }

  private final class Throwing extends QueryWorkflow {
    val calls = new AtomicInteger(0)
    def answer(queryId: String, question: String): WorkflowResult = {
      calls.incrementAndGet()
      throw new IllegalStateException("downstream secret detail")
    }
  }

  /** A graph reader whose first `failures` calls fail with `error` (or hang when `hang`). */
  private final class FlakyGraph(
      inner: GraphReader,
      failures: Int,
      error: StoreError,
      hang: Boolean
  ) extends GraphReader {
    val calls = new AtomicInteger(0)
    val gate = new CountDownLatch(1)
    def run(q: BoundQuery): Either[StoreError, Vector[GraphRow]] =
      if (calls.incrementAndGet() <= failures) {
        if (hang) gate.await(10, TimeUnit.SECONDS)
        Left(error)
      } else inner.run(q)
  }

  private final class FailingKv(inner: KeyValueReader, error: StoreError) extends KeyValueReader {
    val calls = new AtomicInteger(0)
    def get(t: TableRole, k: PrimaryKey) = { calls.incrementAndGet(); Left(error) }
    def query(t: TableRole, q: KeyQuery): Either[StoreError, Vector[Item]] = {
      calls.incrementAndGet()
      Left(error)
    }
  }

  test("timeout: a hanging workflow is 504 timeout within the sync bound, with the query ID") {
    val w = new ServiceWorld
    val wf = new Hanging
    val s = w.service(config = ServiceWorld.config(syncTimeoutMillis = 150L), workflow = wf)
    try {
      val t0 = System.nanoTime()
      val r = post(s.router, "/v1/query", Body)
      val ms = (System.nanoTime() - t0) / 1000000L
      assertEquals(r.status, 504, r.bodyText)
      val j = json(r)
      assertEquals(j("error")("code").str, "timeout")
      assert(j("error")("queryId").str.startsWith("query:"))
      assertEquals(j("error")("traceId").str, r.header("X-Trace-Id").get)
      assert(ms < 5000, s"took $ms ms")
    } finally { wf.gate.countDown(); s.close() }
  }

  test("timeout: an asynchronous job past its bound fails with code timeout") {
    val w = new ServiceWorld
    val wf = new Hanging
    val s = w.service(config = ServiceWorld.config(asyncTimeoutMillis = 100L), workflow = wf)
    try {
      val jobId = json(
        post(
          s.router,
          "/v1/query",
          ujson
            .Obj("question" -> "Who are the coauthors of Ada Lovelace?", "mode" -> "async")
            .render()
        )
      )("jobId").str
      assert(eventually(5000)(json(get(s.router, s"/v1/jobs/$jobId"))("state").str == "failed"))
      val j = json(get(s.router, s"/v1/jobs/$jobId"))
      assertEquals(j("error")("code").str, "timeout")
      assertEquals(get(s.router, s"/v1/jobs/$jobId/result").status, 504)
    } finally { wf.gate.countDown(); s.close() }
  }

  test("downstream failure: a failing workflow is 502 without its exception detail") {
    val w = new ServiceWorld
    val wf = new Throwing
    val s = w.service(workflow = wf)
    try {
      val r = post(s.router, "/v1/query", Body)
      assertEquals(r.status, 502)
      assertEquals(json(r)("error")("code").str, "downstream-failure")
      assert(!r.bodyText.contains("secret"), r.bodyText)
      assert(!r.bodyText.contains("IllegalStateException"), r.bodyText)
    } finally s.close()
  }

  test(
    "circuit breaker: opens after the threshold (503, workflow not called), a failed half-open trial re-opens it"
  ) {
    val w = new ServiceWorld
    val wf = new Throwing
    val clock = new TestClock
    val s = w.service(
      config = ServiceWorld.config(breakerFailureThreshold = 2, breakerOpenMillis = 1000L),
      workflow = wf,
      clock = clock
    )
    try {
      assertEquals(post(s.router, "/v1/query", Body).status, 502)
      assertEquals(post(s.router, "/v1/query", Body).status, 502)
      val open = post(s.router, "/v1/query", Body)
      assertEquals(open.status, 503)
      assertEquals(json(open)("error")("code").str, "circuit-open")
      assertEquals(open.header("Retry-After"), Some("2"))
      assertEquals(wf.calls.get, 2)
      assertEquals(get(s.router, "/readyz").status, 503)
      assertEquals(json(get(s.router, "/readyz"))("checks")("circuit-workflow").str, "failing")
      assertEquals(get(s.router, "/healthz").status, 200)
      clock.advance(1000L)
      // half-open: one trial call reaches the workflow; it fails again, so the circuit re-opens.
      assertEquals(post(s.router, "/v1/query", Body).status, 502)
      assertEquals(wf.calls.get, 3)
      assertEquals(post(s.router, "/v1/query", Body).status, 503)
      assertEquals(wf.calls.get, 3)
    } finally s.close()
  }

  test("circuit breaker unit: closed, open, half-open trial, success closes, neutral releases") {
    val clock = new TestClock
    val b = new CircuitBreaker("x", 2, 100L, clock)
    assert(b.allow()); b.failure()
    assertEquals(b.state, "closed")
    assert(b.allow()); b.failure()
    assertEquals(b.state, "open")
    assert(!b.allow())
    clock.advance(100L)
    assertEquals(b.state, "half-open")
    assert(b.allow())
    assert(!b.allow(), "only one trial call")
    b.neutral()
    assert(b.allow())
    b.success()
    assertEquals(b.state, "closed")
    assert(b.allow())
  }

  test("bounded retries: a transient graph error is retried, then the read succeeds") {
    var flaky: FlakyGraph = null
    val w = new ServiceWorld(graphWrap = g => {
      flaky = new FlakyGraph(g, 2, StoreError.Transient("throttled"), false); flaky
    })
    val s = w.service(config = ServiceWorld.config(readMaxAttempts = 3))
    try {
      val r = get(s.router, s"/v1/graph/nodes/${ServiceWorld.ada}")
      assertEquals(r.status, 200, r.bodyText)
      assertEquals(flaky.calls.get, 3)
    } finally s.close()
  }

  test("bounded retries: attempts are capped; then 502 downstream-failure, no store detail") {
    var flaky: FlakyGraph = null
    val w = new ServiceWorld(graphWrap = g => {
      flaky = new FlakyGraph(g, 100, StoreError.Transient("node-1.internal throttled"), false);
      flaky
    })
    val s = w.service(config = ServiceWorld.config(readMaxAttempts = 2))
    try {
      val r = get(s.router, s"/v1/graph/nodes/${ServiceWorld.ada}")
      assertEquals(r.status, 502)
      assertEquals(json(r)("error")("code").str, "downstream-failure")
      assert(!r.bodyText.contains("internal"), r.bodyText)
      assertEquals(flaky.calls.get, 2)
    } finally s.close()
  }

  test("permanent and corrupt store errors are not retried") {
    Vector(StoreError.Permanent("denied"), StoreError.Corrupt("bad hash")).foreach { error =>
      var flaky: FlakyGraph = null
      val w = new ServiceWorld(graphWrap = g => {
        flaky = new FlakyGraph(g, 100, error, false); flaky
      })
      val s = w.service(config = ServiceWorld.config(readMaxAttempts = 3))
      try {
        assertEquals(get(s.router, s"/v1/graph/nodes/${ServiceWorld.ada}").status, 502, error)
        assertEquals(flaky.calls.get, 1, error)
      } finally s.close()
    }
  }

  test("a half-open trial that succeeds closes the circuit again") {
    var flaky: FlakyGraph = null
    val clock = new TestClock
    val w = new ServiceWorld(graphWrap = g => {
      flaky = new FlakyGraph(g, 2, StoreError.Permanent("down"), false); flaky
    })
    val s = w.service(
      config = ServiceWorld.config(readMaxAttempts = 1, breakerFailureThreshold = 2),
      clock = clock
    )
    try {
      val node = s"/v1/graph/nodes/${ServiceWorld.ada}"
      assertEquals(get(s.router, node).status, 502)
      assertEquals(get(s.router, node).status, 502)
      assertEquals(get(s.router, node).status, 503)
      clock.advance(60000L)
      assertEquals(get(s.router, node).status, 200)
      assertEquals(get(s.router, node).status, 200)
      assertEquals(flaky.calls.get, 4)
      assertEquals(get(s.router, "/readyz").status, 200)
    } finally s.close()
  }

  test("read timeout: a hanging graph read is 504 after the bounded attempts") {
    var flaky: FlakyGraph = null
    val w = new ServiceWorld(graphWrap = g => {
      flaky = new FlakyGraph(g, 100, StoreError.Transient("x"), true); flaky
    })
    val s = w.service(config = ServiceWorld.config(readTimeoutMillis = 80L, readMaxAttempts = 2))
    try {
      val r = get(s.router, s"/v1/graph/nodes/${ServiceWorld.ada}")
      assertEquals(r.status, 504, r.bodyText)
      assertEquals(json(r)("error")("code").str, "timeout")
    } finally { flaky.gate.countDown(); s.close() }
  }

  test("key-value outage: evidence reads fail 502, then the key-value circuit opens") {
    var failing: FailingKv = null
    val w = new ServiceWorld(kvWrap = kv => {
      failing = new FailingKv(kv, StoreError.Transient("down")); failing
    })
    val s =
      w.service(config = ServiceWorld.config(readMaxAttempts = 1, breakerFailureThreshold = 2))
    try {
      assertEquals(get(s.router, "/v1/evidence/ev:" + "1" * 64).status, 502)
      // (Explain of an unowned query is a 404 decided before any store read: SE-P10-03 N1.)
      assertEquals(get(s.router, "/v1/evidence/ev:" + "2" * 64).status, 502)
      val open = get(s.router, "/v1/evidence/ev:" + "1" * 64)
      assertEquals(json(open)("error")("code").str, "circuit-open")
      assertEquals(failing.calls.get, 2)
      // The graph circuit is independent.
      assertEquals(get(s.router, s"/v1/graph/nodes/${ServiceWorld.ada}").status, 200)
    } finally s.close()
  }

  test("job capacity: more unfinished jobs than retained is 429 with Retry-After") {
    val w = new ServiceWorld
    val wf = new Hanging
    val s = w.service(
      config = ServiceWorld.config(maxJobs = 2, jobWorkers = 1, jobQueueCapacity = 4),
      workflow = wf
    )
    try {
      val async = ujson
        .Obj("question" -> "Who are the coauthors of Ada Lovelace?", "mode" -> "async")
        .render()
      assertEquals(post(s.router, "/v1/query", async).status, 202)
      assertEquals(post(s.router, "/v1/query", async).status, 202)
      val r = post(s.router, "/v1/query", async)
      assertEquals(r.status, 429)
      assertEquals(json(r)("error")("code").str, "too-many-requests")
      assertEquals(r.header("Retry-After"), Some("2"))
    } finally { wf.gate.countDown(); s.close() }
  }
}
