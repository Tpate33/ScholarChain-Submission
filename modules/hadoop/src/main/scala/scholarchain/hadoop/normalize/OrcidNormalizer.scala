package scholarchain.hadoop.normalize

import java.io.{ByteArrayInputStream, IOException}
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

import org.w3c.dom.{Document, Element, Node}
import org.xml.sax.{ErrorHandler, SAXException, SAXParseException}

import scholarchain.core.{
  AssertionSource,
  AssertionSourceKind,
  Canonical,
  ExactId,
  ExactIdScheme,
  ExternalId,
  ExternalIdScheme,
  Identifiers,
  SchemaVersions,
  SourceAuthorId,
  SourceAuthorProfile,
  SourceLocalWorkId,
  SourceType,
  Venue,
  VenueId,
  WorkId,
  WorkRecord
}
import scholarchain.hadoop.{HadoopPipeline, Problem, RejectReason, RunContext, TextNormalization}

/**
 * ORCID summaries normalizer (schema note `orcid-raw/1.0.0`): one XML file per ORCID record.
 *
 * Each `work:work-summary` becomes a WorkRecord `work:orcid-work:<iD>/work/<put-code>` with the
 * summary's own title, publication year, `work:type` (recordType), `work:journal-title`
 * (venueName), and a venue when the summary has a `part-of` ISSN (`venue:issn:<ISSN>`). Only the
 * summary's OWN `common:external-ids` are read, never the `activities:group` ones (a group merges
 * several summaries and does not say which source issued an ID):
 *   - `doi` (`self`): the first valid one is `doi`, further distinct ones go to `additionalIds`;
 *   - `eid` (`self`): Scopus EIDs, to `additionalIds` as `scopus-eid`;
 *   - `source-work-id`: to `sourceLocalWorkIds`, each qualified by the summary's submitting source
 *     ([[SourceLocalWorkId.issuedBy]]); never to `additionalIds`.
 *
 * `common:source` (`source-client-id` or `source-orcid`, `source-name`, and the optional
 * `assertion-origin-*`) becomes `assertionSource`. Work summaries list no contributors, so
 * `authors` is empty.
 *
 * The record holder becomes a [[SourceAuthorProfile]] `source-author:orcid:<iD>` with the public
 * given and family names, the credit name as display name, other names as name variants, and the
 * holder-to-work claims (`claimedWorkIds`, sorted by UTF-8 bytes). Only the fields of the schema
 * note are read (privacy minimisation); DOCTYPE declarations are refused.
 */
final class OrcidNormalizer {
  private val NsCommon = "http://www.orcid.org/ns/common"
  private val NsWork = "http://www.orcid.org/ns/work"
  private val NsPerson = "http://www.orcid.org/ns/person"
  private val NsPersonal = "http://www.orcid.org/ns/personal-details"
  private val NsOtherName = "http://www.orcid.org/ns/other-name"

  private val factory: DocumentBuilderFactory = {
    val f = DocumentBuilderFactory.newInstance()
    f.setNamespaceAware(true)
    f.setValidating(false)
    f.setXIncludeAware(false)
    f.setExpandEntityReferences(false)
    f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    f.setFeature("http://xml.org/sax/features/external-general-entities", false)
    f.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
    f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    f
  }

  private object Throwing extends ErrorHandler {
    def warning(e: SAXParseException): Unit = ()
    def error(e: SAXParseException): Unit = throw e
    def fatalError(e: SAXParseException): Unit = throw e
  }

  private def parse(bytes: Array[Byte]): Either[Problem, Document] =
    try {
      val b = factory.newDocumentBuilder()
      b.setErrorHandler(Throwing)
      Right(b.parse(new ByteArrayInputStream(bytes)))
    } catch {
      case e: SAXException =>
        Left(Problem.of(RejectReason.MalformedXml, String.valueOf(e.getMessage)))
      case e: IOException =>
        Left(Problem.of(RejectReason.MalformedXml, String.valueOf(e.getMessage)))
    }

