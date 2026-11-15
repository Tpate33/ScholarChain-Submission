package scholarchain.tests.er

import scholarchain.core.*
import scholarchain.core.ExactIdScheme as S
import scholarchain.core.config.EntityResolutionConfig
import scholarchain.entityresolution.adjudication.*
import scholarchain.entityresolution.identifiers.ExactIdentifiers
import scholarchain.entityresolution.policy.*

import ErFixtures.*

/**
 * TE-P5-05 (Phase 5): adversarial cross-module coverage of human decisions-11 as implemented by
 * ER-P5-12 (er-policy.v8).
 *
 *   - Item 2 (Part A): for a work pair, a shared dblp-key or scopus-eid decides only when the same
 *     publication stage is established, even when no preprint identifier is shared. The RV-P5-08
 *     inputs (a)-(d), both keys (alone and together), both orientations, strong feature profiles,
 *     controls, and T1 / step-3 conflict handling.
 *   - Item 3 (Part B, RV-P5-04 m7): arXiv agreement and disagreement both compare the base
 *     submission ID; the original versioned values stay visible; an arXiv agreement still decides
 *     only for two pure preprints; distinct submissions are a T1 disagreement.
 *
 * Every identifier, candidate and feature value is SYNTHETIC (none is a real record or a human gold
 * label). The adjudicator is a DETERMINISTIC FAKE that always answers accept and counts its calls
 * (no network, no model, no paid call). The DOI `10.1145/synthetic.te.y` is an invented value
 * standing for RV-P5-08's "doi 10.1145/y" (a publication DOI).
 */
class RecordKeyStageArxivBaseIdAdversarialSuite extends munit.FunSuite {

  /** The active policy version, pinned literally (er-policy.v8, decisions-11). */
  private val ActivePolicyVersion = "er-policy.v8"

  // Rule IDs and reason codes, pinned literally (independent of [[Rules]]).
  private val stageKeyReason = "record-key-agreement-blocked-by-publication-stage"
  private def stageKeyRule(s: String): String =
    s"exact-id.unresolved.record-key-agreement-blocked-by-publication-stage.$s"
  private val arxivKeyReason = "record-key-agreement-blocked-by-arxiv-stage"
  private def arxivKeyRule(s: String): String =
    s"exact-id.unresolved.record-key-agreement-blocked-by-arxiv-stage.$s"
  private val doiKeyReason = "record-key-agreement-blocked-by-preprint-doi-stage"
  private def doiKeyRule(s: String): String =
    s"exact-id.unresolved.record-key-agreement-blocked-by-preprint-doi-stage.$s"
  private val arxivRule = "exact-id.unresolved.arxiv-agreement-cross-stage"
  private val arxivReason = "arxiv-agreement-cross-stage"
  private val preprintDoiRule = "exact-id.unresolved.preprint-doi-agreement-cross-stage"
  private val foldReason = "arxiv-agreement-by-base-submission-id"

  private val pubDoiY = "10.1145/synthetic.te.y"

  private val venueAgree: Seq[(String, Double)] =
    Seq("venue.id_both" -> 1.0, "venue.id_agree" -> 1.0)

  private def decision(o: ResolutionOutcome): MatchDecision =
    Resolver.toMatchDecision(o, ctx).fold(e => fail(s"no valid decision: $e"), identity)

  private lazy val (template, schema) =
    AdjudicationProtocol.loadDefault().fold(e => throw new AssertionError(e), identity)

  /**
   * Offers `o` to a DETERMINISTIC FAKE always-accept adjudicator; the result and the call count.
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
        "fake-model.te-p5-05",
        template,
        schema,
        fake,
        new InMemoryAdjudicationCache
      )
      .fold(e => fail(e.mkString("; ")), identity)
    (r, calls)
  }

  private val recordKeys: Vector[(String, ExactId)] = Vector(
    "dblp-key" -> id(S.DblpKey, dblpKeyA),
    "scopus-eid" -> id(S.ScopusEid, scopusA)
  )

  /** Each record key alone, and both together. */
  private val keySets: Vector[(Vector[String], Vector[ExactId])] =
    recordKeys.map { case (s, k) => (Vector(s), Vector(k)) } :+
      (recordKeys.map(_._1), recordKeys.map(_._2))

  private val lowPolicy: MatchPolicy = MatchPolicy
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
    .fold(e => throw new AssertionError(s"config refused: $e"), identity)

  // ------------------------------------------------------------------ Part A: record-key stage gate

