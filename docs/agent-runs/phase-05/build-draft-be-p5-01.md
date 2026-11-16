# BE-P5-01 build draft (NOT applied): localPipeline and goldSetEval tasks — build-engineer reports, verbatim

Saved by the orchestrator, 2026-11-13, per decisions-14.md. **Draft for human review only. No file in `build.sbt` or `project/**` was changed, nothing was compiled or run.** Three parts follow in order: the main report, addendum 1 (after ER-P5-14) and addendum 2 (after HD-P5-01). Later parts replace the earlier parts they name.

Orchestrator note on open conflicts with the FE-P5-02 amendment draft (`amendment-fe-p5-01-rev2.md`), for the human's decision; the drafts must be reconciled before either is applied:
- **Evaluation rows:** BE addendum 1 drops `goldSetEvalDev` and uses the bare `sbt goldSetEval` for held-out (120 rows); FE addendum keeps `goldSetEvalDev` (allow) and `goldSetEvalHeldout` (ask) (121 rows).
- **Held-out pipeline mode:** BE suggests `allow` for both pipeline rows; FE suggests `ask` for `localPipelineHeldout`.
- **Freeze file name:** BE `er-gold-membership.jsonl.sha256` (shasum output line); FE `er-gold-membership.sha256` (bare 64 hex). BE reads it in the build and passes `--manifest-sha256=`; FE recommends an ER `--manifest-sha256-file=` flag (Option F). BE accepts either.
- **Config for agent runs:** BE inherits `SCHOLARCHAIN_CONFIG_FILES` from the session environment set by the human; FE recommends the task set it via the fork's `envVars` to a human-owned `gold/entity-resolution/<split>-v1/er-config.conf`.
- **Pipeline launcher:** BE addendum 2 recommends a bounded build task (120 s limit, committed-manifest check) that runs hadoop's `ErSplitPipelineMain <split>`; HD/FE describe a plain `Test/runMain` alias. Same allowlist shapes either way.

---

## Part 1 — BE-P5-01 main report

BE-P5-01 (build-engineer): proposal for the per-split `localPipeline` and `goldSetEval` tasks and for quieter test logging. Nothing has been written, run or activated.

I made no Bash calls, ran no sbt and wrote no files. I used Read only, because Grep and Glob were not in this session's tool list, so I opened known paths directly. Nothing below has been compiled or run, and every behaviour I describe comes from reading code.

### 1. Recommended approach: one fixed task per split

I recommend four separate tasks, one per split, each with no arguments: `localPipelineDev`, `localPipelineHeldout`, `goldSetEvalDev`, `goldSetEvalHeldout`. Each task's inputs come from committed files at paths fixed in `project/LocalRuns.scala`. The bare names `localPipeline` and `goldSetEval` are kept only as tasks that refuse and name the two split tasks.

