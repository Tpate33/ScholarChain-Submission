# Bounded AWS proposal — planning only

November 23, 2026. Base: accepted production implementation `5e04770`, human
acceptance `14d85c5`, both pushed; local HEAD and fetched origin/main match.
Deadline: November 25, 11 PM America/Chicago.

**No deployment or paid execution is authorized.** This is a concrete candidate
resource/budget plan, not a saved Terraform plan or a claim that AWS is ready.
Research used repository records and anonymous public documentation/price lists;
no AWS account API, credentials, state, saved plan or paid provider was accessed.
No tests or implementation were rerun/changed for this planning work.

## 1. Prior spending: unverified

The student estimates approximately **$0.55**, explicitly uncertain. Searches of
repository cost/deployment records found estimates and historical launch reports,
not an invoice, Cost Explorer export or cumulative pre-credit ledger establishing
that amount. It is **not accepted as an exact baseline**.

Relevant existing records:

- [Old EMR proposal](../phase-06/emr-proposal.md): reference prices were explicitly
  unverified, not measured charges.
- [Old bound](../phase-06/ie-p6-04-repair-02.md): $0.7314 was a maximum-window
  estimate excluding S3, not the bill for the experiment.
- [Historical attempts and cleanup](../phase-06/emr-run-attempt-a3-and-phase-6-status.md):
  several attempts, no successful cloud output, transient EMR/endpoint cleanup;
  base resources and evidence retained. This is not today's live inventory.
- [Infrastructure report](../phase-06/ie-p6-02-report.md): retained KMS/storage
  have continuing cost potential. Its approximate rates do not establish actual
  cumulative charges.

Prior project external API charges are also **unverified**: no cumulative provider
billing record was found. The closed production-connections work made no paid
model calls; that does not prove all earlier project provider charges were zero.
Coding-assistant screenshots/subscription credits are not treated as an application
API invoice. Include any applicable project API charges when reconciling the baseline.

Define `P = verified cumulative AWS + applicable external API charges before credits`
as of the proposed launch, including retained-resource charges through that time.
Do not subtract credits, assume free-tier eligibility, or double-count P as a new
run charge. **P is currently unknown.**

## 2. Smallest proposed integrated workload

Use one deterministic, provenance-preserving DBLP/enrichment input selection,
targeting **up to approximately 10K DBLP works**. Do not start with 50K. Proposed
semantic ceiling: **10,000 stored vectors TOTAL across the existing five indexes**,
256 dimensions, cosine distance. These are different units.

**Mandatory offline census gate:** count all five vector exports, required graph
elements/rows, object sizes, DynamoDB item/GSI write units, and embedding texts
before any paid load. The accepted dev example produced 249 vectors from 41 DBLP
works plus enrichments; a 10K-work selection cannot be presumed to fit 10K vectors.
`maxItemsPerCall` is a resumable call bound, not a total-run vector cap.

If the exact exports exceed the proposed ceiling, stop before paid loading. Select
a smaller coherent producer dataset and regenerate its valid manifests, or prepare
an explicitly reviewed bounded semantic projection. Do not truncate loader inputs,
reseal old manifests, silently omit mandatory indexes, or call a partial load
complete. The smaller workload's actual size/scope must be disclosed in the final
approval and evidence. Published classmate reductions are course guidance; they
are not an individual waiver recorded for this project. No further general
reduction-permission request is needed during local preparation.

## 3. Exact candidate resources

Region: **us-east-1**. Reuse base resources only after an approved current inventory
confirms them. Final names/ARNs, subnet IDs, image digests and plan hashes must be
fixed in the approval package; no placeholders may reach execution.

