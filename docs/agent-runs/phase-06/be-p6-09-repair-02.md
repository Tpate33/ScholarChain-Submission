# BE-P6-09 repair 02: sparkSubmitLocal layout check (build-engineer final report, verbatim)

Findings routed: BE-P6-10 refusal `bin/load-spark-env.sh is not an executable regular file` (be-p6-10-report.md), confirmed by the human's tar listing (human-tar-modes-2026-11-18.md: load-spark-env.sh 0644 upstream). Owner: build-engineer.

```
BE-P6-09 repair 02 is done and `sbt check` passed (exit 0, 1570 tests, 0 failed), but I have to stop with BLOCKED-ON human. `sbt check` ran past 180 s and was moved to the background, so `markTestRun` updated `.claude/.last-test-run` after the last audited Bash call. The Stop hook then reported possible marker forgery and told me to stop.

STATUS: BLOCKED-ON
OWNER: build-engineer
WORK ORDER: BE-P6-09 repair 02 (Phase 6). Narrow repair of the `sparkSubmitLocal` distribution layout check: `bin/load-spark-env.sh` must be a readable regular file with no execute bit required. `bin/spark-submit` and `bin/spark-class` must still be executable regular files. Self-test coverage added, `sbt check` run, `sbt sparkSubmitLocal` not run.

FILES CREATED: none

FILES MODIFIED:
- /Users/taufeeqpatel/Desktop/ScholarChain/project/SparkSubmitLocal.scala (125 insertions, 4 deletions)

COMMANDS RUN:
1. `sbt Test/compile`: success (meta-build recompiled 1 Scala source; "[success] Total time: 2 s").
2. `sbt check`: moved to the background after 180 s, completed with exit code 0 ("[success] Total time: 315 s (0:05:15.0), completed Nov 18, 2026, 8:34:40 PM").
3. `git status --short` and `git diff --stat` / `git diff --no-ext-diff -- project/SparkSubmitLocal.scala`: read-only.
- Not run: `sbt sparkSubmitLocal`, `sbt clean`, any AWS call. `target/spark-submit-local/attempt-1` and the cached distribution in `target/spark-dist/` are untouched.

TEST RESULTS (copied from the `sbt check` output):
- core: Passed 621, Failed 0, Errors 0
- entityResolution: Passed 464, Failed 0, Errors 0
- hadoop: Passed 85, Failed 0, Errors 0
- sparkAnalytics: Passed 255, Failed 0, Errors 0
- tests: Passed 145, Failed 0, Errors 0
- rag, root, services, storage: 0 tests
- Total 1570 passed, 0 failed, 0 errors, 0 skipped. The work order expected 1570 plus new cases. The new cases are in the build self-test, which is not counted in this test total.
- `markTestRun` line: "wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=103d05dbc8f6f71ff21f2c84665b8a5f299b2929, worktree=dirty, suites=112, passed=1570, skipped=0)"

VALIDATION RESULTS:
- `verifySparkSubmitLocalGuards: PASS: 109 cases gave the expected verdict (temporary directories only; no network, no fork)`. That is 99 before plus 10 new cases.
- The other check steps also passed: build sources OK, version ledger OK (24 pinned entries), scalafmt check passed, verifyHeldoutOutputRoute PASS, verifyHeldoutGuards PASS (23 launch cases and 17 clean-tree cases), verifyErDevRunGuards PASS (128 cases), verifySparkItGuards PASS (204 cases), verifySparkItRunner PASS (8 cases).
- The 10 new self-test cases build trees with the build's own tar reader, using the pinned tarball modes. A refusal case passes only if it is the single problem reported, so a failed extraction cannot pass by accident:
  - (a) non-executable regular `load-spark-env.sh` (0644) is accepted.
  - executable `load-spark-env.sh` (0755) is accepted.
  - (b) missing `load-spark-env.sh` is refused: "bin/load-spark-env.sh is missing".
  - (c) symlink to a regular file is refused: "is a symbolic link".
  - (c) a directory entry is refused: "is not a regular file".
  - unreadable file (mode `-w-------`) is refused: "is not readable".
  - (d) non-executable `spark-submit` is refused: "bin/spark-submit is not executable".
  - (e) non-executable `spark-class` is refused: "bin/spark-class is not executable".
  - missing `spark-submit` is refused.
  - `spark-submit` as a symlink to an executable is refused.
- Case (a) failing without the change follows from the code but was not observed. The old rule `!isRegularFile || !isExecutable` reports "bin/load-spark-env.sh is not an executable regular file" for a 0644 file, so `ps.isEmpty` would be false. I did not run the self-test against the old code.
- Unchanged: the tar reader, the sha512 and cache logic, the `jars/` ledger checks, the create-once attempt logic and the environment stripping.

Complete diff:
diff --git a/project/SparkSubmitLocal.scala b/project/SparkSubmitLocal.scala
index 20beb6a..70f5a55 100644
--- a/project/SparkSubmitLocal.scala
+++ b/project/SparkSubmitLocal.scala
@@ -728,14 +728,33 @@ object SparkSubmitLocal {
 
   // --- Distribution layout ------------------------------------------------------------------
 
-  /** The extracted `<top>/`: spark-submit executable; jars/ matching the ledger. */
+  /** Launcher scripts the runs execute: each an executable regular file (no symbolic link). */
+  val ExecutableScripts: Seq[String] = Seq("bin/spark-submit", "bin/spark-class")
+
+  /**
+   * Sourced, never executed (`bin/spark-class` runs `. "${SPARK_HOME}"/bin/load-spark-env.sh`): a
+   * readable regular file (no symbolic link); no execute bit (the pinned tarball ships it 0644).
+   */
+  val SourcedScripts: Seq[String] = Seq("bin/load-spark-env.sh")
+
+  /**
+   * The extracted `<top>/`: spark-submit and spark-class executable, load-spark-env.sh readable;
+   * jars/ matching the ledger.
+   */
   def distributionProblems(home: Path, pins: Pins): Seq[String] = {
     val p = ListBuffer.empty[String]
-    Seq("bin/spark-submit", "bin/spark-class", "bin/load-spark-env.sh").foreach { r =>
+    def script(r: String, executable: Boolean): Unit = {
       val f = home.resolve(r)
-      if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) || !Files.isExecutable(f))
-        p += s"$r is not an executable regular file"
+      if (Files.isSymbolicLink(f)) p += s"$r is a symbolic link"
+      else if (!Files.exists(f, LinkOption.NOFOLLOW_LINKS)) p += s"$r is missing"
+      else if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) p += s"$r is not a regular file"
+      else if (executable) {
+        if (!Files.isExecutable(f)) p += s"$r is not executable"
+      } else if (!Files.isReadable(f))
+        p += s"$r is not readable (it is sourced by bin/spark-class, never executed)"
     }
+    ExecutableScripts.foreach(script(_, executable = true))
+    SourcedScripts.foreach(script(_, executable = false))
     val jars = home.resolve("jars")
     val names =
       if (!Files.isDirectory(jars, LinkOption.NOFOLLOW_LINKS)) {
@@ -2003,6 +2022,108 @@ object SparkSubmitLocal {
         "larger than"
       )
 
+      // Distribution layout (BE-P6-09 repair 02): trees extracted by the build's own tar reader,
+      // with the pinned tarball's modes (load-spark-env.sh 0644, spark-submit and spark-class 0755).
+      // Each refusal must be the only problem, so a failed extraction cannot pass a refusal case.
+      pins.foreach { p =>
+        val jarNames = Seq(
+          s"scala-library-${p.distScalaLibrary}.jar",
+          s"hadoop-client-api-${p.distHadoopClient}.jar",
+          s"hadoop-client-runtime-${p.distHadoopClient}.jar",
+          s"spark-core_2.13-${p.spark}.jar",
+          s"spark-sql_2.13-${p.spark}.jar"
+        )
+        val pinnedModes =
+          Map("spark-submit" -> 493, "spark-class" -> 493, "load-spark-env.sh" -> 420)
+        def dist(bin: Map[String, Int], extra: Seq[TestEntry] = Nil): Path = {
+          val es = Seq(TestEntry(s"$top/", '5', Array.emptyByteArray, 493)) ++
+            bin.toSeq.sorted.map { case (n, m) => TestEntry(s"$top/bin/$n", '0', f1, m) } ++
+            jarNames.map(j => TestEntry(s"$top/jars/$j", '0', f1, 420)) ++ extra
+          extract(tarBytes(es))._1.resolve(top)
+        }
+        def layout(name: String, home: Path, refusal: Option[String]): Unit = {
+          val ps = distributionProblems(home, p)
+          refusal match {
+            case None => expect(name, ps.isEmpty, ps.mkString("; "))
+            case Some(sub) =>
+              expect(
+                s"$name (refused)",
+                ps.size == 1 && ps.head.contains(sub),
+                if (ps.isEmpty) "ALLOW" else ps.mkString("; ")
+              )
+          }
+        }
+        val env = "load-spark-env.sh"
+        layout(
+          "pinned tarball modes: non-executable regular load-spark-env.sh accepted",
+          dist(pinnedModes),
+          None
+        )
+        layout(
+          "executable load-spark-env.sh accepted",
+          dist(pinnedModes.updated(env, 493)),
+          None
+        )
+        layout(
+          "missing load-spark-env.sh",
+          dist(pinnedModes - env),
+          Some("bin/load-spark-env.sh is missing")
+        )
+        val linked = dist(pinnedModes - env)
+        Files.createSymbolicLink(
+          linked.resolve("bin").resolve(env),
+          new File("spark-class").toPath
+        )
+        layout(
+          "load-spark-env.sh as a symbolic link to a regular file",
+          linked,
+          Some("bin/load-spark-env.sh is a symbolic link")
+        )
+        layout(
+          "load-spark-env.sh as a directory",
+          dist(
+            pinnedModes - env,
+            Seq(TestEntry(s"$top/bin/$env/", '5', Array.emptyByteArray, 493))
+          ),
+          Some("bin/load-spark-env.sh is not a regular file")
+        )
+        val unreadable = dist(pinnedModes)
+        Files.setPosixFilePermissions(
+          unreadable.resolve("bin").resolve(env),
+          PosixFilePermissions.fromString("-w-------")
+        )
+        layout(
+          "unreadable load-spark-env.sh",
+          unreadable,
+          Some("bin/load-spark-env.sh is not readable")
+        )
+        layout(
+          "non-executable spark-submit",
+          dist(pinnedModes.updated("spark-submit", 420)),
+          Some("bin/spark-submit is not executable")
+        )
+        layout(
+          "non-executable spark-class",
+          dist(pinnedModes.updated("spark-class", 420)),
+          Some("bin/spark-class is not executable")
+        )
+        layout(
+          "missing spark-submit",
+          dist(pinnedModes - "spark-submit"),
+          Some("bin/spark-submit is missing")
+        )
+        val linkedSubmit = dist(pinnedModes - "spark-submit")
+        Files.createSymbolicLink(
+          linkedSubmit.resolve("bin").resolve("spark-submit"),
+          new File("spark-class").toPath
+        )
+        layout(
+          "spark-submit as a symbolic link to an executable",
+          linkedSubmit,
+          Some("bin/spark-submit is a symbolic link")
+        )
+      }
+
       // Distribution cache.
       pins.foreach { p0 =>
         val body = "distribution".getBytes(StandardCharsets.UTF_8)

ASSUMPTIONS:
- The refusal wording for `spark-submit` and `spark-class` changed. One message, "is not an executable regular file", became four precise ones: "is a symbolic link", "is missing", "is not a regular file", "is not executable". What gets refused is the same as before.
- The readability check applies only to `load-spark-env.sh`, so nothing new is required of the two executables.
- The build-engineer Bash allowlist has no line-search command and I have no Grep tool, so I located the observed counts by reading the background output file with Read.

RISKS:
- The unreadable-file case expects `Files.isReadable` to be false for a `-w-------` file. That holds for a non-root user, which is what was observed here. Under root the case would fail.
- The real check of the extracted pinned distribution happens only in the next `sbt sparkSubmitLocal` run (it will create attempt-2). It can still find another mismatch later in the pipeline.
- The marker was written with worktree=dirty against HEAD 103d05d. It is not evidence for the commit the human will make.

MANUAL ACTION REQUIRED:
1. Review the test marker and `git status`. The marker line above was written by `markTestRun` inside the `sbt check` that exited 0, but it landed after the last audited Bash call because sbt ran in the background.
2. Interrupt the turn (Esc) if the Stop hook is still looping. The next audited Bash call will report the change as UNATTRIBUTED and reset the baseline.
3. Rerun `sbt check` in a fresh build-engineer run so the marker is recorded by an audited call. It takes about 5 minutes, which risks the same background move.
4. After code review and your commit, run `sbt sparkSubmitLocal` (mode ask). It refuses until the code scope is committed and identical to HEAD.

BLOCKED-ON OWNER: human

BLOCKED-ON EVIDENCE: Stop hook output, verbatim:
"BLOCKED by .claude/hooks/verify-tests-ran.sh: completion refused for 'build-engineer'. The test marker .claude/.last-test-run changed after the last audited Bash call of this session, without an audited 'sbt check' (for example a delayed background process started by test code). Possible test-marker forgery: the marker cannot be trusted as evidence that tests passed. Do: stop. Return STATUS: BLOCKED-ON with BLOCKED-ON OWNER: human and this message as BLOCKED-ON EVIDENCE. Do not touch the marker. The human reviews it; the next audited Bash call reports the change as UNATTRIBUTED and resets the baseline, and a fresh 'sbt check' re-records the marker."
The cause is the harness moving `sbt check` (task banypyt21) to the background after 180 s. It finished with exit code 0 at 8:34:40 PM, and `markTestRun` wrote the marker after that. I did not touch the marker.

NEXT GATE: Human marker review and baseline reset, then an audited `sbt check` rerun, then code-reviewer review of the diff, then the human commit, then the `sbt sparkSubmitLocal` rerun (attempt-2).
```
