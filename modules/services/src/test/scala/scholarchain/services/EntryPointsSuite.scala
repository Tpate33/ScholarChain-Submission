package scholarchain.services

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JRequest, HttpResponse as JResponse}
import java.time.Duration
import java.util.concurrent.{CountDownLatch, TimeUnit}

import scholarchain.storage.model.{BoundQuery, GraphRow, StoreError}
import scholarchain.storage.port.GraphReader

/** SE-P10-03 Part B: the three service entry points (QueryJobs, EvidenceExplain, Graph). */
class EntryPointsSuite extends munit.FunSuite {
  import TestHttp.*
  import ServiceKind.*

  private def templates(k: ServiceKind) =
    Router.routesFor(k).map(r => s"${r.method} ${r.template}").toSet

  private val Ops = Set("GET /healthz", "GET /readyz", "GET /metrics")

  private def server(port: Int = 0) =
    HttpServerAdapter.Settings("127.0.0.1", port, 16, 4, 16, 16384, 8)

  test("each service's route table is exactly its subset plus the operations routes") {
    assertEquals(
      templates(QueryJobs),
      Ops ++ Set("POST /v1/query", "GET /v1/jobs/{jobId}", "GET /v1/jobs/{jobId}/result")
    )
    assertEquals(
      templates(EvidenceExplain),
      Ops ++ Set("GET /v1/evidence/{evidenceId}", "GET /v1/explain/{queryId}")
    )
    assertEquals(
      templates(Graph),
      Ops ++ Set("GET /v1/graph/nodes/{nodeId}", "GET /v1/graph/nodes/{nodeId}/neighbors")
    )
    assertEquals(templates(All), Router.Routes.map(r => s"${r.method} ${r.template}").toSet)
    // The three subsets partition the /v1 routes.
    val v1 = Vector(QueryJobs, EvidenceExplain, Graph).flatMap(k => templates(k) -- Ops)
    assertEquals(v1.size, v1.toSet.size)
    assertEquals(v1.toSet, templates(All) -- Ops)
  }

  test("each service router serves only its routes; the others are 404 no such route") {
    val w = new ServiceWorld
    val owners = new InMemoryQueryOwnership(100)
    val qj = w.service(kind = QueryJobs, ownership = owners)
    val ee = w.service(kind = EvidenceExplain, ownership = owners)
    val g = w.service(kind = Graph, ownership = owners)
    def noRoute(r: HttpResponse) =
      r.status == 404 && json(r)("error")("message").str == "no such route"
    try {
      val q = post(
        qj.router,
        "/v1/query",
        ujson.Obj("question" -> "Who are the coauthors of Ada Lovelace?").render()
      )
      assertEquals(q.status, 200, q.bodyText)
      val ev = json(q)("links")("evidence").arr.head.str
      val node = s"/v1/graph/nodes/${ServiceWorld.ada}"
      assert(noRoute(get(qj.router, ev)))
      assert(noRoute(get(qj.router, node)))
      assert(noRoute(post(ee.router, "/v1/query", "{}")))
      assert(noRoute(get(ee.router, node)))
      assert(noRoute(post(g.router, "/v1/query", "{}")))
      assert(noRoute(get(g.router, ev)))
      assert(noRoute(get(g.router, "/v1/jobs/job-1")))
      assertEquals(get(ee.router, ev).status, 200)
      assertEquals(get(g.router, node).status, 200)
    } finally { qj.close(); ee.close(); g.close() }
  }

  test("health and readiness per service: each checks only its own pools and circuits") {
    val w = new ServiceWorld
    val base = Set("accepting-requests", "credential-source", "executors")
    val expected = Map(
      QueryJobs -> (base ++ Set(
        "workflow-workers",
        "job-workers",
        "circuit-workflow",
        "circuit-key-value"
      )),
      EvidenceExplain -> (base + "circuit-key-value"),
      Graph -> (base + "circuit-graph")
    )
    expected.foreach { case (kind, checks) =>
      val s = w.service(kind = kind, ownership = new InMemoryQueryOwnership(10))
      try {
        assertEquals(get(s.router, "/healthz", key = None).status, 200)
        val r = get(s.router, "/readyz", key = None)
        assertEquals(r.status, 200, s"$kind ${r.bodyText}")
        assertEquals(json(r)("checks").obj.keySet.toSet, checks, kind.tag)
        assertEquals(
          s.breakers.map(_.name).toSet,
          checks.filter(_.startsWith("circuit-")).map(_.stripPrefix("circuit-"))
        )
        assertEquals(get(s.router, "/metrics").status, 200)
      } finally s.close()
    }
  }

