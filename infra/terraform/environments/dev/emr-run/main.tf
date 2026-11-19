# Stack `emr-run` of environment `dev` (owner: infra-engineer). One transient EMR cluster (lifecycle L2)
# and the KMS interface endpoint its nodes need in the private subnet: applied by scripts/deploy.sh right
# before a bounded run, destroyed afterwards by scripts/cleanup.sh (Phase A). Inputs as in
# ../base/versions.tf.
#
# Cluster lifetime bound (CR-P6-27 M2), independent of the operator's Terminal:
#   - no step yet: idle auto-termination after idle_timeout_seconds;
#   - the one step (added later by scripts/run-bounded-spark.sh --execute) runs under
#     /usr/bin/timeout -k 60 <step timeout> with ActionOnFailure TERMINATE_CLUSTER, so a failed or
#     timed-out step ends the cluster;
#   - after a successful step: idle auto-termination again.
# keep_job_flow_alive_when_no_steps stays true: with false, a cluster created without steps would end
# as soon as it is ready, before the operator can add the step (L2 adds it after apply). Declaring the
# step here instead would put every run argument into the plan and remove the run record and token
# gates of run-bounded-spark.sh; not chosen.

terraform {
  required_version = ">= 1.10.0, < 2.0.0"

  backend "s3" {
    key          = "scholarchain/dev/emr-run/terraform.tfstate"
    encrypt      = true
    use_lockfile = true
  }

  required_providers {
    aws = {
      source = "hashicorp/aws"
      # 6.72.0: highest 6.x release seen verbatim (emr-docs-research-05.md, 2026-11-18); IE-P6-03.
      version = "= 6.72.0"
    }
  }
}

provider "aws" {
  region              = var.aws_region
  allowed_account_ids = [var.aws_account_id]
  default_tags {
    tags = {
      course      = var.course
      project     = var.project
      environment = var.environment
      owner       = var.owner
      expiration  = var.expiration
    }
  }
}

data "terraform_remote_state" "base" {
  backend = "s3"
  config = {
    bucket = var.state_bucket
    key    = "scholarchain/${var.environment}/base/terraform.tfstate"
    region = var.aws_region
  }
}

locals {
  base          = data.terraform_remote_state.base.outputs
  install_key   = "artifacts/emr-scripts/${var.install_script_sha256}/install-spark-dist.sh"
  cluster_name  = "${var.project}-${var.environment}-spark-run"
  ebs_per_group = { size = var.data_volume_gib, type = "gp3", volumes_per_instance = 1 }
  # AmazonEMRServicePolicy_v2 creates the private-subnet ENI, instances and volumes only with
  # aws:RequestTag/for-use-with-amazon-emr-managed-policies = "true"; EMR propagates the cluster's tag to
  # them (IE-P6-08). Same key and value as local.emr_managed_tag in ../base/versions.tf. Cluster only:
  # not in default_tags, so the KMS endpoint and its security group stay untagged with it.
  emr_managed_tag = { "for-use-with-amazon-emr-managed-policies" = "true" }
}

# KMS interface endpoint (CR-P6-27 6b). The security configuration's LocalDiskEncryption uses the
# customer managed key, so every node calls KMS at boot; the private subnet has no NAT and no internet
# gateway, and the S3 gateway endpoint does not reach KMS. Private DNS makes the regional KMS name
# resolve to the endpoint. Default (full-access) endpoint policy: key use is governed by IAM (R1, R2)
# and the key policy. Billed per endpoint-AZ-hour while it exists (rate UNVERIFIED in this session;
# reference 0.01 USD/h plus 0.01 USD/GB processed), so it lives in this transient stack.
resource "aws_security_group" "kms_endpoint" {
  name        = "${local.cluster_name}-kms-endpoint"
  description = "HTTPS from the VPC to the KMS interface endpoint only"
  vpc_id      = local.base.vpc_id
  tags        = { Name = "${local.cluster_name}-kms-endpoint" }
}

