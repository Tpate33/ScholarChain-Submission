package scholarchain.core

import scala.collection.immutable.SortedMap

import upickle.default.ReadWriter
import upickle.implicits.allowUnknownKeys

import Validated.{all, at, when}

/**
 * One blocking key with its fallback level (0 = primary key; `l + 1` refines an oversize level-`l`
 * block). Keys are produced by the versioned entity-resolution key formulas; their content is not
 * interpreted here. A key is non-blank text without control characters. Ordered by level, then by
 * the UTF-8 bytes of the key ([[BlockKey.compare]]).
 */
@allowUnknownKeys(false)
final case class BlockKey(level: Int, key: String) derives ReadWriter

object BlockKey {
  def checkKey(field: String, key: String): Vector[ValidationError] = Validated.text(field, key)

  def check(field: String, b: BlockKey): Vector[ValidationError] =
    if (b == null) Vector(ValidationError.MissingField(field))
    else
      when(b.level < 0)(
        ValidationError.InvalidValue(at(field, "level"), s"${b.level} is negative")
      ) ++
        checkKey(at(field, "key"), b.key)

  def compare(a: BlockKey, b: BlockKey): Int =
    if (a.level != b.level) Integer.compare(a.level, b.level)
    else Canonical.compareUtf8(a.key, b.key)

  /** Sorted, duplicate-free copy. */
  def canonical(keys: Vector[BlockKey]): Vector[BlockKey] =
    keys.distinct.sortWith((a, b) => compare(a, b) < 0)
}

/** Endpoint ID rules shared by candidate blocks and match candidates. */
private[core] object CandidateRules {
  val EndpointKinds: Map[MatchEntityKind, Set[IdKind]] = Map(
    MatchEntityKind.Researcher -> Set(IdKind.Mention, IdKind.SourceAuthor),
    MatchEntityKind.Work -> Set(IdKind.Work)
  )

  def kindCheck(field: String, k: MatchEntityKind): Vector[ValidationError] =
    when(k == null || !EndpointKinds.contains(k))(
      ValidationError.InvalidValue(field, "must be 'researcher' or 'work'")
    )

  def endpointCheck(field: String, k: MatchEntityKind, id: String): Vector[ValidationError] =
    EndpointKinds.get(k).toVector.flatMap(StableId.checkOneOf(_, id, field))

  /**
   * The dataset source of an endpoint, when its ID names one: the namespace of a work ID or of a
   * source author profile ID. Author mention IDs are hashes and name no source.
   */
  def namedSource(id: String): Option[SourceType] =
    StableId.prefixKind(id) match {
      case Some(IdKind.Work) =>
        WorkRecord.NamespaceOf.collectFirst {
          case (s, ns) if id.startsWith(s"work:$ns:") => s
        }
      case Some(IdKind.SourceAuthor) => SourceAuthorId.sourceOf(id)
      case _ => None
    }

  def sourceCheck(field: String, id: String, s: SourceType): Vector[ValidationError] =
    if (s == null || !s.isDataset)
      Vector(ValidationError.InvalidValue(field, "must be a dataset source"))
    else
      namedSource(id).toVector.flatMap { named =>
        when(named != s)(
          ValidationError
            .InvalidValue(field, s"'${s.tag}' differs from the ID's source '${named.tag}'")
        )
      }

  def derivedCheck(field: String, pr: Provenance): Vector[ValidationError] =
    when(pr != null && pr.sourceDataset != null && pr.sourceDataset != SourceType.Derived)(
      ValidationError.InvalidValue(field, "must be 'scholarchain-derived'")
    )
}

/**
 * One bounded candidate block produced by blocking (README Phase 4 job 4): the members that share
 * one key at one fallback level. Not a match decision.
 *
 * @param blockId
 *   [[CandidateBlockId.of]](entityKind, blockingVersion, level, blockKey, provenance.runId)
 * @param blockingVersion
 *   version of the blocking-key formulas (entity-resolution policy) that produced `blockKey`
 * @param level
 *   fallback level of `blockKey` (0 = primary)
 * @param maxBlockSize
 *   the configured maximum block size in force for the run (>= 2); `size <= maxBlockSize`
 * @param size
 *   number of members (= `memberIds.size`, >= 1)
 * @param memberIds
 *   author mention or source author profile IDs (researcher blocks) or work IDs (work blocks),
 *   sorted by UTF-8 bytes without duplicates
 * @param sourceDatasets
 *   dataset sources of the members, sorted by tag without duplicates
 * @param provenance
 *   `scholarchain-derived`; snapshot and runId of the blocking run (the run manifest lists every
 *   input snapshot); sourceSchemaVersion is the schema of the blocked input records
 */
