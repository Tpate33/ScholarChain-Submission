package scholarchain.entityresolution.policy

import scholarchain.core.*
import scholarchain.core.config.EntityResolutionConfig
import scholarchain.entityresolution.adjudication.Adjudication

import PolicyFixtures.*

/**
 * er-policy.v6 (ER-P5-10, human decision of 2026-11-11, decisions-08, "Block it"; RV-P5-06 decision
 * point and nit 1): when the preprint-DOI rule leaves a work pair UNRESOLVED because the stages
 * differ or are uncertain, a shared dblp-key or scopus-eid must not override that result. Scope:
 * only that case. T1 conflict handling is unchanged, and record keys still decide in every other
 * case. Every identifier here is SYNTHETIC (unregistered DOI prefixes 10.9999, invented arXiv IDs,
 * DBLP keys and Scopus EIDs; 10.48550 is arXiv's DOI prefix with an invented ID); nothing here is
 * gold data.
 */
class PolicyV6Suite extends munit.FunSuite {

  private val doi1 = ExactId(ExactIdScheme.Doi, "10.9999/example.1")
  private val doi2 = ExactId(ExactIdScheme.Doi, "10.9999/example.2")
  private val arxivDoi = ExactId(ExactIdScheme.Doi, "10.48550/arxiv.2101.00001")
  private val arx = ExactId(ExactIdScheme.Arxiv, "2101.00001")
  private val arx2 = ExactId(ExactIdScheme.Arxiv, "2203.00002")
  private val dk = ExactId(ExactIdScheme.DblpKey, "journals/example/SynA")
  private val eid = ExactId(ExactIdScheme.ScopusEid, "2-s2.0-99900000001")
  private val eid2 = ExactId(ExactIdScheme.ScopusEid, "2-s2.0-99900000002")

  /** The two record-key paths of decisions-08. */
  private val recordKeys: Vector[(String, ExactId)] = Vector("dblp-key" -> dk, "scopus-eid" -> eid)

  private val blockedReason = "record-key-agreement-blocked-by-preprint-doi-stage"

  private val exactBib: Seq[(String, Double)] = Seq(
    "title.exact" -> 1.0,
    "title.sim" -> 1.0,
    "title.token_jaccard" -> 1.0,
    "year.both" -> 1.0,
    "year.abs_diff" -> 0.0,
    "authors.both" -> 1.0,
    "authors.sequence_exact" -> 1.0
  )

  /** A score far above the accept threshold (checked in the test). */
  private val strong: Seq[(String, Double)] = Seq(
    "title.exact" -> 1.0,
    "title.sim" -> 1.0,
    "title.token_jaccard" -> 1.0,
    "authors.both" -> 1.0,
    "authors.family_jaccard" -> 1.0,
    "authors.first_family_agree" -> 1.0,
    "authors.sequence_lcs_ratio" -> 0.8
  )

  /** A score inside the LLM band without any identifier (checked in the test). */
  private val band: Seq[(String, Double)] = Seq("title.sim" -> 1.0, "title.token_jaccard" -> 1.0)

  private val sameVenue: Seq[(String, Double)] =
    Seq("venue.id_both" -> 1.0, "venue.id_agree" -> 1.0)

  /** Strong bibliographic profiles: high score, exact rule, same venue, and the band. */
  private val profiles: Vector[(String, Seq[(String, Double)])] = Vector(
    "empty" -> Seq.empty,
    "band" -> band,
    "strong" -> strong,
    "strong+venue" -> (strong ++ sameVenue),
    "exact" -> exactBib,
    "exact+venue" -> (exactBib ++ sameVenue)
  )

  private def valid(o: ResolutionOutcome): MatchDecision =
    Resolver.toMatchDecision(o, ctx).fold(e => fail(e.mkString("; ")), identity)

