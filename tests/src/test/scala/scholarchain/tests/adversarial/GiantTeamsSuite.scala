package scholarchain.tests.adversarial

import scala.collection.mutable

import org.apache.hadoop.fs.Path

import scholarchain.core.{Canonical, CoauthorPairCandidate, Json, PublicationTeam, WorkRecord}
import scholarchain.hadoop.pipeline.Stage

import Harness.*

/**
 * Phase 4 adversarial giant teams (README 1152-1155, 1193, 3056): generated DBLP works with 0, 1,
 * 2, 3, 100 (at the oversize threshold), 101 (just above it) and 1000 authors, through the REAL
 * normalize and teams MapReduce jobs with `scholarchain.team.maxSize = 100`. Every work must emit
 * EXACTLY n(n-1)/2 CoauthorPairCandidate records (no suppression), with positions consistent with
 * the mention IDs; oversize teams are counted and flagged. The 100-author work repeats one author
 * name at two positions (repeated names are distinct mentions).
 */
class GiantTeamsSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(15, "min")

  val sizes: Vector[Int] = Vector(0, 1, 2, 3, 100, 101, 1000)
  val MaxSize = 100

  def key(n: Int) = s"journals/giant/T$n"
  def workId(n: Int) = s"work:dblp:${key(n)}"
  def pairCount(n: Int): Long = n.toLong * (n - 1) / 2

  def authorName(n: Int, i: Int): String =
    if (n == 100 && (i == 10 || i == 60)) "Rep Eated" else s"Giant${n}x$i Author$i"

  def xml: String = {
    val recs = sizes.map { n =>
      val as = (0 until n).map(i => s"<author>${authorName(n, i)}</author>").mkString
      s"""<article key="${key(n)}">$as<title>Team of $n.</title><year>2020</year></article>"""
    }
    ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +: "<dblp>" +: recs :+ "</dblp>").mkString("\n")
  }

  lazy val result = {
    val work = workDir("adversarial-giant-teams")
    val in = inputs(dblp = Vector(writeInput(work, "dblp/giant.xml", xml)))
    val conf = localConf(work, None)
    val m = manifest(baseParams(teamMaxSize = MaxSize))
    val t0 = System.nanoTime()
    val reports = run(conf, m, work.resolve("curated"), in, Vector(Stage.Normalize, Stage.Teams))
    val secs = (System.nanoTime() - t0) / 1e9
    println(f"[adversarial-giant-teams] normalize+teams pipeline took $secs%.1f s")
    printReport("adversarial-giant-teams", reports)
    (conf, reports, runRoot(work.resolve("curated"), m), runIdOf(m), secs)
  }

  def t = stageOf(result._2, "teams").counters

  test("every work emits exactly n(n-1)/2 distinct canonical pairs with consistent positions") {
    val (conf, _, run, _, _) = result
    val works = decodeAll[WorkRecord](lines(conf, new Path(run, "normalize/works")))
      .map(w => w.workId -> w)
      .toMap
    assertEquals(works.keySet, sizes.map(workId).toSet)
    sizes.foreach(n => assertEquals(works(workId(n)).authors.size, n, s"n=$n mentions kept"))
    val w100 = works(workId(100))
    assertEquals(w100.authors(10).rawName, w100.authors(60).rawName)
    assertNotEquals(w100.authors(10).mentionId, w100.authors(60).mentionId)

    val mentionIds = works.view.mapValues(_.authors.map(_.mentionId).toArray).toMap
    val seen = mutable.Map.empty[String, mutable.HashSet[Long]]
    val t0 = System.nanoTime()
    val total = foreachLine(conf, new Path(run, "teams/pairs")) { line =>
      val p = Json.decode[CoauthorPairCandidate](line).fold(e => fail(s"$line: $e"), identity)
      val w = works(p.workId)
      val ids = mentionIds(p.workId)
      assertEquals(p.teamSize, w.authors.size)
      assertEquals(p.year, w.year)
      assertEquals(p.provenance, w.provenance)
      assertEquals(ids(p.positionA), p.endpointA)
      assertEquals(ids(p.positionB), p.endpointB)
      assert(Canonical.compareUtf8(p.endpointA, p.endpointB) < 0, "canonical endpoint order")
      val lo = math.min(p.positionA, p.positionB).toLong
      val hi = math.max(p.positionA, p.positionB).toLong
      assert(lo != hi)
      assert(seen.getOrElseUpdate(p.workId, mutable.HashSet.empty).add(lo * 100000L + hi), line)
    }
    println(
      f"[adversarial-giant-teams] decoded and checked $total pairs in ${(System.nanoTime() - t0) / 1e9}%.1f s"
    )
    sizes.foreach { n =>
      assertEquals(seen.get(workId(n)).map(_.size.toLong).getOrElse(0L), pairCount(n), s"n=$n")
    }
    val expected = sizes.map(pairCount).sum
    assertEquals(expected, 509504L)
    assertEquals(total, expected)
    assertEquals(cnt(t, "EMITTED_PAIR"), expected, "EMITTED_PAIR is the sum over works")
  }

  test("teams: one PublicationTeam per work with authors, in author order; none for 0 authors") {
    val (conf, _, run, _, _) = result
    val teams = decodeAll[PublicationTeam](lines(conf, new Path(run, "teams/teams")))
    assertEquals(teams.map(_.workId).toSet, sizes.filter(_ > 0).map(workId).toSet)
    val works = decodeAll[WorkRecord](lines(conf, new Path(run, "normalize/works")))
    teams.foreach { tm =>
      val w = works.find(_.workId == tm.workId).get
      assertEquals(tm.teamSize, w.authors.size)
      assertEquals(tm.unresolvedMentionIds, w.authors.map(_.mentionId))
    }
    assertEquals(cnt(t, "TEAM_NO_AUTHORS.dblp"), 1L)
    assertEquals(cnt(t, "EMITTED.teams"), 6L)
    assertEquals(cnt(t, "ACCEPTED.dblp"), 6L)
  }

  test("oversize teams (n > maxSize) are counted and flagged, never suppressed; true stats") {
    val (conf, reports, run, _, secs) = result
    assertEquals(cnt(t, "OVERSIZE_TEAM"), 2L, "101 and 1000; 100 is at the threshold")
    assertEquals(cnt(t, "FLAGGED.oversize-team"), 2L)
    val rej = rejects(conf, new Path(run, "teams/rejects"))
    assertEquals(
      rej.map(r => (r.reason, r.disposition, r.sourceRecordId)).sortBy(_.toString),
      Vector(
        ("oversize-team", "flagged", Some(key(1000))),
        ("oversize-team", "flagged", Some(key(101)))
      ).sortBy(_.toString)
    )
    val big = rej.find(_.sourceRecordId.contains(key(1000))).get
    assert(
      Vector("teamSize=1000", "pairs=499500", s"maxSize=$MaxSize", "all pairs emitted")
        .forall(big.detail.contains),
      big.detail.toString
    )
    val st = stageOf(reports, "teams").stats
    assertEquals(st("maxTeamSize").value, 1000L)
    assertEquals(st("maxTeamSize").id, workId(1000))
    assertEquals(st("maxPairExpansion").value, 499500L)
    assertEquals(st("maxPairExpansion").id, workId(1000))
    assert(secs < 600.0, s"bounded runtime: $secs s")
  }
}
