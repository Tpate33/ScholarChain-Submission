package scholarchain.tests.adversarial

import org.apache.hadoop.fs.Path

import scholarchain.core.{
  AnalysisLineage,
  CoauthorEdgeV2,
  CoauthorPairCandidate,
  GroupingEvaluation,
  Provenance,
  PublicationTeam,
  ResearcherId,
  SchemaVersions,
  SourceType,
  TimeWindow,
  WorkRecord,
  YearCount
}
import scholarchain.hadoop.pipeline.Stage

import Harness.*

/**
 * TE-P11-02 T16 (Phase 11 category 4, reordered author lists). The same four authors on three DBLP
 * works, each with a different author order, through the REAL normalize and teams jobs; the same
 * works with the input records in reverse order; and core's coauthor-edge builder with its
 * endpoints swapped. Required: author order changes nothing but positions: every work yields the
 * same set of name pairs (6 = 4*3/2) and team size, output does not depend on record order, and
 * `CoauthorEdgeV2.build(a, b)` equals `build(b, a)` (edge ID, endpoints, counts).
 */
class ReorderedAuthorsSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  private val names = Vector("Alma Ashby", "Boris Birchwood", "Cleo Cedarholm", "Dmitri Dunmore")
  private val orders: Vector[(String, Vector[Int])] = Vector(
    "R1" -> Vector(0, 1, 2, 3),
    "R2" -> Vector(3, 2, 0, 1),
    "R3" -> Vector(1, 3, 0, 2)
  )

  private def record(key: String, order: Vector[Int]): String = {
    val as = order.map(i => s"<author>${names(i)}</author>").mkString
    s"""<article key="journals/reorder/$key">$as<title>Reordered $key authors study.</title><year>2020</year></article>"""
  }

  private def xml(recs: Vector[String]): String =
    ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +: "<dblp>" +: recs :+ "</dblp>").mkString("\n")

  private def runOn(tag: String, recs: Vector[String]) = {
    val work = workDir(s"adversarial-reordered-$tag")
    val in = inputs(dblp = Vector(writeInput(work, "dblp/reordered.xml", xml(recs))))
    val conf = localConf(work, None)
    val m = manifest(baseParams())
    run(conf, m, work.resolve("curated"), in, Vector(Stage.Normalize, Stage.Teams))
    (conf, runRoot(work.resolve("curated"), m))
  }

  private lazy val forward = runOn("forward", orders.map { case (k, o) => record(k, o) })
  private lazy val backward = runOn("backward", orders.reverse.map { case (k, o) => record(k, o) })

  private def namePairs(conf: org.apache.hadoop.conf.Configuration, root: Path) = {
    val works = decodeAll[WorkRecord](lines(conf, new Path(root, "normalize/works")))
    val nameOf = works.flatMap(_.authors.map(a => a.mentionId -> a.rawName)).toMap
    val pairs = decodeAll[CoauthorPairCandidate](lines(conf, new Path(root, "teams/pairs")))
    (works, pairs.groupMap(_.workId)(p => Set(nameOf(p.endpointA), nameOf(p.endpointB))))
  }

  test("T16 permuted author lists: the same 6 name pairs and team size for every work") {
    val (conf, root) = forward
    val (works, byWork) = namePairs(conf, root)
    assertEquals(works.size, 3)
    val expected = names.combinations(2).map(_.toSet).toSet
    assertEquals(expected.size, 6)
    orders.foreach { case (k, order) =>
      val id = s"work:dblp:journals/reorder/$k"
      val w = works.find(_.workId == id).getOrElse(fail(s"no work $id"))
      assertEquals(w.authors.map(_.rawName), order.map(names), s"$k: author order kept as given")
      val ps = byWork.getOrElse(id, Vector.empty)
      assertEquals(ps.size, 6, s"$k: pair count")
      assertEquals(ps.toSet, expected, s"$k: name pairs differ under reordering")
    }
    val teams = decodeAll[PublicationTeam](lines(conf, new Path(root, "teams/teams")))
    assertEquals(teams.map(_.teamSize).toSet, Set(4))
  }

  test("T16 reversing the input record order changes no team or pair output") {
    val (c1, r1) = forward
    val (c2, r2) = backward
    Vector("teams/pairs", "teams/teams", "normalize/works").foreach { d =>
      val a = lines(c1, new Path(r1, d)).sorted
      val b = lines(c2, new Path(r2, d)).sorted
      assert(a.nonEmpty, s"$d: empty output (check would be vacuous)")
      assertEquals(b, a, s"$d depends on input record order")
    }
  }

  test("T16 CoauthorEdgeV2.build(a, b) equals build(b, a): one edge ID, canonical endpoints") {
    def rid(a: String) =
      ResearcherId.scholarChain("v1", "dblp-pid", a).fold(e => fail(e.message), identity)
    val x = rid("91/1")
    val y = rid("92/2")
    val w = TimeWindow(2018, 2022)
    val lineage = AnalysisLineage(
      "run:" + "b" * 64,
      "run:" + "c" * 64,
      "run:" + "d" * 64,
      "e" * 40,
      "f" * 64,
      "er-grouping.v1",
      "er-policy.v8",
      "v1",
      GroupingEvaluation.Unevaluated
    )
    def prov(s: String) =
      Provenance(
        SourceType.Derived,
        s,
        "analysis-2026",
        "run:" + "a" * 64,
        SchemaVersions.ResearcherAssignment,
        Some("graph-policy.v1"),
        None,
        None
      )
    val years = Vector(YearCount(2019, 1), YearCount(2021, 1))
    val works = Vector("work:dblp:conf/x/A", "work:dblp:conf/x/B")
    val ab = CoauthorEdgeV2.build(x, y, w, years, works, lineage, "graph-policy.v1", prov)
    val ba = CoauthorEdgeV2.build(y, x, w, years, works, lineage, "graph-policy.v1", prov)
    val (e1, e2) =
      (ab.fold(e => fail(e.toString), identity), ba.fold(e => fail(e.toString), identity))
    assertEquals(e2.edgeId, e1.edgeId)
    assertEquals((e2.researcherA, e2.researcherB), (e1.researcherA, e1.researcherB))
    assertEquals(e2.jointPublicationCount, e1.jointPublicationCount)
    assertEquals(e2, e1)
    assertEquals(e1.jointPublicationCount, 2L)
    assert(
      scholarchain.core.Canonical.compareUtf8(e1.researcherA, e1.researcherB) < 0,
      "endpoints are not in canonical order"
    )
    assertEquals(Set(e1.researcherA, e1.researcherB), Set(x, y))
  }
}
