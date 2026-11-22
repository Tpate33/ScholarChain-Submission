package scholarchain.rag.agents

import scholarchain.core.{
  BridgeMeasure,
  CollaborationMetric,
  CommunityMembership,
  ComponentMembership,
  Coreness as CorenessRecord,
  IdKind,
  PathLengthEstimate,
  ResearcherMetricsV2,
  SchemaVersions,
  TimeWindow
}
import scholarchain.storage.model.AnalysisResultRecord
import scholarchain.storage.port.KeyValueReader

/** A stored metric value, exactly as Spark wrote it (no rounding, no conversion). */
enum MetricValue {
  case Count(value: Long)
  case Real(value: Double)
  case Id(value: String)
}

/**
 * One metric reading: the stored value, the stored row it came from (record schema = metric
 * version, graph policy, run, load, part, content hash) and, for bridge and community values, the
 * configured method (`measure` or `algorithm`) recorded on the record.
 */
final case class MetricReading(
    metric: CollaborationMetric,
    value: MetricValue,
    method: Option[String],
    source: StoredRecordRef
)

/** A requested metric with no stored value, and why (never filled with an estimate). */
final case class MetricGap(metric: CollaborationMetric, reason: String)

/**
 * The metrics of one researcher in one window. The stored researcher-metrics record (with
 * `maximumTeamWorkIds`, the works attaining M_r, and the unresolved-coauthor fields) is returned
 * verbatim when it was read.
 */
final case class ResearcherMetricsResult(
    researcherId: String,
    window: TimeWindow,
    readings: Vector[MetricReading],
    gaps: Vector[MetricGap],
    researcherMetrics: Option[(ResearcherMetricsV2, StoredRecordRef)]
)

/** One entry of a stored ranking (MC-5): the stored rank and the ranked row's readings. */
final case class RankedEntry(rank: Int, subjectId: String, readings: Vector[MetricReading])

/**
 * A stored top-k ranking. `orderingRule` states the loader's order (storage SE-P7-07); `truncated`
 * is true when `k` rows were returned (more may exist).
 */
final case class Ranking(
    metric: CollaborationMetric,
    role: String,
    window: TimeWindow,
    orderingRule: String,
    entries: Vector[RankedEntry],
    truncated: Boolean
)

enum RankingOutcome {
  case Ranked(ranking: Ranking)

  /** No stored ranking orders by this metric: the caller abstains (never a computed ranking). */
  case NoStoredRanking(metric: CollaborationMetric, reason: String)
}

/**
 * The stored records a reading may come from (all optional), each with its stored-row pointer.
 */
private[agents] final case class StoredRecords(
    rm: Option[(ResearcherMetricsV2, StoredRecordRef)] = None,
    comp: Option[(ComponentMembership, StoredRecordRef)] = None,
    comm: Option[(CommunityMembership, StoredRecordRef)] = None,
    br: Option[(BridgeMeasure, StoredRecordRef)] = None,
    co: Option[(CorenessRecord, StoredRecordRef)] = None,
    ple: Option[(PathLengthEstimate, StoredRecordRef)] = None
)

/** Typed reads of the analysis-result roles (one function per role, no casts). */
private[agents] object StoredReads {
  private val S = SchemaVersions
  private val R = AnalysisRoles

  def researcherMetrics(rows: AnalysisRows, id: String, w: TimeWindow) =
    rows.subject[ResearcherMetricsV2](
      id,
      R.ResearcherMetrics,
      w,
      S.ResearcherMetricsV2,
      _.researcherId,
      _.window
    )
  def component(rows: AnalysisRows, id: String, w: TimeWindow) =
    rows.subject[ComponentMembership](
      id,
      R.ComponentMemberships,
      w,
      S.ComponentMembership,
      _.researcherId,
      _.window
    )
  def community(rows: AnalysisRows, id: String, w: TimeWindow) =
    rows.subject[CommunityMembership](
      id,
      R.CommunityMemberships,
      w,
      S.CommunityMembership,
      _.researcherId,
      _.window
    )
  def bridge(rows: AnalysisRows, id: String, w: TimeWindow) =
    rows.subject[BridgeMeasure](id, R.BridgeMeasures, w, S.BridgeMeasure, _.researcherId, _.window)
  def coreness(rows: AnalysisRows, id: String, w: TimeWindow) =
    rows.subject[CorenessRecord](id, R.Coreness, w, S.Coreness, _.researcherId, _.window)
}

