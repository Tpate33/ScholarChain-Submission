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
    ids: IdSource,
    // SV-INT-02: the durable job and idempotency stores of a QueryJobs process (production);
    // None composes the in-memory pair (local, one replica).
    state: Option[ServiceStatePorts] = None
)

/**
 * Supplies settings and ports to a service main (SE-P10-03 Part B). SV-INT-02: the production
 * composition is [[ProductionServiceProvider]]. SV-INT-03: the three production mains use
 * [[ProductionLauncher]] directly (core `ProductionSettings.forLauncher`, one storage
 * `StorageClients` bundle, released on stop and on a failed start); they do not search the
 * classpath, so no other provider (and never the NON-PRODUCTION local demo) can be selected.
 * [[ServiceMain.loadProvider]] (`java.util.ServiceLoader`) remains for tests and tools only.
 */
trait ServiceProvider {
  def settings(kind: ServiceKind): Either[String, ServiceSettings]
  def ports(kind: ServiceKind): Either[String, ServicePorts]

  /**
   * Composes and starts `kind`. Default: [[settings]], [[ports]], then [[ServiceMain.start]]. A
   * provider that owns resources (clients, pools) overrides it to release them exactly once, on a
   * failed start and when the process stops.
   */
  def start(kind: ServiceKind): Either[String, RunningService] =
    ServiceMain.startComposed(kind, this)
}

/**
 * SV-INT-03 (G5): an idempotent release action. [[run]] runs every action once, in order, each
 * attempted even when an earlier one throws; later calls do nothing.
 */
final class Release private (actions: Vector[() => Unit]) {
  private val done = new java.util.concurrent.atomic.AtomicBoolean(false)

  def run(): Unit =
    if (done.compareAndSet(false, true))
      actions.foreach(a =>
        try a()
        catch { case NonFatal(_) => () }
      )

  def hasRun: Boolean = done.get

  /** This release, then `next` (each still at most once). */
  def andThen(next: Release): Release = new Release(Vector(() => run(), () => next.run()))
}

object Release {
  def none: Release = new Release(Vector.empty)
  def of(actions: (() => Unit)*): Release = new Release(actions.toVector)
}

/** One started service process: its runtime and its HTTP server. */
final class RunningService private[services] (
    val kind: ServiceKind,
    val runtime: ServiceRuntime,
    server: HttpServerAdapter,
    drainMillis: Long,
    graceSeconds: Int,
    // SV-INT-03 (G5): what the process owns besides its runtime (the launcher's storage clients);
    // run once, after the runtime closed.
    release: Release = Release.none
) {
  def port: Int = server.port

  /**
   * Graceful shutdown, in order:
   *   1. drain: readiness fails and /v1 routes answer 503; the server still accepts and serves (so
   *      `/readyz` is seen) for `drainMillis`;
   *   2. the server stops accepting and waits at most `graceSeconds` for in-flight exchanges;
   *   3. the HTTP worker pool and then the admission pool each get at most `graceSeconds` to finish
   *      before they are interrupted;
   *   4. the service pools are interrupted;
   *   5. SV-INT-03: the process's other resources are released (the storage clients), once.
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
    try {
      server.stop(graceSeconds)
      runtime.close()
    } finally release.run()
  }
}

object ServiceMain {

  /** Composes and starts one service from explicit settings and ports. */
  def start(
      kind: ServiceKind,
      settings: ServiceSettings,
      ports: ServicePorts
  ): Either[String, RunningService] = start(kind, settings, ports, Release.none)

  /**
   * SV-INT-03 (G5): as above; `release` runs exactly once: at once when the start fails (refused
   * settings, runtime or bind), else when the started process stops.
   */
  private[services] def start(
      kind: ServiceKind,
      settings: ServiceSettings,
      ports: ServicePorts,
      release: Release
  ): Either[String, RunningService] = {
    val started =
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
            ports.ownership,
            ports.state.orNull
          )
          server <- HttpServerAdapter.start(runtime.router, settings.server).left.map { e =>
            runtime.close(); e
          }
        } yield new RunningService(
          kind,
          runtime,
          server,
          settings.drainMillis,
          settings.graceSeconds,
          release
        )
    started.left.foreach(_ => release.run())
    started
  }

  /** Starts one service from a provider ([[ServiceProvider.start]]). */
  def start(kind: ServiceKind, provider: ServiceProvider): Either[String, RunningService] =
    provider.start(kind)

  /** The default [[ServiceProvider.start]]: settings, ports, then start. */
  private[services] def startComposed(
      kind: ServiceKind,
      provider: ServiceProvider
  ): Either[String, RunningService] =
    for {
      s <- provider.settings(kind)
      p <- provider.ports(kind)
      r <- start(kind, s, p)
    } yield r

  /**
   * The one provider registered on the classpath (`java.util.ServiceLoader`). Tests and tools only:
   * since SV-INT-03 the production mains name their provider ([[productionProvider]]).
   */
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

  /**
   * SV-INT-03: the provider of the three production mains: [[ProductionLauncher]] over the process
   * environment (`SCHOLARCHAIN_CONFIG_FILES` and `SCHOLARCHAIN_CFG_*`), core `forLauncher`, and
   * storage `StorageClients.open()`.
   */
  private[services] val productionProvider: () => Either[String, ServiceProvider] =
    () => Right(new ProductionLauncher())

  private[services] def mainOf(kind: ServiceKind, args: Array[String]): Unit = {
    val code = launch(kind, args, productionProvider, m => System.err.println(m))
    if (code != 0) sys.exit(code)
  }
}

/**
 * QueryJobs: POST /v1/query, GET /v1/jobs/{jobId}, GET /v1/jobs/{jobId}/result, plus ops.
 * Production entry point ([[ProductionLauncher]]); exit code 1 with a value-free reason on stderr
 * when the configuration or a production dependency is missing.
 */
object QueryJobsMain {
  def main(args: Array[String]): Unit = ServiceMain.mainOf(ServiceKind.QueryJobs, args)
}

/**
 * EvidenceExplain: GET /v1/evidence/{evidenceId}, GET /v1/explain/{queryId}, plus ops. Production
 * entry point ([[ProductionLauncher]]).
 */
object EvidenceExplainMain {
  def main(args: Array[String]): Unit = ServiceMain.mainOf(ServiceKind.EvidenceExplain, args)
}

/**
 * Graph: GET /v1/graph/nodes/{nodeId}, GET /v1/graph/nodes/{nodeId}/neighbors, plus ops. Production
 * entry point ([[ProductionLauncher]]).
 */
object GraphMain {
  def main(args: Array[String]): Unit = ServiceMain.mainOf(ServiceKind.Graph, args)
}
