package scholarchain.entityresolution.policy

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.collection.immutable.SortedMap

import scholarchain.core.*

import PolicyFixtures.*

/**
 * er-policy.v3 (ER-P5-07, human decisions of 2026-11-06): the narrow precedence, the per-scheme
 * dispositions, P-6, and decision 4 (cluster-ID agreement needs corroborating publication-stage
 * evidence). Every identifier and name here is SYNTHETIC; none of this is gold data.
 */
class PolicyV3Suite extends munit.FunSuite {

  // SYNTHETIC identifiers (checksum-valid test ORCIDs, invented keys and IDs).
  private val orcid1 = ExactId(ExactIdScheme.Orcid, "0000-0000-0000-0001")
  private val oaA1 = ExactId(ExactIdScheme.OpenAlexAuthor, "A5000000001")
  private val oaA2 = ExactId(ExactIdScheme.OpenAlexAuthor, "A5000000002")
  private val s2a1 = ExactId(ExactIdScheme.SemanticScholarAuthor, "1741101")
  private val s2a2 = ExactId(ExactIdScheme.SemanticScholarAuthor, "1741102")
  private val doi1 = ExactId(ExactIdScheme.Doi, "10.9999/example.1")
  private val doi2 = ExactId(ExactIdScheme.Doi, "10.9999/example.2")
  private val arxivDoi = ExactId(ExactIdScheme.Doi, "10.48550/arxiv.2101.00001")
  private val arx = ExactId(ExactIdScheme.Arxiv, "2101.00001")
  private val dk1 = ExactId(ExactIdScheme.DblpKey, "journals/example/SynA")
  private val dk2 = ExactId(ExactIdScheme.DblpKey, "conf/example/SynB")
  private val sc1 = ExactId(ExactIdScheme.ScopusEid, "2-s2.0-85000000001")
  private val sc2 = ExactId(ExactIdScheme.ScopusEid, "2-s2.0-85000000002")
  private val w1 = ExactId(ExactIdScheme.OpenAlexWork, "W1000000001")
  private val w2 = ExactId(ExactIdScheme.OpenAlexWork, "W1000000002")
  private val mag1 = ExactId(ExactIdScheme.Mag, "2963403868")
  private val mag2 = ExactId(ExactIdScheme.Mag, "2963403869")
  private val corpus1 = ExactId(ExactIdScheme.SemanticScholarCorpus, "215416146")
  private val corpus2 = ExactId(ExactIdScheme.SemanticScholarCorpus, "215416147")
  private val paper1 =
    ExactId(ExactIdScheme.SemanticScholarPaper, "0123456789abcdef0123456789abcdef01234567")
  private val paper2 =
    ExactId(ExactIdScheme.SemanticScholarPaper, "0123456789abcdef0123456789abcdef01234568")

  private val strongName: Seq[(String, Double)] =
    nameOnly ++ Seq("coauthor.jaccard" -> 1.0, "coauthor.shared" -> 4.0)
  private val sameTitle: Seq[(String, Double)] =
    Seq("title.exact" -> 1.0, "title.sim" -> 1.0, "title.token_jaccard" -> 1.0)
  private val otherTitle: Seq[(String, Double)] =
    Seq("title.sim" -> 0.3, "title.token_jaccard" -> 0.1)

  /** Title, year, and author sequence exact: the deterministic accept rule fires. */
  private val exactBib: Seq[(String, Double)] = sameTitle ++ Seq(
    "year.both" -> 1.0,
    "year.abs_diff" -> 0.0,
    "authors.both" -> 1.0,
    "authors.sequence_exact" -> 1.0
  )

  /** Every corroboration clause except `stage` (which comes from the identifiers). */
  private val sameStage: Seq[(String, Double)] = sameTitle ++ Seq(
    "venue.id_both" -> 1.0,
    "venue.id_agree" -> 1.0,
    "year.both" -> 1.0,
    "year.abs_diff" -> 0.0
  )

  private def disagreementSchemes(o: ResolutionOutcome): Set[String] =
    o.conflicts.filter(_.kind == IdentifierConflictKind.Disagreement).map(_.scheme.tag).toSet

