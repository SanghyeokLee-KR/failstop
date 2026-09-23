# 관측 경로 워치독

학교 AWS의 Prometheus, Alertmanager, 보고 서비스, Grafana는 모두 bot-1에 있다. bot-1이 멈추면 그 사실을 알려야 할 경보 경로도 함께 멈추므로, 지금 구성으로는 관측 노드의 장애가 조용히 지나간다. 이 폴더는 그 공백을 줄이는 세 가지를 담는다. app-1에서 1분마다 bot-1의 응답을 확인하는 교차 워치독, bot-1 안에서 경보 경로와 보고 서비스의 흔적을 확인하는 로컬 워치독, 그리고 Prometheus가 항상 발화하는 Watchdog 규칙이다. 두 워치독은 같은 프로그램이며 Alertmanager를 거치지 않고 Teams Workflows 웹훅으로 직접 알린다.

2026-09-23 현재 이 폴더는 저장소의 코드와 설정 템플릿일 뿐이며 학교 서버에는 적용하지 않았다. 설치, SG, Prometheus와 Alertmanager 설정 변경은 아래 절차와 사용자 승인 뒤에 진행한다. 로컬에서 확인한 범위는 마지막 절에 적었다.

## 무엇을 확인하는가

app-1의 교차 워치독은 이미 허용된 경로만 사용한다. 봇 SG는 앱 SG에서 오는 3000, 8443, 9088만 받고 Prometheus 9090과 Alertmanager 9093은 bot-1의 루프백에만 열려 있다([AWS 작업 안내](../../../docs/reports/AWS_작업.md), [Prometheus 서비스](../observability/prometheus.service), [Alertmanager 서비스](../reporting/alertmanager.service)). 그래서 app-1에서는 Grafana와 보고 서비스의 헬스만 기본으로 확인한다.

| 위치 | 점검 이름 | 확인 경로 | 이상으로 보는 조건 |
|---|---|---|---|
| app-1 | `grafana` | `http://172.31.44.141:3000/grafana/api/health` | 응답 실패, `database`가 `ok`가 아님 |
| app-1 | `reporting` | `http://172.31.44.141:9088/health` | 응답 실패, `status`가 `ok`가 아님 |
| app-1 | `prometheus` (꺼 둠) | `http://172.31.44.141:9090/-/ready` | Prometheus 사설 listen과 봇 SG 9090 추가를 승인한 뒤에만 켠다 |
| bot-1 | `prometheus` | `http://127.0.0.1:9090/-/ready` | 응답 실패 |
| bot-1 | `alert_pipeline` | Alertmanager `GET /api/v2/alerts`의 Watchdog | Alertmanager 무응답, 또는 `customer=exchange`, `environment=aws`인 Watchdog이 없거나 `endsAt`이 지남 |
| bot-1 | `report_queue` | 보고 서비스 `/metrics` | 전송 포기(dead) 수가 `max_dead` 초과, 가장 오래된 미전송 보고가 30분 초과, Teams 전송 비활성 |
| bot-1 | `daily_report` | `failstop-reporting-daily.timer`, 같은 이름의 service, `/metrics` | 오늘 09:00 예약 실행 기록이 10분 안에 없음, 등록 service 실패, 30분 안에 일일 보고 전송 기록 없음 |
| bot-1 | `market_bot` | `exchange-bot.service` | active가 아님. 용량 점검 unit의 마지막 결과를 함께 적는다. 이 결과는 용량 점검 스크립트의 오류 여부만 보여 준다 |
| bot-1 | `public_site` | `https://www.sang.it.kr/api/v1/markets` | 200이 아님, TLS 검증 실패, 인증서 만료 14일 미만 |

bot-1의 `alert_pipeline`이 데드맨 스위치 역할을 한다. Prometheus는 [Watchdog 규칙](watchdog-rules.yml)을 항상 발화시키고 매분 Alertmanager에 다시 보낸다. Prometheus가 규칙 평가를 멈추거나 Alertmanager로 보내지 못하면 Alertmanager는 마지막으로 받은 `endsAt`이 지난 뒤 이 경보를 목록에서 뺀다. 워치독은 그 부재를 경보 경로 고장으로 판단한다. 학교 Prometheus의 기본 재전송 간격 1분과 15초 평가에서는 `endsAt`이 마지막 전송 뒤 약 4분이므로, 연속 실패 3회까지 더하면 알림은 고장 뒤 약 7분에 나간다.

