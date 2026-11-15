package scholarchain.entityresolution.evaluation

import scala.collection.immutable.SortedMap

import upickle.default.ReadWriter

import scholarchain.entityresolution.policy.{MatchPolicy, ScoreGate}
import scholarchain.entityresolution.review.{DecisionThresholds, GoldMember, ReviewQueueRow}

/**
 * Counts of one evaluation segment. Gold `unsure` labels are excluded from every metric and counted
 * separately. ACCEPTED is a positive prediction and REJECTED a negative one; UNRESOLVED and
 * CONFLICT make no prediction and lower coverage. A labeled member without a queue row (the run did
 * not generate its candidate, or it was refused or untraceable) makes no prediction either; it is
 * counted as not-generated, and a not-generated gold match lowers end-to-end recall.
 *
 * @param reviewed
 *   human-reviewed pairs of the segment: match, non-match, and unsure labels
 * @param unsure
 *   reviewed pairs labeled unsure (excluded from every binary metric)
 * @param definitive
 *   definitive gold labels: match plus non-match (`reviewed - unsure`). Precision, recall, F1,
 *   coverage, and the unresolved rate use only these; the reviewed count is never a definitive
 *   count
 */
final case class Counts(
    reviewed: Int,
    unsure: Int,
    definitive: Int,
    tp: Int,
    fp: Int,
    tn: Int,
    fn: Int,
    unresolvedMatch: Int,
    unresolvedNonMatch: Int,
    conflictMatch: Int,
    conflictNonMatch: Int,
    notGeneratedMatch: Int,
    notGeneratedNonMatch: Int
) derives ReadWriter {
  def decided: Int = tp + fp + tn + fn
  private def ratio(n: Int, d: Int): Option[Double] = if (d == 0) None else Some(n.toDouble / d)

  /** TP / (TP + FP). */
  def precision: Option[Double] = ratio(tp, tp + fp)

  /** TP / (TP + FN): recall among decided pairs. */
  def recall: Option[Double] = ratio(tp, tp + fn)

  /** TP / all gold matches, including those left UNRESOLVED, CONFLICT, or not generated. */
  def endToEndRecall: Option[Double] =
    ratio(tp, tp + fn + unresolvedMatch + conflictMatch + notGeneratedMatch)

  def f1: Option[Double] =
    for { p <- precision; r <- recall if p + r > 0 } yield 2 * p * r / (p + r)

  /** Decided / definitive gold labels (reviewed minus unsure). */
  def coverage: Option[Double] = ratio(decided, definitive)

  def unresolvedRate: Option[Double] = ratio(unresolvedMatch + unresolvedNonMatch, definitive)
}

/** Metrics of one segment, with derived ratios materialized for the report. */
final case class SegmentReport(
    entityKind: String,
    path: String,
    counts: Counts,
    precision: Option[Double],
    recall: Option[Double],
    endToEndRecall: Option[Double],
    f1: Option[Double],
    coverage: Option[Double],
    unresolvedRate: Option[Double],
    confusion: SortedMap[String, Int]
) derives ReadWriter

/**
 * One misclassified pair, traceable to its source records. A gold match that was not generated (no
 * queue row) has no row, candidate, score, or rules: those fields are None or empty, never
 * invented, and its source references come from the manifest member.
 *
 * @param outcome
 *   the system outcome, or [[GoldSetEvaluation.NotGenerated]]
 */
final case class ErrorExample(
    memberId: String,
    rowId: Option[String],
    candidateId: Option[String],
    entityKind: String,
    path: String,
    outcome: String,
    goldLabel: String,
    score: Option[Double],
    rulesFired: Vector[String],
    sourceA: String,
    sourceB: String,
    displayA: Vector[String],
    displayB: Vector[String]
) derives ReadWriter

