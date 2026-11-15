package scholarchain.hadoop.normalize

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.jdk.CollectionConverters.*

import org.apache.hadoop.fs.Path

import scholarchain.core.SourceType
import scholarchain.hadoop.pipeline.{Pipeline, PipelineInputs, RunLayout, Stage}
import scholarchain.hadoop.{NamedOutputs, RejectReason, RejectRecord, TestSupport}

/**
 * Crossref REST API envelopes (work order HD-P5-02). The committed ER candidate samples keep the
 * envelope `{"status":"ok","message-type":"work-list","message":{"items":[<work>]}}` (schema note
 * er-candidates.md). Before the fix every such line was rejected with missing-key (no top-level
 * DOI). Only DEV samples are read here; held-out samples are never opened by a test.
 */
class CrossrefEnvelopeSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  val cr = TestSupport.run(SourceType.Crossref)
  val devDir = TestSupport.sample("er-candidates/dev/crossref-works")

  def devFiles: Vector[java.nio.file.Path] = {
    val s = Files.list(devDir)
    try s.iterator.asScala.toVector.sortBy(_.getFileName.toString)
    finally s.close()
  }

  def accepted(o: Outcome): Normalized = o match {
    case Outcome.Accepted(_, n) => n
    case other => fail(s"expected Accepted, got $other")
  }

  def rejected(o: Outcome): (RejectReason, Vector[String]) = o match {
    case Outcome.Rejected(_, ps) => (ps.head.reason, ps.flatMap(_.detail))
    case other => fail(s"expected Rejected, got $other")
  }

  /** The exact committed sample format, with `items` replaced (for malformed variants). */
  def envelope(status: String, messageType: String, items: String): String =
    s"""{"status":"$status","message-type":"$messageType","message-version":"1.0.0","message":""" +
      s"""{"facets":{},"total-results":1,"items":$items,"items-per-page":2,""" +
      """"query":{"start-index":0,"search-terms":null}}}"""

  val work: String =
    """{"DOI":"10.1145\/3466819","type":"journal-article","title":["A Survey on End-User Robot Programming"],""" +
      """"author":[{"given":"Gopika","family":"Ajaykumar","sequence":"first"}],""" +
      """"container-title":["ACM Computing Surveys"],"issued":{"date-parts":[[2021,10,4]]}}"""

  test("every committed DEV Crossref sample (API envelope, one line) is accepted as one work") {
    val files = devFiles
    assertEquals(files.size, 6, "dev/crossref-works holds 6 committed envelopes")
    val works = files.map { f =>
      val lines = new String(Files.readAllBytes(f), StandardCharsets.UTF_8)
        .split("\n")
        .toVector
        .filter(_.trim.nonEmpty)
      assertEquals(lines.size, 1, s"$f is one line")
      assert(lines.head.startsWith("""{"status":"ok","message-type":"work-list""""), f.toString)
      val n = accepted(CrossrefNormalizer.workLine(lines.head, cr))
      assertEquals(n.works.size, 1, f.toString)
      val w = n.works.head
      assert(w.workId.startsWith("work:doi:10."), w.workId)
      assertEquals(w.source, SourceType.Crossref)
      assertEquals(w.provenance.sourceDataset, SourceType.Crossref)
      w
    }
    // The retraction notice 10.1155/2023/9869061 has no author list in Crossref: kept, no mentions.
    assertEquals(
      works.filter(_.authors.isEmpty).map(_.workId),
      Vector("work:doi:10.1155/2023/9869061")
    )
  }

  test("envelope content is read from message.items[0]: DOI, title, authors, ORCID, year, venue") {
    val survey = TestSupport.readSample(
      "er-candidates/dev/crossref-works/10.1145_3466819-ce171d83.json"
    )
    val w = accepted(CrossrefNormalizer.workLine(survey, cr)).works.head
    assertEquals(w.workId, "work:doi:10.1145/3466819")
    assertEquals(w.doi, Some("10.1145/3466819"))
    assertEquals(w.title, "A Survey on End-User Robot Programming")
    assertEquals(
      w.authors.map(_.rawName),
      Vector("Gopika Ajaykumar", "Maureen Steele", "Chien-Ming Huang")
    )
    assertEquals(w.authors.map(_.position), Vector(0, 1, 2))
    assertEquals(w.year, Some(2021))
    assertEquals(w.venueName, Some("ACM Computing Surveys"))
    assertEquals(w.volume, Some("54"))
    assertEquals(w.issue, Some("8"))
    assertEquals(w.pages, Some("1-36"))
    val tkde = TestSupport.readSample(
      "er-candidates/dev/crossref-works/10.1109_tkde.2020.3027950-90566e0d.json"
    )
    val t = accepted(CrossrefNormalizer.workLine(tkde, cr)).works.head
    assertEquals(
      t.authors.map(_.orcid),
      Vector(None, Some("0000-0002-3650-0301"), Some("0000-0001-9404-5848"))
    )
  }

  test("a bare work line (public data file shape) is still accepted unchanged") {
    val bare = accepted(CrossrefNormalizer.workLine(work, cr)).works.head
    val wrapped =
      accepted(CrossrefNormalizer.workLine(envelope("ok", "work-list", s"[$work]"), cr)).works.head
    assertEquals(wrapped, bare)
    val single =
      accepted(
        CrossrefNormalizer.workLine(
          s"""{"status":"ok","message-type":"work","message":$work}""",
          cr
        )
      )
    assertEquals(single.works.head, bare)
  }

  test("malformed envelopes fail closed with a reason (never guessed)") {
    def check(line: String, reason: RejectReason, fragment: String) = {
      val (r, detail) = rejected(CrossrefNormalizer.workLine(line, cr))
      assertEquals(r, reason, line)
      assert(detail.exists(_.contains(fragment)), s"$detail lacks '$fragment'")
    }
    check(envelope("ok", "work-list", "[]"), RejectReason.MissingKey, "message.items is empty")
    check(
      envelope("ok", "work-list", s"[$work,$work]"),
      RejectReason.MalformedField,
      "holds 2 works"
    )
    check(
      envelope("error", "work-list", s"[$work]"),
      RejectReason.MalformedField,
      "status is \"error\""
    )
    check(
      envelope("ok", "member", s"[$work]"),
      RejectReason.MalformedField,
      "message-type \"member\""
    )
    check(
      envelope("ok", "work-list", "[42]"),
      RejectReason.MalformedJson,
      "message.items[0] is not an object"
    )
    check(
      envelope("ok", "work-list", "{}"),
      RejectReason.MalformedField,
      "message.items is not an array"
    )
    check(
      """{"status":"ok","message-type":"work-list"}""",
      RejectReason.MissingField,
      "message is missing"
    )
    check(
      s"""{"message-type":"work","message":$work}""",
      RejectReason.MissingField,
      "status is missing"
    )
    check(
      s"""{"status":"ok","message":$work}""",
      RejectReason.MissingField,
      "message-type is missing"
    )
    check(
      """{"status":"ok","message-type":"work-list","message":{"facets":{}}}""",
      RejectReason.MissingField,
      "message.items is missing"
    )
    // A work inside a valid envelope is still checked as a work.
    check(envelope("ok", "work-list", """[{"title":["No DOI"]}]"""), RejectReason.MissingKey, "DOI")
  }

  test(
    "through MapReduce: envelopes accepted, malformed ones in the versioned reject output with counters"
  ) {
    val work0 = TestSupport.workDir("crossref-envelope")
    val in = work0.resolve("crossref.jsonl")
    val good =
      TestSupport.readSample("er-candidates/dev/crossref-works/10.1145_3466819-ce171d83.json")
    Files.write(
      in,
      Vector(
        good,
        envelope("ok", "work-list", s"[$work,$work]"),
        envelope("ok", "work-list", "[]"),
        envelope("error", "work-list", s"[$work]")
      ).mkString("", "\n", "\n").getBytes(StandardCharsets.UTF_8)
    )
    val inputs = PipelineInputs(
      dblp = Vector(new Path(TestSupport.sample("dblp/dblp-sample.xml").toUri)),
      openAlexWorks = Vector.empty,
      openAlexAuthors = Vector.empty,
      openAlexInstitutions = Vector.empty,
      crossrefWorks = Vector(new Path(in.toUri)),
      orcidRecords = Vector.empty
    )
    val m = TestSupport.manifest(TestSupport.baseParams)
    val conf = TestSupport.localConf(work0)
    val root = new Path(work0.resolve("curated").toUri)
    val reports = Pipeline
      .run(conf, m, root, inputs, Vector(Stage.Normalize))
      .fold(e => fail(e.mkString), identity)
    val n = reports.head.counters
    assertEquals(n.getOrElse("INPUT.crossref-works", 0L), 4L)
    assertEquals(n.getOrElse("ACCEPTED.crossref-works", 0L), 1L)
    assertEquals(n.getOrElse("REJECTED.malformed-field", 0L), 2L)
    assertEquals(n.getOrElse("REJECTED.missing-key", 0L), 1L)
    val runId = scholarchain.core.RunId.of(m).toOption.get
    val layout = RunLayout.of(root, m, runId).toOption.get
    val fs = layout.root.getFileSystem(conf)
    val works = Pipeline.readLines(conf, fs, layout.works)
    assert(
      works.exists(_.contains("\"workId\":\"work:doi:10.1145/3466819\"")),
      works.mkString("\n")
    )
    val rej = Pipeline
      .readLines(conf, fs, layout.normalized(NamedOutputs.Rejects))
      .map(RejectRecord.fromJson)
      .filter(_.sourceDataset == "crossref")
    assertEquals(
      rej.filter(_.disposition == "rejected").map(_.reason).sorted,
      Vector("malformed-field", "malformed-field", "missing-key")
    )
    assert(rej.forall(r => r.schemaVersion == RejectRecord.SchemaVersion && r.runId == runId))
    assert(
      rej.filter(_.disposition == "rejected").forall(_.excerpt.exists(_.startsWith("{\"status\"")))
    )
  }
}
