package scholarchain.storage.load

import scala.collection.immutable.SortedMap
import scala.collection.mutable

import scholarchain.core.{
  CanonicalGraph,
  CanonicalPair,
  EdgeLabel,
  GraphNode,
  IdKind,
  LoadRoles,
  MentionId,
  NeptuneCsv,
  NodeLabel,
  StableId,
  StoreLoadManifest,
  TimeWindow,
  ValidationError
}
import scholarchain.storage.model.{Bytes, GraphValue, StoreError}

/** One problem of one Neptune CSV part; `record` is the 1-based data record, 0 for the file. */
final case class RowProblem(path: String, record: Long, reason: String) {
  def subject: String = if (record == 0) path else s"$path#$record"
}

/** One parsed vertex row (values typed per the Neptune `name:Type` header). */
final case class VertexRow(
    path: String,
    record: Long,
    id: String,
    label: NodeLabel,
    properties: SortedMap[String, GraphValue]
)

/** One parsed edge row; `properties` include `sourceEvidence` and `runId`. */
final case class EdgeRow(
    path: String,
    record: Long,
    id: String,
    fromId: String,
    toId: String,
    label: EdgeLabel,
    properties: SortedMap[String, GraphValue]
)

/** Every row of one Neptune store load, in item (path) order then record order. */
final case class NeptuneGraph(vertices: Vector[VertexRow], edges: Vector[EdgeRow])

/**
 * Row contracts and graph IDs of the `neptune-bulk-load` store (SE-P7-05 B1, B2; checklist N2, N3,
 * N5, G-ID). Everything comes from `core`: the roles ([[LoadRoles]]), the header layout
 * ([[NeptuneCsv]]), the property profiles, endpoint labels and evidence kinds ([[CanonicalGraph]]),
 * the ID rules ([[StableId]], [[MentionId]], [[CanonicalPair]], [[TimeWindow]]). Storage restates
 * none of them.
 *
 * Row contracts (deferred check `row-contracts`, for this store only), per record of every item:
 *   - the file is strict RFC 4180 and valid UTF-8; its header equals the core layout of the item's
 *     role, and every column type is `Int` or `String`;
 *   - `~label` is the role's label, one of the six canonical vertex labels or the six relationship
 *     labels AUTHORED, COAUTHORED_WITH, PUBLISHED_IN, AFFILIATED_WITH, HAS_TOPIC, RESOLVES_TO;
 *   - every `Int` value parses as a 32-bit decimal integer and every non-empty property value
 *     passes its core profile rule; every required profile property is present (an empty field is
 *     an absent property);
 *   - `~id` is a valid core ID of the label's kind (`gedge:` for edges); `~from` and `~to` are
 *     valid IDs of the kinds the label allows; a COAUTHORED_WITH pair is stored in canonical order,
 *     a directed edge never joins a vertex to itself;
 *   - `sourceEvidence` is an ID of the label's evidence kind (the mention itself for RESOLVES_TO
 *     and a mention-endpoint AUTHORED edge); COAUTHORED_WITH carries jointPublicationCount,
 *     firstCollaborationYear, lastCollaborationYear, windowStartYear, windowEndYear and its
 *     evidence pointer, with a valid window and both years inside it in order (N3);
 *   - an AuthorMention `~id` is [[MentionId.of]] of its `workId` and `position`;
 *   - `runId` is the verified run.
 *
 * Graph IDs (deferred check `graph-ids`, this store only): no `~id` appears twice among the run's
 * vertices or among its edges; every `~from` and `~to` is a vertex of the run whose label the edge
 * label allows. IDs are copied verbatim, never minted.
 *
 * Not covered here (left to deferred checks `graph-id-parity` and `evidence-trace`, which need the
 * Parquet row reader): equality of the CSV rows with the `graph-nodes` / `graph-edges` Parquet
 * records, their full record contracts (lineage, provenance), and the edge-ID and coauthor-ID
 * formulas, which need the graph policy version (not in the CSV or the run manifest).
 */
object NeptuneRows {

  val vertexRoles: Map[String, NodeLabel] =
    CanonicalGraph.NodeLabels.map(l => LoadRoles.vertexRole(l) -> l).toMap

