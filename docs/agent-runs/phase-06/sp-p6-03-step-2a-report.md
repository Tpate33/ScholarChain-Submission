# Phase 06 SP-P6-03 step 2A: spark-engineer report, components, communities, bridges, coreness (2026-11-17)

- Work order: prompt-sp-p6-03-consolidated.md (human, verbatim), step 2A. HEAD c2d9a82 (uncommitted work).
- STATUS: COMPLETE. No blocker. `sbt sparkIT` not run (scheduled after 2C).

## Orchestrator observations

- `git status --porcelain`: 7 modified modules/spark files (it DevArchiveGraphFoundationSuite.scala doc comment; main AnalysisRun.scala, CoauthorEdgeRows.scala; test LogicalReproducibilitySuite.scala, CoauthorEdgeJobSuite.scala, ResearcherMetricsJobSuite.scala, testkit DevArchiveGraphFoundationChecks.scala); 5 new (main graph/GraphStructureJob.scala, graph/StructureRecordsJob.scala, output/StructureRows.scala; test graph/GraphStructureJobSuite.scala, analysis/GraphStructureRunSuite.scala). No core, build or other-module change.
- **Audit event.** spark-engineer ran its final `sbt check` in the background. The marker update therefore landed after its Bash call ended, and the orchestrator's next Bash call was refused by `audit-bash-writes.sh` as UNATTRIBUTED (`.claude/.last-test-run`, owner GENERATED). The orchestrator Read the marker: `written_by=sbt markTestRun`, `written_at_utc=2026-11-17T18:45:57.888881Z`, `git_head=c2d9a82…`, `git_worktree=dirty`, `suites=98`, `tests_passed=1393`, `tests_failed=0`, `tests_errored=0`. It agrees with the reported `markTestRun` line and `[success] … completed Nov 17, 2026, 12:45:57 PM` (18:45:57Z). The baseline was reset by the refusal and the call re-run. Consequence for evidence: this sbt check is recorded as reported, not audit-attributed. The consolidated `sbt check` after step 2C must run in the foreground, and its report must quote the verifySparkItGuards and verifySparkItRunner lines (not extracted this time).
- `.claude/hooks/verify-tests-ran.sh --check`: OK.

## Agent report (key fields)

DESIGN (per window, key (window index, researcher ID), UTF-8 order; only counts and per-window maps reach the driver):
- Partitioning: one HashPartitioner on the vertex key; arcs distinct in both directions, partitionBy, persisted, so joins with vertex-keyed RDDs are narrow. Each iteration persisted (MEMORY_AND_DISK), previous unpersisted, materialized with a count that must equal the vertex count.
- Checkpointing: new required `AnalysisJobConfig.checkpointing(dir, interval)`, outside the run identity; absolute local path or file/hdfs/s3/s3a URI; local dir overlapping the output root refused; reliable checkpoint every interval-th iteration; subdirectory deleted after publication. sparkIT: `<suite tmp>/checkpoints` under the attempt's tmp/.
- Integrity refusals before any algorithm: self-pair, non-canonical key, unknown window, dangling endpoint. Cross-checks: one structure record per metrics record; window edge/researcher counts equal the edge job's.
- Components: hash-min label propagation to the smallest member (= `ComponentId.of` anchor); exact; bound vertices+1 iterations.
- Communities: synchronous label propagation with self vote, ties to the smallest label; `community.maxIterations`; deterministic (a `community.randomSeed` is refused); non-converged windows keep last labels, recorded converged=false. Outcome per window recorded in the run manifest as computed parameters `community.converged.<start>-<end>`, `community.iterations.<start>-<end>` (reserved prefix). Only `label-propagation` accepted.
- Bridge `cross-community-degree`: degree, cross-community degree, distinct other communities reached, sum of squares for `BridgeMeasure.participation`; exact; `bridge.*` parameters recorded, unused.
- Coreness: distributed peeling; exact; at most vertices rounds.
- Parquet: record `parameters` map stored as ascending `array<struct<key,value>>` (group-by needs it).
- Run now publishes six datasets: edges, metrics, components, communities, bridges, coreness. `outputs=coauthor-edges,researcher-metrics,component-memberships,community-memberships,bridge-measures,coreness`, so the run ID changes again.

Fixture hand table (GraphStructureJobSuite; one graph per window):

