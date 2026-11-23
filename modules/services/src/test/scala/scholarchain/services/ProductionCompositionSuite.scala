package scholarchain.services

import java.util.concurrent.atomic.AtomicInteger

import scholarchain.core.config.{
  ConfigLoader,
  ProcessSettings,
  ProductionSettings,
  ScholarChainConfig,
  ServiceKindKey
}
import scholarchain.storage.aws.dynamodb.{DynamoDbCalls, DynamoDbClientSettings}
import scholarchain.storage.model.{
  BoundQuery,
  IndexInfo,
  ObjectRef,
  PrimaryKey,
  StoreError,
  TableRole,
  VectorFilter
}
import scholarchain.storage.port.{GraphReader, IndexInspector, ObjectReader, VectorReader}

/**
 * SV-INT-02: core's production configuration mapped into the services and storage settings, and the
 * production composition over injected stub clients. All values are synthetic (`.invalid` hosts,
 * all-zero account ID); no AWS call, no network, no socket.
 */
class ProductionCompositionSuite extends munit.FunSuite {
  import TestHttp.*

  // The core ProductionConfigSuite fixture (valid for every rule), restated: services cannot see
  // core's test classes.
  private val base: String =
    """configSchemaVersion = "scholarchain.config.v2"
      |environment = test
      |aws { region = "us-east-1", accountId = "000000000000" }
      |storage {
      |  rawBucket = "example-raw-bucket"
      |  curatedBucket = "example-curated-bucket"
      |  contextBucket = "example-context-bucket"
      |  vectorBucket = "example-vector-bucket"
      |  vectorIndex = "example-index"
      |  dynamoTablePrefix = "example-"
      |  neptuneEndpoint = "neptune.example.invalid"
      |  openSearchEndpoint = null
      |}
      |models { llmModelId = "example-llm", embeddingModelId = "example-embedding", embeddingDimension = 8 }
      |graph {
      |  windows = [{ startYear = 2000, endYear = 2005 }, { startYear = 2006, endYear = 2010 }]
      |  policyVersion = "graph-policy-1.0.0"
      |  identityVersion = "v1"
      |}
      |bridge { algorithm = "betweenness-sampled", sampleSize = 64, randomSeed = 7, maxIterations = 10 }
      |community { algorithm = "label-propagation", maxIterations = 20 }
      |entityResolution {
      |  policyVersion = "er-policy-1.0.0"
      |  acceptThreshold = 0.9
      |  rejectThreshold = 0.2
      |  llmBandLower = 0.4
      |  llmBandUpper = 0.7
      |  promptVersion = "er-adjudication-prompt.v1"
      |}
      |runtime { maxPathHops = 4, retrievalTopK = 10, defaultWindow { startYear = 2015, endYear = 2024 } }
      |storage.dynamodb {
      |  region = "us-east-1"
      |  tables {
      |    runLedger = "example-run-ledger"
      |    artifactRegistry = "example-artifact-registry"
      |    evidenceBundle = "example-evidence-bundle"
      |    querySession = "example-query-session"
      |    entityLinkCache = "example-entity-link-cache"
      |    researcherMetricCache = "example-researcher-metric-cache"
      |    queryOwnership = "example-query-ownership"
      |    queryJob = "example-query-job"
      |    idempotencyKey = "example-idempotency-key"
      |  }
      |  maxQueryLimit = 1000
      |  maxQueryPages = 4
      |  apiCallTimeoutMillis = 500
      |  apiCallAttemptTimeoutMillis = 200
      |  batchGetMaxAttempts = 3
      |  batchGetBackoffBaseMillis = 50
      |  batchGetBackoffMaxMillis = 200
      |}
      |storage.serviceState {
      |  writer = "query-jobs"
      |  querySessionTtlSeconds = 86400
      |  ownershipTtlSeconds = 90000
      |  jobTtlSeconds = 3600
      |  completedTtlSeconds = 900
      |  inFlightTtlSeconds = 120
      |  maxPayloadBytes = 262144
      |  maxConflictRetries = 10
      |  maxOwnedByQueryIds = 100
      |  maxClockSkewMillis = 5000
      |}
      |""".stripMargin

