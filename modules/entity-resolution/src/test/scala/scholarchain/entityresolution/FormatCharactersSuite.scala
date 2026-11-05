package scholarchain.entityresolution

import scholarchain.core.{MentionId, WorkRecord}

import Samples.*

/**
 * Work order ER-P5-00 (Phase 4 accepted deferral): Unicode format characters (category Cf) are
 * deleted by [[TextFolding.fold]], never turned into separators, so they cannot split family keys,
 * given names, or title tokens into different blocks. Non-ASCII code points are built from explicit
 * hex values so that every fixture is unambiguous.
 */
class FormatCharactersSuite extends munit.FunSuite {
  import TextFolding.fold

  def cp(c: Int*): String = c.map(x => new String(Character.toChars(x))).mkString

  /** The format characters named in the work order, with a label. */
  val formats: Vector[(Int, String)] = Vector(
    0x00ad -> "SOFT HYPHEN",
    0x061c -> "ARABIC LETTER MARK",
    0x200b -> "ZERO WIDTH SPACE",
    0x200c -> "ZWNJ",
    0x200d -> "ZWJ",
    0x200e -> "LRM",
    0x200f -> "RLM",
    0x202a -> "LRE",
    0x202b -> "RLE",
    0x202c -> "PDF",
    0x202d -> "LRO",
    0x202e -> "RLO",
    0x2060 -> "WORD JOINER",
    0x2066 -> "LRI",
    0x2067 -> "RLI",
    0x2068 -> "FSI",
    0x2069 -> "PDI",
    0xfeff -> "BOM",
    0xe0041 -> "TAG LATIN CAPITAL A (astral Cf)"
  )

  test("every listed character is category Cf and isFormat; whitespace and controls are not") {
    formats.foreach { case (c, label) =>
      assertEquals(Character.getType(c), Character.FORMAT.toInt, label)
      assert(TextFolding.isFormat(c), label)
    }
    Vector(0x20, 0x09, 0x0a, 0x00, 0x85, 0xa0, 0x2028, 0x2029, 0x3000, 0x2d, 0x27, 0x61)
      .foreach(c => assert(!TextFolding.isFormat(c), f"U+$c%04X"))
  }

  test("a format character inside a word is deleted, at either hyphen setting") {
    formats.foreach { case (c, label) =>
      val f = cp(c)
      assertEquals(fold("Wa" + f + "ng", keepHyphen = false), "wang", label)
      assertEquals(fold("Wa" + f + "ng", keepHyphen = true), "wang", label)
      assertEquals(fold(f + "Wang" + f, keepHyphen = false), "wang", label)
      assertEquals(fold(f, keepHyphen = true), "", s"$label alone")
    }
  }

  test("format characters at word boundaries: a real space still separates; none joins") {
    val zwsp = cp(0x200b)
    assertEquals(fold("Wang" + zwsp + " Jun", keepHyphen = false), "wang jun")
    assertEquals(fold("Wang " + zwsp + "Jun", keepHyphen = false), "wang jun")
    assertEquals(fold("Wang," + zwsp + "Jun", keepHyphen = false), "wang jun", "comma separates")
    assertEquals(
      fold("Jun" + zwsp + "Wang", keepHyphen = false),
      "junwang",
      "accepted: a Cf character is not a word separator"
    )
  }

  test("soft hyphen is deleted even with keepHyphen; a real hyphen is unchanged") {
    val shy = cp(0x00ad)
    assertEquals(fold("Garcia" + shy + "Molina", keepHyphen = true), "garciamolina")
    assertEquals(fold("Garcia" + shy + "Molina", keepHyphen = false), "garciamolina")
    assertEquals(fold("Garcia-Molina", keepHyphen = true), "garcia-molina")
    assertEquals(fold("Garcia-" + shy + "Molina", keepHyphen = true), "garcia-molina")
    assertEquals(PersonName.parse("Hector Garcia" + shy + "Molina").familyKey, "garciamolina")
    assertEquals(
      PersonName.parse("Hector Garcia-Molina").familyKey,
      PersonName.parse("Hector Garcia" + shy + "Molina").familyKey
    )
  }

  test("Persian ZWNJ inside one word keeps the word whole") {
    // mi + ZWNJ + khaham (Persian), and Mohammad-Reza written with ZWNJ in a family name.
    val word = cp(0x645, 0x6cc) + cp(0x200c) + cp(0x62e, 0x648, 0x627, 0x647, 0x645)
    assertEquals(
      fold(word, keepHyphen = false),
      cp(0x645, 0x6cc, 0x62e, 0x648, 0x627, 0x647, 0x645)
    )
    assertEquals(TextFolding.words(word, keepHyphen = false).size, 1)
  }

