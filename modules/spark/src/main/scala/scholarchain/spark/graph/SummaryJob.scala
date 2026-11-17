package scholarchain.spark.graph

import scala.reflect.ClassTag

import org.apache.spark.HashPartitioner
import org.apache.spark.rdd.{PairRDDFunctions, RDD}

import scholarchain.core.*

/**
 * Semantic-summary inputs (course item 10; SP-P6-03 step 2C): the facts a later summary generator
 * may state, each copied from one stored record of the run and citing it. No text is generated, no
 * model is called, and no metric is invented: every fact name is in the frozen core vocabularies
 * and its value is exactly the named field of the first cited record of the required kind
 * (`AnalysisValidation.SummaryFactVocabulary`, `StructureValidation.EntityFactVocabulary`; checked
 * by `AnalysisValidation.checkSummaryInputs` and `StructureValidation.checkEntitySummaries`).
 *
 * `semantic-summary-input.v1`, one per subject and window:
 *   - `researcher-profile`, one per metrics record: P_r, C_r, T_r, unresolved occurrences, A_r, M_r
 *     (also citing the counted works responsible for it), W_r (researcher-metrics), component size
 *     (component), community and community size (community), cross-community degree, communities
 *     reached, participation coefficient (bridge-measure), coreness (coreness); the identity
 *     confidence is the metrics record's (C5). The six specification quantities stay six separate
 *     facts.
 *   - `community-summary`, one per community: the community, its size (community) and the size of
 *     the component that holds it (component).
 *   - `path-summary`, one per stored evidence path: its hop count (citing the path and every hop's
 *     coauthor edge) and the size of the component that holds it (component).
 *
 * `entity-summary-input.v1` (not windowed). Deterministic selection rule: the selected works are
 * the distinct evidence works of the stored evidence paths (every window); the selected topics are
 * the HAS_TOPIC targets of the selected works. So the set is bounded by pairs x windows x
 * `maxPathsPerPair` x `maxHops` x `maxEvidenceWorksPerHop`, and a run without a stored path has no
 * entity summary.
 *   - `work-summary`: `title` (the representative's own curated work record), `graphEligibility`,
 *     `groupSize` and `year` when present (the Work node), one `author` per distinct AUTHORED
 *     source (citing its AUTHORED edges), one `venue` per PUBLISHED_IN edge, one `topic` per
 *     HAS_TOPIC edge, and one `name` per distinct venue or topic name (citing the adjacent nodes).
 *   - `topic-summary`: `name` (the Topic node) and one `work` per selected work with that topic
 *     (citing the HAS_TOPIC edge).
 *
 * Facts are ordered by (name, value). Shuffles (P = `partitions`): researcher profiles need none
 * beyond the caller's joined records; communities `reduceByKey` by (window, community) and `join`
 * the component sizes; paths `join` the source's component; entity summaries: selected works
 * `distinct`, `leftOuterJoin` Work nodes, `join` AUTHORED / PUBLISHED_IN / HAS_TOPIC edges and the
 * adjacent nodes and curated work records, `union` of the facts and `groupByKey` per subject
 * (bounded by the subject's edges), topics likewise; each output `sortBy` its subject key.
 */
object SummaryJob {

  private def pr[K: ClassTag, V: ClassTag](r: RDD[(K, V)]): PairRDDFunctions[K, V] =
    RDD.rddToPairRDDFunctions(r)

  private def ref(k: SummaryEvidenceKind, id: String) = SummaryEvidenceRef(k, id)

  private def fact(name: String, value: String, refs: SummaryEvidenceRef*) =
    SummaryFact(name, value, refs.toVector)

  /** Builds and checks one semantic-summary input. */
  def buildInput(
      ctx: EdgeContext,
      documentType: DocumentType,
      subject: String,
      window: Int,
      facts: Vector[SummaryFact],
      identity: Option[IdentityConfidence],
      sourceSchema: String
  ): Either[String, SemanticSummaryInput] = {
    val w = ctx.windows(window)
    val what = s"${documentType.tag} input of $subject in window $window"
    SummaryInputId
      .of(documentType, subject, w, ctx.graphPolicyVersion)
      .left
      .map(e => s"$what: $e")
      .flatMap { id =>
        val s = SemanticSummaryInput(
          SchemaVersions.SemanticSummaryInput,
          id,
          documentType,
          subject,
          w,
          facts.sortBy(f => (f.name, f.value)),
          identity,
          ctx.lineage,
          CanonicalGraphJob.provenance(ctx, sourceSchema)(id)
        )
        val e = SemanticSummaryInput.contract.validate(s)
        if (e.isEmpty) Right(s) else Left(s"$what: ${e.mkString("; ")}")
      }
  }

