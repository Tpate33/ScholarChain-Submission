package scholarchain.hadoop.dblp

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

import scholarchain.core.{MentionId, SourceType, WorkRecord}
import scholarchain.hadoop.normalize.Outcome
import scholarchain.hadoop.{DblpSettings, RejectReason, TestSupport}

class DblpMappingSuite extends munit.FunSuite {

  val parser = new DblpParser(StandardCharsets.UTF_8)
  val run = TestSupport.run(SourceType.Dblp)
  val all =
    DblpSettings(Set("article", "inproceedings"), None, None, StandardCharsets.UTF_8, 1 << 20)

  def records(xml: String): Vector[DblpRecord] = {
    val b = xml.getBytes(StandardCharsets.UTF_8)
    val s = new DblpScanner(new ByteArrayInputStream(b), 0L, 0L, b.length.toLong, 1 << 20)
    Iterator.continually(s.next()).takeWhile(_.isDefined).map(_.get).toVector.map { r =>
      parser.parse(r.bytes).fold(e => fail(e), identity)
    }
  }

  def accepted(o: Outcome): (WorkRecord, Outcome.Accepted) = o match {
    case a @ Outcome.Accepted(_, n) => (n.works.head, a)
    case other => fail(s"expected Accepted, got $other")
  }

  lazy val sample: Vector[DblpRecord] = records(TestSupport.readSample("dblp/dblp-sample.xml"))

  test("the committed DBLP sample has 4 publication records with entities resolved") {
    assertEquals(
      sample.map(_.key.get),
      Vector(
        "journals/tocs/CorbettDEFFFGGHHHKKLLMMNQRRSSTWW13",
        "journals/cacm/PopperMP21",
        "journals/cacm/Varajao21",
        "conf/osdi/ChangDGHWBCFG06"
      )
    )
    assertEquals(
      sample(1).authors.head,
      DblpAuthor("Christina Pöpper", Some("0000-0002-2814-962X"))
    )
    assertEquals(sample(2).authors.head.name, "João Varajão")
    assertEquals(sample.map(_.authors.size), Vector(26, 3, 1, 9))
  }

  test("sample works map to valid WorkRecords with ordered mentions, DOI, venue, provenance") {
    val works = sample.map(r => accepted(DblpMapping.map(r, all, run))._1)
    val spanner = works(0)
    assertEquals(spanner.workId, "work:dblp:journals/tocs/CorbettDEFFFGGHHHKKLLMMNQRRSSTWW13")
    assertEquals(spanner.doi, Some("10.1145/2491245"))
    assertEquals(spanner.year, Some(2013))
    assertEquals(spanner.authors.map(_.position), (0 until 26).toVector)
    assertEquals(spanner.authors(4).rawName, "Christopher Frost 0001")
    assertEquals(spanner.authors(4).normalizedName, "christopher frost 0001")
    assertEquals(spanner.authors(4).mentionId, MentionId.of(spanner.workId, 4).toOption.get)
    assertEquals(spanner.normalizedTitle, "spanner: google's globally distributed database")
    assertEquals(spanner.provenance.sourceSchemaVersion, "dblp-raw/1.0.0")
    assertEquals(spanner.provenance.snapshot, "dblp-2026-10-03")
    assertEquals(spanner.provenance.sourceRecordId, spanner.sourceRecordId)
    assertEquals(
      works(1).authors.map(_.orcid),
      Vector(
        Some("0000-0002-2814-962X"),
        Some("0000-0001-6899-0651"),
        Some("0000-0003-1909-0336")
      )
    )
    val bigtable = works(3)
    assertEquals(bigtable.doi, None)
    assertEquals(bigtable.venueId, Some("venue:dblp:conf/osdi/2006"))
    assert(bigtable.title.contains("(Awarded Best Paper!)"), "editorial notes are kept verbatim")
    assertEquals(works.take(3).map(_.venueId), Vector(None, None, None))
  }

