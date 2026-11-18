# Read by the emr-run stack through terraform_remote_state. No output is sensitive.

output "data_bucket" {
  value = module.data_bucket.id
}

output "logs_bucket" {
  value = module.logs_bucket.id
}

output "vpc_id" {
  value = aws_vpc.main.id
}

output "vpc_cidr" {
  value = aws_vpc.main.cidr_block
}

output "private_subnet_id" {
  value = aws_subnet.private.id
}

output "emr_primary_security_group_id" {
  value = aws_security_group.emr_primary.id
}

output "emr_core_security_group_id" {
  value = aws_security_group.emr_core.id
}

output "emr_service_access_security_group_id" {
  value = aws_security_group.emr_service_access.id
}

output "emr_service_role_arn" {
  value = aws_iam_role.emr_service.arn
}

output "emr_instance_profile_arn" {
  value = aws_iam_instance_profile.emr_instance.arn
}

output "emr_security_configuration_name" {
  value = aws_emr_security_configuration.main.name
}

output "emr_kms_key_arn" {
  value = aws_kms_key.emr.arn
}

output "operator_role_arn" {
  value = aws_iam_role.operator.arn
}

output "readonly_role_arn" {
  value = aws_iam_role.readonly.arn
}
