package scholarchain.core

import scala.collection.immutable.SortedMap

import scholarchain.core.config.{
  AnalysisConfig,
  AnalysisRunSettings,
  AwsConfig,
  BridgeConfig,
  CommunityConfig,
  EntityResolutionConfig,
  GraphConfig,
  ModelConfig,
  RuntimeConfig,
  ScholarChainConfig,
  StorageConfig
}

import Fixtures.{erPolicy, graphPolicy, ok, orcidB, researcherA, researcherB}

/**
 * One valid, synthetic collaboration-analysis run (DE-P6-02). Every identifier is synthetic (see
 * [[Fixtures]]); no real person, work or dataset fact is asserted; nothing is read from target/,
 * any run directory or any gold set. The shapes mirror the dev run's record types (grouping-run
 * teams, work and researcher assignments), not its values.
 *
 * Researchers: A (ORCID, resolved, with an anchor-collision reason code), B (ORCID singleton), C
 * (t1-anchored dblp-pid singleton), D and E (dblp-pid singletons). Counted works and teams:
 *   - g1 (2016): A, B and one unresolved mention u1;
 *   - g2 (2018): B, C;
 *   - g3 (2021): C, D; g6 (2023): D, C;
 *   - g4 (2022): E alone;
 *   - g5 (no year): A, D (in no window).
 *
 * Windows: 2015-2019 (edges A-B, B-C: a path A-B-C) and 2020-2024 (edge C-D with weight 2, E
 * isolated).
 *
 * Repair 01 (CR-P6-13): the analysis settings and config digest come from a real in-memory
 * configuration ([[config]]); every grouping-run input carries this fixture's grouping run ID; the
 * decisions are the DE-P6-01 decisions of [[decisionRunId]]; and paths are stored for the benchmark
 * pairs (A, C) and (C, D).
 */
object AnalysisFixtures {

  val snapshots: Vector[SnapshotRef] = Vector(SnapshotRef(SourceType.Dblp, "dblp-2026-10-03"))
  private val code = "0123456789abcdef0123456789abcdef01234567"

  val w1: TimeWindow = TimeWindow(2015, 2019)
  val w2: TimeWindow = TimeWindow(2020, 2024)

  val inputManifest: RunManifest = RunManifest(
    SchemaVersions.RunManifest,
    "hadoop.curated",
    code,
    "d" * 64,
    snapshots,
    SortedMap.empty,
    1
  )
  val inputRunId: String = ok(RunId.of(inputManifest))

  /**
   * The decision run of the synthetic DE-P6-01 decisions ([[AssignmentFixtures.decisions]]): their
   * `provenance.runId`, which is also every assignment's `decisionRunId`. Decision IDs hash it, so
   * the analysis fixture reuses that run instead of restamping the decisions.
   */
  val decisionRunId: String = AssignmentFixtures.decisionRunId

  val groupingManifest: RunManifest = RunManifest(
    SchemaVersions.RunManifest,
    "er.grouping",
    code,
    "f" * 64,
    snapshots,
    SortedMap(
      "assignedAt" -> "2026-11-15T12:00:00Z",
      "decisionRunId" -> decisionRunId,
      "groupingPolicyVersion" -> "er-grouping.v1",
      "identityVersion" -> "v1",
      "inputRunId" -> inputRunId,
      "matchPolicyVersion" -> erPolicy
    ),
    1
  )
  val groupingRunId: String = ok(RunId.of(groupingManifest))

  // ---------------------------------------------------------------- configuration and settings

