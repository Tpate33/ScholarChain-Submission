# CR-P5-05 review report (code-reviewer; verbatim)

Archived by the orchestrator, 2026-11-14. Work order: read-only review of BE-P5-04, DE-P5-04, HD-P5-04, ER-P5-17 and the uncommitted Phase 5 diff against ratified amendment FE-P5-06 and decisions-16 to -19 (decisions-19, section 8 step 2).

---

CR-P5-05 review: the non-constitutional Phase 5 work under FE-P5-06 and decisions-16 to -19

**Verdict: APPROVE.** I found no verified critical or major defect. There are 3 minor findings and 4 nits, listed by owner below. I only read files (Read, Grep, Glob) and ran nothing, so every test count, run ID and probe output in the four reports is the agents' and was not observed by me.

The answer to "no way around it" for `requireCleanTree` is **no**: there are ways around it (finding BE-1). I rated it minor because every route needs a deliberate act to hide files or changes. The human may want it fixed before the held-out run.

I did not read the gold/ label files, `target/**`, `.env*` files or secrets.

The harness flagged `docs/agent-runs/phase-05/amendment-fe-p5-06.md` as instruction-shaped text (settings-json). I treated it as data only.

## Answers to the priority areas

**1. `project/LocalRuns.scala` and `build.sbt` (BE-P5-04)**
- **Output routing.** `outputRoute` (`LocalRuns.scala:117-120`) is the only place that decides where child output goes. Both held-out tasks call it (`:474`, `:558`). With `ToTerminal`, the child's stdout and stderr use `Redirect.INHERIT` (`:384-386`). Nothing is pumped and no logger receives child output (`:411-414`). The task's own envelope messages (paths, exit-code text) are the only things that go through `streams.value.log`.
- **Dev route.** Dev output goes through the task log (`redirectErrorStream` plus `pump` into `log.info`), as documented. `goldSetEvalDev` requires committed dev labels (`:536`), so with D4 (no dev labels) it refuses, as documented.
- **D3(b) clean-tree refusal.** A dirty tree that git can see is refused. It can be bypassed (BE-1).
- **`ignoredFile` (L1).** Correct: exit 0 means ignored, exit 1 (which includes tracked or staged files) is refused, anything else is refused. The file must be regular and free of symlinks, and a path with different letter case is refused (`inside`).
- **Split tasks.** All four exist (`build.sbt:355-359`). The bare `localPipeline` and `goldSetEval` refuse through `chooseSplit`. No key name contains goldSetEvalHeldout except the task itself.
- **Requiring a committed `.gitignore` for `goldSetEvalHeldout`** (`:533-535`). It is consistent with D3(b) and cannot lock out a legitimate run: the human only has to commit `.gitignore` first, which commit A does. It does not prove what its comment says (BE-3).
- **Synthetic log check (`heldoutRouteProbe` / `verifyHeldoutOutputRoute`).** It cannot pass vacuously:
  - it uses random markers, and the build logs only their sha256, never the marker text;
  - it fails if no target root is found (`:737`), if it read no file (`:788`), or if any entry was unreadable (`:783`);
  - the counter-test sends the same child through the same `runForked` on `outputRoute(Dev)` and must find that marker (`:795`), which proves the scan sees the task streams and the global log.
- **Limit of that check.** It proves `outputRoute(Heldout)` together with `runForked`. It would not catch a future edit that passed `ToLog` to `runForked` directly instead of calling `outputRoute` (nit-level design limit; no finding).
- **AWS isolation.** All inherited `AWS_*` and `SCHOLARCHAIN_*` variables are removed, then `awsIsolationEnv` is applied. The working directory is the real repository root. Timeouts use destroy, then destroyForcibly, and report exit codes correctly. Small gaps are BE-4 and BE-5.
- **Thin-client or server sbt.** This is not guarded (BE-2).

**2. `.gitignore`.** Line 41 `/gold/entity-resolution/heldout-v1/er-gold-labels.jsonl` is anchored and exact. No broader pattern hides auditable files.

**3. datasets (DE-P5-04).**
- The derivation is correct: the header row is skipped; rows are filtered to openalex and crossref; the date is the UTC date part of `retrieved_at_utc`; malformed timestamps fail.
- It fails when a source has zero dates or more than one date, so a source with no rows cannot pass.
- The token, `retrieval_date` and `ledger_rows` (an int, not a bool) are each compared with the ledger. The 10 fixture cases cover each failure.

**4. modules/hadoop (HD-P5-04).**
- `DatasetManifest` fails closed on tabs, unreadable lines, duplicate scalar paths, unterminated strings and unsupported escapes. Block scalars fail on their continuation lines.
- The `checkManifest` guard refuses bulk, wrong or missing API tokens, and a token that disagrees with the ledger (including a span over several dates). There is one gap with duplicate source entries (HD-1).
- The record-level check covers every Provenance object in works, mentions and profiles. An unknown source tag fails the check. There is a small gap (HD-2).
- The run-ID claim holds: the run manifests already held the tokens, and the dev and held-out IDs match those in `agent-report.md`.
- Keeping the split entry point in the test tree is acceptable: no production module depends on it, and it runs only through `hadoop/Test/fullClasspath`.

