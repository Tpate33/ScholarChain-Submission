# CR-P5-06 focused re-review report (code-reviewer; verbatim)

Archived by the orchestrator, 2026-11-14. Work order: focused re-review of CR-P5-05 BE-1, BE-2 and HD-1 (decisions-20). Fix reports: report-be-p5-05.md, report-hd-p5-05.md.

---

CR-P5-06 focused re-review: are CR-P5-05 findings BE-1, BE-2 and HD-1 closed?

**Verdict: APPROVE.** All three findings are closed for the routes and inputs CR-P5-05 named. The fixes introduced no critical or major defect. I found one new minor finding (BE-6): there is another way to hide changes from git, of the same kind as BE-1, and the new check does not close it. I only used Read, Grep and Glob and ran nothing, so every test count and observed signal below is reported by the agents, not seen by me.

**Status per finding:**
- BE-1: CLOSED (routes a, b and c)
- BE-2: CLOSED
- HD-1: CLOSED

## 1. BE-1: clean-tree refusal (`project/LocalRuns.scala`)

**The three named routes are now refused.**
- **(a) Index flags.** `readTreeState` runs `git ls-files -v -z -- <scope>` (`:305-311`). Any entry that does not start with `H ` is flagged, and `heldoutTreeCheck` refuses it (`:349-356`). That covers `S`, lower-case (assume-unchanged) and `M`.
- **(b) Ignored files.** One call, `git status --porcelain=v1 -z --ignored=matching --untracked-files=all -- <scope>` (`:312-315`). Any entry that is not `!!` counts as a change and is refused (`:357-363`). Every `!!` path not on the allow set is refused (`:329`, `:364-371`). The `--ignored` listing does not care where the ignore rule comes from: nested `.gitignore` files, `.git/info/exclude` and `core.excludesFile` are all caught.
- **(c) Root `.gitignore`.** It must be committed and unmodified. The check is inside `heldoutTreeCheck` (`:373`), which both tasks reach through `requireCleanTree` (`:811-817` for the pipeline, `:890-896` for the evaluation).

**The allow set is exact and minimal enough** (`:269-281`).
- Matching is exact string equality against what git prints. Project `target/` directories outside the scope are filtered out (`:277`).
- `datasets/downloads/` cannot reach anything the held-out tasks use:
  - No sbt project has its base under `datasets/`. The project bases are `modules/*`, `tests` and `.` (`build.sbt:219-333`), and nothing in `build.sbt` adds resource, source or jar directories.
  - The held-out classpaths are `hadoop/Test/fullClasspath` and `entityResolution/Compile/fullClasspath` (`build.sbt:194`, `:207`).
  - `ErSplitPipeline.collectInputs` only accepts files under `datasets/samples/er-candidates/<split>`. It refuses symbolic links, non-regular files, real paths outside that root, and unexpected entries (`ErSplitPipeline.scala:490-503`). `measureTree` refuses symbolic links too.
  - A `datasets/downloads` symlink would show up as an untracked file, because the pattern `/datasets/downloads/` only matches directories, so it would be refused.
  - I see no route from `datasets/downloads/` to a classpath or a pipeline input.
- The remaining weak point is the allowed `target/` directories (see Unverified concerns).

**GIT_\* stripping, fsmonitor/untracked-cache and the 30 s bound fail closed.**
- `gitProcess` (`:165-206`) removes every inherited `GIT_*` variable, closes stdin and discards stderr.
- On timeout it calls `destroyForcibly` and returns None. If the output reader is still alive after the drain, or any exception occurs, it also returns None.
- `readTreeState` refuses on None and on any non-zero exit (`:293-303`).
- `TreeGitOptions` passes `-c core.fsmonitor=false -c core.untrackedCache=false --no-optional-locks` (`:249-250`).
- Repository and global git config are still read, which is what BE-6 relies on.

