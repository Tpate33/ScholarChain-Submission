package scholarchain.entityresolution.policy

import scholarchain.core.*
import scholarchain.core.config.EntityResolutionConfig
import scholarchain.entityresolution.adjudication.Adjudication
import scholarchain.entityresolution.identifiers.ExactIdentifiers

import PolicyFixtures.*

/**
 * er-policy.v8 (ER-P5-12, human decision of 2026-11-12, decisions-11):
 *   - item 2: a shared dblp-key or scopus-eid must not automatically accept a work pair whose
 *     publication stages differ or are uncertain, even when no preprint identifier is shared;
 *     authoritative conflict handling is preserved (RV-P5-08 section 1 inputs (a)-(d));
 *   - item 3 (RV-P5-04 m7, RV-P5-08 MINOR-1): arXiv identifiers are compared by base submission ID
 *     for agreement and disagreement, the original IDs are preserved, and agreement still requires
 *     the same stage.
 *
 * Every identifier here is SYNTHETIC (unregistered DOI prefix 10.9999, invented arXiv IDs, DBLP
 * keys and Scopus EIDs; 10.48550 is arXiv's DOI prefix with an invented ID); nothing here is gold
 * data.
 */
class PolicyV8Suite extends munit.FunSuite {

  private val doi1 = ExactId(ExactIdScheme.Doi, "10.9999/example.1")
  private val doi2 = ExactId(ExactIdScheme.Doi, "10.9999/example.2")
  private val arxivDoi = ExactId(ExactIdScheme.Doi, "10.48550/arxiv.2101.00001")
  private val arx = ExactId(ExactIdScheme.Arxiv, "2101.00001")
  private val arxV1 = ExactId(ExactIdScheme.Arxiv, "2101.00001v1")
  private val arxV2 = ExactId(ExactIdScheme.Arxiv, "2101.00001v2")
  private val arxOther = ExactId(ExactIdScheme.Arxiv, "2101.00002")
  private val arxOld = ExactId(ExactIdScheme.Arxiv, "hep-th/9901001")
  private val arxOldV3 = ExactId(ExactIdScheme.Arxiv, "hep-th/9901001v3")
  private val dk = ExactId(ExactIdScheme.DblpKey, "journals/example/SynA")
  private val eid = ExactId(ExactIdScheme.ScopusEid, "2-s2.0-99900000001")
  private val eid2 = ExactId(ExactIdScheme.ScopusEid, "2-s2.0-99900000002")

  private val recordKeys: Vector[(String, ExactId)] = Vector("dblp-key" -> dk, "scopus-eid" -> eid)

  /** The decisions-11 reason code, pinned literally (independent of [[Rules]]). */
  private val stageBlockedReason = "record-key-agreement-blocked-by-publication-stage"
  private val arxivTriggerReason = "record-key-agreement-blocked-by-arxiv-stage"
  private val doiTriggerReason = "record-key-agreement-blocked-by-preprint-doi-stage"
  private val foldedReason = "arxiv-agreement-by-base-submission-id"

  private val exactBib: Seq[(String, Double)] = Seq(
    "title.exact" -> 1.0,
    "title.sim" -> 1.0,
    "title.token_jaccard" -> 1.0,
    "year.both" -> 1.0,
    "year.abs_diff" -> 0.0,
    "authors.both" -> 1.0,
    "authors.sequence_exact" -> 1.0
  )

  private val strong: Seq[(String, Double)] = Seq(
    "title.exact" -> 1.0,
    "title.sim" -> 1.0,
    "title.token_jaccard" -> 1.0,
    "authors.both" -> 1.0,
    "authors.family_jaccard" -> 1.0,
    "authors.first_family_agree" -> 1.0,
    "authors.sequence_lcs_ratio" -> 0.8
  )

  private val band: Seq[(String, Double)] = Seq("title.sim" -> 1.0, "title.token_jaccard" -> 1.0)