  val edgeRoles: Map[String, EdgeLabel] =
    CanonicalGraph.EdgeLabels.map(l => LoadRoles.edgeRole(l) -> l).toMap

  /** The Neptune column types this layout uses. */
  val ColumnTypes: Set[String] = Set("Int", "String")

  private val SystemColumns = Set("~id", "~label", "~from", "~to")

  /** (name, type) of a typed header column `name:Type`. */
  private def typed(column: String): Option[(String, String)] = {
    val i = column.lastIndexOf(':')
    if (i <= 0) None else Some((column.substring(0, i), column.substring(i + 1)))
  }

  /**
   * A canonical 32-bit decimal (CR-P7-07 n4): no leading zero, no `+`, no `-0`, so the CSV text is
   * the one rendering of its value and graph-id-parity's text comparison compares values.
   */
  private def int32(s: String): Option[Long] =
    if (s.matches("0|-?[1-9][0-9]{0,9}"))
      s.toLongOption.filter(n => n >= Int.MinValue && n <= Int.MaxValue)
    else None

  private def messages(es: Vector[ValidationError]): Vector[String] = es.map(_.message)

  /** Typed properties of the non-system columns; empty fields are absent. */
  private def values(
      columns: Vector[String],
      row: Vector[String]
  ): (SortedMap[String, GraphValue], SortedMap[String, String], Vector[String]) = {
    val typedOut = mutable.TreeMap.empty[String, GraphValue]
    val raw = mutable.TreeMap.empty[String, String]
    val problems = mutable.ArrayBuffer.empty[String]
    columns.zip(row).foreach { case (c, v) =>
      if (!SystemColumns.contains(c) && v.nonEmpty)
        typed(c) match {
          case Some((name, "Int")) =>
            int32(v) match {
              case Some(n) => typedOut(name) = GraphValue.IntValue(n); raw(name) = v
              case None => problems += s"$name: '$v' is not a 32-bit decimal Int"
            }
          case Some((name, "String")) => typedOut(name) = GraphValue.Str(v); raw(name) = v
          case _ => problems += s"$c: not a typed column of a known type"
        }
    }
    (SortedMap.from(typedOut), SortedMap.from(raw), problems.toVector)
  }

  /** Header problems against the core columns of the role. */
  private def header(path: String, got: Vector[String], expected: Vector[String]) =
    if (got != expected)
      Vector(RowProblem(path, 0, "header differs from the NeptuneCsv layout of its role"))
    else
      expected
        .filterNot(SystemColumns.contains)
        .filterNot(c => typed(c).exists(t => ColumnTypes.contains(t._2)))
        .map(c => RowProblem(path, 0, s"column $c has no Int or String type"))

  private def records(
      path: String,
      bytes: Bytes
  ): Either[Vector[RowProblem], Vector[Vector[String]]] =
    CsvRecords.parse(bytes).left.map(why => Vector(RowProblem(path, 0, why)))

  /** Every vertex row of one part of a vertex role, or its problems. */
  def vertices(
      role: String,
      path: String,
      bytes: Bytes,
      runId: String
  ): Either[Vector[RowProblem], Vector[VertexRow]] =
    vertexRoles.get(role) match {
      case None => Left(Vector(RowProblem(path, 0, s"$role is not a Neptune vertex role")))
      case Some(label) =>
        records(path, bytes).flatMap { recs =>
          val columns = NeptuneCsv.vertexColumns(label)
          val head = recs.headOption.getOrElse(Vector.empty)
          val hp = header(path, head, columns)
          if (hp.nonEmpty) Left(hp)
          else {
            val problems = mutable.ArrayBuffer.empty[RowProblem]
            val rows = recs.tail.zipWithIndex.flatMap { case (r, i) =>
              val n = i + 1L
              def bad(why: String): Unit = problems += RowProblem(path, n, why)
              val f = columns.zip(r).toMap
              val id = f("~id")
              val (props, raw, typeProblems) = values(columns, r)
              typeProblems.foreach(bad)
              if (f("~label") != label.tag) bad(s"~label '${f("~label")}' is not ${label.tag}")
              messages(StableId.check(GraphNode.IdKindOf(label), id, "~id")).foreach(bad)
              val profile = raw - "runId"
              if (typeProblems.isEmpty)
                messages(
                  CanonicalGraph
                    .checkProperties("properties", CanonicalGraph.NodeProfiles(label), profile)
                ).foreach(bad)
              runIdProblem(raw.get("runId"), runId).foreach(bad)
              if (label == NodeLabel.AuthorMention)
                (raw.get("workId"), raw.get("position").flatMap(_.toIntOption)) match {
                  case (Some(w), Some(p)) =>
                    MentionId.of(w, p).toOption.filter(_ != id).foreach { expected =>
                      bad(s"~id is not the mention at position $p of $w (expected $expected)")
                    }
                  case _ => () // missing properties reported by the profile check
                }
              Vector(VertexRow(path, n, id, label, props))
            }
            if (problems.isEmpty) Right(rows) else Left(problems.toVector)
          }
        }
    }

