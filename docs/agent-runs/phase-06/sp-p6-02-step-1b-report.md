# Phase 06 SP-P6-02 step 1B: spark-engineer report, researcher collaboration and team-size metrics (2026-11-17)

- Work order: prompt-sp-p6-02-step-1b.md (human, verbatim). Builds on step 1A (committed; sparkIT run 04 PASS at f834c60).
- STATUS: COMPLETE. `sbt sparkIT` not run; nothing archive-backed in this step observed yet.

## Orchestrator observations

- `git diff --stat`: 8 modified modules/spark files, `396 insertions(+), 59 deletions(-)`: src/it DevArchiveGraphFoundationSuite.scala (12), main AnalysisRun.scala (164), CoauthorEdgeJob.scala (4), CoauthorEdgeRows.scala (5), test CoauthorEdgeJobSuite.scala (4), RunPublisherSuite.scala (8), testkit DevArchiveGraphFoundationChecks.scala (195), RunFixtures.scala (63).
- Untracked modules/spark: main graph/ResearcherMetricsJob.scala, output/ResearcherMetricsRows.scala; test analysis/LogicalReproducibilitySuite.scala, graph/ResearcherMetricsJobSuite.scala, testkit/LogicalReproducibility.scala, testkit/MetricsFixtures.scala. Untracked evidence: prompt-sp-p6-02-step-1b.md, prompt-sparkit-run-04.md, sparkit-run-04.md.
- No build.sbt, project/, modules/core or other module change.
- `.claude/hooks/verify-tests-ran.sh --check`: OK.

## Agent report (key fields, output verbatim)

DESIGN: `AnalysisRun.runCollaboration` (replaces `runEdges`; `CollaborationRunResult` adds metricsCount, metricsSummaries) publishes `edges/` and `metrics/` as one staged, verified run; new run-manifest chain parameter `outputs=coauthor-edges,researcher-metrics` (caller cannot replace it), so the run ID differs from step-1A edge-only runs. `IdentityJob` distributes `IdentityConfidence.derive`; `ResearcherMetricsJob` computes ResearcherMetricsV2; `ResearcherMetricsRows` gives the Parquet layout with explicit unknown confidence. C_r and W_r from published edges, T and U from teams; the record contract enforces W = T - U, so the two paths must agree or the run is refused. `RunFixtures` default chain now points resolved members to a real decision of the decision run (was a fake ID).

Metric table (contract modules/core/src/main/scala/scholarchain/core/Analysis.scala; executable definition AnalysisValidation.scala:435-500), hand-derived fixture values:

| Metric | Contract | A 2015-19 | B 2020-24 | E (only unresolved coauthors) | F (solo) |
|---|---|---|---|---|---|
| P_r publicationCount | :509 | 2 | 3 | 1 | 1 |
| T_r totalCoauthorOccurrences | :510 | 5 | 7 | 2 | 0 |
| unresolvedCoauthorOccurrences | :511 | 3 | 1 | 2 | 0 |
| worksWithUnresolvedCoauthors | :512 | 2 | 1 | 1 | 0 |
| A_r averageCoauthorsPerPaper | :513-514, :550 | 2.5 | 7/3 | 2.0 | 0.0 |
| M_r, maximumTeamWorkIds | :515-516 | 3, [m2] | 3, [m6, m9] (tie) | 2, [m3] | 0, [m4] |
| C_r distinctCoauthorCount | :517 | 2 | 3 | 0 | 0 |
| W_r weightedDegree (= T - U, :586-590) | :518-520 | 2 | 6 | 0 | 0 |
| identity (C5) | :236-243, derive :323-378 | resolved, 6 members, measured 0.91 | resolved, 4, measured 0.88 | singleton-t1-anchored, unknown | singleton, unknown (no-supporting-decision) |

Other fixture cases: C collision code grouping.anchor-collision.orcid, measured 0.95; D measured 0.93, only in 2020-2024; G (only yearless m8) has no record; A's member-of-counted copy mc1 excluded. Window totals (records/P/T/U/Wu/C/W/max M): 5/6/12/8/5/4/4/3 and 4/10/24/4/4/12/20/3; team-level unresolved mentions 5 and 2.

