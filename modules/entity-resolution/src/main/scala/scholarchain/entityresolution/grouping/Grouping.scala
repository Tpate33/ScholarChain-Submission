package scholarchain.entityresolution.grouping

import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale

import scholarchain.core.{
  AssignmentStatus,
  AssignmentValidation,
  AuthorshipRecordV2,
  Canonical,
  DecisionRole,
  ExactId,
  ExactIdScheme,
  ExpectedAuthors,
  ExternalIdScheme,
  GraphEligibility,
  MatchCandidate,
  MatchDecision,
  MatchEntityKind,
  MatchOutcome,
  MemberIdentifier,
  Provenance,
  PublicationTeam,
  ResearcherAssignment,
  ResearcherId,
  RunId,
  RunManifest,
  SchemaVersions,
  SourceAuthorProfile,
  SourceType,
  ValidationError,
  WorkAssignment,
  WorkGroupStatus,
  WorkRecord
}
import scholarchain.core.config.GroupingRunSettings
import scholarchain.entityresolution.{BlockingKeys, MatchFeatures, PersonName, ResearcherEndpoint}
import scholarchain.entityresolution.decisions.DecisionExport
import scholarchain.entityresolution.identifiers.{ExactIdentifiers, IdAgreement}
import scholarchain.entityresolution.policy.{MatchPolicy, PublicationStage, StageEvidence}

/** What the merge did with one ACCEPTED decision (a link). */
enum LinkOutcome(val tag: String) {

  /** The link joined two groups. */
  case Merged extends LinkOutcome("merged")

  /** Both ends were already in one group; kept as a supporting link. */
  case Redundant extends LinkOutcome("redundant")

  /** A veto rule fired; the groups stay apart. */
  case Vetoed extends LinkOutcome("vetoed")
}

/**
 * The merge result of one link, with every veto reason code that fired (empty unless vetoed).
 */
final case class LinkResult(
    kind: MatchEntityKind,
    decisionId: String,
    sourceId: String,
    targetId: String,
    outcome: LinkOutcome,
    reasonCodes: Vector[String]
)

/**
 * Everything one grouping run reads. Every input is already verified by its reader: the decision
 * run by [[DecisionExport.readPublished]] (its `_parts.json`), the curated run by the curated-run
 * reader, the configuration by core.
 *
 * @param manifest
 *   the grouping run manifest (stage [[Grouping.Stage]])
 * @param configDigest
 *   [[GroupingRunSettings.configDigest]] of the resolved configuration
 * @param settings
 *   [[GroupingRunSettings.from]] of the resolved configuration
 * @param matchPolicy
 *   the effective match policy (its identifier rules and preprint DOI prefixes are mirrored)
 * @param decisionManifest
 *   the verified decision run's manifest
 * @param decisions
 *   every decision of the verified decision run
 * @param curatedManifest
 *   the manifest of the curated run that the decision run decided
 * @param works
 *   the curated run's works; each work and each of its mentions must carry the curated run's ID
 *   (CR-P6-07)
 * @param profiles
 *   the curated run's source author profiles; each must carry the curated run's ID
 * @param candidates
 *   the candidates of that curated run (the decisions must decide exactly these; each must carry
 *   the curated run's ID)
 */
final case class GroupingInputs(
    manifest: RunManifest,
    configDigest: String,
    settings: GroupingRunSettings,
    policy: GroupingPolicy,
    matchPolicy: MatchPolicy,
    decisionManifest: RunManifest,
    decisions: Vector[MatchDecision],
    curatedManifest: RunManifest,
    works: Vector[WorkRecord],
    profiles: Vector[SourceAuthorProfile],
    candidates: Vector[CuratedCandidate]
)

/**
 * What grouping reads of one curated candidate: its ID (the decisions must decide exactly the
 * curated candidates) and its provenance (it must belong to the curated run; CR-P6-07).
 */
final case class CuratedCandidate(candidateId: String, provenance: Provenance)

object CuratedCandidate {
  def of(c: MatchCandidate): CuratedCandidate = CuratedCandidate(c.candidateId, c.provenance)
}

/**
 * One complete, validated grouping run, ready to publish. Every record list is sorted by its key
 * (UTF-8 bytes): researchers by member ID, works by work ID, authorships by mention ID, teams by
 * work ID.
 *
 * @param links
 *   the merge result of every ACCEPTED decision, in merge order
 * @param diagnostics
 *   descriptive counts, every line starting with `unevaluated`; no label is read and no quality is
 *   claimed
 */
final case class GroupingBatch(
    manifest: RunManifest,
    runId: String,
    researchers: Vector[ResearcherAssignment],
    works: Vector[WorkAssignment],
    authorships: Vector[AuthorshipRecordV2],
    teams: Vector[PublicationTeam],
    links: Vector[LinkResult],
    diagnostics: Vector[String]
)

/**
 * The grouping layer `er-grouping.v1` (ER-P6-05; decision package sections B-E as refined by the
 * approved draft O1-O11). Pure: no I/O, no clock, no randomness, no model call. It never changes a
 * decision or a source record.
 *
 * '''Merge (B1, B2).''' Researchers (author mentions and source author profiles) and works are
 * grouped separately. Every member starts as a group of one, and only ACCEPTED decisions are links.
 * Links are processed in the order of [[GroupingPolicy.mergeOrder]]: match path rank, confidence
 * descending, then `sourceId` and `targetId` by UTF-8 bytes (a pair appears at most once per kind,
 * refused otherwise, so the order is total and uses no run ID, decision ID, file order or clock). A
 * link whose ends are already in one group is a redundant supporting link. Otherwise every veto
 * rule is evaluated (O11) and every rule that fires is recorded; the groups are merged only when
 * none fires.
 *
 * '''Vetoes (B3, O1-O8).''' Every rule except N1 compares cross pairs (x in one group, y in the
 * other) with each member's own identifiers ([[MatchFeatures.researcherExactIds]],
 * [[MatchFeatures.workExactIds]], the functions the pairwise decisions used) and names, so each
 * rule has the form "some cross pair has property P" and is monotone: once a pair of groups is
 * vetoed, every later merge that would join supersets of them is vetoed too, so vetoed endpoints
 * stay apart.
 *   - N1: a REJECTED or CONFLICT decision between a cross pair (UNRESOLVED never vetoes, O7).
 *   - T1: a cross pair with a T1 identifier disagreement (researcher orcid, dblp-pid; work doi,
 *     arxiv by base submission ID).
 *   - BD: a cross pair with a blocking disagreement (researcher openalex-author, s2-author; work
 *     dblp-key, scopus-eid) and no accepting T1 agreement of its own (O2, O3: the exact step-6a
 *     override of er-policy.v8).
 *   - SW: two mentions of one work.
 *   - HN: two DBLP-sourced names with equal base names and different homonym numbers (O4).
 *   - ST: a pure preprint against a pure published record (O5; a backstop er-policy.v8 cannot
 *     trigger).
 *
 * The scheme lists come from the resource and must equal the match policy's T1 and blocking schemes
 * ([[GroupingPolicy.checkAgainst]]).
 *
 * '''Endpoint self-check.''' No veto may fire on the two endpoints of an ACCEPTED decision alone;
 * otherwise the curated inputs are not the ones decided, and the run is refused with
 * [[GroupingPolicy.EndpointCheckCode]].
 *
 * '''Researcher IDs (C1-C3, O6).''' An identifier value is collided when members of more than one
 * final group assert it, unresolved members included. In anchor order (orcid, dblp-pid,
 * openalex-author) a group takes the first scheme of which it holds exactly one distinct
 * non-collided value; otherwise its smallest member ID (namespace `member`). Statuses follow D1
 * ([[GroupingPolicy.statusRules]]).
 *
 * '''Works (E1, E2, O9).''' The representative is the member of the first namespace in
 * representative order (DBLP first), smallest work ID by bytes; the canonical work ID is its own. A
 * group with a DBLP member is counted (representative) and member-of-counted (copies); every other
 * work is enrichment-only. Authorship records exist for every identified mention on every
 * representative; teams only for counted works with mentions, from the representative's mentions.
 *
 * '''Fail closed.''' Before a batch is returned, every core validator of the run must pass
 * (researchers, works, run, decisions, authorships, confidence, teams, coverage of every curated
 * work, singleton anchors with every ORCID iD and DBLP pid of the curated run), together with the
 * producer-side checks core leaves to this layer ([[producerChecks]]). Any problem refuses the run.
 */
