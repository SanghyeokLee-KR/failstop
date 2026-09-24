# 운영 보고 서비스

이 모듈은 GitHub `codex/docs-linux-operations`의 `6ee11791c79564b37c47ea30659373a856eb91c8`에서 가져왔다. 학교 배포는 Python 3.11 가상환경과 [학교 설정](../linux/reporting/config.school.json)을 사용하며, 실행 결과는 [로드맵](../../docs/ROADMAP.md)에서 확인한다. 설치 목록에 빠졌던 `console_api.py`를 추가했다.

거래 서버와 분리된 Python 3.10 이상 서비스다. Prometheus·Alertmanager와 제한된 운영 증거 파일을 읽고, 보고서를 SQLite에 저장한 뒤 Teams Workflows 웹훅에 Adaptive Card를 보낸다. Teams에는 대상·발생 또는 해제 시각·서비스 영향·담당자 조치·핵심 수치를 표시한다. 파일 제공을 켜면 같은 근거로 만든 2쪽 PDF와 HTML을 카드의 버튼으로 연다. 원시 JSON과 내부 수집 ID를 본문에 나열하지 않는다. 운영 명령, ASG 변경, DB 승격을 실행하는 기능은 없다. 범위는 [ADR 0027](../../docs/reference/adr/0027-msp-operations-and-autoscaling.md)을 따른다.

수집·Teams·Gemini 연결은 외부 Python 패키지가 필요 없다. PDF 생성에는 ReportLab과 한글 TTF 글꼴을 별도로 사용한다. 기본 장애 알림은 Alertmanager의 별도 Teams 경로를 유지해야 한다. 이 서비스나 AI의 실패가 기본 알림과 주문 처리에 영향을 주지 않게 배치한다. 실제 AWS 지표 수집, Linux 서비스 설치, Teams 채널 수신은 각 환경에서 확인해야 한다.

## 먼저 로컬에서 확인한다

저장소 루트에서 실행한다. Windows에서는 설치된 Python 실행 파일의 실제 경로를 사용한다.

```powershell
python deploy/reporting/reporter.py preview --config deploy/reporting/config.example.json --output artifacts/operations-20260918/report-preview.json
python deploy/reporting/reporter.py daily --config deploy/reporting/config.example.json --state artifacts/operations-20260918/report-outbox.sqlite3 --date 2026-09-18
python deploy/reporting/reporter.py status --config deploy/reporting/config.example.json --state artifacts/operations-20260918/report-outbox.sqlite3
python -m unittest discover -s deploy/reporting -p "test_*.py" -v
```

`preview`는 수집과 분석 및 카드 생성을 수행하지만 Teams로 보내지 않는다. `daily`는 해당 날짜의 09:00 Asia/Seoul까지 24시간을 조회하는 작업을 대기열에 넣으며 같은 날짜를 두 번 넣지 않는다. 날짜를 생략하고 09:00 이전에 실행하면 전날 보고를 생성한다. timer의 `Persistent=true`는 전원이 꺼진 동안 놓친 최근 실행을 보충한다. 장기간 중단된 모든 날짜를 자동으로 재생하지는 않는다.

## 입력과 분석 계약

[설정 예제](config.example.json)는 최대 12개의 PromQL을 갖는다. 일일 보고는 24시간, 사건 보고는 최근 15분을 조회한다. `range:true`이면 시계열별 마지막 값·최솟값·최댓값·개수와 실제 조회 표본의 `[시각, 값]` 목록을 함께 기록한다. 기본 조회 간격은 일일 720초·사건 60초다. 이 표본은 Prometheus의 원시 scrape 전체나 경보 지속 시간의 증명이 아니다. 시계열 50개·시계열별 200개 표본·HTTP 응답 256KiB를 넘으면 수집 오류로 기록한다. 지표 `ok`, 빈 결과 `missing`, 연결·응답 실패 `error`를 구분한다. NaN·무한대·중복 또는 역순 시각도 오류이며 정상 0으로 바꾸지 않는다. Alertmanager의 정상 빈 목록은 활성 알림 없음이라는 별도 사실이다.

`ok`는 수집 성공이며 서비스 정상 판정이 아니다. 사건의 `resolved`는 해당 경보의 해제이며 전체 복구·조치 완료를 뜻하지 않는다. 카드의 상태와 수치는 코드로 계산하고 AI의 요약·가설·권고를 별도 표시한다. 시간은 KST로 표시하며 오래된 표본, 없는 업무 요청·5xx·응답 histogram은 확인하지 못한 자료로 남긴다. DB 연결은 `exchange` DB의 인스턴스별 연결 수이며 시스템 DB의 0을 섞지 않는다. 업무 요청량에는 Actuator 점검 트래픽을 포함하지 않는다. Windows 공유 파일시스템의 inode 값은 보고에서 제외한다.