  test("NFKD interaction: precomposed, decomposed, and Cf-interrupted marks fold alike") {
    val zwj = cp(0x200d)
    assertEquals(fold("P" + cp(0xf6) + "pper", keepHyphen = false), "popper")
    assertEquals(fold("Po" + cp(0x308) + "pper", keepHyphen = false), "popper")
    assertEquals(fold("Po" + zwj + cp(0x308) + "pper", keepHyphen = false), "popper")
    assertEquals(fold("P" + cp(0xf6) + zwj + "pper", keepHyphen = false), "popper")
    assertEquals(fold(cp(0xfb01) + zwj + "le", keepHyphen = false), "file", "ligature")
  }

  test("idempotent: fold(fold(x)) == fold(x)") {
    val inputs = formats.map { case (c, _) => "Wa" + cp(c) + "ng, J" + cp(c) + "un" } ++ Vector(
      "Jun" + cp(0x200b) + "Wang",
      "Garcia" + cp(0xad) + "-Molina",
      "Christina P" + cp(0xf6) + "pper",
      "a\tb\u0000c\nd",
      "Spanner: Google's Globally Distributed Database."
    )
    for (in <- inputs; h <- Vector(true, false)) {
      val once = fold(in, h)
      assertEquals(fold(once, h), once, s"$in keepHyphen=$h")
      assert(!once.codePoints.anyMatch(c => TextFolding.isFormat(c)), in)
    }
  }

  test("names without format characters fold exactly as before (golden)") {
    assertEquals(fold("Wang, Jun", keepHyphen = false), "wang jun")
    assertEquals(fold("Jean-Pierre Garcia-Molina", keepHyphen = true), "jean-pierre garcia-molina")
    assertEquals(fold("O'Brien  D’Angelo", keepHyphen = false), "obrien dangelo")
    assertEquals(fold("Straße Łukasz", keepHyphen = false), "strasse lukasz")
    assertEquals(TextFolding.removeFormat("Wang, Jun"), "Wang, Jun")
    assertEquals(TextFolding.removeFormat(null), "")
    assertEquals(TextFolding.removeFormat("W" + cp(0x200b) + "a" + cp(0xe0041)), "Wa")
  }

  // ---------------------------------------------------------------- blocking keys

  /** SYNTHETIC: a two-author work whose first author is `name`. */
  private def workWith(name: String, title: String = "Collaboration of Examples."): WorkRecord =
    buildWork(
      synthetic.source,
      "work:dblp:journals/example/Cf01",
      "journals/example/Cf01",
      title,
      Some(2021),
      None,
      None,
      None,
      Vector(Samples.A(name), Samples.A("Ada Example"))
    )

  private def keys(name: String): Vector[String] = {
    val w = workWith(name)
    BlockingKeys.researcherKeys(w, w.authors(0))
  }

  private val clean = keys("Wang, Jun")

  test("golden clean keys") {
    assertEquals(
      clean,
      Vector("wang|j", "wang|j|jun", "wang|j|jun|example", "wang|j|jun|example|2021")
    )
  }

  test("work-order cases: each Cf variant of `Wang, Jun` has the clean family and level-0 key") {
    val variants = Vector(
      "Wa" + cp(0x200b) + "ng, Jun" -> "ZWSP",
      "Wa" + cp(0x200d) + "ng, Jun" -> "ZWJ",
      "Wa" + cp(0x00ad) + "ng, Jun" -> "SOFT HYPHEN",
      cp(0xfeff) + "Wang, Jun" -> "BOM",
      cp(0x200f) + "Wang" + cp(0x200f) + ", Jun" -> "RLM-wrapped",
      "Wa" + cp(0x2060) + "ng, Jun" -> "WORD JOINER"
    )
    variants.foreach { case (n, label) =>
      assertEquals(PersonName.parse(n).familyBlockKey, "wang", label)
      assertEquals(keys(n).head, clean.head, label)
      assertEquals(keys(n), clean, label)
    }
  }

  test("every listed Cf character in the family, the given name, or both gives the clean keys") {
    formats.foreach { case (c, label) =>
      val f = cp(c)
      Vector(
        "Wa" + f + "ng, Jun",
        "Wang, J" + f + "un",
        f + "Wa" + f + "ng" + f + ", " + f + "Jun" + f,
        "Jun Wa" + f + "ng",
        "J" + f + "un Wang"
      ).foreach { n =>
        assertEquals(PersonName.parse(n).familyBlockKey, "wang", s"$label: $n")
        assertEquals(keys(n), clean, s"$label: $n")
      }
    }
  }

  test("initials with format characters keep the clean first initial and initials string") {
    val f = cp(0x200b)
    assertEquals(PersonName.parse("J." + f + " C. Corbett").initials, "jc")
    assertEquals(PersonName.parse(f + "J. C. Corbett").firstInitial, Some("j"))
    assertEquals(PersonName.parse("James C." + f + " Corbett").familyKey, "corbett")
  }