object Grouping {

  /** Stage token of a grouping run manifest. */
  val Stage: String = "er.grouping"

  /** Parameter keys of a grouping run manifest (exactly these). */
  object Params {
    val AssignedAt = "assignedAt"
    val DecisionRunId = "decisionRunId"
    val GroupingPolicyVersion = "groupingPolicyVersion"
    val IdentityVersion = "identityVersion"
    val InputRunId = "inputRunId"
    val MatchPolicyVersion = "matchPolicyVersion"
    val All: Vector[String] = Vector(
      AssignedAt,
      DecisionRunId,
      GroupingPolicyVersion,
      IdentityVersion,
      InputRunId,
      MatchPolicyVersion
    )
  }

  private type Veto = (Vector[Int], Vector[Int], Int => Boolean) => Vector[String]

  private def byUtf8(a: String, b: String): Boolean = Canonical.compareUtf8(a, b) < 0
  private def sortedDistinct(v: Iterable[String]): Vector[String] =
    v.toVector.distinct.sortWith(byUtf8)
  private def when(c: Boolean)(code: => String): Vector[String] =
    if (c) Vector(code) else Vector.empty

  /** Why `s` is not an ISO-8601 UTC instant, or None. */
  def instantProblem(s: String): Option[String] =
    if (s == null || s.isEmpty) Some(s"parameter ${Params.AssignedAt} is empty")
    else
      try {
        Instant.parse(s)
        Option.when(!s.endsWith("Z"))(s"parameter ${Params.AssignedAt} must be a UTC instant (Z)")
      } catch {
        case _: DateTimeParseException =>
          Some(s"parameter ${Params.AssignedAt} '$s' is not an ISO-8601 instant")
      }

  // ---------------------------------------------------------------- manifest

  /**
   * Every reason the grouping run manifest does not fit its verified inputs and the configuration
   * (empty when it fits): stage, config digest, the decision run's input snapshots, the exact
   * parameter set, both verified input run IDs (the decision run and the curated run it decided),
   * the grouping, match policy and identity versions against the configuration, the resource, the
   * decision run and the effective match policy, the decision run's feature and blocking versions
   * against the effective match policy and the code, `assignedAt`, and the curated run ID of every
   * curated record ([[provenanceProblems]]).
   */
  def manifestProblems(in: GroupingInputs): Vector[String] = {
    val m = in.manifest
    val own = RunId.of(m).left.toOption.map(e => s"grouping run manifest: ${e.message}").toVector
    val dec = RunId.of(in.decisionManifest)
    val cur = RunId.of(in.curatedManifest)
    val inErr = dec.left.toOption.map(e => s"decision run manifest: ${e.message}").toVector ++
      cur.left.toOption.map(e => s"curated run manifest: ${e.message}").toVector
    if (own.nonEmpty || inErr.nonEmpty) own ++ inErr
    else {
      val decisionRunId = dec.toOption.get
      val curatedRunId = cur.toOption.get
      val s = in.settings
      val params = m.parameters
      val decided = in.decisionManifest.parameters
      def param(k: String, expected: String, what: String): Vector[String] =
        params.get(k) match {
          case Some(v) if v == expected => Vector.empty
          case Some(v) => Vector(s"parameter $k is $v, but $what is $expected")
          case None => Vector.empty // reported as missing below
        }
      Vector(
        Option.when(m.stage != Stage)(s"manifest stage is '${m.stage}', expected '$Stage'"),
        Option.when(m.configDigest != in.configDigest)(
          s"manifest configDigest ${m.configDigest} differs from the grouping digest " +
            s"${in.configDigest} of the resolved configuration"
        ),
        Option.when(in.decisionManifest.stage != DecisionExport.Stage)(
          s"the decision run's stage is '${in.decisionManifest.stage}', expected " +
            s"'${DecisionExport.Stage}'"
        ),
        Option.when(m.inputSnapshots.toSet != in.decisionManifest.inputSnapshots.toSet)(
          "manifest inputSnapshots " +
            m.inputSnapshots.map(x => s"${x.source.tag}:${x.snapshot}").sorted.mkString(",") +
            " differ from the decision run's " +
            in.decisionManifest.inputSnapshots
              .map(x => s"${x.source.tag}:${x.snapshot}")
              .sorted
              .mkString(",")
        ),
        Option.when(!decided.get(DecisionExport.Params.InputRunId).contains(curatedRunId))(
          s"the decision run decided curated run " +
            s"${decided.getOrElse(DecisionExport.Params.InputRunId, "(none)")}, not the curated " +
            s"run $curatedRunId"
        ),
        Option.when(s.groupingPolicyVersion != in.policy.groupingPolicyVersion)(
          s"configured entityResolution.groupingPolicyVersion ${s.groupingPolicyVersion} differs " +
            s"from the grouping policy resource ${in.policy.groupingPolicyVersion}"
        ),
        Option.when(
          !decided.get(DecisionExport.Params.PolicyVersion).contains(s.matchPolicyVersion)
        )(
          s"the decision run's policyVersion " +
            s"${decided.getOrElse(DecisionExport.Params.PolicyVersion, "(none)")} differs from the " +
            s"configured entityResolution.policyVersion ${s.matchPolicyVersion}"
        ),
        Option.when(in.matchPolicy.policyVersion != s.matchPolicyVersion)(
          s"the effective match policy ${in.matchPolicy.policyVersion} differs from the configured " +
            s"entityResolution.policyVersion ${s.matchPolicyVersion}"
        ),
        // CR-P6-07 nit 2: the vetoes reuse the feature and blocking functions, so the decision
        // run's versions must be the effective policy's and the code's.
        decidedVersion(
          decided,
          DecisionExport.Params.FeatureVersion,
          in.matchPolicy.featureVersion,
          MatchFeatures.Version,
          "feature functions"
        ),
        decidedVersion(
          decided,
          DecisionExport.Params.BlockingVersion,
          in.matchPolicy.blockingVersion,
          BlockingKeys.Version,
          "blocking key formulas"
        )
      ).flatten ++
        params.keys.filterNot(Params.All.contains).map(k => s"unknown parameter '$k'") ++
        Params.All.filterNot(params.contains).map(k => s"parameter '$k' is required") ++
        param(Params.DecisionRunId, decisionRunId, "the verified decision run's ID") ++
        param(Params.InputRunId, curatedRunId, "the curated run's ID") ++
        param(
          Params.GroupingPolicyVersion,
          s.groupingPolicyVersion,
          "the configured entityResolution.groupingPolicyVersion"
        ) ++
        param(
          Params.MatchPolicyVersion,
          s.matchPolicyVersion,
          "the configured entityResolution.policyVersion"
        ) ++
        param(Params.IdentityVersion, s.identityVersion, "the configured graph.identityVersion") ++
        params.get(Params.AssignedAt).flatMap(instantProblem).toVector ++
        GroupingPolicy.checkAgainst(in.policy, in.matchPolicy) ++
        provenanceProblems(in, curatedRunId)
    }
  }

  /**
   * Why the decision run's version parameter `key` is not both the effective policy's `policy`
   * value and the code's `code` value, or None.
   */
  private def decidedVersion(
      decided: Map[String, String],
      key: String,
      policy: String,
      code: String,
      what: String
  ): Option[String] = {
    val v = decided.getOrElse(key, "(none)")
    Option.when(v != policy || v != code)(
      s"the decision run's $key $v differs from the effective match policy's $policy or the " +
        s"$what $code"
    )
  }

