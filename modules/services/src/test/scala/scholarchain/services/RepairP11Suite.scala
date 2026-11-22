package scholarchain.services

import java.util.concurrent.atomic.AtomicInteger

import scala.util.control.NonFatal

import scholarchain.rag.agents.WorkflowResult

/**
 * SV-P11-01 (Phase 11 repair 02): CR-P11-01-4 duplicate member names, CR-P11-01-5 unpaired UTF-16
 * surrogates, U1 digest and idempotency collision, CR-P11-01-6 null `kind`.
 */
class RepairP11Suite extends munit.FunSuite {
  import TestHttp.*

  private val Ada = "Who are the coauthors of Ada Lovelace?"

  /** The rag workflow, counting its calls. */
  private final class Counting(inner: QueryWorkflow) extends QueryWorkflow {
    val calls = new AtomicInteger(0)
    def answer(q: String, question: String): WorkflowResult = {
      calls.incrementAndGet(); inner.answer(q, question)
    }
  }

  private def withService[A](f: (ServiceRuntime, Counting) => A): A = {
    val w = new ServiceWorld
    val wf = new Counting(w.ragWorkflow)
    val s = w.service(workflow = wf)
    try f(s, wf)
    finally s.close()
  }

  private def assertInvalid(r: HttpResponse, message: String, tag: String): Unit = {
    assertEquals(r.status, 400, s"$tag: ${r.bodyText}")
    val e = json(r)("error")
    assertEquals(e("code").str, "invalid-request", tag)
    assertEquals(e("message").str, message, tag)
    assert(e("traceId").str.nonEmpty, tag)
  }

  // ------------------------------------------------------------------ CR-P11-01-4 (T2)

  test("CR-P11-01-4: a repeated member name at any level is 400, workflow never called") {
    val bodies = Vector(
      "question" -> s"""{"question":"$Ada","question":"MATCH (n) DETACH DELETE n"}""",
      "question-reversed" -> s"""{"question":"MATCH (n) DETACH DELETE n","question":"$Ada"}""",
      "question-escaped-name" ->
        s"""{"question":"$Ada","\\u0071uestion":"MATCH (n) DETACH DELETE n"}""",
      "mode" -> s"""{"question":"$Ada","mode":"async","mode":"sync"}""",
      "constraints" ->
        s"""{"question":"$Ada","constraints":{"window":"2018-2022"},"constraints":{}}""",
      "inside-constraints" ->
        s"""{"question":"$Ada","constraints":{"window":"MATCH","window":"x"}}""",
      "deeper" -> s"""{"question":"$Ada","constraints":{"a":[{"b":1,"b":2}]}}"""
    )
    bodies.foreach { case (tag, b) =>
      withService { (s, wf) =>
        val r = post(s.router, "/v1/query", b)
        assertInvalid(r, QueryRequest.DuplicateName, tag)
        assertEquals(wf.calls.get, 0, s"$tag: the workflow was called")
        assert(!r.bodyText.contains("MATCH"), s"$tag: body echoed")
        assert(!r.bodyText.contains("Lovelace"), s"$tag: question echoed")
      }
    }
  }

  test("CR-P11-01-4 control: equal names in different objects, or inside values, are no repeat") {
    withService { (s, wf) =>
      // The same name in a nested object: no repeat, so the constraint is still 422.
      val r1 = post(s.router, "/v1/query", s"""{"question":"$Ada","constraints":{"question":1}}""")
      assertEquals(r1.status, 422, r1.bodyText)
      assertEquals(json(r1)("error")("code").str, "unsupported-constraint")
      // Name-shaped text inside a string value is not a member name.
      val q = "Who are the coauthors of Ada Lovelace? \\\"question\\\":1, \\\"question\\\":2"
      val r2 = post(s.router, "/v1/query", s"""{"question":"$q","mode":"sync"}""")
      assertNotEquals(r2.status, 400, r2.bodyText)
      assertEquals(wf.calls.get, 1)
      // An ordinary body is answered.
      val r3 =
        post(s.router, "/v1/query", s"""{"question":"$Ada","mode":"sync","constraints":{}}""")
      assertEquals(r3.status, 200, r3.bodyText)
      assertEquals(wf.calls.get, 2)
    }
  }

