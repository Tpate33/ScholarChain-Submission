# Network: one VPC, one private subnet, an S3 gateway endpoint. No internet gateway, no NAT gateway,
# no public IP addresses. EMR-managed security groups are created without inline rules; EMR adds the
# rules it needs, and two explicit egress rules per node group are added below. The KMS interface
# endpoint the nodes need lives in the transient stack emr-run (billed hourly).

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = merge(local.emr_managed_tag, { Name = "${local.name}-vpc" })
}

# The default security group is taken over with no rules (deny all).
resource "aws_default_security_group" "default" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${local.name}-default-deny" }
}

resource "aws_subnet" "private" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = var.private_subnet_cidr
  availability_zone_id    = var.availability_zone_id
  map_public_ip_on_launch = false
  tags                    = merge(local.emr_managed_tag, { Name = "${local.name}-private" })
}

resource "aws_route_table" "private" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${local.name}-private" }
}

resource "aws_route_table_association" "private" {
  subnet_id      = aws_subnet.private.id
  route_table_id = aws_route_table.private.id
}

# Gateway endpoints have no hourly or data-processing charge (verified, emr-docs-research-03.md).
# Default (full-access) endpoint policy: EMR nodes also read AWS-owned EMR repository buckets.
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${var.aws_region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.private.id]
  tags              = { Name = "${local.name}-s3-gateway" }
}

resource "aws_security_group" "emr_primary" {
  name                   = "${local.name}-emr-primary"
  description            = "EMR-managed security group for the primary node (rules managed by EMR)"
  vpc_id                 = aws_vpc.main.id
  revoke_rules_on_delete = true
  tags                   = merge(local.emr_managed_tag, { Name = "${local.name}-emr-primary" })

  lifecycle {
    ignore_changes = [ingress, egress]
  }
}

resource "aws_security_group" "emr_core" {
  name                   = "${local.name}-emr-core"
  description            = "EMR-managed security group for core nodes (rules managed by EMR)"
  vpc_id                 = aws_vpc.main.id
  revoke_rules_on_delete = true
  tags                   = merge(local.emr_managed_tag, { Name = "${local.name}-emr-core" })

  lifecycle {
    ignore_changes = [ingress, egress]
  }
}

# Egress of the primary and core nodes (CR-P6-27 6b). Terraform removes the default allow-all egress of
# a security group it creates; whether EMR adds egress rules to customer-supplied managed groups is
# UNVERIFIED, so the two paths the nodes need are explicit: HTTPS to S3 through the gateway endpoint
# (artifacts, data, logs, EMR repositories) and all traffic inside the VPC (node to node, the service
# access ENI, the KMS interface endpoint of stack emr-run). Nothing leaves the VPC except through the
# S3 gateway endpoint: there is no internet gateway or NAT route.
locals {
  emr_node_security_groups = {
    primary = aws_security_group.emr_primary.id
    core    = aws_security_group.emr_core.id
  }
}

resource "aws_vpc_security_group_egress_rule" "emr_nodes_s3_https" {
  for_each          = local.emr_node_security_groups
  security_group_id = each.value
  description       = "HTTPS to S3 through the gateway endpoint"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  prefix_list_id    = aws_vpc_endpoint.s3.prefix_list_id
}

resource "aws_vpc_security_group_egress_rule" "emr_nodes_vpc" {
  for_each          = local.emr_node_security_groups
  security_group_id = each.value
  description       = "All traffic inside the VPC (nodes, service access, KMS endpoint)"
  ip_protocol       = "-1"
  cidr_ipv4         = var.vpc_cidr
}

resource "aws_security_group" "emr_service_access" {
  name                   = "${local.name}-emr-service-access"
  description            = "EMR-managed service access security group (private subnet; rules managed by EMR)"
  vpc_id                 = aws_vpc.main.id
  revoke_rules_on_delete = true
  tags                   = merge(local.emr_managed_tag, { Name = "${local.name}-emr-service-access" })

  lifecycle {
    ignore_changes = [ingress, egress]
  }
}
