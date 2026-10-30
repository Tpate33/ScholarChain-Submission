package scholarchain.core

import java.time.Instant
import java.time.format.DateTimeParseException

import scala.collection.immutable.SortedMap

import upickle.default.ReadWriter
import upickle.implicits.allowUnknownKeys

import Validated.{all, at, nonEmpty, when}

/**
 * A resolved researcher identity.
 *
 * @param researcherId
 *   [[ResearcherId]]: `researcher:orcid:<iD>` (then `externalIds` must contain that ORCID) or
 *   `researcher:sc:<version>:<hex64>`
 * @param identityConfidence
 *   confidence in [0,1] that the merged mentions are one person
 * @param mentionCount
 *   number of author mentions resolved to this researcher
 */
@allowUnknownKeys(false)
final case class CanonicalResearcher(
    schemaVersion: String,
    researcherId: String,
    displayName: String,
    nameVariants: Vector[String],
    externalIds: Vector[ExternalId],
    institutionIds: Vector[String],
    identityConfidence: Double,
    mentionCount: Long,
    provenance: Provenance
) derives ReadWriter

object CanonicalResearcher {
  given contract: RecordContract[CanonicalResearcher] =
    new RecordContract[CanonicalResearcher](
      "CanonicalResearcher",
      SchemaVersions.CanonicalResearcher,
      true
    ) {
      def schemaVersionOf(t: CanonicalResearcher): String = t.schemaVersion
      def provenanceOf(t: CanonicalResearcher): Provenance = t.provenance
      protected def fields(t: CanonicalResearcher, p: String): Vector[ValidationError] = {
        val ext = Option(t.externalIds).getOrElse(Vector.empty)
        val orcidPrefix = "researcher:orcid:"
        all(
          StableId.check(IdKind.Researcher, t.researcherId, at(p, "researcherId")),
          nonEmpty(at(p, "displayName"), t.displayName),
          when(t.nameVariants == null)(ValidationError.MissingField(at(p, "nameVariants"))),
          ext.zipWithIndex.flatMap { case (e, i) =>
            ExternalId.check(at(p, s"externalIds[$i]"), e)
          },
          when(
            t.researcherId != null && t.researcherId.startsWith(orcidPrefix) &&
              !ext.contains(
                ExternalId(ExternalIdScheme.Orcid, t.researcherId.stripPrefix(orcidPrefix))
              )
          )(
            ValidationError.InvalidValue(
              at(p, "externalIds"),
              "an ORCID-based researcherId requires the same ORCID in externalIds"
            )
          ),
          Option(t.institutionIds).toVector.flatten.zipWithIndex.flatMap { case (id, i) =>
            StableId.check(IdKind.Institution, id, at(p, s"institutionIds[$i]"))
          },
          Validated.confidence(at(p, "identityConfidence"), t.identityConfidence),
          Validated.nonNegative(at(p, "mentionCount"), t.mentionCount)
        )
      }
    }
}

/**
 * Canonical publication-to-researcher authorship: one resolved author mention.
 *
 * @param matchDecisionId
 *   the ACCEPTED match decision that resolved the mention
 * @param matchConfidence
 *   confidence in [0,1] of that decision
 */
@allowUnknownKeys(false)
final case class AuthorshipRecord(
    schemaVersion: String,
    workId: String,
    mentionId: String,
    researcherId: String,
    position: Int,
    year: Option[Int],
    matchDecisionId: String,
    matchConfidence: Double,
    provenance: Provenance
) derives ReadWriter

object AuthorshipRecord {
  given contract: RecordContract[AuthorshipRecord] =
    new RecordContract[AuthorshipRecord](
      "AuthorshipRecord",
      SchemaVersions.AuthorshipRecord,
      true
    ) {
      def schemaVersionOf(t: AuthorshipRecord): String = t.schemaVersion
      def provenanceOf(t: AuthorshipRecord): Provenance = t.provenance
      protected def fields(t: AuthorshipRecord, p: String): Vector[ValidationError] =
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
          StableId.check(IdKind.MatchDecision, t.matchDecisionId, at(p, "matchDecisionId")),
          Validated.confidence(at(p, "matchConfidence"), t.matchConfidence)
        )
    }
}

