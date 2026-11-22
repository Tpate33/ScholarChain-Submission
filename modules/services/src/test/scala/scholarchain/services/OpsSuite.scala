package scholarchain.services

import scala.jdk.CollectionConverters.*

/** Health vs readiness, metrics, access logging and the bounded in-memory stores (SE-P10-01). */
class OpsSuite extends munit.FunSuite {
  import TestHttp.*

  test("health and readiness are distinct: a credential-source outage fails only readiness") {
    val w = new ServiceWorld
    val keys = new FakeKeys
    val s = w.service(keys = keys)
    try {
      val h = get(s.router, "/healthz", key = None)
      assertEquals(h.status, 200)
      assertEquals(json(h), ujson.read(resource("healthz.response.json")))
      val r = get(s.router, "/readyz", key = None)
      assertEquals(r.status, 200)
      assertEquals(json(r), ujson.read(resource("readyz.response.json")))
      keys.failing = true
      assertEquals(get(s.router, "/healthz", key = None).status, 200)
      val down = get(s.router, "/readyz", key = None)
      assertEquals(down.status, 503)
      assertEquals(json(down)("status").str, "not-ready")
      assertEquals(json(down)("checks")("credential-source").str, "failing")
      // Without the credential source no authenticated route can serve: 503, never 200 or 401.
      val q = get(s.router, "/v1/jobs/job-1")
      assertEquals((q.status, json(q)("error")("code").str), (503, "not-ready"))
      keys.failing = false
      assertEquals(get(s.router, "/readyz", key = None).status, 200)
    } finally s.close()
  }

  test("draining: after close(), readiness fails and /v1 routes are 503 while liveness stays 200") {
    val w = new ServiceWorld
    val s = w.service()
    s.close()
    assertEquals(get(s.router, "/healthz", key = None).status, 200)
    val r = json(get(s.router, "/readyz", key = None))
    assertEquals(r("checks")("accepting-requests").str, "failing")
    assertEquals(r("checks")("executors").str, "failing")
    val q = post(
      s.router,
      "/v1/query",
      ujson.Obj("question" -> "Who are the coauthors of Ada Lovelace?").render()
    )
    assertEquals((q.status, json(q)("error")("code").str), (503, "not-ready"))
  }

  test("health and readiness bodies expose no secret, key digest or configuration value") {
    val w = new ServiceWorld
    val s = w.service()
    try {
      val texts = Vector("/healthz", "/readyz").map(p => get(s.router, p, key = None).bodyText)
      val forbidden = Vector(
        FakeKeys.FullKey,
        FakeKeys.sha(FakeKeys.FullKey),
        FakeKeys.sha(FakeKeys.ReaderKey),
        "client-full",
        ServiceWorld.run,
        "test-context-bucket",
        "16384",
        "10000",
        "2018"
      )
      texts.foreach(t => forbidden.foreach(f => assert(!t.contains(f), s"$f in $t")))
      assertEquals(
        json(get(s.router, "/readyz", key = None))("checks").obj.keySet.toSet,
        Set(
          "accepting-requests",
          "credential-source",
          "executors",
          "workflow-workers",
          "job-workers",
          "circuit-workflow",
          "circuit-key-value",
          "circuit-graph"
        )
      )
    } finally s.close()
  }

  test(
    "metrics: ops scope, request counts by route template, 404 when the deployment disables it"
  ) {
    val w = new ServiceWorld
    val s = w.service()
    try {
      get(s.router, "/healthz", key = None)
      get(s.router, "/v1/evidence/ev:" + "0" * 64)
      val m = get(s.router, "/metrics")
      assertEquals(m.status, 200)
      assert(m.header("Content-Type").exists(_.startsWith("text/plain")))
      val text = m.bodyText
      assert(
        text.contains("""scholarchain_http_requests_total{route="GET /healthz",status="200"} 1"""),
        text
      )
      assert(
        text.contains(
          """scholarchain_http_requests_total{route="GET /v1/evidence/{evidenceId}",status="404"} 1"""
        ),
        text
      )
      assert(text.contains("""scholarchain_circuit_open{dependency="workflow"} 0"""), text)
      assert(!text.contains("ev:"), "no ID in a metric label")
      assertEquals(get(s.router, "/metrics", key = None).status, 401)
    } finally s.close()
    val off = new ServiceWorld().service(config = ServiceWorld.config(metricsEnabled = false))
    try assertEquals(get(off.router, "/metrics").status, 404)
    finally off.close()
  }

