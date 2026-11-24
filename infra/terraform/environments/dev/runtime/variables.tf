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
}
variable "graph_experiment_deadline" {
  type = string
  validation {
    condition     = try(timecmp(var.graph_experiment_deadline, var.run_start) > 0 && timecmp(var.graph_experiment_deadline, var.runtime_deadline) <= 0, false)
    error_message = "Graph experiment must end inside the runtime window."
  }
}
