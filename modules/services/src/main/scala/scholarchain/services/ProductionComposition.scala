package scholarchain.services

import scholarchain.core.config.{
  DurableStateSettings,
  DynamoDbConnectionSettings,
  ProcessSettings,
  ProductionSettings,
  ScholarChainConfig,
  ServiceKindKey,
  ServiceProcessSettings
}
import scholarchain.storage.aws.dynamodb.{
  DynamoDbAdapterSettings,
  DynamoDbCalls,
  DynamoDbClientSettings,
  DynamoDbKeyValueStore,
  DynamoDbTableNames
}
import scholarchain.storage.model.{
  BoundQuery,
  EmbeddingPolicy,
  Item,
  ObjectRef,
  PrimaryKey,
  PutOutcome,
  StoreError,
  TableRole,
  VectorFilter
}
import scholarchain.storage.port.{
  GraphReader,
  IndexInspector,
  KeyQuery,
  KeyValueReader,
  KeyValueWriter,
  ObjectReader,
  RuntimeStores,
  VectorReader
}
import scholarchain.storage.state.ServiceStateSettings

/**
 * SV-INT-02: the 1:1 mapping of core's validated production settings
 * (`ProductionSettings.forProcess`) into the services and storage settings. No value is recomputed,
 * defaulted or rounded: every field is copied, and the durable-state inputs core derived
 * (`longestWorkflowTimeoutMillis`, `drainMillis`, `maxClockSkewMillis`,
 * `inFlightRequirementMillis`) are passed to storage `ServiceStateSettings.of` unchanged. Error
 * texts name keys and rules only, never values (core's and storage's messages are value-free).
 */
object ProductionMapping {

  /** The core key segment of a deployable service; `All` is not deployable. */
  def keyOf(kind: ServiceKind): Either[String, ServiceKindKey] = kind match {
    case ServiceKind.QueryJobs => Right(ServiceKindKey.QueryJobs)
    case ServiceKind.EvidenceExplain => Right(ServiceKindKey.EvidenceExplain)
    case ServiceKind.Graph => Right(ServiceKindKey.Graph)
    case _ => Left("kind: production composes query-jobs, evidence-explain or graph only")
  }

  def serviceConfig(p: ServiceProcessSettings): ServiceConfig =
    ServiceConfig(
      maxBodyBytes = p.maxBodyBytes,
      maxQuestionBytes = p.maxQuestionBytes,
      syncTimeoutMillis = p.syncTimeoutMillis,
      asyncTimeoutMillis = p.asyncTimeoutMillis,
      readTimeoutMillis = p.readTimeoutMillis,
      readMaxAttempts = p.readMaxAttempts,
      breakerFailureThreshold = p.breakerFailureThreshold,
      breakerOpenMillis = p.breakerOpenMillis,
      readRequestTimeoutMillis = p.readRequestTimeoutMillis,
      queueWaitMillis = p.queueWaitMillis,
      workflowWorkers = p.workflowWorkers,
      workflowQueueCapacity = p.workflowQueueCapacity,
      keyValueWorkers = p.keyValueWorkers,
      keyValueQueueCapacity = p.keyValueQueueCapacity,
      graphWorkers = p.graphWorkers,
      graphQueueCapacity = p.graphQueueCapacity,
      jobWorkers = p.jobWorkers,
      jobQueueCapacity = p.jobQueueCapacity,
      maxJobs = p.maxJobs,
      idempotencyCapacity = p.idempotencyCapacity,
      idempotencyTtlMillis = p.idempotencyTtlMillis,
      queryOwnershipCapacity = p.queryOwnershipCapacity,
      graphMaxDepth = p.graphMaxDepth,
      graphMaxPageSize = p.graphMaxPageSize,
      graphDefaultPageSize = p.graphDefaultPageSize,
      graphMaxResults = p.graphMaxResults,
      graphEdgeTypes = p.graphEdgeTypes,
      graphDefaultWindow = p.graphDefaultWindow,
      evidenceMaxContentBytes = p.evidenceMaxContentBytes,
      evidenceMaxBundles = p.evidenceMaxBundles,
      evidenceScanLimit = p.evidenceScanLimit,
      explainMaxSteps = p.explainMaxSteps,
      metricsEnabled = p.metricsEnabled,
      retryAfterSeconds = p.retryAfterSeconds
    )

  def serverSettings(p: ServiceProcessSettings): HttpServerAdapter.Settings =
    HttpServerAdapter.Settings(
      host = p.serverHost,
      port = p.serverPort,
      backlog = p.serverBacklog,
      workers = p.serverWorkers,
      queueCapacity = p.serverQueueCapacity,
      maxBodyBytes = p.serverMaxBodyBytes,
      overflowCapacity = p.serverOverflowCapacity
    )

