package scholarchain.services

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import scholarchain.rag.agents.WorkflowResult
import scholarchain.storage.model.StoreError

/**
 * SE-P10-04 (CR-P10-04 repair 03): m1 caller-only occurrences, m2 ownership errors, m3 readiness.
 */
class RepairP10cSuite extends munit.FunSuite {
  import TestHttp.*

  private val Coauthors = "Who are the coauthors of Ada Lovelace?"
  private def body(q: String = Coauthors) = ujson.Obj("question" -> q).render()

  // ------------------------------------------------------------------------------------- m1

  test("m1: occurrences are the caller's own; others see identity and provenance only") {
    val w = new ServiceWorld
    val s = w.service()
    try {
      val a1 = json(post(s.router, "/v1/query", body(), key = Some(FakeKeys.FullKey)))
      val a2 = json(post(s.router, "/v1/query", body(), key = Some(FakeKeys.FullKey)))
      val b = json(post(s.router, "/v1/query", body(), key = Some(FakeKeys.OtherKey)))
      val ev = a1("links")("evidence").arr.head.str
      val aBundles = Set(a1, a2).map(_("answer")("evidenceBundle")("bundleId").str)
      val bBundle = b("answer")("evidenceBundle")("bundleId").str

      val asA = get(s.router, ev, key = Some(FakeKeys.FullKey))
      val ja = json(asA)
      assertEquals(ja("occurrences").arr.map(_("bundleId").str).toSet, aBundles)
      // The owner still sees per-bundle scores, equal to its answers' items.
      val id = ev.stripPrefix("/v1/evidence/")
      val score =
        a1("answer")("evidenceBundle")("items").arr.find(_("evidenceId").str == id).get("score")
      assert(ja("occurrences").arr.forall(_("score") == score))
      assertEquals(ja("occurrencesTruncated").bool, false)

      val asB = get(s.router, ev, key = Some(FakeKeys.OtherKey))
      val jb = json(asB)
      assertEquals(jb("occurrences").arr.map(_("bundleId").str).toVector, Vector(bBundle))
      aBundles.foreach(x => assert(!asB.bodyText.contains(x), "A's bundle ID reaches B"))
      assert(!asB.bodyText.contains(a1("queryId").str) && !asB.bodyText.contains(a2("queryId").str))

      // A read-only client that owns nothing: 200, shared identity and provenance, no occurrence.
      val asReader = get(s.router, ev, key = Some(FakeKeys.ReaderKey))
      assertEquals(asReader.status, 200)
      val jr = json(asReader)
      assertEquals(jr("occurrences").arr.size, 0)
      assertEquals(jr("occurrencesTruncated").bool, false)
      assertEquals(jr("evidenceId").str, id)
      assertEquals(jr("provenance"), ja("provenance"))
      (aBundles + bBundle).foreach(x => assert(!asReader.bodyText.contains(x)))
      // Identity fields are the same for every client.
      Vector("evidenceType", "sourceUri", "workId", "chunkId", "graphPath", "pointer").foreach {
        f =>
          assertEquals(jr(f), ja(f), f)
          assertEquals(jb(f), ja(f), f)
      }
      // 404 stays a property of the published run only.
      assertEquals(
        get(s.router, "/v1/evidence/ev:" + "0" * 64, key = Some(FakeKeys.ReaderKey)).status,
        404
      )
    } finally s.close()
  }

  test("m1: occurrencesTruncated counts the caller's own bundles only, never foreign ones") {
    val w = new ServiceWorld
    val s = w.service(config = ServiceWorld.config().copy(evidenceMaxBundles = 1))
    try {
      val a = json(post(s.router, "/v1/query", body(), key = Some(FakeKeys.FullKey)))
      post(s.router, "/v1/query", body(), key = Some(FakeKeys.FullKey))
      post(s.router, "/v1/query", body(), key = Some(FakeKeys.OtherKey))
      post(s.router, "/v1/query", body(), key = Some(FakeKeys.OtherKey))
      val ev = a("links")("evidence").arr.head.str
      // Four stored bundles, two per client, bound 1: each sees one of its own and "truncated".
      Vector(FakeKeys.FullKey, FakeKeys.OtherKey).foreach { k =>
        val j = json(get(s.router, ev, key = Some(k)))
        assertEquals((j("occurrences").arr.size, j("occurrencesTruncated").bool), (1, true))
      }
      // The reader owns none: the four foreign bundles never make the list look cut.
      val r = json(get(s.router, ev, key = Some(FakeKeys.ReaderKey)))
      assertEquals((r("occurrences").arr.size, r("occurrencesTruncated").bool), (0, false))
    } finally s.close()
    // With one foreign bundle beside one own one and bound 1, the owner's list is complete.
    val w2 = new ServiceWorld
    val s2 = w2.service(config = ServiceWorld.config().copy(evidenceMaxBundles = 1))
    try {
      val a = json(post(s2.router, "/v1/query", body(), key = Some(FakeKeys.FullKey)))
      post(s2.router, "/v1/query", body(), key = Some(FakeKeys.OtherKey))
      val j =
        json(get(s2.router, a("links")("evidence").arr.head.str, key = Some(FakeKeys.FullKey)))
      assertEquals((j("occurrences").arr.size, j("occurrencesTruncated").bool), (1, false))
    } finally s2.close()
  }

  test("m1: config refuses a scan limit not above the occurrence bound") {
    val c = ServiceWorld.config()
    assert(
      ServiceConfig
        .problems(c.copy(evidenceScanLimit = c.evidenceMaxBundles))
        .exists(_.startsWith("evidenceScanLimit"))
    )
  }

