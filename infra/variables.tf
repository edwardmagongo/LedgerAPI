variable "aws_region" {
  description = "AWS region for all resources."
  type        = string
  default     = "eu-west-2"
}

variable "project_name" {
  description = "Name prefix applied to every resource."
  type        = string
  default     = "ledger-api"
}

variable "vpc_cidr" {
  description = "CIDR block for the VPC."
  type        = string
  default     = "10.0.0.0/16"
}

variable "public_subnet_cidrs" {
  description = "CIDR blocks for the public subnets, one per availability zone."
  type        = list(string)
  default     = ["10.0.1.0/24", "10.0.2.0/24"]
}

variable "container_port" {
  description = "Port the Spring Boot container listens on. Matches EXPOSE in the Dockerfile."
  type        = number
  default     = 8080
}

variable "container_image_tag" {
  description = "Image tag for the bootstrap task definition. CI registers new revisions on every deploy and Terraform ignores that drift, so this only matters for the very first apply."
  type        = string
  default     = "bootstrap"
}

variable "task_cpu" {
  description = "Fargate CPU units. 512 = 0.5 vCPU. Do not reduce: 0.25 vCPU stretches JVM startup past the health check grace period."
  type        = number
  default     = 512
}

variable "task_memory" {
  description = "Fargate memory in MiB. The JVM defaults to a quarter of this as max heap, so 1024 is the practical floor for Spring Boot."
  type        = number
  default     = 1024
}

variable "db_instance_class" {
  description = "RDS instance class."
  type        = string
  default     = "db.t4g.micro"
}

variable "db_name" {
  description = "Initial database name created by RDS."
  type        = string
  default     = "ledger"
}

variable "db_username" {
  description = "RDS master username."
  type        = string
  default     = "ledger"
}

variable "github_repository" {
  description = "Repository allowed to assume the deploy role, as owner/name. Must match the real GitHub repository or OIDC authentication will be refused."
  type        = string
  default     = "edwardmagongo/LedgerAPI"
}

variable "log_retention_days" {
  description = "CloudWatch Logs retention for the ECS task's log group."
  type        = number
  default     = 14
}