  test("routing: unknown paths and methods are 404 with trace IDs; versioned paths only") {
    val w = new ServiceWorld
    val s = w.service()
    try {
      Vector("/query", "/v2/query", "/v1/query/", "/v1/evidence", "/").foreach { p =>
        val r = get(s.router, p)
        assertEquals(r.status, 404, p)
        assertEquals(ApiContract.check("error.response.json", json(r)), Vector.empty)
      }
      assertEquals(post(s.router, "/v1/evidence/ev:" + "0" * 64, "{}").status, 404)
      assertEquals(get(s.router, "/v1/query").status, 404)
      assertEquals(get(s.router, "/v1/evidence/%zz").status, 400)
    } finally s.close()
  }

  test("access log: one event per request with the route template; never keys or question text") {
    val w = new ServiceWorld
    val log = new RecordingLog
    val s = w.service(log = log)
    try {
      post(
        s.router,
        "/v1/query",
        ujson.Obj("question" -> "Who are the coauthors of Ada Lovelace?").render()
      )
      get(s.router, s"/v1/graph/nodes/${ServiceWorld.ada}", key = Some(FakeKeys.ReaderKey))
      post(s.router, "/v1/query", "{", key = Some("not-a-valid-key-0123456789"))
      val events = log.events.asScala.toVector
      assertEquals(events.size, 3)
      assertEquals(
        events.map(_.route),
        Vector("POST /v1/query", "GET /v1/graph/nodes/{nodeId}", "POST /v1/query")
      )
      assertEquals(events.map(_.clientId), Vector(Some("client-full"), Some("client-reader"), None))
      assertEquals(events(2).errorCode, Some("unauthenticated"))
      events.map(_.render).foreach { line =>
        Vector(
          "Ada Lovelace",
          FakeKeys.FullKey,
          FakeKeys.ReaderKey,
          "not-a-valid-key",
          ServiceWorld.ada,
          "Bearer"
        )
          .foreach(f => assert(!line.contains(f), s"$f in $line"))
      }
    } finally s.close()
  }

  test("thin routes: the router names no rag, storage, graph template or key-value query") {
    var dir = java.nio.file.Paths.get("").toAbsolutePath
    val rel = "modules/services/src/main/scala/scholarchain/services/Router.scala"
    while (dir != null && !java.nio.file.Files.isRegularFile(dir.resolve(rel))) dir = dir.getParent
    assert(dir != null, s"$rel not found above the working directory")
    val src = new String(java.nio.file.Files.readAllBytes(dir.resolve(rel)), "UTF-8")
    Vector(
      "scholarchain.rag",
      "scholarchain.storage",
      "GraphQuery",
      "BoundQuery",
      "KeyQuery",
      "RuntimeStores",
      "CollaborationWorkflow"
    ).foreach(f => assert(!src.contains(f), s"Router.scala names $f"))
    // Every route of the table is versioned under /v1 except the operations endpoints.
    Router.Routes.foreach { r =>
      assert(
        r.template.startsWith("/v1/") || Set("/healthz", "/readyz", "/metrics")(r.template),
        r.template
      )
    }
  }

  test("production logback.xml: root WARN, ScholarChain at INFO, no DEBUG or TRACE") {
    val in = getClass.getResourceAsStream("/logback.xml")
    assert(in != null, "logback.xml is on the services classpath")
    val xml =
      try new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      finally in.close()
    val body = xml.replaceAll("(?s)<!--.*?-->", "")
    assert("""<root level="WARN">""".r.findFirstIn(body).nonEmpty, body)
    val levels = """level="(\w+)"""".r.findAllMatchIn(body).map(_.group(1).toUpperCase).toVector
    assertEquals(levels.sorted, Vector("INFO", "WARN"))
  }

