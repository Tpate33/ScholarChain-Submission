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

  private def readTreeState(task: String, run: GitRunner, allowIgnored: Set[String]): TreeState = {
    def ok(args: Seq[String], what: String): String = run(TreeGitOptions ++ args) match {
      case Some((0, out)) => out
      case Some((n, _)) =>
        refuse(task, s"$what exited $n; the clean-tree check (decision D3(b)) cannot run")
      case None =>
        refuse(
          task,
          s"$what did not run or exceeded ${GitTimeoutSeconds}s; the clean-tree check (decision " +
            "D3(b)) cannot run"
        )
    }
    val scope = "--" +: CleanTreeScope
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
    val (allowed, refused) = ignored.result().partition(allowIgnored.contains)
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
   */
  private def runForked(
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
      log: Logger
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
      .filter(k => k.startsWith("AWS_") || k.startsWith("SCHOLARCHAIN_"))
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
