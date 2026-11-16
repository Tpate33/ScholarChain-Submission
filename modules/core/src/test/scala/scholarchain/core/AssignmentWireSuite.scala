package scholarchain.core

import AssignmentFixtures.*

/**
 * Frozen JSON wire examples of the Phase 6 contracts (DE-P6-01): researcher-assignment.v1,
 * work-assignment.v1, authorship-record.v2 and part-manifest.v1. Each golden string is the exact
 * compact encoding of its fixture. Changing any golden string requires bumping that record's schema
 * version in SchemaVersions.
 */
class AssignmentWireSuite extends munit.FunSuite {

  // Pinned literal IDs (each checked against its formula below).
  private val Run = "run:8dc188c7670e600b1e1f8bf679a11809bd4ebadbd4a9ed27d27e318a59a88614"
  private val GRun = "run:b68cf8fc7adb94e1370432600b3788198d1e1d17b29e336d27a1cef8e7d3c33d"
  private val M0 = "mention:4d99b473c7f1d066094cb66ae71ded0bb0305eea0b75006f46ff2fd12c4c0bc4"
  private val M1 = "mention:191fd11b93e4fceca68d7e03e0d62de4a2fb826cfbdec968e4951677afd6fcbf"
  private val MU = "mention:191bdccd750f5fb2dacbfd3137030f10a141fac2763d35652f24df6cd3bdc162"
  private val SC =
    "researcher:sc:v1:6408a5d4a47f0c82908175bcf67d848349b35e47d3df2aef8acfbeee05209ff0"
  private val DR1 = "match:520ebb18b97f746f3a6d87e7982d510b1aecc0ff94bd57df3ac362918f3b3582"
  private val DR2 = "match:b0cc69cba29523b0206adfd97ba4b792de31769aca639a6d80f48c5d3a3b5c95"
  private val DR3 = "match:eda80af67f84aa52563783e88c8e23f20d927d2966e9648e0f826344de3250f6"

  /**
   * Same pair, policy and run as the frozen CONFLICT example, so the same unchanged decision ID.
   */
  private val DW1 = "match:63d9125dd805b7f7eddaaf7a90e4e5b1bbffb23bc1937dafaa4c0bde836d7d32"

  test("pinned literal IDs equal their formulas") {
    assertEquals(Run, decisionRunId)
    assertEquals(GRun, groupingRunId)
    assertEquals(M0, Fixtures.mention0.mentionId)
    assertEquals(M1, Fixtures.mention1.mentionId)
    assertEquals(MU, mentionU)
    assertEquals(SC, singletonId)
    assertEquals(DR1, dR1.decisionId)
    assertEquals(DR2, dR2.decisionId)
    assertEquals(DR3, dR3.decisionId)
    assertEquals(DW1, dW1.decisionId)
  }

  private def prov(subject: String, schema: String) =
    s"""{"sourceDataset":"scholarchain-derived","sourceRecordId":"$subject","snapshot":"dblp-2026-10-03","runId":"$GRun","sourceSchemaVersion":"$schema","policyVersion":"er-grouping.v1","modelVersion":null,"promptVersion":null}"""

  private val Tail =
    s""""groupingPolicyVersion":"er-grouping.v1","matchPolicyVersion":"er-policy-1.0.0","decisionRunId":"$Run""""

  private val W0 = "work:dblp:journals/example/Synthetic21"
  private val W2dblp = "work:dblp:conf/example/Synthetic22"

  val ResolvedMentionGolden: String =
    s"""{"schemaVersion":"scholarchain.researcher-assignment.v1","memberId":"$M0","workId":"$W0","position":0,"status":"resolved","researcherId":"researcher:orcid:0000-0000-0000-0001","identityVersion":"v1","anchorNamespace":"orcid","anchorValue":"0000-0000-0000-0001","groupSize":2,"supportingDecisionIds":["$DR1"],"vetoedDecisionIds":[],"pendingDecisionIds":[],"conflictDecisionIds":[],"rejectedDecisionIds":["$DR3"],"reasonCodes":["grouping.anchor.orcid","grouping.resolved"],$Tail,"assignedAt":"2026-11-15T12:00:00Z","provenance":${prov(
        M0,
        "scholarchain.work-record.v3"
      )}}"""

