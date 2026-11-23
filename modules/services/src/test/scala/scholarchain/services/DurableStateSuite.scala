package scholarchain.services

import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import scala.jdk.CollectionConverters.*

import scholarchain.rag.agents.WorkflowResult
import scholarchain.storage.model.{StoreError, TableRole}
import scholarchain.storage.state.ServiceStateSettings

/**
 * SV-INT-02: the services over the DURABLE service-state stores (storage's ownership, job and
 * idempotency stores on the real DynamoDB adapter, over the local [[DdbStub]]), through the router:
 * sync and async operation, replay, ambiguous writes, attempt identity, client isolation, deadlines
 * and admission. No AWS, no network.
 */
class DurableStateSuite extends munit.FunSuite {
  import TestHttp.*

  override val munitTimeout = scala.concurrent.duration.Duration(120, "s")

  private val Question = "Who are the coauthors of Ada Lovelace?"
  private def body(mode: String) = ujson.Obj("question" -> Question, "mode" -> mode).render()

  /** Storage settings as core would pass them (in-flight 120 s, skew 5 s, 10 conflict rounds). */
  private val settings: ServiceStateSettings =
    ServiceStateSettings
      .of("query-jobs", 90000L, 3600L, 900L, 120L, 262144, 10, 100, 10000L, 0L, 5000L, 0L)
      .fold(e => throw new IllegalStateException(e.message), identity)

  /** One begin call as the store saw it. */
  final case class BeginCall(attemptId: String, startNanos: Long, endNanos: Long, thread: String)

  /** Records every `begin` (attempt ID, timing) of the wrapped idempotency store. */
  final class RecordingIdempotency(inner: IdempotencyStore) extends IdempotencyStore {
    val calls = new ConcurrentLinkedQueue[BeginCall]()
    def begins: Vector[BeginCall] = calls.asScala.toVector
    def begin(c: String, k: String, d: String, a: String): Either[StoreError, IdempotencyBegin] = {
      val t0 = System.nanoTime()
      try inner.begin(c, k, d, a)
      finally calls.add(BeginCall(a, t0, System.nanoTime(), Thread.currentThread().getName))
    }
    def complete(h: IdempotencyHold, o: IdempotentOutcome) = inner.complete(h, o)
    def abandon(h: IdempotencyHold) = inner.abandon(h)
  }

  /** Counts workflow calls; the first `gated` calls wait for `gate` (outside the store lock). */
  final class GatedWorkflow(inner: QueryWorkflow, gated: Int = 0) extends QueryWorkflow {
    val calls = new AtomicInteger(0)
    val gate = new CountDownLatch(1)
    val entered = new CountDownLatch(1)
    def answer(queryId: String, question: String): WorkflowResult = {
      if (calls.incrementAndGet() <= gated) {
        entered.countDown()
        gate.await(30, TimeUnit.SECONDS)
      }
      inner.answer(queryId, question)
    }
  }

  /** A world whose service state is durable (DynamoDB adapter over the stub). */
  final class Durable {
    val world = new ServiceWorld
    val ddb = new DdbStub(DdbStub.names)
    val clock = new TestClock
    val state: DurableServiceState =
      DurableServiceState(DdbStub.adapter(ddb), clock, settings).fold(e => fail(e), identity)
    val idem = new RecordingIdempotency(state.ports.idempotency)

    def service(
        config: ServiceConfig = ServiceWorld.config(),
        workflow: QueryWorkflow = world.ragWorkflow,
        idempotency: IdempotencyStore = idem
    ): ServiceRuntime =
      ServiceRuntime
        .build(
          world.runtime,
          workflow,
          new FakeKeys,
          config,
          world.access,
          new RecordingLog,
          clock,
          new CountingIds,
          ServiceKind.All,
          state.ownership,
          state.ports.copy(idempotency = idempotency)
        )
        .fold(e => fail(e), identity)
  }

  private def code(r: HttpResponse): String = json(r)("error")("code").str

  private def submit(
      pool: java.util.concurrent.ExecutorService
  )(f: => HttpResponse): java.util.concurrent.Future[HttpResponse] =
    pool.submit(new java.util.concurrent.Callable[HttpResponse] { def call(): HttpResponse = f })

  // ------------------------------------------------------------------ end to end

