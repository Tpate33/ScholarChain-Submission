package scholarchain.core

import scala.collection.immutable.SortedMap

import AssignmentFixtures.*
import AssignmentValidation.*
import Fixtures.{mention0, mention1, ok, orcidA, researcherA, workId, workId2}

/** Dataset-level rules of one grouping run (C1-C3), each with a refusal case and the valid set. */
class AssignmentValidationSuite extends munit.FunSuite {

  private def refused(errs: Vector[ValidationError], fragment: String): Unit =
    assert(
      errs.exists(_.message.contains(fragment)),
      s"expected a refusal containing '$fragment', got ${errs.map(_.message)}"
    )

  test("the synthetic grouping run is valid under every dataset rule") {
    assertEquals(checkResearchers(researchers), Vector.empty)
    assertEquals(checkWorks(works), Vector.empty)
    assertEquals(checkRun(researchers, works), Vector.empty)
    assertEquals(checkAgainstDecisions(researchers, works, decisions), Vector.empty)
    assertEquals(checkAuthorships(authorships, researchers, works), Vector.empty)
    assertEquals(checkAuthorshipConfidence(authorships, decisions), Vector.empty)
    assertEquals(checkTeams(teams, researchers, works), Vector.empty)
  }

  // ------------------------------------------------------------------ researchers

  test("record-level errors are reported with the dataset index; a null dataset is refused") {
    val errs = checkResearchers(Vector(resolvedMention, resolvedProfile.copy(groupSize = 0)))
    assert(
      errs.exists {
        case ValidationError.InvalidValue(f, _) => f == "researchers[1].groupSize"
        case _ => false
      },
      errs
    )
    assertEquals(checkResearchers(null), Vector(ValidationError.MissingField("researchers")))
  }

  test("one record per member") {
    refused(checkResearchers(researchers :+ resolvedMention), "one record per key")
  }

  test("one run: one grouping policy and one grouping run") {
    val v2 = "er-grouping.v2"
    val otherPolicy = researchers.map(r =>
      if (r eq unresolvedMention)
        r.copy(groupingPolicyVersion = v2, provenance = r.provenance.copy(policyVersion = Some(v2)))
      else r
    )
    refused(checkResearchers(otherPolicy), "one run has one value")
    val otherRun = researchers.map(r =>
      if (r eq unresolvedMention)
        r.copy(provenance = r.provenance.copy(runId = Fixtures.runId.replace('8', '9')))
      else r
    )
    refused(checkResearchers(otherRun), "one run has one value")
  }

  test("one anchor per researcher ID: a second anchor for one ID is refused by the ID formula") {
    // Dataset rule backstop; with valid records the formula already binds one ID to one anchor.
    val secondAnchor =
      resolvedProfile.copy(anchorNamespace = Some("dblp-pid"), anchorValue = Some("00/0001"))
    assert(checkResearchers(Vector(resolvedMention, secondAnchor)).nonEmpty)
  }

  test("groupSize equals the number of members sharing the researcher ID") {
    refused(
      checkResearchers(Vector(resolvedMention, singletonMention, unresolvedMention)),
      "groupSize must equal its 1 member(s)"
    )
  }

  test("no two mentions of one work share a researcher ID") {
    val same = singletonMention.copy(
      status = AssignmentStatus.Resolved,
      researcherId = Some(researcherA),
      anchorNamespace = Some("orcid"),
      anchorValue = Some(orcidA),
      groupSize = 3,
      supportingDecisionIds = Vector(dR2.decisionId),
      pendingDecisionIds = Vector.empty
    )
    val set = Vector(resolvedMention.copy(groupSize = 3), resolvedProfile.copy(groupSize = 3), same)
    refused(checkResearchers(set), "is assigned to two mentions of one work")
  }

  test("every listed decision appears on exactly its two endpoints, in the same role") {
    refused(
      checkResearchers(researchers.filterNot(_ eq unresolvedMention)),
      "is listed on 1 record(s)"
    )
    val mixed = researchers.map(r =>
      if (r eq unresolvedMention)
        r.copy(pendingDecisionIds = Vector.empty, vetoedDecisionIds = Vector(dR2.decisionId))
      else r
    )
    refused(checkResearchers(mixed), "different roles on its two endpoints")
  }

