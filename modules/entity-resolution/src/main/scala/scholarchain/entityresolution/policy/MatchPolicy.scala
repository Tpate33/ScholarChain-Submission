package scholarchain.entityresolution.policy

import java.io.InputStream
import java.nio.charset.StandardCharsets

import scala.util.control.NonFatal

import upickle.default.{read, ReadWriter}
import upickle.implicits.allowUnknownKeys

import scholarchain.core.{ExactIdScheme, MatchDecisionPath, MatchEntityKind, SourceType, StableId}
import scholarchain.core.config.{EntityResolutionConfig, ModelConfig}
import scholarchain.entityresolution.{BlockingKeys, MatchFeatures}

/**
 * Match path: the level of the evidence hierarchy that produced a decision. Tags are frozen and
 * equal the core [[MatchDecisionPath]] tags ([[MatchPath.toCore]]); they appear in rule IDs,
 * review-queue rows, and evaluation reports.
 */
enum MatchPath(val tag: String) {

  /** Exact identifier agreement, or disagreeing identifiers (CONFLICT or REJECTED). */
  case ExactId extends MatchPath("exact-id")

  /** Exact normalized bibliographic fields (deterministic rules, no score). */
  case DeterministicBibliographic extends MatchPath("deterministic-bibliographic")

  /** Blocked probabilistic scoring against the accept and reject thresholds. */
  case Probabilistic extends MatchPath("probabilistic")

  /** The bounded unresolved band, sent to the LLM adjudicator port (accept, reject, or abstain). */
  case LlmAdjudicated extends MatchPath("llm-adjudicated")

  /** The core match-decision path with the same tag. */
  def toCore: MatchDecisionPath = this match {
    case ExactId => MatchDecisionPath.ExactId
    case DeterministicBibliographic => MatchDecisionPath.DeterministicBibliographic
    case Probabilistic => MatchDecisionPath.Probabilistic
    case LlmAdjudicated => MatchDecisionPath.LlmAdjudicated
  }
}

object MatchPath {
  def fromTag(tag: String): Option[MatchPath] = values.find(_.tag == tag)

  /**
   * The path named by the rule IDs of a decision: every rule ID starts with its path tag and a `.`
   * ([[Rules]]). The deepest path wins (LLM over probabilistic), because an adjudicated decision
   * also records the probabilistic rule that put it in the band. Decisions record their path
   * explicitly; this is a cross-check.
   */
  def ofRules(rules: Vector[String]): Option[MatchPath] =
    values.reverse.find(p => rules.exists(_.startsWith(p.tag + ".")))
}

/** Accept and reject thresholds on the match score; scores strictly between are UNRESOLVED. */
@allowUnknownKeys(false)
final case class Thresholds(accept: Double, reject: Double) derives ReadWriter

/** The bounded unresolved score band `[lower, upper]` that may be sent to LLM adjudication. */
@allowUnknownKeys(false)
final case class LlmBand(lower: Double, upper: Double) derives ReadWriter {
  def contains(score: Double): Boolean = score >= lower && score <= upper
}

/**
 * LLM adjudication settings. The model ID is never stored here: it is read from the configuration
 * key named by `modelIdConfigKey` (core `models.llmModelId`).
 */
@allowUnknownKeys(false)
final case class AdjudicationSettings(
    promptVersion: String,
    responseSchemaVersion: String,
    modelIdConfigKey: String,
    maxEvidenceItems: Int,
    maxReasonCodes: Int
) derives ReadWriter

