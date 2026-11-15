package scholarchain.core

import scala.collection.immutable.SortedMap

import Fixtures.*

/** Contract rules of `match-decision.v2` (Phase 5). Every fixture value is synthetic. */
class MatchDecisionV2Suite extends munit.FunSuite {

  private def errs(d: MatchDecision): Vector[ValidationError] = MatchDecision.contract.validate(d)

  private def invalidAt(d: MatchDecision, field: String): Boolean = errs(d).exists {
    case ValidationError.InvalidValue(f, _) => f == field
    case ValidationError.MissingField(f) => f == field
    case ValidationError.MalformedId(f, _, _, _) => f == field
    case ValidationError.NonCanonicalPair(f, _, _) => f == field
    case _ => false
  }

  private def assertInvalid(d: MatchDecision, field: String)(using munit.Location): Unit =
    assert(invalidAt(d, field), s"expected an error at '$field', got ${errs(d).map(_.message)}")

  test("the v2 fixtures are valid and carry their pairwise fields") {
    Vector(matchDecision, conflictDecision, llmDecision).foreach(d =>
      assertEquals(errs(d), Vector.empty)
    )
    assertEquals(matchDecision.schemaVersion, "scholarchain.match-decision.v2")
    assertEquals(matchDecision.candidateId, matchCandidate.candidateId)
    assertEquals(
      (matchDecision.sourceId, matchDecision.targetId),
      (matchCandidate.sourceIdA, matchCandidate.sourceIdB)
    )
    assertEquals(matchDecision.provenance.sourceRecordId, matchDecision.candidateId)
    assertEquals(conflictDecision.conflicts.head.valuesB, Vector(otherDoi))
  }

  test("build puts the pair in canonical order and exchanges the conflict sides with it") {
    val c = conflictDecision
    val swapped = MatchDecision.build(
      c.candidateId,
      c.entityKind,
      c.targetId,
      c.sourceId,
      c.matchPath,
      c.decision,
      c.confidence,
      c.score,
      c.blockingVersion,
      c.featureVersion,
      c.features,
      c.rulesFired,
      c.reasonCodes,
      c.conflicts.map(IdentifierConflict.swapSides),
      c.adjudication,
      c.decidedAt,
      c.provenance
    )
    assertEquals(swapped, Right(c))
    val stored = c.copy(sourceId = c.targetId, targetId = c.sourceId)
    assert(errs(stored).exists(_.isInstanceOf[ValidationError.NonCanonicalPair]))
    assert(
      MatchDecisionId.of(c.entityKind, c.sourceId, c.targetId, erPolicy, runId) == Right(
        c.decisionId
      )
    )
  }

  test("unknown and retired schema versions are rejected") {
    for (v <- Vector("scholarchain.match-decision.v1", "scholarchain.match-decision.v3", "")) {
      val e = errs(matchDecision.copy(schemaVersion = v))
      assert(
        e.exists(x =>
          x.isInstanceOf[ValidationError.UnsupportedSchemaVersion] ||
            x.isInstanceOf[ValidationError.MissingSchemaVersion]
        ),
        v
      )
    }
  }

  test("entity kind and endpoints: researcher or work, endpoint kinds must match") {
    assertInvalid(matchDecision.copy(entityKind = MatchEntityKind.Venue), "entityKind")
    assertInvalid(matchDecision.copy(entityKind = MatchEntityKind.Work), "sourceId")
    assertInvalid(matchDecision.copy(targetId = researcherA), "targetId")
    assertInvalid(matchDecision.copy(candidateId = "mcand:xyz"), "candidateId")
    assertInvalid(matchDecision.copy(decisionId = "match:" + ("0" * 64)), "decisionId")
  }

  test(
    "score is separate from confidence: in [0,1], required on scored paths, optional otherwise"
  ) {
    assertEquals(errs(matchDecision.copy(score = Some(0.4))), Vector.empty, "optional on exact-id")
    assertEquals(
      errs(
        matchDecision
          .copy(
            matchPath = MatchDecisionPath.DeterministicBibliographic,
            rulesFired = Vector("d.x")
          )
      ),
      Vector.empty,
      "a deterministic decision needs no score"
    )
    for (bad <- Vector(1.01, -0.01, Double.NaN, Double.PositiveInfinity))
      assertInvalid(matchDecision.copy(score = Some(bad)), "score")
    assertInvalid(
      matchDecision.copy(matchPath = MatchDecisionPath.Probabilistic, score = None),
      "score"
    )
    assertInvalid(llmDecision.copy(score = None), "score")
    assertEquals(
      errs(matchDecision.copy(matchPath = MatchDecisionPath.Probabilistic, score = Some(0.97))),
      Vector.empty
    )
    assertEquals(
      errs(matchDecision.copy(confidence = 0.0, score = Some(1.0))),
      Vector.empty,
      "confidence and score are independent"
    )
  }

