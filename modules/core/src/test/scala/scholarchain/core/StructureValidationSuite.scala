package scholarchain.core

import AnalysisFixtures.*
import Fixtures.{ok, researcherA, workId}
import StructureFixtures.*
import StructureValidation.*

/**
 * DE-P6-03 window structures, path-length estimates, temporal comparisons, summary inputs and load
 * manifests, on the synthetic DE-P6-02 run. Every expected value is hand-derived in
 * [[StructureFixtures]]; each counter-test changes one value and must be refused.
 */
class StructureValidationSuite extends munit.FunSuite {

  private def refused(errs: Vector[ValidationError], fragment: String)(using munit.Location): Unit =
    assert(
      errs.exists(_.message.contains(fragment)),
      s"expected a refusal containing '$fragment', got ${errs.map(_.message)}"
    )

  private val out = fullOutputs

  // ---------------------------------------------------------------- window structures (items 4, 6)

  test("window structures equal the hand-derived totals and pass every check") {
    assertEquals(checkWindowStructures(structures, out, settings, structureManifest), Vector.empty)
    assertEquals(structure2.largestComponentFraction, 2.0 / 3.0)
    assertEquals(structure1.largestComponentFraction, 1.0)
  }

  test("window structures: each count is recomputed from the per-researcher records") {
    def with1(f: WindowStructure => WindowStructure) =
      checkWindowStructures(Vector(f(structure1), structure2), out, settings, structureManifest)
    refused(with1(_.copy(crossCommunityEdgeCount = 0)), "crossCommunityEdgeCount: expected 1")
    refused(
      with1(_.copy(communityCount = 3, largestCommunitySize = 1)),
      "communityCount: expected 2"
    )
    refused(with1(_.copy(maxCoreness = 0)), "maxCoreness: expected 1")
    // Contract-valid but wrong: one component of all three researchers.
    val s2 = structure2.copy(largestComponentSize = 3, largestComponentFraction = 1.0)
    refused(
      checkWindowStructures(Vector(structure1, s2), out, settings, structureManifest),
      "largestComponentSize: expected 2"
    )
    refused(
      checkWindowStructures(Vector(structure2, structure1), out, settings, structureManifest),
      "one record per configured window"
    )
  }

  test(
    "window structures: label-propagation outcome bound to the manifest and the iteration bound"
  ) {
    refused(
      checkWindowStructures(structures, out, settings, analysisManifest),
      "must equal the analysis manifest parameter community.converged.2015-2019"
    )
    val notConverged = structure1.copy(communityConverged = false)
    refused(
      checkWindowStructures(Vector(notConverged, structure2), out, settings, structureManifest),
      "a non-converged window ran community.maxIterations (20) iterations"
    )
    refused(
      checkWindowStructures(
        Vector(structure1.copy(communityIterations = 21), structure2),
        out,
        settings,
        structureManifest
      ),
      "exceeds community.maxIterations 20"
    )
    assertEquals(
      AnalysisValidation.AnalysisManifestKeys.communityIterations(w2),
      "community.iterations.2020-2024"
    )
  }

  test("window-structure.v1 contract: fraction formula, component and community relations") {
    val c = WindowStructure.contract
    structures.foreach(s => assertEquals(c.validate(s), Vector.empty))
    refused(
      c.validate(structure2.copy(largestComponentFraction = 0.67)),
      "largestComponentFraction"
    )
    refused(c.validate(structure2.copy(singletonCount = 3)), "exceeds componentCount")
    refused(c.validate(structure2.copy(componentCount = 3)), "at least two researchers each")
    refused(
      c.validate(structure2.copy(communityCount = 1)),
      "every community lies inside one component"
    )
    refused(c.validate(structure1.copy(maxCoreness = 3)), "k + 1 researchers")
    refused(c.validate(structure1.copy(crossCommunityEdgeCount = 3)), "exceeds edgeCount")
    refused(c.validate(structure1.copy(communityAlgorithm = "has space")), "communityAlgorithm")
    refused(
      c.validate(
        structure1.copy(provenance = structure1.provenance.copy(sourceRecordId = "window:x"))
      ),
      "must equal window:2015-2019"
    )
    val empty = structure(w1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, true, 0)
    assertEquals(c.validate(empty), Vector.empty)
    assertEquals(empty.largestComponentFraction, 0.0)
  }

