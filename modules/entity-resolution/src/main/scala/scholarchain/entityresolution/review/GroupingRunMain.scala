package scholarchain.entityresolution.review

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.util.control.NonFatal

import scholarchain.core.{Json, RunManifest}
import scholarchain.core.config.{ConfigError, GroupingRunSettings, RunLocation, ScholarChainConfig}
import scholarchain.entityresolution.{ConfigEvidence, LoadedConfig}
import scholarchain.entityresolution.decisions.DecisionExport
import scholarchain.entityresolution.grouping.{
  CuratedCandidate,
  Grouping,
  GroupingBatch,
  GroupingExport,
  GroupingInputs,
  GroupingPolicy,
  LinkOutcome
}

/**
 * Grouping run entry point (ER-P6-05): `GroupingRunMain <grouping run manifest .json> <decision run
 * dir> <curated run dir> <assignment output root>`. Every path comes from the arguments; nothing is
 * defaulted. It lives with the run tooling of this package because it reads the curated run with
 * [[CuratedRunReader]] and resolves the match policy with [[ReviewQueueMain.effectivePolicy]]; the
 * grouping itself is the review-free [[Grouping]] layer.
 *
 *   1. Resolves the external configuration (`SCHOLARCHAIN_CONFIG_FILES`, core `ConfigLoader`):
 *      [[GroupingRunSettings.from]] (grouping policy version, match policy version, identity
 *      version, both run roots), the grouping digest [[GroupingRunSettings.configDigest]] (the run
 *      roots are not part of it), and the effective match policy.
 *   2. Loads the versioned grouping policy resource ([[GroupingPolicy.loadDefault]]).
 *   3. Requires local run roots: the decision run directory must be a direct child of the
 *      configured `entityResolution.decisionRunRoot`, and the output root must be the configured
 *      `entityResolution.assignmentRunRoot` (this entry point publishes on a local file system
 *      only; an `s3://` root is refused).
 *   4. Reads the decision run only through the verified reader [[DecisionExport.readPublished]]
 *      (`_parts.json` checked first), and the curated run it decided (its `_run-manifest.json` and
 *      [[CuratedRunReader]]).
 *   5. Groups ([[Grouping.group]]: every manifest, version and input check, including that every
 *      curated candidate, work, mention and profile carries the recorded curated run ID, the
 *      endpoint self-check, every core validator and the producer checks; fail closed) and
 *      publishes all-or-nothing ([[GroupingExport.publishRun]]), which reads the staged directory
 *      back (verified reader and dataset validators) and compares it with the batch BEFORE the
 *      rename (ER-P6-07).
 *   6. Reads the published run back with [[GroupingExport.readValidated]] (ER-P6-06, BE-P6-01
 *      requirement 3a: the verified reader plus the core dataset validators) and requires every
 *      record read back to equal the batch it published; prints a `verified grouping run=...` line.
 *      If this fails, the run is withdrawn (marked `_UNVERIFIED` and renamed to
 *      `.unverified-run=<hex>-<random>`; nothing deleted).
 *
 * It prints the counts, the verified line, the settings, the descriptive diagnostics (each line
 * labelled `unevaluated`) and the configuration evidence. No model is called. Exit codes
 * ([[RunExit]], identical for [[DecisionRunMain]]): 0 published and verified; 2 refused, nothing
 * published; 3 verification failed (staged or after publication), artifacts preserved under the
 * path printed on stderr, not accepted by readers (including this entry point's own decision-run
 * input reader).
 */
object GroupingRunMain {
  def main(args: Array[String]): Unit =
    sys.exit(
      runWithConfig(
        args.toVector,
        println,
        () => ConfigEvidence.system(),
        DecisionExport.DefaultFileWriter,
        System.err.println,
        _ => ()
      )
    )

  /** As [[main]], with an in-process configuration (tests; no file evidence). */
  def run(
      args: Vector[String],
      out: String => Unit,
      loadConfig: () => Either[Vector[ConfigError], ScholarChainConfig]
  ): Int = runWithConfig(args, out, () => ConfigEvidence.injected(loadConfig))

