# Network: one VPC, one private subnet, an S3 gateway endpoint. No internet gateway, no NAT gateway,
# no public IP addresses. EMR-managed security groups are created without inline rules; two explicit
# egress rules per node group and the private-subnet service-access rules (IE-P6-07, including node
# egress TCP 9443 to the service access group) are standalone rule resources below; EMR may add
# further inbound rules at cluster creation. The KMS interface endpoint the nodes need lives in the
# transient stack emr-run (billed hourly).

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

# Private-subnet rules between the cluster manager (service access ENI) and the nodes (IE-P6-07).
# Source: EMR Management Guide, "Amazon EMR-managed security groups", private subnets, releases
# 5.30.0 and later: service access inbound TCP 9443 from the primary group; service access outbound
# TCP 8443 to the primary and to the core/task groups; primary and core/task inbound TCP 8443 from the
# service access group; "the same rules are needed if you are using custom security groups". Cluster
# scholarchain-dev-spark-run failed with VALIDATION_ERROR "ServiceAccessSecurityGroup is missing
# ingress rule from EmrManagedMasterSecurityGroup on port 9443", so EMR did not add that rule itself.
# The service access group has no egress otherwise: Terraform revokes the default allow-all egress of
# every aws_security_group it creates. All rules are group-to-group inside the VPC; no CIDR source.
#
# Node egress TCP 9443 to the service access group is explicit and group-referenced (CR-P6-40 m1): the
# same guide lists primary outbound TCP 9443 to the service access group ("EMR does not add this rule
# when you use a custom managed security group") and core/task minimum egress including TCP 9443 to the
# service access group, and EMR's pre-launch validation evidently checks by group reference. It is a
# subset of emr_nodes_vpc (all traffic to var.vpc_cidr), so it widens nothing. Because core nodes may
# send 9443 to the service access ENI, the service access group also admits TCP 9443 from the core
# group (orchestrator decision, CR-P6-40; the guide's service access inbound table names only the
# primary group, so this rule is defensive, group-referenced and inside the VPC). Node egress TCP 443 to
# the service access group is NOT added: emr_nodes_vpc covers it and the guide requires no matching
# service access ingress.
#
# Interaction with EMR and with ignore_changes: the security groups ignore their inline ingress and
# egress, so rules EMR adds never show as drift and are never removed by a plan; these standalone
# rules are tracked by their own rule IDs. An existing identical rule (for example one EMR added during
# the failed attempt; the most likely duplicates are the TCP 8443 inbound rules on the primary and core
# groups from the service access group) makes the create fail with InvalidPermission.Duplicate; such a
# rule is adopted with an import block, never deleted by hand. After an import, the plan may show an
# in-place description update on that rule resource; that is expected and is not group drift. On
# destroy, revoke_rules_on_delete revokes any rule EMR added before the group is deleted.
resource "aws_vpc_security_group_ingress_rule" "emr_service_access_from_primary" {
  security_group_id            = aws_security_group.emr_service_access.id
  description                  = "TCP 9443 from the EMR primary group (private subnet)"
  ip_protocol                  = "tcp"
  from_port                    = 9443
  to_port                      = 9443
  referenced_security_group_id = aws_security_group.emr_primary.id
}

resource "aws_vpc_security_group_ingress_rule" "emr_service_access_from_core" {
  security_group_id            = aws_security_group.emr_service_access.id
  description                  = "TCP 9443 from the EMR core group (private subnet)"
  ip_protocol                  = "tcp"
  from_port                    = 9443
  to_port                      = 9443
  referenced_security_group_id = aws_security_group.emr_core.id
}

resource "aws_vpc_security_group_egress_rule" "emr_nodes_to_service_access" {
  for_each                     = local.emr_node_security_groups
  security_group_id            = each.value
  description                  = "TCP 9443 to the EMR service access group (private subnet)"
  ip_protocol                  = "tcp"
  from_port                    = 9443
  to_port                      = 9443
  referenced_security_group_id = aws_security_group.emr_service_access.id
}

resource "aws_vpc_security_group_egress_rule" "emr_service_access_to_nodes" {
  for_each                     = local.emr_node_security_groups
  security_group_id            = aws_security_group.emr_service_access.id
  description                  = "TCP 8443 to the EMR ${each.key} group (cluster manager)"
  ip_protocol                  = "tcp"
  from_port                    = 8443
  to_port                      = 8443
  referenced_security_group_id = each.value
}

resource "aws_vpc_security_group_ingress_rule" "emr_nodes_from_service_access" {
  for_each                     = local.emr_node_security_groups
  security_group_id            = each.value
  description                  = "TCP 8443 from the EMR service access group (cluster manager)"
  ip_protocol                  = "tcp"
  from_port                    = 8443
  to_port                      = 8443
  referenced_security_group_id = aws_security_group.emr_service_access.id
}
