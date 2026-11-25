# Internet-facing API load balancer for the smoke test and the demo (IE-P13-02, human decision
# 2026-11-24). Only the query and evidence services are exposed; graph stays private (Cloud Map only).
#
#   human CIDR(s) --(listener port, SG alb)--> ALB in two public subnets --(container port, SG tasks)-->
#   Fargate tasks in the private subnet (no public IP)
#
# - Ingress: only var.api_ingress_cidrs (from the untracked env.local.conf; /24../32, never 0.0.0.0/0),
#   only on the two listener ports. ALB egress: only to the tasks security group on the two container
#   ports. Tasks admit the ALB group on those ports only.
# - HTTPS (443 query, 8443 evidence) when var.api_certificate_arn names an ACM certificate; otherwise
#   HTTP on the non-default ports 8080 (query) and 8081 (evidence). Over HTTP the service API key (the
#   Bearer token) crosses the internet in clear text: acceptable only for a short, CIDR-restricted dev
#   demo; rotate the key afterwards (see README.md).
# - Networking added: one internet gateway on the base VPC and two public subnets with their own route
#   table (default route to the gateway). The private route table is unchanged: tasks, Neptune and EMR
#   still have no internet route. No NAT gateway.
# - Target group health check: GET /healthz (liveness) on the container port. Readiness (/readyz) is
#   checked by scripts/smoke-test.sh through the listener; using /readyz here would make ECS replace
#   tasks whenever a dependency is briefly unready.
locals {
  api_services       = toset(["query", "evidence"])
  api_https          = var.api_certificate_arn != "none"
  api_scheme         = local.api_https ? "https" : "http"
  api_listener_ports = local.api_https ? { query = 443, evidence = 8443 } : { query = 8080, evidence = 8081 }
  api_ingress = { for p in setproduct(sort(split(",", var.api_ingress_cidrs)), sort(local.api_services)) :
    "${p[1]}-${p[0]}" => { cidr = p[0], service = p[1] }
  }
  public_subnets = {
    a = { cidr = var.public_subnet_cidrs[0], az_id = var.availability_zone_id }
    b = { cidr = var.public_subnet_cidrs[1], az_id = var.second_az_id }
  }
}

resource "aws_internet_gateway" "api" {
  vpc_id = var.vpc_id
  tags   = { Name = "${local.name}-api-igw" }
}

resource "aws_subnet" "public" {
  for_each                = local.public_subnets
  vpc_id                  = var.vpc_id
  cidr_block              = each.value.cidr
  availability_zone_id    = each.value.az_id
  map_public_ip_on_launch = false
  tags                    = { Name = "${local.name}-public-${each.key}" }
  lifecycle {
    precondition {
      condition     = var.availability_zone_id != var.second_az_id
      error_message = "The two public subnets need two different AZ IDs (availability_zone_id and second_az_id)."
    }
    precondition {
      condition     = !contains(var.public_subnet_cidrs, var.second_subnet_cidr)
      error_message = "A public subnet CIDR equals second_subnet_cidr."
    }
  }
}

resource "aws_route_table" "public" {
  vpc_id = var.vpc_id
  tags   = { Name = "${local.name}-public" }
}

resource "aws_route" "public_default" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.api.id
}

resource "aws_route_table_association" "public" {
  for_each       = aws_subnet.public
  subnet_id      = each.value.id
  route_table_id = aws_route_table.public.id
}

resource "aws_security_group" "alb" {
  name        = "${local.name}-api-alb"
  description = "API load balancer: listener ports from the allowed CIDRs only"
  vpc_id      = var.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "alb_from_allowed" {
  for_each          = local.api_ingress
  security_group_id = aws_security_group.alb.id
  description       = "${each.value.service} listener from an allowed CIDR"
  cidr_ipv4         = each.value.cidr
  ip_protocol       = "tcp"
  from_port         = local.api_listener_ports[each.value.service]
  to_port           = local.api_listener_ports[each.value.service]
}

resource "aws_vpc_security_group_egress_rule" "alb_to_tasks" {
  for_each                     = local.api_services
  security_group_id            = aws_security_group.alb.id
  description                  = "${each.key} container port on the tasks group"
  referenced_security_group_id = aws_security_group.tasks.id
  ip_protocol                  = "tcp"
  from_port                    = var.ports[each.key]
  to_port                      = var.ports[each.key]
}

resource "aws_vpc_security_group_ingress_rule" "tasks_from_alb" {
  for_each                     = local.api_services
  security_group_id            = aws_security_group.tasks.id
  description                  = "${each.key} container port from the API load balancer"
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = var.ports[each.key]
  to_port                      = var.ports[each.key]
}

resource "aws_lb" "api" {
  name                       = "${local.name}-api"
  internal                   = false
  load_balancer_type         = "application"
  ip_address_type            = "ipv4"
  security_groups            = [aws_security_group.alb.id]
  subnets                    = [for s in aws_subnet.public : s.id]
  drop_invalid_header_fields = true
  idle_timeout               = 120
  enable_deletion_protection = false # stateless, ephemeral runtime stack; removed by cleanup
  depends_on                 = [aws_route.public_default, aws_route_table_association.public]
}

resource "aws_lb_target_group" "api" {
  for_each             = local.api_services
  name                 = "scholarchain-${var.environment}-${each.key}"
  target_type          = "ip"
  protocol             = "HTTP"
  port                 = var.ports[each.key]
  vpc_id               = var.vpc_id
  deregistration_delay = 30
  health_check {
    protocol            = "HTTP"
    path                = "/healthz"
    port                = "traffic-port"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_listener" "api" {
  for_each          = local.api_services
  load_balancer_arn = aws_lb.api.arn
  port              = local.api_listener_ports[each.key]
  protocol          = local.api_https ? "HTTPS" : "HTTP"
  ssl_policy        = local.api_https ? "ELBSecurityPolicy-TLS13-1-2-2021-06" : null
  certificate_arn   = local.api_https ? var.api_certificate_arn : null
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api[each.key].arn
  }
}
