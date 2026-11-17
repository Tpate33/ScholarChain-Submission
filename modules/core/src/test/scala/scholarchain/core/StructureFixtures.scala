package scholarchain.core

import scala.collection.immutable.SortedMap

import AnalysisFixtures.*
import Fixtures.{graphPolicy, mention0, mention1, ok, researcherA, workId, workId2}

/**
 * Synthetic DE-P6-03 fixtures. Every identifier is synthetic ([[Fixtures]]); nothing is read from
 * target/, a run directory or a gold set. Every expected value below is derived by hand in the
 * comments, never by calling the code under test.
 *
 * Canonical graph (course item 1), over the DE-P6-01 grouping-run fixtures
 * ([[AssignmentFixtures]]):
 *   - works: `workId` (DBLP, counted representative of {workId, W1}), W1 (OpenAlex copy), `workId2`
 *     (counted single), W2 (enrichment-only single);
 *   - mentions: mention0 (workId, position 0, resolved to researcher A, which also has an OpenAlex
 *     profile member), mention1 (workId, position 1, t1-anchored singleton C), mentionU (workId2,
 *     position 0, unresolved);
 *   - curated sources: venue `venue:dblp:journals/example` (asserted by workId; W1's
 *     `venue:openalex:S1` has no record), topic T1 (asserted by W1; T2 has no record), institution
 *     I1 (affiliation of mention0 and of mentionU);
 *   - one coauthor edge A - C in 2020-2024 (work workId).
 */
object StructureFixtures {
  import AssignmentFixtures.{mentionU, singletonId, workW1, workW2}

  // ---------------------------------------------------------------- canonical graph input

  private val i1 = Fixtures.institution.institutionId
  private val t1 = Fixtures.topic.topicId
  private val dblpVenue = Fixtures.venue.venueId

  val mentionURecord: AuthorMention = AuthorMention(
    schemaVersion = SchemaVersions.AuthorMention,
    mentionId = mentionU,
    workId = workId2,
    position = 0,
    rawName = "Cy Example",
    normalizedName = "cy example",
    sourceAuthorId = None,
    orcid = None,
    affiliationIds = Vector(i1),
    rawAffiliations = Vector("Example University"),
    provenance = Fixtures.dblpProv
  )

  val sourceWork2: WorkRecord = Fixtures.work.copy(
    workId = workId2,
    sourceRecordId = "conf/example/Synthetic22",
    year = Some(2022),
    venueId = None,
    doi = None,
    authors = Vector(mentionURecord),
    eeLinks = Vector.empty,
    urls = Vector.empty
  )

  val sourceW2: WorkRecord = Fixtures.openAlexWork.copy(
    workId = workW2,
    sourceRecordId = "W2",
    year = Some(2020),
    venueId = None,
    topicIds = Vector.empty,
    additionalIds = Vector.empty,
    provenance = Fixtures.openAlexProv("W2")
  )

  /** The A - C coauthor edge of 2020-2024 (one joint work, `workId`). */
  val coauthorAC: CoauthorEdgeV2 =
    edge(researcherA, singletonId, w2, Vector(2021 -> 1L), Vector(workId))

  val graphInput: CanonicalGraphInput = CanonicalGraphInput(
    works = AssignmentFixtures.works,
    researchers = AssignmentFixtures.researchers,
    authorships = AssignmentFixtures.authorships,
    sourceWorks = Vector(Fixtures.work, Fixtures.openAlexWork, sourceWork2, sourceW2),
    mentions = Vector(mention0.copy(affiliationIds = Vector(i1)), mention1, mentionURecord),
    venues = Vector(Fixtures.venue),
    institutions = Vector(Fixtures.institution),
    topics = Vector(Fixtures.topic)
  )

  // ---------------------------------------------------------------- hand-derived tables

  private def props(kv: (String, String)*) = SortedMap(kv*)

