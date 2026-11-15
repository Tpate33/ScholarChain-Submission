package scholarchain.entityresolution.policy

import scholarchain.core.*
import scholarchain.entityresolution.Samples

import PolicyFixtures.*

/**
 * Human decision 1 (conflict policy) and decision 2 (per-scheme identifier rules), Phase 5, on
 * SYNTHETIC candidates and sample-mirroring records. Not gold data.
 */
class ConflictPolicySuite extends munit.FunSuite {

  // SYNTHETIC, checksum-valid test ORCID iDs and invented identifiers.
  private val orcid1 = ExactId(ExactIdScheme.Orcid, "0000-0000-0000-0001")
  private val orcid2 = ExactId(ExactIdScheme.Orcid, "0000-0000-0000-001X")
  private val pid = ExactId(ExactIdScheme.DblpPid, "69/6042-1")
  private val pid2 = ExactId(ExactIdScheme.DblpPid, "69/6042-2")
  private val oaA1 = ExactId(ExactIdScheme.OpenAlexAuthor, "A5000000001")
  private val oaA2 = ExactId(ExactIdScheme.OpenAlexAuthor, "A5000000002")
  private val s2a1 = ExactId(ExactIdScheme.SemanticScholarAuthor, "1741101")
  private val s2a2 = ExactId(ExactIdScheme.SemanticScholarAuthor, "1741102")
  private val doi1 = ExactId(ExactIdScheme.Doi, "10.9999/example.1")
  private val doi2 = ExactId(ExactIdScheme.Doi, "10.9999/example.2")
  private val w1 = ExactId(ExactIdScheme.OpenAlexWork, "W1000000001")
  private val w2 = ExactId(ExactIdScheme.OpenAlexWork, "W1000000002")
  private val corpus1 = ExactId(ExactIdScheme.SemanticScholarCorpus, "215416146")
  private val corpus2 = ExactId(ExactIdScheme.SemanticScholarCorpus, "215416147")
  private val paper1 =
    ExactId(ExactIdScheme.SemanticScholarPaper, "0123456789abcdef0123456789abcdef01234567")
  private val repo1 = ExactId(ExactIdScheme.RepositoryUrl, "https://gitlab.com/group/sub/repoA")
  private val repo2 = ExactId(ExactIdScheme.RepositoryUrl, "https://gitlab.com/group/sub/repoB")

  /** Strong non-identifier evidence: the score alone would accept. */
  private val strongName: Seq[(String, Double)] =
    nameOnly ++ Seq("coauthor.jaccard" -> 1.0, "coauthor.shared" -> 4.0)

  /** A different person's name: family keys differ, initials incompatible. */
  private val otherName: Seq[(String, Double)] = Seq(
    "name.given_both" -> 1.0,
    "name.family_sim" -> 0.2,
    "name.given_sim" -> 0.1
  )

  /** Near-identical work titles. */
  private val sameTitle: Seq[(String, Double)] =
    Seq("title.exact" -> 1.0, "title.sim" -> 1.0, "title.token_jaccard" -> 1.0)

  /** Unrelated work titles. */
  private val otherTitle: Seq[(String, Double)] =
    Seq("title.sim" -> 0.3, "title.token_jaccard" -> 0.1)

  private def conflictSchemes(o: ResolutionOutcome): Set[String] =
    o.conflicts.filter(_.kind == IdentifierConflictKind.Disagreement).map(_.scheme.tag).toSet

  private def valid(o: ResolutionOutcome): MatchDecision =
    Resolver.toMatchDecision(o, ctx).fold(e => fail(e.mkString("; ")), identity)

  // ------------------------------------------------------------------ researcher