/**
 * RE-P9-01 stage 3, CollaborationMetricsAgent. Returns EXACT stored Spark values with their metric
 * version (record schema and graph policy) and run ID; it never computes, rounds or estimates a
 * metric. Every row is read through the key-value read port (MC-2 subject rows, MC-5 rank GSI),
 * decoded with storage's item codec (hash-checked) and the core record contract, and must belong to
 * the served analysis run, the requested subject and the requested window, else `Corrupt`.
 *
 * Metric sources: P_r, C_r, T_r, A_r, M_r, W_r from `researcher-metrics`; component ID and size
 * from `component-memberships`; community ID from `community-memberships`; bridge score,
 * cross-community degree and participation coefficient from `bridge-measures`; coreness from
 * `coreness`. Pair metrics (joint publication count, shortest path length) are gaps here: they come
 * from [[PathFinderAgent]].
 *
 * Stored rankings ([[CollaborationMetricsAgent.RankedRoles]]): C_r, the selected bridge metric
 * (cross-community degree, the order of the stored bridge ranking), coreness and component size.
 * Every other metric has no stored ranking and yields `NoStoredRanking`.
 */
final class CollaborationMetricsAgent(
    kv: KeyValueReader,
    serving: ServingContext,
    limits: AgentLimits,
    clock: AgentClock
) {
  import CollaborationMetricsAgent.*

  def metrics(
      researcherId: String,
      window: TimeWindow,
      requested: Vector[CollaborationMetric]
  ): Either[AgentError, ResearcherMetricsResult] =
    if (requested == null || requested.isEmpty || requested.contains(null))
      Left(AgentError.Refused("requested metrics: missing"))
    else {
      val rows = new AnalysisRows(kv, serving, new CallBudget(limits, clock))
      val wanted = requested.distinct
      val roles = wanted.flatMap(RoleOf.get).toSet
      def when[A](role: String)(
          read: => Either[AgentError, Option[A]]
      ): Either[AgentError, Option[A]] = if (roles.contains(role)) read else Right(None)
      val R = AnalysisRoles
      for {
        _ <- requireId(IdKind.Researcher, researcherId, "researcherId")
        _ <- windowKey(window)
        rm <- when(R.ResearcherMetrics)(StoredReads.researcherMetrics(rows, researcherId, window))
        comp <- when(R.ComponentMemberships)(StoredReads.component(rows, researcherId, window))
        comm <- when(R.CommunityMemberships)(StoredReads.community(rows, researcherId, window))
        br <- when(R.BridgeMeasures)(StoredReads.bridge(rows, researcherId, window))
        co <- when(R.Coreness)(StoredReads.coreness(rows, researcherId, window))
      } yield {
        val stored = StoredRecords(rm, comp, comm, br, co)
        val (readings, gaps) =
          wanted.foldLeft((Vector.empty[MetricReading], Vector.empty[MetricGap])) {
            case ((rs, gs), m) =>
              readingOf(m, stored) match {
                case Right(r) => (rs :+ r, gs)
                case Left(why) => (rs, gs :+ MetricGap(m, why))
              }
          }
        ResearcherMetricsResult(researcherId, window, readings, gaps, rm)
      }
    }

  /** The top `k` (1..maxResults) entries of the stored ranking of `metric`. */
  def rank(
      metric: CollaborationMetric,
      window: TimeWindow,
      k: Int
  ): Either[AgentError, RankingOutcome] =
    Option(metric).flatMap(RankedRoles.get) match {
      case None =>
        Right(
          RankingOutcome.NoStoredRanking(
            metric,
            s"no stored ranking orders researchers by ${Option(metric).map(_.tag).orNull}"
          )
        )
      case Some((role, rule)) =>
        if (k < 1 || k > limits.maxResults)
          Left(AgentError.Refused(s"k: $k is outside 1..${limits.maxResults}"))
        else {
          val rows = new AnalysisRows(kv, serving, new CallBudget(limits, clock))
          for {
            q <- rows.rankQuery(role, window, k)
            got <- rows.query(q, s"rank $role")
            entries <- got._1.zipWithIndex.foldLeft[Either[AgentError, Vector[RankedEntry]]](
              Right(Vector.empty)
            ) { case (acc, (row, i)) =>
              acc.flatMap(es => entry(rows, row, role, metric, i + 1).map(es :+ _))
            }
          } yield RankingOutcome.Ranked(Ranking(metric, role, window, rule, entries, got._2))
        }
    }

  private def entry(
      rows: AnalysisRows,
      row: AnalysisResultRecord,
      role: String,
      metric: CollaborationMetric,
      expectedRank: Int
  ): Either[AgentError, RankedEntry] = {
    val S = SchemaVersions
    val stored: Either[AgentError, StoredRecords] = role match {
      case AnalysisRoles.ResearcherMetrics =>
        rows
          .checked[ResearcherMetricsV2](row, role, S.ResearcherMetricsV2, _.researcherId, _.window)
          .map(x => StoredRecords(rm = Some(x)))
      case AnalysisRoles.BridgeMeasures =>
        rows
          .checked[BridgeMeasure](row, role, S.BridgeMeasure, _.researcherId, _.window)
          .map(x => StoredRecords(br = Some(x)))
      case AnalysisRoles.Coreness =>
        rows
          .checked[CorenessRecord](row, role, S.Coreness, _.researcherId, _.window)
          .map(x => StoredRecords(co = Some(x)))
      case AnalysisRoles.PathLengthEstimates =>
        rows
          .checked[PathLengthEstimate](row, role, S.PathLengthEstimate, _.componentId, _.window)
          .map(x => StoredRecords(ple = Some(x)))
      case other => Left(AgentError.Refused(s"$other is not a ranked role"))
    }
    if (row.rank != Some(expectedRank))
      Left(
        AgentError.Corrupt(
          s"rank $role: row ${row.scopeId} has rank ${row.rank}, expected $expectedRank"
        )
      )
    else
      stored.flatMap { s =>
        val rs = RankedReadings(role).flatMap(m => readingOf(m, s).toOption)
        if (!rs.exists(_.metric == metric))
          Left(AgentError.Corrupt(s"rank $role: row ${row.scopeId} carries no ${metric.tag} value"))
        else Right(RankedEntry(expectedRank, row.scopeId, rs))
      }
  }
}