  test("blank or non-token versions are rejected") {
    assertInvalid(matchDecision.copy(blockingVersion = ""), "blockingVersion")
    assertInvalid(matchDecision.copy(featureVersion = " "), "featureVersion")
    assertInvalid(matchDecision.copy(featureVersion = null), "featureVersion")
    assertInvalid(
      matchDecision.copy(provenance = matchDecision.provenance.copy(policyVersion = None)),
      "provenance.policyVersion"
    )
    assertInvalid(
      matchDecision.copy(provenance =
        matchDecision.provenance.copy(policyVersion = Some("er policy"))
      ),
      "provenance.policyVersion"
    )
    val blankPrompt = llmDecision.adjudication.get.copy(promptVersion = "")
    assertInvalid(llmDecision.copy(adjudication = Some(blankPrompt)), "adjudication.promptVersion")
    val blankModel = llmDecision.adjudication.get.copy(modelVersion = " ")
    assertInvalid(llmDecision.copy(adjudication = Some(blankModel)), "adjudication.modelVersion")
  }

  test(
    "rules, reason codes, and feature keys are tokens; rules and reasons non-empty and distinct"
  ) {
    assertInvalid(matchDecision.copy(rulesFired = Vector.empty), "rulesFired")
    assertInvalid(matchDecision.copy(rulesFired = Vector("a", "a")), "rulesFired")
    assertInvalid(matchDecision.copy(rulesFired = Vector("has space")), "rulesFired[0]")
    assertInvalid(matchDecision.copy(reasonCodes = Vector.empty), "reasonCodes")
    assertInvalid(matchDecision.copy(reasonCodes = Vector("ok", "bad/code")), "reasonCodes[1]")
    assertInvalid(matchDecision.copy(features = SortedMap("bad key" -> 1.0)), "features")
    assertInvalid(matchDecision.copy(features = SortedMap("x" -> Double.NaN)), "features.x")
  }

  test("provenance is derived from the decided candidate") {
    val p = matchDecision.provenance
    assertInvalid(
      matchDecision.copy(provenance = p.copy(sourceRecordId = workId)),
      "provenance.sourceRecordId"
    )
    assertInvalid(
      matchDecision.copy(provenance = p.copy(sourceSchemaVersion = "scholarchain.work-record.v3")),
      "provenance.sourceSchemaVersion"
    )
    assertInvalid(
      matchDecision.copy(
        provenance = p.copy(sourceDataset = SourceType.Dblp, sourceSchemaVersion = "dblp-raw/1.0.0")
      ),
      "provenance.sourceDataset"
    )
  }

  // ------------------------------------------------------------------ conflicts

  private val doiA = "10.1234/example.2021.001"

  test("CONFLICT requires conflicts; ACCEPTED forbids them; REJECTED may record a disagreement") {
    assertInvalid(conflictDecision.copy(conflicts = Vector.empty), "conflicts")
    assertInvalid(conflictDecision.copy(decision = MatchOutcome.Accepted), "conflicts")
    assertEquals(
      errs(conflictDecision.copy(decision = MatchOutcome.Rejected)),
      Vector.empty,
      "two unrelated works with different DOIs: REJECTED, disagreement kept as evidence"
    )
    assertEquals(errs(conflictDecision.copy(decision = MatchOutcome.Unresolved)), Vector.empty)
  }

  test("an agreement contradicted by a rule is only allowed for CONFLICT") {
    val contradicted =
      IdentifierConflict.contradictedAgreement(
        ExactIdScheme.Orcid,
        Vector(orcidA),
        "exact-id.conflict.same-work"
      )
    val conflict = matchDecision.copy(
      decision = MatchOutcome.Conflict,
      conflicts = Vector(contradicted),
      rulesFired = Vector("exact-id.agree.orcid", "exact-id.conflict.same-work")
    )
    assertEquals(errs(conflict), Vector.empty)
    assertInvalid(conflict.copy(decision = MatchOutcome.Rejected), "conflicts")
    assertInvalid(conflict.copy(decision = MatchOutcome.Unresolved), "conflicts")
  }

