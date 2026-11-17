# Phase 06 CR-P6-23: code-reviewer review of BE-P6-06, SparkIT runner repair (2026-11-17)

- Scope: uncommitted build.sbt (22), project/SparkItJUnitRunner.java (228), project/SparkItRuns.scala (939), project/versions.properties (5) against HEAD 982eecf2f0fb66033a0e3d2f769161b20c4dcd3a (+1078/-116).
- Reviewer tools: Read, Grep, Glob. No diff tool; no sbt or javap.

## Verdict: APPROVE

One minor finding and two nits; none blocks.

| Check | Reviewer result |
|---|---|
| Runner | all reflective names resolved once before any test class loads (SparkItJUnitRunner.java:190-221), missing -> exit 2, no result file; `getIgnoredCount` gone from project/; failures written before counts (:252-294); stage exceptions write error lines, earlier failure lines kept; nonce read and file deleted before JUnit loads (:172-186), CREATE_NEW result bracketed by the nonce; System.exit -> no result file -> no PASS |
| Verdict | SparkItRuns.scala:1201-1333 refuses missing/incomplete/oversized/malformed data, v1, nonce mismatch, any failure line, count mismatch, orphan failure, any error line, run count 0, ignored, assumption failures, class-set mismatch, wrong JUnit path or version, non-zero exit, unconsumed nonce; PASS only with an empty problem list and successful parse (:1330) |
| JUnit provenance | :1102-1170, :1476; scans directories and jars, unreadable jar refuses; munit's junit-interface holds no JUnitCore/Result; one JUnit and one hamcrest on the 166-entry classpath; sparkIT checks after preflight, before any attempt directory |
| verifySparkItRunner | :3215-3449, build.sbt:526-534; same `runRunner` -> `LocalRuns.runForked` (StrippedPrefixes) and `verdict` as sparkIT (:1359-1393); classpath `sparkAnalytics/SparkIT/fullClasspath` plus fixtures; no scholarchain.sparkit.* properties, so no archive path; scratch under target/sparkit-runner-check, symlink and spark-it refused, deleted in finally (Glob: empty); after verifySparkItGuards, before markTestRun (build.sbt:574-576); missing javac fails closed; constant fixtures, `-proc:none -implicit:none` |
| Run 03 evidence | modules/spark/target/spark-it/ holds only attempt-1 with junit-result.txt and result.txt |
| Guards | preflight, route refusals, pinned curated run, segment/held-out refusals, env stripping, 120 s bound unchanged as read; 3 adjusted cases fit v2 and are not weaker; 22 new cases meaningful |
| Ledger | `junit=4.13.2` read only via `versionLedger.value("junit")` (build.sbt:504, :530); resolution unchanged |
| Isolation | only the 4 build files changed; SparkItRuns.scala growth is mostly the runner check (~330), new guard cases (~250) and v2 parse/verdict/provenance; no unrelated rewrite seen (no diff available) |

## Findings

- F1 (minor, build-engineer; SparkItRuns.scala:1277-1286, evidenceLines :1538): failure lines are reported only if the whole result file parses. A file over MaxResultBytes (1 MiB, :1187), e.g. 8+ classes with 100+ failures and long messages, yields only "junit-result.txt is larger than 1048576 bytes". Still FAILED (fail-closed); failures visible only in the sbt task log via the runner's `say` lines (SparkItJUnitRunner.java:270). Partly misses "report failures before other diagnostics can obscure them". Suggested fix: on strict-parse failure, extract `failure\t` lines leniently from the raw text and report them first, labelled as from an unverified file.
- F2 (nit, build-engineer; SparkItRuns.scala:1102-1103, SparkItJUnitRunner.java:226-229): provenance covers only JUnitCore.class and Result.class, not `org/junit/runner/notification/Failure` or the rest of org/junit; a shading jar earlier on the classpath would go undetected. No path to PASS found; not present on today's classpath.
- F3 (nit, ownership, human decision; project/versions.properties:26-30): build-engineer added a new ledger key and value; the file header assigns values to dependency-updater; gap G5 unresolved. Consistent with how existing keys were created; dependency-updater must keep `junit` in step with munit bumps.

## Unverified concerns (reviewer)

API signatures, case counts (8, 197) and the before/after of the 3 adjusted cases rest on current files and recorded output; munit 1.1.1 reporting `assume` and `.ignore` as assumption failures and ignored tests is not exercised (first evidence: run 04); munit `.only` may filter tests without reporting them as ignored (none of `.only`, `.ignore`, `assume(`, `.flaky` under modules/spark/src/it today; for spark-engineer to note).
