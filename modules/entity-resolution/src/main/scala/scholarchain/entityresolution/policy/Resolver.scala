package scholarchain.entityresolution.policy

import scala.collection.immutable.SortedMap

import upickle.default.ReadWriter
import upickle.implicits.allowUnknownKeys

import scholarchain.core.{
  AdjudicationReference,
  AdjudicationVerdict,
  ExactId,
  ExactIdScheme,
  IdentifierConflict,
  MatchCandidate,
  MatchDecision,
  MatchEntityKind,
  MatchOutcome,
  Provenance,
  SchemaVersions,
  SourceType
}
import scholarchain.entityresolution.identifiers.ExactIdentifiers

/**
 * The record of one LLM adjudication attempt (filled by the adjudication step; never by the
 * scorer). No model text is kept: only the validated decision tag, evidence references, and reason
 * codes of the strict response schema, or the reason a response was refused.
 *
 * @param status
 *   `valid`, `invalid-response`, or `adapter-failure`
 * @param decision
 *   `accept`, `reject`, or `abstain` when `status` is `valid`
 */
final case class AdjudicationTrace(
    inputHash: String,
    promptVersion: String,
    modelVersion: String,
    policyVersion: String,
    status: String,
    decision: Option[String],
    evidence: Vector[String],
    reasonCodes: Vector[String],
    refusal: Option[String],
    fromCache: Boolean
) {
  def isValid: Boolean = status == AdjudicationTrace.Valid
}

object AdjudicationTrace {
  val Valid = "valid"
  val InvalidResponse = "invalid-response"
  val AdapterFailure = "adapter-failure"
}

/**
 * Whether the probabilistic score (step 8) decides a candidate when the thresholds change. It is
 * found by running the same [[Resolver]] again on the same candidate with thresholds that put every
 * score in the accept region (`acceptReachable`, `acceptBlockedBy`) and, separately, in the reject
 * region (`rejectReachable`); no gate is re-implemented. The score does not depend on the
 * thresholds, so for any thresholds `(accept, reject)` the Resolver ACCEPTS the candidate on the
 * probabilistic path exactly when `acceptReachable` and `score >= accept`, and REJECTS it there
 * exactly when `rejectReachable` and `score <= reject`.
 *
 * @param acceptReachable
 *   the score path is reached and nothing caps its acceptance: no exact-id or deterministic rule
 *   decides first, no blocking identifier disagreement, and for a work pair an established same
 *   publication stage
 * @param rejectReachable
 *   the score path is reached and a score at or below the reject threshold gives REJECTED
 * @param acceptBlockedBy
 *   the Resolver rules that cap score acceptance to UNRESOLVED
 *   (`probabilistic.accept-blocked-by-id-disagreement`,
 *   `probabilistic.accept-blocked-by-publication-stage`); empty when acceptance is reachable or the
 *   score path is not reached
 */
@allowUnknownKeys(false)
final case class ScoreGate(
    acceptReachable: Boolean,
    rejectReachable: Boolean,
    acceptBlockedBy: Vector[String]
) derives ReadWriter

/**
 * The full outcome of resolving one [[MatchCandidate]] under one policy. This is the in-memory
 * result of the policy; [[Resolver.toMatchDecision]] maps it to the core [[MatchDecision]]
 * (`scholarchain.match-decision.v2`).
 *
 * @param features
 *   the scored features: the candidate's features plus the policy features ([[Scoring]])
 * @param score
 *   the probabilistic score, computed on every path (it decides only on the probabilistic path)
 * @param confidence
 *   a policy confidence constant (uncalibrated), never a measured probability: the scheme's
 *   `agreementConfidence` for an exact-id ACCEPTED, `conflictConfidence` for CONFLICT,
 *   `implausibleDisagreementConfidence` for an exact-id REJECTED, `deterministicConfidence` for a
 *   deterministic decision; on the probabilistic path the uncalibrated logistic output `p` for
 *   ACCEPTED and `1 - p` for REJECTED; 0 for UNRESOLVED (no outcome asserted)
 * @param conflicts
 *   identifier conflicts between side A and side B: disagreements of T1 schemes and of the two
 *   blocking dispositions, and agreements contradicted by another rule. Never non-empty on
 *   ACCEPTED; evidence-only disagreements are never recorded here.
 * @param plausibility
 *   the plausibility test, evaluated when a T1 or algorithmic author identifier disagrees (None
 *   otherwise; record-key disagreements never trigger it)
 * @param llmBandEligible
 *   UNRESOLVED on the probabilistic path with a score inside the policy LLM band and no recorded
 *   identifier conflict
 * @param adjudication
 *   the last LLM adjudication attempt; only a valid one changes the path
 *   ([[Resolver.toMatchDecision]] records only valid ones)
 * @param corroboration
 *   the publication-stage corroboration test ([[Corroboration]]), evaluated when a cluster
 *   identifier (`accept-if-corroborated`) agrees (None otherwise)
 * @param stage
 *   the publication-stage check of a work pair ([[PublicationStage]], er-policy.v4); None for a
 *   researcher pair. A work outcome whose same stage is not established is never band-eligible
 * @param scoreGate
 *   whether the probabilistic score could decide this candidate at other thresholds
 *   ([[ScoreGate]]); set by [[Resolver.decide]], None only for an outcome built by hand
 * @param policyAgreements
 *   the identifier agreements the policy evaluated (er-policy.v8), with the original values of the
 *   endpoint records: the candidate's `exactIdAgreements` for every scheme but arXiv, and for arXiv
 *   every original value of either side whose base submission ID both sides share
 *   ([[scholarchain.entityresolution.identifiers.ExactIdentifiers.agreement]]), versions kept
 *   (`2101.00001v1` and `2101.00001v2`). `exactIdAgreements` stays the candidate field unchanged.
 *   Empty only for an outcome built by hand
 */
final case class ResolutionOutcome(
    candidateId: String,
    entityKind: MatchEntityKind,
    sourceIdA: String,
    sourceIdB: String,
    sourceDatasetA: SourceType,
    sourceDatasetB: SourceType,
    candidateRunId: String,
    candidateSnapshot: String,
    blockingVersion: String,
    featureVersion: String,
    policyVersion: String,
    path: MatchPath,
    outcome: MatchOutcome,
    confidence: Double,
    score: Score,
    features: SortedMap[String, Double],
    exactIdAgreements: Vector[ExactId],
    rulesFired: Vector[String],
    reasonCodes: Vector[String],
    conflicts: Vector[IdentifierConflict],
    plausibility: Option[PlausibilityResult],
    llmBandEligible: Boolean,
    adjudication: Option[AdjudicationTrace],
    corroboration: Option[CorroborationResult] = None,
    stage: Option[StageResult] = None,
    scoreGate: Option[ScoreGate] = None,
    policyAgreements: Vector[ExactId] = Vector.empty
) {

  /** Schemes of the recorded conflicts, distinct, in conflict order. */
  def conflictSchemes: Vector[String] = conflicts.map(_.scheme.tag).distinct
}

