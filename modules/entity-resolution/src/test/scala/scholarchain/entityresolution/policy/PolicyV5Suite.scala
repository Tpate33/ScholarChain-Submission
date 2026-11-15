package scholarchain.entityresolution.policy

import scala.collection.immutable.SortedMap

import scholarchain.core.*
import scholarchain.core.config.EntityResolutionConfig
import scholarchain.entityresolution.adjudication.Adjudication

import PolicyFixtures.*

/**
 * er-policy.v5 (ER-P5-09, human decision of 2026-11-11, decisions-07, Option 1; RV-P5-05 finding
 * 1): a DOI agreement whose shared DOIs all carry a preprint prefix decides only when both records
 * are pure preprints; otherwise the pair is UNRESOLVED on the exact-id path and no fallback path
 * (deterministic rule, score, LLM) may accept it. Shared publication DOIs and T1 conflict handling
 * are unchanged. Every identifier here is SYNTHETIC (unregistered DOI prefixes 10.9999 and 10.9998,
 * invented arXiv IDs; 10.48550 is arXiv's DOI prefix with invented IDs); nothing here is gold data.
 */
class PolicyV5Suite extends munit.FunSuite {

  private val doi1 = ExactId(ExactIdScheme.Doi, "10.9999/example.1")
  private val doi2 = ExactId(ExactIdScheme.Doi, "10.9999/example.2")
  private val doi3 = ExactId(ExactIdScheme.Doi, "10.9998/example.3")
  private val arxivDoi = ExactId(ExactIdScheme.Doi, "10.48550/arxiv.2101.00001")
  private val arx = ExactId(ExactIdScheme.Arxiv, "2101.00001")
  private val arx2 = ExactId(ExactIdScheme.Arxiv, "2203.00002")
  private val dk1 = ExactId(ExactIdScheme.DblpKey, "journals/example/SynA")
  private val dk2 = ExactId(ExactIdScheme.DblpKey, "conf/example/SynB")

  /** RV-P5-05 finding 1: the DBLP CoRR preprint record. */
  private val preprint = Vector(arxivDoi, arx)

  /** RV-P5-05 finding 1: the published record listing its own DOI and the preprint DOI. */
  private val mixed = Vector(doi1, arxivDoi)

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

  /** A score inside the LLM band without any identifier (checked in the test). */
  private val band: Seq[(String, Double)] = Seq("title.sim" -> 1.0, "title.token_jaccard" -> 1.0)

  private val sameVenue: Seq[(String, Double)] =
    Seq("venue.id_both" -> 1.0, "venue.id_agree" -> 1.0)

  private val sameStage: Seq[(String, Double)] = sameVenue ++ Seq(
    "title.exact" -> 1.0,
    "title.sim" -> 1.0,
    "year.both" -> 1.0,
    "year.abs_diff" -> 0.0
  )

  private val profiles: Vector[Seq[(String, Double)]] =
    Vector(Seq.empty, band, strong, strong ++ sameVenue, exactBib, exactBib ++ sameVenue, sameStage)

  private def valid(o: ResolutionOutcome): MatchDecision =
    Resolver.toMatchDecision(o, ctx).fold(e => fail(e.mkString("; ")), identity)

  private def assertPreprintDoiBlocked(o: ResolutionOutcome, clue: String)(using
      munit.Location
  ): Unit = {
    assertEquals((o.outcome, o.path), (MatchOutcome.Unresolved, MatchPath.ExactId), clue)
    assertEquals(o.confidence, 0.0, clue)
    assert(o.rulesFired.contains(Rules.PreprintDoiCrossStage), s"$clue ${o.rulesFired}")
    assert(!o.rulesFired.contains(Rules.exactAgree("doi")), s"$clue ${o.rulesFired}")
    assert(!o.rulesFired.exists(_.startsWith("exact-id.override.")), s"$clue ${o.rulesFired}")
    assert(o.reasonCodes.contains("preprint-doi-agreement-cross-stage"), s"$clue ${o.reasonCodes}")
    assert(
      o.reasonCodes.exists(r =>
        r == "publication-stage-uncertain" || r == "publication-stage-cross-stage"
      ),
      s"$clue ${o.reasonCodes}"
    )
    assert(!o.reasonCodes.contains("exact-id-agreement.doi"), s"$clue ${o.reasonCodes}")
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
    assert(o.stage.exists(!_.established), s"$clue ${o.stage}")
    assertEquals(valid(o).decision, MatchOutcome.Unresolved, clue)
    assertEquals(valid(o).provenance.policyVersion, Some("er-policy.v8"), clue)
  }

