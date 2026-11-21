package scholarchain.storage.model

import java.nio.charset.StandardCharsets

import scala.collection.immutable.SortedMap

import scholarchain.core.{ChunkId, DocumentType, IdKind, SourceType, StableId}

/** Value type of one metadata field. */
enum MetaType {
  case Str
  case StrList
  case Int
}

/**
 * The vector metadata fields (SE-P7-01; README L1393-1395). Filterable fields are compact IDs, tags
 * and years; non-filterable fields point at the long text in ordinary S3 and record its hash, model
 * version and lineage. No field holds text.
 */
enum MetadataField(val key: String, val filterable: Boolean, val tpe: MetaType) {
  case DocumentTypeField extends MetadataField("documentType", true, MetaType.Str)
  case SubjectId extends MetadataField("subjectId", true, MetaType.Str)
  case RunId extends MetadataField("runId", true, MetaType.Str)
  case IndexVersion extends MetadataField("indexVersion", true, MetaType.Str)
  case SourceDataset extends MetadataField("sourceDataset", true, MetaType.Str)
  case InputSources extends MetadataField("inputSources", true, MetaType.StrList)
  case ResearcherIds extends MetadataField("researcherIds", true, MetaType.StrList)
  case WindowStart extends MetadataField("windowStart", true, MetaType.Int)
  case WindowEnd extends MetadataField("windowEnd", true, MetaType.Int)
  case CommunityId extends MetadataField("communityId", true, MetaType.Str)
  case TopicIds extends MetadataField("topicIds", true, MetaType.StrList)
  case InstitutionIds extends MetadataField("institutionIds", true, MetaType.StrList)

  /**
   * CR-P7-11 M2: the researcher subject's ER identity status (core `IdentityStatus` tag), carried
   * from the summary input's identity confidence (C5).
   */
  case IdentityStatusField extends MetadataField("identityStatus", true, MetaType.Str)

  /**
   * CR-P7-11 M2: `true` when the researcher's identity carries at least one anchor-collision code
   * (`grouping.anchor-collision.*`, a possible over-merge), else `false`. The codes themselves are
   * in the context text.
   */
  case AnchorCollision extends MetadataField("anchorCollision", true, MetaType.Str)
  case TextS3Key extends MetadataField("textS3Key", false, MetaType.Str)
  case TextSha256 extends MetadataField("textSha256", false, MetaType.Str)
  case ModelVersion extends MetadataField("modelVersion", false, MetaType.Str)

  /**
   * SE-P7-11 (human requirement 2026-11-20; CR-P7-12 F1 D2): the [[VectorDigest]] of the exact
   * floats that were put, computed at write time. Every pre-write probe and every runtime read
   * recomputes it from the stored floats and refuses a mismatch (`Corrupt`), so changed vector
   * values are detected even when the metadata is intact, without requiring a deterministic
   * embedder. Non-filterable; the Terraform index definitions must declare it non-filterable
   * (infra-engineer).
   */
  case DataSha256 extends MetadataField("dataSha256", false, MetaType.Str)
  case LoadId extends MetadataField("loadId", false, MetaType.Str)
  case InputId extends MetadataField("inputId", false, MetaType.Str)
  case SchemaVersion extends MetadataField("schemaVersion", false, MetaType.Str)

  /** SE-P7-09: the run-relative path of the source part (provenance; non-filterable). */
  case SourcePath extends MetadataField("sourcePath", false, MetaType.Str)
}
object MetadataField {
  def fromKey(key: String): Option[MetadataField] = values.find(_.key == key)
  val filterableFields: Vector[MetadataField] = values.toVector.filter(_.filterable)
  val nonFilterableFields: Vector[MetadataField] = values.toVector.filterNot(_.filterable)
}

/** One metadata value. */
enum MetaValue {
  case Str(value: String)
  case StrList(values: Vector[String])
  case Num(value: Long)

  def tpe: MetaType = this match {
    case Str(_) => MetaType.Str
    case StrList(_) => MetaType.StrList
    case Num(_) => MetaType.Int
  }

  /** Size in bytes: UTF-8 bytes of the strings, 8 for a number. */
  def byteSize: Int = this match {
    case Str(v) => v.getBytes(StandardCharsets.UTF_8).length
    case StrList(vs) => vs.map(_.getBytes(StandardCharsets.UTF_8).length).sum
    case Num(_) => 8
  }
}

