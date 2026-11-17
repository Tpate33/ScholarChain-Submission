package scholarchain.spark.analysis

import java.nio.file.Path

import scala.collection.immutable.SortedMap

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession

import scholarchain.core.*
import scholarchain.core.config.AnalysisRunSettings
import scholarchain.spark.graph.{
  CoauthorEdgeJob,
  EdgeContext,
  IdentityJob,
  MetricsWindowSummary,
  ResearcherMetricsJob,
  WindowInputSummary
}
import scholarchain.spark.input.VerifiedInput
import scholarchain.spark.output.{
  CoauthorEdgeRows,
  ParquetDataset,
  PublishedRun,
  ResearcherMetricsRows,
  RunPublisher
}

/**
 * Settings of one analysis job, all supplied by the caller.
 *
 * @param settings
 *   the analysis settings resolved from configuration (windows, graph policy, export format,
 *   digest)
 * @param codeVersion
 *   40-hex git commit of the analysis code (recorded in the run manifest and every lineage)
 * @param attempt
 *   run manifest attempt (1 for the first run of a manifest)
 * @param extraParameters
 *   additional run manifest parameters (token keys); they may not replace a chain parameter
 * @param partitions
 *   partitions of every shuffle and minimum input splits
 */
final case class AnalysisJobConfig(
    settings: AnalysisRunSettings,
    codeVersion: String,
    attempt: Int,
    extraParameters: SortedMap[String, String],
    partitions: Int
)

/**
 * The outcome of the collaboration run: the published run, the per-window input and edge counts,
 * and the per-window metrics totals.
 */
final case class CollaborationRunResult(
    published: PublishedRun,
    runId: String,
    lineage: AnalysisLineage,
    summaries: Vector[WindowInputSummary],
    edgeCount: Long,
    metricsCount: Long,
    metricsSummaries: Vector[MetricsWindowSummary]
)

/**
 * The collaboration-analysis run of SP-P6-02 steps 1A and 1B: from a verified input to one
 * published run holding the `coauthor-edge.v2` dataset (`edges/`) and the `researcher-metrics.v2`
 * dataset (`metrics/`). Later steps add structure, paths, windows and summaries.
 *
 * The run manifest names the published datasets in the chain parameter [[OutputsParameter]], so a
 * run of another output set is another run ID (the step-1A edge-only runs named only the edges).
 */
object AnalysisRun {

  val Stage = "spark.collaboration-analysis"
  val EdgesDataset = "edges"
  val MetricsDataset = "metrics"

  /** Run manifest parameter naming the published outputs (set by the run, never by the caller). */
  val OutputsParameter = "outputs"
  val Outputs = "coauthor-edges,researcher-metrics"

  private val A = AnalysisValidation.AnalysisManifestKeys
  private val G = AnalysisValidation.GroupingManifestKeys

  /** The single DBLP snapshot of the grouping run (counted works are DBLP works). */
  def dblpSnapshot(grouping: RunManifest): Either[Vector[String], String] =
    grouping.inputSnapshots.filter(_.source == SourceType.Dblp).map(_.snapshot) match {
      case Vector(s) => Right(s)
      case other =>
        Left(Vector(s"the grouping run has ${other.size} DBLP snapshots; exactly one is required"))
    }

  /**
   * The analysis run manifest: the run chain, the benchmark pair digest and the extra parameters.
   */
  def manifest(
      cfg: AnalysisJobConfig,
      input: VerifiedInput,
      pairs: BenchmarkPairList
  ): Either[Vector[String], RunManifest] = {
    val chain = SortedMap(
      A.BenchmarkPairsDigest -> BenchmarkPairList.digest(pairs),
      A.DecisionRunId -> input.decision.runId,
      A.GroupingRunId -> input.grouping.runId,
      A.InputRunId -> input.curated.runId,
      OutputsParameter -> Outputs
    )
    val clash = cfg.extraParameters.keySet.intersect(chain.keySet)
    if (clash.nonEmpty)
      Left(Vector(s"extra parameters may not replace ${clash.toVector.sorted.mkString(", ")}"))
    else {
      val m = RunManifest(
        SchemaVersions.RunManifest,
        Stage,
        cfg.codeVersion,
        cfg.settings.configDigest,
        input.grouping.manifest.inputSnapshots,
        chain ++ cfg.extraParameters,
        cfg.attempt
      )
      val e = RunManifest.validateFields(m)
      if (e.isEmpty) Right(m) else Left(e.map(_.toString))
    }
  }

  /** The lineage every record of the run carries (equals `AnalysisValidation.checkRunChain`'s). */
  def lineage(
      cfg: AnalysisJobConfig,
      input: VerifiedInput
  ): Either[Vector[String], AnalysisLineage] = {
    val gp = input.grouping.manifest.parameters
    val missing = Vector(G.GroupingPolicyVersion, G.MatchPolicyVersion, G.IdentityVersion)
      .filterNot(gp.contains)
    if (missing.nonEmpty) Left(missing.map(k => s"grouping manifest parameter $k is missing"))
    else {
      val l = AnalysisLineage(
        groupingRunId = input.grouping.runId,
        decisionRunId = input.decision.runId,
        inputRunId = input.curated.runId,
        codeVersion = cfg.codeVersion,
        configDigest = cfg.settings.configDigest,
        groupingPolicyVersion = gp(G.GroupingPolicyVersion),
        matchPolicyVersion = gp(G.MatchPolicyVersion),
        identityVersion = gp(G.IdentityVersion),
        groupingEvaluation = GroupingEvaluation.Unevaluated
      )
      val e = AnalysisLineage.check("lineage", l)
      if (e.nonEmpty) Left(e.map(_.toString))
      else if (l.identityVersion != cfg.settings.identityVersion)
        Left(
          Vector(
            s"the grouping run's identityVersion ${l.identityVersion} is not graph.identityVersion ${cfg.settings.identityVersion}"
          )
        )
      else Right(l)
    }
  }

