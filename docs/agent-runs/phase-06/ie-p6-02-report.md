# Phase 06 IE-P6-02: infra-engineer report, Terraform and run scripts for one bounded EMR run (2026-11-17)

- Work order: routed under prompt-emr-preparation.md and prompt-aws-account-signin.md. Authoring only: nothing applied, deployed, uploaded or called on AWS.
- STATUS: COMPLETE (authoring). OWNER: infra-engineer. BLOCKED-ON: none for this order; downstream factory-engineer (rows, environment list), build-engineer and spark-engineer (jar and runtime-jar digests, partitions).
- Tool checks almost all pending: `terraform` and `shellcheck` not installed (exit 127, `command not found`); `bash -n scripts/cleanup.sh` refused by guard rule `cleanup-human-only`. Six other scripts pass `bash -n`. Plan counts below are expected from reading the code, not observed.

## Files created (no file modified)

infra/terraform/README.md; modules/private-bucket/main.tf; bootstrap/main.tf; environments/dev/env.conf; environments/dev/base/{versions.tf, network.tf, storage.tf, kms_emr.tf, iam.tf, outputs.tf, terraform.tfvars}; environments/dev/emr-run/{main.tf, variables.tf, terraform.tfvars}; environments/dev/runs/spark-dev-run-01.conf; scripts/lib/common.sh; scripts/plan.sh; scripts/deploy.sh; scripts/run-bounded-spark.sh; scripts/cleanup.sh; scripts/emr/install-spark-dist.sh; scripts/emr/submit-collaboration-job.sh. scripts/run-bounded-pipeline.sh and .gitkeep files unchanged. Scripts are mode 0644 (no chmod row).

## Commands

`git ls-files -- infra scripts .github docs/agent-runs/phase-06` (exit 0); one `git -C …` refused (no-allowlist-entry); `terraform -chdir=infra/terraform fmt -check -recursive` exit 127; `bash -n` on plan.sh, deploy.sh, run-bounded-spark.sh, lib/common.sh, emr/install-spark-dist.sh, emr/submit-collaboration-job.sh: exit 0 each; `bash -n scripts/cleanup.sh` refused `BLOCKED [cleanup-human-only]`; `shellcheck` exit 127; `git status --porcelain` exit 0. No AWS call, no terraform init/validate/plan, no sbt. Four defects found and fixed by reading (config-parser character range, run-record step writing the summary key, `a || die && b` chain, `java -version | head` under pipefail).

## Expected plans (not observed)

bootstrap 7 add; base 34 add (network 9, buckets 14, KMS key, alias, EMR security configuration 3, IAM 8); emr-run 1 add (`aws_emr_cluster`); 0 change, 0 destroy, no stateful replacement. IAM: 3 roles, 1 managed attachment (AmazonEMRServicePolicy_v2), 3 inline policies, 1 instance profile, 3 bucket policies, 1 key policy.

## Design decisions