  test(
    "conflict entries: disjoint disagreements, canonical sorted values, scheme of the entity kind"
  ) {
    def withConflict(c: IdentifierConflict) = conflictDecision.copy(conflicts = Vector(c))
    val base = conflictDecision.conflicts.head
    assertInvalid(withConflict(base.copy(valuesB = Vector(doiA, otherDoi))), "conflicts[0].valuesB")
    assertInvalid(withConflict(base.copy(valuesA = Vector.empty)), "conflicts[0].valuesA")
    assertInvalid(withConflict(base.copy(valuesB = null)), "conflicts[0].valuesB")
    assert(
      errs(withConflict(base.copy(valuesA = Vector("10.1234/UPPER")))).exists {
        case ValidationError.MalformedId(f, "doi", _, _) => f == "conflicts[0].valuesA[0]"
        case _ => false
      }
    )
    assertInvalid(
      withConflict(base.copy(valuesB = Vector(otherDoi, "10.1234/example.2021.000"))),
      "conflicts[0].valuesB"
    )
    assertInvalid(
      withConflict(
        base.copy(scheme = ExactIdScheme.Orcid, valuesA = Vector(orcidA), valuesB = Vector(orcidB))
      ),
      "conflicts[0].scheme"
    )
    assertInvalid(
      withConflict(base.copy(contradictingRule = Some("rule"))),
      "conflicts[0].contradictingRule"
    )
    val contradicted =
      base.copy(kind = IdentifierConflictKind.ContradictedAgreement, contradictingRule = None)
    assertInvalid(
      withConflict(contradicted.copy(valuesB = Vector(doiA))),
      "conflicts[0].contradictingRule"
    )
    assertInvalid(
      withConflict(contradicted.copy(contradictingRule = Some("rule"))),
      "conflicts[0].valuesB"
    )
    assertInvalid(withConflict(base.copy(kind = null)), "conflicts[0].kind")
  }

  test("conflict lists are sorted by (scheme, kind, rule) without duplicates") {
    val doi = conflictDecision.conflicts.head
    val arxiv = IdentifierConflict(
      IdentifierConflictKind.Disagreement,
      ExactIdScheme.Arxiv,
      Vector("2101.00001"),
      Vector("2101.00002"),
      None
    )
    assertEquals(errs(conflictDecision.copy(conflicts = Vector(arxiv, doi))), Vector.empty)
    assertInvalid(conflictDecision.copy(conflicts = Vector(doi, arxiv)), "conflicts")
    assertInvalid(conflictDecision.copy(conflicts = Vector(doi, doi)), "conflicts")
    assertEquals(IdentifierConflict.canonical(Vector(doi, arxiv, doi)), Vector(arxiv, doi))
  }

  test(
    "IdentifierConflict.disagreement follows the candidate feature rule (both asserted, disjoint)"
  ) {
    val a = Vector(ExactId(ExactIdScheme.Doi, otherDoi), ExactId(ExactIdScheme.Arxiv, "2101.00001"))
    val b = Vector(ExactId(ExactIdScheme.Doi, doiA))
    assertEquals(
      IdentifierConflict.disagreement(ExactIdScheme.Doi, a, b),
      Some(
        IdentifierConflict(
          IdentifierConflictKind.Disagreement,
          ExactIdScheme.Doi,
          Vector(otherDoi),
          Vector(doiA),
          None
        )
      )
    )
    assertEquals(
      IdentifierConflict.disagreement(ExactIdScheme.Arxiv, a, b),
      None,
      "absent on side B"
    )
    val shared = b :+ ExactId(ExactIdScheme.Doi, otherDoi)
    assertEquals(
      IdentifierConflict.disagreement(ExactIdScheme.Doi, a, shared),
      None,
      "values intersect"
    )
    assertEquals(IdentifierConflict.disagreement(ExactIdScheme.Doi, Vector.empty, b), None)
  }

  // ------------------------------------------------------------------ adjudication

  test("an adjudication reference is required on the LLM path and refused on every other path") {
    assertInvalid(llmDecision.copy(adjudication = None), "adjudication")
    for (
      path <- Vector(
        MatchDecisionPath.ExactId,
        MatchDecisionPath.DeterministicBibliographic,
        MatchDecisionPath.Probabilistic
      )
    )
      assertInvalid(llmDecision.copy(matchPath = path), "adjudication")
    assertInvalid(
      matchDecision.copy(adjudication =
        Some(adjudicationRef.copy(decision = AdjudicationVerdict.Accept))
      ),
      "adjudication"
    )
  }

  test("the verdict implies the outcome; the LLM can abstain; it never yields CONFLICT") {
    assertInvalid(llmDecision.copy(decision = MatchOutcome.Accepted), "adjudication.decision")
    val accept = adjudicationRef.copy(decision = AdjudicationVerdict.Accept)
    assertEquals(
      errs(llmDecision.copy(decision = MatchOutcome.Accepted, adjudication = Some(accept))),
      Vector.empty
    )
    val abstain =
      adjudicationRef.copy(decision = AdjudicationVerdict.Abstain, evidenceIds = Vector.empty)
    assertEquals(
      errs(
        llmDecision
          .copy(decision = MatchOutcome.Unresolved, confidence = 0.0, adjudication = Some(abstain))
      ),
      Vector.empty,
      "abstain needs no evidence and leaves the pair UNRESOLVED"
    )
    assertInvalid(
      llmDecision.copy(decision = MatchOutcome.Conflict, conflicts = conflictLike),
      "matchPath"
    )
    assertInvalid(
      llmDecision.copy(adjudication = Some(adjudicationRef.copy(evidenceIds = Vector.empty))),
      "adjudication.evidenceIds"
    )
  }

