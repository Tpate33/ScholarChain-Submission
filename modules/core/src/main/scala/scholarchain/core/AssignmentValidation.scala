package scholarchain.core

import Validated.{all, at, when}

/**
 * The author list a source work is expected to have: its author mention IDs in author order
 * (positions 0..n-1). A validator input for [[AssignmentValidation.checkCoverage]], taken from the
 * curated source work, never derived from the assignments under test.
 */
final case class ExpectedAuthors(workId: String, mentionIds: Vector[String])

object ExpectedAuthors {

  /** The author mention IDs of a curated work record, in author order. */
  def of(w: WorkRecord): ExpectedAuthors =
    ExpectedAuthors(w.workId, Option(w.authors).getOrElse(Vector.empty).map(_.mentionId))
}

/**
 * One exact researcher identifier asserted by one researcher member (an author mention or a source
 * author profile) in the curated data. A validator input for
 * [[AssignmentValidation.checkSingletonAnchors]]; `identifier.value` is in the canonical form of
 * its scheme, the form a researcher anchor value uses.
 */
final case class MemberIdentifier(memberId: String, identifier: ExactId)

object MemberIdentifier {

  /**
   * The ORCID iD and DBLP pid a mention asserts (`orcid`; `sourceAuthorId` when its scheme is
   * `dblp-pid`). Other identifiers are not anchors of a t1-anchored singleton and are not listed.
   */
  def fromMention(m: AuthorMention): Vector[MemberIdentifier] =
    Option(m.orcid).flatten.toVector.map(o =>
      MemberIdentifier(m.mentionId, ExactId(ExactIdScheme.Orcid, o))
    ) ++ Option(m.sourceAuthorId).flatten.toVector.collect {
      case e if e != null && e.scheme == ExternalIdScheme.DblpPid =>
        MemberIdentifier(m.mentionId, ExactId(ExactIdScheme.DblpPid, e.value))
    }

  /** The identifier of a researcher identity link, asserted for its entity (mention or profile). */
  def fromLink(l: IdentityLink): Option[MemberIdentifier] =
    Option(l)
      .filter(_.entityKind == MatchEntityKind.Researcher)
      .map(x => MemberIdentifier(x.entityId, x.identifier))
}

/**
 * Pure dataset-level validation of one grouping run (contracts C1-C3). An empty result means the
 * dataset satisfies the contract. Core validates only: it never groups records, never chooses an
 * anchor and never chooses a representative.
 *
 * Preconditions: each dataset is complete (every member, work, team or authorship record of the
 * run), and every record has already been decoded through [[Json.decode]]. The checks that start
 * with the record contracts ([[checkResearchers]], [[checkWorks]], [[checkAuthorships]],
 * [[checkTeams]], [[checkAgainstDecisions]] for its decisions) re-run them with the dataset index
 * in the field path and return typed errors for null datasets and null records; they do not throw.
 * The other checks, and the cross-dataset parts of those above (the `researchers` and `works`
 * arguments of [[checkRun]], [[checkAuthorships]], [[checkTeams]], [[checkCoverage]],
 * [[checkSingletonAnchors]]), skip null records but assume that every non-null record already
 * passed its record contract: a record with a null field there (for example a null `status`) may
 * throw. Run [[checkResearchers]] and [[checkWorks]] first and stop on their errors.
 *
 * Graph scope (Phase 6 decision O9): authorship records exist for every identified mention on every
 * representative work, counted or enrichment-only ([[checkAuthorships]]); publication teams exist
 * only for counted (DBLP-backed, graph-eligible) canonical works ([[checkTeams]]). Graph metrics
 * are computed from counted works only, and Spark must read the canonical teams of the grouping
 * run, never the Hadoop TeamJob teams (which carry no researcher IDs).
 */
object AssignmentValidation {

  private def sortedKeys[A](m: Map[String, A]): Vector[String] =
    m.keys.toVector.sortWith((a, b) => Canonical.compareUtf8(a, b) < 0)

  private def invalid(field: String, reason: String) = ValidationError.InvalidValue(field, reason)

  /** One run: a field must have the same value on every record. */
  private def oneValue(field: String, values: Vector[String]): Vector[ValidationError] = {
    val distinct = values.distinct
    when(distinct.size > 1)(
      invalid(
        field,
        s"one run has one value; found ${distinct.size} (${distinct.take(3).mkString(", ")})"
      )
    )
  }