  /**
   * CR-P6-07 finding 1: every curated record grouping reads (candidates, works, each work's
   * mentions, profiles) must carry the recorded curated run's ID (`inputRunId` of the decision run,
   * checked above to equal the curated manifest's run ID) in its `provenance.runId`. The check and
   * the record list are the decision exporter's ([[DecisionExport.foreignProvenance]],
   * [[DecisionExport.curatedRecords]]; ER-P6-07).
   */
  private[grouping] def provenanceProblems(
      in: GroupingInputs,
      curatedRunId: String
  ): Vector[String] =
    DecisionExport.foreignProvenance(
      curatedRunId,
      in.candidates.map(c => DecisionExport.candidateLabel(c.candidateId) -> c.provenance) ++
        DecisionExport.curatedRecords(in.works, in.profiles)
    )

  // ---------------------------------------------------------------- members

  private final case class RMember(
      id: String,
      source: SourceType,
      workId: Option[String],
      position: Option[Int],
      name: PersonName,
      ids: Map[ExactIdScheme, Set[String]],
      snapshot: String,
      inputSchema: String
  ) {
    def values(s: ExactIdScheme): Set[String] = ids.getOrElse(s, Set.empty)
  }

  private final case class WMember(
      id: String,
      namespace: String,
      ids: Map[ExactIdScheme, Set[String]],
      stage: StageEvidence,
      work: WorkRecord
  ) {
    def values(s: ExactIdScheme): Set[String] = ids.getOrElse(s, Set.empty)
  }

  private def bySchemes(ids: Vector[ExactId]): Map[ExactIdScheme, Set[String]] =
    ids.groupMap(_.scheme)(_.value).view.mapValues(_.toSet).toMap

  /** The name the pairwise features parse for a researcher endpoint (`MatchFeatures.prepare`). */
  private[grouping] def nameOf(e: ResearcherEndpoint): PersonName = e match {
    case ResearcherEndpoint.OfMention(_, m) => PersonName.parse(m.rawName)
    case ResearcherEndpoint.OfProfile(p) => BlockingKeys.profileName(p)
  }

  private def researcherMembers(
      works: Vector[WorkRecord],
      profiles: Vector[SourceAuthorProfile]
  ): Either[Vector[String], Vector[RMember]] = {
    val mentions = works.flatMap { w =>
      w.authors.map { m =>
        val e = ResearcherEndpoint.OfMention(w, m)
        RMember(
          m.mentionId,
          w.source,
          Some(w.workId),
          Some(m.position),
          nameOf(e),
          bySchemes(MatchFeatures.researcherExactIds(e)),
          w.provenance.snapshot,
          SchemaVersions.WorkRecord
        )
      }
    }
    val ps = profiles.map { p =>
      val e = ResearcherEndpoint.OfProfile(p)
      RMember(
        p.profileId,
        p.source,
        None,
        None,
        nameOf(e),
        bySchemes(MatchFeatures.researcherExactIds(e)),
        p.provenance.snapshot,
        SchemaVersions.SourceAuthorProfile
      )
    }
    val all = (mentions ++ ps).sortWith((a, b) => byUtf8(a.id, b.id))
    val dup = all.groupBy(_.id).collect { case (id, xs) if xs.size > 1 => id }
    if (dup.nonEmpty)
      Left(sortedDistinct(dup).map(id => s"researcher member $id appears more than once"))
    else Right(all)
  }

  private def namespaceOf(workId: String): String =
    workId.stripPrefix("work:").takeWhile(_ != ':')

  private def workMembers(in: GroupingInputs): Either[Vector[String], Vector[WMember]] = {
    val c = in.matchPolicy.workCorroboration
    val all = in.works
      .map { w =>
        val ids = MatchFeatures.workExactIds(w)
        WMember(
          w.workId,
          namespaceOf(w.workId),
          bySchemes(ids),
          PublicationStage.evidence(c, ids),
          w
        )
      }
      .sortWith((a, b) => byUtf8(a.id, b.id))
    val dup = all.groupBy(_.id).collect { case (id, xs) if xs.size > 1 => id }
    val unknown = all.filterNot(w => in.policy.representativeOrder.contains(w.namespace))
    if (dup.nonEmpty || unknown.nonEmpty)
      Left(
        sortedDistinct(dup).map(id => s"work $id appears more than once") ++
          unknown.map(w =>
            s"work ${w.id}: namespace '${w.namespace}' is not in the representative order"
          )
      )
    else Right(all)
  }

  // ---------------------------------------------------------------- decisions

  private def decisionProblems(
      in: GroupingInputs,
      decisionRunId: String,
      researcherIds: Map[String, Int],
      workIds: Map[String, Int]
  ): Vector[String] = {
    val ds = in.decisions
    val kinds = ds.flatMap { d =>
      when(d.entityKind != MatchEntityKind.Researcher && d.entityKind != MatchEntityKind.Work)(
        s"decision ${d.decisionId} is a ${d.entityKind.tag} decision; grouping covers researcher " +
          "and work decisions only"
      )
    }
    val run = ds.flatMap { d =>
      when(d.provenance.runId != decisionRunId)(
        s"decision ${d.decisionId} belongs to run ${d.provenance.runId}, not $decisionRunId"
      ) ++ when(!d.provenance.policyVersion.contains(in.settings.matchPolicyVersion))(
        s"decision ${d.decisionId} has match policy ${d.provenance.policyVersion.getOrElse("(none)")}, " +
          s"not ${in.settings.matchPolicyVersion}"
      )
    }
    val endpoints = ds.flatMap { d =>
      val index = if (d.entityKind == MatchEntityKind.Researcher) researcherIds else workIds
      Vector(d.sourceId, d.targetId).filterNot(index.contains).map { e =>
        s"decision ${d.decisionId}: endpoint $e is not a ${d.entityKind.tag} of the curated run"
      }
    }
    val dupIds = ds.groupBy(_.decisionId).collect {
      case (id, xs) if xs.size > 1 => s"decision $id appears ${xs.size} times"
    }
    val dupPairs = ds.groupBy(d => (d.entityKind.tag, d.sourceId, d.targetId)).collect {
      case ((k, a, b), xs) if xs.size > 1 =>
        s"the $k pair ($a, $b) has ${xs.size} decisions; one decision per pair"
    }
    val candidateIds = in.candidates.map(_.candidateId)
    val decided = ds.map(_.candidateId).toSet
    val curated = candidateIds.toSet
    val coverage = when(decided != curated || ds.size != candidateIds.size)(
      s"the decisions do not decide exactly the curated run's candidates: " +
        s"${(curated -- decided).size} undecided, ${(decided -- curated).size} foreign, " +
        s"${ds.size} decisions for ${candidateIds.size} candidates"
    )
    kinds ++ run ++ endpoints ++ sortedDistinct(dupIds) ++ sortedDistinct(dupPairs) ++ coverage
  }

  /** Links in merge order (B2). */
  private def mergeOrder(p: GroupingPolicy, ds: Vector[MatchDecision]): Vector[MatchDecision] =
    ds.filter(_.decision == MatchOutcome.Accepted).sortWith { (x, y) =>
      val r = Integer.compare(p.pathRank(x.matchPath), p.pathRank(y.matchPath))
      if (r != 0) r < 0
      else {
        val c = java.lang.Double.compare(y.confidence, x.confidence)
        if (c != 0) c < 0
        else {
          val s = Canonical.compareUtf8(x.sourceId, y.sourceId)
          if (s != 0) s < 0 else Canonical.compareUtf8(x.targetId, y.targetId) < 0
        }
      }
    }

  /** Members of each REJECTED or CONFLICT decision's other endpoint (N1 input). */
  private def negatives(ds: Vector[MatchDecision], index: Map[String, Int]): Map[Int, Vector[Int]] =
    ds.filter(d => d.decision == MatchOutcome.Rejected || d.decision == MatchOutcome.Conflict)
      .flatMap { d =>
        val a = index(d.sourceId)
        val b = index(d.targetId)
        Vector(a -> b, b -> a)
      }
      .groupMap(_._1)(_._2)

  // ---------------------------------------------------------------- vetoes

  private def scheme(tag: String): ExactIdScheme =
    ExactIdScheme.fromTag(tag).getOrElse(throw new IllegalStateException(s"unknown scheme $tag"))

