# ADR 0034 S6 관문 스택 실험 초안 (W0~W5)

상태: **모두 NOT RUN**. 이 폴더는 S6 코드 단계(2026-09-25)에서 준비한 실행기와 판정 규칙의 초안이다. 실행 결과, 판정 파일, 측정값은 없다. S5를 병합한 트리에서 메인 세션이 돌리고, 돌릴 때 이 폴더를 `harness/evidence/pml-S6-<YYYYMMDD-HHMM>/`로 옮겨 적는다. 설계는 [S6 설계](../../../docs/reference/architecture/마켓분할_리더_S6_설계.md) 7절이고 판정 값은 설계를 맡은 AI가 정했으며 사용자 확인 대상이다.

## 안전 수칙

설계 7-0을 그대로 따른다. 실험 스택은 compose 프로젝트 `exchange-pml`, 앱 이미지 태그 `exchange-chaos-app:pml`로만 띄우고 `exchange-chaos-app:local`을 덮어쓰지 않는다. 보존 볼륨 `exchange-chaos_chaos-pgdata`와 백업 볼륨 `failstop-keep-*`는 생성 시각만 읽는다(`s6-relay-outage.ps1`이 주행 앞뒤 값을 `summary.json`에 남긴다). 프로젝트 `exchange-chaos`에는 `down -v`, `volume rm`, `up`을 하지 않는다. 비밀 파일(`harness/.env.chaos`, `harness/load/secrets/k6_keys.json`)은 경로로만 넘기고 열지 않는다. 브로커 비밀 값은 `harness/.env.chaos`의 `CHAOS_RELAY_PASSCODE`에 둔다. 시작 전에 `docker ps -a --filter "name=chaos-"`가 비어 있어야 한다.

## 준비

설계 7-1의 환경 변수를 둔 뒤 `up.ps1`로 이미지를 한 번 굽는다. W0은 오버레이 없이 돈다. 실행기 `run-gate.ps1`이 실험마다 `CHAOS_COMPOSE_OVERLAYS`를 비우거나 `harness/docker-compose.relay.yml`로 채운다.

```powershell
$env:CHAOS_PROJECT = 'exchange-pml'
$env:COMPOSE_PROJECT_NAME = 'exchange-pml'
$env:CHAOS_APP_IMAGE = 'exchange-chaos-app:pml'
$env:CHAOS_SPRING_PROFILES = 'dev,chaos-monkey,prod-timeouts'
$ev = 'C:\dev\exchange-pml\harness\evidence\pml-S6-<YYYYMMDD-HHMM>'
$env:CHAOS_EVIDENCE_DIR = "$ev\chaos-runs"
Remove-Item Env:CHAOS_COMPOSE_OVERLAYS -ErrorAction SilentlyContinue
pwsh -NoProfile -File harness/scripts/up.ps1 -Obs
# 실행기는 chaos- 컨테이너가 없어야 시작한다. 이미지를 구운 스택을 내린다. down -v 는 -p exchange-pml 을 붙인 명령으로만 한다
docker compose -p exchange-pml -f harness/docker-compose.chaos.yml --env-file harness/.env.chaos --profile obs down -v
pwsh -NoProfile -File harness/evidence/pml-S6-gate-draft/run-gate.ps1 -EvidenceDir $ev
```

## 실행기

| 실험 | 명령(실행기가 부른다) | 판정 파일 |
|---|---|---|
| W0 기본 모드 회귀 | `adr0022-stage5-chaos.ps1 -Experiments E4,E7 -Protocol 2 -PartitionAxes -WsGateAxes` | 실험마다 `ws_gate.json`, 종전 판정 파일 |
| W1 중계 기본 | `s6-relay-outage.ps1 -Experiment W1` | `relay_axes.json`, `s3_common.json`, `watch.json`, `freshness.jsonl` |
| W2 브로커 정지 | `s6-relay-outage.ps1 -Experiment W2` | 같음 |
| W3 브로커 멈춤 | `s6-relay-outage.ps1 -Experiment W3` | 같음 |
| W4 브로커 메모리 경보 | `s6-relay-outage.ps1 -Experiment W4` | 같음 |
| W5 리더 kill(중계) | `adr0022-stage5-chaos.ps1 -Experiments E4 -Protocol 2 -PartitionAxes -Relay -WsGateAxes` | `relay_axes.json`, `ws_gate.json`, `relay-preflight.json`, 종전 판정 파일 |

W1~W5는 스택을 세우고 P2로 전환한 뒤 오버레이 확인(`chaos-broker` healthy, 두 앱 컨테이너의 `EXCHANGE_WS_RELAY_MODE=broker`, `chaos-lb`의 `nginx.relay.conf` 마운트)을 `relay-preflight.json`에 남기고, 하나라도 거짓이면 그 실험을 BLOCKED로 끝낸다. 확인을 통과해야 k6 관찰자(`harness/load/ws_relay_watch.js`, 컨테이너 `pml-s6-watch-<실험>`)와 미러 신선도 표본기(`s6_relay_axes.py freshness`)를 띄운다. 관찰자는 다음 `down -v` 전에 멈추고 로그를 `watch.log`로 남기며, `s6_relay_axes.py watch`가 노드별 상태를 `watch.json`으로 모은다.

## 판정 규칙