**The `-z` parsing cannot be confused in a way that lets a path through.**
- `-z` turns off path quoting, so spaces and newlines are safe.
- Porcelain paths are relative to the repository root.
- A rename or copy entry skips the original path that follows it (`:325`). The rename entry itself is already counted as a change, so even a mis-split refuses.
- Bytes that are not valid UTF-8 decode to replacement characters, so such a path can never equal an allow-set string.

**The 17 temporary-repository cases test the real function.** `gitFixture` calls `heldoutTreeCheck` itself (`:1096`) with an injected runner built on the real `gitProcess` (`:1069`). The `.gitignore` check goes through the same runner (`committedFileWith`, `:373`). I counted 17 `expect` calls (`:1150-1263`). The fixture sets `core.excludesFile` in the local config, which exercises the same `--ignored` mechanism a global setting would.

## 2. BE-2: launch refusal

**Both tasks check the launch first.** `requireTerminalBatch` is the first statement of each held-out task body (`:808`, `:879`). It runs before `logHead`, any git call and `runForked`.
- The classpath dependencies (`build.sbt:194`, `:207`) compile in-process before the body runs. That starts no forked JVM.

**Plain, non-terminal and server/client launches are refused.**
- Any command channel other than none is refused (`:450`, `:463`). That covers `sbt --client`, sbtn, BSP and IDE requests (`network-N`) and the interactive shell (`console0`).
- Server, client and BSP launch arguments are refused, and so is `shell`, whether given as an argument or queued (`:433-443`, `:460-467`).

**The pipe case is refused twice.** For `sbt goldSetEvalHeldout | tee file`:
- `/dev/fd/1` is a FIFO, so it reads as `FdPipe` (`:496`) and is refused (`:481`).
- On JDK 17, `System.console()` is null when stdout is not a terminal (`:468-469`).
- The two signals are independent. Wrappers that use a pseudo-terminal (`script`, tmux logging, IDE terminals) cannot be told apart from Terminal.app and would be allowed. That is inherent and is a human-side choice.

**A wrong refusal in a real macOS Terminal is unverified.** The logic looks right, but the allow path (console present, both descriptors character devices, channel none, nothing queued) was never observed with a real terminal.

**The self-test uses the real decision function.**
- `guardSelfTest` calls the same `launchProblems` (`:1285`, `:1299`) that `requireTerminalBatch` calls (`:527`). I counted 23 cases (`:986-1040`).
- `observeLaunch` and `fdKind` are not covered by synthetic cases. An unknown or failed read becomes `FdUnknown` and refuses.

**`verifyHeldoutGuards` stays a self-test only** (`build.sbt:394-399`, `:407`; `LocalRuns.scala:1275-1325`).
- It fails `check` only when a synthetic verdict is wrong.
- It only reports the real launch verdict and the real repository state.
- It shares no state with the task path, so it does not weaken the real guard.

## 3. HD-1: duplicate snapshot sources (`ErSplitPipeline.scala`)

- **Refused before any job.** `checkManifest` groups `inputSnapshots` by source right after decoding and returns `Left` for any repeat (`:394-408`). Only after that does it call `checkUniqueManifest` (`:409`).
- **Order in the run.** `prepareAndRun` calls `checkManifest` (`:585`) before `collectInputs`, `Attempts.next` and any stage (`:586-587`).
- **Later lookups are source-unique.** `Pipeline.run` and `summarize` receive the same validated `manifest` (`:628`, `:646`), and nothing reads the manifest file again (only `:585` decodes it; `:596` only prints the path). So the `.toMap` lookups (`:423`, `:727`) and `RunLayout.of`'s `.find` all see one entry per source.
- **The test is non-vacuous** (`ErSplitPipelineSuite.scala:186-244`).
  - Bulk-then-API and wrong-dblp-then-token passed the old last-entry guard.
  - The two reversed cases now need the "more than once" message (`:218-222`, `:227`).
  - The control requires both committed manifests to pass with their own `RunId.of` (`:231-243`).
  - The exact-duplicate case is rejected by core decoding first, as the report says, so it does not exercise the new guard.
