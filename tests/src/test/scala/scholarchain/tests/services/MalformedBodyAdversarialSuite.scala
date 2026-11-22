package scholarchain.tests.services

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

import scala.jdk.CollectionConverters.*

import scholarchain.rag.agents.WorkflowResult
import scholarchain.services.QueryWorkflow

/**
 * TE-P11-02 T2, T3, T10 (Phase 11 category 19: API authentication, authorization and malformed
 * bodies), through the composed router over the real rag workflow.
 *
 *   - T2: duplicate JSON keys must be refused (400), never resolved by "last one wins": a
 *     validating proxy in front would see one question and the backend another.
 *   - T3: format and bidirectional controls and a lone surrogate in the question: never 5xx, never
 *     echoed, never resolved to a researcher.
 *   - T10: authentication edge cases: 401 (or 400 before authentication), workflow never called,
 *     the presented key never echoed.
 */
class MalformedBodyAdversarialSuite extends munit.FunSuite {
  import Http.*

  /** The rag workflow, recording every question it receives. */
  private final class Recording(inner: QueryWorkflow) extends QueryWorkflow {
    val calls = new AtomicInteger(0)
    val questions = new ConcurrentLinkedQueue[String]()
    def answer(queryId: String, question: String): WorkflowResult = {
      calls.incrementAndGet()
      questions.add(question)
      inner.answer(queryId, question)
    }
  }

  private val Ada = "Who are the coauthors of Ada Lovelace?"

  // ------------------------------------------------------------------------- T2

  test("T2 duplicate JSON keys (question twice, mode twice) are 400, workflow never called") {
    val bodies = Vector(
      "question" ->
        s"""{"question":"$Ada","question":"MATCH (n) DETACH DELETE n"}""",
      "question-reversed" ->
        s"""{"question":"MATCH (n) DETACH DELETE n","question":"$Ada"}""",
      "mode" ->
        s"""{"question":"$Ada","mode":"async","mode":"sync"}""",
      "constraints" ->
        s"""{"question":"$Ada","constraints":{"window":"2018-2022"},"constraints":{}}"""
    )
    val results = bodies.map { case (tag, b) =>
      val w = new ApiWorld
      val wf = new Recording(w.ragWorkflow())
      val s = w.service(workflow = wf)
      try {
        val r = post(s.router, "/v1/query", b)
        (tag, r.status, r.bodyText, wf.calls.get, wf.questions.asScala.toVector)
      } finally s.close()
    }
    val accepted = results.filter { case (_, status, _, calls, _) => status != 400 || calls != 0 }
    assert(
      accepted.isEmpty,
      "DEFECT (service-engineer, QueryService.scala:113-130 QueryRequest.parse: ujson.read keeps " +
        "the LAST value of a repeated key, and the parsed object is never checked for duplicates): " +
        accepted
          .map { case (tag, status, body, calls, qs) =>
            s"[$tag] status $status, workflow calls $calls, workflow received ${qs.mkString("|")}, " +
              s"body ${body.take(160)}"
          }
          .mkString("; ")
    )
  }

  // ------------------------------------------------------------------------- T3

  test("T3 bidi/format controls and a lone surrogate: never 5xx, never echoed, never resolved") {
    val cases = Vector(
      "rlo-inside-name" -> ujson
        .Obj("question" -> "Who are the coauthors of Ada‮ Lovelace?")
        .render(),
      "isolates-around-name" ->
        ujson.Obj("question" -> "Who are the coauthors of ⁦Ada Lovelace⁩?").render(),
      "rlo-after-name" -> ujson
        .Obj("question" -> "Who are the coauthors of Ada Lovelace‮?")
        .render(),
      "zwj-inside-name" -> ujson
        .Obj("question" -> "Who are the coauthors of Ada‍ Lovelace?")
        .render(),
      "lone-high-surrogate" -> "{\"question\":\"Who are the coauthors of Ada\\ud800 Lovelace?\"}",
      "lone-low-surrogate" -> "{\"question\":\"Who are the coauthors of \\udc00Ada Lovelace?\"}"
    )
    val controls = Vector('‮', '⁦', '⁩', '‍', '\ud800', '\udc00')
    var reached = 0
    val resolved = Vector.newBuilder[String]
    cases.foreach { case (tag, b) =>
      val w = new ApiWorld
      val wf = new Recording(w.ragWorkflow())
      val log = new RecordingLog
      val s = w.service(workflow = wf, log = log)
      try {
        val r = post(s.router, "/v1/query", b)
        reached += wf.calls.get
        assert(r.status < 500, s"$tag: ${r.status} ${r.bodyText}")
        controls.foreach(c =>
          assert(!r.bodyText.contains(c), s"$tag: control U+${c.toInt.toHexString} echoed")
        )
        log.all.foreach(e => controls.foreach(c => assert(!e.render.contains(c), s"$tag: in log")))
        if (r.status == 200) {
          val j = json(r)
          if (j("status").str == "answered" || r.bodyText.contains(ApiWorld.ada))
            resolved += s"[$tag] 200 ${j("status").str}, workflow received " +
              wf.questions.asScala.toVector
                .map(_.map(c => f"U+${c.toInt}%04X").mkString(" "))
                .mkString
        } else assert(Set(400, 422)(r.status), s"$tag: ${r.status} ${r.bodyText}")
        println(s"TE-P11-02 T3 [$tag]: status ${r.status}, workflow calls ${wf.calls.get}")
      } finally s.close()
    }
    println(s"TE-P11-02 T3: ${cases.size} cases, $reached reached the workflow")
    val bad = resolved.result()
    assert(
      bad.isEmpty,
      "DEFECT (minor, service-engineer, QueryService.scala:134-140 QueryRequest.parse checks " +
        "isISOControl only: a question holding an ill-formed UTF-16 code unit (a JSON-escaped lone " +
        "surrogate) is accepted and, depending on its position, resolves a researcher; such text " +
        "should be 400 invalid-request like a control character): " + bad.mkString("; ")
    )
  }

