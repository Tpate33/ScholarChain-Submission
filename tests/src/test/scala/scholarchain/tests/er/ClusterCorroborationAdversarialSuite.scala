package scholarchain.tests.er

import scala.util.Try

import scholarchain.core.*
import scholarchain.core.ExactIdScheme as S
import scholarchain.core.config.EntityResolutionConfig
import scholarchain.entityresolution.adjudication.*
import scholarchain.entityresolution.identifiers.ExactIdentifiers
import scholarchain.entityresolution.policy.*

import ErFixtures.*

/**
 * TE-P5-01 area 2 (decisions-05 item 4: a cluster identifier agreement alone is not sufficient for
 * ACCEPTED; corroborating publication-stage evidence is required, otherwise UNRESOLVED). SYNTHETIC
 * candidates only.
 *
 * The last two tests (TE-P5-02) assert the DECIDED publication-stage behaviour of er-policy.v4
 * (human decisions-05 and decisions-06, Option A) that closed the two policy gaps reported by
 * ER-P5-07 (Risks 1 and 2): a preprint against its published version is never ACCEPTED, a shared
 * arXiv ID does not by itself merge a preprint with its published version, and an uncertain stage
 * is UNRESOLVED and never sent to the LLM.
 *
 * The preprint-DOI tests (TE-P5-03) assert human decision decisions-07 (Option 1, er-policy.v5): a
 * DOI agreement whose shared DOIs are all preprint DOIs (policy `preprintDoiPrefixes`) decides only
 * when both records are pure preprints; otherwise the pair is UNRESOLVED on the exact-id path and
 * no inferred, score, or LLM path may accept it. Shared publication DOIs and T1 conflicts
 * unchanged.
 */
class ClusterCorroborationAdversarialSuite extends munit.FunSuite {

  private val clusterSchemes: Vector[(String, ExactId)] = Vector(
    "openalex-work" -> id(S.OpenAlexWork, oaWorkA),
    "mag" -> id(S.Mag, magA),
    "s2-corpus" -> id(S.SemanticScholarCorpus, s2CorpusA),
    "s2-paper" -> id(S.SemanticScholarPaper, s2PaperA)
  )

  private def decision(o: ResolutionOutcome): MatchDecision =
    Resolver.toMatchDecision(o, ctx).fold(e => fail(s"no valid decision: $e"), identity)

  private def assertUncorroborated(o: ResolutionOutcome, scheme: String, clue: String)(using
      munit.Location
  ): Unit = {
    assertEquals(o.outcome, MatchOutcome.Unresolved, clue)
    assertEquals(o.path, MatchPath.ExactId, clue)
    assertEquals(o.confidence, 0.0, clue)
    assertEquals(o.llmBandEligible, false, s"never sent to the LLM: $clue")
    assertEquals(o.conflicts, Vector.empty, clue)
    assert(o.rulesFired.contains(Rules.ClusterUnresolved), s"$clue: ${o.rulesFired}")
    assert(
      o.reasonCodes.contains(s"cluster-id-agreement-uncorroborated.$scheme"),
      s"$clue: ${o.reasonCodes}"
    )
    assertEquals(MatchDecision.contract.validate(decision(o)), Vector.empty, clue)
  }

  test(
    "a cluster ID agreement alone is never ACCEPTED, not even when the bibliographic rule or score would accept"
  ) {
    for { (scheme, e) <- clusterSchemes; (profile, kv) <- workProfiles } {
      val o = work(kv, Vector(e), Vector(e)).decide()
      assertUncorroborated(o, scheme, s"$scheme profile=$profile")
    }
    // Two cluster IDs agreeing together are still not corroboration.
    val all = clusterSchemes.map(_._2)
    val o = work(titleYearAuthors, all, all).decide()
    assertEquals(o.outcome, MatchOutcome.Unresolved)
  }

  test("positive control: a corroborated cluster agreement is ACCEPTED at its constant") {
    for ((scheme, e) <- clusterSchemes) {
      val o = work(corroborating, Vector(e), Vector(e)).decide()
      assertEquals(o.outcome, MatchOutcome.Accepted, scheme)
      assertEquals(o.path, MatchPath.ExactId, scheme)
      assertEquals(o.confidence, 0.95, scheme)
      assert(o.reasonCodes.contains(s"cluster-id-agreement-corroborated.$scheme"), scheme)
      assertEquals(o.corroboration.map(_.corroborated), Some(true), scheme)
    }
  }