  private def build(w: ServiceWorld, kind: ServiceKind, ownership: QueryOwnership) =
    ServiceRuntime.build(
      w.runtime,
      w.ragWorkflow,
      new FakeKeys,
      ServiceWorld.config(),
      w.access,
      new RecordingLog,
      new TestClock,
      new CountingIds,
      kind,
      ownership
    )

  test("a service missing its required port refuses to compose") {
    val w = new ServiceWorld
    val noStores = ServiceRuntime.build(
      null,
      w.ragWorkflow,
      new FakeKeys,
      ServiceWorld.config(),
      w.access,
      new RecordingLog,
      new TestClock,
      new CountingIds,
      Graph,
      new InMemoryQueryOwnership(10)
    )
    assert(noStores.isLeft)
    val noWorkflow = ServiceRuntime.build(
      w.runtime,
      null,
      new FakeKeys,
      ServiceWorld.config(),
      w.access,
      new RecordingLog,
      new TestClock,
      new CountingIds,
      QueryJobs,
      new InMemoryQueryOwnership(10)
    )
    assert(noWorkflow.isLeft)
  }

  test("SE-P10-04 n5: split services need an explicit shared ownership port; All may default") {
    val w = new ServiceWorld
    Vector(QueryJobs, EvidenceExplain).foreach { k =>
      val r = build(w, k, null)
      assert(r.left.exists(_.contains("ownership")), s"$k: $r")
    }
    Vector(All, Graph).foreach { k =>
      val r = build(w, k, null)
      assert(r.isRight, s"$k: $r")
      r.foreach(_.close())
    }
  }

  test("mains: no arguments accepted; without a provider on the classpath they refuse to start") {
    val errors = Vector.newBuilder[String]
    assertEquals(
      ServiceMain.launch(Graph, Array("--port", "1"), () => fail("unused"), errors += _),
      2
    )
    val code = ServiceMain.launch(
      QueryJobs,
      Array.empty,
      () => ServiceMain.loadProvider(getClass.getClassLoader),
      errors += _
    )
    assertEquals(code, 1)
    val all = errors.result()
    assert(all.exists(_.contains("takes no arguments")), all.mkString("\n"))
    assert(all.exists(_.contains("no scholarchain.services.ServiceProvider")), all.mkString("\n"))
  }

  test("SE-P10-04 n1: overflowCapacity is a validated setting") {
    val w = new ServiceWorld
    val s = w.service(kind = Graph)
    try
      assert(
        HttpServerAdapter
          .start(s.router, server().copy(overflowCapacity = 0))
          .left
          .exists(_.contains("overflowCapacity"))
      )
    finally s.close()
  }

  test(
    "three services in one JVM, each with its own router and loopback port: query -> evidence -> explain -> graph"
  ) {
    val w = new ServiceWorld
    val owners = new InMemoryQueryOwnership(1000)
    val provider = new ServiceProvider {
      def settings(kind: ServiceKind) =
        Right(ServiceSettings(ServiceWorld.config(), server(), 0L, 0))
      def ports(kind: ServiceKind) = Right(w.ports(owners))
    }
    val started = Vector(QueryJobs, EvidenceExplain, Graph)
      .map(k => k -> ServiceMain.start(k, provider).fold(e => fail(e), identity))
      .toMap
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    def call(kind: ServiceKind, path: String, key: Option[String], body: Option[String] = None) = {
      val b = JRequest
        .newBuilder(URI.create(s"http://127.0.0.1:${started(kind).port}$path"))
        .timeout(Duration.ofSeconds(30))
      key.foreach(k => b.header("Authorization", s"Bearer $k"))
      body match {
        case Some(x) =>
          b.header("Content-Type", "application/json").POST(JRequest.BodyPublishers.ofString(x))
        case None => b.GET()
      }
      client.send(b.build(), JResponse.BodyHandlers.ofString())
    }
    try {
      assertEquals(started.values.map(_.port).toSet.size, 3, "three distinct ports")
      val q = call(
        QueryJobs,
        "/v1/query",
        Some(FakeKeys.FullKey),
        Some(resource("query-sync.request.json"))
      )
      assertEquals(q.statusCode(), 200, q.body())
      val answer = ujson.read(q.body())
      assertEquals(ApiContract.check("query-sync.response.json", answer), Vector.empty)
      val queryId = answer("queryId").str
      val claims = answer("answer")("claims").arr
      assert(claims.nonEmpty && claims.forall(_("evidenceIds").arr.nonEmpty))
      // Every evidence link resolves on EvidenceExplain; its owner sees its occurrences.
      answer("links")("evidence").arr.map(_.str).foreach { l =>
        val mine = call(EvidenceExplain, l, Some(FakeKeys.FullKey))
        assertEquals(mine.statusCode(), 200, l)
        assertEquals(ujson.read(mine.body())("occurrences").arr.size, 1, l)
        val other = call(EvidenceExplain, l, Some(FakeKeys.ReaderKey))
        assertEquals(other.statusCode(), 200, l)
        assertEquals(ujson.read(other.body())("occurrences").arr.size, 0, l)
      }
      // Explain on EvidenceExplain honours the owner recorded by QueryJobs.
      val x = call(EvidenceExplain, s"/v1/explain/$queryId", Some(FakeKeys.FullKey))
      assertEquals(x.statusCode(), 200, x.body())
      assertEquals(ujson.read(x.body())("queryId").str, queryId)
      assertEquals(
        call(EvidenceExplain, s"/v1/explain/$queryId", Some(FakeKeys.OtherKey)).statusCode(),
        404
      )
      // Graph exploration on Graph.
      val n =
        call(Graph, s"/v1/graph/nodes/${ServiceWorld.ada}/neighbors", Some(FakeKeys.ReaderKey))
      assertEquals(n.statusCode(), 200, n.body())
      assertEquals(ujson.read(n.body())("items")(0)("nodeId").str, ServiceWorld.bob)
      // Routes of another service are 404 on each port; ops routes everywhere.
      assertEquals(call(Graph, "/v1/query", Some(FakeKeys.FullKey), Some("{}")).statusCode(), 404)
      assertEquals(
        call(QueryJobs, s"/v1/explain/$queryId", Some(FakeKeys.FullKey)).statusCode(),
        404
      )
      started.keys.foreach { k =>
        assertEquals(call(k, "/healthz", None).statusCode(), 200, k.tag)
        assertEquals(call(k, "/readyz", None).statusCode(), 200, k.tag)
      }
    } finally started.values.foreach(_.stop())
  }

