package scholarchain.entityresolution.policy

import scala.collection.immutable.SortedMap

import scholarchain.core.*

import PolicyFixtures.*

/**
 * er-policy.v4 (ER-P5-08, human decision of 2026-11-06, decisions-06): the same publication-stage
 * target on inferred acceptance, arXiv-based acceptance, and LLM band eligibility. Every identifier
 * here is SYNTHETIC (unregistered DOI prefix 10.9999, invented arXiv IDs); nothing here is gold
 * data.
 */
class PolicyV4Suite extends munit.FunSuite {

  private val doi1 = ExactId(ExactIdScheme.Doi, "10.9999/example.1")
  private val doi2 = ExactId(ExactIdScheme.Doi, "10.9999/example.2")
  private val arxivDoi = ExactId(ExactIdScheme.Doi, "10.48550/arxiv.2101.00001")
  private val arx = ExactId(ExactIdScheme.Arxiv, "2101.00001")
  private val dk1 = ExactId(ExactIdScheme.DblpKey, "journals/example/SynA")
  private val dk2 = ExactId(ExactIdScheme.DblpKey, "conf/example/SynB")

  /** Title, year, and author sequence exact: the deterministic accept rule fires. */
  private val exactBib: Seq[(String, Double)] = Seq(
    "title.exact" -> 1.0,
    "title.sim" -> 1.0,
    "title.token_jaccard" -> 1.0,
    "year.both" -> 1.0,
    "year.abs_diff" -> 0.0,
    "authors.both" -> 1.0,
    "authors.sequence_exact" -> 1.0
  )

  /** A score far above the accept threshold without the deterministic rule. */
  private val strong: Seq[(String, Double)] = Seq(
    "title.exact" -> 1.0,
    "title.sim" -> 1.0,
    "title.token_jaccard" -> 1.0,
    "authors.both" -> 1.0,
    "authors.family_jaccard" -> 1.0,
    "authors.first_family_agree" -> 1.0,
    "authors.sequence_lcs_ratio" -> 0.8
  )

  private val sameVenue: Seq[(String, Double)] =
    Seq("venue.id_both" -> 1.0, "venue.id_agree" -> 1.0)

  private def valid(o: ResolutionOutcome): MatchDecision =
    Resolver.toMatchDecision(o, ctx).fold(e => fail(e.mkString("; ")), identity)

  // ------------------------------------------------------------------ versions

  test("er-policy.v8 (v4 rules kept) is the default; v3 and prompt v1 are retired and refused") {
    assertEquals(policy.policyVersion, "er-policy.v8")
    assertEquals(policy.adjudication.promptVersion, "er-adjudication-prompt.v2")
    val v3 = MatchPolicy.resourceText(MatchPolicy.RetiredV3Resource).fold(e => fail(e), identity)
    assert(MatchPolicy.parse(v3).left.exists(_.exists(_.contains("retired"))))
    assert(MatchPolicy.validate(policy.copy(policyVersion = "er-policy.v3")).nonEmpty)
    val v1Prompt = policy.copy(adjudication =
      policy.adjudication.copy(promptVersion = "er-adjudication-prompt.v1")
    )
    assert(MatchPolicy.validate(v1Prompt).exists(_.contains("retired")))
  }

  // ------------------------------------------------------------------ stage definition

  test("stage evidence and verdicts, as documented") {
    val c = policy.workCorroboration
    def ev(ids: ExactId*) = PublicationStage.evidence(c, ids.toVector)
    assertEquals(ev(arx), StageEvidence.Preprint)
    assertEquals(ev(arxivDoi), StageEvidence.Preprint)
    assertEquals(ev(doi1), StageEvidence.Published)
    assertEquals(ev(arx, doi1), StageEvidence.Mixed)
    assertEquals(ev(), StageEvidence.Unknown)
    assertEquals(ev(dk1), StageEvidence.Unknown, "a record key says nothing about the stage")
    def verdict(kv: Seq[(String, Double)], a: Vector[ExactId], b: Vector[ExactId]) =
      PublicationStage.of(c, SortedMap.from(kv), EndpointIds(a, b)).verdict
    assertEquals(verdict(Seq.empty, Vector(arx), Vector(arxivDoi)), StageVerdict.SamePreprint)
    assertEquals(verdict(Seq.empty, Vector(doi1), Vector(doi2)), StageVerdict.SamePublished)
    assertEquals(verdict(sameVenue, Vector.empty, Vector(doi1)), StageVerdict.SameVenue)
    assertEquals(verdict(sameVenue, Vector.empty, Vector.empty), StageVerdict.SameVenue)
    assertEquals(verdict(sameVenue, Vector(arx), Vector(doi1)), StageVerdict.CrossStage)
    assertEquals(verdict(sameVenue, Vector(arx), Vector.empty), StageVerdict.CrossStage)
    assertEquals(verdict(Seq.empty, Vector.empty, Vector(doi1)), StageVerdict.Uncertain)
    assertEquals(verdict(sameVenue, Vector(arx, doi1), Vector(arx)), StageVerdict.Uncertain)
    assertEquals(verdict(Seq.empty, Vector.empty, Vector.empty), StageVerdict.Uncertain)
    // A venue name match counts at the documented bound, and a missing feature fails closed.
    val byName = Seq("venue.name_both" -> 1.0)
    assertEquals(
      verdict(byName :+ ("venue.name_jaccard" -> 0.8), Vector.empty, Vector.empty),
      StageVerdict.SameVenue
    )
    assertEquals(
      verdict(byName :+ ("venue.name_jaccard" -> Math.nextDown(0.8)), Vector.empty, Vector.empty),
      StageVerdict.Uncertain
    )
  }