  test("preprint vs version of record sharing a cluster ID is never ACCEPTED") {
    val w = id(S.OpenAlexWork, oaWorkA)
    val cases = Vector(
      "arxiv vs published DOI" -> (Vector(w, id(S.Arxiv, arxivA)), Vector(w, id(S.Doi, doiA))),
      "arxiv vs nothing" -> (Vector(w, id(S.Arxiv, arxivA)), Vector(w)),
      "preprint DOI vs nothing" -> (Vector(w, id(S.Doi, preprintDoi)), Vector(w)),
      "nothing vs arxiv" -> (Vector(w), Vector(w, id(S.Arxiv, arxivA))),
      "preprint DOI vs published DOI (also a T1 disagreement)" -> (
        Vector(w, id(S.Doi, preprintDoi)),
        Vector(w, id(S.Doi, doiA))
      )
    )
    for ((clue, (a, b)) <- cases) {
      val o = work(corroborating, a, b).decide()
      assertNotEquals(o.outcome, MatchOutcome.Accepted, clue)
      if (!clue.contains("T1"))
        assert(
          o.reasonCodes.contains("corroboration-clause-failed.stage"),
          s"$clue: ${o.reasonCodes}"
        )
    }
    // Two preprints of one entity (arXiv ID on one side, its arXiv DOI on the other) are allowed.
    val both =
      work(corroborating, Vector(w, id(S.Arxiv, arxivA)), Vector(w, id(S.Doi, preprintDoi)))
    assertEquals(both.decide().outcome, MatchOutcome.Accepted)
  }

  /** Corroborating features with some values replaced. */
  private def corr(kv: (String, Double)*): Seq[(String, Double)] = corroborating ++ kv

  test("clause boundaries: venue, year and title each pass exactly at their documented bound") {
    val w = Vector(id(S.OpenAlexWork, oaWorkA))
    def outcome(kv: Seq[(String, Double)]) = work(kv, w, w).decide()
    val c = policy.workCorroboration
    assertEquals((c.venueNameJaccardMin, c.maxYearDiff, c.titleSimMin), (0.8, 0.0, 0.9))
    val venueByName = Seq("venue.id_agree" -> 0.0, "venue.name_both" -> 1.0)
    val cases: Vector[(String, Seq[(String, Double)], Boolean, String)] = Vector(
      ("venue name jaccard at 0.8", corr(venueByName :+ ("venue.name_jaccard" -> 0.8)*), true, ""),
      (
        "venue name jaccard below 0.8",
        corr(venueByName :+ ("venue.name_jaccard" -> Math.nextDown(0.8))*),
        false,
        "venue"
      ),
      (
        "venue jaccard 1 without venue.name_both",
        corr("venue.id_agree" -> 0.0, "venue.name_both" -> 0.0, "venue.name_jaccard" -> 1.0),
        false,
        "venue"
      ),
      ("year diff 0", corr("year.abs_diff" -> 0.0), true, ""),
      ("year diff 1", corr("year.abs_diff" -> 1.0), false, "year"),
      ("year diff 0 without year.both", corr("year.both" -> 0.0), false, "year"),
      ("title sim 0.9 not exact", corr("title.exact" -> 0.0, "title.sim" -> 0.9), true, ""),
      (
        "title sim below 0.9",
        corr("title.exact" -> 0.0, "title.sim" -> Math.nextDown(0.9)),
        false,
        "title"
      ),
      (
        "title sim 1 without title.exact (two empty titles)",
        corr("title.exact" -> 0.0, "title.sim" -> 1.0),
        false,
        "title"
      )
    )
    cases.foreach { case (clue, kv, accepted, failedClause) =>
      val o = outcome(kv)
      if (accepted) assertEquals(o.outcome, MatchOutcome.Accepted, clue)
      else {
        assertUncorroborated(o, "openalex-work", clue)
        assert(
          o.reasonCodes.contains(s"corroboration-clause-failed.$failedClause"),
          s"$clue: ${o.reasonCodes}"
        )
      }
    }
  }

  test(
    "missing inputs fail closed: absent venue, year and title features leave the pair UNRESOLVED"
  ) {
    val w = Vector(id(S.Mag, magA))
    val nonId = Set(
      "venue.id_agree",
      "venue.id_both",
      "venue.name_both",
      "venue.name_jaccard",
      "year.both",
      "year.abs_diff",
      "title.exact",
      "title.sim",
      "title.token_jaccard"
    )
    val o = work(Seq.empty, w, w, dropFeatures = nonId).decide()
    assertUncorroborated(o, "mag", "every corroboration input missing")
    Vector("venue", "year", "title").foreach(cl =>
      assert(o.reasonCodes.contains(s"corroboration-clause-failed.$cl"), cl)
    )
    val onlyVenueMissing = work(
      corroborating.filterNot(_._1.startsWith("venue.")),
      w,
      w,
      dropFeatures = nonId.filter(_.startsWith("venue."))
    ).decide()
    assertUncorroborated(onlyVenueMissing, "mag", "venue missing")
    assertEquals(onlyVenueMissing.corroboration.map(_.failed), Some(Vector("venue")))
  }

