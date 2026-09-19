# AWS 운영 환경 사전 구현

이 폴더는 [ADR 0027](../../docs/reference/adr/0027-msp-operations-and-autoscaling.md)의 역할 분리와 조회 ASG를 구현한다. AWS 계정 없이 구문·모의 계획·부팅 코드의 입력을 검사할 수 있다. 실제 AWS 배포·복구·확장 성공 기록은 아니며 현재 검증 상태는 [로드맵](../../docs/ROADMAP.md)에서 관리한다.

## 배치와 공개 범위

| 구성 | 배치와 동작 |
|---|---|
| VPC | 두 AZ에 public/private subnet 각 하나. ALB와 AZ별 NAT 두 개는 public에 배치한다. |
| 엔진 | private 고정 두 대. 리더 검사에 통과한 노드로 주문·취소·WebSocket을 보낸다. |
| DB | private primary·standby 각 한 대. 비동기 복제·수동 승격이며 ASG 대상이 아니다. |
| 봇·관측 | private 고정 한 대에 봇, Prometheus, Grafana, Alertmanager, 보고 서비스를 둔다. |
| 조회 | private 두 subnet의 ASG. 지정한 조회만 처리하고 엔진을 실행하지 않는다. |
| 웹 | 비공개 S3와 서명하는 OAC. CloudFront의 정적 웹 origin과 HTTPS ALB origin을 구분한다. |

`enable_public_access=false`가 기본이다. ALB 고객·CloudFront·봇 NAT 주소 ingress를 만들지 않고 CloudFront 배포를 비활성화한다. ALB 자체는 internet-facing이며 이 플래그가 private ALB로 바꾸지는 않는다. NAT, ALB, 고정 EC2는 공개 여부와 관계없이 생성 시 비용이 생긴다. `enable_read_asg=false`가 기본이며 DB 마이그레이션과 고정 노드 점검을 마친 뒤 켠다.

공개를 켜면 CloudFront 웹은 인터넷에서 접근할 수 있다. `allowed_client_cidrs`는 직접 ALB 접근에만 적용되며 CloudFront 시청자의 허용 목록이 아니다. 제한 공개가 필요하면 별도 접근 제어를 준비한다. CloudFront prefix list의 가중치가 다른 ALB 규칙 한도를 소모하지 않도록 전용 SG를 붙인다. AWS SSH 22는 열지 않는다. 운영자는 기존 SSM 권한과 포트 전달을 사용해 localhost 2222의 키 전용 SSH에 접속한다.

8080은 ALB에서 앱·조회로, 8081은 ALB 검사와 관측 노드에서만 허용한다. DB 5432는 앱·조회·봇·DB SG에서, DB exporter 9187과 호스트 9100은 관측 SG에서만 허용한다. 보고 수신 9088은 관측 SG 내부에 제한한다. 호스트 nft에도 ALB public subnet 두 개를 `--health-cidr`로 전달한다. 런타임 outbound는 이미지·SSM·보고 통신을 위해 같은 AZ의 NAT로 허용한다.

## 계정 없이 실행할 검사

검증 기준은 Terraform **1.14.9**, AWS provider **6.65.0**이다. provider는 정확한 버전과 lock 파일을 보관한다. 저장소 루트에서 다음 명령을 실행한다. mock provider는 AWS API를 호출하지 않는다.

```bash
terraform -chdir=deploy/terraform fmt -check -recursive
terraform -chdir=deploy/terraform init -backend=false -input=false
terraform -chdir=deploy/terraform validate
terraform -chdir=deploy/terraform test -no-color
python3 -m unittest discover -s deploy/terraform/tests -p test_bootstrap.py -v
node --test deploy/terraform/tests/static-routes.test.cjs
bash deploy/linux/tests/test-linux.sh
```

`init`은 provider 다운로드 통신을 사용하지만 AWS 자격증명이 필요하지 않다. `.tools`, `.terraform`, 실제 `.tfvars`, state, plan과 release 압축 파일은 커밋하지 않는다. 계정이 없는 단계에서는 `apply`하지 않는다. mock 통과는 실제 IAM·AMI·할당량·서비스 호환성의 증거가 아니다.

## AMI와 입력 계약