/**
 * The exact-identifier rule of one [[ExactIdScheme]] (README Phase 5 exact identifier rules). Every
 * scheme of an entity kind has exactly one rule, so no scheme is silently omitted.
 *
 * Tiers (er-policy.v3, human decisions 2026-11-06): a scheme whose disagreement is
 * `conflict-if-plausible` is tier T1 (orcid, dblp-pid, doi, arxiv); every other scheme that
 * identifies on agreement is tier T2; `repository-url` (evidence only both ways) is T3.
 *
 * @param scheme
 *   core scheme tag
 * @param scope
 *   what one value identifies (documentation, recorded with the policy version)
 * @param reliability
 *   how the source assigns the value and its known failure modes (documentation)
 * @param onAgreement
 *   [[IdentifierRule.Accept]]: a shared canonical value decides ACCEPTED on the exact-id path
 *   (unless a disagreement or a bibliographic rule contradicts it);
 *   [[IdentifierRule.AcceptIfCorroborated]]: a cluster identifier. A shared value decides ACCEPTED
 *   only with corroborating publication-stage evidence ([[Corroboration]]); otherwise the pair is
 *   UNRESOLVED (reason code `cluster-id-agreement-uncorroborated.<scheme>`). Allowed only for work
 *   schemes whose disagreement is `evidence-only`. [[IdentifierRule.EvidenceOnly]]: the agreement
 *   is recorded as a reason code and never decides
 * @param agreementConfidence
 *   policy confidence constant (uncalibrated) of an ACCEPTED decided by this scheme; in (0, 1] for
 *   `accept` and `accept-if-corroborated`, exactly 0 for `evidence-only`
 * @param agreementRationale
 *   why an agreement of this scheme does or does not need corroboration (documentation)
 * @param onDisagreement
 *   [[IdentifierRule.ConflictIfPlausible]] (T1): CONFLICT when the pair is a plausible identity
 *   match ([[Plausibility]]) or another identifier agrees; REJECTED otherwise. Nothing overrides
 *   it; it prevents every automatic acceptance, through any path.
 *   [[IdentifierRule.BlockInferredAcceptanceIfPlausible]] (algorithmic author IDs): never CONFLICT
 *   on its own. On a plausible pair it blocks inferred acceptance (deterministic, probabilistic,
 *   LLM: UNRESOLVED, never sent to the LLM); on an implausible pair it yields REJECTED. A T1
 *   agreement overrides it (reason code); a T2 agreement contradicts it (CONFLICT).
 *   [[IdentifierRule.BlockInferredAcceptance]] (curated record keys, P-2): never CONFLICT and never
 *   REJECTED on its own, and it never triggers the plausibility test. It blocks inferred
 *   acceptance; a T1 agreement overrides it (reason code); a T2 agreement contradicts it
 *   (CONFLICT). [[IdentifierRule.EvidenceOnly]] (P-1): recorded as a reason code only; never
 *   blocks, decides, or enters `conflicts`.
 */
@allowUnknownKeys(false)
final case class IdentifierRule(
    scheme: String,
    scope: String,
    reliability: String,
    onAgreement: String,
    agreementConfidence: Double,
    agreementRationale: String,
    onDisagreement: String
) derives ReadWriter {

  /** The agreement identifies (decides, possibly after corroboration): not evidence only. */
  def identifiesOnAgreement: Boolean =
    onAgreement == IdentifierRule.Accept || onAgreement == IdentifierRule.AcceptIfCorroborated

  /** A cluster identifier: an agreement needs corroborating publication-stage evidence. */
  def requiresCorroboration: Boolean = onAgreement == IdentifierRule.AcceptIfCorroborated

  /** Tier T1: an authoritative disagreement (`conflict-if-plausible`). */
  def isTier1: Boolean = onDisagreement == IdentifierRule.ConflictIfPlausible

  /** An algorithmic author disagreement: blocks inferred acceptance, REJECTED when implausible. */
  def isAlgorithmicAuthor: Boolean =
    onDisagreement == IdentifierRule.BlockInferredAcceptanceIfPlausible

  /** A curated record-key disagreement: blocks inferred acceptance only. */
  def isRecordKey: Boolean = onDisagreement == IdentifierRule.BlockInferredAcceptance

  /** Either blocking disposition. */
  def blocksInferredAcceptance: Boolean = isAlgorithmicAuthor || isRecordKey

  def isEvidenceOnlyDisagreement: Boolean = onDisagreement == IdentifierRule.EvidenceOnly
}