/**
 * Precision and recall of the score-decided rows at one alternative threshold pair, respecting the
 * policy's acceptance gates (RV-P5-04 m1). A row is in the table when its recorded path is
 * probabilistic or llm-adjudicated. At thresholds `(accept, reject)`:
 *   - `score >= accept`: TP or FP only when the Resolver's
 *     [[scholarchain.entityresolution.policy.ScoreGate]] says score acceptance is reachable;
 *     otherwise the row is counted in `blockedAccept` (the policy would leave it UNRESOLVED:
 *     blocking identifier disagreement or publication stage);
 *   - `score <= reject`: TN or FN only when score rejection is reachable, otherwise
 *     `blockedReject`;
 *   - otherwise `undecided`.
 *
 * A row without a recorded gate (built by hand, or from an older row schema) is never assumed
 * decidable: it is counted in `gateUnknown` and in nothing else. Coverage is decided over the rows
 * with a known gate.
 *
 * @param blockedAcceptByRule
 *   `blockedAccept` by the Resolver rule that caps acceptance (a row capped by two rules counts
 *   under each)
 */
final case class SensitivityPoint(
    entityKind: String,
    accept: Double,
    reject: Double,
    tp: Int,
    fp: Int,
    tn: Int,
    fn: Int,
    undecided: Int,
    precision: Option[Double],
    recall: Option[Double],
    coverage: Option[Double],
    blockedAccept: Int,
    blockedAcceptByRule: SortedMap[String, Int],
    blockedReject: Int,
    gateUnknown: Int
) derives ReadWriter

/**
 * The effective settings an evaluation is reported under (RV-P5-04 m6): the policy after the
 * external configuration was applied
 * ([[scholarchain.entityresolution.policy.MatchPolicy.withConfig]]) and the configured adjudication
 * model ID (`models.llmModelId`). The evaluation refuses queue rows that were decided under other
 * versions or thresholds.
 */
final case class EffectiveSettings(
    policyVersion: String,
    blockingVersion: String,
    featureVersion: String,
    acceptThreshold: Double,
    rejectThreshold: Double,
    llmBandLower: Double,
    llmBandUpper: Double,
    promptVersion: String,
    configuredModelId: String
) derives ReadWriter {
  def thresholds: DecisionThresholds =
    DecisionThresholds(acceptThreshold, rejectThreshold, llmBandLower, llmBandUpper)
}

object EffectiveSettings {
  def of(p: MatchPolicy, modelId: String): EffectiveSettings =
    EffectiveSettings(
      p.policyVersion,
      p.blockingVersion,
      p.featureVersion,
      p.thresholds.accept,
      p.thresholds.reject,
      p.llmBand.lower,
      p.llmBand.upper,
      p.adjudication.promptVersion,
      modelId
    )
}

/** One manifest member that does not enter precision and recall, with every reason. */
final case class Exclusion(memberId: String, entityKind: String, reasons: Vector[String])
    derives ReadWriter

/**
 * Coverage of the frozen membership by human review (human instruction 2026-11-05: report
 * exclusions and coverage; never replace uncertain members).
 *
 * @param split
 *   the split of the membership manifest (`heldout` or `dev`), read from the manifest members
 * @param membersSampled
 *   manifest size (the frozen membership)
 * @param humanReviewed
 *   members with a validated human label line: match, non-match, or unsure. This counts toward
 *   [[GoldSetEvaluation.MinimumReviewedPairs]]; it is NOT the number of definitive gold labels
 * @param definitiveLabels
 *   members labeled match or non-match: the definitive gold labels (unsure excluded)
 * @param definitiveByKind
 *   definitive gold labels by entity kind
 * @param unlabeled
 *   members without a label line
 * @param notGenerated
 *   members without a queue row for this matching version (labeled or not)
 * @param exclusions
 *   every member outside precision and recall, sorted by member ID, with its reasons
 */
final case class Coverage(
    split: String,
    membersSampled: Int,
    populationSize: Int,
    capacity: Option[Int],
    seed: Option[String],
    selectionRule: String,
    membersByKind: SortedMap[String, Int],
    humanReviewed: Int,
    reviewedByKind: SortedMap[String, Int],
    definitiveLabels: Int,
    definitiveByKind: SortedMap[String, Int],
    labeledMatch: Int,
    labeledNonMatch: Int,
    labeledUnsure: Int,
    unlabeled: Int,
    notGenerated: Int,
    notGeneratedReviewed: Int,
    exclusionsByReason: SortedMap[String, Int],
    exclusions: Vector[Exclusion],
    minimumReviewed: Int,
    meetsMinimum: Boolean
) derives ReadWriter

