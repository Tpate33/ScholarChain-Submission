package scholarchain.core

class IdentifiersSuite extends munit.FunSuite {

  test("ORCID: ISO 7064 MOD 11-2 check character") {
    assertEquals(Identifiers.orcidCheckChar("000000021825009"), '7')
    assertEquals(Identifiers.orcidCheckChar("000000022814962"), 'X')
    assert(Identifiers.isValidOrcid("0000-0002-1825-0097"))
    assert(Identifiers.isValidOrcid("0000-0002-2814-962X"))
    assert(Identifiers.isValidOrcid("0000-0002-4303-3908"))
    assert(!Identifiers.isValidOrcid("0000-0002-1825-0098"), "wrong check digit")
    assert(!Identifiers.isValidOrcid("0000-0002-1825-009"), "too short")
    assert(!Identifiers.isValidOrcid("0000000218250097"), "no hyphens")
    assert(!Identifiers.isValidOrcid("0000-0002-2814-962x"), "lowercase x")
  }

  test("ORCID: URI form normalizes to the bare form") {
    assertEquals(
      Identifiers.normalizeOrcid("https://orcid.org/0000-0002-1825-0097"),
      Right("0000-0002-1825-0097")
    )
    assert(Identifiers.normalizeOrcid("https://orcid.org/0000-0002-1825-0098").isLeft)
  }

  test("DOI: resolver prefixes stripped and lowercased") {
    assertEquals(
      Identifiers.normalizeDoi("https://doi.org/10.1145/2491245"),
      Right("10.1145/2491245")
    )
    assertEquals(Identifiers.normalizeDoi("doi:10.1145/ABC.Def"), Right("10.1145/abc.def"))
    assert(Identifiers.isCanonicalDoi("10.1145/3447741"))
    assert(!Identifiers.isCanonicalDoi("10.1145/ABC"), "uppercase is not canonical")
    assert(!Identifiers.isCanonicalDoi("11.1145/x"))
    assert(!Identifiers.isCanonicalDoi("10.1145/a b"))
    assert(!Identifiers.isCanonicalDoi("https://doi.org/10.1145/x"))
  }

  test("ISSN: mod-11 check character") {
    // 0001-0782 is an external, published check-digit vector (a registered ISSN), kept here only as
    // an oracle independent of this code. 0000-0019 is the synthetic fixture value, hand-computed.
    assert(Identifiers.isValidIssn("0001-0782"))
    assert(Identifiers.isValidIssn("0000-0019"))
    assert(!Identifiers.isValidIssn("0000-0018"))
    assert(!Identifiers.isValidIssn("0001-0783"))
    assert(!Identifiers.isValidIssn("00010782"))
  }

  test("ROR: 9-character suffix, URI accepted") {
    assertEquals(Identifiers.normalizeRor("https://ror.org/00e5k0821"), Right("00e5k0821"))
    assert(!Identifiers.isValidRor("00e5k082"))
    assert(!Identifiers.isValidRor("10e5k0821"), "must start with 0")
    assert(!Identifiers.isValidRor("00e5l0821"), "l is not Crockford base32")
  }

  test("OpenAlex: entity letter plus digits, URI accepted") {
    assertEquals(
      Identifiers.normalizeOpenAlex('W', "https://openalex.org/W3137759927"),
      Right("W3137759927")
    )
    assert(Identifiers.normalizeOpenAlex('W', "A5113870699").isLeft)
    assert(!Identifiers.isValidOpenAlex('W', "W"))
    assert(!Identifiers.isValidOpenAlex('W', "W12a"))
  }

  test("DBLP keys and person IDs (observed forms)") {
    assert(Identifiers.isValidDblpKey("journals/cacm/PopperMP21"))
    assert(Identifiers.isValidDblpKey("conf/osdi/ChangDGHWBCFG06"))
    assert(!Identifiers.isValidDblpKey("journals"))
    assert(!Identifiers.isValidDblpKey("journals//x"))
    assert(!Identifiers.isValidDblpKey("journals/a b"))
    assert(Identifiers.isValidDblpPid("11/3019"))
    assert(Identifiers.isValidDblpPid("69/6042-1"))
    assert(Identifiers.isValidDblpPid("d/JeffreyDean"))
    assert(!Identifiers.isValidDblpPid("113019"))
  }

  test("arXiv identifiers, new and old style") {
    assert(Identifiers.isValidArxiv("2101.00001"))
    assert(Identifiers.isValidArxiv("2101.00001v2"))
    assert(Identifiers.isValidArxiv("cs/0112017"))
    assert(!Identifiers.isValidArxiv("arXiv:2101.00001"))
  }

  test("external IDs are validated per scheme") {
    assert(Identifiers.validateExternal(ExternalIdScheme.Orcid, "0000-0002-1825-0097").isRight)
    assert(Identifiers.validateExternal(ExternalIdScheme.Orcid, "0000-0002-1825-0098").isLeft)
    assert(Identifiers.validateExternal(ExternalIdScheme.CrossrefMember, "320").isRight)
    assert(Identifiers.validateExternal(ExternalIdScheme.CrossrefMember, "32a").isLeft)
    assert(Identifiers.validateExternal(ExternalIdScheme.Doi, "10.1145/ABC").isLeft)
  }
}
