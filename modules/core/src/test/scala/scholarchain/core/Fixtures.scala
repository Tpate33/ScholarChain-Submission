package scholarchain.core

import scala.collection.immutable.SortedMap

/**
 * Valid, synthetic fixture records. Every identifier is synthetic: the ORCID iDs
 * 0000-0000-0000-0001 and 0000-0000-0000-001X are checksum-valid test values, the DOI uses the
 * 10.1234 example prefix, the DBLP keys use the `example` stream, and the ISSN 0000-0019 is a
 * hand-computed checksum-valid value (weights 8..2 over 0000001 give 2, so the check digit is 9)
 * that is not asserted to identify any serial. No real person, work, or dataset fact is asserted.
 * The raw source schema versions (`dblp-raw/1.0.0`, `openalex-raw/1.0.0`) are the `schema_version`
 * values of `datasets/dataset-manifest.yaml`.
 */
object Fixtures {
  def ok[A](e: Either[?, A]): A =
    e.fold(err => throw new AssertionError(s"fixture is invalid: $err"), identity)

  val manifest: RunManifest = RunManifest(
    schemaVersion = SchemaVersions.RunManifest,
    stage = "hadoop.dblp-extract",
    codeVersion = "0123456789abcdef0123456789abcdef01234567",
    configDigest = "a" * 64,
    inputSnapshots = Vector(SnapshotRef(SourceType.Dblp, "dblp-2026-10-03")),
    parameters = SortedMap("maxRecords" -> "1000"),
    attempt = 1
  )
  val runId: String = ok(RunId.of(manifest))

  val graphPolicy = "graph-policy-1.0.0"
  val erPolicy = "er-policy-1.0.0"

  val dblpKey = "journals/example/Synthetic21"
  val workId: String = ok(WorkId.dblp(dblpKey))
  val workId2: String = ok(WorkId.dblp("conf/example/Synthetic22"))
  val orcidA = "0000-0000-0000-0001"
  val orcidB = "0000-0000-0000-001X"
  val researcherA: String = ok(ResearcherId.orcid(orcidA))
  val researcherB: String = ok(ResearcherId.orcid(orcidB))
  val window: TimeWindow = TimeWindow(2015, 2024)
  val queryId: String = ok(QueryId.of("q-0001"))

  def dblpProv: Provenance = Provenance(
    sourceDataset = SourceType.Dblp,
    sourceRecordId = dblpKey,
    snapshot = "dblp-2026-10-03",
    runId = runId,
    sourceSchemaVersion = "dblp-raw/1.0.0",
    policyVersion = None,
    modelVersion = None,
    promptVersion = None
  )

  def openAlexProv(recordId: String): Provenance = Provenance(
    sourceDataset = SourceType.OpenAlex,
    sourceRecordId = recordId,
    snapshot = "openalex-2026-10-14",
    runId = runId,
    sourceSchemaVersion = "openalex-raw/1.0.0",
    policyVersion = None,
    modelVersion = None,
    promptVersion = None
  )

  def orcidProv(recordId: String): Provenance = Provenance(
    sourceDataset = SourceType.Orcid,
    sourceRecordId = recordId,
    snapshot = "orcid-2025-10-01",
    runId = runId,
    sourceSchemaVersion = "orcid-raw/1.0.0",
    policyVersion = None,
    modelVersion = None,
    promptVersion = None
  )

  /**
   * Provenance of the derived fixtures frozen before work-record.v2. Their input schema is pinned
   * to the literal v1 string (a provenance pointer, not a decodable version), so that the
   * WorkRecord bump does not silently change their frozen wire examples.
   */
  def derivedProv(policy: String): Provenance = Provenance(
    sourceDataset = SourceType.Derived,
    sourceRecordId = workId,
    snapshot = "dblp-2026-10-03",
    runId = runId,
    sourceSchemaVersion = "scholarchain.work-record.v1",
    policyVersion = Some(policy),
    modelVersion = None,
    promptVersion = None
  )

  def runtimeProv: Provenance = Provenance(
    sourceDataset = SourceType.QueryRuntime,
    sourceRecordId = queryId,
    snapshot = "dblp-2026-10-03",
    runId = runId,
    sourceSchemaVersion = SchemaVersions.QueryPlan,
    policyVersion = None,
    modelVersion = Some("example-llm-model"),
    promptVersion = Some("answer-prompt-1")
  )