  private def agrees(s: ExactIdScheme, a: Set[String], b: Set[String]): Boolean =
    ExactIdentifiers.compareCanonical(s, a, b) == IdAgreement.Agree

  private def conflicts(s: ExactIdScheme, a: Set[String], b: Set[String]): Boolean =
    ExactIdentifiers.compareCanonical(s, a, b) == IdAgreement.Conflict

  /** Some cross pair (both asserting `s`) satisfies `p`. */
  private def cross[M](xs: Vector[M], ys: Vector[M], has: M => Boolean)(p: (M, M) => Boolean) = {
    val a = xs.filter(has)
    if (a.isEmpty) false
    else {
      val b = ys.filter(has)
      b.nonEmpty && a.exists(x => b.exists(y => p(x, y)))
    }
  }

  private def negativeFires(neg: Map[Int, Vector[Int]], xs: Vector[Int], inY: Int => Boolean) =
    xs.exists(x => neg.getOrElse(x, Vector.empty).exists(inY))

  private def researcherVetoes(
      rules: Vector[VetoRule],
      ms: Vector[RMember],
      neg: Map[Int, Vector[Int]]
  ): Veto = {
    val t1 = rules.filter(_.id == "T1").flatMap(_.schemes).map(scheme)
    def t1Agrees(x: RMember, y: RMember): Boolean =
      t1.exists(s => agrees(s, x.values(s), y.values(s)))
    def baseName(m: RMember): (Vector[String], Vector[String]) =
      (m.name.givenParts, m.name.familyWords)
    def numbered(is: Vector[Int]): Vector[RMember] =
      is.map(ms(_)).filter(m => m.source == SourceType.Dblp && m.name.homonymSuffix.isDefined)
    (xi, yi, inY) => {
      lazy val xs = xi.map(ms(_))
      lazy val ys = yi.map(ms(_))
      rules.flatMap { r =>
        r.id match {
          case "N1" => when(negativeFires(neg, xi, inY))(r.reasonCode)
          case "T1" =>
            r.schemes.flatMap { tag =>
              val s = scheme(tag)
              when(cross(xs, ys, (m: RMember) => m.values(s).nonEmpty) { (x, y) =>
                conflicts(s, x.values(s), y.values(s))
              })(s"${r.reasonCode}.$tag")
            }
          case "BD" =>
            r.schemes.flatMap { tag =>
              val s = scheme(tag)
              when(cross(xs, ys, (m: RMember) => m.values(s).nonEmpty) { (x, y) =>
                conflicts(s, x.values(s), y.values(s)) && !t1Agrees(x, y)
              })(s"${r.reasonCode}.$tag")
            }
          case "SW" =>
            val left = xs.flatMap(_.workId).toSet
            when(ys.exists(_.workId.exists(left.contains)))(r.reasonCode)
          case "HN" =>
            val left = numbered(xi).groupMap(baseName)(_.name.homonymSuffix.get)
            when(numbered(yi).exists { y =>
              left.get(baseName(y)).exists(_.exists(n => !y.name.homonymSuffix.contains(n)))
            })(r.reasonCode)
          case other => Vector(s"grouping.invariant.unimplemented-rule.$other")
        }
      }
    }
  }

  /**
   * The accepting T1 agreement of a work pair under er-policy.v8 step 6a: a shared DOI that decides
   * (at least one shared DOI without a preprint prefix, or both sides pure preprints), or a shared
   * arXiv base submission ID while both sides are pure preprints.
   */
  private def workT1Accepts(prefixes: Vector[String])(x: WMember, y: WMember): Boolean = {
    val bothPreprint = x.stage == StageEvidence.Preprint && y.stage == StageEvidence.Preprint
    val sharedDoi = x.values(ExactIdScheme.Doi).intersect(y.values(ExactIdScheme.Doi))
    val doi = sharedDoi.nonEmpty &&
      (sharedDoi.exists(v => !prefixes.exists(v.startsWith)) || bothPreprint)
    val arxiv = bothPreprint &&
      agrees(ExactIdScheme.Arxiv, x.values(ExactIdScheme.Arxiv), y.values(ExactIdScheme.Arxiv))
    doi || arxiv
  }

  private def workVetoes(
      rules: Vector[VetoRule],
      ms: Vector[WMember],
      neg: Map[Int, Vector[Int]],
      preprintPrefixes: Vector[String]
  ): Veto = {
    val accepts = workT1Accepts(preprintPrefixes)
    (xi, yi, inY) => {
      lazy val xs = xi.map(ms(_))
      lazy val ys = yi.map(ms(_))
      rules.flatMap { r =>
        r.id match {
          case "N1" => when(negativeFires(neg, xi, inY))(r.reasonCode)
          case "T1" =>
            r.schemes.flatMap { tag =>
              val s = scheme(tag)
              when(cross(xs, ys, (m: WMember) => m.values(s).nonEmpty) { (x, y) =>
                conflicts(s, x.values(s), y.values(s))
              })(s"${r.reasonCode}.$tag")
            }
          case "BD" =>
            r.schemes.flatMap { tag =>
              val s = scheme(tag)
              when(cross(xs, ys, (m: WMember) => m.values(s).nonEmpty) { (x, y) =>
                conflicts(s, x.values(s), y.values(s)) && !accepts(x, y)
              })(s"${r.reasonCode}.$tag")
            }
          case "ST" =>
            val a = xs.map(_.stage).toSet
            val b = ys.map(_.stage).toSet
            val P = StageEvidence.Preprint
            val Q = StageEvidence.Published
            when((a(P) && b(Q)) || (a(Q) && b(P)))(r.reasonCode)
          case other => Vector(s"grouping.invariant.unimplemented-rule.$other")
        }
      }
    }
  }

  // ---------------------------------------------------------------- merge

  /** Union-find with explicit member lists; its roots never reach the output. */
  private final class UnionFind(n: Int) {
    private val parent = Array.tabulate(n)(identity)
    private val members = Array.tabulate(n)(i => Vector(i))
    def find(i: Int): Int = {
      var r = i
      while (parent(r) != r) r = parent(r)
      var j = i
      while (parent(j) != r) { val next = parent(j); parent(j) = r; j = next }
      r
    }
    def membersOf(root: Int): Vector[Int] = members(root)
    def union(ra: Int, rb: Int): Unit = {
      val (big, small) = if (members(ra).size >= members(rb).size) (ra, rb) else (rb, ra)
      parent(small) = big
      members(big) = members(big) ++ members(small)
      members(small) = Vector.empty
    }
  }

  private def endpointProblems(
      kind: MatchEntityKind,
      links: Vector[MatchDecision],
      index: Map[String, Int],
      veto: Veto
  ): Vector[String] =
    links.flatMap { d =>
      val a = index(d.sourceId)
      val b = index(d.targetId)
      val fired = sortedDistinct(veto(Vector(a), Vector(b), _ == b))
      when(fired.nonEmpty)(
        s"${GroupingPolicy.EndpointCheckCode}: ACCEPTED ${kind.tag} decision ${d.decisionId} " +
          s"(${d.sourceId}, ${d.targetId}) fires ${fired.mkString(",")} on its endpoints alone; " +
          "the curated inputs differ from the ones decided"
      )
    }

  private def merge(
      kind: MatchEntityKind,
      n: Int,
      links: Vector[MatchDecision],
      index: Map[String, Int],
      veto: Veto
  ): (UnionFind, Vector[LinkResult]) = {
    val uf = new UnionFind(n)
    val results = links.map { d =>
      val ra = uf.find(index(d.sourceId))
      val rb = uf.find(index(d.targetId))
      def result(o: LinkOutcome, codes: Vector[String]) =
        LinkResult(kind, d.decisionId, d.sourceId, d.targetId, o, codes)
      if (ra == rb) result(LinkOutcome.Redundant, Vector.empty)
      else {
        val codes = sortedDistinct(veto(uf.membersOf(ra), uf.membersOf(rb), y => uf.find(y) == rb))
        if (codes.isEmpty) { uf.union(ra, rb); result(LinkOutcome.Merged, Vector.empty) }
        else result(LinkOutcome.Vetoed, codes)
      }
    }
    (uf, results)
  }