  test("durable sync query through the router: answer, owner row, explain and evidence") {
    val d = new Durable
    val s = d.service()
    try {
      val r = post(s.router, "/v1/query", body("sync"))
      assertEquals(r.status, 200, r.bodyText)
      assertEquals(json(r)("status").str, "answered")
      assertEquals(d.ddb.itemCount(TableRole.QueryOwnership), 1, "owner row in DynamoDB")
      assertEquals(d.ddb.itemCount(TableRole.IdempotencyKey), 0, "no key, no record")
      val explain = json(r)("links")("explain").str
      val e = get(s.router, explain)
      assertEquals(e.status, 200, e.bodyText)
      val evidence = json(r)("links")("evidence")(0).str
      val own = get(s.router, evidence)
      assertEquals(own.status, 200, own.bodyText)
      assert(json(own)("occurrences").arr.nonEmpty, "the owner lists its own occurrences")
      assert(d.ddb.requests.contains("BatchGetItem"), "ownedBy read the durable owner rows")
    } finally s.close()
  }

  test("durable async job through the router: 202, poll, result, job row in DynamoDB") {
    val d = new Durable
    val s = d.service()
    try {
      val r = post(s.router, "/v1/query", body("async"))
      assertEquals(r.status, 202, r.bodyText)
      val jobId = json(r)("jobId").str
      assertEquals(d.ddb.itemCount(TableRole.QueryJob), 1)
      assert(eventually(20000)(json(get(s.router, s"/v1/jobs/$jobId"))("state").str == "succeeded"))
      val result = get(s.router, s"/v1/jobs/$jobId/result")
      assertEquals(result.status, 200, result.bodyText)
      assertEquals(json(result)("status").str, "answered")
      assertEquals(json(result)("queryId"), json(r)("queryId"))
      val job = json(get(s.router, s"/v1/jobs/$jobId"))
      assertEquals(job("stage").str, Job.StageCompleted)
      assert(job("finishedAt").strOpt.nonEmpty)
    } finally s.close()
  }

  // ------------------------------------------------------------------ replay

  test("replay under one Idempotency-Key: one execution, one record, the same outcome") {
    val d = new Durable
    val wf = new GatedWorkflow(d.world.ragWorkflow)
    val s = d.service(workflow = wf)
    try {
      val first = post(s.router, "/v1/query", body("sync"), idempotencyKey = Some("k-sync"))
      assertEquals(first.status, 200, first.bodyText)
      val again = post(s.router, "/v1/query", body("sync"), idempotencyKey = Some("k-sync"))
      assertEquals(again.status, 200, again.bodyText)
      assertEquals(again.header("Idempotent-Replayed"), Some("true"))
      assertEquals(json(again)("queryId"), json(first)("queryId"))
      assertEquals(json(again)("answer"), json(first)("answer"))
      assertEquals(wf.calls.get, 1, "the workflow ran once")
      // Async: the key replays the same job.
      val a = post(s.router, "/v1/query", body("async"), idempotencyKey = Some("k-async"))
      assertEquals(a.status, 202, a.bodyText)
      val b = post(s.router, "/v1/query", body("async"), idempotencyKey = Some("k-async"))
      assertEquals(b.status, 202, b.bodyText)
      assertEquals(b.header("Idempotent-Replayed"), Some("true"))
      assertEquals(json(b)("jobId"), json(a)("jobId"))
      assertEquals(d.ddb.itemCount(TableRole.IdempotencyKey), 2)
      // Another body under a used key is the conflict, not a replay.
      val other = post(s.router, "/v1/query", body("async"), idempotencyKey = Some("k-sync"))
      assertEquals(other.status, 409)
      assertEquals(code(other), "idempotency-key-reused")
    } finally s.close()
  }

  // ------------------------------------------------------------------ ambiguous writes

  test("ambiguous writes: begin and complete applied, responses lost; one attempt ID, one run") {
    val d = new Durable
    val wf = new GatedWorkflow(d.world.ragWorkflow)
    val s = d.service(workflow = wf)
    try {
      // The begin PutItem and the complete PutItem each apply, then the connection resets.
      d.ddb.loseResponseAfterApply(
        TableRole.IdempotencyKey,
        DdbStub.connectionReset(),
        DdbStub.connectionReset()
      )
      val r = post(s.router, "/v1/query", body("sync"), idempotencyKey = Some("k-lost"))
      assertEquals(r.status, 200, r.bodyText)
      assertEquals(wf.calls.get, 1, "one execution")
      val begins = d.idem.begins
      assertEquals(begins.size, 2, "the lost begin was retried once")
      assertEquals(begins.map(_.attemptId).distinct.size, 1, "the retry kept the attempt ID")
      assert(begins.head.attemptId.length >= 16)
      assertEquals(d.ddb.itemCount(TableRole.IdempotencyKey), 1, "one record")
      // The retried complete recorded the outcome of the SAME ticket: the key replays it.
      val replay = post(s.router, "/v1/query", body("sync"), idempotencyKey = Some("k-lost"))
      assertEquals(replay.status, 200, replay.bodyText)
      assertEquals(replay.header("Idempotent-Replayed"), Some("true"))
      assertEquals(json(replay)("queryId"), json(r)("queryId"))
      assertEquals(wf.calls.get, 1)
    } finally s.close()
  }

