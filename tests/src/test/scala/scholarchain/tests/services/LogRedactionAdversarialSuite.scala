package scholarchain.tests.services

import java.util.concurrent.atomic.AtomicInteger

import scholarchain.rag.agents.WorkflowResult
import scholarchain.services.QueryWorkflow

/**
 * TE-P11-02 T7 (Phase 11 category 20, logging redaction): what reaches the access log and the
 * response when a request carries secrets and the workflow fails with them in its exception.
 * Required: no AccessEvent rendering and no response holds the bearer key, the question, a
 * researcher name, the exception class or message, or a CR/LF from a client header.
 */
class LogRedactionAdversarialSuite extends munit.FunSuite {
  import Http.*

  private val Question = "Who are the coauthors of Ada Lovelace?"

  /** A workflow whose exception message carries the key, the question and a store endpoint. */
  private final class Leaky extends QueryWorkflow {
    val calls = new AtomicInteger(0)
    def answer(queryId: String, question: String): WorkflowResult = {
      calls.incrementAndGet()
      throw new IllegalStateException(
        s"neptune-1.cluster.internal refused; key=${Keys.FullKey}; question=$question"
      )
    }
  }

  private def forbidden: Vector[String] =
    Vector(
      Keys.FullKey,
      "te-p11-key-wrong",
      Question,
      "Ada Lovelace",
      "Lovelace",
      "IllegalStateException",
      "neptune",
      ".internal",
      "\r",
      "\n",
      "Bearer"
    )

  private def checkEvent(tag: String, log: RecordingLog)(using munit.Location): Unit = {
    assert(log.all.nonEmpty, s"$tag: no access event recorded")
    log.all.foreach { e =>
      val line = e.render
      forbidden.foreach(f => assert(!line.contains(f), s"$tag: '$f' in access log line: $line"))
    }
  }

  test(
    "T7 a failing workflow's exception text (key, question, endpoint) reaches neither log nor body"
  ) {
    val w = new ApiWorld
    val wf = new Leaky
    val log = new RecordingLog
    val s = w.service(workflow = wf, log = log)
    try {
      val r = post(s.router, "/v1/query", query(Question))
      assertEquals(wf.calls.get, 1, "the leaky workflow was not reached")
      assertEquals(r.status, 502, r.bodyText)
      forbidden.foreach(f =>
        assert(!r.bodyText.contains(f), s"'$f' in the response: ${r.bodyText}")
      )
      checkEvent("failing workflow", log)
      assertEquals(log.all.map(_.errorCode), Vector(Some("downstream-failure")))
    } finally s.close()
  }

  test("T7 CR/LF and Bearer-shaped trace headers and a wrong key are not propagated or logged") {
    val w = new ApiWorld
    val log = new RecordingLog
    val s = w.service(log = log)
    try {
      val headers = Vector(
        "X-Trace-Id" -> "abcdefgh\r\nlevel=ERROR forged=1",
        "traceparent" -> "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01\r\nx"
      )
      val r = post(s.router, "/v1/query", query(Question), headers = headers)
      assertEquals(r.status, 200, r.bodyText)
      val trace = r.header("X-Trace-Id").getOrElse(fail("no trace header"))
      assert(!trace.contains("\r") && !trace.contains("\n") && !trace.contains("forged"), trace)
      val wrong = postRaw(
        s.router,
        query(Question),
        Vector("Authorization" -> "Bearer te-p11-key-wrong-0123456789abcdef")
      )
      assertEquals(wrong.status, 401)
      val refused = post(s.router, "/v1/query", query("MATCH (n) DETACH DELETE n Ada Lovelace"))
      assertEquals(refused.status, 422, refused.bodyText)
      assert(!refused.bodyText.contains("Lovelace"), refused.bodyText)
      assertEquals(log.all.size, 3)
      checkEvent("headers", log)
      // Each event names the route TEMPLATE, never the raw path.
      assert(log.all.forall(_.route == "POST /v1/query"), log.all.map(_.route).toString)
    } finally s.close()
  }
}
