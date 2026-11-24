package scholarchain.storage.load

import java.nio.charset.StandardCharsets

import scala.collection.immutable.SortedMap
import scala.collection.mutable

import scholarchain.core.*
import scholarchain.storage.model.{
  ArtifactRecord,
  Attrs,
  Bytes,
  DistanceMetric,
  EmbeddingModelVersion,
  EmbeddingPolicy,
  EmbeddingWorkItem,
  EmbeddingWorkManifest,
  ItemCodecs,
  LoadState,
  MetaValue,
  MetadataField,
  ObjectRef,
  PutOutcome,
  RejectReason,
  RejectedRecord,
  RejectionSet,
  S3Layout,
  StoreError,
  TableKeys,
  TableRole,
  VectorDigest,
  VectorIndexSpec,
  VectorIndexes,
  VectorKeys,
  VectorLimits,
  VectorMetadata,
  VectorMetadataRules,
  VectorRecord,
  Zone
}
import scholarchain.storage.parquet.ParquetReadBounds
import scholarchain.storage.port.{
  Clock,
  Embedder,
  Jitter,
  KeyQuery,
  KeyValueStore,
  ObjectStore,
  Sleeper,
  SortKeyCondition,
  VectorStore
}

/** The configured shape of one vector index (configuration; creation is Terraform). */
final case class VectorIndexSettings(name: String, dimension: Int, distance: DistanceMetric)

/**
 * Bounded exponential backoff with full jitter (SE-P7-09, V5). Attempt `n` (1-based) that fails
 * transiently is followed by a sleep of `jitter.uniform(min(maxDelayMillis, baseDelayMillis *
 * 2^(n-1)))`, clamped to `0..` that bound (CR-P7-11 nit: a jitter source that returns more is never
 * obeyed); at most `maxAttempts` attempts are made. Configuration, no default.
 */
final case class RetryPolicy private (
    maxAttempts: Int,
    baseDelayMillis: Long,
    maxDelayMillis: Long
) {

  /** The jitter upper bound after the failed attempt `attempt` (1-based). */
  def delayBound(attempt: Int): Long = {
    val shift = math.min(math.max(attempt - 1, 0), 30)
    val d = baseDelayMillis.toDouble * math.pow(2.0, shift.toDouble)
    if (d >= maxDelayMillis.toDouble) maxDelayMillis else d.toLong
  }

  /** The sleep actually taken after `attempt`: the jitter value clamped to `0..delayBound`. */
  def delay(attempt: Int, jitter: Jitter): Long = {
    val bound = delayBound(attempt)
    math.max(0L, math.min(bound, jitter.uniform(bound)))
  }
}

object RetryPolicy {
  def of(
      maxAttempts: Int,
      baseDelayMillis: Long,
      maxDelayMillis: Long
  ): Either[StoreError, RetryPolicy] =
    if (maxAttempts < 1) Left(StoreError.Refused(s"maxAttempts: $maxAttempts is below 1"))
    else if (baseDelayMillis < 0 || maxDelayMillis < baseDelayMillis)
      Left(StoreError.Refused("delays: need 0 <= baseDelayMillis <= maxDelayMillis"))
    else Right(new RetryPolicy(maxAttempts, baseDelayMillis, maxDelayMillis))
}

/**
 * Configuration of the vector loader (SE-P7-09). No default anywhere.
 *
 * The service limits in `limits` (batch size, metadata bytes, keys, topK) are S3 Vectors values
 * that are UNVERIFIED: the aws-docs MCP server was unavailable on 2026-11-20 (and in SE-P7-01/02),
 * so nothing here is a confirmed AWS limit; the caller supplies them and they must be checked
 * against current AWS documentation (with the date) before any live load.
 *
 * @param indexes
 *   exactly the five indexes of [[VectorIndexes.all]], each once, with its dimension and distance
 * @param batchSize
 *   records per PutVectors call and per embedding call, 1..`limits.maxBatchSize` (the service cap)
 * @param maxRejected
 *   the most rejected records a load may have and still finish; one more marks the load failed
 * @param maxTextBytes
 *   the largest context text object accepted (UTF-8 bytes); larger documents are rejected
 * @param maxKeyBytes
 *   the longest vector key accepted (UNVERIFIED S3 Vectors key limit, configuration)
 * @param embedding
 *   [[EmbeddingPolicy.RealModelsOnly]] in production; [[EmbeddingPolicy.SyntheticTestOnly]] only in
 *   local tests
 * @param maxItemsPerCall
 *   SE-P8-01 item 3: the most work-manifest items one call embeds and puts (items found stored and
 *   unchanged do not count); the rest is left for the next call (ledger `documents-staged`). It
 *   must exceed `maxRejected`, so every call that does not fail stores at least one item and a
 *   bounded sequence of calls finishes the manifest
 */
final case class VectorLoadConfig private (
    limits: VectorLimits,
    indexes: Vector[VectorIndexSettings],
    batchSize: Int,
    retry: RetryPolicy,
    maxRejected: Int,
    maxTextBytes: Int,
    maxKeyBytes: Int,
    embedding: EmbeddingPolicy,
    maxItemsPerCall: Int
)

object VectorLoadConfig {
  def of(
      limits: VectorLimits,
      indexes: Vector[VectorIndexSettings],
      batchSize: Int,
      retry: RetryPolicy,
      maxRejected: Int,
      maxTextBytes: Int,
      maxKeyBytes: Int,
      embedding: EmbeddingPolicy,
      maxItemsPerCall: Int
  ): Either[Vector[String], VectorLoadConfig] = {
    val names = Option(indexes).getOrElse(Vector.empty).filter(_ != null).map(_.name)
    val problems =
      VectorLimits.problems(limits) ++
        Option.when(indexes == null || indexes.contains(null))("indexes: missing").toVector ++
        Option
          .when(names.sorted != VectorIndexes.all.map(_.name).sorted)(
            s"indexes: must be exactly ${VectorIndexes.all.map(_.name).mkString(", ")}, each once"
          )
          .toVector ++
        Option(indexes).toVector.flatten
          .filter(i => i != null && (i.dimension < 1 || i.distance == null))
          .map(i => s"indexes.${i.name}: dimension >= 1 and a distance are required") ++
        Option
          .when(limits != null && (batchSize < 1 || batchSize > limits.maxBatchSize))(
            s"batchSize: $batchSize is outside 1..${Option(limits).map(_.maxBatchSize).getOrElse(0)}"
          )
          .toVector ++
        Option.when(retry == null)("retry: missing").toVector ++
        Option.when(maxRejected < 0)("maxRejected: negative").toVector ++
        Option.when(maxTextBytes < 1)("maxTextBytes: must be >= 1").toVector ++
        Option.when(maxKeyBytes < 1)("maxKeyBytes: must be >= 1").toVector ++
        Option.when(embedding == null)("embedding: missing").toVector ++
        Option
          .when(maxItemsPerCall < 1 || maxItemsPerCall <= maxRejected)(
            s"maxItemsPerCall: $maxItemsPerCall must be >= 1 and above maxRejected $maxRejected"
          )
          .toVector
    if (problems.isEmpty)
      Right(
        new VectorLoadConfig(
          limits,
          indexes,
          batchSize,
          retry,
          maxRejected,
          maxTextBytes,
          maxKeyBytes,
          embedding,
          maxItemsPerCall
        )
      )
    else Left(problems)
  }
}

/** SE-P8-01 item 2: what the skip rule ([[VectorLoad.classify]]) found under a work item's key. */
enum StoredVector {

  /** Nothing stored: the item is embedded and put. */
  case Absent

  /** This item's intact vector (same model version and text hash): skipped, never re-embedded. */
  case Unchanged
}

/** The stores and injectable effects of one vector load. */
final case class VectorLoadTargets(
    vectors: VectorStore,
    objects: ObjectStore,
    registry: KeyValueStore,
    ledger: KeyValueStore,
    embedder: Embedder,
    clock: Clock,
    sleeper: Sleeper,
    jitter: Jitter
)

/**
 * Counts of one index in one call.
 *
 * @param routed
 *   source rows whose document type routes here (0 when none: reported, never invented)
 * @param rejected
 *   rejected records of this call routed here (CR-P7-11 nit: also those keyed by the
 *   `row:<path>#<row>` fallback when the record has no vector key)
 * @param stored
 *   routed vectors found stored with this load's metadata after the call (GetVectors; measured)
 * @param workItems
 *   SE-P8-01: items of this index in the work manifest
 * @param skippedUnchanged
 *   SE-P8-01 item 2: items found stored, intact and unchanged (same key, so same model version,
 *   same `textSha256` and metadata) before embedding; never sent to the embedder
 * @param duplicatesOnPut
 *   items the store reported `AlreadyPresent` on put (stored by a concurrent loader after the
 *   probe); `alreadyPresent` = `skippedUnchanged` + `duplicatesOnPut`
 * @param embedded
 *   texts of this index sent to the embedder by this call
 * @param putRetries
 *   record re-sends of this index after transient put failures
 * @param remaining
 *   items of this index left for a later call by the per-call bound
 */
