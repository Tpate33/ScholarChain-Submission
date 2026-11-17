# Phase 06 CR-P6-21: code-reviewer review of the combined curated-archive compatibility repair (2026-11-17)

- Scope: project/SparkItRuns.scala (BE-P6-04, the only build change vs e8c6ecc: +669/-25) and modules/spark/** (SP-P6-02 step 1A, approved at CR-P6-20, plus repair 02: HadoopCuratedRun.scala, VerifiedArchiveReader.scala, RunTree.scala, RunFixtures.scala, DevArchiveGraphFoundationChecks.scala, VerifiedArchiveReaderSuite.scala). HEAD 5e176eb.
- Reviewer tools: Read, Grep, Glob. Contract checked against modules/hadoop producer code (Pipeline, RejectRecord, Counters, BlockingJob, CandidateJob, NormalizeJob, Shuffle). Test results not observed by the reviewer.

## Verdict: APPROVE

| Requirement | Reviewer result |
|---|---|
| Pin, curated only | preflight constant `PinnedCuratedRunHex` (SparkItRuns.scala:120-122) checked in parseConfig (:352) and layoutProblems (:477-484), self-tests :1651-1655, :2270-2278; reader `HadoopCuratedRun.locationProblems` (:123-144) refuses as CuratedRun and stops other checks (VerifiedArchiveReader:237); only real caller passes a committed constant (DevArchiveGraphFoundationChecks:53-54, 88); old curated layout now refused |
| `.DS_Store` | refused in the curated run by both (SparkItRuns:721-722; HadoopCuratedRun:216-217; tests SparkItRuns:2164, Suite:368-372); decision/grouping tolerance in the preflight unchanged (:453-457, :2094-2095) |
| Same contract | structure matches line for line; differing routes still refuse in both; same 20000 entry bound; reader enforces rule 2 (VerifiedArchiveReader:331-352) and rule 4 (matches Pipeline.writeReport :698-711, Counters.scala:26-37); rules 7/8 content explicitly not enforced and documented (HadoopCuratedRun:38-44; VerifiedArchiveReader:149-151); no producer layout refused |
| Unchanged protections | decision/grouping `_SUCCESS`, `_UNVERIFIED`, `_parts.json`, MaxRunDepth 4 (SparkItRuns:485-493); run-ID chain (VerifiedArchiveReader:449-488); NOFOLLOW traversal (RunTree:168-196); segment refusals (SparkItRuns:272-288); StrippedPrefixes; verdict; TimeoutSeconds 120 (compared with CR-P6-16/CR-P6-19 descriptions; no diff available to the reviewer) |
| Fixtures | four valid layouts accepted in both; incomplete input refused in both; assertions specific enough to fail on removal (judged from assertions; no mutation run) |
| Isolation | no archive write, no marker, no ER rerun; no gold/, held-out, target/streams, `/Users/` or AWS strings in modules/spark/src; verifySparkItGuards only stats the archive root (:2527-2550) |
| Module boundary | no hadoop or entityresolution import; HadoopCuratedRun imports core and ujson (core's uPickle); restated names cite the producer |

## Findings (non-blocking)

- m1 (minor; build-engineer SparkItRuns.scala:827-851, spark-engineer HadoopCuratedRun.scala:317-353): rules 5 and 6 are "only if"; both accept truncated layouts the producer cannot make (non-empty `candidates/join/joined` without `pairs/`, Pipeline.scala:646-666; non-empty `round-0/spill` without `round-1`, Pipeline.scala:517-521). No step-1A output affected (no curated part consumed). Must become "iff" before any step reads curated parts.
- n1 (nit; spark-engineer VerifiedArchiveReader.scala:17-23, :216-223): no committed pin in main code; a future caller passing the directory's own hex would generalise the exception. No impact today.
- n2 (nit; spark-engineer HadoopCuratedRun.scala:399-400, :425, :221; build-engineer SparkItRuns.scala:727): untested branches: zero or multiple DBLP snapshots; fractional or >2^53 counter; a `.<X>.crc` that is a directory.

## Pre-existing divergence

Preflight tolerates `.DS_Store` inside the decision/grouping runs; the reader's `PartManifests.verify` refuses it as unlisted. Fails closed (no false pass). Matters for the pinned archive only if a `.DS_Store` exists inside `er-decisions/run=736cb170…` or `er-assignments/run=84dd896f…`; then sparkIT would pass the preflight and FAIL at [part-manifest]. Reviewer recommendation: before the retry the human runs read-only `find ~/ScholarChain-archive/phase-06/dev-run-01 -name .DS_Store -print` in a terminal; any hit needs a human decision (archive not to be modified).

## Unverified concerns (reviewer)

Real archive conformance to rules 1-11; reported test counts (109/170) and sbt check; no mutation tests; no diff against e8c6ecc available to the reviewer; 120 s bound against the deeper curated walk; ujson used transitively via core, not declared on sparkAnalytics.