  test("researcher: plausible + authoritative disagreement (ORCID) is CONFLICT") {
    val o = decide(syntheticResearcher(strongName, Vector(orcid1), Vector(orcid2)))
    assert(o.score.probability > policy.thresholds.accept, "the score alone would accept")
    assertEquals(o.outcome, MatchOutcome.Conflict)
    assertEquals(o.path, MatchPath.ExactId)
    assertEquals(o.plausibility.map(_.plausible), Some(true))
    assert(o.rulesFired.contains(Rules.Plausible))
    assert(o.rulesFired.contains(Rules.exactConflict("orcid")))
    assert(o.reasonCodes.contains("authoritative-id-disagreement.orcid"))
    assertEquals(
      o.conflicts,
      Vector(
        IdentifierConflict(
          IdentifierConflictKind.Disagreement,
          ExactIdScheme.Orcid,
          Vector(orcid1.value),
          Vector(orcid2.value),
          None
        )
      )
    )
    assertEquals(valid(o).decision, MatchOutcome.Conflict)
  }

  test(
    "researcher: plausible + ORCID disagreement + DBLP pid agreement is CONFLICT, never ACCEPTED"
  ) {
    val o = decide(syntheticResearcher(strongName, Vector(orcid1, pid), Vector(orcid2, pid)))
    assertEquals(o.exactIdAgreements, Vector(pid))
    assertEquals(o.outcome, MatchOutcome.Conflict)
    assert(o.rulesFired.contains(Rules.exactAgree("dblp-pid")))
    assert(o.rulesFired.contains(Rules.IdentifierContradiction))
    assertEquals(conflictSchemes(o), Set("orcid"))
    assert(
      o.conflicts.exists(c =>
        c.kind == IdentifierConflictKind.ContradictedAgreement &&
          c.scheme == ExactIdScheme.DblpPid && c.valuesA == Vector(pid.value) &&
          c.contradictingRule.contains(Rules.IdentifierContradiction)
      )
    )
    assertEquals(valid(o).decision, MatchOutcome.Conflict)
  }

  test(
    "researcher: an authoritative disagreement blocks every path, deterministic rules included"
  ) {
    val samePaper = Seq(
      "work.doi_agree" -> 1.0,
      "work.position_agree" -> 1.0,
      "work.team_size_agree" -> 1.0
    )
    val o = decide(syntheticResearcher(strongName ++ samePaper, Vector(pid), Vector(pid2)))
    assertEquals(o.outcome, MatchOutcome.Conflict)
    assertEquals(conflictSchemes(o), Set("dblp-pid"))
  }

  test("researcher: implausible + authoritative disagreement is REJECTED by a distinct rule") {
    val o = decide(syntheticResearcher(otherName, Vector(orcid1), Vector(orcid2)))
    assertEquals(o.plausibility.map(_.plausible), Some(false))
    assertEquals(o.outcome, MatchOutcome.Rejected)
    assertEquals(o.path, MatchPath.ExactId)
    assertEquals(o.confidence, policy.implausibleDisagreementConfidence)
    assertEquals(
      o.rulesFired,
      Vector(Rules.Implausible, Rules.disagreeImplausible("orcid"))
    )
    assertEquals(
      Rules.disagreeImplausible("orcid"),
      "exact-id.reject.disagreement-implausible.orcid"
    )
    assertEquals(conflictSchemes(o), Set("orcid"), "the disagreement is recorded as evidence")
    val d = valid(o)
    assertEquals(d.decision, MatchOutcome.Rejected)
    assertEquals(d.conflicts.map(_.kind).distinct, Vector(IdentifierConflictKind.Disagreement))
  }

  test("researcher: implausible + disagreement + another agreeing identifier is CONFLICT") {
    val o = decide(syntheticResearcher(otherName, Vector(orcid1, pid), Vector(orcid2, pid)))
    assertEquals(o.plausibility.map(_.plausible), Some(false))
    assertEquals(o.outcome, MatchOutcome.Conflict)
    assert(o.rulesFired.contains(Rules.IdentifierContradiction))
  }