  test("a supporting decision joins two records of one group") {
    val split = researchers.map(r =>
      if (r eq resolvedProfile)
        r.copy(
          researcherId = Some(ok(ResearcherId.scholarChain("v1", "member", r.memberId))),
          anchorNamespace = Some("member"),
          anchorValue = Some(r.memberId)
        )
      else r
    )
    refused(checkResearchers(split), "joins records of different groups")
  }

  test("a vetoed decision never joins two records of one group") {
    val x = ok(MentionId.of(workId2, 1))
    val inGroup = Vector(
      resolvedMention.copy(
        groupSize = 3,
        rejectedDecisionIds = Vector.empty,
        vetoedDecisionIds = Vector(dR3.decisionId)
      ),
      resolvedProfile.copy(
        groupSize = 3,
        supportingDecisionIds =
          Vector(dR1.decisionId, dR2.decisionId).sortWith((a, b) => Canonical.compareUtf8(a, b) < 0)
      ),
      researcher(
        x,
        Some(workId2),
        Some(1),
        AssignmentStatus.Resolved,
        Some(researcherA),
        Some("orcid" -> orcidA),
        3,
        supporting = Vector(dR2.decisionId),
        vetoed = Vector(dR3.decisionId),
        reasons = Vector("grouping.resolved")
      )
    )
    val errs = checkResearchers(inGroup)
    refused(errs, "vetoed decision")
    refused(errs, "ended in one group")
  }

  test("a resolved group is connected by its supporting decisions") {
    val x = ok(MentionId.of(workId2, 1))
    val y = ok(MentionId.of(workW2, 0))
    val d = "match:" + "e" * 64
    def member(id: String, w: String, pos: Int) = researcher(
      id,
      Some(w),
      Some(pos),
      AssignmentStatus.Resolved,
      Some(researcherA),
      Some("orcid" -> orcidA),
      4,
      supporting = Vector(d),
      reasons = Vector("grouping.resolved")
    )
    val set = Vector(
      resolvedMention.copy(groupSize = 4, rejectedDecisionIds = Vector.empty),
      resolvedProfile.copy(groupSize = 4),
      member(x, workId2, 1),
      member(y, workW2, 0)
    )
    val errs = checkResearchers(set)
    assertEquals(errs.map(_.message).count(_.contains("is not connected")), 1, errs)
    assertEquals(errs.size, 1, "the only problem is connectivity")
  }

  // ------------------------------------------------------------------ works

  test("works: one record per work; one run") {
    refused(checkWorks(works :+ countedSingle), "one record per key")
    val other = works.map(w =>
      if (w eq enrichmentSingle) w.copy(decisionRunId = groupingRunId.replace('a', 'b')) else w
    )
    refused(checkWorks(other), "one run has one value")
  }

  test("exactly one representative per canonical work") {
    refused(checkWorks(works.filterNot(_ eq countedRepresentative)), "has 0 representatives")
    // A copy whose canonical work has no record of its own: no representative.
    val orphan = work(
      workW1,
      workW2,
      WorkGroupStatus.Grouped,
      2,
      GraphEligibility.EnrichmentOnly,
      Vector(dW1.decisionId),
      Vector("grouping.copy")
    )
    refused(checkWorks(Vector(orphan)), "has 0 representatives; exactly one")
  }

  test("work groupSize equals the number of members") {
    refused(checkWorks(works.filterNot(_ eq countedCopy)), "groupSize must equal its 1 member(s)")
  }

