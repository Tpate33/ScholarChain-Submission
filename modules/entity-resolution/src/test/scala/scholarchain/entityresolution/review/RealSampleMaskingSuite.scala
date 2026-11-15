package scholarchain.entityresolution.review

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

import scholarchain.core.*
import scholarchain.entityresolution.Samples
import scholarchain.entityresolution.Samples.A
import scholarchain.entityresolution.policy.PolicyFixtures.repoRoot

/**
 * Real-data masking check (ER-P5-06): the DS-P5-01 OpenAlex and Crossref sample records under
 * `datasets/samples/er-candidates/{heldout,dev}` are read as DATA by a minimal test-only adapter
 * (names as written, affiliations, institution IDs, ORCIDs, author IDs, DOIs, titles, venues), the
 * masked blind view is built over pairs of their endpoints, and no DBLP pid or homonym pattern may
 * survive. The dblp records do not exist yet (the full dblp download is not authorized), so no dblp
 * record is read here. Counts are printed with the prefix `[er-mask-real]`. Pairs are formed only
 * to exercise the view; they are not candidates and carry no label.
 */
class RealSampleMaskingSuite extends munit.FunSuite {

  private val base = repoRoot.resolve("datasets/samples/er-candidates")
  private val kinds = Vector("openalex-works", "openalex-authors", "crossref-works")

  private def files(kind: String): Vector[Path] =
    Vector("heldout", "dev").flatMap { split =>
      val d = base.resolve(split).resolve(kind)
      if (!Files.isDirectory(d)) Vector.empty
      else {
        val s = Files.list(d)
        try s.iterator.asScala.toVector.filter(_.toString.endsWith(".json")).sortBy(_.toString)
        finally s.close()
      }
    }

  private def str(v: ujson.Value): Option[String] = v match {
    case ujson.Str(s) if s.trim.nonEmpty => Some(s)
    case _ => None
  }
  private def field(v: ujson.Value, k: String): ujson.Value = v match {
    case ujson.Obj(m) => m.getOrElse(k, ujson.Null)
    case _ => ujson.Null
  }
  private def arr(v: ujson.Value): Vector[ujson.Value] = v match {
    case ujson.Arr(xs) => xs.toVector
    case _ => Vector.empty
  }
  private def after(prefix: String)(s: String): String = s.stripPrefix(prefix)

  private def validWork(id: String): Boolean = StableId.validate(IdKind.Work, id).isRight

  private def openAlexWork(j: ujson.Value): Option[WorkRecord] =
    for {
      w <- str(field(j, "id")).map(after("https://openalex.org/"))
      id = s"work:openalex:$w" if validWork(id)
    } yield {
      val authors = arr(field(j, "authorships")).map { a =>
        val au = field(a, "author")
        A(
          str(field(a, "raw_author_name")).orElse(str(field(au, "display_name"))).getOrElse("?"),
          str(field(au, "orcid")).map(after("https://orcid.org/")),
          arr(field(a, "raw_affiliation_strings")).flatMap(str),
          arr(field(a, "institutions"))
            .flatMap(i => str(field(i, "id")))
            .map(i => "institution:openalex:" + after("https://openalex.org/")(i))
            .distinct,
          str(field(au, "id"))
            .map(after("https://openalex.org/"))
            .map(ExternalId(ExternalIdScheme.OpenAlex, _))
        )
      }
      Samples.buildWork(
        SourceType.OpenAlex,
        id,
        w,
        str(field(j, "title")).getOrElse(""),
        field(j, "publication_year").numOpt.map(_.toInt),
        None,
        str(field(field(field(j, "primary_location"), "source"), "display_name")),
        str(field(j, "doi")).map(after("https://doi.org/")).map(_.toLowerCase),
        authors
      )
    }