/** Metadata of one vector, keyed by field key. */
final case class VectorMetadata(values: SortedMap[String, MetaValue]) {
  def get(f: MetadataField): Option[MetaValue] = values.get(f.key)
  def string(f: MetadataField): Option[String] = get(f).collect { case MetaValue.Str(v) => v }
}

object VectorMetadata {

  /**
   * The `schemaVersion` metadata value of every vector the loader writes. v2 (SE-P7-11, CR-P7-12
   * F1): adds the required non-filterable `dataSha256` ([[VectorDigest.Scheme]]).
   */
  val SchemaVersion = "scholarchain.storage.vector-metadata.v2"
}

/**
 * SE-P7-11 (human requirement 2026-11-20; CR-P7-12 F1 D1): integrity digest of a vector's floats.
 *
 * Scheme [[VectorDigest.Scheme]] (frozen): lowercase hex SHA-256 over the UTF-8 bytes of
 * `scholarchain.vector-data.v1\n<dimension>\n` (dimension in decimal), followed by every component
 * in order as its IEEE-754 binary32 bits (`Float.floatToIntBits`), 4 bytes big-endian, with `-0.0f`
 * mapped to `+0.0f` first. The dimension and the component order are part of the digest (a
 * permuted, truncated, extended or swapped vector has another digest). NaN and infinities are
 * refused, never canonicalised. The loader computes it from exactly the floats it puts.
 *
 * RESIDUALS (documented, not closed):
 *   - It detects corruption (changed stored values, a faulty adapter, a partial write), NOT
 *     deliberate tampering: a writer that can replace a vector can also replace its `dataSha256`.
 *     Catching that would need a keyed MAC (key from Secrets Manager), not implemented.
 *   - S3 Vectors float32 bit-exactness on a PutVectors/GetVectors round trip is UNVERIFIED until
 *     the Phase 8 live acceptance. If the service quantises or otherwise changes the floats, this
 *     check must keep failing closed (every such vector refused); it must not be relaxed to a
 *     tolerance or a metadata-only comparison.
 */
object VectorDigest {

  /** The domain-separation header of scheme v1 (before the dimension line). */
  val Scheme = "scholarchain.vector-data.v1"

  /**
   * A well-formed digest of the right length, used only to size-check metadata before the real
   * digest exists (the metadata budget is checked with exactly the bytes the digest will occupy).
   */
  val SizingPlaceholder: String = "0" * 64

  /** The digest of `data`, or `Refused` for missing, empty or non-finite data. */
  def of(data: Vector[Float]): Either[StoreError, String] =
    preimage(data).map((b: Array[Byte]) => scholarchain.core.Canonical.sha256Hex(b))

  /**
   * SE-P7-12 (CR-P7-13 m1): exactly the bytes [[of]] hashes (header, then the components), exposed
   * to storage tests so the scheme can be pinned against a hand-written byte literal.
   */
  private[storage] def preimage(data: Vector[Float]): Either[StoreError, Array[Byte]] =
    if (data == null || data.isEmpty) Left(StoreError.Refused("vector data: missing or empty"))
    else if (data.exists(f => f.isNaN || f.isInfinite))
      Left(StoreError.Refused("vector data: a non-finite component (NaN or infinity) is refused"))
    else {
      val header =
        s"$Scheme\n${data.size}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)
      val bb = java.nio.ByteBuffer.allocate(header.length + 4 * data.size) // big-endian
      bb.put(header)
      data.foreach(f => bb.putInt(java.lang.Float.floatToIntBits(if (f == 0.0f) 0.0f else f)))
      Right(bb.array())
    }

  /** `md` with `dataSha256` set to `digest`. */
  def withDigest(md: VectorMetadata, digest: String): VectorMetadata =
    VectorMetadata(md.values.updated(MetadataField.DataSha256.key, MetaValue.Str(digest)))

  /** `md` without `dataSha256` (the part a loader can compute before embedding). */
  def withoutDigest(md: VectorMetadata): VectorMetadata =
    VectorMetadata(md.values - MetadataField.DataSha256.key)

