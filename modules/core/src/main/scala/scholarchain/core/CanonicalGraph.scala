package scholarchain.core

import scala.collection.immutable.SortedMap
import scala.collection.mutable

import upickle.default.ReadWriter
import upickle.implicits.allowUnknownKeys

import scholarchain.core.config.AnalysisRunSettings

import Validated.{all, at, when}

// =====================================================================================
// Phase 6 (DE-P6-03), course item 1: the canonical Researcher, AuthorMention, Work, Venue,
// Institution and Topic tables and their edges.
//
// Decision (DE-P6-03): the tables use the generic property-graph shape of `graph-node.v1` and
// `graph-edge.v1` (one record type for all labels: label, ID, string properties), versioned up to
// `graph-node.v2` / `graph-edge.v2` ([[GraphNodeV2]], [[GraphEdgeV2]]) because every analysis
// output carries the run lineage (binding decision 4) and the v1 records have none. The shape maps
// one to one onto Neptune property-graph vertices and edges ([[NeptuneCsv]]), so one loader serves
// every label. What v1 left open is now frozen per label: the exact property keys, their value
// rules ([[CanonicalGraph.NodeProfiles]], [[CanonicalGraph.EdgeProfiles]]), the endpoint labels
// and the kind of `sourceEvidence` of each edge class. Dedicated typed records per label were not
// chosen: they would need one loader and one Neptune mapping per label without adding a check
// that the profiles cannot express.
// =====================================================================================

/** Value rule of one canonical graph property; `neptuneType` is its Neptune CSV column type. */
enum GraphPropertyKind(val neptuneType: String) {

  /** Non-blank source text without control characters (names). */
  case Text extends GraphPropertyKind("String")

  /** A token `[A-Za-z0-9._-]+`. */
  case Token extends GraphPropertyKind("String")

  /** A work ID. */
  case WorkRef extends GraphPropertyKind("String")

  /** A decimal integer >= 1 (no sign, no leading zero), at most Int.MaxValue. */
  case Count extends GraphPropertyKind("Int")

  /** A decimal integer >= 0 (no sign, no leading zero), at most Int.MaxValue. */
  case Ordinal extends GraphPropertyKind("Int")

  /** A calendar year in [[Validated.MinYear]]..[[Validated.MaxYear]], decimal. */
  case Year extends GraphPropertyKind("Int")

  /** One of the listed frozen tags. */
  case OneOf(values: Vector[String]) extends GraphPropertyKind("String")

  /** Why `v` is not a value of this kind, or None. */
  def problem(v: String): Option[String] = {
    def decimal(min: Long, max: Long): Option[String] =
      if (
        v != null && v.matches("0|[1-9][0-9]{0,9}") && v.toLongOption
          .exists(n => n >= min && n <= max)
      )
        None
      else Some(s"must be a decimal integer in [$min, $max]")
    this match {
      case Text =>
        if (Validated.text("v", v).isEmpty) None
        else Some("must be non-blank text without control characters")
      case Token => if (StableId.isToken(v)) None else Some("must be a token [A-Za-z0-9._-]+")
      case WorkRef =>
        if (StableId.validate(IdKind.Work, v).isRight) None else Some("must be a work ID")
      case Count => decimal(1, Int.MaxValue)
      case Ordinal => decimal(0, Int.MaxValue)
      case Year => decimal(Validated.MinYear.toLong, Validated.MaxYear.toLong)
      case OneOf(vs) => if (vs.contains(v)) None else Some(s"must be one of ${vs.mkString(", ")}")
    }
  }
}

/** One property key of a canonical graph profile. */
final case class GraphPropertySpec(key: String, required: Boolean, kind: GraphPropertyKind)

/**
 * The frozen profiles of the canonical graph tables (versioned by `graph-node.v2` and
 * `graph-edge.v2`). Every property value is a string; the key set of a record is exactly its
 * label's required keys plus any of its optional keys, nothing else.
 *
 * Node labels and properties:
 *   - `Researcher` (ID `researcher:`): `anchorNamespace` (token), `identityStatus` (an
 *     [[IdentityStatus]] tag), `memberCount` (the grouping members sharing the ID, >= 1).
 *   - `AuthorMention` (ID `mention:`): `assignmentStatus` (an [[AssignmentStatus]] tag), `position`
 *     (0-based author position), `workId` (the mention's work, a representative work).
 *   - `Work` (ID `work:`, a canonical work = its representative): `graphEligibility` (`counted` or
 *     `enrichment-only`), `groupSize` (works in the canonical work), optional `year`.
 *   - `Venue`, `Institution`, `Topic` (IDs `venue:`, `institution:`, `topic:`): `name`.
 *   - `TextChunk` and `DatasetSnapshot` are runtime nodes, never in the canonical tables.
 *
 * Edge classes (endpoint labels; `sourceEvidence` kind; properties):
 *   - `AUTHORED`: Researcher or AuthorMention -> Work; the author mention (for a mention endpoint,
 *     the mention itself); `position`.
 *   - `RESOLVES_TO`: AuthorMention -> Researcher; the mention itself; `assignmentStatus`
 *     (`resolved` or `singleton`).
 *   - `COAUTHORED_WITH` (undirected, canonical order): Researcher - Researcher; the
 *     [[CoauthorEdgeV2]] ID (which must equal [[CoauthorEdgeId]] of the pair, window and graph
 *     policy); `firstCollaborationYear`, `jointPublicationCount`, `lastCollaborationYear`,
 *     `windowEndYear`, `windowStartYear`.
 *   - `PUBLISHED_IN`: Work -> Venue; the asserting member work; no property.
 *   - `AFFILIATED_WITH`: Researcher or AuthorMention -> Institution; the asserting author mention;
 *     no property.
 *   - `HAS_TOPIC`: Work -> Topic; the asserting member work; no property.
 *   - `DERIVED_FROM` and `SUPPORTED_BY` are runtime edges, never in the canonical tables.
 */
object CanonicalGraph {
  import GraphPropertyKind.*

  private def req(key: String, kind: GraphPropertyKind) = GraphPropertySpec(key, true, kind)
  private def opt(key: String, kind: GraphPropertyKind) = GraphPropertySpec(key, false, kind)

  val NodeLabels: Vector[NodeLabel] = Vector(
    NodeLabel.Researcher,
    NodeLabel.AuthorMention,
    NodeLabel.Work,
    NodeLabel.Venue,
    NodeLabel.Institution,
    NodeLabel.Topic
  )

