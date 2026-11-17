package scholarchain.spark.graph

import scala.collection.immutable.SortedMap
import scala.reflect.ClassTag

import org.apache.spark.SparkContext
import org.apache.spark.rdd.{PairRDDFunctions, RDD}
import org.apache.spark.storage.StorageLevel

import scholarchain.core.*
import scholarchain.spark.input.VerifiedInput

/** The grouping-run and curated records the canonical tables are built from, distributed. */
final case class CanonicalInputs(
    works: RDD[WorkAssignment],
    researchers: RDD[ResearcherAssignment],
    authorships: RDD[AuthorshipRecordV2],
    sourceWorks: RDD[WorkRecord],
    mentions: RDD[AuthorMention],
    venues: RDD[Venue],
    institutions: RDD[Institution],
    topics: RDD[Topic]
)

/**
 * The canonical tables before publication: node and edge results (a Left is a refusal of the run)
 * in a stable order (nodes by ID, edges by edge ID), the references without a curated record, and
 * the intermediate RDDs the job persisted (the caller unpersists them after publication).
 */
final case class CanonicalTables(
    nodes: RDD[Either[String, GraphNodeV2]],
    edges: RDD[Either[String, GraphEdgeV2]],
    unmatched: UnmatchedReferences,
    retained: Vector[RDD[?]]
)

/**
 * The canonical Researcher, AuthorMention, Work, Venue, Institution and Topic tables and their
 * AUTHORED, RESOLVES_TO, COAUTHORED_WITH, PUBLISHED_IN, AFFILIATED_WITH and HAS_TOPIC edges (course
 * item 1; schemas `graph-node.v2`, `graph-edge.v2`), distributed (SP-P6-03 step 2B). The executable
 * definition is core `CanonicalGraph.expected` (CanonicalGraph.scala:242-505); every rule below is
 * that function's, applied with joins instead of maps, and `CanonicalGraphValidation.checkTables`
 * compares the published tables with it exactly (dev and sample runs, driver-side, in the tests).
 *
 *   - Researcher: one node per researcher ID of the identified assignments; the members must agree
 *     on (status, anchor namespace, group size); `identityStatus` resolved, singleton-t1-anchored
 *     when any member `requiresT1Anchor`, else singleton.
 *   - Work: one node per representative work assignment, `year` from its curated work record (a
 *     representative without one is refused).
 *   - AuthorMention: one node per mention assignment on a representative work, in every status.
 *     Unresolved mentions are preserved as their own nodes and never merged: an unresolved mention
 *     gets one AUTHORED edge to its work and no RESOLVES_TO; an identified mention gets RESOLVES_TO
 *     to its researcher, and its authorship is the researcher's AUTHORED edge from the authorship
 *     record (refused when the record is on a non-representative or disagrees with the mention's
 *     assignment).
 *   - PUBLISHED_IN / HAS_TOPIC: canonical work -> each venue / topic asserted by any member work
 *     (representative or copy) that has a curated record, one edge per (work, target), evidence the
 *     smallest asserting member work ID (UTF-8 bytes).
 *   - AFFILIATED_WITH: per curated mention and distinct affiliation with a curated institution,
 *     from the researcher (identified), the mention (unresolved, on a representative), or none
 *     (unresolved on a copy); evidence the mention.
 *   - Venue, Institution, Topic: one node per curated record ID (records of one ID must agree on
 *     the name). COAUTHORED_WITH: one edge per coauthor edge of the run.
 *   - A venue, topic or institution reference without a curated record is no edge (it would dangle)
 *     and is counted ([[CanonicalTables.unmatched]]).
 *
 * Provenance of every record: `scholarchain-derived`, the record's own ID, the DBLP snapshot, the
 * analysis run, the graph policy; `sourceSchemaVersion` is the record schema of the primary input
 * ([[NodeSources]], [[EdgeSources]]).
 *
 * Partitioning, shuffles and memory (P = `partitions`):
 *   - inputs are read as text with P minimum splits (the curated gzip parts are not splittable: one
 *     partition per part) and decoded per row through `Json.decode`; the decoded rows are
 *     re-counted against the part manifest (grouping) or the `EMITTED.<leaf>` counters (curated);
 *   - duplicate keys (work, member, curated work, curated mention and authorship IDs): one
 *     `reduceByKey` each, at most 20 keys to the driver;
 *   - Researcher nodes: `reduceByKey` by researcher ID (the value is the set of distinct (status,
 *     anchor, group size), bounded by the disagreement); Work nodes: `leftOuterJoin` of the
 *     representatives with the curated works; mention nodes and their edges: `join` of the mention
 *     assignments with the representatives on work ID; researcher AUTHORED: `leftOuterJoin` on work
 *     ID, then on mention ID with the assignments; member assertions: `join` of the work
 *     assignments with the curated works, `join` with the representatives on the canonical work,
 *     `reduceByKey` to the smallest asserting member per (work, target), `leftOuterJoin` with the
 *     target IDs; affiliations: `leftOuterJoin` on work ID and mention ID, then on institution ID;
 *     source nodes: `reduceByKey` by ID; every join is a hash join (no broadcast: every input grows
 *     with the data);
 *   - the unmatched counts are three actions on persisted assertion RDDs (one Long each to the
 *     driver); nodes are sorted by node ID and edges by edge ID (range-partitioning shuffles);
 *   - nothing per record reaches the driver.
 */