  /**
   * RV-P5-08 inputs (a)-(d): a shared record key, no shared preprint identifier, stage not
   * established. The function builds (side A, side B) from the key set.
   */
  private val rvInputs
      : Vector[(String, Vector[ExactId] => (Vector[ExactId], Vector[ExactId]), StageVerdict)] =
    Vector(
      (
        "(a) {key, arXiv X} vs {key, publication DOI}",
        k => (k :+ id(S.Arxiv, arxivA), k :+ id(S.Doi, doiA)),
        StageVerdict.CrossStage
      ),
      ("(b) {key, arXiv X} vs {key}", k => (k :+ id(S.Arxiv, arxivA), k), StageVerdict.CrossStage),
      (
        "(c) {key, preprint DOI} vs {key}",
        k => (k :+ id(S.Doi, preprintDoi), k),
        StageVerdict.CrossStage
      ),
      (
        "(d) {key, arxiv 2101.00001} vs {key, doi 10.48550/arxiv.2101.00001, publication doi}",
        k => (k :+ id(S.Arxiv, arxivA), k ++ Vector(id(S.Doi, preprintDoi), id(S.Doi, pubDoiY))),
        StageVerdict.Uncertain
      )
    )

  /** Strong profiles: the deterministic rule, a score above accept, and the venue agreement. */
  private val strongProfiles: Vector[(String, Seq[(String, Double)])] = Vector(
    "title-year-authors" -> titleYearAuthors,
    "strong" -> strongWork,
    "band" -> bandWork,
    "corroborating and strong (same venue)" -> (corroborating ++ strongWork)
  )

  /** decisions-11 item 2: UNRESOLVED on exact-id, documented codes, never inferred or sent. */
  private def assertStageKeyBlocked(
      o: ResolutionOutcome,
      schemes: Vector[String],
      verdict: StageVerdict,
      clue: String,
      p: MatchPolicy = policy
  )(using munit.Location): Unit = {
    assertEquals(
      (o.outcome, o.path, o.confidence),
      (MatchOutcome.Unresolved, MatchPath.ExactId, 0.0),
      clue
    )
    assertEquals(o.policyVersion, ActivePolicyVersion, clue)
    assertEquals(o.stage.map(_.verdict), Some(verdict), clue)
    schemes.foreach { s =>
      assertEquals(Rules.recordKeyBlockedByStage(s), stageKeyRule(s), clue)
      assert(o.rulesFired.contains(stageKeyRule(s)), s"$clue: ${o.rulesFired}")
      assert(!o.rulesFired.contains(Rules.exactAgree(s)), s"$clue: key decided: ${o.rulesFired}")
      assert(!o.reasonCodes.contains(s"exact-id-agreement.$s"), s"$clue: ${o.reasonCodes}")
      // No preprint identifier is shared: the v6 and v7 triggers must not be what blocked it.
      assert(!o.rulesFired.contains(arxivKeyRule(s)), s"$clue: ${o.rulesFired}")
      assert(!o.rulesFired.contains(doiKeyRule(s)), s"$clue: ${o.rulesFired}")
    }
    Vector(stageKeyReason, s"publication-stage-${verdict.tag}").foreach(r =>
      assert(o.reasonCodes.contains(r), s"$clue: missing $r in ${o.reasonCodes}")
    )
    Vector(arxivKeyReason, doiKeyReason, arxivReason).foreach(r =>
      assert(!o.reasonCodes.contains(r), s"$clue: unexpected $r in ${o.reasonCodes}")
    )
    Vector(
      arxivRule,
      preprintDoiRule,
      Rules.TitleYearAuthorsExact,
      Rules.ScoreAccept,
      Rules.ScoreReject,
      Rules.ScoreUnresolved,
      Rules.ScoreLlmBand,
      Rules.acceptBlockedByStage(MatchPath.Probabilistic),
      Rules.acceptBlockedByStage(MatchPath.DeterministicBibliographic)
    ).foreach(r => assert(!o.rulesFired.contains(r), s"$clue: rule $r fired: ${o.rulesFired}"))
    assertEquals(o.conflicts, Vector.empty, clue)
    assertEquals(o.llmBandEligible, false, s"never band-eligible: $clue")
    assertEquals(Adjudication.eligible(p, o), false, clue)
    assertEquals(offerToLlm(o, p), (o, 0), s"sent to the LLM: $clue")
    assertEquals(o.scoreGate, Some(ScoreGate(false, false, Vector.empty)), clue)
    val d = decision(o)
    assertEquals(MatchDecision.contract.validate(d), Vector.empty, clue)
    assertEquals(Json.decode[MatchDecision](Json.write(d)), Right(d), clue)
    assertEquals((d.decision, d.matchPath), (MatchOutcome.Unresolved, MatchDecisionPath.ExactId))
    assertEquals(d.provenance.policyVersion, Some(ActivePolicyVersion), clue)
    assertEquals(d.adjudication, None, clue)
  }