  test("work-record v3 fields: record type, venue name, volume, issue, pages, ee links") {
    val works = sample.map(r => accepted(DblpMapping.map(r, all, run))._1)
    assertEquals(
      works.map(_.recordType),
      Vector(Some("article"), Some("article"), Some("article"), Some("inproceedings"))
    )
    // journal articles keep their venue name even though no Venue record exists (no crossref)
    assertEquals(
      works.map(_.venueName),
      Vector(
        Some("ACM Trans. Comput. Syst."),
        Some("Commun. ACM"),
        Some("Commun. ACM"),
        Some("OSDI")
      )
    )
    val spanner = works(0)
    assertEquals((spanner.volume, spanner.issue, spanner.pages), (Some("31"), Some("3"), Some("8")))
    assertEquals(spanner.eeLinks, Vector("https://doi.org/10.1145/2491245"))
    assertEquals(spanner.urls, Vector.empty)
    assertEquals(spanner.additionalIds, Vector.empty)
    assertEquals(works(3).volume, None)
    assertEquals(works(3).pages, Some("205-218"))
    assertEquals(
      works(3).eeLinks,
      Vector("http://www.usenix.org/events/osdi06/tech/chang.html")
    )
  }

  test("a repeated author name keeps both mentions with distinct positions and IDs") {
    val r = records(
      """<dblp><article key="journals/x/Rep">
        |<author>Wei Wang</author><author>Li Chen</author><author>Wei Wang</author>
        |<title>Same name twice.</title><year>2020</year><journal>J. X</journal>
        |</article></dblp>""".stripMargin
    ).head
    assertEquals(r.authors.map(_.name), Vector("Wei Wang", "Li Chen", "Wei Wang"))
    val (w, _) = accepted(DblpMapping.map(r, all, run))
    assertEquals(w.authors.map(_.rawName), Vector("Wei Wang", "Li Chen", "Wei Wang"))
    assertEquals(w.authors.map(_.position), Vector(0, 1, 2))
    assertEquals(w.authors.map(_.mentionId).distinct.size, 3)
    val pairs = scholarchain.hadoop.jobs.PairExpansion.iterator(w).map(_.toOption.get).toVector
    assertEquals(pairs.size, 3, "the pair of the two Wei Wang mentions is kept")
    assert(pairs.exists(p => Set(p.positionA, p.positionB) == Set(0, 2)))
  }

  test("a record missing optional fields (year, ee, url, venue, volume, pages) is accepted") {
    val r = records(
      """<dblp><article key="journals/x/Bare"><author>Solo Author</author>
        |<title>Nothing optional.</title></article></dblp>""".stripMargin
    ).head
    val (w, a) = accepted(DblpMapping.map(r, all, run))
    assertEquals(w.year, None)
    assertEquals(w.doi, None)
    assertEquals(w.arxivId, None)
    assertEquals(w.venueId, None)
    assertEquals(w.venueName, None)
    assertEquals((w.volume, w.issue, w.pages), (None, None, None))
    assertEquals((w.eeLinks, w.urls), (Vector.empty, Vector.empty))
    assertEquals(w.recordType, Some("article"))
    assertEquals(a.out.flags, Vector.empty)
    assertEquals(scholarchain.core.WorkRecord.contract.validate(w), Vector.empty)
  }

  test("a bad ee or url is dropped and flagged, duplicates are removed, and the work is kept") {
    val r = records(
      """<dblp><article key="journals/x/Links"><author>A B</author><title>Links.</title>
        |<ee>https://doi.org/10.1234/x</ee><ee>https://example.org/a b</ee>
        |<ee>https://doi.org/10.1234/x</ee><url>db/journals/x/x1.html#Links</url>
        |<url>db/journals/x/x1.html#Links</url></article></dblp>""".stripMargin
    ).head
    val (w, a) = accepted(DblpMapping.map(r, all, run))
    assertEquals(w.eeLinks, Vector("https://doi.org/10.1234/x"))
    assertEquals(w.urls, Vector("db/journals/x/x1.html#Links"))
    assertEquals(w.doi, Some("10.1234/x"))
    assertEquals(a.out.flags.map(_.reason), Vector(RejectReason.MalformedField))
    assert(a.out.flags.head.detail.head.contains("https://example.org/a b"))
  }