  private val sameVenue: Seq[(String, Double)] =
    Seq("venue.id_both" -> 1.0, "venue.id_agree" -> 1.0)

  private val sameTitle: Seq[(String, Double)] =
    Seq("title.exact" -> 1.0, "title.sim" -> 1.0, "title.token_jaccard" -> 1.0)

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

  private def assertStageBlocked(
      o: ResolutionOutcome,
      schemes: Vector[String],
      clue: String
  )(using munit.Location): Unit = {
    assertEquals(
      (o.outcome, o.path, o.confidence),
      (MatchOutcome.Unresolved, MatchPath.ExactId, 0.0),
      clue
    )
    schemes.foreach { s =>
      assertEquals(
        Rules.recordKeyBlockedByStage(s),
        s"exact-id.unresolved.record-key-agreement-blocked-by-publication-stage.$s"
      )
      assert(o.rulesFired.contains(Rules.recordKeyBlockedByStage(s)), s"$clue ${o.rulesFired}")
      assert(!o.rulesFired.contains(Rules.exactAgree(s)), s"$clue ${o.rulesFired}")
      assert(!o.reasonCodes.contains(s"exact-id-agreement.$s"), s"$clue ${o.reasonCodes}")
    }
    assert(o.reasonCodes.contains(stageBlockedReason), s"$clue ${o.reasonCodes}")
    val verdict = o.stage.map(_.verdict).getOrElse(fail(s"no stage: $clue"))
    assert(!verdict.established, s"$clue $verdict")
    assert(o.reasonCodes.contains(s"publication-stage-${verdict.tag}"), s"$clue ${o.reasonCodes}")
    assertEquals(o.conflicts, Vector.empty, clue)
    assert(
      !o.rulesFired.exists(r =>
        r.startsWith(MatchPath.DeterministicBibliographic.tag + ".") ||
          r.startsWith(MatchPath.Probabilistic.tag + ".")
      ),
      s"$clue ${o.rulesFired}"
    )
    assert(!o.llmBandEligible, clue)
    assert(!Adjudication.eligible(policy, o), clue)
    assertEquals(o.scoreGate.map(_.acceptReachable), Some(false), clue)
    val d = valid(o)
    assertEquals(MatchDecision.contract.validate(d), Vector.empty, clue)
    assertEquals((d.decision, d.matchPath), (MatchOutcome.Unresolved, MatchDecisionPath.ExactId))
    assertEquals(d.provenance.policyVersion, Some("er-policy.v8"), clue)
  }

  private def assertAccepted(o: ResolutionOutcome, clue: String)(using munit.Location): Unit = {
    assertEquals(
      (o.outcome, o.path, o.confidence),
      (MatchOutcome.Accepted, MatchPath.ExactId, 0.99),
      clue
    )
    assertEquals(o.conflicts, Vector.empty, clue)
    assert(
      !o.rulesFired.exists(_.contains("record-key-agreement-blocked")),
      s"$clue ${o.rulesFired}"
    )
    assert(!o.reasonCodes.exists(_.startsWith("record-key-agreement-blocked")), clue)
    assertEquals(valid(o).decision, MatchOutcome.Accepted, clue)
  }

  // ------------------------------------------------------------------ versions

