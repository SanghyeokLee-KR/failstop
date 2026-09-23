# AWS 배포 안내

이 폴더에는 앱, DB, 봇을 서로 다른 호스트에서 실행하기 위한 Compose 설정이 있다. 루트의 `compose.yaml`은 로컬 단일 호스트용이므로 AWS에 그대로 옮겨 쓰지 않는다.

2026-09-18부터 AWS 단계의 목표는 MSP 운영·관제, Linux·네트워크·DB 운영, 조회 계층 EC2 자동 확장, AI 일일·장애 보고와 Microsoft Teams 연계다. 기존 노드 파일은 그 목표가 구현됐다는 뜻이 아니다. 수동 확장만 허용했던 정책은 [ADR 0027](../docs/reference/adr/0027-msp-operations-and-autoscaling.md)로 대체했고, 로컬 카오스는 과거 개발 검증 자료로 남긴다.

학교 EC2의 직접 설치와 10월 초 개인 계정 재배포는 [ADR 0029](../docs/reference/adr/0029-school-aws-first-personal-account-expansion.md)를 따른다. 이 폴더의 Compose는 실행 조건을 확인할 기존 배포 자산이며 Linux 서비스 설치 절차가 완성됐다는 뜻은 아니다. 실제 계정 조회 결과와 남은 작업은 [로드맵](../docs/ROADMAP.md)에서 관리한다.

전체 구조와 배포 착수 조건은 [0099 현재 구조와 AWS 배포 판단](../docs/reference/adr/0099-current-architecture.md)에서 먼저 읽는다. 개별 ADR을 시간순으로 읽으며 현재 결정을 다시 조합할 필요가 없도록, 채택한 구조와 후속 변경을 한곳에 모았다.

현재 비용·확장 기준은 [ADR 0030](../docs/reference/adr/0030-nat-free-lab-and-bounded-read-asg.md)이다. 학교·개인 실습 모두 NAT Gateway를 제외하고 조회 ASG는 최소 2·최초 희망 2·최대 3대로 계획한다. 기존 IGW는 유지한다. 학교 피어링 생성·라우트 추가는 실제 dry-run에서 거부됐으므로 운영자 허용 없이 네트워크를 변경하지 않는다.

## 학교 EC2에서 먼저 확인할 범위

진행 순서는 Docker 결과 확인, 학교 EC2에서 Linux 운영·서비스 검증, 2026년 10월 초 개인 계정 보강이다. [ADR 0029](../docs/reference/adr/0029-school-aws-first-personal-account-expansion.md)에 따라 VM 선행 단계는 보류하고 EC2에서 직접 설치·설정한다. Dockerfile·Compose의 실행 조건과 기존 앱 산출물·검증 기준은 재사용한다. 아래 내용은 설치·시험 완료 기록이 아니다.

| 구성 요소 | Linux에서 직접 수행할 일 |
|---|---|
| 거래 앱·조회 앱 | 현재 빌드의 Java 21 요구를 확인하고 런타임·JAR·역할별 환경 설정을 준비한다. 실행 계정·권한·systemd 기동·종료·로그를 직접 설정한다. |
| PostgreSQL | 현재 PostgreSQL 17 계열 설정을 기준으로 패키지·확장·데이터 경로·계정·접근 규칙·복제·백업을 직접 구성한다. 마이그레이션 순서와 DB 계약을 유지한다. |
| 웹 제공 | 현재 정적 export 산출물과 Nginx 등의 웹 서버 설정을 대조하고 정적 파일·프록시·TLS·접근 로그를 직접 설정한다. 정적 웹 제공에 Node 서버가 필수라고 가정하지 않는다. |
| 관측·알림 | 기존 Prometheus·Grafana·Alertmanager·exporter의 필요한 버전을 설치하고 계정·서비스·설정·보존 경로를 관리한다. |
| 보고·예약 작업 | 필요한 런타임·의존성을 분리하고 실행 계정·비밀 설정·systemd timer 또는 cron·실행 로그·실패 처리를 직접 확인한다. |

