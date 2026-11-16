package scholarchain.core

import scala.collection.immutable.SortedMap

import upickle.default.ReadWriter
import upickle.implicits.allowUnknownKeys

import Validated.{all, at, nonEmpty, when}

// =====================================================================================
// Phase 6 (DE-P6-02): contracts of the Spark collaboration-analysis stage.
//
// Graph scope (binding decision 1, O9): the graph is built from the canonical teams of the
// grouping run (PublicationTeam v1 of a grouping run), only for works whose WorkAssignment has
// graphEligibility `counted`; never from Hadoop TeamJob teams. P-7 (preprint linking) is deferred.
// Metric semantics (binding decision 2): T_r, A_r and M_r include unresolved coauthor
// occurrences, which are also reported in separate fields; C_r, edges and W_r use identified
// coauthors only. Grouping quality is unevaluated (binding decision 3): no record carries or
// implies a quality claim, and the lineage states `groupingEvaluation = unevaluated`.
// =====================================================================================

/**
 * Evaluation state of the grouping run an analysis rests on. Frozen tags. The only value is
 * `unevaluated` (Phase 6 decision: grouping quality is not evaluated). A future evaluated state
 * needs a new value and a schema version bump of every record that carries [[AnalysisLineage]]; no
 * record may imply grouping quality.
 */
enum GroupingEvaluation(val tag: String) {
  case Unevaluated extends GroupingEvaluation("unevaluated")
}
object GroupingEvaluation {
  def fromTag(tag: String): Option[GroupingEvaluation] = values.find(_.tag == tag)
  given ReadWriter[GroupingEvaluation] =
    EnumCodec.tagged("GroupingEvaluation", values.toSeq, _.tag)
}

/**
 * The run chain and versions behind every analysis output record (binding decision 4). Versioned by
 * its enclosing record. All records of one analysis run carry the same lineage, equal to the
 * analysis run manifest ([[AnalysisValidation.checkRunChain]]).
 *
 * @param groupingRunId
 *   the grouping run whose canonical teams and assignments were read
 * @param decisionRunId
 *   the grouping run's decision run (its manifest parameter `decisionRunId`)
 * @param inputRunId
 *   the curated input run of the decision and grouping runs (manifest parameter `inputRunId`)
 * @param codeVersion
 *   40-hex git commit of the analysis code (equals the analysis run manifest's codeVersion)
 * @param configDigest
 *   [[scholarchain.core.config.AnalysisRunSettings.configDigest]] (equals the analysis run
 *   manifest's configDigest)
 * @param groupingPolicyVersion
 *   grouping policy of the grouping run (for example `er-grouping.v1`)
 * @param matchPolicyVersion
 *   match policy of the decision run (for example `er-policy.v8`)
 * @param identityVersion
 *   ScholarChain identity ID version of the grouping run (`v[0-9]+`)
 * @param groupingEvaluation
 *   always `unevaluated`; never a quality claim
 */
@allowUnknownKeys(false)
final case class AnalysisLineage(
    groupingRunId: String,
    decisionRunId: String,
    inputRunId: String,
    codeVersion: String,
    configDigest: String,
    groupingPolicyVersion: String,
    matchPolicyVersion: String,
    identityVersion: String,
    groupingEvaluation: GroupingEvaluation
) derives ReadWriter

object AnalysisLineage {
  private val IdentityVersionPattern = "^v[0-9]+$".r

  def check(field: String, l: AnalysisLineage): Vector[ValidationError] =
    if (l == null) Vector(ValidationError.MissingField(field))
    else {
      val runs = Vector(l.groupingRunId, l.decisionRunId, l.inputRunId)
      all(
        StableId.check(IdKind.Run, l.groupingRunId, at(field, "groupingRunId")),
        StableId.check(IdKind.Run, l.decisionRunId, at(field, "decisionRunId")),
        StableId.check(IdKind.Run, l.inputRunId, at(field, "inputRunId")),
        when(runs.forall(_ != null) && runs.distinct.size != runs.size)(
          ValidationError.InvalidValue(
            at(field, "groupingRunId,decisionRunId,inputRunId"),
            "the grouping, decision and input runs are three different runs"
          )
        ),
        when(l.codeVersion == null || !Canonical.isHex40(l.codeVersion))(
          ValidationError
            .InvalidValue(at(field, "codeVersion"), "expected a 40-hex lowercase git SHA")
        ),
        when(l.configDigest == null || !Canonical.isHex64(l.configDigest))(
          ValidationError
            .InvalidValue(at(field, "configDigest"), "expected 64 lowercase hex characters")
        ),
        AssignmentRules.token(at(field, "groupingPolicyVersion"), l.groupingPolicyVersion),
        AssignmentRules.token(at(field, "matchPolicyVersion"), l.matchPolicyVersion),
        when(l.groupingPolicyVersion != null && l.groupingPolicyVersion == l.matchPolicyVersion)(
          ValidationError.InvalidValue(
            at(field, "groupingPolicyVersion"),
            "the grouping policy is versioned separately from the match policy"
          )
        ),
        when(l.identityVersion == null || !IdentityVersionPattern.matches(l.identityVersion))(
          ValidationError.InvalidValue(at(field, "identityVersion"), "must match v[0-9]+")
        ),
        when(l.groupingEvaluation == null)(
          ValidationError.MissingField(at(field, "groupingEvaluation"))
        )
      )
    }
}

// ------------------------------------------------------------------ C5 identity confidence

/**
 * Identity status of a researcher, carried from the grouping run (decision D1). Frozen tags.
 *   - `resolved`: a group of two or more members joined by kept ACCEPTED decisions.
 *   - `singleton`: a group of one with no pending, vetoed or conflict decision.
 *   - `singleton-t1-anchored`: a group of one that carries a pending, vetoed or conflict decision,
 *     anchored on its own, non-colliding ORCID iD or DBLP pid
 *     ([[ResearcherAssignment.requiresT1Anchor]]).
 *
 * Unresolved members have no researcher ID and therefore never have an identity confidence: their
 * occurrences are counted in the separate unresolved fields only.
 */
enum IdentityStatus(val tag: String) {
  case Resolved extends IdentityStatus("resolved")
  case Singleton extends IdentityStatus("singleton")
  case SingletonT1Anchored extends IdentityStatus("singleton-t1-anchored")
}
object IdentityStatus {
  def fromTag(tag: String): Option[IdentityStatus] = values.find(_.tag == tag)
  given ReadWriter[IdentityStatus] = EnumCodec.tagged("IdentityStatus", values.toSeq, _.tag)
}

/** Whether a confidence value is known. Frozen tags. */
enum ConfidenceKind(val tag: String) {
  case Measured extends ConfidenceKind("measured")
  case Unknown extends ConfidenceKind("unknown")
}
object ConfidenceKind {
  def fromTag(tag: String): Option[ConfidenceKind] = values.find(_.tag == tag)
  given ReadWriter[ConfidenceKind] = EnumCodec.tagged("ConfidenceKind", values.toSeq, _.tag)
}

/**
 * Why a confidence is unknown. Frozen tags. `no-supporting-decision`: the researcher is a
 * singleton, so no pairwise match decision supports it and there is no decision confidence to
 * carry.
 */
enum ConfidenceUnknownReason(val tag: String) {
  case NoSupportingDecision extends ConfidenceUnknownReason("no-supporting-decision")
}
object ConfidenceUnknownReason {
  def fromTag(tag: String): Option[ConfidenceUnknownReason] = values.find(_.tag == tag)
  given ReadWriter[ConfidenceUnknownReason] =
    EnumCodec.tagged("ConfidenceUnknownReason", values.toSeq, _.tag)
}

/**
 * A decision confidence that is either measured or explicitly unknown (binding decision 5). Never a
 * default number, and never an absent field read as known: `kind` is required, `value` is present
 * exactly for `measured` and `unknownReason` exactly for `unknown`. Wire forms:
 * `{"kind":"measured","value":0.97,"unknownReason":null}` and
 * `{"kind":"unknown","value":null,"unknownReason":"no-supporting-decision"}`.
 *
 * A measured value is a match decision `confidence`: an uncalibrated, policy-defined number in
 * [0,1]. It is not an estimate of grouping quality, which is unevaluated.
 */
@allowUnknownKeys(false)
final case class DecisionConfidence(
    kind: ConfidenceKind,
    value: Option[Double],
    unknownReason: Option[ConfidenceUnknownReason]
) derives ReadWriter