  test("researcher: two different people on one paper with different ORCIDs are REJECTED") {
    val o = decide(
      syntheticResearcher(
        strongName :+ ("context.same_work" -> 1.0),
        Vector(orcid1),
        Vector(orcid2)
      )
    )
    assertEquals(o.outcome, MatchOutcome.Rejected)
    assert(o.plausibility.exists(_.clauses.contains("not-same-work=false")))
  }

  private def researcherWith(kv: Seq[(String, Double)]): ResolutionOutcome =
    decide(syntheticResearcher(kv, Vector(orcid1), Vector(orcid2)))

  test("researcher plausibility boundary: family similarity, initials, missing given names") {
    val rp = policy.researcherPlausibility
    assertEquals(rp.familySimMin, 0.85)
    val initials = Seq("name.given_both" -> 1.0, "name.initials_compatible" -> 1.0)
    // Family keys differ; similarity exactly at the bound is plausible, just below it is not.
    assertEquals(
      researcherWith(initials :+ ("name.family_sim" -> 0.85)).outcome,
      MatchOutcome.Conflict
    )
    assertEquals(
      researcherWith(initials :+ ("name.family_sim" -> 0.8499)).outcome,
      MatchOutcome.Rejected
    )
    // Similarity 1 without key agreement means two empty family keys: not plausible.
    assertEquals(
      researcherWith(initials :+ ("name.family_sim" -> 1.0)).outcome,
      MatchOutcome.Rejected
    )
    val family = Seq("name.family_key_agree" -> 1.0, "name.family_sim" -> 1.0)
    // Both given names present but incompatible initials: not plausible.
    assertEquals(
      researcherWith(family :+ ("name.given_both" -> 1.0)).outcome,
      MatchOutcome.Rejected
    )
    // A missing given name contradicts nothing: plausible.
    assertEquals(researcherWith(family).outcome, MatchOutcome.Conflict)
    val strict = policy.copy(researcherPlausibility = rp.copy(missingGivenNamesCompatible = false))
    assertEquals(
      decide(syntheticResearcher(family, Vector(orcid1), Vector(orcid2)), strict).outcome,
      MatchOutcome.Rejected
    )
    // Different DBLP homonym numbers: not plausible.
    assertEquals(
      researcherWith(family ++ initials :+ ("name.homonym_suffix_both" -> 1.0)).outcome,
      MatchOutcome.Rejected
    )
  }

  test("plausibility reads no identifier feature: id flags never change the test") {
    val f = syntheticResearcher(otherName, Vector(orcid1), Vector(orcid2)).features
    val withIds = f ++ Seq("id.orcid.agree" -> 1.0, "id.dblp-pid.agree" -> 1.0)
    assertEquals(
      Plausibility.researcher(policy.researcherPlausibility, withIds),
      Plausibility.researcher(policy.researcherPlausibility, f)
    )
  }

  // ------------------------------------------------------------------ work

  test("work: plausible + DOI disagreement is CONFLICT, and an agreeing W ID cannot accept it") {
    val o = decide(syntheticWork(sameTitle, Vector(doi1), Vector(doi2)))
    assertEquals(o.outcome, MatchOutcome.Conflict)
    assertEquals(conflictSchemes(o), Set("doi"))
    val withW = decide(syntheticWork(sameTitle, Vector(doi1, w1), Vector(doi2, w1)))
    assertEquals(withW.outcome, MatchOutcome.Conflict)
    assert(withW.rulesFired.contains(Rules.IdentifierContradiction))
    assertEquals(valid(withW).decision, MatchOutcome.Conflict)
  }

  test("work: implausible + DOI disagreement (two different CACM papers) is REJECTED") {
    val o = decide(workCandidate(Samples.cacmDblp, Samples.varajaoDblp))
    assertEquals(o.plausibility.map(_.plausible), Some(false))
    assertEquals(o.outcome, MatchOutcome.Rejected)
    assert(o.rulesFired.contains(Rules.disagreeImplausible("doi")))
    assertEquals(valid(o).decision, MatchOutcome.Rejected)
    val synthetic = decide(syntheticWork(otherTitle, Vector(doi1), Vector(doi2)))
    assertEquals(synthetic.outcome, MatchOutcome.Rejected)
  }

