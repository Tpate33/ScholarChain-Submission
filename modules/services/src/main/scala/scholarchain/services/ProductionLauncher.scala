package scholarchain.services

import scala.util.control.NonFatal

import scholarchain.core.config.{
  AdapterClientSettings,
  ApiKeySourceSettings,
  ConfigError,
  ConfigLoader,
  LauncherSettings,
  ScholarChainConfig
}
import scholarchain.storage.aws.{AwsClientSettings, RetrySetting, StorageClients}
import scholarchain.storage.aws.dynamodb.{DynamoDbCalls, DynamoDbClientSettings}
import scholarchain.storage.aws.neptune.{NeptuneClientSettings, NeptuneReaderSettings}
import scholarchain.storage.aws.s3.S3ReaderSettings
import scholarchain.storage.aws.s3vectors.S3VectorsReaderSettings
import scholarchain.storage.model.StoreError
import scholarchain.storage.port.{
  GraphReader,
  IndexInspector,
  ObjectReader,
  RuntimeStores,
  VectorReader
}

/**
 * SV-INT-03: the services-side seam over ONE storage `StorageClients` bundle (production:
 * [[LauncherClients.of]]; tests: stubs). The bundle owns every SDK client of the process and its
 * one credentials provider; [[close]] closes all of them.
 */
trait LauncherClients {
  def dynamoDbFactory(
      retry: RetrySetting
  ): Either[StoreError, DynamoDbClientSettings => Either[String, DynamoDbCalls]]
  def objectReader(
      client: AwsClientSettings,
      settings: S3ReaderSettings
  ): Either[StoreError, ObjectReader]
  def graphReader(
      client: NeptuneClientSettings,
      settings: NeptuneReaderSettings
  ): Either[StoreError, GraphReader]

  /** One reader that is both the `vectors` and the `vectorIndexes` dependency. */
  def vectorReader(
      client: AwsClientSettings,
      settings: S3VectorsReaderSettings
  ): Either[StoreError, VectorReader & IndexInspector]
  def close(): Unit
}

object LauncherClients {

  /** The production seam: every method delegates to `c`. */
  def of(c: StorageClients): LauncherClients = new LauncherClients {
    def dynamoDbFactory(retry: RetrySetting) = c.dynamoDbFactory(retry)
    def objectReader(client: AwsClientSettings, settings: S3ReaderSettings) =
      c.objectReader(client, settings)
    def graphReader(client: NeptuneClientSettings, settings: NeptuneReaderSettings) =
      c.graphReader(client, settings)
    def vectorReader(client: AwsClientSettings, settings: S3VectorsReaderSettings) =
      c.vectorReader(client, settings)
    def close(): Unit = c.close()
  }

  /**
   * storage `StorageClients.open()`: one owned `DefaultCredentialsProvider`, the real SDK builders
   * and the ambient-endpoint guard over the process. Building it makes no AWS call.
   */
  def open(): Either[String, LauncherClients] =
    StorageClients.open().left.map(e => s"storage clients: ${e.message}").map(of)
}

/**
 * SV-INT-03 (criterion A2): core `LauncherSettings` mapped 1:1 into the storage settings and the
 * readers of ONE [[LauncherClients]] bundle, then into [[ProductionDependencies]]:
 *
 *   - `dynamoDbRetryMaxAttempts` -> `RetrySetting.of` -> `dynamoDbFactory` -> `dynamoDb`;
 *   - `s3` -> `AwsClientSettings.of(client...)`, `S3ReaderSettings.of(buckets.toSet,
 *     maxObjectBytes, maxListKeys)` -> `objectReader` -> `objects`;
 *   - `neptune` -> `NeptuneClientSettings.of(region, endpoint, call, attempt, retry)`,
 *     `NeptuneReaderSettings.of(maxRows)` -> `graphReader` -> `graph`;
 *   - `s3Vectors` -> `AwsClientSettings.of(client...)`, `S3VectorsReaderSettings.of(vectorBucket,
 *     maxTopK, maxGetKeys)` -> `vectorReader` -> `vectors` AND `vectorIndexes` (one instance).
 *
 * No value is recomputed or defaulted. A section core does not resolve for the process (`None`) is
 * not built. Messages name sections and storage's value-free reasons only.
 */
