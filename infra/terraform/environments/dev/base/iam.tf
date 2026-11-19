# IAM. R1 EMR service role, R2 EMR EC2 instance role, R3 operator role, R5 read-only role.
# R4 (deployer) is NOT created here: the human's IAM user (admin_principal_arn; console password + MFA,
# no access keys, CLI via `aws login`) runs scripts/deploy.sh and scripts/cleanup.sh. No IAM Identity
# Center and no AWS Organizations (human decision 2026-11-17). Agents keep the read-only profile only.
# Items marked UNVERIFIED were not checked against AWS documentation in this session.

locals {
  emr_cluster_arns = "arn:aws:elasticmapreduce:${var.aws_region}:${var.aws_account_id}:cluster/*"
  output_root      = "output/spark"
}

# ---------------------------------------------------------------------------------------------------
# R1: EMR service role
# ---------------------------------------------------------------------------------------------------
data "aws_iam_policy_document" "emr_service_trust" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["elasticmapreduce.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.aws_account_id]
    }
    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:aws:elasticmapreduce:${var.aws_region}:${var.aws_account_id}:*"]
    }
  }
}

resource "aws_iam_role" "emr_service" {
  name               = "${local.name}-emr-service"
  assume_role_policy = data.aws_iam_policy_document.emr_service_trust.json
}

resource "aws_iam_role_policy_attachment" "emr_service_v2" {
  role       = aws_iam_role.emr_service.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonEMRServicePolicy_v2"
}

data "aws_iam_policy_document" "emr_service_extra" {
  statement {
    sid       = "PassOnlyTheInstanceRoleToEc2"
    actions   = ["iam:PassRole"]
    resources = [aws_iam_role.emr_instance.arn]
    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ec2.amazonaws.com"]
    }
  }

  statement {
    sid       = "UseTheEmrKey"
    actions   = ["kms:Encrypt", "kms:Decrypt", "kms:ReEncrypt*", "kms:GenerateDataKey*", "kms:DescribeKey"]
    resources = [aws_kms_key.emr.arn]
  }

  statement {
    sid       = "GrantTheEmrKeyToAwsResourcesOnly"
    actions   = ["kms:CreateGrant", "kms:ListGrants", "kms:RevokeGrant"]
    resources = [aws_kms_key.emr.arn]
    condition {
      test     = "Bool"
      variable = "kms:GrantIsForAWSResource"
      values   = ["true"]
    }
  }
}

resource "aws_iam_role_policy" "emr_service_extra" {
  name   = "scholarchain-emr-service-extra"
  role   = aws_iam_role.emr_service.id
  policy = data.aws_iam_policy_document.emr_service_extra.json
}

# ---------------------------------------------------------------------------------------------------
# R2: EMR EC2 instance role (bootstrap downloads, the Spark job, EMR log push, event-log copy)
# ---------------------------------------------------------------------------------------------------
data "aws_iam_policy_document" "ec2_trust" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "emr_instance" {
  name               = "${local.name}-emr-instance"
  assume_role_policy = data.aws_iam_policy_document.ec2_trust.json
}

resource "aws_iam_instance_profile" "emr_instance" {
  name = "${local.name}-emr-instance"
  role = aws_iam_role.emr_instance.name
}

