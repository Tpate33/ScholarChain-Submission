locals {
  tables         = { "RunLedger" = ["byLoad"], "ArtifactRegistry" = ["bySha256"], "EvidenceBundle" = ["byQuery", "byEvidence"], "QuerySession" = [], "EntityLinkCache" = ["byResearcher"], "ResearcherMetricCache" = ["byGroup", "byPair", "rank"], "QueryOwnership" = [], "QueryJob" = [], "IdempotencyKey" = [] }
  ttl_tables     = toset(["QuerySession", "EntityLinkCache", "QueryOwnership", "QueryJob", "IdempotencyKey"])
  vector_indexes = toset(["researcher-profile-v1", "collaboration-community-v1", "collaboration-path-v1", "paper-abstract-v1", "topic-institution-summary-v1"])
}
resource "aws_dynamodb_table" "main" {
  for_each     = local.tables
  name         = "${local.name}-${each.key}"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "pk"
  range_key    = "sk"
  dynamic "attribute" {
    for_each = toset(concat(["pk", "sk"], flatten([for i in each.value : ["${i}Pk", "${i}Sk"]])))
    content {
      name = attribute.value
      type = "S"
    }
  }
  dynamic "global_secondary_index" {
    for_each = toset(each.value)
    content {
      name            = global_secondary_index.value
      hash_key        = "${global_secondary_index.value}Pk"
      range_key       = "${global_secondary_index.value}Sk"
      projection_type = "ALL"
    }
  }
  dynamic "ttl" {
    for_each = contains(local.ttl_tables, each.key) ? [1] : []
    content {
      attribute_name = "expiresAt"
      enabled        = true
    }
  }
  server_side_encryption {
    enabled = true
  }
}
resource "aws_s3vectors_vector_bucket" "main" {
  vector_bucket_name = "${local.name}-${var.aws_account_id}"
  force_destroy      = false
  encryption_configuration {
    sse_type = "AES256"
  }
}
resource "aws_s3vectors_index" "main" {
  for_each           = local.vector_indexes
  index_name         = each.key
  vector_bucket_name = aws_s3vectors_vector_bucket.main.vector_bucket_name
  data_type          = "float32"
  dimension          = 256
  distance_metric    = "cosine"
}
# Empty resources only. Humans populate versions outside Terraform, without command-line secrets.
resource "aws_secretsmanager_secret" "main" {
  for_each                = toset(["runtime-digests", "benchmark-client"])
  name                    = "${local.name}-${each.key}"
  kms_key_id              = var.kms_key_arn
  recovery_window_in_days = 7
}
resource "aws_neptune_subnet_group" "main" {
  name       = local.name
  subnet_ids = [var.private_subnet_id, aws_subnet.secondary.id]
}
resource "aws_neptune_cluster" "main" {
  cluster_identifier                  = local.name
  engine                              = "neptune"
  storage_encrypted                   = true
  kms_key_arn                         = var.kms_key_arn
  iam_database_authentication_enabled = true
  neptune_subnet_group_name           = aws_neptune_subnet_group.main.name
  vpc_security_group_ids              = [aws_security_group.neptune.id]
  backup_retention_period             = 1
  skip_final_snapshot                 = true
  deletion_protection                 = false
  apply_immediately                   = true
}
resource "aws_neptune_cluster_instance" "writer" {
  identifier                = "${local.name}-writer"
  cluster_identifier        = aws_neptune_cluster.main.id
  instance_class            = "db.t4g.medium"
  engine                    = "neptune"
  publicly_accessible       = false
  neptune_subnet_group_name = aws_neptune_subnet_group.main.name
  apply_immediately         = true
}
