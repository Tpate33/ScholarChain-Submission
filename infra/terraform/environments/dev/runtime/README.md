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
There is no public ingress, NAT, ALB, replica, Container Insights or new VPC.

`terraform.tfvars.json` and `approved-run.json` are intentionally absent: current
inventory, image digests, addresses, manifests, reviewed gates and spending are
unknown. They must be fixed and committed before a live plan. Never insert secret
values into either file, Terraform input, command strings or materialization JSON.
All materialization entries are explicit nonsecret bucket/key/size/hash/local-path
references; the storage-owned SDK helper downloads them into the image's existing
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
