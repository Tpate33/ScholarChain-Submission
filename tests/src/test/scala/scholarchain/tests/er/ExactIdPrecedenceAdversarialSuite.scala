package scholarchain.tests.er

import scholarchain.core.*
import scholarchain.core.ExactIdScheme as S
import scholarchain.entityresolution.policy.*

import ErFixtures.*

/**
 * TE-P5-01 area 1 (README Phase 5 gate "exact ID conflicts do not silently merge"; decisions-05
 * items 2, 3 and 5). Independent sweeps over SYNTHETIC feature profiles and identifier
 * combinations, asserting the precedence invariants as properties rather than single examples, plus
 * metamorphic checks: adding an identifier of a given disposition to a pair may only change its
 * outcome in the way that disposition allows.
 */
class ExactIdPrecedenceAdversarialSuite extends munit.FunSuite {

  private def decision(o: ResolutionOutcome): MatchDecision =
    Resolver.toMatchDecision(o, ctx).fold(e => fail(s"no valid match-decision.v2: $e"), identity)

  /** Every outcome maps to a valid v2 decision that round-trips, and ACCEPTED has no conflicts. */
  private def assertContract(o: ResolutionOutcome, clue: String)(using munit.Location): Unit = {
    val d = decision(o)
    assertEquals(MatchDecision.contract.validate(d), Vector.empty, clue)
    assertEquals(Json.decode[MatchDecision](Json.write(d)), Right(d), clue)
    if (o.outcome == MatchOutcome.Accepted) {
      assertEquals(o.conflicts, Vector.empty, s"ACCEPTED with conflicts: $clue")
      assertEquals(d.conflicts, Vector.empty, s"ACCEPTED decision with conflicts: $clue")
    }
    if (o.outcome == MatchOutcome.Conflict) assert(d.conflicts.nonEmpty, clue)
  }

  // ------------------------------------------------------------------ researcher T1

  private val researcherT1Disagreements: Vector[(String, Vector[ExactId], Vector[ExactId])] =
    Vector(
      ("orcid", Vector(id(S.Orcid, orcid(1))), Vector(id(S.Orcid, orcid(2)))),
      ("dblp-pid", Vector(id(S.DblpPid, pidA)), Vector(id(S.DblpPid, pidB)))
    )

  /** Agreements of other researcher schemes that identify on agreement. */
  private def researcherAgreements(disagreeing: String): Vector[(String, ExactId)] =
    Vector(
      "orcid" -> id(S.Orcid, orcid(7)),
      "dblp-pid" -> id(S.DblpPid, "00/9077"),
      "openalex-author" -> id(S.OpenAlexAuthor, "A9000000077"),
      "s2-author" -> id(S.SemanticScholarAuthor, "900000077")
    ).filterNot(_._1 == disagreeing)

  test("researcher: an orcid or dblp-pid disagreement is never ACCEPTED on any feature profile") {
    var checked = 0
    for {
      (profile, kv) <- researcherProfiles
      (scheme, a, b) <- researcherT1Disagreements
      extra <- None +: researcherAgreements(scheme).map(Some(_))
    } {
      val agreeing = extra.map(_._2).toVector
      val o = researcher(kv, a ++ agreeing, b ++ agreeing).decide()
      val clue = s"profile=$profile disagree=$scheme agree=${extra.map(_._1)}"
      assertNotEquals(o.outcome, MatchOutcome.Accepted, clue)
      assert(
        o.conflicts.exists(c =>
          c.scheme.tag == scheme && c.kind == IdentifierConflictKind.Disagreement
        ),
        s"the disagreement is not recorded: $clue"
      )
      if (extra.isDefined)
        assertEquals(o.outcome, MatchOutcome.Conflict, s"agreeing ID must contradict: $clue")
      else
        assert(
          o.outcome == MatchOutcome.Conflict || o.outcome == MatchOutcome.Rejected,
          s"a T1 disagreement alone is CONFLICT or REJECTED: $clue -> ${o.outcome}"
        )
      assertEquals(o.path, MatchPath.ExactId, clue)
      assertEquals(o.llmBandEligible, false, clue)
      assertContract(o, clue)
      checked += 1
    }
    assertEquals(checked, 6 * 2 * 4)
  }

  test(
    "researcher: a T1 disagreement on an implausible pair is REJECTED, on a plausible one CONFLICT"
  ) {
    val (_, a, b) = researcherT1Disagreements.head
    val implausible = researcher(nothing, a, b).decide()
    assertEquals(implausible.outcome, MatchOutcome.Rejected)
    assertEquals(implausible.plausibility.map(_.plausible), Some(false))
    assert(implausible.rulesFired.contains(Rules.disagreeImplausible("orcid")))
    val plausible = researcher(nameOnly, a, b).decide()
    assertEquals(plausible.outcome, MatchOutcome.Conflict)
    assertEquals(plausible.plausibility.map(_.plausible), Some(true))
    // Same-work and homonym-differs pairs are never plausible: an orcid disagreement rejects them.
    Vector(sameWork, homonymDiffers).foreach { kv =>
      assertEquals(researcher(kv, a, b).decide().outcome, MatchOutcome.Rejected)
    }
  }

