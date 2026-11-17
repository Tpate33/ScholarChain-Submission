# Phase 06 repair 10: SP-P6-02 step 1A repair 02 (curated-run reader contract, Option A)

- Work order: prompt-curated-compat-option-a.md (human, verbatim); builds on hd-p6-01-report.md and be-p6-04-report.md.
- Owner: spark-engineer. STATUS: COMPLETE. `sbt sparkIT` not run.

## Orchestrator observations (2026-11-17)

- `git status --porcelain`: ` M modules/spark/src/test/scala/scholarchain/spark/SparkParquetCompatibilitySuite.scala`, ` M project/SparkItRuns.scala`; untracked phase-06 evidence files and the step-1A modules/spark trees.
- `git diff --stat e8c6ecc -- build.sbt project`: `project/SparkItRuns.scala | 694`, `1 file changed, 669 insertions(+), 25 deletions(-)` (unchanged since BE-P6-04).
- `.claude/hooks/verify-tests-ran.sh --check`: OK.

## Agent report (key fields, command results verbatim)

FILES CREATED: modules/spark/src/main/scala/scholarchain/spark/input/HadoopCuratedRun.scala (curated contract: rule-1 location and pin, structural rules 2-8, 10, 11, rule-2 manifest content, rule-4 counters content; producer names restated with citations).
FILES MODIFIED: input/VerifiedArchiveReader.scala, input/RunTree.scala, testkit/RunFixtures.scala, testkit/DevArchiveGraphFoundationChecks.scala, input/VerifiedArchiveReaderSuite.scala (all under modules/spark/src). Not touched: project/**, build.sbt, other modules, src/it.

COMMANDS RUN: git status/ls-files/diff (read only); `sbt sparkAnalytics/Test/compile` -> `[success] Total time: 6 s`; `sbt sparkAnalytics/test` -> `[info] Passed: Total 109, Failed 0, Errors 0, Passed 109`; `sbt check` once, last, foreground.

TEST RESULTS (sbt check, verbatim):
- `[info] verifySparkItGuards: PASS: 170 cases gave the expected verdict`
- Passed: core 557, entityResolution 464, hadoop 85, sparkAnalytics 109, tests 145; rag, root, services, storage 0; Failed 0, Errors 0.
- `[info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=5e176eb7b5641ea79b9b610933184bd85052be49, worktree=dirty, suites=94, passed=1360, skipped=0)`
- `[success] Total time: 142 s (0:02:22.0), completed Nov 17, 2026, 12:24:43 AM`

Preflight vs reader (S = project/SparkItRuns.scala curatedRunProblems; R = reader step):

| Rule | Preflight | Reader | Same verdict |
|---|---|---|---|
| 1 | pin = PinnedCuratedRunHex; archive-relative path; no symlink | [curated-run] run ID = caller pin `ArchiveRuns.pinnedCuratedRunId` (IT: committed constant), parent `snapshot=…`, grandparent `hadoop`; symlink refused [tree] | yes |
| 2 | manifest non-empty regular with crc | same structurally; content [run-manifest]: bounded read, Json.decode, stage `hadoop.pipeline`, RunId = dir, exactly one DBLP snapshot = `snapshot=` token | yes (content reader-only) |
| 3 | top level exactly manifest, crc, five stages; R/_SUCCESS refused | same; `_parts.json` refused as unexpected | yes |
| 4 | per stage size-0 `_SUCCESS`, non-empty `_counters.json`, crcs, listed subdirs | same; content [stage-counters]: bounded read, JSON object, `stage` = name, ten counters (Counters.scala:26-37) non-negative integers <= 2^53 | yes (content reader-only) |
| 5 | rounds contiguous, 1 <= k <= 4 | same | yes |
| 6 | join; pairs only if joined parts; final only if pairs parts | same | yes |
| 7 | leaves only non-empty `part-[mr]-…gz`, part-m only in rejects | same; **content NOT ENFORCED** (no part opened, decompressed or decoded) | structurally |
| 8 | only `.<X>.crc` hidden entries; every regular file has one; `.DS_Store`, `._*`, other dotfiles refused | same; **content NOT ENFORCED** (crc never compared) | structurally |
| 9 | files <= 5, dirs <= 4, 20000 entries | NOFOLLOW walk depth 5, maxTreeEntries (IT 20000) | yes |
| 10 | `_temporary`, `_IN_PROGRESS`, `_FAILED`, `.staging*`; stray `_SUCCESS`; symlinks, special files | same, via [hadoop-layout], [staging-entry], [unverified-marker], [tree] | yes |
| 11 | empty leaf, empty blocks/members refused; empty job dir accepted | same | yes |
| 12 | not checked | not checked | n/a |

Unchanged in the reader: decision and grouping checks (top-level `_SUCCESS`, `_parts.json`, maxTreeDepth 4), run-name validation, run-ID chain, driver bounds, decode, GraphInput, Identity, NOFOLLOW traversal, symlink and special-file refusals, unlistable-parent refusal, publisher. CheckStep renumbered to 15 steps (new CuratedRun 2, HadoopLayout 7, StageCounters 10).

New tests (each asserts only the named step refuses, for the curated run, with a specific message; failure-on-removal argued from assertions, **no mutation run**): 4 valid layouts plus restated-list equality; rule 1 (other pin, non-`snapshot=` parent); rule 2 (wrong stage, snapshot mismatch, undecodable, missing manifest); rule 3 (R/_SUCCESS, `_parts.json`, missing stage dir); rule 4 (missing/non-empty `_SUCCESS`, missing/empty `_counters.json`, unknown subdir, invalid JSON, wrong stage, missing counter, negative counter, oversize); rule 5 (gap, round-4); rule 6 (pairs without joined parts); rule 7 (part-m outside rejects, empty part); rule 8 (`.DS_Store`, `._*`, other dotfile, missing crc, orphan crc); rule 9 (depth-6 file, entry bound 50); rule 10 (`_temporary`, `_IN_PROGRESS`, `_FAILED`, stray `_SUCCESS`, `.staging-*`, symlinked part); rule 11 (empty leaf, empty blocks/); order (pin stops other curated checks; layout refusal before metadata read; manifest refusal before counters read). Replaced: the two "curated publishes `_parts.json`" tests (now refused by rule 3). DevArchiveGraphFoundationChecks passes the pin and asserts curated stage `hadoop.pipeline`; maxTreeDepth 4 now bounds decision and grouping only.

ASSUMPTIONS: archived curated manifest decodes with exactly one DBLP snapshot (from the committed dev manifest and HD-P6-01; archive not observed); `_counters.json` shape per Pipeline.writeReport; ujson via core's uPickle (same JSON library).

RISKS: archive not yet checked against the new rules; rules 7/8 content not enforced; rule 6 "only if"; rule 12 unprovable from the archive; pre-existing divergence: preflight tolerates `.DS_Store` in decision/grouping runs but the reader's part-manifest check refuses it as unlisted; curated depth bound fixed in code, not from ReaderLimits; messages differ, verdicts aligned.