  private def valid(o: ResolutionOutcome): MatchDecision =
    Resolver.toMatchDecision(o, ctx).fold(e => fail(e.mkString("; ")), identity)

  // ------------------------------------------------------------------ version, retirement, P-4/P-6

  test("the default policy is er-policy.v8 (v3 rules kept); v2 and v3 are refused, like v1") {
    assertEquals(policy.policyVersion, "er-policy.v8")
    assertEquals(policy.schemaVersion, "er-policy-file.v8")
    assertEquals(MatchPolicy.DefaultResource.split('/').last, "er-policy.v8.json")
    val v3 = MatchPolicy.resourceText(MatchPolicy.RetiredV3Resource).fold(e => fail(e), identity)
    assert(MatchPolicy.parse(v3).left.exists(_.exists(_.contains("retired"))))
    val v2 = MatchPolicy.resourceText(MatchPolicy.RetiredV2Resource).fold(e => fail(e), identity)
    assert(v2.contains("\"er-policy.v2\""), "the v2 history file is kept")
    val refused = MatchPolicy.parse(v2)
    assert(refused.left.exists(_.exists(_.contains("retired"))), refused.toString)
    // Even inside the current file schema, the retired policy version is refused by name.
    assert(
      MatchPolicy
        .validate(policy.copy(policyVersion = "er-policy.v2"))
        .exists(_.contains("retired"))
    )
  }

  test("P-6: the id.other.conflict weight is 0, so a record-key disagreement count never scores") {
    assertEquals(policy.work.weights.get("id.other.conflict"), Some(0.0))
    val f = SortedMap.from(sameTitle)
    val base = Scoring.score(policy.work, f)
    val counted = Scoring.score(policy.work, f + ("id.other.conflict" -> 2.0))
    assertEquals(counted.probability, base.probability)
    assert(!counted.contributions.exists(_.feature == "id.other.conflict"))
  }

  test("P-4: every confidence is described as a policy confidence constant (uncalibrated)") {
    assert(policy.confidenceDefinition.contains("policy confidence constant (uncalibrated)"))
    assert(
      MatchPolicy
        .validate(policy.copy(confidenceDefinition = "estimated probability"))
        .exists(_.contains("confidenceDefinition"))
    )
    val resolver = Files.readString(
      repoRoot.resolve(
        "modules/entity-resolution/src/main/scala/scholarchain/entityresolution/policy/Resolver.scala"
      ),
      StandardCharsets.UTF_8
    )
    assert(!resolver.contains("estimated probability"))
    assert(resolver.contains("policy confidence constant (uncalibrated)"))
    assert(!policy.calibrated)
  }

  test("thresholds, band and confidence constants are unchanged from v2 (uncalibrated)") {
    assertEquals(policy.thresholds, Thresholds(0.85, 0.2))
    assertEquals(policy.llmBand, LlmBand(0.4, 0.7))
    assertEquals(
      (
        policy.conflictConfidence,
        policy.implausibleDisagreementConfidence,
        policy.deterministicConfidence
      ),
      (0.99, 0.95, 0.95)
    )
  }

  // ------------------------------------------------------------------ disposition table

