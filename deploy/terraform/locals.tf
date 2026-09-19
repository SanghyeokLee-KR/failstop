locals {
  azs           = { for i, az in var.availability_zones : tostring(i) => az }
  public_cidrs  = { for i, az in local.azs : i => cidrsubnet(var.vpc_cidr, 8, tonumber(i)) }
  private_cidrs = { for i, az in local.azs : i => cidrsubnet(var.vpc_cidr, 8, 10 + tonumber(i)) }
  fixed_nodes = {
    engine-a   = { role = "app", type = "engine", az = "0", ip = cidrhost(local.private_cidrs["0"], 30) }
    engine-c   = { role = "app", type = "engine", az = "1", ip = cidrhost(local.private_cidrs["1"], 30) }
    db-primary = { role = "data", type = "database", az = "0", ip = cidrhost(local.private_cidrs["0"], 20) }
    db-standby = { role = "standby", type = "database", az = "1", ip = cidrhost(local.private_cidrs["1"], 20) }
    bot        = { role = "bot", type = "bot", az = "1", ip = cidrhost(local.private_cidrs["1"], 40) }
  }
  connection_budget     = (2 + var.db_connections.engine_rollout_surge) * (var.db_connections.engine_pool + var.db_connections.append_pool + var.db_connections.lease_pool) + var.read_capacity.max * (var.db_connections.read_pool + 2) + (var.db_connections.bot_pool + 2) + var.db_connections.operations + var.db_connections.replication
  available_connections = var.db_connections.max - var.db_connections.reserved
  trusted_proxy_regex   = "^(?:${join("|", [for cidr in values(local.public_cidrs) : "${join("\\.", slice(split(".", cidrhost(cidr, 0)), 0, 3))}\\.[0-9]{1,3}"])})$"
  read_path_regex       = "^/api/v1/(markets|trades|candles/[A-Za-z0-9]+)$"
  runtime_overrides = {
    APP_IMAGE                 = var.app_image
    DB_HOST                   = local.fixed_nodes.db-primary.ip
    DB_PORT                   = "5432"
    DB_LISTEN_PORT            = "5432"
    STANDBY_LISTEN_PORT       = "5432"
    REPL_CIDR                 = var.vpc_cidr
    DB_MAX_CONNECTIONS        = tostring(var.db_connections.max)
    DB_RESERVED_CONNECTIONS   = tostring(var.db_connections.reserved)
    DB_OPERATIONS_CONNECTIONS = tostring(var.db_connections.operations)
    MAX_WAL_SENDERS           = tostring(var.db_connections.replication)
    ENGINE_NODE_COUNT         = "2"
    ENGINE_ROLLOUT_SURGE      = tostring(var.db_connections.engine_rollout_surge)
    APP_DB_POOL_MAX           = tostring(var.db_connections.engine_pool)
    APP_APPEND_POOL_MAX       = tostring(var.db_connections.append_pool)
    APP_LEASE_POOL_MAX        = tostring(var.db_connections.lease_pool)
    READ_MAX_NODES            = tostring(var.read_capacity.max)
    READ_DB_POOL_MAX          = tostring(var.db_connections.read_pool)
    BOT_NODE_COUNT            = "1"
    BOT_DB_POOL_MAX           = tostring(var.db_connections.bot_pool)
    EXCHANGE_TRUSTED_PROXIES  = local.trusted_proxy_regex
    SIM_BASE_URL              = "https://${var.api_domain}"
  }
  bootstrap = {
    region              = var.region
    cluster             = var.name
    release             = var.release
    operator_public_key = trimspace(var.operator_public_key)
    runtime_overrides   = local.runtime_overrides
    alb_subnets         = values(local.public_cidrs)
    vpc_cidr            = var.vpc_cidr
    monitor_ip          = local.fixed_nodes.bot.ip
    api_origin          = "https://${var.api_domain}"
  }
}

resource "terraform_data" "requirements" {
  input = { connection_budget = local.connection_budget, available_connections = local.available_connections }
  lifecycle {
    precondition {
      condition     = var.api_domain != var.web.domain
      error_message = "The CloudFront alias and ALB origin hostname must be different to prevent an origin loop."
    }
    precondition {
      condition     = local.connection_budget <= local.available_connections
      error_message = "ASG maximum plus fixed roles and rollout allowance exceeds the PostgreSQL connection budget."
    }
    precondition {
      condition     = alltrue([for az in var.availability_zones : startswith(az, var.region)])
      error_message = "Both availability zones must belong to the selected region."
    }
    precondition {
      condition     = split(":", var.certificate_arn)[3] == var.region
      error_message = "The ALB ACM certificate must be in the selected region."
    }
  }
}