data "aws_iam_policy_document" "emr_instance" {
  # ListBucket on the whole data bucket, without an s3:prefix condition (CR-P6-27 6a): S3 answers a
  # HEAD or GET of an absent key with 404 only when the caller may list the bucket, and a HeadObject
  # request carries no s3:prefix, so a prefix-conditioned grant yields 403 for every absent key (S3A
  # existence probes, mkdirs, the committer). Delta: key names and sizes of the whole data bucket
  # become listable by the cluster; object reads stay prefix-scoped below.
  statement {
    sid       = "ListDataBucket"
    actions   = ["s3:ListBucket"]
    resources = [local.data_bucket_arn]
  }

  # S3A getFileStatus/mkdirs probe every ancestor of a path it creates or opens (HEAD of the key, of
  # "key/", then LIST); CR-P6-27 6c adds "output/". Which probes Hadoop 3.3.4 issues exactly is
  # UNVERIFIED on EMR; the set covers both spellings of every ancestor of input/ and output/spark/.
  statement {
    sid     = "ReadDataPrefixes"
    actions = ["s3:GetObject"]
    resources = [
      "${local.data_bucket_arn}/artifacts/*",
      "${local.data_bucket_arn}/config/*",
      "${local.data_bucket_arn}/input",
      "${local.data_bucket_arn}/input/*",
      "${local.data_bucket_arn}/output",
      "${local.data_bucket_arn}/output/",
      "${local.data_bucket_arn}/${local.output_root}",
      "${local.data_bucket_arn}/${local.output_root}/*",
    ]
  }

  statement {
    sid     = "WriteOutputRoot"
    actions = ["s3:PutObject", "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"]
    resources = [
      "${local.data_bucket_arn}/${local.output_root}",
      "${local.data_bucket_arn}/${local.output_root}/*",
    ]
  }

  statement {
    sid       = "ListMultipartUploads"
    actions   = ["s3:ListBucketMultipartUploads"]
    resources = [local.data_bucket_arn]
  }

  statement {
    sid       = "DeleteOnlySparkScratch"
    actions   = ["s3:DeleteObject"]
    resources = ["${local.data_bucket_arn}/${local.output_root}/.spark-*"]
  }

  statement {
    sid       = "NeverDeletePublishedOrStagedData"
    effect    = "Deny"
    actions   = ["s3:DeleteObject", "s3:DeleteObjectVersion"]
    resources = local.data_protected_object_arns
  }

  statement {
    sid     = "NeverWriteStagedData"
    effect  = "Deny"
    actions = ["s3:PutObject"]
    resources = [
      "${local.data_bucket_arn}/input*",
      "${local.data_bucket_arn}/artifacts/*",
      "${local.data_bucket_arn}/config/*",
      "${local.data_bucket_arn}/submissions/*",
    ]
  }

  statement {
    sid       = "ListLogPrefixes"
    actions   = ["s3:ListBucket"]
    resources = [local.logs_bucket_arn]
    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["emr/*", "spark-events/*"]
    }
  }

  statement {
    sid       = "PushEmrLogs"
    actions   = ["s3:PutObject"]
    resources = ["${local.logs_bucket_arn}/emr/*"]
  }

  # Event-log copy by the submit wrapper is create-once (put-object --if-none-match "*").
  # Condition key s3:if-none-match: UNVERIFIED name; a wrong key denies the copy (fails closed).
  statement {
    sid       = "CopyEventLogCreateOnce"
    actions   = ["s3:PutObject"]
    resources = ["${local.logs_bucket_arn}/spark-events/*"]
    condition {
      test     = "Null"
      variable = "s3:if-none-match"
      values   = ["false"]
    }
  }

  statement {
    sid       = "VerifyEventLogCopy"
    actions   = ["s3:GetObject"]
    resources = ["${local.logs_bucket_arn}/spark-events/*"]
  }

  statement {
    sid       = "LocalDiskEncryptionKey"
    actions   = ["kms:Encrypt", "kms:Decrypt", "kms:ReEncrypt*", "kms:GenerateDataKey*", "kms:DescribeKey"]
    resources = [aws_kms_key.emr.arn]
  }
}

resource "aws_iam_role_policy" "emr_instance" {
  name   = "scholarchain-emr-instance"
  role   = aws_iam_role.emr_instance.id
  policy = data.aws_iam_policy_document.emr_instance.json
}

# ---------------------------------------------------------------------------------------------------
# R3: operator role for scripts/run-bounded-spark.sh --stage and --execute (human Terminal).
# Assumable only by the human's IAM user (profile with source_profile, see infra/terraform/README.md):
# a script bug cannot create, resize or delete anything. It may terminate only clusters tagged with
# its own project and environment (emergency stop; termination only reduces cost; repair 02 item 1).
# ---------------------------------------------------------------------------------------------------
# Trust: the human's IAM user only (admin_principal_arn), named directly as the principal. With
# `aws login` the CLI holds short-lived credentials of that user; that AssumeRole from them reports
# the user ARN as the principal is UNVERIFIED (a mismatch fails closed: AccessDenied). No MFA
# condition: whether aws:MultiFactorAuthPresent is set in `aws login` credentials is UNVERIFIED.
data "aws_iam_policy_document" "admin_user_trust" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "AWS"
      identifiers = [local.admin_principal_arn]
    }
  }
}

