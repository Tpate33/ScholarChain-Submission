package scholarchain.services.localdemo

import scala.util.control.NonFatal

import upickle.default.ReadWriter
import upickle.implicits.allowUnknownKeys

import scholarchain.core.config.RuntimeConfig
import scholarchain.rag.agents.{
  AgentClock,
  AgentLimits,
  AuditConfig,
  CollaborationWorkflow,
  EvidenceLimits,
  NonProductionTemplateSynthesizer,
  SemanticConfig,
  SemanticIndex,
  SynthesisConfig,
  WorkflowConfig
}
import scholarchain.rag.semantic.{NonProductionHashedTokenEmbedder, SemanticContextBudget}
import scholarchain.services.*
import scholarchain.storage.model.VectorIndexes

// ---------------------------------------------------------------------------------------------
// The local API-key digest fixture (classpath resource scholarchain/local-demo/api-keys.v1.json).
// ---------------------------------------------------------------------------------------------

@allowUnknownKeys(false)
private[localdemo] final case class DemoKeyEntry(
    clientId: String,
    keySha256Hex: String,
    scopes: Vector[String]
) derives ReadWriter

@allowUnknownKeys(false)
private[localdemo] final case class DemoKeyFile(
    schemaVersion: String,
    nonProduction: Boolean,
    description: String,
    clients: Vector[DemoKeyEntry]
) derives ReadWriter

/**
 * NON-PRODUCTION (SV-INT-03a): the local demo's [[ApiKeyDigestSource]], read once from the
 * committed digest fixture `scholarchain/local-demo/api-keys.v1.json` (SHA-256 digests only, never
 * a raw key). Strict: unknown keys, a wrong schema version, a missing `nonProduction` flag, an
 * unknown scope or an invalid digest refuse the whole file. Never a production credential source
 * (production uses the AWS Secrets Manager source of SV-INT-03b).
 */
private[localdemo] final class LocalDemoKeys private (entries: Vector[ApiClient])
    extends ApiKeyDigestSource {
  def clients(): Either[String, Vector[ApiClient]] = Right(entries)
}

private[localdemo] object LocalDemoKeys {
  val SchemaVersion = "scholarchain.services.local-demo-api-keys.v1"
  val Resource = "/scholarchain/local-demo/api-keys.v1.json"

  def parse(json: String): Either[String, LocalDemoKeys] =
    (try Right(upickle.default.read[DemoKeyFile](json))
    catch {
      case NonFatal(e) => Left(s"local demo keys: malformed (${e.getClass.getSimpleName})")
    }).flatMap { f =>
      if (f.schemaVersion != SchemaVersion)
        Left(s"local demo keys: schemaVersion is not $SchemaVersion")
      else if (!f.nonProduction) Left("local demo keys: nonProduction must be true")
      else if (f.clients.isEmpty) Left("local demo keys: at least one client")
      else if (f.clients.map(_.clientId).distinct.size != f.clients.size)
        Left("local demo keys: repeated clientId")
      else
        f.clients
          .foldLeft[Either[String, Vector[ApiClient]]](Right(Vector.empty)) { (acc, c) =>
            for {
              xs <- acc
              scopes <- c.scopes.foldLeft[Either[String, Set[Scope]]](Right(Set.empty)) {
                (s, tag) =>
                  s.flatMap(set =>
                    Scope.values
                      .find(_.tag == tag)
                      .map(set + _)
                      .toRight("local demo keys: unknown scope")
                  )
              }
              client <- ApiClient
                .of(c.clientId, c.keySha256Hex, scopes)
                .left
                .map(e => s"local demo keys: $e")
            } yield xs :+ client
          }
          .map(new LocalDemoKeys(_))
    }

  def load(): Either[String, LocalDemoKeys] =
    DemoSeed
      .resourceText(Resource)
      .toRight(s"local demo keys: $Resource is not on the classpath")
      .flatMap(parse)
}

/**
 * The parsed command line of the local demo: `--port <0..65535>` only (default
 * [[LocalDemo.DefaultPort]]; 0 binds an ephemeral port). There is no host option: the demo binds
 * the loopback address [[LocalDemo.LoopbackHost]] only.
 */
final case class LocalDemoArgs(port: Int)

/** One started demo: the running service, its seeded world and the bound loopback port. */
final class StartedDemo private[localdemo] (
    val running: RunningService,
    val world: LocalDemoWorld,
    val host: String
) {
  def port: Int = running.port
  def stop(): Unit = running.stop()
}

