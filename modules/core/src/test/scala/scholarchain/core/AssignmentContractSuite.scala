package scholarchain.core

import AssignmentFixtures.*
import Fixtures.{authorship, mention0, ok, openAlexProfileId, orcidA, researcherA, workId, workId2}

/**
 * Record-level contracts C1 (ResearcherAssignment), C2 (WorkAssignment), C3 (AuthorshipRecord v2).
 */
class AssignmentContractSuite extends munit.FunSuite {

  private val mention1Id: String = Fixtures.mention1.mentionId

  private def fieldOf(e: ValidationError): String = e match {
    case ValidationError.MissingField(f) => f
    case ValidationError.InvalidValue(f, _) => f
    case ValidationError.MalformedId(f, _, _, _) => f
    case ValidationError.ConfidenceOutOfRange(f, _) => f
    case ValidationError.MissingProvenance(_, f) => f
    case _ => ""
  }

  private def refusedAt[T](t: T, field: String)(using c: RecordContract[T]): Unit = {
    val errs = c.validate(t)
    assert(errs.exists(e => fieldOf(e) == field), s"expected a refusal at '$field', got $errs")
  }

  private def valid[T](t: T)(using c: RecordContract[T]): Unit =
    assertEquals(c.validate(t), Vector.empty)

  private def roundTrip[T](name: String, t: T)(using c: RecordContract[T]): Unit = {
    test(s"$name: validates, round-trips through JSON, and rejects unknown keys") {
      valid(t)
      val json = Json.write(t)
      assertEquals(Json.decode[T](json), Right(t))
      val extra = json.replaceFirst("\\{", "{\"unexpected\":1,")
      assert(
        Json.decode[T](extra).left.exists(_.exists(_.isInstanceOf[ValidationError.Undecodable])),
        "unknown top-level key"
      )
      val nested = json.replace("\"promptVersion\":null}", "\"promptVersion\":null,\"x\":0}")
      assert(nested != json)
      assert(Json.decode[T](nested).isLeft, "unknown key inside provenance")
    }
  }

  researchers.foreach(r => roundTrip(s"ResearcherAssignment ${r.status.tag} ${r.memberId}", r))
  works.foreach(w => roundTrip(s"WorkAssignment ${w.graphEligibility.tag} ${w.workId}", w))
  authorships.foreach(a => roundTrip(s"AuthorshipRecordV2 ${a.assignmentStatus.tag}", a))

  test("new enum tags are frozen and unknown tags are rejected on decode") {
    assertEquals(
      AssignmentStatus.values.map(_.tag).toVector,
      Vector("resolved", "singleton", "unresolved")
    )
    assertEquals(WorkGroupStatus.values.map(_.tag).toVector, Vector("grouped", "single"))
    assertEquals(
      GraphEligibility.values.map(_.tag).toVector,
      Vector("counted", "member-of-counted", "enrichment-only")
    )
    assertEquals(
      DecisionRole.values.map(_.field).toVector,
      Vector(
        "supportingDecisionIds",
        "vetoedDecisionIds",
        "pendingDecisionIds",
        "conflictDecisionIds",
        "rejectedDecisionIds"
      )
    )
    val bad = Json.write(resolvedMention).replace("\"resolved\"", "\"merged\"")
    assert(
      Json
        .decode[ResearcherAssignment](bad)
        .left
        .exists(_.exists(_.isInstanceOf[ValidationError.Undecodable]))
    )
    val badScope = Json.write(countedSingle).replace("\"counted\"", "\"graph\"")
    assert(Json.decode[WorkAssignment](badScope).isLeft)
  }

  // ------------------------------------------------------------------ C1 ResearcherAssignment

  test("unresolved without a researcher ID is accepted; with one (or an anchor) it is refused") {
    valid(unresolvedMention)
    refusedAt(
      unresolvedMention.copy(researcherId = Some(researcherA)),
      "researcherId,anchorNamespace,anchorValue"
    )
    refusedAt(
      unresolvedMention.copy(anchorNamespace = Some("orcid")),
      "researcherId,anchorNamespace,anchorValue"
    )
  }