  private def children(e: Element, ns: String, local: String): Vector[Element] = {
    val nodes = e.getChildNodes
    (0 until nodes.getLength).toVector.map(nodes.item).collect {
      case c: Element
          if c.getNodeType == Node.ELEMENT_NODE && c.getNamespaceURI == ns && c.getLocalName == local =>
        c
    }
  }

  private def child(e: Element, ns: String, local: String): Option[Element] =
    children(e, ns, local).headOption

  private def text(e: Element): Option[String] =
    Option(e.getTextContent).map(TextNormalization.collapse).filter(_.nonEmpty)

  private def descendants(d: Document, ns: String, local: String): Vector[Element] = {
    val nodes = d.getElementsByTagNameNS(ns, local)
    (0 until nodes.getLength).toVector.map(nodes.item).collect { case e: Element => e }
  }

  /** Absent visibility (the public data file) or `public`. */
  private def isPublic(e: Element): Boolean = {
    val v = e.getAttribute("visibility")
    v == null || v.isEmpty || v == "public"
  }

  /**
   * The summary's OWN external IDs as (type, value, relationship), preferring the normalized value.
   * Group-level external IDs are never read.
   */
  private def externalIds(ws: Element): Vector[(String, String, String)] =
    child(ws, NsCommon, "external-ids").toVector
      .flatMap(children(_, NsCommon, "external-id"))
      .flatMap { x =>
        for {
          t <- child(x, NsCommon, "external-id-type").flatMap(text)
          v <- child(x, NsCommon, "external-id-normalized")
            .flatMap(text)
            .orElse(child(x, NsCommon, "external-id-value").flatMap(text))
          r = child(x, NsCommon, "external-id-relationship").flatMap(text).getOrElse("")
        } yield (t, v, r)
      }

  /** (kind, path) of a `<prefix>-client-id` or `<prefix>-orcid` child of `common:source`. */
  private def party(src: Element, prefix: String): Option[(AssertionSourceKind, String)] =
    child(src, NsCommon, s"$prefix-client-id")
      .flatMap(child(_, NsCommon, "path"))
      .flatMap(text)
      .map(AssertionSourceKind.Client -> _)
      .orElse(
        child(src, NsCommon, s"$prefix-orcid")
          .flatMap(child(_, NsCommon, "path"))
          .flatMap(text)
          .map(AssertionSourceKind.Orcid -> _)
      )

  /** The summary's `common:source`; an invalid one is dropped and flagged. */
  private def assertionSource(
      ws: Element,
      recordId: String
  ): (Option[AssertionSource], Vector[Problem]) =
    child(ws, NsCommon, "source").flatMap(src => party(src, "source").map(src -> _)) match {
      case None => (None, Vector.empty)
      case Some((src, (kind, id))) =>
        val origin = party(src, "assertion-origin")
        val a = AssertionSource(
          sourceKind = kind,
          sourceId = id,
          sourceName = child(src, NsCommon, "source-name").flatMap(text),
          originKind = origin.map(_._1),
          originId = origin.map(_._2),
          originName =
            if (origin.isDefined) child(src, NsCommon, "assertion-origin-name").flatMap(text)
            else None
        )
        val errors = AssertionSource.check("assertionSource", a)
        if (errors.isEmpty) (Some(a), Vector.empty)
        else
          (
            None,
            Vector(
              Problem.of(
                RejectReason.MalformedField,
                s"$recordId common:source: ${errors.map(_.message).mkString("; ")}; dropped"
              )
            )
          )
    }

  def record(bytes: Array[Byte], run: RunContext): Outcome =
    parse(bytes) match {
      case Left(p) => Outcome.Rejected(None, Vector(p))
      case Right(doc) =>
        val rawId = descendants(doc, NsCommon, "orcid-identifier").headOption
          .flatMap(child(_, NsCommon, "path"))
          .flatMap(text)
        rawId match {
          case None =>
            Outcome.Rejected(
              None,
              Vector(Problem.of(RejectReason.MissingKey, "orcid-identifier/path"))
            )
          case Some(raw) =>
            Identifiers.normalizeOrcid(raw) match {
              case Left(why) =>
                Outcome.Rejected(
                  Some(raw),
                  Vector(Problem.of(RejectReason.MissingKey, s"'$raw': $why"))
                )
              case Right(orcid) => works(doc, orcid, run)
            }
        }
    }