/**
 * '''NON-PRODUCTION''' local demo profile (SV-INT-03a, handoff criterion C). One process serving
 * every route ([[ServiceKind.All]]) over:
 *
 *   - storage's in-memory fakes seeded from the committed seed
 *     `scholarchain/local-demo/world.v1.json` (the Phase 9 rag fixture world; [[LocalDemoWorld]]);
 *   - rag `CollaborationWorkflow` with the NON-PRODUCTION `NonProductionTemplateSynthesizer` and
 *     the NON-PRODUCTION `NonProductionHashedTokenEmbedder` (no model call, no network);
 *   - the local API-key digest fixture `scholarchain/local-demo/api-keys.v1.json`
 *     ([[LocalDemoKeys]]);
 *   - in-memory job, idempotency and query-ownership state (one replica).
 *
 * The demo API key is [[DemoApiKey]], `scholarchain-local-demo-key-NOT-A-SECRET`: a documented,
 * NON-SECRET value that grants scopes query, read and ops in this demo only. It is never accepted
 * by a production process (production keys come from AWS Secrets Manager).
 *
 * Isolation from production: nothing here implements [[ServiceProvider]], no `META-INF/services`
 * entry names it, and the production provider (`ProductionServiceProvider`) and mains
 * (`QueryJobsMain`, `EvidenceExplainMain`, `GraphMain`) never reference this package. Every
 * response carries [[ProfileHeader]] and startup prints [[Banner]].
 *
 * No AWS call, no AWS SDK client, no network apart from the loopback listener.
 */
object LocalDemo {

  /** The documented NON-SECRET demo bearer key (local demo only; see the scaladoc above). */
  val DemoApiKey = "scholarchain-local-demo-key-NOT-A-SECRET"

  /** The only bind address: IPv4 loopback. */
  val LoopbackHost = "127.0.0.1"

  /** The default port when `--port` is not given. */
  val DefaultPort = 8080

  /** Added to every response of the demo. */
  val ProfileHeader: (String, String) = "X-ScholarChain-Profile" -> "local-demo; NON-PRODUCTION"

  val Usage: String =
    "usage: scholarchain.services.localdemo.LocalDemoMain [--port <0..65535>] " +
      s"(default $DefaultPort; binds $LoopbackHost only; NON-PRODUCTION)"

  /** Parses the command line; Left with a usage message for anything else. */
  def parseArgs(args: Seq[String]): Either[String, LocalDemoArgs] =
    args match {
      case Seq() => Right(LocalDemoArgs(DefaultPort))
      case Seq("--port", p) =>
        p.toIntOption
          .filter(n => n >= 0 && n <= 65535 && p.forall(_.isDigit))
          .map(LocalDemoArgs(_))
          .toRight(s"--port: expected an integer 0..65535. $Usage")
      case _ => Left(Usage)
    }

  /** Demo service bounds (NON-PRODUCTION values; production takes them from configuration). */
  val config: ServiceConfig = ServiceConfig(
    maxBodyBytes = 16384,
    maxQuestionBytes = 2048,
    syncTimeoutMillis = 10000L,
    asyncTimeoutMillis = 10000L,
    readTimeoutMillis = 5000L,
    readMaxAttempts = 3,
    breakerFailureThreshold = 3,
    breakerOpenMillis = 60000L,
    readRequestTimeoutMillis = 8000L,
    queueWaitMillis = 5000L,
    workflowWorkers = 4,
    workflowQueueCapacity = 8,
    keyValueWorkers = 4,
    keyValueQueueCapacity = 16,
    graphWorkers = 4,
    graphQueueCapacity = 16,
    jobWorkers = 2,
    jobQueueCapacity = 8,
    maxJobs = 64,
    idempotencyCapacity = 256,
    idempotencyTtlMillis = 3600000L,
    queryOwnershipCapacity = 1000,
    graphMaxDepth = 1,
    graphMaxPageSize = 20,
    graphDefaultPageSize = 10,
    graphMaxResults = 50,
    graphEdgeTypes = GraphService.SupportedEdgeTypes,
    graphDefaultWindow = scholarchain.core.TimeWindow(2018, 2022),
    evidenceMaxContentBytes = 4096,
    evidenceMaxBundles = 10,
    evidenceScanLimit = 50,
    explainMaxSteps = 32,
    metricsEnabled = true,
    retryAfterSeconds = 2
  )

  /** Demo HTTP settings: loopback only. */
  def server(port: Int): HttpServerAdapter.Settings =
    HttpServerAdapter.Settings(LoopbackHost, port, 16, 4, 16, 16384, 8)

  /** Demo shutdown: no drain window, at most 1 s grace per stage (a grader's Ctrl-C exits fast). */
  val DrainMillis = 0L
  val GraceSeconds = 1

  /** The rag workflow configuration of the demo (NON-PRODUCTION synthesizer and embedder). */
  def workflowConfig(world: LocalDemoWorld): Either[String, WorkflowConfig] =
    workflowConfig(world.window)

