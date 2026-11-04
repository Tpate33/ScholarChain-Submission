package scholarchain.hadoop.normalize

import scholarchain.core.{
  AuthorMention,
  ExactId,
  ExactIdScheme,
  Institution,
  Provenance,
  RecordContract,
  SourceAuthorProfile,
  SourceType,
  StableId,
  Topic,
  Validated,
  Venue,
  WorkRecord
}
import scholarchain.hadoop.{HadoopPipeline, Problem, Rank, RejectReason, RunContext}

/** Records produced from one source record, with flags for anything dropped from it. */
final case class Normalized(
    works: Vector[WorkRecord],
    venues: Vector[Venue],
    institutions: Vector[(Institution, Int)],
    topics: Vector[Topic],
    flags: Vector[Problem],
    profiles: Vector[SourceAuthorProfile] = Vector.empty
)

object Normalized {
  val empty: Normalized =
    Normalized(Vector.empty, Vector.empty, Vector.empty, Vector.empty, Vector.empty)
}

/** Result of normalizing one source record. Pure: no Hadoop types. */
enum Outcome {

  /** Kept. `flags` (inside `out`) are written as flagged records. */
  case Accepted(sourceRecordId: String, out: Normalized)

  /** Not kept; every problem is reported in one reject record. */
  case Rejected(sourceRecordId: Option[String], problems: Vector[Problem])

  /** Valid, but outside the configured selection (counted, not written). */
  case Filtered(sourceRecordId: Option[String], why: String)

  /** Never a work by definition (DBLP `www`, `person`, `data`): counted, not written. */
  case Skipped(why: String)
}

/** Shared helpers of the source normalizers. */
object Build {

  def provenance(run: RunContext, sourceRecordId: String, schema: String): Provenance =
    Provenance(
      sourceDataset = run.source,
      sourceRecordId = sourceRecordId,
      snapshot = run.snapshot,
      runId = run.runId,
      sourceSchemaVersion = schema,
      policyVersion = Some(HadoopPipeline.TransformVersion),
      modelVersion = None,
      promptVersion = None
    )

  def schemaOf(source: SourceType): String = source match {
    case SourceType.Dblp => HadoopPipeline.DblpSchema
    case SourceType.OpenAlex => HadoopPipeline.OpenAlexSchema
    case SourceType.Crossref => HadoopPipeline.CrossrefSchema
    case SourceType.Orcid => HadoopPipeline.OrcidSchema
    case other => throw new IllegalArgumentException(s"${other.tag} is not a dataset source")
  }

  /** Validates with the core contract; failures become one schema-failure problem. */
  def validated[T](t: T)(using c: RecordContract[T]): Either[Problem, T] = {
    val errors = c.validate(t)
    if (errors.isEmpty) Right(t)
    else
      Left(Problem(RejectReason.SchemaFailure, errors.map(e => s"${c.recordType}: ${e.message}")))
  }

  /** Keeps valid optional reference records and turns invalid ones into flags. */
  def keepValid[T](items: Vector[T])(using c: RecordContract[T]): (Vector[T], Vector[Problem]) = {
    val checked = items.map(validated(_))
    (checked.collect { case Right(t) => t }, checked.collect { case Left(p) => p })
  }

  /**
   * Optional source text (venue name, volume, issue, pages): kept when it is non-blank text without
   * control characters; otherwise dropped and flagged (the record is kept).
   */
  def optText(label: String, raw: Option[String]): (Option[String], Vector[Problem]) =
    raw match {
      case None => (None, Vector.empty)
      case Some(v) if Validated.text(label, v).isEmpty => (Some(v), Vector.empty)
      case Some(v) =>
        (
          None,
          Vector(
            Problem.of(
              RejectReason.MalformedField,
              s"$label '$v': blank or contains control characters; dropped"
            )
          )
        )
    }

  /** Optional token (record type): kept when it is a token; otherwise dropped and flagged. */
  def optToken(label: String, raw: Option[String]): (Option[String], Vector[Problem]) =
    raw match {
      case None => (None, Vector.empty)
      case Some(v) if StableId.isToken(v) => (Some(v), Vector.empty)
      case Some(v) =>
        (
          None,
          Vector(
            Problem.of(RejectReason.MalformedField, s"$label '$v': not a token; dropped")
          )
        )
    }

  /**
   * Links or URLs as given by the source (review task g): values with whitespace or control
   * characters are dropped and flagged, duplicates are removed (first occurrence kept), and the
   * source order is kept. One bad link never rejects the record.
   */
  def links(label: String, raw: Vector[String]): (Vector[String], Vector[Problem]) = {
    val (good, bad) = raw.zipWithIndex.partition { case (v, i) =>
      Validated.link(s"$label[$i]", v).isEmpty
    }
    (
      good.map(_._1).distinct,
      bad.map { case (v, i) =>
        Problem.of(
          RejectReason.MalformedField,
          s"$label[$i] '$v': blank or contains whitespace or control characters; dropped"
        )
      }
    )
  }

  /**
   * Exact work identifiers of one scheme from raw values: canonical, distinct; malformed values are
   * dropped and flagged.
   */
  def exactIds(
      label: String,
      scheme: ExactIdScheme,
      raw: Vector[String]
  ): (Vector[ExactId], Vector[Problem]) = {
    val parsed = raw.map(r => r -> ExactId.of(scheme, r))
    (
      parsed.collect { case (_, Right(e)) => e }.distinct,
      parsed.collect { case (r, Left(e)) =>
        Problem.of(RejectReason.MalformedIdentifier, s"$label '$r': ${e.message}; dropped")
      }
    )
  }

  def mention(
      workId: String,
      position: Int,
      rawName: String,
      sourceAuthorId: Option[scholarchain.core.ExternalId],
      orcid: Option[String],
      affiliationIds: Vector[String],
      rawAffiliations: Vector[String],
      prov: Provenance
  ): Either[Problem, AuthorMention] =
    scholarchain.core.MentionId.of(workId, position) match {
      case Left(e) => Left(Problem.of(RejectReason.SchemaFailure, e.message))
      case Right(mid) =>
        Right(
          AuthorMention(
            schemaVersion = scholarchain.core.SchemaVersions.AuthorMention,
            mentionId = mid,
            workId = workId,
            position = position,
            rawName = rawName,
            normalizedName = scholarchain.hadoop.TextNormalization.name(rawName),
            sourceAuthorId = sourceAuthorId,
            orcid = orcid,
            affiliationIds = affiliationIds,
            rawAffiliations = rawAffiliations,
            provenance = prov
          )
        )
    }

  /** Primary institution rank for entity-file records, reference rank otherwise. */
  def primary[T](t: T): (T, Int) = (t, Rank.Primary)
  def reference[T](t: T): (T, Int) = (t, Rank.Reference)

  /** Splits a vector of Eithers. */
  def partition[A](v: Vector[Either[Problem, A]]): (Vector[A], Vector[Problem]) =
    (v.collect { case Right(a) => a }, v.collect { case Left(p) => p })
}
