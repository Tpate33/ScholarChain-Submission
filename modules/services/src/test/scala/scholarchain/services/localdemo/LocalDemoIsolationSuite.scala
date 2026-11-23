package scholarchain.services.localdemo

import scholarchain.services.*
import scholarchain.storage.model.{BoundQuery, GraphRow, StoreError}
import scholarchain.storage.port.{GraphReader, RuntimeStores}

/** A graph reader defined in the local-demo package (stands in for any demo object). */
object DemoPackageGraph extends GraphReader {
  def run(query: BoundQuery): Either[StoreError, Vector[GraphRow]] = Right(Vector.empty)
}

/** A workflow factory defined in the local-demo package. */
object DemoPackageWorkflow {
  val factory: RuntimeStores => Either[String, QueryWorkflow] =
    _ => Right(new ServiceWorld().ragWorkflow)
}

/**
 * SV-INT-03 (hardening flagged by SV-INT-03a): the production provider and launcher refuse every
 * `scholarchain.services.localdemo.*` object, so the NON-PRODUCTION demo can never become a
 * production dependency, even when injected explicitly.
 */
class LocalDemoIsolationSuite extends munit.FunSuite {

  private val config = LauncherFixture.config()
  private def clients = new StubClients(LauncherFixture.tableNames())

  private def demoRefusal(r: Either[String, ?], what: String): Unit = {
    assert(r.isLeft, s"$what was accepted: $r")
    assert(r.left.exists(_.startsWith(what)), r.toString)
    assert(r.left.exists(_.contains("NON-PRODUCTION local-demo object")), r.toString)
  }

  test("the production provider refuses the demo key source, a demo store and a demo workflow") {
    val demoKeys = LocalDemoKeys.load().fold(e => fail(e), identity)
    val c = clients
    val ok = ProductionDependencies(
      dynamoDb = Some(
        c.dynamoDbFactory(scholarchain.storage.aws.RetrySetting.of(3).toOption.get).toOption.get
      ),
      apiKeys = Some(new FakeKeys),
      objects = Some(StubObjects),
      graph = Some(c.graph),
      vectors = Some(StubVectorsAndIndexes),
      vectorIndexes = Some(StubVectorsAndIndexes),
      // Defined in scholarchain.services (a lambda written here would be a local-demo object).
      workflow = Some(TestWorkflows.factory)
    )
    val provider = (d: ProductionDependencies) => new ProductionServiceProvider(config, d)
    // Control: the same composition without demo objects is accepted.
    assert(provider(ok).compose(ServiceKind.QueryJobs).isRight)
    assert(provider(ok).compose(ServiceKind.Graph).isRight)
    demoRefusal(provider(ok.copy(apiKeys = Some(demoKeys))).compose(ServiceKind.Graph), "apiKeys")
    demoRefusal(
      provider(ok.copy(graph = Some(DemoPackageGraph))).compose(ServiceKind.Graph),
      "graph"
    )
    demoRefusal(
      provider(ok.copy(workflow = Some(DemoPackageWorkflow.factory)))
        .compose(ServiceKind.QueryJobs),
      "workflow"
    )
    // start refuses as compose does.
    assert(provider(ok.copy(apiKeys = Some(demoKeys))).start(ServiceKind.Graph).isLeft)
  }

  test("the production launcher refuses a demo key source and closes the bundle once") {
    val demoKeys = LocalDemoKeys.load().fold(e => fail(e), identity)
    val h = new LauncherHarness(keys = _ => Right(demoKeys))
    val r = h.launcher.start(ServiceKind.Graph)
    demoRefusal(r, "apiKeys")
    assertEquals(h.clients.closes.get, 1)
  }
}
