package scholarchain.entityresolution.policy

import scholarchain.core.*
import scholarchain.core.config.EntityResolutionConfig
import scholarchain.entityresolution.adjudication.Adjudication

import PolicyFixtures.*

/**
 * er-policy.v7 (ER-P5-11, human decision of 2026-11-11, decisions-09 item 1): when a shared arXiv
 * ID leaves a work pair UNRESOLVED because the publication stages differ or are uncertain, a shared
 * dblp-key or scopus-eid must not override that result. Authoritative conflict handling is
 * unchanged. Also RV-P5-06 nit 2 (canonical preprint DOI prefixes). Every identifier here is
 * SYNTHETIC (unregistered DOI prefixes 10.9999, invented arXiv IDs, DBLP keys and Scopus EIDs;
 * 10.48550 is arXiv's DOI prefix with an invented ID); nothing here is gold data.
 */
class PolicyV7Suite extends munit.FunSuite {

  private val doi1 = ExactId(ExactIdScheme.Doi, "10.9999/example.1")
  private val doi2 = ExactId(ExactIdScheme.Doi, "10.9999/example.2")
  private val arxivDoi = ExactId(ExactIdScheme.Doi, "10.48550/arxiv.2101.00001")
  private val arx = ExactId(ExactIdScheme.Arxiv, "2101.00001")
  private val arx2 = ExactId(ExactIdScheme.Arxiv, "2203.00002")
  private val dk = ExactId(ExactIdScheme.DblpKey, "journals/example/SynA")
  private val eid = ExactId(ExactIdScheme.ScopusEid, "2-s2.0-99900000001")
  private val eid2 = ExactId(ExactIdScheme.ScopusEid, "2-s2.0-99900000002")

  private val recordKeys: Vector[(String, ExactId)] = Vector("dblp-key" -> dk, "scopus-eid" -> eid)

  /** The decisions-09 reason code, pinned literally (independent of [[Rules]]). */
  private val arxivBlockedReason = "record-key-agreement-blocked-by-arxiv-stage"
  private val doiBlockedReason = "record-key-agreement-blocked-by-preprint-doi-stage"

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