  // ------------------------------------------------------------------ deterministic path

  test("deterministic rule: a cross-stage pair is UNRESOLVED with a distinct reason code") {
    val o = decide(syntheticWork(exactBib ++ sameVenue, Vector(arx), Vector(doi1)))
    assertEquals(
      (o.outcome, o.path),
      (MatchOutcome.Unresolved, MatchPath.DeterministicBibliographic)
    )
    assertEquals(o.confidence, 0.0)
    assert(o.rulesFired.contains(Rules.TitleYearAuthorsExact))
    assert(o.rulesFired.contains(Rules.acceptBlockedByStage(MatchPath.DeterministicBibliographic)))
    assert(o.reasonCodes.contains("publication-stage-cross-stage"), o.reasonCodes.toString)
    assert(o.reasonCodes.contains("acceptance-blocked-by-publication-stage"))
    assertEquals(o.conflicts, Vector.empty)
    assert(!o.llmBandEligible)
    assertEquals(valid(o).decision, MatchOutcome.Unresolved)
    // Preprint (no identifier at all on the published side either way) against an unknown stage.
    val unknown = decide(syntheticWork(exactBib ++ sameVenue, Vector(arx), Vector.empty))
    assertEquals(unknown.outcome, MatchOutcome.Unresolved)
    assert(unknown.reasonCodes.contains("publication-stage-cross-stage"))
  }

  test("deterministic rule: an uncertain stage is UNRESOLVED; an established stage accepts") {
    val uncertain = decide(syntheticWork(exactBib, Vector.empty, Vector(doi1)))
    assertEquals(uncertain.outcome, MatchOutcome.Unresolved)
    assert(
      uncertain.reasonCodes.contains("publication-stage-uncertain"),
      uncertain.reasonCodes.toString
    )
    val venue = decide(syntheticWork(exactBib ++ sameVenue, Vector.empty, Vector(doi1)))
    assertEquals(
      (venue.outcome, venue.path, venue.confidence),
      (MatchOutcome.Accepted, MatchPath.DeterministicBibliographic, 0.95)
    )
    assertEquals(venue.stage.map(_.verdict), Some(StageVerdict.SameVenue))
    // Two preprints (an arXiv ID and its arXiv DOI; no identifier agreement) are the same stage.
    val preprints = decide(syntheticWork(exactBib, Vector(arx), Vector(arxivDoi)))
    assertEquals(preprints.outcome, MatchOutcome.Accepted)
    assertEquals(preprints.stage.map(_.verdict), Some(StageVerdict.SamePreprint))
  }

  test("deterministic rule: a record-key block and a stage block are both recorded") {
    val o = decide(syntheticWork(exactBib, Vector(dk1), Vector(dk2)))
    assertEquals(o.outcome, MatchOutcome.Unresolved)
    assert(o.rulesFired.contains(Rules.acceptBlocked(MatchPath.DeterministicBibliographic)))
    assert(o.rulesFired.contains(Rules.acceptBlockedByStage(MatchPath.DeterministicBibliographic)))
    assert(o.reasonCodes.contains("publication-stage-uncertain"))
    assertEquals(o.conflicts.map(_.scheme.tag), Vector("dblp-key"))
  }

  // ------------------------------------------------------------------ probabilistic path

  test("score: a high score never accepts a cross-stage or uncertain-stage pair") {
    val cross = decide(syntheticWork(strong ++ sameVenue, Vector(arx), Vector(doi1)))
    assert(cross.score.probability >= policy.thresholds.accept, cross.score.probability.toString)
    assertEquals((cross.outcome, cross.path), (MatchOutcome.Unresolved, MatchPath.Probabilistic))
    assert(cross.rulesFired.contains(Rules.acceptBlockedByStage(MatchPath.Probabilistic)))
    assert(cross.reasonCodes.contains("publication-stage-cross-stage"))
    val uncertain = decide(syntheticWork(strong))
    assertEquals(uncertain.outcome, MatchOutcome.Unresolved)
    assert(uncertain.reasonCodes.contains("publication-stage-uncertain"))
    // The same score at an established stage accepts on the probabilistic path.
    val ok = decide(syntheticWork(strong ++ sameVenue))
    assertEquals((ok.outcome, ok.path), (MatchOutcome.Accepted, MatchPath.Probabilistic))
    // A low score still rejects: the stage check never turns REJECTED into anything else.
    val low = decide(syntheticWork(Seq.empty, Vector(arx), Vector(doi1)))
    assertEquals(low.outcome, MatchOutcome.Rejected)
  }

