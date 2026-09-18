# Linux·DB 관측 구성

Prometheus·Alertmanager·Grafana는 [관측 Compose](../compose.node-observability.yml)로 실행한다. Linux는 호스트의 node_exporter와 1분 간격 진단 수집기로, DB는 각 DB 호스트의 전용 postgres_exporter로 읽는다. 대상 파일을 채우지 않은 상태는 미구성 상태이며 Linux·앱·DB·보고·외부 접속 대상 누락 알림을 낸다. 그래프가 비었다는 이유로 정상이라고 판단하지 않는다.

고객 관측 범위는 Spring 서비스와 이를 운영하는 고객 서버·DB·접속 경로다. Docker 전체 컨테이너를 고객 자원으로 집계하지 않는다. MSP 화면은 고객사와 환경으로 범위를 고르고 `app|reader|data|standby|probe` 역할만 포함한다. Spring 자원은 Actuator의 프로세스·JVM 지표, 서버 자원은 node_exporter를 사용한다. DB 서버 CPU·메모리는 `role=data|standby`인 전용 호스트에서만 읽으며 로컬 Ubuntu 값을 Docker DB 사용량으로 바꾸어 표시하지 않는다. Prometheus와 보고 서비스 자체의 상태 수집은 관측 경로 점검용으로 유지하지만 고객 서비스 수집 대상 수에는 넣지 않는다.

