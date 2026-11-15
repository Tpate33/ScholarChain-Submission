package scholarchain.entityresolution.review

import scala.collection.immutable.SortedMap

import upickle.default.{write, ReadWriter}
import upickle.implicits.allowUnknownKeys

import scholarchain.core.{Canonical, IdentifierConflict}
import scholarchain.entityresolution.policy.{MatchPolicy, ResolutionOutcome, Resolver, ScoreGate}

/**
 * The effective thresholds and LLM band a decision was made under (the policy after
 * `MatchPolicy.withConfig`), recorded on every generated row so that a run is never reported under
 * thresholds it did not use.
 */
@allowUnknownKeys(false)
final case class DecisionThresholds(
    accept: Double,
    reject: Double,
    llmBandLower: Double,
    llmBandUpper: Double
) derives ReadWriter

object DecisionThresholds {
  def of(p: MatchPolicy): DecisionThresholds =
    DecisionThresholds(p.thresholds.accept, p.thresholds.reject, p.llmBand.lower, p.llmBand.upper)
}

/**
 * What the system decided for a queued pair. Internal only: it is never written to the blind
 * labeling view ([[BlindView]]), and labels are joined to it by member ID only at evaluation time.
 *
 * @param conflicts
 *   the decision's identifier conflicts (core [[IdentifierConflict]])
 * @param plausible
 *   the plausibility test result, when an identifier disagreed
 * @param thresholds
 *   the effective thresholds and band of the decision (always set on a generated row; None only on
 *   a row built by hand)
 * @param scoreGate
 *   whether the probabilistic score could decide the pair at other thresholds, as computed by the
 *   Resolver ([[ScoreGate]]; always set on a generated row; None only on a row built by hand)
 */
@allowUnknownKeys(false)
final case class SystemDecision(
    outcome: String,
    path: String,
    score: Double,
    confidence: Double,
    rulesFired: Vector[String],
    reasonCodes: Vector[String],
    conflicts: Vector[IdentifierConflict],
    plausible: Option[Boolean],
    llmBandEligible: Boolean,
    modelVersion: Option[String],
    promptVersion: Option[String],
    thresholds: Option[DecisionThresholds] = None,
    scoreGate: Option[ScoreGate] = None
) derives ReadWriter

/**
 * One unlabeled internal review-queue row (schema [[ReviewQueue.RowSchemaVersion]]). Every row
 * traces to its candidate (ID, run, snapshot, versions), both source records (dataset, record ID,
 * snapshot, schema, record run), and the system decision. The four label fields are always null in
 * a generated row: labels are written only by a human, in a separate label file. This file is the
 * evaluation-side record; the human labels from the blind view ([[BlindView]]).
 *
 * @param rowId
 *   [[ReviewQueue.rowId]](memberId, policyVersion): one row per member and matching version
 * @param memberId
 *   [[GoldMembership.memberId]] of the pair: the key that gold labels use
 * @param stratum
 *   `<kind>/<path>/<outcome>/<score band>`; internal, for post-hoc reporting and dev diagnostics
 */
@allowUnknownKeys(false)
final case class ReviewQueueRow(
    schemaVersion: String,
    rowId: String,
    memberId: String,
    stratum: String,
    entityKind: String,
    candidateId: String,
    candidateRunId: String,
    candidateSnapshot: String,
    blockingVersion: String,
    featureVersion: String,
    policyVersion: String,
    sourceIdA: String,
    sourceIdB: String,
    endpointA: EndpointRef,
    endpointB: EndpointRef,
    exactIdAgreements: Vector[String],
    features: SortedMap[String, Double],
    system: SystemDecision,
    label: Option[String],
    labeler: Option[String],
    labeledAt: Option[String],
    labelNotes: Option[String]
) derives ReadWriter

/** Score band of a probability under a policy's thresholds and LLM band. */
enum ScoreBand(val tag: String) {
  case AtOrBelowReject extends ScoreBand("le-reject")
  case BelowLlmBand extends ScoreBand("reject-to-band")
  case InLlmBand extends ScoreBand("llm-band")
  case AboveLlmBand extends ScoreBand("band-to-accept")
  case AtOrAboveAccept extends ScoreBand("ge-accept")
}

object ScoreBand {
  def of(p: MatchPolicy, score: Double): ScoreBand =
    if (score <= p.thresholds.reject) AtOrBelowReject
    else if (score >= p.thresholds.accept) AtOrAboveAccept
    else if (p.llmBand.contains(score)) InLlmBand
    else if (score < p.llmBand.lower) BelowLlmBand
    else AboveLlmBand
}

/**
 * Stratified sampling design. Strata are `<entity kind>/<path>/<outcome>/<score band>`. Each
 * stratum first receives `min(minPerStratum, available)` rows; the remaining budget up to
 * `targetTotal` is shared in proportion to the rows still available (largest remainder, ties by
 * stratum name). Within a stratum, rows are ordered by SHA-256 of (`seed`, candidate ID): a
 * deterministic, reproducible pseudo-random order with no clock or random generator.
 */