  // ---------------------------------------------------------------- path-length estimates (item 5)

  test(
    "path-length estimates: deterministic sample, hand-derived distances, exact for a full sample"
  ) {
    assertEquals(checkPathLengthEstimates(estimates, input, samplingSettings), Vector.empty)
    assertEquals(sampleW1.size, 2)
    assertEquals(
      sampleW1,
      ok(PathSampleKey.sample(7L, w1, Vector(rC, rB, rA), 2)),
      "member order is irrelevant"
    )
    assert(!estimateW1.exact)
    assertEquals(estimateW1.pairCount, 4L)
    assert(estimateW2.exact)
    assertEquals(
      (estimateW2.averageDistance, estimateW2.maxObservedDistance, estimateW2.effectiveDiameter90),
      (1.0, 1, 1)
    )
    // The full sample of the path A-B-C is exact: {1: 4, 2: 2}, average 8/6, diameter 2.
    val all3 = handPathCounts(Vector(rA, rB, rC))
    assertEquals(all3, Vector(DistanceCount(1, 4), DistanceCount(2, 2)))
    val exact = ok(
      PathLengthEstimate.build(
        w1,
        componentIdOf(rA, w1),
        3,
        7L,
        3,
        Vector(rA, rB, rC),
        all3,
        lineage,
        prov
      )
    )
    assertEquals(
      (exact.exact, exact.averageDistance, exact.maxObservedDistance),
      (true, 8.0 / 6.0, 2)
    )
    assertEquals(exact.effectiveDiameter90, 2, "10 * 4 < 9 * 6, 10 * 6 >= 9 * 6")
    assertEquals(
      StructureValidation.distanceCounts(
        Map(rA -> Set(rB), rB -> Set(rA, rC), rC -> Set(rB)),
        Vector(rA, rB, rC)
      ),
      all3
    )
  }

  test("path-length estimates: none without configuration; counter-tests detected") {
    refused(
      checkPathLengthEstimates(estimates, input, settings),
      "sets no analysis.pathLength* keys"
    )
    assertEquals(checkPathLengthEstimates(Vector.empty, input, settings), Vector.empty)
    refused(
      checkPathLengthEstimates(Vector(estimateW1), input, samplingSettings),
      "has >= 2 researchers but no estimate"
    )
    val other = Vector(rA, rB, rC).filterNot(sampleW1.contains)
    val wrongSources = ok(
      PathLengthEstimate.build(
        w1,
        componentIdOf(rA, w1),
        3,
        7L,
        2,
        Vector(sampleW1.head) ++ other,
        handPathCounts(Vector(sampleW1.head) ++ other),
        lineage,
        prov
      )
    )
    refused(
      checkPathLengthEstimates(Vector(wrongSources, estimateW2), input, samplingSettings),
      "expected the PathSampleKey sample"
    )
    // Contract-valid counts of the other possible sample, on the right sources.
    val oneHop3 = Vector(DistanceCount(1, 3), DistanceCount(2, 1))
    val oneHop2 = Vector(DistanceCount(1, 2), DistanceCount(2, 2))
    val alt = if (estimateW1.distanceCounts == oneHop3) oneHop2 else oneHop3
    val wrongCounts = ok(
      PathLengthEstimate.build(w1, componentIdOf(rA, w1), 3, 7L, 2, sampleW1, alt, lineage, prov)
    )
    refused(
      checkPathLengthEstimates(Vector(wrongCounts, estimateW2), input, samplingSettings),
      "distanceCounts: expected"
    )
  }