  test("ambiguous writes: a job transition applied, response lost; the job still finishes once") {
    val d = new Durable
    val s = d.service()
    try {
      // create, then start (both PutItem on the job table) lose their responses after applying.
      d.ddb.loseResponseAfterApply(
        TableRole.QueryJob,
        DdbStub.connectionReset(),
        DdbStub.connectionReset()
      )
      val r = post(s.router, "/v1/query", body("async"))
      assertEquals(r.status, 202, r.bodyText)
      val jobId = json(r)("jobId").str
      assert(eventually(20000)(json(get(s.router, s"/v1/jobs/$jobId"))("state").str == "succeeded"))
      assertEquals(d.ddb.itemCount(TableRole.QueryJob), 1)
    } finally s.close()
  }

  // ------------------------------------------------------------------ attempt identity

  test("independent requests mint independent attempt IDs; one key admits one holder") {
    val d = new Durable
    val wf = new GatedWorkflow(d.world.ragWorkflow, gated = 1)
    val s = d.service(workflow = wf)
    val pool = Executors.newFixedThreadPool(6)
    try {
      // One request holds key "shared" inside its workflow.
      val holder = submit(pool)(
        post(s.router, "/v1/query", body("sync"), idempotencyKey = Some("shared"))
      )
      assert(wf.entered.await(20, TimeUnit.SECONDS), "the holder reached its workflow")
      // An independent request with the same key and body: another attempt, 409 in progress.
      val second = post(s.router, "/v1/query", body("sync"), idempotencyKey = Some("shared"))
      assertEquals(second.status, 409, second.bodyText)
      assertEquals(code(second), "idempotency-in-progress")
      // Four concurrent requests with their own keys all run.
      val others = (1 to 4).map(i =>
        submit(pool)(
          post(s.router, "/v1/query", body("sync"), idempotencyKey = Some(s"own-$i"))
        )
      )
      others.foreach(f => assertEquals(f.get(30, TimeUnit.SECONDS).status, 200))
      wf.gate.countDown()
      assertEquals(holder.get(30, TimeUnit.SECONDS).status, 200)
      val ids = d.idem.begins.map(_.attemptId)
      assertEquals(ids.size, 6, d.idem.begins.toString)
      assertEquals(ids.distinct.size, 6, "every logical request had its own attempt ID")
      assertEquals(wf.calls.get, 5, "the 409 request never ran")
    } finally { pool.shutdownNow(); s.close() }
  }

  // ------------------------------------------------------------------ client isolation

  test("client isolation with durable stores: jobs, owners, explain and evidence") {
    val d = new Durable
    val s = d.service()
    try {
      val sync = post(s.router, "/v1/query", body("sync"))
      assertEquals(sync.status, 200, sync.bodyText)
      val async = post(s.router, "/v1/query", body("async"))
      assertEquals(async.status, 202, async.bodyText)
      val jobId = json(async)("jobId").str
      assert(eventually(20000)(json(get(s.router, s"/v1/jobs/$jobId"))("state").str == "succeeded"))
      val other = Some(FakeKeys.OtherKey)
      val unknownJob = get(s.router, "/v1/jobs/job-0000000000000000000000000000ffff", key = other)
      Vector(s"/v1/jobs/$jobId", s"/v1/jobs/$jobId/result").foreach { p =>
        val r = get(s.router, p, key = other)
        assertEquals(r.status, 404, s"$p: ${r.bodyText}")
        assertEquals(json(r)("error")("message"), json(unknownJob)("error")("message"))
        assert(!r.bodyText.contains("client-full"), "the owner is never named")
      }
      val explain = json(sync)("links")("explain").str
      val foreign = get(s.router, explain, key = other)
      assertEquals(foreign.status, 404, foreign.bodyText)
      assert(!foreign.bodyText.contains("client-full"))
      val unknown = get(s.router, "/v1/explain/query:0000000000000000000000000000beef", key = other)
      assertEquals(json(foreign)("error")("message"), json(unknown)("error")("message"))
      val evidence = json(sync)("links")("evidence")(0).str
      val seen = get(s.router, evidence, key = other)
      assertEquals(seen.status, 200, seen.bodyText)
      assertEquals(json(seen)("occurrences").arr.size, 0, "another client lists no occurrence")
      assertEquals(get(s.router, evidence).status, 200)
      assert(json(get(s.router, evidence))("occurrences").arr.nonEmpty)
    } finally s.close()
  }