  /** No record-key block code on an outcome. */
  private def assertNoKeyBlock(o: ResolutionOutcome, clue: String)(using munit.Location): Unit = {
    assert(
      !o.rulesFired.exists(_.startsWith("exact-id.unresolved.record-key-agreement-blocked")),
      s"$clue: ${o.rulesFired}"
    )
    assert(
      !o.reasonCodes.exists(_.startsWith("record-key-agreement-blocked")),
      s"$clue: ${o.reasonCodes}"
    )
    assertEquals(MatchDecision.contract.validate(decision(o)), Vector.empty, clue)
  }

  test(
    "decisions-11 item 2: RV-P5-08 inputs (a)-(d) with a shared dblp-key or scopus-eid are UNRESOLVED on exact-id, both keys, both orientations, strong profiles, never sent to an always-accept LLM"
  ) {
    assertEquals(policy.policyVersion, ActivePolicyVersion)
    assertEquals(Rules.Reason.RecordKeyBlockedByStage, stageKeyReason)
    var checked = 0
    for {
      (schemes, keys) <- keySets
      (input, build, verdict) <- rvInputs
      (profile, kv) <- strongProfiles
      (orient, swap) <- Vector("as listed" -> false, "swapped" -> true)
    } {
      val (x, y) = build(keys)
      val (a, b) = if (swap) (y, x) else (x, y)
      val clue = s"keys=${schemes.mkString("+")} $input profile=$profile $orient"
      val o = work(kv, a, b).decide()
      if (profile == "strong")
        assert(o.score.probability >= policy.thresholds.accept, s"premise: $clue")
      assertStageKeyBlocked(o, schemes, verdict, clue)
      checked += 1
    }
    assertEquals(checked, 3 * 4 * 4 * 2)

    // No fallback: thresholds so low that the score would accept never reach it.
    for {
      (schemes, keys) <- keySets
      (input, build, verdict) <- rvInputs
    } {
      val (a, b) = build(keys)
      val o = work(strongWork, a, b).decide(lowPolicy)
      assert(o.score.probability >= lowPolicy.thresholds.accept, s"premise $input")
      assertStageKeyBlocked(
        o,
        schemes,
        verdict,
        s"low thresholds ${schemes.mkString("+")} $input",
        lowPolicy
      )
    }

    // A forged in-band probabilistic copy is still refused by the adjudication gate (stage).
    for ((input, build, _) <- rvInputs) {
      val (a, b) = build(Vector(recordKeys.head._2))
      val o = work(bandWork, a, b).decide()
      val forged = o.copy(path = MatchPath.Probabilistic, llmBandEligible = true)
      assertEquals(Adjudication.eligible(policy, forged), false, input)
      assertEquals(offerToLlm(forged), (forged, 0), input)
    }
  }

  test(
    "decisions-11 item 2: a shared record key with no stage marker and no venue agreement is uncertain and UNRESOLVED"
  ) {
    for {
      (schemes, keys) <- keySets
      (profile, kv) <- Vector("title-year-authors" -> titleYearAuthors, "strong" -> strongWork)
    }
      assertStageKeyBlocked(
        work(kv, keys, keys).decide(),
        schemes,
        StageVerdict.Uncertain,
        s"{key} vs {key} keys=${schemes.mkString("+")} profile=$profile"
      )
  }