  /** The researcher-profile facts of one researcher in one window. */
  def profileFacts(r: ResearcherWindowRecords): Vector[SummaryFact] = {
    import SummaryEvidenceKind.*
    val m = r.metrics
    val id = m.researcherId
    val mr = ref(ResearcherMetrics, id)
    Vector(
      fact("publicationCount", m.publicationCount.toString, mr),
      fact("distinctCoauthorCount", m.distinctCoauthorCount.toString, mr),
      fact("totalCoauthorOccurrences", m.totalCoauthorOccurrences.toString, mr),
      fact("unresolvedCoauthorOccurrences", m.unresolvedCoauthorOccurrences.toString, mr),
      fact("averageCoauthorsPerPaper", String.valueOf(m.averageCoauthorsPerPaper), mr),
      fact(
        "maximumCoauthorsOnOnePaper",
        m.maximumCoauthorsOnOnePaper.toString,
        (mr +: m.maximumTeamWorkIds.map(ref(CountedWork, _)))*
      ),
      fact("weightedDegree", m.weightedDegree.toString, mr),
      fact(
        "componentSize",
        r.component.componentSize.toString,
        ref(Component, r.component.componentId)
      ),
      fact("community", r.community.communityId, ref(Community, r.community.communityId)),
      fact(
        "communitySize",
        r.community.communitySize.toString,
        ref(Community, r.community.communityId)
      ),
      fact("crossCommunityDegree", r.bridge.crossCommunityDegree.toString, ref(BridgeMeasure, id)),
      fact("communitiesReached", r.bridge.communitiesReached.toString, ref(BridgeMeasure, id)),
      fact(
        "participationCoefficient",
        String.valueOf(r.bridge.participationCoefficient),
        ref(BridgeMeasure, id)
      ),
      fact("coreness", r.coreness.coreness.toString, ref(Coreness, id))
    )
  }

  /** One researcher profile per (window, researcher) record set. */
  def researcherProfiles(
      records: RDD[((Int, String), ResearcherWindowRecords)],
      ctx: EdgeContext
  ): RDD[((String, Int, String), Either[String, SemanticSummaryInput])] =
    records.map { case ((w, r), rec) =>
      (
        (DocumentType.ResearcherProfile.tag, w, r),
        buildInput(
          ctx,
          DocumentType.ResearcherProfile,
          r,
          w,
          profileFacts(rec),
          Some(rec.metrics.identity),
          SchemaVersions.ResearcherMetricsV2
        )
      )
    }

  /** One community summary per (window, community). */
  def communitySummaries(
      communities: RDD[CommunityMembership],
      components: RDD[ComponentMembership],
      ctx: EdgeContext,
      partitions: Int
  ): RDD[((String, Int, String), Either[String, SemanticSummaryInput])] = {
    val ws = ctx.windows
    val byCommunity = pr(
      communities.map(c =>
        ((ws.indexOf(c.window), c.communityId), (c.communitySize, c.componentId))
      )
    ).reduceByKey((a, _) => a, partitions)
    val componentSize = pr(
      components.map(c => ((ws.indexOf(c.window), c.componentId), c.componentSize))
    ).reduceByKey((a, _) => a, partitions)
    pr(byCommunity.map { case ((w, id), (size, comp)) => ((w, comp), (id, size)) })
      .leftOuterJoin(componentSize, partitions)
      .map { case ((w, comp), ((id, size), cs)) =>
        import SummaryEvidenceKind.*
        (
          (DocumentType.CommunitySummary.tag, w, id),
          cs.toRight(s"community $id in window $w: component $comp has no membership record")
            .flatMap(n =>
              buildInput(
                ctx,
                DocumentType.CommunitySummary,
                id,
                w,
                Vector(
                  fact("community", id, ref(Community, id)),
                  fact("communitySize", size.toString, ref(Community, id)),
                  fact("componentSize", n.toString, ref(Component, comp))
                ),
                None,
                SchemaVersions.CommunityMembership
              )
            )
        )
      }
  }