object CanonicalGraphJob {

  private def pr[K: ClassTag, V: ClassTag](r: RDD[(K, V)]): PairRDDFunctions[K, V] =
    RDD.rddToPairRDDFunctions(r)

  /** Record schema of the primary input of each node label (provenance). */
  val NodeSources: Map[NodeLabel, String] = Map(
    NodeLabel.Researcher -> SchemaVersions.ResearcherAssignment,
    NodeLabel.AuthorMention -> SchemaVersions.ResearcherAssignment,
    NodeLabel.Work -> SchemaVersions.WorkAssignment,
    NodeLabel.Venue -> SchemaVersions.Venue,
    NodeLabel.Institution -> SchemaVersions.Institution,
    NodeLabel.Topic -> SchemaVersions.Topic
  )

  /**
   * Record schema of the primary input of each edge class (provenance); AUTHORED from a researcher
   * comes from an authorship record, from an unresolved mention from its assignment.
   */
  val EdgeSources: Map[EdgeLabel, String] = Map(
    EdgeLabel.Authored -> SchemaVersions.AuthorshipRecordV2,
    EdgeLabel.ResolvesTo -> SchemaVersions.ResearcherAssignment,
    EdgeLabel.CoauthoredWith -> SchemaVersions.CoauthorEdgeV2,
    EdgeLabel.PublishedIn -> SchemaVersions.WorkRecord,
    EdgeLabel.AffiliatedWith -> SchemaVersions.AuthorMention,
    EdgeLabel.HasTopic -> SchemaVersions.WorkRecord
  )

  def provenance(ctx: EdgeContext, sourceSchema: String)(id: String): Provenance = Provenance(
    sourceDataset = SourceType.Derived,
    sourceRecordId = id,
    snapshot = ctx.snapshot,
    runId = ctx.analysisRunId,
    sourceSchemaVersion = sourceSchema,
    policyVersion = Some(ctx.graphPolicyVersion),
    modelVersion = None,
    promptVersion = None
  )

  // ---------------------------------------------------------------- decoders

  def decodeAuthorship(line: String): Either[String, AuthorshipRecordV2] =
    Json.decode[AuthorshipRecordV2](line).left.map(_.mkString("; "))
  def decodeSourceWork(line: String): Either[String, WorkRecord] =
    Json.decode[WorkRecord](line).left.map(_.mkString("; "))
  def decodeMention(line: String): Either[String, AuthorMention] =
    Json.decode[AuthorMention](line).left.map(_.mkString("; "))
  def decodeVenue(line: String): Either[String, Venue] =
    Json.decode[Venue](line).left.map(_.mkString("; "))
  def decodeInstitution(line: String): Either[String, Institution] =
    Json.decode[Institution](line).left.map(_.mkString("; "))
  def decodeTopic(line: String): Either[String, Topic] =
    Json.decode[Topic](line).left.map(_.mkString("; "))