  private def workWith(kv: Seq[(String, Double)]): MatchOutcome =
    decide(syntheticWork(kv, Vector(doi1), Vector(doi2))).outcome

  test("work plausibility boundary: title similarity and the title-and-authors clause") {
    val wp = policy.workPlausibility
    assertEquals(
      (wp.titleSimMin, wp.titleJaccardWithAuthorsMin, wp.authorSequenceLcsMin),
      (0.85, 0.5, 0.75)
    )
    assertEquals(workWith(Seq("title.sim" -> 0.85)), MatchOutcome.Conflict)
    assertEquals(workWith(Seq("title.sim" -> 0.8499)), MatchOutcome.Rejected)
    assertEquals(workWith(Seq("title.sim" -> 1.0)), MatchOutcome.Rejected, "two empty titles")
    val ta = Seq(
      "authors.both" -> 1.0,
      "authors.sequence_lcs_ratio" -> 0.75,
      "title.token_jaccard" -> 0.5,
      "title.sim" -> 0.6
    )
    assertEquals(workWith(ta), MatchOutcome.Conflict)
    assertEquals(workWith(ta :+ ("authors.sequence_lcs_ratio" -> 0.7499)), MatchOutcome.Rejected)
    assertEquals(workWith(ta :+ ("title.token_jaccard" -> 0.4999)), MatchOutcome.Rejected)
    // The same author team alone is not a plausible identity: one team writes many papers.
    assertEquals(
      workWith(Seq("authors.both" -> 1.0, "authors.sequence_lcs_ratio" -> 1.0)),
      MatchOutcome.Rejected
    )
  }

  // ------------------------------------------------------------------ OpenAlex A versus W

  test("OpenAlex author ID (A): agreement is an exact-id ACCEPT at the A-ID confidence") {
    val o = decide(syntheticResearcher(nameOnly, Vector(oaA1), Vector(oaA1)))
    assertEquals((o.outcome, o.path), (MatchOutcome.Accepted, MatchPath.ExactId))
    assertEquals(o.rulesFired, Vector(Rules.exactAgree("openalex-author")))
    assertEquals(o.confidence, 0.9)
    assert(o.confidence < 0.99, "weaker than ORCID or DBLP pid")
  }

  test(
    "OpenAlex author ID (A): a disagreement on a plausible pair blocks acceptance, never CONFLICT"
  ) {
    val o = decide(syntheticResearcher(strongName, Vector(oaA1), Vector(oaA2)))
    assert(o.score.probability > policy.thresholds.accept, "the score alone would accept")
    assertEquals(o.outcome, MatchOutcome.Unresolved)
    assertNotEquals(o.outcome, MatchOutcome.Conflict)
    assert(o.rulesFired.contains(Rules.acceptBlocked(MatchPath.Probabilistic)))
    assert(o.rulesFired.contains(Rules.algorithmicDisagreement("openalex-author")))
    assertEquals(conflictSchemes(o), Set("openalex-author"))
    assert(!o.llmBandEligible)
    assertEquals(valid(o).decision, MatchOutcome.Unresolved)
  }

  test("OpenAlex author ID (A): a band pair with a split A ID is never sent to the LLM") {
    // The A-ID conflict weight (-2.5) is offset by some coauthor overlap to land in the band.
    val kv = nameOnly ++ Seq("coauthor.jaccard" -> 0.5, "coauthor.shared" -> 1.0)
    val o = decide(syntheticResearcher(kv, Vector(oaA1), Vector(oaA2)))
    assert(policy.llmBand.contains(o.score.probability), o.score.probability.toString)
    assertEquals(o.outcome, MatchOutcome.Unresolved)
    assert(!o.llmBandEligible)
    assert(!o.rulesFired.contains(Rules.ScoreLlmBand))
    assert(o.reasonCodes.contains(Rules.Reason.LlmExcluded))
  }