  test("the per-scheme disposition table, as approved") {
    def row(kp: KindPolicy, s: ExactIdScheme) = {
      val r = kp.rule(s).get
      (r.onAgreement, r.agreementConfidence, r.onDisagreement)
    }
    val r = policy.researcher
    val w = policy.work
    assertEquals(row(r, ExactIdScheme.Orcid), ("accept", 0.99, "conflict-if-plausible"))
    assertEquals(row(r, ExactIdScheme.DblpPid), ("accept", 0.99, "conflict-if-plausible"))
    assertEquals(
      row(r, ExactIdScheme.OpenAlexAuthor),
      ("accept", 0.9, "block-inferred-acceptance-if-plausible")
    )
    assertEquals(
      row(r, ExactIdScheme.SemanticScholarAuthor),
      ("accept", 0.9, "block-inferred-acceptance-if-plausible")
    )
    assertEquals(row(w, ExactIdScheme.Doi), ("accept", 0.99, "conflict-if-plausible"))
    assertEquals(row(w, ExactIdScheme.Arxiv), ("accept", 0.99, "conflict-if-plausible"))
    assertEquals(row(w, ExactIdScheme.DblpKey), ("accept", 0.99, "block-inferred-acceptance"))
    assertEquals(row(w, ExactIdScheme.ScopusEid), ("accept", 0.99, "block-inferred-acceptance"))
    Vector(
      ExactIdScheme.OpenAlexWork,
      ExactIdScheme.Mag,
      ExactIdScheme.SemanticScholarCorpus,
      ExactIdScheme.SemanticScholarPaper
    ).foreach(s =>
      assertEquals(row(w, s), ("accept-if-corroborated", 0.95, "evidence-only"), s.tag)
    )
    assertEquals(row(w, ExactIdScheme.RepositoryUrl), ("evidence-only", 0.0, "evidence-only"))
    (r.identifierRules ++ w.identifierRules).foreach(x =>
      assert(x.agreementRationale.trim.length > 10, x.scheme)
    )
  }

  test("README exact-identifier rules all still exist (DOI, arXiv, ORCID, OpenAlex, S2, repo)") {
    Vector(
      policy.work -> ExactIdScheme.Doi,
      policy.work -> ExactIdScheme.Arxiv,
      policy.researcher -> ExactIdScheme.Orcid,
      policy.researcher -> ExactIdScheme.OpenAlexAuthor,
      policy.work -> ExactIdScheme.OpenAlexWork,
      policy.researcher -> ExactIdScheme.SemanticScholarAuthor,
      policy.work -> ExactIdScheme.SemanticScholarCorpus,
      policy.work -> ExactIdScheme.SemanticScholarPaper,
      policy.work -> ExactIdScheme.RepositoryUrl
    ).foreach { case (kp, s) => assert(kp.rule(s).isDefined, s.tag) }
  }

  test("validation: accept-if-corroborated only for work cluster IDs; T1 must accept") {
    val r = policy.researcher
    val corroboratedAuthor = r.identifierRules.map(x =>
      if (x.scheme == "openalex-author") x.copy(onAgreement = "accept-if-corroborated") else x
    )
    assert(
      MatchPolicy
        .validate(policy.copy(researcher = r.copy(identifierRules = corroboratedAuthor)))
        .exists(_.contains("work schemes only"))
    )
    val clusterDoi = policy.work.identifierRules.map(x =>
      if (x.scheme == "doi") x.copy(onAgreement = "accept-if-corroborated") else x
    )
    val problems =
      MatchPolicy.validate(policy.copy(work = policy.work.copy(identifierRules = clusterDoi)))
    assert(problems.exists(_.contains("requires onDisagreement evidence-only")), problems.toString)
    assert(problems.exists(_.contains("T1 scheme must accept")), problems.toString)
    val oldName = r.identifierRules.map(x =>
      if (x.scheme == "openalex-author") x.copy(onDisagreement = "block-acceptance-if-plausible")
      else x
    )
    assert(
      MatchPolicy
        .validate(policy.copy(researcher = r.copy(identifierRules = oldName)))
        .exists(_.contains("onDisagreement must be one of"))
    )
  }

  // ------------------------------------------------------------------ ER-P5-03/04 examples

  test("4a: plausible pair, split OpenAlex A ID, no ORCID: UNRESOLVED, never the LLM") {
    val o = decide(syntheticResearcher(strongName, Vector(oaA1), Vector(oaA2)))
    assert(o.score.probability >= policy.thresholds.accept, "the score alone would accept")
    assertEquals((o.outcome, o.path), (MatchOutcome.Unresolved, MatchPath.Probabilistic))
    assertEquals(o.confidence, 0.0)
    assert(!o.llmBandEligible)
    assertEquals(disagreementSchemes(o), Set("openalex-author"))
    assert(o.reasonCodes.contains("algorithmic-id-disagreement.openalex-author"))
    assert(
      o.reasonCodes.contains(
        "block-inferred-acceptance-if-plausible.openalex-author.blocks.probabilistic"
      ),
      o.reasonCodes.toString
    )
    assert(o.rulesFired.contains(Rules.acceptBlocked(MatchPath.Probabilistic)))
    assertNotEquals(o.outcome, MatchOutcome.Conflict)
    assertEquals(valid(o).decision, MatchOutcome.Unresolved)
  }