  val EdgeLabels: Vector[EdgeLabel] = Vector(
    EdgeLabel.Authored,
    EdgeLabel.ResolvesTo,
    EdgeLabel.CoauthoredWith,
    EdgeLabel.PublishedIn,
    EdgeLabel.AffiliatedWith,
    EdgeLabel.HasTopic
  )

  /** Graph eligibility of a canonical work node (a representative is never member-of-counted). */
  val WorkEligibilities: Vector[String] =
    Vector(GraphEligibility.Counted.tag, GraphEligibility.EnrichmentOnly.tag)

  /** Node property profiles, keys ascending. */
  val NodeProfiles: Map[NodeLabel, Vector[GraphPropertySpec]] = Map(
    NodeLabel.Researcher -> Vector(
      req("anchorNamespace", Token),
      req("identityStatus", OneOf(IdentityStatus.values.toVector.map(_.tag))),
      req("memberCount", Count)
    ),
    NodeLabel.AuthorMention -> Vector(
      req("assignmentStatus", OneOf(AssignmentStatus.values.toVector.map(_.tag))),
      req("position", Ordinal),
      req("workId", WorkRef)
    ),
    NodeLabel.Work -> Vector(
      req("graphEligibility", OneOf(WorkEligibilities)),
      req("groupSize", Count),
      opt("year", Year)
    ),
    NodeLabel.Venue -> Vector(req("name", Text)),
    NodeLabel.Institution -> Vector(req("name", Text)),
    NodeLabel.Topic -> Vector(req("name", Text))
  )

  /** Edge property profiles, keys ascending. */
  val EdgeProfiles: Map[EdgeLabel, Vector[GraphPropertySpec]] = Map(
    EdgeLabel.Authored -> Vector(req("position", Ordinal)),
    EdgeLabel.ResolvesTo -> Vector(
      req(
        "assignmentStatus",
        OneOf(Vector(AssignmentStatus.Resolved.tag, AssignmentStatus.Singleton.tag))
      )
    ),
    EdgeLabel.CoauthoredWith -> Vector(
      req("firstCollaborationYear", Year),
      req("jointPublicationCount", Count),
      req("lastCollaborationYear", Year),
      req("windowEndYear", Year),
      req("windowStartYear", Year)
    ),
    EdgeLabel.PublishedIn -> Vector.empty,
    EdgeLabel.AffiliatedWith -> Vector.empty,
    EdgeLabel.HasTopic -> Vector.empty
  )

  /** Allowed (from, to) node labels of each canonical edge class. */
  val Endpoints: Map[EdgeLabel, (Set[NodeLabel], Set[NodeLabel])] = Map(
    EdgeLabel.Authored -> (
      Set(NodeLabel.Researcher, NodeLabel.AuthorMention),
      Set(
        NodeLabel.Work
      )
    ),
    EdgeLabel.ResolvesTo -> (Set(NodeLabel.AuthorMention), Set(NodeLabel.Researcher)),
    EdgeLabel.CoauthoredWith -> (Set(NodeLabel.Researcher), Set(NodeLabel.Researcher)),
    EdgeLabel.PublishedIn -> (Set(NodeLabel.Work), Set(NodeLabel.Venue)),
    EdgeLabel.AffiliatedWith -> (
      Set(NodeLabel.Researcher, NodeLabel.AuthorMention),
      Set(
        NodeLabel.Institution
      )
    ),
    EdgeLabel.HasTopic -> (Set(NodeLabel.Work), Set(NodeLabel.Topic))
  )

  /** ID kind of each canonical edge class's `sourceEvidence`. */
  val SourceEvidenceKind: Map[EdgeLabel, IdKind] = Map(
    EdgeLabel.Authored -> IdKind.Mention,
    EdgeLabel.ResolvesTo -> IdKind.Mention,
    EdgeLabel.CoauthoredWith -> IdKind.CoauthorEdge,
    EdgeLabel.PublishedIn -> IdKind.Work,
    EdgeLabel.AffiliatedWith -> IdKind.Mention,
    EdgeLabel.HasTopic -> IdKind.Work
  )

  /** Property-map errors against a profile: missing required, unknown keys, value rules. */
  def checkProperties(
      field: String,
      profile: Vector[GraphPropertySpec],
      props: SortedMap[String, String]
  ): Vector[ValidationError] =
    if (props == null) Vector(ValidationError.MissingField(field))
    else {
      val known = profile.map(s => s.key -> s).toMap
      all(
        profile.filter(s => s.required && !props.contains(s.key)).map { s =>
          ValidationError.MissingField(at(field, s.key))
        },
        props.toVector.flatMap { case (k, v) =>
          known.get(k) match {
            case None =>
              Vector(
                ValidationError.InvalidValue(at(field, k), "not a property of this label's profile")
              )
            case Some(s) =>
              s.kind.problem(v).toVector.map(why => ValidationError.InvalidValue(at(field, k), why))
          }
        }
      )
    }

  private[core] def intOf(props: SortedMap[String, String], k: String): Option[Int] =
    Option(props).flatMap(_.get(k)).flatMap(_.toIntOption)

  // ---------------------------------------------------------------- the executable definition

