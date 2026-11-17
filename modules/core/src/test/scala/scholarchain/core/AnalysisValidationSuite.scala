package scholarchain.core

import scala.collection.immutable.SortedMap

import scholarchain.core.config.ScholarChainConfig

import AnalysisFixtures.*
import AnalysisValidation.*

/** Dataset-level validation of one synthetic analysis run (DE-P6-02). No file is read. */
class AnalysisValidationSuite extends munit.FunSuite {

  private def refused(errs: Vector[ValidationError], fragment: String): Unit =
    assert(
      errs.exists(_.message.contains(fragment)),
      s"expected a refusal containing '$fragment', got ${errs.map(_.message)}"
    )

  private def notRefused(errs: Vector[ValidationError], fragment: String): Unit =
    assert(
      !errs.exists(_.message.contains(fragment)),
      s"expected no refusal containing '$fragment', got ${errs.map(_.message)}"
    )

  test("the synthetic run satisfies every dataset check") {
    assertEquals(checkGraphInput(input, groupingManifest), Vector.empty)
    assertEquals(checkEdges(edges, input, settings), Vector.empty)
    assertEquals(checkMetrics(metrics, input, settings), Vector.empty)
    assertEquals(checkIdentity(metrics, researchers, decisions, groupingManifest), Vector.empty)
    assertEquals(checkComponents(components, input, settings), Vector.empty)
    assertEquals(checkCommunities(communities, components, settings), Vector.empty)
    assertEquals(checkBridges(bridges, communities, input, settings), Vector.empty)
    assertEquals(checkCoreness(coreness, input, settings), Vector.empty)
    assertEquals(checkPaths(paths, metrics, input, settings, benchmarkPairs), Vector.empty)
    assertEquals(checkWindows(windows, outputs, input, settings), Vector.empty)
    assertEquals(checkSummaryInputs(summaryInputs, outputs, input, settings), Vector.empty)
    assertEquals(
      checkRunChain(analysisManifest, groupingManifest, outputs, settings, benchmarkPairs),
      Vector.empty
    )
  }

  // ---------------------------------------------------------------- counted works only

  private val enrichment = AssignmentFixtures.enrichmentSingle.workId
  private val g7 = AnalysisFixtures.g(7)
  private val g8 = AnalysisFixtures.g(8)

  test("counted works only: a team of a non-counted or unknown work is refused, never counted") {
    val extra = team(enrichment, Some(2017), Vector(rA, rC), Vector.empty)
    refused(
      checkGraphInput(input.copy(teams = teams :+ extra), groupingManifest),
      "graphEligibility is enrichment-only: only counted works enter the graph"
    )
    refused(
      checkGraphInput(
        input.copy(teams = teams :+ team(g7, Some(2017), Vector(rA, rC), Vector.empty)),
        groupingManifest
      ),
      "the work has no work assignment"
    )
    assertEquals(countedTeams(input.copy(teams = teams :+ extra)), teams, "selection ignores it")
    val copy = stamped(AssignmentFixtures.countedCopy)
    val withCopy = input.copy(
      works = works :+ copy,
      teams = teams :+ team(copy.workId, Some(2017), Vector(rA), Vector.empty)
    )
    refused(checkGraphInput(withCopy, groupingManifest), "graphEligibility is member-of-counted")
  }

  test("graph input: team size is identified plus unresolved; team researchers are identified") {
    val bad = teams.head.copy(teamSize = 4)
    refused(
      checkGraphInput(input.copy(teams = bad +: teams.tail), groupingManifest),
      "identified researchers plus unresolved"
    )
    val stranger = Fixtures.ok(ResearcherId.scholarChain("v1", "dblp-pid", "00/0099"))
    val t =
      teams.head.copy(researcherIds = Vector(rA, stranger), unresolvedMentionIds = Vector.empty)
    refused(
      checkGraphInput(input.copy(teams = t +: teams.tail), groupingManifest),
      s"$stranger is not an identified researcher"
    )
  }

  // ---------------------------------------------------------------- repair 01, finding 2

