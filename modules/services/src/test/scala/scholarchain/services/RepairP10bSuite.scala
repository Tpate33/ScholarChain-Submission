package scholarchain.services

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JRequest, HttpResponse as JResponse}
import java.time.Duration
import java.util.concurrent.{CompletableFuture, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import scholarchain.rag.agents.WorkflowResult
import scholarchain.storage.model.{PrimaryKey, TableRole}
import scholarchain.storage.port.{KeyQuery, KeyValueReader}

/** SE-P10-03 Part A (CR-P10-03 repair 02): one feature test or more per finding. */
class RepairP10bSuite extends munit.FunSuite {
  import TestHttp.*

  private val Coauthors = "Who are the coauthors of Ada Lovelace?"
  private def body(q: String = Coauthors, mode: String = "sync") =
    ujson.Obj("question" -> q, "mode" -> mode).render()

  /** Runs the rag workflow until `hang`, then blocks on `gate` (interruptible). */
  private final class Switchable(inner: QueryWorkflow) extends QueryWorkflow {
    val hang = new AtomicBoolean(false)
    val gate = new CountDownLatch(1)
    val entered = new CountDownLatch(1)
    val calls = new AtomicInteger(0)
    def answer(queryId: String, question: String): WorkflowResult = {
      calls.incrementAndGet()
      if (hang.get()) {
        entered.countDown()
        gate.await(10, TimeUnit.SECONDS)
        throw new IllegalStateException("released")
      } else inner.answer(queryId, question)
    }
  }

  // ------------------------------------------------------------------------------------- N1

  test("N1: evidence names no query; explain is served only to the client that submitted") {
    val w = new ServiceWorld
    val s = w.service()
    try {
      val a = json(post(s.router, "/v1/query", body()))
      val q = a("queryId").str
      a("links")("evidence").arr.map(_.str).foreach { l =>
        val e = get(s.router, l, key = Some(FakeKeys.OtherKey))
        assertEquals(e.status, 200, "evidence is shared across clients (design decision)")
        assert(!e.bodyText.contains(q), s"the query ID leaks through $l")
        assert(json(e)("occurrences").arr.forall(o => !o.obj.contains("queryId")))
      }
      assertEquals(get(s.router, s"/v1/explain/$q").status, 200)
      val other = get(s.router, s"/v1/explain/$q", key = Some(FakeKeys.OtherKey))
      assertEquals(other.status, 404, "another client's query is indistinguishable from unknown")
      assertEquals(json(other)("error")("message").str, "no audit record for this query")
      assertEquals(
        json(get(s.router, "/v1/explain/query:never-asked"))("error")("message").str,
        "no audit record for this query"
      )
    } finally s.close()
  }

  test("N1: the bounded ownership record forgets the oldest query (its explain is then 404)") {
    val o = new InMemoryQueryOwnership(2)
    o.record("query:a", "c1")
    o.record("query:b", "c2")
    o.record("query:c", "c1")
    assertEquals(o.owner("query:a"), Right(None))
    assertEquals(o.owner("query:b"), Right(Some("c2")))
    assertEquals(o.owner("query:c"), Right(Some("c1")))
    assertEquals(o.ownedBy("c1", Vector("query:a", "query:b", "query:c")), Right(Set("query:c")))
  }

  // ------------------------------------------------------------------------------------- N2

  test("N2: with every HTTP worker busy, /healthz and /readyz still answer and others get 503") {
    val w = new ServiceWorld
    val wf = new Switchable(w.ragWorkflow)
    wf.hang.set(true)
    val s = w.service(workflow = wf)
    val server = HttpServerAdapter
      .start(s.router, HttpServerAdapter.Settings("127.0.0.1", 0, 16, 1, 1, 16384, 4))
      .fold(e => fail(e), identity)
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    def uri(p: String) = URI.create(s"http://127.0.0.1:${server.port}$p")
    def postQuery(): JRequest =
      JRequest
        .newBuilder(uri("/v1/query"))
        .timeout(Duration.ofSeconds(20))
        .header("Authorization", s"Bearer ${FakeKeys.FullKey}")
        .header("Content-Type", "application/json")
        .POST(JRequest.BodyPublishers.ofString(body()))
        .build()
    try {
      val running = client.sendAsync(postQuery(), JResponse.BodyHandlers.ofString())
      assert(wf.entered.await(5, TimeUnit.SECONDS))
      val waiting = client.sendAsync(postQuery(), JResponse.BodyHandlers.ofString())
      assert(eventually(5000)(server.queued == 1), "the one queue slot is taken")
      // A third slow request: before the fix it ran on the JDK dispatcher thread and blocked it.
      val t0 = System.nanoTime()
      val third = client.send(
        JRequest
          .newBuilder(uri("/v1/query"))
          .timeout(Duration.ofSeconds(5))
          .header("Authorization", s"Bearer ${FakeKeys.FullKey}")
          .header("Content-Type", "application/json")
          .header("X-Trace-Id", "trace-overload-0001")
          .POST(JRequest.BodyPublishers.ofString(body()))
          .build(),
        JResponse.BodyHandlers.ofString()
      )
      assertEquals(third.statusCode(), 503, third.body())
      assertEquals(ujson.read(third.body())("error")("code").str, "overloaded")
      assertEquals(ujson.read(third.body())("error")("traceId").str, "trace-overload-0001")
      assert((System.nanoTime() - t0) / 1000000L < 3000)
      Vector("/healthz", "/readyz").foreach { p =>
        val h = client.send(
          JRequest.newBuilder(uri(p)).timeout(Duration.ofSeconds(5)).GET().build(),
          JResponse.BodyHandlers.ofString()
        )
        assertEquals(h.statusCode(), 200, s"$p: ${h.body()}")
      }
      assertEquals(wf.calls.get, 1, "the overloaded request never reached the workflow")
      wf.gate.countDown()
      running.get(10, TimeUnit.SECONDS)
      waiting.get(10, TimeUnit.SECONDS)
    } finally {
      wf.gate.countDown()
      server.stop(0)
      s.close()
    }
  }

  // ------------------------------------------------------------------------------------- N3

  private final class Probe(blockBefore: Boolean) extends CallsProbe {
    val atHook = new CountDownLatch(1)
    val proceed = new CountDownLatch(1)
    private def hold(): Unit = { atHook.countDown(); proceed.await(5, TimeUnit.SECONDS); () }
    def beforeClaim(): Unit = if (blockBefore) hold()
    def afterClaim(): Unit = if (!blockBefore) hold()
  }

  test("N3: caller withdraws first: the call is NotStarted and never runs (CAS claim)") {
    val pool = Pools.bounded("n3a", 1, 1)
    val probe = new Probe(blockBefore = true)
    val calls = new Calls(pool, 50L, probe)
    val ran = new AtomicInteger(0)
    try {
      val r = calls.run(5000L)(ran.incrementAndGet())
      assertEquals(r, Left(CallFailure.NotStarted))
      assert(probe.atHook.await(5, TimeUnit.SECONDS), "the worker reached the claim")
      probe.proceed.countDown() // the worker now tries to claim and loses
      assert(eventually(5000)(pool.getActiveCount == 0))
      assertEquals(ran.get, 0)
    } finally pool.shutdownNow()
  }

  test("N3: worker claims first: the caller waits for the result, never reports NotStarted") {
    val pool = Pools.bounded("n3b", 1, 1)
    val probe = new Probe(blockBefore = false)
    val calls = new Calls(pool, 50L, probe)
    val ran = new AtomicInteger(0)
    try {
      // Release the worker well after the caller's 50 ms queue-wait bound has expired.
      val releaser = new Thread(() => {
        probe.atHook.await(5, TimeUnit.SECONDS)
        Thread.sleep(200)
        probe.proceed.countDown()
      })
      releaser.setDaemon(true)
      releaser.start()
      assertEquals(calls.run(5000L)(ran.incrementAndGet()), Right(1))
      assertEquals(ran.get, 1)
    } finally { probe.proceed.countDown(); pool.shutdownNow(); () }
  }

  // ------------------------------------------------------------------------------------- N4

  test("N4: after a 504 the key stays in flight until the workflow ends; it runs once") {
    val w = new ServiceWorld
    val gate = new CountDownLatch(1)
    val runs = new AtomicInteger(0)
    val ignoring = new QueryWorkflow {
      def answer(queryId: String, question: String): WorkflowResult = {
        runs.incrementAndGet()
        var done = gate.getCount == 0
        while (!done) {
          try done = { gate.await(10, TimeUnit.SECONDS); true }
          catch { case _: InterruptedException => () } // ignores cancellation
        }
        throw new IllegalStateException("released")
      }
    }
    val s = w.service(config = ServiceWorld.config(syncTimeoutMillis = 150L), workflow = ignoring)
    try {
      val first = post(s.router, "/v1/query", body(), idempotencyKey = Some("n4"))
      assertEquals(first.status, 504, first.bodyText)
      val retry = post(s.router, "/v1/query", body(), idempotencyKey = Some("n4"))
      assertEquals(retry.status, 409, retry.bodyText)
      assertEquals(json(retry)("error")("code").str, "idempotency-in-progress")
      assertEquals(runs.get, 1)
      gate.countDown()
      assert(eventually(5000) {
        post(s.router, "/v1/query", body(), idempotencyKey = Some("n4")).status != 409
      })
      assertEquals(runs.get, 2, "a new execution only once the first one ended")
    } finally { gate.countDown(); s.close() }
  }

  // ------------------------------------------------------------------------------------- X1

  /**
   * SE-P10-04 n4: a deterministic slow store. When armed, its first call advances the service's
   * TestClock by `firstMillis` (the time it "took"); every later call blocks on `gate`, so it times
   * out for certain. Truncation is decided by the TestClock, never by wall-clock margins.
   */
  private final class ScriptedKv(inner: KeyValueReader, clock: TestClock, firstMillis: Long)
      extends KeyValueReader {
    @volatile var armed = false
    val gate = new CountDownLatch(1)
    private val n = new AtomicInteger(0)
    private def call[A](a: => A): A =
      if (!armed) a
      else if (n.incrementAndGet() == 1) { clock.advance(firstMillis); a }
      else { gate.await(10, TimeUnit.SECONDS); a }
    def get(t: TableRole, k: PrimaryKey) = call(inner.get(t, k))
    def query(t: TableRole, q: KeyQuery) = call(inner.query(t, q))
  }

  private def x1(requestDeadlineMillis: Long): (Int, String) = {
    val clock = new TestClock
    var kv: ScriptedKv = null
    val w = new ServiceWorld(kvWrap = inner => { kv = new ScriptedKv(inner, clock, 150L); kv })
    val s = w.service(
      config = ServiceWorld.config(
        readTimeoutMillis = 200L,
        readMaxAttempts = 3,
        readRequestTimeoutMillis = requestDeadlineMillis,
        breakerFailureThreshold = 1
      ),
      clock = clock
    )
    try {
      val q = json(post(s.router, "/v1/query", body()))("queryId").str
      kv.armed = true
      val status = get(s.router, s"/v1/explain/$q").status
      (status, json(get(s.router, "/readyz", key = None))("checks")("circuit-key-value").str)
    } finally { if (kv != null) kv.gate.countDown(); s.close() }
  }

  test("X1: an attempt cut short by the request deadline is no breaker failure (TestClock)") {
    // Deadline 250 ms: the audit read "takes" 150 ms, so the step read gets 100 ms < 200 ms: cut.
    assertEquals(x1(250L), (504, "ok"))
  }

  test("X1 control: a full-length attempt that times out IS a breaker failure") {
    // Deadline 5 s (below the 10 s workflow timeouts, SE-P10-06 m-A rule): the step read gets
    // its full 200 ms and times out: the circuit opens.
    assertEquals(x1(5000L), (504, "failing"))
  }

  test("X1: config refuses a request deadline shorter than one read attempt") {
    val c = ServiceWorld.config()
    assert(
      ServiceConfig
        .problems(c.copy(readRequestTimeoutMillis = c.readTimeoutMillis - 1))
        .exists(_.startsWith("readRequestTimeoutMillis"))
    )
  }

  // ------------------------------------------------------------------------------------- X2

  test("X2: queue wait is charged to the call's total budget") {
    val pool = Pools.bounded("x2", 1, 1)
    val calls = new Calls(pool, 1000L)
    try {
      pool.execute(() => Thread.sleep(200)) // the only thread is busy for 200 ms
      // Total 300 ms: about 200 ms in the queue leaves about 100 ms of run time for a 150 ms call.
      val r = calls.run(1000L, 300L)(Thread.sleep(150))
      assertEquals(r, Left(CallFailure.TimedOut))
    } finally { pool.shutdownNow(); () }
  }

  // ------------------------------------------------------------------------------------- X3

  test("X3: a closed, deeply nested body is invalid-json before any parse") {
    val w = new ServiceWorld
    val s = w.service(config = ServiceWorld.config().copy(maxBodyBytes = 300000))
    try {
      val closed = "[" * 5000 + "]" * 5000
      val r = post(s.router, "/v1/query", closed)
      // Without the scan this parses (or overflows); its error would not be invalid-json.
      assertEquals((r.status, json(r)("error")("code").str), (400, "invalid-json"))
      val shallow = "[" * 16 + "]" * 16
      assertEquals(
        json(post(s.router, "/v1/query", shallow))("error")("code").str,
        "invalid-request"
      )
    } finally s.close()
  }

  // ------------------------------------------------------------------------------------- X4

  test("X4: occurrencesTruncated is true only when more rows exist than are shown") {
    def occurrences(max: Int) = {
      val w = new ServiceWorld
      val s = w.service(config = ServiceWorld.config().copy(evidenceMaxBundles = max))
      try {
        val a = json(post(s.router, "/v1/query", body()))
        post(s.router, "/v1/query", body()) // a second bundle citing the same evidence
        val e = json(get(s.router, a("links")("evidence").arr.head.str))
        (e("occurrences").arr.size, e("occurrencesTruncated").bool)
      } finally s.close()
    }
    assertEquals(occurrences(2), (2, false))
    assertEquals(occurrences(1), (1, true))
    assertEquals(occurrences(3), (2, false))
  }

  // ------------------------------------------------------------------------------------- U-c

  test("U-c: semantic gap lines are fixed templates (index name, configured bound)") {
    // rag SemanticContextAgent builds gaps only as "index <name> is not configured" and
    // "index <name> skipped: maxRetrievals <n> reached"; with every index configured there is none.
    val w = new ServiceWorld
    w.addSemanticDocs()
    val s = w.service()
    try {
      val r = json(
        post(s.router, "/v1/query", body("Explain the research on graph methods by Ada Lovelace"))
      )
      assertEquals(r("status").str, "answered")
      val text = r("answer")("answerText").str
      assert(!text.contains("is not configured") && !text.contains("skipped: maxRetrievals"))
    } finally s.close()
  }

  test("CompletableFuture settle hook used by N4 runs once the call has ended") {
    val pool = Pools.bounded("settle", 1, 1)
    val calls = new Calls(pool, 1000L)
    val gate = new CountDownLatch(1)
    try {
      val (r, settled) = calls.runTracked(50L) {
        var done = false
        while (!done) {
          try done = { gate.await(5, TimeUnit.SECONDS); true }
          catch { case _: InterruptedException => () }
        }
      }
      assertEquals(r, Left(CallFailure.TimedOut))
      assert(!settled.isDone, "still running after its timeout")
      gate.countDown()
      settled.get(5, TimeUnit.SECONDS)
      assert(settled.isDone)
    } finally { gate.countDown(); pool.shutdownNow(); () }
  }
}