  // ------------------------------------------------------------------------- T10

  test("T10 authentication edge cases: 401 (or 400), workflow never called, key never echoed") {
    val k = Keys.FullKey
    val cases: Vector[(String, Vector[(String, String)], Option[String])] = Vector(
      ("trailing-tab", Vector("Authorization" -> s"Bearer $k\t"), None),
      ("trailing-space", Vector("Authorization" -> s"Bearer $k "), None),
      ("double-space", Vector("Authorization" -> s"Bearer  $k"), None),
      ("empty-bearer", Vector("Authorization" -> "Bearer "), None),
      ("no-scheme", Vector("Authorization" -> k), None),
      ("basic-scheme", Vector("Authorization" -> s"Basic $k"), None),
      (
        "two-headers-valid-first",
        Vector(
          "Authorization" -> s"Bearer $k",
          "Authorization" -> "Bearer te-p11-wrong-0123456789abcdef"
        ),
        None
      ),
      (
        "two-headers-valid-second",
        Vector(
          "Authorization" -> "Bearer te-p11-wrong-0123456789abcdef",
          "Authorization" -> s"Bearer $k"
        ),
        None
      ),
      ("comma-joined", Vector("Authorization" -> s"Bearer $k, Bearer $k"), None),
      ("key-in-query-string", Vector.empty, Some(s"api_key=$k")),
      (
        "key-in-query-string-with-header",
        Vector("Authorization" -> s"Bearer $k"),
        Some(s"access_token=$k")
      ),
      ("key-with-nul", Vector("Authorization" -> s"Bearer $k\u0000"), None),
      ("reader-key-on-query", Vector("Authorization" -> s"Bearer ${Keys.ReaderKey}"), None)
    )
    cases.foreach { case (tag, headers, rawQuery) =>
      val w = new ApiWorld
      val wf = new Recording(w.ragWorkflow())
      val s = w.service(workflow = wf)
      try {
        val r = s.router.handle(
          scholarchain.services.HttpRequest(
            "POST",
            "/v1/query",
            rawQuery,
            scholarchain.services.HttpRequest
              .headersOf(Vector("Content-Type" -> "application/json") ++ headers),
            query(Ada).getBytes("UTF-8")
          )
        )
        val expected = if (tag == "reader-key-on-query") Set(403) else Set(400, 401)
        assert(expected(r.status), s"$tag: ${r.status} ${r.bodyText}")
        assertEquals(wf.calls.get, 0, s"$tag: the workflow was called")
        assert(!r.bodyText.contains(k), s"$tag: the key was echoed")
        assert(!r.headers.exists(_._2.contains(k)), s"$tag: the key in a response header")
        if (r.status == 401) assertEquals(r.header("WWW-Authenticate"), Some("Bearer"), tag)
      } finally s.close()
    }
    // Control: the valid key alone is accepted (the cases above are refusals, not a broken key).
    val w = new ApiWorld
    val wf = new Recording(w.ragWorkflow())
    val s = w.service(workflow = wf)
    try {
      val ok = postRaw(s.router, query(Ada), Vector("Authorization" -> s"bearer $k"))
      assertEquals(ok.status, 200, ok.bodyText)
      assertEquals(wf.calls.get, 1)
    } finally s.close()
  }
}