How this meets each requirement:
- **No free arguments through Bash.** Each command is one fixed token, `sbt <taskName>`, so it matches one fixed-shape allowlist row. All arguments are built inside the build from fixed paths.
- **Splits kept separate.** Each split has its own command, approval prompt and evidence. A task reads only its own split's descriptor, its own `datasets/samples/er-candidates/<split>/` tree and its own `gold/entity-resolution/<split>-v1/`. It writes only to `target/local-runs/<split>/`. The descriptor must also state its split on its first line.
- **Why not one descriptor that names the split:** switching splits would mean editing a committed file, and the two runs would share one command and one approval.
- **Why not environment variables:** agents cannot set them (the character gate and allowlist block `VAR=x sbt`). The value would sit in the session environment, not in the repository, so it could not be reviewed.
- **Deterministic outputs, outside git-tracked source.** The pipeline output directory is deleted and rebuilt on every run (only that directory, after a containment check), so a rerun never just skips completed stages and never trips over a half-written stage left by a timeout. Run IDs depend only on the committed manifest. Everything goes under `/target/`, which `.gitignore` already covers, so `.gitignore` does not change. `goldSetEval*` writes nothing except temporary files under `target/`.
- **Held-out `--manifest-sha256`.** The task reads the hash from a human-owned file, `gold/entity-resolution/<split>-v1/er-gold-membership.jsonl.sha256`. The file must hold exactly one line, `<64 lowercase hex>  er-gold-membership.jsonl` (the output format of `shasum -a 256`), and must be committed unmodified. The task passes the value through and never computes it. `GoldSetEvalMain` hashes the manifest itself and compares. The same rule applies to dev.
- **Configuration from `SCHOLARCHAIN_CONFIG_FILES`.** The variable is inherited from the session that runs sbt. The task refuses if it is unset or empty, if any listed file is missing, or if a file name starts with `.env`. It logs each file's path and sha256, then passes absolute paths to the forked program. It also refuses while any `SCHOLARCHAIN_CFG_*` variable is set, so overrides that cannot be reviewed never reach an evidence run. That refusal is a policy choice the human can reverse.
- **No AWS, no network.** The forked program gets the same AWS isolation as the tests (now a shared function, `awsIsolationEnv`). Pipeline inputs must be plain relative paths inside the split's samples tree; colons, so URI schemes like `s3a:` or `http:`, are refused. Hadoop is forced to `fs.defaultFS=file:///` and `mapreduce.framework.name=local`, with `hadoop.tmp.dir` under `target/`. The network is not blocked by the operating system; that risk is unchanged.
- **Bounded runtime and size.** Hard time limits: 120 s for the pipeline program and 60 s for the evaluation. On timeout the program is stopped and the task fails. Inputs are capped at 64 MiB and 10,000 files, committed files at 16 MiB. Together with sbt start-up (about 20–30 s when already compiled) this stays under the ~180 s tool limit. These tasks are not part of `check`, so the 166 s `sbt check` time does not grow.
- **Committed inputs only.** Every descriptor and gold input must be committed and byte-identical to `HEAD` (`git hash-object --no-filters` against `HEAD:<path>`, the same rule as the guard's script check). Each run is therefore tied to a reviewed commit, and the task logs `git_head`.

I chose tasks rather than `runMain` aliases. Hadoop is a `Provided` dependency, and `runMain` uses the project directory as the working directory, so it needs extra fork settings. The tasks fork explicitly with `hadoop/Compile/fullClasspath` (which includes `Provided` jars) or `entityResolution/Compile/fullClasspath`, from the repository root.

### 2. Proposed diff (my territory: `build.sbt`, `project/**`)

Items: A is the four tasks, B is the shared AWS-isolation refactor (behaviour unchanged), C is optional (fork settings for the human's `runMain` commands from FE-P5-01 D2), D is test logging, which you can take or leave separately.

```diff
--- a/build.sbt
+++ b/build.sbt
@@ -2,8 +2,17 @@
 // Every version comes from project/versions.properties via Ledger (project/Ledger.scala),
 // resolved from the build base directory.
 // The internal dependency graph mirrors CLAUDE.md "Module dependency boundaries" exactly.
-// Environment variables: the build uses only the NAMES of inherited AWS_* variables, to
-// blank them in forked test JVMs (see commonSettings). No other variable is read.
+// Environment variables (role law 7; the complete list):
+//   - AWS_*: only the NAMES of inherited variables are used, to blank them in every forked
+//     JVM (tests and the local runs; see awsIsolationEnv). Values are never read.
+//   - SCHOLARCHAIN_CONFIG_FILES: read only by goldSetEvalDev / goldSetEvalHeldout, which check
+//     that every listed file exists, log its sha256, and pass the list to the forked
+//     evaluation (project/LocalRuns.scala). Unset or empty: those tasks refuse.
+//   - SCHOLARCHAIN_CFG_*: only the NAMES are checked, by the same two tasks, which refuse to run
+//     while any is set (evaluation configuration comes only from reviewable files).
+// No other variable is read.
 
 // Build-policy guard (review F1). A strict val: it runs while sbt initializes this file
@@ -22,6 +31,20 @@ lazy val verifyVersionLedger =
   taskKey[Unit]("Checks the version ledger, its tool-mandated mirrors, and literal versions")
 lazy val verifyBuildSources =
   taskKey[Unit]("Fails if any *.sbt file exists outside /build.sbt and /project/")
+// Fixed-shape local runs (BE-P5-01): no arguments; see project/LocalRuns.scala.
+lazy val localPipeline =
+  taskKey[Unit]("Refuses: run localPipelineDev or localPipelineHeldout")
+lazy val localPipelineDev =
+  taskKey[File]("Local bounded Hadoop pipeline over the dev split (modules/hadoop/local-runs/dev/)")
+lazy val localPipelineHeldout =
+  taskKey[File]("Local bounded Hadoop pipeline over the held-out split (modules/hadoop/local-runs/heldout/)")
+lazy val goldSetEval =
+  taskKey[Unit]("Refuses: run goldSetEvalDev or goldSetEvalHeldout")
+lazy val goldSetEvalDev =
+  taskKey[Unit]("Gold-set evaluation, dev split (gold/entity-resolution/dev-v1/), dev diagnostics only")
+lazy val goldSetEvalHeldout =
+  taskKey[Unit]("Gold-set evaluation, held-out split (gold/entity-resolution/heldout-v1/), recorded freeze")
 
 ThisBuild / versionLedger := Ledger.load((ThisBuild / baseDirectory).value)
 ThisBuild / organization := "scholarchain"
@@ -71,41 +94,53 @@ val java17Opens = Seq(
   "sun.util.calendar"
 ).map(p => s"--add-opens=java.base/$p=ALL-UNNAMED") :+ "-Djdk.reflect.useDirectMethodHandle=false"
 
+// Best-effort AWS isolation for forked JVMs (tests and local runs). The network is NOT blocked.
+// sbt can add or override variables of a forked JVM but cannot unset inherited ones, so every
+// AWS_* variable present in sbt's environment (by name; values are not used) and the named
+// credential, profile, role, container-credential, and endpoint variables are set to "".
+// Per-service AWS_ENDPOINT_URL_<SERVICE> variables cannot all be enumerated; only those
+// inherited by sbt are blanked. Config and credentials files point at nonexistent paths;
+// IMDS is disabled.
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
+// Test and local-run logging (BE-P5-01 item D): WARN and above only, for java.util.logging
+// (Hadoop's commons-logging fallback), Log4j 2 (Spark), and Logback (services). The files are
+// in project/test-logging/; to see more, edit them (no environment switch, role law 7).
+def quietLogging(base: File): Seq[String] = {
+  val dir = base / "project" / "test-logging"
+  Seq(
+    s"-Djava.util.logging.config.file=${(dir / "jul-warn.properties").getAbsolutePath}",
+    s"-Dlog4j2.configurationFile=${(dir / "log4j2-warn.properties").getAbsolutePath}",
+    s"-Dlogback.configurationFile=${(dir / "logback-warn.xml").getAbsolutePath}"
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
+  // AWS isolation for forked test JVMs: see awsIsolationEnv.
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
+  Test / javaOptions ++= quietLogging((ThisBuild / baseDirectory).value),
   testOutcome := TestMarker.Outcome(
     thisProject.value.id,
     (Test / executeTests).value,
@@ -128,6 +163,34 @@ def jobAssembly(jarBase: String) = Seq(
   }
 )
 
+// Fixed-shape local runs (BE-P5-01). Each forks one JVM from the repository root; see
+// project/LocalRuns.scala for inputs, outputs, bounds, and refusals.
+def pipelineRun(split: LocalRuns.Split) = Def.task {
+  val base = (ThisBuild / baseDirectory).value
+  LocalRuns.localPipeline(
+    split,
+    base,
+    (hadoop / Compile / fullClasspath).value.map(_.data),
+    java17Opens ++ quietLogging(base),
+    awsIsolationEnv(target.value / "no-aws"),
+    streams.value.log
+  )
+}
+def goldSetEvalRun(split: LocalRuns.Split) = Def.task {
+  val base = (ThisBuild / baseDirectory).value
+  LocalRuns.goldSetEval(
+    split,
+    base,
+    (entityResolution / Compile / fullClasspath).value.map(_.data),
+    quietLogging(base),
+    awsIsolationEnv(target.value / "no-aws"),
+    streams.value.log
+  )
+}
+
 // --- Projects ----------------------------------------------------------------------
 
 lazy val core = project
@@ -142,7 +205,14 @@ lazy val entityResolution = project
   .in(file("modules/entity-resolution"))
   .dependsOn(core)
   .settings(commonSettings)
-  .settings(name := "scholarchain-entity-resolution")
+  .settings(
+    name := "scholarchain-entity-resolution",
+    // Item C (optional; FE-P5-01 D2 human-run generators): `runMain` forks from the
+    // repository root with AWS isolation, so relative paths resolve from the root and
+    // sys.exit ends only the forked JVM.
+    Compile / run / fork := true,
+    Compile / run / baseDirectory := (ThisBuild / baseDirectory).value,
+    Compile / run / envVars ++= awsIsolationEnv(target.value / "no-aws")
+  )
 
 lazy val hadoop = project
   .in(file("modules/hadoop"))
@@ -269,7 +339,15 @@ lazy val root = project
       (ThisBuild / baseDirectory).value,
       testOutcome.all(ScopeFilter(inAnyProject)).value,
       streams.value.log
-    )
+    ),
+    // Fixed-shape local runs (BE-P5-01). Not part of `check`; none writes the test marker.
+    localPipeline := LocalRuns.chooseSplit("localPipeline"),
+    goldSetEval := LocalRuns.chooseSplit("goldSetEval"),
+    localPipelineDev := pipelineRun(LocalRuns.Dev).value,
+    localPipelineHeldout := pipelineRun(LocalRuns.Heldout).value,
+    goldSetEvalDev := goldSetEvalRun(LocalRuns.Dev).value,
+    goldSetEvalHeldout := goldSetEvalRun(LocalRuns.Heldout).value
   )
 
 // --- Aliases -----------------------------------------------------------------------
@@ -281,3 +359,5 @@ addCommandAlias(
 )
 addCommandAlias("packageJobs", "; hadoop/assembly ; sparkAnalytics/assembly")
 addCommandAlias("dockerLocal", "; services/Docker/publishLocal")
+// localPipelineDev/Heldout and goldSetEvalDev/Heldout are root tasks, not aliases:
+// see the root project settings and project/LocalRuns.scala.
--- /dev/null
+++ b/project/LocalRuns.scala
@@ -0,0 +1,262 @@
+import java.nio.file.{Files, LinkOption}
+import java.security.MessageDigest
+import java.util.Locale
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
+ * Fixed-shape local evidence runs (work order BE-P5-01). Owner: build-engineer.
+ *
+ * Four root tasks, none with a command-line argument, so each matches one fixed Bash allowlist
+ * row: `localPipelineDev`, `localPipelineHeldout`, `goldSetEvalDev`, `goldSetEvalHeldout`. Every
+ * argument comes from a committed file at a path fixed here. The freeze SHA-256 is read from a
+ * human-owned file and passed through unchanged; GoldSetEvalMain hashes the manifest and compares.
+ *
+ * Fail-closed rules shared by all four:
+ *   - every input is a plain repository-relative path under its split's fixed root, resolves
+ *     without a symbolic link, and every committed input is byte-identical to HEAD (raw bytes,
+ *     `git hash-object --no-filters`, the same rule as the guard's script provenance);
+ *   - a split never reads the other split's descriptor, samples, or gold directory and writes only
+ *     under target/local-runs/<split>/ (ignored by /target/ in .gitignore);
+ *   - the forked JVM runs from the repository root with AWS isolation, no stdin, java.io.tmpdir and
+ *     JVM crash files under target/, and a wall-clock bound; on timeout it is stopped and the task
+ *     fails;
+ *   - any non-zero exit of the forked program fails the task, so sbt exits non-zero.
+ * None of these tasks is part of `check`, and none writes the test marker.
+ */
+object LocalRuns {
+
+  sealed abstract class Split(val name: String, val title: String)
+  case object Dev extends Split("dev", "Dev")
+  case object Heldout extends Split("heldout", "Heldout")
+
+  // Build-envelope bounds (not data policy). The agent Bash tool limit is about 180 s including
+  // sbt start-up.
+  val PipelineTimeoutSeconds: Long = 120L
+  val EvalTimeoutSeconds: Long = 60L
+  val MaxInputBytes: Long = 64L * 1024L * 1024L
+  val MaxInputFiles: Int = 10000
+  val MaxCommittedFileBytes: Long = 16L * 1024L * 1024L
+
+  val PipelineMain = "scholarchain.hadoop.pipeline.PipelineDriver"
+  val EvalMain = "scholarchain.entityresolution.evaluation.GoldSetEvalMain"
+  val ConfigFilesEnvVar = "SCHOLARCHAIN_CONFIG_FILES"
+  val ConfigOverridePrefix = "SCHOLARCHAIN_CFG_"
+
+  // Fixed per-split locations.
+  def descriptorDir(s: Split): String = s"modules/hadoop/local-runs/${s.name}"
+  def samplesRoot(s: Split): String = s"datasets/samples/er-candidates/${s.name}"
+  def goldDir(s: Split): String = s"gold/entity-resolution/${s.name}-v1"
+  def outputDir(s: Split): String = s"target/local-runs/${s.name}"
+
+  val ManifestFile = "run-manifest.json"
+  val DriverArgsFile = "driver-args.tsv"
+  val MembershipFile = "er-gold-membership.jsonl"
+  val QueueFile = "er-review-queue.jsonl"
+  val LabelsFile = "er-gold-labels.jsonl"
+  val FreezeFile = "er-gold-membership.jsonl.sha256"
+
+  private val InputFlags: Set[String] = Set(
+    "--dblp",
+    "--openalex-works",
+    "--openalex-authors",
+    "--openalex-institutions",
+    "--crossref-works",
+    "--orcid"
+  )
+  private val StagesPattern = "[a-z][a-z-]*(,[a-z][a-z-]*)*".r
+  // Exactly the `shasum -a 256 er-gold-membership.jsonl` output line.
+  private val FreezeLine = "([0-9a-f]{64})  er-gold-membership\\.jsonl".r
+
+  private def refuse(task: String, message: String): Nothing =
+    throw new MessageOnlyException(s"$task: refused: $message")
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
+  private def sha256(f: File): String =
+    MessageDigest
+      .getInstance("SHA-256")
+      .digest(Files.readAllBytes(f.toPath))
+      .map(b => f"${b & 0xff}%02x")
+      .mkString
+
+  /** A plain relative path under `root`, resolved against the canonical base, with no symlink. */
+  def inside(task: String, base: File, rel: String, root: String): File = {
+    val plain = rel.nonEmpty && !rel.startsWith("/") &&
+      !rel.exists(c => c == '\\' || c == ':' || c.isControl) &&
+      rel.split("/", -1).forall(s => s.nonEmpty && s != "." && s != "..")
+    if (!plain) refuse(task, s"'$rel' is not a plain repository-relative path")
+    if (!(rel == root || rel.startsWith(root + "/"))) refuse(task, s"'$rel' is outside $root/")
+    val f = new File(base, rel)
+    if (f.exists && f.getCanonicalFile != f.getAbsoluteFile)
+      refuse(task, s"'$rel' resolves through a symbolic link or a differently cased path")
+    f
+  }
+
+  /** A regular file that is committed at HEAD and byte-identical to it. */
+  def committedFile(task: String, base: File, rel: String, root: String): File = {
+    val f = inside(task, base, rel, root)
+    if (!f.isFile) refuse(task, s"$rel is missing or not a regular file")
+    if (f.length > MaxCommittedFileBytes)
+      refuse(task, s"$rel is larger than $MaxCommittedFileBytes bytes")
+    val atHead = git(base, "rev-parse", "--verify", "--quiet", s"HEAD:$rel")
+    val inTree = git(base, "hash-object", "--no-filters", "--", rel)
+    (atHead, inTree) match {
+      case (Some(h), Some(w)) if h == w => f
+      case (None, _) => refuse(task, s"$rel is not committed at HEAD; commit it before the run")
+      case _ => refuse(task, s"$rel differs from HEAD; commit it unmodified before the run")
+    }
+  }
+
+  /** Total bytes and regular files under `f`; refuses any symbolic link inside. */
+  private def measure(task: String, f: File): (Long, Int) = {
+    val stream = Files.walk(f.toPath)
+    try
+      stream.iterator.asScala.foldLeft((0L, 0)) { case ((bytes, files), p) =>
+        if (Files.isSymbolicLink(p)) refuse(task, s"symbolic link in input: $p")
+        else if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
+          (bytes + Files.size(p), files + 1)
+        else (bytes, files)
+      }
+    finally stream.close()
+  }
+
+  final case class Descriptor(stages: String, inputs: Vector[(String, String)])
+
+  /**
+   * driver-args.tsv: `#` comments and blank lines are ignored; every other line is exactly
+   * `<key><TAB><value>`. The first entry is `split<TAB><split>`, then exactly one `--stages` line
+   * and at least one input line (an input flag of PipelineDriver and a repository-relative path).
+   * `--manifest`, `--curated-root`, and every `-D` option are supplied by the build and refused.
+   */
+  def parseDescriptor(split: Split, lines: Seq[String]): Either[String, Descriptor] = {
+    val data = lines.zipWithIndex.collect {
+      case (l, i) if l.nonEmpty && !l.startsWith("#") => (i + 1, l)
+    }.toVector
+    val parsed = data.map { case (n, l) =>
+      l.split("\t", -1) match {
+        case Array(k, v)
+            if k.nonEmpty && v.nonEmpty && k.trim == k && v.trim == v &&
+              !(k + v).exists(_.isControl) =>
+          Right((n, (k, v)))
+        case _ => Left(s"line $n: expected exactly '<key><TAB><value>'")
+      }
+    }
+    parsed.collectFirst { case Left(e) => e } match {
+      case Some(e) => Left(e)
+      case None =>
+        val rows = parsed.collect { case Right(r) => r }
+        rows.headOption match {
+          case Some((_, ("split", s))) if s == split.name =>
+            val rest = rows.tail
+            val bad = rest.collect {
+              case (n, (k, _)) if k != "--stages" && !InputFlags(k) =>
+                s"line $n: key '$k' is not allowed (allowed: --stages, " +
+                  s"${InputFlags.toVector.sorted.mkString(", ")}; the build supplies " +
+                  "--manifest, --curated-root, and every -D option)"
+            }
+            val stages = rest.collect { case (_, ("--stages", v)) => v }
+            val inputs = rest.collect { case (_, (k, v)) if InputFlags(k) => (k, v) }
+            if (bad.nonEmpty) Left(bad.mkString("; "))
+            else if (stages.size != 1)
+              Left(s"exactly one --stages line is required (found ${stages.size})")
+            else if (!StagesPattern.pattern.matcher(stages.head).matches)
+              Left(s"--stages '${stages.head}' is not a comma-separated stage list")
+            else if (inputs.isEmpty) Left("at least one input line is required")
+            else Right(Descriptor(stages.head, inputs))
+          case Some((n, (k, v))) =>
+            Left(s"line $n: the first entry must be 'split<TAB>${split.name}' (found '$k' '$v')")
+          case None => Left(s"no entries; the first entry must be 'split<TAB>${split.name}'")
+        }
+    }
+  }
+
+  private def runForked(
+      task: String,
+      base: File,
+      classpath: Seq[File],
+      jvmOptions: Seq[String],
+      env: Map[String, String],
+      mainClass: String,
+      args: Seq[String],
+      timeoutSeconds: Long,
+      log: Logger
+  ): Int = {
+    log.info(s"$task: git_head=${git(base, "rev-parse", "--verify", "HEAD").getOrElse("unknown")}")
+    log.info(s"$task: java $mainClass ${args.mkString(" ")} (bound ${timeoutSeconds}s)")
+    val options = ForkOptions()
+      .withWorkingDirectory(base)
+      .withRunJVMOptions(jvmOptions.toVector)
+      .withEnvVars(env)
+      .withOutputStrategy(StdoutOutput)
+      .withConnectInput(false)
+    val cp = classpath.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator)
+    val process = Fork.java.fork(options, Seq("-classpath", cp, mainClass) ++ args)
+    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
+    while (process.isAlive() && System.nanoTime() < deadline) Thread.sleep(250L)
+    if (process.isAlive()) {
+      process.destroy()
+      throw new MessageOnlyException(
+        s"$task: exceeded the ${timeoutSeconds}s bound; the forked JVM was stopped; " +
+          "outputs under target/local-runs/ are incomplete"
+      )
+    }
+    process.exitValue()
+  }
+
+  private def scratchOptions(dir: File): Seq[String] = {
+    IO.delete(dir)
+    IO.createDirectory(dir / "tmp")
+    Seq(
+      s"-Djava.io.tmpdir=${(dir / "tmp").getAbsolutePath}",
+      s"-XX:ErrorFile=${(dir / "hs_err_pid%p.log").getAbsolutePath}"
+    )
+  }
+
+  /** Local bounded Hadoop pipeline for one split; returns the curated root it wrote. */
+  def localPipeline(
+      split: Split,
+      base0: File,
+      classpath: Seq[File],
+      jvmOptions: Seq[String],
+      isolation: Map[String, String],
+      log: Logger
+  ): File = {
+    val task = s"localPipeline${split.title}"
+    val base = base0.getCanonicalFile
+    val dir = descriptorDir(split)
+    val manifest = committedFile(task, base, s"$dir/$ManifestFile", dir)
+    val argsFile = committedFile(task, base, s"$dir/$DriverArgsFile", dir)
+    val d = parseDescriptor(split, IO.readLines(argsFile, IO.utf8))
+      .fold(e => refuse(task, s"$dir/$DriverArgsFile: $e"), identity)
+    val inputs = d.inputs.map { case (flag, rel) =>
+      val f = inside(task, base, rel, samplesRoot(split))
+      if (!f.exists) refuse(task, s"input $rel does not exist")
+      (flag, f)
+    }
+    val (bytes, files) = inputs.map(i => measure(task, i._2)).foldLeft((0L, 0)) {
+      case ((b, n), (b1, n1)) => (b + b1, n + n1)
+    }
+    if (files == 0) refuse(task, "the inputs contain no regular file")
+    if (bytes > MaxInputBytes || files > MaxInputFiles)
+      refuse(task, s"inputs total $bytes bytes in $files files; bound $MaxInputBytes / $MaxInputFiles")
+    val out = base / outputDir(split) / "pipeline"
+    val scratch = scratchOptions(out)
+    val curated = out / "curated"
+    log.info(s"$task: inputs $files files, $bytes bytes, all under ${samplesRoot(split)}/")
+    val args = Seq(
+      "-D",
+      "fs.defaultFS=file:///",
+      "-D",
+      "mapreduce.framework.name=local",
+      "-D",
+      s"hadoop.tmp.dir=${(out / "hadoop-tmp").getAbsolutePath}",
+      "--manifest",
+      manifest.toURI.toString,
+      "--curated-root",
+      curated.toURI.toString,
+      "--stages",
+      d.stages
+    ) ++ inputs.flatMap { case (flag, f) => Seq(flag, f.toURI.toString) }
+    runForked(task, base, classpath, jvmOptions ++ scratch, isolation, PipelineMain, args,
+      PipelineTimeoutSeconds, log) match {
+      case 0 =>
+        log.info(s"$task: completed; outputs under ${outputDir(split)}/pipeline/curated/")
+        curated
+      case 1 => throw new MessageOnlyException(s"$task: a stage failed or refused to overwrite (exit 1)")
+      case 2 => throw new MessageOnlyException(s"$task: invalid driver arguments or run manifest (exit 2)")
+      case n => throw new MessageOnlyException(s"$task: unexpected exit $n")
+    }
+  }
+
+  /** Configuration files for the evaluation: absolute, existing, logged with their sha256. */
+  private def configFiles(task: String, base: File, env: Map[String, String], log: Logger): String = {
+    val overrides = env.keys.filter(_.startsWith(ConfigOverridePrefix)).toVector.sorted
+    if (overrides.nonEmpty)
+      refuse(task, s"${overrides.mkString(", ")} set; evaluation configuration must come only " +
+        s"from the files listed in $ConfigFilesEnvVar")
+    val list = env.get(ConfigFilesEnvVar).filter(_.nonEmpty).getOrElse(
+      refuse(task, s"$ConfigFilesEnvVar is not set; the human sets it before starting the session")
+    )
+    val paths = list.split(",", -1).toVector
+    if (paths.exists(_.isEmpty)) refuse(task, s"$ConfigFilesEnvVar contains an empty path")
+    paths.map { p =>
+      val f0 = new File(p)
+      val f = if (f0.isAbsolute) f0 else new File(base, p)
+      if (f.getName.toLowerCase(Locale.ROOT).startsWith(".env"))
+        refuse(task, s"$p: .env* files are never configuration")
+      if (!f.isFile) refuse(task, s"config file $p is missing or not a regular file")
+      log.info(s"$task: config ${f.getAbsolutePath} sha256=${sha256(f)}")
+      f.getAbsolutePath
+    }.mkString(",")
+  }
+
+  /** Gold-set evaluation for one split. Writes nothing outside target/local-runs/<split>/eval/. */
+  def goldSetEval(
+      split: Split,
+      base0: File,
+      classpath: Seq[File],
+      jvmOptions: Seq[String],
+      isolation: Map[String, String],
+      log: Logger
+  ): Unit = {
+    val task = s"goldSetEval${split.title}"
+    val base = base0.getCanonicalFile
+    val dir = goldDir(split)
+    val membership = committedFile(task, base, s"$dir/$MembershipFile", dir)
+    val queue = committedFile(task, base, s"$dir/$QueueFile", dir)
+    val labels = committedFile(task, base, s"$dir/$LabelsFile", dir)
+    val freeze = committedFile(task, base, s"$dir/$FreezeFile", dir)
+    val recorded = IO.read(freeze, IO.utf8).stripSuffix("\n") match {
+      case FreezeLine(hex) => hex
+      case _ =>
+        refuse(task, s"$dir/$FreezeFile must be exactly one line '<64 lowercase hex>  " +
+          s"$MembershipFile' (the shasum -a 256 output recorded by the human at the freeze)")
+    }
+    val config = configFiles(task, base, sys.env, log)
+    val scratch = scratchOptions(base / outputDir(split) / "eval")
+    val args = Seq(
+      membership.getAbsolutePath,
+      queue.getAbsolutePath,
+      labels.getAbsolutePath,
+      s"--manifest-sha256=$recorded",
+      // Requires the entity-resolution-engineer change in the BE-P5-01 report (exit 2 until then).
+      s"--expect-split=${split.name}"
+    )
+    runForked(task, base, classpath, jvmOptions ++ scratch,
+      isolation + (ConfigFilesEnvVar -> config), EvalMain, args, EvalTimeoutSeconds, log) match {
+      case 0 => log.info(s"$task: evaluation reported (exit 0)")
+      case 2 => throw new MessageOnlyException(s"$task: evaluation refused or usage error (exit 2)")
+      case 3 =>
+        throw new MessageOnlyException(
+          s"$task: metrics withheld: fewer human-reviewed members than the minimum (exit 3)"
+        )
+      case n => throw new MessageOnlyException(s"$task: unexpected exit $n")
+    }
+  }
+}
--- /dev/null
+++ b/project/test-logging/jul-warn.properties
@@ -0,0 +1,5 @@
+# Test and local-run logging (BE-P5-01 item D). Owner: build-engineer.
+# java.util.logging, used by commons-logging when no Log4j binding is present (Hadoop).
+handlers=java.util.logging.ConsoleHandler
+.level=WARNING
+java.util.logging.ConsoleHandler.level=WARNING
--- /dev/null
+++ b/project/test-logging/log4j2-warn.properties
@@ -0,0 +1,9 @@
+# Test and local-run logging (BE-P5-01 item D). Owner: build-engineer.
+# Log4j 2 (Spark and its SLF4J binding). Spark keeps a supplied configuration.
+status = warn
+appender.stderr.type = Console
+appender.stderr.name = stderr
+appender.stderr.target = SYSTEM_ERR
+appender.stderr.layout.type = PatternLayout
+appender.stderr.layout.pattern = %d{HH:mm:ss.SSS} %level %c{1}: %m%n
+rootLogger.level = warn
+rootLogger.appenderRef.stderr.ref = stderr
--- /dev/null
+++ b/project/test-logging/logback-warn.xml
@@ -0,0 +1,10 @@
+<!-- Test and local-run logging (BE-P5-01 item D). Owner: build-engineer.
+     Logback (services Runtime binding); its built-in default without a file is DEBUG. -->
+<configuration>
+  <appender name="STDERR" class="ch.qos.logback.core.ConsoleAppender">
+    <target>System.err</target>
+    <encoder><pattern>%d{HH:mm:ss.SSS} %-5level %logger{36}: %msg%n</pattern></encoder>
+  </appender>
+  <root level="WARN"><appender-ref ref="STDERR"/></root>
+</configuration>
```

Notes on the diff:
- **Line numbers are approximate.** The hunk headers come from reading the file, not from a diff tool, so apply by context.
- **Formatting.** `project/LocalRuns.scala` must pass `scalafmtSbtCheck`. Run `sbt fmt` when activating.
- **Item D (logging).** Hadoop 3.4.1 `hadoop-client-runtime` makes reload4j optional, so Hadoop logs through SLF4J 1.7.36 (no binding) or commons-logging, which falls back to java.util.logging. Spark, used by `sparkAnalytics` and `tests`, brings Log4j 2. Logback is on the `services` Runtime classpath and logs at DEBUG when it has no configuration file. I have not seen which of these produces the volume you noticed, so check it with before and after line counts of the `sbt check` output. The `println` lines in `LocalPipelineSuite` belong to hadoop-engineer and are not affected.

### 3. Exact command shapes for factory-engineer (FE-P5-02)

Replace the two `inactive:B10-alias-pending` rows (`sbt localPipeline`, `sbt goldSetEval`) and add two rows, so the table goes from 119 to 121 rows. The `.tsv` columns are separated by TABs.

```
hadoop-engineer	active	allow	-	sbt localPipelineDev	Phase 5 local bounded pipeline, dev split (BE-P5-01); writes target/local-runs/dev/ only
hadoop-engineer	active	allow	-	sbt localPipelineHeldout	Phase 5 local bounded pipeline, held-out split (BE-P5-01); writes target/local-runs/heldout/ only
entity-resolution-engineer	active	ask	-	sbt goldSetEvalDev	Phase 5 dev-split diagnostics (BE-P5-01); reads gold/entity-resolution/dev-v1/; writes nothing
entity-resolution-engineer	active	ask	-	sbt goldSetEvalHeldout	Phase 5 held-out gold-set evaluation (amendment G9-b, BE-P5-01); reads gold/entity-resolution/heldout-v1/; writes nothing; ask so every run is human-approved
```

- **Modes are your decision.** I suggest `ask` for both evaluation rows, so every run is approved and the labels cannot be used in an evaluate-tune loop (decisions-01 item 4). The dev evaluation could be `allow` if you choose. The pipeline rows reveal no labels, so `allow` is reasonable.
- **No other identity needs a row.** The bare `sbt localPipeline` and `sbt goldSetEval` get no row; they only refuse.
- **The FE-P5-01 probes need updating:** the active `sbt goldSetEval` ASK probe becomes `sbt goldSetEvalHeldout`. They also need ASK probes for `goldSetEvalDev` and `goldSetEvalHeldout` by entity-resolution-engineer, and BLOCK probes for the other identities, case variants (`sbt goldsetevalheldout`), chaining (`sbt check goldSetEvalHeldout`), and hadoop-engineer running the evaluation tasks (and the reverse).
- **The `gold/**` row must cover** `dev-v1/` and the new `er-gold-membership.jsonl.sha256` freeze file. `gold/**` → `HUMAN` already does.
- **Tripwires.** By reading, I found none matching `sbt localpipelinedev`, `sbt localpipelineheldout`, `sbt goldsetevaldev` or `sbt goldsetevalheldout`. FE must confirm with `--validate-allowlist`.

### 4. What each task reads and writes, and how it fails closed

**`localPipelineDev` / `localPipelineHeldout`**
- **Reads:**
  - `modules/hadoop/local-runs/<split>/run-manifest.json` and `driver-args.tsv` (owned by hadoop-engineer), both committed and unmodified;
  - the inputs listed in `driver-args.tsv`, which must sit under `datasets/samples/er-candidates/<split>/`;
  - `hadoop/Compile/fullClasspath`.
- **Writes:** only `target/local-runs/<split>/pipeline/`, which is deleted and rebuilt each run. It contains `curated/hadoop/snapshot=…/run=<id>/…`, `hadoop-tmp/` and `tmp/`.
- **Output:** the driver's stage, counter and stat lines go to stdout unchanged.
- **Refuses when:**
  - a descriptor file is missing, uncommitted or modified;
  - the first descriptor entry does not name this split;
  - a descriptor line uses a key outside the allowed set (`--manifest`, `--curated-root` and `-D` are build-supplied and refused);
  - `--stages` is missing or appears twice;
  - an input path is not plain, is outside the split's root, is a symlink, does not exist, or the inputs are empty or over the size bounds;
  - the run passes 120 s;
  - the driver exits 1 (stage failure) or 2 (bad arguments or manifest).

**`goldSetEvalDev` / `goldSetEvalHeldout`**
- **Reads:**
  - the four files in `gold/entity-resolution/<split>-v1/`: `er-gold-membership.jsonl`, `er-review-queue.jsonl`, `er-gold-labels.jsonl` and `er-gold-membership.jsonl.sha256`, each committed and unmodified;
  - the files listed in `SCHOLARCHAIN_CONFIG_FILES`;
  - `entityResolution/Compile/fullClasspath`.
- **Writes:** only scratch space in `target/local-runs/<split>/eval/` (tmp and crash files). The evaluation itself writes nothing.
- **Refuses when:**
  - any gold file is missing, uncommitted or modified;
  - the freeze line is badly formed;
  - `SCHOLARCHAIN_CONFIG_FILES` is unset or empty, or a listed file is missing or named `.env*`;
  - any `SCHOLARCHAIN_CFG_*` variable is set;
  - the run passes 60 s;
  - `GoldSetEvalMain` exits 2 (refused, including a SHA mismatch or wrong split) or 3 (metrics withheld below the minimum).

**Bare `localPipeline` / `goldSetEval`:** always fail with a message naming the two split tasks.

### 5. Changes needed in other owners' code (not made)

**hadoop-engineer (`modules/hadoop/**`, fits with HD-P5-01)**
1. Create `modules/hadoop/local-runs/{dev,heldout}/run-manifest.json` (`scholarchain.run-manifest.v1`, stage `hadoop.pipeline`, full parameters) and `driver-args.tsv` in the format above. Example: `split⇥heldout`, `--stages⇥normalize,exact-ids,teams,blocking,candidates`, `--dblp⇥datasets/samples/er-candidates/heldout/dblp`, `--openalex-works⇥…`, `--crossref-works⇥…`.
2. Recommended: put a split parameter in each run manifest, so the two splits always get different run IDs.
3. Make `PipelineDriver` print the distinct candidate-pair count as a named stat or counter line. The task forwards stdout but cannot count anything itself.
4. Confirm that no sample file name contains `:`, because Hadoop `Path` rejects such names when listing a directory.
5. Choose the `codeVersion` policy for a manifest that is committed in the repository.

**entity-resolution-engineer (`modules/entity-resolution/**`)**
1. Add `--expect-split=<dev|heldout>` to `GoldSetEvalMain`: refuse (exit 2) when the manifest's split differs. Until this exists, the evaluation tasks fail closed with exit 2 ("unknown argument"). If declined, remove that one argument line from the diff; split integrity then rests on the directory name plus the printed `split=` tag.
2. Confirm the dev gold location `gold/entity-resolution/dev-v1/` and the file names. If ER-P5-14 uses other paths, only the constants in `LocalRuns` change.

**Human**
1. Freeze each split with `shasum -a 256 er-gold-membership.jsonl > er-gold-membership.jsonl.sha256`, run inside that split's gold directory, committed in the manifest commit, with the same value recorded in `human-gate.md`.
2. Set `SCHOLARCHAIN_CONFIG_FILES` before starting the Claude Code session. The config needs dummy but valid AWS, bucket and model values, because the loader requires every key.

```text
STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: BE-P5-01 (Phase 5): prepare the localPipeline and goldSetEval build changes as a proposed diff for human review; activation and execution held
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash calls; Read only)
TEST RESULTS: not applicable (nothing applied; no sbt run)
VALIDATION RESULTS: Nothing compiled or executed; the diff is unverified. Read: decisions-14.md, amendment-fe-p5-01.md, build.sbt, project/TestMarker.scala, project/BuildGuard.scala, project/versions.properties, .gitignore (/target/ already covers target/local-runs/), GoldSetEvalMain.scala (arguments; exit codes 0/2/3; config from ConfigLoader.loadFromSystem), ConfigLoader.scala (SCHOLARCHAIN_CONFIG_FILES and SCHOLARCHAIN_CFG_* rules), PipelineDriver.scala (ToolRunner generic -D options; exit codes 0/1/2), LocalPipelineSuite.scala and TestSupport.scala (local job-runner settings), the phase-04 hadoop report, datasets/er-candidates/split-assignment.tsv, datasets/samples/er-candidates/PROVENANCE.tsv, extraction-result-02.md (per-split sample trees), and the hadoop-client-runtime 3.4.1 POM in the local Coursier cache (reload4j optional, slf4j-api 1.7.36).
ASSUMPTIONS: (1) The sbt 1.11 APIs used behave as recalled: ForkOptions with* methods, Fork.java.fork returning a scala.sys.process.Process with isAlive/destroy/exitValue and fitting a long -classpath, Compile/fullClasspath including Provided jars, and Compile/run/{fork,baseDirectory,envVars} applying to runMain. (2) GenericOptionsParser stops at the first unknown option (--manifest) and passes the rest through. (3) Gold paths gold/entity-resolution/<split>-v1/ with the FE-P5-01 file names, dev included. (4) Pipeline inputs live under datasets/samples/er-candidates/<split>/. (5) The Pipeline creates the curated root itself. (6) Agent sessions inherit SCHOLARCHAIN_CONFIG_FILES from the environment the human starts them in.
RISKS: (1) The meta-build code is untested; a wrong API recollection shows up as a build-load failure at `sbt check`, which fails closed. The meta-build has no unit tests; parseDescriptor and the path rules are checked only by the human's terminal checks. (2) The network is not blocked by the OS; only arguments and environment are fenced. (3) sbt runs owner-written code (driver, evaluation) that could write anywhere; detection after the fact by audit-bash-writes.sh only; code-reviewer must review the entry points. (4) The 120 s pipeline bound may be too small for the real per-split sample sizes (about 290 files) with many local MapReduce jobs; a timeout fails closed; the bound is one constant. (5) Item D may break any test that asserts on log output; it must be checked with sbt check and before/after line counts. (6) The `SCHOLARCHAIN_CFG_*` refusal departs from ConfigLoader's precedence; it is a human decision. (7) Requiring committed, unmodified descriptors and gold files means every evidence run needs a human commit first; this is intended. (8) The --expect-split argument breaks the evaluation tasks (exit 2) until entity-resolution-engineer adds it.
MANUAL ACTION REQUIRED: Human: review this diff, the allowlist row shapes, the row modes, the SCHOLARCHAIN_CFG_* refusal, and items C and D (each can be accepted separately). After ratification: a work order for build-engineer to apply the diff and run sbt fmt, sbt Test/compile and sbt check (code-reviewer reviews). The human then checks once in a terminal: `sbt localPipelineDev` before descriptors exist must refuse with "modules/hadoop/local-runs/dev/run-manifest.json is missing", and `sbt goldSetEvalHeldout` before gold files exist must refuse with "gold/entity-resolution/heldout-v1/er-gold-membership.jsonl is missing". Orchestrator: route FE-P5-02 (rows and probes in section 3) and the hadoop-engineer and entity-resolution-engineer items in section 5.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human review of this draft together with FE-P5-02, HD-P5-01 and ER-P5-14; then the apply work order (sbt check passes, code-reviewer APPROVE), the hadoop and ER entry-point changes, the human applies the constitutional rows with no session open, validate-agent-files.sh and --probes PASS, commit, fresh session; only then any run.
```

---

## Part 2 — BE-P5-01 addendum 1 (after ER-P5-14 G2, G3, G4 and paths)

BE-P5-01 addendum: changes to my draft after ER-P5-14's G2, G3, G4 and path inputs. I wrote no files and made no Bash calls.

I already returned my main report before this input arrived. This addendum replaces the parts listed below; everything else in that report stands. The diff is still uncompiled and unverified.

**What changed in the draft:**
- **Held-out evaluation is now the bare `sbt goldSetEval`.** ER says dev evaluation has no alias and stays human-run, so the held-out task takes the bare name and the ratified row needs no new shape.
- **`goldSetEvalDev` and its allowlist row are dropped.**
- **The hash comes from a human-owned file**, which the build reads. No ER code change is needed for this.

### G2: where `--manifest-sha256` comes from

**Recommendation: the build task reads the human-owned file `gold/entity-resolution/heldout-v1/er-gold-membership.jsonl.sha256` and passes `--manifest-sha256=<hex>` to `GoldSetEvalMain`.**
- The file must hold exactly one line, `<64 lowercase hex>  er-gold-membership.jsonl`, which is what `shasum -a 256` prints.
- It must be committed at HEAD and byte-identical to it.
- The task passes the value through and never computes the hash. `GoldSetEvalMain` hashes the manifest itself and refuses on a mismatch (exit 2).

I rejected embedding the hash in `build.sbt`:
- build-engineer would be writing a value only the human may record;
- every re-freeze would need a build change and review;
- the evidence would live in build policy instead of `gold/**`.

ER's `--manifest-sha256-file=<path>` is also acceptable, and its parsing could be unit-tested in the module, which the meta-build cannot do. If you prefer it, ER adds the flag first and the task changes one argument:
```diff
-      s"--manifest-sha256=$recorded",
+      s"--manifest-sha256-file=${freeze.getAbsolutePath}",
```
The `FreezeLine` check then moves into ER code. I would still keep the committed-and-unmodified check in the build task.

**Revised evaluation command (replaces the held-out wiring in my draft).** It is a task, not a `runMain` alias, so it forks explicitly:
```diff
-lazy val goldSetEval =
-  taskKey[Unit]("Refuses: run goldSetEvalDev or goldSetEvalHeldout")
-lazy val goldSetEvalDev =
-  taskKey[Unit]("Gold-set evaluation, dev split (gold/entity-resolution/dev-v1/), dev diagnostics only")
-lazy val goldSetEvalHeldout =
-  taskKey[Unit]("Gold-set evaluation, held-out split (gold/entity-resolution/heldout-v1/), recorded freeze")
+lazy val goldSetEval =
+  taskKey[Unit]("Held-out gold-set evaluation (gold/entity-resolution/heldout-v1/), recorded freeze; see project/LocalRuns.scala")
 ...
-    goldSetEval := LocalRuns.chooseSplit("goldSetEval"),
-    goldSetEvalDev := goldSetEvalRun(LocalRuns.Dev).value,
-    goldSetEvalHeldout := goldSetEvalRun(LocalRuns.Heldout).value
+    goldSetEval := goldSetEvalRun(LocalRuns.Heldout).value
```
In `project/LocalRuns.scala`, `goldSetEval(split = Heldout, …)` works exactly as drafted. Two edits:
- the task label becomes `"goldSetEval"`;
- the `--expect-split=heldout` line stays only if ER agrees to add that argument. ER-P5-14 did not mention it. Without it, a held-out manifest is identified by its directory and the `split=` tag the program prints. I recommend ER adds it.

**Arguments passed:** absolute paths to `er-gold-membership.jsonl`, `er-review-queue.jsonl` and `er-gold-labels.jsonl` (ER's paths, unchanged), then `--manifest-sha256=<hex>`. There is no `--max-errors`, so the program's minimum of 10 applies and the build hardcodes no number.

### G3: how a run gets its configuration

- `SCHOLARCHAIN_CONFIG_FILES` is inherited from the environment of the process that starts sbt. Agents cannot set variables through Bash, so the human must set it before starting the Claude Code session.
- The task refuses if the variable is unset or empty, if any path in it is empty or missing, or if a file name starts with `.env`. It also refuses while any `SCHOLARCHAIN_CFG_*` variable is set; you can reverse that policy.
- It logs each config file's absolute path and sha256, then gives the forked JVM the variable with absolute paths. The program's no-fallback loader therefore sees exactly the logged files.
- The config must contain valid dummy AWS, bucket and model values, because every key is required. None of them is used.

### G4: forking, working directory, exit codes

- **Forked JVM.** The task runs the program in its own JVM with the canonical repository root as working directory. `sys.exit` ends only that JVM; sbt survives and reads the exit code.
- **Exit-code mapping in the task:**
  - 0: success;
  - 2: fails with "evaluation refused or usage error (exit 2)";
  - 3: fails with "metrics withheld … (exit 3)";
  - anything else: fails with "unexpected exit N".
  - A timeout (60 s) stops the JVM and fails the task.
- **What the shell sees.** A failed task makes sbt 1.11.7 in batch mode print `[error]` and exit with status 1. The program's own code (2 or 3) appears in the error message, not as sbt's exit status. Anything that only checks success or failure works.
- **Item C (optional, for `runMain` commands the human runs in a terminal):**
  - `Compile / run / fork := true`;
  - `Compile / run / baseDirectory := (ThisBuild / baseDirectory).value`, so relative paths resolve from the repository root;
  - AWS isolation as in the tests.
  - Under sbt 1.11.7 a forked `runMain` that exits non-zero fails with "Nonzero exit code returned from runner: N", and sbt exits 1.
  - Without forking, `sys.exit` runs inside the sbt JVM. On JDK 17 `trapExit` no longer protects against that, and I have not observed what actually happens. Forking avoids the question.

### Paths

- **Held-out:** the task uses ER's three files, plus the human-owned freeze file `er-gold-membership.jsonl.sha256` in the same directory. FE-P5-02 should name that fourth file in the `gold/**` row.
- **Dev:** no task and no row. It stays human-run as DEV diagnostics, matching ER-P5-14. If wanted later, `goldSetEvalDev` is one more line and one more row.
- **Pipeline:** unchanged. `localPipelineDev` and `localPipelineHeldout` stay separate per split. The bare `localPipeline` still refuses and names the two split tasks.

### Revised allowlist rows for FE-P5-02 (replaces section 3 of my report)

- The `goldSetEval` row is exactly the FE-P5-01 row C activation: `entity-resolution-engineer	active	ask	-	sbt goldSetEval	…`. Its shape and the FE-P5-01 probes are unchanged. Only the justification text should say "held-out (BE-P5-01 task)".
- Replace the inactive `sbt localPipeline` row with two rows:
```
hadoop-engineer	active	allow	-	sbt localPipelineDev	Phase 5 local bounded pipeline, dev split (BE-P5-01); writes target/local-runs/dev/ only
hadoop-engineer	active	allow	-	sbt localPipelineHeldout	Phase 5 local bounded pipeline, held-out split (BE-P5-01); writes target/local-runs/heldout/ only
```
- The table grows from 119 to 120 rows: one edited in place, one replaced by two.
- New probes needed: ASK or ALLOW for hadoop-engineer on each pipeline task; BLOCK for every other identity, for `sbt localPipeline`, for case variants, and for chaining.

```text
STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: BE-P5-01 addendum (ER-P5-14 inputs G2, G3, G4 and paths)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash calls)
TEST RESULTS: not applicable (nothing applied; no sbt run)
VALIDATION RESULTS: not executed; the revised diff is uncompiled. GoldSetEvalMain arguments and exit codes 0/2/3 were re-checked by reading the file.
ASSUMPTIONS: ER's held-out paths are final. Dev evaluation stays human-run. sbt 1.11.7 batch mode exits 1 on a failed task, and a forked runMain reports "Nonzero exit code returned from runner" (recalled, not observed).
RISKS: As in my main report. Also: the program's exit codes 2 and 3 reach the shell only as sbt exit status 1, with the code in the error message. Without ER's --expect-split argument, split integrity rests on the fixed directory plus the printed split tag.
MANUAL ACTION REQUIRED: Human: choose between the build reading the freeze file (recommended) and ER's --manifest-sha256-file; accept or reject item C and the SCHOLARCHAIN_CFG_* refusal; set SCHOLARCHAIN_CONFIG_FILES before the session. Orchestrator: pass the revised rows to FE-P5-02, and ask ER whether it will add --expect-split (and --manifest-sha256-file if that option is chosen).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human review together with FE-P5-02, HD-P5-01 and ER-P5-14; then the apply work order (sbt check, code-reviewer); constitutional rows applied with no session open; validator and --probes PASS; commit; fresh session.
```

---

## Part 3 — BE-P5-01 addendum 2 (after HD-P5-01)

BE-P5-01 addendum 2: hadoop-engineer's HD-P5-01 input is folded in. I wrote no files, made no Bash calls and ran no sbt; the revised diff is uncompiled.

I'm adopting hadoop-engineer's entry point `ErSplitPipelineMain <split>`, with its fixed paths and its committed manifest. I still recommend that the build start it through a bounded task instead of a plain `Test/runMain` alias. My file-driven descriptor (`driver-args.tsv`) and its parser are dropped. Everything else from my main report and addendum 1 still applies, including `sbt goldSetEval` as the held-out evaluation.

### 1. What I take from HD-P5-01

- **Who owns what.** The main class owns the input root `datasets/samples/er-candidates/<s>/`, the output root `modules/hadoop/target/local-pipeline/er-candidates/<s>/curated` (already ignored by `/modules/hadoop/target/` in `.gitignore`) and `hadoop.tmp.dir`. Those paths sit in hadoop-engineer's code, where they can be unit-tested, rather than in build code, which has no tests.
- **Test classpath.** I'll use `hadoop/Test/fullClasspath`, so the runner stays out of the EMR assembly jar. One correction to HD's reasoning: `Compile/fullClasspath` also contains the `Provided` Hadoop jars. Only `Runtime/fullClasspath`, which the default `run` uses, leaves them out. The choice is still fine.
- **Allowlist rows.** I agree the `<SBT:hadoop>` slot can't match these commands, so they need dedicated rows. The shapes are unchanged: `sbt localPipelineDev` and `sbt localPipelineHeldout`.

### 2. Why a bounded task instead of HD's alias

A `Test/runMain` alias has no wall-clock limit. If the run hangs or is slow, it runs into the ~180 s agent tool limit with no clean failure. The build task adds checks the evaluated program doesn't need to own:
- a 120 s hard limit;
- the manifest must be committed and byte-identical to `HEAD`;
- the sample tree is size-checked (64 MiB / 10,000 files) and refused if it contains symlinks;
- `git_head` is logged;
- JVM temp and crash files go under `target/`;
- the working directory is the repository root;
- AWS variables are blanked, the same way as in tests;
- the program's exit code appears in the error message.

The allowlist command is identical either way, so you can choose the alias variant (section 4) without any constitutional change.

### 3. Revised diff, pipeline part only (replaces the pipeline parts of my main report)

```diff
--- build.sbt (relative to my main draft)
-lazy val localPipelineDev =
-  taskKey[File]("Local bounded Hadoop pipeline over the dev split (modules/hadoop/local-runs/dev/)")
-lazy val localPipelineHeldout =
-  taskKey[File]("Local bounded Hadoop pipeline over the held-out split (modules/hadoop/local-runs/heldout/)")
+lazy val localPipelineDev =
+  taskKey[Unit]("Local bounded Hadoop pipeline, dev split (ErSplitPipelineMain dev; see project/LocalRuns.scala)")
+lazy val localPipelineHeldout =
+  taskKey[Unit]("Local bounded Hadoop pipeline, held-out split (ErSplitPipelineMain heldout)")
 ...
 def pipelineRun(split: LocalRuns.Split) = Def.task {
   val base = (ThisBuild / baseDirectory).value
   LocalRuns.localPipeline(
     split,
     base,
-    (hadoop / Compile / fullClasspath).value.map(_.data),
+    // Test classpath: ErSplitPipelineMain lives in the hadoop test tree (HD-P5-01); it also
+    // carries the Provided Hadoop jars. Keeps the runner out of the EMR assembly.
+    (hadoop / Test / fullClasspath).value.map(_.data),
     java17Opens ++ quietLogging(base),
     awsIsolationEnv(target.value / "no-aws"),
     streams.value.log
   )
 }
--- project/LocalRuns.scala (relative to my main draft)
-  def descriptorDir(s: Split): String = s"modules/hadoop/local-runs/${s.name}"
+  // HD-P5-01: committed per-split run manifest read by ErSplitPipelineMain.
+  def pipelineManifest(s: Split): String =
+    s"modules/hadoop/local-runs/er-candidates.${s.name}.run-manifest.json"
+  val PipelineRunsRoot = "modules/hadoop/local-runs"
+  val ErSplitMain = "scholarchain.hadoop.local.ErSplitPipelineMain"
-  val PipelineMain = "scholarchain.hadoop.pipeline.PipelineDriver"
-  val ManifestFile = "run-manifest.json"
-  val DriverArgsFile = "driver-args.tsv"
-  private val InputFlags ... (removed)
-  private val StagesPattern ... (removed)
-  final case class Descriptor ... (removed)
-  def parseDescriptor ... (removed)
-  def localPipeline(...): File = { ...descriptor-based body... }
+  /**
+   * Local bounded pipeline for one split: runs `ErSplitPipelineMain <split>` (hadoop-engineer),
+   * which fixes its own input root, output root, and hadoop.tmp.dir. The build adds the envelope:
+   * committed manifest, bounded sample tree, repository-root working directory, AWS isolation,
+   * scratch under target/, and the wall-clock bound.
+   */
+  def localPipeline(
+      split: Split,
+      base0: File,
+      classpath: Seq[File],
+      jvmOptions: Seq[String],
+      isolation: Map[String, String],
+      log: Logger
+  ): Unit = {
+    val task = s"localPipeline${split.title}"
+    val base = base0.getCanonicalFile
+    committedFile(task, base, pipelineManifest(split), PipelineRunsRoot)
+    val samples = inside(task, base, samplesRoot(split), samplesRoot(split))
+    if (!samples.isDirectory) refuse(task, s"${samplesRoot(split)}/ is missing")
+    val (bytes, files) = measure(task, samples)
+    if (files == 0) refuse(task, s"${samplesRoot(split)}/ contains no regular file")
+    if (bytes > MaxInputBytes || files > MaxInputFiles)
+      refuse(task, s"samples total $bytes bytes in $files files; bound $MaxInputBytes / $MaxInputFiles")
+    log.info(s"$task: samples $files files, $bytes bytes under ${samplesRoot(split)}/")
+    val scratch = scratchOptions(base / outputDir(split) / "pipeline-jvm")
+    runForked(task, base, classpath, jvmOptions ++ scratch, isolation, ErSplitMain,
+      Seq(split.name), PipelineTimeoutSeconds, log) match {
+      case 0 =>
+        log.info(s"$task: completed; outputs under " +
+          s"modules/hadoop/target/local-pipeline/er-candidates/${split.name}/curated/")
+      case n => throw new MessageOnlyException(s"$task: ErSplitPipelineMain exited $n")
+    }
+  }
```

The build no longer deletes the pipeline output, because that directory now belongs to the main class. After a timeout, a stage directory can be left without `_SUCCESS`, and the pipeline then refuses to overwrite it. The main class should either delete or refuse a stale run directory, or the remedy is `sbt clean check` (already allowlisted for every identity).

### 4. Alternative: HD's alias, if you prefer it

If you accept running without a time limit, use this instead of the task (same allowlist rows):
```scala
// hadoop project settings:
Test / run / baseDirectory := (ThisBuild / baseDirectory).value,
// aliases (instead of the localPipelineDev/Heldout task keys):
addCommandAlias("localPipelineDev", "hadoop/Test/runMain scholarchain.hadoop.local.ErSplitPipelineMain dev")
addCommandAlias("localPipelineHeldout", "hadoop/Test/runMain scholarchain.hadoop.local.ErSplitPipelineMain heldout")
```

Answers to HD's settings questions, from my understanding of sbt scope delegation (not observed):
- **Fork and test settings.** `hadoop / Test / run / fork`, `/ javaOptions` and `/ envVars` fall back to `hadoop / Test / fork`, `Test / javaOptions` and `Test / envVars`. So the forked `Test/runMain` would get `fork := true`, `java17Opens` and the AWS isolation.
- **Working directory.** `Test / run / baseDirectory` falls back to the project directory (`modules/hadoop`), so it must be set explicitly, as shown above. That only affects `run`/`runMain`. Test working directories are unchanged.
- **Exit codes under sbt 1.11.7.** A non-zero exit gives "Nonzero exit code returned from runner: N" and sbt exits with status 1. `sys.exit` ends only the forked JVM.

### 5. Owner follow-ups (not made by me)

**hadoop-engineer:**
- create `scholarchain.hadoop.local.ErSplitPipelineMain` in the hadoop test tree;
- accept only `dev` or `heldout`;
- print the distinct candidate-pair count;
- handle a stale or partial run directory;
- commit `modules/hadoop/local-runs/er-candidates.{dev,heldout}.run-manifest.json`;
- recommended: put the split in the manifest so the two splits get different run IDs.

**factory-engineer (FE-P5-02):**
- rows `hadoop-engineer	active	allow	-	sbt localPipelineDev` and `… sbt localPipelineHeldout` (they replace the inactive `sbt localPipeline` row);
- the `goldSetEval` row as in addendum 1;
- 120 rows in total.

```text
STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: BE-P5-01 addendum 2 (HD-P5-01 inputs)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (no Bash calls)
TEST RESULTS: not applicable (nothing applied; no sbt run)
VALIDATION RESULTS: not executed; the revised diff is uncompiled. The scope-delegation and exit-code answers come from my understanding of sbt 1.11.7, not from observation.
ASSUMPTIONS: ErSplitPipelineMain lives in the hadoop test tree, takes exactly one argument (dev|heldout), fixes its own paths as HD-P5-01 states, and exits 0 on success. hadoop/Test/fullClasspath contains the Provided Hadoop jars and the test classes.
RISKS: The 120 s limit may be too short for a full per-split run; a timeout fails closed and the limit is one constant. The build no longer deletes pipeline outputs, so stale-run handling depends on the main class. The runner is test code that sbt runs and that writes outside target/ only by its own choice; this is detected after the fact by the audit, and code-reviewer must review the main class.
MANUAL ACTION REQUIRED: Human: choose the bounded task (recommended) or HD's alias. Both use the same allowlist rows.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: human review with FE-P5-02, HD-P5-01 and ER-P5-14; apply work order (sbt check, code-reviewer); ErSplitPipelineMain and manifests by hadoop-engineer; constitutional rows applied with no session open; validator and --probes PASS; commit; fresh session.
```