object IdentifierRule {
  val Accept = "accept"
  val AcceptIfCorroborated = "accept-if-corroborated"
  val EvidenceOnly = "evidence-only"
  val ConflictIfPlausible = "conflict-if-plausible"
  val BlockInferredAcceptanceIfPlausible = "block-inferred-acceptance-if-plausible"
  val BlockInferredAcceptance = "block-inferred-acceptance"
  val AgreementValues: Set[String] = Set(Accept, AcceptIfCorroborated, EvidenceOnly)
  val DisagreementValues: Set[String] =
    Set(
      ConflictIfPlausible,
      BlockInferredAcceptanceIfPlausible,
      BlockInferredAcceptance,
      EvidenceOnly
    )
}

/**
 * "Corroborating publication-stage evidence" for a cluster-identifier agreement
 * ([[Corroboration.work]]; human decision 4, 2026-11-06). `definition` and `rationale` document the
 * test in the versioned policy file; the code is authoritative and a test checks that the
 * documented bounds are the ones applied.
 *
 * @param venueNameJaccardMin
 *   minimum `venue.name_jaccard` when venue IDs are not both present and equal
 * @param maxYearDiff
 *   maximum `year.abs_diff` (both years required)
 * @param titleSimMin
 *   minimum `title.sim` when the folded titles are not equal
 * @param preprintDoiPrefixes
 *   DOI prefixes registered for preprints (canonical lower case, for example `10.48550/` for arXiv)
 */
@allowUnknownKeys(false)
final case class WorkCorroboration(
    definition: String,
    rationale: String,
    venueNameJaccardMin: Double,
    maxYearDiff: Double,
    titleSimMin: Double,
    preprintDoiPrefixes: Vector[String]
) derives ReadWriter

/**
 * The publication-stage target of er-policy.v4 ([[PublicationStage]]; human decision of 2026-11-06,
 * decisions-06). `definition` and `rationale` document the check in the versioned policy file; the
 * code is authoritative, and a test checks that the definition names every verdict. It reuses the
 * preprint DOI prefixes and the venue bound of [[WorkCorroboration]].
 */
@allowUnknownKeys(false)
final case class PublicationStagePolicy(definition: String, rationale: String) derives ReadWriter

/**
 * Bounds of the researcher "plausible identity match" test ([[Plausibility.researcher]]).
 * `definition` and `rationale` document the test in the versioned policy file; the code is
 * authoritative and a test checks that the documented bounds are the ones applied.
 *
 * @param familySimMin
 *   minimum Levenshtein similarity of the family block keys when the keys are not equal
 * @param missingGivenNamesCompatible
 *   whether a pair where at least one side has no given name passes the initials clause (nothing
 *   contradicts it)
 */
@allowUnknownKeys(false)
final case class ResearcherPlausibility(
    definition: String,
    rationale: String,
    familySimMin: Double,
    missingGivenNamesCompatible: Boolean
) derives ReadWriter

/**
 * Bounds of the work "plausible identity match" test ([[Plausibility.work]]).
 *
 * @param titleSimMin
 *   minimum Levenshtein similarity of the folded titles (clause T)
 * @param titleJaccardWithAuthorsMin
 *   minimum title content-token Jaccard in clause TA
 * @param authorSequenceLcsMin
 *   minimum author family-sequence LCS ratio in clause TA
 */
@allowUnknownKeys(false)
final case class WorkPlausibility(
    definition: String,
    rationale: String,
    titleSimMin: Double,
    titleJaccardWithAuthorsMin: Double,
    authorSequenceLcsMin: Double
) derives ReadWriter

/**
 * Per-entity-kind policy.
 *
 * @param identifierRules
 *   one rule per exact identifier scheme of the kind
 * @param bias
 *   intercept of the logistic score
 * @param weights
 *   feature name to weight (features of the candidate's `featureVersion`, plus the policy feature
 *   [[Scoring.SourceReliabilityFeature]])
 * @param caps
 *   feature name to an upper cap applied before weighting (counts and differences)
 */
@allowUnknownKeys(false)
final case class KindPolicy(
    identifierRules: Vector[IdentifierRule],
    bias: Double,
    weights: Map[String, Double],
    caps: Map[String, Double]
) derives ReadWriter {

  /** The rule of a scheme (every scheme of the kind has one in a valid policy). */
  def rule(s: ExactIdScheme): Option[IdentifierRule] = identifierRules.find(_.scheme == s.tag)
}

