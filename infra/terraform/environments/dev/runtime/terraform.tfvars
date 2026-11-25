# Stack settings for runtime (dev). Owner: infra-engineer (IE-P13-05, human decision 2026-11-24).
# PLACEHOLDER FILE: every value that contains REPLACE-ME must be replaced, in a reviewed commit, before
# a plan. scripts/plan.sh refuses the runtime stack while any non-comment line of this file contains
# REPLACE-ME (require_runtime_inputs in scripts/lib/common.sh); several variable validations in
# variables.tf (run_id, the four times, zone_prefixes, materialization_manifests, and the number type of
# ports) reject the placeholders a second time. See README.md, "Filling the variables file".
#
# Terraform loads this file automatically, and a value here OVERRIDES a TF_VAR_* environment variable of
# the same name. Therefore this file must NEVER assign any of the variables below (plan.sh refuses it):
#   from env.conf and env.local.conf, exported by scripts/lib/common.sh (tf_env):
#     aws_account_id environment course owner expiration availability_zone_id api_ingress_cidrs
#     api_certificate_arn, and data_bucket and logs_bucket (derived exactly like base:
#     scholarchain-<env>-data|logs-<account>-<region>)
#   real AWS identifiers, exported by the human as TF_VAR_<name> in the Terminal that runs plan.sh, never
#   committed (they carry the account ID, resource IDs or a personal address):
#     vpc_id                  base output vpc_id
#     private_subnet_id       base output private_subnet_id
#     private_route_table_id  route table of base's private subnet (not a base output; read-only
#                             `aws ec2 describe-route-tables` filtered by that subnet association)
#     s3_prefix_list_id       AWS-managed prefix list com.amazonaws.us-east-1.s3 that base's S3
#                             gateway endpoint uses (not a base output; read-only
#                             `aws ec2 describe-managed-prefix-lists`, field PrefixListId)
#     kms_key_arn             base output emr_kms_key_arn
#     runtime_image           <account>.dkr.ecr.us-east-1.amazonaws.com/<repo>@sha256:<digest>
#     worker_image            same shape
#     collector_image         same shape
#     notification_email      address subscribed to the deadline topic (confirm the SNS e-mail)
#     emr_cluster_id          reviewed j-... ID of the bound cluster, or empty (disables the EMR branch)
# Nothing in this file is a secret; never put a secret value here.

# Two-AZ Neptune subnet group: second private subnet inside base's VPC 10.42.0.0/24 (base
# terraform.tfvars), next to base's private subnet 10.42.0.0/26. Proposed design value, checked against
# the committed base settings only (not against the live VPC).
second_subnet_cidr = "10.42.0.64/26"

# AZ ID of the second private subnet and of the second public (ALB) subnet. Must differ from env.conf
# availability_zone_id (use1-az4) and offer Neptune db.t4g.medium and Fargate; choose it from the
# human's read-only offering checks.
second_az_id = "REPLACE-ME-second-az-id-not-use1-az4"

# Two public /27 subnets for the API load balancer, inside 10.42.0.0/24 and clear of 10.42.0.0/26 and
# 10.42.0.64/26 (first in availability_zone_id, second in second_az_id). Proposed design values, checked
# against the committed base settings only. 10.42.0.192/26 stays free.
public_subnet_cidrs = ["10.42.0.128/27", "10.42.0.160/27"]

# Run identifier (^[a-z0-9-]{1,48}$) and the run window, RFC3339 UTC. runtime_deadline and emr_deadline
# must be after run_start and at most 2h after it; runtime_deadline at or before 2026-11-26T05:00:00Z
# (November 25 23:00 CST); graph_experiment_deadline after run_start and at or before runtime_deadline.
# They must equal the reviewed approved-run.json.
run_id = "REPLACE-ME-run-id"

run_start = "REPLACE-ME-rfc3339-utc-run-start"

runtime_deadline = "REPLACE-ME-rfc3339-utc-runtime-deadline"

emr_deadline = "REPLACE-ME-rfc3339-utc-emr-deadline"

graph_experiment_deadline = "REPLACE-ME-rfc3339-utc-graph-experiment-deadline"

# Container ports (numbers) of the three services; the ALB target groups and security group rules use
# query and evidence. Must equal the ports the service configuration binds.
ports = {
  query    = "REPLACE-ME-query-container-port-number"
  evidence = "REPLACE-ME-evidence-container-port-number"
  graph    = "REPLACE-ME-graph-container-port-number"
}

# Exactly one reviewed shell invocation per task, without secret values (run after materialization).
commands = {
  query     = ["REPLACE-ME-query-command"]
  evidence  = ["REPLACE-ME-evidence-command"]
  graph     = ["REPLACE-ME-graph-command"]
  er        = ["REPLACE-ME-er-command"]
  loader    = ["REPLACE-ME-loader-command"]
  collector = ["REPLACE-ME-collector-command"]
}

# SCHOLARCHAIN_CONFIG_FILES per task: comma-separated absolute paths under /work.
config_files = {
  query     = "REPLACE-ME-query-config-paths"
  evidence  = "REPLACE-ME-evidence-config-paths"
  graph     = "REPLACE-ME-graph-config-paths"
  er        = "REPLACE-ME-er-config-paths"
  loader    = "REPLACE-ME-loader-config-paths"
  collector = "REPLACE-ME-collector-config-paths"
}

# Exact data-bucket object keys (for example config/<sha256>/<file>) the tasks may read; keys only,
# never a bucket name or ARN.
config_objects = {
  query     = "REPLACE-ME-query-config-object-key"
  evidence  = "REPLACE-ME-evidence-config-object-key"
  graph     = "REPLACE-ME-graph-config-object-key"
  er        = "REPLACE-ME-er-config-object-key"
  loader    = "REPLACE-ME-loader-config-object-key"
  collector = "REPLACE-ME-collector-config-object-key"
}

# Exactly six relative data-bucket prefixes ending in "/" (lowercase, no ".."); GetObject for every task,
# PutObject for er, loader and collector.
zone_prefixes = [
  "REPLACE-ME-zone-prefix-1/",
  "REPLACE-ME-zone-prefix-2/",
  "REPLACE-ME-zone-prefix-3/",
  "REPLACE-ME-zone-prefix-4/",
  "REPLACE-ME-zone-prefix-5/",
  "REPLACE-ME-zone-prefix-6/",
]

# Literal nonsecret scholarchain.s3-materialize.v1 JSON per task (retryMaxAttempts 1; exact keys,
# sha256 values and byte counts), as one escaped JSON string each. Write every "bucket" field as the
# literal token @DATA_BUCKET@ (for example \"bucket\":\"@DATA_BUCKET@\"), never the real bucket name:
# compute.tf replaces the token with var.data_bucket (tf_env) at plan time, so this file stays free of
# the account ID (CR-P13-04 m1). plan.sh refuses any non-comment line of this file that contains
# "arn:", the account ID or a 12-digit number outside a 64-hex sha256 value.
materialization_manifests = {
  query     = "REPLACE-ME-query-materialization-json"
  evidence  = "REPLACE-ME-evidence-materialization-json"
  graph     = "REPLACE-ME-graph-materialization-json"
  er        = "REPLACE-ME-er-materialization-json"
  loader    = "REPLACE-ME-loader-materialization-json"
  collector = "REPLACE-ME-collector-materialization-json"
}
