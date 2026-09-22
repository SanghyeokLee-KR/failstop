# 부하·장애 실험 도구

거래 서버에 부하나 장애를 주고 복구 시간과 데이터 상태를 확인하는 도구다. 로컬 Docker 스택, 실험 스크립트, 검사 SQL, 모니터링 설정과 실행 결과를 이 폴더에 둔다.

2026-09-18 이후 Docker 카오스는 로컬 개발 검증 이력으로 보존한다. 새 카오스 기능 개발이나 전수 재실행을 현재 목표로 두지 않는다. 부하 도구·SQL 검사·Grafana·Prometheus는 AWS 운영·MSP 관제에 필요한 부분을 재사용한다. 현재 범위는 [ADR 0027](../docs/reference/adr/0027-msp-operations-and-autoscaling.md)과 [로드맵](../docs/ROADMAP.md)을 따른다.

실험의 조건과 통과 기준은 [하네스 검증 설계](../docs/reference/contracts/하네스_검증설계.md), 날짜별 결과는 [측정 문서](../docs/measurements/README.md)에 있다.

## 시작하기

PowerShell 7, Docker와 서버 빌드 환경이 필요하다. 먼저 `.env.chaos.example`을 참고해 `harness/.env.chaos`를 준비한다. 실제 비밀 값은 커밋하지 않는다.

아래 명령은 저장소 루트에서 실행한다. `up.ps1`은 서버 JAR와 이미지를 빌드하고 격리 스택을 시작한다.

```powershell
pwsh -File harness/scripts/up.ps1 -Obs
```

웹까지 함께 실행하는 방법은 [실행 가이드](../실행_가이드.md)를 따른다. 실험 스택은 `chaos-*` 컨테이너와 별도 포트·볼륨을 사용한다. 스크립트가 겨냥한 대상이 이 격리 스택인지 확인한다.

## 구성과 접속

[Compose 파일](docker-compose.chaos.yml)에 PostgreSQL, 노드별 DB 프록시, API 두 노드, LB와 봇이 정의되어 있다. `obs` 프로파일은 Prometheus, Grafana, Alertmanager와 계층별 프로브 등의 관측 도구를 추가한다.

| 대상 | 로컬 포트 |
|---|---:|
| PostgreSQL | 55432 |
| Toxiproxy 관리 API | 58474 |
| API 1 / API 2 | 18080 / 28080 |
| API 3 (조회 전용 증설 노드) | 38080 |
| API 로드밸런서 | 58080 |
| Prometheus / Grafana | 59090 / 53001 |
| Alertmanager | 59093 |

로드밸런서는 쓰기와 조회를 다른 업스트림으로 보낸다. 쓰기는 1차 노드와 백업 노드를 둔 정적 설정이고 DB standby와는 별개이며, 모든 상황에서 리더를 자동으로 찾아 라우팅하는 구성은 아니다. 조회는 마켓·체결·캔들 경로만 해당하고 켜져 있는 노드에 나눠 보낸다. 호가와 티커는 노드 로컬 순번을 실어 보내므로 쓰기 쪽에 남는다. 배선의 조건과 실측은 [검증 설계 §3](../docs/reference/contracts/하네스_검증설계.md)에 있다.

## 용량 증설과 축소

조회 노드를 한 대 더 붙였다 떼는 경로다. AWS의 Auto Scaling group 자리를 로컬에서 대신한다. 판정은 사람이 관리 콘솔에서 하고, 조정기는 정해진 희망 대수를 실현하기만 한다.

`chaos-api-3`은 `scale` 프로파일이라 기본 기동에 없다. 한 번 만들어 두면 조정기가 그 컨테이너를 켰다 껐다 한다. 조정기가 직접 `docker run` 하지 않는 이유는 환경 변수를 compose 파일 한 곳에만 두기 위해서다.

```powershell
docker compose --env-file harness/.env.chaos --profile scale create chaos-api-3
python harness/asg/capacity_reconciler.py --stack chaos --lb-conf harness/nginx/nginx.conf --log <로그경로>
```

조회 계층에 부하를 넣을 때는 `python harness/scripts/read-load.py --vus 20 --sec 300`을 쓴다. 봇 발행률로는 조회 계층이 움직이지 않는다. 봇이 내는 주문은 쓰기 경로이고 봇이 보는 호가·티커도 노드 로컬 순번이라 쓰기 경로에 남기 때문이다. 권고 임계는 서버가 소유하며 이 스택은 `admin-chaos` 프로파일에서 0.25로 내려 두었다. AWS 운영값 0.60과 구분한다.