object LauncherMapping {

  def awsClient(c: AdapterClientSettings): Either[StoreError, AwsClientSettings] =
    AwsClientSettings.of(
      c.region,
      c.endpointOverride,
      c.apiCallTimeoutMillis,
      c.apiCallAttemptTimeoutMillis,
      c.retryMaxAttempts
    )

  private def section[A](name: String)(e: Either[StoreError, A]): Either[String, A] =
    e.left.map(x => s"$name: ${x.message}")

  private def each[A, B](o: Option[A])(f: A => Either[String, B]): Either[String, Option[B]] =
    o match {
      case None => Right(None)
      case Some(a) => f(a).map(Some(_))
    }

  /**
   * The storage-backed dependencies of `ls` (apiKeys, workflow and the rest are added by callers).
   */
  def storage(
      ls: LauncherSettings,
      clients: LauncherClients
  ): Either[String, ProductionDependencies] =
    for {
      dynamo <- each(ls.dynamoDbRetryMaxAttempts)(n =>
        section("storage.dynamodb.retryMaxAttempts")(
          RetrySetting.of(n).flatMap(clients.dynamoDbFactory)
        )
      )
      objects <- each(ls.s3)(s =>
        section("storage.s3")(for {
          client <- awsClient(s.client)
          reader <- S3ReaderSettings.of(s.buckets.toSet, s.maxObjectBytes, s.maxListKeys)
          o <- clients.objectReader(client, reader)
        } yield o)
      )
      graph <- each(ls.neptune)(n =>
        section("storage.neptune")(for {
          client <- NeptuneClientSettings.of(
            n.region,
            n.endpoint,
            n.apiCallTimeoutMillis,
            n.apiCallAttemptTimeoutMillis,
            n.retryMaxAttempts
          )
          reader <- NeptuneReaderSettings.of(n.maxRows)
          g <- clients.graphReader(client, reader)
        } yield g)
      )
      vectors <- each(ls.s3Vectors)(v =>
        section("storage.s3vectors")(for {
          client <- awsClient(v.client)
          reader <- S3VectorsReaderSettings.of(v.vectorBucket, v.maxTopK, v.maxGetKeys)
          r <- clients.vectorReader(client, reader)
        } yield r)
      )
    } yield ProductionDependencies(
      dynamoDb = dynamo,
      objects = objects,
      graph = graph,
      vectors = vectors,
      vectorIndexes = vectors
    )
}

/**
 * SV-INT-03: the production launcher of the three service mains (`QueryJobsMain`,
 * `EvidenceExplainMain`, `GraphMain`). `start(kind)`, in this order:
 *
 *   1. loads the configuration (production: `ConfigLoader.loadFromSystem()`) and resolves it with
 *      core `ProductionSettings.forLauncher(c, kind)` (never `forProcess`): a configuration without
 *      the process's adapter or API-key source sections is refused HERE, before any credential
 *      provider or client exists;
 *   2. builds the lazy Secrets Manager digest source from `ApiKeySourceSettings`;
 *   3. opens ONE storage `StorageClients` bundle and maps `LauncherSettings` 1:1 into it
 *      ([[LauncherMapping]]);
 *   4. composes through [[ProductionServiceProvider.launcher]] (the real Bedrock workflow requires
 *      `productionWorkflow`; its store calls are bounded by the query-jobs read policy,
 *      [[WorkflowStoreBounds]]) and starts the server.
 *
 * The bundle (and an `AutoCloseable` key source) is closed EXACTLY ONCE: at once when any step
 * after it fails, else when the started process stops (after its runtime closed). No fake is ever
 * substituted: a missing production dependency fails closed and names itself.
 *
 * [[settings]] resolves through `forLauncher` too; [[ports]] always refuses, because ports built
 * outside [[start]] would hold clients nobody closes.
 */