  test("path-length-estimate.v1 contract: derived fields and sample size") {
    val c = PathLengthEstimate.contract
    estimates.foreach(e => assertEquals(c.validate(e), Vector.empty))
    refused(c.validate(estimateW1.copy(averageDistance = 1.0)), "averageDistance: expected")
    refused(c.validate(estimateW1.copy(pairCount = 5)), "pairCount")
    refused(c.validate(estimateW1.copy(exact = true)), "exact")
    refused(c.validate(estimateW1.copy(sourceIds = Vector(rA))), "min(sampleSize, componentSize)")
    refused(c.validate(estimateW1.copy(method = "exact-apsp")), "method")
    refused(
      c.validate(estimateW2.copy(distanceCounts = Vector(DistanceCount(2, 2)))),
      "distances are 1..max without gaps"
    )
    refused(c.validate(estimateW2.copy(componentSize = 1)), "needs >= 2 researchers")
  }

  // ---------------------------------------------------------------- temporal comparison (item 9)

  test("temporal comparison: hand-derived pair, limitations and per-researcher changes pass") {
    assertEquals(checkTemporal(Vector(comparison), changes, out, input, settings), Vector.empty)
    assertEquals(windowPairs(settings), Vector(w1 -> w2))
  }

  test("temporal comparison: limitations are recomputed and stated") {
    val silent = comparison.copy(limitations = Vector(ComparisonLimitation.GroupingUnevaluated))
    refused(checkTemporal(Vector(silent), changes, out, input, settings), "limitations: expected")
    val notConverged = out.copy(windowStructures =
      Vector(structure1, structure2.copy(communityConverged = false, communityIterations = 20))
    )
    refused(
      checkTemporal(Vector(comparison), changes, notConverged, input, settings),
      "limitations: expected"
    )
    refused(
      checkTemporal(
        Vector(comparison),
        changes,
        out.copy(windowStructures = Vector.empty),
        input,
        settings
      ),
      "needs the window structures"
    )
    val c = WindowComparison.contract
    assertEquals(c.validate(comparison), Vector.empty)
    refused(c.validate(comparison.copy(inclusionRuleCompatible = false)), "one analysis run only")
    refused(
      c.validate(silent.copy(limitations = Vector.empty)),
      "grouping-unevaluated is always stated"
    )
    refused(c.validate(comparison.copy(overlapYears = 1)), "overlapYears: expected 0")
    val overlapping = comparison.copy(toWindow = TimeWindow(2018, 2022), overlapYears = 2)
    refused(c.validate(overlapping), "overlapping-windows is stated exactly when")
    refused(
      c.validate(comparison.copy(limitations = comparison.limitations.reverse)),
      "must be sorted"
    )
  }

  test("temporal comparison: counts and changes are recomputed from the run's records") {
    refused(
      checkTemporal(Vector(comparison.copy(edgesOnlyTo = 0)), changes, out, input, settings),
      "edgesOnlyTo: expected 1"
    )
    refused(
      checkTemporal(
        Vector(comparison),
        changes.filterNot(_.researcherId == rE),
        out,
        input,
        settings
      ),
      s"$rE has a metrics record"
    )
    val wrongTurnover = changeC.copy(retainedCoauthors = 1, gainedCoauthors = 0, lostCoauthors = 0)
    refused(
      checkTemporal(
        Vector(comparison),
        changes.map(c => if (c == changeC) wrongTurnover else c),
        out,
        input,
        settings
      ),
      "retainedCoauthors: expected 0"
    )
    val wrongOverlap = changeC.copy(communityOverlap = Some(2))
    refused(
      checkTemporal(
        Vector(comparison),
        changes.map(c => if (c == changeC) wrongOverlap else c),
        out,
        input,
        settings
      ),
      // C's first community has one member, so 2 is refused by the record contract already.
      "temporalChanges[2].communityOverlap: in [1, min(community sizes)]"
    )
    val wrongState = changeC.copy(to = changeC.to.map(_.copy(coreness = 0)))
    refused(
      checkTemporal(
        Vector(comparison),
        changes.map(c => if (c == changeC) wrongState else c),
        out,
        input,
        settings
      ),
      "to: expected"
    )
  }

