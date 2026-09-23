# OPS01 일일·장애 운영 보고 구축 절차

OPS01에 Alertmanager와 보고 서비스를 올려 장애 알림과 운영 보고를 Microsoft Teams로 내보내는 절차다. 주소와 포트, 계정은 `build/00-주소계획.md`를 유일한 기준으로 삼는다. 이 문서의 값이 그 표와 다르면 표가 옳다.

여기에 적힌 내용은 전부 "실행하면 이렇게 확인된다"는 절차다. 2026-09-23 기준으로 이 보고 역할은 OPS01에 설치하지 않았다. [재부팅 스냅숏](../../캡처/재부팅검증-2026-09-23/OPS01_부팅후.txt)의 활성·실행 유닛 목록에 `failstop-reporting`이 없고, `캡처` 폴더에 `41-ops01-reporting.sh` 실행 기록도 없다. 명령을 돌린 뒤에 나온 출력만 증거본에 결과로 남긴다.

## 이 절차가 만드는 것

OPS01에는 경보가 Teams까지 가는 길이 두 갈래 생긴다. 첫째는 Alertmanager가 경보를 받아 Teams Workflows 웹훅으로 바로 보내는 기본 장애 알림이다. 둘째는 같은 경보를 `failstop-reporting` 서비스가 한 번 더 받아 Prometheus 지표와 호스트 증거를 모으고 AI 요약을 붙여 따로 보내는 운영 보고다.

두 길을 나눠 두는 이유는 분명하다. 보고 서비스가 죽거나 AI 응답이 오지 않아도 기본 장애 알림은 그대로 나가야 하기 때문이다. 그래서 Alertmanager의 라우팅에서 `teams` 수신자를 `continue: true`로 두고 `evidence-reporter`를 그 뒤에 둔다. 두 수신자를 하나로 합치면 이 분리가 사라지므로 합치지 않는다.

AI는 요약문만 만든다. 서비스를 재시작하거나 DB를 승격하거나 자원을 늘리고 줄이는 경로는 코드에 없다. 보고서의 `next_checks`는 사람이 확인할 항목이지 자동 조치 예고가 아니다.

## 담당 범위와 경계

이 절차가 설치하는 것은 Alertmanager, `failstop-reporting` 상시 서비스, 매일 09:00 KST 일일 보고 타이머, 그리고 Prometheus에 붙이는 경보 규칙이다.

Prometheus와 Grafana, `node_exporter`, `postgres_exporter`, 앱의 Actuator 노출은 관측 역할이 먼저 끝내 둔다. 호스트 증거 수집기인 `failstop-host-evidence` 역시 관측 역할이 `node_exporter`와 함께 설치한다. 보고 설치는 그 유닛이 이미 있을 때만 `--group failstop-report` drop-in을 붙여 보고 계정이 증거 파일을 읽게 만든다.

방화벽 계통은 공통 절차의 `table inet vmlab` 하나다. 설정은 `/etc/vm-lab/firewall.nft`, 유닛은 `vm-lab-firewall.service`이고 input 기본 정책은 drop이다. `scripts/ops01/ops01-firewall.nft`의 단독 테이블과 배포판 `nftables.service`는 쓰지 않는다. 보고 포트 9088은 `127.0.0.1`에만 바인딩하고 어떤 출발지에도 열지 않으므로 별도 허용 규칙도 별도 drop 규칙도 필요 없다. 기본 정책이 drop이면 열거하지 않은 포트는 이미 막힌다.

41 스크립트는 nftables를 직접 바꾸지 않고 상태만 확인한다. 확인할 것은 `nft list chain inet vmlab input`의 정책이 drop인지와 `ss -lnt`에서 9088이 `127.0.0.1`에만 떠 있는지 두 가지다. 이 둘이 맞으면 통과다. `inet failstop` 테이블을 찾지 않는다. 공통 절차를 쓰는 정상 경로에서는 그 테이블이 아예 만들어지지 않으므로, 그 테이블에서 9088 규칙을 찾는 확인은 항상 경고를 내고 40의 `firewall` 단계를 다시 돌려도 상태가 바뀌지 않는다. 해결되지 않는 안내를 증거본에 남기지 않는다.

pfSense는 사용자가 직접 구축한다. 다만 Alertmanager 릴리스를 내려받는 구간은 NAT 경로에 의존하므로 기본 경로를 pfSense로 옮기기 전에 끝내는 편이 낫다. 옮긴 뒤에는 승인한 저장소만 열리므로 이 내려받기가 막힌다. 먼저 옮겼다면 아카이브를 다른 곳에서 받아 `--alertmanager-archive`와 `--alertmanager-sums`로 넘긴다.