  val SingletonMentionGolden: String =
    s"""{"schemaVersion":"scholarchain.researcher-assignment.v1","memberId":"$M1","workId":"$W0","position":1,"status":"singleton","researcherId":"$SC","identityVersion":"v1","anchorNamespace":"dblp-pid","anchorValue":"00/0002","groupSize":1,"supportingDecisionIds":[],"vetoedDecisionIds":[],"pendingDecisionIds":["$DR2"],"conflictDecisionIds":[],"rejectedDecisionIds":[],"reasonCodes":["grouping.anchor.dblp-pid","grouping.singleton.t1-anchored"],$Tail,"assignedAt":"2026-11-15T12:00:00Z","provenance":${prov(
        M1,
        "scholarchain.work-record.v3"
      )}}"""

  val UnresolvedMentionGolden: String =
    s"""{"schemaVersion":"scholarchain.researcher-assignment.v1","memberId":"$MU","workId":"$W2dblp","position":0,"status":"unresolved","researcherId":null,"identityVersion":"v1","anchorNamespace":null,"anchorValue":null,"groupSize":1,"supportingDecisionIds":[],"vetoedDecisionIds":[],"pendingDecisionIds":["$DR2"],"conflictDecisionIds":[],"rejectedDecisionIds":["$DR3"],"reasonCodes":["grouping.unresolved.pending-decision"],$Tail,"assignedAt":"2026-11-15T12:00:00Z","provenance":${prov(
        MU,
        "scholarchain.work-record.v3"
      )}}"""

  val ResolvedProfileGolden: String =
    s"""{"schemaVersion":"scholarchain.researcher-assignment.v1","memberId":"source-author:openalex:A1","workId":null,"position":null,"status":"resolved","researcherId":"researcher:orcid:0000-0000-0000-0001","identityVersion":"v1","anchorNamespace":"orcid","anchorValue":"0000-0000-0000-0001","groupSize":2,"supportingDecisionIds":["$DR1"],"vetoedDecisionIds":[],"pendingDecisionIds":[],"conflictDecisionIds":[],"rejectedDecisionIds":[],"reasonCodes":["grouping.anchor.orcid","grouping.resolved"],$Tail,"assignedAt":"2026-11-15T12:00:00Z","provenance":${prov(
        "source-author:openalex:A1",
        "scholarchain.source-author-profile.v1"
      )}}"""

  val CountedRepresentativeGolden: String =
    s"""{"schemaVersion":"scholarchain.work-assignment.v1","workId":"$W0","canonicalWorkId":"$W0","isRepresentative":true,"status":"grouped","representativeRule":"representative.dblp-first","groupSize":2,"graphEligibility":"counted","supportingDecisionIds":["$DW1"],"vetoedDecisionIds":[],"pendingDecisionIds":[],"conflictDecisionIds":[],"rejectedDecisionIds":[],"reasonCodes":["grouping.representative"],$Tail,"assignedAt":"2026-11-15T12:00:00Z","provenance":${prov(
        W0,
        "scholarchain.work-record.v3"
      )}}"""

  val CountedCopyGolden: String =
    s"""{"schemaVersion":"scholarchain.work-assignment.v1","workId":"work:openalex:W1","canonicalWorkId":"$W0","isRepresentative":false,"status":"grouped","representativeRule":"representative.dblp-first","groupSize":2,"graphEligibility":"member-of-counted","supportingDecisionIds":["$DW1"],"vetoedDecisionIds":[],"pendingDecisionIds":[],"conflictDecisionIds":[],"rejectedDecisionIds":[],"reasonCodes":["grouping.copy"],$Tail,"assignedAt":"2026-11-15T12:00:00Z","provenance":${prov(
        "work:openalex:W1",
        "scholarchain.work-record.v3"
      )}}"""