  // ------------------------------------------------------------------ arXiv agreement

  test("arXiv agreement decides only when both sides are pure preprints") {
    val o = decide(syntheticWork(Seq.empty, Vector(arx), Vector(arx)))
    assertEquals(
      (o.outcome, o.path, o.confidence),
      (MatchOutcome.Accepted, MatchPath.ExactId, 0.99)
    )
    assert(o.rulesFired.contains(Rules.exactAgree("arxiv")))
    assert(!o.reasonCodes.contains("arxiv-agreement-cross-stage"))
  }

  test("a shared arXiv ID never by itself merges a preprint with its published version") {
    // Side B is the published version listing the preprint's arXiv ID (mixed stage).
    val profiles = Vector(Seq.empty, strong, strong ++ sameVenue, exactBib ++ sameVenue)
    profiles.foreach { kv =>
      val o = decide(syntheticWork(kv, Vector(arx), Vector(arx, doi1)))
      val clue = kv.toString
      assertEquals((o.outcome, o.path), (MatchOutcome.Unresolved, MatchPath.ExactId), clue)
      assertEquals(o.confidence, 0.0, clue)
      assert(o.rulesFired.contains(Rules.ArxivCrossStage), clue)
      assert(!o.rulesFired.contains(Rules.exactAgree("arxiv")), clue)
      assert(o.reasonCodes.contains("arxiv-agreement-cross-stage"), clue)
      assert(!o.llmBandEligible, clue)
      assertEquals(valid(o).decision, MatchOutcome.Unresolved, clue)
    }
    // The arXiv agreement does not override a record-key block either.
    val keyed = decide(syntheticWork(strong, Vector(dk1, arx), Vector(dk2, arx, doi1)))
    assertEquals(keyed.outcome, MatchOutcome.Unresolved)
    assertEquals(keyed.conflicts.map(_.scheme.tag), Vector("dblp-key"))
    assert(!keyed.reasonCodes.exists(_.contains("overridden-by.arxiv")), keyed.reasonCodes.toString)
  }

  test("a DOI disagreement plus a shared arXiv ID stays CONFLICT; a DOI agreement still accepts") {
    val conflict = decide(syntheticWork(strong, Vector(arx, doi1), Vector(arx, doi2)))
    assertEquals((conflict.outcome, conflict.path), (MatchOutcome.Conflict, MatchPath.ExactId))
    assert(conflict.conflicts.exists(c => c.scheme == ExactIdScheme.Doi))
    assert(
      conflict.conflicts.exists(c =>
        c.scheme == ExactIdScheme.Arxiv && c.kind == IdentifierConflictKind.ContradictedAgreement
      ),
      conflict.conflicts.toString
    )
    // Both sides list the same DOI and the same arXiv ID: the DOI decides, the arXiv ID does not.
    val doi = decide(syntheticWork(Seq.empty, Vector(arx, doi1), Vector(arx, doi1)))
    assertEquals((doi.outcome, doi.path), (MatchOutcome.Accepted, MatchPath.ExactId))
    assert(doi.rulesFired.contains(Rules.exactAgree("doi")))
    assert(!doi.rulesFired.contains(Rules.exactAgree("arxiv")))
    assert(doi.reasonCodes.contains("arxiv-agreement-cross-stage"))
    assertEquals(doi.conflicts, Vector.empty)
  }

  test("researcher pairs carry no stage check and are unchanged") {
    val o = decide(
      syntheticResearcher(nameOnly ++ Seq("coauthor.jaccard" -> 0.5, "coauthor.shared" -> 3.0))
    )
    assertEquals(o.stage, None)
    assertEquals((o.outcome, o.path), (MatchOutcome.Accepted, MatchPath.Probabilistic))
  }

  test("every v4 outcome above maps to a valid core decision; ACCEPTED never carries conflicts") {
    Vector(
      syntheticWork(exactBib ++ sameVenue, Vector(arx), Vector(doi1)),
      syntheticWork(exactBib, Vector.empty, Vector(doi1)),
      syntheticWork(exactBib, Vector(dk1), Vector(dk2)),
      syntheticWork(strong ++ sameVenue, Vector(arx), Vector(doi1)),
      syntheticWork(Seq.empty, Vector(arx), Vector(arx)),
      syntheticWork(strong, Vector(arx), Vector(arx, doi1)),
      syntheticWork(strong, Vector(dk1, arx), Vector(dk2, arx, doi1)),
      syntheticWork(strong, Vector(arx, doi1), Vector(arx, doi2)),
      syntheticWork(Seq.empty, Vector(arx, doi1), Vector(arx, doi1))
    ).map(decide(_)).foreach { o =>
      assert(!(o.outcome == MatchOutcome.Accepted && o.conflicts.nonEmpty), o.rulesFired.toString)
      assertEquals(MatchDecision.contract.validate(valid(o)), Vector.empty, o.rulesFired.toString)
    }
  }
}