/** Rule IDs and reason codes of policy `er-policy.v8`. Every rule ID starts with its path tag. */
object Rules {
  private val E = MatchPath.ExactId.tag
  private val D = MatchPath.DeterministicBibliographic.tag
  private val P = MatchPath.Probabilistic.tag
  private val L = MatchPath.LlmAdjudicated.tag

  def exactAgree(scheme: String): String = s"$E.agree.$scheme"
  def exactConflict(scheme: String): String = s"$E.conflict.$scheme"
  def disagreeImplausible(scheme: String): String = s"$E.reject.disagreement-implausible.$scheme"
  def algorithmicDisagreement(scheme: String): String =
    s"$E.algorithmic-disagreement.$scheme"
  def recordKeyDisagreement(scheme: String): String = s"$E.record-key-disagreement.$scheme"

  /** A blocking disagreement of `scheme` overridden by a T1 agreement of `by`. */
  def overridden(scheme: String, by: String): String = s"$E.override.$scheme.by.$by"
  def clusterCorroborated(scheme: String): String = s"$E.cluster-agreement.corroborated.$scheme"
  def clusterUncorroborated(scheme: String): String =
    s"$E.cluster-agreement.uncorroborated.$scheme"
  val ClusterUnresolved: String = s"$E.unresolved.cluster-agreement-uncorroborated"

  /** An arXiv agreement whose two sides are not both pure preprints (er-policy.v4). */
  val ArxivCrossStage: String = s"$E.unresolved.arxiv-agreement-cross-stage"

  /**
   * A DOI agreement whose shared DOIs all carry a preprint prefix, on a pair whose two sides are
   * not both pure preprints (er-policy.v5).
   */
  val PreprintDoiCrossStage: String = s"$E.unresolved.preprint-doi-agreement-cross-stage"

  /**
   * A record-key agreement (`scheme` is dblp-key or scopus-eid) that does not decide because the
   * preprint-DOI rule ([[PreprintDoiCrossStage]]) left the pair unresolved (er-policy.v6).
   */
  def recordKeyBlockedByPreprintDoi(scheme: String): String =
    s"$E.unresolved.record-key-agreement-blocked-by-preprint-doi-stage.$scheme"

  /**
   * A record-key agreement (`scheme` is dblp-key or scopus-eid) that does not decide because the
   * arXiv rule ([[ArxivCrossStage]]) left the pair unresolved and no other T1 agreement decides
   * (er-policy.v7).
   */
  def recordKeyBlockedByArxivStage(scheme: String): String =
    s"$E.unresolved.record-key-agreement-blocked-by-arxiv-stage.$scheme"

  /**
   * A record-key agreement (`scheme` is dblp-key or scopus-eid) of a work pair that does not decide
   * because the same publication stage is not established (cross-stage or uncertain) and no T1
   * agreement decides (er-policy.v8, decisions-11 item 2).
   */
  def recordKeyBlockedByStage(scheme: String): String =
    s"$E.unresolved.record-key-agreement-blocked-by-publication-stage.$scheme"

  /** An inferred ACCEPTED of a work pair whose same publication stage is not established. */
  def acceptBlockedByStage(path: MatchPath): String =
    s"${path.tag}.accept-blocked-by-publication-stage"
  def evidenceAgree(scheme: String): String = s"$E.evidence.agree.$scheme"
  def evidenceDisagree(scheme: String): String = s"$E.evidence.disagree.$scheme"
  val Plausible: String = s"$E.plausible-identity"
  val Implausible: String = s"$E.implausible-identity"
  val IdentifierContradiction: String = s"$E.conflict.identifier-contradiction"
  val ExactVsSameWork: String = s"$E.conflict.same-work"
  val ExactVsHomonym: String = s"$E.conflict.homonym-number"
  val SameWork: String = s"$D.same-work-distinct-position"
  val HomonymDiffers: String = s"$D.homonym-number-differs"
  val SamePaperSamePosition: String = s"$D.same-paper-same-position"
  val TitleYearAuthorsExact: String = s"$D.title-year-authors-exact"
  def acceptBlocked(path: MatchPath): String = s"${path.tag}.accept-blocked-by-id-disagreement"
  val ScoreAccept: String = s"$P.accept"
  val ScoreReject: String = s"$P.reject"
  val ScoreUnresolved: String = s"$P.unresolved"
  val ScoreLlmBand: String = s"$P.llm-band"
  val LlmRefused: String = s"$P.llm-response-refused"
  val LlmAccept: String = s"$L.accept"
  val LlmReject: String = s"$L.reject"
  val LlmAbstain: String = s"$L.abstain"

  object Reason {
    def exactAgreement(scheme: String): String = s"exact-id-agreement.$scheme"
    def idDisagreement(scheme: String): String = s"authoritative-id-disagreement.$scheme"
    def algorithmicDisagreement(scheme: String): String = s"algorithmic-id-disagreement.$scheme"
    def recordKeyDisagreement(scheme: String): String = s"record-key-disagreement.$scheme"

    /** `<disposition>.<scheme>.overridden-by.<scheme>` (ER-P5-04 section 1.4). */
    def overridden(disposition: String, scheme: String, by: String): String =
      s"$disposition.$scheme.overridden-by.$by"

    /** `<disposition>.<scheme>.blocks.<path>`. */
    def blocks(disposition: String, scheme: String, path: MatchPath): String =
      s"$disposition.$scheme.blocks.${path.tag}"
    def clusterCorroborated(scheme: String): String = s"cluster-id-agreement-corroborated.$scheme"
    def clusterUncorroborated(scheme: String): String =
      s"cluster-id-agreement-uncorroborated.$scheme"
    def corroborationFailed(clause: String): String = s"corroboration-clause-failed.$clause"
    def evidenceAgreement(scheme: String): String = s"evidence-only-id-agreement.$scheme"
    def evidenceDisagreement(scheme: String): String = s"evidence-only-id-disagreement.$scheme"
    val Plausible = "plausible-identity-match"
    val Implausible = "not-a-plausible-identity-match"
    val DisagreementImplausible = "id-disagreement-on-implausible-pair"
    val IdentifierContradiction = "identifier-agreement-contradicted-by-disagreement"
    val AcceptBlocked = "acceptance-blocked-by-id-disagreement"
    val LlmExcluded = "llm-band-excluded-by-id-disagreement"

    /** `publication-stage-cross-stage` or `publication-stage-uncertain`. */
    def stage(v: StageVerdict): String = s"publication-stage-${v.tag}"
    val AcceptBlockedByStage = "acceptance-blocked-by-publication-stage"
    val LlmExcludedByStage = "llm-band-excluded-by-publication-stage"
    val ArxivCrossStage = "arxiv-agreement-cross-stage"
    val PreprintDoiCrossStage = "preprint-doi-agreement-cross-stage"