  def mention(position: Int, name: String, orcid: Option[String]): AuthorMention = AuthorMention(
    schemaVersion = SchemaVersions.AuthorMention,
    mentionId = ok(MentionId.of(workId, position)),
    workId = workId,
    position = position,
    rawName = name,
    normalizedName = name.toLowerCase,
    sourceAuthorId = Some(ExternalId(ExternalIdScheme.DblpPid, s"00/000${position + 1}")),
    orcid = orcid,
    affiliationIds = Vector.empty,
    rawAffiliations = Vector.empty,
    provenance = dblpProv
  )

  val mention0: AuthorMention = mention(0, "Ada Example", Some(orcidA))
  val mention1: AuthorMention = mention(1, "Ben Example", None)

  val work: WorkRecord = WorkRecord(
    schemaVersion = SchemaVersions.WorkRecord,
    workId = workId,
    source = SourceType.Dblp,
    sourceRecordId = dblpKey,
    title = "A Synthetic Study of Collaboration",
    normalizedTitle = "a synthetic study of collaboration",
    year = Some(2021),
    venueId = Some("venue:dblp:journals/example"),
    doi = Some("10.1234/example.2021.001"),
    arxivId = None,
    authors = Vector(mention0, mention1),
    abstractTextRef = None,
    provenance = dblpProv,
    recordType = Some("article"),
    venueName = Some("Example J."),
    volume = Some("64"),
    issue = Some("3"),
    pages = Some("1-22"),
    eeLinks = Vector("https://doi.org/10.1234/example.2021.001"),
    urls = Vector("db/journals/example/example64.html#Synthetic21"),
    topicIds = Vector.empty,
    additionalIds = Vector.empty,
    assertionSource = None
  )

  val orcidWorkId: String = ok(WorkId.orcidWork(orcidA, 1001L))

  val assertion: AssertionSource = AssertionSource(
    sourceKind = AssertionSourceKind.Client,
    sourceId = "APP-EXAMPLE000000001",
    sourceName = Some("Example Client"),
    originKind = Some(AssertionSourceKind.Orcid),
    originId = Some(orcidA),
    originName = Some("Ada Example")
  )

  /** A synthetic `source-work-id` of the example client (shape of the UUIDs in the samples). */
  val localWorkId: SourceLocalWorkId =
    SourceLocalWorkId.issuedBy(assertion, "00000000-0000-4000-8000-000000000001")

  /**
   * An ORCID work summary: no contributors, a Scopus EID, its assertion source, and the client's
   * own source-work-id.
   */
  val orcidWork: WorkRecord = WorkRecord(
    schemaVersion = SchemaVersions.WorkRecord,
    workId = orcidWorkId,
    source = SourceType.Orcid,
    sourceRecordId = s"$orcidA/work/1001",
    title = "A Synthetic Study of Collaboration",
    normalizedTitle = "a synthetic study of collaboration",
    year = Some(2021),
    venueId = None,
    doi = Some("10.1234/example.2021.001"),
    arxivId = None,
    authors = Vector.empty,
    abstractTextRef = None,
    provenance = orcidProv(s"$orcidA/work/1001"),
    recordType = Some("journal-article"),
    venueName = Some("Example Journal"),
    volume = None,
    issue = None,
    pages = None,
    eeLinks = Vector.empty,
    urls = Vector.empty,
    topicIds = Vector.empty,
    additionalIds = Vector(ExactId(ExactIdScheme.ScopusEid, "2-s2.0-1000000001")),
    assertionSource = Some(assertion),
    sourceLocalWorkIds = Vector(localWorkId)
  )

