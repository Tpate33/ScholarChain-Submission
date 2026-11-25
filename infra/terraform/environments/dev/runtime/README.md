# Bounded private runtime — offline preparation

This new, separate stack reuses explicit approved base network/KMS/bucket IDs. It
never imports base state or discovers the account. Provider: existing pinned AWS
6.72.0. No backend initialization, plan, apply or AWS inspection was performed.

The graph declares nine PAY_PER_REQUEST tables, exactly eight ALL GSIs, five
`expiresAt` TTL tables; one encrypted vector bucket with five fixed 256/cosine
indexes; two empty Secrets Manager resources; a private, encrypted IAM-authenticated
Neptune cluster with one t4g.medium writer and a two-AZ subnet group; six single-AZ
interface endpoints and a DynamoDB gateway; two immutable ECR repositories; six
Fargate task definitions, three services with desired count zero; private Cloud
Map; graph scaling initially suspended at zero admission (1–2 during the experiment); logs/dashboard/CPU alarms; and an
external minute-based Lambda stop monitor with notification and failure alarm.
There is no NAT, replica, Container Insights or new VPC.

## API load balancer (IE-P13-02, human decision 2026-11-24)

`alb.tf` adds an internet-facing ALB so the smoke test and the demo can reach
the query and evidence services. Graph stays private. Services stay in the
private subnet without public IPs; the ALB sits in two new public subnets
(`public_subnet_cidrs`, AZ IDs `availability_zone_id` and `second_az_id`) with an
internet gateway and their own route table. The private route table is unchanged.

- Ingress: only `api_ingress_cidrs` (1-4 canonical IPv4 CIDRs, /24../32, never
  0.0.0.0/0), from the human-local, gitignored `../env.local.conf`, exported by
  `scripts/lib/common.sh` as `TF_VAR_api_ingress_cidrs`. ALB egress goes only to
  the tasks group, on the two container ports. Tasks admit the ALB group on those
  ports only.
- HTTPS (443 query, 8443 evidence, policy ELBSecurityPolicy-TLS13-1-2-2021-06) when
  `api_certificate_arn` is an ACM ARN. The certificate names a custom domain, which
  the human points at the ALB outside this stack, and that domain goes in
  `../smoke.conf`. With `api_certificate_arn=none` the listeners are HTTP on
  8080/8081. **Trade-off:** the service API key (Bearer token) and the answers then
  cross the internet unencrypted. The CIDR restriction limits who can connect, but
  anyone on the network path can still read the key. Use HTTP only for a short dev
  demo, and rotate the key in Secrets Manager afterwards.
- Health check: `GET /healthz` on the container port (liveness). `scripts/smoke-test.sh`
  checks `/healthz` and `/readyz` through each listener.
- Output `api_endpoint`: `alb_dns_name`, `query_base_url`, `evidence_base_url`
  (copy these into `../smoke.conf` in a reviewed commit after apply).
- Cost: these prices are reference values that were NOT verified in this session.
  ALB about 0.0225 USD/h plus LCU about 0.008 USD per LCU-hour (smoke traffic is
  far below 1 LCU). Public IPv4 addresses are about 0.005 USD/h each, with at least
  one per ALB subnet (2). The internet gateway has no hourly charge, and data
  transfer out is negligible at smoke volume. Total: about 0.035-0.04 USD/h while
  the runtime stack exists, including the time services run at desired count zero.
  The ALB bills until `cleanup.sh ... apply-destroy runtime`. No NAT gateway.
- Access logs are off, to avoid a log-delivery bucket policy. Requests are visible
  only in the service logs.

`terraform.tfvars` is committed as a **placeholder file** (IE-P13-05, human decision
2026-11-24); `approved-run.json` is still intentionally absent. Current inventory,
image digests, manifests, reviewed gates and spending are unknown, and both must be
fixed and committed before a live plan. Never insert secret values into either file,
Terraform input, command strings or materialization JSON.

## Filling the variables file (IE-P13-05)