  private def duplicates(field: String, keys: Vector[String]): Vector[ValidationError] =
    keys.groupBy(identity).toVector.filter(_._2.size > 1).sortBy(_._1).map { case (k, same) =>
      invalid(field, s"$k appears in ${same.size} records; one record per key")
    }

  /** Minimal union-find over record indices (connectivity check only). */
  private final class Components(n: Int) {
    private val parent = Array.tabulate(n)(identity)
    def find(i: Int): Int = {
      var r = i
      while (parent(r) != r) r = parent(r)
      var j = i
      while (parent(j) != r) { val next = parent(j); parent(j) = r; j = next }
      r
    }
    def union(a: Int, b: Int): Unit = parent(find(a)) = find(b)
  }

  /**
   * Decision-reference rules shared by researcher and work assignments: every listed decision ID
   * appears on exactly two records (its two endpoints) in the same role; a supporting decision
   * joins two records of one group; a vetoed, REJECTED or CONFLICT decision never joins two records
   * of one group (an assignment never merges across a negative decision; unresolved records have no
   * group); an UNRESOLVED (pending) decision is not constrained; and every group of two or more
   * records is connected by its supporting decisions.
   *
   * @param groupOf
   *   the group key of each record, or None for a record without a group (unresolved)
   */
  private def decisionReferences(
      label: String,
      lists: Vector[Vector[(DecisionRole, Vector[String])]],
      groupOf: Vector[Option[String]]
  ): Vector[ValidationError] = {
    val refs = lists.zipWithIndex.flatMap { case (ls, i) =>
      ls.flatMap { case (role, ids) => ids.filter(_ != null).map(id => (id, role, i)) }
    }
    val byId = refs.groupBy(_._1)
    val pairErrors = sortedKeys(byId).flatMap { id =>
      val hits = byId(id)
      val roles = hits.map(_._2).distinct
      if (hits.size != 2)
        Vector(
          invalid(
            label,
            s"decision $id is listed on ${hits.size} record(s); a decision touches exactly two"
          )
        )
      else if (roles.size != 1)
        Vector(invalid(label, s"decision $id has different roles on its two endpoints"))
      else {
        val ga = groupOf(hits(0)._3)
        val gb = groupOf(hits(1)._3)
        roles.head match {
          case DecisionRole.Supporting =>
            when(ga.isEmpty || ga != gb)(
              invalid(label, s"supporting decision $id joins records of different groups")
            )
          case DecisionRole.Vetoed =>
            when(ga.isDefined && ga == gb)(
              invalid(label, s"vetoed decision $id joins records that ended in one group")
            )
          case DecisionRole.Rejected | DecisionRole.Conflict =>
            when(ga.isDefined && ga == gb)(
              invalid(
                label,
                s"${roles.head.outcome.tag} decision $id joins records that ended in one group"
              )
            )
          case DecisionRole.Pending => Vector.empty
        }
      }
    }
    val components = new Components(lists.size)
    byId.values.foreach { hits =>
      if (hits.size == 2 && hits.forall(_._2 == DecisionRole.Supporting))
        components.union(hits(0)._3, hits(1)._3)
    }
    val groups = groupOf.zipWithIndex.collect { case (Some(g), i) => g -> i }.groupBy(_._1)
    val connectivity = sortedKeys(groups).flatMap { g =>
      val members = groups(g).map(_._2)
      when(members.size > 1 && members.map(components.find).distinct.size > 1)(
        invalid(label, s"group $g is not connected by its supporting decisions")
      )
    }
    pairErrors ++ connectivity
  }

