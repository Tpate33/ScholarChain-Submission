# DU-P7-03 (dependency-updater, read-only, no Bash): codec jars and netty on other modules' test classpaths — orchestrator-condensed record

The full report (with citations, accessed 2026-11-20) is in the session transcript. No file changed; no command run.

## D1 advisories (primary sources)
- snappy-java 1.1.10.4 (hadoop-test) and 1.1.10.5 (spark-test, tests-test): seven xerial advisories published 2026-10-03 (GHSA-wmgv-28fv-894x CVE-2026-90559, GHSA-gwq9-9ffj-mvrv CVE-2026-93451, GHSA-q233-g92q-qxp6 CVE-2026-108106, GHSA-xqrx-qh46-34m7 CVE-2026-116590, GHSA-38f5-hf66-x965 fixed 1.1.10.9; GHSA-6gp7-6wmv-gxqw, GHSA-c73m-r934-8qvg fixed 1.1.10.10). 1.1.10.11 is at or above every fix. DU-P7-02's CVE-2026-93452 and -108104 mapping unconfirmed.
- aircompressor 0.27: CVE-2025-67721 (GHSA-vx9q-rhv9-3jvg), fixed only in 2.0.3 (major).
- zstd-jni 1.5.5-4: nine advisories with fixes (up to 1.5.7-25); four with no confirmed fix (GHSA-8hfr-vm4w-9gfg, GHSA-fh94-9rh7-3mwc, GHSA-779h-x4xj-2f4f, GHSA-c62c-wjcx-gjxx — contradictory or no patch). Newest 1.5.7-28; coverage unconfirmed.
- netty 4.1.96.Final (spark-test): CVE-2026-42587 (fixed 4.1.133.Final) plus GHSA-pvjx-v7vp-62vq and CVE-2026-93492 (fixed 4.1.138.Final); Netty release notes list 13 advisories fixed in 4.1.139.Final and 8 in 4.1.140.Final; 4.1.141.Final fixes the 4.1.140 glibc build.

## D2 reachability (test scope only; Spark/Hadoop Provided and dropped from assemblies)
- hadoop-test: committed run manifest uses DefaultCodec/GzipCodec (JDK zlib); snappy use in hadoop tests unverified.
- spark-test/SparkIT: snappy-java REACHED (RunPublisher writes Parquet with Spark's default snappy); lz4-java 1.8.0 REACHED (Spark default shuffle/broadcast codec); zstd-jni probably not reached; aircompressor not reached; netty HTTP codecs probably not reached (transport uses buffer/handler/transport; loopback only). Inputs are repository fixtures and test-written data.
- tests-test: same Spark/Hadoop codec jars; netty mixed (most modules 4.1.138.Final via AWS SDK eviction; some 4.1.96.Final).

## D3 bounded proposal (build-engineer; test scopes of hadoop, sparkAnalytics, tests)
1. hadoop: exclude snappy-java from hadoop-client-api/runtime (H1); fallback H2 override to 1.1.10.11.
2. sparkAnalytics: exclude aircompressor and zstd-jni from spark-sql; `dependencyOverrides += snappy-java 1.1.10.11`.
3. tests: same exclusions; override snappy-java.
4. netty: override all 16 io.netty modules on sparkAnalytics and tests to a new ledger key `netty=4.1.141.Final` (4.1.140.Final is the minimum covering every identified netty advisory).
5. Proof: `sbt check` with all hadoop, sparkAnalytics, tests suites; new FAIL checks replacing the INFO line (hadoop/spark/sparkit/tests test classpaths; netty at ledger; hadoop and sparkAnalytics Runtime and assemblies free of codec/netty); extend INFO to lz4-java coordinates; optionally `sbt localPipelineDev`, `sbt sparkIT`, `sbt sparkSubmitLocal` (ask-mode).
- Note: `dependencyOverrides` applies project-wide (also the Provided Compile classpath, never packaged).

## Remaining risk requiring human decision (none accepted)
- R1 zstd-jni: no release fixes every advisory; keep the exclusion, or approve 1.5.7-28 with open advisories recorded.
- R2 lz4-java 1.8.0 reached on Spark test JVMs (CVE-2025-12183 fixed 1.8.1; CVE-2025-66566 and CVE-2026-106452 have no org.lz4 fix, fixed under at.yawk.lz4 1.10.1/1.11.2); fix needs a coordinate change (human approval) or a test-config codec change (spark-engineer).
- R3 PRODUCTION netty: storage and services Runtime resolve netty 4.1.138.Final via AWS SDK 2.57.8, affected by the 4.1.139 and 4.1.140 advisories; needs its own work order (AWS SDK BOM bump or netty override on storage and services).
- R4 aircompressor fix only in 2.0.3 (major) if exclusion breaks tests.
- R5 runtimes sbt does not control (pinned Spark 3.5.5 distribution for sparkSubmitLocal; EMR) ship the vulnerable versions.
- R6 unverified items (SnappyCodec/ORC/LZ4_RAW/ZSTD use in hadoop/tests suites; zstd in Spark local mode; hadoop-client-runtime shaded contents; two CVE mappings; unreviewed GHSA records).
- R7 G5 unratified.
