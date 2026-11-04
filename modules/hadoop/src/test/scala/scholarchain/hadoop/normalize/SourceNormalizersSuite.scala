package scholarchain.hadoop.normalize

import java.nio.file.Files

import scholarchain.core.{
  AssertionSource,
  AssertionSourceKind,
  ExactId,
  ExactIdScheme,
  ExternalId,
  ExternalIdScheme,
  SourceLocalWorkId,
  SourceType
}
import scholarchain.hadoop.{Rank, RejectReason, TestSupport}

class SourceNormalizersSuite extends munit.FunSuite {

  def lines(rel: String): Vector[String] =
    TestSupport.readSample(rel).split("\n").toVector.filter(_.trim.nonEmpty)

  def ok(o: Outcome): Normalized = o match {
    case Outcome.Accepted(_, n) => n
    case other => fail(s"expected Accepted, got $other")
  }

  def rejectedReason(o: Outcome): RejectReason = o match {
    case Outcome.Rejected(_, ps) => ps.head.reason
    case other => fail(s"expected Rejected, got $other")
  }

  val oa = TestSupport.run(SourceType.OpenAlex)
  val cr = TestSupport.run(SourceType.Crossref)
  val or = TestSupport.run(SourceType.Orcid)

  test("OpenAlex works sample: 3 works, ordered authorships, nullable author IDs, venues, topics") {
    val ns = lines("openalex/works-sample.jsonl").map(l => ok(OpenAlexNormalizer.workLine(l, oa)))
    val works = ns.flatMap(_.works)
    assertEquals(
      works.map(_.workId),
      Vector(
        "work:openalex:W3137759927",
        "work:openalex:W3147033813",
        "work:openalex:W3200477342"
      )
    )
    assertEquals(works.map(_.authors.size), Vector(26, 3, 1))
    assertEquals(
      works.map(_.doi),
      Vector(Some("10.1145/2491245"), Some("10.1145/3447741"), Some("10.1145/3453932"))
    )
    val spanner = works.head
    assertEquals(spanner.authors(1).sourceAuthorId, None, "author.id is null in the source")
    assertEquals(
      spanner.authors(0).sourceAuthorId,
      Some(ExternalId(ExternalIdScheme.OpenAlex, "A5083116750"))
    )
    assertEquals(spanner.authors(0).affiliationIds, Vector("institution:openalex:I1291425158"))
    assertEquals(
      spanner.authors(10).rawName,
      "Wilson Hsieh",
      "raw_author_name is preferred over display_name"
    )
    assertEquals(spanner.venueId, Some("venue:openalex:S193109227"))
    assertEquals(
      ns.flatMap(_.topics).map(_.topicId),
      Vector("topic:openalex:T10772", "topic:openalex:T10734", "topic:openalex:T10430")
    )
    assertEquals(ns.flatMap(_.venues).map(_.venueId).distinct.size, 2)
    val insts = ns.flatMap(_.institutions)
    assert(insts.forall(_._2 == Rank.Reference))
    assertEquals(
      insts.map(_._1.institutionId).distinct.sorted,
      Vector(
        "institution:openalex:I120250893",
        "institution:openalex:I1291425158",
        "institution:openalex:I4210144839",
        "institution:openalex:I99682543"
      )
    )
    assertEquals(works(1).authors.head.orcid, Some("0000-0002-2814-962X"))
  }

  test("OpenAlex works keep type, venue name, biblio, topic IDs, and the MAG ID") {
    val works =
      lines("openalex/works-sample.jsonl").map(l =>
        ok(OpenAlexNormalizer.workLine(l, oa)).works.head
      )
    val cacm = works(1)
    assertEquals(cacm.recordType, Some("article"))
    assertEquals(cacm.venueName, Some("Communications of the ACM"))
    assertEquals((cacm.volume, cacm.issue, cacm.pages), (Some("64"), Some("4"), Some("96-101")))
    assertEquals(cacm.topicIds, Vector("topic:openalex:T10734"))
    assertEquals(cacm.additionalIds, Vector(ExactId(ExactIdScheme.Mag, "3147033813")))
    assertEquals(works.map(_.topicIds.size), Vector(1, 1, 1))
  }

