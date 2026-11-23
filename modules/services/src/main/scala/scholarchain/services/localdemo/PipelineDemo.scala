package scholarchain.services.localdemo

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path, Paths}

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import scholarchain.core.*
import scholarchain.core.config.{AnalysisRunSettings, ConfigLoader}
import scholarchain.rag.agents.{AgentClock, CollaborationWorkflow, ServingContext}
import scholarchain.rag.semantic.NonProductionHashedTokenEmbedder
import scholarchain.services.*
import scholarchain.storage.fake.*
import scholarchain.storage.load.*
import scholarchain.storage.local.LocalRunSource
import scholarchain.storage.model.*
import scholarchain.storage.port.{Clock, Jitter, Sleeper}

/**
 * A local reader of the human-copied, verified Phase 6 DBLP-derived dev artifacts. Never constructs
 * an AWS client. Storage and models remain explicitly non-production. The separate launcher
 * supplies storage's optional Parquet libraries; the normal service package does not gain
 * Hadoop/Parquet or select this entry point.
 */
object PipelineDemo {
  val ProfileHeader = "X-ScholarChain-Profile" -> "pipeline-demo; DBLP-DERIVED; NON-PRODUCTION"
  val Usage = "PipelineDemoMain --data-root <phase06 fixture directory> [--port <0..65535>]"
  val AcceptedPartsSha256 = "749763adb05af272a3c0c5473ed3d90d071a3e762d67ef70ebef84295cc94907"
  val AcceptedRunId = "run:a65f3f38b597fc4709fe24e0ddbd12ae09a0c79ad54f9d1f5378c06cfe8a8c40"

  final case class Args(dataRoot: Path, port: Int)
  final case class Loaded(
      stores: scholarchain.storage.port.RuntimeStores,
      serving: ServingContext,
      window: TimeWindow,
      report: OpeningLoadReport,
      graph: InMemoryGraphStore
  )
  final case class Started(running: RunningService, loaded: Loaded) {
    def port: Int = running.port
    def stop(): Unit = running.stop()
  }

  def parseArgs(args: Seq[String]): Either[String, Args] = args match {
    case Seq("--data-root", root) => Right(Args(Paths.get(root), LocalDemo.DefaultPort))
    case Seq("--data-root", root, "--port", p) =>
      p.toIntOption
        .filter(n => n >= 0 && n <= 65535 && p.forall(_.isDigit))
        .map(n => Args(Paths.get(root), n))
        .toRight(Usage)
    case _ => Left(Usage)
  }

  private def checked[A](e: Either[StoreError, A]): A =
    e.fold(p => throw new IllegalArgumentException(p.message), identity)

  private def onlyDirectory(parent: Path, prefix: String): Path = {
    val dirs = Files.list(parent)
    val found =
      try
        dirs
          .iterator()
          .asScala
          .filter(p =>
            p.getFileName.toString
              .startsWith(prefix) && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
          )
          .toVector
      finally dirs.close()
    require(found.size == 1, s"$parent: expected exactly one $prefix directory")
    found.head
  }

  private def source(path: Path, files: Int, bytes: Long, depth: Int) =
    checked(LocalRunSource.open(path, checked(SourceBounds.of(files, bytes, depth))))