    /** Why a dblp-key or scopus-eid agreement did not decide (er-policy.v6, decisions-08). */
    val RecordKeyBlockedByPreprintDoi = "record-key-agreement-blocked-by-preprint-doi-stage"

    /** Why a dblp-key or scopus-eid agreement did not decide (er-policy.v7, decisions-09). */
    val RecordKeyBlockedByArxivStage = "record-key-agreement-blocked-by-arxiv-stage"

    /**
     * Why a dblp-key or scopus-eid agreement did not decide (er-policy.v8, decisions-11 item 2).
     */
    val RecordKeyBlockedByStage = "record-key-agreement-blocked-by-publication-stage"

    /**
     * The arXiv agreement holds only through the base submission ID: the two sides carry different
     * revisions vN of one submission (er-policy.v8, decisions-11 item 3).
     */
    val ArxivAgreementByBaseId = "arxiv-agreement-by-base-submission-id"
    val ExactVsBibliographic = "exact-id-contradicted-by-bibliographic-rule"
    val SameWork = "same-work-distinct-author-positions"
    val HomonymDiffers = "dblp-homonym-numbers-differ"
    val SamePaperSamePosition = "same-paper-same-author-position"
    val TitleYearAuthorsExact = "title-year-author-sequence-exact"
    val AboveAccept = "score-at-or-above-accept-threshold"
    val BelowReject = "score-at-or-below-reject-threshold"
    val Unresolved = "score-between-thresholds"
    val LlmBand = "score-inside-llm-band"
    val LlmAccept = "llm-accept"
    val LlmReject = "llm-reject"
    val LlmAbstain = "llm-abstain"
    val LlmRefused = "llm-response-refused"
  }
}

/** Run context of a decision batch. `decidedAt` is supplied by the caller (no clock here). */
final case class DecisionContext(runId: String, decidedAt: String)

/**
 * Applies a [[MatchPolicy]] to [[MatchCandidate]] records. Pure and deterministic; reads only the
 * candidate, its endpoint identifiers ([[EndpointIds]]), and the policy. LLM adjudication is a
 * later, separate step (`scholarchain.entityresolution.adjudication`).
 *
 * Order of evaluation (er-policy.v3, the "narrow" precedence of decision-brief-01 section 2.2,
 * approved 2026-11-06, with decision 4 on cluster identifiers). Abbreviations:
 *   - T1D / T1A: disagreements / agreements of T1 schemes (`conflict-if-plausible`: orcid,
 *     dblp-pid, doi, arxiv);
 *   - BD: blocking disagreements, of `block-inferred-acceptance-if-plausible` (openalex-author,
 *     s2-author) or `block-inferred-acceptance` (dblp-key, scopus-eid);
 *   - T2A: agreements of the other schemes that identify on agreement (`accept` or
 *     `accept-if-corroborated`);
 *   - P: the pair is a plausible identity match ([[Plausibility]], non-identifier evidence only),
 *     computed only when a T1D or an algorithmic author BD exists.
 *
 * Steps:
 *   1. Classify every scheme as agree, disagree, or absent. Evidence-only schemes (repository-url;
 *      the disagreements of openalex-work, mag, s2-corpus, s2-paper) add reason codes only. Every
 *      BD adds its disagreement rule and reason code to every outcome; every cluster agreement adds
 *      its corroboration status.
 *   2. T1D: CONFLICT when P or any identifier agrees (the identifiers contradict each other);
 *      otherwise REJECTED (`exact-id.reject.disagreement-implausible.<scheme>`). Never ACCEPTED.
 *   3. No T1A, BD and T2A: CONFLICT (`exact-id.conflict.identifier-contradiction`).
 *   4. No T1A, an algorithmic author BD, not P: REJECTED.
 *   5. Researcher, an identifying agreement, and two positions on one work or different DBLP
 *      homonym numbers: CONFLICT.
 *   6. a. T1A: ACCEPTED on the exact-id path. It overrides every BD: reason code
 *      `<disposition>.<scheme>.overridden-by.<scheme>`, rule `exact-id.override.<scheme>.by.<scheme>`;
 *      `conflicts` stays empty (core forbids conflicts on ACCEPTED).
 *      b. T2A without BD: ACCEPTED on the exact-id path when a scheme that accepts outright agrees,
 *      or a cluster identifier agrees with corroborating publication-stage evidence
 *      ([[Corroboration]]). A cluster agreement alone is UNRESOLVED on the exact-id path (rule
 *      [[Rules.ClusterUnresolved]], reason `cluster-id-agreement-uncorroborated.<scheme>`); it is
 *      never sent to the LLM and is not decided by the bibliographic rules or the score.
 *   7. Deterministic bibliographic rules, then 8. the probabilistic score. With a BD, an ACCEPTED
 *      result becomes UNRESOLVED (`<path>.accept-blocked-by-id-disagreement`), REJECTED and
 *      UNRESOLVED stand, the BD is written to `conflicts`, and the pair is never band-eligible.
 *
 * Publication-stage target (er-policy.v4, human decision of 2026-11-06, decisions-06): for a work
 * pair the stage is computed once ([[PublicationStage]]).
 *   - An arXiv agreement decides (step 6a) only when both sides are pure preprints
 *     (`same-stage-preprint`). Otherwise it is not an accepting agreement for steps 3 to 6 (reason
 *     `arxiv-agreement-cross-stage`, rule [[Rules.ArxivCrossStage]]). It still counts as an
 *     agreement for step 2, so a T1 disagreement plus a shared arXiv ID stays CONFLICT. When no
 *     other identifier decides, the pair is UNRESOLVED on the exact-id path (6c), never inferred and
 *     never sent to the LLM. A shared arXiv ID therefore never by itself merges a preprint with a
 *     published version.
 *   - er-policy.v5 (human decision of 2026-11-11, decisions-07, Option 1): a DOI agreement whose
 *     shared DOIs all start with one of `workCorroboration.preprintDoiPrefixes` (the arXiv ID in
 *     DOI form) is treated exactly like an arXiv agreement: it decides only for
 *     `same-stage-preprint`, otherwise it is not an accepting agreement for steps 3 to 6 (reason
 *     `preprint-doi-agreement-cross-stage` plus `publication-stage-<verdict>`, rule
 *     [[Rules.PreprintDoiCrossStage]]), and when no other identifier decides the pair is
 *     UNRESOLVED on the exact-id path (6c), never inferred and never sent to the LLM. It still
 *     counts as an agreement for step 2. When at least one shared DOI has no preprint prefix (a
 *     publication DOI), the DOI agreement decides as before.
 *   - er-policy.v6 (human decision of 2026-11-11, decisions-08, "Block it"): while that
 *     preprint-DOI agreement does not decide, a record-key agreement (dblp-key, scopus-eid; T2
 *     `accept`, disagreement `block-inferred-acceptance`) does not decide in step 6b either. The
 *     pair stays UNRESOLVED on the exact-id path (6c) with reason
 *     `record-key-agreement-blocked-by-preprint-doi-stage`, rule
 *     [[Rules.recordKeyBlockedByPreprintDoi]] per blocked scheme, and the preprint-DOI and
 *     publication-stage reasons. The record-key agreement still counts for step 3 (CONFLICT with a
 *     blocking disagreement) and step 2 is unchanged.
 *   - er-policy.v7 (human decision of 2026-11-11, decisions-09 item 1): the same record-key block
 *     applies when a shared arXiv ID leaves the pair unresolved (the arXiv agreement does not
 *     decide because the stage is not `same-stage-preprint`) and no other T1 agreement decides
 *     (for example a shared publication DOI). The pair stays UNRESOLVED on the exact-id path (6c)
 *     with reason `record-key-agreement-blocked-by-arxiv-stage` and rule
 *     [[Rules.recordKeyBlockedByArxivStage]] per blocked scheme, plus the arXiv and
 *     publication-stage reasons. When both triggers apply, both rules and reasons are recorded.
 *     Step 3 and step 2 are unchanged.
 *   - er-policy.v8 (human decision of 2026-11-12, decisions-11 item 2): the record-key block is
 *     general. For a work pair a dblp-key or scopus-eid agreement decides (step 6b) only when the
 *     same publication stage is established (`same-stage-preprint`, `same-stage-published`,
 *     `same-stage-venue`), even when no preprint identifier is shared. When the stage is
 *     `cross-stage` or `uncertain` and no T1 agreement decides, the pair stays UNRESOLVED on the
 *     exact-id path (6c) with reason `record-key-agreement-blocked-by-publication-stage`, the
 *     `publication-stage-<verdict>` reason, and rule [[Rules.recordKeyBlockedByStage]] per blocked
 *     scheme. The v6 and v7 triggers are special cases of it; their rules and reasons are still
 *     recorded in addition when they apply. Steps 2 and 3 are unchanged, researcher pairs are
 *     unaffected, and a T1 agreement that decides (a shared publication DOI) still accepts.
 *   - er-policy.v8 (decisions-11 item 3, RV-P5-04 m7): arXiv identifiers are compared by base
 *     submission ID (without the version suffix vN) for agreement and for disagreement, through
 *     the one normalisation of [[ExactIdentifiers.comparable]]. Two revisions of one submission
 *     agree (reason `arxiv-agreement-by-base-submission-id` when no revision is shared byte for
 *     byte), and the agreement still decides only at `same-stage-preprint`. The original values,
 *     versions included, are kept in [[ResolutionOutcome.policyAgreements]] and in any
 *     contradicted-agreement conflict; the candidate's `exactIdAgreements` is not changed.
 *   - The preprint-DOI rule and reasons are recorded only on the outcomes the block decided (steps
 *     3, 4 and 6c), never on an ACCEPTED outcome or a step-2 or step-5 CONFLICT (RV-P5-06 nit 1).
 *     The record-key block rules are recorded only on the 6c outcome they decide. The arXiv rule is
 *     unchanged (recorded on every outcome where the arXiv agreement is blocked).
 *   - In steps 7 and 8, when the same stage is not established (`cross-stage` or `uncertain`), an
 *     ACCEPTED result becomes UNRESOLVED (rule `<path>.accept-blocked-by-publication-stage`, reasons
 *     `publication-stage-<verdict>` and `acceptance-blocked-by-publication-stage`), and a band
 *     score is not band-eligible (reason `llm-band-excluded-by-publication-stage`). REJECTED stands.
 *
 * Every confidence is a policy confidence constant (uncalibrated) (see [[ResolutionOutcome]]).
 */