## 산출물

| 파일 | 설치 위치 | 소유·권한 |
|---|---|---|
| `build-files/ops/reporting.config.vm.json` | `/etc/failstop/reporting.json` | root:failstop-report 0640 |
| `build-files/ops/alerts.vm.yml` | `/etc/prometheus/vm-alerts.yml` | root:prometheus 0640 |
| `build-files/ops/alertmanager.vm.yml` | `/etc/failstop/alertmanager.yml` | root:<실행 계정 그룹> 0640 |
| `deploy/reporting/systemd/*` | `/etc/systemd/system/failstop-reporting*` | root:root 0644 |
| `scripts/41-ops01-reporting.sh` | 실행 절차 | - |

`alertmanager.vm.yml`은 관측 역할이 먼저 만들어 둔 파일을 그대로 쓴다. 학교 AWS 사본과 다른 부분은 `evidence-reporter`의 수신 주소를 `172.31.44.141:9088`에서 `127.0.0.1:9088`로 되돌린 것과 카드 제목의 환경 표기에 `vm-lab` 분기를 더한 것뿐이다. 보고 서비스와 Alertmanager가 같은 OPS01에 있으므로 루프백이 맞다. 스크립트는 `build-files/ops`를 먼저 보고 없으면 `scripts/ops01`에서 찾는다. 설치 전에 `127.0.0.1:9088`과 `webhook_url_file`이 실제로 들어 있는지 확인하고 아니면 멈춘다.

경보 규칙은 `build-files/ops/alerts.vm.yml`이 정본이다. 관측 역할이 만든 `scripts/ops01/vm-alerts.yml`은 같은 규칙의 사본이므로 둘 중 하나만 설치한다. `40-ops01-observability.sh`도 `alerts.vm.yml`이 있으면 그것을 정본으로 쓰도록 만들어져 있다. 두 파일의 내용이 갈리면 두 스크립트 모두 경고를 낸다. 규칙을 고칠 때는 두 파일을 같이 고치거나 사본을 지운다.

관측 역할의 `40-ops01-observability.sh`를 먼저 돌린 뒤 이 스크립트를 돌리는 것이 정상 순서다. 40이 Prometheus, Grafana, `node_exporter`, Alertmanager 실행 파일과 계정을 올리고, 41이 보고 서비스를 얹으면서 Alertmanager를 최종 설정으로 연결한다. 두 스크립트가 같은 일을 하는 구간, 예를 들어 Alertmanager 실행 파일 설치나 경보 규칙 설치는 이미 되어 있으면 건너뛴다.

## 재사용한 것과 버린 것

저장소의 기존 자산을 그대로 쓰는 것이 원칙이다. 보고 본체인 `deploy/reporting`의 열두 개 모듈은 한 줄도 고치지 않는다. `install.py`가 그 파일들을 `/opt/failstop/deploy/reporting`에 0644로 복사하고, 전용 계정 `failstop-report`를 만들고, 비밀 파일을 원자적으로 교체하고, 세 개의 systemd 유닛을 설치한다. Alertmanager 설치 절차는 `deploy/linux/reporting/install-school.py`의 방식을 그대로 옮겼다. 릴리스를 받아 `sha256sums.txt`와 대조하고, 아카이브 안의 경로를 믿지 않고 `alertmanager`와 `amtool` 두 개만 꺼내 0755로 설치한다.

버린 것도 분명히 해 둔다. `deploy/linux/reporting/report.py`와 `exchange-report@.service`, `exchange-report-daily.timer`, `exchange-report-incident.timer`는 설치하지 않는다. 같은 Teams 채널로 일일 보고가 두 번 가기 때문이다. 학교에서도 `activate-school-reporting.py`가 이 두 타이머를 `disable --now`한 뒤 `failstop-reporting`으로 넘겼다. 다만 이 경로는 Prometheus 하나만 있으면 도는 최소 구성이므로 Alertmanager 설치가 막힐 때의 대안으로 남겨 둔다. 그때 노드 목록은 EDGE01, APP01, APP02, DB01, DB02, OPS01이 된다.

`nginx-reports.conf`의 172.31.44.141 프록시, `https://www.sang.it.kr/operations` 서명 URL, 학교에서 손으로 써 넣은 `backup-restore-school.json`도 옮기지 않는다. 학교 AWS 전용 주소와 학교에서 측정한 결과이기 때문이다. PDF·HTML 파일 제공 기능도 끈다. VM에는 수신자가 닿을 HTTPS 주소가 없고, 켜려면 EDGE01에서 OPS01의 9088로 가는 경로를 새로 열어야 하는데 그 경로는 주소계획의 포트 표에 없다. 표를 먼저 고치지 않고 열지 않는다. 같은 이유로 `python3-reportlab`과 한글 TTF 글꼴도 설치하지 않는다.

