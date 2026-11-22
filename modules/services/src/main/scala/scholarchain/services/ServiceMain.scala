package scholarchain.services

import java.util.ServiceLoader

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import scholarchain.storage.port.RuntimeStores

/**
 * The settings of one service process: its bounds, its HTTP bind (host and port from configuration;
 * port 0 binds an ephemeral port) and its shutdown timing.
 *
 * @param drainMillis
 *   SE-P10-04 n3: how long the process keeps serving after readiness starts failing, so a load
 *   balancer sees `/readyz` 503 and stops routing before the server closes
 * @param graceSeconds
 *   how long the server waits for in-flight exchanges, and then each HTTP pool for its threads
 */
final case class ServiceSettings(
    config: ServiceConfig,
    server: HttpServerAdapter.Settings,
    drainMillis: Long,
    graceSeconds: Int
)

/**
 * The ports one service process composes. `stores` is required by EvidenceExplain and Graph,
 * `workflow` by QueryJobs; `ownership` is shared between the QueryJobs process (records) and the
 * EvidenceExplain process (checks), so in production it is a shared durable store.
 */
final case class ServicePorts(
    stores: Option[RuntimeStores],
    workflow: Option[QueryWorkflow],
    keys: ApiKeyDigestSource,
    ownership: QueryOwnership,
    access: StoreAccess,
    accessLog: AccessLog,
    clock: ServiceClock,
    ids: IdSource
)

/**
 * Supplies settings and ports to a service main (SE-P10-03 Part B). No production implementation
 * exists yet: it needs the production store adapters and workflow model (storage-engineer,
 * rag-engineer), the Secrets Manager key source and deployment configuration (infra-engineer) and
 * core configuration keys for `services.*` (domain-engineer). A main finds it with
 * `java.util.ServiceLoader` (one `META-INF/services/scholarchain.services.ServiceProvider` entry on
 * the classpath); tests pass one directly.
 */
trait ServiceProvider {
  def settings(kind: ServiceKind): Either[String, ServiceSettings]
  def ports(kind: ServiceKind): Either[String, ServicePorts]
}

/** One started service process: its runtime and its HTTP server. */
final class RunningService private[services] (
    val kind: ServiceKind,
    val runtime: ServiceRuntime,
    server: HttpServerAdapter,
    drainMillis: Long,
    graceSeconds: Int
) {
  def port: Int = server.port

  /**
   * Graceful shutdown, in order:
   *   1. drain: readiness fails and /v1 routes answer 503; the server still accepts and serves (so
   *      `/readyz` is seen) for `drainMillis`;
   *   2. the server stops accepting and waits at most `graceSeconds` for in-flight exchanges;
   *   3. the HTTP worker pool and then the admission pool each get at most `graceSeconds` to finish
   *      before they are interrupted;
   *   4. the service pools are interrupted.
   *
   * Worst case: `drainMillis + 3 x graceSeconds` (plus thread scheduling), with an exchange that
   * never ends; an interrupt-ignoring workflow call may keep running on a daemon thread after that,
   * but no longer holds the process.
   */
  def stop(): Unit = {
    runtime.drain()
    if (drainMillis > 0) {
      val drained = new java.util.concurrent.CountDownLatch(1)
      drained.await(drainMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
    }
    server.stop(graceSeconds)
    runtime.close()
  }
}

object ServiceMain {

  /** Composes and starts one service from explicit settings and ports. */
  def start(
      kind: ServiceKind,
      settings: ServiceSettings,
      ports: ServicePorts
  ): Either[String, RunningService] =
    if (settings.graceSeconds < 0) Left("graceSeconds: must be >= 0")
    else if (settings.drainMillis < 0) Left("drainMillis: must be >= 0")
    else
      for {
        runtime <- ServiceRuntime.build(
          ports.stores.orNull,
          ports.workflow.orNull,
          ports.keys,
          settings.config,
          ports.access,
          ports.accessLog,
          ports.clock,
          ports.ids,
          kind,
          ports.ownership
        )
        server <- HttpServerAdapter.start(runtime.router, settings.server).left.map { e =>
          runtime.close(); e
        }
      } yield new RunningService(
        kind,
        runtime,
        server,
        settings.drainMillis,
        settings.graceSeconds
      )

  /** Starts one service from a provider. */
  def start(kind: ServiceKind, provider: ServiceProvider): Either[String, RunningService] =
    for {
      s <- provider.settings(kind)
      p <- provider.ports(kind)
      r <- start(kind, s, p)
    } yield r

  /** The one provider registered on the classpath. */
  def loadProvider(loader: ClassLoader): Either[String, ServiceProvider] =
    try
      ServiceLoader.load(classOf[ServiceProvider], loader).iterator().asScala.toList match {
        case List(p) => Right(p)
        case Nil =>
          Left(
            "no scholarchain.services.ServiceProvider on the classpath: the production store " +
              "adapters, workflow model, key source and service configuration are not built yet"
          )
        case ps => Left(s"${ps.size} ServiceProviders on the classpath; exactly one is required")
      }
    catch { case NonFatal(e) => Left(s"provider failed to load: ${e.getClass.getSimpleName}") }

  /**
   * The body of every main: no arguments (configuration comes from the provider), start the
   * service, register graceful shutdown on JVM exit. Returns the process exit code (0: started; the
   * JDK HTTP server's non-daemon dispatcher keeps the process alive).
   */
  def launch(
      kind: ServiceKind,
      args: Array[String],
      provider: () => Either[String, ServiceProvider],
      err: String => Unit
  ): Int =
    if (args.nonEmpty) {
      err(s"${kind.tag}: takes no arguments (settings come from the ServiceProvider)")
      2
    } else
      provider().flatMap(start(kind, _)) match {
        case Left(e) =>
          err(s"${kind.tag}: not started: $e")
          1
        case Right(running) =>
          Runtime.getRuntime.addShutdownHook(new Thread(() => running.stop()))
          0
      }

  private[services] def mainOf(kind: ServiceKind, args: Array[String]): Unit = {
    val code = launch(
      kind,
      args,
      () => loadProvider(getClass.getClassLoader),
      m => System.err.println(m)
    )
    if (code != 0) sys.exit(code)
  }
}

/** QueryJobs: POST /v1/query, GET /v1/jobs/{jobId}, GET /v1/jobs/{jobId}/result, plus ops. */
object QueryJobsMain {
  def main(args: Array[String]): Unit = ServiceMain.mainOf(ServiceKind.QueryJobs, args)
}

/** EvidenceExplain: GET /v1/evidence/{evidenceId}, GET /v1/explain/{queryId}, plus ops. */
object EvidenceExplainMain {
  def main(args: Array[String]): Unit = ServiceMain.mainOf(ServiceKind.EvidenceExplain, args)
}

/** Graph: GET /v1/graph/nodes/{nodeId}, GET /v1/graph/nodes/{nodeId}/neighbors, plus ops. */
object GraphMain {
  def main(args: Array[String]): Unit = ServiceMain.mainOf(ServiceKind.Graph, args)
}