  test(
    "er-policy.v8 is the default; v7 is retired history, refused by file schema, name and config"
  ) {
    assertEquals(policy.policyVersion, "er-policy.v8")
    assertEquals(policy.schemaVersion, "er-policy-file.v8")
    assertEquals(MatchPolicy.FileSchemaVersion, "er-policy-file.v8")
    assertEquals(MatchPolicy.DefaultResource.split('/').last, "er-policy.v8.json")
    assertEquals(policy.adjudication.promptVersion, "er-adjudication-prompt.v2")
    assertEquals((policy.thresholds, policy.llmBand), (Thresholds(0.85, 0.2), LlmBand(0.4, 0.7)))
    // Feature values are unchanged (the base-ID comparison is a policy rule), so er-features.v2.
    assertEquals(
      (policy.featureVersion, policy.blockingVersion),
      ("er-features.v2", "er-blocking.v2")
    )
    val v7 = MatchPolicy.resourceText(MatchPolicy.RetiredV7Resource).fold(e => fail(e), identity)
    assert(v7.contains("\"er-policy.v7\""), "the v7 history file is kept")
    assert(MatchPolicy.parse(v7).left.exists(_.exists(_.contains("retired"))))
    assert(
      MatchPolicy
        .validate(policy.copy(policyVersion = "er-policy.v7"))
        .exists(_.contains("retired"))
    )
    val json = MatchPolicy.resourceText(MatchPolicy.DefaultResource).fold(e => fail(e), identity)
    val renamed = json.replace("\"er-policy-file.v8\"", "\"er-policy-file.v7\"")
    assert(renamed != json, "replacement did not apply")
    assert(MatchPolicy.parse(renamed).left.exists(_.exists(_.contains("retired"))))
    val cfg =
      EntityResolutionConfig("er-policy.v7", 0.85, 0.2, 0.4, 0.7, "er-adjudication-prompt.v2")
    assert(MatchPolicy.withConfig(policy, cfg).isLeft, "a v7 configuration never runs")
    assert(MatchPolicy.withConfig(policy, cfg.copy(policyVersion = "er-policy.v8")).isRight)
  }

  test("the v8 policy text documents the general record-key stage block and the arXiv base ID") {
    Vector(ExactIdScheme.DblpKey, ExactIdScheme.ScopusEid).foreach { s =>
      val r = policy.work.rule(s).get.agreementRationale
      assert(r.contains(stageBlockedReason), s.tag)
      assert(r.contains("decisions-11"), s.tag)
    }
    assert(policy.publicationStage.definition.contains(stageBlockedReason))
    assert(policy.publicationStage.rationale.contains("decisions-11"))
    assert(policy.evaluationOrder.exists(_.contains(stageBlockedReason)))
    assert(policy.evaluationOrder.exists(_.contains(Rules.recordKeyBlockedByStage("<scheme>"))))
    val arxiv = policy.work.rule(ExactIdScheme.Arxiv).get
    // RV-P5-04 m7: the statement is kept and is now true.
    assert(arxiv.scope.contains("versions vN are compared as one value"))
    assert(arxiv.scope.contains("base submission ID"))
    assert(arxiv.agreementRationale.contains(foldedReason))
    assert(arxiv.agreementRationale.contains("decisions-11"))
    assert(policy.evaluationOrder.exists(_.contains(foldedReason)))
  }

  // ------------------------------------------------------------------ item 2: inputs (a)-(d)

  test(
    "decisions-11 item 2: RV-P5-08 inputs (a)-(d), both record keys, both orientations, strong profiles: UNRESOLVED"
  ) {
    val strongScore = decide(syntheticWork(strong)).score.probability
    assert(strongScore >= policy.thresholds.accept, strongScore.toString)
    assert(policy.llmBand.contains(decide(syntheticWork(band)).score.probability))
    var checked = 0
    for {
      (scheme, key) <- recordKeys
      (name, kv) <- profiles
      (input, a, b, verdict) <- Vector(
        (
          "(a) arXiv vs publication DOI",
          Vector(key, arx),
          Vector(key, doi1),
          StageVerdict.CrossStage
        ),
        ("(b) arXiv vs nothing", Vector(key, arx), Vector(key), StageVerdict.CrossStage),
        (
          "(c) preprint DOI vs nothing",
          Vector(key, arxivDoi),
          Vector(key),
          StageVerdict.CrossStage
        ),
        (
          "(d) arXiv vs its DOI form and a publication DOI",
          Vector(key, arx),
          Vector(key, arxivDoi, doi1),
          StageVerdict.Uncertain
        )
      )
      (x, y, orient) <- Vector((a, b, "A first"), (b, a, "B first"))
    } {
      val clue = s"$scheme $name $input $orient"
      val o = decide(syntheticWork(kv, x, y))
      assertStageBlocked(o, Vector(scheme), clue)
      assertEquals(o.stage.map(_.verdict), Some(verdict), clue)
      // No preprint identifier is shared, so neither v6/v7 trigger applies.
      assert(!o.reasonCodes.contains(arxivTriggerReason), s"$clue ${o.reasonCodes}")
      assert(!o.reasonCodes.contains(doiTriggerReason), s"$clue ${o.reasonCodes}")
      checked += 1
    }
    assertEquals(checked, 2 * 6 * 4 * 2)
  }

