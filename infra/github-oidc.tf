# The provider is an account-global singleton per URL: only one can exist for
# https://token.actions.githubusercontent.com in a given AWS account. If some
# other repository in this account already set up GitHub OIDC, creating a
# second one fails with EntityAlreadyExists — set
# var.create_github_oidc_provider = false to reuse the existing one instead
# (see infra/README.md).
resource "aws_iam_openid_connect_provider" "github" {
  count = var.create_github_oidc_provider ? 1 : 0

  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]

  # AWS no longer validates thumbprints for this well-known issuer, but the
  # API still accepts the value and older tooling expects it to be present.
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"]
}

data "aws_iam_openid_connect_provider" "existing" {
  count = var.create_github_oidc_provider ? 0 : 1

  url = "https://token.actions.githubusercontent.com"
}

locals {
  github_oidc_provider_arn = var.create_github_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn : data.aws_iam_openid_connect_provider.existing[0].arn
}

# The sub condition is what stops any other repository — or a branch other
# than main in this one — from assuming the role. Without it, the OIDC trust
# would accept tokens from all of GitHub.
#
# Repositories created on or after 2026-07-15 get immutable owner/repo IDs
# baked into the sub claim (owner@id/repo@id) instead of the plain owner/repo
# names, so matching on var.github_repository alone is not enough — see the
# comment above var.github_owner_id in variables.tf.
data "aws_iam_policy_document" "github_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${split("/", var.github_repository)[0]}@${var.github_owner_id}/${split("/", var.github_repository)[1]}@${var.github_repo_id}:ref:refs/heads/main"]
    }
  }
}

resource "aws_iam_role" "github_deploy" {
  name               = "${var.project_name}-github-deploy"
  description        = "Assumed by GitHub Actions via OIDC to push images and deploy the service"
  assume_role_policy = data.aws_iam_policy_document.github_assume.json
}

data "aws_iam_policy_document" "github_deploy" {
  statement {
    sid       = "EcrLogin"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"] # This action does not support resource-level permissions.
  }

  statement {
    sid = "PushToThisRepositoryOnly"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:CompleteLayerUpload",
      "ecr:GetDownloadUrlForLayer",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
    ]
    resources = [aws_ecr_repository.ledger_api.arn]
  }

  statement {
    sid = "RegisterTaskDefinitions"
    actions = [
      "ecs:DescribeTaskDefinition",
      "ecs:RegisterTaskDefinition",
    ]
    resources = ["*"] # Neither action supports resource-level permissions.
  }

  statement {
    sid = "UpdateThisServiceOnly"
    actions = [
      "ecs:DescribeServices",
      "ecs:UpdateService",
    ]
    # aws_ecs_service exposes its ARN as `id`, not as `arn`.
    resources = [aws_ecs_service.app.id]
  }

  # Registering a task definition that names the task roles counts as passing
  # them, so this is required even though the workflow never assumes them.
  statement {
    sid     = "PassTaskRoles"
    actions = ["iam:PassRole"]
    resources = [
      aws_iam_role.task_execution.arn,
      aws_iam_role.task.arn,
    ]
  }
}

resource "aws_iam_role_policy" "github_deploy" {
  name   = "${var.project_name}-deploy"
  role   = aws_iam_role.github_deploy.id
  policy = data.aws_iam_policy_document.github_deploy.json
}
