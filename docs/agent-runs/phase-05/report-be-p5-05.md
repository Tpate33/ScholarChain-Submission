# BE-P5-05 final report (build-engineer; verbatim)

Archived by the orchestrator, 2026-11-14. Work order: fix CR-P5-05 BE-1 and BE-2 (decisions-20).

---

BE-1 and BE-2 are fixed in `project/LocalRuns.scala` and `build.sbt` only, and `sbt check` passed in the foreground: 921 tests passed, 0 failed, 76 suites, and the synthetic log check passed. Both held-out tasks now refuse every bypass route the review named. One consequence needs your decision: hadoop-engineer's ratified `ask` row for `sbt localPipelineHeldout` can no longer produce a run from an agent session (see the proposed amendment-text changes).

**BE-1: clean-tree refusal (both held-out tasks)**
- **(a) Index flags:** `git ls-files -v -z` over the scope must list only `H` entries. `S` (skip-worktree), lower-case tags (assume-unchanged) and `M` (unmerged) are refused.
- **(b) Ignored files:** one call, `git status --porcelain=v1 -z --ignored=matching --untracked-files=all -- <scope>`. Any change or untracked path is refused, and so is any ignored path that is not on the allow set. This catches ignores from nested `.gitignore` files, `.git/info/exclude` and `core.excludesFile`.
- **(c) Root `.gitignore`:** it must be committed and unmodified in both tasks. The check moved into the shared clean-tree check, so `goldSetEvalHeldout` no longer does it separately.
- **Closing other bypasses:** every inherited `GIT_*` variable is removed from the build's git processes, so `GIT_INDEX_FILE` or `GIT_DIR` cannot redirect the checks. The fsmonitor hook and the untracked cache are turned off for these calls.
- **Failures:** every git call has a 30 s bound. Any error, non-zero exit or timeout refuses.