final case class VectorIndexCounts(
    index: String,
    routed: Int,
    written: Long,
    alreadyPresent: Long,
    rejected: Int,
    stored: Int,
    workItems: Int,
    skippedUnchanged: Long,
    duplicatesOnPut: Long,
    embedded: Long,
    putRetries: Long,
    remaining: Int
)

/**
 * Result of one vector load call.
 *
 * @param rejected
 *   the rejected records of THIS call (its write generation), empty when skipped; earlier
 *   generations' records are history in the RunLedger and the rejects objects (CR-P7-11 m1)
 * @param embedded
 *   texts sent to the embedder by this call (CR-P7-11 m4: only vectors not already stored)
 * @param modelVersion
 *   SE-P8-01: the embedder's canonical model version (a component of every key; the ledger scope)
 * @param workManifestKey
 *   the ordinary-S3 key of the work manifest ([[S3Layout.embeddingWorkManifest]])
 * @param remaining
 *   items left for a later call by `maxItemsPerCall` (`finalState` is then `documents-staged`)
 * @param startedAtMillis
 *   the injected clock when the call started (a timing hook for throughput measurements; with a
 *   test clock it measures only simulated time: no latency claim is made from it)
 * @param finishedAtMillis
 *   the injected clock when the call ended
 */
final case class VectorLoadReport(
    runId: String,
    loadId: String,
    rows: Int,
    indexes: Vector[VectorIndexCounts],
    textObjects: Int,
    textWritten: Long,
    textPresent: Long,
    registryRows: Int,
    rejected: Vector[RejectedRecord],
    written: Long,
    alreadyPresent: Long,
    finalState: LoadState,
    skipped: Boolean,
    embedded: Long,
    modelVersion: String,
    dimension: Int,
    workManifestKey: String,
    workManifestSha256: String,
    workItems: Int,
    skippedUnchanged: Long,
    duplicatesOnPut: Long,
    putRetries: Long,
    embedRetries: Long,
    remaining: Int,
    startedAtMillis: Long,
    finishedAtMillis: Long
)

/**
 * Loads the `s3-vectors` store of a verified run (SE-P7-09, WP6; checklist V1-V6, S1-S3, L1-L3). It
 * runs only inside [[LoadGate.proceed]] with a [[LoadPermit]] for `s3-vectors` (parquet-rows,
 * row-contracts and summary-evidence performed and passed by the verifier), reads nothing but the
 * permit's items (re-read and re-checked now) and follows [[LoadLedger]] (store-owner rows, then
 * publication claim, ledger state machine, pre-write probe, resume without duplicates).
 *
 * ONE ANALYSIS RUN PER STORE INSTANCE (human decision 2026-11-20): vector keys carry no run ID. The
 * loader writes three physical stores ([[WrittenStores]]: the vector store, the object store for
 * the context text and the key-value store for its registry rows), and CR-P7-11 M1: the owner rows
 * of all three are checked, then claimed, before any write; another run owning any of them refuses
 * the load with zero writes and zero embedding calls. Use fresh stores and a fresh RunLedger for
 * another run. Multi-run coexistence is deferred.
 *
 * Order (CR-P7-11 m4): the embedder and the indexes are inspected and every row is decoded, routed,
 * rendered and checked (no write, no embedding); then the ledger decides (another run's store
 * refuses, a loaded load is skipped); then every vector key, text object and registry row is
 * probed; only then are texts written and vectors embedded, one batch at a time (`batchSize` texts
 * per embedding call, so at most one batch of vectors is held), and only for vectors not already
 * stored with this load's metadata. A rerun of a loaded load, or a refused run, calls the embedder
 * zero times; a resumed load embeds only what is missing.
 *
 *   - Routing (V6): every `summary-inputs` / `entity-summary-inputs` row goes to exactly one of the
 *     five indexes by its document type ([[VectorIndexes.forDocumentType]]); an index with no rows
 *     is reported with 0.
 *   - Key (V2): [[VectorKeys.of]] (index version + the row's core `suminput:` ID). Two rows with
 *     one key refuse the load before any write. A stored vector under the key is this document's
 *     when its METADATA without `dataSha256` (text SHA-256, model version, run, load, input, every
 *     filterable field) is equal (else `Conflict`) AND its stored floats match its own stored
 *     `dataSha256` ([[VectorDigest]], SE-P7-11; else `Corrupt`, refused before any data write) and
 *     have the index dimension. Such a vector is `AlreadyPresent` and is not re-embedded. The
 *     floats are never compared with a fresh embedding, so a resume never needs the embedder to be
 *     deterministic (CR-P7-11 R-a), yet a changed stored value is never accepted.
 *   - Text (V3): the deterministic context document ([[ContextText]]) is a create-once object at
 *     [[S3Layout.contextText]] (chunk ID from the text SHA-256) with an ArtifactRegistry row;
 *     metadata holds only `textS3Key` and `textSha256` plus compact filterable fields, checked by
 *     [[VectorMetadataRules.problems]] against the configured limits. CR-P7-11 M2: a researcher
 *     subject's ER identity confidence is in the text (exactly as the input) and its status and an
 *     anchor-collision flag are filterable metadata.
 *   - Vectors: from the injected [[Embedder]]; its model version and the [[VectorDigest]] of the
 *     exact floats put (`dataSha256`, non-filterable) are in every vector's metadata. Real
 *     embeddings are Phase 8 (OUTSTANDING); local tests use the synthetic embedder, which the
 *     production policy refuses. Index dimension, distance and non-filterable keys (both ways,
 *     CR-P7-11 m5) are inspected and must equal the configuration before any write.
 *   - Batches (V5): per index, `batchSize` records per put; transient failures are retried with
 *     bounded exponential backoff and jitter; a per-record permanent failure becomes a
 *     [[RejectedRecord]] (RunLedger row and one rejects object per write generation, CR-P7-11 m1);
 *     more than `maxRejected` rejections mark the load failed. A batch that stays throttled after
 *     every attempt fails the call (resumable). CR-P8-01 M1: every early exit records the
 *     rejections collected so far and the ledger row's `failure` (error class, detail, generation);
 *     a `Permanent` embedding error of a batch is split down to single texts ([[embedResolved]]),
 *     so a text the provider always refuses becomes one `embedding-failure` rejection instead of
 *     stopping every rerun at the same batch.
 *
 * SE-P8-01 (Phase 8 increment 1):
 *
 *   - Key: `<index>/<modelVersion>/<inputId>` ([[VectorKeys.of]]); a new model version writes new
 *     keys and never touches an earlier model's vectors.
 *   - Ledger scope: one RunLedger row per (load, model version), `load#<loadId>#model#<mv>`, with
 *     its own write generations (rejected records, rejects objects and rejection sets are keyed by
 *     the scope too) and its [[scholarchain.storage.model.EmbeddingTotals]]. A `loaded` row of one
 *     model never skips another model's work.
 *   - Skip rule ([[classify]]): before any embedding, every manifest item's key is probed. An
 *     intact stored vector whose metadata equals the item's (same key, hence the same model
 *     version, and the same `textSha256`, provenance and filterable fields) is UNCHANGED: counted
 *     `skippedUnchanged`, never embedded or put again. Other metadata under the key (another text
 *     under the same model) is a `Conflict`; a damaged stored vector is `Corrupt`; both refuse the
 *     call before any data write. Same text under a new model version is new work under a new key.
 *   - Work list: each call derives its work list from the verified permit (index then key order)
 *     and stores it, create-once in ordinary S3 with an ArtifactRegistry row and before any vector
 *     is put, as the deterministic [[scholarchain.storage.model.EmbeddingWorkManifest]] (one item
 *     per (index, inputId, modelVersion)). CR-P8-01 m2: the stored manifest is the recorded,
 *     content-addressed EVIDENCE of that work list (its SHA-256 is in the ledger totals); the
 *     loader does not read it back as its queue. One call embeds at most `maxItemsPerCall` pending
 *     items, in `batchSize` batches (embedding and put); when items remain, the row ends
 *     `documents-staged` and the next call resumes (done items are skipped by the probe). No SQS.
 *   - Retries: transient embedding and put failures are retried with the same bounded backoff;
 *     re-sends are counted (`embedRetries`, `putRetries`) in the report and the ledger totals; a
 *     record that still fails becomes a [[RejectedRecord]] with its attempts and final error.
 */
object VectorLoad {

  private val artifacts = ItemCodecs.ArtifactCodec
  private val rejectsCodec = ItemCodecs.RejectedRecordCodec
  private val setCodec = ItemCodecs.RejectionSetCodec

  val TextContentType = "text/plain; charset=utf-8"
  val RejectsContentType = "application/x-ndjson"

  /**
   * CR-P7-11 M1: the physical stores this loader writes: vectors (`s3-vectors`), context text
   * objects (`s3`) and their ArtifactRegistry rows (`dynamodb`). Rejected-record rows go to the
   * RunLedger.
   */
  val WrittenStores: Vector[TargetStore] =
    Vector(TargetStore.S3Vectors, TargetStore.S3, TargetStore.DynamoDb)

  /** The role of each summary-input record schema (as core LoadRoles and the fixture manifest). */
  val RoleOf: Map[String, String] = Map(
    SchemaVersions.SemanticSummaryInput -> "summary-inputs",
    SchemaVersions.EntitySummaryInput -> "entity-summary-inputs"
  )

