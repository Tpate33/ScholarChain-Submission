package scholarchain.storage.fake

import scala.collection.immutable.SortedMap
import scala.collection.mutable

import scholarchain.core.{Canonical, CanonicalGraph, EdgeLabel, NodeLabel}
import scholarchain.storage.model.{
  BoundGraphWrite,
  BoundQuery,
  GraphParamValue,
  GraphQuery,
  GraphRow,
  GraphValue,
  PutOutcome,
  StoreError,
  StoredEdge,
  StoredVertex
}
import scholarchain.storage.port.{GraphReader, GraphWriter}

/**
 * In-memory property graph for loader and runtime tests (SE-P7-05 B4; checklist N5). It executes
 * the allowlisted templates by identity, never by parsing text:
 *
 *   - writes ([[BoundGraphWrite]] only): create-once. An absent ID is `Written`; an equal element
 *     (label, endpoints, properties, provenance) is `AlreadyPresent`; a different one is `Conflict`
 *     and the stored element is unchanged. An edge whose `fromId` or `toId` is not a stored vertex,
 *     or whose endpoint labels the edge label does not allow, is `Refused` (the template's `MATCH`
 *     would find nothing to join).
 *   - reads ([[BoundQuery]] only): the nine runtime templates of [[GraphQuery]] with the semantics
 *     of their text (filters, `DISTINCT`, `ORDER BY` with strings in UTF-8 byte order, `LIMIT`); an
 *     absent property is `Null`.
 *
 * Not thread-safe: tests only (it lives in main so rag and services tests can use it). Every view
 * is an immutable snapshot.
 */
final class InMemoryGraphStore extends GraphReader with GraphWriter {
  private val vertexById = mutable.Map.empty[String, StoredVertex]
  private val edgeById = mutable.Map.empty[String, StoredEdge]
  private val executed = mutable.ArrayBuffer.empty[String]
  private val faults = Faults()

  /** The next writes fail with these errors, in order, before touching the store. */
  def failNextWrites(errors: StoreError*): Unit = faults.enqueue(errors*)

  def vertexIds: Set[String] = vertexById.keySet.toSet
  def edgeIds: Set[String] = edgeById.keySet.toSet
  def vertex(id: String): Option[StoredVertex] = vertexById.get(id)
  def edge(id: String): Option[StoredEdge] = edgeById.get(id)
  def vertices: Vector[StoredVertex] = vertexById.values.toVector.sortWith(byId(_.id))
  def edges: Vector[StoredEdge] = edgeById.values.toVector.sortWith(byId(_.id))

  /** The text of every write template executed so far, in order. */
  def executedTemplates: Vector[String] = executed.toVector

  private def byId[A](id: A => String)(a: A, b: A): Boolean =
    Canonical.compareUtf8(id(a), id(b)) < 0

  def storedVertex(id: String): Either[StoreError, Option[StoredVertex]] = Right(vertexById.get(id))
  def storedEdge(id: String): Either[StoreError, Option[StoredEdge]] = Right(edgeById.get(id))

  def write(w: BoundGraphWrite): Either[StoreError, PutOutcome] =
    faults.next() match {
      case Some(e) => Left(e)
      case None =>
        if (w == null) Left(StoreError.Refused("write: missing"))
        else {
          executed += w.template.text
          w.element match {
            case Left(v) => createOnce(vertexById, v.id, v, "vertex")
            case Right(e) => edgeWrite(e)
          }
        }
    }

  private def createOnce[A](
      store: mutable.Map[String, A],
      id: String,
      a: A,
      what: String
  ): Either[StoreError, PutOutcome] =
    store.get(id) match {
      case None => store(id) = a; Right(PutOutcome.Written)
      case Some(existing) if existing == a => Right(PutOutcome.AlreadyPresent)
      case Some(_) => Left(StoreError.Conflict(s"$what $id: holds a different element"))
    }