  test("4b: as 4a plus the same ORCID: ACCEPTED via ORCID, override reason code and rule") {
    val o = decide(syntheticResearcher(strongName, Vector(orcid1, oaA1), Vector(orcid1, oaA2)))
    assertEquals((o.outcome, o.path), (MatchOutcome.Accepted, MatchPath.ExactId))
    assertEquals(o.confidence, 0.99)
    assertEquals(o.conflicts, Vector.empty)
    assert(
      o.reasonCodes.contains(
        "block-inferred-acceptance-if-plausible.openalex-author.overridden-by.orcid"
      ),
      o.reasonCodes.toString
    )
    assert(
      o.rulesFired.contains("exact-id.override.openalex-author.by.orcid"),
      o.rulesFired.toString
    )
    assert(o.reasonCodes.contains("algorithmic-id-disagreement.openalex-author"))
    assertEquals(valid(o).conflicts, Vector.empty)
  }

  test("s2-author behaves as openalex-author: blocks, overridden by ORCID, never CONFLICT alone") {
    val split = decide(syntheticResearcher(strongName, Vector(s2a1), Vector(s2a2)))
    assertEquals(split.outcome, MatchOutcome.Unresolved)
    assertEquals(disagreementSchemes(split), Set("s2-author"))
    val over = decide(syntheticResearcher(strongName, Vector(orcid1, s2a1), Vector(orcid1, s2a2)))
    assertEquals(over.outcome, MatchOutcome.Accepted)
    assert(
      over.reasonCodes.contains(
        "block-inferred-acceptance-if-plausible.s2-author.overridden-by.orcid"
      )
    )
  }

  test(
    "5a: preprint vs proceedings with different dblp-keys and DOIs: CONFLICT on [dblp-key, doi]"
  ) {
    val o = decide(syntheticWork(sameTitle, Vector(dk1, arxivDoi), Vector(dk2, doi2)))
    assertEquals((o.outcome, o.path), (MatchOutcome.Conflict, MatchPath.ExactId))
    assertEquals(disagreementSchemes(o), Set("dblp-key", "doi"))
    assert(o.reasonCodes.contains("authoritative-id-disagreement.doi"))
    assert(o.reasonCodes.contains("record-key-disagreement.dblp-key"))
    assert(o.rulesFired.contains(Rules.exactConflict("doi")))
    assert(!o.rulesFired.contains(Rules.exactConflict("dblp-key")), "the DOI decides, not the key")
    assertEquals(valid(o).decision, MatchOutcome.Conflict)
  }

  test("5b: dblp-keys differ, arXiv ID on one side only: no CONFLICT; inferred accept blocked") {
    val det = decide(syntheticWork(exactBib, Vector(dk1, arx), Vector(dk2)))
    assertEquals(
      (det.outcome, det.path),
      (MatchOutcome.Unresolved, MatchPath.DeterministicBibliographic)
    )
    assertEquals(disagreementSchemes(det), Set("dblp-key"))
    assert(!det.llmBandEligible)
    val scored = decide(syntheticWork(sameTitle, Vector(dk1, arx), Vector(dk2)))
    assertNotEquals(scored.outcome, MatchOutcome.Conflict)
    assertNotEquals(scored.outcome, MatchOutcome.Accepted)
    assert(!scored.llmBandEligible)
  }

