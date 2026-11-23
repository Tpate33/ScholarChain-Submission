package scholarchain.core.config

import scholarchain.core.{Canonical, TimeWindow}

/**
 * DE-INT-01: production configuration contracts (`storage.dynamodb.*`, `storage.serviceState.*`,
 * `services.<kind>.*`). All values are synthetic (`.invalid` hosts, all-zero account ID).
 */
class ProductionConfigSuite extends munit.FunSuite {

  /** The ConfigLoaderSuite base file, unchanged (no production key). */
  val base: String =
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
      |""".stripMargin

  val storageLines: String =
    """storage.dynamodb {
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

  def serviceLines(kind: String): String =
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
       |  server { host = "0.0.0.0", port = 8080, backlog = 64, workers = 8, queueCapacity = 64, maxBodyBytes = 65536, overflowCapacity = 16 }
       |  graceSeconds = 10
       |  drainMillis = 15000
       |}
       |""".stripMargin

  val full: String =
    base + storageLines + serviceLines("query-jobs") + serviceLines("evidence-explain") +
      serviceLines("graph")

  private val filesEnv = Map(ConfigLoader.FilesEnvVar -> "base.conf")
  private def load(text: String, env: Map[String, String] = Map.empty) =
    ConfigLoader.load(filesEnv ++ env, p => if (p == "base.conf") Right(text) else Left("absent"))
  private def loadOk(text: String, env: Map[String, String] = Map.empty): ScholarChainConfig =
    load(text, env).fold(e => fail(e.map(_.message).mkString("; ")), identity)
  private def invalidKeys(r: Either[Vector[ConfigError], ScholarChainConfig]): Set[String] =
    r.left.getOrElse(Vector.empty).collect { case ConfigError.InvalidValue(k, _, _) => k }.toSet
  private def reasons(r: Either[Vector[ConfigError], ScholarChainConfig]): Vector[String] =
    r.left.getOrElse(Vector.empty).collect { case ConfigError.InvalidValue(_, _, why) => why }
  private def env(key: String, v: String) = Map(ConfigKeys.envNameOf(key) -> v)

  private val Q = "services.query-jobs."
  private val E = "services.evidence-explain."
  private val G = "services.graph."
  private val D = "storage.dynamodb."
  private val S = "storage.serviceState."

  // ------------------------------------------------------------------------ valid and complete

  test("a full production configuration loads, and every production key is set exactly once") {
    val c = loadOk(full)
    val flat = ProductionConfig.flatten(c)
    assertEquals(
      flat.keySet,
      ProductionKeys.all.map(_.path).toSet - ProductionKeys.EndpointOverrideKey
    )
    assertEquals(ProductionKeys.all.size, 18 + 10 + 3 * 43, "frozen key count")
    assertEquals(ProductionKeys.all.map(_.path).distinct.size, ProductionKeys.all.size)
    assertEquals(ProductionConfig.problems(c), Vector.empty)
    assertEquals(c.services.queryJobs.graphEdgeTypes, Some(Vector("coauthored-with", "authored")))
    assertEquals(c.services.graph.graphDefaultWindow, Some(TimeWindow(2015, 2024)))
    assertEquals(c.services.evidenceExplain.metricsEnabled, Some(true))
    assertEquals(c.dynamoDb.tables.queryJob, Some("example-query-job"))
  }

  test("forProcess resolves typed settings for each process, with derived timing budgets") {
    val c = loadOk(full)
    val q = ProductionSettings
      .forProcess(c, ServiceKindKey.QueryJobs)
      .fold(e => fail(e.map(_.message).mkString("; ")), identity)
    assertEquals(q.service.syncTimeoutMillis, 30000L)
    assertEquals(q.service.serverPort, 8080)
    assertEquals(q.service.graphEdgeTypes, Vector("coauthored-with", "authored"))
    val d = q.dynamoDb.get
    assertEquals(d.region, "us-east-1")
    assertEquals(d.endpointOverride, None)
    assertEquals(d.tableNames.size, 9)
    assertEquals(d.tableNames("IdempotencyKey"), "example-idempotency-key")
    val st = q.durableState.get
    assertEquals(st.longestWorkflowTimeoutMillis, 60000L)
    assertEquals(st.drainMillis, 15000L)
    // 5000 (begin) + 1000 + max(30000, 60000) + 5000 (complete, CR-INT-04-2) + 15000 + 5000
    assertEquals(st.inFlightRequirementMillis, 91000L)
    // hold = 2*5000 + 1000 + 60000 = 71000; gap = max(ceil(16/4)*71000 + 5000, 66000) = 289000
    assertEquals(st.jobTtlRequirementMillis, 289000L + 15000 + 5000 + 1000)
    assertEquals(st.ownershipTtlRequirementMillis, 86400000L + 60000 + 5000 + 1000)
    assertEquals(st.completedTtlRequirementMillis, 600000L + 5000 + 1000)
    val g = ProductionSettings.forProcess(c, ServiceKindKey.Graph).toOption.get
    assertEquals(g.dynamoDb, None)
    assertEquals(g.durableState, None)
    assert(ProductionSettings.forProcess(c, ServiceKindKey.EvidenceExplain).isRight)
  }

  test("the endpoint override is optional; https or loopback http is accepted") {
    for (
      ok <- Vector(
        "https://dynamodb.example.invalid",
        "http://localhost:8000",
        "http://127.0.0.1:8000"
      )
    )
      assertEquals(
        loadOk(full, env(ProductionKeys.EndpointOverrideKey, ok)).dynamoDb.endpointOverride,
        Some(ok)
      )
  }

  test("environment overrides reach every production kind, with the documented env names") {
    assertEquals(
      ConfigKeys.envNameOf(Q + "syncTimeoutMillis"),
      "SCHOLARCHAIN_CFG_SERVICES__QUERY_JOBS__SYNC_TIMEOUT_MILLIS"
    )
    assertEquals(
      ConfigKeys.envNameOf(E + "server.port"),
      "SCHOLARCHAIN_CFG_SERVICES__EVIDENCE_EXPLAIN__SERVER__PORT"
    )
    assertEquals(
      ConfigKeys.envNameOf(D + "tables.runLedger"),
      "SCHOLARCHAIN_CFG_STORAGE__DYNAMODB__TABLES__RUN_LEDGER"
    )
    assertEquals(ConfigKeys.byEnv.size, ConfigKeys.all.size, "env names stay unique")
    val c = loadOk(
      full,
      env(G + "graphEdgeTypes", "affiliated-with") ++ env(G + "graphDefaultWindow", "2001-2002") ++
        env(G + "metricsEnabled", "false") ++ env(G + "server.port", "0") ++
        env(S + "writer", "query-jobs-2")
    )
    assertEquals(c.services.graph.graphEdgeTypes, Some(Vector("affiliated-with")))
    assertEquals(c.services.graph.graphDefaultWindow, Some(TimeWindow(2001, 2002)))
    assertEquals(c.services.graph.metricsEnabled, Some(false))
    assertEquals(c.services.graph.server.port, Some(0))
    assertEquals(c.serviceState.writer, Some("query-jobs-2"))
    // An empty environment value unsets a production key, which a process then requires.
    val unset = loadOk(full, env(G + "drainMillis", ""))
    assertEquals(unset.services.graph.drainMillis, None)
    assertEquals(
      ProductionSettings.forProcess(unset, ServiceKindKey.Graph).left.map(_.toSet),
      Left(Set(ConfigError.MissingKey(G + "drainMillis", ConfigKeys.envNameOf(G + "drainMillis"))))
    )
  }

  // ------------------------------------------------------------------------ compatibility

  test("compatibility: a config without production keys loads unchanged and keeps its digest") {
    val plain = loadOk(base)
    assertEquals(plain.dynamoDb, DynamoDbConfig())
    assertEquals(plain.serviceState, ServiceStateConfig())
    assertEquals(plain.services, ServicesConfig())
    val json = upickle.default.write(plain)
    assert(
      !json.contains("dynamoDb") && !json.contains("serviceState") && !json.contains("services")
    )
    val withNulls =
      loadOk(base + "services.graph.drainMillis = null\nstorage.dynamodb.region = null\n")
    assertEquals(ScholarChainConfig.digest(withNulls), ScholarChainConfig.digest(plain))
    // Production keys change the full digest but never a pipeline-stage digest.
    val prod = loadOk(full)
    assertNotEquals(ScholarChainConfig.digest(prod), ScholarChainConfig.digest(plain))
    assertEquals(
      ScholarChainConfig.digestWithoutGrouping(prod),
      ScholarChainConfig.digestWithoutGrouping(plain)
    )
    assertEquals(ScholarChainConfig.digestWithoutGrouping(plain), ScholarChainConfig.digest(plain))
    assertEquals(ScholarChainConfig.withoutProduction(prod), plain)
  }

  test("a process refuses a configuration without its section, naming every missing key") {
    val plain = loadOk(base)
    for (k <- ServiceKindKey.values) {
      val r = ProductionSettings.forProcess(plain, k)
      val missing =
        r.left.getOrElse(Vector.empty).collect { case ConfigError.MissingKey(key, _) => key }
      assertEquals(missing.toSet, ProductionSettings.requiredKeys(k).toSet, k.tag)
    }
    assertEquals(ProductionSettings.requiredKeys(ServiceKindKey.Graph).size, 43)
    assert(
      !ProductionSettings
        .requiredKeys(ServiceKindKey.QueryJobs)
        .contains(ProductionKeys.EndpointOverrideKey)
    )
    // Graph alone needs only its own section.
    assert(
      ProductionSettings
        .forProcess(loadOk(base + serviceLines("graph")), ServiceKindKey.Graph)
        .isRight
    )
    // Evidence-explain needs the query-jobs timings its durable-state rules are checked against.
    val noQueryJobs = loadOk(base + storageLines + serviceLines("evidence-explain"))
    assert(ProductionSettings.forProcess(noQueryJobs, ServiceKindKey.EvidenceExplain).isLeft)
    assert(ProductionSettings.dynamoDbSettings(noQueryJobs).isRight)
    assert(ProductionSettings.durableStateSettings(noQueryJobs).isLeft)
    assert(ProductionSettings.serviceSettings(noQueryJobs, ServiceKindKey.EvidenceExplain).isRight)
    // CR-INT-04-3 (deliberate change from DE-INT-01, which required all 43 query-jobs keys): only
    // the eight timing inputs of the durable-state settings and rules are required.
    val r = ProductionSettings.forProcess(noQueryJobs, ServiceKindKey.EvidenceExplain)
    assertEquals(
      r.left.getOrElse(Vector.empty).collect { case ConfigError.MissingKey(k, _) => k }.toSet,
      ProductionSettings.QueryJobsStateInputs.toSet
    )
  }

  // ------------------------------------------------------------------------ DE-INT-02

  /** Only the eight query-jobs timing inputs (CR-INT-04-3), with the `full` values. */
  val queryJobsTimingLines: String =
    """services.query-jobs {
      |  syncTimeoutMillis = 30000
      |  asyncTimeoutMillis = 60000
      |  readRequestTimeoutMillis = 5000
      |  queueWaitMillis = 1000
      |  drainMillis = 15000
      |  jobQueueCapacity = 16
      |  jobWorkers = 4
      |  idempotencyTtlMillis = 600000
      |}
      |""".stripMargin

  test("CR-INT-04-3: evidence-explain requires exactly the eight query-jobs timing inputs") {
    assertEquals(
      ProductionSettings.QueryJobsStateInputs,
      Vector(
        "syncTimeoutMillis",
        "asyncTimeoutMillis",
        "readRequestTimeoutMillis",
        "queueWaitMillis",
        "drainMillis",
        "jobQueueCapacity",
        "jobWorkers",
        "idempotencyTtlMillis"
      ).map(Q + _)
    )
    val required = ProductionSettings.requiredKeys(ServiceKindKey.EvidenceExplain)
    assertEquals(required.size, 43 + 17 + 10 + 8)
    assertEquals(
      required.filter(_.startsWith(Q)).toSet,
      ProductionSettings.QueryJobsStateInputs.toSet
    )
    // Every key a durable-state rule reads from query-jobs is among them (nothing else needed).
    val stRuleQueryKeys =
      ProductionRules.serviceState.flatMap(_.keys).filter(_.startsWith(Q)).toSet
    assertEquals(stRuleQueryKeys, ProductionSettings.QueryJobsStateInputs.toSet)
    // Valid side: the eight inputs alone resolve, with the same derived budgets as `full`.
    val minimal =
      loadOk(base + storageLines + serviceLines("evidence-explain") + queryJobsTimingLines)
    val e = ProductionSettings
      .forProcess(minimal, ServiceKindKey.EvidenceExplain)
      .fold(err => fail(err.map(_.message).mkString("; ")), identity)
    val fromFull =
      ProductionSettings.forProcess(loadOk(full), ServiceKindKey.EvidenceExplain).toOption.get
    assertEquals(e.durableState, fromFull.durableState)
    assert(ProductionSettings.durableStateSettings(minimal).isRight)
    // The reviewer's input: no services.query-jobs.server.port. Evidence-explain resolves; the
    // query-jobs process itself still refuses.
    val noPort = loadOk(full, env(Q + "server.port", ""))
    assert(ProductionSettings.forProcess(noPort, ServiceKindKey.EvidenceExplain).isRight)
    assertEquals(
      ProductionSettings.forProcess(noPort, ServiceKindKey.QueryJobs).left.map(_.toSet),
      Left(Set(ConfigError.MissingKey(Q + "server.port", ConfigKeys.envNameOf(Q + "server.port"))))
    )
    // Invalid side: dropping any one of the eight is a MissingKey for exactly that key.
    for (k <- ProductionSettings.QueryJobsStateInputs) {
      val c = loadOk(
        base + storageLines + serviceLines("evidence-explain") + queryJobsTimingLines,
        env(k, "")
      )
      assertEquals(
        ProductionSettings.forProcess(c, ServiceKindKey.EvidenceExplain).left.map(_.toSet),
        Left(Set(ConfigError.MissingKey(k, ConfigKeys.envNameOf(k)))),
        k
      )
      assert(ProductionSettings.durableStateSettings(c).isLeft, k)
    }
  }

  test(
    "CR-INT-04-1: the job TTL also exceeds max(sync, async), as storage's constructor requires"
  ) {
    // The reviewer's input; inFlight raised from 107 to 112 so that only ST-3 decides
    // (ST-2 now needs 5000 + 1000 + 100000 + 5000 + 0 + 0 = 111000).
    val reviewer =
      env(Q + "syncTimeoutMillis", "100000") ++ env(Q + "asyncTimeoutMillis", "10000") ++
        env(Q + "jobQueueCapacity", "1") ++ env(Q + "drainMillis", "0") ++
        env(S + "maxClockSkewMillis", "0") ++ env(Q + "idempotencyTtlMillis", "200000")
    // The margin alone is 27000 (gap 26000 + 1000); storage needs > 100000.
    assertEquals(
      TimingBudgets.jobTtlRequirementMillis(
        TimingBudgets.jobGapMillis(5000, 1000, 10000, 1, 4),
        0,
        0,
        100000
      ),
      100000L
    )
    val inFlight = env(S + "inFlightTtlSeconds", "112")
    for (ttl <- Vector("28", "100"))
      assertEquals(
        ruleIds(load(full, reviewer ++ inFlight ++ env(S + "jobTtlSeconds", ttl))),
        Set("ST-3"),
        ttl
      )
    val ok = loadOk(full, reviewer ++ inFlight ++ env(S + "jobTtlSeconds", "101"))
    val st =
      ProductionSettings.forProcess(ok, ServiceKindKey.QueryJobs).toOption.get.durableState.get
    assertEquals(st.jobTtlRequirementMillis, 100000L)
    assertEquals(st.longestWorkflowTimeoutMillis, 100000L)
    assert(st.jobTtlSeconds * 1000 > st.longestWorkflowTimeoutMillis)
    // The reviewer's exact input (inFlight 107) is refused, now by ST-2 as well.
    assertEquals(
      ruleIds(
        load(
          full,
          reviewer ++ env(S + "inFlightTtlSeconds", "107") ++ env(S + "jobTtlSeconds", "28")
        )
      ),
      Set("ST-2", "ST-3")
    )
  }

  test(
    "CR-INT-04-2: the in-flight requirement includes the closing write (readRequestTimeoutMillis)"
  ) {
    assertEquals(
      TimingBudgets.inFlightRequirementMillis(5000, 1000, 30000, 60000, 15000, 5000),
      5000L + 1000 + 60000 + 5000 + 15000 + 5000
    )
    // full: 91000 required (strict); 91 s refused, 92 s accepted.
    assertEquals(ruleIds(load(full, env(S + "inFlightTtlSeconds", "91"))), Set("ST-2"))
    assert(load(full, env(S + "inFlightTtlSeconds", "92")).isRight)
    // The request timeout counts twice: 6000 gives 93000.
    val rr = env(Q + "readRequestTimeoutMillis", "6000")
    assertEquals(ruleIds(load(full, rr ++ env(S + "inFlightTtlSeconds", "93"))), Set("ST-2"))
    assert(load(full, rr ++ env(S + "inFlightTtlSeconds", "94")).isRight)
  }

  test("CR-INT-04-4(i): ownedBy is ceil(evidenceScanLimit / 100) BatchGetItem chunks") {
    assertEquals(ProductionBounds.BatchGetKeysPerCall, 100)
    assertEquals(TimingBudgets.ownedByBudgetMillis(250, 1650), 3 * 1650L)
    assertEquals(TimingBudgets.ownedByBudgetMillis(100, 1650), 1650L)
    assertEquals(TimingBudgets.ownedByBudgetMillis(101, 1650), 2 * 1650L)
    assertEquals(
      TimingBudgets.ownedByBudgetMillis(Int.MaxValue.toLong, Long.MaxValue),
      Long.MaxValue
    )
    // The reviewer's input (one chunk is 1650 ms; three are 4950 ms > 2000 ms).
    val scan =
      (n: Int) => env(E + "evidenceScanLimit", n.toString) ++ env(S + "maxOwnedByQueryIds", "1000")
    assertEquals(
      ruleIds(
        load(full, env(E + "evidenceScanLimit", "250") ++ env(S + "maxOwnedByQueryIds", "250"))
      ),
      Set("X-5")
    )
    // Exact boundaries at readTimeout 2000: 100 IDs (one chunk) accepted, 101 refused.
    assert(load(full, scan(100)).isRight)
    assertEquals(ruleIds(load(full, scan(101))), Set("X-5"))
    // At readTimeout 4950: three chunks fit (250 and 300 IDs), four (301) do not; 4949 refuses 250.
    val rt = (ms: Int) => env(E + "readTimeoutMillis", ms.toString)
    assert(load(full, scan(250) ++ rt(4950)).isRight)
    assert(load(full, scan(300) ++ rt(4950)).isRight)
    assertEquals(ruleIds(load(full, scan(301) ++ rt(4950))), Set("X-5"))
    assertEquals(ruleIds(load(full, scan(250) ++ rt(4949))), Set("X-5"))
  }

  test("CR-INT-04-4(ii): one evidence-explain Query attempt is maxQueryPages SDK calls") {
    assertEquals(TimingBudgets.queryAttemptBudgetMillis(10, 500), 5000L)
    assertEquals(
      TimingBudgets.queryAttemptBudgetMillis(Int.MaxValue.toLong, Long.MaxValue),
      Long.MaxValue
    )
    val pages = (n: Int) => env(D + "maxQueryPages", n.toString)
    // The reviewer's input: 10 pages x 500 ms against readTimeout 2000.
    assertEquals(ruleIds(load(full, pages(10))), Set("X-4[evidence-explain]"))
    // Exact boundary: 4 x 500 = 2000 accepted, 5 x 500 refused.
    assert(load(full, pages(4)).isRight)
    assertEquals(ruleIds(load(full, pages(5))), Set("X-4[evidence-explain]"))
    // 10 pages fit an evidence readTimeout of 5000 (and 4999 does not). query-jobs runs no
    // key-value Query, so its readTimeout of 2000 still only bounds one SDK call.
    assert(load(full, pages(10) ++ env(E + "readTimeoutMillis", "5000")).isRight)
    assertEquals(
      ruleIds(load(full, pages(10) ++ env(E + "readTimeoutMillis", "4999"))),
      Set("X-4[evidence-explain]")
    )
    // X-4[query-jobs] is checked on its own: apiCall 2001 against its readTimeout 2000 is refused,
    // while evidence-explain (1 page, readTimeout 7000; X-5 needs 3 x 2001 + 150 = 6153) passes.
    // DE-INT-03 (deliberate change, CR-INT-05-1): the passing query-jobs readTimeout is now
    // 3 x 2001 = 6003 (was 2001, one SDK call), with readRequestTimeout raised to keep SVC-4.
    val oneCall = env(D + "apiCallTimeoutMillis", "2001") ++ pages(1) ++
      env(E + "readTimeoutMillis", "7000") ++ env(E + "readRequestTimeoutMillis", "7000")
    assertEquals(ruleIds(load(full, oneCall)), Set("X-4[query-jobs]"))
    val qrr = env(Q + "readRequestTimeoutMillis", "6003")
    assert(load(full, oneCall ++ qrr ++ env(Q + "readTimeoutMillis", "6003")).isRight)
  }

  test("CR-INT-05-1: X-4[query-jobs] bounds one ownership-record attempt of 3 SDK calls") {
    assertEquals(ProductionBounds.OwnershipRecordSdkCalls, 3)
    assertEquals(TimingBudgets.ownershipRecordAttemptBudgetMillis(2000), 6000L)
    assertEquals(TimingBudgets.ownershipRecordAttemptBudgetMillis(0), 0L)
    assertEquals(TimingBudgets.ownershipRecordAttemptBudgetMillis(Long.MaxValue), Long.MaxValue)
    // Keeps evidence-explain's own rules (X-4[evidence-explain], X-5) out of the way, so that
    // each assertion below is decided by X-4[query-jobs] alone.
    val evidenceRoom = env(D + "maxQueryPages", "1") ++ env(E + "readTimeoutMillis", "7000") ++
      env(E + "readRequestTimeoutMillis", "7000")
    val api = (ms: Int) => env(D + "apiCallTimeoutMillis", ms.toString)
    val qrt = (ms: Int) => env(Q + "readTimeoutMillis", ms.toString)
    // The reviewer's failure input: apiCall = Q.readTimeout = 2000 (DE-INT-02 accepted it).
    assertEquals(
      ruleIds(load(full, evidenceRoom ++ api(2000) ++ qrt(2000))),
      Set("X-4[query-jobs]")
    )
    // Exact boundary in readTimeout at apiCall 2000: 6000 accepted, 5999 refused.
    val qrr = env(Q + "readRequestTimeoutMillis", "6000")
    assert(load(full, evidenceRoom ++ api(2000) ++ qrr ++ qrt(6000)).isRight)
    assertEquals(
      ruleIds(load(full, evidenceRoom ++ api(2000) ++ qrr ++ qrt(5999))),
      Set("X-4[query-jobs]")
    )
    // Exact boundary in apiCall at the fixture's readTimeout 2000: 666 (1998) accepted, 667
    // (2001) refused; 667 was accepted by the one-call rule.
    assert(load(full, evidenceRoom ++ api(666)).isRight)
    assertEquals(ruleIds(load(full, evidenceRoom ++ api(667))), Set("X-4[query-jobs]"))
    // Overflow: the budget saturates instead of wrapping to a negative value that would pass.
    val x4 = ProductionRules.cross.find(_.id == "X-4[query-jobs]").get
    val m = Map[String, Any](
      D + "apiCallTimeoutMillis" -> (Long.MaxValue / 2),
      Q + "readTimeoutMillis" -> (Long.MaxValue - 1)
    )
    assert(!x4.check(m))
    assert(x4.check(m ++ Map(D + "apiCallTimeoutMillis" -> ((Long.MaxValue - 1) / 3))))
  }

  test("CR-INT-04-4(iii): the BatchGetItem backoff mirrors storage, capped from retry 31") {
    import TimingBudgets.{batchGetBackoffMillis => b, batchGetChunkBudgetMillis => chunk}
    val max = 100000000000L
    assertEquals(b(1, 1, max), 1L)
    assertEquals(b(30, 1, max), 1L << 29)
    assertEquals(b(31, 1, max), max, "factor capped: Long.MaxValue, so min(raw, max) = max")
    assertEquals(b(64, 1, max), max)
    assertEquals(b(31, 0, max), 0L, "base 0 never sleeps")
    assertEquals(b(30, 0, max), 0L)
    assertEquals(b(3, 50, 200), 200L)
    val big = ProductionBounds.MaxDurationMillis
    assertEquals(b(30, big, big), big, "factor > Long.MaxValue / base saturates before the min")
    // The reviewer's input: 33 attempts, base 1, max 1e11, apiCall 1000.
    // 33 x 1000 + (2^30 - 1) for retries 1..30 + 2 x 1e11 for retries 31 and 32.
    assertEquals(chunk(33, 1000, 1, max), 33000L + ((1L << 30) - 1) + 2 * max)
    assertEquals(chunk(32, 1000, 1, max), 32000L + ((1L << 30) - 1) + max)
    assertEquals(chunk(31, 1000, 1, max), 31000L + ((1L << 30) - 1))
    // Retries 31.. at base 0 add nothing; the sum saturates.
    assertEquals(chunk(100, 10, 0, max), 1000L)
    assertEquals(chunk(Int.MaxValue.toLong, 1, 1, big), Long.MaxValue)
    // The rule: E.readTimeout 5e9 accepts 31 attempts, refuses 32 and 33 (DE-INT-01 accepted both).
    val x5 = ProductionRules.cross.find(_.id == "X-5").get
    val m0 = ProductionConfig.flatten(loadOk(full)) ++ Map[String, Any](
      D + "apiCallTimeoutMillis" -> 1000L,
      D + "batchGetBackoffBaseMillis" -> 1L,
      D + "batchGetBackoffMaxMillis" -> max,
      E + "readTimeoutMillis" -> 5000000000L
    )
    def at(attempts: Int, rt: Long = 5000000000L) =
      x5.check(m0 ++ Map(D + "batchGetMaxAttempts" -> attempts, E + "readTimeoutMillis" -> rt))
    assert(at(31))
    assert(!at(32))
    assert(!at(33))
    // Exact boundary of the reviewer's case.
    val need = 33000L + ((1L << 30) - 1) + 2 * max
    assert(at(33, need))
    assert(!at(33, need - 1))
  }

  test(
    "CR-INT-04-6: server.maxBodyBytes is capped below the reader's overflow and >= maxBodyBytes"
  ) {
    assertEquals(ProductionBounds.MaxServerBodyBytes, 2147483638)
    for (p <- Vector(Q, E, G)) {
      val t = p.stripPrefix("services.").stripSuffix(".")
      val key = p + "server.maxBodyBytes"
      // (a) the reviewer's input and the values up to it are refused by the key's own bound.
      for (bad <- Vector("2147483647", "2147483646", "2147483639")) {
        val r = load(full, env(key, bad))
        assertEquals(invalidKeys(r), Set(key), s"$key = $bad")
        assertEquals(reasons(r), Vector("must be an integer in [1, 2147483638]"))
      }
      assert(load(full, env(key, "2147483638")).isRight, s"$key at the cap")
      // (b) the reviewer's input, and the exact boundary against maxBodyBytes = 65536.
      assertEquals(ruleIds(load(full, env(key, "1000"))), Set(s"SRV-1[$t]"))
      assertEquals(ruleIds(load(full, env(key, "65535"))), Set(s"SRV-1[$t]"))
      assert(load(full, env(key, "65536")).isRight)
      assertEquals(ruleIds(load(full, env(p + "maxBodyBytes", "65537"))), Set(s"SRV-1[$t]"))
    }
  }

  // ------------------------------------------------------------------------ individual bounds

  test("every Range key refuses min - 1 and max + 1 (or a non-integer), naming only that key") {
    for (spec <- ProductionKeys.all) spec.bound match {
      case KeyBound.Range(min, max) =>
        for (bad <- Vector(BigInt(min) - 1, BigInt(max) + 1)) {
          val r = load(full, env(spec.path, bad.toString))
          assertEquals(invalidKeys(r), Set(spec.path), s"${spec.path} = $bad")
          // The reason is the bound or the type, never the value.
          assert(
            reasons(r).forall(why =>
              why == s"must be an integer in [$min, $max]" || why.startsWith("expected a ")
            ),
            s"${spec.path}: ${reasons(r)}"
          )
        }
        val atMin = load(full, env(spec.path, min.toString))
        // At the bound the key itself is valid (a cross-field rule may still refuse it).
        assert(
          !invalidKeys(atMin).contains(spec.path) || atMin.left.exists(_.exists {
            case ConfigError.InvalidValue(k, src, _) =>
              k == spec.path && src == "resolved configuration"
            case _ => false
          }),
          s"${spec.path} at its minimum"
        )
        assert(invalidKeys(load(full, env(spec.path, "1.5"))).contains(spec.path))
      case _ => ()
    }
  }

  test("Long.MaxValue and Long.MinValue are refused for every 64-bit key without overflow") {
    for (spec <- ProductionKeys.all if spec.kind == ValueKind.OptionalInt64)
      for (v <- Vector(Long.MaxValue, Long.MinValue)) {
        assertEquals(invalidKeys(load(full, env(spec.path, v.toString))), Set(spec.path), spec.path)
        val file = full + s"${spec.path} = $v\n"
        assertEquals(invalidKeys(load(file)), Set(spec.path), s"file ${spec.path}")
      }
    // Beyond the 64-bit range in a file: a typed error, not a wrap.
    assertEquals(
      invalidKeys(load(full + s"${Q}drainMillis = 9223372036854775808\n")),
      Set(Q + "drainMillis")
    )
  }

  test("string, list, window and boolean bounds; reasons never echo the value") {
    val cases = Vector(
      D + "region" -> "Not-A-Region-SECRETX",
      ProductionKeys.EndpointOverrideKey -> "https://user:SECRETX@dynamodb.example.invalid",
      ProductionKeys.EndpointOverrideKey -> "http://dynamodb.example.invalid/SECRETX",
      ProductionKeys.EndpointOverrideKey -> "https://dynamodb.example.invalid/?q=SECRETX",
      ProductionKeys.EndpointOverrideKey -> "ftp://SECRETX.invalid",
      D + "tables.queryJob" -> "no",
      D + "tables.queryJob" -> "has space SECRETX",
      S + "writer" -> "has space SECRETX",
      S + "writer" -> ("w" * 129),
      Q + "server.host" -> "has space SECRETX",
      Q + "graphEdgeTypes" -> "coauthored-with,unknown-SECRETX",
      Q + "graphDefaultWindow" -> "2010-2005",
      Q + "graphDefaultWindow" -> "SECRETX",
      Q + "metricsEnabled" -> "yes-SECRETX"
    )
    for ((key, value) <- cases) {
      val r = load(full, env(key, value))
      assertEquals(invalidKeys(r), Set(key), s"$key = $value")
      val text = r.left.getOrElse(Vector.empty).map(_.message).mkString("\n")
      assert(!text.contains("SECRETX") && !text.contains("w" * 129), s"value echoed: $text")
    }
    // Typed file errors: quoted numbers, a non-list, a non-boolean.
    for (
      line <- Vector(
        s"${Q}syncTimeoutMillis = \"30000\"",
        s"${Q}graphEdgeTypes = coauthored-with",
        s"${Q}metricsEnabled = 1",
        s"${Q}graphEdgeTypes = []",
        s"${Q}graphMaxDepth = 2"
      )
    ) assertEquals(invalidKeys(load(full + line + "\n")).size, 1, line)
  }

  test("unknown production keys and unknown service kinds are rejected") {
    for (
      (line, key) <- Vector(
        s"${Q}unknownKey = 1" -> s"${Q}unknownKey",
        "services.other-kind.syncTimeoutMillis = 1" -> "services.other-kind",
        s"${D}tables.unknownRole = x" -> s"${D}tables.unknownRole",
        s"${S}inFlightTtl = 1" -> s"${S}inFlightTtl",
        s"${Q}server.tls = true" -> s"${Q}server.tls"
      )
    )
      assert(
        load(full + line + "\n").left
          .exists(_.contains(ConfigError.UnknownKey("file base.conf", key))),
        line
      )
    assertEquals(
      load(full, Map("SCHOLARCHAIN_CFG_SERVICES__QUERY_JOBS__NOPE" -> "1")),
      Left(
        Vector(ConfigError.UnknownKey("environment", "SCHOLARCHAIN_CFG_SERVICES__QUERY_JOBS__NOPE"))
      )
    )
  }

  // ------------------------------------------------------------------------ cross-field rules

  private def ruleIds(r: Either[Vector[ConfigError], ScholarChainConfig]): Set[String] =
    reasons(r).map(_.takeWhile(_ != ':')).toSet

  test("the rule table is data: unique IDs, known keys, formulas naming their keys") {
    val ids = ProductionRules.all.map(_.id)
    assertEquals(ids.distinct.size, ids.size)
    assertEquals(ProductionRules.all.size, 6 * 3 + 3 + 3 + 6 + 5)
    for (r <- ProductionRules.all) {
      assert(r.keys.forall(ProductionKeys.byPath.contains), r.id)
      assert(r.source.nonEmpty, r.id)
      assert(r.keys.forall(k => r.formula.contains(k) || r.id == "DDB-3"), r.id)
    }
    assertEquals(ProductionRules.violations(ProductionConfig.flatten(loadOk(full))), Vector.empty)
  }

  test("each cross-field rule refuses its violation, reported on its first key") {
    val cases: Vector[(String, Map[String, String])] =
      Vector(Q, E, G).flatMap { p =>
        val t = p.stripPrefix("services.").stripSuffix(".")
        Vector(
          s"SVC-1[$t]" -> env(p + "graphDefaultPageSize", "51"),
          s"SVC-2[$t]" -> env(p + "graphMaxPageSize", "200"),
          s"SVC-3[$t]" -> env(p + "evidenceMaxBundles", "100"),
          s"SVC-4[$t]" -> env(p + "readTimeoutMillis", "6000"),
          s"SVC-5[$t]" -> env(p + "readRequestTimeoutMillis", "30000"),
          s"SVC-6[$t]" -> env(p + "idempotencyTtlMillis", "60000"),
          s"SRV-1[$t]" -> env(p + "server.maxBodyBytes", "65535")
        )
      } ++ Vector(
        "DDB-1" -> env(D + "apiCallAttemptTimeoutMillis", "500"),
        "DDB-2" -> env(D + "batchGetBackoffBaseMillis", "300"),
        "DDB-3" -> env(D + "tables.queryJob", "example-run-ledger"),
        "X-1" -> env(D + "maxQueryLimit", "99"),
        "X-2" -> env(E + "explainMaxSteps", "1001"),
        "X-3" -> env(S + "maxOwnedByQueryIds", "99"),
        "X-4[query-jobs]" -> env(Q + "readTimeoutMillis", "400"),
        "X-4[evidence-explain]" -> env(E + "readTimeoutMillis", "400"),
        "X-5" -> env(D + "batchGetMaxAttempts", "10"),
        "ST-1" -> env(S + "maxClockSkewMillis", "120000"),
        "ST-2" -> env(S + "inFlightTtlSeconds", "86"),
        "ST-3" -> env(S + "jobTtlSeconds", "310"),
        "ST-4" -> env(S + "ownershipTtlSeconds", "86465"),
        "ST-5" -> env(S + "completedTtlSeconds", "605")
      )
    assertEquals(
      cases.map(_._1).toSet,
      ProductionRules.all.map(_.id).toSet,
      "every rule is covered"
    )
    for ((id, overrides) <- cases) {
      val r = load(full, overrides)
      assert(ruleIds(r).contains(id), s"$id: ${reasons(r)}")
      val rule = ProductionRules.all.find(_.id == id).get
      assert(invalidKeys(r).contains(rule.keys.head), id)
      assert(
        r.left.exists(_.forall {
          case ConfigError.InvalidValue(_, src, _) => src == "resolved configuration"
          case _ => false
        }),
        id
      )
    }
  }

  test("timing rules hold exactly at their derived bound (formulas, not fixed budgets)") {
    // ST-2: 120 s > 91000 ms required (DE-INT-02: 86000 plus the closing write, CR-INT-04-2);
    // 86 s is refused (above), 92 s passes (91 s is refused in the CR-INT-04-2 test).
    assert(load(full, env(S + "inFlightTtlSeconds", "92")).isRight)
    // ST-3: 310000 ms required (strict), 311 s passes.
    assert(load(full, env(S + "jobTtlSeconds", "311")).isRight)
    // ST-4 and ST-5 are >=: the requirement itself passes.
    assert(load(full, env(S + "ownershipTtlSeconds", "86466")).isRight)
    assert(load(full, env(S + "completedTtlSeconds", "606")).isRight)
    // The budgets follow the configured values: a longer async timeout raises every requirement.
    // 116 s and 86466 s pass with async 60000 but not with 90000 (121000 and 86496000 required).
    val longer = env(Q + "asyncTimeoutMillis", "90000")
    val tight = env(S + "inFlightTtlSeconds", "116") ++ env(S + "ownershipTtlSeconds", "86466")
    assert(load(full, tight).isRight)
    assertEquals(ruleIds(load(full, longer ++ tight)), Set("ST-2", "ST-4"))
    // More queued jobs per runner raise the job TTL requirement only:
    // ceil(200/4) * 71000 + 5000 + 21000 = 3576000 < 3600000; ceil(210/4) = 53 gives 3789000.
    assert(load(full, env(Q + "jobQueueCapacity", "200")).isRight)
    assertEquals(ruleIds(load(full, env(Q + "jobQueueCapacity", "210"))), Set("ST-3"))
  }

  test("rules are checked only once every key they name is set, and skip keys with bad values") {
    // ST-2 needs the query-jobs section: storage alone loads.
    assert(
      load(
        base + storageLines.replace("inFlightTtlSeconds = 120", "inFlightTtlSeconds = 10")
      ).isRight
    )
    // An in-memory configuration with an out-of-range value reports the bound once, no rule noise.
    val c = loadOk(full)
    val bad = c.copy(serviceState = c.serviceState.copy(maxClockSkewMillis = Some(Long.MaxValue)))
    assertEquals(ProductionConfig.problems(bad).map(_._1), Vector(S + "maxClockSkewMillis"))
    val neg = c.copy(services =
      c.services.copy(queryJobs = c.services.queryJobs.copy(drainMillis = Some(Long.MinValue)))
    )
    assertEquals(ProductionConfig.problems(neg).map(_._1), Vector(Q + "drainMillis"))
    assert(ProductionSettings.forProcess(neg, ServiceKindKey.QueryJobs).isLeft)
  }

  // ------------------------------------------------------------------------ arithmetic

  test("saturating arithmetic never wraps") {
    assertEquals(SatMath.add(Long.MaxValue, 1), Long.MaxValue)
    assertEquals(SatMath.add(Long.MinValue, -1), Long.MinValue)
    assertEquals(SatMath.mul(Long.MaxValue, 2), Long.MaxValue)
    assertEquals(SatMath.mul(Long.MinValue, 2), Long.MinValue)
    assertEquals(SatMath.mul(-3, 4), -12L)
    assertEquals(SatMath.sum(Long.MaxValue, Long.MaxValue, Long.MaxValue), Long.MaxValue)
    assertEquals(SatMath.ceilDiv(16, 4), 4L)
    assertEquals(SatMath.ceilDiv(17, 4), 5L)
    val max = ProductionBounds.MaxDurationMillis
    assertEquals(
      TimingBudgets.inFlightRequirementMillis(Long.MaxValue, Long.MaxValue, 1, 2, 3, 4),
      Long.MaxValue
    )
    assertEquals(
      TimingBudgets.jobQueueWaitMillis(Int.MaxValue.toLong, 1, max),
      Long.MaxValue,
      "2^31 x 3.2e11 saturates"
    )
    assertEquals(
      TimingBudgets.batchGetChunkBudgetMillis(Int.MaxValue.toLong, max, 1, max),
      Long.MaxValue
    )
    // 3 attempts x 500 + sleeps 50 + 100
    assertEquals(TimingBudgets.batchGetChunkBudgetMillis(3, 500, 50, 200), 1650L)
    // capped sleeps: 50, 100, 200, 200
    assertEquals(TimingBudgets.batchGetChunkBudgetMillis(5, 100, 50, 200), 500L + 550)
    assertEquals(TimingBudgets.batchGetChunkBudgetMillis(4, 100, 0, 0), 400L)
    assertEquals(TimingBudgets.batchGetChunkBudgetMillis(1, 100, 50, 200), 100L)
  }

  test("the frozen digest of a production configuration (wire format of the new sections)") {
    val c = loadOk(base + serviceLines("graph"))
    val json = upickle.default.write(c.services)
    assert(json.startsWith("""{"graph":{"maxBodyBytes":65536,"maxQuestionBytes":2048,"""), json)
    assert(json.contains(""""graphEdgeTypes":["coauthored-with","authored"]"""), json)
    assert(json.contains(""""server":{"host":"0.0.0.0","port":8080,"""), json)
    assertEquals(ScholarChainConfig.digest(c), Canonical.sha256HexUtf8(upickle.default.write(c)))
  }
}
