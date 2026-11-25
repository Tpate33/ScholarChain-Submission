variable "aws_account_id" {
  type = string
}
variable "environment" {
  type = string
}
variable "course" {
  type = string
}
variable "owner" {
  type = string
}
variable "expiration" {
  type = string
}
variable "vpc_id" {
  type = string
}
variable "private_subnet_id" {
  type = string
}
variable "private_route_table_id" {
  type = string
}
variable "s3_prefix_list_id" {
  type = string
}
variable "second_subnet_cidr" {
  type = string
}
variable "second_az_id" {
  type = string
}
# AZ ID of base's private subnet (TF_VAR_availability_zone_id from env.conf); the first public (ALB)
# subnet is placed here, the second in second_az_id.
variable "availability_zone_id" {
  type = string
  validation {
    condition     = can(regex("^[a-z]{3,4}[0-9]-az[0-9]+$", var.availability_zone_id))
    error_message = "availability_zone_id must be an AZ ID such as use1-az4."
  }
}
# Two public subnets for the internet-facing API load balancer (an ALB needs two AZs). Each /24../27
# (an ALB subnet needs at least a /27), canonical, distinct, inside the base VPC CIDR and not overlapping
# the private subnets (overlap and VPC containment are checked by AWS at apply, not offline).
variable "public_subnet_cidrs" {
  type = list(string)
  validation {
    condition = length(var.public_subnet_cidrs) == 2 && length(distinct(var.public_subnet_cidrs)) == 2 && alltrue([
      for c in var.public_subnet_cidrs : can(regex("^([0-9]{1,3}\\.){3}[0-9]{1,3}/(2[4-7])$", c)) && try(cidrhost(c, 0) == split("/", c)[0], false)
    ])
    error_message = "Exactly two distinct canonical IPv4 CIDRs /24../27 are required."
  }
}
# Comma-separated IPv4 CIDRs allowed to reach the API load balancer (TF_VAR_api_ingress_cidrs from the
# human-local env.local.conf). No default. 1 to 4 entries, each canonical and /24../32: 0.0.0.0/0 and
# every other wide range are refused.
variable "api_ingress_cidrs" {
  type = string
  validation {
    condition = length(split(",", var.api_ingress_cidrs)) <= 4 && length(distinct(split(",", var.api_ingress_cidrs))) == length(split(",", var.api_ingress_cidrs)) && alltrue([
      for c in split(",", var.api_ingress_cidrs) : can(regex("^([0-9]{1,3}\\.){3}[0-9]{1,3}/(2[4-9]|3[0-2])$", c)) && try(cidrhost(c, 0) == split("/", c)[0], false)
    ])
    error_message = "api_ingress_cidrs must be 1 to 4 distinct comma-separated canonical IPv4 CIDRs, each /24../32 (never 0.0.0.0/0)."
  }
}
# ACM certificate ARN for HTTPS listeners, or exactly "none" for HTTP listeners on non-default ports
# (TF_VAR_api_certificate_arn from env.local.conf). No default.
variable "api_certificate_arn" {
  type = string
  validation {
    condition     = var.api_certificate_arn == "none" || can(regex("^arn:aws:acm:us-east-1:[0-9]{12}:certificate/[0-9a-f-]{36}$", var.api_certificate_arn))
    error_message = "api_certificate_arn must be \"none\" or an ACM certificate ARN in us-east-1."
  }
}
variable "kms_key_arn" {
  type = string
}
variable "data_bucket" {
  type = string
}
variable "logs_bucket" {
  type = string
}
variable "notification_email" {
  type = string
}
variable "runtime_deadline" {
  type = string
  validation {
    condition     = can(timecmp(var.runtime_deadline, "2026-11-26T05:00:00Z")) && try(timecmp(var.runtime_deadline, "2026-11-26T05:00:00Z") <= 0, false)
    error_message = "Deadline must be RFC3339 at or before November 25 23:00 CST."
  }
}
variable "emr_cluster_id" {
  type = string
}
variable "emr_deadline" {
  type = string
}
variable "runtime_image" {
  type = string
  validation {
    condition     = can(regex("^[0-9]{12}\\.dkr\\.ecr\\.us-east-1\\.amazonaws\\.com/[a-z0-9/-]+@sha256:[0-9a-f]{64}$", var.runtime_image))
    error_message = "Require immutable ECR digest in us-east-1."
  }
}
variable "worker_image" {
  type = string
  validation {
    condition     = can(regex("^[0-9]{12}\\.dkr\\.ecr\\.us-east-1\\.amazonaws\\.com/[a-z0-9/-]+@sha256:[0-9a-f]{64}$", var.worker_image))
    error_message = "Require immutable ECR digest in us-east-1."
  }
}
variable "collector_image" {
  type = string
  validation {
    condition     = can(regex("^[0-9]{12}\\.dkr\\.ecr\\.us-east-1\\.amazonaws\\.com/[a-z0-9/-]+@sha256:[0-9a-f]{64}$", var.collector_image))
    error_message = "Require immutable ECR digest in us-east-1."
  }
}
variable "commands" {
  type = map(list(string))
  validation {
    condition     = alltrue([for k in ["query", "evidence", "graph", "er", "loader", "collector"] : try(length(var.commands[k]) == 1, false)])
    error_message = "Each of six tasks requires exactly one reviewed shell invocation without secret values."
  }
}
variable "config_files" {
  type = map(string)
}
variable "config_objects" {
  type = map(string)
}
variable "ports" {
  type = map(number)
}
variable "zone_prefixes" {
  type = set(string)
  validation {
    condition     = length(var.zone_prefixes) == 6 && alltrue([for x in var.zone_prefixes : can(regex("^[a-z][a-z0-9/-]+/$", x)) && !strcontains(x, "..")])
    error_message = "Exactly six explicit relative zone prefixes ending in slash are required."
  }
}
variable "run_start" {
  type = string
  validation {
    condition     = try(timecmp(var.runtime_deadline, timeadd(var.run_start, "2h")) <= 0 && timecmp(var.runtime_deadline, var.run_start) > 0 && timecmp(var.emr_deadline, timeadd(var.run_start, "2h")) <= 0 && timecmp(var.emr_deadline, var.run_start) > 0, false)
    error_message = "Runtime and EMR deadlines must fit the fixed 120-minute admission window."
  }
}
variable "run_id" {
  type = string
  validation {
    condition     = can(regex("^[a-z0-9-]{1,48}$", var.run_id))
    error_message = "Bounded run identifier required."
  }
}
variable "materialization_manifests" {
  description = "Literal nonsecret scholarchain.s3-materialize.v1 JSON for each task. Exact keys, hashes and byte counts only."
  type        = map(string)
  validation {
    condition     = alltrue([for k in ["query", "evidence", "graph", "er", "loader", "collector"] : try(jsondecode(var.materialization_manifests[k]).schemaVersion == "scholarchain.s3-materialize.v1" && jsondecode(var.materialization_manifests[k]).retryMaxAttempts == 1, false)])
    error_message = "All six tasks require a one-attempt materialization manifest."
  }
  # CR-P13-04 m1: the committed file names the data bucket only as the token @DATA_BUCKET@ (compute.tf
  # replaces it with var.data_bucket); no account ID and no ARN.
  validation {
    condition     = alltrue([for v in values(var.materialization_manifests) : !strcontains(v, "arn:") && !strcontains(v, var.aws_account_id)])
    error_message = "materialization_manifests must not contain an ARN or the account ID; write the data bucket as @DATA_BUCKET@."
  }
}
variable "graph_experiment_deadline" {
  type = string
  validation {
    condition     = try(timecmp(var.graph_experiment_deadline, var.run_start) > 0 && timecmp(var.graph_experiment_deadline, var.runtime_deadline) <= 0, false)
    error_message = "Graph experiment must end inside the runtime window."
  }
}
