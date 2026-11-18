# State bootstrap (owner: infra-engineer). One Terraform state bucket per account and Region.
# Its own state is LOCAL and kept OUTSIDE the repository: scripts/plan.sh passes
#   -backend-config=path=$HOME/<bootstrap_state_home_relative from env.conf>
# The human keeps a copy of that file with the phase evidence archive (see ../README.md).

terraform {
  required_version = ">= 1.10.0, < 2.0.0"

  backend "local" {}

  required_providers {
    aws = {
      source = "hashicorp/aws"
      # 6.72.0: highest 6.x release seen verbatim (emr-docs-research-05.md, 2026-11-18); IE-P6-03.
      version = "= 6.72.0"
    }
  }
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

variable "environment" {
  type = string
}

variable "course" {
  type = string
}

variable "project" {
  type = string
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

module "state_bucket" {
  source = "../modules/private-bucket"

  # Must equal the name scripts/plan.sh derives: scholarchain-tfstate-<account>-<region>.
  bucket_name                        = "scholarchain-tfstate-${var.aws_account_id}-${var.aws_region}"
  noncurrent_version_expiration_days = 90
  additional_policy_documents        = []
}

output "state_bucket" {
  value = module.state_bucket.id
}