## 라벨 규약과 환경 이름

보고의 모든 수치는 Prometheus 라벨에서 온다. 설정의 선택자와 실제 라벨이 한 글자라도 다르면 보고서 전체가 미수집으로 나온다. VM에서 쓰는 값은 다음과 같다.

| 라벨 | 값 |
|---|---|
| `customer` | `exchange` |
| `environment` | `vm-lab` |
| `node` | `edge01`, `app01`, `app02`, `db01`, `db02`, `ops01` |
| `role` | `edge`, `app`, `data`, `standby`, `observability` |
| `job` | `linux-host`, `exchange-api`, `postgres` |

여기서 주의할 점이 하나 있다. `install.py`의 `--environment` 선택지는 `local-lab`과 `aws` 둘뿐이다. 코드를 고치지 않으려면 설치 시점에는 `local-lab`을 넘길 수밖에 없다. 그런데 관측 역할이 만든 `prometheus.yml`은 이미 `environment: vm-lab`을 붙이고 있고, 기존 WSL·Docker 로컬 검증도 `local-lab`을 쓰기 때문에 그대로 두면 VM 결과와 과거 로컬 결과가 같은 이름으로 섞인다.

그래서 이 절차는 `install.py`를 `--environment local-lab`으로 부른 뒤, 미리 `vm-lab`으로 묶어 둔 `reporting.config.vm.json`을 `/etc/failstop/reporting.json`에 덮어쓴다. 설정 파일은 `scope.py`의 `scoped_config(config, "exchange", "vm-lab")`를 그대로 적용해 만들었으므로 열두 개 쿼리의 선택자와 `alert_matchers`가 한 번에 같은 값으로 묶여 있다. 스크립트는 덮어쓰기 전에 설치된 코드의 `load_config`로 문법과 한도를 검사하고, 덮어쓴 뒤에는 `failstop-report` 계정으로 다시 읽어 `environment=vm-lab`을 확인한다.

`install.py`의 `choices`를 고쳐 `vm-lab`을 정식 선택지로 넣는 방법도 있다. 이번 작업에서는 저장소 코드를 고치지 않는 쪽을 골랐다. 보고 카드의 환경 표기도 `local-lab`과 `aws`만 한국어로 바꾸므로 `vm-lab`은 그대로 `vm-lab`으로 보인다. 표시만의 문제이며 수치에는 영향이 없다.

쿼리의 `role` 정규식에는 `edge`를 더했다. 원본은 `app|reader|data|standby|probe`인데 EDGE01의 역할 값 `edge`가 없어서 그대로 두면 EDGE01의 디스크·서비스 사실이 보고서에서 빠진다. `alert_matchers`에는 `edge`와 함께 `observability`도 더했다. OPS01 자신에게 난 경보, 예를 들어 디스크 시험으로 일으키는 `LinuxDiskSpaceLow`가 보고에 들어가게 하기 위해서다. 쿼리 쪽에는 `observability`를 넣지 않았다. 관측 도구 자신의 수집 상태를 서비스 정상 판정과 섞지 않는다는 원래 설계를 유지했고, OPS01의 호스트 사실은 지표가 아니라 `host-latest.json`에서 따로 들어오기 때문이다.

## 실행 전에 준비할 것

### 1. 관측 역할이 끝나 있어야 한다

Prometheus가 `127.0.0.1:9090`에 떠 있고 여섯 대의 `node_exporter`, 앱 두 대의 Actuator, DB 두 대의 `postgres_exporter` 수집이 붙어 있어야 한다. 다음 명령으로 직접 본다.

```bash
curl -fsS http://127.0.0.1:9090/-/ready
curl -s 'http://127.0.0.1:9090/api/v1/query?query=up' | python3 -m json.tool | grep -E '"node"|"role"|"environment"'
```

`edge01`, `app01`, `app02`, `db01`, `db02`, `ops01`이 모두 나오고 `customer=exchange`, `environment=vm-lab`이 붙어 있어야 한다. 하나라도 빠지면 그 노드의 사실은 보고서에 미수집으로 찍힌다. 스크립트도 같은 확인을 하고 맞지 않으면 멈춘다.

호스트 증거 수집기도 이미 돌고 있어야 한다.

