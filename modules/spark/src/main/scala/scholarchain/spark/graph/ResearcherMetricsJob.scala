package scholarchain.spark.graph

import java.nio.file.Path

import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel

import scholarchain.core.*

/**
 * Identity confidence (contract C5) of every identified researcher of the grouping run, distributed
 * (SP-P6-02 step 1B). The executable definition is `IdentityConfidence.derive` (core
 * Analysis.scala:317-378), applied per researcher to all of its grouping members (every researcher
 * assignment that carries the researcher ID, mention or source author profile) and to the
 * confidences of the decision run's decisions that support the group. Nothing is re-derived from
 * names or features and entity resolution is not re-run: the input is the verified grouping and
 * decision runs only.
 *
 * Partitioning, shuffles and memory:
 *   - researcher assignments and match decisions are read as text with `partitions` minimum splits
 *     and decoded per row through `Json.decode` (a decode failure refuses; rows are re-counted
 *     against the part manifest); only (decisionId, confidence) is kept of a decision;
 *   - shuffle 1: `distinct` of the decision IDs (a duplicate decision ID is refused, so a
 *     confidence is never chosen arbitrarily);
 *   - shuffle 2: `groupByKey` of the assignments by researcher ID (each group is bounded by the
 *     researcher's `groupSize`);
 *   - shuffle 3: `distinct` of the (supporting decision, researcher) references;
 *   - shuffle 4: `leftOuterJoin` of the references with the decisions on decision ID (a hash join,
 *     not a broadcast: the decision run grows with the input), then shuffle 5 `groupByKey` by
 *     researcher; shuffle 6 `leftOuterJoin` of the members with their confidences;
 *   - a supporting decision missing from the decision run is passed to `derive` as missing, which
 *     refuses that researcher (never a default confidence);
 *   - nothing per researcher reaches the driver; the result is consumed by
 *     [[ResearcherMetricsJob.metrics]].
 */
object IdentityJob {

  def decodeResearcher(line: String): Either[String, ResearcherAssignment] =
    Json.decode[ResearcherAssignment](line).left.map(_.mkString("; "))

  def decodeDecision(line: String): Either[String, (String, Double)] =
    Json
      .decode[MatchDecision](line)
      .left
      .map(_.mkString("; "))
      .map(d => (d.decisionId, d.confidence))

  /** Researcher ID -> its identity confidence, or why it cannot be derived. */
  def identities(
      sc: SparkContext,
      researcherParts: Vector[Path],
      researcherRows: Long,
      decisionParts: Vector[Path],
      decisionRows: Long,
      partitions: Int
  ): Either[Vector[String], RDD[(String, Either[String, IdentityConfidence])]] =
    for {
      researchers <- CoauthorEdgeJob.checked(
        "researcher assignments",
        CoauthorEdgeJob.lines(sc, researcherParts, partitions).map(decodeResearcher),
        researcherRows
      )
      decisions <- CoauthorEdgeJob.checked(
        "match decisions",
        CoauthorEdgeJob.lines(sc, decisionParts, partitions).map(decodeDecision),
        decisionRows
      )
      _ <- {
        val distinctIds = decisions.map(_._1).distinct(partitions).count()
        if (distinctIds == decisionRows) Right(())
        else
          Left(
            Vector(
              s"match decisions: $decisionRows rows but $distinctIds distinct decision IDs: a duplicate decision ID is refused"
            )
          )
      }
    } yield {
      val withId = researchers.flatMap(r => r.researcherId.toVector.map(id => (id, r)))
      val members = RDD.rddToPairRDDFunctions(withId).groupByKey(partitions)
      val refs = withId
        .flatMap { case (id, r) => r.supportingDecisionIds.map(d => (d, id)) }
        .distinct(partitions)
      val found = RDD
        .rddToPairRDDFunctions(refs)
        .leftOuterJoin(decisions, partitions)
        .map { case (d, (id, c)) => (id, (d, c)) }
      val confidences = RDD.rddToPairRDDFunctions(found).groupByKey(partitions)
      RDD
        .rddToPairRDDFunctions(members)
        .leftOuterJoin(confidences, partitions)
        .map { case (id, (ms, cs)) => (id, derive(id, ms.toVector, cs.toVector.flatten)) }
    }

  /** `IdentityConfidence.derive` with the confidences found for the researcher's decisions. */
  def derive(
      researcherId: String,
      members: Vector[ResearcherAssignment],
      found: Vector[(String, Option[Double])]
  ): Either[String, IdentityConfidence] = {
    val known = found.collect { case (d, Some(c)) => d -> c }.toMap
    IdentityConfidence.derive(researcherId, members, known.get).left.map(_.mkString("; "))
  }
}

/** One counted work of a researcher in a window: `n_w - 1` and `u_w` of the contract. */
final case class WorkStat(workId: String, coauthorOccurrences: Long, unresolved: Long)

/**
 * The per-(window, researcher) aggregate of the counted works: P_r, T_r, the unresolved
 * occurrences, the works with unresolved coauthors, M_r and the works that attain it.
 */
