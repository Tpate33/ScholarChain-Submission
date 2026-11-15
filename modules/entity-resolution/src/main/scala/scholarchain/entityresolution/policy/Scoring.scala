package scholarchain.entityresolution.policy

import scala.collection.immutable.SortedMap

import scholarchain.core.{MatchCandidate, SourceType}

/** One feature's contribution to a score: capped value times weight. */
final case class Contribution(feature: String, value: Double, weight: Double) {
  def amount: Double = value * weight
}

/**
 * A logistic match score with its explanation.
 *
 * @param probability
 *   `1 / (1 + exp(-logit))` in [0, 1]. Uncalibrated until the policy is fitted to gold labels.
 * @param contributions
 *   non-zero contributions, ordered by feature name
 */
final case class Score(logit: Double, probability: Double, contributions: Vector[Contribution])

/**
 * The probabilistic scorer of a [[MatchPolicy]]: `logit = bias + sum(weight * min(value, cap))`
 * over the policy's weighted features. Missing features count as 0 (the feature convention: 0 never
 * means "disagree" on its own). Pure and deterministic.
 */
object Scoring {

  /**
   * Policy-level feature: the minimum source reliability of the pair minus the policy center. It is
   * computed by the policy from `sourceDatasetA` and `sourceDatasetB` and is not part of the
   * candidate's `featureVersion`.
   */
  val SourceReliabilityFeature: String = "policy.source_reliability"

  def sourceReliability(p: MatchPolicy, a: SourceType, b: SourceType): Double = {
    def r(s: SourceType): Double =
      Option(s).flatMap(x => p.sourceReliability.bySource.get(x.tag)).getOrElse(0.0)
    math.min(r(a), r(b)) - p.sourceReliability.center
  }

  /** The candidate's features plus the policy features, as scored. */
  def scoredFeatures(p: MatchPolicy, c: MatchCandidate): SortedMap[String, Double] =
    c.features + (SourceReliabilityFeature -> sourceReliability(
      p,
      c.sourceDatasetA,
      c.sourceDatasetB
    ))

  private def sigmoid(x: Double): Double =
    if (x >= 0) 1.0 / (1.0 + math.exp(-x))
    else {
      val e = math.exp(x)
      e / (1.0 + e)
    }

  def score(kp: KindPolicy, features: SortedMap[String, Double]): Score = {
    val contributions = kp.weights.toVector
      .sortBy(_._1)
      .map { case (f, w) =>
        val raw = features.getOrElse(f, 0.0)
        val v = kp.caps.get(f).fold(raw)(cap => math.min(raw, cap))
        Contribution(f, v, w)
      }
      .filter(_.amount != 0.0)
    val logit = kp.bias + contributions.map(_.amount).sum
    Score(logit, sigmoid(logit), contributions)
  }
}
