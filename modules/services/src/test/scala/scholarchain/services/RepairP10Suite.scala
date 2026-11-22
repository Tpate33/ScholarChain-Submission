package scholarchain.services

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import scala.jdk.CollectionConverters.*

import scholarchain.rag.agents.WorkflowResult
import scholarchain.storage.model.{Item, PrimaryKey, StoreError, TableRole}
import scholarchain.storage.port.{KeyQuery, KeyValueReader}

/** SE-P10-02 (CR-P10-02 repair 01): one feature test or more per finding. */
class RepairP10Suite extends munit.FunSuite {
  import TestHttp.*

  private def body(q: String, mode: String = "sync") =
    ujson.Obj("question" -> q, "mode" -> mode).render()

  private val Coauthors = "Who are the coauthors of Ada Lovelace?"

  /** A workflow that runs the rag workflow until `hang` is set, then blocks on `gate`. */
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

  // ------------------------------------------------------------------------------------- M1

  test("M1: one chunk retrieved by two questions at different distances resolves (no 502)") {
    val w = new ServiceWorld
    w.addSemanticDocs()
    val s = w.service()
    try {
      val q1 = "Explain the research on graph methods by Ada Lovelace"
      val q2 = "Describe the research topics of Ada Lovelace"
      val a = post(s.router, "/v1/query", body(q1))
      val b = post(s.router, "/v1/query", body(q2))
      assertEquals((a.status, b.status), (200, 200), a.bodyText + "\n" + b.bodyText)
      val ja = json(a)
      val jb = json(b)
      assertEquals((ja("status").str, jb("status").str), ("answered", "answered"))
      def items(j: ujson.Value) =
        j("answer")("evidenceBundle")("items").arr.map(i => i("evidenceId").str -> i).toMap
      val ia = items(ja)
      val ib = items(jb)
      val shared = ia.keySet.intersect(ib.keySet).filter { id =>
        ia(id)("evidenceType").str == "text-chunk" && ia(id)("score") != ib(id)("score")
      }
      assert(shared.nonEmpty, s"no shared chunk at different distances: ${ia.keySet & ib.keySet}")
      val bundleA = ja("answer")("evidenceBundle")("bundleId").str
      val bundleB = jb("answer")("evidenceBundle")("bundleId").str
      shared.foreach { id =>
        val e = get(s.router, s"/v1/evidence/$id")
        assertEquals(e.status, 200, e.bodyText)
        val occ = json(e)("occurrences").arr.map(o => o("bundleId").str -> o).toMap
        assertEquals(occ.keySet, Set(bundleA, bundleB))
        assertEquals(occ(bundleA)("score"), ia(id)("score"))
        assertEquals(occ(bundleB)("score"), ib(id)("score"))
        // SE-P10-03 N1: an occurrence never names the query (or client) that cited it.
        assert(occ.values.forall(o => !o.obj.contains("queryId")))
        assert(!e.bodyText.contains(ja("queryId").str) && !e.bodyText.contains(jb("queryId").str))
        assert(!json(e).obj.contains("score"), "no single merged score")
      }
      // End to end: every links.evidence URL of both answers resolves.
      (ja("links")("evidence").arr ++ jb("links")("evidence").arr).map(_.str).foreach { l =>
        assertEquals(get(s.router, l).status, 200, l)
      }
    } finally s.close()
  }

  // ------------------------------------------------------------------------------------- M2