  val EnrichmentSingleGolden: String =
    s"""{"schemaVersion":"scholarchain.work-assignment.v1","workId":"work:openalex:W2","canonicalWorkId":"work:openalex:W2","isRepresentative":true,"status":"single","representativeRule":"representative.dblp-first","groupSize":1,"graphEligibility":"enrichment-only","supportingDecisionIds":[],"vetoedDecisionIds":[],"pendingDecisionIds":[],"conflictDecisionIds":[],"rejectedDecisionIds":[],"reasonCodes":["grouping.no-dblp-member","grouping.single"],$Tail,"assignedAt":"2026-11-15T12:00:00Z","provenance":${prov(
        "work:openalex:W2",
        "scholarchain.work-record.v3"
      )}}"""

  val ResolvedAuthorshipGolden: String =
    s"""{"schemaVersion":"scholarchain.authorship-record.v2","workId":"$W0","mentionId":"$M0","researcherId":"researcher:orcid:0000-0000-0000-0001","position":0,"year":2021,"assignmentStatus":"resolved","supportingDecisionIds":["$DR1"],"matchConfidence":0.97,$Tail,"provenance":${prov(
        M0,
        "scholarchain.researcher-assignment.v1"
      )}}"""

  val SingletonAuthorshipGolden: String =
    s"""{"schemaVersion":"scholarchain.authorship-record.v2","workId":"$W0","mentionId":"$M1","researcherId":"$SC","position":1,"year":2021,"assignmentStatus":"singleton","supportingDecisionIds":[],"matchConfidence":null,$Tail,"provenance":${prov(
        M1,
        "scholarchain.researcher-assignment.v1"
      )}}"""

  /** A literal part manifest (counts and hashes are synthetic; the run ID is the grouping run). */
  val literalPartManifest: PartManifest = PartManifest(
    SchemaVersions.PartManifest,
    groupingRunId,
    Vector(
      PartFile(PartManifest.RunManifestFile, SchemaVersions.RunManifest, 1, 400, "a" * 64),
      PartFile(
        "researchers/part-00000.jsonl",
        SchemaVersions.ResearcherAssignment,
        4,
        4096,
        "b" * 64
      ),
      PartFile("works/part-00000.jsonl", SchemaVersions.WorkAssignment, 4, 3000, "c" * 64)
    )
  )

  val PartManifestGolden: String =
    s"""{"schemaVersion":"scholarchain.part-manifest.v1","runId":"$GRun","files":[{"path":"_run-manifest.json","recordSchemaVersion":"scholarchain.run-manifest.v1","rowCount":1,"byteCount":400,"sha256":"${"a" * 64}"},{"path":"researchers/part-00000.jsonl","recordSchemaVersion":"scholarchain.researcher-assignment.v1","rowCount":4,"byteCount":4096,"sha256":"${"b" * 64}"},{"path":"works/part-00000.jsonl","recordSchemaVersion":"scholarchain.work-assignment.v1","rowCount":4,"byteCount":3000,"sha256":"${"c" * 64}"}]}"""

  private def frozen[T](
      name: String,
      value: T,
      golden: String
  )(using c: RecordContract[T]): Unit = {
    test(s"$name: encoding equals the frozen wire example") {
      assertEquals(Json.write(value), golden)
      assertEquals(Json.encode(value), Right(golden))
    }
    test(s"$name: the frozen wire example decodes to the fixture") {
      assertEquals(Json.decode[T](golden), Right(value))
    }
  }

  frozen("ResearcherAssignment (resolved mention)", resolvedMention, ResolvedMentionGolden)
  frozen("ResearcherAssignment (resolved profile)", resolvedProfile, ResolvedProfileGolden)
  frozen("ResearcherAssignment (singleton)", singletonMention, SingletonMentionGolden)
  frozen("ResearcherAssignment (unresolved)", unresolvedMention, UnresolvedMentionGolden)
  frozen(
    "WorkAssignment (counted representative)",
    countedRepresentative,
    CountedRepresentativeGolden
  )
  frozen("WorkAssignment (member of counted)", countedCopy, CountedCopyGolden)
  frozen("WorkAssignment (enrichment-only)", enrichmentSingle, EnrichmentSingleGolden)
  frozen("AuthorshipRecordV2 (resolved)", resolvedAuthorship, ResolvedAuthorshipGolden)
  frozen("AuthorshipRecordV2 (singleton)", singletonAuthorship, SingletonAuthorshipGolden)
  frozen("PartManifest", literalPartManifest, PartManifestGolden)
}