  /** An OpenAlex work: topics, a MAG ID, and biblio fields. */
  val openAlexWork: WorkRecord = WorkRecord(
    schemaVersion = SchemaVersions.WorkRecord,
    workId = "work:openalex:W1",
    source = SourceType.OpenAlex,
    sourceRecordId = "W1",
    title = "A Synthetic Study of Collaboration",
    normalizedTitle = "a synthetic study of collaboration",
    year = Some(2021),
    venueId = Some("venue:openalex:S1"),
    doi = Some("10.1234/example.2021.001"),
    arxivId = None,
    authors = Vector.empty,
    abstractTextRef = None,
    provenance = openAlexProv("W1"),
    recordType = Some("article"),
    venueName = Some("Example Journal"),
    volume = Some("64"),
    issue = Some("3"),
    pages = Some("1-22"),
    eeLinks = Vector.empty,
    urls = Vector.empty,
    topicIds = Vector("topic:openalex:T1", "topic:openalex:T2"),
    additionalIds = Vector(ExactId(ExactIdScheme.Mag, "1000001")),
    assertionSource = None
  )

  val venue: Venue = Venue(
    schemaVersion = SchemaVersions.Venue,
    venueId = "venue:dblp:journals/example",
    name = "Example Journal",
    venueType = Some("journal"),
    issns = Vector("0000-0019"),
    externalIds = Vector.empty,
    provenance = dblpProv
  )

  val institution: Institution = Institution(
    schemaVersion = SchemaVersions.Institution,
    institutionId = "institution:openalex:I1",
    name = "Example University",
    rorId = None,
    countryCode = Some("US"),
    externalIds = Vector.empty,
    provenance = openAlexProv("I1")
  )

  val topic: Topic = Topic(
    schemaVersion = SchemaVersions.Topic,
    topicId = "topic:openalex:T1",
    name = "Example Topic",
    parentTopicId = None,
    provenance = openAlexProv("T1")
  )

  val researcher: CanonicalResearcher = CanonicalResearcher(
    schemaVersion = SchemaVersions.CanonicalResearcher,
    researcherId = researcherA,
    displayName = "Ada Example",
    nameVariants = Vector("A. Example"),
    externalIds = Vector(
      ExternalId(ExternalIdScheme.Orcid, orcidA),
      ExternalId(ExternalIdScheme.DblpPid, "00/0001")
    ),
    institutionIds = Vector("institution:openalex:I1"),
    identityConfidence = 0.95,
    mentionCount = 12,
    provenance = derivedProv(erPolicy)
  )

  val matchDecision: MatchDecision = MatchDecision(
    schemaVersion = SchemaVersions.MatchDecision,
    decisionId = ok(
      MatchDecisionId
        .of(MatchEntityKind.Researcher, mention0.mentionId, researcherA, erPolicy, runId)
    ),
    entityKind = MatchEntityKind.Researcher,
    sourceId = mention0.mentionId,
    targetId = researcherA,
    features = SortedMap("orcidExact" -> 1.0, "coauthorOverlap" -> 0.75),
    rulesFired = Vector("orcid-exact"),
    decision = MatchOutcome.Accepted,
    confidence = 0.97,
    reasonCodes = Vector("ORCID_EXACT"),
    conflictingTargetIds = Vector.empty,
    decidedAt = "2026-10-29T12:00:00Z",
    provenance = derivedProv(erPolicy)
  )

  val authorship: AuthorshipRecord = AuthorshipRecord(
    schemaVersion = SchemaVersions.AuthorshipRecord,
    workId = workId,
    mentionId = mention0.mentionId,
    researcherId = researcherA,
    position = 0,
    year = Some(2021),
    matchDecisionId = matchDecision.decisionId,
    matchConfidence = 0.97,
    provenance = derivedProv(erPolicy)
  )

  val team: PublicationTeam = PublicationTeam(
    schemaVersion = SchemaVersions.PublicationTeam,
    workId = workId,
    year = Some(2021),
    researcherIds = Vector(researcherA, researcherB),
    teamSize = 2,
    unresolvedMentionIds = Vector.empty,
    provenance = derivedProv(erPolicy)
  )

  val pairCandidate: CoauthorPairCandidate = ok(
    CoauthorPairCandidate.of(
      mention1.mentionId,
      1,
      mention0.mentionId,
      0,
      workId,
      Some(2021),
      2,
      dblpProv
    )
  )

  // ---------------------------------------------------------------- Phase 4 candidates

  val blockingVersion = "er-keys-1.0.0"
  val featureVersion = "er-features-1.0.0"