  test(
    "decisions-11 item 2 controls: same venue accepts, two pure preprints accept, a shared publication DOI accepts"
  ) {
    for {
      (schemes, keys) <- keySets
      (profile, kv) <- Vector("nothing" -> nothingWork, "strong" -> strongWork)
      (orient, swap) <- Vector("as listed" -> false, "swapped" -> true)
    } {
      def sides(x: Vector[ExactId], y: Vector[ExactId]) = if (swap) (y, x) else (x, y)
      val base = s"keys=${schemes.mkString("+")} profile=$profile $orient"
      // Same venue, no preprint marker: same-stage-venue, the record key decides (6b).
      for (
        (what, (a, b)) <- Vector(
          "{key} vs {key}, same venue" -> sides(keys, keys),
          "{key} vs {key, publication DOI}, same venue" -> sides(keys, keys :+ id(S.Doi, doiA))
        )
      ) {
        val clue = s"$base $what"
        val o = work(kv ++ venueAgree, a, b).decide()
        assertEquals((o.outcome, o.path), (MatchOutcome.Accepted, MatchPath.ExactId), clue)
        assertEquals(o.stage.map(_.verdict), Some(StageVerdict.SameVenue), clue)
        schemes.foreach(s =>
          assert(o.rulesFired.contains(Rules.exactAgree(s)), s"$clue: ${o.rulesFired}")
        )
        assertEquals(o.conflicts, Vector.empty, clue)
        assertNoKeyBlock(o, clue)
      }
      // A venue agreement never overrides a preprint marker on exactly one side.
      val (va, vb) = sides(keys :+ id(S.Arxiv, arxivA), keys :+ id(S.Doi, doiA))
      assertStageKeyBlocked(
        work(kv ++ venueAgree, va, vb).decide(),
        schemes,
        StageVerdict.CrossStage,
        s"$base venue vs a preprint marker"
      )
      // Two pure preprints that share no preprint identifier: same-stage-preprint, key decides.
      val (pa, pb) = sides(keys :+ id(S.Arxiv, arxivA), keys :+ id(S.Doi, preprintDoi))
      val pre = work(kv, pa, pb).decide()
      val preClue = s"$base two pure preprints"
      assertEquals((pre.outcome, pre.path), (MatchOutcome.Accepted, MatchPath.ExactId), preClue)
      assertEquals(pre.stage.map(_.verdict), Some(StageVerdict.SamePreprint), preClue)
      schemes.foreach(s =>
        assert(pre.rulesFired.contains(Rules.exactAgree(s)), s"$preClue: ${pre.rulesFired}")
      )
      assertNoKeyBlock(pre, preClue)
      // A shared publication DOI decides (6a) even when the stage is not established.
      for (
        (what, (a, b), verdict) <- Vector(
          (
            "cross-stage {key, arXiv, DOI} vs {key, DOI}",
            sides(keys ++ Vector(id(S.Arxiv, arxivA), id(S.Doi, doiA)), keys :+ id(S.Doi, doiA)),
            StageVerdict.CrossStage
          ),
          (
            "uncertain (d) with the publication DOI on both sides",
            sides(
              keys ++ Vector(id(S.Arxiv, arxivA), id(S.Doi, pubDoiY)),
              keys ++ Vector(id(S.Doi, preprintDoi), id(S.Doi, pubDoiY))
            ),
            StageVerdict.Uncertain
          )
        )
      ) {
        val clue = s"$base $what"
        val o = work(kv, a, b).decide()
        assertEquals(o.stage.map(_.verdict), Some(verdict), clue)
        assertEquals(
          (o.outcome, o.path, o.confidence),
          (MatchOutcome.Accepted, MatchPath.ExactId, 0.99),
          clue
        )
        assert(o.rulesFired.contains(Rules.exactAgree("doi")), s"$clue: ${o.rulesFired}")
        assertEquals(o.conflicts, Vector.empty, clue)
        assertNoKeyBlock(o, clue)
      }
    }
  }

