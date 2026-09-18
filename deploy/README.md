# Linux 운영과 AWS 배포 안내

이 폴더에는 앱·조회·DB·봇/관측의 역할별 Compose와 Linux 운영·DB 복구·보고 도구가 있다. 정본 작업 브랜치는 `codex/docs-linux-operations`다. 루트 `compose.yaml`은 기존 로컬 개발 검증용이므로 AWS에 그대로 옮겨 쓰지 않는다.

2026-09-18부터 AWS 단계의 목표는 MSP 운영·관제, Linux·네트워크·DB 운영, 조회 계층 EC2 자동 확장, AI 일일·장애 보고와 Microsoft Teams 연계다. 기존 노드 파일은 그 목표가 구현됐다는 뜻이 아니다. 수동 확장만 허용했던 정책은 [ADR 0027](../docs/reference/adr/0027-msp-operations-and-autoscaling.md)로 대체했고, 로컬 카오스는 과거 개발 검증 자료로 남긴다.

AWS 계정은 아직 제공되지 않았다. 로컬 실행과 실제 AWS 완료를 구분하며, 현재 검증 근거와 남은 작업은 [로드맵](../docs/ROADMAP.md)에 있다.

전체 구조와 배포 착수 조건은 [0099 현재 구조와 AWS 배포 판단](../docs/reference/adr/0099-current-architecture.md)에서 먼저 읽는다. 개별 ADR을 시간순으로 읽으며 현재 결정을 다시 조합할 필요가 없도록, 채택한 구조와 후속 변경을 한곳에 모았다.

## 사용할 파일

| 파일 | 역할 |
|---|---|
| [compose.node-app.yml](compose.node-app.yml) | 앱 노드. 이미지, DB 주소, 비밀 값과 허용 오리진을 외부에서 받는다. |
| [compose.node-data.yml](compose.node-data.yml) | PostgreSQL primary 또는 standby 노드. 비동기 복제 설정을 포함한다. |
| [compose.node-bot.yml](compose.node-bot.yml) | 실제 주문 API를 호출하는 봇 노드. |
| [compose.node-observability.yml](compose.node-observability.yml), [AWS 관측 overlay](compose.observability-aws.yml) | Prometheus·Grafana·Alertmanager와 AWS 전용 CloudWatch 데이터 소스. |
| [pg-init-replication.sh](pg-init-replication.sh) | primary의 복제 계정과 슬롯 준비. |
| [pg-standby-entrypoint.sh](pg-standby-entrypoint.sh) | base backup을 받아 standby 시작. |
| [.env.example](.env.example) | 배포 변수 이름과 예시. 실제 비밀 값은 별도로 관리한다. |
| [compose.node-read.yml](compose.node-read.yml) | 엔진과 쓰기 경로를 차단한 조회 역할. |
| [compose.migrate.yml](compose.migrate.yml) | 스키마 적용, 초기 카탈로그·빈 시스템 계정, 분리 로그인 준비. |
| [check-config.py](check-config.py) | 역할별 필수 값·digest·사설 바인딩·DB 풀 전체 예산 검사. |
| [Linux 도구](linux/README.md) | 계정·SSH·권한·systemd·로그·방화벽·디스크 점검. |
| [관측 도구](observability/README.md) | 호스트·DB exporter, 기존 Grafana와 Teams 기본 알림. |
| [DB 실습](lab/README.md), [백업 도구](backup/README.md) | 격리·수동 승격·전환·재편입과 실제 백업 복원. |
| [운영 보고](reporting/README.md) | 매일 09:00 KST·장애 보고, 영속 재전송, 2쪽 PDF·HTML 자동 제공과 선택적 Gemini 분석. |
| [Terraform](terraform/README.md) | AWS 네트워크·역할별 배치·조회 ASG 사전 구현. |

`compose.base.yml`은 없다. 각 노드의 파일을 직접 사용한다. 앱 이미지는 태그만으로 식별하지 않고 배포할 digest를 확정한다.

`compose.node-edge.yml`, `nginx-edge.conf.template`과 인증서 스크립트는 ALB 채택 전 구성이다. 현재 AWS 진입 계층으로 그대로 사용하지 않는다.

