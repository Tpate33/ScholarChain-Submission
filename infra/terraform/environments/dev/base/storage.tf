# Data bucket (artifacts, input, config, output/spark, submissions) and logs bucket (emr/, spark-events/).
# Prefix layout (content-addressed where possible; written by scripts/run-bounded-spark.sh --stage):
#   artifacts/spark-dist/<sha512>/spark-3.5.5-bin-hadoop3-scala2.13.tgz
#   artifacts/runtime-jars/<sha256>/<jar>          artifacts/emr-scripts/<sha256>/<script>
#   artifacts/job/<40-hex code version>/scholarchain-spark-jobs-0.1.0.jar
#   config/<sha256>/<file>                         config/input-trees/<sha256>/input-tree.tsv
#   input/<input name>/<archive tree>              submissions/<submission id>/{run-record,run-summary}.json
#   output/spark/                                  (job output root: run=<hex>/, load=<hex>/, .spark-<hex>/)

locals {
  data_bucket_name = "${local.name}-data-${var.aws_account_id}-${var.aws_region}"
  logs_bucket_name = "${local.name}-logs-${var.aws_account_id}-${var.aws_region}"
  data_bucket_arn  = "arn:aws:s3:::${local.data_bucket_name}"
  logs_bucket_arn  = "arn:aws:s3:::${local.logs_bucket_name}"

  data_protected_object_arns = [
    "${local.data_bucket_arn}/input*",
    "${local.data_bucket_arn}/artifacts/*",
    "${local.data_bucket_arn}/config/*",
    "${local.data_bucket_arn}/submissions/*",
    "${local.data_bucket_arn}/output/spark/run=*",
    "${local.data_bucket_arn}/output/spark/load=*",
  ]
}

# Nobody but the human's IAM user (admin_principal_arn, signed in with `aws login`) may delete
# protected objects or any object version.
data "aws_iam_policy_document" "data_bucket_extra" {
  statement {
    sid       = "DenyDeleteProtectedPrefixesExceptAdmin"
    effect    = "Deny"
    actions   = ["s3:DeleteObject", "s3:DeleteObjectVersion"]
    resources = local.data_protected_object_arns
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "ArnNotEquals"
      variable = "aws:PrincipalArn"
      values   = [local.admin_principal_arn]
    }
  }

  statement {
    sid       = "DenyDeleteObjectVersionExceptAdmin"
    effect    = "Deny"
    actions   = ["s3:DeleteObjectVersion"]
    resources = ["${local.data_bucket_arn}/*"]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "ArnNotEquals"
      variable = "aws:PrincipalArn"
      values   = [local.admin_principal_arn]
    }
  }
}

data "aws_iam_policy_document" "logs_bucket_extra" {
  statement {
    sid       = "DenyDeleteExceptAdmin"
    effect    = "Deny"
    actions   = ["s3:DeleteObject", "s3:DeleteObjectVersion"]
    resources = ["${local.logs_bucket_arn}/*"]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "ArnNotEquals"
      variable = "aws:PrincipalArn"
      values   = [local.admin_principal_arn]
    }
  }
}

module "data_bucket" {
  source = "../../../modules/private-bucket"

  bucket_name                        = local.data_bucket_name
  noncurrent_version_expiration_days = var.noncurrent_version_expiration_days
  additional_policy_documents        = [data.aws_iam_policy_document.data_bucket_extra.json]
}

module "logs_bucket" {
  source = "../../../modules/private-bucket"

  bucket_name                        = local.logs_bucket_name
  noncurrent_version_expiration_days = var.noncurrent_version_expiration_days
  additional_policy_documents        = [data.aws_iam_policy_document.logs_bucket_extra.json]
}