예제의 지표 조회는 `customer=exchange`와 `app|reader|data|standby|probe` 역할로 한정한다. 대상 가용성은 고객 Spring·Linux 호스트·DB·접속 검사만 집계하며 Prometheus와 보고 서비스 자체는 포함하지 않는다. 별도 Ubuntu 운영 실습은 고객 서버가 아니므로 제외한다. `host_file`은 기본 `null`이며 실제 고객 호스트의 진단 파일을 준비했을 때만 지정한다. 보고 프로세스가 실행되는 관측 서버의 파일을 고객 서버 근거로 대신 사용하지 않는다.

`alert_matchers`는 Alertmanager의 현재 경보 목록에 적용하는 필터다. 예제는 같은 고객사·역할의 경보를 조회하고 보고 도구의 경보를 제외한다. 고객 역할을 식별하지 못한 공통 경보는 기본 관측 알림 경로에서 확인한다. 인증된 사건 webhook의 발화·해제 원문은 사건의 근거로 보존하므로 이 필터는 webhook 수신 제어나 접근 권한을 대신하지 않는다. 다른 고객사나 여러 환경을 한 Prometheus에서 수집할 때는 모든 PromQL과 경보 필터를 해당 고객·환경으로 함께 바꾼다.

[환경 범위 설정](scope.py)와 `install.py --environment local-lab|aws`는 공통 쿼리에 고객사·환경 조건을 함께 적용한다. 설치기의 기본값은 `--customer exchange --environment local-lab`이다. 원자료에는 이 범위를 보존하고 Teams 카드 상단에도 고객사와 로컬 실습 또는 AWS를 표시한다. AWS 설치는 `--environment aws --bind <관측 노드 사설 IP>`와 실제 내부 관측 주소를 사용한다. Google 호출 모드는 자동으로 켜지지 않는다. 로컬·AWS의 outbox와 timer는 각 설치에 속하며, 이전 일일 보고 기록으로 새 환경의 보고를 대체하지 않는다. [학교 배포 안내](../../docs/reports/AWS_작업.md)를 함께 확인한다.

## 담당자 개입과 보고 양식

[개입 판정](intervention.py)은 수집 시점의 근거로 즉시 대응·담당자 조치·담당자 확인·관찰 유지를 구분한다. 최신 중대 경보, 대상 응답 실패, 서비스 실패는 우선 대응 대상으로 두고, 디스크 여유 부족 같은 잔여 위험은 해당 경보가 해제돼도 조치 과제로 남긴다. 필수 자료가 없거나 오래됐으면 관찰 유지로 낮추지 않는다. AI의 문장은 이 등급을 바꾸지 않는다. 이 구분은 대응 권고이며 조직의 장애 등급·SLA나 실제 작업 배정을 대신하지 않는다.

조치표에는 권고 담당 역할·착수 시점·해야 할 일·완료 조건·근거를 바인딩한다. 백업은 성공 시각만으로 종결하지 않고 예약 실행과 해당 백업의 복원 확인도 대조한다. 담당자의 실제 처리·승인 기록이 없으면 완료나 승인으로 표시하지 않는다. 과거 보고를 다시 열 때도 보고에 적힌 수집 시점의 판정이라는 점을 유지한다.

[공통 표시 모델](presentation.py)을 Teams·HTML·PDF가 함께 사용한다. PDF는 2쪽 고정 양식으로 첫 장에 결론·사건 경과·영향·조치표, 둘째 장에 그래프·지표표·자료 공백·종결 조건을 담는다. 단순히 여백을 채우기 위한 원자료 나열이나 반복 설명은 넣지 않는다. 담당자 이름·검토 시각·실제 수행 결과는 근거가 있을 때만 기록한다.

사건 제목은 수집 조건에 맞춰 구분한다. `DatabaseConnectionFailed`는 수집기가 응답하면서 `pg_up=0`인 상태이므로 역할에 따라 `Standby DB 접속 장애 발생` 또는 `Primary DB 접속 장애 발생`으로 표시한다. 수집기 응답이나 필수 지표를 확보하지 못한 `DatabaseExporterUnavailable`은 `DB 수집 경로 장애 발생`으로 표시한다. 두 경보 모두 EC2나 운영체제 전체의 중단을 증명하지 않는다. 해제 보고에는 발생·해제 시각과 접속·복제·요청 경로의 재확인 조건을 남긴다.