  /** Provenance of a derived Phase 4 candidate record (input schema work-record.v2). */
  def candidateProv(recordId: String): Provenance = Provenance(
    sourceDataset = SourceType.Derived,
    sourceRecordId = recordId,
    snapshot = "dblp-2026-10-03",
    runId = runId,
    sourceSchemaVersion = "scholarchain.work-record.v2",
    policyVersion = None,
    modelVersion = None,
    promptVersion = None
  )

  val openAlexProfileId: String = ok(SourceAuthorId.openAlex("https://openalex.org/A1"))
  val orcidProfileId: String = ok(SourceAuthorId.orcid(orcidA))

  val block: CandidateBlock = ok(
    CandidateBlock.build(
      MatchEntityKind.Researcher,
      blockingVersion,
      0,
      "example|a",
      5,
      Vector(openAlexProfileId, mention1.mentionId, mention0.mentionId),
      Vector(SourceType.OpenAlex, SourceType.Dblp),
      candidateProv("example|a")
    )
  )

  val matchCandidate: MatchCandidate = ok(
    MatchCandidate.build(
      MatchEntityKind.Researcher,
      openAlexProfileId,
      SourceType.OpenAlex,
      mention0.mentionId,
      SourceType.Dblp,
      Vector(BlockKey(1, "example|ada"), BlockKey(0, "example|a")),
      blockingVersion,
      featureVersion,
      SortedMap("orcidEqual" -> 1.0, "nameSimilarity" -> 0.92),
      Vector(ExactId(ExactIdScheme.Orcid, orcidA)),
      candidateProv(mention0.mentionId)
    )
  )

  // ---------------------------------------------------------------- source identity

  val mentionLink: IdentityLink = ok(
    IdentityLink.build(
      ExactId(ExactIdScheme.Orcid, orcidA),
      MatchEntityKind.Researcher,
      mention0.mentionId,
      Some(workId),
      None,
      dblpProv
    )
  )

  /** The ORCID holder-to-work claim as an identity link, with its assertion source. */
  val claimLink: IdentityLink = ok(
    IdentityLink.build(
      ExactId(ExactIdScheme.Orcid, orcidA),
      MatchEntityKind.Researcher,
      orcidProfileId,
      Some(orcidWorkId),
      Some(assertion),
      orcidProv(s"$orcidA/work/1001")
    )
  )

  val openAlexProfile: SourceAuthorProfile = SourceAuthorProfile(
    schemaVersion = SchemaVersions.SourceAuthorProfile,
    profileId = openAlexProfileId,
    source = SourceType.OpenAlex,
    sourceRecordId = "A1",
    displayName = Some("Ada Example"),
    givenNames = None,
    familyName = None,
    nameVariants = Vector("A. Example", "Ada B. Example"),
    orcid = Some(orcidA),
    externalIds = Vector(ExternalId(ExternalIdScheme.OpenAlex, "A1")),
    institutionIds = Vector("institution:openalex:I1"),
    claimedWorkIds = Vector.empty,
    provenance = openAlexProv("A1")
  )

  val orcidProfile: SourceAuthorProfile = SourceAuthorProfile(
    schemaVersion = SchemaVersions.SourceAuthorProfile,
    profileId = orcidProfileId,
    source = SourceType.Orcid,
    sourceRecordId = orcidA,
    displayName = None,
    givenNames = Some("Ada"),
    familyName = Some("Example"),
    nameVariants = Vector("A. Example"),
    orcid = Some(orcidA),
    externalIds = Vector.empty,
    institutionIds = Vector.empty,
    claimedWorkIds = Vector(orcidWorkId),
    provenance = orcidProv(orcidA)
  )

  val coauthorEdge: CoauthorEdge = ok(
    CoauthorEdge.build(
      researcherB,
      researcherA,
      window,
      jointPublicationCount = 3,
      firstCollaborationYear = 2017,
      lastCollaborationYear = 2021,
      representativeWorkIds = Vector(workId, workId2),
      provenance = derivedProv(graphPolicy)
    )
  )

