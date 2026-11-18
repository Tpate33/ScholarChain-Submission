# Phase 06 BE-P6-09: build-engineer report, packaging and local spark-submit gate (2026-11-17)

- Work order: routed under prompt-emr-preparation.md. Base HEAD bd9c0a5 plus uncommitted SP-P6-05 (not touched).
- STATUS: BLOCKED-ON spark-engineer (comparison entry point). Everything in build territory done; `sbt sparkSubmitLocal` not run (row not ratified; it also refuses until the comparator exists and the code scope is committed).

## Files

- Created: project/SparkSubmitLocal.scala (assembly content check, ledger pin check, distribution cache and download, bounded tar reader accepting only regular files and directories, spark-submit command and child environment, per-run output checks, comparison PASS rule `verdict`, task, self-test `verifySparkSubmitLocalGuards` 81 cases).
- Modified: build.sbt (keys and root tasks `sparkJobAssemblyCheck`, `sparkSubmitLocal`, `verifySparkSubmitLocalGuards`; sparkAnalytics assembly `mainClass = scholarchain.spark.job.CollaborationJob`, `assemblyExcludedJars` drops org.scala-lang:scala-library, `assemblyRepeatableBuild := true`; `check` gains `verifySparkSubmitLocalGuards` before `markTestRun`; `packageJobs = ; hadoop/assembly ; sparkAnalytics/assembly ; sparkJobAssemblyCheck`); project/LocalRuns.scala (refactor: shared `scopeEqualsHead`, `scopeIgnoredAllowed`, `gitRunnerAt`; erDev messages byte-identical, verifyErDevRunGuards 128 PASS); project/versions.properties (9 new keys). .gitignore unchanged (`/target/` covers the new paths).

## Ledger pins (new; to be confirmed by the human or dependency-updater)

```
spark.distribution.url=https://archive.apache.org/dist/spark/spark-3.5.5/spark-3.5.5-bin-hadoop3-scala2.13.tgz
spark.distribution.file=spark-3.5.5-bin-hadoop3-scala2.13.tgz
spark.distribution.sha512=a462656d2a87afcf81f24e4dea9a99e6666ed5c8ff7ba0cc9c936188498c549cf852d0ebb475325761d6cf645d1184d527be9a609503d587bb47d5532b533aa1
spark.distribution.scala-library=2.13.8   (expected; not verified; the gate refuses otherwise)
spark.distribution.hadoop-client=3.3.4
spark.runtime.scala-library=2.13.16
hadoop-aws.s3a=3.3.4
hadoop-aws.s3a.jar-sha1=a65839fbf1869f81a1632e09f415e586922e4f80
aws-sdk-bundle.s3a=1.12.262
```

No checksum pinned for aws-java-sdk-bundle 1.12.262 or scala-library 2.13.16 (Coursier stores no .sha1); the gate records their sha256 in `inventory.txt`. Existing `spark=3.5.5` and `hadoop=3.4.1` unchanged. Module graph unchanged.

## scala-library decision (one approach)

scala3-library_3 3.3.6 depends on scala-library 2.13.16; the distribution is believed to ship 2.13.8. The job jar carries no scala-library; scala-library 2.13.16 is placed first on the driver (`--driver-class-path`) and executor (`spark.executor.extraClassPath`) system classpath so Spark and the job share one library on one loader (2.13 is backward compatible). Rejected: `userClassPathFirst` (a second `scala.*` copy in a child-first loader breaks closures passed between Spark and job code). Distribution never modified.

## Commands and results

- `sbt Test/compile`: success.
- `sbt packageJobs`: success; `sparkJobAssemblyCheck: PASS: scholarchain-spark-jobs-0.1.0.jar: Main-Class scholarchain.spark.job.CollaborationJob; no class under org/apache/spark/, org/apache/hadoop/, org/apache/parquet/, com/amazonaws/, software/amazon/awssdk/, org/apache/logging/log4j/, org/apache/log4j/, org/slf4j/; no scala-library 2.13.16 class; 562 scala/ class(es), all from scala3-library_3 3.3.6; 2916 entries, 2369 classes, 1 distinct entry timestamp(s); …`; jar `bytes=7618495 sha256=568a6e78393a560403e9b19576ddf462bdb98028cc1b26bcb8e76dde45164ef9` (dirty tree; not a staging artifact). "Version ledger OK: 24 pinned entries".
- `sbt check` (once; background after 180 s; exit 0, `[success] Total time: 288 s (0:04:48.0)`):
  ```
  [info] Passed: Total 621, Failed 0, Errors 0, Passed 621
  [info] Passed: Total 464, Failed 0, Errors 0, Passed 464
  [info] Passed: Total 85, Failed 0, Errors 0, Passed 85
  [info] Passed: Total 249, Failed 0, Errors 0, Passed 249
  [info] Passed: Total 145, Failed 0, Errors 0, Passed 145
  [info] markTestRun: wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run (git_head=bd9c0a5a195a86a6b8df9a39de3eabc50db124f7, worktree=dirty, suites=111, passed=1564, skipped=0)
  ```
  Self-tests: verifyHeldoutGuards PASS (23 + 17), verifyErDevRunGuards PASS 128, verifySparkItGuards PASS 204, verifySparkItRunner PASS 8, `verifySparkSubmitLocalGuards: PASS: 81 cases gave the expected verdict (temporary directories only; no network, no fork)`.