  /**
   * The canonical tables an analysis run must publish for `input` (the executable definition of
   * course item 1, as [[AnalysisValidation.selectShortestPaths]] is for paths): every node with its
   * label and properties, every edge with its endpoints, evidence and properties. Lineage and
   * provenance are not part of it (checked by the record contracts and
   * [[AnalysisValidation.checkRunChain]]).
   *
   * Rules (all over the grouping run of `input` and the curated records it was decided on):
   *   - `Researcher`: one node per researcher ID of the identified researcher assignments (mentions
   *     and source author profiles); `identityStatus` resolved, or singleton /
   *     singleton-t1-anchored by [[ResearcherAssignment.requiresT1Anchor]]; `memberCount` =
   *     `groupSize`. The members of one ID must agree on status, anchor namespace and group size.
   *   - `Work`: one node per representative work assignment (counted or enrichment-only); `year`
   *     from its curated work record when it has one.
   *   - `AuthorMention`: one node per mention assignment whose work is a representative work, in
   *     every status. Unresolved mentions are preserved as nodes and are never merged.
   *   - `RESOLVES_TO`: identified mention -> its researcher. An unresolved mention has none.
   *   - `AUTHORED`: researcher -> work for every authorship record (identified mention on a
   *     representative, `sourceEvidence` = the mention); unresolved mention -> its work for every
   *     unresolved mention node. So every mention on a representative work has exactly one of
   *     RESOLVES_TO (identified) or AUTHORED (unresolved).
   *   - `PUBLISHED_IN` / `HAS_TOPIC`: canonical work -> each venue / topic asserted by any of its
   *     member works (the representative and its copies) that has a curated Venue / Topic record;
   *     one edge per (work, venue or topic), `sourceEvidence` = the smallest asserting member work
   *     ID (UTF-8 bytes).
   *   - `AFFILIATED_WITH`: for every curated author mention of any work and every distinct
   *     affiliation with a curated Institution record: from the mention's researcher when it is
   *     identified, from the mention itself when it is unresolved and on a representative work,
   *     none when it is unresolved on a non-representative copy; `sourceEvidence` = the mention.
   *   - `Venue`, `Institution`, `Topic`: one node per distinct curated record ID; records of one ID
   *     must agree on `name`.
   *   - `COAUTHORED_WITH`: one edge per [[CoauthorEdgeV2]] of the run (per window).
   *
   * A venue, topic or institution reference without a curated record is not an edge (it would be
   * dangling); the number of such references is reported in [[ExpectedGraph.unmatched]].
   *
   * Refuses (Left) an inconsistent input: duplicate assignments or work records, a representative
   * without a curated work record, an authorship record on a non-representative work or that
   * disagrees with its mention's assignment, a curated mention without an assignment, and
   * disagreeing source names.
   */
  def expected(
      input: CanonicalGraphInput,
      coauthorEdges: Vector[CoauthorEdgeV2]
  ): Either[Vector[ValidationError], ExpectedGraph] =
    if (
      input == null || Vector(
        input.works,
        input.researchers,
        input.authorships,
        input.sourceWorks,
        input.mentions,
        input.venues,
        input.institutions,
        input.topics
      ).exists(x => x == null || x.exists(_ == null)) || coauthorEdges == null ||
      coauthorEdges.exists(_ == null)
    ) Left(Vector(ValidationError.MissingField("canonicalGraphInput")))
    else build(input, coauthorEdges)

  private def build(
      input: CanonicalGraphInput,
      coauthorEdges: Vector[CoauthorEdgeV2]
  ): Either[Vector[ValidationError], ExpectedGraph] = {
    val errors = mutable.ArrayBuffer.empty[ValidationError]
    def bad(field: String, why: String): Unit = errors += ValidationError.InvalidValue(field, why)
    def dup(field: String, keys: Vector[String]): Unit =
      DatasetRules.duplicates(field, keys).foreach(errors += _)

    dup("works.workId", input.works.map(_.workId))
    dup("researchers.memberId", input.researchers.map(_.memberId))
    dup("sourceWorks.workId", input.sourceWorks.map(_.workId))
    dup("mentions.mentionId", input.mentions.map(_.mentionId))
    dup("authorships.mentionId", input.authorships.map(_.mentionId))

    val representatives = input.works.filter(_.isRepresentative)
    val repIds = representatives.map(_.workId).toSet
    val sourceWork = input.sourceWorks.map(w => w.workId -> w).toMap
    val assignmentOf = input.researchers.map(r => r.memberId -> r).toMap
    val members = input.works.groupBy(_.canonicalWorkId)

    val nodes = mutable.ArrayBuffer.empty[ExpectedNode]
    val edges = mutable.ArrayBuffer.empty[ExpectedEdge]
    def node(id: String, label: NodeLabel, props: (String, String)*): Unit =
      nodes += ExpectedNode(id, label, SortedMap(props*))
    def edge(t: EdgeLabel, from: String, to: String, ev: String, props: (String, String)*): Unit =
      edges += ExpectedEdge(t, from, to, ev, SortedMap(props*))

    // Researchers.
    input.researchers
      .filter(r => r.status != null && r.status.identified && r.researcherId.isDefined)
      .groupBy(_.researcherId.get)
      .toVector
      .sortWith((a, b) => Canonical.compareUtf8(a._1, b._1) < 0)
      .foreach { case (rid, ms) =>
        if (ms.map(m => (m.status, m.anchorNamespace, m.groupSize)).distinct.size != 1)
          bad(
            s"researchers[$rid]",
            "the members of one researcher ID disagree on status, anchor or group size"
          )
        else {
          val h = ms.head
          val status =
            if (h.status == AssignmentStatus.Resolved) IdentityStatus.Resolved
            else if (ms.exists(ResearcherAssignment.requiresT1Anchor))
              IdentityStatus.SingletonT1Anchored
            else IdentityStatus.Singleton
          node(
            rid,
            NodeLabel.Researcher,
            "anchorNamespace" -> h.anchorNamespace.getOrElse(""),
            "identityStatus" -> status.tag,
            "memberCount" -> h.groupSize.toString
          )
        }
      }

    // Works.
    representatives.foreach { w =>
      sourceWork.get(w.workId) match {
        case None =>
          bad(s"sourceWorks", s"representative ${w.workId} has no curated work record")
        case Some(s) =>
          node(
            w.workId,
            NodeLabel.Work,
            (Vector(
              "graphEligibility" -> w.graphEligibility.tag,
              "groupSize" -> w.groupSize.toString
            ) ++ Option(s.year).flatten.map(y => "year" -> y.toString))*
          )
      }
    }

    // Mentions, RESOLVES_TO and unresolved AUTHORED.
    input.researchers
      .filter(r => ResearcherAssignment.isMention(r) && r.workId.exists(repIds.contains))
      .foreach { m =>
        val w = m.workId.get
        val pos = m.position.getOrElse(0).toString
        node(
          m.memberId,
          NodeLabel.AuthorMention,
          "assignmentStatus" -> m.status.tag,
          "position" -> pos,
          "workId" -> w
        )
        if (m.status.identified)
          edge(
            EdgeLabel.ResolvesTo,
            m.memberId,
            m.researcherId.getOrElse(""),
            m.memberId,
            "assignmentStatus" -> m.status.tag
          )
        else edge(EdgeLabel.Authored, m.memberId, w, m.memberId, "position" -> pos)
      }

    // Researcher AUTHORED, from the authorship records.
    input.authorships.foreach { a =>
      val f = s"authorships[${a.mentionId}]"
      if (!repIds.contains(a.workId))
        bad(f, s"${a.workId} is not a representative work (one author list per canonical work)")
      else
        assignmentOf.get(a.mentionId) match {
          case None => bad(f, "the mention has no researcher assignment")
          case Some(r) if r.researcherId != Some(a.researcherId) || !r.status.identified =>
            bad(f, "disagrees with the mention's researcher assignment")
          case Some(_) =>
            edge(
              EdgeLabel.Authored,
              a.researcherId,
              a.workId,
              a.mentionId,
              "position" -> a.position.toString
            )
        }
    }

    // Venue, institution and topic nodes.
    def sourceNodes(label: NodeLabel, records: Vector[(String, String)]): Set[String] = {
      records
        .groupBy(_._1)
        .toVector
        .sortWith((a, b) => Canonical.compareUtf8(a._1, b._1) < 0)
        .foreach { case (id, rs) =>
          if (rs.map(_._2).distinct.size != 1)
            bad(s"${label.tag}[$id]", "the curated records of one ID disagree on name")
          else node(id, label, "name" -> rs.head._2)
        }
      records.map(_._1).toSet
    }
    val venues = sourceNodes(NodeLabel.Venue, input.venues.map(v => v.venueId -> v.name))
    val institutions =
      sourceNodes(NodeLabel.Institution, input.institutions.map(i => i.institutionId -> i.name))
    val topics = sourceNodes(NodeLabel.Topic, input.topics.map(t => t.topicId -> t.name))

    // PUBLISHED_IN and HAS_TOPIC from the member works of each canonical work.
    var unmatchedVenues = 0L
    var unmatchedTopics = 0L
    var unmatchedInstitutions = 0L
    representatives.foreach { r =>
      val ws = members.getOrElse(r.workId, Vector.empty).flatMap(m => sourceWork.get(m.workId))
      def asserted(of: WorkRecord => Vector[String]): Vector[(String, String)] =
        ws.flatMap(w => of(w).map(_ -> w.workId))
          .groupBy(_._1)
          .toVector
          .map { case (id, xs) =>
            id -> xs.map(_._2).reduce((a, b) => if (Canonical.compareUtf8(a, b) <= 0) a else b)
          }
      asserted(w => Option(w.venueId).flatten.toVector).foreach { case (v, by) =>
        if (venues.contains(v)) edge(EdgeLabel.PublishedIn, r.workId, v, by)
        else unmatchedVenues += 1
      }
      asserted(w => Option(w.topicIds).getOrElse(Vector.empty)).foreach { case (t, by) =>
        if (topics.contains(t)) edge(EdgeLabel.HasTopic, r.workId, t, by)
        else unmatchedTopics += 1
      }
    }

    // AFFILIATED_WITH from every curated mention.
    input.mentions.foreach { m =>
      assignmentOf.get(m.mentionId) match {
        case None => bad(s"mentions[${m.mentionId}]", "the mention has no researcher assignment")
        case Some(a) =>
          val from =
            if (a.status.identified) a.researcherId
            else if (repIds.contains(m.workId)) Some(m.mentionId)
            else None
          from.foreach { f =>
            Option(m.affiliationIds).getOrElse(Vector.empty).distinct.foreach { i =>
              if (institutions.contains(i)) edge(EdgeLabel.AffiliatedWith, f, i, m.mentionId)
              else unmatchedInstitutions += 1
            }
          }
      }
    }

    // COAUTHORED_WITH, one per coauthor edge of the run.
    coauthorEdges.foreach { e =>
      edge(
        EdgeLabel.CoauthoredWith,
        e.researcherA,
        e.researcherB,
        e.edgeId,
        "firstCollaborationYear" -> e.firstCollaborationYear.toString,
        "jointPublicationCount" -> e.jointPublicationCount.toString,
        "lastCollaborationYear" -> e.lastCollaborationYear.toString,
        "windowEndYear" -> e.window.endYear.toString,
        "windowStartYear" -> e.window.startYear.toString
      )
    }

    if (errors.nonEmpty) Left(errors.toVector)
    else
      Right(
        ExpectedGraph(
          nodes.toVector.sortWith((a, b) => Canonical.compareUtf8(a.nodeId, b.nodeId) < 0),
          edges.toVector.sortWith((a, b) => ExpectedEdge.less(a, b)),
          UnmatchedReferences(unmatchedVenues, unmatchedTopics, unmatchedInstitutions)
        )
      )
  }
}