  test("finding 2: teams, works and researchers of another run than the grouping run are refused") {
    val other = AssignmentFixtures.groupingRunId
    assertNotEquals(other, groupingRunId)
    def foreign(p: Provenance) = p.copy(runId = other)
    val t = teams.head
    refused(
      checkGraphInput(
        input.copy(teams = t.copy(provenance = foreign(t.provenance)) +: teams.tail),
        groupingManifest
      ),
      s"teams[${t.workId}].provenance.runId: must be the grouping run $groupingRunId"
    )
    val w = works.head
    refused(
      checkGraphInput(
        input.copy(works = w.copy(provenance = foreign(w.provenance)) +: works.tail),
        groupingManifest
      ),
      s"works[${w.workId}].provenance.runId: must be the grouping run"
    )
    val r = researchers.head
    refused(
      checkGraphInput(
        input.copy(researchers = r.copy(provenance = foreign(r.provenance)) +: researchers.tail),
        groupingManifest
      ),
      s"researchers[${r.memberId}].provenance.runId: must be the grouping run"
    )
    val nullProv = t.copy(provenance = null)
    refused(
      checkGraphInput(input.copy(teams = nullProv +: teams.tail), groupingManifest),
      "provenance"
    )
    // The same records, checked against another grouping manifest, all belong to another run.
    val otherManifest = groupingManifest.copy(attempt = 2)
    refused(checkGraphInput(input, otherManifest), "must be the grouping run")
  }

  test("finding 2: assignments of another decision run than the grouping manifest's are refused") {
    val w = works.head
    refused(
      checkGraphInput(
        input.copy(works = w.copy(decisionRunId = inputRunId) +: works.tail),
        groupingManifest
      ),
      s"works[${w.workId}].decisionRunId: must be the decision run $decisionRunId"
    )
    val r = researchers.head
    refused(
      checkGraphInput(
        input.copy(researchers = r.copy(decisionRunId = inputRunId) +: researchers.tail),
        groupingManifest
      ),
      s"researchers[${r.memberId}].decisionRunId: must be the decision run"
    )
    val noDecision =
      groupingManifest.copy(parameters = groupingManifest.parameters - "decisionRunId")
    refused(checkGraphInput(input, noDecision), "groupingManifest.parameters.decisionRunId")
  }

  test("finding 2: exactly one team per counted work with mentions; missing or duplicate refused") {
    // g2 is counted and has a mention (B at position 0) in the grouping run.
    val withoutG2 = teams.filterNot(_.workId == g2)
    refused(
      checkGraphInput(input.copy(teams = withoutG2), groupingManifest),
      s"counted work $g2 has mentions in the grouping run but no team"
    )
    val twice = teams :+ teams.find(_.workId == g2).get
    refused(
      checkGraphInput(input.copy(teams = twice), groupingManifest),
      s"$g2 appears in 2 records; one record per key"
    )
    // A counted work without any mention in the grouping run has no team (teamSize >= 1), so its
    // absence is not a gap: g7 below is counted and unmentioned.
    assertEquals(
      checkGraphInput(input.copy(works = works :+ countedWork(g7)), groupingManifest),
      Vector.empty
    )
  }

  test("finding 2: identity is derived only from the grouping run and its decision run") {
    val d = decisions.head
    val foreignDecision = d.copy(provenance = d.provenance.copy(runId = inputRunId))
    refused(
      checkIdentity(metrics, researchers, foreignDecision +: decisions.tail, groupingManifest),
      s"decisions[${d.decisionId}].provenance.runId: must be the decision run $decisionRunId"
    )
    val r = researchers.head
    refused(
      checkIdentity(
        metrics,
        r.copy(provenance = r.provenance.copy(runId = inputRunId)) +: researchers.tail,
        decisions,
        groupingManifest
      ),
      s"researchers[${r.memberId}].provenance.runId: must be the grouping run"
    )
    // A grouping manifest that names another decision run refuses the decisions it was given.
    val otherDecisionRun =
      groupingManifest.copy(parameters =
        groupingManifest.parameters.updated("decisionRunId", inputRunId)
      )
    refused(
      checkIdentity(metrics, researchers, decisions, otherDecisionRun),
      "must be the decision run"
    )
  }

  // ---------------------------------------------------------------- C2 edges

  test("edges: identified endpoints and counted supporting works only") {
    val stranger = Fixtures.ok(ResearcherId.scholarChain("v1", "dblp-pid", "00/0099"))
    val e = edge(rA, stranger, w1, Vector(2016 -> 1L), Vector(g1))
    refused(
      checkEdges(edges :+ e, input, settings),
      s"endpoint $stranger is not an identified researcher"
    )
    val notCounted = edge(rA, rC, w1, Vector(2017 -> 1L), Vector(g7))
    val errs = checkEdges(edges :+ notCounted, input, settings)
    refused(errs, s"supporting work $g7 is not a counted work")
    refused(errs, "no counted team of 2015-2019 holds both researchers")
  }

