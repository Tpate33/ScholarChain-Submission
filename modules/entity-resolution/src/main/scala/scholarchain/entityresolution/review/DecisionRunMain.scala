package scholarchain.entityresolution.review

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.util.control.NonFatal

import scholarchain.core.{Json, RunManifest}
import scholarchain.core.config.{ConfigError, ScholarChainConfig}
import scholarchain.entityresolution.{ConfigEvidence, LoadedConfig}
import scholarchain.entityresolution.decisions.{
  DecisionBatch,
  DecisionExport,
  Preserved,
  PublishResult
}
import scholarchain.entityresolution.policy.MatchPolicy

/**
 * The exit-code contract shared by [[DecisionRunMain]] and [[GroupingRunMain]] (ER-P6-07).
 *
 *   - [[Verified]] (0): published as `<output root>/run=<hex>` and verified twice (the staged
 *     directory before the rename, and the published directory after it). stdout: the summary, the
 *     `verified ...` line, settings and evidence lines.
 *   - [[Refused]] (2): refused; nothing published. stdout: one `error: ...` line. Nothing new is
 *     left under the output root by this attempt, except that the output root directory itself may
 *     have been created, and a `.staging-run=<hex>-<random>` directory remains only if the process
 *     died or deleting it after a write failure failed (readers ignore it). A pre-existing
 *     `run=<hex>` (the no-overwrite refusal) is untouched.
 *   - [[Unverified]] (3): verification failed; artifacts preserved; not accepted by readers.
 *     stdout: one `error: ...` line with the reason; stderr (`err`): exactly one line starting with
 *     [[UnverifiedPrefix]] and naming the preserved directory. Nothing is deleted. No `run=<hex>`
 *     of this attempt exists that a reader accepts. The artifacts are under
 *     `<output root>/.unverified-run=<hex>-<random>` holding `_UNVERIFIED` (normal case); if that
 *     rename failed, under `.staging-run=<hex>-<random>` (staged failure) or `run=<hex>` holding
 *     `_UNVERIFIED` (post-publication failure); only if both the rename and the marker write failed
 *     after publication is `run=<hex>` left unmarked, and the stderr line then says so
 *     ([[unverifiedLine]]).
 */
object RunExit {
  val Verified: Int = 0
  val Refused: Int = 2
  val Unverified: Int = 3

  /** The first characters of the stderr line of an [[Unverified]] exit. */
  val UnverifiedPrefix: String = "unverified: "

  /** Why a run did not end verified. */
  sealed trait Failure { def message: String }

  /** Exit [[Refused]]. */
  final case class Refusal(message: String) extends Failure

  /** Exit [[Unverified]]: the artifacts are preserved at `preserved`. */
  final case class Withdrawn(message: String, preserved: Preserved) extends Failure

  /** The one stderr line of an [[Unverified]] exit. */
  def unverifiedLine(p: Preserved): String =
    if (p.rejectedByReaders)
      s"${UnverifiedPrefix}verification failed; artifacts preserved under ${p.path}; not " +
        "accepted by readers"
    else
      s"${UnverifiedPrefix}verification failed; artifacts left under ${p.path}, which could be " +
        s"neither renamed nor marked ${DecisionExport.UnverifiedMarker}; a reader may accept it: " +
        "move it aside by hand before any reader runs"

  /** Lifts a step that can only refuse. */
  def refusing[A](e: Either[String, A]): Either[Failure, A] = e.left.map(Refusal(_))

  /** The outcome of a publication, with the attempt tag of a published run. */
  def published(r: PublishResult): Either[Failure, (Path, String)] = r match {
    case PublishResult.Published(dir, attempt) => Right((dir, attempt))
    case PublishResult.Refused(why) => Left(Refusal(why))
    case PublishResult.Unverified(p, why) => Left(Withdrawn(why, p))
  }

