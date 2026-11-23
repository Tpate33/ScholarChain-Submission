package scholarchain.services

import scholarchain.core.config.{
  DurableStateSettings,
  DynamoDbConnectionSettings,
  LauncherSettings,
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

  /**
   * Core's `forProcess` resolution of one process; Left with core's value-free messages. Used by a
   * [[ProductionServiceProvider]] built from a configuration (tests, compositions without adapter
   * sections). The production mains never use it: they resolve through [[resolveLauncher]].
   */
  def resolve(c: ScholarChainConfig, kind: ServiceKind): Either[String, ProcessSettings] =
    keyOf(kind).flatMap(k =>
      ProductionSettings.forProcess(c, k).left.map(_.map(_.message).mkString("; "))
    )

  /**
   * SV-INT-03 (criterion A1): core's `ProductionSettings.forLauncher` resolution of one process,
   * the ONLY resolution the production mains use ([[ProductionLauncher]]). It refuses everything
   * `forProcess` refuses and, in addition, a configuration without the process's adapter and
   * API-key source sections. Left with core's value-free messages.
   */
  def resolveLauncher(c: ScholarChainConfig, kind: ServiceKind): Either[String, LauncherSettings] =
    keyOf(kind).flatMap(k =>
      ProductionSettings.forLauncher(c, k).left.map(_.map(_.message).mkString("; "))
    )
}

/**
 * SV-INT-02: the production dependencies of a service process, each an EXPLICIT injection point. A
 * process that needs one that is `None` does not start (fail closed), naming it. No in-memory fake
 * is ever substituted: an injected `scholarchain.storage.fake.*` store and any object of a
 * sub-package of `scholarchain.services` (the only one is the NON-PRODUCTION local demo of
 * SV-INT-03a) are refused. SV-INT-03: the production mains fill these from ONE storage
 * `StorageClients` bundle ([[ProductionLauncher]], mapping core `LauncherSettings` 1:1), which they
 * close on shutdown and on a failed start.
 *
 * @param dynamoDb
 *   builds the five-operation DynamoDB seam from the validated client settings (production: storage
 *   `StorageClients.dynamoDbFactory(RetrySetting.of(storage.dynamodb.retryMaxAttempts))`, a
 *   `DynamoDbClientFactory` sharing the bundle's credentials provider and its explicit retry
 *   setting, CR-INT-07-3; tests: a stub). Required by query-jobs and evidence-explain.
 * @param apiKeys
 *   the client key digests (production: the AWS Secrets Manager-backed source of SV-INT-03b, NOT
 *   BUILT: the secretsmanager SDK module is not on the services classpath). Required.
 * @param objects
 *   ordinary S3 reads (production: `StorageClients.objectReader`). Required by query-jobs.
 * @param graph
 *   Neptune reads (production: `StorageClients.graphReader`). Required by query-jobs and graph.
 * @param vectors
 *   S3 Vectors reads (production: `StorageClients.vectorReader`). Required by query-jobs.
 * @param vectorIndexes
 *   S3 Vectors index inspection (production: the SAME `StorageClients.vectorReader` instance).
 *   Required by query-jobs.
 * @param workflow
 *   builds the Graph-RAG workflow over the composed runtime stores (production: rag
 *   `CollaborationWorkflow` with the production AnswerModel and query embedder, NOT BUILT; only
 *   non-production ones exist). Required by query-jobs. The stores it receives are bounded by the
 *   query-jobs read policy ([[WorkflowStoreBounds]], G4).
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

/**
 * One composed production process: its settings, its ports and the reported attempt budgets.
 * `resources` are what the composition itself started (query-jobs: the [[WorkflowStoreBounds]]
 * pool); [[ProductionServiceProvider.start]] releases them with the process.
 */
