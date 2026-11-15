package scholarchain.hadoop.normalize

import scala.util.control.NonFatal

import scholarchain.core.{
  ExactId,
  ExactIdScheme,
  ExternalId,
  ExternalIdScheme,
  Identifiers,
  Institution,
  InstitutionId,
  SchemaVersions,
  SourceAuthorId,
  SourceAuthorProfile,
  SourceType,
  Topic,
  TopicId,
  Venue,
  VenueId,
  WorkId,
  WorkRecord
}
import scholarchain.hadoop.{HadoopPipeline, Problem, RejectReason, RunContext, TextNormalization}

/** Null-safe navigation of ujson values (JSON `null` is treated as absent). */
object Js {
  def field(v: ujson.Value, k: String): Option[ujson.Value] = v match {
    case o: ujson.Obj => o.value.get(k).filter(_ != ujson.Null)
    case _ => None
  }
  def path(v: ujson.Value, ks: String*): Option[ujson.Value] =
    ks.foldLeft(Option(v))((acc, k) => acc.flatMap(field(_, k)))
  def str(v: ujson.Value, ks: String*): Option[String] =
    path(v, ks*).collect { case ujson.Str(s) => s.trim }.filter(_.nonEmpty)
  def int(v: ujson.Value, ks: String*): Option[Int] =
    path(v, ks*).collect { case ujson.Num(n) if n.isWhole && math.abs(n) < 1e9 => n.toInt }
  def arr(v: ujson.Value, ks: String*): Vector[ujson.Value] =
    path(v, ks*).collect { case a: ujson.Arr => a.value.toVector }.getOrElse(Vector.empty)
  def strs(v: ujson.Value, ks: String*): Vector[String] =
    arr(v, ks*).collect { case ujson.Str(s) if s.trim.nonEmpty => s.trim }

  def parse(line: String): Either[Problem, ujson.Value] =
    try {
      ujson.read(line) match {
        case o: ujson.Obj => Right(o)
        case _ => Left(Problem.of(RejectReason.MalformedJson, "line is not a JSON object"))
      }
    } catch {
      case NonFatal(e) => Left(Problem.of(RejectReason.MalformedJson, String.valueOf(e.getMessage)))
    }
}

/** Optional identifier: absent is fine; malformed is dropped and flagged. */
private[normalize] object OptId {
  def apply(
      label: String,
      raw: Option[String],
      norm: String => Either[String, String]
  ): (Option[String], Vector[Problem]) =
    raw match {
      case None => (None, Vector.empty)
      case Some(r) =>
        norm(r) match {
          case Right(v) => (Some(v), Vector.empty)
          case Left(why) =>
            (
              None,
              Vector(Problem.of(RejectReason.MalformedIdentifier, s"$label '$r': $why; dropped"))
            )
        }
    }
}

/**
 * OpenAlex JSON Lines normalizers (schema note `openalex-raw/1.0.0`). OpenAlex IDs are kept as
 * short IDs (`W…`, `A…`, `I…`, `S…`, `T…`). Abstracts are not used (excluded by the dataset
 * manifest). Works also keep `type` (recordType), the source display name (venueName),
 * `biblio.volume`/`issue`, pages `<first_page>-<last_page>` (or the first page alone), topic IDs
 * (primary topic first, then `topics[]`), and `ids.mag` as an additional `mag` identifier.
 */
object OpenAlexNormalizer {

  /**
   * @param embeddedIn
   *   the work that embeds this institution (its provenance record), or None for the entity file
   */
  private def institution(
      v: ujson.Value,
      run: RunContext,
      embeddedIn: Option[String]
  ): Either[Problem, Institution] = {
    val rawId = Js.str(v, "id")
    rawId.toRight(Problem.of(RejectReason.MissingKey, "institution id")).flatMap { raw =>
      Identifiers.normalizeOpenAlex('I', raw) match {
        case Left(why) =>
          Left(Problem.of(RejectReason.MalformedIdentifier, s"institution id '$raw': $why"))
        case Right(iid) =>
          val (ror, rorFlags) = OptId("institution ror", Js.str(v, "ror"), Identifiers.normalizeRor)
          if (rorFlags.nonEmpty) Left(rorFlags.head)
          else {
            val country = Js.str(v, "country_code").filter(_.matches("[A-Z]{2}"))
            val inst = Institution(
              schemaVersion = SchemaVersions.Institution,
              institutionId = s"institution:openalex:$iid",
              name = Js.str(v, "display_name").getOrElse(""),
              rorId = ror,
              countryCode = country,
              externalIds = Vector(ExternalId(ExternalIdScheme.OpenAlex, iid)) ++
                ror.map(ExternalId(ExternalIdScheme.Ror, _)).toVector,
              provenance =
                Build.provenance(run, embeddedIn.getOrElse(iid), HadoopPipeline.OpenAlexSchema)
            )
            InstitutionId
              .of("openalex", iid)
              .left
              .map(e => Problem.of(RejectReason.SchemaFailure, e.message))
              .flatMap(_ => Build.validated(inst))
          }
      }
    }
  }

