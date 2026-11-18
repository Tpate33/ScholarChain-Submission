package scholarchain.spark.analysis

import java.nio.file.Path

import scala.collection.immutable.SortedMap
import scala.collection.mutable
import scala.reflect.ClassTag

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.sql.types.StructType
import org.apache.spark.storage.StorageLevel

import scholarchain.core.*
import scholarchain.core.config.AnalysisRunSettings
import scholarchain.spark.graph.{
  CanonicalGraphJob,
  CanonicalInputs,
  Checkpointing,
  CoauthorEdgeJob,
  EdgeContext,
  GraphStructure,
  GraphStructureJob,
  IdentityJob,
  LabelPropagationOutcome,
  MetricsWindowSummary,
  PathJob,
  PathLengthStats,
  PathSearchStats,
  ResearcherMetricsJob,
  ResearcherWindowRecords,
  StructureContext,
  StructureRecordsJob,
  StructureWindowSummary,
  SummaryJob,
  TemporalJob,
  WindowInputSummary
}
import scholarchain.spark.input.VerifiedInput
import scholarchain.spark.store.{NioRunStore, RunStore, StorePath}
import scholarchain.spark.output.{
  AnalysisWindowRows,
  BridgeMeasureRows,
  CoauthorEdgeRows,
  CommunityMembershipRows,
  ComponentMembershipRows,
  CorenessRows,
  CsvDataset,
  EntitySummaryInputRows,
  EvidencePathRows,
  GraphEdgeRows,
  GraphNodeRows,
  LoadManifests,
  NeptuneCsvParts,
  ParquetDataset,
  PathLengthEstimateRows,
  PublishedLoads,
  PublishedRun,
  ResearcherMetricsRows,
  RunPublisher,
  SummaryInputRows,
  TemporalChangeRows,
  WindowComparisonRows,
  WindowStructureRows
}

/**
 * Settings of one analysis job, all supplied by the caller.
 *
 * @param settings
 *   the analysis settings resolved from configuration (windows, graph policy, community and bridge
 *   settings, export format, digest)
 * @param codeVersion
 *   40-hex git commit of the analysis code (recorded in the run manifest and every lineage)
 * @param attempt
 *   run manifest attempt (1 for the first run of a manifest)
 * @param extraParameters
 *   additional run manifest parameters (token keys); they may not replace a chain parameter and may
 *   not use the reserved prefix `community.`
 * @param partitions
 *   partitions of every shuffle and minimum input splits
 * @param checkpointing
 *   checkpoint root and interval of the iterative graph algorithms; a local root must lie outside
 *   the output root. Not part of the run identity (it does not change any result)
 */
final case class AnalysisJobConfig(
    settings: AnalysisRunSettings,
    codeVersion: String,
    attempt: Int,
    extraParameters: SortedMap[String, String],
    partitions: Int,
    checkpointing: Checkpointing
)

/**
 * The outcome of the collaboration run: the published run, the per-window input and edge counts,
 * the per-window metrics totals, the record counts of the structure datasets, the per-window
 * structure totals (components, communities, label-propagation outcome, coreness histogram), and
 * the iteration counts of the graph algorithms.
 */
final case class CollaborationRunResult(
    published: PublishedRun,
    runId: String,
    lineage: AnalysisLineage,
    summaries: Vector[WindowInputSummary],
    edgeCount: Long,
    metricsCount: Long,
    metricsSummaries: Vector[MetricsWindowSummary],
    componentCount: Long,
    communityCount: Long,
    bridgeCount: Long,
    corenessCount: Long,
    structureSummaries: Vector[StructureWindowSummary],
    componentIterations: Int,
    corenessRounds: Int,
    checkpoints: Int,
    canonical: CanonicalSummary,
    windowStructures: Vector[WindowStructure],
    step2C: Step2CSummary
)

/**
 * What SP-P6-03 step 2C adds to a run result (counts and per-window or per-pair records only): the
 * `analysis-window.v1` records, the stored-path counts, the path-length estimate count and
 * statistics (None when the `analysis.pathLength*` keys are unset), the window comparisons, the
 * temporal-change, summary-input and entity-summary counts, the counted works without a year, and
 * the published load manifests.
 */
final case class Step2CSummary(
    analysisWindows: Vector[AnalysisWindow],
    pathCount: Long,
    pathStats: PathSearchStats,
    pathLength: Option[(Long, PathLengthStats)],
    windowComparisons: Vector[WindowComparison],
    temporalChangeCount: Long,
    summaryInputCount: Long,
    entitySummaryCount: Long,
    worksWithoutYear: Long,
    loads: PublishedLoads
)

/**
 * Counts of the canonical tables of one run (SP-P6-03 step 2B): nodes per label and edges per class
 * (frozen tags, `CanonicalGraph` order, zeros included), the venue, topic and institution
 * references without a curated record (never an edge), the invariant failures (all 0 in a published
 * run) and the Neptune CSV parts per dataset.
 */
final case class CanonicalSummary(
    nodesByLabel: Vector[(String, Long)],
    edgesByClass: Vector[(String, Long)],
    unmatched: UnmatchedReferences,
    invariantFailures: Vector[(String, Long)],
    csvParts: Vector[(String, Int)]
) {
  def nodeCount: Long = nodesByLabel.map(_._2).sum
  def edgeCount: Long = edgesByClass.map(_._2).sum
}

