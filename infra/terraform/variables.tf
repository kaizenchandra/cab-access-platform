variable "region" { type = string }
variable "name" {
  type    = string
  default = "cab-access"
}
variable "availability_zones" {
  type = list(string)
  validation {
    condition     = length(var.availability_zones) == 2
    error_message = "Exactly two availability zones are required."
  }
}
variable "image" {
  type        = string
  description = "Approved immutable image reference including @sha256 digest."
  validation {
    condition     = can(regex("@sha256:[a-f0-9]{64}$", var.image))
    error_message = "Use an immutable image digest."
  }
}
variable "certificate_arn" { type = string }
variable "oidc_issuer" { type = string }
variable "oidc_jwks" { type = string }
variable "runtime_secret_arns" {
  type        = map(string)
  description = "Environment variable -> Secrets Manager ARN, optionally JSON-key selector. Supply DATABASE_USER/PASSWORD, CAB_CONTACT_ENCRYPTION_KEY, merchant keys, notification and gate credentials."
  validation {
    condition     = alltrue([for name in ["DATABASE_USER", "DATABASE_PASSWORD", "CAB_CONTACT_ENCRYPTION_KEY", "NOTIFICATION_TOKEN", "GATE_TOKEN"] : contains(keys(var.runtime_secret_arns), name)])
    error_message = "Required runtime credentials must be injected from Secrets Manager."
  }
}
variable "secret_resource_arns" {
  type        = list(string)
  description = "Base secret ARNs permitted to the ECS execution role."
}
variable "secret_kms_key_arns" {
  type    = list(string)
  default = []
}
variable "notification_url" { type = string }
variable "gate_url" { type = string }
variable "db_instance_class" {
  type    = string
  default = "db.t4g.small"
}
variable "db_engine_version" {
  type    = string
  default = "17.6"
}
variable "desired_count" {
  type    = number
  default = 2
}