```bash
systemctl is-active failstop-host-evidence.timer
ls -l /var/lib/failstop/evidence/host-latest.json
```

### 2. 비밀 값을 OPS01에서 직접 만든다

비밀 값은 이 저장소와 문서, 증거본 캡처 어디에도 남기지 않는다. 아래 명령을 OPS01에서 직접 실행하고, 값을 입력하는 화면은 캡처하지 않는다. 증거본에는 그 구간을 누락으로 표시한다.

```bash
sudo install -d -o root -g root -m 0755 /etc/failstop
sudo install -m 0600 /dev/null /etc/failstop/teams-webhook
sudo install -m 0600 /dev/null /etc/failstop/gemini-key
sudo nano /etc/failstop/teams-webhook   # Workflows 웹훅 URL 한 줄
sudo nano /etc/failstop/gemini-key      # AI API 키 한 줄
```

웹훅은 Teams Workflows에서 만든 것이어야 한다. `webhook trigger`와 Adaptive Card 게시 단계를 갖춰야 하고 호스트는 `.logic.azure.com` 또는 `.api.powerplatform.com` 아래여야 한다. 다른 호스트를 넣으면 `install.py`가 `endpoint_is_not_teams_workflows`로 막는다. 파일에는 URL 한 줄만 둔다. 여러 개를 넣으면 `secret_missing_or_ambiguous`로 막힌다.

Alertmanager가 보고 서비스로 경보를 넘길 때 쓰는 Bearer 토큰도 필요하다. 이 값은 사람이 고르는 비밀이 아니라 난수이므로 다음처럼 만든다.

```bash
openssl rand -hex 32 | sudo tee /etc/failstop/reporter-token >/dev/null
sudo chmod 0600 /etc/failstop/reporter-token
```

`install.py`는 이 파일이 이미 있으면 그 값을 그대로 쓰고 없으면 새로 만든다. 다만 길이가 32자 이상이고 영숫자만 있어야 한다. 하이픈이나 기호가 섞이면 `Invalid existing ingest token`으로 막히므로 위 명령으로 만든 16진수 값을 쓴다. 관측 역할의 `40-ops01-observability.sh`를 먼저 돌린다면 그 스크립트도 이 파일을 요구하므로 그 전에 만들어 둔다.

세 파일 모두 `install.py`가 읽은 뒤 같은 경로에 `root:failstop-report 0640`으로 다시 쓴다. 설치를 다시 돌려도 같은 결과가 나오므로 매번 새로 만들 필요는 없다.

### 3. 저장소를 OPS01에 내려 둔다

스크립트는 `deploy/reporting`과 `deploy/linux/reporting`의 파일을 그대로 쓴다. 저장소 경로를 `--repo`로 넘긴다.

## 설치 실행

```bash
sudo /home/ops/vm-lab/build/scripts/41-ops01-reporting.sh --repo /home/ops/failstop
```

스크립트는 아홉 단계를 순서대로 지나며 각 단계에서 막히면 그 자리에서 멈춘다. 실패를 넘기고 다음 단계로 가지 않는다.

1단계는 선행 조건을 본다. root 여부, 필수 명령, `python3` 3.10 이상, 저장소 파일과 산출물 파일의 존재, 비밀 파일의 존재를 확인한다. 비밀 파일은 열어 보지 않고 일반 파일인지와 크기가 0이 아닌지만 본다. `python3`가 3.10 미만이면 여기서 멈춘다. 그때는 유닛의 `/usr/bin/python3` 경로를 별도 런타임으로 바꿔야 하며, 학교 AL2023에서는 같은 이유로 `/opt/exchange-report-runtime` 가상환경을 따로 만들었다.

2단계는 패키지를 넣는다. `sysstat`은 `collect-host.py`가 `iostat`으로 디스크 IO 증거를 모으는 데 쓰고, 없으면 host 증거가 error로 남아 보고서에 미수집으로 표시된다. `tar`는 Alertmanager 아카이브에서 실행 파일 두 개를 꺼내는 데 쓴다. `chrony`는 보고 기준 시각과 09:00 실행의 근거다. 시계가 어긋나면 경보 감지 시각과 표본 나이 판정이 함께 틀어진다. SELinux는 끄지 않는다. `semanage`가 없으면 `policycoreutils-python-utils`만 더 넣는다. 시스템 시간대는 바꾸지 않고 `Asia/Seoul`이 아니면 경고만 낸다. 다른 역할의 로그 시각 기준까지 같이 움직이는 변경이라 사람이 결정할 일이다.