  test(
    "OpenAlex author ID (A): an ORCID agreement overrides a split A ID; implausible is REJECTED"
  ) {
    val o = decide(syntheticResearcher(nameOnly, Vector(orcid1, oaA1), Vector(orcid1, oaA2)))
    assertEquals(o.outcome, MatchOutcome.Accepted)
    assertEquals(o.conflicts, Vector.empty)
    assert(
      o.reasonCodes.contains(
        "block-inferred-acceptance-if-plausible.openalex-author.overridden-by.orcid"
      )
    )
    assertEquals(valid(o).decision, MatchOutcome.Accepted)
    val implausible = decide(syntheticResearcher(otherName, Vector(oaA1), Vector(oaA2)))
    assertEquals(implausible.outcome, MatchOutcome.Rejected)
    assert(implausible.rulesFired.contains(Rules.disagreeImplausible("openalex-author")))
  }

  test(
    "OpenAlex work ID (W): agreement alone is UNRESOLVED (uncorroborated); disagreement is evidence"
  ) {
    val agree = decide(syntheticWork(otherTitle, Vector(w1), Vector(w1)))
    assertEquals((agree.outcome, agree.path), (MatchOutcome.Unresolved, MatchPath.ExactId))
    assert(agree.reasonCodes.contains("cluster-id-agreement-uncorroborated.openalex-work"))
    val disagree = decide(syntheticWork(sameTitle, Vector(w1), Vector(w2)))
    assertNotEquals(disagree.outcome, MatchOutcome.Conflict)
    assertEquals(conflictSchemes(disagree), Set.empty[String])
    assert(disagree.reasonCodes.contains("evidence-only-id-disagreement.openalex-work"))
    val implausible = decide(syntheticWork(otherTitle, Vector(w1), Vector(w2)))
    assertEquals(implausible.path, MatchPath.Probabilistic, "the score decides, not the W IDs")
  }

  test("OpenAlex A and W rules differ, and each is explicit in the policy") {
    val a = policy.researcher.rule(ExactIdScheme.OpenAlexAuthor).get
    val w = policy.work.rule(ExactIdScheme.OpenAlexWork).get
    assertEquals(
      (a.onAgreement, a.onDisagreement),
      ("accept", "block-inferred-acceptance-if-plausible")
    )
    assertEquals((w.onAgreement, w.onDisagreement), ("accept-if-corroborated", "evidence-only"))
    assert(a.scope.contains("author cluster") && a.reliability.contains("splitting"))
    assert(w.scope.contains("work record"))
  }

  // ------------------------------------------------------------------ Semantic Scholar, repository

  test("Semantic Scholar author: agreement ACCEPTS, disagreement blocks acceptance (inert today)") {
    val agree = decide(syntheticResearcher(nameOnly, Vector(s2a1), Vector(s2a1)))
    assertEquals(agree.outcome, MatchOutcome.Accepted)
    assertEquals(agree.rulesFired, Vector(Rules.exactAgree("s2-author")))
    val split = decide(syntheticResearcher(strongName, Vector(s2a1), Vector(s2a2)))
    assertEquals(split.outcome, MatchOutcome.Unresolved)
    assertEquals(conflictSchemes(split), Set("s2-author"))
  }

