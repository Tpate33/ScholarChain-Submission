package scholarchain.core

import scala.util.control.NonFatal

import upickle.default.ReadWriter
import upickle.implicits.allowUnknownKeys

import Validated.{all, at, when}

/**
 * Canonical publication-to-researcher authorship after grouping (schema `authorship-record.v2`):
 * one identified author mention on the representative of its canonical work.
 *
 * Differences from v1 ([[AuthorshipRecord]], still decoded unchanged): the single required
 * `matchDecisionId` becomes `supportingDecisionIds` (empty for a singleton researcher, which needs
 * no pairwise match), `matchConfidence` becomes optional, and the record names its assignment
 * status, both policy versions and the decision run.
 *
 * @param workId
 *   the mention's work, which is the representative of its canonical work, so `workId` is the
 *   canonical work ID. Mentions on non-representative copies get no authorship record (one author
 *   list per canonical work; see [[AssignmentValidation.checkAuthorships]])
 * @param mentionId
 *   [[MentionId.of]](workId, position)
 * @param researcherId
 *   the researcher assigned to the mention ([[ResearcherAssignment.researcherId]])
 * @param assignmentStatus
 *   `resolved` or `singleton`; an `unresolved` mention never has an authorship record
 * @param supportingDecisionIds
 *   the mention's supporting ACCEPTED decisions (equal to its assignment's list), sorted by UTF-8
 *   bytes without duplicates: non-empty for `resolved`, empty for `singleton`
 * @param matchConfidence
 *   the lowest `confidence` among the supporting decisions (an uncalibrated, policy-defined value):
 *   required for `resolved`, absent for `singleton`
 * @param provenance
 *   `scholarchain-derived`; `sourceRecordId` = `mentionId`; `runId` = the run that wrote the record
 *   (not the decision run); `policyVersion` = `groupingPolicyVersion`; no model or prompt
 */
@allowUnknownKeys(false)
final case class AuthorshipRecordV2(
    schemaVersion: String,
    workId: String,
    mentionId: String,
    researcherId: String,
    position: Int,
    year: Option[Int],
    assignmentStatus: AssignmentStatus,
    supportingDecisionIds: Vector[String],
    matchConfidence: Option[Double],
    groupingPolicyVersion: String,
    matchPolicyVersion: String,
    decisionRunId: String,
    provenance: Provenance
) derives ReadWriter

object AuthorshipRecordV2 {
  given contract: RecordContract[AuthorshipRecordV2] =
    new RecordContract[AuthorshipRecordV2](
      "AuthorshipRecordV2",
      SchemaVersions.AuthorshipRecordV2,
      true
    ) {
      def schemaVersionOf(t: AuthorshipRecordV2): String = t.schemaVersion
      def provenanceOf(t: AuthorshipRecordV2): Provenance = t.provenance
      protected def fields(t: AuthorshipRecordV2, p: String): Vector[ValidationError] = {
        val supporting = Option(t.supportingDecisionIds).getOrElse(Vector.empty)
        val confidence = Option(t.matchConfidence).getOrElse(None)
        all(
          StableId.check(IdKind.Work, t.workId, at(p, "workId")),
          StableId.check(IdKind.Mention, t.mentionId, at(p, "mentionId")),
          StableId.check(IdKind.Researcher, t.researcherId, at(p, "researcherId")),
          when(t.position < 0)(ValidationError.InvalidValue(at(p, "position"), "negative")),
          (MentionId.of(t.workId, t.position), StableId.prefixKind(t.mentionId)) match {
            case (Right(expected), Some(IdKind.Mention)) =>
              when(expected != t.mentionId)(
                ValidationError.InvalidValue(at(p, "mentionId"), s"expected $expected")
              )
            case _ => Vector.empty
          },
          Option(t.year).getOrElse(None).toVector.flatMap(Validated.year(at(p, "year"), _)),
          AssignmentRules.decisionList(at(p, "supportingDecisionIds"), t.supportingDecisionIds),
          confidence.toVector.flatMap(Validated.confidence(at(p, "matchConfidence"), _)),
          t.assignmentStatus match {
            case null => Vector(ValidationError.MissingField(at(p, "assignmentStatus")))
            case AssignmentStatus.Resolved =>
              all(
                when(supporting.isEmpty)(
                  ValidationError.InvalidValue(
                    at(p, "supportingDecisionIds"),
                    "resolved requires at least one supporting ACCEPTED decision"
                  )
                ),
                when(confidence.isEmpty)(
                  ValidationError.MissingField(at(p, "matchConfidence"))
                )
              )
            case AssignmentStatus.Singleton =>
              all(
                when(supporting.nonEmpty)(
                  ValidationError.InvalidValue(
                    at(p, "supportingDecisionIds"),
                    "a singleton has no supporting decision"
                  )
                ),
                when(confidence.isDefined)(
                  ValidationError.InvalidValue(
                    at(p, "matchConfidence"),
                    "a singleton has no supporting decision, so no match confidence"
                  )
                )
              )
            case AssignmentStatus.Unresolved =>
              Vector(
                ValidationError.InvalidValue(
                  at(p, "assignmentStatus"),
                  "an unresolved mention has no researcher and no authorship record"
                )
              )
          },
          AssignmentRules.runFields(
            p,
            t.mentionId,
            t.groupingPolicyVersion,
            t.matchPolicyVersion,
            t.decisionRunId,
            None,
            t.provenance
          )
        )
      }
    }
}

/**
 * Reads authorship records of either version. `authorship-record.v1` ([[AuthorshipRecord]]) keeps
 * its own contract, codec and frozen wire example unchanged; `authorship-record.v2`
 * ([[AuthorshipRecordV2]]) is the version every grouping-based writer produces. Each contract still
 * accepts exactly its own version; this reader dispatches on `schemaVersion`.
 */
object AuthorshipRecords {

  /** Every authorship schema version this build reads, oldest first. */
  val readable: Vector[String] =
    Vector(SchemaVersions.AuthorshipRecord, SchemaVersions.AuthorshipRecordV2)

  /** Decodes a v1 record as `Left` and a v2 record as `Right`, with full validation. */
  def decode(
      json: String
  ): Either[Vector[ValidationError], Either[AuthorshipRecord, AuthorshipRecordV2]] = {
    val version: Either[Vector[ValidationError], String] =
      try
        ujson.read(json) match {
          case o: ujson.Obj =>
            o.value.get("schemaVersion") match {
              case Some(ujson.Str(s)) => Right(s)
              case _ => Left(Vector(ValidationError.MissingSchemaVersion("AuthorshipRecord")))
            }
          case _ =>
            Left(Vector(ValidationError.MalformedJson("top-level value is not a JSON object")))
        }
      catch {
        case NonFatal(e) =>
          Left(Vector(ValidationError.MalformedJson(String.valueOf(e.getMessage))))
      }
    version.flatMap {
      case SchemaVersions.AuthorshipRecord => Json.decode[AuthorshipRecord](json).map(Left(_))
      case SchemaVersions.AuthorshipRecordV2 => Json.decode[AuthorshipRecordV2](json).map(Right(_))
      case other =>
        Left(Vector(ValidationError.UnsupportedSchemaVersion("AuthorshipRecord", other, readable)))
    }
  }
}
