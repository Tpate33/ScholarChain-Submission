package scholarchain.entityresolution.identifiers

import scholarchain.core.{ExactId, ExactIdScheme, Identifiers}
import scholarchain.core.ExactIdScheme.*

import ExactIdentifiers.{compare, normalize}

class ExactIdentifiersSuite extends munit.FunSuite {

  private def same(s: ExactIdScheme, expected: String, raws: String*): Unit =
    raws.foreach(r => assertEquals(normalize(s, r), Right(expected), s"${s.tag} '$r'"))

  private def refused(s: ExactIdScheme, raws: String*): Unit =
    raws.foreach(r => assert(normalize(s, r).isLeft, s"${s.tag} '$r' should be refused"))

  test("DOI: resolver prefixes, doi: prefix, case, and whitespace") {
    same(
      Doi,
      "10.1145/2491245",
      "10.1145/2491245",
      " https://doi.org/10.1145/2491245 ",
      "http://dx.doi.org/10.1145/2491245",
      "https://www.doi.org/10.1145/2491245",
      "doi.org/10.1145/2491245",
      "DOI:10.1145/2491245",
      "doi:10.1145/2491245"
    )
    same(Doi, "10.1000/abc.def", "10.1000/ABC.DEF")
    refused(Doi, "2491245", "https://example.org/10.1145/2491245", "10.1/x", "")
  }

  test("arXiv: prefixes and URL forms; versions are kept but compare as one work") {
    same(
      Arxiv,
      "2101.00001",
      "2101.00001",
      "https://arxiv.org/abs/2101.00001",
      "ARXIV:2101.00001"
    )
    same(Arxiv, "2101.00001v3", "arXiv:2101.00001v3")
    same(Arxiv, "2101.00001v2", "http://arxiv.org/pdf/2101.00001v2.pdf")
    same(Arxiv, "hep-th/9901001", "arxiv.org/abs/hep-th/9901001")
    refused(Arxiv, "2101.001", "arXiv:", "10.48550/arXiv.2101.00001")
    assertEquals(
      compare(Arxiv, Vector("arXiv:2101.00001v3"), Vector("2101.00001v1")),
      IdAgreement.Agree
    )
    assertEquals(
      compare(Arxiv, Vector("hep-th/9901001v2"), Vector("hep-th/9901001")),
      IdAgreement.Agree
    )
    assertEquals(compare(Arxiv, Vector("2101.00001"), Vector("2101.00002")), IdAgreement.Conflict)
  }

  test("ORCID: URI forms, lower-case check character, and the MOD 11-2 checksum") {
    same(
      Orcid,
      "0000-0002-2814-962X",
      "0000-0002-2814-962X",
      "https://orcid.org/0000-0002-2814-962X",
      "orcid.org/0000-0002-2814-962x",
      "http://www.orcid.org/0000-0002-2814-962X"
    )
    refused(
      Orcid,
      "0000-0002-2814-9621",
      "0000000228149 62X",
      "https://example.org/0000-0002-2814-962X"
    )
  }

  test("OpenAlex: W and A are separate schemes; URI forms and letter case") {
    same(
      OpenAlexWork,
      "W2741809807",
      "https://openalex.org/W2741809807",
      "openalex.org/works/w2741809807"
    )
    same(OpenAlexWork, "W2741809807", "https://api.openalex.org/works/W2741809807")
    same(OpenAlexAuthor, "A5113870699", "https://openalex.org/A5113870699", "a5113870699")
    refused(OpenAlexWork, "A5113870699", "W", "W12x")
    refused(OpenAlexAuthor, "W2741809807")
    assertEquals(OpenAlexWork.entityKind.tag, "work")
    assertEquals(OpenAlexAuthor.entityKind.tag, "researcher")
  }

  test("Semantic Scholar: corpus, paper, and author IDs use the core schemes and normalizers") {
    same(
      SemanticScholarCorpus,
      "215416146",
      "CorpusId:215416146",
      "corpusid:215416146",
      "215416146"
    )
    refused(SemanticScholarCorpus, "CorpusId:", "0123", "abc")
    val sha = "0123456789abcdef0123456789abcdef01234567"
    same(
      SemanticScholarPaper,
      sha,
      sha,
      sha.toUpperCase,
      s"https://www.semanticscholar.org/paper/Some-Title-Slug/$sha",
      s"https://www.semanticscholar.org/paper/$sha?utm=x"
    )
    refused(SemanticScholarPaper, "0123", "https://www.semanticscholar.org/paper/Slug-Only")
    same(
      SemanticScholarAuthor,
      "1741101",
      "1741101",
      "https://www.semanticscholar.org/author/Some-Name/1741101"
    )
    Vector(SemanticScholarCorpus, SemanticScholarPaper, SemanticScholarAuthor).foreach { s =>
      assertEquals(normalize(s, "215416146"), Identifiers.normalizeExact(s, "215416146"))
    }
  }