/**
 * The curated and grouping-run records the canonical tables are built from (validator input of
 * [[CanonicalGraphValidation.checkTables]] and input of [[CanonicalGraph.expected]]). Each dataset
 * is complete for the run and decoded through [[Json.decode]].
 *
 * @param works
 *   every work assignment of the grouping run
 * @param researchers
 *   every researcher assignment of the grouping run (mentions and source author profiles)
 * @param authorships
 *   every `authorship-record.v2` of the grouping run
 * @param sourceWorks
 *   every curated work record the decision run was decided on
 * @param mentions
 *   every curated author mention (the affiliations source)
 * @param venues
 *   every curated venue record
 * @param institutions
 *   every curated institution record
 * @param topics
 *   every curated topic record
 */
final case class CanonicalGraphInput(
    works: Vector[WorkAssignment],
    researchers: Vector[ResearcherAssignment],
    authorships: Vector[AuthorshipRecordV2],
    sourceWorks: Vector[WorkRecord],
    mentions: Vector[AuthorMention],
    venues: Vector[Venue],
    institutions: Vector[Institution],
    topics: Vector[Topic]
)

/** One node of [[CanonicalGraph.expected]]. */
final case class ExpectedNode(
    nodeId: String,
    label: NodeLabel,
    properties: SortedMap[String, String]
)

/** One edge of [[CanonicalGraph.expected]]; the key is (type, from, to, sourceEvidence). */
final case class ExpectedEdge(
    edgeType: EdgeLabel,
    fromId: String,
    toId: String,
    sourceEvidence: String,
    properties: SortedMap[String, String]
) {
  def key: (String, String, String, String) = (edgeType.tag, fromId, toId, sourceEvidence)
}

object ExpectedEdge {
  private[core] def less(a: ExpectedEdge, b: ExpectedEdge): Boolean = {
    val ka = a.key
    val kb = b.key
    Vector(
      Canonical.compareUtf8(ka._1, kb._1),
      Canonical.compareUtf8(ka._2, kb._2),
      Canonical.compareUtf8(ka._3, kb._3),
      Canonical.compareUtf8(ka._4, kb._4)
    ).find(_ != 0).exists(_ < 0)
  }
}

/** Venue, topic and institution references without a curated record (never an edge). */
final case class UnmatchedReferences(venues: Long, topics: Long, institutions: Long)

/** The canonical tables of one run: nodes by ID, edges by key, and the unmatched references. */
final case class ExpectedGraph(
    nodes: Vector[ExpectedNode],
    edges: Vector[ExpectedEdge],
    unmatched: UnmatchedReferences
)

// ------------------------------------------------------------------ graph-node.v2

