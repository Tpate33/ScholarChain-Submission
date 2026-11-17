package scholarchain.spark.graph

import scala.collection.immutable.SortedMap
import scala.reflect.ClassTag

import org.apache.spark.HashPartitioner
import org.apache.spark.rdd.{PairRDDFunctions, RDD}

import scholarchain.core.*

import GraphStructureJob.{Vertex, minUtf8}

/**
 * What every structure record of one analysis run shares besides the edge context: the configured
 * community algorithm and parameters (`community.*`) and bridge measure and parameters
 * (`bridge.*`), recorded on every record exactly as configured (`AnalysisRunSettings`).
 */
final case class StructureContext(
    edges: EdgeContext,
    communityAlgorithm: String,
    communityParameters: SortedMap[String, String],
    bridgeAlgorithm: String,
    bridgeParameters: SortedMap[String, String]
)

/**
 * Component memberships, community memberships, bridge measures and coreness records (schemas
 * `component-membership.v1`, `community-membership.v1`, `bridge-measure.v1`, `coreness.v1`; core
 * Analysis.scala:823-1029) from a [[GraphStructure]], one record per identified researcher and
 * window, each checked against its record contract (a failure is a refusal of the run, never a
 * silently dropped record).
 *
 * IDs: a component ID is `ComponentId.of(window, graph policy, members)`, whose only member input
 * is the anchor (smallest member), which is the hash-min label; a community ID is
 * `CommunityId.of(window, graph policy, algorithm, parameters, members)` with the smallest member
 * computed per community (the propagated label need not be a member). Neither depends on
 * partitioning or iteration order.
 *
 * Bridge measure `cross-community-degree` (the only implemented `bridge.algorithm`): `score` =
 * `crossCommunityDegree` as a Double, exact; `parameters` = the configured `bridge.*` values, which
 * this measure does not use (no sampling, no iteration, no randomness); `communitiesReached` and
 * the participation coefficient (`BridgeMeasure.participation`) are recorded beside it. No
 * betweenness approximation is computed.
 *
 * Shuffles: component sizes (`reduceByKey` by (window, label)), the size and ID join back to the
 * members (shuffle); community sizes and anchors (`reduceByKey`), their join back (shuffle), and
 * the join with the member's component (shuffle); bridges: the join of community info, bridge
 * counts and degrees (shuffles); coreness: the co-partitioned join with the degrees (narrow); every
 * record set is sorted by (window index, researcher ID) (range-partitioning shuffle) for a stable
 * output order. Nothing per record reaches the driver.
 */
object StructureRecordsJob {

  private def pr[K: ClassTag, V: ClassTag](r: RDD[(K, V)]): PairRDDFunctions[K, V] =
    RDD.rddToPairRDDFunctions(r)

  /** Per vertex: its component's (ID, size), or why the ID cannot be formed. */
  def componentInfo(
      s: GraphStructure,
      ctx: EdgeContext,
      partitions: Int
  ): RDD[(Vertex, Either[String, (String, Long)])] = {
    val sizes =
      pr(s.components.map { case (v, l) => ((v._1, l), 1L) }).reduceByKey(_ + _, partitions)
    val ids = sizes.map { case ((w, l), size) =>
      ((w, l), componentId(ctx, w, l).map(id => (id, size)))
    }
    pr(pr(s.components.map { case (v, l) => ((v._1, l), v) }).join(ids, partitions).values)
      .partitionBy(new HashPartitioner(partitions))
  }

  def componentId(ctx: EdgeContext, w: Int, anchor: String): Either[String, String] =
    ComponentId
      .of(ctx.windows(w), ctx.graphPolicyVersion, Vector(anchor))
      .left
      .map(e => s"component ID of $anchor in window $w: $e")

