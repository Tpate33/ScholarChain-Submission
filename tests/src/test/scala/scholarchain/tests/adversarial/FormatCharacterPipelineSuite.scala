package scholarchain.tests.adversarial

import java.nio.charset.StandardCharsets

import org.apache.hadoop.fs.Path

import scholarchain.core.{
  AuthorMention,
  CandidateBlock,
  MatchCandidate,
  MatchEntityKind,
  WorkRecord
}
import scholarchain.entityresolution.{EntityResolution, TextFolding, TitleText}
import scholarchain.hadoop.jobs.BlockMembership
import scholarchain.hadoop.pipeline.Stage

import Harness.*

/**
 * TE-P5-00 (Phase 4 human gate, accepted deferral 1): Unicode format characters (general category
 * Cf) inside names and titles. Under `er-blocking.v1` / `er-features.v1` they were folded to spaces
 * and split family keys, given names, and title tokens; `er-blocking.v2` / `er-features.v2` delete
 * them. Every case is a generated DBLP record driven through the REAL five-stage pipeline with the
 * REAL entity-resolution key source, plus property checks on the public entity-resolution API.
 * Non-ASCII code points are built from explicit hex values.
 *
 * Regression sensitivity (against the v1 fold, analysed by hand; see each test): tests marked
 * `[regression]` fail when a Cf character becomes a separator; tests marked `[guard]` hold under
 * both v1 and v2 and protect against over-deletion or crashes introduced by the change.
 */
class FormatCharacterPipelineSuite extends munit.FunSuite {

  override val munitTimeout = scala.concurrent.duration.Duration(15, "min")

  def cp(c: Int*): String = c.map(x => new String(Character.toChars(x))).mkString

  /** The format characters named in the work order. */
  val formats: Vector[Int] = Vector(0x00ad, 0x200b, 0x200c, 0x200d, 0x200e, 0x200f) ++
    (0x202a to 0x202e) ++ Vector(0x2060) ++ (0x2066 to 0x2069) ++ Vector(0x061c, 0xfeff)

  def hex(c: Int): String = f"$c%04X"

  /** (record key, authors, title). Every non-group author and title is unique. */
  lazy val records: Vector[(String, Vector[String], String)] = {
    var co = 0
    def coauthor(): String = { co += 1; s"Ann Kox$co" }
    def own(k: String): String = s"Zeta ${k.toLowerCase} study."
    val fam = ("F0000", Vector("Jian Wang", coauthor()), own("F0000")) +:
      formats.map(c => (s"F${hex(c)}", Vector("Jian Wa" + cp(c) + "ng", coauthor()), own(s"F$c")))
    val giv = ("G0000", Vector("Meiling Zhao", coauthor()), own("G0000")) +:
      formats.map(c =>
        (s"G${hex(c)}", Vector("Mei" + cp(c) + "ling Zhao", coauthor()), own(s"G$c"))
      )
    val tit = ("T0000", Vector(coauthor()), "Graph mining methods.") +:
      formats.map(c => (s"T${hex(c)}", Vector(coauthor()), "Gra" + cp(c) + "ph mining methods."))
    val other = Vector(
      // a leading BOM on a DBLP author (and on its coauthor)
      ("B1", Vector(cp(0xfeff) + "Jian Wang", cp(0xfeff) + coauthor()), own("B1")),
      // a trailing Cf after the editorial note must not hide the note
      ("TN1", Vector(coauthor()), "Graph mining methods (Best Paper)" + cp(0x200b) + "."),
      // Cf mixed with NFD combining marks (baseline precomposed)
      ("M0", Vector("Christina P" + cp(0xf6) + "pper", coauthor()), own("M0")),
      ("M1", Vector("Christina Po" + cp(0x308, 0x200b) + "pper", coauthor()), own("M1")),
      ("M2", Vector("Christina Po" + cp(0x200d, 0x308) + "pper", coauthor()), own("M2")),
      ("M3", Vector("Chri" + cp(0xad) + "stina Po" + cp(0x308) + "pper", coauthor()), own("M3")),
      // Cf adjacent to a real hyphen, and the soft hyphen
      ("H0", Vector("Ana Garcia-Molina", coauthor()), own("H0")),
      ("H1", Vector("Ana Garcia-" + cp(0x200b) + "Molina", coauthor()), own("H1")),
      ("H2", Vector("Ana Garcia" + cp(0x200b) + "-Molina", coauthor()), own("H2")),
      ("H3", Vector("Ana Garcia" + cp(0xad) + "Molina", coauthor()), own("H3")),
      ("H4", Vector("Ana Garcia" + cp(0xad) + "-Molina", coauthor()), own("H4")),
      // a nobiliary particle and a DBLP homonym number split by Cf
      ("P0", Vector("Ludwig van Beethoven", coauthor()), own("P0")),
      ("P1", Vector("Ludwig va" + cp(0x2060) + "n Beethoven", coauthor()), own("P1")),
      ("S0", Vector("Christopher Frost 0001", coauthor()), own("S0")),
      ("S1", Vector("Christopher Frost 00" + cp(0x200b) + "01", coauthor()), own("S1")),
      // confusables with an embedded Cf: Cyrillic a, Greek omicron
      ("C1", Vector("Jian W" + cp(0x430, 0x200c) + "ng", coauthor()), own("C1")),
      ("C2", Vector("Christina P" + cp(0x3bf, 0x200b) + "pper", coauthor()), own("C2")),
      // names made only of format characters (and one space)
      ("N1", Vector(cp(0x200b, 0x200d, 0xfeff, 0x2060), "Lee Nox"), own("N1")),
      ("N2", Vector(cp(0xad) + " " + cp(0x200e, 0x202e), "Lee Noy"), own("N2")),
      // documented acceptance: a Cf alone between two words joins them; with a space it does not
      ("J1", Vector("Jun" + cp(0x200b) + "Wang", coauthor()), own("J1")),
      ("J2", Vector("Wang" + cp(0x200b) + " Jun", coauthor()), own("J2"))
    )
    fam ++ giv ++ tit ++ other
  }

