package scholarchain.core

import scala.collection.immutable.SortedMap

import Fixtures.*

/**
 * One valid, synthetic grouping run (DE-P6-01). Every identifier is synthetic (see [[Fixtures]]);
 * no real person, work or dataset fact is asserted, and nothing is derived from any gold set.
 *
 * Members: mention0 (DBLP work `workId`, ORCID A) and the OpenAlex profile A1 are one resolved
 * researcher (ACCEPTED dR1); mention1 is a singleton anchored on its DBLP pid with a pending
 * decision dR2; mentionU (DBLP work `workId2`, position 0) is unresolved (dR2 pending, dR3
 * rejected). Works: `workId` and `work:openalex:W1` are one canonical work (ACCEPTED dW1) with the
 * DBLP representative counted; `workId2` is a counted single; `work:openalex:W2` is
 * enrichment-only.
 */
object AssignmentFixtures {

  val groupingPolicy = "er-grouping.v1"
  val matchPolicy: String = erPolicy
  val identityVersion = "v1"
  val decisionRunId: String = runId
  val assignedAt = "2026-11-15T12:00:00Z"

  val groupingManifest: RunManifest = RunManifest(
    schemaVersion = SchemaVersions.RunManifest,
    stage = "er.grouping",
    codeVersion = "0123456789abcdef0123456789abcdef01234567",
    configDigest = "c" * 64,
    inputSnapshots = Vector(SnapshotRef(SourceType.Dblp, "dblp-2026-10-03")),
    parameters = SortedMap("decisionRunId" -> decisionRunId),
    attempt = 1
  )
  val groupingRunId: String = ok(RunId.of(groupingManifest))

  /** Literal input schemas, so later bumps do not change the frozen examples. */
  val WorkSchema = "scholarchain.work-record.v3"
  val ProfileSchema = "scholarchain.source-author-profile.v1"
  val AssignmentSchema = "scholarchain.researcher-assignment.v1"

  def groupingProv(subject: String, inputSchema: String): Provenance = Provenance(
    sourceDataset = SourceType.Derived,
    sourceRecordId = subject,
    snapshot = "dblp-2026-10-03",
    runId = groupingRunId,
    sourceSchemaVersion = inputSchema,
    policyVersion = Some(groupingPolicy),
    modelVersion = None,
    promptVersion = None
  )

  val workW1 = "work:openalex:W1"
  val workW2 = "work:openalex:W2"
  val mentionU: String = ok(MentionId.of(workId2, 0))
  val singletonId: String = ok(ResearcherId.scholarChain(identityVersion, "dblp-pid", "00/0002"))

  private def decision(
      candidate: String,
      kind: MatchEntityKind,
      a: String,
      b: String,
      path: MatchDecisionPath,
      outcome: MatchOutcome,
      confidence: Double,
      score: Option[Double],
      rules: Vector[String],
      reasons: Vector[String]
  ): MatchDecision = ok(
    MatchDecision.build(
      candidateId = candidate,
      entityKind = kind,
      sourceId = a,
      targetId = b,
      matchPath = path,
      decision = outcome,
      confidence = confidence,
      score = score,
      blockingVersion = blockingVersion,
      featureVersion = featureVersion,
      features = SortedMap.empty,
      rulesFired = rules,
      reasonCodes = reasons,
      conflicts = Vector.empty,
      adjudication = None,
      decidedAt = decidedAt,
      provenance = decisionProv(candidate)
    )
  )

  /** ACCEPTED: mention0 and the OpenAlex profile (the Phase 5 fixture decision). */
  val dR1: MatchDecision = matchDecision

  /** UNRESOLVED: mention1 and mentionU. */
  val dR2: MatchDecision = decision(
    "mcand:" + "1" * 64,
    MatchEntityKind.Researcher,
    mention1.mentionId,
    mentionU,
    MatchDecisionPath.Probabilistic,
    MatchOutcome.Unresolved,
    0.5,
    Some(0.55),
    Vector("probabilistic.unresolved"),
    Vector("score-between-thresholds")
  )

  /** REJECTED: mention0 and mentionU. */
  val dR3: MatchDecision = decision(
    "mcand:" + "3" * 64,
    MatchEntityKind.Researcher,
    mention0.mentionId,
    mentionU,
    MatchDecisionPath.Probabilistic,
    MatchOutcome.Rejected,
    0.9,
    Some(0.1),
    Vector("probabilistic.reject"),
    Vector("score-at-or-below-reject")
  )

