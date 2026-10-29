import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardCopyOption}
import java.time.Instant

import scala.sys.process.{Process, ProcessLogger}
import scala.util.Try

import sbt._
import sbt.internal.util.MessageOnlyException
import sbt.protocol.testing.TestResult

/**
 * Writes `.claude/.last-test-run` only after every project's tests passed.
 *
 * The marker records the commit, working-tree state, and test counts of the run so that it is bound
 * to what was tested (docs/agents.md, R1 residual).
 */
object TestMarker {
  final case class Outcome(
      project: String,
      output: Tests.Output,
      productionSources: Int,
      testSources: Int
  ) {
    def passed: Int = output.events.values.map(_.passedCount).sum
  }

  private def git(base: File, args: String*): Option[String] =
    Try(Process("git" +: args, base).!!(ProcessLogger(_ => ()))).toOption.map(_.trim)

  /**
   * Per-project rule (review F3): a project with production sources, or with test sources, must
   * have at least one passing test in that same project. The test-source half refuses suites whose
   * tests are all ignored, skipped, or pending, and test trees that define no runnable test.
   */
  def untestedProjects(outcomes: Seq[Outcome]): Seq[String] =
    outcomes.collect {
      case o if (o.productionSources > 0 || o.testSources > 0) && o.passed == 0 =>
        s"${o.project} (production sources=${o.productionSources}, test sources=${o.testSources}, passed=0)"
    }

  def record(base: File, outcomes: Seq[Outcome], log: Logger): File = {
    val sorted = outcomes.sortBy(_.project)
    // Standard sbt result reporting per project, then a hard pass/fail decision.
    sorted.foreach(o => TestResultLogger.Default.run(log, o.output, s"${o.project} / Test / test"))
    val notPassed = sorted.filter(_.output.overall != TestResult.Passed).map(_.project)
    if (notPassed.nonEmpty)
      throw new MessageOnlyException(
        s"markTestRun: tests did not pass in ${notPassed.mkString(", ")}; marker not written"
      )

    val suites = sorted.flatMap(_.output.events.values)
    val passed = suites.map(_.passedCount).sum
    val failed = suites.map(_.failureCount).sum
    val errored = suites.map(_.errorCount).sum
    val skipped = suites.map(s => s.skippedCount + s.ignoredCount + s.pendingCount).sum
    val canceled = suites.map(_.canceledCount).sum
    val production = sorted.map(_.productionSources).sum

    if (failed + errored + canceled > 0)
      throw new MessageOnlyException(
        s"markTestRun: tests did not pass (failed=$failed errored=$errored canceled=$canceled); marker not written"
      )
    val untested = untestedProjects(sorted)
    if (untested.nonEmpty)
      throw new MessageOnlyException(
        s"markTestRun: no passing test in ${untested.mkString("; ")}; marker not written"
      )
    sorted.foreach { o =>
      log.info(
        s"markTestRun: ${o.project}: production sources=${o.productionSources}, test sources=${o.testSources}, passed=${o.passed}"
      )
    }

    val head = git(base, "rev-parse", "--verify", "HEAD").getOrElse("unknown")
    val worktree = git(base, "--no-optional-locks", "status", "--porcelain")
      .map(s => if (s.isEmpty) "clean" else "dirty")
      .getOrElse("unknown")

    val content = Seq(
      "scholarchain-test-marker-v1",
      "written_by=sbt markTestRun",
      s"written_at_utc=${Instant.now()}",
      s"git_head=$head",
      s"git_worktree=$worktree",
      s"projects=${sorted.map(_.project).mkString(",")}",
      s"suites=${suites.size}",
      s"tests_passed=$passed",
      s"tests_failed=$failed",
      s"tests_errored=$errored",
      s"tests_skipped=$skipped",
      s"production_sources=$production"
    ).mkString("", "\n", "\n")

    val dir = base / ".claude"
    IO.createDirectory(dir)
    val marker = dir / ".last-test-run"
    // The temporary file lives under the ignored build output directory, not in .claude/, so an
    // interrupted run leaves no stray file there. ATOMIC_MOVE fails (and no marker is written) if
    // target/ is on a different filesystem from .claude/.
    val tmpDir = base / "target" / "test-marker"
    IO.createDirectory(tmpDir)
    val tmp = Files.createTempFile(tmpDir.toPath, ".last-test-run.", ".tmp")
    Files.write(tmp, content.getBytes(StandardCharsets.UTF_8))
    Files.move(
      tmp,
      marker.toPath,
      StandardCopyOption.REPLACE_EXISTING,
      StandardCopyOption.ATOMIC_MOVE
    )
    log.info(
      s"markTestRun: wrote ${marker.getPath} (git_head=$head, worktree=$worktree, suites=${suites.size}, passed=$passed, skipped=$skipped)"
    )
    marker
  }
}