  test("graceful stop: readiness fails and /v1 routes answer 503 during the drain") {
    val w = new ServiceWorld
    val s = w.service(kind = Graph)
    s.drain()
    assertEquals(get(s.router, "/readyz", key = None).status, 503)
    assertEquals(get(s.router, s"/v1/graph/nodes/${ServiceWorld.ada}").status, 503)
    assertEquals(get(s.router, "/healthz", key = None).status, 200)
    s.close()
  }

  /** A graph reader whose calls block until `gate` opens (an in-flight exchange). */
  private final class BlockingGraph(inner: GraphReader) extends GraphReader {
    val gate = new CountDownLatch(1)
    val entered = new CountDownLatch(1)
    def run(q: BoundQuery): Either[StoreError, Vector[GraphRow]] = {
      entered.countDown()
      gate.await(30, TimeUnit.SECONDS)
      inner.run(q)
    }
  }

  test(
    "SE-P10-04 n3: stop keeps serving /readyz 503 for the drain window; bounded with an exchange in flight"
  ) {
    var blocking: BlockingGraph = null
    val w = new ServiceWorld(graphWrap = g => { blocking = new BlockingGraph(g); blocking })
    val drainMillis = 400L
    val grace = 1
    val running = ServiceMain
      .start(
        Graph,
        ServiceSettings(ServiceWorld.config(), server(), drainMillis, grace),
        w.ports(new InMemoryQueryOwnership(10))
      )
      .fold(e => fail(e), identity)
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    def uri(p: String) = URI.create(s"http://127.0.0.1:${running.port}$p")
    try {
      val inFlight = client.sendAsync(
        JRequest
          .newBuilder(uri(s"/v1/graph/nodes/${ServiceWorld.ada}"))
          .header("Authorization", s"Bearer ${FakeKeys.ReaderKey}")
          .timeout(Duration.ofSeconds(30))
          .GET()
          .build(),
        JResponse.BodyHandlers.ofString()
      )
      assert(blocking.entered.await(5, TimeUnit.SECONDS), "the exchange is in flight")
      val t0 = System.nanoTime()
      val stopper = new Thread(() => running.stop())
      stopper.start()
      // During the drain window the server still answers, with readiness failing.
      assert(eventually(2000) {
        val r = client.send(
          JRequest.newBuilder(uri("/readyz")).timeout(Duration.ofSeconds(2)).GET().build(),
          JResponse.BodyHandlers.ofString()
        )
        r.statusCode() == 503
      })
      stopper.join(15000)
      val ms = (System.nanoTime() - t0) / 1000000L
      assert(!stopper.isAlive, "stop returned")
      // Documented worst case: drainMillis + 3 x graceSeconds (plus scheduling).
      assert(ms >= drainMillis, s"stopped after $ms ms, before the drain window ended")
      assert(ms <= drainMillis + 3 * grace * 1000L + 2000L, s"stop took $ms ms")
      inFlight.cancel(true)
    } finally { if (blocking != null) blocking.gate.countDown() }
  }
}
