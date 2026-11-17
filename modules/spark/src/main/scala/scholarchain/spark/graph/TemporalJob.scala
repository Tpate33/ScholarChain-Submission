package scholarchain.spark.graph

import scala.reflect.ClassTag

import org.apache.spark.HashPartitioner
import org.apache.spark.rdd.{PairRDDFunctions, RDD}

import scholarchain.core.*

/** The five per-researcher records of one researcher in one window. */
final case class ResearcherWindowRecords(
    metrics: ResearcherMetricsV2,
    component: ComponentMembership,
    community: CommunityMembership,
    bridge: BridgeMeasure,
    coreness: Coreness
)

/**
 * The per-pair counts of the window comparisons (to the driver: at most three presence classes and
 * three edge classes per consecutive window pair).
 */
final case class TemporalCounts(
    presence: Map[(Int, TemporalPresence), Long],
    edges: Map[(Int, Int), Long]
)

/**
 * Temporal comparison of consecutive configured windows (course item 9; SP-P6-03 step 2C; schemas
 * `window-comparison.v1` and `researcher-temporal-change.v1`, core AnalysisStructure.scala; checked
 * by `StructureValidation.checkTemporal`, which recomputes every value from the run's records).
 *
 * Pairs: (`graph.windows(i)`, `graph.windows(i + 1)`) for i = 0..n-2, `ordinal` = i; nothing else
 * is compared. Both windows of a pair come from the same run, so the inclusion rule (counted works
 * of the grouping run, a work in a window when its year lies in it, identified researchers only for
 * edges) is the same and `inclusionRuleCompatible` is true; what still differs is stated as
 * limitations, computed as the core recomputes them: `overlapping-windows` (shared years),
 * `unequal-window-lengths`, `works-without-year-excluded` (counted works without a year, in no
 * window), `community-not-converged` (label propagation hit its bound in either window), and
 * `grouping-unevaluated` (always).
 *
 * Per (pair, researcher with a metrics record in either window): presence, both windows' states
 * (P_r, C_r, A_r, M_r; component size and whether it is a largest component of the window;
 * community size; cross-community degree, communities reached, participation coefficient; coreness)
 * copied from the run's records, the identified coauthors retained, gained and lost, and (in both
 * windows) the overlap of its two communities.
 *
 * Shuffles (P = `partitions`): the five record sets are keyed by (window index, researcher) and
 * `partitionBy` P (five shuffles), then joined co-partitioned (narrow); the per-pair sides
 * `groupByKey` by (pair, researcher) (at most two values per key); coauthor turnover: both
 * directions of every edge keyed by (pair, researcher, coauthor) with a window bit, `reduceByKey`
 * with bitwise or, then `reduceByKey` by (pair, researcher); community overlap: `reduceByKey` by
 * (pair, from community, to community) over the researchers in both windows and a `join` back; two
 * `leftOuterJoin`s and one `sortBy` (pair, researcher). Driver: the per-pair counts
 * ([[TemporalCounts]]), the per-window largest component size and convergence; nothing per
 * researcher.
 */
object TemporalJob {

  private def pr[K: ClassTag, V: ClassTag](r: RDD[(K, V)]): PairRDDFunctions[K, V] =
    RDD.rddToPairRDDFunctions(r)

  /**
   * The five records of every (window index, researcher) with a metrics record; Left when a
   * structure record is missing (the run's cross-checks make this an invariant failure).
   */
  def researcherWindows(
      metrics: RDD[ResearcherMetricsV2],
      components: RDD[ComponentMembership],
      communities: RDD[CommunityMembership],
      bridges: RDD[BridgeMeasure],
      coreness: RDD[Coreness],
      windows: Vector[TimeWindow],
      partitions: Int
  ): RDD[((Int, String), Either[String, ResearcherWindowRecords])] = {
    val part = new HashPartitioner(partitions)
    val ws = windows
    def by[T: ClassTag](r: RDD[T])(w: T => TimeWindow, id: T => String) =
      pr(r.map(x => ((ws.indexOf(w(x)), id(x)), x))).partitionBy(part)
    val m = by(metrics)(_.window, _.researcherId)
    val c = by(components)(_.window, _.researcherId)
    val cm = by(communities)(_.window, _.researcherId)
    val b = by(bridges)(_.window, _.researcherId)
    val k = by(coreness)(_.window, _.researcherId)
    pr(pr(pr(pr(m).leftOuterJoin(c, part)).leftOuterJoin(cm, part)).leftOuterJoin(b, part))
      .leftOuterJoin(k, part)
      .map { case (key, ((((mr, co), cy), br), ko)) =>
        val rec = for {
          x <- co; y <- cy; z <- br; q <- ko
        } yield ResearcherWindowRecords(mr, x, y, z, q)
        (
          key,
          rec.toRight(
            s"${key._2} in window ${key._1}: a metrics record without every component, community, bridge and coreness record"
          )
        )
      }
  }

