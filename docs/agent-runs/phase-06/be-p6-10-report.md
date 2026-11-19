# BE-P6-10: sbt sparkSubmitLocal (build-engineer final report, verbatim)

```
STATUS: BLOCKED-ON
OWNER: build-engineer
WORK ORDER: BE-P6-10 (Phase 6): run the local spark-submit compatibility gate `sbt sparkSubmitLocal`. It needed two invocations. The first downloaded and verified the distribution as expected. The second stopped on a layout check in the build's own task code, before any Spark run started.

FILES CREATED: none by me. The task itself wrote ignored build output:
- /Users/taufeeqpatel/Desktop/ScholarChain/target/spark-dist/spark-3.5.5-bin-hadoop3-scala2.13.tgz (sha512 verified)
- /Users/taufeeqpatel/Desktop/ScholarChain/target/spark-submit-local/attempt-1/ (kept). It holds only dist/spark-3.5.5-bin-hadoop3-scala2.13/. No inventory.txt, runtime-jars/, job/, conf/, run-*/ or result.txt was written, because the task failed before those steps.

FILES MODIFIED: none. `git status --porcelain` before and after shows only the three untracked orchestrator files under docs/agent-runs/phase-06/.

COMMANDS RUN:
1. `git status --porcelain`: only the three untracked docs/agent-runs/phase-06/ files.
2. `git rev-parse HEAD`: 103d05dbc8f6f71ff21f2c84665b8a5f299b2929
3. `sbt sparkSubmitLocal` (run 1): exit 1. This is the expected download-and-stop, not a pass. Key lines, verbatim:
   - `[info] sparkSubmitLocal: code scope build.sbt project modules/core modules/spark: 137 tracked files, raw bytes equal to HEAD 103d05dbc8f6f71ff21f2c84665b8a5f299b2929, no uncommitted path; 4 ignored sbt target or .DS_Store path(s); --code-version will be HEAD`
   - The sparkIT archive preflight passed: root /Users/taufeeqpatel/ScholarChain-archive/phase-06/dev-run-01, with curated run=2881724b5c86b561a235836d51946fbd884beed36a14f929b27c278cb5de8baa, decision run=736cb1706210a34f0958e658c061054c4e6b27b80847528db8619da84495b76a and grouping run=84dd896fda43977b934008f10955191e78c2b93b80ec57c00f55dbb137dbd7d2.
   - `[info] sparkSubmitLocal: PASS: scholarchain-spark-jobs-0.1.0.jar: Main-Class scholarchain.spark.job.CollaborationJob; no class under org/apache/spark/, org/apache/hadoop/, org/apache/parquet/, com/amazonaws/, software/amazon/awssdk/, org/apache/logging/log4j/, org/apache/log4j/, org/slf4j/; no scala-library 2.13.16 class; 562 scala/ class(es), all from scala3-library_3 3.3.6; 2916 entries, 2369 classes, 1 distinct entry timestamp(s); ...`
   - `[info] sparkSubmitLocal: /Users/taufeeqpatel/Desktop/ScholarChain/modules/spark/target/scala-3.3.6/scholarchain-spark-jobs-0.1.0.jar bytes=7618495 sha256=568a6e78393a560403e9b19576ddf462bdb98028cc1b26bcb8e76dde45164ef9`
   - `[info] sparkSubmitLocal: downloaded https://archive.apache.org/dist/spark/spark-3.5.5/spark-3.5.5-bin-hadoop3-scala2.13.tgz: sha512 matches the ledger`
   - `[error] sparkSubmitLocal: downloaded and verified spark-3.5.5-bin-hadoop3-scala2.13.tgz into target/spark-dist/ (sha512 a462656d2a87afcf81f24e4dea9a99e6666ed5c8ff7ba0cc9c936188498c549cf852d0ebb475325761d6cf645d1184d527be9a609503d587bb47d5532b533aa1); no Spark run was started in this invocation (tool-call bound); run `sbt sparkSubmitLocal` again; NOT a pass`
   - `[error] Total time: 40 s, completed Nov 18, 2026, 8:23:53 PM`
4. `sbt sparkSubmitLocal` (run 2): exit 1, failed. The same pre-checks and assembly PASS lines repeated, with the same jar sha256. Then, verbatim:
   - `[info] sparkSubmitLocal: cached target/spark-dist/spark-3.5.5-bin-hadoop3-scala2.13.tgz: sha512 matches the ledger; not downloaded`
   - `[info] sparkSubmitLocal: attempt target/spark-submit-local/attempt-1 (create-once; removed only by sbt clean)`
   - `[error] sparkSubmitLocal: the extracted distribution differs from the ledger: bin/load-spark-env.sh is not an executable regular file; attempt target/spark-submit-local/attempt-1 kept`
   - `[error] Total time: 4 s, completed Nov 18, 2026, 8:24:12 PM`
5. `git status --porcelain` (after): unchanged.

There was no third invocation, no sbt clean, no other sbt task and no AWS call.

TEST RESULTS: No tests were run, as the work order instructed. The last recorded `sbt check` is the one reused from BE-P6-09 repair 01: 1570 passed / 0 failed / 0 errored / 0 skipped. No client-mode run happened, so local[2] and local-cluster[2,1,1024] have no exit status or comparator result, and there is no final verdict line.