| Window | Graph | Components | Communities, converged at | Coreness |
|---|---|---|---|---|
| 0 | ab cd, e alone | {a,b} {c,d} {e} | same 3; 2 | a b c d 1, e 0 |
| 1 | triangle plus pendant ab ac bc cd | all a | one; 3 | a b c 2, d 1 |
| 2 | two K4 plus bridge de | all a | {abcd} {efgh}; 3 | all 3 |
| 3 | tie case ab ac bc ef eg fg cz ez | all a | {a,b,c,z} {e,f,g}; 3 | all 2 |
| 4 | K4(abcd), ef eg fg ag eh, i alone | {a..h} {i} | {a,b,c,d,g} {e,f,h} {i}; 2 | i 0, h 1, e f g 2, a b c d 3 |
| 5 | hub z plus three triangles | all a | {a,b,c,z} {d,e,f} {g,h,i}; 3 | all 2 |

Bridge counts (degree, cross, reached, sum of squares): w2 d and e (4,1,1,10), pc 0.375; w3 z (2,1,1,2), e (3,1,1,5); w4 g (3,2,1,5), e (3,1,1,5), f (2,1,1,2); w5 z (3,2,2,3), f and i (3,1,1,5). Window totals (researchers, edges, components, largest, singletons): (5,2,3,2,1), (4,4,1,4,0), (8,13,1,8,0), (7,8,1,7,0), (9,11,2,8,1), (10,12,1,10,0). Also: partition/order invariance at 3 partitions, refusal tests, and a 60-vertex pseudo-random graph against sequential reference implementations. GraphStructureRunSuite: MetricsFixtures end to end, validators checkComponents/checkCommunities/checkBridges/checkCoreness/checkRunChain clean, counter-tests detected, refusals publish nothing.

IT extensions (not yet run): 176 records per new dataset; structure counts 112/163 and 64/96; solo 0000-0002-2664-4694 singleton, coreness 0; sc 6ce3728b… coreness 3 (K4); orcid 0000-0001-5952-232X coreness 4 in 2020-2024 (K5); max coreness >= 4; validators pass; two builds (1 and 3 partitions) logically equal for all six datasets. IT now 10 tests.

Item-1 inventory (course item 1, read only): core has generic `GraphNode` (Graph.scala:12-47; labels Researcher, AuthorMention, Work, Venue, Institution, Topic) and `GraphEdge` (:57-137; AUTHORED Researcher|Mention to Work, RESOLVES_TO, PUBLISHED_IN, AFFILIATED_WITH, HAS_TOPIC); `AuthorshipRecordV2` (AuthorshipV2.scala:40-132) identified mentions only; unresolved mentions preserved in the grouping run. Missing: (1) no Spark job builds node tables or AUTHORED edges; (2) no reader decodes the curated parts (CR-P6-21 m1); (3) no core validator that every edge endpoint is an existing node with unresolved mention nodes kept, and `AnalysisOutputs` (AnalysisValidation.scala:22-32) has no node/edge-table fields; (4) core does not say whether these tables are generic GraphNode/GraphEdge or dedicated records, nor their properties. (3) and (4) need domain-engineer.

TEST RESULTS (sbt check, verbatim as reported): core 557, entityResolution 464, hadoop 85, sparkAnalytics 142 (123 + 9 GraphStructureJobSuite + 10 GraphStructureRunSuite), tests 145; rag, root, services, storage 0.

```
[info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=c2d9a8216b945f2dd57eb587099f0b938ed1d670, worktree=dirty, suites=98, passed=1393, skipped=0)
[success] Total time: 168 s (0:02:48.0), completed Nov 17, 2026, 12:45:57 PM
```

Reproducibility (fixture): all six datasets logicalEqual(p1,p3)=true, logicalEqual(p1,p1)=true; sameBytes(p1,p1)=true, sameBytes(p1,p3)=false (diagnosis only).

RISKS (as reported): largest-component fraction, singletons, cross-community edges per window and convergence detail are run-result fields only (core `AnalysisWindow` has no fields; domain-engineer); no per-community cross-community edge count in records (derivable); IT assertions never run on the archive; 120 s sparkIT bound with two builds of iterative jobs unobserved; `setCheckpointDir` global per SparkContext; checkpoint cleanup failure after publication reported as Left; label propagation has no quality guarantee and communities need not be connected; validators on the driver.