/**
 * The collaboration-analysis run of SP-P6-02 steps 1A and 1B and SP-P6-03 steps 2A and 2B: from a
 * verified input to one published run holding `coauthor-edge.v2` (`edges/`),
 * `researcher-metrics.v2` (`metrics/`), `component-membership.v1` (`components/`),
 * `community-membership.v1` (`communities/`), `bridge-measure.v1` (`bridges/`), `coreness.v1`
 * (`coreness/`), and (step 2B) the canonical tables `graph-node.v2` (`graph-nodes/`) and
 * `graph-edge.v2` (`graph-edges/`), their Neptune bulk-load CSV parts (one directory per load role,
 * `researcher-vertices/` .. `has-topic-edges/`, core `NeptuneCsv` layout) and `window-structure.v1`
 * (`window-structures/`). Step 2C adds `analysis-window.v1` (`analysis-windows/`),
 * `evidence-path.v1` (`evidence-paths/`), `path-length-estimate.v1` (`path-length-estimates/`, only
 * when the `analysis.pathLength*` keys are set), `window-comparison.v1` (`window-comparisons/`),
 * `researcher-temporal-change.v1` (`temporal-changes/`), `semantic-summary-input.v1`
 * (`summary-inputs/`) and `entity-summary-input.v1` (`entity-summary-inputs/`) in the same staged,
 * verified run, then the four `store-load-manifest.v1` files beside it ([[LoadManifests]],
 * `load=<run hex>/`).
 *
 * Step 2C stages (P = `partitions`; each job's Scaladoc lists its shuffles):
 *   - analysis windows: driver, from the per-window input, edge and structure totals and one count
 *     of the counted teams without a year (one action);
 *   - stored paths ([[PathJob.shortestPaths]], [[PathJob.evidencePaths]]): one distributed BFS
 *     level per hop (frontier join arcs, distinct, subtract seen: three shuffles), one walk step
 *     per hop (two joins, one bounded aggregation), then the hop-edge and node-identity joins and a
 *     cogroup; the arcs keep the structure's hash partitioner; levels persisted and every
 *     `checkpointing.interval`-th one checkpointed;
 *   - path-length estimates ([[PathJob.componentDistances]]): bounded per-component sample
 *     aggregation, multi-source BFS levels as above until no new vertex, per-level counts;
 *   - temporal ([[TemporalJob]]): the five per-researcher datasets `partitionBy` P and joined
 *     co-partitioned, per-pair grouping, coauthor-turnover and community-overlap reductions; the
 *     comparisons on the driver from per-pair counts;
 *   - summaries ([[SummaryJob]]): profiles from the joined records (no shuffle), communities and
 *     paths with one join each, entity summaries with joins to the canonical tables, a union and a
 *     per-subject grouping; every output `sortBy` its key;
 *   - publication as before (one more Parquet dataset each), then the load manifests (driver: the
 *     published part manifest and the SHA-256 of `_parts.json`).
 * Driver memory in step 2C: per-window and per-pair records (analysis windows, comparisons), the
 * search list and per-level search sets, counts; never a vertex, edge, path or record list.
 * Invariants checked before publication: one researcher-window record per metrics record, one
 * comparison per consecutive window pair, as many temporal changes as the comparisons count, and
 * one semantic-summary input per metrics record, community and stored path.
 *
 * Step 2B partitioning and driver use: the canonical tables are built distributed
 * ([[CanonicalGraphJob]], its shuffles documented there); the invariant counts, the per-label and
 * per-class counts (at most 12 entries) and the CSV row count of each partition reach the driver;
 * the window structures are built on the driver from the per-window structure totals (one record
 * per window) and written from a one-partition RDD.
 *
 * The run manifest names the published datasets in the chain parameter [[OutputsParameter]], and
 * records the label-propagation outcome of every window ([[convergenceParameters]]: whether it
 * converged and after how many iterations), so a run of another output set or another outcome is
 * another run ID. The outcome is a deterministic function of the input and the configuration, so
 * the same input and configuration always give the same manifest.
 */
object AnalysisRun {

  val Stage = "spark.collaboration-analysis"
  val EdgesDataset = "edges"
  val MetricsDataset = "metrics"
  val ComponentsDataset = "components"
  val CommunitiesDataset = "communities"
  val BridgesDataset = "bridges"
  val CorenessDataset = "coreness"
  val NodesDataset = "graph-nodes"
  val GraphEdgesDataset = "graph-edges"
  val WindowStructuresDataset = "window-structures"
  // SP-P6-03 step 2C (named like their LoadRoles roles).
  val AnalysisWindowsDataset = "analysis-windows"
  val EvidencePathsDataset = "evidence-paths"
  val PathLengthEstimatesDataset = "path-length-estimates"
  val WindowComparisonsDataset = "window-comparisons"
  val TemporalChangesDataset = "temporal-changes"
  val SummaryInputsDataset = "summary-inputs"
  val EntitySummaryInputsDataset = "entity-summary-inputs"

  /** Run manifest parameter naming the published outputs (set by the run, never by the caller). */
  val OutputsParameter = "outputs"

