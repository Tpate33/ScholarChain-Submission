# CR-P6-34: review of BE-P6-09 repair 02 (code-reviewer final report, verbatim)

```
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-P6-34 (Phase 6, read-only). Review of the uncommitted change to /Users/taufeeqpatel/Desktop/ScholarChain/project/SparkSubmitLocal.scala made in BE-P6-09 repair 02, against HEAD 103d05dbc8f6f71ff21f2c84665b8a5f299b2929.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none. I used only Read and Grep, on these files:
- /Users/taufeeqpatel/Desktop/ScholarChain/docs/agent-runs/phase-06/be-p6-09-repair-02.md
- /Users/taufeeqpatel/Desktop/ScholarChain/docs/agent-runs/phase-06/be-p6-10-report.md
- /Users/taufeeqpatel/Desktop/ScholarChain/docs/agent-runs/phase-06/human-tar-modes-2026-11-18.md
- /Users/taufeeqpatel/Desktop/ScholarChain/project/SparkSubmitLocal.scala (lines 382-383, 440-550, 619-780, 1440-1470, 1620-1747, 1915-2130, 2320-2340)
- /Users/taufeeqpatel/Desktop/ScholarChain/target/spark-submit-local/attempt-1/dist/spark-3.5.5-bin-hadoop3-scala2.13/bin/spark-class
TEST RESULTS: not run (I have no Bash tool). I checked the reported counts against the code, see check 4.
VALIDATION RESULTS:
1. Diff scope: verified.
   - The git status snapshot shows ` M project/SparkSubmitLocal.scala` as the only modified tracked file. Everything else changed is an untracked orchestrator file under docs/agent-runs/phase-06/.
   - The report's diff has two hunks. The first is `-728,14 +728,33` (23 lines added, 4 removed). The second is `-2003,6 +2022,108` (102 lines added, all inside the self-test). That adds up to the reported 125 insertions and 4 deletions. The second hunk's +19 line offset fits the first hunk's net growth of 19 lines.
   - The working-tree file matches both hunks at lines 729-757 and 2025-2125.
   - Unchanged code that I read: the tar reader mode rule at lines 509-510 (owner execute bit gives Exec rwxr-xr-x, otherwise Plain rw-r--r--, defined at lines 382-383), the refusal of link entries at lines 514-518, the sha512 cache and download in `ensureDistribution` (lines 619-727), the jars/ ledger checks (lines 758-779) and the caller (lines 1453-1458).
   - The call site has not changed. Any non-empty `distributionProblems` result calls `fail(...)` before runtime-jars/, job/ or any run is created.
   - `bin/spark-submit` and `bin/spark-class` (`ExecutableScripts`) still have to be non-symlink regular files with the execute bit.
   - I did not open the hunks for env stripping, create-once attempts or the code-scope check. The hunk headers and insertion counts leave no room for other changes there.
   - Grep finds no remaining user of the old message "not an executable regular file" in project/.
2. Check order: correct.
   - `Files.isSymbolicLink(f)` is tested first, so a symlink cannot reach the regular-file, executable or readable tests.
   - `exists` and `isRegularFile` both use NOFOLLOW_LINKS. `isExecutable` and `isReadable` follow links, but only after symlinks have been excluded.
   - The chain is if/else-if, so each script reports at most one problem. Every path ends in either a reported problem or a pass, so nothing is silently skipped: symlink, then missing, then not regular, then (for an executable script) not executable, or (for a sourced script) not readable.
   - bin/spark-class:24 is `. "${SPARK_HOME}"/bin/load-spark-env.sh`. That is the file's only reference to load-spark-env.sh, so it is sourced, never executed. The human's tar listing (load-spark-env.sh 0644, spark-submit and spark-class 0755) shows the old rule was too strict and that the tar reader copied the upstream modes faithfully.
3. Self-test: valid.
   - Case (a) builds a 0644 load-spark-env.sh with the build's own `untar`. The old rule `!isRegularFile || !isExecutable` would have reported a problem for it, so `ps.isEmpty` would have been false and the case would have failed. This follows from reading the code; I did not observe it.
   - Each refusal case requires `ps.size == 1 && ps.head.contains(sub)`. The 5 jar names are built from the real pins and match each `one(prefix, ...)` check, so a broken fixture would show up as an extra problem.
   - The directory case uses a `'5'` entry with a trailing slash, which the reader already supports (it uses `$top/` the same way).
   - The symlinks are relative (`spark-class`, inside the same bin/), so they resolve inside the temporary tree. `deleteTree` uses `Files.walk`, which does not follow links, and `deleteIfExists`, which removes the link and not its target. Nothing escapes the temporary directory.
   - The temporary root is `Files.createTempDirectory("scholarchain-sparksubmit-selftest-")`, removed in `finally deleteTree(tmp)` at line 2336.
   - The `-w-------` file sits in a 0755 directory, so it can still be deleted.
   - The block runs inside `pins.foreach`. If the pins were a Left, the 10 cases would be skipped, but line 1740 already fails the self-test for a Left. So this does not fail open.
4. Evidence: the report is honest and consistent with the code.
   - 621 + 464 + 85 + 255 + 145 = 1570.
   - The new block makes exactly 10 `layout` calls, one `expect` each, so 99 + 10 = 109 matches.
   - The report says openly that case (a) was not run against the old code, that `sbt check` was moved to the background, that the marker was written after the last audited Bash call with worktree=dirty, and that it returned BLOCKED-ON human. It does not overclaim.
ASSUMPTIONS:
- The diff in be-p6-09-repair-02.md is the complete working-tree diff. I cannot run git, but the hunk line counts, the stat and the file content all agree with it.
- The extracted distribution under attempt-1 is the sha512-verified pinned one, as BE-P6-10 reported.
RISKS:
- Whether the fixed check passes on the real extracted distribution is seen only when `sbt sparkSubmitLocal` next runs (attempt-2). Later stages may still refuse.
- The test marker is not commit evidence yet. It needs the audited `sbt check` rerun that the report itself names.
MANUAL ACTION REQUIRED: none from this review. The marker review, baseline reset and audited `sbt check` rerun listed in the BE-P6-09 repair 02 report are still pending before the commit.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human marker review and audited `sbt check` rerun, then the human commit, then the BE-P6-10 rerun of `sbt sparkSubmitLocal` (attempt-2).
VERDICT: APPROVE
FINDINGS:
build-engineer:
- nit, /Users/taufeeqpatel/Desktop/ScholarChain/project/SparkSubmitLocal.scala:2090-2099. Failure input: `sbt check` run as root (or with CAP_DAC_OVERRIDE). Root can read a `-w-------` file, so `Files.isReadable` returns true, the "unreadable load-spark-env.sh" case gets an empty problem list and the self-test fails. Impact: `sbt check` fails closed under root; it never passes falsely. The report's RISKS already discloses this. Optional fix: skip the case when the process can read the file anyway, with a note in the output.
- nit, /Users/taufeeqpatel/Desktop/ScholarChain/project/SparkSubmitLocal.scala:751-752. Failure input: an executable script with mode `--x------`. It passes the gate, but bash cannot read it to run it. Impact: none in practice, because the tar reader only ever produces rwxr-xr-x or rw-r--r--. Noted for completeness.
UNVERIFIED CONCERNS:
- Deferred, not introduced by this change: `Files.isSymbolicLink` and NOFOLLOW_LINKS apply only to the last path component, so a symlinked `bin/` would not be caught here. The tar reader refuses link entries (lines 514-518), so extraction cannot create one. The old code behaved the same way.
- I did not observe the self-test failing against the HEAD code (case (a)). That conclusion comes from reading the code only.
```

Orchestrator: both nits deferred (non-blocking; fail closed). Next: human marker review and baseline reset, human Terminal `sbt check`, human commit, then the BE-P6-10 rerun.