  // ------------------------------------------------------------------------------------- m2

  /** An ownership store that can fail or hang, wrapping a working one. */
  private final class FlakyOwnership(inner: QueryOwnership) extends QueryOwnership {
    @volatile var failing: Option[StoreError] = None
    @volatile var hanging = false
    val gate = new CountDownLatch(1)
    val records = new AtomicInteger(0)
    private def guard[A](a: => Either[StoreError, A]): Either[StoreError, A] =
      if (hanging) { gate.await(10, TimeUnit.SECONDS); a }
      else failing.map(Left(_)).getOrElse(a)
    def record(q: String, c: String) = { records.incrementAndGet(); guard(inner.record(q, c)) }
    def owner(q: String) = guard(inner.owner(q))
    def ownedBy(c: String, qs: Vector[String]) = guard(inner.ownedBy(c, qs))
  }

  test("m2: an ownership outage is a 5xx on explain and evidence (fail closed), never a 404") {
    val w = new ServiceWorld
    val owners = new FlakyOwnership(new InMemoryQueryOwnership(100))
    val s = w.service(
      config = ServiceWorld.config(
        readTimeoutMillis = 150L,
        readMaxAttempts = 2,
        breakerFailureThreshold = 10
      ),
      ownership = owners
    )
    try {
      val a = json(post(s.router, "/v1/query", body()))
      val q = a("queryId").str
      val ev = a("links")("evidence").arr.head.str
      owners.failing = Some(StoreError.Transient("ownership table throttled"))
      val x = get(s.router, s"/v1/explain/$q")
      assertEquals((x.status, json(x)("error")("code").str), (502, "downstream-failure"))
      assert(!x.bodyText.contains("throttled"))
      val e = get(s.router, ev)
      assertEquals((e.status, json(e)("error")("code").str), (502, "downstream-failure"))
      assert(!e.bodyText.contains("occurrences"), "never a list without the ownership check")
      owners.failing = None
      owners.hanging = true
      val t = get(s.router, s"/v1/explain/$q")
      assertEquals((t.status, json(t)("error")("code").str), (504, "timeout"))
    } finally { owners.gate.countDown(); s.close() }
  }

  test("m2 policy: if ownership cannot be recorded the query is refused before the workflow runs") {
    val w = new ServiceWorld
    val owners = new FlakyOwnership(new InMemoryQueryOwnership(100))
    owners.failing = Some(StoreError.Transient("down"))
    val ran = new AtomicInteger(0)
    val counting = new QueryWorkflow {
      val inner = w.ragWorkflow
      def answer(q: String, question: String): WorkflowResult = {
        ran.incrementAndGet(); inner.answer(q, question)
      }
    }
    val s = w.service(
      config = ServiceWorld.config(readMaxAttempts = 2),
      workflow = counting,
      ownership = owners
    )
    try {
      val r = post(s.router, "/v1/query", body(), idempotencyKey = Some("own-1"))
      assertEquals(r.status, 502, r.bodyText)
      assertEquals(ran.get, 0, "no workflow ran, so no answer exists without a known owner")
      assertEquals(owners.records.get, 2, "the idempotent record was retried within its bound")
      // Retryable: the key was released, and a retry after recovery runs normally.
      owners.failing = None
      assertEquals(post(s.router, "/v1/query", body(), idempotencyKey = Some("own-1")).status, 200)
      assertEquals(ran.get, 1)
    } finally s.close()
  }

  // ------------------------------------------------------------------------------------- m3

  test("m3: /readyz fails while every workflow thread is pinned by an overdue call") {
    val w = new ServiceWorld
    val gate = new CountDownLatch(1)
    val ignoring = new QueryWorkflow {
      def answer(queryId: String, question: String): WorkflowResult = {
        var done = false
        while (!done) {
          try done = { gate.await(10, TimeUnit.SECONDS); true }
          catch { case _: InterruptedException => () }
        }
        throw new IllegalStateException("released")
      }
    }
    val s = w.service(
      config = ServiceWorld.config(syncTimeoutMillis = 100L, workflowWorkers = 1),
      workflow = ignoring
    )
    try {
      assertEquals(get(s.router, "/readyz", key = None).status, 200)
      assertEquals(post(s.router, "/v1/query", body()).status, 504)
      val r = get(s.router, "/readyz", key = None)
      assertEquals(r.status, 503, r.bodyText)
      assertEquals(json(r)("checks")("workflow-workers").str, "failing")
      gate.countDown()
      assert(eventually(5000)(get(s.router, "/readyz", key = None).status == 200))
    } finally { gate.countDown(); s.close() }
  }

  test(
    "m3: a half-open circuit whose trial never started is not ready until an outcome or expiry"
  ) {
    val clock = new TestClock
    val b = new CircuitBreaker("x", 1, 100L, clock)
    assert(b.ready)
    assert(b.allow()); b.failure()
    assert(!b.ready, "open")
    clock.advance(100L)
    assert(b.ready, "half-open, no trial spent yet")
    assert(b.allow())
    b.notStarted()
    assertEquals(b.state, "half-open")
    assert(!b.ready, "the trial never reached the dependency")
    assert(b.allow(), "a new trial may be attempted")
    b.success()
    assert(b.ready)
    assertEquals(b.state, "closed")
    // neutral() (a refused or interrupted call) releases a trial but does not mark it unproven.
    assert(b.allow()); b.failure(); clock.advance(100L)
    assert(b.allow()); b.neutral()
    assert(b.ready)
  }
}
