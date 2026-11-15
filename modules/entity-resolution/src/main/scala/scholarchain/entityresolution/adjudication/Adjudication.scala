package scholarchain.entityresolution.adjudication

import scala.collection.mutable
import scala.util.control.NonFatal

import scholarchain.core.{MatchEntityKind, MatchOutcome}
import scholarchain.entityresolution.policy.{
  AdjudicationTrace,
  MatchPath,
  MatchPolicy,
  ResolutionOutcome,
  Rules
}

/**
 * The LLM adjudicator port. An implementation sends `prompt` to model `modelId` and returns the raw
 * response text, or a failure description. It receives only the rendered prompt: no record,
 * candidate, or writable state is ever passed to it. This module contains no implementation that
 * calls a network service; concrete clients belong to a runtime module and need build approval.
 */
trait Adjudicator {
  def complete(modelId: String, prompt: String): Either[String, String]
}

/** Cache of validated adjudications, keyed by [[AdjudicationCacheKey]]. */
trait AdjudicationCache {
  def get(key: AdjudicationCacheKey): Option[ValidatedResponse]
  def put(key: AdjudicationCacheKey, value: ValidatedResponse): Unit
}

/** A process-local cache (tests and bounded local runs). */
final class InMemoryAdjudicationCache extends AdjudicationCache {
  private val entries = mutable.LinkedHashMap.empty[AdjudicationCacheKey, ValidatedResponse]
  def get(key: AdjudicationCacheKey): Option[ValidatedResponse] =
    synchronized(entries.get(key))
  def put(key: AdjudicationCacheKey, value: ValidatedResponse): Unit =
    synchronized { entries.update(key, value) }
  def size: Int = synchronized(entries.size)
}

/**
 * LLM adjudication, bounded to the policy's unresolved band. Only an outcome that is UNRESOLVED on
 * the probabilistic path, flagged band-eligible, carrying no identifier conflict, not yet validly
 * adjudicated, and whose score lies inside `llmBand` is sent; any other outcome is returned
 * unchanged without a call. A validated `accept`, `reject`, or `abstain` sets ACCEPTED, REJECTED,
 * or UNRESOLVED on the `llm-adjudicated` path. A refused response (adapter failure or a response
 * that fails the strict schema) is NOT an adjudication: the outcome stays UNRESOLVED on the
 * probabilistic path, gains the rule `probabilistic.llm-response-refused`, and keeps the refused
 * attempt only as an in-memory trace, which is never mapped to an adjudication reference or to
 * model and prompt provenance ([[Resolver.toMatchDecision]]). Only validated responses are cached.
 * The outcome's IDs, features, and source fields are copied from the input outcome and never from
 * the response.
 */
object Adjudication {

  /**
   * True when the outcome may be sent to the adjudicator under this policy. A work pair is eligible
   * only when its same publication stage is established ([[ResolutionOutcome.stage]],
   * er-policy.v4): a cross-stage or uncertain-stage pair, or a work outcome without a stage check,
   * is never sent.
   */
  def eligible(p: MatchPolicy, o: ResolutionOutcome): Boolean =
    o.outcome == MatchOutcome.Unresolved && o.path == MatchPath.Probabilistic &&
      o.llmBandEligible && o.conflicts.isEmpty && !o.adjudication.exists(_.isValid) &&
      o.policyVersion == p.policyVersion && p.llmBand.contains(o.score.probability) &&
      (o.entityKind != MatchEntityKind.Work || o.stage.exists(_.established))

  def adjudicate(
      p: MatchPolicy,
      o: ResolutionOutcome,
      a: EndpointEvidence,
      b: EndpointEvidence,
      modelId: String,
      template: AdjudicationProtocol.PromptTemplate,
      schemaJson: String,
      adjudicator: Adjudicator,
      cache: AdjudicationCache
  ): Either[Vector[String], ResolutionOutcome] =
    if (!eligible(p, o)) Right(o)
    else if (template.version != p.adjudication.promptVersion)
      Left(
        Vector(
          s"prompt template version ${template.version} differs from the policy's " +
            p.adjudication.promptVersion
        )
      )
    else if (modelId == null || modelId.isEmpty || modelId.trim != modelId)
      Left(Vector(s"${MatchPolicy.ModelIdConfigKey} is not configured"))
    else if (a == null || b == null || a.id != o.sourceIdA || b.id != o.sourceIdB)
      Left(Vector("endpoint evidence must describe sourceIdA and sourceIdB, in that order"))
    else {
      val req = AdjudicationProtocol.request(p, o, a, b)
      val key = AdjudicationProtocol.cacheKey(req, modelId)
      def trace(
          status: String,
          v: Option[ValidatedResponse],
          refusal: Option[String],
          cached: Boolean
      ) = AdjudicationTrace(
        key.inputHash,
        key.promptVersion,
        key.modelVersion,
        key.policyVersion,
        status,
        v.map(_.decision.tag),
        v.fold(Vector.empty[String])(_.evidence),
        v.fold(Vector.empty[String])(_.reasonCodes),
        refusal,
        cached
      )
      cache.get(key) match {
        case Some(v) => Right(apply(o, v, trace("valid", Some(v), None, cached = true)))
        case None =>
          val prompt =
            template.render(schemaJson, AdjudicationProtocol.requestJson(req))
          val raw =
            try adjudicator.complete(modelId, prompt)
            catch { case NonFatal(e) => Left(s"adapter threw ${e.getClass.getName}") }
          raw match {
            case Left(why) =>
              Right(refused(o, trace("adapter-failure", None, Some(why), cached = false)))
            case Right(text) =>
              AdjudicationProtocol.validateResponse(
                text,
                req,
                p.adjudication.maxEvidenceItems,
                p.adjudication.maxReasonCodes
              ) match {
                case Left(why) =>
                  Right(refused(o, trace("invalid-response", None, Some(why), cached = false)))
                case Right(v) =>
                  cache.put(key, v)
                  Right(apply(o, v, trace("valid", Some(v), None, cached = false)))
              }
          }
      }
    }

  private def refused(o: ResolutionOutcome, t: AdjudicationTrace): ResolutionOutcome =
    o.copy(
      path = MatchPath.Probabilistic,
      outcome = MatchOutcome.Unresolved,
      confidence = 0.0,
      rulesFired = (o.rulesFired :+ Rules.LlmRefused).distinct,
      reasonCodes = (o.reasonCodes :+ Rules.Reason.LlmRefused).distinct,
      adjudication = Some(t)
    )

  private def apply(
      o: ResolutionOutcome,
      v: ValidatedResponse,
      t: AdjudicationTrace
  ): ResolutionOutcome = {
    val prob = o.score.probability
    val llmCodes = v.reasonCodes.map(c => s"llm.$c")
    val (outcome, confidence, rule, reason) = v.decision match {
      case AdjudicationDecision.Accept =>
        (MatchOutcome.Accepted, prob, Rules.LlmAccept, Rules.Reason.LlmAccept)
      case AdjudicationDecision.Reject =>
        (MatchOutcome.Rejected, 1.0 - prob, Rules.LlmReject, Rules.Reason.LlmReject)
      case AdjudicationDecision.Abstain =>
        (MatchOutcome.Unresolved, 0.0, Rules.LlmAbstain, Rules.Reason.LlmAbstain)
    }
    o.copy(
      path = MatchPath.LlmAdjudicated,
      outcome = outcome,
      confidence = confidence,
      rulesFired = o.rulesFired :+ rule,
      reasonCodes = (o.reasonCodes :+ reason) ++ llmCodes,
      adjudication = Some(t)
    )
  }
}
