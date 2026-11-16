package scholarchain.core

import scala.collection.immutable.SortedMap

import AnalysisFixtures.*

/** Record contracts of the Phase 6 analysis outputs (DE-P6-02, C1-C6), synthetic fixtures only. */
class AnalysisContractSuite extends munit.FunSuite {

  private def refused(errs: Vector[ValidationError], fragment: String): Unit =
    assert(
      errs.exists(_.message.contains(fragment)),
      s"expected a refusal containing '$fragment', got ${errs.map(_.message)}"
    )

  private def roundTrip[T](label: String, xs: Vector[T])(using c: RecordContract[T]): Unit =
    xs.foreach { x =>
      assertEquals(c.validate(x), Vector.empty, s"$label $x")
      assertEquals(Json.decode[T](Json.write(x)), Right(x), label)
    }

  test("every fixture record is valid and round-trips through JSON") {
    roundTrip("metrics", metrics)
    roundTrip("edges", edges)
    roundTrip("components", components)
    roundTrip("communities", communities)
    roundTrip("bridges", bridges)
    roundTrip("coreness", coreness)
    roundTrip("paths", paths)
    roundTrip("windows", windows)
    roundTrip("summaryInputs", summaryInputs)
  }

  test("unknown keys are rejected at the top level and in nested values") {
    val json = Json.write(metricA1)
    assert(Json.decode[ResearcherMetricsV2](json.replaceFirst("\\{", "{\"x\":1,")).isLeft)
    assert(
      Json
        .decode[ResearcherMetricsV2](
          json.replace("\"groupingEvaluation\":", "\"x\":1,\"groupingEvaluation\":")
        )
        .isLeft,
      "lineage"
    )
    assert(
      Json
        .decode[ResearcherMetricsV2](
          json.replace("\"collisionCodes\":", "\"quality\":1,\"collisionCodes\":")
        )
        .isLeft,
      "identity: no extra field, such as a quality value, can be smuggled in"
    )
  }

  // ---------------------------------------------------------------- C1

  test("C1: W_r and C_r count identified coauthors only; T_r includes unresolved ones") {
    // A in 2015-2019: T = 2 (one identified, one unresolved coauthor on g1), U = 1, W = 1, C = 1.
    assertEquals(metricA1.totalCoauthorOccurrences, 2L)
    assertEquals(metricA1.weightedDegree, 1L)
    refused(
      ResearcherMetricsV2.contract.validate(metricA1.copy(weightedDegree = 2)),
      "W_r counts identified coauthor occurrences only"
    )
    refused(
      ResearcherMetricsV2.contract.validate(metricA1.copy(distinctCoauthorCount = 2)),
      "C_r counts identified coauthors only"
    )
    refused(
      ResearcherMetricsV2.contract.validate(metricA1.copy(unresolvedCoauthorOccurrences = 3)),
      "exceeds totalCoauthorOccurrences"
    )
    refused(
      ResearcherMetricsV2.contract.validate(metricA1.copy(worksWithUnresolvedCoauthors = 0)),
      "unresolved occurrences lie on at least one work"
    )
  }

  test(
    "C1: A_r is exactly T_r / P_r; M_r is at least A_r; P_r >= 1; works are counted DBLP works"
  ) {
    refused(
      ResearcherMetricsV2.contract.validate(metricB1.copy(averageCoauthorsPerPaper = 1.4999)),
      "A_r = T_r / P_r"
    )
    refused(
      ResearcherMetricsV2.contract.validate(metricB1.copy(maximumCoauthorsOnOnePaper = 1)),
      "M_r is below the average"
    )
    refused(
      ResearcherMetricsV2.contract.validate(metricE2.copy(publicationCount = 0)),
      "must be >= 1"
    )
    refused(
      ResearcherMetricsV2.contract.validate(
        metricE2.copy(maximumTeamWorkIds = Vector("work:openalex:W2"))
      ),
      "not a DBLP work"
    )
    refused(
      ResearcherMetricsV2.contract.validate(
        metricC2.copy(maximumTeamWorkIds = metricC2.maximumTeamWorkIds.reverse)
      ),
      "sorted by UTF-8 bytes"
    )
    assertEquals(ResearcherMetricsV2.average(3, 2), 1.5)
  }

  // ---------------------------------------------------------------- C5