  /** One path summary per stored evidence path. */
  def pathSummaries(
      paths: RDD[EvidencePath],
      components: RDD[ComponentMembership],
      ctx: EdgeContext,
      partitions: Int
  ): RDD[((String, Int, String), Either[String, SemanticSummaryInput])] = {
    val ws = ctx.windows
    val componentOf =
      components.map(c =>
        ((ws.indexOf(c.window), c.researcherId), (c.componentId, c.componentSize))
      )
    pr(paths.map(p => ((ws.indexOf(p.window), p.sourceResearcherId), p)))
      .leftOuterJoin(componentOf, partitions)
      .map { case ((w, src), (p, comp)) =>
        import SummaryEvidenceKind.*
        (
          (DocumentType.PathSummary.tag, w, p.pathId),
          comp
            .toRight(s"path ${p.pathId}: its source $src has no component membership in window $w")
            .flatMap { case (cid, size) =>
              buildInput(
                ctx,
                DocumentType.PathSummary,
                p.pathId,
                w,
                Vector(
                  fact(
                    "hopCount",
                    p.hopCount.toString,
                    (ref(EvidencePath, p.pathId) +: p.hops.map(h => ref(CoauthorEdge, h.edgeId)))*
                  ),
                  fact("componentSize", size.toString, ref(Component, cid))
                ),
                None,
                SchemaVersions.EvidencePath
              )
            }
        )
      }
  }

  /** Every semantic-summary input, sorted by (document type, window index, subject). */
  def semanticInputs(
      parts: Vector[RDD[((String, Int, String), Either[String, SemanticSummaryInput])]],
      partitions: Int
  ): RDD[Either[String, SemanticSummaryInput]] =
    parts
      .reduce(_ union _)
      .sortBy(_._1, ascending = true, partitions)
      .map(_._2)

  // ---------------------------------------------------------------- entity summaries

  private def eref(k: EntityEvidenceKind, id: String) = EntityEvidenceRef(k, id)

  private def sortedIds(xs: Iterable[String]): Vector[String] =
    xs.toVector.distinct.sortWith((a, b) => Canonical.compareUtf8(a, b) < 0)

  /** Builds and checks one entity summary input. */
  def buildEntity(
      ctx: EdgeContext,
      documentType: DocumentType,
      subject: String,
      facts: Vector[EntityFact]
  ): Either[String, EntitySummaryInput] = {
    val what = s"${documentType.tag} input of $subject"
    EntitySummaryInputId
      .of(documentType, subject, ctx.graphPolicyVersion)
      .left
      .map(e => s"$what: $e")
      .flatMap { id =>
        val s = EntitySummaryInput(
          SchemaVersions.EntitySummaryInput,
          id,
          documentType,
          subject,
          facts.sortBy(f => (f.name, f.value)),
          ctx.lineage,
          CanonicalGraphJob.provenance(ctx, SchemaVersions.GraphNodeV2)(id)
        )
        val e = EntitySummaryInput.contract.validate(s)
        if (e.isEmpty) Right(s) else Left(s"$what: ${e.mkString("; ")}")
      }
  }

