package scholarchain.services

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import scholarchain.core.config.{
  ConfigError,
  ConfigLoader,
  LauncherSettings,
  ProductionSettings,
  ScholarChainConfig,
  ServiceKindKey
}
import scholarchain.storage.aws.{AwsClientSettings, RetrySetting}
import scholarchain.storage.aws.dynamodb.{DynamoDbCalls, DynamoDbClientSettings}
import scholarchain.storage.aws.neptune.{NeptuneClientSettings, NeptuneReaderSettings}
import scholarchain.storage.aws.s3.S3ReaderSettings
import scholarchain.storage.aws.s3vectors.S3VectorsReaderSettings
import scholarchain.storage.model.{
  BoundQuery,
  GraphRow,
  IndexInfo,
  ObjectRef,
  StoreError,
  TableRole,
  VectorFilter
}
import scholarchain.storage.port.{
  GraphReader,
  IndexInspector,
  ObjectReader,
  RuntimeStores,
  VectorReader
}

/**
 * SV-INT-03: a production configuration WITH the DE-INT-04 adapter and API-key source sections
 * (synthetic values: `.invalid` hosts, all-zero account, a secret NAME, never a secret), stub
 * storage clients and a launcher harness. No AWS call, no network apart from loopback.
 *
 * query-jobs has fast read bounds (readTimeoutMillis 300, readRequestTimeoutMillis 1000) so the G4
 * tests run quickly; the adapter timings satisfy core's T-rules against them.
 */
object LauncherFixture {

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
      |  apiCallTimeoutMillis = 50
      |  apiCallAttemptTimeoutMillis = 20
      |  batchGetMaxAttempts = 3
      |  batchGetBackoffBaseMillis = 10
      |  batchGetBackoffMaxMillis = 20
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

  /** The DE-INT-04 sections (no endpoint override: the production shape). */
  val adapters: String =
    """storage.dynamodb.retryMaxAttempts = 3
      |storage.s3 { apiCallTimeoutMillis = 20, apiCallAttemptTimeoutMillis = 10, retryMaxAttempts = 2, maxObjectBytes = 1048576, maxListKeys = 10 }
      |storage.neptune { endpoint = "https://neptune.example.invalid:8182", apiCallTimeoutMillis = 200, apiCallAttemptTimeoutMillis = 100, retryMaxAttempts = 2, maxRows = 1000 }
      |storage.s3vectors { apiCallTimeoutMillis = 100, apiCallAttemptTimeoutMillis = 50, retryMaxAttempts = 2, maxTopK = 10, maxGetKeys = 10 }
      |services.apiKeys { secretId = "scholarchain/test/api-keys", apiCallTimeoutMillis = 1000, apiCallAttemptTimeoutMillis = 500, retryMaxAttempts = 2, cacheTtlMillis = 60000 }
      |""".stripMargin

  private def serviceLines(
      kind: String,
      port: Int,
      readTimeout: Long,
      readRequest: Long,
      queueWait: Long
  ): String =
    s"""services.$kind {
       |  maxBodyBytes = 65536
       |  maxQuestionBytes = 2048
       |  syncTimeoutMillis = 30000
       |  asyncTimeoutMillis = 60000
       |  readTimeoutMillis = $readTimeout
       |  readMaxAttempts = 3
       |  breakerFailureThreshold = 5
       |  breakerOpenMillis = 10000
       |  readRequestTimeoutMillis = $readRequest
       |  queueWaitMillis = $queueWait
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
       |  drainMillis = 10
       |}
       |""".stripMargin

  /** The configuration text; every server binds an ephemeral loopback port unless given one. */
  def text(withAdapters: Boolean = true, queryJobsPort: Int = 0): String =
    base + (if (withAdapters) adapters else "") +
      serviceLines("query-jobs", queryJobsPort, 300, 1000, 200) +
      serviceLines("evidence-explain", 0, 2000, 5000, 1000) +
      serviceLines("graph", 0, 2000, 5000, 1000)

  def load(text: String): Either[Vector[ConfigError], ScholarChainConfig] =
    ConfigLoader.load(
      Map(ConfigLoader.FilesEnvVar -> "launcher.conf"),
      p => if (p == "launcher.conf") Right(text) else Left("absent")
    )

  def config(text: String = LauncherFixture.text()): ScholarChainConfig =
    load(text).fold(e => throw new IllegalStateException(e.map(_.message).mkString("; ")), identity)

  def launcherSettings(kind: ServiceKindKey, c: ScholarChainConfig = config()): LauncherSettings =
    ProductionSettings
      .forLauncher(c, kind)
      .fold(e => throw new IllegalStateException(e.map(_.message).mkString("; ")), identity)

  /** The configured DynamoDB table names (as the adapter resolves them). */
  def tableNames(c: ScholarChainConfig = config()): Map[TableRole, String] = {
    val parts = ProductionMapping
      .dynamoDbParts(launcherSettings(ServiceKindKey.QueryJobs, c).process.dynamoDb.get)
      .fold(e => throw new IllegalStateException(e), identity)
    TableRole.values.map(r => r -> parts.names.of(r).toOption.get).toMap
  }
}