/**
 * Source reliability prior: `sourceReliability(dataset tag)` in [0, 1]; the policy feature value of
 * a pair is the minimum over its two sources minus `center`.
 */
@allowUnknownKeys(false)
final case class SourceReliability(center: Double, bySource: Map[String, Double]) derives ReadWriter

/**
 * A versioned scoring policy (README Phase 5 "versioned scoring policy"). Loaded from the module
 * resource [[MatchPolicy.DefaultResource]]; thresholds and the LLM band are overridden from the
 * core configuration ([[MatchPolicy.withConfig]]). The policy never reads labels, review-queue
 * decisions, or evaluation outputs.
 *
 * Every confidence value is a policy confidence constant (uncalibrated): a hand-set number, not a
 * measured probability that the outcome is correct (`confidenceDefinition`).
 *
 * @param calibrated
 *   false until the thresholds and weights have been fitted against human gold labels
 * @param confidenceDefinition
 *   what the confidence values mean (documentation; must name them uncalibrated policy constants)
 * @param evaluationOrder
 *   the documented order of evaluation ([[Resolver]] is authoritative)
 * @param conflictConfidence
 *   policy confidence constant (uncalibrated) of a CONFLICT decision
 * @param implausibleDisagreementConfidence
 *   policy confidence constant (uncalibrated) of a REJECTED decided by a disagreement on a pair
 *   that is not a plausible match
 * @param deterministicConfidence
 *   policy confidence constant (uncalibrated) of a deterministic-bibliographic decision
 * @param workCorroboration
 *   corroborating publication-stage evidence for cluster-identifier agreements
 * @param publicationStage
 *   the same-stage check of inferred acceptance, arXiv acceptance, and LLM band eligibility
 */
@allowUnknownKeys(false)
final case class MatchPolicy(
    schemaVersion: String,
    policyVersion: String,
    blockingVersion: String,
    featureVersion: String,
    calibrated: Boolean,
    confidenceDefinition: String,
    evaluationOrder: Vector[String],
    thresholds: Thresholds,
    llmBand: LlmBand,
    conflictConfidence: Double,
    implausibleDisagreementConfidence: Double,
    deterministicConfidence: Double,
    adjudication: AdjudicationSettings,
    sourceReliability: SourceReliability,
    researcherPlausibility: ResearcherPlausibility,
    workPlausibility: WorkPlausibility,
    workCorroboration: WorkCorroboration,
    publicationStage: PublicationStagePolicy,
    researcher: KindPolicy,
    work: KindPolicy
) derives ReadWriter {

  def kind(k: MatchEntityKind): Option[KindPolicy] = k match {
    case MatchEntityKind.Researcher => Some(researcher)
    case MatchEntityKind.Work => Some(work)
    case _ => None
  }
}

object MatchPolicy {

  /** Schema of the policy file itself. */
  val FileSchemaVersion: String = "er-policy-file.v8"

  /**
   * Retired policy file schemas: refused with a pointer to the current one. `er-policy-file.v2`
   * (er-policy.v2) is retired from active use by human decision 5 (2026-11-06, P-3);
   * `er-policy-file.v3` (er-policy.v3) by the publication-stage decision of 2026-11-06
   * (decisions-06); `er-policy-file.v4` (er-policy.v4) by the preprint-DOI decision of 2026-11-11
   * (decisions-07, Option 1); `er-policy-file.v5` (er-policy.v5) by the record-key decision of
   * 2026-11-11 (decisions-08, "Block it"); `er-policy-file.v6` (er-policy.v6) by the arXiv
   * record-key decision of 2026-11-11 (decisions-09 item 1); `er-policy-file.v7` (er-policy.v7) by
   * the general record-key stage block and the arXiv base-ID comparison of 2026-11-12 (decisions-11
   * items 2 and 3).
   */
  val RetiredFileSchemaVersions: Set[String] =
    Set(
      "er-policy-file.v1",
      "er-policy-file.v2",
      "er-policy-file.v3",
      "er-policy-file.v4",
      "er-policy-file.v5",
      "er-policy-file.v6",
      "er-policy-file.v7"
    )

