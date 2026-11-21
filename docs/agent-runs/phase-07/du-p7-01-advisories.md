# DU-P7-01 (dependency-updater): advisory check of the storage Parquet/Hadoop transitives — orchestrator-condensed record

The full report, with every advisory URL, is in the session transcript.

- STATUS: COMPLETE.
- What it did: no files changed, no sbt run, nothing upgraded. Read-only git, Read and about 75 WebFetch calls (GitHub Advisory Database, OSV, project advisories). NVD returned no usable content, so no ID could be cross-checked there.
- **aircompressor 0.21** (affected):
  - CVE-2024-36114 / GHSA-973x-65j7-xcf4: High 8.6, range < 0.27, fixed in 0.27. CONFIRMED.
  - CVE-2025-67721 / GHSA-vx9q-rhv9-3jvg: High, range < 2.0.3, fixed in 2.0.3. 0.27 is still affected, and no 0.x release fixes it.
- **snappy-java 1.1.10.4** (affected):
  - Seven 2026 advisories (GitHub and OSV), all fixed by 1.1.10.9 or 1.1.10.10. One of them, CVE-2026-90559, is in `Snappy.uncompress(ByteBuffer, ByteBuffer)`, the call Parquet's SnappyDecompressor makes.
  - 1.1.10.9 and 1.1.10.10 contain a ByteBuffer compress regression that 1.1.10.11 fixes, so 1.1.10.11 is the safe minimum.
  - CVE-2023-43642 is already fixed in 1.1.10.4.
- **zstd-jni 1.5.0-1** (in range):
  - Falls in several 2026 advisory ranges. Two of them (GHSA-fh94-9rh7-3mwc and GHSA-779h-x4xj-2f4f) list no patched version.
  - Latest release is 1.5.7-28 (2026-11-14). Its compatibility with Parquet 1.13.1 is unverified.
- **No known advisory:** commons-pool 1.6; parquet-hadoop, parquet-column, parquet-common, parquet-encoding, parquet-format-structures and parquet-jackson 1.13.1; hadoop-client-api and hadoop-client-runtime 3.4.1.
  - CVE-2025-27821 affects hadoop-hdfs-native-client, which is not on our classpath.
  - The parquet-avro CVEs do not apply, because parquet-avro is not a dependency.
- Exposure (the agent's own assessment):
  - Low for Phase 7: trusted Parquet from our own Spark job, read offline in local tests.
  - Spark writes Snappy by default, so snappy-java is the library actually exercised. aircompressor and zstd-jni are probably not exercised.
  - Exposure rises if a deployed loader reads Parquet from a bucket that other principals can write to.
  - The jars are Optional and verified absent from rag, services and the services package.
- Recommendations (none applied):
  - snappy-java: override to 1.1.10.11, storage only.
  - aircompressor, three options:
    - (a) 0.27, which fixes CVE-2024-36114 only;
    - (b) 2.0.3, a major version that needs human approval; binary linkage with Parquet 1.13.1 is unverified;
    - (c) documented acceptance.

    The agent leans toward (a) plus (c) for CVE-2025-67721.
  - zstd-jni: pin 1.5.7-28, or document acceptance (ZSTD is not on our data path).
- Ownership of follow-up work:
  - The override mechanism (`dependencyOverrides` in storage, new ledger keys) is build-engineer's.
  - Version values become dependency-updater's after gap G5 is ratified.
  - aircompressor 2.x needs human approval.
- Orchestrator note: several 2026 IDs come only from GitHub advisory pages and OSV, and have no NVD confirmation. Treat them as reported by those sources.
- Open human decisions: the aircompressor path, zstd-jni pin versus acceptance, and whether the snappy-java override goes ahead.