  /** Per vertex: its community's (ID, size), or why the ID cannot be formed. */
  def communityInfo(
      s: GraphStructure,
      sctx: StructureContext,
      partitions: Int
  ): RDD[(Vertex, Either[String, (String, Long)])] = {
    val agg = pr(s.communities.map { case (v, l) => ((v._1, l), (1L, v._2)) })
      .reduceByKey((a, b) => (a._1 + b._1, minUtf8(a._2, b._2)), partitions)
    val ids = agg.map { case ((w, l), (size, anchor)) =>
      ((w, l), communityId(sctx, w, anchor).map(id => (id, size)))
    }
    pr(pr(s.communities.map { case (v, l) => ((v._1, l), v) }).join(ids, partitions).values)
      .partitionBy(new HashPartitioner(partitions))
  }

  def communityId(sctx: StructureContext, w: Int, anchor: String): Either[String, String] =
    CommunityId
      .of(
        sctx.edges.windows(w),
        sctx.edges.graphPolicyVersion,
        sctx.communityAlgorithm,
        sctx.communityParameters,
        Vector(anchor)
      )
      .left
      .map(e => s"community ID of $anchor in window $w: $e")

  private def checked[T](
      label: String,
      r: String,
      w: Int,
      t: T,
      errors: Vector[ValidationError]
  ): Either[String, T] =
    if (errors.isEmpty) Right(t) else Left(s"$label of $r in window $w: ${errors.mkString("; ")}")

  def buildComponent(
      ctx: EdgeContext,
      v: Vertex,
      info: Either[String, (String, Long)]
  ): Either[String, ComponentMembership] =
    info.flatMap { case (id, size) =>
      val c = ComponentMembership(
        SchemaVersions.ComponentMembership,
        v._2,
        ctx.windows(v._1),
        id,
        size,
        ctx.lineage,
        ctx.provenanceOf(v._2)
      )
      checked("component membership", v._2, v._1, c, ComponentMembership.contract.validate(c))
    }

  def buildCommunity(
      sctx: StructureContext,
      v: Vertex,
      community: Either[String, (String, Long)],
      component: Either[String, (String, Long)]
  ): Either[String, CommunityMembership] =
    for {
      cm <- community
      cp <- component
      c = CommunityMembership(
        SchemaVersions.CommunityMembership,
        v._2,
        sctx.edges.windows(v._1),
        cm._1,
        cm._2,
        cp._1,
        sctx.communityAlgorithm,
        sctx.communityParameters,
        sctx.edges.lineage,
        sctx.edges.provenanceOf(v._2)
      )
      r <- checked("community membership", v._2, v._1, c, CommunityMembership.contract.validate(c))
    } yield r

  def buildBridge(
      sctx: StructureContext,
      v: Vertex,
      community: Either[String, (String, Long)],
      counts: BridgeCounts,
      degree: Long
  ): Either[String, BridgeMeasure] =
    if (counts.degree != degree)
      Left(
        s"bridge measure of ${v._2} in window ${v._1}: neighbour degree ${counts.degree} differs from the degree $degree"
      )
    else
      community.flatMap { case (id, _) =>
        val b = BridgeMeasure(
          SchemaVersions.BridgeMeasure,
          v._2,
          sctx.edges.windows(v._1),
          id,
          sctx.bridgeAlgorithm,
          sctx.bridgeParameters,
          counts.crossCommunityDegree.toDouble,
          counts.degree,
          counts.crossCommunityDegree,
          counts.communitiesReached,
          BridgeMeasure.participation(counts.degree, counts.sumOfSquares),
          sctx.edges.lineage,
          sctx.edges.provenanceOf(v._2)
        )
        checked("bridge measure", v._2, v._1, b, BridgeMeasure.contract.validate(b))
      }

  def buildCoreness(
      ctx: EdgeContext,
      v: Vertex,
      k: Long,
      degree: Long
  ): Either[String, Coreness] = {
    val c = Coreness(
      SchemaVersions.Coreness,
      v._2,
      ctx.windows(v._1),
      k,
      degree,
      ctx.lineage,
      ctx.provenanceOf(v._2)
    )
    checked("coreness", v._2, v._1, c, Coreness.contract.validate(c))
  }