  test("6: conference vs journal, different DOIs and W IDs: CONFLICT on [doi] only") {
    val o = decide(syntheticWork(sameTitle, Vector(doi1, w1), Vector(doi2, w2)))
    assertEquals(o.outcome, MatchOutcome.Conflict)
    assertEquals(disagreementSchemes(o), Set("doi"))
    assert(o.reasonCodes.contains("evidence-only-id-disagreement.openalex-work"))
    assert(!o.conflicts.exists(_.scheme == ExactIdScheme.OpenAlexWork))
  }

  test("7a: two dblp-keys, same DOI: the DOI agreement overrides the dblp-key block") {
    val o = decide(syntheticWork(sameTitle, Vector(dk1, doi1), Vector(dk2, doi1)))
    assertEquals((o.outcome, o.path), (MatchOutcome.Accepted, MatchPath.ExactId))
    assertEquals(o.confidence, 0.99)
    assertEquals(o.conflicts, Vector.empty)
    assert(o.reasonCodes.contains("block-inferred-acceptance.dblp-key.overridden-by.doi"))
    assert(o.rulesFired.contains("exact-id.override.dblp-key.by.doi"))
    assert(o.reasonCodes.contains("record-key-disagreement.dblp-key"))
    assertEquals(valid(o).decision, MatchOutcome.Accepted)
  }

  test("a scopus-eid block is overridden by an arXiv agreement, with the reason code") {
    val o = decide(syntheticWork(sameTitle, Vector(sc1, arx), Vector(sc2, arx)))
    assertEquals(o.outcome, MatchOutcome.Accepted)
    assert(o.reasonCodes.contains("block-inferred-acceptance.scopus-eid.overridden-by.arxiv"))
    assert(o.rulesFired.contains("exact-id.override.scopus-eid.by.arxiv"))
  }

  test(
    "7b: two dblp-keys, no DOI, identical title/year/authors: UNRESOLVED (deterministic blocked)"
  ) {
    val o = decide(syntheticWork(exactBib, Vector(dk1), Vector(dk2)))
    assertEquals(
      (o.outcome, o.path),
      (MatchOutcome.Unresolved, MatchPath.DeterministicBibliographic)
    )
    assertEquals(o.conflicts.map(_.scheme.tag), Vector("dblp-key"))
    assert(
      o.reasonCodes.contains(
        "block-inferred-acceptance.dblp-key.blocks.deterministic-bibliographic"
      )
    )
    assert(o.rulesFired.contains(Rules.acceptBlocked(MatchPath.DeterministicBibliographic)))
    assertEquals(o.plausibility, None, "a record-key disagreement never triggers plausibility")
    assertEquals(valid(o).decision, MatchOutcome.Unresolved)
  }

  test("8a: two W IDs, same DOI: ACCEPTED via DOI; the W disagreement is evidence only") {
    val o = decide(syntheticWork(sameTitle, Vector(w1, doi1), Vector(w2, doi1)))
    assertEquals((o.outcome, o.path), (MatchOutcome.Accepted, MatchPath.ExactId))
    assert(o.rulesFired.contains(Rules.exactAgree("doi")))
    assert(o.reasonCodes.contains("evidence-only-id-disagreement.openalex-work"))
    assertEquals(o.conflicts, Vector.empty)
  }

  test("8b: two W IDs, no DOI: the deterministic rule or the score decides; no CONFLICT") {
    // er-policy.v4: the deterministic rule accepts only at an established publication stage. With
    // the same venue (no preprint marker on either side) it accepts; without it the stage is
    // uncertain and the pair stays UNRESOLVED on the same path.
    val det = decide(syntheticWork(exactBib :+ ("venue.id_agree" -> 1.0), Vector(w1), Vector(w2)))
    assertEquals(
      (det.outcome, det.path, det.confidence),
      (MatchOutcome.Accepted, MatchPath.DeterministicBibliographic, 0.95)
    )
    val uncertain = decide(syntheticWork(exactBib, Vector(w1), Vector(w2)))
    assertEquals(
      (uncertain.outcome, uncertain.path),
      (MatchOutcome.Unresolved, MatchPath.DeterministicBibliographic)
    )
    assertEquals(uncertain.conflicts, Vector.empty)
    val scored = decide(syntheticWork(sameTitle, Vector(w1), Vector(w2)))
    assertEquals(scored.path, MatchPath.Probabilistic)
    assertEquals(scored.conflicts, Vector.empty)
    assertNotEquals(scored.outcome, MatchOutcome.Conflict)
  }

