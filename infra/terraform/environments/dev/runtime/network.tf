# Reuse approved inventory IDs; no remote state or account discovery during offline work.
resource "aws_subnet" "secondary" {
  vpc_id                  = var.vpc_id
  cidr_block              = var.second_subnet_cidr
  availability_zone_id    = var.second_az_id
  map_public_ip_on_launch = false
}
resource "aws_route_table_association" "secondary" {
  subnet_id      = aws_subnet.secondary.id
  route_table_id = var.private_route_table_id
}
resource "aws_security_group" "tasks" {
  name   = "${local.name}-tasks"
  vpc_id = var.vpc_id
}
resource "aws_security_group" "endpoints" {
  name   = "${local.name}-endpoints"
  vpc_id = var.vpc_id
}
resource "aws_security_group" "neptune" {
  name   = "${local.name}-neptune"
  vpc_id = var.vpc_id
}
resource "aws_vpc_security_group_ingress_rule" "http" {
  for_each                     = local.services
  security_group_id            = aws_security_group.tasks.id
  referenced_security_group_id = aws_security_group.tasks.id
  ip_protocol                  = "tcp"
  from_port                    = var.ports[each.key]
  to_port                      = var.ports[each.key]
}
resource "aws_vpc_security_group_egress_rule" "http" {
  for_each                     = local.services
  security_group_id            = aws_security_group.tasks.id
  referenced_security_group_id = aws_security_group.tasks.id
  ip_protocol                  = "tcp"
  from_port                    = var.ports[each.key]
  to_port                      = var.ports[each.key]
}
resource "aws_vpc_security_group_ingress_rule" "endpoint" {
  security_group_id            = aws_security_group.endpoints.id
  referenced_security_group_id = aws_security_group.tasks.id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
}
resource "aws_vpc_security_group_egress_rule" "endpoint" {
  security_group_id            = aws_security_group.tasks.id
  referenced_security_group_id = aws_security_group.endpoints.id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443
}
resource "aws_vpc_security_group_ingress_rule" "neptune" {
  security_group_id            = aws_security_group.neptune.id
  referenced_security_group_id = aws_security_group.tasks.id
  ip_protocol                  = "tcp"
  from_port                    = 8182
  to_port                      = 8182
}
resource "aws_vpc_security_group_egress_rule" "neptune" {
  security_group_id            = aws_security_group.tasks.id
  referenced_security_group_id = aws_security_group.neptune.id
  ip_protocol                  = "tcp"
  from_port                    = 8182
  to_port                      = 8182
}
resource "aws_vpc_security_group_egress_rule" "s3" {
  security_group_id = aws_security_group.tasks.id
  prefix_list_id    = var.s3_prefix_list_id
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}
resource "aws_vpc_security_group_egress_rule" "dynamodb" {
  security_group_id = aws_security_group.tasks.id
  prefix_list_id    = aws_vpc_endpoint.dynamodb.prefix_list_id
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}
resource "aws_vpc_endpoint" "runtime" {
  for_each            = toset(["ecr.api", "ecr.dkr", "logs", "bedrock-runtime", "secretsmanager", "s3vectors"])
  vpc_id              = var.vpc_id
  service_name        = "com.amazonaws.us-east-1.${each.key}"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = [var.private_subnet_id]
  security_group_ids  = [aws_security_group.endpoints.id]
  private_dns_enabled = true
}
resource "aws_vpc_endpoint" "dynamodb" {
  vpc_id            = var.vpc_id
  service_name      = "com.amazonaws.us-east-1.dynamodb"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [var.private_route_table_id]
}
