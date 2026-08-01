resource "aws_ecr_repository" "ledger_api" {
  name                 = var.project_name
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  # Demo environment: let `terraform destroy` remove the repository even when
  # images remain, so teardown never needs a manual console step. A production
  # registry would leave this false.
  force_delete = true

  tags = { Name = "${var.project_name}-ecr" }
}

# Every build is tagged with its commit SHA, so without an expiry rule images
# accumulate forever. Ten is enough history to roll back a few releases.
resource "aws_ecr_lifecycle_policy" "keep_recent" {
  repository = aws_ecr_repository.ledger_api.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Keep only the 10 most recent images"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = 10
        }
        action = {
          type = "expire"
        }
      }
    ]
  })
}