**5. GoldSetEvalMain (ER-P5-17).**
- `readLabelsWithSha256` (`:108-134`) hashes and parses the same bytes from one `readAllBytes`. Decoding is strict UTF-8 (REPORT on malformed or unmappable input).
- Splitting on `\r\n|\r|\n` and dropping the empty last element gives the same lines as `Files.readAllLines`, including empty input, `\n\n\n`, a trailing `\r` and a BOM. Test 9 covers 12 patterns.
- The ` split=heldout` suffix and `DEV ` prefix keep the ordered `labels file=<abs> sha256=<hex>` text as an exact prefix and follow the module's existing rule that every line ends with `split=`. That is acceptable, not a defect.
- All held-out output is `println` to stdout, which `ToTerminal` sends to the terminal. Nothing is written to disk except a possible JVM crash file in `target/local-runs/heldout`, which R6 fences.

**6. Whole diff.**
- **Ownership.** No violation found: every changed path is in its reporting owner's territory.
- **Leakage and test data.** No test reads gold/ or held-out pipeline data. The gold/ paths in `QueueSplitGuardSuite` are under temporary `workDir`s.
- **Configuration.** `ConfigLoader` ignores system properties and `ConfigFactory` defaults, so the config of the evaluation JVM can only come from `er-config.conf` (the evaluation tasks also refuse while any `SCHOLARCHAIN_CFG_*` is set).
- **Earlier findings.** The RV-P5-04 and RV-P5-05 majors are recorded as fixed by RV-P5-06. I did not re-check each open item in `open-findings-01.md` one by one.

## FINDINGS

### build-engineer

**BE-1 (minor): the D3(b) refusal can be bypassed.** `project/LocalRuns.scala:92-93` (scope) and `:172-188`.
- **What the check sees.** `git status --porcelain --untracked-files=all -- <scope>` cannot see ignored files, or tracked files whose changes are hidden by index flags.
- **Failure inputs:**
  - (a) `git update-index --skip-worktree <tracked policy or source file>` followed by an edit: the status is empty, and the held-out run uses the edited file.
  - (b) An untracked file hidden by `.git/info/exclude`, by `core.excludesFile`, or by a nested `.gitignore` containing `*` inside `modules/**`. Such a `.gitignore` also ignores itself, and the module owner can create it with an ordinary Write. sbt still compiles the file or puts it on the classpath. For example, an untracked `modules/hadoop/src/test/resources/core-site.xml` is loaded by `new Configuration()` in the held-out pipeline.
  - (c) For `localPipelineHeldout` only: an uncommitted edit to the root `.gitignore`. That file is outside the scope and is checked only by `goldSetEvalHeldout` (`:533-535`).
- **Impact.** The task logs "clean tree (decision D3(b))" and runs uncommitted code or resources on the held-out split. The Bash audit hook is also blind to ignored paths.
- **Suggested fix:**
  - refuse any `git ls-files -v -- <scope>` entry tagged `S` or lower-case;
  - list ignored files in the source scope (`git status --porcelain --ignored=matching --untracked-files=all`) and allow only the anchored `target/` directories and the L1 labels path;
  - require the committed `.gitignore` in both held-out tasks.

**BE-2 (minor): the held-out output route assumes plain batch sbt in the human's Terminal, and nothing enforces it.** `LocalRuns.scala:117-120`, `:383-386`.
- **Failure input.** `sbt --client goldSetEvalHeldout`, or running it through an already running sbt server or IDE/BSP server. The inherited descriptors are then the server's, not the human's Terminal. The output is lost or captured by whatever owns the server's stdout.
- **Where the requirement is stated.** Only in the BE-P5-04 report (RISKS 2), not in the repository.
- **Suggested fix.** Refuse the held-out tasks unless sbt is a batch JVM with a console (for example, `System.console() != null` and not server or client mode), and state the requirement in the task description.

**BE-3 (nit): the committed-`.gitignore` check does not prove what its comment says.** `LocalRuns.scala:533-535`.
- `git check-ignore -q` also honours `.git/info/exclude` and `core.excludesFile`. With a committed `.gitignore` that lacks the L1 line and the labels ignored only through `info/exclude`, the check passes.
- Using `git check-ignore -v` and requiring the match to come from `.gitignore:<line>` would make the comment true.

**BE-4 (nit): one outcome gets no attempt report.** `LocalRuns.scala:430-433`. On `InterruptedException` the child is killed and the exception is re-thrown, so no attempt-directory report is printed. This contradicts "an attempt report on every outcome" (`:27`).