  test(
    "decisions-11 item 2 controls: T1 disagreements next to a shared key stay CONFLICT, and a record-key disagreement plus a key agreement stays a step-3 CONFLICT"
  ) {
    for {
      (schemes, keys) <- keySets
      (profile, kv) <- Vector("nothing" -> nothingWork, "strong" -> strongWork)
    } {
      val base = s"keys=${schemes.mkString("+")} profile=$profile"
      for (
        (what, a, b, t1) <- Vector(
          ("doi disagreement", keys :+ id(S.Doi, doiA), keys :+ id(S.Doi, doiB), S.Doi),
          ("arXiv disagreement", keys :+ id(S.Arxiv, arxivA), keys :+ id(S.Arxiv, arxivB), S.Arxiv),
          (
            "arXiv disagreement, preprint vs published",
            keys :+ id(S.Arxiv, arxivA),
            keys ++ Vector(id(S.Arxiv, arxivB), id(S.Doi, doiA)),
            S.Arxiv
          )
        )
      ) {
        val clue = s"$base $what"
        val o = work(kv, a, b).decide()
        assertEquals((o.outcome, o.path), (MatchOutcome.Conflict, MatchPath.ExactId), clue)
        assert(
          o.conflicts.exists(c => c.scheme == t1 && c.kind == IdentifierConflictKind.Disagreement),
          s"$clue: ${o.conflicts}"
        )
        schemes.foreach(s =>
          assert(
            o.conflicts.exists(c =>
              c.scheme.tag == s && c.kind == IdentifierConflictKind.ContradictedAgreement
            ),
            s"$clue: the shared key is a contradicted agreement: ${o.conflicts}"
          )
        )
        assertEquals(o.llmBandEligible, false, clue)
        assertEquals(offerToLlm(o), (o, 0), clue)
        assertNoKeyBlock(o, clue)
      }
    }
    // Step 3: one record key agrees and the other disagrees, on an uncertain-stage pair.
    for (
      (what, a, b, agreeing, disagreeing) <- Vector(
        (
          "dblp-key agrees, scopus-eid disagrees",
          Vector(id(S.DblpKey, dblpKeyA), id(S.ScopusEid, scopusA)),
          Vector(id(S.DblpKey, dblpKeyA), id(S.ScopusEid, scopusB)),
          S.DblpKey,
          S.ScopusEid
        ),
        (
          "scopus-eid agrees, dblp-key disagrees",
          Vector(id(S.DblpKey, dblpKeyA), id(S.ScopusEid, scopusA)),
          Vector(id(S.DblpKey, dblpKeyB), id(S.ScopusEid, scopusA)),
          S.ScopusEid,
          S.DblpKey
        )
      )
    ) {
      val o = work(strongWork, a, b).decide()
      assertEquals(o.stage.map(_.verdict), Some(StageVerdict.Uncertain), what)
      assertEquals((o.outcome, o.path), (MatchOutcome.Conflict, MatchPath.ExactId), what)
      assert(
        o.conflicts.exists(c =>
          c.scheme == disagreeing && c.kind == IdentifierConflictKind.Disagreement
        ),
        s"$what: ${o.conflicts}"
      )
      assert(
        o.conflicts.exists(c =>
          c.scheme == agreeing && c.kind == IdentifierConflictKind.ContradictedAgreement
        ),
        s"$what: ${o.conflicts}"
      )
      assertNoKeyBlock(o, what)
    }
  }

  // ------------------------------------------------------------------ Part B: arXiv base ID (m7)

  /** Independent oracle: the base submission ID (a trailing vN removed). */
  private def base(v: String): String = v.replaceFirst("v[0-9]+$", "")

  private def arxiv(vs: String*): Vector[ExactId] = vs.toVector.map(id(S.Arxiv, _))

  /** Different revisions (or a revision and the bare ID) of one submission. */
  private val revisions: Vector[(String, String)] = Vector(
    "2101.00001v1" -> "2101.00001v2",
    "2101.00001v1" -> "2101.00001",
    "2101.00001" -> "2101.00001v7",
    "2101.00001v2" -> "2101.00001v12",
    "hep-th/9901001" -> "hep-th/9901001v3",
    "hep-th/9901001v2" -> "hep-th/9901001v10"
  )

  /** Distinct submissions (new-style vs new-style, old-style vs new-style, old vs old). */
  private val distinct: Vector[(String, String)] = Vector(
    "2101.00001" -> "2101.00002",
    "2101.00001v1" -> "2101.00002v1",
    "2101.00001v2" -> "2101.00002",
    "hep-th/9901001" -> "2101.00001",
    "hep-th/9901001v2" -> "2101.00001v2",
    "hep-th/9901001" -> "hep-th/9901002"
  )