  /**
   * A synthetic resolved configuration (dummy, non-secret values; nothing is called). The analysis
   * settings and the analysis config digest are derived from it, never written by hand.
   */
  val config: ScholarChainConfig = ScholarChainConfig(
    configSchemaVersion = "scholarchain.config.v2",
    environment = "test",
    aws = AwsConfig("us-east-1", "000000000000"),
    storage = StorageConfig(
      "dummy-raw",
      "dummy-curated",
      "dummy-context",
      "dummy-vectors",
      "dummy-index",
      "dummy-",
      "https://neptune.invalid",
      None
    ),
    models = ModelConfig("not-called", "not-called", 8),
    graph = GraphConfig(Vector(w1, w2), graphPolicy, "v1"),
    bridge = BridgeConfig("cross-community-degree", 64, 7L, 10),
    community = CommunityConfig("label-propagation", 20, None),
    entityResolution =
      EntityResolutionConfig(erPolicy, 0.85, 0.2, 0.4, 0.7, "er-adjudication-prompt.v2"),
    runtime = RuntimeConfig(3, 10, w1),
    analysis = AnalysisConfig(Some(3), Some(2), Some(2), Some("parquet"))
  )

  /** The analysis settings of a configuration (fails the fixture when it is refused). */
  def settingsOf(c: ScholarChainConfig): AnalysisRunSettings =
    AnalysisRunSettings.from(c).fold(e => throw new AssertionError(s"fixture config: $e"), identity)

  val settings: AnalysisRunSettings = settingsOf(config)

  // ---------------------------------------------------------------- researchers and pairs

  val rA: String = researcherA
  val rB: String = researcherB
  val rC: String = AssignmentFixtures.singletonId
  val rD: String = ok(ResearcherId.scholarChain("v1", "dblp-pid", "00/0003"))
  val rE: String = ok(ResearcherId.scholarChain("v1", "dblp-pid", "00/0004"))

  /**
   * The run's benchmark pair list: (A, C) and (C, D), canonical and ascending. Provenance names the
   * grouping run whose researcher IDs it lists.
   */
  val benchmarkPairs: BenchmarkPairList = BenchmarkPairList(
    SchemaVersions.BenchmarkPairList,
    "benchmark-pairs-synthetic.v1",
    Vector(BenchmarkPair(rA, rC), BenchmarkPair(rC, rD)),
    Provenance(
      sourceDataset = SourceType.Derived,
      sourceRecordId = "benchmark-pairs-synthetic.v1",
      snapshot = "dblp-2026-10-03",
      runId = groupingRunId,
      sourceSchemaVersion = "scholarchain.researcher-assignment.v1",
      policyVersion = None,
      modelVersion = None,
      promptVersion = None
    )
  )

  // ---------------------------------------------------------------- analysis run

  val analysisCode = "89abcdef0123456789abcdef0123456789abcdef"

  /** The real analysis config digest of [[config]] (repair 01: never a hand-written value). */
  val analysisConfigDigest: String = settings.configDigest

  val analysisManifest: RunManifest = RunManifest(
    SchemaVersions.RunManifest,
    "spark.collaboration-analysis",
    analysisCode,
    analysisConfigDigest,
    snapshots,
    SortedMap(
      "benchmarkPairsDigest" -> BenchmarkPairList.digest(benchmarkPairs),
      "decisionRunId" -> decisionRunId,
      "groupingRunId" -> groupingRunId,
      "inputRunId" -> inputRunId
    ),
    1
  )
  val analysisRunId: String = ok(RunId.of(analysisManifest))

  val lineage: AnalysisLineage = AnalysisLineage(
    groupingRunId = groupingRunId,
    decisionRunId = decisionRunId,
    inputRunId = inputRunId,
    codeVersion = analysisCode,
    configDigest = analysisConfigDigest,
    groupingPolicyVersion = "er-grouping.v1",
    matchPolicyVersion = erPolicy,
    identityVersion = "v1",
    groupingEvaluation = GroupingEvaluation.Unevaluated
  )

  /** Literal input schema, so a later PublicationTeam bump does not change the frozen examples. */
  val TeamSchema = "scholarchain.publication-team.v1"

