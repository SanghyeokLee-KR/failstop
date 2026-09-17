# AWS 배포 안내

이 폴더에는 앱, DB, 봇을 서로 다른 호스트에서 실행하기 위한 Compose 설정이 있다. 루트의 `compose.yaml`은 로컬 단일 호스트용이므로 AWS에 그대로 옮겨 쓰지 않는다.

2026-09-18부터 AWS 단계의 목표는 MSP 운영·관제, Linux·네트워크·DB 운영, 조회 계층 EC2 자동 확장, AI 일일·장애 보고와 Microsoft Teams 연계다. 기존 노드 파일은 그 목표가 구현됐다는 뜻이 아니다. 수동 확장만 허용했던 정책은 [ADR 0027](../docs/reference/adr/0027-msp-operations-and-autoscaling.md)로 대체했고, 로컬 카오스는 과거 개발 검증 자료로 남긴다.

2026-09-17 문서 정리 기준으로 실제 AWS 계정과 실행 상태는 조회하지 않았다. 저장된 설정과 배포 완료 여부를 구분한다. 남은 배포 작업은 [로드맵](../docs/ROADMAP.md)에 있다.

전체 구조와 배포 착수 조건은 [0099 현재 구조와 AWS 배포 판단](../docs/reference/adr/0099-current-architecture.md)에서 먼저 읽는다. 개별 ADR을 시간순으로 읽으며 현재 결정을 다시 조합할 필요가 없도록, 채택한 구조와 후속 변경을 한곳에 모았다.

## 사용할 파일

| 파일 | 역할 |
|---|---|
| [compose.node-app.yml](compose.node-app.yml) | 앱 노드. 이미지, DB 주소, 비밀 값과 허용 오리진을 외부에서 받는다. |
| [compose.node-data.yml](compose.node-data.yml) | PostgreSQL primary 또는 standby 노드. 비동기 복제 설정을 포함한다. |
| [compose.node-bot.yml](compose.node-bot.yml) | 실제 주문 API를 호출하는 봇 노드. |
| [pg-init-replication.sh](pg-init-replication.sh) | primary의 복제 계정과 슬롯 준비. |
| [pg-standby-entrypoint.sh](pg-standby-entrypoint.sh) | base backup을 받아 standby 시작. |
| [.env.example](.env.example) | 배포 변수 이름과 예시. 실제 비밀 값은 별도로 관리한다. |

`compose.base.yml`은 없다. 각 노드의 파일을 직접 사용한다. 앱 이미지는 태그만으로 식별하지 않고 배포할 digest를 확정한다.

`compose.node-edge.yml`, `nginx-edge.conf.template`과 인증서 스크립트는 ALB 채택 전 구성이다. 현재 AWS 진입 계층으로 그대로 사용하지 않는다. 이번 정리에서는 실행 설정을 변경하지 않았다.

## 배포 구조를 확인할 문서

- [ADR 0010](../docs/reference/adr/0010-onprem-to-aws-deployment.md): 외부 접속 가능한 AWS 배포와 실험 환경 분리.
- [ADR 0011](../docs/reference/adr/0011-cloudfront-two-origins.md): 정적 웹과 API를 CloudFront 뒤에서 연결하는 구조.
- [ADR 0027](../docs/reference/adr/0027-msp-operations-and-autoscaling.md): MSP 운영과 조회 계층 자동 확장, Teams·AI 보고. ADR 0015의 수동 확장 정책을 대체한다.
- [ADR 0016](../docs/reference/adr/0016-observability-on-bot-node.md): 봇 노드에 관측 도구를 두는 결정. 노드 Compose의 실제 서비스 정의와 추가로 맞춰야 한다.
- [ADR 0020](../docs/reference/adr/0020-managed-nat-gateway.md): 관리형 NAT Gateway 채택.
- [ADR 0024](../docs/reference/adr/0024-async-two-node-replication.md): PostgreSQL 비동기 복제와 수동 승격.