`daily_report`는 보고 서비스가 실제로 남기는 흔적만 읽는다. timer의 `LastTriggerUSec`는 예약이 실행됐는지, `failstop-reporting-daily.service`의 `Result`는 일일 작업 등록이 성공했는지, `report_last_daily_sent_timestamp_seconds`는 Teams가 일일 보고를 접수했는지를 보여 준다([보고 서비스](../../reporting/reporter.py)). 09:00 직후의 오탐을 막으려고 예약 실행은 10분, 전송은 30분이 지난 뒤부터 오늘 기준으로 판정한다. `market_bot`은 [용량 점검](../bot/capacity_guard.py)이 봇을 멈추고 로그만 남기는 경우를 Teams로 드러낸다. 운영자가 의도적으로 봇을 멈춘 경우에도 알리므로 그때는 설정에서 이 점검을 끈다. 용량 점검은 봇을 멈추거나 수집 누락을 기록해도 정상 종료하므로, 카드의 `exchange-bot-capacity.service 결과`는 봇을 멈춘 경우에도 `success`다. `exit-code` 등은 용량 점검 스크립트가 판정 기록이나 봇 중지를 끝내지 못한 경우만 뜻한다. 용량 점검이 봇을 멈췄는지는 bot-1의 `/var/lib/exchange-bot-guard/state.json`에서 `stop`과 `reasons`를, `journalctl -u exchange-bot-capacity`에서 봇 중지 문구를 확인해 사람의 중지와 가른다.

봇과 보고 서비스가 멈추면 이 워치독과 Alertmanager 경보가 함께 울린다. 이 겹침은 의도한 것이다. 워치독은 Alertmanager 경로가 죽었을 때를 위한 별도 경로라서, 그 경로가 살아 있을 때도 같은 장애를 따로 알린다. 봇이 멈추면 [학교 경보 규칙](../reporting/school-alerts.yml)의 `MarketBotNotActive`(5분 지속, Alertmanager 반복 4시간)와 `market_bot`(연속 3회 실패, 1시간마다 다시 알림)이 서로 다른 카드로 나간다. 보고 서비스가 멈추면 `OperationsReporterNotActive`, app-1의 `reporting`, bot-1의 `report_queue`와 `daily_report`가 함께 발생할 수 있다. 계획한 봇 중지 때는 `market_bot`을 끄고 Alertmanager 침묵(silence)을 함께 건다. 겹침을 없애려면 bot-1 설정에서 `market_bot`을 끄고 Prometheus 규칙에 맡기는 방법이 있으나, 그러면 용량 점검 결과를 함께 적는 카드가 사라지므로 정하지 않았다.

## 알림 방식

1분 timer가 한 번 실행될 때마다 모든 점검을 순서대로 수행한다. 한 점검이 연속 3회 실패하면 이상 알림을, 이상 상태에서 연속 2회 성공하면 회복 알림을 보낸다. 이상이 계속되면 1시간마다 한 번 다시 알린다. 같은 실행에서 바뀐 점검은 카드 하나로 묶고, 한 대상의 모든 점검이 응답 없음으로 실패하면 EC2 상태 검사와 SG, 네트워크 경로를 확인하라는 문장을 붙인다. 카드에는 알림 번호와 "Alertmanager와 별도 경로 · 자동 조치 없음"을 적는다. 워치독은 서비스 재시작이나 설정 변경 같은 조치를 하지 않는다.

상태와 대기 중인 알림은 `/var/lib/failstop-watchdog/state.json`에 저장한다. 같은 폴더의 임시 파일에 쓰고 fsync한 뒤 이름을 바꾸므로 중간에 멈춰도 이전 상태나 새 상태 중 하나가 남는다. 전송을 시작하기 직전에 전송 중 표시를 먼저 저장한다. 그 사이 프로세스가 종료되면 다음 실행은 그 알림을 Teams가 받았을 수도 있는 알림으로 센다.

