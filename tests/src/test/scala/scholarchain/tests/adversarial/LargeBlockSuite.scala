package scholarchain.tests.adversarial

import scala.collection.immutable.SortedMap

import org.apache.hadoop.fs.Path

import scholarchain.core.{CandidateBlock, MatchEntityKind}
import scholarchain.hadoop.pipeline.Stage

import Harness.*

/**
 * TE-P11-02 T17 (Phase 11 category 5, very large blocking groups) through the REAL normalize,
 * teams, blocking (all rounds) and candidate jobs with the REAL entity-resolution keys,
 * maxBlockSize 64 and the drop policy. Generated DBLP input, two-author works:
 *
 *   - group A: 1000 works "Wei Zhang" + "Ann Kappa", years 2000 + (i mod 20) (50 works per year);
 *   - group B: 200 works "Li Wang" + "Bo Chen", all in 2015.
 *
 * Hand derivation (refinement levels as documented by CommonNamesSuite: surname|initial, + given
 * name, + coauthor anchor, + year):
 *   - A: `zhang|w` 1000, `zhang|w|wei` 1000, `zhang|w|wei|kappa` 1000 are oversize and refined; at
 *     the last level `zhang|w|wei|kappa|<year>` 50 each: 20 blocks of 50. Same for `kappa|a|...`. 3
 *     oversize groups and 3000 refined members per name, 40 blocks of 50 in all;
 *   - B: the same three levels refined (600 per name), and at the last level `wang|l|li|chen|2015`
 *     and `chen|b|bo|wang|2015` (200 each) are still oversize: dropped.
 *
 * So OVERSIZE_BLOCK = 6 + 8 = 14, BLOCK_MEMBERS_REFINED.researcher = 6000 + 1200 = 7200,
 * BLOCK_MEMBERS_DROPPED.researcher = 400, 40 researcher blocks of 50, and exactly 40 * C(50, 2) =
 * 49000 researcher candidates: never C(1000, 2) = 499500 per name. The candidate output is bounded
 * by the block size, not by the group size.
 */
class LargeBlockSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(15, "min")

  private val MaxBlock = 64

  private def xml: String = {
    val a = (0 until 1000).map { i =>
      s"""<article key="journals/lb/A$i"><author>Wei Zhang</author><author>Ann Kappa</author><title>Alpha$i Beta$i large block study.</title><year>${2000 + i % 20}</year></article>"""
    }
    val b = (0 until 200).map { i =>
      s"""<article key="journals/lb/B$i"><author>Li Wang</author><author>Bo Chen</author><title>Gamma$i Delta$i large block study.</title><year>2015</year></article>"""
    }
    ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +: "<dblp>" +: (a ++ b) :+ "</dblp>")
      .mkString("\n")
  }

  private lazy val result = {
    val work = workDir("adversarial-large-block")
    val in = inputs(dblp = Vector(writeInput(work, "dblp/large.xml", xml)))
    val conf = localConf(work, None)
    val m = manifest(baseParams() ++ blockingParams(MaxBlock, "drop") ++ candidateParams)
    val t0 = System.nanoTime()
    val reports = run(
      conf,
      m,
      work.resolve("curated"),
      in,
      Vector(Stage.Normalize, Stage.Teams, Stage.Blocking, Stage.Candidates)
    )
    val secs = (System.nanoTime() - t0) / 1e9
    println(f"[adversarial-large-block] pipeline took $secs%.1f s")
    printReport("adversarial-large-block", reports)
    (conf, reports, runRoot(work.resolve("curated"), m), secs)
  }

  private def b: SortedMap[String, Long] = stageOf(result._2, "blocking").counters

  private def blocks: Vector[CandidateBlock] =
    (0 until 6).toVector
      .map(r => new Path(result._3, s"blocking/round-$r/blocks/researcher"))
      .filter(fileExists(result._1, _))
      .flatMap(d => decodeAll[CandidateBlock](lines(result._1, d)))

  test(
    "T17 a 1000-member name group is refined to bounded blocks; the last-level oversize is dropped"
  ) {
    assertEquals(cnt(b, "INPUT.researcher"), 2400L)
    assertEquals(cnt(b, "INPUT.work"), 1200L)
    assertEquals(cnt(b, "OVERSIZE_BLOCK"), 14L)
    assertEquals(cnt(b, "BLOCK_MEMBERS_REFINED.researcher"), 7200L)
    assertEquals(cnt(b, "BLOCK_MEMBERS_DROPPED.researcher"), 400L)
    assertEquals(cnt(b, "BLOCK_MEMBERS_DROPPED.work"), 0L)
    val rb = blocks.filter(_.entityKind == MatchEntityKind.Researcher)
    assertEquals(rb.size, 40)
    assert(
      rb.forall(x => x.size == 50 && x.maxBlockSize == MaxBlock),
      rb.map(_.size).distinct.toString
    )
    assertEquals(rb.map(_.size).sum + 400, 2400, "every member is in exactly one block or dropped")
    assertEquals(rb.flatMap(_.memberIds).distinct.size, 2000, "blocks are disjoint")
    val st = stageOf(result._2, "blocking").stats
    assertEquals(st("largestGroup.researcher").value, 1000L)
    assertEquals(st("largestBlock.researcher").value, 50L)
  }

  test("T17 candidate output is bounded by the block size (no quadratic expansion of the group)") {
    val (conf, reports, root, secs) = result
    val k = stageOf(reports, "candidates").counters
    var researcher = 0L
    val total =
      foreachLine(conf, new Path(root, "candidates/final/researcher"))(_ => researcher += 1)
    assertEquals(total, researcher)
    assertEquals(researcher, 40L * 50 * 49 / 2, "exactly C(50,2) candidates per block")
    assertEquals(cnt(k, "CANDIDATE_PAIRS"), researcher)
    assert(researcher < 1000L * 999 / 2, "a group-size quadratic expansion")
    assert(secs < 600.0, s"bounded runtime: $secs s")
  }
}