  test("researcher-temporal-change.v1 contract: presence, turnover sums and overlap bounds") {
    val c = ResearcherTemporalChange.contract
    changes.foreach(x => assertEquals(c.validate(x), Vector.empty, x.researcherId))
    refused(c.validate(changeC.copy(to = None)), "to: missing")
    refused(
      c.validate(changeC.copy(lostCoauthors = 0)),
      "sum to the fromWindow distinctCoauthorCount"
    )
    refused(c.validate(changeC.copy(communityOverlap = None)), "communityOverlap: missing")
    refused(c.validate(changeC.copy(communityOverlap = Some(3))), "in [1, min(community sizes)]")
    val a = changes.head
    refused(c.validate(a.copy(communityOverlap = Some(1))), "only for in-both")
    refused(c.validate(a.copy(gainedCoauthors = 1)), "0 when absent from toWindow")
    refused(c.validate(changeC.copy(toWindow = w1)), "compares two different windows")
    refused(
      c.validate(changeC.copy(from = changeC.from.map(_.copy(communitySize = 4)))),
      "a community lies inside one component"
    )
  }

  // ---------------------------------------------------------------- summaries (item 10)

  test("semantic summary facts: only the frozen vocabulary, each equal to its cited record") {
    assertEquals(
      AnalysisValidation.checkSummaryInputs(summaryInputs, outputs, input, settings),
      Vector.empty
    )
    val f = profileB1.facts.head
    val invented = profileB1.copy(facts = profileB1.facts.updated(0, f.copy(value = "3")))
    refused(
      AnalysisValidation.checkSummaryInputs(Vector(invented), outputs, input, settings),
      "expected 2, the publicationCount of researcher-metrics"
    )
    val unnamed =
      profileB1.copy(facts = profileB1.facts.updated(0, f.copy(name = "collaborationScore")))
    refused(
      AnalysisValidation.checkSummaryInputs(Vector(unnamed), outputs, input, settings),
      "is not a computed fact of the frozen vocabulary"
    )
    val wrongKind = profileB1.copy(facts =
      profileB1.facts.updated(
        0,
        f.copy(evidence = Vector(SummaryEvidenceRef(SummaryEvidenceKind.Coreness, rB)))
      )
    )
    refused(
      AnalysisValidation.checkSummaryInputs(Vector(wrongKind), outputs, input, settings),
      "a publicationCount fact cites a researcher-metrics record"
    )
  }

  test("entity summaries: work and topic facts verified against the canonical tables") {
    def check(xs: Vector[EntitySummaryInput]) =
      checkEntitySummaries(
        xs,
        nodes,
        graphEdges,
        AssignmentFixtures.works,
        graphInput.sourceWorks,
        settings
      )
    assertEquals(check(Vector(workSummary, topicSummary)), Vector.empty)
    def withFact(s: EntitySummaryInput, i: Int, f: EntityFact => EntityFact) =
      s.copy(facts = s.facts.updated(i, f(s.facts(i))))
    refused(check(Vector(withFact(workSummary, 1, _.copy(value = "2020")))), "expected 2021")
    refused(
      check(Vector(withFact(workSummary, 0, _.copy(name = "abstract")))),
      "not a fact of the work-summary vocabulary"
    )
    refused(
      check(
        Vector(
          withFact(
            workSummary,
            0,
            _.copy(evidence = Vector(ref(EntityEvidenceKind.SourceWork, Fixtures.workId2)))
          )
        )
      ),
      "must cite a member work of the subject canonical work"
    )
    val coauthor = edgeOf(EdgeLabel.CoauthoredWith, researcherA, AssignmentFixtures.singletonId)
    refused(
      check(
        Vector(
          withFact(
            workSummary,
            2,
            _.copy(evidence = Vector(ref(EntityEvidenceKind.GraphEdge, coauthor.edgeId)))
          )
        )
      ),
      "must cite a AUTHORED edge"
    )
    refused(
      check(
        Vector(
          withFact(
            workSummary,
            5,
            _.copy(evidence =
              Vector(ref(EntityEvidenceKind.GraphNode, Fixtures.institution.institutionId))
            )
          )
        )
      ),
      "adjacent to it"
    )
    refused(
      check(
        Vector(
          withFact(
            topicSummary,
            0,
            _.copy(evidence = Vector(ref(EntityEvidenceKind.GraphNode, "topic:openalex:T9")))
          )
        )
      ),
      "resolves to no record of the run"
    )
    val asTopic = entitySummary(DocumentType.TopicSummary, "topic:openalex:T2", topicSummary.facts)
    refused(check(Vector(asTopic)), "is not a Topic node of the canonical tables")
  }