  private def crossrefWork(j: ujson.Value): Option[WorkRecord] = {
    val item = arr(field(field(j, "message"), "items")).headOption.getOrElse(field(j, "message"))
    for {
      doi <- str(field(item, "DOI")).map(_.toLowerCase)
      id = s"work:doi:$doi" if validWork(id)
    } yield Samples.buildWork(
      SourceType.Crossref,
      id,
      doi,
      arr(field(item, "title")).flatMap(str).headOption.getOrElse(""),
      arr(
        arr(field(field(item, "issued"), "date-parts")).headOption.getOrElse(ujson.Arr())
      ).headOption
        .flatMap(_.numOpt)
        .map(_.toInt),
      None,
      arr(field(item, "container-title")).flatMap(str).headOption,
      Some(doi),
      arr(field(item, "author")).map { a =>
        A(
          str(field(a, "name"))
            .getOrElse(
              Vector(str(field(a, "given")), str(field(a, "family"))).flatten.mkString(" ")
            ),
          str(field(a, "ORCID")).map(after("https://orcid.org/")).map(after("http://orcid.org/")),
          arr(field(a, "affiliation")).flatMap(x => str(field(x, "name")))
        )
      }
    )
  }

  private def openAlexAuthor(j: ujson.Value): Option[SourceAuthorProfile] =
    str(field(j, "id")).map(after("https://openalex.org/")).map { a =>
      SourceAuthorProfile(
        schemaVersion = SchemaVersions.SourceAuthorProfile,
        profileId = s"source-author:openalex:$a",
        source = SourceType.OpenAlex,
        sourceRecordId = a,
        displayName = str(field(j, "display_name")),
        givenNames = None,
        familyName = None,
        nameVariants = arr(field(j, "display_name_alternatives")).flatMap(str).distinct,
        orcid = str(field(j, "orcid")).map(after("https://orcid.org/")),
        externalIds = Vector.empty,
        institutionIds = arr(field(j, "last_known_institutions"))
          .flatMap(i => str(field(i, "id")))
          .map(i => "institution:openalex:" + after("https://openalex.org/")(i))
          .distinct,
        claimedWorkIds = Vector.empty,
        provenance = Samples.prov(SourceType.OpenAlex, a)
      )
    }

  private def leaves(v: ujson.Value): Vector[String] = v match {
    case ujson.Obj(m) => m.values.toVector.flatMap(leaves)
    case ujson.Arr(xs) => xs.toVector.flatMap(leaves)
    case ujson.Str(s) => Vector(s)
    case _ => Vector.empty
  }

  /** Every pid or person-link pattern a raw string carries (before masking). */
  private val PidPatterns = Vector(
    "(?i)dblp[\\s_-]?pid".r,
    "(?i)dblp[^\\s\"]*(?:/pid/|/pers/|homepages/)".r
  )

