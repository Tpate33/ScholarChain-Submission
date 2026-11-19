# CR-P6-35: review of BE-P6-09 repair 03 (code-reviewer final report, verbatim)

```
STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: CR-P6-35 (Phase 6, read-only). Review of the uncommitted change to /Users/taufeeqpatel/Desktop/ScholarChain/project/SparkSubmitLocal.scala (build-engineer, BE-P6-09 repair 03) against HEAD 609f27a99fc7098a82f8d6abb5bce5d12110a2b6.
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none. I used only Read, Grep and Glob. I could not run git, so I reviewed the working-tree file directly and did not see a diff against HEAD.
TEST RESULTS: none run by the reviewer. The reported sbt check result is consistent with the evidence: the suite totals 621+464+85+255+145 add up to 1570. /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run shows written_at_utc=2026-11-19T03:25:25Z (9:25:25 PM local, matching the report), git_head=609f27a…, git_worktree=dirty, tests_passed=1570, failed 0, errored 0, skipped 0.
VALIDATION RESULTS:
  Check 1 (verdict and baseline):
  - verdict (SparkSubmitLocal.scala:1417-1485) checks the following for each side: the published run name, edges 259 (:1442), metrics 176 (:1444), and exactly the four stores (:1446).
  - For each store it checks comparison rows against ExpectedLoads (:1449-1452, rows 1258/1302/2809/249) and build-read distinct roles 12/12/16/2 (:1457). The build's items and rows must equal the comparison's (:1459).
  - Between the runs it requires equal items and rows (:1468) and equal role sets (:1471-1477).
  - It also requires 28 datasets, all equal (:1478-1482). Nonce, exit/timeout and the strict parser (:1224-1280) are unchanged. Run-name and _run-manifest.json byte equality are still enforced at :1740-1743.
  - Only the item pin was removed. I found no other gate weakened.
  - Attempt-2 evidence, verified by Read and Grep:
    - comparison.txt shows loads 24/1258, 21/1302, 29/2809, 4/249 on both sides, 28 datasets equal, and edges 259 and metrics 176 on both sides.
    - The run-1 manifests contain 12 (neptune-bulk-load), 12 (dynamodb), 16 (s3) and 2 (s3-vectors) distinct targetRole values, which I counted by hand.
    - The run-2 dynamodb manifest has the same 21-item role list.
    - Each load dir holds exactly the four .json files and no hidden files. Each manifest is one LF-terminated row, well under 1 MiB.
  - So attempt-2's data passes the new verdict, and attempt-3 with unchanged code and data should pass.
  - A change in rows, role count, role names, cross-run items, or any dataset is still refused (self-test cases at :2719-2758).
  - Role baseline provenance: sparkit-run-08.md:50 gives items 12/12/16/2 with 29 _parts.json files. The claim that run 08 had one part per dataset, so items = roles, is an inference, as both reports state. The pinned role counts are independently confirmed by attempt-2's actual manifests at the current code, so the gate is sound either way.
  Check 2 (manifest reading):
  - LoadManifestStores (:1289-1294) maps neptune to neptune-bulk-load and the other three stores to their own tags. This matches LoadManifests.fileName (`${t.tag}.json`, LoadManifests.scala:61), core TargetStore.NeptuneBulkLoad "neptune-bulk-load" (StoreLoad.scala:18), and PublishedRunComparisonMain.storeTag (:130-133).
  - targetRole is the core LoadItem field (StoreLoad.scala:42).
  - readLoadManifests fails closed on each of the following:
    - a symlinked or non-directory load dir (:1374)
    - a directory listing that is not exactly the four files, hidden files included, since `list` uses Files.list (:1141-1147, :1376)
    - a non-regular or symlinked file (NOFOLLOW, :1384)
    - a file over 1 MiB (:1385)
    - a manifest for the wrong store (:1390)
  - parseLoadManifest refuses each of the following:
    - not exactly one LF-terminated row
    - malformed JSON
    - a repeated key at the top level and inside items (:1316-1322)
    - a missing or empty targetStore or targetRole
    - empty items
    - a rowCount that is not ^(0|[1-9][0-9]{0,17})$, which rejects negative, fractional and exponent forms
  - An 18-digit value fits in a Long, so the per-item toLong cannot overflow.
  - IO exceptions abort the task without a pass.
  Check 3 (JSON library): the code uses sbt's own bundled sjsonnew in the meta-build. It is the only such use under project/ and adds no dependency. Judged acceptable; see nit n3.
  Check 4 (self-test):
  - Case (a) (:2703-2708) uses the attempt-2 items 24/21/29/4. The old rule `s.loads != ExpectedLoads`, with items 12/12/16/2, refuses exactly these values; attempt-2's recorded verdict is that refusal. That is my reading of the code and the evidence; I did not execute the old code.
  - Both temp dirs are deleted in finally (:2538, :2642).
  - Case count: 22 new cases (9 parse, 4 directory, 9 verdict) plus the 11 kept refusals. Going from 109 to 130 (+21) is consistent if the old pass case was replaced, which matches the code shape. I could not confirm this without the old file.
  Check 5 (honesty): the 1570/0/0/0 result, the 130 cases and the marker landing after the audited call all agree with the marker file and the report text. The two untracked docs/agent-runs files are orchestrator evidence.
ASSUMPTIONS: The orchestrator said the verbatim diff was given to me, but it was not in my work order. I reviewed the working-tree file instead, so I could not see the old code or the old case count directly.
RISKS:
  - The new manifest reading has not yet run against real spark-submit output; attempt-3 will be its first run.
  - The meta-build now depends on sjsonnew's shaded scalajson API, which is tied to sbt.version 1.11.7. A change there would break compilation, which fails loudly rather than silently.
MANUAL ACTION REQUIRED: The human reviews git status and git diff of project/SparkSubmitLocal.scala, then commits. Running sbt sparkSubmitLocal again (attempt-3) needs a new human approval. Optionally, the human or factory-engineer records whether sbt's built-in JSON parser in project/ code is within the "one JSON library" invariant (n3).
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Human commit of the repair, then one human-approved `sbt sparkSubmitLocal` run (attempt-3), whose PASS needs baseline rows and roles and equal items between the runs.
VERDICT: APPROVE
FINDINGS:
  There are no critical, major or minor findings. All nits belong to build-engineer except n3, which is for the human or factory-engineer.
  - n1 (nit), SparkSubmitLocal.scala:2584-2590:
    - The "manifest with a repeated key" case calls refusedWith with expected substring "", so any refusal satisfies it.
    - Failure input: a parser regression that refuses this input for another reason, for example a duplicate-key exception reported as "not JSON". The case would still pass without showing that the repeated-key branch (:1319) works.
    - Impact: the case is less specific than the work order asked. It still catches a parser that silently accepts duplicates, which would return Right.
    - Fix: assert "repeats a key", or "not JSON" if the parser itself rejects duplicates.
  - n2 (nit), SparkSubmitLocal.scala:2598-2642:
    - No self-test covers a symlinked load=<hex> directory, a symlinked manifest file, an oversize (> 1 MiB) manifest, or a repeated key inside items[]. The code handles all of these (:1374, :1384, :1385, :1316).
    - Impact: a later regression in those branches would go unnoticed by verifySparkSubmitLocalGuards.
  - n3 (nit, policy clarification; owner human or factory-engineer):
    - parseLoadManifest (:1315, :1325) uses sbt's internal sjsonnew parser, while CLAUDE.md says "One JSON library … uPickle". This is build tooling that runs in sbt's own classpath. It adds no dependency, and its version is fixed by sbt.version. I judge it acceptable and not a violation.
    - The invariant does not say whether it covers the meta-build, so a one-line clarification would prevent the question from coming up again.
  - n4 (nit), SparkSubmitLocal.scala:1357 and the manifest re-read:
    - Overflow: `parsed.map(_._2).sum` can wrap if several 18-digit rowCounts are summed. The comparison sums the same way (PublishedRunComparisonMain.scala:289), so the cross-check would wrap identically. A wrapped total would still have to equal the pinned row baseline exactly, which honest job output cannot reach.
    - TOCTOU: the build re-reads the manifests after the comparison JVM has verified them. The items/rows cross-check binds counts and rows but not role names. Only an outside writer acting after every child had exited could exploit this, and both runs' role sets must still match each other and the role count.
    - Impact: negligible.
UNVERIFIED CONCERNS:
  - I could not confirm the 61 deleted lines or the earlier case list, because I could not read HEAD's version of the file. As a result, I cannot fully rule out that an earlier self-test case unrelated to loads was removed (the +21 arithmetic suggests none was).
  - I did not confirm that sjsonnew's unsafe facade keeps duplicate object keys rather than rejecting them. The self-test result shows a refusal happens either way.
  - The claim that sparkIT run 08 wrote one part per dataset is inferred from its counts, not from its configuration. It does not affect the gate, because attempt-2's roles match.
```

Orchestrator note on the first unverified concern: the orchestrator's work order to CR-P6-35 wrongly implied the diff was in be-p6-09-repair-03.md. The orchestrator checked the verbatim diff in build-engineer's hand-back (index 70f5a55..a067588). Its removed self-test lines are only the old "complete comparison passes" case and the 11 refusal cases (exit 1, timeout, no result, wrong nonce, 27 datasets, a different dataset, edge count off baseline, other run compared, datasets= mismatch, truncated result, unknown line), and all 11 are re-added through `judge`. No unrelated case was removed: 109 − 1 + 22 = 130. Nits n1, n2, n4 deferred to build-engineer; n3 deferred to the human or factory-engineer.