## 배포 구조를 확인할 문서

- [ADR 0010](../docs/reference/adr/0010-onprem-to-aws-deployment.md): 외부 접속 가능한 AWS 배포와 실험 환경 분리.
- [ADR 0011](../docs/reference/adr/0011-cloudfront-two-origins.md): 정적 웹과 API를 CloudFront 뒤에서 연결하는 구조.
- [ADR 0027](../docs/reference/adr/0027-msp-operations-and-autoscaling.md): MSP 운영과 조회 계층 자동 확장, Teams·AI 보고. ADR 0015의 수동 확장 정책을 대체한다.
- [ADR 0016](../docs/reference/adr/0016-observability-on-bot-node.md): 봇 노드에 관측 도구를 두는 결정. 관측 Compose와 자원 한도를 함께 적용한다.
- [ADR 0020](../docs/reference/adr/0020-managed-nat-gateway.md): 관리형 NAT Gateway 채택.
- [ADR 0024](../docs/reference/adr/0024-async-two-node-replication.md): PostgreSQL 비동기 복제와 수동 승격.

여러 과거 문서가 인용하는 배포·비용 기획안 원본은 현재 작업 폴더에 없다. 이전 내용을 대조해야 한다면 `git show af90bca^:제출_20260901/기획안_원본.md`로 읽을 수 있다. 위 결정 기록과 현재 설정을 바탕으로 배포 전 구성을 확정해야 하며, 이 안내만으로 누락된 세부 계약이 복원된 것은 아니다.

## AWS 사용 조건

사용자 설명에 따르면 IAM을 제외한 AWS 서비스는 사용할 수 있을 것으로 예상된다. 이전의 NAT Gateway, Route 53, ACM, Systems Manager 일괄 제한은 현재 전제로 삼지 않는다. 실제 계정 권한은 아직 확인하지 않았다.

IAM 제약 때문에 기존 역할을 사용할 수 있는지, 추가 역할 설정을 학교 관리자에게 요청해야 하는지 확인이 필요하다. 사용자가 밝힌 지원금은 40만 원이다. 과거 ADR의 50만 원 전제를 현재 예산으로 사용하지 않는다. 실제 지급·크레딧 잔액·청구액과 배포 착수 가능 여부는 조회하지 않았다.

## Linux 실행 환경

기존 [ADR 0010](../docs/reference/adr/0010-onprem-to-aws-deployment.md)은 Rocky Linux 10 계열과 Graviton ARM64를 전제로 한다. 로컬 실습은 Ubuntu WSL·x86_64다. apt/dnf, ssh/sshd, AppArmor/SELinux와 firewalld 차이는 [Linux 안내](linux/README.md)에 정리했다. AWS AMI 출처·ID, ARM64 이미지 digest와 실제 재부팅 후 서비스 기동은 별도로 검증한다. WSL의 systemd timer는 해당 WSL이 실행 중일 때 동작하며 Windows 상시 가동 예약을 대신하지 않는다.

## 녹화를 위한 운영 기간

발표는 실제 시연 영상과 캡처를 PPTX에 넣어 준비한다. [교수님께 보고한 구성](../아키텍쳐/README.md)을 기준으로 로컬에서 절차를 연습한 뒤 AWS 검증과 촬영을 모아서 진행한다. 예산에 맞추려고 DB나 NAT 수를 임의로 줄이면 보고안과 다른 구성의 실험이 된다. 우선 가동 기간과 부하·확장 노드의 사용 시간을 관리한다.

보고본의 수동 확장 표시는 과거 계획이며 현재 요구는 AWS 자동 확장이다. 매칭 리더와 DB는 고정된 역할이고 조회 노드만 확장한다. 최대 대수·교체 여유의 DB 연결 예산과 비용도 합산한다. 새 Linux 관측·Teams·보고 구성까지 실제 호스트에 배포하고 검증해야 한다.

자원을 만들기 전에 예상 사용 시간, 재촬영 여유, 보존할 데이터, 종료일과 철거·재생성 절차를 정한다. 기존 결정의 외부 상시 접속 조건이 영상 발표로 자동 면제된 것은 아니므로 필요한 접속 유지 기간을 별도로 확인한다. 40만 원 안에 드는지는 실제 규격·볼륨·시간·전송량과 잔액을 넣어 다시 계산해야 한다.

