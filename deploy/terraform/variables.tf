variable "name" {
  type    = string
  default = "failstop-ops"
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,19}$", var.name))
    error_message = "Use 3 through 20 lowercase letters, digits and hyphens."
  }
}

variable "region" {
  type    = string
  default = "ap-northeast-2"
}

variable "availability_zones" {
  type = list(string)
  validation {
    condition     = length(var.availability_zones) == 2 && length(toset(var.availability_zones)) == 2
    error_message = "Exactly two distinct availability zones are required."
  }
}

variable "vpc_cidr" {
  type    = string
  default = "10.42.0.0/16"
  validation {
    condition     = can(cidrhost(var.vpc_cidr, 0)) && endswith(var.vpc_cidr, "/16") && startswith(var.vpc_cidr, "10.")
    error_message = "Use a 10.x.0.0/16 private network with no overlap with operator access networks."
  }
}

variable "ami" {
  description = "Reviewed Rocky Linux 10 AMI with the bootstrap prerequisites; no latest-AMI lookup."
  type        = object({ id = string, owner_account_id = string, architecture = string })
  validation {
    condition     = can(regex("^ami-[0-9a-f]{8,17}$", var.ami.id)) && can(regex("^[0-9]{12}$", var.ami.owner_account_id)) && var.ami.architecture == "arm64"
    error_message = "Supply a reviewed AMI ID and owner account for the adopted ARM64 architecture."
  }
}

variable "instance_types" {
  type = object({ engine = string, database = string, bot = string, reader = string })
}

variable "instance_profiles" {
  description = "Existing least-privilege IAM instance profile names. This module creates no IAM roles."
  type        = object({ engine = string, database = string, bot = string, reader = string })
  validation {
    condition     = alltrue([for name in values(var.instance_profiles) : can(regex("^[A-Za-z0-9+=,.@_-]{1,128}$", name))])
    error_message = "Provide existing IAM instance profile names."
  }
}

variable "asg_service_linked_role_arn" {
  type = string
  validation {
    condition     = can(regex("^arn:aws:iam::[0-9]{12}:role/aws-service-role/autoscaling.amazonaws.com/", var.asg_service_linked_role_arn))
    error_message = "Provide an existing Auto Scaling service-linked role ARN."
  }
}

variable "release" {
  description = "Versioned S3 release archive. Only object identifiers and the hash enter Terraform state."
  type        = object({ bucket = string, key = string, version_id = string, sha256 = string })
  validation {
    condition     = length(var.release.bucket) > 2 && length(var.release.key) > 0 && length(var.release.version_id) > 0 && var.release.version_id != "null" && can(regex("^[a-f0-9]{64}$", var.release.sha256))
    error_message = "Supply a versioned S3 object and its SHA256 digest."
  }
}

variable "app_image" {
  type = string
  validation {
    condition     = can(regex("^[^[:space:]]+@sha256:[a-f0-9]{64}$", var.app_image))
    error_message = "APP_IMAGE must be immutable and use a sha256 digest."
  }
}

variable "role_parameter_names" {
  description = "Existing SecureString parameter names containing role dotenv configuration, never secret values."
  type        = object({ app = string, data = string, standby = string, bot = string, reader = string })
  validation {
    condition     = alltrue([for name in values(var.role_parameter_names) : can(regex("^/[A-Za-z0-9_./-]+$", name))])
    error_message = "Use absolute SSM parameter names."
  }
}

variable "operator_public_key" {
  type = string
  validation {
    condition     = can(regex("^ssh-ed25519 [A-Za-z0-9+/=]+( [^\n\r]*)?$", trimspace(var.operator_public_key)))
    error_message = "Provide one Ed25519 public key; never pass a private key."
  }
}

variable "certificate_arn" {
  description = "Existing ACM certificate in the deployment region covering api_domain."
  type        = string
  validation {
    condition     = can(regex("^arn:aws:acm:[a-z0-9-]+:[0-9]{12}:certificate/", var.certificate_arn))
    error_message = "An existing regional ACM certificate is required."
  }
}

variable "api_domain" {
  type = string
  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9.-]+[a-z0-9]$", var.api_domain))
    error_message = "Use the DNS name covered by the ACM certificate, without a scheme or path."
  }
}

variable "dns_zone_id" {
  description = "Existing Route 53 public zone ID; null leaves DNS creation to the existing DNS operator."
  type        = string
  default     = null
}

variable "allowed_client_cidrs" {
  description = "Explicit ALB audience. Use the operator range for a private trial; public access must be deliberate."
  type        = set(string)
  validation {
    condition     = length(var.allowed_client_cidrs) > 0 && alltrue([for cidr in var.allowed_client_cidrs : can(cidrnetmask(cidr))])
    error_message = "Provide at least one explicit IPv4 client CIDR."
  }
}

variable "enable_public_access" {
  description = "Explicit publication gate: default closes ALB client ingress and disables the CloudFront distribution."
  type        = bool
  default     = false
}

variable "web" {
  type = object({ bucket_name = string, domain = string, certificate_arn = string })
  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.web.bucket_name)) && can(regex("^[a-z0-9][a-z0-9.-]+[a-z0-9]$", var.web.domain)) && can(regex("^arn:aws:acm:us-east-1:[0-9]{12}:certificate/", var.web.certificate_arn))
    error_message = "Supply a globally unique S3 bucket, web DNS name and existing ACM certificate in us-east-1."
  }
}

variable "enable_read_asg" {
  description = "Enable only after manual schema migration, runtime-role provisioning and fixed-node checks."
  type        = bool
  default     = false
}

variable "read_capacity" {
  type = object({ min = number, max = number, requests_per_target = number, warmup_seconds = optional(number, 300) })
  validation {
    condition     = var.read_capacity.min >= 1 && var.read_capacity.max >= var.read_capacity.min && var.read_capacity.max <= 6 && floor(var.read_capacity.min) == var.read_capacity.min && floor(var.read_capacity.max) == var.read_capacity.max && var.read_capacity.requests_per_target > 0 && var.read_capacity.warmup_seconds >= 120
    error_message = "Set integer capacity 1 <= min <= max <= 6, a positive measured requests/minute target and warmup >= 120 seconds."
  }
}

variable "db_connections" {
  type = object({
    max         = optional(number, 200), reserved = optional(number, 3), operations = optional(number, 10), replication = optional(number, 4),
    engine_pool = optional(number, 32), append_pool = optional(number, 14), lease_pool = optional(number, 2), engine_rollout_surge = optional(number, 1),
    read_pool   = optional(number, 8), bot_pool = optional(number, 8)
  })
  default = {}
  validation {
    condition     = alltrue([for n in values(var.db_connections) : n >= 0 && floor(n) == n]) && var.db_connections.max >= 50 && var.db_connections.reserved >= 3 && var.db_connections.engine_pool >= 8 && var.db_connections.append_pool >= 2 && var.db_connections.lease_pool >= 1 && var.db_connections.read_pool >= 2 && var.db_connections.bot_pool >= 2 && var.db_connections.replication >= 2
    error_message = "Provide valid nonnegative integer pool budgets; preserve runtime minimum pools and administrative reserves."
  }
}

variable "root_volume_gib" {
  type    = object({ engine = optional(number, 20), database = optional(number, 40), bot = optional(number, 30), reader = optional(number, 20) })
  default = {}
  validation {
    condition     = alltrue([for size in values(var.root_volume_gib) : size >= 20 && size <= 200 && floor(size) == size])
    error_message = "Root volumes must be between 20 and 200 GiB. Confirm actual space requirements before applying."
  }
}