  /**
   * Whether a STORED vector is intact (CR-P7-12 D3): its metadata holds a well-formed `dataSha256`
   * and the digest recomputed from its stored floats equals it. Any other case is `Corrupt` naming
   * the key (fail closed; no legacy acceptance, no metadata-only fallback): no metadata, no digest,
   * a malformed digest, missing, empty or non-finite data, or a mismatch.
   */
  def verify(index: String, r: VectorRecord): Either[StoreError, Unit] =
    if (r == null) Left(StoreError.Corrupt(s"$index: a stored vector is missing"))
    else {
      val where = s"$index ${r.key}"
      val stored =
        Option(r.metadata).filter(_.values != null).flatMap(_.string(MetadataField.DataSha256))
      stored match {
        case None =>
          Left(StoreError.Corrupt(s"$where: no dataSha256 in its metadata (refused, unverifiable)"))
        case Some(s) if !scholarchain.core.Canonical.isHex64(s) =>
          Left(StoreError.Corrupt(s"$where: dataSha256 is not 64 lowercase hex characters"))
        case Some(s) =>
          of(r.data) match {
            case Left(e) =>
              Left(StoreError.Corrupt(s"$where: stored data is not a valid vector (${e.message})"))
            case Right(d) if d != s =>
              Left(
                StoreError.Corrupt(
                  s"$where: the stored vector values do not match its dataSha256 (changed or " +
                    "corrupted data; refused)"
                )
              )
            case Right(_) => Right(())
          }
      }
    }
}

/**
 * Metadata limits (S3 Vectors service limits, UNVERIFIED: mcp__aws-docs was unavailable in SE-P7-01
 * and SE-P7-02; supplied by configuration and recorded with the date checked when verified).
 *
 * @param maxValueBytes
 *   per-value cap: any single value above it is refused, which keeps long text out of metadata
 */
final case class VectorLimits(
    maxBatchSize: Int,
    maxValueBytes: Int,
    maxFilterableBytes: Int,
    maxTotalBytes: Int,
    maxKeys: Int,
    maxNonFilterableKeys: Int,
    maxListValues: Int,
    maxTopK: Int
)

object VectorLimits {
  def problems(l: VectorLimits): Vector[String] =
    if (l == null) Vector("limits: missing")
    else
      Vector(
        "maxBatchSize" -> l.maxBatchSize,
        "maxValueBytes" -> l.maxValueBytes,
        "maxFilterableBytes" -> l.maxFilterableBytes,
        "maxTotalBytes" -> l.maxTotalBytes,
        "maxKeys" -> l.maxKeys,
        "maxNonFilterableKeys" -> l.maxNonFilterableKeys,
        "maxListValues" -> l.maxListValues,
        "maxTopK" -> l.maxTopK
      ).collect { case (k, v) if v < 1 => s"$k: must be >= 1" }
}

/**
 * One vector index (README L1403-1408). `name` is also the index version recorded in metadata and
 * the source version of every vector key. Index creation is Terraform (infra-engineer); this is the
 * logical schema the loader and the runtime use.
 *
 * @param documentTypes
 *   the document types routed here; every [[DocumentType]] routes to exactly one index
 */
final case class VectorIndexSpec(
    name: String,
    documentTypes: Vector[DocumentType],
    required: Vector[MetadataField],
    optional: Vector[MetadataField],
    note: String
) {
  def allowed: Set[MetadataField] = (required ++ optional).toSet
}

object VectorIndexes {
  import MetadataField.*

  /** Fields every index requires. */
  val Common: Vector[MetadataField] = Vector(
    DocumentTypeField,
    SubjectId,
    RunId,
    IndexVersion,
    SourceDataset,
    TextS3Key,
    TextSha256,
    ModelVersion,
    DataSha256,
    LoadId,
    InputId,
    SchemaVersion,
    SourcePath
  )

  val ResearcherProfile: VectorIndexSpec = VectorIndexSpec(
    "researcher-profile-v1",
    Vector(DocumentType.ResearcherProfile),
    Common ++ Vector(ResearcherIds, WindowStart, WindowEnd, IdentityStatusField, AnchorCollision),
    Vector(InputSources, CommunityId, TopicIds, InstitutionIds),
    "researcher-profile documents; identityStatus and anchorCollision carry the subject's ER " +
      "identity confidence (CR-P7-11 M2; every researcher-profile input has one in core)"
  )

