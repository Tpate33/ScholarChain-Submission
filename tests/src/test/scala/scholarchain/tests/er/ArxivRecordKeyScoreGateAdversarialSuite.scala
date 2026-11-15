package scholarchain.tests.er

import scala.collection.immutable.SortedMap

import upickle.default.write

import scholarchain.core.*
import scholarchain.core.ExactIdScheme as S
import scholarchain.core.config.EntityResolutionConfig
import scholarchain.entityresolution.adjudication.*
import scholarchain.entityresolution.evaluation.{GoldLabels, GoldSetEvaluation}
import scholarchain.entityresolution.policy.*
import scholarchain.entityresolution.review.{EndpointRef, ReviewQueue, ReviewQueueRow}

import ErFixtures.*

/**
 * TE-P5-04 (Phase 5): adversarial cross-module coverage of the two ER-P5-11 changes.
 *
 *   1. Human decision decisions-09 item 1 (er-policy.v7): when a shared arXiv ID leaves a work pair
 *      unresolved because the publication stages differ or are uncertain, a shared DBLP key or
 *      Scopus EID must not override that result; authoritative (T1) conflict handling is preserved.
 *      Both record keys, both orientations, several feature profiles, and controls.
 *   2. RV-P5-04 m1 (decisions-09 item 2): threshold sensitivity respects the policy's acceptance
 *      gates. A stage-blocked or identifier-blocked pair with a high score is never counted as
 *      acceptable at any threshold, and the sensitivity table is cross-checked against an
 *      independent `Resolver.decide` at each threshold pair.
 *
 * Every candidate, identifier, and label here is SYNTHETIC (none is a human gold label), and the
 * adjudicator is a DETERMINISTIC FAKE that always answers accept and counts its calls (no network,
 * no model, no paid call).
 */
class ArxivRecordKeyScoreGateAdversarialSuite extends munit.FunSuite {

  /** The active policy version, pinned literally (er-policy.v8, decisions-11). */
  private val ActivePolicyVersion = "er-policy.v8"

  /** The decisions-09 rule and reason codes, pinned literally (independent of [[Rules]]). */
  private val arxivKeyReason = "record-key-agreement-blocked-by-arxiv-stage"
  private def arxivKeyRule(scheme: String): String =
    s"exact-id.unresolved.record-key-agreement-blocked-by-arxiv-stage.$scheme"

  /** The decisions-11 item 2 (er-policy.v8) rule and reason code, pinned literally. */
  private val stageKeyReason = "record-key-agreement-blocked-by-publication-stage"
  private def stageKeyRule(scheme: String): String =
    s"exact-id.unresolved.record-key-agreement-blocked-by-publication-stage.$scheme"
  private val arxivRule = "exact-id.unresolved.arxiv-agreement-cross-stage"
  private val arxivReason = "arxiv-agreement-cross-stage"

  private val venueAgree: Seq[(String, Double)] =
    Seq("venue.id_both" -> 1.0, "venue.id_agree" -> 1.0)

  private def decision(o: ResolutionOutcome): MatchDecision =
    Resolver.toMatchDecision(o, ctx).fold(e => fail(s"no valid decision: $e"), identity)

  private lazy val (template, schema) =
    AdjudicationProtocol.loadDefault().fold(e => throw new AssertionError(e), identity)

  /**
   * Offers `o` to a DETERMINISTIC FAKE adjudicator that always answers `accept`; returns the result
   * and the number of calls.
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
        "fake-model.te-p5-04",
        template,
        schema,
        fake,
        new InMemoryAdjudicationCache
      )
      .fold(e => fail(e.mkString("; ")), identity)
    (r, calls)
  }

  /** The two record-key schemes (block-inferred-acceptance), with a SYNTHETIC shared value. */
  private val recordKeys: Vector[(String, ExactId)] = Vector(
    "dblp-key" -> id(S.DblpKey, dblpKeyA),
    "scopus-eid" -> id(S.ScopusEid, scopusA)
  )

  /** A preprint record: the key and the arXiv ID only. */
  private def preprint(keys: Vector[ExactId]): Vector[ExactId] = keys :+ id(S.Arxiv, arxivA)

  /** A mixed record: the same key and arXiv ID plus a publication DOI (10.9999, not a preprint). */
  private def mixed(keys: Vector[ExactId]): Vector[ExactId] =
    keys ++ Vector(id(S.Arxiv, arxivA), id(S.Doi, doiA))