  test(
    "a T1 agreement accepts without corroboration; a T1 disagreement makes a cluster agreement a CONFLICT"
  ) {
    val w = id(S.OpenAlexWork, oaWorkA)
    val t1 = work(nothingWork, Vector(w, id(S.Doi, doiA)), Vector(w, id(S.Doi, doiA))).decide()
    assertEquals(t1.outcome, MatchOutcome.Accepted)
    assert(t1.rulesFired.contains(Rules.exactAgree("doi")))
    assert(
      !t1.rulesFired.contains(Rules.exactAgree("openalex-work")),
      "uncorroborated cluster ID decided"
    )
    val contradicted =
      work(corroborating, Vector(w, id(S.Doi, doiA)), Vector(w, id(S.Doi, doiB))).decide()
    assertEquals(contradicted.outcome, MatchOutcome.Conflict)
  }

  test(
    "characterization (ER-P5-07 Risk 4): a researcher author-cluster agreement alone still accepts at 0.90"
  ) {
    val a = id(S.OpenAlexAuthor, oaAuthorA)
    val o = researcher(nothing, Vector(a), Vector(a)).decide()
    assertEquals((o.outcome, o.path, o.confidence), (MatchOutcome.Accepted, MatchPath.ExactId, 0.9))
  }

  // ------------------------------------------------------------------ publication-stage target

  private val venueAgree: Seq[(String, Double)] =
    Seq("venue.id_both" -> 1.0, "venue.id_agree" -> 1.0)

  /** UNRESOLVED by the publication-stage cap of steps 7 and 8 (er-policy.v4). */
  private def assertStageBlocked(
      o: ResolutionOutcome,
      path: MatchPath,
      verdict: StageVerdict,
      clue: String
  )(using munit.Location): Unit = {
    assertEquals((o.outcome, o.path, o.confidence), (MatchOutcome.Unresolved, path, 0.0), clue)
    assertEquals(o.llmBandEligible, false, s"never sent to the LLM: $clue")
    assertEquals(o.stage.map(_.verdict), Some(verdict), clue)
    assert(o.rulesFired.contains(Rules.acceptBlockedByStage(path)), s"$clue: ${o.rulesFired}")
    assert(
      o.reasonCodes.contains(Rules.Reason.stage(verdict)) &&
        o.reasonCodes.contains("acceptance-blocked-by-publication-stage"),
      s"$clue: ${o.reasonCodes}"
    )
    assertEquals(o.conflicts, Vector.empty, clue)
    assertEquals(Adjudication.eligible(policy, o), false, clue)
    assertEquals(MatchDecision.contract.validate(decision(o)), Vector.empty, clue)
  }