  private def assertRecordKeyBlocked(o: ResolutionOutcome, schemes: Vector[String], clue: String)(
      using munit.Location
  ): Unit = {
    assertEquals(
      (o.outcome, o.path, o.confidence),
      (MatchOutcome.Unresolved, MatchPath.ExactId, 0.0),
      clue
    )
    schemes.foreach { s =>
      assert(
        o.rulesFired.contains(Rules.recordKeyBlockedByPreprintDoi(s)),
        s"$clue ${o.rulesFired}"
      )
      assert(!o.rulesFired.contains(Rules.exactAgree(s)), s"$clue ${o.rulesFired}")
      assert(!o.reasonCodes.contains(s"exact-id-agreement.$s"), s"$clue ${o.reasonCodes}")
    }
    assert(o.rulesFired.contains(Rules.PreprintDoiCrossStage), s"$clue ${o.rulesFired}")
    assert(!o.rulesFired.contains(Rules.exactAgree("doi")), s"$clue ${o.rulesFired}")
    assert(o.reasonCodes.contains(blockedReason), s"$clue ${o.reasonCodes}")
    assert(o.reasonCodes.contains("preprint-doi-agreement-cross-stage"), s"$clue ${o.reasonCodes}")
    assert(o.reasonCodes.contains("publication-stage-uncertain"), s"$clue ${o.reasonCodes}")
    assertEquals(o.stage.map(_.verdict), Some(StageVerdict.Uncertain), clue)
    assertEquals(o.conflicts, Vector.empty, clue)
    // No fallback path ran: neither the bibliographic rules nor the score decided.
    assert(
      !o.rulesFired.exists(r =>
        r.startsWith(MatchPath.DeterministicBibliographic.tag + ".") ||
          r.startsWith(MatchPath.Probabilistic.tag + ".")
      ),
      s"$clue ${o.rulesFired}"
    )
    assert(!o.llmBandEligible, clue)
    assert(!Adjudication.eligible(policy, o), clue)
    val d = valid(o)
    assertEquals(MatchDecision.contract.validate(d), Vector.empty, clue)
    assertEquals((d.decision, d.matchPath), (MatchOutcome.Unresolved, MatchDecisionPath.ExactId))
    assertEquals(d.provenance.policyVersion, Some("er-policy.v8"), clue)
  }

  private def assertRecordKeyNotBlocked(o: ResolutionOutcome, clue: String)(using
      munit.Location
  ): Unit = {
    assert(!o.rulesFired.exists(_.contains(blockedReason)), s"$clue ${o.rulesFired}")
    assert(!o.reasonCodes.contains(blockedReason), s"$clue ${o.reasonCodes}")
  }

  // ------------------------------------------------------------------ versions

  test(
    "er-policy.v8 (v6 rules kept) is the default; v5 is retired history, refused by file schema and by name"
  ) {
    assertEquals(policy.policyVersion, "er-policy.v8")
    assertEquals(policy.schemaVersion, "er-policy-file.v8")
    assertEquals(MatchPolicy.DefaultResource.split('/').last, "er-policy.v8.json")
    assertEquals(policy.adjudication.promptVersion, "er-adjudication-prompt.v2")
    val v5 = MatchPolicy.resourceText(MatchPolicy.RetiredV5Resource).fold(e => fail(e), identity)
    assert(v5.contains("\"er-policy.v5\""), "the v5 history file is kept")
    assert(MatchPolicy.parse(v5).left.exists(_.exists(_.contains("retired"))))
    assert(
      MatchPolicy
        .validate(policy.copy(policyVersion = "er-policy.v5"))
        .exists(_.contains("retired"))
    )
    val json = MatchPolicy.resourceText(MatchPolicy.DefaultResource).fold(e => fail(e), identity)
    val renamed = json.replace("\"er-policy-file.v8\"", "\"er-policy-file.v5\"")
    assert(renamed != json, "replacement did not apply")
    assert(MatchPolicy.parse(renamed).left.exists(_.exists(_.contains("retired"))))
    val cfg =
      EntityResolutionConfig("er-policy.v5", 0.85, 0.2, 0.4, 0.7, "er-adjudication-prompt.v2")
    assert(MatchPolicy.withConfig(policy, cfg).isLeft, "a v5 configuration never runs")
    assert(MatchPolicy.withConfig(policy, cfg.copy(policyVersion = "er-policy.v8")).isRight)
  }

  test("the policy text (v6 rules, kept in v7) documents the record-key block") {
    val doi = policy.work.rule(ExactIdScheme.Doi).get.agreementRationale
    assert(doi.contains("dblp-key or scopus-eid does not override"), doi)
    Vector(ExactIdScheme.DblpKey, ExactIdScheme.ScopusEid).foreach { s =>
      assert(policy.work.rule(s).get.agreementRationale.contains(blockedReason), s.tag)
      assert(policy.work.rule(s).get.agreementRationale.contains("decisions-08"), s.tag)
    }
    assert(policy.publicationStage.definition.contains(blockedReason))
    assert(policy.publicationStage.rationale.contains("decisions-08"))
    assert(policy.evaluationOrder.exists(_.contains(blockedReason)))
  }