  test(
    "graph eligibility: a DBLP work in a non-DBLP canonical work fits no label (E2 tie to DBLP)"
  ) {
    // The DBLP work is a copy of an OpenAlex representative: counted, member-of-counted and
    // enrichment-only are all refused, so a group with a DBLP member must have a DBLP
    // representative, which must be counted.
    val rep = work(
      workW1,
      workW1,
      WorkGroupStatus.Grouped,
      2,
      GraphEligibility.EnrichmentOnly,
      Vector(dW1.decisionId),
      Vector("grouping.representative")
    )
    for (label <- GraphEligibility.values.toVector) {
      val dblpCopy = work(
        workId,
        workW1,
        WorkGroupStatus.Grouped,
        2,
        label,
        Vector(dW1.decisionId),
        Vector("grouping.copy")
      )
      refused(checkWorks(Vector(rep, dblpCopy)), "graphEligibility")
    }
    // An all-non-DBLP group is enrichment-only throughout and valid.
    val f = "match:" + "f" * 64
    val ok2 = Vector(
      work(
        workW2,
        workW2,
        WorkGroupStatus.Grouped,
        2,
        GraphEligibility.EnrichmentOnly,
        Vector(f),
        Vector("r")
      ),
      work(
        "work:openalex:W3",
        workW2,
        WorkGroupStatus.Grouped,
        2,
        GraphEligibility.EnrichmentOnly,
        Vector(f),
        Vector("r")
      )
    )
    assertEquals(checkWorks(ok2), Vector.empty)
  }

  test(
    "O9: an enrichment-only representative keeps its authorship records but never has a team"
  ) {
    // A mention on the enrichment-only representative W2 (no DBLP member).
    val w2Mention = researcher(
      ok(MentionId.of(workW2, 0)),
      Some(workW2),
      Some(0),
      AssignmentStatus.Singleton,
      Some(ok(ResearcherId.scholarChain("v1", "openalex-author", "A8"))),
      Some("openalex-author" -> "A8"),
      1,
      reasons = Vector("grouping.singleton")
    )
    val rs = researchers :+ w2Mention
    refused(
      checkAuthorships(authorships, rs, works),
      s"identified mention ${w2Mention.memberId} on a representative work has no authorship record"
    )
    assertEquals(
      checkAuthorships(authorships :+ authorship(w2Mention, None, None), rs, works),
      Vector.empty
    )
    // Teams: none is expected for W2, and one for W2 is refused.
    assertEquals(checkTeams(teams, rs, works), Vector.empty)
    refused(
      checkTeams(
        teams :+ team(workW2, Vector(w2Mention.researcherId.get), 1, Vector.empty),
        rs,
        works
      ),
      "exists only for a counted canonical work"
    )
  }

  test("work decisions: a supporting decision appears on both endpoints of one canonical work") {
    val split = works.map(w =>
      if (w eq countedCopy) w.copy(supportingDecisionIds = Vector("match:" + "d" * 64)) else w
    )
    val errs = checkWorks(split)
    refused(errs, "is listed on 1 record(s)")
    refused(errs, "is not connected by its supporting decisions")
  }

  // ------------------------------------------------------------------ run and decisions

  test("researchers and works are one run, and every mention's work has a work assignment") {
    refused(
      checkRun(researchers, works.filterNot(_ eq countedSingle)),
      "has mentions but no work assignment"
    )
    val otherRun = works.map(w =>
      w.copy(provenance = w.provenance.copy(runId = Fixtures.runId.replace('8', '7')))
    )
    refused(checkRun(researchers, otherRun), "one run has one value")
  }

  test("assignments preserve their decisions: each on both endpoints, in its outcome's list") {
    refused(
      checkAgainstDecisions(researchers, works, decisions.filterNot(_ eq dR3)),
      "resolves to no decision"
    )
    val wrongList = researchers.map(r =>
      if (r eq unresolvedMention)
        r.copy(rejectedDecisionIds = Vector.empty, conflictDecisionIds = Vector(dR3.decisionId))
      else r
    )
    val errs = checkAgainstDecisions(wrongList, works, decisions)
    refused(errs, "is REJECTED, not CONFLICT")
    refused(errs, "must be listed once on")
    val forgotten = researchers.map(r =>
      if (r eq resolvedMention) r.copy(rejectedDecisionIds = Vector.empty) else r
    )
    refused(checkAgainstDecisions(forgotten, works, decisions), "must be listed once on")
  }