The file name is `terraform.tfvars` (HCL), the convention of `base` and `emr-run`,
instead of the earlier `terraform.tfvars.json`: HCL allows the comments that mark
each placeholder. `scripts/plan.sh` refuses any other auto-loaded variables file
(`terraform.tfvars.json`, `*.auto.tfvars`, `*.auto.tfvars.json`) in this directory.
Terraform loads `terraform.tfvars` automatically, and a value in it **overrides** a
`TF_VAR_*` environment variable of the same name. That is why the file never assigns
an account-bearing or script-supplied variable, and `plan.sh` refuses it if it does.
The inputs come from three sources:

| Source | Variables | How |
|---|---|---|
| `scripts/lib/common.sh` (`tf_env`) | `aws_account_id`, `environment`, `course`, `owner`, `expiration`, `availability_zone_id` (env.conf), `api_ingress_cidrs`, `api_certificate_arn` (env.local.conf), `data_bucket`, `logs_bucket` (derived like base: `scholarchain-dev-data\|logs-<account>-us-east-1`) | automatic |
| Human Terminal, `export TF_VAR_<name>=...` before `scripts/plan.sh dev runtime`, never committed | `vpc_id` (base output `vpc_id`), `private_subnet_id` (base output `private_subnet_id`), `private_route_table_id` (route table of base's private subnet; not a base output), `s3_prefix_list_id` (the managed S3 prefix list `com.amazonaws.us-east-1.s3`; not a base output), `kms_key_arn` (base output `emr_kms_key_arn`), `runtime_image`, `worker_image`, `collector_image` (`<account>.dkr.ecr.us-east-1.amazonaws.com/<repo>@sha256:<digest>`), `notification_email`, `emr_cluster_id` (reviewed `j-...`, or empty to disable the EMR branch) | the human's shell |
| `terraform.tfvars`, committed in a reviewed commit | `second_subnet_cidr`, `second_az_id`, `public_subnet_cidrs`, `run_id`, `run_start`, `runtime_deadline`, `emr_deadline`, `graph_experiment_deadline`, `ports`, `commands`, `config_files`, `config_objects`, `zone_prefixes`, `materialization_manifests` | edit, review, commit |

The committed file already holds the proposed CIDRs: `second_subnet_cidr = 10.42.0.64/26`,
and `public_subnet_cidrs = [10.42.0.128/27, 10.42.0.160/27]`. They lie inside base's
VPC `10.42.0.0/24` and clear of base's private subnet `10.42.0.0/26`. They were checked
against base's committed `terraform.tfvars` only, not against the live VPC. Every
other value contains `REPLACE-ME`.

Steps, in your own Terminal:

1. Replace every `REPLACE-ME` value in `terraform.tfvars`. `second_az_id` must differ
   from `use1-az4` and offer Neptune `db.t4g.medium` and Fargate. The run times and
   `run_id` must equal the reviewed `approved-run.json`. In `materialization_manifests`,
   write every `bucket` field as the literal token `@DATA_BUCKET@`, never the real
   bucket name (`scholarchain-dev-data-<account>-us-east-1` carries the account ID).
   `compute.tf` replaces the token with `var.data_bucket` (exported by `tf_env`) at
   plan time, so the task definitions get the real name and the committed file does
   not. Review the diff and commit it. The file must hold no account ID, ARN, IP
   address, e-mail address or secret: `plan.sh` refuses any non-comment line that
   contains `arn:`, the env.local.conf account ID, or a 12-digit number (after
   removing 64-hex sha256 values; a byte count of 12 or more digits is refused too),
   and a variable validation refuses a manifest that contains `arn:` or the account
   ID. IP addresses and e-mail addresses are not machine-checked; review for them.
2. Export the ten identifiers from the table, for example
   `export TF_VAR_vpc_id=vpc-...`. Take them from `terraform -chdir=infra/terraform/environments/dev/base output`
   and read-only `aws ec2 describe-route-tables` / `describe-managed-prefix-lists` calls.
   Keep a private, uncommitted note of them outside the repository.
3. Run `scripts/plan.sh dev runtime`. It refuses (exit 3, or 2 for a malformed shape)
   if any of the following holds:
   - a non-comment line of `terraform.tfvars` still contains `REPLACE-ME`;
   - a non-comment line of `terraform.tfvars` contains `arn:`, the account ID or a
     12-digit number (outside a sha256 value);
   - the file assigns a variable from the first two rows;
   - another auto-loaded variables file exists;
   - a `TF_VAR_*` from the second row is unset, `REPLACE-ME`, or of the wrong shape;
   - the KMS ARN or an image is not in the env.local.conf account and Region.

   It prints names and counts only, never a value.

**Which control rejects an unfilled file:** `scripts/plan.sh`
(`require_runtime_inputs`) rejects every placeholder. As a second layer, the variable
validations reject some placeholders: `run_id`, the four times, `zone_prefixes`,
`materialization_manifests`, and (by type) `ports`. Other placeholders pass
Terraform's own validation: `second_az_id`, `commands`, `config_files` and
`config_objects`. For those, `plan.sh` is the only gate before AWS rejects them at
apply.

## Cleanup recovery (CR-P13-03)

`scripts/cleanup.sh dev plan-destroy runtime` evaluates this stack's variable
validations even though a destroy plan creates nothing. It therefore needs a
**filled, validation-passing `terraform.tfvars` committed** at HEAD, because cleanup
requires a clean tree. Keep the filled file that was applied committed until
`apply-destroy runtime` reports `RESULT DESTROYED`. Do not revert it to placeholders
earlier.

If the file was lost or reverted after an apply, restore the applied version in your
own Terminal with `git show <applied commit>:infra/terraform/environments/dev/runtime/terraform.tfvars`.
Write it to the same path, commit it, and rerun `plan-destroy runtime`. Any
validation-passing values also work, because a destroy plan does not use them.

The ten human identifiers are not needed for teardown. In teardown mode `tf_env`
exports inert, validation-passing values for them (`vpc-000...`, a zero-digest image
under the account, `teardown-only@example.invalid`, an empty EMR ID), as it already
does for the API variables (CR-P13-02 m-B). `plan.sh` refuses the zero digest for a
create plan.
All materialization entries are explicit nonsecret bucket/key/size/hash/local-path
references (bucket written as `@DATA_BUCKET@` in `terraform.tfvars`, substituted in
`compute.tf`); the storage-owned SDK helper downloads them into the image's existing
UID-10001-writable `/work`. Runtime config paths are comma-separated absolute
`SCHOLARCHAIN_CONFIG_FILES`. Worker bootstrap uses the packaged `bin/s3-materialize` launcher; normal
runtime helper classpath is `/opt/docker/lib/*`. All six final invocations are
explicit single-element `commands` lists. Collector code/image remains a separate
prerequisite; specifying an image does not establish that collector exists.

Offline checks, from the repository root:

```
python3 scripts/runtime/package-monitor.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/runtime/check-offline.py
terraform -chdir=infra/terraform fmt -check -recursive
terraform -chdir=infra/terraform/environments/dev/runtime validate -no-color
bash -n scripts/lib/common.sh scripts/plan.sh scripts/deploy.sh scripts/cleanup.sh
shellcheck scripts/lib/common.sh scripts/plan.sh scripts/deploy.sh scripts/cleanup.sh
```

`deadline-monitor.zip` is a deterministic deployment artifact containing only
reviewed `scripts/runtime/deadline_monitor.py`, named `deadline_monitor.py`.
Package it again after any source edit. Lambda has no VPC attachment, so stopping
ECS/EMR does not depend on the runtime endpoints. SDK attempts are one. EventBridge
and Lambda asynchronous retries are disabled. The monitor stops expired ECS tasks,
zeros services and suspends scaling at the absolute runtime deadline; closes the
Graph experiment window by clamping its suspended target to [0,1], preserving an
explicit stopped state; refuses concurrent ER/loader work; and terminates only
the explicitly supplied EMR cluster on its 120-minute ceiling or first failed
step. A missing EMR ID disables its EMR branch: **an integrated run requires a
reviewed real ID and a fresh plan binding it before any EMR chain is admitted**.
Minute scheduling and AWS API failures introduce delay; this is no billing hard
cap. Neptune/endpoints/storage remain subject to separately approved cleanup.

Lifecycle scripts now recognize `runtime`. Existing clean-tree, reviewed plan
checksum, account/identity and human-Terminal gates remain. The collector execution role retrieves only its benchmark secret into `SCHOLARCHAIN_BENCHMARK_KEY`; commands and logs must never print it. Runtime deploy also
requires committed `approved-run.json` validated by `scripts/runtime/approval.py`
and `SCHOLARCHAIN_RUNTIME_APPROVAL=runtime-<HEAD>-<saved-plan-sha256>-<approval-json-sha256>`.
Approval JSON is a human-produced launch package, not an agent claim of review;
its booleans and reported counts do not themselves enforce account-wide API caps.
It binds a <=120m runtime/EMR window, <=3h Neptune/<=6h endpoint windows, the
November 25 23:00 CST absolute limit, <=10K vectors/10.5K embeddings/100 answers/
50K graph requests, exact artifact hashes, verified prior spend and the $12
incremental/$25 cumulative condition. Actual application/provider limits and
collector request counters still require execution evidence before launch.

`scripts/runtime/control.py <reviewed-inventory.json> <action>` defaults to offline
validation. `--execute` requires a human Terminal, a clean committed tree, explicit
AWS_PROFILE and an exact `SCHOLARCHAIN_RUN_APPROVAL=<HEAD>:<inventory-sha256>:<approval-sha256>:<action>`.
It admits one ER task, then one loader, then starts services, then one collector;
Graph experiment admission is a separate action inside its final 20-minute
window. `stop-services` suspends graph scaling before setting every desired count to zero;
subsequent monitor ticks never request a positive count. Exclusive local receipts prevent automatic stage retry. A failed provider
call aborts; no replacement is launched by this controller. ECS services themselves
can replace failed tasks until their deadline; Lambda remains the external stop
control. EMR provisioning/Hadoop/Spark submission is **not** implemented by this
controller; historical Phase 6 launch permissions and script pins must not be
reused. The approved complete single-cluster chain is a remaining launch gate.

Cleanup is human-only through existing `scripts/cleanup.sh dev plan-destroy runtime`
and checksum-approved `apply-destroy runtime`, after reviewing inventory and
archiving evidence. Runtime destroy removes these ephemeral stores; it does not
remove base data/log buckets. ECR images are explicitly force-deleted with their
reviewed Terraform repository destroy; S3 vector indexes and their vectors are
removed with their Terraform index destroy. Secrets schedule deletion after seven
days; retained charges and that recovery window require reconciliation. Base
cleanup now requires runtime and EMR stacks empty first. The existing broad base
bucket-emptying operation is a separate destructive decision and is not needed
to tear down this runtime.

Remaining live gates: account inventory and quotas; gross prior spending; Bedrock
availability; image build/push/digests and collector; correct real configuration;
actual complete bounded producer census; reviewed complete EMR chain and bound
cluster ID; external monitor IAM/notification tests; reviewed saved create/destroy
plans with actual counts; specific run/cleanup authorization. No live plan counts,
cost measurement, deployment or stop reliability is claimed by offline validation.

S3 staging prerequisite: each exact input/config upload must supply the full-object
base64 SHA256 S3 checksum, matching the manifest hash. ETags do not satisfy the
existing reader contract. Do not use generic recursive bucket sync. For a manifest
that exceeds ECS override limits, pass a tiny SHA-pinned bootstrap manifest that
downloads the full nonsecret manifest, then invoke the same helper with
`--manifest-file /work/full-manifest.json`; both downloads remain within explicit
file/byte limits. No uploads were performed during this work.

The graph-window clamp relies on the documented RegisterScalableTarget update
behavior: current capacity outside a new minimum/maximum is moved inside those
bounds. A [0,1] clamp reduces two to one while preserving a manually stopped zero.
Reference: https://docs.aws.amazon.com/autoscaling/application/APIReference/API_RegisterScalableTarget.html