  /** Retired policy versions: refused by name even inside a current file schema. */
  val RetiredPolicyVersions: Set[String] =
    Set(
      "er-policy.v1",
      "er-policy.v2",
      "er-policy.v3",
      "er-policy.v4",
      "er-policy.v5",
      "er-policy.v6",
      "er-policy.v7"
    )

  /**
   * Retired adjudication prompt versions: a policy naming one is refused, and so is a prompt
   * template that declares one. `er-adjudication-prompt.v1` asked for "the same published work"
   * without the publication-stage target (decisions-06).
   */
  val RetiredPromptVersions: Set[String] = Set("er-adjudication-prompt.v1")

  /** Class-path resource of the default policy. */
  val DefaultResource: String = "/scholarchain/entityresolution/policy/er-policy.v8.json"

  /** The superseded er-policy.v1 resource (kept only to prove that it is refused). */
  val RetiredV1Resource: String = "/scholarchain/entityresolution/policy/er-policy.v1.json"

  /** The superseded er-policy.v2 resource (kept as history and to prove that it is refused). */
  val RetiredV2Resource: String = "/scholarchain/entityresolution/policy/er-policy.v2.json"

  /** The superseded er-policy.v3 resource (kept as history and to prove that it is refused). */
  val RetiredV3Resource: String = "/scholarchain/entityresolution/policy/er-policy.v3.json"

  /** The superseded er-policy.v4 resource (kept as history and to prove that it is refused). */
  val RetiredV4Resource: String = "/scholarchain/entityresolution/policy/er-policy.v4.json"

  /** The superseded er-policy.v5 resource (kept as history and to prove that it is refused). */
  val RetiredV5Resource: String = "/scholarchain/entityresolution/policy/er-policy.v5.json"

  /** The superseded er-policy.v6 resource (kept as history and to prove that it is refused). */
  val RetiredV6Resource: String = "/scholarchain/entityresolution/policy/er-policy.v6.json"

  /** The superseded er-policy.v7 resource (kept as history and to prove that it is refused). */
  val RetiredV7Resource: String = "/scholarchain/entityresolution/policy/er-policy.v7.json"

  /**
   * The canonical form of one `workCorroboration.preprintDoiPrefixes` entry: a DOI registrant
   * prefix `10.<4 to 9 ASCII digits>/`, ending in its slash (RV-P5-06 nit 2). Canonical DOIs are
   * lower case and start with exactly such a prefix, so any other form (upper case, no slash, a
   * suffix part, surrounding space) would never match and would silently disable the stage block.
   */
  val PreprintDoiPrefixPattern: scala.util.matching.Regex = "^10\\.[0-9]{4,9}/$".r

  /** Verdict tags the `publicationStage.definition` must name ([[StageVerdict]]). */
  val StageVerdictTags: Vector[String] = StageVerdict.values.toVector.map(_.tag)

  /** The phrase every policy's `confidenceDefinition` must contain (human decision 5, P-4). */
  val ConfidenceWording: String = "policy confidence constant (uncalibrated)"

  /** The only model-ID configuration key the adjudication step reads. */
  val ModelIdConfigKey: String = "models.llmModelId"

  private def featureNames(k: MatchEntityKind): Set[String] = k match {
    case MatchEntityKind.Researcher => MatchFeatures.ResearcherFeatureNames.toSet
    case _ => MatchFeatures.WorkFeatureNames.toSet
  }

  private def finite(d: Double): Boolean = !d.isNaN && !d.isInfinite
  private def prob(d: Double): Boolean = finite(d) && d >= 0.0 && d <= 1.0
  private def positiveProb(d: Double): Boolean = prob(d) && d > 0.0