[그래프](charts.py)는 저장한 조회 표본으로만 그린다. 수집 공백에서는 선을 끊고 조회 구간 전체를 표시한다. 표본 시계열을 저장하지 않은 예전 보고는 마지막 값의 막대로 구분하며 추세선을 만들어 붙이지 않는다. 파일시스템·DB는 대상별로 구분하고, 응답 대상 수는 모든 대상의 표본이 있는 동일 시각만 합산한다. 시계열은 저장 보고와 그래프에 사용하며 AI·Teams 본문에는 전체 점 목록을 보내지 않는다.

DB 사건의 그래프는 Spring 요청 처리량·DB 연결·WAL 전송과 재생 위치 차이·수집 대상 응답을 우선 표시한다. Spring 사건에는 처리량·P95·5xx·대상 응답을 사용한다. WAL 위치 차이를 유실량으로 바꾸거나, 수집 대상 응답 수를 업무 정상 수치로 해석하지 않는다.

`evidence_root` 아래의 파일만 읽는다. 절대 경로나 심볼릭 링크가 이 경계를 벗어나면 거부한다. 파일은 64KiB 이하로 제한한다.

| 입력 | 형식과 허용 범위 |
|---|---|
| `host_file` | `schema_version:1`, `collected_at`, `status`, `sources`를 가진 JSON. sources는 filesystems, inodes, failed_services, service_states, disk_io, network, journal_usage만 읽고 각 항목의 ok/output/error를 보존한다. |
| `journal_file` | 운영자가 수집·정제한 JSONL 최대 50행. `__REALTIME_TIMESTAMP`, `_SYSTEMD_UNIT`, `PRIORITY`, `MESSAGE`만 읽는다. 기본 null로 비활성화한다. |
| `aws_activity_file` | 운영자가 추출한 ASG 활동 JSONL 최대 50행. ActivityId, StartTime, EndTime, StatusCode, Description, Cause, Progress만 읽는다. 기본 null로 비활성화한다. |
| `backup_schedule_file` | `schema_version:1`, `records`를 가진 선택적 확인 JSON. enabled 불리언·checked_at·next_run_at만 읽는다. 활성 여부와 실제 다음 예약을 확인한 뒤 기록한다. |
| `backup_restore_file` | 같은 형식의 선택적 확인 JSON. verified 불리언·backup_id·verified_at만 읽는다. 해당 백업을 격리 환경에서 복원한 결과를 기록한다. |

파일 입력의 시간 범위는 수집자가 보고 시간대에 맞춰 준비한다. 파일 내용이 현재 상황이라는 보장은 없으므로 host의 `collected_at`, journal의 시간, AWS 활동 시간을 함께 대조한다. 백업 성공 시각은 `failstop_backup_last_success_timestamp_seconds`이며 복원 검증 완료를 뜻하지 않는다.

백업 확인 파일은 기본 `null`이며 없어도 보고는 생성된다. 없는 확인 결과를 성공으로 채우지 않는다. UTC 또는 시간대가 포함된 ISO 시각을 사용하고, 운영자가 확인한 두 파일을 보호된 `evidence_root` 아래에 둔 뒤 설정에는 상대 경로를 넣는다. 보고 계정에는 읽기 권한만 준다. AI 응답을 확인 파일로 사용하지 않으며 파일 입력은 백업 실행·복원을 대신하지 않는다.

허용 필드 안에서도 비밀 키 이름, URL, Bearer·Basic 인증, 일반적인 password/token/key 표현, 이메일·IPv4·AWS 키·JWT를 제거한다. 임의의 개인정보를 완전히 알아내는 필터는 아니다. 서비스 계정에 운영 진단 파일만 읽도록 권한을 주고, 앱 원문 로그·개인정보·사용자 데이터 파일은 증거 디렉터리에 복사하지 않는다. 전체 system journal이나 AWS 자격증명을 자동 수집하지 않는다.

기본 예제는 `mock`이다. `facts-only`는 수집 사실만 정리하며, `gemini`는 Google Gemini API를 직접 호출한다. 별도 분석 서버를 사용하는 기존 `remote`도 유지한다. `remote`에는 `REPORT_AI_URL`, `REPORT_AI_ALLOWED_HOSTS`, `REPORT_AI_KEY`가 필요하며 아래 계약의 분석 엔드포인트를 지정한다.

## 저비용 Gemini 연결

권장 모델은 `gemini-3.1-flash-lite`, 최소 비용 선택은 `gemini-2.5-flash-lite`다. `ai_model`을 명시하며 `latest`나 다른 모델로 자동 전환하지 않는다. [Gemini 모듈](gemini.py)은 고정 Google HTTPS 주소와 `x-goog-api-key` 헤더를 사용한다. 키는 `REPORT_AI_KEY_FILE`의 보호 파일 또는 `GOOGLE_API_KEY`·`GEMINI_API_KEY`에서 읽는다. 새 AQ 인증 키도 지원하며 URL·명령 인수·보고서에 키를 넣지 않는다.