Teams 전송 결과는 중복 위험에 따라 다르게 처리한다. 연결 전에 실패했거나 408, 429, 503이면 요청이 접수되지 않은 것으로 보고 다음 실행에서 다시 보내며, 약 30분(30회) 동안 실패하면 버린다. 요청을 보낸 뒤 응답을 받지 못했거나 그 밖의 5xx를 받으면 이미 접수됐을 수 있으므로 한 번만 다시 보내고, 그 카드에는 같은 알림 번호와 재전송 표시를 붙인다. 전송 중에 프로세스가 끊긴 경우도 불확실 한 번으로 센다. 그래서 재전송 도중 끊겨 불확실이 두 번이 된 알림이나 시도 한도에 이른 알림은 다음 실행에서 보내지 않고 버린다. 그 밖의 4xx와 리다이렉트는 설정 문제로 보고 버린다. 알림을 버렸거나 거절됐으면 서비스는 종료 코드 2로 끝나고, 웹훅 비밀을 읽지 못하면 알림을 보관한 채 종료 코드 1로 끝난다. 이는 [보고 서비스의 재시도 정책](../../reporting/README.md)과 별개다.

웹훅 URL은 root:root 0600 파일 `/etc/failstop-watchdog/teams-webhook`에 한 줄로 둔다. 서비스는 전용 계정 `failstop-watchdog`로 실행하고 systemd `LoadCredential=`이 이 실행에만 읽기 전용 사본을 건넨다. 프로그램은 운영자가 관리하는 원본이 링크가 아닌 일반 파일인지, 소유자가 root 또는 실행 계정인지, 그룹과 다른 사용자에게 권한이 없는지 매 실행 확인하며, 호스트가 `.logic.azure.com` 또는 `.api.powerplatform.com`인 HTTPS 443 주소만 받는다. bot-1의 보고 토큰은 기존 `/etc/failstop/reporter-token`(root:failstop-report 0640)을 같은 방식으로 받고 원본의 그룹 읽기만 허용한다. URL과 토큰은 로그, 카드, 상태 파일에 쓰지 않는다. 저장소에는 비밀 값이나 자리표시자 파일을 두지 않는다.

systemd 사본의 권한은 원본과 무관하게 systemd가 정한다. 사본이 ramfs에 있으면 실행 계정 소유 0400이 되고, ACL을 지원하는 tmpfs에 있으면 root 소유 0400에 실행 계정 읽기 ACL이 붙어 `stat`의 그룹 자리에 ACL mask가 보여 0440이 된다. 그래서 사본에는 소유자와 일반 파일 여부를 확인하고 그룹 읽기는 허용하며 쓰기와 다른 사용자 권한만 거부한다. 알려진 동작으로는 systemd 254 이상에서 커널이 `noswap`을 지원하면 tmpfs를 쓰고, Amazon Linux 2023 기본 systemd 252는 ramfs를 먼저 시도한다. 두 경우 모두 이 규칙으로 읽을 수 있지만 학교 서버의 systemd 버전(`systemctl --version`)과 실제 사본 권한은 아직 확인하지 않았고, 10월 개인 계정에서 배포판이나 커널이 바뀌면 경로가 달라질 수 있다. 아래 `enable`이 서비스 계정으로 한 번 실행하므로 사본을 읽지 못하면 그 단계에서 멈춘다.

각 실행은 `/var/lib/failstop-watchdog/textfile/failstop_watchdog.prom`에 마지막 실행 시각, 점검별 성공 여부, 이상 상태, 대기 알림 수를 쓴다. node_exporter의 textfile 수집기가 이 폴더를 읽게 하면 bot-1의 Prometheus가 [점검 중단 규칙](checker-rules.yml)으로 워치독 자체의 멈춤을 알린다. app-1의 결과는 기존 `linux-host` 수집 경로로 들어온다.

## 감지하지 못하는 것

app-1과 bot-1이 함께 멈추면 알릴 주체가 남지 않는다. 같은 학교 계정, 서울 리전, VPC에 동시에 생긴 장애나 계정 정지도 마찬가지다. 인터넷 밖의 관점에서 사이트를 보는 프로브는 이 폴더에 없다. 운영자 PC나 10월 개인 계정처럼 다른 계정·위치에서 도는 점검이 있어야 이 경우를 잡을 수 있다.