  /**
   * The post-publication read-back: `readBack` of the published `dir`; when it fails, the run is
   * withdrawn ([[DecisionExport.withdraw]]: marker, then rename; nothing deleted).
   */
  def afterPublication(
      dir: Path,
      attempt: String,
      readBack: Path => Either[String, String],
      move: DecisionExport.Mover
  ): Either[Failure, String] =
    readBack(dir) match {
      case Right(line) => Right(line)
      case Left(why) =>
        val p = DecisionExport.withdraw(dir, attempt, why, move)
        Left(
          Withdrawn(
            s"published $dir, but the integrity read-back failed, so the run is NOT verified and " +
              s"was withdrawn; ${DecisionExport.unverifiedText(p)}: $why",
            p
          )
        )
    }

  /** Prints the result and returns its exit code. */
  def report(
      result: Either[Failure, Vector[String]],
      out: String => Unit,
      err: String => Unit
  ): Int = result match {
    case Right(lines) => lines.foreach(out); Verified
    case Left(Refusal(m)) => out(s"error: $m"); Refused
    case Left(Withdrawn(m, p)) => out(s"error: $m"); err(unverifiedLine(p)); Unverified
  }
}

/**
 * Decision run entry point (ER-P6-02): `DecisionRunMain <decision run manifest .json> <curated run
 * dir> <output root>`. Every path comes from the arguments; nothing is defaulted. It lives with the
 * run tooling of this package because it reads the curated run with [[CuratedRunReader]] and
 * resolves the policy with [[ReviewQueueMain.effectivePolicy]]; the decision logic itself is the
 * review-free [[DecisionExport]] (the structural leakage check keeps that package from naming this
 * one).
 *
 *   1. Resolves the external configuration (`SCHOLARCHAIN_CONFIG_FILES`, core `ConfigLoader`) and
 *      the effective policy exactly as the review-queue tooling does
 *      ([[ReviewQueueMain.effectivePolicy]]: policy and prompt versions must match; thresholds and
 *      band come from the configuration).
 *   2. Reads the decision run manifest, the curated run's `_run-manifest.json`, and the curated run
 *      ([[CuratedRunReader]]: completed stages only, every line validated).
 *   3. Decides every candidate ([[DecisionExport.decideAll]]; fail closed, including the curated
 *      run ID of every candidate, work, mention and profile) against the manifest's `configDigest`,
 *      which must equal [[ScholarChainConfig.digestWithoutGrouping]] of the resolved configuration
 *      (grouping-scope keys are not part of a decision run's identity), and publishes the dataset
 *      all-or-nothing with its `_parts.json` ([[DecisionExport.publishRun]]), which reads the
 *      staged directory back and compares it with the batch BEFORE the rename (ER-P6-07).
 *   4. Reads the published run back with the verified reader [[DecisionExport.readPublished]]
 *      (ER-P6-06, BE-P6-01 requirement 3a) and requires the manifest and every decision read back
 *      to equal the batch it published; prints a `verified decision run=...` line. If this fails,
 *      the run is withdrawn (marked `_UNVERIFIED` and renamed to `.unverified-run=<hex>-<random>`;
 *      nothing deleted).
 *
 * No LLM is called. Exit codes ([[RunExit]], identical for [[GroupingRunMain]]): 0 published and
 * verified; 2 refused, nothing published; 3 verification failed (staged or after publication),
 * artifacts preserved under the path printed on stderr, not accepted by readers.
 */
object DecisionRunMain {
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
    "usage: DecisionRunMain <decision run manifest .json> <curated run dir> <output root>"

  /** At most this many refusal reasons are printed (the count is always printed). */
  val MaxReasons: Int = 20

  /** Reads a run manifest file exactly as this entry point decodes it (the final LF is kept). */
  private[review] def readManifest(p: Path): Either[String, RunManifest] =
    try
      Json
        .decode[RunManifest](Files.readString(p, StandardCharsets.UTF_8))
        .left
        .map(es => s"$p: ${es.map(_.message).mkString("; ")}")
    catch { case NonFatal(e) => Left(s"cannot read $p: ${e.getClass.getName}: ${e.getMessage}") }

  private def reasons(es: Vector[String]): String =
    s"${es.size} reason(s): " + es.take(MaxReasons).mkString(" | ") +
      (if (es.size > MaxReasons) s" | ... ${es.size - MaxReasons} more" else "")

