package scholarchain.storage.model

import scala.collection.immutable.SortedMap

import scholarchain.core.{CanonicalGraph, EdgeLabel, GraphNode, IdKind, NodeLabel, StableId}

/**
 * Provenance every graph element written by a loader keeps (checklist L3, SE-P7-05 B4): the
 * analysis run, the store load, the part file it was read from (relative to the run directory) and
 * that part's record schema version.
 */
final case class GraphProvenance(
    runId: String,
    loadId: String,
    sourcePath: String,
    recordSchemaVersion: String
)

/**
 * One vertex as a loader writes it. `id` is the Spark `~id`, copied verbatim (the loader never
 * mints an ID); `properties` are the typed CSV columns other than `~id` and `~label` (absent
 * optional properties are left out), including `runId`.
 */
final case class StoredVertex(
    id: String,
    label: NodeLabel,
    properties: SortedMap[String, GraphValue],
    provenance: GraphProvenance
)

/**
 * One edge as a loader writes it: `id`, `fromId`, `toId` are the Spark `~id`, `~from`, `~to`,
 * verbatim; `properties` include `sourceEvidence` (the evidence pointer) and `runId`.
 */
final case class StoredEdge(
    id: String,
    label: EdgeLabel,
    fromId: String,
    toId: String,
    properties: SortedMap[String, GraphValue],
    provenance: GraphProvenance
)

/** What a write template creates. */
enum GraphWriteKind {
  case Vertex
  case Edge
}

/**
 * One allowlisted, parameterized openCypher write (SE-P7-05 B4, README L1377). The text is fixed at
 * startup from the frozen core label tags and holds no data: every value is a `$parameter` (`$id`,
 * `$fromId`, `$toId`, `$properties`, `$provenance`). Neptune openCypher support of the exact
 * constructs (`MERGE` with a `~id` property map, `ON CREATE SET ... +=` with a map parameter) is
 * UNVERIFIED until the bounded integration run (A1 adapters are deferred); the in-memory store
 * executes the template by identity, with create-once semantics. A plain class with a private
 * constructor: only [[GraphWriteTemplates]] holds instances.
 */
final class GraphWriteTemplate private[model] (
    val name: String,
    val text: String,
    val kind: GraphWriteKind,
    val label: String,
    val parameters: Vector[String]
) {
  override def toString: String = s"GraphWriteTemplate($name)"
}

/** The closed set of loader write templates: one per canonical vertex label and edge label. */
object GraphWriteTemplates {

  private val LabelToken = "^[A-Za-z_]+$".r

  private def checked(tag: String): String = {
    require(LabelToken.matches(tag), s"label tag $tag is not a plain identifier")
    tag
  }

  val vertices: Map[NodeLabel, GraphWriteTemplate] =
    CanonicalGraph.NodeLabels.map { l =>
      val t = checked(l.tag)
      l -> new GraphWriteTemplate(
        s"create-vertex-$t",
        s"MERGE (v:$t {`~id`: $$id}) ON CREATE SET v += $$properties, v += $$provenance",
        GraphWriteKind.Vertex,
        t,
        Vector("id", "properties", "provenance")
      )
    }.toMap

  val edges: Map[EdgeLabel, GraphWriteTemplate] =
    CanonicalGraph.EdgeLabels.map { l =>
      val t = checked(l.tag)
      l -> new GraphWriteTemplate(
        s"create-edge-$t",
        s"MATCH (a), (b) WHERE id(a) = $$fromId AND id(b) = $$toId " +
          s"MERGE (a)-[e:$t {`~id`: $$id}]->(b) ON CREATE SET e += $$properties, e += $$provenance",
        GraphWriteKind.Edge,
        t,
        Vector("id", "fromId", "toId", "properties", "provenance")
      )
    }.toMap

  /** Every template, vertex templates first, each in core label order. */
  val all: Vector[GraphWriteTemplate] =
    CanonicalGraph.NodeLabels.map(vertices) ++ CanonicalGraph.EdgeLabels.map(edges)
}

/**
 * A write template bound to one element. The constructor is private: the only way to obtain one is
 * [[BoundGraphWrite.vertex]] or [[BoundGraphWrite.edge]], which pick the template of the element's
 * label and check its IDs with `core`, and the graph writer port accepts nothing else, so no write
 * text is ever built from data.
 */
final class BoundGraphWrite private (
    val template: GraphWriteTemplate,
    val element: Either[StoredVertex, StoredEdge]
) {
  override def toString: String = s"BoundGraphWrite(${template.name}, ${element.fold(_.id, _.id)})"
}

object BoundGraphWrite {
  private def refused(e: scholarchain.core.ValidationError) = StoreError.Refused(e.message)

  private def provenance(p: GraphProvenance): Either[StoreError, GraphProvenance] =
    for {
      _ <- Option(p).toRight(StoreError.Refused("provenance: missing"))
      _ <- StableId.validate(IdKind.Run, p.runId, "provenance.runId").left.map(refused)
      _ <- StableId.validate(IdKind.StoreLoad, p.loadId, "provenance.loadId").left.map(refused)
      _ <- S3Layout.checkRelative(p.sourcePath)
      _ <-
        if (p.recordSchemaVersion != null && p.recordSchemaVersion.nonEmpty) Right(())
        else Left(StoreError.Refused("provenance.recordSchemaVersion: missing"))
    } yield p

  def vertex(v: StoredVertex): Either[StoreError, BoundGraphWrite] =
    for {
      _ <- Option(v).toRight(StoreError.Refused("vertex: missing"))
      t <- GraphWriteTemplates.vertices
        .get(v.label)
        .toRight(StoreError.Refused(s"label: ${v.label} has no write template"))
      _ <- StableId.validate(GraphNode.IdKindOf(v.label), v.id, "~id").left.map(refused)
      _ <- Option(v.properties).toRight(StoreError.Refused("properties: missing"))
      _ <- provenance(v.provenance)
    } yield new BoundGraphWrite(t, Left(v))

  def edge(e: StoredEdge): Either[StoreError, BoundGraphWrite] =
    for {
      _ <- Option(e).toRight(StoreError.Refused("edge: missing"))
      t <- GraphWriteTemplates.edges
        .get(e.label)
        .toRight(StoreError.Refused(s"label: ${e.label} has no write template"))
      _ <- StableId.validate(IdKind.GraphEdge, e.id, "~id").left.map(refused)
      _ <- StableId.validateAny(e.fromId, "~from").left.map(refused)
      _ <- StableId.validateAny(e.toId, "~to").left.map(refused)
      _ <- Option(e.properties).toRight(StoreError.Refused("properties: missing"))
      _ <- provenance(e.provenance)
    } yield new BoundGraphWrite(t, Right(e))
}
