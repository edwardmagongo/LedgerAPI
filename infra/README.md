# LedgerAPI infrastructure

Terraform for running LedgerAPI on ECS Fargate with RDS Postgres behind an ALB, in `eu-west-2`.

See `../docs/superpowers/specs/2026-08-01-aws-deployment-design.md` for why the architecture looks
the way it does — particularly the no-NAT trade-off and the Terraform/CI ownership boundary.

**Nothing here has ever been applied.** It is written to be correct and reviewable; standing it up
against a real account is the exercise below.

## Prerequisites

- Terraform >= 1.9 (`brew install terraform`)
- AWS CLI v2, authenticated against an account you are happy to spend roughly $55/month in
  (`aws configure`)
- Docker, for building and pushing the image

## Standing it up from scratch

The ECR repository is created empty, but the ECS service needs an image to pull the moment it is
created. A single `terraform apply` therefore leaves the service retrying a task that can never
start. The first run is three phases:

```bash
# Phase 1 — create only the registry
terraform init
terraform apply -target=aws_ecr_repository.ledger_api

# Phase 2 — build and push the bootstrap image.
# `terraform output` can come back empty after a -target apply, since outputs
# depending on not-yet-created resources are pruned from the graph. Fall back
# to asking ECR directly.
REPO=$(terraform output -raw ecr_repository_url 2>/dev/null \
  || aws ecr describe-repositories --repository-names ledger-api \
       --region eu-west-2 --query 'repositories[0].repositoryUri' --output text)

aws ecr get-login-password --region eu-west-2 | docker login --username AWS --password-stdin "$REPO"
docker build -t "$REPO:bootstrap" ..
docker push "$REPO:bootstrap"

# Phase 3 — create everything else, now that a pullable image exists
terraform apply
```

Subsequent applies are a single `terraform apply`. The three-phase sequence only applies to a fresh
environment, including after a `terraform destroy`.

Once phase 3 finishes:

```bash
curl -sf "http://$(terraform output -raw alb_dns_name)/actuator/health"
```

Expect `{"status":"UP"}`. Allow a couple of minutes — the task needs to start, run Flyway, and pass
two consecutive health checks before the ALB routes to it.

## Wiring up CI/CD

The deploy workflow reads repository-level **variables** (not secrets — none of these are
sensitive). Set them from the Terraform outputs:

| GitHub Actions variable | Value |
|---|---|
| `AWS_REGION` | `eu-west-2` |
| `AWS_DEPLOY_ROLE_ARN` | `terraform output -raw github_deploy_role_arn` |
| `ECR_REPOSITORY` | `terraform output -raw ecr_repository_name` |
| `ECS_CLUSTER` | `terraform output -raw ecs_cluster_name` |
| `ECS_SERVICE` | `terraform output -raw ecs_service_name` |
| `ECS_TASK_FAMILY` | `terraform output -raw ecs_task_family` |
| `ECS_CONTAINER_NAME` | `terraform output -raw ecs_container_name` |

The OIDC trust policy is scoped to one repository, set by `var.github_repository`. If the GitHub
repository is not `edwardmagongo/LedgerAPI`, override that variable or the deploy job will fail to
assume the role.

## Who owns what

Terraform owns the infrastructure. **The pipeline owns which image version is running.** The ECS
service carries `lifecycle { ignore_changes = [task_definition, desired_count] }`, so Terraform will
not revert a deployment made by CI. Terraform's task definition is only ever the bootstrap revision.

## Tearing it down

```bash
terraform destroy
```

This is a one-command teardown by design: the database sets `deletion_protection = false` and
`skip_final_snapshot = true`, and the ECR repository sets `force_delete = true`. All three are the
opposite of what a production environment should do, and are deliberate choices for an environment
meant to be created and destroyed around a demo.

## Verifying without an AWS account

```bash
terraform init
terraform fmt -check
terraform validate
```

These need network access to download providers but **no AWS credentials**. `terraform plan` does
need credentials — the AWS provider authenticates while configuring itself, before it plans
anything.