- Hook events: after the background check, `git status --short` refused by `audit-bash-writes.sh --pre` as UNATTRIBUTED, changed path `.claude/.last-test-run` (GENERATED) only; marker read (`written_at_utc=2026-11-18T03:28:22.643971Z`, `git_head=bd9c0a5…`, `worktree=dirty`, `suites=111`, `tests_passed=1564`, failed/errored/skipped 0, `production_sources=112`), matches; re-run once. Guard refusals (nothing executed): one `git grep`, one `git status --short -- project`.
- Not run: `sbt clean`, AWS calls, `sbt sparkSubmitLocal`. Archive unchanged; no gold/ or held-out reads.

## sparkSubmitLocal design (not run)

Pre- and post-compile checks: JDK release 17; ledger consistency; code scope `build.sbt project modules/core modules/spark` clean and byte-identical to HEAD (HEAD becomes `--code-version`); sparkIT preflight (spark-it.properties, archive layout, SCHOLARCHAIN_CFG_*, held-out, gold/, target/streams refusals); committed analysis-dev.conf and benchmark-pairs-dev.v1.json. Then: comparator present; assembly check; three runtime jars resolved intransitively, hadoop-aws sha1 checked. Cache `target/spark-dist/`: sha512 match reused, mismatch refused and kept; HTTPS GET with no redirect, ≤512 MiB, ≤420 s, partial file hard-linked only on sha512 match, only the partial deleted; a downloading invocation stops with "NOT a pass". Per run a fresh create-once `target/spark-submit-local/attempt-<n>/` (dist/, jars/ checked, runtime-jars/, job/, inventory.txt). Two client-mode runs with the distribution's `bin/spark-submit` (`local[2]`, `local-cluster[2,1,1024]`), event log per run, both required confs, Java 17 opens, loopback only; 150 s per run, 120 s comparison. PASS: both exit 0; each exactly one `run=<hex>` with `_SUCCESS`, `_parts.json`, `_run-manifest.json`, no `.spark-*` left, one complete event log; same run name and byte-equal `_run-manifest.json`; nonce-bound comparison showing 28 datasets equal, edges 259, metrics 176, load manifests neptune 12/1258, dynamodb 12/1302, s3 16/2809, s3-vectors 2/249. Writes result.txt and submit-commands.txt. Child environment strips AWS_*, SCHOLARCHAIN_*, GIT_*, MUNIT_*, SPARK_*, HADOOP_*, YARN_*, PYSPARK_*, JAVA_TOOL_OPTIONS, _JAVA_OPTIONS, JDK_JAVA_OPTIONS, JAVA_OPTS, SBT_OPTS, CLASSPATH.

## Allowlist row proposal (for factory-engineer)

Exactly `sbt sparkSubmitLocal`; identities build-engineer and spark-engineer; mode `ask`. Writes target/spark-dist/, target/spark-submit-local/attempt-<n>/, usual build output and target/streams/; not target/local-runs/ or the marker; never in check; child code opaque. Reads the repository, `<user.home>/ScholarChain-archive/phase-06/dev-run-01`, Coursier cache. Network: one HTTPS GET to archive.apache.org on a cache miss; repo1.maven.org via Coursier; 127.0.0.1 listeners; no AWS. Time: download invocation ≤420 s and stops; run invocation ≤150+150+120 s plus start-up, within 600 s. No `clean` token, no held-out name.

## BLOCKED-ON spark-engineer: comparator interface (verbatim)

Main class `scholarchain.spark.testkit.PublishedRunComparisonMain` in the sparkAnalytics Test tree, forked on `sparkAnalytics/Test/fullClasspath` with the Java 17 opens. Arguments `--left-output-root <abs dir> --right-output-root <abs dir> --nonce <64 hex> --result <abs file that must not exist>`. Per root: exactly one `run=<hex>`, verified with the existing verified reader; row multisets compared for the 16 Parquet and 12 Neptune CSV datasets of LogicalReproducibility; edge and metric counts and every load manifest's items and rows reported. Exit 0 compared (result written), 1 could not compare, 2 arguments refused. Facts only; the build applies the baseline. Result (UTF-8, LF, final LF, tab-separated):