  /** One line of the `institutions` entity. */
  def institutionLine(line: String, run: RunContext): Outcome =
    Js.parse(line) match {
      case Left(p) => Outcome.Rejected(None, Vector(p))
      case Right(v) =>
        val id = Js.str(v, "id")
        institution(v, run, None) match {
          case Left(p) => Outcome.Rejected(id, Vector(p))
          case Right(i) =>
            Outcome.Accepted(
              i.provenance.sourceRecordId,
              Normalized.empty.copy(institutions = Vector(Build.primary(i)))
            )
        }
    }

  /**
   * One line of the `authors` entity: a [[SourceAuthorProfile]] `source-author:openalex:<A-ID>`
   * with the display name, `display_name_alternatives` as name variants (distinct, source order),
   * the asserted ORCID, the A-ID as external ID, and the last known institutions (also emitted as
   * reference institution records). A malformed ORCID or institution is dropped and flagged.
   */
  def authorLine(line: String, run: RunContext): Outcome =
    Js.parse(line) match {
      case Left(p) => Outcome.Rejected(None, Vector(p))
      case Right(v) =>
        Js.str(v, "id") match {
          case None => Outcome.Rejected(None, Vector(Problem.of(RejectReason.MissingKey, "id")))
          case Some(rawId) =>
            Identifiers.normalizeOpenAlex('A', rawId) match {
              case Left(why) =>
                Outcome.Rejected(
                  Some(rawId),
                  Vector(Problem.of(RejectReason.MissingKey, s"author id '$rawId': $why"))
                )
              case Right(aid) => author(v, aid, run)
            }
        }
    }

  private def author(v: ujson.Value, aid: String, run: RunContext): Outcome = {
    val prov = Build.provenance(run, aid, HadoopPipeline.OpenAlexSchema)
    val (orcid, orcidFlags) = OptId("author orcid", Js.str(v, "orcid"), Identifiers.normalizeOrcid)
    val insts = Js.arr(v, "last_known_institutions").map(institution(_, run, Some(aid)))
    val (okInsts, instFlags) = Build.partition(insts)
    val (variants, variantFlags) = {
      val raw = Js.arr(v, "display_name_alternatives").collect { case ujson.Str(s) => s.trim }
      val checked = raw.map(s => Build.optText("display_name_alternatives", Some(s)))
      (checked.flatMap(_._1).distinct, checked.flatMap(_._2))
    }
    val (display, displayFlags) = Build.optText("display_name", Js.str(v, "display_name"))
    SourceAuthorId.openAlex(aid) match {
      case Left(e) =>
        Outcome.Rejected(Some(aid), Vector(Problem.of(RejectReason.MissingKey, e.message)))
      case Right(profileId) =>
        val profile = SourceAuthorProfile(
          schemaVersion = SchemaVersions.SourceAuthorProfile,
          profileId = profileId,
          source = SourceType.OpenAlex,
          sourceRecordId = aid,
          displayName = display,
          givenNames = None,
          familyName = None,
          nameVariants = variants,
          orcid = orcid,
          externalIds = Vector(ExternalId(ExternalIdScheme.OpenAlex, aid)),
          institutionIds = okInsts.map(_.institutionId).distinct,
          claimedWorkIds = Vector.empty,
          provenance = prov
        )
        Build.validated(profile) match {
          case Left(p) => Outcome.Rejected(Some(aid), Vector(p))
          case Right(ok) =>
            Outcome.Accepted(
              aid,
              Normalized.empty.copy(
                profiles = Vector(ok),
                institutions = okInsts.map(Build.reference),
                flags = orcidFlags ++ instFlags.map(dropped) ++ variantFlags ++ displayFlags
              )
            )
        }
    }
  }