  private def serviceLines(kind: String, port: Int): String =
    s"""services.$kind {
       |  maxBodyBytes = 65536
       |  maxQuestionBytes = 2048
       |  syncTimeoutMillis = 30000
       |  asyncTimeoutMillis = 60000
       |  readTimeoutMillis = 2000
       |  readMaxAttempts = 3
       |  breakerFailureThreshold = 5
       |  breakerOpenMillis = 10000
       |  readRequestTimeoutMillis = 5000
       |  queueWaitMillis = 1000
       |  workflowWorkers = 4
       |  workflowQueueCapacity = 16
       |  keyValueWorkers = 4
       |  keyValueQueueCapacity = 16
       |  graphWorkers = 4
       |  graphQueueCapacity = 16
       |  jobWorkers = 4
       |  jobQueueCapacity = 16
       |  maxJobs = 100
       |  idempotencyCapacity = 1000
       |  idempotencyTtlMillis = 600000
       |  queryOwnershipCapacity = 1000
       |  graphMaxDepth = 1
       |  graphMaxPageSize = 50
       |  graphDefaultPageSize = 20
       |  graphMaxResults = 200
       |  graphEdgeTypes = [coauthored-with, authored]
       |  graphDefaultWindow { startYear = 2015, endYear = 2024 }
       |  evidenceMaxContentBytes = 4096
       |  evidenceMaxBundles = 10
       |  evidenceScanLimit = 100
       |  explainMaxSteps = 50
       |  metricsEnabled = true
       |  retryAfterSeconds = 5
       |  server { host = "127.0.0.1", port = $port, backlog = 64, workers = 8, queueCapacity = 64, maxBodyBytes = 65536, overflowCapacity = 16 }
       |  graceSeconds = 0
       |  drainMillis = 15000
       |}
       |""".stripMargin

  private val full: String =
    base + serviceLines("query-jobs", 8081) + serviceLines("evidence-explain", 8082) +
      serviceLines("graph", 8083)

  private val config: ScholarChainConfig =
    ConfigLoader
      .load(
        Map(ConfigLoader.FilesEnvVar -> "base.conf"),
        p => if (p == "base.conf") Right(full) else Left("absent")
      )
      .fold(e => throw new IllegalStateException(e.map(_.message).mkString("; ")), identity)

  private def resolved(kind: ServiceKindKey): ProcessSettings =
    ProductionSettings.forProcess(config, kind).fold(e => fail(e.map(_.message).mkString), identity)

  // ------------------------------------------------------------------ stub clients and readers

  /** The DynamoDB client factory: counts its calls, returns a stub over the configured names. */
  final class Factory {
    val calls = new AtomicInteger(0)
    @volatile var stub: DdbStub = null
    val build: DynamoDbClientSettings => Either[String, DynamoDbCalls] = s => {
      calls.incrementAndGet()
      val names = TableRole.values.map { r =>
        r -> ProductionMapping
          .dynamoDbParts(resolved(ServiceKindKey.QueryJobs).dynamoDb.get)
          .toOption
          .get
          .names
          .of(r)
          .toOption
          .get
      }.toMap
      stub = new DdbStub(names)
      Right(stub)
    }
  }

  private object StubObjects extends ObjectReader {
    def head(ref: ObjectRef) = Right(None)
    def get(ref: ObjectRef) = Right(None)
    def list(bucket: String, prefix: String, maxKeys: Int) = Right(Vector.empty)
  }
  private object StubGraph extends GraphReader {
    def run(query: BoundQuery) = Right(Vector.empty)
  }
  private object StubVectors extends VectorReader {
    def query(index: String, vector: Vector[Float], topK: Int, filter: Option[VectorFilter]) =
      Right(Vector.empty)
    def getVectors(index: String, keys: Vector[String]) = Right(Vector.empty)
  }
  private object StubIndexes extends IndexInspector {
    def describe(index: String): Either[StoreError, Option[IndexInfo]] = Right(None)
  }

  /** Every injection point provided by a stub; the workflow is the local rag workflow. */
  private def complete(f: Factory, world: ServiceWorld = new ServiceWorld) =
    ProductionDependencies(
      dynamoDb = Some(f.build),
      apiKeys = Some(new FakeKeys),
      objects = Some(StubObjects),
      graph = Some(StubGraph),
      vectors = Some(StubVectors),
      vectorIndexes = Some(StubIndexes),
      workflow = Some(_ => Right(world.ragWorkflow)),
      accessLog = new RecordingLog,
      clock = new TestClock,
      ids = new CountingIds
    )