  private def works(doc: Document, orcid: String, run: RunContext): Outcome = {
    val results = descendants(doc, NsWork, "work-summary").map(ws => work(ws, orcid, run))
    val ok = results.collect { case Right(r) => r }
    val bad = results.collect { case Left(p) => p }
    val claims = ok.map(_._1.workId).distinct.sortWith((a, b) => Canonical.compareUtf8(a, b) < 0)
    holder(doc, orcid, claims, run) match {
      case Left(p) => Outcome.Rejected(Some(orcid), Vector(p))
      case Right((profile, profileFlags)) =>
        Outcome.Accepted(
          orcid,
          Normalized.empty.copy(
            works = ok.map(_._1),
            venues = ok.flatMap(_._2),
            profiles = Vector(profile),
            flags = profileFlags ++ bad ++ ok.flatMap(_._3)
          )
        )
    }
  }

  /** The record holder's profile with the public names and the holder-to-work claims. */
  private def holder(
      doc: Document,
      orcid: String,
      claims: Vector[String],
      run: RunContext
  ): Either[Problem, (SourceAuthorProfile, Vector[Problem])] = {
    val name = descendants(doc, NsPerson, "name").find(isPublic)
    def nameField(local: String, label: String) =
      Build.optText(label, name.flatMap(child(_, NsPersonal, local)).flatMap(text))
    val (givenNames, givenFlags) = nameField("given-names", "given-names")
    val (family, familyFlags) = nameField("family-name", "family-name")
    val (credit, creditFlags) = nameField("credit-name", "credit-name")
    val others = descendants(doc, NsOtherName, "other-name")
      .filter(isPublic)
      .flatMap(child(_, NsOtherName, "content"))
      .flatMap(text)
      .distinct
    SourceAuthorId
      .orcid(orcid)
      .left
      .map(e => Problem.of(RejectReason.MissingKey, e.message))
      .flatMap { profileId =>
        Build
          .validated(
            SourceAuthorProfile(
              schemaVersion = SchemaVersions.SourceAuthorProfile,
              profileId = profileId,
              source = SourceType.Orcid,
              sourceRecordId = orcid,
              displayName = credit,
              givenNames = givenNames,
              familyName = family,
              nameVariants = others,
              orcid = Some(orcid),
              externalIds = Vector.empty,
              institutionIds = Vector.empty,
              claimedWorkIds = claims,
              provenance = Build.provenance(run, orcid, HadoopPipeline.OrcidSchema)
            )
          )
          .map(_ -> (givenFlags ++ familyFlags ++ creditFlags))
      }
  }