  /** Every problem of a policy (empty when valid). */
  def validate(p: MatchPolicy): Vector[String] = {
    val out = Vector.newBuilder[String]
    def need(ok: Boolean, msg: => String): Unit = if (!ok) out += msg
    need(p.schemaVersion == FileSchemaVersion, s"schemaVersion must be $FileSchemaVersion")
    need(StableId.isToken(p.policyVersion), "policyVersion must be a token")
    need(
      p.blockingVersion == BlockingKeys.Version,
      s"blockingVersion ${p.blockingVersion} differs from the key formulas ${BlockingKeys.Version}"
    )
    need(
      p.featureVersion == MatchFeatures.Version,
      s"featureVersion ${p.featureVersion} differs from the feature functions ${MatchFeatures.Version}"
    )
    val t = p.thresholds
    val b = p.llmBand
    need(prob(t.accept) && prob(t.reject), "thresholds must be in [0, 1]")
    need(t.reject < t.accept, "thresholds.reject must be below thresholds.accept")
    need(prob(b.lower) && prob(b.upper), "llmBand bounds must be in [0, 1]")
    need(b.lower < b.upper, "llmBand.lower must be below llmBand.upper")
    need(
      b.lower > t.reject && b.upper < t.accept,
      "llmBand must lie strictly inside the unresolved interval (reject, accept)"
    )
    need(positiveProb(p.conflictConfidence), "conflictConfidence must be in (0, 1]")
    need(
      positiveProb(p.implausibleDisagreementConfidence),
      "implausibleDisagreementConfidence must be in (0, 1]"
    )
    need(positiveProb(p.deterministicConfidence), "deterministicConfidence must be in (0, 1]")
    val a = p.adjudication
    need(StableId.isToken(a.promptVersion), "adjudication.promptVersion must be a token")
    need(
      !RetiredPromptVersions.contains(a.promptVersion),
      s"adjudication.promptVersion ${a.promptVersion} is retired from active use"
    )
    need(
      StableId.isToken(a.responseSchemaVersion),
      "adjudication.responseSchemaVersion must be a token"
    )
    need(
      a.modelIdConfigKey == ModelIdConfigKey,
      s"adjudication.modelIdConfigKey must be $ModelIdConfigKey"
    )
    need(a.maxEvidenceItems >= 1 && a.maxEvidenceItems <= 64, "maxEvidenceItems in [1, 64]")
    need(a.maxReasonCodes >= 1 && a.maxReasonCodes <= 16, "maxReasonCodes in [1, 16]")
    val sr = p.sourceReliability
    need(prob(sr.center), "sourceReliability.center must be in [0, 1]")
    val datasets = SourceType.values.filter(_.isDataset).map(_.tag).toSet
    need(
      sr.bySource.keySet == datasets,
      s"sourceReliability.bySource must name exactly ${datasets.toVector.sorted.mkString(", ")}"
    )
    sr.bySource.foreach { case (s, v) => need(prob(v), s"sourceReliability.$s must be in [0, 1]") }
    val rp = p.researcherPlausibility
    need(
      prob(rp.familySimMin) && rp.familySimMin > 0,
      "researcherPlausibility.familySimMin in (0, 1]"
    )
    val wp = p.workPlausibility
    Vector(rp.definition, rp.rationale, wp.definition, wp.rationale).foreach { d =>
      need(Option(d).exists(_.trim.nonEmpty), "plausibility definition and rationale required")
    }
    need(prob(wp.titleSimMin) && wp.titleSimMin > 0, "workPlausibility.titleSimMin in (0, 1]")
    need(
      prob(wp.titleJaccardWithAuthorsMin) && wp.titleJaccardWithAuthorsMin > 0,
      "workPlausibility.titleJaccardWithAuthorsMin in (0, 1]"
    )
    need(
      prob(wp.authorSequenceLcsMin) && wp.authorSequenceLcsMin > 0,
      "workPlausibility.authorSequenceLcsMin in (0, 1]"
    )
    Vector(MatchEntityKind.Researcher -> p.researcher, MatchEntityKind.Work -> p.work).foreach {
      case (k, kp) =>
        val names = featureNames(k) + Scoring.SourceReliabilityFeature
        val schemes = ExactIdScheme.values.filter(_.entityKind == k).map(_.tag).toSet
        need(finite(kp.bias), s"${k.tag}.bias must be finite")
        kp.weights.foreach { case (f, w) =>
          need(names.contains(f), s"${k.tag}.weights: unknown feature '$f'")
          need(finite(w), s"${k.tag}.weights.$f must be finite")
        }
        kp.caps.foreach { case (f, c) =>
          need(kp.weights.contains(f), s"${k.tag}.caps: '$f' has no weight")
          need(finite(c) && c > 0, s"${k.tag}.caps.$f must be positive")
        }
        val ruleSchemes = kp.identifierRules.map(_.scheme)
        need(
          ruleSchemes.distinct.size == ruleSchemes.size,
          s"${k.tag}.identifierRules: duplicate scheme"
        )
        ruleSchemes.filterNot(schemes.contains).foreach { s =>
          need(false, s"${k.tag}.identifierRules: '$s' is not a ${k.tag} scheme")
        }
        schemes.toVector.sorted.filterNot(ruleSchemes.contains).foreach { s =>
          need(false, s"${k.tag}.identifierRules: no rule for scheme '$s'")
        }
        kp.identifierRules.foreach { r =>
          val f = s"${k.tag}.identifierRules.${r.scheme}"
          need(
            IdentifierRule.AgreementValues.contains(r.onAgreement),
            s"$f.onAgreement must be one of ${IdentifierRule.AgreementValues.toVector.sorted.mkString(", ")}"
          )
          need(
            IdentifierRule.DisagreementValues.contains(r.onDisagreement),
            s"$f.onDisagreement must be one of ${IdentifierRule.DisagreementValues.toVector.sorted.mkString(", ")}"
          )
          if (r.identifiesOnAgreement)
            need(positiveProb(r.agreementConfidence), s"$f.agreementConfidence must be in (0, 1]")
          else
            need(
              r.agreementConfidence == 0.0,
              s"$f.agreementConfidence must be 0 when onAgreement is evidence-only"
            )
          if (r.requiresCorroboration) {
            need(
              k == MatchEntityKind.Work,
              s"$f: accept-if-corroborated is defined for work schemes only"
            )
            need(
              r.isEvidenceOnlyDisagreement,
              s"$f: accept-if-corroborated requires onDisagreement evidence-only (a cluster ID)"
            )
          }
          if (r.isTier1)
            need(
              r.onAgreement == IdentifierRule.Accept,
              s"$f: a T1 scheme must accept on agreement"
            )
          need(
            Option(r.scope).exists(_.trim.nonEmpty) && Option(r.reliability)
              .exists(_.trim.nonEmpty) && Option(r.agreementRationale).exists(_.trim.nonEmpty),
            s"$f: scope, reliability and agreementRationale must be documented"
          )
        }
    }
    need(
      !RetiredPolicyVersions.contains(p.policyVersion),
      s"policyVersion ${p.policyVersion} is retired from active use"
    )
    need(
      Option(p.confidenceDefinition).exists(_.contains(ConfidenceWording)),
      s"confidenceDefinition must call every confidence a $ConfidenceWording"
    )
    need(
      Option(p.evaluationOrder).exists(o =>
        o.nonEmpty && o.forall(s => s != null && s.trim.nonEmpty)
      ),
      "evaluationOrder must document the order of the policy steps"
    )
    val wc = p.workCorroboration
    need(
      Option(wc).exists(w =>
        Option(w.definition).exists(_.trim.nonEmpty) && Option(w.rationale).exists(_.trim.nonEmpty)
      ),
      "workCorroboration definition and rationale required"
    )
    Option(wc).foreach { w =>
      need(
        prob(w.venueNameJaccardMin) && w.venueNameJaccardMin > 0,
        "workCorroboration.venueNameJaccardMin in (0, 1]"
      )
      need(prob(w.titleSimMin) && w.titleSimMin > 0, "workCorroboration.titleSimMin in (0, 1]")
      need(
        finite(w.maxYearDiff) && w.maxYearDiff >= 0,
        "workCorroboration.maxYearDiff must be finite and non-negative"
      )
      need(Option(w.preprintDoiPrefixes).nonEmpty, "workCorroboration.preprintDoiPrefixes required")
      Option(w.preprintDoiPrefixes).toVector.flatten.foreach { x =>
        need(
          x != null && PreprintDoiPrefixPattern.matches(x),
          s"workCorroboration.preprintDoiPrefixes entry '${String.valueOf(x)}' is not a canonical " +
            "DOI prefix 10.<registrant digits>/ (lower case, ending in /)"
        )
      }
    }
    val ps = p.publicationStage
    need(
      Option(ps).exists(s =>
        Option(s.definition).exists(_.trim.nonEmpty) && Option(s.rationale).exists(_.trim.nonEmpty)
      ),
      "publicationStage definition and rationale required"
    )
    Option(ps).flatMap(s => Option(s.definition)).foreach { d =>
      StageVerdictTags.filterNot(d.contains).foreach { t =>
        need(false, s"publicationStage.definition must name the verdict '$t'")
      }
    }
    out.result()
  }