  test(
    "publication stage (decisions-06, gap 1 closed): a preprint is never ACCEPTED against its published version by the title-year-authors rule or a high score; uncertain stage is UNRESOLVED and never sent to the LLM"
  ) {
    // SYNTHETIC: side A is a DBLP CoRR-style preprint record (arXiv ID or no identifier), side B
    // the published version with a non-preprint DOI, same folded title, same year, same authors.
    // Option A: a non-match, so the policy must not accept it on any inferred path.
    val arxivVsDoi = (Vector(id(S.Arxiv, arxivA)), Vector(id(S.Doi, doiA)))
    val o1 = work(titleYearAuthors, arxivVsDoi._1, arxivVsDoi._2).decide()
    assertStageBlocked(
      o1,
      MatchPath.DeterministicBibliographic,
      StageVerdict.CrossStage,
      "arxiv vs doi"
    )
    assert(o1.rulesFired.contains(Rules.TitleYearAuthorsExact), "the rule fired and was capped")
    // A preprint DOI marks the stage the same way an arXiv ID does (side B carries no DOI, so no
    // T1 disagreement decides first).
    assertStageBlocked(
      work(titleYearAuthors, Vector(id(S.Doi, preprintDoi)), Vector.empty).decide(),
      MatchPath.DeterministicBibliographic,
      StageVerdict.CrossStage,
      "preprint doi vs no identifier"
    )
    // A venue agreement never overrides a preprint marker on exactly one side.
    assertStageBlocked(
      work(titleYearAuthors ++ venueAgree, arxivVsDoi._1, arxivVsDoi._2).decide(),
      MatchPath.DeterministicBibliographic,
      StageVerdict.CrossStage,
      "arxiv vs doi with the same venue"
    )
    // No identifier on the preprint side and no venue agreement: the stage is uncertain.
    assertStageBlocked(
      work(titleYearAuthors, Vector.empty, Vector(id(S.Doi, doiA))).decide(),
      MatchPath.DeterministicBibliographic,
      StageVerdict.Uncertain,
      "no id vs doi"
    )
    // A score above the accept threshold is capped the same way.
    val byScore = work(strongWork, arxivVsDoi._1, arxivVsDoi._2).decide()
    assert(byScore.score.probability >= policy.thresholds.accept, s"${byScore.score.probability}")
    assertStageBlocked(byScore, MatchPath.Probabilistic, StageVerdict.CrossStage, "by score")
    // A band score of a cross-stage or uncertain pair is never sent to the LLM.
    for (
      (clue, (a, b), verdict) <- Vector(
        ("band cross-stage", arxivVsDoi, StageVerdict.CrossStage),
        ("band uncertain", (Vector.empty[ExactId], Vector(id(S.Doi, doiA))), StageVerdict.Uncertain)
      )
    ) {
      val o = work(bandWork, a, b).decide()
      assert(policy.llmBand.contains(o.score.probability), s"$clue: ${o.score.probability}")
      assertEquals((o.outcome, o.path), (MatchOutcome.Unresolved, MatchPath.Probabilistic), clue)
      assertEquals(o.llmBandEligible, false, clue)
      assertEquals(o.stage.map(_.verdict), Some(verdict), clue)
      assert(!o.rulesFired.contains(Rules.ScoreLlmBand), s"$clue: ${o.rulesFired}")
      assert(
        o.reasonCodes.contains("llm-band-excluded-by-publication-stage") &&
          o.reasonCodes.contains(Rules.Reason.stage(verdict)),
        s"$clue: ${o.reasonCodes}"
      )
      assertEquals(Adjudication.eligible(policy, o), false, clue)
    }
    // Positive controls: an established same stage still accepts on the same rules.
    val sameVenue = work(titleYearAuthors ++ venueAgree, Vector.empty, Vector(id(S.Doi, doiA)))
      .decide()
    assertEquals(
      (sameVenue.outcome, sameVenue.path, sameVenue.stage.map(_.verdict)),
      (
        MatchOutcome.Accepted,
        MatchPath.DeterministicBibliographic,
        Some(StageVerdict.SameVenue)
      )
    )
    val twoPreprints =
      work(strongWork, Vector(id(S.Arxiv, arxivA)), Vector(id(S.Doi, preprintDoi))).decide()
    assertEquals(
      (twoPreprints.outcome, twoPreprints.path, twoPreprints.stage.map(_.verdict)),
      (MatchOutcome.Accepted, MatchPath.Probabilistic, Some(StageVerdict.SamePreprint))
    )
  }

  test(
    "publication stage (decisions-06, gap 2 closed): a shared arXiv ID does not by itself merge a preprint with a published record listing that arXiv ID"
  ) {
    // SYNTHETIC: side A is the preprint (arXiv ID only), side B the published version (its DOI and
    // the same arXiv ID). No DOI disagreement exists because the preprint carries no DOI.
    val preprint = Vector(id(S.Arxiv, arxivA))
    val published = Vector(id(S.Arxiv, arxivA), id(S.Doi, doiA))
    val profiles = Vector(
      "different year" -> Seq("year.both" -> 1.0, "year.abs_diff" -> 2.0),
      "title-year-authors" -> titleYearAuthors,
      "title-year-authors and same venue" -> (titleYearAuthors ++ venueAgree),
      "strong score" -> strongWork,
      "band score" -> bandWork
    )
    for {
      (profile, kv) <- profiles
      (sideA, sideB, orient) <- Vector(
        (preprint, published, "A=preprint"),
        (published, preprint, "B=preprint")
      )
    } {
      val clue = s"$profile $orient"
      val o = work(kv, sideA, sideB).decide()
      assertEquals(
        (o.outcome, o.path, o.confidence),
        (MatchOutcome.Unresolved, MatchPath.ExactId, 0.0),
        clue
      )
      assert(!o.rulesFired.contains(Rules.exactAgree("arxiv")), s"$clue: ${o.rulesFired}")
      assert(o.rulesFired.contains(Rules.ArxivCrossStage), s"$clue: ${o.rulesFired}")
      assert(o.reasonCodes.contains("arxiv-agreement-cross-stage"), s"$clue: ${o.reasonCodes}")
      assert(o.reasonCodes.contains("publication-stage-uncertain"), s"$clue: ${o.reasonCodes}")
      assert(!o.reasonCodes.contains("exact-id-agreement.arxiv"), s"$clue: ${o.reasonCodes}")
      assertEquals(o.stage.map(_.verdict), Some(StageVerdict.Uncertain), clue)
      assertEquals(o.llmBandEligible, false, s"never sent to the LLM: $clue")
      assertEquals(Adjudication.eligible(policy, o), false, clue)
      assertEquals(o.conflicts, Vector.empty, clue)
      assertEquals(MatchDecision.contract.validate(decision(o)), Vector.empty, clue)
    }
    // A T1 disagreement next to the shared arXiv ID still makes it a CONFLICT (step 2).
    val contradicted = work(
      titleYearAuthors,
      Vector(id(S.Arxiv, arxivA), id(S.Doi, doiA)),
      Vector(id(S.Arxiv, arxivA), id(S.Doi, doiB))
    ).decide()
    assertEquals(contradicted.outcome, MatchOutcome.Conflict)
    // Positive control: two pure preprints sharing the arXiv ID are ACCEPTED at the T1 constant.
    val both = work(Seq.empty, preprint, preprint).decide()
    assertEquals(
      (both.outcome, both.path, both.confidence),
      (MatchOutcome.Accepted, MatchPath.ExactId, 0.99)
    )
    assert(both.rulesFired.contains(Rules.exactAgree("arxiv")))
    assert(!both.reasonCodes.contains("arxiv-agreement-cross-stage"), s"${both.reasonCodes}")
  }