/**
 * One node of the canonical graph tables (schema `graph-node.v2`, DE-P6-03). The generic v1 shape
 * plus the analysis lineage; the label is one of [[CanonicalGraph.NodeLabels]] and the properties
 * follow its frozen profile ([[CanonicalGraph.NodeProfiles]]). Not windowed: one node per entity
 * per run.
 *
 * @param provenance
 *   `scholarchain-derived`; `sourceRecordId` = `nodeId`; `runId` = the analysis run;
 *   `policyVersion` = the graph policy; no model or prompt
 */
@allowUnknownKeys(false)
final case class GraphNodeV2(
    schemaVersion: String,
    nodeId: String,
    label: NodeLabel,
    properties: SortedMap[String, String],
    lineage: AnalysisLineage,
    provenance: Provenance
) derives ReadWriter

object GraphNodeV2 {

  /** Builds and validates a node; `provenanceOf` receives the node ID. */
  def build(
      nodeId: String,
      label: NodeLabel,
      properties: SortedMap[String, String],
      lineage: AnalysisLineage,
      provenanceOf: String => Provenance
  ): Either[Vector[ValidationError], GraphNodeV2] = {
    val n = GraphNodeV2(
      SchemaVersions.GraphNodeV2,
      nodeId,
      label,
      properties,
      lineage,
      provenanceOf(nodeId)
    )
    val e = contract.validate(n)
    if (e.isEmpty) Right(n) else Left(e)
  }

  given contract: RecordContract[GraphNodeV2] =
    new RecordContract[GraphNodeV2]("GraphNodeV2", SchemaVersions.GraphNodeV2, true) {
      def schemaVersionOf(t: GraphNodeV2): String = t.schemaVersion
      def provenanceOf(t: GraphNodeV2): Provenance = t.provenance
      protected def fields(t: GraphNodeV2, p: String): Vector[ValidationError] = {
        val profile = Option(t.label).flatMap(CanonicalGraph.NodeProfiles.get)
        val props = Option(t.properties).getOrElse(SortedMap.empty[String, String])
        val propErrors =
          profile.toVector.flatMap(
            CanonicalGraph.checkProperties(at(p, "properties"), _, t.properties)
          )
        all(
          when(t.label == null)(ValidationError.MissingField(at(p, "label"))),
          when(t.label != null && profile.isEmpty)(
            ValidationError.InvalidValue(
              at(p, "label"),
              s"${t.label.tag} is not a canonical table label (TextChunk and DatasetSnapshot are runtime nodes)"
            )
          ),
          profile.toVector.flatMap(_ =>
            StableId.check(GraphNode.IdKindOf(t.label), t.nodeId, at(p, "nodeId"))
          ),
          propErrors,
          if (propErrors.nonEmpty) Vector.empty
          else
            t.label match {
              case NodeLabel.Researcher =>
                val status = props.get("identityStatus")
                val count = CanonicalGraph.intOf(props, "memberCount")
                all(
                  when(status.contains(IdentityStatus.Resolved.tag) && count.exists(_ < 2))(
                    ValidationError.InvalidValue(
                      at(p, "properties.memberCount"),
                      "resolved requires a group of >= 2"
                    )
                  ),
                  when(!status.contains(IdentityStatus.Resolved.tag) && count.exists(_ != 1))(
                    ValidationError
                      .InvalidValue(at(p, "properties.memberCount"), "a singleton has one member")
                  ),
                  when(
                    status.contains(IdentityStatus.SingletonT1Anchored.tag) &&
                      !props
                        .get("anchorNamespace")
                        .exists(ResearcherAssignment.T1AnchorNamespaces.contains)
                  )(
                    ValidationError.InvalidValue(
                      at(p, "properties.anchorNamespace"),
                      "a t1-anchored singleton is anchored on orcid or dblp-pid (decision D1)"
                    )
                  )
                )
              case NodeLabel.AuthorMention =>
                (props.get("workId"), CanonicalGraph.intOf(props, "position")) match {
                  case (Some(w), Some(n)) =>
                    MentionId.of(w, n) match {
                      case Right(expected) =>
                        when(expected != t.nodeId)(
                          ValidationError.InvalidValue(
                            at(p, "nodeId"),
                            s"is not the mention at position $n of $w (expected $expected)"
                          )
                        )
                      case Left(_) => Vector.empty
                    }
                  case _ => Vector.empty
                }
              case _ => Vector.empty
            },
          AnalysisLineage.check(at(p, "lineage"), t.lineage),
          AnalysisRules.provenance(p, t.nodeId, t.provenance, t.lineage)
        )
      }
    }
}

// ------------------------------------------------------------------ graph-edge.v2

/**
 * One edge of the canonical graph tables (schema `graph-edge.v2`, DE-P6-03). The generic v1 shape
 * plus the analysis lineage; the class is one of [[CanonicalGraph.EdgeLabels]], endpoints and
 * evidence follow [[CanonicalGraph.Endpoints]] and [[CanonicalGraph.SourceEvidenceKind]], and the
 * properties its frozen profile.
 *
 * `edgeId` = [[GraphEdgeId.of]](edgeType, fromId, toId, provenance.policyVersion, sourceEvidence),
 * the unchanged v1 formula. `COAUTHORED_WITH` is stored in canonical order and its `sourceEvidence`
 * must equal [[CoauthorEdgeId.of]](pair, window from the properties, graph policy). For an
 * AuthorMention endpoint of `AUTHORED` and for `RESOLVES_TO`, `sourceEvidence` is the mention
 * itself (`fromId`).
 *
 * @param provenance
 *   `scholarchain-derived`; `sourceRecordId` = `edgeId`; `runId` = the analysis run;
 *   `policyVersion` = the graph policy; no model or prompt
 */
@allowUnknownKeys(false)
final case class GraphEdgeV2(
    schemaVersion: String,
    edgeId: String,
    fromId: String,
    edgeType: EdgeLabel,
    toId: String,
    sourceEvidence: String,
    properties: SortedMap[String, String],
    lineage: AnalysisLineage,
    provenance: Provenance
) derives ReadWriter

object GraphEdgeV2 {