  /** One line of the `works` entity. */
  def workLine(line: String, run: RunContext): Outcome =
    Js.parse(line) match {
      case Left(p) => Outcome.Rejected(None, Vector(p))
      case Right(v) =>
        Js.str(v, "id") match {
          case None => Outcome.Rejected(None, Vector(Problem.of(RejectReason.MissingKey, "id")))
          case Some(rawId) =>
            WorkId.openAlex(rawId) match {
              case Left(e) =>
                Outcome.Rejected(
                  Some(rawId),
                  Vector(Problem.of(RejectReason.MissingKey, e.message))
                )
              case Right(workId) => work(v, workId, run)
            }
        }
    }

  private def work(v: ujson.Value, workId: String, run: RunContext): Outcome = {
    val wid = workId.stripPrefix("work:openalex:")
    val prov = Build.provenance(run, wid, HadoopPipeline.OpenAlexSchema)
    Js.str(v, "title").orElse(Js.str(v, "display_name")) match {
      case None =>
        Outcome.Rejected(Some(wid), Vector(Problem.of(RejectReason.MissingField, "title")))
      case Some(title) =>
        val (doi, doiFlags) = OptId("doi", Js.str(v, "doi"), Identifiers.normalizeDoi)
        // venue (primary_location.source)
        val src = Js.path(v, "primary_location", "source")
        val (venue, venueFlags) = src.flatMap(s => Js.str(s, "id")) match {
          case None => (None, Vector.empty)
          case Some(rawS) =>
            Identifiers.normalizeOpenAlex('S', rawS) match {
              case Left(why) =>
                (
                  None,
                  Vector(
                    Problem.of(
                      RejectReason.MalformedIdentifier,
                      s"source id '$rawS': $why; dropped"
                    )
                  )
                )
              case Right(sid) =>
                val s = src.get
                val (issnL, issnFlags) = OptId(
                  "source issn_l",
                  Js.str(s, "issn_l"),
                  i =>
                    if (Identifiers.isValidIssn(i)) Right(i) else Left("not a checksum-valid ISSN")
                )
                val candidate = Js.str(s, "display_name").map { name =>
                  Venue(
                    SchemaVersions.Venue,
                    s"venue:openalex:$sid",
                    name,
                    Js.str(s, "type"),
                    issnL.toVector,
                    Vector(ExternalId(ExternalIdScheme.OpenAlex, sid)),
                    prov
                  )
                }
                val (kept, bad) = Build.keepValid(candidate.toVector)
                (kept.headOption, issnFlags ++ bad)
            }
        }
        // topics: primary_topic first, then topics[] (distinct)
        val topicResults = (Js.path(v, "primary_topic").toVector ++ Js.arr(v, "topics"))
          .flatMap(t => Js.str(t, "id").map(t -> _))
          .map { case (t, rawT) =>
            Identifiers
              .normalizeOpenAlex('T', rawT)
              .flatMap(tid => TopicId.of("openalex", tid).left.map(_.message)) match {
              case Left(why) =>
                Left(
                  Problem.of(RejectReason.MalformedIdentifier, s"topic id '$rawT': $why; dropped")
                )
              case Right(topicId) =>
                val candidate = Js
                  .str(t, "display_name")
                  .map(n => Topic(SchemaVersions.Topic, topicId, n, None, prov))
                val (kept, bad) = Build.keepValid(candidate.toVector)
                Right((topicId, kept, bad))
            }
          }
        val topicIds = topicResults.collect { case Right((id, _, _)) => id }.distinct
        val topics = topicResults.collect { case Right((_, kept, _)) => kept }.flatten
        val topicFlags =
          topicResults.collect { case Left(p) => p } ++
            topicResults.collect { case Right((_, _, bad)) => bad }.flatten
        // v3 work fields
        val (recordType, typeFlags) = Build.optToken("type", Js.str(v, "type"))
        val (venueName, venueNameFlags) =
          Build.optText(
            "primary_location.source.display_name",
            src.flatMap(Js.str(_, "display_name"))
          )
        val (volume, volumeFlags) = Build.optText("biblio.volume", Js.str(v, "biblio", "volume"))
        val (issue, issueFlags) = Build.optText("biblio.issue", Js.str(v, "biblio", "issue"))
        val (pages, pagesFlags) = Build.optText(
          "biblio.first_page/last_page",
          (Js.str(v, "biblio", "first_page"), Js.str(v, "biblio", "last_page")) match {
            case (Some(f), Some(l)) if f != l => Some(s"$f-$l")
            case (Some(f), _) => Some(f)
            case (None, l) => l
          }
        )
        val mag = Js.path(v, "ids", "mag").toVector.collect {
          case ujson.Str(s) if s.trim.nonEmpty => s.trim
          case ujson.Num(n) if n.isWhole && n >= 0 => BigDecimal(n).toBigInt.toString
        }
        val (magIds, magFlags) = Build.exactIds("ids.mag", ExactIdScheme.Mag, mag)
        // authorships
        val built = Js.arr(v, "authorships").zipWithIndex.map { case (a, i) =>
          Js.str(a, "raw_author_name").orElse(Js.str(a, "author", "display_name")) match {
            case None =>
              Left(Problem.of(RejectReason.MissingField, s"authorships[$i]: no author name"))
            case Some(name) =>
              val (aid, aidFlags) = OptId(
                s"authorships[$i].author.id",
                Js.str(a, "author", "id"),
                Identifiers.normalizeOpenAlex('A', _)
              )
              val (orcid, orcidFlags) =
                OptId(
                  s"authorships[$i].author.orcid",
                  Js.str(a, "author", "orcid"),
                  Identifiers.normalizeOrcid
                )
              val insts = Js.arr(a, "institutions").map(institution(_, run, Some(wid)))
              val (okInsts, instFlags) = Build.partition(insts)
              Build
                .mention(
                  workId,
                  i,
                  name,
                  aid.map(ExternalId(ExternalIdScheme.OpenAlex, _)),
                  orcid,
                  okInsts.map(_.institutionId).distinct,
                  Js.strs(a, "raw_affiliation_strings"),
                  prov
                )
                .map(m => (m, okInsts, aidFlags ++ orcidFlags ++ instFlags.map(dropped)))
          }
        }
        val (mentions, problems) = Build.partition(built)
        if (problems.nonEmpty) Outcome.Rejected(Some(wid), problems)
        else {
          val w = WorkRecord(
            schemaVersion = SchemaVersions.WorkRecord,
            workId = workId,
            source = SourceType.OpenAlex,
            sourceRecordId = wid,
            title = TextNormalization.collapse(title),
            normalizedTitle = TextNormalization.title(title),
            year = Js.int(v, "publication_year"),
            venueId = venue.map(_.venueId),
            doi = doi,
            arxivId = None,
            authors = mentions.map(_._1),
            abstractTextRef = None,
            provenance = prov,
            recordType = recordType,
            venueName = venueName,
            volume = volume,
            issue = issue,
            pages = pages,
            topicIds = topicIds,
            additionalIds = ExactId.canonical(magIds)
          )
          Build.validated(w) match {
            case Left(p) => Outcome.Rejected(Some(wid), Vector(p))
            case Right(ok) =>
              Outcome.Accepted(
                wid,
                Normalized(
                  works = Vector(ok),
                  venues = venue.toVector,
                  institutions = mentions.flatMap(_._2).map(Build.reference),
                  topics = topics,
                  flags = doiFlags ++ venueFlags ++ topicFlags ++ mentions.flatMap(_._3) ++
                    typeFlags ++ venueNameFlags ++ volumeFlags ++ issueFlags ++ pagesFlags ++
                    magFlags
                )
              )
          }
        }
    }
  }