  private val profiles: Vector[(String, Seq[(String, Double)])] =
    workProfiles ++ Vector(
      "title-year-authors and same venue" -> (titleYearAuthors ++ venueAgree),
      "corroborating" -> corroborating,
      "strong and same venue" -> (strongWork ++ venueAgree)
    )

  /** decisions-09 item 1: UNRESOLVED on exact-id, documented codes, never inferred or sent. */
  private def assertArxivKeyBlocked(
      o: ResolutionOutcome,
      schemes: Vector[String],
      clue: String
  )(using munit.Location): Unit = {
    assertEquals(
      (o.outcome, o.path, o.confidence),
      (MatchOutcome.Unresolved, MatchPath.ExactId, 0.0),
      clue
    )
    assertEquals(o.policyVersion, ActivePolicyVersion, clue)
    assertEquals(o.stage.map(_.verdict), Some(StageVerdict.Uncertain), clue)
    schemes.foreach { s =>
      assertEquals(Rules.recordKeyBlockedByArxivStage(s), arxivKeyRule(s), clue)
      assert(o.rulesFired.contains(arxivKeyRule(s)), s"$clue: ${o.rulesFired}")
      // er-policy.v8: the general stage block also holds the key back; the v7 code stays recorded.
      assert(o.rulesFired.contains(stageKeyRule(s)), s"$clue: ${o.rulesFired}")
      assert(!o.rulesFired.contains(Rules.exactAgree(s)), s"$clue: key decided: ${o.rulesFired}")
      assert(!o.reasonCodes.contains(s"exact-id-agreement.$s"), s"$clue: ${o.reasonCodes}")
    }
    assert(o.rulesFired.contains(arxivRule), s"$clue: ${o.rulesFired}")
    assert(!o.rulesFired.contains(Rules.exactAgree("arxiv")), s"$clue: ${o.rulesFired}")
    Vector(arxivKeyReason, stageKeyReason, arxivReason, "publication-stage-uncertain").foreach(r =>
      assert(o.reasonCodes.contains(r), s"$clue: missing $r in ${o.reasonCodes}")
    )
    // No preprint DOI is shared, so the decisions-08 trigger must not be what blocked the pair.
    assert(!o.rulesFired.contains(Rules.PreprintDoiCrossStage), s"$clue: ${o.rulesFired}")
    assert(
      !o.reasonCodes.contains("record-key-agreement-blocked-by-preprint-doi-stage"),
      s"$clue: ${o.reasonCodes}"
    )
    // No inferred path decided or was capped: the pair never reached steps 7 and 8.
    Vector(
      Rules.TitleYearAuthorsExact,
      Rules.ScoreAccept,
      Rules.ScoreReject,
      Rules.ScoreUnresolved,
      Rules.ScoreLlmBand,
      Rules.acceptBlockedByStage(MatchPath.Probabilistic),
      Rules.acceptBlockedByStage(MatchPath.DeterministicBibliographic)
    ).foreach(r => assert(!o.rulesFired.contains(r), s"$clue: inferred rule $r fired"))
    assertEquals(o.conflicts, Vector.empty, clue)
    assertEquals(o.llmBandEligible, false, s"never band-eligible: $clue")
    assertEquals(Adjudication.eligible(policy, o), false, clue)
    assertEquals(offerToLlm(o), (o, 0), s"sent to the LLM: $clue")
    // The score path is never reached, at any threshold (m1 gate).
    assertEquals(o.scoreGate, Some(ScoreGate(false, false, Vector.empty)), clue)
    val d = decision(o)
    assertEquals(MatchDecision.contract.validate(d), Vector.empty, clue)
    assertEquals(Json.decode[MatchDecision](Json.write(d)), Right(d), clue)
    assertEquals((d.decision, d.matchPath), (MatchOutcome.Unresolved, MatchDecisionPath.ExactId))
    assertEquals(d.provenance.policyVersion, Some(ActivePolicyVersion), clue)
    assertEquals(d.adjudication, None, clue)
  }

  // ------------------------------------------------------------------ decisions-09 item 1