object Resolver {

  private def flag(f: SortedMap[String, Double], k: String): Boolean = f.get(k).contains(1.0)

  /** A DOI with one of the policy's preprint prefixes (`workCorroboration.preprintDoiPrefixes`). */
  private def isPreprintDoi(p: MatchPolicy, doi: String): Boolean =
    p.workCorroboration.preprintDoiPrefixes.exists(pre => doi.startsWith(pre))

  /**
   * Resolves one candidate with the identifiers of its two endpoints, or explains why it cannot be
   * resolved under this policy.
   */
  def decide(
      p: MatchPolicy,
      c: MatchCandidate,
      ids: EndpointIds
  ): Either[Vector[String], ResolutionOutcome] = {
    val problems = Option(c).fold(Vector("candidate is null")) { cand =>
      MatchCandidate.contract.validate(cand).map(_.message) ++
        (if (cand.blockingVersion != p.blockingVersion)
           Vector(
             s"candidate blockingVersion ${cand.blockingVersion} differs from the policy's " +
               s"${p.blockingVersion}; outputs of different key versions are never mixed"
           )
         else Vector.empty) ++
        (if (cand.featureVersion != p.featureVersion)
           Vector(
             s"candidate featureVersion ${cand.featureVersion} differs from the policy's " +
               s"${p.featureVersion}; outputs of different feature versions are never mixed"
           )
         else Vector.empty)
    }
    val kp = Option(c).flatMap(x => Option(x.entityKind)).flatMap(p.kind)
    if (problems.nonEmpty) Left(problems)
    else if (ids == null) Left(Vector("endpoint identifiers are missing"))
    else
      kp match {
        case None => Left(Vector(s"no policy for entity kind ${c.entityKind}"))
        case Some(k) =>
          val bad = EndpointIdentifiers.inconsistencies(c, ids)
          if (bad.nonEmpty) Left(bad)
          else Right(resolve(p, k, c, ids).copy(scoreGate = Some(scoreGate(p, k, c, ids))))
      }
  }

  /**
   * [[ScoreGate]] of a valid candidate: the same resolution run twice more, with every score in the
   * accept region (both thresholds at negative infinity) and in the reject region (both at positive
   * infinity). These probe thresholds are never a policy and are never validated or recorded.
   */
  private def scoreGate(
      p: MatchPolicy,
      kp: KindPolicy,
      c: MatchCandidate,
      ids: EndpointIds
  ): ScoreGate = {
    val P = MatchPath.Probabilistic
    val acceptAll = resolve(
      p.copy(thresholds = Thresholds(Double.NegativeInfinity, Double.NegativeInfinity)),
      kp,
      c,
      ids
    )
    val rejectAll =
      resolve(
        p.copy(thresholds = Thresholds(Double.PositiveInfinity, Double.PositiveInfinity)),
        kp,
        c,
        ids
      )
    val caps = Set(Rules.acceptBlocked(P), Rules.acceptBlockedByStage(P))
    ScoreGate(
      acceptReachable = acceptAll.path == P && acceptAll.outcome == MatchOutcome.Accepted,
      rejectReachable = rejectAll.path == P && rejectAll.outcome == MatchOutcome.Rejected,
      acceptBlockedBy =
        if (acceptAll.path == P && acceptAll.outcome != MatchOutcome.Accepted)
          acceptAll.rulesFired.filter(caps.contains)
        else Vector.empty
    )
  }

