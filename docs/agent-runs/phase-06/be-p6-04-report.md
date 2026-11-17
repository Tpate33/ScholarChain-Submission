# Phase 06 BE-P6-04: build-engineer report (2026-11-17)

- Work order: curated-run sparkIT preflight per HD-P6-01 rules 1-11, Option A (prompt-curated-compat-option-a.md).
- STATUS: COMPLETE. `sbt sparkIT` not run.

## Orchestrator observations

- `git diff --stat e8c6ecc -- build.sbt project`: `project/SparkItRuns.scala | 694 ++++++...--`, `1 file changed, 669 insertions(+), 25 deletions(-)`.
- `.claude/hooks/verify-tests-ran.sh --check`: `verify-tests-ran --check: OK (no uncommitted source change is newer than .claude/.last-test-run)`.
- No audit interruption on the next Bash call.

## Agent report (key fields, command results verbatim)

FILES MODIFIED: project/SparkItRuns.scala only. build.sbt and project/spark-it.properties unchanged (`run.curated` already names the pinned run). modules/spark/** untouched.

COMMANDS RUN: `git status --short`; `git ls-files -- project`, `-- modules/hadoop/src/main docs/job-contracts.md`, `-- modules/hadoop`; `sbt Test/compile` (`[success] Total time: 1 s`); `sbt check` once, last, foreground; `git diff --stat e8c6ecc -- build.sbt project`. One `git grep` refused by the character gate (nothing ran).

TEST RESULTS (verbatim lines):
- `[info] verifySparkItGuards: PASS: 170 cases gave the expected verdict` (before 129; +41: 1 config-parse pin case, 1 decision-run `.DS_Store` tolerance case, 39 curated cases: 4 accepted layouts, 33 refusals, entry bound plus control; the old "curated run without _SUCCESS" case replaced by "curated run holds a run-level _SUCCESS (refused)").
- Passed: core 557, entityResolution 464, hadoop 85, sparkAnalytics 63, tests 145; rag, root, services, storage 0; Failed 0, Errors 0.
- `[info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=5e176eb7b5641ea79b9b610933184bd85052be49, worktree=dirty, suites=94, passed=1314, skipped=0)`
- `[success] Total time: 140 s (0:02:20.0), completed Nov 17, 2026, 12:06:02 AM`

Design:
- Pin `PinnedCuratedRunHex = 2881724b…8baa`, checked in `parseConfig` and `layoutProblems`; any other curated run ID refused outright (no fallback).
- `curatedRunProblems(runDir, shown, maxEntries)` replaces the top-level `_SUCCESS` rule and depth-4 walk for the curated run only; `_UNVERIFIED` refusal kept. NOFOLLOW walk, depth limit 6; refuses symlinks, special files, files deeper than 5, directories deeper than 4, more than 20000 entries (`CuratedMaxEntries`); at most 20 problems reported.
- Unchanged: decision and grouping rules (`_SUCCESS`, `_parts.json`, no `_UNVERIFIED`, depth 4, `.DS_Store` tolerance), run-name and segment rules, held-out/gold/target-streams refusals, environment stripping, pass rule, 120 s bound, all pre-existing cases.

Rule mapping (S = preflight, names/types/sizes; R = left to the reader):
1. S: name, location, no symlink, equals the pinned run.
2. S: `_run-manifest.json` non-empty regular with crc. R: strict decode, stage `hadoop.pipeline`, RunId = directory, dblp snapshot = `snapshot=` token.
3. S: top level exactly manifest, crc, five stage dirs; `R/_SUCCESS` refused.
4. S: per stage size-0 `_SUCCESS`, non-empty `_counters.json`, crcs, only the listed subdirectories. R: `_counters.json` JSON with `stage` and the ten counters.
5. S: round-0..round-(k-1), 1 <= k <= 4.
6. S: join present; pairs only if join/joined holds a part; final only if pairs/pairs does ("only if", not "iff").
7. S: leaves only non-empty `part-[mr]-[0-9]{5,}.gz`, `part-m` only in rejects. R: decompress/decode, checksum verification.
8. S: only hidden entry `.<X>.crc` for a regular non-hidden sibling; every regular file has one; `.DS_Store`, `._*`, other dotfiles refused. R: crc matches file.
9. S: files <= 5, directories <= 4, entry bound.
10. S: `_temporary`, `_IN_PROGRESS`, `_FAILED`, `.staging*` refused anywhere; `_SUCCESS` outside a stage dir refused; symlinks, special files refused.
11. S: empty leaf refused, empty blocks/ or members/ refused; empty job output dir accepted.
12. Not checked (archive holds R only; recorded in prompt-curated-compat-option-a.md).

RISKS (as reported): archive not yet checked against the new rules; content parts of rules 2, 4, 7, 8 depend on the reader; until the reader is updated it still refuses this run (top-level `_SUCCESS`, depth 4); rule 6 not "iff"; rule 12 unprovable from the archive; changes the reviewed and ratified e8c6ecc build (CR-P6-16 F5), so re-review and human approval required.

NEXT GATE: spark-engineer reader alignment, one focused review of the combined repair, human approval of the revised build, then one `sbt sparkIT`.