위 표는 직접 설치 대상으로 정한 범위이며 설치 절차를 실행하거나 검증한 결과가 아니다. 첫 설치는 각 단계의 설정과 결과를 사람이 확인하고, 검증한 뒤 반복 절차를 자동화한다. Docker는 꼭 필요한 항목에 한해 이유와 실습 한계를 기록하며, 아직 확정한 예외는 없다. 기존 Docker 데이터와 검증 환경은 보존한다. 컨테이너 이름·docker exec·볼륨 경로에 의존한 기존 스크립트를 그대로 실행하지 않고 실제 Linux 경로와 비교한다. Compose 기동을 직접 설치 실습으로 대신하지 않는다.

먼저 생성한 t3.small·gp3 20GiB 서버는 첫 앱 노드다. 앱, DB primary, DB standby, 관측은 처음부터 서로 다른 인스턴스에 배치하며, 이 서버에 전체 스택을 설치한 뒤 나누는 절차는 사용하지 않는다. 앱 리더·팔로워도 서로 다른 호스트에 둔다. [0099의 역할별 배치](../docs/reference/adr/0099-current-architecture.md#역할별-인스턴스-배치)를 기준으로 대상 인스턴스와 설치할 역할을 먼저 대조한다. 다음 자원 생성 전에는 노드별 규격·AZ·서브넷·허용 통신·비용을 확정한다.

EC2에서 계정·권한의 허용과 거부, 재부팅 후 마운트·서비스·로그 보존을 확인한다. 이후 여러 호스트의 통신·관측과 격리된 복원 대상에서 백업·데이터 검증을 수행한다. 학교에서 확인하지 못한 ALB·ASG·권한·성능은 미검증으로 남긴다. [증거본](../증거본/README.md)에 AWS 설정과 명령어별 실제 화면·출력을 보관한다. 개인 계정에서도 주소·권한·배치를 적용한 뒤 재검증한다.

## 로컬 VM 실습망

[ADR 0029](../docs/reference/adr/0029-school-aws-first-personal-account-expansion.md)가 보류한 것은 'VM에서 먼저 검증한 뒤 AWS로 간다'는 착수 조건이다. VM 실습망 자체는 그 조건과 별개로 구축했다. [vm-lab](vm-lab/) 폴더의 kickstart와 스크립트로 VirtualBox VM 여섯 대(EDGE01, APP01, APP02, DB01, DB02, OPS01)에 Rocky Linux 10.2와 각 역할을 직접 설치했고, 2026-09-23에는 여섯 대를 끄고 다시 켜는 재부팅 확인을 했다. 구축과 재부팅 기록은 `vm-lab/캡처/`에 있다. 재부팅 확인에서는 네 대의 콜드 부팅 정지를 커널 인자로 고쳤다고 기록했지만, 스냅숏 시점에 OPS01의 Grafana가 떠 있지 않았고 네 대의 호스트 점검 유닛이 실패로 남았다([재부팅 검증](vm-lab/캡처/재부팅검증-2026-09-23/README.md)). pfSense 경계 방화벽, VM의 DB 수동 승격, OPS01 보고 역할은 아직 만들지 않았다.

로드맵의 09-23 인계에 따르면 여섯 대는 끈 상태로 USB에 옮겼다. 2026-09-23에 VM을 만든 PC가 아닌 지금의 작업 PC에서 확인했을 때 USB가 연결돼 있지 않았고 VirtualBox에 여섯 대가 등록돼 있지 않았으며 관리 개인키(`%USERPROFILE%\.ssh\vm-lab_ed25519`)도 없었다. kickstart가 root와 ops 비밀번호를 잠갔으므로 키가 없으면 SSH로 들어갈 수 없다. VM 작업을 이어가려면 VM과 키를 함께 옮겨야 한다.

VM 결과는 단일 PC의 가상 네트워크에서 얻은 것이다. 학교 AWS나 개인 AWS의 검증 결과로 옮겨 적지 않고, 학교 증거본에도 섞지 않는다. 이 폴더의 스크립트 중 VM 구축 뒤에 고친 것(DB의 `mount-guard` 단계, EDGE01 80 허용 출발지, 관측 수집 대상 판정, chrony 시각원 유지)은 저장소 템플릿만 고친 것이며 VM에는 적용하지 않았다.

## 사용할 파일

| 파일 | 역할 |
|---|---|
| [compose.node-app.yml](compose.node-app.yml) | 앱 노드. 이미지, DB 주소, 비밀 값과 허용 오리진을 외부에서 받는다. |
| [compose.node-data.yml](compose.node-data.yml) | PostgreSQL primary 또는 standby 노드. 비동기 복제 설정을 포함한다. |
| [compose.node-bot.yml](compose.node-bot.yml) | 실제 주문 API를 호출하는 봇 노드. |
| [학교 봇 설정](linux/bot/bot.env.school), [서비스](linux/bot/exchange-bot.service), [설치](linux/bot/install-bot.sh), [암호화 전달](linux/bot/transfer-secrets.py) | bot-1에 직접 설치하는 BTC·ETH·XRP 봇. 세 마켓 합계 목표 3 OPS로 HTTPS 주문을 보내며 엔진·Flyway는 끈다. DB 풀 상한은 4+1+1개다. [용량 보호](linux/bot/capacity_guard.py)가 네 서버의 디스크·inode와 수집 상태를 확인하고 부족할 때 봇만 중지한다. 재현·중지 절차는 AWS 작업 안내를 따른다. |
| [pg-init-replication.sh](pg-init-replication.sh) | primary의 복제 계정과 슬롯 준비. |
| [pg-standby-entrypoint.sh](pg-standby-entrypoint.sh) | base backup을 받아 standby 시작. |
| [.env.example](.env.example) | 배포 변수 이름과 예시. 실제 비밀 값은 별도로 관리한다. |
| [60-exchange-lab.conf](linux/journald.conf.d/60-exchange-lab.conf) | 학교 실습 서버의 journald 영구 저장·용량·기간 설정. 서버 적용 파일과 해시를 대조했다. |
| [exchange.conf](linux/postgresql/exchange.conf) | 학교 DB 두 대에 적용한 PostgreSQL 17 공통 설정. 호스트별 주소·인증서·HBA·복제 비밀 값은 별도로 구성한다. |
| [exchange-app.service](linux/app/exchange-app.service), [app.env.school-private](linux/app/app.env.school-private) | 첫 학교 앱의 systemd 서비스와 루프백 기동 설정. 외부 접근 주소와 관측 경로는 별도 확정한다. |
| [MigrateDatabase.java](linux/app/MigrateDatabase.java) | 앱 JAR과 같은 Flyway 의존성으로 스키마만 적용·검증하는 실행 도구. 비밀 값은 환경에서 받는다. |
| [nginx-preview.conf](linux/app/nginx-preview.conf) | 학교 app-1의 HTTP 화면 확인용 설정. 사용자 PC 한 개의 SG 제한을 전제로 정적 웹·조회만 제공하고 쓰기·관리·WS는 차단한다. 운영 HTTPS 배포 설정으로 사용하지 않는다. |
| [nginx-https.conf](linux/app/nginx-https.conf), [적용 스크립트](linux/app/activate-https.py) | 학교 앱의 현재 HTTPS 설정. 인증서 발급 후 오리진을 함께 바꾸며, 승인된 Grafana·봇 관리자 로그인 경로만 연결한다. |
| [ACME 준비](linux/app/prepare-acme.py), [갱신 훅](linux/app/certbot-nginx-reload.sh) | 학교 미리보기의 인증 경로 준비와 갱신 후 Nginx 검사·reload. 발급·타이머 절차는 AWS 작업 안내의 도메인 절을 따른다. |
| [마켓 기본값](linux/app/bootstrap-markets.sql), [공개 경로 검사](linux/app/verify-public.py) | 빈 카탈로그 전용 초기 등록과 HTTPS·관리 경로 차단·데모 조회 검사. 거래 데이터 삭제나 부하 시험은 하지 않는다. |
| [nginx-metrics.conf](linux/app/nginx-metrics.conf) | 위 설정에서 포함하는 지표 전용 서버. `/etc/nginx/exchange-metrics.conf`에 먼저 배치한다. 앱 사설 주소의 9101에서 bot-1만 `/metrics`를 읽으며 SG 소스도 봇 그룹으로 제한한다. |
| [Linux 관측 설정](linux/observability/) | bot-1의 Prometheus·node_exporter·Grafana 서비스와 Linux 대시보드. Prometheus 등 내부 지표는 루프백이다. Grafana는 아래 HTTPS 프록시 전용 설정을 사용한다. |
| [MSP 화면 변환](linux/observability/build-msp-dashboards.py), [MSP 화면](linux/observability/msp-dashboards/), [프로비저닝](linux/observability/grafana-msp-dashboards.yml) | GitHub `6ee1179`의 MSP 화면 6개와 추가한 디스크·용량 화면을 AWS 앱·호스트·DB 지표에 연결한다. 미수집은 0이나 정상으로 바꾸지 않는다. |
| [Linux 수집 서비스](linux/observability/node_exporter-host.service), [DB 수집 서비스](linux/observability/postgres_exporter.service), [통계 계정](linux/observability/create-monitor-role.sql), [DB 쿼리](linux/observability/pg-queries.yaml) | 앱·DB 호스트는 사설 9100, DB 통계는 사설 9187을 봇 SG에만 제공한다. 로컬 peer 인증을 사용하며 거래 테이블 권한은 주지 않는다. |
| [용량 화면 원본](linux/observability/storage_dashboard.py) | 호스트별 루트 파일시스템·inode·DB 크기·WAL을 표시한다. 70·85·95%는 시각 표시 기준이며 자동 정리·알림 기능이 아니다. |
| [Grafana 최초 관리자 준비](linux/observability/bootstrap-grafana-admin.sh) | 사용자가 터미널에서 아이디·비밀번호를 직접 입력하는 최초 계정 생성 절차. 기존 사용자가 있으면 중단하며 비밀번호 재설정용이 아니다. |
| [Grafana HTTPS 설정](linux/observability/grafana-public.ini), [Nginx Grafana 경로](linux/app/nginx-grafana.conf) | 기존 로그인과 사용자 DB를 유지하고 앱 Nginx를 통해 `/grafana/`로 접속한다. 사설 3000은 앱 SG만 허용한다. |
| [관리자 로그인 경로](linux/app/nginx-admin.conf), [내부 mTLS](linux/app/nginx-admin-upstream.conf), [계정 생성](linux/app/create-admin-login.py), [공개 접근 검사](linux/app/verify-admin-public.py) | 운영자 1명의 HTTPS 비밀번호 로그인과 내부 mTLS. 설치 순서는 AWS 작업 안내, 신뢰 경계는 ADR 0031을 따른다. |
| [봇 관리 설정](linux/bot/admin.env.school), [systemd 추가 설정](linux/bot/admin-service-override.conf), [인증서 준비](linux/bot/prepare-admin-gateway.py) | 실제 봇과 같은 JVM에서 사설 8443 관리 API를 실행한다. 인증서 60일, 자동 갱신 미구현. |
| [운영 보고](linux/reporting/) | bot-1 지표 수집, Gemini 요약, Teams 전송과 systemd 예약. 비밀 값은 별도 root 전용 파일에서 서비스 자격증명으로 전달한다. |
| [DB 백업](linux/postgresql/backup/README.md) | 현재 Primary에서만 물리 베이스백업을 만들고 검증하는 Docker 없는 도구와 systemd 타이머, 경보 규칙. 비공개 S3 외부 보관은 설정했을 때만 켠다. 2026-09-23 저장소에 추가했고 학교 서버에는 설치하지 않았다. |
| [관측 노드 감시](linux/watchdog/README.md) | app-1과 bot-1이 관측 경로를 서로 확인하고 Alertmanager를 거치지 않고 Teams로 직접 알리는 워치독과 Watchdog 규칙. 2026-09-23 저장소에 추가했고 학교 서버에는 설치하지 않았다. |

`compose.base.yml`은 없다. 각 노드의 파일을 직접 사용한다. 앱 이미지는 태그만으로 식별하지 않고 배포할 digest를 확정한다.

`compose.node-edge.yml`, `nginx-edge.conf.template`과 인증서 스크립트는 ALB 채택 전 구성이다. 현재 AWS 진입 계층으로 그대로 사용하지 않는다. 이번 정리에서는 실행 설정을 변경하지 않았다.

## 배포 구조를 확인할 문서

- [ADR 0010](../docs/reference/adr/0010-onprem-to-aws-deployment.md): 외부 접속 가능한 AWS 배포와 실험 환경 분리.
- [ADR 0011](../docs/reference/adr/0011-cloudfront-two-origins.md): 정적 웹과 API를 CloudFront 뒤에서 연결하는 구조.
- [ADR 0027](../docs/reference/adr/0027-msp-operations-and-autoscaling.md): MSP 운영과 조회 계층 자동 확장, Teams·AI 보고. ADR 0015의 수동 확장 정책을 대체한다.
- [ADR 0016](../docs/reference/adr/0016-observability-on-bot-node.md): 봇 노드에 관측 도구를 두는 결정. 노드 Compose의 실제 서비스 정의와 추가로 맞춰야 한다.
- [ADR 0020](../docs/reference/adr/0020-managed-nat-gateway.md): 이전 관리형 NAT Gateway 결정. 현재 학교·개인 실습은 ADR 0030으로 대체됐다.
- [ADR 0029](../docs/reference/adr/0029-school-aws-first-personal-account-expansion.md): 학교 계정 제한, EC2 선행과 개인 계정 보강.
- [ADR 0024](../docs/reference/adr/0024-async-two-node-replication.md): PostgreSQL 비동기 복제와 수동 승격.

여러 과거 문서가 인용하는 배포·비용 기획안 원본은 현재 작업 폴더에 없다. 이전 내용을 대조해야 한다면 `git show af90bca^:제출_20260901/기획안_원본.md`로 읽을 수 있다. 위 결정 기록과 현재 설정을 바탕으로 배포 전 구성을 확정해야 하며, 이 안내만으로 누락된 세부 계약이 복원된 것은 아니다.

## AWS 사용 조건

학교 계정은 Default VPC와 Public Subnet, t3.small 이하, gp3 50GiB 이하를 사용한다. Route 53·ACM·Private Subnet·NAT Gateway·VPC Endpoint 구성과 IAM 사용자·역할 생성, Access Key 발급은 금지한다. 기존 Internet Gateway와 기본 네트워크를 임의로 바꾸지 않는다. 세부 조건은 [ADR 0029](../docs/reference/adr/0029-school-aws-first-personal-account-expansion.md)를 따른다.

EC2에는 기존 `SafeInstanceProfile-{username}`을 연결한다. 현재 사용자 이름에 맞는 프로파일의 존재·연결 권한과 SSM 에이전트·통신 조건을 확인해야 한다. SG는 별도 보안 그룹 화면에서 인바운드 없이 생성하고 username 자동 태그가 붙었는지 확인한 뒤 필요한 규칙만 추가한다. EC2 생성 화면에서는 이 그룹을 '기존 보안 그룹'으로 선택하며 생성 화면의 자동 SSH 공개 규칙은 사용하지 않는다. DB와 관리 포트를 인터넷 전체에 열지 않는다. 학교 안내상 인바운드 수정이 제한되므로 저장 전 규칙을 확정한다.

50GiB는 스토리지 용량 조건이다. RAM이나 인스턴스 대수 한도가 아니며 계정 전체 EBS 할당량은 별도로 확인한다. 첫 실습은 20GiB로 준비하고 DB·로그의 사용량을 측정한다. t3.small의 2GiB 메모리에 전체 스택을 한꺼번에 배치하지 않는다. 부하 시험에서는 CPU 크레딧과 메모리·디스크 사용량도 함께 본다.

학교 월 40만 원 예산과 개인 계정 크레딧은 별개로 관리한다. 실제 잔액·만료·정산 조건·사용 시간으로 비용을 계산한다. 학교 비용·감사 설정은 운영자 영역이며 수정하지 않는다. ALB·ASG·시작 템플릿 등의 생성 권한은 미확인 상태로 출발한다. 접근 거부를 우회하려고 IAM이나 계정 연결을 바꾸지 않는다.

## Linux 실행 환경

[ADR 0010](../docs/reference/adr/0010-onprem-to-aws-deployment.md)의 Rocky Linux 10·Graviton 계획과 달리 학교 실습은 사용자가 유지하기로 확정한 Amazon Linux 2023·x86_64를 사용한다. t3 제한 자체가 Rocky 사용을 금지하는 것은 아니다. 생성된 AMI와 규격은 [로드맵](../docs/ROADMAP.md), 변경 적용 범위는 [ADR 0029](../docs/reference/adr/0029-school-aws-first-personal-account-expansion.md)에서 확인한다. Java 21·PostgreSQL 17·관측 도구의 패키지 지원을 대조하고 ARM64 전용 이미지나 바이너리를 그대로 옮기지 않는다. 첫 접속 후 OS·CPU·메모리·디스크·시간대부터 명령과 화면으로 확인한다. 개인 계정의 CPU·OS도 재배포 시 따로 정한다.

### 실습 서버의 로그 보존

시스템 로그는 `/var/log/journal`에 보존한다. [설정 원본](linux/journald.conf.d/60-exchange-lab.conf)을 `/etc/systemd/journald.conf.d/60-exchange-lab.conf`에 적용한다. 20GiB 실습 디스크를 기준으로 영구 journal은 최대 256MiB, 메모리 journal은 최대 64MiB, 파일시스템 여유 기준은 1GiB, 보존 기간 상한은 7일로 정했다. 용량 조건이 먼저 적용될 수 있으므로 7일치 보존을 보장하지 않는다. 이 여유 기준은 다른 프로세스의 디스크 사용을 막는 예약 공간도 아니다. 애플리케이션 파일 로그·DB 로그·중앙 수집의 보존 정책은 별도다. [systemd 설정 설명](https://www.freedesktop.org/software/systemd/man/252/journald.conf.html)

새 호스트에서는 기존 설정과 drop-in을 먼저 읽고 겹치는 항목이 없는지 확인한다. 위 파일을 root 소유로 설치한 뒤 `sudo systemctl restart systemd-journald`, `sudo journalctl --flush`를 순서대로 실행한다. 적용 로그에 경로와 최대 용량이 표시되는지 보고, 시험 로그를 남긴 다음 계획된 재부팅 후 `sudo journalctl -b -1 -t exchange-lab --no-pager`로 이전 부팅의 실제 기록을 확인한다. 학교 서버는 이 절차를 한 번 수행했으며 화면과 명령은 [증거본](../증거본/2026-09-19_학교AWS/README.md)에 있다. 호스트 로그 보존 확인을 거래 앱 복구·DB 무손실 검증으로 확대하지 않는다.

원복은 이번에 추가한 drop-in만 적용 디렉터리 밖으로 보관하고 journald를 재시작하는 범위다. 기존 `/etc/systemd/journald.conf`와 journal 데이터는 삭제하지 않는다. 기본 `Storage=auto`에서 `/var/log/journal` 존재 여부가 저장 방식에 영향을 주므로 원복 후에도 실제 경로를 확인한다. 원복 절차는 아직 실행하지 않았다.

## 녹화를 위한 운영 기간

발표는 실제 시연 영상과 캡처를 PPTX에 넣어 준비한다. 학교 EC2에서 가능한 운영 검증을 먼저 촬영하고, 10월 초 개인 계정에서 제한됐던 구성을 보강한다. [기존 보고본](../아키텍쳐/README.md)의 Private Subnet·NAT 등은 학교 환경과 다르다. 학교 환경을 기존 도면 전체의 구현 결과처럼 보여 주지 않으며 개인 계정도 실제 적용한 범위만 설명한다.

보고본의 수동 확장 표시는 과거 계획이며 현재 요구는 AWS 자동 확장이다. 매칭 리더와 DB는 고정된 역할로 두고, 먼저 확장 가능한 조회 경로와 ASG 노드의 준비·축소·관측 등록을 설계한다. 새 노드 최대 대수만큼의 DB 연결 예산과 비용도 합산한다. 기존 Discord 알림을 Teams로 바꾸고 Linux 호스트 지표·보고 작업을 배포해야 하므로, 앱 Compose만 옮겨서 완료할 수 없다.

자원을 만들기 전에 예상 사용 시간, 재촬영 여유, 보존할 데이터, 종료일과 철거·재생성 절차를 정한다. 기존 결정의 외부 상시 접속 조건이 영상 발표로 자동 면제된 것은 아니므로 필요한 접속 유지 기간을 별도로 확인한다. 월 40만 원 안에 드는지는 실제 규격·볼륨·시간·전송량과 잔액을 넣어 다시 계산해야 한다.

EC2를 중지하는 것만으로 전체 과금이 끝나지 않는다. 남은 EBS 볼륨, NAT Gateway·ALB 등의 비용을 확인하고, 결과와 원자료를 보관한 뒤 불필요한 자원을 정리한다. NAT 삭제 후에도 Elastic IP는 별도로 해제해야 한다. NAT 관련 종료 절차는 해당 자원을 실제 사용하는 개인 계정 환경에만 적용한다. 실행 상태는 로드맵과 증거를 확인한다. [EC2 중지](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/how-ec2-instance-stop-start-works.html), [NAT 삭제](https://docs.aws.amazon.com/vpc/latest/userguide/nat-gateway-working-with.html), [ALB 삭제](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/load-balancer-delete.html)

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

기존 계획의 도메인은 `sang.it.kr`이다. 학교에서는 Route 53·ACM을 사용하지 않으므로 허용 여부를 확인한 CloudFront 기본 도메인 등을 검토한다. 사용자와 CloudFront 사이의 HTTPS가 ALB 원본 구간까지 암호화한다는 뜻은 아니다. 개인 도메인은 개인 계정 보강 때 따로 판단한다. 실제 접속 주소를 확정한 뒤 다음 값을 웹 빌드에 넣는다.

- `NEXT_PUBLIC_DATASOURCE=http`.
- `NEXT_PUBLIC_API_BASE`: 같은 오리진을 사용하면 빈 문자열.
- `NEXT_PUBLIC_WS_BASE`: 배포 도메인의 절대 `wss://` 주소.

공개 번들에서 관리 화면을 제거하는 절차는 [웹 README](../web/README.md)에 있다. 도메인 변경 시 웹을 다시 빌드하고 서버의 CORS·WebSocket 허용 오리진도 맞춘다.

## 배포 전 확인

첫 실행 전에 [DB 노드 설정](compose.node-data.yml)의 사설 호스트 간 접속 경로를 마련해야 한다. 현재는 포트 공개나 호스트 간 네트워크가 정의되어 있지 않다. 앱·리스·append·봇의 풀을 합산해 PostgreSQL 연결 한도도 맞춘다. 기존 기본 구성의 최대 합계는 106개로 기본 일반 연결 한도 97개를 넘는다. 학교의 첫 앱은 요청 16·append 14·리스 2로 상한을 줄였다. 두 앱은 합계 64개이며 봇·관측·관리·조회 ASG를 포함한 전체 예산은 추가 배포 전에 다시 계산한다. 역할 권한과 V41·V44·V46 적용 순서까지 포함한 선행 작업은 [0099](../docs/reference/adr/0099-current-architecture.md)에 정리했다.

노드 간 연결, 관리 접근 통제, 모니터링과 알림, 재배포·롤백, 백업 복원을 실제 환경에서 확인한다. DB 장애 후 절차는 [DB 수동 페일오버](../docs/reference/operations/DB_수동_페일오버.md)를 따른다. 리더 앱 자동 전환이 DB 자동 승격을 뜻하지는 않는다.

기존 기록에는 2026-08-30의 ARM64 이미지 확인과 prod 부팅 시험이 있다. 당시 확인한 이미지라도 배포할 digest가 달라지면 다시 확인한다. 과거 로컬 부팅 성공을 다중 호스트 배포 성공으로 계산하지 않는다.


## 학교 native 배포와 원격 구현 자료의 구분

학교 EC2에서 실제로 적용한 설정은 [Linux 배포 폴더](linux/)와 [AWS 작업 안내](../docs/reports/AWS_작업.md)를 따른다. 원격 브랜치에서 개발한 [Grafana·관측](observability/README.md), [예약 백업](backup/README.md), [선택적 운영 콘솔](console/README.md), Terraform·조회 역할 코드는 함께 보존한다. [09-18 기록](../docs/measurements/20260918.md)은 해당 로컬 검증 자료이며 학교 AWS 실행 증거와 구분한다. Docker 예제의 메모리·연결 풀·조회 최대 대수를 현재 학교 t3.small 설정에 그대로 적용하지 않는다.
