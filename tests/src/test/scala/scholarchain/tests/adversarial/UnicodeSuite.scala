package scholarchain.tests.adversarial

import java.nio.charset.StandardCharsets
import java.util.Locale

import org.apache.hadoop.fs.Path

import scholarchain.core.{
  AuthorMention,
  CandidateBlock,
  CoauthorPairCandidate,
  MatchCandidate,
  PublicationTeam,
  WorkRecord
}
import scholarchain.entityresolution.EntityResolution
import scholarchain.hadoop.jobs.BlockMembership
import scholarchain.hadoop.pipeline.Stage

import Harness.*

/**
 * Phase 4 adversarial Unicode (README 1197, 2243) and cross-module checks: generated DBLP records
 * with NFC vs NFD names, Pöpper/Poepper/Popper, Cyrillic and Greek confusables, ZWJ/ZWNJ/BOM/RLM,
 * Arabic and Hebrew names, Turkish dotted/dotless i, and an astral-plane character placed across a
 * 64-byte split boundary, through the REAL five-stage pipeline with the REAL entity-resolution
 * keys. Non-ASCII code points are built from explicit hex values so the fixture is unambiguous.
 *
 * Cross-module: every curated line decodes with core `Json.decode`; a repeated run is skipped and
 * leaves outputs unchanged; an independent run under the Turkish default locale is line-identical.
 */
class UnicodeSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(15, "min")

  def cp(c: Int*): String = c.map(x => new String(Character.toChars(x))).mkString

  val SmallSplit = 64L

  /** key -> (author 0 name, coauthor name). */
  val records: Vector[(String, String, String)] = Vector(
    ("U1", "Christina P" + cp(0xf6) + "pper", "Ada Zed"),
    ("U2", "Christina Po" + cp(0x308) + "pper", "Bo Kay"),
    ("U3", "Christina Poepper", "Cy Lam"),
    ("U4", "Christina Popper", "Di Moss"),
    ("U5", "Christina P" + cp(0x3bf) + "pper", "Ed Nu"), // Greek omicron
    ("W1", "Jian Wang", "Fa Xi"),
    ("W2", "Jian W" + cp(0x430) + "ng", "Gu Pi"), // Cyrillic a
    ("W3", "Jian Wa" + cp(0x200d) + "ng", "Hu Rho"), // zero-width joiner
    ("W4", "Jian Wa" + cp(0x200c) + "ng", "Iv Tau"), // zero-width non-joiner
    ("W5", cp(0xfeff) + "Jian Wang", "Jo Chi"), // byte order mark
    ("W6", cp(0x200f) + "Jian Wang" + cp(0x200f), "Ku Psi"), // right-to-left marks
    (
      "R1",
      cp(0x645, 0x62d, 0x645, 0x62f, 0x20, 0x639, 0x644, 0x64a),
      cp(0x5d3, 0x5d5, 0x5d3, 0x20, 0x5db, 0x5d4, 0x5df)
    ),
    ("T1", cp(0x130) + "smail I" + cp(0x15f, 0x131) + "k", "Lu Mu"),
    ("T2", "Ismail Isik", "Ma Ny")
  )

  val astral: String = cp(0x20000) // CJK Extension B, a letter outside the BMP
  val astralTitle: String = "Astral " + cp(0x1d400) + " title " + cp(0x20001) + "."

  /** The fixture, padded so that the 4-byte astral author name straddles a 64-byte boundary. */
  lazy val xml: String = {
    def build(pad: Int): String = {
      val recs = records.zipWithIndex.map { case ((k, a, c), i) =>
        s"""<article key="journals/uni/$k"><author>$a</author><author>$c</author><title>Unicode probe $i.</title><year>2021</year></article>"""
      }
      val astralRec =
        " " * pad + s"""<article key="journals/uni/A1"><author>Wei $astral</author><author>Na Ox</author><title>$astralTitle</title><year>2022</year></article>"""
      ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +: "<dblp>" +: (recs :+ astralRec) :+ "</dblp>")
        .mkString("\n")
    }
    (0 until 64).iterator
      .map(build)
      .find { s =>
        val b = s.getBytes(StandardCharsets.UTF_8)
        val o = indexOf(b, astral)
        o >= 0 && o / SmallSplit != (o + 3) / SmallSplit
      }
      .getOrElse(sys.error("no padding puts the astral character across a split boundary"))
  }

  val stages =
    Vector(Stage.Normalize, Stage.ExactIds, Stage.Teams, Stage.Blocking, Stage.Candidates)
  val params = baseParams() ++ blockingParams(50, "drop") ++ candidateParams

  def runIn(name: String) = {
    val work = workDir(s"adversarial-unicode-$name")
    val in = inputs(dblp = Vector(writeInput(work, "dblp/unicode.xml", xml)))
    val conf = localConf(work, Some(SmallSplit))
    val m = manifest(params)
    val reports = run(conf, m, work.resolve("curated"), in, stages)
    printReport(s"adversarial-unicode-$name", reports)
    (conf, reports, runRoot(work.resolve("curated"), m), in, work)
  }

  lazy val main = runIn("root-locale")
  def conf = main._1
  def runDir = main._3
  lazy val works: Map[String, WorkRecord] =
    decodeAll[WorkRecord](lines(conf, new Path(runDir, "normalize/works")))
      .map(w => w.sourceRecordId.stripPrefix("journals/uni/") -> w)
      .toMap
  def m0(k: String): AuthorMention = works(k).authors(0)
  def key0(k: String): String = EntityResolution.researcherBlockingKeys(works(k), m0(k)).head

  lazy val memberships: Vector[BlockMembership] =
    (0 until 4).toVector
      .map(r => new Path(runDir, s"blocking/round-$r/members/researcher"))
      .flatMap(d => lines(conf, d))
      .map(upickle.default.read[BlockMembership](_))
  lazy val candidates: Vector[MatchCandidate] =
    decodeAll[MatchCandidate](lines(conf, new Path(runDir, "candidates/final/researcher")))
  def candidate(a: String, b: String): Option[MatchCandidate] =
    candidates.find(c => Set(c.sourceIdA, c.sourceIdB) == Set(m0(a).mentionId, m0(b).mentionId))
  def feature(c: MatchCandidate, suffix: String): Double =
    c.features.collectFirst { case (k, v) if k.endsWith(suffix) => v }.get

  test("every Unicode record is accepted; raw names are preserved byte for byte") {
    val n = stageOf(main._2, "normalize").counters
    assertEquals(cnt(n, "INPUT.dblp"), 15L)
    assertEquals(cnt(n, "ACCEPTED.dblp"), 15L)
    assertEquals(cnt(n, "REJECTED"), 0L)
    assertEquals(cnt(n, "FLAGGED"), 0L)
    records.foreach { case (k, a, c) =>
      assertEquals(works(k).authors.map(_.rawName), Vector(a, c), k)
    }
    assertEquals(works("A1").authors(0).rawName, s"Wei $astral", "astral char across a split")
    assertEquals(works("A1").title, astralTitle)
    val all = outputDirs(conf, runDir).flatMap { case (_, d) => lines(conf, d) }
    assert(!all.exists(_.contains(cp(0xfffd))), "no replacement character in any output")
  }

  test("NFC and NFD forms normalize identically; Poepper/Popper share the Pöpper block") {
    assertEquals(m0("U1").normalizedName, m0("U2").normalizedName)
    assertEquals(m0("U1").normalizedName, "christina p" + cp(0xf6) + "pper")
    Vector("U1", "U2", "U3", "U4").foreach(k => assertEquals(key0(k), "popper|c", k))
    val blockOf = memberships.map(mb => mb.memberId -> mb.blockId).toMap
    assertEquals(Vector("U1", "U2", "U3", "U4").map(k => blockOf(m0(k).mentionId)).distinct.size, 1)
    val c = candidate("U1", "U2").getOrElse(fail("no NFC/NFD candidate"))
    assertEquals(feature(c, "family_key_agree"), 1.0)
    assertEquals(feature(c, "family_exact"), 1.0)
  }

  test("confusables are never silently merged with the Latin name") {
    assertNotEquals(m0("W2").normalizedName, m0("W1").normalizedName)
    assertNotEquals(m0("U5").normalizedName, m0("U1").normalizedName)
    Vector(("W1", "W2"), ("U1", "U5"), ("U4", "U5")).foreach { case (latin, conf2) =>
      println(s"[adversarial-unicode] keys $latin=${key0(latin)} $conf2=${key0(conf2)}")
      candidate(latin, conf2).foreach { c =>
        assertEquals(feature(c, "family_exact"), 0.0, s"$latin/$conf2 asserted identical")
      }
    }
    val links = lines(conf, new Path(runDir, "exact-ids/links"))
    assert(!links.exists(_.contains("\"entityKind\":\"researcher\"")), "no identifier was invented")
  }

  test("invisible format characters, RTL text and Turkish i give deterministic keys") {
    Vector("W1", "W5", "W6").foreach(k => assertEquals(key0(k), "wang|j", k))
    assertEquals(key0("T1"), key0("T2"))
    assertEquals(key0("T1"), "isik|i")
    println(s"[adversarial-unicode] key R1=${key0("R1")}")
    val r1 = works("R1").authors
    r1.foreach(m => assert(EntityResolution.researcherBlockingKeys(works("R1"), m).nonEmpty))
    // Every researcher membership's block key is the mention's key at that level, recomputed.
    val mentions = works.values.flatMap(w => w.authors.map(m => m.mentionId -> (w, m))).toMap
    memberships.filter(_.memberId.startsWith("mention:")).foreach { mb =>
      val (w, m) = mentions(mb.memberId)
      assertEquals(EntityResolution.researcherBlockingKeys(w, m)(mb.level), mb.blockKey)
    }
    assertEquals(
      memberships.map(_.memberId).toSet,
      mentions.keySet,
      "every mention blocked once (maxBlockSize 50)"
    )
  }

  // TE-P5-00 (Phase 4 accepted deferral 1): er-blocking.v1 folded ZWJ/ZWNJ to spaces, so W3/W4
  // keyed as "ng|j" (family "ng") and never met W1. Regression-sensitive: every assertion on W3/W4
  // keys, blocks, and candidates below fails under the v1 fold.
  test("ZWJ and ZWNJ inside Wang: key wang|j, same pipeline block and candidate as W1, raw kept") {
    val blockOf = memberships.map(mb => (mb.memberId, mb.level) -> mb).toMap
    val w1 = blockOf((m0("W1").mentionId, 0))
    assertEquals(w1.blockKey, "wang|j")
    Vector("W3" -> 0x200d, "W4" -> 0x200c).foreach { case (k, fmt) =>
      assertEquals(key0(k), "wang|j", k)
      assertEquals(
        EntityResolution.researcherBlockingKeys(works(k), m0(k)).take(2),
        Vector("wang|j", "wang|j|jian"),
        k
      )
      val mb = blockOf.getOrElse((m0(k).mentionId, 0), fail(s"$k has no level-0 membership"))
      assertEquals(mb.blockId, w1.blockId, s"$k is in W1's pipeline block")
      assertEquals(mb.blockKey, "wang|j", k)
      assertEquals(mb.blockingVersion, EntityResolution.blockingKeysVersion, k)
      val c = candidate("W1", k).getOrElse(fail(s"no W1/$k candidate"))
      assertEquals(feature(c, "name.family_exact"), 1.0, k)
      assertEquals(feature(c, "name.first_given_agree"), 1.0, k)
      // The curated rawName keeps the original format character, byte for byte.
      val expected = "Jian Wa" + cp(fmt) + "ng"
      val raw = m0(k).rawName
      assertEquals(raw, expected, k)
      assert(
        java.util.Arrays.equals(
          raw.getBytes(StandardCharsets.UTF_8),
          expected.getBytes(StandardCharsets.UTF_8)
        ),
        k
      )
      assert(raw.contains(cp(fmt)), s"$k rawName lost U+${fmt.toHexString}")
      val mention = decodeAll[AuthorMention](lines(conf, new Path(runDir, "normalize/mentions")))
        .find(_.mentionId == m0(k).mentionId)
        .getOrElse(fail(s"$k mention not in normalize/mentions"))
      assertEquals(mention.rawName, expected, s"$k in normalize/mentions")
    }
  }

  test("cross-module: curated lines decode with Json.decode and reference existing records") {
    val counts = checkContracts(conf, runDir, runIdOf(manifest(params)))
    println(s"[adversarial-unicode] contract-checked lines: $counts")
    assert(counts.exists(_._1.endsWith("/candidates/final/researcher")))
    val mentionIds = decodeAll[AuthorMention](lines(conf, new Path(runDir, "normalize/mentions")))
      .map(_.mentionId)
      .toSet
    assertEquals(mentionIds, works.values.flatMap(_.authors.map(_.mentionId)).toSet)
    val pairs = decodeAll[CoauthorPairCandidate](lines(conf, new Path(runDir, "teams/pairs")))
    assert(pairs.forall(p => mentionIds(p.endpointA) && mentionIds(p.endpointB)))
    val teams = decodeAll[PublicationTeam](lines(conf, new Path(runDir, "teams/teams")))
    assert(teams.forall(_.unresolvedMentionIds.forall(mentionIds)))
    val blocks = (0 until 4).toVector.flatMap(r =>
      Vector("researcher", "work").flatMap(e =>
        decodeAll[CandidateBlock](lines(conf, new Path(runDir, s"blocking/round-$r/blocks/$e")))
      )
    )
    val workIds = works.values.map(_.workId).toSet
    assert(blocks.forall(_.memberIds.forall(id => mentionIds(id) || workIds(id))))
    val inBlock = blocks.map(bl => (bl.level, bl.blockKey) -> bl.memberIds.toSet).toMap
    candidates.foreach { c =>
      assert(
        c.blockKeys.exists(bk =>
          inBlock(bk.level, bk.key)(c.sourceIdA) && inBlock(bk.level, bk.key)(c.sourceIdB)
        )
      )
    }
  }

  test("cross-module: a repeated run is skipped with unchanged outputs (idempotent)") {
    val (c, reports, run0, in, work) = main
    val before = snapshot(c, run0)
    val again = Harness.run(c, manifest(params), work.resolve("curated"), in, stages)
    assertEquals(again.map(_.status), Vector.fill(5)("skipped"))
    assertEquals(again.map(_.counters), reports.map(_.counters))
    assertEquals(snapshot(c, run0), before)
  }

  test("cross-module: an independent run under the Turkish default locale is line-identical") {
    val saved = Locale.getDefault
    val other =
      try {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        runIn("tr-locale")
      } finally Locale.setDefault(saved)
    val a = snapshot(conf, runDir)
    val b = snapshot(other._1, other._3)
    assertEquals(b.keySet, a.keySet)
    a.foreach { case (k, v) =>
      // spill is a between-round intermediate in arrival order (documented); all else is exact.
      if (k.endsWith("/spill")) assertEquals(b(k).sorted, v.sorted, k)
      else assertEquals(b(k), v, k)
    }
  }
}