  /** The `anchorCollision` metadata value of an identity with or without collision codes. */
  def anchorCollisionValue(collision: Boolean): String = if (collision) "true" else "false"

  val CollaborationCommunity: VectorIndexSpec = VectorIndexSpec(
    "collaboration-community-v1",
    Vector(DocumentType.CommunitySummary),
    Common ++ Vector(CommunityId, WindowStart, WindowEnd),
    Vector(InputSources, ResearcherIds, TopicIds, InstitutionIds),
    "community-summary documents"
  )

  val CollaborationPath: VectorIndexSpec = VectorIndexSpec(
    "collaboration-path-v1",
    Vector(DocumentType.PathSummary, DocumentType.CollaborationSummary),
    Common ++ Vector(WindowStart, WindowEnd),
    Vector(InputSources, CommunityId, ResearcherIds),
    "path-summary documents; collaboration-summary routed here provisionally (orchestrator " +
      "decision on SE-P7-01; rag-engineer may revise in Phase 8/9). researcherIds is optional " +
      "(SE-P7-09): the fixture's path-summary inputs cite only the path, its component and " +
      "coauthor edges, never a researcher ID, and the evidence-path record is not an s3-vectors " +
      "item, so the IDs are not invented"
  )

  val PaperAbstract: VectorIndexSpec = VectorIndexSpec(
    "paper-abstract-v1",
    Vector(DocumentType.WorkSummary, DocumentType.WorkAbstract),
    Common,
    Vector(InputSources, ResearcherIds, TopicIds, InstitutionIds, WindowStart, WindowEnd),
    "work-summary documents; the archived run has no work-abstract rows (reported, not invented)"
  )

  val TopicInstitutionSummary: VectorIndexSpec = VectorIndexSpec(
    "topic-institution-summary-v1",
    Vector(DocumentType.TopicSummary),
    Common,
    Vector(InputSources, TopicIds, InstitutionIds, ResearcherIds),
    "topic-summary documents; the archived run has no institution-summary rows (reported, not " +
      "invented; core has no institution document type)"
  )

  /** Exactly the five required indexes, in README order. */
  val all: Vector[VectorIndexSpec] =
    Vector(
      ResearcherProfile,
      CollaborationCommunity,
      PaperAbstract,
      CollaborationPath,
      TopicInstitutionSummary
    )

  def byName(name: String): Option[VectorIndexSpec] = all.find(_.name == name)

  /** The one index a document type routes to. */
  def forDocumentType(d: DocumentType): VectorIndexSpec = all.find(_.documentTypes.contains(d)).get
}

/**
 * Vector keys and text chunk IDs (SE-P7-01 law 1; SE-P7-09 V2): a key never silently refers to
 * different text.
 */
object VectorKeys {

  /**
   * The stable vector key (SE-P7-09, V2): `<index name>/<inputId>`, the index version and the core
   * stable ID of the source summary input (`suminput:`, which already binds document type, subject,
   * window and graph policy). It does not depend on the text, so the store's GetVectors-compare put
   * refuses other text under the key (`Conflict`; the metadata carries `textSha256`) and equal text
   * is `AlreadyPresent`. It carries no run ID: one analysis run per store instance (human decision
   * 2026-11-20).
   */
  def of(index: VectorIndexSpec, inputId: String): Either[StoreError, String] =
    if (index == null) Left(StoreError.Refused("index: missing"))
    else
      StableId
        .validate(IdKind.SummaryInput, inputId, "inputId")
        .left
        .map(e => StoreError.Refused(e.message))
        .map(id => s"${index.name}/$id")

  /**
   * The text chunk ID of a context document: `core` [[ChunkId.ofTextHash]](subjectId, index name,
   * documentType, no ordinal, textSha256): the text hash is an input, so other text always yields
   * another chunk ID (and another text object key). CR-P7-15 N2: the text object key therefore
   * commits to the EXPECTED text hash: an object under that key that is not that text is never
   * accepted as it. The loader refuses (never overwrites) an object whose recorded SHA-256 differs
   * (pre-write HEAD probe) or whose bytes differ on a create-once put; bytes changed behind intact
   * object metadata are not detected by the HEAD probe (documented residual).
   */
  def chunkId(
      subjectId: String,
      documentType: DocumentType,
      textSha256: String
  ): Either[StoreError, String] =
    if (documentType == null) Left(StoreError.Refused("documentType: missing"))
    else
      ChunkId
        .ofTextHash(
          subjectId,
          VectorIndexes.forDocumentType(documentType).name,
          documentType,
          None,
          textSha256
        )
        .left
        .map(e => StoreError.Refused(e.message))
}

