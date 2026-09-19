terraform {
  required_version = ">= 1.9, < 2.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "= 6.65.0"
    }
  }
}

provider "aws" {
  region = var.region
  default_tags {
    tags = {
      Project         = "failstop"
      FailstopCluster = var.name
      ManagedBy       = "terraform"
    }
  }
}
