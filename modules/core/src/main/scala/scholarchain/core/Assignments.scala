package scholarchain.core

import upickle.default.ReadWriter
import upickle.implicits.allowUnknownKeys

import Validated.{all, at, when}

/**
 * Status of one researcher member (an author mention or a source author profile) after grouping
 * (`er-grouping.v1`, Phase 6 decision D1). Frozen tags.
 *
 *   - `resolved`: the member is in a group of two or more members joined by kept ACCEPTED
 *     decisions; it carries the group's researcher ID.
 *   - `singleton`: a group of one that still receives its own researcher ID (no pairwise match is
 *     needed, so it may have no supporting decision). A singleton that carries a pending, vetoed or
 *     conflict decision is `t1-anchored`: its anchor is its own, non-colliding ORCID iD or DBLP
 *     pid.
 *   - `unresolved`: no researcher ID is assigned; the record keeps the pending, vetoed or conflict
 *     decisions and reason codes that left it unresolved.
 */
enum AssignmentStatus(val tag: String, val identified: Boolean) {
  case Resolved extends AssignmentStatus("resolved", true)
  case Singleton extends AssignmentStatus("singleton", true)
  case Unresolved extends AssignmentStatus("unresolved", false)
}
object AssignmentStatus {
  def fromTag(tag: String): Option[AssignmentStatus] = values.find(_.tag == tag)
  given ReadWriter[AssignmentStatus] = EnumCodec.tagged("AssignmentStatus", values.toSeq, _.tag)
}

/** Whether a work was grouped with other works (`grouped`) or stands alone (`single`). */
enum WorkGroupStatus(val tag: String) {
  case Grouped extends WorkGroupStatus("grouped")
  case Single extends WorkGroupStatus("single")
}
object WorkGroupStatus {
  def fromTag(tag: String): Option[WorkGroupStatus] = values.find(_.tag == tag)
  given ReadWriter[WorkGroupStatus] = EnumCodec.tagged("WorkGroupStatus", values.toSeq, _.tag)
}

/**
 * Graph scope of one work record (Phase 6 decision E2: only DBLP-backed canonical works enter the
 * coauthorship graph; every other work is kept for enrichment). Frozen tags. Stored on every
 * [[WorkAssignment]] so that consumers select counted works with one equality test and never
 * re-derive the scope.
 *
 *   - `counted`: the representative of a canonical work whose group has a DBLP member. The
 *     representative is itself a DBLP work, and its author list is the one team of the canonical
 *     work.
 *   - `member-of-counted`: a non-representative copy of a counted canonical work. It is never
 *     counted on its own, and its mentions form no team and no edge.
 *   - `enrichment-only`: any member of a canonical work whose group has no DBLP member. Kept for
 *     enrichment, never counted in the graph.
 */
enum GraphEligibility(val tag: String, val counted: Boolean) {
  case Counted extends GraphEligibility("counted", true)
  case MemberOfCounted extends GraphEligibility("member-of-counted", false)
  case EnrichmentOnly extends GraphEligibility("enrichment-only", false)
}
object GraphEligibility {
  def fromTag(tag: String): Option[GraphEligibility] = values.find(_.tag == tag)
  given ReadWriter[GraphEligibility] = EnumCodec.tagged("GraphEligibility", values.toSeq, _.tag)
}

/**
 * The role of a match decision on an assignment, the decision-ID list that holds it, and the only
 * outcome a decision in that list may have.
 */
enum DecisionRole(val field: String, val outcome: MatchOutcome) {

  /** ACCEPTED decisions kept as links of the member's group (including redundant ones). */
  case Supporting extends DecisionRole("supportingDecisionIds", MatchOutcome.Accepted)

  /** ACCEPTED decisions whose merge was skipped by a cannot-link rule. */
  case Vetoed extends DecisionRole("vetoedDecisionIds", MatchOutcome.Accepted)

  /** UNRESOLVED decisions touching the member. */
  case Pending extends DecisionRole("pendingDecisionIds", MatchOutcome.Unresolved)

  /** CONFLICT decisions touching the member. */
  case Conflict extends DecisionRole("conflictDecisionIds", MatchOutcome.Conflict)

  /** REJECTED decisions touching the member. */
  case Rejected extends DecisionRole("rejectedDecisionIds", MatchOutcome.Rejected)
}

