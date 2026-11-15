package scholarchain.entityresolution.policy

import scholarchain.core.*
import scholarchain.core.config.EntityResolutionConfig
import scholarchain.entityresolution.Samples
import scholarchain.entityresolution.Samples.{em, ep}

import PolicyFixtures.*

/** Policy behaviour on SYNTHETIC fixtures and sample-mirroring records (not gold data). */
class ResolverSuite extends munit.FunSuite {

  private val orcid = ExactId(ExactIdScheme.Orcid, "0000-0002-2814-962X")

  test("exact ORCID agreement is ACCEPTED on the exact-id path") {
    val c = researcherCandidate(em(Samples.cacmDblp, 0), ep(Samples.popperOpenAlex))
    assertEquals(c.exactIdAgreements, Vector(orcid))
    val o = decide(c)
    assertEquals(o.outcome, MatchOutcome.Accepted)
    assertEquals(o.path, MatchPath.ExactId)
    assertEquals(o.rulesFired, Vector("exact-id.agree.orcid"))
    assertEquals(o.confidence, 0.99)
    assertEquals(o.conflicts, Vector.empty)
  }

  test("an exact agreement contradicted by a same-work or homonym rule is CONFLICT") {
    val sameWork =
      decide(syntheticResearcher(Seq("context.same_work" -> 1.0), Vector(orcid), Vector(orcid)))
    assertEquals(sameWork.outcome, MatchOutcome.Conflict)
    assert(sameWork.rulesFired.contains(Rules.ExactVsSameWork))
    assertEquals(
      sameWork.conflicts,
      Vector(
        IdentifierConflict.contradictedAgreement(
          ExactIdScheme.Orcid,
          Vector(orcid.value),
          Rules.ExactVsSameWork
        )
      )
    )
    val homonym = decide(
      syntheticResearcher(Seq("name.homonym_suffix_both" -> 1.0), Vector(orcid), Vector(orcid))
    )
    assertEquals(homonym.outcome, MatchOutcome.Conflict)
    assert(homonym.rulesFired.contains(Rules.ExactVsHomonym))
  }

  test("the same paper in two sources at the same position: deterministic ACCEPTED") {
    val o = decide(researcherCandidate(em(Samples.spannerDblp, 4), em(Samples.spannerCrossref, 4)))
    assertEquals(o.outcome, MatchOutcome.Accepted)
    assertEquals(o.path, MatchPath.DeterministicBibliographic)
    assertEquals(o.rulesFired, Vector(Rules.SamePaperSamePosition))
  }

  test("two positions on one work and differing DBLP homonym numbers: deterministic REJECTED") {
    val strong = nameOnly ++ Seq("coauthor.jaccard" -> 1.0, "coauthor.shared" -> 4.0)
    val sameWork = decide(syntheticResearcher(strong :+ ("context.same_work" -> 1.0)))
    assertEquals(sameWork.outcome, MatchOutcome.Rejected)
    assertEquals(sameWork.rulesFired, Vector(Rules.SameWork))
    val homonym = decide(syntheticResearcher(strong :+ ("name.homonym_suffix_both" -> 1.0)))
    assertEquals(homonym.outcome, MatchOutcome.Rejected)
    assertEquals(homonym.path, MatchPath.DeterministicBibliographic)
    val sameNumber = decide(
      syntheticResearcher(
        strong ++ Seq("name.homonym_suffix_both" -> 1.0, "name.homonym_suffix_agree" -> 1.0)
      )
    )
    assertEquals(sameNumber.outcome, MatchOutcome.Accepted)
  }

  test("work: DOI agreement is exact; title, year, and author sequence equality is deterministic") {
    val doi = decide(workCandidate(Samples.spannerDblp, Samples.spannerCrossref))
    assertEquals((doi.outcome, doi.path), (MatchOutcome.Accepted, MatchPath.ExactId))
    assertEquals(doi.rulesFired, Vector("exact-id.agree.doi"))
    val exact = Seq(
      "title.exact" -> 1.0,
      "year.both" -> 1.0,
      "authors.both" -> 1.0,
      "authors.sequence_exact" -> 1.0
    )
    // er-policy.v4: the deterministic rule accepts a work pair only at an established stage; here
    // neither side carries a preprint marker and the venue agrees (same-stage-venue).
    val bib = decide(syntheticWork(exact :+ ("venue.id_agree" -> 1.0)))
    assertEquals(
      (bib.outcome, bib.path),
      (MatchOutcome.Accepted, MatchPath.DeterministicBibliographic)
    )
    assertEquals(bib.stage.map(_.verdict), Some(StageVerdict.SameVenue))
    // Without the venue the stage is uncertain: UNRESOLVED, never accepted.
    val noVenue = decide(syntheticWork(exact))
    assertEquals(
      (noVenue.outcome, noVenue.path),
      (MatchOutcome.Unresolved, MatchPath.DeterministicBibliographic)
    )
    assert(
      noVenue.reasonCodes.contains("publication-stage-uncertain"),
      noVenue.reasonCodes.toString
    )
  }