  private def edgeWrite(e: StoredEdge): Either[StoreError, PutOutcome] = {
    val (fromLabels, toLabels) =
      CanonicalGraph.Endpoints.getOrElse(e.label, (Set.empty[NodeLabel], Set.empty[NodeLabel]))
    def end(which: String, id: String, allowed: Set[NodeLabel]): Either[StoreError, Unit] =
      vertexById.get(id) match {
        case None => Left(StoreError.Refused(s"edge ${e.id}: $which endpoint $id is not a vertex"))
        case Some(v) if !allowed.contains(v.label) =>
          Left(StoreError.Refused(s"edge ${e.id}: $which endpoint $id is a ${v.label.tag} vertex"))
        case Some(_) => Right(())
      }
    for {
      _ <- end("from", e.fromId, fromLabels)
      _ <- end("to", e.toId, toLabels)
      o <- createOnce(edgeById, e.id, e, "edge")
    } yield o
  }

  // ------------------------------------------------------------------ reads

  private def str(p: SortedMap[String, GraphParamValue], k: String): String = p(k) match {
    case GraphParamValue.Str(s) => s
    case GraphParamValue.IntValue(n) => n.toString
  }
  private def int(p: SortedMap[String, GraphParamValue], k: String): Long = p(k) match {
    case GraphParamValue.IntValue(n) => n.toLong
    case GraphParamValue.Str(s) => s.toLong
  }

  private def prop(props: SortedMap[String, GraphValue], k: String): GraphValue =
    props.getOrElse(k, GraphValue.Null)

  private def row(cols: (String, GraphValue)*): GraphRow = GraphRow(SortedMap(cols*))

  private def isLabel(id: String, l: NodeLabel): Boolean = vertexById.get(id).exists(_.label == l)

  private def out(from: String, l: EdgeLabel): Vector[StoredEdge] =
    edgeById.values.toVector.filter(e => e.label == l && e.fromId == from).sortWith(byId(_.id))

  private def compareStr(a: GraphValue, b: GraphValue): Int = (a, b) match {
    case (GraphValue.Str(x), GraphValue.Str(y)) => Canonical.compareUtf8(x, y)
    case _ => 0
  }

  private def ascBy(col: String)(a: GraphRow, b: GraphRow): Boolean =
    compareStr(a.columns(col), b.columns(col)) < 0

  private def window(e: StoredEdge, p: SortedMap[String, GraphParamValue]): Boolean =
    prop(e.properties, "windowStartYear") == GraphValue.IntValue(int(p, "windowStartYear")) &&
      prop(e.properties, "windowEndYear") == GraphValue.IntValue(int(p, "windowEndYear"))