  test("C5: unknown confidence is explicit: a value or a missing reason is refused") {
    val c = DecisionConfidence.unknown(ConfidenceUnknownReason.NoSupportingDecision)
    assertEquals(DecisionConfidence.check("c", c), Vector.empty)
    refused(DecisionConfidence.check("c", c.copy(value = Some(0.0))), "never a default number")
    refused(DecisionConfidence.check("c", c.copy(unknownReason = None)), "c.unknownReason: missing")
    val m = DecisionConfidence.measured(0.97)
    refused(DecisionConfidence.check("c", m.copy(value = None)), "c.value: missing")
    refused(DecisionConfidence.check("c", m.copy(value = Some(1.5))), "outside [0,1]")
    refused(
      DecisionConfidence
        .check("c", m.copy(unknownReason = Some(ConfidenceUnknownReason.NoSupportingDecision))),
      "only for kind unknown"
    )
  }

  test("C5: an absent confidence field is never read as known") {
    val json = Json.write(metricB1)
    assert(
      json.contains(
        "\"lowestDecisionConfidence\":{\"kind\":\"unknown\",\"value\":null,\"unknownReason\":\"no-supporting-decision\"}"
      )
    )
    val measured = Json.write(metricA1)
    assert(measured.contains("\"kind\":\"measured\",\"value\":0.97,"))
    for (
      broken <- Vector(
        json.replace("\"kind\":\"unknown\",", ""),
        measured.replace("\"value\":0.97,", ""),
        json.replace(
          "\"lowestDecisionConfidence\":{\"kind\":\"unknown\",\"value\":null,\"unknownReason\":\"no-supporting-decision\"}",
          "\"lowestDecisionConfidence\":null"
        ),
        json.replace("\"kind\":\"unknown\"", "\"kind\":\"estimated\""),
        json.replace(
          "\"value\":null,\"unknownReason\":\"no-supporting-decision\"",
          "\"value\":0.5,\"unknownReason\":\"no-supporting-decision\""
        )
      )
    )
      assert(Json.decode[ResearcherMetricsV2](broken).isLeft, broken)
  }

  test(
    "C5: status rules: resolved is measured, singletons are unknown, t1-anchored uses orcid or dblp-pid"
  ) {
    refused(
      IdentityConfidence
        .check("i", idA.copy(lowestDecisionConfidence = idB.lowestDecisionConfidence)),
      "so the confidence is measured"
    )
    refused(IdentityConfidence.check("i", idA.copy(memberCount = 1)), "group of >= 2")
    refused(
      IdentityConfidence
        .check("i", idB.copy(lowestDecisionConfidence = DecisionConfidence.measured(0.9))),
      "a singleton has no supporting decision"
    )
    refused(
      IdentityConfidence.check("i", idC.copy(anchorNamespace = "openalex-author")),
      "t1-anchored"
    )
    refused(
      IdentityConfidence.check("i", idA.copy(collisionCodes = Vector("grouping.resolved"))),
      "must be a token starting with"
    )
  }

  test(
    "C5: derive carries status, anchor, member count, collision codes and the lowest decision confidence"
  ) {
    val conf = decisions.map(d => d.decisionId -> d.confidence).toMap
    def members(r: String) = researchers.filter(_.researcherId.contains(r))
    for ((r, exp) <- identityOf)
      assertEquals(IdentityConfidence.derive(r, members(r), conf.get), Right(exp), r)
    refused(
      IdentityConfidence.derive(rA, members(rA).take(1), conf.get).left.getOrElse(Vector.empty),
      "1 member(s) supplied, groupSize is 2"
    )
    refused(
      IdentityConfidence.derive(rA, members(rA), _ => None).left.getOrElse(Vector.empty),
      "has no confidence in the decision run"
    )
    refused(
      IdentityConfidence.derive(rA, Vector.empty, conf.get).left.getOrElse(Vector.empty),
      "no grouping member carries this researcher ID"
    )
    refused(
      IdentityConfidence.derive(rB, members(rA), conf.get).left.getOrElse(Vector.empty),
      "carries another researcher ID"
    )
  }

  // ---------------------------------------------------------------- C2