- L2: bootstrap, base, emr-run; environment `dev` placeholder pending ratification. Bootstrap state local at `$HOME/ScholarChain-tfstate/bootstrap/terraform.tfstate` (outside the repo, archived by the human after each apply); base and emr-run S3 state with `use_lockfile`.
- Configuration external: account, Region, AZ ID, profiles, tags in env.conf exported as `TF_VAR_*` by plan.sh; no defaults; REPLACE-ME fails validation; state bucket `scholarchain-tfstate-<account>-<region>`; `allowed_account_ids` pinned.
- Provider `hashicorp/aws = 6.0.0`, Terraform `>= 1.10.0, < 2.0.0` (UNVERIFIED as current).
- Network: VPC 10.42.0.0/24, one private /26 by AZ ID, S3 gateway endpoint; no IGW, NAT or public IPs; default SG emptied; three EMR-managed SGs empty with `revoke_rules_on_delete`.
- Buckets: SSE-S3, public access blocked, BucketOwnerEnforced, TLS-only, versioned (noncurrent 30 days), `force_destroy=false`; deletes on protected prefixes and all version deletes denied except the admin SSO role.
- KMS: one customer managed key (7-day deletion window, rotation on) for EMR local disk and EBS (chosen over `aws/ebs` for an explicit policy; `aws/ebs` support by EMR UNVERIFIED; saves at most 1 USD/month). In-transit encryption off (accepted risk).
- Cluster: emr-7.14.0, Hadoop only, 1 primary + 1 core m5.xlarge, 15 GiB root + 32 GiB gp3, no SSH key, step concurrency 1, idle auto-termination 1800 s, no scaling, one bootstrap action; core-site `fs.s3a.directory.marker.retention=keep`.
- Bootstrap verifies sha512, sha256, sha1 before installing; archive listing restricted to regular files and directories under one top directory; create-once install into /opt/scholarchain; spark-defaults with IAMInstanceCredentialsProvider, endpoint and Region, marker retention keep, maxAppAttempts 1, HDFS event log.
- Submit wrapper verifies jar, config and pairs sha256, runs the BE-P6-09 command (2 executors x 1 core x 2 GB), copies the event log create-once to `s3://<logs>/spark-events/<submission-id>/`.
- R3 operator: IAM role `scholarchain-dev-operator`, assumable only from the admin SSO role (profile with `source_profile = scholarchain-admin`). R4: no role; the human's ScholarChainAdmin session deploys and cleans up. Agents keep only the read-only profile.
- plan.sh `<env> <bootstrap|base|emr-run>`: read-only profile, init with derived backend and `-lockfile=readonly`, validate, `plan -lock=false -out=tfplan`, prints counts, stateful/security/cost lines, sha256, writes `.terraform/scholarchain-plan.meta`.
- deploy.sh `<env> <sha40> <sha256>`: clean tree, HEAD equals sha, the one stack whose tfplan matches, meta checked, refuses delete/replace except the EMR cluster in emr-run, admin account checked, applies the saved plan.
- run-bounded-spark.sh `--dry-run | --stage | --execute <env> <config>`; stage and execute each need their own token in `SCHOLARCHAIN_CONFIRM_SPARK_RUN`; stage create-once with `--checksum-sha256`; execute: lock, full cluster check, create-once run record, one step, bounded poll, own-step cancel, verifies published run and event log, create-once summary. Caps `HC_MAX_COST_USD=2`, `HC_MAX_INSTANCES=2`; run config `max_cost_usd=1`; script worst-case estimate 0.7763 USD.
- cleanup.sh: inventory, plan-destroy, apply-destroy, empty-buckets, each token-gated; refuses inside Claude Code (`CLAUDECODE`) and without a TTY; Phase B needs `SCHOLARCHAIN_EVIDENCE_ARCHIVE_DIR` non-empty outside the repo.

## Cost (verified rates from emr-docs-research-03.md; S3 rates unverified, bounded at twice reference)

| Item | Formula | Expected | Worst case |
|---|---|---|---|
| Compute 2 x m5.xlarge | 0.48 USD/h x minutes | 58 min: 0.464 | 95 min: 0.760 |
| EBS 2 x 47 GiB gp3 | 0.0103 USD/h | 0.010 | 0.016 |
| S3 requests | ~3k PUT + 5k GET; bound 20k + 50k at 2x | 0.02 | 0.24 |
| **One run** | | **~0.49** | **~1.02** |
| KMS key, base kept 7 days | 168/730 x 1 USD | 0.230 | 0.230 (1.00 if 30 days) |
| S3 storage ≤ 1 GB, 7 days | | 0.005 | 0.011 |
| VPC, endpoint, IAM, security configuration, state bucket | | 0 | 0 |
| **Bootstrap + base 7 days + one run** | | **~0.73** | **~1.27** |

Each retry adds up to 1.02. Human-created `ScholarChain-monthly` USD 10 budget not in Terraform.

## Final IAM (compact; [U] = not verified against AWS documentation)