  /** One routed source row with its document and metadata (no vector yet). */
  private final case class Doc(
      item: LoadItem,
      row: Int,
      index: VectorIndexSpec,
      inputId: String,
      key: String,
      text: String,
      textSha256: String,
      textRef: ObjectRef,
      metadata: VectorMetadata
  )

  private final case class Embedded(doc: Doc, record: VectorRecord)

  /** One summary input, whichever schema. */
  private final case class Input(
      inputId: String,
      documentType: DocumentType,
      subjectId: String,
      window: Option[TimeWindow],
      facts: Vector[(String, String, Vector[(String, String)])],
      sourceDataset: SourceType,
      identity: Option[IdentityConfidence]
  )

  private def inputOf(p: Product): Option[Input] = p match {
    case x: SemanticSummaryInput =>
      Some(
        Input(
          x.inputId,
          x.documentType,
          x.subjectId,
          Some(x.window),
          x.facts.map(f => (f.name, f.value, f.evidence.map(e => (e.kind.tag, e.id)))),
          x.provenance.sourceDataset,
          Option(x.identity).flatten
        )
      )
    case x: EntitySummaryInput =>
      Some(
        Input(
          x.inputId,
          x.documentType,
          x.subjectId,
          None,
          x.facts.map(f => (f.name, f.value, f.evidence.map(e => (e.kind.tag, e.id)))),
          x.provenance.sourceDataset,
          None
        )
      )
    case _ => None
  }

  private def bytesOf(s: String): Int = s.getBytes(StandardCharsets.UTF_8).length

  private def detail(s: String): String = {
    val clean = s.map(c => if (c.isControl) ' ' else c)
    val out = new StringBuilder
    clean.foreach(c => if (bytesOf(out.toString + c) <= RejectedRecord.MaxDetailBytes) out += c)
    if (out.isEmpty) "rejected" else out.toString
  }

  private def sortUtf8(xs: Vector[String]): Vector[String] =
    xs.distinct.sortWith((a, b) => Canonical.compareUtf8(a, b) < 0)

  /**
   * CR-P7-11 M2: the filterable identity fields of a researcher subject: its status tag and whether
   * any anchor-collision code is present.
   */
  private[load] def identityMetadata(identity: IdentityConfidence): Vector[(String, MetaValue)] =
    Vector(
      MetadataField.IdentityStatusField.key -> MetaValue.Str(identity.status.tag),
      MetadataField.AnchorCollision.key -> MetaValue.Str(
        VectorIndexes.anchorCollisionValue(Option(identity.collisionCodes).exists(_.nonEmpty))
      )
    )

  /** The compact filterable metadata of one input for its index (only fields the index allows). */
  private def metadataOf(
      in: Input,
      index: VectorIndexSpec,
      permit: LoadPermit,
      it: LoadItem,
      textRef: ObjectRef,
      textSha256: String,
      modelVersion: String
  ): VectorMetadata = {
    import MetadataField.*
    val cited: Vector[String] =
      (in.subjectId +: in.facts.flatMap { case (_, v, ev) => v +: ev.map(_._2) })
        .filter(v => v != null && StableId.validateAny(v).isRight)
    def ofKind(k: IdKind) = sortUtf8(cited.filter(v => StableId.prefixKind(v).contains(k)))
    val community: Option[String] =
      if (StableId.prefixKind(in.subjectId).contains(IdKind.Community)) Some(in.subjectId)
      else
        ofKind(IdKind.Community) match {
          case Vector(one) => Some(one)
          case _ => None // none, or ambiguous: never guessed
        }
    def list(f: MetadataField, xs: Vector[String]) =
      Option.when(index.allowed(f) && xs.nonEmpty)(f.key -> MetaValue.StrList(xs))
    val identity: Vector[(String, MetaValue)] =
      in.identity.toVector
        .flatMap(identityMetadata)
        .filter { case (k, _) => MetadataField.fromKey(k).exists(index.allowed) }
    val fields: Vector[Option[(String, MetaValue)]] = Vector(
      Some(DocumentTypeField.key -> MetaValue.Str(in.documentType.tag)),
      Some(SubjectId.key -> MetaValue.Str(in.subjectId)),
      Some(RunId.key -> MetaValue.Str(permit.runId)),
      Some(IndexVersion.key -> MetaValue.Str(index.name)),
      Some(SourceDataset.key -> MetaValue.Str(in.sourceDataset.tag)),
      list(ResearcherIds, ofKind(IdKind.Researcher)),
      list(TopicIds, ofKind(IdKind.Topic)),
      list(InstitutionIds, ofKind(IdKind.Institution)),
      community
        .filter(_ => index.allowed(CommunityId))
        .map(c => CommunityId.key -> MetaValue.Str(c)),
      in.window
        .filter(_ => index.allowed(WindowStart))
        .map(w => WindowStart.key -> MetaValue.Num(w.startYear.toLong)),
      in.window
        .filter(_ => index.allowed(WindowEnd))
        .map(w => WindowEnd.key -> MetaValue.Num(w.endYear.toLong)),
      Some(TextS3Key.key -> MetaValue.Str(textRef.key)),
      Some(TextSha256.key -> MetaValue.Str(textSha256)),
      Some(ModelVersion.key -> MetaValue.Str(modelVersion)),
      Some(LoadId.key -> MetaValue.Str(permit.load.loadId)),
      Some(InputId.key -> MetaValue.Str(in.inputId)),
      Some(SchemaVersion.key -> MetaValue.Str(VectorMetadata.SchemaVersion)),
      Some(SourcePath.key -> MetaValue.Str(it.path))
    )
    VectorMetadata(SortedMap.from(fields.flatten ++ identity))
  }

  private def reasonOf(problems: Vector[String]): RejectReason =
    if (problems.exists(_.contains(": missing"))) RejectReason.MissingMetadata
    else if (problems.exists(p => p.contains("exceed") || p.contains("cap")))
      RejectReason.OversizeMetadata
    else RejectReason.MalformedMetadata

  /**
   * Everything decided before the ledger: routed documents and pre-write rejections, each with the
   * index it routes to. No embedding.
   */
  private final case class Plan(
      rows: Int,
      routed: Map[String, Int],
      docs: Vector[Doc],
      rejected: Vector[(String, RejectedRecord)]
  )

  /**
   * A rejected record. `generation` is the call's write generation; pre-write rejections are made
   * before it is known and stamped by [[writeAll]].
   */
  private def reject(
      permit: LoadPermit,
      it: LoadItem,
      key: String,
      reason: RejectReason,
      why: String,
      attempts: Int,
      generation: Long,
      scope: Option[String] = None
  ): RejectedRecord =
    RejectedRecord(
      RejectedRecord.SchemaVersion,
      permit.runId,
      permit.load.loadId,
      TargetStore.S3Vectors,
      it.targetRole,
      it.path,
      key,
      reason,
      detail(why),
      attempts,
      generation,
      scope
    )

  private def checkIndexes(
      config: VectorLoadConfig,
      vectors: VectorStore,
      embedder: Embedder
  ): Either[StoreError, Unit] =
    config.indexes.foldLeft[Either[StoreError, Unit]](Right(())) { (acc, ix) =>
      acc.flatMap { _ =>
        vectors.describe(ix.name).flatMap {
          case None =>
            Left(StoreError.Refused(s"index ${ix.name}: absent (index creation is Terraform)"))
          case Some(info) =>
            val declared = Option(info.nonFilterableKeys).getOrElse(Vector.empty)
            val missingNonFilterable =
              MetadataField.nonFilterableFields.map(_.key).filterNot(declared.contains)
            // CR-P7-11 m5: a filterable field declared non-filterable could never be filtered.
            val filterableDeclared =
              declared.filter(k => MetadataField.fromKey(k).exists(_.filterable))
            if (info.dimension != ix.dimension)
              Left(
                StoreError.Refused(
                  s"index ${ix.name}: dimension ${info.dimension} is not the configured ${ix.dimension}"
                )
              )
            else if (info.distance != ix.distance)
              Left(StoreError.Refused(s"index ${ix.name}: distance ${info.distance.tag} differs"))
            else if (embedder.dimension != ix.dimension)
              Left(
                StoreError.Refused(
                  s"index ${ix.name}: embedder dimension ${embedder.dimension} is not ${ix.dimension}"
                )
              )
            else if (missingNonFilterable.nonEmpty)
              Left(
                StoreError.Refused(
                  s"index ${ix.name}: not declared non-filterable: ${missingNonFilterable.mkString(", ")}"
                )
              )
            else if (filterableDeclared.nonEmpty)
              Left(
                StoreError.Refused(
                  s"index ${ix.name}: filterable fields declared non-filterable: " +
                    filterableDeclared.mkString(", ")
                )
              )
            else Right(())
        }
      }
    }