  test("an unresolved member keeps at least one pending, vetoed or conflict decision") {
    val bare = unresolvedMention.copy(pendingDecisionIds = Vector.empty)
    refusedAt(bare, "pendingDecisionIds,vetoedDecisionIds,conflictDecisionIds")
    valid(bare.copy(vetoedDecisionIds = Vector(dR2.decisionId)))
    valid(bare.copy(conflictDecisionIds = Vector(dR2.decisionId)))
    refusedAt(
      unresolvedMention.copy(rejectedDecisionIds = Vector.empty, pendingDecisionIds = Vector.empty),
      "pendingDecisionIds,vetoedDecisionIds,conflictDecisionIds"
    )
  }

  test("a singleton has a researcher ID without any pairwise match (no decision at all)") {
    val noDecision = singletonMention.copy(pendingDecisionIds = Vector.empty)
    valid(noDecision)
    refusedAt(
      singletonMention.copy(supportingDecisionIds = Vector(dR1.decisionId)),
      "supportingDecisionIds"
    )
    refusedAt(singletonMention.copy(groupSize = 2), "groupSize")
  }

  test(
    "F2: a singleton with a pending, vetoed or conflict decision is anchored on orcid or dblp-pid"
  ) {
    val byMember = singletonMention.copy(
      researcherId = Some(ok(ResearcherId.scholarChain("v1", "member", mention1Id))),
      anchorNamespace = Some("member"),
      anchorValue = Some(mention1Id)
    )
    val d = dR2.decisionId
    refusedAt(byMember, "anchorNamespace")
    refusedAt(
      byMember.copy(pendingDecisionIds = Vector.empty, vetoedDecisionIds = Vector(d)),
      "anchorNamespace"
    )
    refusedAt(
      byMember.copy(pendingDecisionIds = Vector.empty, conflictDecisionIds = Vector(d)),
      "anchorNamespace"
    )
    // No blocking decision, or REJECTED decisions only: any anchor namespace.
    valid(byMember.copy(pendingDecisionIds = Vector.empty))
    valid(byMember.copy(pendingDecisionIds = Vector.empty, rejectedDecisionIds = Vector(d)))
    // The two t1 namespaces are accepted with a pending decision.
    valid(singletonMention)
    valid(
      singletonMention.copy(
        researcherId = Some(Fixtures.researcherB),
        anchorNamespace = Some("orcid"),
        anchorValue = Some(Fixtures.orcidB)
      )
    )
    assertEquals(ResearcherAssignment.T1AnchorNamespaces, Set("orcid", "dblp-pid"))
  }

  test("an identified member requires researcherId and both anchor fields") {
    refusedAt(singletonMention.copy(researcherId = None), "researcherId")
    refusedAt(singletonMention.copy(anchorNamespace = None), "anchorNamespace")
    refusedAt(singletonMention.copy(anchorValue = None), "anchorValue")
  }

  test("resolved requires a group of two or more and supporting decisions") {
    refusedAt(resolvedMention.copy(groupSize = 1), "groupSize")
    refusedAt(resolvedMention.copy(supportingDecisionIds = Vector.empty), "supportingDecisionIds")
    refusedAt(resolvedMention.copy(groupSize = 0), "groupSize")
  }

  test("the ORCID-form researcher ID equals orcid(anchorValue) in namespace orcid") {
    refusedAt(resolvedMention.copy(anchorValue = Some("0000-0000-0000-001X")), "researcherId")
    refusedAt(resolvedMention.copy(anchorNamespace = Some("dblp-pid")), "anchorNamespace")
    refusedAt(
      resolvedMention.copy(anchorValue = Some("https://orcid.org/" + orcidA)),
      "anchorValue"
    )
  }

  test("the sc-form researcher ID equals scholarChain(identityVersion, namespace, value)") {
    valid(singletonMention)
    refusedAt(singletonMention.copy(anchorValue = Some("00/0003")), "researcherId")
    refusedAt(singletonMention.copy(identityVersion = "v2"), "researcherId")
    refusedAt(singletonMention.copy(anchorNamespace = Some("orcid")), "researcherId")
    refusedAt(singletonMention.copy(identityVersion = "1"), "identityVersion")
    refusedAt(singletonMention.copy(anchorNamespace = Some("not a token")), "anchorNamespace")
  }