  /** The roles of every decision touching each endpoint ID. */
  private def roles(
      ds: Vector[MatchDecision],
      linkOf: Map[String, LinkResult]
  ): Map[String, Vector[(DecisionRole, MatchDecision)]] =
    ds.flatMap { d =>
      val role = d.decision match {
        case MatchOutcome.Accepted =>
          if (linkOf.get(d.decisionId).exists(_.outcome == LinkOutcome.Vetoed)) DecisionRole.Vetoed
          else DecisionRole.Supporting
        case MatchOutcome.Unresolved => DecisionRole.Pending
        case MatchOutcome.Conflict => DecisionRole.Conflict
        case MatchOutcome.Rejected => DecisionRole.Rejected
      }
      Vector(d.sourceId -> (role, d), d.targetId -> (role, d))
    }.groupMap(_._1)(_._2)

  private def ids(rs: Vector[(DecisionRole, MatchDecision)], role: DecisionRole): Vector[String] =
    sortedDistinct(rs.collect { case (r, d) if r == role => d.decisionId })

  private def vetoCodes(
      rs: Vector[(DecisionRole, MatchDecision)],
      linkOf: Map[String, LinkResult]
  ): Vector[String] =
    rs.collect { case (DecisionRole.Vetoed, d) => linkOf(d.decisionId).reasonCodes }.flatten

  private def provenance(
      subject: String,
      snapshot: String,
      runId: String,
      inputSchema: String,
      groupingVersion: String
  ): Provenance =
    Provenance(
      SourceType.Derived,
      subject,
      snapshot,
      runId,
      inputSchema,
      Some(groupingVersion),
      None,
      None
    )

  // ---------------------------------------------------------------- run

  /** Groups one verified decision run; refused with every reason, nothing partial. */
  def group(in: GroupingInputs): Either[Vector[String], GroupingBatch] = {
    def check(es: Vector[String]): Either[Vector[String], Unit] =
      if (es.isEmpty) Right(()) else Left(es)
    for {
      _ <- check(manifestProblems(in))
      runId = RunId.of(in.manifest).toOption.get
      decisionRunId = RunId.of(in.decisionManifest).toOption.get
      rms <- researcherMembers(in.works, in.profiles)
      wms <- workMembers(in)
      rIndex = rms.zipWithIndex.map { case (m, i) => m.id -> i }.toMap
      wIndex = wms.zipWithIndex.map { case (m, i) => m.id -> i }.toMap
      _ <- check(decisionProblems(in, decisionRunId, rIndex, wIndex))
      rDecisions = in.decisions.filter(_.entityKind == MatchEntityKind.Researcher)
      wDecisions = in.decisions.filter(_.entityKind == MatchEntityKind.Work)
      rVeto = researcherVetoes(
        in.policy.rules(MatchEntityKind.Researcher),
        rms,
        negatives(rDecisions, rIndex)
      )
      wVeto = workVetoes(
        in.policy.rules(MatchEntityKind.Work),
        wms,
        negatives(wDecisions, wIndex),
        in.matchPolicy.workCorroboration.preprintDoiPrefixes
      )
      rLinks = mergeOrder(in.policy, rDecisions)
      wLinks = mergeOrder(in.policy, wDecisions)
      _ <- check(
        endpointProblems(MatchEntityKind.Researcher, rLinks, rIndex, rVeto) ++
          endpointProblems(MatchEntityKind.Work, wLinks, wIndex, wVeto)
      )
      (rUf, rResults) = merge(MatchEntityKind.Researcher, rms.size, rLinks, rIndex, rVeto)
      (wUf, wResults) = merge(MatchEntityKind.Work, wms.size, wLinks, wIndex, wVeto)
      ctx = Context(
        in,
        runId,
        decisionRunId,
        (rResults ++ wResults).map(l => l.decisionId -> l).toMap
      )
      researchers <- researcherRecords(ctx, rms, rUf, rDecisions)
      works = workRecords(ctx, wms, wUf, wDecisions)
      authorships = authorshipRecords(ctx, researchers, works, rDecisions)
      teams = teamRecords(ctx, researchers, works)
      batch = GroupingBatch(
        in.manifest,
        runId,
        researchers,
        works,
        authorships,
        teams,
        rResults ++ wResults,
        diagnostics(ctx, rms, wms, researchers, works, teams, rResults ++ wResults)
      )
      _ <- check(verify(in, batch))
    } yield batch
  }

  private final case class Context(
      in: GroupingInputs,
      runId: String,
      decisionRunId: String,
      linkOf: Map[String, LinkResult]
  ) {
    def version: String = in.settings.groupingPolicyVersion
    def assignedAt: String = in.manifest.parameters(Params.AssignedAt)
  }

  /** The anchor schemes of C1, in anchor order (every anchor namespace but `member`). */
  private def anchorSchemes(p: GroupingPolicy): Vector[ExactIdScheme] =
    p.anchorOrder.filterNot(_ == GroupingPolicy.MemberAnchor).map(scheme)