  test("OpenAlex topics: primary topic first, then topics[], distinct") {
    val line =
      """{"id":"https://openalex.org/W9","title":"T","primary_topic":{"id":"https://openalex.org/T2","display_name":"Two"},"topics":[{"id":"https://openalex.org/T1","display_name":"One"},{"id":"https://openalex.org/T2","display_name":"Two"}]}"""
    val n = ok(OpenAlexNormalizer.workLine(line, oa))
    assertEquals(n.works.head.topicIds, Vector("topic:openalex:T2", "topic:openalex:T1"))
    assertEquals(
      n.topics.map(_.topicId).distinct.sorted,
      Vector("topic:openalex:T1", "topic:openalex:T2")
    )
  }

  test("OpenAlex institutions are primary records with ROR; authors become source profiles") {
    val n =
      ok(OpenAlexNormalizer.institutionLine(lines("openalex/institutions-sample.jsonl").head, oa))
    val (inst, rank) = n.institutions.head
    assertEquals(rank, Rank.Primary)
    assertEquals(inst.rorId, Some("00e5k0821"))
    assertEquals(inst.countryCode, Some("AE"))
    val a = ok(OpenAlexNormalizer.authorLine(lines("openalex/authors-sample.jsonl").head, oa))
    val p = a.profiles.head
    assertEquals(p.profileId, "source-author:openalex:A5113870699")
    assertEquals(p.source, SourceType.OpenAlex)
    assertEquals(p.displayName, Some("Christina Pöpper"))
    assertEquals(p.orcid, Some("0000-0002-2814-962X"))
    assertEquals(p.externalIds, Vector(ExternalId(ExternalIdScheme.OpenAlex, "A5113870699")))
    assertEquals(p.institutionIds, Vector("institution:openalex:I120250893"))
    assertEquals(p.nameVariants.size, 6)
    assertEquals(p.claimedWorkIds, Vector.empty)
    assertEquals(a.institutions.map(_._2), Vector(Rank.Reference))
    assertEquals(a.flags, Vector.empty)
    assertEquals(
      rejectedReason(OpenAlexNormalizer.authorLine("""{"id":"https://openalex.org/W1"}""", oa)),
      RejectReason.MissingKey
    )
  }

  test("Crossref sample: DOI work IDs, title plus subtitle, ISSN venues, affiliation strings") {
    val ns = lines("crossref/works-sample.jsonl").map(l => ok(CrossrefNormalizer.workLine(l, cr)))
    val works = ns.flatMap(_.works)
    assertEquals(
      works.map(_.workId),
      Vector(
        "work:doi:10.1145/2491245",
        "work:doi:10.1145/3447741",
        "work:doi:10.1145/3453932"
      )
    )
    assertEquals(works.head.title, "Spanner: Google's Globally Distributed Database")
    assertEquals(works(2).title, "Software development in disruptive times")
    assertEquals(works.map(_.year), Vector(Some(2013), Some(2021), Some(2021)))
    assertEquals(
      works.map(_.venueId),
      Vector(
        Some("venue:issn:0734-2071"),
        Some("venue:issn:0001-0782"),
        Some("venue:issn:0001-0782")
      )
    )
    assertEquals(
      works(1).authors.map(_.rawName),
      Vector("Christina Pöpper", "Michail Maniatakos", "Roberto Di Pietro")
    )
    assertEquals(works(1).authors(2).rawAffiliations, Vector("HBKU-CSE, Doha-Qatar"))
    assertEquals(ns.head.venues.head.issns, Vector("0734-2071", "1557-7333"))
    assertEquals(works.map(_.recordType), Vector.fill(3)(Some("journal-article")))
    assertEquals(works(1).venueName, Some("Communications of the ACM"))
    assertEquals(
      (works(1).volume, works(1).issue, works(1).pages),
      (Some("64"), Some("4"), Some("96-101"))
    )
  }

