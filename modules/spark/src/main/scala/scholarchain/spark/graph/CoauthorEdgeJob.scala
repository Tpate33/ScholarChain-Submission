package scholarchain.spark.graph

import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel

import scholarchain.core.*
import scholarchain.spark.store.StorePath

/**
 * What every edge record of one analysis run shares: the configured windows (in configured order),
 * the graph policy, the lineage and the analysis run, and the DBLP snapshot of the grouping run.
 */
final case class EdgeContext(
    windows: Vector[TimeWindow],
    graphPolicyVersion: String,
    lineage: AnalysisLineage,
    analysisRunId: String,
    snapshot: String
) {

  /** The provenance of an edge: derived from publication teams, graph policy, no model. */
  def provenanceOf(edgeId: String): Provenance = Provenance(
    sourceDataset = SourceType.Derived,
    sourceRecordId = edgeId,
    snapshot = snapshot,
    runId = analysisRunId,
    sourceSchemaVersion = SchemaVersions.PublicationTeam,
    policyVersion = Some(graphPolicyVersion),
    modelVersion = None,
    promptVersion = None
  )
}

/** Counts of the graph input of one window (all from counted teams; unresolved kept separate). */
final case class WindowInputSummary(
    window: TimeWindow,
    countedWorks: Long,
    identifiedResearchers: Long,
    unresolvedMentionOccurrences: Long,
    edges: Long,
    jointPublications: Long
)

/**
 * Graph input and coauthor edges v2 (contract C2), distributed (SP-P6-02 step 1).
 *
 * Graph scope (binding decision 1, O9): the teams of the grouping run (`PublicationTeam` parts),
 * never Hadoop TeamJob output, restricted to works whose `WorkAssignment.graphEligibility` is
 * `counted`. Endpoints are the team's identified `researcherIds` only (binding decision 2);
 * `unresolvedMentionIds` never form an edge and are counted separately ([[WindowInputSummary]]). A
 * team without a year is in no window. Self-pairs cannot arise (researcher IDs of a team are
 * distinct by contract, and pairs are i < j over the distinct list); pairs are canonical (UTF-8
 * byte order, `Canonical.compareUtf8`); a pair seen on several works is aggregated into one edge
 * per window with its per-year counts and every supporting work. The executable definition is
 * `AnalysisValidation.checkEdges`.
 *
 * Partitioning, shuffles and memory:
 *   - teams and work assignments are read as text with `partitions` minimum splits and decoded per
 *     row through `Json.decode` (a decode failure refuses the run, rows are re-counted);
 *   - shuffle 1: join of teams with the counted work IDs on `workId` (`partitions` partitions; a
 *     plain hash join, not a broadcast, because the number of works grows with the input);
 *   - shuffle 2: `aggregateByKey` on (window index, researcherA, researcherB), each value the
 *     (year, workId) list of one pair, bounded by the pair's joint publications;
 *   - shuffle 3: `sortBy` (range partitioning) on (window, researcherA, researcherB) for a stable
 *     output order (IDs are printable ASCII, so String order is UTF-8 byte order);
 *   - the edge results are persisted (MEMORY_AND_DISK) because they are counted, checked and
 *     written; the caller unpersists them;
 *   - only per-window counts, at most 20 refusal messages and nothing per edge reach the driver.
 */
object CoauthorEdgeJob {

  private def uri(p: StorePath): String = p.toUri.toString

  /** Every part location `textFile` would split or glob-expand (CR-P6-26 n3). */
  private[graph] def globProblems(parts: Vector[StorePath]): Vector[String] =
    parts.flatMap(StorePath.globProblem)

  /**
   * The text rows of `parts`, read by Spark's own Hadoop FileSystem for each location's URI
   * (`file:`, `hdfs:`, `s3a:`), so every executor reads them directly (SP-P6-04). `textFile` takes
   * one comma-separated string of glob patterns, so a part location with a comma or a glob
   * character is refused (IllegalArgumentException; [[countedTeams]] refuses it first).
   */
  private[graph] def lines(
      sc: SparkContext,
      parts: Vector[StorePath],
      partitions: Int
  ): RDD[String] = {
    val bad = globProblems(parts)
    require(bad.isEmpty, bad.mkString("; "))
    if (parts.isEmpty) sc.emptyRDD[String]
    else sc.textFile(parts.map(uri).mkString(","), partitions)
  }

  // Decoders are methods of this top-level object, so task closures reference the object
  // statically and never capture a (non-serializable) RecordContract instance.
  def decodeTeam(line: String): Either[String, PublicationTeam] =
    Json.decode[PublicationTeam](line).left.map(_.mkString("; "))

  def decodeWork(line: String): Either[String, WorkAssignment] =
    Json.decode[WorkAssignment](line).left.map(_.mkString("; "))

  /** Decoded rows, or the first refusals; refuses when the decoded count differs from `rows`. */
  private[graph] def checked[T](label: String, rdd: RDD[Either[String, T]], rows: Long)(using
      ct: scala.reflect.ClassTag[T]
  ): Either[Vector[String], RDD[T]] = {
    val bad = rdd.filter(_.isLeft).map(_.left.toOption.get).take(20).toVector
    if (bad.nonEmpty) Left(bad.map(w => s"$label: $w"))
    else {
      val good = rdd.map(_.toOption.get)
      val n = good.count()
      if (n != rows) Left(Vector(s"$label: decoded $n rows, the part manifest lists $rows"))
      else Right(good)
    }
  }

