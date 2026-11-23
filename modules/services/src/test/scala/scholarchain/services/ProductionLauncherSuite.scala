package scholarchain.services

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JRequest, HttpResponse as JResponse}
import java.time.Duration

import scala.util.control.NonFatal

import scholarchain.core.config.{ConfigLoader, ProductionSettings, ServiceKindKey}
import scholarchain.storage.aws.{AwsClientSettings, RetrySetting}
import scholarchain.storage.aws.neptune.{NeptuneClientSettings, NeptuneReaderSettings}
import scholarchain.storage.aws.s3.S3ReaderSettings
import scholarchain.storage.aws.s3vectors.S3VectorsReaderSettings
import scholarchain.storage.model.StoreError
import scholarchain.storage.port.RuntimeStores

/**
 * SV-INT-03 (handoff criterion A): the production launcher of the three mains. Every client is a
 * stub ([[StubClients]]); no credentials provider, no SDK client, no AWS call, loopback only.
 */
class ProductionLauncherSuite extends munit.FunSuite {

  private val kinds = Vector(
    ServiceKind.QueryJobs -> ServiceKindKey.QueryJobs,
    ServiceKind.EvidenceExplain -> ServiceKindKey.EvidenceExplain,
    ServiceKind.Graph -> ServiceKindKey.Graph
  )

  private def right[A](e: Either[String, A]): A = e.fold(m => fail(m), identity)
  private def get[A](e: Either[StoreError, A]): A = e.fold(m => fail(m.message), identity)

  /** A workflow factory that keeps the stores it was given. */
  private final class Capture {
    @volatile var stores: RuntimeStores = null
    val factory: RuntimeStores => Either[String, QueryWorkflow] = s => {
      stores = s
      Right(new ServiceWorld().ragWorkflow)
    }
  }

  // ------------------------------------------------------------------ A1: forLauncher, never forProcess

  test("A1: a config without the adapter sections is refused before any key source or client") {
    val noAdapters = LauncherFixture.text(withAdapters = false)
    val c = LauncherFixture.config(noAdapters)
    kinds.foreach { case (kind, key) =>
      // forProcess accepts this configuration; the launcher's forLauncher does not.
      assert(ProductionSettings.forProcess(c, key).isRight, kind.tag)
      val h = new LauncherHarness(text = noAdapters, workflow = Some(new Capture().factory))
      val r = h.launcher.start(kind)
      assert(r.isLeft, s"${kind.tag}: $r")
      val m = r.left.toOption.get
      assert(m.contains("services.apiKeys.secretId"), m)
      if (kind == ServiceKind.QueryJobs) assert(m.contains("storage.s3.maxListKeys"), m)
      if (kind != ServiceKind.EvidenceExplain) assert(m.contains("storage.neptune.endpoint"), m)
      assert(h.launcher.settings(kind).isLeft, kind.tag)
      val errs = Vector.newBuilder[String]
      assertEquals(ServiceMain.launch(kind, Array.empty, () => Right(h.launcher), errs += _), 1)
      assertEquals(h.keyBuilds.get, 0, s"${kind.tag}: no key source was built")
      assertEquals(h.opens.get, 0, s"${kind.tag}: no storage client was opened")
      assert(!(errs.result() :+ m).mkString.contains("example-"), "no configured value is shown")
    }
  }

  test("A1: with the sections, the launcher's settings are exactly forProcess's for that kind") {
    val c = LauncherFixture.config()
    kinds.foreach { case (kind, key) =>
      val h = new LauncherHarness
      val viaProcess = ProductionSettings.forProcess(c, key).fold(e => fail(e.toString), identity)
      assertEquals(LauncherFixture.launcherSettings(key).process, viaProcess)
      assertEquals(
        h.launcher.settings(kind),
        Right(ProductionMapping.serviceSettings(viaProcess.service))
      )
      assert(h.launcher.ports(kind).isLeft, "ports outside start would leak clients")
    }
    assert(new LauncherHarness().launcher.start(ServiceKind.All).isLeft)
  }

  test("A1: the three mains use ProductionLauncher; without configuration they refuse, no client") {
    val p = ServiceMain.productionProvider().fold(e => fail(e), identity)
    assert(p.isInstanceOf[ProductionLauncher], p.getClass.getName)
    val h = new LauncherHarness
    val unconfigured = new ProductionLauncher(
      h.seams.copy(loadConfig = () => ConfigLoader.load(Map.empty, _ => Left("absent")))
    )
    kinds.foreach { case (kind, _) =>
      val errs = Vector.newBuilder[String]
      assertEquals(ServiceMain.launch(kind, Array.empty, () => Right(unconfigured), errs += _), 1)
      val all = errs.result().mkString("\n")
      assert(all.contains(s"${kind.tag}: not started: configuration:"), all)
      assert(all.contains(ConfigLoader.FilesEnvVar), all)
    }
    assertEquals((h.keyBuilds.get, h.opens.get), (0, 0))
  }

  // ------------------------------------------------------------------ A2: one bundle, mapped 1:1