/**
 * @param split
 *   the split of the membership manifest (`heldout` or `dev`); a `dev` report is never a final
 *   held-out gold report ([[GoldSetEvaluation.DevStatus]])
 * @param membershipRunId
 *   the run the membership manifest was generated from
 * @param reviewedPairs
 *   human-reviewed members (unsure included); counts toward the minimum, never a definitive count
 * @param definitiveGoldLabels
 *   members labeled match or non-match (unsure excluded): the labels precision and recall use
 * @param unsurePairs
 *   members labeled unsure (reviewed, excluded from binary precision and recall)
 * @param meetsMinimum
 *   at least [[GoldSetEvaluation.MinimumReviewedPairs]] members carry a human label
 * @param metricsStatus
 *   [[GoldSetEvaluation.FinalStatus]] (held-out), [[GoldSetEvaluation.DevStatus]] (dev split: a
 *   development diagnostic, never final), or [[GoldSetEvaluation.RefusedStatus]] below the minimum:
 *   the metrics are then NOT final gold metrics and the entry point withholds them
 * @param notGeneratedMatches
 *   member IDs of gold matches without a queue row (false negatives before matching)
 * @param falseNegatives
 *   examples of gold matches the system did not ACCEPT: REJECTED, UNRESOLVED, CONFLICT and
 *   not-generated (RV-P5-04 N3), drawn from every category in turn
 * @param settings
 *   the effective settings the rows were checked against; None when no configuration was supplied
 *   (a library call; the entry point always supplies it)
 * @param adjudicationModel
 *   the model ID recorded by the rows' valid adjudications, or [[GoldSetEvaluation.NoAdjudicator]]
 * @param falsePositivesAvailable
 *   every ACCEPTED / non-match pair (the listed examples are at most `maxErrors` of them)
 * @param falseNegativesAvailable
 *   every gold match not ACCEPTED, by outcome (REJECTED, UNRESOLVED, CONFLICT, NOT-GENERATED)
 * @param errorExampleMinimum
 *   examples listed per kind when at least that many exist ([[GoldSetEvaluation.MinErrorExamples]])
 * @param sensitivityMethod
 *   how threshold sensitivity was computed ([[GoldSetEvaluation.SensitivityMethod]])
 */
final case class EvaluationReport(
    split: String,
    policyVersion: String,
    blockingVersion: String,
    featureVersion: String,
    membershipRunId: String,
    membershipBlockingVersion: String,
    reviewedPairs: Int,
    definitiveGoldLabels: Int,
    unsurePairs: Int,
    minimumRequired: Int,
    meetsMinimum: Boolean,
    metricsStatus: String,
    coverage: Coverage,
    segments: Vector[SegmentReport],
    falsePositives: Vector[ErrorExample],
    falseNegatives: Vector[ErrorExample],
    notGeneratedMatches: Vector[String],
    sensitivity: Vector[SensitivityPoint],
    settings: Option[EffectiveSettings],
    adjudicationModel: String,
    falsePositivesAvailable: Int,
    falseNegativesAvailable: SortedMap[String, Int],
    errorExampleMinimum: Int,
    sensitivityMethod: String
) derives ReadWriter

/**
 * Gold-set evaluation: a pure function of the review-queue rows and the validated human labels.
 * Reported separately for researcher and work resolution and, within each, by match path; there is
 * no cross-entity aggregate. Metrics are on the labeled sample: the sample is stratified, so they
 * are not population estimates.
 */
object GoldSetEvaluation {

  /**
   * Final gold metrics require at least this many manifest members with a human label (human
   * instruction 2026-11-05: at least 200 pairs must actually receive human review). `unsure` counts
   * as reviewed but is excluded from precision and recall. Unsure members are never replaced or
   * resampled: the membership is frozen before labeling, and nothing here can change it.
   */
  val MinimumReviewedPairs: Int = 200
  val FinalStatus: String = "final"
  val RefusedStatus: String = "refused-below-minimum"

  /** Metrics of a dev-split manifest: development diagnostics, never final gold metrics. */
  val DevStatus: String = "dev-diagnostic-not-final"

  val HeldoutSplit: String = "heldout"
  val DevSplit: String = "dev"

