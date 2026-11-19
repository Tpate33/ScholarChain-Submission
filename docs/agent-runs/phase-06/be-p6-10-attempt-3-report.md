# BE-P6-10 attempt-3: sbt sparkSubmitLocal at 5242754 (build-engineer final report, verbatim)

```
STATUS: BLOCKED-ON
OWNER: build-engineer
WORK ORDER: BE-P6-10 attempt-3 (Phase 6). One approved invocation of `sbt sparkSubmitLocal` at the committed repair 03 (HEAD 524275482b63c6f05f0682964177ec2abc25ba82).

The run failed. Run 1 (local[2]) completed, but run 2 (local-cluster[2,1,1024]) exited 1: the Spark job refused its own output, saying the temporal "changes" record count did not match the researcher count of the comparisons. The task stopped there, so no comparator or verdict ran. I made no repair and did not rerun. The failure is inside the job code, so the next step belongs to spark-engineer.

FILES CREATED: none by me. The task created /Users/taufeeqpatel/Desktop/ScholarChain/target/spark-submit-local/attempt-3/ (gitignored build output).
FILES MODIFIED: none

COMMANDS RUN:
1. `git rev-parse HEAD` printed 524275482b63c6f05f0682964177ec2abc25ba82.
2. `git status --porcelain` printed nothing (clean).
3. `sbt sparkSubmitLocal`, exactly once. It exited 1 after 35 s ("Total time: 35 s, completed Nov 18, 2026, 9:53:08 PM").
4. `git status --porcelain` afterwards printed nothing (still clean).
No sbt check, no other sbt task, no AWS call, no sbt clean.

TEST RESULTS: No tests were run, as ordered. sbt check evidence is reused from the human Terminal run (1570/0/0/0, docs/agent-runs/phase-06/human-sbt-check-repair-03-2026-11-18.md). sparkSubmitLocal writes no test marker.

VALIDATION RESULTS (lines quoted from the console):
- Code scope: `sparkSubmitLocal: code scope build.sbt project modules/core modules/spark: 137 tracked files, raw bytes equal to HEAD 524275482b63c6f05f0682964177ec2abc25ba82, no uncommitted path; 4 ignored sbt target or .DS_Store path(s); --code-version will be HEAD`
- Archive: the sparkIT archive-root line confirmed /Users/taufeeqpatel/ScholarChain-archive/phase-06/dev-run-01 holds exactly the pinned runs curated 2881724b…8baa, decision 736cb170…b76a and grouping 84dd896f…7dbd7d2.
- Job jar PASS line: `sparkSubmitLocal: PASS: scholarchain-spark-jobs-0.1.0.jar: Main-Class scholarchain.spark.job.CollaborationJob; no class under org/apache/spark/, org/apache/hadoop/, org/apache/parquet/, com/amazonaws/, software/amazon/awssdk/, org/apache/logging/log4j/, org/apache/log4j/, org/slf4j/; no scala-library 2.13.16 class; 562 scala/ class(es), all from scala3-library_3 3.3.6; 2916 entries, 2369 classes, 1 distinct entry timestamp(s); top-level packages scholarchain/core:893, scala/quoted:330, scholarchain/spark:273, com/typesafe:181, upickle/core:160, upickle/implicits:101, scala/runtime:82, scala/annotation:43`
- Cache: `sparkSubmitLocal: cached target/spark-dist/spark-3.5.5-bin-hadoop3-scala2.13.tgz: sha512 matches the ledger; not downloaded`
- Attempt directory: `sparkSubmitLocal: attempt target/spark-submit-local/attempt-3 (create-once; removed only by sbt clean)`
- Extraction: `sparkSubmitLocal: extracted 1816 files (72 executable), 190 directories, 453497504 bytes; jars/ holds scala-library-2.13.8.jar, hadoop-client-api/runtime-3.3.4, spark-core_2.13-3.5.5`

Inventory (attempt-3/inventory.txt, in full):
- runtime-jar scala-library-2.13.16.jar bytes=5926340 sha256=1ebb2b6f9e4eb4022497c19b1e1e825019c08514f962aaac197145f88ed730f1
- runtime-jar hadoop-aws-3.3.4.jar bytes=962685 sha256=53f9ae03c681a30a50aa17524bd9790ab596b28481858e54efd989a826ed3a4a
- runtime-jar aws-java-sdk-bundle-1.12.262.jar bytes=280645251 sha256=873fe7cf495126619997bec21c44de5d992544aea7e632fdc77adb1a0915bae5
- job-jar scholarchain-spark-jobs-0.1.0.jar bytes=7618495 sha256=568a6e78393a560403e9b19576ddf462bdb98028cc1b26bcb8e76dde45164ef9
- distribution spark-3.5.5-bin-hadoop3-scala2.13.tgz bytes=409707962 sha512=a462656d2a87afcf81f24e4dea9a99e6666ed5c8ff7ba0cc9c936188498c549cf852d0ebb475325761d6cf645d1184d527be9a609503d587bb47d5532b533aa1
- hadoop-aws-sha1 a65839fbf1869f81a1632e09f415e586922e4f80
- code-version 524275482b63c6f05f0682964177ec2abc25ba82

Against the pins: the distribution ships scala-library 2.13.8 (pin 2.13.8) and the runtime scala-library jar is 2.13.16 (pin 2.13.16). Hadoop client is hadoop-client-api/runtime 3.3.4, from the extraction line.

Run 1 (local[2]): it ran and produced output; its exit status line is in the truncated part of the console (see below). Evidence is in /Users/taufeeqpatel/Desktop/ScholarChain/target/spark-submit-local/attempt-3/run-1-local/output.txt:
- Run: `analysisRunId=run:c034b49cdfed23c90cadbbcb5de3527b489941d7b0c64a9e9bf682263c4466cf`, edges=259, metrics=176, codeVersion=524275482b…, partitions=2.
- Temporal line: `scholarchain-collaboration-job temporal: analysisWindows=2 comparisons=1 changes=112 worksWithoutYear=0 limitations=grouping-unevaluated+overlapping-windows+unequal-window-lengths`
- Load manifests as the job printed them (store/items/rows/roles):
  - neptune-bulk-load 24/1258/12
  - dynamodb 21/1302/12
  - s3 29/2809/16
  - s3-vectors 4/249/2
- Output: `.../attempt-3/run-1-local/out/run=c034b49c…`, partManifestSha256=9eb6961844c9e36e28d6b8e3e03cc68f700404c3e735e5c4c5414ba074386b6a

Run 2 (local-cluster[2,1,1024]): exited 1. Its whole output.txt (/Users/taufeeqpatel/Desktop/ScholarChain/target/spark-submit-local/attempt-3/run-2-local-cluster/output.txt) is three Spark warnings and then `scholarchain-collaboration-job refused: temporal changes: 112 records, the comparisons count 55 researchers`. The task then printed `[error] sparkSubmitLocal: run 2 (local-cluster[2,1,1024]) exited 1; attempt target/spark-submit-local/attempt-3 kept; NOT a pass`. No run name, event log or load manifest was published for run 2.

Not produced, because the task stopped at run 2:
- the comparator line (compare/comparison.txt);
- result.txt (Read: "File does not exist");
- submit-commands.txt (Read: "File does not exist");
- the build's two "load manifests as read by the build" lines;
- a final PASS line, so there are no PASS evidence paths to archive.

What I could not see: about 11,900 characters of console output were truncated in my view, between the code-version line and the run-2 submit command. That covers run 1's command, exit status line, published run name and event log, and the build's run-1 load-manifest line, if the task prints one before run 2. Without Bash ls I could not list the attempt's events/ directories.

ASSUMPTIONS: The refusal text comes from modules/spark job code (CollaborationJob output validation), not from the sbt task machinery. The task only reported the child's exit code.

RISKS:
- The temporal output of CollaborationJob is nondeterministic. The same code scope, inputs, jar code and partitions=2 gave "changes=112" with consistent comparisons in attempt-3 run 1 (local[2]) and in attempt-2 run 2 (also local-cluster[2,1,1024]; /Users/taufeeqpatel/Desktop/ScholarChain/target/spark-submit-local/attempt-2/run-2-local-cluster/output.txt line 19). In attempt-3 run 2, the comparisons' researcher count came out as 55 against 112 change records.
- 55 is about half of 112 with 2 partitions. That suggests the comparison researcher set is built from a non-idempotent or partially seen computation: for example a recomputed lineage, a driver-side or accumulator-based count, take/limit or first over unordered data, or state that is not shared across separate executor JVMs. This is a hypothesis; I did not inspect the source.
- This risk applies directly to the EMR run, which is multi-executor.

MANUAL ACTION REQUIRED: The human should decide whether to route the job nondeterminism to spark-engineer and approve a new sparkSubmitLocal invocation after the repair. attempt-3 is kept under target/ as failure evidence. Archive it before any sbt clean if wanted.

BLOCKED-ON OWNER: spark-engineer (modules/spark, CollaborationJob temporal comparison and changes validation).

BLOCKED-ON EVIDENCE:
- /Users/taufeeqpatel/Desktop/ScholarChain/target/spark-submit-local/attempt-3/run-2-local-cluster/output.txt line 4: `scholarchain-collaboration-job refused: temporal changes: 112 records, the comparisons count 55 researchers`
- /Users/taufeeqpatel/Desktop/ScholarChain/target/spark-submit-local/attempt-3/run-1-local/output.txt line 18 (same code and inputs, consistent, changes=112)
- /Users/taufeeqpatel/Desktop/ScholarChain/target/spark-submit-local/attempt-2/run-2-local-cluster/output.txt line 19 (same mode in an earlier attempt, consistent)
- Console: `[error] sparkSubmitLocal: run 2 (local-cluster[2,1,1024]) exited 1; attempt target/spark-submit-local/attempt-3 kept; NOT a pass`

Requested repair: make the temporal comparison's researcher set and the changes count deterministic and independent of executor layout and recomputation. Add a feature test that fails without the fix, for example over multiple partitions or repeated evaluation.

NEXT GATE: spark-engineer repair with sbt check, then code-reviewer review, then human approval of a fresh single `sbt sparkSubmitLocal` invocation (attempt-4) by build-engineer.
```

Orchestrator note: the job jar sha256 (568a6e78…) is identical in attempt-2 and attempt-3; only build machinery changed between them. The same jar, inputs and master passed this check in attempt-2 run 2 and failed in attempt-3 run 2, so the job is nondeterministic under multi-JVM execution. Routed to spark-engineer as SP-P6-07.