  test("A2: LauncherSettings map 1:1 into one StorageClients bundle; one vector reader for both") {
    val ls = LauncherFixture.launcherSettings(ServiceKindKey.QueryJobs)
    val clients = new StubClients(LauncherFixture.tableNames())
    val deps = right(LauncherMapping.storage(ls, clients))
    assertEquals(clients.dynamo, Vector(get(RetrySetting.of(3))))
    assertEquals(
      clients.s3,
      Vector(
        get(AwsClientSettings.of("us-east-1", None, 20L, 10L, 2)) -> get(
          S3ReaderSettings.of(
            Set("example-raw-bucket", "example-curated-bucket", "example-context-bucket"),
            1048576,
            10
          )
        )
      )
    )
    assertEquals(
      clients.neptune,
      Vector(
        get(
          NeptuneClientSettings.of(
            "us-east-1",
            "https://neptune.example.invalid:8182",
            200L,
            100L,
            2
          )
        ) -> get(NeptuneReaderSettings.of(1000))
      )
    )
    assertEquals(
      clients.vectors,
      Vector(
        get(AwsClientSettings.of("us-east-1", None, 100L, 50L, 2)) -> get(
          S3VectorsReaderSettings.of("example-vector-bucket", 10, 10)
        )
      )
    )
    assert(deps.objects.exists(_ eq StubObjects))
    assert(deps.graph.exists(_ eq clients.graph))
    assert(deps.vectors.exists(_ eq StubVectorsAndIndexes))
    assert(
      deps.vectorIndexes.exists(_ eq StubVectorsAndIndexes),
      "vectorIndexes is the same reader"
    )
    assert(deps.dynamoDb.isDefined)
    assertEquals((deps.apiKeys, deps.workflow), (None, None))

    // evidence-explain: only the DynamoDB factory; graph: only Neptune.
    val ee = new StubClients(LauncherFixture.tableNames())
    val eeDeps = right(
      LauncherMapping.storage(LauncherFixture.launcherSettings(ServiceKindKey.EvidenceExplain), ee)
    )
    assertEquals((ee.dynamo.size, ee.s3.size, ee.neptune.size, ee.vectors.size), (1, 0, 0, 0))
    assertEquals((eeDeps.objects, eeDeps.graph, eeDeps.vectors), (None, None, None))
    val g = new StubClients(LauncherFixture.tableNames())
    val gDeps =
      right(LauncherMapping.storage(LauncherFixture.launcherSettings(ServiceKindKey.Graph), g))
    assertEquals((g.dynamo.size, g.s3.size, g.neptune.size, g.vectors.size), (0, 0, 1, 0))
    assertEquals(gDeps.dynamoDb, None)

    // A storage refusal names its section.
    val refusing = new StubClients(LauncherFixture.tableNames())
    refusing.refuseGraph = true
    val r = LauncherMapping.storage(ls, refusing)
    assert(r.left.exists(_.startsWith("storage.neptune: neptune: refused")), r.toString)
  }

  // ------------------------------------------------------------------ G5: close exactly once

  test("A2/A5: a missing workflow fails closed, names itself, and closes the bundle once") {
    val h = new LauncherHarness(workflow = None)
    val r = h.launcher.start(ServiceKind.QueryJobs)
    assert(r.left.exists(_.startsWith("workflow (rag CollaborationWorkflow")), r.toString)
    assert(r.left.exists(_.contains("not provided")), r.toString)
    assertEquals(h.opens.get, 1)
    assertEquals(h.clients.closes.get, 1, "closed once on the failed start")
    assertEquals(h.clients.dynamoClients.size, 1, "the bundle's DynamoDB factory was used")
  }

  test("A5: the production seams build no key source and no workflow: refused before any client") {
    val ls = LauncherFixture.launcherSettings(ServiceKindKey.QueryJobs)
    val system = ProductionLauncher.Seams.system
    assertEquals(system.apiKeys(ls.apiKeys), Left(ProductionLauncher.ApiKeysNotBuilt))
    assertEquals(system.workflow(ls), None)
    val h = new LauncherHarness(keys = system.apiKeys, workflow = Some(new Capture().factory))
    kinds.foreach { case (kind, _) =>
      val r = h.launcher.start(kind)
      assert(
        r.left.exists(_.startsWith("apiKeys (Secrets Manager ApiKeyDigestSource)")),
        r.toString
      )
      assert(r.left.exists(_.contains("not provided")), r.toString)
    }
    assertEquals(h.opens.get, 0, "no storage client was opened")
    // With a key source, the production workflow seam still refuses, naming the workflow.
    val w = new LauncherHarness(workflow = system.workflow(ls))
    assert(w.launcher.start(ServiceKind.QueryJobs).left.exists(_.startsWith("workflow")))
    assertEquals(w.clients.closes.get, 1)
  }