  /**
   * Reads every input from the verified run directories (grouping parts from the part manifest,
   * curated parts from the verified listing), re-counting the decoded rows.
   */
  def inputs(
      sc: SparkContext,
      input: VerifiedInput,
      partitions: Int
  ): Either[Vector[String], CanonicalInputs] = {
    def grouping[T: ClassTag](label: String, schema: String, decode: String => Either[String, T]) =
      CoauthorEdgeJob.checked(
        label,
        CoauthorEdgeJob.lines(sc, input.grouping.partsOf(schema), partitions).map(decode),
        input.grouping.rowsOf(schema)
      )
    def curated[T: ClassTag](leaf: String, decode: String => Either[String, T]) =
      CoauthorEdgeJob.checked(
        s"curated $leaf",
        CoauthorEdgeJob.lines(sc, input.curatedPartsOf(leaf), partitions).map(decode),
        input.curatedRowsOf(leaf)
      )
    for {
      works <- grouping(
        "work assignments",
        SchemaVersions.WorkAssignment,
        CoauthorEdgeJob.decodeWork
      )
      researchers <- grouping(
        "researcher assignments",
        SchemaVersions.ResearcherAssignment,
        IdentityJob.decodeResearcher
      )
      authorships <- grouping(
        "authorship records",
        SchemaVersions.AuthorshipRecordV2,
        decodeAuthorship
      )
      sourceWorks <- curated("works", decodeSourceWork)
      mentions <- curated("mentions", decodeMention)
      venues <- curated("venues", decodeVenue)
      institutions <- curated("institutions", decodeInstitution)
      topics <- curated("topics", decodeTopic)
    } yield CanonicalInputs(
      works,
      researchers,
      authorships,
      sourceWorks,
      mentions,
      venues,
      institutions,
      topics
    )
  }

  // ---------------------------------------------------------------- record builders

  private def props(kv: Vector[(String, String)]): SortedMap[String, String] = SortedMap(kv*)

  def node(
      ctx: EdgeContext,
      id: String,
      label: NodeLabel,
      kv: Vector[(String, String)]
  ): Either[String, GraphNodeV2] =
    GraphNodeV2
      .build(id, label, props(kv), ctx.lineage, provenance(ctx, NodeSources(label)))
      .left
      .map(e => s"${label.tag} node $id: ${e.mkString("; ")}")

  def edge(
      ctx: EdgeContext,
      t: EdgeLabel,
      from: String,
      to: String,
      evidence: String,
      kv: Vector[(String, String)],
      sourceSchema: String
  ): Either[String, GraphEdgeV2] =
    GraphEdgeV2
      .build(
        t,
        from,
        to,
        evidence,
        props(kv),
        ctx.lineage,
        ctx.graphPolicyVersion,
        provenance(ctx, sourceSchema)
      )
      .left
      .map(e => s"${t.tag} edge $from -> $to (evidence $evidence): ${e.mkString("; ")}")

  /** A Researcher node from the members' distinct (status, anchor, group size) and any T1 rule. */
  def researcherNode(
      ctx: EdgeContext,
      rid: String,
      agreed: Set[(String, String, Int)],
      anyT1: Boolean
  ): Either[String, GraphNodeV2] =
    agreed.toVector match {
      case Vector((status, anchor, size)) =>
        val identity =
          if (status == AssignmentStatus.Resolved.tag) IdentityStatus.Resolved
          else if (anyT1) IdentityStatus.SingletonT1Anchored
          else IdentityStatus.Singleton
        node(
          ctx,
          rid,
          NodeLabel.Researcher,
          Vector(
            "anchorNamespace" -> anchor,
            "identityStatus" -> identity.tag,
            "memberCount" -> size.toString
          )
        )
      case _ =>
        Left(
          s"researchers[$rid]: the members of one researcher ID disagree on status, anchor or group size"
        )
    }

  def workNode(
      ctx: EdgeContext,
      w: WorkAssignment,
      source: Option[WorkRecord]
  ): Either[String, GraphNodeV2] =
    source match {
      case None => Left(s"sourceWorks: representative ${w.workId} has no curated work record")
      case Some(s) =>
        node(
          ctx,
          w.workId,
          NodeLabel.Work,
          Vector(
            "graphEligibility" -> w.graphEligibility.tag,
            "groupSize" -> w.groupSize.toString
          ) ++ Option(s.year).flatten.map(y => "year" -> y.toString)
        )
    }

