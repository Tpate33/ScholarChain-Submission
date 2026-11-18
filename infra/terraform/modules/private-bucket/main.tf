# Hardened private S3 bucket (owner: infra-engineer).
# Never public, encrypted (SSE-S3), versioned, TLS-only, bucket-owner-enforced, not force-destroyed.
# Callers add their own deny statements through additional_policy_documents.

terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
      # Callers pin the exact version (= 6.72.0); this module uses only v6-compatible arguments
      # (split aws_s3_bucket_* resources, no `region` argument).
      version = ">= 6.0.0, < 7.0.0"
    }
  }
}

variable "bucket_name" {
  description = "Globally unique bucket name (derived by the caller from account ID and Region)."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.bucket_name))
    error_message = "bucket_name must be a valid S3 bucket name."
  }
}

variable "noncurrent_version_expiration_days" {
  description = "Days after which noncurrent object versions expire."
  type        = number
}

variable "additional_policy_documents" {
  description = "Extra bucket-policy JSON documents merged with the TLS-only statement."
  type        = list(string)
}

resource "aws_s3_bucket" "this" {
  bucket = var.bucket_name
  # Deletion protection: a bucket that still holds objects or versions cannot be destroyed.
  force_destroy = false
}

resource "aws_s3_bucket_ownership_controls" "this" {
  bucket = aws_s3_bucket.this.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_public_access_block" "this" {
  bucket                  = aws_s3_bucket.this.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  bucket = aws_s3_bucket.this.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_versioning" "this" {
  bucket = aws_s3_bucket.this.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "this" {
  bucket = aws_s3_bucket.this.id

  rule {
    id     = "abort-incomplete-multipart-uploads"
    status = "Enabled"
    filter {}
    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }

  rule {
    id     = "expire-noncurrent-versions"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = var.noncurrent_version_expiration_days
    }
  }

  depends_on = [aws_s3_bucket_versioning.this]
}

data "aws_iam_policy_document" "this" {
  source_policy_documents = var.additional_policy_documents

  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.this.arn,
      "${aws_s3_bucket.this.arn}/*",
    ]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "this" {
  bucket = aws_s3_bucket.this.id
  policy = data.aws_iam_policy_document.this.json

  depends_on = [aws_s3_bucket_public_access_block.this]
}

output "id" {
  value = aws_s3_bucket.this.id
}

output "arn" {
  value = aws_s3_bucket.this.arn
}