  // ------------------------------------------------------------------ work T1

  private val workT1Disagreements: Vector[(String, Vector[ExactId], Vector[ExactId])] =
    Vector(
      ("doi", Vector(id(S.Doi, doiA)), Vector(id(S.Doi, doiB))),
      ("arxiv", Vector(id(S.Arxiv, arxivA)), Vector(id(S.Arxiv, arxivB)))
    )

  /** Agreements of other work schemes; `identifies` = the scheme decides on agreement. */
  private def workAgreements(disagreeing: String): Vector[(String, ExactId, Boolean)] =
    Vector(
      ("doi", id(S.Doi, "10.9999/synthetic.te.shared"), true),
      ("arxiv", id(S.Arxiv, "2101.00077"), true),
      ("dblp-key", id(S.DblpKey, "journals/synthetic/Shared21"), true),
      ("scopus-eid", id(S.ScopusEid, "2-s2.0-90000000077"), true),
      ("openalex-work", id(S.OpenAlexWork, "W9000000077"), true),
      ("mag", id(S.Mag, "9000000077"), true),
      ("s2-corpus", id(S.SemanticScholarCorpus, "900000077"), true),
      ("s2-paper", id(S.SemanticScholarPaper, "c" * 40), true),
      ("repository-url", id(S.RepositoryUrl, "https://gitlab.example.org/synthetic/shared"), false)
    ).filterNot(_._1 == disagreeing)

  test("work: a doi or arxiv disagreement is never ACCEPTED, whatever else agrees") {
    var checked = 0
    for {
      (profile, kv) <- workProfiles :+ ("corroborating" -> corroborating)
      (scheme, a, b) <- workT1Disagreements
      extra <- None +: workAgreements(scheme).map(Some(_))
    } {
      val agreeing = extra.map(_._2).toVector
      val o = work(kv, a ++ agreeing, b ++ agreeing).decide()
      val clue = s"profile=$profile disagree=$scheme agree=${extra.map(_._1)}"
      assertNotEquals(o.outcome, MatchOutcome.Accepted, clue)
      assert(o.conflicts.exists(_.scheme.tag == scheme), s"disagreement not recorded: $clue")
      if (extra.exists(_._3))
        assertEquals(o.outcome, MatchOutcome.Conflict, s"agreeing ID must contradict: $clue")
      assertEquals(o.llmBandEligible, false, clue)
      assertContract(o, clue)
      checked += 1
    }
    assertEquals(checked, 5 * 2 * 9)
  }

  // ------------------------------------------------------------------ overrides

  test(
    "a T1 agreement overrides a blocking disagreement with the documented reason code and rule"
  ) {
    val cases = Vector(
      // (kind, blocking scheme, disposition, by, build)
      (
        "openalex-author",
        IdentifierRule.BlockInferredAcceptanceIfPlausible,
        "orcid",
        researcher(
          nameOnly,
          Vector(id(S.OpenAlexAuthor, oaAuthorA), id(S.Orcid, orcid(3))),
          Vector(id(S.OpenAlexAuthor, oaAuthorB), id(S.Orcid, orcid(3)))
        )
      ),
      (
        "s2-author",
        IdentifierRule.BlockInferredAcceptanceIfPlausible,
        "dblp-pid",
        researcher(
          nothing,
          Vector(id(S.SemanticScholarAuthor, s2AuthorA), id(S.DblpPid, pidA)),
          Vector(id(S.SemanticScholarAuthor, s2AuthorB), id(S.DblpPid, pidA))
        )
      ),
      (
        "dblp-key",
        IdentifierRule.BlockInferredAcceptance,
        "doi",
        work(
          nothingWork,
          Vector(id(S.DblpKey, dblpKeyA), id(S.Doi, doiA)),
          Vector(id(S.DblpKey, dblpKeyB), id(S.Doi, doiA))
        )
      ),
      (
        "scopus-eid",
        IdentifierRule.BlockInferredAcceptance,
        "arxiv",
        work(
          bandWork,
          Vector(id(S.ScopusEid, scopusA), id(S.Arxiv, arxivA)),
          Vector(id(S.ScopusEid, scopusB), id(S.Arxiv, arxivA))
        )
      )
    )
    cases.foreach { case (blocking, disposition, by, built) =>
      val o = built.decide()
      assertEquals(o.outcome, MatchOutcome.Accepted, blocking)
      assertEquals(o.path, MatchPath.ExactId, blocking)
      assertEquals(o.conflicts, Vector.empty, blocking)
      assert(
        o.reasonCodes.contains(s"$disposition.$blocking.overridden-by.$by"),
        s"$blocking: ${o.reasonCodes}"
      )
      assert(
        o.rulesFired.contains(s"exact-id.override.$blocking.by.$by"),
        s"$blocking: ${o.rulesFired}"
      )
      assertContract(o, blocking)
    }
  }