  test("repository URLs: the core normalizer; deep paths are never shortened") {
    same(
      RepositoryUrl,
      "https://github.com/apache/spark",
      "https://github.com/apache/spark",
      "http://www.github.com/Apache/Spark/",
      "https://github.com/apache/spark.git",
      "git@github.com:apache/spark.git",
      "git+https://github.com/apache/spark",
      "ssh://git@github.com/apache/spark.git"
    )
    same(RepositoryUrl, "https://gitlab.com/Group/Project", "https://gitlab.com/Group/Project.git")
    refused(
      RepositoryUrl,
      "https://github.com/apache",
      "ftp://github.com/apache/spark",
      "not a url",
      "https://github.com/ap%20ache/spark",
      "https://github.com/apache/spark/tree/master/sql"
    )
  }

  test("repository URLs: gitlab subgroup repositories stay distinct (no two-segment truncation)") {
    // Under the module's former normalizer both became https://gitlab.com/group/sub and agreed,
    // a false merge of two repositories.
    val a = normalize(RepositoryUrl, "https://gitlab.com/group/sub/repoA")
    val b = normalize(RepositoryUrl, "https://gitlab.com/group/sub/repoB.git")
    assertEquals(a, Right("https://gitlab.com/group/sub/repoA"))
    assertEquals(b, Right("https://gitlab.com/group/sub/repoB"))
    assertNotEquals(a, b)
    assertEquals(
      compare(
        RepositoryUrl,
        Vector("https://gitlab.com/group/sub/repoA"),
        Vector("git@gitlab.com:group/sub/repoB.git")
      ),
      IdAgreement.Conflict
    )
    assertNotEquals(
      normalize(RepositoryUrl, "https://gitlab.com/group/sub/repoA"),
      Right("https://gitlab.com/group/sub")
    )
  }

  test("every value this module normalizes is canonical for core (validateExact)") {
    val raws: Vector[(ExactIdScheme, String)] = Vector(
      Doi -> "https://doi.org/10.1145/2491245",
      Arxiv -> "arXiv:2101.00001v3",
      Orcid -> "orcid.org/0000-0002-2814-962x",
      OpenAlexWork -> "openalex.org/works/w2741809807",
      OpenAlexAuthor -> "a5113870699",
      SemanticScholarCorpus -> "CorpusId:215416146",
      SemanticScholarPaper -> ("0123456789abcdef0123456789abcdef01234567".toUpperCase),
      SemanticScholarAuthor -> "1741101",
      RepositoryUrl -> "git@gitlab.com:group/sub/repoA.git"
    )
    assertEquals(raws.map(_._1).toSet.size, raws.size)
    raws.foreach { case (s, raw) =>
      val v = normalize(s, raw).fold(e => fail(s"${s.tag}: $e"), identity)
      assertEquals(Identifiers.validateExact(s, v), Right(v), s.tag)
      assert(ExactId.of(s, v).isRight)
    }
  }

  test("comparison: agree on any shared canonical value, conflict on disjoint sets, else absent") {
    assertEquals(
      compare(Doi, Vector("doi:10.1000/abcd"), Vector("10.1000/ABCD")),
      IdAgreement.Agree
    )
    assertEquals(compare(Doi, Vector("10.1000/a"), Vector("10.1000/b")), IdAgreement.Conflict)
    assertEquals(compare(Doi, Vector.empty, Vector("10.1000/b")), IdAgreement.Absent)
    assertEquals(compare(Doi, Vector("garbage"), Vector("10.1000/b")), IdAgreement.Absent)
    assertEquals(
      compare(RepositoryUrl, Vector("git@github.com:a/b.git"), Vector("https://github.com/A/B")),
      IdAgreement.Agree
    )
  }

  test("disagreement builds the core IdentifierConflict, except for arXiv versions") {
    val a = Vector(ExactId(Doi, "10.1000/a"))
    val b = Vector(ExactId(Doi, "10.1000/b"))
    assertEquals(
      ExactIdentifiers.disagreement(Doi, a, b),
      scholarchain.core.IdentifierConflict.disagreement(Doi, a, b)
    )
    assert(ExactIdentifiers.disagreement(Doi, a, b).isDefined)
    val v1 = Vector(ExactId(Arxiv, "2101.00001v1"))
    val v2 = Vector(ExactId(Arxiv, "2101.00001v2"))
    assert(scholarchain.core.IdentifierConflict.disagreement(Arxiv, v1, v2).isDefined)
    assertEquals(ExactIdentifiers.disagreement(Arxiv, v1, v2), None)
  }
}