@allowUnknownKeys(false)
final case class CandidateBlock(
    schemaVersion: String,
    blockId: String,
    entityKind: MatchEntityKind,
    blockingVersion: String,
    level: Int,
    blockKey: String,
    maxBlockSize: Int,
    size: Int,
    memberIds: Vector[String],
    sourceDatasets: Vector[SourceType],
    provenance: Provenance
) derives ReadWriter

object CandidateBlock {

  /** Sorts members and sources, sets the size, computes the block ID, and validates. */
  def build(
      entityKind: MatchEntityKind,
      blockingVersion: String,
      level: Int,
      blockKey: String,
      maxBlockSize: Int,
      memberIds: Vector[String],
      sourceDatasets: Vector[SourceType],
      provenance: Provenance
  ): Either[Vector[ValidationError], CandidateBlock] =
    (for {
      p <- Option(provenance).toRight(
        ValidationError.MissingProvenance("CandidateBlock", "provenance")
      )
      k <- Option(entityKind).toRight(ValidationError.MissingField("entityKind"))
      ms <- Option(memberIds)
        .filter(_.forall(_ != null))
        .toRight(ValidationError.MissingField("memberIds"))
      ss <- Option(sourceDatasets)
        .filter(_.forall(_ != null))
        .toRight(ValidationError.MissingField("sourceDatasets"))
      id <- CandidateBlockId.of(k, blockingVersion, level, blockKey, p.runId)
    } yield {
      val sorted = ms.sortWith((a, b) => Canonical.compareUtf8(a, b) < 0)
      CandidateBlock(
        SchemaVersions.CandidateBlock,
        id,
        k,
        blockingVersion,
        level,
        blockKey,
        maxBlockSize,
        sorted.size,
        sorted,
        ss.distinct.sortWith((a, b) => Canonical.compareUtf8(a.tag, b.tag) < 0),
        p
      )
    }) match {
      case Left(e) => Left(Vector(e))
      case Right(b) =>
        val errors = contract.validate(b)
        if (errors.isEmpty) Right(b) else Left(errors)
    }

  given contract: RecordContract[CandidateBlock] =
    new RecordContract[CandidateBlock]("CandidateBlock", SchemaVersions.CandidateBlock, true) {
      def schemaVersionOf(t: CandidateBlock): String = t.schemaVersion
      def provenanceOf(t: CandidateBlock): Provenance = t.provenance
      protected def fields(t: CandidateBlock, p: String): Vector[ValidationError] = {
        val members = Option(t.memberIds).getOrElse(Vector.empty)
        val sources = Option(t.sourceDatasets).getOrElse(Vector.empty)
        val local = all(
          CandidateRules.kindCheck(at(p, "entityKind"), t.entityKind),
          when(!StableId.isToken(t.blockingVersion))(
            ValidationError.InvalidValue(at(p, "blockingVersion"), "missing or not a token")
          ),
          when(t.level < 0)(
            ValidationError.InvalidValue(at(p, "level"), s"${t.level} is negative")
          ),
          BlockKey.checkKey(at(p, "blockKey"), t.blockKey),
          when(t.maxBlockSize < 2)(
            ValidationError.InvalidValue(at(p, "maxBlockSize"), "must be >= 2")
          ),
          when(t.memberIds == null || members.isEmpty)(
            ValidationError.MissingField(at(p, "memberIds"))
          ),
          when(t.size != members.size)(
            ValidationError.InvalidValue(at(p, "size"), s"expected ${members.size} (memberIds)")
          ),
          when(t.size > t.maxBlockSize && t.maxBlockSize >= 2)(
            ValidationError.InvalidValue(at(p, "size"), s"exceeds maxBlockSize ${t.maxBlockSize}")
          ),
          members.zipWithIndex.flatMap { case (m, i) =>
            CandidateRules.endpointCheck(at(p, s"memberIds[$i]"), t.entityKind, m)
          },
          Validated.strictlyAscending(at(p, "memberIds"), members),
          when(t.sourceDatasets == null || sources.isEmpty)(
            ValidationError.MissingField(at(p, "sourceDatasets"))
          ),
          sources.zipWithIndex.flatMap { case (s, i) =>
            when(s == null || !s.isDataset)(
              ValidationError.InvalidValue(at(p, s"sourceDatasets[$i]"), "must be a dataset source")
            )
          },
          Validated.strictlyAscending(
            at(p, "sourceDatasets"),
            sources.map(s => if (s == null) null else s.tag)
          ),
          members.flatMap(CandidateRules.namedSource).distinct.flatMap { s =>
            when(!sources.contains(s))(
              ValidationError
                .InvalidValue(at(p, "sourceDatasets"), s"lacks member source '${s.tag}'")
            )
          },
          CandidateRules.derivedCheck(at(p, "provenance.sourceDataset"), t.provenance),
          StableId.check(IdKind.CandidateBlock, t.blockId, at(p, "blockId"))
        )
        val idCheck =
          if (local.nonEmpty || t.provenance == null) Vector.empty
          else
            CandidateBlockId.of(
              t.entityKind,
              t.blockingVersion,
              t.level,
              t.blockKey,
              t.provenance.runId
            ) match {
              case Right(expected) =>
                when(expected != t.blockId)(
                  ValidationError.InvalidValue(at(p, "blockId"), s"expected $expected")
                )
              case Left(_) => Vector.empty
            }
        local ++ idCheck
      }
    }
}