  test("decisions-11 item 2: a record key alone with an uncertain stage (no venue) is UNRESOLVED") {
    for {
      (scheme, key) <- recordKeys
      kv <- Vector(Seq.empty, strong, exactBib)
    } {
      val o = decide(syntheticWork(kv, Vector(key), Vector(key)))
      assertStageBlocked(o, Vector(scheme), s"$scheme $kv")
      assertEquals(o.stage.map(_.verdict), Some(StageVerdict.Uncertain))
    }
  }

  test("decisions-11 item 2: both record keys shared at once are both blocked and both recorded") {
    for (
      (a, b) <- Vector(
        (Vector(dk, eid, arx), Vector(dk, eid, doi1)),
        (Vector(dk, eid, doi1), Vector(dk, eid, arx))
      )
    )
      assertStageBlocked(
        decide(syntheticWork(strong ++ sameVenue, a, b)),
        Vector("dblp-key", "scopus-eid"),
        s"$a / $b"
      )
  }

  test("the v6 and v7 triggers keep their codes and also record the general stage code") {
    for ((scheme, key) <- recordKeys) {
      val viaArxiv = decide(syntheticWork(strong, Vector(key, arx), Vector(key, arx, doi1)))
      assertStageBlocked(viaArxiv, Vector(scheme), s"$scheme arXiv trigger")
      assert(viaArxiv.reasonCodes.contains(arxivTriggerReason), viaArxiv.reasonCodes.toString)
      assert(viaArxiv.rulesFired.contains(Rules.recordKeyBlockedByArxivStage(scheme)))
      val viaDoi = decide(syntheticWork(strong, Vector(key, arxivDoi), Vector(key, doi1, arxivDoi)))
      assertStageBlocked(viaDoi, Vector(scheme), s"$scheme preprint-DOI trigger")
      assert(viaDoi.reasonCodes.contains(doiTriggerReason), viaDoi.reasonCodes.toString)
      assert(viaDoi.rulesFired.contains(Rules.recordKeyBlockedByPreprintDoi(scheme)))
    }
  }

  // ------------------------------------------------------------------ item 2: controls

  test("control: a record key with an established publication stage still accepts") {
    for {
      (scheme, key) <- recordKeys
      (what, kv, a, b, verdict) <- Vector(
        ("same venue, no markers", sameVenue, Vector(key), Vector(key), StageVerdict.SameVenue),
        (
          "same venue, one publication DOI",
          strong ++ sameVenue,
          Vector(key),
          Vector(key, doi1),
          StageVerdict.SameVenue
        ),
        (
          "two pure preprints, no shared preprint identifier",
          Seq.empty,
          Vector(key, arx),
          Vector(key, arxivDoi),
          StageVerdict.SamePreprint
        )
      )
      (x, y) <- Vector((a, b), (b, a))
    } {
      val clue = s"$scheme $what"
      val o = decide(syntheticWork(kv, x, y))
      assertAccepted(o, clue)
      assertEquals(o.stage.map(_.verdict), Some(verdict), clue)
      assert(o.rulesFired.contains(Rules.exactAgree(scheme)), s"$clue ${o.rulesFired}")
    }
  }

