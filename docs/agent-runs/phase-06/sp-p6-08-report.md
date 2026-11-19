# SP-P6-08: EMR partitions and loadId drift (spark-engineer final report, verbatim)

```
SP-P6-08 is analysis only. I changed no files and ran no sbt or AWS command.

Decision 1: set `partitions=2`. infra-engineer needs to write it into the run config (`spark-dev-run-01.conf`), which is their file.

Decision 2: the different `loadId` and `partManifestSha256` values are intended, not a defect. Nothing blocks the EMR run. The loader rules that follow from it go to Phase 7 (storage-engineer). A byte-reproducibility hardening is optional and low priority.

STATUS: COMPLETE
OWNER: spark-engineer
WORK ORDER: SP-P6-08 (Phase 6, analysis only): the `partitions` value for the bounded EMR run, and the explanation and classification of BE-P6-10 attempt-4 RISK 1 (`loadId` and `partManifestSha256` differ between `local[2]` and `local-cluster`).

FILES CREATED: none
FILES MODIFIED: none

COMMANDS RUN:
1. `git ls-files -- modules/spark/src/main`: succeeded.
2. `git ls-files -- modules/core/src/main`: succeeded.
3. `git grep -n -i -E "..." -- modules/spark/src/main`: refused by guard-dangerous.sh (a quote character is outside the allowed alphabet). Nothing ran. I used Read instead.
4. `git ls-files -- project modules/spark/src/sparkit modules/spark/src/it`: succeeded.
5. `git ls-files -- modules/spark/src/test/scala`: succeeded.

Files I read:
- the run config, `scripts/emr/submit-collaboration-job.sh` and the attempt-4 report
- `LoadManifests.scala`, `RunPublisher.scala`, `CollaborationJob.scala`, `AnalysisRun.scala`, `CoauthorEdgeJob.scala` and `TemporalJob.scala` (lines 1-200)
- core `StoreLoad.scala` and `ConfigLoader.scala`
- the test files `testkit/LogicalReproducibility.scala` and `analysis/LogicalReproducibilitySuite.scala`
- the attempt-4 evidence: `run-1-local/output.txt` and both published `_parts.json` files under `target/spark-submit-local/attempt-4/run-{1-local,2-local-cluster}/out/run=a65f3f38.../`

TEST RESULTS: none. The work order forbids sbt. I did not observe any `sbt check` at this HEAD.

VALIDATION RESULTS:

Decision 1: partitions = 2.

How the value is used:
- `--partitions` becomes `AnalysisJobConfig.partitions`. That is the partition count of every RDD shuffle (`aggregateByKey`, `join`, `partitionBy` with `HashPartitioner(P)`, `sortBy` range partitioning) and the minimum number of input splits for `textFile`.
- The job adds no DataFrame shuffle: frames come from `createDataFrame` over P-partition RDDs and are written directly. So `spark.sql.shuffle.partitions` and AQE do not matter.
- P is not part of the run manifest. The `AnalysisRun.manifest` chain is the benchmark digest, decision, grouping and input run IDs, outputs and convergence, plus code version, config digest, snapshots and attempt. So P does not change the run ID.

Rationale for 2:
1. Comparability with the baseline. At P=2 the EMR run reproduces the local gate's physical shape:
   - every shuffled Parquet dataset and every Neptune CSV dataset gets two parts;
   - the driver-built datasets (`window-structures`, `analysis-windows`, `window-comparisons`) get one part each;
   - so the load-manifest item counts and item paths should equal the local baseline: dynamodb 21, neptune 24, s3 29, s3-vectors 4.
   Any other P changes the number of parts, and therefore the item counts, by design. `LogicalReproducibilitySuite` documents this: "The load manifests name other part paths per partitioning, but the same roles per store".
2. Logical content does not depend on P. That suite publishes one fixture at P=1, 3 and 1 and requires the same run ID and the same row multisets for all 16 Parquet and 12 CSV datasets. That is tested on fixtures only; on the dev archive it is so far observed only at P=2 (two topologies). Keeping P=2 means the cloud-vs-local comparison tests the topology change (YARN, HDFS checkpoints, S3A, separate executor JVMs) and not a partitioning change as well.
3. Matches the EMR layout. The wrapper runs `--num-executors 2 --executor-cores 1`, so there are two task slots. P=2 fills both in every shuffle stage, with one wave and no idle slot. Shuffles still cross executor JVMs, which is the condition that exposed the SP-P6-07 defect. With one core node, both executors are probably on the same host; I did not verify that.
   - P=1 would not exercise a cross-executor reduce.
   - P=4 or 8 adds no information on 259 edges. It multiplies the S3 objects that protocol B copies, re-reads and hashes on the driver, which uses up the 30-minute step budget. The 8 in the wrapper's `--self-check` is only a sample value.
4. Observability. Event logging is on (HDFS, copied create-once to the logs bucket). At P=2 every shuffle stage shows two tasks with their shuffle read and write metrics, so the stage DAGs and per-partition metrics can be read directly against the local event logs, which also used P=2. Input stages can have more than two tasks, because `textFile` splits at least once per input file.

What the cloud run should and should not match at P=2:
- Expected to match:
  - run ID `run:a65f3f38b597fc4709fe24e0ddbd12ae09a0c79ad54f9d1f5378c06cfe8a8c40` (same code version `148b4b9`, `attempt=1`, same config file and pinned input runs; see Assumption 1);
  - `_run-manifest.json` bytes (1244 bytes, sha256 `8bec49ec...`);
  - all 28 dataset row multisets (the comparator criterion);
  - edges 259, metrics 176;
  - the temporal line (`comparisons=1 changes=112`);
  - every count in the summary lines (windows, structure, canonical, paths, summaries);
  - load-manifest items, rows and distinct roles per store (`dynamodb:21/1302/12, neptune:24/1258/12, s3:29/2809/16, s3-vectors:4/249/2`);
  - load-item paths;
  - `csvParts` (each CSV dataset 2).
- Expected not to match (not compared):
  - per-part `rowCount` split, `byteCount` and `sha256`;
  - `partManifestSha256`;
  - the four `loadId` values;
  - output and checkpoint locations, event-log names and timings.
- Residual risk: Spark writes no Parquet file for an empty partition. If one of the small datasets (`evidence-paths` 9 rows, `entity-summary-inputs` 13 rows) lands entirely in one partition on EMR, its part count drops to 1 and the item count changes. This is unlikely, because both local topologies split every two-part dataset with rows on both sides, but it is not guaranteed. A difference there would be a physical-layout finding, not a logical one.

Decision 2: why `loadId` and `partManifestSha256` differ.

Derivation:
- `partManifestSha256` is the SHA-256 of the exact published `_parts.json` bytes (`LoadManifests.partManifestSha256` → `RunTree.observe`). `_parts.json` lists every part's path, `rowCount`, `byteCount` and `sha256`.
- `loadId` is `StoreLoadId.of(targetStore, sourceRunId, partManifestSha256, (path, targetRole) of every item)` (core `StoreLoad.scala`). So `loadId` names one physical set of bytes, and changes whenever any listed part's bytes change.
- The run ID comes only from the run manifest, which is logical identity and has no bytes, P or location.

What differs, from comparing the two `_parts.json` files directly:
1. Rows land in different parts. Per-part row counts differ in:
   - `edges` 137/122 vs 138/121;
   - `metrics` 83/93 vs 94/82;
   - `components`, `communities`, `bridges`, `coreness`;
   - `graph-edges` 375/424 vs 458/341;
   - all six edge CSV datasets (for example `coauthored-with-edges` 133/126 vs 155/104).
   Totals are identical. The cause is in the code: `sortBy` uses Spark's `RangePartitioner`, whose bounds come from reservoir sampling of each input partition. That sampling depends on iterator order, and iterator order after a shuffle depends on the order in which map outputs are fetched, which differs between one executor and two executor JVMs. At about 130 rows per partition and a per-partition sample of 60, the sample is order-dependent. I inferred this mechanism from the code and the counts; I did not trace it at runtime.
2. Same rows and sizes, different hashes.
   - `graph-nodes`, `evidence-paths`, `path-length-estimates`, `temporal-changes`, `summary-inputs`, `entity-summary-inputs`;
   - the driver-built single-part `analysis-windows` (2 rows, 11845 bytes) and `window-structures` (2 rows, 13655 bytes).
   For the shuffled datasets the likely cause is row order inside a part: ties in a sort, or unsorted per-partition order. For the two driver-built datasets I could not identify the byte difference without inspecting the bytes, which I did not do. Possible causes include row or element order taken from JVM-identity-dependent hash iteration (the SP-P6-07 class).
3. Byte-identical. `_run-manifest.json`, `window-comparisons` (1 row) and all six vertex CSV datasets are byte-identical across the two runs.

In every case the comparator (`LogicalReproducibility`: schema plus exact row multisets, grouped by every column, order-sensitive on arrays) found 0 logical differences across 28 datasets.

Is it intended? Yes. The project already decided that byte equality is "a diagnosis only; it is never the reproducibility criterion" (`LogicalReproducibility` Scaladoc, carried from sparkIT run 04). The load manifest is content-addressed so that a loader verifies exactly the bytes it loads. Within one output root, create-once publication guarantees one `partManifestSha256` per run ID. Across output roots or topologies, one run ID can name physically different but logically equal publications, each with its own `loadId`s. That is consistent with the contract. It is not stated explicitly in the core Scaladoc.

Effect on the EMR run: none. The cloud run will publish under its own S3 output root with its own `_parts.json` hash and `loadId`s, and the load manifests verify against that run's own bytes.

Effect on the cloud-vs-local comparison: compare the logical and shape values listed under Decision 1, never `loadId`, `partManifestSha256` or part hashes. The sparkSubmitLocal verdict already follows that rule.

Effect on Phase 7 loaders: `loadId` identifies one load job over one byte set. It is suitable for dedupe, resume or "already applied" markers of that exact load. It is not a logical-content key: re-publishing the same run ID elsewhere, or another attempt, gives other `loadId`s. Loader idempotency therefore has to come from stable record keys:
- researcher, edge, node and window IDs;
- the Neptune CSV `~id`;
- DynamoDB primary keys from record IDs;
- vector keys from summary-input IDs.
It must never come from part path, part order or row offset. Then two publications of one run ID converge to the same store state. Loaders must also not assume that `sourceRunId` identifies a unique `partManifestSha256` globally.

Classification:
- not blocking for the EMR run;
- carry to Phase 7 (storage-engineer: the loader idempotency contract above);
- no Spark defect needs fixing now.

Optional later hardening (spark-engineer, by a separate work order; not implemented): make the physical layout reproducible at a fixed P across topologies, so `partManifestSha256` and `loadId` repeat:
- replace the sample-based `sortBy` before each published dataset with `partitionBy(HashPartitioner(P))` on the record's unique key (String keys hash stably across JVMs), then `sortWithinPartitions` by the full unique key;
- make every driver-built dataset's row order and any array-element order explicitly sorted;
- add a feature test asserting equal `_parts.json` bytes between a `local[2]` run and a two-JVM run at the same P;
- first, a byte-level diff of `analysis-windows/part-00000.parquet` between the two runs, to confirm the cause in item 2.
Even then, hashes still change with P and with Spark or Parquet versions.

ASSUMPTIONS:
1. The run ID equals the local one only if the config digest depends only on the configuration values. I read that `ConfigLoader` keeps the file path only for error messages and that `ScholarChainConfig` holds no path, but I did not read the digest code in `AnalysisRunSettings`. If the digest included the path, the cloud run ID would differ from `a65f3f38...` while the datasets stayed logically equal. Recommend the comparison report the run ID first.
2. Expected matches assume the uploaded analysis config, benchmark pairs and input archive are byte-identical to the local ones. The run config pins them: `job_jar_sha256` and the three pinned run directories; `input_tree_sha256` is still all zeros, pending the dry run.
3. Both executors probably run on the single core node (with 2g executors on an m5.xlarge, YARN capacity is enough). Not verified on a cluster.

RISKS:
1. A Parquet dataset with an empty partition at P=2 on EMR would drop one load item and change an item count. Low probability; it would be a physical finding, not a logical one.
2. The cause of the byte difference in the driver-built `analysis-windows` and `window-structures` parts is not established at byte level. No logical difference was found, but that is the comparator's verdict, not a byte explanation.
3. Phase 7 loaders that key idempotency on `loadId` or part paths would load the same logical run twice under different keys. This is a routing note for storage-engineer.
4. `spark_dist_sha256` and `input_tree_sha256` in the run config are still unset or placeholder. They are outside this work order.

MANUAL ACTION REQUIRED:
- The orchestrator routes `partitions=2` to infra-engineer, who sets it in `/Users/taufeeqpatel/Desktop/ScholarChain/infra/terraform/environments/dev/runs/spark-dev-run-01.conf`.
- The human reviews and commits it with the rest of the run config.
- The orchestrator records the Decision 2 classification in the Phase 6 evidence and carries the loader idempotency note into the Phase 7 work order for storage-engineer.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none

NEXT GATE: infra-engineer fills `partitions=2` (plus the pending sha256 values). Then the `scripts/run-bounded-spark.sh --dry-run` review, then human approval of the bounded EMR run. After the run, compare cloud and local on: run ID, `_run-manifest.json` bytes, the 28-dataset logical comparison, the counts, and load-manifest items, rows, roles and paths. Do not compare `loadId`, `partManifestSha256` or part hashes. Also capture the stage DAGs, partitions and shuffle metrics from the copied event log.
```

Orchestrator: Decision 2 recorded as not blocking for Phase 6. Carried to Phase 7 (storage-engineer): loader idempotency must key on stable record IDs, never loadId, part path, part order or row offset. Optional spark-engineer hardening (byte-reproducible layout) deferred.