  /** Every edge row of one part of an edge role, or its problems. */
  def edges(
      role: String,
      path: String,
      bytes: Bytes,
      runId: String
  ): Either[Vector[RowProblem], Vector[EdgeRow]] =
    edgeRoles.get(role) match {
      case None => Left(Vector(RowProblem(path, 0, s"$role is not a Neptune edge role")))
      case Some(label) =>
        records(path, bytes).flatMap { recs =>
          val columns = NeptuneCsv.edgeColumns(label)
          val head = recs.headOption.getOrElse(Vector.empty)
          val hp = header(path, head, columns)
          if (hp.nonEmpty) Left(hp)
          else {
            val problems = mutable.ArrayBuffer.empty[RowProblem]
            val (fromLabels, toLabels) = CanonicalGraph.Endpoints(label)
            val rows = recs.tail.zipWithIndex.flatMap { case (r, i) =>
              val n = i + 1L
              def bad(why: String): Unit = problems += RowProblem(path, n, why)
              val f = columns.zip(r).toMap
              val (id, from, to) = (f("~id"), f("~from"), f("~to"))
              val (props, raw, typeProblems) = values(columns, r)
              typeProblems.foreach(bad)
              if (f("~label") != label.tag) bad(s"~label '${f("~label")}' is not ${label.tag}")
              messages(StableId.check(IdKind.GraphEdge, id, "~id")).foreach(bad)
              val endpointErrors =
                StableId.checkOneOf(fromLabels.map(GraphNode.IdKindOf), from, "~from") ++
                  StableId.checkOneOf(toLabels.map(GraphNode.IdKindOf), to, "~to")
              messages(endpointErrors).foreach(bad)
              if (label.undirected)
                messages(CanonicalPair.checkStored("~from,~to", from, to)).foreach(bad)
              else if (from == to) bad("~from and ~to are the same vertex")
              val evidence = raw.get("sourceEvidence")
              evidence match {
                case None =>
                  bad("sourceEvidence: missing (every edge carries its evidence pointer)")
                case Some(ev) =>
                  val kindErrors =
                    StableId.check(CanonicalGraph.SourceEvidenceKind(label), ev, "sourceEvidence")
                  messages(kindErrors).foreach(bad)
                  val fromMention = StableId.prefixKind(from).contains(IdKind.Mention)
                  if (
                    kindErrors.isEmpty && (label == EdgeLabel.ResolvesTo ||
                      (label == EdgeLabel.Authored && fromMention)) && ev != from
                  )
                    bad(
                      "sourceEvidence: for an AuthorMention endpoint the evidence is the mention itself"
                    )
              }
              val profile = raw - "runId" - "sourceEvidence"
              val profileErrors =
                if (typeProblems.nonEmpty) Vector.empty
                else
                  CanonicalGraph
                    .checkProperties("properties", CanonicalGraph.EdgeProfiles(label), profile)
              messages(profileErrors).foreach(bad)
              if (
                label == EdgeLabel.CoauthoredWith && typeProblems.isEmpty && profileErrors.isEmpty
              )
                coauthorWindow(profile).foreach(bad)
              runIdProblem(raw.get("runId"), runId).foreach(bad)
              Vector(EdgeRow(path, n, id, from, to, label, props))
            }
            if (problems.isEmpty) Right(rows) else Left(problems.toVector)
          }
        }
    }

