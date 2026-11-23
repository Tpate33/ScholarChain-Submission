package scholarchain.services

import java.nio.file.{Files, LinkOption, Path, Paths}

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

import scholarchain.core.{Json, RunId, RunManifest}
import scholarchain.core.config.{
  AnalysisRunSettings,
  ConfigLoader,
  LauncherSettings,
  ProductionLoadSettings,
  ProductionSettings,
  ScholarChainConfig,
  ServiceKindKey
}
import scholarchain.storage.aws.neptune.NeptuneClientSettings
import scholarchain.storage.load.*
import scholarchain.storage.local.LocalRunSource
import scholarchain.storage.model.*
import scholarchain.storage.port.{Clock, Jitter, RunSource}

/**
 * Operational composition of the existing OpeningLoad, not a replacement loader. Default mode
 * validates local configuration/source envelopes only and cannot construct an AWS client. Execution
 * is a separately approved paid mutation: `--execute <configured analysisRunId>`.
 */
object ProductionLoadMain {
  val Usage = "ProductionLoadMain [--validate | --execute <analysisRunId>]"
  final case class Prepared(
      launcher: LauncherSettings,
      sources: OpeningLoadSources,
      config: OpeningLoadConfig,
      writer: NeptuneClientSettings,
      vectorLimits: VectorLimits,
      runId: String,
      partManifestSha256: String
  )

  def parseArgs(args: Seq[String]): Either[String, Option[String]] = args match {
    case Seq() | Seq("--validate") => Right(None)
    case Seq("--execute", runId)
        if scholarchain.core.StableId
          .validate(scholarchain.core.IdKind.Run, runId, "runId")
          .isRight =>
      Right(Some(runId))
    case _ => Left(Usage)
  }

  private def mapped[A](e: Either[StoreError, A]): Either[String, A] =
    e.left.map(_ => "productionLoad: invalid source or loader bounds")

  /** Refuse protected source locations before listing or reading any source file. */
  private[services] def safeSourcePath(path: String): Either[String, Path] = try {
    val p = Paths.get(path).toAbsolutePath.normalize()
    val forbidden = p.iterator().asScala.exists { segment =>
      val n = segment.toString.toLowerCase(java.util.Locale.ROOT)
      n == "gold" || n.contains("heldout") || n == ".aws" || n == ".ssh" || n == ".git" ||
      n == ".claude" || n == ".codex" || n.startsWith(".env") || n.contains("secret") || n.contains(
        "credential"
      )
    }
    if (forbidden) Left("productionLoad: protected source path refused")
    else {
      val ancestors = Iterator.iterate(p)(_.getParent).takeWhile(_ != null).toVector
      if (ancestors.exists(Files.isSymbolicLink(_))) Left("productionLoad: source symlink refused")
      else if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
        Left("productionLoad: source directory unavailable")
      else Right(p)
    }
  } catch { case NonFatal(_) => Left("productionLoad: invalid source path") }

  private def source(path: String, s: ProductionLoadSettings): Either[String, RunSource] = for {
    p <- safeSourcePath(path)
    bounds <- mapped(SourceBounds.of(s.sourceMaxFiles, s.sourceMaxBytes, s.sourceMaxDepth))
    source <- mapped(LocalRunSource.open(p, bounds))
    files <- mapped(source.listFiles())
    _ <-
      if (
        files.exists(f =>
          f.toLowerCase(java.util.Locale.ROOT).contains("heldout") ||
            f.toLowerCase(java.util.Locale.ROOT).contains("credential") || f
              .toLowerCase(java.util.Locale.ROOT)
              .contains("secret")
        )
      )
        Left("productionLoad: protected source file refused")
      else Right(())
  } yield source

  private def producerConfig(s: ProductionLoadSettings): Either[String, ScholarChainConfig] = try {
    val file = Paths.get(s.analysisConfigFile).toAbsolutePath.normalize()
    val name = file.getFileName.toString
    if (
      !name.endsWith(".conf") || name.toLowerCase(java.util.Locale.ROOT).startsWith(".env") ||
      name.toLowerCase(java.util.Locale.ROOT).contains("secret") ||
      name.toLowerCase(java.util.Locale.ROOT).contains("credential") || name
        .toLowerCase(java.util.Locale.ROOT)
        .contains("heldout")
    )
      Left("productionLoad: protected or invalid producer config file refused")
    else
      for {
        directory <- safeSourcePath(file.getParent.toString)
        bounds <- mapped(SourceBounds.of(s.sourceMaxFiles, s.sourceMaxBytes, s.sourceMaxDepth))
        configSource <- mapped(LocalRunSource.open(directory, bounds))
        bytes <- mapped(configSource.readAtMost(name, math.min(s.sourceMaxBytes, 65536L)))
        config <- ConfigLoader
          .resolve(Vector("archived-analysis-config" -> bytes.utf8), Map.empty)
          .left
          .map(_ => "productionLoad: invalid archived analysis configuration")
      } yield config
  } catch {
    case NonFatal(_) => Left("productionLoad: archived analysis configuration unavailable")
  }