  def prov(subject: String): Provenance = Provenance(
    sourceDataset = SourceType.Derived,
    sourceRecordId = subject,
    snapshot = "dblp-2026-10-03",
    runId = analysisRunId,
    sourceSchemaVersion = TeamSchema,
    policyVersion = Some(graphPolicy),
    modelVersion = None,
    promptVersion = None
  )

  // ---------------------------------------------------------------- grouping-run input

  /** Provenance of a record of this fixture's grouping run. */
  def groupingProv(subject: String, inputSchema: String): Provenance =
    AssignmentFixtures.groupingProv(subject, inputSchema).copy(runId = groupingRunId)

  /** A DE-P6-01 assignment fixture restamped as a record of this fixture's grouping run. */
  def stamped(w: WorkAssignment): WorkAssignment =
    w.copy(provenance = w.provenance.copy(runId = groupingRunId))
  def stamped(r: ResearcherAssignment): ResearcherAssignment =
    r.copy(provenance = r.provenance.copy(runId = groupingRunId))

  /** A counted single work of this fixture's grouping run. */
  def countedWork(w: String): WorkAssignment = stamped(
    AssignmentFixtures.work(
      w,
      w,
      WorkGroupStatus.Single,
      1,
      GraphEligibility.Counted,
      reasons = Vector("grouping.single")
    )
  )

  def g(n: Int): String = ok(WorkId.dblp(s"journals/example/Synthetic3$n"))
  val g1: String = g(1)
  val g2: String = g(2)
  val g3: String = g(3)
  val g4: String = g(4)
  val g5: String = g(5)
  val g6: String = g(6)
  val u1: String = ok(MentionId.of(g1, 2))

  def team(w: String, year: Option[Int], ids: Vector[String], unresolved: Vector[String]) =
    PublicationTeam(
      schemaVersion = SchemaVersions.PublicationTeam,
      workId = w,
      year = year,
      researcherIds = ids,
      teamSize = ids.size + unresolved.size,
      unresolvedMentionIds = unresolved,
      provenance = groupingProv(w, "scholarchain.work-assignment.v1")
    )

  val teams: Vector[PublicationTeam] = Vector(
    team(g1, Some(2016), Vector(rA, rB), Vector(u1)),
    team(g2, Some(2018), Vector(rB, rC), Vector.empty),
    team(g3, Some(2021), Vector(rC, rD), Vector.empty),
    team(g4, Some(2022), Vector(rE), Vector.empty),
    team(g5, None, Vector(rA, rD), Vector.empty),
    team(g6, Some(2023), Vector(rD, rC), Vector.empty)
  )

  val works: Vector[WorkAssignment] =
    Vector(g1, g2, g3, g4, g5, g6).map(countedWork) :+ stamped(AssignmentFixtures.enrichmentSingle)

  private def singleton(work: String, pos: Int, rid: String, anchor: (String, String)) =
    stamped(
      AssignmentFixtures.researcher(
        ok(MentionId.of(work, pos)),
        Some(work),
        Some(pos),
        AssignmentStatus.Singleton,
        Some(rid),
        Some(anchor),
        1,
        reasons = Vector(s"grouping.anchor.${anchor._1}", "grouping.singleton")
      )
    )

  val collisionCode = "grouping.anchor-collision.orcid"

  val researchers: Vector[ResearcherAssignment] = Vector(
    stamped(
      AssignmentFixtures.resolvedMention
        .copy(reasonCodes = Vector(collisionCode, "grouping.anchor.orcid", "grouping.resolved"))
    ),
    stamped(AssignmentFixtures.resolvedProfile),
    singleton(g2, 0, rB, "orcid" -> orcidB),
    stamped(AssignmentFixtures.singletonMention),
    singleton(g3, 1, rD, "dblp-pid" -> "00/0003"),
    singleton(g4, 0, rE, "dblp-pid" -> "00/0004")
  )

  val decisions: Vector[MatchDecision] = AssignmentFixtures.decisions

  val input: GraphInput = GraphInput(teams, works, researchers)

  // ---------------------------------------------------------------- C5 identity

