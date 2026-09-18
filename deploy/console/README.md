# MSP 관제 콘솔 · 비교와 확장

기본 관제 화면은 포트 53001의 [Grafana MSP 대시보드](../observability/README.md#grafana-기본-관제-화면)다. 이 콘솔은 앞서 구현한 포트 53002의 비교·확장 화면으로 보존한다. 고객 서비스의 장애·영향·확인 대상을 한 화면에 모으고, 저장된 보고와 담당자 확인 이력을 함께 조회할 때 선택적으로 실행한다. 기본 Grafana 관제에 콘솔 실행은 필요하지 않다.

콘솔은 [Prometheus 관측](../observability/README.md), Grafana와 [보고 서비스](../reporting/README.md)를 사용하며 거래소 웹과 별도 프로세스로 실행한다. Grafana 자체 화면이나 플러그인이 아니며 이곳의 사이드바·테마 설정도 Grafana 원본 메뉴를 변경하지 않는다. 화면 설계와 접근 경계는 [ADR 0028](../../docs/reference/adr/0028-operator-console.md)을 따른다.

## 화면과 자료

| 메뉴 | 내용과 동작 |
|---|---|
| 서비스 관제 | 접속·Spring·DB 경로, 요청·오류·지연·복제 추이, 수집 상태와 최근 경보 |
| 트래픽 · Spring | 인스턴스, HTTP 응답, 요청 경로, JVM·GC·스레드·연결 풀 |
| AWS · 서버 | 고객 호스트 자원과 CloudWatch ALB·ASG·EBS. 미등록 자료는 회색 표시 |
| DB · 복구 | 실제 DB 역할·복제·연결·트랜잭션·잠금·백업과 수동 복구 절차 |
| 고객 전체 현황 | 실제 등록한 고객·환경·대상 조회와 해당 관제로 이동 |
| 알림 현황 | 현재 경보·담당자 확인 기록. 확인 처리와 장애 해제는 별도 |
| 이벤트 이력 | 저장된 사건·해제 보고와 운영자 확인 기록 |
| 운영 보고서 | 실제 저장한 보고 목록과 PDF·HTML 다운로드 |
| 설정 | 테마·조회 기간·갱신 주기의 브라우저별 저장 |
| 대시보드·데이터 소스·사용자 관리 | Grafana의 실제 목록과 원본 관리 화면 연결. 변경 권한은 Grafana에서 적용 |

기본 다크와 라이트 테마를 선택한다. 데스크톱 최소 폭은 1280px이며 1366·1440·1920px 일반 모니터에서 주요 정보를 함께 읽도록 구성한다. 표·상세 내용은 패널 안에서 스크롤한다. 상태는 색·아이콘·문구를 함께 사용한다. 고객·환경·서비스·인스턴스 필터와 조회 기간은 Prometheus 질의에 전달한다. 보고·이벤트 목록은 고객과 환경을 기준으로 조회한다.

`app|reader|data|standby|probe` 역할만 고객 대상으로 읽는다. Ubuntu 운영 실습이나 관측 컨테이너를 고객 AWS 자원으로 대신하지 않는다. `up`은 수집 경로이며 DB 접속은 `pg_up`, 실제 Primary·Standby 역할은 `pg_wal_position_in_recovery`로 구분한다. 누락·정의되지 않은 값·조회 실패는 정상 0으로 바꾸지 않고 그래프 공백을 보존한다. 앱 메모리는 JVM 사용/확보 비율, DB 잠금은 전체 잠금 수이며 대기 수와 다르다.

미등록 Loki·Tempo·배포 이력과 계약상 SLA를 임의 생성하지 않는다. 운영자 확인은 SQLite에 기록하며 경보를 지우거나 알림 전송을 중단하지 않는다. 서버 재시작·DB 승격·ASG 변경은 콘솔에서 수행하지 않는다.

## 로컬 기동

기존 관측 스택이 실행 중인 저장소 루트에서 다음을 실행한다. `.state/compose.env`에는 비공개 파일 경로가 있어야 한다. 콘솔 추가만 실행하므로 기존 DB·앱·경보 수신기는 재생성하지 않는다.

```powershell
docker compose --project-name failstop-observability --env-file deploy/observability/.state/compose.env -f deploy/compose.node-observability.yml -f deploy/compose.console.yml up -d --build --no-deps console
```

접속 주소는 `http://127.0.0.1:53002`이며 기존 Grafana 계정으로 로그인한다. 암호를 새로 만들거나 브라우저 저장소에 보관하지 않는다. 로그인은 Grafana의 실제 조직·역할을 확인하며 Viewer는 조회, Editor·Admin은 알림 확인 처리가 가능하다. 사용자·소스 설정 변경은 Grafana 관리 화면으로 이동한다.

보고 서비스에는 `console_api.py`와 최신 `reporter.py`가 설치되어 있어야 한다. 로컬 WSL bridge도 최신 `windows-wsl-bridge.py`로 실행한다. `CONSOLE_REPORTER_TOKEN_FILE`은 서버가 읽는 보호 파일이며 브라우저에 전달하지 않는다. 목록은 저장된 고객·환경 근거가 일치하는 보고만 반환한다. 파일이 있는 보고에는 1시간 서명 링크를 만들고 콘솔 인증을 거쳐 제공한다. 목록 조회는 PDF 재생성·AI 호출·Teams 전송을 하지 않는다.

## AWS 이전과 접근 통제

동일 Compose를 사설 관측 노드에 추가한다. 기본 게시 주소는 루프백이다. SSM 포트 포워딩을 사용하거나 검증한 사설 HTTPS 역방향 프록시에서 접근한다. 프록시 사용 시 `CONSOLE_PUBLIC_URL`은 실제 HTTPS origin으로 지정한다. HTTP 공인 주소, 임의 Host·Origin과 브라우저의 임의 upstream 지정은 거절한다. 콘솔에 접속할 수 있는 Grafana 조직은 `CONSOLE_GRAFANA_ORG_ID`로 제한한다.

컨테이너는 UID/GID 10001과 읽기 전용 root filesystem으로 실행한다. 영속 상태는 `console-data` 볼륨에만 쓴다. Linux에서는 보고 토큰의 별도 복사본을 root:10001, 0640으로 준비하고 Compose의 `CONSOLE_TOKEN_FILE` 경로를 지정한다. 원래 보고 서비스 파일의 권한을 넓히지 않는다. 각 호스트의 실제 읽기·쓰기 거절은 배포 후 검사한다.

`CONSOLE_REPORTER_URL`은 AWS 보고 서비스의 사설 `http://<주소>:9088`이다. 로컬 `/reporter` bridge 접두어를 AWS 주소에 붙이지 않는다. Grafana·Prometheus 내부 주소는 Compose 서비스 DNS를 사용하며 운영자용 링크는 `CONSOLE_GRAFANA_PUBLIC_URL`, `CONSOLE_PROMETHEUS_PUBLIC_URL`, `CONSOLE_ALERTMANAGER_PUBLIC_URL`로 지정한다.

CloudWatch는 기존 [AWS overlay](../compose.observability-aws.yml)와 Grafana의 `cloudwatch` 데이터 소스를 사용한다. 콘솔에는 실제 `CONSOLE_AWS_CUSTOMER`, `CONSOLE_AWS_REGION`, `CONSOLE_AWS_LOAD_BALANCER`, `CONSOLE_AWS_TARGET_GROUP`, `CONSOLE_AWS_ASG`, `CONSOLE_AWS_VOLUME_ID`를 지정한다. ALB·대상 그룹은 CloudWatch dimension 값, ASG는 그룹 이름, EBS는 volume ID다. 설정한 고객의 `aws` 환경에서만 읽으며 관리형 지표는 60초 동안 캐시한다. IAM·데이터 소스 오류는 미수집으로 표시한다. 실제 AWS 조회·권한·ARM64 실행 검증은 계정 준비 후 수행한다.

세션은 서버 메모리에 최대 8시간 유지하고 60초마다 Grafana 권한을 다시 확인한다. 콘솔 재시작 시 다시 로그인한다. 로그인 제한·동시 요청·캐시·시계열 개수와 조회 기간을 제한한다. 관리 기능은 고객마다 권한이 격리된 멀티테넌트 제품이 아니며, 지정 Grafana 조직의 운영자가 등록된 고객 범위를 함께 조회하는 구조다.

## 검사

```powershell
python -W error::ResourceWarning -m unittest discover -s deploy/console -p 'test_*.py'
node --test deploy/console/test_ui.mjs
python -m unittest discover -s deploy/reporting -p test_console_api.py
python -m unittest discover -s deploy/observability/tests -p test_bridge.py
node deploy/console/check-browser.mjs
```

브라우저 검사는 실행 중인 로컬 콘솔에서 수행하며 실제 계정 값은 보호 파일에서 읽는다. 기존 웹의 Playwright 의존성과 Windows Edge를 사용한다. `CONSOLE_TEST_URL`, `CONSOLE_TEST_USER`, `CONSOLE_TEST_PASSWORD_FILE`로 로컬 접속 설정을 지정할 수 있다. 로그인·각 메뉴·테마·조회 기간·서비스 필터·표·보고 파일을 확인하며, 권한 거절은 API 단위검사에서 확인한다. 브라우저 장애 표시는 응답을 바꾼 별도 표본으로 검사하고 실제 DB 중단이나 외부 메시지를 수행하지 않는다. 현재 검증 상태는 [로드맵](../../docs/ROADMAP.md)에 기록한다.