object DecisionConfidence {
  def measured(v: Double): DecisionConfidence =
    DecisionConfidence(ConfidenceKind.Measured, Some(v), None)
  def unknown(reason: ConfidenceUnknownReason): DecisionConfidence =
    DecisionConfidence(ConfidenceKind.Unknown, None, Some(reason))

  def check(field: String, c: DecisionConfidence): Vector[ValidationError] =
    if (c == null) Vector(ValidationError.MissingField(field))
    else {
      val value = Option(c.value).getOrElse(None)
      val reason = Option(c.unknownReason).getOrElse(None)
      c.kind match {
        case null => Vector(ValidationError.MissingField(at(field, "kind")))
        case ConfidenceKind.Measured =>
          all(
            when(value.isEmpty)(ValidationError.MissingField(at(field, "value"))),
            value.toVector.flatMap(Validated.confidence(at(field, "value"), _)),
            when(reason.isDefined)(
              ValidationError.InvalidValue(at(field, "unknownReason"), "only for kind unknown")
            )
          )
        case ConfidenceKind.Unknown =>
          all(
            when(value.isDefined)(
              ValidationError.InvalidValue(
                at(field, "value"),
                "an unknown confidence has no value (never a default number)"
              )
            ),
            when(reason.isEmpty)(ValidationError.MissingField(at(field, "unknownReason")))
          )
      }
    }
}

/**
 * Identity confidence of one researcher, carried from the grouping run into metrics and evidence
 * (contract C5). It describes how the identity was formed; it is not a quality claim.
 *
 * @param status
 *   resolved, singleton or singleton-t1-anchored ([[IdentityStatus]])
 * @param anchorNamespace
 *   anchor namespace of the researcher ID (for example `orcid`, `dblp-pid`, `openalex-author`)
 * @param memberCount
 *   number of grouping members (mentions and source author profiles) that share the researcher ID
 *   (the assignments' `groupSize`)
 * @param collisionCodes
 *   the grouping reason codes of the researcher's members in the anchor-collision namespace
 *   ([[IdentityConfidence.CollisionCodePrefix]]), sorted by UTF-8 bytes without duplicates; empty
 *   when none
 * @param lowestDecisionConfidence
 *   measured: the lowest confidence of the ACCEPTED decisions that support the group (resolved
 *   only); unknown with `no-supporting-decision` for both singleton statuses
 */
@allowUnknownKeys(false)
final case class IdentityConfidence(
    status: IdentityStatus,
    anchorNamespace: String,
    memberCount: Int,
    collisionCodes: Vector[String],
    lowestDecisionConfidence: DecisionConfidence
) derives ReadWriter

object IdentityConfidence {

  /**
   * Namespace of the er-grouping.v1 reason codes that record an anchor collision (for example
   * `grouping.anchor-collision.orcid`). Carried as given; core does not interpret the suffix.
   */
  val CollisionCodePrefix = "grouping.anchor-collision."

  def check(field: String, c: IdentityConfidence): Vector[ValidationError] =
    if (c == null) Vector(ValidationError.MissingField(field))
    else {
      val codes = Option(c.collisionCodes).getOrElse(Vector.empty)
      val conf =
        DecisionConfidence.check(at(field, "lowestDecisionConfidence"), c.lowestDecisionConfidence)
      val kind = Option(c.lowestDecisionConfidence).map(_.kind).orNull
      all(
        AssignmentRules.token(at(field, "anchorNamespace"), c.anchorNamespace),
        when(c.collisionCodes == null)(ValidationError.MissingField(at(field, "collisionCodes"))),
        codes.zipWithIndex.flatMap { case (k, i) =>
          when(k == null || !StableId.isToken(k) || !k.startsWith(CollisionCodePrefix))(
            ValidationError.InvalidValue(
              at(field, s"collisionCodes[$i]"),
              s"must be a token starting with $CollisionCodePrefix"
            )
          )
        },
        Validated.strictlyAscending(at(field, "collisionCodes"), codes),
        conf,
        c.status match {
          case null => Vector(ValidationError.MissingField(at(field, "status")))
          case IdentityStatus.Resolved =>
            all(
              when(c.memberCount < 2)(
                ValidationError
                  .InvalidValue(at(field, "memberCount"), "resolved requires a group of >= 2")
              ),
              when(kind != null && kind != ConfidenceKind.Measured)(
                ValidationError.InvalidValue(
                  at(field, "lowestDecisionConfidence"),
                  "a resolved researcher rests on ACCEPTED decisions, so the confidence is measured"
                )
              )
            )
          case IdentityStatus.Singleton | IdentityStatus.SingletonT1Anchored =>
            all(
              when(c.memberCount != 1)(
                ValidationError.InvalidValue(at(field, "memberCount"), "a singleton has one member")
              ),
              when(
                c.lowestDecisionConfidence != null &&
                  c.lowestDecisionConfidence != DecisionConfidence
                    .unknown(ConfidenceUnknownReason.NoSupportingDecision)
              )(
                ValidationError.InvalidValue(
                  at(field, "lowestDecisionConfidence"),
                  "a singleton has no supporting decision: unknown with no-supporting-decision"
                )
              ),
              when(
                c.status == IdentityStatus.SingletonT1Anchored && c.anchorNamespace != null &&
                  !ResearcherAssignment.T1AnchorNamespaces.contains(c.anchorNamespace)
              )(
                ValidationError.InvalidValue(
                  at(field, "anchorNamespace"),
                  "a t1-anchored singleton is anchored on orcid or dblp-pid (decision D1)"
                )
              )
            )
        }
      )
    }

  /**
   * The identity confidence of `researcherId` from its grouping members (every assignment of the
   * grouping run that carries the researcher ID) and the decision confidences of the decision run
   * (`confidenceOf(decisionId)`). Refuses an incomplete member set (fewer members than their
   * `groupSize`), members that disagree, and a supporting decision without a confidence.
   */
  def derive(
      researcherId: String,
      members: Vector[ResearcherAssignment],
      confidenceOf: String => Option[Double]
  ): Either[Vector[ValidationError], IdentityConfidence] = {
    val f = s"identity[$researcherId]"
    def bad(why: String) = Left(Vector(ValidationError.InvalidValue(f, why)))
    val ms = Option(members).getOrElse(Vector.empty)
    if (ms.isEmpty || ms.exists(_ == null)) bad("no grouping member carries this researcher ID")
    else if (ms.exists(_.researcherId != Some(researcherId)))
      bad("a supplied member carries another researcher ID")
    else if (ms.map(m => (m.status, m.anchorNamespace, m.groupSize)).distinct.size != 1)
      bad("the members disagree on status, anchor or group size")
    else if (ms.size != ms.head.groupSize)
      bad(s"${ms.size} member(s) supplied, groupSize is ${ms.head.groupSize}")
    else {
      val head = ms.head
      val codes = ms
        .flatMap(m => Option(m.reasonCodes).getOrElse(Vector.empty))
        .filter(c => c != null && c.startsWith(CollisionCodePrefix))
        .distinct
        .sortWith((a, b) => Canonical.compareUtf8(a, b) < 0)
      val anchor = head.anchorNamespace.getOrElse("")
      head.status match {
        case AssignmentStatus.Resolved =>
          val supporting = ms.flatMap(_.supportingDecisionIds).distinct
          val found = supporting.map(id => id -> confidenceOf(id))
          found.collectFirst { case (id, None) => id } match {
            case Some(id) => bad(s"supporting decision $id has no confidence in the decision run")
            case None if supporting.isEmpty => bad("a resolved group has no supporting decision")
            case None =>
              Right(
                IdentityConfidence(
                  IdentityStatus.Resolved,
                  anchor,
                  head.groupSize,
                  codes,
                  DecisionConfidence.measured(found.flatMap(_._2).min)
                )
              )
          }
        case AssignmentStatus.Singleton =>
          Right(
            IdentityConfidence(
              if (ResearcherAssignment.requiresT1Anchor(head)) IdentityStatus.SingletonT1Anchored
              else IdentityStatus.Singleton,
              anchor,
              1,
              codes,
              DecisionConfidence.unknown(ConfidenceUnknownReason.NoSupportingDecision)
            )
          )
        case _ => bad("an unresolved member has no researcher ID")
      }
    }
  }
}

// ------------------------------------------------------------------ shared rules