[example.tfvars](example.tfvars)는 형식 예시다. 실제 값은 별도 파일에 넣는다. 비밀번호·토큰·Teams URL을 Terraform 변수나 userdata에 넣지 않는다. 역할별 SSM SecureString 이름만 넘기고 값은 호스트의 기존 IAM profile로 읽는다.

AMI는 소유 계정과 ID를 검토한 Rocky Linux 10 ARM64 이미지다. `latest` 조회를 하지 않으며 인스턴스 유형의 ARM64 지원을 검사한다. 예시 c7g/t4g 크기와 요청량 600은 측정 결과가 아니다. 운영 AMI에는 다음 조건이 필요하다.

- systemd, Python 3.12 이상, AWS CLI v2, cloud-init, SSM Agent와 시간 동기화.
- 같은 호스트의 Docker Engine과 Compose 플러그인. `docker.service`가 부팅 때 실행되어야 한다.
- openssh-server, sudo, nftables, logrotate, sysstat, curl, ca-certificates, rsync, iproute, coreutils, tar, policycoreutils-python-utils. SELinux는 유지한다.
- ARM64 앱 이미지 digest와 pull 권한. 비공개 ECR이면 root Docker credential helper와 역할별 ECR 읽기 권한을 준비한다. userdata는 레지스트리 로그인을 대신하지 않는다.
- DNS·NAT를 통한 release S3, SSM, 이미지 저장소, 공식 AWS IP 범위, node_exporter 다운로드 경로 접근.

기존 profile은 해당 역할의 `ssm:GetParameter`, 지정 KMS 키의 `kms:Decrypt`, 지정 release version의 `s3:GetObjectVersion`과 SSM 관리 권한이 필요하다. 관측 profile에는 `ec2:DescribeInstances`를 추가한다. DescribeInstances의 태그 필터는 권한 격리 수단이 아니다. ECR·백업 권한은 실제 저장소에 제한한다. 이 모듈은 IAM 역할과 Auto Scaling service-linked role을 생성하지 않는다.

Grafana에서 CloudWatch를 조회할 때는 관측 bot의 기존 profile에 [CloudWatch 읽기 권한](../observability/grafana/cloudwatch-read-policy.json)을 검토해 추가한다. 이 예제는 서울 리전 지표·리소스 검색용이며 권한 부여를 자동 실행하지 않는다. 관측 bot의 IMDSv2 응답 hop limit만 2로 설정해 같은 호스트의 Grafana 컨테이너가 instance profile을 사용하게 한다. 고정 앱·DB와 조회 launch template은 hop limit 1을 유지하며 모든 노드는 IMDSv2 토큰을 요구한다. AWS 계정이 없는 현재는 이 설정의 모의 검증만 가능하고 실제 자격증명 취득·조회 성공은 별도 확인이 필요하다.

기존 ACM 인증서는 ALB용 배포 region과 CloudFront용 `us-east-1`에 필요하다. `api_domain`은 ALB origin의 DNS이며 `web.domain`과 달라야 한다. 기존 Route 53 zone을 넘기면 alias를 만들고, 생략하면 DNS 담당자가 준비한다. 인증서 발급·도메인 검증·release 버킷 생성은 별도다.

## 코드와 비밀의 부팅 경로

```bash
python3 deploy/terraform/package-release.py --output deploy/terraform/release-reviewed.tar.gz
```

[공유 허용 목록](../linux/runtime-files.py)으로 배포 코드, 관측 규칙·대시보드, DB 쿼리·복구 판정 SQL만 묶는다. `.env`, 숨김 상태, 보고 JSON, 수집 대상 JSON, DB 덤프는 제외한다. Linux 설치 시 실행 스크립트는 0755로 둔다. 기존 versioning S3에 압축 파일을 업로드하고 SHA256, object key와 **version ID**를 입력한다. 업로드와 비밀 등록은 계정을 받은 뒤 수행한다.

userdata는 특정 object version을 받고 SHA256을 비교한 뒤 일반 파일·디렉터리만 추출한다. [bootstrap-node.sh](bootstrap-node.sh)는 IMDSv2로 private IP를 구하고 SecureString을 root 전용 임시 파일로 받는다. dotenv를 셸 명령으로 실행하지 않는다. 이미지·DB 예산·주소·포트를 고정한 뒤 설정 검사기, Linux 계정·SSH·방화벽, 호스트 exporter와 Compose 설치기에 연결한다. 비밀 내용은 출력하지 않으며 임시 파일은 종료 시 제거한다.