resource "aws_vpc_security_group_ingress_rule" "kms_endpoint_https" {
  security_group_id = aws_security_group.kms_endpoint.id
  description       = "HTTPS from the VPC (EMR nodes)"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = local.base.vpc_cidr
}

resource "aws_vpc_endpoint" "kms" {
  vpc_id              = local.base.vpc_id
  service_name        = "com.amazonaws.${var.aws_region}.kms"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = [local.base.private_subnet_id]
  security_group_ids  = [aws_security_group.kms_endpoint.id]
  private_dns_enabled = true
  tags                = { Name = "${local.cluster_name}-kms" }
}

resource "aws_emr_cluster" "run" {
  # Nodes need KMS at boot (local-disk encryption).
  depends_on = [aws_vpc_endpoint.kms, aws_vpc_security_group_ingress_rule.kms_endpoint_https]

  name                   = local.cluster_name
  release_label          = var.emr_release_label
  applications           = ["Hadoop"]
  service_role           = local.base.emr_service_role_arn
  security_configuration = local.base.emr_security_configuration_name
  log_uri                = "s3://${local.base.logs_bucket}/emr/"

  keep_job_flow_alive_when_no_steps = true
  termination_protection            = false
  step_concurrency_level            = 1
  ebs_root_volume_size              = var.root_volume_gib
  scale_down_behavior               = "TERMINATE_AT_TASK_COMPLETION"

  auto_termination_policy {
    idle_timeout = var.idle_timeout_seconds
  }

  # No key_name: no SSH. Private subnet: no public IP.
  ec2_attributes {
    subnet_id                         = local.base.private_subnet_id
    instance_profile                  = local.base.emr_instance_profile_arn
    emr_managed_master_security_group = local.base.emr_primary_security_group_id
    emr_managed_slave_security_group  = local.base.emr_core_security_group_id
    service_access_security_group     = local.base.emr_service_access_security_group_id
  }

  # Fixed capacity: 1 primary + 1 core, no task group, no managed or automatic scaling.
  master_instance_group {
    name           = "primary"
    instance_type  = var.instance_type
    instance_count = 1
    ebs_config {
      size                 = local.ebs_per_group.size
      type                 = local.ebs_per_group.type
      volumes_per_instance = local.ebs_per_group.volumes_per_instance
    }
  }

  core_instance_group {
    name           = "core"
    instance_type  = var.instance_type
    instance_count = 1
    ebs_config {
      size                 = local.ebs_per_group.size
      type                 = local.ebs_per_group.type
      volumes_per_instance = local.ebs_per_group.volumes_per_instance
    }
  }

  # Arguments are positional; scripts/emr/install-spark-dist.sh and scripts/run-bounded-spark.sh
  # (list-bootstrap-actions check) rely on this exact order.
  bootstrap_action {
    name = "install-scholarchain-spark"
    path = "s3://${local.base.data_bucket}/${local.install_key}"
    args = [
      local.base.data_bucket,
      var.aws_region,
      var.spark_dist_sha512,
      var.scala_library_sha256,
      var.hadoop_aws_sha256,
      var.hadoop_aws_sha1,
      var.sdk_bundle_sha256,
      var.submit_script_sha256,
    ]
  }

  # S3A must never delete directory markers (no deletes under run= or load=; SP-P6-05 u1).
  # YARN: one application-master attempt (matches spark.yarn.maxAppAttempts=1; CR-P6-27 n1), so a
  # failed driver or AM is never re-run inside the step's time bound.
  configurations_json = jsonencode([
    {
      Classification = "core-site"
      Properties = {
        "fs.s3a.directory.marker.retention" = "keep"
      }
    },
    {
      Classification = "yarn-site"
      Properties = {
        "yarn.resourcemanager.am.max-attempts" = "1"
      }
    }
  ])

  tags = merge(local.emr_managed_tag, {
    Name = local.cluster_name
  })
}

output "cluster_id" {
  value = aws_emr_cluster.run.id
}

output "cluster_name" {
  value = aws_emr_cluster.run.name
}

output "kms_endpoint_id" {
  value = aws_vpc_endpoint.kms.id
}