  val Usage: String =
    "usage: GroupingRunMain <grouping run manifest .json> <decision run dir> <curated run dir> " +
      "<assignment output root>"

  /** At most this many refusal reasons are printed (the count is always printed). */
  val MaxReasons: Int = 20

  /** Reads a run manifest file exactly as this entry point decodes it (one final LF stripped). */
  private[review] def readManifest(p: Path): Either[String, RunManifest] =
    try
      Json
        .decode[RunManifest](Files.readString(p, StandardCharsets.UTF_8).stripSuffix("\n"))
        .left
        .map(es => s"$p: ${es.map(_.message).mkString("; ")}")
    catch { case NonFatal(e) => Left(s"cannot read $p: ${e.getClass.getName}: ${e.getMessage}") }

  private def reasons(es: Vector[String]): String =
    s"${es.size} reason(s): " + es.take(MaxReasons).mkString(" | ") +
      (if (es.size > MaxReasons) s" | ... ${es.size - MaxReasons} more" else "")

  private def configErrors(what: String, es: Vector[ConfigError]): String =
    s"$what refused: " + es.map(_.message).mkString("; ")

  /**
   * Why the run directories do not fit the configured run roots (local file system only), or Right
   * with the normalized paths.
   */
  def locationProblem(
      s: GroupingRunSettings,
      decisionDir: Path,
      outputRoot: Path
  ): Either[String, Unit] = {
    val local = Vector(
      "entityResolution.decisionRunRoot" -> s.decisionRunRoot,
      "entityResolution.assignmentRunRoot" -> s.assignmentRunRoot
    ).collect {
      case (k, v) if RunLocation.problem(v).nonEmpty || !v.startsWith("/") =>
        s"$k '$v' is not an absolute local path; this entry point publishes on a local file " +
          "system only"
    }
    if (local.nonEmpty) Left(local.mkString("; "))
    else {
      val decisionRoot = Path.of(s.decisionRunRoot).normalize
      val assignmentRoot = Path.of(s.assignmentRunRoot).normalize
      val dir = decisionDir.toAbsolutePath.normalize
      val out = outputRoot.toAbsolutePath.normalize
      Vector(
        Option.when(dir.getParent != decisionRoot)(
          s"decision run $dir is not a run directory of the configured " +
            s"entityResolution.decisionRunRoot $decisionRoot"
        ),
        Option.when(out != assignmentRoot)(
          s"output root $out is not the configured entityResolution.assignmentRunRoot " +
            s"$assignmentRoot"
        )
      ).flatten match {
        case Vector() => Right(())
        case es => Left(es.mkString("; "))
      }
    }
  }

  /** The effective settings of a grouping run. */
  def settingsLine(s: GroupingRunSettings, g: GroupingPolicy, digest: String): String =
    s"settings groupingPolicy=${g.groupingPolicyVersion} matchPolicy=${s.matchPolicyVersion} " +
      s"identityVersion=${s.identityVersion} configDigest=$digest " +
      s"decisionRunRoot=${s.decisionRunRoot} assignmentRunRoot=${s.assignmentRunRoot} " +
      "model=none (grouping makes no model call) config=applied"

  /** Summary lines of a published grouping batch. */
  def summary(b: GroupingBatch, dir: Path): Vector[String] = {
    val p = b.manifest.parameters
    Vector(
      s"published $dir",
      s"grouping run=${b.runId} stage=${b.manifest.stage} attempt=${b.manifest.attempt} " +
        s"decisionRunId=${p(Grouping.Params.DecisionRunId)} " +
        s"inputRunId=${p(Grouping.Params.InputRunId)} " +
        s"assignedAt=${p(Grouping.Params.AssignedAt)}",
      s"records researcher=${b.researchers.size} work=${b.works.size} " +
        s"authorship=${b.authorships.size} team=${b.teams.size}",
      s"links merged=${b.links.count(_.outcome == LinkOutcome.Merged)} " +
        s"redundant=${b.links.count(_.outcome == LinkOutcome.Redundant)} " +
        s"vetoed=${b.links.count(_.outcome == LinkOutcome.Vetoed)}"
    )
  }