  // ------------------------------------------------------------------ mapping

  test("ServiceProcessSettings map 1:1 into ServiceConfig, the server and the shutdown timing") {
    ServiceKindKey.values.foreach { k =>
      val p = resolved(k).service
      val c = ProductionMapping.serviceConfig(p)
      val source = p.productElementNames.zip(p.productIterator).toMap
      c.productElementNames.zip(c.productIterator).foreach { case (name, value) =>
        assertEquals(Some(value), source.get(name), s"${k.tag}.$name")
      }
      assertEquals(c.productArity, p.productArity - 1 - 7 - 2, "every ServiceConfig field mapped")
      assertEquals(ServiceConfig.problems(c), Vector.empty, k.tag)
      val s = ProductionMapping.serverSettings(p)
      assertEquals(
        s,
        HttpServerAdapter.Settings(
          p.serverHost,
          p.serverPort,
          p.serverBacklog,
          p.serverWorkers,
          p.serverQueueCapacity,
          p.serverMaxBodyBytes,
          p.serverOverflowCapacity
        )
      )
      val ss = ProductionMapping.serviceSettings(p)
      assertEquals((ss.drainMillis, ss.graceSeconds), (p.drainMillis, p.graceSeconds))
      val kind = ServiceKind.values.find(_.tag == k.tag).get
      val provider = new ProductionServiceProvider(config, ProductionDependencies())
      assertEquals(provider.settings(kind), Right(ss))
    }
    assertEquals(resolved(ServiceKindKey.Graph).service.serverPort, 8083)
    assert(ProductionMapping.keyOf(ServiceKind.All).isLeft)
  }

  test("core's derived durable-state inputs reach ServiceStateSettings.of unchanged") {
    val d = resolved(ServiceKindKey.QueryJobs).durableState.get
    val s = ProductionMapping.stateSettings(d).fold(e => fail(e), identity)
    assertEquals(s.inFlightRequirementMillis, d.inFlightRequirementMillis)
    assertEquals(s.maxClockSkewMillis, d.maxClockSkewMillis)
    assertEquals(
      (
        s.writer,
        s.ownershipTtlSeconds,
        s.jobTtlSeconds,
        s.completedTtlSeconds,
        s.inFlightTtlSeconds
      ),
      (
        d.writer,
        d.ownershipTtlSeconds,
        d.jobTtlSeconds,
        d.completedTtlSeconds,
        d.inFlightTtlSeconds
      )
    )
    assertEquals(
      (s.maxPayloadBytes, s.maxConflictRetries, s.maxOwnedByQueryIds),
      (d.maxPayloadBytes, d.maxConflictRetries, d.maxOwnedByQueryIds)
    )
    // Each of the four derived inputs is passed, not replaced: storage refuses each one at its
    // own bound (a mapping that dropped or zeroed one would accept).
    val inFlight = d.inFlightTtlSeconds * 1000L
    Vector(
      "requirement" -> d.copy(inFlightRequirementMillis = inFlight),
      "longest" -> d.copy(longestWorkflowTimeoutMillis = d.jobTtlSeconds * 1000L),
      "drain" -> d.copy(drainMillis = inFlight),
      "skew" -> d.copy(maxClockSkewMillis = inFlight)
    ).foreach { case (tag, x) =>
      assert(ProductionMapping.stateSettings(x).isLeft, s"$tag was not passed to storage")
    }
    assertEquals(d.longestWorkflowTimeoutMillis, 60000L, "max(sync, async) from core")
  }

  test("storage.dynamodb maps every table role and the adapter bounds") {
    val parts = ProductionMapping
      .dynamoDbParts(resolved(ServiceKindKey.QueryJobs).dynamoDb.get)
      .fold(e => fail(e), identity)
    assertEquals(parts.names.roles, TableRole.values.toSet)
    assertEquals(parts.names.of(TableRole.QueryJob), Right("example-query-job"))
    assertEquals(parts.names.of(TableRole.IdempotencyKey), Right("example-idempotency-key"))
    assertEquals(parts.client.region, "us-east-1")
    assertEquals(parts.client.endpointOverride, None)
    assertEquals(
      (parts.client.apiCallTimeoutMillis, parts.client.apiCallAttemptTimeoutMillis),
      (500L, 200L)
    )
    assertEquals(parts.adapter.maxQueryPages, 4)
    val dropped = resolved(ServiceKindKey.QueryJobs).dynamoDb.get
    val fewer = dropped.copy(tableNames = dropped.tableNames - TableRole.QueryJob.tag)
    assert(ProductionMapping.dynamoDbParts(fewer).isLeft, "a missing role fails closed")
  }