  test(
    "decisions-09 item 1: a shared dblp-key or scopus-eid never overrides the arXiv cross-stage block, in either orientation, on any profile"
  ) {
    assertEquals(policy.policyVersion, ActivePolicyVersion)
    assertEquals(Rules.Reason.RecordKeyBlockedByArxivStage, arxivKeyReason)
    assertEquals(Rules.ArxivCrossStage, arxivRule)
    var checked = 0
    for {
      (scheme, key) <- recordKeys
      (profile, kv) <- profiles
      (a, b, orient) <- Vector(
        (preprint(Vector(key)), mixed(Vector(key)), "A=preprint"),
        (mixed(Vector(key)), preprint(Vector(key)), "B=preprint")
      )
    } {
      val clue = s"key=$scheme profile=$profile $orient"
      val o = work(kv, a, b).decide()
      if (profile == "strong" || profile == "strong and same venue")
        assert(o.score.probability >= policy.thresholds.accept, s"premise: $clue")
      assertArxivKeyBlocked(o, Vector(scheme), clue)
      checked += 1
    }
    assertEquals(checked, 2 * 7 * 2)

    // Both record keys shared at once: both are held back and both rules are recorded.
    val both = recordKeys.map(_._2)
    for (
      (a, b, orient) <- Vector(
        (preprint(both), mixed(both), "A"),
        (mixed(both), preprint(both), "B")
      )
    )
      assertArxivKeyBlocked(
        work(strongWork, a, b).decide(),
        Vector("dblp-key", "scopus-eid"),
        s"both keys $orient"
      )

    // No fallback: thresholds configured so low that the score would accept never reach it.
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
    for (
      (scheme, key) <- recordKeys;
      (profile, kv) <- Vector("strong" -> strongWork, "band" -> bandWork)
    ) {
      val o = work(kv, preprint(Vector(key)), mixed(Vector(key))).decide(low)
      assert(o.score.probability >= low.thresholds.accept, s"premise $scheme $profile")
      assertEquals(
        (o.outcome, o.path),
        (MatchOutcome.Unresolved, MatchPath.ExactId),
        s"$scheme $profile"
      )
      assert(o.rulesFired.contains(arxivKeyRule(scheme)), s"$scheme $profile: ${o.rulesFired}")
      assertEquals(offerToLlm(o, low), (o, 0), s"$scheme $profile")
    }

    // A forged in-band probabilistic copy is still refused by the adjudication gate (stage).
    for ((scheme, key) <- recordKeys) {
      val band = work(bandWork, preprint(Vector(key)), mixed(Vector(key))).decide()
      val forged = band.copy(path = MatchPath.Probabilistic, llmBandEligible = true)
      assertEquals(Adjudication.eligible(policy, forged), false, scheme)
      assertEquals(offerToLlm(forged), (forged, 0), scheme)
    }
  }