  test(
    "mention members carry workId and position that reproduce the mention ID; profiles carry neither"
  ) {
    refusedAt(resolvedMention.copy(position = Some(1)), "memberId")
    refusedAt(resolvedMention.copy(workId = Some(workId2)), "memberId")
    refusedAt(resolvedMention.copy(workId = None), "workId")
    refusedAt(resolvedMention.copy(position = None), "position")
    refusedAt(resolvedProfile.copy(workId = Some(workId)), "workId")
    refusedAt(resolvedProfile.copy(position = Some(0)), "position")
    refusedAt(resolvedMention.copy(memberId = workId), "memberId")
  }

  test("decision lists hold match IDs, sorted by bytes, distinct, and disjoint from each other") {
    refusedAt(
      resolvedMention.copy(rejectedDecisionIds = Vector("run:" + "0" * 64)),
      "rejectedDecisionIds[0]"
    )
    val two =
      Vector(dR1.decisionId, dR3.decisionId).sortWith((a, b) => Canonical.compareUtf8(a, b) < 0)
    refusedAt(resolvedMention.copy(rejectedDecisionIds = two.reverse), "rejectedDecisionIds")
    refusedAt(
      resolvedMention.copy(rejectedDecisionIds = Vector(dR3.decisionId, dR3.decisionId)),
      "rejectedDecisionIds"
    )
    refusedAt(
      resolvedMention.copy(rejectedDecisionIds = Vector(dR1.decisionId)),
      "supportingDecisionIds,rejectedDecisionIds"
    )
    refusedAt(resolvedMention.copy(vetoedDecisionIds = null), "vetoedDecisionIds")
  }

  test("reason codes: non-empty, tokens, sorted without duplicates") {
    refusedAt(resolvedMention.copy(reasonCodes = Vector.empty), "reasonCodes")
    refusedAt(resolvedMention.copy(reasonCodes = Vector("has space")), "reasonCodes[0]")
    refusedAt(resolvedMention.copy(reasonCodes = Vector("b", "a")), "reasonCodes")
  }

  test("grouping and match policy versions are separate tokens; the decision run is a run ID") {
    refusedAt(resolvedMention.copy(groupingPolicyVersion = matchPolicy), "groupingPolicyVersion")
    refusedAt(resolvedMention.copy(matchPolicyVersion = " "), "matchPolicyVersion")
    refusedAt(resolvedMention.copy(decisionRunId = "run:x"), "decisionRunId")
    refusedAt(resolvedMention.copy(assignedAt = "2026-11-15T12:00:00+01:00"), "assignedAt")
    refusedAt(resolvedMention.copy(assignedAt = "yesterday"), "assignedAt")
  }

  test(
    "provenance: required, derived, subject = member, policy = grouping, no model, not the decision run"
  ) {
    assert(
      ResearcherAssignment.contract
        .validate(resolvedMention.copy(provenance = null))
        .exists(_.isInstanceOf[ValidationError.MissingProvenance])
    )
    val p = resolvedMention.provenance
    refusedAt(resolvedMention.copy(provenance = Fixtures.dblpProv), "provenance.sourceDataset")
    refusedAt(
      resolvedMention.copy(provenance = p.copy(sourceRecordId = workId)),
      "provenance.sourceRecordId"
    )
    refusedAt(
      resolvedMention.copy(provenance = p.copy(policyVersion = Some(matchPolicy))),
      "provenance.policyVersion"
    )
    refusedAt(
      resolvedMention.copy(provenance = p.copy(modelVersion = Some("m"))),
      "provenance.modelVersion,promptVersion"
    )
    refusedAt(resolvedMention.copy(provenance = p.copy(runId = decisionRunId)), "provenance.runId")
  }

  test("decode refuses missing provenance and unsupported schema versions") {
    val json = Json.write(resolvedMention)
    val noProv = json.substring(0, json.indexOf(",\"provenance\":")) + "}"
    assertEquals(
      Json.decode[ResearcherAssignment](noProv),
      Left(Vector(ValidationError.MissingProvenance("ResearcherAssignment", "provenance")))
    )
    val v2 =
      json.replace(SchemaVersions.ResearcherAssignment, "scholarchain.researcher-assignment.v2")
    assert(
      Json
        .decode[ResearcherAssignment](v2)
        .left
        .exists(_.exists(_.isInstanceOf[ValidationError.UnsupportedSchemaVersion]))
    )
  }

