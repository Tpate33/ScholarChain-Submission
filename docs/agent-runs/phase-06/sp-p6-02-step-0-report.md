# Phase 06 SP-P6-02 step 0: spark-engineer report (2026-11-16), verbatim

- Work order (verbatim): docs/agent-runs/phase-06/prompt-sp-p6-02-step-0.md
- HEAD at work: e59d7539d344eece8120c47c0ac6704773fff518

## Agent report, verbatim

STATUS: COMPLETE
OWNER: spark-engineer
WORK ORDER: SP-P6-02 step 0 (Phase 6). A local Spark and Parquet compatibility test in modules/spark/src/test/** only. No build change, no main code, no graph jobs, no AWS.

FILES CREATED:
- /Users/taufeeqpatel/Desktop/ScholarChain/modules/spark/src/test/scala/scholarchain/spark/SparkParquetCompatibilitySuite.scala

FILES MODIFIED: none

COMMANDS RUN (all observed to succeed):
1. `git rev-parse HEAD` printed e59d7539d344eece8120c47c0ac6704773fff518.
2. `git status --porcelain` showed only `?? docs/agent-runs/phase-06/prompt-sp-p6-02-step-0.md` before my work.
3. `git ls-files -- modules/spark modules/core` (to see what the modules contain).
4. A `git grep` with quoted patterns was refused by the guard (shell-syntax-gate) and nothing ran. I read the core sources with Read instead.
5. `sbt sparkAnalytics/Test/compile` compiled 1 Scala source. [success] Total time: 5 s.
6. `sbt sparkAnalytics/test` passed: Total 3, Failed 0, Errors 0.
7. `sbt check`, run once as the final command after my last edit. [success] Total time: 145 s (0:02:25.0).
8. `git status --porcelain` afterwards showed `?? docs/agent-runs/phase-06/prompt-sp-p6-02-step-0.md` and `?? modules/spark/src/test/scala/scholarchain/`.

TEST RESULTS:
`sbt check` passed: verifyBuildSources OK, verifyVersionLedger OK, scalafmtSbtCheck and scalafmtCheckAll OK (spark: 1 source checked), Test/compile, verifyHeldoutOutputRoute, verifyHeldoutGuards and verifyErDevRunGuards, then markTestRun. Counts per project, as markTestRun reported them:

| Project | Production sources | Test sources | Passed |
|---|---|---|---|
| core | 25 | 28 | 557 |
| entityResolution | 40 | 41 | 464 |
| hadoop | 18 | 13 | 85 |
| sparkAnalytics | 0 | 1 | 3 |
| tests | 0 | 18 | 145 |
| rag | 0 | 0 | 0 |
| root | 0 | 0 | 0 |
| services | 0 | 0 | 0 |
| storage | 0 | 0 | 0 |

Every project reported Failed 0, Errors 0. markTestRun wrote /Users/taufeeqpatel/Desktop/ScholarChain/.claude/.last-test-run with git_head=e59d7539d344eece8120c47c0ac6704773fff518, worktree=dirty, suites=90, passed=1254, skipped=0.

New suite `scholarchain.spark.SparkParquetCompatibilitySuite`. The timings below come from the `sbt sparkAnalytics/test` run; the `sbt check` log is 27.5 MB and I did not pull its per-test lines, only its counts.
- `runtime versions observed in the forked test JVM`: 0.264s
- `uPickle JSON of core records round-trips through a Dataset[String]`: 2.222s (includes the first Spark job)
- `Parquet write and read, footer row count, part-manifest v2 verification`: 0.845s
- The whole `sbt sparkAnalytics/test` command, SparkSession startup included, took 5 s.

VALIDATION RESULTS:
- **Runtime versions**, printed by the test inside the forked JVM:
  - spark.version=3.5.5
  - scala.util.Properties.versionString="version 2.13.16"
  - scala3-library jar=scala3-library_3-3.3.6.jar
  - spark-sql jar=spark-sql_2.13-3.5.5.jar
  - parquet Version.FULL_VERSION="parquet-mr version 1.13.1 (build db4183109d5b734ec5930d870cdae161e408ddba)"
  - parquet-hadoop jar=parquet-hadoop-1.13.1.jar
  - hadoop VersionInfo=3.3.4
  - java.version=17.0.17 (Eclipse Adoptium)
  - master=local[1]
- **Session settings:** master local[1], spark.ui.enabled=false, spark.sql.shuffle.partitions=1, spark.default.parallelism=1. The warehouse, local and hadoop.tmp.dir directories are all under one fresh `Files.createTempDirectory` inside java.io.tmpdir (observed path /private/var/folders/.../T/scholarchain-spark-compat-*/local/blockmgr-*). The driver host and bind address are 127.0.0.1, and the time zone is UTC. The session is stopped and the temp tree deleted in afterAll (try/finally).
- **JSON round trip:**
  - I used simpler core records instead of the v2 analysis records: 3 RunManifest records (Json.encode, validated) and 2 TimeWindow records (uPickle).
  - They went into a Dataset[String] through `Encoders.STRING` and through `orderBy("value")`, which adds a real shuffle into one partition. They were collected and decoded with Json.decode and upickle.
  - Results matched exactly in UTF-8 byte order, and the RunIds re-derived from the decoded records equal the originals. No Spark encoder is derived for any Scala 3 case class.