  // ------------------------------------------------------------------ deadlines

  private val tight =
    ServiceWorld.config(readTimeoutMillis = 200L, readRequestTimeoutMillis = 1500L)

  test(
    "a hung begin ends within the request bound, is never retried beside itself, and is released"
  ) {
    val d = new Durable
    val wf = new GatedWorkflow(d.world.ragWorkflow)
    val s = d.service(config = tight, workflow = wf)
    val release = new CountDownLatch(1)
    val entered = new CountDownLatch(1)
    try {
      d.ddb.hangNext("PutItem", release, entered)
      val t0 = System.nanoTime()
      val r = post(s.router, "/v1/query", body("sync"), idempotencyKey = Some("k-hung"))
      val elapsed = (System.nanoTime() - t0) / 1000000L
      assertEquals(r.status, 504, r.bodyText)
      assertEquals(code(r), "timeout")
      assert(elapsed < 1500L + 1000L, s"took $elapsed ms against a 1500 ms bound")
      assertEquals(wf.calls.get, 0, "nothing ran")
      // The first attempt never ended inside the bound: no second attempt was started.
      assertEquals(d.idem.begins.size, 0, "the only attempt is still inside the store")
      release.countDown()
      // Its write lands late; the orphan release reads it back under the same attempt and frees it.
      assert(eventually(10000)(d.idem.begins.size == 2), d.idem.begins.toString)
      assertEquals(d.idem.begins.map(_.attemptId).distinct.size, 1)
      assert(eventually(10000)(d.ddb.itemCount(TableRole.IdempotencyKey) == 0), "key released")
      val retry = post(s.router, "/v1/query", body("sync"), idempotencyKey = Some("k-hung"))
      assertEquals(retry.status, 200, retry.bodyText)
      assertEquals(wf.calls.get, 1)
    } finally { release.countDown(); s.close() }
  }

  test("a timed-out begin settles before its retry: same attempt, no overlap, one run") {
    val d = new Durable
    val wf = new GatedWorkflow(d.world.ragWorkflow)
    val s = d.service(
      config = ServiceWorld.config(readTimeoutMillis = 200L, readRequestTimeoutMillis = 5000L),
      workflow = wf
    )
    val release = new CountDownLatch(1)
    val entered = new CountDownLatch(1)
    val timer = Executors.newSingleThreadScheduledExecutor()
    try {
      d.ddb.hangNext("PutItem", release, entered)
      // The store answers 600 ms after the begin's PutItem arrived: past its 200 ms attempt.
      timer.execute(new Runnable {
        def run(): Unit = {
          entered.await(10, TimeUnit.SECONDS)
          Thread.sleep(600)
          release.countDown()
        }
      })
      val r = post(s.router, "/v1/query", body("sync"), idempotencyKey = Some("k-slow"))
      assertEquals(r.status, 200, r.bodyText)
      val Vector(first, second) = d.idem.begins.sortBy(_.startNanos): @unchecked
      assertEquals(first.attemptId, second.attemptId, "the retry kept the attempt ID")
      assert(second.startNanos >= first.endNanos, "the retry started after the attempt ended")
      assert((first.endNanos - first.startNanos) / 1000000L >= 500L, "the first attempt was slow")
      assertEquals(wf.calls.get, 1)
      assertEquals(d.ddb.itemCount(TableRole.IdempotencyKey), 1)
      val replay = post(s.router, "/v1/query", body("sync"), idempotencyKey = Some("k-slow"))
      assertEquals(replay.header("Idempotent-Replayed"), Some("true"))
    } finally { release.countDown(); timer.shutdownNow(); s.close() }
  }