  // ------------------------------------------------------------------ C2 WorkAssignment

  test("the representative maps to itself, and only the representative does") {
    refusedAt(countedCopy.copy(isRepresentative = true), "isRepresentative")
    refusedAt(countedRepresentative.copy(isRepresentative = false), "isRepresentative")
  }

  test(
    "single: groupSize 1, its own canonical work, no supporting decision; grouped: >= 2 with support"
  ) {
    refusedAt(countedSingle.copy(groupSize = 2), "groupSize")
    refusedAt(
      countedSingle.copy(supportingDecisionIds = Vector(dW1.decisionId)),
      "supportingDecisionIds"
    )
    refusedAt(
      countedCopy
        .copy(status = WorkGroupStatus.Single, groupSize = 1, supportingDecisionIds = Vector.empty),
      "canonicalWorkId"
    )
    refusedAt(countedRepresentative.copy(groupSize = 1), "groupSize")
    refusedAt(
      countedRepresentative.copy(supportingDecisionIds = Vector.empty),
      "supportingDecisionIds"
    )
  }

  test("graph eligibility: counted only on a DBLP representative") {
    refusedAt(countedCopy.copy(graphEligibility = GraphEligibility.Counted), "graphEligibility")
    refusedAt(
      enrichmentSingle.copy(graphEligibility = GraphEligibility.Counted),
      "graphEligibility"
    )
  }

  test(
    "graph eligibility: member-of-counted only on a non-representative of a DBLP canonical work"
  ) {
    refusedAt(
      countedSingle.copy(graphEligibility = GraphEligibility.MemberOfCounted),
      "graphEligibility"
    )
    val nonDblpCanonical = countedCopy.copy(
      workId = workId,
      canonicalWorkId = workW1,
      provenance = countedCopy.provenance.copy(sourceRecordId = workId)
    )
    refusedAt(nonDblpCanonical, "graphEligibility")
  }

  test(
    "graph eligibility: enrichment-only never on a DBLP work or a copy of a DBLP canonical work"
  ) {
    refusedAt(
      countedSingle.copy(graphEligibility = GraphEligibility.EnrichmentOnly),
      "graphEligibility"
    )
    refusedAt(
      countedCopy.copy(graphEligibility = GraphEligibility.EnrichmentOnly),
      "graphEligibility"
    )
    valid(enrichmentSingle)
  }

  test("work assignment IDs, rule, lists and provenance are validated") {
    refusedAt(countedSingle.copy(workId = "work:x:1"), "workId")
    refusedAt(countedCopy.copy(canonicalWorkId = "nope"), "canonicalWorkId")
    refusedAt(countedSingle.copy(representativeRule = ""), "representativeRule")
    refusedAt(
      countedCopy.copy(pendingDecisionIds = Vector(dW1.decisionId)),
      "supportingDecisionIds,pendingDecisionIds"
    )
    refusedAt(
      countedSingle.copy(provenance = countedSingle.provenance.copy(sourceRecordId = workW2)),
      "provenance.sourceRecordId"
    )
  }

  // ------------------------------------------------------------------ C3 AuthorshipRecord v2

  test("v2 accepts a singleton without any decision (v1 refuses one)") {
    valid(singletonAuthorship)
    assertEquals(singletonAuthorship.supportingDecisionIds, Vector.empty)
    val v1Errors = AuthorshipRecord.contract.validate(authorship.copy(matchDecisionId = ""))
    assert(v1Errors.exists(e => fieldOf(e) == "matchDecisionId"), "v1 requires a match decision ID")
  }