  // ------------------------------------------------------------------ the RV-P5-06 input

  test(
    "decisions-08: a shared dblp-key or scopus-eid does not override the preprint-DOI block (both orientations)"
  ) {
    val bandScore = decide(syntheticWork(band)).score.probability
    assert(policy.llmBand.contains(bandScore), bandScore.toString)
    val strongScore = decide(syntheticWork(strong)).score.probability
    assert(strongScore >= policy.thresholds.accept, strongScore.toString)
    var checked = 0
    for {
      (scheme, key) <- recordKeys
      (name, kv) <- profiles
      // A: the preprint record; B: the published record listing its own DOI and the preprint DOI.
      (preName, pre) <- Vector(
        "doi" -> Vector(key, arxivDoi),
        "doi+arxiv" -> Vector(key, arxivDoi, arx)
      )
      mixed = Vector(key, doi1, arxivDoi) ++ (if (preName == "doi+arxiv") Vector(arx) else Vector())
      (a, b, orient) <- Vector((pre, mixed, "A=preprint"), (mixed, pre, "A=mixed"))
    } {
      val clue = s"$scheme $name $preName $orient"
      val o = decide(syntheticWork(kv, a, b))
      assertRecordKeyBlocked(o, Vector(scheme), clue)
      if (preName == "doi+arxiv") assert(o.rulesFired.contains(Rules.ArxivCrossStage), clue)
      checked += 1
    }
    assertEquals(checked, 2 * 6 * 2 * 2)
  }

  test("decisions-08: two mixed records sharing a preprint DOI and a record key stay UNRESOLVED") {
    for {
      (scheme, key) <- recordKeys
      (a, b) <- Vector(
        (Vector(key, doi1, arxivDoi), Vector(key, doi2, arxivDoi)),
        (Vector(key, doi2, arxivDoi), Vector(key, doi1, arxivDoi))
      )
    } assertRecordKeyBlocked(
      decide(syntheticWork(strong ++ sameVenue, a, b)),
      Vector(scheme),
      scheme
    )
  }

  test("decisions-08: both record keys shared at once are both blocked and both recorded") {
    for {
      (a, b) <- Vector(
        (Vector(dk, eid, arxivDoi), Vector(dk, eid, doi1, arxivDoi)),
        (Vector(dk, eid, doi1, arxivDoi), Vector(dk, eid, arxivDoi))
      )
    } {
      val o = decide(syntheticWork(exactBib ++ sameVenue, a, b))
      assertRecordKeyBlocked(o, Vector("dblp-key", "scopus-eid"), s"$a / $b")
    }
  }

  // ------------------------------------------------------------------ controls (unchanged)

  test(
    "decisions-11 (replaces the v6 'no preprint DOI shared' accept control): without a shared preprint DOI the v6 trigger does not fire, and the v8 stage block decides"
  ) {
    // Under v6/v7 these inputs were ACCEPTED at 0.99 by the record key (with the `strong` profile,
    // which has no venue agreement). er-policy.v8 (decisions-11 item 2): the stage is cross-stage
    // or uncertain, so the pair stays UNRESOLVED; the full regressions are in PolicyV8Suite.
    for {
      (scheme, key) <- recordKeys
      (what, a, b) <- Vector(
        ("key only", Vector(key), Vector(key)),
        ("key and a preprint DOI on one side", Vector(key, arxivDoi), Vector(key)),
        ("key and a publication DOI on one side", Vector(key), Vector(key, doi1))
      )
      (x, y) <- Vector((a, b), (b, a))
    } {
      val clue = s"$scheme $what"
      val o = decide(syntheticWork(strong, x, y))
      assertEquals(
        (o.outcome, o.path, o.confidence),
        (MatchOutcome.Unresolved, MatchPath.ExactId, 0.0),
        clue
      )
      assert(!o.rulesFired.contains(Rules.exactAgree(scheme)), s"$clue ${o.rulesFired}")
      assert(o.rulesFired.contains(Rules.recordKeyBlockedByStage(scheme)), s"$clue ${o.rulesFired}")
      assert(!o.rulesFired.contains(Rules.PreprintDoiCrossStage), s"$clue ${o.rulesFired}")
      assertRecordKeyNotBlocked(o, clue)
      assertEquals(o.conflicts, Vector.empty, clue)
      assertEquals(valid(o).decision, MatchOutcome.Unresolved, clue)
    }
    // With an established stage (same venue, no preprint marker) the record key still accepts.
    for ((scheme, key) <- recordKeys) {
      val o = decide(syntheticWork(strong ++ sameVenue, Vector(key), Vector(key, doi1)))
      assertEquals((o.outcome, o.confidence), (MatchOutcome.Accepted, 0.99), scheme)
      assert(o.rulesFired.contains(Rules.exactAgree(scheme)), scheme)
    }
  }