  test("a T2 agreement never overrides a blocking disagreement: it contradicts it (CONFLICT)") {
    val cases = Vector(
      "dblp-key vs scopus-eid" -> work(
        strongWork,
        Vector(id(S.DblpKey, dblpKeyA), id(S.ScopusEid, scopusA)),
        Vector(id(S.DblpKey, dblpKeyB), id(S.ScopusEid, scopusA))
      ),
      "scopus-eid vs dblp-key" -> work(
        titleYearAuthors,
        Vector(id(S.ScopusEid, scopusA), id(S.DblpKey, dblpKeyA)),
        Vector(id(S.ScopusEid, scopusB), id(S.DblpKey, dblpKeyA))
      ),
      "dblp-key vs openalex-work (cluster, even corroborated)" -> work(
        corroborating,
        Vector(id(S.DblpKey, dblpKeyA), id(S.OpenAlexWork, oaWorkA)),
        Vector(id(S.DblpKey, dblpKeyB), id(S.OpenAlexWork, oaWorkA))
      ),
      "openalex-author vs s2-author" -> researcher(
        strongResearcher,
        Vector(id(S.OpenAlexAuthor, oaAuthorA), id(S.SemanticScholarAuthor, s2AuthorA)),
        Vector(id(S.OpenAlexAuthor, oaAuthorB), id(S.SemanticScholarAuthor, s2AuthorA))
      )
    )
    cases.foreach { case (clue, built) =>
      val o = built.decide()
      assertEquals(o.outcome, MatchOutcome.Conflict, clue)
      assert(
        o.conflicts.exists(_.kind == IdentifierConflictKind.ContradictedAgreement),
        s"$clue: ${o.conflicts}"
      )
      assertContract(o, clue)
    }
  }

  // ------------------------------------------------------------------ metamorphic dispositions

  private def blockedExpectation(base: MatchOutcome): MatchOutcome =
    if (base == MatchOutcome.Accepted) MatchOutcome.Unresolved else base

  test(
    "record keys (dblp-key, scopus-eid): a disagreement only caps ACCEPTED; never CONFLICT or REJECTED"
  ) {
    val keyPairs = Vector(
      "dblp-key" -> (Vector(id(S.DblpKey, dblpKeyA)), Vector(id(S.DblpKey, dblpKeyB))),
      "scopus-eid" -> (Vector(id(S.ScopusEid, scopusA)), Vector(id(S.ScopusEid, scopusB))),
      "both" -> (
        Vector(id(S.DblpKey, dblpKeyA), id(S.ScopusEid, scopusA)),
        Vector(id(S.DblpKey, dblpKeyB), id(S.ScopusEid, scopusB))
      )
    )
    val profiles = workProfiles ++ Vector(
      "corroborating" -> corroborating,
      "plausible-low" -> Seq("title.sim" -> 0.86)
    )
    for { (profile, kv) <- profiles; (scheme, (a, b)) <- keyPairs } {
      val base = work(kv).decide()
      val o = work(kv, a, b).decide()
      val clue = s"profile=$profile scheme=$scheme base=${base.outcome}"
      assertEquals(o.outcome, blockedExpectation(base.outcome), clue)
      assertNotEquals(o.outcome, MatchOutcome.Conflict, clue)
      if (base.outcome != MatchOutcome.Rejected)
        assertNotEquals(o.outcome, MatchOutcome.Rejected, clue)
      assertEquals(o.score.probability, base.score.probability, s"P-6 zero weight: $clue")
      assertEquals(o.plausibility, None, s"record keys never trigger plausibility: $clue")
      assertEquals(o.llmBandEligible, false, clue)
      assert(o.conflicts.nonEmpty, s"the blocking disagreement is recorded: $clue")
      assert(
        o.conflicts.forall(_.kind == IdentifierConflictKind.Disagreement),
        clue
      )
      assertContract(o, clue)
    }
  }