  /** Parses and validates a policy document. Retired file schemas are refused by name. */
  def parse(json: String): Either[Vector[String], MatchPolicy] = {
    val retired =
      RetiredFileSchemaVersions.find(v => Option(json).exists(_.contains("\"" + v + "\"")))
    retired match {
      case Some(v) =>
        Left(Vector(s"policy file schema $v is retired; use $FileSchemaVersion"))
      case None =>
        (try Right(read[MatchPolicy](json))
        catch { case NonFatal(e) => Left(Vector(s"policy is not decodable: ${e.getMessage}")) })
          .flatMap { p =>
            val problems = validate(p)
            if (problems.isEmpty) Right(p) else Left(problems)
          }
    }
  }

  /** Reads a class-path resource of this module as UTF-8 text. */
  private[entityresolution] def resourceText(path: String): Either[String, String] = {
    val in: InputStream = getClass.getResourceAsStream(path)
    if (in == null) Left(s"resource $path not found")
    else
      try Right(new String(in.readAllBytes(), StandardCharsets.UTF_8))
      catch { case NonFatal(e) => Left(s"resource $path unreadable: ${e.getMessage}") }
      finally in.close()
  }

  /** The default versioned policy of this module. */
  def loadDefault(): Either[Vector[String], MatchPolicy] =
    resourceText(DefaultResource).left.map(Vector(_)).flatMap(parse)

