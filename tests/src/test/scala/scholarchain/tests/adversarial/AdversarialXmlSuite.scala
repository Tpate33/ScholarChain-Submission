package scholarchain.tests.adversarial

import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path as JPath}
import java.util.zip.GZIPOutputStream

import scala.collection.immutable.SortedMap

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path

import scholarchain.core.{CoauthorPairCandidate, PublicationTeam, WorkRecord}
import scholarchain.hadoop.RejectRecord
import scholarchain.hadoop.pipeline.{Stage, StageReport}

import Harness.*

/**
 * Phase 4 adversarial XML (README 1125, 1192, 1197, 2243): broken DBLP XML driven through the REAL
 * normalize and teams MapReduce jobs on LocalJobRunner, once with 64-byte input splits (records and
 * a comment straddle split boundaries) and once with whole-file splits. Fixtures:
 * tests/src/test/resources/adversarial/xml/.
 */
class AdversarialXmlSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  val SmallSplit = 64L

  /** Works that every configuration must accept (valid records next to broken ones). */
  val ValidWorks: Set[String] = Set(
    "journals/adv/Good1",
    "journals/adv/Good2",
    "journals/adv/Good3",
    "journals/adv/Good4a",
    "journals/adv/Good6",
    "journals/adv/BeforeTrunc",
    "journals/adv/CommentBefore",
    "journals/adv/CommentAfter",
    "journals/adv/Gz1",
    "journals/adv/Gz2"
  )

  /** Valid records that directly follow the unclosed record in unclosed.xml. */
  val UnclosedNeighbours: Set[String] = Set("conf/adv/Good4", "journals/adv/Good5")

  /** Keys that must never become works. */
  val NeverWorks: Set[String] = Set(
    "journals/adv/BadEntity",
    "journals/adv/Mismatch",
    "journals/adv/MalformedAmp",
    "journals/adv/Empty",
    "journals/adv/Unclosed",
    "journals/adv/Trunc",
    "books/adv/Book1",
    "homepages/00/0000",
    "persons/adv/1",
    "data/adv/1"
  )

  final case class Result(
      name: String,
      conf: Configuration,
      files: Map[String, JPath],
      reports: Vector[StageReport],
      run: Path
  ) {
    lazy val works: Vector[WorkRecord] =
      decodeAll[WorkRecord](lines(conf, new Path(run, "normalize/works")))
    lazy val keys: Set[String] = works.map(_.sourceRecordId).toSet
    lazy val rejects: Vector[RejectRecord] =
      Harness.rejects(conf, new Path(run, "normalize/rejects"))
    def normalize: SortedMap[String, Long] = stageOf(reports, "normalize").counters
    def teams: SortedMap[String, Long] = stageOf(reports, "teams").counters
    def rejectFor(key: String): Option[RejectRecord] =
      rejects.find(r =>
        r.sourceRecordId.contains(key) || r.excerpt.exists(_.contains(s"key=\"$key\""))
      )
  }

  def gzip(src: JPath, dst: JPath): JPath = {
    Files.createDirectories(dst.getParent)
    val out = new GZIPOutputStream(new FileOutputStream(dst.toFile))
    try out.write(Files.readAllBytes(src))
    finally out.close()
    dst
  }

  def runWith(name: String, split: Option[Long]): Result = {
    val work = workDir(s"adversarial-xml-$name")
    val files = Map(
      "mixed" -> stage(work, "xml/broken-mixed.xml", "dblp/broken-mixed.xml"),
      "unclosed" -> stage(work, "xml/unclosed.xml", "dblp/unclosed.xml"),
      "comment" -> stage(work, "xml/comment.xml", "dblp/comment.xml"),
      "truncated" -> stage(work, "xml/truncated.xml", "dblp/truncated.xml"),
      "gzip" -> gzip(resource("xml/gzip-source.xml"), work.resolve("input/dblp/gzip-source.xml.gz"))
    )
    val conf = localConf(work, split)
    val m = manifest(baseParams())
    val order = Vector("mixed", "unclosed", "comment", "truncated", "gzip")
    val reports = run(
      conf,
      m,
      work.resolve("curated"),
      inputs(dblp = order.map(files)),
      Vector(Stage.Normalize, Stage.Teams)
    )
    printReport(s"adversarial-xml-$name", reports)
    Result(name, conf, files, reports, runRoot(work.resolve("curated"), m))
  }

  lazy val small: Result = runWith("split64", Some(SmallSplit))
  lazy val whole: Result = runWith("whole-file", None)
  def both: Vector[Result] = Vector(small, whole)

  test("precondition: 64-byte splits cut record start tags and the commented-out record tag") {
    val mixed = bytes(resource("xml/broken-mixed.xml"))
    val starts = Vector("<article key=", "<www key=", "<person key=", "<book key=")
      .flatMap { t =>
        Iterator
          .iterate(indexOf(mixed, t))(i => if (i < 0) -1 else indexOf(mixed, t, i + 1))
          .takeWhile(_ >= 0)
          .toVector
          .map(i => (i, t.length))
      }
    assert(
      starts.exists { case (i, len) => i / SmallSplit != (i + len - 1) / SmallSplit },
      "some record start tag straddles a 64-byte split boundary"
    )
    val comment = bytes(resource("xml/comment.xml"))
    val opener = indexOf(comment, "<!--")
    val ghost = indexOf(comment, "<article key=\"journals/adv/Ghost\"")
    assert(opener > 0 && ghost > opener)
    assertNotEquals(opener.toLong / SmallSplit, ghost.toLong / SmallSplit)
  }

  test("valid records next to broken ones are accepted in every split configuration") {
    both.foreach { r =>
      assert(ValidWorks.subsetOf(r.keys), s"${r.name}: missing ${ValidWorks -- r.keys}")
      assert((r.keys intersect NeverWorks).isEmpty, s"${r.name}: ${r.keys intersect NeverWorks}")
      val good2 = r.works.find(_.sourceRecordId == "journals/adv/Good2").get
      assertEquals(good2.title, "Good two: Café & öl.", "entities and inline markup")
      assertEquals(good2.authors.map(_.rawName), Vector("Dana Good", "Eve Good", "Fay Good"))
      val good3 = r.works.find(_.sourceRecordId == "journals/adv/Good3").get
      assertEquals(good3.title, "Good three spans lines.", "multi-line title collapsed")
      val good1 = r.works.find(_.sourceRecordId == "journals/adv/Good1").get
      assertEquals(good1.venueName, Some("J. Adv."))
      assertEquals(good1.recordType, Some("article"))
    }
  }

  test("broken records are rejected with reason, disposition, versioned format and locator") {
    val expected = Vector(
      "journals/adv/BadEntity" -> "malformed-xml",
      "journals/adv/Mismatch" -> "malformed-xml",
      "journals/adv/MalformedAmp" -> "malformed-xml",
      "journals/adv/Empty" -> "missing-field",
      "journals/adv/Trunc" -> "unterminated-record"
    )
    both.foreach { r =>
      expected.foreach { case (key, reason) =>
        val rej = r.rejectFor(key).getOrElse(fail(s"${r.name}: no reject for $key"))
        assertEquals((rej.reason, rej.disposition), (reason, "rejected"), s"${r.name} $key")
        assertEquals(rej.schemaVersion, "scholarchain.hadoop.reject.v1")
        assertEquals(rej.stage, "normalize")
        assertEquals(rej.sourceDataset, "dblp")
        assertEquals(rej.snapshot, "dblp-2026-10-03")
        // The locator is <path>@<byte offset of the record's '<'>, and the excerpt is the record.
        val at = rej.locator.lastIndexOf('@')
        val file = rej.locator.substring(0, at)
        val offset = rej.locator.substring(at + 1).toInt
        val src = r.files.values.find(p => file.endsWith(p.getFileName.toString)).get
        val raw = bytes(src)
        val ex = rej.excerpt.getOrElse(fail(s"$key: no excerpt"))
        assert(ex.startsWith("<"), ex)
        assertEquals(
          new String(
            raw.slice(offset, offset + ex.getBytes(StandardCharsets.UTF_8).length),
            "UTF-8"
          ),
          ex,
          s"${r.name} $key: excerpt is the record at the locator offset"
        )
      }
      // The unclosed record itself is never accepted and is reported.
      val unclosed = r.rejectFor("journals/adv/Unclosed").getOrElse(fail("no unclosed reject"))
      assert(Set("malformed-xml", "unterminated-record")(unclosed.reason), unclosed.reason)
      assertEquals(unclosed.disposition, "rejected")
      assert(r.rejects.forall(_.runId == r.rejects.head.runId))
    }
  }

  test("non-publication, filtered, and comment-only content is counted and never a work") {
    both.foreach { r =>
      val n = r.normalize
      assertEquals(cnt(n, "SKIPPED_NON_PUBLICATION.non-publication:www"), 1L, r.name)
      assertEquals(cnt(n, "SKIPPED_NON_PUBLICATION.non-publication:person"), 1L, r.name)
      assertEquals(cnt(n, "SKIPPED_NON_PUBLICATION.non-publication:data"), 1L, r.name)
      assertEquals(cnt(n, "FILTERED.record-type"), 1L, r.name)
      assertEquals(cnt(n, "REJECTED.unterminated-record"), 1L, r.name)
      assertEquals(cnt(n, "REJECTED.missing-field"), 1L, r.name)
      assertEquals(cnt(n, "MISSING_KEY"), 0L, r.name)
    }
  }

  test("required counters reconcile with the reject output and the curated works") {
    both.foreach { r =>
      val n = r.normalize
      val rejected = r.rejects.count(_.disposition == "rejected").toLong
      assertEquals(cnt(n, "REJECTED"), rejected, r.name)
      assertEquals(
        cnt(n, "SCHEMA_FAILURE"),
        r.rejects.count(x => SchemaFailureReasons(x.reason)).toLong,
        r.name
      )
      assertEquals(cnt(n, "ACCEPTED.dblp"), r.works.size.toLong, s"${r.name}: no duplicates here")
      assertEquals(
        cnt(n, "INPUT.dblp"),
        cnt(n, "ACCEPTED.dblp") + rejected + cnt(n, "FILTERED") +
          cnt(n, "SKIPPED_NON_PUBLICATION"),
        s"${r.name}: every scanned record is accepted, rejected, filtered or skipped"
      )
      // Teams: every accepted work with authors has one team and n(n-1)/2 pairs.
      val teams = decodeAll[PublicationTeam](lines(r.conf, new Path(r.run, "teams/teams")))
      val pairs = decodeAll[CoauthorPairCandidate](lines(r.conf, new Path(r.run, "teams/pairs")))
      assertEquals(
        teams.map(_.workId).toSet,
        r.works.filter(_.authors.nonEmpty).map(_.workId).toSet
      )
      r.works.foreach { w =>
        val n = w.authors.size.toLong
        assertEquals(pairs.count(_.workId == w.workId).toLong, n * (n - 1) / 2, w.workId)
      }
      assertEquals(cnt(r.teams, "EMITTED_PAIR"), pairs.size.toLong)
      checkContracts(r.conf, r.run, runIdOf(manifest(baseParams())))
    }
  }

  test("gzip DBLP input is read whole: its records are normalized and teamed") {
    both.foreach { r =>
      val gz = r.works.filter(_.sourceRecordId.startsWith("journals/adv/Gz"))
      assertEquals(gz.map(_.sourceRecordId).sorted, Vector("journals/adv/Gz1", "journals/adv/Gz2"))
      assertEquals(gz.find(_.sourceRecordId.endsWith("Gz2")).get.authors.size, 3)
      val pairs = decodeAll[CoauthorPairCandidate](lines(r.conf, new Path(r.run, "teams/pairs")))
      assertEquals(pairs.count(_.workId == "work:dblp:journals/adv/Gz2"), 3)
    }
  }

  // ---- Known-risk and defect probes (see the Phase 4 test report). ----

  test("an XML comment containing a record tag never yields a work (64-byte splits)") {
    assert(
      !small.keys.contains("journals/adv/Ghost"),
      "a record inside <!-- ... --> was emitted as a work when its tag lies in a later split " +
        "than the comment opener; observed works: " + small.keys.toVector.sorted.mkString(", ")
    )
    assert(!whole.keys.contains("journals/adv/Ghost"), "whole-file read must skip the comment")
  }

  test("an unclosed record does not swallow the valid records that follow it (whole-file split)") {
    both.foreach { r =>
      assert(
        UnclosedNeighbours.subsetOf(r.keys),
        s"${r.name}: valid neighbours ${UnclosedNeighbours -- r.keys} lost after the unclosed " +
          s"record; INPUT.dblp=${cnt(r.normalize, "INPUT.dblp")}"
      )
    }
  }

  test("normalize output does not depend on the input split size (determinism)") {
    def sorted(r: Result, dir: String) = lines(r.conf, new Path(r.run, dir)).sorted
    assertEquals(sorted(small, "normalize/works"), sorted(whole, "normalize/works"))
    assertEquals(sorted(small, "normalize/mentions"), sorted(whole, "normalize/mentions"))
    assertEquals(
      small.rejects.map(x => (x.reason, x.disposition, x.locator.split('/').last)).sorted,
      whole.rejects.map(x => (x.reason, x.disposition, x.locator.split('/').last)).sorted
    )
    assertEquals(cnt(small.normalize, "INPUT.dblp"), cnt(whole.normalize, "INPUT.dblp"))
  }
}
