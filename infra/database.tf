resource "random_password" "db" {
  length  = 32
  special = false
}

resource "aws_ssm_parameter" "db_password" {
  name        = "/${var.project_name}/db-password"
  description = "RDS master password for LedgerAPI"
  type        = "SecureString"
  value       = random_password.db.result

  tags = { Name = "${var.project_name}-db-password" }
}

resource "aws_db_subnet_group" "main" {
  name       = "${var.project_name}-db-subnets"
  subnet_ids = aws_subnet.public[*].id

  tags = { Name = "${var.project_name}-db-subnets" }
}

resource "aws_db_instance" "main" {
  identifier     = "${var.project_name}-db"
  engine         = "postgres"
  engine_version = "16"
  instance_class = var.db_instance_class

  allocated_storage = 20
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = var.db_name
  username = var.db_username
  password = random_password.db.result

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]

  # The instance sits in public subnets to avoid paying for a NAT gateway, but
  # gets no public IP and accepts traffic only from the task security group.
  publicly_accessible = false
  multi_az            = false

  backup_retention_period    = 1
  auto_minor_version_upgrade = true

  # Demo environment: teardown must not require manual snapshot cleanup.
  # A production database would invert both of these.
  deletion_protection = false
  skip_final_snapshot = true

  tags = { Name = "${var.project_name}-db" }
}