  // ------------------------------------------------------------------ preprint DOI (decisions-07)

  /**
   * The active policy version, pinned literally (er-policy.v8, decisions-11). The decisions-07
   * behaviour asserted below is unchanged by v6 (decisions-08), v7 (decisions-09) and v8
   * (decisions-11: no record key is shared in these pairs, and every arXiv ID is unversioned).
   */
  private val ActivePolicyVersion = "er-policy.v8"

  /** The rule and reason code of decisions-07, pinned literally (independent of [[Rules]]). */
  private val preprintDoiRule = "exact-id.unresolved.preprint-doi-agreement-cross-stage"
  private val preprintDoiReason = "preprint-doi-agreement-cross-stage"

  private lazy val (template, schema) =
    AdjudicationProtocol.loadDefault().fold(e => throw new AssertionError(e), identity)

  /**
   * Offers `o` to a DETERMINISTIC FAKE adjudicator that always answers `accept` (no network, no
   * model); returns the result and the number of calls.
   */
  private def offerToLlm(
      o: ResolutionOutcome,
      p: MatchPolicy = policy
  ): (ResolutionOutcome, Int) = {
    var calls = 0
    val fake = new Adjudicator {
      def complete(modelId: String, prompt: String): Either[String, String] = {
        calls += 1
        Right(
          s"""{"schemaVersion":"er-adjudication-response.v1","candidateId":"${o.candidateId}",""" +
            s""""decision":"accept","evidence":["${o.sourceIdA}"],"reasonCodes":[]}"""
        )
      }
    }
    val r = Adjudication
      .adjudicate(
        p,
        o,
        EndpointEvidence(o.sourceIdA, "dblp", Vector("synthetic")),
        EndpointEvidence(o.sourceIdB, "crossref", Vector("synthetic")),
        "fake-model.te-p5-03",
        template,
        schema,
        fake,
        new InMemoryAdjudicationCache
      )
      .fold(e => fail(e.mkString("; ")), identity)
    (r, calls)
  }

  /** The decisions-07 outcome: UNRESOLVED on exact-id, documented codes, never sent to the LLM. */
  private def assertPreprintDoiBlocked(o: ResolutionOutcome, clue: String)(using
      munit.Location
  ): Unit = {
    assertEquals(
      (o.outcome, o.path, o.confidence),
      (MatchOutcome.Unresolved, MatchPath.ExactId, 0.0),
      clue
    )
    assertEquals(o.policyVersion, ActivePolicyVersion, clue)
    assert(o.rulesFired.contains(preprintDoiRule), s"$clue: ${o.rulesFired}")
    assert(!o.rulesFired.contains(Rules.exactAgree("doi")), s"$clue: ${o.rulesFired}")
    assert(o.reasonCodes.contains(preprintDoiReason), s"$clue: ${o.reasonCodes}")
    assert(!o.reasonCodes.contains("exact-id-agreement.doi"), s"$clue: ${o.reasonCodes}")
    val verdict = o.stage.map(_.verdict).getOrElse(fail(s"no stage check: $clue"))
    assert(!verdict.established, s"$clue: $verdict")
    assert(o.reasonCodes.contains(s"publication-stage-${verdict.tag}"), s"$clue: ${o.reasonCodes}")
    assertEquals(o.conflicts, Vector.empty, clue)
    assertEquals(o.llmBandEligible, false, s"never band-eligible: $clue")
    assertEquals(Adjudication.eligible(policy, o), false, clue)
    assertEquals(offerToLlm(o), (o, 0), s"sent to the LLM: $clue")
    val d = decision(o)
    assertEquals(d.schemaVersion, "scholarchain.match-decision.v2", clue)
    assertEquals(MatchDecision.contract.validate(d), Vector.empty, clue)
    assertEquals(Json.decode[MatchDecision](Json.write(d)), Right(d), clue)
    assertEquals((d.decision, d.matchPath), (MatchOutcome.Unresolved, MatchDecisionPath.ExactId))
    assertEquals(d.provenance.policyVersion, Some(ActivePolicyVersion), clue)
    assertEquals(d.adjudication, None, clue)
  }

