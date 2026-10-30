package scholarchain.core

import scala.collection.immutable.SortedMap

import upickle.default.ReadWriter
import upickle.implicits.allowUnknownKeys

import Validated.{all, at, when}

/** A property-graph node. The ID kind must match the label (for example `Work` -> `work:`). */
@allowUnknownKeys(false)
final case class GraphNode(
    schemaVersion: String,
    nodeId: String,
    label: NodeLabel,
    properties: SortedMap[String, String],
    provenance: Provenance
) derives ReadWriter

object GraphNode {

  /** ID kind of each node class. */
  val IdKindOf: Map[NodeLabel, IdKind] = Map(
    NodeLabel.Researcher -> IdKind.Researcher,
    NodeLabel.AuthorMention -> IdKind.Mention,
    NodeLabel.Work -> IdKind.Work,
    NodeLabel.Venue -> IdKind.Venue,
    NodeLabel.Institution -> IdKind.Institution,
    NodeLabel.Topic -> IdKind.Topic,
    NodeLabel.TextChunk -> IdKind.Chunk,
    NodeLabel.DatasetSnapshot -> IdKind.Snapshot
  )

  given contract: RecordContract[GraphNode] =
    new RecordContract[GraphNode]("GraphNode", SchemaVersions.GraphNode, true) {
      def schemaVersionOf(t: GraphNode): String = t.schemaVersion
      def provenanceOf(t: GraphNode): Provenance = t.provenance
      protected def fields(t: GraphNode, p: String): Vector[ValidationError] =
        all(
          when(t.label == null)(ValidationError.MissingField(at(p, "label"))),
          Option(t.label).toVector.flatMap(l =>
            StableId.check(IdKindOf(l), t.nodeId, at(p, "nodeId"))
          ),
          when(t.properties == null)(ValidationError.MissingField(at(p, "properties")))
        )
    }
}

/**
 * A property-graph edge. `edgeId` is [[GraphEdgeId.of]](edgeType, fromId, toId,
 * provenance.policyVersion, sourceEvidence). For the undirected `COAUTHORED_WITH`, endpoints are
 * stored in canonical order, `sourceEvidence` is the [[CoauthorEdge]] ID (the stable pointer to the
 * supporting works), and `properties` must contain `jointPublicationCount`,
 * `firstCollaborationYear`, and `lastCollaborationYear`.
 */
@allowUnknownKeys(false)
final case class GraphEdge(
    schemaVersion: String,
    edgeId: String,
    fromId: String,
    edgeType: EdgeLabel,
    toId: String,
    sourceEvidence: String,
    properties: SortedMap[String, String],
    provenance: Provenance
) derives ReadWriter

object GraphEdge {
  private val AnyNode: Set[IdKind] = GraphNode.IdKindOf.values.toSet

  /** Allowed (from, to) endpoint ID kinds of each edge class. */
  val Endpoints: Map[EdgeLabel, (Set[IdKind], Set[IdKind])] = Map(
    EdgeLabel.Authored -> (Set(IdKind.Researcher, IdKind.Mention), Set(IdKind.Work)),
    EdgeLabel.ResolvesTo -> (Set(IdKind.Mention), Set(IdKind.Researcher)),
    EdgeLabel.CoauthoredWith -> (Set(IdKind.Researcher), Set(IdKind.Researcher)),
    EdgeLabel.PublishedIn -> (Set(IdKind.Work), Set(IdKind.Venue)),
    EdgeLabel.AffiliatedWith -> (Set(IdKind.Researcher, IdKind.Mention), Set(IdKind.Institution)),
    EdgeLabel.HasTopic -> (Set(IdKind.Work, IdKind.Researcher), Set(IdKind.Topic)),
    EdgeLabel.DerivedFrom -> (AnyNode, Set(IdKind.Snapshot)),
    EdgeLabel.SupportedBy -> (AnyNode, Set(IdKind.Chunk, IdKind.Work, IdKind.Snapshot))
  )

  /** Properties every `COAUTHORED_WITH` edge must carry. */
  val CoauthoredWithProperties: Vector[String] =
    Vector("firstCollaborationYear", "jointPublicationCount", "lastCollaborationYear")

  given contract: RecordContract[GraphEdge] =
    new RecordContract[GraphEdge]("GraphEdge", SchemaVersions.GraphEdge, true) {
      def schemaVersionOf(t: GraphEdge): String = t.schemaVersion
      def provenanceOf(t: GraphEdge): Provenance = t.provenance
      protected def fields(t: GraphEdge, p: String): Vector[ValidationError] = {
        val policy = Option(t.provenance).flatMap(pr => Option(pr.policyVersion).flatten)
        val props = Option(t.properties).getOrElse(SortedMap.empty[String, String])
        if (t.edgeType == null) Vector(ValidationError.MissingField(at(p, "edgeType")))
        else {
          val (fromKinds, toKinds) = Endpoints(t.edgeType)
          val endpointErrors =
            StableId.checkOneOf(fromKinds, t.fromId, at(p, "fromId")) ++
              StableId.checkOneOf(toKinds, t.toId, at(p, "toId"))
          all(
            endpointErrors,
            if (t.edgeType.undirected)
              CanonicalPair.checkStored(at(p, "fromId,toId"), t.fromId, t.toId)
            else
              when(t.fromId != null && t.fromId == t.toId)(
                ValidationError.SelfPair(at(p, "fromId,toId"), t.fromId)
              ),
            when(!Identifiers.isPrintableAscii(t.sourceEvidence))(
              ValidationError.MissingField(at(p, "sourceEvidence"))
            ),
            if (t.edgeType == EdgeLabel.CoauthoredWith)
              StableId.check(IdKind.CoauthorEdge, t.sourceEvidence, at(p, "sourceEvidence"))
            else Vector.empty,
            when(t.properties == null)(ValidationError.MissingField(at(p, "properties"))),
            if (t.edgeType == EdgeLabel.CoauthoredWith)
              CoauthoredWithProperties.flatMap { k =>
                when(!props.contains(k))(ValidationError.MissingField(at(p, s"properties.$k")))
              }
            else Vector.empty,
            when(policy.isEmpty)(ValidationError.MissingField(at(p, "provenance.policyVersion"))),
            StableId.check(IdKind.GraphEdge, t.edgeId, at(p, "edgeId")),
            policy match {
              case Some(pv) if endpointErrors.isEmpty =>
                GraphEdgeId.of(t.edgeType, t.fromId, t.toId, pv, t.sourceEvidence) match {
                  case Right(expected) =>
                    when(expected != t.edgeId)(
                      ValidationError.InvalidValue(at(p, "edgeId"), s"expected $expected")
                    )
                  case Left(_) => Vector.empty
                }
              case _ => Vector.empty
            }
          )
        }
      }
    }
}