  /**
   * The single split of a membership manifest (`heldout` or `dev`), or why it has none: an empty
   * manifest, a mix of splits, or another split value is refused.
   */
  def splitOf(manifest: Vector[GoldMember]): Either[String, String] =
    manifest.map(_.split).distinct match {
      case Vector(s) if s == HeldoutSplit || s == DevSplit => Right(s)
      case Vector() => Left("the membership manifest is empty")
      case Vector(s) => Left(s"manifest split '$s' is neither $HeldoutSplit nor $DevSplit")
      case ss => Left(s"the manifest mixes splits: ${ss.sorted.mkString(", ")}")
    }

  val UnlabeledReason: String = "unlabeled: no human label line for this member"
  val UnsureReason: String = "unsure: human-reviewed; excluded from precision and recall"
  val NotGeneratedReason: String =
    "not-generated: no queue row for this matching version; a gold match counts only in " +
      "end-to-end recall"

  /**
   * Problems of labels built in code (RV-P5-05 #4): a member labeled twice, or a label that is not
   * exactly canonical. [[GoldLabels.ingest]] refuses both for label files; this check keeps a
   * programmatic caller from bypassing it.
   */
  def labelProblems(labels: Vector[GoldLabel]): Vector[String] = {
    val dup = labels.groupBy(_.memberId).collect {
      case (id, ls) if ls.size > 1 => s"member $id has ${ls.size} labels"
    }
    val nonCanonical = labels
      .filterNot(g => GoldLabels.canonical(g.label))
      .map(g =>
        s"label for ${g.memberId} is not exactly one of ${GoldLabels.Labels.toVector.sorted.mkString(", ")}"
      )
    dup.toVector.sorted ++ nonCanonical
  }

  /**
   * Coverage of a manifest by validated labels and by the queue rows of one matching version.
   * Refused (never mapped to an empty label) when a label is not exactly canonical or a member is
   * labeled twice ([[labelProblems]]).
   */
  def coverage(
      manifest: Vector[GoldMember],
      queue: Vector[ReviewQueueRow],
      labels: Vector[GoldLabel]
  ): Either[Vector[String], Coverage] = {
    val problems = labelProblems(labels)
    if (problems.nonEmpty) Left(problems) else Right(coverageOf(manifest, queue, labels))
  }

  private def coverageOf(
      manifest: Vector[GoldMember],
      queue: Vector[ReviewQueueRow],
      labels: Vector[GoldLabel]
  ): Coverage = {
    // Every label here is canonical (checked by `coverage` and `evaluate`), so `collect` drops none.
    val labelOf = labels.collect {
      case g if GoldLabels.canonical(g.label) => g.memberId -> g.label.get
    }.toMap
    val rowed = queue.map(_.memberId).toSet
    val kinds = Vector("researcher", "work")
    def byKind(ms: Vector[GoldMember]) =
      SortedMap.from(kinds.map(k => k -> ms.count(_.entityKind == k)))
    val exclusions = manifest.sortBy(_.memberId).flatMap { m =>
      val reasons = Vector(
        Option.when(!labelOf.contains(m.memberId))(UnlabeledReason),
        Option.when(labelOf.get(m.memberId).contains("unsure"))(UnsureReason),
        Option.when(!rowed.contains(m.memberId))(NotGeneratedReason)
      ).flatten
      Option.when(reasons.nonEmpty)(Exclusion(m.memberId, m.entityKind, reasons))
    }
    val reviewed = manifest.filter(m => labelOf.contains(m.memberId))
    val definitive =
      reviewed.filter(m => labelOf.get(m.memberId).exists(l => l == "match" || l == "non-match"))
    val head = manifest.headOption
    Coverage(
      splitOf(manifest).getOrElse(manifest.map(_.split).distinct.sorted.mkString("+")),
      manifest.size,
      head.fold(0)(_.populationSize),
      head.flatMap(_.capacity),
      head.flatMap(_.seed),
      head.fold("")(_.selectionRule),
      byKind(manifest),
      reviewed.size,
      byKind(reviewed),
      definitive.size,
      byKind(definitive),
      labelOf.values.count(_ == "match"),
      labelOf.values.count(_ == "non-match"),
      labelOf.values.count(_ == "unsure"),
      manifest.count(m => !labelOf.contains(m.memberId)),
      manifest.count(m => !rowed.contains(m.memberId)),
      reviewed.count(m => !rowed.contains(m.memberId)),
      SortedMap.from(
        Vector(UnlabeledReason, UnsureReason, NotGeneratedReason).map(r =>
          r -> exclusions.count(_.reasons.contains(r))
        )
      ),
      exclusions,
      MinimumReviewedPairs,
      reviewed.size >= MinimumReviewedPairs
    )
  }
  val Paths: Vector[String] =
    Vector("exact-id", "deterministic-bibliographic", "probabilistic", "llm-adjudicated")
  val Outcomes: Vector[String] = Vector("ACCEPTED", "REJECTED", "UNRESOLVED", "CONFLICT")