  def serviceSettings(p: ServiceProcessSettings): ServiceSettings =
    ServiceSettings(serviceConfig(p), serverSettings(p), p.drainMillis, p.graceSeconds)

  /** Storage settings with core's derived inputs passed unchanged (12 parameters). */
  def stateSettings(d: DurableStateSettings): Either[String, ServiceStateSettings] =
    ServiceStateSettings
      .of(
        d.writer,
        d.ownershipTtlSeconds,
        d.jobTtlSeconds,
        d.completedTtlSeconds,
        d.inFlightTtlSeconds,
        d.maxPayloadBytes,
        d.maxConflictRetries,
        d.maxOwnedByQueryIds,
        d.longestWorkflowTimeoutMillis,
        d.drainMillis,
        d.maxClockSkewMillis,
        d.inFlightRequirementMillis
      )
      .left
      .map(e => s"storage.serviceState: ${e.message}")

  /** The DynamoDB adapter's settings from `storage.dynamodb.*`. */
  final case class DynamoDbParts(
      client: DynamoDbClientSettings,
      adapter: DynamoDbAdapterSettings,
      names: DynamoDbTableNames
  )

  def dynamoDbParts(d: DynamoDbConnectionSettings): Either[String, DynamoDbParts] = {
    def p[A](e: Either[StoreError, A]) = e.left.map(x => s"storage.dynamodb: ${x.message}")
    for {
      client <- p(
        DynamoDbClientSettings
          .of(d.region, d.endpointOverride, d.apiCallTimeoutMillis, d.apiCallAttemptTimeoutMillis)
      )
      adapter <- p(
        DynamoDbAdapterSettings.of(
          d.maxQueryLimit,
          d.maxQueryPages,
          d.batchGetMaxAttempts,
          d.batchGetBackoffBaseMillis,
          d.batchGetBackoffMaxMillis
        )
      )
      roles <- d.tableNames.toVector.foldLeft[Either[String, Map[TableRole, String]]](
        Right(Map.empty)
      ) { case (acc, (tag, name)) =>
        acc.flatMap(m =>
          TableRole.values
            .find(_.tag == tag)
            .map(r => m + (r -> name))
            .toRight("storage.dynamodb.tables: a role core names is not a storage table role")
        )
      }
      _ <-
        if (TableRole.values.forall(roles.contains)) Right(())
        else Left("storage.dynamodb.tables: every storage table role needs a name")
      names <- p(DynamoDbTableNames.of(roles))
    } yield DynamoDbParts(client, adapter, names)
  }

  /** Core's resolution of one process; Left with core's value-free messages. */
  def resolve(c: ScholarChainConfig, kind: ServiceKind): Either[String, ProcessSettings] =
    keyOf(kind).flatMap(k =>
      ProductionSettings.forProcess(c, k).left.map(_.map(_.message).mkString("; "))
    )
}

/**
 * SV-INT-02: the production dependencies of a service process, each an EXPLICIT injection point.
 * Only the DynamoDB adapter exists in this repository (storage `DynamoDbKeyValueStore`, built here
 * from `storage.dynamodb.*` over the injected SDK-call factory). Every other production adapter is
 * not built yet and MUST be injected; a process that needs one that is `None` does not start (fail
 * closed). No in-memory fake is ever substituted, and an injected `scholarchain.storage.fake.*`
 * store is refused.
 *
 * @param dynamoDb
 *   builds the five-operation DynamoDB seam from the validated client settings (production: storage
 *   `DynamoDbCalls.of(DynamoDbClients.build(settings, credentials))` with the deployment's
 *   credentials provider; tests: a stub). Required by query-jobs and evidence-explain.
 * @param apiKeys
 *   the client key digests (production: an AWS Secrets Manager-backed source, NOT BUILT). Required.
 * @param objects
 *   ordinary S3 reads (S3 adapter, NOT BUILT). Required by query-jobs.
 * @param graph
 *   Neptune reads (Neptune adapter, NOT BUILT). Required by query-jobs and graph.
 * @param vectors
 *   S3 Vectors reads (S3 Vectors adapter, NOT BUILT). Required by query-jobs.
 * @param vectorIndexes
 *   S3 Vectors index inspection (same adapter, NOT BUILT). Required by query-jobs.
 * @param workflow
 *   builds the Graph-RAG workflow over the composed runtime stores (production: rag
 *   `CollaborationWorkflow` with the production AnswerModel and query embedder, NOT BUILT; only
 *   non-production ones exist). Required by query-jobs.
 */