  test("a hung job write and a hung job read end within the request bound (504)") {
    val d = new Durable
    val s = d.service(config = tight)
    val release = new CountDownLatch(1)
    try {
      d.ddb.hangNext("PutItem", release, new CountDownLatch(1))
      val t0 = System.nanoTime()
      val r = post(s.router, "/v1/query", body("async"))
      val elapsed = (System.nanoTime() - t0) / 1000000L
      assertEquals(r.status, 504, r.bodyText)
      assert(json(r)("error")("message").str.startsWith("creating the job"))
      assert(elapsed < 2500L, s"took $elapsed ms")
      release.countDown()
      val read = new CountDownLatch(1)
      d.ddb.hangNext("GetItem", read, new CountDownLatch(1))
      val t1 = System.nanoTime()
      val g = get(s.router, "/v1/jobs/job-00000000000000000000000000000001")
      assertEquals(g.status, 504, g.bodyText)
      assert((System.nanoTime() - t1) / 1000000L < 2500L)
      read.countDown()
    } finally { release.countDown(); s.close() }
  }

  // ------------------------------------------------------------------ admission

  test("429 admission with durable jobs: per-replica maxJobs; the slot returns when a job ends") {
    val d = new Durable
    val wf = new GatedWorkflow(d.world.ragWorkflow, gated = 1)
    val s = d.service(config = ServiceWorld.config(maxJobs = 1), workflow = wf)
    try {
      val a = post(s.router, "/v1/query", body("async"))
      assertEquals(a.status, 202, a.bodyText)
      val b = post(s.router, "/v1/query", body("async"))
      assertEquals(b.status, 429, b.bodyText)
      assertEquals(code(b), "too-many-requests")
      assertEquals(b.header("Retry-After"), Some("2"))
      assertEquals(d.ddb.itemCount(TableRole.QueryJob), 1, "the refused job was never created")
      wf.gate.countDown()
      val jobId = json(a)("jobId").str
      assert(eventually(20000)(json(get(s.router, s"/v1/jobs/$jobId"))("state").str == "succeeded"))
      assert(eventually(5000)(post(s.router, "/v1/query", body("async")).status == 202))
    } finally s.close()
  }

  test("in-process stores are called directly: an open key-value circuit strands no local key") {
    val w = new ServiceWorld
    val clock = new TestClock
    @volatile var failing = true
    val owners = new InMemoryQueryOwnership(100)
    val flaky = new QueryOwnership {
      def record(q: String, c: String) =
        if (failing) Left(StoreError.Transient("stub: ownership down")) else owners.record(q, c)
      def owner(q: String) = owners.owner(q)
      def ownedBy(c: String, qs: Vector[String]) = owners.ownedBy(c, qs)
    }
    val s = w.service(clock = clock, ownership = flaky)
    try {
      // Three failed ownership records (502) open the key-value circuit (threshold 3).
      (1 to 3).foreach { i =>
        val r = post(s.router, "/v1/query", body("sync"), idempotencyKey = Some(s"k$i"))
        assertEquals(r.status, 502, r.bodyText)
      }
      assertEquals(s.breakers.find(_.name == "key-value").map(_.state), Some("open"))
      failing = false
      clock.advance(60001L)
      // The third key was released although the circuit was open when it was abandoned.
      val retry = post(s.router, "/v1/query", body("sync"), idempotencyKey = Some("k3"))
      assertEquals(retry.status, 200, retry.bodyText)
    } finally s.close()
  }

  test("the durable idempotency store is never Full; the in-memory one is (control)") {
    def run(durable: Boolean): Int = {
      val d = new Durable
      val wf = new GatedWorkflow(d.world.ragWorkflow, gated = 1)
      val config = ServiceWorld.config(idempotencyCapacity = 1)
      val s =
        if (durable) d.service(config = config, workflow = wf)
        else d.world.service(config = config, workflow = wf)
      val pool = Executors.newSingleThreadExecutor()
      try {
        val held = submit(pool)(
          post(s.router, "/v1/query", body("sync"), idempotencyKey = Some("held"))
        )
        assert(wf.entered.await(20, TimeUnit.SECONDS))
        val second = post(s.router, "/v1/query", body("sync"), idempotencyKey = Some("next"))
        wf.gate.countDown()
        assertEquals(held.get(30, TimeUnit.SECONDS).status, 200)
        second.status
      } finally { pool.shutdownNow(); s.close() }
    }
    assertEquals(run(durable = true), 200)
    assertEquals(run(durable = false), 429)
  }
}
