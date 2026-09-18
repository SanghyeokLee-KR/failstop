# 예시 식별자는 실제 AWS 값이 아니다. IAM·인증서·AMI·버킷·SSM을 확인한 뒤 별도 .tfvars에 넣는다.
name                        = "failstop-ops"
region                      = "ap-northeast-2"
availability_zones          = ["ap-northeast-2a", "ap-northeast-2c"]
ami                         = { id = "ami-0123456789abcdef0", owner_account_id = "123456789012", architecture = "arm64" }
instance_types              = { engine = "c7g.large", database = "t4g.medium", bot = "t4g.medium", reader = "t4g.small" }
instance_profiles           = { engine = "ExistingEngine", database = "ExistingDatabase", bot = "ExistingOperations", reader = "ExistingReader" }
asg_service_linked_role_arn = "arn:aws:iam::123456789012:role/aws-service-role/autoscaling.amazonaws.com/AWSServiceRoleForAutoScaling"
release                     = { bucket = "replace-with-release-bucket", key = "releases/release.tar.gz", version_id = "replace-with-version-id", sha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" }
app_image                   = "example.invalid/failstop@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
role_parameter_names        = { app = "/failstop/app", data = "/failstop/data", standby = "/failstop/standby", bot = "/failstop/bot", reader = "/failstop/reader" }
operator_public_key         = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIReplaceWithYourPublicKey000000000000000000000= operator"
certificate_arn             = "arn:aws:acm:ap-northeast-2:123456789012:certificate/11111111-2222-4333-8444-555555555555"
api_domain                  = "origin.example.invalid"
web                         = { bucket_name = "replace-with-unique-web-bucket", domain = "exchange.example.invalid", certificate_arn = "arn:aws:acm:us-east-1:123456789012:certificate/11111111-2222-4333-8444-555555555555" }
allowed_client_cidrs        = ["198.51.100.0/24"]
enable_public_access        = false
enable_read_asg             = false
# 600은 검증용 예시이며 실제 AWS 부하 측정으로 선택한다. requests/minute/target 단위다.
read_capacity = { min = 1, max = 2, requests_per_target = 600, warmup_seconds = 300 }
