package scholarchain.spark.analysis

import java.nio.file.Path

import scala.collection.immutable.SortedMap

import org.apache.spark.sql.SparkSession

import scholarchain.core.*
import scholarchain.core.config.AnalysisRunSettings
import scholarchain.spark.graph.{CoauthorEdgeJob, EdgeContext, WindowInputSummary}
import scholarchain.spark.input.VerifiedInput
import scholarchain.spark.output.{CoauthorEdgeRows, ParquetDataset, PublishedRun, RunPublisher}

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

/** The outcome of the edge stage: the published run and the per-window counts. */
final case class EdgeRunResult(
    published: PublishedRun,
    runId: String,
    lineage: AnalysisLineage,
    summaries: Vector[WindowInputSummary],
    edgeCount: Long
)

/**
 * The collaboration-analysis run of SP-P6-02 step 1: from a verified input to the published
 * `coauthor-edge.v2` dataset. Later steps add metrics, structure, paths, windows and summaries to
 * the same run.
 */
object AnalysisRun {

  val Stage = "spark.collaboration-analysis"
  val EdgesDataset = "edges"

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
      A.InputRunId -> input.curated.runId
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

  /**
   * Builds, checks and publishes the coauthor edges of every configured window under `outputRoot`.
   */
  def runEdges(
      spark: SparkSession,
      cfg: AnalysisJobConfig,
      input: VerifiedInput,
      pairs: BenchmarkPairList,
      outputRoot: Path
  ): Either[Vector[String], EdgeRunResult] =
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
              val bad = results.filter(_.isLeft).map(_.left.toOption.get).take(20).toVector
              if (bad.nonEmpty) Left(bad)
              else {
                val edges = results.map(_.toOption.get)
                val n = edges.count()
                val summaries = CoauthorEdgeJob.summaries(teams, edges, ctx.windows)
                val frame =
                  spark.createDataFrame(edges.map(CoauthorEdgeRows.toRow), CoauthorEdgeRows.schema)
                RunPublisher
                  .publish(
                    spark,
                    outputRoot,
                    m,
                    Vector(ParquetDataset(EdgesDataset, SchemaVersions.CoauthorEdgeV2, frame, n)),
                    input.limits.maxMetadataBytes
                  )
                  .map(p => EdgeRunResult(p, runId, l, summaries, n))
              }
            } finally {
              results.unpersist(blocking = false)
              ()
            }
          }
        } yield result
    }
}
