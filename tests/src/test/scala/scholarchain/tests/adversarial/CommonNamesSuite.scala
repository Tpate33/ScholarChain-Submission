package scholarchain.tests.adversarial

import scala.collection.immutable.SortedMap

import org.apache.hadoop.fs.Path

import scholarchain.core.{CandidateBlock, MatchCandidate, MatchEntityKind, MentionId}
import scholarchain.hadoop.pipeline.Stage

import Harness.*

/**
 * Phase 4 adversarial common names (README 1140-1147, 2245): 26 generated DBLP works in which many
 * distinct researchers share `wang|j` ("Jun Wang", "J. Wang", "Jing Wang") or `zhang|w` ("Wei
 * Zhang"), run through the REAL normalize, blocking (all rounds) and candidate jobs with the REAL
 * entity-resolution keys and maxBlockSize 4.
 *
 * Hand-derived expectation (researcher members, maxBlockSize 4):
 *   - round 0: `wang|j` 14 and `zhang|w` 12 are oversize and refined (26 spilled);
 *   - round 1: `wang|j|jun` 5, `wang|j|j` 6, `zhang|w|wei` 12 oversize (23 spilled); `wang|j|jing`
 *     3 is a block;
 *   - round 2: `wang|j|jun|zed` 3, `|kay` 1, `|lam` 1 and 12 `zhang|w|wei|<anchor>` blocks;
 *     `wang|j|j|moss` 6 oversize (6 spilled);
 *   - round 3: `wang|j|j|moss|2010` 6 is still oversize at the last level: dropped (drop policy) or
 *     the job fails (fail policy).
 *
 * So OVERSIZE_BLOCK = 7, BLOCK_MEMBERS_REFINED = 55, BLOCK_MEMBERS_DROPPED = 6, 40 researcher and
 * 26 work blocks, and 9 researcher candidates (three blocks of size 3).
 */
class CommonNamesSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  final case class W(key: String, year: Int, authors: Vector[String])

  val works: Vector[W] = {
    val a = Vector(
      W("A1", 2001, Vector("Jun Wang", "Ada Zed")),
      W("A2", 2002, Vector("Jun Wang", "Ada Zed")),
      W("B1", 2003, Vector("Jun Wang", "Ada Zed")),
      W("C1", 2004, Vector("Jun Wang", "Bo Kay")),
      W("C2", 2004, Vector("Jun Wang", "Cy Lam"))
    )
    val d = Vector("Dee", "Eli", "Fay", "Gus", "Hy", "Ike").zipWithIndex.map { case (g, i) =>
      W(s"D${i + 1}", 2010, Vector("J. Wang", s"$g Moss"))
    }
    val e = Vector("Gil Nu", "Hal Xi", "Ivy Pi").zipWithIndex.map { case (c, i) =>
      W(s"E${i + 1}", 2011 + i, Vector("Jing Wang", c))
    }
    val f = Vector(
      "Kappa",
      "Lambda",
      "Sigma",
      "Tau",
      "Omega",
      "Delta",
      "Gamma",
      "Theta",
      "Iota",
      "Rho",
      "Chi",
      "Psi"
    ).zipWithIndex.map { case (fam, i) => W(s"F${i + 1}", 2015, Vector("Wei Zhang", s"Ann $fam")) }
    a ++ d ++ e ++ f
  }

  def xml: String = {
    val recs = works.zipWithIndex.map { case (w, i) =>
      val as = w.authors.map(n => s"<author>$n</author>").mkString
      s"""<article key="journals/cn/${w.key}">$as<title>Zeta$i Omega$i common name study.</title><year>${w.year}</year></article>"""
    }
    ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +: "<dblp>" +: recs :+ "</dblp>").mkString("\n")
  }

  def mention(key: String, pos: Int): String =
    MentionId.of(s"work:dblp:journals/cn/$key", pos).fold(e => fail(e.message), identity)

  val allStages = Vector(Stage.Normalize, Stage.Teams, Stage.Blocking, Stage.Candidates)

  lazy val drop = {
    val work = workDir("adversarial-common-names-drop")
    val in = inputs(dblp = Vector(writeInput(work, "dblp/common.xml", xml)))
    val conf = localConf(work, None)
    val m = manifest(baseParams() ++ blockingParams(4, "drop") ++ candidateParams)
    val t0 = System.nanoTime()
    val reports = run(conf, m, work.resolve("curated"), in, allStages)
    println(f"[adversarial-common-names] drop run took ${(System.nanoTime() - t0) / 1e9}%.1f s")
    printReport("adversarial-common-names", reports)
    (conf, reports, runRoot(work.resolve("curated"), m), runIdOf(m))
  }

  def b: SortedMap[String, Long] = stageOf(drop._2, "blocking").counters
  def roundDirs(sub: String): Vector[Path] =
    (0 until 4).toVector
      .map(r => new Path(drop._3, s"blocking/round-$r/$sub"))
      .filter(fileExists(drop._1, _))
  lazy val blocks: Vector[CandidateBlock] =
    roundDirs("blocks/researcher").flatMap(d => decodeAll[CandidateBlock](lines(drop._1, d))) ++
      roundDirs("blocks/work").flatMap(d => decodeAll[CandidateBlock](lines(drop._1, d)))
  lazy val candidates: Vector[MatchCandidate] =
    Vector("researcher", "work").flatMap(e =>
      decodeAll[MatchCandidate](lines(drop._1, new Path(drop._3, s"candidates/final/$e")))
    )

  test("oversize common-name blocks are counted, refined level by level, and dropped at the end") {
    assertEquals(cnt(b, "INPUT.researcher"), 52L)
    assertEquals(cnt(b, "INPUT.work"), 26L)
    assertEquals(cnt(b, "OVERSIZE_BLOCK"), 7L)
    assertEquals(cnt(b, "BLOCK_MEMBERS_REFINED.researcher"), 55L)
    assertEquals(cnt(b, "BLOCK_MEMBERS_DROPPED.researcher"), 6L)
    assertEquals(cnt(b, "BLOCK_MEMBERS_DROPPED.work"), 0L)
    assertEquals(cnt(b, "REFINE_INPUT"), 55L, "every refined member is re-read exactly once")
    val researcher = blocks.filter(_.entityKind == MatchEntityKind.Researcher)
    assertEquals(researcher.size, 40)
    assertEquals(blocks.count(_.entityKind == MatchEntityKind.Work), 26)
    assertEquals(researcher.map(_.size).sum + 6, 52, "every member in one block or dropped")
    assertEquals(
      researcher.groupMapReduce(_.level)(_ => 1)(_ + _),
      Map(0 -> 24, 1 -> 1, 2 -> 15),
      "levels of the emitted researcher blocks"
    )
    assert(blocks.forall(bl => bl.size <= 4 && bl.maxBlockSize == 4), "bounded block sizes")
    assertEquals(blocks.flatMap(_.memberIds).distinct.size, blocks.map(_.size).sum, "disjoint")
    assert(!researcher.exists(_.blockKey.startsWith("wang|j|j|moss")), "never-emitted group")
    val st = stageOf(drop._2, "blocking").stats
    assertEquals(st("largestGroup.researcher").value, 14L)
    assertEquals(st("largestGroup.researcher").id, "researcher:0:wang|j")
    assertEquals(st("largestBlock.researcher").value, 3L)
  }

  test("each oversize group has one flagged oversize-block record with its policy outcome") {
    val rej =
      roundDirs("rejects").flatMap(d => rejects(drop._1, d)).filter(_.reason == "oversize-block")
    assertEquals(rej.size, 7)
    assert(rej.forall(r => r.disposition == "flagged" && r.stage == "blocking"))
    val byLabel = rej.map(r => r.locator -> r.detail).toMap
    assertEquals(
      byLabel.keySet,
      Set(
        "researcher:0:wang|j",
        "researcher:0:zhang|w",
        "researcher:1:wang|j|jun",
        "researcher:1:wang|j|j",
        "researcher:1:zhang|w|wei",
        "researcher:2:wang|j|j|moss",
        "researcher:3:wang|j|j|moss|2010"
      )
    )
    val top = byLabel("researcher:0:wang|j")
    assert(
      Vector("size=14", "refinedToNextLevel=14", "dropped=0", "policy=drop").forall(top.contains),
      top.toString
    )
    val last = byLabel("researcher:3:wang|j|j|moss|2010")
    assert(
      Vector("size=6", "refinedToNextLevel=0", "dropped=6").forall(last.contains),
      last.toString
    )
  }

  test("same-person and different-person candidates share a refined block; one per pair per run") {
    val k = stageOf(drop._2, "candidates").counters
    val researcher = candidates.filter(_.entityKind == MatchEntityKind.Researcher)
    assertEquals(researcher.size, 9)
    assertEquals(candidates.count(_.entityKind == MatchEntityKind.Work), 0)
    assertEquals(cnt(k, "CANDIDATE_PAIRS"), 9L)
    assertEquals(
      candidates.map(c => (c.sourceIdA, c.sourceIdB)).distinct.size,
      candidates.size,
      "one MatchCandidate per canonical pair per run"
    )
    def pair(x: String, y: String) =
      researcher.find(c => Set(c.sourceIdA, c.sourceIdB) == Set(x, y))
    // A1 and A2: the same Jun Wang on two works (same coauthor).
    val same = pair(mention("A1", 0), mention("A2", 0)).getOrElse(fail("no A1/A2 candidate"))
    assertEquals(same.blockKeys.map(bk => (bk.level, bk.key)), Vector((2, "wang|j|jun|zed")))
    // A1 and B1: a different Jun Wang with the same coauthor anchor.
    val diff = pair(mention("A1", 0), mention("B1", 0)).getOrElse(fail("no A1/B1 candidate"))
    assertEquals(diff.blockKeys.map(_.level), Vector(2))
    // Jun Wang C1 (anchor kay) is in a singleton block: no candidate with A1.
    assertEquals(pair(mention("A1", 0), mention("C1", 0)), None)
    // The dropped J. Wang group yields no candidates.
    assert(
      !researcher.exists(c =>
        (1 to 6).exists(i => Set(c.sourceIdA, c.sourceIdB).contains(mention(s"D$i", 0)))
      )
    )
    assert(candidates.forall(_.features.values.forall(v => !v.isNaN && !v.isInfinite)))
    checkContracts(drop._1, drop._3, drop._4)
  }

  test("the fail policy fails the blocking stage on the same input and never commits it") {
    val work = workDir("adversarial-common-names-fail")
    val in = inputs(dblp = Vector(writeInput(work, "dblp/common.xml", xml)))
    val conf = localConf(work, None)
    val m = manifest(baseParams() ++ blockingParams(4, "fail"))
    val res = tryRun(conf, m, work.resolve("curated"), in, Vector(Stage.Normalize, Stage.Blocking))
    assert(res.left.exists(_.exists(_.contains("failed"))), res.toString)
    val root = runRoot(work.resolve("curated"), m)
    assert(fileExists(conf, new Path(root, "normalize/_SUCCESS")))
    assert(!fileExists(conf, new Path(root, "blocking/_SUCCESS")), "failed stage not committed")
  }
}
