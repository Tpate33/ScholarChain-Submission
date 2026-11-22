# Phase 10 outstanding requirements, residuals and deployment prerequisites (orchestrator, 2026-11-22)

Records state only; approves nothing. Phase 10 scope delivered: LOCAL services with in-memory fakes. Nothing is deployed, packaged or connected to AWS.

## Pending human decisions at closeout
- Logging fix (BE-P10-01, CR-P10-01 APPROVE): minor finding m1 and nits n1-n3 accepted by the human (2026-11-22) as documented residuals for local Phase 10.
- Repair 05 (SE-P10-06) passed `sbt check` but was not code-reviewed (human decision). Disclose it in human-gate.md.
- CR-REL-01 release-scan findings F1-F11 (release-scan-cr-rel-01.md). Unchanged and out of Phase 10 scope; preserved for remediation. The literal account ID in release-scan-cr-rel-01.md's `git log -S` command was replaced with the placeholder `<AWS_ACCOUNT_ID>` (human instruction, 2026-11-22); the finding and its meaning are unchanged. This does not resolve the existing account-ID exposure elsewhere (finding F1: 15 files, 29 occurrences).
- Phase 9 residual R1 (an ID-shaped subject token inside a PARTIALLY SUPPORTED label) now reaches API clients through answerText and claims. It must be reassessed before real-LLM integration and before deployment.

## Accepted residuals (not fixed)
| ID | Residual | Owner of eventual fix |
|---|---|---|
| CR-P10-05 F1 | Evidence scan window is cut before the ownership filter. Foreign co-citations can push a caller's own occurrence out of the window, so a caller can learn a rough count and timing of foreign citations. Accepted by the human. | storage-engineer (owner-keyed evidence index), possibly rag-engineer and domain-engineer |
| CR-P10-05 F2 | Evidence-ID existence (404 to 200) reveals the first citation by any query, because evidence rows are written per query and IDs are computable from public inputs. Accepted by the human. | storage-engineer (published-run evidence table), possibly rag-engineer and domain-engineer |
| CR-P10-05 U-c | evidenceScanLimit is not validated against the adapter's maxQueryLimit; a larger value makes every evidence call return 500. | domain-engineer (config contract), infra-engineer (deployed value) |
| N5 | StoreAccess.serialized: a slow workflow holds the in-memory store lock and blocks evidence and explain store reads, which can open circuit-key-value and fail /readyz. The M2 pool test does not cover it. Ownership calls are outside the lock but share the key-value pool and breaker (n-A). | storage-engineer (thread-safe adapters, then StoreAccess.concurrent) |
| Owner eviction | An admitted query flood can evict owners from the bounded in-memory QueryOwnership (queryOwnershipCapacity). A forgotten query fails closed with 404. | storage-engineer (durable ownership) |
| m-A jitter | With very small headroom, the workflow timer can still fire before the ownership record's bound. That case is neutral, and the 504 names the ownership step, but the key-value breaker does not count it. No deterministic test. | service-engineer |
| n-B | An async ownership-record failure becomes the job's final error and is replayed under the same Idempotency-Key until TTL; only sync failures release the key. Documented. | none (documented behaviour) |
| m3 residual | An interrupt-ignoring workflow can pin its thread indefinitely, bounded by worker count; visible in /readyz (workflow-workers, job-workers). | rag-engineer / storage-engineer (interruptible adapters) |
| U-1 (CR-P10-06) | The breaker does not track which call holds the half-open trial. With queueWaitMillis > breakerOpenMillis, a late notStarted() or neutral() can release a concurrent trial. Recorded in code. | service-engineer |
| U-3 (CR-P10-06) | The key-value pool has no `*-workers` readiness check; a pinned key-value pool shows only through circuit-key-value. Recorded in code. | service-engineer |
| U2 | The JDK HttpServer has no per-exchange read deadline; slow body uploads hold a worker. The deployment may set -Dsun.net.httpserver.maxReqTime / maxRspTime (names unverified). | infra-engineer (JVM options), service-engineer |
| U-1/U-2/U-3 (CR-P10-04, JDK internals) | Unverified: the admission-queue rejection path may close or leak a connection; header reads on the single admission thread can stall it; ExchangeImpl.close may drain about 64 KiB of body. Recorded on HttpServerAdapter. | service-engineer |
| Graph scope | Graph routes serve researcher nodes only (other kinds get 422), depth capped at 1 (one-hop templates only). | storage-engineer (multi-hop templates), service-engineer |
| Query constraints | Any `constraints` key gets 422; the rag workflow accepts the question only. | rag-engineer |
| U-c (CR-P10-03) | rag gap lines can contain the `maxRetrievals` configuration value in abstention bodies (no stored text). | rag-engineer |
| X5 | The evidence response shape (shared identity plus caller-only `occurrences[] {bundleId, score, content}`, `occurrencesTruncated`) diverges from the README example at README.md:2500-2516. | documentation-engineer |
| Logging (CR-P10-01) | m1: runtime reconfiguration (for example setLogLevel("DEBUG") inside a test) is not detected by verifyTestLogging, which runs before the tests. n1: two DEBUG shorthand forms in build-owned configs are not flagged. n2: the build.sbt environment header omits the probe JVMs. n3: a comma in the repository path splits log4j2.configurationFile (fails closed). Accepted by the human as documented residuals. | build-engineer |

