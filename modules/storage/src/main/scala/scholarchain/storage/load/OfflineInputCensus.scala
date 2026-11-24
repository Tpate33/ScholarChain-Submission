package scholarchain.storage.load

import scholarchain.core.{PartFileV2, TargetStore}
import scholarchain.storage.model.*
import scholarchain.storage.port.{KeyQuery, KeyValueReader}

/** Planned inputs only: no embeddings, writes, live index checks or provider token counts. */
final case class VectorInputCensus(
    total: Int,
    indexes: Vector[(String, Int, Long)],
    dimension: Int,
    textBytes: Long,
    largestTextBytes: Int,
    workManifestSha256: String,
    workManifest: EmbeddingWorkManifest
)

/**
 * DynamoDB sizes cover analysis result items only, using the loader's existing estimator. Ledger,
 * registry, entity links, runtime traffic and retries need separate bounds.
 */
final case class OfflineInputCensusReport(
    runId: String,
    partManifestSha256: String,
    groupingRunId: String,
    groupingPartManifestSha256: String,
    benchmarkDigest: String,
    curatedRunId: String,
    worksBySource: Vector[(String, Int)],
    parts: Vector[PartFileV2],
    vectors: VectorInputCensus,
    graphVertices: Int,
    graphEdges: Int,
    analysisItems: Int,
    analysisItemBytes: Long,
    largestAnalysisItemBytes: Int,
    analysisBaseWriteUnits: Long,
    analysisGsiWriteUnits: Long
)

object OfflineInputCensus {
  // A prospective fresh ledger's read view, deliberately with no writer capability.
  private val emptyLedger: KeyValueReader = new KeyValueReader {
    def get(table: TableRole, key: PrimaryKey): Either[StoreError, Option[Item]] = Right(None)
    def query(table: TableRole, q: KeyQuery): Either[StoreError, Vector[Item]] =
      Right(Vector.empty)
  }

  private def gated[A](v: VerificationReport, target: TargetStore)(
      plan: LoadPermit => Either[StoreError, A]
  ): Either[StoreError, A] =
    LoadGate
      .proceed(v, target)(plan)
      .left
      .map(rs =>
        StoreError.Refused("census: verification refused " + target.tag + ": " + rs.mkString("; "))
      )
      .flatMap(identity)

  /**
   * Same verified source chain and load gates as OpeningLoad, then existing pure planners. No
   * target stores or embedder can be supplied. maxTotalVectors applies across all five indexes.
   */
  def inspect(
      sources: OpeningLoadSources,
      config: OpeningLoadConfig,
      modelVersion: String,
      maxTotalVectors: Long
  ): Either[StoreError, OfflineInputCensusReport] = {
    def problems(xs: Vector[String]) = StoreError.Refused(xs.mkString("; "))
    for {
      g <- UpstreamRuns.grouping(sources.grouping, config.upstream).left.map(problems)
      c <- UpstreamRuns.curated(sources.curated, config.upstream).left.map(problems)
      b <- BenchmarkPairsReader
        .read(sources.benchmark, sources.benchmarkFile, config.benchmarkMaxBytes)
        .left
        .map(problems)
      up = UpstreamRuns.upstream(g, c, Some(b))
      v = LoadVerifier.verify(
        sources.run,
        sources.loads,
        emptyLedger,
        Some(VerificationContext(config.settings, config.parquet.bounds, Some(up)))
      )
      vectors <- gated(v, TargetStore.S3Vectors)(p =>
        VectorLoad.census(
          p,
          config.parquet.bounds,
          config.layout,
          config.vectors,
          modelVersion,
          maxTotalVectors
        )
      )
      graph <- gated(v, TargetStore.NeptuneBulkLoad)(GraphLoad.elements)
      rows <- gated(v, TargetStore.DynamoDb)(p => KeyValueLoad.rows(p, config.parquet.bounds))
      items <- rows.foldLeft[Either[StoreError, Vector[Item]]](Right(Vector.empty)) { (acc, row) =>
        for { xs <- acc; item <- ItemCodecs.AnalysisResultCodec.encode(row) } yield xs :+ item
      }
      sizes = items.map(KeyValueLoad.itemBytes)
      _ <-
        if (sizes.exists(_ > config.keyValueLimits.maxItemBytes))
          Left(StoreError.Refused("census: analysis item exceeds configured item byte limit"))
        else Right(())
      verified <- v.verified.toRight(StoreError.Refused("census: verified run absent"))
    } yield {
      def units(n: Int): Long = (n.toLong + 1023L) / 1024L
      val indexes = ItemCodecs.AnalysisResultCodec.table.indexes
      val gsiUnits = items.map { item =>
        indexes.count(ix => item.contains(ix.partitionKey) && item.contains(ix.sortKey)) *
          units(KeyValueLoad.itemBytes(item))
      }.sum
      OfflineInputCensusReport(
        verified.runId,
        verified.partManifestSha256,
        g.runId,
        g.partManifestSha256,
        b.digest,
        c.runId,
        c.works.groupBy(_.source.tag).toVector.sortBy(_._1).map((s, ws) => s -> ws.size),
        verified.partManifest.files,
        vectors,
        graph._1.size,
        graph._2.size,
        items.size,
        sizes.map(_.toLong).sum,
        sizes.maxOption.getOrElse(0),
        sizes.map(units).sum,
        gsiUnits
      )
    }
  }
}
