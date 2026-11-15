package scholarchain.entityresolution.review

import upickle.default.ReadWriter
import upickle.implicits.allowUnknownKeys

import scholarchain.core.{SourceAuthorProfile, WorkRecord}
import scholarchain.entityresolution.adjudication.EndpointEvidence

/**
 * Where one candidate endpoint came from: enough to find the source record again.
 *
 * @param endpointKind
 *   `mention` (an author position on a work), `profile` (a source author profile), or `work`
 * @param workId
 *   the work of a mention or work endpoint
 * @param position
 *   0-based author position of a mention
 * @param displayText
 *   what the reviewer reads: the name as written plus the work title, or the profile names, or the
 *   work title, year, venue, and first authors (untrusted source text)
 */
@allowUnknownKeys(false)
final case class EndpointRef(
    endpointId: String,
    endpointKind: String,
    sourceDataset: String,
    sourceRecordId: String,
    snapshot: String,
    sourceSchemaVersion: String,
    recordRunId: String,
    workId: Option[String],
    position: Option[Int],
    displayText: Vector[String]
) derives ReadWriter {
  def evidence: EndpointEvidence = EndpointEvidence(endpointId, sourceDataset, displayText)
}

object EndpointRef {
  private def workLines(w: WorkRecord): Vector[String] =
    Vector(
      Some(s"title: ${w.title}"),
      w.year.map(y => s"year: $y"),
      w.venueName.map(v => s"venue: $v"),
      Some(
        s"authors: ${w.authors.take(6).map(_.rawName).mkString("; ")}" +
          (if (w.authors.size > 6) s"; ... (${w.authors.size} authors)" else "")
      )
    ).flatten

  /** References of a work and of every author mention on it. */
  def fromWork(w: WorkRecord): Vector[EndpointRef] = {
    val p = w.provenance
    val work = EndpointRef(
      w.workId,
      "work",
      w.source.tag,
      w.sourceRecordId,
      p.snapshot,
      p.sourceSchemaVersion,
      p.runId,
      Some(w.workId),
      None,
      workLines(w)
    )
    work +: w.authors.map { m =>
      EndpointRef(
        m.mentionId,
        "mention",
        w.source.tag,
        w.sourceRecordId,
        m.provenance.snapshot,
        m.provenance.sourceSchemaVersion,
        m.provenance.runId,
        Some(w.workId),
        Some(m.position),
        Vector(s"author as written: ${m.rawName} (position ${m.position + 1} of ${w.authors.size})")
          ++ m.rawAffiliations.take(2).map(a => s"affiliation: $a") ++ workLines(w)
      )
    }
  }

  def fromProfile(p: SourceAuthorProfile): EndpointRef =
    EndpointRef(
      p.profileId,
      "profile",
      p.source.tag,
      p.sourceRecordId,
      p.provenance.snapshot,
      p.provenance.sourceSchemaVersion,
      p.provenance.runId,
      None,
      None,
      (p.displayName.map(n => s"display name: $n").toVector ++
        (p.givenNames.toVector ++ p.familyName.toVector).headOption
          .map(_ =>
            s"structured name: ${p.givenNames.getOrElse("")} / ${p.familyName.getOrElse("")}"
          )
          .toVector ++
        p.nameVariants.take(4).map(v => s"name variant: $v") ++
        p.orcid.map(o => s"orcid: $o").toVector)
    )

  /** Lookup of every endpoint of the given records by endpoint ID. */
  def index(
      works: Vector[WorkRecord],
      profiles: Vector[SourceAuthorProfile]
  ): Map[String, EndpointRef] =
    (works.flatMap(fromWork) ++ profiles.map(fromProfile)).map(r => r.endpointId -> r).toMap
}
