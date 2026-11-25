output "runtime_inventory" {
  value = {
    cluster          = aws_ecs_cluster.main.arn
    task_definitions = { for k, t in aws_ecs_task_definition.main : k => t.arn }
    services         = { for k, s in aws_ecs_service.main : k => s.name }
    subnet           = var.private_subnet_id
    security_group   = aws_security_group.tasks.id
    neptune_writer   = aws_neptune_cluster.main.endpoint
    tables           = { for k, t in aws_dynamodb_table.main : k => t.name }
    vector_bucket    = aws_s3vectors_vector_bucket.main.vector_bucket_name
    secrets          = { for k, s in aws_secretsmanager_secret.main : k => s.arn }
    repositories     = { for k, r in aws_ecr_repository.main : k => r.repository_url }
    monitor          = aws_lambda_function.deadline.function_name
  }
}
# Copy query_base_url and evidence_base_url into ../smoke.conf (a reviewed commit) after apply. With
# HTTPS, the certificate names a custom domain, not the ALB DNS name: use that domain (pointed at
# alb_dns_name by the human) in smoke.conf instead.
output "api_endpoint" {
  value = {
    alb_dns_name      = aws_lb.api.dns_name
    scheme            = local.api_scheme
    query_base_url    = "${local.api_scheme}://${lower(aws_lb.api.dns_name)}:${local.api_listener_ports["query"]}"
    evidence_base_url = "${local.api_scheme}://${lower(aws_lb.api.dns_name)}:${local.api_listener_ports["evidence"]}"
  }
}