/** Rules shared by the assignment records. */
private[core] object AssignmentRules {

  val ResearcherMemberKinds: Set[IdKind] = Set(IdKind.Mention, IdKind.SourceAuthor)

  private val DblpPrefix: String = s"work:${WorkRecord.NamespaceOf(SourceType.Dblp)}:"

  /** True when `workId` is a DBLP work ID. */
  def isDblp(workId: String): Boolean = workId != null && workId.startsWith(DblpPrefix)

  def token(field: String, v: String): Vector[ValidationError] =
    when(!StableId.isToken(v))(ValidationError.InvalidValue(field, "missing or not a token"))

  /** A decision-ID list: present, every element a match decision ID, sorted by bytes, distinct. */
  def decisionList(field: String, ids: Vector[String]): Vector[ValidationError] =
    if (ids == null) Vector(ValidationError.MissingField(field))
    else
      ids.zipWithIndex.flatMap { case (id, i) =>
        StableId.check(IdKind.MatchDecision, id, s"$field[$i]")
      } ++ Validated.strictlyAscending(field, ids)

  /** No decision ID appears in two lists of one record. */
  def disjoint(
      p: String,
      lists: Vector[(DecisionRole, Vector[String])]
  ): Vector[ValidationError] = {
    val seen = lists.flatMap { case (r, ids) =>
      Option(ids).getOrElse(Vector.empty).filter(_ != null).map(_ -> r)
    }
    seen.groupBy(_._1).toVector.sortBy(_._1).flatMap { case (id, roles) =>
      when(roles.map(_._2).distinct.size > 1)(
        ValidationError.InvalidValue(
          at(p, roles.map(_._2.field).distinct.mkString(",")),
          s"decision $id appears in more than one list"
        )
      )
    }
  }

  /** Reason codes: non-empty, tokens, sorted by UTF-8 bytes without duplicates. */
  def reasonCodes(field: String, codes: Vector[String]): Vector[ValidationError] =
    if (codes == null || codes.isEmpty) Vector(ValidationError.MissingField(field))
    else
      codes.zipWithIndex.flatMap { case (c, i) =>
        when(!StableId.isToken(c))(ValidationError.InvalidValue(s"$field[$i]", "not a token"))
      } ++ Validated.strictlyAscending(field, codes)

  /**
   * The two policy versions, the decision run, the assignment instant and the provenance of an
   * assignment-run record: `scholarchain-derived`, `sourceRecordId` = the record's own subject,
   * `policyVersion` = the grouping policy version, no model or prompt (grouping makes no LLM call),
   * and a run that is not the decision run.
   */
  def runFields(
      p: String,
      subject: String,
      groupingPolicyVersion: String,
      matchPolicyVersion: String,
      decisionRunId: String,
      assignedAt: Option[String],
      pr: Provenance
  ): Vector[ValidationError] =
    all(
      token(at(p, "groupingPolicyVersion"), groupingPolicyVersion),
      token(at(p, "matchPolicyVersion"), matchPolicyVersion),
      when(
        groupingPolicyVersion != null && groupingPolicyVersion == matchPolicyVersion
      )(
        ValidationError.InvalidValue(
          at(p, "groupingPolicyVersion"),
          "the grouping policy is versioned separately from the match policy"
        )
      ),
      StableId.check(IdKind.Run, decisionRunId, at(p, "decisionRunId")),
      assignedAt.toVector.flatMap(MatchDecision.checkInstant(at(p, "assignedAt"), _)),
      CandidateRules.derivedCheck(at(p, "provenance.sourceDataset"), pr),
      Option(pr).toVector.flatMap { x =>
        all(
          when(subject != null && x.sourceRecordId != subject)(
            ValidationError.InvalidValue(at(p, "provenance.sourceRecordId"), s"must equal $subject")
          ),
          when(
            groupingPolicyVersion != null && Option(x.policyVersion).flatten != Some(
              groupingPolicyVersion
            )
          )(
            ValidationError.InvalidValue(
              at(p, "provenance.policyVersion"),
              "must equal groupingPolicyVersion"
            )
          ),
          when(
            Option(x.modelVersion).flatten.isDefined || Option(x.promptVersion).flatten.isDefined
          )(
            ValidationError.InvalidValue(
              at(p, "provenance.modelVersion,promptVersion"),
              "grouping makes no model call: both must be null"
            )
          ),
          when(x.runId != null && x.runId == decisionRunId)(
            ValidationError.InvalidValue(
              at(p, "provenance.runId"),
              "the assignment run must differ from the decision run"
            )
          )
        )
      }
    )
}