  test(
    "researcher algorithmic IDs: a disagreement blocks or rejects, never accepts or conflicts alone"
  ) {
    val pairs = Vector(
      "openalex-author" -> (
        Vector(id(S.OpenAlexAuthor, oaAuthorA)),
        Vector(
          id(S.OpenAlexAuthor, oaAuthorB)
        )
      ),
      "s2-author" -> (
        Vector(id(S.SemanticScholarAuthor, s2AuthorA)),
        Vector(
          id(S.SemanticScholarAuthor, s2AuthorB)
        )
      )
    )
    for { (profile, kv) <- researcherProfiles; (scheme, (a, b)) <- pairs } {
      val o = researcher(kv, a, b).decide()
      val clue = s"profile=$profile scheme=$scheme"
      assertNotEquals(o.outcome, MatchOutcome.Accepted, clue)
      assertNotEquals(o.outcome, MatchOutcome.Conflict, clue)
      assertEquals(o.llmBandEligible, false, clue)
      if (o.plausibility.exists(!_.plausible))
        assertEquals(o.outcome, MatchOutcome.Rejected, clue)
      assertContract(o, clue)
    }
  }

  test("evidence-only schemes never block, decide, or enter conflicts (metamorphic, P-1)") {
    val evidenceOnly = Vector(
      "openalex-work disagree" -> (
        Vector(id(S.OpenAlexWork, oaWorkA)),
        Vector(
          id(S.OpenAlexWork, oaWorkB)
        )
      ),
      "mag disagree" -> (Vector(id(S.Mag, magA)), Vector(id(S.Mag, magB))),
      "s2-corpus disagree" -> (
        Vector(id(S.SemanticScholarCorpus, s2CorpusA)),
        Vector(
          id(S.SemanticScholarCorpus, s2CorpusB)
        )
      ),
      "s2-paper disagree" -> (
        Vector(id(S.SemanticScholarPaper, s2PaperA)),
        Vector(
          id(S.SemanticScholarPaper, s2PaperB)
        )
      ),
      "repository-url disagree" -> (
        Vector(id(S.RepositoryUrl, repoA)),
        Vector(
          id(S.RepositoryUrl, repoB)
        )
      ),
      "repository-url agree" -> (
        Vector(id(S.RepositoryUrl, repoA)),
        Vector(
          id(S.RepositoryUrl, repoA)
        )
      ),
      "all disagree" -> (
        Vector(id(S.OpenAlexWork, oaWorkA), id(S.Mag, magA), id(S.RepositoryUrl, repoA)),
        Vector(id(S.OpenAlexWork, oaWorkB), id(S.Mag, magB), id(S.RepositoryUrl, repoB))
      )
    )
    for { (profile, kv) <- workProfiles; (what, (a, b)) <- evidenceOnly } {
      val base = work(kv).decide()
      val o = work(kv, a, b).decide()
      val clue = s"profile=$profile $what"
      assertEquals(
        (o.outcome, o.path, o.confidence, o.score.probability, o.llmBandEligible, o.conflicts),
        (
          base.outcome,
          base.path,
          base.confidence,
          base.score.probability,
          base.llmBandEligible,
          base.conflicts
        ),
        clue
      )
      assertContract(o, clue)
    }
  }

  test(
    "P-6: the id.other.conflict weight is 0, so record-key and cluster disagreements never move the score"
  ) {
    assertEquals(policy.work.weights.get("id.other.conflict"), Some(0.0))
    val base = work(strongWork).decide()
    val two = work(
      strongWork,
      Vector(id(S.OpenAlexWork, oaWorkA), id(S.Mag, magA)),
      Vector(id(S.OpenAlexWork, oaWorkB), id(S.Mag, magB))
    )
    assertEquals(two.candidate.features("id.other.conflict"), 2.0)
    assertEquals(two.decide().score.probability, base.score.probability)
  }

  test("refused: endpoint identifiers that contradict the candidate, or the wrong versions") {
    val built = work(nothingWork, Vector(id(S.Doi, doiA)), Vector(id(S.Doi, doiB)))
    // Hiding the disagreement from the resolver (empty endpoint sets) is refused, not decided.
    assert(Resolver.decide(policy, built.candidate, EndpointIds.Empty).isLeft)
    // A swapped agreement set is refused.
    val agree = work(nothingWork, Vector(id(S.Doi, doiA)), Vector(id(S.Doi, doiA)))
    assert(Resolver.decide(policy, agree.candidate, built.ids).isLeft)
    // A researcher identifier on a work endpoint is refused.
    assert(
      Resolver
        .decide(
          policy,
          agree.candidate,
          agree.ids.copy(a = agree.ids.a :+ id(S.Orcid, orcid(1)))
        )
        .isLeft
    )
    val wrongFeatures = researcher(nameOnly, featureVersion = "er-features.v1")
    assert(Resolver.decide(policy, wrongFeatures.candidate, wrongFeatures.ids).isLeft)
    val wrongBlocking = researcher(nameOnly, blockingVersion = "er-blocking.v1")
    assert(Resolver.decide(policy, wrongBlocking.candidate, wrongBlocking.ids).isLeft)
  }
}