  /**
   * The counted teams of the grouping run: teams joined with the work assignments whose
   * graphEligibility is counted. `teamRows` and `workRows` are the part manifest's row counts.
   */
  def countedTeams(
      sc: SparkContext,
      teamParts: Vector[StorePath],
      teamRows: Long,
      workParts: Vector[StorePath],
      workRows: Long,
      partitions: Int
  ): Either[Vector[String], RDD[PublicationTeam]] =
    for {
      _ <- {
        val bad = globProblems(teamParts ++ workParts)
        if (bad.isEmpty) Right(()) else Left(bad.map(w => s"input parts: $w"))
      }
      teams <- checked("teams", lines(sc, teamParts, partitions).map(decodeTeam), teamRows)
      works <- checked("works", lines(sc, workParts, partitions).map(decodeWork), workRows)
    } yield {
      val counted = works
        .filter(_.graphEligibility == GraphEligibility.Counted)
        .map(w => (w.workId, true))
      RDD
        .rddToPairRDDFunctions(teams.map(t => (t.workId, t)))
        .join(counted, partitions)
        .map(_._2._1)
    }

  /** Every (window index, canonical pair) occurrence of the counted teams, with year and work. */
  def pairOccurrences(
      teams: RDD[PublicationTeam],
      windows: Vector[TimeWindow]
  ): RDD[((Int, String, String), (Int, String))] =
    teams.flatMap { t =>
      t.year.toVector.flatMap { y =>
        val ids = t.researcherIds.distinct
        windows.indices.filter(i => windows(i).contains(y)).flatMap { wi =>
          for {
            i <- ids.indices
            j <- (i + 1) until ids.size
          } yield {
            val (a, b) =
              if (Canonical.compareUtf8(ids(i), ids(j)) < 0) (ids(i), ids(j)) else (ids(j), ids(i))
            ((wi, a, b), (y, t.workId))
          }
        }
      }
    }

  /** One edge from the aggregated occurrences of one pair in one window (core builder). */
  def buildEdge(
      ctx: EdgeContext,
      wi: Int,
      a: String,
      b: String,
      occurrences: Vector[(Int, String)]
  ): Either[String, CoauthorEdgeV2] = {
    val years = occurrences
      .groupBy(_._1)
      .toVector
      .sortBy(_._1)
      .map { case (y, xs) => YearCount(y, xs.size.toLong) }
    CoauthorEdgeV2
      .build(
        a,
        b,
        ctx.windows(wi),
        years,
        occurrences.map(_._2),
        ctx.lineage,
        ctx.graphPolicyVersion,
        ctx.provenanceOf
      )
      .left
      .map(e => s"edge $a $b in window $wi: ${e.mkString("; ")}")
  }

  /** The edge results of every window, sorted; persisted (the caller unpersists). */
  def edges(
      teams: RDD[PublicationTeam],
      ctx: EdgeContext,
      partitions: Int
  ): RDD[Either[String, CoauthorEdgeV2]] = {
    val grouped = RDD
      .rddToPairRDDFunctions(pairOccurrences(teams, ctx.windows))
      .aggregateByKey(Vector.empty[(Int, String)], partitions)(_ :+ _, _ ++ _)
    grouped
      .map { case ((wi, a, b), occ) => ((wi, a, b), buildEdge(ctx, wi, a, b, occ)) }
      .sortBy(_._1, ascending = true, partitions)
      .map(_._2)
      .persist(StorageLevel.MEMORY_AND_DISK)
  }

  /** Per-window input and edge counts (one small record per window reaches the driver). */
  def summaries(
      teams: RDD[PublicationTeam],
      edges: RDD[CoauthorEdgeV2],
      windows: Vector[TimeWindow]
  ): Vector[WindowInputSummary] = {
    val perWindow = teams.flatMap { t =>
      t.year.toVector.flatMap(y => windows.indices.filter(i => windows(i).contains(y)).map(_ -> t))
    }
    val works =
      RDD.rddToPairRDDFunctions(perWindow.map(x => (x._1, 1L))).reduceByKey(_ + _).collectAsMap()
    val unresolved = RDD
      .rddToPairRDDFunctions(perWindow.map(x => (x._1, x._2.unresolvedMentionIds.size.toLong)))
      .reduceByKey(_ + _)
      .collectAsMap()
    val researchers = RDD
      .rddToPairRDDFunctions(
        perWindow
          .flatMap(x => x._2.researcherIds.map(r => (x._1, r)))
          .distinct()
          .map(x => (x._1, 1L))
      )
      .reduceByKey(_ + _)
      .collectAsMap()
    val edgeCounts = RDD
      .rddToPairRDDFunctions(
        edges.map(e => (windows.indexOf(e.window), (1L, e.jointPublicationCount)))
      )
      .reduceByKey((x, y) => (x._1 + y._1, x._2 + y._2))
      .collectAsMap()
    windows.indices.toVector.map { i =>
      val (n, j) = edgeCounts.getOrElse(i, (0L, 0L))
      WindowInputSummary(
        windows(i),
        works.getOrElse(i, 0L),
        researchers.getOrElse(i, 0L),
        unresolved.getOrElse(i, 0L),
        n,
        j
      )
    }
  }
}