**Allow set** (exact strings as git prints them; no patterns or prefixes; built by `heldoutIgnoredAllowSet`):
- **`<base>/target/` of every sbt project in the scope** (taken from the build's own `target` settings), plus `project/target/` and `project/project/target/`. sbt writes these on every load, so no run could pass without them.
- **`datasets/downloads/` as one directory entry.** This is the human-confirmed location for full downloads (role law 8), and the full DBLP file is there now. It is not an sbt source, resource or classpath directory, and the pipeline reads only the committed samples and ledgers. This is my decision; you can veto it.
- **`gold/entity-resolution/heldout-v1/er-gold-labels.jsonl`, for `goldSetEvalHeldout` only.** `localPipelineHeldout` refuses while that file exists.
- **Refused on purpose:** `.DS_Store`, `project/metals.sbt` and `project/project/metals.sbt` (both are build code), and `project/.bloop/`. The root `.bsp`, `.metals` and `.idea` directories are outside the scope.
- **Real repository today (read-only report inside check):** 443 tracked files, 0 with index flags. The ignored paths it would refuse are `project/.DS_Store` and `project/project/.DS_Store`.

**BE-2: launch refusal**
Both held-out tasks call this check first. `launchProblems` is a pure function; `observeLaunch` reads the real values, and the tasks refuse when any signal fails:
1. sbt launch arguments include `--server`, `--detach-stdio`, `--client`, `-client`, `--jvm-client`, `-bsp`, `--bsp` or `shell`.
2. The command channel is anything except none. Batch commands have no channel: I observed `command channel=none` in batch `sbt check`. `console0` (the interactive shell) and `network-N` are refused.
3. `shell` is queued in the remaining commands. I observed `[markTestRun]` in batch.
4. The console check:
   - JDK 22 and later: `Console.isTerminal()` (called reflectively) must be true.
   - Earlier JDKs: `System.console()` must be non-null, and the `jdk.console` property must be unset.
   - JDK 22 or later without that method is refused.
5. `/dev/fd/1` and `/dev/fd/2` must be character devices other than `/dev/null`, read through the `unix:mode` and `unix:rdev` file attributes.

The task descriptions in `build.sbt` now state this requirement. `heldoutRouteProbe` and `verifyHeldoutOutputRoute` do not call this check and are unchanged.

**What I verified and what I could not**
- **JDK:** sbt prints `Eclipse Adoptium Java 17.0.17` (feature 17).
- **Observed in the agent's batch run:** `System.console()=null`, `fd1=FdRegularFile`, `fd2=FdRegularFile`, arguments `[check]`, channel none. So the real verdict here is REFUSE, which is correct. The file-type read works on this macOS.
- **Not verifiable from inside a session:**
  - the allow case on a real Terminal (console present and both descriptors character devices);
  - the channel names for the interactive shell and network clients (these come from my knowledge of sbt; only "none" is allowed, so any other name refuses anyway);
  - where a server started by a thin client or IDE sends its stdout (it is refused either way);
  - a JDK 22 or later run.

**New task `verifyHeldoutGuards`** runs in `check` just before `markTestRun`. It fails `check` only when a synthetic case gives the wrong verdict; the real launch verdict and the real repository state are reported, not enforced.

**PROPOSED AMENDMENT-TEXT CHANGES (for separate human review)**
1. FE-P5-06 gives hadoop-engineer `active ask` for `sbt localPipelineHeldout`. Under BE-2 that task refuses inside any agent Bash call, because there is no terminal (observed). The row now only lets an agent start a run that will refuse. Choose one:
   - mark the row inactive and say the held-out pipeline is run by the human in a Terminal;
   - or decide that agent-run held-out pipelines are wanted. That would mean relaxing BE-2 for this task, which I have not done.
   The guard probes still pass as written.
2. Wherever the amendment or `docs/agents.md` describes D3(b) as "`git status --porcelain --untracked-files=all -- <scope>` is empty", it should describe the stricter check above: index flags, the ignored allow set, and the committed `.gitignore` for both tasks.
3. Gaps I did not close, because closing them means changing the D3(b) scope:
   - root files the sbt launcher reads (`.jvmopts`, `.sbtopts`) are outside the scope;
   - the global `~/.sbt` settings are outside the repository.
4. Please confirm or reject two allow-set decisions: `datasets/downloads/` allowed, and `.DS_Store` refused.

```text
STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: BE-P5-05 (Phase 5; decisions-20): fix CR-P5-05 BE-1 (clean-tree refusal bypasses) and BE-2 (held-out tasks only under plain batch sbt on an interactive terminal), editing only project/LocalRuns.scala and build.sbt; sbt check in the foreground, including the synthetic log check.
FILES CREATED: none
FILES MODIFIED: /Users/taufeeqpatel/Desktop/ScholarChain/project/LocalRuns.scala (untracked file from BE-P5-04, edited in place); /Users/taufeeqpatel/Desktop/ScholarChain/build.sbt
COMMANDS RUN:
- git ls-files --others -- project tests datasets: OK (173 KB of output; shows the ignored datasets/downloads/dblp/full/* and project/.DS_Store files)
- git ls-files --others -- datasets: OK (only the 2 files under datasets/downloads/)
- git ls-files -- project: OK
- git ls-files --others --exclude-standard -- modules/hadoop/src/test/scala/scholarchain/hadoop/local modules/hadoop/src/main/scala/scholarchain/hadoop: OK
- sbt fmt: [success]; meta-build compiled
- git status --short: OK; unchanged from the start of the session apart from my two files
- sbt Test/compile: [success] (twice)
- sbt check (foreground, run 1): passed; 22 launch cases, 17 repository cases. I then restricted the allowed channel to none, added 1 case and fixed a rename-parse detail.
- sbt check (foreground, run 2, the one reported): [success] Total time 133 s; output saved to /Users/taufeeqpatel/.claude/projects/-Users-taufeeqpatel-Desktop-ScholarChain/107a6780-f081-4d82-af1b-11c6ff34610e/tool-results/bcpklyp3m.txt
- git diff --stat -- build.sbt project .gitignore: OK (the .gitignore diff is from BE-P5-04)
- No hook reported an UNATTRIBUTED change.
TEST RESULTS: sbt check run 2: 921 passed, 0 failed, 0 errors, 0 skipped, 76 suites. Per project: core 299, entityResolution 393, hadoop 84, tests 145; rag, root, services, sparkAnalytics, storage 0 (no tests). Marker line: "[info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=36ffcf95e585c7def6aa06835a784820936e1b60, worktree=dirty, suites=76, passed=921, skipped=0)"
VALIDATION RESULTS:
- verifyBuildSources OK; Version ledger OK (14 entries, 2 build files, no literal versions); scalafmtSbtCheck and scalafmtCheckAll pass; Test/compile success.
- Synthetic log check, verbatim from run 2:
  "[info] verifyHeldoutOutputRoute: scanned 11 sbt target directories (modules/core/target, modules/entity-resolution/target, modules/hadoop/target, modules/rag/target, modules/services/target, modules/spark/target, modules/storage/target, project/project/target, project/target, target, tests/target): 4396 files, 102409315 bytes; 1 symbolic links not followed; 1 excluded data/scratch directories not read (modules/hadoop/target/local-pipeline, target/local-runs/heldout)"
  "[info] verifyHeldoutOutputRoute: counter-test PASS: control marker (sbt task log route) found in target/streams/_global/heldoutRouteProbe/_global/streams/out, target/global-logging/sbt-global-log2471205770697244716.log"
  "[info] verifyHeldoutOutputRoute: PASS: held-out-route marker sha256=2bb058a185d72ff374f28bbff5a9ed8b37fa10ff7c4076a1af9437da572c85f2 found in 0 of 4396 sbt-persisted files; it was printed by the child straight to the terminal"
  Summary: 4396 files scanned; 0 held-out marker hits; control marker in the two files above; PASS.
- verifyHeldoutGuards: "PASS: 23 synthetic launch cases and 17 temporary-repository clean-tree cases gave the expected verdict".
  - Launch cases: ALLOW for batch on JDK 17 and JDK 25 with terminals. REFUSE for: channel network-1 or network-2; console0 with or without shell queued; --server; --detach-stdio --server; -bsp; shell queued; JDK 25 isTerminal() false or unavailable; JDK 25 without the method; JDK 17 console null; jdk.console set; stdout to a pipe, file, /dev/null, socket or unknown; stderr to a file or pipe.
  - Repository cases, in a temporary repository in the system temp dir, deleted afterwards: ALLOW for the controls and for the labels file with the evaluation allow set. REFUSE for: labels with the pipeline allow set; an untracked file; a modified file; skip-worktree plus edit; assume-unchanged plus edit; core-site.xml hidden by info/exclude, by core.excludesFile, and by a nested '*' .gitignore; a modified root .gitignore (both tasks); git not running; git exit 128; git timeout. For the two skip-worktree/assume-unchanged cases and the three hidden core-site.xml cases, the log also shows the BE-P5-04 query listing nothing, so each was a real bypass of the old check.
  - Observed real signals: "java=17.0.17 (Eclipse Adoptium) feature=17; System.console()=null; Console.isTerminal() method=absent; jdk.console=-; fd1=FdRegularFile; fd2=FdRegularFile; sbt arguments=[check]; command channel=none; remaining commands=[markTestRun]"; real verdict REFUSE (reported only).
ASSUMPTIONS:
- The channel names for the interactive shell (console0) and for network clients (network-N), and the sbt launch arguments, come from my knowledge of sbt 1.x. Only the batch values (channel none, no shell queued) were observed; any channel other than none refuses.
- On a real Terminal, macOS reports /dev/fd/1 and /dev/fd/2 as character devices. The attribute read itself was observed to work.
- The held-out pipeline does not read datasets/downloads/ (from ErSplit's fixed inputs).
- Git 2.16 or later is installed (needed for --ignored=matching; an older git makes the tasks refuse).
RISKS:
1. The allow case on a real Terminal is not observed. If a signal behaves differently there, the held-out tasks refuse (fail closed) until build-engineer adjusts them.
2. Today's tree has project/.DS_Store and project/project/.DS_Store, so the human must delete them before a held-out run, and Finder can recreate them.
3. Files tampered with inside the allowed target/ directories are not checked. Whether Zinc would reuse a tampered class file is the reviewer's open, unverified concern.
4. Stdin must also be a terminal (System.console() requires it before JDK 22), so `sbt <task> < file` refuses.
5. Root launcher files (.jvmopts, .sbtopts) and ~/.sbt are outside the D3(b) scope.
6. The GIT_* removal and the git time bound also apply to the dev tasks' git checks. Behaviour there is the same unless the human uses GIT_* variables.
7. hadoop-engineer's ask row for localPipelineHeldout can no longer produce a run (see the proposed changes).
8. check takes about 1 s longer.
MANUAL ACTION REQUIRED: Human: decide the proposed amendment-text changes 1 to 4 (in the report above). After the commit, run `sbt localPipelineHeldout` once in a Terminal to see the launch and clean-tree lines (it will refuse on a dirty tree, which is expected). code-reviewer: CR-P5-06 focused re-review of BE-1 and BE-2 in project/LocalRuns.scala (heldoutIgnoredAllowSet, readTreeState, heldoutTreeCheck, gitProcess, launchProblems, observeLaunch, guardSelfTest) and the build.sbt diff.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: HD-P5-05 (hadoop-engineer, HD-1), then CR-P5-06 focused re-review of BE-1, BE-2 and HD-1; the human decides the proposed amendment-text changes; no held-out run until approved.
```
