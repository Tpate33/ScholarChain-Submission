locals {
  ecs_trust        = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Principal = { Service = "ecs-tasks.amazonaws.com" }, Action = "sts:AssumeRole" }] })
  table_arns       = [for t in aws_dynamodb_table.main : t.arn]
  table_index_arns = [for t in aws_dynamodb_table.main : "${t.arn}/index/*"]
  runtime_writable = toset(["EvidenceBundle", "QuerySession", "EntityLinkCache", "QueryOwnership", "QueryJob", "IdempotencyKey"])
  s3_objects       = concat([for z in var.zone_prefixes : "arn:aws:s3:::${var.data_bucket}/${z}*"], [for k in values(var.config_objects) : "arn:aws:s3:::${var.data_bucket}/${k}"])
  model_arns       = ["arn:aws:bedrock:us-east-1::foundation-model/amazon.nova-lite-v1:0", "arn:aws:bedrock:us-east-1::foundation-model/amazon.titan-embed-text-v2:0"]
}
resource "aws_iam_role" "execution" {
  for_each           = local.tasks
  name               = "${local.name}-${each.key}-exec"
  assume_role_policy = local.ecs_trust
}
resource "aws_iam_role_policy" "execution" {
  for_each = local.tasks
  role     = aws_iam_role.execution[each.key].id
  policy = jsonencode({ Version = "2012-10-17", Statement = concat([
    { Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = "*" },
    { Effect = "Allow", Action = ["ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"], Resource = [for r in aws_ecr_repository.main : r.arn] },
    { Effect = "Allow", Action = ["logs:CreateLogStream", "logs:PutLogEvents"], Resource = "${aws_cloudwatch_log_group.tasks.arn}:*" }
    ], each.key == "collector" ? [
    { Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = aws_secretsmanager_secret.main["benchmark-client"].arn },
    { Effect = "Allow", Action = ["kms:Decrypt"], Resource = var.kms_key_arn }
  ] : []) })
}
resource "aws_iam_role" "task" {
  for_each           = local.tasks
  name               = "${local.name}-${each.key}-task"
  assume_role_policy = local.ecs_trust
}
resource "aws_iam_role_policy" "task" {
  for_each = local.tasks
  role     = aws_iam_role.task[each.key].id
  policy = jsonencode({ Version = "2012-10-17", Statement = concat([
    { Effect = "Allow", Action = ["s3:GetObject"], Resource = local.s3_objects },
    { Effect = "Allow", Action = ["kms:Decrypt", "kms:GenerateDataKey"], Resource = var.kms_key_arn }
    ], each.key == "collector" ? [
    { Effect = "Allow", Action = ["s3:PutObject"], Resource = [for z in var.zone_prefixes : "arn:aws:s3:::${var.data_bucket}/${z}*"] }
    ] : each.key == "er" ? [
    { Effect = "Allow", Action = ["s3:PutObject"], Resource = [for z in var.zone_prefixes : "arn:aws:s3:::${var.data_bucket}/${z}*"] }
    ] : concat([
      { Effect = "Allow", Action = ["dynamodb:GetItem", "dynamodb:BatchGetItem", "dynamodb:Query"], Resource = concat(local.table_arns, local.table_index_arns) },
      { Effect = "Allow", Action = ["dynamodb:PutItem", "dynamodb:UpdateItem", "dynamodb:DeleteItem", "dynamodb:BatchWriteItem"], Resource = each.key == "loader" ? local.table_arns : [for k in local.runtime_writable : aws_dynamodb_table.main[k].arn] },
      { Effect = "Allow", Action = each.key == "loader" ? ["neptune-db:ReadDataViaQuery", "neptune-db:WriteDataViaQuery", "neptune-db:DeleteDataViaQuery", "neptune-db:GetEngineStatus"] : ["neptune-db:ReadDataViaQuery", "neptune-db:GetEngineStatus"], Resource = "arn:aws:neptune-db:us-east-1:${var.aws_account_id}:${aws_neptune_cluster.main.cluster_resource_id}/*" },
      { Effect = "Allow", Action = each.key == "loader" ? ["s3vectors:GetIndex", "s3vectors:GetVectors", "s3vectors:QueryVectors", "s3vectors:PutVectors"] : ["s3vectors:GetIndex", "s3vectors:GetVectors", "s3vectors:QueryVectors"], Resource = [for i in aws_s3vectors_index.main : i.arn] }
      ], contains(["query", "loader"], each.key) ? [
      { Effect = "Allow", Action = ["bedrock:InvokeModel"], Resource = each.key == "loader" ? [local.model_arns[1]] : local.model_arns }
      ] : [], contains(local.services, each.key) ? [
      { Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = aws_secretsmanager_secret.main["runtime-digests"].arn }
      ] : [], each.key == "loader" ? [
      { Effect = "Allow", Action = ["s3:PutObject"], Resource = [for z in var.zone_prefixes : "arn:aws:s3:::${var.data_bucket}/${z}*"] }
  ] : [])) })
}