/**
 * One entity-link decision about one (source, candidate target) pair. Policy, model, prompt and run
 * are recorded in `provenance`; `provenance.policyVersion` is required.
 *
 * @param decisionId
 *   [[MatchDecisionId.of]](entityKind, sourceId, targetId, provenance.policyVersion,
 *   provenance.runId)
 * @param features
 *   feature name to value (finite), ordered by name
 * @param rulesFired
 *   policy rules that fired, in evaluation order
 * @param confidence
 *   in [0,1]
 * @param decidedAt
 *   ISO-8601 instant (UTC `Z`). Not an input of any ID
 * @param conflictingTargetIds
 *   other targets in conflict; non-empty exactly when decision is CONFLICT
 */
@allowUnknownKeys(false)
final case class MatchDecision(
    schemaVersion: String,
    decisionId: String,
    entityKind: MatchEntityKind,
    sourceId: String,
    targetId: String,
    features: SortedMap[String, Double],
    rulesFired: Vector[String],
    decision: MatchOutcome,
    confidence: Double,
    reasonCodes: Vector[String],
    conflictingTargetIds: Vector[String],
    decidedAt: String,
    provenance: Provenance
) derives ReadWriter

object MatchDecision {
  given contract: RecordContract[MatchDecision] =
    new RecordContract[MatchDecision]("MatchDecision", SchemaVersions.MatchDecision, true) {
      def schemaVersionOf(t: MatchDecision): String = t.schemaVersion
      def provenanceOf(t: MatchDecision): Provenance = t.provenance
      protected def fields(t: MatchDecision, p: String): Vector[ValidationError] = {
        val conflicts = Option(t.conflictingTargetIds).getOrElse(Vector.empty)
        val policy = Option(t.provenance).flatMap(pr => Option(pr.policyVersion).flatten)
        all(
          when(t.entityKind == null)(ValidationError.MissingField(at(p, "entityKind"))),
          when(t.decision == null)(ValidationError.MissingField(at(p, "decision"))),
          Validated.fromEither(StableId.validateAny(t.sourceId, at(p, "sourceId"))),
          Validated.fromEither(StableId.validateAny(t.targetId, at(p, "targetId"))),
          when(t.sourceId != null && t.sourceId == t.targetId)(
            ValidationError.SelfPair(at(p, "sourceId,targetId"), t.sourceId)
          ),
          when(t.features == null)(ValidationError.MissingField(at(p, "features"))),
          Option(t.features).toVector.flatten.flatMap { case (k, v) =>
            nonEmpty(at(p, "features.<key>"), k) ++ Validated.finite(at(p, s"features.$k"), v)
          },
          when(t.rulesFired == null)(ValidationError.MissingField(at(p, "rulesFired"))),
          Validated.confidence(at(p, "confidence"), t.confidence),
          when(t.reasonCodes == null || t.reasonCodes.isEmpty)(
            ValidationError.MissingField(at(p, "reasonCodes"))
          ),
          conflicts.zipWithIndex.flatMap { case (c, i) =>
            Validated.fromEither(StableId.validateAny(c, at(p, s"conflictingTargetIds[$i]")))
          },
          when(t.decision == MatchOutcome.Conflict && conflicts.isEmpty)(
            ValidationError.InvalidValue(at(p, "conflictingTargetIds"), "required for CONFLICT")
          ),
          when(t.decision != null && t.decision != MatchOutcome.Conflict && conflicts.nonEmpty)(
            ValidationError.InvalidValue(at(p, "conflictingTargetIds"), "only allowed for CONFLICT")
          ),
          checkInstant(at(p, "decidedAt"), t.decidedAt),
          when(policy.isEmpty)(ValidationError.MissingField(at(p, "provenance.policyVersion"))),
          StableId.check(IdKind.MatchDecision, t.decisionId, at(p, "decisionId")),
          (t.entityKind, t.provenance, policy) match {
            case (k, pr, Some(pv)) if k != null && pr != null =>
              MatchDecisionId.of(k, t.sourceId, t.targetId, pv, pr.runId) match {
                case Right(expected) =>
                  when(expected != t.decisionId)(
                    ValidationError.InvalidValue(at(p, "decisionId"), s"expected $expected")
                  )
                case Left(_) => Vector.empty
              }
            case _ => Vector.empty
          }
        )
      }
    }

  private[core] def checkInstant(field: String, s: String): Vector[ValidationError] =
    if (s == null || s.isEmpty) Vector(ValidationError.MissingField(field))
    else
      try {
        Instant.parse(s)
        when(!s.endsWith("Z"))(ValidationError.InvalidValue(field, "instant must be in UTC (Z)"))
      } catch {
        case _: DateTimeParseException =>
          Vector(ValidationError.InvalidValue(field, s"'$s' is not an ISO-8601 instant"))
      }
}