import AssignmentRules.*

/**
 * The grouping result for one researcher member (schema `researcher-assignment.v1`). One record per
 * author mention and per source author profile of the decided curated run. Produced by the
 * entity-resolution grouping layer (`er-grouping.v1`); core validates the shape and the ID formula
 * only and never chooses groups, anchors or IDs.
 *
 * @param memberId
 *   the member: an author mention ID or a source author profile ID (the endpoints of researcher
 *   match decisions)
 * @param workId
 *   the mention's work; present exactly for mention members
 * @param position
 *   the mention's 0-based author position; present exactly for mention members, and
 *   [[MentionId.of]](workId, position) must reproduce `memberId`
 * @param status
 *   resolved, singleton or unresolved
 * @param researcherId
 *   the assigned researcher; present exactly when status is not `unresolved`. ORCID form
 *   `researcher:orcid:<anchorValue>` when `anchorNamespace` is `orcid`; otherwise
 *   [[ResearcherId.scholarChain]](identityVersion, anchorNamespace, anchorValue)
 * @param identityVersion
 *   version of ScholarChain identity IDs in force for the run (`v[0-9]+`, `graph.identityVersion`)
 * @param anchorNamespace
 *   anchor namespace (token) of the researcher ID; present exactly with `researcherId`
 * @param anchorValue
 *   anchor value (printable ASCII); present exactly with `researcherId`
 * @param groupSize
 *   number of members that share the researcher ID (1 for singleton and unresolved)
 * @param supportingDecisionIds
 *   ACCEPTED decisions kept as links of the group that touch this member; non-empty for `resolved`,
 *   empty otherwise
 * @param vetoedDecisionIds
 *   ACCEPTED decisions touching this member whose merge a cannot-link rule skipped
 * @param pendingDecisionIds
 *   UNRESOLVED decisions touching this member
 * @param conflictDecisionIds
 *   CONFLICT decisions touching this member
 * @param rejectedDecisionIds
 *   REJECTED decisions touching this member. Together the five lists hold every decision of the
 *   decision run that touches the member, each in exactly one list; every list is sorted by UTF-8
 *   bytes without duplicates. An `unresolved` member has at least one pending, vetoed or conflict
 *   decision
 * @param reasonCodes
 *   grouping reason codes (tokens), non-empty, sorted by UTF-8 bytes without duplicates
 * @param groupingPolicyVersion
 *   grouping policy version (token, for example `er-grouping.v1`); equals
 *   `provenance.policyVersion`
 * @param matchPolicyVersion
 *   match policy version of the decisions the record rests on (token, for example `er-policy.v8`);
 *   must differ from the grouping policy version
 * @param decisionRunId
 *   the decision run whose decisions were grouped (every listed decision belongs to it)
 * @param assignedAt
 *   ISO-8601 instant in UTC (`Z`); not an input of any ID
 * @param provenance
 *   `scholarchain-derived`; `sourceRecordId` = `memberId`; `runId` = the grouping run (not the
 *   decision run); `policyVersion` = `groupingPolicyVersion`; no model or prompt
 */
@allowUnknownKeys(false)
final case class ResearcherAssignment(
    schemaVersion: String,
    memberId: String,
    workId: Option[String],
    position: Option[Int],
    status: AssignmentStatus,
    researcherId: Option[String],
    identityVersion: String,
    anchorNamespace: Option[String],
    anchorValue: Option[String],
    groupSize: Int,
    supportingDecisionIds: Vector[String],
    vetoedDecisionIds: Vector[String],
    pendingDecisionIds: Vector[String],
    conflictDecisionIds: Vector[String],
    rejectedDecisionIds: Vector[String],
    reasonCodes: Vector[String],
    groupingPolicyVersion: String,
    matchPolicyVersion: String,
    decisionRunId: String,
    assignedAt: String,
    provenance: Provenance
) derives ReadWriter

object ResearcherAssignment {

  val OrcidNamespace = "orcid"
  private val OrcidPrefix = "researcher:orcid:"
  private val IdentityVersionPattern = "^v[0-9]+$".r