Teams나 Workflows가 장애이거나 웹훅이 삭제되면 Alertmanager와 두 워치독의 알림이 모두 막힌다. 워치독은 결과를 journal, 종료 코드, 상태 파일에만 남긴다. HTTP 2xx는 워크플로가 요청을 접수했다는 뜻이며 채널 게시 성공은 Workflows 실행 이력에서 따로 확인한다.

bot-1의 인터넷 출구만 막히면 bot-1의 Alertmanager와 로컬 워치독은 Teams에 닿지 못한다. 반면 app-1은 사설망으로 bot-1이 정상이라고 판단하므로 이 경우는 알림 없이 지나간다.

app-1은 bot-1의 Prometheus와 Alertmanager 내부 상태를 보지 못한다. bot-1이 살아 있는 동안에는 로컬 워치독이 이를 확인하지만 bot-1 전체가 멈추면 app-1이 Grafana와 보고 서비스의 무응답으로만 판단한다. Watchdog 확인은 Prometheus에서 Alertmanager까지의 경로를 증명할 뿐이며, Watchdog은 `watchdog-null`로 보내므로 Alertmanager에서 Teams로 가는 전송은 증명하지 않는다.

`public_site`는 bot-1이 공개 주소로 app-1을 호출하는 같은 VPC 안의 다른 호스트 관점이다. 인터넷 사용자의 경로와는 다르다. app-1이 자기 도메인을 호출하는 점검은 외부 관점이 아니고 app-1이 멈추면 함께 멈추므로 app-1 설정에 넣지 않았다.

`daily_report`는 마지막 일일 보고 전송 시각 하나로 판정한다. 전날 보고가 재시도 끝에 오늘 09:00 뒤에 전송되면 오늘 보고의 누락을 가릴 수 있다. `report_queue`의 dead 수는 누적값이므로 이미 확인한 전송 포기 건수를 `max_dead`에 적어야 계속 이상으로 남지 않는다.

## 설치 절차

아래 명령은 Amazon Linux 2023 기준이며 아직 실행하지 않았다. 실행할 때는 [증거본](../../../증거본/README.md) 규칙에 따라 명령과 출력을 남기고 웹훅을 입력하는 단계는 캡처하지 않는다.

먼저 워치독 전용 Teams Workflows 웹훅을 만드는 것을 권장한다. 인터넷에 열린 app-1에 두는 사본이 새면 이 웹훅만 폐기할 수 있다. 두 호스트에서 각각 아래처럼 비밀 파일을 만든다. 입력한 URL은 화면에 표시되지 않는다.

```bash
sudo install -d -o root -g root -m 0755 /etc/failstop-watchdog
sudo bash -c 'umask 077; IFS= read -rs url; printf "%s\n" "$url" > /etc/failstop-watchdog/teams-webhook'
sudo chown root:root /etc/failstop-watchdog/teams-webhook
sudo chmod 0600 /etc/failstop-watchdog/teams-webhook
```

이 폴더를 각 호스트의 `/tmp/failstop-watchdog`로 옮긴 뒤 설치기를 실행한다. 설치기는 전용 계정, 프로그램, 설정, systemd unit을 배치하고 점검 결과를 한 번 출력한다. 상태 파일과 Teams는 건드리지 않으며 timer도 켜지 않는다. 설정 파일이 이미 있으면 덮어쓰지 않고 `config.json.new`에 새 기본값을 둔다. 이 단계의 점검 실패는 설치를 막지 않으며, 아래 준비를 마친 뒤 `enable`에서 다시 판정한다.

```bash
sudo bash /tmp/failstop-watchdog/install-watchdog.sh install app-1   # bot-1에서는 bot-1
```

