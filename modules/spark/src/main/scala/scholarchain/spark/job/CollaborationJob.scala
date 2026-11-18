package scholarchain.spark.job

import java.io.PrintStream

import scala.collection.immutable.SortedMap
import scala.util.control.NonFatal

import org.apache.spark.sql.SparkSession

import scholarchain.core.BenchmarkPairList
import scholarchain.core.config.{AnalysisRunSettings, ConfigLoader}
import scholarchain.spark.analysis.{
  AnalysisJobConfig,
  AnalysisRun,
  BenchmarkPairs,
  CollaborationRunResult
}
import scholarchain.spark.input.{ArchiveInput, RunTree, VerifiedArchiveReader}
import scholarchain.spark.store.{HadoopRunStore, RunStore}

/**
 * The collaboration-analysis job entry point for `spark-submit` (SP-P6-04): the same computation as
 * the sparkIT suite ([[AnalysisRun.runCollaborationTo]]) over a verified archive at any accepted
 * location, published create-once under the output root.
 *
 * Arguments: [[JobArguments]] (each flag required once, `--flag value`; nothing is hardcoded). The
 * SparkSession is obtained with `SparkSession.builder().getOrCreate()`: no master, no deploy mode
 * and no Spark setting is set in code, so `spark-submit` (or its `--conf`) decides them, including
 * the event log (`spark.eventLog.enabled`, `spark.eventLog.dir`; no code is needed for it). Every
 * file goes through the store of its location ([[HadoopRunStore]] over the SparkContext's Hadoop
 * configuration: `file:`, `hdfs:`, `s3a:`); the job publishes with the commit-marker protocol (no
 * rename; [[scholarchain.spark.output.RunPublisher]] protocol B) on every scheme, `file:` included:
 * a `file:` job run goes through Hadoop's local file system (check-then-create, link-following
 * open) and is not atomic as a whole run; completion is its `_SUCCESS` (CR-P6-26 n5). Whole-run
 * atomicity by one rename (protocol A) holds only for the java.nio store of
 * [[AnalysisRun.runCollaboration]] (the sparkIT path), never for this job. The configuration is
 * read with an empty environment (no `SCHOLARCHAIN_*` override reaches the run), so the
 * configuration digest describes the file alone.
 *
 * Order: arguments (refused before any Spark session exists), the start-up checks of the effective
 * configuration ([[StartupChecks]]: S3A directory markers kept, one YARN application attempt), no
 * `file:` input, output or checkpoint root under a cluster master
 * ([[JobArguments.clusterProblems]]), all before any read or write of job data; configuration and
 * settings, benchmark pair list (form), the verified reader ([[VerifiedArchiveReader]], committed
 * curated pin), the pair binding, the run, the summary lines. Exit status: 0 published and
 * verified; 2 refused arguments; 1 any other refusal or failure (each reason printed, prefix
 * [[Prefix]]` refused:`). Output: the summary lines on standard output (counts, run ID, output
 * locations; never a record).
 */
object CollaborationJob {

  val Prefix = "scholarchain-collaboration-job"
  val AppName = "scholarchain-collaboration-analysis"

  val ExitOk = 0
  val ExitRefused = 1
  val ExitArguments = 2

  /**
   * Opens the archive locations; production always uses [[ArchiveInput.of]] (the committed curated
   * pin, CR-P6-21 n1). A parameter only so the synthetic-fixture suite can pass its own pinned
   * opener from the `input` test package; `run` is `private[job]`.
   */
  type ArchiveOpener =
    (RunStore, String, String, String, String) => Either[Vector[String], ArchiveInput]

  def main(args: Array[String]): Unit = {
    var started: Option[SparkSession] = None
    val code =
      try
        run(
          args.toVector,
          () => {
            val s = SparkSession.builder().appName(AppName).getOrCreate()
            started = Some(s)
            s
          },
          System.out,
          ArchiveInput.of
        )
      finally started.foreach(_.stop())
    System.out.flush()
    if (code != ExitOk) sys.exit(code)
  }