/** Rules shared by the analysis output records. */
private[core] object AnalysisRules {

  /** Sorted by UTF-8 bytes, distinct, each an ID of `kind`; non-empty when `required`. */
  def sortedIds(
      field: String,
      kind: IdKind,
      ids: Vector[String],
      required: Boolean
  ): Vector[ValidationError] =
    if (ids == null) Vector(ValidationError.MissingField(field))
    else
      all(
        when(required && ids.isEmpty)(ValidationError.MissingField(field)),
        ids.zipWithIndex.flatMap { case (id, i) => StableId.check(kind, id, s"$field[$i]") },
        Validated.strictlyAscending(field, ids)
      )

  /** Every work is a DBLP work: counted works are DBLP representatives (decision E2). */
  def dblpWorks(field: String, ids: Vector[String]): Vector[ValidationError] =
    Option(ids).getOrElse(Vector.empty).zipWithIndex.flatMap { case (w, i) =>
      when(w != null && StableId.prefixKind(w).contains(IdKind.Work) && !AssignmentRules.isDblp(w))(
        ValidationError.InvalidValue(
          s"$field[$i]",
          "not a DBLP work: only counted (DBLP representative) works enter the graph"
        )
      )
    }

  /** Algorithm parameters: keys are tokens, values printable ASCII. */
  def parameters(field: String, m: SortedMap[String, String]): Vector[ValidationError] =
    if (m == null) Vector(ValidationError.MissingField(field))
    else
      m.toVector.flatMap { case (k, v) =>
        all(
          when(!StableId.isToken(k))(ValidationError.InvalidValue(s"$field.$k", "key not a token")),
          when(!Identifiers.isPrintableAscii(v))(
            ValidationError.InvalidValue(s"$field.$k", "value must be printable ASCII")
          )
        )
      }

  /**
   * The provenance of an analysis record: `scholarchain-derived`, `sourceRecordId` = the record's
   * subject, `policyVersion` = the graph policy (required), no model or prompt (graph analysis
   * makes no model call), and a run that is none of the lineage's input runs.
   */
  def provenance(
      p: String,
      subject: String,
      pr: Provenance,
      lineage: AnalysisLineage
  ): Vector[ValidationError] =
    all(
      CandidateRules.derivedCheck(at(p, "provenance.sourceDataset"), pr),
      Option(pr).toVector.flatMap { x =>
        all(
          when(subject != null && x.sourceRecordId != subject)(
            ValidationError.InvalidValue(at(p, "provenance.sourceRecordId"), s"must equal $subject")
          ),
          when(Option(x.policyVersion).flatten.isEmpty)(
            ValidationError.MissingField(at(p, "provenance.policyVersion"))
          ),
          when(
            Option(x.modelVersion).flatten.isDefined || Option(x.promptVersion).flatten.isDefined
          )(
            ValidationError.InvalidValue(
              at(p, "provenance.modelVersion,promptVersion"),
              "graph analysis makes no model call: both must be null"
            )
          ),
          Option(lineage).toVector.flatMap { l =>
            when(
              x.runId != null &&
                Set(l.groupingRunId, l.decisionRunId, l.inputRunId).contains(x.runId)
            )(
              ValidationError.InvalidValue(
                at(p, "provenance.runId"),
                "the analysis run differs from the grouping, decision and input runs"
              )
            )
          }
        )
      }
    )

  def policyOf(pr: Provenance): Option[String] =
    Option(pr).flatMap(x => Option(x.policyVersion).flatten)

  /** Window, lineage and provenance checks every analysis record shares. */
  def common(
      p: String,
      subject: String,
      window: TimeWindow,
      lineage: AnalysisLineage,
      pr: Provenance
  ): Vector[ValidationError] =
    all(
      TimeWindow.check(at(p, "window"), window),
      AnalysisLineage.check(at(p, "lineage"), lineage),
      provenance(p, subject, pr, lineage)
    )

  /** `expected` (an ID formula result) equals the stored `id`, when the formula applies. */
  def formula(
      field: String,
      id: String,
      expected: Either[ValidationError, String]
  ): Vector[ValidationError] =
    expected match {
      case Right(e) => when(e != id)(ValidationError.InvalidValue(field, s"expected $e"))
      case Left(_) => Vector.empty
    }
}

import AnalysisRules.*

// ------------------------------------------------------------------ C1 researcher metrics v2

/**
 * Collaboration metrics of one identified researcher in one configured window (schema
 * `researcher-metrics.v2`, contract C1). One record per identified researcher with at least one
 * counted work in the window. The six specification quantities are separate fields, never combined
 * into one score. With, for every counted work w of the researcher in the window, `n_w` =
 * `teamSize`, `i_w` = number of identified researchers on the team (`researcherIds`), `u_w` =
 * number of unresolved mentions (`unresolvedMentionIds`), and `n_w = i_w + u_w`:
 *   - P_r `publicationCount` = number of such works (>= 1);
 *   - T_r `totalCoauthorOccurrences` = sum of (n_w - 1), unresolved coauthors included;
 *   - `unresolvedCoauthorOccurrences` = sum of u_w (the unresolved part of T_r);
 *   - `worksWithUnresolvedCoauthors` = number of works with u_w > 0;
 *   - A_r `averageCoauthorsPerPaper` = T_r.toDouble / P_r.toDouble (IEEE-754 division), unresolved
 *     included;
 *   - M_r `maximumCoauthorsOnOnePaper` = max of (n_w - 1), unresolved included, attained by
 *     `maximumTeamWorkIds` (sorted by UTF-8 bytes);
 *   - C_r `distinctCoauthorCount` = number of distinct identified coauthors (identified only);
 *   - W_r `weightedDegree` = sum over identified coauthors c of the joint publication count of the
 *     edge (r, c) in the window ([[CoauthorEdgeV2]]), identified only. It equals T_r minus the
 *     unresolved occurrences.
 *
 * @param identity
 *   the researcher's identity confidence from the grouping run (C5)
 * @param provenance
 *   `scholarchain-derived`; `sourceRecordId` = `researcherId`; `runId` = the analysis run;
 *   `policyVersion` = the graph policy (`graph.policyVersion`)
 */
@allowUnknownKeys(false)
final case class ResearcherMetricsV2(
    schemaVersion: String,
    researcherId: String,
    window: TimeWindow,
    publicationCount: Long,
    distinctCoauthorCount: Long,
    totalCoauthorOccurrences: Long,
    unresolvedCoauthorOccurrences: Long,
    worksWithUnresolvedCoauthors: Long,
    averageCoauthorsPerPaper: Double,
    maximumCoauthorsOnOnePaper: Long,
    maximumTeamWorkIds: Vector[String],
    weightedDegree: Long,
    identity: IdentityConfidence,
    lineage: AnalysisLineage,
    provenance: Provenance
) derives ReadWriter

object ResearcherMetricsV2 {

  /** A_r as the contract defines it: T_r.toDouble / P_r.toDouble. */
  def average(total: Long, publications: Long): Double = total.toDouble / publications.toDouble