  /**
   * Researcher assignments of one grouping run (C1): one record per member; one run (policy
   * versions, decision run, identity version, grouping run); per researcher ID one anchor, a group
   * size equal to the number of members, and no two mentions of one work; and the
   * decision-reference rules.
   */
  def checkResearchers(as: Vector[ResearcherAssignment]): Vector[ValidationError] =
    if (as == null || as.exists(_ == null)) Vector(ValidationError.MissingField("researchers"))
    else {
      val records = as.zipWithIndex.flatMap { case (a, i) =>
        ResearcherAssignment.contract.validateAt(a, s"researchers[$i]")
      }
      if (records.nonEmpty) records
      else {
        val byResearcher = as.filter(_.researcherId.isDefined).groupBy(_.researcherId.get)
        val groupErrors = sortedKeys(byResearcher).flatMap { rid =>
          val g = byResearcher(rid)
          val mentionWorks = g.flatMap(_.workId)
          all(
            when(g.map(a => (a.anchorNamespace, a.anchorValue)).distinct.size > 1)(
              invalid("researchers", s"$rid has more than one anchor")
            ),
            when(g.exists(_.groupSize != g.size))(
              invalid("researchers", s"$rid: groupSize must equal its ${g.size} member(s)")
            ),
            when(mentionWorks.distinct.size != mentionWorks.size)(
              invalid("researchers", s"$rid is assigned to two mentions of one work")
            )
          )
        }
        all(
          duplicates("researchers.memberId", as.map(_.memberId)),
          oneValue("researchers.groupingPolicyVersion", as.map(_.groupingPolicyVersion)),
          oneValue("researchers.matchPolicyVersion", as.map(_.matchPolicyVersion)),
          oneValue("researchers.decisionRunId", as.map(_.decisionRunId)),
          oneValue("researchers.identityVersion", as.map(_.identityVersion)),
          oneValue("researchers.provenance.runId", as.map(_.provenance.runId)),
          groupErrors,
          decisionReferences(
            "researchers",
            as.map(ResearcherAssignment.decisionLists),
            as.map(_.researcherId)
          )
        )
      }
    }

  /**
   * Work assignments of one grouping run (C2): one record per work; one run; per canonical work
   * exactly one representative, which is the record of the canonical work itself; a group size
   * equal to the number of members; graph eligibility consistent with the group (a group with a
   * DBLP member has a counted DBLP representative and only member-of-counted copies; any other
   * group is enrichment-only throughout); and the decision-reference rules.
   */
  def checkWorks(ws: Vector[WorkAssignment]): Vector[ValidationError] =
    if (ws == null || ws.exists(_ == null)) Vector(ValidationError.MissingField("works"))
    else {
      val records = ws.zipWithIndex.flatMap { case (w, i) =>
        WorkAssignment.contract.validateAt(w, s"works[$i]")
      }
      if (records.nonEmpty) records
      else {
        val byCanonical = ws.groupBy(_.canonicalWorkId)
        val groupErrors = sortedKeys(byCanonical).flatMap { c =>
          val g = byCanonical(c)
          val reps = g.filter(_.isRepresentative)
          val hasDblp = g.exists(w => WorkAssignment.isDblpWork(w.workId))
          all(
            when(reps.size != 1)(
              invalid("works", s"canonical work $c has ${reps.size} representatives; exactly one")
            ),
            when(g.exists(_.groupSize != g.size))(
              invalid("works", s"$c: groupSize must equal its ${g.size} member(s)")
            ),
            when(hasDblp && !reps.forall(_.graphEligibility == GraphEligibility.Counted))(
              invalid(
                "works",
                s"$c has a DBLP member, so its representative is a counted DBLP work (decision E2)"
              )
            ),
            when(
              !hasDblp && g.exists(_.graphEligibility != GraphEligibility.EnrichmentOnly)
            )(invalid("works", s"$c has no DBLP member, so every member is enrichment-only"))
          )
        }
        all(
          duplicates("works.workId", ws.map(_.workId)),
          oneValue("works.groupingPolicyVersion", ws.map(_.groupingPolicyVersion)),
          oneValue("works.matchPolicyVersion", ws.map(_.matchPolicyVersion)),
          oneValue("works.decisionRunId", ws.map(_.decisionRunId)),
          oneValue("works.provenance.runId", ws.map(_.provenance.runId)),
          groupErrors,
          decisionReferences(
            "works",
            ws.map(WorkAssignment.decisionLists),
            ws.map(w => Some(w.canonicalWorkId))
          )
        )
      }
    }