  // ------------------------------------------------------------------ versions

  test("er-policy.v8 (v5 rules kept) is the default; v4 is retired history, refused") {
    assertEquals(policy.policyVersion, "er-policy.v8")
    assertEquals(policy.schemaVersion, "er-policy-file.v8")
    assertEquals(MatchPolicy.FileSchemaVersion, "er-policy-file.v8")
    assertEquals(MatchPolicy.DefaultResource.split('/').last, "er-policy.v8.json")
    // The prompt text did not change, so the prompt version stays v2.
    assertEquals(policy.adjudication.promptVersion, "er-adjudication-prompt.v2")
    val v4 = MatchPolicy.resourceText(MatchPolicy.RetiredV4Resource).fold(e => fail(e), identity)
    assert(v4.contains("\"er-policy.v4\""), "the v4 history file is kept")
    val refused = MatchPolicy.parse(v4)
    assert(refused.left.exists(_.exists(_.contains("retired"))), refused.toString)
    assert(
      MatchPolicy
        .validate(policy.copy(policyVersion = "er-policy.v4"))
        .exists(_.contains("retired"))
    )
    val json = MatchPolicy.resourceText(MatchPolicy.DefaultResource).fold(e => fail(e), identity)
    val renamed = json.replace("\"er-policy-file.v8\"", "\"er-policy-file.v4\"")
    assert(renamed != json, "replacement did not apply")
    assert(MatchPolicy.parse(renamed).left.exists(_.exists(_.contains("retired"))))
    val cfg =
      EntityResolutionConfig("er-policy.v4", 0.85, 0.2, 0.4, 0.7, "er-adjudication-prompt.v2")
    assert(MatchPolicy.withConfig(policy, cfg).isLeft, "a v4 configuration never runs")
    assert(MatchPolicy.withConfig(policy, cfg.copy(policyVersion = "er-policy.v8")).isRight)
  }

  test("the policy text no longer calls every DOI agreement publication-stage evidence") {
    val doi = policy.work.rule(ExactIdScheme.Doi).get
    assert(!doi.agreementRationale.contains("is already publication-stage evidence"))
    assert(doi.agreementRationale.contains("preprint-doi-agreement-cross-stage"))
    assert(doi.agreementRationale.contains("same-stage-preprint"))
    assert(policy.publicationStage.definition.contains("preprint-doi-agreement-cross-stage"))
    assert(policy.evaluationOrder.exists(_.contains("preprint-doi-agreement-cross-stage")))
  }

  // ------------------------------------------------------------------ (a) the RV-P5-05 input

  test(
    "(a) a published record listing its own DOI and the preprint DOI never merges the preprint"
  ) {
    // The band profile really scores inside the LLM band without identifiers.
    val bandScore = decide(syntheticWork(band)).score.probability
    assert(policy.llmBand.contains(bandScore), bandScore.toString)
    for {
      kv <- profiles
      (a, b, orient) <- Vector((preprint, mixed, "A=preprint"), (mixed, preprint, "A=mixed"))
    } {
      val o = decide(syntheticWork(kv, a, b))
      assertPreprintDoiBlocked(o, s"$orient $kv")
      assertEquals(o.stage.map(_.verdict), Some(StageVerdict.Uncertain), s"$orient $kv")
    }
    // A preprint record carrying only the preprint DOI, and a mixed record that also lists the
    // arXiv ID: both agreements are blocked, in both orientations.
    val alsoArxiv = Vector(doi1, arxivDoi, arx)
    for {
      (a, b) <- Vector(
        (Vector(arxivDoi), mixed),
        (mixed, Vector(arxivDoi)),
        (preprint, alsoArxiv),
        (alsoArxiv, preprint)
      )
      kv <- Vector(strong ++ sameVenue, exactBib ++ sameVenue)
    } {
      val o = decide(syntheticWork(kv, a, b))
      assertPreprintDoiBlocked(o, s"$a / $b")
      if ((a ++ b).count(_ == arx) == 2) {
        assert(o.rulesFired.contains(Rules.ArxivCrossStage), o.rulesFired.toString)
        assert(!o.rulesFired.contains(Rules.exactAgree("arxiv")))
      }
    }
  }