  given contract: RecordContract[ResearcherMetricsV2] =
    new RecordContract[ResearcherMetricsV2](
      "ResearcherMetricsV2",
      SchemaVersions.ResearcherMetricsV2,
      true
    ) {
      def schemaVersionOf(t: ResearcherMetricsV2): String = t.schemaVersion
      def provenanceOf(t: ResearcherMetricsV2): Provenance = t.provenance
      protected def fields(t: ResearcherMetricsV2, p: String): Vector[ValidationError] = {
        val maxWorks = Option(t.maximumTeamWorkIds).getOrElse(Vector.empty)
        val pr = t.publicationCount
        val total = t.totalCoauthorOccurrences
        val unresolved = t.unresolvedCoauthorOccurrences
        val identified = total - unresolved
        val counts = all(
          when(pr < 1)(ValidationError.InvalidValue(at(p, "publicationCount"), "must be >= 1")),
          Validated.nonNegative(at(p, "distinctCoauthorCount"), t.distinctCoauthorCount),
          Validated.nonNegative(at(p, "totalCoauthorOccurrences"), total),
          Validated.nonNegative(at(p, "unresolvedCoauthorOccurrences"), unresolved),
          Validated
            .nonNegative(at(p, "worksWithUnresolvedCoauthors"), t.worksWithUnresolvedCoauthors),
          Validated.nonNegative(at(p, "maximumCoauthorsOnOnePaper"), t.maximumCoauthorsOnOnePaper),
          Validated.nonNegative(at(p, "weightedDegree"), t.weightedDegree)
        )
        val relations =
          if (counts.nonEmpty) Vector.empty
          else
            all(
              when(unresolved > total)(
                ValidationError.InvalidValue(
                  at(p, "unresolvedCoauthorOccurrences"),
                  "exceeds totalCoauthorOccurrences"
                )
              ),
              when(t.weightedDegree != identified)(
                ValidationError.InvalidValue(
                  at(p, "weightedDegree"),
                  s"W_r counts identified coauthor occurrences only: expected $identified (T_r minus unresolved)"
                )
              ),
              when(t.distinctCoauthorCount > identified)(
                ValidationError.InvalidValue(
                  at(p, "distinctCoauthorCount"),
                  "C_r counts identified coauthors only: exceeds identified occurrences"
                )
              ),
              when(t.worksWithUnresolvedCoauthors > pr)(
                ValidationError
                  .InvalidValue(at(p, "worksWithUnresolvedCoauthors"), "exceeds publicationCount")
              ),
              when(t.worksWithUnresolvedCoauthors > unresolved)(
                ValidationError.InvalidValue(
                  at(p, "worksWithUnresolvedCoauthors"),
                  "exceeds unresolvedCoauthorOccurrences"
                )
              ),
              when(unresolved > 0 && t.worksWithUnresolvedCoauthors == 0)(
                ValidationError.InvalidValue(
                  at(p, "worksWithUnresolvedCoauthors"),
                  "unresolved occurrences lie on at least one work"
                )
              ),
              when(t.maximumCoauthorsOnOnePaper > total)(
                ValidationError.InvalidValue(
                  at(p, "maximumCoauthorsOnOnePaper"),
                  "exceeds totalCoauthorOccurrences"
                )
              ),
              when(t.maximumCoauthorsOnOnePaper.toDouble * pr.toDouble < total.toDouble)(
                ValidationError.InvalidValue(
                  at(p, "maximumCoauthorsOnOnePaper"),
                  "M_r is below the average A_r"
                )
              ),
              when(t.averageCoauthorsPerPaper != average(total, pr))(
                ValidationError.InvalidValue(
                  at(p, "averageCoauthorsPerPaper"),
                  s"A_r = T_r / P_r: expected ${average(total, pr)}"
                )
              ),
              when(maxWorks.size.toLong > pr)(
                ValidationError.InvalidValue(
                  at(p, "maximumTeamWorkIds"),
                  "more works than publicationCount"
                )
              )
            )
        all(
          StableId.check(IdKind.Researcher, t.researcherId, at(p, "researcherId")),
          counts,
          relations,
          Validated.finite(at(p, "averageCoauthorsPerPaper"), t.averageCoauthorsPerPaper),
          sortedIds(at(p, "maximumTeamWorkIds"), IdKind.Work, t.maximumTeamWorkIds, true),
          dblpWorks(at(p, "maximumTeamWorkIds"), maxWorks),
          IdentityConfidence.check(at(p, "identity"), t.identity),
          common(p, t.researcherId, t.window, t.lineage, t.provenance)
        )
      }
    }
}

// ------------------------------------------------------------------ C2 coauthor edge v2

/** Joint publications of an edge in one year. */
@allowUnknownKeys(false)
final case class YearCount(year: Int, count: Long) derives ReadWriter

/**
 * Consolidated undirected coauthorship edge between two identified researchers in one configured
 * window (schema `coauthor-edge.v2`, contract C2). One record per canonical pair and window that
 * share at least one counted work in the window. Unresolved mentions never form an edge.
 *
 * Compared with v1 ([[CoauthorEdge]], still decoded unchanged): `representativeWorkIds` (a bounded
 * sample) becomes `supportingWorkIds` (every supporting counted work), and the per-year weights
 * inside the window, the lineage and identified-only endpoints are added. The edge ID formula is
 * the v1 formula ([[CoauthorEdgeId]]: canonical pair, window, graph policy), so v1 and v2 records
 * of one pair, window and policy have the same edge ID.
 *
 * @param jointPublicationCount
 *   the edge weight: number of counted works in the window whose team holds both researchers
 * @param publicationsByYear
 *   joint publications per year: years strictly ascending, inside the window, counts >= 1, summing
 *   to `jointPublicationCount`; the first and last years are the collaboration years
 * @param supportingWorkIds
 *   every supporting counted work, sorted by UTF-8 bytes without duplicates; exactly
 *   `jointPublicationCount` of them
 * @param provenance
 *   `scholarchain-derived`; `sourceRecordId` = `edgeId`; `policyVersion` = the graph policy (an
 *   input of the edge ID)
 */
@allowUnknownKeys(false)
final case class CoauthorEdgeV2(
    schemaVersion: String,
    edgeId: String,
    researcherA: String,
    researcherB: String,
    window: TimeWindow,
    jointPublicationCount: Long,
    firstCollaborationYear: Int,
    lastCollaborationYear: Int,
    publicationsByYear: Vector[YearCount],
    supportingWorkIds: Vector[String],
    lineage: AnalysisLineage,
    provenance: Provenance
) derives ReadWriter

object CoauthorEdgeV2 {

  /**
   * Builds an edge: orders the pair canonically, sorts the supporting works and computes the edge
   * ID (with `graphPolicyVersion`), the count and the collaboration years from the per-year counts.
   * `provenanceOf` receives the computed edge ID (the provenance's `sourceRecordId`); the record
   * contract then requires its `policyVersion` to give the same edge ID.
   */
  def build(
      x: String,
      y: String,
      window: TimeWindow,
      publicationsByYear: Vector[YearCount],
      supportingWorkIds: Vector[String],
      lineage: AnalysisLineage,
      graphPolicyVersion: String,
      provenanceOf: String => Provenance
  ): Either[Vector[ValidationError], CoauthorEdgeV2] = {
    val years = Option(publicationsByYear).getOrElse(Vector.empty).filter(_ != null).sortBy(_.year)
    val built = for {
      pair <- CanonicalPair.researchers(x, y)
      id <- CoauthorEdgeId.of(pair, window, graphPolicyVersion)
      first <- years.headOption.toRight(ValidationError.MissingField("publicationsByYear"))
      provenance = provenanceOf(id)
    } yield CoauthorEdgeV2(
      SchemaVersions.CoauthorEdgeV2,
      id,
      pair.first,
      pair.second,
      window,
      years.map(_.count).sum,
      first.year,
      years.last.year,
      years,
      Option(supportingWorkIds)
        .getOrElse(Vector.empty)
        .sortWith((a, b) => Canonical.compareUtf8(a, b) < 0),
      lineage,
      provenance
    )
    built match {
      case Left(e) => Left(Vector(e))
      case Right(edge) =>
        val errors = contract.validate(edge)
        if (errors.isEmpty) Right(edge) else Left(errors)
    }
  }

  given contract: RecordContract[CoauthorEdgeV2] =
    new RecordContract[CoauthorEdgeV2]("CoauthorEdgeV2", SchemaVersions.CoauthorEdgeV2, true) {
      def schemaVersionOf(t: CoauthorEdgeV2): String = t.schemaVersion
      def provenanceOf(t: CoauthorEdgeV2): Provenance = t.provenance
      protected def fields(t: CoauthorEdgeV2, p: String): Vector[ValidationError] = {
        val years = Option(t.publicationsByYear).getOrElse(Vector.empty)
        val works = Option(t.supportingWorkIds).getOrElse(Vector.empty)
        val windowErrors = TimeWindow.check(at(p, "window"), t.window)
        val policy = policyOf(t.provenance)
        val yearErrors =
          if (t.publicationsByYear == null)
            Vector(ValidationError.MissingField(at(p, "publicationsByYear")))
          else
            years.zipWithIndex.flatMap { case (y, i) =>
              val f = at(p, s"publicationsByYear[$i]")
              if (y == null) Vector(ValidationError.MissingField(f))
              else
                all(
                  when(y.count < 1)(ValidationError.InvalidValue(at(f, "count"), "must be >= 1")),
                  when(windowErrors.isEmpty && !t.window.contains(y.year))(
                    ValidationError.InvalidValue(at(f, "year"), "outside the edge window")
                  )
                )
            } ++ when(years.isEmpty)(ValidationError.MissingField(at(p, "publicationsByYear"))) ++
              when(
                years.forall(_ != null) &&
                  years.sliding(2).exists(w => w.size == 2 && w(0).year >= w(1).year)
              )(
                ValidationError.InvalidValue(
                  at(p, "publicationsByYear"),
                  "years must be strictly ascending"
                )
              )
        val good = years.filter(_ != null)
        all(
          StableId.check(IdKind.Researcher, t.researcherA, at(p, "researcherA")),
          StableId.check(IdKind.Researcher, t.researcherB, at(p, "researcherB")),
          CanonicalPair.checkStored(at(p, "researcherA,researcherB"), t.researcherA, t.researcherB),
          when(t.jointPublicationCount < 1)(
            ValidationError.InvalidValue(at(p, "jointPublicationCount"), "must be >= 1")
          ),
          yearErrors,
          when(yearErrors.isEmpty && good.map(_.count).sum != t.jointPublicationCount)(
            ValidationError.InvalidValue(
              at(p, "publicationsByYear"),
              "counts must sum to jointPublicationCount"
            )
          ),
          when(
            yearErrors.isEmpty && good.nonEmpty &&
              (good.head.year != t.firstCollaborationYear || good.last.year != t.lastCollaborationYear)
          )(
            ValidationError.InvalidValue(
              at(p, "firstCollaborationYear,lastCollaborationYear"),
              "must be the first and last years of publicationsByYear"
            )
          ),
          sortedIds(at(p, "supportingWorkIds"), IdKind.Work, t.supportingWorkIds, true),
          dblpWorks(at(p, "supportingWorkIds"), works),
          when(t.supportingWorkIds != null && works.size.toLong != t.jointPublicationCount)(
            ValidationError.InvalidValue(
              at(p, "supportingWorkIds"),
              "exactly one supporting counted work per joint publication"
            )
          ),
          StableId.check(IdKind.CoauthorEdge, t.edgeId, at(p, "edgeId")),
          (CanonicalPair.of(t.researcherA, t.researcherB), policy) match {
            case (Right(pair), Some(pv)) if windowErrors.isEmpty && pair.first == t.researcherA =>
              formula(at(p, "edgeId"), t.edgeId, CoauthorEdgeId.of(pair, t.window, pv))
            case _ => Vector.empty
          },
          common(p, t.edgeId, t.window, t.lineage, t.provenance)
        )
      }
    }
}