  private def dropped(p: Problem): Problem =
    p.copy(detail = p.detail.map(_ + "; affiliation dropped"))
}

/**
 * Crossref JSON Lines normalizer (schema note `crossref-raw/1.0.0`). `workId` = `work:doi:<doi>`.
 * Title = `title[0]`, plus `": " + subtitle[0]` when a subtitle exists (the schema note's
 * `normalized_title (title plus subtitle)`). Venue = the first checksum-valid ISSN, named by
 * `container-title[0]`. Also `type` (recordType), `container-title[0]` (venueName, kept even
 * without a valid ISSN), `volume`, `issue`, and `page`. Abstracts, references, links, and licences
 * are dropped.
 *
 * A line is either one bare work object (the public data file shape) or one REST API envelope
 * holding exactly one work (the ER candidate samples, schema note `er-candidates-raw/1.0.0`):
 * `{"status":"ok","message-type":"work-list","message":{...,"items":[<work>]}}` or
 * `{"status":"ok","message-type":"work","message":<work>}`. See [[CrossrefNormalizer.unwrap]].
 */
object CrossrefNormalizer {

  /** Top-level keys that make a line a REST API envelope (a bare work has none of them). */
  val EnvelopeKeys: Vector[String] = Vector("status", "message-type", "message")

  /**
   * The work object of one parsed line. A line with any of [[EnvelopeKeys]] is an envelope and must
   * have `status` "ok" and either `message-type` "work-list" with exactly one object in
   * `message.items`, or `message-type` "work" with an object `message`. Anything else is a problem
   * (the line is rejected, never guessed): a missing envelope field is `missing-field`, a wrong
   * status, message type or item count is `malformed-field`, an empty item list is `missing-key`
   * (no work, so no DOI), and a non-object item is `malformed-json`. A line without envelope keys
   * is returned unchanged (bare work).
   */
  def unwrap(v: ujson.Value): Either[Problem, ujson.Value] = {
    val obj = v match {
      case o: ujson.Obj => o.value
      case _ => scala.collection.mutable.LinkedHashMap.empty[String, ujson.Value]
    }
    if (!EnvelopeKeys.exists(obj.contains)) Right(v)
    else {
      def missing(what: String) =
        Left(Problem.of(RejectReason.MissingField, s"Crossref envelope: $what is missing"))
      def asObject(x: ujson.Value, what: String): Either[Problem, ujson.Value] = x match {
        case o: ujson.Obj => Right(o)
        case _ =>
          Left(Problem.of(RejectReason.MalformedJson, s"Crossref envelope: $what is not an object"))
      }
      val status = obj.get("status") match {
        case None | Some(ujson.Null) => missing("status")
        case Some(ujson.Str("ok")) => Right(())
        case Some(other) =>
          Left(
            Problem.of(
              RejectReason.MalformedField,
              s"Crossref envelope: status is ${ujson.write(other)}, expected \"ok\""
            )
          )
      }
      val message = obj.get("message").filter(_ != ujson.Null) match {
        case None => missing("message")
        case Some(m) => asObject(m, "message")
      }
      for {
        _ <- status
        m <- message
        work <- obj.get("message-type") match {
          case None | Some(ujson.Null) => missing("message-type")
          case Some(ujson.Str("work")) => Right(m)
          case Some(ujson.Str("work-list")) =>
            Js.field(m, "items") match {
              case None => missing("message.items")
              case Some(a: ujson.Arr) if a.value.isEmpty =>
                Left(
                  Problem.of(
                    RejectReason.MissingKey,
                    "Crossref work-list envelope: message.items is empty (no work, no DOI)"
                  )
                )
              case Some(a: ujson.Arr) if a.value.size > 1 =>
                Left(
                  Problem.of(
                    RejectReason.MalformedField,
                    s"Crossref work-list envelope: message.items holds ${a.value.size} works; " +
                      "exactly one per line is expected"
                  )
                )
              case Some(a: ujson.Arr) => asObject(a.value.head, "message.items[0]")
              case Some(_) =>
                Left(
                  Problem.of(
                    RejectReason.MalformedField,
                    "Crossref work-list envelope: message.items is not an array"
                  )
                )
            }
          case Some(other) =>
            Left(
              Problem.of(
                RejectReason.MalformedField,
                s"Crossref envelope: message-type ${ujson.write(other)} is not \"work-list\" or \"work\""
              )
            )
        }
      } yield work
    }
  }