VALIDATION RESULTS:
- Code-scope check: pass (137 files equal to HEAD 103d05d).
- Archive preflight: pass.
- Job-jar assembly check: PASS (sha256 568a6e78393a560403e9b19576ddf462bdb98028cc1b26bcb8e76dde45164ef9, 7618495 bytes).
- Runtime jars: resolved without a refusal, and the hadoop-aws sha1 check passed silently. Their names and hashes were not printed, because inventory.txt is written only after the failing layout check.
- Distribution: sha512 a462656d…33aa1 matches the ledger. It extracted with no tar refusal.
- Distribution layout: FAILED on `bin/load-spark-env.sh`. That is the only problem reported. `bin/spark-submit` and `bin/spark-class` passed the same check, and the jars/ ledger checks (scala-library, hadoop-client-api/runtime, spark-core/sql) found no problem.
- Not observed, so the two scala-library pins are still unconfirmed:
  - jars/ checked without a reported problem, which suggests spark.distribution.scala-library=2.13.8 is correct. But the success line naming it is printed only after the layout check passes, so I did not see it.
  - spark.runtime.scala-library=2.13.16 was confirmed only by the assembly check, which saw "no scala-library 2.13.16 class". It was not exercised at runtime.

Diagnosis (no repair made):
- The check that refused is `distributionProblems` in /Users/taufeeqpatel/Desktop/ScholarChain/project/SparkSubmitLocal.scala, lines 734-737. It requires `bin/spark-submit`, `bin/spark-class` and `bin/load-spark-env.sh` all to be regular files with the execute bit.
- The build's tar reader (line 509-510) gives a file rwxr-xr-x only when the tar entry has the owner execute bit, otherwise rw-r--r--. So the pinned upstream tarball almost certainly ships `bin/load-spark-env.sh` without the owner execute bit. I could not read the mode directly, because no allowlisted command prints file modes.
- The file exists and is a regular, readable bash script: /Users/taufeeqpatel/Desktop/ScholarChain/target/spark-submit-local/attempt-1/dist/spark-3.5.5-bin-hadoop3-scala2.13/bin/load-spark-env.sh.
- Spark never runs it as a program. spark-class sources it on line 24 (`. "${SPARK_HOME}"/bin/load-spark-env.sh`; /Users/taufeeqpatel/Desktop/ScholarChain/target/spark-submit-local/attempt-1/dist/spark-3.5.5-bin-hadoop3-scala2.13/bin/spark-class), so it does not need the execute bit.
- Conclusion: the build's check is too strict. The distribution and the job code are not at fault.
- Suggested fix for a new work order: require `bin/load-spark-env.sh` to be a regular, readable file only, and keep the execute requirement for spark-submit and spark-class. Add a self-test case for a non-executable load-spark-env.sh, then run `sbt check` and `sbt sparkSubmitLocal` again. The rerun will create attempt-2. The distribution is cached, so there is no new download.

ASSUMPTIONS:
- The non-executable mode comes from the upstream tar entry. I inferred this from the extractor code; I did not observe the mode directly.
- The two scala-library pins remain unconfirmed until a run gets past the layout check.

RISKS:
- Until the check is fixed, the gate cannot run, so Scala 3.3.6 on Spark 3.5.5 with scala-library 2.13.16 put first on the classpath has never been exercised at runtime.
- After the fix, later failures may still show up: classpath conflicts, local-cluster startup inside the 150 s bound, or comparator mismatches.
- attempt-1 is kept until sbt clean. Attempts are create-once, so the next run uses attempt-2.

MANUAL ACTION REQUIRED: The human (or orchestrator) issues a build-engineer repair work order for the layout check in project/SparkSubmitLocal.scala, then reruns BE-P6-10.

BLOCKED-ON OWNER: build-engineer (task machinery in project/SparkSubmitLocal.scala). It needs a new repair work order and a fresh run; I made no repair here, as the work order instructed.

BLOCKED-ON EVIDENCE:
- Run 2 output: `the extracted distribution differs from the ledger: bin/load-spark-env.sh is not an executable regular file; attempt target/spark-submit-local/attempt-1 kept`
- The check: project/SparkSubmitLocal.scala:734-737.
- The tar reader's mode rule: project/SparkSubmitLocal.scala:509-510.
- spark-class sources load-spark-env.sh rather than executing it (bin/spark-class:24 in the extracted distribution).
- The sha512 of the distribution matches the ledger, so the distribution is the pinned one.

NEXT GATE: build-engineer repair (BE-P6-09 repair 02): relax the load-spark-env.sh check, add a self-test case, pass `sbt check`, get code-reviewer review and human commit. Then rerun BE-P6-10 for `sbt sparkSubmitLocal`, which uses the cached distribution with no download and needs one invocation.
```

Orchestrator note: the diagnosis (tar entry lacks the owner execute bit) is inferred, not observed. Before routing repair 02, the human is asked to confirm the tar entry modes in a Terminal, so the repair does not mask a tar-reader bug.