final case class MetricAcc(
    publications: Long,
    total: Long,
    unresolved: Long,
    worksWithUnresolved: Long,
    max: Long,
    maxWorks: Vector[String]
)

object MetricAcc {
  val empty: MetricAcc = MetricAcc(0L, 0L, 0L, 0L, -1L, Vector.empty)

  def of(s: WorkStat): MetricAcc =
    MetricAcc(
      1L,
      s.coauthorOccurrences,
      s.unresolved,
      if (s.unresolved > 0) 1L else 0L,
      s.coauthorOccurrences,
      Vector(s.workId)
    )

  /** Associative and commutative (the max works are a set, sorted when the record is built). */
  def merge(a: MetricAcc, b: MetricAcc): MetricAcc =
    MetricAcc(
      a.publications + b.publications,
      a.total + b.total,
      a.unresolved + b.unresolved,
      a.worksWithUnresolved + b.worksWithUnresolved,
      math.max(a.max, b.max),
      if (a.max > b.max) a.maxWorks
      else if (b.max > a.max) b.maxWorks
      else a.maxWorks ++ b.maxWorks
    )

  def add(a: MetricAcc, s: WorkStat): MetricAcc = merge(a, of(s))
}

/** C_r (distinct identified coauthors) and W_r (sum of the incident edges' weights). */
final case class Degree(distinct: Long, weighted: Long)

object Degree {
  val zero: Degree = Degree(0L, 0L)
}

/** Per-window totals of the metrics records (one small record per window reaches the driver). */
final case class MetricsWindowSummary(
    window: TimeWindow,
    researchers: Long,
    publications: Long,
    coauthorOccurrences: Long,
    unresolvedCoauthorOccurrences: Long,
    worksWithUnresolvedCoauthors: Long,
    distinctCoauthors: Long,
    weightedDegree: Long,
    maximumCoauthorsOnOnePaper: Long
)

/**
 * Researcher metrics v2 (contract C1, core Analysis.scala:500-651), distributed (SP-P6-02 step 1B).
 *
 * Definitions (core Analysis.scala:505-520; the executable definition is
 * `AnalysisValidation.checkMetrics`, AnalysisValidation.scala:429-500). For every counted work w of
 * researcher r whose year lies in the window, `n_w` = `teamSize`, `u_w` = unresolved mentions on
 * the team; `checkGraphInput` (applied by the verified reader) guarantees `n_w = i_w + u_w`:
 *   - P_r = number of such works; T_r = sum of (n_w - 1), unresolved included; unresolved
 *     occurrences = sum of u_w; works with unresolved coauthors = number of works with u_w > 0;
 *   - A_r = T_r.toDouble / P_r.toDouble (`ResearcherMetricsV2.average`);
 *   - M_r = max of (n_w - 1), with every work attaining it (sorted by UTF-8 bytes);
 *   - C_r = number of the researcher's edges in the window (identified coauthors only);
 *   - W_r = sum of the joint publication counts of those edges (identified only).
 *
 * C_r and W_r come from the published edges ([[CoauthorEdgeJob.edges]]), the other fields from the
 * counted teams; the record contract (`ResearcherMetricsV2.contract`, Analysis.scala:552-650)
 * checks W_r = T_r - unresolved on every record, so the two independent paths must agree or the run
 * is refused. Unresolved mentions never form an edge and are never folded into C_r or W_r. A work
 * without a year is in no window; a researcher appears only in the windows that hold one of its
 * counted works; works that are not counted (member-of-counted, enrichment-only) never reach the
 * teams.
 *
 * Partitioning, shuffles and memory:
 *   - shuffle 1: `aggregateByKey` of the (window index, researcher) work statistics into
 *     [[MetricAcc]] (bounded per key by P_r);
 *   - shuffle 2: `reduceByKey` of the edge endpoints into [[Degree]];
 *   - shuffle 3: `leftOuterJoin` of the aggregates with the degrees (a researcher without an edge
 *     has C_r = W_r = 0);
 *   - shuffle 4: `leftOuterJoin` by researcher ID with the identities ([[IdentityJob]]); a
 *     researcher without an identity is refused, never given a default;
 *   - shuffle 5: `sortBy` (window index, researcher ID) for a stable output order;
 *   - the results are persisted (MEMORY_AND_DISK); the caller unpersists them; only per-window
 *     totals and at most 20 refusal messages reach the driver.
 */
object ResearcherMetricsJob {

  private def sortedUtf8(xs: Vector[String]): Vector[String] =
    xs.sortWith((a, b) => Canonical.compareUtf8(a, b) < 0)

  /** Every (window index, researcher) occurrence of the counted teams. */
  def occurrences(
      teams: RDD[PublicationTeam],
      windows: Vector[TimeWindow]
  ): RDD[((Int, String), WorkStat)] =
    teams.flatMap { t =>
      t.year.toVector.flatMap { y =>
        val stat =
          WorkStat(t.workId, t.teamSize.toLong - 1L, t.unresolvedMentionIds.size.toLong)
        windows.indices
          .filter(i => windows(i).contains(y))
          .flatMap(wi => t.researcherIds.distinct.map(r => ((wi, r), stat)))
      }
    }