  /**
   * Anchor namespaces of a `t1-anchored` singleton (decision D1): the member's own ORCID iD or DBLP
   * pid (the [[ExactIdScheme]] tags `orcid` and `dblp-pid`).
   */
  val T1AnchorNamespaces: Set[String] = Set(ExactIdScheme.Orcid.tag, ExactIdScheme.DblpPid.tag)

  /**
   * True for a singleton that carries a pending, vetoed or conflict decision: it may have a
   * researcher ID only when anchored in [[T1AnchorNamespaces]] (record contract) on its own,
   * non-colliding value ([[AssignmentValidation.checkSingletonAnchors]]).
   */
  def requiresT1Anchor(t: ResearcherAssignment): Boolean =
    t.status == AssignmentStatus.Singleton && blockingDecisions(t).nonEmpty

  private def blockingDecisions(t: ResearcherAssignment): Vector[String] =
    Option(t.pendingDecisionIds).getOrElse(Vector.empty) ++
      Option(t.vetoedDecisionIds).getOrElse(Vector.empty) ++
      Option(t.conflictDecisionIds).getOrElse(Vector.empty)

  /** The five decision lists of `t`, in field order (null lists read as empty). */
  def decisionLists(t: ResearcherAssignment): Vector[(DecisionRole, Vector[String])] =
    Vector(
      DecisionRole.Supporting -> t.supportingDecisionIds,
      DecisionRole.Vetoed -> t.vetoedDecisionIds,
      DecisionRole.Pending -> t.pendingDecisionIds,
      DecisionRole.Conflict -> t.conflictDecisionIds,
      DecisionRole.Rejected -> t.rejectedDecisionIds
    ).map { case (r, ids) => r -> Option(ids).getOrElse(Vector.empty) }

  /** True when the member is an author mention (otherwise a source author profile). */
  def isMention(t: ResearcherAssignment): Boolean =
    StableId.prefixKind(t.memberId).contains(IdKind.Mention)

  private def memberFields(t: ResearcherAssignment, p: String): Vector[ValidationError] = {
    val work = Option(t.workId).getOrElse(None)
    val pos = Option(t.position).getOrElse(None)
    StableId.checkOneOf(ResearcherMemberKinds, t.memberId, at(p, "memberId")) ++ {
      StableId.prefixKind(t.memberId) match {
        case Some(IdKind.Mention) =>
          all(
            when(work.isEmpty)(ValidationError.MissingField(at(p, "workId"))),
            when(pos.isEmpty)(ValidationError.MissingField(at(p, "position"))),
            work.toVector.flatMap(StableId.check(IdKind.Work, _, at(p, "workId"))),
            pos.toVector.flatMap { n =>
              when(n < 0)(ValidationError.InvalidValue(at(p, "position"), s"$n is negative"))
            },
            (work, pos) match {
              case (Some(w), Some(n)) =>
                MentionId.of(w, n) match {
                  case Right(expected) =>
                    when(expected != t.memberId)(
                      ValidationError.InvalidValue(
                        at(p, "memberId"),
                        s"is not the mention at position $n of $w (expected $expected)"
                      )
                    )
                  case Left(_) => Vector.empty
                }
              case _ => Vector.empty
            }
          )
        case Some(IdKind.SourceAuthor) =>
          all(
            when(work.isDefined)(
              ValidationError.InvalidValue(at(p, "workId"), "only allowed for mention members")
            ),
            when(pos.isDefined)(
              ValidationError.InvalidValue(at(p, "position"), "only allowed for mention members")
            )
          )
        case _ => Vector.empty
      }
    }
  }