final case class ProductionComposition(
    kind: ServiceKind,
    settings: ServiceSettings,
    ports: ServicePorts,
    stateSettings: Option[ServiceStateSettings],
    attemptBudgets: Option[CompositeAttemptBudgets],
    resources: Vector[AutoCloseable] = Vector.empty
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
 *
 * SV-INT-03: built from a configuration (`new ProductionServiceProvider(config, deps)`) it resolves
 * through core `forProcess` (compositions and tests); the production mains build it through
 * [[ProductionServiceProvider.launcher]] from core `forLauncher`'s `LauncherSettings` instead
 * ([[ProductionLauncher]]), so `forProcess` is never on their path.
 */
final class ProductionServiceProvider private (
    resolveProcess: ServiceKind => Either[String, ProcessSettings],
    deps: ProductionDependencies
) extends ServiceProvider {

  /** Resolves through core `ProductionSettings.forProcess` ([[ProductionMapping.resolve]]). */
  def this(config: ScholarChainConfig, deps: ProductionDependencies) =
    this((k: ServiceKind) => ProductionMapping.resolve(config, k), deps)

  def settings(kind: ServiceKind): Either[String, ServiceSettings] =
    resolveProcess(kind).map(p => ProductionMapping.serviceSettings(p.service))

  def ports(kind: ServiceKind): Either[String, ServicePorts] = compose(kind).map(_.ports)

  private def required[A](what: String, a: Option[A]): Either[String, A] =
    a.filter(_ != null).toRight(s"$what: not provided (production dependency, fail closed)")

  /**
   * An in-memory store of storage is never a production dependency, and neither is any object of a
   * SUB-PACKAGE of `scholarchain.services` (SV-INT-03a hardening): the only such package is the
   * NON-PRODUCTION local demo, and every production class of services lives in the package itself.
   * The rule is structural, so this source does not name the demo package (the demo's own isolation
   * test forbids that). Checked by class and package name: a wrapper of such an object defined
   * elsewhere passes (CR-INT-06-4, recorded); ownership of the production entry points is the
   * primary control.
   */
  private def notFake[A](what: String, a: A): Either[String, A] =
    if (a.getClass.getName.startsWith(ProductionServiceProvider.StorageFakePrefix))
      Left(s"$what: an in-memory fake is not a production dependency")
    else if (ProductionServiceProvider.servicesSubPackage(a.getClass))
      Left(s"$what: a NON-PRODUCTION local-demo object is not a production dependency")
    else Right(a)

  private def injected[A](what: String, a: Option[A]): Either[String, A] =
    required(what, a).flatMap(notFake(what, _))

  private def adapter(p: ProcessSettings): Either[String, DynamoDbKeyValueStore] =
    for {
      d <- p.dynamoDb.toRight("storage.dynamodb: not resolved")
      parts <- ProductionMapping.dynamoDbParts(d)
      factory <- injected("dynamoDb (DynamoDB SDK client factory)", deps.dynamoDb)
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
      p <- resolveProcess(kind)
      settings = ProductionMapping.serviceSettings(p.service)
      keys <- injected("apiKeys (Secrets Manager ApiKeyDigestSource)", deps.apiKeys)
      log <- injected("accessLog", Option(deps.accessLog))
      clock <- injected("clock", Option(deps.clock))
      ids <- injected("ids", Option(deps.ids))
      composed <- kind match {
        case ServiceKind.QueryJobs =>
          for {
            kv <- adapter(p)
            st <- durable(p, kv)
            objects <- injected("objects (S3 ObjectReader)", deps.objects)
            graph <- injected("graph (Neptune GraphReader)", deps.graph)
            vectors <- injected("vectors (S3 Vectors VectorReader)", deps.vectors)
            indexes <- injected("vectorIndexes (S3 Vectors IndexInspector)", deps.vectorIndexes)
            factory <- injected(
              "workflow (rag CollaborationWorkflow with the production AnswerModel and embedder)",
              deps.workflow
            )
            // G4: every store call of the workflow runs under the query-jobs read policy. The
            // service-state stores (durable ownership, jobs, idempotency) are not wrapped: they
            // already run under the key-value policy (writeTracked).
            bounds = WorkflowStoreBounds(settings.config, clock)
            stores = RuntimeStores(
              bounds.objects(objects),
              bounds.keyValues(kv),
              bounds.writer(kv),
              bounds.graph(graph),
              bounds.vectors(vectors),
              bounds.indexes(indexes),
              EmbeddingPolicy.RealModelsOnly
            )
            wf <- ProductionLauncher
              .guard("workflow")(factory(stores))
              .flatMap(identity)
              .flatMap(w => Option(w).toRight("workflow: none was built"))
              .flatMap(notFake("workflow", _))
              .left
              .map { e =>
                bounds.close(); e
              }
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
            Some(st.settings),
            Vector[AutoCloseable](bounds)
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
            Some(st.settings),
            Vector.empty[AutoCloseable]
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
            None,
            Vector.empty[AutoCloseable]
          )
      }
    } yield ProductionComposition(
      kind,
      settings,
      composed._1,
      composed._2,
      budgets(p),
      composed._3
    )

  /**
   * Composes and starts `kind`. Attempt IDs are the runtime's `AttemptTokens.secureRandom()` (128
   * random bits per logical request). The composition's own resources are released when the process
   * stops or when the start fails.
   */
  override def start(kind: ServiceKind): Either[String, RunningService] =
    startReleasing(kind, Release.none)

  /**
   * SV-INT-03 (G5): as [[start]], and `release` (the launcher's storage clients) runs EXACTLY ONCE:
   * at once when composing or starting fails, else when the started process stops, after its
   * runtime closed.
   */
  private[services] def startReleasing(
      kind: ServiceKind,
      release: Release
  ): Either[String, RunningService] =
    compose(kind) match {
      case Left(e) =>
        release.run()
        Left(e)
      case Right(c) =>
        ServiceMain.start(
          kind,
          c.settings,
          c.ports,
          Release.of(c.resources.map(r => () => r.close())*).andThen(release)
        )
    }
}

object ProductionServiceProvider {
  val StorageFakePrefix = "scholarchain.storage.fake."

  /** This package (the services production package). */
  val ServicesPackage: String = classOf[ProductionServiceProvider].getPackageName

  /** True when `c` lives in a strict sub-package of [[ServicesPackage]] (NON-PRODUCTION). */
  def servicesSubPackage(c: Class[?]): Boolean =
    c != null && c.getPackageName.startsWith(ServicesPackage + ".")

  /**
   * SV-INT-03: the provider of ONE process resolved by core `forLauncher` (`settings.process` is
   * exactly `forProcess`'s result for that kind); every other kind is refused.
   */
  private[services] def launcher(
      kind: ServiceKind,
      settings: LauncherSettings,
      deps: ProductionDependencies
  ): ProductionServiceProvider =
    new ProductionServiceProvider(
      (k: ServiceKind) =>
        if (k == kind && settings != null && settings.process != null) Right(settings.process)
        else Left(s"kind: this launcher composes ${kind.tag} only"),
      deps
    )
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