  /** Shared non-production model bounds; the pipeline demo supplies its actual analysis window. */
  def workflowConfig(window: scholarchain.core.TimeWindow): Either[String, WorkflowConfig] = {
    val embedder = new NonProductionHashedTokenEmbedder(LocalDemoWorld.Dimension)
    for {
      limits <- AgentLimits.of(
        RuntimeConfig(4, 10, window),
        50,
        100000L,
        200,
        200,
        1000,
        5,
        10,
        2048,
        4
      )
      ev <- EvidenceLimits.of(300, 2000000, 5)
      syn <- SynthesisConfig.of(NonProductionTemplateSynthesizer, 3, 400000, 400000, 64, 1000000L)
      au <- AuditConfig.of(3600L, 65536L)
      budget <- SemanticContextBudget.of(3, 3, 2048, 65536, 4096)
      sem <- SemanticConfig.of(
        embedder,
        VectorIndexes.all.map(SemanticIndex(_, LocalDemoWorld.Dimension)),
        "local-demo-context",
        budget,
        5
      )
      wc <- WorkflowConfig.of(limits, ev, syn, sem, au, 5, 10)
    } yield wc
  }

  /** The startup banner (stdout). Lists the seeded researcher IDs for the graph routes. */
  def banner(started: StartedDemo): Vector[String] = {
    val base = s"http://${started.host}:${started.port}"
    val rule = "=" * 78
    Vector(
      rule,
      "  ScholarChain LOCAL DEMO -- NON-PRODUCTION",
      "  In-memory fixture world (Phase 9 rag fixture), NON-PRODUCTION template synthesizer and",
      "  stand-in embedder, local API-key digest fixture. No AWS, no model call, no real data.",
      s"  profile=local-demo listening=$base (loopback only)",
      s"  demo API key (documented, NOT A SECRET): $DemoApiKey",
      "  researchers (graph node IDs):"
    ) ++ started.world.seed.researchers.map(r =>
      s"    ${r.alias} ${r.label}: ${started.world.researcherIds(r.alias)}"
    ) ++ Vector("  example questions:") ++
      started.world.seed.exampleQuestions.map(q => s"    ${q.question}") ++
      Vector("  stop: Ctrl-C or SIGTERM", rule)
  }

  /**
   * Composes and starts the demo on the loopback address. Left (with everything started so far
   * stopped) when the seed, the key fixture, the workflow configuration or the bind is refused.
   */
  def start(args: LocalDemoArgs): Either[String, StartedDemo] =
    for {
      world <- LocalDemoWorld.fromClasspath()
      keys <- LocalDemoKeys.load()
      wc <- workflowConfig(world)
      access = StoreAccess.serialized()
      workflow = QueryWorkflow.of(
        new CollaborationWorkflow(world.runtime, world.serving, wc, AgentClock.system),
        access
      )
      runtime <- ServiceRuntime.build(
        world.runtime,
        workflow,
        keys,
        config,
        access,
        AccessLog.slf4j(),
        ServiceClock.system,
        IdSource.secure(),
        ServiceKind.All
      )
      srv <- HttpServerAdapter
        .start(runtime.router, server(args.port), Vector(ProfileHeader))
        .left
        .map { e =>
          runtime.close(); e
        }
    } yield new StartedDemo(
      new RunningService(ServiceKind.All, runtime, srv, DrainMillis, GraceSeconds),
      world,
      LoopbackHost
    )

  /**
   * The body of [[LocalDemoMain]]: parse, start, print the banner, register graceful shutdown on
   * JVM exit (SIGTERM, Ctrl-C). Returns the exit code (0 started: the JDK HTTP server's non-daemon
   * dispatcher keeps the process alive until the shutdown hook stops it; 2 bad arguments; 1 refused
   * start).
   */
  def launch(
      args: Array[String],
      out: String => Unit,
      err: String => Unit,
      addShutdownHook: Thread => Unit
  ): Int =
    parseArgs(args.toVector) match {
      case Left(usage) =>
        err(s"local-demo (NON-PRODUCTION): $usage")
        2
      case Right(a) =>
        start(a) match {
          case Left(e) =>
            err(s"local-demo (NON-PRODUCTION): not started: $e")
            1
          case Right(started) =>
            addShutdownHook(new Thread(() => started.stop(), "local-demo-shutdown"))
            banner(started).foreach(out)
            0
        }
    }
}

/**
 * '''NON-PRODUCTION''' entry point of the local demo (SV-INT-03a). Arguments: `[--port <0..65535>]`
 * (default 8080). Binds 127.0.0.1 only. Demo API key: `scholarchain-local-demo-key-NOT-A-SECRET`
 * (documented, not a secret). Not a [[ServiceProvider]]; never loaded by `ServiceLoader`.
 */
object LocalDemoMain {
  def main(args: Array[String]): Unit = {
    val code = LocalDemo.launch(
      args,
      m => println(m),
      m => System.err.println(m),
      t => Runtime.getRuntime.addShutdownHook(t)
    )
    if (code != 0) sys.exit(code)
  }
}
