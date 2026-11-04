package scholarchain.entityresolution

class NamesSuite extends munit.FunSuite {
  import PersonName.parse

  test("a DBLP homonym number is removed from the name and kept as homonymSuffix") {
    assertEquals(
      parse("Christopher Frost 0001"),
      PersonName(Vector("christopher"), Vector("frost"), Some("0001"))
    )
    assertEquals(parse("Christopher Frost").homonymSuffix, None)
    assertEquals(parse("Christopher Frost 0001").familyBlockKey, "frost")
  }

  test("initials and middle names") {
    val furman = parse("J. J. Furman")
    assertEquals(furman.givenParts, Vector("j", "j"))
    assertEquals(furman.familyKey, "furman")
    assertEquals(furman.initials, "jj")
    assertEquals(parse("James C. Corbett").initials, "jc")
    assertEquals(parse("James C. Corbett").firstGivenKey, Some("james"))
  }

  test("particles join the family name only when a given name remains") {
    val dp = parse("Roberto Di Pietro")
    assertEquals(dp.familyWords, Vector("di", "pietro"))
    assertEquals(dp.familyKey, "dipietro")
    assertEquals(dp.givenParts, Vector("roberto"))
    assertEquals(parse("Wil M. P. van der Aalst").familyKey, "vanderaalst")
    assertEquals(parse("Wil M. P. van der Aalst").initials, "wmp")
    assertEquals(parse("Le Song"), PersonName(Vector("le"), Vector("song"), None))
  }

  test("comma form `Family, Given`") {
    assertEquals(parse("Pöpper, C."), PersonName(Vector("c"), Vector("popper"), None))
    val poepper = parse("Poepper, Christina")
    assertEquals(poepper.familyKey, "poepper")
    assertEquals(poepper.familyBlockKey, "popper")
    assertEquals(parse("O'Brien, Mary").familyKey, "obrien")
    assertEquals(parse("Guy Steele, Jr.").familyKey, "steele", "comma before a suffix")
  }

  test("hyphenated names, generational suffixes, single names") {
    val gm = parse("Jean-Pierre Garcia-Molina")
    assertEquals(gm.givenParts, Vector("jean", "pierre"))
    assertEquals(gm.familyKey, "garciamolina")
    assertEquals(parse("Guy L. Steele Jr.").familyKey, "steele")
    assertEquals(parse("Guy L. Steele Jr.").initials, "gl")
    val single = parse("Madonna")
    assertEquals(single, PersonName(Vector.empty, Vector("madonna"), None))
    assertEquals(single.firstInitial, None)
    assertEquals(parse("王小明").familyKey, "王小明")
  }

  test("unreadable names are empty") {
    assert(parse("").isEmpty)
    assert(parse("   ").isEmpty)
    assert(parse("...").isEmpty)
    assert(parse(null).isEmpty)
    assert(parse("\t\u0000").isEmpty)
  }

  test("structured ORCID names; a missing family name falls back to parsing the given field") {
    assertEquals(
      PersonName.structured(Some("Christina"), Some("Poepper")),
      PersonName(Vector("christina"), Vector("poepper"), None)
    )
    assertEquals(
      PersonName.structured(Some("Christina Pöpper"), None),
      PersonName(Vector("christina"), Vector("popper"), None)
    )
    assert(PersonName.structured(None, None).isEmpty)
  }

  test("full key and tokens use the blocking family key") {
    assertEquals(parse("Christina Poepper").fullKey, "christina popper")
    assertEquals(parse("Christina Pöpper").tokens, Set("christina", "popper"))
    assertEquals(parse("Madonna").fullKey, "madonna")
  }

  test("titles: trailing editorial note and period removed, punctuation folded") {
    assertEquals(
      TitleText.words(
        "Bigtable: A Distributed Storage System for Structured Data (Awarded Best Paper!)."
      ),
      Vector("bigtable", "a", "distributed", "storage", "system", "for", "structured", "data")
    )
    assertEquals(TitleText.words("(Preface)"), Vector("preface"), "a note alone is kept")
    assertEquals(
      TitleText.contentTokens("Spanner: Google's Globally Distributed Database."),
      Vector("spanner", "googles", "globally", "distributed", "database")
    )
    assertEquals(
      TitleText.normalize("Spanner: Google's Globally Distributed Database."),
      TitleText.normalize("Spanner: Google's Globally Distributed Database")
    )
    assertEquals(TitleText.words("Cyber-security"), TitleText.words("Cyber security"))
    assertEquals(TitleText.words("!!!"), Vector.empty)
    assertEquals(TitleText.words(null), Vector.empty)
  }

  test("the facade's normalizeName and normalizeTitle expose the folded forms") {
    assertEquals(EntityResolution.normalizeName("Christopher Frost 0001"), "christopher frost")
    assertEquals(
      EntityResolution.normalizeTitle("Software development in disruptive times."),
      "software development in disruptive times"
    )
  }
}
