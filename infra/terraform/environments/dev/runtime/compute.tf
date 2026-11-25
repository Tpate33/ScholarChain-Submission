resource "aws_ecr_repository" "main" {
  for_each             = toset(["runtime", "worker"])
  name                 = "${local.name}/${each.key}"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = true
  encryption_configuration {
    encryption_type = "AES256"
  }
}
resource "aws_cloudwatch_log_group" "tasks" {
  name              = "/scholarchain/${var.environment}/bounded"
  retention_in_days = 1
}
resource "aws_ecs_cluster" "main" {
  name = local.name
  setting {
    name  = "containerInsights"
    value = "disabled"
  }
}
resource "aws_service_discovery_private_dns_namespace" "main" {
  name = "${local.name}.internal"
  vpc  = var.vpc_id
}
resource "aws_service_discovery_service" "main" {
  for_each = local.services
  name     = each.key
  dns_config {
    namespace_id = aws_service_discovery_private_dns_namespace.main.id
    dns_records {
      ttl  = 10
      type = "A"
    }
    routing_policy = "MULTIVALUE"
  }
}
# CR-P13-04 m1: the committed terraform.tfvars never names the account-bearing data bucket. Its
# materialization manifests carry the literal token @DATA_BUCKET@, replaced here by var.data_bucket
# (exported by scripts/lib/common.sh tf_env, derived like base).
locals {
  materialization_manifests = { for k, v in var.materialization_manifests : k => replace(v, "@DATA_BUCKET@", var.data_bucket) }
}
resource "aws_ecs_task_definition" "main" {
  for_each                 = local.tasks
  family                   = "${local.name}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = contains(["er", "loader"], each.key) ? "1024" : "256"
  memory                   = contains(["er", "loader"], each.key) ? "4096" : (each.key == "collector" ? "512" : "1024")
  execution_role_arn       = aws_iam_role.execution[each.key].arn
  task_role_arn            = aws_iam_role.task[each.key].arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name      = each.key
    image     = contains(["er", "loader"], each.key) ? var.worker_image : (each.key == "collector" ? var.collector_image : var.runtime_image)
    essential = true
    # Entrypoints/config must be tested against the immutable portable image before approval.
    entryPoint  = ["/bin/sh", "-c"]
    command     = [contains(["er", "loader"], each.key) ? "sh /opt/scholarchain-worker/bin/s3-materialize --manifest-json \"$SCHOLARCHAIN_MATERIALIZE_MANIFEST\" --root /work --execute \"$SCHOLARCHAIN_RUN_ID\" && exec ${var.commands[each.key][0]}" : "java -cp '/opt/docker/lib/*' scholarchain.storage.local.S3MaterializeMain --manifest-json \"$SCHOLARCHAIN_MATERIALIZE_MANIFEST\" --root /work --execute \"$SCHOLARCHAIN_RUN_ID\" && exec ${var.commands[each.key][0]}"]
    stopTimeout = 30
    environment = [
      { name = "SCHOLARCHAIN_CONFIG_FILES", value = var.config_files[each.key] },
      { name = "SCHOLARCHAIN_MATERIALIZE_MANIFEST", value = local.materialization_manifests[each.key] },
      { name = "SCHOLARCHAIN_RUN_ID", value = var.run_id },
      { name = "AWS_REGION", value = "us-east-1" },
      { name = "AWS_MAX_ATTEMPTS", value = "1" },
      { name = "SCHOLARCHAIN_DEADLINE", value = var.runtime_deadline }
    ]
    portMappings = contains(local.services, each.key) ? [{ containerPort = var.ports[each.key], protocol = "tcp" }] : []
    logConfiguration = {
      logDriver = "awslogs"
      options   = { awslogs-group = aws_cloudwatch_log_group.tasks.name, awslogs-region = "us-east-1", awslogs-stream-prefix = each.key }
    }
    secrets          = each.key == "collector" ? [{ name = "SCHOLARCHAIN_BENCHMARK_KEY", valueFrom = aws_secretsmanager_secret.main["benchmark-client"].arn }] : []
    environmentFiles = []
  }])
}
resource "aws_ecs_service" "main" {
  for_each        = local.services
  name            = "${local.name}-${each.key}"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.main[each.key].arn
  # No workload is admitted by apply. The separately approved run controller starts services.
  desired_count                      = 0
  launch_type                        = "FARGATE"
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100
  deployment_circuit_breaker {
    enable   = true
    rollback = false
  }
  network_configuration {
    subnets          = [var.private_subnet_id]
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = false
  }
  service_registries {
    registry_arn = aws_service_discovery_service.main[each.key].arn
  }
  # query and evidence register with the API load balancer (alb.tf); graph stays Cloud Map only.
  dynamic "load_balancer" {
    for_each = contains(local.api_services, each.key) ? [each.key] : []
    content {
      target_group_arn = aws_lb_target_group.api[load_balancer.value].arn
      container_name   = load_balancer.value
      container_port   = var.ports[load_balancer.value]
    }
  }
  health_check_grace_period_seconds = contains(local.api_services, each.key) ? 120 : null
  lifecycle {
    ignore_changes = [desired_count]
  }
  # The target groups must be attached to the load balancer before a service registers with them.
  depends_on = [aws_lb_listener.api]
}
resource "aws_appautoscaling_target" "graph" {
  max_capacity       = 2
  min_capacity       = 0
  resource_id        = "service/${aws_ecs_cluster.main.name}/${aws_ecs_service.main["graph"].name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
  # Suspended until the run controller opens the approved graph experiment window.
  suspended_state {
    dynamic_scaling_in_suspended  = true
    dynamic_scaling_out_suspended = true
    scheduled_scaling_suspended   = true
  }
  lifecycle {
    ignore_changes = [suspended_state]
  }
}
resource "aws_appautoscaling_policy" "graph" {
  for_each           = { out = 1, in = -1 }
  name               = "${local.name}-${each.key}"
  policy_type        = "StepScaling"
  resource_id        = aws_appautoscaling_target.graph.resource_id
  scalable_dimension = aws_appautoscaling_target.graph.scalable_dimension
  service_namespace  = aws_appautoscaling_target.graph.service_namespace
  step_scaling_policy_configuration {
    adjustment_type         = "ChangeInCapacity"
    cooldown                = 60
    metric_aggregation_type = "Average"
    step_adjustment {
      metric_interval_lower_bound = each.key == "out" ? 0 : null
      metric_interval_upper_bound = each.key == "in" ? 0 : null
      scaling_adjustment          = each.value
    }
  }
}
resource "aws_cloudwatch_metric_alarm" "graph" {
  for_each            = { out = 45, in = 15 }
  alarm_name          = "${local.name}-cpu-${each.key}"
  namespace           = "AWS/ECS"
  metric_name         = "CPUUtilization"
  dimensions          = { ClusterName = aws_ecs_cluster.main.name, ServiceName = aws_ecs_service.main["graph"].name }
  statistic           = "Average"
  period              = 60
  evaluation_periods  = each.key == "out" ? 2 : 3
  threshold           = each.value
  comparison_operator = each.key == "out" ? "GreaterThanThreshold" : "LessThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_appautoscaling_policy.graph[each.key].arn]
}
resource "aws_cloudwatch_dashboard" "main" {
  dashboard_name = local.name
  dashboard_body = jsonencode({ widgets = [{ type = "metric", x = 0, y = 0, width = 12, height = 6, properties = {
    region  = "us-east-1", title = "Graph CPU (native ECS)", period = 60, stat = "Average",
    metrics = [["AWS/ECS", "CPUUtilization", "ClusterName", aws_ecs_cluster.main.name, "ServiceName", aws_ecs_service.main["graph"].name]]
  } }] })
}