  /**
   * The researcher and work assignments are one grouping run: the same policy versions, decision
   * run and grouping run, and every mention's work has a work assignment.
   */
  def checkRun(
      researchers: Vector[ResearcherAssignment],
      works: Vector[WorkAssignment]
  ): Vector[ValidationError] = {
    val rs = Option(researchers).getOrElse(Vector.empty).filter(_ != null)
    val ws = Option(works).getOrElse(Vector.empty).filter(_ != null)
    val workIds = ws.map(_.workId).toSet
    all(
      oneValue(
        "run.groupingPolicyVersion",
        rs.map(_.groupingPolicyVersion) ++ ws.map(_.groupingPolicyVersion)
      ),
      oneValue(
        "run.matchPolicyVersion",
        rs.map(_.matchPolicyVersion) ++ ws.map(_.matchPolicyVersion)
      ),
      oneValue("run.decisionRunId", rs.map(_.decisionRunId) ++ ws.map(_.decisionRunId)),
      oneValue(
        "run.provenance.runId",
        rs.flatMap(r => Option(r.provenance).map(_.runId)) ++
          ws.flatMap(w => Option(w.provenance).map(_.runId))
      ),
      rs.flatMap(_.workId).distinct.sorted.filterNot(workIds.contains).map { w =>
        invalid("researchers.workId", s"$w has mentions but no work assignment")
      }
    )
  }

  /**
   * The assignments preserve the match decisions they rest on: every decision of the decision run
   * appears on both of its endpoints, in the list of its outcome, and every listed decision ID
   * resolves to a decision of that outcome that touches the record. The decisions belong to the
   * assignments' decision run and match policy.
   */
  def checkAgainstDecisions(
      researchers: Vector[ResearcherAssignment],
      works: Vector[WorkAssignment],
      decisions: Vector[MatchDecision]
  ): Vector[ValidationError] = {
    val rs = Option(researchers).getOrElse(Vector.empty).filter(_ != null)
    val ws = Option(works).getOrElse(Vector.empty).filter(_ != null)
    val ds = Option(decisions).getOrElse(Vector.empty)
    if (decisions == null || ds.exists(_ == null)) Vector(ValidationError.MissingField("decisions"))
    else {
      val decisionErrors = ds.zipWithIndex.flatMap { case (d, i) =>
        MatchDecision.contract.validateAt(d, s"decisions[$i]")
      }
      if (decisionErrors.nonEmpty) decisionErrors
      else {
        val byId = ds.groupBy(_.decisionId)
        val runIds = (rs.map(_.decisionRunId) ++ ws.map(_.decisionRunId)).distinct
        val policies = (rs.map(_.matchPolicyVersion) ++ ws.map(_.matchPolicyVersion)).distinct
        // Per record: decision ID -> the roles it is listed in (one hash lookup per endpoint).
        def roleIndex(
            ls: Vector[(DecisionRole, Vector[String])]
        ): Map[String, Vector[DecisionRole]] =
          ls.flatMap { case (r, ids) => ids.filter(_ != null).map(_ -> r) }.groupMap(_._1)(_._2)
        val members: Map[String, Map[String, Vector[DecisionRole]]] =
          rs.map(r => r.memberId -> roleIndex(ResearcherAssignment.decisionLists(r))).toMap
        val workLists: Map[String, Map[String, Vector[DecisionRole]]] =
          ws.map(w => w.workId -> roleIndex(WorkAssignment.decisionLists(w))).toMap
        def listsOf(kind: MatchEntityKind) =
          if (kind == MatchEntityKind.Researcher) members else workLists

        val perDecision = ds.flatMap { d =>
          val f = s"decisions[${d.decisionId}]"
          val lists = listsOf(d.entityKind)
          all(
            when(
              d.entityKind != MatchEntityKind.Researcher && d.entityKind != MatchEntityKind.Work
            )(
              invalid(f, s"grouping covers researcher and work decisions, not ${d.entityKind.tag}")
            ),
            when(!runIds.forall(_ == d.provenance.runId))(
              invalid(f, "belongs to another decision run than the assignments")
            ),
            when(!policies.forall(p => d.provenance.policyVersion.contains(p)))(
              invalid(f, "has another match policy version than the assignments")
            ),
            Vector(d.sourceId, d.targetId).flatMap { e =>
              lists.get(e) match {
                case None => Vector(invalid(f, s"endpoint $e has no assignment"))
                case Some(index) =>
                  val roles = index.getOrElse(d.decisionId, Vector.empty).distinct
                  when(roles.size != 1 || roles.head.outcome != d.decision)(
                    invalid(
                      f,
                      s"a ${d.decision.tag} decision must be listed once on $e, in a list of that outcome"
                    )
                  )
              }
            }
          )
        }
        def perRecord(
            label: String,
            key: String,
            ls: Vector[(DecisionRole, Vector[String])],
            kind: MatchEntityKind
        ) =
          ls.flatMap { case (role, ids) =>
            ids.flatMap { id =>
              byId.get(id).flatMap(_.headOption) match {
                case None =>
                  Vector(invalid(s"$label[$key].${role.field}", s"$id resolves to no decision"))
                case Some(d) =>
                  all(
                    when(d.entityKind != kind)(
                      invalid(
                        s"$label[$key].${role.field}",
                        s"$id is a ${d.entityKind.tag} decision"
                      )
                    ),
                    when(d.sourceId != key && d.targetId != key)(
                      invalid(s"$label[$key].${role.field}", s"$id does not touch $key")
                    ),
                    when(d.decision != role.outcome)(
                      invalid(
                        s"$label[$key].${role.field}",
                        s"$id is ${d.decision.tag}, not ${role.outcome.tag}"
                      )
                    )
                  )
              }
            }
          }
        all(
          duplicates("decisions.decisionId", ds.map(_.decisionId)),
          perDecision,
          rs.flatMap(r =>
            perRecord(
              "researchers",
              r.memberId,
              ResearcherAssignment.decisionLists(r),
              MatchEntityKind.Researcher
            )
          ),
          ws.flatMap(w =>
            perRecord("works", w.workId, WorkAssignment.decisionLists(w), MatchEntityKind.Work)
          )
        )
      }
    }
  }