2026-09-18에 확인한 표준 텍스트 가격은 다음과 같다. 금액은 USD이며 무료 적용 여부·세금·환율·계정의 실제 청구액은 포함하지 않는다. [공식 가격](https://ai.google.dev/gemini-api/docs/pricing)

| 모델 | 입력 100만 토큰 | 출력 100만 토큰 | 입력 5,000·출력 1,000토큰인 보고 1,000건 |
|---|---|---|---|
| Gemini 2.5 Flash-Lite | $0.10 | $0.40 | 약 $0.90 |
| Gemini 3.1 Flash-Lite | $0.25 | $1.50 | 약 $2.75 |

무료 사용 한도는 모델·프로젝트별로 AI Studio에서 확인한다. 이 서비스가 무료 요금제를 선택하거나 유료 청구를 막아 주지는 않는다. 무료 구간의 입력·응답은 Google 제품 개선에 사용될 수 있으므로 실고객의 기밀·개인정보를 보내지 않는다. 실제 고객 운영에는 유료 구간의 데이터 처리 조건을 검토한다. [사용량 제한](https://ai.google.dev/gemini-api/docs/rate-limits), [데이터 사용 조건](https://ai.google.dev/gemini-api/terms)

기본 한도는 입력 6,000·출력 1,200토큰, 분당 분석 시도 4회·KST 하루 50회·달력월 1,000회다. 생성 전에 `countTokens`로 입력 상한을 검사하고 생성은 한 번만 요청한다. 정상 분석 시도 하나에 토큰 조회와 생성 HTTP 요청이 각각 한 번 필요하다. SQLite의 원자적 예약으로 동시 실행·재시작 뒤에도 호출 횟수를 유지하며 실패 시도도 한도에 포함한다. Teams 재전송은 저장한 분석을 재사용한다. 한도를 넘거나 AI가 실패하면 기본 경보와 사실 보고를 계속 보낸다.

3.1 Flash-Lite와 이 토큰 상한·월 1,000회가 유지될 때 생성 토큰비용의 계산상 상한은 월 $3.30이다. 같은 키를 사용하는 다른 프로그램, 설정 변경과 가격 변경은 이 제한 밖이다. 출력 상한에는 모델의 thinking 토큰도 포함된다. `status`의 `ai_usage`와 SQLite `ai_calls`에서 시도 수·완료 수·사용 토큰을 확인한다. [출력 토큰 제한](https://ai.google.dev/gemini-api/docs/generate-content/thinking#token-limits-and-max_output_tokens)

```json
{
  "summary": "운영자가 확인할 요약",
  "observations": [{"text": "관측 해석", "evidence": ["target_up"]}],
  "hypotheses": [{"text": "확정되지 않은 원인 가설", "evidence": ["host"]}],
  "next_checks": [{"text": "운영자가 확인할 항목", "evidence": ["alerts"]}]
}
```

원격 분석 요청은 고정 지시와 `untrusted_evidence`를 구분한다. Gemini는 지표의 단위·최악값·대표 대상·누락 사유·사건 전환을 남기고 입력을 압축한다. 응답은 위 키만 허용하며 요약 600자, 항목당 400자·항목별 8개로 제한한다. 모든 항목은 실제 fact ID를 인용해야 한다. 도구 호출이나 임의 필드는 거부하고, AI 실패·시간 초과·형식 오류는 사실 보고로 대체한다. 로그 문장을 지시로 취급하지 않도록 분리하며 카드에는 텍스트만 넣는다. 인용 ID와 형식 검사가 해석의 진실성을 증명하지는 않으므로 실제 AI의 해석과 가설은 운영자가 확인한다.

## Teams 비밀과 전송

`TEAMS_WEBHOOK_URL` 환경 변수 또는 `--secret-file`/`REPORT_TEAMS_SECRET_FILE` 파일을 사용한다. 파일은 URL 하나만 적힌 형태와 `TEAMS_WEBHOOK_URL=...` 형태를 지원한다. 파일을 셸에서 source하거나 명령으로 평가하지 않는다. `REPORT_TEAMS_ALLOWED_HOSTS`에는 웹훅의 **정확한 호스트 이름**을 설정한다. Teams는 HTTPS 443과 `.logic.azure.com` 또는 `.api.powerplatform.com` 하위 호스트만 허용한다. 임의 도메인·리다이렉트·URL 내 사용자 정보는 거부한다. 호스트·경로·서명 URL과 원격 오류 본문은 로그에 남기지 않는다.

Workflows가 webhook trigger와 Adaptive Card 게시 단계를 갖고 있어야 한다. HTTP 2xx는 웹훅이 접수했다는 증거이며 채널 게시 성공을 보장하지 않는다. 워크플로 실행 이력과 채널에서 실제 수신을 확인한다. 워크플로 소유자·공동 소유자와 인증 방식도 운영자가 관리한다. 형식은 [Microsoft Workflows 안내](https://learn.microsoft.com/en-us/microsoftteams/platform/webhooks-and-connectors/how-to/add-incoming-webhook), 조회 계약은 [Prometheus HTTP API](https://prometheus.io/docs/prometheus/latest/querying/api/)를 따른다.

아래 명령은 실제 메시지를 보낸다. URL을 CLI 인수나 출력에 적지 않는다.

```powershell
python deploy/reporting/reporter.py test-send --config deploy/reporting/config.example.json --state artifacts/operations-20260918/report-outbox.sqlite3 --secret-file .env
python deploy/reporting/reporter.py drain --config deploy/reporting/config.example.json --state artifacts/operations-20260918/report-outbox.sqlite3 --secret-file .env
```

`test-send`는 운영 자료를 포함하지 않는 모의 연결 시험 1건을 영속 저장 후 보낸다. `drain`은 준비된 작업을 최대 100개 처리하며 재시도 예정 시각까지 기다리지 않고 종료한다. 상시 `serve` 작업자가 이후 재시도를 처리한다.

실제 전송은 `REPORT_DELIVERY_ENABLED=true`를 명시해야 한다. 기본 false에서는 inbox·health·metrics와 일일 대기열 등록은 유지하고 worker 송신을 멈춘다. test-send·drain도 이 값이 false이면 종료코드 2로 멈춘다. 승인한 수신 대상과 정제한 보고 내용을 확인한 뒤 전송을 켠다. 중지 기간에 쌓인 보고도 재개 후 전송 대상이므로 outbox를 먼저 확인한다.

## Teams에서 PDF와 HTML을 연다

[파일 제공 모듈](artifacts.py)은 송신 전에 저장된 보고를 PDF와 HTML로 만들고 카드에 `PDF 보고서`, `HTML 보고서` 버튼을 붙인다. 새 지표 조회나 AI 분석 없이 같은 보고 ID의 근거를 사용한다. PDF 버튼은 파일 내려받기, HTML 버튼은 브라우저 열기다. Teams의 파일 탭에 파일을 등록하는 기능은 구현하지 않았다. 채널 파일 첨부가 필요하면 Microsoft 365 저장소와 Workflows의 파일 생성·게시 절차를 별도로 연결해야 한다.

Ubuntu에서는 보고 서비스를 실행하는 Python에 ReportLab을 설치하고 한글 글꼴을 준비한다. 다음은 시스템 Python과 Nanum 글꼴을 사용하는 설치 예다. Rocky Linux 등 다른 배포판에서는 해당 Python 환경의 ReportLab과 실제 TTF 경로를 지정한다.

```bash
sudo apt-get install python3-reportlab fonts-nanum
sudo python3 deploy/reporting/install.py --apply --secret-file /root/teams.env \
  --analyzer facts-only --enable-delivery --environment local-lab \
  --artifact-base-url http://127.0.0.1:59188/reporter
```

위 주소는 Docker Desktop·WSL 실습의 루프백 bridge 경로다. 보고를 여는 PC에서 그 bridge가 동작해야 하며, 다른 PC의 관계자나 Teams 모바일 사용자는 이 로컬 주소로 파일을 받을 수 없다. AWS에서는 수신자가 접근할 수 있는 HTTPS 주소를 `--artifact-base-url`로 지정하고, `/reports/` 하위 요청을 보고 서비스로 전달하는 TLS 프록시를 구성한다. 경로 접두어를 지정했다면 프록시에서 해당 접두어를 제거한다. AWS 도메인·인증서·프록시는 이 옵션이 자동 생성하지 않는다.

설치기는 `/var/lib/failstop-reporting/artifacts`에 파일을 보관하고 `/etc/failstop/report-artifact-key`에 별도 서명 키를 둔다. 키 파일은 root:failstop-report 0640으로 보호하며 `REPORT_ARTIFACT_KEY_FILE`로 읽는다. 재설치 때 기존 키를 유지한다. 설정의 `artifacts` 항목에는 `enabled`, `public_base_url`, `directory`, `font`, `font_bold`, `ttl_seconds`가 들어간다. URL 유효 기간은 기본 604800초, 즉 7일이며 300초부터 7일까지 지정할 수 있다.

서명은 보고 파일·형식·만료 시각에 묶인다. 서명 URL을 가진 사람은 만료 전까지 해당 파일을 읽을 수 있으며 사용자별 로그인 권한을 대신하지 않는다. URL을 외부에 전달하거나 프록시 접근 로그에 남기지 않는다. HTTPS 환경에서는 필요에 따라 조직의 접근 인증도 프록시에 적용한다. URL 만료는 파일 삭제가 아니며 보관 파일의 정리는 별도 보존 정책을 따른다. 파일 생성 실패 시 PDF 버튼을 붙이지 않고 생성 실패 안내와 요약 카드를 전송한다.

## 관제센터 AI 사건

관리 콘솔의 관제센터(관리 JVM)는 Teams를 직접 부르지 않는다. AI 자동 조치의 실행·되돌림, 모드 전환, 상한 도달, 관찰 실패, 결과 모름, 모델 응답 실패 연속, AI 정지·재개를 [사건 모듈](msp_events.py)이 정한 모양으로 `POST /msp/events`에 넘기고, 이 서비스가 일일·사건 보고와 같은 보관함·재시도·전송 기록으로 Teams에 보낸다. 모양은 관리 JVM의 `TeamsNotifier`와 짝이며 [관제센터 2단계 설계](../../docs/reference/architecture/MSP_관제센터_2단계_설계.md) 7-12절이 기준이다.

본문은 서버가 만든 값뿐이다. 사건 종류, 조치 이름과 대상, 전후 대수, 상태, 확신도, 관찰 창 끝, 모드와 전환 원인, 모델 이름, 근거 지표 최대 6개와 임계, 미확인 근거 수, 만료한 조치안 수와 모델 연속 실패 수만 받는다. 모르는 필드나 목록 밖의 값이 하나라도 있으면 400 `invalid_event`로 거부한다. 모델의 상황·이유 문장은 필드 자체가 없어 카드에 실릴 수 없다. 같은 사건 id는 한 작업이 되므로 관리 JVM이 응답을 잃고 다시 넘겨도 카드는 한 장이다.

작업자는 이 사건에 근거 수집이나 AI 분석을 붙이지 않고 PDF·HTML도 만들지 않는다. 카드는 머리의 고객·환경, 개입 등급과 제목, 대상과 발생 시각(KST), 영향, 담당자 조치, 후속 관측(근거 지표와 임계), 'AI' 표시를 붙인 참고 분석(모델과 확신도), 미확인 근거 수, '자동 조치 있음·없음' 기록 줄로 이뤄진다. 개입 등급은 관찰 실패와 결과 모름이 P1, 자동 되돌림이 P2, 사람이 켜고 끈 모드 전환·만료·재개가 P4, 나머지가 P3이다. 설정에 `msp_console_url`(https, 사용자 정보·질의·조각 없이 300자 이하)을 두면 '관제센터에서 확인' 버튼을 붙인다. 링크는 관리 JVM이 보낸 값이 아니라 이 설정에서만 만든다. 설정이 이 조건을 어기면 기동하지 않는다.

관제센터의 보고서 화면은 `GET /console/reports`로 목록을 읽고(사건은 `kind=msp`, 파일 링크 없음), 설정의 Teams 연동 상태는 `GET /console/status`로 작업 상태별 수, 전송 켬 여부, 마지막 전송과 마지막 시도 결과, 가장 오래된 대기 나이를 읽는다. 두 경로 모두 수집용 Bearer 토큰이 필요하다. 관리 JVM은 이 토큰을 파일 경로로만 받는다.

## Linux 서비스와 예약

`sudo python3 deploy/reporting/install.py --apply --secret-file /root/teams.env`로 전용 계정·코드·설정·비밀·systemd를 설치한다. 기본 bind는 루프백이며 실제 사설 주소를 쓰면 먼저 관측 출발지만 허용한다. 설치기는 raw URL과 dotenv 파일을 평가 없이 파싱하고 비밀을 제한된 권한으로 원자 교체한다. 기본은 모의 분석과 송신 비활성화이며 승인된 전송은 `--enable-delivery`를 명시한다. 내부 Prometheus·Alertmanager 주소는 `--prometheus-url`, `--alertmanager-url`로 지정한다. 기존 호스트 수집기가 있으면 drop-in으로 `--group failstop-report`를 유지해 매번 파일을 교체한 뒤에도 보고 계정이 읽을 수 있게 한다.

Gemini 설치는 위 명령에 `--analyzer gemini --ai-model gemini-3.1-flash-lite --ai-secret-file /root/google.env --enable-delivery`를 추가한다. 별도 키는 `/etc/failstop/gemini-key`에 root:failstop-report 0640으로 설치하며 환경 파일에는 이 경로만 둔다. 설명 문구와 Teams URL·Google 키를 함께 붙여 넣은 비밀 파일도 읽지만 셸 명령이나 지시로 실행하지 않는다. 저장소의 `.env`는 Git에서 제외하며 배포 입력에 비밀을 직접 넣지 않는다.

[환경 변수 예제](reporting.env.example)를 `/etc/failstop/reporting.env`, 설정을 `/etc/failstop/reporting.json`, 웹훅을 `/etc/failstop/teams-webhook`에 둔다. 환경·비밀 파일은 root:failstop-report, 0640으로 두고 전용 `failstop-report` 계정만 읽게 한다. 상태 디렉터리는 systemd가 `/var/lib/failstop-reporting`에 만들며 SQLite 보고와 대기열을 보존한다. 운영 진단 증거 디렉터리는 이 계정에 읽기만 허용한다.

[상시 서비스](systemd/failstop-reporting.service), [일일 서비스](systemd/failstop-reporting-daily.service), [일일 timer](systemd/failstop-reporting-daily.timer)를 `/etc/systemd/system/`에 설치한 뒤 실행한다. Linux 설치와 계정·방화벽 검증은 [AWS 작업 안내](../../docs/reports/AWS_작업.md)와 함께 수행한다.

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now failstop-reporting.service failstop-reporting-daily.timer
systemctl list-timers failstop-reporting-daily.timer
journalctl -u failstop-reporting.service -u failstop-reporting-daily.service --since today
```

기본 `REPORT_BIND=127.0.0.1`이다. Docker Alertmanager가 호스트로 접근해야 하면 실제 사설 인터페이스에 바인딩하고 호스트 방화벽에서 Docker/관측 노드의 출발지만 9088에 허용한다. 인터넷에 이 HTTP 포트를 열지 않는다. 전송 구간이 신뢰할 수 없는 네트워크라면 별도 TLS 종단을 구성한다. Docker Desktop·WSL 실습 연결은 기존 로컬 관측 환경의 제한된 루프백 bridge를 사용할 수 있다.

| 경로 | 계약 |
|---|---|
| `POST /alerts` | Alertmanager webhook JSON, `Authorization: Bearer <REPORT_INGEST_TOKEN>`, JSON Content-Type 및 Content-Length 필수. 64KiB·알림 32개 제한, 202 응답. 중복이면 duplicate=true. |
| `POST /msp/events` | 관제센터 AI 사건 JSON(위 절). `/alerts`와 같은 Bearer 인증·JSON·Content-Length·64KiB 제한, 202 응답. 같은 사건 id면 duplicate=true, 모양이 다르면 400 `invalid_event`. |
| `GET /console/reports?customer=&environment=&limit=` | 같은 Bearer 인증. 고객·환경 범위의 보고 목록(최대 100건)과 파일 서명 링크. private/no-store. |
| `GET /console/status` | 같은 Bearer 인증. 작업 상태별 수, `delivery_enabled`, 마지막 전송 시각, 마지막 시도의 시각·상태·정제한 오류 코드, 가장 오래된 대기 나이, 마지막 일일 전송. 보고 본문과 웹훅 주소는 싣지 않는다. |
| `GET /health` | SQLite 읽기 가능 여부만 200/503으로 반환한다. AI·Teams 전달 성공의 증거가 아니다. |
| `GET /metrics` | 같은 Bearer 인증. 상태별 report_jobs·report_artifacts, report_last_daily_sent_timestamp_seconds, report_oldest_pending_age_seconds를 반환한다. |
| `GET /reports/<파일 ID>/<만료 시각>/<서명>/report.pdf 또는 report.html` | 파일 제공 설정 시 사용. 보고별 서명·만료 검사, 파일당 8MiB 제한. 수집용 Bearer 대신 URL 서명으로 접근하며 private/no-store로 반환한다. |

토큰은 최소 32자다. 요청 처리 동시성은 16개, 소켓 비활성 제한은 5초이며 HTTP 접근 로그를 남기지 않는다. Alertmanager `authorization.credentials_file`과 Prometheus `authorization.credentials_file`에 같은 비밀 토큰 파일을 연결한다. report_jobs의 dead 증가, pending 연령, 마지막 daily 전송이 26시간 이상 오래됨을 기본 관제에서 알려야 한다. daily timestamp 0은 아직 전달된 보고가 없다는 뜻이다.

## 중복·실패·복구

사건 키는 알림의 labels·startsAt·firing/resolved를 묶어 만든다. 동일한 묶음의 반복 알림은 한 보고로 수렴하고 resolved 전환은 별도 보고다. 일일 키는 KST 날짜다. SQLite 트랜잭션으로 작업을 선점하고 300초 임대를 사용해 동시 작업자를 분리한다. 프로세스가 종료되면 만료한 임대를 다시 처리한다. 보고서를 저장한 뒤 전송하므로 재시도는 원래 근거와 분석을 유지한다.

429·408·5xx·연결 실패는 최대 6회 시도한다. 지수 대기와 Retry-After 초/HTTP-date를 적용하되 대기는 최대 1시간이다. 영구 4xx·리다이렉트와 한도 초과는 dead로 남긴다. `job_attempts`에는 각 시도의 시각·상태·정제한 오류 코드를 남겨 성공 후에도 재시도 이유를 보존한다. 이 기록을 도입하기 전 성공으로 덮인 오류는 복원할 수 없다. Teams가 접수했어도 응답 시간 초과가 발생하거나 SQLite 상태 저장 전에 프로세스가 종료되면 같은 보고가 재전송될 수 있다. 외부 웹훅의 정확히 한 번 전달을 보장하지 않으며 카드의 보고 ID로 같은 사건임을 구분한다.

PDF 생성 결과는 SQLite의 `report_artifacts`와 상태별 지표에 남는다. 글꼴·렌더러 오류가 발생해도 요약 카드와 다음 보고의 처리는 계속한다. 실패 상태의 보고는 같은 저장 원자료로 파일을 다시 생성하고 확인한 뒤 상태를 갱신한다.

보고서는 아래처럼 내보낼 수 있다. 저장된 JSON은 이미 정제한 근거·분석이며 상태 조회에 웹훅 URL은 포함되지 않는다. 대기열·보고 DB는 별도 백업·보존 기간을 운영자가 정하고 저장 공간을 관측한다. 보존 기간을 정하지 않은 상태에서 자동 삭제하지 않는다.

```powershell
python deploy/reporting/reporter.py export --config deploy/reporting/config.example.json --state artifacts/operations-20260918/report-outbox.sqlite3 --id daily:2026-09-18 --output artifacts/operations-20260918/daily-report.json
```

`export --format html`은 같은 저장 보고를 외부 자원·스크립트 없이 읽을 수 있는 HTML로 내보낸다. [PDF 내보내기](render_pdf.py)는 이 JSON을 고정 양식에 바인딩해 A4 2쪽으로 만든다. 새 AI 호출이나 Teams 전송은 하지 않는다. 4개 그래프와 최대 12개 지표를 표시하며 상세 원자료는 저장 보고에 남긴다. `--bindings-output`은 보고 ID·표시 값·개입 판정·그래프 근거의 바인딩 JSON을 함께 저장한다.

```powershell
python deploy/reporting/reporter.py export --state <outbox.sqlite3> --id <report-id> --format html --output report.html
python deploy/reporting/render_pdf.py --input report.json --output report.pdf --bindings-output report-bindings.json --font C:/Windows/Fonts/malgun.ttf --font-bold C:/Windows/Fonts/malgunbd.ttf
```

PDF 환경에는 ReportLab과 한글 TTF 글꼴을 설치한다. Windows 수동 내보내기는 ReportLab 4.4.9와 시스템의 맑은 고딕을 사용하며 글꼴 원본을 배포 패키지에 복사하지 않는다. Linux에서는 해당 환경의 사용 가능한 Nanum·Noto 한글 TTF 경로를 `--font`로 지정한다. PDF에는 사용한 글꼴을 포함한다. 원본을 바꾼 뒤 다시 렌더링하고 모든 페이지의 글자·표·여백을 확인한다. 자동 제공은 위 파일 제공 설정을 사용하며, Teams 카드 수신과 PDF 버튼의 실제 열기를 각각 확인한다.

로컬 테스트는 mock HTTP 서버만 사용해 지표 0/없음/실패, 리다이렉트 거부, 정제, 잘못된 AI 근거·시간 초과, 동시 작업·중복, 재시작, 보고 재사용, 429와 영구 실패, 입력 인증·본문 제한을 확인한다. 관제센터 사건은 모양 검사와 거부, 카드 문구와 개입 등급, 관제센터 버튼의 설정 검사, 수신·중복·전송, 보고 목록과 전송 상태 조회를 [사건 시험](test_msp_events.py)이 본다. 실제 AI 모델 정확도, Teams 채널 게시, AWS 계정 권한은 이 테스트의 검증 범위가 아니다.