  private def firstRefusals[T](results: RDD[Either[String, T]]): Vector[String] =
    results.filter(_.isLeft).map(_.left.toOption.get).take(20).toVector

  /**
   * Builds, checks and publishes, under `outputRoot`, the coauthor edges and the researcher metrics
   * of every configured window as one run. Order: counted teams; edges (refused on any edge
   * refusal); identities from the grouping and decision runs ([[IdentityJob]]); metrics from the
   * counted teams, the edges and the identities ([[ResearcherMetricsJob]], refused on any record
   * refusal); then one staged publication of both datasets ([[RunPublisher.publish]]).
   */
  def runCollaboration(
      spark: SparkSession,
      cfg: AnalysisJobConfig,
      input: VerifiedInput,
      pairs: BenchmarkPairList,
      outputRoot: Path
  ): Either[Vector[String], CollaborationRunResult] =
    if (cfg.settings.exportFormat != PartFormat.Parquet)
      Left(
        Vector(
          s"analysis.exportFormat ${cfg.settings.exportFormat.tag}: only parquet is implemented"
        )
      )
    else {
      val pairProblems = BenchmarkPairs.bindingProblems(
        pairs,
        input.grouping.runId,
        BenchmarkPairs.identifiedOf(input.graphInput.researchers)
      )
      if (pairProblems.nonEmpty) Left(pairProblems)
      else
        for {
          m <- manifest(cfg, input, pairs)
          runId <- RunId.of(m).left.map(e => Vector(e.toString))
          l <- lineage(cfg, input)
          snapshot <- dblpSnapshot(input.grouping.manifest)
          ctx = EdgeContext(
            cfg.settings.windows,
            cfg.settings.graphPolicyVersion,
            l,
            runId,
            snapshot
          )
          teams <- CoauthorEdgeJob.countedTeams(
            spark.sparkContext,
            input.teamParts,
            input.grouping.rowsOf(SchemaVersions.PublicationTeam),
            input.workParts,
            input.grouping.rowsOf(SchemaVersions.WorkAssignment),
            cfg.partitions
          )
          result <- {
            val results = CoauthorEdgeJob.edges(teams, ctx, cfg.partitions)
            try {
              val bad = firstRefusals(results)
              if (bad.nonEmpty) Left(bad)
              else {
                val edges = results.map(_.toOption.get)
                val n = edges.count()
                val summaries = CoauthorEdgeJob.summaries(teams, edges, ctx.windows)
                withMetrics(spark, cfg, input, teams, edges, ctx).flatMap {
                  case (metricResults, metrics, metricsCount, metricsSummaries) =>
                    try {
                      val edgeFrame = spark.createDataFrame(
                        edges.map(CoauthorEdgeRows.toRow),
                        CoauthorEdgeRows.schema
                      )
                      val metricsFrame = spark.createDataFrame(
                        metrics.map(ResearcherMetricsRows.toRow),
                        ResearcherMetricsRows.schema
                      )
                      RunPublisher
                        .publish(
                          spark,
                          outputRoot,
                          m,
                          Vector(
                            ParquetDataset(
                              EdgesDataset,
                              SchemaVersions.CoauthorEdgeV2,
                              edgeFrame,
                              n
                            ),
                            ParquetDataset(
                              MetricsDataset,
                              SchemaVersions.ResearcherMetricsV2,
                              metricsFrame,
                              metricsCount
                            )
                          ),
                          input.limits.maxMetadataBytes
                        )
                        .map(p =>
                          CollaborationRunResult(
                            p,
                            runId,
                            l,
                            summaries,
                            n,
                            metricsCount,
                            metricsSummaries
                          )
                        )
                    } finally {
                      metricResults.unpersist(blocking = false)
                      ()
                    }
                }
              }
            } finally {
              results.unpersist(blocking = false)
              ()
            }
          }
        } yield result
    }

  /**
   * The identities and the metrics of the run: the persisted metric results (the caller unpersists
   * them on success, this method on refusal), the checked metrics, their count and per-window
   * totals.
   */
  private def withMetrics(
      spark: SparkSession,
      cfg: AnalysisJobConfig,
      input: VerifiedInput,
      teams: RDD[PublicationTeam],
      edges: RDD[CoauthorEdgeV2],
      ctx: EdgeContext
  ): Either[
    Vector[String],
    (
        RDD[Either[String, ResearcherMetricsV2]],
        RDD[ResearcherMetricsV2],
        Long,
        Vector[MetricsWindowSummary]
    )
  ] =
    IdentityJob
      .identities(
        spark.sparkContext,
        input.grouping.partsOf(SchemaVersions.ResearcherAssignment),
        input.grouping.rowsOf(SchemaVersions.ResearcherAssignment),
        input.decision.partsOf(SchemaVersions.MatchDecision),
        input.decision.rowsOf(SchemaVersions.MatchDecision),
        cfg.partitions
      )
      .flatMap { identities =>
        val results =
          ResearcherMetricsJob.metrics(teams, edges, identities, ctx, cfg.partitions)
        val bad = firstRefusals(results)
        if (bad.nonEmpty) {
          results.unpersist(blocking = false)
          Left(bad)
        } else {
          val metrics = results.map(_.toOption.get)
          val count = metrics.count()
          Right((results, metrics, count, ResearcherMetricsJob.summaries(metrics, ctx.windows)))
        }
      }
}