/**
 * A candidate pair produced by blocking and deterministic feature construction (README Phase 4 job
 * 5). It is input to entity resolution and deliberately carries no score, threshold, or decision:
 * those belong to a [[MatchDecision]].
 *
 * @param candidateId
 *   [[MatchCandidateId.of]](entityKind, (sourceIdA, sourceIdB), blockingVersion, featureVersion,
 *   provenance.runId)
 * @param sourceIdA
 *   first endpoint of the canonical pair (`sourceIdA < sourceIdB` by UTF-8 bytes): author mention
 *   or source author profile IDs (researcher), or work IDs (work)
 * @param sourceDatasetA
 *   dataset of `sourceIdA` (must agree with the ID's namespace when the ID names a source)
 * @param blockKeys
 *   every (level, key) block in which the pair co-occurred, sorted ([[BlockKey.compare]]) without
 *   duplicates, non-empty
 * @param blockingVersion
 *   version of the blocking-key formulas
 * @param featureVersion
 *   version of the feature functions that computed `features`
 * @param features
 *   deterministic named features: token keys (sorted by the map), finite values
 * @param exactIdAgreements
 *   exact identifiers asserted for both endpoints with equal canonical values, of the candidate's
 *   entity kind, sorted ([[ExactId.compare]]) without duplicates
 * @param provenance
 *   `scholarchain-derived`; runId of the candidate run
 */
@allowUnknownKeys(false)
final case class MatchCandidate(
    schemaVersion: String,
    candidateId: String,
    entityKind: MatchEntityKind,
    sourceIdA: String,
    sourceIdB: String,
    sourceDatasetA: SourceType,
    sourceDatasetB: SourceType,
    blockKeys: Vector[BlockKey],
    blockingVersion: String,
    featureVersion: String,
    features: SortedMap[String, Double],
    exactIdAgreements: Vector[ExactId],
    provenance: Provenance
) derives ReadWriter

object MatchCandidate {

  /**
   * Builds a candidate: orders the pair canonically (each endpoint keeps its own dataset), sorts
   * and de-duplicates block keys and identifier agreements, computes the ID, and validates.
   */
  def build(
      entityKind: MatchEntityKind,
      x: String,
      sourceX: SourceType,
      y: String,
      sourceY: SourceType,
      blockKeys: Vector[BlockKey],
      blockingVersion: String,
      featureVersion: String,
      features: SortedMap[String, Double],
      exactIdAgreements: Vector[ExactId],
      provenance: Provenance
  ): Either[Vector[ValidationError], MatchCandidate] =
    (for {
      p <- Option(provenance).toRight(
        ValidationError.MissingProvenance("MatchCandidate", "provenance")
      )
      k <- Option(entityKind).toRight(ValidationError.MissingField("entityKind"))
      keys <- Option(blockKeys)
        .filter(_.forall(_ != null))
        .toRight(ValidationError.MissingField("blockKeys"))
      ids <- Option(exactIdAgreements)
        .filter(_.forall(e => e != null && e.scheme != null))
        .toRight(ValidationError.MissingField("exactIdAgreements"))
      pair <- CanonicalPair.of(x, y, "sourceIdA,sourceIdB")
      id <- MatchCandidateId.of(k, pair, blockingVersion, featureVersion, p.runId)
    } yield {
      val (sa, sb) = if (pair.first == x) (sourceX, sourceY) else (sourceY, sourceX)
      MatchCandidate(
        SchemaVersions.MatchCandidate,
        id,
        k,
        pair.first,
        pair.second,
        sa,
        sb,
        BlockKey.canonical(keys),
        blockingVersion,
        featureVersion,
        features,
        ExactId.canonical(ids),
        p
      )
    }) match {
      case Left(e) => Left(Vector(e))
      case Right(c) =>
        val errors = contract.validate(c)
        if (errors.isEmpty) Right(c) else Left(errors)
    }