  private def checkEmbedder(config: VectorLoadConfig, e: Embedder): Either[StoreError, Unit] =
    if (e == null || !StableId.isToken(e.modelVersion))
      Left(StoreError.Refused("embedder: missing, or its model version is not a token"))
    else if (EmbeddingModelVersion.problem(e.modelVersion).isDefined)
      Left(StoreError.Refused(s"embedder ${EmbeddingModelVersion.problem(e.modelVersion).get}"))
    else
      EmbeddingPolicy.refusal(config.embedding, Some(e.modelVersion)) match {
        case Some(why) => Left(StoreError.Refused(s"embedder $why"))
        case None => Right(())
      }

  private def plan(
      permit: LoadPermit,
      parquet: ParquetReadBounds,
      layout: S3Layout,
      config: VectorLoadConfig,
      modelVersion: String
  ): Either[StoreError, Plan] = {
    val decoded = permit.items.foldLeft[Either[StoreError, Vector[(LoadItem, Int, Input)]]](
      Right(Vector.empty)
    ) { (acc, it) =>
      for {
        xs <- acc
        _ <-
          if (RoleOf.get(it.recordSchemaVersion).contains(it.targetRole)) Right(())
          else
            Left(
              StoreError.Refused(
                s"${it.path}: role ${it.targetRole} is not the s3-vectors role of ${it.recordSchemaVersion}"
              )
            )
        b <- permit.readItem(it.path)
        part <- ParquetRecords.decodePart(it.path, it.recordSchemaVersion, b, parquet)
        ins <- part.records.zipWithIndex
          .foldLeft[Either[StoreError, Vector[(LoadItem, Int, Input)]]](
            Right(Vector.empty)
          ) { case (a, (r, i)) =>
            a.flatMap(v =>
              inputOf(r)
                .toRight(
                  StoreError.Refused(s"${it.path}: ${r.productPrefix} is not a summary input")
                )
                .map(in => v :+ ((it, i, in)))
            )
          }
      } yield xs ++ ins
    }
    decoded.flatMap { rows =>
      val routed = rows
        .groupBy(r => VectorIndexes.forDocumentType(r._3.documentType).name)
        .map((k, v) => k -> v.size)
      val built: Vector[Either[(String, RejectedRecord), Doc]] = rows.map { case (it, i, in) =>
        val index = VectorIndexes.forDocumentType(in.documentType)
        val fallback = s"row:${it.path}#$i"
        val text = ContextText.render(
          permit.runId,
          it,
          in.documentType,
          in.subjectId,
          in.window,
          in.inputId,
          in.identity,
          in.facts
        )
        val sha = Canonical.sha256HexUtf8(text)
        def rej(key: String, reason: RejectReason, why: String) =
          index.name -> reject(permit, it, key, reason, why, 0, 1L)
        (for {
          key <- VectorKeys.of(index, modelVersion, in.inputId)
          _ <-
            if (bytesOf(key) <= config.maxKeyBytes) Right(())
            else Left(StoreError.Refused(s"key: ${bytesOf(key)} bytes exceed maxKeyBytes"))
          chunk <- VectorKeys.chunkId(in.subjectId, in.documentType, sha)
          ref <- layout.contextText(permit.load.loadId, chunk)
        } yield (key, ref)) match {
          case Left(e) => Left(rej(fallback, RejectReason.MalformedRecord, e.message))
          case Right((key, ref)) =>
            if (bytesOf(text) > config.maxTextBytes)
              Left(
                rej(
                  key,
                  RejectReason.MalformedRecord,
                  s"context text of ${bytesOf(text)} bytes exceeds maxTextBytes ${config.maxTextBytes}"
                )
              )
            else {
              val md = metadataOf(in, index, permit, it, ref, sha, modelVersion)
              // SE-P7-11: checked with a digest of the exact size the real one will have.
              val p = VectorMetadataRules.problems(
                index,
                VectorDigest.withDigest(md, VectorDigest.SizingPlaceholder),
                config.limits
              )
              if (p.nonEmpty) Left(rej(key, reasonOf(p), p.mkString("; ")))
              else Right(Doc(it, i, index, in.inputId, key, text, sha, ref, md))
            }
        }
      }
      val docs = built.collect { case Right(d) => d }
      val pre = built.collect { case Left(r) => r }
      val dupKeys = docs.groupBy(_.key).filter(_._2.size > 1).keys.toVector
      if (dupKeys.nonEmpty)
        Left(
          StoreError.Refused(
            s"vector load: ${dupKeys.size} keys are produced by more than one row (first ${sortUtf8(dupKeys).head})"
          )
        )
      else {
        // Index then key order (deterministic): the probe, the writes and the embedding batches.
        val ordered = VectorIndexes.all.flatMap(ix =>
          docs
            .filter(_.index.name == ix.name)
            .sortWith((a, b) => Canonical.compareUtf8(a.key, b.key) < 0)
        )
        Right(Plan(rows.size, routed, ordered, pre))
      }
    }
  }

  /**
   * Offline census of the exact production plan. No embedder, store or write is accepted. Refuses
   * every pre-write rejection instead of reporting a filtered subset as complete.
   */
  def census(
      permit: LoadPermit,
      parquet: ParquetReadBounds,
      layout: S3Layout,
      config: VectorLoadConfig,
      modelVersion: String,
      maxTotalVectors: Long
  ): Either[StoreError, VectorInputCensus] =
    if (permit.target != TargetStore.S3Vectors || maxTotalVectors < 1)
      Left(StoreError.Refused("census: s3-vectors permit and positive global vector cap required"))
    else if (EmbeddingModelVersion.problem(modelVersion).isDefined)
      Left(StoreError.Refused("census: invalid embedding model version"))
    else if (EmbeddingPolicy.refusal(config.embedding, Some(modelVersion)).isDefined)
      Left(StoreError.Refused("census: embedding policy refuses model version"))
    else
      plan(permit, parquet, layout, config, modelVersion).flatMap { p =>
        if (p.rejected.nonEmpty)
          Left(
            StoreError.Refused(
              s"census: ${p.rejected.size} planned input rejections; no projection allowed"
            )
          )
        else if (p.rows.toLong > maxTotalVectors)
          Left(
            StoreError
              .Refused(s"census: ${p.rows} total vectors exceed global cap $maxTotalVectors")
          )
        else {
          val dimension = config.indexes.head.dimension
          if (config.indexes.exists(_.dimension != dimension))
            Left(StoreError.Refused("census: indexes must share one configured dimension"))
          else
            workOf(permit, layout, modelVersion, dimension, p.docs).map { work =>
              VectorInputCensus(
                p.rows,
                VectorIndexes.all.map { ix =>
                  val docs = p.docs.filter(_.index.name == ix.name)
                  (ix.name, docs.size, docs.map(d => bytesOf(d.text).toLong).sum)
                },
                dimension,
                p.docs.map(d => bytesOf(d.text).toLong).sum,
                p.docs.map(d => bytesOf(d.text)).maxOption.getOrElse(0),
                work.sha256,
                work.manifest
              )
            }
        }
      }

  private def artifactRow(permit: LoadPermit, d: Doc): ArtifactRecord =
    ArtifactRecord(
      ArtifactRecord.SchemaVersion,
      permit.runId,
      Some(permit.load.loadId),
      Zone.SemanticContext.tag,
      d.textRef.bucket,
      d.textRef.key,
      d.textSha256,
      bytesOf(d.text).toLong,
      TextContentType,
      Some(d.item.path),
      Some(d.item.recordSchemaVersion)
    )

  /**
   * SE-P7-11 (CR-P7-12 D3): whether a stored vector is this document's, intact. Its metadata
   * without `dataSha256` must equal the document's expected metadata, which never contains the
   * digest (text SHA-256, model version, provenance, filterable fields; else `Conflict`: other
   * content under the key); its stored floats must be non-null and finite and match its own stored
   * `dataSha256` ([[VectorDigest.verify]]; a missing digest fails closed; else `Corrupt`); and its
   * dimension must be the index's (else `Corrupt`). The floats are never compared with a new
   * embedding, so a nondeterministic embedder resumes without a Conflict.
   */
  private def storedState(
      ix: String,
      d: Doc,
      dimension: Int,
      s: VectorRecord
  ): Either[StoreError, Unit] =
    classify(ix, d.key, d.metadata, dimension, Some(s)).map(_ => ())

  /**
   * SE-P8-01 item 2, THE SKIP RULE (skip unchanged text by content hash and embedding model
   * version), for one work item whose expected metadata (without `dataSha256`) is `expected` and
   * whose key `key` holds the model version ([[VectorKeys.of]]):
   *
   *   - nothing stored under the key: [[StoredVector.Absent]] (embed and put);
   *   - a stored vector whose metadata without `dataSha256` equals `expected` (so the same
   *     `textSha256`, the same `modelVersion` and the same provenance and filterable fields) and
   *     whose floats are intact and of the index dimension: [[StoredVector.Unchanged]] (skipped:
   *     not embedded, not put; counted `skippedUnchanged`);
   *   - stored metadata that differs (another text hash under the same key and model, or another
   *     model or provenance): `Conflict` (never an overwrite; the call stops before any data
   *     write);
   *   - stored floats that do not verify, a wrong dimension or no metadata: `Corrupt`.
   *
   * Same text under a NEW model version is never decided here as unchanged: its key differs (the
   * model is a key component), so it is `Absent` and embedded under the new key.
   */
  private[load] def classify(
      ix: String,
      key: String,
      expected: VectorMetadata,
      dimension: Int,
      stored: Option[VectorRecord]
  ): Either[StoreError, StoredVector] =
    stored match {
      case None => Right(StoredVector.Absent)
      case Some(s) => classifyStored(ix, key, expected, dimension, s)
    }