  private def assertArxivRecordKeyBlocked(
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
        Rules.recordKeyBlockedByArxivStage(s),
        s"exact-id.unresolved.record-key-agreement-blocked-by-arxiv-stage.$s"
      )
      assert(o.rulesFired.contains(Rules.recordKeyBlockedByArxivStage(s)), s"$clue ${o.rulesFired}")
      assert(!o.rulesFired.contains(Rules.exactAgree(s)), s"$clue ${o.rulesFired}")
      assert(!o.reasonCodes.contains(s"exact-id-agreement.$s"), s"$clue ${o.reasonCodes}")
    }
    assert(o.rulesFired.contains(Rules.ArxivCrossStage), s"$clue ${o.rulesFired}")
    assert(!o.rulesFired.contains(Rules.exactAgree("arxiv")), s"$clue ${o.rulesFired}")
    assert(o.reasonCodes.contains(arxivBlockedReason), s"$clue ${o.reasonCodes}")
    assert(o.reasonCodes.contains("arxiv-agreement-cross-stage"), s"$clue ${o.reasonCodes}")
    val verdict = o.stage.map(_.verdict).getOrElse(fail(s"no stage: $clue"))
    assert(!verdict.established, s"$clue $verdict")
    assert(o.reasonCodes.contains(s"publication-stage-${verdict.tag}"), s"$clue ${o.reasonCodes}")
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
    // The score path is never reached, so no threshold change could accept it either.
    assertEquals(o.scoreGate.map(_.acceptReachable), Some(false), clue)
    val d = valid(o)
    assertEquals(MatchDecision.contract.validate(d), Vector.empty, clue)
    assertEquals((d.decision, d.matchPath), (MatchOutcome.Unresolved, MatchDecisionPath.ExactId))
    assertEquals(d.provenance.policyVersion, Some("er-policy.v8"), clue)
  }

  private def assertNotArxivBlocked(o: ResolutionOutcome, clue: String)(using
      munit.Location
  ): Unit = {
    assert(!o.rulesFired.exists(_.contains(arxivBlockedReason)), s"$clue ${o.rulesFired}")
    assert(!o.reasonCodes.contains(arxivBlockedReason), s"$clue ${o.reasonCodes}")
  }

  // ------------------------------------------------------------------ versions

  test(
    "er-policy.v8 (v7 rules kept) is the default; v6 is retired history, refused by file schema, name and config"
  ) {
    assertEquals(policy.policyVersion, "er-policy.v8")
    assertEquals(policy.schemaVersion, "er-policy-file.v8")
    assertEquals(MatchPolicy.FileSchemaVersion, "er-policy-file.v8")
    assertEquals(MatchPolicy.DefaultResource.split('/').last, "er-policy.v8.json")
    assertEquals(policy.adjudication.promptVersion, "er-adjudication-prompt.v2")
    // Thresholds, features and blocking are unchanged.
    assertEquals((policy.thresholds, policy.llmBand), (Thresholds(0.85, 0.2), LlmBand(0.4, 0.7)))
    assertEquals(
      (policy.featureVersion, policy.blockingVersion),
      ("er-features.v2", "er-blocking.v2")
    )
    val v6 = MatchPolicy.resourceText(MatchPolicy.RetiredV6Resource).fold(e => fail(e), identity)
    assert(v6.contains("\"er-policy.v6\""), "the v6 history file is kept")
    assert(MatchPolicy.parse(v6).left.exists(_.exists(_.contains("retired"))))
    assert(
      MatchPolicy
        .validate(policy.copy(policyVersion = "er-policy.v6"))
        .exists(_.contains("retired"))
    )
    val json = MatchPolicy.resourceText(MatchPolicy.DefaultResource).fold(e => fail(e), identity)
    val renamed = json.replace("\"er-policy-file.v8\"", "\"er-policy-file.v6\"")
    assert(renamed != json, "replacement did not apply")
    assert(MatchPolicy.parse(renamed).left.exists(_.exists(_.contains("retired"))))
    val cfg =
      EntityResolutionConfig("er-policy.v6", 0.85, 0.2, 0.4, 0.7, "er-adjudication-prompt.v2")
    assert(MatchPolicy.withConfig(policy, cfg).isLeft, "a v6 configuration never runs")
    assert(MatchPolicy.withConfig(policy, cfg.copy(policyVersion = "er-policy.v8")).isRight)
  }

  test("the v7 policy text documents the arXiv record-key block") {
    Vector(ExactIdScheme.DblpKey, ExactIdScheme.ScopusEid, ExactIdScheme.Arxiv).foreach { s =>
      val r = policy.work.rule(s).get.agreementRationale
      assert(r.contains("decisions-09"), s.tag)
    }
    Vector(ExactIdScheme.DblpKey, ExactIdScheme.ScopusEid).foreach { s =>
      assert(policy.work.rule(s).get.agreementRationale.contains(arxivBlockedReason), s.tag)
    }
    assert(policy.publicationStage.definition.contains(arxivBlockedReason))
    assert(policy.publicationStage.rationale.contains("decisions-09"))
    assert(policy.evaluationOrder.exists(_.contains(arxivBlockedReason)))
    assert(
      policy.evaluationOrder.exists(_.contains(Rules.recordKeyBlockedByArxivStage("<scheme>")))
    )
  }

  // ------------------------------------------------------------------ the decisions-09 regressions

  test(
    "decisions-09: a shared dblp-key or scopus-eid does not override the arXiv cross-stage block (both orientations, strong profiles)"
  ) {
    val strongScore = decide(syntheticWork(strong)).score.probability
    assert(strongScore >= policy.thresholds.accept, strongScore.toString)
    assert(policy.llmBand.contains(decide(syntheticWork(band)).score.probability))
    var checked = 0
    for {
      (scheme, key) <- recordKeys
      (name, kv) <- profiles
      // A: the preprint (arXiv ID only); B: a published record listing the arXiv ID next to one or
      // two DOIs of its own (mixed). The preprint side has no DOI, so no DOI is compared, and the
      // stage is uncertain (preprint against mixed).
      (pubName, published) <- Vector(
        "published doi + arxiv" -> Vector(key, arx, doi1),
        "published two dois + arxiv" -> Vector(key, arx, doi1, doi2)
      )
      preprint = Vector(key, arx)
      (a, b, orient) <- Vector(
        (preprint, published, "A=preprint"),
        (published, preprint, "A=mixed")
      )
    } {
      val clue = s"$scheme $name $pubName $orient"
      val o = decide(syntheticWork(kv, a, b))
      assertArxivRecordKeyBlocked(o, Vector(scheme), clue)
      // Not the preprint-DOI trigger: no preprint DOI is shared.
      assert(!o.reasonCodes.contains(doiBlockedReason), s"$clue ${o.reasonCodes}")
      assert(!o.rulesFired.contains(Rules.PreprintDoiCrossStage), s"$clue ${o.rulesFired}")
      checked += 1
    }
    assertEquals(checked, 2 * 6 * 2 * 2)
  }

  test(
    "decisions-09: the deterministic title-year-authors rule cannot accept the held-back pair either"
  ) {
    // Exact title, year and author sequence plus the same venue: without the block the record key
    // would accept (v6), and without any identifier the deterministic rule would. Two published
    // records listing the arXiv ID with different DOIs would instead be a T1 DOI disagreement
    // (CONFLICT, step 2; tested below), so preprint against mixed is the reachable form.
    for {
      (scheme, key) <- recordKeys
      (a, b) <- Vector(
        (Vector(key, arx), Vector(key, arx, doi1)),
        (Vector(key, arx, doi1), Vector(key, arx))
      )
    } {
      val o = decide(syntheticWork(exactBib ++ sameVenue, a, b))
      assertArxivRecordKeyBlocked(o, Vector(scheme), s"$scheme $a / $b")
      assertEquals(o.stage.map(_.verdict), Some(StageVerdict.Uncertain))
    }
  }

  test("decisions-09: both record keys shared at once are both blocked and both recorded") {
    for {
      (a, b) <- Vector(
        (Vector(dk, eid, arx), Vector(dk, eid, arx, doi1)),
        (Vector(dk, eid, arx, doi1), Vector(dk, eid, arx))
      )
    } assertArxivRecordKeyBlocked(
      decide(syntheticWork(strong ++ sameVenue, a, b)),
      Vector("dblp-key", "scopus-eid"),
      s"$a / $b"
    )
  }

  test("both triggers at once (shared arXiv ID and shared preprint DOI): both are recorded") {
    for ((scheme, key) <- recordKeys) {
      val o = decide(
        syntheticWork(
          strong ++ sameVenue,
          Vector(key, arx, arxivDoi),
          Vector(key, arx, arxivDoi, doi1)
        )
      )
      assertArxivRecordKeyBlocked(o, Vector(scheme), scheme)
      assert(
        o.rulesFired.contains(Rules.recordKeyBlockedByPreprintDoi(scheme)),
        o.rulesFired.toString
      )
      assert(o.reasonCodes.contains(doiBlockedReason), o.reasonCodes.toString)
      assert(o.rulesFired.contains(Rules.PreprintDoiCrossStage), o.rulesFired.toString)
    }
  }

  // ------------------------------------------------------------------ controls

  test(
    "decisions-11 (replaces the v7 'no preprint identifier shared' accept control): the v7 trigger does not fire, and the v8 stage block decides"
  ) {
    // Under v7 these inputs were ACCEPTED at 0.99 by the record key (RV-P5-08 section 1 gap).
    // er-policy.v8 (decisions-11 item 2): the stage is cross-stage or uncertain (the `strong`
    // profile has no venue agreement), so the pair stays UNRESOLVED; see PolicyV8Suite.
    for {
      (scheme, key) <- recordKeys
      (what, a, b) <- Vector(
        ("key only", Vector(key), Vector(key)),
        ("arXiv ID on one side only", Vector(key, arx), Vector(key)),
        ("arXiv ID on one side, publication DOI on the other", Vector(key, arx), Vector(key, doi1))
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
      assertNotArxivBlocked(o, clue)
      assertEquals(o.conflicts, Vector.empty, clue)
      assertEquals(valid(o).decision, MatchOutcome.Unresolved, clue)
    }
  }

  test("control: a shared publication DOI decides next to a blocked arXiv ID and a record key") {
    for {
      (scheme, key) <- recordKeys
      (a, b) <- Vector(
        (Vector(key, arx, doi1), Vector(key, doi1)),
        (Vector(key, doi1), Vector(key, arx, doi1)),
        (Vector(key, arx, doi1), Vector(key, arx, doi1))
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
      assertNotArxivBlocked(o, clue)
      assertEquals(o.conflicts, Vector.empty, clue)
    }
  }

  test("control: two pure preprints sharing the arXiv ID and a record key accept") {
    for {
      (scheme, key) <- recordKeys
      (a, b) <- Vector(
        (Vector(key, arx), Vector(key, arx)),
        (Vector(key, arx, arxivDoi), Vector(key, arx))
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
      assert(o.rulesFired.contains(Rules.exactAgree("arxiv")), clue)
      assertNotArxivBlocked(o, clue)
    }
  }

  // ------------------------------------------------------------------ conflicts (unchanged)

  test("a T1 disagreement next to a shared arXiv ID and a shared record key is still CONFLICT") {
    for ((scheme, key) <- recordKeys) {
      // Two published records listing the same arXiv ID and different DOIs: a T1 DOI disagreement.
      val doiT1 = decide(syntheticWork(strong, Vector(key, arx, doi1), Vector(key, arx, doi2)))
      assertEquals((doiT1.outcome, doiT1.path), (MatchOutcome.Conflict, MatchPath.ExactId), scheme)
      assert(
        doiT1.conflicts.exists(c =>
          c.scheme == ExactIdScheme.Doi && c.kind == IdentifierConflictKind.Disagreement
        )
      )
      assert(
        doiT1.conflicts.exists(c =>
          c.scheme.tag == scheme && c.kind == IdentifierConflictKind.ContradictedAgreement
        )
      )
      assertNotArxivBlocked(doiT1, scheme)
      // An arXiv disagreement beside a shared record key.
      val arxT1 = decide(syntheticWork(strong, Vector(key, arx), Vector(key, arx2, doi1)))
      assertEquals(arxT1.outcome, MatchOutcome.Conflict, scheme)
      assertNotArxivBlocked(arxT1, scheme)
      assertEquals(valid(arxT1).decision, MatchOutcome.Conflict, scheme)
    }
  }

  test(
    "step 3 is unchanged: a held-back dblp-key agreement still contradicts a scopus-eid disagreement"
  ) {
    for (
      (a, b) <- Vector(
        (Vector(dk, eid, arx), Vector(dk, eid2, arx, doi1)),
        (Vector(dk, eid2, arx, doi1), Vector(dk, eid, arx))
      )
    ) {
      val o = decide(syntheticWork(strong ++ sameVenue, a, b))
      assertEquals((o.outcome, o.path), (MatchOutcome.Conflict, MatchPath.ExactId), s"$a / $b")
      assert(o.rulesFired.contains(Rules.IdentifierContradiction), o.rulesFired.toString)
      // The record-key block did not decide this outcome, so its rule is not recorded.
      assertNotArxivBlocked(o, s"$a / $b")
      assert(o.conflicts.exists(_.scheme == ExactIdScheme.ScopusEid), o.conflicts.toString)
      assertEquals(valid(o).decision, MatchOutcome.Conflict)
    }
  }

  test(
    "sweep: no ACCEPTED outcome lists a record-key 'unresolved' rule; ACCEPTED has no conflicts"
  ) {
    val sides: Vector[Vector[ExactId]] = Vector(
      Vector(arx),
      Vector(arx, doi1),
      Vector(arx, arxivDoi),
      Vector(doi1),
      Vector(dk, arx),
      Vector(dk, arx, doi1),
      Vector(dk, arx, doi2),
      Vector(eid, arx),
      Vector(eid, arx, doi1),
      Vector(eid2, arx, doi1),
      Vector(dk, eid, arx),
      Vector(dk)
    )
    var accepted = 0
    var blocked = 0
    for {
      a <- sides
      b <- sides
      kv <- Vector(Seq.empty, strong ++ sameVenue)
    } {
      val o = decide(syntheticWork(kv, a, b))
      if (o.outcome == MatchOutcome.Accepted) {
        accepted += 1
        assert(!o.rulesFired.exists(_.contains("record-key-agreement-blocked")), s"$a / $b")
        assert(!o.reasonCodes.exists(_.startsWith("record-key-agreement-blocked")), s"$a / $b")
        assertEquals(o.conflicts, Vector.empty, s"$a / $b")
      }
      if (o.reasonCodes.contains(arxivBlockedReason)) {
        blocked += 1
        assertEquals((o.outcome, o.path), (MatchOutcome.Unresolved, MatchPath.ExactId), s"$a / $b")
      }
      assertEquals(MatchDecision.contract.validate(valid(o)), Vector.empty, s"$a / $b")
    }
    assert(accepted > 0 && blocked > 0, s"accepted=$accepted blocked=$blocked")
  }

  // ------------------------------------------------------------------ RV-P5-06 nit 2

  test(
    "RV-P5-06 nit 2: preprint DOI prefixes must be canonical lower-case DOI prefixes ending in /"
  ) {
    def withPrefixes(ps: Vector[String]) =
      MatchPolicy.validate(
        policy.copy(workCorroboration = policy.workCorroboration.copy(preprintDoiPrefixes = ps))
      )
    assertEquals(withPrefixes(Vector("10.48550/")), Vector.empty)
    assertEquals(withPrefixes(Vector("10.48550/", "10.9999/")), Vector.empty)
    Vector(
      "10.48550/ARXIV", // upper case: never matches a canonical DOI (fails open under v6)
      "10.48550/arxiv.", // a suffix part, not a registrant prefix
      "10.48550", // no trailing slash
      " 10.48550/", // surrounding space
      "10.48550/ ",
      "10.ABCD/",
      "10.123/", // registrant code too short
      "11.48550/",
      "doi:10.48550/",
      ""
    ).foreach { bad =>
      val ps = withPrefixes(Vector("10.48550/", bad))
      assert(ps.exists(_.contains("preprintDoiPrefixes")), s"'$bad' accepted: $ps")
    }
    // The same refusal through the policy file itself.
    val json = MatchPolicy.resourceText(MatchPolicy.DefaultResource).fold(e => fail(e), identity)
    val upper = json.replace(
      "\"preprintDoiPrefixes\": [\"10.48550/\"]",
      "\"preprintDoiPrefixes\": [\"10.48550/ARXIV\"]"
    )
    assert(upper != json, "replacement did not apply")
    assert(MatchPolicy.parse(upper).left.exists(_.exists(_.contains("preprintDoiPrefixes"))))
  }
}