  /** The effective settings of a decision run. */
  def settingsLine(p: MatchPolicy): String =
    s"settings policy=${p.policyVersion} blocking=${p.blockingVersion} " +
      s"features=${p.featureVersion} accept=${p.thresholds.accept} " +
      s"reject=${p.thresholds.reject} llmBand=[${p.llmBand.lower},${p.llmBand.upper}] " +
      s"prompt=${p.adjudication.promptVersion} model=none (no adjudicator ran; no LLM call) " +
      "config=applied"

  /** Summary lines of a published batch: counts by entity kind, outcome and match path. */
  def summary(b: DecisionBatch, dir: Path): Vector[String] = {
    val head = Vector(
      s"published $dir",
      s"decision run=${b.runId} stage=${b.manifest.stage} attempt=${b.manifest.attempt} " +
        s"inputRunId=${b.manifest.parameters(DecisionExport.Params.InputRunId)} " +
        s"decidedAt=${b.manifest.parameters(DecisionExport.Params.DecidedAt)}",
      s"decisions researcher=${b.researcher.size} work=${b.work.size}"
    )
    val counts = Vector("researcher" -> b.researcher, "work" -> b.work).flatMap { case (k, ds) =>
      ds.groupBy(d => (d.decision.tag, d.matchPath.tag))
        .toVector
        .sortBy(_._1)
        .map { case ((o, path), xs) => s"count $k/$o/$path=${xs.size}" }
    }
    head ++ counts
  }

  /**
   * The integrity read-back of a published decision run (ER-P6-06):
   * [[DecisionExport.readPublished]] of `dir` (layout, `_parts.json`, row and byte counts, SHA-256,
   * run ID) must succeed, and the manifest and every decision read back must equal the batch that
   * was published ([[DecisionExport.compareWithBatch]]). Right with the `verified` line; Left with
   * the reason.
   */
  def readBack(b: DecisionBatch, dir: Path): Either[String, String] =
    DecisionExport.readPublished(dir).flatMap(DecisionExport.compareWithBatch(b, _)).map { _ =>
      s"verified decision run=${b.runId} dir=$dir researcher=${b.researcher.size} " +
        s"work=${b.work.size} (read back with DecisionExport.readPublished; equal to the " +
        "published batch)"
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
    import RunExit.refusing
    val result: Either[RunExit.Failure, Vector[String]] = args match {
      case Vector(manifestFile, runDir, outputRoot) =>
        lazy val loaded = source()
        for {
          m <- refusing(readManifest(Path.of(manifestFile)))
          cfg <- refusing(
            loaded.result.left.map(es =>
              "configuration refused: " + es.map(_.message).mkString("; ")
            )
          )
          p <- refusing(ReviewQueueMain.effectivePolicy(() => loaded.result))
          input <- refusing(readManifest(Path.of(runDir).resolve(DecisionExport.ManifestFile)))
          run <- refusing(CuratedRunReader.read(Path.of(runDir)))
          batch <- refusing(
            DecisionExport
              .decideAll(
                p,
                run.works,
                run.profiles,
                run.candidates,
                input,
                m,
                // F4 (CR-P6-03): the decision stage's digest leaves out the grouping scope, so
                // grouping-only settings never change the decision run ID; without grouping keys
                // it equals ScholarChainConfig.digest(cfg), so existing decision manifests stay
                // valid.
                ScholarChainConfig.digestWithoutGrouping(cfg)
              )
              .left
              .map(es => "decision run refused, nothing published; " + reasons(es))
          )
          published <- RunExit.published(
            DecisionExport.publishRun(Path.of(outputRoot), batch, writer)
          )
          (dir, attempt) = published
          verified <- {
            afterRename(dir)
            RunExit.afterPublication(dir, attempt, readBack(batch, _), DecisionExport.AtomicMove)
          }
        } yield summary(batch, dir) ++ Vector(verified, settingsLine(p)) ++
          ConfigEvidence.lines(loaded)
      case _ => Left(RunExit.Refusal(Usage))
    }
    RunExit.report(result, out, err)
  }
}
