# Phase 06 proposal: one bounded EMR Spark run (for human approval)

Status: PROPOSAL, 2026-11-17. Not approved. No AWS call, Terraform or script has been run or authored for it. Supersedes emr-plan-draft.md. Sources: be-p6-08-report.md (runtime), sp-p6-04-report.md (I/O and entry point), ie-p6-01-report.md (resources, IAM, cost, execution, cleanup), emr-docs-research-01.md and -02.md (AWS documentation).

## 1. Recommendation in one paragraph

Run the committed Spark job once on a transient EMR on EC2 cluster (emr-7.14.0, Hadoop only, 1 primary + 1 core m5.xlarge, private subnet with an S3 gateway endpoint, idle auto-termination 30 min), using a pinned Apache Spark 3.5.5 for Scala 2.13 installed by a checksum-verified bootstrap action and launched with its own `spark-submit --master yarn --deploy-mode client`. Input is the dev archive copied create-once to S3; output is published with the new commit-marker protocol to `s3a://…/output/spark/`; the event log gives stage DAGs, partition counts and shuffle metrics. Expected cost about USD 1, hard-capped at USD 2 per run (prices to be confirmed).

## 2. Runtime compatibility: what is verified and what is not

| Fact | Status | Evidence |
|---|---|---|
| EMR on EC2 7.14.0: Spark 3.5.8-amzn-0 built for Scala 2.12.18, Hadoop 3.4.2-amzn-2, Corretto 17 default | verified (AWS documentation) | emr-docs-research-01.md |
| Our job (Scala 3.3.6 against `spark-sql_2.13` 3.5.5) cannot run on EMR's Spark (2.12/2.13 binary incompatibility) | verified by build analysis | be-p6-08-report.md section 1 |
| No EMR on EC2 7.x release with Spark for Scala 2.13 | verified for 7.11-7.14 | research 01 |
| EMR Serverless emr-spark-8.2.0 = Spark 4.1.3; its Scala version | Spark version verified; Scala not stated by AWS | research 01 |
| User-installed Spark on EMR on EC2 via bootstrap + command-runner | **not documented either way by AWS** | research 01 items 3-4 |
| Hadoop client in the Apache distribution (believed 3.3.4) against EMR YARN 3.4.2 | not verified | be-p6-08 |
| S3A with instance-profile credentials; directory-marker retention; conditional put | not verified (the commit protocol does not need conditional put) | sp-p6-04, be-p6-08 |