bot-1은 timer를 켜기 전에 경보 경로의 Watchdog부터 만든다. `alert_pipeline` 점검은 Alertmanager에서 Watchdog 경보를 찾으므로, 규칙 없이 timer를 켜면 세 번째 실행인 약 2분 뒤 실제 Teams 채널에 "관측 경로 이상 감지"가 나가고 그 뒤 1시간마다 다시 알린다. 먼저 Alertmanager에 Watchdog만 받는 빈 경로가 있어야 한다. 이 경로가 없는 상태에서 Watchdog 규칙을 불러오면 기본 경로가 Watchdog을 Teams와 보고 서비스로 보낸다. 저장소의 [학교 Alertmanager 설정](../reporting/alertmanager.school.yml)에는 2026-09-23부터 `routes` 맨 앞에 아래 경로가, `receivers`에 빈 `watchdog-null`이 들어 있다. 서버의 `/etc/failstop/alertmanager.yml`이 이 판이 아니면 날짜 붙은 이름으로 백업하고, `diff`로 이 경로와 억제 규칙 외에 다른 차이(수신처 주소, 비밀 파일 경로)가 없는지 본 뒤 저장소 판으로 바꾼다. `amtool check-config`와 아래 경로 시험으로 Watchdog은 `watchdog-null`로만, 다른 경보는 기존 두 수신처로 가는지 확인한 뒤 Alertmanager를 다시 읽힌다.

```bash
sudo /usr/local/bin/amtool check-config /etc/failstop/alertmanager.yml
/usr/local/bin/amtool config routes test --config.file=/etc/failstop/alertmanager.yml alertname=Watchdog            # watchdog-null
/usr/local/bin/amtool config routes test --config.file=/etc/failstop/alertmanager.yml alertname=LinuxHostUnreachable # teams,evidence-reporter
sudo systemctl kill -s HUP alertmanager.service
```

```yaml
  routes:
    - matchers: [alertname="Watchdog"]
      receiver: watchdog-null
    - receiver: teams
      continue: true
    - receiver: evidence-reporter
receivers:
  - name: watchdog-null
```

그다음 `watchdog-rules.yml`을 `/etc/prometheus/watchdog-rules.yml`(root:root 0644)에 둔다. 저장소의 [prometheus.yml](../observability/prometheus.yml)은 이 경로를 `rule_files`에 이미 적어 두었다. 나열한 파일이 하나라도 없으면 `promtool check config`가 실패하므로, 서버의 `/etc/prometheus/prometheus.yml`에는 이 파일을 둘 때 이 줄만 더하고 `checker-rules.yml` 줄은 아래 마지막 단계에서 더한다. `promtool check config`가 통과하면 Prometheus를 다시 읽힌다. 1분쯤 뒤 아래 명령이 `customer="exchange"`, `environment="aws"`인 Watchdog 한 건을 돌려주면 준비가 끝난 것이다. 같은 시각 Teams 채널과 보고 서비스 로그에 Watchdog이 없어야 한다.

```bash
sudo install -o root -g root -m 0644 /tmp/failstop-watchdog/watchdog-rules.yml /etc/prometheus/watchdog-rules.yml
sudo /usr/local/bin/promtool check config /etc/prometheus/prometheus.yml
sudo systemctl kill -s HUP prometheus.service
curl -fsS 'http://127.0.0.1:9093/api/v2/alerts?filter=alertname%3D%22Watchdog%22'
```

Prometheus와 Alertmanager 변경을 아직 승인받지 못했으면 bot-1의 `/etc/failstop-watchdog/config.json`에서 `alert_pipeline`의 `enabled`를 `false`로 바꾸고 `note`에 이유를 적는다. 그동안은 데드맨 스위치가 꺼져 있어 Prometheus의 규칙 평가 중단이나 Alertmanager 수신 중단을 이 워치독이 잡지 못한다. 규칙을 적용하고 위 명령으로 Watchdog을 확인한 뒤 `true`로 되돌리고 아래 `enable`을 다시 실행한다.

bot-1에서는 보고 서비스의 현재 dead 수도 확인하고, 이미 검토한 건수를 `/etc/failstop-watchdog/config.json`의 `max_dead`에 적는다.

```bash
sudo -u failstop-report /opt/exchange-report-runtime/bin/python /opt/failstop/deploy/reporting/reporter.py status \
  --config /etc/failstop/reporting.json --state /var/lib/failstop-reporting/outbox.sqlite3
```

두 호스트에서 Teams 연결을 시험한다. `test-send`는 실제 카드 한 장을 보내므로 보낼 채널을 확인한 뒤 실행한다.