  private def work(
      ws: Element,
      orcid: String,
      run: RunContext
  ): Either[Problem, (WorkRecord, Option[Venue], Vector[Problem])] = {
    val putCode = Option(ws.getAttribute("put-code")).flatMap(_.toLongOption)
    putCode
      .toRight(
        Problem.of(RejectReason.MissingKey, s"work-summary of $orcid without a put-code; dropped")
      )
      .flatMap { pc =>
        WorkId
          .orcidWork(orcid, pc)
          .left
          .map(e => Problem.of(RejectReason.MissingKey, e.message))
          .flatMap { workId =>
            val recordId = s"$orcid/work/$pc"
            val prov = Build.provenance(run, recordId, HadoopPipeline.OrcidSchema)
            val title =
              child(ws, NsWork, "title").flatMap(child(_, NsCommon, "title")).flatMap(text)
            title
              .toRight(Problem.of(RejectReason.MissingField, s"$recordId: title; dropped"))
              .flatMap { t =>
                val ids = externalIds(ws)
                val dois = ids.collect { case ("doi", v, "self") =>
                  v -> Identifiers.normalizeDoi(v)
                }
                val goodDois = dois.collect { case (_, Right(d)) => d }.distinct
                val doiFlags = dois.collect { case (v, Left(why)) =>
                  Problem.of(RejectReason.MalformedIdentifier, s"$recordId doi '$v': $why; dropped")
                }
                val (eids, eidFlags) = Build.exactIds(
                  s"$recordId eid",
                  ExactIdScheme.ScopusEid,
                  ids.collect { case ("eid", v, "self") => v }
                )
                val (assertion, assertionFlags) = assertionSource(ws, recordId)
                val localRaw = ids.collect { case ("source-work-id", v, _) => v }.distinct
                val (localIds, localFlags) = assertion match {
                  case None if localRaw.nonEmpty =>
                    (
                      Vector.empty,
                      Vector(
                        Problem.of(
                          RejectReason.MissingField,
                          s"$recordId: ${localRaw.size} source-work-id value(s) without a valid " +
                            "common:source (their issuer); dropped"
                        )
                      )
                    )
                  case None => (Vector.empty, Vector.empty)
                  case Some(a) =>
                    val checked = localRaw.map(v => SourceLocalWorkId.issuedBy(a, v)).map { l =>
                      val errors = SourceLocalWorkId.check("sourceLocalWorkIds", l)
                      if (errors.isEmpty) Right(l)
                      else
                        Left(
                          Problem.of(
                            RejectReason.MalformedIdentifier,
                            s"$recordId source-work-id '${l.value}': " +
                              s"${errors.map(_.message).mkString("; ")}; dropped"
                          )
                        )
                    }
                    (
                      SourceLocalWorkId.canonical(checked.collect { case Right(l) => l }),
                      checked.collect { case Left(p) => p }
                    )
                }
                val issn = ids.collectFirst {
                  case ("issn", v, "part-of") if Identifiers.isValidIssn(v) => v
                }
                val journal = child(ws, NsWork, "journal-title").flatMap(text)
                val venue = for {
                  i <- issn
                  name <- journal
                  vid <- VenueId.of("issn", i).toOption
                } yield Venue(
                  SchemaVersions.Venue,
                  vid,
                  name,
                  None,
                  Vector(i),
                  Vector(ExternalId(ExternalIdScheme.Issn, i)),
                  prov
                )
                val (venues, venueFlags) = Build.keepValid(venue.toVector)
                val (recordType, typeFlags) =
                  Build.optToken(s"$recordId work:type", child(ws, NsWork, "type").flatMap(text))
                val (venueName, venueNameFlags) =
                  Build.optText(s"$recordId work:journal-title", journal)
                val year = child(ws, NsCommon, "publication-date")
                  .flatMap(child(_, NsCommon, "year"))
                  .flatMap(text)
                  .flatMap(_.toIntOption)
                val w = WorkRecord(
                  schemaVersion = SchemaVersions.WorkRecord,
                  workId = workId,
                  source = SourceType.Orcid,
                  sourceRecordId = recordId,
                  title = t,
                  normalizedTitle = TextNormalization.title(t),
                  year = year,
                  venueId = venues.headOption.map(_.venueId),
                  doi = goodDois.headOption,
                  arxivId = None,
                  authors = Vector.empty,
                  abstractTextRef = None,
                  provenance = prov,
                  recordType = recordType,
                  venueName = venueName,
                  additionalIds = ExactId.canonical(
                    goodDois.drop(1).map(ExactId(ExactIdScheme.Doi, _)) ++ eids
                  ),
                  assertionSource = assertion,
                  sourceLocalWorkIds = localIds
                )
                Build
                  .validated(w)
                  .map(ok =>
                    (
                      ok,
                      venues.headOption,
                      doiFlags ++ venueFlags ++ eidFlags ++ assertionFlags ++ localFlags ++
                        typeFlags ++ venueNameFlags
                    )
                  )
              }
          }
      }
  }
}