  /** The state of one researcher in one window, from its records (core `ResearcherWindowState`). */
  def stateOf(r: ResearcherWindowRecords, largestComponent: Long): ResearcherWindowState =
    ResearcherWindowState(
      r.metrics.publicationCount,
      r.metrics.distinctCoauthorCount,
      r.metrics.averageCoauthorsPerPaper,
      r.metrics.maximumCoauthorsOnOnePaper,
      r.component.componentSize,
      r.component.componentSize == largestComponent,
      r.community.communitySize,
      r.bridge.crossCommunityDegree,
      r.bridge.communitiesReached,
      r.bridge.participationCoefficient,
      r.coreness.coreness
    )

  /** Window bit of side `fromSide` (1) or the to side (2). */
  private def bit(fromSide: Boolean): Int = if (fromSide) 1 else 2

  /** (pair, from side?) of window `w` among `n` windows. */
  def sidesOf(w: Int, n: Int): Vector[(Int, Boolean)] =
    Vector(Option.when(w <= n - 2)((w, true)), Option.when(w >= 1)((w - 1, false))).flatten

  /** One `researcher-temporal-change.v1` record. */
  def buildChange(
      ctx: EdgeContext,
      p: Int,
      researcher: String,
      from: Option[(ResearcherWindowState, String)],
      to: Option[(ResearcherWindowState, String)],
      turnover: (Long, Long, Long),
      overlap: Option[Long]
  ): Either[String, ResearcherTemporalChange] = {
    val presence = (from, to) match {
      case (Some(_), Some(_)) => Right(TemporalPresence.InBoth)
      case (Some(_), None) => Right(TemporalPresence.OnlyFrom)
      case (None, Some(_)) => Right(TemporalPresence.OnlyTo)
      case _ => Left(s"$researcher in window pair $p: in neither window")
    }
    presence.flatMap { pres =>
      val c = ResearcherTemporalChange(
        SchemaVersions.ResearcherTemporalChange,
        researcher,
        ctx.windows(p),
        ctx.windows(p + 1),
        pres,
        from.map(_._1),
        to.map(_._1),
        turnover._1,
        turnover._2,
        turnover._3,
        if (pres == TemporalPresence.InBoth) overlap else None,
        ctx.lineage,
        CanonicalGraphJob.provenance(ctx, SchemaVersions.ResearcherMetricsV2)(researcher)
      )
      val e = ResearcherTemporalChange.contract.validate(c)
      if (e.isEmpty) Right(c)
      else Left(s"temporal change of $researcher in window pair $p: ${e.mkString("; ")}")
    }
  }

  /**
   * Every researcher temporal change (stable order: pair, researcher) and the per-pair counts.
   * `records` must hold only Right values (the caller checked them); `largest` is the largest
   * component size of each window.
   */
  def changes(
      records: RDD[((Int, String), ResearcherWindowRecords)],
      edges: RDD[CoauthorEdgeV2],
      largest: Vector[Long],
      ctx: EdgeContext,
      partitions: Int
  ): (RDD[Either[String, ResearcherTemporalChange]], TemporalCounts) = {
    val part = new HashPartitioner(partitions)
    val n = ctx.windows.size
    val ws = ctx.windows
    val big = largest
    val sides = records.flatMap { case ((w, r), rec) =>
      val state = (stateOf(rec, big(w)), rec.community.communityId)
      sidesOf(w, n).map { case (p, isFrom) => ((p, r), (isFrom, state)) }
    }
    val grouped = pr(pr(sides).groupByKey(part)).mapValues { xs =>
      (xs.find(_._1).map(_._2), xs.find(!_._1).map(_._2))
    }
    val edgeBits = pr(edges.flatMap { e =>
      sidesOf(ws.indexOf(e.window), n).map { case (p, isFrom) =>
        ((p, e.researcherA, e.researcherB), bit(isFrom))
      }
    }).reduceByKey(part, _ | _)
    val turnover = pr(
      edgeBits.flatMap { case ((p, a, b), m) =>
        val t = m match {
          case 3 => (1L, 0L, 0L)
          case 2 => (0L, 1L, 0L)
          case _ => (0L, 0L, 1L)
        }
        Vector(((p, a), t), ((p, b), t))
      }
    ).reduceByKey(part, (x, y) => (x._1 + y._1, x._2 + y._2, x._3 + y._3))
    val inBoth = grouped.flatMap { case ((p, r), (f, t)) =>
      for { a <- f; b <- t } yield ((p, a._2, b._2), r)
    }
    val sizes = pr(inBoth.map { case (k, _) => (k, 1L) }).reduceByKey(part, _ + _)
    val overlap: RDD[((Int, String), Long)] =
      pr(inBoth).join(sizes, part).map { case ((p, _, _), (r, s)) => ((p, r), s) }
    val out = pr(pr(grouped).leftOuterJoin(turnover, part))
      .leftOuterJoin(overlap, part)
      .map { case ((p, r), (((f, t), tv), ov)) =>
        ((p, r), buildChange(ctx, p, r, f, t, tv.getOrElse((0L, 0L, 0L)), ov))
      }
      .sortBy(_._1, ascending = true, partitions)
      .map(_._2)
    val presence = grouped
      .map { case ((p, _), (f, t)) =>
        (
          p,
          if (f.isDefined && t.isDefined) TemporalPresence.InBoth
          else if (f.isDefined) TemporalPresence.OnlyFrom
          else TemporalPresence.OnlyTo
        )
      }
      .countByValue()
      .toMap
    val edgeClasses = edgeBits.map { case ((p, _, _), m) => (p, m) }.countByValue().toMap
    (out, TemporalCounts(presence, edgeClasses))
  }

