package scholarchain.hadoop.dblp

import java.util.Locale

import scholarchain.core.{
  ExactId,
  ExactIdScheme,
  Identifiers,
  SchemaVersions,
  SourceType,
  Venue,
  VenueId,
  WorkId,
  WorkRecord
}
import scholarchain.hadoop.normalize.{Build, Normalized, Outcome}
import scholarchain.hadoop.{
  DblpRecordTypes,
  DblpSettings,
  HadoopPipeline,
  Problem,
  RejectReason,
  RunContext,
  TextNormalization
}

/**
 * Maps one parsed DBLP record to core contracts (pure, deterministic).
 *
 *   - `www`, `person`, `data` records are skipped (never works); publication types outside
 *     `scholarchain.dblp.recordTypes`, the year range, or the venue list are filtered.
 *   - `workId` = `work:dblp:<key>`; `title` keeps the source text (whitespace collapsed),
 *     `normalizedTitle` is [[TextNormalization.title]].
 *   - Authors in document order, positions 0..n-1; `rawName` keeps the homonym suffix. Editors are
 *     not authors and are not extracted. `author/@orcid` is accepted bare or as an orcid.org URI.
 *   - DOI: the first `ee` with a doi.org prefix; further distinct DOIs go to `additionalIds` and
 *     are flagged as a conflict. arXiv: the first `ee` of the form `arxiv.org/abs/<id>` (further
 *     ones likewise).
 *   - `recordType` = element name; `venueName` = `journal` or `booktitle` (kept even without a
 *     Venue record); `volume`, `issue` (= `number`), `pages`; `eeLinks` = every `ee`, `urls` =
 *     every `url`, in source order, duplicates removed, values with whitespace or control
 *     characters dropped and flagged (the work is kept).
 *   - Venue record: only when the record has a `crossref` key (`venue:dblp:<crossref>`, named by
 *     `booktitle` or `journal`). The record key is never parsed for meaning (schema note).
 *   - Optional fields (year, ee, url, venue, volume, number, pages) may all be absent.
 */
/**
 * Classifies one raw record from [[DblpInputFormat]] (pure, deterministic; used by `DblpMapper`).
 *
 *   - unclosed markup report -> rejected `malformed-xml` (never a record);
 *   - `www`/`person`/`data` -> skipped; types outside `scholarchain.dblp.recordTypes` -> filtered;
 *   - interrupted by the next record start -> rejected `malformed-xml`;
 *   - cut by end of input or the size limit -> rejected `unterminated-record`;
 *   - otherwise parsed (`malformed-xml` on failure) and mapped by [[DblpMapping.map]].
 */
object DblpRawOutcome {
  def of(raw: DblpRawRecord, settings: DblpSettings, parser: DblpParser, run: RunContext): Outcome =
    if (raw.status == DblpRawRecord.UnclosedMarkup)
      Outcome.Rejected(
        None,
        Vector(
          Problem.of(
            RejectReason.MalformedXml,
            s"${raw.recordType} opened at byte ${raw.offset} is not closed within " +
              s"${settings.maxRecordBytes} bytes; its opener was read as text"
          )
        )
      )
    else if (!DblpRecordTypes.Publication.contains(raw.recordType))
      Outcome.Skipped(s"non-publication:${raw.recordType}")
    else if (!settings.recordTypes.contains(raw.recordType)) Outcome.Filtered(None, "record-type")
    else if (raw.status == DblpRawRecord.Interrupted)
      Outcome.Rejected(
        None,
        Vector(
          Problem.of(
            RejectReason.MalformedXml,
            s"<${raw.recordType}> at byte ${raw.offset} has no end tag before the next record " +
              s"start tag at byte ${raw.nextOffset}; the reader resynchronized there"
          )
        )
      )
    else if (!raw.complete)
      Outcome.Rejected(
        None,
        Vector(
          Problem.of(
            RejectReason.UnterminatedRecord,
            s"<${raw.recordType}> at byte ${raw.offset} has no end tag within " +
              s"${settings.maxRecordBytes} bytes or before end of input"
          )
        )
      )
    else
      parser.parse(raw.bytes) match {
        case Left(why) => Outcome.Rejected(None, Vector(Problem.of(RejectReason.MalformedXml, why)))
        case Right(rec) => DblpMapping.map(rec, settings, run)
      }
}