  test("control: a shared publication DOI still accepts a cross-stage pair with a record key") {
    for {
      (scheme, key) <- recordKeys
      (a, b) <- Vector(
        (Vector(key, arx, doi1), Vector(key, doi1)),
        (Vector(key, doi1), Vector(key, arx, doi1))
      )
    } {
      val o = decide(syntheticWork(Seq.empty, a, b))
      assertAccepted(o, s"$scheme $a / $b")
      assert(o.rulesFired.contains(Rules.exactAgree("doi")))
      assert(!o.stage.exists(_.established))
    }
  }

  test("control: researcher pairs are unaffected (no stage, identifier agreement accepts)") {
    val orcid = ExactId(ExactIdScheme.Orcid, "0000-0002-1825-0097")
    val o = decide(syntheticResearcher(Seq.empty, Vector(orcid), Vector(orcid)))
    assertEquals((o.outcome, o.path), (MatchOutcome.Accepted, MatchPath.ExactId))
    assertEquals(o.stage, None)
    assert(!o.reasonCodes.contains(stageBlockedReason))
  }

  // ------------------------------------------------------------------ item 2: conflicts unchanged

  test(
    "conflicts unchanged: a T1 disagreement or a record-key disagreement next to a held-back key"
  ) {
    for ((scheme, key) <- recordKeys) {
      val t1 = decide(syntheticWork(strong, Vector(key, arx, doi1), Vector(key, doi2)))
      assertEquals((t1.outcome, t1.path), (MatchOutcome.Conflict, MatchPath.ExactId), scheme)
      assert(
        t1.conflicts.exists(c =>
          c.scheme == ExactIdScheme.Doi && c.kind == IdentifierConflictKind.Disagreement
        )
      )
      assert(
        t1.conflicts.exists(c =>
          c.scheme.tag == scheme && c.kind == IdentifierConflictKind.ContradictedAgreement
        )
      )
      assert(!t1.reasonCodes.contains(stageBlockedReason), scheme)
      assertEquals(valid(t1).decision, MatchOutcome.Conflict)
    }
    // Step 3: a held-back dblp-key agreement still contradicts a scopus-eid disagreement.
    val s3 = decide(syntheticWork(strong, Vector(dk, eid), Vector(dk, eid2)))
    assertEquals((s3.outcome, s3.path), (MatchOutcome.Conflict, MatchPath.ExactId))
    assert(s3.rulesFired.contains(Rules.IdentifierContradiction))
    assert(!s3.reasonCodes.contains(stageBlockedReason))
    assert(s3.conflicts.exists(_.scheme == ExactIdScheme.ScopusEid))
  }

  test(
    "sweep: a record key decides only at an established stage; ACCEPTED never has conflicts or block codes"
  ) {
    val sides: Vector[Vector[ExactId]] = Vector(
      Vector(dk),
      Vector(dk, arx),
      Vector(dk, arxV2),
      Vector(dk, arxivDoi),
      Vector(dk, doi1),
      Vector(dk, doi2),
      Vector(dk, arx, doi1),
      Vector(dk, arxivDoi, doi1),
      Vector(eid),
      Vector(eid, arx),
      Vector(eid, doi1),
      Vector(eid2, doi1),
      Vector(dk, eid),
      Vector(arx),
      Vector(doi1)
    )
    val t1 = Set("doi", "arxiv", "orcid", "dblp-pid")
    var keyOnly = 0
    var blocked = 0
    for {
      a <- sides
      b <- sides
      kv <- Vector(Seq.empty, strong, strong ++ sameVenue)
    } {
      val o = decide(syntheticWork(kv, a, b))
      if (o.outcome == MatchOutcome.Accepted) {
        assertEquals(o.conflicts, Vector.empty, s"$a / $b")
        assert(!o.rulesFired.exists(_.contains("record-key-agreement-blocked")), s"$a / $b")
        val byKey =
          Vector("dblp-key", "scopus-eid").exists(s => o.rulesFired.contains(Rules.exactAgree(s)))
        val byT1 = t1.exists(s => o.rulesFired.contains(Rules.exactAgree(s)))
        if (byKey && !byT1) {
          keyOnly += 1
          assert(o.stage.exists(_.established), s"$a / $b ${o.stage}")
        }
      }
      if (o.reasonCodes.contains(stageBlockedReason)) {
        blocked += 1
        assertEquals((o.outcome, o.path), (MatchOutcome.Unresolved, MatchPath.ExactId), s"$a / $b")
      }
      assertEquals(MatchDecision.contract.validate(valid(o)), Vector.empty, s"$a / $b")
    }
    assert(keyOnly > 0 && blocked > 0, s"keyOnly=$keyOnly blocked=$blocked")
  }