  /**
   * Builds and validates an edge: puts undirected endpoints in canonical order and computes the
   * edge ID with `graphPolicyVersion`; `provenanceOf` receives the edge ID.
   */
  def build(
      edgeType: EdgeLabel,
      fromId: String,
      toId: String,
      sourceEvidence: String,
      properties: SortedMap[String, String],
      lineage: AnalysisLineage,
      graphPolicyVersion: String,
      provenanceOf: String => Provenance
  ): Either[Vector[ValidationError], GraphEdgeV2] = {
    val ends =
      if (edgeType != null && edgeType.undirected)
        CanonicalPair.of(fromId, toId, "fromId,toId").map(c => (c.first, c.second))
      else Right((fromId, toId))
    val built = for {
      e <- ends
      id <- GraphEdgeId.of(edgeType, e._1, e._2, graphPolicyVersion, sourceEvidence)
    } yield GraphEdgeV2(
      SchemaVersions.GraphEdgeV2,
      id,
      e._1,
      edgeType,
      e._2,
      sourceEvidence,
      properties,
      lineage,
      provenanceOf(id)
    )
    built match {
      case Left(e) => Left(Vector(e))
      case Right(edge) =>
        val errors = contract.validate(edge)
        if (errors.isEmpty) Right(edge) else Left(errors)
    }
  }

  given contract: RecordContract[GraphEdgeV2] =
    new RecordContract[GraphEdgeV2]("GraphEdgeV2", SchemaVersions.GraphEdgeV2, true) {
      def schemaVersionOf(t: GraphEdgeV2): String = t.schemaVersion
      def provenanceOf(t: GraphEdgeV2): Provenance = t.provenance
      protected def fields(t: GraphEdgeV2, p: String): Vector[ValidationError] =
        if (t.edgeType == null) Vector(ValidationError.MissingField(at(p, "edgeType")))
        else if (!CanonicalGraph.EdgeProfiles.contains(t.edgeType))
          Vector(
            ValidationError.InvalidValue(
              at(p, "edgeType"),
              s"${t.edgeType.tag} is not a canonical table edge (DERIVED_FROM and SUPPORTED_BY are runtime edges)"
            )
          )
        else {
          val (fromLabels, toLabels) = CanonicalGraph.Endpoints(t.edgeType)
          val endpointErrors =
            StableId.checkOneOf(fromLabels.map(GraphNode.IdKindOf), t.fromId, at(p, "fromId")) ++
              StableId.checkOneOf(toLabels.map(GraphNode.IdKindOf), t.toId, at(p, "toId"))
          val policy = AnalysisRules.policyOf(t.provenance)
          val props = Option(t.properties).getOrElse(SortedMap.empty[String, String])
          val propErrors = CanonicalGraph.checkProperties(
            at(p, "properties"),
            CanonicalGraph.EdgeProfiles(t.edgeType),
            t.properties
          )
          val evidenceErrors = StableId.check(
            CanonicalGraph.SourceEvidenceKind(t.edgeType),
            t.sourceEvidence,
            at(p, "sourceEvidence")
          )
          val fromMention = StableId.prefixKind(t.fromId).contains(IdKind.Mention)
          all(
            endpointErrors,
            if (t.edgeType.undirected)
              CanonicalPair.checkStored(at(p, "fromId,toId"), t.fromId, t.toId)
            else
              when(t.fromId != null && t.fromId == t.toId)(
                ValidationError.SelfPair(at(p, "fromId,toId"), t.fromId)
              ),
            evidenceErrors,
            when(
              evidenceErrors.isEmpty && (t.edgeType == EdgeLabel.ResolvesTo ||
                (t.edgeType == EdgeLabel.Authored && fromMention)) && t.sourceEvidence != t.fromId
            )(
              ValidationError.InvalidValue(
                at(p, "sourceEvidence"),
                "for an AuthorMention endpoint the evidence is the mention itself (fromId)"
              )
            ),
            propErrors,
            if (t.edgeType != EdgeLabel.CoauthoredWith || propErrors.nonEmpty) Vector.empty
            else {
              val first = CanonicalGraph.intOf(props, "firstCollaborationYear").get
              val last = CanonicalGraph.intOf(props, "lastCollaborationYear").get
              val start = CanonicalGraph.intOf(props, "windowStartYear").get
              val end = CanonicalGraph.intOf(props, "windowEndYear").get
              TimeWindow.of(start, end) match {
                case Left(e) => Vector(e)
                case Right(w) =>
                  all(
                    when(first > last || !w.contains(first) || !w.contains(last))(
                      ValidationError.InvalidValue(
                        at(p, "properties"),
                        "collaboration years must be ordered and inside the window"
                      )
                    ),
                    (CanonicalPair.of(t.fromId, t.toId), policy) match {
                      case (Right(pair), Some(pv))
                          if endpointErrors.isEmpty && evidenceErrors.isEmpty =>
                        AnalysisRules.formula(
                          at(p, "sourceEvidence"),
                          t.sourceEvidence,
                          CoauthorEdgeId.of(pair, w, pv)
                        )
                      case _ => Vector.empty
                    }
                  )
              }
            },
            StableId.check(IdKind.GraphEdge, t.edgeId, at(p, "edgeId")),
            policy match {
              case Some(pv) if endpointErrors.isEmpty && evidenceErrors.isEmpty =>
                AnalysisRules.formula(
                  at(p, "edgeId"),
                  t.edgeId,
                  GraphEdgeId.of(t.edgeType, t.fromId, t.toId, pv, t.sourceEvidence)
                )
              case _ => Vector.empty
            },
            AnalysisLineage.check(at(p, "lineage"), t.lineage),
            AnalysisRules.provenance(p, t.edgeId, t.provenance, t.lineage)
          )
        }
    }
}

// ------------------------------------------------------------------ dataset rules

/** Dataset-level helpers shared by the DE-P6-03 validators. */
private[core] object DatasetRules {

  def sortedUtf8(xs: Iterable[String]): Vector[String] =
    xs.toVector.sortWith((a, b) => Canonical.compareUtf8(a, b) < 0)

  def records[A](label: String, xs: Vector[A])(using
      c: RecordContract[A]
  ): Vector[ValidationError] =
    if (xs == null || xs.exists(_ == null)) Vector(ValidationError.MissingField(label))
    else xs.zipWithIndex.flatMap { case (x, i) => c.validateAt(x, s"$label[$i]") }

  def duplicates(field: String, keys: Vector[String]): Vector[ValidationError] =
    keys.groupBy(identity).toVector.filter(_._2.size > 1).sortBy(_._1).map { case (k, same) =>
      ValidationError.InvalidValue(field, s"$k appears in ${same.size} records; one record per key")
    }

  def wkey(w: TimeWindow): String = s"${w.startYear}-${w.endYear}"
}

// ------------------------------------------------------------------ validation

/**
 * Validation of the canonical graph tables (course item 1). [[checkStructure]] needs only the
 * tables; [[checkTables]] also recomputes them exactly from the run's inputs
 * ([[CanonicalGraph.expected]]).
 */
