# BE-P5-02 reconciled build draft (NOT applied) — build-engineer report, verbatim

Saved by the orchestrator, 2026-11-13, per decisions-15. **Proposal for human review only. No file in `build.sbt` or `project/**` was changed; nothing was compiled or run.** It replaces `build-draft-be-p5-01.md` (main report and addenda 1 and 2).

---

BE-P5-02 (build-engineer): one reconciled diff that replaces every earlier draft (the BE-P5-01 main report and addenda 1 and 2). It is a proposal only. I wrote no files and did not run sbt, so nothing below has been compiled or run, and every behaviour I describe is **unverified** unless I say I observed it.

## 0. What this replaces and what it settles
- **Evaluation:** two tasks, `goldSetEvalDev` and `goldSetEvalHeldout` (decisions-15 item 1). Addendum 1's bare held-out `sbt goldSetEval` is gone. The bare name now only refuses.
- **Freeze:** the build never reads, parses, computes or embeds the hash. It checks that `gold/entity-resolution/<split>-v1/er-gold-membership.jsonl.sha256` is committed and byte-identical to HEAD, then passes `--manifest-sha256-file=<absolute path>` and `--expect-split=<split>`. `FreezeRecord` (ER) parses the file, enforces the exact name and the same directory, re-hashes the membership and exits 2 on any mismatch. This is the one hash-file format, verified by the evaluation, as item 2 asks.
- **Config:** the committed, human-owned `gold/entity-resolution/<split>-v1/er-config.conf` is set as `SCHOLARCHAIN_CONFIG_FILES` in the forked JVM's environment. The task refuses while any `SCHOLARCHAIN_CFG_*` variable is inherited. The program prints `config file=… sha256=…` itself, so the build does no config hashing.
- **Pipeline:** the task forks `ErSplitPipelineMain <split>` (HD's fixed inputs, outputs, attempt directories and retry rule) inside a build envelope. The descriptor file and its parser from my earlier draft are dropped.
- **Process control:** the forks use `java.lang.ProcessBuilder` instead of sbt's `Fork`. Three reasons:
  - it can **remove** inherited variables (AWS_* and SCHOLARCHAIN_*), which `envVars` cannot;
  - it has `destroyForcibly`;
  - it has `waitFor(timeout)`.
  The `SCHOLARCHAIN_CFG_*` refusal stays as decided. Removing those variables is only an extra safeguard.

## 1. Timeout arithmetic (decisions-15 item 3)
Pipeline bound **90 s** (`PipelineTimeoutSeconds`). It applies to the forked JVM only, not to compilation.
- **Dev, measured (dev-run-01):** the pipeline itself took 10.957 s. The whole sbt invocation took 15.513 s wall, so sbt start-up, load, a no-op compile and the fork cost 15.513 − 10.957 ≈ 4.6 s.
- **Held-out estimate:** it has 192 vs 98 provenance rows. Scaling linearly: 10.957 × 192/98 ≈ 21.5 s, plus about 2 s of JVM start, so about 23.5 s. This is an upper-side estimate, because each local MapReduce job has a fixed setup cost. The in-suite stage totals of 9.2–9.5 s show little variance.
- **Margin:** 90 s is about 3.8× the held-out estimate and about 8.2× the dev elapsed time. 60 s would be only about 2.6×, too tight for cold-cache or JIT variance on a first run. 120 s (my earlier draft) leaves less room under the tool limit.
- **Tool limit:** about 180 s per call including sbt start-up.
  - Normal case: 180 − 90 = 90 s left for sbt boot, load and git checks, against the 4.6 s observed (about 20×).
  - Worst case: 90 s bound + 10 s SIGTERM grace before SIGKILL + 5 s output drain = 105 s, plus overhead, still under 180 s.
  - If compilation is needed (for example the first run after a commit), the agent runs `sbt Test/compile` first as a separate call. That command is already allowlisted for all identities.

Evaluation bound **60 s** (`EvalTimeoutSeconds`). This one is **not measured**. It is pure in-memory work on at most 240 members, with the same 10 s and 5 s stop sequence. The human should `time sbt goldSetEvalDev` once to confirm it.

## 2. Proposed diff

Hunk line numbers come from reading the files, so apply by context. `sbt fmt` must run before `sbt check`, because `scalafmtSbtCheck` covers both `build.sbt` and `project/*.scala`.

```diff
--- a/build.sbt
+++ b/build.sbt
@@ -2,8 +2,19 @@
 // Every version comes from project/versions.properties via Ledger (project/Ledger.scala),
 // resolved from the build base directory.
 // The internal dependency graph mirrors CLAUDE.md "Module dependency boundaries" exactly.
-// Environment variables: the build uses only the NAMES of inherited AWS_* variables, to
-// blank them in forked test JVMs (see commonSettings). No other variable is read.
+// Environment variables (role law 7; the complete list):
+//   - AWS_*: only the NAMES of inherited variables are used: blanked in forked test and
+//     runMain JVMs (awsIsolationEnv) and removed from the local-run JVMs
+//     (project/LocalRuns.scala). Values are never read.
+//   - SCHOLARCHAIN_CFG_*: only the NAMES are checked, by goldSetEvalDev / goldSetEvalHeldout,
+//     which refuse to run while any is set. Local-run JVMs never inherit any SCHOLARCHAIN_*.
+//   - SCHOLARCHAIN_CONFIG_FILES: never read by the build; the two evaluation tasks set it in
+//     their forked JVM to the committed gold/entity-resolution/<split>-v1/er-config.conf.
+//   - PATH: used only to locate `git` (markTestRun and the local runs).
+// No other variable is read.
 
 // Build-policy guard (review F1). A strict val: it runs while sbt initializes this file
@@ -22,6 +33,20 @@ lazy val verifyVersionLedger =
   taskKey[Unit]("Checks the version ledger, its tool-mandated mirrors, and literal versions")
 lazy val verifyBuildSources =
   taskKey[Unit]("Fails if any *.sbt file exists outside /build.sbt and /project/")
+// Fixed-shape local runs (BE-P5-02, decisions-15): no arguments; see project/LocalRuns.scala.
+lazy val localPipeline =
+  taskKey[Unit]("Refuses: run localPipelineDev or localPipelineHeldout")
+lazy val localPipelineDev =
+  taskKey[Unit]("Bounded local Hadoop pipeline, dev split (ErSplitPipelineMain dev)")
+lazy val localPipelineHeldout =
+  taskKey[Unit]("Bounded local Hadoop pipeline, held-out split (ErSplitPipelineMain heldout)")
+lazy val goldSetEval =
+  taskKey[Unit]("Refuses: run goldSetEvalDev or goldSetEvalHeldout")
+lazy val goldSetEvalDev =
+  taskKey[Unit]("Gold-set evaluation, dev split (gold/entity-resolution/dev-v1/): DEV diagnostics only")
+lazy val goldSetEvalHeldout =
+  taskKey[Unit]("Gold-set evaluation, held-out split (gold/entity-resolution/heldout-v1/), recorded freeze")
 
 ThisBuild / versionLedger := Ledger.load((ThisBuild / baseDirectory).value)
 ThisBuild / organization := "scholarchain"
@@ -71,41 +96,44 @@ val java17Opens = Seq(
   "sun.util.calendar"
 ).map(p => s"--add-opens=java.base/$p=ALL-UNNAMED") :+ "-Djdk.reflect.useDirectMethodHandle=false"
 
+// Best-effort AWS isolation for forked JVMs (tests, runMain, local runs). The network is NOT
+// blocked. sbt can add or override variables of a forked JVM but cannot unset inherited ones,
+// so every AWS_* variable present in sbt's environment (by name; values are not used) and the
+// named credential, profile, role, container-credential, and endpoint variables are set to "".
+// Per-service AWS_ENDPOINT_URL_<SERVICE> variables cannot all be enumerated; only those
+// inherited by sbt are blanked. Config and credentials files point at nonexistent paths; IMDS
+// is disabled. (The local runs additionally remove every inherited AWS_* first.)
+def awsIsolationEnv(noAws: File): Map[String, String] = {
+  val named = Seq(
+    "AWS_ACCESS_KEY_ID",
+    "AWS_SECRET_ACCESS_KEY",
+    "AWS_SESSION_TOKEN",
+    "AWS_PROFILE",
+    "AWS_DEFAULT_PROFILE",
+    "AWS_ROLE_ARN",
+    "AWS_ROLE_SESSION_NAME",
+    "AWS_WEB_IDENTITY_TOKEN_FILE",
+    "AWS_CONTAINER_CREDENTIALS_FULL_URI",
+    "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI",
+    "AWS_CONTAINER_AUTHORIZATION_TOKEN",
+    "AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE",
+    "AWS_ENDPOINT_URL"
+  )
+  val inherited = sys.env.keySet.filter(_.startsWith("AWS_"))
+  (named ++ inherited).map(_ -> "").toMap ++ Map(
+    "AWS_EC2_METADATA_DISABLED" -> "true",
+    "AWS_CONFIG_FILE" -> (noAws / "config").getAbsolutePath,
+    "AWS_SHARED_CREDENTIALS_FILE" -> (noAws / "credentials").getAbsolutePath
+  )
+}
+
 lazy val commonSettings = Seq(
   scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-release:17", "-Werror"),
   javacOptions ++= Seq("--release", "17"),
   libraryDependencies += munit.value % Test,
-  // Best-effort AWS isolation for forked test JVMs. The network is NOT blocked.
-  // sbt can add or override variables of the forked JVM but cannot unset inherited
-  // ones, so every AWS_* variable present in sbt's environment (by name; values are
-  // not used) and the named credential, profile, role, container-credential, and
-  // endpoint variables are set to "". Per-service AWS_ENDPOINT_URL_<SERVICE>
-  // variables cannot all be enumerated; only those inherited by sbt are blanked.
-  // Config and credentials files point at nonexistent paths; IMDS is disabled.
+  // AWS isolation for forked test JVMs: see awsIsolationEnv (behaviour unchanged).
   Test / fork := true,
-  Test / envVars ++= {
-    val named = Seq(
-      "AWS_ACCESS_KEY_ID",
-      "AWS_SECRET_ACCESS_KEY",
-      "AWS_SESSION_TOKEN",
-      "AWS_PROFILE",
-      "AWS_DEFAULT_PROFILE",
-      "AWS_ROLE_ARN",
-      "AWS_ROLE_SESSION_NAME",
-      "AWS_WEB_IDENTITY_TOKEN_FILE",
-      "AWS_CONTAINER_CREDENTIALS_FULL_URI",
-      "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI",
-      "AWS_CONTAINER_AUTHORIZATION_TOKEN",
-      "AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE",
-      "AWS_ENDPOINT_URL"
-    )
-    val inherited = sys.env.keySet.filter(_.startsWith("AWS_"))
-    (named ++ inherited).map(_ -> "").toMap ++ Map(
-      "AWS_EC2_METADATA_DISABLED" -> "true",
-      "AWS_CONFIG_FILE" -> (target.value / "no-aws" / "config").getAbsolutePath,
-      "AWS_SHARED_CREDENTIALS_FILE" -> (target.value / "no-aws" / "credentials").getAbsolutePath
-    )
-  },
+  Test / envVars ++= awsIsolationEnv(target.value / "no-aws"),
   testOutcome := TestMarker.Outcome(
     thisProject.value.id,
     (Test / executeTests).value,
@@ -128,6 +156,36 @@ def jobAssembly(jarBase: String) = Seq(
   }
 )
 
+// Fixed-shape local runs (BE-P5-02). Each forks one JVM from the repository root; inputs,
+// bounds, refusals, and exit-code messages are in project/LocalRuns.scala.
+def pipelineRun(split: LocalRuns.Split) = Def.task {
+  LocalRuns.localPipeline(
+    split,
+    (ThisBuild / baseDirectory).value,
+    javaHome.value,
+    // ErSplitPipelineMain is in the hadoop test tree; this classpath also carries the Provided
+    // Hadoop jars, as `hadoop/Test/runMain` does. Same JVM options as Test / javaOptions.
+    (hadoop / Test / fullClasspath).value.map(_.data),
+    java17Opens,
+    awsIsolationEnv(target.value / "no-aws"),
+    streams.value.log
+  )
+}
+def goldSetEvalRun(split: LocalRuns.Split) = Def.task {
+  LocalRuns.goldSetEval(
+    split,
+    (ThisBuild / baseDirectory).value,
+    javaHome.value,
+    (entityResolution / Compile / fullClasspath).value.map(_.data),
+    awsIsolationEnv(target.value / "no-aws"),
+    sys.env,
+    streams.value.log
+  )
+}
+
 // --- Projects ----------------------------------------------------------------------
 
 lazy val core = project
@@ -141,7 +199,16 @@ lazy val core = project
 lazy val entityResolution = project
   .in(file("modules/entity-resolution"))
   .dependsOn(core)
   .settings(commonSettings)
-  .settings(name := "scholarchain-entity-resolution")
+  .settings(
+    name := "scholarchain-entity-resolution",
+    // decisions-15 item 4: runMain (the human's Terminal generators and evaluation) forks a JVM
+    // in the repository root, so sys.exit ends only that JVM and relative paths resolve from the
+    // root. AWS isolation as in the tests; inherited SCHOLARCHAIN_* variables still reach it.
+    Compile / run / fork := true,
+    Compile / run / baseDirectory := (ThisBuild / baseDirectory).value,
+    Compile / run / envVars ++= awsIsolationEnv(target.value / "no-aws")
+  )
 
 lazy val hadoop = project
   .in(file("modules/hadoop"))
@@ -155,7 +222,11 @@ lazy val hadoop = project
       hadoopClientApi.value % Provided,
       hadoopClientRuntime.value % Provided
     ),
-    Test / javaOptions ++= java17Opens
+    Test / javaOptions ++= java17Opens,
+    // decisions-15 item 4: Test/runMain already forks (Test / run / fork delegates to
+    // Test / fork) with Test / javaOptions and Test / envVars; it now starts in the repository
+    // root instead of modules/hadoop. Test working directories are unchanged.
+    Test / run / baseDirectory := (ThisBuild / baseDirectory).value
   )
 
 lazy val sparkAnalytics = project
@@ -269,7 +340,15 @@ lazy val root = project
       (ThisBuild / baseDirectory).value,
       testOutcome.all(ScopeFilter(inAnyProject)).value,
       streams.value.log
-    )
+    ),
+    // Fixed-shape local runs (BE-P5-02). None is part of `check`; none writes the test marker.
+    localPipeline := LocalRuns.chooseSplit("localPipeline"),
+    localPipelineDev := pipelineRun(LocalRuns.Dev).value,
+    localPipelineHeldout := pipelineRun(LocalRuns.Heldout).value,
+    goldSetEval := LocalRuns.chooseSplit("goldSetEval"),
+    goldSetEvalDev := goldSetEvalRun(LocalRuns.Dev).value,
+    goldSetEvalHeldout := goldSetEvalRun(LocalRuns.Heldout).value
   )
 
 // --- Aliases -----------------------------------------------------------------------
@@ -281,3 +360,5 @@ addCommandAlias(
 )
 addCommandAlias("packageJobs", "; hadoop/assembly ; sparkAnalytics/assembly")
 addCommandAlias("dockerLocal", "; services/Docker/publishLocal")
+// localPipelineDev/Heldout and goldSetEvalDev/Heldout are root tasks, not aliases: see the
+// root project settings and project/LocalRuns.scala.
--- /dev/null
+++ b/project/LocalRuns.scala
@@ -0,0 +1,330 @@
+import java.io.{BufferedReader, IOException, InputStreamReader}
+import java.nio.charset.StandardCharsets
+import java.nio.file.{Files, LinkOption}
+import java.util.concurrent.TimeUnit
+
+import scala.collection.JavaConverters._
+import scala.sys.process.{Process, ProcessLogger}
+import scala.util.Try
+
+import sbt._
+import sbt.internal.util.MessageOnlyException
+
+/**
+ * Fixed-shape local runs (work order BE-P5-02; human decisions-15 items 1-4). Owner: build-engineer.
+ *
+ * Root tasks without arguments, so each matches one fixed Bash allowlist row:
+ *   - localPipelineDev / localPipelineHeldout fork `ErSplitPipelineMain <split>` (hadoop-engineer),
+ *     which owns its inputs, its output root and attempt directories, and its retry rule. The build
+ *     adds the envelope: committed manifest and sample ledgers, a bounded symlink-free samples tree,
+ *     the repository root as working directory, AWS isolation, no stdin, JVM scratch under
+ *     target/, a wall-clock bound, and an attempt report on every outcome. Nothing is deleted.
+ *   - goldSetEvalDev / goldSetEvalHeldout fork `GoldSetEvalMain` (entity-resolution-engineer) on
+ *     the five committed, human-owned files of gold/entity-resolution/<split>-v1/ with
+ *     `--manifest-sha256-file` and `--expect-split`. The build never reads, computes, or embeds
+ *     the freeze hash: GoldSetEvalMain parses the record, re-hashes the membership, and refuses.
+ *     SCHOLARCHAIN_CONFIG_FILES is set to that split's er-config.conf; the task refuses while any
+ *     SCHOLARCHAIN_CFG_* variable is inherited.
+ *   - localPipeline / goldSetEval refuse and name the two split tasks.
+ * The forked JVMs are started with java.lang.ProcessBuilder so inherited AWS_* and SCHOLARCHAIN_*
+ * variables are removed (not just blanked) and the JVM can be stopped on timeout. None of these
+ * tasks is part of `check`, and none writes the test marker.
+ */
+object LocalRuns {
+
+  sealed abstract class Split(val name: String, val title: String)
+  case object Dev extends Split("dev", "Dev")
+  case object Heldout extends Split("heldout", "Heldout")
+
+  // Build-envelope bounds, not data policy (arithmetic in the BE-P5-02 report: dev pipeline
+  // 10.957 s measured, held-out ~2x, ~180 s agent tool limit including sbt start-up).
+  val PipelineTimeoutSeconds: Long = 90L
+  val EvalTimeoutSeconds: Long = 60L
+  val StopGraceSeconds: Long = 10L
+  val OutputDrainMillis: Long = 5000L
+  val MaxSampleBytes: Long = 64L * 1024L * 1024L
+  val MaxSampleFiles: Int = 1000
+  val MaxCommittedFileBytes: Long = 16L * 1024L * 1024L
+
+  val PipelineMain = "scholarchain.hadoop.local.ErSplitPipelineMain"
+  val EvalMain = "scholarchain.entityresolution.evaluation.GoldSetEvalMain"
+  val ConfigFilesEnvVar = "SCHOLARCHAIN_CONFIG_FILES"
+  val ConfigOverridePrefix = "SCHOLARCHAIN_CFG_"
+
+  // Repository-relative paths. The pipeline paths mirror ErSplit (hadoop-engineer); the build only
+  // checks or reports them.
+  val PipelineRunsRoot = "modules/hadoop/local-runs"
+  def pipelineManifest(s: Split): String =
+    s"$PipelineRunsRoot/er-candidates.${s.name}.run-manifest.json"
+  val SamplesRoot = "datasets/samples/er-candidates"
+  val SampleLedgers: Seq[String] =
+    Seq(s"$SamplesRoot/PROVENANCE.tsv", s"$SamplesRoot/DBLP-RELEASE-RANGES.tsv")
+  def samplesRoot(s: Split): String = s"$SamplesRoot/${s.name}"
+  def attemptsRoot(s: Split): String =
+    s"modules/hadoop/target/local-pipeline/er-candidates/${s.name}"
+  def goldDir(s: Split): String = s"gold/entity-resolution/${s.name}-v1"
+  def scratchRoot(s: Split): String = s"target/local-runs/${s.name}"
+
+  val MembershipFile = "er-gold-membership.jsonl"
+  val FreezeFile: String = MembershipFile + ".sha256"
+  val ConfigFile = "er-config.conf"
+  val QueueFile = "er-review-queue.jsonl"
+  val LabelsFile = "er-gold-labels.jsonl"
+
+  private val AttemptName = "^attempt-([1-9][0-9]*)$".r
+
+  private def refuse(task: String, message: String): Nothing =
+    throw new MessageOnlyException(s"$task: refused: $message")
+
+  private def fail(task: String, message: String): Nothing =
+    throw new MessageOnlyException(s"$task: $message")
+
+  /** The bare names have no default split. */
+  def chooseSplit(name: String): Unit =
+    throw new MessageOnlyException(
+      s"$name has no default split and takes no arguments; run ${name}Dev or ${name}Heldout"
+    )
+
+  private def git(base: File, args: String*): Option[String] =
+    Try(Process("git" +: args, base).!!(ProcessLogger(_ => ()))).toOption
+      .map(_.trim)
+      .filter(_.nonEmpty)
+
+  private def realBase(base: File): File = base.toPath.toRealPath().toFile
+
+  private def rel(base: File, f: File): String = base.toPath.relativize(f.toPath).toString
+
+  private def logHead(task: String, base: File, log: Logger): Unit = {
+    val head = git(base, "rev-parse", "--verify", "HEAD")
+      .getOrElse(refuse(task, "git rev-parse --verify HEAD failed; the local runs need git and a commit"))
+    val worktree =
+      Try(Process(Seq("git", "--no-optional-locks", "status", "--porcelain"), base).!!(ProcessLogger(_ => ())))
+        .toOption
+        .map(s => if (s.trim.isEmpty) "clean" else "dirty")
+        .getOrElse("unknown")
+    log.info(s"$task: git_head=$head git_worktree=$worktree")
+  }
+
+  /** A repository path that resolves without a symbolic link or a differently cased component. */
+  private def inside(task: String, base: File, relPath: String): File = {
+    val f = new File(base, relPath)
+    val abs = f.toPath.toAbsolutePath.normalize
+    if (Files.exists(abs, LinkOption.NOFOLLOW_LINKS) && abs.toRealPath() != abs)
+      refuse(task, s"$relPath resolves through a symbolic link or a differently cased path")
+    f
+  }
+
+  /** A regular file committed at HEAD and byte-identical to it (raw bytes, no filters). */
+  private def committedFile(task: String, base: File, relPath: String): File = {
+    val f = inside(task, base, relPath)
+    if (!Files.isRegularFile(f.toPath, LinkOption.NOFOLLOW_LINKS))
+      refuse(task, s"$relPath is missing or not a regular file")
+    if (f.length > MaxCommittedFileBytes)
+      refuse(task, s"$relPath is larger than $MaxCommittedFileBytes bytes")
+    val atHead = git(base, "rev-parse", "--verify", "--quiet", s"HEAD:$relPath")
+    val inTree = git(base, "hash-object", "--no-filters", "--", relPath)
+    (atHead, inTree) match {
+      case (Some(h), Some(w)) if h == w => f
+      case (None, _) => refuse(task, s"$relPath is not committed at HEAD; the human commits it before the run")
+      case (_, None) => refuse(task, s"$relPath could not be hashed with git hash-object")
+      case _ => refuse(task, s"$relPath differs from HEAD; commit it unmodified before the run")
+    }
+  }
+
+  /** Bytes and regular files under a directory; refuses links, special files, and the bounds. */
+  private def measureTree(task: String, base: File, relPath: String): (Long, Int) = {
+    val dir = inside(task, base, relPath)
+    if (!Files.isDirectory(dir.toPath, LinkOption.NOFOLLOW_LINKS))
+      refuse(task, s"$relPath/ is missing or not a directory")
+    val walk = Files.walk(dir.toPath)
+    val (bytes, files) =
+      try
+        walk.iterator.asScala.foldLeft((0L, 0)) { case ((b, n), p) =>
+          if (Files.isSymbolicLink(p))
+            refuse(task, s"symbolic link in $relPath/: ${base.toPath.relativize(p)}")
+          else if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) (b, n)
+          else if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
+            val b1 = b + Files.size(p)
+            val n1 = n + 1
+            if (b1 > MaxSampleBytes || n1 > MaxSampleFiles)
+              refuse(task, s"$relPath/ exceeds the bound of $MaxSampleFiles files / $MaxSampleBytes bytes")
+            (b1, n1)
+          } else refuse(task, s"special file in $relPath/: ${base.toPath.relativize(p)}")
+        }
+      finally walk.close()
+    if (files == 0) refuse(task, s"$relPath/ contains no regular file")
+    (bytes, files)
+  }
+
+  private final case class Attempt(n: Int, dir: File) {
+    def status: String =
+      if (new File(dir, "_SUCCESS").exists) "complete"
+      else if (new File(dir, "_FAILED").exists) "failed"
+      else "partial"
+  }
+
+  /** Existing attempt-<n> directories of a split (read only; mirrors ErSplit's layout). */
+  private def attempts(base: File, split: Split): List[Attempt] = {
+    val root = (base / attemptsRoot(split)).toPath
+    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) Nil
+    else {
+      val listing = Files.list(root)
+      try
+        listing.iterator.asScala.toList
+          .flatMap { p =>
+            p.getFileName.toString match {
+              case AttemptName(n) if Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) =>
+                Try(n.toInt).toOption.map(Attempt(_, p.toFile)).toList
+              case _ => Nil
+            }
+          }
+          .sortBy(_.n)
+      finally listing.close()
+    }
+  }
+
+  /** The attempt this run created (newer than `before`), and a sentence describing it. */
+  private def attemptState(base: File, split: Split, before: Option[Int]): (Option[Attempt], String) =
+    attempts(base, split).lastOption.filter(a => before.forall(_ < a.n)) match {
+      case Some(a) if a.status == "complete" =>
+        (Some(a), s"attempt directory ${rel(base, a.dir)} is complete (_SUCCESS present)")
+      case Some(a) =>
+        val state =
+          if (a.status == "failed") "FAILED (_FAILED present); not evidence"
+          else "PARTIAL (neither _SUCCESS nor _FAILED); incomplete and not evidence"
+        (
+          Some(a),
+          s"this run's attempt directory ${rel(base, a.dir)} is $state; nothing was deleted; the " +
+            s"next run uses a fresh attempt directory (attempt-${a.n + 1}) and reports this one as " +
+            s"status=${a.status}"
+        )
+      case None =>
+        (None, s"this run created no attempt directory under ${attemptsRoot(split)}/; nothing was deleted")
+    }
+
+  private def scratchOptions(base: File, split: Split, kind: String): Seq[String] = {
+    val root = base / scratchRoot(split)
+    IO.createDirectory(root)
+    val dir = Files.createTempDirectory(root.toPath, s"$kind-jvm-").toFile
+    IO.createDirectory(dir / "tmp")
+    Seq(
+      s"-Djava.io.tmpdir=${(dir / "tmp").getAbsolutePath}",
+      s"-XX:ErrorFile=${(dir / "hs_err_pid%p.log").getAbsolutePath}"
+    )
+  }
+
+  private def pump(name: String, in: java.io.InputStream, log: Logger): Thread = {
+    val t = new Thread(
+      new Runnable {
+        def run(): Unit = {
+          val r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
+          try {
+            var line = r.readLine()
+            while (line != null) {
+              log.info(line)
+              line = r.readLine()
+            }
+          } catch { case _: IOException => () }
+          finally { Try(r.close()); () }
+        }
+      },
+      name
+    )
+    t.setDaemon(true)
+    t.start()
+    t
+  }
+
+  /** Forks one JVM in `base`; Some(exit code), or None when the bound was reached. */
+  private def runForked(
+      task: String,
+      base: File,
+      javaHome: Option[File],
+      classpath: Seq[File],
+      jvmOptions: Seq[String],
+      env: Map[String, String],
+      mainClass: String,
+      args: Seq[String],
+      timeoutSeconds: Long,
+      log: Logger
+  ): Option[Int] = {
+    val javaBin = javaHome.getOrElse(new File(System.getProperty("java.home"))) / "bin" / "java"
+    if (!javaBin.isFile) refuse(task, s"no java executable at $javaBin")
+    val cp = classpath.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator)
+    val command =
+      Seq(javaBin.getAbsolutePath) ++ jvmOptions ++ Seq("-classpath", cp, mainClass) ++ args
+    val builder = new java.lang.ProcessBuilder(command.asJava)
+    builder.directory(base)
+    builder.redirectErrorStream(true)
+    val childEnv = builder.environment()
+    childEnv.keySet.asScala.toList
+      .filter(k => k.startsWith("AWS_") || k.startsWith("SCHOLARCHAIN_"))
+      .foreach(k => childEnv.remove(k))
+    env.foreach { case (k, v) => childEnv.put(k, v) }
+    log.info(
+      s"$task: forking $mainClass ${args.mkString(" ")} in $base " +
+        s"(classpath ${classpath.size} entries; bound ${timeoutSeconds}s; no stdin)"
+    )
+    val child = builder.start()
+    child.getOutputStream.close()
+    val killer = new Thread(new Runnable { def run(): Unit = { child.destroyForcibly(); () } })
+    Runtime.getRuntime.addShutdownHook(killer)
+    val output = pump(s"$task-output", child.getInputStream, log)
+    try {
+      if (child.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
+        output.join(OutputDrainMillis)
+        Some(child.exitValue())
+      } else {
+        log.error(s"$task: the ${timeoutSeconds}s bound was reached; stopping the forked JVM")
+        child.destroy()
+        if (!child.waitFor(StopGraceSeconds, TimeUnit.SECONDS)) {
+          child.destroyForcibly()
+          child.waitFor(StopGraceSeconds, TimeUnit.SECONDS)
+        }
+        output.join(OutputDrainMillis)
+        None
+      }
+    } catch {
+      case e: InterruptedException =>
+        child.destroyForcibly()
+        throw e
+    } finally {
+      Try(Runtime.getRuntime.removeShutdownHook(killer))
+      ()
+    }
+  }
+
+  /** Bounded local pipeline for one split (ErSplitPipelineMain <split>). */
+  def localPipeline(
+      split: Split,
+      buildBase: File,
+      javaHome: Option[File],
+      classpath: Seq[File],
+      jvmOptions: Seq[String],
+      isolation: Map[String, String],
+      log: Logger
+  ): Unit = {
+    val task = s"localPipeline${split.title}"
+    val base = realBase(buildBase)
+    logHead(task, base, log)
+    committedFile(task, base, pipelineManifest(split))
+    SampleLedgers.foreach(l => committedFile(task, base, l))
+    val (bytes, files) = measureTree(task, base, samplesRoot(split))
+    log.info(
+      s"$task: samples ${samplesRoot(split)}/: $files files, $bytes bytes (sizes only; " +
+        "ErSplitPipelineMain checks every file against the ledger)"
+    )
+    val before = attempts(base, split).lastOption.map(_.n)
+    val outcome = runForked(
+      task,
+      base,
+      javaHome,
+      classpath,
+      jvmOptions ++ scratchOptions(base, split, "pipeline"),
+      isolation,
+      PipelineMain,
+      Seq(split.name),
+      PipelineTimeoutSeconds,
+      log
+    )
+    val (made, report) = attemptState(base, split, before)
+    outcome match {
+      case Some(0) if made.exists(_.status == "complete") =>
+        log.info(s"$task: complete (ErSplitPipelineMain exit 0); $report")
+      case Some(0) =>
+        fail(task, s"ErSplitPipelineMain exited 0, but $report; the run is not evidence")
+      case Some(1) =>
+        fail(task, s"ErSplitPipelineMain exit 1: a stage or a consistency check failed; $report")
+      case Some(2) =>
+        fail(
+          task,
+          s"ErSplitPipelineMain refused (exit 2) before any job ran; see its '[er-split] " +
+            s"split=${split.name} status=refused reason=...' line above (a complete attempt must " +
+            s"first be moved away by the human, as that line says); $report"
+        )
+      case Some(n) => fail(task, s"ErSplitPipelineMain exited $n (unexpected); $report")
+      case None =>
+        fail(
+          task,
+          s"exceeded the ${PipelineTimeoutSeconds}s bound; the forked JVM was stopped; the run " +
+            s"is PARTIAL; $report"
+        )
+    }
+  }
+
+  /** Gold-set evaluation for one split. Writes nothing outside target/local-runs/<split>/. */
+  def goldSetEval(
+      split: Split,
+      buildBase: File,
+      javaHome: Option[File],
+      classpath: Seq[File],
+      isolation: Map[String, String],
+      inheritedEnv: Map[String, String],
+      log: Logger
+  ): Unit = {
+    val task = s"goldSetEval${split.title}"
+    val overrides = inheritedEnv.keys.filter(_.startsWith(ConfigOverridePrefix)).toVector.sorted
+    if (overrides.nonEmpty)
+      refuse(
+        task,
+        s"${overrides.mkString(", ")} set in sbt's environment (names only are checked); " +
+          s"evaluation configuration comes only from ${goldDir(split)}/$ConfigFile; unset them " +
+          "and start sbt again"
+      )
+    val base = realBase(buildBase)
+    logHead(task, base, log)
+    val dir = goldDir(split)
+    val membership = committedFile(task, base, s"$dir/$MembershipFile")
+    val freeze = committedFile(task, base, s"$dir/$FreezeFile")
+    val config = committedFile(task, base, s"$dir/$ConfigFile")
+    val queue = committedFile(task, base, s"$dir/$QueueFile")
+    val labels = committedFile(task, base, s"$dir/$LabelsFile")
+    if (inheritedEnv.contains(ConfigFilesEnvVar))
+      log.info(s"$task: the inherited $ConfigFilesEnvVar is not passed on")
+    log.info(s"$task: config $dir/$ConfigFile only (GoldSetEvalMain prints its sha256)")
+    val args = Seq(
+      membership.getAbsolutePath,
+      queue.getAbsolutePath,
+      labels.getAbsolutePath,
+      s"--manifest-sha256-file=${freeze.getAbsolutePath}",
+      s"--expect-split=${split.name}"
+    )
+    runForked(
+      task,
+      base,
+      javaHome,
+      classpath,
+      scratchOptions(base, split, "eval"),
+      isolation + (ConfigFilesEnvVar -> config.getAbsolutePath),
+      EvalMain,
+      args,
+      EvalTimeoutSeconds,
+      log
+    ) match {
+      case Some(0) if split == Heldout =>
+        log.info(s"$task: final held-out gold metrics reported (GoldSetEvalMain exit 0)")
+      case Some(0) =>
+        log.info(s"$task: DEV diagnostics reported (GoldSetEvalMain exit 0); never final gold metrics")
+      case Some(2) =>
+        fail(
+          task,
+          "GoldSetEvalMain refused (exit 2); see its 'refused:' lines above (freeze record, " +
+            "expected split, configuration, labels, or usage)"
+        )
+      case Some(3) =>
+        fail(
+          task,
+          "GoldSetEvalMain withheld the metrics (exit 3): fewer human-reviewed members than the " +
+            "minimum; coverage is printed above"
+        )
+      case Some(n) => fail(task, s"GoldSetEvalMain exited $n (unexpected)")
+      case None =>
+        fail(
+          task,
+          s"exceeded the ${EvalTimeoutSeconds}s bound; the forked JVM was stopped; nothing from " +
+            "this run counts (the evaluation writes no files)"
+        )
+    }
+  }
+}
```

## 3. What each task does (from reading; unverified)

**`localPipelineDev` / `localPipelineHeldout`**
- **Checks, in order:**
  1. logs `git_head` and `git_worktree`;
  2. the split's manifest must be committed and byte-identical to HEAD;
  3. **addition:** `PROVENANCE.tsv` and `DBLP-RELEASE-RANGES.tsv` must also be committed and unmodified. The main verifies every sample's SHA-256 against these ledgers, so this ties the inputs to HEAD. It is one line to drop if you don't want it;
  4. the `datasets/samples/er-candidates/<split>/` tree: no symlinks or special files, at most 1,000 files and 64 MiB. The task looks only at file sizes and opens no sample file.
- **Forking:** it forks with `java17Opens`, from the repository root, with no stdin. Every inherited AWS_* and SCHOLARCHAIN_* variable is removed and the isolation values are set. `java.io.tmpdir` and `hs_err` files go under `target/local-runs/<split>/pipeline-jvm-*/`. Output lines are passed through the sbt log as `[info] [er-split] …`.
- **Outcomes:**
  - Exit 0 only if this run created an attempt directory with `_SUCCESS`.
  - Exit 1 names the attempt directory and its status.
  - Exit 2 points to the main's `status=refused reason=` line.
  - On timeout: SIGTERM, then SIGKILL after 10 s. The task then names the newest attempt directory created by this run, says it is PARTIAL and not evidence, says nothing was deleted, and says the next run uses `attempt-<n+1>`.
  - Any failure makes sbt exit 1.
- **Pre-fork checks are build code; their order and messages need review.** Refusal messages are not data.

**`goldSetEvalDev` / `goldSetEvalHeldout`**
- **Checks, in order:**
  1. refuse if any `SCHOLARCHAIN_CFG_*` is inherited (checked first, so it can be seen before any gold file exists);
  2. log `git_head`;
  3. the five files, in this order: membership, `.jsonl.sha256`, `er-config.conf`, queue, labels. Each must be committed and byte-identical to HEAD, free of symlinks, and at most 16 MiB.
- **Forking:** absolute paths, `--manifest-sha256-file=<abs>/er-gold-membership.jsonl.sha256`, `--expect-split=<split>`, and `SCHOLARCHAIN_CONFIG_FILES=<abs er-config.conf>`.
- **Exit codes:** 0 means final held-out metrics, or DEV diagnostics for dev. 2 means refused. 3 means below the minimum. Anything else is unexpected. The bound is 60 s.

**`localPipeline` / `goldSetEval`:** these always fail with "… has no default split and takes no arguments; run …Dev or …Heldout".

## 4. Item C: forked runMain from the repository root
- **Hadoop (confirmed by reading only):** `commonSettings` sets `Test / fork := true`, and `hadoop / Test / run / fork` delegates to `hadoop / Test / fork` because the task axis falls back to Zero first. So `hadoop/Test/runMain` already forks, with `Test / javaOptions` (java17Opens) and `Test / envVars` (AWS isolation). Only the working directory changes: it moves from `modules/hadoop` to the root. Not observed with `show`.
- **Your ER-P5-15 section 6 commands after the change:**
  - Step 1 (`hadoop/Test/runMain … ErSplitPipelineMain heldout`): no visible change. The main walks up to the root either way. Outputs, run IDs and attempt rules stay the same.
  - Steps 2, 5 and 6 (`entityResolution/runMain ReviewQueueMain …` and `GoldSetEvalMain …`) now run in a separate JVM whose working directory is the root:
    - relative paths (`G/…`, `datasets/…`, `R`) still resolve from the root, as they do today when sbt is started there;
    - `sys.exit` ends only the forked JVM. A non-zero exit should appear as `Nonzero exit code returned from runner: N`, with sbt exiting 1 (recalled from sbt behaviour, not observed);
    - AWS_* is blanked;
    - your `export SCHOLARCHAIN_CONFIG_FILES="$PWD/G/er-config.conf"` is still inherited by the fork, so keep it absolute;
    - stdin is not connected;
    - output lines may gain an `[info]` prefix;
    - each run adds about 1 s of JVM start.
    No argument changes.
- **`sbt check`:** nothing changes.
  - The `check` alias is unchanged, and none of the new tasks is in it.
  - The test fork settings produce the same environment map. `Test / run / baseDirectory` and `Compile / run / *` affect only `run`/`runMain`.
  - One-time cost: the first sbt load after the change recompiles the meta-build (`project/LocalRuns.scala`), duration unmeasured.
  - Expected counts are the then-current baseline (last observed by the owners: 908 passed, 75 suites).

## 5. Item D: awsIsolationEnv refactor
This is a pure extraction. It uses the same 13 named variables, the inherited AWS_* names, IMDS disabled and the same `target/no-aws` paths, and it is evaluated at the same time (inside the task). Test JVM behaviour should not change, but that is not verified until `sbt check` runs.

## 6. Item E: test log reduction becomes a separate work order, BE-P5-03 (not in this diff)
- **Why it is not zero-risk:**
  - it changes `Test / javaOptions` for every forked test JVM, so any test that asserts on log output could change;
  - by delegation it also changes the `hadoop/Test/runMain` JVM that produced the dev measurement, so the pipeline timing evidence would shift mid-work;
  - it would have to be mirrored into the `localPipeline*` JVM options.
- **What BE-P5-03 contains:** the `quietLogging(base)` def plus three files from my BE-P5-01 item D (`project/test-logging/jul-warn.properties`, `log4j2-warn.properties`, `logback-warn.xml`), WARN and above.
  - `Test / javaOptions ++= quietLogging(...)` goes in `commonSettings`, and is optionally added to the local-run JVM options.
  - It starts by finding the backend that emits the roughly 27 MB of DEBUG output ER-P5-15 noted. My hypothesis, unverified: Logback reaches the `tests` classpath through `services % Test` and logs at DEBUG when it has no configuration file.
  - Acceptance: byte and line counts of the `sbt check` output before and after, and identical test counts.

## 7. Item F: allowlist rows for factory-engineer (FE-P5-03)
- Replace `hadoop-engineer	inactive:B10-alias-pending	allow	-	sbt localPipeline` with the first two rows.
- Replace `entity-resolution-engineer	inactive:B10-alias-pending	allow	-	sbt goldSetEval` with the last two rows.
- The table grows from 119 to **121** rows. The bare names get no row; they only refuse.

TSV form (columns are single TABs; ASCII only):
```
hadoop-engineer	active	allow	-	sbt localPipelineDev	Phase 5 bounded local pipeline, dev split (decisions-15 item 3, BE-P5-02): root task forking ErSplitPipelineMain dev on fixed inputs; committed manifest and ledgers; 90 s bound; writes only modules/hadoop/target/local-pipeline/er-candidates/dev/ and target/local-runs/dev/
hadoop-engineer	active	ask	-	sbt localPipelineHeldout	Phase 5 bounded local pipeline, held-out split (decisions-15 items 1 and 3, BE-P5-02): as the dev row for heldout; ask on every run (human decision)
entity-resolution-engineer	active	allow	-	sbt goldSetEvalDev	Phase 5 dev-split evaluation diagnostics (decisions-15 items 1 and 2, BE-P5-02): committed gold/entity-resolution/dev-v1/ files; freeze file and --expect-split=dev; never final metrics; writes nothing outside target/
entity-resolution-engineer	active	ask	-	sbt goldSetEvalHeldout	Phase 5 held-out gold-set evaluation (decisions-15 items 1 and 2, BE-P5-02): committed gold/entity-resolution/heldout-v1/ files; human-recorded er-gold-membership.jsonl.sha256 and --expect-split=heldout; writes nothing outside target/; ask on every run (human decision)
```
Markdown form:
```
| `hadoop-engineer` | active | allow | - | `sbt localPipelineDev` | Phase 5 bounded local pipeline, dev split (decisions-15 item 3, BE-P5-02); 90 s bound; writes only its gitignored dev output |
| `hadoop-engineer` | active | ask | - | `sbt localPipelineHeldout` | Phase 5 bounded local pipeline, held-out split; ask on every run (human decision) |
| `entity-resolution-engineer` | active | allow | - | `sbt goldSetEvalDev` | Phase 5 dev-split evaluation diagnostics; freeze file and --expect-split=dev; never final |
| `entity-resolution-engineer` | active | ask | - | `sbt goldSetEvalHeldout` | Phase 5 held-out gold-set evaluation; human-recorded freeze file and --expect-split=heldout; ask on every run (human decision) |
```
- **Modes:** both held-out rows are `ask`, as you decided. I recommend `allow` for both dev rows: dev diagnostics are permitted by decisions-05, and the dev pipeline reveals no labels. `ask` is the conservative alternative.
- If D4 decides there will be no dev label set, make `goldSetEvalDev` `inactive:G9-dev-set-undecided`. The task refuses anyway until the dev gold files are committed.
- **Probes FE needs:**
  - ALLOW or ASK for the owner on each of the four rows;
  - BLOCK for every other identity, the bare names, case variants (`sbt goldsetevalheldout`), chaining (`sbt localPipelineDev localPipelineHeldout`), trailing arguments, and cross-identity use (hadoop-engineer running an eval task and the reverse);
  - the freeze-file name in probes and text must be `er-gold-membership.jsonl.sha256`, and the `gold/**` layout gains `er-config.conf`.
- No tripwire matches these words, by my reading. FE must confirm with its validator.

## 8. Item G: Terminal commands for the held-out sequence
Run everything from `/Users/taufeeqpatel/Desktop/ScholarChain`, after commits A and B. All of it stays HELD until you approve.

**Before activation (runMain forms, as they exist now):**
1. `time sbt "hadoop/Test/runMain scholarchain.hadoop.local.ErSplitPipelineMain heldout"`
2. Membership, freeze, labels and queue: exactly ER-P5-15 section 6, steps 2–5.
3. `export SCHOLARCHAIN_CONFIG_FILES="$PWD/gold/entity-resolution/heldout-v1/er-config.conf"`, then `env | grep '^SCHOLARCHAIN_CFG_'`, which must print nothing. Then:
   `sbt "entityResolution/runMain scholarchain.entityresolution.evaluation.GoldSetEvalMain gold/entity-resolution/heldout-v1/er-gold-membership.jsonl gold/entity-resolution/heldout-v1/er-review-queue.jsonl gold/entity-resolution/heldout-v1/er-gold-labels.jsonl --manifest-sha256-file=gold/entity-resolution/heldout-v1/er-gold-membership.jsonl.sha256 --expect-split=heldout"`

**After activation:**
1. `time sbt localPipelineHeldout`. It needs the held-out manifest and both ledgers committed and unmodified.
2. Membership and queue stay the same runMain commands (no task exists for them). With item C they now fork from the root.
3. Commit all five held-out gold files. Unset any `SCHOLARCHAIN_CFG_*`; no export is needed. Then run `time sbt goldSetEvalHeldout`.

Agents can use these only after FE-P5-03 is ratified: hadoop-engineer `sbt localPipelineHeldout` (ask) and entity-resolution-engineer `sbt goldSetEvalHeldout` (ask).

**`sbt check` duration:** no effect, because none of the tasks is in `check`. The only cost is the one-time meta-build recompile.

## 9. Item H: verification plan after activation
**build-engineer, under an apply work order:**
1. Write `build.sbt` and `project/LocalRuns.scala` exactly as in section 2.
2. Run `sbt fmt`, then `sbt Test/compile`, then `sbt check`, and record the exact counts.
3. Run `git diff --stat` and `git status --short`.

I have no allowlist row for the new tasks, so their behaviour has to be observed by the human, once each in a Terminal:
1. `sbt localPipeline` and `sbt goldSetEval`: each refuses, naming its two split tasks; sbt exits 1.
2. `sbt localPipelineDev` before commit A: refuses with `modules/hadoop/local-runs/er-candidates.dev.run-manifest.json is not committed at HEAD` (that file is untracked today).
3. After commit A, with the cap-4 `dev/attempt-1` still in place: `sbt localPipelineDev` forks, the main prints `[er-split] split=dev status=refused reason=…`, and the task fails "refused (exit 2) … this run created no attempt directory".
4. After `mv …/dev/attempt-1 …/dev/attempt-1.cap4`: `time sbt localPipelineDev` completes. Record the wall time; about 16 s is expected, well under the 90 s bound.
5. `sbt goldSetEvalHeldout` before the gold files exist: refuses with `gold/entity-resolution/heldout-v1/er-gold-membership.jsonl is missing or not a regular file`. `sbt goldSetEvalDev` behaves the same for `dev-v1`.
6. `SCHOLARCHAIN_CFG_PROBE=x sbt goldSetEvalDev`: refuses, naming `SCHOLARCHAIN_CFG_PROBE`, before any file check.
7. `sbt "show hadoop/Test/run/fork" "show hadoop/Test/run/baseDirectory" "show entityResolution/Compile/run/fork" "show entityResolution/Compile/run/baseDirectory"`: expected true, repository root, true, repository root.
8. `sbt "entityResolution/runMain scholarchain.entityresolution.evaluation.GoldSetEvalMain"` with no arguments: usage message, `Nonzero exit code returned from runner: 2`, and `echo $?` prints 1. This confirms forked exit handling and reads no files.

The timeout path (SIGTERM/SIGKILL and the partial-attempt message) cannot be observed without a slow run, so it stays unverified. code-reviewer should review `runForked` and `attemptState`.

```text
STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: BE-P5-02 (Phase 5): one reconciled proposed diff (build.sbt, project/LocalRuns.scala) for localPipelineDev/Heldout, goldSetEvalDev/Heldout, refusing bare names, forked runMain from the repository root, awsIsolationEnv refactor; test log reduction routed to BE-P5-03; allowlist rows; command sequences; verification plan. Proposed diff only; activation held.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: git ls-files -- project (ok); git ls-files --others --exclude-standard -- docs/agent-runs/phase-05 (ok); one grep call refused by guard-dangerous.sh (shell-syntax-gate; nothing ran). No sbt.
TEST RESULTS: not applicable (nothing applied; no sbt run)
VALIDATION RESULTS: Nothing compiled or executed; the whole diff is unverified. Read: decisions-15.md, dev-run-01.md, build-draft-be-p5-01.md (all three parts), amendment-fe-p5-01-rev2.md (sections 0-3), agent-report.md HD-P5-02, ER-P5-15, ER-P5-16, HD-P5-03, test-report.md TE-P5-06/07, build.sbt, project/{BuildGuard,Ledger,TestMarker}.scala, project/build.properties (sbt 1.11.7), .gitignore (/target/ and /modules/hadoop/target/ already cover every output; no .gitignore change), ErSplitPipelineMain.scala, ErSplitPipeline.scala (ErSplit paths, Attempts rule), GoldSetEvalMain.scala (flags, exit codes 0/2/3), FreezeRecord.scala (exact name, same directory, strict line), ConfigEvidence.scala.
ASSUMPTIONS: (1) The meta-build is Scala 2.12 under sbt 1.11.7, and these work as I recall: `hadoop / Test / fullClasspath`, `javaHome` (Option[File]), Def.task inside a def, RichFile `/`, and imports that avoid sbt's `Path`. (2) Test/run/fork delegates to Test/fork (read, not shown). Compile/run/{fork,baseDirectory,envVars} apply to runMain. (3) A forked runMain with a non-zero exit reports "Nonzero exit code returned from runner" and sbt exits 1. (4) The attempt layout and markers match ErSplit (the build only reads them). (5) The five gold files will be committed before an evaluation run, as the work order says. (6) git is on sbt's PATH.
RISKS: (1) The meta-build code is untested. A wrong API assumption fails the build load at `sbt check`, which fails closed. (2) Conflict with FE D3/L1: if held-out labels are kept gitignored, goldSetEvalHeldout refuses ("not committed at HEAD"). The alternative is a one-line change for labels only: require a regular, symlink-free file instead of a committed one. This needs your decision. (3) Held-out pipeline outputs live under modules/hadoop/target, so `sbt clean` / `sbt clean check` (allowlisted for every identity) deletes the run the frozen membership was drawn from (FE criterion 4). Archive the attempt directory or avoid clean after the held-out run. The path itself was settled, not re-decided. (4) The 60 s evaluation bound is unmeasured. (5) The build duplicates HD's attempt-directory naming, but only for reporting; if HD changes the layout, only the messages go wrong. (6) The network is not blocked by the OS. The forked programs are owner code that could write anywhere; the audit detects this only after the fact, and code-reviewer must review. (7) The evaluation's stdout (held-out false positives and false negatives) goes through the sbt log into the calling agent's context; that is why the held-out rows are ask. (8) The SIGTERM, SIGKILL and shutdown-hook paths are unobserved. (9) git hash-object reads the held-out label bytes inside sbt; nothing is printed.
MANUAL ACTION REQUIRED: Human: review the diff, the 90 s / 60 s bounds, the added ledger check, the dev-row modes (allow recommended), and the labels-committed versus L1 question. Orchestrator: route section 7 rows and probes to FE-P5-03, and BE-P5-03 (test logging) after the pipeline work. After ratification: an apply work order to build-engineer (fmt, Test/compile, check), code-reviewer review, then the human's section 9 Terminal observations.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human review of this diff together with FE-P5-03; then the apply work order (sbt check passes, code-reviewer APPROVE); FE rows applied with no session open, validator and probes PASS; commits A and B; fresh session; then the section 9 observations; the held-out run only with separate approval.
```
