# Stack `base` of environment `dev` (owner: infra-engineer). Persistent, no compute.
# The environment name `dev` is a PLACEHOLDER pending ratification of the environment list.
#
# Inputs: account-specific values come from ../env.conf, exported by scripts/plan.sh as TF_VAR_*;
# stack settings come from terraform.tfvars in this directory. No variable has a default.
# Backend: partial S3 configuration; plan.sh supplies bucket and region with -backend-config.

terraform {
  required_version = ">= 1.10.0, < 2.0.0"

  backend "s3" {
    key          = "scholarchain/dev/base/terraform.tfstate"
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
    tags = local.required_tags
  }
}

locals {
  name = "${var.project}-${var.environment}"
  required_tags = {
    course      = var.course
    project     = var.project
    environment = var.environment
    owner       = var.owner
    expiration  = var.expiration
  }
  # AmazonEMRServicePolicy_v2 acts only on network resources carrying this tag.
  emr_managed_tag = { "for-use-with-amazon-emr-managed-policies" = "true" }
  # The human's IAM user (console password + MFA, no access keys; CLI through `aws login`): the
  # deployer (R4) and the only principal that may assume the operator and read-only roles or delete
  # protected objects. No IAM Identity Center, no AWS Organizations (human decision 2026-11-17).
  admin_principal_arn = var.admin_principal_arn
}

variable "aws_account_id" {
  type = string
  validation {
    condition     = can(regex("^[0-9]{12}$", var.aws_account_id))
    error_message = "aws_account_id must be 12 digits."
  }
}

variable "aws_region" {
  type = string
  validation {
    condition     = can(regex("^[a-z]{2}(-[a-z]+)+-[0-9]$", var.aws_region))
    error_message = "aws_region must be a Region code."
  }
}

variable "availability_zone_id" {
  description = "AZ ID (for example use1-az4) that offers the EMR instance type; from describe-instance-type-offerings."
  type        = string
  validation {
    condition     = can(regex("^[a-z0-9]+-az[0-9]+$", var.availability_zone_id))
    error_message = "availability_zone_id must be an AZ ID such as use1-az4."
  }
}

variable "environment" {
  type = string
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,15}$", var.environment))
    error_message = "environment must be a short lowercase name."
  }
}

variable "admin_principal_arn" {
  description = "ARN of the human's IAM user (arn:aws:iam::<account>:user/<name>), from env.conf; no default."
  type        = string
  validation {
    condition     = can(regex("^arn:aws:iam::[0-9]{12}:user/[A-Za-z0-9._-]{1,64}$", var.admin_principal_arn))
    error_message = "admin_principal_arn must be an IAM user ARN arn:aws:iam::<12 digits>:user/<name> (REPLACE-ME is refused)."
  }
  validation {
    condition     = try(split(":", var.admin_principal_arn)[4], "") == var.aws_account_id
    error_message = "admin_principal_arn must be in aws_account_id."
  }
}

variable "course" {
  type = string
}

variable "project" {
  type = string
  validation {
    condition     = var.project == "scholarchain"
    error_message = "project must be scholarchain (role and bucket names derive from it)."
  }
}

variable "owner" {
  type = string
}

variable "expiration" {
  type = string
  validation {
    condition     = can(regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$", var.expiration))
    error_message = "expiration must be YYYY-MM-DD."
  }
}

variable "vpc_cidr" {
  type = string
}

variable "private_subnet_cidr" {
  type = string
}

variable "noncurrent_version_expiration_days" {
  type = number
}
