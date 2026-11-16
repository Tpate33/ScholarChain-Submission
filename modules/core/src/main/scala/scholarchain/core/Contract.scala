package scholarchain.core

import scala.util.control.NonFatal

import upickle.default.ReadWriter

/**
 * Schema version constants. Every record carries `schemaVersion`; a decoder accepts only the
 * versions listed in its contract. Changing a frozen wire example requires a new version here.
 * Value objects nested in records (Provenance, TimeWindow, ExternalId, ExactId, BlockKey,
 * AssertionSource, SnapshotRef, QueryStep) are versioned by their enclosing record.
 *
 * Version history (a superseded version is rejected on decode unless stated otherwise):
 *   - `work-record.v2` (Phase 4): adds recordType, venueName, volume, issue, pages, eeLinks, urls,
 *     topicIds, additionalIds, assertionSource. v1 is rejected: no v1 work record was ever accepted
 *     into a curated output.
 *   - `work-record.v3` (Phase 4, work order 1b): adds sourceLocalWorkIds (ORCID `source-work-id`
 *     qualified by its issuing source). v2 is rejected: it was never committed or accepted into a
 *     curated output, and its frozen examples changed bytes.
 *   - `coauthor-pair-candidate.v2` (Phase 4): adds positionA and positionB. v1 is rejected for the
 *     same reason.
 *   - `match-decision.v2` (Phase 5): a decision about one MatchCandidate pair, with pairwise
 *     semantics. Adds candidateId, matchPath, score (separate from confidence), blockingVersion,
 *     featureVersion, conflicts (pairwise identifier conflicts) and adjudication (LLM adjudication
 *     reference); removes conflictingTargetIds ("other targets in conflict" is not a property of
 *     one pair); requires canonical pair order and researcher or work endpoints. v1 is rejected: no
 *     v1 decision was ever accepted into a curated output. The decision ID formula
 *     ([[MatchDecisionId]], hash tag `scholarchain.match-decision.v1`) is unchanged.
 *   - Phase 6 (DE-P6-01), new records: `researcher-assignment.v1` (C1), `work-assignment.v1` (C2),
 *     `authorship-record.v2` (C3) and `part-manifest.v1` (C5). `authorship-record.v2` is its own
 *     record type (`AuthorshipRecordV2`); `authorship-record.v1` is NOT retired: its contract,
 *     codec and frozen example are unchanged and it still decodes, and [[AuthorshipRecords.decode]]
 *     reads either version. Grouping-based writers write v2 only.
 */
object SchemaVersions {
  val WorkRecord = "scholarchain.work-record.v3"
  val AuthorMention = "scholarchain.author-mention.v1"
  val CanonicalResearcher = "scholarchain.canonical-researcher.v1"
  val AuthorshipRecord = "scholarchain.authorship-record.v1"
  val PublicationTeam = "scholarchain.publication-team.v1"
  val CoauthorPairCandidate = "scholarchain.coauthor-pair-candidate.v2"
  val CoauthorEdge = "scholarchain.coauthor-edge.v1"
  val ResearcherMetrics = "scholarchain.researcher-metrics.v1"
  val Venue = "scholarchain.venue.v1"
  val Institution = "scholarchain.institution.v1"
  val Topic = "scholarchain.topic.v1"
  val MatchDecision = "scholarchain.match-decision.v2"
  val GraphNode = "scholarchain.graph-node.v1"
  val GraphEdge = "scholarchain.graph-edge.v1"
  val EvidenceItem = "scholarchain.evidence-item.v1"
  val EvidenceBundle = "scholarchain.evidence-bundle.v1"
  val QueryPlan = "scholarchain.query-plan.v1"
  val AnswerClaim = "scholarchain.answer-claim.v1"
  val AnswerResponse = "scholarchain.answer-response.v1"
  val RunManifest = "scholarchain.run-manifest.v1"
  val MatchCandidate = "scholarchain.match-candidate.v1"
  val CandidateBlock = "scholarchain.candidate-block.v1"
  val IdentityLink = "scholarchain.identity-link.v1"
  val SourceAuthorProfile = "scholarchain.source-author-profile.v1"
  val ResearcherAssignment = "scholarchain.researcher-assignment.v1"
  val WorkAssignment = "scholarchain.work-assignment.v1"
  val AuthorshipRecordV2 = "scholarchain.authorship-record.v2"
  val PartManifest = "scholarchain.part-manifest.v1"

  /** Superseded versions that no decoder accepts (kept to make the rejection explicit). */
  val retired: Map[String, Vector[String]] = Map(
    "WorkRecord" -> Vector("scholarchain.work-record.v1", "scholarchain.work-record.v2"),
    "CoauthorPairCandidate" -> Vector("scholarchain.coauthor-pair-candidate.v1"),
    "MatchDecision" -> Vector("scholarchain.match-decision.v1")
  )