  val metrics: ResearcherMetrics = ResearcherMetrics(
    schemaVersion = SchemaVersions.ResearcherMetrics,
    researcherId = researcherA,
    window = window,
    publicationCount = 10,
    distinctCoauthorCount = 9,
    totalCoauthorOccurrences = 25,
    averageCoauthorsPerPaper = 2.5,
    maximumCoauthorsOnOnePaper = 6,
    maximumTeamWorkIds = Vector(workId),
    weightedDegree = 25.0,
    componentId = "cc-0001",
    componentSize = 1523,
    communityId = Some("community-17"),
    bridgeScore = Some(0.125),
    bridgeMeasure = Some("betweenness-sampled"),
    coreness = Some(4),
    provenance = derivedProv(graphPolicy)
  )

  val graphNode: GraphNode = GraphNode(
    schemaVersion = SchemaVersions.GraphNode,
    nodeId = workId,
    label = NodeLabel.Work,
    properties = SortedMap("year" -> "2021"),
    provenance = derivedProv(graphPolicy)
  )

  val graphEdge: GraphEdge = GraphEdge(
    schemaVersion = SchemaVersions.GraphEdge,
    edgeId = ok(
      GraphEdgeId
        .of(EdgeLabel.CoauthoredWith, researcherA, researcherB, graphPolicy, coauthorEdge.edgeId)
    ),
    fromId = researcherA,
    edgeType = EdgeLabel.CoauthoredWith,
    toId = researcherB,
    sourceEvidence = coauthorEdge.edgeId,
    properties = SortedMap(
      "jointPublicationCount" -> "3",
      "firstCollaborationYear" -> "2017",
      "lastCollaborationYear" -> "2021"
    ),
    provenance = derivedProv(graphPolicy)
  )

  def evidence(
      t: EvidenceType,
      uri: String,
      w: Option[String],
      path: Option[Vector[String]],
      score: Double,
      text: Option[String]
  ): EvidenceItem =
    EvidenceItem(
      schemaVersion = SchemaVersions.EvidenceItem,
      evidenceId = ok(EvidenceId.of(t, uri, w, None, path.getOrElse(Vector.empty), runId)),
      evidenceType = t,
      workId = w,
      sourceUri = uri,
      chunkId = None,
      graphPath = path,
      score = score,
      text = text,
      provenance = runtimeProv
    )

  val evidenceWork: EvidenceItem = evidence(
    EvidenceType.SourceRecord,
    "https://dblp.org/rec/journals/example/Synthetic21",
    Some(workId),
    None,
    1.0,
    None
  )
  val evidenceEdge: EvidenceItem = evidence(
    EvidenceType.CoauthorEdge,
    s"urn:scholarchain:${coauthorEdge.edgeId}",
    None,
    Some(Vector(researcherA, researcherB)),
    1.0,
    None
  )

  val bundle: EvidenceBundle =
    ok(EvidenceBundle.build(queryId, Vector(evidenceWork, evidenceEdge), runtimeProv))

  val claimText = "Ada Example and Ben Example coauthored 3 works between 2015 and 2024."
  val claim: AnswerClaim = AnswerClaim(
    schemaVersion = SchemaVersions.AnswerClaim,
    claimId = ok(ClaimId.of(queryId, 0, claimText)),
    text = claimText,
    evidenceIds = Vector(evidenceEdge.evidenceId),
    verificationStatus = VerificationStatus.Supported,
    confidence = Some(0.9),
    uncertainty = Some("Ben Example is identified by one source-asserted ORCID iD."),
    provenance = runtimeProv
  )

  val queryPlan: QueryPlan = QueryPlan(
    schemaVersion = SchemaVersions.QueryPlan,
    queryId = queryId,
    question = "How often did Ada Example and Ben Example collaborate?",
    window = window,
    steps = Vector(
      QueryStep(
        0,
        QueryOperation.ResolveResearcher,
        Vector.empty,
        SortedMap("name" -> "Ada Example")
      ),
      QueryStep(
        1,
        QueryOperation.GetMetrics,
        Vector(CollaborationMetric.JointPublicationCount),
        SortedMap.empty
      )
    ),
    provenance = runtimeProv
  )

  val answerText = "They coauthored 3 works in 2015-2024."
  val answer: AnswerResponse = AnswerResponse(
    schemaVersion = SchemaVersions.AnswerResponse,
    responseId = ok(AnswerId.of(queryId, bundle.bundleId, answerText)),
    queryId = queryId,
    window = window,
    answerText = answerText,
    claims = Vector(claim),
    evidenceBundle = bundle,
    provenance = runtimeProv
  )
}
