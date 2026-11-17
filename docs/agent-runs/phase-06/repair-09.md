# Phase 06 repair 09: SP-P6-02 step 1A repair 01 (CR-P6-19 F1-F6)

- Work order (verbatim): docs/agent-runs/phase-06/prompt-sp-p6-02-step-1a-repair-01.md
- Findings routed: CR-P6-19 F1 (major), F2-F5 (minor), F6 (nit) (cr-p6-19-review.md)
- Owner: spark-engineer

## Orchestrator observations after the report (2026-11-16)

- The orchestrator's next Bash call was not refused by the audit (sbt check ran in the foreground inside an audited call, per the agent).
- `git status --porcelain`: ` M modules/spark/src/test/scala/scholarchain/spark/SparkParquetCompatibilitySuite.scala`; untracked phase-06 evidence files and the step-1A modules/spark trees (src/it/, src/main/scala/scholarchain/, src/test/resources/, test packages analysis/, graph/, input/, output/, testkit/).
- `git diff --stat e8c6ecc -- build.sbt project`: no output.
- `.claude/hooks/verify-tests-ran.sh --check`: `verify-tests-ran --check: OK (no uncommitted source change is newer than .claude/.last-test-run)`.
- The `sbt check` output itself was not observed by the orchestrator; its result rests on the agent's report.

## Agent report (spark-engineer, 2026-11-16), summary of key fields with verbatim command results

STATUS: COMPLETE. OWNER: spark-engineer. `sbt sparkIT` NOT run. MANUAL ACTION REQUIRED: none. BLOCKED-ON: none.

Correction to the step 1A report: line 68 ("Every refusal has its own feature test that fails without its check") was false. Now: every reader refusal and publication outcome has a test observed failing when its check is deleted, except (1) the grouping run-ID-vs-directory check, also caught by `PartManifestV2.checkRunManifest` (the new curated F1 test guards that check), and (2) the per-part read bound in decode, only reachable if a file grows after verification (the `readUtf8` bound is tested directly). VerifiedArchiveReaderSuite had 22 tests in step 1A, not 23.

F6 corrected order: (1) layout for all three runs, no content read, all layout refusals reported together; (2) per run, curated, decision, grouping, stopping at the first refusal: part-manifest then run-manifest (interleaved), metadata read with at most maxMetadataBytes; (3) run-chain; (4) driver-bound, rows and bytes, before any data row is read; (5) decode, each part read with at most its declared bytes; (6) graph-input, then identity.

Changes:
- F1: `RunFixtures.Spec` lets one manifest change propagate new run IDs down the chain, so each mutation differs only in the refused property.
- F2: tests for the untested branches; order tests that bounds run before decoding.
- F3: `ReaderLimits.maxDriverValidationBytes` and `maxMetadataBytes` (caller-supplied); `RunTree.readUtf8(file, maxBytes)` checks size before opening and reads at most maxBytes+1; driver-bound checks declared bytes; publisher reads its run back with maxMetadataBytes.
- F5: `RunTree.names` returns Either and fails closed; an unlistable parent is a staging-entry refusal.
- F4: publish = pre-checks; create-once staging; stage and verify; claim destination with `Files.createDirectory(run=<hex>)` (never replaces); ATOMIC_MOVE onto the claim; re-verify. Outcomes: (c) claim fails -> staging removed, destination untouched; (b) rename fails -> staging removed, claim removed only if still empty (`deleteIfExists`); (a) post-rename verification fails -> `_UNVERIFIED` created, then `_SUCCESS` deleted, directory kept; reader and `verifyPublished` refuse it.
- F4 limitation (RunPublisher Scaladoc): the JDK has no no-replace rename; ATOMIC_MOVE maps to rename(2), which replaces an empty target directory (observed in mutation round C). Guarantee rests on the mkdir claim; not covered: another process deleting the claim and recreating an empty directory between claim and rename (excluded by a single-writer-per-output-root assumption). A crash in that window leaves an empty claim that blocks that run ID (fails closed; human removes it). A full fix needs `renameat2(RENAME_NOREPLACE)` / `renamex_np(RENAME_EXCL)` via native calls; not done.
- Test seams: `private[output] PublishHooks` (beforeClaim, beforeMove, afterMove); production uses `PublishHooks.Noop`.

FILES CREATED: none. FILES MODIFIED: input/RunTree.scala, input/VerifiedArchiveReader.scala, output/RunPublisher.scala, analysis/AnalysisRun.scala, testkit/RunFixtures.scala, testkit/DevArchiveGraphFoundationChecks.scala (IT limits maxDriverValidationBytes 64 MiB, maxMetadataBytes 1 MiB), input/VerifiedArchiveReaderSuite.scala, output/RunPublisherSuite.scala, graph/CoauthorEdgeJobSuite.scala (all under modules/spark/src).

COMMANDS RUN (as reported):
1. `sbt sparkAnalytics/Test/compile`: [success], 6 s.
2. `sbt sparkAnalytics/test`: Passed: Total 63, Failed 0, Errors 0.
3. Mutation round A (M1-M12, P1, P3, P5): exit 1 as intended; reader suite 38 tests, 16 failures (exactly the 16 new tests); publisher suite 11 tests, 4 failures.
4. Mutation round B (P3 only): Failed 1.
5. Mutation round C (P2 only): Failed 2; the F4(c) claim test returned Right (empty destination replaced).
6. Mutations restored.
7. `git diff --stat e8c6ecc -- build.sbt project`: no output.
8. `sbt check`, once, last, foreground, 600000 ms, not backgrounded. Final lines verbatim:
   - `[info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=5e176eb7b5641ea79b9b610933184bd85052be49, worktree=dirty, suites=94, passed=1314, skipped=0)`
   - `[success] Total time: 140 s (0:02:20.0), completed Nov 16, 2026, 11:17:59 PM`
9. `git status --porcelain`.

TEST RESULTS: core 557, entityResolution 464, hadoop 85, sparkAnalytics 63 (7 production, 8 test sources; SparkParquetCompatibilitySuite 3, VerifiedArchiveReaderSuite 38, CoauthorEdgeJobSuite 7, RunPublisherSuite 11, DevInputsSuite 4), tests 145, rag/root/services/storage 0; Failed 0, Errors 0 everywhere; total 1314.

Test-to-finding map (deleted line that makes each fail): F1 M1 decision inputRunId, M2 grouping vs decision snapshots, M3 decision vs curated snapshots, M4 curated run ID vs directory; F2 M5 no match-decision part, M6 Kind.Other (unit test on walked entries; JDK cannot create a FIFO, socket path too long), M7 maxTreeEntries, M8 curated `_parts.json` pass and mismatch, M12 bound/decode swap; F3 M10 bytes bound (and order test), M11 readUtf8 size checks (four tests; fails only with both checks removed); F5 M9 listing failure mapped to empty; F4(a) P1 markUnverified; F4(b) P3 deleteTree(staging), P5 deleteIfExists(claim); F4(c) P2 createDirectory claim (observed), pre-check plus claim (reasoned, not run).

RISKS (as reported): residual claim delete-and-recreate race; crash leaves an empty claim (fails closed); same-size post-verification change undetected; SparkIT class unobserved until sparkIT; IT limits unmeasured.

NEXT GATE: focused re-review CR-P6-20; then build re-confirmation against e8c6ecc and one standalone `sbt sparkIT`.