  /** Pure local preparation; no clients, credential resolution, provider calls or store writes. */
  def prepare(c: ScholarChainConfig): Either[String, Prepared] = for {
    s <- ProductionLoadSettings
      .from(c)
      .left
      .map(_ => "productionLoad: missing or invalid typed configuration")
    launcher <- ProductionSettings
      .forLauncher(c, ServiceKindKey.QueryJobs)
      .left
      .map(_ => "productionLoad: invalid query launcher configuration")
    workflow <- launcher.workflow.toRight("productionLoad: productionWorkflow required")
    _ <- BedrockModels.validate(ProductionWorkflow.modelSettings(workflow))
    vectorAdapter <- launcher.s3Vectors.toRight(
      "productionLoad: vector adapter configuration required"
    )
    _ <-
      if (
        s.vectorBatchSize <= workflow.maxEmbeddingBatchTexts &&
        s.vectorBatchSize <= s.vectorMaxBatchSize && s.vectorBatchSize <= vectorAdapter.maxGetKeys &&
        s.maxTextBytes <= workflow.maxEmbeddingInputBytes
      ) Right(())
      else Left("productionLoad: vector/embedding batch or text bounds are incompatible")
    original <- producerConfig(s)
    analysis <- AnalysisRunSettings
      .from(original)
      .left
      .map(_ => "productionLoad: archived analysis configuration required")
    _ <-
      if (analysis.windows.contains(workflow.runtime.defaultWindow)) Right(())
      else Left("productionLoad: runtime default window is absent from the configured analysis run")
    run <- source(s.runDirectory, s)
    loads <- source(s.loadDirectory, s)
    grouping <- source(s.groupingDirectory, s)
    curated <- source(s.curatedDirectory, s)
    benchmark <- source(s.benchmarkDirectory, s)
    manifestBytes <- mapped(run.readAtMost("_run-manifest.json", s.sourceMaxBytes))
    manifest <- Json
      .decode[RunManifest](manifestBytes.utf8.trim)
      .left
      .map(_ => "productionLoad: invalid run manifest")
    runId <- RunId.of(manifest).left.map(_ => "productionLoad: invalid run identity")
    _ <-
      if (runId == workflow.analysisRunId && manifest.configDigest == analysis.configDigest)
        Right(())
      else Left("productionLoad: analysis manifest does not match configured serving run/settings")
    _ <-
      if (manifest.inputSnapshots.exists(_.snapshot == workflow.snapshot)) Right(())
      else Left("productionLoad: serving snapshot is not a source snapshot of the configured run")
    parts <- mapped(run.readAtMost("_parts.json", s.sourceMaxBytes))
    parquet <- mapped(
      ParquetReadLimits.of(
        s.parquetMaxFooterBytes,
        s.parquetMaxPartBytes,
        s.parquetMaxRowsPerPart,
        s.parquetMaxPageBytes
      )
    )
    upstream <- mapped(UpstreamBounds.of(s.upstreamMaxBytes, s.upstreamMaxRows))
    kv <- mapped(KeyValueLoadLimits.of(s.keyValueMaxItemBytes))
    layout <- S3Layout
      .from(
        S3LayoutSettings(
          Map(
            Zone.Raw -> ZoneLocation(c.storage.rawBucket, s.rawPrefix),
            Zone.Curated -> ZoneLocation(c.storage.curatedBucket, s.curatedPrefix),
            Zone.Graph -> ZoneLocation(s.graphBucket, s.graphPrefix),
            Zone.SemanticContext -> ZoneLocation(c.storage.contextBucket, s.semanticContextPrefix),
            Zone.Reports -> ZoneLocation(s.reportsBucket, s.reportsPrefix),
            Zone.Logs -> ZoneLocation(s.logsBucket, s.logsPrefix)
          ),
          s.layoutMaxKeyBytes
        )
      )
      .left
      .map(_ => "productionLoad: invalid S3 layout")
    vectorLimits = VectorLimits(
      s.vectorMaxBatchSize,
      s.vectorMaxValueBytes,
      s.vectorMaxFilterableBytes,
      s.vectorMaxTotalBytes,
      s.vectorMaxKeys,
      s.vectorMaxNonFilterableKeys,
      s.vectorMaxListValues,
      s.vectorMaxTopK
    )
    distance <- DistanceMetric.values
      .find(_.tag == s.vectorDistance)
      .toRight("productionLoad: invalid vector distance")
    retry <- mapped(
      RetryPolicy.of(s.vectorRetryMaxAttempts, s.vectorRetryBaseMillis, s.vectorRetryMaxMillis)
    )
    vectors <- VectorLoadConfig
      .of(
        vectorLimits,
        VectorIndexes.all.map(i =>
          VectorIndexSettings(i.name, c.models.embeddingDimension, distance)
        ),
        s.vectorBatchSize,
        retry,
        s.maxRejected,
        s.maxTextBytes,
        s.maxKeyBytes,
        EmbeddingPolicy.RealModelsOnly,
        s.maxItemsPerCall
      )
      .left
      .map(_ => "productionLoad: invalid vector load bounds")
    entity <- mapped(
      EntityLinkLoadConfig.of(
        s.entityLinksExpiresAtEpochSeconds,
        s.maxEntityLinks,
        s.maxMentionIdsPerLink
      )
    )
    _ <-
      if (s.entityLinksExpiresAtEpochSeconds > System.currentTimeMillis() / 1000) Right(())
      else Left("productionLoad: entity link expiry is not in the future")
    neptune <- launcher.neptune.toRight("productionLoad: Neptune configuration required")
    _ <-
      if (s.writerEndpoint.contains(".cluster-ro-"))
        Left("productionLoad: writer endpoint must not be a read replica endpoint")
      else Right(())
    writer <- mapped(
      NeptuneClientSettings.of(
        neptune.region,
        s.writerEndpoint,
        neptune.apiCallTimeoutMillis,
        neptune.apiCallAttemptTimeoutMillis,
        neptune.retryMaxAttempts
      )
    )
    _ <-
      if (
        Set(c.storage.rawBucket, c.storage.curatedBucket, c.storage.contextBucket).contains(
          s.graphBucket
        )
      ) Right(())
      else Left("productionLoad: graph bucket is outside configured S3 adapter allowlist")
    _ <-
      if (
        Set(c.storage.rawBucket, c.storage.curatedBucket, c.storage.contextBucket).contains(
          s.reportsBucket
        ) &&
        Set(c.storage.rawBucket, c.storage.curatedBucket, c.storage.contextBucket).contains(
          s.logsBucket
        )
      ) Right(())
      else Left("productionLoad: report/log buckets are outside configured S3 adapter allowlist")
  } yield Prepared(
    launcher,
    OpeningLoadSources(run, loads, grouping, curated, benchmark, s.benchmarkFile),
    OpeningLoadConfig(
      analysis,
      parquet,
      upstream,
      s.benchmarkMaxBytes,
      kv,
      layout,
      vectors,
      Some(entity)
    ),
    writer,
    vectorLimits,
    runId,
    parts.sha256
  )