  private def classifyStored(
      ix: String,
      key: String,
      expected: VectorMetadata,
      dimension: Int,
      s: VectorRecord
  ): Either[StoreError, StoredVector] =
    if (s == null || s.metadata == null || s.metadata.values == null)
      Left(StoreError.Corrupt(s"$ix $key: a stored vector without metadata (pre-write probe)"))
    else if (s.key != key)
      Left(StoreError.Corrupt(s"$ix $key: the stored vector has another key (pre-write probe)"))
    else if (VectorDigest.withoutDigest(s.metadata) != expected)
      Left(
        StoreError.Conflict(
          s"$ix ${s.key}: holds textSha256 ${s.metadata.string(MetadataField.TextSha256).orNull} " +
            s"(or another model version, provenance or metadata) (pre-write probe)"
        )
      )
    else
      VectorDigest.verify(ix, s).flatMap { _ =>
        if (s.data.size == dimension) Right(StoredVector.Unchanged)
        else
          Left(
            StoreError.Corrupt(s"$ix ${s.key}: stored dimension ${s.data.size} is not $dimension")
          )
      }

  /**
   * Pre-write probe: every stored vector ([[storedState]]: this document's metadata and intact
   * floats), text object and registry row is absent or this load's. Vectors found stored and intact
   * are recorded in `present`: they are not embedded or put again. A corrupted stored vector
   * refuses the call before any data write.
   */
  private def probe(
      permit: LoadPermit,
      config: VectorLoadConfig,
      t: VectorLoadTargets,
      work: Work,
      present: mutable.Set[String]
  )(docs: Vector[Doc]): Either[StoreError, Unit] = {
    present.clear()
    val dims = config.indexes.map(i => i.name -> i.dimension).toMap
    // SE-P8-01: the work manifest object and its registry row are absent or exactly this one.
    val manifestCheck = for {
      meta <- t.objects.head(work.ref)
      _ <- meta match {
        case Some(m) if m.sha256 != work.sha256 =>
          Left(
            StoreError.Conflict(
              s"${work.ref.uri}: holds another embedding work manifest (pre-write probe)"
            )
          )
        case _ => Right(())
      }
      row = workRow(permit, work)
      item <- artifacts.encode(row)
      key <- artifacts.key(row)
      stored <- t.registry.get(artifacts.table.role, key)
      _ <- stored match {
        case Some(s) if s.get(Attrs.RecordSha256) != item.get(Attrs.RecordSha256) =>
          Left(StoreError.Conflict(s"registry ${key.sk}: holds a different row (pre-write probe)"))
        case _ => Right(())
      }
    } yield ()
    lazy val vectorCheck = docs
      .groupBy(_.index.name)
      .toVector
      .sortBy(_._1)
      .flatMap { case (ix, xs) => xs.grouped(config.limits.maxBatchSize).map(ix -> _) }
      .foldLeft[Either[StoreError, Unit]](Right(())) { case (acc, (ix, xs)) =>
        acc.flatMap { _ =>
          t.vectors.getVectors(ix, xs.map(_.key)).flatMap { stored =>
            val mine = xs.map(d => d.key -> d).toMap
            stored.iterator
              .map { s =>
                Option(s).flatMap(x => mine.get(x.key)) match {
                  case None =>
                    Left(
                      StoreError
                        .Corrupt(s"$ix: GetVectors returned an unrequested key (pre-write probe)")
                    )
                  case Some(d) => storedState(ix, d, dims(ix), s)
                }
              }
              .collectFirst { case Left(e) => e } match {
              case Some(e) => Left(e)
              case None =>
                present ++= stored.map(_.key)
                Right(())
            }
          }
        }
      }
    manifestCheck.flatMap(_ => vectorCheck).flatMap { _ =>
      docs.foldLeft[Either[StoreError, Unit]](Right(())) { (acc, d) =>
        acc.flatMap { _ =>
          for {
            // SE-P7-12 (CR-P7-13 n2), documented limitation: a present text object is probed by
            // its HeadObject sha256 metadata only; the stored bytes are NOT re-read and re-hashed
            // here. This relies on (a) the text key, which embeds the load ID and the chunk ID
            // (S3Layout.contextText), so one key names one chunk of one load; (b) create-once
            // text writes, which never overwrite a key; and (c) S3's own integrity checksums on
            // upload and storage. A stored object whose bytes changed while its sha256 metadata
            // stayed intact would not be detected by this probe.
            meta <- t.objects.head(d.textRef)
            _ <- meta match {
              case Some(m) if m.sha256 != d.textSha256 =>
                Left(StoreError.Conflict(s"${d.textRef.uri}: holds other text (pre-write probe)"))
              case _ => Right(())
            }
            row = artifactRow(permit, d)
            item <- artifacts.encode(row)
            key <- artifacts.key(row)
            stored <- t.registry.get(artifacts.table.role, key)
            _ <- stored match {
              case Some(s) if s.get(Attrs.RecordSha256) != item.get(Attrs.RecordSha256) =>
                Left(
                  StoreError.Conflict(
                    s"registry ${key.sk}: holds a different row (pre-write probe)"
                  )
                )
              case _ => Right(())
            }
          } yield ()
        }
      }
    }
  }

  /** Mutable per-call tallies (reported; the ledger keeps its own). */
  private final class Tally {
    val written: mutable.Map[String, Long] = mutable.Map.empty.withDefaultValue(0L)

    /** SE-P8-01: found unchanged by the skip rule (probe), per index. */
    val skipped: mutable.Map[String, Long] = mutable.Map.empty.withDefaultValue(0L)

    /** SE-P8-01: `AlreadyPresent` on put (a concurrent loader stored it after the probe). */
    val duplicates: mutable.Map[String, Long] = mutable.Map.empty.withDefaultValue(0L)
    val embeddedBy: mutable.Map[String, Long] = mutable.Map.empty.withDefaultValue(0L)
    val putRetries: mutable.Map[String, Long] = mutable.Map.empty.withDefaultValue(0L)
    val remaining: mutable.Map[String, Int] = mutable.Map.empty.withDefaultValue(0)

    /** (index, record) of this call, in rejection order. */
    val rejected: mutable.ArrayBuffer[(String, RejectedRecord)] = mutable.ArrayBuffer.empty
    var textWritten = 0L
    var textPresent = 0L
    var embedded = 0L
    var embedRetries = 0L
    def present(ix: String): Long = skipped(ix) + duplicates(ix)
  }

  private final case class Attempted(
      e: Embedded,
      outcome: Either[StoreError, PutOutcome],
      attempts: Int
  )

  /** One batch with bounded retries of transient failures (batch or per record). */
  private def putBatch(
      index: String,
      batch: Vector[Embedded],
      config: VectorLoadConfig,
      t: VectorLoadTargets,
      retried: Int => Unit
  ): Either[StoreError, Vector[Attempted]] = {
    var pending = batch
    var done = Vector.empty[Attempted]
    var attempt = 0
    var fatal: Option[StoreError] = None
    // SE-P8-01 item 4: every record re-sent after a transient failure is counted.
    def backoff(): Unit = {
      retried(pending.size)
      t.sleeper.sleep(config.retry.delay(attempt, t.jitter))
    }
    while (pending.nonEmpty && fatal.isEmpty) {
      attempt += 1
      t.vectors.putVectors(index, pending.map(_.record)) match {
        case Left(StoreError.Transient(why)) =>
          if (attempt >= config.retry.maxAttempts)
            fatal = Some(
              StoreError.Transient(
                s"$index: a batch of ${pending.size} stayed throttled after $attempt attempts ($why)"
              )
            )
          else backoff()
        case Left(e) => fatal = Some(e)
        case Right(rs) =>
          if (rs == null || rs.map(_.key) != pending.map(_.record.key))
            fatal = Some(StoreError.Corrupt(s"$index: put results do not match the batch keys"))
          else {
            val pairs = pending.zip(rs.map(_.outcome))
            val retry = pairs.collect { case (e, Left(StoreError.Transient(_))) => e }
            val settled = pairs.filter {
              case (_, Left(StoreError.Transient(_))) => false
              case _ => true
            }
            done ++= settled.map { case (e, o) => Attempted(e, o, attempt) }
            if (retry.isEmpty) pending = Vector.empty
            else if (attempt >= config.retry.maxAttempts) {
              done ++= pairs.collect { case (e, o @ Left(StoreError.Transient(_))) =>
                Attempted(e, o, attempt)
              }
              pending = Vector.empty
            } else {
              pending = retry
              backoff()
            }
          }
      }
    }
    fatal.toLeft(done)
  }