/** Metadata validation per index and per limits (SE-P7-01). */
object VectorMetadataRules {
  import MetadataField.*

  private def idOf(kind: IdKind, v: String, f: MetadataField): Option[String] =
    StableId.validate(kind, v, f.key).left.toOption.map(_.message)

  /** Service-limit checks only (also applied by the fake store on write). */
  def limitProblems(md: VectorMetadata, limits: VectorLimits): Vector[String] =
    if (md == null || md.values == null) Vector("metadata: missing")
    else {
      val entries = md.values.toVector
      val known = entries.flatMap { case (k, v) => fromKey(k).map(_ -> v) }
      val filterableBytes =
        known.collect { case (f, v) if f.filterable => f.key.length + v.byteSize }.sum
      val totalBytes = entries.map { case (k, v) => k.length + v.byteSize }.sum
      entries.collect {
        case (k, v) if v.byteSize > limits.maxValueBytes =>
          s"$k: value of ${v.byteSize} bytes exceeds the per-value cap ${limits.maxValueBytes}"
      } ++ entries.collect {
        case (k, MetaValue.StrList(vs)) if vs.size > limits.maxListValues =>
          s"$k: ${vs.size} values exceed maxListValues ${limits.maxListValues}"
      } ++
        Option
          .when(entries.size > limits.maxKeys)(s"metadata: ${entries.size} keys exceed maxKeys")
          .toVector ++
        Option
          .when(known.count(!_._1.filterable) > limits.maxNonFilterableKeys)(
            "metadata: too many non-filterable keys"
          )
          .toVector ++
        Option
          .when(filterableBytes > limits.maxFilterableBytes)(
            s"metadata: filterable size $filterableBytes exceeds ${limits.maxFilterableBytes}"
          )
          .toVector ++
        Option
          .when(totalBytes > limits.maxTotalBytes)(
            s"metadata: total size $totalBytes exceeds ${limits.maxTotalBytes}"
          )
          .toVector
    }

  /** Every problem of the metadata of one vector of `index`; empty when valid. */
  def problems(index: VectorIndexSpec, md: VectorMetadata, limits: VectorLimits): Vector[String] =
    if (md == null || md.values == null) Vector("metadata: missing")
    else {
      val unknown = md.values.keys.toVector.filter(k => fromKey(k).forall(f => !index.allowed(f)))
      val missing = index.required.filterNot(f => md.values.contains(f.key))
      val typed = md.values.toVector.flatMap { case (k, v) =>
        fromKey(k).filter(index.allowed).toVector.flatMap { f =>
          if (v.tpe != f.tpe) Vector(s"$k: expected ${f.tpe}, found ${v.tpe}") else Vector.empty
        }
      }
      val values = if (typed.nonEmpty) Vector.empty else valueProblems(index, md)
      unknown.map(k => s"$k: not a metadata field of ${index.name}") ++
        missing.map(f => s"${f.key}: missing") ++ typed ++ values ++ limitProblems(md, limits)
    }

