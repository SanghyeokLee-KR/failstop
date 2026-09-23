output "api_endpoint" { value = "https://${var.api_domain}" }
output "alb_dns_name" { value = aws_lb.api.dns_name }
output "monitoring_dimensions" {
  description = "CloudWatch dashboard dimensions for the deployed customer resources."
  value = {
    load_balancer       = aws_lb.api.arn_suffix
    engine_target_group = aws_lb_target_group.engine.arn_suffix
    reader_target_group = aws_lb_target_group.reader.arn_suffix
    reader_asg          = try(aws_autoscaling_group.reader[0].name, null)
    region              = var.region
  }
}
output "fixed_private_addresses" { value = { for name, node in local.fixed_nodes : name => node.ip } }
output "fixed_instance_ids" { value = { for name, node in aws_instance.fixed : name => node.id } }
output "read_asg_name" { value = try(aws_autoscaling_group.reader[0].name, null) }
output "web_endpoint" { value = "https://${var.web.domain}" }
output "web_bucket" { value = aws_s3_bucket.web.id }
output "cloudfront_distribution_id" { value = aws_cloudfront_distribution.web.id }
output "db_connection_budget" {
  value = { total = local.connection_budget, available = local.available_connections, headroom = local.available_connections - local.connection_budget, reader_maximum = var.read_capacity.max }
}
output "cost_inventory" {
  description = "Quantities only; not a price estimate or evidence of remaining credits."
  value = {
    region                     = var.region
    fixed_ec2                  = 5
    read_ec2_min               = var.enable_read_asg ? var.read_capacity.min : 0
    read_ec2_max               = var.read_capacity.max
    nat_gateways               = var.enable_nat_gateway ? 2 : 0
    public_ipv4_for_nat        = var.enable_nat_gateway ? 2 : 0
    node_public_ipv4_at_max    = var.enable_nat_gateway ? 0 : 5 + var.read_capacity.max
    application_load_balancers = 1
    root_ebs_gib_at_read_max   = 2 * var.root_volume_gib.engine + 2 * var.root_volume_gib.database + var.root_volume_gib.bot + var.read_capacity.max * var.root_volume_gib.reader
    instance_types             = var.instance_types
    cloudfront_distributions   = 1
    static_web_buckets         = 1
    excluded                   = ["release and backup S3", "snapshots", "DNS and domain", "data transfer and request charges", "CloudWatch", "tax"]
  }
}