  private def conflictLike: Vector[IdentifierConflict] =
    Vector(
      IdentifierConflict(
        IdentifierConflictKind.Disagreement,
        ExactIdScheme.DblpPid,
        Vector("00/0001"),
        Vector("00/0002"),
        None
      )
    )

  test("adjudication versions must equal the decision's provenance versions") {
    assertInvalid(
      llmDecision.copy(adjudication = Some(adjudicationRef.copy(policyVersion = "other-policy"))),
      "adjudication.policyVersion"
    )
    assertInvalid(
      llmDecision.copy(adjudication = Some(adjudicationRef.copy(modelVersion = "other-model"))),
      "provenance.modelVersion"
    )
    assertInvalid(
      llmDecision.copy(adjudication = Some(adjudicationRef.copy(promptVersion = "other-prompt"))),
      "provenance.promptVersion"
    )
    assertInvalid(
      matchDecision.copy(provenance =
        matchDecision.provenance.copy(modelVersion = Some(modelVersion))
      ),
      "provenance.modelVersion,promptVersion"
    )
    assertInvalid(
      llmDecision.copy(adjudication = Some(adjudicationRef.copy(inputHash = "B" * 64))),
      "adjudication.inputHash"
    )
  }

  test("evidence references cannot introduce identifiers the candidate does not carry") {
    def withEvidence(ev: String*) =
      llmDecision.copy(adjudication = Some(adjudicationRef.copy(evidenceIds = ev.toVector)))
    assertEquals(errs(withEvidence(llmDecision.candidateId)), Vector.empty)
    assertEquals(errs(withEvidence(s"exact-id:orcid:$orcidA")), Vector.empty)
    assertInvalid(withEvidence("feature:noSuchFeature"), "adjudication.evidenceIds[0]")
    assertInvalid(withEvidence(researcherB), "adjudication.evidenceIds[0]")
    assertInvalid(
      withEvidence("exact-id:doi:10.1234/example.2021.001"),
      "adjudication.evidenceIds[0]"
    )
    assertInvalid(withEvidence("exact-id:orcid:0000-0000-0000-0002"), "adjudication.evidenceIds[0]")
    assertInvalid(withEvidence("exact-id:no-such-scheme:x"), "adjudication.evidenceIds[0]")
    assertInvalid(withEvidence("free text"), "adjudication.evidenceIds[0]")
    assertInvalid(
      withEvidence(mention0.mentionId, "feature:nameSimilarity"),
      "adjudication.evidenceIds"
    )
    assertInvalid(
      llmDecision.copy(adjudication = Some(adjudicationRef.copy(reasonCodes = Vector("b", "a")))),
      "adjudication.reasonCodes"
    )
  }

  // ------------------------------------------------------------------ wire

  test("v2 decode rejects inconsistent JSON with typed errors") {
    val json = Json.write(llmDecision)
    val badScore = json.replace("\"score\":0.55", "\"score\":1.5")
    assert(
      Json
        .decode[MatchDecision](badScore)
        .left
        .exists(_.exists {
          case ValidationError.InvalidValue("score", _) => true
          case _ => false
        })
    )
    val badPath = json.replace("\"llm-adjudicated\"", "\"heuristic\"")
    assert(
      Json
        .decode[MatchDecision](badPath)
        .left
        .exists(_.exists(_.isInstanceOf[ValidationError.Undecodable]))
    )
    val badVerdict = json.replace("\"decision\":\"reject\"", "\"decision\":\"maybe\"")
    assert(
      Json
        .decode[MatchDecision](badVerdict)
        .left
        .exists(_.exists(_.isInstanceOf[ValidationError.Undecodable]))
    )
    val legacyKey = json.replace("\"conflicts\":[]", "\"conflictingTargetIds\":[]")
    assert(
      Json
        .decode[MatchDecision](legacyKey)
        .left
        .exists(_.exists(_.isInstanceOf[ValidationError.Undecodable])),
      "conflictingTargetIds is not a v2 key"
    )
    val exact = ujson.read(Json.write(matchDecision))
    exact.obj("adjudication") = ujson.read(upickle.default.write(adjudicationRef))
    assert(
      Json
        .decode[MatchDecision](ujson.write(exact))
        .left
        .exists(
          _.contains(
            ValidationError.InvalidValue("adjudication", "only allowed on the llm-adjudicated path")
          )
        ),
      "an adjudication on the exact-id path"
    )
  }
}