  def run(query: BoundQuery): Either[StoreError, Vector[GraphRow]] =
    if (query == null) Left(StoreError.Refused("query: missing"))
    else {
      val p = query.parameters
      def limit(rows: Vector[GraphRow]) = rows.take(int(p, "limit").toInt)
      Right(query.query match {
        case GraphQuery.ResearcherById =>
          vertexById
            .get(str(p, "researcherId"))
            .filter(_.label == NodeLabel.Researcher)
            .toVector
            .map { r =>
              row(
                "researcherId" -> GraphValue.Str(r.id),
                "anchorNamespace" -> prop(r.properties, "anchorNamespace"),
                "identityStatus" -> prop(r.properties, "identityStatus"),
                "memberCount" -> prop(r.properties, "memberCount")
              )
            }

        case GraphQuery.DirectCollaborators =>
          val r = str(p, "researcherId")
          val rows = edgeById.values.toVector
            .filter(e =>
              e.label == EdgeLabel.CoauthoredWith && (e.fromId == r || e.toId == r) && window(e, p)
            )
            .map(e => (e, if (e.fromId == r) e.toId else e.fromId))
            .filter { case (_, c) =>
              isLabel(r, NodeLabel.Researcher) && isLabel(c, NodeLabel.Researcher)
            }
            .map { case (e, c) =>
              row(
                "collaboratorId" -> GraphValue.Str(c),
                "jointPublicationCount" -> prop(e.properties, "jointPublicationCount"),
                "firstCollaborationYear" -> prop(e.properties, "firstCollaborationYear"),
                "lastCollaborationYear" -> prop(e.properties, "lastCollaborationYear"),
                "sourceEvidence" -> prop(e.properties, "sourceEvidence")
              )
            }
          def count(r: GraphRow) = r.columns("jointPublicationCount") match {
            case GraphValue.IntValue(n) => n
            case _ => Long.MinValue
          }
          limit(rows.sortWith { (a, b) =>
            if (count(a) != count(b)) count(a) > count(b) else ascBy("collaboratorId")(a, b)
          })

        case GraphQuery.CoauthorEdgeBetween =>
          val (a, b) = (str(p, "researcherA"), str(p, "researcherB"))
          edgeById.values.toVector
            .filter(e =>
              e.label == EdgeLabel.CoauthoredWith && window(e, p) &&
                ((e.fromId == a && e.toId == b) || (e.fromId == b && e.toId == a)) &&
                isLabel(a, NodeLabel.Researcher) && isLabel(b, NodeLabel.Researcher)
            )
            .sortWith(byId(_.id))
            .map { e =>
              row(
                "edgeId" -> GraphValue.Str(e.id),
                "jointPublicationCount" -> prop(e.properties, "jointPublicationCount"),
                "firstCollaborationYear" -> prop(e.properties, "firstCollaborationYear"),
                "lastCollaborationYear" -> prop(e.properties, "lastCollaborationYear"),
                "sourceEvidence" -> prop(e.properties, "sourceEvidence")
              )
            }

        case GraphQuery.SharedWorks =>
          val (a, b) = (str(p, "researcherA"), str(p, "researcherB"))
          def works(r: String) =
            if (!isLabel(r, NodeLabel.Researcher)) Set.empty[String]
            else out(r, EdgeLabel.Authored).map(_.toId).filter(isLabel(_, NodeLabel.Work)).toSet
          val shared = works(a).intersect(works(b)).toVector
          limit(shared.map(w => workRow(w)).sortWith(ascBy("workId")))

        case GraphQuery.WorksOfResearcher =>
          val r = str(p, "researcherId")
          val rows =
            if (!isLabel(r, NodeLabel.Researcher)) Vector.empty
            else
              out(r, EdgeLabel.Authored).map(_.toId).filter(isLabel(_, NodeLabel.Work)).map(workRow)
          limit(rows.sortWith(ascBy("workId")))

        case GraphQuery.WorkVenue =>
          val w = str(p, "workId")
          named(w, NodeLabel.Work, EdgeLabel.PublishedIn, NodeLabel.Venue, "venueId")

        case GraphQuery.WorkTopics =>
          val w = str(p, "workId")
          limit(named(w, NodeLabel.Work, EdgeLabel.HasTopic, NodeLabel.Topic, "topicId"))

        case GraphQuery.ResearcherInstitutions =>
          val r = str(p, "researcherId")
          limit(
            named(
              r,
              NodeLabel.Researcher,
              EdgeLabel.AffiliatedWith,
              NodeLabel.Institution,
              "institutionId"
            ).distinct
          )

        case GraphQuery.MentionResolution =>
          val m = str(p, "mentionId")
          if (!isLabel(m, NodeLabel.AuthorMention)) Vector.empty
          else
            out(m, EdgeLabel.ResolvesTo).filter(e => isLabel(e.toId, NodeLabel.Researcher)).map {
              e =>
                row(
                  "researcherId" -> GraphValue.Str(e.toId),
                  "assignmentStatus" -> prop(e.properties, "assignmentStatus")
                )
            }
      })
    }

  private def workRow(w: String): GraphRow =
    row(
      "workId" -> GraphValue.Str(w),
      "year" -> vertexById.get(w).map(v => prop(v.properties, "year")).getOrElse(GraphValue.Null)
    )

  /** `(subject:fromLabel)-[:edge]->(x:toLabel)` rows `{idColumn: id(x), name: x.name}`. */
  private def named(
      subject: String,
      fromLabel: NodeLabel,
      edge: EdgeLabel,
      toLabel: NodeLabel,
      idColumn: String
  ): Vector[GraphRow] =
    if (!isLabel(subject, fromLabel)) Vector.empty
    else
      out(subject, edge)
        .map(_.toId)
        .flatMap(vertexById.get)
        .filter(_.label == toLabel)
        .map(x => row(idColumn -> GraphValue.Str(x.id), "name" -> prop(x.properties, "name")))
        .sortWith(ascBy(idColumn))
}
