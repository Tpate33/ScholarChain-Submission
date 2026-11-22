package scholarchain.tests.rag

import scholarchain.core.Canonical
import scholarchain.rag.agents.SurfaceForms
import scholarchain.storage.model.{EntityLinkEvidence, EntityLinkSurfaceForm}

/**
 * TE-P9-01 item 1 (README Phase 9 stage 2 "Resolve user-supplied names ... using the
 * entity-resolution outputs"; SE-P9-01 risk "normalization duplicated in storage (drift test needed
 * in the tests tree)"): the runtime lookup normalization (rag [[SurfaceForms]]) and the loader's
 * normalization (storage [[EntityLinkSurfaceForm]]) must be byte-identical, under one version tag,
 * or a stored link is unreachable (silent abstention) or, worse, reachable from another name.
 *
 * Inputs are written with `\\u` escapes only (no literal bidi or format character in source).
 */
class SurfaceFormDriftSuite extends munit.FunSuite {

  private def rag(s: String) = SurfaceForms.normalize(s)
  private def sto(s: String) = EntityLinkSurfaceForm.normalize(s)

  /** Named adversarial inputs, by family. */
  private val Adversarial: Vector[(String, String)] = Vector(
    // NFKC compatibility forms
    "ligature fi" -> "ﬁsher ﬂores",
    "roman numeral" -> "Henry Ⅳ",
    "circled digit" -> "Ada ①",
    "half-width katakana" -> "ｶﾀｶﾅ",
    "square unit" -> "㎏ Smith",
    "superscript" -> "x² y⁵",
    "long s" -> "Moſes",
    "kelvin sign" -> "Karl",
    "angstrom sign" -> "Ångström",
    "fraction" -> "½ Lee",
    "full-width name" -> "Ｊｏｈｎ　Ｓｍｉｔｈ",
    "full-width punctuation" -> "Smith，Ｊ．；：",
    "math bold" -> "𝐀𝐝𝐚",
    // confusables (must stay distinct and agree)
    "cyrillic A" -> "Аda Lovelace",
    "greek alpha" -> "Αda Lovelace",
    "latin" -> "Ada Lovelace",
    "cyrillic o in name" -> "Jоhn Smith",
    "dotless i" -> "ıbrahim",
    "dotted capital I" -> "İstanbul Yılmaz",
    "sharp s" -> "Straße",
    "capital sharp s" -> "STRAẞE",
    "final sigma" -> "ΟΔΟΣ",
    "titlecase digraph" -> "ǅordǆe",
    // combining marks
    "decomposed e acute" -> "José García",
    "composed e acute" -> "José García",
    "combining order a" -> "ậ b",
    "combining order b" -> "ậ b",
    "stacked marks" -> "Z̵̡͘a̶lgo",
    "hangul jamo" -> "각",
    "hangul syllable" -> "각",
    // bidi controls
    "RTL override" -> "‮ecalevol ada",
    "RTL embedding" -> "Ada ‫Lovelace‬",
    "LRM and RLM" -> "Ada‎ ‏Lovelace",
    "arabic letter mark" -> "؜Ada",
    "isolates" -> "⁦Ada⁩ ⁧Lovelace⁩",
    // zero-width and invisible
    "zero-width space" -> "Ada​Lovelace",
    "ZWJ ZWNJ" -> "Ada‍‌Lovelace",
    "BOM" -> "﻿Ada Lovelace",
    "word joiner" -> "Ada⁠ Lovelace",
    "soft hyphen" -> "Love­lace",
    "mongolian vowel separator" -> "Ada᠎Lovelace",
    // punctuation the rules map and nearby ones they do not
    "mapped punctuation" -> "Smith,J.;A:B",
    "unmapped punctuation" -> "O'Neil-Smith ’ ‐ – · ! ? ( ) /",
    "only punctuation" -> " .,;: ",
    "dots and spaces" -> "J. R. R. Tolkien",
    // whitespace variants
    "ascii whitespace" -> "a\tb\nc\rd\fe\u000Bf",
    "nbsp" -> "Ada Lovelace",
    "figure and narrow nbsp" -> "Ada Love lace",
    "en and em spaces" -> "Ada    Lovelace",
    "ideographic space" -> "Ada　Lovelace",
    "line and paragraph separator" -> "Ada Love lace",
    "next line" -> "Ada\u0085Lovelace",
    "ogham space" -> "Ada Lovelace",
    "leading trailing mixed" -> "　 \t Ada  Lovelace  \n",
    // controls, surrogates, empties
    "nul and bell" -> "Ada\u0000\u0007Lovelace",
    "lone high surrogate" -> "Ada\uD800",
    "lone low surrogate" -> "\uDC00Ada",
    "emoji with modifier" -> "Ada 👩🏽‍🔬",
    "regional indicators" -> "🇺🇸",
    "empty" -> "",
    "spaces only" -> "     ",
    "very long" -> ("Ab́c" * 2000)
  )