  test(
    "idempotency store: expiry, capacity eviction of completed records, refusal when all in flight"
  ) {
    val clock = new TestClock
    val s = new IdempotencyStore(2, 1000L, clock)
    assertEquals(s.begin("c", "a", "d1"), IdempotencyBegin.Fresh)
    assertEquals(s.begin("c", "a", "d1"), IdempotencyBegin.InProgress)
    assertEquals(s.begin("c", "a", "d2"), IdempotencyBegin.Mismatch)
    assertEquals(s.begin("c", "b", "d1"), IdempotencyBegin.Fresh)
    assertEquals(s.begin("c", "x", "d1"), IdempotencyBegin.Full)
    val done = IdempotentOutcome.JobRef("job-1")
    s.complete("c", "a", done)
    assertEquals(s.begin("c", "a", "d1"), IdempotencyBegin.Replay(done))
    assertEquals(s.begin("c", "x", "d1"), IdempotencyBegin.Fresh) // evicted the completed "a"
    s.complete("c", "b", done)
    clock.advance(1000L)
    assertEquals(s.begin("c", "b", "d9"), IdempotencyBegin.Fresh) // completed b expired
    s.abandon("c", "b")
    s.abandon("c", "x")
    assertEquals(s.size, 0)
  }

  test("SE-P10-02 m3: an in-flight idempotency record never expires, a completed one does") {
    val clock = new TestClock
    val s = new IdempotencyStore(4, 1000L, clock)
    assertEquals(s.begin("c", "k", "d"), IdempotencyBegin.Fresh)
    clock.advance(10000L) // far past the TTL while the first request still runs
    assertEquals(s.begin("c", "k", "d"), IdempotencyBegin.InProgress)
    assertEquals(s.begin("c", "k", "other"), IdempotencyBegin.Mismatch)
    val done = IdempotentOutcome.JobRef("job-1")
    s.complete("c", "k", done)
    clock.advance(999L) // the TTL runs from completion
    assertEquals(s.begin("c", "k", "d"), IdempotencyBegin.Replay(done))
    clock.advance(1L)
    assertEquals(s.begin("c", "k", "d"), IdempotencyBegin.Fresh)
  }

  test("SE-P10-02 m3: config refuses an idempotency TTL not above both workflow timeouts") {
    val c = ServiceWorld.config()
    assert(
      ServiceConfig
        .problems(c.copy(idempotencyTtlMillis = c.asyncTimeoutMillis))
        .exists(_.startsWith("idempotencyTtlMillis"))
    )
    assertEquals(ServiceConfig.problems(c), Vector.empty)
  }

  test("job store: owner isolation, eviction of finished jobs, refusal when all unfinished") {
    val clock = new TestClock
    val j = new JobStore(2, clock, 1)
    assert(j.create("c", "job-1", "query:1").isRight)
    assert(j.create("c", "job-2", "query:2").isRight)
    assertEquals(
      j.create("c", "job-3", "query:3").left.map(_.code),
      Left(ErrorCode.TooManyRequests)
    )
    j.start("job-1")
    j.succeed("job-1", Reply(200, ujson.Obj()))
    assert(j.create("c", "job-3", "query:3").isRight)
    assertEquals(j.get("c", "job-1"), None)
    assertEquals(j.get("other", "job-2"), None)
    assertEquals(j.get("c", "job-2").map(_.state), Some(JobState.Queued))
    assertEquals(j.counts("queued"), 2)
  }

  test("API key authenticator: constant-time digest match, scopes, malformed headers") {
    val a = new ApiKeyAuthenticator(new FakeKeys, 1)
    assertEquals(
      a.authenticate(Vector(s"Bearer ${FakeKeys.ReaderKey}")).map(_.clientId),
      Right("client-reader")
    )
    assertEquals(a.authenticate(Vector()).left.map(_.code), Left(ErrorCode.Unauthenticated))
    assertEquals(
      a.authenticate(Vector(s"Bearer ${FakeKeys.FullKey}", s"Bearer ${FakeKeys.FullKey}"))
        .left
        .map(_.code),
      Left(ErrorCode.Unauthenticated)
    )
    assertEquals(
      a.authenticate(Vector("Bearer short")).left.map(_.code),
      Left(ErrorCode.Unauthenticated)
    )
    val reader = a.authenticate(Vector(s"Bearer ${FakeKeys.ReaderKey}")).toOption.get
    assertEquals(a.authorize(reader, Scope.Query).left.map(_.code), Left(ErrorCode.Forbidden))
    assert(!reader.toString.contains(FakeKeys.sha(FakeKeys.ReaderKey)))
    assert(ApiClient.of("c", "ab", Set(Scope.Read)).isLeft)
    assert(ApiClient.of("c", FakeKeys.sha("x"), Set.empty).isLeft)
  }
}