final case class SamplingDesign(targetTotal: Int, minPerStratum: Int, seed: String)

object SamplingDesign {

  /** At least the README minimum of 200 reviewed pairs, plus a margin for "unsure" labels. */
  val Default: SamplingDesign =
    SamplingDesign(targetTotal = 240, minPerStratum = 10, seed = "er-p5")
}

/** Availability and selection of one stratum. */
final case class StratumCount(stratum: String, available: Int, selected: Int, minimum: Int) {
  def shortfall: Int = math.max(0, minimum - available)
}

/** Availability and selection of one required coverage cell (entity kind, outcome or band). */
final case class CellCount(entityKind: String, cell: String, available: Int, selected: Int)

/** The queue, the strata, and every candidate that could not be traced to its source records. */
final case class ReviewQueueBuild(
    rows: Vector[ReviewQueueRow],
    strata: Vector[StratumCount],
    cells: Vector[CellCount],
    untraceable: Vector[(String, String)],
    design: SamplingDesign
) {
  def selectedTotal: Int = rows.size
  def availableTotal: Int = strata.map(_.available).sum
  def totalShortfall: Int = math.max(0, design.targetTotal - availableTotal)
}

/** Deterministic, provenance-carrying review-queue generator. */
object ReviewQueue {

  /**
   * v4 (ER-P5-11): the system decision records its effective thresholds and its score gate. Rows of
   * v3 are refused by the queue parser.
   */
  val RowSchemaVersion: String = "er-review-queue-row.v4"

  /** Required coverage cells: every outcome for both entity kinds, plus the LLM band. */
  val RequiredCells: Vector[(String, String)] =
    for {
      kind <- Vector("researcher", "work")
      cell <- Vector("ACCEPTED", "REJECTED", "UNRESOLVED", "CONFLICT", "llm-band")
    } yield (kind, cell)

  def stratumOf(p: MatchPolicy, o: ResolutionOutcome): String =
    s"${o.entityKind.tag}/${o.path.tag}/${o.outcome.tag}/${ScoreBand.of(p, o.score.probability).tag}"

  /**
   * `rowId = "rq:" + SHA-256(netstrings(RowSchemaVersion, memberId, policyVersion))`: the same
   * member gets a new row under each matching version, while its member ID (and its label) stays
   * the same. The decision is not an input.
   */
  def rowId(memberId: String, policyVersion: String): String =
    "rq:" + Canonical.hashFields(RowSchemaVersion, memberId, policyVersion)

  /** The unlabeled row of one outcome, or why it cannot be traced. */
  def row(
      p: MatchPolicy,
      o: ResolutionOutcome,
      refs: Map[String, EndpointRef]
  ): Either[String, ReviewQueueRow] =
    (refs.get(o.sourceIdA), refs.get(o.sourceIdB)) match {
      case (Some(a), Some(b)) =>
        val member = GoldMembership.memberId(o.entityKind.tag, o.sourceIdA, o.sourceIdB)
        if (a.sourceDataset != o.sourceDatasetA.tag || b.sourceDataset != o.sourceDatasetB.tag)
          Left("endpoint dataset differs from the candidate")
        else if (Vector(a, b).exists(r => r.sourceRecordId.isEmpty || r.snapshot.isEmpty))
          Left("endpoint has no source record ID or snapshot")
        else if (member.isLeft) Left(member.left.getOrElse(""))
        else {
          val (model, prompt) = Resolver.llmVersions(o)
          val memberId = member.getOrElse("")
          Right(
            ReviewQueueRow(
              RowSchemaVersion,
              rowId(memberId, o.policyVersion),
              memberId,
              stratumOf(p, o),
              o.entityKind.tag,
              o.candidateId,
              o.candidateRunId,
              o.candidateSnapshot,
              o.blockingVersion,
              o.featureVersion,
              o.policyVersion,
              o.sourceIdA,
              o.sourceIdB,
              a,
              b,
              o.exactIdAgreements.map(e => s"${e.scheme.tag}:${e.value}"),
              o.features,
              SystemDecision(
                o.outcome.tag,
                o.path.tag,
                o.score.probability,
                o.confidence,
                o.rulesFired,
                o.reasonCodes,
                o.conflicts,
                o.plausibility.map(_.plausible),
                o.llmBandEligible,
                model,
                prompt,
                Some(DecisionThresholds.of(p)),
                o.scoreGate
              ),
              None,
              None,
              None,
              None
            )
          )
        }
      case (a, b) =>
        Left(
          s"no source record for ${Vector(a.fold(Some(o.sourceIdA))(_ => None), b.fold(Some(o.sourceIdB))(_ => None)).flatten.mkString(", ")}"
        )
    }