  /** Default sensitivity grid: accept 0.60..0.95 and reject 0.05..0.40 in steps of 0.05. */
  val DefaultGrid: Vector[(Double, Double)] =
    for {
      a <- (12 to 19).toVector.map(_ * 0.05)
      r <- (1 to 8).toVector.map(_ * 0.05)
      if r < a
    } yield (
      BigDecimal(a).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble,
      BigDecimal(r).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble
    )

  /** Pseudo-outcome of a labeled member that has no queue row. */
  val NotGenerated: String = "NOT-GENERATED"

  /** Pseudo-path of the segment of labeled members without a queue row. */
  val NotGeneratedPath: String = "not-generated"

  /**
   * At least this many false-positive and false-negative examples are listed when that many exist
   * (the entry point and `evaluate` refuse a smaller `maxErrors`).
   */
  val MinErrorExamples: Int = 10

  /** The non-accept outcomes of a gold match, in the order their examples are drawn. */
  val MissedMatchOutcomes: Vector[String] =
    Vector("REJECTED", "UNRESOLVED", "CONFLICT", NotGenerated)

  /** The adjudication model reported when no row carries a valid adjudication. */
  val NoAdjudicator: String = "none (no adjudicator ran)"

  /** How [[sensitivity]] decides which rows a threshold change could accept or reject. */
  val SensitivityMethod: String =
    "resolver-score-gate.v1: each generated row records the Resolver's ScoreGate, computed by " +
      "resolving the same candidate again with every score in the accept region and in the " +
      "reject region; a row counts as accepted (TP/FP) at an alternative accept threshold only " +
      "when that gate shows the score path is reached and nothing caps acceptance (no exact-id " +
      "or deterministic rule, no blocking identifier disagreement, established publication " +
      "stage); capped rows are reported as blockedAccept; rows without a gate as gateUnknown; " +
      "LLM band eligibility and LLM adjudication are not modelled at alternative thresholds: an " +
      "llm-adjudicated row enters only through its score, as if no adjudicator ran"

  /**
   * Counts over generated pairs (row, gold label) and the gold labels of members without a row.
   */
  def counts(
      pairs: Vector[(ReviewQueueRow, String)],
      notGenerated: Vector[String] = Vector.empty
  ): Counts = {
    def n(f: (String, String) => Boolean) = pairs.count { case (r, l) => f(r.system.outcome, l) }
    val reviewed = pairs.size + notGenerated.size
    val unsure = n((_, l) => l == "unsure") + notGenerated.count(_ == "unsure")
    Counts(
      reviewed = reviewed,
      unsure = unsure,
      definitive = reviewed - unsure,
      tp = n((o, l) => o == "ACCEPTED" && l == "match"),
      fp = n((o, l) => o == "ACCEPTED" && l == "non-match"),
      tn = n((o, l) => o == "REJECTED" && l == "non-match"),
      fn = n((o, l) => o == "REJECTED" && l == "match"),
      unresolvedMatch = n((o, l) => o == "UNRESOLVED" && l == "match"),
      unresolvedNonMatch = n((o, l) => o == "UNRESOLVED" && l == "non-match"),
      conflictMatch = n((o, l) => o == "CONFLICT" && l == "match"),
      conflictNonMatch = n((o, l) => o == "CONFLICT" && l == "non-match"),
      notGeneratedMatch = notGenerated.count(_ == "match"),
      notGeneratedNonMatch = notGenerated.count(_ == "non-match")
    )
  }

  /**
   * Confusion matrix: `<system outcome>|<gold label>` to count, every cell present, including the
   * [[NotGenerated]] row.
   */
  def confusion(
      pairs: Vector[(ReviewQueueRow, String)],
      notGenerated: Vector[String] = Vector.empty
  ): SortedMap[String, Int] =
    SortedMap.from(
      for {
        o <- Outcomes :+ NotGenerated
        l <- Vector("match", "non-match", "unsure")
      } yield s"$o|$l" -> (if (o == NotGenerated) notGenerated.count(_ == l)
                           else pairs.count { case (r, g) => r.system.outcome == o && g == l })
    )