  test(
    "Semantic Scholar paper: corpus and paper IDs need corroboration; disagreement is evidence only"
  ) {
    assertEquals(
      decide(syntheticWork(otherTitle, Vector(corpus1), Vector(corpus1))).outcome,
      MatchOutcome.Unresolved
    )
    assertEquals(
      decide(syntheticWork(otherTitle, Vector(paper1), Vector(paper1))).outcome,
      MatchOutcome.Unresolved
    )
    val d = decide(syntheticWork(sameTitle, Vector(corpus1), Vector(corpus2)))
    assertNotEquals(d.outcome, MatchOutcome.Conflict)
    assertEquals(conflictSchemes(d), Set.empty[String])
    assert(d.reasonCodes.contains("evidence-only-id-disagreement.s2-corpus"))
    // A corpus ID and a paper ID are different schemes: never compared with each other.
    val mixed = decide(syntheticWork(sameTitle, Vector(corpus1), Vector(paper1)))
    assertEquals(mixed.conflicts, Vector.empty)
    assertNotEquals(mixed.outcome, MatchOutcome.Conflict)
  }

  test(
    "repository URL: evidence only; an agreement never accepts, a disagreement never conflicts"
  ) {
    val base = decide(syntheticWork(otherTitle))
    val agree = decide(syntheticWork(otherTitle, Vector(repo1), Vector(repo1)))
    assertEquals(agree.outcome, base.outcome)
    assertNotEquals(agree.outcome, MatchOutcome.Accepted)
    assert(agree.reasonCodes.contains("evidence-only-id-agreement.repository-url"))
    assert(agree.rulesFired.contains(Rules.evidenceAgree("repository-url")))
    val disagree = decide(syntheticWork(sameTitle, Vector(repo1), Vector(repo2)))
    assertNotEquals(disagree.outcome, MatchOutcome.Conflict)
    assertEquals(disagree.conflicts, Vector.empty)
    assert(disagree.reasonCodes.contains("evidence-only-id-disagreement.repository-url"))
    assertEquals(valid(disagree).conflicts, Vector.empty)
  }

  test("arXiv versions of one ID are not a disagreement") {
    val v1 = ExactId(ExactIdScheme.Arxiv, "2101.00001v1")
    val v2 = ExactId(ExactIdScheme.Arxiv, "2101.00001v2")
    val o = decide(syntheticWork(sameTitle, Vector(v1), Vector(v2)))
    assertEquals(o.conflicts, Vector.empty)
    assertNotEquals(o.outcome, MatchOutcome.Conflict)
  }

  test("every exact identifier scheme of each kind has exactly one documented rule") {
    Vector(MatchEntityKind.Researcher -> policy.researcher, MatchEntityKind.Work -> policy.work)
      .foreach { case (k, kp) =>
        assertEquals(
          kp.identifierRules.map(_.scheme).toSet,
          ExactIdScheme.values.filter(_.entityKind == k).map(_.tag).toSet
        )
      }
    val dropped = policy.work.copy(identifierRules =
      policy.work.identifierRules.filterNot(_.scheme == "openalex-work")
    )
    assert(
      MatchPolicy
        .validate(policy.copy(work = dropped))
        .exists(_.contains("no rule for scheme 'openalex-work'"))
    )
  }

  test("no decision with a recorded identifier conflict is ever ACCEPTED (all fixtures)") {
    val all = Vector(
      syntheticResearcher(strongName, Vector(orcid1), Vector(orcid2)),
      syntheticResearcher(strongName, Vector(orcid1, pid), Vector(orcid2, pid)),
      syntheticResearcher(strongName, Vector(oaA1), Vector(oaA2)),
      syntheticResearcher(nameOnly, Vector(orcid1, oaA1), Vector(orcid1, oaA2)),
      syntheticWork(sameTitle, Vector(doi1, w1), Vector(doi2, w1)),
      syntheticWork(sameTitle, Vector(w1), Vector(w2))
    ).map(decide(_))
    all.foreach { o =>
      assert(!(o.outcome == MatchOutcome.Accepted && o.conflicts.nonEmpty), o.toString)
      assert(Resolver.toMatchDecision(o, ctx).isRight, o.rulesFired.toString)
    }
  }
}
