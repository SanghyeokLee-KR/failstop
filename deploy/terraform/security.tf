resource "aws_security_group" "role" {
  for_each    = toset(["alb", "cloudfront-origin", "engine", "reader", "database", "bot"])
  name        = "${var.name}-${each.key}"
  description = "Failstop ${each.key} boundary"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "${var.name}-${each.key}" }
}

resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  for_each          = var.enable_public_access ? var.allowed_client_cidrs : toset([])
  security_group_id = aws_security_group.role["alb"].id
  cidr_ipv4         = each.value
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  for_each          = var.enable_public_access ? var.allowed_client_cidrs : toset([])
  security_group_id = aws_security_group.role["alb"].id
  cidr_ipv4         = each.value
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
}

locals {
  ingress = merge(
    { for role in ["engine", "reader"] : "${role}-api" => { target = role, source = "alb", port = 8080 } },
    { for role in ["engine", "reader"] : "${role}-health" => { target = role, source = "alb", port = 8081 } },
    { for role in ["engine", "reader"] : "${role}-metrics" => { target = role, source = "bot", port = 8081 } },
    { for role in ["engine", "reader", "database", "bot"] : "${role}-host" => { target = role, source = "bot", port = 9100 } },
    { for role in ["engine", "reader", "bot", "database"] : "database-${role}" => { target = "database", source = role, port = 5432 } },
    { "database-metrics" = { target = "database", source = "bot", port = 9187 }, "reporter" = { target = "bot", source = "bot", port = 9088 } }
  )
}

data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

resource "aws_vpc_security_group_ingress_rule" "cloudfront_origin" {
  count = var.enable_public_access ? 1 : 0
  # CloudFront's managed prefix list consumes 55 rules; keep it in a separate SG.
  security_group_id = aws_security_group.role["cloudfront-origin"].id
  prefix_list_id    = data.aws_ec2_managed_prefix_list.cloudfront.id
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_vpc_security_group_ingress_rule" "bot_api" {
  for_each          = var.enable_public_access ? local.azs : {}
  security_group_id = aws_security_group.role["alb"].id
  cidr_ipv4         = "${aws_eip.nat[each.key].public_ip}/32"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  description       = "Bot requests reach the public ALB through the private subnet NAT"
}

resource "aws_vpc_security_group_ingress_rule" "private" {
  for_each                     = local.ingress
  security_group_id            = aws_security_group.role[each.value.target].id
  referenced_security_group_id = aws_security_group.role[each.value.source].id
  ip_protocol                  = "tcp"
  from_port                    = each.value.port
  to_port                      = each.value.port
}

resource "aws_vpc_security_group_egress_rule" "alb_targets" {
  for_each                     = toset(["engine", "reader"])
  security_group_id            = aws_security_group.role["alb"].id
  referenced_security_group_id = aws_security_group.role[each.key].id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8081
}

resource "aws_vpc_security_group_egress_rule" "runtime" {
  for_each          = toset(["engine", "reader", "database", "bot"])
  security_group_id = aws_security_group.role[each.key].id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
  description       = "Private egress via same-AZ NAT for image, SSM, package and reporting endpoints"
}