희망 대수는 관리 API의 `POST /admin/v1/capacity/scale-out`과 `scale-in`이 바꾸고 조정기가 그 값을 따라간다. 조정기는 한 주기에 한 대만 움직이고, 축소할 때는 로드밸런서에서 먼저 빼고 드레이닝 창을 기다린 뒤에 컨테이너를 내린다. 쓰기 백업 노드는 축소 대상에서 제외한다. `--stack` 기본값은 종전 `ex-*` 스택이라 옛 호출은 그대로 돈다.

## 장애 실험

실행할 실험의 설정을 `config/`에서 확인한 뒤 해당 스크립트를 실행한다. 예를 들어 앱 리더 중단은 다음과 같다.

```powershell
pwsh -File harness/scripts/e4-leader-kill.ps1
```

| 실험 | 스크립트 | 확인할 동작 |
|---|---|---|
| E1 | [PG 지연](scripts/e1-pg-latency.ps1) | 지연 중 거래 유지. 불필요한 페일오버가 없어야 한다. |
| E2 | [DB 연결 단절](scripts/e2-db-cut.ps1) | DB에 닿지 못하는 앱의 전환과 복구. |
| E3 | [Redis 장애](scripts/e3-redis-outage.ps1) | 현재 Redis가 없어 SKIP 사유를 기록한다. 장애 주입 PASS에 포함하지 않는다. |
| E4 | [리더 중단](scripts/e4-leader-kill.ps1) | 대기 앱 승격과 체결 재개. |
| E5 | [네트워크 분할](scripts/e5-net-partition.ps1) | 이전 리더의 잘못된 쓰기 차단. |
| E6 | [폭주 중 리더 중단](scripts/e6-spike-failover.ps1) | 부하 중 데이터 정합성과 복구. |
| E7 | [프로세스 일시 정지](scripts/e7-gray-pause.ps1) | 죽지 않았지만 처리하지 못하는 리더의 전환. |
| E8 | [앱 지연·예외](scripts/e8-app-assault.ps1) | 앱 내부 장애의 영향. |
| E9 | [커밋 응답 절단](scripts/e9-commit-cut.ps1) | 응답을 잃은 주문의 재조회와 중복 방지. |

현재 E6 스크립트는 봇 노드의 OPS를 바꿔 스파이크를 만든다. 일정한 도착률로 요청을 넣는 k6 실험과는 구분한다. k6 준비와 실행은 [부하 도구 안내](load/README.md)에 있다. E10 커널 네트워크 실험은 로드맵의 남은 작업이다.

## 결과 읽기

실험마다 사전 상태, 장애 주입, 복구, 사후 검사를 확인한다. 페일오버가 필요한 실험과 없어야 하는 실험의 조건이 다르므로 하나의 공통 식으로 모두 판정하지 않는다.

판정 파일은 [evidence/chaos-runs/](evidence/chaos-runs/)에 남는다. DB의 `invariant_checks`는 데이터 검사, `harness.failover_runs`는 복구 시간과 판정, `recovery_runs`는 재생 대조 결과를 담는다. 볼륨을 지우면 DB 기록도 사라지므로 필요한 결과를 먼저 보존한다.

요청·체결·검사 대상이 실제로 있었는지 확인한 뒤 위반 0건을 해석한다. 빈 결과나 실패한 조회를 통과로 읽지 않는다. `SKIP`과 `NOT RUN`도 실행 후 통과와 구분한다.

직접 확인할 SQL은 [invariants.sql](sql/invariants.sql)과 [summary.sql](sql/summary.sql), 공통 판정 코드는 [scripts/lib.ps1](scripts/lib.ps1)에 있다. 재생 결과는 `ReplayService.verifyToMarket`이 저장된 이벤트와 대조한다. `fp_replay`와 `fp_live`의 문자열 일치만으로 결정론을 판단하지 않는다.

## 관측과 한계

[Prometheus](prometheus/), [Grafana](grafana/), [Alertmanager](alertmanager/), [blackbox](blackbox/)에 설정이 있다. 로컬 계층별 검사와 알람 실험은 [probe-layers.ps1](scripts/probe-layers.ps1)과 [09-13 결과](evidence/layer-probes-20260913/)를 본다. 알람 규칙이 있다는 사실과 실제 외부 알림이 전달되는지는 따로 확인한다.

현재 복구에는 마켓별 스냅샷과 저널 재생이 구현되어 있다. 자세한 흐름은 [코드 지도](../docs/reference/architecture/프로젝트_코드지도.md)에 있다. 단일 PC에서 실행한 컨테이너 실험은 실제 호스트·가용영역 분리를 검증한 결과가 아니다.

종료와 초기화는 구분한다. [down.ps1](scripts/down.ps1)은 격리 스택의 볼륨까지 지우는 초기화 명령이다. 데이터를 유지하려는 단순 중지에 사용하지 않는다.