EC2를 중지하는 것만으로 전체 과금이 끝나지 않는다. 남은 EBS 볼륨, NAT Gateway·ALB 등의 비용을 확인하고, 결과와 원자료를 보관한 뒤 불필요한 자원을 정리한다. NAT 삭제 후에도 Elastic IP는 별도로 해제해야 한다. 이번 문서 정리에서는 자원을 생성·중지·삭제하지 않았다. [EC2 중지](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/how-ec2-instance-stop-start-works.html), [NAT 삭제](https://docs.aws.amazon.com/vpc/latest/userguide/nat-gateway-working-with.html), [ALB 삭제](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/load-balancer-delete.html)

## 앱 설정

[앱 노드 설정](compose.node-app.yml)의 기본 프로파일은 `prod`다. 아래 값을 준비한다.

- DB 주소와 계정: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`.
- 암호화·회원 인증: `MASTER_KEY_BASE64`, `PASSWORD_PEPPER_BASE64`, `SESSION_SEED_BASE64`.
- 브라우저 접근: `CORS_ALLOWED_ORIGINS`, `WS_ALLOWED_ORIGINS`.
- 실행 이미지와 신뢰 프록시: `APP_IMAGE`, `EXCHANGE_TRUSTED_PROXIES`.

마이그레이션은 같은 앱 이미지의 별도 명령으로 실행한다. 일반 앱·조회·봇은 Flyway를 끈다. 첫 배포는 primary 기동 후 아래 명령을 각각 성공시킨 뒤 standby와 앱을 시작한다. 최초 카탈로그는 자산·마켓·잔고 0의 SYSTEM 계정만 만들며 데모 사용자·회원·가상 자금을 만들지 않는다.

```bash
python3 deploy/check-config.py --env /etc/failstop/migrate.env --role migrate
docker compose --env-file /etc/failstop/migrate.env -f deploy/compose.migrate.yml run --rm migrate
docker compose --env-file /etc/failstop/migrate.env -f deploy/compose.migrate.yml run --rm catalog-seed
docker compose --env-file /etc/failstop/migrate.env -f deploy/compose.migrate.yml run --rm provision
```

기존 DB 업그레이드는 V41 인덱스·V44 래치·V46 이벤트 대역의 [ADR 0022 적용 조건](../docs/reference/adr/0022-market-partitioned-command-journal.md)을 따르며 쓰기 정지 창과 전체 엔진 버전 정렬이 선행된다. 적용한 Flyway 파일이나 체크섬을 고치지 않는다. prod API 8080과 관리 8081은 분리되며 관리 포트와 하네스 API를 공개하지 않는다. `prod,admin`은 별도 운영 인증서가 필요하다.

`EXCHANGE_TRUSTED_PROXIES`는 CIDR이 아닌 Java 정규식이다. 실제 ALB 서브넷만 신뢰하도록 지정한다. root 소유의 환경 파일과 배포 파일을 [Linux 설치기](linux/README.md)로 설치하고 역할별 설정 검사를 거친다. 로컬 태그 이미지는 명시적인 `--local-image`에서만 허용한다.

컨테이너 생존 확인과 요청 수용 여부도 다르다. 팔로워는 살아 있어도 readiness가 접수 불가를 반환할 수 있으므로, 생존 확인에는 liveness를 사용한다.

[조회 Compose](compose.node-read.yml)는 `prod,read`, 엔진·Flyway·하네스·관리 기능 비활성화와 읽기 전용 DB 로그인을 강제한다. GET/HEAD의 markets·trades·candles만 제공하며 회원·주문·취소·WS·호가·티커를 거절한다. ALB는 8081의 `/actuator/health/read`를 검사하고 ASG는 EC2 health로 교체를 판단한다. 공통 DB 장애에서 read 503과 liveness 200을 구분한다. 호가·티커는 WebSocket 순번과 연결되므로 고정 엔진 경로에 유지한다. [ADR 0027](../docs/reference/adr/0027-msp-operations-and-autoscaling.md)

## 웹과 도메인

기존 계획의 도메인은 `sang.it.kr`이다. 현재 DNS 연결 여부는 조회하지 않았다. 배포할 도메인을 확정한 뒤 다음 값을 웹 빌드에 넣는다.

- `NEXT_PUBLIC_DATASOURCE=http`.
- `NEXT_PUBLIC_API_BASE`: 같은 오리진을 사용하면 빈 문자열.
- `NEXT_PUBLIC_WS_BASE`: 배포 도메인의 절대 `wss://` 주소.

공개 번들에서 관리 화면을 제거하는 절차는 [웹 README](../web/README.md)에 있다. 도메인 변경 시 웹을 다시 빌드하고 서버의 CORS·WebSocket 허용 오리진도 맞춘다.

## 배포 전 확인

[DB 노드](compose.node-data.yml)는 기본 루프백 바인딩이다. 여러 호스트에서는 `DB_BIND_IP`를 해당 사설 IPv4로, 별도 standby의 `STANDBY_LISTEN_PORT`를 5432로 지정하고 SG·호스트 방화벽을 맞춘다. 복제 계정·pg_hba 초기화는 빈 볼륨의 첫 기동에만 실행되므로 기존 볼륨 변경은 SQL과 설정을 별도로 검토한다.

기본 연결 상한은 `(엔진 2 + 교체 여유 1) × 48 + 조회 최대 2 × 10 + 봇 10 + 운영 10 + 복제 4 = 188`이며 PostgreSQL 일반 한도 `200 - 3 = 197` 이내다. 실제 사용량이나 메모리 적정성의 측정값은 아니다. 풀·ASG 최대 대수·교체 여유를 바꾸면 전체 예산을 검사하고 2 GiB 기본 메모리와 실제 EC2 규격을 부하로 검증한다.

node_exporter와 전용 DB exporter, 실제 주소 인벤토리와 Grafana를 연결한다. 고객사·환경·역할로 관측 범위를 정하고 서비스 관제·Spring·AWS 서버·DB 복구 화면에서 같은 대상을 조회한다. DB 접속 실패와 exporter 장애는 별도 경보이며 AWS CloudWatch는 전용 overlay와 기존 instance profile로 연결한다. ALB·ASG 식별자는 Terraform 출력에서 가져온다.

Alertmanager의 기본 Teams 경로와 보고 webhook은 독립적이다. 일일 보고는 **09:00 Asia/Seoul**이다. Google Gemini 직접 연결·호출 상한을 구현했으며 현재 설치는 외부 전송 동의를 기다리는 `facts-only`다. Teams 요약 카드와 같은 원자료의 2쪽 PDF·HTML 자동 제공은 [보고 서비스 안내](reporting/README.md)를 따른다. 파일 링크는 별도 키로 서명하고 기본 7일 뒤 만료된다. PDF 생성 실패가 카드 전송을 막지 않으며 실패를 별도 지표로 남긴다. 로컬 주소와 외부 관계자용 HTTPS 주소를 구분한다. SQLite가 중복·재시작·재전송을 처리하고 비밀 URL·키·토큰은 저장소에 넣지 않는다. HTTP 2xx와 실제 Teams 채널 게시를 구분하며, 환경별 실행 근거는 [09-18 기록](../docs/measurements/20260918.md)에 있다. AWS 계정 없이 Terraform 정적 검사를 통과한 것은 실제 plan·apply 결과가 아니다.

노드 간 연결, 관리 접근 통제, 모니터링과 알림, 재배포·롤백, 백업 복원을 실제 환경에서 확인한다. DB 장애 후 절차는 [DB 수동 페일오버](../docs/reference/operations/DB_수동_페일오버.md)를 따른다. 리더 앱 자동 전환이 DB 자동 승격을 뜻하지는 않는다.

기존 기록에는 2026-08-30의 ARM64 이미지 확인과 prod 부팅 시험이 있다. 당시 확인한 이미지라도 배포할 digest가 달라지면 다시 확인한다. 과거 로컬 부팅 성공을 다중 호스트 배포 성공으로 계산하지 않는다.
