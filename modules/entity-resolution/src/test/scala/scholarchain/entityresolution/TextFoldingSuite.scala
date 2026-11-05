package scholarchain.entityresolution

import java.util.Locale

class TextFoldingSuite extends munit.FunSuite {
  import TextFolding.fold

  test("diacritics fold to base letters, precomposed and decomposed alike") {
    assertEquals(fold("Christina Pöpper", keepHyphen = false), "christina popper")
    assertEquals(fold("Christina Pöpper", keepHyphen = false), "christina popper")
    assertEquals(fold("João Varajão", keepHyphen = false), "joao varajao")
  }

  test("letters NFKD keeps whole use the explicit transliteration table") {
    assertEquals(fold("Straße", keepHyphen = false), "strasse")
    assertEquals(fold("Łukasz", keepHyphen = false), "lukasz")
    assertEquals(fold("Søren", keepHyphen = false), "soren")
    assertEquals(fold("Œuvre Æsir", keepHyphen = false), "oeuvre aesir")
    assertEquals(fold("Þorr Đorđe", keepHyphen = false), "thorr dorde")
    assertEquals(fold("ﬁle", keepHyphen = false), "file", "NFKD ligature")
  }

  test("apostrophes are deleted; hyphens kept only on request; other punctuation is a space") {
    assertEquals(fold("O'Brien D’Angelo", keepHyphen = false), "obrien dangelo")
    assertEquals(fold("Garcia-Molina", keepHyphen = true), "garcia-molina")
    assertEquals(fold("Garcia–Molina", keepHyphen = true), "garcia-molina")
    assertEquals(fold("Garcia-Molina", keepHyphen = false), "garcia molina")
    assertEquals(fold("Spanner: Google's  DB.", keepHyphen = false), "spanner googles db")
  }

  test("control characters, tabs and newlines become separators; null and blanks fold to empty") {
    assertEquals(fold("a\tb\u0000c\nd\u0085e", keepHyphen = false), "a b c d e")
    assertEquals(fold("a b c d　e", keepHyphen = false), "a b c d e", "Zl Zp Zs")
    assertEquals(fold(null, keepHyphen = false), "")
    assertEquals(fold("  \t ", keepHyphen = false), "")
    assertEquals(fold("́", keepHyphen = false), "", "a lone combining mark")
  }

  test("lower-casing does not depend on the default locale (Turkish dotted and dotless i)") {
    val before = Locale.getDefault
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"))
      assertEquals(fold("IŞIK İNAN TITLE", keepHyphen = false), "isik inan title")
    } finally Locale.setDefault(before)
    assertEquals(fold("IŞIK İNAN TITLE", keepHyphen = false), "isik inan title")
  }

  test("letters of other scripts are kept") {
    assertEquals(fold("王小明", keepHyphen = false), "王小明")
    assertEquals(fold("Αθηνά", keepHyphen = false), "αθηνα")
  }

  test("vowel digraph collapse unifies German umlaut spellings") {
    assertEquals(TextFolding.collapseVowelDigraphs("poepper"), "popper")
    assertEquals(TextFolding.collapseVowelDigraphs("popper"), "popper")
    assertEquals(TextFolding.collapseVowelDigraphs("mueller"), "muller")
    assertEquals(TextFolding.collapseVowelDigraphs("michael"), "michal")
    assertEquals(TextFolding.collapseVowelDigraphs("dean"), "dean")
  }

  test("Levenshtein distance and similarity (hand-computed)") {
    assertEquals(Similarity.levenshtein("kitten", "sitting"), 3)
    assertEquals(Similarity.levenshteinSimilarity("kitten", "sitting"), 1.0 - 3.0 / 7.0)
    assertEquals(Similarity.levenshteinSimilarity("frost", "frost"), 1.0)
    assertEquals(Similarity.levenshteinSimilarity("", "frost"), 0.0)
    assertEquals(Similarity.levenshteinSimilarity("", ""), 0.0)
    assertEquals(Similarity.levenshtein("𝒜b", "b"), 1, "counts code points")
  }

  test("Jaccard and LCS (hand-computed); empty inputs give 0") {
    assertEquals(Similarity.jaccard(Set("a", "b", "c"), Set("b", "c", "d")), 2.0 / 4.0)
    assertEquals(Similarity.jaccard(Set.empty, Set.empty), 0.0)
    assertEquals(Similarity.lcsLength(Vector("a", "b", "c", "d"), Vector("b", "x", "d")), 2)
    assertEquals(Similarity.lcsLength(Vector("", "a"), Vector("", "a")), 1, "empty never matches")
    assertEquals(Similarity.lcsLength(Vector.empty, Vector("a")), 0)
  }
}
