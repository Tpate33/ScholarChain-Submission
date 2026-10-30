package scholarchain.core

import upickle.default.ReadWriter
import upickle.implicits.allowUnknownKeys

import Validated.{all, at, nonEmpty, when}

/**
 * One author position on one source work, before identity resolution. A matching name is never
 * evidence of identity; `sourceAuthorId` and `orcid` are assertions of the source, with provenance.
 *
 * @param mentionId
 *   [[MentionId.of]](workId, position)
 * @param position
 *   0-based author position on the work
 * @param sourceAuthorId
 *   author key asserted by the source (DBLP pid, OpenAlex A-ID), if any
 * @param orcid
 *   ORCID iD asserted by the source (bare form), if any
 * @param affiliationIds
 *   institution IDs asserted by the source
 */
@allowUnknownKeys(false)
final case class AuthorMention(
    schemaVersion: String,
    mentionId: String,
    workId: String,
    position: Int,
    rawName: String,
    normalizedName: String,
    sourceAuthorId: Option[ExternalId],
    orcid: Option[String],
    affiliationIds: Vector[String],
    rawAffiliations: Vector[String],
    provenance: Provenance
) derives ReadWriter

object AuthorMention {
  given contract: RecordContract[AuthorMention] =
    new RecordContract[AuthorMention]("AuthorMention", SchemaVersions.AuthorMention, true) {
      def schemaVersionOf(t: AuthorMention): String = t.schemaVersion
      def provenanceOf(t: AuthorMention): Provenance = t.provenance
      protected def fields(t: AuthorMention, p: String): Vector[ValidationError] =
        all(
          StableId.check(IdKind.Work, t.workId, at(p, "workId")),
          StableId.check(IdKind.Mention, t.mentionId, at(p, "mentionId")),
          when(t.position < 0)(ValidationError.InvalidValue(at(p, "position"), "negative")),
          MentionId.of(t.workId, t.position) match {
            case Right(expected) if StableId.prefixKind(t.mentionId).contains(IdKind.Mention) =>
              when(expected != t.mentionId)(
                ValidationError.InvalidValue(at(p, "mentionId"), s"expected $expected")
              )
            case _ => Vector.empty
          },
          nonEmpty(at(p, "rawName"), t.rawName),
          when(t.normalizedName == null)(ValidationError.MissingField(at(p, "normalizedName"))),
          Option(t.sourceAuthorId)
            .getOrElse(None)
            .toVector
            .flatMap(
              ExternalId.check(at(p, "sourceAuthorId"), _)
            ),
          Option(t.orcid).getOrElse(None).toVector.flatMap { o =>
            when(!Identifiers.isValidOrcid(o))(
              ValidationError
                .MalformedId(at(p, "orcid"), "orcid", o, "not a checksum-valid ORCID iD")
            )
          },
          Option(t.affiliationIds).toVector.flatten.zipWithIndex.flatMap { case (id, i) =>
            StableId.check(IdKind.Institution, id, at(p, s"affiliationIds[$i]"))
          },
          when(t.rawAffiliations == null)(ValidationError.MissingField(at(p, "rawAffiliations")))
        )
    }
}

/**
 * A source work. `workId` is namespace plus authoritative source ID ([[WorkId]]), and the namespace
 * is the one of `source` ([[WorkRecord.NamespaceOf]]); `source` equals `provenance.sourceDataset`.
 * Authors are in source order with positions 0..n-1.
 */
@allowUnknownKeys(false)
final case class WorkRecord(
    schemaVersion: String,
    workId: String,
    source: SourceType,
    sourceRecordId: String,
    title: String,
    normalizedTitle: String,
    year: Option[Int],
    venueId: Option[String],
    doi: Option[String],
    arxivId: Option[String],
    authors: Vector[AuthorMention],
    abstractTextRef: Option[String],
    provenance: Provenance
) derives ReadWriter

object WorkRecord {

  /**
   * Work ID namespace required for each dataset source: the source's authoritative work ID. DBLP
   * record key, OpenAlex W-ID, ORCID iD plus put-code, and the DOI for Crossref.
   */
  val NamespaceOf: Map[SourceType, String] = Map(
    SourceType.Dblp -> "dblp",
    SourceType.OpenAlex -> "openalex",
    SourceType.Orcid -> "orcid-work",
    SourceType.Crossref -> "doi"
  )