So runtime compatibility is **verified negatively** (EMR's own Spark is incompatible, from documentation) and **unverified positively** for the recommended route. The gap is closed in two steps before the cloud run: (a) a local gate that runs the assembled jar under the same Apache distribution with `spark-submit` at `local[2]` and `local-cluster[2,1,1024]` (separate executor JVMs) over the dev archive and compares logically with sparkIT; (b) the bootstrap and YARN behaviour, which can only be observed on EMR: the first cloud run is itself the test, bounded by the caps below, and fails closed (bootstrap refuses on a digest mismatch; the step fails visibly).

Alternatives (one recommendation; tradeoffs for your decision):

| Option | Runtime | Major upgrade | Infra | Evidence quality | Verdict |
|---|---|---|---|---|---|
| A (recommended) | EMR on EC2 7.14 + Apache Spark 3.5.5 Scala 2.13 on YARN | no | small (transient cluster) | real YARN executors, event log | recommended |
| B | EMR Serverless emr-spark-8.x | yes: Spark 3.5 -> 4.1 (needs your approval; RDD, Parquet API, ANSI, test classpath retest) | smallest | real, managed | only if you approve the upgrade and Scala 2.13 is confirmed |
| C | EMR on EKS | unknown | largest (EKS, IRSA, ECR) | real | not recommended |
| D | EMR on EC2, Spark local mode on one node | no | smallest cluster | weak (no YARN executors) | fallback only |

## 3. Cloud-capable input/output (CR-P6-25 f2): resolved locally, not yet reviewed

Done in SP-P6-04 (uncommitted; full sbt check and sparkIT run 08 in progress; focused review pending): Hadoop FileSystem store for reader and publishers; commit-marker publication on S3A/HDFS without rename or conditional put (stage under `.spark-<hex>/`, copy create-once with re-read verification, `_parts.json`, `_SUCCESS` last; nothing deleted under `run=`/`load=`); driver lists through the target file system; entry point `scholarchain.spark.job.CollaborationJob` with 17 required flags, real 40-hex `--code-version` required. Tested on `file:` and a simulated S3 without atomic rename (same run ID and logically equal outputs as the local run). Single-writer assumption is load-bearing on S3A.

## 4. Resources (infra-engineer, ie-p6-01-report.md)

- Terraform layout: `infra/terraform/bootstrap/` (state bucket), `environments/<env>/base/` (persistent, no compute), `environments/<env>/emr-run/` (one transient cluster). Lifecycle L2: `emr-run` applied by `scripts/deploy.sh` right before the run; the cluster terminates itself 30 min after the step; destroy plan afterwards.
- S3: data bucket (artifacts, input, config, output, submissions) and logs bucket (EMR logs, Spark event logs); SSE-S3, public access blocked, TLS only, versioned, create-once and no-delete bucket-policy statements; checkpoint on HDFS.
- Network: new VPC, one private subnet, S3 gateway endpoint, no NAT or internet gateway, EMR-managed security groups.
- Encryption: one KMS key for EMR local-disk/EBS encryption; in-transit encryption between nodes off (risk to accept).
- Cluster: emr-7.14.0, Hadoop only, 1 primary + 1 core m5.xlarge (m6i.xlarge alternate), root 15 GiB gp3 + 32 GiB gp3 per node, no scaling, no SSH, step concurrency 1, idle timeout 1800 s.
- Budget: USD 10/month with alerts at USD 2 and 5 actual and 10 forecast (budgets with notifications are free).
- Estimated first plans: ~47 adds, 0 change, 0 destroy, 3 new runtime roles + 1 deployer role.

## 5. IAM (summary)

R1 EMR service role; R2 EC2 instance role (prefix-scoped read, write only to `output/spark/*`, delete only in `output/spark/.spark-*`, explicit deny of deletes on `run=`/`load=`/input/artifacts/config); R3 operator role (describe, add-steps, cancel own step; explicit deny of terminate, run-job-flow, modify and scaling; no PassRole; create-once staging puts; no delete); R4 deployer role used only by `scripts/deploy.sh`. Several statements are [UNVERIFIED] against AWS documentation (listed in ie-p6-01-report.md) and go to code review before any plan is signed.

## 6. Estimated total cost

Prices were **not verified** from AWS pages in this session (rate tables did not render; research 02). Reference us-east-1 On-Demand rates, unverified, to be confirmed by you: EC2 m5.xlarge 0.192 USD/h, EMR uplift 0.048 USD/h, gp3 0.08 USD/GB-month, S3 Standard 0.023 USD/GB-month, PUT 0.005 and GET 0.0004 per 1,000 (GET matches research 02). Billing is per second with a one-minute minimum (verified).

| Item | Formula | Expected | Worst case |
|---|---|---|---|
| Compute (2 nodes) | 2 x (0.192 + 0.048) x minutes/60 | ~58 min: USD 0.46 | 95 min (provisioning cap 20 + step 30 + cancel 10 + idle 30 + termination 5): USD 0.76 |
| EBS | 2 x 47 GiB x 0.08/730 x hours | USD 0.01 | USD 0.02 |
| S3 storage (~0.9 GB, mostly the Spark tarball and SDK bundle) | 0.9 x 0.023 per month | USD 0.02/month | USD 0.03/month |
| S3 requests | <= 10k PUT/LIST + 10k GET | USD 0.05 | USD 0.06 |
| KMS key | 1 USD per key-month, prorated until deletion | USD 0.50 | USD 1.00 |
| Data transfer | same Region, gateway endpoint | 0 | 0 |
| **Total** | | **~USD 1.0** | **~USD 2.0** |

Caps: run config `max_cost_usd=2`; script hard cap `HC_MAX_COST_USD=5`, `HC_MAX_INSTANCES=2`, step timeout 30 min, idle timeout 1800 s; AWS Budget USD 10. A retry (attempt + 1) is a new approval and roughly doubles compute.

## 7. Exact approvals needed (in order)

1. Runtime: Option A (or B with the Spark 4 upgrade, or D as a separate fallback).
2. Lifecycle L2 and the two-stack Terraform layout.
3. Environment name, and ratification of the environment lists (`@env`, `@env-nonprod`) (factory-engineer change, restart).
4. AWS account ID and Region (us-east-1 proposed).
5. Account type: plain account vs AWS Academy/Learner Lab or Organizations with SCPs (a restricted account may forbid IAM role creation or EMR and would change sections 4-5).
6. G6 operator (deploy-engineer proposed), the operator and deployer principal ARNs, and how the confirmation token reaches the execute run (agent Bash cannot set an environment variable: you run execute in your Terminal, or export the token before the session).
7. Security change review and acceptance: IAM R1-R4, bucket policies, KMS key policy, in-transit encryption off.
8. Terraform state bootstrap procedure (local state for `bootstrap/` kept outside the repository, then migrated).
9. Cost caps above and the budget alert email address (personal data; committed tfvars or `TF_VAR_`).
10. Consent to copy the dev archive (public bibliographic names and ORCID iDs; dev split only; no gold/ path) to S3.
11. Spark distribution and S3A jars: you fetch `spark-3.5.5-bin-hadoop3-scala2.13.tgz` from archive.apache.org, verify `.sha512` and `.asc` against KEYS, identify its bundled Hadoop client version and fetch the matching `hadoop-aws` and `aws-java-sdk-bundle` from Maven Central; digests pinned in the ledger (dependency-updater or you).
12. Allowlist rows (factory-engineer; ratification and restart): Terraform init/validate/plan per stack; `scripts/run-bounded-spark.sh` in `--dry-run`, `--stage` and execute shapes for the operator; `sbt sparkSubmitLocal` (local gate); deploy.sh for two stacks; a security-scan tool row.
13. Terraform plan signatures: `base`, then `emr-run` (checksums).
14. Dry-run review and confirmation token, then one execution.
15. Cleanup approvals: Phase A (destroy `emr-run`), Phase B (after evidence archival: bucket contents, `base`, KMS scheduled deletion, budget), run by you with `scripts/cleanup.sh`.

## 8. Account and Region information not obtainable through permitted inspection in this session

No AWS profile is ratified, the `aws-api` MCP server is not connected, and no `@AWS-RO` row has an environment. None of the following can be confirmed now; each has a read-only check for after a read-only profile exists (most through `mcp__aws-api`, one through the allowlisted `aws sts get-caller-identity`):

account ID and caller identity (`sts get-caller-identity`); Organizations membership and SCPs; emr-7.14.0 availability in the Region (`emr describe-release-label`); m5/m6i.xlarge offerings per AZ (`ec2 describe-instance-type-offerings`); On-Demand Standard vCPU quota, 8 vCPU needed (`service-quotas get-service-quota`, quota L-1216C47A; a low quota on a new account is a hard blocker); default VPC; account-level S3 Block Public Access; EBS encryption default; EMR service-linked role; existing budgets; cost-allocation tag activation; prices (`pricing get-products`); bucket-name collisions.

## 9. Remaining blockers

- Runtime route A is not documented by AWS (observed only on the first run).
- Hadoop client vs YARN version skew; S3A directory-marker deletes against the no-delete policy (a failure would be fail-safe and cost a retry).
- A private subnet may need interface endpoints (would fail provisioning cheaply; fallback is a reviewed plan change, not a NAT gateway).
- 30-minute window between `emr-run` apply and execute.
- Prices and many IAM details unverified until documentation or read-only inspection is available (`uvx` would restore the aws-docs and terraform MCP servers, but is not required: the web-fetch route was used here; the Terraform provider version pin still needs a Registry or documentation read).
- SP-P6-04: sbt check (1547 passed) and sparkIT run 08 (14 PASS, run ID unchanged) done; CR-P6-26 APPROVE with six nits; awaiting your commit before packaging work. CR-P6-26 adds to the run configuration: `spark.yarn.maxAppAttempts=1` (single writer) and `fs.s3a.directory.marker.retention=keep` (no S3A marker deletes under `run=`/`load=`; a start-up check in the job is suggested).

## 10. Work orders that follow approval

infra-engineer: Terraform stacks, run script, EMR bootstrap and submit scripts, plan/deploy/cleanup/check scripts, fmt/validate/plan, script checks, security scan. build-engineer: assembly (mainClass `scholarchain.spark.job.CollaborationJob`, exclusions, content check), `sbt sparkSubmitLocal`, logical-comparison task. spark-engineer: step flag values, comparison logic, S3A marker behaviour. dependency-updater or you: ledger values. factory-engineer: allowlist rows and environment lists. deploy-engineer: applies through scripts/deploy.sh with signed checksums. code-reviewer: Terraform, scripts and IAM before any plan is signed. You: sections 7 and 8, cleanup, evidence archival.