  private def identityFields(t: ResearcherAssignment, p: String): Vector[ValidationError] = {
    val rid = Option(t.researcherId).getOrElse(None)
    val ns = Option(t.anchorNamespace).getOrElse(None)
    val value = Option(t.anchorValue).getOrElse(None)
    val identified = t.status != null && t.status.identified
    val presence =
      if (t.status == null) Vector.empty
      else if (identified)
        all(
          when(rid.isEmpty)(ValidationError.MissingField(at(p, "researcherId"))),
          when(ns.isEmpty)(ValidationError.MissingField(at(p, "anchorNamespace"))),
          when(value.isEmpty)(ValidationError.MissingField(at(p, "anchorValue")))
        )
      else
        when(rid.isDefined || ns.isDefined || value.isDefined)(
          ValidationError.InvalidValue(
            at(p, "researcherId,anchorNamespace,anchorValue"),
            "an unresolved member has no researcher ID and no anchor"
          )
        )
    val formats = all(
      rid.toVector.flatMap(StableId.check(IdKind.Researcher, _, at(p, "researcherId"))),
      ns.toVector.flatMap(token(at(p, "anchorNamespace"), _)),
      value.toVector.flatMap { v =>
        when(!Identifiers.isPrintableAscii(v))(
          ValidationError.InvalidValue(at(p, "anchorValue"), "must be non-empty printable ASCII")
        )
      }
    )
    val formula = (rid, ns, value) match {
      case (Some(r), Some(n), Some(v)) if formats.isEmpty =>
        if (r.startsWith(OrcidPrefix))
          all(
            when(n != OrcidNamespace)(
              ValidationError.InvalidValue(
                at(p, "anchorNamespace"),
                s"an ORCID-form researcher ID is anchored in namespace '$OrcidNamespace'"
              )
            ),
            when(!Identifiers.isValidOrcid(v))(
              ValidationError.InvalidValue(at(p, "anchorValue"), "not a bare, valid ORCID iD")
            ),
            when(ResearcherId.orcid(v) != Right(r))(
              ValidationError
                .InvalidValue(at(p, "researcherId"), "does not equal orcid(anchorValue)")
            )
          )
        else if (n == OrcidNamespace)
          Vector(
            ValidationError.InvalidValue(
              at(p, "researcherId"),
              "an ORCID anchor gives the ORCID-form researcher ID, never the sc form"
            )
          )
        else
          ResearcherId.scholarChain(t.identityVersion, n, v) match {
            case Right(expected) =>
              when(expected != r)(
                ValidationError.InvalidValue(at(p, "researcherId"), s"expected $expected")
              )
            case Left(_) => Vector.empty
          }
      case _ => Vector.empty
    }
    presence ++ formats ++ formula
  }

  private def statusFields(t: ResearcherAssignment, p: String): Vector[ValidationError] = {
    val supporting = Option(t.supportingDecisionIds).getOrElse(Vector.empty)
    val blocking = blockingDecisions(t)
    when(t.groupSize < 1)(ValidationError.InvalidValue(at(p, "groupSize"), "must be >= 1")) ++
      (t.status match {
        case null => Vector(ValidationError.MissingField(at(p, "status")))
        case AssignmentStatus.Resolved =>
          all(
            when(t.groupSize < 2)(
              ValidationError.InvalidValue(at(p, "groupSize"), "resolved requires a group of >= 2")
            ),
            when(supporting.isEmpty)(
              ValidationError.InvalidValue(
                at(p, "supportingDecisionIds"),
                "resolved requires at least one supporting ACCEPTED decision"
              )
            )
          )
        case AssignmentStatus.Singleton =>
          all(
            when(t.groupSize != 1)(
              ValidationError.InvalidValue(at(p, "groupSize"), "singleton requires groupSize 1")
            ),
            when(supporting.nonEmpty)(
              ValidationError.InvalidValue(
                at(p, "supportingDecisionIds"),
                "a singleton has no supporting decision"
              )
            ),
            when(
              blocking.nonEmpty &&
                !Option(t.anchorNamespace).flatten.exists(T1AnchorNamespaces.contains)
            )(
              ValidationError.InvalidValue(
                at(p, "anchorNamespace"),
                "a singleton with a pending, vetoed or conflict decision is anchored on its own orcid or dblp-pid (decision D1, t1-anchored)"
              )
            )
          )
        case AssignmentStatus.Unresolved =>
          all(
            when(t.groupSize != 1)(
              ValidationError.InvalidValue(at(p, "groupSize"), "unresolved requires groupSize 1")
            ),
            when(supporting.nonEmpty)(
              ValidationError.InvalidValue(
                at(p, "supportingDecisionIds"),
                "an unresolved member has no supporting decision"
              )
            ),
            when(blocking.isEmpty)(
              ValidationError.InvalidValue(
                at(p, "pendingDecisionIds,vetoedDecisionIds,conflictDecisionIds"),
                "unresolved requires at least one pending, vetoed or conflict decision"
              )
            )
          )
      })
  }

