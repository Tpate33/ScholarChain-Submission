package scholarchain.services

import scala.util.control.NonFatal

import scholarchain.core.config.LauncherSettings
import scholarchain.storage.aws.{AwsClientSettings, RetrySetting, StorageClients}
import scholarchain.storage.aws.dynamodb.DynamoDbKeyValueStore
import scholarchain.storage.aws.neptune.{NeptuneClientSettings, NeptuneReaderSettings}
import scholarchain.storage.aws.s3.S3ReaderSettings
import scholarchain.storage.aws.s3vectors.S3VectorsReaderSettings
import scholarchain.storage.load.OpeningLoadTargets
import scholarchain.storage.model.*
import scholarchain.storage.port.{Clock, GraphReader, GraphWriter, Jitter, Sleeper}

/** Loader-only capabilities and their resources. Never installed in the runtime service. */
final class ProductionLoadTargets private[services] (
    val targets: OpeningLoadTargets,
    release: Release
) extends AutoCloseable {
  def close(): Unit = release.run()
}

object ProductionLoad {
  private def mapped[A](e: Either[StoreError, A]): Either[String, A] =
    e.left.map(_ => "production load: storage adapter refused")

  /**
   * Owns a fresh storage/model bundle. No request is sent by constructing targets. Every failure
   * after construction closes both bundles; success transfers ownership to the returned handle.
   * Writer settings are explicit and must name the writable Neptune endpoint; runtime reader
   * settings are deliberately not reused for writes.
   */
  def open(
      settings: LauncherSettings,
      writer: NeptuneClientSettings,
      limits: VectorLimits,
      clock: Clock,
      sleeper: Sleeper,
      jitter: Jitter
  ): Either[String, ProductionLoadTargets] = for {
    workflow <- settings.workflow.toRight("production load: productionWorkflow required")
    _ <-
      if (
        writer == null || clock == null || sleeper == null || jitter == null || VectorLimits
          .problems(limits)
          .nonEmpty
      )
        Left("production load: writer and bounds required")
      else Right(())
    _ <- BedrockModels.validate(ProductionWorkflow.modelSettings(workflow))
    clients <- mapped(StorageClients.open())
    models <- BedrockModels.open(ProductionWorkflow.modelSettings(workflow)).left.map { e =>
      clients.close(); e
    }
    result <- {
      val release = Release.of(() => models.close(), () => clients.close())
      val built =
        try targets(settings, writer, limits, clock, sleeper, jitter, clients, models)
        catch { case NonFatal(_) => Left("production load: target construction failed") }
      built.left
        .map { e =>
          release.run(); e
        }
        .map(t => new ProductionLoadTargets(t, release))
    }
  } yield result

  private[services] def targets(
      settings: LauncherSettings,
      writer: NeptuneClientSettings,
      limits: VectorLimits,
      clock: Clock,
      sleeper: Sleeper,
      jitter: Jitter,
      clients: StorageClients,
      models: BedrockModels
  ): Either[String, OpeningLoadTargets] = {
    def client(s: scholarchain.core.config.AdapterClientSettings) =
      mapped(
        AwsClientSettings.of(
          s.region,
          s.endpointOverride,
          s.apiCallTimeoutMillis,
          s.apiCallAttemptTimeoutMillis,
          s.retryMaxAttempts
        )
      )
    for {
      d <- settings.process.dynamoDb.toRight("production load: DynamoDB configuration required")
      parts <- ProductionMapping.dynamoDbParts(d)
      retry <- mapped(RetrySetting.of(settings.dynamoDbRetryMaxAttempts.getOrElse(0)))
      factory <- mapped(clients.dynamoDbFactory(retry))
      calls <- factory(parts.client).left.map(_ => "production load: DynamoDB client refused")
      kv = DynamoDbKeyValueStore(calls, parts.names, parts.adapter, sleeper)
      s3 <- settings.s3.toRight("production load: S3 configuration required")
      sc <- client(s3.client)
      sr <- mapped(S3ReaderSettings.of(s3.buckets.toSet, s3.maxObjectBytes, s3.maxListKeys))
      objects <- mapped(clients.objectStore(sc, sr))
      graphWriter <- mapped(clients.graphWriter(writer))
      rows <- settings.neptune.toRight("production load: Neptune read bounds required")
      readerSettings <- mapped(NeptuneReaderSettings.of(rows.maxRows))
      // Reads made while loading must see the writer's just-written graph, avoiding replica lag.
      graphReader <- mapped(clients.graphReader(writer, readerSettings))
      v <- settings.s3Vectors.toRight("production load: vector configuration required")
      vc <- client(v.client)
      vr <- mapped(S3VectorsReaderSettings.of(v.vectorBucket, v.maxTopK, v.maxGetKeys))
      vectors <- mapped(clients.vectorStore(vc, vr, limits))
      graph = new GraphReader with GraphWriter {
        def run(q: BoundQuery) = graphReader.run(q)
        def write(w: BoundGraphWrite) = graphWriter.write(w)
        def storedVertex(id: String) = graphWriter.storedVertex(id)
        def storedEdge(id: String) = graphWriter.storedEdge(id)
      }
    } yield OpeningLoadTargets(
      graph,
      kv,
      kv,
      objects,
      vectors,
      models.embedder,
      clock,
      sleeper,
      jitter
    )
  }
}
