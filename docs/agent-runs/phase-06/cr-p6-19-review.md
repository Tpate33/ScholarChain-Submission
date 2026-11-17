# Phase 06 CR-P6-19: code-reviewer review of SP-P6-02 step 1A (2026-11-16)

- Scope: uncommitted modules/spark/** on HEAD 5e176eb, before the first archive-backed `sbt sparkIT` run.
- Reviewer tools: Read, Grep, Glob only.

## Verdict: REQUEST_CHANGES

All findings are owned by `spark-engineer`.

| ID | Severity | Location | Failure input | Impact |
|---|---|---|---|---|
| F1 | major | `input/VerifiedArchiveReader.scala:305` (decision inputRunId = curated), `:306-323` (snapshot-set equality), `:269-273` (run ID vs directory, sole binding for the curated run); `testkit/RunFixtures.scala:313-321` has no mutations for these; `VerifiedArchiveReaderSuite.scala:134,145-159` | delete line 305, the snapshot comparisons, or lines 269-273: all 41 routine tests and the IT still pass | run-chain provenance checks unprotected; breaks the feature-test invariant; the report claim "every refusal has its own feature test" (sp-p6-02-step-1a-report.md:68) is false |
| F2 | minor | untested: no match-decision part (`VerifiedArchiveReader.scala:241-247`), `maxTreeEntries` (`RunTree.scala:151`), special file kind Other (`VerifiedArchiveReader.scala:188-193`), curated run with `_parts.json` (`:214-218`); test 9 driver-bound (`VerifiedArchiveReaderSuite.scala:163-167`) cannot show bound-before-decode | delete any branch, or swap bound and decode (`:150-151`): tests still pass | guards unprotected; "refuses before decoding" not tested |
| F3 | minor | `RunTree.scala:178-181` `readUtf8` unbounded `readAllBytes`; used at `VerifiedArchiveReader.scala:220,259,358`; bound at `:340-341` counts rows only | a verified part declaring 10 rows of 500 MB each | driver memory not bounded as documented (not exposed at dev scale) |
| F4 | minor | `output/RunPublisher.scala:133-139`: move outside try; post-rename verify failure leaves `run=<hex>` with `_SUCCESS`; ATOMIC_MOVE replaces an empty racing target | a part changed between verifications, or a concurrent empty `run=<hex>` | a run that failed re-verification looks published; no-overwrite race |
| F5 | minor | `VerifiedArchiveReader.scala:172-173`, `RunTree.scala:119-124`: `names` returns empty on IOException | parent directory mode 0311 holding `.staging-x` | staging-entry rule silently skipped (fails open) |
| F6 | nit | `VerifiedArchiveReader` Scaladoc `:102-103`, report lines 44-58 | n/a | stage order wording inaccurate (part-manifest and run-manifest interleave per run); CR-P6-14 nit 3 still holds |

## Found sound (reviewer)

Archive-backed suite not tautological (core `checkEdges` recomputes independently; counts compared with dev-run-01 tables); only `scholarchain.sparkit.*` properties; writes only under outputDir and java.io.tmpdir; no ignore/assume/flaky; beforeAll failure fails every test; abstract base not discovered by routine Test (41 count matches). Unpinned codeVersion marked `codeVersionStatus=unpinned-sparkit-dev`. Edge semantics match AnalysisValidation. Lineage and provenance on every edge. N1 StructType check before and after writing and in verifyPublished, tested. Benchmark list canonical and bound to `run:84dd896f…d7d2`. No-follow, bounded tree walk. No hardcoded paths, buckets or thresholds. Module boundary: core only; no gold/, target/streams, held-out or `/Users/` strings.

## Unverified concerns (reviewer)

- Hand counts (112/163/182, 64/96/107, 259 edges, 30 weight-2) not checkable without the archive; Hadoop `coauthorPairs=182` counts mention pairs, a loose match only.
- Archive shape unconfirmed: decision manifest key `inputRunId`; equal `inputSnapshots` sets across runs; `maxTreeDepth=4` sufficiency (reader refuses an empty directory at depth 4, stricter than the sbt preflight).
- Thin IT subclass discovery through a binary superclass: unobserved until `sbt sparkIT`.
- 120 s bound headroom unmeasured.
- `sc.textFile` comma-joined URIs and Hadoop globbing: configured segments exclude `,{}[]*?`; `user.home` unconstrained (plain here).
- Reviewer could not run git; build match with e8c6ecc rests on the orchestrator (`git diff --stat e8c6ecc -- build.sbt project`: no output).

## Reviewer next gate

spark-engineer repairs F1 (required) and preferably F2-F6 with tests that fail without each check, reruns `sbt check`; focused re-review CR-P6-20; build re-confirmed against e8c6ecc; then the single `sbt sparkIT` run.

## Orchestrator note

Per the human's instruction, `sbt sparkIT` runs only after APPROVE. Not run. Findings are presented to the human before any repair is routed.
