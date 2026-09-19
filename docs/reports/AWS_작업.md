# AWS 작업 이어가기

「Linux 기반 AWS 인프라 구축 및 운영」의 학교 EC2 작업을 이어받기 위한 문서다. 사용자가 요청한 작업 안내 한 파일을 계속 갱신한다. 전체 완료 상태는 [로드맵](../ROADMAP.md), 설계 판단은 [0099](../reference/adr/0099-current-architecture.md), 실행 원본은 [증거본](../../증거본/2026-09-19_학교AWS/README.md)을 기준으로 확인한다.

2026-09-20 복구 시연 이후 DB 역할이 바뀌었다. **현재 Primary는 `172.31.33.195`(AZ-c), Standby는 `172.31.1.229`(AZ-a)**다. 인스턴스 이름은 생성 당시 이름을 유지한다. 아래 초기 설치 절의 주소를 그대로 복사하지 말고, 문서 마지막의 복구 절과 실제 `pg_is_in_recovery()` 결과를 먼저 확인한다. [이번 화면·명령](../../증거본/2026-09-20_백업_복구_보고/README.md)에 전환 근거가 있다.

## 지금 접속할 서버

2026-09-19에 학교 계정의 서울 리전에 앱 1대, DB 2대, 봇·관측 1대를 만들었다. 앱·DB primary·DB standby·관측은 처음부터 서로 다른 인스턴스로 구성한다. 전체 스택을 한 서버에 넣었다가 나누는 절차는 폐기했다. 역할별 기준은 [0099의 배치 표](../reference/adr/0099-current-architecture.md#역할별-인스턴스-배치)를 따른다. 같은 이름의 서버를 다시 만들지 않는다. 사용자는 Rocky Linux 대신 이번 서버의 Amazon Linux를 그대로 쓰기로 확정했다.

| 항목 | 확인한 값 |
|---|---|
| 학교 사용자 | `2026-ai-kopo-04` |
| 서버 | `2026-ai-kopo-04-linux-lab-01` / `i-0465dd41a0390f262` |
| 운영체제 | Amazon Linux 2023.12.20260918, x86_64 |
| 사양 | t3.small, 2 vCPU, 메모리 2GiB, CPU 크레딧 Standard |
| 루트 디스크 | 암호화 gp3 20GiB, XFS / `vol-0429fcdf9a5c3cd4e` |
| 네트워크 | 기본 VPC `vpc-07ad90c6c3880faee`, 서브넷 `subnet-0525d8f575f6529ec` |
| 앱 사설 주소 | `172.31.2.86` / ap-northeast-2a |
| 보안 그룹 | `sg-0e321b38da5cc8aaf` |
| 연결한 프로파일 | `SafeInstanceProfile-2026-ai-kopo-04` |
| 접속 방법 | EC2 Instance Connect 브라우저 SSH, `ec2-user` |

생성 당시에는 서울 EC2 Instance Connect 서비스의 관리형 접두사 목록 `pl-00ec8fd779e5b4175`에서 오는 TCP 22만 허용했다. 앱은 PC 제한 HTTP 미리보기를 거쳐 현재 공개 80·443으로 서비스한다. 80은 인증서 검증과 HTTPS 이동에만 쓴다. DB와 내부 관리 포트는 직접 공개하지 않는다. Grafana와 봇 관리자는 공개 HTTPS에서 별도 로그인으로 접근한다. 현재 SSH 규칙으로 개인 PC의 PuTTY에서 직접 접속할 수는 없다. 공인 IP는 중지·시작으로 바뀔 수 있으므로 콘솔에서 조회한다.

SSM 콘솔의 대상 조회 권한은 거부됐지만 학교 사용자 CloudShell과 이 PC에서 봇 노드의 포트 포워딩 세션 생성은 성공했다. 조회 거부를 모든 세션 연결의 거부로 해석하지 않는다. PC 브라우저에서 Grafana 로그인 화면까지 확인했으며 관리자 계정 생성과 대시보드 로그인·지표 표시까지 확인했다. 아래 접속 절에서 이어간다.

## 완료한 작업과 확인 방법

서버 생성 뒤 OS·메모리·디스크·라우팅·시간 동기화·기본 서비스를 확인했다. 이어 journald에 디스크 사용 상한 256MiB와 보존 기간 상한 7일을 설정하고, 실제 재부팅 뒤 이전 부팅의 시험 로그가 남는지 확인했다. SSH 재접속과 기본 서비스의 실행, 루트 파일시스템의 읽기·쓰기도 확인했다. 거래 서비스나 DB 복구 시험은 아직 아니다.

적용한 설정은 [60-exchange-lab.conf](../../deploy/linux/journald.conf.d/60-exchange-lab.conf)다. 서버와 저장소의 SHA-256이 `5b1d5a88358944bd90353927848f7c36536e67c2514b350fbb0080b4831a0eb3`으로 일치했다. 재현·원복 절차는 [배포 안내](../../deploy/README.md)에 있다. 용량 상한에 먼저 도달하면 7일치 로그가 모두 남지 않을 수 있다.

Amazon Linux 공식 저장소에서 `java-21-amazon-corretto-headless`를 설치했다. `java -version`은 OpenJDK 21.0.12.1, Corretto 21.0.12.9.1을 출력했다. 로그인 불가 셸을 사용하는 `exchange` 계정도 만들었다. `/opt/exchange`와 `/etc/exchange`는 root:exchange 0750, `/var/lib/exchange`는 exchange:exchange 0750이다. 앱 계정으로 실행·설정 경로에 파일을 만들 때 권한 거부가 발생했고 데이터 경로에는 시험 파일이 생성됐다. 이 시험 파일은 남아 있다.

첫 앱 노드에서는 PostgreSQL 17을 패키지 조회만 했으며 DB 서버·관측 스택은 설치하지 않았다. 거래 앱은 아래 기록대로 systemd 서비스로 구성했다. 잘못된 통합 배치를 되돌리기 위한 DB 삭제 작업은 없다.

소스 리비전 `3362117`에서 로컬 `:app:bootJar` 빌드가 성공했다. JAR은 53,461,799바이트이고 SHA-256은 `8a056e1b556abfadd0f5a7ab26c9cf693d95f16481b9a78e6d3d2f4e77a5aabd`다. AWS 전송 후 같은 해시와 기동을 확인했다. 이번에 서버 전체 테스트를 다시 실행한 것은 아니다.

## 다음 작업 순서

재개할 때 `exchange-app` 서비스와 readiness, DB 복제 상태를 먼저 조회한다. 종목 기본값과 공개 HTTPS·WebSocket, 호스트·DB 관측은 연결했다. 세 종목 시장 봇은 가동 중이며 회원 거래·두 번째 앱과 복구 검증은 남아 있다. 현재 진행 상태는 로드맵의 '다음 작업의 시작점'을 따른다. ALB 조회·생성과 ASG 조회·시작 템플릿 생성이 학교 사용자에게 거부됐으므로 운영자 확인이 필요하다. CloudFront는 콘솔 목록 조회만 성공했고 생성은 하지 않았다.

2026-09-19 사용자가 DB primary와 standby를 각각 gp3 50GiB로 지정했다. 두 DB의 루트 볼륨을 각각 암호화 50GiB로 구성하며, 앱 서버의 기존 20GiB는 유지한다. DB 두 대는 t3.small·CPU 크레딧 Standard, Amazon Linux 2023 x86_64로 준비한다. Primary는 기본 VPC의 AZ-a(`subnet-0525d8f575f6529ec`), Standby는 AZ-c(`subnet-05a746c2ab576c30c`)다. 생성 결과와 설치 상태는 로드맵에서 확인한다.

DB 그룹 `sg-03b023cf05dc4bafe`는 빈 인바운드로 생성하고 username 자동 태그를 확인했다. 사용자는 두 DB의 기존 인스턴스 프로파일 연결과 다음 세 규칙을 승인했다. TCP 22는 서울 Instance Connect 접두사 목록, TCP 5432는 기존 앱 그룹과 DB 그룹 자체에서만 허용한다. DB는 사설 주소로 통신한다. AWS 설정만으로 PostgreSQL 인증·복제 구성이 끝난 것은 아니다.

공식 서울 가격표에서 t3.small Linux는 시간당 $0.026, gp3는 GB·월당 $0.0912를 확인했다. 공인 IPv4는 주소·시간당 $0.005다. DB 두 대를 730시간 가동하면 EC2·IPv4 $45.26와 100GiB EBS $9.12로 합계 약 $54.38다. 기본 5대를 모두 t3.small로 가정하고 앱 2대·bot-1에 각각 20GiB, DB 2대에 각각 50GiB를 두면 같은 항목의 합계는 약 $127.74다. 아직 생성하지 않은 노드의 규격은 견적 가정이다. ALB·LCU·ALB IPv4·조회 ASG·load-1·교차 AZ 전송·백업·CloudWatch·AI·세금·환율은 별도이며 이 금액을 월 40만 원의 전체 견적으로 쓰지 않는다. 현재 만든 네 대와 총 140GiB의 같은 항목을 730시간 기준으로 계산하면 약 $103.29다. 추가 노드와 서비스는 생성 전에 별도로 계산한다.

가격 확인은 2026-09-19에 수행했다. EC2·EBS 가격 발효일은 2026-09-01이다. [공식 지역 가격표](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEC2/current/ap-northeast-2/index.csv), [발췌 원본](../../증거본/2026-09-19_학교AWS/AWS_서울_가격조회.csv), [IPv4 요금](https://aws.amazon.com/vpc/pricing/)을 근거로 한다. 인스턴스를 중지해도 남은 EBS는 과금되며 이번 생성이 종료 예약을 포함하지는 않는다.

1. [보고한 전체 구성도](../../아키텍쳐/교수님_보고본/01_전체구성/거래소_하이브리드_인프라_v5.png)와 0099의 배치 표를 기준으로 학교용 노드별 규격·AZ·서브넷·허용 통신·가동 시간·비용을 맞춘다. 기본 역할은 app-1·app-2, DB primary·standby, bot-1(시장 봇·관측)이며 load-1은 별도 시험 노드다. 관측 전용 EC2를 추가하는 것으로 임의 변경하지 않는다.
2. 거래 DB·계정·앱 TLS 연결은 완료했다. 복제와 서비스 상태를 먼저 조회하고 종목 초기화·실제 거래를 검증한다. 다음 노드 추가 전에 두 앱·봇·운영 접속의 연결 풀 합계를 맞춘다.
3. 첫 앱의 JAR 전송·systemd 기동은 완료했다. 두 번째 앱도 별도 EC2에 같은 버전과 공유 인증 설정으로 구성한다.
4. bot-1의 Java·Prometheus·Grafana 설치 결과는 아래 관측 절에서 확인한다. 시장 봇 연결과 여러 호스트의 수집은 남아 있다. 동거에 따른 자원 간섭을 측정하고 분리가 필요하면 변경 근거와 비용을 사용자에게 제시한다. load-1의 부하 발생기는 시험 대상 앱·DB와 분리한다.
5. ALB 조회·생성과 ASG 조회·시작 템플릿 생성 권한 거부를 학교 운영자에게 확인한 뒤 허용된 경로에서 조회 계층 확장을 진행한다. DB 복구와 Teams 보고는 별도로 이어간다. 도면의 수동 app-3 증설을 현재 ASG 구현 완료로 해석하지 않는다. 완료 조건은 로드맵 M4를 따른다.

앱 실행 조건은 [application.yml](../../server/app/src/main/resources/application.yml)의 `prod` 설정과 [기존 앱 배포 설정](../../deploy/compose.node-app.yml)에서 확인했다. DB 접속 정보 외에 `EXCHANGE_AUTH_MASTER_KEY_BASE64`, `EXCHANGE_MEMBER_PASSWORD_PEPPER_BASE64`, `EXCHANGE_MEMBER_SESSION_SEED_BASE64`, CORS와 WebSocket 허용 출처가 필요하다. 실제 비밀 값은 이 문서·화면·Git에 남기지 않는다. DB 마이그레이션은 첫 노드에서 한 번 적용하고 이미 적용한 Flyway 파일은 수정하지 않는다.

## 별도 DB에서 한 작업

두 서버는 같은 Amazon Linux 2023·PostgreSQL 17.11을 사용한다. 학교 지침에 따라 별도 SG 화면에서 빈 그룹 생성, username 자동 태그 확인, 제한 규칙 추가를 거친 뒤 EC2 생성 화면에서 기존 그룹을 선택했다. 사용자가 우려한 '새 보안 그룹 생성' 금지는 EC2 생성 화면의 자동 생성 옵션에 해당한다. 기존 DB 그룹을 다시 만들 필요가 없다.

| 역할 | 인스턴스 | 사설 주소·AZ | 루트 볼륨 |
|---|---|---|---|
| Primary | `i-0e302764af73d861f` | `172.31.1.229` / ap-northeast-2a | `vol-026b2f4c824fa9398`, 암호화 gp3 50GiB |
| Standby | `i-099799f0dc164ae71` | `172.31.33.195` / ap-northeast-2c | `vol-02164bac33fa6596c`, 암호화 gp3 50GiB |

두 서버의 EC2 상태 검사 3/3, 브라우저 SSH, 50GiB XFS, 시간 동기화를 확인했다. `postgresql17-server`와 `postgresql17-contrib`를 공식 저장소에서 설치했다. Primary만 `initdb --encoding=UTF8 --locale=C.UTF-8 --auth-local=peer --auth-host=scram-sha-256 --data-checksums`로 초기화했다. 데이터 경로는 `/var/lib/pgsql/data`, 서비스는 `postgresql.service`다. Standby에서 initdb를 다시 실행하면 안 된다.

설정 본문은 [exchange.conf](../../deploy/linux/postgresql/exchange.conf)에 있다. 두 서버 적용본과 저장소의 SHA-256은 `8acbe28b479fa81c73ede1acfd978f1c232d2a85ed478287762a27ad90b603bd`로 일치했다. 최초 전송에서 브라우저 입력이 줄바꿈을 없앴고 일반 사용자 셸이 제한된 디렉터리의 와일드카드를 확장하지 못했다. 파일을 줄바꿈 보존 방식으로 다시 전송하고 명시 경로로 소유권을 수정한 뒤 해시·기동·실효 설정을 확인했다. 실패 화면 186·187도 그대로 보관했다.

| 위치 | 용도와 주의점 |
|---|---|
| `/etc/postgresql/exchange.conf` | 두 DB 공통 설정, root:postgres 0640. shared_buffers 256MB, work_mem 4MB, max_connections 100. 부하 측정 전 초기값이다. |
| `/etc/postgresql/node.conf` | localhost와 해당 서버의 사설 주소만 listen. 베이스백업으로 다른 서버 주소를 덮어쓰지 않도록 PGDATA 밖에 둔다. |
| `/var/lib/pgsql/data/postgresql.conf` | 공통 설정 파일을 include한다. |
| `/var/lib/pgsql/data/pg_hba.conf` | Primary는 로컬 peer, Standby `/32` 복제, 앱 `172.31.2.86/32`의 exchange_app hostssl·SCRAM을 허용한다. Standby HBA는 아직 앱 접속 규칙이 없어 승격 전에 역할에 맞춰 검토해야 한다. |
| `/etc/postgresql/server.crt`, `server.key` | 각 노드에서 만든 TLS 인증서·개인 키. 개인 키는 postgres:postgres 0600이며 외부로 복사하지 않았다. 만료는 2026-12-18 UTC다. |
| Standby의 `/etc/postgresql/primary.crt` | Primary의 공개 인증서를 신뢰 파일로 보관. 원본 지문과 일치하는 것을 확인했다. |
| Standby의 `/var/lib/pgsql/.pgpass` | 복제 전용 비밀번호, postgres:postgres 0600. 값은 문서·Git·캡처에 남기지 않았다. 내용을 출력하지 않는다. |
| `/etc/systemd/journald.conf.d/60-exchange-lab.conf` | 앱과 같은 보존 설정. 두 DB에 적용했지만 DB 호스트 재부팅 후 보존 검증은 아직 하지 않았다. |

복제 계정 `replicator`에는 LOGIN·REPLICATION만 주고 superuser·DB 생성·역할 생성 권한은 주지 않았다. 비밀번호는 화면에 표시하지 않는 입력으로 설정했다. Primary의 공개 인증서를 Standby로 전달하고 지문을 비교한 뒤 다음 명령을 실행했다. 이는 이미 완료한 첫 구성 기록이다. 데이터 디렉터리가 채워진 현재 상태에서 그대로 재실행하거나 자동 삭제하지 않는다.

```bash
sudo -u postgres pg_basebackup \
  -d 'host=172.31.1.229 port=5432 user=replicator application_name=school_standby_1 sslmode=verify-full sslrootcert=/etc/postgresql/primary.crt passfile=/var/lib/pgsql/.pgpass' \
  -D /var/lib/pgsql/data -Fp -Xs -P -R -S standby_1 \
  --checkpoint=fast --no-password
sudo -u postgres pg_verifybackup /var/lib/pgsql/data
sudo systemctl enable --now postgresql
```

베이스백업은 23,802kB를 복사했고 기동 전에 `backup successfully verified`를 확인했다. `-R`은 복구 신호와 연결 설정을 만든다. 비밀번호 본문은 연결 문자열에 넣지 않았다. 옵션의 근거는 [PostgreSQL 17 pg_basebackup 문서](https://www.postgresql.org/docs/17/app-pgbasebackup.html)다. 실행 중인 데이터 디렉터리는 계속 바뀌므로 최초 백업의 manifest 검사를 현재 서비스에 무작정 다시 적용하지 않는다.

첫 복제에서 Primary는 `streaming / async / TLSv1.3`, Standby는 `pg_is_in_recovery()=true`와 `streaming`을 반환했다. 거래 데이터와 분리한 `infra_validation` DB의 `public.replication_probe` 시험 테이블에 id 1을 기록하고 Standby에서 같은 행을 읽었다. Standby의 id 2 쓰기 시도는 읽기 전용 오류로 거부됐다. Standby 서비스를 재시작한 뒤 Primary에서 추가한 id 3도 도착했다. 이 두 행은 확인 근거로 남아 있다. 시험은 운영 거래·장애 승격 시험이 아니다.

재접속 뒤에는 다음 읽기 전용 조회로 시작한다. Primary에서는 `pg_stat_replication`, Standby에서는 `pg_stat_wal_receiver`를 조회한다. 비밀번호가 들어갈 수 있는 연결 설정 전체를 출력하지 않는다.

```bash
systemctl is-active postgresql
sudo -u postgres psql -X -P pager=off -c 'SELECT pg_is_in_recovery();'
# Primary
sudo -u postgres psql -X -P pager=off -c \
  'SELECT client_addr,state,sync_state FROM pg_stat_replication;'
# Standby
sudo -u postgres psql -X -P pager=off -c \
  'SELECT status,sender_host,slot_name FROM pg_stat_wal_receiver;'
sudo -u postgres psql -X -P pager=off -d infra_validation -c \
  'SELECT id,note FROM replication_probe ORDER BY id;'
```

WAL 슬롯 보존은 기존 정책의 4GB를 유지했다. 이 값은 체크포인트 시점에 적용되므로 디스크 사용량의 절대 상한이 아니며, 초과하면 슬롯이 필요한 WAL을 잃어 다시 베이스백업을 받아야 할 수 있다. `wal_keep_size=0`, `archive_mode=off`다. 현재 Standby는 복제본이며 삭제·오입력까지 보호하는 별도 보관 백업을 대신하지 않는다. 앱 계정과 TLS 신뢰는 배포했다. 인증서 교체·만료 관리, 실제 장애 격리·승격·재편입과 별도 백업 복원은 후속 작업이다.

## 첫 앱 배포에서 확인한 것

비공개 S3 버킷 `2026-ai-kopo-04-exchange-artifacts-619256964117`에 승인된 `exchange-app-3362117.zip`을 업로드했다. Block Public Access와 SSE-S3를 유지했고 Access Key 대신 EC2의 기존 Role로 내려받았다. ZIP SHA-256은 `e401a778edacf6e0279229f37dcaebc0619bc81f385a00ca2abb594fbbc5c5ed`다. 앱 파일은 `/opt/exchange/releases/3362117`, 현재 링크는 `/opt/exchange/current`다.

DB 소유자 `exchange_owner`로 Flyway 47개를 적용·검증한 뒤 NOLOGIN으로 잠그고 암호를 제거했다. 임시 마이그레이션 비밀 파일도 제거했다. 실행 계정 `exchange_app`은 app_role을 상속하며 스키마 생성·원장 INSERT·잔고 UPDATE가 실제 거부됐다. 최초 원장 거부 시험은 identity 열 문법 오류여서 권한 근거로 쓰지 않았고 열을 명시해 다시 확인했다. Standby에서 47개 스키마 이력과 리더 epoch 복제를 확인했다.

`exchange-app.service`는 exchange 사용자로 실행하며 설정은 `/etc/exchange/app.env`, 비밀 값은 root:root 0600의 `/etc/exchange/app-secret.env`에 있다. 비밀 값은 출력·캡처하지 않는다. `/etc/exchange/primary.crt`로 DB 인증서를 verify-full 검증한다. JVM 힙 최대 768MiB, 서비스 MemoryMax 1400MiB로 시작했다. 요청·append·리스 풀 상한 설정은 16+14+2다. 요청 16과 리스 2는 실제 지표로 확인했고 append 풀은 아직 사용 전이다. 두 앱이면 설정 합계 64개이며 봇·관측·관리·ASG 연결을 추가할 때 DB 최대 100개 안에서 다시 계산한다.

서비스 재시작 후 readiness·조회 헬스 UP과 마켓 API 응답을 확인했다. 마켓 목록은 빈 배열이다. 현재 API 8080과 관리 8081은 127.0.0.1만 listen하며 대시보드·외부 웹은 미배포다. 회원 인증 유지, 실제 거래, 부하·장애 복구와 호스트 재부팅 후 앱 검증은 아직 수행하지 않았다. 명령 채번 래치는 미적용이다. 신규 빈 DB에 V46을 적용했으므로 이후 앱도 V46 호환본으로 맞추며 첫 이벤트 대역 기록 후 구버전으로 되돌리지 않는다.

ALB·ASG는 학교 사용자 콘솔에서 조회 권한이 거부됐다. CloudFront는 사용자 콘솔 목록 조회만 성공했고 EC2 Role의 목록 조회는 거부됐다. 생성 권한 성공으로 확대하지 않는다. 자세한 실행 화면은 증거 211~242에 있다. 최종 이사 설명서는 운영 구성을 마친 뒤 이 문서에서 정리한다.

추가 생성 시험에서 `elasticloadbalancing:CreateLoadBalancer`와 `ec2:CreateLaunchTemplate`이 모두 identity-based policy의 허용 부재로 거부됐다. ALB는 앱 연결 없는 고정 503 응답·기존 제한 SG로 요청했으며 생성되지 않았다. ASG는 선택할 시작 템플릿이 없고 템플릿 생성도 막혀 `CreateAutoScalingGroup` 요청까지 진행하지 못했다. ASG 생성 API 자체의 허용·거부는 미확인이다. 이번 시험으로 새 ALB·리스너·템플릿·ASG·EC2는 생성되지 않았다. 증거 258~262에 화면과 오류를 보관했다.

## 봇·관측 서버 준비

`2026-ai-kopo-04-bot-01`은 `i-06ff9b16739362b14`, 사설 주소 `172.31.44.141`, AZ-c다. t3.small·Amazon Linux 2023 x86_64·CPU 크레딧 Standard·IMDSv2 Required·기존 SafeInstanceProfile을 사용한다. 루트 볼륨 `vol-0e6aaf6ce38e531a0`은 암호화 gp3 20GiB다. DB 두 대의 50GiB 구성은 유지한다.

전용 그룹 `sg-064b0da59e8c9a13c`은 빈 규칙으로 생성한 뒤 username 자동 태그를 확인했다. 승인된 TCP 22 한 개를 서울 Instance Connect 접두사 목록에서만 허용했고 브라우저 SSH 접속에 성공했다. 실제 셸에서 XFS 20GiB, 시간 동기화, 실패 서비스 0개를 확인했다. 공통 journald 설정을 적용해 파일 해시 일치와 256MiB 상한을 확인했다. 이 호스트의 재부팅 후 보존 시험은 아직 하지 않았다.

서버 생성 직후에는 봇·관측 프로그램을 설치하지 않았고 앱·DB 그룹에 봇 접근도 허용하지 않았다. 이후 관측 수집과 시장 봇을 순서대로 연결했으며 상세 결과와 현재 상태는 아래 절을 따른다. 앱 8080·8081은 루프백을 유지한다. Grafana 익명 Admin 설정을 옮기지 않는다. 이 서버 생성만으로 대시보드 연결이나 관제 검증이 끝난 것은 아니다. 실행 화면과 명령은 증거 243번 이후에 있다.

## 관측 서비스 설치에서 이어가기

위의 설치 전 점검 이후 bot-1에 Prometheus 3.13.3과 node_exporter 1.12.1을 설치했다. 공식 [다운로드 목록](https://prometheus.io/download/)의 linux-amd64 SHA-256과 다운로드 파일을 대조한 뒤 `/usr/local/bin`에 배치했다. 서비스별 로그인 불가 계정을 사용하고 9090·9100은 `127.0.0.1`에만 연다. 설정 원본은 [관측 설정 폴더](../../deploy/linux/observability/)에 있다. Grafana 13.1.0은 공식 RPM 저장소의 서명 검증을 켜고 설치했으며, 최초 관리자 자동 생성과 익명 접근을 끈 상태다. 3000도 루프백만 사용한다. 설치 직후에는 관리자 계정이 0개였다. 이후 사용자가 직접 계정을 만들었으며 현재도 접속은 SSM 터널과 Grafana 로그인으로 제한한다.

Prometheus는 15초마다 수집하며 보존 조건은 7일·2GiB다. 먼저 도달한 조건에 따라 오래된 데이터를 지우므로 7일 전체 보존을 보장하지 않는다. WAL·작업 공간까지 포함한 전체 디스크 사용 상한도 아니다. 메모리 상한은 Prometheus 512MiB·node_exporter 128MiB다. 봇·Grafana를 함께 기동한 상태의 메모리 여유는 아직 측정하지 않았다.

`sudo systemctl status prometheus node_exporter --no-pager`로 서비스 상태를 보고, `curl -fsS http://127.0.0.1:9090/-/ready`로 준비 완료를 확인한다. `curl -fsS 'http://127.0.0.1:9090/api/v1/query?query=up'`은 수집 대상별 성공 여부를 보여 준다. HTTP 수집 성공과 개별 수집기의 성공은 다르므로 `node_scrape_collector_success`도 확인한다. 현재 사용하는 CPU·메모리·파일시스템·디스크·네트워크·systemd 수집기를 명시했고, 없는 장치나 사용하지 않는 파일시스템용 기본 수집기를 모두 켜지는 않는다.

학교 사용자로 VPC 피어링 생성과 라우트 추가를 `--dry-run` 검사했고 두 작업 모두 허용 정책 부재로 거부됐다. 실제 요청·라우트는 만들지 않았다. 개인 기본 VPC도 학교와 주소가 겹치므로 그대로 연결할 수 없다. 아래 '학교 운영자에게 요청할 내용'에 검사 범위와 연결 선행 조건을 정리했다. EC2 Role 결과를 콘솔 사용자의 권한으로 바꿔 기록하지 않는다.

## 앱 화면을 여는 방법

사용자 승인으로 첫 앱에 Nginx 1.30.4와 Next.js 정적 웹을 배치했다. 앱의 기존 SG에 현재 사용자 PC의 공인 IPv4 한 개(`/32`)에서 오는 TCP 80만 추가했다. 실제 주소는 변경될 수 있으므로 EC2 콘솔에서 app-1의 Public IPv4를 확인하고 `http://<앱 공인 IPv4>/home`으로 접속한다. 현재 집의 IP가 바뀌거나 학교에서 접속하면 허용 소스부터 확인한다. 이를 해결하려고 DB·관리 포트를 공개하지 않는다. 봇 SG와 기존 SSH 규칙은 변경하지 않았다.

이 경로는 HTTP 화면 확인용이다. [Nginx 설정](../../deploy/linux/app/nginx-preview.conf)은 GET·HEAD만 받고 로그인·관리 화면·WebSocket·actuator를 차단한다. 화면 200, 실제 시장 조회 API의 빈 배열, 쓰기 405, 관리·actuator 403을 확인했다. 거래 DB가 비어 있고 WS를 차단했으므로 화면에 가격 `-`와 연결 끊김이 나온다. 거래나 실시간 기능이 정상이라는 뜻은 아니다. HTTPS와 정상 거래 검증을 마친 뒤에만 기능을 열어야 한다.

웹 빌드는 `NEXT_PUBLIC_DATASOURCE=http`, `NEXT_PUBLIC_ADMIN_DATASOURCE=http`로 수행했고 API·WS·관리 기본 주소는 빈 값으로 지정했다. `web/out/`만 압축해 승인된 기존 비공개 S3 버킷에 `exchange-web-20260919.tar.gz`로 올렸다. 크기는 3,644,139바이트, SHA-256은 `a44c2b15b505b8849369e2bacf650e3f6b2fa756eff80e7e555dd7d3519afc67`다. 환경 파일이나 비밀 값은 포함하지 않는다.

서버는 기존 Role로 파일을 내려받아 해시를 대조한 후 `/opt/exchange-web/releases/20260919`에 풀고 `current` 링크를 연결했다. 파일은 root 소유 0644, 디렉터리는 0755로 정리했다. `/etc/nginx/nginx.conf.before-exchange`에 설치 기본 설정을 보관하고 새 설정의 `nginx -t`를 통과한 뒤 `nginx`를 enable·start했다. 원복할 때는 우선 Nginx를 중지하고 기존 설정·배포 링크를 대조한다. 앱·DB 서비스를 함께 내릴 이유는 없다.

## Grafana에 접속하기

현재 주소는 `https://www.sang.it.kr/grafana/`다. 기존 `dltkdgur` 계정으로 로그인한다. 익명 접근과 회원가입은 허용하지 않는다. 앱 Nginx가 봇 사설 주소의 3000으로 전달하며 봇 SG의 해당 포트는 앱 SG만 허용한다. 공개 3000 포트나 PC 터널은 필요 없다. 사용자 로그인 후 실제 DB 복제 패널과 Linux 지표를 확인했다. 비인증 API는 401이다.

설정 원본은 [Grafana](../../deploy/linux/observability/grafana-public.ini)와 [Nginx 경로](../../deploy/linux/app/nginx-grafana.conf)다. 봇의 `/etc/grafana/grafana.ini`에 Grafana 설정을, 앱의 `/etc/nginx/exchange-grafana.conf`에 Nginx 경로를 배치한다. Grafana 재시작, 앱 `nginx -t`, Nginx reload 순서로 적용한다. 기존 사용자 DB는 보존한다. 이전 설정은 각 서버의 `grafana.ini.before-https-20260920`과 `nginx.conf.before-grafana-20260920`에 있다.

### 이전 SSM 접속 실험

아래는 HTTPS 전환 전에 확인한 방법이다. 2026-09-20 조회 시 이 PC의 13000 리스너와 Session Manager 플러그인 프로세스는 없었다. 현재 Grafana는 사설 주소에 바인딩하므로 아래의 단순 루프백 터널 명령을 그대로 재사용하지 않는다. SSM 에이전트를 제거하거나 계정의 SSM 권한을 바꾼 것은 아니다.

사용자가 봇 서버의 인바운드를 22번만 유지하고 SSM 포트 포워딩을 우선 확인하도록 지정했다. 개인 PC SSH 규칙과 열람용 SSH 키는 추가하지 않았다. Grafana 익명 접근도 변경하지 않았다.

학교 사용자 `2026-ai-kopo-04`로 연 CloudShell에서 아래 명령의 세션 생성과 8080 로컬 포트 개방이 성공했다. 이는 CloudShell 안의 로컬 포트이며 사용자 PC의 8080 포트가 열린 것은 아니다. 콘솔 목록의 `ssm:DescribeInstanceProperties` 거부와 `StartSession` 성공을 구분한다.

```bash
aws ssm start-session --region ap-northeast-2 \
  --target i-06ff9b16739362b14 \
  --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["3000"],"localPortNumber":["8080"]}'
```

PC에서는 AWS CLI와 Session Manager 플러그인, 학교 계정의 단기 로그인 세션을 사용한다. 개인 AWS 계정으로 로그인하지 않는다. `aws login --profile school-2026-ai-kopo-04 --region ap-northeast-2` 실행 중 Edge에 콜백 차단 화면이 나왔지만 CLI는 정상 종료했고 STS에서 학교 IAM 사용자임을 확인했다. 앞서 로그인 실패라고 안내했던 판단은 이 결과로 정정한다. 장기 Access Key는 발급하지 않았다. 공식 설치 파일의 AWS 서명을 검증한 뒤 Session Manager 플러그인 1.2.835.0을 설치했다. [AWS CLI 콘솔 로그인](https://docs.aws.amazon.com/cli/latest/userguide/cli-configure-sign-in.html), [Windows 플러그인 설치](https://docs.aws.amazon.com/systems-manager/latest/userguide/install-plugin-windows.html)

이 PC의 `127.0.0.1:13000`에서 봇 노드의 Grafana `127.0.0.1:3000`으로 연결했고 브라우저의 로그인 화면을 확인했다. 터널 프로세스가 종료되거나 AWS 세션이 만료되면 다시 연결해야 한다. Windows PowerShell에서는 JSON 인용 문제를 피하도록 매개변수를 파일로 전달한다.

```powershell
$env:PATH = 'C:\Program Files\Amazon\SessionManagerPlugin\bin;' + $env:PATH
$portsFile = Join-Path $env:TEMP 'exchange-grafana-ports.json'
[IO.File]::WriteAllText($portsFile, '{"portNumber":["3000"],"localPortNumber":["13000"]}')
aws ssm start-session --profile school-2026-ai-kopo-04 --region ap-northeast-2 --target i-06ff9b16739362b14 --document-name AWS-StartPortForwardingSession --parameters "file://$portsFile"
```

연결 후 브라우저에서 `http://127.0.0.1:13000/login`을 연다. 최초 계정은 [관리자 준비 스크립트](../../deploy/linux/observability/bootstrap-grafana-admin.sh)를 봇 서버의 `/usr/local/sbin/exchange-grafana-admin-init`에 설치하고 `sudo /usr/local/sbin/exchange-grafana-admin-init`로 만든다. 운영자가 아이디와 비밀번호를 직접 입력한다. 비밀번호는 화면·명령 이력에 표시하지 않으며 생성 후 임시 파일을 제거하고 기존 접근 설정을 복원한다. 기존 사용자가 있으면 실행을 중단하므로 비밀번호 재설정용으로 쓰지 않는다. 사용자가 직접 입력해 dltkdgur 관리자 계정을 만들었다. 서비스 active·DB 헬스 정상, 임시 파일 0개, 익명 API 401을 확인했다. 로그인 후 `http://127.0.0.1:13000/d/school-linux`에서 bot-1의 CPU·메모리·디스크·네트워크 그래프와 서비스 상태를 확인했다. 실제 화면은 증거 311에 있다. 앱·DB 전체 관제 화면은 아직 아니다.

CloudShell 시험에서는 터널을 통한 Grafana 헬스 200과 익명 API 401을 확인했다. 별도로 요청한 `ssm:TerminateSession`은 권한 부족으로 거부됐다. 세션 시작 권한과 종료 API 권한을 구분하고 운영자에게 종료 권한을 확인한다. PC 터미널 화면은 도구로 캡처할 수 없어 명령·출력을 로그로 남겼고, 브라우저 화면은 증거 308에 보관했다. 비밀번호 입력 구간은 캡처하지 않는다.

## 기존 Docker MSP 화면을 AWS에 연결하기

학교에서 푸시한 `codex/docs-linux-operations`의 `6ee11791c79564b37c47ea30659373a856eb91c8`을 원본으로 사용한다. 서비스 관제, 트래픽·Spring, AWS·서버, DB 관제, SQL·세션, 복제·복구의 6개 화면이다. 처음에는 두 커밋 뒤처진 PC의 4개 화면을 배포했으나 사용자가 제공한 화면과 대조해 잘못된 버전임을 확인하고 교체했다. GitHub 푸시는 정상이며 저장소 전체를 병합한 것은 아니다. 기존 작업 파일을 보존하고 대시보드 JSON만 해당 커밋에서 읽었다.

[생성 스크립트](../../deploy/linux/observability/build-msp-dashboards.py)는 커밋을 고정해 AWS 사본을 `deploy/linux/observability/msp-dashboards/`에 만든다. 화면의 위치·크기·서비스 흐름도·이동 링크와 지표 식을 유지한다. 환경 기본값은 `aws`로 맞춘다. 아직 구성하지 않은 경보는 0건으로 표시하지 않도록 안내 패널로 대체하고, CloudWatch도 미연결 안내를 표시한다. 원본은 Git 커밋에 그대로 남으며 수집기를 연결할 때 이 제한을 함께 해제한다.

```powershell
python deploy/linux/observability/build-msp-dashboards.py
```

봇 서버에서 원본 화면 6개와 추가 용량 화면을 포함한 JSON 7개를 `/var/lib/grafana/msp-dashboards/`에, `grafana-msp-dashboards.yml`을 `/etc/grafana/provisioning/dashboards/school-msp.yml`에 배치한다. 데이터 원본 UID는 기존 `prometheus`다. 새 프로비저닝을 처음 추가할 때만 Grafana를 재시작하며 이후 JSON 변경은 10초 간격으로 반영된다. 사용자 계정·인증 설정은 유지한다. SSM 연결 후 `http://127.0.0.1:13000/d/msp-overview?var-customer=exchange&var-environment=aws`를 연다. 이전 AWS 화면은 `/var/lib/grafana/msp-before-6ee1179/`에 보관했다.

비밀 값 없이 JSON 6개·Prometheus 설정·검사 쿼리만 묶은 `msp-6ee1179-school.tar.gz`를 기존 비공개 S3 버킷에 올렸다. 크기는 43,776바이트, SHA-256은 `93897150e8fb7ec0d8e9d41905826bb8fc8d62e6b4ea0912b5f0b1e55a55630f`다. EC2의 기존 Role로 받아 해시와 파일 목록을 대조하고 `promtool check config`를 통과한 뒤 적용했다. JSON은 root:grafana 0640이며 기존 파일과 백업을 비교해 복원할 수 있다.

앱 지표는 Nginx의 사설 주소 `172.31.2.86:9101/metrics`가 앱의 루프백 `8081/actuator/prometheus`를 읽어 제공한다. 승인된 앱 SG 규칙 `sgr-0a21d2a6470ed76ba`의 소스는 봇 SG `sg-064b0da59e8c9a13c` 하나다. Nginx에서도 bot-1 사설 주소만 허용한다. `nginx-metrics.conf`를 `/etc/nginx/exchange-metrics.conf`에 먼저 배치하고, 이를 포함하는 `nginx-preview.conf`를 적용한 뒤 `sudo nginx -t`와 `sudo systemctl reload nginx`를 실행했다. 앱 프로세스는 재시작하지 않았고 봇 SG의 인바운드는 22만 유지했다.

Prometheus 설정은 [학교 수집 설정](../../deploy/linux/observability/prometheus.yml)을 `/etc/prometheus/prometheus.yml`에 배치한다. `promtool check config`를 통과한 뒤 서비스를 재시작했다. 앱은 `customer=exchange`, `environment=aws`, `account=school`, `role=app`, `node=app-1`, `job=exchange-api`로 수집한다. `external_labels`만으로는 로컬 조회에 환경 라벨이 붙지 않으므로 각 수집 대상에도 명시했다. bot-1의 역할은 `observability`이며 고객 앱·DB 호스트로 표시하지 않는다. 기존 Linux 대시보드와 AI 보고의 `linux` 작업 이름도 유지했다. 이전 시계열은 보존되며 새 라벨은 적용 시점부터 쌓인다.

최신 화면의 PromQL 121개는 오류 없이 실행됐고 초기 검사에서 16개에 데이터가 있었다. 앱 수집 UP과 서비스 가능 여부·리더·HTTP 요청·CPU 지표를 확인했다. 이 초기 검사 시점에는 DB exporter·앱과 DB의 호스트 수집·사이트 프로브·CloudWatch·경보 규칙이 연결 전이었다. 이후 호스트·DB 지표를 연결했으며 결과는 아래 절을 따른다. 정상 거래·부하와 실제 장애 복구는 따로 검증해야 한다. AI·Teams 보고 범위는 여전히 bot-1이다. 최초 50개 쿼리 검사 결과는 교체 전 화면의 중간 결과이며 최신본 검증으로 인용하지 않는다.

접근 시험은 봇에서 GET `/metrics` 200, POST 403, 다른 경로 404였고 비허용 출발지인 앱 자체에서는 지표 403이었다. 기존 공개 포트의 actuator 차단 403과 앱 홈 200도 확인했다.

화면 연결 중 끊김을 처음에는 SSM 문제로 판단했지만 서버 직접 호출도 실패했다. Grafana의 초기 384MiB 상한에서 OOM 종료를 확인했고 512/640MiB로 조정한 뒤에도 메모리 회수 지연이 재발했다. 당시 익명 메모리는 약 615MiB, 호스트 가용 메모리는 약 872MiB였다. [Grafana 설정](../../deploy/linux/observability/grafana-limits.conf)은 최종적으로 `GOMEMLIMIT=512MiB`, `MemoryHigh=768M`, `MemoryMax=896M`으로 조정했다. Go 런타임의 회수 목표와 Linux 서비스 한도를 함께 둔 것으로, 런타임 목표는 강제 상한이 아니다. [Go 메모리 관리 설명](https://go.dev/doc/gc-guide#Memory_limit)

최신 화면 6개를 차례로 열고 약 1분 동안 헬스를 6회 조회했으며 모두 HTTP 200이었다. 이 구간의 서비스 메모리는 약 490MiB, 재시작은 0회였다. 마지막 검사에서 메모리 압박·OOM 이벤트는 0, 호스트 가용 메모리는 1,006MiB였다. 현재는 기존 노드를 유지했다. 이는 짧은 화면 전환 시험이며 시장 봇과의 동시 운영이나 장시간 부하 검증은 아니다.

실제 적용 파일은 `/etc/systemd/system/grafana-server.service.d/limits.conf`다. 초기 값은 `.before-msp`, 중간 값은 `.before-gc` 파일에 보관했다. EC2 사양·대수나 앱·DB 설정은 바꾸지 않았다. 시장 봇을 추가하기 전에는 관측·보고·봇의 전체 메모리와 CPU 크레딧을 다시 측정해야 한다. 사용자는 여유가 없으면 봇과 관측 노드를 분리하도록 지시했다. 메모리 압박이 재발하거나 동시 기동 시험에서 여유가 부족하면 별도 관측 노드로 옮긴다. PC SSM의 원본·재접속 로그도 보존하며 서버 세션 종료 API의 성공을 주장하지 않는다.

## 앱·DB의 Linux 지표와 DB 통계 연결

앱과 DB 두 대에는 node_exporter 1.12.1을, DB 두 대에는 postgres_exporter 0.17.1을 직접 설치했다. PostgreSQL 수집기는 기존 Docker 검증 환경과 버전을 맞췄다. [DB 쿼리 파일](../../deploy/linux/observability/pg-queries.yaml)은 GitHub `6ee1179`의 원본을 사용한다. exporter의 사용자 쿼리 옵션은 폐기 예정 기능이므로 향후 버전 변경 때 호환성을 확인해야 한다. 이번 설치에서 DB나 앱 서비스를 재시작하지 않았다.

| 대상 | 사설 수집 주소 | 허용 출발지 |
|---|---|---|
| 앱 Linux | `172.31.2.86:9100` | 봇 SG `sg-064b0da59e8c9a13c` |
| Primary Linux·DB | `172.31.1.229:9100`, `:9187` | 같은 봇 SG |
| Standby Linux·DB | `172.31.33.195:9100`, `:9187` | 같은 봇 SG |
| 봇 Linux | `127.0.0.1:9100` | 봇 내부 Prometheus |

사용자 승인으로 기존 앱 그룹에 9100, 기존 DB 그룹에 9100·9187 규칙만 추가했다. 봇 그룹의 인바운드는 22번 그대로다. 봇에서 모든 대상이 UP인 반면 앱에서 DB 관측 포트로 연결하면 시간 초과가 발생했다. 지표를 제공하는 서버는 사설 IP에만 바인딩한다. 당시 Grafana와 Prometheus의 관리 포트는 루프백이었다. 현재 Grafana는 위 HTTPS 접속 절의 사설 바인딩을 따른다.

### 다른 계정에서 재현할 때

먼저 새 계정의 사설 주소와 SG를 정하고 위 표를 바꾼다. 기존 주소를 그대로 복사하지 않는다. 공식 릴리스에서 받은 linux-amd64 바이너리를 검사한 뒤 비공개 배포 묶음으로 전달했다. 파일 이름이 같다는 이유로 검증을 생략하지 않는다.

| 파일 | SHA-256 |
|---|---|
| `node_exporter-1.12.1.linux-amd64.tar.gz` | `b51d8a76aa2a9156a55d501aca6276fae09e262259a5e4e831d2c2222f084e63` |
| `postgres_exporter-0.17.1.linux-amd64.tar.gz` | `6da7d2edafd69ecceb08addec876786fa609849f6d5f903987c0d61c3fc89506` |
| `host-db-observation-20260919.tar.gz` · 21,947,209바이트 | `3dfcb05d5a56a22d8a149961c7df1693ee02bd081a8461f3987f7aeaf4623bfd` |
| `msp-storage-20260919-final.tar.gz` · 46,050바이트 | `fe0f49222a52606f719dfa3fa9bb727397c9e2f031b527bfb6b533fb5779c873` |

학교에서는 기존 비공개 버킷 `2026-ai-kopo-04-exchange-artifacts-619256964117`에서 EC2 Role로 내려받았다. 개인 계정에서는 반출 조건을 확인한 후 해당 계정의 비공개 버킷으로 전달한다. 묶음에는 비밀 값이 없다. 중간 `msp-storage-20260919.tar.gz` 이후 눈금과 표시를 보완했으므로 화면 재배포에는 `-final` 파일을 쓴다.

새 호스트의 node_exporter 설치 순서는 계정 생성, 바이너리·설정 배치, 구문·주소 확인, 기동이다. 아래는 Primary 예시이며 앱·Standby에서는 주소를 바꾼다. 기존 계정이 있으면 먼저 용도와 권한을 확인하고 생성 단계를 건너뛴다.

```bash
sudo useradd --system --no-create-home --shell /sbin/nologin node_exporter
sudo install -m 0755 node_exporter /usr/local/bin/node_exporter
sudo install -d -m 0755 /etc/node_exporter
printf '%s\n' 'NODE_EXPORTER_ADDRESS=172.31.1.229' | sudo tee /etc/node_exporter/listen.env
sudo install -m 0644 node_exporter-host.service /etc/systemd/system/node_exporter.service
sudo systemctl daemon-reload
sudo systemctl enable --now node_exporter
sudo systemctl status node_exporter --no-pager
sudo ss -lntp
```

DB 두 대에는 OS 사용자 `failstop_monitor`를 만들고, PostgreSQL 역할은 Primary에서만 [계정 SQL](../../deploy/linux/observability/create-monitor-role.sql)을 실행한다. 역할 생성은 Standby로 복제된다. 기존 `local all all peer` 규칙을 사용했으며 TCP 인증 규칙이나 DB 비밀번호는 추가하지 않았다. 아래 SQL 파일은 최초 설치용이라 기존 역할이 있으면 재실행하지 않고 권한을 대조한다.

```bash
sudo useradd --system --no-create-home --shell /sbin/nologin failstop_monitor
# 이 SQL은 Primary에서만 실행한다.
sudo -u postgres psql -X -v ON_ERROR_STOP=1 -d exchange -f create-monitor-role.sql
# 두 DB에서 각각 peer 인증과 Primary/Standby 역할을 확인한다.
sudo -u failstop_monitor psql -X -P pager=off -d exchange -c 'SELECT current_user, pg_is_in_recovery();'
```

역할은 `CONNECT`, `pg_read_all_stats`, `pg_read_all_settings`, `pg_ls_waldir()` 실행 권한만 추가한다. 연결 한도는 3, 기본 트랜잭션은 읽기 전용이며 문장 3초·잠금 500ms 제한을 둔다. 통계 권한에는 다른 세션의 상태·질의 정보 열람이 포함되므로 일반 사용자용 계정으로 쓰지 않는다. 거래 테이블 SELECT와 주문 쓰기·스키마 CREATE 권한이 없음을 확인했고, `SELECT 1 FROM public.accounts LIMIT 0`도 실제로 거부됐다.

postgres_exporter 바이너리는 `/usr/local/bin`, [서비스 파일](../../deploy/linux/observability/postgres_exporter.service)은 `/etc/systemd/system/postgres_exporter.service`, 쿼리는 `/etc/postgres_exporter/queries.yaml`에 배치한다. `/etc/postgres_exporter/listen.env`에는 `PG_EXPORTER_ADDRESS=<해당 DB 사설 주소>`만 넣는다. 서비스는 OS 사용자와 같은 DB 역할로 `/var/run/postgresql` 소켓에 접속한다. 비밀번호 파일은 필요 없다.

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now postgres_exporter
sudo systemctl status postgres_exporter --no-pager
sudo ss -lntp
```

봇에는 [Prometheus 설정](../../deploy/linux/observability/prometheus.yml)의 새 주소를 대조해 배치한다. 적용 전 파일을 백업하고 `promtool check config /etc/prometheus/prometheus.yml`이 통과한 뒤 Prometheus만 재시작한다. 학교의 적용 전 설정은 `/etc/prometheus/prometheus.yml.before-host-db`에 보관했다. 문제가 있으면 수집기를 중지하고 Prometheus 설정을 복원한다. 관측 설치를 되돌리려고 DB·거래 데이터나 복제를 초기화하지 않는다.

전체 8개 수집 대상이 UP이고 DB 두 대의 `pg_up=1`, `pg_exporter_last_scrape_error=0`을 확인했다. 호스트 수집기 실패는 없었다. Primary/Standby 구분, 비동기 복제 streaming, WAL 크기와 연결·잠금 지표가 수집된다. `pg_stat_statements`는 preload되지 않았으며 SQL 통계 수집 가능 값은 0이다. 쿼리별 처리량·지연을 보려면 확장 설치와 재시작 영향을 별도로 검토해야 한다.

7개 화면의 133개 쿼리 중 113개에 데이터가 있었고 실행 오류는 0개였다. 거래·요청이 없는 패널과 미적재 SQL 통계에 값이 없다는 사실은 그대로 표시한다. 최종 화면 확인 뒤 Grafana 약 503MiB·Prometheus 약 74MiB, 호스트 여유 908MiB였고 재시작·메모리 압박·OOM 이벤트는 0이었다. 이 짧은 확인은 시장 봇 동시 운영이나 장시간 부하 검증을 대신하지 않는다.

## 디스크가 차기 전에 확인하는 방법

SSM 터널을 연 뒤 `http://127.0.0.1:13000/d/msp-storage?var-customer=exchange&var-environment=aws`로 접속한다. 상단의 **07 · 디스크·용량**에서도 이동한다. 호스트별 사용률·남은 용량·파일시스템 크기·inode와 PostgreSQL의 DB·WAL 크기를 표시한다. 수집 실패나 오래된 샘플을 정상 0으로 만들지 않는다. 70% 주의, 85% 조치, 95% 긴급은 화면 색상 기준이며 Prometheus 경보나 Teams 발송은 아직 연결하지 않았다.

50GiB를 넘겨 계속 저장할 수 있는 구조가 아니다. 공간이 가득 차면 DB 쓰기와 로그 기록에 장애가 생길 수 있으므로 그 전에 원인을 찾는다. Primary와 Standby의 디스크는 각각 50GiB이며 복제 때문에 두 용량이 합쳐지지 않는다. 현재 DB 데이터·WAL·OS·로그가 같은 루트 볼륨을 사용한다. 화면의 파일시스템 약 49.9GiB와 EC2의 할당량 50GiB 표시는 구분한다.

마지막 측정에서 DB 두 대는 각각 약 4.4% 사용·47.7GiB 여유, exchange DB는 약 13.3MiB, WAL 디렉터리는 각각 32MiB였다. 지금 정리할 필요는 없으며 이번 작업에서 파일이나 거래 데이터를 삭제하지 않았다.

용량이 증가하면 먼저 Linux에서 어느 경로가 커졌는지 확인한다. 아래 명령은 조회용이다. `du`는 큰 디스크에서 I/O를 사용하므로 운영 중에는 필요한 경로부터 확인한다.

```bash
df -hT /
df -i /
sudo du -xsh /var/log /var/lib/pgsql/data
sudo du -xsh /var/lib/pgsql/data/pg_wal
sudo journalctl --disk-usage
sudo -u postgres psql -X -P pager=off -d exchange -c 'SELECT pg_size_pretty(pg_database_size(current_database()));'
sudo -u postgres psql -X -P pager=off -d exchange -c 'SELECT slot_name, active, restart_lsn, wal_status FROM pg_replication_slots;'
```

로그·패키지 캐시·오래된 배포 파일·백업이 원인이면 필요한 증거와 복구 파일을 보존한 뒤 해당 프로그램의 정리 방법과 보존 기간을 정한다. journald는 이미 256MiB·7일 한도를 두었지만 이것이 Nginx·PostgreSQL 파일 로그 전체의 상한은 아니다. 백업은 새 백업의 복원 가능성을 확인하기 전에 마지막 복구본을 지우지 않는다. 삭제한 파일을 프로세스가 계속 열고 있으면 용량이 바로 돌아오지 않을 수도 있다.

DB 자체가 커졌으면 거래 기록의 보존 정책·별도 보관과 용량 증설을 검토한다. `pg_wal`이나 데이터 파일을 탐색기·`rm`으로 지우면 복구에 필요한 기록을 잃을 수 있다. 복제 슬롯이 WAL을 붙잡는다면 Standby 연결과 복제 진행을 먼저 복구한다. 원인 확인 없이 슬롯을 삭제하는 절차도 쓰지 않는다.

일반 `VACUUM`은 삭제된 행 공간을 DB 안에서 다시 쓰게 하며 대개 OS에 공간을 돌려주지 않는다. `VACUUM FULL`은 추가 공간과 강한 잠금이 필요한 재작성 작업이므로 디스크가 꽉 찬 상황의 첫 조치로 실행하지 않는다. 데이터 정리나 이 작업은 보존 요구·여유 공간·정지 시간을 정한 뒤 수행한다. [PostgreSQL VACUUM 설명](https://www.postgresql.org/docs/17/routine-vacuuming.html)

## 뒤에서 놓치면 안 되는 조건

### AI·Teams 운영 보고

[보고 소스](../../deploy/linux/reporting/report.py)는 Prometheus에서 bot-1 수치와 필요한 서비스 상태를 읽는다. 수집 실패나 오래된 데이터가 있으면 확인 대상으로 표시한다. AI 의견과 별개로 실측값·판정·확인 항목을 보고에 남기며, 모델이 조치를 실행하는 기능은 없다. 현재의 일일 보고는 매일 한 번 실행하는 상태 점검이다. 하루 전체의 가용성 통계나 로그 종합 분석으로 설명하지 않는다.

사용자 승인으로 기존 API 키·웹훅을 `/etc/exchange-report/` 아래 root 0600 파일에 저장했다. systemd `LoadCredential`로 보고 전용 서비스에만 전달하며 값은 명령 이력·캡처·Git에 넣지 않는다. 로컬 원본 `.evn`도 Git에서 제외한다. 파일을 `source`로 실행하지 않는다. 비밀 파일 내용을 다른 계정의 이전 설명서나 배포 ZIP에 넣지 않는다.

`exchange-report-daily.timer`는 한국 시간 오전 9시, `exchange-report-incident.timer`는 직전 실행이 끝난 뒤 2분마다 확인한다. 두 타이머를 활성화했고 최초 Teams 수신은 사용자가 확인했다. 상태가 그대로면 AI를 호출하거나 메시지를 보내지 않는다. 일일 보고와 장애 확인은 같은 잠금을 사용한다. 일일 보고는 먼저 실행한 작업을 기다리고, 장애 확인은 바쁘면 다음 예약에 다시 확인한다.

실행 상태는 `systemctl list-timers 'exchange-report-*' --all --full --no-pager`, 최근 결과는 `sudo journalctl -u exchange-report@daily.service -u exchange-report@incident.service -n 30 --no-pager`로 확인한다. `/var/lib/exchange-report/latest-snapshot.json`에 수집값, `latest-report.json`에 보고 본문, `delivery-state.json`에 중복 방지·전송 상태를 남긴다. 같은 날 일일 보고를 다시 실행해도 이미 접수된 보고는 재전송하지 않는다.

전송 중 연결이 끊기면 Teams가 메시지를 받았는지 모호할 수 있다. 이 경우 상태 파일의 `pending`을 유지하고 자동 발송을 멈춘다. Teams 채널과 Workflows 실행 기록을 확인한 뒤 해당 보고가 도착했는지 판단해야 한다. 원인 확인 없이 상태 파일을 지우거나 초기화하면 중복 보고가 생길 수 있다. HTTP 접수 성공과 채널 표시도 별도로 확인한다.

첫 시험에서는 마지막 수집 후 경과 시간을 AI가 지연으로 설명했다. 15초 수집 주기·60초 신선도 기준을 입력에 명시하고, 이상 항목이 없으면 원인을 추측하지 않도록 고쳤다. 수정본의 AWS 재호출은 정상 범위 수치만 요약했다. 첫 Teams 메시지를 수정하거나 추가 발송하지는 않았다. 실제 장애와 회복 전송, 보고 노드 자체 장애에 대한 외부 감시, 하루 통계·앱·DB·로그 연동은 후속 작업이다.

### 계정과 배포 범위

학교에서는 기본 VPC와 퍼블릭 서브넷을 사용한다. NAT Gateway·새 private subnet·VPC endpoint·Route 53·ACM·새 IAM 역할·Access Key는 만들지 않는다. 학교의 50GiB 안내는 디스크 설정 조건이며 EC2 대수나 계정 전체 할당량을 확인한 결과가 아니다. 서버는 현재 실행 중이라 비용이 발생한다. 월 40만 원 예산의 여러 노드 구성을 만들기 전에 가동 시간과 디스크·IPv4·트래픽 비용을 함께 계산한다.

공개 전에는 주문 커밋 결과가 불분명할 때 멱등 키를 삭제하고 새 키로 재시도하는 경로를 재현·수정·검증해야 한다. 기본 DB 연결 풀을 여러 앱에 그대로 적용하면 PostgreSQL 연결 한도를 넘을 수 있다. 이 위험과 조회 ASG의 범위는 0099를 따른다. 앱 리더 전환과 PostgreSQL 수동 승격은 다른 작업이며, 비동기 DB 복제는 유실 가능성이 있다.

2026년 10월 초 개인 계정으로 재배포해 학교에서 제한된 부분을 보강할 계획이다. 학교 실험 성공만으로 원래 도면의 모든 자원이 검증됐다고 쓰지 않는다. 개인 크레딧 잔액·만료와 학교 데이터 반출 조건은 이전 전에 다시 확인한다.

## 한 달 상시 운영과 두 계정 연결 검토

2026-09-19 사용자가 30일·24시간 상시 운영과 `www.sang.it.kr` 사용을 지정했다. 조회 ASG는 최소 2대·최초 희망 2대·최대 3대, NAT Gateway는 학교·개인 실습 모두 제외하는 것으로 정했다. [ADR 0030](../reference/adr/0030-nat-free-lab-and-bounded-read-asg.md)은 이 설계 결정이며 실제 ASG 구축 완료를 뜻하지 않는다. 두 계정 연결은 학교 권한에 막혀 있고, 개인 계정 자원·DNS·인증서·피어링은 생성하거나 변경하지 않았다.

개인 계정 콘솔에서 총 예상 크레딧 잔액 $163.62, 사용액 $36.38을 확인했다. 활성 크레딧은 6개이며 표시된 만료일은 모두 2027-05-27이었다. 기본 Free Tier 크레딧의 적용 서비스 목록에는 EC2·ELB·VPC·CloudFront가 있다. 각 추가 크레딧의 상세 적용 조건과 계정 전체의 현재 비용·다른 리전 잔여 자원은 전수 확인하지 않았다. 잔액은 약 24시간 간격의 추정 갱신값이며 실제 현금 청구와 같지 않다. 학교와 개인 크레딧은 합산하지 않는다. 개인 계정 화면에는 크레딧 ID가 포함돼 원본 캡처·텍스트를 Git에서 제외했다.

### 피어링의 선행 조건

학교 VPC와 개인 서울 기본 VPC의 주소가 모두 `172.31.0.0/16`이다. 현재 두 VPC끼리는 피어링할 수 없다. 개인 계정에 겹치지 않는 새 VPC를 만드는 안을 검토한다. 예시 `10.20.0.0/16`도 개인 계정 전체 네트워크와 대조한 뒤 확정한다. 학교에서는 기존 라우팅을 임의로 바꾸지 않으므로 운영자의 피어링·왕복 경로 변경 허용이 선행 조건이다. 생성 권한 하나가 있어도 정책상 허용과 왕복 통신이 확보됐다는 뜻은 아니다. [AWS 주소 중복 제한](https://docs.aws.amazon.com/vpc/latest/peering/vpc-peering-basics.html)

분산안을 채택한다면 학교에는 기존 앱·DB 두 대·봇/관측을 유지하고, 개인 계정에 CloudFront·ALB·조회 ASG를 둔다. 주문·취소·리더 의존 조회·WebSocket은 기존 앱 경로, 분산 가능한 조회만 개인 ASG로 보낸다. 두 경로의 API 계약과 프록시·쿠키·인증 전달을 먼저 검증해야 한다. 현재 코드가 모든 GET 요청을 임의의 노드로 분산해도 된다는 뜻은 아니다. 학교 앱의 고가용성까지 시험하려면 학교 app-2가 추가로 필요하다.

개인 ALB가 학교 앱을 대상으로 삼을 때는 피어링 경로의 사설 IP 대상으로 등록한다. 개인 ASG는 개인 계정에서 만든 조회 인스턴스를 관리한다. 개인 계정에 ASG만 만들고 학교의 기존 EC2를 자동 증설 대상으로 붙이는 방식으로 설명하지 않는다. [ALB의 피어 VPC 대상 등록](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/load-balancer-target-groups.html)

피어링 자체에는 시간당 연결 요금이 없지만 교차 AZ 전송은 별도다. 같은 물리 AZ 내부는 계정이 달라도 무료이고, 다른 AZ 사이에는 각 끝에서 $0.01/GB가 부과된다. 예를 들어 양방향을 합한 전송량이 100GB이면 학교 약 $1·개인 약 $1이며, 왕복을 다시 이중 합산하지 않는다. 계정의 AZ 이름보다 AZ ID를 대조한다. 학교 DB 복제의 교차 AZ 전송도 별도이며 이 경우 양 끝 비용 모두 학교 계정이다. [VPC 요금](https://aws.amazon.com/vpc/pricing/)

학교 라우팅 변경이 허용되지 않으면 이 분산안은 중단한다. DB 포트를 인터넷에 열어 대신 연결하지 않는다. 그 경우 학교 검증을 유지하고 개인 계정에 독립적으로 재배포하는 기존 계획으로 돌아간다.

### 학교 운영자에게 요청할 내용

학교 사용자 `2026-ai-kopo-04`로 실행한 피어링 생성(`ec2:CreateVpcPeeringConnection`)과 라우트 추가(`ec2:CreateRoute`)의 `--dry-run` 결과가 모두 `UnauthorizedOperation`이었다. 오류는 해당 작업을 허용하는 사용자 정책이 없다고 명시했다. 실제 연결 요청이 없어 수락 권한은 검사하지 않았다. 피어링 생성 검사는 기존 개인 VPC를 지정한 권한 검사로, 주소 중복이 해소됐다는 뜻은 아니다. 라우트 검사는 기존 기본 경로와 IGW를 인자로 사용했으며 새 피어링 경로의 유효성을 확인한 검사가 아니다. [검사 원문과 기존 라우팅 화면](../../증거본/2026-09-19_학교AWS/README.md)

아래 내용은 전달용으로 준비했으며 운영자에게 전송하지 않았다.

> 학교의 Linux·DB·관측 서버와 개인 계정의 ALB·조회 ASG를 사설 통신으로 연결할 수 있는지 확인 부탁드립니다. 학교 계정은 `619256964117`, 사용자는 `2026-ai-kopo-04`, VPC는 `vpc-07ad90c6c3880faee`(`172.31.0.0/16`)입니다. 개인 계정은 `377228489329`이며, 기존 기본 VPC도 같은 대역이라 새 VPC가 필요합니다. 새 대역은 `10.20.0.0/16`을 후보로 두고 중복 여부를 확인한 뒤 확정하겠습니다.
>
> 학교 사용자로 피어링 생성과 라우트 추가를 변경 없이 검사했으나 권한이 거부됐습니다. 학교 정책상 계정 간 연결이 허용된다면 운영자께서 피어링 생성 또는 수락과 해당 서버의 왕복 경로를 설정해 주실 수 있는지 요청드립니다. 현재 학교 라우트 테이블 `rtb-089b2928f869157a4`는 공유 기본 테이블입니다. 기존 `0.0.0.0/0 → igw-06f91ef880c597734` 경로를 바꾸지 않고, 다른 팀에 미치는 영향을 검토해 대상 서브넷에만 적용할 경로를 정해야 합니다.
>
> 서비스·DB·관측 통신의 출발지와 필요한 포트만 별도 확정하겠습니다. DB의 인터넷 공개, 새 IAM 역할·Access Key, NAT Gateway는 요청하지 않습니다. 연결이 허용되지 않으면 학교 검증을 유지하고 개인 계정에 독립 환경을 구성하겠습니다.

운영자가 허용한 범위를 받기 전에는 개인 VPC 생성부터 진행하지 않는다. 허용되면 CIDR·AZ ID·서브넷과 라우트 연결 관계를 먼저 확정하고, 승인된 양방향 경로와 SG만 적용한 뒤 실제 통신을 검사한다. 피어링은 양쪽 서버 서브넷에 왕복 경로가 필요하며, 같은 리전의 다른 계정 SG 참조는 활성 피어링과 계정 ID·SG ID를 전제로 한다. [AWS 라우팅 안내](https://docs.aws.amazon.com/vpc/latest/peering/vpc-peering-routing.html), [피어 SG 참조 조건](https://docs.aws.amazon.com/vpc/latest/peering/vpc-peering-security-groups.html)

### 월요일 상담에서 결정받을 범위

2026-09-19 학교 안내 페이지의 EC2 규격·보안 그룹 절차·제한 사항을 브라우저로 다시 확인했다. 개인 계정 연결과 금지 항목의 예외 요청은 상담에서 제외한다. 아래 두 가지만 묻는다. [학교 AWS 안내](https://rowan-cupboard-1fe.notion.site/AWS-Management-Console-Credentials-AIKOPO-Detailed-3dd2156ceb5f8096a9cbf02253b18f2a)

1. ALB와 조회 서버 Auto Scaling을 사용할 수 있는지 확인한다. 현재 ALB 생성과 시작 템플릿 생성은 거부됐으며 ASG 생성 자체는 미검사다. 안내의 금지 목록에 ALB·ASG가 명시돼 있지 않지만 미기재를 허용으로 단정하지 않는다. 기존 기본 VPC·보안 그룹·제공된 프로파일을 사용해 조회 서버 2→3→2대 증설·축소를 시험하려는 목적을 설명한다.
2. 현재 서버와 추가할 조회 서버를 월 40만 원 범위에서 한 달 상시 운영해도 되는지 확인한다. 서버별 t3.small 이하·gp3 50GiB 이하 조건은 그대로 지킨다. 전체 대수와 사용 기간은 안내에서 확인하지 못했으므로 운영자가 가능한 규모와 기간을 안내하도록 요청한다.

교수님께는 다음 정도로 설명한다.

> 교수님, AWS 안내에 적힌 제한을 지키면서 부하에 따라 조회 서버가 2대에서 3대로 늘고 다시 줄어드는 실험을 하려고 합니다. 현재 ALB와 시작 템플릿 생성이 권한 오류로 막히는데, ALB·Auto Scaling 사용이 가능한지 확인 부탁드립니다. 가능하다면 기존 서버와 함께 월 40만 원 범위에서 한 달 운영해도 되는지도 여쭙고 싶습니다.

안내에서 이미 정한 Route 53·ACM·VPC 구성 변경·NAT·새 IAM 사용자와 역할·Access Key·CI/CD·비용 및 감사 도구의 금지 해제는 요청하지 않는다. SG 규칙 수정 권한도 요청 목록에서 뺐다. SG는 별도 화면에서 빈 상태로 생성하고 자동 태그 확인 후 인바운드를 추가하며, EC2 생성 화면에서는 기존 SG를 선택하는 안내를 따른다. CloudFront 기본 도메인과 CloudWatch Logs·Metrics는 안내의 대안으로 제시돼 있어 교수님께 다시 허가를 구할 항목으로 늘리지 않는다. 실제 API 접근이 막히면 그 오류만 담당자에게 확인한다.

### 30일 비용 계산

서울 Linux On-Demand, t3.small, gp3 기본 IOPS·처리량, 720시간을 기준으로 계산했다. 할인·크레딧·세금은 차감하거나 더하지 않았다. 공인 IPv4는 EC2당 1개, 인터넷 ALB는 2개를 가정했다. 개인 조회 노드는 NAT 없는 퍼블릭 서브넷과 제한 SG를 가정한 실습 견적이며, 원래 private subnet·NAT 도면의 비용이 아니다. ALB는 평균 1 LCU 가정이므로 트래픽에 따라 달라진다.

| 단가·항목 | 계산 |
|---|---|
| t3.small | $0.026/시간, 한 대 720시간 $18.72 |
| 공인 IPv4 | $0.005/시간, 한 개 720시간 $3.60 |
| gp3 | $0.0912/GB·월, 20GiB $1.824·50GiB $4.56 |
| ALB 기본 | $0.0225/시간 × 720 = $16.20 |
| ALB 평균 1 LCU | $0.008 × 720 = $5.76 |
| ALB 공인 IPv4 두 개 | $7.20 |
| ALB 위 세 항목 합 | $29.16 |

공식 가격표의 조회 시점 단가를 사용했다. [서울 EC2·EBS 가격표](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEC2/current/ap-northeast-2/index.csv), [서울 ELB 가격표](https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AWSELB/current/ap-northeast-2/index.json), [공인 IPv4 요금](https://aws.amazon.com/vpc/pricing/). 기존 절의 730시간 계산과 가동 시간 가정이 다르다.

| 비교안 | 학교 | 개인 | 합계 |
|---|---:|---:|---:|
| 현재 학교 4대·140GiB 유지 | $102.05 | 추가 구성 없음 | $102.05 |
| 학교 4대 유지 + 개인 ALB·조회 ASG 2대/40GiB | $102.05 | $77.45 | $179.50 |
| 학교 app-2 추가: 5대·160GiB + 개인 ALB·조회 ASG 2대/40GiB | $126.19 | $77.45 | $203.64 |
| 학교 4대 유지 + 개인 조회 ASG 3대/60GiB를 한 달 유지·ALB | $102.05 | $101.59 | $203.64 |
| 학교 5대 유지 + 개인 조회 ASG 3대/60GiB를 한 달 유지·ALB | $126.19 | $101.59 | $227.78 |

ASG는 min 2 / desired 2 / max 3으로 계획한다. 기본 두 대에 세 번째 t3.small·20GiB·공인 IPv4 노드가 추가되면 약 $0.0335/시간, 10시간 약 $0.335, 한 달 내내 추가하면 $24.144다. 이 계산은 종료 때 추가 볼륨도 삭제하고 잔여 자원을 남기지 않는 조건이다. ASG 자체 요금은 없으나 인스턴스·EBS·지표·경보 요금은 발생한다. max 3을 전체 비용 상한으로 보지 않으며 교체 중 일시적인 추가 용량도 배포 정책에서 검토한다. [EC2 Auto Scaling 요금](https://docs.aws.amazon.com/autoscaling/ec2/userguide/what-is-amazon-ec2-auto-scaling.html)

위 합계에는 교차 AZ·피어링 전송, CloudFront 유료 사용분, S3·백업·스냅샷·CloudWatch·AI API·도메인 갱신·부가세가 없다. ALB 추가 IPv4, gp3 추가 성능, T3 Unlimited CPU 크레딧도 제외했다. 학교는 현재 Standard라 CPU 크레딧 고갈 시 성능 제한을 관측해야 한다. 개인 노드도 크레딧 모드를 비용·부하 계획과 함께 정한다. 고정비가 작아도 무제한 고부하를 가정하지 않는다.

현재 실습에서는 NAT Gateway를 제외한다. 원래 설계의 서울 NAT 두 대와 공인 IPv4 시간 비용 $92.16/30일 및 별도 데이터 처리 요금을 피한다. 현재 표에는 대신 각 EC2의 공인 IP 비용을 포함했다. 이 금액은 NAT 항목의 비교값이며 구성 전체의 순절감액과 구분한다. 기존 Internet Gateway는 유지한다.

개인 계정의 기본 구성 계산값은 약 $77.45, 세 번째 노드를 한 달 유지한 계산값은 약 $101.59다. 여기에 위에서 제외한 실제 사용량을 더해야 하므로 크레딧 $163.62만 보고 한 달 운영이 보장된다고 판단하지 않는다. 학교도 현재 4대와 app-2를 추가한 5대 비용을 구분한다. 두 계정을 독립 배포하면 개인 DB·관측 비용이 추가되므로 이 분산안 표를 재사용할 수 없다. 실제 환율·과세·크레딧 적용 뒤 현금 청구액은 별도로 확인한다. 학교 지원금 40만 원을 반드시 소진하는 정산 조건은 운영자에게 확인해야 하며 불필요한 자원을 늘려 금액을 맞추지 않는다.

### 도메인과 인증서

2026-09-19 23:19 KST에 사용자가 학교 앱으로 HTTP 임시 연결을 확정했다. 가비아에서 `www`의 기존 ALB CNAME을 `A 43.201.253.75`, TTL 600초로 교체했다. 기존 ACM 검증 CNAME은 유지했다. 가비아 권한 네임서버의 A 응답과 PC에서 HTTP 200, 브라우저의 거래소 화면을 확인했다. DNS 변경 전후와 실제 화면은 [증거 381~384](../../증거본/2026-09-19_학교AWS/README.md)에 보관했다.

이후 사용자 승인으로 PC 제한 미리보기를 공개 HTTPS로 전환했다. 기존 앱 SG에 TCP 80·443의 `0.0.0.0/0` 규칙만 추가했다. 80은 인증서 검증과 HTTPS 이동, 443은 거래 화면·사용자 API·WebSocket에 쓴다. 최초 공개 때는 `/admin`, 관리자 API, `/actuator`를 Nginx에서 차단했고 DB·관측 SG는 바꾸지 않았다. 이후 `/admin/bots` 로그인 경로를 별도 승인으로 연결했다. 인증서 발급과 자동 갱신 시험, HTTPS 200·HTTP 308, 관리 경로 403, 무인증 자산 조회 401과 데모 자산 조회 200을 확인했다. [증거 386~401](../../증거본/2026-09-19_학교AWS/README.md)

학교 app-1에서 Amazon Linux 공식 패키지의 Certbot 2.6.0을 사용했다. 인증서는 `www.sang.it.kr` 하나에 발급했으며 만료일은 2026-12-18이다. 개인키는 서버의 `/etc/letsencrypt` 안에 두고 복사하거나 증거에 출력하지 않았다. `sang.it.kr` 자체나 다른 서브도메인용 인증서는 아니다. 개인 계정 CloudFront·ACM은 사용자가 정정해 진행하지 않았다. 아래 개인 계정 설명은 후속 검토안이다.

다른 서버에서 재현할 때는 DNS가 해당 서버를 가리키는지 먼저 확인한다. [ACME 준비 스크립트](../../deploy/linux/app/prepare-acme.py)는 이번 학교 미리보기 설정에만 맞춰져 있다. 기존 설정과 다르면 그대로 실행하지 않고 `/.well-known/acme-challenge/`가 `/var/lib/letsencrypt`를 제공하도록 맞춘다. 80 포트에서 그 경로로 접근할 수 있어야 다음 HTTP-01 발급과 갱신이 성공한다. [Let’s Encrypt 검증 방식](https://letsencrypt.org/docs/challenge-types/)

```bash
sudo dnf install -y certbot
sudo certbot certonly --webroot -w /var/lib/letsencrypt -d www.sang.it.kr \
  --non-interactive --agree-tos --register-unsafely-without-email
```

약관은 사용자가 발급 직전에 동의했다. 이번에는 연락 이메일 없이 등록했다. 재배포에서는 계정 담당자가 약관과 연락 방식도 결정한다. 발급한 `fullchain.pem`과 `privkey.pem` 경로는 [Nginx HTTPS 설정](../../deploy/linux/app/nginx-https.conf)에 들어 있다. 이 설정을 `/tmp/nginx-https.conf`로 준비하고 [적용 스크립트](../../deploy/linux/app/activate-https.py)를 `/tmp/activate-https.py`로 복사한 뒤 실행했다. 현재 HTTPS 설정 원본에는 Grafana·봇 관리자 include가 추가돼 있으므로 새 서버에서는 두 경로 설정과 내부 인증서·비밀번호 파일을 먼저 준비한다. 과거 최초 공개 검사인 `verify-public.py`의 `/admin` 차단 전제도 현재와 다르므로 지금은 `verify-admin-public.py`로 관리 접근을 검사한다. 스크립트는 Nginx 설정과 앱 오리진 설정을 `.before-https`로 백업하고 구문 검사 후 앱을 재시작한다. 원본의 `EXCHANGE_CORS_ALLOWED_ORIGINS`와 `APP_WS_ALLOWED_ORIGINS`는 모두 `https://www.sang.it.kr`로 바꿨다. 앱의 8080·8081은 루프백만 유지한다.

```bash
sudo python3 /tmp/activate-https.py
curl -fsS http://127.0.0.1:8081/actuator/health/readiness
sudo install -o root -g root -m 0755 /tmp/certbot-nginx-reload.sh \
  /etc/letsencrypt/renewal-hooks/deploy/nginx-reload
sudo systemctl enable --now certbot-renew.timer
systemctl list-timers certbot-renew.timer --no-pager
sudo certbot renew --dry-run --run-deploy-hooks
```

`/tmp/certbot-nginx-reload.sh`의 원본은 [갱신 후 Nginx 적용 훅](../../deploy/linux/app/certbot-nginx-reload.sh)이다. 타이머 활성화와 모의 갱신 성공을 확인했다. 훅은 `nginx -t`에 성공해야 reload한다. Certbot 출력의 `Hook ran with error output` 아래 두 줄은 Nginx가 표준 오류에 쓰는 구문 검사 성공 메시지이며, 갱신 결과는 success였다. 실제 만료 시점의 자동 갱신은 아직 도래하지 않았다. 이후에는 `journalctl -u certbot-renew.service`와 타이머 상태를 점검한다. 갱신 실패의 Teams 경보는 아직 연결하지 않았다.

문제가 생기면 `.before-https` 백업 두 파일을 확인한 뒤 `/etc/nginx/nginx.conf`와 `/etc/exchange/app.env`를 복원하고 앱 재시작·`nginx -t`·Nginx reload 순서로 되돌린다. 이 백업은 PC IP 제한과 ACME 경로를 유지하므로 HTTPS 설정 복원과 SG 규칙 철회는 별도다. 기존 SG 규칙을 임의로 수정하지 않는다. 현재 공인 IP는 고정 IP가 아니므로 EC2 중지 후 다시 시작할 때 주소와 DNS를 재확인해야 한다.

빈 DB 카탈로그에는 [기본값 SQL](../../deploy/linux/app/bootstrap-markets.sql)로 자산 13개, 마켓 12개, 시스템 계정 39개를 한 트랜잭션으로 등록했다. 값의 원본은 개발 코드 `DevDataSeeder`와 `AccountBootstrap`이다. 기존 거래·회원·잔고를 삭제하지 않았으며 Flyway 파일도 바꾸지 않았다. 실행 전 Primary에서 `pg_dump -Fc`로 `/var/lib/pgsql/backups/exchange-before-markets-20260919.dump`를 보관하고 목록을 읽었다. 같은 디스크 안의 변경 전 백업이며 다른 서버로 복원해 검증한 재해 복구 백업은 아니다. SQL은 assets 또는 markets가 비어 있지 않으면 중단하므로 이미 등록한 서버에서 다시 실행하지 않는다.

Standby에서 같은 자산·마켓·시스템 계정 수와 `streaming`을 확인했다. 앱 재시작 후 [공개 경로 검사](../../deploy/linux/app/verify-public.py)와 브라우저의 '연결됨' 표시를 확인했다. 데모 자산 13개 조회도 성공했다. 이 HTTPS 확인 시점에는 봇이 없어 차트·체결 내역이 비어 있었다. 이후 시장 봇 연결 결과는 아래 절에 기록했다. 회원 가입·로그인과 부하·장애 복구는 아직 검증하지 않았다.

변경 전 DNS는 `www`의 CNAME `sangkwon-alb-574477203.ap-northeast-2.elb.amazonaws.com.`, TTL 3600이었다. DNS를 그 시점으로 되돌릴 때의 기록이며, 기존 ALB가 현재도 서비스 가능한지는 별도 확인이 필요하다. 기존 ALB 자원을 삭제하지 않았다.

가비아의 `www` CNAME을 개인 CloudFront 배포 도메인으로 연결하고, `www.sang.it.kr`용 비내보내기 ACM 공개 인증서를 개인 계정 us-east-1에서 발급·연결하는 안이다. DNS 검증 CNAME은 가비아에 유지한다. CloudFront용 인증서를 PC로 다운로드할 필요는 없고 해당 종류는 발급 비용이 없다. ALB HTTPS 원본을 쓰면 원본 이름에 맞는 ACM 인증서는 ALB와 같은 서울 리전에 별도로 둔다. 브라우저부터 CloudFront까지만 HTTPS라고 전체 구간 암호화라고 하지 않는다. [CloudFront 인증서 리전](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/cnames-and-https-requirements.html), [ACM 요금](https://aws.amazon.com/certificate-manager/pricing/)

CloudFront는 정적 파일 캐시와 API·인증·주문·WS 정책을 분리한다. 사용자별 API 응답은 캐시하지 않는다. 정액 Free 요금제와 종량제 무료 제공량은 서로 다른 정책이므로 배포 때 하나를 선택하고 제공량을 계정에서 확인한다. 예산에서 CDN $0을 가정하려면 선택한 요금제의 무료 범위 안에 있다는 조건이 필요하다. [CloudFront 요금](https://aws.amazon.com/cloudfront/pricing/)

## 시장 봇을 시작하고 확인하는 방법

bot-1에서 기존 거래 앱과 같은 JAR를 별도 `exchange-bot` 서비스로 실행한다. 봇은 HTTPS 주문 API를 호출하고, DB는 봇 계정·초기 가상 자산·제어 상태에 사용한다. 매칭 엔진과 Flyway는 꺼 두었다. 사용자 페이지는 [BTC 거래소](https://www.sang.it.kr/exchange/KRW-BTC)에서 볼 수 있다. 첫 기동은 2026-09-19 23:58 KST이며 아래 검증은 자정을 넘겨 진행했다.

첫 시험은 BTC 한 종목·주문과 취소 합계 목표 2 OPS로 시작했다. 9월 20일 00:14 KST부터 BTC·ETH·XRP 세 종목, 전체 합계 목표 3 OPS로 늘렸다. 종목마다 MM 2개와 TAKER·TREND·NOISE 각 1개를 활성 목표로 두었다. 기존 봇의 무작위 대기·주문 크기·시장 국면을 사용하므로 일정한 간격의 같은 주문만 반복하지 않는다. 토큰 버킷은 짧은 순간의 요청 묶음을 허용하므로 매초 정확히 3회라는 뜻은 아니다. 조회 요청도 이 목표와 별개다. 타입별 예비 풀의 봇 계정 360개를 일반 회원 수나 활성 봇 수로 해석하지 않는다. 설정은 [bot.env.school](../../deploy/linux/bot/bot.env.school), 서비스 상한은 [exchange-bot.service](../../deploy/linux/bot/exchange-bot.service)에 있다.

재배포할 때는 다음 순서로 준비한다.

1. 앱의 HTTPS와 Primary·Standby 복제를 먼저 확인한다. 기존 DB SG에 TCP 5432의 소스를 봇 SG 하나로 추가한다. 학교 적용 규칙은 `sgr-0fdf7835ee54f9a75`다. Primary의 `/var/lib/pgsql/data/pg_hba.conf`를 백업한 뒤 `hostssl exchange exchange_app 172.31.44.141/32 scram-sha-256`을 추가하고 규칙 오류가 없는지 확인한 뒤 reload한다. 이번에는 기존 세 규칙을 보존했고 Standby HBA는 변경하지 않았다.
2. 봇에서 RSA 수신자 키와 공개 인증서를 root 전용 경로에 만든다. 공개 인증서만 앱으로 복사한다. [설정 전달 도구](../../deploy/linux/bot/transfer-secrets.py)의 `seal`은 앱의 DB 비밀번호와 인증 설정 세 항목, DB 공개 인증서를 읽어 OpenSSL CMS로 암호화한다. 원문을 출력하거나 로컬 PC에 복사하지 않는다.
3. 암호문만 기존 비공개 S3의 `transfer/bot-settings-20260919.cms`에 올리고 봇의 기존 Role로 내려받는다. `open --sha256 <앱에서 확인한 암호문 해시>`로 무결성을 대조해 복호화한다. 봇의 `/etc/exchange-bot/bot-secret.env`는 root 소유 0600, DB 공개 인증서는 0644다. 전달용 파일은 두 서버의 `/root/exchange-bot-transfer`에 남아 있으며 S3 암호문의 별도 만료·삭제는 설정하지 않았다. 이 경로와 수신자 개인키도 비밀 자료로 관리한다.
4. 앱과 같은 `exchange-app-3362117.zip`을 봇의 `/tmp`로 받고, 위 설정·서비스 파일과 [설치 스크립트](../../deploy/linux/bot/install-bot.sh)를 `/tmp`에 배치한다. 스크립트가 ZIP 해시를 대조하고 로그인 불가 사용자·JAR 경로·파일 권한·systemd 구문을 확인한다. 다른 계정에서는 사설 IP와 HTTPS 주소, 인증서·비밀 값을 해당 환경에 맞춘다. 기존 설치를 덮는 업데이트 스크립트는 아니다.
5. 용량 보호용 `capacity_guard.py`, `exchange-bot-capacity.service`, `exchange-bot-capacity.timer`도 `/tmp`에 함께 배치한다. 아래 명령으로 기동한 뒤 서비스 로그, 실제 체결, 원장과 메모리를 확인한다. 현재 봇은 부팅 시 자동 시작하며 오류 종료에는 60초 뒤 재시작한다. 15분 안에 시작 시도가 3회에 이르면 systemd가 추가 시작을 제한한다.

```bash
sudo bash /tmp/install-bot.sh
sudo systemctl start exchange-bot
sudo systemctl enable exchange-bot
sudo systemctl enable --now exchange-bot-capacity.timer
sudo journalctl -u exchange-bot -n 40 --no-pager -o cat
curl -fsS http://127.0.0.1:8081/actuator/health/liveness
sudo systemctl show exchange-bot -p ActiveState -p MemoryCurrent -p NRestarts
free -m
cat /sys/fs/cgroup/system.slice/exchange-bot.service/memory.events
```

봇 최초 기동은 HTTP 8080·관리 8081을 루프백에 두었다. 현재는 아래 관리자 설정을 추가해 HTTP 서비스가 사설 mTLS 8443으로 바뀌었고 관리 지표 8081은 루프백이다. 봇 SG의 3000·8443은 앱 SG만 허용한다. 봇은 엔진이 꺼져 있어 거래 서버용 readiness가 요청 수락을 거부할 수 있다. 봇 확인에는 liveness와 실제 주문 결과를 사용하며 로드밸런서의 거래 대상으로 등록하지 않는다. 기동 때 이 readiness 정정을 알리는 경고가 발생했다.

DB 연결 풀 상한은 요청 4개·append 1개·lease 1개다. 현재 앱의 32개와 합해 최대 38개이며, 향후 두 앱이면 70개다. DB 최대 연결 100개 안에서 관측·관리·추가 조회 노드도 합산해야 한다. `exchange_app`을 공유하는 시험 구성이고 봇 전용 DB 권한을 새로 분리한 것은 아니다.

00:00:35 KST 조회에서 주문 83건·체결 42건, `balance_ledger_check` 불일치 0건, 기록된 자산별 원장 합계 0을 확인했다. bot-1의 DB 연결은 TLS 1.3이고 Standby는 async·streaming, 해당 표본의 재생 차이는 0바이트였다. 이것이 비동기 복제의 무손실 보장을 뜻하지 않는다. DB 크기는 16,750,259바이트였지만 짧은 표본이라 하루 증가량으로 환산하지 않는다.

동시 기동 직후 봇 약 287MiB·Grafana 약 157MiB·Prometheus 약 88MiB, 호스트 가용 메모리 약 976MiB를 확인했다. 봇의 OOM·메모리 상한 도달은 0건이며 기존 Prometheus 대상 8개도 모두 UP이었다. 봇 자체의 Java 지표를 새 수집 대상으로 추가한 것은 아니다. 브라우저에서 실제 호가·체결·1분 캔들 갱신을 확인했다. 장시간 운영이나 모든 Grafana 화면을 동시에 연 최악 조건은 검증하지 않았다. [증거 402~419](../../증거본/2026-09-19_학교AWS/README.md#시장-봇의-첫-aws-체결)

시험을 멈출 때는 `sudo systemctl stop exchange-bot`을 실행하고 종료 로그를 확인한다. 기존 주문과 거래 기록은 보존한다. 중지 직전의 미체결 주문까지 모두 취소됐다고 가정하지 말고 상태를 확인한다. 재부팅 뒤에도 중지를 유지하려면 `sudo systemctl disable exchange-bot`을 함께 실행한다. 메모리 압박이 재발하면 봇 속도를 올리지 않고 사용자가 지정한 관측 노드 분리를 판단한다.

세 종목으로 확대한 뒤 실제 ETH·XRP 체결과 차트 갱신, 원장 불일치 0건과 자산별 원장 합계 0을 확인했다. 당시 봇 메모리는 약 298MiB, 호스트 가용 메모리는 약 956MiB였고 OOM은 없었다. 따라서 현재는 봇과 Grafana를 같은 서버에서 운영하되 장시간 사용 결과를 계속 본다. [세 종목 기동 증거](../../증거본/2026-09-20_시장봇/README.md)

### 저장 공간이 부족해지기 전에 멈추기

[용량 보호 도구](../../deploy/linux/bot/capacity_guard.py)는 1분마다 Prometheus에서 앱·봇·두 DB의 루트 파일시스템을 확인한다. 어느 서버든 여유 공간이 20% 또는 2GiB 이하이거나 inode 여유가 10% 이하이면 봇 서비스만 멈춘다. 지표 누락·오류·90초 이상 오래된 수집이 세 번 연속 발생해도 멈춘다. 데이터는 삭제하지 않고 자동으로 다시 시작하지도 않는다. 다만 서버를 재부팅하면 활성화된 봇 서비스가 다시 시작하므로, 용량 문제로 장기 중지할 때는 위의 disable 명령도 필요하다.

현재 판정은 `/var/lib/exchange-bot-guard/state.json`, 시간별 용량 표본은 같은 디렉터리의 `hourly-capacity.csv`에 남는다. 정상 상태의 타이머 실행과 로컬 판정 테스트 4개를 확인했다. 실제 디스크를 가득 채워 중지를 유발하는 시험은 하지 않았다. 사용자는 10월 16일까지 가동할 계획이나 종료 시각과 예약 중지는 아직 설정하지 않았다. 하루 증가량은 충분한 표본을 모은 뒤 계산하며 하루 1~2GB라는 추정을 측정값으로 쓰지 않는다.

### 어드민과 용량 관리

현재 봇 관리자 주소는 `https://www.sang.it.kr/admin/bots`다. 사용자 로그인과 BTC·ETH·XRP 15대 조회, 현재 대수를 유지하는 `sim.scale` 실행을 확인했다. DB 감사에는 `fleet_present=true`와 적용 대수가 기록됐다. 웹에서 3→2→3 OPS를 적용해 서버 현재·목표 값이 함께 바뀌는 것도 확인했다. 시험 후 3 OPS로 복귀했다. 속도 슬라이더는 1 OPS 단위이며 현재 기본값은 전체 3 OPS다. 사용자 PC에 인증서를 설치하지 않는다. 아이디 `dltkdgur`와 앱 서버에서 직접 만든 비밀번호를 쓰며 Grafana와는 별도 계정이다. 현재 운영자는 조회와 봇 제어만 할 수 있다. 학교 배포의 신뢰 경계와 제한은 [ADR 0031](../reference/adr/0031-school-admin-password-gateway.md)을 따른다.

봇 JVM은 `prod,bots,admin`으로 실행하고 내부 관리 API를 `172.31.44.141:8443`에서 제공한다. 앱 SG만 이 포트에 접근할 수 있다. 앱 Nginx가 로그인 요청의 비밀번호를 확인한 뒤 내부 mTLS로 세션을 발급받으며 비밀번호를 봇 서버에 전달하지 않는다. 이후 요청은 브라우저 메모리의 단기 세션과 게이트웨이 인증서를 함께 검사한다. 거래 앱이나 봇의 매칭 엔진을 추가로 켜지 않는다.

#### 같은 구성을 다시 설치하는 순서

1. 앱에서 [인증서 준비 도구](../../deploy/linux/bot/prepare-admin-gateway.py)의 `app-recipient`, 봇에서 `bot-pki`, 다시 앱에서 `app-install`을 root로 실행한다. 도구의 S3 버킷과 주소는 학교 값이므로 계정 이전 시 바꾼다. 기존 디렉터리를 덮어쓰지 않는다. 공개 수신 인증서와 암호화된 묶음만 비공개 S3를 통과하며 개인키 원문은 출력하지 않는다.
2. Primary에서 [운영자 등록 SQL](../../deploy/linux/bot/register-bot-operator.sql)을 `sudo -u postgres psql -d exchange -f /tmp/register-bot-operator.sql`로 실행한다. `VIEWER`·`BOT_OPS` 두 역할만 등록한다.
3. 봇의 `/etc/exchange-bot/admin.env`에 [관리 설정](../../deploy/linux/bot/admin.env.school)을 배치하고 [systemd 추가 설정](../../deploy/linux/bot/admin-service-override.conf)을 `exchange-bot.service.d/admin.conf`에 둔다. 인증서 도구가 만든 `admin-secret.env`는 root 전용으로 유지한다. `systemctl daemon-reload`와 봇 재시작 후 8081 liveness, 사설 8443 listen과 메모리를 확인한다.
4. 앱에 `dnf install -y httpd-tools`를 실행한 뒤 [계정 생성 도구](../../deploy/linux/app/create-admin-login.py)를 `sudo python3`로 실행한다. 운영자가 12자 이상 비밀번호를 두 번 직접 입력한다. `/etc/nginx/admin.htpasswd`는 root:nginx 0640이며 bcrypt 해시만 저장한다. 기존 파일이 있으면 중단한다. 비밀번호를 명령 인자·채팅·증거에 넣지 않는다.
5. `pwsh -NoProfile -File deploy/linux/app/build-web.ps1`로 실제 HTTPS·WSS 주소와 관리자 비밀번호 모드를 명시해 빌드한다. 빈 환경 변수는 `.env.local`의 localhost 값을 되살릴 수 있으므로 사용하지 않는다. `web/out`만 기존 비공개 S3로 전송하고 서버에서 SHA-256을 대조한다. 환경 파일이나 서버 인증서는 웹 묶음에 포함하지 않는다.
6. 앱의 `/etc/nginx/exchange-admin.conf`와 `exchange-admin-upstream.conf`에 [로그인 경로](../../deploy/linux/app/nginx-admin.conf)와 [내부 연결](../../deploy/linux/app/nginx-admin-upstream.conf)을 배치한다. [HTTPS 설정](../../deploy/linux/app/nginx-https.conf)은 로그인 제한 구역을 선언하고 두 관제 경로를 포함한다. `nginx -t` 후 배포 링크를 교체하고 Nginx를 reload한다. 인터넷에 8443을 열지 않는다.
7. [비인증 검사](../../deploy/linux/app/verify-admin-public.py)를 실행하고 운영자가 웹에서 로그인한다. 기존 대수를 그대로 적용해도 실제 플릿 제어·감사 경로를 검사할 수 있다. 화면에 보이는 모의 데이터만으로 성공이라 판단하지 않는다.

웹 배포는 `/opt/exchange-web/releases/admin-20260920`에서 시작했고 저속 조절 보완본은 `admin-controls-20260920`으로 구분한다. 보완본 ZIP의 SHA-256은 `73716e05934507b4ed88e787cb2f8cdca303c540fb2889c2db008ed0df869e81`이다. 이전 배포는 `releases/20260919`, Nginx 백업은 `/etc/nginx/nginx.conf.before-admin-20260920`이다. 원복은 관리 경로가 차단된 백업 설정과 이전 웹 링크를 복구한 뒤 `nginx -t`·reload로 확인한다. 봇의 추가 설정을 별도 보관 경로로 옮기고 daemon-reload·재시작하면 이전 봇 전용 실행으로 돌아간다. 거래 DB를 초기화할 필요는 없다.

내부 인증서는 2026-11-19 00:48 KST에 만료되며 자동 갱신은 아직 없다. 공개 Let's Encrypt 인증서의 갱신과 구분한다. 만료 전에 서버·게이트웨이 인증서와 신뢰 저장소를 함께 교체·검증한다. 파일 경로나 인증서 키를 다른 계정에 그대로 복사하기보다 새 환경에 맞게 다시 발급한다.

일반 회원의 거래 화면, 봇 운영자 어드민, 인프라 Grafana는 서로 다른 용도다. 기존 [어드민 인증 필터](../../server/app/src/main/java/kr/exchange/admin/AdminAuthFilter.java)의 mTLS·세션 검사는 유지한다. 이전 [미리보기 Nginx](../../deploy/linux/app/nginx-preview.conf)는 관리 경로를 차단했던 초기 설정으로 보존하며 현재 공개 설정으로 사용하지 않는다.

어드민에는 저장 공간 상태·증가량·남은 예상 기간을 읽는 화면과 권한이 제한된 시험 초기화 작업을 나눠 두는 안을 권한다. 일반 회원이나 Viewer에게 삭제 기능을 주지 않는다. 운영 API에 DB 소유자·슈퍼유저 권한을 붙이지 않고 별도 유지보수 실행기가 작업 ID와 진행·실패 상태를 기록하도록 설계한다. 버튼에는 대상 환경과 삭제 범위를 표시하고, 데이터 보존·백업 확인을 거쳐 명시적으로 실행하도록 한다. 자동 임계 삭제는 넣지 않는다.

초기화는 봇 중지와 쓰기 차단, 진행 중 거래 정리, 증거·백업 보관과 복원 확인, 시험 데이터 전체의 새 기준 상태 구성, Standby 재편입, 원장·복구·거래 검증 순서가 필요하다. 중간 실패 때 서비스를 다시 열지 않고 재개 가능한 상태를 남긴다. 기존 원장·저널의 삭제 방지 트리거를 끄거나 운영 계정에 TRUNCATE 권한을 주는 방식은 쓰지 않는다. 현재 [로컬 초기화 코드](../../harness/scale/clean_suite.py)는 격리 Docker 볼륨을 다시 만드는 실험용이며 AWS 두 DB에 그대로 실행할 수 없다. 실제 초기화 기능은 아직 구현하지 않았다.

하루 1~2GB 증가는 사용자 추정이다. 기존 로컬 기록에는 하루 후 DB 2.36GB와 조건이 다른 4.4GB/일 추정이 있고, 24분 측정의 WAL 생성량을 하루로 환산한 32.95GB도 있다. WAL 생성량은 디스크에 계속 쌓이는 순증가량과 다르다. AWS 봇의 첫 저속 체결만 확인했으므로 이 수치를 AWS 하루 증가량이나 복제 트래픽으로 확정하지 않는다. 봇의 낮은 기본 속도를 먼저 정하고 테이블·인덱스·WAL 보유량·로그를 따로 측정해 보존·가동 속도를 정한다. [로컬 WAL 측정과 한계](../../harness/evidence/wal-rate-20260913/README.md)

## 작업을 기록하는 방식

AWS 조작과 Linux 명령은 한 단계씩 실행하고 실제 화면을 [날짜별 증거 폴더](../../증거본/2026-09-19_학교AWS/)에 저장한다. 전체 명령과 화면 출력은 그 폴더의 `Linux_명령실행.log`, 캡처 시각은 `작업시간.log`에 있다. 이후 번호는 기존 파일 다음에서 시작한다. 종료 코드 출력 문구를 명령 뒤에 붙이지 않으며, 코드가 수집되지 않으면 미수집으로 기록하고 실제 출력과 상태를 확인한다.

일반 설치·조회·문서 작업은 Medium으로 진행한다. DB 격리·승격·재편입, 주문 정합성 수정, ASG 경로 분리처럼 여러 실패 조건을 함께 판단해야 할 때는 사용자에게 이유를 설명하고 High 이상을 추천한다. 모델 설정을 임의로 변경하지 않는다.


## 2026-09-20 백업·복원과 보고서 연결

화면에 ‘연결 끊김’이 표시됐지만 봇 프로세스와 실제 거래는 계속 실행 중이었다. 직전 웹 빌드에서 `.env.local`의 `localhost:8080`이 번들에 들어간 것이 원인이었다. PowerShell에서 빈 환경 변수 설정이 삭제로 처리될 수 있어 [배포 빌드 도구](../../deploy/linux/app/build-web.ps1)는 HTTPS·WSS 주소를 명시한다. 빌드 뒤 localhost 잔존 검사도 통과해야 한다. 현재 배포 경로는 `/opt/exchange-web/releases/origin-20260920`이며 이전 `admin-controls-20260920`도 보존했다. ZIP SHA-256은 `5df8abe14f8a488622aa647baaea75b48fa979926dfbc5cfc2bdd8720bd66282`다.

백업은 [백업·격리 복원 도구](../../deploy/linux/postgresql/backup-restore-check.py)와 `harness/sql/summary.sql`을 Primary의 `/tmp`에 전송한 뒤 실행한다.

```bash
sudo -u postgres python3 /tmp/backup-restore-check.py --invariants /tmp/summary.sql
```

이 명령은 같은 트랜잭션 스냅샷으로 백업과 주요 테이블 건수를 얻는다. 이어 `/var/lib/pgsql/backup-checks/<백업 ID>/restore-data`에 새로운 시험 클러스터를 만들고 Unix socket으로만 접속해 복원·정합성을 검사한다. 시험 서버는 종료하지만 백업 파일·시험 데이터·판정 JSON은 삭제하지 않는다. 반복 실행하면 디스크를 사용하므로 결과를 확인하며 실행해야 한다. 운영 DB나 이전 백업을 초기화하는 명령이 아니다.

첫 검증 ID는 `20260919T162452010003Z`, 백업 크기는 4,559,507바이트, SHA-256은 `7e0d023a134a28d3bfb897df2ba65a81a5419cd199730141c25d37c4124f971d`다. 원본·복원 모두 명령 12,733·이벤트 20,746·체결 6,710·원장 항목 68,686건이었다. 정합성 30개 중 필수 검사 실패는 없었다. 역할·ACL, 앱 부팅, 외부 백업 보관, 예약 백업은 이번 검사의 범위가 아니다. `result.json`의 확인 범위와 한계를 함께 읽는다.

Teams 보고는 [공통 보고 모듈](../../deploy/reporting/README.md)을 사용한다. [학교 설치 도구](../../deploy/linux/reporting/install-school.py)는 전용 Python 3.11 환경, ReportLab 4.4.9, Nanum Gothic, 보고 서비스와 Alertmanager 0.31.1을 설치한다. 배포 ZIP에는 코드·공개 글꼴만 넣고, 기존 봇 서버의 비밀 파일을 서버 안에서 읽는다. 저장소의 생성 스크립트와 현재 배포 ZIP은 일치 여부를 확인해야 하며, 이번 최초 묶음에는 빠진 `console_api.py`를 후속 설치했다. 수정한 공통 설치기는 이 파일을 포함한다.

보고 서비스는 사설 `172.31.44.141:9088`, Alertmanager는 `127.0.0.1:9093`이다. [내부 경로 연결 도구](../../deploy/linux/reporting/connect-school-reporting.py)는 설정을 백업하고 검사한 뒤 Prometheus·Nginx에 반영한다. 봇 SG 9088 규칙 `sgr-0e7d1928623e2ad5d`의 소스는 앱 SG 하나다. [Nginx 보고 경로](../../deploy/linux/reporting/nginx-reports.conf)는 `/operations/reports/`의 GET만 허용하고 내부 관리·수집 API를 외부에 연결하지 않는다. 보고서 URL 서명을 접근 로그에 남기지 않는다. 링크는 7일 후 만료되지만 저장 파일의 자동 삭제는 아직 없다.

기존 `exchange-report-daily.timer`·`exchange-report-incident.timer`는 중복 발송을 막기 위해 껐다. 새 `failstop-reporting-daily.timer`는 한국 시간 오전 9시에 동작하며 경보 보고는 Alertmanager 이벤트로 발생·해제를 구분한다. AI는 설명만 작성하고 서버 제어나 DB 승격을 실행하지 않는다. 수집되지 않은 근거는 미확인으로 남긴다. 예약 백업은 아직 없으므로 보고서에서도 완료로 표시하지 않는다.

실제 AWS 지표를 담은 `school-live-check-20260920` 보고의 웹훅 접수, PDF 111,333바이트·HTML 16,445바이트의 HTTPS 200과 저장 내용 일치를 확인했다. 사용자가 Teams 카드와 두 보고서가 모두 열린다고 확인했다. 장애 시험·DB 승격의 진행 상태는 로드맵과 [실행 증거](../../증거본/2026-09-20_백업_복구_보고/README.md)를 따른다.


## DB 수동 전환을 재현할 때

이번에는 AZ-a Primary의 데이터를 지우지 않고 AZ-c Standby를 새 Primary로 바꿨다. 앱을 멈춘 동안 정적 화면은 열려도 거래 API·실시간 연결은 일시 중단된다. 봇도 전환 동안만 멈췄으며 이후 BTC·ETH·XRP 15개를 전체 3 OPS로 다시 켰다. 소스의 배포 설정과 관측 역할도 새 주소로 맞췄다.

| 확인 시점(KST, 2026-09-20) | 실제 확인 |
|---|---|
| 01:53:32 | 봇 중단 시작 |
| 01:53:48 | 앱 중단 시작 |
| 01:54:32 | 명령 17,610·이벤트 28,851·체결 9,337·원장 94,918건, 복제 위치 일치 |
| 01:54:48 | 기존 Primary 중지·mask, 5432 listen 없음 |
| 01:54:59 | 앱에서 옛 DB 접속 거부 확인 |
| 01:55:17 | Standby 승격, 네 테이블 건수 일치 |
| 01:55:48 | 새 DB에 연결한 앱 health UP |
| 01:56:05 | 봇 재기동 |
| 01:57:40 | 새 Primary 체결 9,472건, 새 봇 상태 running·3 OPS |
| 01:59:56 | 같은 스냅샷의 정합성 30항목 검사, 필수 실패 0건 |
| 02:03:29 | 옛 Primary가 Standby로 streaming, WAL 송신·재생 위치 일치 |

격리부터 거래 증가를 다시 확인하기까지 2분 52초, 앱 중단부터는 3분 52초다. 브라우저 명령 실행과 사람이 확인한 간격이 들어간 관측값이며 정확한 최초 성공 거래 시각을 계측한 값은 아니다. 쓰기를 멈추고 복제를 따라잡은 이번 표본에서 네 테이블 건수 차이는 0건이었다. 갑작스러운 전원·네트워크 장애 때도 유실이 없다는 뜻은 아니다.

순서는 다음과 같다. [준비·재편입 도구](../../deploy/linux/postgresql/prepare-db-recovery.py)와 [앱·봇 전환 도구](../../deploy/linux/postgresql/switch-db-endpoint.py)는 이번 호스트와 날짜를 고정한 실행 자료다. 개인 계정에서는 주소·호스트·버킷·TLS SAN·SG를 먼저 맞춘 뒤 새 기록 경로를 사용한다. 현재 환경에 처음부터 재실행하지 않는다.

1. 별도 시험 클러스터에서 백업 복원과 정합성을 먼저 확인한다. 운영 데이터 디렉터리를 복원 대상으로 삼지 않는다.
2. `prepare-old`로 암호화 수신용 공개 인증서를 만들고, 그 SHA-256을 대조해 Standby에서 `prepare-new`를 실행한다. 기존 복제 비밀번호만 암호화해 비공개 S3로 전달한다. 비밀 값은 화면·명령 인수·증거에 출력하지 않는다.
3. 앱과 봇에서 `switch-db-endpoint.py <app|bot> prepare --sha256 <새 DB 인증서 지문>`을 실행한다. 아직 서비스를 중단하거나 접속 주소를 바꾸지 않는다.
4. 봇, 앱 순서로 중지하고 네 테이블 건수·WAL 위치를 기록한다. 옛 Primary에서 `systemctl stop postgresql`과 `systemctl mask postgresql`을 실행한다. 프로세스·5432 listen·앱에서의 접속 거부까지 확인하기 전에는 승격하지 않는다.
5. Standby가 기준 WAL 위치까지 재생했는지 확인하고 `SELECT pg_promote(true,60)`을 실행한다. `pg_is_in_recovery()`가 false인지, 기준 데이터가 남았는지 확인한다.
6. 앱·봇에서 `switch-db-endpoint.py <app|bot> apply --sha256 <검증한 지문>`으로 기존 설정·인증서를 백업하고 새 DB로 바꾼다. 앱부터 시작해 health UP을 확인한 뒤 봇을 시작한다. 거래 건수가 실제로 증가하고 화면이 연결되는지 확인한다.
7. 옛 Primary에서 `prepare-db-recovery.py rejoin-old --server-sha256 <검증한 지문>`으로 새 베이스백업을 받고 `pg_verifybackup`을 통과시킨다. 옛 PGDATA는 `/var/lib/pgsql/data-before-recovery-20260920`에 보존했다. 기존 DB를 지우거나 강제로 다시 Primary로 시작하지 않는다.
8. 양쪽에서 streaming·WAL 위치를 확인한다. 관측 주소와 역할은 [마무리 도구](../../deploy/linux/postgresql/finish-db-recovery.py)로 갱신했고 promtool을 통과했다. [정합성 검사](../../deploy/linux/postgresql/verify-db-recovery.py)는 같은 읽기 스냅샷으로 실행한다.

재편입 중 두 가지를 보완했다. `runuser --preserve-environment`가 root의 홈 경로를 유지해 인증서 탐색을 막았으므로 제거했다. 이어서 `PGPASSWORD`만 전달한 베이스백업은 재기동할 복제 연결의 비밀번호를 보존하지 못했고, 복사한 설정에는 새 Primary에 없는 `standby_1` 슬롯 참조도 남았다. 기존 비밀번호를 postgres 소유 0600 passfile에 저장하고 해당 경로를 연결 문자열에 지정했으며 슬롯 참조를 비웠다. 현재 도구에는 이 두 수정이 들어 있다. 서버에는 [보완 도구](../../deploy/linux/postgresql/fix-replication-passfile.py)와 SQL reload로 적용했고 streaming까지 확인했다.

Java 프로세스의 SIGTERM 종료 코드 143이 systemd에서 실패로 잡히는 문제도 발견했다. 앱·봇에 `SuccessExitStatus=143`을 적용했다. 정상 종료만 성공으로 분류하며 OOM·다른 오류를 정상으로 바꾸지 않는다. 이 설정 적용을 위해 서비스 자체를 다시 중단하지 않았다.

## 경보 발생과 해제를 확인한 범위

Standby를 3분간 중지하고 자동 재기동을 예약해 DB 접속 실패·복제 연결 끊김 경보를 확인했다. 수동 DB 전환 중 앱 지표 수집 중단도 감지했다. 별도 128MiB loop 파일시스템을 98% 사용 상태로 만들어 여유 15% 미만 5분 경보를 확인한 뒤, 7분 자동 공간 반환과 경보 해제를 확인했다. 운영 루트·DB 볼륨은 채우지 않았다. 시험 이미지는 남기고 마운트만 해제했다.

발생·해제 모두 보고 저장소에서 `sent`, PDF·HTML 생성은 `ready`, 분석 모드는 `gemini`로 확인했다. 사용자는 연결 검증 카드와 두 파일을 실제 Teams에서 열었다. 개별 장애 카드 전체를 사람이 하나씩 열람했다는 뜻은 아니다. 최종 시각별 결과는 증거본의 025·039·050 이후를 따른다. 외부 사이트 프로브는 아직 없어 앱 프로세스 건강·지표 응답과 인터넷에서의 실제 접속 성공을 구분해야 한다.

기존 bot-1 전용 2분 timer는 껐다. 현재 Prometheus는 15초마다 평가하며 DB 접속·앱 지표는 1분, 복제는 2분, 디스크는 5분 지속 조건을 사용한다. Alertmanager의 묶음·재전송 간격과 AI 처리 시간이 더해져 카드 도착은 즉시가 아니다. 일일 보고는 오전 9시 KST이며 이번 확인은 시험 전송이다. 아직 다음 예약 시각의 실제 실행 결과는 없다.

남은 운영 작업은 백업의 서버 외부 보관·자동 예약, 인증서 만료 대응, 실제 회원 주문과 장시간 증가량·메모리·CPU 크레딧 측정이다. 로컬 디스크의 백업과 보존한 옛 PGDATA는 볼륨 자체가 사라지는 사고를 막아 주지 않는다. 이번 시연에서는 실제 거래 데이터나 옛 데이터 디렉터리를 삭제하지 않았다.

관측 역할 변경 뒤 과거 시계열이 남아 용량 보호기가 봇을 안전 중지했다. 90초 이내 파일시스템 표본만 선택하도록 보완했고, 첫 수정에서 발생한 PromQL 422 오류도 고쳤다. 네 호스트 용량·inode와 실패 누적 0을 확인한 뒤 봇을 수동 재개했다. 용량 임계값이나 수집 누락 시 중지 기준은 낮추지 않았다. 이 중단은 위 DB 전환 시간에 포함되지 않는다.