// ------------------------------------------------------------------ C3 graph structure

/**
 * Connected-component membership of one researcher in one window (schema
 * `component-membership.v1`). Every researcher with a metrics record in the window has exactly one
 * membership; a researcher without identified coauthors is a component of size 1.
 *
 * @param componentId
 *   [[ComponentId.of]](window, graph policy, members): independent of the algorithm's labels
 * @param componentSize
 *   number of researchers in the component
 */
@allowUnknownKeys(false)
final case class ComponentMembership(
    schemaVersion: String,
    researcherId: String,
    window: TimeWindow,
    componentId: String,
    componentSize: Long,
    lineage: AnalysisLineage,
    provenance: Provenance
) derives ReadWriter

object ComponentMembership {
  given contract: RecordContract[ComponentMembership] =
    new RecordContract[ComponentMembership](
      "ComponentMembership",
      SchemaVersions.ComponentMembership,
      true
    ) {
      def schemaVersionOf(t: ComponentMembership): String = t.schemaVersion
      def provenanceOf(t: ComponentMembership): Provenance = t.provenance
      protected def fields(t: ComponentMembership, p: String): Vector[ValidationError] =
        all(
          StableId.check(IdKind.Researcher, t.researcherId, at(p, "researcherId")),
          StableId.check(IdKind.Component, t.componentId, at(p, "componentId")),
          when(t.componentSize < 1)(
            ValidationError.InvalidValue(at(p, "componentSize"), "must be >= 1")
          ),
          common(p, t.researcherId, t.window, t.lineage, t.provenance)
        )
    }
}

/**
 * Community membership of one researcher in one window (schema `community-membership.v1`). The
 * algorithm and its parameters are recorded on every record and equal the configured values
 * (`community.algorithm`, `community.maxIterations`, `community.randomSeed` when set). A community
 * lies inside one connected component.
 *
 * @param communityId
 *   [[CommunityId.of]](window, graph policy, algorithm, parameters, members)
 * @param componentId
 *   the component that holds the community
 */
@allowUnknownKeys(false)
final case class CommunityMembership(
    schemaVersion: String,
    researcherId: String,
    window: TimeWindow,
    communityId: String,
    communitySize: Long,
    componentId: String,
    algorithm: String,
    parameters: SortedMap[String, String],
    lineage: AnalysisLineage,
    provenance: Provenance
) derives ReadWriter

object CommunityMembership {
  given contract: RecordContract[CommunityMembership] =
    new RecordContract[CommunityMembership](
      "CommunityMembership",
      SchemaVersions.CommunityMembership,
      true
    ) {
      def schemaVersionOf(t: CommunityMembership): String = t.schemaVersion
      def provenanceOf(t: CommunityMembership): Provenance = t.provenance
      protected def fields(t: CommunityMembership, p: String): Vector[ValidationError] =
        all(
          StableId.check(IdKind.Researcher, t.researcherId, at(p, "researcherId")),
          StableId.check(IdKind.Community, t.communityId, at(p, "communityId")),
          StableId.check(IdKind.Component, t.componentId, at(p, "componentId")),
          when(t.communitySize < 1)(
            ValidationError.InvalidValue(at(p, "communitySize"), "must be >= 1")
          ),
          AssignmentRules.token(at(p, "algorithm"), t.algorithm),
          parameters(at(p, "parameters"), t.parameters),
          common(p, t.researcherId, t.window, t.lineage, t.provenance)
        )
    }
}

/**
 * Bridge measures of one researcher in one window (schema `bridge-measure.v1`). Degrees count
 * distinct identified coauthors (C_r); unresolved occurrences never enter.
 *
 *   - `score`: the configured bridge algorithm's score (`bridge.algorithm`, with `parameters` =
 *     `bridge.sampleSize`, `bridge.randomSeed`, `bridge.maxIterations`); finite and >= 0. Its value
 *     is algorithm-defined and not re-derived by core.
 *   - `crossCommunityDegree`: identified coauthors in another community of the window.
 *   - `communitiesReached`: distinct other communities among those coauthors.
 *   - `participationCoefficient` = 0.0 when `degree` is 0, otherwise `1.0 - (S.toDouble /
 *     (degree.toDouble * degree.toDouble))`, where S is the exact integer sum over communities (the
 *     researcher's own included) of the squared number of the researcher's coauthors in that
 *     community. Computed from integers in that order, so it is exactly reproducible.
 */
@allowUnknownKeys(false)
final case class BridgeMeasure(
    schemaVersion: String,
    researcherId: String,
    window: TimeWindow,
    communityId: String,
    measure: String,
    parameters: SortedMap[String, String],
    score: Double,
    degree: Long,
    crossCommunityDegree: Long,
    communitiesReached: Long,
    participationCoefficient: Double,
    lineage: AnalysisLineage,
    provenance: Provenance
) derives ReadWriter

object BridgeMeasure {

  /** The participation coefficient as the contract defines it. */
  def participation(degree: Long, sumOfSquares: Long): Double =
    if (degree == 0) 0.0 else 1.0 - (sumOfSquares.toDouble / (degree.toDouble * degree.toDouble))

  given contract: RecordContract[BridgeMeasure] =
    new RecordContract[BridgeMeasure]("BridgeMeasure", SchemaVersions.BridgeMeasure, true) {
      def schemaVersionOf(t: BridgeMeasure): String = t.schemaVersion
      def provenanceOf(t: BridgeMeasure): Provenance = t.provenance
      protected def fields(t: BridgeMeasure, p: String): Vector[ValidationError] =
        all(
          StableId.check(IdKind.Researcher, t.researcherId, at(p, "researcherId")),
          StableId.check(IdKind.Community, t.communityId, at(p, "communityId")),
          AssignmentRules.token(at(p, "measure"), t.measure),
          parameters(at(p, "parameters"), t.parameters),
          Validated.nonNegativeD(at(p, "score"), t.score),
          Validated.nonNegative(at(p, "degree"), t.degree),
          Validated.nonNegative(at(p, "crossCommunityDegree"), t.crossCommunityDegree),
          Validated.nonNegative(at(p, "communitiesReached"), t.communitiesReached),
          when(t.crossCommunityDegree > t.degree)(
            ValidationError.InvalidValue(at(p, "crossCommunityDegree"), "exceeds degree")
          ),
          when(t.communitiesReached > t.crossCommunityDegree)(
            ValidationError
              .InvalidValue(at(p, "communitiesReached"), "exceeds crossCommunityDegree")
          ),
          when(t.crossCommunityDegree > 0 && t.communitiesReached == 0)(
            ValidationError.InvalidValue(
              at(p, "communitiesReached"),
              "a cross-community coauthor reaches at least one community"
            )
          ),
          when(
            t.participationCoefficient.isNaN || t.participationCoefficient < 0.0 ||
              t.participationCoefficient >= 1.0
          )(
            ValidationError.InvalidValue(at(p, "participationCoefficient"), "must lie in [0, 1)")
          ),
          when(t.crossCommunityDegree == 0 && t.participationCoefficient != 0.0)(
            ValidationError.InvalidValue(
              at(p, "participationCoefficient"),
              "0.0 when every coauthor is in the researcher's own community"
            )
          ),
          common(p, t.researcherId, t.window, t.lineage, t.provenance)
        )
    }
}

