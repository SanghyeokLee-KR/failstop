mock_provider "aws" {
  mock_data "aws_ami" {
    defaults = { id = "ami-0123456789abcdef0", root_device_name = "/dev/sda1", architecture = "arm64" }
  }
  mock_data "aws_ec2_instance_type" {
    defaults = { supported_architectures = ["arm64"] }
  }
  mock_data "aws_ec2_managed_prefix_list" {
    defaults = { id = "pl-0123456789abcdef0" }
  }
  mock_data "aws_cloudfront_cache_policy" {
    defaults = { id = "11111111-2222-4333-8444-555555555555" }
  }
  mock_data "aws_cloudfront_origin_request_policy" {
    defaults = { id = "11111111-2222-4333-8444-555555555555" }
  }
  mock_data "aws_cloudfront_response_headers_policy" {
    defaults = { id = "11111111-2222-4333-8444-555555555555" }
  }
}

variables {
  availability_zones          = ["ap-northeast-2a", "ap-northeast-2c"]
  ami                         = { id = "ami-0123456789abcdef0", owner_account_id = "123456789012", architecture = "arm64" }
  instance_types              = { engine = "c7g.large", database = "t4g.medium", bot = "t4g.medium", reader = "t4g.small" }
  instance_profiles           = { engine = "ExistingEngine", database = "ExistingDatabase", bot = "ExistingOperations", reader = "ExistingReader" }
  asg_service_linked_role_arn = "arn:aws:iam::123456789012:role/aws-service-role/autoscaling.amazonaws.com/AWSServiceRoleForAutoScaling"
  release                     = { bucket = "example-failstop-release", key = "release.tar.gz", version_id = "fixture-version", sha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" }
  app_image                   = "example.invalid/failstop@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
  role_parameter_names        = { app = "/failstop/app", data = "/failstop/data", standby = "/failstop/standby", bot = "/failstop/bot", reader = "/failstop/reader" }
  operator_public_key         = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIFixtureOnlyNotARealKey0000000000000000000000= fixture"
  certificate_arn             = "arn:aws:acm:ap-northeast-2:123456789012:certificate/11111111-2222-4333-8444-555555555555"
  api_domain                  = "origin.example.invalid"
  allowed_client_cidrs        = ["198.51.100.0/24"]
  web                         = { bucket_name = "example-failstop-web", domain = "exchange.example.invalid", certificate_arn = "arn:aws:acm:us-east-1:123456789012:certificate/11111111-2222-4333-8444-555555555555" }
  read_capacity               = { min = 1, max = 2, requests_per_target = 600 }
}

run "default_is_private_and_fixed_roles_stay_fixed" {
  command = plan
  assert {
    condition     = length(aws_nat_gateway.main) == 2 && length(aws_subnet.private) == 2 && length(aws_instance.fixed) == 5
    error_message = "Preserve two AZs, two NAT gateways and five fixed engine/database/operations nodes."
  }
  assert {
    condition     = alltrue([for node in aws_instance.fixed : !node.associate_public_ip_address])
    error_message = "Runtime nodes must not receive public IP addresses."
  }
  assert {
    condition     = aws_instance.fixed["bot"].metadata_options[0].http_put_response_hop_limit == 2 && aws_instance.fixed["bot"].metadata_options[0].http_tokens == "required"
    error_message = "Only the operations host permits its Grafana container to obtain IMDSv2 instance-role credentials."
  }
  assert {
    condition     = alltrue([for name, node in aws_instance.fixed : node.metadata_options[0].http_put_response_hop_limit == 1 && node.metadata_options[0].http_tokens == "required" if name != "bot"])
    error_message = "Engine and database hosts must retain a single metadata response hop and require IMDSv2."
  }
  assert {
    condition     = length(aws_autoscaling_group.reader) == 0 && length(aws_lb_listener_rule.reader) == 0
    error_message = "Read scaling and routing require the explicit readiness gate."
  }
  assert {
    condition     = !aws_cloudfront_distribution.web.enabled && length(aws_vpc_security_group_ingress_rule.alb_https) == 0 && length(aws_vpc_security_group_ingress_rule.alb_http) == 0 && length(aws_vpc_security_group_ingress_rule.cloudfront_origin) == 0
    error_message = "Public access must remain off by default."
  }
  assert {
    condition     = aws_s3_bucket_public_access_block.web.block_public_policy && aws_s3_bucket_public_access_block.web.restrict_public_buckets && aws_cloudfront_origin_access_control.web.signing_behavior == "always"
    error_message = "The static bucket must be private and use signed OAC requests."
  }
  assert {
    condition     = output.db_connection_budget.total == 188 && output.db_connection_budget.available == 197
    error_message = "Connection accounting must include all pools, fixed rollout allowance and maximum readers."
  }
}

run "reader_scale_and_health_are_separate" {
  command = plan
  variables { enable_read_asg = true }
  assert {
    condition     = length(aws_autoscaling_group.reader) == 1 && aws_autoscaling_group.reader[0].max_size == 2 && aws_autoscaling_group.reader[0].min_size == 1
    error_message = "Only the reader role may autoscale within its explicit bounds."
  }
  assert {
    condition     = aws_launch_template.reader.metadata_options[0].http_put_response_hop_limit == 1 && aws_launch_template.reader.metadata_options[0].http_tokens == "required"
    error_message = "Reader autoscaling must not grant container access to instance metadata credentials."
  }
  assert {
    condition     = aws_autoscaling_group.reader[0].health_check_type == "EC2" && aws_lb_target_group.reader.health_check[0].path == "/actuator/health/read"
    error_message = "Shared DB readiness failure must not trigger ASG instance replacement."
  }
  assert {
    condition     = aws_lb_target_group.engine.health_check[0].path == "/actuator/health/readiness" && aws_lb_target_group.reader.deregistration_delay >= 60
    error_message = "The engine target must select the leader and readers must drain before termination."
  }
  assert {
    condition     = aws_autoscaling_policy.reader_requests[0].target_tracking_configuration[0].predefined_metric_specification[0].predefined_metric_type == "ALBRequestCountPerTarget" && !aws_autoscaling_policy.reader_requests[0].target_tracking_configuration[0].disable_scale_in
    error_message = "CloudWatch target tracking must scale readers out and back in."
  }
  assert {
    condition     = local.read_path_regex == "^/api/v1/(markets|trades|candles/[A-Za-z0-9]+)$"
    error_message = "Order, authentication, local-sequence and WebSocket paths cannot route to readers."
  }
}

run "public_gate_enables_two_origins" {
  command = plan
  variables { enable_public_access = true }
  assert {
    condition     = aws_cloudfront_distribution.web.enabled && toset([for origin in aws_cloudfront_distribution.web.origin : origin.origin_id]) == toset(["static-web", "api-alb"]) && length(aws_cloudfront_distribution.web.ordered_cache_behavior) == 2 && length(aws_vpc_security_group_ingress_rule.cloudfront_origin) == 1
    error_message = "Explicit publication must connect static S3 and HTTPS API/WebSocket origins."
  }
}

run "reject_connection_overcommit" {
  command = plan
  variables { read_capacity = { min = 1, max = 6, requests_per_target = 600 } }
  expect_failures = [terraform_data.requirements]
}

run "reject_mutable_app_image" {
  command = plan
  variables { app_image = "example.invalid/failstop:latest" }
  expect_failures = [var.app_image]
}

run "reject_undersized_append_pool" {
  command = plan
  variables { db_connections = { append_pool = 1 } }
  expect_failures = [var.db_connections]
}