  /**
   * The job body: `session` is called only after the arguments are accepted. `open` and `schemes`
   * are test seams (the synthetic-fixture pin and the S3-semantics test file system); [[main]]
   * passes [[ArchiveInput.of]] and [[JobArguments.Schemes]].
   */
  private[job] def run(
      args: Seq[String],
      session: () => SparkSession,
      out: PrintStream,
      open: ArchiveOpener,
      schemes: Set[String] = JobArguments.Schemes
  ): Int =
    JobArguments.parseWith(args, schemes) match {
      case Left(problems) =>
        problems.foreach(w => out.println(s"$Prefix refused: $w"))
        out.println(s"$Prefix ${JobArguments.usage}")
        ExitArguments
      case Right(a) =>
        val outcome =
          try {
            val spark = session()
            execute(spark, new HadoopRunStore(spark.sparkContext.hadoopConfiguration), a, open)
          } catch { case NonFatal(e) => Left(Vector(s"failure: $e")) }
        outcome match {
          case Left(problems) =>
            problems.foreach(w => out.println(s"$Prefix refused: $w"))
            ExitRefused
          case Right(lines) =>
            lines.foreach(l => out.println(s"$Prefix $l"))
            ExitOk
        }
    }

  private def execute(
      spark: SparkSession,
      store: RunStore,
      a: JobArguments,
      open: ArchiveOpener
  ): Either[Vector[String], Vector[String]] = {
    val meta = a.limits.maxMetadataBytes
    val sc = spark.sparkContext
    for {
      _ <- {
        val p = StartupChecks.problems(sc.master, sc.getConf, sc.hadoopConfiguration, a) ++
          JobArguments.clusterProblems(sc.master, a)
        if (p.isEmpty) Right(()) else Left(p)
      }
      configText <- RunTree
        .readUtf8(store, a.configFile, meta)
        .left
        .map(w => Vector(s"configuration: $w"))
      config <- ConfigLoader
        .resolve(Vector(a.configFile.toString -> configText), Map.empty)
        .left
        .map(_.map(e => s"configuration: ${e.message}"))
      settings <- AnalysisRunSettings
        .from(config)
        .left
        .map(_.map(e => s"configuration: ${e.message}"))
      pairsText <- RunTree
        .readUtf8(store, a.benchmarkPairsFile, meta)
        .left
        .map(w => Vector(s"benchmark pairs: $w"))
      _ <- BenchmarkPairs.parse(pairsText).left.map(_.map(w => s"benchmark pairs: $w"))
      runs <- open(
        store,
        a.inputRoot.toString,
        a.inputRoot.resolve(a.curatedRun).toString,
        a.inputRoot.resolve(a.decisionRun).toString,
        a.inputRoot.resolve(a.groupingRun).toString
      ).left.map(_.map(w => s"input: $w"))
      input <- VerifiedArchiveReader.read(runs, a.limits).left.map(_.map(r => s"input: $r"))
      pairs <- BenchmarkPairs
        .load(
          pairsText,
          input.grouping.runId,
          BenchmarkPairs.identifiedOf(input.graphInput.researchers)
        )
        .left
        .map(_.map(w => s"benchmark pairs: $w"))
      cfg = AnalysisJobConfig(
        settings,
        a.codeVersion,
        a.attempt,
        SortedMap.empty,
        a.partitions,
        a.checkpointing
      )
      result <- AnalysisRun.runCollaborationTo(spark, cfg, input, pairs, store, a.outputRoot)
    } yield summary(result, cfg, pairs, a)
  }