final case class ProductionDependencies(
    dynamoDb: Option[DynamoDbClientSettings => Either[String, DynamoDbCalls]] = None,
    apiKeys: Option[ApiKeyDigestSource] = None,
    objects: Option[ObjectReader] = None,
    graph: Option[GraphReader] = None,
    vectors: Option[VectorReader] = None,
    vectorIndexes: Option[IndexInspector] = None,
    workflow: Option[RuntimeStores => Either[String, QueryWorkflow]] = None,
    accessLog: AccessLog = AccessLog.slf4j(),
    clock: ServiceClock = ServiceClock.system,
    ids: IdSource = IdSource.secure()
)

/** One composed production process: its settings, its ports and the reported attempt budgets. */
final case class ProductionComposition(
    kind: ServiceKind,
    settings: ServiceSettings,
    ports: ServicePorts,
    stateSettings: Option[ServiceStateSettings],
    attemptBudgets: Option[CompositeAttemptBudgets]
)

/**
 * SV-INT-02: the production [[ServiceProvider]]: core `ProductionSettings.forProcess` resolves and
 * validates the configuration (a configuration core refuses never reaches a client factory or a
 * runtime), [[ProductionMapping]] maps it 1:1, and the ports are composed from ONE DynamoDB adapter
 * (all nine table names; the runtime read facade refuses the service-state tables, CR-INT-01-3)
 * plus the injected dependencies. Store access is [[StoreAccess.concurrent]] (every production
 * adapter must be safe for concurrent use). Per process:
 *
 *   - query-jobs: DynamoDB (durable ownership, jobs and idempotency; runtime tables), API keys, S3,
 *     Neptune, S3 Vectors, the workflow;
 *   - evidence-explain: DynamoDB (durable ownership reads; runtime table reads), API keys; S3,
 *     Neptune, S3 Vectors and the runtime writers are not composed (they refuse);
 *   - graph: Neptune, API keys; DynamoDB, S3 and S3 Vectors are not composed (they refuse).
 */
final class ProductionServiceProvider(config: ScholarChainConfig, deps: ProductionDependencies)
    extends ServiceProvider {

  def settings(kind: ServiceKind): Either[String, ServiceSettings] =
    ProductionMapping.resolve(config, kind).map(p => ProductionMapping.serviceSettings(p.service))

  def ports(kind: ServiceKind): Either[String, ServicePorts] = compose(kind).map(_.ports)

  private def required[A](what: String, a: Option[A]): Either[String, A] =
    a.filter(_ != null).toRight(s"$what: not provided (production dependency, fail closed)")

  /** An in-memory store of storage is never a production dependency. */
  private def notFake[A](what: String, a: A): Either[String, A] =
    if (a.getClass.getName.startsWith("scholarchain.storage.fake."))
      Left(s"$what: an in-memory fake is not a production dependency")
    else Right(a)

  private def injected[A](what: String, a: Option[A]): Either[String, A] =
    required(what, a).flatMap(notFake(what, _))

  private def adapter(p: ProcessSettings): Either[String, DynamoDbKeyValueStore] =
    for {
      d <- p.dynamoDb.toRight("storage.dynamodb: not resolved")
      parts <- ProductionMapping.dynamoDbParts(d)
      factory <- required("dynamoDb (DynamoDB SDK client factory)", deps.dynamoDb)
      calls <- factory(parts.client).flatMap(c =>
        Option(c).toRight("dynamoDb: the client factory returned no client")
      )
      checked <- notFake("dynamoDb", calls)
    } yield DynamoDbKeyValueStore(
      checked,
      parts.names,
      parts.adapter,
      DynamoDbKeyValueStore.ThreadSleeper
    )

  private def durable(
      p: ProcessSettings,
      kv: DynamoDbKeyValueStore
  ): Either[String, DurableServiceState] =
    for {
      d <- p.durableState.toRight("storage.serviceState: not resolved")
      s <- ProductionMapping.stateSettings(d)
      st <- DurableServiceState(kv, deps.clock, s)
    } yield st

  private def budgets(p: ProcessSettings): Option[CompositeAttemptBudgets] =
    for {
      d <- p.dynamoDb
      s <- p.durableState
    } yield CompositeAttemptBudgets.of(d.apiCallTimeoutMillis, s.maxConflictRetries)

  def compose(kind: ServiceKind): Either[String, ProductionComposition] =
    for {
      p <- ProductionMapping.resolve(config, kind)
      settings = ProductionMapping.serviceSettings(p.service)
      keys <- injected("apiKeys (Secrets Manager ApiKeyDigestSource)", deps.apiKeys)
      log <- required("accessLog", Option(deps.accessLog))
      clock <- required("clock", Option(deps.clock))
      ids <- required("ids", Option(deps.ids))
      composed <- kind match {
        case ServiceKind.QueryJobs =>
          for {
            kv <- adapter(p)
            st <- durable(p, kv)
            objects <- injected("objects (S3 ObjectReader)", deps.objects)
            graph <- injected("graph (Neptune GraphReader)", deps.graph)
            vectors <- injected("vectors (S3 Vectors VectorReader)", deps.vectors)
            indexes <- injected("vectorIndexes (S3 Vectors IndexInspector)", deps.vectorIndexes)
            stores = RuntimeStores(
              objects,
              kv,
              kv,
              graph,
              vectors,
              indexes,
              EmbeddingPolicy.RealModelsOnly
            )
            factory <- required(
              "workflow (rag CollaborationWorkflow with the production AnswerModel and embedder)",
              deps.workflow
            )
            wf <- factory(stores).flatMap(w => Option(w).toRight("workflow: none was built"))
          } yield (
            ServicePorts(
              Some(stores),
              Some(wf),
              keys,
              st.ownership,
              StoreAccess.concurrent,
              log,
              clock,
              ids,
              Some(st.ports)
            ),
            Some(st.settings)
          )
        case ServiceKind.EvidenceExplain =>
          for {
            kv <- adapter(p)
            st <- durable(p, kv)
            stores = RuntimeStores(
              Unwired.objects(kind),
              kv,
              Unwired.writer(kind),
              Unwired.graph(kind),
              Unwired.vectors(kind),
              Unwired.indexes(kind),
              EmbeddingPolicy.RealModelsOnly
            )
          } yield (
            ServicePorts(
              Some(stores),
              None,
              keys,
              st.ownership,
              StoreAccess.concurrent,
              log,
              clock,
              ids
            ),
            Some(st.settings)
          )
        case _ =>
          for {
            graph <- injected("graph (Neptune GraphReader)", deps.graph)
            stores = RuntimeStores(
              Unwired.objects(kind),
              Unwired.reader(kind),
              Unwired.writer(kind),
              graph,
              Unwired.vectors(kind),
              Unwired.indexes(kind),
              EmbeddingPolicy.RealModelsOnly
            )
          } yield (
            ServicePorts(
              Some(stores),
              None,
              keys,
              null,
              StoreAccess.concurrent,
              log,
              clock,
              ids
            ),
            None
          )
      }
    } yield ProductionComposition(kind, settings, composed._1, composed._2, budgets(p))

  /**
   * Composes and starts `kind`. Attempt IDs are the runtime's `AttemptTokens.secureRandom()` (128
   * random bits per logical request).
   */
  def start(kind: ServiceKind): Either[String, RunningService] =
    compose(kind).flatMap(c => ServiceMain.start(kind, c.settings, c.ports))
}