  given contract: RecordContract[WorkRecord] =
    new RecordContract[WorkRecord]("WorkRecord", SchemaVersions.WorkRecord, true) {
      def schemaVersionOf(t: WorkRecord): String = t.schemaVersion
      def provenanceOf(t: WorkRecord): Provenance = t.provenance
      protected def fields(t: WorkRecord, p: String): Vector[ValidationError] = {
        val authors = Option(t.authors).getOrElse(Vector.empty)
        all(
          StableId.check(IdKind.Work, t.workId, at(p, "workId")),
          when(t.source == null || !t.source.isDataset)(
            ValidationError.InvalidValue(at(p, "source"), "must be a dataset source")
          ),
          when(t.source != null && t.provenance != null && t.provenance.sourceDataset != t.source)(
            ValidationError.InvalidValue(at(p, "source"), "differs from provenance.sourceDataset")
          ),
          (Option(t.source).flatMap(WorkRecord.NamespaceOf.get), Option(t.workId)) match {
            case (Some(ns), Some(id)) if !id.startsWith(s"work:$ns:") =>
              Vector(
                ValidationError.InvalidValue(
                  at(p, "workId"),
                  s"namespace must be '$ns' for source '${t.source.tag}'"
                )
              )
            case _ => Vector.empty
          },
          nonEmpty(at(p, "sourceRecordId"), t.sourceRecordId),
          nonEmpty(at(p, "title"), t.title),
          when(t.normalizedTitle == null)(ValidationError.MissingField(at(p, "normalizedTitle"))),
          Option(t.year).getOrElse(None).toVector.flatMap(Validated.year(at(p, "year"), _)),
          Option(t.venueId)
            .getOrElse(None)
            .toVector
            .flatMap(
              StableId.check(IdKind.Venue, _, at(p, "venueId"))
            ),
          Option(t.doi).getOrElse(None).toVector.flatMap { d =>
            when(!Identifiers.isCanonicalDoi(d))(
              ValidationError
                .MalformedId(at(p, "doi"), "doi", d, "not a canonical lowercase bare DOI")
            )
          },
          Option(t.arxivId).getOrElse(None).toVector.flatMap { a =>
            when(!Identifiers.isValidArxiv(a))(
              ValidationError.MalformedId(at(p, "arxivId"), "arxiv", a, "not an arXiv identifier")
            )
          },
          when(t.authors == null)(ValidationError.MissingField(at(p, "authors"))),
          authors.zipWithIndex.flatMap { case (m, i) =>
            val f = at(p, s"authors[$i]")
            AuthorMention.contract.validateAt(m, f) ++ (
              if (m == null) Vector.empty
              else
                all(
                  when(m.workId != t.workId)(
                    ValidationError.InvalidValue(at(f, "workId"), "differs from the work's workId")
                  ),
                  when(m.position != i)(
                    ValidationError.InvalidValue(at(f, "position"), s"expected $i")
                  )
                )
            )
          },
          Option(t.abstractTextRef)
            .getOrElse(None)
            .toVector
            .flatMap(nonEmpty(at(p, "abstractTextRef"), _))
        )
      }
    }
}

/** A publication venue (journal, conference series, or source). */
@allowUnknownKeys(false)
final case class Venue(
    schemaVersion: String,
    venueId: String,
    name: String,
    venueType: Option[String],
    issns: Vector[String],
    externalIds: Vector[ExternalId],
    provenance: Provenance
) derives ReadWriter

object Venue {
  given contract: RecordContract[Venue] =
    new RecordContract[Venue]("Venue", SchemaVersions.Venue, true) {
      def schemaVersionOf(t: Venue): String = t.schemaVersion
      def provenanceOf(t: Venue): Provenance = t.provenance
      protected def fields(t: Venue, p: String): Vector[ValidationError] =
        all(
          StableId.check(IdKind.Venue, t.venueId, at(p, "venueId")),
          nonEmpty(at(p, "name"), t.name),
          Option(t.issns).toVector.flatten.zipWithIndex.flatMap { case (s, i) =>
            when(!Identifiers.isValidIssn(s))(
              ValidationError
                .MalformedId(at(p, s"issns[$i]"), "issn", String.valueOf(s), "invalid ISSN")
            )
          },
          Option(t.externalIds).toVector.flatten.zipWithIndex.flatMap { case (e, i) =>
            ExternalId.check(at(p, s"externalIds[$i]"), e)
          }
        )
    }
}

/** A research institution. `rorId` is the bare 9-character ROR suffix. */
@allowUnknownKeys(false)
final case class Institution(
    schemaVersion: String,
    institutionId: String,
    name: String,
    rorId: Option[String],
    countryCode: Option[String],
    externalIds: Vector[ExternalId],
    provenance: Provenance
) derives ReadWriter

object Institution {
  given contract: RecordContract[Institution] =
    new RecordContract[Institution]("Institution", SchemaVersions.Institution, true) {
      def schemaVersionOf(t: Institution): String = t.schemaVersion
      def provenanceOf(t: Institution): Provenance = t.provenance
      protected def fields(t: Institution, p: String): Vector[ValidationError] =
        all(
          StableId.check(IdKind.Institution, t.institutionId, at(p, "institutionId")),
          nonEmpty(at(p, "name"), t.name),
          Option(t.rorId).getOrElse(None).toVector.flatMap { r =>
            when(!Identifiers.isValidRor(r))(
              ValidationError.MalformedId(at(p, "rorId"), "ror", r, "not a 9-character ROR suffix")
            )
          },
          Option(t.countryCode).getOrElse(None).toVector.flatMap { c =>
            when(!c.matches("[A-Z]{2}"))(
              ValidationError.InvalidValue(at(p, "countryCode"), "expected ISO 3166-1 alpha-2")
            )
          },
          Option(t.externalIds).toVector.flatten.zipWithIndex.flatMap { case (e, i) =>
            ExternalId.check(at(p, s"externalIds[$i]"), e)
          }
        )
    }
}

/** A research topic (OpenAlex topic taxonomy in the selected datasets). */
@allowUnknownKeys(false)
final case class Topic(
    schemaVersion: String,
    topicId: String,
    name: String,
    parentTopicId: Option[String],
    provenance: Provenance
) derives ReadWriter

object Topic {
  given contract: RecordContract[Topic] =
    new RecordContract[Topic]("Topic", SchemaVersions.Topic, true) {
      def schemaVersionOf(t: Topic): String = t.schemaVersion
      def provenanceOf(t: Topic): Provenance = t.provenance
      protected def fields(t: Topic, p: String): Vector[ValidationError] =
        all(
          StableId.check(IdKind.Topic, t.topicId, at(p, "topicId")),
          nonEmpty(at(p, "name"), t.name),
          Option(t.parentTopicId)
            .getOrElse(None)
            .toVector
            .flatMap(
              StableId.check(IdKind.Topic, _, at(p, "parentTopicId"))
            )
        )
    }
}