  private type Disagreement = (ExactIdScheme, IdentifierRule, IdentifierConflict)

  private def bdRule(d: Disagreement): String =
    if (d._2.isRecordKey) Rules.recordKeyDisagreement(d._1.tag)
    else Rules.algorithmicDisagreement(d._1.tag)

  private def bdReason(d: Disagreement): String =
    if (d._2.isRecordKey) Rules.Reason.recordKeyDisagreement(d._1.tag)
    else Rules.Reason.algorithmicDisagreement(d._1.tag)

  private def resolve(
      p: MatchPolicy,
      kp: KindPolicy,
      c: MatchCandidate,
      ids: EndpointIds
  ): ResolutionOutcome = {
    val f = Scoring.scoredFeatures(p, c)
    val s = Scoring.score(kp, f)
    val researcher = c.entityKind == MatchEntityKind.Researcher
    val schemes = ExactIdScheme.values.toVector.filter(_.entityKind == c.entityKind)
    val ruled: Vector[(ExactIdScheme, IdentifierRule)] =
      schemes.flatMap(sc => kp.rule(sc).map(sc -> _))
    val disagreements: Vector[Disagreement] =
      ruled.flatMap { case (sc, r) =>
        ExactIdentifiers.disagreement(sc, ids.a, ids.b).map(d => (sc, r, d))
      }
    val t1d = disagreements.filter(_._2.isTier1)
    val authorBd = disagreements.filter(_._2.isAlgorithmicAuthor)
    val bd = disagreements.filter(_._2.blocksInferredAcceptance)
    val evidenceDisagreements = disagreements.filter(_._2.isEvidenceOnlyDisagreement)
    val ruleOf: Map[String, IdentifierRule] = ruled.map { case (sc, r) => sc.tag -> r }.toMap
    // er-policy.v8 (decisions-11 item 3, RV-P5-04 m7): the arXiv agreement is the agreement of the
    // base submission IDs of the endpoint identifiers ([[ExactIdentifiers.agreement]]), the same
    // normalisation as the arXiv disagreement above, with the original versioned values of both
    // sides. Every other scheme agrees on the candidate's exact canonical values, as before (for a
    // valid candidate these equal the endpoint agreements; EndpointIdentifiers checks it).
    val arxivAgreement: Vector[ExactId] =
      ExactIdentifiers.agreement(ExactIdScheme.Arxiv, ids.a, ids.b)
    val arxivVersionsFolded =
      ExactIdentifiers.agreesOnlyByComparable(ExactIdScheme.Arxiv, ids.a, ids.b)
    val policyAgreements: Vector[ExactId] =
      ExactId.canonical(
        c.exactIdAgreements.filterNot(_.scheme == ExactIdScheme.Arxiv) ++ arxivAgreement
      )
    val agreementsByScheme: Vector[(ExactIdScheme, Vector[ExactId])] =
      policyAgreements.groupBy(_.scheme).toVector.sortBy(_._1.tag)
    val identifying = agreementsByScheme.filter { case (sc, _) =>
      ruleOf.get(sc.tag).exists(_.identifiesOnAgreement)
    }
    val evidenceAgreements = agreementsByScheme.filter { case (sc, _) =>
      ruleOf.get(sc.tag).exists(r => !r.identifiesOnAgreement)
    }
    val stage: Option[StageResult] =
      if (researcher) None else Some(PublicationStage.of(p.workCorroboration, f, ids))
    val stageEstablished = stage.exists(_.established)
    // er-policy.v4: an arXiv agreement accepts only when both sides are pure preprints.
    val arxivBlocked = !researcher &&
      identifying.exists(_._1 == ExactIdScheme.Arxiv) && !stage.exists(_.bothPreprint)
    // er-policy.v5: a DOI agreement whose shared DOIs all carry a preprint prefix (the arXiv ID in
    // DOI form) is treated like an arXiv agreement: it accepts only when both sides are pure
    // preprints. One shared publication DOI keeps the DOI agreement deciding (unchanged).
    val doiPreprintOnly = !researcher &&
      identifying.exists { case (sc, vs) =>
        sc == ExactIdScheme.Doi && vs.nonEmpty && vs.forall(e => isPreprintDoi(p, e.value))
      }
    val doiBlocked = doiPreprintOnly && !stage.exists(_.bothPreprint)
    val blockedSchemes: Set[ExactIdScheme] =
      (if (arxivBlocked) Set(ExactIdScheme.Arxiv) else Set.empty) ++
        (if (doiBlocked) Set(ExactIdScheme.Doi) else Set.empty)
    // The accepting agreements of steps 3 to 6 (a blocked arXiv or preprint-DOI agreement is not
    // one of them).
    val accepting = identifying.filterNot { case (sc, _) => blockedSchemes.contains(sc) }
    val t1a = accepting.filter { case (sc, _) => ruleOf(sc.tag).isTier1 }
    val t2a = accepting.filterNot { case (sc, _) => ruleOf(sc.tag).isTier1 }
    val cluster = identifying.filter { case (sc, _) => ruleOf(sc.tag).requiresCorroboration }
    val corroboration: Option[CorroborationResult] =
      if (cluster.isEmpty) None else Some(Corroboration.work(p.workCorroboration, f, ids))
    val corroborated = corroboration.exists(_.corroborated)
    // er-policy.v6 (decisions-08): while the preprint-DOI agreement does not decide, a record-key
    // agreement (dblp-key, scopus-eid) does not decide either. er-policy.v7 (decisions-09 item 1):
    // the same holds while a shared arXiv ID does not decide (stage not same-stage-preprint) and no
    // other T1 agreement decides. A blocked record-key agreement stays in `t2a`, so step 3 still
    // reports a CONFLICT with a blocking disagreement exactly as before, and step 2 is unchanged.
    // (A blocked preprint DOI always leaves `t1a` empty: its condition also blocks the arXiv ID.)
    val recordKeyAgreements: Vector[String] =
      accepting.collect { case (sc, _) if ruleOf(sc.tag).isRecordKey => sc.tag }
    val recordKeyBlockedByDoi: Vector[String] =
      if (doiBlocked && t1a.isEmpty) recordKeyAgreements else Vector.empty
    val recordKeyBlockedByArxiv: Vector[String] =
      if (arxivBlocked && t1a.isEmpty) recordKeyAgreements else Vector.empty
    // er-policy.v8 (decisions-11 item 2): for a work pair a record-key agreement decides only when
    // the same publication stage is established (same-stage-preprint, same-stage-published or
    // same-stage-venue), whether or not a preprint identifier is shared. Cross-stage or uncertain:
    // it does not decide, and when no T1 agreement decides the pair stays UNRESOLVED (step 6c). This
    // contains the v6 and v7 blocks (their triggers imply a stage that is not established and an
    // empty `t1a`), whose trigger-specific codes are still recorded where they apply.
    val recordKeyBlockedByStage: Vector[String] =
      if (!researcher && !stageEstablished && t1a.isEmpty) recordKeyAgreements else Vector.empty
    val recordKeyBlocked: Vector[String] =
      (recordKeyBlockedByStage ++ recordKeyBlockedByDoi ++ recordKeyBlockedByArxiv).distinct
    val deciding = accepting.filter { case (sc, _) =>
      !recordKeyBlocked.contains(sc.tag) && (!ruleOf(sc.tag).requiresCorroboration || corroborated)
    }
    val decidingSchemes = deciding.map(_._1.tag)
    val sameWork = researcher && flag(f, "context.same_work")
    val homonymDiffers =
      researcher && flag(f, "name.homonym_suffix_both") && !flag(f, "name.homonym_suffix_agree")
    val plaus: Option[PlausibilityResult] =
      if (t1d.nonEmpty || authorBd.nonEmpty) Some(Plausibility.of(p, c.entityKind, c.features))
      else None
    val plausible = plaus.exists(_.plausible)
    val plausRule = plaus.toVector.map(r => if (r.plausible) Rules.Plausible else Rules.Implausible)
    val plausReason =
      plaus.toVector.map(r => if (r.plausible) Rules.Reason.Plausible else Rules.Reason.Implausible)

    // Recorded on every outcome: evidence-only schemes, blocking disagreements, cluster status.
    val clusterRules = cluster.map { case (sc, _) =>
      if (corroborated) Rules.clusterCorroborated(sc.tag) else Rules.clusterUncorroborated(sc.tag)
    }
    val clusterReasons = cluster.map { case (sc, _) =>
      if (corroborated) Rules.Reason.clusterCorroborated(sc.tag)
      else Rules.Reason.clusterUncorroborated(sc.tag)
    } ++ corroboration.toVector.flatMap(_.failed.map(Rules.Reason.corroborationFailed))
    val stageVerdictReason = stage.toVector.map(x => Rules.Reason.stage(x.verdict))
    val arxivRules = if (arxivBlocked) Vector(Rules.ArxivCrossStage) else Vector.empty
    val arxivReasons =
      if (arxivBlocked) Vector(Rules.Reason.ArxivCrossStage) ++ stageVerdictReason
      else Vector.empty
    // The preprint-DOI block is recorded only on the outcomes it decided (steps 3, 4 and 6c), never
    // on ACCEPTED and never on a step-2 or step-5 CONFLICT (RV-P5-06 nit 1).
    val doiRules = if (doiBlocked) Vector(Rules.PreprintDoiCrossStage) else Vector.empty
    val doiReasons =
      if (doiBlocked) Vector(Rules.Reason.PreprintDoiCrossStage) ++ stageVerdictReason
      else Vector.empty
    // Recorded only on the step-6c outcome the record-key block decides (never on ACCEPTED).
    // The general stage rule (v8) is recorded on every outcome the block decides, with the stage
    // verdict; the v6 and v7 trigger rules are recorded in addition when their trigger applies.
    val recordKeyRules = recordKeyBlockedByStage.map(Rules.recordKeyBlockedByStage) ++
      recordKeyBlockedByDoi.map(Rules.recordKeyBlockedByPreprintDoi) ++
      recordKeyBlockedByArxiv.map(Rules.recordKeyBlockedByArxivStage)
    val recordKeyReasons =
      (if (recordKeyBlockedByStage.nonEmpty)
         Vector(Rules.Reason.RecordKeyBlockedByStage) ++ stageVerdictReason
       else Vector.empty) ++
        (if (recordKeyBlockedByDoi.nonEmpty) Vector(Rules.Reason.RecordKeyBlockedByPreprintDoi)
         else Vector.empty) ++
        (if (recordKeyBlockedByArxiv.nonEmpty) Vector(Rules.Reason.RecordKeyBlockedByArxivStage)
         else Vector.empty)
    // An arXiv agreement that holds only through the base submission ID (different revisions vN):
    // recorded on every outcome, so the folding is visible next to the original values.
    val arxivFoldReasons =
      if (arxivVersionsFolded && ruleOf.contains(ExactIdScheme.Arxiv.tag))
        Vector(Rules.Reason.ArxivAgreementByBaseId)
      else Vector.empty
    val alwaysRules =
      bd.map(bdRule) ++ clusterRules ++ arxivRules ++
        evidenceAgreements.map(x => Rules.evidenceAgree(x._1.tag)) ++
        evidenceDisagreements.map(x => Rules.evidenceDisagree(x._1.tag))
    val alwaysReasons =
      bd.map(bdReason) ++ clusterReasons ++ arxivReasons ++ arxivFoldReasons ++
        evidenceAgreements.map(x => Rules.Reason.evidenceAgreement(x._1.tag)) ++
        evidenceDisagreements.map(x => Rules.Reason.evidenceDisagreement(x._1.tag))

    def out(
        path: MatchPath,
        outcome: MatchOutcome,
        confidence: Double,
        rules: Vector[String],
        reasons: Vector[String],
        conflicts: Vector[IdentifierConflict] = Vector.empty,
        band: Boolean = false
    ): ResolutionOutcome =
      ResolutionOutcome(
        candidateId = c.candidateId,
        entityKind = c.entityKind,
        sourceIdA = c.sourceIdA,
        sourceIdB = c.sourceIdB,
        sourceDatasetA = c.sourceDatasetA,
        sourceDatasetB = c.sourceDatasetB,
        candidateRunId = c.provenance.runId,
        candidateSnapshot = c.provenance.snapshot,
        blockingVersion = c.blockingVersion,
        featureVersion = c.featureVersion,
        policyVersion = p.policyVersion,
        path = path,
        outcome = outcome,
        confidence = confidence,
        score = s,
        features = f,
        exactIdAgreements = c.exactIdAgreements,
        rulesFired = (rules ++ alwaysRules).distinct,
        reasonCodes = (reasons ++ alwaysReasons).distinct,
        conflicts = IdentifierConflict.canonical(conflicts),
        plausibility = plaus,
        llmBandEligible = band,
        adjudication = None,
        corroboration = corroboration,
        stage = stage,
        policyAgreements = policyAgreements
      )

    def contradicted(
        agreements: Vector[(ExactIdScheme, Vector[ExactId])],
        rule: String
    ): Vector[IdentifierConflict] =
      agreements.map { case (sc, vs) =>
        IdentifierConflict.contradictedAgreement(sc, vs.map(_.value), rule)
      }

    val bdConflicts = bd.map(_._3)
    val identifyingSchemes = identifying.map(_._1.tag)
    val acceptingSchemes = accepting.map(_._1.tag)

    if (t1d.nonEmpty) {
      // Step 2. T1 disagreement: never an automatic acceptance, through any path.
      if (plausible || identifying.nonEmpty) {
        val contradiction = identifying.nonEmpty
        out(
          MatchPath.ExactId,
          MatchOutcome.Conflict,
          p.conflictConfidence,
          plausRule ++ t1d.map(x => Rules.exactConflict(x._1.tag)) ++
            identifyingSchemes.map(Rules.exactAgree) ++
            (if (contradiction) Vector(Rules.IdentifierContradiction) else Vector.empty),
          plausReason ++ t1d.map(x => Rules.Reason.idDisagreement(x._1.tag)) ++
            identifyingSchemes.map(Rules.Reason.exactAgreement) ++
            (if (contradiction) Vector(Rules.Reason.IdentifierContradiction) else Vector.empty),
          t1d.map(_._3) ++ bdConflicts ++
            (if (contradiction) contradicted(identifying, Rules.IdentifierContradiction)
             else Vector.empty)
        )
      } else
        out(
          MatchPath.ExactId,
          MatchOutcome.Rejected,
          p.implausibleDisagreementConfidence,
          plausRule ++ (t1d ++ authorBd).map(x => Rules.disagreeImplausible(x._1.tag)),
          plausReason ++ t1d.map(x => Rules.Reason.idDisagreement(x._1.tag)) :+
            Rules.Reason.DisagreementImplausible,
          t1d.map(_._3) ++ bdConflicts
        )
    } else if (t1a.isEmpty && bd.nonEmpty && t2a.nonEmpty)
      // Step 3. A blocking disagreement contradicted by a same-tier agreement.
      out(
        MatchPath.ExactId,
        MatchOutcome.Conflict,
        p.conflictConfidence,
        (plausRule ++ acceptingSchemes.map(Rules.exactAgree) :+ Rules.IdentifierContradiction) ++
          doiRules,
        (plausReason ++ acceptingSchemes.map(Rules.Reason.exactAgreement) :+
          Rules.Reason.IdentifierContradiction) ++ doiReasons,
        bdConflicts ++ contradicted(accepting, Rules.IdentifierContradiction)
      )
    else if (t1a.isEmpty && authorBd.nonEmpty && !plausible)
      // Step 4. An algorithmic author disagreement on a pair that is not plausible.
      out(
        MatchPath.ExactId,
        MatchOutcome.Rejected,
        p.implausibleDisagreementConfidence,
        plausRule ++ authorBd.map(x => Rules.disagreeImplausible(x._1.tag)) ++ doiRules,
        (plausReason :+ Rules.Reason.DisagreementImplausible) ++ doiReasons,
        bdConflicts
      )
    else if (identifying.nonEmpty && (sameWork || homonymDiffers)) {
      // Step 5. An identifier agreement contradicted by a bibliographic non-identity rule.
      val contradictions =
        (if (sameWork) Vector(Rules.ExactVsSameWork) else Vector.empty) ++
          (if (homonymDiffers) Vector(Rules.ExactVsHomonym) else Vector.empty)
      out(
        MatchPath.ExactId,
        MatchOutcome.Conflict,
        p.conflictConfidence,
        plausRule ++ identifyingSchemes.map(Rules.exactAgree) ++ contradictions,
        plausReason ++ identifyingSchemes.map(Rules.Reason.exactAgreement) :+
          Rules.Reason.ExactVsBibliographic,
        contradictions.flatMap(contradicted(identifying, _)) ++ bdConflicts
      )
    } else if (t1a.nonEmpty) {
      // Step 6a. A T1 agreement accepts and overrides every blocking disagreement.
      val by = t1a.map(_._1.tag)
      val overrides = for { d <- bd; b <- by } yield (d, b)
      out(
        MatchPath.ExactId,
        MatchOutcome.Accepted,
        deciding.map { case (sc, _) => ruleOf(sc.tag).agreementConfidence }.max,
        plausRule ++ decidingSchemes.map(Rules.exactAgree) ++
          overrides.map { case (d, b) => Rules.overridden(d._1.tag, b) },
        plausReason ++ decidingSchemes.map(Rules.Reason.exactAgreement) ++
          overrides.map { case (d, b) => Rules.Reason.overridden(d._2.onDisagreement, d._1.tag, b) }
      )
    } else if (deciding.nonEmpty)
      // Step 6b. A T2 agreement (corroborated when it is a cluster identifier), no blocking
      // disagreement (step 3 handled that).
      out(
        MatchPath.ExactId,
        MatchOutcome.Accepted,
        deciding.map { case (sc, _) => ruleOf(sc.tag).agreementConfidence }.max,
        plausRule ++ decidingSchemes.map(Rules.exactAgree),
        plausReason ++ decidingSchemes.map(Rules.Reason.exactAgreement)
      )
    else if (identifying.nonEmpty) {
      // Step 6c. Only uncorroborated cluster agreements, or an arXiv or preprint-DOI agreement that
      // does not decide (not both pure preprints), with any record-key agreement the preprint-DOI
      // (er-policy.v6) or arXiv (er-policy.v7) block holds back: UNRESOLVED on the exact-id path,
      // never inferred, never LLM.
      val uncorroborated = cluster.nonEmpty && !corroborated
      out(
        MatchPath.ExactId,
        MatchOutcome.Unresolved,
        0.0,
        plausRule ++ (if (uncorroborated) Vector(Rules.ClusterUnresolved) else Vector.empty) ++
          doiRules ++ recordKeyRules,
        plausReason ++ doiReasons ++ recordKeyReasons,
        bdConflicts
      )
    } else {
      // Steps 7 and 8, capped by any blocking disagreement and, for a work pair, by the
      // publication-stage target (er-policy.v4).
      val base = bibliographicOrScore(p, f, s, researcher, sameWork, homonymDiffers, out)
      val stageBlocks = !researcher && !stageEstablished
      val stageReason = stage.toVector.map(x => Rules.Reason.stage(x.verdict))
      if (bd.isEmpty && !stageBlocks) base
      else if (base.outcome == MatchOutcome.Accepted)
        base.copy(
          outcome = MatchOutcome.Unresolved,
          confidence = 0.0,
          rulesFired = (plausRule ++ base.rulesFired ++
            (if (bd.nonEmpty) Vector(Rules.acceptBlocked(base.path)) else Vector.empty) ++
            (if (stageBlocks) Vector(Rules.acceptBlockedByStage(base.path))
             else Vector.empty)).distinct,
          reasonCodes = (plausReason ++ base.reasonCodes ++
            (if (bd.nonEmpty)
               bd.map(d => Rules.Reason.blocks(d._2.onDisagreement, d._1.tag, base.path)) :+
                 Rules.Reason.AcceptBlocked
             else Vector.empty) ++
            (if (stageBlocks) stageReason :+ Rules.Reason.AcceptBlockedByStage
             else Vector.empty)).distinct,
          conflicts = IdentifierConflict.canonical(bdConflicts),
          llmBandEligible = false
        )
      else if (bd.isEmpty && !base.llmBandEligible) base
      else
        base.copy(
          rulesFired = (plausRule ++ base.rulesFired.filterNot(_ == Rules.ScoreLlmBand)).distinct,
          reasonCodes = (plausReason ++ base.reasonCodes.filterNot(_ == Rules.Reason.LlmBand) ++
            (if (base.llmBandEligible && bd.nonEmpty) Vector(Rules.Reason.LlmExcluded)
             else Vector.empty) ++
            (if (base.llmBandEligible && stageBlocks)
               stageReason :+ Rules.Reason.LlmExcludedByStage
             else Vector.empty)).distinct,
          conflicts = IdentifierConflict.canonical(bdConflicts),
          llmBandEligible = false
        )
    }
  }