/**
 * k-core coreness of one researcher in one window (schema `coreness.v1`): the largest k such that
 * the researcher belongs to a subgraph in which every researcher has at least k identified
 * coauthors (unweighted, simple graph of the window's edges). `degree` is C_r.
 */
@allowUnknownKeys(false)
final case class Coreness(
    schemaVersion: String,
    researcherId: String,
    window: TimeWindow,
    coreness: Long,
    degree: Long,
    lineage: AnalysisLineage,
    provenance: Provenance
) derives ReadWriter

object Coreness {
  given contract: RecordContract[Coreness] =
    new RecordContract[Coreness]("Coreness", SchemaVersions.Coreness, true) {
      def schemaVersionOf(t: Coreness): String = t.schemaVersion
      def provenanceOf(t: Coreness): Provenance = t.provenance
      protected def fields(t: Coreness, p: String): Vector[ValidationError] =
        all(
          StableId.check(IdKind.Researcher, t.researcherId, at(p, "researcherId")),
          Validated.nonNegative(at(p, "coreness"), t.coreness),
          Validated.nonNegative(at(p, "degree"), t.degree),
          when(t.coreness > t.degree)(
            ValidationError.InvalidValue(at(p, "coreness"), "exceeds degree")
          ),
          common(p, t.researcherId, t.window, t.lineage, t.provenance)
        )
    }
}

/** The bounds a path record was computed under (configured, recorded on every record). */
@allowUnknownKeys(false)
final case class PathBounds(maxHops: Int, maxPathsPerPair: Int, maxEvidenceWorksPerHop: Int)
    derives ReadWriter

object PathBounds {
  def check(field: String, b: PathBounds): Vector[ValidationError] =
    if (b == null) Vector(ValidationError.MissingField(field))
    else
      all(
        when(b.maxHops < 1)(ValidationError.InvalidValue(at(field, "maxHops"), "must be >= 1")),
        when(b.maxPathsPerPair < 1)(
          ValidationError.InvalidValue(at(field, "maxPathsPerPair"), "must be >= 1")
        ),
        when(b.maxEvidenceWorksPerHop < 1)(
          ValidationError.InvalidValue(at(field, "maxEvidenceWorksPerHop"), "must be >= 1")
        )
      )
}

/**
 * One hop of an evidence path: the coauthor edge between two consecutive nodes and the counted
 * works shown as its evidence (sorted, distinct, at most `maxEvidenceWorksPerHop`, every one a
 * supporting work of the edge).
 */
@allowUnknownKeys(false)
final case class PathHop(edgeId: String, evidenceWorkIds: Vector[String]) derives ReadWriter

/**
 * A bounded shortest-path support record between two identified researchers in one window (schema
 * `evidence-path.v1`, contracts C3 and C6). The pair is stored in canonical order; `nodeIds` runs
 * from `sourceResearcherId` to `targetResearcherId` through distinct researchers, and `hopCount` is
 * the shortest-path distance of the pair in the window's graph. Every ID resolves to stored
 * evidence: each hop's `edgeId` is the [[CoauthorEdgeV2]] of its two nodes in the same window
 * ([[CoauthorEdgeId]], checked here), its works are supporting works of that edge, and each node
 * has a metrics record ([[AnalysisValidation.checkPaths]]).
 *
 * Which paths are stored (DE-P6-02 repair 01, CR-P6-13 finding 4): only for the pairs of the run's
 * [[BenchmarkPairList]], in every configured window (`graph.windows`). For one pair and window the
 * stored paths are exactly the first `maxPathsPerPair` shortest paths (at most `maxHops` hops) in
 * [[EvidencePath.nodeOrder]], with `ordinal` = their position in that order; none when the pair is
 * not connected within `maxHops` in the window. [[AnalysisValidation.selectShortestPaths]] is the
 * executable definition.
 *
 * @param pathId
 *   [[EvidencePathId.of]](window, graph policy, nodeIds)
 * @param ordinal
 *   0-based position among the stored shortest paths of the pair and window, in
 *   [[EvidencePath.nodeOrder]] of `nodeIds`
 * @param bounds
 *   the configured bounds: `hopCount <= maxHops`, `ordinal < maxPathsPerPair`
 * @param nodeIdentities
 *   the identity confidence of each node (C5), in node order
 */
@allowUnknownKeys(false)
final case class EvidencePath(
    schemaVersion: String,
    pathId: String,
    window: TimeWindow,
    sourceResearcherId: String,
    targetResearcherId: String,
    ordinal: Int,
    hopCount: Int,
    nodeIds: Vector[String],
    hops: Vector[PathHop],
    nodeIdentities: Vector[IdentityConfidence],
    bounds: PathBounds,
    lineage: AnalysisLineage,
    provenance: Provenance
) derives ReadWriter

object EvidencePath {

  /**
   * The stable total order of stored paths: lexicographic on the canonical researcher-ID node
   * sequence (`nodeIds`, source first), comparing element by element by unsigned UTF-8 bytes
   * ([[Canonical.compareUtf8]]); the first differing element decides, and a proper prefix sorts
   * first. All shortest paths of one pair have the same length and share the source and target, so
   * in effect the intermediate nodes decide, first hop first. Negative, zero or positive.
   */
  def compareNodes(a: Vector[String], b: Vector[String]): Int = {
    val n = math.min(a.size, b.size)
    var i = 0
    var c = 0
    while (c == 0 && i < n) { c = Canonical.compareUtf8(a(i), b(i)); i += 1 }
    if (c != 0) c else Integer.compare(a.size, b.size)
  }

  /** [[compareNodes]] as an Ordering. */
  val nodeOrder: Ordering[Vector[String]] = Ordering.fromLessThan((a, b) => compareNodes(a, b) < 0)

