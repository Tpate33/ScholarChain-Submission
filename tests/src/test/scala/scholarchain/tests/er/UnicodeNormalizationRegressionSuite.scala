package scholarchain.tests.er

import scholarchain.core.*
import scholarchain.core.ExactIdScheme as S
import scholarchain.entityresolution.{
  BlockingKeys,
  MatchFeatures,
  PersonName,
  TextFolding,
  TitleText
}
import scholarchain.entityresolution.identifiers.{ExactIdentifiers, IdAgreement}
import scholarchain.entityresolution.policy.*

import ErFixtures.*

/**
 * TE-P5-01 area 7: normalization regressions after the Phase 5 changes, and the accepted Unicode
 * limitations of the prerequisite (prereq-unicode-limitations.md: Cf joins words, NIT-1, NIT-2),
 * pinned as CHARACTERIZATION so that a silent fix or a silent regression is visible. Code points
 * are written as explicit hex values. Every name is SYNTHETIC.
 */
class UnicodeNormalizationRegressionSuite extends munit.FunSuite {

  def cp(c: Int*): String = c.map(x => new String(Character.toChars(x))).mkString

  test("confusables never fold to the Latin name") {
    val latin = PersonName.parse("Jian Wang")
    Vector(
      "Jian W" + cp(0x430) + "ng", // Cyrillic a
      "Jian W" + cp(0x251) + "ng", // Latin alpha
      "Ji" + cp(0x430) + "n Wang" // Cyrillic a in the given name
    ).foreach { n =>
      val p = PersonName.parse(n)
      assert(p.familyKey != latin.familyKey || p.givenParts != latin.givenParts, n)
    }
    assertNotEquals(
      PersonName.parse("Christina P" + cp(0x3bf) + "pper").familyBlockKey,
      PersonName.parse("Christina Popper").familyBlockKey,
      "Greek omicron"
    )
  }

  test("combining marks: precomposed, decomposed, and digraph spellings share one block key") {
    val keys = Vector(
      "Christina P" + cp(0xf6) + "pper",
      "Christina Po" + cp(0x308) + "pper",
      "Christina Poepper",
      "Popper, Christina"
    ).map(PersonName.parse(_).familyBlockKey)
    assertEquals(keys.distinct, Vector("popper"))
    assertEquals(
      PersonName.parse("Christina P" + cp(0xf6) + "pper").familyKey,
      PersonName.parse("Christina Po" + cp(0x308) + "pper").familyKey,
      "NFC and NFD family keys are equal"
    )
  }

  test("initials and name order") {
    assertEquals(PersonName.parse("J. C. Corbett").initials, "jc")
    assertEquals(PersonName.parse("James C. Corbett").initials, "jc")
    val inverted = PersonName.parse("Corbett, James C.")
    assertEquals((inverted.familyKey, inverted.initials), ("corbett", "jc"))
    assertEquals(PersonName.parse("Wang, Jun").familyKey, PersonName.parse("Jun Wang").familyKey)
    assertEquals(PersonName.parse("Wang, Jun").givenParts, PersonName.parse("Jun Wang").givenParts)
    assertEquals(PersonName.parse("Roberto Di Pietro").familyKey, "dipietro")
    assertEquals(PersonName.parse("Di Pietro, Roberto").familyKey, "dipietro")
    val hyphen = PersonName.parse("Jean-Pierre Garcia-Molina")
    assertEquals((hyphen.givenParts, hyphen.familyKey), (Vector("jean", "pierre"), "garciamolina"))
    val homonym = PersonName.parse("Quentin Synthwell 0002")
    assertEquals((homonym.familyKey, homonym.homonymSuffix), ("synthwell", Some("0002")))
  }

  test(
    "CHARACTERIZATION (finding): a homonym number before the comma of a Family, Given name is not parsed"
  ) {
    // PersonName.parse documents only a TRAILING homonym number. The blind-view masking rule handles
    // "Synthwell 0002, Quentin", but the parser keeps the number in the family key. DBLP writes
    // "Given Family 0002", so this form is not expected from DBLP; pinned as current behaviour.
    val inverted = PersonName.parse("Synthwell 0002, Quentin")
    assertEquals((inverted.familyKey, inverted.homonymSuffix), ("synthwell0002", None))
  }

  test(
    "DOI prefix and case normalization agree across resolver forms; malformed DOIs are refused"
  ) {
    val canonical = Right("10.9999/synthetic.abc")
    Vector(
      "10.9999/SYNTHETIC.ABC",
      "https://doi.org/10.9999/Synthetic.Abc",
      "http://dx.doi.org/10.9999/synthetic.abc",
      "https://www.doi.org/10.9999/synthetic.abc",
      "DOI:10.9999/synthetic.abc",
      "doi.org/10.9999/synthetic.abc",
      "  10.9999/synthetic.abc\t"
    ).foreach(raw => assertEquals(ExactIdentifiers.normalize(S.Doi, raw), canonical, raw))
    Vector(
      "10.9999 /synthetic.abc",
      "10.99/synthetic.abc",
      "11.9999/synthetic.abc",
      cp(0xff11, 0xff10) + ".9999/synthetic.abc",
      "https://doi.org.evil.example/10.9999/synthetic.abc",
      ""
    ).foreach(raw => assert(ExactIdentifiers.normalize(S.Doi, raw).isLeft, raw))
    assertEquals(
      ExactIdentifiers.compare(
        S.Doi,
        Vector("https://doi.org/10.9999/SYNTHETIC.ABC"),
        Vector("10.9999/synthetic.abc")
      ),
      IdAgreement.Agree
    )
  }