```bash
sudo python3 /opt/failstop-watchdog/failstop_watchdog.py test-send
```

마지막으로 `enable`로 timer를 켠다. `enable`은 활성 점검을 모두 한 번 실행하고, 하나라도 실패하면 timer를 켜지 않고 멈춘다. 켜는 순간 실패하고 있는 점검은 약 2분 뒤 실제 이상 알림이 되기 때문이다. 운영자가 일부러 멈춘 시장 봇이나, 보고 서비스를 막 켜서 아직 09:00 일일 보고를 한 번도 보내지 않은 `daily_report`처럼 의도한 실패라면 그 점검의 `enabled`를 `false`로 바꾸고 `note`에 이유를 적은 뒤 다시 실행한다. 모든 점검이 통과하면 서비스 계정과 `LoadCredential=` 사본으로 한 번 실행해 비밀 전달과 상태 파일 쓰기를 확인하고, 그 실행이 성공할 때만 timer를 켠다.

```bash
sudo bash /tmp/failstop-watchdog/install-watchdog.sh enable
journalctl -u failstop-watchdog.service -n 20 --no-pager
sudo -u failstop-watchdog python3 /opt/failstop-watchdog/failstop_watchdog.py status
```

node_exporter와 점검 중단 규칙은 두 호스트의 timer가 결과 파일을 만든 뒤에 붙인다. 저장소의 node_exporter 유닛([bot-1용](../observability/node_exporter.service), [app·DB용](../observability/node_exporter-host.service))은 textfile 수집기를 켜 두고 디렉터리를 `/etc/node_exporter/textfile.env`에서 받는다. 이 파일이 없으면 textfile을 읽지 않는다. 두 호스트의 유닛이 저장소 판인지 확인하고, [node_exporter-textfile.env.watchdog](../observability/node_exporter-textfile.env.watchdog)을 그 이름으로 둔 뒤 node_exporter를 재시작한다. 디렉터리는 `install`이 만들었으므로 수집 오류가 나지 않는다. 같은 유닛은 `failstop-watchdog.service`의 상태도 수집하므로, 웹훅 비밀을 읽지 못하거나 알림을 버린 실행은 [학교 경보 규칙](../reporting/school-alerts.yml)의 `LinuxServiceFailed`로 Alertmanager를 거쳐 알려진다.

```bash
sudo install -o root -g root -m 0644 /tmp/node_exporter-textfile.env.watchdog /etc/node_exporter/textfile.env
sudo systemctl restart node_exporter
curl -s http://127.0.0.1:9100/metrics | grep -E 'failstop_watchdog_last_run|node_textfile_scrape_error'   # app-1은 사설 주소:9100
```

bot-1 Prometheus에서 `failstop_watchdog_last_run_timestamp_seconds`가 app-1과 bot-1 두 노드에서 조회되면 `checker-rules.yml`을 `/etc/prometheus/checker-rules.yml`에 두고 `rule_files`에 그 줄을 더한 뒤 같은 방식으로 불러온다. 순서를 바꾸면 `WatchdogCheckerMissing`이 10분 뒤 발화한다. 백업 규칙([backup-alerts.yml](../postgresql/backup/backup-alerts.yml))까지 적용했다면 이 단계에서 서버의 `rule_files`는 저장소 [prometheus.yml](../observability/prometheus.yml)과 같은 네 줄이 된다.

## 확인

```bash
systemctl list-timers failstop-watchdog.timer --no-pager
journalctl -u failstop-watchdog.service --since '-1h' --no-pager
sudo -u failstop-watchdog python3 /opt/failstop-watchdog/failstop_watchdog.py status
cat /var/lib/failstop-watchdog/textfile/failstop_watchdog.prom
curl -fsS 'http://127.0.0.1:9093/api/v2/alerts?filter=alertname%3D%22Watchdog%22'   # bot-1
```

`status`는 점검별 연속 실패 수, 이상 여부와 시작 시각, 대기 알림 수, 마지막 전송 결과를 보여 준다. 프로그램은 상태가 바뀌거나 전송을 시도했거나 비밀·설정 오류가 있을 때만 journal에 한 줄을 남긴다. systemd의 매분 시작·종료 기록은 별도로 남는다. `run` 명령을 root로 직접 실행하면 상태 파일의 소유자가 바뀌므로 수동 실행은 `systemctl start failstop-watchdog.service`로 한다.