object CanonicalGraphValidation {
  import DatasetRules.*

  private def invalid(field: String, reason: String) = ValidationError.InvalidValue(field, reason)

  /**
   * Structural rules of the tables alone (no input needed):
   *   - every record passes its contract; node IDs and edge IDs are unique;
   *   - no dangling endpoint: every edge's `fromId` and `toId` is a node of the tables, with a
   *     label the edge class allows ([[CanonicalGraph.Endpoints]]);
   *   - unresolved mentions are preserved without a forced merge: an AuthorMention node with
   *     `assignmentStatus` `unresolved` has exactly one AUTHORED edge, to its own `workId`, and no
   *     RESOLVES_TO edge;
   *   - an identified mention has exactly one RESOLVES_TO edge, no AUTHORED edge of its own, and
   *     its researcher has exactly one AUTHORED edge to the mention's work with the mention as
   *     evidence;
   *   - every researcher AUTHORED edge cites a mention node as its evidence.
   */
  def checkStructure(
      nodes: Vector[GraphNodeV2],
      edges: Vector[GraphEdgeV2]
  ): Vector[ValidationError] = {
    val rec = records("nodes", nodes) ++ records("graphEdges", edges)
    if (rec.nonEmpty) rec
    else {
      val byId = nodes.map(n => n.nodeId -> n).toMap
      val authoredBy = edges.filter(_.edgeType == EdgeLabel.Authored).groupBy(_.fromId)
      val resolvesBy = edges.filter(_.edgeType == EdgeLabel.ResolvesTo).groupBy(_.fromId)
      val authoredByEvidence =
        edges.filter(_.edgeType == EdgeLabel.Authored).groupBy(_.sourceEvidence)
      val dangling = edges.flatMap { e =>
        val f = s"graphEdges[${e.edgeId}]"
        val (fromLabels, toLabels) = CanonicalGraph.Endpoints(e.edgeType)
        def end(which: String, id: String, allowed: Set[NodeLabel]) = byId.get(id) match {
          case None =>
            Vector(invalid(f, s"dangling $which endpoint $id: no node of the canonical tables"))
          case Some(n) if !allowed.contains(n.label) =>
            Vector(
              invalid(
                f,
                s"$which endpoint $id is a ${n.label.tag} node; ${e.edgeType.tag} allows ${allowed.map(_.tag).toVector.sorted.mkString(", ")}"
              )
            )
          case _ => Vector.empty
        }
        end("from", e.fromId, fromLabels) ++ end("to", e.toId, toLabels)
      }
      val mentionRules = nodes.filter(_.label == NodeLabel.AuthorMention).flatMap { m =>
        val f = s"nodes[${m.nodeId}]"
        val own = authoredBy.getOrElse(m.nodeId, Vector.empty)
        val resolves = resolvesBy.getOrElse(m.nodeId, Vector.empty)
        val work = m.properties.get("workId")
        if (m.properties.get("assignmentStatus").contains(AssignmentStatus.Unresolved.tag))
          all(
            when(resolves.nonEmpty)(
              invalid(f, "an unresolved mention never has a RESOLVES_TO edge (no forced merge)")
            ),
            when(own.size != 1)(
              invalid(
                f,
                s"an unresolved mention keeps exactly one AUTHORED edge (found ${own.size})"
              )
            ),
            when(own.size == 1 && work != Some(own.head.toId))(
              invalid(f, "the unresolved mention's AUTHORED edge must point to its own work")
            )
          )
        else {
          val researcher = resolves.headOption.map(_.toId)
          val backing = authoredByEvidence
            .getOrElse(m.nodeId, Vector.empty)
            .filter(e => researcher.contains(e.fromId) && work.contains(e.toId))
          all(
            when(resolves.size != 1)(
              invalid(
                f,
                s"an identified mention has exactly one RESOLVES_TO edge (found ${resolves.size})"
              )
            ),
            when(own.nonEmpty)(
              invalid(f, "an identified mention's authorship is its researcher's AUTHORED edge")
            ),
            when(resolves.size == 1 && backing.size != 1)(
              invalid(
                f,
                s"its researcher has exactly one AUTHORED edge to the mention's work with the mention as evidence (found ${backing.size})"
              )
            )
          )
        }
      }
      val evidence = edges
        .filter(e =>
          e.edgeType == EdgeLabel.Authored && StableId
            .prefixKind(e.fromId)
            .contains(IdKind.Researcher)
        )
        .flatMap { e =>
          when(!byId.get(e.sourceEvidence).exists(_.label == NodeLabel.AuthorMention))(
            invalid(
              s"graphEdges[${e.edgeId}].sourceEvidence",
              s"${e.sourceEvidence} is not an AuthorMention node: every authorship cites its mention"
            )
          )
        }
      all(
        duplicates("nodes.nodeId", nodes.map(_.nodeId)),
        duplicates("graphEdges.edgeId", edges.map(_.edgeId)),
        dangling,
        mentionRules,
        evidence
      )
    }
  }