  test("year and venue selections filter, never reject; non-publication records are skipped") {
    val years = all.copy(years = Some((2015, 2024)))
    assertEquals(
      sample.map(r => DblpMapping.map(r, years, run).isInstanceOf[Outcome.Accepted]),
      Vector(false, true, true, false)
    )
    assert(DblpMapping.map(sample(0), years, run).isInstanceOf[Outcome.Filtered])
    val cacm = all.copy(venues = Some(Set("Commun. ACM")))
    assertEquals(
      sample.map(r => DblpMapping.map(r, cacm, run).isInstanceOf[Outcome.Accepted]),
      Vector(false, true, true, false)
    )
    val onlyInproc = all.copy(recordTypes = Set("inproceedings"))
    assertEquals(
      DblpMapping.map(sample(0), onlyInproc, run),
      Outcome.Filtered(Some(sample(0).key.get), "record-type")
    )
    val www = records(
      """<dblp><www key="homepages/x/y"><author>A</author><title>Home Page</title></www></dblp>"""
    )
    assertEquals(DblpMapping.map(www.head, all, run), Outcome.Skipped("non-publication:www"))
  }

  test("missing key, malformed year, missing title, and empty author name are rejected") {
    val rs = records(
      """<dblp>
        |<article><title>No key.</title></article>
        |<article key="journals/x/Y"><title>Bad year.</title><year>20x1</year></article>
        |<article key="journals/x/Z"><year>2020</year></article>
        |<article key="journals/x/W"><author></author><title>T.</title></article>
        |</dblp>""".stripMargin
    )
    val reasons = rs.map(r => DblpMapping.map(r, all, run)).map {
      case Outcome.Rejected(_, ps) => ps.head.reason
      case other => fail(s"expected Rejected, got $other")
    }
    assertEquals(
      reasons,
      Vector(
        RejectReason.MissingKey,
        RejectReason.MalformedField,
        RejectReason.MissingField,
        RejectReason.MissingField
      )
    )
  }

  test(
    "invalid ORCID is dropped and flagged; extra DOIs go to additionalIds (flagged); arXiv is read"
  ) {
    val r = records(
      """<dblp><article key="journals/x/Q">
        |<author orcid="0000-0002-2814-9620">Bad Checksum</author>
        |<author orcid="https://orcid.org/0000-0002-2814-962X">Uri Form</author>
        |<title>T.</title><year>2020</year>
        |<ee>https://doi.org/10.1234/One</ee><ee>https://doi.org/10.1234/two</ee>
        |<ee>https://arxiv.org/abs/2101.00001</ee>
        |</article></dblp>""".stripMargin
    ).head
    val (w, a) = accepted(DblpMapping.map(r, all, run))
    assertEquals(w.authors.map(_.orcid), Vector(None, Some("0000-0002-2814-962X")))
    assertEquals(w.doi, Some("10.1234/one"))
    assertEquals(
      w.additionalIds,
      Vector(scholarchain.core.ExactId(scholarchain.core.ExactIdScheme.Doi, "10.1234/two"))
    )
    assertEquals(w.arxivId, Some("2101.00001"))
    assertEquals(
      a.out.flags.map(_.reason).toSet,
      Set(RejectReason.MalformedIdentifier, RejectReason.Conflict)
    )
  }

  test("parser: undeclared entity and an embedded DOCTYPE are malformed, never guessed or loaded") {
    assert(parser.parse("""<article key="a/b"><title>&bogus;</title></article>""".getBytes).isLeft)
    assert(
      parser
        .parse(
          """<article key="a/b"><title>&xxe;</title></article><!DOCTYPE x [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>""".getBytes
        )
        .isLeft
    )
    assert(parser.parse("""<article key="a/b"><title>unclosed</article>""".getBytes).isLeft)
  }

  test(
    "parser: multiline title with inline markup collapses whitespace; ISO-8859-1 input decodes"
  ) {
    val rec = parser
      .parse(
        "<article key=\"a/b\"><title>A <i>multiline</i>\n   title  &amp; more.</title></article>".getBytes
      )
      .toOption
      .get
    assertEquals(rec.title, Some("A multiline title & more."))
    val latin1 = new DblpParser(StandardCharsets.ISO_8859_1)
    val bytes = "<article key=\"a/b\"><author>João</author><title>t</title></article>"
      .getBytes(StandardCharsets.ISO_8859_1)
    assertEquals(latin1.parse(bytes).toOption.get.authors.head.name, "João")
  }
}