  test("M2: a saturated workflow pool leaves evidence, graph and explain reads and /readyz up") {
    val w = new ServiceWorld
    val wf = new Switchable(w.ragWorkflow)
    val s = w.service(
      config = ServiceWorld.config(
        workflowWorkers = 1,
        workflowQueueCapacity = 1,
        queueWaitMillis = 100L
      ),
      workflow = wf
    )
    try {
      val first = json(post(s.router, "/v1/query", body(Coauthors)))
      val queryId = first("queryId").str
      val evidence = first("links")("evidence").arr.head.str
      wf.hang.set(true)
      val blocker = new Thread(() => { post(s.router, "/v1/query", body(Coauthors)); () })
      blocker.setDaemon(true)
      blocker.start()
      assert(wf.entered.await(5, TimeUnit.SECONDS))
      // The workflow pool's only thread is busy: a further call waits its queue bound and is
      // withdrawn unstarted: 503 overloaded, the workflow is not called, no breaker failure.
      val queued = post(s.router, "/v1/query", body(Coauthors))
      assertEquals(queued.status, 503, queued.bodyText)
      assertEquals(json(queued)("error")("code").str, "overloaded")
      assertEquals(wf.calls.get, 2)
      // Reads use their own pools.
      assertEquals(get(s.router, evidence).status, 200)
      assertEquals(get(s.router, s"/v1/graph/nodes/${ServiceWorld.ada}").status, 200)
      assertEquals(get(s.router, s"/v1/explain/$queryId").status, 200)
      val ready = get(s.router, "/readyz", key = None)
      assertEquals(ready.status, 200, ready.bodyText)
      assertEquals(json(ready)("checks")("circuit-key-value").str, "ok")
      assertEquals(json(ready)("checks")("circuit-workflow").str, "ok")
      assertEquals(s.breakers.map(b => b.name -> b.state).toMap.apply("key-value"), "closed")
    } finally { wf.gate.countDown(); s.close() }
  }

  test(
    "M2: a call that never starts is NotStarted (no run, no retry); run time counts from start"
  ) {
    val pool = Pools.bounded("test", 1, 1)
    val calls = new Calls(pool, 50L)
    val gate = new CountDownLatch(1)
    val ran = new AtomicInteger(0)
    try {
      val busy = new Thread(() => { calls.run(5000L)(gate.await(5, TimeUnit.SECONDS)); () })
      busy.setDaemon(true)
      busy.start()
      assert(TestHttp.eventually(2000)(pool.getActiveCount == 1))
      val r = calls.run(5000L)(ran.incrementAndGet())
      assertEquals(r, Left(CallFailure.NotStarted))
      assert(CallFailure.NotStarted.neverStarted)
      gate.countDown()
      busy.join(5000)
      assertEquals(ran.get, 0, "a withdrawn call never runs")
      // Once a thread is free, a call that waited is timed from its start, not from submission.
      assertEquals(calls.run(5000L)(ran.incrementAndGet()), Right(1))
    } finally { gate.countDown(); pool.shutdownNow(); () }
  }

  // ------------------------------------------------------------------------------- m1 and m2

  test("m1: an abstention body carries the reason tag only, never AgentError or store detail") {
    val failing = (inner: KeyValueReader) =>
      new KeyValueReader {
        private val e =
          StoreError.Transient(
            "EntityLinkCache pk=surface#deadbeef role=researcher-metrics secret-x"
          )
        def get(t: TableRole, k: PrimaryKey) = Left(e)
        def query(t: TableRole, q: KeyQuery): Either[StoreError, Vector[Item]] = Left(e)
      }
    val w = new ServiceWorld(kvWrap = failing)
    val s = w.service()
    try {
      val r = post(s.router, "/v1/query", body(Coauthors))
      assertEquals(r.status, 200, r.bodyText)
      val j = json(r)
      assertEquals(j("status").str, "abstained")
      assertEquals(j("abstention")("reason").str, "stage-failure")
      assert(!j("abstention").obj.contains("detail"))
      Vector("EntityLinkCache", "surface#", "researcher-metrics", "secret-x", "store:", "Ada")
        .foreach(f => assert(!r.bodyText.contains(f), s"$f in ${r.bodyText}"))
    } finally s.close()
  }

  test("m2: an ambiguous name abstains through the workflow (frozen example, no name echoed)") {
    val w = new ServiceWorld
    val s = w.service()
    try {
      val q = "Who are the coauthors of John Smith?"
      val r = post(s.router, "/v1/query", body(q))
      assertEquals(r.status, 200, r.bodyText)
      val j = json(r)
      assertEquals(ApiContract.check("query-abstained.response.json", j), Vector.empty)
      assertEquals(
        j("abstention")("names")(0)("candidateIds").arr.map(_.str).toSet,
        Set(ServiceWorld.bob, ServiceWorld.cyd)
      )
      Vector("John", "Smith", q).foreach(f => assert(!r.bodyText.contains(f), f))
      // The explain link of the abstention resolves to its audit (outcome abstained).
      val x = get(s.router, j("links")("explain").str)
      assertEquals(json(x)("outcome").str, "abstained")
    } finally s.close()
  }

