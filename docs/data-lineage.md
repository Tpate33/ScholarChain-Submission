# Data lineage

The source portfolio and licensing/snapshot details are recorded in
[`dataset-manifest.yaml`](../datasets/dataset-manifest.yaml) and the schema notes
under `datasets/schemas/`. DBLP supplies publication/author mentions; OpenAlex,
ORCID, and Crossref supply enrichment and identifiers. Acquisition and sample
verification are distinct from full-corpus processing.

Pipeline lineage follows source record -> normalized Hadoop record -> ER decision
and canonical identity -> Spark vertices/edges/metrics -> store-load manifest and
checksummed parts -> storage record -> query evidence bundle -> cited answer.
Stable IDs, run IDs, windows, schema versions, and source pointers travel with the
records. The store ledger provides rerun/idempotency checks; invalid or missing
parts must be rejected rather than treated as successfully loaded.

The original synthetic HTTP demo takes a separate route: committed
`modules/services/src/main/resources/scholarchain/local-demo/world.v1.json` ->
`LocalDemoWorld` -> in-memory serving/runtime stores -> workflow -> evidence/explain
responses. This hand-built synthetic fixture is NOT the archived DBLP/Spark output.
Its evidence IDs demonstrate the application's lookup contract, not real scholarly
claims. The source seed documents counts, aliases, and expected example outcomes.

Historical real-data local output evidence is under the Phase 3–7 records and the
external Phase 6 archive. It must not be conflated with the synthetic HTTP replay.
No successful cloud Spark output or completed live store load is claimed.

The new `pipeline-demo` profile reads the human-copied Phase 6 run, curated Hadoop
records, grouping outputs, analysis settings and benchmark list. The accepted part
manifest SHA-256 is pinned. `OpeningLoad` verifies the run chain and bytes before
returning runtime stores. Graph/metric values, canonical IDs and provenance pass
through the existing loaders into in-memory implementations, then through the
normal workflow and HTTP routes. No collaboration metric is re-created by the
launcher. Only API-key placeholders, answer synthesis, embeddings and storage
backends remain non-production. See [reproduction](demo-script.md) and
[verification](verification.md).
