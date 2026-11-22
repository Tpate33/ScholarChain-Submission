package scholarchain.rag.agents

import scholarchain.core.{
  BridgeMeasure,
  CollaborationMetric,
  CommunityMembership,
  ComponentMembership,
  IdKind,
  SchemaVersions,
  TimeWindow
}
import scholarchain.storage.model.GraphQuery
import scholarchain.storage.port.{GraphReader, KeyValueReader}

/**
 * Direct collaboration breadth: the researcher's distinct identified coauthors (C_r) in the window,
 * from the stored researcher-metrics row. NOT a bridge measure.
 */
final case class DirectDegree(reading: MetricReading)

/**
 * Structural bridge importance: the stored bridge-measure record (configured measure and
 * parameters, score, cross-community degree, communities reached, participation coefficient). Kept
 * apart from [[DirectDegree]]: a high direct degree is not a high bridge importance.
 */
final case class StructuralBridge(record: BridgeMeasure, source: StoredRecordRef)

/** One coauthor edge of the researcher whose other end is in another community (stored IDs). */
final case class CrossCommunityEdge(
    collaboratorId: String,
    collaboratorCommunityId: String,
    coauthorEdgeId: String,
    jointPublicationCount: Long,
    communitySource: StoredRecordRef
)

/** A neighbouring community reached through the listed collaborators (listing, not a metric). */
final case class NeighbouringCommunity(communityId: String, viaCollaborators: Vector[String])

/**
 * The community view of one researcher in one window. `members` is the stored membership list of
 * the researcher's community (MC-3), bounded; the stored size is `community.communitySize`. The
 * cross-community edges and neighbouring communities are LISTED from the researcher's direct
 * coauthors (bounded by `maxResults`; `collaboratorsTruncated` when cut); their number is never a
 * metric: the stored cross-community degree is `bridge.record.crossCommunityDegree`.
 */
final case class CommunityView(
    researcherId: String,
    window: TimeWindow,
    community: Option[(CommunityMembership, StoredRecordRef)],
    component: Option[(ComponentMembership, StoredRecordRef)],
    members: Vector[String],
    membersTruncated: Boolean,
    directDegree: Option[DirectDegree],
    structuralBridge: Option[StructuralBridge],
    crossCommunityEdges: Vector[CrossCommunityEdge],
    neighbouringCommunities: Vector[NeighbouringCommunity],
    collaboratorsWithoutCommunity: Vector[String],
    collaboratorsTruncated: Boolean,
    gaps: Vector[String]
)

/** Two researchers' communities and components compared by stored ID equality. */
final case class CommunityComparison(
    a: CommunityView,
    b: CommunityView,
    sameCommunity: Option[Boolean],
    sameComponent: Option[Boolean]
)

/** One entry of the stored bridge ranking with its structural and direct values side by side. */
final case class BridgeEntry(
    rank: Int,
    researcherId: String,
    structuralBridge: StructuralBridge,
    directDegree: Option[DirectDegree]
)

final case class BridgeRanking(
    window: TimeWindow,
    orderingRule: String,
    entries: Vector[BridgeEntry],
    truncated: Boolean
)

/**
 * RE-P9-01 stage 5, CommunityAnalystAgent: community membership, community size and members,
 * cross-community edges, bridge measures and neighbouring communities, from stored Spark rows and
 * the bounded `DirectCollaborators` template only. It keeps direct degree ([[DirectDegree]], C_r)
 * and structural bridge importance ([[StructuralBridge]]) as distinct fields, and computes no
 * metric.
 */