- **Doc.** The `job-contracts.md` change (`:422-425`, `:403-404`) matches the code.

## 4. New defects in the touched code

**BE-6 (minor; owner build-engineer): changes can still be hidden from `git status` through git config or attributes.** `project/LocalRuns.scala:249-250` (`TreeGitOptions`) and `:312-315`.
- **Why.** `git status` trusts file stat data and runs clean filters. Both are controlled by repository or global config that the build neither pins nor checks. `gitProcess` strips `GIT_*` variables but still reads `.git/config` and `~/.gitconfig`.
- **Failure input 1:**
  1. Run `git config core.trustctime false` and `git config core.checkStat minimal`.
  2. Edit a tracked `modules/hadoop/src/...` file, keeping the same byte length.
  3. Run `touch -t` to set its mtime earlier than `.git/index`.
  Result: `ls-files -v` shows `H` and `git status` lists nothing.
- **Failure input 2:**
  1. Put `modules/** filter=keep` in `.git/info/attributes`.
  2. Run `git config filter.keep.clean "git show HEAD:%f"`.
  3. Edit any tracked file in scope.
  Result: `git status` compares the clean-filtered content, which is the HEAD blob, and reports a clean tree.
- **Impact.** The task logs "clean tree (decision D3(b))" and the held-out run compiles and uses uncommitted content. As with BE-1, this needs a deliberate act. A change to `~/.gitconfig` is outside the Bash audit.
- **Suggested fix.** Stop relying on stat data or filters:
  - for every entry of `git ls-tree -r -z HEAD -- <scope>`, require that `git hash-object --no-filters --stdin-paths` of the work-tree file equals the HEAD blob (handle symlink and gitlink modes explicitly);
  - require `git diff-index --cached --quiet HEAD -- <scope>`;
  - keep the untracked and ignored listing.
  Pinning `-c core.checkStat=default -c core.trustctime=true` alone does not close the filter route.

No other new defect found.

## Comments on the proposed amendment-text changes (for the human; not findings)

1. **Accurate.** `requireTerminalBatch` (`:808`) refuses whenever there is no terminal. The agent observed `System.console()=null` and regular-file descriptors in its own run. So the FE-P5-06 `ask` row (amendment lines 91 and 569) and the step-1 text "run by hadoop-engineer (ask)" (line 760) can no longer produce a run. Both options are coherent. Relaxing BE-2 for the pipeline only would be consistent with the original intent that an agent could see held-out pipeline output. This is a constitutional change (factory-engineer and the human).
2. **Accurate.** The header comment at `LocalRuns.scala:41-48` is the correct description. The new text should not say every bypass is closed (see BE-6).
3. **Accurate but incomplete.** Also outside the scope:
   - the `SBT_OPTS`, `JAVA_OPTS` and `JAVA_TOOL_OPTIONS` variables (BE-5);
   - JDK selectors (`.java-version`, `.tool-versions`, `.sdkmanrc`);
   - the dependency caches outside the repository;
   - global git config (BE-6).
   Adding `.jvmopts` and `.sbtopts` to the scope as "absent or committed" would be cheap.
4. **`datasets/downloads/` allowed:** sound (see 1 above). **`.DS_Store` refused:** fail-closed, but it costs effort because Finder recreates it; allowing the exact `.DS_Store` paths would be harmless to sbt, and it is your call. Also note that `localPipelineHeldout` refuses while the held-out labels file exists (`:279`, `:815`), so the run order or moving the file needs planning. I did not check whether the file exists.