  private val preprintDoiProfiles: Vector[(String, Seq[(String, Double)])] =
    workProfiles ++ Vector(
      "different year" -> Seq("year.both" -> 1.0, "year.abs_diff" -> 2.0),
      "title-year-authors and same venue" -> (titleYearAuthors ++ venueAgree),
      "corroborating" -> corroborating,
      "corroborating and strong" -> (corroborating ++ strongWork)
    )

  test(
    "preprint DOI (decisions-07 a): a published record listing its own DOI and the preprint DOI is never ACCEPTED against the preprint, in either orientation, on any profile"
  ) {
    assertEquals(Rules.PreprintDoiCrossStage, preprintDoiRule)
    val published = Vector(id(S.Doi, doiA), id(S.Doi, preprintDoi))
    val publishedSides = Vector(
      "own DOI + preprint DOI" -> published,
      "own DOI + preprint DOI + arXiv ID" -> (published :+ id(S.Arxiv, arxivA))
    )
    val preprintSides = Vector(
      "preprint DOI" -> Vector(id(S.Doi, preprintDoi)),
      "preprint DOI + arXiv ID" -> Vector(id(S.Doi, preprintDoi), id(S.Arxiv, arxivA))
    )
    var checked = 0
    for {
      (profile, kv) <- preprintDoiProfiles
      (pubName, pub) <- publishedSides
      (preName, pre) <- preprintSides
      (a, b, orient) <- Vector((pre, pub, "A=preprint"), (pub, pre, "B=preprint"))
    } {
      val clue = s"profile=$profile published=[$pubName] preprint=[$preName] $orient"
      val o = work(kv, a, b).decide()
      if (profile == "strong")
        assert(o.score.probability >= policy.thresholds.accept, s"premise: $clue")
      assertEquals(o.stage.map(_.verdict), Some(StageVerdict.Uncertain), clue)
      assertPreprintDoiBlocked(o, clue)
      checked += 1
    }
    assertEquals(checked, 8 * 2 * 2 * 2)

    // Two published versions (different publication DOIs) that share only the preprint DOI.
    assertPreprintDoiBlocked(
      work(
        titleYearAuthors ++ venueAgree,
        Vector(id(S.Doi, doiA), id(S.Doi, preprintDoi)),
        Vector(id(S.Doi, doiB), id(S.Doi, preprintDoi))
      ).decide(),
      "two published versions sharing the preprint DOI"
    )

    // No fallback: thresholds configured so low that the score would accept still never reach it.
    val low = MatchPolicy
      .withConfig(
        policy,
        EntityResolutionConfig(
          policy.policyVersion,
          0.3,
          0.05,
          0.1,
          0.2,
          policy.adjudication.promptVersion
        )
      )
      .fold(e => fail(s"config refused: $e"), identity)
    for ((profile, kv) <- Vector("strong" -> strongWork, "band" -> bandWork)) {
      val o = work(kv, Vector(id(S.Doi, preprintDoi)), published).decide(low)
      assert(o.score.probability >= low.thresholds.accept, s"premise $profile")
      assertEquals((o.outcome, o.path), (MatchOutcome.Unresolved, MatchPath.ExactId), profile)
      assert(o.rulesFired.contains(preprintDoiRule), s"$profile: ${o.rulesFired}")
      assertEquals(offerToLlm(o, low), (o, 0), profile)
    }

    // No fallback through a corroborated cluster ID: the stage clause fails (one side is mixed).
    val w = id(S.OpenAlexWork, oaWorkA)
    val cluster = work(corroborating, Vector(w, id(S.Doi, preprintDoi)), published :+ w).decide()
    assertNotEquals(cluster.outcome, MatchOutcome.Accepted)
    assertEquals((cluster.outcome, cluster.path), (MatchOutcome.Unresolved, MatchPath.ExactId))
    assert(
      cluster.reasonCodes.contains("corroboration-clause-failed.stage"),
      s"${cluster.reasonCodes}"
    )
    assert(cluster.reasonCodes.contains(preprintDoiReason), s"${cluster.reasonCodes}")

    // A forged in-band probabilistic copy is still refused by the adjudication gate (stage).
    val band = work(bandWork, Vector(id(S.Doi, preprintDoi)), published).decide()
    assert(policy.llmBand.contains(band.score.probability), s"${band.score.probability}")
    val forged = band.copy(path = MatchPath.Probabilistic, llmBandEligible = true)
    assertEquals(Adjudication.eligible(policy, forged), false)
    assertEquals(offerToLlm(forged), (forged, 0))

    // T1 conflicts unchanged: an arXiv disagreement next to the shared preprint DOI is a CONFLICT,
    // and the preprint DOI agreement is recorded as the contradicted agreement.
    val t1 = work(
      titleYearAuthors,
      Vector(id(S.Doi, preprintDoi), id(S.Arxiv, arxivA)),
      published :+ id(S.Arxiv, arxivB)
    ).decide()
    assertEquals((t1.outcome, t1.path), (MatchOutcome.Conflict, MatchPath.ExactId))
    assert(
      t1.conflicts.exists(c =>
        c.scheme == S.Arxiv && c.kind == IdentifierConflictKind.Disagreement
      ),
      s"${t1.conflicts}"
    )
    assert(
      t1.conflicts.exists(c =>
        c.scheme == S.Doi && c.kind == IdentifierConflictKind.ContradictedAgreement
      ),
      s"${t1.conflicts}"
    )
    assertEquals(MatchDecision.contract.validate(decision(t1)), Vector.empty)
  }