  test("control: a record key next to a shared publication DOI accepts as before") {
    for {
      (scheme, key) <- recordKeys
      (a, b) <- Vector(
        (Vector(key, doi1, arxivDoi), Vector(key, doi1)),
        (Vector(key, doi1), Vector(key, doi1, arxivDoi)),
        (Vector(key, doi1, arxivDoi), Vector(key, doi1, arxivDoi))
      )
    } {
      val clue = s"$scheme $a / $b"
      val o = decide(syntheticWork(Seq.empty, a, b))
      assertEquals(
        (o.outcome, o.path, o.confidence),
        (MatchOutcome.Accepted, MatchPath.ExactId, 0.99),
        clue
      )
      assert(o.rulesFired.contains(Rules.exactAgree("doi")), clue)
      assert(o.rulesFired.contains(Rules.exactAgree(scheme)), clue)
      assert(!o.rulesFired.contains(Rules.PreprintDoiCrossStage), s"$clue ${o.rulesFired}")
      assertRecordKeyNotBlocked(o, clue)
      assertEquals(o.conflicts, Vector.empty, clue)
    }
  }

  test("control: a record key between two pure preprints sharing the preprint DOI accepts") {
    for {
      (scheme, key) <- recordKeys
      (a, b) <- Vector(
        (Vector(key, arxivDoi), Vector(key, arxivDoi)),
        (Vector(key, arxivDoi, arx), Vector(key, arxivDoi))
      )
      (x, y) <- Vector((a, b), (b, a))
    } {
      val clue = s"$scheme $x / $y"
      val o = decide(syntheticWork(strong, x, y))
      assertEquals(
        (o.outcome, o.path, o.confidence),
        (MatchOutcome.Accepted, MatchPath.ExactId, 0.99),
        clue
      )
      assertEquals(o.stage.map(_.verdict), Some(StageVerdict.SamePreprint), clue)
      assert(o.rulesFired.contains(Rules.exactAgree(scheme)), clue)
      assert(!o.rulesFired.contains(Rules.PreprintDoiCrossStage), s"$clue ${o.rulesFired}")
      assertRecordKeyNotBlocked(o, clue)
    }
  }

  test(
    "decisions-09 (replaces the ER-P5-10 characterization): an arXiv cross-stage block also holds back a record key"
  ) {
    // Under v6 this input was ACCEPTED at 0.99 by the record key (the characterization pinned it).
    // er-policy.v7 (decisions-09 item 1): the pair stays UNRESOLVED; the full regressions are in
    // PolicyV7Suite. The preprint-DOI record-key rule is not recorded (no preprint DOI is shared).
    for ((scheme, key) <- recordKeys) {
      val o = decide(syntheticWork(strong, Vector(key, arx), Vector(key, arx, doi1)))
      assertEquals(
        (o.outcome, o.path, o.confidence),
        (MatchOutcome.Unresolved, MatchPath.ExactId, 0.0),
        scheme
      )
      assert(!o.rulesFired.contains(Rules.exactAgree(scheme)), scheme)
      assert(o.rulesFired.contains(Rules.ArxivCrossStage), scheme)
      assert(o.rulesFired.contains(Rules.recordKeyBlockedByArxivStage(scheme)), scheme)
      assertRecordKeyNotBlocked(o, scheme)
    }
  }

  // ------------------------------------------------------------------ conflicts (unchanged)

