# BE-P6-09 repair 03: sparkSubmitLocal verdict load baseline (build-engineer final report)

Findings routed: BE-P6-10 rerun (be-p6-10-rerun-report.md), attempt-2 verdict refused on load-manifest item counts that depend on Partitions = 2. Owner: build-engineer.

The report below is verbatim except the COMPLETE DIFF field, which is omitted here: the diff (project/SparkSubmitLocal.scala, 427 insertions, 61 deletions, `index 70f5a55..a067588`) is recorded by the commit that contains this file and was given verbatim to code-reviewer (CR-P6-35).

```
STATUS: BLOCKED-ON
OWNER: build-engineer
WORK ORDER: BE-P6-09 repair 03 (Phase 6). The sparkSubmitLocal verdict now checks each load manifest's rows and distinct roles against the sparkIT run 08 baseline. Item counts depend on the output partition count, so they are no longer pinned.

Summary: the repair is done. `sbt check` exited 0 with 1570 passed and 0 failed, and the self-test passed with 130 cases (was 109). I return BLOCKED-ON human for one reason only. The backgrounded `sbt check` wrote the test marker after its tool call had ended. The audit hook then reported that write as an UNATTRIBUTED inter-call change, refused my next Bash call, and told me to return BLOCKED-ON human. This is the known remedy the work order describes. No code or test problem is pending.

FILES CREATED: none (the self-test temporary directories are deleted in `finally`)
FILES MODIFIED: /Users/taufeeqpatel/Desktop/ScholarChain/project/SparkSubmitLocal.scala (427 insertions, 61 deletions)

COMMANDS RUN:
  1. git status --porcelain: only `?? docs/agent-runs/phase-06/be-p6-10-rerun-report.md` (tree clean at HEAD 609f27a99fc7098a82f8d6abb5bce5d12110a2b6).
  2. git ls-files -- project; git ls-files --others -- target/spark-submit-local/attempt-2/run-1-local/out; git ls-files -- modules/spark/src/test/scala/scholarchain/spark/testkit, modules/spark/src/main/scala/scholarchain/spark/output, modules/spark/src/main/scala/scholarchain/spark/job, modules/core/src/main/scala. All read-only.
  3. sbt Test/compile (first try): failed with 11 errors, "No implicit Ordering defined for (String, SparkSubmitLocal.StoreFacts)" and follow-on errors. I fixed this by sorting with sortBy(_._1).
  4. sbt Test/compile (second try): "[info] done compiling", "[success] Total time: 2 s, completed Nov 18, 2026, 9:19:48 PM".
  5. sbt check, run once. It moved to the background after 180 s, I waited for it to finish, and the notification said "completed (exit code 0)". I did not start a second run.
  6. Read-only git status, diff, log and rev-parse calls while waiting. The last of them, `git log --oneline --max-count=1`, was refused by the audit hook (see BLOCKED-ON EVIDENCE).
  I did not run sbt sparkSubmitLocal, sbt clean or any AWS call. attempt-1 and attempt-2 are untouched.

TEST RESULTS (verbatim from the sbt check output):
  [info] verifySparkSubmitLocalGuards: PASS: 130 cases gave the expected verdict (temporary directories only; no network, no fork)
  [info] Passed: Total 621, Failed 0, Errors 0, Passed 621
  [info] Passed: Total 464, Failed 0, Errors 0, Passed 464
  [info] Passed: Total 85, Failed 0, Errors 0, Passed 85
  [info] Passed: Total 255, Failed 0, Errors 0, Passed 255
  [info] Passed: Total 145, Failed 0, Errors 0, Passed 145
  (rag, root, services, storage: "No tests to run")
  [info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=609f27a99fc7098a82f8d6abb5bce5d12110a2b6, worktree=dirty, suites=112, passed=1570, skipped=0)
  [success] Total time: 313 s (0:05:13.0), completed Nov 18, 2026, 9:25:25 PM
  [exited with code 0]
  The other self-tests in the same run passed as well: verifyHeldoutGuards (23 launch and 17 clean-tree cases), verifyErDevRunGuards (128), verifySparkItGuards (204) and verifySparkItRunner (8). The format check passed: scalafmt checked 6 and then 2 build sources.

VALIDATION RESULTS:
  Role field: the role field is `targetRole`. I confirmed this in three places:
  - core `LoadItem(path, recordSchemaVersion, format, rowCount, byteCount, sha256, targetRole)` in modules/core/src/main/scala/scholarchain/core/StoreLoad.scala;
  - attempt-2's s3-vectors.json and dynamodb.json;
  - spark-engineer's `LoadManifests.verify`.

  Why the build reads the manifests itself: the comparison result (format v1, `PublishedRunComparisonMain.render`) gives only `load <side> <store> <items> <rows>`, with no roles. To check roles without changing spark-engineer's interface, the build now reads both runs' `out/load=<run hex>/` files.
  - `readLoadManifests` requires a real directory holding exactly the four `<TargetStore tag>.json` regular files. Each file is at most 1 MiB, one LF-terminated row, and must be for its own store. `neptune-bulk-load.json` maps to the comparison tag `neptune`.
  - `parseLoadManifest` uses sbt's own bundled JSON parser (sjsonnew). It reads only `targetStore`, `items[].targetRole` and `items[].rowCount`, and refuses repeated keys, an empty or missing role, and a non-integer or negative rowCount.
  - The verdict also requires the build's items and rows to equal the comparison's for every store. This ties the build's reading to the comparison entry point's fully verified decode.

  New verdict:
  - Per run: the published run name, edges 259 and metrics 176 (unchanged), exactly the four stores, and per store rows 1258/1302/2809/249 and distinct roles 12/12/16/2.
  - Between the runs: comparison load values (items and rows) equal, and each store's role set equal.
  - The rest is unchanged: 28 datasets with every dataset equal, exit and timeout handling, the nonce, the strict parser, run-name and _run-manifest.json byte equality, and every earlier gate.
  - Item counts have no baseline.
  - `result.txt` on a pass also gets `loads=store:items/rows/roles`, and the task logs each run's manifest facts.

  Sanity bound on items: not added.
  - `roles <= items` always holds, because each distinct role comes from at least one item, so it would check nothing.
  - `items <= roles x Partitions` would depend on how the writer partitions each dataset. I did not find a guarantee for that in the code I read. attempt-2 shows some roles with one part and others with two.

  Self-test cases (109 -> 130, +21):
  - (a) "attempt-2 values pass: items 24/21/29/4, rows and roles at the run 08 baseline, runs equal", plus "one part per role (run 08 shape, items = roles) passes".
  - (b) "rows off the baseline in both runs" is refused.
  - (c) "distinct roles off the baseline in both runs" is refused.
  - (d) "runs that disagree on items" is refused.
  - Further refusals: build reading differs from the comparison's items; the runs name different roles with the same count; load manifests not readable by the build; a store missing from the comparison.
  - Manifest parsing: the attempt-2 s3-vectors shape gives 4 items, 249 rows and 2 roles. Refused: no targetRole, an empty targetRole, a negative rowCount, a fractional rowCount, a repeated key, no final LF, not JSON, no items.
  - Directory reading: four files are read and keyed by tag. Refused: an extra file, s3.json holding the dynamodb manifest, no load directory for the run.
  - The 11 existing verdict refusals are kept: exit 1, timeout, no result, wrong nonce, 27 datasets, a different dataset, edges 258, other run compared, datasets= mismatch, truncated result, unknown line.
  - (a) against the old code: I did not execute the old code. I conclude it would have refused by reading it. The old rule `s.loads != ExpectedLoads` with ExpectedLoads items 12/12/16/2 rejects items 24/21/29/4, and that is exactly attempt-2's recorded verdict.

ASSUMPTIONS:
  - Roles baseline: the roles 12/12/16/2 come from run 08's item counts (sparkit-run-08.md line 50). Run 08 wrote one part per dataset (`_parts.json` 29 files: 28 datasets and the run manifest), so its items equal its roles. attempt-2's job independently printed roles=12/12/16/2 (run-1-local/output.txt lines 20-23). The provenance comment in ExpectedLoads says this and explains why items are not pinned.
  - JSON parser: using sbt's bundled JSON parser (`sjsonnew.support.scalajson.unsafe.Parser`, version fixed by sbt.version=1.11.7) in the meta-build does not break the one-JSON-library rule. uPickle is not on the meta-build classpath, and no dependency was added.

RISKS:
  - The meta-build now relies on sjsonnew's shaded scalajson API, which belongs to sbt. A change to sbt.version could move it; compilation would then fail rather than pass silently.
  - The repair is not yet shown on real spark-submit output. The next sparkSubmitLocal attempt (attempt-3) is the first run of the new verdict and the new manifest reading.
  - If spark-engineer ever renames manifest files or store tags, `readLoadManifests` refuses (fails closed).

MANUAL ACTION REQUIRED:
  - Review `git status` and `git diff`. The only change to the tree is project/SparkSubmitLocal.scala, plus the marker written by `sbt check`, which passed with exit 0 (marker git_head=609f27a…, worktree=dirty, passed=1570).
  - The audit has already reset its baseline. If the Stop hook still refuses, follow the known remedy: rerun `sbt check` in a Terminal, or make one orchestrator Bash call.
  - Then: code review, the human commit, and a new human approval for one `sbt sparkSubmitLocal` run (attempt-3).

BLOCKED-ON OWNER: human
BLOCKED-ON EVIDENCE: audit-bash-writes.sh --pre, verbatim: "INTER-CALL CHANGE detected by .claude/hooks/audit-bash-writes.sh (UNATTRIBUTED) … These git-visible paths changed AFTER the previous Bash call of this session ended and BEFORE this call, without an audited Write/Edit … - .claude/.last-test-run (owner: GENERATED) This Bash call was refused. The change was NOT reverted. … Do: stop. Return STATUS: BLOCKED-ON with BLOCKED-ON OWNER: human … The baseline has been reset to the current state". The write came from markTestRun inside the backgrounded `sbt check`, which finished at 9:25:25 PM with exit code 0.

NEXT GATE: human review of the marker report, then code-reviewer review of the diff below, the human commit, and a newly approved `sbt sparkSubmitLocal` (attempt-3) whose PASS requires rows and roles at baseline and items equal between the runs.
```