  /**
   * The tables are exactly [[CanonicalGraph.expected]] of the input and the run's coauthor edges
   * (no missing, unexpected or differing node or edge), pass [[checkStructure]], and carry the
   * configured graph policy.
   */
  def checkTables(
      nodes: Vector[GraphNodeV2],
      edges: Vector[GraphEdgeV2],
      input: CanonicalGraphInput,
      coauthorEdges: Vector[CoauthorEdgeV2],
      settings: AnalysisRunSettings
  ): Vector[ValidationError] = {
    val structure = checkStructure(nodes, edges)
    if (structure.nonEmpty) structure
    else
      CanonicalGraph.expected(input, coauthorEdges) match {
        case Left(e) => e
        case Right(g) =>
          val policy = all(
            nodes.flatMap(n =>
              when(AnalysisRules.policyOf(n.provenance) != Some(settings.graphPolicyVersion))(
                invalid(
                  s"nodes[${n.nodeId}].provenance.policyVersion",
                  s"must be ${settings.graphPolicyVersion}"
                )
              )
            ),
            edges.flatMap(e =>
              when(AnalysisRules.policyOf(e.provenance) != Some(settings.graphPolicyVersion))(
                invalid(
                  s"graphEdges[${e.edgeId}].provenance.policyVersion",
                  s"must be ${settings.graphPolicyVersion}"
                )
              )
            )
          )
          val expNodes = g.nodes.map(n => n.nodeId -> n).toMap
          val gotNodes = nodes.map(n => n.nodeId -> n).toMap
          val nodeDiff = all(
            g.nodes.filterNot(n => gotNodes.contains(n.nodeId)).map { n =>
              invalid("nodes", s"missing ${n.label.tag} node ${n.nodeId}")
            },
            sortedUtf8(gotNodes.keys).flatMap { id =>
              val n = gotNodes(id)
              expNodes.get(id) match {
                case None =>
                  Vector(invalid(s"nodes[$id]", "not a node of the canonical tables of the input"))
                case Some(x) =>
                  all(
                    when(x.label != n.label)(
                      invalid(s"nodes[$id].label", s"expected ${x.label.tag}")
                    ),
                    when(x.properties != n.properties)(
                      invalid(s"nodes[$id].properties", s"expected ${x.properties}")
                    )
                  )
              }
            }
          )
          def keyOf(e: GraphEdgeV2) = (e.edgeType.tag, e.fromId, e.toId, e.sourceEvidence)
          val expEdges = g.edges.map(e => e.key -> e).toMap
          val gotEdges = edges.map(e => keyOf(e) -> e).toMap
          val edgeDiff = all(
            g.edges.filterNot(e => gotEdges.contains(e.key)).map { e =>
              invalid(
                "graphEdges",
                s"missing ${e.edgeType.tag} edge ${e.fromId} -> ${e.toId} (evidence ${e.sourceEvidence})"
              )
            },
            edges.sortBy(_.edgeId).flatMap { e =>
              val f = s"graphEdges[${e.edgeId}]"
              expEdges.get(keyOf(e)) match {
                case None => Vector(invalid(f, "not an edge of the canonical tables of the input"))
                case Some(x) =>
                  when(x.properties != e.properties)(
                    invalid(s"$f.properties", s"expected ${x.properties}")
                  )
              }
            },
            duplicates(
              "graphEdges",
              edges.map(e => s"${e.edgeType.tag} ${e.fromId} ${e.toId} ${e.sourceEvidence}")
            )
          )
          all(policy, nodeDiff, edgeDiff)
      }
  }
}

// ------------------------------------------------------------------ Neptune CSV layout

/**
 * The Neptune bulk-load CSV layout of the canonical tables (Gremlin load data format; a
 * serialization contract only, versioned by `graph-node.v2` / `graph-edge.v2`; the loader is
 * storage-engineer's). One file per label: a header line, then one line per record, each line
 * ending in LF.
 *
 *   - Vertex columns: `~id`, `~label`, then the label's profile keys in ascending order as
 *     `<key>:<type>` (type `Int` for counts, ordinals and years, `String` otherwise), then
 *     `runId:String` (the analysis run, the provenance of every vertex).
 *   - Edge columns: `~id`, `~from`, `~to`, `~label`, the profile keys as above,
 *     `sourceEvidence:String`, `runId:String`.
 *   - An absent optional property is an empty field. A field is enclosed in double quotes, with
 *     each inner double quote doubled, when it contains a comma, a double quote, CR or LF, or
 *     begins or ends with a space; otherwise it is written as is.
 */
object NeptuneCsv {

  val RunIdColumn = "runId:String"
  val SourceEvidenceColumn = "sourceEvidence:String"

  private def profileColumns(profile: Vector[GraphPropertySpec]): Vector[String] =
    profile.sortBy(_.key).map(s => s"${s.key}:${s.kind.neptuneType}")

  def vertexColumns(label: NodeLabel): Vector[String] =
    Vector("~id", "~label") ++ profileColumns(CanonicalGraph.NodeProfiles(label)) :+ RunIdColumn

  def edgeColumns(label: EdgeLabel): Vector[String] =
    Vector("~id", "~from", "~to", "~label") ++
      profileColumns(CanonicalGraph.EdgeProfiles(label)) :+ SourceEvidenceColumn :+ RunIdColumn

  /** One CSV field as the layout writes it. */
  def field(s: String): String =
    if (
      s.exists(c => c == ',' || c == '"' || c == '\n' || c == '\r') ||
      s.startsWith(" ") || s.endsWith(" ")
    ) "\"" + s.replace("\"", "\"\"") + "\""
    else s

  private def line(fields: Vector[String]): String = fields.map(field).mkString(",") + "\n"

  def vertexRow(n: GraphNodeV2): String =
    line(
      Vector(n.nodeId, n.label.tag) ++
        CanonicalGraph
          .NodeProfiles(n.label)
          .sortBy(_.key)
          .map(s => n.properties.getOrElse(s.key, "")) :+
        n.provenance.runId
    )

  def edgeRow(e: GraphEdgeV2): String =
    line(
      Vector(e.edgeId, e.fromId, e.toId, e.edgeType.tag) ++
        CanonicalGraph
          .EdgeProfiles(e.edgeType)
          .sortBy(_.key)
          .map(s => e.properties.getOrElse(s.key, "")) :+
        e.sourceEvidence :+ e.provenance.runId
    )

  /** The whole vertex file of one label; refuses an invalid node or a node of another label. */
  def vertexFile(
      label: NodeLabel,
      nodes: Vector[GraphNodeV2]
  ): Either[Vector[ValidationError], String] =
    if (!CanonicalGraph.NodeProfiles.contains(label))
      Left(
        Vector(
          ValidationError.InvalidValue("label", s"${label.tag} is not a canonical table label")
        )
      )
    else {
      val errors = DatasetRules.records("nodes", nodes) ++
        nodes.filter(n => n != null && n.label != label).map { n =>
          ValidationError.InvalidValue(s"nodes[${n.nodeId}]", s"not a ${label.tag} node")
        }
      if (errors.nonEmpty) Left(errors)
      else Right(line(vertexColumns(label)) + nodes.map(vertexRow).mkString)
    }

  /** The whole edge file of one class; refuses an invalid edge or an edge of another class. */
  def edgeFile(
      label: EdgeLabel,
      edges: Vector[GraphEdgeV2]
  ): Either[Vector[ValidationError], String] =
    if (!CanonicalGraph.EdgeProfiles.contains(label))
      Left(
        Vector(ValidationError.InvalidValue("label", s"${label.tag} is not a canonical table edge"))
      )
    else {
      val errors = DatasetRules.records("graphEdges", edges) ++
        edges.filter(e => e != null && e.edgeType != label).map { e =>
          ValidationError.InvalidValue(s"graphEdges[${e.edgeId}]", s"not a ${label.tag} edge")
        }
      if (errors.nonEmpty) Left(errors)
      else Right(line(edgeColumns(label)) + edges.map(edgeRow).mkString)
    }
}