`local-lab`의 고객 대상은 Docker에서 실행하는 Spring·DB·접속 검사다. 별도 Ubuntu는 `customer=operations-lab`인 Linux 운영 실습 환경으로 두며 고객 서버 지표와 보고에서 제외한다. AWS에서는 지정한 고객 프로젝트의 EC2를 등록한다. ALB·ASG·EBS의 CloudWatch 데이터 소스와 조회 패널은 준비했지만 AWS 계정이 없어 실제 연결은 아직 검증하지 않았다. Grafana는 Windows bind mount의 파일 변경도 읽도록 30초 주기로 대시보드 파일을 확인한다. [Grafana 파일 갱신 안내](https://grafana.com/docs/grafana/latest/administration/provisioning/#dashboards)

## 호스트와 DB 수집

Linux 호스트에서 [설치 스크립트](install-node-exporter.sh)의 기본 계획을 확인하고 설치한다. 바인딩 주소와 방화벽 출발지는 실제 관측 경로에 맞춘다. 기본 루프백 9100은 원격 Prometheus에서 접근할 수 없다.

```bash
sudo bash deploy/observability/install-node-exporter.sh --apply --listen 127.0.0.1:9100
```

보고 계정을 이미 만든 뒤에는 설치와 재설치 명령에 `--evidence-group failstop-report`를 반드시 넣는다. 기본 그룹은 root다. 보고 서비스 설치기는 수집 서비스 drop-in에도 전용 그룹을 지정하지만, node exporter를 root 기본값으로 재설치하면 근거 디렉터리 그룹이 바뀔 수 있다. 수집기를 두 번 실행한 뒤 보고 계정에서 최신 파일을 읽는지 확인한다. 수집기는 `/var/lib/failstop/evidence/host-latest.json`을 0640으로 교체하면서 지정 그룹을 보존하고 `/var/lib/failstop/metrics/failstop-host.prom`에는 수집 시각·성공 여부를 기록한다. sysstat의 `iostat` 등 필요한 명령이 없거나 권한이 부족하면 FAIL을 그대로 기록한다. 로그 사용량만 읽으며 앱 로그 본문을 수집하지 않는다.

WSL 실습에서는 `--profile wsl`을 명시한다. WSL에 없는 RAID·NFS·ZFS·InfiniBand 등 하드웨어·커널 하위 시스템을 수집 대상으로 주장하지 않고 CPU·메모리·디스크·파일시스템·네트워크·systemd·textfile을 수집한다. 일반 Linux 배포의 기본 `linux` 프로파일과 구분한다. 모든 프로파일에서 `/run/user` 등 임시·가상 마운트를 제외해 중복된 사용자 tmpfs가 동일한 Prometheus 시계열을 만들지 않게 한다. 실제 저장소인 `/mnt/c` 부족은 제외하지 않는다.

[DB exporter Compose](compose.postgres-exporter.yml)는 Linux DB 호스트에서 실행한다. `network_mode:host`이고 기본 DB 주소와 exporter 주소는 루프백이다. primary·standby 각각 한 exporter를 실행하며 standby의 DB 포트가 5433이면 `PG_EXPORTER_URI`를 그 포트로 지정한다. 다른 호스트에서 DB에 연결할 경우 TLS가 확인된 DSN과 인증서를 따로 구성한다.

모니터 계정은 관리자·앱 계정과 분리된 `failstop_monitor`다. primary에 [역할 스크립트](provision-monitor.sh)를 실행하면 로그인·pg_monitor·읽기 기본값·쿼리 제한을 설정한다. 이 역할은 WAL 디렉터리 크기와 통계만 읽으며 업무 테이블의 권한을 부여하지 않는다. 역할은 PostgreSQL 복제로 standby에도 전달된다. 실제 운영 권한은 DDL과 업무 테이블 쓰기·읽기 거부 시험으로 확인한다.

`DB_ADMIN_USER`, `DB_ADMIN_PASSWORD`, `DB_HOST`, `DB_PORT`, `DB_NAME`, `PG_MONITOR_PASSWORD_FILE`을 비공개 실행 환경에 설정하고 아래를 실행한다. 비밀번호를 명령 인수나 README에 적지 않는다. exporter 비밀번호 파일은 root:65534 0640으로 두고 상위 디렉터리 접근 권한도 맞춘다. exporter 이미지의 uid/gid가 65534라는 점은 [공식 v0.17.1 안내](https://github.com/prometheus-community/postgres_exporter/tree/v0.17.1#quick-start)를 따른다.

```bash
sh deploy/observability/provision-monitor.sh
docker compose -f deploy/observability/compose.postgres-exporter.yml up -d
```

전용 비밀 파일은 exporter에 읽기 전용으로 마운트한다. DB 연결 비밀번호를 Compose 환경 변수로 전달하지 않는다. postgres_exporter v0.17.1의 custom query 기능을 사용하며 이 기능은 upstream에서 deprecated 상태이므로 버전 갱신 때 대체 수집기와 지표 호환성을 확인한다. [확장 질의](../../harness/prometheus/pg-queries.yaml)는 WAL 실제 파일 크기, 복제 전송·재생 위치 차이와 마지막 재생 나이를 읽는다. 마지막 재생 나이에는 유휴 시간이 포함되므로 RPO나 실제 유실량으로 단정하지 않는다.

## 수집 대상을 등록한다

[inventory 예제](inventory.example.json)를 실행 환경 전용 파일에 복사하고 실제 주소로 바꾼다. hosts·apps·databases·reporters·probes 다섯 그룹을 모두 명시한다. 각 주소에 customer·service·role·environment 라벨을 넣어 Grafana의 고객사·환경 선택과 고객 역할 범위에 연결한다. apps의 관리 포트는 prod 기준 8081이며 metrics 경로는 `/actuator/prometheus`다.

```powershell
python deploy/observability/configure-targets.py --inventory artifacts/operations-20260918/inventory.json --output-dir artifacts/operations-20260918/prometheus-targets
python deploy/observability/configure-targets.py --inventory artifacts/operations-20260918/inventory.json --output-dir artifacts/operations-20260918/prometheus-targets --write
```

첫 명령은 검증과 개수만 출력하고 두 번째가 각 JSON을 원자적으로 교체한다. 빈 그룹은 기본 거부하며 의도적으로 아직 없는 역할은 `--allow-empty`로 표시한다. URL 자격증명·query·fragment, 메타데이터 등 link-local 주소, unspecified·multicast 주소와 중복 대상을 거부한다. 출력 디렉터리를 Compose `OBS_TARGETS_DIR`로 지정한다. Prometheus는 30초마다 file discovery를 갱신한다. 이 도구는 AWS ASG 인스턴스를 자동 발견하지 않으며 신규 조회 노드 자동 등록은 AWS 수집 경로에서 별도로 연결한다.

reporter의 기본 계약은 9088 `/metrics`, `/alerts`이고 양쪽 모두 Bearer 토큰을 사용한다. `REPORTER_TOKEN_FILE`은 Prometheus와 Alertmanager 양쪽에 같은 파일로 마운트한다. alertmanager.yml의 후속 보고 주소는 `host.docker.internal:9088`; 두 컨테이너 모두 Linux host-gateway 매핑이 있다. 실제 reporter가 WSL이나 다른 Linux 호스트에 있으면 그 사설 주소를 사용하고 방화벽에서 관측 출발지만 허용한다. 파일 대상 reporters 주소를 바꿨다면 Alertmanager webhook 주소도 같은 접속 경로로 맞춘다.

기본 Teams 알림은 Alertmanager가 직접 보내고 후속 보고는 [운영 보고 서비스](../reporting/README.md)가 처리한다. AI나 후속 보고가 실패해도 기본 알림 경로는 유지된다. `TEAMS_WEBHOOK_FILE`과 `GRAFANA_PASSWORD_FILE`도 비밀 파일이며 Git에 넣지 않는다. `OBS_BIND_IP` 기본 루프백과 공개하지 않는 관리 접근을 유지한다.

## Docker Desktop·WSL 실습 연결

Windows와 Docker Desktop에서 WSL 사설 주소로 직접 접근하지 못할 때는 [로컬 bridge](windows-wsl-bridge.py)를 Windows의 127.0.0.1:59188에 실행한다. Docker의 `host.docker.internal:59188` 요청을 `wsl.exe`로 전달하며 `/node/metrics`, `/reporter/health`, `/reporter/metrics`, `/reporter/alerts`만 허용한다. 일반 프록시가 아니며 원격 주소·임의 경로·리다이렉트를 받지 않는다. 보고 인증은 그대로 유지하고 인증 헤더는 프로세스 표준입력으로만 전달한다. Windows 재시작 후에는 이 개발용 프로세스를 다시 시작해야 한다. Linux 운영 배포에 bridge를 설치하지 않는다.

[로컬 준비 도구](prepare-local.py)는 이미 구성한 WSL 보고 비밀과 로컬 DB 실습을 연결하고 `.state/`에 비공개 Compose·대상 파일을 만든다. 최초 준비는 모든 Teams 알림을 quiet로 둔다. DB 실습의 승격된 standby가 현재 primary라는 구성을 전제로 하므로 다른 배포에서 실행하지 않는다. 실제 전송을 허용한 뒤 [운영 경로 활성화 도구](activate-local-alerts.py)를 `--apply`로 실행한다. 이 도구는 Ubuntu 운영 실습과 `environment=local-lab`인 `TradeSilence`를 quiet로 둔다. 실습용 C드라이브 경고나 거래가 없는 유휴 상태를 고객 서버 사건으로 전송하지 않는다. 고객 Spring·DB와 실제 AWS 서버 경보의 기본 알림·후속 보고 경로는 유지하며 일반 배포의 원본 경보 규칙은 바꾸지 않는다.

Prometheus 59090·Alertmanager 59093·Grafana 53001의 Windows 게시 주소는 모두 127.0.0.1이다. 실제 서버 간 방화벽·인증 검증과 로컬 bridge 검증을 구분한다. 로컬 sink 통합 검사는 외부 전송 없이 5종 카드와 중복 방지를 확인한다. 실제 Teams 시험은 별도의 `--send`가 필요하며 이미 수락된 동일 사건을 이어 확인할 때는 `--resume`과 같은 증거 파일을 사용한다.

## 로컬과 AWS의 관측 경로를 맞춘다

[환경 설정 도구](configure-environment.py)는 공통 경보·보고 원본에서 Prometheus, Alertmanager, 보고 JSON을 함께 만든다. 파일 생성만 수행하며 서비스 적용이나 Teams 전송은 하지 않는다. 로컬에는 Windows·WSL bridge 경로와 실습 알림 제외 조건을 넣고, AWS에는 Linux 사설 주소로 직접 연결한다. 보고의 모든 지표와 Alertmanager 조회에는 고객사와 환경 조건을 넣는다. 알림 묶음에도 고객사·환경을 유지해 두 환경의 사건을 합치지 않는다.

```powershell
python deploy/observability/configure-environment.py --environment local-lab --reporter-url http://host.docker.internal:59188 --output-dir deploy/observability/.state/environments/local-lab --write
```

AWS 관측 노드에서는 아래처럼 준비한다. `10.42.1.10`은 설명용 주소이며 실제 관측 노드의 사설 IP로 바꾼다. 이 주소는 관측 컨테이너가 접근할 reporter 주소이고, 59090·59093은 같은 Linux 호스트에서 실행하는 보고 서비스가 접근하는 Compose 게시 포트다.

```bash
python3 deploy/observability/configure-environment.py --environment aws --reporter-url http://10.42.1.10:9088 --output-dir /etc/failstop/observability --write
```

1. [AWS 탐색 서비스](../terraform/README.md)의 지정 `FailstopCluster`만 등록한다. Spring 앱·조회 노드는 8081, 고객 호스트는 9100, DB exporter는 9187이다. 봇의 앱 지표는 고객 Spring 대상에서 제외한다. 관측 노드 자체의 호스트·reporter 수집은 경로 점검용으로 유지한다.
2. [보고 설치](../reporting/README.md)에 `--environment aws --bind <관측 노드 사설 IP>`를 지정한다. Teams·인증 토큰·Grafana 비밀은 AWS 호스트의 보호 파일로 전달한다. 보고의 localhost 59090·59093 접속, reporter 9088의 관측 출발지 방화벽과 Bearer 인증을 확인한다.
3. 관측 Compose 환경의 `PROMETHEUS_CONFIG`와 `ALERTMANAGER_CONFIG`를 생성한 파일로, `OBS_TARGETS_DIR`를 AWS 탐색 서비스의 출력 디렉터리로 지정한다. 로컬 `.state/compose.local.yml`과 Windows bridge는 AWS에서 사용하지 않는다. Prometheus file discovery 형식과 Grafana 4개 화면·경보·보고 양식은 공통으로 유지한다.
4. `promtool check config`와 `amtool check-config`를 통과한 뒤 관측 Compose를 기동한다. Grafana에서 `environment=aws`를 선택하고 실제 scrape, 기본 알림, 장애 보고, 복구 보고를 확인한다. AWS 계정이 없을 때 만든 설정·모의 탐색 결과는 이 실행 검증을 대신하지 않는다.

EC2의 Spring·Linux·PostgreSQL 지표와 ALB·ASG·EBS의 CloudWatch 지표는 수집 경로가 다르다. 이 설정 전환만으로 모든 AWS 관리형 지표가 들어오지는 않는다. CloudWatch 연결과 ASG 증감 중 대상 갱신은 AWS 계정에서 검증할 작업으로 남긴다.

## AWS 관리형 지표를 연결한다

AWS 관측 노드에서는 기본 관측 Compose에 [AWS 전용 파일](../compose.observability-aws.yml)을 추가한다. 이 파일은 Grafana에 UID `cloudwatch`인 [CloudWatch 데이터 소스](grafana/cloudwatch.aws.yml)를 등록하며, 관측 EC2의 instance profile로 인증한다. 액세스 키를 대시보드나 환경 변수에 넣지 않는다. 실제 배포 리전은 `AWS_REGION`으로 지정하며 기본값은 서울이다. 로컬 Compose에는 이 파일을 추가하지 않는다.

```bash
docker compose --env-file /etc/failstop/observability/compose.env -f deploy/compose.node-observability.yml -f deploy/compose.observability-aws.yml config --quiet
docker compose --env-file /etc/failstop/observability/compose.env -f deploy/compose.node-observability.yml -f deploy/compose.observability-aws.yml up -d
```

위 환경 파일에는 앞 절에서 준비한 설정·수집 대상·보호 파일 경로가 있어야 한다. 관측 노드의 기존 IAM 역할에 필요한 조회 권한을 검토해 추가한다. [읽기 정책 예제](grafana/cloudwatch-read-policy.json)는 서울 리전의 CloudWatch 지표와 리소스 검색을 허용하며 실제 권한 부여 결과가 아니다. `Resource: "*"`인 읽기 API의 권한 범위를 Grafana 고객사 변수만으로 격리할 수는 없다. 계정별 역할과 리전 조건을 실제 고객 범위에 맞춰야 한다. 컨테이너에서 instance profile을 사용할 수 있도록 Terraform은 관측 bot 노드만 IMDSv2 응답 hop limit 2로 두며 다른 앱·DB·조회 노드는 1을 유지한다. [Grafana CloudWatch 인증·설정](https://grafana.com/docs/plugins/cloudwatch/latest/configure/)

인프라 화면의 `CloudWatch` 행은 기본 접힘 상태다. 로컬에서는 AWS 지표를 조회하지 않으며 `AWS 미연결` 상태를 정상 수치로 대체하지 않는다. AWS 배포 후 아래 실제 값을 Grafana URL 변수로 지정하고 이 행을 펼친다. 값은 전체 ARN이나 DNS 이름이 아니라 CloudWatch dimension에 맞는 식별자여야 한다.

| Grafana URL 변수 | 실제 값의 출처 |
|---|---|
| `var-environment` | `aws` |
| `var-aws_region` | Terraform `monitoring_dimensions.region` |
| `var-aws_alb` | `monitoring_dimensions.load_balancer`, ALB ARN suffix |
| `var-aws_asg` | `monitoring_dimensions.reader_asg`, 조회 ASG 이름 |
| `var-aws_reader_target_group` | `monitoring_dimensions.reader_target_group`, 대상 그룹 ARN suffix |
| `var-aws_ebs_volume` | 점검할 고객 서버의 실제 EBS volume ID |

```bash
terraform -chdir=deploy/terraform output -json monitoring_dimensions
```

Grafana 주소의 `/d/msp-infra` 뒤에 `?var-environment=aws&var-aws_region=ap-northeast-2&var-aws_alb=<URL 인코딩한 ARN suffix>` 형식으로 연결하고 나머지 변수도 같은 방식으로 전달한다. `reader_asg=null`이면 조회 ASG가 활성화되지 않은 상태이므로 임의의 이름을 넣지 않는다. EBS는 해당 서버와 볼륨의 실제 연결을 확인한 후 지정한다. 화면의 `UNCONFIGURED`는 배포 준비 값이며 실재 자원이 아니다.

연결 검증에서는 실제 ALB 대상 그룹 상태, ASG의 원하는 수·준비 완료 수·대기 수와 Grafana 값을 대조한다. 이 단계의 성공은 CloudWatch 조회 경로의 증거이며 자동 확장이나 DB 복구 실험의 완료를 대신하지 않는다.

## 실제 지표로 보고 연결을 검사한다

[등록된 standby 검사](tests/check-monitored-standby.py)는 현재 로컬 실습의 `failstop-ops-lab-rejoined-1`만 대상으로 한다. Compose 프로젝트·서비스 라벨과 `pg_is_in_recovery=true`, 정상 scrape·전송 대기열을 확인한 뒤 replica를 멈춘다. Prometheus의 실제 `pg_up=0`, 경보 발화, 같은 사건의 보고 HTTP 접수를 기다리고 replica를 다시 켠다. primary는 정지하거나 승격하지 않는다. 실패해도 중단 단계의 `finally`에서 재시작을 요청하며, 강제 프로세스 종료나 Docker 엔진 장애까지 복구를 보장하지 않는다.

```powershell
python deploy/observability/tests/check-monitored-standby.py --output artifacts/operations-20260918/prometheus-standby-delivery.json
python deploy/observability/tests/check-monitored-standby.py --run --output artifacts/operations-20260918/prometheus-standby-delivery.json
```

첫 명령은 계획만 출력한다. `--run`은 이미 활성화한 Teams 경로로 실제 로컬 장애·해제 알림과 보고를 보낸다. 경보 임계나 대기 시간을 단축하지 않으며 `group_interval=5m` 때문에 복구 보고까지 몇 분 걸릴 수 있다. 복제 streaming·`pg_up=1`·Spring readiness, 발화·해제 보고 각각 한 건과 고객/환경을 대조한다. [전송 조회 도구](../reporting/delivery-audit.py)는 SQLite를 읽기 전용으로 열어 사건 ID·상태·시도 수만 확인하며 비밀과 원문 payload는 출력하지 않는다. HTTP 접수와 사용자가 Teams 채널에서 실제로 읽은 상태는 구분한다.

## 실패를 구분해서 본다

Linux 패널은 디스크·inode·I/O·네트워크·systemd 실패 상태·수집 시각·수집기 성공 여부를 보여준다. DB는 exporter 상태, WAL replay 위치 차이와 재생 나이를 함께 본다. 복제 지연 stat은 현재 instant query를 사용해 수집이 끊긴 뒤 과거 정상값을 계속 표시하지 않는다. MSP 화면의 과거 `chaos-*` 컨테이너 조회는 Spring·고객 호스트 지표로 교체했다. cstats 수집기는 기존 로컬 하네스 검증에 남기며 고객 관측 스택에는 추가하지 않는다.

cstats는 Docker API HTTP 오류·일부 컨테이너 수집 실패 시 수집 성공=0과 마지막 성공 시각만 게시한다. 이전 자원값을 재사용하지 않는다. 네트워크 통계 필드가 없으면 수신·송신 0을 만들지 않고 해당 표본을 생략한다. 실제 0은 그대로 보존한다.

경보는 대상 없음, 호스트·DB 연결 실패, custom DB query 누락, Linux 수집 실패, 디스크·inode 부족, systemd 실패, 보고 대기·전송 실패·일일 누락과 백업 상태 미확인을 구분한다. 시간·용량 임계는 시작값이며 실제 운영 부하와 보존 정책에 맞춰 검토한다. 백업 성공 지표는 복원 검증을 대신하지 않는다.

## 검증

```powershell
python -m unittest discover -s deploy/observability/tests -p "test_*.py" -v
docker run --rm --mount type=bind,source=<repo>/deploy/observability,target=/workspace,readonly --workdir /workspace/tests --entrypoint /bin/promtool prom/prometheus:v3.13.0 test rules linux-operations.test.yml
python deploy/observability/tests/check-postgres-exporter.py
```

마지막 검사는 임의 이름의 격리 DB·exporter·네트워크를 만들고 정리한다. 기존 DB를 사용하지 않는다. pg_monitor 역할을 두 번 적용하고 WAL 실측, 업무 테이블 SELECT·쓰기·DDL 거부, pg_up=1과 확장 지표 존재를 확인한다. 운영 DB나 AWS에서 같은 검증을 끝냈다는 뜻은 아니다.