object DblpMapping {
  private val DoiPrefixes =
    Vector("https://doi.org/", "http://doi.org/", "https://dx.doi.org/", "http://dx.doi.org/")
  private val ArxivPrefixes = Vector("https://arxiv.org/abs/", "http://arxiv.org/abs/")

  def map(rec: DblpRecord, settings: DblpSettings, run: RunContext): Outcome =
    if (!DblpRecordTypes.Publication.contains(rec.recordType))
      Outcome.Skipped(s"non-publication:${rec.recordType}")
    else if (!settings.recordTypes.contains(rec.recordType))
      Outcome.Filtered(rec.key, "record-type")
    else
      rec.key match {
        case None =>
          Outcome.Rejected(None, Vector(Problem.of(RejectReason.MissingKey, "record has no key")))
        case Some(key) =>
          WorkId.dblp(key) match {
            case Left(e) =>
              Outcome.Rejected(Some(key), Vector(Problem.of(RejectReason.MissingKey, e.message)))
            case Right(workId) => mapKeyed(rec, key, workId, settings, run)
          }
      }

  private def mapKeyed(
      rec: DblpRecord,
      key: String,
      workId: String,
      settings: DblpSettings,
      run: RunContext
  ): Outcome = {
    val year: Either[Problem, Option[Int]] = rec.year match {
      case None => Right(None)
      case Some(y) =>
        y.toIntOption
          .filter(v => v >= 0 && v <= 9999)
          .map(Some(_))
          .toRight(Problem.of(RejectReason.MalformedField, s"year '$y' is not a year"))
    }
    year match {
      case Left(p) => Outcome.Rejected(Some(key), Vector(p))
      case Right(y) =>
        val venueName = rec.journal.orElse(rec.booktitle)
        if (settings.years.exists { case (a, b) => !y.exists(v => v >= a && v <= b) })
          Outcome.Filtered(Some(key), "year")
        else if (settings.venues.exists(vs => !venueName.exists(vs.contains)))
          Outcome.Filtered(Some(key), "venue")
        else build(rec, key, workId, y, run)
    }
  }

  /**
   * All distinct valid values in source order; malformed ones become flags. Several distinct values
   * are flagged as a conflict: the first becomes the dedicated field, the others are kept in
   * `additionalIds` (never dropped).
   */
  private def collect(
      label: String,
      candidates: Vector[(String, Either[String, String])]
  ): (Vector[String], Vector[Problem]) = {
    val bad = candidates.collect { case (raw, Left(why)) =>
      Problem.of(RejectReason.MalformedIdentifier, s"$label '$raw': $why; dropped")
    }
    val good = candidates.collect { case (_, Right(v)) => v }.distinct
    val conflict =
      if (good.size > 1)
        Vector(
          Problem.of(
            RejectReason.Conflict,
            s"several distinct $label values (${good.mkString(", ")}); the first is kept as the " +
              "dedicated identifier and the others in additionalIds"
          )
        )
      else Vector.empty
    (good, bad ++ conflict)
  }