  /** ACCEPTED: the DBLP work and the OpenAlex work W1 share a DOI. */
  val dW1: MatchDecision = decision(
    "mcand:" + "2" * 64,
    MatchEntityKind.Work,
    workId,
    workW1,
    MatchDecisionPath.ExactId,
    MatchOutcome.Accepted,
    0.99,
    None,
    Vector("exact-id.agree.doi"),
    Vector("exact-id-agreement.doi")
  )

  val decisions: Vector[MatchDecision] = Vector(dR1, dR2, dR3, dW1)

  def researcher(
      memberId: String,
      work: Option[String],
      position: Option[Int],
      status: AssignmentStatus,
      researcherId: Option[String],
      anchor: Option[(String, String)],
      groupSize: Int,
      supporting: Vector[String] = Vector.empty,
      vetoed: Vector[String] = Vector.empty,
      pending: Vector[String] = Vector.empty,
      conflict: Vector[String] = Vector.empty,
      rejected: Vector[String] = Vector.empty,
      reasons: Vector[String]
  ): ResearcherAssignment = ResearcherAssignment(
    schemaVersion = SchemaVersions.ResearcherAssignment,
    memberId = memberId,
    workId = work,
    position = position,
    status = status,
    researcherId = researcherId,
    identityVersion = identityVersion,
    anchorNamespace = anchor.map(_._1),
    anchorValue = anchor.map(_._2),
    groupSize = groupSize,
    supportingDecisionIds = supporting,
    vetoedDecisionIds = vetoed,
    pendingDecisionIds = pending,
    conflictDecisionIds = conflict,
    rejectedDecisionIds = rejected,
    reasonCodes = reasons,
    groupingPolicyVersion = groupingPolicy,
    matchPolicyVersion = matchPolicy,
    decisionRunId = decisionRunId,
    assignedAt = assignedAt,
    provenance = groupingProv(memberId, if (work.isDefined) WorkSchema else ProfileSchema)
  )

  val resolvedMention: ResearcherAssignment = researcher(
    mention0.mentionId,
    Some(workId),
    Some(0),
    AssignmentStatus.Resolved,
    Some(researcherA),
    Some("orcid" -> orcidA),
    2,
    supporting = Vector(dR1.decisionId),
    rejected = Vector(dR3.decisionId),
    reasons = Vector("grouping.anchor.orcid", "grouping.resolved")
  )

  val resolvedProfile: ResearcherAssignment = researcher(
    openAlexProfileId,
    None,
    None,
    AssignmentStatus.Resolved,
    Some(researcherA),
    Some("orcid" -> orcidA),
    2,
    supporting = Vector(dR1.decisionId),
    reasons = Vector("grouping.anchor.orcid", "grouping.resolved")
  )

  /** A singleton with a researcher ID and no pairwise match (its only decision is pending). */
  val singletonMention: ResearcherAssignment = researcher(
    mention1.mentionId,
    Some(workId),
    Some(1),
    AssignmentStatus.Singleton,
    Some(singletonId),
    Some("dblp-pid" -> "00/0002"),
    1,
    pending = Vector(dR2.decisionId),
    reasons = Vector("grouping.anchor.dblp-pid", "grouping.singleton.t1-anchored")
  )

  /** An unresolved mention: no researcher ID, its pending and rejected decisions preserved. */
  val unresolvedMention: ResearcherAssignment = researcher(
    mentionU,
    Some(workId2),
    Some(0),
    AssignmentStatus.Unresolved,
    None,
    None,
    1,
    pending = Vector(dR2.decisionId),
    rejected = Vector(dR3.decisionId),
    reasons = Vector("grouping.unresolved.pending-decision")
  )

  val researchers: Vector[ResearcherAssignment] =
    Vector(resolvedMention, resolvedProfile, singletonMention, unresolvedMention)

  def work(
      id: String,
      canonical: String,
      status: WorkGroupStatus,
      groupSize: Int,
      eligibility: GraphEligibility,
      supporting: Vector[String] = Vector.empty,
      reasons: Vector[String]
  ): WorkAssignment = WorkAssignment(
    schemaVersion = SchemaVersions.WorkAssignment,
    workId = id,
    canonicalWorkId = canonical,
    isRepresentative = id == canonical,
    status = status,
    representativeRule = "representative.dblp-first",
    groupSize = groupSize,
    graphEligibility = eligibility,
    supportingDecisionIds = supporting,
    vetoedDecisionIds = Vector.empty,
    pendingDecisionIds = Vector.empty,
    conflictDecisionIds = Vector.empty,
    rejectedDecisionIds = Vector.empty,
    reasonCodes = reasons,
    groupingPolicyVersion = groupingPolicy,
    matchPolicyVersion = matchPolicy,
    decisionRunId = decisionRunId,
    assignedAt = assignedAt,
    provenance = groupingProv(id, WorkSchema)
  )