  test("decisions must belong to the decision run and match policy, touch members, and be unique") {
    val candidate = "mcand:" + "4" * 64
    val stranger = ok(
      MatchDecision.build(
        candidateId = candidate,
        entityKind = MatchEntityKind.Researcher,
        sourceId = mention1.mentionId,
        targetId = ok(MentionId.of(workId, 7)),
        matchPath = MatchDecisionPath.Probabilistic,
        decision = MatchOutcome.Rejected,
        confidence = 0.9,
        score = Some(0.05),
        blockingVersion = Fixtures.blockingVersion,
        featureVersion = Fixtures.featureVersion,
        features = SortedMap.empty,
        rulesFired = Vector("probabilistic.reject"),
        reasonCodes = Vector("score-at-or-below-reject"),
        conflicts = Vector.empty,
        adjudication = None,
        decidedAt = Fixtures.decidedAt,
        provenance = Fixtures.decisionProv(candidate)
      )
    )
    val errs = checkAgainstDecisions(researchers, works, decisions :+ stranger)
    refused(errs, "has no assignment")
    refused(errs, "must be listed once on")
    val otherPolicy = researchers.map(_.copy(matchPolicyVersion = "er-policy.v8"))
    refused(checkAgainstDecisions(otherPolicy, works, decisions), "another match policy version")
    val otherRun = researchers.map(_.copy(decisionRunId = groupingRunId))
    refused(checkAgainstDecisions(otherRun, works, decisions), "another decision run")
    refused(checkAgainstDecisions(researchers, works, decisions :+ dR1), "one record per key")
  }

  // ------------------------------------------------------------------ authorship v2

  test(
    "authorships: one per identified mention on a representative work, equal to its assignment"
  ) {
    refused(
      checkAuthorships(Vector(resolvedAuthorship), researchers, works),
      "has no authorship record"
    )
    refused(
      checkAuthorships(authorships :+ resolvedAuthorship, researchers, works),
      "one record per key"
    )
    val wrongResearcher = singletonAuthorship.copy(researcherId = researcherA)
    refused(
      checkAuthorships(Vector(resolvedAuthorship, wrongResearcher), researchers, works),
      "researcherId differs"
    )
    val wrongSupport = resolvedAuthorship.copy(supportingDecisionIds = Vector(dR2.decisionId))
    refused(
      checkAuthorships(Vector(wrongSupport, singletonAuthorship), researchers, works),
      "supportingDecisionIds differ"
    )
  }

  test("authorships: never for an unresolved mention, never on a non-representative copy") {
    val onUnresolved = AuthorshipRecordV2(
      schemaVersion = SchemaVersions.AuthorshipRecordV2,
      workId = workId2,
      mentionId = mentionU,
      researcherId = singletonId,
      position = 0,
      year = None,
      assignmentStatus = AssignmentStatus.Singleton,
      supportingDecisionIds = Vector.empty,
      matchConfidence = None,
      groupingPolicyVersion = groupingPolicy,
      matchPolicyVersion = matchPolicy,
      decisionRunId = decisionRunId,
      provenance = groupingProv(mentionU, AssignmentSchema)
    )
    refused(
      checkAuthorships(authorships :+ onUnresolved, researchers, works),
      "the mention is unresolved"
    )
    val copyMention = researcher(
      ok(MentionId.of(workW1, 0)),
      Some(workW1),
      Some(0),
      AssignmentStatus.Singleton,
      Some(ok(ResearcherId.scholarChain("v1", "openalex-author", "A9"))),
      Some("openalex-author" -> "A9"),
      1,
      reasons = Vector("grouping.singleton")
    )
    val rs = researchers :+ copyMention
    assertEquals(
      checkAuthorships(authorships, rs, works),
      Vector.empty,
      "no record is expected for the copy"
    )
    refused(
      checkAuthorships(authorships :+ authorship(copyMention, None, None), rs, works),
      "is not a representative"
    )
  }

  test("authorship confidence is the lowest confidence of the supporting decisions") {
    refused(
      checkAuthorshipConfidence(
        Vector(resolvedAuthorship.copy(matchConfidence = Some(0.5))),
        decisions
      ),
      "the lowest supporting confidence"
    )
    refused(
      checkAuthorshipConfidence(authorships, decisions.filterNot(_ eq dR1)),
      "must resolve to an ACCEPTED decision"
    )
  }

  // ------------------------------------------------------------------ teams

