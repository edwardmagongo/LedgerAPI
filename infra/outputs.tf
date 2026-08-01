output "alb_dns_name" {
  description = "Public hostname for the API. Swagger UI is at http://<this>/swagger-ui.html"
  value       = aws_lb.main.dns_name
}

output "ecr_repository_url" {
  description = "Docker push target, needed for the phase-2 bootstrap image push."
  value       = aws_ecr_repository.ledger_api.repository_url
}

output "ecr_repository_name" {
  description = "Set as the ECR_REPOSITORY GitHub Actions variable."
  value       = aws_ecr_repository.ledger_api.name
}

output "rds_endpoint" {
  description = "RDS host:port. Not reachable from outside the VPC; shown for debugging."
  value       = aws_db_instance.main.endpoint
}

output "github_deploy_role_arn" {
  description = "Set as the AWS_DEPLOY_ROLE_ARN GitHub Actions variable."
  value       = aws_iam_role.github_deploy.arn
}

output "ecs_cluster_name" {
  description = "Set as the ECS_CLUSTER GitHub Actions variable."
  value       = aws_ecs_cluster.main.name
}

output "ecs_service_name" {
  description = "Set as the ECS_SERVICE GitHub Actions variable."
  value       = aws_ecs_service.app.name
}

output "ecs_task_family" {
  description = "Set as the ECS_TASK_FAMILY GitHub Actions variable."
  value       = aws_ecs_task_definition.app.family
}

output "ecs_container_name" {
  description = "Set as the ECS_CONTAINER_NAME GitHub Actions variable."
  value       = var.project_name
}