  private def runIdProblem(got: Option[String], runId: String): Option[String] =
    got match {
      case None => Some("runId: missing (every row carries its run)")
      case Some(r) if r != runId => Some(s"runId: $r is not the verified run $runId")
      case Some(_) => None
    }

  /** COAUTHORED_WITH: a valid window holding both years, in order (properties already valid). */
  private def coauthorWindow(p: SortedMap[String, String]): Option[String] = {
    def i(k: String) = p(k).toInt
    TimeWindow.of(i("windowStartYear"), i("windowEndYear")) match {
      case Left(e) => Some(s"window: ${e.message}")
      case Right(w) =>
        val (first, last) = (i("firstCollaborationYear"), i("lastCollaborationYear"))
        if (first > last || !w.contains(first) || !w.contains(last))
          Some("collaboration years must be ordered and inside the window")
        else None
    }
  }

  /**
   * Graph IDs over the whole load: unique vertex `~id`, unique edge `~id`, every endpoint a vertex
   * of the run with a label the edge label allows.
   */
  def graphIds(g: NeptuneGraph): Vector[RowProblem] = {
    val problems = mutable.ArrayBuffer.empty[RowProblem]
    def dups[A](xs: Vector[A])(id: A => String, at: A => RowProblem): Unit =
      xs.groupBy(id).toVector.sortBy(_._1).foreach { case (k, same) =>
        if (same.size > 1)
          same.tail
            .foreach(x => problems += at(x).copy(reason = s"~id $k appears ${same.size} times"))
      }
    dups(g.vertices)(_.id, v => RowProblem(v.path, v.record, ""))
    dups(g.edges)(_.id, e => RowProblem(e.path, e.record, ""))
    val labelOf = g.vertices.map(v => v.id -> v.label).toMap
    g.edges.foreach { e =>
      val (fromLabels, toLabels) = CanonicalGraph.Endpoints(e.label)
      def end(which: String, id: String, allowed: Set[NodeLabel]): Unit =
        labelOf.get(id) match {
          case None =>
            problems += RowProblem(
              e.path,
              e.record,
              s"dangling $which endpoint $id: no vertex of the run"
            )
          case Some(l) if !allowed.contains(l) =>
            problems += RowProblem(
              e.path,
              e.record,
              s"$which endpoint $id is a ${l.tag} vertex; ${e.label.tag} allows " +
                allowed.map(_.tag).toVector.sorted.mkString(", ")
            )
          case _ => ()
        }
      end("~from", e.fromId, fromLabels)
      end("~to", e.toId, toLabels)
    }
    problems.toVector
  }

  /**
   * Parses every item of a Neptune load manifest through `read` (verified bytes), in path order:
   * row-contract problems, or the graph.
   */
  def parse(
      load: StoreLoadManifest,
      runId: String,
      read: String => Either[StoreError, Bytes]
  ): Either[Vector[RowProblem], NeptuneGraph] = {
    val problems = mutable.ArrayBuffer.empty[RowProblem]
    val vs = Vector.newBuilder[VertexRow]
    val es = Vector.newBuilder[EdgeRow]
    load.items
      .sortWith((a, b) => scholarchain.core.Canonical.compareUtf8(a.path, b.path) < 0)
      .foreach { it =>
        read(it.path) match {
          case Left(e) => problems += RowProblem(it.path, 0, e.message)
          case Right(b) =>
            if (vertexRoles.contains(it.targetRole))
              vertices(it.targetRole, it.path, b, runId).fold(problems ++= _, vs ++= _)
            else if (edgeRoles.contains(it.targetRole))
              edges(it.targetRole, it.path, b, runId).fold(problems ++= _, es ++= _)
            else problems += RowProblem(it.path, 0, s"${it.targetRole} is not a Neptune role")
        }
      }
    if (problems.nonEmpty) Left(problems.toVector)
    else Right(NeptuneGraph(vs.result(), es.result()))
  }
}