  // ------------------------------------------------------------------ record keys (P-2)

  test(
    "a record-key disagreement never yields CONFLICT, never REJECTED alone, never conflicts on ACCEPTED"
  ) {
    val profiles: Vector[Seq[(String, Double)]] = Vector(
      otherTitle,
      sameTitle,
      exactBib,
      Seq("title.sim" -> 0.6, "title.token_jaccard" -> 0.5),
      Seq.empty
    )
    for {
      kv <- profiles
      (x, y) <- Vector((dk1, dk2), (sc1, sc2))
    } {
      val without = decide(syntheticWork(kv))
      val o = decide(syntheticWork(kv, Vector(x), Vector(y)))
      val tag = s"${x.scheme.tag} $kv"
      assertNotEquals(o.outcome, MatchOutcome.Conflict, tag)
      assertEquals(o.plausibility, None, tag)
      assert(!o.rulesFired.exists(_.startsWith("exact-id.reject")), tag)
      // The key changes an outcome only by blocking an inferred acceptance.
      if (without.outcome == MatchOutcome.Accepted)
        assertEquals(o.outcome, MatchOutcome.Unresolved, tag)
      else assertEquals(o.outcome, without.outcome, tag)
      assert(!(o.outcome == MatchOutcome.Accepted && o.conflicts.nonEmpty), tag)
      assertEquals(o.conflicts.map(_.scheme), Vector(x.scheme), tag)
      assert(Resolver.toMatchDecision(o, ctx).isRight, tag)
    }
    // Overridden by a T1 agreement: ACCEPTED, the key is not in conflicts.
    val over = decide(syntheticWork(otherTitle, Vector(dk1, doi1), Vector(dk2, doi1)))
    assertEquals(over.outcome, MatchOutcome.Accepted)
    assertEquals(over.conflicts, Vector.empty)
  }

  test("a same-tier contradiction is CONFLICT: dblp-key disagreement plus scopus-eid agreement") {
    val o = decide(syntheticWork(sameTitle, Vector(dk1, sc1), Vector(dk2, sc1)))
    assertEquals(o.outcome, MatchOutcome.Conflict)
    assert(o.rulesFired.contains(Rules.IdentifierContradiction))
    assertEquals(disagreementSchemes(o), Set("dblp-key"))
    assert(o.conflicts.exists(c => c.kind == IdentifierConflictKind.ContradictedAgreement))
    assertEquals(valid(o).decision, MatchOutcome.Conflict)
  }

  // ------------------------------------------------------------------ decision 4: cluster IDs

  private val clusterPairs: Vector[(String, ExactId, ExactId)] = Vector(
    ("openalex-work", w1, w2),
    ("mag", mag1, mag2),
    ("s2-corpus", corpus1, corpus2),
    ("s2-paper", paper1, paper2)
  )

  test(
    "a cluster-ID agreement alone is UNRESOLVED with cluster-id-agreement-uncorroborated.<scheme>"
  ) {
    clusterPairs.foreach { case (scheme, id, _) =>
      val o = decide(syntheticWork(otherTitle, Vector(id), Vector(id)))
      assertEquals((o.outcome, o.path), (MatchOutcome.Unresolved, MatchPath.ExactId), scheme)
      assert(o.reasonCodes.contains(s"cluster-id-agreement-uncorroborated.$scheme"), scheme)
      assert(o.rulesFired.contains(Rules.ClusterUnresolved), scheme)
      assert(o.rulesFired.contains(s"exact-id.cluster-agreement.uncorroborated.$scheme"), scheme)
      assert(!o.llmBandEligible, scheme)
      assertEquals(o.confidence, 0.0)
      assertEquals(o.conflicts, Vector.empty)
      assertEquals(valid(o).decision, MatchOutcome.Unresolved)
      // Even the exact bibliographic rule does not accept an uncorroborated cluster agreement.
      val bib = decide(syntheticWork(exactBib, Vector(id), Vector(id)))
      assertEquals(bib.outcome, MatchOutcome.Unresolved, scheme)
      assert(bib.reasonCodes.contains("corroboration-clause-failed.venue"), scheme)
    }
  }