  /** Hand derivation of the node table (see the object comment). */
  val handNodes: Vector[ExpectedNode] = Vector(
    // A: resolved group of mention0 and the OpenAlex profile, anchored on its ORCID iD.
    ExpectedNode(
      researcherA,
      NodeLabel.Researcher,
      props("anchorNamespace" -> "orcid", "identityStatus" -> "resolved", "memberCount" -> "2")
    ),
    // C: singleton with a pending decision (dR2): t1-anchored on its DBLP pid.
    ExpectedNode(
      singletonId,
      NodeLabel.Researcher,
      props(
        "anchorNamespace" -> "dblp-pid",
        "identityStatus" -> "singleton-t1-anchored",
        "memberCount" -> "1"
      )
    ),
    ExpectedNode(
      workId,
      NodeLabel.Work,
      props("graphEligibility" -> "counted", "groupSize" -> "2", "year" -> "2021")
    ),
    ExpectedNode(
      workId2,
      NodeLabel.Work,
      props("graphEligibility" -> "counted", "groupSize" -> "1", "year" -> "2022")
    ),
    ExpectedNode(
      workW2,
      NodeLabel.Work,
      props("graphEligibility" -> "enrichment-only", "groupSize" -> "1", "year" -> "2020")
    ),
    ExpectedNode(
      mention0.mentionId,
      NodeLabel.AuthorMention,
      props("assignmentStatus" -> "resolved", "position" -> "0", "workId" -> workId)
    ),
    ExpectedNode(
      mention1.mentionId,
      NodeLabel.AuthorMention,
      props("assignmentStatus" -> "singleton", "position" -> "1", "workId" -> workId)
    ),
    // The unresolved mention is a node of its own: never merged into a researcher.
    ExpectedNode(
      mentionU,
      NodeLabel.AuthorMention,
      props("assignmentStatus" -> "unresolved", "position" -> "0", "workId" -> workId2)
    ),
    ExpectedNode(dblpVenue, NodeLabel.Venue, props("name" -> "Example Journal")),
    ExpectedNode(i1, NodeLabel.Institution, props("name" -> "Example University")),
    ExpectedNode(t1, NodeLabel.Topic, props("name" -> "Example Topic"))
  )

  /** Hand derivation of the edge table. */
  val handEdges: Vector[ExpectedEdge] = Vector(
    ExpectedEdge(
      EdgeLabel.ResolvesTo,
      mention0.mentionId,
      researcherA,
      mention0.mentionId,
      props("assignmentStatus" -> "resolved")
    ),
    ExpectedEdge(
      EdgeLabel.ResolvesTo,
      mention1.mentionId,
      singletonId,
      mention1.mentionId,
      props("assignmentStatus" -> "singleton")
    ),
    ExpectedEdge(
      EdgeLabel.Authored,
      researcherA,
      workId,
      mention0.mentionId,
      props("position" -> "0")
    ),
    ExpectedEdge(
      EdgeLabel.Authored,
      singletonId,
      workId,
      mention1.mentionId,
      props("position" -> "1")
    ),
    // The unresolved mention keeps its authorship: mention -> work.
    ExpectedEdge(EdgeLabel.Authored, mentionU, workId2, mentionU, props("position" -> "0")),
    // workId asserts the DBLP venue; W1's S1 has no venue record (unmatched).
    ExpectedEdge(EdgeLabel.PublishedIn, workId, dblpVenue, workId, props()),
    // W1 (a member of workId) asserts T1 and T2; only T1 has a record.
    ExpectedEdge(EdgeLabel.HasTopic, workId, t1, workW1, props()),
    ExpectedEdge(EdgeLabel.AffiliatedWith, researcherA, i1, mention0.mentionId, props()),
    ExpectedEdge(EdgeLabel.AffiliatedWith, mentionU, i1, mentionU, props()),
    ExpectedEdge(
      EdgeLabel.CoauthoredWith,
      researcherA,
      singletonId,
      coauthorAC.edgeId,
      props(
        "firstCollaborationYear" -> "2021",
        "jointPublicationCount" -> "1",
        "lastCollaborationYear" -> "2021",
        "windowEndYear" -> "2024",
        "windowStartYear" -> "2020"
      )
    )
  )

  val handUnmatched: UnmatchedReferences = UnmatchedReferences(1, 1, 0)

  val nodes: Vector[GraphNodeV2] =
    handNodes.map(n => ok(GraphNodeV2.build(n.nodeId, n.label, n.properties, lineage, prov)))
  val graphEdges: Vector[GraphEdgeV2] = handEdges.map(e =>
    ok(
      GraphEdgeV2.build(
        e.edgeType,
        e.fromId,
        e.toId,
        e.sourceEvidence,
        e.properties,
        lineage,
        graphPolicy,
        prov
      )
    )
  )

