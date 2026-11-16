# Phase 5 human decisions after RV-P5-07, and human-run verification (transcribed by the orchestrator)

Received 2026-11-12. This is a verbatim transcription, not a human-gate record.

```text
1. Hold the full DBLP download until the two minor findings are repaired
   and verified. Bring me the reviewed command afterward.
2. Fix both minors now: refuse symlinked download paths and include
   Crossref ORCID assertions in the dev/held-out overlap check.
3. Approve the proposed nit-9 selection.tsv comment. Say that DBLP
   records are extracted from the pinned release, and update the nearby
   wording from "fetched" to "extracted." Keep the selected rows intact.
```

## Human-run `sbt check` (covers ER-P5-11, TE-P5-04, DS-P5-05)

The human supplied a Terminal screenshot of `sbt -no-colors check`. Lines transcribed from it:

```text
[info] Passed: Total 299, Failed 0, Errors 0, Passed 299
[info] Passed: Total 351, Failed 0, Errors 0, Passed 351
[info] Passed: Total 67, Failed 0, Errors 0, Passed 67
[info] Passed: Total 129, Failed 0, Errors 0, Passed 129
[info] markTestRun: core: production sources=17, test sources=17, passed=299
[info] markTestRun: entityResolution: production sources=31, test sources=32, passed=351
[info] markTestRun: hadoop: production sources=18, test sources=8, passed=67
[info] markTestRun: tests: production sources=0, test sources=17, passed=129
[info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=1bc5382c00200b76d03f6a88ffdbbac53220760e, worktree=dirty, suites=69, passed=846, skipped=0)
[success] Total time: 121 s (0:02:01.0), completed Nov 12, 2026, 11:36:05 AM
```

rag, root, services, sparkAnalytics and storage: "No tests to run" (Total 0).

Orchestrator checks after the run:
- First Bash call (`verify-tests-ran.sh --check`) refused by `audit-bash-writes.sh --pre`: UNATTRIBUTED inter-call change to `.claude/.last-test-run` only (owner GENERATED); baseline reset. Attributed to the human's Terminal `sbt check` shown in the screenshot (same procedure as recovery-04).
- Re-run: `verify-tests-ran --check: OK (no uncommitted source change is newer than .claude/.last-test-run)`.
- Marker (Read): `written_at_utc=2026-11-12T17:36:05.304204Z`, `git_head=1bc5382…`, `git_worktree=dirty`, `suites=69`, `tests_passed=846`, `tests_failed=0`, `tests_errored=0`, `tests_skipped=0`, consistent with the screenshot.

## Routing

| Item | Work order |
|---|---|
| 1. Download | held; reviewed command re-presented after the minors are repaired and verified |
| 2. RV-P5-07 MINOR-1 (symlinked download paths refused) and MINOR-2 (Crossref ORCIDs in the cross-split check) | DS-P5-06 (dataset-engineer), then code-reviewer re-review of the command |
| 3. Nit 9: approved comment text in `selection.tsv`, plus "fetched" → "extracted" in the nearby wording; selected rows unchanged | DS-P5-06 |
| Re-review of ER-P5-11 and TE-P5-04 after the passing check | RV-P5-08 (code-reviewer) |