  /**
   * Bounded verification and the existing production loader into local in-memory stores. No
   * synthetic collaboration facts are substituted on any refusal.
   */
  def load(root: Path): Either[String, Loaded] = try {
    val runRoot = root.resolve("phase06-run-1-local")
    val run = onlyDirectory(runRoot, "run=")
    val loads = onlyDirectory(runRoot, "load=")
    val runSource = source(run, 100, 8L * 1024 * 1024, 2)
    // Bind the profile's real-data claim to the human-verified part manifest, rather than
    // accepting an arbitrary resealed synthetic directory with the same layout.
    require(
      checked(runSource.readAtMost("_parts.json", 1024 * 1024)).sha256 == AcceptedPartsSha256,
      "the analysis parts are not the accepted Phase 6 dev artifact"
    )
    val grouping = onlyDirectory(root.resolve("phase06-grouping-dev"), "run=")
    val curated =
      onlyDirectory(onlyDirectory(root.resolve("phase06-curated-dev"), "snapshot="), "run=")
    val configFile = root.resolve("phase06-analysis-settings/analysis-dev.conf")
    require(Files.size(configFile) <= 65536, "analysis settings exceed local bound")
    val config = ConfigLoader
      .resolve(
        Vector(configFile.toString -> Files.readString(configFile, StandardCharsets.UTF_8)),
        Map.empty
      )
      .fold(e => throw new IllegalArgumentException(e.map(_.message).mkString("; ")), identity)
    val settings = AnalysisRunSettings
      .from(config)
      .fold(e => throw new IllegalArgumentException(e.map(_.message).mkString("; ")), identity)
    val window = settings.windows.maxBy(w => w.endYear - w.startYear)
    val dimension = LocalDemoWorld.Dimension
    val limits = VectorLimits(100, 1024, 2048, 40960, 50, 10, 50, 30)
    val graph = new InMemoryGraphStore
    val kv = new InMemoryKeyValueStore(10000)
    val ledger = new InMemoryKeyValueStore(10000)
    val objects = new InMemoryObjectStore(10000)
    val vectors = new InMemoryVectorStore(
      VectorIndexes.all.map(i =>
        i -> IndexInfo(
          i.name,
          dimension,
          DistanceMetric.Cosine,
          MetadataField.nonFilterableFields.map(_.key)
        )
      ),
      limits
    )
    val layout =
      S3Layout
        .from(
          S3LayoutSettings(
            Zone.values.map(z => z -> ZoneLocation("local-pipeline-demo", z.tag)).toMap,
            1024
          )
        )
        .fold(e => throw new IllegalArgumentException(e.mkString("; ")), identity)
    val vectorConfig = VectorLoadConfig
      .of(
        limits,
        VectorIndexes.all.map(i => VectorIndexSettings(i.name, dimension, DistanceMetric.Cosine)),
        50,
        checked(RetryPolicy.of(1, 0L, 0L)),
        5,
        65536,
        512,
        EmbeddingPolicy.SyntheticTestOnly,
        10000
      )
      .fold(e => throw new IllegalArgumentException(e.mkString("; ")), identity)
    val now = System.currentTimeMillis()
    val loadConfig = OpeningLoadConfig(
      settings,
      checked(ParquetReadLimits.of(1024 * 1024, 8L * 1024 * 1024, 100000L, 8 * 1024 * 1024)),
      checked(UpstreamBounds.of(16L * 1024 * 1024, 100000)),
      65536L,
      checked(KeyValueLoadLimits.of(400 * 1024)),
      layout,
      vectorConfig,
      Some(checked(EntityLinkLoadConfig.of(now / 1000 + 86400, 10000, 8)))
    )
    val result = OpeningLoad.run(
      OpeningLoadSources(
        runSource,
        source(loads, 10, 1024L * 1024, 1),
        source(grouping, 20, 4L * 1024 * 1024, 3),
        source(curated, 200, 4L * 1024 * 1024, 5),
        source(root.resolve("phase06-benchmark-pairs"), 5, 1024L * 1024, 1),
        "benchmark-pairs-dev.v1.json"
      ),
      loadConfig,
      OpeningLoadTargets(
        graph,
        kv,
        ledger,
        objects,
        vectors,
        new NonProductionHashedTokenEmbedder(dimension),
        new Clock { def nowMillis() = now },
        new Sleeper { def sleep(millis: Long): Unit = () },
        new Jitter { def uniform(n: Long) = 0L }
      )
    )
    require(result.report.runId.contains(AcceptedRunId), "unexpected analysis run")
    result.runtime.toRight("pipeline load refused: " + result.report.toString).flatMap { runtime =>
      ServingContext
        .of(AcceptedRunId, "dblp-2026-10-03")
        .map(s => Loaded(runtime, s, window, result.report, graph))
    }
  } catch {
    case NonFatal(e) => Left(s"pipeline demo: ${e.getClass.getSimpleName}: ${e.getMessage}")
  }

  def start(args: Args): Either[String, Started] = for {
    data <- load(args.dataRoot)
    keys <- LocalDemoKeys.load()
    wc <- LocalDemo.workflowConfig(data.window)
    access = StoreAccess.serialized()
    workflow = QueryWorkflow.of(
      new CollaborationWorkflow(data.stores, data.serving, wc, AgentClock.system),
      access
    )
    runtime <- ServiceRuntime.build(
      data.stores,
      workflow,
      keys,
      LocalDemo.config.copy(graphDefaultWindow = data.window),
      access,
      AccessLog.slf4j(),
      ServiceClock.system,
      IdSource.secure(),
      ServiceKind.All
    )
    server <- HttpServerAdapter
      .start(runtime.router, LocalDemo.server(args.port), Vector(ProfileHeader))
      .left
      .map { e =>
        runtime.close(); e
      }
  } yield Started(
    new RunningService(
      ServiceKind.All,
      runtime,
      server,
      LocalDemo.DrainMillis,
      LocalDemo.GraceSeconds
    ),
    data
  )
}

object PipelineDemoMain {
  def main(args: Array[String]): Unit = {
    val started = for {
      a <- PipelineDemo.parseArgs(args.toVector)
      s <- PipelineDemo.start(a)
    } yield s
    started match {
      case Left(e) => System.err.println(e); sys.exit(1)
      case Right(s) =>
        Runtime.getRuntime.addShutdownHook(new Thread(() => s.stop(), "pipeline-demo-shutdown"))
        println("ScholarChain DBLP-DERIVED LOCAL DEMO -- NON-PRODUCTION")
        println(
          "Real bounded Phase 6 dev data; in-memory stores, template answer model and hashed-token stand-in embeddings."
        )
        println(
          "No AWS, no paid model calls. This is not a production deployment or corpus-scale evaluation."
        )
        println(s"profile=pipeline-demo listening=http://127.0.0.1:${s.port}")
        println(s"analysisRunId=${s.loaded.serving.analysisRunId} window=${s.loaded.window}")
        s.loaded.report.stores.foreach(r =>
          println(s"load=${r.target.tag} status=${r.status.tag} written=${r.written}")
        )
        println("Example: Who are the coauthors of Ailing Zeng?")
    }
  }
}