  given contract: RecordContract[ResearcherAssignment] =
    new RecordContract[ResearcherAssignment](
      "ResearcherAssignment",
      SchemaVersions.ResearcherAssignment,
      true
    ) {
      def schemaVersionOf(t: ResearcherAssignment): String = t.schemaVersion
      def provenanceOf(t: ResearcherAssignment): Provenance = t.provenance
      protected def fields(t: ResearcherAssignment, p: String): Vector[ValidationError] =
        all(
          memberFields(t, p),
          when(t.identityVersion == null || !IdentityVersionPattern.matches(t.identityVersion))(
            ValidationError.InvalidValue(at(p, "identityVersion"), "must match v[0-9]+")
          ),
          identityFields(t, p),
          statusFields(t, p),
          decisionLists(t)
            .zip(
              Vector(
                t.supportingDecisionIds,
                t.vetoedDecisionIds,
                t.pendingDecisionIds,
                t.conflictDecisionIds,
                t.rejectedDecisionIds
              )
            )
            .flatMap { case ((r, _), raw) => decisionList(at(p, r.field), raw) },
          disjoint(p, decisionLists(t)),
          reasonCodes(at(p, "reasonCodes"), t.reasonCodes),
          runFields(
            p,
            t.memberId,
            t.groupingPolicyVersion,
            t.matchPolicyVersion,
            t.decisionRunId,
            Some(t.assignedAt),
            t.provenance
          )
        )
    }
}

/**
 * The grouping result for one work (schema `work-assignment.v1`). One record per work of the
 * decided curated run; works are never dropped. Produced by the entity-resolution grouping layer;
 * core validates only and never chooses a representative.
 *
 * @param workId
 *   the work
 * @param canonicalWorkId
 *   the canonical work: the work ID of the group's single representative (no new WorkId namespace)
 * @param isRepresentative
 *   true exactly when `workId == canonicalWorkId`. Exactly one representative exists per canonical
 *   work, and the representative's author list is the only author list of the canonical work
 * @param status
 *   `grouped` (groupSize >= 2, supporting decisions present) or `single` (groupSize 1, its own
 *   canonical work, no supporting decision)
 * @param representativeRule
 *   token naming the grouping rule that chose the representative
 * @param groupSize
 *   number of works that share `canonicalWorkId`
 * @param graphEligibility
 *   graph scope ([[GraphEligibility]]): `counted` only on a DBLP representative,
 *   `member-of-counted` only on a non-representative of a DBLP canonical work, `enrichment-only`
 *   only when neither the work nor its canonical work is a DBLP work
 * @param supportingDecisionIds
 *   see [[ResearcherAssignment]]; the same five lists, for work decisions
 * @param provenance
 *   `scholarchain-derived`; `sourceRecordId` = `workId`; `runId` = the grouping run;
 *   `policyVersion` = `groupingPolicyVersion`; no model or prompt
 */
@allowUnknownKeys(false)
final case class WorkAssignment(
    schemaVersion: String,
    workId: String,
    canonicalWorkId: String,
    isRepresentative: Boolean,
    status: WorkGroupStatus,
    representativeRule: String,
    groupSize: Int,
    graphEligibility: GraphEligibility,
    supportingDecisionIds: Vector[String],
    vetoedDecisionIds: Vector[String],
    pendingDecisionIds: Vector[String],
    conflictDecisionIds: Vector[String],
    rejectedDecisionIds: Vector[String],
    reasonCodes: Vector[String],
    groupingPolicyVersion: String,
    matchPolicyVersion: String,
    decisionRunId: String,
    assignedAt: String,
    provenance: Provenance
) derives ReadWriter

object WorkAssignment {

  /** The five decision lists of `t`, in field order (null lists read as empty). */
  def decisionLists(t: WorkAssignment): Vector[(DecisionRole, Vector[String])] =
    Vector(
      DecisionRole.Supporting -> t.supportingDecisionIds,
      DecisionRole.Vetoed -> t.vetoedDecisionIds,
      DecisionRole.Pending -> t.pendingDecisionIds,
      DecisionRole.Conflict -> t.conflictDecisionIds,
      DecisionRole.Rejected -> t.rejectedDecisionIds
    ).map { case (r, ids) => r -> Option(ids).getOrElse(Vector.empty) }

  /** True when `workId` is a DBLP work (the only works that can be counted in the graph). */
  def isDblpWork(workId: String): Boolean = AssignmentRules.isDblp(workId)