  given contract: RecordContract[EvidencePath] =
    new RecordContract[EvidencePath]("EvidencePath", SchemaVersions.EvidencePath, true) {
      def schemaVersionOf(t: EvidencePath): String = t.schemaVersion
      def provenanceOf(t: EvidencePath): Provenance = t.provenance
      protected def fields(t: EvidencePath, p: String): Vector[ValidationError] = {
        val nodes = Option(t.nodeIds).getOrElse(Vector.empty)
        val hops = Option(t.hops).getOrElse(Vector.empty)
        val ids = Option(t.nodeIdentities).getOrElse(Vector.empty)
        val windowOk = TimeWindow.check("window", t.window).isEmpty
        val policy = policyOf(t.provenance)
        val nodeErrors =
          if (t.nodeIds == null) Vector(ValidationError.MissingField(at(p, "nodeIds")))
          else
            nodes.zipWithIndex.flatMap { case (n, i) =>
              StableId.check(IdKind.Researcher, n, at(p, s"nodeIds[$i]"))
            }
        val bounds = Option(t.bounds)
        all(
          StableId.check(IdKind.Researcher, t.sourceResearcherId, at(p, "sourceResearcherId")),
          StableId.check(IdKind.Researcher, t.targetResearcherId, at(p, "targetResearcherId")),
          CanonicalPair.checkStored(
            at(p, "sourceResearcherId,targetResearcherId"),
            t.sourceResearcherId,
            t.targetResearcherId
          ),
          PathBounds.check(at(p, "bounds"), t.bounds),
          when(t.hopCount < 1)(ValidationError.InvalidValue(at(p, "hopCount"), "must be >= 1")),
          bounds.toVector.flatMap { b =>
            all(
              when(t.hopCount > b.maxHops)(
                ValidationError.InvalidValue(at(p, "hopCount"), s"exceeds the bound ${b.maxHops}")
              ),
              when(t.ordinal >= b.maxPathsPerPair)(
                ValidationError.InvalidValue(
                  at(p, "ordinal"),
                  s"at most ${b.maxPathsPerPair} path(s) per pair"
                )
              )
            )
          },
          when(t.ordinal < 0)(ValidationError.InvalidValue(at(p, "ordinal"), "negative")),
          nodeErrors,
          when(t.nodeIds != null && nodes.size != t.hopCount + 1)(
            ValidationError.InvalidValue(at(p, "nodeIds"), "a path of n hops has n + 1 nodes")
          ),
          when(nodes.distinct.size != nodes.size)(
            ValidationError.InvalidValue(at(p, "nodeIds"), "a shortest path visits distinct nodes")
          ),
          when(
            nodes.nonEmpty &&
              (nodes.head != t.sourceResearcherId || nodes.last != t.targetResearcherId)
          )(
            ValidationError.InvalidValue(
              at(p, "nodeIds"),
              "must run from sourceResearcherId to targetResearcherId"
            )
          ),
          when(t.hops == null)(ValidationError.MissingField(at(p, "hops"))),
          when(t.hops != null && hops.size != t.hopCount)(
            ValidationError.InvalidValue(at(p, "hops"), "one hop record per hop")
          ),
          hops.zipWithIndex.flatMap { case (h, i) =>
            val f = at(p, s"hops[$i]")
            if (h == null) Vector(ValidationError.MissingField(f))
            else
              all(
                StableId.check(IdKind.CoauthorEdge, h.edgeId, at(f, "edgeId")),
                sortedIds(at(f, "evidenceWorkIds"), IdKind.Work, h.evidenceWorkIds, true),
                dblpWorks(at(f, "evidenceWorkIds"), h.evidenceWorkIds),
                bounds.toVector.flatMap { b =>
                  when(Option(h.evidenceWorkIds).exists(_.size > b.maxEvidenceWorksPerHop))(
                    ValidationError.InvalidValue(
                      at(f, "evidenceWorkIds"),
                      s"at most ${b.maxEvidenceWorksPerHop} evidence work(s) per hop"
                    )
                  )
                },
                (policy, nodeErrors.isEmpty && windowOk && i + 1 < nodes.size) match {
                  case (Some(pv), true) =>
                    CanonicalPair.of(nodes(i), nodes(i + 1)) match {
                      case Right(pair) =>
                        formula(at(f, "edgeId"), h.edgeId, CoauthorEdgeId.of(pair, t.window, pv))
                      case Left(e) => Vector(e)
                    }
                  case _ => Vector.empty
                }
              )
          },
          when(t.nodeIdentities == null)(ValidationError.MissingField(at(p, "nodeIdentities"))),
          when(t.nodeIdentities != null && ids.size != nodes.size)(
            ValidationError.InvalidValue(at(p, "nodeIdentities"), "one identity per node")
          ),
          ids.zipWithIndex.flatMap { case (c, i) =>
            IdentityConfidence.check(at(p, s"nodeIdentities[$i]"), c)
          },
          StableId.check(IdKind.EvidencePath, t.pathId, at(p, "pathId")),
          (policy, nodeErrors.isEmpty && windowOk) match {
            case (Some(pv), true) =>
              formula(at(p, "pathId"), t.pathId, EvidencePathId.of(t.window, pv, nodes))
            case _ => Vector.empty
          },
          common(p, t.pathId, t.window, t.lineage, t.provenance)
        )
      }
    }
}

// ------------------------------------------------------------------ benchmark pairs (stored paths)

/** One configured researcher pair, stored in canonical order (`researcherA < researcherB`). */
@allowUnknownKeys(false)
final case class BenchmarkPair(researcherA: String, researcherB: String) derives ReadWriter

/**
 * The explicit, versioned list of researcher pairs for which an analysis run stores evidence paths
 * (schema `benchmark-pairs.v1`; DE-P6-02 repair 01, CR-P6-13 finding 4; human decision "use an
 * explicit, versioned list of benchmark pairs for stored paths"). An input of the analysis run, not
 * an output: the analysis run manifest binds it through the parameter `benchmarkPairsDigest` =
 * [[BenchmarkPairList.digest]], so another list is another run ID
 * ([[AnalysisValidation.checkRunChain]]). Windows are not part of the list: paths are stored for
 * every pair in every configured window (`graph.windows`).
 *
 * @param listVersion
 *   version token of the list (for example `benchmark-pairs-dev.v1`); a changed pair set is a new
 *   version
 * @param pairs
 *   non-empty; each pair canonical (two different researcher IDs, the smaller first by UTF-8
 *   bytes); pairs strictly ascending by (researcherA, researcherB), so no duplicate
 * @param provenance
 *   `scholarchain-derived`; `sourceRecordId` = `listVersion`; `runId` = the grouping run whose
 *   researcher IDs the pairs name (it must be the analysis run's grouping run); `sourceSchemaVersion`
 *   = the researcher-assignment schema the IDs come from; no model or prompt
 */
@allowUnknownKeys(false)
final case class BenchmarkPairList(
    schemaVersion: String,
    listVersion: String,
    pairs: Vector[BenchmarkPair],
    provenance: Provenance
) derives ReadWriter

object BenchmarkPairList {

  /** The value of the analysis manifest parameter `benchmarkPairsDigest`. */
  def digest(l: BenchmarkPairList): String = Canonical.sha256HexUtf8(Json.write(l))

  private def pairLess(x: BenchmarkPair, y: BenchmarkPair): Boolean = {
    val c = Canonical.compareUtf8(x.researcherA, y.researcherA)
    if (c != 0) c < 0 else Canonical.compareUtf8(x.researcherB, y.researcherB) < 0
  }

  given contract: RecordContract[BenchmarkPairList] =
    new RecordContract[BenchmarkPairList](
      "BenchmarkPairList",
      SchemaVersions.BenchmarkPairList,
      true
    ) {
      def schemaVersionOf(t: BenchmarkPairList): String = t.schemaVersion
      def provenanceOf(t: BenchmarkPairList): Provenance = t.provenance
      protected def fields(t: BenchmarkPairList, p: String): Vector[ValidationError] = {
        val pairs = Option(t.pairs).getOrElse(Vector.empty)
        val perPair = pairs.zipWithIndex.flatMap { case (x, i) =>
          val f = at(p, s"pairs[$i]")
          if (x == null) Vector(ValidationError.MissingField(f))
          else
            all(
              StableId.check(IdKind.Researcher, x.researcherA, at(f, "researcherA")),
              StableId.check(IdKind.Researcher, x.researcherB, at(f, "researcherB")),
              CanonicalPair
                .checkStored(at(f, "researcherA,researcherB"), x.researcherA, x.researcherB)
            )
        }
        all(
          AssignmentRules.token(at(p, "listVersion"), t.listVersion),
          when(t.pairs == null || pairs.isEmpty)(ValidationError.MissingField(at(p, "pairs"))),
          perPair,
          when(
            perPair.isEmpty &&
              pairs.sliding(2).exists(w => w.size == 2 && !pairLess(w(0), w(1)))
          )(
            ValidationError.InvalidValue(
              at(p, "pairs"),
              "pairs must be strictly ascending by (researcherA, researcherB): no duplicates"
            )
          ),
          CandidateRules.derivedCheck(at(p, "provenance.sourceDataset"), t.provenance),
          Option(t.provenance).toVector.flatMap { x =>
            all(
              when(t.listVersion != null && x.sourceRecordId != t.listVersion)(
                ValidationError
                  .InvalidValue(at(p, "provenance.sourceRecordId"), "must equal listVersion")
              ),
              when(
                Option(x.modelVersion).flatten.isDefined ||
                  Option(x.promptVersion).flatten.isDefined
              )(
                ValidationError.InvalidValue(
                  at(p, "provenance.modelVersion,promptVersion"),
                  "a benchmark pair list is not model output: both must be null"
                )
              )
            )
          }
        )
      }
    }
}

// ------------------------------------------------------------------ C4 temporal windows

/**
 * Summary of one configured analysis window (schema `analysis-window.v1`, contract C4). One record
 * per window of `graph.windows`, in configured order (`ordinal` 0..n-1). Windows are inclusive year
 * ranges taken from configuration, never hardcoded; they may overlap. A counted work without a year
 * belongs to no window and is counted in `countedWorksWithoutYear`, which has the same value on
 * every window record of a run.
 *
 * @param countedWorkCount
 *   counted works (teams) whose year lies in the window
 * @param researcherCount
 *   identified researchers with a metrics record in the window
 * @param unresolvedCoauthorOccurrences
 *   unresolved mentions on the window's counted teams (never part of an edge)
 */
@allowUnknownKeys(false)
final case class AnalysisWindow(
    schemaVersion: String,
    window: TimeWindow,
    ordinal: Int,
    countedWorkCount: Long,
    countedWorksWithoutYear: Long,
    researcherCount: Long,
    edgeCount: Long,
    componentCount: Long,
    communityCount: Long,
    unresolvedCoauthorOccurrences: Long,
    lineage: AnalysisLineage,
    provenance: Provenance
) derives ReadWriter

object AnalysisWindow {

  /** The subject (`provenance.sourceRecordId`) of a window record: `window:<start>-<end>`. */
  def subjectOf(w: TimeWindow): String =
    if (w == null) null else s"window:${w.startYear}-${w.endYear}"

