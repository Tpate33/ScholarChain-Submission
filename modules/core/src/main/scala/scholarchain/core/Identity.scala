package scholarchain.core

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