  /**
   * Authorship v2 records of one grouping run (C3): one record per identified mention on a
   * representative work and no other record (one author list per canonical work, from the
   * representative only), each equal to its mention's assignment (researcher, status, position,
   * supporting decisions, policy versions, decision run).
   *
   * Scope (decision O9): canonical authorship records are kept for every representative work,
   * counted or enrichment-only. They are not graph input on their own: graph metrics use counted
   * works only (select `graphEligibility == counted` on the work assignment, or read the teams of
   * [[checkTeams]]). That every author of the source work has an assignment is checked by
   * [[checkCoverage]], not here.
   */
  def checkAuthorships(
      authorships: Vector[AuthorshipRecordV2],
      researchers: Vector[ResearcherAssignment],
      works: Vector[WorkAssignment]
  ): Vector[ValidationError] =
    if (authorships == null || authorships.exists(_ == null))
      Vector(ValidationError.MissingField("authorships"))
    else {
      val records = authorships.zipWithIndex.flatMap { case (a, i) =>
        AuthorshipRecordV2.contract.validateAt(a, s"authorships[$i]")
      }
      if (records.nonEmpty) records
      else {
        val rs = Option(researchers).getOrElse(Vector.empty).filter(_ != null)
        val byMember = rs.map(r => r.memberId -> r).toMap
        val workById =
          Option(works).getOrElse(Vector.empty).filter(_ != null).map(w => w.workId -> w).toMap
        val byMention = authorships.map(a => a.mentionId -> a).toMap
        val perRecord = authorships.flatMap { a =>
          val f = s"authorships[${a.mentionId}]"
          byMember.get(a.mentionId) match {
            case None => Vector(invalid(f, "the mention has no researcher assignment"))
            case Some(r) =>
              all(
                when(!r.status.identified)(
                  invalid(f, "the mention is unresolved: no authorship record")
                ),
                when(r.researcherId != Some(a.researcherId))(
                  invalid(f, "researcherId differs from the assignment")
                ),
                when(r.status != a.assignmentStatus)(
                  invalid(f, "assignmentStatus differs from the assignment")
                ),
                when(r.workId != Some(a.workId) || r.position != Some(a.position))(
                  invalid(f, "workId or position differs from the assignment")
                ),
                when(r.supportingDecisionIds != a.supportingDecisionIds)(
                  invalid(f, "supportingDecisionIds differ from the assignment")
                ),
                when(
                  r.groupingPolicyVersion != a.groupingPolicyVersion ||
                    r.matchPolicyVersion != a.matchPolicyVersion || r.decisionRunId != a.decisionRunId
                )(invalid(f, "policy versions or decision run differ from the assignment")),
                workById.get(a.workId) match {
                  case None => Vector(invalid(f, s"${a.workId} has no work assignment"))
                  case Some(w) =>
                    when(!w.isRepresentative)(
                      invalid(
                        f,
                        s"${a.workId} is not a representative: the author list of a canonical work comes from its representative only"
                      )
                    )
                }
              )
          }
        }
        val missing = rs
          .filter(r =>
            r.status.identified && r.workId.exists(w => workById.get(w).exists(_.isRepresentative))
          )
          .filterNot(r => byMention.contains(r.memberId))
          .map(r =>
            invalid(
              "authorships",
              s"identified mention ${r.memberId} on a representative work has no authorship record"
            )
          )
        all(duplicates("authorships.mentionId", authorships.map(_.mentionId)), perRecord, missing)
      }
    }