  // ------------------------------------------------------------------------------------- m3

  test("m3: a request still running past the idempotency TTL is never executed twice") {
    val w = new ServiceWorld
    val wf = new Switchable(w.ragWorkflow)
    wf.hang.set(true)
    val clock = new TestClock
    val s = w.service(
      config = ServiceWorld.config(syncTimeoutMillis = 10000L, idempotencyTtlMillis = 10001L),
      workflow = wf,
      clock = clock
    )
    try {
      val first = new Thread(() => {
        post(s.router, "/v1/query", body(Coauthors), idempotencyKey = Some("ttl")); ()
      })
      first.setDaemon(true)
      first.start()
      assert(wf.entered.await(5, TimeUnit.SECONDS))
      clock.advance(20000L) // past the TTL, the first execution still in flight
      val repeat = post(s.router, "/v1/query", body(Coauthors), idempotencyKey = Some("ttl"))
      assertEquals(repeat.status, 409, repeat.bodyText)
      assertEquals(json(repeat)("error")("code").str, "idempotency-in-progress")
      assertEquals(wf.calls.get, 1)
    } finally { wf.gate.countDown(); s.close() }
  }

  // ------------------------------------------------------------------------------------- m4

  /** A key-value reader whose calls take `delayMillis` (set after the setup reads). */
  private final class SlowKv(inner: KeyValueReader) extends KeyValueReader {
    @volatile var delayMillis = 0L
    val calls = new AtomicInteger(0)
    private def slow[A](a: => A): A = {
      calls.incrementAndGet()
      if (delayMillis > 0) Thread.sleep(delayMillis)
      a
    }
    def get(t: TableRole, k: PrimaryKey) = slow(inner.get(t, k))
    def query(t: TableRole, q: KeyQuery) = slow(inner.query(t, q))
  }

  test("m4: explain has one request deadline across its reads (slow store: 504 in bound)") {
    var slow: SlowKv = null
    val w = new ServiceWorld(kvWrap = kv => { slow = new SlowKv(kv); slow })
    val s = w.service(
      config = ServiceWorld.config(
        readTimeoutMillis = 200L,
        readMaxAttempts = 3,
        readRequestTimeoutMillis = 250L
      ),
      clock = ServiceClock.system
    )
    try {
      val q = json(post(s.router, "/v1/query", body(Coauthors)))("queryId").str
      assertEquals(get(s.router, s"/v1/explain/$q").status, 200)
      slow.delayMillis = 150L
      val t0 = System.nanoTime()
      val r = get(s.router, s"/v1/explain/$q")
      val ms = (System.nanoTime() - t0) / 1000000L
      // Each read alone fits its 200 ms attempt bound; together they exceed the 250 ms deadline.
      assertEquals(r.status, 504, r.bodyText)
      assertEquals(json(r)("error")("code").str, "timeout")
      // Loose wall-clock bound (machine load); the 504 above is what the deadline decides.
      assert(ms < 3000, s"took $ms ms")
    } finally { if (slow != null) slow.delayMillis = 0L; s.close() }
  }

  // ------------------------------------------------------------------------------------ nits

  test("n1: a request method outside the standard set is logged as OTHER") {
    assertEquals(Router.safeMethod("GET"), "GET")
    assertEquals(Router.safeMethod("GET\nforged=1"), "OTHER")
    assertEquals(Router.safeMethod(null), "OTHER")
    val w = new ServiceWorld
    val log = new RecordingLog
    val s = w.service(log = log)
    try {
      s.router.handle(HttpRequest("X\r\nY", "/healthz", None, Map.empty, Array.emptyByteArray))
      assertEquals(log.events.asScala.toVector.map(_.method), Vector("OTHER"))
      assert(!log.events.asScala.head.render.contains("\n"))
    } finally s.close()
  }