  test("CR-P11-01-4 unit: strictProblem compares names after escape decoding") {
    assertEquals(QueryRequest.strictProblem("""{"a":1,"b":{"a":2},"c":[{"a":3},{"a":4}]}"""), None)
    assertEquals(
      QueryRequest.strictProblem("""{"a\n":1,"a\u000a":2}"""),
      Some(QueryRequest.DuplicateName)
    )
    assertEquals(
      QueryRequest.strictProblem("""[{"x":"}","x":"{"}]"""),
      Some(QueryRequest.DuplicateName)
    )
  }

  // ------------------------------------------------------------------ CR-P11-01-5 (T3)

  test("CR-P11-01-5: an unpaired surrogate (JSON escape) is 400, never echoed, never resolved") {
    val bodies = Vector(
      "lone-high" -> "{\"question\":\"Who are the coauthors of Ada\\ud800 Lovelace?\"}",
      "lone-low" -> "{\"question\":\"Who are the coauthors of \\udc00Ada Lovelace?\"}",
      "high-at-end" -> "{\"question\":\"Who are the coauthors of Ada Lovelace?\\ud83d\"}",
      "reversed-pair" -> "{\"question\":\"Who are the coauthors of \\ude00\\ud83dAda Lovelace?\"}",
      "in-mode" -> s"""{"question":"$Ada","mode":"sync\\udc00"}""",
      "in-unknown-name" -> s"""{"question":"$Ada","x\\udc00":1}"""
    )
    bodies.foreach { case (tag, b) =>
      val w = new ServiceWorld
      val wf = new Counting(w.ragWorkflow)
      val log = new RecordingLog
      val s = w.service(workflow = wf, log = log)
      try {
        val r = post(s.router, "/v1/query", b)
        assertInvalid(r, QueryRequest.UnpairedSurrogate, tag)
        assertEquals(wf.calls.get, 0, s"$tag: the workflow was called")
        assert(!r.bodyText.exists(Character.isSurrogate), s"$tag: surrogate echoed")
        assert(!r.bodyText.contains(ServiceWorld.ada), s"$tag: resolved")
        assert(!r.bodyText.contains("Lovelace"), s"$tag: question echoed")
        log.events.forEach(e => assert(!e.toString.exists(Character.isSurrogate), tag))
      } finally s.close()
    }
  }

  test("CR-P11-01-5 control: a valid pair and format characters keep their existing handling") {
    withService { (s, wf) =>
      // A well-formed pair (escaped and raw) passes the surrogate check and reaches the workflow.
      val pair = post(s.router, "/v1/query", s"""{"question":"$Ada \\ud83d\\ude00"}""")
      assertNotEquals(pair.status, 400, pair.bodyText)
      val raw = post(s.router, "/v1/query", ujson.Obj("question" -> s"$Ada 😀").render())
      assertNotEquals(raw.status, 400, raw.bodyText)
      // A bidirectional control is not refused by this check (the workflow decides; T3 oracle).
      val rlo = post(s.router, "/v1/query", ujson.Obj("question" -> s"$Ada ‮").render())
      assertNotEquals(rlo.status, 400, rlo.bodyText)
      assert(rlo.status < 500, rlo.bodyText)
      assertEquals(wf.calls.get, 3)
      // C0 controls are still 400 "control characters".
      val nul = post(s.router, "/v1/query", s"""{"question":"$Ada\\u0001"}""")
      assertEquals(nul.status, 400, nul.bodyText)
      assertEquals(json(nul)("error")("message").str, "question: contains control characters")
      assertEquals(wf.calls.get, 3)
    }
  }