IT expectations (hand-derived from the 41 archived teams; not yet run), records/P/T/U/Wu/C/W/max M: 1970-2026 = 112/135/364/0/0/326/364/4; 2020-2024 = 64/78/214/0/0/192/214/4; 176 metrics rows. Hand-derived researchers: orcid 0000-0002-2664-4694 (solo, both windows) P1 T0 A0.0 M0 C0 W0; sc 6ce3728b… (1970-2026 only) P2 T6 A3.0 M3 C3 W6; orcid 0000-0001-5952-232X P2 T8 M4 C7 W8 (1970-2026), P1 T4 C4 W4 (2020-2024); orcid 0000-0003-3922-8419 P2 T8 A4.0 M4 C7 W8 in both. Identity distribution computed only, checked by checkIdentity. The dev graph has no unresolved mentions; unresolved cases are covered by fixtures only. The agent read the archived grouping teams file and `_parts.json` (Read only) to derive these.

Logical reproducibility (carried forward): `LogicalReproducibility` compares StructType and the row multiset (group by every column with count) and reports the exact difference; three fixture runs at 1, 3 and 1 partitions: same run ID, schema and row multiset for edges and metrics.

```
SP-P6-02 step 1B reproducibility scholarchain.coauthor-edge.v2: rows=8 logicalEqual(p1,p3)=true logicalEqual(p1,p1)=true sameBytes(p1,p1)=true sameBytes(p1,p3)=false (diagnosis only)
```

(same line for researcher-metrics.v2, rows=9). Byte diagnosis: same JVM, same partitioning -> identical bytes; different partitioning -> different bytes; run 03 vs 04 (separate JVMs) still undiagnosed. The IT adds the same check on the archived input (two builds in-run).

Counter-tests: unresolved coauthor counted as identified refused by the record contract and checkMetrics; P_r including the uncounted copy detected; default identity confidence detected by checkIdentity; supporting decision missing from the decision run refuses the whole run, nothing published.

COMMANDS RUN: `git ls-files`; one `git grep` refused by the guard; `sbt sparkAnalytics/compile`, `sbt sparkAnalytics/Test/compile` (success); `sbt sparkAnalytics/test` (first: Total 111, Failed 2, fixture decision IDs duplicated, fixed; then Total 123, Failed 0); `sbt check` once, last; `git status --porcelain`.

TEST RESULTS (sbt check, verbatim):

```
[info] compiling 1 Scala source to /Users/taufeeqpatel/Desktop/ScholarChain/modules/spark/target/scala-3.3.6/sparkit-classes ...
[info] done compiling
[info] verifySparkItGuards: PASS: 197 cases gave the expected verdict
[info] verifySparkItRunner: PASS: 8 cases gave the expected verdict with the real runner on the SparkIT classpath (JUnit /Users/taufeeqpatel/Library/Caches/Coursier/v1/https/repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar); scratch target/sparkit-runner-check/ cleaned
[info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=f834c603b0db1965914cf779b1f6bdffa7a426ab, worktree=dirty, suites=96, passed=1374, skipped=0)
[success] Total time: 149 s (0:02:29.0), completed Nov 17, 2026, 10:55:49 AM
```

Passed: core 557, entityResolution 464, hadoop 85, sparkAnalytics 123 (RunPublisherSuite 11, SparkParquetCompatibilitySuite 3, VerifiedArchiveReaderSuite 81, ResearcherMetricsJobSuite 11 new, CoauthorEdgeJobSuite 7, DevInputsSuite 4, LogicalReproducibilitySuite 3 new), tests 145; rag, root, services, storage 0; Failed 0, Errors 0. IT suite now 9 tests.

RISKS (as reported): archive-backed IT not run (hand counts could be wrong; would fail honestly); no mutation runs; run 03/04 byte difference undiagnosed; IT builds twice against the 120 s bound (run 04 took 5 s; not observed); RunFixtures default change may matter to future reader tests; codeVersion unpinned in the IT; core validators run on the driver.

NEXT GATE: one focused code review (MetricsFixtures hand table, IdentityJob, IT hand counts); then human approval for one `sbt sparkIT` at the reviewed commit (expected 9 IT tests and the reproducibility lines).
