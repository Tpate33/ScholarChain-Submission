# Phase 7 → Phase 8 handoff (orchestrator, 2026-11-21)

## State
- Phase 7 local development closeout scope (WP1-WP10) is complete and reviewed (CR-P7-20 APPROVE, no blocking finding). Phase 7 is NOT complete as a whole: live AWS acceptance and deferred items below remain outstanding; nothing is waived. Human acceptance and commit are pending.
- Latest `sbt check`: 2026-11-21 3:07:45 AM CST, exit 0, 162 suites, 2006 passed (human-reviewed).
- Storage API for later phases: docs/storage/README.md and docs/storage/phase-09-11-handoff.md.

## Remaining blockers (none block starting Phase 8 locally)
1. **Human commit** of the Phase 7 tree (steps below). Required before `sbt sparkSubmitLocal` can run.
2. **Not run / not verified:** `sbt sparkSubmitLocal` (needs the commit); `sbt localPipelineDev` (refused: existing attempt-1 output; human chose no rerun; H1 verified only by hadoop tests).
3. **Outstanding Phase 7 requirements (AWS-GATE / deferred):** A1 SDK adapters (S3 Vectors writes are Phase 8 storage work per README L1451); W1-W6 live loading and `sbt storageIT` (row inactive, B10); runtime IAM (infra-engineer); AWS service limits unverified (aws-docs MCP down); curated-zone integrity design for S3 (no .crc); Terraform must declare `dataSha256` non-filterable on vector indexes; Neptune adapter must RETURN and compare (CR-P7-07 m2); Neptune CSV staging to the graph zone (CR-P7-07 n6); multi-run coexistence (deferred by the human); core StorageConfig keys for S3 zones, Parquet bounds, Neptune loader settings (domain-engineer).
4. **Dependency residuals:** Netty 4.1.141 compatibility evidence is our tests only (no runtime gate for netty-nio-client); Netty 4.1 EOL 2027-07-01; the pinned Spark distribution and EMR carry their own Netty/lz4/zstd (R5); Hadoop jar scan limits documented.

## Phase 8 inputs and decisions to make early
- Embeddings: real model ID format (pin it; `isSynthetic` is a case-sensitive label check, CR-P7-20 n4); determinism not required (vector digest supports nondeterministic embedders); a new model version under the same vector key is a Conflict today — Phase 8's "skip unchanged by content hash and model version" needs a key/versioning decision.
- Rendering ownership: storage's `ContextText.render` (context-document v2) vs rag-engineer prompt templates — split explicitly.
- Corpus: fixtures give 249 units; the requirement is ≥10,000 vectors (no instructor approval for less).
- Build time: each `sbt check` now takes ≈3 h because the tests JVM logs at DEBUG (deferred); fixing logging (build-engineer/test-engineer) would make Phase 8 iterations practical.
- Carried notes: Unicode line terminators raw in quoted fact lines (rag reader must split on LF only); vector float32 exactness on S3 Vectors unverified (must fail closed); one extra GetVectors per query.

## Commit steps for the human (run in your Terminal; the human commits)
```sh
cd /Users/taufeeqpatel/Desktop/ScholarChain
git status --porcelain
git add build.sbt project/versions.properties project/DependencyScopes.scala project/RagSourceImports.scala project/SparkSubmitLocal.scala \
  modules/storage/src modules/spark/src/test/scala/scholarchain/spark/SparkParquetCompatibilitySuite.scala \
  modules/spark/src/test/scala/scholarchain/spark/testkit/LocalSpark.scala modules/spark/src/test/scala/scholarchain/spark/testkit/TestCompressionSuite.scala \
  tests/src/test/scala/scholarchain/tests/storage docs/storage docs/agent-runs/phase-07
git status --porcelain
```
Review the staged list (it must not include anything under target/, any .env or credential file, or gold/), write `docs/agent-runs/phase-07/human-gate.md` (what you reviewed, accepted/rejected, remaining risk), then commit, e.g. `git commit -m "Phase 7: storage loaders, local closeout (live AWS outstanding)"`, and record the hash in `docs/agent-runs/phase-07/commit.txt` (human-owned files).