  test("arXiv: prefixes and URLs normalize; versions of one ID are compared as one value") {
    Vector(
      "arXiv:2101.00001v2" -> "2101.00001v2",
      "https://arxiv.org/abs/2101.00001v3" -> "2101.00001v3",
      "https://arxiv.org/pdf/2101.00001v1.pdf" -> "2101.00001v1",
      "http://export.arxiv.org/abs/2101.00001" -> "2101.00001",
      "hep-th/9901001v2" -> "hep-th/9901001v2"
    ).foreach { case (raw, want) =>
      assertEquals(ExactIdentifiers.normalize(S.Arxiv, raw), Right(want), raw)
    }
    def cmp(a: String, b: String) = ExactIdentifiers.compare(S.Arxiv, Vector(a), Vector(b))
    assertEquals(cmp("2101.00001v1", "2101.00001v2"), IdAgreement.Agree)
    assertEquals(cmp("2101.00001", "arXiv:2101.00001v7"), IdAgreement.Agree)
    assertEquals(cmp("hep-th/9901001v2", "hep-th/9901001"), IdAgreement.Agree)
    assertEquals(cmp("2101.00001v1", "2101.00011v1"), IdAgreement.Conflict)
    assertEquals(cmp("2101.00001", "2101.000010"), IdAgreement.Absent, "invalid value is dropped")
  }

  test("policy: two versions of one arXiv ID are never an identifier disagreement") {
    val o = work(
      bandWork,
      Vector(ExactId(S.Arxiv, "2101.00001v1")),
      Vector(ExactId(S.Arxiv, "2101.00001v2"))
    ).decide()
    assertNotEquals(o.outcome, MatchOutcome.Conflict)
    assertEquals(o.conflicts, Vector.empty)
    assertEquals(o.plausibility, None, "no T1 disagreement, so no plausibility test")
  }

  test(
    "RV-P5-04 m7 (decisions-11 item 3, was a CHARACTERIZATION): two versions of one arXiv ID are an exact-id agreement of two pure preprints"
  ) {
    // Under er-policy.v7 this pair was decided by the score (REJECTED), because candidate
    // agreements (er-features.v2, computed by Hadoop) are raw equality. er-policy.v8 compares the
    // base submission ID in the policy layer; the candidate itself is unchanged (er-features.v2).
    val built = work(
      nothingWork,
      Vector(ExactId(S.Arxiv, "2101.00001v1")),
      Vector(ExactId(S.Arxiv, "2101.00001v2"))
    )
    assertEquals(built.candidate.exactIdAgreements, Vector.empty)
    assertEquals(built.candidate.features("id.arxiv.conflict"), 1.0)
    val o = built.decide()
    assertEquals(policy.policyVersion, "er-policy.v8")
    assertEquals(
      (o.outcome, o.path, o.confidence),
      (MatchOutcome.Accepted, MatchPath.ExactId, 0.99)
    )
    assertEquals(o.stage.map(_.verdict), Some(StageVerdict.SamePreprint))
    assert(o.rulesFired.contains(Rules.exactAgree("arxiv")), s"${o.rulesFired}")
    assert(o.reasonCodes.contains("arxiv-agreement-by-base-submission-id"), s"${o.reasonCodes}")
    // The original versioned values are kept; the candidate field is untouched.
    assertEquals(
      o.policyAgreements,
      Vector(ExactId(S.Arxiv, "2101.00001v1"), ExactId(S.Arxiv, "2101.00001v2"))
    )
    assertEquals(o.exactIdAgreements, Vector.empty)
    assertEquals(o.conflicts, Vector.empty)
  }

  // ------------------------------------------------------------------ accepted limitations

  test("accepted limitation: a Cf character as the only separator joins words (no silent fix)") {
    val zwsp = cp(0x200b)
    assertEquals(TextFolding.fold("Jun" + zwsp + "Wang", keepHyphen = false), "junwang")
    assertEquals(PersonName.parse("Jun" + zwsp + "Wang").familyKey, "junwang")
    assertEquals(TextFolding.fold("Wa" + zwsp + "ng, Jun", keepHyphen = false), "wang jun")
  }

  test(
    "accepted limitation NIT-2: a ZWSP before a DBLP homonym number hides the number (no silent fix)"
  ) {
    val withSpace = PersonName.parse("Christopher Frost 0001")
    assertEquals((withSpace.familyKey, withSpace.homonymSuffix), ("frost", Some("0001")))
    val withZwsp = PersonName.parse("Christopher Frost" + cp(0x200b) + "0001")
    assertEquals((withZwsp.familyKey, withZwsp.homonymSuffix), ("frost0001", None))
  }

  test("accepted limitation NIT-1: lone surrogates around a Cf character (no silent fix)") {
    val s = 0xd800.toChar.toString + cp(0x200b) + 0xdc00.toChar.toString
    assertEquals(TextFolding.fold(s, keepHyphen = false), "")
    assertEquals(TitleText.words(s), Vector(cp(0x10000)))
  }

  test("pinned versions: blocking and features stay at v2; the policy reads only v2 candidates") {
    assertEquals(BlockingKeys.Version, "er-blocking.v2")
    assertEquals(MatchFeatures.Version, "er-features.v2")
    assertEquals(
      (policy.blockingVersion, policy.featureVersion),
      ("er-blocking.v2", "er-features.v2")
    )
  }
}