  def nodeOf(id: String): GraphNodeV2 = nodes.find(_.nodeId == id).get
  def edgeOf(t: EdgeLabel, from: String, to: String): GraphEdgeV2 =
    graphEdges.find(e => e.edgeType == t && e.fromId == from && e.toId == to).get

  // ---------------------------------------------------------------- window structures

  def structure(
      w: TimeWindow,
      ordinal: Int,
      researchers: Long,
      edges: Long,
      comps: Long,
      largest: Long,
      singletons: Long,
      comms: Long,
      largestComm: Long,
      cross: Long,
      iterations: Int,
      converged: Boolean,
      maxCore: Long
  ): WindowStructure = WindowStructure(
    SchemaVersions.WindowStructure,
    w,
    ordinal,
    researchers,
    edges,
    comps,
    largest,
    WindowStructure.fraction(largest, researchers),
    singletons,
    comms,
    largestComm,
    cross,
    "label-propagation",
    iterations,
    converged,
    maxCore,
    lineage,
    prov(AnalysisWindow.subjectOf(w))
  )

  /**
   * 2015-2019: A, B, C; edges A-B, B-C; one component of 3 (fraction 1.0, no singleton);
   * communities {A,B}, {C}; B-C crosses (bridges: 0 + 1 + 1 = 2 = 2 * 1); max coreness 1.
   */
  val structure1: WindowStructure = structure(w1, 0, 3, 2, 1, 3, 0, 2, 2, 1, 2, true, 1)

  /**
   * 2020-2024: C, D, E; edge C-D; components {C,D}, {E} (largest 2 of 3, one singleton);
   * communities {C,D}, {E}; no crossing edge; max coreness 1.
   */
  val structure2: WindowStructure = structure(w2, 1, 3, 1, 2, 2, 1, 2, 2, 0, 2, true, 1)
  val structures: Vector[WindowStructure] = Vector(structure1, structure2)

  /** The fixture manifest plus the label-propagation outcome parameters of both windows. */
  val structureManifest: RunManifest = analysisManifest.copy(parameters =
    analysisManifest.parameters ++ SortedMap(
      "community.converged.2015-2019" -> "true",
      "community.converged.2020-2024" -> "true",
      "community.iterations.2015-2019" -> "2",
      "community.iterations.2020-2024" -> "2"
    )
  )

  // ---------------------------------------------------------------- path-length estimates

  val samplingSettings: scholarchain.core.config.AnalysisRunSettings = settingsOf(
    AnalysisFixtures.config.copy(analysis =
      AnalysisFixtures.config.analysis.copy(
        pathLengthSampleSize = Some(2),
        pathLengthSeed = Some(7L),
        pathLengthMinComponentSize = Some(2)
      )
    )
  )

  /** Distances on the 2015-2019 path A - B - C: A-C is 2, every other pair 1 (hand). */
  def handPathCounts(sources: Vector[String]): Vector[DistanceCount] = {
    def d(x: String, y: String) = if (Set(x, y) == Set(rA, rC)) 2 else 1
    sources
      .flatMap(s => Vector(rA, rB, rC).filter(_ != s).map(d(s, _)))
      .groupBy(identity)
      .toVector
      .sortBy(_._1)
      .map { case (dist, xs) => DistanceCount(dist, xs.size.toLong) }
  }

  val sampleW1: Vector[String] = ok(PathSampleKey.sample(7L, w1, Vector(rA, rB, rC), 2))

  val estimateW1: PathLengthEstimate = ok(
    PathLengthEstimate.build(
      w1,
      componentIdOf(rA, w1),
      3,
      7L,
      2,
      sampleW1,
      handPathCounts(sampleW1),
      lineage,
      prov
    )
  )

  /** 2020-2024: component {C, D} (size 2), both sampled: exact, distances {1: 2}. */
  val estimateW2: PathLengthEstimate = ok(
    PathLengthEstimate.build(
      w2,
      componentIdOf(rC, w2),
      2,
      7L,
      2,
      Vector(rC, rD),
      Vector(DistanceCount(1, 2)),
      lineage,
      prov
    )
  )
  val estimates: Vector[PathLengthEstimate] = Vector(estimateW1, estimateW2)

  // ---------------------------------------------------------------- temporal comparison