  test(
    "teams: one per counted work with mentions, and none for a copy or an enrichment-only work"
  ) {
    refused(checkTeams(teams.take(1), researchers, works), "has mentions but no team")
    val copyTeam = team(workW1, Vector(researcherA), 1, Vector.empty)
    refused(
      checkTeams(teams :+ copyTeam, researchers, works),
      "exists only for a counted canonical work"
    )
    val enrichmentTeam = team(workW2, Vector.empty, 1, Vector(ok(MentionId.of(workW2, 0))))
    refused(
      checkTeams(teams :+ enrichmentTeam, researchers, works),
      "exists only for a counted canonical work"
    )
  }

  test(
    "teams come from the representative only: author order, unresolved mentions, every mention counted"
  ) {
    val reordered =
      teams.map(t => if (t.workId == workId) t.copy(researcherIds = t.researcherIds.reverse) else t)
    refused(checkTeams(reordered, researchers, works), "in author order")
    val dropped =
      teams.map(t => if (t.workId == workId2) t.copy(unresolvedMentionIds = Vector.empty) else t)
    refused(checkTeams(dropped, researchers, works), "unresolved mentions")
    val big = teams.map(t => if (t.workId == workId) t.copy(teamSize = 3) else t)
    refused(checkTeams(big, researchers, works), "teamSize must equal")
    // A mention on the non-representative copy W1 adds nothing to the canonical work's team.
    val copyMention = researcher(
      ok(MentionId.of(workW1, 0)),
      Some(workW1),
      Some(0),
      AssignmentStatus.Singleton,
      Some(ok(ResearcherId.scholarChain("v1", "openalex-author", "A9"))),
      Some("openalex-author" -> "A9"),
      1,
      reasons = Vector("grouping.singleton")
    )
    assertEquals(checkTeams(teams, researchers :+ copyMention, works), Vector.empty)
    assertEquals(mention0.workId, workId)
  }

  // ------------------------------------------------------------------ repair 01 regressions

  private def sorted(ids: String*): Vector[String] =
    ids.toVector.sortWith((a, b) => Canonical.compareUtf8(a, b) < 0)

  /**
   * F1: mention0, the profile and x share researcherA (connected by dR1 and dR2), while mention0
   * and x carry the negative decision dR3 in `role`.
   */
  private def mergedAcross(role: DecisionRole): Vector[ResearcherAssignment] = {
    def put(r: ResearcherAssignment, ids: Vector[String]) = role match {
      case DecisionRole.Rejected => r.copy(rejectedDecisionIds = ids)
      case DecisionRole.Conflict => r.copy(conflictDecisionIds = ids)
      case other => fail(s"not a negative role: $other")
    }
    val x = researcher(
      ok(MentionId.of(workId2, 1)),
      Some(workId2),
      Some(1),
      AssignmentStatus.Resolved,
      Some(researcherA),
      Some("orcid" -> orcidA),
      3,
      supporting = Vector(dR2.decisionId),
      reasons = Vector("grouping.resolved")
    )
    Vector(
      put(
        resolvedMention.copy(groupSize = 3, rejectedDecisionIds = Vector.empty),
        Vector(dR3.decisionId)
      ),
      resolvedProfile
        .copy(groupSize = 3, supportingDecisionIds = sorted(dR1.decisionId, dR2.decisionId)),
      put(x, Vector(dR3.decisionId))
    )
  }

  test("F1: a REJECTED or CONFLICT decision never joins two members of one researcher") {
    for (
      (role, tag) <- Vector(
        DecisionRole.Rejected -> "REJECTED",
        DecisionRole.Conflict -> "CONFLICT"
      )
    ) {
      val errs = checkResearchers(mergedAcross(role))
      // The negative decision is the only defect: without the F1 rule this set is accepted.
      assertEquals(errs.size, 1, errs)
      refused(errs, s"$tag decision ${dR3.decisionId} joins records that ended in one group")
    }
    // An UNRESOLVED (pending) decision inside one group is not constrained.
    val pendingInside = mergedAcross(DecisionRole.Rejected).map(r =>
      if (r.rejectedDecisionIds.nonEmpty)
        r.copy(rejectedDecisionIds = Vector.empty, pendingDecisionIds = Vector(dR3.decisionId))
      else r
    )
    assertEquals(checkResearchers(pendingInside), Vector.empty)
    // Endpoints in different groups (or unresolved) stay valid: the base set has dR3 REJECTED
    // between the resolved mention0 and the unresolved mentionU.
    assertEquals(checkResearchers(researchers), Vector.empty)
  }

