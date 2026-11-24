resource "aws_sns_topic" "deadline" {
  name = "${local.name}-deadline"
}
resource "aws_sns_topic_subscription" "human" {
  topic_arn = aws_sns_topic.deadline.arn
  protocol  = "email"
  endpoint  = var.notification_email
}
resource "aws_iam_role" "monitor" {
  name               = "${local.name}-monitor"
  assume_role_policy = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Principal = { Service = "lambda.amazonaws.com" }, Action = "sts:AssumeRole" }] })
}
resource "aws_cloudwatch_log_group" "monitor" {
  name              = "/aws/lambda/${local.name}-deadline"
  retention_in_days = 1
}
resource "aws_iam_role_policy" "monitor" {
  role = aws_iam_role.monitor.id
  policy = jsonencode({ Version = "2012-10-17", Statement = concat([
    { Effect = "Allow", Action = ["ecs:ListTasks", "ecs:DescribeTasks", "ecs:StopTask"], Resource = "*", Condition = { ArnEquals = { "ecs:cluster" = aws_ecs_cluster.main.arn } } },
    { Effect = "Allow", Action = ["ecs:UpdateService"], Resource = [for s in aws_ecs_service.main : s.id] },
    { Effect = "Allow", Action = ["application-autoscaling:RegisterScalableTarget"], Resource = "*", Condition = { StringEquals = { "application-autoscaling:service-namespace" = "ecs" } } },
    { Effect = "Allow", Action = ["sns:Publish"], Resource = aws_sns_topic.deadline.arn },
    { Effect = "Allow", Action = ["logs:CreateLogStream", "logs:PutLogEvents"], Resource = "${aws_cloudwatch_log_group.monitor.arn}:*" }
    ], var.emr_cluster_id == "" ? [] : [
    { Effect = "Allow", Action = ["elasticmapreduce:DescribeCluster", "elasticmapreduce:ListSteps", "elasticmapreduce:TerminateJobFlows"], Resource = "arn:aws:elasticmapreduce:us-east-1:${var.aws_account_id}:cluster/${var.emr_cluster_id}" }
  ]) })
}
resource "aws_lambda_function" "deadline" {
  function_name                  = "${local.name}-deadline"
  role                           = aws_iam_role.monitor.arn
  runtime                        = "python3.12"
  handler                        = "deadline_monitor.handler"
  filename                       = "${path.module}/deadline-monitor.zip"
  source_code_hash               = filebase64sha256("${path.module}/deadline-monitor.zip")
  timeout                        = 60
  reserved_concurrent_executions = 1
  environment {
    variables = { CONTROL = jsonencode({
      cluster        = aws_ecs_cluster.main.arn,
      services       = [for s in aws_ecs_service.main : s.name],
      graph_resource = aws_appautoscaling_target.graph.resource_id,
      families       = { for k in local.tasks : aws_ecs_task_definition.main[k].family => k },
      deadline       = var.runtime_deadline, graph_deadline = var.graph_experiment_deadline, graph_service = aws_ecs_service.main["graph"].name, emr_cluster_id = var.emr_cluster_id,
      emr_deadline   = var.emr_deadline, topic = aws_sns_topic.deadline.arn
    }) }
  }
  depends_on = [aws_cloudwatch_log_group.monitor, aws_iam_role_policy.monitor]
}
resource "aws_cloudwatch_event_rule" "deadline" {
  name                = "${local.name}-deadline"
  schedule_expression = "rate(1 minute)"
}
resource "aws_cloudwatch_event_target" "deadline" {
  rule = aws_cloudwatch_event_rule.deadline.name
  arn  = aws_lambda_function.deadline.arn
  retry_policy {
    maximum_retry_attempts       = 0
    maximum_event_age_in_seconds = 60
  }
}
resource "aws_lambda_permission" "deadline" {
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.deadline.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.deadline.arn
}
resource "aws_lambda_function_event_invoke_config" "deadline" {
  function_name                = aws_lambda_function.deadline.function_name
  maximum_retry_attempts       = 0
  maximum_event_age_in_seconds = 60
}
resource "aws_cloudwatch_metric_alarm" "monitor_failure" {
  alarm_name          = "${local.name}-monitor-failed"
  namespace           = "AWS/Lambda"
  metric_name         = "Errors"
  dimensions          = { FunctionName = aws_lambda_function.deadline.function_name }
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.deadline.arn]
}