- R1 `scholarchain-dev-emr-service`: trust elasticmapreduce.amazonaws.com with `aws:SourceAccount` and `aws:SourceArn` [U]; AmazonEMRServicePolicy_v2 (tag `for-use-with-amazon-emr-managed-policies=true` [U]); inline iam:PassRole on R2 only (`iam:PassedToService`=ec2.amazonaws.com); KMS Encrypt/Decrypt/ReEncrypt*/GenerateDataKey*/DescribeKey on the key; CreateGrant/ListGrants/RevokeGrant with `kms:GrantIsForAWSResource` [U].
- R2 `scholarchain-dev-emr-instance` (+ instance profile): data bucket ListBucket on prefixes artifacts/*, config/*, input, input/*, output, output/, output/spark, output/spark/* [U ancestors]; GetObject on artifacts/*, config/*, input*, output, output/spark, output/spark/*; Put/AbortMultipartUpload/ListMultipartUploadParts on output/spark(/*); ListBucketMultipartUploads; DeleteObject on output/spark/.spark-* only; Deny Delete/DeleteVersion on input*, artifacts/*, config/*, submissions/*, output/spark/run=*, output/spark/load=*; Deny Put on input*, artifacts/*, config/*, submissions/*. Logs bucket: List emr/*, spark-events/*; Put emr/*; Put spark-events/* only with Null `s3:if-none-match`=false [U]; Get spark-events/*. KMS use on the key. No EMR, EC2, CloudWatch, DynamoDB or SNS [U].
- R3 `scholarchain-dev-operator` (max session 3600 s): trust account root with ArnLike `aws:PrincipalArn` `…/aws-reserved/sso.amazonaws.com/*AWSReservedSSO_ScholarChainAdmin_*` [U]; ListClusters on *; on tagged clusters (project=scholarchain, environment=dev) DescribeCluster, ListInstanceGroups, ListBootstrapActions, ListSteps, DescribeStep, GetAutoTerminationPolicy, GetManagedScalingPolicy, AddJobFlowSteps, CancelSteps ([U] resource-level support for some); Deny RunJobFlow, TerminateJobFlows, Modify*, Add instance groups/fleets, scaling and auto-termination changes, SetTerminationProtection, SetKeepJobFlowAliveWhenNoSteps [U], SetUnhealthyNodeReplacement [U], iam:PassRole, S3 deletes and bucket-policy/versioning/lifecycle changes; List and Get on both buckets; Put on artifacts/*, config/*, input/*, submissions/* only with Null `s3:if-none-match`=false [U].
- Bucket policies: deny `s3:*` without TLS; data bucket deny deletes on protected prefixes and all version deletes except the admin pattern; logs bucket deny deletes except admin. KMS key policy: account root `kms:*` (key use governed by IAM).
- Wrong [U] items fail closed with access denied.

## Execution order

1. Human, read-only profile: account commands. 2. Human fills and commits env.conf, emr-run tfvars, run config; factory-engineer rows and environment list; ratification and restart. 3. infra-engineer: `terraform -chdir=<stack> init -input=false -backend=false` and validate per stack (registry network); human commits three `.terraform.lock.hcl`. 4. code-reviewer; human accepts the security change; commit. 5. `scripts/plan.sh dev bootstrap` (7 add); human signs. 6. deploy-engineer or human `scripts/deploy.sh` (admin); ~0 USD; human archives local state. 7. `scripts/plan.sh dev base` (34 add); signs. 8. deploy.sh; KMS key 0.00137 USD/h starts. 9. Human adds operator profile; checks `sts get-caller-identity`. 10. `sbt packageJobs` at the committed commit and `sbt sparkSubmitLocal`; human fills jar and runtime-jar values; commit; all later steps at that HEAD. 11. `run-bounded-spark.sh --dry-run` (no AWS call; prints both tokens). 12. Human, operator profile: `--stage` (~0.9 GB, ≤2,000 PUTs, cents). 13. `scripts/plan.sh dev emr-run` (1 add); signs. 14. deploy.sh: cluster billing ~0.49 USD/h. 15. Human, operator profile, within 30 min: `--execute` (~0.46-0.76 USD). 16. Agents read-only describe. 17. Human Phase A: cleanup plan-destroy and apply-destroy emr-run. 18. Human archives evidence. 19. Human Phase B: inventory, empty-buckets, plan-destroy and apply-destroy base (KMS scheduled for deletion, free); optionally bootstrap.

## Allowlist rows needed (for factory-engineer)

`@env = dev`, `@env-nonprod = dev`; `<RO>` = scholarchain-readonly, `<REGION>` = us-east-1; new slot `<TFSTACK>` (bootstrap, environments/<ENV>/base, environments/<ENV>/emr-run). infra-engineer: `terraform -chdir=<TFSTACK> init -input=false -backend=false` (ask), `terraform -chdir=<TFSTACK> validate -no-color` (allow), `scripts/plan.sh <ENVNP> {bootstrap;base;emr-run}` (ask, git), `scripts/run-bounded-spark.sh --dry-run <ENVNP> <PATH:infra/terraform/environments/>` (allow, git), narrow exceptions for `bash -n scripts/cleanup.sh` and shellcheck of it, one security-scan row (e.g. checkov). deploy-engineer: activate `scripts/deploy.sh <ENV> <SHA40> <SHA256>`; `shasum -a 256 <TFPLAN>` and `terraform <TFCHDIR> show -no-color tfplan` cover all three stacks. Stage and execute: human Terminal only. New @AWS-RO shapes: emr list-clusters --active, list-bootstrap-actions, list-instance-groups, describe-step, get-auto-termination-policy, describe-release-label; service-quotas get-service-quota; ec2 describe-instance-type-offerings (filter value with `=` and `,` needs a slot class); ec2 get-ebs-encryption-by-default; s3api list-objects-v2 with --max-items.

## Account information needed now

Account ID; admin role ARN shape (`aws sts get-caller-identity --profile scholarchain-admin --query Arn --output text`); Region confirmation; vCPU quota L-1216C47A ≥ 8; `emr describe-release-label emr-7.14.0`; AZ IDs offering m5.xlarge (`--location-type availability-zone-id`); `owner` tag handle and `expiration` date. EBS default encryption optional.

## Assumptions and risks (as reported)

Assumptions: `dev` and us-east-1; management account of its own organization (no SCPs); SSO profile usable as `source_profile` [U]; node images have AWS CLI v2 with `--if-none-match`, sha512sum/sha1sum, Corretto 17, `/mnt/var/lib/info/instance.json` [U]; terminated cluster re-created on re-plan [U]; `-lockfile=readonly`, partial local backend `path`, `use_lockfile` [U]; job accepts local `--config` and `--benchmark-pairs`; output root `output/spark/`.
Risks: no terraform, shellcheck or scan run; Option A undocumented by AWS; Hadoop client 3.3.4 vs YARN 3.4.2; EMR core-site S3A conflicts; S3A ancestor probes and `s3:if-none-match` unverified (fail closed); private subnet may need interface endpoints (fails cheaply); bootstrap name check may falsely refuse; 30-minute execute window; AddJobFlowSteps runs code with R2 powers; in-transit encryption off; wrong admin pattern makes cleanup's empty-buckets denied (fail-safe); `plan -lock=false`; HDFS checkpoint and event log lost on termination (copied first); env.conf commits the account ID; scripts not executable.

## Manual action required (as reported)

Human: account commands and REPLACE-ME values (`input_tree_sha256`: commit 64 zeros, read the value from the `--dry-run` refusal, review, commit); `git update-index --chmod=+x` for the six executable scripts; install terraform ≥1.10, shellcheck and a scanner; commit lock files; add operator profile after base; approve the security change, sign plan checksums, run stage, execute and cleanup in a Terminal. factory-engineer: rows and environment lists. dependency-updater or human: confirm provider pin.