resource "aws_iam_role" "operator" {
  name                 = "${local.name}-operator"
  assume_role_policy   = data.aws_iam_policy_document.admin_user_trust.json
  max_session_duration = 3600
}

data "aws_iam_policy_document" "operator" {
  statement {
    sid       = "FindClusters"
    actions   = ["elasticmapreduce:ListClusters"]
    resources = ["*"]
  }

  # Resource-level and tag-condition support per action: UNVERIFIED for CancelSteps,
  # GetAutoTerminationPolicy, GetManagedScalingPolicy and ListBootstrapActions.
  statement {
    sid = "InspectAndStepOwnEnvironmentClusters"
    actions = [
      "elasticmapreduce:DescribeCluster",
      "elasticmapreduce:ListInstanceGroups",
      "elasticmapreduce:ListBootstrapActions",
      "elasticmapreduce:ListSteps",
      "elasticmapreduce:DescribeStep",
      "elasticmapreduce:GetAutoTerminationPolicy",
      "elasticmapreduce:GetManagedScalingPolicy",
      "elasticmapreduce:AddJobFlowSteps",
      "elasticmapreduce:CancelSteps",
    ]
    resources = [local.emr_cluster_arns]
    condition {
      test     = "StringEquals"
      variable = "aws:ResourceTag/project"
      values   = [var.project]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:ResourceTag/environment"
      values   = [var.environment]
    }
  }

  # IE-P6-02 repair 02 item 1 (CR-P6-28 unverified concern): the step is added with
  # ActionOnFailure TERMINATE_CLUSTER. If EMR authorizes AddJobFlowSteps with that value against
  # the caller's termination authority, an explicit Deny on TerminateJobFlows would deny add-steps
  # and waste the window. Termination only reduces cost, so R3 may terminate clusters of its own
  # project and environment (same tag scope as the step statement above), and nothing else.
  # Privilege delta: + elasticmapreduce:TerminateJobFlows on local.emr_cluster_arns with
  # aws:ResourceTag/project = var.project and aws:ResourceTag/environment = var.environment
  # (previously explicitly denied on "*"). Tag-condition support for TerminateJobFlows: per the
  # EMR service authorization reference as recalled, UNVERIFIED in this session (no docs MCP);
  # an unsupported condition fails closed (no termination authority), never wider.
  # Termination protection stays off (emr-run) and SetTerminationProtection stays denied.
  statement {
    sid       = "EmergencyStopOwnEnvironmentClusters"
    actions   = ["elasticmapreduce:TerminateJobFlows"]
    resources = [local.emr_cluster_arns]
    condition {
      test     = "StringEquals"
      variable = "aws:ResourceTag/project"
      values   = [var.project]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:ResourceTag/environment"
      values   = [var.environment]
    }
  }

  # SetKeepJobFlowAliveWhenNoSteps and SetUnhealthyNodeReplacement: action names UNVERIFIED.
  # TerminateJobFlows removed from this Deny (repair 02 item 1); every other deny is unchanged.
  statement {
    sid    = "NeverChangeClusterLifecycleOrCapacity"
    effect = "Deny"
    actions = [
      "elasticmapreduce:RunJobFlow",
      "elasticmapreduce:ModifyCluster",
      "elasticmapreduce:ModifyInstanceGroups",
      "elasticmapreduce:ModifyInstanceFleet",
      "elasticmapreduce:AddInstanceGroups",
      "elasticmapreduce:AddInstanceFleet",
      "elasticmapreduce:PutAutoScalingPolicy",
      "elasticmapreduce:PutManagedScalingPolicy",
      "elasticmapreduce:RemoveManagedScalingPolicy",
      "elasticmapreduce:PutAutoTerminationPolicy",
      "elasticmapreduce:RemoveAutoTerminationPolicy",
      "elasticmapreduce:SetTerminationProtection",
      "elasticmapreduce:SetKeepJobFlowAliveWhenNoSteps",
      "elasticmapreduce:SetUnhealthyNodeReplacement",
      "iam:PassRole",
      "s3:DeleteObject",
      "s3:DeleteObjectVersion",
      "s3:DeleteBucket",
      "s3:PutBucketPolicy",
      "s3:DeleteBucketPolicy",
      "s3:PutBucketVersioning",
      "s3:PutLifecycleConfiguration",
    ]
    resources = ["*"]
  }

  # Unconditional, as for R2 (CR-P6-27 6a): run-bounded-spark.sh relies on 404 for absent keys
  # (s3_head of staged objects and of the run record). R3 could already read every data object.
  statement {
    sid       = "ListDataBucket"
    actions   = ["s3:ListBucket"]
    resources = [local.data_bucket_arn]
  }

  statement {
    sid       = "ListLogPrefixes"
    actions   = ["s3:ListBucket"]
    resources = [local.logs_bucket_arn]
    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["emr/*", "spark-events/*"]
    }
  }

  statement {
    sid       = "ReadDataAndLogs"
    actions   = ["s3:GetObject"]
    resources = ["${local.data_bucket_arn}/*", "${local.logs_bucket_arn}/*"]
  }

  # Create-once staging. Condition key s3:if-none-match: UNVERIFIED name (fails closed if wrong).
  statement {
    sid     = "StageCreateOnce"
    actions = ["s3:PutObject"]
    resources = [
      "${local.data_bucket_arn}/artifacts/*",
      "${local.data_bucket_arn}/config/*",
      "${local.data_bucket_arn}/input/*",
      "${local.data_bucket_arn}/submissions/*",
    ]
    condition {
      test     = "Null"
      variable = "s3:if-none-match"
      values   = ["false"]
    }
  }
}