  test("a configuration core refuses never reaches a client factory or a runtime") {
    val bad = config.copy(serviceState = config.serviceState.copy(inFlightTtlSeconds = Some(1L)))
    val f = new Factory
    val provider = new ProductionServiceProvider(bad, complete(f))
    val s = provider.settings(ServiceKind.QueryJobs)
    assert(s.isLeft, s.toString)
    assert(s.left.toOption.get.contains("inFlightTtlSeconds"), s.toString)
    assert(provider.ports(ServiceKind.QueryJobs).isLeft)
    assert(ServiceMain.start(ServiceKind.QueryJobs, provider).isLeft)
    val errs = scala.collection.mutable.ArrayBuffer.empty[String]
    assertEquals(
      ServiceMain.launch(ServiceKind.QueryJobs, Array.empty, () => Right(provider), errs += _),
      1
    )
    assertEquals(f.calls.get, 0, "no client was built for a refused configuration")
    assert(!errs.mkString.contains("example-"), "no configured value in the refusal")
  }

  // ------------------------------------------------------------------ composition

  test("query-jobs composes from stub clients: durable state on DynamoDB, a query end to end") {
    val f = new Factory
    val provider = new ProductionServiceProvider(config, complete(f))
    val c = provider.compose(ServiceKind.QueryJobs).fold(e => fail(e), identity)
    assertEquals(f.calls.get, 1)
    assertEquals(c.ports.access, StoreAccess.concurrent)
    assert(c.ports.state.exists(_.jobs.isInstanceOf[DurableJobStore]))
    assert(c.ports.state.exists(_.idempotency.isInstanceOf[DurableIdempotencyStore]))
    assert(c.ports.ownership.isInstanceOf[DurableQueryOwnership])
    // CR-INT-01-3: one adapter with all nine names; the runtime facade refuses service-state tables.
    val runtimeKv = c.ports.stores.get.keyValues
    assert(runtimeKv.get(TableRole.QueryJob, PrimaryKey("job#x", "job")).isLeft)
    assertEquals(c.stateSettings.map(_.inFlightTtlSeconds), Some(120L))
    val rt = ServiceRuntime
      .build(
        c.ports.stores.orNull,
        c.ports.workflow.orNull,
        c.ports.keys,
        c.settings.config,
        c.ports.access,
        c.ports.accessLog,
        c.ports.clock,
        c.ports.ids,
        ServiceKind.QueryJobs,
        c.ports.ownership,
        c.ports.state.orNull
      )
      .fold(e => fail(e), identity)
    try {
      val q = ujson.Obj("question" -> "Who are the coauthors of Ada Lovelace?").render()
      val r = post(rt.router, "/v1/query", q, idempotencyKey = Some("prod-1"))
      assertEquals(r.status, 200, r.bodyText)
      assertEquals(f.stub.itemCount(TableRole.QueryOwnership), 1)
      assertEquals(f.stub.itemCount(TableRole.IdempotencyKey), 1)
      assert(
        f.stub.requests.exists(_ == "PutItem example-idempotency-key"),
        f.stub.requests.toString
      )
      val again = post(rt.router, "/v1/query", q, idempotencyKey = Some("prod-1"))
      assertEquals(again.header("Idempotent-Replayed"), Some("true"))
    } finally rt.close()
  }