  private def sorted[T: ClassTag](r: RDD[(Vertex, Either[String, T])], partitions: Int) =
    r.sortBy(_._1, ascending = true, partitions).map(_._2)

  def components(
      info: RDD[(Vertex, Either[String, (String, Long)])],
      ctx: EdgeContext,
      partitions: Int
  ): RDD[Either[String, ComponentMembership]] =
    sorted(info.map { case (v, i) => (v, buildComponent(ctx, v, i)) }, partitions)

  def communities(
      communityInfo: RDD[(Vertex, Either[String, (String, Long)])],
      componentInfo: RDD[(Vertex, Either[String, (String, Long)])],
      sctx: StructureContext,
      partitions: Int
  ): RDD[Either[String, CommunityMembership]] =
    sorted(
      pr(communityInfo).join(componentInfo, partitions).map { case (v, (cm, cp)) =>
        (v, buildCommunity(sctx, v, cm, cp))
      },
      partitions
    )

  def bridges(
      communityInfo: RDD[(Vertex, Either[String, (String, Long)])],
      counts: RDD[(Vertex, BridgeCounts)],
      degrees: RDD[(Vertex, Long)],
      sctx: StructureContext,
      partitions: Int
  ): RDD[Either[String, BridgeMeasure]] =
    sorted(
      pr(pr(communityInfo).join(counts, partitions)).join(degrees, partitions).map {
        case (v, ((cm, b), d)) => (v, buildBridge(sctx, v, cm, b, d))
      },
      partitions
    )

  def coreness(
      s: GraphStructure,
      ctx: EdgeContext,
      partitions: Int
  ): RDD[Either[String, Coreness]] =
    sorted(
      pr(s.coreness).join(s.degrees).map { case (v, (k, d)) => (v, buildCoreness(ctx, v, k, d)) },
      partitions
    )

  /**
   * The `window-structure.v1` records of the run (SP-P6-03 step 2B; core AnalysisStructure.scala
   * WindowStructure), one per configured window in configured order, from the per-window structure
   * totals [[GraphStructureJob.summaries]] already computed (one small record per window on the
   * driver; no RDD is read): researchers, edges (degree sum / 2), components and the largest
   * (fraction `WindowStructure.fraction`), singletons, communities and the largest, cross-community
   * edges (half the cross-community degree sum), the configured community algorithm, the
   * label-propagation outcome (iterations, converged) and the maximum coreness. Each record passes
   * its contract; `StructureValidation.checkWindowStructures` recomputes every count from the run's
   * per-researcher records and ties the outcome to the run manifest parameters.
   */
  def windowStructures(
      summaries: Vector[StructureWindowSummary],
      sctx: StructureContext
  ): Either[Vector[String], Vector[WindowStructure]] = {
    val ctx = sctx.edges
    val built = summaries.zipWithIndex.map { case (s, i) =>
      val subject = AnalysisWindow.subjectOf(s.window)
      val r = WindowStructure(
        SchemaVersions.WindowStructure,
        s.window,
        i,
        s.researchers,
        s.edges,
        s.components,
        s.largestComponent,
        WindowStructure.fraction(s.largestComponent, s.researchers),
        s.singletonComponents,
        s.communities,
        s.largestCommunity,
        s.crossCommunityEdges,
        sctx.communityAlgorithm,
        s.labelPropagation.iterations,
        s.labelPropagation.converged,
        s.maxCoreness,
        ctx.lineage,
        ctx.provenanceOf(subject)
      )
      val e = WindowStructure.contract.validate(r)
      if (e.isEmpty) Right(r) else Left(s"window structure $subject: ${e.mkString("; ")}")
    }
    val bad = built.collect { case Left(w) => w }
    if (bad.nonEmpty) Left(bad)
    else if (summaries.map(_.window) != ctx.windows)
      Left(Vector("window structures: the summaries are not the configured windows in order"))
    else Right(built.collect { case Right(r) => r })
  }
}