  /**
   * The match confidence of each resolved authorship is the lowest confidence of its supporting
   * decisions.
   */
  def checkAuthorshipConfidence(
      authorships: Vector[AuthorshipRecordV2],
      decisions: Vector[MatchDecision]
  ): Vector[ValidationError] = {
    val byId =
      Option(decisions).getOrElse(Vector.empty).filter(_ != null).map(d => d.decisionId -> d).toMap
    Option(authorships).getOrElse(Vector.empty).filter(_ != null).flatMap { a =>
      val f = s"authorships[${a.mentionId}].matchConfidence"
      val supporting = Option(a.supportingDecisionIds).getOrElse(Vector.empty)
      val found = supporting.flatMap(byId.get)
      if (supporting.isEmpty) Vector.empty
      else if (found.size != supporting.size || found.exists(_.decision != MatchOutcome.Accepted))
        Vector(invalid(f, "every supporting decision must resolve to an ACCEPTED decision"))
      else
        when(a.matchConfidence != Some(found.map(_.confidence).min))(
          invalid(f, s"expected ${found.map(_.confidence).min} (the lowest supporting confidence)")
        )
    }
  }

  /**
   * Publication teams of one grouping run: one team per counted canonical work that has mentions,
   * and no team for any other work. The team of a counted work comes from its representative's
   * mentions only: `researcherIds` are the identified mentions' researchers in author order,
   * `unresolvedMentionIds` the unresolved mentions in author order, and `teamSize` the number of
   * mentions (unresolved included).
   *
   * Scope (decision O9): teams exist only for DBLP-backed, graph-eligible (counted) canonical
   * works, and graph metrics are computed from these teams only. Spark must use the canonical teams
   * of the grouping run, never the Hadoop TeamJob teams. The mentions compared here are the
   * assignments themselves; that they are every author of the source work is checked by
   * [[checkCoverage]], so run both.
   */
  def checkTeams(
      teams: Vector[PublicationTeam],
      researchers: Vector[ResearcherAssignment],
      works: Vector[WorkAssignment]
  ): Vector[ValidationError] =
    if (teams == null || teams.exists(_ == null)) Vector(ValidationError.MissingField("teams"))
    else {
      val records = teams.zipWithIndex.flatMap { case (t, i) =>
        PublicationTeam.contract.validateAt(t, s"teams[$i]")
      }
      if (records.nonEmpty) records
      else {
        val ws = Option(works).getOrElse(Vector.empty).filter(_ != null)
        val counted = ws.filter(_.graphEligibility == GraphEligibility.Counted).map(_.workId).toSet
        val mentionsOf = Option(researchers)
          .getOrElse(Vector.empty)
          .filter(r => r != null && r.workId.isDefined)
          .groupBy(_.workId.get)
          .view
          .mapValues(_.sortBy(_.position.getOrElse(Int.MaxValue)))
          .toMap
        val teamOf = teams.map(t => t.workId -> t).toMap
        val perTeam = teams.flatMap { t =>
          val f = s"teams[${t.workId}]"
          if (!counted.contains(t.workId))
            Vector(
              invalid(
                f,
                "a team exists only for a counted canonical work (its DBLP representative)"
              )
            )
          else {
            val ms = mentionsOf.getOrElse(t.workId, Vector.empty)
            val identified = ms.filter(_.status.identified).flatMap(_.researcherId)
            val unresolved = ms.filterNot(_.status.identified).map(_.memberId)
            all(
              when(t.researcherIds != identified)(
                invalid(
                  f,
                  "researcherIds must be the representative's identified mentions, in author order"
                )
              ),
              when(t.unresolvedMentionIds != unresolved)(
                invalid(
                  f,
                  "unresolvedMentionIds must be the representative's unresolved mentions, in author order"
                )
              ),
              when(t.teamSize != ms.size)(
                invalid(f, s"teamSize must equal the representative's ${ms.size} mention(s)")
              )
            )
          }
        }
        val missing = counted.toVector.sorted
          .filter(w => mentionsOf.get(w).exists(_.nonEmpty))
          .filterNot(teamOf.contains)
          .map(w => invalid("teams", s"counted work $w has mentions but no team"))
        all(duplicates("teams.workId", teams.map(_.workId)), perTeam, missing)
      }
    }

