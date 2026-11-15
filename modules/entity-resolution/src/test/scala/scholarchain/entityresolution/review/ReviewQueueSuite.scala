package scholarchain.entityresolution.review

import scholarchain.core.{Canonical, MatchOutcome}
import scholarchain.entityresolution.Samples
import scholarchain.entityresolution.Samples.{em, ep}
import scholarchain.entityresolution.policy.*
import scholarchain.entityresolution.policy.PolicyFixtures.*

/** Review-queue generation over SYNTHETIC and sample-mirroring fixtures (no labels anywhere). */
class ReviewQueueSuite extends munit.FunSuite {

  private val ada = new MatchFeaturesFixtures
  private val works = Vector(
    Samples.spannerDblp,
    Samples.spannerCrossref,
    Samples.cacmDblp,
    Samples.cacmCrossref,
    Samples.varajaoDblp,
    Samples.bigtableDblp
  )
  private val adaWorks = Vector(ada.adaA, ada.adaB).collect {
    case scholarchain.entityresolution.ResearcherEndpoint.OfMention(w, _) => w
  }
  private val refs =
    EndpointRef.index(works ++ adaWorks, Vector(Samples.popperOpenAlex, Samples.popperOrcid))

  private val outcomes: Vector[ResolutionOutcome] = Vector(
    decide(researcherCandidate(em(Samples.cacmDblp, 0), ep(Samples.popperOpenAlex))),
    decide(researcherCandidate(em(Samples.spannerDblp, 4), em(Samples.spannerCrossref, 4))),
    decide(researcherCandidate(em(Samples.spannerDblp, 1), em(Samples.bigtableDblp, 1))),
    decide(researcherCandidate(ada.adaA, ada.adaB)),
    decide(workCandidate(Samples.spannerDblp, Samples.spannerCrossref)),
    decide(workCandidate(Samples.cacmDblp, Samples.varajaoDblp)),
    decide(syntheticResearcher(nameOnly)) // endpoints of invented works: untraceable here
  )

  private def dev(
      os: Vector[ResolutionOutcome],
      rs: Map[String, EndpointRef],
      d: SamplingDesign
  ): ReviewQueueBuild =
    ReviewQueue
      .buildDevDiagnostics(policy, os, rs, d, SelectionUnits.Empty)
      .fold(e => fail(e.mkString("; ")), identity)

  private val build = dev(outcomes, refs, SamplingDesign(240, 10, "test"))

  test("every row traces to both source records, the candidate, and every version") {
    assertEquals(build.rows.size, 6)
    build.rows.foreach { r =>
      val o = outcomes.find(_.candidateId == r.candidateId).get
      assertEquals(r.schemaVersion, ReviewQueue.RowSchemaVersion)
      assertEquals((r.sourceIdA, r.sourceIdB), (o.sourceIdA, o.sourceIdB))
      assertEquals(r.endpointA.endpointId, o.sourceIdA)
      assertEquals(r.endpointB.endpointId, o.sourceIdB)
      assertEquals(r.endpointA.sourceDataset, o.sourceDatasetA.tag)
      assertEquals(r.endpointB.sourceDataset, o.sourceDatasetB.tag)
      Vector(r.endpointA, r.endpointB).foreach { e =>
        assert(e.sourceRecordId.nonEmpty && e.snapshot.nonEmpty && e.recordRunId.startsWith("run:"))
        assert(e.sourceSchemaVersion.endsWith("-raw/1.0.0"), e.sourceSchemaVersion)
        assert(e.displayText.nonEmpty)
      }
      assertEquals(r.candidateRunId, candidateRunId)
      assertEquals(r.candidateSnapshot, "dblp-2026-10-03")
      assertEquals(
        (r.blockingVersion, r.featureVersion, r.policyVersion),
        ("er-blocking.v2", "er-features.v2", "er-policy.v8")
      )
      assertEquals(r.system.outcome, o.outcome.tag)
      assertEquals(r.system.path, o.path.tag)
      assertEquals(r.system.rulesFired, o.rulesFired)
      assertEquals(r.system.conflicts, o.conflicts)
      assertEquals(
        Right(r.memberId),
        GoldMembership.memberId(o.entityKind.tag, o.sourceIdA, o.sourceIdB)
      )
      assertEquals(r.rowId, ReviewQueue.rowId(r.memberId, "er-policy.v8"))
      // ER-P5-11: every generated row records its effective thresholds and its score gate.
      assertEquals(r.system.thresholds, Some(DecisionThresholds.of(policy)))
      assertEquals(r.system.scoreGate, o.scoreGate)
      assert(r.system.scoreGate.isDefined, r.rowId)
    }
    val mention = build.rows
      .flatMap(r => Vector(r.endpointA, r.endpointB))
      .find(_.endpointKind == "mention")
      .get
    assert(mention.workId.isDefined && mention.position.isDefined)
  }