  def mentionNode(ctx: EdgeContext, m: ResearcherAssignment): Either[String, GraphNodeV2] =
    node(
      ctx,
      m.memberId,
      NodeLabel.AuthorMention,
      Vector(
        "assignmentStatus" -> m.status.tag,
        "position" -> m.position.getOrElse(0).toString,
        "workId" -> m.workId.getOrElse("")
      )
    )

  /** RESOLVES_TO of an identified mention, AUTHORED of an unresolved one (never both). */
  def mentionEdge(ctx: EdgeContext, m: ResearcherAssignment): Either[String, GraphEdgeV2] =
    if (m.status.identified)
      edge(
        ctx,
        EdgeLabel.ResolvesTo,
        m.memberId,
        m.researcherId.getOrElse(""),
        m.memberId,
        Vector("assignmentStatus" -> m.status.tag),
        SchemaVersions.ResearcherAssignment
      )
    else
      edge(
        ctx,
        EdgeLabel.Authored,
        m.memberId,
        m.workId.getOrElse(""),
        m.memberId,
        Vector("position" -> m.position.getOrElse(0).toString),
        SchemaVersions.ResearcherAssignment
      )

  def researcherAuthored(
      ctx: EdgeContext,
      a: AuthorshipRecordV2,
      onRepresentative: Boolean,
      assignment: Option[ResearcherAssignment]
  ): Either[String, GraphEdgeV2] = {
    val f = s"authorships[${a.mentionId}]"
    if (!onRepresentative)
      Left(s"$f: ${a.workId} is not a representative work (one author list per canonical work)")
    else
      assignment match {
        case None => Left(s"$f: the mention has no researcher assignment")
        case Some(r) if r.researcherId != Some(a.researcherId) || !r.status.identified =>
          Left(s"$f: disagrees with the mention's researcher assignment")
        case Some(_) =>
          edge(
            ctx,
            EdgeLabel.Authored,
            a.researcherId,
            a.workId,
            a.mentionId,
            Vector("position" -> a.position.toString),
            SchemaVersions.AuthorshipRecordV2
          )
      }
  }

  def sourceNode(
      ctx: EdgeContext,
      label: NodeLabel,
      id: String,
      names: Set[String]
  ): Either[String, GraphNodeV2] =
    names.toVector match {
      case Vector(n) => node(ctx, id, label, Vector("name" -> n))
      case _ => Left(s"${label.tag}[$id]: the curated records of one ID disagree on name")
    }

  def coauthored(ctx: EdgeContext, e: CoauthorEdgeV2): Either[String, GraphEdgeV2] =
    edge(
      ctx,
      EdgeLabel.CoauthoredWith,
      e.researcherA,
      e.researcherB,
      e.edgeId,
      Vector(
        "firstCollaborationYear" -> e.firstCollaborationYear.toString,
        "jointPublicationCount" -> e.jointPublicationCount.toString,
        "lastCollaborationYear" -> e.lastCollaborationYear.toString,
        "windowEndYear" -> e.window.endYear.toString,
        "windowStartYear" -> e.window.startYear.toString
      ),
      SchemaVersions.CoauthorEdgeV2
    )

  /** From whom a curated mention's affiliation edge starts (expected's rule), or None. */
  def affiliationSource(
      m: AuthorMention,
      onRepresentative: Boolean,
      a: ResearcherAssignment
  ): Option[String] =
    if (a.status.identified) a.researcherId
    else if (onRepresentative) Some(m.mentionId)
    else None

  // ---------------------------------------------------------------- the job

  /** At most 20 keys that occur in more than one record, as refusal messages. */
  def duplicates(label: String, keys: RDD[String], partitions: Int): Vector[String] =
    pr(keys.map(k => (k, 1L)))
      .reduceByKey(_ + _, partitions)
      .filter(_._2 > 1L)
      .take(20)
      .toVector
      .map { case (k, n) => s"$label: $k appears in $n records; one record per key" }

  /**
   * Builds the canonical tables of `in` and the run's coauthor edges. Left with the duplicate-key
   * refusals (as `CanonicalGraph.expected`); every other refusal is a Left record.
   */
  def build(
      in: CanonicalInputs,
      coauthorEdges: RDD[CoauthorEdgeV2],
      ctx: EdgeContext,
      partitions: Int
  ): Either[Vector[String], CanonicalTables] = {
    val dups = duplicates("works.workId", in.works.map(_.workId), partitions) ++
      duplicates("researchers.memberId", in.researchers.map(_.memberId), partitions) ++
      duplicates("sourceWorks.workId", in.sourceWorks.map(_.workId), partitions) ++
      duplicates("mentions.mentionId", in.mentions.map(_.mentionId), partitions) ++
      duplicates("authorships.mentionId", in.authorships.map(_.mentionId), partitions)
    if (dups.nonEmpty) Left(dups) else Right(tables(in, coauthorEdges, ctx, partitions))
  }