축 이름은 `harness/scale/s6_relay_axes.py`의 판정 파일 키다. 축마다 참·거짓이고 하나라도 거짓이면 그 실험은 FAIL이다. 읽지 못한 값은 참으로 세지 않는다. 모든 실험에 공통 판정(S4b까지 그대로, `s3_common_axes.py judge`)이 붙는다.

- **W0** (`gate --mode simple`): `leader_found`, `follower_is_expected`(E4·E7 뒤 api-1), `follower_orderbook_seq0_lease_epoch`·`follower_ticker_seq0_lease_epoch`(각 10회), `follower_handshake_503_retry_after_5`, `leader_handshake_101`, `lb_handshake_101`, `lb_log_503_then_101`, `follower_gate_closed`, `follower_refused_relay_required_ge_1`, `leader_gate_open`.
- **W1**: 세 관찰자(api-1, api-2, LB) x 세 마켓마다 `book_ge_100`·`trades_ge_10`·`dense_gaps_0`·`one_epoch`, `book_set_diff_le_0_1pct`, `dropped_0`, `connected_always_1`, `degraded_always_0`, `lag_p99_le_250ms`, `freshness_300_pairs_all_ok`, `freshness_follower_nonzero_ge_90pct`.
- **W2·W3·W4 공통**: `accept_ratio_ge_90pct`, `leader_5xx_0`, `demotions_0`, `append_p99_le_1_5x`, `connected_back_within_<15|20|30>s`, `follower_degraded_within_20s`, `follower_degraded_back_0`, `leader_degraded_always_0`, `follower_watch_1013`, `follower_watch_handshake_refused`, `leader_watch_kept_connection`, `leader_watch_kept_receiving`, `all_watchers_receive_again_within_60s`, `freshness_during_and_60s_after_all_ok`.
- **W2·W3 추가**: `connected_went_0`. W3은 `heap_growth_le_64MiB`.
- **W4 추가**: `sub_link_kept_during_alarm`, `dropped_write_timeout_or_queue_full_ge_1`, `follower_book_seq0_during_alarm`(경보 시작 8초 뒤부터), `mirror_fallback_owner_silent_ge_1`, `follower_gate_open_within_30s`, `follower_lag_max_le_5s_after`. 팔로워 `degraded`는 `cause="owner_silent"`로 본다.
- **W5** (`judge --experiment W5`, `gate --mode broker`): `survivor_watch_reconnect_0`, `survivor_never_degraded`, 세 마켓마다 `epoch_rise_once_within_rto_plus_5s`·`dense_gaps_after_rise_0`, `rejoined_api1_gate_open`, `freshness_after_rejoin_all_ok`, 두 노드 `gate_open`·`handshake_101`, `lb_handshake_101`. E4 규칙(RTO 30초 미만, epoch 정확히 +1, 옛 epoch 쓰기 0)은 종전 판정이 본다.

W6·W7은 P3이 필요해 S7 관문에서 돈다(설계 7-2). 웹 관찰은 `harness/scripts/s6-web-resync.mjs`다.

## 알려진 한계

- 시각 판정의 해상도. 연결·불능 게이지는 프로메테우스 표본(api 잡 2초 간격)으로 읽으므로 '15초 안에' 같은 판정에 2초 안팎의 오차가 있다. W5의 '팔로워였던 동안 `degraded`가 한 번도 1이 아니다'도 2초 표본이다. 200ms 간격 판정은 서버 시험 `RelayDegradedGateTest`가 한다.
- W0의 LB 로그 판정. 기본 스택 `nginx.conf`의 접근 로그는 업스트림 상태를 싣지 않는다. 그래서 nginx가 `/ws/` 요청의 503으로 1차 노드를 뺄 때 남기는 경고 줄(`upstream server temporarily disabled`, 요청 줄 포함) 뒤에 `/ws/` 101 접근 줄이 있는지를 본다. 봇 쓰기의 503이 1차 노드를 먼저 빼 두면 WS 요청이 그 노드를 거치지 않으므로, WS 게이트 표본은 공통 판정이 봇을 멈춘 뒤에 뜬다. 중계 오버레이의 `nginx.relay.conf`는 접근 줄 끝에 `ups=<상태 목록>`을 싣는다.
- 관찰자의 거절 코드. k6 WebSocket은 업그레이드 거절의 HTTP 상태를 이벤트에 싣지 않을 수 있어, 핸드셰이크 실패 뒤 같은 주소로 업그레이드 머리글을 단 HTTP 요청을 한 번 더 보내 상태와 `Retry-After`를 남긴다(5초에 한 번). 그 사이 게이트가 열리면 이 요청이 3초 시한까지 붙어 있을 수 있다.
- 메모리 경보 주입은 `docker exec -u rabbitmq chaos-broker rabbitmqctl ...`로 한다. 설계 7-2의 명령에서 사용자를 더했다. root로 부르면 Erlang 쿠키가 맞지 않아 실패했다(서버 시험 `RelayBroker`).
- 경보 중 늦은 전달. 로컬 Docker Desktop의 포트 전달 프록시는 앱의 RST를 컨테이너 브로커에 넘기지 않아, 서버 시험에서는 경보 뒤 늦게 도착한 봉투를 기록만 했다. 실험 스택은 앱과 브로커가 같은 `chaos-net`에 있어 프록시를 거치지 않는다. W4의 `follower_lag_max_le_5s_after`가 이 성질을 스택에서 처음 판정한다.