  test("version tags: the loader writes the runtime's normalization version") {
    assertEquals(EntityLinkSurfaceForm.Version, SurfaceForms.Version)
    assertEquals(SurfaceForms.Version, "scholarchain.rag.surface-form.v1")
  }

  test("adversarial Unicode: rag and storage normalize and hash byte-identically") {
    Adversarial.foreach { case (why, s) =>
      assertEquals(rag(s), sto(s), s"$why: normalize drift")
      assertEquals(SurfaceForms.sha256(s), EntityLinkSurfaceForm.sha256(s), s"$why: sha256 drift")
      assertEquals(SurfaceForms.sha256(s), Canonical.sha256HexUtf8(rag(s)), why)
    }
    assertEquals(Adversarial.map(_._1).distinct.size, 62, "every named adversarial input ran")
    assertEquals(rag(null), sto(null))
    assertEquals(rag(null), "")
  }

  test("the rules of v1 as documented hold in both modules (not merely equal to each other)") {
    val cases: Vector[(String, String)] = Vector(
      "ﬁsher" -> "fisher", // NFKC
      "Ｊｏｈｎ　Ｓｍｉｔｈ" -> "john smith", // full width
      "José" -> "josé", // composed by NFKC, diacritic kept
      "Smith，Ｊ．" -> "smith j", // full-width punctuation is mapped after NFKC
      "ậ" -> "ậ", // marks reordered canonically, then composed (NFKC)
      "ậ" -> "ậ",
      "Ada Lovelace" -> "ada lovelace", // NFKC NBSP -> space
      "Ada　Lovelace" -> "ada lovelace", // NFKC ideographic space -> space
      "  John   SMITH " -> "john smith",
      " .,;: " -> ""
    )
    cases.foreach { case (in, out) =>
      assertEquals(rag(in), out, in)
      assertEquals(sto(in), out, in)
    }
    // Confusables are distinct surface forms (never merged), in both modules.
    val confusable = Vector("Аda Lovelace", "Αda Lovelace", "Ada Lovelace")
    assertEquals(confusable.map(rag).distinct.size, 3)
    assertEquals(confusable.map(sto).distinct.size, 3)
    // Format characters are not removed by v1 (documented: NFKC keeps them): the zero-width
    // spelling is another surface form in both modules.
    assertNotEquals(rag("Ada​Lovelace"), rag("AdaLovelace"))
    assertEquals(rag("Ada​Lovelace"), sto("Ada​Lovelace"))
  }

  test("seeded fuzz over an adversarial alphabet: 20000 strings, zero drift") {
    val alphabet: Vector[String] = Vector(
      "a",
      "Z",
      "é",
      "é",
      " ",
      "\t",
      "\n",
      ".",
      ",",
      ";",
      ":",
      "'",
      "-",
      " ",
      " ",
      "　",
      " ",
      "\u0085",
      "​",
      "‍",
      "‮",
      "⁦",
      "﻿",
      "­",
      "ﬁ",
      "Ａ",
      "．",
      "，",
      "K",
      "İ",
      "ı",
      "ß",
      "Σ",
      "А",
      "̣",
      "̂",
      "\uD800",
      "👩",
      "\u0000",
      "1",
      "Ⅳ"
    )
    val rnd = new scala.util.Random(0x5c9e01L)
    var n = 0
    (1 to 20000).foreach { _ =>
      val len = rnd.nextInt(13)
      val s = (0 until len).map(_ => alphabet(rnd.nextInt(alphabet.size))).mkString
      assertEquals(rag(s), sto(s), s"drift on ${s.map(c => f"U+${c.toInt}%04X").mkString(" ")}")
      assertEquals(SurfaceForms.sha256(s), EntityLinkSurfaceForm.sha256(s))
      n += 1
    }
    assertEquals(n, 20000)
  }

  test("stored corpus links: every loader row is found by the runtime hash and names v1") {
    val c = Phase9Corpus
    val w = new CorpusWorld(Phase9Corpus.Dim)
    var rows = 0
    var partitions = 0
    c.namedMentions
      .map(_._1)
      .distinct
      .filter(n => sto(n).nonEmpty)
      .groupBy(SurfaceForms.sha256)
      .foreach { case (sha, spellings) =>
        // Every spelling of the corpus reaches the same partition through the runtime's hash.
        spellings.foreach(n => assertEquals(EntityLinkSurfaceForm.sha256(n), sha, n))
        val got = c.linkRows(w.kvStore, sha)
        assert(got.nonEmpty, s"partition $sha: no stored link for ${spellings.head}")
        got.foreach { l =>
          assertEquals(l.surfaceSha256, sha)
          val e = l.evidence.getOrElse(fail(s"$sha: loader link without evidence"))
          assertEquals(e.surfaceFormVersion, SurfaceForms.Version)
          assertEquals(EntityLinkEvidence.problems(e), Vector.empty)
        }
        partitions += 1
        rows += got.size
      }
    println(
      s"TE-P9-01 drift: $partitions surface forms, $rows stored link rows reached by rag's hash"
    )
    assertEquals((partitions, rows), (148, 157), "SE-P9-01 corpus counts (no coverage loss)")
  }
}