  private def tables(
      in: CanonicalInputs,
      coauthorEdges: RDD[CoauthorEdgeV2],
      ctx: EdgeContext,
      partitions: Int
  ): CanonicalTables = {
    val p = partitions
    val reps: RDD[(String, WorkAssignment)] =
      in.works.filter(_.isRepresentative).map(w => (w.workId, w))
    val repKeys: RDD[(String, Unit)] = reps.map(x => (x._1, ()))
    val sources: RDD[(String, WorkRecord)] = in.sourceWorks.map(w => (w.workId, w))
    val assignments: RDD[(String, ResearcherAssignment)] = in.researchers.map(r => (r.memberId, r))

    val researcherNodes =
      pr(
        in.researchers
          .filter(r => r.status != null && r.status.identified && r.researcherId.isDefined)
          .map(r =>
            (
              r.researcherId.get,
              (
                Set((r.status.tag, r.anchorNamespace.getOrElse(""), r.groupSize)),
                ResearcherAssignment.requiresT1Anchor(r)
              )
            )
          )
      ).reduceByKey((a, b) => (a._1 ++ b._1, a._2 || b._2), p)
        .map { case (rid, (agreed, t1)) => researcherNode(ctx, rid, agreed, t1) }

    val workNodes = pr(reps).leftOuterJoin(sources, p).map { case (_, (w, s)) =>
      workNode(ctx, w, s)
    }

    val mentionsOnReps: RDD[ResearcherAssignment] = pr(
      in.researchers
        .filter(r => ResearcherAssignment.isMention(r) && r.workId.isDefined)
        .map(r => (r.workId.get, r))
    ).join(repKeys, p).map(_._2._1)
    val mentionNodes = mentionsOnReps.map(m => mentionNode(ctx, m))
    val mentionEdges = mentionsOnReps.map(m => mentionEdge(ctx, m))

    val authorshipsOnReps = pr(in.authorships.map(a => (a.workId, a)))
      .leftOuterJoin(repKeys, p)
      .map { case (_, (a, rep)) => (a.mentionId, (a, rep.isDefined)) }
    val authored = pr(authorshipsOnReps).leftOuterJoin(assignments, p).map {
      case (_, ((a, onRep), r)) => researcherAuthored(ctx, a, onRep, r)
    }

    def sourceNodes(label: NodeLabel, named: RDD[(String, String)]) =
      pr(named.map { case (id, n) => (id, Set(n)) }).reduceByKey(_ ++ _, p).map {
        case (id, names) => sourceNode(ctx, label, id, names)
      }
    val venueNodes = sourceNodes(NodeLabel.Venue, in.venues.map(v => (v.venueId, v.name)))
    val institutionNodes =
      sourceNodes(NodeLabel.Institution, in.institutions.map(i => (i.institutionId, i.name)))
    val topicNodes = sourceNodes(NodeLabel.Topic, in.topics.map(t => (t.topicId, t.name)))
    def ids(xs: RDD[String]): RDD[(String, Unit)] =
      pr(xs.map(x => (x, ()))).reduceByKey((a, _) => a, p)

    // Member-work assertions of every canonical work that is a representative: (canonical work,
    // asserting member work, its curated record).
    val onReps: RDD[(String, String, WorkRecord)] =
      pr(pr(in.works.map(w => (w.workId, w.canonicalWorkId))).join(sources, p).map {
        case (member, (canonical, src)) => (canonical, (member, src))
      }).join(repKeys, p).map { case (canonical, ((member, src), _)) => (canonical, member, src) }

    /** (canonical work, target, smallest asserting member, target has a curated record). */
    def asserted(
        targetsOf: WorkRecord => Vector[String],
        known: RDD[(String, Unit)]
    ): RDD[(String, String, String, Boolean)] = {
      val smallest = pr(onReps.flatMap { case (c, m, s) =>
        targetsOf(s).distinct.map(t => ((c, t), m))
      }).reduceByKey(GraphStructureJob.minUtf8, p).map { case ((c, t), by) => (t, (c, by)) }
      pr(smallest)
        .leftOuterJoin(known, p)
        .map { case (t, ((c, by), k)) => (c, t, by, k.isDefined) }
        .persist(StorageLevel.MEMORY_AND_DISK)
    }
    val venueAssertions =
      asserted(s => Option(s.venueId).flatten.toVector, ids(in.venues.map(_.venueId)))
    val topicAssertions =
      asserted(s => Option(s.topicIds).getOrElse(Vector.empty), ids(in.topics.map(_.topicId)))
    val published = venueAssertions.filter(_._4).map { case (c, t, by, _) =>
      edge(ctx, EdgeLabel.PublishedIn, c, t, by, Vector.empty, SchemaVersions.WorkRecord)
    }
    val hasTopic = topicAssertions.filter(_._4).map { case (c, t, by, _) =>
      edge(ctx, EdgeLabel.HasTopic, c, t, by, Vector.empty, SchemaVersions.WorkRecord)
    }

    // AFFILIATED_WITH: every curated mention (any work), its assignment and its affiliations.
    val mentionsWithRep = pr(in.mentions.map(m => (m.workId, m)))
      .leftOuterJoin(repKeys, p)
      .map { case (_, (m, rep)) => (m.mentionId, (m, rep.isDefined)) }
    val affiliationSources: RDD[Either[String, Option[(String, String, Vector[String])]]] =
      pr(mentionsWithRep)
        .leftOuterJoin(assignments, p)
        .map { case (mid, ((m, onRep), a)) =>
          a match {
            case None => Left(s"mentions[$mid]: the mention has no researcher assignment")
            case Some(x) =>
              Right(
                affiliationSource(m, onRep, x)
                  .map(f => (f, mid, Option(m.affiliationIds).getOrElse(Vector.empty).distinct))
              )
          }
        }
        .persist(StorageLevel.MEMORY_AND_DISK)
    val unassigned: RDD[Either[String, GraphEdgeV2]] =
      affiliationSources.filter(_.isLeft).map(x => Left(x.left.toOption.get))
    val affiliationAssertions = pr(affiliationSources.flatMap {
      case Right(Some((f, mid, affs))) => affs.map(i => (i, (f, mid)))
      case _ => Vector.empty
    }).leftOuterJoin(ids(in.institutions.map(_.institutionId)), p)
      .map { case (i, ((f, mid), k)) => (f, i, mid, k.isDefined) }
      .persist(StorageLevel.MEMORY_AND_DISK)
    val affiliated = affiliationAssertions.filter(_._4).map { case (f, i, mid, _) =>
      edge(ctx, EdgeLabel.AffiliatedWith, f, i, mid, Vector.empty, SchemaVersions.AuthorMention)
    }

    val unmatched = UnmatchedReferences(
      venueAssertions.filter(!_._4).count(),
      topicAssertions.filter(!_._4).count(),
      affiliationAssertions.filter(!_._4).count()
    )

    val nodes: RDD[Either[String, GraphNodeV2]] =
      researcherNodes ++ workNodes ++ mentionNodes ++ venueNodes ++ institutionNodes ++ topicNodes
    val edges: RDD[Either[String, GraphEdgeV2]] =
      mentionEdges ++ authored ++ published ++ hasTopic ++ unassigned ++ affiliated ++
        coauthorEdges.map(e => coauthored(ctx, e))
    CanonicalTables(
      nodes
        .map(x => (x.fold(w => ("0", w), n => ("1", n.nodeId)), x))
        .sortBy(_._1, ascending = true, p)
        .map(_._2),
      edges
        .map(x => (x.fold(w => ("0", w), e => ("1", e.edgeId)), x))
        .sortBy(_._1, ascending = true, p)
        .map(_._2),
      unmatched,
      Vector(venueAssertions, topicAssertions, affiliationSources, affiliationAssertions)
    )
  }