  def orcidFile(name: String) = Files.readAllBytes(TestSupport.sample(s"orcid/$name.xml"))

  test("ORCID samples: 2, 1, and 0 work summaries, and one holder profile each, no flags") {
    val norm = new OrcidNormalizer
    val maniatakos = ok(norm.record(orcidFile("0000-0001-6899-0651"), or))
    assertEquals(
      maniatakos.works.map(_.workId),
      Vector(
        "work:orcid-work:0000-0001-6899-0651/work/94865737",
        "work:orcid-work:0000-0001-6899-0651/work/107628996"
      )
    )
    assert(maniatakos.works.forall(_.doi.contains("10.1145/3447741")))
    assert(maniatakos.works.forall(_.authors.isEmpty))
    assertEquals(maniatakos.works.map(_.venueId), Vector(None, Some("venue:issn:1557-7317")))
    assertEquals(maniatakos.works.map(_.recordType), Vector.fill(2)(Some("journal-article")))
    assertEquals(
      maniatakos.works.map(_.venueName),
      Vector.fill(2)(Some("Communications of the ACM"))
    )
    assert(
      maniatakos.works.forall(
        _.additionalIds == Vector(ExactId(ExactIdScheme.ScopusEid, "2-s2.0-85103197452"))
      )
    )
    assertEquals(maniatakos.flags, Vector.empty)
    val holder = maniatakos.profiles.head
    assertEquals(holder.profileId, "source-author:orcid:0000-0001-6899-0651")
    assertEquals((holder.givenNames, holder.familyName), (Some("Michail"), Some("Maniatakos")))
    assertEquals(holder.nameVariants, Vector("Mihalis Maniatakos"))
    assertEquals(holder.orcid, Some("0000-0001-6899-0651"))
    assertEquals(
      holder.claimedWorkIds,
      Vector(
        "work:orcid-work:0000-0001-6899-0651/work/107628996",
        "work:orcid-work:0000-0001-6899-0651/work/94865737"
      ),
      "claims are sorted by UTF-8 bytes"
    )
    assertEquals(ok(norm.record(orcidFile("0000-0002-2814-962X"), or)).works.size, 1)
    val varajao = ok(norm.record(orcidFile("0000-0002-4303-3908"), or))
    assertEquals(varajao.works.size, 0)
    assertEquals(varajao.flags, Vector.empty)
    assertEquals(varajao.profiles.map(_.claimedWorkIds), Vector(Vector.empty))
    assertEquals(varajao.profiles.head.familyName, Some("Varajão"))
  }

  test("ORCID assertion source and source-work-id: summary level only, issued by common:source") {
    val norm = new OrcidNormalizer
    val ws = ok(norm.record(orcidFile("0000-0001-6899-0651"), or)).works
    val (nyu, scopus) = (ws(0), ws(1))
    assertEquals(
      nyu.assertionSource,
      Some(
        AssertionSource(
          AssertionSourceKind.Client,
          "APP-VOZZVWHV1K15COEN",
          Some("New York University Scholars"),
          None,
          None,
          None
        )
      )
    )
    assertEquals(
      nyu.sourceLocalWorkIds,
      Vector(
        SourceLocalWorkId(
          AssertionSourceKind.Client,
          "APP-VOZZVWHV1K15COEN",
          "06b25b01-8677-4af5-b139-0de6dd3c6eb9"
        )
      )
    )
    assert(nyu.additionalIds.forall(_.scheme != ExactIdScheme.Doi), "never in additionalIds")
    // The Scopus summary has no source-work-id of its own; the group-level one is not taken.
    assertEquals(scopus.sourceLocalWorkIds, Vector.empty)
    assertEquals(
      scopus.assertionSource,
      Some(
        AssertionSource(
          AssertionSourceKind.Client,
          "0000-0002-5982-8983",
          Some("Scopus - Elsevier"),
          Some(AssertionSourceKind.Orcid),
          Some("0000-0001-6899-0651"),
          Some("Michail Maniatakos")
        )
      )
    )
    assert(ws.forall(w => scholarchain.core.WorkRecord.contract.validate(w).isEmpty))
  }