  private type Out = (
      MatchPath,
      MatchOutcome,
      Double,
      Vector[String],
      Vector[String],
      Vector[IdentifierConflict],
      Boolean
  ) => ResolutionOutcome

  /** Steps 7 and 8: deterministic bibliographic rules, then the probabilistic score. */
  private def bibliographicOrScore(
      p: MatchPolicy,
      f: SortedMap[String, Double],
      s: Score,
      researcher: Boolean,
      sameWork: Boolean,
      homonymDiffers: Boolean,
      out: Out
  ): ResolutionOutcome = {
    val none = Vector.empty[IdentifierConflict]
    if (sameWork)
      out(
        MatchPath.DeterministicBibliographic,
        MatchOutcome.Rejected,
        p.deterministicConfidence,
        Vector(Rules.SameWork),
        Vector(Rules.Reason.SameWork),
        none,
        false
      )
    else if (homonymDiffers)
      out(
        MatchPath.DeterministicBibliographic,
        MatchOutcome.Rejected,
        p.deterministicConfidence,
        Vector(Rules.HomonymDiffers),
        Vector(Rules.Reason.HomonymDiffers),
        none,
        false
      )
    else if (
      researcher && flag(f, "work.doi_agree") && flag(f, "work.position_agree") &&
      flag(f, "work.team_size_agree") && flag(f, "name.family_key_agree") &&
      flag(f, "name.initials_compatible")
    )
      out(
        MatchPath.DeterministicBibliographic,
        MatchOutcome.Accepted,
        p.deterministicConfidence,
        Vector(Rules.SamePaperSamePosition),
        Vector(Rules.Reason.SamePaperSamePosition),
        none,
        false
      )
    else if (
      !researcher && flag(f, "title.exact") && flag(f, "year.both") &&
      f.get("year.abs_diff").contains(0.0) && flag(f, "authors.sequence_exact")
    )
      out(
        MatchPath.DeterministicBibliographic,
        MatchOutcome.Accepted,
        p.deterministicConfidence,
        Vector(Rules.TitleYearAuthorsExact),
        Vector(Rules.Reason.TitleYearAuthorsExact),
        none,
        false
      )
    else {
      val prob = s.probability
      if (prob >= p.thresholds.accept)
        out(
          MatchPath.Probabilistic,
          MatchOutcome.Accepted,
          prob,
          Vector(Rules.ScoreAccept),
          Vector(Rules.Reason.AboveAccept),
          none,
          false
        )
      else if (prob <= p.thresholds.reject)
        out(
          MatchPath.Probabilistic,
          MatchOutcome.Rejected,
          1.0 - prob,
          Vector(Rules.ScoreReject),
          Vector(Rules.Reason.BelowReject),
          none,
          false
        )
      else {
        val band = p.llmBand.contains(prob)
        out(
          MatchPath.Probabilistic,
          MatchOutcome.Unresolved,
          0.0,
          Vector(Rules.ScoreUnresolved) ++ (if (band) Vector(Rules.ScoreLlmBand) else Vector.empty),
          Vector(Rules.Reason.Unresolved) ++ (if (band) Vector(Rules.Reason.LlmBand)
                                              else Vector.empty),
          none,
          band
        )
      }
    }
  }