3단계는 관측 선행 조건을 확인한다. Prometheus 응답과 위에서 적은 라벨 규약을 실제 `up` 시계열로 대조한다.

4단계는 Alertmanager 0.31.1을 설치한다. `dnf`로 받지 않는 이유는 두 가지다. Rocky 10 기본 저장소의 제공 여부를 확인하지 않았고, 설정이 쓰는 `msteamsv2_configs`가 0.28 이상을 요구한다. 릴리스와 `sha256sums.txt`를 받아 해시를 대조하고 다르면 중단한다. 아카이브 안의 경로를 그대로 풀지 않고 `alertmanager`와 `amtool`만 표준출력으로 꺼내 `/usr/local/bin`에 0755로 넣은 뒤 `restorecon`으로 문맥을 맞춘다. 이미 같은 버전이 있으면 내려받기를 건너뛴다.

5단계는 보고 서비스를 설치한다. `install.py`를 다음 인수로 부른다.

```
--apply --environment local-lab --customer exchange --bind 127.0.0.1
--prometheus-url http://127.0.0.1:9090 --alertmanager-url http://127.0.0.1:9093
--analyzer gemini --ai-model gemini-3.1-flash-lite
--secret-file /etc/failstop/teams-webhook --ai-secret-file /etc/failstop/gemini-key
```

학교에서 쓰던 `--artifact-base-url`, `--pdf-font`, `--pdf-font-bold`는 넣지 않는다. `--enable-delivery`도 넣지 않는다. 설치 직후에는 `REPORT_DELIVERY_ENABLED=false`여서 수집과 대기열 등록만 하고 밖으로 아무것도 보내지 않는다. 스크립트는 이 값이 실제로 false인지 확인하고 아니면 중단한다. `/etc/failstop/reporting.env`에는 수집 토큰이 들어 있으므로 파일 내용을 출력하지 않고 해당 줄의 존재만 본다.

6단계는 `reporting.config.vm.json`을 `/etc/failstop/reporting.json`에 덮어쓴다. 위의 "라벨 규약과 환경 이름"에서 설명한 이유다.

7단계는 Alertmanager 설정과 서비스를 올린다. 여기서 계정 문제를 하나 정리한다. `install.py`는 `/etc/failstop/teams-webhook`과 `/etc/failstop/reporter-token`을 실행할 때마다 `root:failstop-report 0640`으로 다시 쓴다. 그런데 관측 역할은 Alertmanager를 전용 계정 `alertmanager`로 돌리고 같은 파일을 `root:alertmanager`로 맞춘다. 두 계정이 같은 두 파일을 읽어야 하는데 한쪽만 그룹을 가질 수 있는 상황이다.

`failstop-report` 쪽에 보조 그룹을 붙이는 방법은 쓸 수 없다. `install.py`가 그 계정에 전용 기본 그룹 외의 그룹이 붙어 있으면 설치를 거부한다. 그래서 반대로 `alertmanager` 계정을 `failstop-report` 그룹에 넣는다. 파일은 계속 `root:failstop-report 0640`으로 두고 Alertmanager는 그룹 권한으로 읽는다. 이렇게 하면 두 스크립트 중 어느 쪽을 나중에 돌려도 두 서비스가 모두 파일을 읽는다.

관측 역할의 유닛이 아직 없으면 저장소의 `deploy/linux/reporting/alertmanager.service`를 설치한다. 이 유닛은 `failstop-report` 계정으로 돌기 때문에 그룹을 더 손댈 필요가 없다. 원본 유닛의 Description은 학교 AWS 문구이므로 파일을 고치지 않고 drop-in으로 표시 이름만 덮어쓴다.

어느 쪽이든 설정을 넣은 뒤 `amtool check-config`를 root와 실제 실행 계정 두 가지로 각각 돌린다. 두 번째 확인이 서비스 계정이 비밀 파일을 실제로 읽는다는 증거다.

한 가지 주의할 점이 있다. `40-ops01-observability.sh`의 `connect` 단계는 두 비밀 파일을 `root:alertmanager`로 다시 chown한다. 41을 돌린 뒤에 40의 `connect`를 다시 돌리면 보고 서비스가 웹훅 파일을 읽지 못하게 된다. 그때는 41을 다시 돌리거나 `install.py`만 다시 실행해 소유를 되돌린다. 두 스크립트 모두 여러 번 돌려도 같은 결과가 나오도록 만들어져 있다.