  /**
   * The work and topic summary inputs of the selected works and topics (see the object comment),
   * sorted by (document type, subject). `works` are the grouping run's work assignments and
   * `sourceWorks` the curated work records.
   */
  def entitySummaries(
      paths: RDD[EvidencePath],
      nodes: RDD[GraphNodeV2],
      graphEdges: RDD[GraphEdgeV2],
      works: RDD[WorkAssignment],
      sourceWorks: RDD[WorkRecord],
      ctx: EdgeContext,
      partitions: Int
  ): RDD[Either[String, EntitySummaryInput]] = {
    import EntityEvidenceKind.*
    val part = new HashPartitioner(partitions)
    val selected = pr(
      paths.flatMap(_.hops.flatMap(_.evidenceWorkIds)).distinct(partitions).map(w => (w, ()))
    ).partitionBy(part)
    val nodeById = nodes.map(n => (n.nodeId, n))
    val workNodes = pr(selected).leftOuterJoin(nodeById, part).map { case (w, (_, n)) =>
      (w, n.filter(_.label == NodeLabel.Work))
    }
    def edgesOf(t: EdgeLabel, fromSide: Boolean) =
      graphEdges
        .filter(_.edgeType == t)
        .map(e => (if (fromSide) e.fromId else e.toId, e))
    val authored = pr(selected).join(edgesOf(EdgeLabel.Authored, false), part).map {
      case (w, (_, e)) => ((w, e.fromId), e.edgeId)
    }
    val outgoing =
      pr(selected)
        .join(
          graphEdges
            .filter(e => e.edgeType == EdgeLabel.PublishedIn || e.edgeType == EdgeLabel.HasTopic)
            .map(e => (e.fromId, e)),
          part
        )
        .map { case (w, (_, e)) => (w, e) }
    val title = pr(
      pr(selected)
        .join(
          works.filter(a => a.workId == a.canonicalWorkId).map(a => (a.workId, ())),
          part
        )
    ).join(sourceWorks.map(s => (s.workId, s.title)), part).map { case (w, ((_, _), t)) => (w, t) }
    val neighbourNames = pr(outgoing.map { case (w, e) => (e.toId, w) })
      .join(nodeById, part)
      .flatMap { case (id, (w, n)) => n.properties.get("name").map(name => ((w, name), id)) }

    val subjectFacts: RDD[(String, Either[String, EntityFact])] = workNodes.flatMap {
      case (w, None) => Vector((w, Left(s"selected work $w is not a Work node of the run")))
      case (w, Some(n)) =>
        Vector("graphEligibility", "groupSize", "year").flatMap(k =>
          n.properties.get(k).map(v => (w, Right(EntityFact(k, v, Vector(eref(GraphNode, w))))))
        )
    }
    val titleFacts: RDD[(String, Either[String, EntityFact])] =
      pr(selected).leftOuterJoin(title, part).map { case (w, (_, t)) =>
        (
          w,
          t.toRight(s"selected work $w has no curated work record of its own")
            .map(v => EntityFact("title", v, Vector(eref(SourceWork, w))))
        )
      }
    val authorFacts: RDD[(String, Either[String, EntityFact])] =
      pr(authored).groupByKey(part).map { case ((w, from), ids) =>
        (w, Right(EntityFact("author", from, sortedIds(ids).map(eref(GraphEdge, _)))))
      }
    val edgeFacts: RDD[(String, Either[String, EntityFact])] = outgoing.map { case (w, e) =>
      val name = if (e.edgeType == EdgeLabel.PublishedIn) "venue" else "topic"
      (w, Right(EntityFact(name, e.toId, Vector(eref(GraphEdge, e.edgeId)))))
    }
    val nameFacts: RDD[(String, Either[String, EntityFact])] =
      pr(neighbourNames).groupByKey(part).map { case ((w, name), ids) =>
        (w, Right(EntityFact("name", name, sortedIds(ids).map(eref(GraphNode, _)))))
      }
    val workInputs =
      pr(subjectFacts.union(titleFacts).union(authorFacts).union(edgeFacts).union(nameFacts))
        .groupByKey(part)
        .map { case (w, fs) =>
          val v = fs.toVector
          val bad = v.collect { case Left(e) => e }
          (
            (DocumentType.WorkSummary.tag, w),
            if (bad.nonEmpty) Left(bad.mkString("; "))
            else buildEntity(ctx, DocumentType.WorkSummary, w, v.collect { case Right(f) => f })
          )
        }

    val topicEdges = outgoing.filter(_._2.edgeType == EdgeLabel.HasTopic).map { case (w, e) =>
      (e.toId, (w, e.edgeId))
    }
    val topicInputs = pr(pr(topicEdges).groupByKey(part))
      .leftOuterJoin(nodeById, part)
      .map { case (t, (es, n)) =>
        val facts = n.flatMap(_.properties.get("name")) match {
          case None => Left(s"topic $t of a selected work has no Topic node name")
          case Some(name) =>
            Right(
              EntityFact("name", name, Vector(eref(GraphNode, t))) +: es.toVector.map {
                case (w, edgeId) => EntityFact("work", w, Vector(eref(GraphEdge, edgeId)))
              }
            )
        }
        (
          (DocumentType.TopicSummary.tag, t),
          facts.flatMap(fs => buildEntity(ctx, DocumentType.TopicSummary, t, fs))
        )
      }
    workInputs.union(topicInputs).sortBy(_._1, ascending = true, partitions).map(_._2)
  }
}