| Boundary | Proposed resources and limits |
| --- | --- |
| Network | Existing private VPC/subnet/S3 gateway; one additional private subnet in a second AZ; no NAT, internet gateway, public IP or public application endpoint |
| Runtime endpoints | Six interface endpoints in the primary task AZ only: ECR API, ECR DKR, CloudWatch Logs, Bedrock Runtime, Secrets Manager, S3 Vectors; add DynamoDB gateway endpoint |
| EMR endpoint | One transient KMS endpoint for the fixed EMR window; separate from runtime endpoints |
| EMR | One cluster, one primary + one core `m5.xlarge`, no resize/Spot/retry; 15 GiB root + 32 GiB data per node (94 GiB total); global maximum 120 minutes |
| Graph database | One encrypted IAM-authenticated Neptune Standard cluster, one `db.t4g.medium` writer, no replica; two-AZ subnet group; maximum three-hour billed window including provisioning/cleanup |
| Key/value | Nine on-demand DynamoDB tables: RunLedger, ArtifactRegistry, EvidenceBundle, QuerySession, EntityLinkCache, ResearcherMetricCache, QueryOwnership, QueryJob, IdempotencyKey |
| DynamoDB schema | Existing string pk/sk contracts; eight ALL-projection GSIs; `expiresAt` TTL only on QuerySession, EntityLinkCache, QueryOwnership, QueryJob, IdempotencyKey |
| Vector storage | One vector bucket, all five existing named indexes, 256/cosine; at most 10K total vectors; one externally serialized loader |
| Object storage | Existing private data/log buckets and six explicit zone prefixes; graph/report/log zones remain within configured S3 allowlist; no six-new-bucket expansion |
| Runtime services | One ECS cluster; QueryJobs=1, EvidenceExplain=1, Graph=1–2; initially 0.25 vCPU/1 GiB each; services at most two hours |
| Workers | One ER/staging task, 1 vCPU/4 GiB, at most 20 minutes; one loader task, 1 vCPU/4 GiB, at most 45 minutes; sequential, no automatic replacement/retry |
| Evidence/load generator | One private task, 0.25 vCPU/0.5 GiB, at most 20 minutes and 50K graph requests |
| Packaging | One normal runtime ECR image and one isolated worker image containing portable loader dependencies plus a separate ER artifact; immutable Git SHA tag and content digest |
| Discovery | One private Cloud Map namespace and three service registrations; internal task runs real HTTP requests and archives outputs; no ALB needed for this bounded private proof |
| Authentication | Two Secrets Manager resources: runtime digest document and raw benchmark-client key, separately scoped; human-approved value population without secrets in Terraform state/arguments/logs |
| Models, proposed only | Bedrock on-demand `amazon.nova-lite-v1:0` answer model and `amazon.titan-embed-text-v2:0`, 256-dimension normalized embeddings; availability/quota/applicable approval still unverified |
| Observation/control | Short-retention logs, one dashboard, native ECS CPU metrics, two step-scaling alarms, request/usage counters, deadline monitor/scheduler and notification; no Container Insights/managed model throughput commitment |