  /**
   * The outputs of a run whose configuration sets no `analysis.pathLength*` key. The load manifests
   * are written beside the run (they name its `_parts.json` hash) and are not listed.
   */
  val Outputs =
    "coauthor-edges,researcher-metrics,component-memberships,community-memberships,bridge-measures,coreness," +
      "graph-nodes,graph-edges,neptune-bulk-load-csv,window-structures,analysis-windows,evidence-paths," +
      "window-comparisons,temporal-changes,summary-inputs,entity-summary-inputs"

  /** The outputs of a run that also stores the path-length estimates. */
  val OutputsWithPathLength: String = Outputs + ",path-length-estimates"

  /** The `outputs` parameter of a run with these settings. */
  def outputsFor(s: AnalysisRunSettings): String =
    if (s.pathLengthSampling.isDefined) OutputsWithPathLength else Outputs

  /** The only implemented `community.algorithm` and `bridge.algorithm`. */
  val CommunityAlgorithm = "label-propagation"
  val BridgeAlgorithm = "cross-community-degree"

  /** Reserved prefix of the computed manifest parameters. */
  val ComputedPrefix = "community."

  /** Core `AnalysisManifestKeys.communityConverged` (DE-P6-03), the only spelling of the key. */
  def convergedKey(w: TimeWindow): String =
    AnalysisValidation.AnalysisManifestKeys.communityConverged(w)

  /** Core `AnalysisManifestKeys.communityIterations` (DE-P6-03). */
  def iterationsKey(w: TimeWindow): String =
    AnalysisValidation.AnalysisManifestKeys.communityIterations(w)