  lazy val xml: String = {
    val recs = records.map { case (k, as, t) =>
      val authors = as.map(a => s"<author>$a</author>").mkString
      s"""<article key="journals/cf/$k">$authors<title>$t</title><year>2021</year></article>"""
    }
    ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +: "<dblp>" +: recs :+ "</dblp>").mkString("\n")
  }

  val stages =
    Vector(Stage.Normalize, Stage.ExactIds, Stage.Teams, Stage.Blocking, Stage.Candidates)
  val params = baseParams() ++ blockingParams(50, "drop") ++ candidateParams

  lazy val main = {
    val work = workDir("adversarial-format-characters")
    val in = inputs(dblp = Vector(writeInput(work, "dblp/cf.xml", xml)))
    val conf = localConf(work, None)
    val m = manifest(params)
    val reports = run(conf, m, work.resolve("curated"), in, stages)
    printReport("adversarial-cf", reports)
    (conf, reports, runRoot(work.resolve("curated"), m), runIdOf(m))
  }
  def conf = main._1
  def runDir: Path = main._3

  lazy val works: Map[String, WorkRecord] =
    decodeAll[WorkRecord](lines(conf, new Path(runDir, "normalize/works")))
      .map(w => w.sourceRecordId.stripPrefix("journals/cf/") -> w)
      .toMap
  def m0(k: String): AuthorMention = works(k).authors(0)
  def keys(k: String): Vector[String] = EntityResolution.researcherBlockingKeys(works(k), m0(k))

  lazy val memberships: Vector[BlockMembership] =
    (0 until 4).toVector
      .flatMap(r =>
        Vector("researcher", "work").map(e => new Path(runDir, s"blocking/round-$r/members/$e"))
      )
      .flatMap(d => lines(conf, d))
      .map(upickle.default.read[BlockMembership](_))
  lazy val blockOf: Map[String, BlockMembership] =
    memberships.filter(_.level == 0).map(mb => mb.memberId -> mb).toMap
  lazy val blocks: Vector[CandidateBlock] = (0 until 4).toVector.flatMap(r =>
    Vector("researcher", "work").flatMap(e =>
      decodeAll[CandidateBlock](lines(conf, new Path(runDir, s"blocking/round-$r/blocks/$e")))
    )
  )
  lazy val candidates: Vector[MatchCandidate] =
    Vector("researcher", "work").flatMap(e =>
      decodeAll[MatchCandidate](lines(conf, new Path(runDir, s"candidates/final/$e")))
    )
  def candidateOf(a: String, b: String): Option[MatchCandidate] =
    candidates.find(c => Set(c.sourceIdA, c.sourceIdB) == Set(a, b))
  def mentionCandidate(a: String, b: String): MatchCandidate =
    candidateOf(m0(a).mentionId, m0(b).mentionId).getOrElse(fail(s"no candidate $a/$b"))
  def workCandidate(a: String, b: String): MatchCandidate =
    candidateOf(works(a).workId, works(b).workId).getOrElse(fail(s"no work candidate $a/$b"))
  def feat(c: MatchCandidate, name: String): Double =
    c.features.getOrElse(name, fail(s"feature $name missing in ${c.features.keySet}"))

  def groupKeys(prefix: String): Vector[String] =
    (s"${prefix}0000" +: formats.map(c => s"$prefix${hex(c)}"))

  // ------------------------------------------------------------------ normalize

  test("[guard] every record is accepted; raw names and titles are byte-identical to the input") {
    val n = stageOf(main._2, "normalize").counters
    assertEquals(cnt(n, "INPUT.dblp"), records.size.toLong)
    assertEquals(cnt(n, "ACCEPTED.dblp"), records.size.toLong)
    assertEquals(cnt(n, "REJECTED"), 0L)
    records.foreach { case (k, as, t) =>
      val w = works.getOrElse(k, fail(s"$k not accepted"))
      assertEquals(w.authors.map(_.rawName), as, k)
      w.authors.zip(as).foreach { case (m, a) =>
        assert(
          java.util.Arrays.equals(
            m.rawName.getBytes(StandardCharsets.UTF_8),
            a.getBytes(StandardCharsets.UTF_8)
          ),
          s"$k ${m.rawName}"
        )
      }
      assertEquals(w.title, t, k)
    }
    formats.foreach { c =>
      assert(m0(s"F${hex(c)}").rawName.contains(cp(c)), s"F U+${hex(c)} kept in rawName")
      assert(works(s"T${hex(c)}").title.contains(cp(c)), s"T U+${hex(c)} kept in title")
    }
  }

  // ------------------------------------------------------------------ family / given / title

  test("[regression] Cf inside the family name: wang|j keys, one pipeline block, family_exact") {
    // v1: "Wa ng" parsed as given (jian, wa), family "ng" -> key "ng|j": every assertion fails.
    val base = keys("F0000")
    assertEquals(base.take(2), Vector("wang|j", "wang|j|jian"))
    val group = groupKeys("F") :+ "B1"
    val ids = group.map(k => blockOf.getOrElse(m0(k).mentionId, fail(s"$k not blocked")))
    group.zip(ids).foreach { case (k, mb) =>
      assertEquals(keys(k).take(2), base.take(2), k)
      assertEquals(mb.blockKey, "wang|j", k)
    }
    assertEquals(ids.map(_.blockId).distinct.size, 1, "one level-0 block for the whole group")
    groupKeys("F").tail.foreach { k =>
      val c = mentionCandidate("F0000", k)
      assertEquals(feat(c, "name.family_exact"), 1.0, k)
      assertEquals(feat(c, "name.family_key_agree"), 1.0, k)
      assertEquals(feat(c, "name.first_given_agree"), 1.0, k)
      assertEquals(feat(c, "name.best_variant_sim"), 1.0, k)
      assertEquals(feat(c, "name.token_jaccard"), 1.0, k)
    }
  }

  test("[regression] Cf inside the given name: zhao|m|meiling, one block, first_given_agree") {
    // v1: "Mei ling" gave given parts (mei, ling) -> level 1 "zhao|m|mei"; given_sim < 1.
    val base = keys("G0000")
    assertEquals(base.take(2), Vector("zhao|m", "zhao|m|meiling"))
    groupKeys("G").foreach(k => assertEquals(keys(k).take(2), base.take(2), k))
    assertEquals(groupKeys("G").map(k => blockOf(m0(k).mentionId).blockId).distinct.size, 1)
    groupKeys("G").tail.foreach { k =>
      val c = mentionCandidate("G0000", k)
      assertEquals(feat(c, "name.first_given_agree"), 1.0, k)
      assertEquals(feat(c, "name.given_sim"), 1.0, k)
      assertEquals(feat(c, "name.token_jaccard"), 1.0, k)
    }
  }

  test("[regression] Cf inside a work title: graph_mining keys, one work block, title.exact") {
    // v1: "Gra ph mining methods" -> level 0 "gra_ph"; TN1 kept "best paper" (Cf after ")").
    val base = EntityResolution.workBlockingKeys(works("T0000"))
    assertEquals(
      base.take(3),
      Vector("graph_mining", "graph_mining|methods", "graph_mining|methods|2021")
    )
    val group = groupKeys("T") :+ "TN1"
    group.foreach { k =>
      assertEquals(EntityResolution.workBlockingKeys(works(k)).take(3), base.take(3), k)
      assertEquals(EntityResolution.normalizeTitle(works(k).title), "graph mining methods", k)
      assertEquals(blockOf(works(k).workId).blockKey, "graph_mining", k)
    }
    assertEquals(group.map(k => blockOf(works(k).workId).blockId).distinct.size, 1)
    group.tail.foreach { k =>
      val c = workCandidate("T0000", k)
      assertEquals(feat(c, "title.exact"), 1.0, k)
      assertEquals(feat(c, "title.sim"), 1.0, k)
      assertEquals(feat(c, "title.token_jaccard"), 1.0, k)
    }
  }

  test("[guard] a leading BOM on a DBLP author and on its coauthor gives the clean keys") {
    // A leading Cf was trimmed under v1 too; kept as an end-to-end guard.
    assertEquals(keys("B1").take(2), Vector("wang|j", "wang|j|jian"))
    val co = works("B1").authors(1)
    assertEquals(
      EntityResolution.researcherBlockingKeys(works("B1"), co).head,
      "kox" + co.rawName.stripPrefix(cp(0xfeff) + "Ann Kox") + "|a"
    )
    assertEquals(
      keys("B1")(2),
      "wang|j|jian|" + EntityResolution.normalizeName(co.rawName).split(' ').last
    )
  }

  // ------------------------------------------------------------------ marks, hyphens, particles

  test("[regression] Cf mixed with NFD combining marks: popper|c|christina, one block") {
    // v1: M1/M2 "po pper" -> family "pper"; M3 "chri stina" -> level 1 "popper|c|chri".
    val group = Vector("M0", "M1", "M2", "M3")
    group.foreach(k => assertEquals(keys(k).take(2), Vector("popper|c", "popper|c|christina"), k))
    assertEquals(group.map(k => blockOf(m0(k).mentionId).blockId).distinct.size, 1)
    group.tail.foreach(k => assertEquals(feat(mentionCandidate("M0", k), "name.family_exact"), 1.0))
  }

  test("[regression] Cf adjacent to a hyphen, and the soft hyphen, keep garcia-molina whole") {
    // v1: H1 "garcia- molina", H2 "garcia -molina", H3/H4 SHY -> space: family "molina".
    val group = Vector("H0", "H1", "H2", "H3", "H4")
    group.foreach(k => assertEquals(keys(k).head, "garciamolina|a", k))
    assertEquals(group.map(k => blockOf(m0(k).mentionId).blockId).distinct.size, 1)
    group.tail.foreach(k => assertEquals(feat(mentionCandidate("H0", k), "name.family_exact"), 1.0))
    // The soft hyphen is deleted even with keepHyphen; the real hyphen stays "-".
    val shy = cp(0xad)
    assertEquals(TextFolding.fold("Garcia" + shy + "Molina", keepHyphen = true), "garciamolina")
    assertEquals(TextFolding.fold("Garcia-Molina", keepHyphen = true), "garcia-molina")
    assertEquals(TextFolding.fold("Garcia-Molina", keepHyphen = false), "garcia molina")
    assertEquals(
      TextFolding.fold("Garcia-" + cp(0x200b) + "Molina", keepHyphen = true),
      "garcia-molina"
    )
    assertEquals(TextFolding.fold("Garcia" + shy + "-Molina", keepHyphen = true), "garcia-molina")
  }

  test("[regression] Cf inside a particle or a DBLP homonym number does not change parsing") {
    // v1: "va n beethoven" -> family "beethoven"; "00 01" -> family "01".
    assertEquals(keys("P0").head, "vanbeethoven|l")
    assertEquals(keys("P1").take(2), keys("P0").take(2))
    assertEquals(blockOf(m0("P1").mentionId).blockId, blockOf(m0("P0").mentionId).blockId)
    assertEquals(keys("S0").head, "frost|c")
    assertEquals(keys("S1").take(2), keys("S0").take(2))
    val c = mentionCandidate("S0", "S1")
    assertEquals(feat(c, "name.homonym_suffix_both"), 1.0)
    assertEquals(feat(c, "name.homonym_suffix_agree"), 1.0)
  }

  // ------------------------------------------------------------------ confusables, unreadable

  test("[guard] Cf removal never merges a Cyrillic or Greek lookalike with the Latin name") {
    assertEquals(keys("C1").head, "w" + cp(0x430) + "ng|j")
    assertEquals(keys("C2").head, "p" + cp(0x3bf) + "pper|c")
    assertNotEquals(blockOf(m0("C1").mentionId).blockId, blockOf(m0("F0000").mentionId).blockId)
    assertNotEquals(blockOf(m0("C2").mentionId).blockId, blockOf(m0("M0").mentionId).blockId)
    val latin = (groupKeys("F") ++ Vector("B1", "M0", "M1", "M2", "M3")).map(k => m0(k).mentionId)
    Vector("C1", "C2").foreach { k =>
      val id = m0(k).mentionId
      latin.foreach { l =>
        candidateOf(id, l).foreach(c =>
          assertEquals(feat(c, "name.family_exact"), 0.0, s"$k merged with Latin $l")
        )
      }
      memberships
        .filter(_.memberId == id)
        .foreach(mb =>
          assert(!mb.blockKey.startsWith("wang|") && !mb.blockKey.startsWith("popper|"))
        )
    }
  }

  test("[guard] a name made only of format characters yields no keys and is flagged, no crash") {
    Vector("N1", "N2").foreach { k =>
      assertEquals(keys(k), Vector.empty, k)
      assertEquals(EntityResolution.normalizeName(m0(k).rawName), "", k)
      assert(!blockOf.contains(m0(k).mentionId), s"$k must not be blocked")
    }
    val b = stageOf(main._2, "blocking").counters
    assertEquals(cnt(b, "MISSING_KEY"), 2L)
    val flagged = rejects(conf, new Path(runDir, "blocking/round-0/rejects"))
      .filter(_.reason == "missing-key")
    assertEquals(
      flagged.map(r => (r.sourceRecordId, r.disposition)).toSet,
      Set(
        (Some(m0("N1").mentionId), "flagged"),
        (Some(m0("N2").mentionId), "flagged")
      )
    )
    // The readable coauthor is still blocked, with no anchor from the unreadable name.
    val co = works("N1").authors(1)
    assertEquals(
      EntityResolution.researcherBlockingKeys(works("N1"), co),
      Vector("nox|l", "nox|l|lee", "nox|l|lee|-", "nox|l|lee|-|2021")
    )
    assert(blockOf.contains(co.mentionId))
  }

  test("[guard] documented acceptance: Jun+ZWSP+Wang joins; Wang+ZWSP+space keeps two words") {
    assertEquals(keys("J1").head, "junwang|-")
    assertEquals(keys("J2").head, "jun|w")
  }

  // ------------------------------------------------------------------ versions and contracts

  test("[guard] blocks, memberships and candidates carry er-blocking.v2 / er-features.v2") {
    assertEquals(EntityResolution.blockingKeysVersion, "er-blocking.v2")
    assertEquals(EntityResolution.featuresVersion, "er-features.v2")
    assert(blocks.nonEmpty && candidates.nonEmpty && memberships.nonEmpty)
    assert(blocks.forall(_.blockingVersion == EntityResolution.blockingKeysVersion))
    assert(memberships.forall(_.blockingVersion == EntityResolution.blockingKeysVersion))
    assert(candidates.forall(_.blockingVersion == EntityResolution.blockingKeysVersion))
    assert(candidates.forall(_.featureVersion == EntityResolution.featuresVersion))
    assert(candidates.exists(_.entityKind == MatchEntityKind.Work), "work candidates checked")
    assert(candidates.exists(_.entityKind == MatchEntityKind.Researcher))
    // No derived key carries a format character.
    val allKeys = blocks.map(_.blockKey) ++ candidates.flatMap(_.blockKeys.map(_.key))
    allKeys.foreach(k => assert(!k.codePoints.anyMatch(c => TextFolding.isFormat(c)), k))
    checkContracts(conf, runDir, main._4)
  }

  // ------------------------------------------------------------------ API properties

  /** Every code point the running JDK classifies as Cf. */
  lazy val allFormat: Vector[Int] =
    (0 to Character.MAX_CODE_POINT).filter(c => Character.getType(c) == Character.FORMAT).toVector

  test("[regression] every Cf code point of the JDK is deleted inside a word, both hyphen modes") {
    assert(allFormat.size > 100, s"only ${allFormat.size} Cf code points")
    formats.foreach(c => assert(allFormat.contains(c), s"U+${hex(c)} is not Cf on this JDK"))
    for (c <- allFormat; h <- Vector(true, false)) {
      assertEquals(TextFolding.fold("Wa" + cp(c) + "ng", h), "wang", s"U+${hex(c)} keepHyphen=$h")
      assertEquals(TextFolding.fold(cp(c), h), "", s"U+${hex(c)} alone")
    }
  }

  test("[guard] Zs, Zl, Zp, Cc, Cs, Co and Cn still separate words") {
    val unassigned = Character.UNASSIGNED.toInt
    val privateUse = Character.PRIVATE_USE.toInt
    val types: Vector[Int] = Vector(
      Character.SPACE_SEPARATOR.toInt,
      Character.LINE_SEPARATOR.toInt,
      Character.PARAGRAPH_SEPARATOR.toInt,
      Character.CONTROL.toInt,
      privateUse,
      unassigned
    )
    val sample = (0 to Character.MAX_CODE_POINT).iterator
      .filter(c => types.contains(Character.getType(c)))
      .filter(c => Character.getType(c) != unassigned || c % 4099 == 0 || c < 0x1000)
      .filter(c => Character.getType(c) != privateUse || c % 997 == 0)
      .toVector
    types.foreach(t =>
      assert(sample.exists(c => Character.getType(c) == t), s"no sample of type $t")
    )
    for (c <- sample; h <- Vector(true, false))
      assertEquals(TextFolding.fold("Wa" + cp(c) + "ng", h), "wa ng", s"U+${hex(c)} keepHyphen=$h")
    // Lone surrogates (Cs) cannot be built with Character.toChars from a valid code point.
    Vector('\ud800', '\udbff', '\udc00', '\udfff').foreach { s =>
      assertEquals(
        TextFolding.fold("Wa" + s + "ng", keepHyphen = false),
        "wa ng",
        s.toInt.toHexString
      )
    }
  }

  test("[regression] fuzz: Cf deletion commutes with fold, keys and title words; fold idempotent") {
    val rnd = new scala.util.Random(441L)
    val cfs = (formats ++ Vector(0xe0001, 0xe0067, 0x180e, 0x0600, 0x110bd)).map(cp(_))
    val letters =
      Vector("a", "W", "n", "g", "J", cp(0xf6), "o", cp(0x308), cp(0x301), cp(0x430), cp(0x3bf))
    val separators =
      Vector(" ", "-", ",", ".", "'", "(", ")", "\t", cp(0x2028), cp(0xa0), cp(0x2010))
    val others =
      Vector(cp(0x20000), cp(0x645), cp(0x5d3), cp(0x130), cp(0xdf), "0", "1", "\ud800")
    val alphabet: Vector[String] = cfs ++ letters ++ separators ++ others
    val template = works("F0000")
    (1 to 3000).foreach { i =>
      val x = Vector.fill(1 + rnd.nextInt(14))(alphabet(rnd.nextInt(alphabet.size))).mkString
      val clean = TextFolding.removeFormat(x)
      for (h <- Vector(true, false)) {
        val f = TextFolding.fold(x, h)
        assertEquals(f, TextFolding.fold(clean, h), s"#$i fold '$x' h=$h")
        assertEquals(TextFolding.fold(f, h), f, s"#$i idempotent '$x' h=$h")
        assert(!f.codePoints.anyMatch(c => TextFolding.isFormat(c)), s"#$i Cf in output '$f'")
        assert(!f.codePoints.anyMatch(c => Character.getType(c) == Character.CONTROL), s"#$i")
        assert(!f.contains("|") && !f.contains("  ") && f == f.trim, s"#$i '$f'")
      }
      def withName(n: String): WorkRecord =
        template.copy(authors = template.authors.updated(0, template.authors(0).copy(rawName = n)))
      val wx = withName(x)
      val wc = withName(clean)
      assertEquals(
        EntityResolution.researcherBlockingKeys(wx, wx.authors(0)),
        EntityResolution.researcherBlockingKeys(wc, wc.authors(0)),
        s"#$i keys '$x'"
      )
      assertEquals(TitleText.words(x), TitleText.words(clean), s"#$i title words '$x'")
      assertEquals(
        EntityResolution.workBlockingKeys(template.copy(title = x)),
        EntityResolution.workBlockingKeys(template.copy(title = clean)),
        s"#$i work keys '$x'"
      )
      // Keys never contain a Cf character or a blank component.
      EntityResolution.researcherBlockingKeys(wx, wx.authors(0)).foreach { k =>
        assert(!k.codePoints.anyMatch(c => TextFolding.isFormat(c)), s"#$i key '$k'")
        assert(!k.split('|').exists(_.isEmpty), s"#$i empty component in '$k'")
      }
    }
  }

  test("[guard] literal version pins") {
    assertEquals(EntityResolution.blockingKeysVersion, "er-blocking.v2")
    assertEquals(EntityResolution.featuresVersion, "er-features.v2")
  }
}
