#!/usr/bin/env bash
# Scales the live ECS service's task count. NOT done via Terraform: the service's
# `lifecycle { ignore_changes = [task_definition, desired_count] }` (see ecs.tf) deliberately
# stops `terraform apply` from fighting the deploy pipeline over the running count, the same
# reason it already does so for the image version - so scaling happens the same way an image
# deploy does, outside Terraform.
#
# Usage: infra/scale.sh <count>
set -euo pipefail

COUNT="${1:?Usage: infra/scale.sh <count>}"
CLUSTER=$(terraform -chdir=infra output -raw ecs_cluster_name)
SERVICE=$(terraform -chdir=infra output -raw ecs_service_name)

aws ecs update-service \
  --cluster "$CLUSTER" \
  --service "$SERVICE" \
  --desired-count "$COUNT" \
  --query 'service.{cluster:clusterArn,service:serviceName,desiredCount:desiredCount}' \
  --output table

echo "Scaled to $COUNT. Waiting for the service to stabilize..."
aws ecs wait services-stable --cluster "$CLUSTER" --services "$SERVICE"
echo "Stable at $COUNT tasks."
