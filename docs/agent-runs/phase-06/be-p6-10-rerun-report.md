# BE-P6-10 rerun: sbt sparkSubmitLocal attempt-2 at 609f27a (build-engineer final report, verbatim)

```text
STATUS: BLOCKED-ON
OWNER: build-engineer
WORK ORDER: BE-P6-10 rerun (Phase 6): one approved invocation of `sbt sparkSubmitLocal` at 609f27a99fc7098a82f8d6abb5bce5d12110a2b6
FILES CREATED: none by the agent. The task wrote target/spark-submit-local/attempt-2/ (gitignored): inventory.txt, result.txt, run-1-local/, run-2-local-cluster/, compare/comparison.txt, extracted distribution
FILES MODIFIED: none
COMMANDS RUN:
  1. git rev-parse HEAD -> 609f27a99fc7098a82f8d6abb5bce5d12110a2b6
  2. git status --porcelain -> (empty)
  3. sbt sparkSubmitLocal -> exit 1, "[error] Total time: 59 s, completed Nov 18, 2026, 9:12:36 PM" (run exactly once)
  4. git ls-files -- project; git log --oneline --max-count=8 -- project/SparkSubmitLocal.scala; git ls-files --others -- <attempt-2 run-1-local and compare> (read-only diagnosis)
TEST RESULTS: sbt check not run, as the work order said. The evidence reused is the human Terminal sbt check 1570/0/0/0 (docs/agent-runs/phase-06/human-sbt-check-2026-11-18.md). sparkSubmitLocal writes no test marker.
VALIDATION RESULTS:
  Verbatim lines:
  - Code scope: "sparkSubmitLocal: code scope build.sbt project modules/core modules/spark: 137 tracked files, raw bytes equal to HEAD 609f27a99fc7098a82f8d6abb5bce5d12110a2b6, no uncommitted path; 4 ignored sbt target or .DS_Store path(s); --code-version will be HEAD" (printed twice, before and after the build)
  - Archive preflight: the sparkIT line for archive root /Users/taufeeqpatel/ScholarChain-archive/phase-06/dev-run-01 with the three pinned runs (curated 2881724b…, decision 736cb170…, grouping 84dd896f…), none _UNVERIFIED
  - Assembly check: "sparkSubmitLocal: PASS: scholarchain-spark-jobs-0.1.0.jar: Main-Class scholarchain.spark.job.CollaborationJob; no class under org/apache/spark/, org/apache/hadoop/, org/apache/parquet/, com/amazonaws/, software/amazon/awssdk/, org/apache/logging/log4j/, org/apache/log4j/, org/slf4j/; no scala-library 2.13.16 class; 562 scala/ class(es), all from scala3-library_3 3.3.6; 2916 entries, 2369 classes, 1 distinct entry timestamp(s); …"
  - Cache: "sparkSubmitLocal: cached target/spark-dist/spark-3.5.5-bin-hadoop3-scala2.13.tgz: sha512 matches the ledger; not downloaded"
  - Attempt: "sparkSubmitLocal: attempt target/spark-submit-local/attempt-2 (create-once; removed only by sbt clean)"
  - Extraction: "extracted 1816 files (72 executable), 190 directories, 453497504 bytes; jars/ holds scala-library-2.13.8.jar, hadoop-client-api/runtime-3.3.4, spark-core_2.13-3.5.5"
  Inventory (target/spark-submit-local/attempt-2/inventory.txt, matching the printed lines):
  - distribution spark-3.5.5-bin-hadoop3-scala2.13.tgz bytes=409707962 sha512=a462656d2a87afcf81f24e4dea9a99e6666ed5c8ff7ba0cc9c936188498c549cf852d0ebb475325761d6cf645d1184d527be9a609503d587bb47d5532b533aa1
  - scala-library in the distribution: 2.13.8, pin spark.distribution.scala-library=2.13.8, consistent
  - runtime scala-library-2.13.16.jar bytes=5926340 sha256=1ebb2b6f9e4eb4022497c19b1e1e825019c08514f962aaac197145f88ed730f1, pin spark.runtime.scala-library=2.13.16, consistent
  - hadoop-client: hadoop-client-api/runtime 3.3.4, from the distribution's jars/
  - hadoop-aws-3.3.4.jar bytes=962685 sha256=53f9ae03c681a30a50aa17524bd9790ab596b28481858e54efd989a826ed3a4a, hadoop-aws-sha1 a65839fbf1869f81a1632e09f415e586922e4f80
  - aws-java-sdk-bundle-1.12.262.jar bytes=280645251 sha256=873fe7cf495126619997bec21c44de5d992544aea7e632fdc77adb1a0915bae5
  - job jar scholarchain-spark-jobs-0.1.0.jar bytes=7618495 sha256=568a6e78393a560403e9b19576ddf462bdb98028cc1b26bcb8e76dde45164ef9
  - code-version 609f27a99fc7098a82f8d6abb5bce5d12110a2b6
  Run 1 (local[2]):
  - The sbt "run 1 … exit …" line was in the truncated part of my view.
  - The run published run=cf709455538981eccf999a73d0120f2fcba4fbe21f4f96384961a9cfbbd9eb20 with _SUCCESS, _parts.json and _run-manifest.json, and wrote the event log events/local-1795057905098 (file listing).
  - Child output target/spark-submit-local/attempt-2/run-1-local/output.txt: edges=259 metrics=176 partitions=2 codeVersion=609f27a…; load manifests neptune items=24 rows=1258 roles=12, dynamodb 21/1302/12, s3 29/2809/16, s3-vectors 4/249/2.
  - The comparator parsed it as "left".
  Run 2 (local-cluster[2,1,1024]):
  - "sparkSubmitLocal: run 2 (local-cluster[2,1,1024]): exit 0; published run=cf709455538981eccf999a73d0120f2fcba4fbe21f4f96384961a9cfbbd9eb20 with _SUCCESS; event log events/app-20261118211200-0000"
  - Load manifests are the same as run 1.
  Comparator: "published-run-comparison compared: 28 datasets, 0 different; result /Users/taufeeqpatel/Desktop/ScholarChain/target/spark-submit-local/attempt-2/compare/comparison.txt"
  - The file has 28 dataset lines, all equal (edges 259, metrics 176, graph-node 459, graph-edge 799, …), and a complete= nonce line that matches its nonce= line.
  - Both run names are the same (run=cf709455…).
  Final verdict (verbatim): "[error] sparkSubmitLocal: FAILED: left (local[2]) load manifests dynamodb:21/1302,neptune:24/1258,s3:29/2809,s3-vectors:4/249, baseline dynamodb:12/1302,neptune:12/1258,s3:16/2809,s3-vectors:2/249; right (local-cluster) load manifests dynamodb:21/1302,neptune:24/1258,s3:29/2809,s3-vectors:4/249, baseline dynamodb:12/1302,neptune:12/1258,s3:16/2809,s3-vectors:2/249; NOT a pass; attempt target/spark-submit-local/attempt-2 kept"
  - result.txt: status=fail, codeVersion=609f27a…, jobJarSha256=568a6e78…, masters=local[2],local-cluster[2,1,1024].
  Not seen:
  - The truncated middle of the sbt output (about 19.6 kB), including the run-1 exit/duration line, the spark-submit command lines and the run-1 child log in the sbt stream. Run 1's child output was recovered from output.txt.
  - Per-run durations: no visible line gives them. The whole task took 59 s.
  - The run-2 output.txt was not read.
ASSUMPTIONS: The baseline's item counts come from sparkIT run 08, which ran with one output partition. It gave items = roles (12/12/16/2) and 29 part files in total (docs/agent-runs/phase-06/sparkit-run-08.md line 50). I inferred the one-partition setting from those counts and did not check the sparkIT configuration.
RISKS:
  - Why it failed: in project/SparkSubmitLocal.scala lines 129-138, `ExpectedLoads` pins (items, rows) per store at 12/12/16/2 items. The task runs the job with `Partitions = 2` (line 116). A load-manifest item is one part file: s3-vectors.json lists entity-summary-inputs/part-00000 and part-00001, and summary-inputs/part-00000 and part-00001, so 4 items for 2 roles. With two partitions the item count therefore grows (24/21/29/4). That is not a data or runtime difference.
  - Every partition-independent value matches the baseline: rows 1258/1302/2809/249, roles 12/12/16/2 (equal to the baseline's item numbers), edges 259, metrics 176, 28 of 28 datasets equal.
  - Suggested fix (not applied), for build-engineer: base the verdict on rows and on distinct targetRole counts, or on the partition count it actually passes. Do not compare raw item counts with a baseline taken at one partition. A rerun then needs a new human approval.
  - Run ID cf709455… differs from sparkIT run 08's dffad781…. This is expected, because codeVersion (and partitions) feed the run identity. The comparator checks equality between the two runs here, not against run 08's ID.
  - Benign warnings seen: hostname resolves to loopback and Spark uses 10.0.0.231 on en0 (in the comparator JVM, and possibly in the runs); native-hadoop library not loaded.
MANUAL ACTION REQUIRED: The human decides whether to send the verdict defect to build-engineer. After a reviewed fix is committed, approving another sparkSubmitLocal run creates attempt-3. attempt-2 stays as evidence; it is in target/, which `sbt clean` removes.
BLOCKED-ON OWNER: build-engineer (task machinery: SparkSubmitLocal.verdict / ExpectedLoads). No spark-engineer, domain-engineer or pin issue was found.
BLOCKED-ON EVIDENCE:
  - target/spark-submit-local/attempt-2/result.txt (status=fail, reason above)
  - target/spark-submit-local/attempt-2/compare/comparison.txt (28/28 equal; load lines 24/21/29/4 items)
  - target/spark-submit-local/attempt-2/run-1-local/output.txt line 13: "csvParts=…:2" for all 12 CSV roles; lines 20-23: items vs roles
  - target/spark-submit-local/attempt-2/run-1-local/out/load=cf709455…/s3-vectors.json: 4 part-file items over 2 roles
  - project/SparkSubmitLocal.scala lines 116 (Partitions = 2) and 129-138 (ExpectedLoads item baseline from sparkIT run 08)
  - docs/agent-runs/phase-06/sparkit-run-08.md line 50 (run 08 load items 12/12/16/2, 29 part files)
NEXT GATE: build-engineer repair of the sparkSubmitLocal verdict (load baseline independent of partition count) plus its self-test, then code-reviewer review, human commit, and a new human approval for one sparkSubmitLocal run (attempt-3).
```

Orchestrator verification: project/SparkSubmitLocal.scala:116 `val Partitions = 2` and :133-138 `ExpectedLoads` item counts 12/12/16/2 confirmed by Read. Note dynamodb items 21 for 12 roles at 2 partitions, so items are not simply roles × partitions (some roles write one part). Routed to build-engineer as BE-P6-09 repair 03.