  /**
   * SE-P7-11 (CR-P7-12 F2): whether any earlier generation of this load recorded a rejection set (a
   * [[RejectionSet]] summary under RL-8, complete or, SE-P8-03, the incomplete set of a failed
   * call). Decided from the STORED evidence, never from the ledger count, so a ledger row that
   * missed an update cannot hide stale evidence; a finished pass then records its (possibly empty)
   * complete set, which becomes the current one.
   */
  private def anySetRecorded(
      permit: LoadPermit,
      t: VectorLoadTargets,
      scope: Option[String]
  ): Either[StoreError, Boolean] =
    for {
      p <- TableKeys.RunLedger.partition(permit.runId)
      prefix <- TableKeys.RunLedger.rejectionSetPrefix(permit.load.loadId, scope)
      rows <- t.ledger.query(
        TableRole.RunLedger,
        KeyQuery(None, p, SortKeyCondition.BeginsWith(prefix), 1)
      )
    } yield rows.nonEmpty

  /**
   * The rejected-record rows, the rejects object and the [[RejectionSet]] summary of one write
   * generation (CR-P7-11 m1; CR-P7-12 F2): keys hold the generation, so no other call ever writes
   * them. Recorded when this generation rejected something, and also (then empty) whenever ANY
   * earlier generation recorded a set, so the highest summarized generation is always the current
   * evidence and never leaves an earlier call's rejections as the last word for records this call
   * stored. The summary is written last: rows without a summary are an incomplete set.
   *
   * SE-P8-03 (CR-P8-02 m-A): `complete` is false for the set of a call that stops with an error
   * (only then is it called with rejections only: a failed call that rejected nothing records
   * nothing). Such a summary is marked `complete = false`, is never the current set, and does not
   * replace the ledger's `rejected` count.
   */
  private def recordRejections(
      permit: LoadPermit,
      layout: S3Layout,
      t: VectorLoadTargets,
      attempt: WriteAttempt,
      all: Vector[RejectedRecord],
      progress: LoadProgress,
      complete: Boolean
  ): Either[StoreError, Unit] =
    (if (all.nonEmpty) Right(true)
     else if (!complete) Right(false)
     else anySetRecorded(permit, t, attempt.embeddingModel)).flatMap { needed =>
      if (!needed) Right(())
      else writeRejectionSet(permit, layout, t, attempt, all, progress, complete)
    }

  private def writeRejectionSet(
      permit: LoadPermit,
      layout: S3Layout,
      t: VectorLoadTargets,
      attempt: WriteAttempt,
      all: Vector[RejectedRecord],
      progress: LoadProgress,
      complete: Boolean
  ): Either[StoreError, Unit] = {
    val sorted = all.sortWith((a, b) => Canonical.compareUtf8(a.recordKey, b.recordKey) < 0)
    val text = sorted.map(RejectedRecord.jsonLine).map(_ + "\n").mkString
    for {
      _ <-
        if (attempt.generation >= 1 && attempt.generation <= RejectedRecord.MaxGeneration)
          Right(())
        else
          Left(
            StoreError.Refused(
              s"vector load: write generation ${attempt.generation} is outside 1..${RejectedRecord.MaxGeneration}"
            )
          )
      _ <- sorted.foldLeft[Either[StoreError, Unit]](Right(())) { (acc, r) =>
        acc.flatMap(_ =>
          rejectsCodec.encode(r).flatMap(t.ledger.putIfAbsent(rejectsCodec.table.role, _)).map {
            case PutOutcome.Written =>
              progress.rejectedOne(); progress.sideWrite("rejected-rows-written")
            case PutOutcome.AlreadyPresent => progress.sideWrite("rejected-rows-present")
          }
        )
      }
      ref <- layout.rejects(permit.load.loadId, attempt.generation.toInt, attempt.embeddingModel)
      o <- t.objects.putCreateOnce(ref, Bytes.utf8(text), RejectsContentType)
      _ = progress.sideWrite(
        if (o == PutOutcome.Written) "rejects-object-written" else "rejects-object-present"
      )
      // CR-P7-12 F2: the commit marker of this generation's set, written last.
      summary <- setCodec.encode(
        RejectionSet(
          RejectionSet.SchemaVersion,
          permit.runId,
          permit.load.loadId,
          TargetStore.S3Vectors,
          attempt.generation,
          sorted.size.toLong,
          ref.key,
          Canonical.sha256HexUtf8(text),
          attempt.embeddingModel,
          complete
        )
      )
      s <- t.ledger.putIfAbsent(setCodec.table.role, summary)
    } yield {
      progress.sideWrite(
        if (s == PutOutcome.Written) "rejection-set-written" else "rejection-set-present"
      )
      // SE-P7-11 item 4: a COMPLETE set is now the current one (the ledger's `rejected`); SE-P8-03:
      // a failed call's partial set leaves the current set and the ledger count unchanged.
      if (complete) progress.rejectionSet(sorted.size.toLong)
    }
  }

  /** The vector of `d` checked against its index, or its rejection. */
  private def checked(
      permit: LoadPermit,
      d: Doc,
      v: Vector[Float],
      dimension: Int,
      generation: Long,
      scope: Option[String]
  ): Either[RejectedRecord, Embedded] =
    if (v == null || v.size != dimension)
      Left(
        reject(
          permit,
          d.item,
          d.key,
          RejectReason.DimensionMismatch,
          s"embedding of dimension ${Option(v).map(_.size).getOrElse(0)}, index ${d.index.name} has $dimension",
          0,
          generation,
          scope
        )
      )
    else
      // SE-P7-11: the digest of the exact floats goes into the vector's metadata.
      VectorDigest.of(v) match {
        case Left(_) =>
          Left(
            reject(
              permit,
              d.item,
              d.key,
              RejectReason.MalformedRecord,
              "non-finite component",
              0,
              generation,
              scope
            )
          )
        case Right(digest) =>
          Right(Embedded(d, VectorRecord(d.key, v, VectorDigest.withDigest(d.metadata, digest))))
      }