  test("C2: build orders the pair canonically, sorts works, and uses the v1 edge ID formula") {
    assertEquals(edgeAB.researcherA, rA)
    assertEquals(
      edgeCD.supportingWorkIds,
      Vector(g3, g6).sortWith((a, b) => Canonical.compareUtf8(a, b) < 0)
    )
    assertEquals(edgeCD.jointPublicationCount, 2L)
    assertEquals((edgeCD.firstCollaborationYear, edgeCD.lastCollaborationYear), (2021, 2023))
    val v1 = Fixtures.ok(
      CoauthorEdge.build(
        rA,
        rB,
        w1,
        1,
        2016,
        2016,
        Vector(g1),
        Fixtures.derivedProv(Fixtures.graphPolicy)
      )
    )
    assertEquals(
      edgeAB.edgeId,
      v1.edgeId,
      "v1 and v2 records of one pair, window and policy share the ID"
    )
  }

  test("C2: per-year weights, supporting works and edge ID must agree") {
    val c = CoauthorEdgeV2.contract
    refused(
      c.validate(edgeCD.copy(jointPublicationCount = 3)),
      "counts must sum to jointPublicationCount"
    )
    refused(
      c.validate(edgeCD.copy(publicationsByYear = edgeCD.publicationsByYear.reverse)),
      "strictly ascending"
    )
    refused(
      c.validate(edgeCD.copy(publicationsByYear = Vector(YearCount(2019, 1), YearCount(2023, 1)))),
      "outside the edge window"
    )
    refused(
      c.validate(edgeCD.copy(supportingWorkIds = Vector(g3))),
      "one supporting counted work per joint publication"
    )
    refused(
      c.validate(edgeAB.copy(supportingWorkIds = Vector("work:openalex:W1"))),
      "not a DBLP work"
    )
    refused(c.validate(edgeAB.copy(researcherA = rB, researcherB = rA)), "not in canonical order")
    refused(c.validate(edgeAB.copy(window = w2)), "expected coauthor:")
    refused(c.validate(edgeAB.copy(lastCollaborationYear = 2017)), "first and last years")
  }

  // ---------------------------------------------------------------- lineage and provenance

  test(
    "lineage and provenance: three distinct input runs, an unevaluated grouping, no model call"
  ) {
    val c = ComponentMembership.contract
    val x = components.head
    refused(
      c.validate(x.copy(lineage = lineage.copy(inputRunId = decisionRunId))),
      "three different runs"
    )
    refused(c.validate(x.copy(lineage = lineage.copy(codeVersion = "abc"))), "40-hex")
    refused(
      c.validate(x.copy(lineage = lineage.copy(matchPolicyVersion = "er-grouping.v1"))),
      "versioned separately"
    )
    refused(
      c.validate(x.copy(provenance = x.provenance.copy(runId = groupingRunId))),
      "differs from the grouping"
    )
    refused(
      c.validate(x.copy(provenance = x.provenance.copy(modelVersion = Some("m")))),
      "no model call"
    )
    refused(
      c.validate(x.copy(provenance = x.provenance.copy(sourceRecordId = rB))),
      s"must equal ${x.researcherId}"
    )
    refused(
      c.validate(x.copy(provenance = x.provenance.copy(policyVersion = None))),
      "provenance.policyVersion"
    )
    refused(
      c.validate(x.copy(provenance = Fixtures.dblpProv)),
      "provenance.sourceDataset"
    )
    refused(c.validate(x.copy(lineage = null)), "lineage: missing")
  }

  test("no record carries or implies a grouping quality claim") {
    assertEquals(GroupingEvaluation.values.toVector, Vector(GroupingEvaluation.Unevaluated))
    val words = Vector("quality", "precision", "recall", "accuracy", "\"f1\"", "\":\"evaluated\"")
    val jsons =
      Vector(Json.write(metricA1), Json.write(edgeAB), Json.write(pathAC), Json.write(profileB1))
    for (j <- jsons; w <- words)
      assert(!j.contains(w), s"'$w' in $j")
    assert(Json.write(metricA1).contains("\"groupingEvaluation\":\"unevaluated\""))
  }

  // ---------------------------------------------------------------- C3