  test(
    "preprint DOI (decisions-07 b): two preprint records sharing the preprint DOI are ACCEPTED on the exact-id path"
  ) {
    val pre = Vector(id(S.Doi, preprintDoi))
    val preArxiv = pre :+ id(S.Arxiv, arxivA)
    val cases = Vector(
      "preprint DOI on both" -> (pre, pre),
      "preprint DOI and arXiv ID on both" -> (preArxiv, preArxiv),
      "preprint DOI and arXiv ID vs preprint DOI" -> (preArxiv, pre)
    )
    var checked = 0
    for {
      (profile, kv) <- preprintDoiProfiles
      (what, (x, y)) <- cases
      (a, b, orient) <- Vector((x, y, "x-y"), (y, x, "y-x"))
    } {
      val clue = s"profile=$profile $what $orient"
      val o = work(kv, a, b).decide()
      assertEquals(
        (o.outcome, o.path, o.confidence),
        (MatchOutcome.Accepted, MatchPath.ExactId, 0.99),
        clue
      )
      assertEquals(o.stage.map(_.verdict), Some(StageVerdict.SamePreprint), clue)
      assert(o.rulesFired.contains(Rules.exactAgree("doi")), s"$clue: ${o.rulesFired}")
      assert(o.reasonCodes.contains("exact-id-agreement.doi"), s"$clue: ${o.reasonCodes}")
      assert(!o.rulesFired.contains(preprintDoiRule), s"$clue: ${o.rulesFired}")
      assert(!o.reasonCodes.contains(preprintDoiReason), s"$clue: ${o.reasonCodes}")
      assertEquals(o.conflicts, Vector.empty, clue)
      val d = decision(o)
      assertEquals(MatchDecision.contract.validate(d), Vector.empty, clue)
      assertEquals((d.decision, d.matchPath), (MatchOutcome.Accepted, MatchDecisionPath.ExactId))
      checked += 1
    }
    assertEquals(checked, 8 * 3 * 2)
  }

