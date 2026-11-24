terraform {
  required_version = ">= 1.10.0, < 2.0.0"
  backend "s3" {
    key          = "scholarchain/dev/runtime/terraform.tfstate"
    encrypt      = true
    use_lockfile = true
  }
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "= 6.72.0"
    }
  }
}
provider "aws" {
  region              = "us-east-1"
  allowed_account_ids = [var.aws_account_id]
  default_tags {
    tags = { course = var.course, project = "scholarchain", environment = var.environment, owner = var.owner, expiration = var.expiration }
  }
}
locals {
  name     = "scholarchain-${var.environment}-bounded"
  services = toset(["query", "evidence", "graph"])
  tasks    = toset(["query", "evidence", "graph", "er", "loader", "collector"])
}