  test("profile keys: structured and display names with format characters") {
    val f = cp(0x200c)
    val structured =
      popperOrcid.copy(
        givenNames = Some("Chris" + f + "tina"),
        familyName = Some("Poe" + f + "pper")
      )
    assertEquals(BlockingKeys.profileKeys(structured), BlockingKeys.profileKeys(popperOrcid))
    val display = popperOpenAlex.copy(displayName = Some("Christina P" + f + cp(0xf6) + "pper"))
    assertEquals(BlockingKeys.profileKeys(display), BlockingKeys.profileKeys(popperOpenAlex))
  }

  test("work keys and title tokens ignore format characters, including after the editorial note") {
    val shy = cp(0x00ad)
    val zwsp = cp(0x200b)
    assertEquals(
      BlockingKeys.workKeys(
        spannerDblp.copy(title =
          "Span" + shy + "ner: Goo" + zwsp + "gle's Globally Dis" + cp(0x2060) +
            "tributed Database."
        )
      ),
      BlockingKeys.workKeys(spannerDblp)
    )
    assertEquals(
      TitleText.contentTokens("Cyber" + cp(0x200d) + "security"),
      Vector("cybersecurity")
    )
    assertEquals(
      TitleText.words(bigtableDblp.title + cp(0x200b)),
      TitleText.words(bigtableDblp.title),
      "a trailing Cf character does not hide the (Awarded Best Paper!) note"
    )
    assertEquals(
      TitleText.words(cp(0xfeff) + bigtableDblp.title.replace("!)", "!)" + cp(0x200f))),
      TitleText.words(bigtableDblp.title)
    )
  }

  test("the coauthor anchor of another mention ignores format characters") {
    val w = buildWork(
      synthetic.source,
      "work:dblp:journals/example/Cf02",
      "journals/example/Cf02",
      "Collaboration of Examples.",
      Some(2021),
      None,
      None,
      None,
      Vector(Samples.A("Jun Wang"), Samples.A("Ada Ex" + cp(0x200b) + "ample"))
    )
    assertEquals(BlockingKeys.coauthorAnchor(w, w.authors(0)), "example")
  }

  test("raw names and titles are not altered; keys are read from the raw fields") {
    val raw = "Wa" + cp(0x200b) + "ng, " + cp(0xfeff) + "Jun"
    val rawTitle = "Collab" + cp(0x00ad) + "oration of Examples."
    val rawCopy = new String(raw.toCharArray)
    val titleCopy = new String(rawTitle.toCharArray)
    val w = workWith(raw, rawTitle)
    val k = BlockingKeys.researcherKeys(w, w.authors(0))
    val wk = BlockingKeys.workKeys(w)
    val f = MatchFeatures.researcher(em(w, 0), em(workWith("Wang, Jun"), 0))
    assertEquals(k, clean)
    assertEquals(wk, BlockingKeys.workKeys(workWith("Wang, Jun")))
    assertEquals(f("name.family_exact"), 1.0)
    // The record still carries the exact input, code unit for code unit.
    assertEquals(w.authors(0).rawName, rawCopy)
    assertEquals(w.authors(0).rawName.length, rawCopy.length)
    assert(w.authors(0).rawName.contains(cp(0x200b)))
    assertEquals(w.title, titleCopy)
    assert(w.title.contains(cp(0x00ad)))
    // Keys come from rawName and title: a garbled source-normalized field changes nothing, and a
    // different rawName changes the key.
    val garbled = w.copy(
      normalizedTitle = "zzz",
      authors = w.authors.map(_.copy(normalizedName = "zzz"))
    )
    assertEquals(BlockingKeys.researcherKeys(garbled, garbled.authors(0)), k)
    assertEquals(BlockingKeys.workKeys(garbled), wk)
    val renamed = w.copy(authors = w.authors.updated(0, w.authors(0).copy(rawName = "Li, Jun")))
    assertEquals(BlockingKeys.researcherKeys(renamed, renamed.authors(0)).head, "li|j")
    // Profiles: the record is unchanged after keying.
    val p = popperOrcid.copy(familyName = Some("Poe" + cp(0x200d) + "pper"))
    val before = p.copy()
    BlockingKeys.profileKeys(p)
    assertEquals(p, before)
    assertEquals(p.familyName, Some("Poe" + cp(0x200d) + "pper"))
  }

  test("adversarial key validity: a Cf-only name yields no keys") {
    val w = workWith(cp(0x200b, 0xfeff, 0x200f))
    assertEquals(BlockingKeys.researcherKeys(w, w.authors(0)), Vector.empty)
    val mid = ok(MentionId.of(w.workId, 0))
    assertEquals(w.authors(0).mentionId, mid)
  }

  test("pinned versions after the format-character change") {
    assertEquals(BlockingKeys.Version, "er-blocking.v2")
    assertEquals(MatchFeatures.Version, "er-features.v2")
  }
}
