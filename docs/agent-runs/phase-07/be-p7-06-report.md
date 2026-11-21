# BE-P7-06 (build-engineer): bounded dependency remediation, cross-module test setup, C3 option (d) — orchestrator-condensed record

The full report is in the session transcript.

- STATUS: BLOCKED-ON human (procedural marker review). `sbt check` (task baruj2dzn) backgrounded; completion notification exit 0; agent read: core 621, entityResolution 464, hadoop 85, sparkAnalytics 263, storage 390, tests 145; rag/root/services 0; all Failed 0 Errors 0; markTestRun suites=156, passed=1968, skipped=0; `[success] Total time: 357 s (0:05:57.0), completed Nov 20, 2026, 10:17:38 PM`; `[exited with code 0]`. Marker read by the orchestrator: written_at_utc=2026-11-21T04:17:38.233436Z, same counts.
- `sbt packageJobs` NOT run (no Bash after the backgrounded check); extended assembly checks covered only by self-test cases so far.

## Changes
- project/versions.properties: `netty=4.1.141.Final` (comment: advisories fixed per DU-P7-04; compatibility evidence from our tests only; Netty 4.1 EOL 2027-07-01); ledger 28 entries.
- build.sbt: hadoop H1 (snappy-java excluded from hadoop-client-api/runtime); sparkAnalytics spark-sql excludeAll(aircompressor, zstd-jni, lz4-java) + netty and snappy-java overrides; storage and services netty overrides; tests: `storage % "test->test"`, parquet-hadoop % Test, Hadoop client and parquet-hadoop excludeAll(snappy, aircompressor, zstd, lz4), spark-sql exclusions, netty and snappy overrides; verifyDependencyScopes reads 11 classpaths and the Hadoop jar list; new `hadoopJobAssemblyCheck` in `packageJobs`.
- project/DependencyScopes.scala: header rewritten (CR-P7-14 n2); Netty, codec and Hadoop-client content checks; CR-P7-14 n1 fixed (id-only self-test inputs).
- project/SparkSubmitLocal.scala: ForbiddenPrefixes extended (io/netty/, org/xerial/snappy/, com/github/luben/, io/airlift/compress/, net/jpountz/); new hadoop prefix check; 7 new guard cases.

## Validation (verifyDependencyScopes: OK, 64 checks passed)
- Self-tests: 63 broken + 1 good; hadoop-client-netty-self-test 11 broken jar sets (relocated, ordinary, thirdparty, multi-release, Maven-metadata, native-library Netty; corrupt, unreadable, missing jar; missing client jar; no client jar) + 1 clean.
- tests-test has parquet-hadoop 1.13.1 + hadoop 3.4.1; rag compile/test no Parquet/Hadoop; hadoop-test and hadoop/spark Runtime no native codecs; spark-test, sparkit-test, tests-test exactly snappy-java 1.1.10.11, no aircompressor/zstd-jni/lz4-java.
- Netty: storage/services (incl. Universal and Docker) exactly the 10 SDK modules; spark-test, sparkit-test, tests-test 20 jars covering exactly 16 modules (native classifiers); all at 4.1.141.Final; no Netty on rag, hadoop-test, hadoop/spark Runtime.
- INFO: io.netty all 4.1.141.Final; snappy-java 1.1.10.11 on storage/spark/sparkit/tests Test only; no aircompressor, zstd-jni or lz4-java anywhere.

## C3 option (d) results — Hadoop client jars (every jar: Netty entries 0)
| Classpath | hadoop-client-api | hadoop-client-runtime |
|---|---|---|
| storage-compile/runtime/test, hadoop-test, tests-test | 3.4.1 (9373 entries) | 3.4.1 (19609 entries) |
| spark-test, sparkit-test | 3.3.4 (9183 entries) | 3.3.4 (19628 entries) |
- 3.4.1 wins on tests Test (observed). DU-P7-05's 3.4.1 claim CONFIRMED; 3.3.4 resolved as no Netty at jar level within the scanned patterns. No Hadoop version changed.
- **Correction recorded:** DU-P7-04 finding C3 ("hadoop-client-runtime 3.4.1 shades netty 4.1.100") is withdrawn; both resolved Hadoop client versions contain no bundled Netty entries (ordinary or relocated paths containing io/netty/).

## Outcomes
- H1 succeeded (no NoClassDefFoundError; H2 not needed). L1 succeeded (lz4-java excluded; no class-load failure).
- Module graph: only tests → storage test->test (within CLAUDE.md "tests → any module, test scope only"); rag and services have no Parquet/Hadoop (checked).
- Provided-scope exclusions/overrides do not reach job assemblies.

## Risks
- Netty 4.1.141 compatibility with AWS SDK 2.57.8 and Spark 3.5.5 rests on our tests only; no gate exercises netty-nio-client at runtime (C1); EOL 2027-07-01.
- sparkSubmitLocal's pinned Spark distribution still carries Netty 4.1.96, lz4, zstd (R5); not yet run; needs the human's commit first.
- ORC/zstd/lz4 paths would now fail at class load (C10).
- tests Test JVM logs Hadoop at DEBUG (UserGroupInformation traces); output 228,737 lines; origin not established (test-engineer or build-engineer logging config).
- Content scan detects io/netty/ path patterns only; a relocation without io/netty/ in the path would not be detected.
- tests→storage test->test exposes storage's test classes to tests compilation (not run there; tests count stayed 145).