  def segment(
      kind: String,
      path: String,
      pairs: Vector[(ReviewQueueRow, String)],
      notGenerated: Vector[String] = Vector.empty
  ): SegmentReport = {
    val c = counts(pairs, notGenerated)
    SegmentReport(
      kind,
      path,
      c,
      c.precision,
      c.recall,
      c.endToEndRecall,
      c.f1,
      c.coverage,
      c.unresolvedRate,
      confusion(pairs, notGenerated)
    )
  }

  private def example(r: ReviewQueueRow, label: String): ErrorExample =
    ErrorExample(
      r.memberId,
      Some(r.rowId),
      Some(r.candidateId),
      r.entityKind,
      r.system.path,
      r.system.outcome,
      label,
      Some(r.system.score),
      r.system.rulesFired,
      s"${r.endpointA.sourceDataset}:${r.endpointA.sourceRecordId}@${r.endpointA.snapshot}",
      s"${r.endpointB.sourceDataset}:${r.endpointB.sourceRecordId}@${r.endpointB.snapshot}",
      r.endpointA.displayText,
      r.endpointB.displayText
    )

  /** The example of a labeled member without a queue row (references from the manifest only). */
  private def notGeneratedExample(m: GoldMember, label: String): ErrorExample =
    ErrorExample(
      m.memberId,
      None,
      None,
      m.entityKind,
      NotGeneratedPath,
      NotGenerated,
      label,
      None,
      Vector.empty,
      s"${m.endpointA.sourceDataset}:${m.endpointA.sourceRecordId}@${m.endpointA.snapshot}",
      s"${m.endpointB.sourceDataset}:${m.endpointB.sourceRecordId}@${m.endpointB.snapshot}",
      Vector.empty,
      Vector.empty
    )

  /**
   * Up to `limit` examples drawn from the categories in turn (round robin, each category sorted by
   * entity kind and member ID), so every non-empty category is shown when `limit` allows.
   */
  private def drawn(categories: Vector[Vector[ErrorExample]], limit: Int): Vector[ErrorExample] = {
    val sorted = categories.map(_.sortBy(e => (e.entityKind, e.memberId)))
    val depth = if (sorted.isEmpty) 0 else sorted.map(_.size).max
    (0 until depth).toVector.flatMap(i => sorted.flatMap(_.lift(i))).take(limit)
  }

  /**
   * Threshold sensitivity over the rows decided by score (probabilistic and LLM paths), respecting
   * the policy's acceptance gates as recorded by the Resolver ([[SensitivityPoint]],
   * [[SensitivityMethod]]; RV-P5-04 m1).
   */
  def sensitivity(
      pairs: Vector[(ReviewQueueRow, String)],
      grid: Vector[(Double, Double)]
  ): Vector[SensitivityPoint] = {
    val scored = pairs.filter { case (r, l) =>
      l != "unsure" && (r.system.path == "probabilistic" || r.system.path == "llm-adjudicated")
    }
    for {
      kind <- Vector("researcher", "work")
      (a, rj) <- grid
    } yield {
      val ofKind = scored.filter(_._1.entityKind == kind)
      val (known, unknown) = ofKind.partition(_._1.system.scoreGate.isDefined)
      val ps = known.map { case (r, l) => (r.system.score, l, r.system.scoreGate.get) }
      def n(f: (Double, String, ScoreGate) => Boolean) = ps.count { case (s, l, g) => f(s, l, g) }
      val tp = n((s, l, g) => s >= a && g.acceptReachable && l == "match")
      val fp = n((s, l, g) => s >= a && g.acceptReachable && l == "non-match")
      val tn = n((s, l, g) => s <= rj && g.rejectReachable && l == "non-match")
      val fn = n((s, l, g) => s <= rj && g.rejectReachable && l == "match")
      val blockedA = ps.filter { case (s, _, g) => s >= a && !g.acceptReachable }
      val blockedR = n((s, _, g) => s < a && s <= rj && !g.rejectReachable)
      val decided = tp + fp + tn + fn
      val undecided = ps.size - decided - blockedA.size - blockedR
      SensitivityPoint(
        kind,
        a,
        rj,
        tp,
        fp,
        tn,
        fn,
        undecided,
        if (tp + fp == 0) None else Some(tp.toDouble / (tp + fp)),
        if (tp + fn == 0) None else Some(tp.toDouble / (tp + fn)),
        if (ps.isEmpty) None else Some(decided.toDouble / ps.size),
        blockedA.size,
        SortedMap.from(
          blockedA.flatMap(_._3.acceptBlockedBy).groupBy(identity).map { case (k, v) =>
            k -> v.size
          }
        ),
        blockedR,
        unknown.size
      )
    }
  }