  // ------------------------------------------------------------------ item 3: arXiv base ID (m7)

  test("m7: agreement and disagreement use one base-ID normalisation, originals preserved") {
    def agree(a: Vector[ExactId], b: Vector[ExactId]) =
      ExactIdentifiers.agreement(ExactIdScheme.Arxiv, a, b)
    def disagree(a: Vector[ExactId], b: Vector[ExactId]) =
      ExactIdentifiers.disagreement(ExactIdScheme.Arxiv, a, b)
    // Revisions of one submission agree, with both original values kept.
    assertEquals(
      agree(Vector(arxV1), Vector(arxV2)).map(_.value).toSet,
      Set("2101.00001v1", "2101.00001v2")
    )
    assertEquals(
      agree(Vector(arxV1), Vector(arx)).map(_.value).toSet,
      Set("2101.00001v1", "2101.00001")
    )
    assertEquals(
      agree(Vector(arxOld), Vector(arxOldV3)).map(_.value).toSet,
      Set("hep-th/9901001", "hep-th/9901001v3")
    )
    assert(
      ExactIdentifiers.agreesOnlyByComparable(ExactIdScheme.Arxiv, Vector(arxV1), Vector(arxV2))
    )
    assert(!ExactIdentifiers.agreesOnlyByComparable(ExactIdScheme.Arxiv, Vector(arx), Vector(arx)))
    // Distinct submissions disagree: new-style, and old-style against new-style.
    assertEquals(agree(Vector(arx), Vector(arxOther)), Vector.empty)
    assert(disagree(Vector(arx), Vector(arxOther)).isDefined)
    assert(disagree(Vector(arxV1), Vector(ExactId(ExactIdScheme.Arxiv, "2101.00002v1"))).isDefined)
    assertEquals(agree(Vector(arxOld), Vector(arx)), Vector.empty)
    assert(disagree(Vector(arxOld), Vector(arx)).isDefined)
    // For every pair of non-empty sides exactly one of agreement and disagreement holds.
    val all = Vector(arx, arxV1, arxV2, arxOther, arxOld, arxOldV3)
    for {
      a <- all
      b <- all
    } assertEquals(
      agree(Vector(a), Vector(b)).nonEmpty,
      disagree(Vector(a), Vector(b)).isEmpty,
      s"$a / $b"
    )
  }

  test("m7: two pure preprints with v1 / v2 (or v1 / bare) accept at same-stage-preprint") {
    for {
      (a, b) <- Vector((arxV1, arxV2), (arxV1, arx), (arxOld, arxOldV3))
      (x, y) <- Vector((a, b), (b, a))
    } {
      val o = decide(syntheticWork(sameTitle, Vector(x), Vector(y)))
      assertAccepted(o, s"$x / $y")
      assertEquals(o.stage.map(_.verdict), Some(StageVerdict.SamePreprint))
      assert(o.rulesFired.contains(Rules.exactAgree("arxiv")), o.rulesFired.toString)
      assert(o.reasonCodes.contains(foldedReason), o.reasonCodes.toString)
      // Provenance: the candidate field is unchanged (no exact shared value); the policy agreement
      // keeps both original versioned IDs.
      assertEquals(o.exactIdAgreements, Vector.empty)
      assertEquals(o.policyAgreements.map(_.value).toSet, Set(x.value, y.value))
    }
    // An exact shared revision is not "folded".
    val exact = decide(syntheticWork(sameTitle, Vector(arxV1), Vector(arxV1)))
    assertAccepted(exact, "exact")
    assert(!exact.reasonCodes.contains(foldedReason))
  }

