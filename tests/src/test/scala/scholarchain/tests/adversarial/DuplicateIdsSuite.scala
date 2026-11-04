package scholarchain.tests.adversarial

import java.nio.file.Path as JPath

import scala.collection.immutable.SortedMap

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path

import scholarchain.core.{IdentityLink, Identifiers, SourceType, WorkRecord}
import scholarchain.hadoop.Envelope
import scholarchain.hadoop.pipeline.{Stage, StageReport}

import Harness.*

/**
 * Phase 4 adversarial duplicate IDs (README 1137-1138, 1161, 1166): the same DBLP key twice (same
 * and different content), one DOI on different works within one source and across sources,
 * conflicting DOIs inside one record, one ORCID claimed by two mentions or two profiles, duplicate
 * OpenAlex work and author IDs, a duplicated ORCID record, and a DOI duplicated only by case. Runs
 * the REAL normalize and exact-ids jobs twice: once in fixture order and once with every input file
 * in reversed record order, to show that the canonical choice does not depend on arrival order.
 */
class DuplicateIdsSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  final case class Result(conf: Configuration, reports: Vector[StageReport], run: Path) {
    def counters(s: String): SortedMap[String, Long] = stageOf(reports, s).counters
    def out(dir: String): Vector[String] = lines(conf, new Path(run, dir))
  }

  /** Reverses the order of the record lines of a one-record-per-line fixture. */
  def reversed(text: String, isRecord: String => Boolean): String = {
    val ls = text.split("\n", -1).toVector
    val idx = ls.indices.filter(i => isRecord(ls(i)))
    val recs = idx.map(ls).reverse
    idx.zip(recs).foldLeft(ls) { case (acc, (i, r)) => acc.updated(i, r) }.mkString("\n")
  }

  def runWith(name: String, reverse: Boolean): Result = {
    val work = workDir(s"adversarial-dups-$name")
    def put(rel: String, as: String, isRecord: String => Boolean): JPath = {
      val text = readResource(rel)
      writeInput(work, as, if (reverse) reversed(text, isRecord) else text)
    }
    val json = (l: String) => l.startsWith("{")
    val holder = readResource("dups/orcid-holder.xml")
    val o1 = writeInput(work, "orcid/dup-1.xml", holder)
    writeInput(work, "orcid/dup-2.xml", holder)
    val in = inputs(
      dblp = Vector(put("dups/dblp-dups.xml", "dblp/dups.xml", _.startsWith("<article"))),
      openAlexWorks = Vector(put("dups/openalex-works-dups.jsonl", "openalex/works.jsonl", json)),
      openAlexAuthors =
        Vector(put("dups/openalex-authors-dups.jsonl", "openalex/authors.jsonl", json)),
      crossrefWorks = Vector(put("dups/crossref-dups.jsonl", "crossref/works.jsonl", json)),
      orcid = Vector(o1.getParent)
    )
    val conf = localConf(work, None)
    val m = manifest(baseParams())
    val reports = run(conf, m, work.resolve("curated"), in, Vector(Stage.Normalize, Stage.ExactIds))
    printReport(s"adversarial-dups-$name", reports)
    Result(conf, reports, runRoot(work.resolve("curated"), m))
  }

  lazy val forward: Result = runWith("forward", reverse = false)
  lazy val backward: Result = runWith("reversed", reverse = true)

  test("precondition: the ORCID iDs used as duplicates are checksum-valid") {
    assert(Identifiers.isValidOrcid("0000-0002-1825-0097"))
    assert(Identifiers.isValidOrcid("0000-0001-5109-3700"))
    assert(!Identifiers.isValidOrcid("0000-0002-1825-0098"))
  }

  test("normalize: DUPLICATE and CONFLICT counters per kind, and flagged conflict records") {
    Vector(forward, backward).foreach { r =>
      val n = r.counters("normalize")
      assertEquals(cnt(n, "DUPLICATE.work"), 6L, "Same, Diff, crdup, W9001, W9002, ORCID work")
      assertEquals(cnt(n, "DUPLICATE.profile"), 2L, "A9001 and the duplicated ORCID holder")
      assertEquals(cnt(n, "DUPLICATE"), 8L)
      assertEquals(cnt(n, "CONFLICT"), 3L, "Diff, W9002 (content) and TwoDois (two DOIs)")
      assertEquals(cnt(n, "FLAGGED.conflict"), 3L)
      assertEquals(cnt(n, "REJECTED"), 0L)
      assertEquals(cnt(n, "EMITTED.works"), 13L)
      assertEquals(cnt(n, "EMITTED.profiles"), 4L)
      val conflicts =
        rejects(r.conf, new Path(r.run, "normalize/rejects")).filter(_.reason == "conflict")
      assertEquals(
        conflicts.map(c => (c.sourceRecordId, c.disposition, c.sourceDataset)).sortBy(_.toString),
        Vector(
          (Some("journals/dup/TwoDois"), "flagged", "dblp"),
          (Some("work:dblp:journals/dup/Diff"), "flagged", "dblp"),
          (Some("work:openalex:W9002"), "flagged", "openalex")
        ).sortBy(_.toString)
      )
      val works = decodeAll[WorkRecord](r.out("normalize/works"))
      assertEquals(works.map(_.workId).distinct.size, works.size, "one record per work ID")
      val two = works.find(_.sourceRecordId == "journals/dup/TwoDois").get
      assertEquals(two.doi, Some("10.5555/two1"))
      assertEquals(
        two.additionalIds.map(i => (i.scheme.tag, i.value)),
        Vector(("doi", "10.5555/two2"))
      )
      assert(works.exists(_.workId == "work:doi:10.5555/crdup"), "case-only DOI duplicate merged")
    }
  }

  test("canonical copy of a conflicting key is the smallest content hash, whatever the order") {
    Vector(forward, backward).foreach { r =>
      val line = r.out("normalize/works").find(_.contains("\"work:dblp:journals/dup/Diff\"")).get
      val kept = ujson.read(line)
      val keptTitle = kept("title").str
      val other = ujson.read(line)
      val (t, nt) =
        if (keptTitle == "First version.") ("Second version.", "second version")
        else ("First version.", "first version")
      other("title") = t
      other("normalizedTitle") = nt
      val keptHash = Envelope.contentHash(line)
      assert(keptHash < Envelope.contentHash(ujson.write(other)), s"kept '$keptTitle'")
      val rej = rejects(r.conf, new Path(r.run, "normalize/rejects"))
        .find(_.sourceRecordId.contains("work:dblp:journals/dup/Diff"))
        .get
      assert(rej.detail.head.contains(keptHash), rej.detail.toString)
      assert(rej.detail.head.contains("2 copies"), rej.detail.toString)
    }
    for (dir <- Vector("normalize/works", "normalize/mentions", "normalize/profiles"))
      assertEquals(backward.out(dir), forward.out(dir), s"$dir independent of input order")
  }

  test(
    "exact ids: within-source DOI, mention-ORCID and profile-ORCID conflicts; cross-source DOI"
  ) {
    Vector(forward, backward).foreach { r =>
      val x = r.counters("exact-ids")
      assertEquals(cnt(x, "CONFLICT"), 3L)
      val conflicts = rejects(r.conf, new Path(r.run, "exact-ids/rejects"))
      assertEquals(
        conflicts.map(c => (c.locator, c.reason, c.disposition)).sorted,
        Vector(
          ("doi:10.5555/shared", "conflict", "flagged"),
          ("orcid:0000-0001-5109-3700", "conflict", "flagged"),
          ("orcid:0000-0002-1825-0097", "conflict", "flagged")
        )
      )
      val byLocator = conflicts.map(c => c.locator -> c.detail.mkString).toMap
      assert(byLocator("doi:10.5555/shared").contains("2 distinct dblp works"))
      assert(byLocator("orcid:0000-0002-1825-0097").contains("2 author mentions of one work"))
      assert(byLocator("orcid:0000-0001-5109-3700").contains("2 distinct openalex author profiles"))
      val links = decodeAll[IdentityLink](r.out("exact-ids/links"))
      val cross =
        links.filter(l => l.identifier.scheme.tag == "doi" && l.identifier.value == "10.5555/cross")
      assertEquals(
        cross.map(_.assertedBy).distinct.sortBy(_.tag),
        Vector(SourceType.Crossref, SourceType.Dblp, SourceType.OpenAlex, SourceType.Orcid)
      )
      assert(cnt(x, "EXACT_ID_CROSS_SOURCE.doi") >= 1L)
      assertEquals(links.map(_.linkId).distinct.size, links.size, "links are distinct")
      val same = links.filter(l => l.identifier.value == "10.5555/same")
      assertEquals(
        same.map(_.entityId),
        Vector("work:dblp:journals/dup/Same"),
        "dedupe before links"
      )
    }
    assertEquals(backward.out("exact-ids/links"), forward.out("exact-ids/links"))
    checkContracts(forward.conf, forward.run, runIdOf(manifest(baseParams())))
  }
}