**hadoop-engineer's core-validation suggestion: accurate.** Core only enforces distinct (source, snapshot) pairs (`Provenance.scala:187-190`), and the production `Pipeline.scala:124` (`.find`) and `:181` (last write wins) still disagree on a repeated source. Tightening it is a contract change for domain-engineer, with contract tests. Check first that no stage legitimately needs two snapshots of one source, such as an incremental input.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-P5-06 (Phase 5; decisions-20): focused read-only re-review of the closure of CR-P5-05 BE-1, BE-2 and HD-1, and of new defects in the touched code
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep, Glob only)
TEST RESULTS: none observed. Agent-reported only: BE 921/0 (76 suites); HD 922/0 (hadoop 85); verifyHeldoutGuards PASS with 23 launch cases and 17 repository cases.
VALIDATION RESULTS: none observed. Agent-reported only: verifyHeldoutOutputRoute PASS (0 of 4402 files); real launch verdict REFUSE (agent session, no terminal); run IDs unchanged.
ASSUMPTIONS: The diff is judged from current file content (I cannot run git). Git behaviour (porcelain -z format, --ignored=matching, stat trust, clean filters during status) is from git's documented behaviour. sbt behaviour (batch command source None, server and client channels) is from sbt 1.x knowledge plus the agents' observations. One Grep over modules/ (to check inputSnapshots usage) also returned paths and one line under modules/hadoop/target/test-work and the dev attempt directory. No held-out path, gold/ file, target/streams or target/local-runs path was read.
RISKS: Held-out runs still rely on git config and attributes that nothing pins (BE-6), and on the contents of the allowed target/ directories. The terminal allow path was never observed. Pseudo-terminal wrappers (script, tmux logging, IDE terminals) pass the launch check by design.
MANUAL ACTION REQUIRED: Human: decide amendment-text changes 1-4 and the core-validation suggestion; decide whether BE-6 must be fixed before the held-out run. Until it is fixed, check `git config --show-origin --get-regexp 'core\.(checkstat|trustctime|attributesfile)|filter\.'` and `.git/info/attributes` yourself before the run. Consider running held-out tasks with sbt supershell off.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Orchestrator routes BE-6 (optional for approval) to build-engineer; the human decides the proposed amendment changes; the human runs the held-out tasks in a Terminal once to see the allow path.
VERDICT: APPROVE
FINDING STATUS: BE-1: CLOSED (routes a, b, c; a further route of the same kind is new finding BE-6). BE-2: CLOSED. HD-1: CLOSED.
FINDINGS:
- build-engineer: BE-6 minor, project/LocalRuns.scala:249-250 and :312-315. Failure input 1: core.trustctime=false and core.checkStat=minimal, a same-length edit, then the mtime reset to before .git/index. Failure input 2: .git/info/attributes `modules/** filter=keep` with filter.keep.clean="git show HEAD:%f". Either way git status is empty, the task logs a clean D3(b) tree, and the held-out run uses uncommitted content. Fix: compare raw bytes with git hash-object --no-filters against git ls-tree HEAD, plus git diff-index --cached HEAD.
- hadoop-engineer: none.
UNVERIFIED CONCERNS:
- Allowed target/ directories: a file placed by hand in modules/hadoop/target/scala-*/test-classes/ (for example core-site.xml) is on hadoop/Test/fullClasspath and would be loaded by `new Configuration()` (ErSplitPipeline.scala:569). The D3(b) check accepts anything under target/. I did not verify whether sbt's copyResources removes such a file.
- sbt supershell or JLine redraws may overwrite child output written straight to the terminal (Redirect.INHERIT, LocalRuns.scala:738-739) now that a real terminal is required. Possible mitigation: sbt --supershell=never or -Dsbt.supershell=false; whether this keeps the launch check passing is not verified.
- Whether a real macOS Terminal gives channel none and an empty command queue in batch, so that the allow path is reached, is not observed.
- A committed submodule with submodule.<name>.ignore=all would hide work-tree changes inside it (no --ignore-submodules=none is passed). It needs a reviewed commit of a gitlink.
- Compilation runs before the clean-tree check (the task dependencies evaluate first), so a concurrent edit-then-revert race is possible in theory.
```