/** A graph reader that blocks while `blocking` (interruptibly) and records its threads. */
final class SlowGraph extends GraphReader {
  @volatile var blocking = false
  val calls = new AtomicInteger(0)
  val threads = new java.util.concurrent.ConcurrentLinkedQueue[String]()
  private val never = new CountDownLatch(1)
  def run(query: BoundQuery): Either[StoreError, Vector[GraphRow]] = {
    calls.incrementAndGet()
    threads.add(Thread.currentThread().getName)
    if (blocking) never.await(30, TimeUnit.SECONDS)
    Right(Vector.empty)
  }
}

/** A workflow factory defined in scholarchain.services (the local rag workflow; ignores stores). */
object TestWorkflows {
  val factory: RuntimeStores => Either[String, QueryWorkflow] =
    _ => Right(new ServiceWorld().ragWorkflow)
}

object StubObjects extends ObjectReader {
  def head(ref: ObjectRef) = Right(None)
  def get(ref: ObjectRef) = Right(None)
  def list(bucket: String, prefix: String, maxKeys: Int) = Right(Vector.empty)
}

object StubVectorsAndIndexes extends VectorReader with IndexInspector {
  def query(index: String, vector: Vector[Float], topK: Int, filter: Option[VectorFilter]) =
    Right(Vector.empty)
  def getVectors(index: String, keys: Vector[String]) = Right(Vector.empty)
  def describe(index: String): Either[StoreError, Option[IndexInfo]] = Right(None)
}

/**
 * Stub storage clients: record every builder call and its settings, count closes, and build the
 * DynamoDB seam over a [[DdbStub]].
 */
final class StubClients(names: Map[TableRole, String]) extends LauncherClients {
  val closes = new AtomicInteger(0)
  val graph = new SlowGraph
  @volatile var refuseGraph = false
  @volatile var dynamo: Vector[RetrySetting] = Vector.empty
  @volatile var dynamoClients: Vector[DynamoDbClientSettings] = Vector.empty
  @volatile var s3: Vector[(AwsClientSettings, S3ReaderSettings)] = Vector.empty
  @volatile var neptune: Vector[(NeptuneClientSettings, NeptuneReaderSettings)] = Vector.empty
  @volatile var vectors: Vector[(AwsClientSettings, S3VectorsReaderSettings)] = Vector.empty

  def dynamoDbFactory(
      retry: RetrySetting
  ): Either[StoreError, DynamoDbClientSettings => Either[String, DynamoDbCalls]] = {
    dynamo :+= retry
    Right(s => { dynamoClients :+= s; Right(new DdbStub(names)) })
  }
  def objectReader(client: AwsClientSettings, settings: S3ReaderSettings) = {
    s3 :+= (client -> settings)
    Right(StubObjects)
  }
  def graphReader(client: NeptuneClientSettings, settings: NeptuneReaderSettings) = {
    neptune :+= (client -> settings)
    if (refuseGraph) Left(StoreError.Refused("neptune: refused (stub)")) else Right(graph)
  }
  def vectorReader(client: AwsClientSettings, settings: S3VectorsReaderSettings) = {
    vectors :+= (client -> settings)
    Right(StubVectorsAndIndexes)
  }
  def close(): Unit = { closes.incrementAndGet(); () }
}

/** A key source that counts its closes (an `AutoCloseable` source is closed with the process). */
final class CloseableKeys extends ApiKeyDigestSource with AutoCloseable {
  private val inner = new FakeKeys
  val closes = new AtomicInteger(0)
  def clients() = inner.clients()
  def close(): Unit = { closes.incrementAndGet(); () }
}

/** A launcher over stub seams, counting key-source builds and client opens. */
final class LauncherHarness(
    text: String = LauncherFixture.text(),
    keys: ApiKeySourceSettingsBuilder = LauncherHarness.fakeKeys,
    val workflow: Option[RuntimeStores => Either[String, QueryWorkflow]] = None,
    openFails: Boolean = false
) {
  val keyBuilds = new AtomicInteger(0)
  val opens = new AtomicInteger(0)
  lazy val clients = new StubClients(LauncherFixture.tableNames())

  val seams: ProductionLauncher.Seams = ProductionLauncher.Seams(
    loadConfig = () => LauncherFixture.load(text),
    apiKeys = s => { keyBuilds.incrementAndGet(); keys(s) },
    openClients = () => {
      opens.incrementAndGet()
      if (openFails) Left("storage clients: no credentials (stub)") else Right(clients)
    },
    workflow = _ => workflow,
    accessLog = new RecordingLog,
    clock = ServiceClock.system,
    ids = new CountingIds
  )

  val launcher = new ProductionLauncher(seams)
}

type ApiKeySourceSettingsBuilder =
  scholarchain.core.config.ApiKeySourceSettings => Either[String, ApiKeyDigestSource]

object LauncherHarness {
  val fakeKeys: ApiKeySourceSettingsBuilder = _ => Right(new FakeKeys)
}
