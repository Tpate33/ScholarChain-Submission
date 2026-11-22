package scholarchain.rag.agents

import scala.collection.mutable

import scholarchain.core.{Canonical, CoauthorEdgeV2, IdKind, SchemaVersions, TimeWindow, YearCount}
import scholarchain.storage.model.{GraphQuery, GraphRow}
import scholarchain.storage.port.{GraphReader, KeyValueReader}

/**
 * The closed set of path-finder templates (RE-P9-01 stage 4). Each is a fixed composition of
 * storage's allowlisted, parameterized openCypher templates ([[GraphQuery]]); parameters are typed
 * IDs, years and bounds, never text.
 *
 *   - `direct-collaborators.v1`: `GraphQuery.DirectCollaborators` (one researcher, one window,
 *     LIMIT), or `GraphQuery.CoauthorEdgeBetween` for one named pair.
 *   - `shortest-coauthorship-path.v1`: breadth-first search over `GraphQuery.DirectCollaborators`,
 *     at most `maxPathDepth` levels, `maxVisited` researchers, `maxGraphCalls` calls and
 *     `timeoutMillis`.
 *
 * Every hop's evidence (weight, supporting publications, years, provenance) is the stored
 * `coauthor-edges` record of the hop's edge in the window, read from the analysis-results table
 * (MC-2) and checked against the graph row.
 */
enum PathTemplate(val tag: String, val queries: Vector[GraphQuery]) {
  case DirectCollaborators
      extends PathTemplate(
        "direct-collaborators.v1",
        Vector(GraphQuery.DirectCollaborators, GraphQuery.CoauthorEdgeBetween)
      )
  case ShortestPath
      extends PathTemplate("shortest-coauthorship-path.v1", Vector(GraphQuery.DirectCollaborators))
}

/**
 * The stored evidence of one coauthor edge in one window: its weight (joint publication count),
 * collaboration years, per-year counts and every supporting publication, copied from the
 * `coauthor-edge.v2` record, with that record's stored-row pointer.
 */
final case class HopEvidence(
    coauthorEdgeId: String,
    jointPublicationCount: Long,
    firstCollaborationYear: Int,
    lastCollaborationYear: Int,
    publicationsByYear: Vector[YearCount],
    supportingWorkIds: Vector[String],
    source: StoredRecordRef
)

/** One hop: from, to (researcher IDs, path order) and the edge's stored evidence. */
final case class Hop(fromId: String, toId: String, evidence: HopEvidence)

final case class DirectCollaboratorsResult(
    researcherId: String,
    window: TimeWindow,
    template: PathTemplate,
    hops: Vector[Hop],
    truncated: Boolean,
    graphCalls: Int,
    executedQueries: Vector[String]
)

enum PathOutcome {

  /**
   * A PROVEN shortest path within the bound; `hopCount` = hops.size, from the traversal. Only
   * returned when no neighbour list up to the hit level was cut by the result limit.
   */
  case Found(nodeIds: Vector[String], hops: Vector[Hop])

  /**
   * RE-P9-04 (CR-P9-01 M1): a connecting path was found, but at least one neighbour list read at or
   * before the hit level was cut by the result limit, so a shorter path may exist. The path proves
   * connectivity within `hops.size` hops (an upper bound); it is NEVER a shortest path and its
   * evidence carries `searchTruncated = true`.
   */
  case FoundNotProvenShortest(nodeIds: Vector[String], hops: Vector[Hop], reason: String)

  /** Every neighbour list was complete and no path of at most `maxDepth` hops exists. */
  case NotConnectedWithin(maxDepth: Int)

  /** The search was cut (result limit, visit bound): neither a path nor its absence is known. */
  case Inconclusive(reason: String)
}

final case class PathResult(
    sourceId: String,
    targetId: String,
    window: TimeWindow,
    maxDepth: Int,
    template: PathTemplate,
    outcome: PathOutcome,
    graphCalls: Int,
    executedQueries: Vector[String]
)