여러 과거 문서가 인용하는 배포·비용 기획안 원본은 현재 작업 폴더에 없다. 이전 내용을 대조해야 한다면 `git show af90bca^:제출_20260901/기획안_원본.md`로 읽을 수 있다. 위 결정 기록과 현재 설정을 바탕으로 배포 전 구성을 확정해야 하며, 이 안내만으로 누락된 세부 계약이 복원된 것은 아니다.

## AWS 사용 조건

사용자 설명에 따르면 IAM을 제외한 AWS 서비스는 사용할 수 있을 것으로 예상된다. 이전의 NAT Gateway, Route 53, ACM, Systems Manager 일괄 제한은 현재 전제로 삼지 않는다. 실제 계정 권한은 아직 확인하지 않았다.

IAM 제약 때문에 기존 역할을 사용할 수 있는지, 추가 역할 설정을 학교 관리자에게 요청해야 하는지 확인이 필요하다. 사용자가 밝힌 지원금은 40만 원이다. 과거 ADR의 50만 원 전제를 현재 예산으로 사용하지 않는다. 실제 지급·크레딧 잔액·청구액과 배포 착수 가능 여부는 조회하지 않았다.

## Linux 실행 환경

기존 [ADR 0010](../docs/reference/adr/0010-onprem-to-aws-deployment.md)은 Rocky Linux 10 계열과 Graviton ARM64를 전제로 한다. 실제 사용할 OS 버전·AMI의 출처와 ID, CPU 아키텍처와 배포 이미지 digest는 착수할 때 확인한다. 현재 AWS 호스트의 OS와 설정을 조회한 결과는 없다. 계정·SSH 또는 SSM 접근, 서비스의 부팅·재시작, 로그 보존, 디스크·파일시스템, 네트워크 점검 절차를 이 환경에 맞춰 준비한다. 첫 작업 순서는 [로드맵](../docs/ROADMAP.md)의 '다음 작업의 시작점'을 따른다.

## 녹화를 위한 운영 기간

발표는 실제 시연 영상과 캡처를 PPTX에 넣어 준비한다. [교수님께 보고한 구성](../아키텍쳐/README.md)을 기준으로 로컬에서 절차를 연습한 뒤 AWS 검증과 촬영을 모아서 진행한다. 예산에 맞추려고 DB나 NAT 수를 임의로 줄이면 보고안과 다른 구성의 실험이 된다. 우선 가동 기간과 부하·확장 노드의 사용 시간을 관리한다.

보고본의 수동 확장 표시는 과거 계획이며 현재 요구는 AWS 자동 확장이다. 매칭 리더와 DB는 고정된 역할로 두고, 먼저 확장 가능한 조회 경로와 ASG 노드의 준비·축소·관측 등록을 설계한다. 새 노드 최대 대수만큼의 DB 연결 예산과 비용도 합산한다. 기존 Discord 알림을 Teams로 바꾸고 Linux 호스트 지표·보고 작업을 배포해야 하므로, 앱 Compose만 옮겨서 완료할 수 없다.

자원을 만들기 전에 예상 사용 시간, 재촬영 여유, 보존할 데이터, 종료일과 철거·재생성 절차를 정한다. 기존 결정의 외부 상시 접속 조건이 영상 발표로 자동 면제된 것은 아니므로 필요한 접속 유지 기간을 별도로 확인한다. 40만 원 안에 드는지는 실제 규격·볼륨·시간·전송량과 잔액을 넣어 다시 계산해야 한다.