object CollaborationMetricsAgent {
  import scholarchain.core.{CollaborationMetric as M}

  /** Record schema (metric version) each role is read with (any other schema is Corrupt). */
  val SchemaOf: Map[String, String] = Map(
    AnalysisRoles.ResearcherMetrics -> SchemaVersions.ResearcherMetricsV2,
    AnalysisRoles.CoauthorEdges -> SchemaVersions.CoauthorEdgeV2,
    AnalysisRoles.ComponentMemberships -> SchemaVersions.ComponentMembership,
    AnalysisRoles.CommunityMemberships -> SchemaVersions.CommunityMembership,
    AnalysisRoles.BridgeMeasures -> SchemaVersions.BridgeMeasure,
    AnalysisRoles.Coreness -> SchemaVersions.Coreness,
    AnalysisRoles.PathLengthEstimates -> SchemaVersions.PathLengthEstimate
  )

  /** The analysis role that stores each per-researcher metric. */
  val RoleOf: Map[CollaborationMetric, String] = Map(
    M.PublicationCount -> AnalysisRoles.ResearcherMetrics,
    M.DistinctCoauthorCount -> AnalysisRoles.ResearcherMetrics,
    M.TotalCoauthorOccurrences -> AnalysisRoles.ResearcherMetrics,
    M.AverageCoauthorsPerPaper -> AnalysisRoles.ResearcherMetrics,
    M.MaximumCoauthorsOnOnePaper -> AnalysisRoles.ResearcherMetrics,
    M.WeightedDegree -> AnalysisRoles.ResearcherMetrics,
    M.ComponentId -> AnalysisRoles.ComponentMemberships,
    M.ComponentSize -> AnalysisRoles.ComponentMemberships,
    M.CommunityId -> AnalysisRoles.CommunityMemberships,
    M.BridgeScore -> AnalysisRoles.BridgeMeasures,
    M.CrossCommunityDegree -> AnalysisRoles.BridgeMeasures,
    M.ParticipationCoefficient -> AnalysisRoles.BridgeMeasures,
    M.Coreness -> AnalysisRoles.Coreness
  )

