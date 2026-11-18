# One customer managed KMS key for EMR local-disk and EBS encryption, and the EMR security configuration.
# Chosen over the AWS managed aws/ebs key: explicit, reviewable key policy and no dependency on the
# unknown account-level EBS default-encryption setting. Cost: 1 USD per key-month prorated hourly, no
# charge while scheduled for deletion (verified, emr-docs-research-03.md). The aws/ebs alternative is
# cheaper by at most that amount; whether EMR accepts it for LocalDiskEncryption is UNVERIFIED.

data "aws_iam_policy_document" "emr_key" {
  # Standard account statement: key use is then governed by IAM policies (R1, R2 below).
  statement {
    sid       = "EnableIamPoliciesInThisAccount"
    effect    = "Allow"
    actions   = ["kms:*"]
    resources = ["*"]
    principals {
      type        = "AWS"
      identifiers = ["arn:aws:iam::${var.aws_account_id}:root"]
    }
  }
}

resource "aws_kms_key" "emr" {
  description             = "${local.name} EMR local disk and EBS encryption"
  deletion_window_in_days = 7
  enable_key_rotation     = true
  policy                  = data.aws_iam_policy_document.emr_key.json
}

resource "aws_kms_alias" "emr" {
  name          = "alias/${local.name}-emr"
  target_key_id = aws_kms_key.emr.key_id
}

# In-transit encryption between nodes is OFF (needs a certificate provider; accepted risk, emr-proposal.md
# section 4). S3 objects are SSE-S3 by bucket default; S3EncryptionConfiguration applies to EMRFS only.
resource "aws_emr_security_configuration" "main" {
  name = "${local.name}-emr"
  configuration = jsonencode({
    EncryptionConfiguration = {
      EnableInTransitEncryption = false
      EnableAtRestEncryption    = true
      AtRestEncryptionConfiguration = {
        S3EncryptionConfiguration = {
          EncryptionMode = "SSE-S3"
        }
        LocalDiskEncryptionConfiguration = {
          EncryptionKeyProviderType = "AwsKms"
          AwsKmsKey                 = aws_kms_key.emr.arn
          EnableEbsEncryption       = true
        }
      }
    }
  })
}