final class CommunityAnalystAgent(
    graph: GraphReader,
    kv: KeyValueReader,
    serving: ServingContext,
    limits: AgentLimits,
    clock: AgentClock
) {
  private val metricsAgent = new CollaborationMetricsAgent(kv, serving, limits, clock)

  def view(researcherId: String, window: TimeWindow): Either[AgentError, CommunityView] = {
    val budget = new CallBudget(limits, clock)
    val rows = new AnalysisRows(kv, serving, budget)
    for {
      _ <- requireId(IdKind.Researcher, researcherId, "researcherId")
      _ <- windowKey(window)
      comm <- StoredReads.community(rows, researcherId, window)
      comp <- StoredReads.component(rows, researcherId, window)
      br <- StoredReads.bridge(rows, researcherId, window)
      rm <- StoredReads.researcherMetrics(rows, researcherId, window)
      members <- comm match {
        case None => Right((Vector.empty[String], false))
        case Some((c, _)) => membersOf(rows, c.communityId, window)
      }
      _ <- comm match {
        case Some((c, _)) if !members._2 && !members._1.contains(researcherId) =>
          Left(AgentError.Corrupt(s"${c.communityId}: the stored member list omits $researcherId"))
        case _ => Right(())
      }
      edges <- crossEdges(budget, rows, researcherId, window, comm.map(_._1.communityId))
    } yield {
      val degree = CollaborationMetricsAgent
        .readingOf(CollaborationMetric.DistinctCoauthorCount, StoredRecords(rm = rm))
        .toOption
        .map(DirectDegree(_))
      val (cross, without, cut) = edges
      val neighbours = cross
        .groupBy(_.collaboratorCommunityId)
        .toVector
        .map { case (id, es) => NeighbouringCommunity(id, es.map(_.collaboratorId).sorted) }
        .sortBy(_.communityId)
      val gaps =
        Vector(
          Option.when(comm.isEmpty)("no community-memberships row in the window"),
          Option.when(comp.isEmpty)("no component-memberships row in the window"),
          Option.when(br.isEmpty)("no bridge-measures row in the window"),
          Option.when(rm.isEmpty)("no researcher-metrics row in the window")
        ).flatten
      CommunityView(
        researcherId,
        window,
        comm,
        comp,
        members._1,
        members._2,
        degree,
        br.map((r, ref) => StructuralBridge(r, ref)),
        cross,
        neighbours,
        without,
        cut,
        gaps
      )
    }
  }

  private def membersOf(
      rows: AnalysisRows,
      communityId: String,
      window: TimeWindow
  ): Either[AgentError, (Vector[String], Boolean)] =
    for {
      q <- rows.groupQuery(communityId, window, limits.maxResults)
      got <- rows.query(q, "community members")
      ms <- got._1.foldLeft[Either[AgentError, Vector[String]]](Right(Vector.empty)) { (acc, row) =>
        acc.flatMap { xs =>
          rows
            .checked[CommunityMembership](
              row,
              AnalysisRoles.CommunityMemberships,
              SchemaVersions.CommunityMembership,
              _.researcherId,
              _.window
            )
            .flatMap { case (m, _) =>
              if (m.communityId != communityId)
                Left(AgentError.Corrupt(s"$communityId: member row of ${m.communityId}"))
              else Right(xs :+ m.researcherId)
            }
        }
      }
    } yield (ms, got._2)

  private def crossEdges(
      budget: CallBudget,
      rows: AnalysisRows,
      researcherId: String,
      window: TimeWindow,
      own: Option[String]
  ): Either[AgentError, (Vector[CrossCommunityEdge], Vector[String], Boolean)] =
    own match {
      case None => Right((Vector.empty, Vector.empty, false))
      case Some(community) =>
        val Q = GraphQuery.DirectCollaborators
        for {
          q <- Bind(
            Q,
            limits,
            "researcherId" -> Bind.id(researcherId),
            "windowStartYear" -> Bind.int(window.startYear),
            "windowEndYear" -> Bind.int(window.endYear),
            "limit" -> Bind.int(limits.maxResults)
          )
          got <- budget.graph(graph, q)
          out <- got.foldLeft[Either[AgentError, (Vector[CrossCommunityEdge], Vector[String])]](
            Right((Vector.empty, Vector.empty))
          ) { (acc, r) =>
            acc.flatMap { case (es, without) =>
              for {
                c <- Cols.str(r, "collaboratorId", Q)
                n <- Cols.long(r, "jointPublicationCount", Q)
                e <- Cols.str(r, "sourceEvidence", Q)
                m <- StoredReads.community(rows, c, window)
              } yield m match {
                case None => (es, without :+ c)
                case Some((cm, _)) if cm.communityId == community => (es, without)
                case Some((cm, ref)) =>
                  (es :+ CrossCommunityEdge(c, cm.communityId, e, n, ref), without)
              }
            }
          }
        } yield (out._1, out._2, got.size >= limits.maxResults)
    }

  /** Two researchers compared by their stored community and component IDs. */
  def compare(a: String, b: String, window: TimeWindow): Either[AgentError, CommunityComparison] =
    for {
      va <- view(a, window)
      vb <- view(b, window)
    } yield CommunityComparison(
      va,
      vb,
      for { x <- va.community; y <- vb.community } yield x._1.communityId == y._1.communityId,
      for { x <- va.component; y <- vb.component } yield x._1.componentId == y._1.componentId
    )

  /**
   * The stored bridge ranking (selected bridge metric: cross-community degree, see
   * [[CollaborationMetricsAgent.RankedRoles]]), each entry with its structural bridge record and,
   * separately, its direct degree C_r.
   */
  def bridgeRanking(window: TimeWindow, k: Int): Either[AgentError, Option[BridgeRanking]] =
    metricsAgent.rank(CollaborationMetric.CrossCommunityDegree, window, k).flatMap {
      case RankingOutcome.NoStoredRanking(_, _) => Right(None)
      case RankingOutcome.Ranked(r) =>
        val budget = new CallBudget(limits, clock)
        val rows = new AnalysisRows(kv, serving, budget)
        r.entries
          .foldLeft[Either[AgentError, Vector[BridgeEntry]]](Right(Vector.empty)) { (acc, e) =>
            acc.flatMap { xs =>
              for {
                br <- StoredReads.bridge(rows, e.subjectId, window)
                b <- br.toRight(AgentError.Corrupt(s"ranked ${e.subjectId}: no bridge row"))
                rm <- StoredReads.researcherMetrics(rows, e.subjectId, window)
              } yield xs :+ BridgeEntry(
                e.rank,
                e.subjectId,
                StructuralBridge(b._1, b._2),
                CollaborationMetricsAgent
                  .readingOf(CollaborationMetric.DistinctCoauthorCount, StoredRecords(rm = rm))
                  .toOption
                  .map(DirectDegree(_))
              )
            }
          }
          .map(es => Some(BridgeRanking(window, r.orderingRule, es, r.truncated)))
    }
}