  test("real DS-P5-01 OpenAlex and Crossref samples: no pid or homonym pattern survives") {
    val parsed = kinds.map(k =>
      k -> files(k).map(p => ujson.read(Files.readString(p, StandardCharsets.UTF_8)))
    )
    val byKind = parsed.toMap
    assume(byKind.values.exists(_.nonEmpty), s"no DS-P5-01 sample files under $base")
    val works =
      byKind("openalex-works").flatMap(openAlexWork) ++ byKind("crossref-works").flatMap(
        crossrefWork
      )
    val profiles = byKind("openalex-authors").flatMap(openAlexAuthor)
    val skipped =
      byKind("openalex-works").size + byKind("crossref-works").size - works.size
    // Raw-record counts before masking (every string leaf of every file).
    val raw = parsed.flatMap(_._2).flatMap(leaves)
    val rawPid = raw.count(s => PidPatterns.exists(_.findFirstIn(s).isDefined))
    val rawNames =
      works.flatMap(_.authors.map(_.rawName)) ++
        profiles.flatMap(p => p.displayName.toVector ++ p.nameVariants)
    val rawHomonym = rawNames.count(n => DblpMasking.HomonymSuffix.findFirstIn(n).isDefined)
    val rawChanged = raw.count(s => DblpMasking.text(s) != s)
    // Pairs that exercise every endpoint at least once (consecutive in sorted ID order).
    val evidence = BlindView.index(works, profiles)
    def pairUp(kind: String, ids: Vector[String]): Vector[BlindItem] =
      ids.sorted
        .sliding(2)
        .collect { case Vector(a, b) =>
          val Vector(x, y) = Vector(a, b).sortWith(Canonical.compareUtf8(_, _) < 0)
          BlindItem(GoldMembership.memberId(kind, x, y).toOption.get, kind, x, y)
        }
        .toVector
    val researcherIds = evidence.values.filter(_.endpointKind != "work").map(_.endpointId).toVector
    val workIds = evidence.values.filter(_.endpointKind == "work").map(_.endpointId).toVector
    val items = pairUp("researcher", researcherIds) ++ pairUp("work", workIds)
    val view =
      BlindView.build(items, evidence, "real-check").fold(e => fail(e.mkString("; ")), identity)
    val survivors = DblpMaskingSuite.survivors(view)
    val masked = view.count(_.maskingNotes.nonEmpty)
    // View v5 shows OpenAlex endpoints only as per-row masked references, so coverage is counted
    // over the raw endpoint IDs of the rows built.
    val endpointsShown = items.flatMap(i => Vector(i.sourceIdA, i.sourceIdB)).toSet
    // m9 (view v5): every OpenAlex A and W ID of the real records, as raw values.
    val clusterIds: Set[String] =
      (works.map(_.sourceRecordId) ++
        works.flatMap(_.authors.flatMap(_.sourceAuthorId.map(_.value))) ++
        profiles.map(_.sourceRecordId))
        .filter(v => OpenAlexMasking.ClusterId.matches(v))
        .map(_.toUpperCase(java.util.Locale.ROOT))
        .toSet
    val idKeys = Set("memberId", "sourceIdA", "sourceIdB", "endpointId")
    def displayed(v: ujson.Value): Vector[String] = v match {
      case ujson.Obj(m) =>
        m.toVector.flatMap { case (k, x) => if (idKeys(k)) Vector() else displayed(x) }
      case ujson.Arr(xs) => xs.toVector.flatMap(displayed)
      case ujson.Str(s) => Vector(s)
      case _ => Vector.empty
    }
    val shownStrings = view.flatMap(r => displayed(ujson.read(upickle.default.write(r))))
    val clusterSurvivors = shownStrings.filter(s =>
      OpenAlexMasking.ClusterId.findAllIn(s).exists(x => clusterIds(x.toUpperCase))
    )
    val shownIds = view.flatMap(r => Vector(r.sourceIdA, r.sourceIdB))
    val openAlexRows = view.count(_.maskingNotes.contains(OpenAlexMasking.Note))
    println(
      s"[er-mask-real] files openalex-works=${byKind("openalex-works").size} " +
        s"openalex-authors=${byKind("openalex-authors").size} " +
        s"crossref-works=${byKind("crossref-works").size} dblp=0 (dblp records not downloaded)"
    )
    println(
      s"[er-mask-real] records works=${works.size} profiles=${profiles.size} skipped=$skipped " +
        s"endpoints=${evidence.size} endpoints-shown=${endpointsShown.size} rows=${view.size}"
    )
    println(
      s"[er-mask-real] before masking: string-leaves=${raw.size} pid-pattern-leaves=$rawPid " +
        s"leaves-changed-by-text-rule=$rawChanged person-names=${rawNames.size} " +
        s"names-with-4-digit-suffix=$rawHomonym"
    )
    println(
      s"[er-mask-real] after masking: rows-with-masking-notes=$masked survivors=${survivors.size}"
    )
    println(
      s"[er-mask-real] openalex (view v5): distinct-A/W-ids=${clusterIds.size} " +
        s"rows-with-openalex-note=$openAlexRows surviving-A/W-strings=${clusterSurvivors.size}"
    )
    assertEquals(survivors, Vector.empty)
    assertEquals(clusterSurvivors, Vector.empty, "an OpenAlex A or W ID survived masking")
    assert(clusterIds.nonEmpty, "the real records carry OpenAlex IDs")
    assert(
      !shownIds.exists(s => clusterIds.exists(id => s.toUpperCase.contains(id))),
      "an OpenAlex ID is shown as a label reference"
    )
    assertEquals(endpointsShown.size, evidence.size, "every endpoint appears in some row")
    assert(works.nonEmpty && profiles.nonEmpty)
    // Non-DBLP evidence is still there after masking.
    val shown = view.flatMap(r => Vector(r.endpointA, r.endpointB))
    assert(shown.exists(_.identifiers.exists(_.startsWith("orcid:"))))
    assert(shown.exists(_.affiliations.nonEmpty))
    assert(shown.exists(_.work.exists(_.identifiers.exists(_.startsWith("doi:")))))
  }
}