  test("evidence-explain and graph compose only what they use; the rest refuses") {
    val f = new Factory
    val deps = ProductionDependencies(
      dynamoDb = Some(f.build),
      apiKeys = Some(new FakeKeys),
      graph = Some(StubGraph)
    )
    val provider = new ProductionServiceProvider(config, deps)
    val ee = provider.compose(ServiceKind.EvidenceExplain).fold(e => fail(e), identity)
    assert(ee.ports.ownership.isInstanceOf[DurableQueryOwnership])
    assertEquals(ee.ports.workflow, None)
    assertEquals(ee.ports.state, None)
    assert(ee.ports.stores.get.objects.head(ObjectRef("b", "k")).isLeft, "S3 is not composed")
    assert(
      Unwired
        .writer(ServiceKind.EvidenceExplain)
        .putIfAbsent(TableRole.QuerySession, Map.empty)
        .isLeft,
      "evidence-explain composes no runtime table writer"
    )
    val g = provider.compose(ServiceKind.Graph).fold(e => fail(e), identity)
    assertEquals(g.ports.ownership, null)
    assertEquals(g.stateSettings, None)
    assert(g.ports.stores.get.keyValues.get(TableRole.QuerySession, PrimaryKey("a", "b")).isLeft)
    assertEquals(f.calls.get, 1, "graph builds no DynamoDB client")
    // Graph needs no DynamoDB at all.
    assert(
      new ProductionServiceProvider(config, deps.copy(dynamoDb = None))
        .compose(ServiceKind.Graph)
        .isRight
    )
  }

  test("every missing production dependency fails closed, naming it; never a fake") {
    val f = new Factory
    val all = complete(f)
    val provider = (d: ProductionDependencies) => new ProductionServiceProvider(config, d)
    val queryJobs = Vector(
      "dynamoDb" -> all.copy(dynamoDb = None),
      "apiKeys" -> all.copy(apiKeys = None),
      "objects" -> all.copy(objects = None),
      "graph" -> all.copy(graph = None),
      "vectors" -> all.copy(vectors = None),
      "vectorIndexes" -> all.copy(vectorIndexes = None),
      "workflow" -> all.copy(workflow = None)
    )
    queryJobs.foreach { case (name, d) =>
      val r = provider(d).compose(ServiceKind.QueryJobs)
      assert(r.isLeft, s"$name missing was accepted")
      assert(r.left.toOption.get.startsWith(name), r.toString)
      assert(r.left.toOption.get.contains("not provided"), r.toString)
      assert(ServiceMain.start(ServiceKind.QueryJobs, provider(d)).isLeft)
    }
    Vector("dynamoDb" -> all.copy(dynamoDb = None), "apiKeys" -> all.copy(apiKeys = None))
      .foreach { case (name, d) =>
        assert(provider(d).compose(ServiceKind.EvidenceExplain).left.exists(_.startsWith(name)))
      }
    Vector("graph" -> all.copy(graph = None), "apiKeys" -> all.copy(apiKeys = None)).foreach {
      case (name, d) =>
        assert(provider(d).compose(ServiceKind.Graph).left.exists(_.startsWith(name)))
    }
    // An in-memory store of storage is refused as a production dependency.
    val fake = all.copy(graph = Some(new scholarchain.storage.fake.InMemoryGraphStore))
    assert(
      provider(fake).compose(ServiceKind.Graph).left.exists(_.contains("in-memory fake")),
      "a fake graph store was accepted"
    )
    // A factory failure and a non-deployable kind are refusals too.
    val broken = all.copy(dynamoDb = Some(_ => Left("dynamoDb: no credentials (stub)")))
    assert(provider(broken).compose(ServiceKind.QueryJobs).isLeft)
    assert(provider(all).compose(ServiceKind.All).isLeft)
  }

  // ------------------------------------------------------------------ CR-INT-02 (A1-W) budgets

  test("composite attempt budgets: the worst case per operation, and the ones over readTimeout") {
    val c = new ProductionServiceProvider(config, complete(new Factory))
      .compose(ServiceKind.QueryJobs)
      .fold(e => fail(e), identity)
    val b = c.attemptBudgets.get
    // apiCall 500 ms, R = 10: begin 4 x 11 = 44 calls; job transitions 2 x 11 = 22 calls.
    assertEquals(b.sdkCalls("idempotency.begin"), 44L)
    assertEquals(b.millis("idempotency.begin"), 22000L)
    assertEquals(b.millis("ownership.record"), 1500L)
    assertEquals(b.millis("idempotency.complete"), 1500L)
    assertEquals(b.millis("idempotency.abandon"), 1000L)
    assertEquals(b.millis("job.start"), 11000L)
    assertEquals(
      b.exceeding(c.settings.config.readTimeoutMillis),
      Vector("idempotency.begin", "job.fail", "job.start", "job.succeed")
    )
    assertEquals(CompositeAttemptBudgets.of(Long.MaxValue, 100).millis("job.remove"), Long.MaxValue)
  }
}
