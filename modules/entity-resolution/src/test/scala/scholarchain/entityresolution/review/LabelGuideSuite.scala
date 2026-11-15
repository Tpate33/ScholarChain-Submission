package scholarchain.entityresolution.review

import upickle.default.{read, write}

import scholarchain.entityresolution.Samples
import scholarchain.entityresolution.policy.PolicyFixtures.*

/**
 * The versioned label guide er-label-guide.v1 (human decision 1, 2026-11-06, Option A) and its use
 * in the blind view. No labels anywhere.
 */
class LabelGuideSuite extends munit.FunSuite {

  private val guide: String = LabelGuide.text().fold(e => fail(e), identity)

  test("the guide is versioned and states both questions verbatim, with their targets") {
    assertEquals(LabelGuide.Version, "er-label-guide.v1")
    assert(guide.startsWith("er-label-guide.v1\n"), guide.take(40))
    assert(guide.contains(s"QUESTION work: ${LabelGuide.WorkQuestion}\n"))
    assert(guide.contains(s"QUESTION researcher: ${LabelGuide.ResearcherQuestion}\n"))
    assert(guide.contains("labelingTarget publication-stage"))
    assert(guide.contains("labelingTarget person"))
    assertEquals(LabelGuide.target("work"), Some("publication-stage"))
    assertEquals(LabelGuide.target("researcher"), Some("person"))
    assertEquals(LabelGuide.target("venue"), None)
  }

  test("the blind-view questions are the guide's questions; the researcher question is unchanged") {
    assertEquals(BlindView.question("work"), LabelGuide.WorkQuestion)
    assertEquals(BlindView.question("researcher"), LabelGuide.ResearcherQuestion)
    assertEquals(
      LabelGuide.ResearcherQuestion,
      "Do endpoint A and endpoint B refer to the same person? Answer match, non-match, or unsure."
    )
    assert(
      LabelGuide.WorkQuestion.contains("same publication entity at the same publication stage")
    )
    assert(LabelGuide.WorkQuestion.contains("revisions within that entity"))
  }

  test("no 'byte-identical' or 'same text' wording in the question or the guide") {
    val forbidden = Vector("byte-identical", "byte identical", "same text", "identical text")
    (Vector(LabelGuide.WorkQuestion, LabelGuide.ResearcherQuestion, guide)).foreach { t =>
      val lower = t.toLowerCase
      forbidden.foreach(f => assert(!lower.contains(f), s"'$f' in: ${t.take(80)}"))
    }
  }

  test(
    "preprint vs published article is explicitly a non-match; the Option A edge cases are listed"
  ) {
    assert(
      guide.contains(
        "A preprint and the published article or published conference paper of the same " +
          "research are a non-match"
      )
    )
    Vector(
      "1. Preprint vs published version (version of record): N.",
      "2. Extended journal version of a conference paper: N.",
      "3. Corrected version (the same DOI updated, or arXiv vN of one preprint): M.",
      "4. Erratum or corrigendum notice vs the article it corrects: N.",
      "5. Retraction notice vs the retracted paper: N.",
      "6. Retracted paper (flagged as retracted) vs another record of the same paper: M.",
      "7. Same-title editorial, preface or introduction in a different year: N.",
      "8. Thesis vs paper: N.",
      "9. One publication registered under two DOIs"
    ).foreach(e => assert(guide.contains(e), e))
  }

  test("one question per kind: no second (lifecycle) question in the guide or the view") {
    assertEquals("QUESTION ".r.findAllMatchIn(guide).size, 2)
    assert(!BlindView.VisibleFields.exists(f => f.toLowerCase.contains("lifecycle")))
    assert(!BlindView.VisibleFields.contains("questions"))
  }

  test("labelingTarget and labelGuideVersion round-trip through a v5 blind row") {
    val works = Vector(Samples.spannerDblp, Samples.spannerCrossref)
    val evidence = BlindView.index(works, Vector.empty)
    val c = workCandidate(Samples.spannerDblp, Samples.spannerCrossref)
    val member =
      GoldMembership.memberId("work", c.sourceIdA, c.sourceIdB).fold(e => fail(e), identity)
    val row = BlindView
      .row(BlindItem(member, "work", c.sourceIdA, c.sourceIdB), evidence)
      .fold(e => fail(e), identity)
    assertEquals(row.schemaVersion, "er-blind-label-view.v5")
    assertEquals(
      (row.labelingTarget, row.labelGuideVersion),
      ("publication-stage", "er-label-guide.v1")
    )
    val json = write(row)
    assert(json.contains("\"labelingTarget\":\"publication-stage\""), json.take(200))
    assertEquals(read[BlindRow](json), row)
  }
}
