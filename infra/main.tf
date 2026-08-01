terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # Remote state is recommended but deliberately not provisioned by this
  # configuration (see the design spec's non-goals) — an S3 backend has to
  # exist before Terraform can use it, which is a bootstrap problem this
  # project does not need to solve. To adopt it later, create an encrypted
  # bucket and a lock table out of band, then uncomment:
  #
  # backend "s3" {
  #   bucket         = "ledger-api-tfstate-<unique-suffix>"
  #   key            = "ledger-api/terraform.tfstate"
  #   region         = "eu-west-2"
  #   dynamodb_table = "ledger-api-tflock"
  #   encrypt        = true
  # }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project   = var.project_name
      ManagedBy = "terraform"
    }
  }
}