  test(
    "m7: revisions of one submission agree; two pure preprints are ACCEPTED on exact-id with the original versions kept"
  ) {
    for {
      (x, y) <- revisions
      (a, b) <- Vector((x, y), (y, x))
      (profile, kv) <- Vector("nothing" -> nothingWork, "band" -> bandWork, "strong" -> strongWork)
    } {
      val clue = s"$a vs $b profile=$profile"
      assertEquals(base(a), base(b), s"premise: $clue")
      val built = work(kv, arxiv(a), arxiv(b))
      // The candidate (er-features.v2) is unchanged: raw equality, no agreement.
      assertEquals(built.candidate.exactIdAgreements, Vector.empty, clue)
      assertEquals(built.candidate.features("id.arxiv.conflict"), 1.0, clue)
      val o = built.decide()
      assertEquals(
        (o.outcome, o.path, o.confidence),
        (MatchOutcome.Accepted, MatchPath.ExactId, 0.99),
        clue
      )
      assertEquals(o.stage.map(_.verdict), Some(StageVerdict.SamePreprint), clue)
      assert(o.rulesFired.contains(Rules.exactAgree("arxiv")), s"$clue: ${o.rulesFired}")
      assert(o.reasonCodes.contains("exact-id-agreement.arxiv"), s"$clue: ${o.reasonCodes}")
      assert(o.reasonCodes.contains(foldReason), s"$clue: ${o.reasonCodes}")
      assertEquals(o.policyAgreements.map(_.value).toSet, Set(a, b), clue)
      assert(o.policyAgreements.forall(_.scheme == S.Arxiv), clue)
      assertEquals(o.exactIdAgreements, Vector.empty, clue)
      assertEquals(o.conflicts, Vector.empty, clue)
      assertEquals(o.plausibility, None, s"no T1 disagreement: $clue")
      assertEquals(MatchDecision.contract.validate(decision(o)), Vector.empty, clue)
    }
    // A byte-identical shared revision is not a fold-only agreement.
    val same = work(nothingWork, arxiv("2101.00001v2"), arxiv("2101.00001v2")).decide()
    assertEquals(same.outcome, MatchOutcome.Accepted)
    assert(!same.reasonCodes.contains(foldReason), s"${same.reasonCodes}")
    val overlap =
      work(nothingWork, arxiv("2101.00001v1", "2101.00001v2"), arxiv("2101.00001v2")).decide()
    assertEquals(overlap.outcome, MatchOutcome.Accepted)
    assert(!overlap.reasonCodes.contains(foldReason), s"${overlap.reasonCodes}")
    assertEquals(
      overlap.policyAgreements.map(_.value).toSet,
      Set("2101.00001v1", "2101.00001v2")
    )
  }

  test(
    "m7: a revision agreement still decides only for two pure preprints (mixed side: UNRESOLVED, never sent)"
  ) {
    for {
      (x, y) <- revisions
      (pub, what) <- Vector(
        id(S.Doi, doiA) -> "publication DOI",
        id(S.Doi, preprintDoi) -> "and a publication DOI"
      )
      (orient, swap) <- Vector("A=preprint" -> false, "B=preprint" -> true)
      (profile, kv) <- Vector(
        "title-year-authors and same venue" -> (titleYearAuthors ++ venueAgree),
        "strong" -> strongWork
      )
    } {
      val mixedSide =
        if (what == "publication DOI") arxiv(y) :+ pub
        else arxiv(y) ++ Vector(pub, id(S.Doi, doiA))
      val (a, b) = if (swap) (mixedSide, arxiv(x)) else (arxiv(x), mixedSide)
      val clue = s"$x vs $y + $what $orient profile=$profile"
      val o = work(kv, a, b).decide()
      assertEquals(
        (o.outcome, o.path, o.confidence),
        (MatchOutcome.Unresolved, MatchPath.ExactId, 0.0),
        clue
      )
      assertEquals(o.stage.map(_.verdict), Some(StageVerdict.Uncertain), clue)
      assert(o.rulesFired.contains(arxivRule), s"$clue: ${o.rulesFired}")
      assert(!o.rulesFired.contains(Rules.exactAgree("arxiv")), s"$clue: ${o.rulesFired}")
      assert(o.reasonCodes.contains(arxivReason), s"$clue: ${o.reasonCodes}")
      assert(o.reasonCodes.contains(foldReason), s"$clue: ${o.reasonCodes}")
      assert(
        o.policyAgreements.filter(_.scheme == S.Arxiv).map(_.value).toSet == Set(x, y),
        s"$clue: ${o.policyAgreements}"
      )
      assertEquals(o.conflicts, Vector.empty, clue)
      assertEquals(o.llmBandEligible, false, clue)
      assertEquals(offerToLlm(o), (o, 0), clue)
      assertEquals(MatchDecision.contract.validate(decision(o)), Vector.empty, clue)
    }
  }