  /** The valid adjudication of an outcome, if any (refused attempts are never recorded). */
  def validAdjudication(o: ResolutionOutcome): Option[AdjudicationTrace] =
    o.adjudication.filter(_.isValid)

  /** Model and prompt versions recorded in provenance: only when a valid adjudication decided. */
  def llmVersions(o: ResolutionOutcome): (Option[String], Option[String]) =
    validAdjudication(o).fold((Option.empty[String], Option.empty[String]))(t =>
      (Some(t.modelVersion), Some(t.promptVersion))
    )

  /** The core adjudication reference of a valid trace, or why it cannot be expressed. */
  def toReference(t: AdjudicationTrace): Either[String, AdjudicationReference] =
    if (!t.isValid) Left(s"adjudication status ${t.status} is not a valid adjudication")
    else
      t.decision.flatMap(AdjudicationVerdict.fromTag) match {
        case None => Left("a valid adjudication has no accept, reject, or abstain decision")
        case Some(v) =>
          Right(
            AdjudicationReference(
              t.inputHash,
              t.promptVersion,
              t.modelVersion,
              t.policyVersion,
              v,
              t.evidence,
              t.reasonCodes
            )
          )
      }

  /**
   * The core [[MatchDecision]] (`match-decision.v2`) of an outcome, built with
   * [[MatchDecision.build]]: side A = `sourceIdA`, side B = `sourceIdB`, the explicit match path,
   * the score (always recorded), the candidate's blocking and feature versions, the identifier
   * conflicts, and the adjudication reference of a valid LLM adjudication.
   * `provenance.sourceRecordId` = the candidate ID, `provenance.snapshot` = the candidate's
   * snapshot, `provenance.runId` = the decision run; model and prompt versions are recorded only
   * with a valid adjudication.
   */
  def toMatchDecision(
      o: ResolutionOutcome,
      ctx: DecisionContext
  ): Either[Vector[String], MatchDecision] = {
    val (model, prompt) = llmVersions(o)
    val prov = Provenance(
      SourceType.Derived,
      o.candidateId,
      o.candidateSnapshot,
      ctx.runId,
      SchemaVersions.MatchCandidate,
      Some(o.policyVersion),
      model,
      prompt
    )
    val reference: Either[Vector[String], Option[AdjudicationReference]] =
      validAdjudication(o) match {
        case None => Right(None)
        case Some(t) => toReference(t).map(Some(_)).left.map(Vector(_))
      }
    reference.flatMap { ref =>
      MatchDecision
        .build(
          candidateId = o.candidateId,
          entityKind = o.entityKind,
          sourceId = o.sourceIdA,
          targetId = o.sourceIdB,
          matchPath = o.path.toCore,
          decision = o.outcome,
          confidence = o.confidence,
          score = Some(o.score.probability),
          blockingVersion = o.blockingVersion,
          featureVersion = o.featureVersion,
          features = o.features,
          rulesFired = o.rulesFired,
          reasonCodes = o.reasonCodes,
          conflicts = o.conflicts,
          adjudication = ref,
          decidedAt = ctx.decidedAt,
          provenance = prov
        )
        .left
        .map(_.map(_.message))
    }
  }
}