  /**
   * The integrity read-back of a published grouping run (ER-P6-06):
   * [[GroupingExport.readValidated]] of `dir` must succeed, and the manifest, run ID and every
   * record read back must equal the batch that was published ([[GroupingExport.compareWithBatch]]).
   * Right with the `verified` line; Left with the reason.
   */
  def readBack(b: GroupingBatch, dir: Path): Either[String, String] =
    GroupingExport.readValidated(dir).flatMap(GroupingExport.compareWithBatch(b, _)).map { _ =>
      s"verified grouping run=${b.runId} dir=$dir researcher=${b.researchers.size} " +
        s"work=${b.works.size} authorship=${b.authorships.size} team=${b.teams.size} (read " +
        "back with GroupingExport.readValidated; equal to the published batch)"
    }

  def runWithConfig(args: Vector[String], out: String => Unit, source: () => LoadedConfig): Int =
    runWithConfig(args, out, source, DecisionExport.DefaultFileWriter)

  /** As above, with the publisher's file writer (tests inject I/O faults through it). */
  def runWithConfig(
      args: Vector[String],
      out: String => Unit,
      source: () => LoadedConfig,
      writer: DecisionExport.FileWriter
  ): Int = runWithConfig(args, out, source, writer, out, _ => ())

  /**
   * As above, with the stderr sink `err` and `afterRename`, called with the published directory
   * between the rename and the read-back (tests inject a post-publication fault through it; a no-op
   * in [[main]]).
   */
  def runWithConfig(
      args: Vector[String],
      out: String => Unit,
      source: () => LoadedConfig,
      writer: DecisionExport.FileWriter,
      err: String => Unit,
      afterRename: Path => Unit
  ): Int = {
    val result: Either[String, (GroupingBatch, Vector[String])] = args match {
      case Vector(manifestFile, decisionDir, curatedDir, outputRoot) =>
        lazy val loaded = source()
        for {
          m <- readManifest(Path.of(manifestFile))
          cfg <- loaded.result.left.map(es => configErrors("configuration", es))
          settings <- GroupingRunSettings
            .from(cfg)
            .left
            .map(es => configErrors("grouping configuration", es))
          digest <- GroupingRunSettings
            .configDigest(cfg)
            .left
            .map(es => configErrors("grouping configuration", es))
          p <- ReviewQueueMain.effectivePolicy(() => loaded.result)
          g <- GroupingPolicy
            .loadDefault()
            .left
            .map(es => "grouping policy refused: " + es.mkString("; "))
          _ <- locationProblem(settings, Path.of(decisionDir), Path.of(outputRoot))
          decided <- DecisionExport
            .readPublished(Path.of(decisionDir))
            .left
            .map(e => s"decision run refused (verified reader): $e")
          curatedManifest <- readManifest(Path.of(curatedDir).resolve(DecisionExport.ManifestFile))
          run <- CuratedRunReader.read(Path.of(curatedDir))
          batch <- Grouping
            .group(
              GroupingInputs(
                manifest = m,
                configDigest = digest,
                settings = settings,
                policy = g,
                matchPolicy = p,
                decisionManifest = decided._1,
                decisions = decided._2,
                curatedManifest = curatedManifest,
                works = run.works,
                profiles = run.profiles,
                candidates = run.candidates.map(CuratedCandidate.of)
              )
            )
            .left
            .map(es => "grouping run refused, nothing published; " + reasons(es))
        } yield (
          batch,
          Vector(settingsLine(settings, g, digest)) ++ batch.diagnostics ++
            ConfigEvidence.lines(loaded)
        )
      case _ => Left(Usage)
    }
    val outputRoot = args.lift(3).map(Path.of(_))
    val done: Either[RunExit.Failure, Vector[String]] = for {
      prepared <- RunExit.refusing(result)
      (batch, tail) = prepared
      published <- RunExit.published(GroupingExport.publishRun(outputRoot.get, batch, writer))
      (dir, attempt) = published
      verified <- {
        afterRename(dir)
        RunExit.afterPublication(dir, attempt, readBack(batch, _), DecisionExport.AtomicMove)
      }
    } yield summary(batch, dir) ++ Vector(verified) ++ tail
    RunExit.report(done, out, err)
  }
}