  test("m7: distinct submissions are a T1 disagreement (CONFLICT), never an agreement") {
    for {
      (x, y) <- distinct
      (a, b) <- Vector((x, y), (y, x))
    } {
      val clue = s"$a vs $b"
      assertNotEquals(base(a), base(b), s"premise: $clue")
      // Plausible (near-identical title): CONFLICT on the arXiv disagreement.
      val o = work(strongWork, arxiv(a), arxiv(b)).decide()
      assertEquals((o.outcome, o.path), (MatchOutcome.Conflict, MatchPath.ExactId), clue)
      val dis = o.conflicts.filter(c =>
        c.scheme == S.Arxiv && c.kind == IdentifierConflictKind.Disagreement
      )
      assertEquals(dis.size, 1, s"$clue: ${o.conflicts}")
      // The original values (versions included) are the conflict values.
      assertEquals((dis.head.valuesA ++ dis.head.valuesB).toSet, Set(a, b), clue)
      assert(!o.reasonCodes.contains(foldReason), s"$clue: ${o.reasonCodes}")
      assert(!o.rulesFired.contains(Rules.exactAgree("arxiv")), s"$clue: ${o.rulesFired}")
      assertEquals(o.policyAgreements.filter(_.scheme == S.Arxiv), Vector.empty, clue)
      assertEquals(o.llmBandEligible, false, clue)
      assertEquals(offerToLlm(o), (o, 0), clue)
      assertEquals(MatchDecision.contract.validate(decision(o)), Vector.empty, clue)
      // With a shared record key and no other evidence: still CONFLICT (the key is contradicted).
      val keyed = work(
        nothingWork,
        arxiv(a) :+ id(S.DblpKey, dblpKeyA),
        arxiv(b) :+ id(S.DblpKey, dblpKeyA)
      ).decide()
      assertEquals(keyed.outcome, MatchOutcome.Conflict, s"keyed $clue")
      // Implausible and nothing agrees: REJECTED, never ACCEPTED.
      val bare = work(nothingWork, arxiv(a), arxiv(b)).decide()
      assertEquals((bare.outcome, bare.path), (MatchOutcome.Rejected, MatchPath.ExactId), clue)
    }
  }

  test(
    "m7 sweep: arXiv agreement and disagreement are never both true; agreement equals the base-ID oracle and keeps every original value"
  ) {
    val values = Vector(
      "2101.00001",
      "2101.00001v1",
      "2101.00001v2",
      "2101.00001v12",
      "2101.00002",
      "2101.00002v1",
      "2101.10001",
      "hep-th/9901001",
      "hep-th/9901001v3",
      "hep-th/9901002",
      "math.AG/0601001v1"
    )
    values.foreach(v => assert(Identifiers.isValidArxiv(v), s"premise: $v is a canonical arXiv ID"))
    val sides: Vector[Vector[String]] =
      values.map(Vector(_)) ++ values.combinations(2).map(_.toVector).toVector
    var agreeing = 0
    var disagreeing = 0
    for { sa <- sides; sb <- sides } {
      val clue = s"${sa.mkString(",")} vs ${sb.mkString(",")}"
      val a = arxiv(sa*)
      val b = arxiv(sb*)
      val shared = sa.map(base).toSet.intersect(sb.map(base).toSet)
      val agreement = ExactIdentifiers.agreement(S.Arxiv, a, b)
      val disagreement = ExactIdentifiers.disagreement(S.Arxiv, a, b)
      assert(!(agreement.nonEmpty && disagreement.isDefined), s"both hold: $clue")
      assertEquals(agreement.nonEmpty, shared.nonEmpty, clue)
      assertEquals(disagreement.isDefined, shared.isEmpty, clue)
      assertEquals(
        agreement.map(_.value).toSet,
        (sa ++ sb).filter(v => shared.contains(base(v))).toSet,
        clue
      )
      assertEquals(
        ExactIdentifiers.agreesOnlyByComparable(S.Arxiv, a, b),
        shared.nonEmpty && !sa.exists(sb.contains),
        clue
      )
      if (shared.nonEmpty) agreeing += 1 else disagreeing += 1
    }
    assert(agreeing > 0 && disagreeing > 0, s"vacuous sweep: $agreeing / $disagreeing")
    // Through the Resolver (single values, two pure preprints, plausible pair): never both an
    // arXiv agreement and an arXiv disagreement on one outcome.
    for { x <- values; y <- values } {
      val clue = s"$x vs $y"
      val o = work(strongWork, arxiv(x), arxiv(y)).decide()
      val agreesHere = o.policyAgreements.exists(_.scheme == S.Arxiv)
      val disagreesHere = o.conflicts.exists(c =>
        c.scheme == S.Arxiv && c.kind == IdentifierConflictKind.Disagreement
      )
      assert(!(agreesHere && disagreesHere), s"both hold: $clue")
      if (base(x) == base(y)) {
        assert(agreesHere, clue)
        assertEquals(o.outcome, MatchOutcome.Accepted, clue)
        assertEquals(o.reasonCodes.contains(foldReason), x != y, s"$clue: ${o.reasonCodes}")
      } else {
        assert(disagreesHere, clue)
        assertEquals(o.outcome, MatchOutcome.Conflict, clue)
      }
    }
  }