  private def groupFields(t: WorkAssignment, p: String): Vector[ValidationError] = {
    val supporting = Option(t.supportingDecisionIds).getOrElse(Vector.empty)
    val sameId = t.workId != null && t.workId == t.canonicalWorkId
    all(
      when(t.isRepresentative != sameId)(
        ValidationError.InvalidValue(
          at(p, "isRepresentative"),
          "true exactly when workId equals canonicalWorkId (the representative maps to itself)"
        )
      ),
      when(t.groupSize < 1)(ValidationError.InvalidValue(at(p, "groupSize"), "must be >= 1")),
      t.status match {
        case null => Vector(ValidationError.MissingField(at(p, "status")))
        case WorkGroupStatus.Single =>
          all(
            when(t.groupSize != 1)(
              ValidationError.InvalidValue(at(p, "groupSize"), "single requires groupSize 1")
            ),
            when(!sameId)(
              ValidationError.InvalidValue(
                at(p, "canonicalWorkId"),
                "a single work is its own canonical work"
              )
            ),
            when(supporting.nonEmpty)(
              ValidationError.InvalidValue(
                at(p, "supportingDecisionIds"),
                "a single work has no supporting decision"
              )
            )
          )
        case WorkGroupStatus.Grouped =>
          all(
            when(t.groupSize < 2)(
              ValidationError.InvalidValue(at(p, "groupSize"), "grouped requires a group of >= 2")
            ),
            when(supporting.isEmpty)(
              ValidationError.InvalidValue(
                at(p, "supportingDecisionIds"),
                "grouped requires at least one supporting ACCEPTED decision"
              )
            )
          )
      }
    )
  }

  private def eligibilityFields(t: WorkAssignment, p: String): Vector[ValidationError] = {
    val field = at(p, "graphEligibility")
    t.graphEligibility match {
      case null => Vector(ValidationError.MissingField(field))
      case GraphEligibility.Counted =>
        all(
          when(!t.isRepresentative)(
            ValidationError.InvalidValue(field, "counted only on the representative")
          ),
          when(!isDblpWork(t.workId))(
            ValidationError.InvalidValue(field, "counted only on a DBLP work (decision E2)")
          )
        )
      case GraphEligibility.MemberOfCounted =>
        all(
          when(t.isRepresentative)(
            ValidationError.InvalidValue(
              field,
              "a representative is counted or enrichment-only, never member-of-counted"
            )
          ),
          when(!isDblpWork(t.canonicalWorkId))(
            ValidationError.InvalidValue(field, "member-of-counted requires a DBLP canonical work")
          )
        )
      case GraphEligibility.EnrichmentOnly =>
        when(isDblpWork(t.workId) || isDblpWork(t.canonicalWorkId))(
          ValidationError.InvalidValue(
            field,
            "a DBLP work, or a member of a DBLP canonical work, is never enrichment-only"
          )
        )
    }
  }

  given contract: RecordContract[WorkAssignment] =
    new RecordContract[WorkAssignment]("WorkAssignment", SchemaVersions.WorkAssignment, true) {
      def schemaVersionOf(t: WorkAssignment): String = t.schemaVersion
      def provenanceOf(t: WorkAssignment): Provenance = t.provenance
      protected def fields(t: WorkAssignment, p: String): Vector[ValidationError] =
        all(
          StableId.check(IdKind.Work, t.workId, at(p, "workId")),
          StableId.check(IdKind.Work, t.canonicalWorkId, at(p, "canonicalWorkId")),
          token(at(p, "representativeRule"), t.representativeRule),
          groupFields(t, p),
          eligibilityFields(t, p),
          decisionLists(t)
            .zip(
              Vector(
                t.supportingDecisionIds,
                t.vetoedDecisionIds,
                t.pendingDecisionIds,
                t.conflictDecisionIds,
                t.rejectedDecisionIds
              )
            )
            .flatMap { case ((r, _), raw) => decisionList(at(p, r.field), raw) },
          disjoint(p, decisionLists(t)),
          reasonCodes(at(p, "reasonCodes"), t.reasonCodes),
          runFields(
            p,
            t.workId,
            t.groupingPolicyVersion,
            t.matchPolicyVersion,
            t.decisionRunId,
            Some(t.assignedAt),
            t.provenance
          )
        )
    }
}
