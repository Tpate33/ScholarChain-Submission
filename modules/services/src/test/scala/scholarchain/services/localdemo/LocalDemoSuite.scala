package scholarchain.services.localdemo

import java.net.{ConnectException, URI}
import java.net.http.{HttpClient, HttpRequest as JRequest, HttpResponse as JResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.time.Duration

import scala.jdk.CollectionConverters.*

import scholarchain.services.*

/** SV-INT-03a: the NON-PRODUCTION local demo profile (handoff criterion C). */
class LocalDemoSuite extends munit.FunSuite {

  private def sha256Hex(s: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(s.getBytes(StandardCharsets.US_ASCII))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  private def hexOf(b: Array[Byte]): String = b.map(x => f"${x & 0xff}%02x").mkString

  private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

  private def call(
      port: Int,
      method: String,
      path: String,
      key: Option[String] = Some(LocalDemo.DemoApiKey),
      body: Option[String] = None,
      idempotencyKey: Option[String] = None
  ): JResponse[String] = {
    val b = JRequest
      .newBuilder(URI.create(s"http://127.0.0.1:$port$path"))
      .timeout(Duration.ofSeconds(30))
    key.foreach(k => b.header("Authorization", s"Bearer $k"))
    idempotencyKey.foreach(k => b.header("Idempotency-Key", k))
    (method, body) match {
      case ("POST", Some(x)) =>
        b.header("Content-Type", "application/json").POST(JRequest.BodyPublishers.ofString(x))
      case _ => b.GET()
    }
    client.send(b.build(), JResponse.BodyHandlers.ofString())
  }

  private def profile(r: JResponse[String]): Option[String] =
    r.headers().firstValue(LocalDemo.ProfileHeader._1).map(Option(_)).orElse(None)

  private def withDemo[A](f: StartedDemo => A): A = {
    val d = LocalDemo.start(LocalDemoArgs(0)).fold(e => fail(e), identity)
    try f(d)
    finally d.stop()
  }

  private val queryBody =
    ujson.Obj("question" -> "Who are the coauthors of Ada Lovelace?", "mode" -> "sync").render()

  // ------------------------------------------------------------------ fixtures

  test("the key fixture holds only the SHA-256 digest of the documented demo key, never the key") {
    val keys = LocalDemoKeys.load().fold(e => fail(e), identity)
    val clients = keys.clients().fold(e => fail(e), identity)
    assertEquals(clients.map(_.clientId), Vector("local-demo-client"))
    assertEquals(hexOf(clients.head.keyDigest), sha256Hex(LocalDemo.DemoApiKey))
    assertEquals(clients.head.scopes, Set(Scope.Query, Scope.Read, Scope.Ops))
    val raw = DemoSeed.resourceText(LocalDemoKeys.Resource).getOrElse(fail("no key fixture"))
    assert(!raw.contains(LocalDemo.DemoApiKey), "the fixture must not hold the raw key")
    assert(raw.contains("\"nonProduction\": true"))
  }

  test("the key fixture is decoded strictly: every malformed file is refused") {
    val good = DemoSeed.resourceText(LocalDemoKeys.Resource).getOrElse(fail("no key fixture"))
    assert(LocalDemoKeys.parse(good).isRight)
    val bad = Vector(
      good.replace(LocalDemoKeys.SchemaVersion, "scholarchain.services.local-demo-api-keys.v0"),
      good.replace("\"nonProduction\": true", "\"nonProduction\": false"),
      good.replace("\"scopes\"", "\"extra\": 1, \"scopes\""),
      good.replace("\"ops\"", "\"admin\""),
      good.replace(sha256Hex(LocalDemo.DemoApiKey), "not-a-digest"),
      good.replace("\"clients\": [", "\"clients\": [] , \"x\": ["),
      "{}",
      "not json"
    )
    bad.zipWithIndex.foreach { case (b, i) =>
      assert(LocalDemoKeys.parse(b).isLeft, s"case $i was accepted")
    }
  }

  test(
    "the seed is decoded strictly and builds the fixture world (5 researchers, 3 coauthor edges)"
  ) {
    val good = DemoSeed.resourceText(DemoSeed.Resource).getOrElse(fail("no seed"))
    val seed = DemoSeed.parse(good).fold(e => fail(e), identity)
    assertEquals(seed.researchers.map(_.alias), Vector("ada", "bob", "cyd", "dan", "eve"))
    val w = LocalDemoWorld.of(seed).fold(e => fail(e), identity)
    assertEquals(w.researcherIds.values.toSet.size, 5)
    assertEquals(w.seed.coauthorships.size, 3)
    val bad = Vector(
      good.replace(DemoSeed.SchemaVersion, "scholarchain.services.local-demo-world.v0"),
      good.replace("\"nonProduction\": true", "\"nonProduction\": false"),
      good.replace("\"a\": \"ada\"", "\"a\": \"zed\""),
      good.replace("\"label\": \"Ada Lovelace\",", "\"label\": \"Ada Lovelace\", \"x\": 1,"),
      "[]"
    )
    bad.zipWithIndex.foreach { case (b, i) =>
      assert(DemoSeed.parse(b).isLeft, s"case $i was accepted")
    }
    // A seed row refused by its core contract fails closed (no partial world).
    val badStatus =
      good.replace("\"identityStatus\": \"singleton\"", "\"identityStatus\": \"maybe\"")
    assert(DemoSeed.parse(badStatus).flatMap(LocalDemoWorld.of).isLeft)
  }

  // ------------------------------------------------------------------ arguments and binding

  test("arguments: --port only (default 8080, 0 ephemeral); anything else, and any host, refused") {
    assertEquals(LocalDemo.parseArgs(Vector.empty), Right(LocalDemoArgs(8080)))
    assertEquals(LocalDemo.parseArgs(Vector("--port", "0")), Right(LocalDemoArgs(0)))
    assertEquals(LocalDemo.parseArgs(Vector("--port", "9090")), Right(LocalDemoArgs(9090)))
    Vector(
      Vector("--port"),
      Vector("--port", "-1"),
      Vector("--port", "65536"),
      Vector("--port", "+80"),
      Vector("--port", "x"),
      Vector("--host", "0.0.0.0"),
      Vector("--port", "1", "--host", "0.0.0.0"),
      Vector("extra")
    ).foreach(a => assert(LocalDemo.parseArgs(a).isLeft, a.mkString(" ")))
    assertEquals(LocalDemo.server(1234).host, "127.0.0.1")
  }

  test("the demo binds the IPv4 loopback address only") {
    withDemo { d =>
      assertEquals(d.host, "127.0.0.1")
      val local = java.net.InetAddress.getLocalHost
      // Reaching the port through a non-loopback address of this host must fail.
      if (!local.isLoopbackAddress) {
        val s = new java.net.Socket()
        val refused =
          try { s.connect(new java.net.InetSocketAddress(local, d.port), 2000); false }
          catch { case _: java.io.IOException => true }
          finally s.close()
        assert(refused, s"reachable on ${local.getHostAddress}")
      }
      assertEquals(call(d.port, "GET", "/healthz", key = None).statusCode(), 200)
    }
  }

  // ------------------------------------------------------------------ the complete flow

  test(
    "loopback HTTP flow: query -> cited evidence -> explain -> graph; 401 without or with a wrong key; idempotent replay"
  ) {
    withDemo { d =>
      val port = d.port
      val idem = "demo-flow-0001"
      val q = call(port, "POST", "/v1/query", body = Some(queryBody), idempotencyKey = Some(idem))
      assertEquals(q.statusCode(), 200, q.body())
      val answer = ujson.read(q.body())
      assertEquals(ApiContract.check("query-sync.response.json", answer), Vector.empty)
      assertEquals(answer("verification")("overall").str, "VERIFIED")
      assertEquals(answer("nonProductionModel").bool, true)
      val claims = answer("answer")("claims").arr
      assert(claims.nonEmpty && claims.forall(_("evidenceIds").arr.nonEmpty), q.body())
      val bundleIds =
        answer("answer")("evidenceBundle")("items").arr.map(_("evidenceId").str).toSet
      val cited = claims.flatMap(_("evidenceIds").arr.map(_.str)).toVector
      assert(cited.forall(bundleIds), "every cited evidence ID is in the bundle")
      val queryId = answer("queryId").str

      // GET /v1/evidence/{id} for a cited evidence ID.
      val ev = call(port, "GET", s"/v1/evidence/${cited.head}")
      assertEquals(ev.statusCode(), 200, ev.body())
      val evJson = ujson.read(ev.body())
      assertEquals(ApiContract.check("evidence.response.json", evJson), Vector.empty)
      assertEquals(evJson("evidenceId").str, cited.head)
      assertEquals(evJson("occurrences").arr.size, 1)

      // GET /v1/explain/{queryId}.
      val x = call(port, "GET", s"/v1/explain/$queryId")
      assertEquals(x.statusCode(), 200, x.body())
      val xJson = ujson.read(x.body())
      assertEquals(ApiContract.check("explain.response.json", xJson), Vector.empty)
      assertEquals(xJson("queryId").str, queryId)

      // One graph route: Ada's neighbours are Bob only.
      val ada = d.world.researcherIds("ada")
      val n = call(port, "GET", s"/v1/graph/nodes/$ada/neighbors")
      assertEquals(n.statusCode(), 200, n.body())
      assertEquals(
        ujson.read(n.body())("items").arr.map(_("nodeId").str).toVector,
        Vector(d.world.researcherIds("bob"))
      )

      // 401: missing key, wrong key.
      val missing = call(port, "POST", "/v1/query", key = None, body = Some(queryBody))
      assertEquals(missing.statusCode(), 401, missing.body())
      val wrong = call(
        port,
        "POST",
        "/v1/query",
        key = Some("scholarchain-wrong-key-0123456789"),
        body = Some(queryBody)
      )
      assertEquals(wrong.statusCode(), 401, wrong.body())
      assertEquals(call(port, "GET", s"/v1/explain/$queryId", key = None).statusCode(), 401)
      assert(ujson.read(wrong.body())("error")("traceId").str.nonEmpty, wrong.body())

      // Replay under the same Idempotency-Key: the same result, no new query.
      val replay =
        call(port, "POST", "/v1/query", body = Some(queryBody), idempotencyKey = Some(idem))
      assertEquals(replay.statusCode(), 200, replay.body())
      val again = ujson.read(replay.body())
      assertEquals(again("queryId").str, queryId)
      assertEquals(again("answer"), answer("answer"))
      // A fresh key is a new query.
      val fresh = call(
        port,
        "POST",
        "/v1/query",
        body = Some(queryBody),
        idempotencyKey = Some("demo-flow-0002")
      )
      assertEquals(fresh.statusCode(), 200, fresh.body())
      assertNotEquals(ujson.read(fresh.body())("queryId").str, queryId)
    }
  }

  test("every example question of the seed gets its documented outcome") {
    withDemo { d =>
      d.world.seed.exampleQuestions.foreach { ex =>
        val r = call(
          d.port,
          "POST",
          "/v1/query",
          body = Some(ujson.Obj("question" -> ex.question, "mode" -> "sync").render())
        )
        assertEquals(r.statusCode(), 200, s"${ex.question}: ${r.body()}")
        assertEquals(ujson.read(r.body())("status").str, ex.expectedStatus, ex.question)
      }
    }
  }

  // ------------------------------------------------------------------ profile identification

  test(
    "every demo response carries the NON-PRODUCTION profile header; the production default adds none"
  ) {
    withDemo { d =>
      val rs = Vector(
        call(d.port, "GET", "/healthz", key = None),
        call(d.port, "GET", "/readyz", key = None),
        call(d.port, "GET", "/v1/explain/nope", key = None),
        call(d.port, "GET", "/v1/no-such-route"),
        call(d.port, "POST", "/v1/query", body = Some(queryBody))
      )
      rs.foreach(r =>
        assertEquals(profile(r), Some("local-demo; NON-PRODUCTION"), r.uri().toString)
      )
      // The same router behind the adapter's production default: no profile header.
      val plain = HttpServerAdapter
        .start(d.running.runtime.router, LocalDemo.server(0))
        .fold(e => fail(e), identity)
      try assertEquals(profile(call(plain.port, "GET", "/healthz", key = None)), None)
      finally plain.stop(0)
    }
  }

  test(
    "launch prints the NON-PRODUCTION banner, registers a shutdown hook, and the hook stops the server"
  ) {
    val out = Vector.newBuilder[String]
    val err = Vector.newBuilder[String]
    var hook: Option[Thread] = None
    val code = LocalDemo.launch(Array("--port", "0"), out += _, err += _, t => hook = Some(t))
    assertEquals(code, 0, err.result().mkString("\n"))
    val lines = out.result()
    assert(lines.exists(_.contains("NON-PRODUCTION")), lines.mkString("\n"))
    assert(lines.exists(_.contains(LocalDemo.DemoApiKey)), "the banner documents the demo key")
    val Listening = "^.*listening=http://127\\.0\\.0\\.1:([0-9]+) .*$".r
    val port = lines.collectFirst { case Listening(p) => p.toInt }.getOrElse(fail(lines.mkString))
    assertEquals(call(port, "GET", "/healthz", key = None).statusCode(), 200)
    val h = hook.getOrElse(fail("no shutdown hook registered"))
    h.start()
    h.join(15000)
    assert(!h.isAlive, "the shutdown hook returned")
    val refused =
      try { call(port, "GET", "/healthz", key = None); false }
      catch { case _: ConnectException => true; case _: java.io.IOException => true }
    assert(refused, "the server still answers after the shutdown hook ran")
  }

  test("launch refuses bad arguments with exit code 2 and registers no hook") {
    val err = Vector.newBuilder[String]
    var hooked = false
    assertEquals(
      LocalDemo.launch(Array("--host", "0.0.0.0"), _ => (), err += _, _ => hooked = true),
      2
    )
    assert(!hooked)
    assert(err.result().exists(_.contains("NON-PRODUCTION")))
  }

  // ------------------------------------------------------------------ isolation from production

  test("the production provider path cannot reach the demo") {
    val demoClasses: Vector[Class[?]] = Vector(
      LocalDemo.getClass,
      LocalDemoMain.getClass,
      classOf[LocalDemoWorld],
      LocalDemoWorld.getClass,
      classOf[LocalDemoKeys],
      classOf[StartedDemo],
      classOf[LocalDemoArgs]
    )
    demoClasses.foreach(c =>
      assert(!classOf[ServiceProvider].isAssignableFrom(c), s"${c.getName} is a ServiceProvider")
    )
    // No ServiceLoader registration names the demo, and ServiceLoader finds no provider at all.
    val loader = getClass.getClassLoader
    val regs = loader
      .getResources("META-INF/services/scholarchain.services.ServiceProvider")
      .asScala
      .toVector
      .map(u =>
        scala.util.Using.resource(u.openStream())(in =>
          new String(in.readAllBytes(), StandardCharsets.UTF_8)
        )
      )
    assert(!regs.exists(_.contains("localdemo")), regs.mkString("\n"))
    val loaded = ServiceMain.loadProvider(loader)
    assert(
      loaded.left.exists(_.contains("no scholarchain.services.ServiceProvider")),
      loaded.toString
    )
    // The production mains refuse to start without a provider, even with the demo on the classpath.
    val errors = Vector.newBuilder[String]
    assertEquals(
      ServiceMain.launch(
        ServiceKind.QueryJobs,
        Array.empty,
        () => ServiceMain.loadProvider(loader),
        errors += _
      ),
      1
    )
    // No production source of the services package names the demo package.
    val dir = Vector(
      Paths.get("src/main/scala/scholarchain/services"),
      Paths.get("modules/services/src/main/scala/scholarchain/services")
    ).find(Files.isDirectory(_)).getOrElse(fail("services main sources not found"))
    val sources: Vector[Path] =
      scala.util.Using
        .resource(Files.list(dir))(_.iterator().asScala.toVector)
        .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".scala"))
    assert(sources.size >= 10, sources.toString)
    sources.foreach { p =>
      val text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
      assert(!text.contains("localdemo") && !text.contains("LocalDemo"), s"$p names the demo")
    }
  }

  // The production provider refuses every injected scholarchain.storage.fake.* store (its notFake
  // rule, covered by ProductionCompositionSuite); this pins that the demo's stores are such fakes.
  test("the demo's stores are all storage in-memory fakes (the package production refuses)") {
    val w = LocalDemoWorld.fromClasspath().fold(e => fail(e), identity)
    Vector[AnyRef](w.graphStore, w.kv, w.objects, w.vectors).foreach(s =>
      assert(s.getClass.getName.startsWith("scholarchain.storage.fake."), s.getClass.getName)
    )
  }
}