  /** Tokens like [[CountingIds]], throwing on the listed call numbers. */
  private final class FlakyIds(failAt: Set[Int]) extends IdSource {
    private val n = new AtomicInteger(0)
    def token(): String = {
      val i = n.incrementAndGet()
      if (failAt(i)) throw new IllegalStateException("id source failed")
      f"$i%032d"
    }
  }

  test("n2: an in-flight idempotency key is released when the request throws unexpectedly") {
    val w = new ServiceWorld
    val s = w.service(ids = new FlakyIds(Set(2)))
    val trace = Vector("X-Trace-Id" -> "trace-fixed-0001")
    try {
      // Call 1 is the request ID; call 2 (the query ID) throws inside the idempotent execution.
      val a =
        post(s.router, "/v1/query", body(Coauthors), idempotencyKey = Some("n2"), headers = trace)
      assertEquals(a.status, 500, a.bodyText)
      val b =
        post(s.router, "/v1/query", body(Coauthors), idempotencyKey = Some("n2"), headers = trace)
      assertEquals(b.status, 200, b.bodyText)
    } finally s.close()
  }

  test("n2: a job whose result cannot be rendered still reaches a final state") {
    val w = new ServiceWorld
    val broken = new QueryWorkflow {
      def answer(queryId: String, question: String): WorkflowResult = null
    }
    val s = w.service(workflow = broken)
    try {
      val sync = post(s.router, "/v1/query", body(Coauthors))
      assertEquals((sync.status, json(sync)("error")("code").str), (500, "internal"))
      val jobId = json(post(s.router, "/v1/query", body(Coauthors, "async")))("jobId").str
      assert(eventually(5000)(json(get(s.router, s"/v1/jobs/$jobId"))("state").str == "failed"))
      assertEquals(json(get(s.router, s"/v1/jobs/$jobId"))("error")("code").str, "internal")
    } finally s.close()
  }

  test("n4: the Bearer scheme is case-insensitive; the key is not") {
    val a = new ApiKeyAuthenticator(new FakeKeys, 1)
    Vector("bearer", "BEARER", "BeArEr").foreach { scheme =>
      assertEquals(
        a.authenticate(Vector(s"$scheme ${FakeKeys.ReaderKey}")).map(_.clientId),
        Right("client-reader"),
        scheme
      )
    }
    assert(a.authenticate(Vector(s"Bearer ${FakeKeys.ReaderKey.toUpperCase}")).isLeft)
  }

  test("n5: an unexpected exception is a 500 counted under the matched route") {
    val w = new ServiceWorld
    val throwing = new ApiKeyDigestSource {
      def clients(): Either[String, Vector[ApiClient]] = throw new IllegalStateException("boom")
    }
    val s = w.service(keys = throwing)
    try {
      val r = get(s.router, "/v1/evidence/ev:" + "0" * 64)
      assertEquals(r.status, 500)
      assertEquals(ApiContract.check("error.response.json", json(r)), Vector.empty)
      assert(!r.bodyText.contains("boom"))
      assertEquals(s.metrics.count("GET /v1/evidence/{evidenceId}", 500), 1L)
      assertEquals(s.metrics.count("unmatched", 500), 0L)
    } finally s.close()
  }

  test(
    "U3: nesting beyond the bound is invalid-json before parsing; brackets in strings count not"
  ) {
    assertEquals(QueryRequest.nesting("[[["), 3)
    assertEquals(QueryRequest.nesting("""{"q":"[[[[[[\"[[[["}"""), 1)
    val w = new ServiceWorld
    val s = w.service(config = ServiceWorld.config().copy(maxBodyBytes = 300000))
    try {
      val deep = "[" * 200000
      val r = post(s.router, "/v1/query", deep)
      assertEquals((r.status, json(r)("error")("code").str), (400, "invalid-json"))
    } finally s.close()
  }

}