8단계는 경보 규칙을 `/etc/prometheus/vm-alerts.yml`에 넣고 `promtool check rules`로 검사한다. 이어서 `prometheus.yml`에 `rule_files`와 `alerting`이 없으면 한 번만 백업하고 붙인 뒤 `promtool check config`를 돌린다. 실패하면 원문으로 되돌리고 멈춘다. 관측 역할의 `prometheus.yml`에는 이미 둘 다 들어 있으므로 보통은 편집 없이 지나간다.

9단계는 설치 결과를 확인한다. 다음 절의 항목이 여기서 출력된다.

## 확인 항목

설치 스크립트가 자동으로 도는 확인은 다음과 같다. 출력이 기대와 다르면 스크립트가 멈춘다.

```bash
systemctl is-active alertmanager failstop-reporting
systemctl list-timers failstop-reporting-daily.timer --no-pager
ss -lntp | grep -E ':(3000|9088|9090|9093)\b'
curl -fsS http://127.0.0.1:9088/health
```

`ss`의 Local Address가 전부 `127.0.0.1`이어야 한다. `0.0.0.0`이나 `192.168.10.41`이 보이면 스크립트가 중단한다. `/health`는 `{"status":"ok"}`를 돌려주는데, 이는 SQLite를 읽을 수 있다는 뜻일 뿐 Teams 전달이 성공했다는 증거가 아니다.

일일 타이머의 NEXT는 다음 09:00 KST여야 한다. `Persistent=true`이므로 VM이 꺼져 있었으면 다음 기동 때 밀린 실행이 한 번 돈다.

저널에 비밀 값이 섞여 나오지 않는지도 개수만 센다. 일치한 줄 자체는 출력하지 않는다.

```bash
journalctl -u failstop-reporting -u alertmanager --since today --no-pager | grep -c -E 'logic\.azure\.com|AIza|Bearer '
```

0이 나와야 한다.

### 사람이 직접 하는 확인

여기부터는 스크립트가 하지 않는다. 다른 VM에 들어가거나 실제로 전송을 켜는 일이라 사람이 판단할 구간이다.

포트가 VM 밖으로 열리지 않았는지는 APP01에서 본다.

```bash
curl --connect-timeout 3 http://192.168.10.41:9088/health
curl --connect-timeout 3 http://192.168.10.41:9093/
```

둘 다 연결 거부나 타임아웃으로 끝나야 한다. 응답이 오면 바인딩이나 nftables 규칙을 고친 뒤 다시 본다.

`/metrics`는 수집 토큰이 있어야 읽는다. 토큰은 `/etc/failstop/reporter-token`에 있고 `root:failstop-report 0640`이다.

```bash
sudo curl -H "Authorization: Bearer $(sudo cat /etc/failstop/reporter-token)" http://127.0.0.1:9088/metrics
```

`report_jobs`, `report_delivery_enabled`, `report_oldest_pending_age_seconds`가 나온다. 토큰 없이 부르면 401이다. 이 명령은 토큰을 화면에 띄우지는 않지만 셸 기록에 남으므로 캡처할 때 주의한다.

대기열 상태는 다음으로 본다.

```bash
sudo runuser -u failstop-report -- python3 /home/ops/failstop/deploy/reporting/run-installed.py status
```

전송 승인 전에는 `delivery_enabled`가 false여야 한다.

## 경보가 실제로 흐르는지 보기

설치만으로는 경로가 살아 있는지 알 수 없다. 경보를 한 번 실제로 태운다. APP02의 `node_exporter`를 1분 이상 멈추면 `LinuxHostUnreachable`이 firing으로 바뀐다.

```bash
# APP02에서. 유닛 이름은 관측 역할의 설치 방식에 따라 node_exporter 또는 failstop-node-exporter다.
sudo systemctl stop node_exporter
# OPS01에서 1분쯤 뒤
sudo journalctl -u alertmanager --since -10m --no-pager | tail -n 30
sudo journalctl -u failstop-reporting --since -10m --no-pager | tail -n 30
sudo python3 /home/ops/failstop/deploy/linux/reporting/check-school-alerts.py
```

`check-school-alerts.py`는 Prometheus의 `/api/v1/alerts`, Alertmanager의 `/api/v2/alerts`, 그리고 outbox SQLite를 읽어 `PROM`, `AM`, `REPORT` 세 줄 묶음을 낸다. 같은 경보를 가리키면 경로가 이어진 것이다. 이 스크립트는 웹훅과 서명 링크, 보고 원문을 출력하지 않으므로 그대로 캡처해도 된다. Alertmanager 로그에는 `teams` 수신자 전달 시도가 남고, 보고 서비스는 `202 accepted`를 돌려주며 outbox에 incident 작업이 생긴다. APP02의 `node_exporter`를 다시 켜면 resolved 알림도 온다.