  test("a cluster-ID agreement with corroborating publication-stage evidence is ACCEPTED") {
    clusterPairs.foreach { case (scheme, id, _) =>
      val o = decide(syntheticWork(sameStage, Vector(id), Vector(id)))
      assertEquals((o.outcome, o.path), (MatchOutcome.Accepted, MatchPath.ExactId), scheme)
      assertEquals(o.confidence, 0.95)
      assert(o.rulesFired.contains(Rules.exactAgree(scheme)), scheme)
      assert(o.reasonCodes.contains(s"cluster-id-agreement-corroborated.$scheme"), scheme)
      assertEquals(o.corroboration.map(_.corroborated), Some(true))
      assertEquals(valid(o).decision, MatchOutcome.Accepted)
    }
  }

  test("preprint vs version of record sharing a cluster ID is never ACCEPTED") {
    clusterPairs.foreach { case (scheme, id, _) =>
      // The preprint side carries an arXiv ID only; the published side a DOI: stage differs.
      val arxivOnly = decide(syntheticWork(sameStage, Vector(id, arx), Vector(id, doi1)))
      assertEquals(arxivOnly.outcome, MatchOutcome.Unresolved, scheme)
      assert(arxivOnly.reasonCodes.contains("corroboration-clause-failed.stage"), scheme)
      // An arXiv DOI against a publisher DOI is a T1 DOI disagreement: CONFLICT.
      val dois = decide(syntheticWork(sameStage, Vector(id, arxivDoi), Vector(id, doi1)))
      assertEquals(dois.outcome, MatchOutcome.Conflict, scheme)
      // Different venues (preprint server vs journal) without markers: not corroborated.
      val venues = decide(
        syntheticWork(sameStage :+ ("venue.id_agree" -> 0.0), Vector(id), Vector(id))
      )
      assertEquals(venues.outcome, MatchOutcome.Unresolved, scheme)
      Vector(arxivOnly, dois, venues).foreach(o =>
        assertNotEquals(o.outcome, MatchOutcome.Accepted)
      )
    }
    // Two preprints of one line (both marked) may still corroborate.
    val bothPreprints = decide(syntheticWork(sameStage, Vector(w1, arx), Vector(w1, arxivDoi)))
    assertEquals(bothPreprints.outcome, MatchOutcome.Accepted)
  }

  test("corroboration bounds are the documented ones, at their edges") {
    val wc = policy.workCorroboration
    assertEquals(
      (wc.venueNameJaccardMin, wc.maxYearDiff, wc.titleSimMin, wc.preprintDoiPrefixes),
      (0.8, 0.0, 0.9, Vector("10.48550/"))
    )
    Vector("venueNameJaccardMin", "maxYearDiff", "titleSimMin", "preprintDoiPrefixes", "stage")
      .foreach(k => assert(wc.definition.contains(k), k))
    assert(wc.rationale.contains("preprint"))
    val yearTitle = Seq("year.both" -> 1.0, "year.abs_diff" -> 0.0, "title.exact" -> 1.0)
    def out(kv: Seq[(String, Double)]) = decide(syntheticWork(kv, Vector(w1), Vector(w1))).outcome
    val byName = yearTitle ++ Seq("venue.name_both" -> 1.0)
    assertEquals(out(byName :+ ("venue.name_jaccard" -> 0.8)), MatchOutcome.Accepted)
    assertEquals(out(byName :+ ("venue.name_jaccard" -> 0.7999)), MatchOutcome.Unresolved)
    val venue = Seq("venue.id_both" -> 1.0, "venue.id_agree" -> 1.0, "year.both" -> 1.0)
    assertEquals(out(venue ++ Seq("title.sim" -> 0.9)), MatchOutcome.Accepted)
    assertEquals(out(venue ++ Seq("title.sim" -> 0.8999)), MatchOutcome.Unresolved)
    assertEquals(out(venue ++ Seq("title.sim" -> 1.0)), MatchOutcome.Unresolved, "empty titles")
    assertEquals(
      out(venue ++ Seq("title.exact" -> 1.0, "year.abs_diff" -> 1.0)),
      MatchOutcome.Unresolved
    )
    assertEquals(
      out(Seq("venue.id_both" -> 1.0, "venue.id_agree" -> 1.0, "title.exact" -> 1.0)),
      MatchOutcome.Unresolved,
      "a missing year fails its clause"
    )
  }