  private def valueProblems(index: VectorIndexSpec, md: VectorMetadata): Vector[String] = {
    def str(f: MetadataField) = md.string(f)
    def list(f: MetadataField) = md.get(f).collect { case MetaValue.StrList(vs) => vs }
    def num(f: MetadataField) = md.get(f).collect { case MetaValue.Num(n) => n }
    def ids(f: MetadataField, kind: IdKind) =
      list(f).toVector.flatten.flatMap(idOf(kind, _, f)) ++
        list(f).toVector.flatMap(vs =>
          Option.when(vs.distinct.size != vs.size)(s"${f.key}: duplicate values")
        )
    val docType = str(DocumentTypeField)
    Vector(
      docType.flatMap { t =>
        DocumentType.fromTag(t) match {
          case None => Some(s"documentType: '$t' is not a document type")
          case Some(d) if !index.documentTypes.contains(d) =>
            Some(s"documentType: '$t' does not route to ${index.name}")
          case _ => None
        }
      },
      str(SubjectId).flatMap(s =>
        StableId.validateAny(s, "subjectId").left.toOption.map(_.message)
      ),
      str(RunId).flatMap(idOf(IdKind.Run, _, RunId)),
      str(LoadId).flatMap(idOf(IdKind.StoreLoad, _, LoadId)),
      str(InputId).flatMap(idOf(IdKind.SummaryInput, _, InputId)),
      str(CommunityId).flatMap(idOf(IdKind.Community, _, CommunityId)),
      str(IndexVersion).flatMap(v =>
        Option.when(v != index.name)(s"indexVersion: '$v' is not ${index.name}")
      ),
      str(SourceDataset).flatMap(v =>
        Option.when(SourceType.fromTag(v).isEmpty)(s"sourceDataset: '$v' is not a source tag")
      ),
      str(TextSha256).flatMap(v =>
        Option.when(!scholarchain.core.Canonical.isHex64(v))(
          "textSha256: expected 64 lowercase hex characters"
        )
      ),
      str(DataSha256).flatMap(v =>
        Option.when(!scholarchain.core.Canonical.isHex64(v))(
          "dataSha256: expected 64 lowercase hex characters"
        )
      ),
      str(TextS3Key).flatMap(v =>
        Option.when(v.isEmpty || v.exists(c => c.isWhitespace || c.isControl))(
          "textS3Key: missing or has whitespace"
        )
      ),
      str(ModelVersion).flatMap(v =>
        Option.when(!StableId.isToken(v))("modelVersion: missing or not a token")
      ),
      str(SchemaVersion).flatMap(v =>
        Option.when(!StableId.isToken(v))("schemaVersion: missing or not a token")
      ),
      str(SourcePath).flatMap(v => S3Layout.relativeProblem(v).map("sourcePath: " + _)),
      str(IdentityStatusField).flatMap(v =>
        Option.when(scholarchain.core.IdentityStatus.fromTag(v).isEmpty)(
          s"identityStatus: '$v' is not an identity status"
        )
      ),
      str(AnchorCollision).flatMap(v =>
        Option.when(v != "true" && v != "false")(s"anchorCollision: '$v' is not true or false")
      ),
      (num(WindowStart), num(WindowEnd)) match {
        case (Some(s), Some(e)) if s > e => Some(s"windowStart: $s is after windowEnd $e")
        case (Some(_), None) | (None, Some(_)) =>
          Some("windowStart, windowEnd: set together or not at all")
        case _ => None
      }
    ).flatten ++
      Vector(WindowStart, WindowEnd).flatMap(f =>
        num(f).toVector.flatMap(n => StoreIds.year(n.toInt, f.key).left.toOption.map(_.message))
      ) ++
      list(InputSources).toVector.flatten.flatMap(v =>
        Option.when(SourceType.fromTag(v).forall(!_.isDataset))(
          s"inputSources: '$v' is not a dataset source tag"
        )
      ) ++
      ids(ResearcherIds, IdKind.Researcher) ++ ids(TopicIds, IdKind.Topic) ++
      ids(InstitutionIds, IdKind.Institution)
  }
}

/**
 * Which embedding models a vector load accepts (SE-P7-09). A model version starting with
 * [[EmbeddingPolicy.SyntheticPrefix]] is synthetic (test vectors, no meaning).
 */
enum EmbeddingPolicy {

  /** Production: a synthetic model version is refused before any write. */
  case RealModelsOnly

  /** Local tests only: anything but a synthetic model version is refused (no real model call). */
  case SyntheticTestOnly
}

object EmbeddingPolicy {
  val SyntheticPrefix = "synthetic-"

  def isSynthetic(modelVersion: String): Boolean =
    modelVersion != null && modelVersion.startsWith(SyntheticPrefix)