  /** (scheme, value) -> the final groups (roots) of the members that assert it. */
  private def asserters(
      schemes: Vector[ExactIdScheme],
      rms: Vector[RMember],
      uf: UnionFind
  ): Map[(ExactIdScheme, String), Set[Int]] =
    rms.indices
      .flatMap(i => schemes.flatMap(s => rms(i).values(s).map(v => (s, v) -> uf.find(i))))
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.toSet)
      .toMap

  private final case class Anchor(namespace: String, value: String, collisions: Vector[String])

  private def anchorOf(
      schemes: Vector[ExactIdScheme],
      members: Vector[RMember],
      collided: ((ExactIdScheme, String)) => Boolean
  ): Anchor = {
    val chosen = schemes.iterator
      .map { s =>
        val vs = members.flatMap(_.values(s)).distinct
        Option.when(vs.size == 1 && !collided((s, vs.head)))(s.tag -> vs.head)
      }
      .collectFirst { case Some(a) => a }
      .getOrElse(GroupingPolicy.MemberAnchor -> members.map(_.id).sortWith(byUtf8).head)
    val collisions = schemes.collect {
      case s if members.exists(_.values(s).exists(v => collided((s, v)))) =>
        s"grouping.anchor-collision.${s.tag}"
    }
    Anchor(chosen._1, chosen._2, collisions)
  }

  private def researcherRecords(
      ctx: Context,
      rms: Vector[RMember],
      uf: UnionFind,
      ds: Vector[MatchDecision]
  ): Either[Vector[String], Vector[ResearcherAssignment]] = {
    val in = ctx.in
    val schemes = anchorSchemes(in.policy)
    val by = asserters(schemes, rms, uf)
    val collided: ((ExactIdScheme, String)) => Boolean = k => by.getOrElse(k, Set.empty).size > 1
    val groups = rms.indices.groupBy(uf.find).view.mapValues(_.toVector.sorted).toMap
    val anchors = groups.map { case (root, is) =>
      root -> anchorOf(schemes, is.map(rms(_)), collided)
    }
    val rolesOf = roles(ds, ctx.linkOf)
    def idOf(a: Anchor): Either[ValidationError, String] =
      if (a.namespace == ResearcherAssignment.OrcidNamespace) ResearcherId.orcid(a.value)
      else ResearcherId.scholarChain(in.settings.identityVersion, a.namespace, a.value)
    val records = rms.indices.toVector.map { i =>
      val m = rms(i)
      val root = uf.find(i)
      val size = groups(root).size
      val anchor = anchors(root)
      val rs = rolesOf.getOrElse(m.id, Vector.empty)
      val pending = ids(rs, DecisionRole.Pending)
      val vetoed = ids(rs, DecisionRole.Vetoed)
      val conflict = ids(rs, DecisionRole.Conflict)
      val blocking = pending.nonEmpty || vetoed.nonEmpty || conflict.nonEmpty
      val t1 = ResearcherAssignment.T1AnchorNamespaces.contains(anchor.namespace)
      val status =
        if (size >= 2) AssignmentStatus.Resolved
        else if (!blocking || t1) AssignmentStatus.Singleton
        else AssignmentStatus.Unresolved
      val statusCodes = status match {
        case AssignmentStatus.Resolved => Vector("grouping.resolved")
        case AssignmentStatus.Singleton =>
          Vector(if (blocking) "grouping.singleton.t1-anchored" else "grouping.singleton")
        case AssignmentStatus.Unresolved =>
          when(pending.nonEmpty)("grouping.unresolved.pending-decision") ++
            when(vetoed.nonEmpty)("grouping.unresolved.vetoed-decision") ++
            when(conflict.nonEmpty)("grouping.unresolved.conflict-decision")
      }
      val identified = status.identified
      idOf(anchor).left
        .map(e => s"researcher ID of ${m.id}: ${e.message}")
        .map { rid =>
          ResearcherAssignment(
            schemaVersion = SchemaVersions.ResearcherAssignment,
            memberId = m.id,
            workId = m.workId,
            position = m.position,
            status = status,
            researcherId = Option.when(identified)(rid),
            identityVersion = in.settings.identityVersion,
            anchorNamespace = Option.when(identified)(anchor.namespace),
            anchorValue = Option.when(identified)(anchor.value),
            groupSize = if (identified) size else 1,
            supportingDecisionIds = ids(rs, DecisionRole.Supporting),
            vetoedDecisionIds = vetoed,
            pendingDecisionIds = pending,
            conflictDecisionIds = conflict,
            rejectedDecisionIds = ids(rs, DecisionRole.Rejected),
            reasonCodes = sortedDistinct(
              (if (identified) Vector(s"grouping.anchor.${anchor.namespace}") else Vector.empty) ++
                anchor.collisions ++ statusCodes ++ vetoCodes(rs, ctx.linkOf)
            ),
            groupingPolicyVersion = ctx.version,
            matchPolicyVersion = in.settings.matchPolicyVersion,
            decisionRunId = ctx.decisionRunId,
            assignedAt = ctx.assignedAt,
            provenance = provenance(m.id, m.snapshot, ctx.runId, m.inputSchema, ctx.version)
          )
        }
    }
    val errors = records.collect { case Left(e) => e }
    if (errors.nonEmpty) Left(errors) else Right(records.collect { case Right(r) => r })
  }

  private def workRecords(
      ctx: Context,
      wms: Vector[WMember],
      uf: UnionFind,
      ds: Vector[MatchDecision]
  ): Vector[WorkAssignment] = {
    val in = ctx.in
    val order = in.policy.representativeOrder
    val dblp = WorkRecord.NamespaceOf(SourceType.Dblp)
    val groups = wms.indices.groupBy(uf.find).view.mapValues(_.toVector.sorted).toMap
    val reps = groups.map { case (root, is) =>
      val ms = is.map(wms(_))
      val ns = order.find(n => ms.exists(_.namespace == n)).get
      val same = ms.filter(_.namespace == ns).map(_.id).sortWith(byUtf8)
      root -> (same.head, ns, same.size > 1, ms.exists(_.namespace == dblp))
    }
    val rolesOf = roles(ds, ctx.linkOf)
    wms.indices.toVector.map { i =>
      val w = wms(i)
      val root = uf.find(i)
      val size = groups(root).size
      val (rep, ns, tie, counted) = reps(root)
      val isRep = rep == w.id
      val eligibility =
        if (!counted) GraphEligibility.EnrichmentOnly
        else if (isRep) GraphEligibility.Counted
        else GraphEligibility.MemberOfCounted
      val rs = rolesOf.getOrElse(w.id, Vector.empty)
      val pending = ids(rs, DecisionRole.Pending)
      val conflict = ids(rs, DecisionRole.Conflict)
      WorkAssignment(
        schemaVersion = SchemaVersions.WorkAssignment,
        workId = w.id,
        canonicalWorkId = rep,
        isRepresentative = isRep,
        status = if (size >= 2) WorkGroupStatus.Grouped else WorkGroupStatus.Single,
        representativeRule = in.policy.representativeRule,
        groupSize = size,
        graphEligibility = eligibility,
        supportingDecisionIds = ids(rs, DecisionRole.Supporting),
        vetoedDecisionIds = ids(rs, DecisionRole.Vetoed),
        pendingDecisionIds = pending,
        conflictDecisionIds = conflict,
        rejectedDecisionIds = ids(rs, DecisionRole.Rejected),
        reasonCodes = sortedDistinct(
          Vector(
            if (size >= 2) "grouping.grouped" else "grouping.single",
            s"grouping.graph.${eligibility.tag}",
            if (isRep) s"grouping.representative.$ns" else "grouping.copy"
          ) ++ when(isRep && tie)("grouping.representative.utf8-tiebreak") ++
            when(pending.nonEmpty)("grouping.pending-decision") ++
            when(conflict.nonEmpty)("grouping.conflict-decision") ++
            vetoCodes(rs, ctx.linkOf)
        ),
        groupingPolicyVersion = ctx.version,
        matchPolicyVersion = in.settings.matchPolicyVersion,
        decisionRunId = ctx.decisionRunId,
        assignedAt = ctx.assignedAt,
        provenance = provenance(
          w.id,
          w.work.provenance.snapshot,
          ctx.runId,
          SchemaVersions.WorkRecord,
          ctx.version
        )
      )
    }
  }

  private def authorshipRecords(
      ctx: Context,
      researchers: Vector[ResearcherAssignment],
      works: Vector[WorkAssignment],
      ds: Vector[MatchDecision]
  ): Vector[AuthorshipRecordV2] = {
    val byMember = researchers.map(r => r.memberId -> r).toMap
    val reps = works.filter(_.isRepresentative).map(_.workId).toSet
    val confidence = ds.map(d => d.decisionId -> d.confidence).toMap
    ctx.in.works
      .filter(w => reps.contains(w.workId))
      .flatMap { w =>
        w.authors.flatMap { m =>
          byMember.get(m.mentionId).filter(_.status.identified).map { r =>
            AuthorshipRecordV2(
              schemaVersion = SchemaVersions.AuthorshipRecordV2,
              workId = w.workId,
              mentionId = m.mentionId,
              researcherId = r.researcherId.get,
              position = m.position,
              year = w.year,
              assignmentStatus = r.status,
              supportingDecisionIds = r.supportingDecisionIds,
              matchConfidence = Option.when(r.supportingDecisionIds.nonEmpty)(
                r.supportingDecisionIds.map(confidence).min
              ),
              groupingPolicyVersion = ctx.version,
              matchPolicyVersion = ctx.in.settings.matchPolicyVersion,
              decisionRunId = ctx.decisionRunId,
              provenance = provenance(
                m.mentionId,
                w.provenance.snapshot,
                ctx.runId,
                SchemaVersions.ResearcherAssignment,
                ctx.version
              )
            )
          }
        }
      }
      .sortWith((a, b) => byUtf8(a.mentionId, b.mentionId))
  }

  private def teamRecords(
      ctx: Context,
      researchers: Vector[ResearcherAssignment],
      works: Vector[WorkAssignment]
  ): Vector[PublicationTeam] = {
    val byMember = researchers.map(r => r.memberId -> r).toMap
    val counted =
      works.filter(_.graphEligibility == GraphEligibility.Counted).map(_.workId).toSet
    ctx.in.works
      .filter(w => counted.contains(w.workId) && w.authors.nonEmpty)
      .map { w =>
        val ms = w.authors.sortBy(_.position).map(m => byMember(m.mentionId))
        PublicationTeam(
          schemaVersion = SchemaVersions.PublicationTeam,
          workId = w.workId,
          year = w.year,
          researcherIds = ms.filter(_.status.identified).flatMap(_.researcherId),
          teamSize = ms.size,
          unresolvedMentionIds = ms.filterNot(_.status.identified).map(_.memberId),
          provenance = provenance(
            w.workId,
            w.provenance.snapshot,
            ctx.runId,
            SchemaVersions.WorkAssignment,
            ctx.version
          )
        )
      }
      .sortWith((a, b) => byUtf8(a.workId, b.workId))
  }

  // ---------------------------------------------------------------- validation

  /**
   * Every ORCID iD and DBLP pid asserted by a researcher member of the curated run, in the
   * canonical form the anchors use (the input of [[AssignmentValidation.checkSingletonAnchors]];
   * unresolved members are included, so they count as colliders).
   */
  private def memberIdentifiers(rms: Vector[RMember]): Vector[MemberIdentifier] = {
    val t1 = ResearcherAssignment.T1AnchorNamespaces.toVector.sorted.map(scheme)
    rms.flatMap(m =>
      t1.flatMap(s =>
        m.values(s).toVector.sortWith(byUtf8).map(v => MemberIdentifier(m.id, ExactId(s, v)))
      )
    )
  }

  /** Every core validator of a grouping run; the record checks first (core precondition). */
  private def validate(
      ctx: Context,
      rms: Vector[RMember],
      rs: Vector[ResearcherAssignment],
      ws: Vector[WorkAssignment],
      as: Vector[AuthorshipRecordV2],
      ts: Vector[PublicationTeam]
  ): Vector[String] = {
    def tag(name: String, es: Vector[ValidationError]): Vector[String] =
      es.map(e => s"grouping.invariant.core.$name: ${e.message}")
    val records =
      tag("checkResearchers", AssignmentValidation.checkResearchers(rs)) ++
        tag("checkWorks", AssignmentValidation.checkWorks(ws))
    if (records.nonEmpty) records
    else
      tag("checkRun", AssignmentValidation.checkRun(rs, ws)) ++
        tag(
          "checkAgainstDecisions",
          AssignmentValidation.checkAgainstDecisions(rs, ws, ctx.in.decisions)
        ) ++
        tag("checkAuthorships", AssignmentValidation.checkAuthorships(as, rs, ws)) ++
        tag(
          "checkAuthorshipConfidence",
          AssignmentValidation.checkAuthorshipConfidence(as, ctx.in.decisions)
        ) ++
        tag("checkTeams", AssignmentValidation.checkTeams(ts, rs, ws)) ++
        tag(
          "checkCoverage",
          AssignmentValidation.checkCoverage(rs, ws, ctx.in.works.map(ExpectedAuthors.of))
        ) ++
        tag(
          "checkSingletonAnchors",
          AssignmentValidation.checkSingletonAnchors(rs, memberIdentifiers(rms))
        )
  }

  /**
   * The checks core leaves to the producer (DE-P6-01 repair 01, F2 list), plus the merge
   * invariants:
   *   - the identifier input is complete and canonical: it holds every ORCID iD and DBLP pid that
   *     core itself reads from each mention ([[MemberIdentifier.fromMention]]) and that each
   *     profile asserts (its own iD or pid, `orcid`, external IDs), every value canonical;
   *   - every researcher member of the curated run (mentions and profiles) has exactly one
   *     assignment;
   *   - no collision outside a group: every member asserting a group's orcid, dblp-pid or
   *     openalex-author anchor value carries that group's researcher ID, and a member anchor is a
   *     member of its group (that one researcher ID is one connected group is core's
   *     [[AssignmentValidation.checkResearchers]]);
   *   - correct singleton status: an ACCEPTED decision is supporting exactly when its endpoints
   *     share a researcher (or canonical work), and a vetoed one carries its veto reason codes; an
   *     unresolved member has no usable T1 anchor of its own.
   */
  private def producerChecks(
      ctx: Context,
      rms: Vector[RMember],
      rs: Vector[ResearcherAssignment],
      ws: Vector[WorkAssignment]
  ): Vector[String] = {
    val P = "grouping.invariant.producer"
    val identifiers = memberIdentifiers(rms)
    val canonical = identifiers.flatMap { i =>
      ExactId
        .check(s"${i.memberId}", i.identifier)
        .map(e => s"$P: non-canonical identifier: ${e.message}")
    }
    val supplied = identifiers.map(i => (i.memberId, i.identifier)).toSet
    val profileAssertions = ctx.in.profiles.flatMap { p =>
      val own = p.source match {
        case SourceType.Orcid => Vector(ExactId(ExactIdScheme.Orcid, p.sourceRecordId))
        case SourceType.Dblp => Vector(ExactId(ExactIdScheme.DblpPid, p.sourceRecordId))
        case _ => Vector.empty
      }
      val ext = p.externalIds.collect {
        case e if e.scheme == ExternalIdScheme.Orcid => ExactId(ExactIdScheme.Orcid, e.value)
        case e if e.scheme == ExternalIdScheme.DblpPid => ExactId(ExactIdScheme.DblpPid, e.value)
      }
      (own ++ p.orcid.toVector.map(ExactId(ExactIdScheme.Orcid, _)) ++ ext).map(p.profileId -> _)
    }
    val asserted = ctx.in.works
      .flatMap(_.authors.flatMap(MemberIdentifier.fromMention))
      .map(i => i.memberId -> i.identifier) ++ profileAssertions
    val incomplete = asserted.distinct.filterNot(supplied.contains).map { case (m, e) =>
      s"$P: $m asserts ${e.scheme.tag} ${e.value}, which the collision input lacks"
    }
    val memberSet = rms.map(_.id).toSet
    val assigned = rs.map(_.memberId)
    val coverage =
      when(assigned.toSet != memberSet || assigned.size != memberSet.size)(
        s"$P: the researcher assignments are not exactly the curated run's " +
          s"${memberSet.size} researcher members (found ${assigned.size})"
      )
    val ridOf = rs.map(r => r.memberId -> r.researcherId).toMap
    val index = rms.zipWithIndex.map { case (m, i) => m.id -> i }.toMap
    val schemes = anchorSchemes(ctx.in.policy)
    val holders = rms
      .flatMap(m => schemes.flatMap(s => m.values(s).map(v => (s.tag, v) -> m.id)))
      .groupMap(_._1)(_._2)
    val anchors = rs.filter(_.status.identified).flatMap { r =>
      (r.anchorNamespace, r.anchorValue) match {
        case (Some(GroupingPolicy.MemberAnchor), Some(v)) =>
          when(ridOf.get(v).flatten != r.researcherId)(
            s"$P: ${r.memberId}: member anchor $v is not a member of its researcher"
          )
        case (Some(ns), Some(v)) =>
          holders
            .getOrElse((ns, v), Vector.empty)
            .filter(h => ridOf.get(h).flatten != r.researcherId)
            .map { h =>
              s"$P: anchor $ns $v of ${r.researcherId.get} is also asserted by $h of another group"
            }
        case _ => Vector(s"$P: ${r.memberId} is identified without an anchor")
      }
    }
    val canonicalOf = ws.map(w => w.workId -> w.canonicalWorkId).toMap
    val status = ctx.in.decisions.filter(_.decision == MatchOutcome.Accepted).flatMap { d =>
      val together =
        if (d.entityKind == MatchEntityKind.Researcher)
          ridOf.get(d.sourceId).flatten.exists(r => ridOf.get(d.targetId).flatten.contains(r))
        else canonicalOf.get(d.sourceId).exists(canonicalOf.get(d.targetId).contains)
      ctx.linkOf.get(d.decisionId) match {
        case None => Vector(s"$P: ACCEPTED decision ${d.decisionId} was never merged or vetoed")
        case Some(l) if l.outcome == LinkOutcome.Vetoed =>
          when(together)(s"$P: vetoed decision ${d.decisionId} joins one group") ++
            when(l.reasonCodes.isEmpty)(s"$P: vetoed decision ${d.decisionId} has no veto reason")
        case Some(_) =>
          when(!together)(s"$P: supporting decision ${d.decisionId} joins different groups")
      }
    }
    val t1 = ResearcherAssignment.T1AnchorNamespaces.toVector.sorted.map(scheme)
    val unresolved = rs.filter(_.status == AssignmentStatus.Unresolved).flatMap { r =>
      index.get(r.memberId).toVector.flatMap { i =>
        val m = rms(i)
        t1.flatMap { s =>
          val vs = m.values(s)
          when(
            vs.size == 1 &&
              holders.getOrElse((s.tag, vs.head), Vector.empty).distinct == Vector(m.id)
          )(s"$P: unresolved ${m.id} has its own non-colliding ${s.tag} ${vs.head}")
        }
      }
    }
    canonical ++ incomplete ++ coverage ++ anchors ++ status ++ unresolved
  }

  /**
   * Re-runs every pre-publication check of a batch against its inputs: every core validator
   * ([[validate]]) and the producer checks ([[producerChecks]]). Empty when the batch may be
   * published. [[group]] calls it before returning a batch.
   */
  def verify(in: GroupingInputs, b: GroupingBatch): Vector[String] =
    researcherMembers(in.works, in.profiles) match {
      case Left(es) => es
      case Right(rms) =>
        val decisionRunId = RunId.of(in.decisionManifest).getOrElse("")
        val ctx = Context(in, b.runId, decisionRunId, b.links.map(l => l.decisionId -> l).toMap)
        validate(ctx, rms, b.researchers, b.works, b.authorships, b.teams) ++
          producerChecks(ctx, rms, b.researchers, b.works)
    }

  // ---------------------------------------------------------------- diagnostics

  private def histogram(sizes: Iterable[Int]): String =
    sizes.groupBy(identity).toVector.sortBy(_._1).map { case (s, xs) => s"$s:${xs.size}" } match {
      case Vector() => "none"
      case v => v.mkString(",")
    }

  private def counts(keys: Iterable[String]): String =
    keys.groupBy(identity).toVector.sortWith((a, b) => byUtf8(a._1, b._1)).map { case (k, xs) =>
      s"$k:${xs.size}"
    } match {
      case Vector() => "none"
      case v => v.mkString(",")
    }

  /**
   * Descriptive counts of one grouping run, every line labelled `unevaluated` (no label is read; no
   * precision, recall or quality is claimed): group sizes, link outcomes, vetoes by rule and
   * reason, statuses, the unresolved rate, anchor namespaces and collisions, graph eligibility,
   * teams, the ST count (expected 0 under er-policy.v8) and the SW-C count (O8: pairs of mentions
   * of one researcher on two copies of one canonical work at different positions; not a veto in
   * v1).
   */
  private def diagnostics(
      ctx: Context,
      rms: Vector[RMember],
      wms: Vector[WMember],
      rs: Vector[ResearcherAssignment],
      ws: Vector[WorkAssignment],
      ts: Vector[PublicationTeam],
      links: Vector[LinkResult]
  ): Vector[String] = {
    val U = "unevaluated"
    val identified = rs.filter(_.status.identified)
    val groups = identified.groupBy(_.researcherId.get)
    val unresolved = rs.count(_.status == AssignmentStatus.Unresolved)
    val rate =
      if (rs.isEmpty) "n/a"
      else String.format(Locale.ROOT, "%.4f", Double.box(unresolved.toDouble / rs.size))
    val workGroups = ws.groupBy(_.canonicalWorkId)
    val st = ctx.in.policy.rules(MatchEntityKind.Work).filter(_.id == "ST").flatMap(_.codes).toSet
    val stCount = links.count(l => l.reasonCodes.exists(st.contains))
    val canonical = ws.map(w => w.workId -> w.canonicalWorkId).toMap
    // SW-C (O8): pairs of mentions of one researcher on two different copies of one canonical
    // work, at different author positions.
    val swc = groups.values.toVector.map { g =>
      val ms = g.flatMap(r => r.workId.map(w => (canonical(w), w, r.position.getOrElse(-1))))
      ms.indices.map { i =>
        (i + 1 until ms.size).count { j =>
          ms(i)._1 == ms(j)._1 && ms(i)._2 != ms(j)._2 && ms(i)._3 != ms(j)._3
        }
      }.sum
    }.sum
    // Identifier values asserted by more than one final group (an unidentified member is a
    // group of its own), per anchor scheme.
    val collidedValues = {
      val groupOf = rs.map(r => r.memberId -> r.researcherId.getOrElse(r.memberId)).toMap
      anchorSchemes(ctx.in.policy)
        .map { s =>
          val n = rms
            .flatMap(m => m.values(s).map(v => v -> groupOf(m.id)))
            .groupMap(_._1)(_._2)
            .count(_._2.distinct.size > 1)
          s"${s.tag}:$n"
        }
        .mkString(",")
    }
    val collision = "grouping.anchor-collision."
    def collisionCodes(xs: Iterable[ResearcherAssignment]): String =
      counts(xs.flatMap(_.reasonCodes.filter(_.startsWith(collision))))
    def linkLine(k: MatchEntityKind): String = {
      val ls = links.filter(_.kind == k)
      s"$U links ${k.tag} total=${ls.size} " +
        LinkOutcome.values.map(o => s"${o.tag}=${ls.count(_.outcome == o)}").mkString(" ")
    }
    def vetoLine(k: MatchEntityKind): String = {
      val ls = links.filter(l => l.kind == k && l.outcome == LinkOutcome.Vetoed)
      val ruleOf = ctx.in.policy.rules(k).flatMap(r => r.codes.map(_ -> r.id)).toMap
      val byRule = ls.flatMap(_.reasonCodes.map(c => ruleOf.getOrElse(c, c)).distinct)
      s"$U vetoes ${k.tag} links=${ls.size} byRule=${counts(byRule)} " +
        s"byReason=${counts(ls.flatMap(_.reasonCodes))}"
    }
    val singletons = rs.filter(_.status == AssignmentStatus.Singleton)
    val t1Anchored = singletons.count(ResearcherAssignment.requiresT1Anchor)
    def eligible(g: GraphEligibility): Int = ws.count(_.graphEligibility == g)
    Vector(
      s"$U grouping run=${ctx.runId} policy=${ctx.version} " +
        s"matchPolicy=${ctx.in.settings.matchPolicyVersion} decisionRun=${ctx.decisionRunId} " +
        s"identityVersion=${ctx.in.settings.identityVersion} " +
        "(descriptive counts only; not evaluated: no precision, recall or quality claim)",
      s"$U researcher members=${rs.size} mentions=${rs.count(_.workId.isDefined)} " +
        s"profiles=${rs.count(_.workId.isEmpty)} researcherIds=${groups.size}",
      // CR-P6-07 nit 3: identified groups only; unresolved members have no researcher ID and are
      // reported separately.
      s"$U researcher groupSizes=${histogram(groups.values.map(_.size))} " +
        s"(identified groups only; unresolved members excluded: $unresolved)",
      s"$U researcher status resolved=${rs.count(_.status == AssignmentStatus.Resolved)} " +
        s"singleton=${singletons.size - t1Anchored} singletonT1Anchored=$t1Anchored " +
        s"unresolved=$unresolved",
      s"$U researcher unresolved=$unresolved of ${rs.size} rate=$rate",
      s"$U researcher anchorNamespaces=${counts(groups.values.map(_.head.anchorNamespace.get))}",
      s"$U researcher anchorCollisions identifiedGroups=${collisionCodes(groups.values.map(_.head))} " +
        s"unresolvedMembers=${collisionCodes(rs.filterNot(_.status.identified))} " +
        s"collidedValues=$collidedValues",
      linkLine(MatchEntityKind.Researcher),
      vetoLine(MatchEntityKind.Researcher),
      s"$U work members=${ws.size} canonicalWorks=${workGroups.size} " +
        s"groupSizes=${histogram(workGroups.values.map(_.size))}",
      s"$U work graphEligibility counted=${eligible(GraphEligibility.Counted)} " +
        s"member-of-counted=${eligible(GraphEligibility.MemberOfCounted)} " +
        s"enrichment-only=${eligible(GraphEligibility.EnrichmentOnly)}",
      linkLine(MatchEntityKind.Work),
      vetoLine(MatchEntityKind.Work),
      s"$U teams=${ts.size} teamMentions=${ts.map(_.teamSize).sum} " +
        s"unresolvedTeamMentions=${ts.map(_.unresolvedMentionIds.size).sum} works=${wms.size}",
      s"$U ST=$stCount (expected 0 under er-policy.v8) SW-C=$swc (O8 diagnostic, not a veto)"
    )
  }
}