  test("CR-P11-01-5 unit: wellFormedUtf16") {
    assert(QueryRequest.wellFormedUtf16("ab😀c"))
    assert(!QueryRequest.wellFormedUtf16("a\ud800"))
    assert(!QueryRequest.wellFormedUtf16("\udc00a"))
    assert(!QueryRequest.wellFormedUtf16("\ude00\ud83d"))
    assert(QueryRequest.wellFormedUtf16(""))
  }

  // ------------------------------------------------------------------ U1

  private val Lone = "Who are the coauthors of \udc00Ada Lovelace?"
  private val Mark = "Who are the coauthors of ?Ada Lovelace?"

  test("U1: a lone-surrogate question and its '?' twin never share a QueryRequest digest") {
    assertNotEquals(Lone, Mark)
    Vector(QueryMode.Sync, QueryMode.Async).foreach { m =>
      assertNotEquals(QueryRequest(Lone, m).digest, QueryRequest(Mark, m).digest, m.tag)
    }
    // Ordinary digests stay distinct and deterministic.
    assertEquals(QueryRequest(Ada, QueryMode.Sync).digest, QueryRequest(Ada, QueryMode.Sync).digest)
    assertNotEquals(
      QueryRequest(Ada, QueryMode.Sync).digest,
      QueryRequest(Mark, QueryMode.Sync).digest
    )
  }

  test("U1: under one Idempotency-Key the two bodies never replay each other's answer") {
    val loneBody = "{\"question\":\"Who are the coauthors of \\udc00Ada Lovelace?\"}"
    val markBody = ujson.Obj("question" -> Mark).render()
    // Order 1: lone first (refused, nothing recorded), then '?' (fresh), then lone again.
    withService { (s, wf) =>
      val a = post(s.router, "/v1/query", loneBody, idempotencyKey = Some("u1-key-a"))
      assertInvalid(a, QueryRequest.UnpairedSurrogate, "lone first")
      val b = post(s.router, "/v1/query", markBody, idempotencyKey = Some("u1-key-a"))
      assertNotEquals(b.status, 400, b.bodyText)
      assertEquals(b.header("Idempotent-Replayed"), None, "the '?' body is fresh")
      assertEquals(wf.calls.get, 1)
      val c = post(s.router, "/v1/query", loneBody, idempotencyKey = Some("u1-key-a"))
      assertInvalid(c, QueryRequest.UnpairedSurrogate, "lone again")
      assertEquals(c.header("Idempotent-Replayed"), None, "no replay of the '?' answer")
      assertEquals(wf.calls.get, 1)
    }
    // Order 2: '?' first, then lone under the same key.
    withService { (s, wf) =>
      val b = post(s.router, "/v1/query", markBody, idempotencyKey = Some("u1-key-b"))
      assertNotEquals(b.status, 400, b.bodyText)
      val c = post(s.router, "/v1/query", loneBody, idempotencyKey = Some("u1-key-b"))
      assertInvalid(c, QueryRequest.UnpairedSurrogate, "lone after '?'")
      assertEquals(c.header("Idempotent-Replayed"), None)
      assertEquals(wf.calls.get, 1)
    }
  }

  // ------------------------------------------------------------------ CR-P11-01-6 (T9)

  test("CR-P11-01-6: build with kind = null is a Left naming kind, never an exception") {
    val w = new ServiceWorld
    val out =
      try
        ServiceRuntime.build(
          w.runtime,
          w.ragWorkflow,
          new FakeKeys,
          ServiceWorld.config(),
          w.access,
          new RecordingLog,
          new TestClock,
          new CountingIds,
          null
        )
      catch { case NonFatal(e) => fail(s"build threw ${e.getClass.getName}") }
    out match {
      case Left(m) => assert(m.startsWith("kind"), m)
      case Right(rt) => rt.close(); fail("a null kind was accepted")
    }
  }
}