  test("probabilistic path: accept, reject, unresolved in band, unresolved outside band") {
    val accept = decide(
      syntheticResearcher(nameOnly ++ Seq("coauthor.jaccard" -> 0.5, "coauthor.shared" -> 3.0))
    )
    assertEquals((accept.outcome, accept.path), (MatchOutcome.Accepted, MatchPath.Probabilistic))
    assertEquals(accept.confidence, accept.score.probability)
    val reject = decide(syntheticResearcher(Seq("name.family_key_agree" -> 1.0)))
    assertEquals(reject.outcome, MatchOutcome.Rejected)
    assertEqualsDouble(reject.confidence, 1.0 - reject.score.probability, 1e-12)
    val band = decide(syntheticResearcher(nameOnly))
    assertEquals(band.outcome, MatchOutcome.Unresolved)
    assert(band.llmBandEligible && band.rulesFired.contains(Rules.ScoreLlmBand))
    val above = decide(syntheticResearcher(nameOnly :+ ("work.title_jaccard" -> 0.6)))
    assert(above.score.probability > policy.llmBand.upper)
    assertEquals(above.outcome, MatchOutcome.Unresolved)
    assert(!above.llmBandEligible)
    val below = decide(
      syntheticResearcher(
        Seq(
          "name.family_key_agree" -> 1.0,
          "name.first_given_agree" -> 1.0,
          "name.initials_compatible" -> 1.0
        )
      )
    )
    assert(below.score.probability > policy.thresholds.reject)
    assert(below.score.probability < policy.llmBand.lower)
    assertEquals(below.outcome, MatchOutcome.Unresolved)
    assert(!below.llmBandEligible)
  }

  test("a name match alone is never ACCEPTED (it lands in the LLM band)") {
    val o = decide(syntheticResearcher(nameOnly))
    assertNotEquals(o.outcome, MatchOutcome.Accepted)
    assert(policy.llmBand.contains(o.score.probability))
  }

  private def config(accept: Double, reject: Double) =
    EntityResolutionConfig("er-policy.v8", accept, reject, 0.4, 0.7, "er-adjudication-prompt.v2")

  test("thresholds come from configuration: the same score flips with configured thresholds") {
    val c = syntheticResearcher(nameOnly :+ ("work.title_jaccard" -> 0.6))
    val p = decide(c).score.probability
    assertEquals(decide(c).outcome, MatchOutcome.Unresolved)
    val lower = MatchPolicy
      .withConfig(policy, config(p - 0.01, 0.2))
      .fold(e => fail(e.mkString), identity)
    assertEquals(decide(c, lower).outcome, MatchOutcome.Accepted)
  }

  test("source reliability is a policy feature computed from the candidate's datasets") {
    val dblp = decide(syntheticResearcher(nameOnly))
    val oa = decide(syntheticResearcher(nameOnly, sources = (SourceType.OpenAlex, SourceType.Dblp)))
    assert(
      oa.features(Scoring.SourceReliabilityFeature) < dblp.features(
        Scoring.SourceReliabilityFeature
      )
    )
    assert(oa.score.probability < dblp.score.probability)
  }

  test("candidates of another feature or blocking version are refused, never mixed") {
    val v1 = syntheticResearcher(nameOnly, featureVersion = "er-features.v1")
    assert(
      Resolver
        .decide(policy, v1, EndpointIds.Empty)
        .left
        .exists(_.exists(_.contains("never mixed")))
    )
    val keys = syntheticResearcher(nameOnly).copy(blockingVersion = "er-blocking.v1")
    assert(Resolver.decide(policy, keys, EndpointIds.Empty).isLeft)
  }