## 해제

Prometheus에서 `checker-rules.yml`을 먼저 빼고 다시 읽힌다. 그다음 각 호스트에서 설치기로 timer와 unit, 결과 파일을 지운다. 설치기는 textfile 디렉터리를 남기므로 `/etc/node_exporter/textfile.env`를 그대로 두어도 수집 오류가 나지 않는다. 웹훅, 설정, 상태 파일은 남기므로 필요하면 운영자가 직접 지운다. 남긴 상태 파일에는 이전 이상 상태와 대기 알림이 들어 있으므로, 다시 설치할 때는 `status`로 확인하고 지난 사건이면 `enable` 전에 상태 파일을 지운다. Watchdog까지 없애려면 워치독을 해제한 뒤 `watchdog-rules.yml`을 빼고 마지막에 Alertmanager 경로를 제거한다. 워치독을 남긴 채 Watchdog 규칙만 빼야 하면 먼저 bot-1 설정에서 `alert_pipeline`을 끈다.

```bash
sudo bash /tmp/failstop-watchdog/install-watchdog.sh remove
```

## 저장소에서 확인한 범위

단위 시험은 상태 전이와 발화 임계, 재알림, 전송 대기 중 회복, Watchdog 부재 판정, Teams 응답 분류와 불확실 재전송 한도, 재전송 중 끊긴 알림을 세 번째로 보내지 않는 것, 비밀 파일 권한과 systemd 사본의 ACL mask 처리, 일일 보고 판정, 원자적 저장을 스텁으로 확인한다. 실제 웹훅은 호출하지 않는다.

```powershell
python -B -m unittest discover -s deploy/linux/tests -p test_failstop_watchdog.py -v
docker run --rm --entrypoint promtool -v "${PWD}/deploy/linux/watchdog:/watchdog:ro" -w /watchdog prom/prometheus:v3.13.0 test rules watchdog-rules.test.yml
```

2026-09-23 로컬 Docker에서 Prometheus 3.13.0, Alertmanager 0.34.0, Grafana 13.1.0을 잠시 띄워 확인했다. Watchdog이 위 경로로 `watchdog-null`에만 전달되고 기존 경보는 `teams`와 `evidence-reporter`로 그대로 가는 것을 `amtool config routes test`로 봤다. Prometheus를 멈추자 `endsAt`이 지난 뒤 `alert_pipeline`이 `watchdog_missing`으로 바뀌었고, Alertmanager를 멈추면 `network_error`가 됐다. Grafana는 하위 경로 설정에서 `/grafana/api/health`가 응답했다. 같은 날 `python:3.12-slim` 컨테이너에서 root와 일반 계정으로 단위 시험을 다시 실행했고, 가짜 `systemctl`로 `enable`이 실패한 점검, 서비스 실행 실패, 쓰기 권한이 열린 설정에서 timer를 켜지 않는 것을 확인했다. 같은 날 경로를 저장소 설정에 넣은 뒤 `amtool config routes test`로 Watchdog은 `watchdog-null`, 다른 경보는 `teams`와 `evidence-reporter`로 가는 것을 다시 확인했다. 외부로 나갈 수 없는 Docker 내부 망에서 이 판의 Alertmanager와 Watchdog 규칙만 띄워 Watchdog이 `watchdog-null`로만 접수되는 것도 봤다. [연결 시험](../tests/test_observability_wiring.py)은 규칙 파일 목록, node_exporter의 textfile 디렉터리와 수집 유닛, 점검 중단 규칙이 읽는 지표 이름이 이 폴더와 맞는지 고정한다. 학교 서버의 Python 3.9 실행, systemd unit 검증, `LoadCredential=` 사본의 실제 권한, node_exporter가 `textfile.env`로 디렉터리를 받는 동작, 실제 Teams 수신은 아직 확인하지 않았다. 범위와 결정은 [ADR 0027](../../../docs/reference/adr/0027-msp-operations-and-autoscaling.md)을 따른다.