  test("(a) a blocked preprint DOI does not override a record-key block") {
    val o =
      decide(syntheticWork(strong ++ sameVenue, Vector(dk1) ++ preprint, Vector(dk2) ++ mixed))
    assertPreprintDoiBlocked(o, "dblp-key")
    assertEquals(o.conflicts.map(_.scheme.tag), Vector("dblp-key"))
    assert(!o.reasonCodes.exists(_.contains("overridden-by.doi")), o.reasonCodes.toString)
  }

  test("the preprint prefixes come from the policy configuration, not from code") {
    // With no preprint prefix configured, the shared 10.48550 DOI is an ordinary DOI and decides.
    val none = policy.copy(workCorroboration =
      policy.workCorroboration.copy(preprintDoiPrefixes = Vector.empty)
    )
    assertEquals(MatchPolicy.validate(none), Vector.empty)
    val c = syntheticWork(Seq.empty, preprint, mixed)
    val plain = decide(c, none)
    assertEquals((plain.outcome, plain.path), (MatchOutcome.Accepted, MatchPath.ExactId))
    // With another prefix configured, that prefix blocks the same way.
    val other = policy.copy(workCorroboration =
      policy.workCorroboration.copy(preprintDoiPrefixes = Vector("10.9999/"))
    )
    val o = decide(syntheticWork(strong ++ sameVenue, Vector(doi1), Vector(doi1, doi3)), other)
    assertEquals((o.outcome, o.path), (MatchOutcome.Unresolved, MatchPath.ExactId))
    assert(o.rulesFired.contains(Rules.PreprintDoiCrossStage))
    assertEquals(
      decide(syntheticWork(Seq.empty, Vector(doi1), Vector(doi1, doi3))).outcome,
      MatchOutcome.Accepted
    )
  }

  // ------------------------------------------------------------------ (b) two preprints

  test("(b) two preprint records sharing a preprint DOI are ACCEPTED on exact-id") {
    Vector(
      (Vector(arxivDoi), Vector(arxivDoi)),
      (preprint, Vector(arxivDoi)),
      (Vector(arxivDoi), preprint),
      (preprint, preprint)
    ).foreach { case (a, b) =>
      val o = decide(syntheticWork(Seq.empty, a, b))
      val clue = s"$a / $b"
      assertEquals(
        (o.outcome, o.path, o.confidence),
        (MatchOutcome.Accepted, MatchPath.ExactId, 0.99),
        clue
      )
      assert(o.rulesFired.contains(Rules.exactAgree("doi")), clue)
      assert(!o.rulesFired.contains(Rules.PreprintDoiCrossStage), clue)
      assert(!o.reasonCodes.contains("preprint-doi-agreement-cross-stage"), clue)
      assertEquals(o.stage.map(_.verdict), Some(StageVerdict.SamePreprint), clue)
      assertEquals(o.conflicts, Vector.empty, clue)
      assertEquals(valid(o).decision, MatchOutcome.Accepted, clue)
    }
    // A shared preprint DOI between two preprints still overrides a record-key block.
    val keyed = decide(syntheticWork(Seq.empty, Vector(dk1, arxivDoi), Vector(dk2, arxivDoi)))
    assertEquals(keyed.outcome, MatchOutcome.Accepted)
    assert(keyed.reasonCodes.contains("block-inferred-acceptance.dblp-key.overridden-by.doi"))
  }

  // ------------------------------------------------------------------ (c) publication DOIs

  test("(c) a shared publication DOI still accepts, also next to a shared preprint DOI") {
    Vector(
      (Vector(doi1), Vector(doi1)),
      (mixed, mixed),
      (mixed, Vector(doi1)),
      (Vector(doi1), mixed),
      (Vector(doi1, arxivDoi, arx), Vector(doi1, arxivDoi, arx))
    ).foreach { case (a, b) =>
      val o = decide(syntheticWork(Seq.empty, a, b))
      val clue = s"$a / $b"
      assertEquals(
        (o.outcome, o.path, o.confidence),
        (MatchOutcome.Accepted, MatchPath.ExactId, 0.99),
        clue
      )
      assert(o.rulesFired.contains(Rules.exactAgree("doi")), clue)
      assert(!o.rulesFired.contains(Rules.PreprintDoiCrossStage), clue)
      assert(!o.reasonCodes.contains("preprint-doi-agreement-cross-stage"), clue)
      assertEquals(o.conflicts, Vector.empty, clue)
      assertEquals(valid(o).decision, MatchOutcome.Accepted, clue)
    }
    // A publication DOI agreement still overrides a record-key block.
    val keyed = decide(syntheticWork(Seq.empty, Vector(dk1) ++ mixed, Vector(dk2, doi1)))
    assertEquals(keyed.outcome, MatchOutcome.Accepted)
    assert(keyed.reasonCodes.contains("block-inferred-acceptance.dblp-key.overridden-by.doi"))
  }