```
format=scholarchain.spark.published-run-comparison.v1
nonce=<hex>
run	left	<run=hex>
run	right	<run=hex>
count	<left|right>	<edges|metrics>	<n>
load	<left|right>	<store>	<items>	<rows>
dataset	<name>	<leftRows>	<rightRows>	<equal|different>
datasets=<number of dataset lines>
complete=<hex>
```

Store tags neptune, dynamodb, s3, s3-vectors. Parser and PASS rule: `SparkSubmitLocal.parseComparison` and `verdict`.

## For infra-engineer

Artifacts: (1) spark-3.5.5-bin-hadoop3-scala2.13.tgz, 391M, sha512 as pinned, extracted by bootstrap to `/opt/scholarchain/spark-3.5.5-bin-hadoop3-scala2.13`; (2) scala-library-2.13.16.jar, (3) hadoop-aws-3.3.4.jar (sha1 a65839fb…4f80), (4) aws-java-sdk-bundle-1.12.262.jar into `/opt/scholarchain/runtime-jars/` (sha256 of 2 and 4 from the first gate inventory); (5) the job jar rebuilt by `sbt packageJobs` at the committed commit (not 568a6e78…); (6) analysis-dev.conf and benchmark-pairs-dev.v1.json from `modules/spark/src/test/resources/scholarchain/spark/dev-run-01/`.

EMR client-mode command (verbatim from the report):

```
SPARK_HOME=/opt/scholarchain/spark-3.5.5-bin-hadoop3-scala2.13 SPARK_CONF_DIR=/opt/scholarchain/spark-conf HADOOP_CONF_DIR=/etc/hadoop/conf \
/opt/scholarchain/spark-3.5.5-bin-hadoop3-scala2.13/bin/spark-submit \
 --master yarn --deploy-mode client --name scholarchain-collaboration-analysis \
 --class scholarchain.spark.job.CollaborationJob --driver-memory 1g \
 --driver-class-path /opt/scholarchain/runtime-jars/scala-library-2.13.16.jar:/opt/scholarchain/runtime-jars/hadoop-aws-3.3.4.jar:/opt/scholarchain/runtime-jars/aws-java-sdk-bundle-1.12.262.jar \
 --driver-java-options "<OPENS>" \
 --conf spark.executor.extraClassPath=/opt/scholarchain/runtime-jars/scala-library-2.13.16.jar:/opt/scholarchain/runtime-jars/hadoop-aws-3.3.4.jar:/opt/scholarchain/runtime-jars/aws-java-sdk-bundle-1.12.262.jar \
 --conf "spark.executor.extraJavaOptions=<OPENS>" \
 --conf spark.eventLog.enabled=true --conf spark.eventLog.dir=hdfs:///scholarchain/spark-events \
 --conf spark.yarn.maxAppAttempts=1 --conf spark.hadoop.fs.s3a.directory.marker.retention=keep \
 /opt/scholarchain/job/scholarchain-spark-jobs-0.1.0.jar \
 --input-root s3a://<bucket>/<archive-prefix> --curated-run <rel> --decision-run <rel> --grouping-run <rel> \
 --output-root s3a://<bucket>/<output-prefix> --checkpoint-dir hdfs:///scholarchain/checkpoints/<attempt> \
 --config <path or s3a:// to analysis-dev.conf> --benchmark-pairs <path or s3a:// to benchmark-pairs-dev.v1.json> \
 --code-version <40-hex commit of the jar> --attempt 1 --partitions <n> --checkpoint-interval 2 \
 --max-tree-entries 20000 --max-tree-depth 4 --max-driver-validation-rows 2000 \
 --max-driver-validation-bytes 67108864 --max-metadata-bytes 1048576
```

`<OPENS>` = the Java 17 `--add-opens` set for java.lang, java.lang.invoke, java.lang.reflect, java.io, java.net, java.nio, java.util, java.util.concurrent, java.util.concurrent.atomic, sun.nio.ch, sun.nio.cs, sun.security.action, sun.util.calendar (all `=ALL-UNNAMED`) plus `-Djdk.reflect.useDirectMethodHandle=false`. Optional `spark.yarn.jars=local:/opt/scholarchain/spark-3.5.5-bin-hadoop3-scala2.13/jars/*` avoids ~300 MB staging per submit (not exercised locally). S3A credentials: default provider chain (instance profile), unverified on EMR.

## Risks (as reported)

Nothing run end to end; scala-library 2.13.16 over the distribution's 2.13.8 scala-reflect/compiler/xml untested; GPG not verified; no checksum pin for the SDK bundle or scala-library; ~0.4 GB cache plus ~1 GB per attempt; SDK first resolution time; comparison JVM may inherit JAVA_TOOL_OPTIONS-class names (prefix stripping only); repeatable build not tested across two builds; gate refuses until SP-P6-05 and BE-P6-09 are committed; BE-P6-08 risks remain.

NEXT GATE: spark-engineer comparator; factory-engineer row draft and human ratification; combined code review; human commit; `sbt sparkSubmitLocal` twice (download, then runs).
