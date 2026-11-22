package scholarchain.storage.port

import scholarchain.core.EvidenceBundle
import scholarchain.storage.model.{
  AuditBounds,
  EmbeddingPolicy,
  EntityLinkRecord,
  EvidenceItemRow,
  ItemCodec,
  ItemCodecs,
  PutOutcome,
  QueryAuditRecord,
  QuerySessionRecord,
  QueryStepRecord,
  StoreError,
  TableRole
}

/** Writes one record type to its runtime table through its codec (putIfAbsent only). */
private[port] final class CodecWriter[A](codec: ItemCodec[A], kv: KeyValueWriter) {
  require(codec.table.role.runtimeWritable, s"${codec.table.role.tag} is not a runtime table")
  def put(a: A): Either[StoreError, PutOutcome] =
    codec.encode(a).flatMap(kv.putIfAbsent(codec.table.role, _))
}

/** Runtime writer of the QuerySession table only (sessions, their plan steps and audit rows). */
final class QuerySessionWriter private (
    sessions: CodecWriter[QuerySessionRecord],
    steps: CodecWriter[QueryStepRecord],
    audits: CodecWriter[QueryAuditRecord]
) {
  def putSession(s: QuerySessionRecord): Either[StoreError, PutOutcome] = sessions.put(s)
  def putStep(s: QueryStepRecord): Either[StoreError, PutOutcome] = steps.put(s)

  /**
   * SE-P9-02: the audit row of a query (QS-3), create-once: `Written` the first time,
   * `AlreadyPresent` for an identical row, `Conflict` for a different row under the same query
   * (another body, hash, schema version or TTL). A body over `bounds` is `Refused` before any
   * write. Pass the same `expiresAtEpochSeconds` as the query's session and step rows.
   */
  def putAudit(a: QueryAuditRecord, bounds: AuditBounds): Either[StoreError, PutOutcome] =
    if (bounds == null) Left(StoreError.Refused("QuerySession: audit bounds are required"))
    else if (a != null && QueryAuditRecord.jsonBytes(a) > bounds.maxAuditJsonBytes)
      Left(
        StoreError.Refused(
          s"QuerySession: auditJson is ${QueryAuditRecord.jsonBytes(a)} bytes, over the " +
            s"configured bound of ${bounds.maxAuditJsonBytes}"
        )
      )
    else audits.put(a)
}
object QuerySessionWriter {
  def apply(kv: KeyValueWriter): QuerySessionWriter =
    new QuerySessionWriter(
      new CodecWriter(ItemCodecs.QuerySessionCodec, kv),
      new CodecWriter(ItemCodecs.QueryStepCodec, kv),
      new CodecWriter(ItemCodecs.QueryAuditCodec, kv)
    )
}

/** Runtime writer of the EntityLinkCache table only. */
final class EntityLinkCacheWriter private (links: CodecWriter[EntityLinkRecord]) {
  def put(link: EntityLinkRecord): Either[StoreError, PutOutcome] = links.put(link)
}
object EntityLinkCacheWriter {
  def apply(kv: KeyValueWriter): EntityLinkCacheWriter =
    new EntityLinkCacheWriter(new CodecWriter(ItemCodecs.EntityLinkCodec, kv))
}

/**
 * Runtime writer of the EvidenceBundle table only. A bundle is written item rows first, bundle row
 * last, each create-once: a present bundle row means every item row is present, and a retry after a
 * partial write completes it without duplicates.
 */
final class EvidenceBundleWriter private (
    bundles: CodecWriter[EvidenceBundle],
    items: CodecWriter[EvidenceItemRow]
) {
  def put(bundle: EvidenceBundle): Either[StoreError, PutOutcome] =
    if (bundle == null || bundle.items == null) Left(StoreError.Refused("EvidenceBundle: missing"))
    else {
      val rows = bundle.items.map(i =>
        EvidenceItemRow(EvidenceItemRow.SchemaVersion, bundle.bundleId, bundle.queryId, i)
      )
      rows.map(items.put).collectFirst { case Left(e) => e } match {
        case Some(e) => Left(e)
        case None => bundles.put(bundle)
      }
    }
}
object EvidenceBundleWriter {
  def apply(kv: KeyValueWriter): EvidenceBundleWriter =
    new EvidenceBundleWriter(
      new CodecWriter(ItemCodecs.EvidenceBundleCodec, kv),
      new CodecWriter(ItemCodecs.EvidenceItemRowCodec, kv)
    )
}