  given contract: RecordContract[MatchCandidate] =
    new RecordContract[MatchCandidate]("MatchCandidate", SchemaVersions.MatchCandidate, true) {
      def schemaVersionOf(t: MatchCandidate): String = t.schemaVersion
      def provenanceOf(t: MatchCandidate): Provenance = t.provenance
      protected def fields(t: MatchCandidate, p: String): Vector[ValidationError] = {
        val keys = Option(t.blockKeys).getOrElse(Vector.empty)
        val local = all(
          CandidateRules.kindCheck(at(p, "entityKind"), t.entityKind),
          CandidateRules.endpointCheck(at(p, "sourceIdA"), t.entityKind, t.sourceIdA),
          CandidateRules.endpointCheck(at(p, "sourceIdB"), t.entityKind, t.sourceIdB),
          CanonicalPair.checkStored(at(p, "sourceIdA,sourceIdB"), t.sourceIdA, t.sourceIdB),
          CandidateRules.sourceCheck(at(p, "sourceDatasetA"), t.sourceIdA, t.sourceDatasetA),
          CandidateRules.sourceCheck(at(p, "sourceDatasetB"), t.sourceIdB, t.sourceDatasetB),
          when(t.blockKeys == null || keys.isEmpty)(
            ValidationError.MissingField(at(p, "blockKeys"))
          ),
          keys.zipWithIndex.flatMap { case (b, i) => BlockKey.check(at(p, s"blockKeys[$i]"), b) },
          when(
            keys.forall(_ != null) &&
              keys.sliding(2).exists(w => w.size == 2 && BlockKey.compare(w(0), w(1)) >= 0)
          )(
            ValidationError.InvalidValue(
              at(p, "blockKeys"),
              "must be sorted by (level, key) without duplicates"
            )
          ),
          when(!StableId.isToken(t.blockingVersion))(
            ValidationError.InvalidValue(at(p, "blockingVersion"), "missing or not a token")
          ),
          when(!StableId.isToken(t.featureVersion))(
            ValidationError.InvalidValue(at(p, "featureVersion"), "missing or not a token")
          ),
          when(t.features == null)(ValidationError.MissingField(at(p, "features"))),
          Option(t.features).toVector.flatten.flatMap { case (k, v) =>
            when(!StableId.isToken(k))(
              ValidationError.InvalidValue(at(p, "features"), s"key '$k' is not a token")
            ) ++ Validated.finite(at(p, s"features.$k"), v)
          },
          Option(t.entityKind).toVector.flatMap(k =>
            ExactId.checkSet(at(p, "exactIdAgreements"), t.exactIdAgreements, k)
          ),
          CandidateRules.derivedCheck(at(p, "provenance.sourceDataset"), t.provenance),
          StableId.check(IdKind.MatchCandidate, t.candidateId, at(p, "candidateId"))
        )
        val idCheck =
          if (local.nonEmpty || t.provenance == null) Vector.empty
          else
            CanonicalPair
              .of(t.sourceIdA, t.sourceIdB)
              .flatMap(
                MatchCandidateId
                  .of(t.entityKind, _, t.blockingVersion, t.featureVersion, t.provenance.runId)
              ) match {
              case Right(expected) =>
                when(expected != t.candidateId)(
                  ValidationError.InvalidValue(at(p, "candidateId"), s"expected $expected")
                )
              case Left(_) => Vector.empty
            }
        local ++ idCheck
      }
    }
}
