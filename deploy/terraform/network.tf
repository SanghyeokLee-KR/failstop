resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = { Name = var.name }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
}

resource "aws_subnet" "public" {
  for_each                = local.azs
  vpc_id                  = aws_vpc.main.id
  availability_zone       = each.value
  cidr_block              = local.public_cidrs[each.key]
  map_public_ip_on_launch = false
  tags                    = { Name = "${var.name}-public-${each.key}" }
}

locals {
  # ADR 0030: 실습 기본값은 NAT 없이 노드가 자기 공인 IPv4로 나간다. true일 때만 AZ별 NAT를 만든다.
  nat_azs = { for key, az in local.azs : key => az if var.enable_nat_gateway }
}

# 고정 노드와 조회 노드의 subnet이다. NAT가 없으면 아래 경로가 Internet Gateway를 가리킨다.
# ALB subnet과 나눈 배치, 고정 주소와 ALB subnet 기준의 신뢰 프록시 대역은 NAT 여부와 관계없이 같다.
resource "aws_subnet" "private" {
  for_each                = local.azs
  vpc_id                  = aws_vpc.main.id
  availability_zone       = each.value
  cidr_block              = local.private_cidrs[each.key]
  map_public_ip_on_launch = false
  tags                    = { Name = "${var.name}-${var.enable_nat_gateway ? "private" : "node"}-${each.key}" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }
}

resource "aws_route_table_association" "public" {
  for_each       = local.azs
  subnet_id      = aws_subnet.public[each.key].id
  route_table_id = aws_route_table.public.id
}

resource "aws_eip" "nat" {
  for_each = local.nat_azs
  domain   = "vpc"
  tags     = { Name = "${var.name}-nat-${each.key}" }
}

resource "aws_nat_gateway" "main" {
  for_each      = local.nat_azs
  allocation_id = aws_eip.nat[each.key].id
  subnet_id     = aws_subnet.public[each.key].id
  depends_on    = [aws_internet_gateway.main]
  tags          = { Name = "${var.name}-nat-${each.key}" }
}

resource "aws_route_table" "private" {
  for_each = local.azs
  vpc_id   = aws_vpc.main.id
  route {
    cidr_block     = "0.0.0.0/0"
    gateway_id     = var.enable_nat_gateway ? null : aws_internet_gateway.main.id
    nat_gateway_id = var.enable_nat_gateway ? aws_nat_gateway.main[each.key].id : null
  }
}

resource "aws_route_table_association" "private" {
  for_each       = local.azs
  subnet_id      = aws_subnet.private[each.key].id
  route_table_id = aws_route_table.private[each.key].id
}