  test(
    "m7: a folded arXiv agreement contradicted by a DOI disagreement is a CONFLICT whose contradicted-agreement values keep both versions"
  ) {
    for ((a, b) <- Vector(arxiv("2101.00001v1") -> arxiv("2101.00001v2"))) {
      for (
        (sideA, sideB, orient) <- Vector(
          (a :+ id(S.Doi, doiA), b :+ id(S.Doi, doiB), "A-B"),
          (b :+ id(S.Doi, doiB), a :+ id(S.Doi, doiA), "B-A")
        )
      ) {
        val o = work(strongWork, sideA, sideB).decide()
        assertEquals((o.outcome, o.path), (MatchOutcome.Conflict, MatchPath.ExactId), orient)
        val contradicted = o.conflicts.filter(c =>
          c.scheme == S.Arxiv && c.kind == IdentifierConflictKind.ContradictedAgreement
        )
        assertEquals(contradicted.size, 1, s"$orient: ${o.conflicts}")
        assertEquals(contradicted.head.valuesA, Vector("2101.00001v1", "2101.00001v2"), orient)
        assertEquals(contradicted.head.valuesB, Vector("2101.00001v1", "2101.00001v2"), orient)
        assert(
          o.conflicts.exists(c =>
            c.scheme == S.Doi && c.kind == IdentifierConflictKind.Disagreement
          ),
          s"$orient: ${o.conflicts}"
        )
        assert(o.reasonCodes.contains(foldReason), s"$orient: ${o.reasonCodes}")
        val d = decision(o)
        assertEquals(MatchDecision.contract.validate(d), Vector.empty, orient)
        assertEquals(Json.decode[MatchDecision](Json.write(d)), Right(d), orient)
        assert(
          d.conflicts.exists(c =>
            c.scheme == S.Arxiv && c.valuesA == Vector("2101.00001v1", "2101.00001v2")
          ),
          s"$orient: ${d.conflicts}"
        )
      }
    }
  }

  test(
    "RV-P5-08 MINOR-1: {key, arXiv v1} vs {key, arXiv v2, publication DOI} is now an arXiv agreement and UNRESOLVED, both keys, both orientations"
  ) {
    for {
      (schemes, keys) <- keySets
      (orient, swap) <- Vector("A=preprint" -> false, "B=preprint" -> true)
      (profile, kv) <- Vector(
        "nothing" -> nothingWork,
        "title-year-authors" -> titleYearAuthors,
        "strong" -> strongWork
      )
    } {
      val pre = keys :+ id(S.Arxiv, "2101.00001v1")
      val pub = keys ++ Vector(id(S.Arxiv, "2101.00001v2"), id(S.Doi, doiA))
      val (a, b) = if (swap) (pub, pre) else (pre, pub)
      val clue = s"keys=${schemes.mkString("+")} $orient profile=$profile"
      val o = work(kv, a, b).decide()
      assertEquals(
        (o.outcome, o.path, o.confidence),
        (MatchOutcome.Unresolved, MatchPath.ExactId, 0.0),
        clue
      )
      assertEquals(o.stage.map(_.verdict), Some(StageVerdict.Uncertain), clue)
      assert(o.rulesFired.contains(arxivRule), s"$clue: ${o.rulesFired}")
      schemes.foreach { s =>
        assert(o.rulesFired.contains(stageKeyRule(s)), s"$clue: ${o.rulesFired}")
        assert(o.rulesFired.contains(arxivKeyRule(s)), s"$clue: ${o.rulesFired}")
        assert(!o.rulesFired.contains(Rules.exactAgree(s)), s"$clue: ${o.rulesFired}")
      }
      assert(!o.rulesFired.contains(Rules.exactAgree("arxiv")), s"$clue: ${o.rulesFired}")
      Vector(foldReason, arxivReason, stageKeyReason, arxivKeyReason, "publication-stage-uncertain")
        .foreach(r => assert(o.reasonCodes.contains(r), s"$clue: missing $r in ${o.reasonCodes}"))
      assertEquals(
        o.policyAgreements.filter(_.scheme == S.Arxiv).map(_.value),
        Vector("2101.00001v1", "2101.00001v2"),
        clue
      )
      assertEquals(o.conflicts, Vector.empty, clue)
      assertEquals(o.llmBandEligible, false, clue)
      assertEquals(offerToLlm(o), (o, 0), clue)
      assertEquals(o.scoreGate, Some(ScoreGate(false, false, Vector.empty)), clue)
      val d = decision(o)
      assertEquals(MatchDecision.contract.validate(d), Vector.empty, clue)
      assertEquals((d.decision, d.matchPath), (MatchOutcome.Unresolved, MatchDecisionPath.ExactId))
    }
  }
}