  test("ORCID: a source-work-id without a common:source is dropped and flagged, work kept") {
    val xml =
      """<record:record xmlns:record="http://www.orcid.org/ns/record" xmlns:common="http://www.orcid.org/ns/common" xmlns:activities="http://www.orcid.org/ns/activities" xmlns:work="http://www.orcid.org/ns/work">
        |<common:orcid-identifier><common:path>0000-0001-6899-0651</common:path></common:orcid-identifier>
        |<activities:activities-summary><activities:works><activities:group>
        |<work:work-summary put-code="5"><work:title><common:title>T</common:title></work:title>
        |<common:external-ids><common:external-id><common:external-id-type>source-work-id</common:external-id-type>
        |<common:external-id-value>abc</common:external-id-value><common:external-id-relationship>self</common:external-id-relationship>
        |</common:external-id></common:external-ids></work:work-summary>
        |</activities:group></activities:works></activities:activities-summary></record:record>""".stripMargin
    val n = ok((new OrcidNormalizer).record(xml.getBytes("UTF-8"), or))
    assertEquals(n.works.size, 1)
    assertEquals(n.works.head.sourceLocalWorkIds, Vector.empty)
    assertEquals(n.works.head.assertionSource, None)
    assertEquals(n.flags.map(_.reason), Vector(RejectReason.MissingField))
    assertEquals(n.profiles.head.claimedWorkIds, Vector(n.works.head.workId))
  }

  test("ORCID: DOCTYPE (XXE) and malformed XML are rejected") {
    val norm = new OrcidNormalizer
    val xxe =
      """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]><r>&x;</r>"""
    assertEquals(rejectedReason(norm.record(xxe.getBytes, or)), RejectReason.MalformedXml)
    assertEquals(rejectedReason(norm.record("<r>".getBytes, or)), RejectReason.MalformedXml)
  }

  test("JSON sources: malformed JSON, non-object lines, missing and malformed keys are rejected") {
    assertEquals(
      rejectedReason(OpenAlexNormalizer.workLine("{not json", oa)),
      RejectReason.MalformedJson
    )
    assertEquals(
      rejectedReason(OpenAlexNormalizer.workLine("[1,2]", oa)),
      RejectReason.MalformedJson
    )
    assertEquals(
      rejectedReason(OpenAlexNormalizer.workLine("""{"title":"x"}""", oa)),
      RejectReason.MissingKey
    )
    assertEquals(
      rejectedReason(
        OpenAlexNormalizer.workLine("""{"id":"https://openalex.org/X1","title":"x"}""", oa)
      ),
      RejectReason.MissingKey
    )
    assertEquals(
      rejectedReason(OpenAlexNormalizer.workLine("""{"id":"https://openalex.org/W1"}""", oa)),
      RejectReason.MissingField
    )
    assertEquals(
      rejectedReason(CrossrefNormalizer.workLine("""{"DOI":"not-a-doi","title":["x"]}""", cr)),
      RejectReason.MissingKey
    )
    assertEquals(
      rejectedReason(
        CrossrefNormalizer.workLine("""{"DOI":"10.1234/x","title":["x"],"author":[{}]}""", cr)
      ),
      RejectReason.MissingField
    )
  }

  test("JSON sources: malformed optional identifiers are dropped and flagged, the record is kept") {
    val line =
      """{"id":"https://openalex.org/W9","title":"T","doi":"not a doi","authorships":[{"author":{"id":"https://openalex.org/X5","display_name":"N","orcid":"https://orcid.org/0000-0000-0000-0000"},"institutions":[]}]}"""
    val n = ok(OpenAlexNormalizer.workLine(line, oa))
    assertEquals(n.works.head.doi, None)
    assertEquals(n.works.head.authors.head.sourceAuthorId, None)
    assertEquals(n.works.head.authors.head.orcid, None)
    assertEquals(n.flags.size, 3)
    assert(n.flags.forall(_.reason == RejectReason.MalformedIdentifier))
  }
}