  private def order(seed: String, candidateId: String): String =
    Canonical.hashFields("er-review-sample.v1", seed, candidateId)

  /**
   * Outcome-stratified queue for DEV DIAGNOSTICS ONLY. It selects pairs by system outcome, path and
   * score band, so a different matching version would select different pairs; held-out gold
   * membership is therefore never built here but by [[GoldMembership]]. Refused when any endpoint's
   * record belongs to a held-out selection unit of `units` (records outside the DS-P5-01 selection,
   * such as the Phase 4 samples, are not held out). `units` is mandatory, so a caller cannot skip
   * the check by omission.
   */
  def buildDevDiagnostics(
      p: MatchPolicy,
      outcomes: Vector[ResolutionOutcome],
      refs: Map[String, EndpointRef],
      design: SamplingDesign,
      units: SelectionUnits
  ): Either[Vector[String], ReviewQueueBuild] = {
    val heldout = outcomes
      .flatMap(o => Vector(o.sourceIdA, o.sourceIdB))
      .distinct
      .flatMap(refs.get)
      .flatMap(r =>
        units
          .unitsOf(r)
          .filter(_.split != SelectionUnits.Dev)
          .map(u => s"endpoint ${r.endpointId} is in ${u.split} unit ${u.unitId}")
      )
      .distinct
      .sorted
    if (heldout.nonEmpty)
      Left(("the outcome-stratified queue is for dev diagnostics only; refused" +: heldout))
    else Right(build(p, outcomes, refs, design))
  }

  /**
   * Builds the stratified queue. Deterministic; the order of distinct candidates in the input does
   * not matter (a repeated candidate ID keeps its first outcome).
   */
  private def build(
      p: MatchPolicy,
      outcomes: Vector[ResolutionOutcome],
      refs: Map[String, EndpointRef],
      design: SamplingDesign
  ): ReviewQueueBuild = {
    val unique = outcomes.groupBy(_.candidateId).values.map(_.head).toVector
    val traced = unique.map(o => o -> row(p, o, refs))
    val untraceable =
      traced.collect { case (o, Left(why)) => o.candidateId -> why }.sortBy(_._1)
    val rows = traced.collect { case (_, Right(r)) => r }
    val byStratum: Vector[(String, Vector[ReviewQueueRow])] =
      rows
        .groupBy(_.stratum)
        .toVector
        .sortBy(_._1)
        .map { case (s, rs) => s -> rs.sortBy(r => order(design.seed, r.candidateId)) }
    val base = byStratum.map { case (s, rs) => s -> math.min(design.minPerStratum, rs.size) }.toMap
    val remainingBudget = math.max(0, design.targetTotal - base.values.sum)
    val spare = byStratum.map { case (s, rs) => s -> (rs.size - base(s)) }
    val spareTotal = spare.map(_._2).sum
    val extra: Map[String, Int] =
      if (spareTotal == 0 || remainingBudget == 0) Map.empty
      else {
        val budget = math.min(remainingBudget, spareTotal)
        val exact = spare.map { case (s, n) => (s, n, n.toDouble * budget / spareTotal) }
        val floors = exact.map { case (s, n, x) => s -> math.min(n, math.floor(x).toInt) }.toMap
        val left = budget - floors.values.sum
        val bonus = exact
          .filter { case (s, n, _) => floors(s) < n }
          .sortBy { case (s, _, x) => (-(x - math.floor(x)), s) }
          .take(left)
          .map(_._1)
          .toSet
        floors.map { case (s, f) => s -> (f + (if (bonus(s)) 1 else 0)) }
      }
    val selected = byStratum.flatMap { case (s, rs) => rs.take(base(s) + extra.getOrElse(s, 0)) }
    val counts = byStratum.map { case (s, rs) =>
      StratumCount(s, rs.size, base(s) + extra.getOrElse(s, 0), design.minPerStratum)
    }
    val availableCells = cellCounts(rows)
    val selectedCells = cellCounts(selected).toMap
    val cells = availableCells.map { case ((k, c), n) =>
      CellCount(k, c, n, selectedCells((k, c)))
    }
    ReviewQueueBuild(selected, counts, cells, untraceable, design)
  }

  /**
   * Count of each required coverage cell (entity kind x outcome, plus the LLM band), so that a
   * missing cell is reported, never silently skipped.
   */
  def cellCounts(rows: Vector[ReviewQueueRow]): Vector[((String, String), Int)] =
    RequiredCells.map { case (kind, cell) =>
      val n =
        if (cell == "llm-band") rows.count(r => r.entityKind == kind && r.system.llmBandEligible)
        else rows.count(r => r.entityKind == kind && r.system.outcome == cell)
      (kind, cell) -> n
    }

  /** JSON Lines, one row per line, in queue order. */
  def toJsonl(rows: Vector[ReviewQueueRow]): String =
    rows.map(r => write(r)).mkString("", "\n", if (rows.isEmpty) "" else "\n")
}