  test("edges: exactly the recomputed edges, weights and supporting works per window") {
    refused(checkEdges(edges.filterNot(_ == edgeBC), input, settings), "missing edge")
    val wrongYears = edge(rD, rC, w2, Vector(2021 -> 2L), Vector(g3, g6))
    refused(
      checkEdges(edges.map(e => if (e == edgeCD) wrongYears else e), input, settings),
      "publicationsByYear differs"
    )
    refused(checkEdges(edges :+ edgeAB, input, settings), "has more than one record")
    val other = edge(rA, rB, TimeWindow(2016, 2016), Vector(2016 -> 1L), Vector(g1))
    refused(checkEdges(edges :+ other, input, settings), "is not a configured window")
  }

  // ---------------------------------------------------------------- C1 metrics, C5 identity

  test(
    "metrics: unresolved occurrences belong in T_r and its separate field, never in C_r or W_r"
  ) {
    // Dropping the unresolved coauthor from T_r passes the record contract (W = T - U holds) but
    // not the recomputation from the counted teams.
    val dropped = metricA1.copy(
      totalCoauthorOccurrences = 1,
      unresolvedCoauthorOccurrences = 0,
      worksWithUnresolvedCoauthors = 0,
      averageCoauthorsPerPaper = 1.0,
      maximumCoauthorsOnOnePaper = 1
    )
    assertEquals(ResearcherMetricsV2.contract.validate(dropped), Vector.empty)
    val errs = checkMetrics(metrics.map(m => if (m == metricA1) dropped else m), input, settings)
    refused(errs, "totalCoauthorOccurrences: expected 2")
    refused(errs, "unresolvedCoauthorOccurrences: expected 1")
    refused(errs, "maximumCoauthorsOnOnePaper: expected 2")
  }

  test("metrics: one record per identified researcher with counted works in each window") {
    refused(
      checkMetrics(metrics.filterNot(_ == metricE2), input, settings),
      "but no metrics record"
    )
    refused(
      checkMetrics(metrics :+ metricA1.copy(window = w2), input, settings),
      "no counted team of 2020-2024"
    )
    refused(checkMetrics(metrics :+ metricA1, input, settings), "one record per key")
    val off = metricC2.copy(maximumTeamWorkIds = Vector(metricC2.maximumTeamWorkIds.head))
    refused(
      checkMetrics(metrics.map(m => if (m == metricC2) off else m), input, settings),
      "maximumTeamWorkIds: expected"
    )
  }

  test("identity: metrics carry exactly the grouping run's identity confidence") {
    val wrong = metricB1.copy(identity =
      idB.copy(lowestDecisionConfidence = DecisionConfidence.measured(0.5))
    )
    refused(
      checkIdentity(Vector(wrong), researchers, decisions, groupingManifest),
      "expected IdentityConfidence"
    )
    val noCollision = metricA1.copy(identity = idA.copy(collisionCodes = Vector.empty))
    refused(
      checkIdentity(Vector(noCollision), researchers, decisions, groupingManifest),
      "expected IdentityConfidence"
    )
    refused(
      checkIdentity(metrics, researchers, Vector.empty, groupingManifest),
      "has no confidence in the decision run"
    )
  }

  // ---------------------------------------------------------------- C3

  test("components: exactly the connected components, with formula IDs and sizes") {
    val merged = components.map(c =>
      if (c.window == w2) component(c.researcherId, w2, Vector(rC, rD, rE)) else c
    )
    val errs = checkComponents(merged, input, settings)
    refused(errs, "componentSize: expected 2")
    refused(errs, "componentSize: expected 1")
    refused(checkComponents(components.tail, input, settings), "has no component membership")
  }

  test("communities: configured algorithm and parameters, inside one component, formula IDs") {
    val params = communities.map(_.copy(parameters = SortedMap("maxIterations" -> "21")))
    refused(checkCommunities(params, components, settings), "community.*")
    val spanning = communities.map(c =>
      if (c.window == w2) community(c.researcherId, w2, Vector(rC, rD, rE)) else c
    )
    refused(
      checkCommunities(spanning, components, settings),
      "a community lies inside one connected component"
    )
    val sized = communities.map(c =>
      if (c.researcherId == rC && c.window == w1) c.copy(communitySize = 2) else c
    )
    refused(checkCommunities(sized, components, settings), "communitySize: expected 1")
    refused(checkCommunities(communities.tail, components, settings), "has no community membership")
  }