  val countedRepresentative: WorkAssignment = work(
    workId,
    workId,
    WorkGroupStatus.Grouped,
    2,
    GraphEligibility.Counted,
    Vector(dW1.decisionId),
    Vector("grouping.representative")
  )
  val countedCopy: WorkAssignment = work(
    workW1,
    workId,
    WorkGroupStatus.Grouped,
    2,
    GraphEligibility.MemberOfCounted,
    Vector(dW1.decisionId),
    Vector("grouping.copy")
  )
  val countedSingle: WorkAssignment =
    work(
      workId2,
      workId2,
      WorkGroupStatus.Single,
      1,
      GraphEligibility.Counted,
      reasons = Vector("grouping.single")
    )
  val enrichmentSingle: WorkAssignment = work(
    workW2,
    workW2,
    WorkGroupStatus.Single,
    1,
    GraphEligibility.EnrichmentOnly,
    reasons = Vector("grouping.no-dblp-member", "grouping.single")
  )

  val works: Vector[WorkAssignment] =
    Vector(countedRepresentative, countedCopy, countedSingle, enrichmentSingle)

  def authorship(
      r: ResearcherAssignment,
      year: Option[Int],
      confidence: Option[Double]
  ): AuthorshipRecordV2 = AuthorshipRecordV2(
    schemaVersion = SchemaVersions.AuthorshipRecordV2,
    workId = r.workId.get,
    mentionId = r.memberId,
    researcherId = r.researcherId.get,
    position = r.position.get,
    year = year,
    assignmentStatus = r.status,
    supportingDecisionIds = r.supportingDecisionIds,
    matchConfidence = confidence,
    groupingPolicyVersion = groupingPolicy,
    matchPolicyVersion = matchPolicy,
    decisionRunId = decisionRunId,
    provenance = groupingProv(r.memberId, AssignmentSchema)
  )

  val resolvedAuthorship: AuthorshipRecordV2 = authorship(resolvedMention, Some(2021), Some(0.97))
  val singletonAuthorship: AuthorshipRecordV2 = authorship(singletonMention, Some(2021), None)
  val authorships: Vector[AuthorshipRecordV2] = Vector(resolvedAuthorship, singletonAuthorship)

  def team(
      w: String,
      ids: Vector[String],
      size: Int,
      unresolved: Vector[String]
  ): PublicationTeam = PublicationTeam(
    schemaVersion = SchemaVersions.PublicationTeam,
    workId = w,
    year = Some(2021),
    researcherIds = ids,
    teamSize = size,
    unresolvedMentionIds = unresolved,
    provenance = groupingProv(w, "scholarchain.work-assignment.v1")
  )

  val teams: Vector[PublicationTeam] = Vector(
    team(workId, Vector(researcherA, singletonId), 2, Vector.empty),
    team(workId2, Vector.empty, 1, Vector(mentionU))
  )

  // ---------------------------------------------------------------- part manifest (C5)

  val runManifestLine: String = Json.write(groupingManifest) + "\n"
  val researcherLines: String = researchers.map(Json.write(_)).mkString("", "\n", "\n")
  val workLines: String = works.map(Json.write(_)).mkString("", "\n", "\n")

  private def utf8(s: String): Array[Byte] = s.getBytes(java.nio.charset.StandardCharsets.UTF_8)

  /** The published files of the synthetic grouping run, by relative path. */
  val runFiles: Vector[(String, String, Array[Byte])] = Vector(
    (PartManifest.RunManifestFile, SchemaVersions.RunManifest, utf8(runManifestLine)),
    ("researchers/part-00000.jsonl", SchemaVersions.ResearcherAssignment, utf8(researcherLines)),
    ("works/part-00000.jsonl", SchemaVersions.WorkAssignment, utf8(workLines))
  )

  def partFile(path: String, schema: String, bytes: Array[Byte]): PartFile = {
    val o = ObservedFile.of(path, bytes)
    PartFile(path, schema, o.rowCount, o.byteCount, o.sha256)
  }

  val partManifest: PartManifest =
    ok(PartManifest.build(groupingRunId, runFiles.map { case (p, s, b) => partFile(p, s, b) }))

  /** The observation of a complete, correct copy: every file plus both marker files. */
  val observedRun: Vector[ObservedFile] =
    runFiles.map { case (p, _, b) => ObservedFile.of(p, b) } ++ Vector(
      ObservedFile.of(PartManifest.FileName, utf8(Json.write(partManifest) + "\n")),
      ObservedFile.of(PartManifest.SuccessMarker, Array.emptyByteArray)
    )
}