  test("generated rows carry blank (null) label fields and never a label") {
    build.rows.foreach(r =>
      assertEquals((r.label, r.labeler, r.labeledAt, r.labelNotes), (None, None, None, None))
    )
    val jsonl = ReviewQueue.toJsonl(build.rows)
    jsonl.split('\n').foreach { line =>
      assert(line.contains("\"label\":null"), line)
      assert(line.contains("\"labeler\":null") && line.contains("\"labeledAt\":null"))
      assert(line.contains("\"labelNotes\":null"))
    }
  }

  test("untraceable candidates are reported, never queued") {
    val synthetic = outcomes.last.candidateId
    assertEquals(build.untraceable.map(_._1), Vector(synthetic))
    assert(build.untraceable.head._2.contains("no source record"))
    assert(!build.rows.exists(_.candidateId == synthetic))
  }

  test("the queue covers the decision outcomes it can, and reports cells it cannot") {
    val outcomesSeen = build.rows.map(r => (r.entityKind, r.system.outcome)).toSet
    assert(outcomesSeen.contains(("researcher", "ACCEPTED")))
    assert(outcomesSeen.contains(("researcher", "CONFLICT")))
    assert(outcomesSeen.contains(("work", "ACCEPTED")))
    assertEquals(build.cells.size, ReviewQueue.RequiredCells.size)
    def cell(k: String, c: String) = build.cells.find(x => x.entityKind == k && x.cell == c).get
    // Two different CACM 2021 papers with different DOIs: the disagreement is on a pair that is
    // not a plausible identity match, so er-policy.v2 rejects it (er-policy.v1 gave CONFLICT).
    assertEquals(cell("work", "REJECTED").available, 1)
    assertEquals(cell("work", "CONFLICT").available, 0)
    assertEquals(cell("work", "UNRESOLVED").available, 0, "absent cells are reported as 0")
  }

  test("the build is deterministic and independent of input order") {
    val again = dev(outcomes.reverse, refs, SamplingDesign(240, 10, "test"))
    assertEquals(again.rows, build.rows)
    assertEquals(again.strata, build.strata)
    assertEquals(ReviewQueue.toJsonl(again.rows), ReviewQueue.toJsonl(build.rows))
  }

  /** SYNTHETIC: many outcomes in three strata with traceable invented endpoints. */
  private def synthetic(n: Int, base: ResolutionOutcome, tag: String): Vector[ResolutionOutcome] =
    (0 until n).toVector.map(i =>
      base.copy(candidateId = "mcand:" + Canonical.hashFields(tag, i.toString))
    )

  private val synRefs: Map[String, EndpointRef] = Vector(mentionA, mentionB).map { id =>
    id -> EndpointRef(
      id,
      "mention",
      "dblp",
      "journals/example/Syn",
      "dblp-2026-10-03",
      "dblp-raw/1.0.0",
      candidateRunId,
      Some("work:dblp:journals/example/Syn"),
      Some(0),
      Vector("SYNTHETIC")
    )
  }.toMap

