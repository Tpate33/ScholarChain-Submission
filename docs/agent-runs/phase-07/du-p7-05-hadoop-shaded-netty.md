# DU-P7-05 (dependency-updater, read-only, no Bash): Hadoop-bundled Netty (C3) — orchestrator-condensed record

The full report (citations accessed 2026-11-20) is in the session transcript. No file changed; no command run.

## Headline: DU-P7-04 finding C3 was wrong
- hadoop-client-runtime 3.4.1 contains NO Netty: its shade configuration has `<exclude>io.netty:*</exclude>` (added by HADOOP-15327, commit 5bb11cec…, fix version 3.4.0); its POM passes no Netty dependency; hadoop-client-api 3.4.1 includes only `org.apache.hadoop:*`. DU-P7-04 had misread hadoop-project's `netty4.version=4.1.100.Final` as the shaded content. Relocated references to `org.apache.hadoop.shaded.io.netty.*` inside hadoop-client-api point at classes that do not exist (NoClassDefFoundError if called; cannot run vulnerable code). 3.4.3 keeps the exclusion.
- hadoop-client-runtime 3.3.4 (Spark 3.5.5's client on sparkAnalytics Test, SparkIT, and in the sparkSubmitLocal distribution): no io.netty exclusion in its shade set, but tracing every dependency path finds no Netty 4 brought in; PROBABLY absent, NOT proven at jar level (would be 4.1.77.Final if present).

## Advisories
- 3.4.1: none apply (no Netty bytes). 3.3.4: none if the POM analysis holds; if shaded 4.1.77 is present, every 4.1.x advisory fixed after 4.1.77 applies to the modules present, and scanners may miss relocated copies.
- Unshaded Netty findings (AWS SDK 4.1.138 on storage/services Runtime; Spark 4.1.96 on spark-test/tests-test) unaffected; still handled by `netty=4.1.141.Final`.

## Reachability
- storage: parquet-mr 1.13.1 `ParquetFileReader.open(InputFile, options)` with a supplied codec factory uses no Hadoop Configuration/Path/FileSystem; `HadoopCodecs.newFactory` (new Configuration) only when no factory supplied — storage supplies one. Hadoop Netty neither present nor reachable. (Separately: AWS SDK netty-nio-client 4.1.138 on storage/services Runtime, present but unreachable today — no AWS adapter; DU-P7-04 C1.)
- hadoop Test: no io.netty modules; LocalJobRunner does not use the Netty shuffle server.
- tests Test: 3.4.1 expected to win over 3.3.4 (assumption, unobserved).
- sparkAnalytics Test/SparkIT/sparkSubmitLocal distribution: 3.3.4, shaded Netty probably absent; low expected reachability in local[N].

## Options (smallest first)
- (d) RECOMMENDED: jar-content check in verifyDependencyScopes (build-engineer): open every hadoop-client-api/-runtime jar on storage Compile/Runtime/Test, hadoop Test, sparkAnalytics Test and SparkIT, tests Test; FAIL on entries under `org/apache/hadoop/shaded/io/netty/`, `io/netty/`, `META-INF/maven/io.netty/`, `META-INF/native/*netty*`; print per-jar counts and the Hadoop client revision per classpath; self-test with a synthetic jar. (d2) only if 3.3.4 contains Netty: override hadoop-client to the ledger 3.4.1 on sparkAnalytics Test/SparkIT (human approval; proof sparkIT + sparkSubmitLocal).
- (c) optional hardening: remove Hadoop from storage Compile/Runtime (keep at Test); unverified class-loading; breaks the ReaderClasspath check and current scope checks; not needed for Netty.
- (b) not recommended: unshaded hadoop-common would ADD Netty 4.1.100 plus Jetty/ZooKeeper/Curator.
- (a) not recommended for Netty: Hadoop upgrade (3.4.2 pins 4.1.118, 3.4.3 4.1.127, 3.5.0 4.1.130 — all below the 4.1.133+ fixes; human approval required).

## Remaining if no option is taken (not an acceptance)
- 3.4.1: no Hadoop Netty exposure (POM/shade/commit evidence, not an observed jar listing).
- 3.3.4: absence unverified at jar level; test-only/local path, low reachability.
- EMR's Hadoop distributions outside the build (R5).
