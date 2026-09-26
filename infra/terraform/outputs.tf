output "api_hostname" { value = aws_lb.main.dns_name }
output "database_endpoint" { value = aws_db_instance.main.endpoint }
output "bootstrap_secret_arn" {
  value     = aws_db_instance.main.master_user_secret[0].secret_arn
  sensitive = true
}
output "private_subnets" { value = aws_subnet.private[*].id }
output "application_security_group" { value = aws_security_group.app.id }