- **Parquet write and read:**
  - A DataFrame with an explicit StructType (string, int, long; not nullable) and 3 rows, written with coalesce(1) and SaveMode.ErrorIfExists, produced exactly 1 part-*.parquet file.
  - Reading it back with the same schema returned the expected rows.
  - The footer row count from ParquetFileReader.getRecordCount is 3. Footer createdBy is "parquet-mr version 1.13.1 (build db41831...)".
- **Part-manifest v2:**
  - I built a run directory with metrics/part-00000.parquet (format parquet, rowCountSource parquet-footer, byteCount, and a SHA-256 computed independently with the JDK MessageDigest), plus _run-manifest.json (jsonl, lf-count, 1 row), _parts.json and _SUCCESS.
  - Checks that returned no errors: `PartManifestV2.build`, `PartManifestVerifier.verifyV2` over the observed bytes, `PartManifestV2.checkRunManifest`, `PartManifests.decode`, which gives Right(Right(manifest)), and `PartManifests.verify`.
  - Newline rule: the file starts and ends with the PAR1 magic, endsWithLf is false, and it still verifies.
  - Counter-test 1: the same bytes declared as jsonl/lf-count fail with "a non-empty file must end in LF".
  - Counter-test 2: a copy truncated by one byte fails on both byteCount and sha256.
- **Analytics:** no graph, window, component or community output and no integrity counters, because no graph job is in scope. Stage DAGs and shuffle metrics: there was one shuffle (orderBy) into 1 partition and one coalesce(1) write; no Spark metrics were collected.

ASSUMPTIONS:
- The work order allowed "simpler existing core records", so I used RunManifest and TimeWindow instead of CoauthorEdgeV2 or ResearcherMetricsV2.
- The part-manifest contract requires a current record schema label for every file. The synthetic Parquet file is labelled `scholarchain.researcher-metrics.v2` only as a placeholder, and a comment in the test says its rows are not records of that schema.
- The synthetic RunManifest uses codeVersion "0"*40, a fixed synthetic configDigest, and snapshot `dblp-synthetic-1`. They are test-only values, never a real run.

RISKS:
- **Hadoop version split:** the sparkAnalytics classpath gets Hadoop 3.3.4 through spark-sql 3.5.5, while the ledger pins hadoop=3.4.1 for the hadoop module. This is harmless for local Parquet here, but there is no stated decision on it for later S3A/EMR work. It is a build-engineer and human topic, and the ledger already notes that the runtime target (review F2) is pending.
- **Scala binary for EMR:** the runtime standard library is 2.13.16 because Spark is used through its _2.13 artifacts. The ledger notes that EMR 7.x Spark is understood to be Scala 2.12, so the cluster runtime target is still undecided (F2).
- **Host address:** Spark logged "hostname resolves to a loopback address ... using 10.0.0.231" and named the executor host 10.0.0.231. The RPC and block manager bound to 127.0.0.1, and no outbound traffic was intended. Setting SPARK_LOCAL_IP would need an env change in build.sbt (build-engineer), which I did not request.
- **Log size:** the `sbt check` output is 27.5 MB, mostly DEBUG logging from the existing hadoop module tests, which makes the per-suite lines hard to read. Not caused by this change.

MANUAL ACTION REQUIRED: The human reviews and commits the new test file (no staging or commit was done). Nothing else.
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: code-reviewer review of SparkParquetCompatibilitySuite.scala, human acceptance, then the next work order for the graph jobs (and later the human F2 decision on the Spark/Scala/Hadoop runtime target before any EMR work).