  private def build(
      rec: DblpRecord,
      key: String,
      workId: String,
      year: Option[Int],
      run: RunContext
  ): Outcome = {
    val prov = Build.provenance(run, key, HadoopPipeline.DblpSchema)
    rec.title match {
      case None =>
        Outcome.Rejected(Some(key), Vector(Problem.of(RejectReason.MissingField, "title")))
      case Some(title) =>
        val (dois, doiFlags) = collect(
          "ee DOI",
          rec.ee
            .filter(e => DoiPrefixes.exists(p => e.toLowerCase(Locale.ROOT).startsWith(p)))
            .map(e => e -> Identifiers.normalizeDoi(e))
        )
        val (arxivs, arxivFlags) = collect(
          "ee arXiv",
          rec.ee.flatMap { e =>
            ArxivPrefixes.find(e.startsWith).map { p =>
              val id = e.substring(p.length)
              e -> (if (Identifiers.isValidArxiv(id)) Right(id)
                    else Left("not an arXiv identifier"))
            }
          }
        )
        val authors = rec.authors.zipWithIndex.map { case (a, i) =>
          if (a.name.isEmpty)
            Left(Problem.of(RejectReason.MissingField, s"authors[$i]: empty author name"))
          else {
            val (orcid, flag) = a.orcid match {
              case None => (None, None)
              case Some(o) =>
                Identifiers.normalizeOrcid(o) match {
                  case Right(v) => (Some(v), None)
                  case Left(why) =>
                    (
                      None,
                      Some(
                        Problem.of(
                          RejectReason.MalformedIdentifier,
                          s"authors[$i].orcid '$o': $why; dropped"
                        )
                      )
                    )
                }
            }
            Build
              .mention(workId, i, a.name, None, orcid, Vector.empty, Vector.empty, prov)
              .map(m => (m, flag))
          }
        }
        val (mentions, authorProblems) = Build.partition(authors)
        if (authorProblems.nonEmpty) Outcome.Rejected(Some(key), authorProblems)
        else {
          val venue = for {
            c <- rec.crossref if Identifiers.isValidDblpKey(c)
            name <- rec.booktitle.orElse(rec.journal)
            vid <- VenueId.of("dblp", c).toOption
          } yield Venue(SchemaVersions.Venue, vid, name, None, Vector.empty, Vector.empty, prov)
          val (venues, venueFlags) = Build.keepValid(venue.toVector)
          val (venueName, venueNameFlags) =
            Build.optText("journal/booktitle", rec.journal.orElse(rec.booktitle))
          val (volume, volumeFlags) = Build.optText("volume", rec.volume)
          val (issue, issueFlags) = Build.optText("number", rec.number)
          val (pages, pagesFlags) = Build.optText("pages", rec.pages)
          val (eeLinks, eeFlags) = Build.links("ee", rec.ee)
          val (urls, urlFlags) = Build.links("url", rec.urls)
          val extra = ExactId.canonical(
            dois.drop(1).map(ExactId(ExactIdScheme.Doi, _)) ++
              arxivs.drop(1).map(ExactId(ExactIdScheme.Arxiv, _))
          )
          val work = WorkRecord(
            schemaVersion = SchemaVersions.WorkRecord,
            workId = workId,
            source = SourceType.Dblp,
            sourceRecordId = key,
            title = title,
            normalizedTitle = TextNormalization.title(title),
            year = year,
            venueId = venues.headOption.map(_.venueId),
            doi = dois.headOption,
            arxivId = arxivs.headOption,
            authors = mentions.map(_._1),
            abstractTextRef = None,
            provenance = prov,
            recordType = Some(rec.recordType),
            venueName = venueName,
            volume = volume,
            issue = issue,
            pages = pages,
            eeLinks = eeLinks,
            urls = urls,
            additionalIds = extra
          )
          Build.validated(work) match {
            case Left(p) => Outcome.Rejected(Some(key), Vector(p))
            case Right(w) =>
              Outcome.Accepted(
                key,
                Normalized.empty.copy(
                  works = Vector(w),
                  venues = venues,
                  flags = doiFlags ++ arxivFlags ++ mentions.flatMap(_._2) ++ venueFlags ++
                    venueNameFlags ++ volumeFlags ++ issueFlags ++ pagesFlags ++ eeFlags ++
                    urlFlags
                )
              )
          }
        }
    }
  }
}