  test("bridges: degrees and participation recomputed exactly; configured measure") {
    val cross = bridges.map(b =>
      if (b == bridgeB1) b.copy(crossCommunityDegree = 2, communitiesReached = 1) else b
    )
    refused(checkBridges(cross, communities, input, settings), "crossCommunityDegree: expected 1")
    val pc = bridges.map(b => if (b == bridgeB1) b.copy(participationCoefficient = 0.25) else b)
    refused(
      checkBridges(pc, communities, input, settings),
      "participationCoefficient: expected 0.5"
    )
    val measure = bridges.map(_.copy(measure = "betweenness-exact"))
    refused(checkBridges(measure, communities, input, settings), "bridge.algorithm")
  }

  /** The fixture run with one extra counted team: A and C in 2017 (a triangle A, B, C). */
  private val tri = input.copy(
    teams = teams :+ team(g7, Some(2017), Vector(rA, rC), Vector.empty),
    works = works :+ countedWork(g7)
  )

  test("coreness: exactly the k-core numbers") {
    // The triangle raises every coreness in 2015-2019 to 2.
    val errs = checkCoreness(coreness, tri, settings)
    refused(errs, "coreness: expected 2")
    refused(
      checkCoreness(
        coreness.map(c => if (c == corenessB1) c.copy(coreness = 0) else c),
        input,
        settings
      ),
      "coreness: expected 1"
    )
  }

  test("paths: shortest, bounded, every hop an edge whose works support it") {
    refused(
      checkPaths(paths, metrics, tri, settings, benchmarkPairs),
      "the shortest path has 1 hop(s)"
    )
    val wrongWork = pathAC.copy(hops = pathAC.hops.updated(0, PathHop(edgeAB.edgeId, Vector(g2))))
    refused(
      checkPaths(Vector(wrongWork, pathCD), metrics, input, settings, benchmarkPairs),
      s"$g2 does not support the edge"
    )
    val wrongId = pathAC.copy(nodeIdentities = pathAC.nodeIdentities.updated(1, idA))
    refused(
      checkPaths(Vector(wrongId, pathCD), metrics, input, settings, benchmarkPairs),
      "differs from the node's metrics identity"
    )
    refused(
      checkPaths(
        Vector(pathAC.copy(ordinal = 1), pathCD),
        metrics,
        input,
        settings,
        benchmarkPairs
      ),
      "ordinals are 0..n-1"
    )
    val onePerPair = config.copy(analysis = config.analysis.copy(maxPathsPerPair = Some(1)))
    refused(
      checkPaths(paths, metrics, input, settingsOf(onePerPair), benchmarkPairs),
      "analysis.*"
    )
  }

  // ---------------------------------------------------------------- repair 01, finding 4

  /**
   * The fixture run with two extra counted teams in 2017: A with E (g7) and E with C (g8). In
   * 2015-2019 the pair (A, C) then has two shortest paths of 2 hops: A-B-C and A-E-C. B sorts
   * before E (`researcher:orcid:...` < `researcher:sc:...`), so node-sequence order is A-B-C, then
   * A-E-C.
   */
  private val tie = input.copy(
    teams = teams ++ Vector(
      team(g7, Some(2017), Vector(rA, rE), Vector.empty),
      team(g8, Some(2017), Vector(rE, rC), Vector.empty)
    ),
    works = works ++ Vector(countedWork(g7), countedWork(g8))
  )
  private val edgeAE = edge(rA, rE, w1, Vector(2017 -> 1L), Vector(g7))
  private val edgeEC = edge(rE, rC, w1, Vector(2017 -> 1L), Vector(g8))
  private def pathAEC(ordinal: Int, bounds: PathBounds = settings.pathBounds) = path(
    w1,
    Vector(rA, rE, rC),
    Vector(PathHop(edgeAE.edgeId, Vector(g7)), PathHop(edgeEC.edgeId, Vector(g8))),
    ordinal,
    bounds
  )
  private val selectionMessage = "shortest path(s) in node-sequence order"