  test("m7: distinct submissions disagree and give CONFLICT as T1 (new-style and old-style)") {
    for {
      (a, b) <- Vector(
        (arx, arxOther),
        (arxV1, ExactId(ExactIdScheme.Arxiv, "2101.00002v2")),
        (arxOld, arx),
        (arxOldV3, arxV2)
      )
      (x, y) <- Vector((a, b), (b, a))
    } {
      val o = decide(syntheticWork(sameTitle, Vector(x), Vector(y)))
      assertEquals((o.outcome, o.path), (MatchOutcome.Conflict, MatchPath.ExactId), s"$x / $y")
      assert(
        o.conflicts.exists(c =>
          c.scheme == ExactIdScheme.Arxiv && c.kind == IdentifierConflictKind.Disagreement
        ),
        o.conflicts.toString
      )
      assertEquals(valid(o).decision, MatchOutcome.Conflict)
    }
  }

  test("m7: RV-P5-08 MINOR-1, a versioned arXiv agreement with a record key is blocked") {
    for {
      (scheme, key) <- recordKeys
      kv <- Vector(Seq.empty, strong, strong ++ sameVenue, exactBib ++ sameVenue)
      (a, b) <- Vector(
        (Vector(key, arxV1), Vector(key, arxV2, doi1)),
        (Vector(key, arxV2, doi1), Vector(key, arxV1))
      )
    } {
      val clue = s"$scheme $a / $b"
      val o = decide(syntheticWork(kv, a, b))
      assertStageBlocked(o, Vector(scheme), clue)
      assert(o.rulesFired.contains(Rules.ArxivCrossStage), s"$clue ${o.rulesFired}")
      assert(o.rulesFired.contains(Rules.recordKeyBlockedByArxivStage(scheme)), clue)
      assert(o.reasonCodes.contains(arxivTriggerReason), clue)
      assert(o.reasonCodes.contains(foldedReason), clue)
      assert(
        Set("2101.00001v1", "2101.00001v2").subsetOf(o.policyAgreements.map(_.value).toSet),
        o.policyAgreements.toString
      )
    }
  }

  test(
    "m7: a versioned arXiv agreement without a record key across stages is UNRESOLVED on exact-id"
  ) {
    for (
      (a, b) <- Vector((Vector(arxV1), Vector(arxV2, doi1)), (Vector(arxV2, doi1), Vector(arxV1)))
    ) {
      val o = decide(syntheticWork(exactBib ++ sameVenue, a, b))
      assertEquals(
        (o.outcome, o.path, o.confidence),
        (MatchOutcome.Unresolved, MatchPath.ExactId, 0.0),
        s"$a / $b"
      )
      assert(o.rulesFired.contains(Rules.ArxivCrossStage))
      assert(!o.llmBandEligible)
    }
  }

  test(
    "m7: a versioned arXiv agreement contradicted by a T1 DOI disagreement keeps the originals"
  ) {
    val o = decide(syntheticWork(sameTitle, Vector(arxV1, doi1), Vector(arxV2, doi2)))
    assertEquals((o.outcome, o.path), (MatchOutcome.Conflict, MatchPath.ExactId))
    val contradicted = o.conflicts.filter(c =>
      c.scheme == ExactIdScheme.Arxiv && c.kind == IdentifierConflictKind.ContradictedAgreement
    )
    assertEquals(contradicted.size, 1, o.conflicts.toString)
    assert(contradicted.head.toString.contains("2101.00001v1"), contradicted.toString)
    assert(contradicted.head.toString.contains("2101.00001v2"), contradicted.toString)
    assertEquals(valid(o).decision, MatchOutcome.Conflict)
  }
}
