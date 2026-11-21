# CR-P7-01 (code-reviewer) — build dependency changes — orchestrator-condensed record

The full report is in the session transcript.

- VERDICT: APPROVE. No verified critical or major defect.
- STATUS: COMPLETE. Read-only: no files changed, no commands run (Read, Grep and Glob only). The test results are build-engineer's evidence; the reviewer did not observe them.
- Scope: build.sbt, project/versions.properties, project/DependencyScopes.scala (BE-P7-01 and repair 01).
- What the reviewer verified:
  - The checks read resolved `fullClasspath` and the `Universal` and `Docker` mappings (build.sbt:669-705; DependencyScopes.scala:97-128). Jars are matched by module ID or repository path, which also covers sub-organizations.
  - The sbt classpath exports match the reported results: storage has Parquet/Hadoop and the SDK; services has the SDK only; rag has 12 entries, with no amazon, parquet, hadoop or netty jar.
  - The storage POM marks the three Parquet/Hadoop dependencies `<optional>true</optional>`. The rag POM excludes `software.amazon.awssdk:*`. Caveat: these POMs are build output.
  - A FAIL throws before markTestRun (DependencyScopes.scala:394-398; build.sbt:742, 749).
  - Output is deterministic, and the task writes no file of its own.
  - No literal versions in build.sbt; verifyVersionLedger is unchanged.
  - The module graph is unchanged and matches CLAUDE.md; packageJobs is unaffected.
  - **rag ports-only: vacuous.** modules/rag/src contains only .gitkeep files.
  - **storage port, model and fake packages: substantive.** All 24 main sources import only scala.*, java.nio.charset, upickle.*, scholarchain.core.* and scholarchain.storage.{model,port}.*. There are no SDK or Apache imports, and no `scholarchain.storage.aws` package exists yet.
  - No hook, marker or out-of-territory change.
- FINDINGS (build-engineer):
  - F1 (minor). The rag ports-only rule is enforced on the classpath only, not in source. A future `import scholarchain.storage.aws.*` in rag is undetected. Fix: a source-import check over modules/rag/src/** in `check`, or a separate adapters project (a module-graph amendment, needing factory-engineer and human ratification).
  - F2 (minor). The tests Test classpath mixes Netty 4.1.96 (netty-all and the native transports) with 4.1.138, which the SDK brings in through `storage % Test`. Not an EMR or packageJobs defect. Fix: align or exclude Netty, or accept explicitly.
  - F3 (nit). Leak-check gaps: Parquet's non-Apache transitives, unmanaged jars, AWS SDK v1 (com.amazonaws), and path-only SDK jars in awsAligned.
  - F4 (nit). The self-test requires only that some check fails for each broken input, not the intended check.
- RISKS:
  - R1 (storage-engineer). Parquet/Hadoop on Java 17 has been resolved but never run; increment 4 is the first real proof.
  - R2 (human, then build-engineer). Loader execution is proven only on the sbt `storage/Runtime` classpath. No loader packaging exists, and the services image excludes Parquet, so the loader host needs a decision.
  - R3. slf4j-api 1.7.36 has no binding on storage Runtime.
  - R4. Each `check` now builds the services packaging, which costs time and disk.
- UNVERIFIED:
  - U1. Whether evaluating the Docker mappings starts a docker process.
  - U2 (dependency-updater). Possible CVE in aircompressor 0.21 (believed CVE-2024-36114, fixed in 0.27); zstd-jni 1.5.0-1 and commons-pool 1.6 are old. Not checked against an advisory database.
  - U3. Whether Scala 3 rag code could compile against an adapter without the SDK.
  - U4. The build outputs read may not be from the final configuration.
- NEXT: the human accepts or rejects the verdict. Route F1-F4 to build-engineer, R1 to storage-engineer, U2 to dependency-updater, and the R2 decision to the human. Re-review rag when it gets sources.