  test("finding 4: the tie in the fixture graph is the one described (hand check)") {
    assert(Canonical.compareUtf8(rB, rE) < 0)
    val m = AnalysisValidation.selectShortestPaths(
      Map(rA -> Set(rB, rE), rB -> Set(rA, rC), rE -> Set(rA, rC), rC -> Set(rB, rE)),
      rA,
      rC,
      3,
      2
    )
    assertEquals(m, Vector(Vector(rA, rB, rC), Vector(rA, rE, rC)))
  }

  test("finding 4: K = 2 stores both tied paths in node-sequence order, ordinals 0 and 1") {
    val both = Vector(pathAC, pathAEC(1), pathCD)
    notRefused(checkPaths(both, metrics, tie, settings, benchmarkPairs), selectionMessage)
    notRefused(checkPaths(both, metrics, tie, settings, benchmarkPairs), "ordinals")
    // Only one of the two: the set is not the first K = 2.
    refused(checkPaths(paths, metrics, tie, settings, benchmarkPairs), selectionMessage)
    // Both, but A-E-C first: the ordinals do not follow node-sequence order.
    val swapped = Vector(pathAC.copy(ordinal = 1), pathAEC(0), pathCD)
    refused(checkPaths(swapped, metrics, tie, settings, benchmarkPairs), selectionMessage)
  }

  test("finding 4: K = 1 stores the smaller node sequence (A-B-C), never A-E-C") {
    val k1 = settingsOf(config.copy(analysis = config.analysis.copy(maxPathsPerPair = Some(1))))
    val b1 = k1.pathBounds
    val smallest = Vector(pathAC.copy(bounds = b1), pathCD.copy(bounds = b1))
    assertEquals(checkPaths(smallest, metrics, tie, k1, benchmarkPairs), Vector.empty)
    val largest = Vector(pathAEC(0, b1), pathCD.copy(bounds = b1))
    refused(checkPaths(largest, metrics, tie, k1, benchmarkPairs), selectionMessage)
  }

  test("finding 4: paths only for listed pairs; a listed pair connected in a window has paths") {
    val ab = path(w1, Vector(rA, rB), Vector(PathHop(edgeAB.edgeId, Vector(g1))))
    refused(
      checkPaths(paths :+ ab, metrics, input, settings, benchmarkPairs),
      "is not in benchmark pair list benchmark-pairs-synthetic.v1"
    )
    refused(
      checkPaths(Vector(pathAC), metrics, input, settings, benchmarkPairs),
      s"paths[${pathCD.sourceResearcherId} ${pathCD.targetResearcherId} 2020-2024]"
    )
    val stranger = Fixtures.ok(ResearcherId.scholarChain("v1", "dblp-pid", "00/0099"))
    val (lo, hi) =
      if (Canonical.compareUtf8(rA, stranger) < 0) (rA, stranger) else (stranger, rA)
    val unknown = benchmarkPairs.copy(pairs = benchmarkPairs.pairs :+ BenchmarkPair(lo, hi))
    val sortedUnknown = unknown.copy(pairs =
      unknown.pairs.sortWith((x, y) =>
        EvidencePath.compareNodes(
          Vector(x.researcherA, x.researcherB),
          Vector(y.researcherA, y.researcherB)
        ) < 0
      )
    )
    refused(
      checkPaths(paths, metrics, input, settings, sortedUnknown),
      s"$stranger is not an identified researcher of the grouping run"
    )
    refused(
      checkPaths(paths, metrics, input, settings, benchmarkPairs.copy(pairs = Vector.empty)),
      "benchmarkPairs"
    )
  }

  // ---------------------------------------------------------------- C4 windows

  test("windows: one per configured window, in order, with recomputed counts") {
    refused(checkWindows(windows.reverse, outputs, input, settings), "in configured order")
    val wrong = windows.map(w => if (w == window1) w.copy(countedWorksWithoutYear = 0) else w)
    refused(checkWindows(wrong, outputs, input, settings), "countedWorksWithoutYear: expected 1")
    val edgesOff = windows.map(w => if (w == window1) w.copy(edgeCount = 3) else w)
    refused(checkWindows(edgesOff, outputs, input, settings), "edgeCount: expected 2")
  }

  // ---------------------------------------------------------------- C6 summary inputs

