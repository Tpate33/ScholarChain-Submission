# BE-P6-10 attempt-4: sbt sparkSubmitLocal PASS at 148b4b9 (build-engineer final report, verbatim)

```
BE-P6-10 attempt-4 passed. I ran `sbt sparkSubmitLocal` once at HEAD 148b4b9 and it finished with PASS. Both runs (`local[2]` and `local-cluster[2,1,1024]`) exited 0 and published the same run. Both temporal lines now read `comparisons=1 changes=112`, and the 28 compared datasets are logically equal. One thing to check before infra-engineer uses the evidence: the `loadId` values and `partManifestSha256` differ between the two runs (see RISKS).

STATUS: COMPLETE
OWNER: build-engineer
WORK ORDER: BE-P6-10 attempt-4 (Phase 6). One approved `sbt sparkSubmitLocal` invocation at the committed SP-P6-07 fix, HEAD 148b4b91bc904a63680b94f91f275173f743c4b6.

FILES CREATED: none by me. The task itself created the create-once attempt directory /Users/taufeeqpatel/Desktop/ScholarChain/target/spark-submit-local/attempt-4/ (gitignored) and rebuilt /Users/taufeeqpatel/Desktop/ScholarChain/modules/spark/target/scala-3.3.6/scholarchain-spark-jobs-0.1.0.jar.
FILES MODIFIED: none. `git status --porcelain` printed nothing both before and after the run.

COMMANDS RUN:
1. `git rev-parse HEAD`: 148b4b91bc904a63680b94f91f275173f743c4b6
2. `git status --porcelain`: no output
3. `sbt sparkSubmitLocal`: run once, approved. It finished in the foreground (`[success] Total time: 62 s (0:01:02.0), completed Nov 18, 2026, 10:44:40 PM`). The console output was saved to /Users/taufeeqpatel/.claude/projects/-Users-taufeeqpatel-Desktop-ScholarChain/7e2adb2c-04c4-4499-bc7c-e377443f1cd5/tool-results/bvfmjzitr.txt, and I read all 89 lines of it.
4. `git status --porcelain` after the run: no output

No other sbt task, no `sbt check`, no `sbt clean` and no AWS call.

TEST RESULTS: not run, as the work order instructs (no `sbt check`). The orchestrator cites human Terminal `sbt check` 1574/0/0/0 at this code; I did not observe it. sparkSubmitLocal writes no test marker.

VALIDATION RESULTS (verbatim):
- Code scope:
  `[info] sparkSubmitLocal: code scope build.sbt project modules/core modules/spark: 138 tracked files, raw bytes equal to HEAD 148b4b91bc904a63680b94f91f275173f743c4b6, no uncommitted path; 4 ignored sbt target or .DS_Store path(s); --code-version will be HEAD` (printed twice, before and after the jar build)
- Job-jar PASS:
  `[info] sparkSubmitLocal: PASS: scholarchain-spark-jobs-0.1.0.jar: Main-Class scholarchain.spark.job.CollaborationJob; no class under org/apache/spark/, org/apache/hadoop/, org/apache/parquet/, com/amazonaws/, software/amazon/awssdk/, org/apache/logging/log4j/, org/apache/log4j/, org/slf4j/; no scala-library 2.13.16 class; 562 scala/ class(es), all from scala3-library_3 3.3.6; 2916 entries, 2369 classes, 1 distinct entry timestamp(s); top-level packages scholarchain/core:893, scala/quoted:330, scholarchain/spark:273, com/typesafe:181, upickle/core:160, upickle/implicits:101, scala/runtime:82, scala/annotation:43`
- Job-jar sha256:
  `[info] sparkSubmitLocal: /Users/taufeeqpatel/Desktop/ScholarChain/modules/spark/target/scala-3.3.6/scholarchain-spark-jobs-0.1.0.jar bytes=7621816 sha256=cd62042c642b11a62be290128cc97b28ed03b9b55d1808faf8b5fed1dbaf597a` (new sha256, as expected)
- Cache:
  `[info] sparkSubmitLocal: cached target/spark-dist/spark-3.5.5-bin-hadoop3-scala2.13.tgz: sha512 matches the ledger; not downloaded`
- Attempt directory:
  `[info] sparkSubmitLocal: attempt target/spark-submit-local/attempt-4 (create-once; removed only by sbt clean)`
- Extraction:
  `[info] sparkSubmitLocal: extracted 1816 files (72 executable), 190 directories, 453497504 bytes; jars/ holds scala-library-2.13.8.jar, hadoop-client-api/runtime-3.3.4, spark-core_2.13-3.5.5`
- inventory.txt in full (attempt-4/inventory.txt, tab-separated):
  runtime-jar	scala-library-2.13.16.jar	bytes=5926340	sha256=1ebb2b6f9e4eb4022497c19b1e1e825019c08514f962aaac197145f88ed730f1
  runtime-jar	hadoop-aws-3.3.4.jar	bytes=962685	sha256=53f9ae03c681a30a50aa17524bd9790ab596b28481858e54efd989a826ed3a4a
  runtime-jar	aws-java-sdk-bundle-1.12.262.jar	bytes=280645251	sha256=873fe7cf495126619997bec21c44de5d992544aea7e632fdc77adb1a0915bae5
  job-jar	scholarchain-spark-jobs-0.1.0.jar	bytes=7621816	sha256=cd62042c642b11a62be290128cc97b28ed03b9b55d1808faf8b5fed1dbaf597a
  distribution	spark-3.5.5-bin-hadoop3-scala2.13.tgz	bytes=409707962	sha512=a462656d2a87afcf81f24e4dea9a99e6666ed5c8ff7ba0cc9c936188498c549cf852d0ebb475325761d6cf645d1184d527be9a609503d587bb47d5532b533aa1
  hadoop-aws-sha1	a65839fbf1869f81a1632e09f415e586922e4f80
  code-version	148b4b91bc904a63680b94f91f275173f743c4b6
- Run 1, `local[2]`:
  - Status: `[info] sparkSubmitLocal: run 1 (local[2]): exit 0; published run=a65f3f38b597fc4709fe24e0ddbd12ae09a0c79ad54f9d1f5378c06cfe8a8c40 with _SUCCESS; event log events/local-1795063426393`
  - Temporal line (attempt-4/run-1-local/output.txt, line 18): `scholarchain-collaboration-job temporal: analysisWindows=2 comparisons=1 changes=112 worksWithoutYear=0 limitations=grouping-unevaluated+overlapping-windows+unequal-window-lengths`
  - Run line: `edges=259 metrics=176`
  - `partManifestSha256=749763adb05af272a3c0c5473ed3d90d071a3e762d67ef70ebef84295cc94907`
- Run 2, `local-cluster[2,1,1024]`:
  - Status: `[info] sparkSubmitLocal: run 2 (local-cluster[2,1,1024]): exit 0; published run=a65f3f38b597fc4709fe24e0ddbd12ae09a0c79ad54f9d1f5378c06cfe8a8c40 with _SUCCESS; event log events/app-20261118224401-0000`
  - Temporal line (attempt-4/run-2-local-cluster/output.txt, line 19): `scholarchain-collaboration-job temporal: analysisWindows=2 comparisons=1 changes=112 worksWithoutYear=0 limitations=grouping-unevaluated+overlapping-windows+unequal-window-lengths`
  - Run line: `edges=259 metrics=176`
  - `partManifestSha256=a3200b82f3f91ed38b202150e8a68fcf5eab6d5e55206e167c31034f8c065b1f`
  - Confirmed: changes=112 and comparisons=1 in both runs. The comparator's `scholarchain.researcher-temporal-change.v1 112 112 equal` and `scholarchain.window-comparison.v1 1 1 equal` agree. The attempt-3 mismatch (112 vs 55) is gone.
- Build's load-manifest lines:
  - `[info] sparkSubmitLocal: run 1 (local[2]) load manifests as read by the build (store:items/rows/roles): dynamodb:21/1302/12,neptune:24/1258/12,s3:29/2809/16,s3-vectors:4/249/2`
  - `[info] sparkSubmitLocal: run 2 (local-cluster) load manifests as read by the build (store:items/rows/roles): dynamodb:21/1302/12,neptune:24/1258/12,s3:29/2809/16,s3-vectors:4/249/2`
- Comparator line:
  `[info] published-run-comparison compared: 28 datasets, 0 different; result /Users/taufeeqpatel/Desktop/ScholarChain/target/spark-submit-local/attempt-4/compare/comparison.txt`
  - comparison.txt (format `scholarchain.published-run-comparison.v1`, nonce 8dabc682df0eadf343d8bebeb9ab7c34b5da1b6c3ed20bd22c7a6b34343df28d) lists 28 dataset rows, all `equal`, and closes with `datasets=28` and a matching `complete=<nonce>`.
- result.txt in full (attempt-4/result.txt):
  status=pass
  run=run=a65f3f38b597fc4709fe24e0ddbd12ae09a0c79ad54f9d1f5378c06cfe8a8c40
  datasets=28
  edges=259
  metrics=176
  loads=dynamodb:21/1302/12,neptune:24/1258/12,s3:29/2809/16,s3-vectors:4/249/2
  codeVersion=148b4b91bc904a63680b94f91f275173f743c4b6
  jobJarSha256=cd62042c642b11a62be290128cc97b28ed03b9b55d1808faf8b5fed1dbaf597a
  masters=local[2],local-cluster[2,1,1024]
- submit-commands.txt summary: two lines, identical except for `--master` (`local[2]` and `local-cluster[2,1,1024]`) and the per-run directories (`run-1-local` and `run-2-local-cluster`):
  - `--deploy-mode client --class scholarchain.spark.job.CollaborationJob --driver-memory 1g`
  - Driver and executor class path: the three attempt-4/runtime-jars jars
  - The JDK 17 `--add-opens` set plus `-Djdk.reflect.useDirectMethodHandle=false`
  - Event log on, UI off, loopback driver host and bind address, `spark.yarn.maxAppAttempts=1`, `fs.s3a.directory.marker.retention=keep`
  - Job jar: attempt-4/job/scholarchain-spark-jobs-0.1.0.jar
  - Input: archive root /Users/taufeeqpatel/ScholarChain-archive/phase-06/dev-run-01 with the pinned curated, decision and grouping runs
  - Config: modules/spark/src/test/resources/scholarchain/spark/dev-run-01/analysis-dev.conf and benchmark-pairs-dev.v1.json
  - `--code-version 148b4b9…` `--attempt 1 --partitions 2 --checkpoint-interval 2` plus the max-tree and driver-validation bounds
- Final line:
  `[info] sparkSubmitLocal: PASS: spark-submit of scholarchain-spark-jobs-0.1.0.jar (sha256 cd62042c642b11a62be290128cc97b28ed03b9b55d1808faf8b5fed1dbaf597a) on spark-3.5.5-bin-hadoop3-scala2.13.tgz exited 0 at local[2] and local-cluster[2,1,1024]; both published run=a65f3f38b597fc4709fe24e0ddbd12ae09a0c79ad54f9d1f5378c06cfe8a8c40 with _SUCCESS and byte-equal _run-manifest.json; 28 datasets logically equal; edges 259, metrics 176 and the rows and distinct roles of the four load manifests equal the sparkIT run 08 baseline; load-manifest items and rows equal between the runs; evidence target/spark-submit-local/attempt-4/ (result.txt, inventory.txt, submit-commands.txt, run-*/output.txt, run-*/events/, compare/comparison.txt); copy it into the phase evidence`
  `[success] Total time: 62 s (0:01:02.0)`
- Evidence paths named by the PASS line, all under /Users/taufeeqpatel/Desktop/ScholarChain/target/spark-submit-local/attempt-4/:
  - result.txt
  - inventory.txt
  - submit-commands.txt
  - run-1-local/output.txt
  - run-2-local-cluster/output.txt
  - run-1-local/events/ (event log local-1795063426393)
  - run-2-local-cluster/events/ (event log app-20261118224401-0000)
  - compare/comparison.txt
- Hashes for the infra-engineer run config (from inventory.txt):
  - job jar scholarchain-spark-jobs-0.1.0.jar: sha256 cd62042c642b11a62be290128cc97b28ed03b9b55d1808faf8b5fed1dbaf597a (7621816 bytes)
  - scala-library-2.13.16.jar: sha256 1ebb2b6f9e4eb4022497c19b1e1e825019c08514f962aaac197145f88ed730f1 (5926340 bytes)
  - hadoop-aws-3.3.4.jar: sha256 53f9ae03c681a30a50aa17524bd9790ab596b28481858e54efd989a826ed3a4a (962685 bytes; hadoop-aws-sha1 a65839fbf1869f81a1632e09f415e586922e4f80)
  - aws-java-sdk-bundle-1.12.262.jar: sha256 873fe7cf495126619997bec21c44de5d992544aea7e632fdc77adb1a0915bae5 (280645251 bytes)
  - distribution spark-3.5.5-bin-hadoop3-scala2.13.tgz: sha512 a462656d2a87afcf81f24e4dea9a99e6666ed5c8ff7ba0cc9c936188498c549cf852d0ebb475325761d6cf645d1184d527be9a609503d587bb47d5532b533aa1
- What I could not see: nothing was cut off. I read every console line and all six text evidence files above. I did not open the event-log directories or the published output data beyond what the job and the comparator printed.

ASSUMPTIONS: none beyond the work order. The PASS verdict is the build's own, from its printed checks and the nonce-bound comparison file. I did not verify it independently.

RISKS:
1. The job's `loadId` values and `partManifestSha256` differ between the two runs, for example `neptune-bulk-load` `load:b1e1894c…` in run 1 vs `load:e5276ca3…` in run 2. Items, rows, roles, the run ID and `_run-manifest.json` (byte-equal) do match. The build does not compare `loadId` or part-manifest hashes, so this does not affect the PASS. If downstream loaders or the EMR run config expect `loadId` to stay the same across cluster topologies, spark-engineer should confirm whether it is meant to come from part bytes or paths. A likely cause, not verified, is that part file bytes or order differ by executor layout.
2. The run-2 warning that the checkpoint directory is on the local filesystem is expected for local-cluster.
3. The evidence is under a gitignored `target/` and is removed by `sbt clean`. It should be copied into the phase evidence before any clean.

MANUAL ACTION REQUIRED: the human or orchestrator copies attempt-4 evidence into docs/agent-runs/phase-06/. Then hand the hashes above to infra-engineer for the run config.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review of the attempt-4 evidence; infra-engineer updates the run config with the job-jar and runtime-jar hashes; then the human approves the Phase 6 bounded EMR run.
```

Orchestrator note: the local spark-submit compatibility gate (B8 item 1) is passed. The text evidence above (result.txt, inventory.txt, both temporal lines, the comparator line, the PASS line) is recorded here; the event logs and output data stay under target/spark-submit-local/attempt-4/ until the human archives them outside the repository. RISK 1 (loadId and partManifestSha256 differ between runs, same logical content) is carried as an open question for spark-engineer. It is not a gate condition, because the comparison is logical and the run identity and _run-manifest.json are byte-equal.