  /**
   * 2015-2019 vs 2020-2024: no shared year, both 5 years long; g5 has no year (works without a year
   * are excluded); both converged. Researchers {A,B,C} and {C,D,E}: C in both, A and B only in the
   * first, D and E only in the second. Edges {A-B, B-C} and {C-D}: none in both.
   */
  val comparison: WindowComparison = WindowComparison(
    SchemaVersions.WindowComparison,
    w1,
    w2,
    0,
    true,
    0,
    Vector(ComparisonLimitation.GroupingUnevaluated, ComparisonLimitation.WorksWithoutYearExcluded),
    1,
    2,
    2,
    0,
    2,
    1,
    lineage,
    prov(WindowComparison.subjectOf(w1, w2))
  )

  def st(
      p: Long,
      c: Long,
      a: Double,
      m: Long,
      comp: Long,
      largest: Boolean,
      comm: Long,
      cross: Long,
      reached: Long,
      pc: Double,
      core: Long
  ): ResearcherWindowState =
    ResearcherWindowState(p, c, a, m, comp, largest, comm, cross, reached, pc, core)

  def change(
      r: String,
      presence: TemporalPresence,
      from: Option[ResearcherWindowState],
      to: Option[ResearcherWindowState],
      retained: Long,
      gained: Long,
      lost: Long,
      overlap: Option[Long]
  ): ResearcherTemporalChange = ResearcherTemporalChange(
    SchemaVersions.ResearcherTemporalChange,
    r,
    w1,
    w2,
    presence,
    from,
    to,
    retained,
    gained,
    lost,
    overlap,
    lineage,
    prov(r)
  )

  /**
   * Hand values from the C1/C3 fixtures: A (P 1, C 1, A_r 2.0, M 2; component 3, community {A,B};
   * no crossing; coreness 1; lost B), B (P 2, C 2, A_r 1.5, M 2; cross 1, reached 1, participation
   * 0.5; lost A and C), C (first: P 1, C 1, A_r 1.0, community {C}, cross 1; second: P 2, C 1, A_r
   * 1.0, component and community {C,D}; coauthor B lost, D gained; community overlap {C} = 1), D
   * (gained C), E (no coauthor; component of 1 is not the largest).
   */
  val changeC: ResearcherTemporalChange = change(
    rC,
    TemporalPresence.InBoth,
    Some(st(1, 1, 1.0, 1, 3, true, 1, 1, 1, 0.0, 1)),
    Some(st(2, 1, 1.0, 1, 2, true, 2, 0, 0, 0.0, 1)),
    0,
    1,
    1,
    Some(1)
  )
  val changes: Vector[ResearcherTemporalChange] = Vector(
    change(
      rA,
      TemporalPresence.OnlyFrom,
      Some(st(1, 1, 2.0, 2, 3, true, 2, 0, 0, 0.0, 1)),
      None,
      0,
      0,
      1,
      None
    ),
    change(
      rB,
      TemporalPresence.OnlyFrom,
      Some(st(2, 2, 1.5, 2, 3, true, 2, 1, 1, 0.5, 1)),
      None,
      0,
      0,
      2,
      None
    ),
    changeC,
    change(
      rD,
      TemporalPresence.OnlyTo,
      None,
      Some(st(2, 1, 1.0, 1, 2, true, 2, 0, 0, 0.0, 1)),
      0,
      1,
      0,
      None
    ),
    change(
      rE,
      TemporalPresence.OnlyTo,
      None,
      Some(st(1, 0, 0.0, 0, 1, false, 1, 0, 0, 0.0, 0)),
      0,
      0,
      0,
      None
    )
  )

  /** The DE-P6-02 fixture outputs plus the DE-P6-03 datasets above. */
  val fullOutputs: AnalysisOutputs = outputs.copy(
    nodes = nodes,
    graphEdges = graphEdges,
    windowStructures = structures,
    pathLengthEstimates = estimates,
    windowComparisons = Vector(comparison),
    temporalChanges = changes,
    entitySummaryInputs = Vector.empty
  )

  // ---------------------------------------------------------------- entity summaries

  def entitySummary(
      d: DocumentType,
      subject: String,
      facts: Vector[EntityFact]
  ): EntitySummaryInput = {
    val id = ok(EntitySummaryInputId.of(d, subject, graphPolicy))
    EntitySummaryInput(SchemaVersions.EntitySummaryInput, id, d, subject, facts, lineage, prov(id))
  }