  test("entity-summary-input.v1 contract: document types, evidence kinds, ID formula") {
    val c = EntitySummaryInput.contract
    assertEquals(c.validate(workSummary), Vector.empty)
    refused(
      c.validate(workSummary.copy(documentType = DocumentType.ResearcherProfile)),
      "is not a work or topic summary"
    )
    refused(c.validate(workSummary.copy(inputId = "suminput:" + "0" * 64)), "inputId: expected")
    refused(
      c.validate(workSummary.copy(facts = workSummary.facts :+ workSummary.facts.head)),
      "(name, value) pairs must be distinct"
    )
    refused(
      c.validate(
        workSummary.copy(facts =
          Vector(EntityFact("title", "x", Vector(ref(EntityEvidenceKind.GraphEdge, workId))))
        )
      ),
      "id"
    )
    // The semantic-summary-input.v1 contract still refuses the two new document types.
    refused(
      SemanticSummaryInput.contract.validate(
        profileB1.copy(documentType = DocumentType.WorkSummary)
      ),
      "is not an analysis summary"
    )
  }

  // ---------------------------------------------------------------- load manifests (item 11)

  test("load manifests: one per store, roles of the vocabulary, every acceptable part loaded") {
    assertEquals(checkLoadManifests(loads, Right(loadParts), partsSha), Vector.empty)
  }

  test("load manifests: counter-tests") {
    refused(
      checkLoadManifests(loads.tail, Right(loadParts), partsSha),
      "no load manifest for neptune-bulk-load"
    )
    refused(
      checkLoadManifests(loads :+ loads.head, Right(loadParts), partsSha),
      "2 load manifests for neptune-bulk-load"
    )
    val s3Partial = load(
      TargetStore.S3,
      "metrics/part-00000.parquet" -> "researcher-metrics",
      "summaries/part-00000.jsonl" -> "summary-inputs"
    )
    refused(
      checkLoadManifests(loads.init :+ s3Partial, Right(loadParts), partsSha),
      "part nodes/part-00000.parquet (scholarchain.graph-node.v2, parquet) is not loaded"
    )
    val wrongRole = load(TargetStore.DynamoDb, "metrics/part-00000.parquet" -> "coauthor-edges")
    refused(
      checkLoadManifests(loads.updated(1, wrongRole), Right(loadParts), partsSha),
      "role coauthor-edges holds scholarchain.coauthor-edge.v2 records"
    )
    val unknown = load(TargetStore.DynamoDb, "metrics/part-00000.parquet" -> "metrics-table")
    refused(
      checkLoadManifests(loads.updated(1, unknown), Right(loadParts), partsSha),
      "'metrics-table' is not a dynamodb role"
    )
    refused(checkLoadManifests(loads, Right(loadParts), "8" * 64), "partManifestSha256")
  }
}