  test(
    "v2 status rules: resolved needs support and confidence; singleton neither; unresolved refused"
  ) {
    refusedAt(
      resolvedAuthorship.copy(supportingDecisionIds = Vector.empty),
      "supportingDecisionIds"
    )
    refusedAt(resolvedAuthorship.copy(matchConfidence = None), "matchConfidence")
    refusedAt(singletonAuthorship.copy(matchConfidence = Some(0.5)), "matchConfidence")
    refusedAt(
      singletonAuthorship.copy(supportingDecisionIds = Vector(dR1.decisionId)),
      "supportingDecisionIds"
    )
    refusedAt(
      singletonAuthorship.copy(assignmentStatus = AssignmentStatus.Unresolved),
      "assignmentStatus"
    )
    refusedAt(resolvedAuthorship.copy(matchConfidence = Some(1.5)), "matchConfidence")
  }

  test("v2 identity and provenance rules") {
    refusedAt(resolvedAuthorship.copy(position = 1), "mentionId")
    refusedAt(resolvedAuthorship.copy(researcherId = "researcher:x"), "researcherId")
    refusedAt(resolvedAuthorship.copy(groupingPolicyVersion = matchPolicy), "groupingPolicyVersion")
    refusedAt(resolvedAuthorship.copy(decisionRunId = groupingRunId), "provenance.runId")
    refusedAt(
      resolvedAuthorship.copy(provenance =
        resolvedAuthorship.provenance.copy(sourceRecordId = workId)
      ),
      "provenance.sourceRecordId"
    )
  }

  test("authorship-record.v1 still decodes unchanged; the reader dispatches v1 and v2") {
    val v1 = Json.write(authorship)
    assertEquals(Json.decode[AuthorshipRecord](v1), Right(authorship))
    assertEquals(AuthorshipRecords.decode(v1), Right(Left(authorship)))
    assertEquals(
      AuthorshipRecords.decode(Json.write(singletonAuthorship)),
      Right(Right(singletonAuthorship))
    )
    assertEquals(
      AuthorshipRecords.readable,
      Vector(SchemaVersions.AuthorshipRecord, SchemaVersions.AuthorshipRecordV2)
    )
    // Each contract still accepts exactly its own version.
    assert(
      Json
        .decode[AuthorshipRecordV2](v1)
        .left
        .exists(_.exists(_.isInstanceOf[ValidationError.UnsupportedSchemaVersion]))
    )
    assert(Json.decode[AuthorshipRecord](Json.write(singletonAuthorship)).isLeft)
  }

  test("the authorship reader refuses unknown versions, missing versions and malformed JSON") {
    val v3 = Json
      .write(singletonAuthorship)
      .replace(SchemaVersions.AuthorshipRecordV2, "scholarchain.authorship-record.v3")
    assertEquals(
      AuthorshipRecords.decode(v3),
      Left(
        Vector(
          ValidationError.UnsupportedSchemaVersion(
            "AuthorshipRecord",
            "scholarchain.authorship-record.v3",
            AuthorshipRecords.readable
          )
        )
      )
    )
    assertEquals(
      AuthorshipRecords.decode("{}"),
      Left(Vector(ValidationError.MissingSchemaVersion("AuthorshipRecord")))
    )
    assert(
      AuthorshipRecords
        .decode("[")
        .left
        .exists(_.exists(_.isInstanceOf[ValidationError.MalformedJson]))
    )
    assert(
      AuthorshipRecords
        .decode("[]")
        .left
        .exists(_.exists(_.isInstanceOf[ValidationError.MalformedJson]))
    )
    // A v2 record with a v1-only key is refused (strict keys).
    val withV1Key =
      Json.write(resolvedAuthorship).replaceFirst("\\{", "{\"matchDecisionId\":\"x\",")
    assert(AuthorshipRecords.decode(withV1Key).isLeft)
  }

  test("fixtures use the existing constructors and formulas unchanged") {
    assertEquals(resolvedMention.memberId, mention0.mentionId)
    assertEquals(resolvedProfile.memberId, openAlexProfileId)
    assertEquals(ResearcherId.orcid(orcidA), Right(researcherA))
    assertEquals(MentionId.of(workId2, 0), Right(mentionU))
    assertEquals(ResearcherId.scholarChain("v1", "dblp-pid", "00/0002"), Right(singletonId))
    assertNotEquals(groupingRunId, decisionRunId)
    assertEquals(ok(RunId.of(groupingManifest)), groupingRunId)
  }
}