  // ---------------------------------------------------------------- invariants

  /**
   * Invariant failures of published-ready tables, counted distributed (each must be 0; the run
   * refuses otherwise): edges whose endpoint is not a node (dangling), edges whose endpoint node
   * has a label the edge class does not allow, unresolved mention nodes without exactly one own
   * AUTHORED edge or with a RESOLVES_TO edge, and identified mention nodes without exactly one
   * RESOLVES_TO edge or with an own AUTHORED edge. Shuffles: the endpoints `leftOuterJoin` the node
   * labels by ID; the per-mention edge counts `reduceByKey`, then `leftOuterJoin` the mention
   * nodes. Only the counts reach the driver.
   */
  def invariants(
      nodes: RDD[GraphNodeV2],
      edges: RDD[GraphEdgeV2],
      partitions: Int
  ): Vector[(String, Long)] = {
    val p = partitions
    val labels: RDD[(String, NodeLabel)] = nodes.map(n => (n.nodeId, n.label))
    val ends = edges.flatMap { e =>
      val (fromLabels, toLabels) = CanonicalGraph.Endpoints(e.edgeType)
      Vector((e.fromId, fromLabels), (e.toId, toLabels))
    }
    val joined = pr(ends).leftOuterJoin(labels, p).map(_._2).persist(StorageLevel.MEMORY_AND_DISK)
    val dangling = joined.filter(_._2.isEmpty).count()
    val wrongLabel =
      joined.filter { case (allowed, l) => l.exists(x => !allowed.contains(x)) }.count()
    joined.unpersist(blocking = false)
    // (RESOLVES_TO from it, AUTHORED from it) per mention node.
    val own = pr(
      edges
        .filter(e =>
          (e.edgeType == EdgeLabel.ResolvesTo || e.edgeType == EdgeLabel.Authored) &&
            StableId.prefixKind(e.fromId).contains(IdKind.Mention)
        )
        .map(e => (e.fromId, if (e.edgeType == EdgeLabel.ResolvesTo) (1L, 0L) else (0L, 1L)))
    ).reduceByKey((a, b) => (a._1 + b._1, a._2 + b._2), p)
    val mentions = nodes
      .filter(_.label == NodeLabel.AuthorMention)
      .map(n =>
        (n.nodeId, n.properties.get("assignmentStatus").contains(AssignmentStatus.Unresolved.tag))
      )
    val mentionRule = pr(mentions)
      .leftOuterJoin(own, p)
      .filter { case (_, (unresolved, counts)) =>
        val (r, a) = counts.getOrElse((0L, 0L))
        if (unresolved) r != 0L || a != 1L else r != 1L || a != 0L
      }
      .count()
    Vector(
      "edges with a dangling endpoint (no node of the canonical tables)" -> dangling,
      "edge endpoints with a label the edge class does not allow" -> wrongLabel,
      "mention nodes breaking the one-of rule (unresolved: one own AUTHORED and no RESOLVES_TO; identified: one RESOLVES_TO and no own AUTHORED)" -> mentionRule
    )
  }