## 전송을 켜는 순간

승인된 수신 대상과 정제된 보고 내용을 사람이 확인한 뒤에만 켠다.

```bash
sudo sed -i 's/^REPORT_DELIVERY_ENABLED=false$/REPORT_DELIVERY_ENABLED=true/' /etc/failstop/reporting.env
sudo systemctl restart failstop-reporting
sudo runuser -u failstop-report -- python3 /home/ops/failstop/deploy/reporting/run-installed.py test-send
```

`test-send`는 모의 연결 시험 한 건을 보낸다. 여기서 HTTP 2xx가 나와도 그것은 접수 증거일 뿐이다. Workflows 실행 이력과 Teams 채널 화면을 따로 열어 카드가 실제로 보이는지 눈으로 확인하고 캡처한다. 2xx만 보고 게시 성공으로 기록하지 않는다.

일일 보고의 내용을 미리 보고 싶으면 `daily`를 한 번 수동으로 돌린다. 다만 이때 만들어지는 보고는 그 시점의 지표로 작성되므로 정기 보고와 구분해 기록한다.

## 장애 경로와 실패 견디기 시험

디스크 경보는 `disk-alert-exercise.py`로 한 번 태운다. 이 스크립트는 128MiB짜리 루프 파일시스템을 따로 만들어 채우고 7분 뒤 `systemd-run`으로 공간을 자동 반환한다. 운영 루트와 DB 파일은 건드리지 않는다.

```bash
sudo python3 /home/ops/failstop/deploy/linux/reporting/disk-alert-exercise.py
```

5분 안에 `LinuxDiskSpaceLow`가 발화하고 기본 알림과 보고가 따로 도착한 뒤, 7분 뒤에 공간이 돌아오며 경보가 해제되는지 본다. 이 시험을 OPS01에서 돌리면 경보의 `role`은 `observability`가 된다. `alert_matchers`에 `observability`를 넣어 둔 것이 이 때문이다. APP02에서 돌려도 되며 그때는 `role=app`으로 잡힌다.

실패를 견디는지는 두 가지로 나눠 본다. 먼저 AI 키 파일을 잠시 잘못된 값으로 두고 경보를 태우면 보고의 `analysis.mode`가 `facts-only`로 바뀐 카드가 그대로 도착한다. 같은 시점에 Alertmanager의 기본 알림도 따로 온다. 이것이 AI 실패와 기본 알림이 분리돼 있다는 실제 확인이다. 다음으로 `systemctl stop failstop-reporting` 상태에서 경보를 태우면 Teams에는 기본 장애 알림만 오고 보고는 오지 않는다. 서비스를 다시 켜면 밀린 작업이 outbox에서 재시도된다. 실패는 최대 다섯 번까지 재시도한 뒤 dead로 남는다.

## 방화벽과 노출 정책

OPS01에서 새로 열리는 인바운드 포트는 없다. 보고 서비스는 `127.0.0.1:9088`, Alertmanager는 `127.0.0.1:9093`에만 바인딩한다. 공통 기반의 input 기본 정책이 drop이므로 9088과 9093, 3000과 9090은 허용 목록에 없다는 것만으로 이미 막힌다. 포트마다 drop 줄을 따로 적지 않는다. 관제 화면은 `ssh -L 3000:127.0.0.1:3000` 터널로만 본다.

밖으로 나가는 연결은 TCP 443 두 곳이다. Teams Workflows 웹훅 호스트 하나와 `generativelanguage.googleapis.com`이다. 그 밖에 이름 해석용 53과 시각 동기용 123, 구축 단계의 패키지 저장소가 필요하다. 수집 방향으로는 OPS01에서 각 VM의 9100, APP01과 APP02의 8081, DB01과 DB02의 9187으로 나간다. 목적지 쪽 nftables에서 출발지를 `192.168.10.41`로 제한한다.

`deploy/linux/firewall-host.sh`는 OPS01에 그대로 쓸 수 없다. 세 가지가 VM 정책과 충돌한다. 첫째로 그 스크립트는 SSH 포트가 22이면 거부하는데 주소계획은 22를 쓴다. 둘째로 역할이 `observability`일 때 Grafana, Prometheus, Alertmanager 포트를 관리 대역에 열고 9088을 관측 대역에 여는데 VM은 이 네 포트를 아무에게도 열지 않는다. 셋째로 관리 대역 하나가 SSH와 관제 포트에 함께 쓰여 둘을 다르게 둘 수 없다. 그래서 규칙을 따로 쓰지 않고 공통 기반의 `table inet vmlab`을 그대로 쓴다. OPS01이 더 열 포트가 없기 때문이다. `scripts/ops01/ops01-firewall.nft`는 input 정책이 accept라 열거한 포트만 drop하고 나머지를 열어 두므로 접근제어도와 어긋나고, 이 구성에서는 쓰지 않는다. 포트마다 허용 출발지를 먼저 쓰고 add와 flush를 같은 트랜잭션에 묶는 방식은 공통 기반이 이미 같은 형태로 갖고 있다.