/**
 * Everything the runtime service may use (role law 4; README L1378): read-only facades over S3,
 * DynamoDB, Neptune and S3 Vectors, plus exactly the three runtime-table writers. No loader, no
 * object writer, no vector writer, no graph loader and no writer of RunLedger, ArtifactRegistry or
 * ResearcherMetricCache is reachable from here. The readers are wrapped in facades, so a concrete
 * store passed in cannot be cast back to its write side. The vector reader
 * ([[VectorReader.verified]]) also checks every returned vector's stored floats against its own
 * `dataSha256` (SE-P7-11; a mismatch is `Corrupt` and nothing is served) and its model version
 * against the explicit vector policy (CR-P7-11 m2). SE-P8-03 (CR-P8-02 m-B): [[vectors]] names no
 * query model, so it serves `getVectors` only and REFUSES every `query`; queries go through
 * [[vectorsFor]] with the query embedder's model version.
 */
final class RuntimeStores private (
    val objects: ObjectReader,
    val keyValues: KeyValueReader,
    val graph: GraphReader,
    val vectors: VectorReader,
    val vectorIndexes: IndexInspector,
    val querySessions: QuerySessionWriter,
    val entityLinks: EntityLinkCacheWriter,
    val evidenceBundles: EvidenceBundleWriter,
    vectorsOf: String => VectorReader
) {

  /**
   * CR-P8-01 M2: the vector reader for queries embedded with `modelVersion` (the query embedder's
   * canonical model version): [[VectorReader.verified]] with that model, which excludes other
   * models' matches, so no result mixes embedding spaces and a query never returns another model's
   * vectors. This is the ONLY query path: the unnamed [[vectors]] refuses every query (SE-P8-03).
   */
  def vectorsFor(modelVersion: String): VectorReader = vectorsOf(modelVersion)
}

object RuntimeStores {

  /** The tables runtime code may write. */
  val WritableTables: Set[TableRole] = TableRole.values.filter(_.runtimeWritable).toSet

  /**
   * @param vectorPolicy
   *   CR-P7-11 m2, required (no default): which stored vectors the runtime may read, by their
   *   `modelVersion`. Production composition passes `EmbeddingPolicy.RealModelsOnly`, under which a
   *   read that would return a synthetic test vector is refused; only a local test passes
   *   `SyntheticTestOnly`, explicitly.
   */
  def apply(
      objects: ObjectReader,
      keyValues: KeyValueReader,
      runtimeTableWriter: KeyValueWriter,
      graph: GraphReader,
      vectors: VectorReader,
      vectorIndexes: IndexInspector,
      vectorPolicy: EmbeddingPolicy
  ): RuntimeStores = {
    require(vectors != null && vectorPolicy != null, "reader and policy are required")
    new RuntimeStores(
      ObjectReader.readOnly(objects),
      KeyValueReader.readOnly(keyValues),
      readOnlyGraph(graph),
      VectorReader.verified(vectors, vectorPolicy),
      IndexInspector.readOnly(vectorIndexes),
      QuerySessionWriter(runtimeTableWriter),
      EntityLinkCacheWriter(runtimeTableWriter),
      EvidenceBundleWriter(runtimeTableWriter),
      model => VectorReader.verified(vectors, vectorPolicy, model)
    )
  }

  private def readOnlyGraph(g: GraphReader): GraphReader = new GraphReader {
    def run(query: scholarchain.storage.model.BoundQuery) = g.run(query)
  }
}