  // ---------------------------------------------------------------- Neptune CSV

  /**
   * The Neptune bulk-load CSV parts of one label, one file per partition (`NeptuneCsv.vertexFile`:
   * header plus one line per node of the partition), each with its row count; a Left is a refusal.
   * No shuffle (`mapPartitions` over the sorted node records).
   */
  def vertexFiles(
      nodes: RDD[GraphNodeV2],
      label: NodeLabel
  ): RDD[Either[String, (Long, String)]] =
    nodes
      .filter(_.label == label)
      .mapPartitions(
        it => {
          val xs = it.toVector
          Iterator(
            NeptuneCsv
              .vertexFile(label, xs)
              .left
              .map(e => s"Neptune ${label.tag} vertices: ${e.mkString("; ")}")
              .map(text => (xs.size.toLong, text))
          )
        },
        preservesPartitioning = true
      )

  /** As [[vertexFiles]], for the edges of one class (`NeptuneCsv.edgeFile`). */
  def edgeFiles(
      edges: RDD[GraphEdgeV2],
      label: EdgeLabel
  ): RDD[Either[String, (Long, String)]] =
    edges
      .filter(_.edgeType == label)
      .mapPartitions(
        it => {
          val xs = it.toVector
          Iterator(
            NeptuneCsv
              .edgeFile(label, xs)
              .left
              .map(e => s"Neptune ${label.tag} edges: ${e.mkString("; ")}")
              .map(text => (xs.size.toLong, text))
          )
        },
        preservesPartitioning = true
      )
}