  test("summary inputs: every subject and cited ID resolves to a stored record of the window") {
    val dangling = profileB1.copy(facts =
      profileB1.facts :+ SummaryFact(
        "edge",
        "x",
        Vector(SummaryEvidenceRef(SummaryEvidenceKind.CoauthorEdge, edgeCD.edgeId))
      )
    )
    refused(
      checkSummaryInputs(Vector(dangling), outputs, input, settings),
      s"coauthor-edge ${edgeCD.edgeId} resolves to no stored record of the window"
    )
    val noRecord = summary(DocumentType.ResearcherProfile, rE, w1, profileB1.facts, Some(idE))
    refused(
      checkSummaryInputs(Vector(noRecord), outputs, input, settings),
      "resolves to no stored researcher-metrics record"
    )
    val idOff = profileB1.copy(identity = Some(idA))
    refused(
      checkSummaryInputs(Vector(idOff), outputs, input, settings),
      "differs from the subject's metrics identity"
    )
    val work = profileB1.copy(facts =
      Vector(
        SummaryFact("work", "g4", Vector(SummaryEvidenceRef(SummaryEvidenceKind.CountedWork, g4)))
      )
    )
    refused(checkSummaryInputs(Vector(work), outputs, input, settings), "counted-work")
  }

  // ---------------------------------------------------------------- run chain and parts

  private def chain(
      m: RunManifest = analysisManifest,
      g: RunManifest = groupingManifest,
      o: AnalysisOutputs = outputs,
      s: scholarchain.core.config.AnalysisRunSettings = settings,
      b: BenchmarkPairList = benchmarkPairs
  ) = checkRunChain(m, g, o, s, b)

  test("run chain: the analysis manifest and every lineage follow the grouping manifest") {
    val wrongDecision = analysisManifest.copy(parameters =
      analysisManifest.parameters.updated("decisionRunId", inputRunId)
    )
    refused(chain(m = wrongDecision), "the grouping run's decisionRunId is")
    val wrongGrouping = analysisManifest.copy(parameters =
      analysisManifest.parameters.updated("groupingRunId", inputRunId)
    )
    refused(chain(m = wrongGrouping), "the grouping manifest gives")
    // Records of this run name the analysis run whose manifest is `analysisManifest`; another
    // manifest (another code version) is another run.
    refused(chain(m = analysisManifest.copy(codeVersion = "f" * 40)), "must be the analysis run")
    val noKey = groupingManifest.copy(parameters = groupingManifest.parameters - "inputRunId")
    refused(chain(g = noKey), "groupingManifest.parameters.inputRunId: missing")
    val digestOff =
      outputs.copy(metrics = metrics.map(_.copy(lineage = lineage.copy(configDigest = "8" * 64))))
    refused(chain(o = digestOff), "must equal the run chain")
    val v2 = settingsOf(config.copy(graph = config.graph.copy(identityVersion = "v2")))
    refused(chain(s = v2), "the configured graph.identityVersion is v2")
  }

  // ---------------------------------------------------------------- repair 01, finding 1

  test("finding 1: the fixture records the real analysis config digest of its configuration") {
    assertEquals(
      scholarchain.core.config.AnalysisRunSettings.configDigest(config),
      Right(analysisConfigDigest)
    )
    assertEquals(analysisManifest.configDigest, settings.configDigest)
    assertEquals(lineage.configDigest, settings.configDigest)
  }

  /** One variant per analysis-scope key (ScholarChainConfig.AnalysisScopeKeys), in that order. */
  private val oneKeyChanged: Vector[(String, ScholarChainConfig)] = {
    val a = config.analysis
    Vector(
      "community.randomSeed" -> config.copy(community =
        config.community.copy(randomSeed = Some(1L))
      ),
      "analysis.maxPathHops" -> config.copy(analysis = a.copy(maxPathHops = Some(4))),
      "analysis.maxPathsPerPair" -> config.copy(analysis = a.copy(maxPathsPerPair = Some(3))),
      "analysis.maxEvidenceWorksPerHop" -> config.copy(analysis =
        a.copy(maxEvidenceWorksPerHop = Some(3))
      ),
      "analysis.exportFormat" -> config.copy(analysis = a.copy(exportFormat = Some("jsonl"))),
      // DE-P6-03: the path-length keys are set together; each variant differs in its own key.
      "analysis.pathLengthSampleSize" -> config.copy(analysis =
        a.copy(
          pathLengthSampleSize = Some(4),
          pathLengthSeed = Some(7L),
          pathLengthMinComponentSize = Some(2)
        )
      ),
      "analysis.pathLengthSeed" -> config.copy(analysis =
        a.copy(
          pathLengthSampleSize = Some(4),
          pathLengthSeed = Some(8L),
          pathLengthMinComponentSize = Some(2)
        )
      ),
      "analysis.pathLengthMinComponentSize" -> config.copy(analysis =
        a.copy(
          pathLengthSampleSize = Some(4),
          pathLengthSeed = Some(7L),
          pathLengthMinComponentSize = Some(3)
        )
      )
    )
  }