  /**
   * Coverage of the source works' authors: every author mention of a work has a researcher
   * assignment (at most one per member is checked by [[checkResearchers]]), and no other mention is
   * assigned to that work. The expected author lists come from the curated source works
   * ([[ExpectedAuthors.of]]), never from the assignments, so a dropped final author is detected as
   * well as a gap in the middle.
   *
   * Rules:
   *   - each expected list belongs to a work with a work assignment, at most one list per work, and
   *     its i-th mention ID is [[MentionId.of]](workId, i) (positions 0..n-1);
   *   - every representative work has an expected list (its authors are the canonical work's author
   *     list, so its coverage must be checkable); a list for a non-representative work is checked
   *     too;
   *   - for each listed work, the mention assignments with that `workId` are exactly the expected
   *     mention IDs.
   *
   * A null `expected`, null entry or null `mentionIds` gives `MissingField("expected")`; a null
   * `workId` gives `MissingField("expected[i].workId")` for each such entry; a malformed `workId`
   * gives the [[StableId.check]] error. None of them throws.
   *
   * Cost: linear in the number of records and expected mentions (hash lookups only).
   */
  def checkCoverage(
      researchers: Vector[ResearcherAssignment],
      works: Vector[WorkAssignment],
      expected: Vector[ExpectedAuthors]
  ): Vector[ValidationError] =
    if (expected == null || expected.exists(e => e == null || e.mentionIds == null))
      Vector(ValidationError.MissingField("expected"))
    else if (expected.exists(_.workId == null))
      // Refused before any sorting or keying, which would compare the null (CR-P6-04 n2).
      expected.zipWithIndex.collect {
        case (e, i) if e.workId == null => ValidationError.MissingField(s"expected[$i].workId")
      }
    else {
      val rs = Option(researchers).getOrElse(Vector.empty).filter(_ != null)
      val ws = Option(works).getOrElse(Vector.empty).filter(_ != null)
      val workIds = ws.map(_.workId).toSet
      val shape = expected.flatMap { e =>
        val f = s"expected[${e.workId}]"
        val id = StableId.check(IdKind.Work, e.workId, at(f, "workId"))
        if (id.nonEmpty) id
        else
          e.mentionIds.zipWithIndex.flatMap { case (m, i) =>
            MentionId.of(e.workId, i) match {
              case Right(x) =>
                when(x != m)(
                  invalid(at(f, s"mentionIds[$i]"), s"is not the mention at position $i")
                )
              case Left(err) => Vector(err)
            }
          }
      }
      val byWork = expected.map(e => e.workId -> e).toMap
      val unknown = expected.map(_.workId).distinct.sorted.filterNot(workIds.contains).map { w =>
        invalid("expected", s"$w has no work assignment")
      }
      val unlisted =
        ws.filter(_.isRepresentative).map(_.workId).distinct.sorted.filterNot(byWork.contains).map {
          w => invalid("works", s"representative $w has no expected author list")
        }
      val assigned: Map[String, Vector[String]] =
        rs.filter(r => Option(r.workId).flatten.isDefined).groupMap(_.workId.get)(_.memberId)
      val coverage = sortedKeys(byWork).flatMap { w =>
        val exp = byWork(w).mentionIds
        val expSet = exp.toSet
        val got = assigned.getOrElse(w, Vector.empty)
        val gotSet = got.toSet
        val missing = exp.indices.filterNot(i => gotSet.contains(exp(i)))
        val extra = got.filterNot(expSet.contains).distinct.sorted
        all(
          when(missing.nonEmpty)(
            invalid(
              s"researchers[$w]",
              s"author position(s) ${missing.take(5).mkString(",")} of ${exp.size} have no researcher assignment"
            )
          ),
          when(extra.nonEmpty)(
            invalid(
              s"researchers[$w]",
              s"${extra.size} assigned mention(s) are not authors of the source work: ${extra.take(3).mkString(", ")}"
            )
          )
        )
      }
      all(shape, duplicates("expected.workId", expected.map(_.workId)), unknown, unlisted, coverage)
    }