final class ProductionLauncher private[services] (seams: ProductionLauncher.Seams)
    extends ServiceProvider {

  /** The production seams ([[ProductionLauncher.Seams.system]]). */
  def this() = this(ProductionLauncher.Seams.system)

  private def configured(kind: ServiceKind): Either[String, LauncherSettings] =
    for {
      _ <- ProductionMapping.keyOf(kind)
      c <- ProductionLauncher
        .guard("configuration")(seams.loadConfig())
        .flatMap(
          _.left.map(es => s"configuration: ${es.map(_.message).mkString("; ")}")
        )
      ls <- ProductionMapping.resolveLauncher(c, kind)
    } yield ls

  def settings(kind: ServiceKind): Either[String, ServiceSettings] =
    configured(kind).map(ls => ProductionMapping.serviceSettings(ls.process.service))

  def ports(kind: ServiceKind): Either[String, ServicePorts] =
    Left(
      s"${kind.tag}: the production launcher composes ports only inside start, which owns and " +
        "closes the storage clients"
    )

  override def start(kind: ServiceKind): Either[String, RunningService] =
    for {
      ls <- configured(kind)
      keys <- ProductionLauncher
        .guard("apiKeys")(seams.apiKeys(ls.apiKeys))
        .flatMap(identity)
        .flatMap(k => Option(k).toRight("apiKeys: the key source builder returned none"))
      keyRelease = keys match {
        case c: AutoCloseable => Release.of(() => c.close())
        case _ => Release.none
      }
      clients <- ProductionLauncher
        .guard("storage clients")(seams.openClients())
        .flatMap(identity)
        .flatMap(c => Option(c).toRight("storage clients: none were opened"))
        .left
        .map { e =>
          keyRelease.run(); e
        }
      release = Release.of(() => clients.close()).andThen(keyRelease)
      storage <- ProductionLauncher
        .guard("storage clients")(LauncherMapping.storage(ls, clients))
        .flatMap(identity)
        .left
        .map { e =>
          release.run(); e
        }
      workflow <- ProductionLauncher.guard("workflow")(seams.workflow(ls)).left.map { e =>
        release.run(); e
      }
      deps = storage.copy(
        apiKeys = Some(keys),
        workflow = workflow,
        accessLog = seams.accessLog,
        clock = seams.clock,
        ids = seams.ids
      )
      running <- ProductionServiceProvider.launcher(kind, ls, deps).startReleasing(kind, release)
    } yield running
}

object ProductionLauncher {

  /** A seam that throws is a value-free refusal (the exception class only). */
  private[services] def guard[A](what: String)(a: => A): Either[String, A] =
    try Right(a)
    catch { case NonFatal(e) => Left(s"$what: failed (${e.getClass.getSimpleName})") }

  /**
   * What the launcher takes from its environment. Production: [[Seams.system]]; tests replace each
   * one with a stub.
   *
   * @param apiKeys
   *   builds the Secrets Manager `ApiKeyDigestSource` from `services.apiKeys.*` (SV-INT-03b)
   * @param workflow
   *   the query-jobs workflow factory for the resolved settings (None: not built, fail closed)
   */
  final case class Seams(
      loadConfig: () => Either[Vector[ConfigError], ScholarChainConfig],
      apiKeys: ApiKeySourceSettings => Either[String, ApiKeyDigestSource],
      openClients: () => Either[String, LauncherClients],
      workflow: LauncherSettings => Option[RuntimeStores => Either[String, QueryWorkflow]],
      accessLog: AccessLog,
      clock: ServiceClock,
      ids: IdSource
  )

  object Seams {

    /**
     * Real production adapters. Client construction sends no request. Missing workflow config fails
     * closed; neither local demo model nor in-memory storage can be selected here.
     */
    def system: Seams = Seams(
      loadConfig = () => ConfigLoader.loadFromSystem(),
      apiKeys = SecretsManagerKeys.open,
      openClients = () => LauncherClients.open(),
      workflow =
        ls => Option.when(ls.workflow.isDefined)(stores => ProductionWorkflow.build(ls, stores)),
      accessLog = AccessLog.slf4j(),
      clock = ServiceClock.system,
      ids = IdSource.secure()
    )
  }
}