  test("F1: a REJECTED or CONFLICT decision never joins two works of one canonical work") {
    val w3 = "work:openalex:W3"
    val dX = "match:" + "a" * 64
    val dY = "match:" + "b" * 64
    def set(role: DecisionRole): Vector[WorkAssignment] = {
      def put(w: WorkAssignment) = role match {
        case DecisionRole.Rejected => w.copy(rejectedDecisionIds = Vector(dY))
        case DecisionRole.Conflict => w.copy(conflictDecisionIds = Vector(dY))
        case other => fail(s"not a negative role: $other")
      }
      Vector(
        put(countedRepresentative.copy(groupSize = 3)),
        countedCopy.copy(groupSize = 3, supportingDecisionIds = sorted(dW1.decisionId, dX)),
        put(
          work(
            w3,
            workId,
            WorkGroupStatus.Grouped,
            3,
            GraphEligibility.MemberOfCounted,
            Vector(dX),
            Vector("grouping.copy")
          )
        ),
        countedSingle,
        enrichmentSingle
      )
    }
    for (
      (role, tag) <- Vector(
        DecisionRole.Rejected -> "REJECTED",
        DecisionRole.Conflict -> "CONFLICT"
      )
    ) {
      val errs = checkWorks(set(role))
      assertEquals(errs.size, 1, errs)
      refused(errs, s"$tag decision $dY joins records that ended in one group")
    }
    // The same decision between two different canonical works is valid.
    val apart = Vector(
      countedRepresentative.copy(rejectedDecisionIds = Vector(dY)),
      countedCopy,
      countedSingle.copy(rejectedDecisionIds = Vector(dY)),
      enrichmentSingle
    )
    assertEquals(checkWorks(apart), Vector.empty)
  }

  // F2 ---------------------------------------------------------------

  /** The ORCID iD and DBLP pid each fixture mention asserts (mention1 asserts pid 00/0002). */
  private val identifiers: Vector[MemberIdentifier] =
    Vector(mention0, mention1).flatMap(MemberIdentifier.fromMention)

  test("F2: a t1-anchored singleton asserting its own, non-colliding pid is accepted") {
    assert(
      identifiers.contains(
        MemberIdentifier(mention1.mentionId, ExactId(ExactIdScheme.DblpPid, "00/0002"))
      )
    )
    assertEquals(checkSingletonAnchors(researchers, identifiers), Vector.empty)
    // An identity link gives the same input.
    val link = ok(
      IdentityLink.build(
        ExactId(ExactIdScheme.DblpPid, "00/0002"),
        MatchEntityKind.Researcher,
        mention1.mentionId,
        Some(workId),
        None,
        Fixtures.dblpProv
      )
    )
    assertEquals(
      MemberIdentifier.fromLink(link),
      Some(MemberIdentifier(mention1.mentionId, ExactId(ExactIdScheme.DblpPid, "00/0002")))
    )
    assertEquals(
      MemberIdentifier.fromLink(Fixtures.mentionLink),
      Some(MemberIdentifier(mention0.mentionId, ExactId(ExactIdScheme.Orcid, orcidA)))
    )
    assertEquals(
      checkSingletonAnchors(researchers, MemberIdentifier.fromLink(link).toVector),
      Vector.empty
    )
    // A singleton without pending, vetoed or conflict decisions needs no t1 anchor.
    val plain = singletonMention.copy(pendingDecisionIds = Vector.empty)
    assertEquals(checkSingletonAnchors(Vector(plain), Vector.empty), Vector.empty)
  }

  test("F2: a t1-anchored singleton whose anchor the member does not assert is refused") {
    refused(
      checkSingletonAnchors(researchers, Vector.empty),
      "is not asserted by the member itself"
    )
    val otherValue = identifiers.map(i =>
      if (i.memberId == mention1.mentionId)
        i.copy(identifier = ExactId(ExactIdScheme.DblpPid, "00/0009"))
      else i
    )
    refused(checkSingletonAnchors(researchers, otherValue), "is not asserted by the member itself")
  }