  test(
    "decisions-09 item 1 controls: a shared publication DOI still decides, two pure preprints still accept, T1 disagreements stay CONFLICT"
  ) {
    def noKeyBlock(o: ResolutionOutcome, clue: String)(using munit.Location): Unit = {
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
    val kvs = Vector("nothing" -> nothingWork, "band" -> bandWork, "strong" -> strongWork)
    for { (scheme, key) <- recordKeys; (profile, kv) <- kvs } {
      val k = Vector(key)
      // (a) A shared publication DOI decides, also next to a shared arXiv ID and record key.
      val published = k :+ id(S.Doi, doiA)
      for (
        (what, (a, b)) <- Vector(
          "mixed vs mixed" -> (mixed(k), mixed(k)),
          "published vs mixed" -> (published, mixed(k)),
          "mixed vs published" -> (mixed(k), published)
        )
      ) {
        val clue = s"key=$scheme profile=$profile shared publication DOI, $what"
        val o = work(kv, a, b).decide()
        assertEquals(
          (o.outcome, o.path, o.confidence),
          (MatchOutcome.Accepted, MatchPath.ExactId, 0.99),
          clue
        )
        assert(o.rulesFired.contains(Rules.exactAgree("doi")), s"$clue: ${o.rulesFired}")
        assertEquals(o.conflicts, Vector.empty, clue)
        noKeyBlock(o, clue)
      }
      // (b) Two pure preprints sharing the key and the arXiv ID are ACCEPTED on the arXiv ID.
      val pre = work(kv, preprint(k), preprint(k)).decide()
      val preClue = s"key=$scheme profile=$profile two pure preprints"
      assertEquals(
        (pre.outcome, pre.path, pre.confidence),
        (MatchOutcome.Accepted, MatchPath.ExactId, 0.99),
        preClue
      )
      assertEquals(pre.stage.map(_.verdict), Some(StageVerdict.SamePreprint), preClue)
      assert(pre.rulesFired.contains(Rules.exactAgree("arxiv")), s"$preClue: ${pre.rulesFired}")
      assert(!pre.rulesFired.contains(arxivRule), s"$preClue: ${pre.rulesFired}")
      noKeyBlock(pre, preClue)
      // (c) T1 disagreements next to the shared key stay CONFLICT (step 2 unchanged).
      val doiConflict =
        work(kv, mixed(k), k ++ Vector(id(S.Arxiv, arxivA), id(S.Doi, doiB))).decide()
      val arxivConflict =
        work(kv, preprint(k), k ++ Vector(id(S.Arxiv, arxivB), id(S.Doi, doiA))).decide()
      for (
        (what, o, t1) <- Vector(
          ("doi disagreement next to a shared arXiv ID", doiConflict, S.Doi),
          ("arXiv disagreement, preprint vs published", arxivConflict, S.Arxiv)
        )
      ) {
        val clue = s"key=$scheme profile=$profile $what"
        assertEquals((o.outcome, o.path), (MatchOutcome.Conflict, MatchPath.ExactId), clue)
        assert(
          o.conflicts.exists(c => c.scheme == t1 && c.kind == IdentifierConflictKind.Disagreement),
          s"$clue: ${o.conflicts}"
        )
        assert(
          o.conflicts.exists(c =>
            c.scheme.tag == scheme && c.kind == IdentifierConflictKind.ContradictedAgreement
          ),
          s"$clue: the shared key is a contradicted agreement: ${o.conflicts}"
        )
        assertEquals(o.llmBandEligible, false, clue)
        assertEquals(offerToLlm(o), (o, 0), clue)
        noKeyBlock(o, clue)
      }
    }
  }

  test(
    "decisions-11 item 2 (was the ER-P5-11 CHARACTERIZATION): a record key no longer accepts a cross-stage pair that shares no preprint identifier"
  ) {
    // SYNTHETIC: A = {key, arXiv ID}, B = {key, publication DOI}. No arXiv ID or preprint DOI is
    // shared, so neither the v6 nor the v7 trigger applies. Under er-policy.v7 the record-key
    // agreement decided (6b, ACCEPTED); the human decided (decisions-11 item 2) that it must not.
    // Both keys, together and alone, in both orientations.
    val pubSide: Vector[ExactId] => Vector[ExactId] = _ :+ id(S.Doi, doiA)
    val keySets = recordKeys.map { case (s, k) => (Vector(s), Vector(k)) } :+
      (recordKeys.map(_._1), recordKeys.map(_._2))
    for {
      (schemes, keys) <- keySets
      (a, b, orient) <- Vector(
        (preprint(keys), pubSide(keys), "A=preprint"),
        (pubSide(keys), preprint(keys), "B=preprint")
      )
    } {
      val clue = s"keys=${schemes.mkString("+")} $orient"
      val o = work(nothingWork, a, b).decide()
      assertEquals(o.policyVersion, ActivePolicyVersion, clue)
      assertEquals(o.stage.map(_.verdict), Some(StageVerdict.CrossStage), clue)
      assertEquals(
        (o.outcome, o.path, o.confidence),
        (MatchOutcome.Unresolved, MatchPath.ExactId, 0.0),
        clue
      )
      schemes.foreach { s =>
        assert(o.rulesFired.contains(stageKeyRule(s)), s"$clue: ${o.rulesFired}")
        assert(!o.rulesFired.contains(Rules.exactAgree(s)), s"$clue: ${o.rulesFired}")
        assert(!o.rulesFired.contains(arxivKeyRule(s)), s"$clue: v7 trigger: ${o.rulesFired}")
      }
      Vector(stageKeyReason, "publication-stage-cross-stage").foreach(r =>
        assert(o.reasonCodes.contains(r), s"$clue: missing $r in ${o.reasonCodes}")
      )
      assert(!o.reasonCodes.contains(arxivKeyReason), s"$clue: ${o.reasonCodes}")
      assertEquals(o.conflicts, Vector.empty, clue)
      assertEquals(o.llmBandEligible, false, clue)
      assertEquals(offerToLlm(o), (o, 0), clue)
      val d = decision(o)
      assertEquals(MatchDecision.contract.validate(d), Vector.empty, clue)
      assertEquals((d.decision, d.matchPath), (MatchOutcome.Unresolved, MatchDecisionPath.ExactId))
    }
  }

  // ------------------------------------------------------------------ RV-P5-04 m1 score gate

  private def endpointRef(endpointId: String, dataset: String, recordId: String): EndpointRef =
    EndpointRef(
      endpointId,
      "work",
      dataset,
      recordId,
      "synthetic-snapshot",
      s"$dataset-raw/1.0.0",
      candidateRunId,
      Some(endpointId),
      None,
      Vector(s"title: synthetic $endpointId")
    )

  private val refs: Map[String, EndpointRef] = Map(
    workA -> endpointRef(workA, "dblp", "journals/synthetic/TeWorkA21"),
    workB -> endpointRef(workB, "crossref", "10.9999/synthetic.te.endpoint")
  )

  /** The generated queue row of `o`; the score gate must survive the queue file unchanged. */
  private def queueRow(o: ResolutionOutcome, clue: String)(using munit.Location): ReviewQueueRow = {
    val r = ReviewQueue.row(policy, o, refs).fold(e => fail(s"$clue: $e"), identity)
    assertEquals(r.system.scoreGate, o.scoreGate, clue)
    val back = GoldLabels.parseQueue(Vector(write(r))).fold(e => fail(e.mkString("; ")), identity)
    assertEquals(back, Vector(r), s"$clue: the row does not round-trip")
    r
  }

  /** The default policy with SYNTHETIC thresholds `(accept, reject)` applied through the config. */
  private def configuredAt(accept: Double, reject: Double): MatchPolicy =
    MatchPolicy
      .withConfig(
        policy,
        EntityResolutionConfig(
          policy.policyVersion,
          accept,
          reject,
          reject + (accept - reject) / 3,
          reject + 2 * (accept - reject) / 3,
          policy.adjudication.promptVersion
        )
      )
      .fold(e => fail(s"thresholds ($accept, $reject) refused: ${e.mkString("; ")}"), identity)

  test(
    "m1: a stage-blocked or identifier-blocked high-score pair is never counted as acceptable at any threshold; the table matches Resolver.decide at every grid point"
  ) {
    val P = MatchPath.Probabilistic
    val stageRule = "probabilistic.accept-blocked-by-publication-stage"
    val idRule = "probabilistic.accept-blocked-by-id-disagreement"
    assertEquals(Rules.acceptBlockedByStage(P), stageRule)
    assertEquals(Rules.acceptBlocked(P), idRule)

    // SYNTHETIC: arXiv preprint vs published DOI with a high score (cross-stage; Option A non-match).
    val stageB = work(strongWork, Vector(id(S.Arxiv, arxivA)), Vector(id(S.Doi, doiA)))
    // Same venue (stage established) but a dblp-key disagreement: identifier-blocked.
    val idB = work(
      strongWork ++ venueAgree,
      Vector(id(S.DblpKey, dblpKeyA)),
      Vector(id(S.DblpKey, dblpKeyB))
    )
    // Positive control: the same high score with nothing capping it.
    val controlB = work(strongWork ++ venueAgree)
    // The decisions-09 exact-id block: never on the score path at all.
    val exactB = work(
      strongWork,
      preprint(Vector(id(S.DblpKey, dblpKeyA))),
      mixed(Vector(id(S.DblpKey, dblpKeyA)))
    )

    val stage0 = stageB.decide()
    val id0 = idB.decide()
    val control0 = controlB.decide()
    val exact0 = exactB.decide()
    // Premises: high scores, and the outcomes the default thresholds give.
    Vector("stage" -> stage0, "id" -> id0, "control" -> control0).foreach { case (n, o) =>
      assert(o.score.probability >= policy.thresholds.accept, s"premise $n: ${o.score.probability}")
    }
    assertEquals((stage0.outcome, stage0.path), (MatchOutcome.Unresolved, P))
    assert(stage0.rulesFired.contains(stageRule), s"${stage0.rulesFired}")
    assertEquals(stage0.stage.map(_.verdict), Some(StageVerdict.CrossStage))
    assertEquals((id0.outcome, id0.path), (MatchOutcome.Unresolved, P))
    assert(id0.rulesFired.contains(idRule), s"${id0.rulesFired}")
    assertEquals((control0.outcome, control0.path), (MatchOutcome.Accepted, P))
    assertEquals((exact0.outcome, exact0.path), (MatchOutcome.Unresolved, MatchPath.ExactId))
    // The recorded gates.
    assertEquals(stage0.scoreGate, Some(ScoreGate(false, true, Vector(stageRule))))
    assertEquals(id0.scoreGate, Some(ScoreGate(false, true, Vector(idRule))))
    assertEquals(control0.scoreGate, Some(ScoreGate(true, true, Vector.empty)))
    assertEquals(exact0.scoreGate, Some(ScoreGate(false, false, Vector.empty)))

    // SYNTHETIC labels (not human labels): the blocked pairs are labeled so that counting either
    // one as accepted would show up as a false positive (stage) or an extra true positive (id).
    val labeled: Vector[(Built, ResolutionOutcome, String)] = Vector(
      (controlB, control0, "match"),
      (idB, id0, "match"),
      (stageB, stage0, "non-match"),
      (exactB, exact0, "non-match")
    )
    val pairs = labeled.map { case (_, o, l) => queueRow(o, l) -> l }
    // The default grid plus points far below and above the scores (accept 0.3 is below every score).
    val grid =
      (GoldSetEvaluation.DefaultGrid ++ Vector((0.3, 0.1), (0.5, 0.2), (0.99, 0.95))).distinct
    val points = GoldSetEvaluation.sensitivity(pairs, grid).filter(_.entityKind == "work")
    assertEquals(points.map(p => (p.accept, p.reject)), grid)

    points.foreach { pt =>
      val (a, r) = (pt.accept, pt.reject)
      val clue = s"accept=$a reject=$r"
      val q = configuredAt(a, r)
      val decided = labeled.map { case (b, o0, l) => (b.decide(q), o0, l) }
      def onP(o: ResolutionOutcome) = o.path == P
      def count(f: (ResolutionOutcome, String) => Boolean) =
        decided.count { case (o, _, l) => f(o, l) }
      // Independent cross-check: the counts are what the Resolver does at these thresholds.
      val expected = (
        count((o, l) => onP(o) && o.outcome == MatchOutcome.Accepted && l == "match"),
        count((o, l) => onP(o) && o.outcome == MatchOutcome.Accepted && l == "non-match"),
        count((o, l) => onP(o) && o.outcome == MatchOutcome.Rejected && l == "non-match"),
        count((o, l) => onP(o) && o.outcome == MatchOutcome.Rejected && l == "match")
      )
      assertEquals((pt.tp, pt.fp, pt.tn, pt.fn), expected, clue)
      // The gate predicts the Resolver for every candidate (the score ignores thresholds).
      decided.foreach { case (o, o0, l) =>
        val g = o0.scoreGate.getOrElse(fail(s"$clue: no gate"))
        val s = o0.score.probability
        assertEquals(o.score.probability, s, s"$clue $l")
        assertEquals(
          onP(o) && o.outcome == MatchOutcome.Accepted,
          g.acceptReachable && s >= a,
          s"$clue $l accept"
        )
        assertEquals(
          onP(o) && o.outcome == MatchOutcome.Rejected,
          g.rejectReachable && s <= r,
          s"$clue $l reject"
        )
      }
      // The blocked pairs are never ACCEPTED, through any path, at any threshold.
      Vector("stage" -> stageB, "id" -> idB, "exact" -> exactB).foreach { case (n, b) =>
        assertNotEquals(b.decide(q).outcome, MatchOutcome.Accepted, s"$clue: $n accepted")
      }
      assertEquals(pt.fp, 0, s"$clue: the stage-blocked non-match was counted as accepted")
      assertEquals(
        pt.tp,
        if (control0.score.probability >= a) 1 else 0,
        s"$clue: only the unblocked control may be a TP"
      )
      val blockedExpected = Vector(stage0, id0).count(_.score.probability >= a)
      assertEquals(pt.blockedAccept, blockedExpected, clue)
      assertEquals(pt.blockedAcceptByRule.values.sum, blockedExpected, clue)
      assertEquals(pt.blockedReject, 0, clue)
      assertEquals(pt.gateUnknown, 0, clue)
      // The exact-id row is not in the score table at all.
      assertEquals(
        pt.tp + pt.fp + pt.tn + pt.fn + pt.undecided + pt.blockedAccept + pt.blockedReject,
        3,
        clue
      )
    }
    // Not vacuous: at accept 0.3 both blocked pairs are above the threshold and reported as blocked.
    val lowPoint = points.find(p => p.accept == 0.3 && p.reject == 0.1).getOrElse(fail("no point"))
    assertEquals((lowPoint.tp, lowPoint.fp, lowPoint.blockedAccept), (1, 0, 2))
    assertEquals(lowPoint.blockedAcceptByRule, SortedMap(idRule -> 1, stageRule -> 1))
  }
}