  private val unknown = DecisionConfidence.unknown(ConfidenceUnknownReason.NoSupportingDecision)
  val idA: IdentityConfidence = IdentityConfidence(
    IdentityStatus.Resolved,
    "orcid",
    2,
    Vector(collisionCode),
    DecisionConfidence.measured(0.97)
  )
  val idB: IdentityConfidence =
    IdentityConfidence(IdentityStatus.Singleton, "orcid", 1, Vector.empty, unknown)
  val idC: IdentityConfidence =
    IdentityConfidence(IdentityStatus.SingletonT1Anchored, "dblp-pid", 1, Vector.empty, unknown)
  val idD: IdentityConfidence =
    IdentityConfidence(IdentityStatus.Singleton, "dblp-pid", 1, Vector.empty, unknown)
  val idE: IdentityConfidence = idD
  val identityOf: Map[String, IdentityConfidence] =
    Map(rA -> idA, rB -> idB, rC -> idC, rD -> idD, rE -> idE)

  // ---------------------------------------------------------------- C1 metrics

  def metric(
      r: String,
      w: TimeWindow,
      p: Long,
      c: Long,
      t: Long,
      u: Long,
      wwu: Long,
      m: Long,
      maxWorks: Vector[String],
      wd: Long
  ): ResearcherMetricsV2 = ResearcherMetricsV2(
    schemaVersion = SchemaVersions.ResearcherMetricsV2,
    researcherId = r,
    window = w,
    publicationCount = p,
    distinctCoauthorCount = c,
    totalCoauthorOccurrences = t,
    unresolvedCoauthorOccurrences = u,
    worksWithUnresolvedCoauthors = wwu,
    averageCoauthorsPerPaper = t.toDouble / p.toDouble,
    maximumCoauthorsOnOnePaper = m,
    maximumTeamWorkIds = maxWorks,
    weightedDegree = wd,
    identity = identityOf(r),
    lineage = lineage,
    provenance = prov(r)
  )

  private def sorted(xs: String*): Vector[String] =
    xs.toVector.sortWith((a, b) => Canonical.compareUtf8(a, b) < 0)

  val metricA1: ResearcherMetricsV2 = metric(rA, w1, 1, 1, 2, 1, 1, 2, Vector(g1), 1)
  val metricB1: ResearcherMetricsV2 = metric(rB, w1, 2, 2, 3, 1, 1, 2, Vector(g1), 2)
  val metricC1: ResearcherMetricsV2 = metric(rC, w1, 1, 1, 1, 0, 0, 1, Vector(g2), 1)
  val metricC2: ResearcherMetricsV2 = metric(rC, w2, 2, 1, 2, 0, 0, 1, sorted(g3, g6), 2)
  val metricD2: ResearcherMetricsV2 = metric(rD, w2, 2, 1, 2, 0, 0, 1, sorted(g3, g6), 2)
  val metricE2: ResearcherMetricsV2 = metric(rE, w2, 1, 0, 0, 0, 0, 0, Vector(g4), 0)
  val metrics: Vector[ResearcherMetricsV2] =
    Vector(metricA1, metricB1, metricC1, metricC2, metricD2, metricE2)

  // ---------------------------------------------------------------- C2 edges

  def edge(x: String, y: String, w: TimeWindow, years: Vector[(Int, Long)], ws: Vector[String]) =
    ok(
      CoauthorEdgeV2.build(
        x,
        y,
        w,
        years.map { case (yr, c) => YearCount(yr, c) },
        ws,
        lineage,
        graphPolicy,
        prov
      )
    )

  val edgeAB: CoauthorEdgeV2 = edge(rB, rA, w1, Vector(2016 -> 1L), Vector(g1))
  val edgeBC: CoauthorEdgeV2 = edge(rB, rC, w1, Vector(2018 -> 1L), Vector(g2))
  val edgeCD: CoauthorEdgeV2 = edge(rD, rC, w2, Vector(2021 -> 1L, 2023 -> 1L), Vector(g6, g3))
  val edges: Vector[CoauthorEdgeV2] = Vector(edgeAB, edgeBC, edgeCD)