  test("F2: a t1-anchored singleton whose anchor another member asserts or uses is refused") {
    // Asserted by the unresolved mention too (an unresolved member counts as its own group).
    val shared =
      identifiers :+ MemberIdentifier(mentionU, ExactId(ExactIdScheme.DblpPid, "00/0002"))
    refused(checkSingletonAnchors(researchers, shared), s"collides: also asserted by $mentionU")
    // Asserted by the profile of another group.
    val byProfile = identifiers :+ MemberIdentifier(
      Fixtures.openAlexProfileId,
      ExactId(ExactIdScheme.DblpPid, "00/0002")
    )
    refused(checkSingletonAnchors(researchers, byProfile), "collides: also asserted by")
    // Used as the anchor of another member record.
    val twin = singletonMention.copy(
      memberId = ok(MentionId.of(workId2, 1)),
      workId = Some(workId2),
      provenance = singletonMention.provenance.copy(sourceRecordId = ok(MentionId.of(workId2, 1)))
    )
    refused(
      checkSingletonAnchors(researchers :+ twin, identifiers),
      s"collides: also the anchor of ${twin.memberId}"
    )
  }

  test("F2: a t1-anchored singleton outside orcid and dblp-pid is refused at both levels") {
    val member = singletonMention.copy(
      researcherId = Some(ok(ResearcherId.scholarChain("v1", "member", mention1.mentionId))),
      anchorNamespace = Some("member"),
      anchorValue = Some(mention1.mentionId)
    )
    refused(
      checkSingletonAnchors(Vector(member), identifiers),
      "anchored on its own orcid or dblp-pid"
    )
    refused(checkResearchers(Vector(member)), "anchored on its own orcid or dblp-pid")
  }

  test("F2: null inputs are refused as missing") {
    assertEquals(
      checkSingletonAnchors(null, identifiers),
      Vector(ValidationError.MissingField("researchers"))
    )
    assertEquals(
      checkSingletonAnchors(researchers, null),
      Vector(ValidationError.MissingField("identifiers"))
    )
    assertEquals(
      checkSingletonAnchors(researchers, Vector(MemberIdentifier(mention1.mentionId, null))),
      Vector(ValidationError.MissingField("identifiers"))
    )
  }

  // F3 ---------------------------------------------------------------

  private val m2: String = ok(MentionId.of(workId, 2))

  /** The expected author lists of every representative of the base set. */
  private val expected: Vector[ExpectedAuthors] = Vector(
    ExpectedAuthors.of(Fixtures.work),
    ExpectedAuthors(workId2, Vector(mentionU)),
    ExpectedAuthors(workW2, Vector.empty)
  )

  private def withAuthors(w: String, ids: Vector[String]) =
    expected.map(e => if (e.workId == w) e.copy(mentionIds = ids) else e)

  test("F3: a complete team is accepted") {
    assertEquals(expected.head.mentionIds, Vector(mention0.mentionId, mention1.mentionId))
    assertEquals(checkCoverage(researchers, works, expected), Vector.empty)
  }

  test("F3: a missing final author is refused (the assignments alone look complete)") {
    val threeAuthors = withAuthors(workId, Vector(mention0.mentionId, mention1.mentionId, m2))
    // Positions 0 and 1 are contiguous, and the team agrees with the assignments: the earlier
    // checks accept this set, and only the source work's expected authors reveal the loss.
    assertEquals(checkTeams(teams, researchers, works), Vector.empty)
    assertEquals(checkAuthorships(authorships, researchers, works), Vector.empty)
    val errs = checkCoverage(researchers, works, threeAuthors)
    assertEquals(errs.size, 1, errs)
    refused(errs, "author position(s) 2 of 3 have no researcher assignment")
  }

  test("F3: a gap in the middle is refused") {
    val third = researcher(
      m2,
      Some(workId),
      Some(2),
      AssignmentStatus.Singleton,
      Some(ok(ResearcherId.scholarChain("v1", "dblp-pid", "00/0003"))),
      Some("dblp-pid" -> "00/0003"),
      1,
      reasons = Vector("grouping.singleton")
    )
    val gap = researchers.filterNot(_ eq singletonMention) :+ third
    val threeAuthors = withAuthors(workId, Vector(mention0.mentionId, mention1.mentionId, m2))
    val errs = checkCoverage(gap, works, threeAuthors)
    assertEquals(errs.size, 1, errs)
    refused(errs, "author position(s) 1 of 3 have no researcher assignment")
  }

