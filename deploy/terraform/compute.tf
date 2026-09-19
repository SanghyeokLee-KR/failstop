data "aws_ami" "selected" {
  owners = [var.ami.owner_account_id]
  filter {
    name   = "image-id"
    values = [var.ami.id]
  }
  filter {
    name   = "architecture"
    values = [var.ami.architecture]
  }
  filter {
    name   = "state"
    values = ["available"]
  }
}

data "aws_ec2_instance_type" "selected" {
  for_each      = var.instance_types
  instance_type = each.value
}

resource "aws_instance" "fixed" {
  for_each                    = local.fixed_nodes
  ami                         = data.aws_ami.selected.id
  instance_type               = var.instance_types[each.value.type]
  subnet_id                   = aws_subnet.private[each.value.az].id
  private_ip                  = each.value.ip
  associate_public_ip_address = false
  vpc_security_group_ids      = [aws_security_group.role[each.value.type].id]
  iam_instance_profile        = var.instance_profiles[each.value.type]
  monitoring                  = true
  user_data = templatefile("${path.module}/user-data.sh.tftpl", {
    configuration = base64encode(jsonencode(merge(local.bootstrap, {
      role           = each.value.role
      parameter_name = var.role_parameter_names[each.value.role]
      start_service  = contains(["data", "standby"], each.value.role)
    })))
  })
  metadata_options {
    http_endpoint = "enabled"
    http_tokens   = "required"
    # 관측 노드의 Grafana 컨테이너만 EC2 역할로 CloudWatch를 조회한다.
    http_put_response_hop_limit = each.value.role == "bot" ? 2 : 1
    instance_metadata_tags      = "disabled"
  }
  root_block_device {
    volume_type           = "gp3"
    volume_size           = var.root_volume_gib[each.value.type]
    encrypted             = true
    delete_on_termination = each.value.type != "database"
  }
  lifecycle {
    prevent_destroy = true
    precondition {
      condition     = contains(data.aws_ec2_instance_type.selected[each.value.type].supported_architectures, var.ami.architecture)
      error_message = "Instance type must support the selected ARM64 AMI."
    }
  }
  depends_on = [terraform_data.requirements, aws_route_table_association.private]
  tags       = { Name = "${var.name}-${each.key}", Role = each.value.role }
}

resource "aws_launch_template" "reader" {
  name_prefix            = "${var.name}-reader-"
  image_id               = data.aws_ami.selected.id
  instance_type          = var.instance_types.reader
  update_default_version = false
  iam_instance_profile { name = var.instance_profiles.reader }
  network_interfaces {
    associate_public_ip_address = false
    security_groups             = [aws_security_group.role["reader"].id]
    delete_on_termination       = true
  }
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
    instance_metadata_tags      = "disabled"
  }
  block_device_mappings {
    device_name = data.aws_ami.selected.root_device_name
    ebs {
      volume_type           = "gp3"
      volume_size           = var.root_volume_gib.reader
      encrypted             = true
      delete_on_termination = true
    }
  }
  monitoring { enabled = true }
  user_data = base64encode(templatefile("${path.module}/user-data.sh.tftpl", {
    configuration = base64encode(jsonencode(merge(local.bootstrap, {
      role           = "reader"
      parameter_name = var.role_parameter_names.reader
      start_service  = true
    })))
  }))
  tag_specifications {
    resource_type = "instance"
    tags          = { Name = "${var.name}-reader", Project = "failstop", FailstopCluster = var.name, Role = "reader" }
  }
  lifecycle {
    precondition {
      condition     = contains(data.aws_ec2_instance_type.selected["reader"].supported_architectures, var.ami.architecture)
      error_message = "Reader instance type must support the ARM64 AMI."
    }
  }
  depends_on = [terraform_data.requirements]
}

resource "aws_autoscaling_group" "reader" {
  count                     = var.enable_read_asg ? 1 : 0
  name                      = "${var.name}-reader"
  min_size                  = var.read_capacity.min
  desired_capacity          = var.read_capacity.min
  max_size                  = var.read_capacity.max
  vpc_zone_identifier       = [for subnet in aws_subnet.private : subnet.id]
  target_group_arns         = [aws_lb_target_group.reader.arn]
  health_check_type         = "EC2"
  health_check_grace_period = var.read_capacity.warmup_seconds
  default_instance_warmup   = var.read_capacity.warmup_seconds
  service_linked_role_arn   = var.asg_service_linked_role_arn
  enabled_metrics           = ["GroupDesiredCapacity", "GroupInServiceInstances", "GroupPendingInstances", "GroupTerminatingInstances", "GroupTotalInstances"]
  wait_for_capacity_timeout = "15m"
  launch_template {
    id      = aws_launch_template.reader.id
    version = tostring(aws_launch_template.reader.latest_version)
  }
  # stop-before-start keeps refresh capacity within the DB connection budget.
  instance_maintenance_policy {
    min_healthy_percentage = 0
    max_healthy_percentage = 100
  }
  instance_refresh {
    strategy = "Rolling"
    preferences {
      min_healthy_percentage = 0
      max_healthy_percentage = 100
      instance_warmup        = var.read_capacity.warmup_seconds
      skip_matching          = true
    }
  }
  lifecycle { ignore_changes = [desired_capacity] }
  depends_on = [aws_lb_listener.https, terraform_data.requirements, aws_route_table_association.private]
  tag {
    key                 = "FailstopCluster"
    value               = var.name
    propagate_at_launch = true
  }
  tag {
    key                 = "Role"
    value               = "reader"
    propagate_at_launch = true
  }
}

resource "aws_autoscaling_policy" "reader_requests" {
  count                  = var.enable_read_asg ? 1 : 0
  name                   = "${var.name}-reader-requests"
  autoscaling_group_name = aws_autoscaling_group.reader[0].name
  policy_type            = "TargetTrackingScaling"
  target_tracking_configuration {
    target_value     = var.read_capacity.requests_per_target
    disable_scale_in = false
    predefined_metric_specification {
      predefined_metric_type = "ALBRequestCountPerTarget"
      resource_label         = "${aws_lb.api.arn_suffix}/${aws_lb_target_group.reader.arn_suffix}"
    }
  }
}