  test("stratified allocation: a minimum per stratum, then the budget in proportion") {
    val band = decide(syntheticResearcher(nameOnly))
    val reject = decide(syntheticResearcher(Seq("name.family_key_agree" -> 1.0)))
    val accept = decide(
      syntheticResearcher(nameOnly ++ Seq("coauthor.jaccard" -> 0.5, "coauthor.shared" -> 3.0))
    )
    val all = synthetic(50, reject, "r") ++ synthetic(5, band, "b") ++ synthetic(1, accept, "a")
    val b = dev(all, synRefs, SamplingDesign(20, 3, "seed"))
    val by = b.strata.map(s => s.stratum -> (s.available, s.selected)).toMap
    assertEquals(b.rows.size, 20)
    assertEquals(by(ReviewQueue.stratumOf(policy, reject)), (50, 15))
    assertEquals(by(ReviewQueue.stratumOf(policy, band)), (5, 4))
    assertEquals(by(ReviewQueue.stratumOf(policy, accept)), (1, 1))
    assertEquals(b.strata.find(_.available == 1).get.shortfall, 2)
    // Within a stratum the order is SHA-256(seed, candidateId): another seed picks other rows.
    val other = dev(all, synRefs, SamplingDesign(20, 3, "other-seed"))
    assertNotEquals(other.rows.map(_.candidateId).toSet, b.rows.map(_.candidateId).toSet)
    assertEquals(other.rows.size, 20)
    val llm = b.cells.find(c => c.entityKind == "researcher" && c.cell == "llm-band").get
    assertEquals((llm.available, llm.selected), (5, 4))
  }

  test("a target above availability selects everything and reports the shortfall") {
    val reject = decide(syntheticResearcher(Seq("name.family_key_agree" -> 1.0)))
    val b = dev(synthetic(7, reject, "x"), synRefs, SamplingDesign.Default)
    assertEquals(b.rows.size, 7)
    assertEquals(b.totalShortfall, SamplingDesign.Default.targetTotal - 7)
    assert(SamplingDesign.Default.targetTotal >= 200)
  }

  test("the outcome-stratified builder is dev-only: it refuses held-out endpoints") {
    val heldout = SyntheticUnits.of(
      Vector(
        ("syn-held", "syn-crit", "heldout", Vector(Samples.spannerDblp.workId)),
        ("syn-dev", "syn-crit", "dev", Vector(Samples.cacmDblp.workId))
      )
    )
    val refused = ReviewQueue.buildDevDiagnostics(
      policy,
      outcomes,
      refs,
      SamplingDesign(240, 10, "test"),
      heldout
    )
    assert(refused.isLeft)
    assert(refused.left.toOption.get.exists(_.contains("heldout unit syn-held")), refused.toString)
    val devOnly = outcomes.filterNot(o =>
      Vector(o.sourceIdA, o.sourceIdB)
        .flatMap(refs.get)
        .exists(_.workId.contains(Samples.spannerDblp.workId))
    )
    assert(
      ReviewQueue
        .buildDevDiagnostics(policy, devOnly, refs, SamplingDesign(240, 10, "test"), heldout)
        .isRight
    )
  }

  test("score bands follow the policy thresholds and band") {
    assertEquals(ScoreBand.of(policy, 0.1), ScoreBand.AtOrBelowReject)
    assertEquals(ScoreBand.of(policy, 0.3), ScoreBand.BelowLlmBand)
    assertEquals(ScoreBand.of(policy, 0.5), ScoreBand.InLlmBand)
    assertEquals(ScoreBand.of(policy, 0.8), ScoreBand.AboveLlmBand)
    assertEquals(ScoreBand.of(policy, 0.9), ScoreBand.AtOrAboveAccept)
    assert(outcomes.exists(_.outcome == MatchOutcome.Conflict))
  }
}