/**
 * SV-INT-02: the stores a process does not compose. Each refuses every call (`Refused`, which the
 * read policy maps to `500`, never a silent empty answer), so a process can never reach a store it
 * was not given, and no fake ever stands in for a real one.
 */
private[services] object Unwired {
  private def no(what: String, kind: ServiceKind) =
    Left(StoreError.Refused(s"$what is not composed in the ${kind.tag} process"))

  def objects(kind: ServiceKind): ObjectReader = new ObjectReader {
    def head(ref: ObjectRef) = no("S3", kind)
    def get(ref: ObjectRef) = no("S3", kind)
    def list(bucket: String, prefix: String, maxKeys: Int) = no("S3", kind)
  }

  def reader(kind: ServiceKind): KeyValueReader = new KeyValueReader {
    def get(table: TableRole, key: PrimaryKey): Either[StoreError, Option[Item]] =
      no("DynamoDB", kind)
    def query(table: TableRole, q: KeyQuery): Either[StoreError, Vector[Item]] =
      no("DynamoDB", kind)
  }

  def writer(kind: ServiceKind): KeyValueWriter = new KeyValueWriter {
    def putIfAbsent(table: TableRole, item: Item): Either[StoreError, PutOutcome] =
      no("the runtime table writer", kind)
    def putIfVersion(table: TableRole, item: Item, expectedVersion: Long) =
      no("the runtime table writer", kind)
  }

  def graph(kind: ServiceKind): GraphReader = new GraphReader {
    def run(query: BoundQuery) = no("Neptune", kind)
  }

  def vectors(kind: ServiceKind): VectorReader = new VectorReader {
    def query(index: String, vector: Vector[Float], topK: Int, filter: Option[VectorFilter]) =
      no("S3 Vectors", kind)
    def getVectors(index: String, keys: Vector[String]) = no("S3 Vectors", kind)
  }

  def indexes(kind: ServiceKind): IndexInspector = new IndexInspector {
    def describe(index: String) = no("S3 Vectors", kind)
  }
}
