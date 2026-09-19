resource "aws_lb" "api" {
  name                       = var.name
  load_balancer_type         = "application"
  internal                   = false
  ip_address_type            = "ipv4"
  security_groups            = [aws_security_group.role["alb"].id, aws_security_group.role["cloudfront-origin"].id]
  subnets                    = [for subnet in aws_subnet.public : subnet.id]
  idle_timeout               = 120
  drop_invalid_header_fields = true
  desync_mitigation_mode     = "strictest"
}

resource "aws_lb_target_group" "engine" {
  name                 = "${var.name}-engine"
  vpc_id               = aws_vpc.main.id
  protocol             = "HTTP"
  port                 = 8080
  target_type          = "instance"
  deregistration_delay = 90
  health_check {
    protocol            = "HTTP"
    port                = "8081"
    path                = "/actuator/health/readiness"
    matcher             = "200"
    interval            = 5
    timeout             = 3
    healthy_threshold   = 2
    unhealthy_threshold = 2
  }
}

resource "aws_lb_target_group" "reader" {
  name                 = "${var.name}-reader"
  vpc_id               = aws_vpc.main.id
  protocol             = "HTTP"
  port                 = 8080
  target_type          = "instance"
  deregistration_delay = 90
  health_check {
    protocol            = "HTTP"
    port                = "8081"
    path                = "/actuator/health/read"
    matcher             = "200"
    interval            = 10
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_target_group_attachment" "engine" {
  for_each         = { for name, node in local.fixed_nodes : name => node if node.role == "app" }
  target_group_arn = aws_lb_target_group.engine.arn
  target_id        = aws_instance.fixed[each.key].id
  port             = 8080
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.api.arn
  port              = 80
  protocol          = "HTTP"
  default_action {
    type = "redirect"
    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.api.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.engine.arn
  }
}

resource "aws_lb_listener_rule" "deny_management" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 10
  action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      message_body = "Not found"
      status_code  = "404"
    }
  }
  condition {
    path_pattern {
      regex_values = ["^/(actuator|admin|harness|internal)(/.*)?$"]
    }
  }
}

resource "aws_lb_listener_rule" "reader" {
  count        = var.enable_read_asg ? 1 : 0
  listener_arn = aws_lb_listener.https.arn
  priority     = 20
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.reader.arn
  }
  condition {
    http_request_method { values = ["GET", "HEAD"] }
  }
  condition {
    path_pattern { regex_values = [local.read_path_regex] }
  }
}

resource "aws_route53_record" "api" {
  count   = var.dns_zone_id == null ? 0 : 1
  zone_id = var.dns_zone_id
  name    = var.api_domain
  type    = "A"
  alias {
    name                   = aws_lb.api.dns_name
    zone_id                = aws_lb.api.zone_id
    evaluate_target_health = true
  }
}
