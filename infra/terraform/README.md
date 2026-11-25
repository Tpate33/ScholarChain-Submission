# ScholarChain Terraform (owner: infra-engineer)

Lifecycle L2 for the bounded EMR Spark run (Phase 6, IE-P6-02, repair 01 for CR-P6-27). Nothing here
has been applied. The environment name `dev` is a placeholder pending ratification of the environment
list.

| Stack | Directory | State | Contents |
|---|---|---|---|
| bootstrap | `bootstrap/` | local file `$HOME/<bootstrap_state_home_relative>` (outside the repository) | state bucket `scholarchain-tfstate-<account>-<region>` |
| base | `environments/<env>/base/` | S3 `scholarchain/<env>/base/terraform.tfstate` (lock file in S3) | VPC, private subnet, S3 gateway endpoint, EMR security groups (plus explicit node egress: HTTPS to the S3 prefix list, all traffic inside the VPC), data and logs buckets, KMS key, EMR security configuration, IAM R1-R3 and R5 (read-only) |
| emr-run | `environments/<env>/emr-run/` | S3 `scholarchain/<env>/emr-run/terraform.tfstate` | one transient `aws_emr_cluster` (yarn-site `yarn.resourcemanager.am.max-attempts=1`), the KMS interface endpoint and its security group |

