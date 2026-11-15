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

  // Semantic Scholar and repository URL values below are synthetic: no selected dataset carries
  // them, and none is asserted to identify a real paper, author, or repository.
  private val hex40 = "0123456789abcdef0123456789abcdef01234567"

  test("Semantic Scholar corpus ID: digits without a leading zero, CorpusId: prefix accepted") {
    assertEquals(Identifiers.normalizeS2CorpusId("CorpusId:123456"), Right("123456"))
    assertEquals(Identifiers.normalizeS2CorpusId("corpusid:123456"), Right("123456"))
    assertEquals(Identifiers.normalizeS2CorpusId("123456"), Right("123456"))
    assert(Identifiers.isValidS2CorpusId("1"))
    assert(Identifiers.isValidS2CorpusId("9" * 18))
    assert(!Identifiers.isValidS2CorpusId("9" * 19), "beyond a signed 64-bit integer")
    for (bad <- Vector("0123", "0", "", "12a", "-1", "CorpusId:", "CorpusId: 1", " 123", null))
      assert(Identifiers.normalizeS2CorpusId(bad).isLeft, String.valueOf(bad))
  }

  test("Semantic Scholar paper ID: 40 lowercase hex; bare (any case) and web URL forms") {
    assertEquals(Identifiers.normalizeS2PaperId(hex40), Right(hex40))
    assertEquals(Identifiers.normalizeS2PaperId(hex40.toUpperCase), Right(hex40))
    assertEquals(
      Identifiers.normalizeS2PaperId(
        s"https://www.semanticscholar.org/paper/Some-Title-Slug/$hex40"
      ),
      Right(hex40)
    )
    assertEquals(
      Identifiers.normalizeS2PaperId(s"https://semanticscholar.org/paper/$hex40?utm=x#ref"),
      Right(hex40)
    )
    assert(Identifiers.isValidS2PaperId(hex40))
    assert(!Identifiers.isValidS2PaperId(hex40.toUpperCase), "canonical form is lowercase")
    for (
      bad <- Vector(
        hex40.dropRight(1),
        hex40 + "8",
        hex40.replace('a', 'g'),
        s"https://www.semanticscholar.org/author/$hex40",
        s"https://example.org/paper/$hex40",
        "123456",
        null
      )
    ) assert(Identifiers.normalizeS2PaperId(bad).isLeft, String.valueOf(bad))
  }

  test("Semantic Scholar author ID: digits, bare or author URL; distinct from paper forms") {
    assertEquals(Identifiers.normalizeS2AuthorId("1741101"), Right("1741101"))
    assertEquals(
      Identifiers.normalizeS2AuthorId("https://www.semanticscholar.org/author/Ada-Example/1741101"),
      Right("1741101")
    )
    assertEquals(
      Identifiers.normalizeS2AuthorId("https://www.semanticscholar.org/author/1741101/"),
      Right("1741101")
    )
    assert(Identifiers.normalizeS2AuthorId("https://www.semanticscholar.org/paper/1741101").isLeft)
    assert(
      Identifiers.normalizeS2AuthorId("CorpusId:1741101").isLeft,
      "a corpus ID is not an author ID"
    )
    assert(Identifiers.normalizeS2AuthorId(hex40).isLeft)
    assert(Identifiers.normalizeS2AuthorId("01741101").isLeft)
  }

  test("repository URL: canonical form https://<host>/<path>, scheme and host lowercased") {
    val canonical = "https://github.com/example-org/example-repo"
    val forms = Vector(
      canonical,
      "https://github.com/example-org/example-repo/",
      "https://github.com/example-org/example-repo.git",
      "https://github.com/example-org/example-repo.git/",
      "HTTPS://GitHub.COM/Example-Org/Example-Repo",
      "http://www.github.com/example-org/example-repo",
      "https://github.com:443/example-org/example-repo",
      "https://github.com/example-org/example-repo?tab=readme#install",
      "git+https://github.com/example-org/example-repo.git",
      "ssh://git@github.com/example-org/example-repo.git",
      "git@github.com:example-org/example-repo.git",
      "https://user:secret@github.com/example-org/example-repo"
    )
    forms.foreach(f => assertEquals(Identifiers.normalizeRepositoryUrl(f), Right(canonical), f))
    assert(Identifiers.isCanonicalRepositoryUrl(canonical))
    assert(!Identifiers.isCanonicalRepositoryUrl(canonical + "/"))
    assert(!Identifiers.isCanonicalRepositoryUrl(canonical + ".git"))
    assert(!Identifiers.isCanonicalRepositoryUrl("http://github.com/example-org/example-repo"))
  }

  test("repository URL: path case is preserved except on case-insensitive hosts") {
    assertEquals(
      Identifiers.normalizeRepositoryUrl("https://Git.Example.ORG/Group/SubGroup/Repo.git"),
      Right("https://git.example.org/Group/SubGroup/Repo"),
      "an unlisted host keeps its path case and its full path (subgroups are distinct repositories)"
    )
    assertNotEquals(
      Identifiers.normalizeRepositoryUrl("https://git.example.org/group/sub/repo-a"),
      Identifiers.normalizeRepositoryUrl("https://git.example.org/group/sub/repo-b")
    )
    assertEquals(Identifiers.CaseInsensitiveRepositoryHosts, Set("github.com"))
  }

  test("repository URL: refusals never guess and never echo credentials") {
    val refused = Vector(
      "https://github.com/example-org",
      "https://github.com/example-org/example-repo/tree/main",
      "https://github.com",
      "https://github.com//example-repo",
      "https://github.com/example-org/../example-repo",
      "https://github.com/example-org/.git",
      "https://github.com/example%20org/repo",
      "https://github.com:8443/example-org/example-repo",
      "ftp://github.com/example-org/example-repo",
      "git://github.com/example-org/example-repo",
      "https://localhost/example-org/example-repo",
      "https://github.com/example org/repo",
      "github.com/example-org/example-repo",
      "",
      null
    )
    refused.foreach(r => assert(Identifiers.normalizeRepositoryUrl(r).isLeft, String.valueOf(r)))
    val withSecret = "https://user:hunter2@github.com:8443/example-org/example-repo"
    val err = ExactId.of(ExactIdScheme.RepositoryUrl, withSecret)
    assert(err.isLeft)
    assert(!err.left.toOption.get.message.contains("hunter2"), err.left.toOption.get.message)
    assert(err.left.toOption.get.message.contains(Identifiers.WithheldRepositoryUrl))
    val stored = ExactId.check("x", ExactId(ExactIdScheme.RepositoryUrl, withSecret))
    assert(stored.nonEmpty && stored.forall(!_.message.contains("hunter2")))
  }

  // ------------------------------------------------ DE-P5-02: D2 / RV-P5-04 m10 credential echo
  //
  // SYNTHETIC credentials only. Every check looks at both the message and the case-class rendering
  // (toString) of each error, through ExactId.of (raw input) and ExactId.check (a stored value).

  private val token = "S3cr3tSyntheticTok"

  private def assertNoEcho(raw: String)(using munit.Location): Unit = {
    def clean(e: ValidationError) =
      assert(!e.toString.contains(token) && !e.message.contains(token), s"echoed: $e")
    ExactId.of(ExactIdScheme.RepositoryUrl, raw) match {
      case Left(e) => clean(e)
      case Right(id) => assert(!id.value.contains(token), s"accepted with the secret: $id")
    }
    ExactId.check("identifiers[0]", ExactId(ExactIdScheme.RepositoryUrl, raw)).foreach(clean)
  }

  test("credential echo: whitespace-padded URLs with user information are never echoed") {
    val raws = Vector(
      s" https://deploy:$token@gitlab.example.org/group/repo",
      s"\thttps://deploy:$token@gitlab.example.org/group/repo",
      s"https://deploy:$token@gitlab.example.org/group/repo ",
      s"https://deploy:$token@gitlab.example.org/group/repo\n"
    )
    raws.foreach { r =>
      assert(ExactId.of(ExactIdScheme.RepositoryUrl, r).isLeft, r)
      assertNoEcho(r)
    }
  }

  test("credential echo: a password containing '/' is never echoed") {
    val raws = Vector(
      s"https://deploy:$token/x@gitlab.example.org/group/repo",
      s"https://deploy:a/$token@gitlab.example.org/group/repo",
      s"https://deploy:12345/$token@gitlab.example.org/group/repo"
    )
    raws.foreach { r =>
      assert(ExactId.of(ExactIdScheme.RepositoryUrl, r).isLeft, r)
      assertNoEcho(r)
    }
  }

  test("credential echo: scp-like user:pass@host:path and scheme-less user information") {
    val raws = Vector(
      s"deploy:$token@gitlab.example.org:group/repo.git",
      s"deploy:$token@gitlab.example.org/group/repo",
      s"$token@gitlab.example.org/group/repo",
      s"//deploy:$token@gitlab.example.org/group/repo"
    )
    raws.foreach { r =>
      assert(ExactId.of(ExactIdScheme.RepositoryUrl, r).isLeft, r)
      assertNoEcho(r)
    }
  }

  test("credential echo: credentials in query parameters are never echoed or kept") {
    val params = Vector("access_token", "token", "private_token", "password", "key")
    params.foreach { p =>
      // Refused on a host whose query may name the repository (D3), and not echoed.
      val other = s"https://gitlab.example.org/group/repo?$p=$token"
      assert(ExactId.of(ExactIdScheme.RepositoryUrl, other).isLeft, other)
      assertNoEcho(other)
      // Accepted on a query-ignoring host, with the query dropped; the stored raw form is not
      // canonical, and its validation error does not echo it either.
      val gh = s"https://github.com/example-org/example-repo?$p=$token"
      assertEquals(
        ExactId.of(ExactIdScheme.RepositoryUrl, gh).map(_.value),
        Right("https://github.com/example-org/example-repo"),
        gh
      )
      assert(ExactId.check("x", ExactId(ExactIdScheme.RepositoryUrl, gh)).nonEmpty, gh)
      assertNoEcho(gh)
    }
  }

  test("credential echo: errorDisplay shows a fixed placeholder for every repository-URL value") {
    val values = Vector(
      "https://github.com/example-org/example-repo",
      s"https://deploy:$token@gitlab.example.org/group/repo",
      s" https://deploy:$token@gitlab.example.org/group/repo",
      "",
      "not a url"
    )
    values.foreach(v =>
      assertEquals(
        Identifiers.errorDisplay(ExactIdScheme.RepositoryUrl, v),
        Identifiers.WithheldRepositoryUrl,
        v
      )
    )
    assertEquals(Identifiers.errorDisplay(ExactIdScheme.RepositoryUrl, null), "null")
    assertEquals(Identifiers.errorDisplay(ExactIdScheme.Doi, "10.1145/x y"), "10.1145/x y")
  }

  // ------------------------------------------------ DE-P5-02: D3 query-named repositories

  test("repository URL: a non-empty query is refused on hosts where it may name the repository") {
    val alpha = "https://git.example.org/gitweb/index.cgi?p=alpha.git"
    val beta = "https://git.example.org/gitweb/index.cgi?p=beta.git"
    assert(Identifiers.normalizeRepositoryUrl(alpha).isLeft, alpha)
    assert(Identifiers.normalizeRepositoryUrl(beta).isLeft, beta)
    assert(Identifiers.normalizeRepositoryUrl("https://gitlab.com/group/repo?ref=main").isLeft)
    assert(Identifiers.normalizeRepositoryUrl("ssh://git@git.example.org/a/b?x=1").isLeft)
    assertEquals(Identifiers.QueryIgnoringRepositoryHosts, Set("github.com"))
  }

  test("repository URL: fragments and empty queries are dropped; github drops any query") {
    val r = Identifiers.normalizeRepositoryUrl
    assertEquals(
      r("https://git.example.org/group/repo#readme"),
      Right("https://git.example.org/group/repo")
    )
    assertEquals(
      r("https://git.example.org/group/repo?"),
      Right("https://git.example.org/group/repo")
    )
    assertEquals(
      r("https://git.example.org/group/repo?#x"),
      Right("https://git.example.org/group/repo")
    )
    assertEquals(
      r("https://github.com/example-org/example-repo?tab=readme#install"),
      Right("https://github.com/example-org/example-repo")
    )
    assertEquals(
      r("https://www.github.com/Example-Org/Example-Repo.git?p=other.git"),
      Right("https://github.com/example-org/example-repo"),
      "on github the path, not the query, names the repository"
    )
  }

  test("repository URL normalization is idempotent") {
    val inputs = Vector(
      "git@github.com:Example-Org/Example-Repo.git",
      "https://Git.Example.org/Group/Repo/",
      "ssh://git@git.example.org:22/a/b/c.git"
    )
    inputs.foreach { i =>
      val once = Identifiers.normalizeRepositoryUrl(i)
      assert(once.isRight, i)
      assertEquals(once.flatMap(Identifiers.normalizeRepositoryUrl), once, i)
    }
  }

  test("new exact schemes: validateExact accepts only canonical forms; normalizeExact rewrites") {
    val cases = Vector(
      (ExactIdScheme.SemanticScholarCorpus, "CorpusId:42", "42"),
      (ExactIdScheme.SemanticScholarPaper, hex40.toUpperCase, hex40),
      (ExactIdScheme.SemanticScholarAuthor, "https://www.semanticscholar.org/author/7", "7"),
      (
        ExactIdScheme.RepositoryUrl,
        "git@github.com:Org/Repo.git",
        "https://github.com/org/repo"
      )
    )
    cases.foreach { case (s, raw, canonical) =>
      assertEquals(Identifiers.normalizeExact(s, raw), Right(canonical), s.tag)
      assertEquals(Identifiers.validateExact(s, canonical), Right(canonical), s.tag)
      assert(Identifiers.validateExact(s, raw).isLeft, s"${s.tag}: raw form is not canonical")
      assertEquals(ExactId.of(s, raw), Right(ExactId(s, canonical)), s.tag)
    }
    // A digit string is valid for corpus and author IDs alike; the scheme keeps them apart.
    assertNotEquals(
      ExactId(ExactIdScheme.SemanticScholarCorpus, "42"),
      ExactId(ExactIdScheme.SemanticScholarAuthor, "42")
    )
    assertEquals(ExactIdScheme.SemanticScholarAuthor.entityKind, MatchEntityKind.Researcher)
    assertEquals(ExactIdScheme.SemanticScholarCorpus.entityKind, MatchEntityKind.Work)
    assertEquals(ExactIdScheme.SemanticScholarPaper.entityKind, MatchEntityKind.Work)
    assertEquals(ExactIdScheme.RepositoryUrl.entityKind, MatchEntityKind.Work)
  }

  test("external IDs are validated per scheme") {
    assert(Identifiers.validateExternal(ExternalIdScheme.Orcid, "0000-0002-1825-0097").isRight)
    assert(Identifiers.validateExternal(ExternalIdScheme.Orcid, "0000-0002-1825-0098").isLeft)
    assert(Identifiers.validateExternal(ExternalIdScheme.CrossrefMember, "320").isRight)
    assert(Identifiers.validateExternal(ExternalIdScheme.CrossrefMember, "32a").isLeft)
    assert(Identifiers.validateExternal(ExternalIdScheme.Doi, "10.1145/ABC").isLeft)
  }
}