  def ref(k: EntityEvidenceKind, id: String): EntityEvidenceRef = EntityEvidenceRef(k, id)

  val workSummary: EntitySummaryInput = entitySummary(
    DocumentType.WorkSummary,
    workId,
    Vector(
      EntityFact(
        "title",
        "A Synthetic Study of Collaboration",
        Vector(ref(EntityEvidenceKind.SourceWork, workW1))
      ),
      EntityFact("year", "2021", Vector(ref(EntityEvidenceKind.GraphNode, workId))),
      EntityFact(
        "author",
        researcherA,
        Vector(
          ref(EntityEvidenceKind.GraphEdge, edgeOf(EdgeLabel.Authored, researcherA, workId).edgeId)
        )
      ),
      EntityFact(
        "venue",
        dblpVenue,
        Vector(
          ref(EntityEvidenceKind.GraphEdge, edgeOf(EdgeLabel.PublishedIn, workId, dblpVenue).edgeId)
        )
      ),
      EntityFact(
        "topic",
        t1,
        Vector(ref(EntityEvidenceKind.GraphEdge, edgeOf(EdgeLabel.HasTopic, workId, t1).edgeId))
      ),
      EntityFact("name", "Example Topic", Vector(ref(EntityEvidenceKind.GraphNode, t1)))
    )
  )

  val topicSummary: EntitySummaryInput = entitySummary(
    DocumentType.TopicSummary,
    t1,
    Vector(
      EntityFact("name", "Example Topic", Vector(ref(EntityEvidenceKind.GraphNode, t1))),
      EntityFact(
        "work",
        workId,
        Vector(ref(EntityEvidenceKind.GraphEdge, edgeOf(EdgeLabel.HasTopic, workId, t1).edgeId))
      )
    )
  )

  // ---------------------------------------------------------------- load manifests

  private def part(path: String, schema: String, f: PartFormat, src: RowCountSource, c: Char) =
    PartFileV2(path, schema, f, 3, src, 1000, c.toString * 64)

  /** A literal analysis-run part manifest (counts and hashes are synthetic). */
  val loadParts: PartManifestV2 = PartManifestV2(
    SchemaVersions.PartManifestV2,
    analysisRunId,
    Vector(
      PartFileV2(
        PartManifest.RunManifestFile,
        SchemaVersions.RunManifest,
        PartFormat.Jsonl,
        1,
        RowCountSource.LfCount,
        500,
        "a" * 64
      ),
      part(
        "metrics/part-00000.parquet",
        SchemaVersions.ResearcherMetricsV2,
        PartFormat.Parquet,
        RowCountSource.ParquetFooter,
        'b'
      ),
      part(
        "neptune/authored-edges.csv",
        SchemaVersions.GraphEdgeV2,
        PartFormat.Csv,
        RowCountSource.Writer,
        'c'
      ),
      part(
        "neptune/researcher-vertices.csv",
        SchemaVersions.GraphNodeV2,
        PartFormat.Csv,
        RowCountSource.Writer,
        'd'
      ),
      part(
        "nodes/part-00000.parquet",
        SchemaVersions.GraphNodeV2,
        PartFormat.Parquet,
        RowCountSource.ParquetFooter,
        'e'
      ),
      part(
        "summaries/part-00000.jsonl",
        SchemaVersions.SemanticSummaryInput,
        PartFormat.Jsonl,
        RowCountSource.LfCount,
        'f'
      )
    )
  )

  val partsSha: String = "9" * 64

  def load(t: TargetStore, roles: (String, String)*): StoreLoadManifest =
    ok(StoreLoadManifest.build(t, Right(loadParts), partsSha, roles.toMap))

  val loads: Vector[StoreLoadManifest] = Vector(
    load(
      TargetStore.NeptuneBulkLoad,
      "neptune/authored-edges.csv" -> "authored-edges",
      "neptune/researcher-vertices.csv" -> "researcher-vertices"
    ),
    load(TargetStore.DynamoDb, "metrics/part-00000.parquet" -> "researcher-metrics"),
    load(TargetStore.S3Vectors, "summaries/part-00000.jsonl" -> "summary-inputs"),
    load(
      TargetStore.S3,
      "metrics/part-00000.parquet" -> "researcher-metrics",
      "nodes/part-00000.parquet" -> "graph-nodes",
      "summaries/part-00000.jsonl" -> "summary-inputs"
    )
  )
}