Inputs: `environments/<env>/env.conf` (committed: Region, AZ ID, profiles, tags), the human-local,
gitignored `environments/<env>/env.local.conf` (account ID, `admin_principal_arn`, API ingress CIDRs,
API certificate ARN or `none`; template `env.local.conf.example`; IE-P13-02), both exported by
`scripts/lib/common.sh` as `TF_VAR_*`, and each stack's `terraform.tfvars`. No variable has a default.
plan.sh, deploy.sh and the run scripts refuse while env.local.conf is missing, tracked, not ignored,
or holds REPLACE-ME or other dummy values. Teardown and evidence (cleanup.sh, archive-evidence.sh,
`allow-expired`) require and check only `aws_account_id` and `admin_principal_arn`, so lost or stale
API values never block teardown; smoke-test.sh does not read env.local.conf (CR-P13-02).
Provider `hashicorp/aws = 6.72.0` in every stack (module `private-bucket`: `>= 6.0.0, < 7.0.0`),
Terraform `>= 1.10.0, < 2.0.0` (the human's installed 1.17.2 satisfies it). Filled for `dev`
(IE-P6-03): us-east-1, AZ ID use1-az4, owner `taufeeq`, expiration 2026-11-26; the account ID is
in env.local.conf only. It was redacted from `infra/` and `scripts/` on 2026-11-24; historical
evidence under `docs/agent-runs/phase-06` and git history still contain it (human decision).

## Version compatibility (IE-P6-03)

- Terraform 1.17.2 and `hashicorp/aws` 6.72.0: release existence from emr-docs-research-05.md
  (6.72.0 seen verbatim). The provider's protocol 5.0 is supported by Terraform 1.x.
- Provider v6 `region` argument: no resource or data source in these stacks sets `region`; every
  resource uses the provider's Region. The only `region` key is the S3 *backend* configuration of
  `terraform_remote_state` in emr-run (backend setting, not the v6 resource argument). No `aws_s3_bucket`
  reads or sets `region` or `bucket_region`.
- S3 buckets use only the split resources (`aws_s3_bucket_ownership_controls`, `_public_access_block`,
  `_server_side_encryption_configuration`, `_versioning`, `_lifecycle_configuration`, `_policy`); no
  deprecated inline `aws_s3_bucket` argument (acl, versioning, lifecycle_rule, policy,
  server_side_encryption_configuration, logging) is used.
- `aws_emr_cluster`, `aws_emr_security_configuration`, `aws_vpc_endpoint`: no v6 upgrade-guide entry
  (research 05). Arguments were checked against main-branch documentation, not the 6.72.0 tag.
- S3 backend `use_lockfile = true`: non-experimental on the Terraform 1.17 page; the exact minimum
  Terraform version is UNVERIFIED (introduced, experimental, in 1.10).
- UNVERIFIED until the human's local `terraform validate` with 6.72.0 runs: argument behaviour at the
  6.72.0 tag (versus main-branch docs), any v6 change to `aws_s3_bucket_lifecycle_configuration`
  (empty `filter {}`), and that no 6.x deprecation warning appears. Validate prints warnings; review them.

## State lock permissions (IE-P6-03)

- Apply (`scripts/deploy.sh`, `cleanup.sh apply-destroy`, profile `scholarchain-admin` = the human's
  user with AdministratorAccess) takes the S3 lock: s3:GetObject, s3:PutObject and s3:DeleteObject on
  `scholarchain/<env>/<stack>/terraform.tfstate.tflock` in `scholarchain-tfstate-<account>-<region>`.
  The state bucket policy (bootstrap, `additional_policy_documents = []`) has only the TLS-only deny,
  no delete deny, so the admin can create and delete the lock object. The bucket is versioned: a
  delete leaves a delete marker and a noncurrent version (expired after 90 days; removed by
  `cleanup.sh empty-buckets bootstrap`). The delete denies of the data and logs buckets (base) do not
  apply to the state bucket.
- Plan (`scripts/plan.sh`, `cleanup.sh plan-destroy`) runs `terraform plan -lock=false`: no lock object
  is read, written or deleted; the read-only role needs only s3:ListBucket and s3:GetObject on the
  state keys (ReadOnlyAccess). `terraform init` and `validate` take no lock.
- bootstrap uses a local backend: no S3 lock.

## EC2 vCPU quota (prerequisite for emr-run)

The emr-run cluster is 1 primary + 1 core m5.xlarge = 8 vCPU (unchanged; `HC_MAX_INSTANCES=2`). The
account's us-east-1 quota L-1216C47A (Running On-Demand Standard (A, C, D, H, I, M, R, T, Z) instances)
was 5.0 on 2026-11-18; the human is requesting 8. `scripts/deploy.sh` refuses an emr-run apply while
`aws service-quotas get-service-quota --service-code ec2 --quota-code L-1216C47A` reports less than 8
(read-only call with the deploy profile). bootstrap and base need no vCPU and can be applied before the
increase. Other running On-Demand Standard instances in the Region also consume the quota and are
not counted by the check.

## Local checks without AWS (human Terminal, repository root)

No command below calls AWS. `init -backend=false` downloads the provider from the Terraform Registry
and writes `.terraform/` (gitignored) and `.terraform.lock.hcl` (to be committed, step 2 below).

```sh
terraform -chdir=infra/terraform fmt -check -recursive
for d in infra/terraform/bootstrap infra/terraform/environments/dev/base infra/terraform/environments/dev/emr-run; do
  terraform -chdir="$d" init -input=false -backend=false && terraform -chdir="$d" validate
done
shellcheck scripts/plan.sh scripts/deploy.sh scripts/cleanup.sh scripts/run-bounded-spark.sh scripts/lib/common.sh scripts/emr/*.sh
bash -n scripts/cleanup.sh
bash scripts/emr/check-submit-args.sh
```

Expected on success: `fmt -check` prints nothing and exits 0; each `init` ends with
`Terraform has been successfully initialized!` and each `validate` prints
`Success! The configuration is valid.` (warnings, if any, are listed above that line and need
review); `shellcheck` (reads `scripts/.shellcheckrc`, which follows the
`source=scripts/lib/common.sh` directives, so run it from the repository root) and `bash -n` print
nothing and exit 0 (`scripts/run-bounded-pipeline.sh` is shellcheck-clean since IE-P13-02, via
justified directives only); `check-submit-args.sh` prints 4
`[check-submit-args] CASE ok accepted ...` lines (plus the argv of the first), 18
`[check-submit-args] CASE ok refused ...` lines, no `CASE FAIL`, and ends with
`[check-submit-args] RESULT PASS`, exit 0. Any other output or a non-zero exit (`echo $?`) is a
failure to report.

## Full non-cloud validation order (IE-P13-03, CR-P13-01 M1)

```sh
sbt check          # 1. first, as its own command: tests and the test marker .claude/.last-test-run
scripts/check.sh   # 2. then: every other non-cloud gate; reads the marker, never runs sbt
```

`scripts/check.sh` does not run sbt: the shell-write audit accepts a change to the test marker only
when the command is exactly `sbt check` or `sbt clean check`, so sbt inside the script would be
reported as marker forgery. Its `test-marker` gate only reads the marker and fails (with "run
`sbt check` first as its own command") unless the marker is a regular file written by
`sbt markTestRun`, records `tests_failed=0` and `tests_errored=0`, and its `git_head` equals the
current HEAD. A marker written on a dirty tree passes with a NOTE: it binds HEAD, not the
uncommitted changes, so re-run `sbt check` after any edit.

## Cost bound (cluster side)

The bound holds without the operator's Terminal, network or credentials:

- before the step: idle auto-termination (`idle_timeout_seconds`, 900 s in dev);
- the step: `/usr/bin/timeout -k 60 <step_timeout_minutes x 60>` around the wrapper (spark-submit
  inside has its own timeout), `ActionOnFailure = TERMINATE_CLUSTER`: a failed or timed-out step ends
  the cluster;
- after a successful step: idle auto-termination again. The step wrapper removes only its own HDFS
  checkpoint directory (`/scholarchain/checkpoints/<submission id>`) after a successful job, so HDFS
  utilization does not hold the cluster out of idle; a removal failure is logged, not fatal.
- emergency stop: R3 (operator) may call `elasticmapreduce:TerminateJobFlows` only on clusters tagged
  with its own `project` and `environment` (IE-P6-02 repair 02; it was explicitly denied before). It
  still cannot create, resize, change protection or scaling, or delete data.

`keep_job_flow_alive_when_no_steps` stays `true`: with `false` the cluster would end as soon as it is
ready, before the operator adds the step.

Worst-case window = provisioning 20 + idle 15 + step 31 (30 + 1 kill) + idle 15 + termination 5 =
86 min; 2 x m5.xlarge with EBS plus one interface endpoint: 0.7314 USD (endpoint rate bounded at
0.02 USD/h, UNVERIFIED). The KMS endpoint keeps billing until cleanup Phase A destroys emr-run
(about 0.24 USD per day at the reference rate).

## Network

base and emr-run: no internet gateway, NAT gateway or public IP. (The runtime stack adds an internet
gateway and two public subnets for its API load balancer only; the private route table that the EMR
nodes use is unchanged. See `environments/dev/runtime/README.md`.) Nodes reach S3 through the gateway endpoint and KMS
(local-disk encryption at boot) through the interface endpoint with private DNS. Not added, because
no node-side call to them is expected: `elasticmapreduce` (the EMR service reaches the nodes through
the service access security group), `sts` and `ec2` (credentials come from the instance metadata
service), `logs` (no CloudWatch agent). This minimum is UNVERIFIED against AWS documentation in this
session; a missing endpoint fails provisioning (`TERMINATED_WITH_ERRORS`, cost within the
provisioning allowance).

## Procedure (each step needs the approval named in the IE-P6-02 report)

1. Human: review and commit `env.conf` (filled by IE-P6-03) and fill the REPLACE-ME values of
   `emr-run/terraform.tfvars` and `runs/spark-dev-run-01.conf` (artifact digests, see IE-P6-03).
2. Provider lock files: `terraform -chdir=<stack> init -input=false -backend=false` in each stack, then
   the human commits the three `.terraform.lock.hcl` files (plan.sh uses `-lockfile=readonly`).
3. `SCHOLARCHAIN_PLAN_FIRST_WITH_DEPLOY_PROFILE=bootstrap scripts/plan.sh <env> bootstrap` (human
   Terminal, see Identity and profiles), human signs the checksum, `scripts/deploy.sh <env> <sha40> <sha256>`.
   The bootstrap state file stays local; the human copies it into the evidence archive after each
   apply. It is not migrated into the bucket it creates.
4. `SCHOLARCHAIN_PLAN_FIRST_WITH_DEPLOY_PROFILE=base scripts/plan.sh <env> base`, signature,
   `scripts/deploy.sh`. From here on every plan uses `scholarchain-readonly`.
5. `scripts/run-bounded-spark.sh --dry-run`, then `--stage` (human Terminal, stage token).
6. Quota L-1216C47A >= 8 (see EC2 vCPU quota). `scripts/plan.sh <env> emr-run`, signature,
   `scripts/deploy.sh` (refuses below 8; the cluster and the KMS endpoint start billing).
7. `scripts/run-bounded-spark.sh --execute` (human Terminal, execute token), within the idle timeout
   (15 minutes in dev) of step 6, or the cluster ends by itself.
8. `scripts/cleanup.sh` Phase A (emr-run), evidence archival with the ARCHIVE-COMMAND lines of
   `cleanup.sh <env> inventory` (layout in the header of `scripts/cleanup.sh`), Phase B (buckets,
   base, optionally bootstrap). `empty-buckets base` refuses until every object under
   `submissions/`, each run's event log and step logs, and the `run=`/`load=` listing are archived.

Local check without AWS: `scripts/emr/check-submit-args.sh` runs the step wrapper's argument
construction (`--self-check`) for accepted and refused sample `fs.defaultFS` values.

## Identity and profiles (human decision 2026-11-17: no IAM Identity Center, no AWS Organizations)

One IAM user for the human (console password + MFA, no access keys), `admin_principal_arn` in
`env.local.conf` (not committed); the CLI signs in with `aws login` (AWS CLI >= 2.32.0, short-lived credentials up to 12 h,
managed policy SignInLocalDevelopmentAccess on the user). Roles: R3 `scholarchain-<env>-operator`
and R5 `scholarchain-<env>-readonly` (AWS managed ReadOnlyAccess), both assumable only by that user.
The bucket-policy delete exceptions name that user only.

`source_profile` pointing directly at an `aws login` profile is not documented, so the documented
`credential_process` route is used (UNVERIFIED end to end; Terraform reads `credential_process`
profiles through the AWS SDK, also UNVERIFIED for these credentials). `~/.aws/config`, no keys:

```ini
[profile scholarchain-login]
# aws login --profile scholarchain-login
region = us-east-1

[profile scholarchain-admin]
# deploy profile (aws_deploy_profile): deploy.sh, cleanup.sh, the first plans of bootstrap and base
credential_process = aws configure export-credentials --profile scholarchain-login --format process
region = us-east-1

[profile scholarchain-readonly]
# aws_readonly_profile: plan.sh, cleanup.sh listings, agents and the aws-api MCP server (after base)
role_arn = arn:aws:iam::<account>:role/scholarchain-<env>-readonly
source_profile = scholarchain-admin
region = us-east-1

[profile scholarchain-operator]
# aws_operator_profile: run-bounded-spark.sh --stage and --execute
role_arn = arn:aws:iam::<account>:role/scholarchain-<env>-operator
source_profile = scholarchain-admin
region = us-east-1
```

Chicken and egg: the read-only role is created by base, so the first plans of bootstrap and base
cannot use it. `scripts/plan.sh` uses the read-only profile by default and always for emr-run. Only
in the human's Terminal (not inside Claude Code, stdin and stdout a TTY) and with
`SCHOLARCHAIN_PLAN_FIRST_WITH_DEPLOY_PROFILE=<bootstrap|base>` naming the stack, it plans with the
deploy profile, after confirming with `iam get-role` that `scholarchain-<env>-readonly` does not
exist (NoSuchEntity). Once base has created the role the exception refuses, so the read-only default
cannot be bypassed later; the plan meta file records `plan_profile`. cleanup.sh reads with the
deploy profile only for bootstrap teardown and `inventory after-base`, because the read-only role is
destroyed with base.

Residual: the read-only profile sources the human's admin credentials through `credential_process`
in the same OS account, so the boundary for agents is the guard's mandatory `--profile <RO>` and the
read-only role, not file permissions.
