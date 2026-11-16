import java.io.{BufferedReader, ByteArrayOutputStream, IOException, InputStream, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.nio.file.{FileVisitResult, Files, LinkOption, Path, Paths, SimpleFileVisitor}
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.util.Try

import sbt._
import sbt.internal.util.MessageOnlyException

/**
 * Fixed-shape local runs (work orders BE-P5-02 and BE-P5-04; human decisions-15 to -19 and
 * amendment FE-P5-06). Owner: build-engineer.
 *
 * Root tasks without arguments, so each matches one fixed Bash allowlist row (or, for the held-out
 * evaluation, the human's Terminal only):
 *   - localPipelineDev / localPipelineHeldout fork `ErSplitPipelineMain <split>` (hadoop-engineer),
 *     which owns its inputs, its output root and attempt directories, and its retry rule. The build
 *     adds the envelope: committed manifest and sample ledgers, a bounded symlink-free samples
 *     tree, the repository root as working directory, AWS isolation, no stdin, JVM scratch under
 *     target/, a wall-clock bound, and an attempt report on every outcome. Nothing is deleted.
 *   - goldSetEvalDev / goldSetEvalHeldout fork `GoldSetEvalMain` (entity-resolution-engineer) on
 *     the human-owned files of gold/entity-resolution/<split>-v1/ with `--manifest-sha256-file` and
 *     `--expect-split`. The build never reads, computes, or embeds the freeze hash: GoldSetEvalMain
 *     parses the record, re-hashes the membership, and refuses. SCHOLARCHAIN_CONFIG_FILES is set to
 *     that split's er-config.conf; the task refuses while any SCHOLARCHAIN_CFG_* is inherited.
 *   - localPipeline / goldSetEval refuse and name the two split tasks.
 *
 * Held-out split (FE-P5-06 step 2; hardened by BE-P5-05 for review findings CR-P5-05 BE-1, BE-2):
 *   - Launch (BE-2): both held-out tasks refuse unless sbt is a plain batch JVM (`sbt <task>`)
 *     whose stdout and stderr are the interactive terminal: not the thin client (`sbt --client`,
 *     sbtn), not an sbt server or BSP/IDE server, not the interactive sbt shell, not redirected.
 *     `launchProblems` is the pure decision over `LaunchSignals`; `verifyHeldoutGuards` (in
 *     `sbt check`) tests it with synthetic signals, because the agent-run `sbt check` has no
 *     terminal.
 *   - D3(b) (BE-1): both held-out tasks refuse unless, in `build.sbt project modules tests datasets
 *     gold`, every tracked file has no index flag (`git ls-files -v`: only `H`; `S` skip-worktree,
 *     lower case assume-unchanged, `M` unmerged are refused), git lists no modified, staged,
 *     deleted, or untracked path, and every ignored path git lists (`--ignored=matching`, so
 *     ignores from .gitignore files at any depth, .git/info/exclude, and core.excludesFile all
 *     count) is on the exact allow set of `heldoutIgnoredAllowSet`; and the root .gitignore is
 *     committed and unmodified. Git runs without inherited GIT_* variables, with fsmonitor and the
 *     untracked cache off, and within a time bound; any git error or timeout refuses.
 *   - D5 = L1: the held-out labels file must be a regular, symlink-free, git-ignored file; the
 *     other four gold files (and .gitignore, which carries the L1 line) are committed and
 *     byte-identical to HEAD.
 *   - D15(c): the forked child's stdout and stderr are inherited (ProcessBuilder.Redirect.INHERIT):
 *     they go to sbt's own file descriptors 1 and 2, i.e. the terminal sbt was started from, and
 *     never pass through the sbt JVM, an sbt Logger, or any file under target/streams. Only the
 *     task's own envelope messages (which carry no child output) use the sbt log. `outputRoute` is
 *     the single place that decides this; `verifyHeldoutOutputRoute` (part of `sbt check`) tests
 *     that exact function with synthetic output.
 *
 * The forked JVMs are started with java.lang.ProcessBuilder so inherited AWS_* and SCHOLARCHAIN_*
 * variables are removed (not just blanked) and the JVM can be stopped on timeout. None of the run
 * tasks is part of `check`, and none writes the test marker.
 */
object LocalRuns {

  sealed abstract class Split(val name: String, val title: String)
  case object Dev extends Split("dev", "Dev")
  case object Heldout extends Split("heldout", "Heldout")

  // Build-envelope bounds, not data policy (arithmetic in the BE-P5-02 report: dev pipeline
  // 10.957 s measured, held-out ~2x, ~180 s agent tool limit including sbt start-up).
  val PipelineTimeoutSeconds: Long = 90L
  val EvalTimeoutSeconds: Long = 60L
  val StopGraceSeconds: Long = 10L
  val OutputDrainMillis: Long = 5000L
  val RouteProbeTimeoutSeconds: Long = 30L
  val GitTimeoutSeconds: Long = 30L
  val MaxSampleBytes: Long = 64L * 1024L * 1024L
  val MaxSampleFiles: Int = 1000
  val MaxCommittedFileBytes: Long = 16L * 1024L * 1024L

  val PipelineMain = "scholarchain.hadoop.local.ErSplitPipelineMain"
  val EvalMain = "scholarchain.entityresolution.evaluation.GoldSetEvalMain"
  val ConfigFilesEnvVar = "SCHOLARCHAIN_CONFIG_FILES"
  val ConfigOverridePrefix = "SCHOLARCHAIN_CFG_"
  val RouteMarkerEnvVar: String = LocalRunsRouteEmitter.MarkerEnvVar
  val RouteEmitterMain = "LocalRunsRouteEmitter"

  /** Inherited variables removed from every forked local-run JVM (by name prefix). */
  val DefaultStrippedPrefixes: Seq[String] = Seq("AWS_", "SCHOLARCHAIN_")

  // Repository-relative paths. The pipeline paths mirror ErSplit (hadoop-engineer); the build only
  // checks or reports them.
  val PipelineRunsRoot = "modules/hadoop/local-runs"
  def pipelineManifest(s: Split): String =
    s"$PipelineRunsRoot/er-candidates.${s.name}.run-manifest.json"
  val SamplesRoot = "datasets/samples/er-candidates"
  val SampleLedgers: Seq[String] =
    Seq(s"$SamplesRoot/PROVENANCE.tsv", s"$SamplesRoot/DBLP-RELEASE-RANGES.tsv")
  def samplesRoot(s: Split): String = s"$SamplesRoot/${s.name}"
  def attemptsRoot(s: Split): String =
    s"modules/hadoop/target/local-pipeline/er-candidates/${s.name}"
  def goldDir(s: Split): String = s"gold/entity-resolution/${s.name}-v1"
  def scratchRoot(s: Split): String = s"target/local-runs/${s.name}"
  val RouteCheckScratch = "target/route-check"

  /** D3(b) scope: the held-out tasks refuse while git reports anything here. */
  val CleanTreeScope: Seq[String] =
    Seq("build.sbt", "project", "modules", "tests", "datasets", "gold")

  /** Meta-build output directories (sbt compiles project/ and project/project/ into these). */
  val MetaBuildTargets: Seq[String] = Seq("project/target/", "project/project/target/")

  /** The human-confirmed full-download directory (role law 8; .gitignore /datasets/downloads/). */
  val DatasetDownloads = "datasets/downloads/"

  val MembershipFile = "er-gold-membership.jsonl"
  val FreezeFile: String = MembershipFile + ".sha256"
  val ConfigFile = "er-config.conf"
  val QueueFile = "er-review-queue.jsonl"
  val LabelsFile = "er-gold-labels.jsonl"

  private val AttemptName = "^attempt-([1-9][0-9]*)$".r

  // --- Output routing (D15(c)) ---------------------------------------------------------

  sealed trait OutputRoute

  /** Child stdout/stderr inherited: written straight to sbt's fds 1 and 2, never via sbt. */
  case object ToTerminal extends OutputRoute

  /** Child stdout/stderr read by the build and written through the given sbt task logger. */
  final case class ToLog(log: Logger) extends OutputRoute

  /**
   * The single routing decision for every forked local run. Held-out output never passes through an
   * sbt logger, so sbt persists none of it under target/streams. Dev output stays on the task log.
   */
  def outputRoute(split: Split, log: Logger): OutputRoute = split match {
    case Heldout => ToTerminal
    case Dev => ToLog(log)
  }

  // --- Small helpers ---------------------------------------------------------------------

  private def refuse(task: String, message: String): Nothing =
    throw new MessageOnlyException(s"$task: refused: $message")

  private def fail(task: String, message: String): Nothing =
    throw new MessageOnlyException(s"$task: $message")

  /** The bare names have no default split. */
  def chooseSplit(name: String): Unit =
    throw new MessageOnlyException(
      s"$name has no default split and takes no arguments; run ${name}Dev or ${name}Heldout"
    )

  /**
   * Runs git with the given arguments: Some((exit code, stdout)), or None (did not run, timeout).
   */
  type GitRunner = Seq[String] => Option[(Int, String)]

  /**
   * One git process in `base`: stdin closed, stderr discarded, every inherited GIT_* variable
   * removed (so GIT_DIR, GIT_INDEX_FILE, GIT_WORK_TREE and similar cannot redirect the checks; only
   * the names are used), then `env` added. None when git cannot start, exceeds `timeoutSeconds`
   * (the process is killed), or its output cannot be read completely.
   */
  private def gitProcess(
      base: File,
      env: Map[String, String],
      timeoutSeconds: Long,
      args: Seq[String]
  ): Option[(Int, String)] =
    Try {
      val pb = new java.lang.ProcessBuilder(("git" +: args).asJava)
      pb.directory(base)
      pb.redirectError(java.lang.ProcessBuilder.Redirect.DISCARD)
      val e = pb.environment()
      e.keySet.asScala.toList.filter(_.startsWith("GIT_")).foreach(k => e.remove(k))
      env.foreach { case (k, v) => e.put(k, v) }
      val p = pb.start()
      p.getOutputStream.close()
      val buf = new ByteArrayOutputStream
      val reader = new Thread(
        new Runnable {
          def run(): Unit = {
            val in = p.getInputStream
            try {
              val b = new Array[Byte](1 << 16)
              var r = in.read(b)
              while (r != -1) { buf.write(b, 0, r); r = in.read(b) }
            } catch { case _: IOException => () }
            finally { Try(in.close()); () }
          }
        },
        "local-runs-git"
      )
      reader.setDaemon(true)
      reader.start()
      if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        p.destroyForcibly()
        p.waitFor(StopGraceSeconds, TimeUnit.SECONDS)
        None
      } else {
        reader.join(OutputDrainMillis)
        if (reader.isAlive) None
        else Some((p.exitValue(), new String(buf.toByteArray, StandardCharsets.UTF_8)))
      }
    }.toOption.flatten

  private def gitRunner(base: File): GitRunner =
    args => gitProcess(base, Map.empty, GitTimeoutSeconds, args)

  /** Exit code and stdout of a git command (stderr discarded); None if git could not run. */
  private def gitRun(base: File, args: String*): Option[(Int, String)] = gitRunner(base)(args)

  /** Trimmed stdout of a successful git command with non-empty output. */
  private def git(base: File, args: String*): Option[String] =
    gitWith(gitRunner(base), args: _*)

  private def gitWith(run: GitRunner, args: String*): Option[String] =
    run(args).collect { case (0, out) if out.trim.nonEmpty => out.trim }

  private def realBase(base: File): File = base.toPath.toRealPath().toFile

  private def rel(base: File, f: File): String = base.toPath.relativize(f.toPath).toString

  private def sha256Hex(s: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(s.getBytes(StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  private def logHead(task: String, base: File, log: Logger): Unit = {
    val head = git(base, "rev-parse", "--verify", "HEAD")
      .getOrElse(
        refuse(task, "git rev-parse --verify HEAD failed; the local runs need git and a commit")
      )
    val worktree = gitRun(base, "--no-optional-locks", "status", "--porcelain")
      .collect { case (0, s) => if (s.trim.isEmpty) "clean" else "dirty" }
      .getOrElse("unknown")
    log.info(s"$task: git_head=$head git_worktree=$worktree")
  }

  // --- D3(b) clean-tree check (BE-1) ------------------------------------------------------

  /**
   * Options for every clean-tree git call: no index refresh written (--no-optional-locks), and no
   * fsmonitor hook or untracked cache, either of which could tell git that nothing changed.
   */
  private val TreeGitOptions: Seq[String] =
    Seq("--no-optional-locks", "-c", "core.fsmonitor=false", "-c", "core.untrackedCache=false")

  /**
   * The exact ignored paths a held-out run accepts in the D3(b) scope, as `git status --porcelain
   * --ignored=matching` prints them (a matched directory with a trailing slash; its contents are
   * not listed). Exact string equality only; no pattern, no prefix.
   *   - `<base>/target/` of every sbt project of this build inside the scope (from the build's own
   *     `target` settings), and `project/target/` and `project/project/target/` (meta-build): sbt
   *     writes them on every load and compile, so no run could pass without them. sbt reads no
   *     source or resource from them unless a generator writes it there.
   *   - `datasets/downloads/` as one directory entry: the human-confirmed full-download location
   *     (role law 8 requires it ignored; moving gigabytes before each run is not practical). It is
   *     not an sbt source, resource, or classpath directory, and neither held-out task reads it.
   *   - `gold/entity-resolution/heldout-v1/er-gold-labels.jsonl`, only when `withLabels`
   *     (goldSetEvalHeldout): decision D5 = L1 keeps it ignored and the evaluation reads it.
   *     localPipelineHeldout does not need it, so it refuses while the file exists.
   * Everything else ignored is refused, including `.DS_Store`, `project/metals.sbt`,
   * `project/project/metals.sbt`, and `project/.bloop/`.
   */
  def heldoutIgnoredAllowSet(
      buildBase: File,
      projectTargets: Seq[File],
      withLabels: Boolean
  ): Set[String] = {
    val basePath = buildBase.toPath.toAbsolutePath.normalize
    val projectDirs = projectTargets
      .map(t => basePath.relativize(t.toPath.toAbsolutePath.normalize).toString.replace('\\', '/'))
      .filter(r => CleanTreeScope.exists(s => r.startsWith(s + "/")))
      .map(_ + "/")
    val labels = if (withLabels) Seq(s"${goldDir(Heldout)}/$LabelsFile") else Nil
    (projectDirs ++ MetaBuildTargets ++ Seq(DatasetDownloads) ++ labels).toSet
  }

  /** What git reports for the D3(b) scope. Refuses only when git fails or times out. */
  final case class TreeState(
      tracked: Int,
      flagged: Vector[String],
      changes: Vector[String],
      ignoredAllowed: Vector[String],
      ignoredRefused: Vector[String]
  )

  /**
   * `paths` and `context` default to the D3(b) scope and wording (Phase 5 behaviour unchanged); the
   * BE-P6-02 code-scope check passes its own scope, allow predicate, and wording.
   */
  private def readTreeState(
      task: String,
      run: GitRunner,
      allowIgnored: String => Boolean,
      paths: Seq[String] = CleanTreeScope,
      context: String = "the clean-tree check (decision D3(b))"
  ): TreeState = {
    def ok(args: Seq[String], what: String): String = run(TreeGitOptions ++ args) match {
      case Some((0, out)) => out
      case Some((n, _)) =>
        refuse(task, s"$what exited $n; $context cannot run")
      case None =>
        refuse(task, s"$what did not run or exceeded ${GitTimeoutSeconds}s; $context cannot run")
    }
    val scope = "--" +: paths
    val index = ok(Seq("ls-files", "-v", "-z") ++ scope, "git ls-files -v")
      .split('\u0000')
      .filter(_.nonEmpty)
      .toVector
    // `H ` is a plain tracked file. `S` skip-worktree, lower case assume-unchanged, `M` unmerged:
    // git status cannot see changes behind the first two, so they are refused.
    val flagged = index.filterNot(_.startsWith("H "))
    val status = ok(
      Seq("status", "--porcelain=v1", "-z", "--ignored=matching", "--untracked-files=all") ++ scope,
      "git status --ignored=matching"
    ).split('\u0000').toVector
    val changes = Vector.newBuilder[String]
    val ignored = Vector.newBuilder[String]
    var i = 0
    while (i < status.size) {
      val entry = status(i)
      if (entry.nonEmpty) {
        if (entry.startsWith("!! ")) ignored += entry.drop(3)
        else changes += entry
        // A rename or copy entry (either column) is followed by its original path.
        if (entry.length > 1 && entry.take(2).exists(c => c == 'R' || c == 'C')) i += 1
      }
      i += 1
    }
    val (allowed, refused) = ignored.result().partition(allowIgnored)
    TreeState(index.size, flagged, changes.result(), allowed, refused)
  }

  private def firstOf(xs: Seq[String]): String = xs.take(10).mkString("; ")

  /**
   * D3(b) for the held-out tasks: the full check against `base` with `run` (injectable for the
   * temporary-repository self-test). Returns the summary line, or refuses.
   */
  def heldoutTreeCheck(
      task: String,
      base: File,
      run: GitRunner,
      allowIgnored: Set[String]
  ): String = {
    val st = readTreeState(task, run, allowIgnored)
    val scope = CleanTreeScope.mkString(" ")
    if (st.tracked == 0)
      refuse(task, s"git ls-files lists no tracked file in $scope; this is not the repository")
    if (st.flagged.nonEmpty)
      refuse(
        task,
        s"git ls-files -v -- $scope lists ${st.flagged.size} tracked file(s) with an index flag " +
          "(S = skip-worktree, lower case = assume-unchanged, M = unmerged); git status cannot see " +
          "changes behind them (decision D3(b)); clear the flags (git update-index " +
          s"--no-skip-worktree / --no-assume-unchanged) and commit; first: ${firstOf(st.flagged)}"
      )
    if (st.changes.nonEmpty)
      refuse(
        task,
        s"git status --porcelain --untracked-files=all -- $scope lists ${st.changes.size} " +
          "path(s) (decision D3(b): the held-out run needs a committed, clean tree); first: " +
          firstOf(st.changes)
      )
    if (st.ignoredRefused.nonEmpty)
      refuse(
        task,
        s"git status --ignored=matching -- $scope lists ${st.ignoredRefused.size} ignored path(s) " +
          "that are not on the held-out allow set (ignored files are invisible to git status and " +
          "to the Bash audit, but sbt or the run could still read them; decision D3(b)); remove " +
          s"them first; first: ${firstOf(st.ignoredRefused)}"
      )
    // The root .gitignore decides what git ignores (and carries the L1 line): committed, unmodified.
    committedFileWith(task, base, ".gitignore", run)
    s"$task: clean tree in $scope (decision D3(b)): ${st.tracked} tracked files without index " +
      s"flags; no modified, staged, deleted, or untracked path; ${st.ignoredAllowed.size} ignored " +
      s"path(s), all on the allow set (${st.ignoredAllowed.sorted.mkString(", ")}); .gitignore " +
      "committed and unmodified"
  }

  private def requireCleanTree(
      task: String,
      base: File,
      allowIgnored: Set[String],
      log: Logger
  ): Unit =
    log.info(heldoutTreeCheck(task, base, gitRunner(base), allowIgnored))

  // --- Launch check (BE-2) ----------------------------------------------------------------

  /** What a standard-stream file descriptor of the sbt JVM is connected to. */
  sealed trait FdKind

  /** A character device other than /dev/null (a terminal). */
  case object FdCharDevice extends FdKind
  case object FdDevNull extends FdKind
  case object FdPipe extends FdKind
  case object FdRegularFile extends FdKind
  case object FdSocket extends FdKind
  final case class FdOther(mode: Int) extends FdKind
  final case class FdUnknown(reason: String) extends FdKind

  /**
   * Observable inputs of the launch decision. Real values come from `observeLaunch`; the self-test
   * in `verifyHeldoutGuards` supplies synthetic ones.
   *   - javaFeature, javaVersion: the sbt JVM (the forked children use the same java.home).
   *   - consolePresent: System.console() != null. Before JDK 22 it is non-null only when stdin and
   *     stdout are both terminals; from JDK 22 it can be non-null when output is redirected.
   *   - isTerminalMethod / consoleIsTerminal: java.io.Console.isTerminal() (JDK 22+) exists, and
   *     its answer (None when the console is null or the call failed).
   *   - jdkConsoleProperty: the jdk.console system property (it selects the console provider).
   *   - stdout, stderr: the file type of /dev/fd/1 and /dev/fd/2 (what the children inherit).
   *   - sbtArguments: the sbt launcher arguments (AppConfiguration.arguments).
   *   - commandChannel: the channel that issued the running command (None for the batch command
   *     line; `console0` in the interactive shell; `network-<n>` for a thin client, BSP, or IDE).
   *   - remainingCommands: queued command lines (`shell` is queued in the interactive shell and in
   *     server mode while a command runs).
   */
  final case class LaunchSignals(
      javaFeature: Int,
      javaVersion: String,
      consolePresent: Boolean,
      isTerminalMethod: Boolean,
      consoleIsTerminal: Option[Boolean],
      jdkConsoleProperty: Option[String],
      stdout: FdKind,
      stderr: FdKind,
      sbtArguments: Seq[String],
      commandChannel: Option[String],
      remainingCommands: Seq[String]
  )

  /** sbt launcher arguments of a server, thin-client, BSP, or shell launch. */
  val NonBatchArguments: Set[String] =
    Set(
      "--server",
      "--detach-stdio",
      "--client",
      "-client",
      "--jvm-client",
      "-bsp",
      "--bsp",
      "shell"
    )

  /**
   * Command channels allowed for the held-out tasks: only the batch command line, whose commands
   * carry no source (observed in batch `sbt check`, sbt 1.11.7). The interactive shell's console
   * channel (`console0`) and every network channel are refused.
   */
  val BatchChannels: Set[Option[String]] = Set(None)

  private def fdOk(k: FdKind): Boolean = k == FdCharDevice

  /**
   * The pure launch decision: empty when the held-out output, inherited by the forked JVM, goes to
   * the interactive terminal of a plain batch `sbt <task>`; otherwise one reason per failed signal.
   */
  def launchProblems(s: LaunchSignals): Seq[String] = {
    val p = ListBuffer.empty[String]
    val nonBatch = s.sbtArguments.filter(NonBatchArguments.contains)
    if (nonBatch.nonEmpty)
      p += s"sbt was started with ${nonBatch.mkString(" ")} (server, thin-client, BSP, or shell launch)"
    if (!BatchChannels.contains(s.commandChannel))
      p += s"the command came from channel ${s.commandChannel.getOrElse("-")}, not the batch " +
        "command line (sbt --client, sbtn, BSP, or an IDE talking to an sbt server)"
    if (s.remainingCommands.exists(_.trim.split("\\s+").headOption.contains("shell")))
      p += "the sbt shell is queued (interactive sbt shell or sbt server), not a batch run"
    if (!s.consolePresent)
      p += "System.console() is null: stdin or stdout is not a terminal"
    else if (s.isTerminalMethod) {
      if (s.consoleIsTerminal != Some(true))
        p += "java.io.Console.isTerminal() is " +
          s.consoleIsTerminal.fold("unavailable")(
            _.toString
          ) + ": stdin or stdout is not a terminal"
    } else if (s.javaFeature >= 22)
      p += s"JDK ${s.javaFeature} without java.io.Console.isTerminal(): the terminal cannot be confirmed"
    else if (s.jdkConsoleProperty.nonEmpty)
      p += s"jdk.console=${s.jdkConsoleProperty.get} on JDK ${s.javaFeature}: System.console() may " +
        "be non-null with redirected output, so the terminal cannot be confirmed"
    if (!fdOk(s.stdout)) p += s"stdout (fd 1) is ${s.stdout}, not a terminal"
    if (!fdOk(s.stderr)) p += s"stderr (fd 2) is ${s.stderr}, not a terminal"
    p.toList
  }

  private def fdKind(fd: Int): FdKind =
    Try {
      val p = Paths.get(s"/dev/fd/$fd")
      val mode = Files.getAttribute(p, "unix:mode").asInstanceOf[java.lang.Integer].intValue
      (mode & 0xf000) match {
        case 0x2000 =>
          val rdev = Files.getAttribute(p, "unix:rdev").asInstanceOf[java.lang.Long].longValue
          val nullRdev =
            Files.getAttribute(Paths.get("/dev/null"), "unix:rdev").asInstanceOf[java.lang.Long]
          if (rdev == nullRdev.longValue) FdDevNull else FdCharDevice
        case 0x1000 => FdPipe
        case 0x8000 => FdRegularFile
        case 0xc000 => FdSocket
        case other => FdOther(other)
      }
    }.fold(e => FdUnknown(s"${e.getClass.getSimpleName}: ${e.getMessage}"), identity)

  /** The real signals of this sbt JVM and of the command running the task. */
  def observeLaunch(state: State): LaunchSignals = {
    val console = System.console()
    val method = Try(classOf[java.io.Console].getMethod("isTerminal")).toOption
    val isTerminal = method.flatMap { m =>
      if (console == null) None
      else Try(m.invoke(console).asInstanceOf[java.lang.Boolean].booleanValue).toOption
    }
    LaunchSignals(
      javaFeature = java.lang.Runtime.version().feature(),
      javaVersion = s"${System.getProperty("java.version")} (${System.getProperty("java.vendor")})",
      consolePresent = console != null,
      isTerminalMethod = method.nonEmpty,
      consoleIsTerminal = isTerminal,
      jdkConsoleProperty = Option(System.getProperty("jdk.console")),
      stdout = fdKind(1),
      stderr = fdKind(2),
      sbtArguments = Try(state.configuration.arguments.toSeq).getOrElse(Seq("<unreadable>")),
      commandChannel = state.currentCommand.flatMap(_.source).map(_.channelName),
      remainingCommands = state.remainingCommands.map(_.commandLine)
    )
  }

  private def requireTerminalBatch(task: String, launch: LaunchSignals, log: Logger): Unit = {
    val problems = launchProblems(launch)
    if (problems.nonEmpty)
      refuse(
        task,
        "held-out output goes straight to the terminal sbt was started from, so this task runs " +
          "only as plain batch `sbt <task>` in an interactive Terminal (no --client/sbtn, no sbt " +
          s"server or shell, no BSP/IDE, no redirection or pipe): ${problems.mkString("; ")}"
      )
    log.info(
      s"$task: plain batch sbt with stdout and stderr on a terminal (JDK ${launch.javaVersion})"
    )
  }

  /** A repository path that resolves without a symbolic link or a differently cased component. */
  private def inside(task: String, base: File, relPath: String): File = {
    val f = new File(base, relPath)
    val abs = f.toPath.toAbsolutePath.normalize
    if (Files.exists(abs, LinkOption.NOFOLLOW_LINKS) && abs.toRealPath() != abs)
      refuse(task, s"$relPath resolves through a symbolic link or a differently cased path")
    f
  }

  private def regularBoundedFile(task: String, base: File, relPath: String): File = {
    val f = inside(task, base, relPath)
    if (!Files.isRegularFile(f.toPath, LinkOption.NOFOLLOW_LINKS))
      refuse(task, s"$relPath is missing or not a regular file")
    if (f.length > MaxCommittedFileBytes)
      refuse(task, s"$relPath is larger than $MaxCommittedFileBytes bytes")
    f
  }

  /** A regular file committed at HEAD and byte-identical to it (raw bytes, no filters). */
  private def committedFile(task: String, base: File, relPath: String): File =
    committedFileWith(task, base, relPath, gitRunner(base))

  private def committedFileWith(task: String, base: File, relPath: String, run: GitRunner): File = {
    val f = regularBoundedFile(task, base, relPath)
    val atHead = gitWith(run, "rev-parse", "--verify", "--quiet", s"HEAD:$relPath")
    val inTree = gitWith(run, "hash-object", "--no-filters", "--", relPath)
    (atHead, inTree) match {
      case (Some(h), Some(w)) if h == w => f
      case (None, _) =>
        refuse(task, s"$relPath is not committed at HEAD; the human commits it before the run")
      case (_, None) => refuse(task, s"$relPath could not be hashed with git hash-object")
      case _ => refuse(task, s"$relPath differs from HEAD; commit it unmodified before the run")
    }
  }

  /**
   * D5 = L1: a regular, symlink-free file that git ignores (`git check-ignore`). A tracked file is
   * not ignored, so it is refused too. The file's content is never read here.
   */
  private def ignoredFile(task: String, base: File, relPath: String): File = {
    val f = regularBoundedFile(task, base, relPath)
    gitRun(base, "check-ignore", "-q", "--", relPath) match {
      case Some((0, _)) => f
      case Some((1, _)) =>
        refuse(
          task,
          s"$relPath is not git-ignored (decision D5 = L1: held-out labels stay ignored, and untracked, " +
            "until the human accepts the held-out evaluation; the committed .gitignore carries the line)"
        )
      case _ => refuse(task, s"git check-ignore failed for $relPath")
    }
  }

  /** Bytes and regular files under a directory; refuses links, special files, and the bounds. */
  private def measureTree(task: String, base: File, relPath: String): (Long, Int) = {
    val dir = inside(task, base, relPath)
    if (!Files.isDirectory(dir.toPath, LinkOption.NOFOLLOW_LINKS))
      refuse(task, s"$relPath/ is missing or not a directory")
    val walk = Files.walk(dir.toPath)
    val (bytes, files) =
      try
        walk.iterator.asScala.foldLeft((0L, 0)) { case ((b, n), p) =>
          if (Files.isSymbolicLink(p))
            refuse(task, s"symbolic link in $relPath/: ${base.toPath.relativize(p)}")
          else if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) (b, n)
          else if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
            val b1 = b + Files.size(p)
            val n1 = n + 1
            if (b1 > MaxSampleBytes || n1 > MaxSampleFiles)
              refuse(
                task,
                s"$relPath/ exceeds the bound of $MaxSampleFiles files / $MaxSampleBytes bytes"
              )
            (b1, n1)
          } else refuse(task, s"special file in $relPath/: ${base.toPath.relativize(p)}")
        }
      finally walk.close()
    if (files == 0) refuse(task, s"$relPath/ contains no regular file")
    (bytes, files)
  }

  private final case class Attempt(n: Int, dir: File) {
    def status: String =
      if (new File(dir, "_SUCCESS").exists) "complete"
      else if (new File(dir, "_FAILED").exists) "failed"
      else "partial"
  }

  /** Existing attempt-<n> directories of a split (read only; mirrors ErSplit's layout). */
  private def attempts(base: File, split: Split): List[Attempt] = {
    val root = (base / attemptsRoot(split)).toPath
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) Nil
    else {
      val listing = Files.list(root)
      try
        listing.iterator.asScala.toList
          .flatMap { p =>
            p.getFileName.toString match {
              case AttemptName(n) if Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) =>
                Try(n.toInt).toOption.map(Attempt(_, p.toFile)).toList
              case _ => Nil
            }
          }
          .sortBy(_.n)
      finally listing.close()
    }
  }

  /** The attempt this run created (newer than `before`), and a sentence describing it. */
  private def attemptState(
      base: File,
      split: Split,
      before: Option[Int]
  ): (Option[Attempt], String) =
    attempts(base, split).lastOption.filter(a => before.forall(_ < a.n)) match {
      case Some(a) if a.status == "complete" =>
        (Some(a), s"attempt directory ${rel(base, a.dir)} is complete (_SUCCESS present)")
      case Some(a) =>
        val state =
          if (a.status == "failed") "FAILED (_FAILED present); not evidence"
          else "PARTIAL (neither _SUCCESS nor _FAILED); incomplete and not evidence"
        (
          Some(a),
          s"this run's attempt directory ${rel(base, a.dir)} is $state; nothing was deleted; the " +
            s"next run uses a fresh attempt directory (attempt-${a.n + 1}) and reports this one as " +
            s"status=${a.status}"
        )
      case None =>
        (
          None,
          s"this run created no attempt directory under ${attemptsRoot(split)}/; nothing was deleted"
        )
    }

  private def scratchOptions(base: File, relRoot: String, kind: String): Seq[String] = {
    val root = base / relRoot
    IO.createDirectory(root)
    val dir = Files.createTempDirectory(root.toPath, s"$kind-jvm-").toFile
    IO.createDirectory(dir / "tmp")
    Seq(
      s"-Djava.io.tmpdir=${(dir / "tmp").getAbsolutePath}",
      s"-XX:ErrorFile=${(dir / "hs_err_pid%p.log").getAbsolutePath}"
    )
  }

  private def pump(name: String, in: InputStream, log: Logger): Thread = {
    val t = new Thread(
      new Runnable {
        def run(): Unit = {
          val r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
          try {
            var line = r.readLine()
            while (line != null) {
              log.info(line)
              line = r.readLine()
            }
          } catch { case _: IOException => () }
          finally { Try(r.close()); () }
        }
      },
      name
    )
    t.setDaemon(true)
    t.start()
    t
  }

  /**
   * Forks one JVM in `base`; Some(exit code), or None when the bound was reached. `log` carries
   * only the envelope messages written here; the child's own output follows `route`.
   *
   * With ToTerminal the child writes directly to the inherited descriptors, so there is nothing to
   * pump or drain: lines appear as they are written, also when the bound is reached and the JVM is
   * stopped. The exit code, the timeout decision, and the attempt-directory report do not depend on
   * reading the child's output, so they work the same on both routes.
   *
   * Not private since BE-P6-03: `sparkIT` (project/SparkItRuns.scala) reuses it unchanged.
   */
  def runForked(
      task: String,
      base: File,
      javaHome: Option[File],
      classpath: Seq[File],
      jvmOptions: Seq[String],
      env: Map[String, String],
      mainClass: String,
      args: Seq[String],
      timeoutSeconds: Long,
      route: OutputRoute,
      log: Logger,
      stripPrefixes: Seq[String] = DefaultStrippedPrefixes
  ): Option[Int] = {
    val javaBin = javaHome.getOrElse(new File(System.getProperty("java.home"))) / "bin" / "java"
    if (!javaBin.isFile) refuse(task, s"no java executable at $javaBin")
    val cp = classpath.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator)
    val command =
      Seq(javaBin.getAbsolutePath) ++ jvmOptions ++ Seq("-classpath", cp, mainClass) ++ args
    val builder = new java.lang.ProcessBuilder(command.asJava)
    builder.directory(base)
    route match {
      case ToTerminal =>
        builder.redirectOutput(java.lang.ProcessBuilder.Redirect.INHERIT)
        builder.redirectError(java.lang.ProcessBuilder.Redirect.INHERIT)
      case ToLog(_) =>
        builder.redirectErrorStream(true)
    }
    val childEnv = builder.environment()
    childEnv.keySet.asScala.toList
      .filter(k => stripPrefixes.exists(k.startsWith))
      .foreach(k => childEnv.remove(k))
    env.foreach { case (k, v) => childEnv.put(k, v) }
    val routeText = route match {
      case ToTerminal => "child output straight to the terminal, not through the sbt log"
      case ToLog(_) => "child output through the sbt task log"
    }
    log.info(
      s"$task: forking $mainClass ${args.mkString(" ")} in $base " +
        s"(classpath ${classpath.size} entries; bound ${timeoutSeconds}s; no stdin; $routeText)"
    )
    val child =
      try builder.start()
      catch {
        case e: IOException => fail(task, s"could not start the forked JVM: ${e.getMessage}")
      }
    child.getOutputStream.close()
    val killer = new Thread(new Runnable { def run(): Unit = { child.destroyForcibly(); () } })
    java.lang.Runtime.getRuntime.addShutdownHook(killer)
    val output: Option[Thread] = route match {
      case ToLog(childLog) => Some(pump(s"$task-output", child.getInputStream, childLog))
      case ToTerminal => None
    }
    def drain(): Unit = output.foreach(_.join(OutputDrainMillis))
    try {
      if (child.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        drain()
        Some(child.exitValue())
      } else {
        log.error(s"$task: the ${timeoutSeconds}s bound was reached; stopping the forked JVM")
        child.destroy()
        if (!child.waitFor(StopGraceSeconds, TimeUnit.SECONDS)) {
          child.destroyForcibly()
          child.waitFor(StopGraceSeconds, TimeUnit.SECONDS)
        }
        drain()
        None
      }
    } catch {
      case e: InterruptedException =>
        child.destroyForcibly()
        throw e
    } finally {
      Try(java.lang.Runtime.getRuntime.removeShutdownHook(killer))
      ()
    }
  }

  // --- Local pipeline --------------------------------------------------------------------

  /** Bounded local pipeline for one split (ErSplitPipelineMain <split>). */
  def localPipeline(
      split: Split,
      buildBase: File,
      javaHome: Option[File],
      classpath: Seq[File],
      jvmOptions: Seq[String],
      isolation: Map[String, String],
      launch: LaunchSignals,
      projectTargets: Seq[File],
      log: Logger
  ): Unit = {
    val task = s"localPipeline${split.title}"
    if (split == Heldout) requireTerminalBatch(task, launch, log)
    val base = realBase(buildBase)
    logHead(task, base, log)
    if (split == Heldout)
      requireCleanTree(
        task,
        base,
        heldoutIgnoredAllowSet(buildBase, projectTargets, withLabels = false),
        log
      )
    committedFile(task, base, pipelineManifest(split))
    SampleLedgers.foreach(l => committedFile(task, base, l))
    val (bytes, files) = measureTree(task, base, samplesRoot(split))
    log.info(
      s"$task: samples ${samplesRoot(split)}/: $files files, $bytes bytes (sizes only; " +
        "ErSplitPipelineMain checks every file against the ledger)"
    )
    val before = attempts(base, split).lastOption.map(_.n)
    val outcome = runForked(
      task,
      base,
      javaHome,
      classpath,
      jvmOptions ++ scratchOptions(base, scratchRoot(split), "pipeline"),
      isolation,
      PipelineMain,
      Seq(split.name),
      PipelineTimeoutSeconds,
      outputRoute(split, log),
      log
    )
    val (made, report) = attemptState(base, split, before)
    outcome match {
      case Some(0) if made.exists(_.status == "complete") =>
        log.info(s"$task: complete (ErSplitPipelineMain exit 0); $report")
      case Some(0) =>
        fail(task, s"ErSplitPipelineMain exited 0, but $report; the run is not evidence")
      case Some(1) =>
        fail(task, s"ErSplitPipelineMain exit 1: a stage or a consistency check failed; $report")
      case Some(2) =>
        fail(
          task,
          s"ErSplitPipelineMain refused (exit 2) before any job ran; see its '[er-split] " +
            s"split=${split.name} status=refused reason=...' line above (a complete attempt must " +
            s"first be moved away by the human, as that line says); $report"
        )
      case Some(n) => fail(task, s"ErSplitPipelineMain exited $n (unexpected); $report")
      case None =>
        fail(
          task,
          s"exceeded the ${PipelineTimeoutSeconds}s bound; the forked JVM was stopped; the run " +
            s"is PARTIAL; $report"
        )
    }
  }

  // --- Gold-set evaluation ---------------------------------------------------------------

  /** Gold-set evaluation for one split. Writes nothing outside target/local-runs/<split>/. */
  def goldSetEval(
      split: Split,
      buildBase: File,
      javaHome: Option[File],
      classpath: Seq[File],
      isolation: Map[String, String],
      inheritedEnv: Map[String, String],
      launch: LaunchSignals,
      projectTargets: Seq[File],
      log: Logger
  ): Unit = {
    val task = s"goldSetEval${split.title}"
    if (split == Heldout) requireTerminalBatch(task, launch, log)
    val overrides = inheritedEnv.keys.filter(_.startsWith(ConfigOverridePrefix)).toVector.sorted
    if (overrides.nonEmpty)
      refuse(
        task,
        s"${overrides.mkString(", ")} set in sbt's environment (names only are checked); " +
          s"evaluation configuration comes only from ${goldDir(split)}/$ConfigFile; unset them " +
          "and start sbt again"
      )
    val base = realBase(buildBase)
    logHead(task, base, log)
    if (split == Heldout)
      requireCleanTree(
        task,
        base,
        heldoutIgnoredAllowSet(buildBase, projectTargets, withLabels = true),
        log
      )
    val dir = goldDir(split)
    val membership = committedFile(task, base, s"$dir/$MembershipFile")
    val freeze = committedFile(task, base, s"$dir/$FreezeFile")
    val config = committedFile(task, base, s"$dir/$ConfigFile")
    val queue = committedFile(task, base, s"$dir/$QueueFile")
    val labels = split match {
      case Heldout =>
        // requireCleanTree above has already required the committed, unmodified .gitignore.
        ignoredFile(task, base, s"$dir/$LabelsFile")
      case Dev => committedFile(task, base, s"$dir/$LabelsFile")
    }
    if (inheritedEnv.contains(ConfigFilesEnvVar))
      log.info(s"$task: the inherited $ConfigFilesEnvVar is not passed on")
    log.info(s"$task: config $dir/$ConfigFile only (GoldSetEvalMain prints its sha256)")
    val args = Seq(
      membership.getAbsolutePath,
      queue.getAbsolutePath,
      labels.getAbsolutePath,
      s"--manifest-sha256-file=${freeze.getAbsolutePath}",
      s"--expect-split=${split.name}"
    )
    runForked(
      task,
      base,
      javaHome,
      classpath,
      scratchOptions(base, scratchRoot(split), "eval"),
      isolation + (ConfigFilesEnvVar -> config.getAbsolutePath),
      EvalMain,
      args,
      EvalTimeoutSeconds,
      outputRoute(split, log),
      log
    ) match {
      case Some(0) if split == Heldout =>
        log.info(
          s"$task: final held-out gold metrics reported to the terminal (GoldSetEvalMain exit 0)"
        )
      case Some(0) =>
        log.info(
          s"$task: DEV diagnostics reported (GoldSetEvalMain exit 0); never final gold metrics"
        )
      case Some(2) =>
        fail(
          task,
          "GoldSetEvalMain refused (exit 2); see its 'refused:' lines above (freeze record, " +
            "expected split, configuration, labels, or usage)"
        )
      case Some(3) =>
        fail(
          task,
          "GoldSetEvalMain withheld the metrics (exit 3): fewer human-reviewed members than the " +
            "minimum; coverage is printed above"
        )
      case Some(n) => fail(task, s"GoldSetEvalMain exited $n (unexpected)")
      case None =>
        fail(
          task,
          s"exceeded the ${EvalTimeoutSeconds}s bound; the forked JVM was stopped; nothing from " +
            "this run counts (the evaluation writes no files)"
        )
    }
  }

  // --- Dev decision and grouping run (BE-P6-01) -----------------------------------------

  /*
   * `erDecisionGroupingDev`: one bounded, dev-only root task without arguments. It forks
   * `DecisionRunMain`, then `GroupingRunMain` (both entity-resolution-engineer), on the one complete
   * dev attempt of `localPipelineDev`, with committed run manifests and a committed dev config. The
   * build adds the envelope: fixed paths with held-out and gold refusals; committed, unmodified
   * inputs; a generated run-root overlay (the two absolute local roots of this checkout, which
   * enter neither run ID); create-once output roots; the repository root as working directory;
   * removal of inherited AWS_*, SCHOLARCHAIN_* and GIT_* variables; no stdin; JVM scratch under
   * target/; and a wall-clock bound per stage. It never runs localPipelineDev, never lists or reads
   * a held-out directory or gold/, and deletes nothing. Dev output goes to the sbt task log.
   *
   * BE-P6-02 (CR-P6-09 M1, M3 and suggestion): before compiling and again before forking, the
   * code-scope check ([[erDevCodeScopeCheck]]) refuses unless build.sbt, project/, modules/core/
   * and modules/entity-resolution/ are committed and clean (ignored sbt target directories and
   * .DS_Store files excepted) and the manifests' common codeVersion C is HEAD or an ancestor with
   * `C..HEAD` touching only the two manifests. Create-once: any entry under either output root
   * blocks a run, named. After each child exits, the stage state comes from the exit code AND a
   * listing of the output root ([[classifyStage]]): published-and-verified, refused-nothing-
   * published, unverified-preserved, or not established (fails); grouping starts only after a
   * published-and-verified decision run. The overlay is written once (N1). Every refusal is a
   * function that `verifyErDevRunGuards` exercises.
   */
  val ErDevTask = "erDecisionGroupingDev"
  val DecisionMain = "scholarchain.entityresolution.review.DecisionRunMain"
  val GroupingMain = "scholarchain.entityresolution.review.GroupingRunMain"

  // Build-envelope bounds, not data policy: dev scale is 374 candidates, 90 works, 286 mentions
  // (dev-run-02); goldSetEvalDev reads the same run within its 60 s bound. 45 + 45 s keeps the
  // whole task within the 90 s of localPipelineDev (agent tool limit ~180 s with sbt start-up).
  val DecisionTimeoutSeconds: Long = 45L
  val GroupingTimeoutSeconds: Long = 45L

  /** Committed inputs (owner entity-resolution-engineer: the modules/entity-resolution tree). */
  val ErLocalRunsRoot = "modules/entity-resolution/local-runs"
  val ErDevConfig: String = s"$ErLocalRunsRoot/er-dev.conf"
  val ErDevDecisionManifest: String = s"$ErLocalRunsRoot/er-decisions.dev.run-manifest.json"
  val ErDevGroupingManifest: String = s"$ErLocalRunsRoot/er-grouping.dev.run-manifest.json"
  val ErDevInputs: Seq[String] = Seq(ErDevConfig, ErDevDecisionManifest, ErDevGroupingManifest)

  /**
   * Build outputs, under the root project's target/local-runs/dev/: outside the D3(b) scope, the R6
   * fences (target/streams, target/local-runs/heldout) and the D10-protected held-out attempt root.
   */
  val ErDevDecisionRoot: String = s"${scratchRoot(Dev)}/er-decisions"
  val ErDevAssignmentRoot: String = s"${scratchRoot(Dev)}/er-assignments"
  val ErDevRootsOverlay: String = s"${scratchRoot(Dev)}/er-run-roots.conf"
  val ErDevOutputs: Seq[String] = Seq(ErDevDecisionRoot, ErDevAssignmentRoot, ErDevRootsOverlay)

  /**
   * The schema version the generated overlay declares (core ConfigLoader.SupportedSchemaVersions).
   */
  val OverlaySchemaVersion = "scholarchain.config.v2"

  /** Path prefixes a dev run never names (compared case-insensitively). */
  val ErDevForbiddenPrefixes: Seq[String] =
    Seq("gold/", "target/streams/", s"${scratchRoot(Heldout)}/", s"${attemptsRoot(Heldout)}/")

  private val HeldoutSpellings = Seq("heldout", "held-out", "held_out", "held out")
  private val CuratedRunName = "^run=[0-9a-f]{64}$".r
  private val SnapshotName = "^snapshot=[A-Za-z0-9._-]+$".r
  private val OverlayPath = "^/[A-Za-z0-9._/+=@:-]+$".r
  private val PublishedPrefix = "run="
  private val StagingPrefix = ".staging-"

  private def lower(s: String): String = s.toLowerCase(java.util.Locale.ROOT)

  /** True when `s` names the held-out split in any spelling or letter case. */
  def namesHeldout(s: String): Boolean = HeldoutSpellings.exists(lower(s).contains)

  /**
   * Why a repository-relative path may not be used by the dev run (empty when it may): it names the
   * held-out split, lies under one of [[ErDevForbiddenPrefixes]], or is not a plain relative path.
   */
  def devPathProblems(relPath: String): Seq[String] = {
    val p = ListBuffer.empty[String]
    if (namesHeldout(relPath)) p += s"'$relPath' names the held-out split"
    ErDevForbiddenPrefixes.foreach { f =>
      if (lower(relPath + "/").startsWith(lower(f))) p += s"'$relPath' is under $f"
    }
    val segs = relPath.split("/", -1).toSeq
    if (
      relPath.isEmpty || relPath.startsWith("/") || relPath.contains("\\") ||
      segs.exists(s => s.isEmpty || s == "." || s == "..")
    )
      p += s"'$relPath' is not a plain repository-relative path"
    p.toList
  }

  private def requireDevPath(task: String, relPath: String): Unit = {
    val p = devPathProblems(relPath)
    if (p.nonEmpty) refuse(task, s"dev-only task: ${p.mkString("; ")}")
  }

  /** DecisionRunMain <decision run manifest> <curated run dir> <decision output root>. */
  def decisionArgs(base: File, curatedRel: String): Seq[String] =
    Seq(ErDevDecisionManifest, curatedRel, ErDevDecisionRoot).map(r => (base / r).getAbsolutePath)

  /** GroupingRunMain <grouping manifest> <decision run dir> <curated run dir> <assignment root>. */
  def groupingArgs(base: File, curatedRel: String, decisionRunRel: String): Seq[String] =
    Seq(ErDevGroupingManifest, decisionRunRel, curatedRel, ErDevAssignmentRoot)
      .map(r => (base / r).getAbsolutePath)

  /** SCHOLARCHAIN_CONFIG_FILES: the committed dev config, then the generated run-root overlay. */
  def configFiles(base: File): String =
    Seq(ErDevConfig, ErDevRootsOverlay).map(r => (base / r).getAbsolutePath).mkString(",")

  /**
   * The generated overlay: the schema version and the two run roots, nothing else. The roots are
   * absolute local paths of this checkout (GroupingRunMain requires them), so they cannot be
   * committed portably; neither enters a run ID (decision runs record digestWithoutGrouping,
   * grouping runs GroupingRunSettings.configDigest, which leaves the roots out). Refused when a
   * path holds a character this unescaped HOCON string or the comma-separated file list cannot
   * carry.
   */
  def rootsOverlay(decisionRoot: String, assignmentRoot: String): Either[String, String] = {
    val bad = Seq(decisionRoot, assignmentRoot).filterNot(OverlayPath.pattern.matcher(_).matches)
    if (bad.nonEmpty)
      Left(
        s"run root(s) ${bad.mkString(", ")} must be absolute and use only A-Z a-z 0-9 . _ / + = @ : -" +
          " (no space, comma, quote or backslash); move the checkout to such a path"
      )
    else
      Right(
        "# Generated by sbt erDecisionGroupingDev (build-engineer, BE-P6-01) on every run; not\n" +
          "# committed. Run roots of this checkout only; neither enters a run ID.\n" +
          s"""configSchemaVersion = "$OverlaySchemaVersion"""" + "\n" +
          s"""entityResolution.decisionRunRoot = "$decisionRoot"""" + "\n" +
          s"""entityResolution.assignmentRunRoot = "$assignmentRoot"""" + "\n"
      )
  }

  private def names(dir: File): Seq[String] =
    if (!Files.isDirectory(dir.toPath, LinkOption.NOFOLLOW_LINKS)) Nil
    else {
      val listing = Files.list(dir.toPath)
      try listing.iterator.asScala.map(_.getFileName.toString).toList.sorted
      finally listing.close()
    }

  // --- Output-root listing, create-once rule and publication state (BE-P6-02, M1) ----------

  /** The marker file an entry point writes into artifacts that failed verification (ER-P6-07). */
  val UnverifiedMarker = "_UNVERIFIED"
  private val UnverifiedPrefix = ".unverified-"

  /** What one entry directly below an output root is (no link is followed). */
  sealed abstract class EntryKind(val text: String)
  case object EntryDir extends EntryKind("directory")
  case object EntryFile extends EntryKind("regular file")
  case object EntryLink extends EntryKind("symbolic link")
  case object EntryOther extends EntryKind("special file")

  /** One entry below an output root; `marked`: a real directory that holds _UNVERIFIED. */
  final case class RootEntry(name: String, kind: EntryKind, marked: Boolean)

  /** The entries directly below `dir` (none when it is missing), by name. Reads no content. */
  def listRoot(dir: File): Seq[RootEntry] =
    names(dir).map { n =>
      val p = dir.toPath.resolve(n)
      val kind =
        if (Files.isSymbolicLink(p)) EntryLink
        else if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) EntryDir
        else if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) EntryFile
        else EntryOther
      val marked =
        kind == EntryDir && Files.exists(p.resolve(UnverifiedMarker), LinkOption.NOFOLLOW_LINKS)
      RootEntry(n, kind, marked)
    }

  private def isRunEntry(e: RootEntry): Boolean = CuratedRunName.pattern.matcher(e.name).matches

  /** One entry, named and classified, for refusal and state messages. */
  def describeEntry(e: RootEntry): String = {
    val what =
      if (e.kind != EntryDir) s"a ${e.kind.text}, not a run directory"
      else if (isRunEntry(e)) {
        if (e.marked) s"a run= entry holding $UnverifiedMarker; readers refuse it"
        else s"a run= entry without $UnverifiedMarker; readers accept it"
      } else if (e.name.startsWith(UnverifiedPrefix))
        "preserved unverified artifacts " +
          (if (e.marked) s"holding $UnverifiedMarker" else s"without $UnverifiedMarker") +
          "; readers refuse the name"
      else if (e.name.startsWith(StagingPrefix))
        "a staging entry" + (if (e.marked) s" holding $UnverifiedMarker" else "") +
          "; readers ignore it"
      else "an unexpected directory"
    s"${e.name} ($what)"
  }

  /**
   * Create-once rule (BE-P6-02, M1): an output root must hold no entry at all before a run. Every
   * existing entry blocks a re-run and is named: a run= entry (with or without _UNVERIFIED),
   * preserved .unverified-* artifacts, a .staging-* entry (the exit-3 fallback can leave unverified
   * artifacts under that name, and a killed publisher leaves one), or anything else. Nothing is
   * deleted: the human inspects the entries and moves both roots away.
   */
  def createOnceProblem(rootRel: String, entries: Seq[RootEntry]): Option[String] =
    if (entries.isEmpty) None
    else
      Some(
        s"$rootRel/ already holds ${entries.size} entr${if (entries.size == 1) "y" else "ies"}: " +
          s"${entries.map(describeEntry).mkString("; ")}; the dev decision and grouping runs are " +
          "create-once, and every existing entry blocks a re-run (run=, .unverified-*, " +
          ".staging-*, anything else); nothing was deleted; to run again, the human inspects " +
          s"both $ErDevDecisionRoot/ and $ErDevAssignmentRoot/ and moves them outside the repository"
      )

  /** The publication state of one stage, from its exit code AND the listing after it exited. */
  sealed trait StageState

  /** Exit 0, and exactly one new run=<64 hex> directory without _UNVERIFIED. */
  final case class PublishedVerified(run: String) extends StageState

  /** Exit 2, and no new entry other than unmarked .staging-* directories (readers ignore them). */
  final case class RefusedNothingPublished(stagingLeftovers: Seq[String]) extends StageState

  /**
   * Exit 3, and exactly one new .unverified-*, .staging-* or run= directory: the preserved path.
   */
  final case class UnverifiedPreserved(entry: RootEntry) extends StageState

  /**
   * No contract state: no exit code (bound reached), an exit code outside the contract, a listing
   * that contradicts the exit code, or a pre-existing entry that changed or vanished.
   */
  final case class StageUnestablished(observed: String) extends StageState

  /**
   * The pure M1 classifier over the ER-P6-07 exit-code contract (0 published and verified; 2
   * refused, nothing published; 3 verification failed, artifacts preserved). Neither the code nor
   * the listing is trusted alone: a listing that contradicts the code is StageUnestablished.
   */
  def classifyStage(
      exit: Option[Int],
      before: Seq[RootEntry],
      after: Seq[RootEntry]
  ): StageState = {
    val beforeNames = before.map(_.name).toSet
    val afterByName = after.map(e => e.name -> e).toMap
    val added = after.filterNot(e => beforeNames(e.name))
    val disturbed = before.filterNot(b => afterByName.get(b.name).contains(b)).map(_.name)
    val code =
      exit.fold("no exit code (the bound was reached and the forked JVM was stopped)")(n =>
        s"exit $n"
      )
    val listing = "new entries after the child exited: " +
      (if (added.isEmpty) "none" else added.map(describeEntry).mkString("; "))
    val runs = added.filter(e => e.kind == EntryDir && isRunEntry(e))
    val runNote =
      if (runs.isEmpty) ""
      else
        s"; ${runs.map(_.name).mkString(", ")} exists and was not verified: treat it as " +
          "published but unverified" +
          (if (runs.forall(_.marked)) ""
           else
             s"; a reader may accept a run= entry without $UnverifiedMarker: move it aside by " +
               "hand before any reader runs")
    def unestablished(why: String): StageState =
      StageUnestablished(s"$code; $why; $listing$runNote")
    if (disturbed.nonEmpty)
      unestablished(s"pre-existing entries changed or vanished: ${disturbed.mkString(", ")}")
    else
      exit match {
        case Some(0) =>
          added match {
            case Seq(e) if e.kind == EntryDir && isRunEntry(e) && !e.marked =>
              PublishedVerified(e.name)
            case _ =>
              unestablished(
                "the listing contradicts exit 0 (published and verified): expected exactly one " +
                  s"new run=<64 hex> directory without $UnverifiedMarker"
              )
          }
        case Some(2) =>
          val (staging, other) = added
            .partition(e => e.kind == EntryDir && e.name.startsWith(StagingPrefix) && !e.marked)
          if (other.isEmpty) RefusedNothingPublished(staging.map(_.name))
          else unestablished("the listing contradicts exit 2 (refused, nothing published)")
        case Some(3) =>
          added match {
            case Seq(e)
                if e.kind == EntryDir && (isRunEntry(e) || e.name.startsWith(UnverifiedPrefix) ||
                  e.name.startsWith(StagingPrefix)) =>
              UnverifiedPreserved(e)
            case _ =>
              unestablished(
                "the listing contradicts exit 3 (verification failed, artifacts preserved): " +
                  "expected exactly one new .unverified-*, .staging-* or run= directory"
              )
          }
        case Some(n) => unestablished(s"exit $n is outside the entry-point contract (0, 2, 3)")
        case None => unestablished("the stage did not finish")
      }
  }

  /** The state line of one stage: what was published, where, and whether a reader accepts it. */
  def stageReport(main: String, rootRel: String, s: StageState): String = {
    val name = main.split('.').last
    s match {
      case PublishedVerified(r) =>
        s"$name: published-and-verified: $rootRel/$r (exit 0; the listing shows exactly this " +
          s"new run= directory, without $UnverifiedMarker)"
      case RefusedNothingPublished(left) =>
        s"$name: refused-nothing-published (exit 2; see its 'error:' line above; no run= or " +
          s".unverified-* entry was added under $rootRel/)" +
          (if (left.isEmpty) ""
           else
             "; staging leftover(s) remain, which readers ignore: " +
               left.map(l => s"$rootRel/$l").mkString(", ")) +
          "; nothing was deleted"
      case UnverifiedPreserved(e) =>
        val where = s"$rootRel/${e.name}"
        val state =
          if (isRunEntry(e)) {
            if (e.marked)
              s"a run= entry exists ($where) and carries $UnverifiedMarker, so readers refuse it"
            else
              s"a run= entry exists ($where) WITHOUT $UnverifiedMarker: a reader may accept " +
                "it; move it aside by hand before any reader runs"
          } else
            s"no run= entry exists; $where " +
              (if (e.marked) s"holds $UnverifiedMarker" else s"holds no $UnverifiedMarker") +
              ", and readers do not accept its name"
        s"$name: unverified-preserved (exit 3; see its 'error:' and 'unverified:' lines above): " +
          s"artifacts preserved at $where; $state; nothing was deleted"
      case StageUnestablished(o) =>
        s"$name: publication state not established under $rootRel/: $o; nothing was deleted"
    }
  }

  /** The one sub-directory of `relDir` whose name matches `pattern` (no symbolic link). */
  private def oneDir(
      task: String,
      base: File,
      relDir: String,
      pattern: scala.util.matching.Regex,
      what: String
  ): String =
    names(inside(task, base, relDir)).filter(pattern.pattern.matcher(_).matches) match {
      case Seq(n) =>
        if (!Files.isDirectory((base / relDir / n).toPath, LinkOption.NOFOLLOW_LINKS))
          refuse(task, s"$relDir/$n is not a directory (or is a symbolic link)")
        n
      case Seq() =>
        refuse(task, s"$relDir/ holds no $what directory; the dev curated run is incomplete")
      case many =>
        refuse(
          task,
          s"$relDir/ holds ${many.size} $what directories (${many.mkString(", ")}); expected " +
            "exactly one"
        )
    }

  /**
   * The curated run of the one complete dev attempt of localPipelineDev:
   * `<dev attempts root>/attempt-<n>/curated/hadoop/snapshot=<s>/run=<64 hex>/` (ErSplit and Hadoop
   * RunLayout, mirrored read-only). Lists only the dev attempts root; refuses when no or several
   * complete attempts exist, the layout is not exactly one snapshot and one run, a component is a
   * symbolic link, or the run has no regular _run-manifest.json. Reads no file content.
   */
  def devCuratedRun(task: String, base: File): File = {
    val root = attemptsRoot(Dev)
    requireDevPath(task, root)
    inside(task, base, root)
    val attempt = attempts(base, Dev).filter(_.status == "complete") match {
      case Seq(a) => a
      case Seq() =>
        refuse(
          task,
          s"no complete dev attempt (attempt-<n>/ with _SUCCESS) under $root/; run sbt " +
            "localPipelineDev first (this task does not run it)"
        )
      case many =>
        refuse(
          task,
          s"${many.size} complete dev attempts under $root/ (${many.map(_.dir.getName).mkString(", ")}); " +
            "expected exactly one; the human moves the extra ones outside the repository"
        )
    }
    val hadoopRel = s"$root/${attempt.dir.getName}/curated/hadoop"
    val hadoopDir = inside(task, base, hadoopRel)
    if (!Files.isDirectory(hadoopDir.toPath, LinkOption.NOFOLLOW_LINKS))
      refuse(task, s"$hadoopRel/ is missing or not a directory; the dev curated run is incomplete")
    val snapshot = oneDir(task, base, hadoopRel, SnapshotName, "snapshot=<name>")
    val run = oneDir(task, base, s"$hadoopRel/$snapshot", CuratedRunName, "run=<64 hex>")
    val runRel = s"$hadoopRel/$snapshot/$run"
    requireDevPath(task, runRel)
    regularBoundedFile(task, base, s"$runRel/$CuratedRunManifest")
    inside(task, base, runRel)
  }

  /** The run manifest file of a curated run (Hadoop RunLayout; checked to exist, never read). */
  val CuratedRunManifest = "_run-manifest.json"

  /** Creates an output directory after refusing a symbolic link on any existing component. */
  private def outputDir(task: String, base: File, relPath: String): File = {
    requireDevPath(task, relPath)
    noLinkPrefixes(task, base, relPath)
    IO.createDirectory(base / relPath)
    val f = inside(task, base, relPath)
    if (!Files.isDirectory(f.toPath, LinkOption.NOFOLLOW_LINKS))
      refuse(task, s"$relPath is not a directory")
    f
  }

  private def noLinkPrefixes(task: String, base: File, relPath: String): Unit = {
    val segs = relPath.split('/')
    (1 to segs.length).foreach(n => inside(task, base, segs.take(n).mkString("/")))
  }

  private def runIdOf(dirName: String): String = "run:" + dirName.stripPrefix(PublishedPrefix)

  // --- Code-scope check (BE-P6-02, review suggestion) -----------------------------------

  /** The scope that affects the dev run: the build definition and the two compiled modules. */
  val ErDevCodeScope: Seq[String] =
    Seq("build.sbt", "project", "modules/core", "modules/entity-resolution")

  /** Ignored generated outputs in that scope that never refuse (sbt target directories). */
  val ErDevGeneratedDirs: Set[String] = Set(
    "project/target/",
    "project/project/target/",
    "modules/core/target/",
    "modules/entity-resolution/target/"
  )

  /** The only two files `codeVersion..HEAD` may change in the code scope. */
  val ErDevManifests: Seq[String] = Seq(ErDevDecisionManifest, ErDevGroupingManifest)

  /**
   * Ignored paths the code-scope check accepts, as `git status --ignored=matching` prints them: the
   * four sbt target directories (exact strings) and macOS `.DS_Store` files (a file, by name; sbt
   * compiles no such file). Every other ignored path refuses (for example project/metals.sbt,
   * project/.bloop/, or a source file hidden by .git/info/exclude or core.excludesFile).
   */
  def erDevIgnoredAllowed(path: String): Boolean =
    ErDevGeneratedDirs.contains(path) ||
      (!path.endsWith("/") && path.split('/').lastOption.contains(".DS_Store"))

  private val CodeVersionField = "\"codeVersion\"\\s*:\\s*\"([^\"\\\\]*)\"".r
  private val FullCommitId = "^[0-9a-f]{40}$".r

  /** The codeVersion of a run manifest: exactly one field holding a full commit id. */
  private def codeVersionOf(task: String, base: File, relPath: String): String = {
    val text = IO.read(regularBoundedFile(task, base, relPath), StandardCharsets.UTF_8)
    CodeVersionField.findAllMatchIn(text).map(_.group(1)).toList match {
      case List(c) if FullCommitId.pattern.matcher(c).matches => c
      case List(c) =>
        refuse(
          task,
          s"$relPath: codeVersion '${c.take(80)}' is not a full 40-character lower-case hex " +
            "commit id"
        )
      case found =>
        refuse(task, s"$relPath holds ${found.size} codeVersion fields; expected exactly one")
    }
  }

  /**
   * BE-P6-02 code-scope check, git only (injectable `run` for the temporary-repository self-test).
   * Over [[ErDevCodeScope]], with the D3(b) git hygiene (GIT_* removed, stdin closed, stderr
   * discarded, bounded time, --no-optional-locks, fsmonitor and untracked cache off; any git error,
   * timeout or incomplete output refuses):
   *   1. every tracked file has no index flag; git status lists no modified, staged, deleted or
   *      untracked (non-ignored) path; every ignored path is accepted by [[erDevIgnoredAllowed]];
   *      the root .gitignore is committed and byte-identical to HEAD;
   *   2. every HEAD entry in the scope is a regular file (no symbolic link, no gitlink) whose raw
   *      working-tree bytes (`git hash-object --no-filters`) equal its HEAD blob, so a clean filter
   *      or stat setting cannot make a changed file look clean;
   *   3. both dev run manifests record the same codeVersion C, a full commit id of a commit in this
   *      repository that is HEAD or an ancestor of HEAD;
   *   4. `git diff --name-only C HEAD` over the scope lists nothing except the two manifests.
   * So a manifest-only commit Y that pins the code commit X passes at HEAD = Y. Reads only files in
   * the scope and .gitignore; never gold/ or a held-out path. Returns the summary line.
   */
  def erDevCodeScopeCheck(task: String, base: File, run: GitRunner): String = {
    val what = "the code-scope check (BE-P6-02)"
    val scopeText = ErDevCodeScope.mkString(" ")
    val scope = "--" +: ErDevCodeScope
    def ok(args: Seq[String], cmd: String): String = run(TreeGitOptions ++ args) match {
      case Some((0, out)) => out
      case Some((n, _)) => refuse(task, s"$cmd exited $n; $what cannot run")
      case None =>
        refuse(task, s"$cmd did not run or exceeded ${GitTimeoutSeconds}s; $what cannot run")
    }
    val st = readTreeState(task, run, erDevIgnoredAllowed _, ErDevCodeScope, what)
    if (st.tracked == 0)
      refuse(task, s"git ls-files lists no tracked file in $scopeText; this is not the repository")
    if (st.flagged.nonEmpty)
      refuse(
        task,
        s"git ls-files -v -- $scopeText lists ${st.flagged.size} tracked file(s) with an index " +
          "flag (S = skip-worktree, lower case = assume-unchanged, M = unmerged); git status " +
          s"cannot see changes behind them; first: ${firstOf(st.flagged)}"
      )
    if (st.changes.nonEmpty)
      refuse(
        task,
        s"git status --porcelain --untracked-files=all -- $scopeText lists ${st.changes.size} " +
          "path(s); the dev run compiles this scope, so it must be committed (no modified, " +
          s"staged, deleted, or untracked non-ignored path); first: ${firstOf(st.changes)}"
      )
    if (st.ignoredRefused.nonEmpty)
      refuse(
        task,
        s"git status --ignored=matching -- $scopeText lists ${st.ignoredRefused.size} ignored " +
          s"path(s) other than the sbt target directories (${ErDevGeneratedDirs.toSeq.sorted
              .mkString(", ")}) and .DS_Store files; sbt could read them; first: " +
          firstOf(st.ignoredRefused)
      )
    committedFileWith(task, base, ".gitignore", run)

    val entries = ok(Seq("ls-tree", "-r", "-z", "HEAD") ++ scope, "git ls-tree -r HEAD")
      .split('\u0000')
      .filter(_.nonEmpty)
      .toVector
      .map { rec =>
        val tab = rec.indexOf('\t')
        val meta = if (tab < 0) Array.empty[String] else rec.take(tab).split(' ')
        if (meta.length != 3)
          refuse(task, s"git ls-tree printed an unexpected entry: ${rec.take(200)}")
        (meta(0), meta(2), rec.drop(tab + 1))
      }
    val special = entries.filterNot { case (m, _, _) => m == "100644" || m == "100755" }
    if (special.nonEmpty)
      refuse(
        task,
        s"HEAD holds ${special.size} entr(y/ies) in $scopeText that are not regular files (mode " +
          "120000 symbolic link or 160000 gitlink), which the raw-byte check refuses; first: " +
          firstOf(special.map { case (m, _, p) => s"$m $p" })
      )
    if (entries.size != st.tracked)
      refuse(
        task,
        s"git ls-tree lists ${entries.size} file(s) at HEAD in $scopeText, but the index holds " +
          s"${st.tracked}"
      )
    val differing = entries
      .grouped(100)
      .flatMap { chunk =>
        val hashes = ok(
          Seq("hash-object", "--no-filters", "--") ++ chunk.map(_._3),
          "git hash-object --no-filters"
        ).split('\n').map(_.trim).filter(_.nonEmpty).toVector
        if (hashes.size != chunk.size)
          refuse(
            task,
            s"git hash-object --no-filters printed ${hashes.size} hash(es) for ${chunk.size} " +
              s"path(s); $what cannot run"
          )
        chunk.zip(hashes).collect { case ((_, h, p), w) if h != w => p }
      }
      .toVector
    if (differing.nonEmpty)
      refuse(
        task,
        s"${differing.size} tracked file(s) in $scopeText differ from HEAD in raw bytes (git " +
          "hash-object --no-filters), although git status may call them clean (a clean filter " +
          s"or a stat setting can hide a change); first: ${firstOf(differing)}"
      )

    val versions = ErDevManifests.map(m => m -> codeVersionOf(task, base, m))
    val c = versions.map(_._2).distinct match {
      case Seq(one) => one
      case _ =>
        refuse(
          task,
          "the dev run manifests disagree on codeVersion (" +
            versions.map { case (m, v) => s"$m: $v" }.mkString("; ") +
            "); both must record the same commit"
        )
    }
    gitWith(run, "rev-parse", "--verify", "--quiet", s"$c^{commit}") match {
      case Some(r) if r == c => ()
      case _ => refuse(task, s"codeVersion $c is not a commit in this repository")
    }
    val head = gitWith(run, "rev-parse", "--verify", "HEAD")
      .getOrElse(refuse(task, s"git rev-parse --verify HEAD failed; $what cannot run"))
    run(Seq("merge-base", "--is-ancestor", c, "HEAD")) match {
      case Some((0, _)) => ()
      case Some((1, _)) =>
        refuse(
          task,
          s"codeVersion $c is not HEAD ($head) or an ancestor of it; the manifests must pin a " +
            "commit that this checkout contains"
        )
      case other =>
        refuse(
          task,
          s"git merge-base --is-ancestor $c HEAD failed (" +
            other.fold("did not run or timed out")(r => s"exit ${r._1}") + s"); $what cannot run"
        )
    }
    val changed = ok(
      Seq(
        "diff",
        "--name-only",
        "-z",
        "--no-renames",
        "--no-ext-diff",
        "--no-textconv",
        c,
        "HEAD"
      ) ++
        scope,
      s"git diff --name-only $c HEAD"
    ).split('\u0000').filter(_.nonEmpty).toVector
    val beyond = changed.filterNot(ErDevManifests.contains)
    if (beyond.nonEmpty)
      refuse(
        task,
        s"$c..HEAD changes ${beyond.size} path(s) in $scopeText other than the two dev run " +
          s"manifests, so the code that would run is not the recorded codeVersion $c; commit the " +
          "code, then pin that commit in both manifests in a manifest-only commit; first: " +
          firstOf(beyond)
      )
    s"$task: code scope $scopeText (BE-P6-02): ${st.tracked} tracked files without index flags " +
      "and with raw bytes equal to HEAD; no modified, staged, deleted, or untracked path; " +
      s"${st.ignoredAllowed.size} ignored path(s), all sbt target directories or .DS_Store " +
      "files; .gitignore committed and unmodified; codeVersion " +
      s"$c in both manifests is ${if (c == head) "HEAD" else s"an ancestor of HEAD $head"}, and " +
      s"$c..HEAD changes ${if (changed.isEmpty) "nothing" else changed.mkString(", ")} in this scope"
  }

  // --- Dev run guards (BE-P6-02, M3: each refusal is a function the self-test calls) ------

  /** SCHOLARCHAIN_CFG_* refusal (names only): the reason, or None. */
  def configOverrideProblem(inheritedEnv: Map[String, String]): Option[String] = {
    val overrides = inheritedEnv.keys.filter(_.startsWith(ConfigOverridePrefix)).toVector.sorted
    if (overrides.isEmpty) None
    else
      Some(
        s"${overrides.mkString(", ")} set in sbt's environment (names only are checked); the dev " +
          s"configuration comes only from $ErDevConfig and the generated $ErDevRootsOverlay; " +
          "unset them and start sbt again"
      )
  }

  /**
   * The checks that need no compiled code. build.sbt runs them before it compiles the classpath,
   * and [[erDevPreflight]] runs them again after: SCHOLARCHAIN_CFG_* refusal, the fixed paths,
   * HEAD, the three committed inputs (raw bytes equal HEAD), the code-scope check. Returns the real
   * base directory.
   */
  def erDevSourceChecks(
      task: String,
      buildBase: File,
      inheritedEnv: Map[String, String],
      run: GitRunner,
      log: Logger
  ): File = {
    configOverrideProblem(inheritedEnv).foreach(m => refuse(task, m))
    (ErDevInputs ++ ErDevOutputs).foreach(requireDevPath(task, _))
    val base = realBase(buildBase)
    val head = gitWith(run, "rev-parse", "--verify", "HEAD")
      .getOrElse(
        refuse(task, "git rev-parse --verify HEAD failed; the dev run needs git and a commit")
      )
    log.info(s"$task: git_head=$head")
    ErDevInputs.foreach(r => committedFileWith(task, base, r, run))
    log.info(
      s"$task: committed and unmodified (raw bytes equal HEAD): ${ErDevInputs.mkString(", ")}"
    )
    log.info(erDevCodeScopeCheck(task, base, run))
    base
  }

  /**
   * Writes the run-root overlay once (N1): written when absent; left alone when it already holds
   * exactly these bytes; refused when it holds other bytes, is not a regular file, or any path
   * component is a symbolic link. Returns "written" or "unchanged ...".
   */
  def writeOverlay(task: String, base: File, text: String): String = {
    noLinkPrefixes(task, base, ErDevRootsOverlay)
    val f = base / ErDevRootsOverlay
    val p = f.toPath
    val want = text.getBytes(StandardCharsets.UTF_8)
    if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
      if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
        refuse(task, s"$ErDevRootsOverlay exists and is not a regular file")
      if (Files.size(p) > MaxCommittedFileBytes)
        refuse(task, s"$ErDevRootsOverlay is larger than $MaxCommittedFileBytes bytes")
      val have = Files.readAllBytes(p)
      if (java.util.Arrays.equals(have, want))
        "unchanged (identical bytes already present; not rewritten)"
      else
        refuse(
          task,
          s"$ErDevRootsOverlay exists with different bytes (sha256 ${bytesSha256Hex(have)}, " +
            s"expected ${bytesSha256Hex(want)}); it is never overwritten; the human inspects it " +
            "and moves it outside the repository"
        )
    } else {
      IO.write(f, want)
      "written"
    }
  }

  private def bytesSha256Hex(b: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(b).map(x => f"${x & 0xff}%02x").mkString

  /** The real base and the dev curated run, after every refusal before any fork. */
  final case class ErDevPreflight(base: File, curatedRel: String, overlayState: String)

  /**
   * Every refusal of erDecisionGroupingDev before it forks, in order: [[erDevSourceChecks]], the
   * one complete dev curated run, both output roots (no symbolic link on any component), the
   * create-once rule on both roots, and the overlay ([[writeOverlay]]).
   */
  def erDevPreflight(
      task: String,
      buildBase: File,
      inheritedEnv: Map[String, String],
      run: GitRunner,
      log: Logger
  ): ErDevPreflight = {
    val base = erDevSourceChecks(task, buildBase, inheritedEnv, run, log)
    val curatedRel = rel(base, devCuratedRun(task, base))
    log.info(s"$task: dev curated run $curatedRel (the one complete dev attempt)")
    val decisionRoot = outputDir(task, base, ErDevDecisionRoot)
    val assignmentRoot = outputDir(task, base, ErDevAssignmentRoot)
    Seq(ErDevDecisionRoot -> decisionRoot, ErDevAssignmentRoot -> assignmentRoot).foreach {
      case (r, d) => createOnceProblem(r, listRoot(d)).foreach(m => refuse(task, m))
    }
    val overlayText =
      rootsOverlay(decisionRoot.getAbsolutePath, assignmentRoot.getAbsolutePath) match {
        case Right(t) => t
        case Left(m) => refuse(task, m)
      }
    val state = writeOverlay(task, base, overlayText)
    log.info(
      s"$task: $ErDevRootsOverlay $state (run roots only; sha256=${sha256Hex(overlayText)}); " +
        "the entry points print the sha256 of both config files"
    )
    ErDevPreflight(base, curatedRel, state)
  }

  /** Forks one entry point: (main class, arguments, bound in seconds) => exit code, or None. */
  type ErDevFork = (String, Seq[String], Long) => Option[Int]

  /**
   * The two stages with an injectable fork (M1). Each stage lists its output root before and after
   * the child exits and classifies the state ([[classifyStage]]); the grouping stage starts only
   * after the decision stage is published-and-verified. Returns the two run directories.
   */
  def erDevStages(
      task: String,
      base: File,
      curatedRel: String,
      fork: ErDevFork,
      log: Logger
  ): (String, String) = {
    val again =
      s"to run again, the human first inspects and moves both $ErDevDecisionRoot/ and " +
        s"$ErDevAssignmentRoot/ outside the repository; nothing was deleted"
    def stage(
        main: String,
        rootRel: String,
        args: Seq[String],
        bound: Long,
        onFailure: String
    ): String = {
      val root = base / rootRel
      val before = listRoot(root)
      val exit = fork(main, args, bound)
      val state = classifyStage(exit, before, listRoot(root))
      val line = stageReport(main, rootRel, state)
      state match {
        case PublishedVerified(r) =>
          log.info(s"$task: $line")
          val relRun = s"$rootRel/$r"
          requireDevPath(task, relRun)
          relRun
        case _ => fail(task, s"$line; $onFailure")
      }
    }
    val decisionRel = stage(
      DecisionMain,
      ErDevDecisionRoot,
      decisionArgs(base, curatedRel),
      DecisionTimeoutSeconds,
      s"GroupingRunMain was not started; $again"
    )
    val groupingRel = stage(
      GroupingMain,
      ErDevAssignmentRoot,
      groupingArgs(base, curatedRel, decisionRel),
      GroupingTimeoutSeconds,
      s"the decision run $decisionRel stays (published and verified); $again"
    )
    (decisionRel, groupingRel)
  }

  /** Dev decision run, then dev grouping run (BE-P6-01). Not part of `check`. */
  def erDecisionGroupingDev(
      buildBase: File,
      javaHome: Option[File],
      classpath: Seq[File],
      isolation: Map[String, String],
      inheritedEnv: Map[String, String],
      log: Logger
  ): Unit = {
    val task = ErDevTask
    val pre = erDevPreflight(task, buildBase, inheritedEnv, gitRunner(realBase(buildBase)), log)
    val base = pre.base
    if (inheritedEnv.contains(ConfigFilesEnvVar))
      log.info(s"$task: the inherited $ConfigFilesEnvVar is not passed on")
    val env = isolation + (ConfigFilesEnvVar -> configFiles(base))
    val strip = DefaultStrippedPrefixes :+ "GIT_"
    val jvm = scratchOptions(base, scratchRoot(Dev), "er-dev")
    val fork: ErDevFork = (main, args, bound) =>
      runForked(
        task,
        base,
        javaHome,
        classpath,
        jvm,
        env,
        main,
        args,
        bound,
        ToLog(log),
        log,
        strip
      )
    val (decisionRel, groupingRel) = erDevStages(task, base, pre.curatedRel, fork, log)
    log.info(
      s"$task: complete: decision run ${runIdOf(new File(decisionRel).getName)} ($decisionRel), " +
        "read back by GroupingRunMain through DecisionExport.readPublished; grouping run " +
        s"${runIdOf(new File(groupingRel).getName)} ($groupingRel); curated input " +
        s"${pre.curatedRel}; DEV only, unevaluated"
    )
  }

  /** The pre-compile part: build.sbt runs it before entityResolution/Compile/fullClasspath. */
  def erDevBeforeCompile(
      buildBase: File,
      inheritedEnv: Map[String, String],
      log: Logger
  ): Unit = {
    log.info(s"$ErDevTask: source and code-scope checks before compiling")
    erDevSourceChecks(ErDevTask, buildBase, inheritedEnv, gitRunner(realBase(buildBase)), log)
    ()
  }

  // --- Dev decision and grouping guard self-test (BE-P6-01, BE-P6-02; part of `sbt check`) -

  /**
   * `verifyErDevRunGuards`: the fixed arguments, paths and held-out refusals of
   * erDecisionGroupingDev on constants and synthetic inputs; its curated-run selection and
   * create-once listing on a temporary directory; the code-scope check and every preflight refusal
   * (SCHOLARCHAIN_CFG_*, committed inputs, create-once, output-root and overlay symbolic links,
   * overlay bytes) on a temporary git repository; and the M1 stage states and stage gating with a
   * synthetic fork. Every fixture lives in the system temp directory, never in the real repository.
   * Forks no JVM. Last, a read-only report (not enforced) of what the source and code-scope checks
   * say about the real repository.
   */
  def erDevGuardSelfTest(buildBase: File, log: Logger): Unit = {
    val task = "verifyErDevRunGuards"
    val failures = ListBuffer.empty[String]
    var cases = 0
    def expect(name: String, ok: Boolean, got: => String): Unit = {
      cases += 1
      if (ok) log.info(s"$task: case '$name': as expected")
      else failures += s"case '$name': got $got"
    }

    expect("task name names no held-out split", !namesHeldout(ErDevTask), ErDevTask)
    expect(
      "main classes are the two ER entry points",
      Seq(DecisionMain, GroupingMain).forall(m =>
        m.startsWith("scholarchain.entityresolution.review.") && !namesHeldout(m)
      ),
      s"$DecisionMain, $GroupingMain"
    )
    (ErDevInputs ++ ErDevOutputs).foreach { p =>
      expect(s"fixed path $p is allowed", devPathProblems(p).isEmpty, devPathProblems(p).mkString)
    }
    ErDevInputs.foreach { p =>
      expect(s"input $p is in the ER module tree", p.startsWith(s"$ErLocalRunsRoot/"), p)
    }
    ErDevOutputs.foreach { p =>
      expect(
        s"output $p is under ${scratchRoot(Dev)}/ and outside the D3(b) scope",
        p.startsWith(s"${scratchRoot(Dev)}/") &&
          !CleanTreeScope.exists(s => p == s || p.startsWith(s + "/")),
        p
      )
    }
    expect(
      "the two run roots neither equal nor contain each other",
      !ErDevDecisionRoot.startsWith(ErDevAssignmentRoot + "/") &&
        !ErDevAssignmentRoot.startsWith(ErDevDecisionRoot + "/") &&
        ErDevDecisionRoot != ErDevAssignmentRoot,
      s"$ErDevDecisionRoot, $ErDevAssignmentRoot"
    )
    Seq(
      s"${attemptsRoot(Heldout)}/attempt-1/curated/hadoop",
      "modules/hadoop/target/local-pipeline/er-candidates/HELDOUT/attempt-1",
      s"${scratchRoot(Heldout)}/er-decisions",
      "target/local-runs/HeldOut/er-assignments",
      "target/local-runs/dev/held-out/er-decisions",
      "target/local-runs/dev/Held_Out",
      "gold/entity-resolution/dev-v1/er-config.conf",
      "Gold/entity-resolution/dev-v1/er-config.conf",
      "gold",
      "target/streams/_global/x",
      "TARGET/Streams/x",
      "target/local-runs/dev/../heldout",
      "target/local-runs/dev/../../gold/x",
      "target/local-runs/dev/./x",
      "target//x",
      "/tmp/x",
      "target\\local-runs\\dev",
      ""
    ).foreach { p =>
      expect(s"synthetic path '$p' is refused", devPathProblems(p).nonEmpty, "allowed")
    }

    val fake = new File("/repo")
    val hexA = "a" * 64
    val curatedRel = s"${attemptsRoot(Dev)}/attempt-1/curated/hadoop/snapshot=s/run=$hexA"
    val decisionRel = s"$ErDevDecisionRoot/run=${"b" * 64}"
    val d = decisionArgs(fake, curatedRel)
    expect(
      "decision arguments: manifest, curated run, decision root",
      d == Seq(
        s"/repo/$ErDevDecisionManifest",
        s"/repo/$curatedRel",
        s"/repo/$ErDevDecisionRoot"
      ),
      d.mkString(" ")
    )
    val g = groupingArgs(fake, curatedRel, decisionRel)
    expect(
      "grouping arguments: manifest, decision run, curated run, assignment root",
      g == Seq(
        s"/repo/$ErDevGroupingManifest",
        s"/repo/$decisionRel",
        s"/repo/$curatedRel",
        s"/repo/$ErDevAssignmentRoot"
      ),
      g.mkString(" ")
    )
    expect(
      "every argument is under the repository and names no held-out split",
      (d ++ g).forall(a =>
        a.startsWith("/repo/") && devPathProblems(a.stripPrefix("/repo/")).isEmpty
      ),
      (d ++ g).mkString(" ")
    )
    expect(
      "config files: the committed dev config, then the generated overlay",
      configFiles(fake) == s"/repo/$ErDevConfig,/repo/$ErDevRootsOverlay",
      configFiles(fake)
    )
    rootsOverlay(s"/repo/$ErDevDecisionRoot", s"/repo/$ErDevAssignmentRoot") match {
      case Right(t) =>
        val keys = t.split('\n').toSeq.filterNot(l => l.isEmpty || l.startsWith("#"))
        expect(
          "overlay sets exactly the schema version and the two run roots",
          keys == Seq(
            s"""configSchemaVersion = "$OverlaySchemaVersion"""",
            s"""entityResolution.decisionRunRoot = "/repo/$ErDevDecisionRoot"""",
            s"""entityResolution.assignmentRunRoot = "/repo/$ErDevAssignmentRoot""""
          ) && !t.contains("${"),
          keys.mkString(" | ")
        )
      case Left(e) => expect("overlay for a plain path", false, e)
    }
    Seq("/a b/x", "/a,b/x", "/a\"b/x", "/a\\b/x", "relative/x", "/a\nb").foreach { bad =>
      expect(
        s"overlay refuses root '${bad.replace("\n", "\\n")}'",
        rootsOverlay(bad, "/repo/y").isLeft && rootsOverlay("/repo/y", bad).isLeft,
        "accepted"
      )
    }

    expect(
      "configOverrideProblem: no SCHOLARCHAIN_CFG_* name",
      configOverrideProblem(Map("SCHOLARCHAIN_CONFIG_FILES" -> "x", "PATH" -> "y")).isEmpty,
      configOverrideProblem(Map("SCHOLARCHAIN_CONFIG_FILES" -> "x")).toString
    )
    expect(
      "code scope: the build definition and the two compiled modules, outside gold/ and held-out",
      ErDevCodeScope == Seq("build.sbt", "project", "modules/core", "modules/entity-resolution") &&
        ErDevCodeScope.forall(p => devPathProblems(p).isEmpty),
      ErDevCodeScope.mkString(" ")
    )
    expect(
      "code scope: only the two dev manifests may change after codeVersion",
      ErDevManifests == Seq(ErDevDecisionManifest, ErDevGroupingManifest),
      ErDevManifests.mkString(" ")
    )
    Seq(
      "project/target/" -> true,
      "project/project/target/" -> true,
      "modules/core/target/" -> true,
      "modules/entity-resolution/target/" -> true,
      "project/.DS_Store" -> true,
      "modules/core/src/.DS_Store" -> true,
      "project/.DS_Store/" -> false,
      "project/metals.sbt" -> false,
      "project/project/metals.sbt" -> false,
      "project/.bloop/" -> false,
      "modules/core/target" -> false,
      "modules/core/src/main/scala/Hidden.scala" -> false,
      "modules/hadoop/target/" -> false
    ).foreach { case (p, want) =>
      expect(
        s"code-scope ignored path '$p' is ${if (want) "accepted" else "refused"}",
        erDevIgnoredAllowed(p) == want,
        erDevIgnoredAllowed(p).toString
      )
    }

    Seq(erDevFixture(log), erDevGitFixture(log), erDevStageFixture(log)).foreach { case (n, f) =>
      cases += n
      failures ++= f
    }

    // Read-only report of the real repository (not enforced: the agent-run check has a dirty tree).
    val realRepo = realBase(buildBase)
    Try(erDevSourceChecks(task, realRepo, Map.empty, gitRunner(realRepo), log)) match {
      case scala.util.Success(_) =>
        log.info(
          s"$task: real repository source and code-scope checks, read-only (reported, not " +
            "enforced by check): ALLOW"
        )
      case scala.util.Failure(e) =>
        log.info(
          s"$task: real repository source and code-scope checks, read-only (reported, not " +
            s"enforced by check): REFUSE: ${e.getMessage}"
        )
    }
    Try(erDevCodeScopeCheck(task, realRepo, gitRunner(realRepo))) match {
      case scala.util.Success(s) =>
        log.info(s"$task: real repository code-scope check alone (reported, not enforced): $s")
      case scala.util.Failure(e) =>
        log.info(
          s"$task: real repository code-scope check alone (reported, not enforced): REFUSE: " +
            e.getMessage
        )
    }
    if (failures.nonEmpty)
      fail(task, s"FAIL: ${failures.size} case(s) wrong: ${failures.mkString(" | ")}")
    log.info(s"$task: PASS: $cases cases gave the expected verdict")
  }

  /** Curated-run selection and create-once listing on a temporary directory. */
  private def erDevFixture(log: Logger): (Int, Seq[String]) = {
    val task = "verifyErDevRunGuards"
    val tmp = Files.createTempDirectory("scholarchain-er-dev-guard-").toFile
    val failures = ListBuffer.empty[String]
    var cases = 0
    try {
      val base = realBase(tmp)
      def mk(r: String): File = { val f = base / r; IO.createDirectory(f); f }
      def touch(r: String): Unit = {
        val f = base / r
        IO.createDirectory(f.getParentFile)
        IO.write(f, "")
      }
      def selection(name: String, want: Either[String, String]): Unit = {
        cases += 1
        val got = Try(devCuratedRun("fixture", base)).map(f => rel(base, f))
        (want, got) match {
          case (Right(r), scala.util.Success(g)) if g == r =>
            log.info(s"$task: selection case '$name': ALLOW as expected ($g)")
          case (Left(sub), scala.util.Failure(e: MessageOnlyException))
              if e.getMessage.contains(sub) =>
            log.info(s"$task: selection case '$name': REFUSE as expected: ${e.getMessage}")
          case _ =>
            failures += s"selection case '$name': expected " +
              want.fold(s => s"REFUSE containing '$s'", r => s"ALLOW $r") + ", got " +
              got.fold(e => s"REFUSE: ${e.getMessage}", g => s"ALLOW $g")
        }
      }
      def listing(name: String, ok: Boolean, got: => String): Unit = {
        cases += 1
        if (ok) log.info(s"$task: listing case '$name': as expected")
        else failures += s"listing case '$name': got $got"
      }
      val dev = attemptsRoot(Dev)
      val held = attemptsRoot(Heldout)
      val hex1 = "1" * 64
      val hex2 = "2" * 64
      val snap = s"$dev/attempt-1/curated/hadoop/snapshot=dblp-x"
      val good = s"$snap/run=$hex1"

      selection("no dev attempt", Left("no complete dev attempt"))
      mk(good)
      selection("partial dev attempt only (no _SUCCESS)", Left("no complete dev attempt"))
      mk(s"$held/attempt-1/curated/hadoop/snapshot=dblp-x/run=$hex2")
      touch(s"$held/attempt-1/_SUCCESS")
      touch(s"$held/attempt-1/curated/hadoop/snapshot=dblp-x/run=$hex2/_run-manifest.json")
      selection("a complete held-out attempt is never chosen", Left("no complete dev attempt"))
      touch(s"$dev/attempt-1/_SUCCESS")
      selection("dev run without _run-manifest.json", Left("_run-manifest.json"))
      touch(s"$good/_run-manifest.json")
      selection("one complete dev attempt", Right(good))
      mk(s"$dev/attempt-2")
      selection("a second, partial dev attempt is ignored", Right(good))
      touch(s"$dev/attempt-2/_SUCCESS")
      selection("two complete dev attempts", Left("complete dev attempts"))
      IO.delete(base / s"$dev/attempt-2")
      mk(s"$snap/run=$hex2")
      selection("two curated runs", Left("expected exactly one"))
      IO.delete(base / s"$snap/run=$hex2")
      mk(s"$dev/attempt-1/curated/hadoop/snapshot=dblp-y")
      selection("two snapshots", Left("expected exactly one"))
      IO.delete(base / s"$dev/attempt-1/curated/hadoop/snapshot=dblp-y")
      IO.delete(base / good)
      val elsewhere = mk("elsewhere/run")
      IO.write(elsewhere / "_run-manifest.json", "")
      val link = (base / good).toPath
      Files.createSymbolicLink(link, elsewhere.toPath)
      selection("curated run is a symbolic link", Left("symbolic link"))
      Files.delete(link)
      touch(s"$good/_run-manifest.json")
      selection("final control after every undo", Right(good))

      val root = mk(ErDevDecisionRoot)
      listing(
        "empty output root: create-once allows",
        listRoot(root).isEmpty && createOnceProblem(ErDevDecisionRoot, listRoot(root)).isEmpty,
        listRoot(root).mkString(", ")
      )
      val staging = s"${StagingPrefix}run=$hex1-123"
      mk(s"$ErDevDecisionRoot/$staging")
      listing(
        "a staging leftover blocks a re-run and is named as one",
        createOnceProblem(ErDevDecisionRoot, listRoot(root))
          .exists(_.contains(s"$staging (a staging entry; readers ignore it)")),
        createOnceProblem(ErDevDecisionRoot, listRoot(root)).toString
      )
      mk(s"$ErDevDecisionRoot/run=$hex1")
      listing(
        "a published run is listed as a run= directory without _UNVERIFIED",
        listRoot(root).contains(RootEntry(s"run=$hex1", EntryDir, marked = false)),
        listRoot(root).mkString(", ")
      )
      listing(
        "a missing output root lists nothing",
        listRoot(base / ErDevAssignmentRoot).isEmpty,
        listRoot(base / ErDevAssignmentRoot).mkString(", ")
      )
    } finally IO.delete(tmp)
    (cases, failures.toList)
  }

  /** A synthetic run manifest with one codeVersion field (fixture only). */
  private def fixtureManifest(stage: String, codeVersion: String): String =
    s"""{"schemaVersion":"scholarchain.run-manifest.v1","stage":"$stage",""" +
      s""""codeVersion":"$codeVersion","attempt":1}""" + "\n"

  /**
   * The code-scope check and every preflight refusal on a temporary git repository in the system
   * temp directory (never the real repository): commits W (code), X (code change, manifests pin W),
   * Y (manifest-only, pins X), Z (outside the scope), then one perturbation per case, each undone.
   */
  private def erDevGitFixture(log: Logger): (Int, Seq[String]) = {
    val task = "verifyErDevRunGuards"
    val tmp = Files.createTempDirectory("scholarchain-er-dev-scope-").toFile
    val failures = ListBuffer.empty[String]
    var cases = 0
    try {
      val repo = realBase(tmp)
      // Deterministic fixture git: no user or system config, and no discovery above the fixture.
      val env = Map(
        "GIT_CONFIG_GLOBAL" -> "/dev/null",
        "GIT_CONFIG_NOSYSTEM" -> "1",
        "GIT_CEILING_DIRECTORIES" -> repo.getParent
      )
      val run: GitRunner = args => gitProcess(repo, env, GitTimeoutSeconds, args)
      def setup(args: String*): String = run(args) match {
        case Some((0, out)) => out.trim
        case other => fail(task, s"FAIL: fixture setup `git ${args.mkString(" ")}` returned $other")
      }
      val ident = Seq(
        "-c",
        "user.name=scholarchain-fixture",
        "-c",
        "user.email=fixture@example.invalid",
        "-c",
        "commit.gpgsign=false"
      )
      def commit(msg: String): String = {
        setup("add", "-A")
        setup(ident ++ Seq("commit", "-q", "-m", msg): _*)
        setup("rev-parse", "--verify", "HEAD")
      }
      def write(path: String, text: String): Unit = {
        val f = new File(repo, path)
        IO.createDirectory(f.getParentFile)
        IO.write(f, text)
      }
      def remove(path: String): Unit = IO.delete(new File(repo, path))
      def pin(decision: String, grouping: String): Unit = {
        write(ErDevDecisionManifest, fixtureManifest("er.decisions", decision))
        write(ErDevGroupingManifest, fixtureManifest("er.grouping", grouping))
      }
      def verdict[A](
          kind: String,
          name: String,
          got: Try[A],
          refusal: Option[String],
          extra: A => Option[String]
      ): Unit = {
        cases += 1
        (refusal, got) match {
          case (None, scala.util.Success(a)) if extra(a).isEmpty =>
            log.info(s"$task: $kind case '$name': ALLOW as expected")
          case (Some(sub), scala.util.Failure(e: MessageOnlyException))
              if e.getMessage.contains(sub) =>
            log.info(s"$task: $kind case '$name': REFUSE as expected: ${e.getMessage}")
          case _ =>
            failures += s"$kind case '$name': expected " +
              refusal.fold("ALLOW")(s => s"REFUSE containing '$s'") + ", got " +
              got.fold(
                e => s"REFUSE: ${e.getMessage}",
                a => extra(a).fold("ALLOW")(m => s"ALLOW with $m")
              )
        }
      }
      def check(name: String, runner: GitRunner, refusal: Option[String]): Unit =
        verdict[String](
          "code-scope",
          name,
          Try(erDevCodeScopeCheck("fixture", repo, runner)),
          refusal,
          _ => None
        )

      setup("init", "-q")
      val top =
        gitWith(run, "rev-parse", "--show-toplevel").map(p => new File(p).toPath.toRealPath())
      if (!top.contains(repo.toPath))
        fail(task, s"FAIL: the fixture git top level is $top, not $repo; nothing was changed")
      val ignoreText = "/target/\n/project/target/\n/project/project/target/\n" +
        "/modules/core/target/\n/modules/entity-resolution/target/\n/modules/hadoop/target/\n" +
        "/project/metals.sbt\n.DS_Store\n"
      write(".gitignore", ignoreText)
      write("build.sbt", "// synthetic fixture\n")
      write("project/Fixture.scala", "object Fixture\n")
      val coreSrc = "modules/core/src/main/scala/C.scala"
      val emptySrc = "modules/core/src/main/scala/Empty.scala"
      val erSrc = "modules/entity-resolution/src/main/scala/E.scala"
      write(coreSrc, "object C\n")
      write(emptySrc, "")
      write(erSrc, "object E\n")
      write(ErDevConfig, "synthetic = true\n")
      write("docs/notes.md", "synthetic\n")
      pin("1" * 40, "1" * 40)
      val w = commit("W: code; manifests pin a commit id that does not exist")
      check("codeVersion is not a commit", run, Some("is not a commit in this repository"))
      write(erSrc, "object E { val v = 2 }\n")
      pin(w, w)
      commit("X: code change; manifests pin W")
      check(
        "a change to code after codeVersion (HEAD = X, C = W)",
        run,
        Some("other than the two dev run manifests")
      )
      val x = setup("rev-parse", "--verify", "HEAD")
      pin(x, x)
      commit("Y: manifest-only commit pinning X")
      check("manifest-only commit pinning the code commit (HEAD = Y, C = X)", run, None)
      write("docs/notes.md", "synthetic, later\n")
      val z = commit("Z: outside the code scope")
      check("clean pass: a later commit outside the code scope (HEAD = Z, C = X)", run, None)

      Seq(
        "modules/core/target/classes/C.class",
        "modules/entity-resolution/target/classes/E.class",
        "project/target/streams/out",
        "project/project/target/config-classes/x.class",
        "modules/hadoop/target/x",
        "target/x"
      ).foreach(write(_, "synthetic"))
      check("ignored target/ outputs do not refuse", run, None)
      write("project/.DS_Store", "synthetic")
      write("modules/core/src/.DS_Store", "synthetic")
      check("ignored .DS_Store files do not refuse", run, None)
      write("project/metals.sbt", "// ignored build file\n")
      check(
        "an ignored build file that is not generated output refuses (project/metals.sbt)",
        run,
        Some("ignored path(s) other than the sbt target directories")
      )
      remove("project/metals.sbt")

      write(coreSrc, "object C { val edited = 1 }\n")
      check("dirty tracked file", run, Some("lists 1 path(s)"))
      setup("checkout", "--", coreSrc)
      write(coreSrc, "object C { val staged = 1 }\n")
      setup("add", "--", coreSrc)
      check("staged change", run, Some("lists 1 path(s)"))
      setup("checkout", "HEAD", "--", coreSrc)
      write("modules/entity-resolution/src/main/scala/N.scala", "object N\n")
      check("untracked, non-ignored source", run, Some("lists 1 path(s)"))
      remove("modules/entity-resolution/src/main/scala/N.scala")
      write("project/Extra.scala", "object Extra\n")
      check("untracked build definition file", run, Some("lists 1 path(s)"))
      remove("project/Extra.scala")
      setup("update-index", "--skip-worktree", coreSrc)
      write(coreSrc, "object C { val hidden = 1 }\n")
      check("skip-worktree plus edit", run, Some("with an index flag"))
      setup("update-index", "--no-skip-worktree", coreSrc)
      setup("checkout", "--", coreSrc)
      write(".git/info/attributes", s"/$emptySrc filter=fixture\n")
      setup("config", "filter.fixture.clean", "cat >/dev/null")
      write(emptySrc, "object Hidden\n")
      check(
        "a clean filter hides an edit from git status (raw bytes differ)",
        run,
        Some("differ from HEAD in raw bytes")
      )
      write(emptySrc, "")
      setup("config", "--unset", "filter.fixture.clean")
      remove(".git/info/attributes")
      write(".gitignore", ignoreText + "# uncommitted local edit\n")
      check("modified root .gitignore", run, Some(".gitignore differs from HEAD"))
      setup("checkout", "--", ".gitignore")

      val tree = setup("rev-parse", "--verify", "HEAD^{tree}")
      val dangling = setup(ident ++ Seq("commit-tree", tree, "-m", "not an ancestor"): _*)
      pin(dangling, dangling)
      commit("manifests pin a commit that is not an ancestor of HEAD")
      check("codeVersion is not an ancestor of HEAD", run, Some("or an ancestor of it"))
      setup("reset", "-q", "--hard", z)
      pin(x, w)
      commit("the two manifests disagree")
      check("the two manifests disagree on codeVersion", run, Some("disagree on codeVersion"))
      setup("reset", "-q", "--hard", z)
      pin(x.take(12), x.take(12))
      commit("abbreviated codeVersion")
      check("abbreviated codeVersion", run, Some("not a full 40-character"))
      setup("reset", "-q", "--hard", z)
      Files.createSymbolicLink((repo / "modules/core/link.scala").toPath, Paths.get("x"))
      commit("a symbolic link in the code scope")
      check("a symbolic link at HEAD in the code scope", run, Some("not regular files"))
      setup("reset", "-q", "--hard", z)
      check("git cannot run", _ => None, Some("did not run or exceeded"))
      check("git exits 128", _ => Some((128, "")), Some("exited 128"))
      check("code-scope final control after every undo", run, None)

      // Preflight on the same fixture (HEAD = Z): one complete dev attempt, roots, overlay.
      val hex = "3" * 64
      val curated = s"${attemptsRoot(Dev)}/attempt-1/curated/hadoop/snapshot=dblp-x/run=$hex"
      write(s"${attemptsRoot(Dev)}/attempt-1/_SUCCESS", "")
      write(s"$curated/$CuratedRunManifest", "")
      def pre(
          name: String,
          inherited: Map[String, String],
          refusal: Option[String],
          state: String = "written"
      ): Unit =
        verdict[ErDevPreflight](
          "preflight",
          name,
          Try(erDevPreflight("fixture", repo, inherited, run, log)),
          refusal,
          p =>
            if (p.curatedRel != curated) Some(s"curated run ${p.curatedRel}")
            else if (!p.overlayState.startsWith(state)) Some(s"overlay ${p.overlayState}")
            else None
        )
      pre("preflight control: the overlay is written", Map.empty, None)
      pre("N1: an identical overlay is not rewritten", Map.empty, None, "unchanged")
      pre(
        "SCHOLARCHAIN_CFG_* inherited",
        Map("SCHOLARCHAIN_CFG_ENTITYRESOLUTION_X" -> "synthetic"),
        Some("SCHOLARCHAIN_CFG_ENTITYRESOLUTION_X set in sbt's environment")
      )
      write(ErDevConfig, "synthetic = false\n")
      pre("committed input modified", Map.empty, Some(s"$ErDevConfig differs from HEAD"))
      setup("checkout", "--", ErDevConfig)
      def blocked(name: String, rootRel: String, entry: String, marked: Boolean, sub: String) = {
        IO.createDirectory(repo / rootRel / entry)
        if (marked) IO.write(repo / rootRel / entry / UnverifiedMarker, "")
        pre(name, Map.empty, Some(sub))
        IO.delete(repo / rootRel / entry)
      }
      blocked(
        "create-once: a published run= entry",
        ErDevDecisionRoot,
        s"run=$hex",
        false,
        s"run=$hex (a run= entry without _UNVERIFIED"
      )
      blocked(
        "create-once: a run= entry holding _UNVERIFIED",
        ErDevDecisionRoot,
        s"run=$hex",
        true,
        s"run=$hex (a run= entry holding _UNVERIFIED"
      )
      blocked(
        "create-once: preserved .unverified-* artifacts",
        ErDevAssignmentRoot,
        s".unverified-run=$hex-r1",
        true,
        s".unverified-run=$hex-r1 (preserved unverified artifacts holding _UNVERIFIED"
      )
      blocked(
        "create-once: a .staging-* leftover",
        ErDevAssignmentRoot,
        s".staging-run=$hex-r2",
        false,
        s".staging-run=$hex-r2 (a staging entry"
      )
      val elsewhere = repo / "elsewhere"
      IO.createDirectory(elsewhere)
      IO.delete(repo / ErDevDecisionRoot)
      Files.createSymbolicLink((repo / ErDevDecisionRoot).toPath, elsewhere.toPath)
      pre(
        "output root is a symbolic link",
        Map.empty,
        Some(s"$ErDevDecisionRoot resolves through a symbolic link")
      )
      Files.delete((repo / ErDevDecisionRoot).toPath)
      val devScratch = (repo / scratchRoot(Dev)).toPath
      val moved = (repo / "elsewhere-dev").toPath
      Files.move(devScratch, moved)
      Files.createSymbolicLink(devScratch, moved)
      pre(
        "output root below a symbolically linked parent",
        Map.empty,
        Some(s"${scratchRoot(Dev)} resolves through a symbolic link")
      )
      Files.delete(devScratch)
      Files.move(moved, devScratch)
      val overlay = repo / ErDevRootsOverlay
      val overlayText = IO.read(overlay)
      IO.delete(overlay)
      IO.write(elsewhere / "roots.conf", overlayText)
      Files.createSymbolicLink(overlay.toPath, (elsewhere / "roots.conf").toPath)
      pre(
        "overlay is a symbolic link (to identical bytes)",
        Map.empty,
        Some(s"$ErDevRootsOverlay resolves through a symbolic link")
      )
      Files.delete(overlay.toPath)
      IO.write(overlay, overlayText + "# edited\n")
      pre("N1: an overlay with different bytes refuses", Map.empty, Some("with different bytes"))
      IO.delete(overlay)
      pre("preflight final control after every undo", Map.empty, None)
    } finally IO.delete(tmp)
    (cases, failures.toList)
  }

  /**
   * M1 with a synthetic fork on temporary directories: each scenario lets the fake decision and
   * grouping children leave entries and return an exit code, then checks erDevStages' verdict, its
   * message, whether the grouping stage started, and (on success) the grouping arguments.
   */
  private def erDevStageFixture(log: Logger): (Int, Seq[String]) = {
    val task = "verifyErDevRunGuards"
    val failures = ListBuffer.empty[String]
    var cases = 0
    val hexD = "d" * 64
    val hexG = "e" * 64
    type Act = File => Option[Int]
    def entry(root: File, name: String, marked: Boolean): Unit = {
      IO.createDirectory(root / name)
      if (marked) IO.write(root / name / UnverifiedMarker, "")
    }
    def leaves(code: Option[Int], es: (String, Boolean)*): Act = root => {
      es.foreach { case (n, m) => entry(root, n, m) }
      code
    }
    val publishD = leaves(Some(0), s"run=$hexD" -> false)
    val publishG = leaves(Some(0), s"run=$hexG" -> false)
    def scenario(
        name: String,
        decision: Act,
        grouping: Act,
        refusal: Seq[String],
        groupingStarts: Boolean
    ): Unit = {
      cases += 1
      val tmp = Files.createTempDirectory("scholarchain-er-dev-stage-").toFile
      try {
        val base = realBase(tmp)
        var groupingArgsSeen: Option[Seq[String]] = None
        val fork: ErDevFork = (main, args, _) =>
          if (main == DecisionMain) decision(base / ErDevDecisionRoot)
          else { groupingArgsSeen = Some(args); grouping(base / ErDevAssignmentRoot) }
        val got = Try(erDevStages("fixture", base, "curated/run", fork, log))
        val wantDecision = (base / s"$ErDevDecisionRoot/run=$hexD").getAbsolutePath
        val ok = (refusal, got) match {
          case (Seq(), scala.util.Success(r)) =>
            r == (s"$ErDevDecisionRoot/run=$hexD", s"$ErDevAssignmentRoot/run=$hexG") &&
            groupingArgsSeen.exists(_.lift(1).contains(wantDecision))
          case (subs, scala.util.Failure(e: MessageOnlyException)) if subs.nonEmpty =>
            subs.forall(e.getMessage.contains)
          case _ => false
        }
        if (ok && groupingArgsSeen.nonEmpty == groupingStarts)
          log.info(
            s"$task: stage case '$name': as expected: " +
              got.fold(_.getMessage, r => s"ALLOW ${r._1}, ${r._2}")
          )
        else
          failures += s"stage case '$name': expected " +
            (if (refusal.isEmpty) "ALLOW" else s"REFUSE containing ${refusal.mkString(" & ")}") +
            s" with grouping started=$groupingStarts; got " +
            got.fold(e => s"REFUSE: ${e.getMessage}", r => s"ALLOW $r") +
            s" with grouping started=${groupingArgsSeen.nonEmpty}"
      } finally IO.delete(tmp)
    }
    val notStarted = "GroupingRunMain was not started"
    val unreached: Act = _ => Some(0)
    scenario("both stages published and verified", publishD, publishG, Nil, groupingStarts = true)
    scenario(
      "decision exit 2, nothing published",
      leaves(Some(2)),
      unreached,
      Seq("DecisionRunMain: refused-nothing-published", notStarted),
      groupingStarts = false
    )
    scenario(
      "decision exit 2 with a staging leftover",
      leaves(Some(2), s".staging-run=$hexD-x" -> false),
      unreached,
      Seq("refused-nothing-published", "staging leftover(s) remain", s".staging-run=$hexD-x"),
      groupingStarts = false
    )
    scenario(
      "decision exit 2, but a run= entry appeared",
      leaves(Some(2), s"run=$hexD" -> false),
      unreached,
      Seq("contradicts exit 2", "published but unverified", "a reader may accept", notStarted),
      groupingStarts = false
    )
    scenario(
      "decision exit 3, .unverified-* holding _UNVERIFIED",
      leaves(Some(3), s".unverified-run=$hexD-x" -> true),
      unreached,
      Seq(
        "DecisionRunMain: unverified-preserved",
        s"$ErDevDecisionRoot/.unverified-run=$hexD-x",
        "no run= entry exists",
        notStarted
      ),
      groupingStarts = false
    )
    scenario(
      "decision exit 3, .staging-* fallback without _UNVERIFIED",
      leaves(Some(3), s".staging-run=$hexD-x" -> false),
      unreached,
      Seq("unverified-preserved", "no run= entry exists", s"holds no $UnverifiedMarker"),
      groupingStarts = false
    )
    scenario(
      "decision exit 3, run= holding _UNVERIFIED",
      leaves(Some(3), s"run=$hexD" -> true),
      unreached,
      Seq("unverified-preserved", s"carries $UnverifiedMarker, so readers refuse it"),
      groupingStarts = false
    )
    scenario(
      "decision exit 3, run= without _UNVERIFIED",
      leaves(Some(3), s"run=$hexD" -> false),
      unreached,
      Seq("unverified-preserved", "a reader may accept it; move it aside by hand"),
      groupingStarts = false
    )
    scenario(
      "decision exit 3, nothing listed",
      leaves(Some(3)),
      unreached,
      Seq("contradicts exit 3", notStarted),
      groupingStarts = false
    )
    scenario(
      "decision exit 0, nothing listed",
      leaves(Some(0)),
      unreached,
      Seq("contradicts exit 0", notStarted),
      groupingStarts = false
    )
    scenario(
      "decision exit 0, run= holding _UNVERIFIED",
      leaves(Some(0), s"run=$hexD" -> true),
      unreached,
      Seq("contradicts exit 0", "published but unverified"),
      groupingStarts = false
    )
    scenario(
      "decision exit 0, two run= entries",
      leaves(Some(0), s"run=$hexD" -> false, s"run=$hexG" -> false),
      unreached,
      Seq("contradicts exit 0"),
      groupingStarts = false
    )
    scenario(
      "decision exit 0, run= is a symbolic link",
      root => {
        // The link points to an empty sibling directory (no cycle for the cleanup).
        val target = root.toPath.resolveSibling("link-target")
        Files.createDirectories(target)
        IO.createDirectory(root)
        Files.createSymbolicLink((root / s"run=$hexD").toPath, target)
        Some(0)
      },
      unreached,
      Seq("contradicts exit 0", "symbolic link"),
      groupingStarts = false
    )
    scenario(
      "decision bound reached, a run= entry appeared",
      leaves(None, s"run=$hexD" -> false),
      unreached,
      Seq("no exit code", "published but unverified", "a reader may accept", notStarted),
      groupingStarts = false
    )
    scenario(
      "decision exit 1 (outside the contract)",
      leaves(Some(1)),
      unreached,
      Seq("exit 1 is outside the entry-point contract", notStarted),
      groupingStarts = false
    )
    scenario(
      "grouping exit 2 after a verified decision run",
      publishD,
      leaves(Some(2)),
      Seq(
        "GroupingRunMain: refused-nothing-published",
        s"the decision run $ErDevDecisionRoot/run=$hexD stays"
      ),
      groupingStarts = true
    )
    scenario(
      "grouping exit 3, .unverified-* holding _UNVERIFIED",
      publishD,
      leaves(Some(3), s".unverified-run=$hexG-y" -> true),
      Seq("GroupingRunMain: unverified-preserved", s"$ErDevAssignmentRoot/.unverified-run="),
      groupingStarts = true
    )
    scenario(
      "grouping exit 0, nothing listed",
      publishD,
      leaves(Some(0)),
      Seq("GroupingRunMain: publication state not established", "contradicts exit 0"),
      groupingStarts = true
    )

    cases += 1
    val staged = RootEntry(s".staging-run=$hexD-x", EntryDir, marked = false)
    classifyStage(
      Some(0),
      Seq(staged),
      Seq(RootEntry(s"run=$hexD", EntryDir, marked = false))
    ) match {
      case StageUnestablished(o) if o.contains("changed or vanished") =>
        log.info(s"$task: stage case 'a pre-existing entry vanished': as expected")
      case other => failures += s"stage case 'a pre-existing entry vanished': got $other"
    }
    (cases, failures.toList)
  }

  // --- Held-out guard self-test (BE-P5-05; part of `sbt check`) --------------------------

  /** Synthetic launch cases: (name, signals, expected ALLOW). */
  private def launchCases: Seq[(String, LaunchSignals, Boolean)] = {
    val b17 = LaunchSignals(
      javaFeature = 17,
      javaVersion = "17 (synthetic)",
      consolePresent = true,
      isTerminalMethod = false,
      consoleIsTerminal = None,
      jdkConsoleProperty = None,
      stdout = FdCharDevice,
      stderr = FdCharDevice,
      sbtArguments = Seq("localPipelineHeldout"),
      commandChannel = None,
      remainingCommands = Nil
    )
    val b25 = b17.copy(
      javaFeature = 25,
      javaVersion = "25 (synthetic)",
      isTerminalMethod = true,
      consoleIsTerminal = Some(true)
    )
    val server = Seq("--detach-stdio", "--server")
    Seq(
      ("batch, JDK 17, console present, fds 1 and 2 terminals", b17, true),
      ("batch, JDK 25, Console.isTerminal() true, fds 1 and 2 terminals", b25, true),
      (
        "thin client (sbt --client, sbtn): channel network-1",
        b17.copy(commandChannel = Some("network-1")),
        false
      ),
      (
        "thin-client-started server that inherited a terminal: network-1, --detach-stdio --server",
        b25.copy(
          commandChannel = Some("network-1"),
          sbtArguments = server,
          remainingCommands = Seq("shell")
        ),
        false
      ),
      ("sbt server launch: --server", b17.copy(sbtArguments = Seq("--server")), false),
      ("detached-stdio server launch", b17.copy(sbtArguments = server), false),
      ("BSP launch: -bsp", b17.copy(sbtArguments = Seq("-bsp")), false),
      ("BSP/IDE request on network-2", b25.copy(commandChannel = Some("network-2")), false),
      (
        "interactive sbt shell: console0 with shell queued",
        b17.copy(commandChannel = Some("console0"), remainingCommands = Seq("shell")),
        false
      ),
      ("shell queued (server or shell mode)", b17.copy(remainingCommands = Seq("shell")), false),
      (
        "console channel console0 without shell queued",
        b17.copy(commandChannel = Some("console0")),
        false
      ),
      (
        "JDK 25, stdout redirected: console present, isTerminal() false",
        b25.copy(consoleIsTerminal = Some(false)),
        false
      ),
      ("JDK 25, isTerminal() unavailable", b25.copy(consoleIsTerminal = None), false),
      (
        "JDK 25 without isTerminal()",
        b25.copy(isTerminalMethod = false, consoleIsTerminal = None),
        false
      ),
      ("JDK 17, System.console() null", b17.copy(consolePresent = false), false),
      (
        "JDK 17, jdk.console=jdk.internal.le",
        b17.copy(jdkConsoleProperty = Some("jdk.internal.le")),
        false
      ),
      ("stdout to a pipe (| tee)", b17.copy(stdout = FdPipe), false),
      ("stdout to a file (> file)", b17.copy(stdout = FdRegularFile), false),
      ("stdout to /dev/null", b17.copy(stdout = FdDevNull), false),
      ("stdout to a socket", b17.copy(stdout = FdSocket), false),
      ("stdout unknown", b17.copy(stdout = FdUnknown("synthetic")), false),
      ("stderr to a file (2> file)", b17.copy(stderr = FdRegularFile), false),
      ("stderr to a pipe (2>&1 |)", b25.copy(stderr = FdPipe), false)
    )
  }

  private def describe(s: LaunchSignals): String =
    s"java=${s.javaVersion} feature=${s.javaFeature}; System.console()=" +
      s"${if (s.consolePresent) "present" else "null"}; Console.isTerminal() method=" +
      s"${if (s.isTerminalMethod) "present" else "absent"} answer=" +
      s"${s.consoleIsTerminal.fold("-")(_.toString)}; jdk.console=${s.jdkConsoleProperty.getOrElse("-")}; " +
      s"fd1=${s.stdout}; fd2=${s.stderr}; sbt arguments=[${s.sbtArguments.mkString(" ")}]; command " +
      s"channel=${s.commandChannel.getOrElse("none")}; remaining commands=[" +
      s"${s.remainingCommands.take(20).mkString(" | ")}]"

  /**
   * The D3(b) check against a temporary git repository in the system temp directory (never the real
   * repository): every BE-1 route, the controls, and git failure and timeout. Returns failures.
   */
  private def gitFixture(task: String, log: Logger): (Int, Seq[String]) = {
    val tmp = Files.createTempDirectory("scholarchain-heldout-guard-").toFile
    val failures = ListBuffer.empty[String]
    var cases = 0
    try {
      val repo = realBase(tmp)
      // Deterministic fixture git: no user or system config, and no discovery above the fixture.
      val env = Map(
        "GIT_CONFIG_GLOBAL" -> "/dev/null",
        "GIT_CONFIG_NOSYSTEM" -> "1",
        "GIT_CEILING_DIRECTORIES" -> repo.getParent
      )
      val run: GitRunner = args => gitProcess(repo, env, GitTimeoutSeconds, args)
      def setup(args: String*): Unit = run(args) match {
        case Some((0, _)) => ()
        case other => fail(task, s"FAIL: fixture setup `git ${args.mkString(" ")}` returned $other")
      }
      def write(path: String, text: String): Unit = {
        val f = new File(repo, path)
        IO.createDirectory(f.getParentFile)
        IO.write(f, text)
      }
      def remove(path: String): Unit = IO.delete(new File(repo, path))
      val oldQuery =
        Seq("--no-optional-locks", "status", "--porcelain", "--untracked-files=all", "--") ++
          CleanTreeScope
      def oldQueryEmpty: Boolean = run(oldQuery).exists { case (c, o) => c == 0 && o.trim.isEmpty }
      val targets = Seq(new File(repo, "modules/m/target"), new File(repo, "target"))
      val pipelineAllow = heldoutIgnoredAllowSet(repo, targets, withLabels = false)
      val evalAllow = heldoutIgnoredAllowSet(repo, targets, withLabels = true)
      def expect(
          name: String,
          allow: Set[String],
          runner: GitRunner,
          refusal: Option[String],
          bypassedOldCheck: Boolean = false
      ): Unit = {
        cases += 1
        val oldEmpty = bypassedOldCheck && oldQueryEmpty
        val got = Try(heldoutTreeCheck("fixture", repo, runner, allow))
        (refusal, got) match {
          case (None, scala.util.Success(_)) =>
            log.info(s"$task: clean-tree case '$name': ALLOW as expected")
          case (Some(sub), scala.util.Failure(e: MessageOnlyException))
              if e.getMessage.contains(sub) =>
            log.info(s"$task: clean-tree case '$name': REFUSE as expected: ${e.getMessage}")
          case _ =>
            failures += s"clean-tree case '$name': expected " +
              refusal.fold("ALLOW")(s => s"REFUSE containing '$s'") + ", got " +
              got.fold(e => s"REFUSE: ${e.getMessage}", _ => "ALLOW")
        }
        if (bypassedOldCheck) {
          if (oldEmpty)
            log.info(
              s"$task: clean-tree case '$name': the BE-P5-04 query (git status --porcelain " +
                "--untracked-files=all) lists nothing here, so this route bypassed the old check"
            )
          else failures += s"clean-tree case '$name': the BE-P5-04 query was expected to be empty"
        }
      }

      setup("init", "-q")
      val top =
        gitWith(run, "rev-parse", "--show-toplevel").map(p => new File(p).toPath.toRealPath())
      if (!top.contains(repo.toPath))
        fail(task, s"FAIL: the fixture git top level is $top, not $repo; nothing was changed")
      val ignoreText = "/project/target/\n/project/project/target/\n/modules/m/target/\n" +
        s"/$DatasetDownloads\n/${goldDir(Heldout)}/$LabelsFile\n"
      write(".gitignore", ignoreText)
      write("build.sbt", "// synthetic fixture\n")
      write("project/Fixture.scala", "object Fixture\n")
      write("modules/m/src/main/scala/M.scala", "object M\n")
      write("modules/m/src/test/resources/keep.txt", "synthetic\n")
      write("datasets/README.txt", "synthetic\n")
      write(s"${goldDir(Heldout)}/$ConfigFile", "synthetic = true\n")
      setup("add", "-A")
      setup(
        "-c",
        "user.name=scholarchain-fixture",
        "-c",
        "user.email=fixture@example.invalid",
        "-c",
        "commit.gpgsign=false",
        "commit",
        "-q",
        "-m",
        "synthetic fixture"
      )
      // Build output and downloads that a legitimate run always has.
      write("modules/m/target/classes/M.class", "synthetic")
      write("project/target/streams/out", "synthetic")
      write("project/project/target/config-classes/x.class", "synthetic")
      write(s"${DatasetDownloads}full.xml.gz", "synthetic")
      expect(
        "control: committed tree with allowed ignored dirs (pipeline)",
        pipelineAllow,
        run,
        None
      )
      expect("control: committed tree with allowed ignored dirs (evaluation)", evalAllow, run, None)

      val labels = s"${goldDir(Heldout)}/$LabelsFile"
      write(labels, "synthetic\n")
      expect("L1 labels present and ignored (evaluation)", evalAllow, run, None)
      expect(
        "L1 labels present and ignored (pipeline)",
        pipelineAllow,
        run,
        Some("not on the held-out allow set")
      )
      remove(labels)

      val src = "modules/m/src/main/scala/M.scala"
      write("modules/m/src/main/scala/N.scala", "object N\n")
      expect("untracked source file", pipelineAllow, run, Some("lists 1 path(s)"))
      remove("modules/m/src/main/scala/N.scala")
      write(src, "object M { val edited = 1 }\n")
      expect("modified tracked file", pipelineAllow, run, Some("lists 1 path(s)"))
      setup("checkout", "--", src)

      setup("update-index", "--skip-worktree", src)
      write(src, "object M { val hidden = 1 }\n")
      expect(
        "BE-1(a) skip-worktree plus edit",
        pipelineAllow,
        run,
        Some("with an index flag"),
        true
      )
      setup("update-index", "--no-skip-worktree", src)
      setup("checkout", "--", src)

      setup("update-index", "--assume-unchanged", src)
      write(src, "object M { val hidden = 2 }\n")
      expect(
        "BE-1(a) assume-unchanged plus edit",
        pipelineAllow,
        run,
        Some("with an index flag"),
        true
      )
      setup("update-index", "--no-assume-unchanged", src)
      setup("checkout", "--", src)

      val coreSite = "modules/m/src/test/resources/core-site.xml"
      write(".git/info/exclude", s"/$coreSite\n")
      write(coreSite, "<configuration/>\n")
      expect(
        "BE-1(b) untracked core-site.xml hidden by .git/info/exclude",
        evalAllow,
        run,
        Some("not on the held-out allow set"),
        true
      )
      remove(coreSite)
      write(".git/info/exclude", "")

      val excludes = new File(repo, ".git/fixture-excludes")
      IO.write(excludes, "core-site.xml\n")
      setup("config", "core.excludesFile", excludes.getAbsolutePath)
      write(coreSite, "<configuration/>\n")
      expect(
        "BE-1(b) untracked core-site.xml hidden by core.excludesFile",
        evalAllow,
        run,
        Some("not on the held-out allow set"),
        true
      )
      remove(coreSite)
      setup("config", "--unset", "core.excludesFile")

      write("modules/m/src/test/resources/.gitignore", "*\n")
      write(coreSite, "<configuration/>\n")
      expect(
        "BE-1(b) untracked core-site.xml hidden by a nested .gitignore '*'",
        evalAllow,
        run,
        Some("not on the held-out allow set"),
        true
      )
      remove(coreSite)
      remove("modules/m/src/test/resources/.gitignore")

      write(".gitignore", ignoreText + "# uncommitted local edit\n")
      expect(
        "BE-1(c) modified root .gitignore (pipeline)",
        pipelineAllow,
        run,
        Some(".gitignore differs from HEAD")
      )
      expect(
        "BE-1(c) modified root .gitignore (evaluation)",
        evalAllow,
        run,
        Some(".gitignore differs from HEAD")
      )
      setup("checkout", "--", ".gitignore")

      expect("git cannot run", pipelineAllow, _ => None, Some("did not run or exceeded"))
      expect("git exits 128", pipelineAllow, _ => Some((128, "")), Some("exited 128"))
      expect(
        "git exceeds its bound (0 s)",
        pipelineAllow,
        args => gitProcess(repo, env, 0L, args),
        Some("did not run or exceeded")
      )
      expect("final control after every undo", pipelineAllow, run, None)
    } finally IO.delete(tmp)
    (cases, failures.toList)
  }

  /**
   * `verifyHeldoutGuards` (part of `sbt check`): the BE-2 launch decision on synthetic signals, the
   * BE-1 clean-tree check on a temporary git repository, and two read-only reports about this run
   * (its real launch signals and verdict; what git lists in the real D3(b) scope). Fails only when
   * a synthetic case gives the wrong verdict; the reports are not enforced, because the agent-run
   * `sbt check` has no terminal and a dirty tree.
   */
  def guardSelfTest(
      buildBase: File,
      launch: LaunchSignals,
      projectTargets: Seq[File],
      log: Logger
  ): Unit = {
    val task = "verifyHeldoutGuards"
    val failures = ListBuffer.empty[String]
    val launchSet = launchCases
    launchSet.foreach { case (name, s, allow) =>
      val p = launchProblems(s)
      if (p.isEmpty == allow)
        log.info(
          s"$task: launch case '$name': ${if (allow) "ALLOW" else "REFUSE"} as expected" +
            (if (p.nonEmpty) s" (${p.mkString("; ")})" else "")
        )
      else
        failures += s"launch case '$name': expected ${if (allow) "ALLOW" else "REFUSE"}, got " +
          (if (p.isEmpty) "ALLOW" else s"REFUSE (${p.mkString("; ")})")
    }
    val (treeCases, treeFailures) = gitFixture(task, log)
    failures ++= treeFailures

    log.info(s"$task: observed launch signals of this sbt JVM: ${describe(launch)}")
    val real = launchProblems(launch)
    log.info(
      s"$task: held-out launch verdict for this sbt JVM (reported, not enforced by check): " +
        (if (real.isEmpty) "ALLOW" else s"REFUSE: ${real.mkString("; ")}")
    )
    val base = realBase(buildBase)
    val allow = heldoutIgnoredAllowSet(buildBase, projectTargets, withLabels = true)
    Try(readTreeState(task, gitRunner(base), allow)) match {
      case scala.util.Success(st) =>
        log.info(
          s"$task: real repository D3(b) scope, read-only (reported, not enforced by check): " +
            s"${st.tracked} tracked files, ${st.flagged.size} with an index flag" +
            (if (st.flagged.nonEmpty) s" (${firstOf(st.flagged)})" else "") +
            s"; ${st.changes.size} modified, staged, deleted, or untracked path(s); ignored paths on " +
            s"the allow set: [${st.ignoredAllowed.sorted.mkString(", ")}]; ignored paths a held-out " +
            s"run would refuse: ${st.ignoredRefused.size} [${st.ignoredRefused.sorted.take(30).mkString(", ")}]"
        )
      case scala.util.Failure(e) =>
        log.warn(s"$task: real repository report unavailable: ${e.getMessage}")
    }
    if (failures.nonEmpty)
      fail(task, s"FAIL: ${failures.size} case(s) wrong: ${failures.mkString(" | ")}")
    log.info(
      s"$task: PASS: ${launchSet.size} synthetic launch cases and $treeCases temporary-repository " +
        "clean-tree cases gave the expected verdict"
    )
  }

  // --- Synthetic held-out output-route check (decisions-17/19; part of `sbt check`) ------

  /** Two independent random markers. Neither is ever passed to an sbt logger by the build. */
  final case class RouteProbe(terminalMarker: String, controlMarker: String)

  private def newMarker(): String =
    "scholarchain-synthetic-route-" + UUID.randomUUID().toString.replace("-", "")

  private def codeSource(c: Class[_]): Option[File] =
    Option(c.getProtectionDomain)
      .flatMap(pd => Option(pd.getCodeSource))
      .flatMap(cs => Option(cs.getLocation))
      .flatMap(u => Try(new File(u.toURI)).toOption)
      .filter(_.exists)

  /**
   * Forks the synthetic emitter twice through `runForked`: once on `outputRoute(Heldout, log)`, the
   * exact route both held-out tasks use, and once on `outputRoute(Dev, log)` (the sbt task log) as
   * the counter-test. The markers reach the child only through its environment; the build logs
   * their sha256, never the marker text. No gold file, label, or held-out data is involved.
   */
  def routeProbe(buildBase: File, javaHome: Option[File], log: Logger): RouteProbe = {
    val task = "heldoutRouteProbe"
    val base = realBase(buildBase)
    val classpath = Seq(
      codeSource(LocalRunsRouteEmitter.getClass)
        .getOrElse(
          fail(task, "FAIL: cannot locate the meta-build classes of LocalRunsRouteEmitter")
        ),
      codeSource(classOf[scala.Option[_]])
        .getOrElse(fail(task, "FAIL: cannot locate the Scala library of the meta-build"))
    ).distinct
    val terminalMarker = newMarker()
    val controlMarker = newMarker()
    val scratchParent = base / RouteCheckScratch
    IO.createDirectory(scratchParent)
    val scratch = Files.createTempDirectory(scratchParent.toPath, "probe-").toFile
    try {
      val jvm = Seq(s"-Djava.io.tmpdir=${scratch.getAbsolutePath}", "-Xshare:auto")
      def emit(route: OutputRoute, marker: String, label: String): Unit =
        runForked(
          task,
          base,
          javaHome,
          classpath,
          jvm,
          Map(RouteMarkerEnvVar -> marker),
          RouteEmitterMain,
          Nil,
          RouteProbeTimeoutSeconds,
          route,
          log
        ) match {
          case Some(0) => ()
          case Some(n) =>
            fail(task, s"FAIL: the $label child exited $n (it must print the marker and exit 0)")
          case None =>
            fail(task, s"FAIL: the $label child exceeded the ${RouteProbeTimeoutSeconds}s bound")
        }
      val heldoutRoute = outputRoute(Heldout, log)
      log.info(
        s"$task: held-out route = outputRoute(Heldout) = $heldoutRoute; synthetic marker " +
          s"sha256=${sha256Hex(terminalMarker)}; only the child prints the marker"
      )
      emit(heldoutRoute, terminalMarker, "held-out route")
      log.info(
        s"$task: counter-test route = outputRoute(Dev) (the sbt task log); control marker " +
          s"sha256=${sha256Hex(controlMarker)}"
      )
      emit(outputRoute(Dev, log), controlMarker, "counter-test")
      RouteProbe(terminalMarker, controlMarker)
    } finally {
      IO.delete(scratch)
      Try(Files.deleteIfExists(scratchParent.toPath))
      ()
    }
  }

  /** Byte-pattern presence per needle in one file, read in bounded chunks. */
  private def fileContains(p: Path, needles: Seq[Array[Byte]]): Seq[Boolean] = {
    val found = Array.fill(needles.size)(false)
    val keep = needles.map(_.length).max - 1
    val buf = new Array[Byte](1 << 20)
    var carry = Array.emptyByteArray
    val in = Files.newInputStream(p)
    try {
      var r = in.read(buf)
      while (r != -1) {
        val window = new Array[Byte](carry.length + r)
        System.arraycopy(carry, 0, window, 0, carry.length)
        System.arraycopy(buf, 0, window, carry.length, r)
        needles.indices.foreach { i =>
          if (!found(i) && indexOf(window, needles(i))) found(i) = true
        }
        carry = window.slice(math.max(0, window.length - keep), window.length)
        r = in.read(buf)
      }
    } finally in.close()
    found.toSeq
  }

  private def indexOf(hay: Array[Byte], needle: Array[Byte]): Boolean = {
    val first = needle(0)
    val last = hay.length - needle.length
    var i = 0
    var hit = false
    while (!hit && i <= last) {
      if (hay(i) == first) {
        var j = 1
        while (j < needle.length && hay(i + j) == needle(j)) j += 1
        if (j == needle.length) hit = true
      }
      i += 1
    }
    hit
  }

  /**
   * Scans every regular file under every sbt target directory (all projects, the meta-builds),
   * which includes every task log under target/streams, for both markers. PASS only when the
   * held-out-route marker is in no file AND the counter-test marker is found (so the scan can see
   * output routed through an sbt task log). Fails closed on any unreadable entry. Pipeline data and
   * held-out run scratch are not read (excluded and counted).
   */
  def routeScan(
      buildBase: File,
      projectTargets: Seq[File],
      probe: RouteProbe,
      log: Logger
  ): Unit = {
    val task = "verifyHeldoutOutputRoute"
    val base = realBase(buildBase)
    val basePath = base.toPath
    val candidates =
      projectTargets ++ Seq(base / "project" / "target", base / "project" / "project" / "target")
    val roots = candidates
      .map(_.toPath)
      .filter(p => Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
      .map(_.toRealPath())
      .filter(_.startsWith(basePath))
      .distinct
      .sortBy(_.toString)
    val excluded = Seq(
      basePath.resolve("modules/hadoop/target/local-pipeline"),
      basePath.resolve(scratchRoot(Heldout))
    )
    if (roots.isEmpty) fail(task, "FAIL: no sbt target directory found; the scan cannot run")
    val needles =
      Seq(probe.terminalMarker, probe.controlMarker).map(_.getBytes(StandardCharsets.UTF_8))
    val terminalHits = ListBuffer.empty[String]
    val controlHits = ListBuffer.empty[String]
    val errors = ListBuffer.empty[String]
    var files = 0
    var bytes = 0L
    var links = 0
    var skippedDirs = 0
    def relOf(p: Path): String = basePath.relativize(p).toString
    roots.foreach { root =>
      Files.walkFileTree(
        root,
        new SimpleFileVisitor[Path] {
          override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
            if (excluded.exists(e => dir.startsWith(e))) {
              skippedDirs += 1; FileVisitResult.SKIP_SUBTREE
            } else FileVisitResult.CONTINUE

          override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
            if (attrs.isSymbolicLink) links += 1
            else if (attrs.isRegularFile) {
              try {
                val hit = fileContains(file, needles)
                files += 1
                bytes += attrs.size
                if (hit(0)) terminalHits += relOf(file)
                if (hit(1)) controlHits += relOf(file)
              } catch { case e: IOException => errors += s"${relOf(file)}: ${e.getMessage}" }
            }
            FileVisitResult.CONTINUE
          }

          override def visitFileFailed(file: Path, exc: IOException): FileVisitResult = {
            errors += s"${relOf(file)}: ${exc.getMessage}"
            FileVisitResult.CONTINUE
          }
        }
      )
    }
    log.info(
      s"$task: scanned ${roots.size} sbt target directories (${roots.map(relOf).mkString(", ")}): " +
        s"$files files, $bytes bytes; $links symbolic links not followed; $skippedDirs excluded " +
        "data/scratch directories not read (modules/hadoop/target/local-pipeline, target/local-runs/heldout)"
    )
    if (errors.nonEmpty)
      fail(
        task,
        s"FAIL: the scan could not read ${errors.size} entr(y/ies): ${errors.take(5).mkString("; ")}"
      )
    if (files == 0) fail(task, "FAIL: the scan read no file")
    if (terminalHits.nonEmpty)
      fail(
        task,
        s"FAIL: the held-out-route marker was found in sbt-persisted files: ${terminalHits.mkString(", ")}; " +
          "held-out output is not kept off the sbt logs"
      )
    if (controlHits.isEmpty)
      fail(
        task,
        "FAIL: counter-test: the control marker routed through the sbt task log was not found in any " +
          "persisted file, so this scan cannot detect a leak"
      )
    log.info(
      s"$task: counter-test PASS: control marker (sbt task log route) found in ${controlHits.mkString(", ")}"
    )
    log.info(
      s"$task: PASS: held-out-route marker sha256=${sha256Hex(probe.terminalMarker)} found in 0 of " +
        s"$files sbt-persisted files; it was printed by the child straight to the terminal"
    )
  }
}

/**
 * Synthetic child for the held-out output-route check (meta-build class, run in a forked JVM).
 * Prints the marker from its environment to stdout and stderr and exits 0; exits 2 without a marker
 * and 3 when a write failed. Deliberately independent of sbt and of LocalRuns.
 */
object LocalRunsRouteEmitter {
  val MarkerEnvVar = "SCHOLARCHAIN_ROUTE_PROBE_MARKER"

  def main(args: Array[String]): Unit = {
    val marker = System.getenv(MarkerEnvVar)
    if (marker == null || marker.isEmpty) {
      System.err.println("[route-probe] no marker in the environment")
      System.exit(2)
    }
    System.out.println("[route-probe] stdout synthetic marker=" + marker)
    System.err.println("[route-probe] stderr synthetic marker=" + marker)
    System.out.flush()
    System.err.flush()
    if (System.out.checkError() || System.err.checkError()) System.exit(3)
  }
}