  /**
   * The anchor of a singleton that carries a pending, vetoed or conflict decision (decision D1,
   * `t1-anchored`): it is the member's own, non-colliding ORCID iD or DBLP pid.
   *
   * Checked here from the supplied data:
   *   - the anchor namespace is `orcid` or `dblp-pid` (also refused by the record contract);
   *   - the member itself asserts the anchor value in `identifiers` (scheme tag = anchor namespace,
   *     value = anchor value, compared as given);
   *   - no other member asserts that value in `identifiers`, whatever its group or status (an
   *     unresolved member counts as a group of its own);
   *   - no other member record of `researchers` uses that anchor.
   *
   * Not checkable by core; the producer (entity-resolution grouping) must ensure it:
   *   - that `identifiers` holds every ORCID iD and DBLP pid asserted in the decided curated run
   *     (mention `orcid` and DBLP `sourceAuthorId`, profile iDs and pids, researcher identity
   *     links) and nothing else: core cannot see the curated data;
   *   - that the values are canonical (the same canonical form as the anchor value);
   *   - that the value collides with no member outside the supplied sets (other runs, other
   *     splits);
   *   - that the member's status is correct, that is, that no ACCEPTED link of the member was
   *     dropped (the decision lists themselves are checked by [[checkAgainstDecisions]]).
   */
  def checkSingletonAnchors(
      researchers: Vector[ResearcherAssignment],
      identifiers: Vector[MemberIdentifier]
  ): Vector[ValidationError] =
    if (researchers == null || researchers.exists(_ == null))
      Vector(ValidationError.MissingField("researchers"))
    else if (
      identifiers == null || identifiers.exists(i =>
        i == null || i.memberId == null || i.identifier == null || i.identifier.scheme == null
      )
    ) Vector(ValidationError.MissingField("identifiers"))
    else {
      val namespaces = ResearcherAssignment.T1AnchorNamespaces
      val assertedBy: Map[(String, String), Set[String]] = identifiers
        .filter(i => namespaces.contains(i.identifier.scheme.tag))
        .groupMap(i => (i.identifier.scheme.tag, i.identifier.value))(_.memberId)
        .view
        .mapValues(_.toSet)
        .toMap
      def anchorOf(r: ResearcherAssignment): Option[(String, String)] =
        for {
          n <- Option(r.anchorNamespace).flatten
          v <- Option(r.anchorValue).flatten
        } yield (n, v)
      val anchoredBy: Map[(String, String), Vector[String]] =
        researchers.flatMap(r => anchorOf(r).map(_ -> r.memberId)).groupMap(_._1)(_._2)
      researchers
        .filter(ResearcherAssignment.requiresT1Anchor)
        .sortWith((a, b) => Canonical.compareUtf8(a.memberId, b.memberId) < 0)
        .flatMap { r =>
          val f = s"researchers[${r.memberId}]"
          anchorOf(r) match {
            case Some(key @ (n, v)) if namespaces.contains(n) =>
              val asserters = assertedBy.getOrElse(key, Set.empty)
              val others = (asserters - r.memberId).toVector.sorted
              val sharing = anchoredBy.getOrElse(key, Vector.empty).filter(_ != r.memberId).sorted
              all(
                when(!asserters.contains(r.memberId))(
                  invalid(f, s"anchor $n $v is not asserted by the member itself")
                ),
                when(others.nonEmpty)(
                  invalid(
                    f,
                    s"anchor $n $v collides: also asserted by ${others.take(3).mkString(", ")}"
                  )
                ),
                when(sharing.nonEmpty)(
                  invalid(
                    f,
                    s"anchor $n $v collides: also the anchor of ${sharing.take(3).mkString(", ")}"
                  )
                )
              )
            case _ =>
              Vector(
                invalid(
                  f,
                  "a singleton with a pending, vetoed or conflict decision is anchored on its own orcid or dblp-pid"
                )
              )
          }
        }
    }
}