  /**
   * Applies the core configuration (`scholarchain.config.v2`):
   *   - the configuration must be valid ([[EntityResolutionConfig.problems]]);
   *   - `entityResolution.policyVersion` must name this policy;
   *   - `entityResolution.promptVersion` must equal the policy's adjudication prompt version, so a
   *     run never pairs a policy with a prompt it was not written for;
   *   - `acceptThreshold`, `rejectThreshold`, `llmBandLower` and `llmBandUpper` replace the policy
   *     thresholds and band. The result is validated again.
   */
  def withConfig(p: MatchPolicy, c: EntityResolutionConfig): Either[Vector[String], MatchPolicy] =
    if (c == null) Left(Vector("entityResolution configuration is missing"))
    else {
      val configProblems = EntityResolutionConfig.problems(c).map { case (k, why) => s"$k: $why" }
      if (configProblems.nonEmpty) Left(configProblems)
      else if (c.policyVersion != p.policyVersion)
        Left(
          Vector(
            s"configured entityResolution.policyVersion '${c.policyVersion}' differs from the " +
              s"policy '${p.policyVersion}'"
          )
        )
      else if (c.promptVersion != p.adjudication.promptVersion)
        Left(
          Vector(
            s"configured entityResolution.promptVersion '${c.promptVersion}' differs from the " +
              s"policy's adjudication prompt '${p.adjudication.promptVersion}'; the run is refused"
          )
        )
      else {
        val q = p.copy(
          thresholds = Thresholds(c.acceptThreshold, c.rejectThreshold),
          llmBand = LlmBand(c.llmBandLower, c.llmBandUpper)
        )
        val problems = validate(q)
        if (problems.isEmpty) Right(q) else Left(problems)
      }
    }

  /** The adjudication model ID from the core configuration key [[ModelIdConfigKey]]. */
  def modelId(models: ModelConfig): Either[String, String] =
    Option(models)
      .map(_.llmModelId)
      .filter(m => m != null && m.nonEmpty && m.trim == m && !m.exists(_.isControl))
      .toRight(s"$ModelIdConfigKey is not configured")
}