  test("C3: component, community, bridge and coreness record rules") {
    refused(
      ComponentMembership.contract.validate(components.head.copy(componentSize = 0)),
      "must be >= 1"
    )
    refused(
      ComponentMembership.contract.validate(components.head.copy(componentId = "cc-1")),
      "component"
    )
    val cm = communities.head
    refused(
      CommunityMembership.contract.validate(
        cm.copy(parameters = SortedMap("max iterations" -> "1"))
      ),
      "key not a token"
    )
    refused(
      CommunityMembership.contract.validate(cm.copy(algorithm = "label propagation")),
      "not a token"
    )
    val b = bridgeB1
    refused(BridgeMeasure.contract.validate(b.copy(crossCommunityDegree = 3)), "exceeds degree")
    refused(BridgeMeasure.contract.validate(b.copy(participationCoefficient = 1.0)), "[0, 1)")
    refused(BridgeMeasure.contract.validate(b.copy(score = Double.NaN)), "not finite")
    refused(
      BridgeMeasure.contract.validate(b.copy(communitiesReached = 0)),
      "at least one community"
    )
    assertEquals(BridgeMeasure.participation(2, 2), 0.5)
    assertEquals(BridgeMeasure.participation(0, 0), 0.0)
    refused(Coreness.contract.validate(corenessB1.copy(coreness = 3)), "exceeds degree")
  }

  test("C3/C6: evidence paths are bounded, canonical, and every hop names its coauthor edge") {
    val c = EvidencePath.contract
    val p = pathAC
    assertEquals(p.sourceResearcherId, rA)
    assertEquals(p.hops.map(_.edgeId), Vector(edgeAB.edgeId, edgeBC.edgeId))
    refused(c.validate(p.copy(hops = p.hops.reverse)), "hops[0].edgeId: expected")
    refused(c.validate(p.copy(bounds = p.bounds.copy(maxHops = 1))), "exceeds the bound 1")
    refused(c.validate(p.copy(ordinal = 2)), "at most 2 path(s) per pair")
    refused(
      c.validate(
        p.copy(hops =
          p.hops.updated(
            0,
            p.hops(0)
              .copy(evidenceWorkIds =
                Vector(g1, g2, g3).sortWith((a, b) => Canonical.compareUtf8(a, b) < 0)
              )
          )
        )
      ),
      "at most 2 evidence work(s) per hop"
    )
    refused(c.validate(p.copy(nodeIdentities = p.nodeIdentities.take(2))), "one identity per node")
    refused(c.validate(p.copy(nodeIds = Vector(rA, rC, rB))), "must run from sourceResearcherId")
    refused(
      c.validate(p.copy(sourceResearcherId = rC, targetResearcherId = rA)),
      "not in canonical order"
    )
    refused(c.validate(p.copy(hopCount = 3)), "a path of n hops has n + 1 nodes")
    refused(c.validate(p.copy(pathId = pathCD.pathId)), "pathId: expected")
  }

  // ---------------------------------------------------------------- C4

  test("C4: window records: counts are consistent and the subject names the window") {
    assertEquals(window1.provenance.sourceRecordId, "window:2015-2019")
    refused(
      AnalysisWindow.contract.validate(window1.copy(componentCount = 4)),
      "exceeds researcherCount"
    )
    refused(
      AnalysisWindow.contract.validate(window1.copy(countedWorkCount = 0)),
      "imply counted works"
    )
    refused(
      AnalysisWindow.contract.validate(window1.copy(window = TimeWindow(2019, 2015))),
      "exceeds end year"
    )
  }

  // ---------------------------------------------------------------- C6

  test(
    "C6: summary inputs: analysis document types, typed subjects, cited evidence, explicit identity"
  ) {
    val c = SemanticSummaryInput.contract
    refused(
      c.validate(profileB1.copy(documentType = DocumentType.WorkAbstract)),
      "is not an analysis summary"
    )
    refused(c.validate(profileB1.copy(identity = None)), "identity: missing")
    refused(
      c.validate(collaborationAB1.copy(identity = Some(idA))),
      "only for a researcher subject"
    )
    refused(c.validate(collaborationAB1.copy(subjectId = rA)), "subjectId")
    refused(
      c.validate(profileB1.copy(facts = profileB1.facts.map(_.copy(evidence = Vector.empty)))),
      "evidence: missing"
    )
    refused(
      c.validate(
        profileB1.copy(facts =
          Vector(
            SummaryFact("x", "1", Vector(SummaryEvidenceRef(SummaryEvidenceKind.CoauthorEdge, rB)))
          )
        )
      ),
      "malformed coauthor ID"
    )
    refused(
      c.validate(profileB1.copy(facts = profileB1.facts ++ profileB1.facts)),
      "contains duplicate values"
    )
    refused(c.validate(profileB1.copy(window = w2)), "inputId: expected")
    refused(c.validate(profileB1.copy(facts = Vector.empty)), "facts: missing")
  }
}