  test("per-record identifiers (doi, arxiv, dblp-key, scopus-eid) accept without corroboration") {
    // er-policy.v8 (decisions-11 item 2): a record key (dblp-key, scopus-eid) also needs an
    // established publication stage, which is not corroboration; with no identifier stage marker
    // that is the venue clause (same-stage-venue). Without it the stage is uncertain: UNRESOLVED.
    val sameVenue = Seq("venue.id_both" -> 1.0, "venue.id_agree" -> 1.0)
    Vector(doi1 -> Seq.empty, arx -> Seq.empty, dk1 -> sameVenue, sc1 -> sameVenue).foreach {
      case (id, stage) =>
        val o = decide(syntheticWork(otherTitle ++ stage, Vector(id), Vector(id)))
        assertEquals((o.outcome, o.path), (MatchOutcome.Accepted, MatchPath.ExactId), id.scheme.tag)
        assertEquals(o.corroboration, None)
    }
    Vector(dk1, sc1).foreach { id =>
      val o = decide(syntheticWork(otherTitle, Vector(id), Vector(id)))
      assertEquals((o.outcome, o.path), (MatchOutcome.Unresolved, MatchPath.ExactId), id.scheme.tag)
      assert(o.reasonCodes.contains("record-key-agreement-blocked-by-publication-stage"))
      assertEquals(o.corroboration, None)
    }
  }

  test("a cluster-ID agreement with a T1 agreement accepts via the T1 scheme") {
    val o = decide(syntheticWork(otherTitle, Vector(w1, doi1), Vector(w1, doi1)))
    assertEquals(o.outcome, MatchOutcome.Accepted)
    assert(o.rulesFired.contains(Rules.exactAgree("doi")))
    assert(!o.rulesFired.contains(Rules.exactAgree("openalex-work")), "W did not decide")
    assert(o.reasonCodes.contains("cluster-id-agreement-uncorroborated.openalex-work"))
  }

  test("every v3 outcome above maps to a valid core decision; ACCEPTED never carries conflicts") {
    val all = Vector(
      syntheticResearcher(strongName, Vector(oaA1), Vector(oaA2)),
      syntheticResearcher(strongName, Vector(orcid1, oaA1), Vector(orcid1, oaA2)),
      syntheticWork(sameTitle, Vector(dk1, arxivDoi), Vector(dk2, doi2)),
      syntheticWork(exactBib, Vector(dk1, arx), Vector(dk2)),
      syntheticWork(sameTitle, Vector(doi1, w1), Vector(doi2, w2)),
      syntheticWork(sameTitle, Vector(dk1, doi1), Vector(dk2, doi1)),
      syntheticWork(exactBib, Vector(dk1), Vector(dk2)),
      syntheticWork(sameTitle, Vector(w1, doi1), Vector(w2, doi1)),
      syntheticWork(exactBib, Vector(w1), Vector(w2)),
      syntheticWork(otherTitle, Vector(w1), Vector(w1)),
      syntheticWork(sameStage, Vector(w1), Vector(w1))
    ).map(decide(_))
    all.foreach { o =>
      assert(!(o.outcome == MatchOutcome.Accepted && o.conflicts.nonEmpty), o.rulesFired.toString)
      assert(Resolver.toMatchDecision(o, ctx).isRight, o.rulesFired.toString)
    }
  }
}