## Deferred work (mandatory, not waived)
- **Separate deployables** (QueryJobsMain, EvidenceExplainMain, GraphMain exist and are tested in one JVM). No production ServiceProvider exists, so each main exits 1 with "no scholarchain.services.ServiceProvider on the classpath". Needed:
  - **domain-engineer:** `services.query-jobs.*`, `services.evidence-explain.*` and `services.graph.*` config keys in core, because ConfigLoader rejects unknown keys. Fields: every ServiceConfig field (positive Int/Long values, graphEdgeTypes token list, graphDefaultWindow window, metricsEnabled boolean, including readRequestTimeoutMillis, queueWaitMillis, the per-pool workers and queue capacities, queryOwnershipCapacity, evidenceScanLimit); server.host, server.port (0..65535), server.backlog, server.workers, server.queueCapacity, server.maxBodyBytes, server.overflowCapacity; graceSeconds; drainMillis. Rules to carry: readRequestTimeoutMillis >= readTimeoutMillis; readRequestTimeoutMillis < sync and async timeouts; idempotencyTtlMillis > both workflow timeouts; evidenceScanLimit > evidenceMaxBundles.
  - **build-engineer:** one launcher per main (sbt-native-packager mainClass per script, or three images); register the production ServiceProvider (META-INF/services) once it exists.
  - **storage-engineer:**
    - thread-safe production read adapters (closes N5);
    - a durable QueryOwnership adapter with batch ownedBy (for example DynamoDB BatchGetItem keyed by query ID, clientId, TTL matching QuerySession), errors mapped to StoreError;
    - durable shared job and idempotency stores (required before more than one QueryJobs replica);
    - an owner-keyed evidence index (F1/F2).
  - **infra-engineer:**
    - three ECS services with their own task sizes and autoscaling;
    - ALB path routing: /v1/query and /v1/jobs* to QueryJobs, /v1/evidence* and /v1/explain* to EvidenceExplain, /v1/graph* to Graph; health checks on /healthz and /readyz;
    - Secrets Manager key-digest source;
    - ownership, job and idempotency tables;
    - per-service configuration;
    - drainMillis >= load-balancer health-check interval x unhealthy threshold;
    - JVM options for U2.
  - **service-engineer:** a production ServiceProvider composing real adapters once they exist; a Secrets Manager-backed ApiKeyDigestSource.
- **Carried from Phase 9** (docs/agent-runs/phase-09/outstanding.md), unchanged:
  - real LLM and access (only the NON-PRODUCTION template synthesizer exists);
  - real embedding model;
  - the >= 10,000-vector corpus or instructor approval for a smaller one;
  - live Neptune, DynamoDB and S3 Vectors, with IAM, Terraform and bounded ingestion evidence;
  - throughput, latency and cost evidence;
  - DynamoDB item-size verification against the 256 KiB audit ceiling;
  - production-scale retrieval and groundedness evaluation;
  - the `sbt benchmarkQueries` alias row;
  - core RuntimeConfig keys for runtime limits;
  - gap G7.
- **Process:** a full `sbt check` exceeds the 180 s foreground limit. Every Phase 10 agent run was backgrounded, so each marker write was flagged UNATTRIBUTED and the human reviewed it. A durable fix (an allowlisted way to run check without the R1-b pause, or a faster check) belongs to build-engineer and factory-engineer and needs human authorization for hook changes.

## Deployment prerequisites (Phase 10 code is not deployment-ready)
1. Production ServiceProvider and real adapters (storage-engineer, service-engineer).
2. Core per-service config keys (domain-engineer).
3. Launchers or images per service (build-engineer).
4. Terraform for the ECS services, ALB, tables, Secrets Manager and IAM, with a reviewed plan, then the human `scripts/deploy.sh` path (infra-engineer, human).
5. R1 reassessment before any real LLM (rag-engineer).
6. Human decisions on the CR-REL-01 publication findings before the repository is made public.