Neptune subnet groups require the AZ coverage above. Private image pulls need ECR
and S3 connectivity; S3 Vectors needs its own endpoint. See [Neptune limits](https://docs.aws.amazon.com/neptune/latest/userguide/limits.html),
[ECR endpoint requirements](https://docs.aws.amazon.com/AmazonECR/latest/userguide/vpc-endpoints.html),
[S3 Vectors PrivateLink](https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-vectors-privatelink.html).
EC2 Standard and Fargate vCPU quotas are separate and both need current checks;
historical quota values are not current proof. Bound rolling-deployment surge as
well as autoscaling maximums (for this ephemeral run, proposed minimum healthy 0%,
maximum 100%); do not assume Graph max=2 bounds all transient tasks automatically.

Private HTTP is the existing transport, not a claim of production TLS. Restrict
security-group access to the internal tasks, use an ephemeral scoped benchmark
key, suppress authorization headers, and disclose this transport limitation in
the final security approval. Existing public/non-production demo keys are not used
as production credentials. The runtime receives only digest-secret access; the
load-generator execution role may retrieve only its benchmark secret.

## 4. Bounded sequence and evidence

1. **Offline, first:** portable images, deterministic input/vector census, typed
   config and archived producer config, script/image hashes, source manifests,
   local checks, reviewed Terraform definitions and termination design.
2. **Only after specific account-inspection approval:** current resources, quotas,
   model access and gross spending reconciliation. No provisioning in this step.
3. **Only after final plan/run/teardown approval:** provision the reviewed resources
   just in time. A new bounded run requires explicit authorization; the historical
   Phase 6 decision stopped retries, and its plans/script pins must not be reused.
4. One fixed EMR chain: provisioning ≤20m, Hadoop ≤20m plus kill allowance, ER
   worker ≤20m while EMR waits, Spark ≤30m plus kill allowance, then idle/termination
   allowances. Planned window about 112m; external deadline at 120m. No second
   attempt on failure. A global controller must propagate Hadoop failures rather
   than the old `CONTINUE` behavior. If the chain cannot fit, stop; do not silently
   create a second cluster or extend the budget.
5. Verify/download bounded immutable output into the loader task, check original
   producer configuration/manifests, then call existing `OpeningLoad`. Use the
   writer Neptune endpoint for loading/read-back, `RealModelsOnly`, and matching
   embedding versions/dimensions. First actual provider denial/failure stops the
   run; no automatic provider/model or Region switch.
6. Internal HTTP task: save a genuine API query, cited evidence lookup, explain
   result, graph response, authentication refusal, idempotent replay and provider
   usage. Preserve real source/run IDs and observed errors. Do not relabel the
   existing tiny fixture run as the new bounded cloud run.
7. One small measured **Graph 1→2→1** experiment: CPU above 45% for two 60-second
   datapoints scales out; below 15% for three datapoints scales in; cooldown 60s.
   Hard limits 20m/50K graph requests. Query/evidence stay at one task. This avoids
   using paid model calls to generate load. Refresh discovery and collect per-task
   logs/counts so both replicas' behavior is observable. If thresholds/events do
   not occur, report the experiment failed; never fabricate scaling evidence.
8. Export raw metrics/query/usage/step logs and manifests, reconcile costs, then
   execute the separately approved Terraform cleanup. No public source archive,
   ledger or evidence deletion outside its reviewed scope. Stop new work early
   enough to finish resource deletion inside the budgeted windows.

ECS stop timeout, expiration tags and budget alerts are not billing hard stops.
A reviewed external monitor must stop expired EMR/ECS work and notify the human;
Neptune/endpoints/storage cleanup remains through the repository-approved human
deployment/teardown path. Do not introduce unreviewed direct infrastructure
deletion or assume a laptop timer guarantees cleanup. Account for API failures,
cleanup delay and billing latency. Persistent base/evidence charges need their
own retained-resource decision after the run.

## 5. Incremental cost allocation, before credits

These are **planning allocations under the stated caps**, not measured charges or
an absolute AWS billing cap. Prices exclude applicable taxes; contingency must
cover them or the final approval amount must be revised. No free tier, credits,
reservations, Spot discount or provisioned model commitment is deducted.

| Item | Calculation/bound used | USD allocation |
| --- | --- | ---: |
| EMR compute | 2 nodes × 2h × ($0.192 EC2 + $0.048 EMR) | 0.96 |
| EMR volumes | 94 GiB × 2h; conservative $0.10/GiB-month reference | 0.03 |
| Fargate | Three runtime tasks ≤2h, extra graph ≤20m, ER/loader/load-task windows above; approximately $0.04048/vCPU-h + $0.00445/GiB-h | 0.25 |
| Neptune | $0.093 × 3h; up to $0.90 T4g CPU-credit allowance, 2M I/Os at $0.20/M and bounded storage/backup | 1.65 |
| Endpoints/network | Six single-AZ endpoints ≤6 billed hours plus KMS ≤2h, $0.01/endpoint-AZ-hour reference; ≤10GB processing and regional-transfer allowance | 0.65 |
| DynamoDB | ≤1M write units including GSIs/retries + ≤1M read units, at $0.625/$0.125 per million; ≤1GB stored for one day | 0.80 |
| Ordinary S3 | ≤200K PUTs, ≤200K GETs, ≤5GB retained one day, all staging/evidence included | 1.15 |
| S3 Vectors | ≤10K total vectors/0.4GB logical data; PUT minimums/batching, ≤1K queries and one-day storage allowance | 0.15 |
| Titan V2 | ≤10K load embeddings + ≤500 query embeddings; 8,192 tokens/call ceiling at $0.02/M input tokens gives $1.72032 | 1.80 |
| Nova Lite | ≤100 calls total, 32KiB prompt budget plus framing allowance, ≤1,024 output tokens/call; $0.06/M input, $0.24/M output | 0.25 |
| Secrets | Two secret resources, ≤1 day retention and bounded reads, no assumed free trial | 0.05 |
| ECR/discovery/DNS | ≤2GB image storage, one namespace/zone, ≤4 simultaneous registrations and bounded DNS; allow a full zone/resource-month where proration is unverified; no paid Route53 health checks | 1.00 |
| Existing KMS | One key-day and ≤100K requests allowance; other existing base costs must be reconciled | 0.35 |
| Observability | ≤0.25GB log ingestion, one-day retention, one dashboard/alarms/metrics/export allowance | 0.75 |
| Monitor/notification | Bounded scheduler/function/notification usage allowance | 0.10 |
| **Allocated subtotal** | Sum of the above allocations | **9.94** |
| Contingency/taxes/cleanup allowance | Not permission for another attempt or increased scale | **2.06** |
| **Proposed additional authorization envelope** | Conditional on all bounds and verified prior spend | **12.00** |

The token ceilings are budget assumptions to enforce/reconcile, not a claim that
byte length is an exact provider tokenizer. Count/estimate input tokens using the
chosen provider's supported method before the approved load, allow framing, reject
oversize texts, and include query embeddings/retries in totals. Configure SDK,
loader and synthesis attempts to one and enforce the overall call counts; current
per-call settings alone are not an account-wide spending control. No model call
was made to obtain these prices.

**Cumulative approval condition: `P + 12.00 <= 25.00`, with taxes/retention fully
accounted for.** Thus verified prior spending must leave at least $12 of headroom;
P must be no more than $13 under this candidate envelope. Because P is unknown,
the plan is not yet eligible for paid execution. Any unpriced retained charges,
longer windows, extra endpoints/AZs, changed models, retries or second clusters
require recalculation, not consumption of an assumed $24.45 balance.

## 6. Pricing evidence and uncertainty

Selected current public catalog entries are archived in
[public-price-evidence.json](public-price-evidence.json): Neptune Standard T4g
$0.093/h (catalog September 11), EMR M5 uplift $0.048/h (November 17), Nova Lite
on-demand and Titan V2 input rates (November 23). These were anonymous static file
reads, not signed account requests. EC2 $0.192 is the official published regional
reference below; refresh the exact launch quote before approval. Other allocation
rows deliberately include headroom rather than claiming every allowance is a
quoted SKU subtotal.

- [EC2 regional M5 reference](https://docs.aws.amazon.com/prescriptive-guidance/latest/optimize-costs-microsoft-workloads/right-size-selection.html),
  [EMR public catalog](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/ElasticMapReduce/current/us-east-1/index.json).
- [Neptune public catalog](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonNeptune/current/us-east-1/index.json),
  [Neptune I/O/storage/CPU-credit pricing](https://aws.amazon.com/neptune/pricing/).
- [Bedrock public catalog](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonBedrock/current/us-east-1/index.json),
  [Bedrock pricing](https://aws.amazon.com/bedrock/pricing/).
- [Fargate pricing](https://aws.amazon.com/fargate/pricing/),
  [PrivateLink pricing and whole partial-hour billing](https://aws.amazon.com/privatelink/pricing/).
- [DynamoDB pricing](https://aws.amazon.com/dynamodb/pricing/),
  [S3 and S3 Vectors pricing](https://aws.amazon.com/s3/pricing/),
  [EBS pricing](https://aws.amazon.com/ebs/pricing/).
- [Secrets Manager pricing](https://aws.amazon.com/secrets-manager/pricing/),
  [ECR pricing](https://aws.amazon.com/ecr/pricing/),
  [Cloud Map/DNS pricing](https://aws.amazon.com/cloud-map/pricing/).

## 7. Local work still required before a launch approval package

| Owner | Required preparation; no paid execution implied |
| --- | --- |
| build-engineer | Portable isolated worker packaging; current productionLoadClasspath is host-absolute, not a deployable OCI image; separate ER artifact without altering module dependencies |
| infra-engineer | Runtime Terraform/IAM/endpoints/ECR/Neptune/tables/indexes/secrets/discovery/alarms/deadline controls; bounded S3 materialization/upload and integrated wrappers; reviewed cleanup mechanism |
| entity-resolution / producer owners | Portable execution of existing local-directory ER CLIs and deterministic bounded corpus/export census; no private held-out label reads or unaudited data filtering |
| experiment-engineer | Bounded internal HTTP collector/load generator, auth-header redaction, task/latency/CPU/usage evidence and honest failure handling |
| deploy-engineer + human | Approved current inventory/quotas/spending, final immutable hashes and Terraform plan, exact run and cleanup authorization |

The committed code does not provide a full Step Functions/SQS worker-backlog
system or every original course experiment. This plan's single Graph scaling
experiment does not claim those requirements met. No direct Anthropic/Voyage
provider switch, new internet egress path or separate provider charge is included.
If Bedrock is unavailable, stop and price/review the applicable alternative before
making calls.

No additional tests, source changes, AWS operations, commits or pushes were made
while preparing this proposal. Next useful work is local portable-worker packaging
and the vector/input census, before spending or deployment approval.