  /**
   * Evaluates validated labels (keyed by member ID) against the membership manifest and the queue
   * rows of one matching version. Labels join rows on member ID, so the same labels evaluate the
   * rows of any matching version built from the same manifest. A labeled member without a row is
   * counted as not-generated. Refused when a row is not a manifest member or differs from it, a
   * member has two rows, a label is not a manifest member, a member is labeled twice or a label is
   * not canonical ([[labelProblems]]), the rows mix policy, blocking, or feature versions,
   * thresholds, or adjudication models, or `maxErrors` is below [[MinErrorExamples]].
   *
   * With `settings` (the entry point always passes them, RV-P5-04 m6) the rows must also have been
   * decided under exactly those settings: the same policy, blocking and feature versions, the same
   * recorded thresholds and band, and any valid adjudication under the configured model and the
   * policy's prompt version. A mismatch is refused, never reported under the wrong settings.
   */
  def evaluate(
      manifest: Vector[GoldMember],
      queue: Vector[ReviewQueueRow],
      labels: Vector[GoldLabel],
      maxErrors: Int = MinErrorExamples,
      grid: Vector[(Double, Double)] = DefaultGrid,
      settings: Option[EffectiveSettings] = None
  ): Either[Vector[String], EvaluationReport] = {
    val models = queue.flatMap(_.system.modelVersion).distinct.sorted
    val settingsProblems: Vector[String] = settings.toVector.flatMap { s =>
      queue.flatMap { r =>
        val v = Vector(
          Option.when(r.policyVersion != s.policyVersion)(
            s"policyVersion ${r.policyVersion} differs from the configured ${s.policyVersion}"
          ),
          Option.when(r.blockingVersion != s.blockingVersion)(
            s"blockingVersion ${r.blockingVersion} differs from the policy's ${s.blockingVersion}"
          ),
          Option.when(r.featureVersion != s.featureVersion)(
            s"featureVersion ${r.featureVersion} differs from the policy's ${s.featureVersion}"
          ),
          r.system.thresholds match {
            case None => Some("records no thresholds; the configured thresholds cannot be verified")
            case Some(t) if t != s.thresholds =>
              Some(s"was decided under thresholds $t, not the configured ${s.thresholds}")
            case _ => None
          },
          r.system.modelVersion
            .filter(_ != s.configuredModelId)
            .map(m => s"was adjudicated by model $m, not the configured models.llmModelId"),
          r.system.promptVersion
            .filter(_ != s.promptVersion)
            .map(pv => s"was adjudicated with prompt $pv, not the policy's ${s.promptVersion}")
        ).flatten
        v.map(why => s"queue row ${r.rowId}: $why")
      }
    }
    val members = manifest.map(m => m.memberId -> m).toMap
    val rowProblems = queue.flatMap { r =>
      members.get(r.memberId) match {
        case None => Some(s"queue row ${r.rowId}: member ${r.memberId} is not in the manifest")
        case Some(m)
            if m.entityKind != r.entityKind ||
              Set(m.endpointA.endpointId, m.endpointB.endpointId) != Set(
                r.sourceIdA,
                r.sourceIdB
              ) =>
          Some(s"queue row ${r.rowId}: kind or endpoints differ from member ${r.memberId}")
        case _ => None
      }
    } ++ queue.groupBy(_.memberId).collect {
      case (id, rs) if rs.size > 1 => s"member $id has ${rs.size} queue rows"
    }
    val versions = queue.map(r => (r.policyVersion, r.blockingVersion, r.featureVersion)).distinct
    val thresholdSets = queue.map(_.system.thresholds).distinct
    val unknown = labels.filterNot(g => members.contains(g.memberId)).map(_.memberId)
    // RV-P5-04 M1 and RV-P5-05 #4: only exact canonical label strings, one per member, are counted
    // (GoldLabels.ingest refuses the rest); labels built in code are checked here as well, never
    // silently dropped or double counted.
    val badLabels = labelProblems(labels)
    val split = splitOf(manifest)
    if (maxErrors < MinErrorExamples)
      Left(Vector(s"maxErrors $maxErrors is below the minimum of $MinErrorExamples examples"))
    else if (rowProblems.nonEmpty) Left(rowProblems.sorted)
    else if (split.isLeft) Left(split.swap.toOption.toVector)
    else if (unknown.nonEmpty)
      Left(unknown.map(id => s"label for $id does not resolve to a manifest member"))
    else if (badLabels.nonEmpty) Left(badLabels)
    else if (versions.size > 1)
      Left(Vector(s"queue rows mix versions: ${versions.mkString(", ")}"))
    else if (thresholdSets.size > 1)
      Left(Vector(s"queue rows mix thresholds: ${thresholdSets.mkString(", ")}"))
    else if (models.size > 1)
      Left(Vector(s"queue rows mix adjudication models: ${models.mkString(", ")}"))
    else if (settingsProblems.nonEmpty) Left(settingsProblems.distinct.sorted)
    else {
      val byMember = queue.map(r => r.memberId -> r).toMap
      val withLabel = labels.map(g => (g, g.label.getOrElse("")))
      val pairs = withLabel.flatMap { case (g, l) => byMember.get(g.memberId).map(_ -> l) }
      val missing = withLabel.filterNot { case (g, _) => byMember.contains(g.memberId) }
      val (pv, bv, fv) = versions.headOption.getOrElse(
        settings.fold(("", "", ""))(s => (s.policyVersion, s.blockingVersion, s.featureVersion))
      )
      val segments = for {
        kind <- Vector("researcher", "work")
        path <- ("all" +: Paths) :+ NotGeneratedPath
      } yield {
        val ng = missing.collect {
          case (g, l) if g.entityKind == kind && (path == "all" || path == NotGeneratedPath) => l
        }
        segment(
          kind,
          path,
          pairs.filter { case (r, _) =>
            r.entityKind == kind && (path == "all" || r.system.path == path)
          },
          ng
        )
      }
      def errors(outcome: String, label: String): Vector[ErrorExample] =
        pairs.collect {
          case (r, l) if r.system.outcome == outcome && l == label => example(r, l)
        }
      val falsePositives = errors("ACCEPTED", "non-match")
      // RV-P5-04 N3: every gold match the system did not ACCEPT is a missed match, whatever the
      // outcome, including members the run never generated.
      val missedByOutcome: Vector[(String, Vector[ErrorExample])] = MissedMatchOutcomes.map {
        case NotGenerated =>
          NotGenerated -> missing.collect {
            case (g, "match") if members.contains(g.memberId) =>
              notGeneratedExample(members(g.memberId), "match")
          }
        case o => o -> errors(o, "match")
      }
      val cov = coverageOf(manifest, queue, labels)
      val sp = split.getOrElse("")
      Right(
        EvaluationReport(
          sp,
          pv,
          bv,
          fv,
          manifest.headOption.fold("")(_.generatingRunId),
          manifest.headOption.fold("")(_.blockingVersion),
          labels.size,
          labels.count(g => g.label.contains("match") || g.label.contains("non-match")),
          labels.count(_.label.contains("unsure")),
          MinimumReviewedPairs,
          cov.meetsMinimum,
          if (!cov.meetsMinimum) RefusedStatus
          else if (sp == HeldoutSplit) FinalStatus
          else DevStatus,
          cov,
          segments,
          drawn(Vector(falsePositives), maxErrors),
          drawn(missedByOutcome.map(_._2), maxErrors),
          missing.collect { case (g, "match") => g.memberId }.sorted,
          sensitivity(pairs, grid),
          settings,
          models.headOption.getOrElse(NoAdjudicator),
          falsePositives.size,
          SortedMap.from(missedByOutcome.map { case (o, es) => o -> es.size }),
          MinErrorExamples,
          SensitivityMethod
        )
      )
    }
  }
}