  // ------------------------------------------------------------------ (d) T1 conflicts

  test("(d) a T1 disagreement next to a shared preprint DOI is still CONFLICT") {
    // An arXiv disagreement (the only other T1 work scheme) beside a shared preprint DOI, with a
    // pure-preprint side and with a mixed side: the blocked DOI still counts as an agreement for
    // step 2, so the identifiers contradict each other.
    Vector(
      (Vector(arxivDoi, arx), Vector(arxivDoi, arx2)),
      (Vector(arxivDoi, arx), Vector(doi1, arxivDoi, arx2)),
      (Vector(doi1, arxivDoi, arx2), Vector(arxivDoi, arx))
    ).foreach { case (a, b) =>
      val clue = s"$a / $b"
      val o = decide(syntheticWork(strong ++ sameVenue, a, b))
      assertEquals((o.outcome, o.path), (MatchOutcome.Conflict, MatchPath.ExactId), clue)
      assertEquals(o.confidence, policy.conflictConfidence, clue)
      assert(
        o.conflicts.exists(c =>
          c.scheme == ExactIdScheme.Arxiv && c.kind == IdentifierConflictKind.Disagreement
        ),
        s"$clue ${o.conflicts}"
      )
      assert(
        o.conflicts.exists(c =>
          c.scheme == ExactIdScheme.Doi && c.kind == IdentifierConflictKind.ContradictedAgreement
        ),
        s"$clue ${o.conflicts}"
      )
      assert(o.rulesFired.contains(Rules.exactConflict("arxiv")), clue)
      assert(o.rulesFired.contains(Rules.IdentifierContradiction), clue)
      assertEquals(valid(o).decision, MatchOutcome.Conflict, clue)
    }
    // A DOI disagreement (disjoint DOI sets) is CONFLICT as before.
    val dois = decide(syntheticWork(strong, Vector(arxivDoi), Vector(doi1)))
    assertEquals(dois.outcome, MatchOutcome.Conflict)
  }

  test("characterization: two mixed records with different publication DOIs and one preprint DOI") {
    // DOI comparison is set intersection, so a shared preprint DOI means "DOI agrees" and the two
    // different publication DOIs are not a T1 DOI disagreement. Under v5 the pair is no longer
    // ACCEPTED (it was under v4); it is UNRESOLVED, not CONFLICT.
    val o =
      decide(syntheticWork(strong ++ sameVenue, Vector(doi1, arxivDoi), Vector(doi2, arxivDoi)))
    assertPreprintDoiBlocked(o, "mixed / mixed")
  }

  test("every v5 outcome above maps to a valid core decision; ACCEPTED never carries conflicts") {
    Vector(
      syntheticWork(strong, preprint, mixed),
      syntheticWork(strong, mixed, preprint),
      syntheticWork(Seq.empty, Vector(arxivDoi), Vector(arxivDoi)),
      syntheticWork(Seq.empty, mixed, mixed),
      syntheticWork(strong, Vector(arxivDoi, arx), Vector(arxivDoi, arx2)),
      syntheticWork(strong, Vector(dk1) ++ preprint, Vector(dk2) ++ mixed)
    ).map(decide(_)).foreach { o =>
      assert(!(o.outcome == MatchOutcome.Accepted && o.conflicts.nonEmpty), o.rulesFired.toString)
      assertEquals(MatchDecision.contract.validate(valid(o)), Vector.empty, o.rulesFired.toString)
    }
    // The stage computed for the decision equals the documented definition.
    val s = PublicationStage.of(
      policy.workCorroboration,
      SortedMap.empty[String, Double],
      EndpointIds(preprint, mixed)
    )
    assertEquals(
      (s.a, s.b, s.verdict),
      (StageEvidence.Preprint, StageEvidence.Mixed, StageVerdict.Uncertain)
    )
  }
}