  private def writeAll(
      permit: LoadPermit,
      layout: S3Layout,
      config: VectorLoadConfig,
      t: VectorLoadTargets,
      pre: Vector[(String, RejectedRecord)],
      present: collection.Set[String],
      work: Work,
      tally: Tally,
      progress: LoadProgress
  )(attempt: WriteAttempt, docs: Vector[Doc]): Either[StoreError, PassEnd] = {
    val g = attempt.generation
    val scope = attempt.embeddingModel
    val dims = config.indexes.map(i => i.name -> i.dimension).toMap
    def bound(n: Int) =
      StoreError.Refused(
        s"vector load: $n rejected records exceed maxRejected ${config.maxRejected}; the load is failed"
      )
    var recorded = false
    // SE-P8-03 (CR-P8-02 m-A): `complete` only for the set of a finished pass; every set recorded
    // on the way to an error is marked incomplete.
    def record(complete: Boolean) = {
      recorded = true
      recordRejections(
        permit,
        layout,
        t,
        attempt,
        tally.rejected.toVector.map(_._2),
        progress,
        complete
      )
    }
    // CR-P8-01 M1: on EVERY early exit (a put Conflict, a fatal put or embedding error, a failed
    // text or manifest write) the rejections collected so far are recorded as this generation's
    // set before the error is returned (best effort: a recording error never replaces the original
    // error). A failed call's set holds only the items it reached and is recorded INCOMPLETE
    // (SE-P8-03): never the current set, never the ledger's `rejected` count; the ledger row's
    // `failure` names the error. Nothing is recorded when the call rejected nothing. Either way an
    // earlier complete set stays the current one.
    def onExit(r: Either[StoreError, PassEnd]): Either[StoreError, PassEnd] = r match {
      case Left(e) if !recorded =>
        if (tally.rejected.nonEmpty) { val _ = record(complete = false) }
        Left(e)
      case other => other
    }
    // SE-P8-01 item 3: the bounded slice of this call: pending items (not found unchanged by the
    // probe) in manifest order, at most maxItemsPerCall; the rest is left for the next call.
    val pending = docs.filter(d => !present.contains(d.key))
    val slice = pending.take(config.maxItemsPerCall)
    val left = pending.drop(config.maxItemsPerCall)
    left.foreach(d => tally.remaining(d.index.name) += 1)
    tally.rejected ++= pre.map { case (ix, r) =>
      ix -> r.copy(generation = g, embeddingModel = scope)
    }
    if (pre.size > config.maxRejected)
      record(complete = false).flatMap(_ => Left(bound(pre.size)))
    else {
      // 0. SE-P8-01: the work manifest and its registry row (create-once), before any vector.
      val manifest = for {
        o <- t.objects.putCreateOnce(work.ref, work.bytes, EmbeddingWorkManifest.ContentType)
        _ = progress.sideWrite(
          if (o == PutOutcome.Written) "work-manifest-written" else "work-manifest-present"
        )
        row <- artifacts.encode(workRow(permit, work))
        r <- t.registry.putIfAbsent(artifacts.table.role, row)
      } yield {
        progress.sideWrite(if (r == PutOutcome.Written) "registry-written" else "registry-present")
        // The ledger records embedding totals only for a call whose manifest is stored.
        progress.workManifest(work.sha256, work.manifest.items.size.toLong, left.size.toLong)
      }
      // 1. Context text objects and their registry rows (create-once).
      val texts = manifest.flatMap { _ =>
        docs.foldLeft[Either[StoreError, Unit]](Right(())) { (acc, d) =>
          acc.flatMap { _ =>
            for {
              o <- t.objects.putCreateOnce(d.textRef, Bytes.utf8(d.text), TextContentType)
              _ = o match {
                case PutOutcome.Written =>
                  tally.textWritten += 1; progress.sideWrite("text-written")
                case PutOutcome.AlreadyPresent =>
                  tally.textPresent += 1; progress.sideWrite("text-present")
              }
              row <- artifacts.encode(artifactRow(permit, d))
              r <- t.registry.putIfAbsent(artifacts.table.role, row)
            } yield progress.sideWrite(
              if (r == PutOutcome.Written) "registry-written" else "registry-present"
            )
          }
        }
      }
      // 2. The skip rule: vectors found stored and unchanged by the probe are never embedded again
      // (CR-P7-11 m4; SE-P8-01 item 2).
      def skippedOnes(): Unit =
        docs.filter(d => present.contains(d.key)).foreach { d =>
          tally.skipped(d.index.name) += 1; progress.present()
        }
      // 3. This call's slice, per index in batches: embed one batch (bounded retries), then put it
      // with bounded retries.
      val batches = VectorIndexes.all.flatMap(ix =>
        slice.filter(_.index.name == ix.name).grouped(config.batchSize).toVector
      )
      val vectors = texts.flatMap { _ =>
        skippedOnes()
        batches.foldLeft[Either[StoreError, Unit]](Right(())) { (acc, batch) =>
          acc.flatMap { _ =>
            val ix = batch.head.index.name
            tally.embedded += batch.size
            tally.embeddedBy(ix) += batch.size
            progress.embedded(batch.size.toLong)
            // CR-P8-01 M1: a text the embedder refuses alone is a per-item rejection.
            def refused(d: Doc, why: String, n: Int): RejectedRecord =
              reject(
                permit,
                d.item,
                d.key,
                RejectReason.EmbeddingFailure,
                s"embedder: $why",
                n,
                g,
                scope
              )
            val (resolvedSoFar, embedError) = embedResolved(batch, config, t, tally, progress, 0)
            embedError match {
              case Some(e) =>
                // SE-P8-03 (CR-P8-02 n-A): texts already refused alone before the error (for
                // example in the sibling half of a split) keep their rejections; onExit records them.
                tally.rejected ++= resolvedSoFar.collect { case (d, Left((why, n))) =>
                  ix -> refused(d, why, n)
                }
                Left(e)
              case None =>
                val out = resolvedSoFar.map {
                  case (d, Right(v)) => checked(permit, d, v, dims(ix), g, scope)
                  case (d, Left((why, n))) => Left(refused(d, why, n))
                }
                tally.rejected ++= out.collect { case Left(r) => ix -> r }
                val good = out.collect { case Right(e) => e }
                val put =
                  if (good.isEmpty) Right(Vector.empty[Attempted])
                  else
                    putBatch(
                      ix,
                      good,
                      config,
                      t,
                      n => { tally.putRetries(ix) += n; progress.putRetried(n.toLong) }
                    )
                put.flatMap { results =>
                  val conflict = results.collectFirst {
                    case Attempted(_, Left(c: StoreError.Conflict), _) => c
                  }
                  results.foreach {
                    case Attempted(_, Right(PutOutcome.Written), _) =>
                      tally.written(ix) += 1; progress.wrote()
                    case Attempted(_, Right(PutOutcome.AlreadyPresent), _) =>
                      tally.duplicates(ix) += 1; progress.present()
                    case Attempted(_, Left(_: StoreError.Conflict), _) => ()
                    case Attempted(e, Left(err), n) =>
                      val reason =
                        if (err.retryable) RejectReason.RetriesExhausted
                        else RejectReason.PermanentWriteFailure
                      tally.rejected += ix -> reject(
                        permit,
                        e.doc.item,
                        e.doc.key,
                        reason,
                        err.message,
                        n,
                        g,
                        scope
                      )
                  }
                  conflict match {
                    case Some(c) => Left(c) // never an overwrite: the load fails
                    case None if tally.rejected.size > config.maxRejected =>
                      record(complete = false).flatMap(_ => Left(bound(tally.rejected.size)))
                    case None => Right(())
                  }
                }
            }
          }
        }
      }
      // 4. Rejected records of a finished pass (also an empty set after an earlier generation's):
      // the only COMPLETE set (SE-P8-03).
      onExit(
        vectors
          .flatMap(_ => record(complete = true))
          .map(_ => if (left.isEmpty) PassEnd.Complete else PassEnd.Staged)
      )
    }
  }

  /**
   * SE-P8-01 item 4: one embedding call with the same bounded backoff as puts: a `Transient`
   * failure is retried (every re-sent text counted in `embedRetries`) up to `retry.maxAttempts`
   * attempts; then, or on any other error, the error is returned with the attempts made (the
   * caller, [[embedResolved]], splits a `Permanent` batch and stops the call on anything else).
   */
  private def embedBatch(
      texts: Vector[String],
      config: VectorLoadConfig,
      t: VectorLoadTargets,
      tally: Tally,
      progress: LoadProgress
  ): (Either[StoreError, Vector[Vector[Float]]], Int) = {
    var attempt = 0
    var out: Option[Either[StoreError, Vector[Vector[Float]]]] = None
    while (out.isEmpty) {
      attempt += 1
      t.embedder.embed(texts) match {
        case Left(StoreError.Transient(why)) if attempt < config.retry.maxAttempts =>
          tally.embedRetries += texts.size
          progress.embedRetried(texts.size.toLong)
          t.sleeper.sleep(config.retry.delay(attempt, t.jitter))
        case Left(StoreError.Transient(why)) =>
          out = Some(
            Left(
              StoreError.Transient(
                s"embedder: a batch of ${texts.size} stayed throttled after $attempt attempts ($why)"
              )
            )
          )
        case other => out = Some(other)
      }
    }
    (out.get, attempt)
  }

  /**
   * CR-P8-01 M1: one batch embedded and resolved PER ITEM, so a text the provider always refuses
   * cannot wedge the load. [[embedBatch]] first (bounded transient retries). On a `Permanent` batch
   * error the batch is split in halves and each half is embedded again (recursively, halving),
   * until the failing texts are embedded alone; a text whose single-item call is still `Permanent`
   * becomes `Left((final error, attempts))`: a per-item rejection (reason `embedding-failure`, its
   * attempts are every embedding attempt that included it), counted against `maxRejected`, and the
   * load goes on. Bound: at most `2 * batchSize - 1` embedding calls per batch (a halving tree of
   * depth `ceil(log2 batchSize)`), each with at most `retry.maxAttempts` attempts; texts re-sent by
   * a split are counted in `embedRetries`. Any other error (`Transient` after every attempt,
   * `Refused`, `Corrupt`, a wrong vector count) stops the call; its rejections so far are recorded.
   *
   * SE-P8-03 (CR-P8-02 n-A): the result is the items resolved SO FAR plus the error that stopped
   * the batch, if any, so a text already refused alone (for example in the first half of a split)
   * keeps its `embedding-failure` rejection when a later half fails with `Corrupt` or an exhausted
   * `Transient`: the caller records those rejections before it returns the error.
   */
  private def embedResolved(
      batch: Vector[Doc],
      config: VectorLoadConfig,
      t: VectorLoadTargets,
      tally: Tally,
      progress: LoadProgress,
      prior: Int
  ): (Vector[(Doc, Either[(String, Int), Vector[Float]])], Option[StoreError]) = {
    val (out, n) = embedBatch(batch.map(_.text), config, t, tally, progress)
    out match {
      case Right(vs) =>
        if (vs == null || vs.size != batch.size)
          (Vector.empty, Some(StoreError.Corrupt("embedder: returned another number of vectors")))
        else (batch.zip(vs).map((d, v) => d -> Right(v)), None)
      case Left(StoreError.Permanent(why)) if batch.size == 1 =>
        (Vector(batch.head -> Left((why, prior + n))), None)
      case Left(StoreError.Permanent(_)) =>
        tally.embedRetries += batch.size
        progress.embedRetried(batch.size.toLong)
        val (a, b) = batch.splitAt(batch.size / 2)
        val (x, xError) = embedResolved(a, config, t, tally, progress, prior + n)
        if (xError.isDefined) (x, xError)
        else {
          val (y, yError) = embedResolved(b, config, t, tally, progress, prior + n)
          (x ++ y, yError)
        }
      case Left(e) => (Vector.empty, Some(e))
    }
  }

  /** SE-P8-01 item 3: the work manifest of one call, its bytes, address and SHA-256. */
  private final case class Work(
      manifest: EmbeddingWorkManifest,
      ref: ObjectRef,
      bytes: Bytes,
      sha256: String
  )