  test("F3: an assigned mention that is not an author of the source work is refused") {
    refused(
      checkCoverage(researchers, works, withAuthors(workId, Vector(mention0.mentionId))),
      "are not authors of the source work"
    )
  }

  test("F3: expected author lists are complete, well formed and attached to known works") {
    refused(
      checkCoverage(researchers, works, expected.filterNot(_.workId == workW2)),
      s"representative $workW2 has no expected author list"
    )
    refused(
      checkCoverage(
        researchers,
        works,
        withAuthors(workId, Vector(mention1.mentionId, mention0.mentionId))
      ),
      "is not the mention at position 0"
    )
    refused(checkCoverage(researchers, works, expected :+ expected.head), "one record per key")
    refused(
      checkCoverage(
        researchers,
        works,
        expected :+ ExpectedAuthors("work:openalex:W9", Vector.empty)
      ),
      "work:openalex:W9 has no work assignment"
    )
    assertEquals(
      checkCoverage(researchers, works, null),
      Vector(ValidationError.MissingField("expected"))
    )
  }

  test("n2: a null or malformed expected workId gives a typed error, never an exception") {
    // Null among several entries: without the guard, sorting and keying compare the null and throw.
    val nullFirst = ExpectedAuthors(null, Vector.empty) +: expected
    assertEquals(
      checkCoverage(researchers, works, nullFirst),
      Vector(ValidationError.MissingField("expected[0].workId"))
    )
    val twoNulls = expected.patch(1, Vector(ExpectedAuthors(null, Vector(mentionU))), 0) :+
      ExpectedAuthors(null, Vector.empty)
    assertEquals(
      checkCoverage(researchers, works, twoNulls),
      Vector(
        ValidationError.MissingField("expected[1].workId"),
        ValidationError.MissingField(s"expected[${twoNulls.size - 1}].workId")
      )
    )
    assertEquals(
      checkCoverage(researchers, works, Vector(ExpectedAuthors(null, Vector.empty))),
      Vector(ValidationError.MissingField("expected[0].workId"))
    )
    // A malformed, non-null work ID among several entries is a typed ID error.
    val malformed =
      checkCoverage(researchers, works, expected :+ ExpectedAuthors("nope", Vector.empty))
    assert(malformed.exists(_.isInstanceOf[ValidationError.MalformedId]), malformed)
  }

  // F5 and N6 ---------------------------------------------------------

  test("F5: duplicate counts and decision-list lookups are unchanged") {
    val errs = checkResearchers(researchers ++ Vector(resolvedMention, resolvedMention))
    refused(errs, s"${resolvedMention.memberId} appears in 3 records; one record per key")
    // A decision listed in two lists of one record is still reported as not listed once.
    val twice = researchers.map(r =>
      if (r eq unresolvedMention) r.copy(conflictDecisionIds = Vector(dR3.decisionId)) else r
    )
    refused(checkAgainstDecisions(twice, works, decisions), "must be listed once on")
    assertEquals(checkAgainstDecisions(researchers, works, decisions), Vector.empty)
  }

  test("N6: unresolved mentions of a team are in author order") {
    val u1 = ok(MentionId.of(workId2, 1))
    val second = unresolvedMention.copy(
      memberId = u1,
      position = Some(1),
      provenance = unresolvedMention.provenance.copy(sourceRecordId = u1)
    )
    val rs = researchers :+ second
    val t = team(workId2, Vector.empty, 2, Vector(mentionU, u1))
    val ts = teams.map(x => if (x.workId == workId2) t else x)
    assertEquals(checkTeams(ts, rs, works), Vector.empty)
    refused(
      checkTeams(
        teams.map(x =>
          if (x.workId == workId2) t.copy(unresolvedMentionIds = Vector(u1, mentionU)) else x
        ),
        rs,
        works
      ),
      "unresolved mentions, in author order"
    )
  }
}