/** A direct edge check between two named researchers. */
final case class DirectEdgeResult(
    researcherA: String,
    researcherB: String,
    window: TimeWindow,
    hop: Option[Hop],
    executedQueries: Vector[String]
)

/**
 * RE-P9-01 stage 4, PathFinderAgent. Bounded, parameterized graph retrieval through the
 * [[GraphReader]] port only, with hop evidence from the [[KeyValueReader]] port. Limits
 * ([[AgentLimits]]): `maxResults` per template call, `maxPathDepth` hops, `maxVisited` researchers,
 * `maxGraphCalls` and `maxKeyValueCalls` per invocation, `timeoutMillis` per invocation. A limit
 * that cuts the search makes the outcome `Inconclusive`, never a guess.
 */
final class PathFinderAgent(
    graph: GraphReader,
    kv: KeyValueReader,
    serving: ServingContext,
    limits: AgentLimits,
    clock: AgentClock
) {

  private final case class EdgeRow(collaboratorId: String, joint: Long, edgeId: String)

  private def neighbours(
      budget: CallBudget,
      id: String,
      w: TimeWindow,
      limit: Int
  ): Either[AgentError, Vector[EdgeRow]] = {
    val Q = GraphQuery.DirectCollaborators
    for {
      q <- Bind(
        Q,
        limits,
        "researcherId" -> Bind.id(id),
        "windowStartYear" -> Bind.int(w.startYear),
        "windowEndYear" -> Bind.int(w.endYear),
        "limit" -> Bind.int(limit)
      )
      rows <- budget.graph(graph, q)
      out <- Cols.all(rows)(edgeRow(_, Q, "collaboratorId"))
    } yield out
  }

  private def edgeRow(r: GraphRow, q: GraphQuery, idCol: String): Either[AgentError, EdgeRow] =
    for {
      c <- Cols.str(r, idCol, q)
      n <- Cols.long(r, "jointPublicationCount", q)
      e <- Cols.str(r, "sourceEvidence", q)
      _ <- requireId(IdKind.CoauthorEdge, e, "sourceEvidence").left.map(x =>
        AgentError.Corrupt(s"$q: ${x.message}")
      )
    } yield EdgeRow(c, n, e)

  /** The stored coauthor-edge record of a hop, checked against the graph row. */
  private def evidence(
      rows: AnalysisRows,
      from: String,
      to: String,
      e: EdgeRow,
      w: TimeWindow
  ): Either[AgentError, Hop] =
    rows
      .subject[CoauthorEdgeV2](
        e.edgeId,
        AnalysisRoles.CoauthorEdges,
        w,
        SchemaVersions.CoauthorEdgeV2,
        _.edgeId,
        _.window
      )
      .flatMap {
        case None =>
          Left(
            AgentError.Corrupt(s"edge ${e.edgeId}: no stored coauthor-edges record in the window")
          )
        case Some((x, ref)) =>
          val pair = Set(x.researcherA, x.researcherB)
          if (pair != Set(from, to))
            Left(AgentError.Corrupt(s"edge ${e.edgeId}: the stored record joins other researchers"))
          else if (x.jointPublicationCount != e.joint)
            Left(
              AgentError.Corrupt(
                s"edge ${e.edgeId}: graph weight ${e.joint} differs from stored ${x.jointPublicationCount}"
              )
            )
          else
            Right(
              Hop(
                from,
                to,
                HopEvidence(
                  x.edgeId,
                  x.jointPublicationCount,
                  x.firstCollaborationYear,
                  x.lastCollaborationYear,
                  x.publicationsByYear,
                  x.supportingWorkIds,
                  ref
                )
              )
            )
      }

  private def exists(budget: CallBudget, id: String): Either[AgentError, Boolean] =
    for {
      q <- Bind(GraphQuery.ResearcherById, limits, "researcherId" -> Bind.id(id))
      rows <- budget.graph(graph, q)
    } yield rows.nonEmpty

  /** Direct collaborators of one researcher in a window, strongest edges first (graph order). */
  def directCollaborators(
      researcherId: String,
      window: TimeWindow,
      limit: Int
  ): Either[AgentError, DirectCollaboratorsResult] =
    if (limit < 1 || limit > limits.maxResults)
      Left(AgentError.Refused(s"limit: $limit is outside 1..${limits.maxResults}"))
    else {
      val budget = new CallBudget(limits, clock)
      val rows = new AnalysisRows(kv, serving, budget)
      for {
        _ <- requireId(IdKind.Researcher, researcherId, "researcherId")
        _ <- windowKey(window)
        ns <- neighbours(budget, researcherId, window, limit)
        hops <- ns.foldLeft[Either[AgentError, Vector[Hop]]](Right(Vector.empty)) { (acc, e) =>
          acc.flatMap(hs => evidence(rows, researcherId, e.collaboratorId, e, window).map(hs :+ _))
        }
      } yield DirectCollaboratorsResult(
        researcherId,
        window,
        PathTemplate.DirectCollaborators,
        hops,
        ns.size >= limit,
        budget.graphCallCount,
        budget.executedTemplates
      )
    }

  /** The direct edge between two named researchers in a window, if any. */
  def directEdge(a: String, b: String, window: TimeWindow): Either[AgentError, DirectEdgeResult] = {
    val budget = new CallBudget(limits, clock)
    val rows = new AnalysisRows(kv, serving, budget)
    val Q = GraphQuery.CoauthorEdgeBetween
    for {
      _ <- requireId(IdKind.Researcher, a, "researcherA")
      _ <- requireId(IdKind.Researcher, b, "researcherB")
      _ <- if (a != b) Right(()) else Left(AgentError.Refused("the two researchers are the same"))
      _ <- windowKey(window)
      q <- Bind(
        Q,
        limits,
        "researcherA" -> Bind.id(a),
        "researcherB" -> Bind.id(b),
        "windowStartYear" -> Bind.int(window.startYear),
        "windowEndYear" -> Bind.int(window.endYear)
      )
      got <- budget.graph(graph, q)
      hop <- got match {
        case Vector() => Right(None)
        case Vector(r) =>
          for {
            n <- Cols.long(r, "jointPublicationCount", Q)
            e <- Cols.str(r, "sourceEvidence", Q)
            h <- evidence(rows, a, b, EdgeRow(b, n, e), window)
          } yield Some(h)
        case _ => Left(AgentError.Corrupt(s"$Q: several edges for one pair and window"))
      }
    } yield DirectEdgeResult(a, b, window, hop, budget.executedTemplates)
  }

  /**
   * A shortest coauthorship path from `source` to `target` in `window` of at most `maxDepth` hops
   * (1..`limits.maxPathDepth`): breadth-first, neighbours in researcher-ID order (UTF-8 bytes), the
   * first parent kept, so the path is deterministic. Every neighbour call asks for `maxResults`
   * rows; a call that returns that many may be cut, so a search that finds nothing after any cut
   * call is `Inconclusive`, never `NotConnectedWithin`, and a path found after any cut call is
   * `FoundNotProvenShortest`, never `Found` (RE-P9-04, M1: a cut list may hide a shorter path).
   */
  def shortestPath(
      source: String,
      target: String,
      window: TimeWindow,
      maxDepth: Int
  ): Either[AgentError, PathResult] = {
    val budget = new CallBudget(limits, clock)
    val rows = new AnalysisRows(kv, serving, budget)
    def result(o: PathOutcome) =
      PathResult(
        source,
        target,
        window,
        maxDepth,
        PathTemplate.ShortestPath,
        o,
        budget.graphCallCount,
        budget.executedTemplates
      )
    for {
      _ <- requireId(IdKind.Researcher, source, "source")
      _ <- requireId(IdKind.Researcher, target, "target")
      _ <- if (source != target) Right(()) else Left(AgentError.Refused("source equals target"))
      _ <-
        if (maxDepth >= 1 && maxDepth <= limits.maxPathDepth) Right(())
        else Left(AgentError.Refused(s"maxDepth: $maxDepth is outside 1..${limits.maxPathDepth}"))
      _ <- windowKey(window)
      se <- exists(budget, source)
      te <- exists(budget, target)
      _ <-
        if (se && te) Right(())
        else Left(AgentError.Refused("source or target is not a researcher in the graph"))
      found <- search(budget, source, target, window, maxDepth)
      out <- found match {
        case Left(o) => Right(result(o))
        case Right((path, cutReason)) =>
          path
            .zip(path.tail)
            .foldLeft[Either[AgentError, Vector[Hop]]](Right(Vector.empty)) {
              case (acc, ((from, _), (to, Some(e)))) =>
                acc.flatMap(hs => evidence(rows, from, to, e, window).map(hs :+ _))
              case (_, (_, (to, None))) =>
                Left(AgentError.Corrupt(s"path node $to has no edge"))
            }
            .map(hops =>
              result(cutReason match {
                case None => PathOutcome.Found(path.map(_._1), hops)
                case Some(why) => PathOutcome.FoundNotProvenShortest(path.map(_._1), hops, why)
              })
            )
      }
    } yield out
  }

  /**
   * BFS: Right((path of (node, edge into it), cut reason)) or Left(outcome without a path). The cut
   * reason is Some when any neighbour list read before the hit reached the result limit: the path
   * is then not proven shortest (M1).
   */
  private def search(
      budget: CallBudget,
      source: String,
      target: String,
      w: TimeWindow,
      maxDepth: Int
  ): Either[
    AgentError,
    Either[PathOutcome, (Vector[(String, Option[EdgeRow])], Option[String])]
  ] = {
    def cutReason = s"a neighbour list reached the result limit ${limits.maxResults}"
    val parent = mutable.LinkedHashMap[String, Option[(String, EdgeRow)]](source -> None)
    var frontier = Vector(source)
    var depth = 0
    var cut = false
    var error: Option[AgentError] = None
    var hit = false
    var over = false
    while (!hit && !over && error.isEmpty && frontier.nonEmpty && depth < maxDepth) {
      val next = mutable.ArrayBuffer.empty[String]
      val it = frontier.iterator
      while (!hit && !over && error.isEmpty && it.hasNext) {
        val node = it.next()
        neighbours(budget, node, w, limits.maxResults) match {
          case Left(e) => error = Some(e)
          case Right(ns) =>
            if (ns.size >= limits.maxResults) cut = true
            ns.sortWith((a, b) => Canonical.compareUtf8(a.collaboratorId, b.collaboratorId) < 0)
              .foreach { e =>
                if (!hit && !parent.contains(e.collaboratorId)) {
                  parent(e.collaboratorId) = Some((node, e))
                  next += e.collaboratorId
                  if (e.collaboratorId == target) hit = true
                }
              }
            if (!hit && parent.size > limits.maxVisited) over = true
        }
      }
      frontier = next.toVector.sortWith((a, b) => Canonical.compareUtf8(a, b) < 0)
      depth += 1
    }
    error match {
      case Some(e) => Left(e)
      case None if hit =>
        val path = mutable.ArrayBuffer.empty[(String, Option[EdgeRow])]
        var cur = target
        while (cur != source) {
          val (p, e) = parent(cur).get
          path.prepend((cur, Some(e)))
          cur = p
        }
        path.prepend((source, None))
        Right(Right((path.toVector, Option.when(cut)(cutReason))))
      case None if over =>
        Right(Left(PathOutcome.Inconclusive(s"more than ${limits.maxVisited} researchers visited")))
      case None if cut =>
        Right(Left(PathOutcome.Inconclusive(cutReason)))
      case None => Right(Left(PathOutcome.NotConnectedWithin(maxDepth)))
    }
  }
}