  /** The limitations of one pair, as `StructureValidation.checkTemporal` recomputes them. */
  def limitationsOf(
      from: TimeWindow,
      to: TimeWindow,
      worksWithoutYear: Long,
      fromConverged: Boolean,
      toConverged: Boolean
  ): Vector[ComparisonLimitation] = {
    import ComparisonLimitation.*
    Vector(
      Option.when(WindowComparison.overlap(from, to) > 0)(OverlappingWindows),
      Option.when((from.endYear - from.startYear) != (to.endYear - to.startYear))(
        UnequalWindowLengths
      ),
      Option.when(worksWithoutYear > 0)(WorksWithoutYearExcluded),
      Option.when(!fromConverged || !toConverged)(CommunityNotConverged),
      Some(GroupingUnevaluated)
    ).flatten.sortBy(_.tag)
  }

  /**
   * The `window-comparison.v1` records (driver: one per consecutive pair, from the per-pair counts,
   * the counted works without a year and each window's label-propagation outcome).
   */
  def comparisons(
      counts: TemporalCounts,
      worksWithoutYear: Long,
      converged: Vector[Boolean],
      ctx: EdgeContext
  ): Either[Vector[String], Vector[WindowComparison]] = {
    val ws = ctx.windows
    val built = (0 until ws.size - 1).toVector.map { p =>
      val (a, b) = (ws(p), ws(p + 1))
      def pres(x: TemporalPresence) = counts.presence.getOrElse((p, x), 0L)
      def edges(m: Int) = counts.edges.getOrElse((p, m), 0L)
      val subject = WindowComparison.subjectOf(a, b)
      val c = WindowComparison(
        SchemaVersions.WindowComparison,
        a,
        b,
        p,
        inclusionRuleCompatible = true,
        WindowComparison.overlap(a, b),
        limitationsOf(a, b, worksWithoutYear, converged(p), converged(p + 1)),
        pres(TemporalPresence.InBoth),
        pres(TemporalPresence.OnlyFrom),
        pres(TemporalPresence.OnlyTo),
        edges(3),
        edges(1),
        edges(2),
        ctx.lineage,
        CanonicalGraphJob.provenance(ctx, SchemaVersions.WindowStructure)(subject)
      )
      val e = WindowComparison.contract.validate(c)
      if (e.isEmpty) Right(c) else Left(s"window comparison $subject: ${e.mkString("; ")}")
    }
    val bad = built.collect { case Left(w) => w }
    if (bad.nonEmpty) Left(bad) else Right(built.collect { case Right(c) => c })
  }

  /**
   * The `analysis-window.v1` records (contract C4; driver: one per window from the per-window
   * input, edge and structure totals and the counted works without a year).
   */
  def analysisWindows(
      inputs: Vector[WindowInputSummary],
      structure: Vector[StructureWindowSummary],
      worksWithoutYear: Long,
      ctx: EdgeContext
  ): Either[Vector[String], Vector[AnalysisWindow]] =
    if (inputs.map(_.window) != ctx.windows || structure.map(_.window) != ctx.windows)
      Left(Vector("analysis windows: the summaries are not the configured windows in order"))
    else {
      val built = inputs.zip(structure).zipWithIndex.map { case ((i, s), o) =>
        val subject = AnalysisWindow.subjectOf(i.window)
        val w = AnalysisWindow(
          SchemaVersions.AnalysisWindow,
          i.window,
          o,
          i.countedWorks,
          worksWithoutYear,
          i.identifiedResearchers,
          i.edges,
          s.components,
          s.communities,
          i.unresolvedMentionOccurrences,
          ctx.lineage,
          CanonicalGraphJob.provenance(ctx, SchemaVersions.PublicationTeam)(subject)
        )
        val e = AnalysisWindow.contract.validate(w)
        if (e.isEmpty) Right(w) else Left(s"analysis window $subject: ${e.mkString("; ")}")
      }
      val bad = built.collect { case Left(w) => w }
      if (bad.nonEmpty) Left(bad) else Right(built.collect { case Right(w) => w })
    }
}
