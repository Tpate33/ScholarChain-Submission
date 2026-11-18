# No variable has a default. Account values: ../env.conf via scripts/plan.sh (TF_VAR_*);
# stack values: terraform.tfvars. A REPLACE-ME placeholder fails validation, so plan refuses.

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

variable "state_bucket" {
  description = "Terraform state bucket (scholarchain-tfstate-<account>-<region>), derived by plan.sh."
  type        = string
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

variable "emr_release_label" {
  type = string
  validation {
    condition     = var.emr_release_label == "emr-7.14.0"
    error_message = "Only emr-7.14.0 is approved (Option A)."
  }
}

variable "instance_type" {
  type = string
  validation {
    condition     = contains(["m5.xlarge", "m6i.xlarge"], var.instance_type)
    error_message = "instance_type must be m5.xlarge or m6i.xlarge."
  }
}

variable "root_volume_gib" {
  type = number
  validation {
    condition     = var.root_volume_gib >= 15 && var.root_volume_gib <= 20
    error_message = "root_volume_gib must be 15-20."
  }
}

variable "data_volume_gib" {
  type = number
  validation {
    condition     = var.data_volume_gib >= 8 && var.data_volume_gib <= 64
    error_message = "data_volume_gib must be 8-64."
  }
}

variable "idle_timeout_seconds" {
  type = number
  validation {
    condition     = var.idle_timeout_seconds >= 300 && var.idle_timeout_seconds <= 1800
    error_message = "idle_timeout_seconds must be 300-1800."
  }
}

variable "install_script_sha256" {
  description = "sha256 of the committed scripts/emr/install-spark-dist.sh (content-addressed S3 key)."
  type        = string
  validation {
    condition     = can(regex("^[0-9a-f]{64}$", var.install_script_sha256))
    error_message = "install_script_sha256 must be 64 lowercase hex."
  }
}

variable "submit_script_sha256" {
  description = "sha256 of the committed scripts/emr/submit-collaboration-job.sh."
  type        = string
  validation {
    condition     = can(regex("^[0-9a-f]{64}$", var.submit_script_sha256))
    error_message = "submit_script_sha256 must be 64 lowercase hex."
  }
}

variable "spark_dist_sha512" {
  type = string
  validation {
    condition     = can(regex("^[0-9a-f]{128}$", var.spark_dist_sha512))
    error_message = "spark_dist_sha512 must be 128 lowercase hex."
  }
}

variable "scala_library_sha256" {
  type = string
  validation {
    condition     = can(regex("^[0-9a-f]{64}$", var.scala_library_sha256))
    error_message = "scala_library_sha256 must be 64 lowercase hex."
  }
}

variable "hadoop_aws_sha256" {
  type = string
  validation {
    condition     = can(regex("^[0-9a-f]{64}$", var.hadoop_aws_sha256))
    error_message = "hadoop_aws_sha256 must be 64 lowercase hex."
  }
}

variable "hadoop_aws_sha1" {
  type = string
  validation {
    condition     = can(regex("^[0-9a-f]{40}$", var.hadoop_aws_sha1))
    error_message = "hadoop_aws_sha1 must be 40 lowercase hex."
  }
}

variable "sdk_bundle_sha256" {
  type = string
  validation {
    condition     = can(regex("^[0-9a-f]{64}$", var.sdk_bundle_sha256))
    error_message = "sdk_bundle_sha256 must be 64 lowercase hex."
  }
}