  /** C_r and W_r per (window index, researcher) from the edges. */
  def degrees(
      edges: RDD[CoauthorEdgeV2],
      windows: Vector[TimeWindow],
      partitions: Int
  ): RDD[((Int, String), Degree)] =
    RDD
      .rddToPairRDDFunctions(edges.flatMap { e =>
        val wi = windows.indexOf(e.window)
        val d = Degree(1L, e.jointPublicationCount)
        Vector(((wi, e.researcherA), d), ((wi, e.researcherB), d))
      })
      .reduceByKey((a, b) => Degree(a.distinct + b.distinct, a.weighted + b.weighted), partitions)

  /** One metrics record, checked against its record contract. */
  def buildMetrics(
      ctx: EdgeContext,
      wi: Int,
      researcherId: String,
      acc: MetricAcc,
      degree: Degree,
      identity: Option[Either[String, IdentityConfidence]]
  ): Either[String, ResearcherMetricsV2] =
    identity match {
      case None =>
        Left(s"researcher $researcherId has no grouping member: no identity confidence")
      case Some(Left(why)) => Left(s"identity of $researcherId: $why")
      case Some(Right(id)) =>
        val m = ResearcherMetricsV2(
          SchemaVersions.ResearcherMetricsV2,
          researcherId,
          ctx.windows(wi),
          acc.publications,
          degree.distinct,
          acc.total,
          acc.unresolved,
          acc.worksWithUnresolved,
          ResearcherMetricsV2.average(acc.total, acc.publications),
          acc.max,
          sortedUtf8(acc.maxWorks),
          degree.weighted,
          id,
          ctx.lineage,
          ctx.provenanceOf(researcherId)
        )
        val errors = ResearcherMetricsV2.contract.validate(m)
        if (errors.isEmpty) Right(m)
        else Left(s"metrics of $researcherId in window $wi: ${errors.mkString("; ")}")
    }

  /** The metrics results of every window, sorted; persisted (the caller unpersists). */
  def metrics(
      teams: RDD[PublicationTeam],
      edges: RDD[CoauthorEdgeV2],
      identities: RDD[(String, Either[String, IdentityConfidence])],
      ctx: EdgeContext,
      partitions: Int
  ): RDD[Either[String, ResearcherMetricsV2]] = {
    val acc = RDD
      .rddToPairRDDFunctions(occurrences(teams, ctx.windows))
      .aggregateByKey(MetricAcc.empty, partitions)(MetricAcc.add, MetricAcc.merge)
    val withDegree = RDD
      .rddToPairRDDFunctions(acc)
      .leftOuterJoin(degrees(edges, ctx.windows, partitions), partitions)
      .map { case ((wi, r), (a, d)) => (r, (wi, a, d.getOrElse(Degree.zero))) }
    RDD
      .rddToPairRDDFunctions(withDegree)
      .leftOuterJoin(identities, partitions)
      .map { case (r, ((wi, a, d), id)) => ((wi, r), buildMetrics(ctx, wi, r, a, d, id)) }
      .sortBy(_._1, ascending = true, partitions)
      .map(_._2)
      .persist(StorageLevel.MEMORY_AND_DISK)
  }

  /** Per-window totals (one record per configured window, zeros for an empty window). */
  def summaries(
      metrics: RDD[ResearcherMetricsV2],
      windows: Vector[TimeWindow]
  ): Vector[MetricsWindowSummary] = {
    val zero = MetricsWindowSummary(TimeWindow(0, 0), 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L)
    val got = RDD
      .rddToPairRDDFunctions(metrics.map { m =>
        (
          windows.indexOf(m.window),
          MetricsWindowSummary(
            m.window,
            1L,
            m.publicationCount,
            m.totalCoauthorOccurrences,
            m.unresolvedCoauthorOccurrences,
            m.worksWithUnresolvedCoauthors,
            m.distinctCoauthorCount,
            m.weightedDegree,
            m.maximumCoauthorsOnOnePaper
          )
        )
      })
      .reduceByKey((a, b) =>
        MetricsWindowSummary(
          a.window,
          a.researchers + b.researchers,
          a.publications + b.publications,
          a.coauthorOccurrences + b.coauthorOccurrences,
          a.unresolvedCoauthorOccurrences + b.unresolvedCoauthorOccurrences,
          a.worksWithUnresolvedCoauthors + b.worksWithUnresolvedCoauthors,
          a.distinctCoauthors + b.distinctCoauthors,
          a.weightedDegree + b.weightedDegree,
          math.max(a.maximumCoauthorsOnOnePaper, b.maximumCoauthorsOnOnePaper)
        )
      )
      .collectAsMap()
    windows.indices.toVector.map(i => got.getOrElse(i, zero.copy(window = windows(i))))
  }
}
