# Phase 06 requirement checklist (orchestrator, maintained)

Source: the Phase 6 work order, prompt.md (verbatim course requirements). Status values: DONE-LOCAL (implemented, tested in sbt check and passing the archive-backed dev sparkIT), PARTIAL, OPEN, AWS-GATE (needs human approval of AWS spending). Updated 2026-11-17 after sparkit-run-07.md; consolidated review CR-P6-25 pending, so every DONE-LOCAL is subject to it.

| # | Requirement (prompt.md) | Status | Evidence |
|---|---|---|---|
| 1 | Canonical Researcher, Work, AUTHORED, Topic, Institution, Venue tables; unresolved mentions preserved; authorship edges point to existing nodes | DONE-LOCAL | de-p6-03-report.md (graph-node.v2/graph-edge.v2, checkStructure/checkTables); sp-p6-03-step-2b-report.md; run 07: 459 nodes, 799 edges, unmatched 0/0/0 |
| 2 | Temporal weighted coauthorship graph | DONE-LOCAL | steps 1A; runs 04-07 (259 edges) |
| 3 | P, C, T, A, M, W per researcher and window, works for M | DONE-LOCAL | step 1B; runs 05-07 (176 rows) |
| 4 | Connected components, size, largest-component fraction, singletons | DONE-LOCAL | step 2A, window-structure.v1 (2B); run 07: 31 components, largest 8/112 (0.071), 1 singleton (1970-2026) |
| 5 | Shortest paths for selected pairs; bounded path evidence; component path-length estimate; no driver all-pairs | DONE-LOCAL | step 2C (distributed BFS, exact top-K; sampled-bfs estimate); run 07: 9 stored paths, 37 estimates (all exact at dev size) |
| 6 | Communities (label propagation), membership, size, cross-community edges | DONE-LOCAL | step 2A/2B; run 07: 32 communities, 6 cross-community edges (1970-2026), converged in 3 |
| 7 | Bridge measure, parameters, distinct adjacent communities | DONE-LOCAL | step 2A (cross-community degree, exact; no betweenness) |
| 8 | k-core coreness, hand-checkable tests | DONE-LOCAL | step 2A; run 07 max coreness 4 |
| 9 | Temporal analysis across windows, inclusion-rule limits stated | DONE-LOCAL | step 2C (window-comparison.v1, researcher-temporal-change.v1); run 07: 112 changes, limitations recorded |
| 10 | Semantic-context inputs from computed data only | DONE-LOCAL | step 2C; run 07: 236 summary inputs, 13 entity summaries |
| 11 | Partitioned Parquet tables; load manifests for Neptune, DynamoDB, S3 Vectors, S3 | DONE-LOCAL (interpretation) | step 2C; run 07: 16 Parquet + 12 CSV datasets, 4 load manifests. "Partitioned" = part-file directories, no Hive-style partition columns (flagged to the human) |
| O1 | Partitioning and shuffle boundaries explained | DONE-LOCAL | step reports 2A, 2B, 2C |
| O2 | No full-graph collect on the driver | DONE-LOCAL (review pending) | step reports; driver validators are dev-scale only |
| O3 | Checkpointing for iterative algorithms | DONE-LOCAL | steps 2A, 2C |
| O4 | Counts and invariant failures emitted | DONE-LOCAL | printed summaries; refusals tested |
| O5 | Reproducible from a run manifest | PARTIAL | logical reproducibility of every dataset within one JVM (run 07: 28/28); Parquet bytes not reproducible (accepted limit); cross-JVM untested; codeVersion unpinned in sparkIT |
| G1 | sbt check | DONE (agent, reported) | sparkit-run-07.md: 1517 passed, 0 failed; final human run pending |
| G2 | Local Spark integration test | DONE | sparkit-run-07.md: 14 tests PASS, 32 s |
| G3 | Bounded EMR Spark run | AWS-GATE | emr-plan-draft.md |
| R1 | Report stage DAGs, partition counts, shuffle metrics, graph/component/community counts, metric distributions, path tests, windows, rejected violations, output paths | PARTIAL | counts, distributions, paths, windows, outputs: runs 06-07. Stage DAGs and shuffle metrics (Spark UI/event log) not captured locally; planned for the EMR run |

Carried findings: CR-P6-22 N1, N2; CR-P6-23 F1, F2; CR-P6-24 m1, n1-n4. CR-P6-21 m1, n1, n2 closed in BE-P6-07 and step 2B (review to confirm). Cross-JVM reproducibility open.