  // ---------------------------------------------------------------- C3 structure

  def component(r: String, w: TimeWindow, members: Vector[String]): ComponentMembership =
    ComponentMembership(
      SchemaVersions.ComponentMembership,
      r,
      w,
      ok(ComponentId.of(w, graphPolicy, members)),
      members.size.toLong,
      lineage,
      prov(r)
    )

  val components: Vector[ComponentMembership] =
    Vector(rA, rB, rC).map(component(_, w1, Vector(rA, rB, rC))) ++
      Vector(rC, rD).map(component(_, w2, Vector(rC, rD))) :+ component(rE, w2, Vector(rE))

  def componentIdOf(r: String, w: TimeWindow): String =
    components.find(c => c.researcherId == r && c.window == w).get.componentId

  def community(r: String, w: TimeWindow, members: Vector[String]): CommunityMembership =
    CommunityMembership(
      SchemaVersions.CommunityMembership,
      r,
      w,
      ok(
        CommunityId.of(
          w,
          graphPolicy,
          settings.communityAlgorithm,
          settings.communityParameters,
          members
        )
      ),
      members.size.toLong,
      componentIdOf(r, w),
      settings.communityAlgorithm,
      settings.communityParameters,
      lineage,
      prov(r)
    )

  val communities: Vector[CommunityMembership] =
    Vector(rA, rB).map(community(_, w1, Vector(rA, rB))) ++
      Vector(community(rC, w1, Vector(rC))) ++
      Vector(rC, rD).map(community(_, w2, Vector(rC, rD))) :+ community(rE, w2, Vector(rE))

  def communityIdOf(r: String, w: TimeWindow): String =
    communities.find(c => c.researcherId == r && c.window == w).get.communityId

  def bridge(
      r: String,
      w: TimeWindow,
      score: Double,
      degree: Long,
      cross: Long,
      reached: Long,
      participation: Double
  ): BridgeMeasure = BridgeMeasure(
    SchemaVersions.BridgeMeasure,
    r,
    w,
    communityIdOf(r, w),
    settings.bridgeAlgorithm,
    settings.bridgeParameters,
    score,
    degree,
    cross,
    reached,
    participation,
    lineage,
    prov(r)
  )

  val bridgeB1: BridgeMeasure = bridge(rB, w1, 1.0, 2, 1, 1, 0.5)
  val bridges: Vector[BridgeMeasure] = Vector(
    bridge(rA, w1, 0.0, 1, 0, 0, 0.0),
    bridgeB1,
    bridge(rC, w1, 0.0, 1, 1, 1, 0.0),
    bridge(rC, w2, 0.0, 1, 0, 0, 0.0),
    bridge(rD, w2, 0.0, 1, 0, 0, 0.0),
    bridge(rE, w2, 0.0, 0, 0, 0, 0.0)
  )

  def core(r: String, w: TimeWindow, k: Long, degree: Long): Coreness =
    Coreness(SchemaVersions.Coreness, r, w, k, degree, lineage, prov(r))

  val corenessB1: Coreness = core(rB, w1, 1, 2)
  val coreness: Vector[Coreness] = Vector(
    core(rA, w1, 1, 1),
    corenessB1,
    core(rC, w1, 1, 1),
    core(rC, w2, 1, 1),
    core(rD, w2, 1, 1),
    core(rE, w2, 0, 0)
  )

  def path(
      w: TimeWindow,
      nodes: Vector[String],
      hops: Vector[PathHop],
      ordinal: Int = 0,
      bounds: PathBounds = settings.pathBounds
  ): EvidencePath = {
    val id = ok(EvidencePathId.of(w, graphPolicy, nodes))
    EvidencePath(
      SchemaVersions.EvidencePath,
      id,
      w,
      nodes.head,
      nodes.last,
      ordinal,
      hops.size,
      nodes,
      hops,
      nodes.map(identityOf),
      bounds,
      lineage,
      prov(id)
    )
  }