resource "aws_iam_role_policy" "operator" {
  name   = "scholarchain-operator"
  role   = aws_iam_role.operator.id
  policy = data.aws_iam_policy_document.operator.json
}

# ---------------------------------------------------------------------------------------------------
# R5: read-only role for profile scholarchain-readonly (scripts/plan.sh after base exists, cleanup.sh
# listings, agent read-only checks and the aws-api MCP server). Assumable only by admin_principal_arn.
# AWS managed ReadOnlyAccess, not ViewOnlyAccess: terraform plan must read the state objects in S3
# (s3:GetObject), and refresh reads bucket policies, IAM inline role policies, the KMS key policy and
# the EMR security configuration, which ViewOnlyAccess does not grant (from knowledge of the two
# policies; their exact action lists are UNVERIFIED in this session). Delta: ReadOnlyAccess can read
# every object in every bucket of the account and describe most services; it grants no write,
# no kms:Decrypt and no secretsmanager:GetSecretValue (UNVERIFIED).
# ---------------------------------------------------------------------------------------------------
resource "aws_iam_role" "readonly" {
  name                 = "${local.name}-readonly"
  assume_role_policy   = data.aws_iam_policy_document.admin_user_trust.json
  max_session_duration = 3600
}

resource "aws_iam_role_policy_attachment" "readonly" {
  role       = aws_iam_role.readonly.name
  policy_arn = "arn:aws:iam::aws:policy/ReadOnlyAccess"
}

# IE-P6-11: ReadOnlyAccess does not grant elasticmapreduce:GetAutoTerminationPolicy (observed
# AccessDenied for scholarchain-dev-readonly: the emr-run refresh in `cleanup.sh dev plan-destroy
# emr-run` while the cluster was WAITING/TERMINATING, and the @AWS-RO get-auto-termination-policy row).
# The aws provider 6.72.0 aws_emr_cluster Read calls DescribeCluster, ListInstanceGroups or
# ListInstanceFleets, ListBootstrapActions, ListSteps and GetAutoTerminationPolicy (from provider
# source knowledge, UNVERIFIED in this session); ReadOnlyAccess covers the Describe*/List* ones, which
# the observed refresh got past. GetManagedScalingPolicy is not added: emr-run declares no managed
# scaling policy, the cluster Read does not call it, and no @AWS-RO row names it.
# Scope: EMR clusters of this account and Region. No aws:ResourceTag condition: support for this
# action is UNVERIFIED, an unsupported key would fail closed and reproduce the refresh failure, and R5
# already describes every cluster through ReadOnlyAccess. Privilege delta: + one read action.
data "aws_iam_policy_document" "readonly_emr_extra" {
  statement {
    sid       = "ReadClusterAutoTerminationPolicy"
    actions   = ["elasticmapreduce:GetAutoTerminationPolicy"]
    resources = [local.emr_cluster_arns]
  }
}

resource "aws_iam_role_policy" "readonly_emr_extra" {
  name   = "scholarchain-readonly-emr-extra"
  role   = aws_iam_role.readonly.id
  policy = data.aws_iam_policy_document.readonly_emr_extra.json
}