  test("endpoint identifiers that do not reproduce the candidate's id features are refused") {
    val other = ExactId(ExactIdScheme.Orcid, "0000-0000-0000-0001")
    val c = syntheticResearcher(nameOnly, Vector(orcid), Vector(orcid))
    // The candidate says the ORCIDs agree; endpoint records that disagree are not its records.
    val wrong = EndpointIds(Vector(orcid), Vector(other))
    assert(Resolver.decide(policy, c, wrong).left.exists(_.exists(_.contains("exactIdAgreements"))))
    val flagOnly = syntheticResearcher(nameOnly :+ ("id.orcid.conflict" -> 1.0))
    assertEquals(flagOnly.features("id.orcid.conflict"), 0.0, "fixture derives id flags from ids")
    val forged = flagOnly.copy(features = flagOnly.features + ("id.orcid.conflict" -> 1.0))
    assert(
      Resolver
        .decide(policy, forged, EndpointIds.Empty)
        .left
        .exists(_.exists(_.contains("id.orcid.conflict")))
    )
  }

  test("decisions are deterministic and do not modify the candidate") {
    val c = researcherCandidate(em(Samples.spannerDblp, 1), em(Samples.bigtableDblp, 1))
    val before = c.copy()
    assertEquals(decide(c), decide(c))
    assertEquals(c, before)
  }

  // ------------------------------------------------------------------ core contract (v2)

  test("toMatchDecision: a valid v2 record with path, score, versions, and provenance") {
    val o = decide(researcherCandidate(em(Samples.spannerDblp, 4), em(Samples.spannerCrossref, 4)))
    val d = Resolver.toMatchDecision(o, ctx).fold(e => fail(e.mkString), identity)
    assertEquals(MatchDecision.contract.validate(d), Vector.empty)
    assertEquals(d.schemaVersion, SchemaVersions.MatchDecision)
    assertEquals(d.decision, MatchOutcome.Accepted)
    assertEquals(d.matchPath, MatchDecisionPath.DeterministicBibliographic)
    assertEquals(d.candidateId, o.candidateId)
    assertEquals(d.score, Some(o.score.probability))
    assertEquals((d.blockingVersion, d.featureVersion), ("er-blocking.v2", "er-features.v2"))
    assertEquals(d.conflicts, Vector.empty)
    assertEquals(d.adjudication, None)
    assertEquals(d.provenance.sourceRecordId, o.candidateId)
    assertEquals(d.provenance.policyVersion, Some("er-policy.v8"))
    assertEquals(d.provenance.runId, decisionRunId)
    assertEquals(d.provenance.modelVersion, None)
    assertEquals(MatchPath.ofRules(d.rulesFired), Some(MatchPath.DeterministicBibliographic))
    assert(Json.encode(d).isRight)
  }

  test("toMatchDecision: CONFLICT round-trips with the disagreeing identifier values") {
    val f = new MatchFeaturesFixtures
    val o = decide(researcherCandidate(f.adaA, f.adaB))
    assertEquals(o.outcome, MatchOutcome.Conflict)
    val d = Resolver.toMatchDecision(o, ctx).fold(e => fail(e.mkString), identity)
    assertEquals(d.decision, MatchOutcome.Conflict)
    assertEquals(d.matchPath, MatchDecisionPath.ExactId)
    val c = d.conflicts.head
    assertEquals((c.kind, c.scheme), (IdentifierConflictKind.Disagreement, ExactIdScheme.Orcid))
    assertEquals(
      (c.valuesA ++ c.valuesB).toSet,
      Set("0000-0000-0000-0001", "0000-0000-0000-001X")
    )
    val json = Json.encode(d).fold(e => fail(e.toString), identity)
    val back = Json.decode[MatchDecision](json).fold(e => fail(e.toString), identity)
    assertEquals(back, d)
    assertEquals(MatchDecision.contract.validate(back), Vector.empty)
  }

  test("every match path tag equals the core MatchDecisionPath tag") {
    assertEquals(MatchPath.values.map(_.tag).toVector, MatchDecisionPath.values.map(_.tag).toVector)
    MatchPath.values.foreach(p => assertEquals(p.toCore.tag, p.tag))
  }
}

/** SYNTHETIC: two different people named Ada Example with different (test) ORCID iDs. */
final class MatchFeaturesFixtures {
  private val a = Samples.buildWork(
    SourceType.Dblp,
    "work:dblp:journals/example/AdaA",
    "journals/example/AdaA",
    "First Example",
    Some(2020),
    None,
    None,
    None,
    Vector(Samples.A("Ada Example", Some("0000-0000-0000-0001")))
  )
  private val b = Samples.buildWork(
    SourceType.Dblp,
    "work:dblp:journals/example/AdaB",
    "journals/example/AdaB",
    "Second Example",
    Some(2022),
    None,
    None,
    None,
    Vector(Samples.A("Ada Example", Some("0000-0000-0000-001X")))
  )
  val adaA = em(a, 0)
  val adaB = em(b, 0)
}