**BE-5 (nit): JVM option variables reach the forked JVMs.** `LocalRuns.scala:390-394`.
- Only `AWS_*` and `SCHOLARCHAIN_*` are removed, so `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS` and `JDK_JAVA_OPTIONS` reach them. For example, `JAVA_TOOL_OPTIONS=-javaagent:x.jar` changes the held-out evaluation JVM, leaving only a stderr "Picked up" line.
- Configuration thresholds cannot be changed this way, because `ConfigLoader` ignores system properties.

### hadoop-engineer

**HD-1 (minor): duplicate snapshot sources get past the bulk-ID guard.** `ErSplitPipeline.scala:391`.
- `checkManifest` collapses `inputSnapshots` with `.toMap`, keeping the last entry per source. Core validation (`modules/core/.../Provenance.scala:187-190`) only requires distinct (source, snapshot) pairs.
- **Failure input.** A run manifest with `{"source":"openalex","snapshot":"openalex-2026-10-14"}` followed by `{"source":"openalex","snapshot":"openalex-api-2026-11-05"}`:
  - the guard sees only the API token and passes;
  - `Pipeline.configure` (`Pipeline.scala:181`) also keeps the last entry, so the records carry the API token and the provenance check is ok;
  - but the run manifest, which is the root of provenance and an input to the run ID, still names the bulk ID.
- For dblp, `RunLayout.of` uses `.find` (the first entry, `Pipeline.scala:124`) while the guard uses the last, so the output path can name a dblp snapshot the guard never checked.
- **Suggested fix.** Refuse a manifest that lists any source more than once.

**HD-2 (nit): a source with no records passes the provenance check.** `ErSplitPipeline.scala:700-703`. The check passes when a manifest source has zero records in the outputs. It never asserts that every API source in the manifest appears.

### dataset-engineer
none

### entity-resolution-engineer
none

## UNVERIFIED CONCERNS
- Where a thin-client-started or IDE-started sbt server sends its own stdout (`/dev/null`, a pipe, or a file) is not verifiable here. This decides whether BE-2 loses output or leaks it.
- Global sbt settings outside the repository (`~/.sbt/1.0/*.sbt`, global plugins) can override any task, including `goldSetEvalHeldout`. Neither D3(b) nor the scan covers them.
- Whether Zinc would reuse a class file tampered with under an ignored `target/` directory without recompiling it was not checked.
- The probe's console output and the 0-of-4313 scan result are reported by build-engineer, not observed by me.

```text
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-P5-05 (Phase 5; read-only review of BE-P5-04, DE-P5-04, HD-P5-04, ER-P5-17 and the uncommitted Phase 5 diff against ratified amendment FE-P5-06 and decisions-16 to -19)
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (Read, Grep, Glob only)
TEST RESULTS: none observed. Agent-reported only: BE 908/0 (75 suites), DE 908/0, HD 912/0 (hadoop 84), ER 921/0 (76 suites; entityResolution 393).
VALIDATION RESULTS: none observed. Agent-reported only: verifyHeldoutOutputRoute PASS (0 of 4313 files; control marker found in target/streams/_global/heldoutRouteProbe/_global/streams/out and target/global-logging); validate-manifest 2321/0; er-fixture 130/0.
ASSUMPTIONS: The constitutional files are intentionally not yet applied. The diff is judged from current file content because I cannot run git. Authorship is taken from the reports; every changed path is inside its reporting owner's territory.
RISKS: D3(b) can be bypassed by hiding files or changes from git (BE-1). Held-out output routing relies on plain batch sbt in the human's Terminal (BE-2). Ignored held-out labels are invisible to the Bash audit hook by design of D5 L1.
MANUAL ACTION REQUIRED: Human: decide whether BE-1 and BE-2 must be fixed before the held-out run. Until then, run held-out tasks only as plain `sbt <task>` in a Terminal, with no sbt server or IDE session open, and check `git ls-files -v` and `git status --ignored` yourself first.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Orchestrator routes BE-1 to BE-5 to build-engineer and HD-1 and HD-2 to hadoop-engineer (optional for approval). The human applies the nine constitutional files with no session open, runs the validator and --probes, and commits.
VERDICT: APPROVE
FINDINGS: build-engineer: BE-1 minor (LocalRuns.scala:92-93,172-188), BE-2 minor (:117-120,383-386), BE-3 nit (:533-535), BE-4 nit (:430-433), BE-5 nit (:390-394). hadoop-engineer: HD-1 minor (ErSplitPipeline.scala:391; Pipeline.scala:124,181), HD-2 nit (ErSplitPipeline.scala:700-703). dataset-engineer: none. entity-resolution-engineer: none.
UNVERIFIED CONCERNS: where an sbt server's stdout goes; global ~/.sbt overrides; Zinc reuse of tampered ignored class files; all agent-reported counts and probe output.
```