  test(
    "a T1 disagreement next to a blocked preprint DOI and a shared record key is still CONFLICT"
  ) {
    for ((scheme, key) <- recordKeys) {
      // arXiv disagreement beside the shared preprint DOI and record key.
      val arxivT1 = decide(
        syntheticWork(
          strong ++ sameVenue,
          Vector(key, arxivDoi, arx),
          Vector(key, doi1, arxivDoi, arx2)
        )
      )
      assertEquals(
        (arxivT1.outcome, arxivT1.path),
        (MatchOutcome.Conflict, MatchPath.ExactId),
        scheme
      )
      assertEquals(arxivT1.confidence, policy.conflictConfidence, scheme)
      assert(
        arxivT1.conflicts.exists(c =>
          c.scheme == ExactIdScheme.Arxiv && c.kind == IdentifierConflictKind.Disagreement
        ),
        arxivT1.conflicts.toString
      )
      assert(
        arxivT1.conflicts.exists(c =>
          c.scheme.tag == scheme && c.kind == IdentifierConflictKind.ContradictedAgreement
        ),
        arxivT1.conflicts.toString
      )
      assertRecordKeyNotBlocked(arxivT1, scheme)
      // RV-P5-06 nit 1: the "unresolved" preprint-DOI rule is not recorded on a step-2 CONFLICT.
      assert(!arxivT1.rulesFired.contains(Rules.PreprintDoiCrossStage), arxivT1.rulesFired.toString)
      assertEquals(valid(arxivT1).decision, MatchOutcome.Conflict, scheme)
      // A DOI disagreement (disjoint DOI sets) beside a shared record key.
      val doiT1 = decide(syntheticWork(strong, Vector(key, arxivDoi), Vector(key, doi1)))
      assertEquals(doiT1.outcome, MatchOutcome.Conflict, scheme)
      assert(doiT1.conflicts.exists(_.scheme == ExactIdScheme.Doi), doiT1.conflicts.toString)
    }
  }

  test(
    "a record-key disagreement beside a shared record key and a blocked preprint DOI stays CONFLICT"
  ) {
    // Step 3 is unchanged: the held-back dblp-key agreement still contradicts the scopus-eid
    // disagreement. The preprint-DOI block decided this outcome (without it the DOI would accept and
    // override), so its rule is recorded; the record-key block did not, so its rule is not.
    for (
      (a, b) <- Vector(
        (Vector(dk, eid, arxivDoi), Vector(dk, eid2, doi1, arxivDoi)),
        (Vector(dk, eid2, doi1, arxivDoi), Vector(dk, eid, arxivDoi))
      )
    ) {
      val o = decide(syntheticWork(strong ++ sameVenue, a, b))
      assertEquals((o.outcome, o.path), (MatchOutcome.Conflict, MatchPath.ExactId), s"$a / $b")
      assert(o.rulesFired.contains(Rules.IdentifierContradiction), o.rulesFired.toString)
      assert(o.rulesFired.contains(Rules.PreprintDoiCrossStage), o.rulesFired.toString)
      assertRecordKeyNotBlocked(o, s"$a / $b")
      assert(o.conflicts.exists(_.scheme == ExactIdScheme.ScopusEid), o.conflicts.toString)
      assertEquals(valid(o).decision, MatchOutcome.Conflict)
    }
  }

  // ------------------------------------------------------------------ provenance (RV-P5-06 nit 1)

  test(
    "no ACCEPTED outcome lists the preprint-DOI or record-key 'unresolved' rule; ACCEPTED has no conflicts"
  ) {
    val sides: Vector[Vector[ExactId]] = Vector(
      Vector(arxivDoi),
      Vector(arxivDoi, arx),
      Vector(doi1),
      Vector(doi1, arxivDoi),
      Vector(doi2, arxivDoi),
      Vector(dk, arxivDoi),
      Vector(dk, doi1, arxivDoi),
      Vector(eid, arxivDoi),
      Vector(eid, doi1, arxivDoi),
      Vector(eid2, doi1, arxivDoi),
      Vector(dk, eid, arxivDoi)
    )
    var accepted = 0
    for {
      a <- sides
      b <- sides
      kv <- Vector(Seq.empty, strong ++ sameVenue)
    } {
      val o = decide(syntheticWork(kv, a, b))
      if (o.outcome == MatchOutcome.Accepted) {
        accepted += 1
        assert(!o.rulesFired.contains(Rules.PreprintDoiCrossStage), s"$a / $b ${o.rulesFired}")
        assert(!o.reasonCodes.contains("preprint-doi-agreement-cross-stage"), s"$a / $b")
        assert(!o.rulesFired.exists(_.contains(blockedReason)), s"$a / $b ${o.rulesFired}")
        assertEquals(o.conflicts, Vector.empty, s"$a / $b")
      }
      assertEquals(MatchDecision.contract.validate(valid(o)), Vector.empty, s"$a / $b")
    }
    assert(accepted > 0, "the sweep must contain ACCEPTED outcomes")
  }
}