  test(
    "preprint DOI (decisions-07 c): shared publication DOIs still decide, and the prefix list comes from the policy"
  ) {
    assertEquals(policy.policyVersion, ActivePolicyVersion)
    assertEquals(policy.workCorroboration.preprintDoiPrefixes, Vector("10.48550/"))
    val cases = Vector(
      "shared publication DOI" -> (Vector(id(S.Doi, doiA)), Vector(id(S.Doi, doiA))),
      "shared publication DOI and shared preprint DOI" -> (
        Vector(id(S.Doi, doiA), id(S.Doi, preprintDoi)),
        Vector(id(S.Doi, doiA), id(S.Doi, preprintDoi))
      ),
      "shared publication DOI, preprint DOI on one side" -> (
        Vector(id(S.Doi, doiA), id(S.Doi, preprintDoi)),
        Vector(id(S.Doi, doiA))
      )
    )
    for {
      (profile, kv) <- Vector(
        "nothing" -> nothingWork,
        "band" -> bandWork,
        "tya" -> titleYearAuthors
      )
      (what, (x, y)) <- cases
      (a, b, orient) <- Vector((x, y, "x-y"), (y, x, "y-x"))
    } {
      val clue = s"profile=$profile $what $orient"
      val o = work(kv, a, b).decide()
      assertEquals(
        (o.outcome, o.path, o.confidence),
        (MatchOutcome.Accepted, MatchPath.ExactId, 0.99),
        clue
      )
      assert(o.rulesFired.contains(Rules.exactAgree("doi")), s"$clue: ${o.rulesFired}")
      assert(!o.rulesFired.contains(preprintDoiRule), s"$clue: ${o.rulesFired}")
      assert(!o.reasonCodes.contains(preprintDoiReason), s"$clue: ${o.reasonCodes}")
      assertEquals(MatchDecision.contract.validate(decision(o)), Vector.empty, clue)
    }
    // Metamorphic: the block follows the configured prefix list, not a hardcoded arXiv prefix.
    // Under a SYNTHETIC prefix list naming 10.9999/, doiA counts as a preprint DOI; the
    // 10.99999/ DOI does not (the prefix includes its slash).
    val synthetic = policy.copy(workCorroboration =
      policy.workCorroboration.copy(preprintDoiPrefixes = Vector("10.9999/"))
    )
    val pair = work(
      nothingWork,
      Vector(id(S.Doi, doiA), id(S.Doi, "10.99999/synthetic.te.vor")),
      Vector(id(S.Doi, doiA))
    )
    assertEquals(pair.decide().outcome, MatchOutcome.Accepted, "default prefixes")
    val moved = pair.decide(synthetic)
    assertEquals((moved.outcome, moved.path), (MatchOutcome.Unresolved, MatchPath.ExactId))
    assert(moved.rulesFired.contains(preprintDoiRule), s"${moved.rulesFired}")
  }

  test(
    "preprint DOI (decisions-07 c): case and resolver-URL forms of the preprint DOI cannot bypass the prefix check"
  ) {
    val raws = Vector(
      "10.48550/ARXIV.2101.00001",
      "10.48550/arXiv.2101.00001",
      "https://doi.org/10.48550/arXiv.2101.00001",
      "HTTPS://DOI.ORG/10.48550/ARXIV.2101.00001",
      "http://dx.doi.org/10.48550/arxiv.2101.00001",
      "https://dx.doi.org/10.48550/arxiv.2101.00001",
      "https://www.doi.org/10.48550/arxiv.2101.00001",
      "doi.org/10.48550/arxiv.2101.00001",
      "DOI:10.48550/arxiv.2101.00001",
      "doi:10.48550/ARXIV.2101.00001",
      " 10.48550/arxiv.2101.00001\t"
    )
    val published = Vector(id(S.Doi, doiA), id(S.Doi, preprintDoi))
    raws.foreach { raw =>
      // 1. Normalization (the path every endpoint identifier takes) lands inside the prefix.
      assertEquals(ExactIdentifiers.normalize(S.Doi, raw), Right(preprintDoi), raw)
      ExactId.of(S.Doi, raw).foreach(e => assertEquals(e.value, preprintDoi, s"core: $raw"))
      // 2. A pair built from the normalized value is blocked like the canonical one.
      ExactIdentifiers.normalize(S.Doi, raw).foreach { v =>
        assertPreprintDoiBlocked(
          work(strongWork, Vector(id(S.Doi, v)), published).decide(),
          s"normalized $raw"
        )
      }
      // 3. Attack: the raw (non-canonical) value placed directly in both endpoint identifier sets,
      // next to a publication DOI on the published side. It must be refused (candidate build or
      // Resolver.decide) or, if decided, never ACCEPTED.
      Try(
        work(strongWork, Vector(id(S.Doi, raw)), Vector(id(S.Doi, doiA), id(S.Doi, raw)))
      ).toOption
        .foreach { built =>
          Resolver.decide(policy, built.candidate, built.ids).foreach { o =>
            assertNotEquals(
              o.outcome,
              MatchOutcome.Accepted,
              s"BYPASS: non-canonical preprint DOI '$raw' accepted (rules ${o.rulesFired})"
            )
          }
        }
    }
    // Control for step 3: the canonical value through the same construction is decided (and blocked),
    // so the attack loop is not vacuous because the construction itself always fails.
    val canonical =
      work(
        strongWork,
        Vector(id(S.Doi, preprintDoi)),
        Vector(id(S.Doi, doiA), id(S.Doi, preprintDoi))
      )
    assertPreprintDoiBlocked(canonical.decide(), "canonical control")
    // Look-alike prefixes are not the preprint prefix (and are not silently folded into it).
    for (other <- Vector("10.485500/arxiv.2101.00001", "10.4855/arxiv.2101.00001")) {
      val n = ExactIdentifiers.normalize(S.Doi, other)
      assert(n.forall(v => !v.startsWith("10.48550/")), s"$other -> $n")
    }
  }
}
