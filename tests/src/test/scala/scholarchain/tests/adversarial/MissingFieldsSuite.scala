package scholarchain.tests.adversarial

import scala.collection.immutable.SortedMap

import org.apache.hadoop.fs.Path

import scholarchain.core.{SourceAuthorProfile, SourceType, WorkRecord}
import scholarchain.hadoop.pipeline.Stage

import Harness.*

/**
 * Phase 4 adversarial missing fields (README 1134, 1161, 2243): DBLP, OpenAlex works and authors,
 * Crossref, and ORCID records missing required or optional fields, driven through the REAL
 * normalize, teams and blocking jobs. Required-field gaps must be rejected (missing-key or
 * schema-failure family), optional-field gaps accepted, malformed optional values dropped and
 * flagged. Expected counts are derived by hand from the fixtures in
 * tests/src/test/resources/adversarial/missing/.
 */
class MissingFieldsSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  lazy val result = {
    val work = workDir("adversarial-missing")
    val orcid = Vector("no-id", "bad-checksum", "malformed", "xxe", "partial").map(n =>
      stage(work, s"missing/orcid/$n.xml", s"orcid/$n.xml")
    )
    val in = inputs(
      dblp = Vector(stage(work, "missing/dblp-missing.xml", "dblp/dblp-missing.xml")),
      openAlexWorks =
        Vector(stage(work, "missing/openalex-works-missing.jsonl", "openalex/works.jsonl")),
      openAlexAuthors =
        Vector(stage(work, "missing/openalex-authors-missing.jsonl", "openalex/authors.jsonl")),
      crossrefWorks = Vector(stage(work, "missing/crossref-missing.jsonl", "crossref/works.jsonl")),
      orcid = Vector(orcid.head.getParent)
    )
    val conf = localConf(work, None)
    val m = manifest(baseParams() ++ blockingParams(50, "drop"))
    val reports =
      run(
        conf,
        m,
        work.resolve("curated"),
        in,
        Vector(Stage.Normalize, Stage.Teams, Stage.Blocking)
      )
    printReport("adversarial-missing", reports)
    (conf, reports, runRoot(work.resolve("curated"), m), runIdOf(m))
  }

  def conf = result._1
  def counters(stage: String): SortedMap[String, Long] = stageOf(result._2, stage).counters
  def runDir: Path = result._3
  lazy val works: Vector[WorkRecord] =
    decodeAll[WorkRecord](lines(conf, new Path(runDir, "normalize/works")))
  lazy val rejected = rejects(conf, new Path(runDir, "normalize/rejects"))

  def rejectOf(source: String, id: Option[String], excerptHas: String = "") =
    rejected.filter(r =>
      r.sourceDataset == source && r.disposition == "rejected" &&
        (id.isEmpty || r.sourceRecordId == id) &&
        (excerptHas.isEmpty || r.excerpt.exists(_.contains(excerptHas)))
    )

  test("required fields missing: each record rejected with the documented reason") {
    val expected: Vector[(String, Option[String], String, String)] = Vector(
      ("dblp", None, "No Key", "missing-key"),
      ("dblp", Some("X/Upper"), "", "missing-key"),
      ("dblp", Some("journals/mf/NoTitle"), "", "missing-field"),
      ("dblp", Some("journals/mf/BlankTitle"), "", "missing-field"),
      ("dblp", Some("journals/mf/EmptyAuthor"), "", "missing-field"),
      ("dblp", Some("journals/mf/BadYear"), "", "malformed-field"),
      ("openalex", None, "No id", "missing-key"),
      ("openalex", Some("https://openalex.org/X123"), "", "missing-key"),
      ("openalex", Some("W7001"), "", "missing-field"),
      ("openalex", Some("W7002"), "", "missing-field"),
      ("openalex", None, "W7005", "malformed-json"),
      ("openalex", None, "[1,2,3]", "malformed-json"),
      ("openalex", None, "No Id", "missing-key"),
      ("crossref", None, "No DOI", "missing-key"),
      ("crossref", Some("not a doi"), "", "missing-key"),
      ("crossref", Some("10.5555/cr.notitle"), "", "missing-field"),
      ("crossref", Some("10.5555/cr.noname"), "", "missing-field"),
      ("orcid", None, "Withoutid", "missing-key"),
      ("orcid", Some("0000-0002-1825-0098"), "", "missing-key"),
      ("orcid", None, "never closed", "malformed-xml"),
      ("orcid", None, "file:///etc/hosts", "malformed-xml")
    )
    expected.foreach { case (src, id, ex, reason) =>
      val found = rejectOf(src, id, ex)
      assertEquals(found.map(_.reason), Vector(reason), s"$src $id '$ex': ${found.map(_.toString)}")
      assertEquals(found.head.schemaVersion, "scholarchain.hadoop.reject.v1")
      assert(found.head.excerpt.nonEmpty, "rejected records carry an excerpt")
    }
    assertEquals(rejected.count(_.disposition == "rejected"), expected.size, "no other rejects")
    val n = counters("normalize")
    assertEquals(cnt(n, "REJECTED"), 21L)
    assertEquals(cnt(n, "REJECTED.missing-key"), 9L)
    assertEquals(cnt(n, "REJECTED.missing-field"), 7L)
    assertEquals(cnt(n, "REJECTED.malformed-field"), 1L)
    assertEquals(cnt(n, "REJECTED.malformed-json"), 2L)
    assertEquals(cnt(n, "REJECTED.malformed-xml"), 2L)
  }

  test("the XXE probe is refused and nothing from the external entity reaches any output") {
    val all = outputDirs(conf, runDir).flatMap { case (_, d) => lines(conf, d) }
    assert(all.nonEmpty)
    // /etc/hosts always maps localhost; it must never be read into a record or reject excerpt
    // beyond the literal entity declaration of the fixture itself.
    assert(!all.exists(_.contains("127.0.0.1")), "external entity content leaked into outputs")
  }

  test("optional fields missing: records accepted with empty optional values") {
    val byKey = works.map(w => w.sourceRecordId -> w).toMap
    assertEquals(
      works.filter(_.source == SourceType.Dblp).map(_.sourceRecordId).sorted,
      Vector(
        "journals/mf/BadOrcid",
        "journals/mf/Bare",
        "journals/mf/NoAuthors",
        "journals/mf/NoYear"
      )
    )
    val bare = byKey("journals/mf/Bare")
    assertEquals(bare.year, None)
    assertEquals(bare.venueName, None)
    assertEquals(bare.doi, None)
    assertEquals(bare.eeLinks, Vector.empty)
    assertEquals(bare.urls, Vector.empty)
    assertEquals(bare.volume, None)
    assertEquals(bare.pages, None)
    assertEquals(byKey("journals/mf/NoYear").year, None)
    assertEquals(byKey("journals/mf/NoAuthors").authors, Vector.empty)
    val bo = byKey("journals/mf/BadOrcid")
    assertEquals(bo.doi, Some("10.5555/mf.1"))
    assertEquals(bo.eeLinks, Vector("https://doi.org/10.5555/mf.1"), "bad ee dropped")
    assertEquals(bo.authors.map(_.orcid), Vector(None), "malformed ORCID dropped")
    assertEquals(
      works.filter(_.source == SourceType.OpenAlex).map(_.sourceRecordId).sorted,
      Vector("W7003", "W7004", "W7006")
    )
    assertEquals(byKey("W7006").title, "Display name only")
    val w4 = byKey("W7004")
    assertEquals(w4.doi, None)
    assertEquals(w4.authors.map(a => (a.sourceAuthorId, a.orcid)), Vector((None, None)))
    assertEquals(
      works.filter(_.source == SourceType.Crossref).map(_.workId).sorted,
      Vector("work:doi:10.5555/cr.issn", "work:doi:10.5555/cr.min")
    )
    assertEquals(byKey("10.5555/cr.issn").venueId, None, "invalid ISSN builds no venue")
    assertEquals(byKey("10.5555/cr.issn").venueName, Some("J. Missing"))
    assertEquals(
      works.filter(_.source == SourceType.Orcid).map(_.sourceRecordId),
      Vector("0000-0002-1825-0097/work/1001"),
      "ORCID summaries without title or put-code are dropped (flagged), the titled one kept"
    )
    val profiles =
      decodeAll[SourceAuthorProfile](lines(conf, new Path(runDir, "normalize/profiles")))
    assertEquals(
      profiles.map(_.profileId).sorted,
      Vector(
        "source-author:openalex:A7001",
        "source-author:openalex:A7002",
        "source-author:orcid:0000-0002-1825-0097"
      )
    )
    assertEquals(profiles.find(_.sourceRecordId == "A7002").get.orcid, None)
    assertEquals(
      profiles.find(_.source == SourceType.Orcid).get.claimedWorkIds,
      Vector("work:orcid-work:0000-0002-1825-0097/work/1001")
    )
  }

  test("malformed optional values are flagged (kept records) and counted") {
    val flagged = rejected.filter(_.disposition == "flagged")
    assertEquals(
      flagged.map(f => (f.sourceDataset, f.reason)).groupMapReduce(identity)(_ => 1)(_ + _),
      Map(
        ("dblp", "malformed-identifier") -> 1,
        ("dblp", "malformed-field") -> 1,
        ("openalex", "malformed-identifier") -> 4,
        ("crossref", "malformed-identifier") -> 1,
        ("orcid", "missing-field") -> 1,
        ("orcid", "missing-key") -> 1
      )
    )
    val n = counters("normalize")
    assertEquals(cnt(n, "FLAGGED"), 9L)
    assertEquals(cnt(n, "INPUT"), 33L)
    assertEquals(cnt(n, "ACCEPTED"), 12L)
    assertEquals(cnt(n, "INPUT"), cnt(n, "ACCEPTED") + cnt(n, "REJECTED"))
    assertEquals(cnt(n, "MISSING_KEY"), 10L, "9 rejected + 1 flagged missing-key")
    assertEquals(cnt(n, "SCHEMA_FAILURE"), 20L, "12 rejected + 8 flagged schema-class")
    assertEquals(cnt(n, "EMITTED.works"), 10L)
    assertEquals(cnt(n, "EMITTED.profiles"), 3L)
  }

  test("downstream: authorless works form no team; a nameless profile is a blocking MISSING_KEY") {
    val t = counters("teams")
    assertEquals(cnt(t, "TEAM_NO_AUTHORS.dblp"), 1L)
    assertEquals(cnt(t, "EMITTED.teams"), 3L)
    assertEquals(cnt(t, "EMITTED_PAIR"), 1L, "NoYear has two authors; Bare and BadOrcid one")
    assertEquals(cnt(t, "TEAM_SOURCE_EXCLUDED"), 6L)
    val b = counters("blocking")
    assertEquals(cnt(b, "MISSING_KEY"), 1L)
    val br =
      rejects(conf, new Path(runDir, "blocking/round-0/rejects")).filter(_.reason == "missing-key")
    assertEquals(
      br.map(r => (r.sourceRecordId, r.disposition)),
      Vector((Some("source-author:openalex:A7001"), "flagged"))
    )
    assertEquals(
      cnt(b, "INPUT"),
      cnt(b, "ACCEPTED") + cnt(b, "MISSING_KEY") + cnt(b, "FLAGGED.invalid-blocking-key") +
        cnt(b, "REJECTED")
    )
    checkContracts(conf, runDir, result._4)
  }
}