  /** A < C (ORCID IDs sort before sc IDs), so the path runs A, B, C. */
  val pathAC: EvidencePath = path(
    w1,
    Vector(rA, rB, rC),
    Vector(PathHop(edgeAB.edgeId, Vector(g1)), PathHop(edgeBC.edgeId, Vector(g2)))
  )
  val pathCD: EvidencePath =
    path(
      w2,
      Vector(edgeCD.researcherA, edgeCD.researcherB),
      Vector(PathHop(edgeCD.edgeId, sorted(g3, g6)))
    )
  val paths: Vector[EvidencePath] = Vector(pathAC, pathCD)

  // ---------------------------------------------------------------- C4 windows

  def windowRecord(
      w: TimeWindow,
      ordinal: Int,
      works: Long,
      researchers: Long,
      edges: Long,
      comps: Long,
      comms: Long,
      unresolved: Long
  ): AnalysisWindow = AnalysisWindow(
    SchemaVersions.AnalysisWindow,
    w,
    ordinal,
    works,
    1,
    researchers,
    edges,
    comps,
    comms,
    unresolved,
    lineage,
    prov(AnalysisWindow.subjectOf(w))
  )

  val window1: AnalysisWindow = windowRecord(w1, 0, 2, 3, 2, 1, 2, 1)
  val windows: Vector[AnalysisWindow] = Vector(window1, windowRecord(w2, 1, 3, 3, 1, 2, 2, 0))

  // ---------------------------------------------------------------- C6 summary inputs

  def summary(
      d: DocumentType,
      subject: String,
      w: TimeWindow,
      facts: Vector[SummaryFact],
      identity: Option[IdentityConfidence]
  ): SemanticSummaryInput = {
    val id = ok(SummaryInputId.of(d, subject, w, graphPolicy))
    SemanticSummaryInput(
      SchemaVersions.SemanticSummaryInput,
      id,
      d,
      subject,
      w,
      facts,
      identity,
      lineage,
      prov(id)
    )
  }

  val profileB1: SemanticSummaryInput = summary(
    DocumentType.ResearcherProfile,
    rB,
    w1,
    Vector(
      SummaryFact(
        "publicationCount",
        "2",
        Vector(SummaryEvidenceRef(SummaryEvidenceKind.ResearcherMetrics, rB))
      ),
      SummaryFact(
        "community",
        communityIdOf(rB, w1),
        Vector(SummaryEvidenceRef(SummaryEvidenceKind.Community, communityIdOf(rB, w1)))
      )
    ),
    Some(idB)
  )

  val collaborationAB1: SemanticSummaryInput = summary(
    DocumentType.CollaborationSummary,
    edgeAB.edgeId,
    w1,
    Vector(
      SummaryFact(
        "jointPublicationCount",
        "1",
        Vector(
          SummaryEvidenceRef(SummaryEvidenceKind.CoauthorEdge, edgeAB.edgeId),
          SummaryEvidenceRef(SummaryEvidenceKind.CountedWork, g1)
        )
      )
    ),
    None
  )

  val pathSummaryAC: SemanticSummaryInput = summary(
    DocumentType.PathSummary,
    pathAC.pathId,
    w1,
    Vector(
      SummaryFact(
        "hopCount",
        "2",
        Vector(SummaryEvidenceRef(SummaryEvidenceKind.EvidencePath, pathAC.pathId))
      )
    ),
    None
  )

  val summaryInputs: Vector[SemanticSummaryInput] =
    Vector(profileB1, collaborationAB1, pathSummaryAC)

  val outputs: AnalysisOutputs = AnalysisOutputs(
    metrics,
    edges,
    components,
    communities,
    bridges,
    coreness,
    paths,
    windows,
    summaryInputs
  )
}