  /**
   * CR-P7-11 m2: why `policy` refuses a model version (None when accepted). `RealModelsOnly`
   * refuses a synthetic or missing model version; `SyntheticTestOnly` refuses anything else. Used
   * by the loader (the embedder) and by the runtime vector reader (each stored vector's
   * `modelVersion`).
   */
  def refusal(policy: EmbeddingPolicy, modelVersion: Option[String]): Option[String] =
    (policy, modelVersion) match {
      case (null, _) => Some("no embedding policy")
      case (_, None) => Some("no modelVersion")
      case (RealModelsOnly, Some(m)) if isSynthetic(m) =>
        Some(s"$m: synthetic test vectors are refused by the RealModelsOnly policy (production)")
      case (SyntheticTestOnly, Some(m)) if !isSynthetic(m) =>
        Some(
          s"$m: the SyntheticTestOnly policy accepts only synthetic model versions (prefix " +
            s"$SyntheticPrefix)"
        )
      case _ => None
    }
}

/** Distance metric of an index. */
enum DistanceMetric(val tag: String) {
  case Cosine extends DistanceMetric("cosine")
  case Euclidean extends DistanceMetric("euclidean")
}

/** What an index inspection reports (creation is Terraform). */
final case class IndexInfo(
    name: String,
    dimension: Int,
    distance: DistanceMetric,
    nonFilterableKeys: Vector[String]
)

/** One vector to write or read. */
final case class VectorRecord(key: String, data: Vector[Float], metadata: VectorMetadata)

/** One query match: smaller distance is closer. */
final case class VectorMatch(key: String, distance: Double, metadata: VectorMetadata)

/** The per-record result of a batch put. */
final case class VectorPutResult(key: String, outcome: Either[StoreError, PutOutcome])

/**
 * The metadata filter model (README L1395): equality, `in` and integer ranges over filterable
 * fields of the index, combined with `and`.
 */
enum VectorFilter {
  case Eq(field: MetadataField, value: String)
  case In(field: MetadataField, values: Vector[String])
  case Range(field: MetadataField, min: Long, max: Long)
  case And(filters: Vector[VectorFilter])
}

object VectorFilters {
  import VectorFilter.*

  /** Every problem of a filter for `index`; empty when valid. */
  def problems(index: VectorIndexSpec, f: VectorFilter): Vector[String] = {
    def field(m: MetadataField): Vector[String] =
      if (m == null) Vector("filter: field missing")
      else if (!m.filterable) Vector(s"${m.key}: not filterable")
      else if (!index.allowed(m)) Vector(s"${m.key}: not a field of ${index.name}")
      else Vector.empty
    f match {
      case null => Vector("filter: missing")
      case Eq(m, v) =>
        field(m) ++ Option
          .when(m != null && m.tpe == MetaType.Int)(s"${m.key}: use a range")
          .toVector ++
          Option
            .when(v == null || v.isEmpty)(s"${Option(m).map(_.key).orNull}: empty value")
            .toVector
      case In(m, vs) =>
        field(m) ++ Option
          .when(m != null && m.tpe == MetaType.Int)(s"${m.key}: use a range")
          .toVector ++
          Option
            .when(vs == null || vs.isEmpty)(s"${Option(m).map(_.key).orNull}: empty value set")
            .toVector
      case Range(m, lo, hi) =>
        field(m) ++ Option
          .when(m != null && m.tpe != MetaType.Int)(s"${m.key}: ranges need an integer field")
          .toVector ++
          Option.when(lo > hi)(s"${Option(m).map(_.key).orNull}: min $lo is above max $hi").toVector
      case And(fs) =>
        if (fs == null || fs.isEmpty) Vector("and: no filters") else fs.flatMap(problems(index, _))
    }
  }

  /** Whether `md` satisfies `f` (list fields match when any element matches). */
  def matches(f: VectorFilter, md: VectorMetadata): Boolean = f match {
    case Eq(m, v) =>
      md.get(m) match {
        case Some(MetaValue.Str(s)) => s == v
        case Some(MetaValue.StrList(vs)) => vs.contains(v)
        case _ => false
      }
    case In(m, vs) =>
      md.get(m) match {
        case Some(MetaValue.Str(s)) => vs.contains(s)
        case Some(MetaValue.StrList(xs)) => xs.exists(vs.contains)
        case _ => false
      }
    case Range(m, lo, hi) =>
      md.get(m) match {
        case Some(MetaValue.Num(n)) => n >= lo && n <= hi
        case _ => false
      }
    case And(fs) => fs.forall(matches(_, md))
  }
}