EC2를 중지하는 것만으로 전체 과금이 끝나지 않는다. 남은 EBS 볼륨, NAT Gateway·ALB 등의 비용을 확인하고, 결과와 원자료를 보관한 뒤 불필요한 자원을 정리한다. NAT 삭제 후에도 Elastic IP는 별도로 해제해야 한다. 이번 문서 정리에서는 자원을 생성·중지·삭제하지 않았다. [EC2 중지](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/how-ec2-instance-stop-start-works.html), [NAT 삭제](https://docs.aws.amazon.com/vpc/latest/userguide/nat-gateway-working-with.html), [ALB 삭제](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/load-balancer-delete.html)

## 앱 설정

[앱 노드 설정](compose.node-app.yml)의 기본 프로파일은 `prod`다. 아래 값을 준비한다.

- DB 주소와 계정: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`.
- 암호화·회원 인증: `MASTER_KEY_BASE64`, `PASSWORD_PEPPER_BASE64`, `SESSION_SEED_BASE64`.
- 브라우저 접근: `CORS_ALLOWED_ORIGINS`, `WS_ALLOWED_ORIGINS`.
- 실행 이미지와 스키마 적용: `APP_IMAGE`, `FLYWAY_ENABLED`.

마이그레이션은 먼저 기동하는 한 노드에서 완료하고 나머지 앱을 시작한다. prod에서는 API 포트 8080과 관리 포트 8081이 분리된다. 관리 포트와 하네스 API를 공개하지 않는다. `prod,admin` 조합에는 별도 운영 인증서가 필요하다.

컨테이너 생존 확인과 요청 수용 여부도 다르다. 팔로워는 살아 있어도 readiness가 접수 불가를 반환할 수 있으므로, 생존 확인에는 liveness를 사용한다.

조회 ASG용 실행 설정은 아직 완성되지 않았다. 현재 앱 Compose의 `environment`에는 `EXCHANGE_ENGINE_ENABLED` 전달이 없으므로 `.env`에 `false`만 적어도 조회 전용 노드로 바뀌지 않는다. 역할별 실행 설정과 런치 템플릿부터 구현하고 실제 프로세스에서 엔진 비활성화를 확인한다. 이 설정만으로 쓰기 API가 제거되는 것도 아니므로 라우팅과 접근 통제를 함께 검증한다. 조회 헬스의 DB 의존성을 ASG 교체 판단에 그대로 연결하지 않는 조건은 [ADR 0027](../docs/reference/adr/0027-msp-operations-and-autoscaling.md)을 따른다.

## 웹과 도메인

기존 계획의 도메인은 `sang.it.kr`이다. 현재 DNS 연결 여부는 조회하지 않았다. 배포할 도메인을 확정한 뒤 다음 값을 웹 빌드에 넣는다.

- `NEXT_PUBLIC_DATASOURCE=http`.
- `NEXT_PUBLIC_API_BASE`: 같은 오리진을 사용하면 빈 문자열.
- `NEXT_PUBLIC_WS_BASE`: 배포 도메인의 절대 `wss://` 주소.

공개 번들에서 관리 화면을 제거하는 절차는 [웹 README](../web/README.md)에 있다. 도메인 변경 시 웹을 다시 빌드하고 서버의 CORS·WebSocket 허용 오리진도 맞춘다.

## 배포 전 확인

첫 실행 전에 [DB 노드 설정](compose.node-data.yml)의 사설 호스트 간 접속 경로를 마련해야 한다. 현재는 포트 공개나 호스트 간 네트워크가 정의되어 있지 않다. 앱·리스·append·봇의 풀을 합산해 PostgreSQL 연결 한도도 맞춘다. 문서상 기본 구성의 최대 합계는 106개로 기본 일반 연결 한도 97개를 넘는다. 역할 권한과 V41·V44·V46 적용 순서까지 포함한 선행 작업은 [0099](../docs/reference/adr/0099-current-architecture.md)에 정리했다.

노드 간 연결, 관리 접근 통제, 모니터링과 알림, 재배포·롤백, 백업 복원을 실제 환경에서 확인한다. DB 장애 후 절차는 [DB 수동 페일오버](../docs/reference/operations/DB_수동_페일오버.md)를 따른다. 리더 앱 자동 전환이 DB 자동 승격을 뜻하지는 않는다.

기존 기록에는 2026-08-30의 ARM64 이미지 확인과 prod 부팅 시험이 있다. 당시 확인한 이미지라도 배포할 digest가 달라지면 다시 확인한다. 과거 로컬 부팅 성공을 다중 호스트 배포 성공으로 계산하지 않는다.
