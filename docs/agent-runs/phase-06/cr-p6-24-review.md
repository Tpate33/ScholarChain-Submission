# Phase 06 CR-P6-24: code-reviewer review of SP-P6-02 step 1B (2026-11-17)

- Scope: uncommitted modules/spark changes against HEAD f834c603b0db1965914cf779b1f6bdffa7a426ab (8 modified, 6 new files; sp-p6-02-step-1b-report.md).
- Reviewer tools: Read, Grep, Glob. Read the archived grouping `_parts.json` and `teams/part-00000.jsonl` under ~/ScholarChain-archive/phase-06/dev-run-01 (read only); no gold/ or held-out path. No sbt, no git diff.

## Verdict: APPROVE

No critical or major findings.

## Recomputed by the reviewer (match)

- MetricsFixtures table: 2015-2019 A 2/5/3/2/2.5/3[m2]/C2/W2, B, C, E, F; 2020-2024 A, B (7/3, tie [m6,m9]), C, D; window totals 5/6/12/8/5/4/4/3 and 4/10/24/4/4/12/20/3; identities A 0.91/6, B 0.88/4, C 0.95/3 with collision code, D 0.93/3, E singleton-t1-anchored and F singleton, both unknown.
- IT counts from the 41 archived teams: team sizes 1x1, 12x2, 10x3, 10x4, 8x5 overall; 1, 8, 5, 4, 6 in 2020-2024. Sum P 135 and 78; sum T = sum n(n-1) = 364 and 214 = 2 x 182 and 2 x 107; sum C = 2 x 163 = 326 and 2 x 96 = 192; W = T since U = 0; max M 4.
- Four named researchers (Thabtah20 solo; AlemparteCSW74/75 same four-person team twice; Lu and Diehl, 7 distinct coauthors each) and their maximumTeamWorkIds UTF-8 order.

## Checked, no defect

Metrics match Analysis.scala:505-520 and AnalysisValidation.scala:453-493 (counted works only, window filter, member-of-counted copy excluded, yearless works in no window, solo M 0, unresolved never in C or W; W = T - U enforced per record, Analysis.scala:586). IdentityJob passes exactly the supporting-decision confidences to IdentityConfidence.derive, keeps unknown explicit, refuses duplicate decision IDs, no ER rerun. One staged RunPublisher.publish for edges and metrics; `outputs` not overridable (tested); create-once and `_UNVERIFIED` unchanged. Reproducibility is a full multiset comparison (group by all columns with count, except both ways); >1 part asserted; duplicate-count negative control real; cannot pass on empty output. RunFixtures default change does not weaken reader refusals. Isolation clean.

## Findings (spark-engineer unless noted)

- m1 (minor; LogicalReproducibility.scala:13-15, :75-80): claims StructType comparison covers nullability at every level, but both sides are read with `spark.read.parquet` (file sources come back nullable, cf. RunPublisherSuite.scala:183) and, without mergeSchema, from one footer. A nullable vs non-nullable write, or one part with a different footer schema, still gives sameSchema=true. Per-part layout is still caught by `RunPublisher.verifyPublished`. Fix: correct the claim or compare footer schemas per part.
- n1 (nit; LogicalReproducibility.scala:57-60): `fold(identity, identity)` turns read errors into strings, so two parts failing with the same message give sameBytes=true (diagnosis flag only).
- n2 (nit; AnalysisRun.scala:81-82, :108): `outputs` chain parameter defined only in spark, not in core `AnalysisValidation.AnalysisManifestKeys` (AnalysisValidation.scala:1166-1173); if it should be a contract key, that is a domain-engineer request.
- n3 (nit; ResearcherMetricsJob.scala:291-294, IdentityJob :90-93): a dangling supporting decision refuses the run only when that researcher has a metrics record; a resolved researcher only on yearless or uncounted works with a dangling decision is accepted. Matches core checkIdentity (iterates metrics only, AnalysisValidation.scala:555); no published identity affected.
- n4 (nit; sp-p6-02-step-1b-report.md:58): per-suite sparkAnalytics breakdown (11+3+81+11+7+4+3) sums to 120, not the reported 123; the 1374 grand total is consistent with 123. Orchestrator note: the breakdown was transcribed from spark-engineer's report; the verbatim `Passed: Total 123` line and the markTestRun total stand; one per-suite figure in the agent's table is misstated.

## Risks and unverified concerns (reviewer)

- Reproducibility checks compare builds inside one JVM only; the run 03/04 byte difference was across JVMs and stays undiagnosed.
- The `outputs` parameter changes the analysis run ID, so every row's `provenance.runId` differs from run 04's; run-04 edges cannot be compared row for row with new output. No step builds on run-04 output today.
- Archive-backed IT not run; 120 s bound with two builds not observed.
- TOCTOU carried from 1A: IdentityJob and countedTeams re-read parts after driver verification; decisions RDD not persisted (read about four times).
- CR-P6-21 m1, n1, n2 still open (no curated parts read in 1B).