  /** Every current version, keyed by record type name. */
  val all: Map[String, String] = Map(
    "WorkRecord" -> WorkRecord,
    "AuthorMention" -> AuthorMention,
    "CanonicalResearcher" -> CanonicalResearcher,
    "AuthorshipRecord" -> AuthorshipRecord,
    "PublicationTeam" -> PublicationTeam,
    "CoauthorPairCandidate" -> CoauthorPairCandidate,
    "CoauthorEdge" -> CoauthorEdge,
    "ResearcherMetrics" -> ResearcherMetrics,
    "Venue" -> Venue,
    "Institution" -> Institution,
    "Topic" -> Topic,
    "MatchDecision" -> MatchDecision,
    "GraphNode" -> GraphNode,
    "GraphEdge" -> GraphEdge,
    "EvidenceItem" -> EvidenceItem,
    "EvidenceBundle" -> EvidenceBundle,
    "QueryPlan" -> QueryPlan,
    "AnswerClaim" -> AnswerClaim,
    "AnswerResponse" -> AnswerResponse,
    "RunManifest" -> RunManifest,
    "MatchCandidate" -> MatchCandidate,
    "CandidateBlock" -> CandidateBlock,
    "IdentityLink" -> IdentityLink,
    "SourceAuthorProfile" -> SourceAuthorProfile,
    "ResearcherAssignment" -> ResearcherAssignment,
    "WorkAssignment" -> WorkAssignment,
    "AuthorshipRecordV2" -> AuthorshipRecordV2,
    "PartManifest" -> PartManifest
  )
}

/**
 * Wire and validation contract of one record type: its JSON codec, accepted schema versions, and
 * validation rules. Validation returns typed errors and never throws.
 */
abstract class RecordContract[T](
    val recordType: String,
    val currentVersion: String,
    val requiresProvenance: Boolean
)(using val rw: ReadWriter[T]) {

  /** Versions this build decodes. Currently exactly the current version. */
  def supportedVersions: Vector[String] = Vector(currentVersion)

  /**
   * Top-level fields that have a Scala default value (for source compatibility of constructor
   * calls). The wire format still requires them: [[Json.decode]] rejects a JSON object that lacks
   * one, and the record type is annotated to always serialize them.
   */
  def fieldsWithDefaults: Vector[String] = Vector.empty

  def schemaVersionOf(t: T): String
  def provenanceOf(t: T): Provenance

  /** Record-specific checks; `at` is the path prefix of this record ("" at top level). */
  protected def fields(t: T, at: String): Vector[ValidationError]

  final def checkVersion(found: String): Vector[ValidationError] =
    if (found == null || found.isEmpty) Vector(ValidationError.MissingSchemaVersion(recordType))
    else if (!supportedVersions.contains(found))
      Vector(ValidationError.UnsupportedSchemaVersion(recordType, found, supportedVersions))
    else Vector.empty

  final def validateAt(t: T, at: String): Vector[ValidationError] =
    if (t == null) Vector(ValidationError.MissingField(if (at.isEmpty) recordType else at))
    else {
      val prov =
        if (requiresProvenance)
          Provenance.check(recordType, Validated.at(at, "provenance"), provenanceOf(t))
        else Vector.empty
      checkVersion(schemaVersionOf(t)) ++ prov ++ fields(t, at)
    }

  final def validate(t: T): Vector[ValidationError] = validateAt(t, "")
}

/** JSON entry points. Every decode checks schema version, provenance presence, and validation. */
object Json {

  /** Compact JSON with the frozen field order. Does not validate. */
  def write[T](t: T)(using c: RecordContract[T]): String = {
    given ReadWriter[T] = c.rw
    upickle.default.write(t)
  }

  /** Validates, then writes. */
  def encode[T](t: T)(using c: RecordContract[T]): Either[Vector[ValidationError], String] = {
    val errors = c.validate(t)
    if (errors.isEmpty) Right(write(t)) else Left(errors)
  }

  /**
   * Parses and validates. Order: JSON syntax, top-level object, `schemaVersion` present and
   * supported, `provenance` present and non-null, every key with a Scala default present (checked
   * only for a supported version, whose key set it is), structural decode, then field validation.
   */
  def decode[T](json: String)(using c: RecordContract[T]): Either[Vector[ValidationError], T] = {
    val parsed: Either[Vector[ValidationError], ujson.Value] =
      try Right(ujson.read(json))
      catch {
        case NonFatal(e) =>
          Left(Vector(ValidationError.MalformedJson(String.valueOf(e.getMessage))))
      }
    parsed.flatMap {
      case obj: ujson.Obj =>
        val version = obj.value.get("schemaVersion") match {
          case Some(ujson.Str(s)) => c.checkVersion(s)
          case _ => Vector(ValidationError.MissingSchemaVersion(c.recordType))
        }
        val provenance =
          if (!c.requiresProvenance) Vector.empty
          else
            obj.value.get("provenance") match {
              case Some(_: ujson.Obj) => Vector.empty
              case _ => Vector(ValidationError.MissingProvenance(c.recordType, "provenance"))
            }
        val missing = c.fieldsWithDefaults.filterNot(obj.value.contains)
        val keys =
          if (missing.isEmpty || version.nonEmpty) Vector.empty
          else
            Vector(
              ValidationError
                .Undecodable(c.recordType, s"missing key(s): ${missing.mkString(", ")}")
            )
        val pre = version ++ provenance ++ keys
        if (pre.nonEmpty) Left(pre)
        else {
          val decoded =
            try {
              given ReadWriter[T] = c.rw
              Right(upickle.default.read[T](obj))
            } catch {
              case NonFatal(e) =>
                Left(
                  Vector(ValidationError.Undecodable(c.recordType, String.valueOf(e.getMessage)))
                )
            }
          decoded.flatMap { t =>
            val errors = c.validate(t)
            if (errors.isEmpty) Right(t) else Left(errors)
          }
        }
      case _ => Left(Vector(ValidationError.MalformedJson("top-level value is not a JSON object")))
    }
  }
}