  test("G5: failures after the bundle opened close it once; a key source is closed too") {
    // A storage refusal while mapping.
    val refused = new LauncherHarness(workflow = Some(new Capture().factory))
    refused.clients.refuseGraph = true
    assert(refused.launcher.start(ServiceKind.Graph).left.exists(_.startsWith("storage.neptune")))
    assertEquals(refused.clients.closes.get, 1)
    // A failed bind (the configured port is taken).
    val taken = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
    try {
      val keys = new CloseableKeys
      val bind = new LauncherHarness(
        text = LauncherFixture.text(queryJobsPort = taken.getLocalPort),
        keys = _ => Right(keys),
        workflow = Some(new Capture().factory)
      )
      val r = bind.launcher.start(ServiceKind.QueryJobs)
      assert(r.left.exists(_.contains("could not bind")), r.toString)
      assertEquals(bind.clients.closes.get, 1)
      assertEquals(keys.closes.get, 1, "an AutoCloseable key source is released with the bundle")
    } finally taken.close()
    // The bundle never opened: nothing to close but the key source.
    val keys = new CloseableKeys
    val noOpen = new LauncherHarness(keys = _ => Right(keys), openFails = true)
    val r = noOpen.launcher.start(ServiceKind.Graph)
    assert(r.left.exists(_.startsWith("storage clients: no credentials")), r.toString)
    assertEquals(keys.closes.get, 1)
    // A throwing seam is a value-free refusal.
    val throwing = new LauncherHarness(keys = _ => throw new IllegalStateException("secret-ish"))
    val t = throwing.launcher.start(ServiceKind.Graph)
    assertEquals(t, Left("apiKeys: failed (IllegalStateException)"))
    assertEquals(throwing.opens.get, 0)
  }

  test("G5: a started process closes the bundle exactly once, on stop, after serving") {
    val h = new LauncherHarness
    val running = right(h.launcher.start(ServiceKind.Graph))
    try {
      assertEquals(h.clients.closes.get, 0, "open while running")
      val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
      val res = client.send(
        JRequest
          .newBuilder(URI.create(s"http://127.0.0.1:${running.port}/healthz"))
          .timeout(Duration.ofSeconds(10))
          .GET()
          .build(),
        JResponse.BodyHandlers.ofString()
      )
      assertEquals(res.statusCode(), 200)
    } finally running.stop()
    assertEquals(h.clients.closes.get, 1)
    try running.stop()
    catch { case NonFatal(_) => () }
    assertEquals(h.clients.closes.get, 1, "a second stop does not close again")
  }

  test("G5: a throwing workflow factory refuses safely and releases every opened resource") {
    val keys = new CloseableKeys
    var stores: RuntimeStores = null
    val factory: RuntimeStores => Either[String, QueryWorkflow] = s => {
      stores = s
      assertEquals(s.graph.run(null), Right(Vector.empty))
      throw new IllegalStateException("private-workflow-message")
    }
    val h = new LauncherHarness(keys = _ => Right(keys), workflow = Some(factory))
    val result = h.launcher.start(ServiceKind.QueryJobs)
    assertEquals(result, Left("workflow: failed (IllegalStateException)"))
    assertEquals(h.clients.closes.get, 1)
    assertEquals(keys.closes.get, 1)
    assert(stores != null)
    val before = h.clients.graph.calls.get
    assert(stores.graph.run(null).left.exists(_.message.contains("no capacity")))
    assertEquals(h.clients.graph.calls.get, before, "the closed bounds cannot call the client")
  }

  // ------------------------------------------------------------------ G4: workflow reads bounded

  test(
    "G4: the query-jobs workflow's store reads run under the read policy, bounded by readTimeout"
  ) {
    val capture = new Capture
    val h = new LauncherHarness(workflow = Some(capture.factory))
    val running = right(h.launcher.start(ServiceKind.QueryJobs))
    val stores = capture.stores
    assert(stores != null, "the workflow factory received the runtime stores")
    try {
      // A fast read passes through unchanged, on the workflow-stores pool.
      assertEquals(stores.graph.run(null), Right(Vector.empty))
      assert(h.clients.graph.threads.peek().startsWith("scholarchain-workflow-stores-"))
      // A hanging read is cut at readTimeoutMillis (300) per attempt, within
      // readRequestTimeoutMillis (1000), at most readMaxAttempts (3) attempts.
      h.clients.graph.blocking = true
      val before = h.clients.graph.calls.get
      val t0 = System.nanoTime()
      val r = stores.graph.run(null)
      val ms = (System.nanoTime() - t0) / 1000000L
      assert(r.left.exists(_.isInstanceOf[StoreError.Transient]), r.toString)
      assert(r.left.exists(_.message.contains("readTimeoutMillis")), r.toString)
      assert(ms >= 300 && ms < 2500, s"took $ms ms")
      val attempts = h.clients.graph.calls.get - before
      assert(attempts >= 2 && attempts <= 3, s"$attempts attempts")
      h.clients.graph.blocking = false
    } finally running.stop()
    assertEquals(h.clients.closes.get, 1)
    // The bounds pool was released with the process: no read runs any more.
    val after = h.clients.graph.calls.get
    assert(stores.graph.run(null).left.exists(_.message.contains("no capacity")))
    assertEquals(h.clients.graph.calls.get, after)
  }
}