앱·reader의 trusted proxy는 ALB subnet과 공식 `CLOUDFRONT_ORIGIN_FACING` IPv4만 정규식으로 만든다. ALB는 X-Forwarded-For append를 유지한다. 오른쪽부터 실제 사용자를 찾으므로 CloudFront와 직접 ALB 요청에서 사용자가 앞에 붙인 주소를 신뢰하지 않는다. 공식 목록 다운로드·검증 실패 시 부팅을 중단한다. AWS IP 변경 알림을 받고 승인된 release로 앱을 갱신해야 한다. 체인 모의 검사는 실제 Tomcat 통합 검사를 대신하지 않는다. [AWS IP 범위](https://docs.aws.amazon.com/vpc/latest/userguide/aws-ip-ranges.html), [CloudFront 전달 주소](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/RequestAndResponseBehaviorCustomOrigin.html)

고정 노드 userdata 변경만으로 기존 호스트에 코드가 재설치되지는 않는다. release 변경 시 노드별 설치·검증을 수행한다. 엔진·DB는 `prevent_destroy`로 보호하고 DB root EBS도 종료 시 보존한다. 이는 백업이 아니며 보존 볼륨에는 비용이 발생한다.

## 계정을 받은 뒤 실행 순서

1. 기존 권한·AMI·DNS·인증서·할당량·비용을 확인하고 암호화·잠금이 적용된 Terraform state 보관소를 준비한다. 코드는 backend를 강제하지 않는다.
2. 역할별 env를 [배포 안내](../README.md)와 [검사기](../check-config.py)로 검사해 SSM SecureString으로 등록한다. reader는 별도 DB 계정을 쓴다. 두 enable 플래그를 false로 유지한 계획을 검토한다.
3. primary·standby의 실제 복제를 확인한다. 앱·봇은 설치·enable만 하고 즉시 시작하지 않는다. 별도 migrate 역할로 스키마를 한 번 적용하고 DB 권한을 구성한 뒤 앱을 시작한다. ASG 부팅에서 마이그레이션하지 않는다.
4. 봇 노드에서 [관측](../observability/README.md)과 [보고](../reporting/README.md), DB 노드에서 postgres_exporter와 [백업](../backup/README.md)을 설치한다. Teams·Grafana·reporter token은 보호 파일로 제공한다. 이 모듈은 이 비밀이나 AI 모델을 선택하지 않는다.
5. 관측 Compose의 실제 bridge CIDR를 확인하고 관측 노드 방화벽에 기존 monitor private IP와 추가 `--monitor-cidr BRIDGE_CIDR`를 함께 전달한다. 같은 호스트 컨테이너는 bridge 주소로 호스트 exporter를 호출할 수 있다. `/0`이나 전체 Docker 범위를 허용하지 않는다. 다른 호스트는 관측 private IP `/32`만 허용한다.
6. 기존 배포 절차대로 운영 웹 export와 관리자 산출물 제거를 수행해 웹 S3에 업로드한다. DNS·TLS를 확인하고 공개 플래그를 켠다. ALB `/actuator`, `/admin`, `/harness`, `/internal` 접근 거절도 확인한다.
7. 조회 ASG를 켜고 처음 한 대에서 준비 완료·읽기 권한·쓰기 거절을 확인한다. 부하 증가·증설·ALB 등록·축소·연결 반환을 측정해 목표 요청량과 warmup을 정한다.

첫 DB 준비 전에 재부팅하면 enable된 앱·봇도 시작을 시도한다. 초기 구축 중에는 재부팅 전에 시작 조건을 확인한다. 스키마 검사를 자동 마이그레이션이나 repair로 우회하지 않는다.

## 확장과 관측 판정

ALB는 GET/HEAD의 `/api/v1/markets`, `/api/v1/trades`, `/api/v1/candles/{unit}`만 reader로 보낸다. 주문·인증·호가·티커·WebSocket은 엔진 경로다. reader health는 `/actuator/health/read`의 DB·ping이며 리더 여부를 요구하지 않는다. ASG 교체 검사는 **EC2**로 두어 공통 DB 장애로 전체를 교체하지 않는다. 개별 앱·부팅 실패는 ALB·서비스 관측으로 감지하고 운영자가 복구한다. [ASG 검사](https://docs.aws.amazon.com/autoscaling/ec2/userguide/health-checks-overview.html)

target tracking은 `ALBRequestCountPerTarget`의 대상당 분당 요청량을 쓰며 축소를 허용한다. AWS가 desired capacity를 소유하므로 Terraform이 이를 min으로 덮지 않는다. reader refresh는 추가 인스턴스 없이 교체하므로 min=1에서는 조회 중단이 생길 수 있다. 모든 target이 unhealthy인 경우 ALB fail-open과 backend 리더 권한 확인도 실제 시험에 포함한다. [target tracking](https://docs.aws.amazon.com/autoscaling/ec2/userguide/as-scaling-target-tracking.html)

DB 예산에는 엔진 두 대와 갱신 여유 한 대의 앱·append·lease 풀, reader 상한, 봇, 운영·복제 연결을 넣는다. 기본 예시는 전체 188, 사용 가능 197이며 reader max=6이면 거절한다. ASG 크기와 DB 풀을 따로 증설하지 않는다. 수동 노드 추가도 예산을 다시 검토한다.

관측 노드는 30초마다 EC2 태그·running 상태를 읽어 호스트·앱·DB·보고·외부 probe 파일을 갱신한다. reader 축소 시 대상에서도 빠진다. AWS 조회 실패·빈 결과는 정상 빈 목록으로 덮지 않고 실패와 마지막 성공 시각을 textfile 지표로 남긴다. 갱신 지연·exporter 중단·대상 누락을 구분해 경보한다. discovery는 읽기 API만 사용한다.

AWS API의 태그 필터와 반환 목록의 `FailstopCluster` 검사를 함께 적용한다. 고객 Spring 대상은 `Role=app|reader`만 등록하고 봇의 앱 지표는 넣지 않는다. 관측 노드 자체의 호스트·보고 서비스는 경로 점검용으로 남긴다. 로컬에서 사용한 Prometheus·Grafana·보고 코드는 유지하며 [환경 설정 전환](../observability/README.md#로컬과-aws의-관측-경로를-맞춘다)에서 사설 접속 주소·`environment=aws`·보호 파일 경로를 바꾼다. AWS에서는 Windows·WSL bridge를 사용하지 않는다.

ALB·ASG·EBS의 관리형 지표는 [AWS 관측 Compose 추가 설정](../compose.observability-aws.yml)으로 Grafana CloudWatch 데이터 소스에 연결한다. `terraform output -json monitoring_dimensions`의 `load_balancer`, `reader_target_group`은 CloudWatch dimension에 넣을 ARN suffix이고 `reader_asg`는 실제 ASG 이름이다. ASG를 켜지 않았다면 `reader_asg`는 null이다. 전체 ARN이나 ALB DNS 이름으로 대체하지 않는다. 이 출력과 `region`을 [관측 화면의 AWS URL 변수](../observability/README.md#aws-관리형-지표를-연결한다)에 연결한다. EBS volume ID는 점검할 고객 서버의 실제 연결 볼륨을 확인해 별도로 지정한다. 로컬에서는 AWS 데이터 소스를 설치하지 않고 접힌 CloudWatch 행을 유지한다. AWS 계정과 실제 자원이 준비되기 전 화면은 미연결 상태이며 조회 결과를 정상으로 만들지 않는다.

## 비용과 실환경 검증

`cost_inventory`는 EC2 고정 5대와 reader 상한, NAT 2개와 public IPv4 2개, ALB, EBS 용량, CloudFront·웹 버킷 수량이다. 가격이나 크레딧 잔액은 아니다. region·크기·기간·전송량을 넣어 AWS 계산기로 확인하고 예산 알림을 준비한다. release·백업 S3, 스냅샷, DNS, 요청·전송, CloudWatch, 세금도 포함한다. AZ별 NAT는 기존 가용성 계획을 따른 선택이며 소규모 실습에서는 큰 고정 비용이 될 수 있다.

계정이 준비되면 IAM 거절 조건, ARM64 부팅·pull, nft·SG 연결, CloudFront 쿠키·인증·WebSocket·사용자 IP, ALB 리더 전환, 확장·축소, DB 수동 복구·백업 복원, 재부팅 후 서비스·관측을 실제 검증한다. 정리할 때는 고정 노드 보호, DB 보존 EBS, versioned S3 object를 따로 확인하며 보호를 무조건 해제해 일괄 삭제하지 않는다.
