# Patroni 실험 스택 기동 확인 (2026-09-25)

코드 단계에서 만든 Patroni 실험 스택([compose](../../patroni/docker-compose.patroni.yml))이 뜨고, 쓰기 가능 탐지·쓰기 게이트·리스 획득 규칙이 실제 Patroni 전환에서 설계대로 움직이는지 한 번씩 본 기록이다. E-P 실험이 아니다. DB 수준 ACK 기록기, 사전·사후 권위 run 의 비교, 회차 무효 조건 확인, 부하(k6·출금 요청기)가 없고 시간 축 판정을 하지 않았다. 설계 8-3 의 PASS·FAIL 은 여기서 정하지 않는다(NOT RUN).

## 환경

| 항목 | 값 |
|---|---|
| 호스트 | 이 PC 의 Docker Desktop. 같은 시각에 다른 작업(마켓별 리더 S5·S6)의 빌드와 Testcontainers 컨테이너가 돌았다 |
| 이미지 | `exchange-patroni-db:local`(postgres 17.11-bookworm + Patroni 4.1.5, psycopg 3.3.6), `quay.io/coreos/etcd:v3.5.34`, `haproxy:3.0.27-alpine`, `exchange-chaos-app:patroni`(이 브랜치 작업 트리의 bootJar) |
| 구성 | compose 프로젝트 `exchange-patroni`, 컨테이너 `patroni-*`, 망 둘(10.159.0.0/24 DB 망, 10.159.1.0/24 앱 망), 호스트 포트는 루프백 59xxx |
| 앱 | 프로파일 `dev,chaos-monkey,prod-timeouts,patroni`, 리스 TTL 6초·틱 2초, 봇 10 OPS. 리더 모드는 P1 이다(P2 전환은 하지 않았다) |
| 관찰 | [observe.sh](observe.sh)가 1초마다(응답이 느리면 더 길게) 두 앱의 상태 API, LB 로 보낸 무인증 POST `/api/v1/orders`(ACTIVE 면 인증 필터의 401, SUSPENDED 면 게이트의 423, UNREACHABLE 이면 503), LB 조회 `/api/v1/markets`, Patroni `/cluster`, 리더 리스를 적었다. 시각은 UTC 다 |

## 장면과 관찰

| 장면 | 관찰(원시 표본) |
|---|---|
| Standby 정지(`docker stop patroni-db-2` 06:20:57, `docker start` 06:22:13) | [standby-stop-observe.txt](standby-stop-observe.txt). 두 앱이 06:21:00.5 무렵 SUSPENDED(`replication_unavailable`)가 됐다(docker stop 명령 자체가 정상 종료를 기다린다). 중단 동안 쓰기 게이트는 423, LB 조회는 200, 리더 리스는 epoch 1 그대로였다. Standby 가 streaming 으로 돌아온 뒤 06:22:20.6 에 사람 조작 없이 ACTIVE 로 돌아왔고 두 노드가 재개 따라잡기를 요청했다. Patroni `/cluster` 가 sync_standby 를 적은 것(06:22:22)보다 탐지기의 재개가 앞섰다. 탐지기는 설정이 멤버 이름으로 바뀐 것을 읽었다 |
| Primary 강제 종료(`docker kill patroni-db-1` 06:23:43, `docker start` 06:26:34) | [primary-kill-observe.txt](primary-kill-observe.txt). 06:23:49.7 UNREACHABLE(`database_unavailable`), LB 쓰기는 502·503, db-2 가 06:24:07 무렵 타임라인 2 의 리더가 된 뒤 06:24:08.7 SUSPENDED(`replication_unavailable`, `'*'`), 쓰기 423. 옛 리더(api-1)는 06:24:10 리스 갱신 거부로 파티션 강등 한 번(차단기 1회)이었다. db-1 은 06:26:36 pg_rewind(종료 코드 0)로 되감겨 06:26:37 스트리밍, 06:26:39.0 api-2 가 ACTIVE 로 돌아온 직후 06:26:39.1 epoch 2 로 승격했다. 중단 동안 어느 노드도 리스를 얻지 않았다(획득은 ACTIVE 에서만) |
| 사후 불변식 | 두 장면 뒤 `POST /harness/v1/invariants/run?runId=smoke-20260925-1&window=post`(api-2)가 PASS, criticalClean true, 위반 0 이었다 |

[transitions.txt](transitions.txt)는 컨테이너 로그에서 상태 전이, 리스, 탐지 실패, Patroni 동기 지정과 되감기 줄만 옮긴 것이다. 원문 로그는 저장소 밖에 두고 해시를 [log-sha256.txt](log-sha256.txt)에 적었다.

탐지 연결의 소켓 제한(60초)도 한 번씩 걸렸다. 중단이 60초를 넘자 세 앱 노드가 탐지 연결을 버리고 다시 붙으며 커밋을 기다리던 이전 탐지 백엔드를 끝냈다(`커밋을 기다리던 이전 탐지 백엔드를 끝냈다`). 새 탐지 연결의 첫 탐지 가운데 하나는 승격 직전의 db-2 에 닿아 `cannot assign TransactionIds during recovery`로 실패했고 불통으로 셌다.

## 확인하지 않은 것

설계 8-3 의 판정 축(ACK 유실, T_ 구간 상한, 재개 뒤 첫 체결, 조회 표본 비율, 구 epoch 쓰기 증분, 주문 500)은 재지 않았다. P2 모드, 소리 없는 정지(pause), 격리, etcd 장애, 한 노드 경로 지연, 재초기화, 영구 손실은 돌리지 않았다. 봇은 423 을 받아도 주문 생성을 멈추지 않았다(봇 코드 변경 전).

## 정리

끝에 `docker compose -p exchange-patroni ... --profile obs down -v --remove-orphans`로 지웠고 이름이 `patroni-`인 컨테이너, 프로젝트 라벨이 `exchange-patroni`인 볼륨, 이름이 `exchange-patroni`인 망이 없음을 출력으로 확인했다. 보존 볼륨 `exchange-chaos_chaos-pgdata`와 `failstop-keep-chaos-pgdata-20260924`는 마운트하지 않았고 목록에 그대로 있었다. 다른 작업의 프로젝트·컨테이너에는 명령을 보내지 않았다.
