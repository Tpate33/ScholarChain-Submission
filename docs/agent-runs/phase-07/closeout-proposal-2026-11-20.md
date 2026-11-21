# Phase 7 bounded development closeout: proposal (orchestrator, 2026-11-20) — FOR HUMAN DECISION

Status: PROPOSED. Not approved. Phase 7 is NOT complete and no requirement is waived. Requirement IDs refer to requirements-checklist.md (spec README.md at d23d307, Phase 7 L1350-1432).

Goal of the closeout: a working, locally verified **fixture → verified load → populated local stores → retrieval ports** flow that Phases 9-11 (rag, services, tests) can build on, with every invalid or unverified write refused. Live AWS acceptance (W1-W6) stays outstanding.

Rules kept: per-increment `sbt check`, human marker review of each changed marker, code-reviewer review of each increment, builds sequential, no live AWS, commits, cleanup, hook or constitutional changes.

## 1. Required now (local storage-to-retrieval flow)

| WP | Owner | Content | Checklist IDs | Tests that must exist |
|---|---|---|---|---|
| WP1 (running: SE-P7-04) | storage-engineer | m1: gate refuses while deferred checks for the store are outstanding. m2: verified bytes bound to the VerifiedRun or re-hashed at read. Mirror/`fromProduct` negative compile test. Cheap nits. Codec reachability from the fixture Parquet footers and the Spark writer setting. | L1, gate safety | replaced-after-verify refused with zero writes; wrong source refused; deferred checks block every store |
| WP2 | build-engineer | F-C only: path, selector, wildcard and package rules also run on the literal-free stream; three self-test cases. U-a, U-b, U-c, U-d are not in scope. | constitutional boundary rag → ports | 3 self-test cases expect `path` at line 1 |
| WP3 | build-engineer (structure), dependency-updater (values) | Storage-only codec fixes driven by WP1's evidence: exclude codecs the data cannot reach (if the reader still works without them), override reachable ones to versions that fix the identified advisories. Re-verify by reading the fixture Parquet on the patched classpath. Any advisory left (for example an unpatched zstd-jni issue, if reachable) goes to the human separately; none is accepted by default. SE-P7-04 evidence: all 29 fixture Parquet parts are SNAPPY only, so snappy-java is reached; zstd-jni and aircompressor are on the classpath but not reached by these files. | dependency policy | fixture Parquet read on the patched classpath; verifyDependencyScopes still passes |
| WP4 (increment 3) | storage-engineer | Neptune CSV row contracts, duplicate `~id`, dangling `~from`/`~to`, label set; loader into the in-memory GraphStore through the allowlisted parameterized templates only; Neptune bulk-loader request built (not sent). Closes the graph-ids and Neptune row-contracts deferred checks. | N1, N2, N3, N5, G-ID, L2 | loaded ID sets equal CSV ID sets; dangling and duplicate refused; injection string stays a parameter; reload adds nothing |
| WP5 (increments 4+5) | storage-engineer | Parquet reader (footer, row counts, row contracts) for dynamodb, s3 and s3-vectors items; KeyValue loader with conditional writes for RunLedger, ArtifactRegistry, EvidenceBundle and the metric cache used for bounded metric lookup; publication row putIfAbsent before any data write (CR-P7-03 R1 concurrency, compare `partManifestSha256`); S3 create-once objects with sha256 and source reference; COAUTHORED_WITH evidence trace. | S1-S3, N4, D1-D4, G-EV, L2, L3 | conditional double-put no-op/Conflict; interrupted load resumes without duplicates; every record carries runId, loadId, source path, schema version; broken evidence pointer refused |
| WP6 (increment 6) | storage-engineer | Five-index registry; route the 249 summary-input rows to index documents; long text to the object store with key+sha256 in metadata; filter model (researcher, year window, source, topic, institution, community, document type); batching, bounded retry with injectable clock, rejected records. Embeddings are Phase 8, so vectors in tests are synthetic and labelled as such; indexes with no source rows are reported, not invented. | V1-V6 | registry has exactly five; duplicate key with different text hash refused; throttle-then-success; permanent failure gives rejected record; oversize metadata refused (limits configured, AWS values unverified) |
| WP7 (increment 8, reduced) | storage-engineer | Deterministic load report; one entry point that loads the fixture run into the local fakes and returns `RuntimeStores` for rag/services tests; exact local sbt invocation commands. | handoff to Phases 9-11 | full fixture load then rerun: identical report, zero new writes |
| WP8 | test-engineer | One adversarial pass under tests/**: corrupt/missing parts, ID consistency, reruns, injection in graph parameters, duplicate vector keys, across the WP7 entry point. | T1 | exact pass/fail counts |
| WP9 | code-reviewer | Review of each WP above, then one whole-phase review of the Phase 7 diff. | gate | APPROVE |
| WP10 | documentation-engineer, orchestrator | Storage page under docs/** separating implemented / locally verified / unverified on live AWS; Phase 9-11 handoff; agent-report, review-report, test-report, checklist update; exact human review and commit steps. | documentation requirement | command replay |

Exit criteria for the development closeout: WP1-WP10 done; `sbt check` observed passing; every review APPROVE; no unaccepted vulnerability; checklist shows each ID as DONE-LOCAL or outstanding with its reason.

## 2. Assignment requirements proposed for deferral (NOT waived; tracked outstanding)

| Item | Why defer | Deliverable impact while deferred |
|---|---|---|
| A1 offline AWS SDK adapters (increment 7: S3, DynamoDB, Neptune data, S3 Vectors, stubbed clients) | Not needed for the local flow; ports isolate rag/services from them. | No code path to the real stores; W1-W6 and the deployed app cannot use real Neptune, DynamoDB, S3 or S3 Vectors until done. Must be completed before Phases 12-14 deployment. |
| W1-W6 live storage acceptance, `sbt storageIT` (row inactive B10), live Neptune bulk load | Needs a signed plan, resources that do not exist (Neptune), approval and spend; also depends on A1. | Phase 7 gate "bounded storage integration suite" (L1420) and "Neptune IDs equal Spark IDs" in a live cluster remain unmet. |
| N6 / W5 read-only runtime graph credentials (IAM) | infra-engineer Terraform. | Only the type-level reader/loader split is verified locally. |
| W6 cleanup procedure for the four stores | Needs the real resource set; human executes cleanup. | Cleanup documentation incomplete until resources exist. |
| AWS service limits (vector dimension, metadata size, batch sizes, Neptune loader options) | aws-docs MCP is down; values cannot be confirmed now. | Limits are configuration, marked unverified. |
| Embedding ingestion queue | Phase 8 (embeddings, ≥10,000 vectors). | Semantic retrieval in Phases 9-11 runs on synthetic vectors only. |
| Carried from earlier phases (unchanged) | bounded EMR run; Phase 8 corpus size; Phases 12-15 deployment, scaling, elasticity; release manifest; cleanup inventory | No instructor approval of any deviation has been obtained. |

## 3. Optional improvements to defer

- Scanner refinements U-a, U-b, U-c, U-d; R1 (awsAligned); the split-line `storage // c` + `.aws` residual; any further scanner rule. The scanner is not expanded beyond F-C.
- CR-P7-01 F2 (Netty mix) and nits; CR-P7-03 n3, n6; residual R2 (check-then-open race; content is still sha256-checked), R3 (in-memory bound), R4 (public mutable `WriteCounts`).
- Streaming or performance work on the Parquet reader beyond the fixture bounds.
- A harness-level fix for `sbt check` being moved to the background (would be a constitutional change; per-increment human marker review continues instead).
- Optional OpenSearch synchronization.

## Decisions requested

1. Approve section 1 as the Phase 7 development closeout scope (or name changes).
2. Approve deferring A1 (offline SDK adapters) to a dedicated slot before deployment, or keep it in Phase 7 now (adds one storage increment plus review).
3. Approve the section 3 deferrals.