  test("finding 1: changing any one analysis setting changes the digest and the run ID") {
    assertEquals(oneKeyChanged.map(_._1), ScholarChainConfig.AnalysisScopeKeys)
    val digests = oneKeyChanged.map { case (key, c) =>
      val s = settingsOf(c)
      assertNotEquals(s.configDigest, settings.configDigest, key)
      val rerun = analysisManifest.copy(configDigest = s.configDigest)
      assertNotEquals(RunId.of(rerun), Right(analysisRunId), key)
      // The old manifest no longer matches the changed settings: refused, never reused.
      refused(chain(s = s), "analysisManifest.configDigest: must be the analysis config digest")
      s.configDigest
    }
    assertEquals(digests.distinct.size, digests.size, "every variant has its own digest")
    // The upstream digests do not move (the analysis scope is not an upstream input).
    oneKeyChanged.foreach { case (key, c) =>
      assertEquals(
        ScholarChainConfig.digestWithoutGrouping(c),
        ScholarChainConfig.digestWithoutGrouping(config),
        key
      )
    }
  }

  test("finding 1: a manifest with another digest scope or an arbitrary digest is refused") {
    val fragment = "analysisManifest.configDigest: must be the analysis config digest"
    val base = ScholarChainConfig.digestWithoutGrouping(config)
    assertNotEquals(base, settings.configDigest)
    for (d <- Vector(base, "9" * 64, "0" * 64)) {
      // Records restamped consistently with the bad manifest still fail on the digest itself.
      val m = analysisManifest.copy(configDigest = d)
      val run = Fixtures.ok(RunId.of(m))
      def re(l: AnalysisLineage, p: Provenance) =
        (l.copy(configDigest = d), p.copy(runId = run))
      val o = outputs.copy(metrics = metrics.map { x =>
        val (l, p) = re(x.lineage, x.provenance)
        x.copy(lineage = l, provenance = p)
      })
      val errs = chain(m = m, o = o)
      refused(errs, fragment)
      refused(errs, "must equal the run chain")
    }
    notRefused(chain(), fragment)
  }

  // ---------------------------------------------------------------- repair 01, finding 4 (chain)

  test(
    "finding 4: the manifest binds the benchmark pair list by digest; list from the grouping run"
  ) {
    val other = benchmarkPairs.copy(pairs = benchmarkPairs.pairs.take(1))
    refused(
      chain(b = other),
      "analysisManifest.parameters.benchmarkPairsDigest: the benchmark pair list"
    )
    val noKey =
      analysisManifest.copy(parameters = analysisManifest.parameters - "benchmarkPairsDigest")
    refused(chain(m = noKey), "analysisManifest.parameters.benchmarkPairsDigest: missing")
    val foreign =
      benchmarkPairs.copy(provenance = benchmarkPairs.provenance.copy(runId = inputRunId))
    val bound = analysisManifest.copy(parameters =
      analysisManifest.parameters.updated("benchmarkPairsDigest", BenchmarkPairList.digest(foreign))
    )
    val errs = chain(m = bound, b = foreign)
    refused(errs, s"benchmarkPairs.provenance.runId: must be the grouping run $groupingRunId")
    // Another list is another run.
    assertNotEquals(RunId.of(bound), Right(analysisRunId))
  }

  test("parts: a grouping run is verified per format and must hold all four record schemas") {
    // The DE-P6-01 synthetic run lists researchers and works only.
    refused(
      checkGroupingRunParts(
        Left(AssignmentFixtures.partManifest),
        AssignmentFixtures.groupingRunId,
        AssignmentFixtures.observedRun
      ),
      s"no part of schema ${SchemaVersions.PublicationTeam}"
    )
    refused(
      checkGroupingRunParts(
        Left(AssignmentFixtures.partManifest),
        AssignmentFixtures.groupingRunId,
        AssignmentFixtures.observedRun.tail
      ),
      "listed file missing"
    )
  }
}
