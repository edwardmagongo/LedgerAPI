data "aws_region" "current" {}

resource "aws_ecs_cluster" "main" {
  name = var.project_name

  tags = { Name = "${var.project_name}-cluster" }
}

resource "aws_cloudwatch_log_group" "app" {
  name              = "/ecs/${var.project_name}"
  retention_in_days = var.log_retention_days

  tags = { Name = "${var.project_name}-logs" }
}

# --- IAM ---

data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# Used by the ECS agent, not by the application: pulls the image, writes logs,
# and resolves the SSM parameters referenced in the task definition.
resource "aws_iam_role" "task_execution" {
  name               = "${var.project_name}-task-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "task_execution_managed" {
  role       = aws_iam_role.task_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# The managed policy covers ECR and CloudWatch but not reading these specific
# parameters, which the agent needs before it can start the container.
data "aws_iam_policy_document" "task_execution_secrets" {
  statement {
    actions = ["ssm:GetParameters"]
    resources = [
      aws_ssm_parameter.db_password.arn,
      aws_ssm_parameter.jwt_secret.arn,
    ]
  }
}

resource "aws_iam_role_policy" "task_execution_secrets" {
  name   = "${var.project_name}-read-secrets"
  role   = aws_iam_role.task_execution.id
  policy = data.aws_iam_policy_document.task_execution_secrets.json
}

# Assumed by the application itself. Deliberately carries no policies: the API
# makes no AWS API calls at runtime. It exists so adding one later does not
# require restructuring the task definition.
resource "aws_iam_role" "task" {
  name               = "${var.project_name}-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

# --- Task definition ---

resource "aws_ecs_task_definition" "app" {
  family                   = var.project_name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.task_execution.arn
  task_role_arn            = aws_iam_role.task.arn

  # Stated explicitly rather than left to the provider default: the bootstrap
  # image is force-built for linux/amd64 (see infra/README.md), so the task
  # definition must match or Fargate cannot pull it.
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }

  container_definitions = jsonencode([
    {
      name      = var.project_name
      image     = "${aws_ecr_repository.ledger_api.repository_url}:${var.container_image_tag}"
      essential = true

      portMappings = [
        {
          containerPort = var.container_port
          protocol      = "tcp"
        }
      ]

      # aws_db_instance.endpoint already includes the port, so this renders as
      # jdbc:postgresql://<host>:5432/ledger
      environment = [
        {
          name  = "SPRING_DATASOURCE_URL"
          value = "jdbc:postgresql://${aws_db_instance.main.endpoint}/${var.db_name}"
        },
        {
          name  = "SPRING_DATASOURCE_USERNAME"
          value = var.db_username
        }
      ]

      # Resolved by the ECS agent using the execution role and injected as
      # environment variables before the container starts. The application
      # needs no AWS SDK and never calls SSM itself.
      secrets = [
        {
          name      = "SPRING_DATASOURCE_PASSWORD"
          valueFrom = aws_ssm_parameter.db_password.arn
        },
        {
          name      = "LEDGER_JWT_SECRET"
          valueFrom = aws_ssm_parameter.jwt_secret.arn
        }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.app.name
          "awslogs-region"        = data.aws_region.current.name
          "awslogs-stream-prefix" = "ecs"
        }
      }
    }
  ])

  tags = { Name = "${var.project_name}-task" }
}

# --- Service ---

resource "aws_ecs_service" "app" {
  name            = var.project_name
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.app.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets         = aws_subnet.public[*].id
    security_groups = [aws_security_group.ecs_task.id]

    # Load-bearing. With no NAT gateway and no VPC endpoints, a task without a
    # public IP has no route to ECR, SSM or CloudWatch and will not start.
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.app.arn
    container_name   = var.project_name
    container_port   = var.container_port
  }

  # Spring Boot context startup plus the Flyway migration takes well over a
  # minute. Without this grace period the target group marks the task
  # unhealthy and ECS kills it before it ever serves a health check, producing
  # a permanent crash loop that looks like an application bug.
  health_check_grace_period_seconds = 120

  depends_on = [aws_lb_listener.http]

  # There is no on-call or alerting for this project, so an automatic rollback
  # is the only recovery mechanism if a deploy ships a task that can't reach a
  # steady state (bad image, OOM, migration failure) — without this, ECS
  # retries indefinitely and the deploy just hangs until the GitHub Actions
  # waiter times out, leaving the service degraded.
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  # CI registers a new task definition revision on every merge to main. Without
  # this, the next `terraform apply` would notice the drift and roll the
  # running service back to the bootstrap image. Terraform owns the
  # infrastructure; the pipeline owns which image version runs.
  lifecycle {
    ignore_changes = [task_definition, desired_count]
  }

  tags = { Name = "${var.project_name}-service" }
}