  def workLine(line: String, run: RunContext): Outcome =
    Js.parse(line).flatMap(unwrap) match {
      case Left(p) => Outcome.Rejected(None, Vector(p))
      case Right(v) =>
        Js.str(v, "DOI") match {
          case None => Outcome.Rejected(None, Vector(Problem.of(RejectReason.MissingKey, "DOI")))
          case Some(rawDoi) =>
            WorkId.doi(rawDoi) match {
              case Left(e) =>
                Outcome.Rejected(
                  Some(rawDoi),
                  Vector(Problem.of(RejectReason.MissingKey, e.message))
                )
              case Right(workId) => work(v, workId, run)
            }
        }
    }

  private def work(v: ujson.Value, workId: String, run: RunContext): Outcome = {
    val doi = workId.stripPrefix("work:doi:")
    val prov = Build.provenance(run, doi, HadoopPipeline.CrossrefSchema)
    Js.strs(v, "title").headOption match {
      case None =>
        Outcome.Rejected(Some(doi), Vector(Problem.of(RejectReason.MissingField, "title")))
      case Some(main) =>
        val title = Js.strs(v, "subtitle").headOption.fold(main)(sub => s"$main: $sub")
        val issns = Js.strs(v, "ISSN")
        val validIssns = issns.filter(Identifiers.isValidIssn).distinct
        val issnFlags = issns.filterNot(Identifiers.isValidIssn).map { i =>
          Problem.of(
            RejectReason.MalformedIdentifier,
            s"ISSN '$i': not a checksum-valid ISSN; dropped"
          )
        }
        val venueCandidate = for {
          first <- validIssns.headOption
          name <- Js.strs(v, "container-title").headOption
          vid <- VenueId.of("issn", first).toOption
        } yield Venue(
          SchemaVersions.Venue,
          vid,
          name,
          None,
          validIssns,
          validIssns.map(ExternalId(ExternalIdScheme.Issn, _)),
          prov
        )
        val (venues, venueFlags) = Build.keepValid(venueCandidate.toVector)
        val built = Js.arr(v, "author").zipWithIndex.map { case (a, i) =>
          val personal = Vector(Js.str(a, "given"), Js.str(a, "family")).flatten.mkString(" ")
          val name = if (personal.nonEmpty) Some(personal) else Js.str(a, "name")
          name match {
            case None => Left(Problem.of(RejectReason.MissingField, s"author[$i]: no name"))
            case Some(n) =>
              val (orcid, flags) =
                OptId(s"author[$i].ORCID", Js.str(a, "ORCID"), Identifiers.normalizeOrcid)
              Build
                .mention(
                  workId,
                  i,
                  n,
                  None,
                  orcid,
                  Vector.empty,
                  Js.arr(a, "affiliation").flatMap(Js.str(_, "name")),
                  prov
                )
                .map(_ -> flags)
          }
        }
        val (mentions, problems) = Build.partition(built)
        if (problems.nonEmpty) Outcome.Rejected(Some(doi), problems)
        else {
          val year = Js.arr(v, "issued", "date-parts").headOption.flatMap {
            case a: ujson.Arr =>
              a.value.headOption.collect { case ujson.Num(n) if n.isWhole => n.toInt }
            case _ => None
          }
          val (recordType, typeFlags) = Build.optToken("type", Js.str(v, "type"))
          val (venueName, venueNameFlags) =
            Build.optText("container-title[0]", Js.strs(v, "container-title").headOption)
          val (volume, volumeFlags) = Build.optText("volume", Js.str(v, "volume"))
          val (issue, issueFlags) = Build.optText("issue", Js.str(v, "issue"))
          val (pages, pagesFlags) = Build.optText("page", Js.str(v, "page"))
          val w = WorkRecord(
            schemaVersion = SchemaVersions.WorkRecord,
            workId = workId,
            source = SourceType.Crossref,
            sourceRecordId = doi,
            title = TextNormalization.collapse(title),
            normalizedTitle = TextNormalization.title(title),
            year = year,
            venueId = venues.headOption.map(_.venueId),
            doi = Some(doi),
            arxivId = None,
            authors = mentions.map(_._1),
            abstractTextRef = None,
            provenance = prov,
            recordType = recordType,
            venueName = venueName,
            volume = volume,
            issue = issue,
            pages = pages
          )
          Build.validated(w) match {
            case Left(p) => Outcome.Rejected(Some(doi), Vector(p))
            case Right(ok) =>
              Outcome.Accepted(
                doi,
                Normalized.empty.copy(
                  works = Vector(ok),
                  venues = venues,
                  flags = issnFlags ++ venueFlags ++ mentions.flatMap(_._2) ++ typeFlags ++
                    venueNameFlags ++ volumeFlags ++ issueFlags ++ pagesFlags
                )
              )
          }
        }
    }
  }
}