  /**
   * The summary lines of a run: the sparkIT's run, window, metrics, structure, canonical, path,
   * temporal, summary and load-manifest lines, from the result's counts and per-window or per-store
   * records only (nothing collected from the datasets).
   */
  def summary(
      r: CollaborationRunResult,
      cfg: AnalysisJobConfig,
      pairs: BenchmarkPairList,
      a: JobArguments
  ): Vector[String] = {
    val st = r.step2C.pathStats
    val loads = r.step2C.loads
    Vector(
      s"run: analysisRunId=${r.runId} dir=${r.published.dir} edges=${r.edgeCount} metrics=${r.metricsCount} " +
        s"configDigest=${cfg.settings.configDigest} benchmarkPairsDigest=${BenchmarkPairList.digest(pairs)} " +
        s"codeVersion=${cfg.codeVersion} attempt=${cfg.attempt} partitions=${cfg.partitions}",
      s"input: archiveRoot=${a.inputRoot} curated=${r.lineage.inputRunId} decision=${r.lineage.decisionRunId} " +
        s"grouping=${r.lineage.groupingRunId}"
    ) ++ r.summaries.map(s => s"window: $s") ++
      r.metricsSummaries.map(s => s"metrics window: $s") ++
      r.structureSummaries.map { s =>
        s"structure window: window=${s.window.startYear}-${s.window.endYear} " +
          s"researchers=${s.researchers} edges=${s.edges} components=${s.components} largestComponent=${s.largestComponent} " +
          s"largestComponentFraction=${s.largestComponentFraction} singletonComponents=${s.singletonComponents} " +
          s"communities=${s.communities} largestCommunity=${s.largestCommunity} singletonCommunities=${s.singletonCommunities} " +
          s"crossCommunityEdges=${s.crossCommunityEdges} maxCoreness=${s.maxCoreness} " +
          s"corenessHistogram=${s.corenessHistogram.map { case (k, n) => s"$k:$n" }.mkString(",")} " +
          s"labelPropagation=${s.labelPropagation.iterations}/${s.labelPropagation.converged}/${s.labelPropagation.lastIterationChanges}"
      } ++ Vector(
        s"structure iterations: componentIterations=${r.componentIterations} corenessRounds=${r.corenessRounds} " +
          s"checkpoints=${r.checkpoints}",
        s"records: components=${r.componentCount} communities=${r.communityCount} bridges=${r.bridgeCount} " +
          s"coreness=${r.corenessCount}",
        s"canonical: nodes=${r.canonical.nodesByLabel} edges=${r.canonical.edgesByClass} " +
          s"unmatched=${r.canonical.unmatched} invariantFailures=${r.canonical.invariantFailures} " +
          s"csvParts=${r.canonical.csvParts.map { case (n, k) => s"$n:$k" }.mkString(",")}"
      ) ++ r.windowStructures.map { s =>
        s"window structure: window=${s.window.startYear}-${s.window.endYear} ordinal=${s.ordinal} " +
          s"researchers=${s.researcherCount} edges=${s.edgeCount} components=${s.componentCount} " +
          s"largest=${s.largestComponentSize} communities=${s.communityCount} " +
          s"iterations=${s.communityIterations} converged=${s.communityConverged} maxCoreness=${s.maxCoreness}"
      } ++ Vector(
        s"paths: stored=${r.step2C.pathCount} searches=${st.searches} present=${st.present} connected=${st.connected} " +
          s"supersteps=${st.supersteps} walkSteps=${st.walkSteps} checkpoints=${st.checkpoints} maxLevel=${st.maxLevel} " +
          s"maxPartialPaths=${st.maxPartialPaths}"
      ) ++ r.step2C.pathLength.toVector.map { case (n, s) =>
        s"path-length: estimates=$n components=${s.components} sources=${s.sources} supersteps=${s.supersteps} " +
          s"checkpoints=${s.checkpoints} maxLevel=${s.maxLevel}"
      } ++ Vector(
        s"temporal: analysisWindows=${r.step2C.analysisWindows.size} comparisons=${r.step2C.windowComparisons.size} " +
          s"changes=${r.step2C.temporalChangeCount} worksWithoutYear=${r.step2C.worksWithoutYear} " +
          s"limitations=${r.step2C.windowComparisons.map(_.limitations.map(_.tag).mkString("+")).mkString(",")}",
        s"summaries: summaryInputs=${r.step2C.summaryInputCount} entitySummaryInputs=${r.step2C.entitySummaryCount}"
      ) ++ loads.manifests.map { m =>
        s"load manifest: store=${m.targetStore.tag} items=${m.items.size} rows=${m.items.map(_.rowCount).sum} " +
          s"roles=${m.items.map(_.targetRole).distinct.size} loadId=${m.loadId}"
      } ++ Vector(
        s"output: run=${r.published.dir} loads=${loads.dir} partManifestSha256=${loads.partManifestSha256} " +
          s"checkpointRoot=${a.checkpointDir}"
      )
  }
}