  private def workOf(
      permit: LoadPermit,
      layout: S3Layout,
      modelVersion: String,
      dimension: Int,
      docs: Vector[Doc]
  ): Either[StoreError, Work] = {
    val m = EmbeddingWorkManifest(
      EmbeddingWorkManifest.SchemaVersion,
      permit.runId,
      permit.load.loadId,
      modelVersion,
      dimension,
      docs.map(d =>
        EmbeddingWorkItem(d.index.name, d.inputId, modelVersion, d.key, d.textSha256, d.textRef.key)
      )
    )
    for {
      b <- EmbeddingWorkManifest.bytes(m)
      ref <- layout.embeddingWorkManifest(permit.load.loadId, modelVersion, b.sha256)
    } yield Work(m, ref, b, b.sha256)
  }

  /** The ArtifactRegistry row of the work manifest object. */
  private def workRow(permit: LoadPermit, w: Work): ArtifactRecord =
    ArtifactRecord(
      ArtifactRecord.SchemaVersion,
      permit.runId,
      Some(permit.load.loadId),
      Zone.SemanticContext.tag,
      w.ref.bucket,
      w.ref.key,
      w.sha256,
      w.bytes.size.toLong,
      EmbeddingWorkManifest.ContentType,
      None,
      Some(EmbeddingWorkManifest.SchemaVersion)
    )

  def load(
      permit: LoadPermit,
      parquet: ParquetReadBounds,
      layout: S3Layout,
      config: VectorLoadConfig,
      t: VectorLoadTargets,
      progress: LoadProgress = new LoadProgress
  ): Either[StoreError, VectorLoadReport] =
    if (Vector(permit, parquet, layout, config, t, progress).contains(null))
      Left(StoreError.Refused("vector load: every argument is required"))
    else if (
      Vector(t.vectors, t.objects, t.registry, t.ledger, t.embedder, t.clock, t.sleeper, t.jitter)
        .contains(null)
    )
      Left(StoreError.Refused("vector load: every target is required"))
    else if (permit.target != TargetStore.S3Vectors)
      Left(StoreError.Refused(s"vector load: the permit is for ${permit.target.tag}"))
    else
      for {
        started <- Right[StoreError, Long](t.clock.nowMillis())
        _ <- checkEmbedder(config, t.embedder)
        _ <- checkIndexes(config, t.vectors, t.embedder)
        model = t.embedder.modelVersion
        p <- plan(permit, parquet, layout, config, model)
        work <- workOf(permit, layout, model, t.embedder.dimension, p.docs)
        tally = new Tally
        present = mutable.Set.empty[String]
        run <- LoadLedger.runScoped(
          permit,
          t.ledger,
          t.clock,
          p.docs,
          WrittenStores,
          progress,
          Some(model)
        )(
          probe(permit, config, t, work, present)
        )(writeAll(permit, layout, config, t, p.rejected, present, work, tally, progress))
        stored <- measureVectors(config, t, p.docs)
        rows <- measureRegistry(permit, t, p.docs)
      } yield {
        val rejectedNow =
          if (run.skipped) Vector.empty[(String, RejectedRecord)] else tally.rejected.toVector
        val counts = VectorIndexes.all.map { ix =>
          VectorIndexCounts(
            ix.name,
            p.routed.getOrElse(ix.name, 0),
            tally.written(ix.name),
            tally.present(ix.name),
            rejectedNow.count(_._1 == ix.name),
            stored.getOrElse(ix.name, 0),
            work.manifest.items.count(_.index == ix.name),
            tally.skipped(ix.name),
            tally.duplicates(ix.name),
            tally.embeddedBy(ix.name),
            tally.putRetries(ix.name),
            tally.remaining(ix.name)
          )
        }
        VectorLoadReport(
          permit.runId,
          permit.load.loadId,
          p.rows,
          counts,
          p.docs.size,
          tally.textWritten,
          tally.textPresent,
          rows,
          rejectedNow.map(_._2),
          run.written,
          run.alreadyPresent,
          run.finalState,
          run.skipped,
          tally.embedded,
          model,
          t.embedder.dimension,
          work.ref.key,
          work.sha256,
          work.manifest.items.size,
          counts.map(_.skippedUnchanged).sum,
          counts.map(_.duplicatesOnPut).sum,
          counts.map(_.putRetries).sum,
          tally.embedRetries,
          counts.map(_.remaining).sum,
          started,
          t.clock.nowMillis()
        )
      }

  /**
   * Routed vectors stored now with this load's metadata and intact floats (SE-P7-11), per index
   * (GetVectors; measured).
   */
  private def measureVectors(
      config: VectorLoadConfig,
      t: VectorLoadTargets,
      docs: Vector[Doc]
  ): Either[StoreError, Map[String, Int]] = {
    val dims = config.indexes.map(i => i.name -> i.dimension).toMap
    docs
      .groupBy(_.index.name)
      .toVector
      .foldLeft[Either[StoreError, Map[String, Int]]](Right(Map.empty)) { case (acc, (ix, xs)) =>
        acc.flatMap { m =>
          xs.grouped(config.limits.maxBatchSize)
            .foldLeft[Either[StoreError, Int]](Right(0)) { (a, g) =>
              a.flatMap { n =>
                t.vectors.getVectors(ix, g.map(_.key)).map { stored =>
                  val mine = g.map(d => d.key -> d).toMap
                  n + stored.count(s =>
                    s != null && mine.get(s.key).exists(storedState(ix, _, dims(ix), s).isRight)
                  )
                }
              }
            }
            .map(n => m.updated(ix, n))
        }
      }
  }

  /** Text registry rows stored and equal now (GetItem; measured). */
  private def measureRegistry(
      permit: LoadPermit,
      t: VectorLoadTargets,
      docs: Vector[Doc]
  ): Either[StoreError, Int] =
    docs.foldLeft[Either[StoreError, Int]](Right(0)) { (acc, d) =>
      for {
        n <- acc
        row = artifactRow(permit, d)
        item <- artifacts.encode(row)
        key <- artifacts.key(row)
        stored <- t.registry.get(artifacts.table.role, key)
      } yield n + stored.count(_.get(Attrs.RecordSha256) == item.get(Attrs.RecordSha256))
    }
}

/**
 * The context document of one summary input (SE-P7-09, V3): a deterministic rendering of its
 * verified facts (summary-evidence passed), NOT generated prose and NOT a prompt or synthesis
 * (those are rag-engineer's). Every value is written as a JSON string literal, so dataset text can
 * never forge a line. The header carries the provenance: run, input, source part and record schema.
 *
 * CR-P7-11 M2: a researcher subject's ER identity confidence (status, anchor namespace, member
 * count, anchor-collision codes, lowest decision confidence) is carried exactly as the input, as
 * one `identity:` line holding its compact core JSON (never dropped, never reinterpreted), followed
 * by readable `identity.*` lines derived from the same value.
 */
object ContextText {

  /**
   * v2 (SE-P7-11 item 3): v1 had no identity lines; v2 adds the `identity:` line and the readable
   * `identity.*` lines (CR-P7-11 M2). Text objects are keyed by their text SHA-256 (chunk ID), so a
   * v2 document never shares a key with a v1 document.
   */
  val Version = "scholarchain.context-document.v2"

  /** The prefix of the identity line; the rest of the line is the identity's core JSON. */
  val IdentityPrefix = "identity: "

  def render(
      runId: String,
      item: LoadItem,
      documentType: DocumentType,
      subjectId: String,
      window: Option[TimeWindow],
      inputId: String,
      identity: Option[IdentityConfidence],
      facts: Vector[(String, String, Vector[(String, String)])]
  ): String = {
    def q(s: String) = upickle.default.write(s)
    val identityLines: Vector[String] = identity.toVector.flatMap { i =>
      val codes = Option(i.collisionCodes).getOrElse(Vector.empty)
      val c = i.lowestDecisionConfidence
      val conf = Option(c).map { dc =>
        Option(dc.value).flatten match {
          case Some(v) => s"${dc.kind.tag} ${v.toString}"
          case None =>
            s"${dc.kind.tag} (${Option(dc.unknownReason).flatten.map(_.tag).getOrElse("no reason")})"
        }
      }
      Vector(
        IdentityPrefix + upickle.default.write(i),
        s"identity.status: ${i.status.tag}",
        s"identity.anchorNamespace: ${q(i.anchorNamespace)}",
        s"identity.memberCount: ${i.memberCount}",
        "identity.anchorCollisionCodes: " +
          (if (codes.isEmpty) "none" else codes.map(q).mkString(", ")),
        s"identity.lowestDecisionConfidence: ${conf.getOrElse("missing")}"
      )
    }
    val header = Vector(
      Version,
      "notice: deterministic rendering of verified Spark summary-input facts; not generated text",
      s"documentType: ${documentType.tag}",
      s"subjectId: $subjectId"
    ) ++ window.map(w => s"window: ${w.startYear}-${w.endYear}").toVector ++ Vector(
      s"inputId: $inputId",
      s"runId: $runId",
      s"sourcePath: ${item.path}",
      s"recordSchemaVersion: ${item.recordSchemaVersion}"
    ) ++ identityLines ++ Vector("facts:")
    val body = facts.flatMap { case (name, value, ev) =>
      Vector(s"- ${q(name)}: ${q(value)}") ++
        Option
          .when(ev.nonEmpty)("  evidence: " + ev.map { case (k, id) => s"$k:$id" }.mkString(", "))
          .toVector
    }
    (header ++ body).mkString("", "\n", "\n")
  }
}