  /**
   * Execution is unreachable from default/validation mode. No wrapper calls this without approval.
   */
  private[services] def execute(p: Prepared, confirmed: String): Either[String, OpeningLoadReport] =
    if (confirmed != p.runId)
      Left("productionLoad: execution confirmation does not match prepared run")
    else
      ProductionLoad
        .open(
          p.launcher,
          p.writer,
          p.vectorLimits,
          new Clock { def nowMillis() = System.currentTimeMillis() },
          scholarchain.storage.aws.dynamodb.DynamoDbKeyValueStore.ThreadSleeper,
          new Jitter {
            def uniform(n: Long): Long =
              if (n <= 0) 0L else java.util.concurrent.ThreadLocalRandom.current().nextLong(n)
          }
        )
        .flatMap { resources =>
          try {
            val result = OpeningLoad.run(p.sources, p.config, resources.targets)
            if (result.report.complete && result.report.runId.contains(p.runId))
              Right(result.report)
            else
              Left(
                "productionLoad: opening load incomplete; inspect the durable RunLedger and retained source evidence"
              )
          } catch {
            case NonFatal(_) =>
              Left("productionLoad: execution failed; inspect durable load evidence")
          } finally resources.close()
        }

  def main(args: Array[String]): Unit = {
    val result = for {
      action <- parseArgs(args.toVector)
      config <- ConfigLoader
        .loadFromSystem()
        .left
        .map(_ => "productionLoad: configuration unavailable or invalid")
      p <- prepare(config)
      message <- action match {
        case None =>
          Right(
            s"LOCAL VALIDATION ONLY: run=${p.runId} partsSha256=${p.partManifestSha256}; no AWS or model call. Full data/store verification occurs in OpeningLoad during approved execution."
          )
        case Some(confirmed) =>
          execute(p, confirmed).map { report =>
            report.stores
              .map(s => s"${s.target.tag}:${s.status.tag}:written=${s.written}")
              .mkString("PRODUCTION LOAD COMPLETE: ", ", ", "")
          }
      }
    } yield message
    result match {
      case Right(message) => println(message)
      case Left(problem) => System.err.println(problem); sys.exit(1)
    }
  }
}
