# Phase 06 CR-P6-22: code-reviewer review of BE-P6-05, SparkIT classpath repair (2026-11-17)

- Scope: uncommitted build.sbt (+/-30) and project/SparkItRuns.scala (+102) against HEAD 7244398258894dbe400ae3811cd1c0ca9d9d6322 (+123/-9 total).
- Reviewer tools: Read, Grep, Glob. No diff tool; no sbt replay.

## Verdict: APPROVE

No critical, major or minor findings.

| Check | Reviewer result |
|---|---|
| Diagnosis and fix | `SparkIT = config("sparkit").extend(Test)` (build.sbt:125), `Defaults.testSettings` (:321), Spark `% Provided` (:316); custom config never reaches `provided` through extend, matching sparkit-run-02.md. One-line fix `SparkIT / classpathConfiguration := Configurations.TestInternal` (:332), after testSettings; minimal, scoped to sparkAnalytics/SparkIT, no new dependency or resolution; Test already resolves the same classpath |
| Runner | runner uses `(sparkAnalytics / SparkIT / fullClasspath)` (build.sbt:497), built from dependencyClasspath on classpathConfiguration; Compile, Test, Runtime unchanged |
| Job jar | `jobAssembly` (build.sbt:221-233) overrides only jar name and merge strategy; no `assembly / fullClasspath` override, so default Runtime (no Provided); `packageJobs` (:559) unchanged; Provided at :298-299, :316 |
| Compile gate | `CompileCheckStep = "sparkAnalytics/SparkIT/compile"` (SparkItRuns.scala:177) after Test/compile, before verify tasks; markTestRun last (build.sbt:544-557); preflight, archive properties and attempt creation reached only from the `sparkIT` root task (:482-504); compile cannot run SparkIT/test or sparkIT |
| Guards | new code: docs 73-75, constants 173-180, `classpathWiringProblems`/`compileGateProblems` 1420-1475, 5 cases 1614-1646; RefusedRoutes overrides (build.sbt:333-345), TimeoutSeconds 120, StrippedPrefixes, PinnedCuratedRunHex, pass rule in place; counter-tests meaningful; 175 = 170 + 5 |
| Isolation | only build.sbt and project/SparkItRuns.scala modified; no archive, gold/, held-out, AWS, modules/**, .claude/** change; no version literal |
| -Werror | acceptable: -Werror (build.sbt:207) already applied to SparkIT under sparkIT; check surfaces it earlier |

## Nits (non-blocking, owner build-engineer)

- N1 (SparkItRuns.scala:1441): `classpathWiringProblems` checks the build.sbt text, not the resolved value; e.g. `Configurations.TestInternal.withExtendsConfigs(Vector())` would pass. Optional hardening: compare the resolved `classpathConfiguration` (name `test-internal`).
- N2 (SparkItRuns.scala:1469): the `sparkIT` word check is case-sensitive while the run-capable key check is lowercased; negligible.

## Unverified concerns (reviewer)

(a) Spark jars on the forked runner's classpath at runtime (inferred, first exercised by sparkIT run 03); (b) job-jar contents (inferred from sbt-assembly default; optional human check: `sbt packageJobs` and list the jar for org/apache/spark or org/apache/hadoop classes); (c) the 170 earlier guard cases byte-identical to HEAD (case count and line accounting only); (d) the reported sbt check output (not replayed).
