# 64 alphanumeric characters is 512 bits, comfortably above the 256-bit floor
# HS256 requires. Special characters are excluded so the value survives shell
# and environment-variable handling without escaping.
resource "random_password" "jwt_secret" {
  length  = 64
  special = false
}

resource "aws_ssm_parameter" "jwt_secret" {
  name        = "/${var.project_name}/jwt-secret"
  description = "HS256 signing key for LedgerAPI JWTs"
  type        = "SecureString"
  value       = random_password.jwt_secret.result

  tags = { Name = "${var.project_name}-jwt-secret" }
}
