# Phase 06 CR-P6-20: code-reviewer re-review of SP-P6-02 step 1A repair 01 (2026-11-16)

- Scope: uncommitted modules/spark/** on HEAD 5e176eb after repair-09 (CR-P6-19 F1-F6). Gates the first archive-backed `sbt sparkIT` run.
- Reviewer tools: Read, Grep, Glob only. Reviewer hand-counted VerifiedArchiveReaderSuite (38, 16 new) and RunPublisherSuite (11); matches the agent's report. Mutation rounds and `sbt check` output not observed by the reviewer.

## Verdict: APPROVE

No critical, major or minor findings.

| CR-P6-19 finding | Reviewer result |
|---|---|
| F1 | resolved: RunFixtures.Spec propagates run IDs; four `refusedExactly` tests (VerifiedArchiveReaderSuite 300-308, 310-319, 321-330, 258-271); each deletion traced, none masked; grouping overlap disclosed at Suite:252 |
| F2 | resolved: no match-decision part (203-212), maxTreeEntries (144-151), Kind.Other via treeRefusals (153-167), curated `_parts.json` pass and mismatch (214-230); order tests for row bound (437-444) and byte bound (446-452) return only DriverBound |
| F3 | resolved: ReaderLimits gains caller-supplied maxDriverValidationBytes and maxMetadataBytes (RunTree:83-89); readUtf8 NOFOLLOW size pre-check and maxBytes+1 read (RunTree:205-233); every use bounded (VAR:264, 305, 411; RunPublisher:390, 417); no readAllBytes/readString in main |
| F4 | resolved for the local runner: post-rename failure -> `_UNVERIFIED` (CREATE_NEW) then `_SUCCESS` deleted, refused by reader and verifyPublished; failed claim leaves destination untouched (fileKey checked); failed rename removes staging, claim only if empty; pre-existing destination refused; Scaladoc limitation (RunPublisher:92-102) accurate; mkdir claim plus single-writer assumption is the smallest safe remedy without native calls; PublishHooks `private[output]`, production passes Noop |
| F5 | resolved: RunTree.names returns Either; unlistable parent is a StagingEntry refusal, tested with mode -wx--x--x and precondition assert |
| F6 | resolved: Scaladoc (VAR:111-150, RunTree:15-22) matches the code |

Regression check against the CR-P6-19 "found sound" list: no regression; only `scholarchain.sparkit.*` properties; no gold/, target/streams, held-out or `/Users/` strings; no ignore/assume/flaky; main imports core only; IT class still a one-line subclass; IT limits (64 MiB, 1 MiB, 2000 rows vs 797 consumed dev rows) in test code only.

## Nits (spark-engineer; non-blocking, at the human's discretion)

- N1 RunPublisherSuite.scala:266-275: "refused before staging" cannot distinguish the pre-check (RunPublisher:180) from the claim (:224); pre-check unprotected (destination still never replaced).
- N2 RunTree.scala:220-221: grew-during-read branch untested (reachable only with a concurrent writer).
- N3 RunTree.scala:118-124: `kindOf` mapping to Kind.Other untested; `kindOf(Paths.get("/dev/null"))` would test it.
- N4 RunPublisher.scala:78-102: a crash between ATOMIC_MOVE (:233) and the end of verifyPublished (:247) is not documented; the run keeps `_SUCCESS` having passed staging verification only.
- N5 RunPublisher.scala:126-133 called outside try at :204, :228, :237: Files.walk UncheckedIOException escapes as an exception, not Left; fails closed.

## Unverified concerns (reviewer)

- Mutation results and `sbt check` output rest on the agent's report.
- Archive shape (decision key `inputRunId`, equal inputSnapshots, maxTreeDepth=4), hand counts, SparkIT discovery via a binary superclass, 120 s headroom, and IT limits against the real archive: unobserved until `sbt sparkIT`.
- The F5 test assumes a non-root user (fails loudly under root).
