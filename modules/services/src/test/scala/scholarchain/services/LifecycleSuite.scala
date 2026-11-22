package scholarchain.services

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JRequest, HttpResponse as JResponse}
import java.time.Duration

import scala.jdk.CollectionConverters.*

/**
 * The full query-to-evidence lifecycle on bounded fixtures (README Phase 10 gate: at least one
 * end-to-end API test reaches an evidence-backed answer), in process and over a real loopback
 * socket on an ephemeral port.
 */
class LifecycleSuite extends munit.FunSuite {
  import TestHttp.*

  private val Question = "Who are the coauthors of Ada Lovelace?"

  test("full lifecycle with fakes: query -> answer -> every cited evidence item -> explain") {
    val w = new ServiceWorld
    val s = w.service()
    try {
      val r = post(s.router, "/v1/query", ujson.Obj("question" -> Question).render())
      assertEquals(r.status, 200, r.bodyText)
      val j = json(r)
      val queryId = j("queryId").str
      val answer = j("answer")
      val cited = answer("claims").arr.flatMap(_("evidenceIds").arr.map(_.str)).distinct.toVector
      assert(cited.nonEmpty)
      val bundleId = answer("evidenceBundle")("bundleId").str
      // Every cited evidence ID resolves through the Evidence API to stored evidence of this run.
      cited.foreach { id =>
        val e = get(s.router, s"/v1/evidence/$id")
        assertEquals(e.status, 200, e.bodyText)
        val ej = json(e)
        assertEquals(ApiContract.check("evidence.response.json", ej), Vector.empty)
        assertEquals(ej("evidenceId").str, id)
        assertEquals(ej("provenance")("runId").str, ServiceWorld.run)
        val occ = ej("occurrences").arr.find(_("bundleId").str == bundleId).get
        assert(!occ.obj.contains("queryId"))
        val item = answer("evidenceBundle")("items").arr.find(_("evidenceId").str == id).get
        assertEquals(occ("score"), item("score"))
        if (item("text") != ujson.Null)
          assertEquals(
            occ("content")("sha256").str,
            scholarchain.core.Canonical.sha256HexUtf8(item("text").str)
          )
      }
      // The links in the response are the routes that serve them.
      j("links")("evidence").arr.map(_.str).foreach(l => assertEquals(get(s.router, l).status, 200))
      val x = get(s.router, j("links")("explain").str)
      assertEquals(x.status, 200, x.bodyText)
      val xj = json(x)
      assertEquals(ApiContract.check("explain.response.json", xj), Vector.empty)
      assertEquals(xj("queryId").str, queryId)
      assertEquals(xj("bundleId").str, bundleId)
      assertEquals(
        xj("evidenceIds").arr.map(_.str).toSet,
        answer("evidenceBundle")("items").arr.map(_("evidenceId").str).toSet
      )
      assert(xj("stagesExecuted").arr.nonEmpty)
      assertEquals(
        xj("stagesExecuted").arr.map(_("operation").str).toVector,
        xj("plan")("steps").arr.map(_("operation").str).toVector
      )
      assertEquals(xj("scoreComponents").arr.size, answer("evidenceBundle")("items").arr.size)
      // The audit holds no question text, no name and no hidden reasoning field.
      assert(!x.bodyText.contains("Ada Lovelace"))
      assert(!x.bodyText.toLowerCase.contains("chain-of-thought"))
      assert(!x.bodyText.toLowerCase.contains("reasoning\":"))
    } finally s.close()
  }

  test("loopback socket: health, an authenticated sync query and an error over real HTTP") {
    val w = new ServiceWorld
    val s = w.service()
    val server = HttpServerAdapter
      .start(s.router, HttpServerAdapter.Settings("127.0.0.1", 0, 16, 4, 16, 16384, 8))
      .fold(e => fail(e), identity)
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    def uri(p: String) = URI.create(s"http://127.0.0.1:${server.port}$p")
    try {
      assert(server.port > 0)
      val h = client.send(
        JRequest.newBuilder(uri("/healthz")).timeout(Duration.ofSeconds(10)).GET().build(),
        JResponse.BodyHandlers.ofString()
      )
      assertEquals(h.statusCode(), 200)
      assertEquals(ujson.read(h.body()), ujson.read(resource("healthz.response.json")))
      val q = client.send(
        JRequest
          .newBuilder(uri("/v1/query"))
          .timeout(Duration.ofSeconds(30))
          .header("Authorization", s"Bearer ${FakeKeys.FullKey}")
          .header("Content-Type", "application/json")
          .header("Idempotency-Key", "socket-1")
          .POST(JRequest.BodyPublishers.ofString(resource("query-sync.request.json")))
          .build(),
        JResponse.BodyHandlers.ofString()
      )
      assertEquals(q.statusCode(), 200, q.body())
      assertEquals(
        ApiContract.check("query-sync.response.json", ujson.read(q.body())),
        Vector.empty
      )
      assert(q.headers().firstValue("X-Request-Id").isPresent)
      val missing = client.send(
        JRequest
          .newBuilder(uri("/v1/evidence/ev:" + "0" * 64))
          .timeout(Duration.ofSeconds(10))
          .header("Authorization", s"Bearer ${FakeKeys.FullKey}")
          .GET()
          .build(),
        JResponse.BodyHandlers.ofString()
      )
      assertEquals(missing.statusCode(), 404)
      assertEquals(
        ujson.read(missing.body())("error")("traceId").str,
        missing.headers().firstValue("X-Trace-Id").get
      )
      val big = client.send(
        JRequest
          .newBuilder(uri("/v1/query"))
          .timeout(Duration.ofSeconds(10))
          .header("Authorization", s"Bearer ${FakeKeys.FullKey}")
          .header("Content-Type", "application/json")
          .POST(JRequest.BodyPublishers.ofString(" " * 20000))
          .build(),
        JResponse.BodyHandlers.ofString()
      )
      assertEquals(big.statusCode(), 400)
      assertEquals(ujson.read(big.body())("error")("code").str, "body-too-large")
      assertEquals(
        h.headers().map().asScala.keySet.exists(_.equalsIgnoreCase("x-request-id")),
        true
      )
    } finally {
      server.stop(0)
      s.close()
    }
  }
}