  given contract: RecordContract[AnalysisWindow] =
    new RecordContract[AnalysisWindow]("AnalysisWindow", SchemaVersions.AnalysisWindow, true) {
      def schemaVersionOf(t: AnalysisWindow): String = t.schemaVersion
      def provenanceOf(t: AnalysisWindow): Provenance = t.provenance
      protected def fields(t: AnalysisWindow, p: String): Vector[ValidationError] =
        all(
          when(t.ordinal < 0)(ValidationError.InvalidValue(at(p, "ordinal"), "negative")),
          Validated.nonNegative(at(p, "countedWorkCount"), t.countedWorkCount),
          Validated.nonNegative(at(p, "countedWorksWithoutYear"), t.countedWorksWithoutYear),
          Validated.nonNegative(at(p, "researcherCount"), t.researcherCount),
          Validated.nonNegative(at(p, "edgeCount"), t.edgeCount),
          Validated.nonNegative(at(p, "componentCount"), t.componentCount),
          Validated.nonNegative(at(p, "communityCount"), t.communityCount),
          Validated
            .nonNegative(at(p, "unresolvedCoauthorOccurrences"), t.unresolvedCoauthorOccurrences),
          when(t.componentCount > t.researcherCount)(
            ValidationError.InvalidValue(at(p, "componentCount"), "exceeds researcherCount")
          ),
          when(t.communityCount > t.researcherCount)(
            ValidationError.InvalidValue(at(p, "communityCount"), "exceeds researcherCount")
          ),
          when(t.researcherCount > 0 && t.countedWorkCount == 0)(
            ValidationError.InvalidValue(
              at(p, "countedWorkCount"),
              "researchers in a window imply counted works in it"
            )
          ),
          common(p, AnalysisWindow.subjectOf(t.window), t.window, t.lineage, t.provenance)
        )
    }
}

// ------------------------------------------------------------------ C6 semantic-summary inputs

/**
 * Kind of stored record a summary fact cites. Frozen tags. Each kind names the ID it is resolved by
 * within the input's window ([[AnalysisValidation.checkSummaryInputs]]).
 */
enum SummaryEvidenceKind(val tag: String, val idKind: IdKind) {
  case ResearcherMetrics extends SummaryEvidenceKind("researcher-metrics", IdKind.Researcher)
  case CoauthorEdge extends SummaryEvidenceKind("coauthor-edge", IdKind.CoauthorEdge)
  case EvidencePath extends SummaryEvidenceKind("evidence-path", IdKind.EvidencePath)
  case Component extends SummaryEvidenceKind("component", IdKind.Component)
  case Community extends SummaryEvidenceKind("community", IdKind.Community)
  case BridgeMeasure extends SummaryEvidenceKind("bridge-measure", IdKind.Researcher)
  case Coreness extends SummaryEvidenceKind("coreness", IdKind.Researcher)
  case CountedWork extends SummaryEvidenceKind("counted-work", IdKind.Work)
}
object SummaryEvidenceKind {
  def fromTag(tag: String): Option[SummaryEvidenceKind] = values.find(_.tag == tag)
  given ReadWriter[SummaryEvidenceKind] =
    EnumCodec.tagged("SummaryEvidenceKind", values.toSeq, _.tag)
}

/** A reference to one stored analysis record (or counted work) of the input's window. */
@allowUnknownKeys(false)
final case class SummaryEvidenceRef(kind: SummaryEvidenceKind, id: String) derives ReadWriter

/**
 * One deterministic fact for a summary. `value` is the text form of a value taken from the cited
 * records (counts, IDs, years); it is data, never instructions, and never computed from text. Every
 * fact cites at least one record.
 */
@allowUnknownKeys(false)
final case class SummaryFact(name: String, value: String, evidence: Vector[SummaryEvidenceRef])
    derives ReadWriter

/**
 * Input of one semantic summary (schema `semantic-summary-input.v1`, contract C6): the facts a
 * summary generator may state about one subject in one window, each with the stored records that
 * support it. Counts and other deterministic facts come from these records only, never from vector
 * similarity or generated text.
 *
 * Subject by document type: `researcher-profile` -> researcher ID; `collaboration-summary` ->
 * coauthor edge ID; `path-summary` -> evidence path ID; `community-summary` -> community ID.
 * `work-abstract` is source text, not an analysis summary, and is refused.
 *
 * @param inputId
 *   [[SummaryInputId.of]](documentType, subjectId, window, graph policy)
 * @param identity
 *   present exactly for a researcher subject: its identity confidence (C5), carried as is
 */
@allowUnknownKeys(false)
final case class SemanticSummaryInput(
    schemaVersion: String,
    inputId: String,
    documentType: DocumentType,
    subjectId: String,
    window: TimeWindow,
    facts: Vector[SummaryFact],
    identity: Option[IdentityConfidence],
    lineage: AnalysisLineage,
    provenance: Provenance
) derives ReadWriter

object SemanticSummaryInput {

  /** The subject ID kind of each summary document type (work abstracts are not analysis inputs). */
  val SubjectKind: Map[DocumentType, IdKind] = Map(
    DocumentType.ResearcherProfile -> IdKind.Researcher,
    DocumentType.CollaborationSummary -> IdKind.CoauthorEdge,
    DocumentType.PathSummary -> IdKind.EvidencePath,
    DocumentType.CommunitySummary -> IdKind.Community
  )

  given contract: RecordContract[SemanticSummaryInput] =
    new RecordContract[SemanticSummaryInput](
      "SemanticSummaryInput",
      SchemaVersions.SemanticSummaryInput,
      true
    ) {
      def schemaVersionOf(t: SemanticSummaryInput): String = t.schemaVersion
      def provenanceOf(t: SemanticSummaryInput): Provenance = t.provenance
      protected def fields(t: SemanticSummaryInput, p: String): Vector[ValidationError] = {
        val facts = Option(t.facts).getOrElse(Vector.empty)
        val identity = Option(t.identity).getOrElse(None)
        val kind = Option(t.documentType).flatMap(SubjectKind.get)
        val windowOk = TimeWindow.check("window", t.window).isEmpty
        all(
          when(t.documentType == null)(ValidationError.MissingField(at(p, "documentType"))),
          when(t.documentType != null && kind.isEmpty)(
            ValidationError.InvalidValue(
              at(p, "documentType"),
              s"'${t.documentType.tag}' is not an analysis summary"
            )
          ),
          kind.toVector.flatMap(k => StableId.check(k, t.subjectId, at(p, "subjectId"))),
          when(kind.contains(IdKind.Researcher) && identity.isEmpty)(
            ValidationError.MissingField(at(p, "identity"))
          ),
          when(kind.isDefined && !kind.contains(IdKind.Researcher) && identity.isDefined)(
            ValidationError.InvalidValue(at(p, "identity"), "only for a researcher subject")
          ),
          identity.toVector.flatMap(IdentityConfidence.check(at(p, "identity"), _)),
          when(t.facts == null || facts.isEmpty)(ValidationError.MissingField(at(p, "facts"))),
          facts.zipWithIndex.flatMap { case (fact, i) =>
            val f = at(p, s"facts[$i]")
            if (fact == null) Vector(ValidationError.MissingField(f))
            else {
              val ev = Option(fact.evidence).getOrElse(Vector.empty)
              all(
                AssignmentRules.token(at(f, "name"), fact.name),
                Validated.text(at(f, "value"), fact.value),
                when(ev.isEmpty)(ValidationError.MissingField(at(f, "evidence"))),
                ev.zipWithIndex.flatMap { case (r, j) =>
                  val g = at(f, s"evidence[$j]")
                  if (r == null || r.kind == null) Vector(ValidationError.MissingField(g))
                  else StableId.check(r.kind.idKind, r.id, at(g, "id"))
                },
                when(ev.filter(_ != null).distinct.size != ev.size)(
                  ValidationError.InvalidValue(at(f, "evidence"), "contains duplicate values")
                )
              )
            }
          },
          Validated.distinct(at(p, "facts.name"), facts.filter(_ != null).map(_.name)),
          StableId.check(IdKind.SummaryInput, t.inputId, at(p, "inputId")),
          (policyOf(t.provenance), kind, windowOk) match {
            case (Some(pv), Some(_), true) =>
              formula(
                at(p, "inputId"),
                t.inputId,
                SummaryInputId.of(t.documentType, t.subjectId, t.window, pv)
              )
            case _ => Vector.empty
          },
          common(p, t.inputId, t.window, t.lineage, t.provenance)
        )
      }
    }
}