  /** The label-propagation outcome of every window as run manifest parameters. */
  def convergenceParameters(outcomes: Vector[LabelPropagationOutcome]): SortedMap[String, String] =
    SortedMap.from(outcomes.flatMap { o =>
      Vector(
        convergedKey(o.window) -> o.converged.toString,
        iterationsKey(o.window) -> o.iterations.toString
      )
    })

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
   * The analysis run manifest: the run chain, the benchmark pair digest, the outputs, the computed
   * parameters (`computed`, the label-propagation outcomes) and the extra parameters.
   */
  def manifest(
      cfg: AnalysisJobConfig,
      input: VerifiedInput,
      pairs: BenchmarkPairList,
      computed: SortedMap[String, String]
  ): Either[Vector[String], RunManifest] = {
    val chain = SortedMap(
      A.BenchmarkPairsDigest -> BenchmarkPairList.digest(pairs),
      A.DecisionRunId -> input.decision.runId,
      A.GroupingRunId -> input.grouping.runId,
      A.InputRunId -> input.curated.runId,
      OutputsParameter -> outputsFor(cfg.settings)
    ) ++ computed
    val clash = cfg.extraParameters.keySet.intersect(chain.keySet)
    val reserved = cfg.extraParameters.keySet.filter(_.startsWith(ComputedPrefix))
    if (clash.nonEmpty)
      Left(Vector(s"extra parameters may not replace ${clash.toVector.sorted.mkString(", ")}"))
    else if (reserved.nonEmpty)
      Left(
        Vector(
          s"extra parameters may not use the reserved prefix $ComputedPrefix: ${reserved.toVector.sorted.mkString(", ")}"
        )
      )
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
   * Refusals of the structure settings before anything is computed: only `label-propagation` and
   * `cross-community-degree` are implemented; the propagation is deterministic, so
   * `community.randomSeed` must be unset; `community.maxIterations` >= 1; a local checkpoint root
   * outside the output root.
   */
  def structureProblems(cfg: AnalysisJobConfig, outputRoot: Path): Vector[String] =
    structureProblems(cfg, StorePath.local(outputRoot))

  /**
   * As the `Path` overload, for an output root in any store: the checkpoint root (an absolute local
   * path or a URI) may neither lie inside the output root nor contain it (same scheme and
   * authority); a checkpoint root that cannot be compared is refused.
   */
  def structureProblems(cfg: AnalysisJobConfig, outputRoot: StorePath): Vector[String] = {
    val s = cfg.settings
    Vector(
      Option.when(s.communityAlgorithm != CommunityAlgorithm)(
        s"community.algorithm ${s.communityAlgorithm}: only $CommunityAlgorithm is implemented"
      ),
      Option.when(s.bridgeAlgorithm != BridgeAlgorithm)(
        s"bridge.algorithm ${s.bridgeAlgorithm}: only $BridgeAlgorithm is implemented"
      ),
      Option.when(s.communityParameters.contains("randomSeed"))(
        s"community.randomSeed is set, but $CommunityAlgorithm here is deterministic (no random order): unset it"
      ),
      Option.when(maxIterations(s).forall(_ < 1))(
        s"community.maxIterations ${s.communityParameters.getOrElse("maxIterations", "(missing)")} must be an integer >= 1"
      ),
      GraphStructureJob.checkpointProblem(cfg.checkpointing),
      GraphStructureJob.checkpointProblem(cfg.checkpointing) match {
        case Some(_) => None // reported above
        case None =>
          checkpointLocation(cfg.checkpointing) match {
            case Left(w) =>
              Some(s"the checkpoint directory cannot be compared with the output root: $w")
            case Right(p) =>
              Option.when(p.startsWith(outputRoot) || outputRoot.startsWith(p))(
                s"the checkpoint directory $p overlaps the output root $outputRoot"
              )
          }
      }
    ).flatten
  }

  /** The checkpoint root as a location (an absolute local path or a URI). */
  def checkpointLocation(c: Checkpointing): Either[String, StorePath] =
    if (c.dir.startsWith("/")) Right(StorePath.local(java.nio.file.Paths.get(c.dir)))
    else StorePath.parse(c.dir)

  private def maxIterations(s: AnalysisRunSettings): Option[Int] =
    s.communityParameters.get("maxIterations").flatMap(_.toIntOption)

  private def firstRefusals[T](results: RDD[Either[String, T]]): Vector[String] =
    results.filter(_.isLeft).map(_.left.toOption.get).take(20).toVector

  /** Persisted, checked records of one dataset and their count. */
  private final case class Checked[T](records: RDD[T], count: Long)

  private def checked[T: ClassTag](
      label: String,
      results: RDD[Either[String, T]],
      keep: mutable.ArrayBuffer[RDD[?]]
  ): Either[Vector[String], Checked[T]] = {
    if (results.getStorageLevel == StorageLevel.NONE)
      results.persist(StorageLevel.MEMORY_AND_DISK)
    keep += results
    val bad = firstRefusals(results)
    if (bad.nonEmpty) Left(bad.map(w => s"$label: $w"))
    else {
      val good = results.map(_.toOption.get)
      Right(Checked(good, good.count()))
    }
  }

  /**
   * Builds, checks and publishes, under `outputRoot`, every configured window's coauthor edges,
   * researcher metrics, component memberships, community memberships, bridge measures and coreness
   * as one run. Order: refusals of the settings; counted teams (persisted); graph structure
   * ([[GraphStructureJob.compute]]: components, label propagation, coreness; refused on an
   * integrity or invariant failure); run manifest with the label-propagation outcomes; edges
   * (refused on any edge refusal); identities and metrics ([[ResearcherMetricsJob]]); structure
   * records ([[StructureRecordsJob]]); cross-checks (one structure record per metrics record of
   * each dataset, the structure's edge count per window equals the edge job's); window structures
   * ([[StructureRecordsJob.windowStructures]]); the canonical tables (refused on any record
   * refusal, a non-zero invariant count, or a COAUTHORED_WITH count other than the edge count) and
   * their Neptune CSV parts; then one staged publication of every Parquet and CSV dataset
   * ([[RunPublisher.publish]]). The checkpoint subdirectory is deleted at the end, whatever the
   * outcome.
   */
  def runCollaboration(
      spark: SparkSession,
      cfg: AnalysisJobConfig,
      input: VerifiedInput,
      pairs: BenchmarkPairList,
      outputRoot: Path
  ): Either[Vector[String], CollaborationRunResult] =
    runCollaborationTo(spark, cfg, input, pairs, NioRunStore, StorePath.local(outputRoot))

  /**
   * As [[runCollaboration]], publishing under `outputRoot` in `store` (SP-P6-04): the local
   * staged-rename protocol for the java.nio store, the commit-marker protocol (no rename) for any
   * Hadoop FileSystem ([[RunPublisher]], [[LoadManifests]]). The computation is the same.
   */
  def runCollaborationTo(
      spark: SparkSession,
      cfg: AnalysisJobConfig,
      input: VerifiedInput,
      pairs: BenchmarkPairList,
      store: RunStore,
      outputRoot: StorePath
  ): Either[Vector[String], CollaborationRunResult] =
    if (cfg.settings.exportFormat != PartFormat.Parquet)
      Left(
        Vector(
          s"analysis.exportFormat ${cfg.settings.exportFormat.tag}: only parquet is implemented"
        )
      )
    else {
      val problems = BenchmarkPairs.bindingProblems(
        pairs,
        input.grouping.runId,
        BenchmarkPairs.identifiedOf(input.graphInput.researchers)
      ) ++ structureProblems(cfg, outputRoot)
      if (problems.nonEmpty) Left(problems)
      else
        for {
          _ <- manifest(cfg, input, pairs, SortedMap.empty) // fail fast on the fixed parts
          l <- lineage(cfg, input)
          snapshot <- dblpSnapshot(input.grouping.manifest)
          teams <- CoauthorEdgeJob.countedTeams(
            spark.sparkContext,
            input.teamParts,
            input.grouping.rowsOf(SchemaVersions.PublicationTeam),
            input.workParts,
            input.grouping.rowsOf(SchemaVersions.WorkAssignment),
            cfg.partitions
          )
          result <- {
            teams.persist(StorageLevel.MEMORY_AND_DISK)
            try withStructure(spark, cfg, input, pairs, teams, l, snapshot, store, outputRoot)
            finally { teams.unpersist(blocking = false); () }
          }
        } yield result
    }

  private def withStructure(
      spark: SparkSession,
      cfg: AnalysisJobConfig,
      input: VerifiedInput,
      pairs: BenchmarkPairList,
      teams: RDD[PublicationTeam],
      l: AnalysisLineage,
      snapshot: String,
      store: RunStore,
      outputRoot: StorePath
  ): Either[Vector[String], CollaborationRunResult] = {
    val windows = cfg.settings.windows
    GraphStructureJob
      .compute(
        spark.sparkContext,
        ResearcherMetricsJob.occurrences(teams, windows).map(_._1),
        CoauthorEdgeJob.pairOccurrences(teams, windows).map(_._1),
        windows,
        maxIterations(cfg.settings).getOrElse(0),
        cfg.checkpointing,
        cfg.partitions
      )
      .flatMap { structure =>
        var released = Vector.empty[String]
        val result =
          try
            for {
              m <- manifest(cfg, input, pairs, convergenceParameters(structure.communityOutcomes))
              runId <- RunId.of(m).left.map(e => Vector(e.toString))
              ctx = EdgeContext(windows, cfg.settings.graphPolicyVersion, l, runId, snapshot)
              r <- build(
                spark,
                cfg,
                input,
                pairs,
                teams,
                structure,
                m,
                runId,
                l,
                ctx,
                store,
                outputRoot
              )
            } yield r
          finally released = structure.release(spark.sparkContext)
        result.flatMap(r =>
          if (released.isEmpty) Right(r)
          else
            Left(
              released.map(w => s"after publication: $w") :+
                s"run ${r.runId} was published and verified at ${r.published.dir}; only the checkpoint cleanup failed"
            )
        )
      }
  }

  private def frame[T](
      spark: SparkSession,
      records: RDD[T],
      toRow: T => Row,
      schema: StructType
  ): DataFrame =
    spark.createDataFrame(records.map(toRow), schema)

  private def build(
      spark: SparkSession,
      cfg: AnalysisJobConfig,
      input: VerifiedInput,
      pairs: BenchmarkPairList,
      teams: RDD[PublicationTeam],
      structure: GraphStructure,
      m: RunManifest,
      runId: String,
      l: AnalysisLineage,
      ctx: EdgeContext,
      store: RunStore,
      outputRoot: StorePath
  ): Either[Vector[String], CollaborationRunResult] = {
    val keep = mutable.ArrayBuffer.empty[RDD[?]]
    val p = cfg.partitions
    val s = cfg.settings
    val sctx = StructureContext(
      ctx,
      s.communityAlgorithm,
      s.communityParameters,
      s.bridgeAlgorithm,
      s.bridgeParameters
    )
    def kept[T](r: RDD[T]): RDD[T] = {
      r.persist(StorageLevel.MEMORY_AND_DISK)
      keep += r
      r
    }
    try
      for {
        edges <- checked("edges", CoauthorEdgeJob.edges(teams, ctx, p), keep)
        summaries = CoauthorEdgeJob.summaries(teams, edges.records, ctx.windows)
        identities <- IdentityJob.identities(
          spark.sparkContext,
          input.grouping.partsOf(SchemaVersions.ResearcherAssignment),
          input.grouping.rowsOf(SchemaVersions.ResearcherAssignment),
          input.decision.partsOf(SchemaVersions.MatchDecision),
          input.decision.rowsOf(SchemaVersions.MatchDecision),
          p
        )
        metrics <- checked(
          "metrics",
          ResearcherMetricsJob.metrics(teams, edges.records, identities, ctx, p),
          keep
        )
        metricsSummaries = ResearcherMetricsJob.summaries(metrics.records, ctx.windows)
        componentInfo = kept(StructureRecordsJob.componentInfo(structure, ctx, p))
        communityInfo = kept(StructureRecordsJob.communityInfo(structure, sctx, p))
        bridgeCounts = kept(GraphStructureJob.bridgeCounts(structure, p))
        components <- checked(
          "component memberships",
          StructureRecordsJob.components(componentInfo, ctx, p),
          keep
        )
        communities <- checked(
          "community memberships",
          StructureRecordsJob.communities(communityInfo, componentInfo, sctx, p),
          keep
        )
        bridges <- checked(
          "bridge measures",
          StructureRecordsJob.bridges(communityInfo, bridgeCounts, structure.degrees, sctx, p),
          keep
        )
        coreness <- checked("coreness", StructureRecordsJob.coreness(structure, ctx, p), keep)
        structureSummaries = GraphStructureJob.summaries(structure, bridgeCounts)
        _ <- crossChecks(
          metrics.count,
          Vector(
            "component memberships" -> components.count,
            "community memberships" -> communities.count,
            "bridge measures" -> bridges.count,
            "coreness" -> coreness.count
          ),
          summaries,
          structureSummaries
        )
        windowStructures <- StructureRecordsJob.windowStructures(structureSummaries, sctx)
        canonical <- canonicalTables(spark, input, edges, ctx, p, keep)
        // SP-P6-03 step 2C.
        withoutYear = teams.filter(_.year.isEmpty).count()
        analysisWindows <- TemporalJob.analysisWindows(
          summaries,
          structureSummaries,
          withoutYear,
          ctx
        )
        stored <- storedPaths(spark, cfg, pairs, structure, edges, metrics, ctx, keep)
        (paths, pathStats) = stored
        estimates <- pathLength(spark, cfg, structure, ctx, keep)
        records <- checked(
          "researcher window records",
          TemporalJob
            .researcherWindows(
              metrics.records,
              components.records,
              communities.records,
              bridges.records,
              coreness.records,
              ctx.windows,
              p
            )
            .map { case (k, e) => e.map(r => (k, r)) },
          keep
        )
        temporal = TemporalJob.changes(
          records.records,
          edges.records,
          structureSummaries.map(_.largestComponent),
          ctx,
          p
        )
        changes <- checked("temporal changes", temporal._1, keep)
        comparisons <- TemporalJob.comparisons(
          temporal._2,
          withoutYear,
          structureSummaries.map(_.labelPropagation.converged),
          ctx
        )
        _ <- temporalChecks(
          metrics.count,
          records.count,
          changes.count,
          comparisons,
          ctx.windows
        )
        summaryInputs <- checked(
          "semantic-summary inputs",
          SummaryJob.semanticInputs(
            Vector(
              SummaryJob.researcherProfiles(records.records, ctx),
              SummaryJob.communitySummaries(communities.records, components.records, ctx, p),
              SummaryJob.pathSummaries(paths.records, components.records, ctx, p)
            ),
            p
          ),
          keep
        )
        entityInputs <- checked(
          "entity summary inputs",
          SummaryJob.entitySummaries(
            paths.records,
            canonical.nodes.records,
            canonical.edges.records,
            canonical.inputs.works,
            canonical.inputs.sourceWorks,
            ctx,
            p
          ),
          keep
        )
        _ <-
          if (
            summaryInputs.count == metrics.count + paths.count + communityCount(structureSummaries)
          )
            Right(())
          else
            Left(
              Vector(
                s"semantic-summary inputs: ${summaryInputs.count}, expected one per metrics record, community and stored path"
              )
            )
        published <- RunPublisher.publishTo(
          spark,
          store,
          outputRoot,
          m,
          Vector(
            ParquetDataset(
              EdgesDataset,
              SchemaVersions.CoauthorEdgeV2,
              frame(spark, edges.records, CoauthorEdgeRows.toRow, CoauthorEdgeRows.schema),
              edges.count
            ),
            ParquetDataset(
              MetricsDataset,
              SchemaVersions.ResearcherMetricsV2,
              frame(
                spark,
                metrics.records,
                ResearcherMetricsRows.toRow,
                ResearcherMetricsRows.schema
              ),
              metrics.count
            ),
            ParquetDataset(
              ComponentsDataset,
              SchemaVersions.ComponentMembership,
              frame(
                spark,
                components.records,
                ComponentMembershipRows.toRow,
                ComponentMembershipRows.schema
              ),
              components.count
            ),
            ParquetDataset(
              CommunitiesDataset,
              SchemaVersions.CommunityMembership,
              frame(
                spark,
                communities.records,
                CommunityMembershipRows.toRow,
                CommunityMembershipRows.schema
              ),
              communities.count
            ),
            ParquetDataset(
              BridgesDataset,
              SchemaVersions.BridgeMeasure,
              frame(spark, bridges.records, BridgeMeasureRows.toRow, BridgeMeasureRows.schema),
              bridges.count
            ),
            ParquetDataset(
              CorenessDataset,
              SchemaVersions.Coreness,
              frame(spark, coreness.records, CorenessRows.toRow, CorenessRows.schema),
              coreness.count
            ),
            ParquetDataset(
              NodesDataset,
              SchemaVersions.GraphNodeV2,
              frame(spark, canonical.nodes.records, GraphNodeRows.toRow, GraphNodeRows.schema),
              canonical.nodes.count
            ),
            ParquetDataset(
              GraphEdgesDataset,
              SchemaVersions.GraphEdgeV2,
              frame(spark, canonical.edges.records, GraphEdgeRows.toRow, GraphEdgeRows.schema),
              canonical.edges.count
            ),
            ParquetDataset(
              WindowStructuresDataset,
              SchemaVersions.WindowStructure,
              frame(
                spark,
                spark.sparkContext.parallelize(windowStructures, 1),
                WindowStructureRows.toRow,
                WindowStructureRows.schema
              ),
              windowStructures.size.toLong
            ),
            ParquetDataset(
              AnalysisWindowsDataset,
              SchemaVersions.AnalysisWindow,
              frame(
                spark,
                spark.sparkContext.parallelize(analysisWindows, 1),
                AnalysisWindowRows.toRow,
                AnalysisWindowRows.schema
              ),
              analysisWindows.size.toLong
            ),
            ParquetDataset(
              EvidencePathsDataset,
              SchemaVersions.EvidencePath,
              frame(spark, paths.records, EvidencePathRows.toRow, EvidencePathRows.schema),
              paths.count
            ),
            ParquetDataset(
              WindowComparisonsDataset,
              SchemaVersions.WindowComparison,
              frame(
                spark,
                spark.sparkContext.parallelize(comparisons, 1),
                WindowComparisonRows.toRow,
                WindowComparisonRows.schema
              ),
              comparisons.size.toLong
            ),
            ParquetDataset(
              TemporalChangesDataset,
              SchemaVersions.ResearcherTemporalChange,
              frame(spark, changes.records, TemporalChangeRows.toRow, TemporalChangeRows.schema),
              changes.count
            ),
            ParquetDataset(
              SummaryInputsDataset,
              SchemaVersions.SemanticSummaryInput,
              frame(spark, summaryInputs.records, SummaryInputRows.toRow, SummaryInputRows.schema),
              summaryInputs.count
            ),
            ParquetDataset(
              EntitySummaryInputsDataset,
              SchemaVersions.EntitySummaryInput,
              frame(
                spark,
                entityInputs.records,
                EntitySummaryInputRows.toRow,
                EntitySummaryInputRows.schema
              ),
              entityInputs.count
            )
          ) ++ estimates.toVector.map { case (e, _) =>
            ParquetDataset(
              PathLengthEstimatesDataset,
              SchemaVersions.PathLengthEstimate,
              frame(spark, e.records, PathLengthEstimateRows.toRow, PathLengthEstimateRows.schema),
              e.count
            )
          },
          canonical.csv,
          input.limits.maxMetadataBytes
        )
        loads <- LoadManifests
          .publishTo(store, outputRoot, published, input.limits.maxMetadataBytes)
          .left
          .map(
            _.map(w => s"load manifests: $w") :+
              s"run ${published.runId} was published and verified at ${published.dir}; its load manifests were not written"
          )
      } yield CollaborationRunResult(
        published,
        runId,
        l,
        summaries,
        edges.count,
        metrics.count,
        metricsSummaries,
        components.count,
        communities.count,
        bridges.count,
        coreness.count,
        structureSummaries,
        structure.componentIterations,
        structure.corenessRounds,
        structure.checkpoints,
        canonical.summary,
        windowStructures,
        Step2CSummary(
          analysisWindows,
          paths.count,
          pathStats,
          estimates.map { case (e, st) => (e.count, st) },
          comparisons,
          changes.count,
          summaryInputs.count,
          entityInputs.count,
          withoutYear,
          loads
        )
      )
    finally keep.foreach(_.unpersist(blocking = false))
  }

  private def communityCount(structure: Vector[StructureWindowSummary]): Long =
    structure.map(_.communities).sum

  /**
   * The stored evidence paths of the benchmark pairs in every window ([[PathJob]]): the distributed
   * search, then the checked `evidence-path.v1` records (a refusal refuses the run).
   */
  private def storedPaths(
      spark: SparkSession,
      cfg: AnalysisJobConfig,
      pairs: BenchmarkPairList,
      structure: GraphStructure,
      edges: Checked[CoauthorEdgeV2],
      metrics: Checked[ResearcherMetricsV2],
      ctx: EdgeContext,
      keep: mutable.ArrayBuffer[RDD[?]]
  ): Either[Vector[String], (Checked[EvidencePath], PathSearchStats)] = {
    val searches = PathJob.searchesOf(pairs, ctx.windows)
    val b = cfg.settings.pathBounds
    for {
      sp <- PathJob.shortestPaths(
        spark.sparkContext,
        structure.arcs,
        structure.degrees.map(_._1),
        searches,
        b.maxHops,
        b.maxPathsPerPair,
        cfg.checkpointing.interval,
        cfg.partitions
      )
      _ = keep ++= sp.retained
      paths <- checked(
        "evidence paths",
        PathJob.evidencePaths(
          sp.paths,
          searches,
          edges.records,
          metrics.records,
          ctx,
          b,
          cfg.partitions
        ),
        keep
      )
    } yield (paths, sp.stats)
  }

  /**
   * The checked path-length estimates ([[PathJob.componentDistances]]) when the configuration sets
   * the `analysis.pathLength*` keys; None otherwise (nothing is computed or stored).
   */
  private def pathLength(
      spark: SparkSession,
      cfg: AnalysisJobConfig,
      structure: GraphStructure,
      ctx: EdgeContext,
      keep: mutable.ArrayBuffer[RDD[?]]
  ): Either[Vector[String], Option[(Checked[PathLengthEstimate], PathLengthStats)]] =
    cfg.settings.pathLengthSampling match {
      case None => Right(None)
      case Some(sampling) =>
        for {
          d <- PathJob.componentDistances(
            spark.sparkContext,
            structure.arcs,
            structure.components,
            ctx.windows,
            sampling,
            cfg.checkpointing.interval,
            cfg.partitions,
            structure.vertexCount + 1L
          )
          _ = keep ++= d.retained
          e <- checked(
            "path-length estimates",
            PathJob.estimates(d.components, ctx, sampling, cfg.partitions),
            keep
          )
        } yield Some((e, d.stats))
    }

  /**
   * Invariants of the temporal datasets: one comparison per consecutive window pair, and per pair
   * as many temporal changes as researchers with a metrics record in either window (in both, only
   * in the first, only in the second), so the changes of all pairs sum to the comparisons' counts.
   */
  private def temporalChecks(
      metricsCount: Long,
      recordCount: Long,
      changeCount: Long,
      comparisons: Vector[WindowComparison],
      windows: Vector[TimeWindow]
  ): Either[Vector[String], Unit] = {
    val perPair =
      comparisons.map(c => c.researchersInBoth + c.researchersOnlyFrom + c.researchersOnlyTo).sum
    val problems = Vector(
      Option.when(comparisons.size != math.max(windows.size - 1, 0))(
        s"window comparisons: ${comparisons.size} for ${windows.size} windows"
      ),
      Option.when(changeCount != perPair)(
        s"temporal changes: $changeCount records, the comparisons count $perPair researchers"
      ),
      Option.when(recordCount != metricsCount)(
        s"researcher window records: $recordCount, but $metricsCount metrics records"
      )
    ).flatten
    if (problems.isEmpty) Right(()) else Left(problems)
  }

  /** The checked canonical tables, their Neptune CSV datasets and their counts. */
  private final case class CanonicalBuilt(
      nodes: Checked[GraphNodeV2],
      edges: Checked[GraphEdgeV2],
      csv: Vector[CsvDataset],
      summary: CanonicalSummary,
      inputs: CanonicalInputs
  )

  private def countBy(keys: RDD[String]): scala.collection.Map[String, Long] =
    RDD.rddToPairRDDFunctions(keys.map(k => (k, 1L))).reduceByKey(_ + _).collectAsMap()

  /**
   * The canonical tables (course item 1, [[CanonicalGraphJob]]) of the run: inputs read and
   * re-counted, built, every record checked (a refusal refuses the run), the distributed invariants
   * (dangling endpoints, endpoint labels, the mention one-of rule) all 0, one COAUTHORED_WITH edge
   * per coauthor edge, and one Neptune CSV dataset per node label and edge class
   * (`NeptuneCsv.vertexFile` / `edgeFile` per partition). Only counts reach the driver.
   */
  private def canonicalTables(
      spark: SparkSession,
      input: VerifiedInput,
      coauthor: Checked[CoauthorEdgeV2],
      ctx: EdgeContext,
      p: Int,
      keep: mutable.ArrayBuffer[RDD[?]]
  ): Either[Vector[String], CanonicalBuilt] = {
    def kept[T](r: RDD[T]): RDD[T] = {
      r.persist(StorageLevel.MEMORY_AND_DISK)
      keep += r
      r
    }
    for {
      read <- CanonicalGraphJob.inputs(spark.sparkContext, input, p)
      in = read.copy(
        works = kept(read.works),
        researchers = kept(read.researchers),
        authorships = kept(read.authorships),
        sourceWorks = kept(read.sourceWorks),
        mentions = kept(read.mentions),
        venues = kept(read.venues),
        institutions = kept(read.institutions),
        topics = kept(read.topics)
      )
      tables <- CanonicalGraphJob.build(in, coauthor.records, ctx, p)
      _ = keep ++= tables.retained
      nodes <- checked("graph nodes", tables.nodes, keep)
      edges <- checked("graph edges", tables.edges, keep)
      failures = CanonicalGraphJob.invariants(nodes.records, edges.records, p)
      _ <-
        if (failures.forall(_._2 == 0L)) Right(())
        else
          Left(failures.collect {
            case (what, n) if n > 0L => s"canonical tables invariant: $n $what"
          })
      byLabel = countBy(nodes.records.map(_.label.tag))
      byClass = countBy(edges.records.map(_.edgeType.tag))
      coauthored = byClass.getOrElse(EdgeLabel.CoauthoredWith.tag, 0L)
      _ <-
        if (coauthored == coauthor.count) Right(())
        else
          Left(
            Vector(
              s"canonical tables: $coauthored COAUTHORED_WITH edges, but ${coauthor.count} coauthor edges"
            )
          )
      vertexFiles <- NeptuneCsvParts.vertexDatasets.foldLeft(
        Right(Vector.empty): Either[Vector[String], Vector[CsvDataset]]
      ) { case (acc, (label, name, schema, header)) =>
        acc.flatMap(done =>
          checked(name, CanonicalGraphJob.vertexFiles(nodes.records, label), keep).map(files =>
            done :+ CsvDataset(
              name,
              schema,
              header,
              files.records,
              byLabel.getOrElse(label.tag, 0L)
            )
          )
        )
      }
      edgeFiles <- NeptuneCsvParts.edgeDatasets.foldLeft(
        Right(Vector.empty): Either[Vector[String], Vector[CsvDataset]]
      ) { case (acc, (label, name, schema, header)) =>
        acc.flatMap(done =>
          checked(name, CanonicalGraphJob.edgeFiles(edges.records, label), keep).map(files =>
            done :+ CsvDataset(
              name,
              schema,
              header,
              files.records,
              byClass.getOrElse(label.tag, 0L)
            )
          )
        )
      }
      csv = vertexFiles ++ edgeFiles
    } yield CanonicalBuilt(
      nodes,
      edges,
      csv,
      CanonicalSummary(
        CanonicalGraph.NodeLabels.map(l => l.tag -> byLabel.getOrElse(l.tag, 0L)),
        CanonicalGraph.EdgeLabels.map(l => l.tag -> byClass.getOrElse(l.tag, 0L)),
        tables.unmatched,
        failures,
        csv.map(d => d.name -> d.files.getNumPartitions)
      ),
      in
    )
  }

  /**
   * Invariants across the datasets of one run: every structure dataset has exactly one record per
   * metrics record (one per identified researcher and window), and the structure's edge count of
   * every window equals the edge job's.
   */
  private def crossChecks(
      metricsCount: Long,
      counts: Vector[(String, Long)],
      inputs: Vector[WindowInputSummary],
      structure: Vector[StructureWindowSummary]
  ): Either[Vector[String], Unit] = {
    val problems =
      counts.collect {
        case (what, n) if n != metricsCount =>
          s"$what: $n records, but $metricsCount researcher metrics records (one per researcher and window)"
      } ++ inputs.zip(structure).collect {
        case (i, st) if i.edges != st.edges || i.identifiedResearchers != st.researchers =>
          s"window ${i.window}: the edge job has ${i.edges} edges and ${i.identifiedResearchers} researchers, the graph structure ${st.edges} and ${st.researchers}"
      }
    if (problems.isEmpty) Right(()) else Left(problems)
  }
}