## SELinux

SELinux는 끄지 않는다. 이번에 설치하는 유닛들은 정책 전이가 없어 `unconfined_service_t`로 뜨므로 포트 라벨링이 필요 없을 것으로 보인다. 다만 Rocky 10에서 실제로 확인한 것은 아니다. `/usr/local/bin`에 넣은 실행 파일과 새로 쓴 유닛 파일에는 `restorecon`을 걸어 문맥을 맞춘다. 문제가 생기면 다음으로 거부 기록을 본다.

```bash
sudo ausearch -m AVC -ts today
```

거부가 나오면 SELinux를 끄지 말고 필요한 포트나 문맥을 더하는 방향으로 다룬다.

## 남은 위험과 확정하지 않은 것

보고에 들어가는 host 증거는 OPS01 자신의 것뿐이다. 나머지 다섯 대의 호스트 상태는 Prometheus 지표로만 들어오며 `df`나 `systemctl` 출력 원문은 보고서에 없다. 구성도의 "서비스 로그·지표 수집" 문구를 이 범위 이상으로 읽지 않는다.

저널 로그 증거를 만드는 수집기는 저장소에 없다. `journal_file`은 null로 둔다. 보고서의 로그 근거는 `host-latest.json`이 담는 실패 서비스 목록, 서비스 상태, 저널 사용량으로 제한된다.

`backup_schedule_file`과 `backup_restore_file`도 null로 시작한다. 학교의 `backup-restore-school.json`을 옮겨 적지 않는다. DB 역할이 VM에서 실제 복원 검증을 한 뒤 `schema_version 1` 형식으로 직접 써야 그 사실이 보고서에 들어간다.

Prometheus가 보고 서비스의 9088 `/metrics`를 스스로 수집하게 하려면 Bearer 토큰이 필요하다. `/etc/failstop/reporter-token`은 `root:failstop-report 0640`이라 `prometheus` 계정이 읽지 못한다. 토큰 사본을 `prometheus` 그룹으로 하나 더 두거나 자체 수집을 포기해야 하며 아직 정하지 않았다. 지금 설정에서는 자체 수집을 하지 않는다.

Alertmanager를 `dnf`로 설치할 수 있는지 Rocky 10에서 확인하지 않았다. 릴리스 내려받기는 구축 단계의 NAT 경로에 의존한다.

AI 호출에는 상한이 걸려 있다. 하루 50회, 월 1000회, 분당 4회이며 입력 6000, 출력 1200 토큰이다. 상한을 넘으면 AI 없이 사실만 담은 보고로 내려간다. 비용 계산과 모델 가격은 이번 작업에서 확인하지 않았다.

Teams 웹훅은 HTTP 2xx를 돌려줘도 채널 게시를 보장하지 않는다. 워크플로 실행 이력과 채널 화면을 따로 확인한다. 워크플로 소유자와 인증 방식도 사람이 관리한다.

관측 역할과 맞춰야 하는 라벨 규약은 아직 실제로 대조해 보지 않았다. 스크립트의 3단계가 그 대조를 수행하므로 처음 실행할 때 여기서 걸릴 수 있다.

## 증거로 남길 것

모든 단계의 명령과 출력을 `증거본`에 남긴다. 비밀 값을 입력하는 구간은 캡처하지 않고 누락 구간으로 표시한다. 다음 여섯 가지는 실제 화면이 필요하다.

1. 설치 스크립트의 각 단계 출력, 특히 SHA-256 대조 통과와 `amtool check-config` 두 계정 통과
2. `ss -lntp`의 루프백 전용 바인딩
3. APP01에서 9088과 9093이 막힌 화면
4. `check-school-alerts.py`의 PROM·AM·REPORT 세 줄 묶음
5. Teams 채널에 실제로 보이는 기본 장애 알림 카드와 운영 보고 카드
6. `facts-only` 모드로 내려간 보고 카드와 같은 시점의 기본 알림

HTTP 2xx 응답만으로 게시 성공을 기록하지 않는다. 실행하지 않은 검증을 통과했다고 쓰지 않는다.