  /**
   * The stored rankings (MC-5) and the loader's ordering rule (storage `KeyValueLoad` ranks, as
   * documented by SE-P7-07). The selected bridge metric for "bridge researchers" is the stored
   * bridge ranking's order, cross-community degree; the algorithm score and the participation
   * coefficient are returned alongside, never re-ranked.
   */
  val RankedRoles: Map[CollaborationMetric, (String, String)] = Map(
    M.DistinctCoauthorCount -> (AnalysisRoles.ResearcherMetrics ->
      "distinctCoauthorCount desc, then publicationCount desc, then researcherId (UTF-8) asc"),
    M.CrossCommunityDegree -> (AnalysisRoles.BridgeMeasures ->
      "crossCommunityDegree desc, then communitiesReached desc, then researcherId (UTF-8) asc"),
    M.Coreness -> (AnalysisRoles.Coreness -> "coreness desc, then researcherId (UTF-8) asc"),
    M.ComponentSize -> (AnalysisRoles.PathLengthEstimates ->
      "componentSize desc, then componentId (UTF-8) asc")
  )

  private val RankedReadings: Map[String, Vector[CollaborationMetric]] = Map(
    AnalysisRoles.ResearcherMetrics -> Vector(
      M.DistinctCoauthorCount,
      M.PublicationCount,
      M.TotalCoauthorOccurrences,
      M.AverageCoauthorsPerPaper,
      M.MaximumCoauthorsOnOnePaper,
      M.WeightedDegree
    ),
    AnalysisRoles.BridgeMeasures -> Vector(
      M.CrossCommunityDegree,
      M.BridgeScore,
      M.ParticipationCoefficient
    ),
    AnalysisRoles.Coreness -> Vector(M.Coreness),
    AnalysisRoles.PathLengthEstimates -> Vector(M.ComponentSize)
  )

  /** The verbatim reading of `m` from the stored records, or why there is none. */
  private[agents] def readingOf(
      m: CollaborationMetric,
      s: StoredRecords
  ): Either[String, MetricReading] = {
    def none(role: String) = Left(s"no $role row for the subject in the window")
    def r(v: MetricValue, ref: StoredRecordRef, method: Option[String] = None) =
      Right(MetricReading(m, v, method, ref))
    import MetricValue.{Count, Id, Real}
    m match {
      case M.PublicationCount | M.DistinctCoauthorCount | M.TotalCoauthorOccurrences |
          M.AverageCoauthorsPerPaper | M.MaximumCoauthorsOnOnePaper | M.WeightedDegree =>
        s.rm match {
          case None => none(AnalysisRoles.ResearcherMetrics)
          case Some((x, ref)) =>
            m match {
              case M.PublicationCount => r(Count(x.publicationCount), ref)
              case M.DistinctCoauthorCount => r(Count(x.distinctCoauthorCount), ref)
              case M.TotalCoauthorOccurrences => r(Count(x.totalCoauthorOccurrences), ref)
              case M.AverageCoauthorsPerPaper => r(Real(x.averageCoauthorsPerPaper), ref)
              case M.MaximumCoauthorsOnOnePaper => r(Count(x.maximumCoauthorsOnOnePaper), ref)
              case _ => r(Count(x.weightedDegree), ref)
            }
        }
      case M.ComponentId =>
        s.comp match {
          case Some((x, ref)) => r(Id(x.componentId), ref)
          case None => none(AnalysisRoles.ComponentMemberships)
        }
      case M.ComponentSize =>
        (s.comp, s.ple) match {
          case (Some((x, ref)), _) => r(Count(x.componentSize), ref)
          case (None, Some((x, ref))) => r(Count(x.componentSize), ref)
          case _ => none(AnalysisRoles.ComponentMemberships)
        }
      case M.CommunityId =>
        s.comm match {
          case Some((x, ref)) => r(Id(x.communityId), ref, Some(x.algorithm))
          case None => none(AnalysisRoles.CommunityMemberships)
        }
      case M.BridgeScore | M.CrossCommunityDegree | M.ParticipationCoefficient =>
        s.br match {
          case None => none(AnalysisRoles.BridgeMeasures)
          case Some((x, ref)) =>
            m match {
              case M.BridgeScore => r(Real(x.score), ref, Some(x.measure))
              case M.CrossCommunityDegree => r(Count(x.crossCommunityDegree), ref, Some(x.measure))
              case _ => r(Real(x.participationCoefficient), ref, Some(x.measure))
            }
        }
      case M.Coreness =>
        s.co match {
          case Some((x, ref)) => r(Count(x.coreness), ref)
          case None => none(AnalysisRoles.Coreness)
        }
      case M.JointPublicationCount | M.ShortestPathLength =>
        Left(s"${m.tag} is a pair metric: it comes from the path finder, not a researcher row")
    }
  }
}
