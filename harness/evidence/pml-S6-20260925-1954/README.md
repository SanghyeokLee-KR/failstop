# S6 관문 폐기용 스택 실험 W0~W5: WebSocket 외부 브로커 중계 (2026-09-25 19:54 KST~)

[S6 설계](../../../docs/reference/architecture/마켓분할_리더_S6_설계.md) 7절의 스택 실험을 S5·S6을 병합한 트리(`C:\dev\exchange-pml`, 브랜치 `claude/per-market-leader`, HEAD `d46d5073`)에서 돈 기록이다. 코드 단계가 남긴 초안 `harness/evidence/pml-S6-gate-draft/`(실행기 `run-gate.ps1`과 판정 규칙)를 이 폴더로 옮겨 적었다. 실행 결과와 판정 표는 주행 뒤 아래 '결과' 절에 적는다. 이 절까지는 주행 전에 고정한 조건과 판정 규칙이다.

## 주행 전에 고정한 판정 규칙

축 이름은 `harness/scale/s6_relay_axes.py`의 판정 파일 키다. 축마다 참·거짓이고 하나라도 거짓이면 그 실험은 FAIL이다. 읽지 못한 값(프로메테우스 무응답, 파일 없음)은 참으로 세지 않는다. 판정 값은 설계를 맡은 AI가 2026-09-25에 정했고 사용자 확인 대상이다(설계 7-2, 10절). 모든 실험에 공통 판정(S4b까지 그대로, `s3_common_axes.py judge`: 사후 권위 run과 드레인 뒤 `summary.sql`의 CRITICAL·HIGH PASS, 리더 5xx 0, 세 원천의 주문 `500` 0, 리더 강등 계수 0 등)이 붙는다. 실험 스크립트가 실행 전 조건을 채우지 못하면 BLOCKED이고, 실행하지 않은 실험은 NOT RUN이다. 어느 것도 통과에 합산하지 않는다.

- **W0 기본 모드 회귀**(P2, `simple`, `nginx.conf`, 오버레이 없음). `adr0022-stage5-chaos.ps1 -Experiments E4,E7 -Protocol 2 -PartitionAxes -WsGateAxes`. 종전 판정(E4·E7 규칙, 공통 판정, 파티션 축, E4 합류)과 `ws_gate.json`(`gate --mode simple`)의 축 `leader_found`, `follower_is_expected`(E4·E7 뒤 팔로워가 api-1), `follower_orderbook_seq0_lease_epoch`·`follower_ticker_seq0_lease_epoch`(팔로워 호스트 포트 각 10회가 모두 `200`·`seq: 0`·리스 epoch), `follower_handshake_503_retry_after_5`, `leader_handshake_101`, `lb_handshake_101`, `lb_log_503_then_101`, `follower_gate_closed`, `follower_refused_relay_required_ge_1`, `leader_gate_open`.
- **W1 중계 기본**(P2, 봇 300 OPS, 600초, 주입 없음). 세 관찰자(api-1, api-2, LB) x 세 마켓마다 `book_ge_100`(호가 실시간 100건 이상), `trades_ge_10`, `dense_gaps_0`(호가·티커 결번 0), `one_epoch`. 그리고 `book_set_diff_le_0_1pct`(두 노드 관찰자의 호가 `(market, epoch, seq)` 집합 대칭차가 합집합의 0.1% 이하), `dropped_0`(`exchange_ws_relay_dropped_total` 증가 0), `connected_always_1`, `degraded_always_0`, `lag_p99_le_250ms`, `freshness_300_pairs_all_ok`(미러 신선도 표본 300쌍 이상이 모두 '팔로워 `seq` 0이거나 500ms 앞 리더 표본 이상'), `freshness_follower_nonzero_ge_90pct`.
- **W2 브로커 정지**(`docker stop chaos-broker` 60초 뒤 `docker start`), **W3 브로커 멈춤**(`docker pause` 40초 뒤 `unpause`), **W4 브로커 메모리 경보**(`docker exec -u rabbitmq chaos-broker rabbitmqctl set_vm_memory_high_watermark absolute 1MiB` 30초 뒤 `192MiB`). 봇 300 OPS, 주입 앞 60초, 복구 뒤 90초. 공통 축 `accept_ratio_ge_90pct`(창 안 저널 append 속도 / 창 앞 60초), `leader_5xx_0`, `demotions_0`, `append_p99_le_1_5x`, `connected_back_within_<15|20|30>s`(W2·W3·W4), `follower_degraded_within_20s`(W4는 `cause="owner_silent"`), `follower_degraded_back_0`, `leader_degraded_always_0`, `follower_watch_1013`, `follower_watch_handshake_refused`, `leader_watch_kept_connection`, `leader_watch_kept_receiving`, `all_watchers_receive_again_within_60s`, `freshness_during_and_60s_after_all_ok`. W2·W3은 `connected_went_0`, W3은 `heap_growth_le_64MiB`. W4는 `sub_link_kept_during_alarm`, `dropped_write_timeout_or_queue_full_ge_1`, `follower_book_seq0_during_alarm`(경보 시작 8초 뒤부터), `mirror_fallback_owner_silent_ge_1`, `follower_gate_open_within_30s`, `follower_lag_max_le_5s_after`.
- **W5 리더 kill(중계)**. `adr0022-stage5-chaos.ps1 -Experiments E4 -Protocol 2 -PartitionAxes -Relay -WsGateAxes`. 종전 E4 규칙(RTO 30초 미만, epoch 정확히 +1, 옛 epoch 쓰기 0)·공통 판정·파티션 축·합류와, `relay_axes.json`(`judge --experiment W5`)의 `survivor_watch_reconnect_0`, `survivor_never_degraded`, 세 마켓마다 `epoch_rise_once_within_rto_plus_5s`·`dense_gaps_after_rise_0`, `rejoined_api1_gate_open`, `freshness_after_rejoin_all_ok`, 그리고 `ws_gate.json`(`gate --mode broker`)의 두 노드 `gate_open`·`handshake_101`, `lb_handshake_101`.
- W1~W5는 스택을 세우고 P2로 전환한 뒤 오버레이 확인(`chaos-broker` healthy, 두 앱 컨테이너의 `EXCHANGE_WS_RELAY_MODE=broker`, `chaos-lb`의 `nginx.relay.conf` 마운트)을 `relay-preflight.json`에 남기고, 하나라도 거짓이면 그 실험은 BLOCKED다.
- W6·W7은 P3가 필요해 S7 관문이다(설계 7-2). 이 주행의 판정 대상이 아니다(N/A).

잡음 재측정. 같은 PC에서 Patroni 실험(Docker 프로젝트 `exchange-patroni`)이 함께 돌 수 있다. 실험마다 앞뒤로 호스트 CPU와 다른 프로젝트 컨테이너 수를 남기고(`host-<실험>.json`), 주행 동안 5초 간격 CPU를 `cpu-<실험>.csv`에 남긴다. 시간 축(RTO, 지연 p99, '몇 초 안에')에서만 FAIL하고 그 시각에 호스트 CPU가 높았으면 같은 조건으로 한 번 더 잰다. 다시 잰 결과는 첫 결과를 지우지 않고 나란히 적는다.

결번 관찰. S5 첫 주행에서 k6 WebSocket orderbook 결번(8회 중 1회, 4건)이 났다. 이번에는 관찰자의 호가·티커 결번(`gaps`: 시각, epoch, 앞 seq, 뒤 seq)을 노드별로 모으고, 결번이 나면 같은 시각의 두 노드 관찰자, 중계 유실 계수(`exchange_ws_relay_dropped_total{reason}`), 앱 로그를 대조해 원인이 게시 쪽(두 노드가 같은 seq를 함께 못 받음)인지 중계 쪽(팔로워만 못 받음)인지 WS 세션 쪽(한 관찰자만)인지 적는다.

결함 처리. 코드 결함으로 FAIL이면 원인을 좁힌다. 자산 보존·멱등·펜싱에 영향이 있거나 관문을 막는 결함이면 고친 뒤 시험을 더하고 `server` 전체 빌드를 돌려 이미지를 다시 구워 해당 실험을 다시 잰다. 도구(판정기·실행기) 결함이면 고친 뒤 다시 재고, 고치기 전 결과는 최신 통과 증거로 쓰지 않는다.

## 안전 수칙과 조건

설계 7-0을 따른다. compose 프로젝트 `exchange-pml`, 앱 이미지 `exchange-chaos-app:pml`(이 트리에서 새로 굽고 참조 태그 `exchange-chaos-app:s6ref`를 함께 붙인다)에서만 돈다. 보존 볼륨 `exchange-chaos_chaos-pgdata`와 `failstop-keep-*`는 생성 시각만 읽는다(시작·끝과 단계마다 `volumes.txt`). 프로젝트 `exchange-chaos`에는 `up`·`down`·`volume rm`을 하지 않고, `down -v`는 `-p exchange-pml`이 붙은 호출로만 한다. `patroni-*` 컨테이너는 건드리지 않는다. 비밀 파일(`harness/.env.chaos`, `harness/load/secrets/k6_keys.json`)은 경로로만 넘긴다. 이 주행 전에 `harness/.env.chaos`에 무작위 브로커 비밀 값 `CHAOS_RELAY_PASSCODE`를 한 줄 더했다(값은 출력하지 않았다). 관찰자 컨테이너 이름은 `pml-s6-watch-<실험>`이다.

실행기는 [run-s6-gate.ps1](run-s6-gate.ps1)이다. 초안 `run-gate.ps1`에 단계 기록(보존 볼륨, 호스트 부하, CPU 표본, 이미지 기록)을 더한 것이고, 초안 두 파일은 이 커밋에서 지웠다(Git 이력의 `harness/evidence/pml-S6-gate-draft/`). 여러 단계를 잇는 것은 [run-seq.ps1](run-seq.ps1)이고 순서와 종료 코드는 `run-seq-*.log`, 단계마다 실행기 출력은 `<단계>-runner.log`, 모든 명령의 시각은 [commands.log](commands.log)다. 관찰자 요약은 [summarize.py](summarize.py), 결번 대조는 [gap_report.py](gap_report.py)가 낸다. 폴더 이름의 `-r2`는 도구를 고친 뒤 같은 이미지로 다시 잰 회차, `-fix`는 둘째 이미지, `-fix2`는 셋째 이미지다.

## 주행 중 도구 보정

판정 값은 바꾸지 않았다. 도구가 판정 규칙이 뜻하는 표본을 뜨지 못한 두 곳을 고쳤고, 고치기 전의 결과는 통과 증거로 쓰지 않고 다시 쟀다.

1. W0의 `lb_log_503_then_101`(20:01). 첫 W0의 E4에서 LB 경유 핸드셰이크는 `101`이었고 팔로워 직접 핸드셰이크는 `503`이었지만 이 축이 거짓이었다. 기본 스택 `harness/nginx/nginx.conf`에는 `error_log` 지시어가 없어 nginx 기본 수준(`error`)으로 찍히므로, 판정기가 찾는 `[warn] ... upstream server temporarily disabled` 줄이 원래 남지 않는다. 판정 규칙('LB가 api-1의 `503` 뒤 api-2로 넘겨 `101`을 받았다')은 그대로 두고, 증거를 LB 로그 줄 또는 'LB 경유 핸드셰이크 앞뒤로 읽은 팔로워의 `handshake_refused{reason=relay_required}` 증가 1 이상과 LB `101`'로 넓혔다. 축 이름은 `lb_follower_503_then_101`로 바꿨다. `harness/nginx/nginx.conf`는 바꾸지 않았다. W0 전체를 `W0-r2`로 다시 돈다.
2. W5의 두 창(주행 전, 20:00). `judge --experiment W5`의 미러 신선도 창이 'kill + RTO'부터였는데, 그 뒤 옛 리더가 합류하기까지는 팔로워 응답이 없어 표본이 늘 판정을 채우지 못한다. 설계 7-2의 '합류 뒤'대로 `rejoin.json`의 합류 시각부터 보게 했다(`--rejoin-at`). '팔로워였던 동안 `degraded`가 한 번도 1이 아니다'의 창 시작도 스택 기동 시각에서 관찰자 기동 시각으로 옮겼다. 스택 기동과 P2 전환의 재기동은 팔로워 관찰 구간이 아니다. W5는 이 보정 뒤에 처음 돈다.
3. 관찰자 하트비트와 종료 코드(20:10, W2 첫 주행 뒤). 첫 W2에서 세 관찰자 모두 약 40초마다 STOMP `ERROR` 뒤 `1002`로 끊겼다. 관찰자가 CONNECT에서 `heart-beat:10000,10000`을 약속하고 하트비트를 보내지 않아 서버 내장 브로커가 닫은 것이다. 그래서 리더 관찰자의 `leader_watch_kept_connection`이 거짓이었고, 재연결마다 기준점이 새로 잡혀 결번 관찰도 흐려졌다. 관찰자(`harness/load/ws_relay_watch.js`)가 5초마다 하트비트(줄바꿈 한 줄)를 보내게 했다. 또 k6의 `close` 이벤트는 서버의 종료 코드를 싣지 않고 같은 사건의 `error` 문자열(`websocket: close 1013: relay_unavailable`)에만 남아 `follower_watch_1013`이 0이었다. 종료 코드를 그 문자열에서 읽게 했다. 첫 W2의 팔로워 관찰자 오류 목록에는 `close 1013: relay_unavailable`이 11:07:07에 있었다.
4. append p99(20:10, W2 첫 주행 뒤). 기본 스택은 저널 append 타이머(`exchange.journal.append`)에 히스토그램 버킷을 내지 않아 `append_p99_le_1_5x`의 두 p99가 빈 결과였고, 판정기는 빈 결과를 0으로 읽었다(축은 창 앞 p99가 0보다 커야 해서 거짓). 중계 오버레이(`harness/docker-compose.relay.yml`)의 두 앱 노드에 이 타이머의 버킷만 켜는 `SPRING_APPLICATION_JSON`을 더했고(관측만 바뀐다), 판정기는 빈 결과와 NaN을 `None`으로 읽게 했다. 같은 이유로 W1의 `dropped_0`은 두 노드의 유실 계수 계열이 있어야 참이고, W4의 지연 최댓값도 계열이 없으면 거짓이다.

첫 W2(`W2/`)의 나머지 축은 참이었다(접수율 1.106, 리더 5xx 0, 강등 0, 두 노드 연결 재개 12.4초, 팔로워 중계 불능 7.9초 뒤, 모든 관찰자 수신 재개, 신선도 122쌍 모두 참). 이 회차는 도구 결함이 있던 회차라 판정 증거로 쓰지 않고 `W2-r2`로 다시 잰다.

## 코드 결함과 수정 (20:45~)

첫 이미지(`sha256:267fc6de…`)로 W1·W3·W4·W5를 돌린 결과 W3·W4가 같은 축 하나로 FAIL이었다. `freshness_during_and_60s_after_all_ok`에서 표본 하나씩이 판정을 채우지 못했다. W3은 `docker pause` 1.2초 뒤 리더 표본 `(5, 681)`에 대해 500ms 뒤 팔로워가 `(5, 675)`를 냈고, W4는 경보 2.1초 뒤 리더 `(5, 716)`에 대해 팔로워가 `(5, 705)`를 냈다. 팔로워가 멈춘 미러를 `seq`≠0 기준점으로 낸 것이다. 원인은 미러 사용 조건이다. 소유 노드의 발행이 조용히 멈추면(브로커 멈춤, 메모리 경보) 구독 연결은 붙어 있고, '최근 5초에 하트비트 3개'라는 닿음 조건은 셋째 하트비트가 창을 벗어날 때까지 약 3초 더 참이다. 그동안 미러는 멈춘 값인데 조건 셋을 모두 채운다. 설계 5절의 '클라이언트가 멈춘 미러를 기준점으로 받지 않는다'가 이 3초 동안 성립하지 않았다. 자산·멱등·펜싱에는 닿지 않는다(REST 호가 스냅샷의 신선도 문제이고, 클라이언트는 다음 실시간의 결번으로 알아채 다시 맞춘다). 그러나 관문을 막으므로 고쳤다. W2(정지)는 구독 연결이 곧바로 끊겨 미러를 쓰지 않으므로 이 경로를 타지 않았다.

고친 것. 비소유 노드는 가장 최근 소유 노드 하트비트가 `exchange.ws.relay.mirror-max-silence`(기본 400ms) 안에 왔을 때만 미러를 쓴다(`OwnerBeats.heardWithin`, `MarketViews`). 이 조건이 뜻을 가지려면 하트비트가 더 자주 와야 해서 `owner-heartbeat` 기본값을 1초에서 200ms로 줄였고, 간격의 두 배가 `mirror-max-silence`를 넘으면 기동을 멈춘다(`OwnerBeats.requireFreshInterval`). WS 게이트의 중계 불능 판정(닿음 5초에 셋, TTL + 3초)은 그대로다. 판정 규칙(500ms 앞 리더 표본 이상이거나 `seq` 0)과 판정 창은 바꾸지 않았다. 400ms는 표본기의 500ms 간격에서 리더 응답 시간을 뺀 여유 안에 들도록 AI가 정했고 사용자 확인 대상이다. 계약은 API 설계 v2.58 §4-A-2·§5-1과 패치 노트에 같은 변경으로 적었다.

시험. `MarketMirrorTest`에 두 사례를 더했다. 200ms 간격 하트비트 뒤 발행이 멈추면 닿음은 아직 참인데 401ms 뒤 `seq` 0이고 `fallback{reason=owner_silent}`가 1 오른다(대조군: 나이를 보지 않는 판은 같은 시각에 멈춘 미러를 낸다). 간격의 두 배가 `mirror-max-silence`를 넘는 설정은 기동을 거부한다. `BrokerRelayIntegrationTest`의 원격 하트비트를 200ms로, 리더 하트비트 간격 단언을 200ms 중앙값으로 고쳤다. 나이 검사를 끄는 변이에서 새 사례가 실패하는 것을 보고 되돌렸다(SHA-256 같음). 관련 여섯 클래스(24개)가 통과했고 `server` 전체 빌드를 다시 돌렸다(결과는 아래 '결과'). 새 이미지로 W0~W5를 모두 다시 잰다(폴더 `<실험>-fix`). 첫 이미지의 결과는 기록으로 남긴다.

## 수정 이미지 주행 중 도구 보정 (21:30)

5. W4의 '경보 동안' 창 끝(수정 이미지 W4-fix 뒤). `follower_book_seq0_during_alarm`은 경보 시작 8초 뒤부터 '경보 끝'까지 팔로워 응답이 모두 `seq` 0이어야 한다. 판정기는 창의 끝을 되돌리기 명령(`rabbitmqctl ... 192MiB`)이 돌아온 시각으로 잡았는데, 이 명령은 부른 뒤 1~3초 걸려 돌아오고 경보는 그 사이에 풀린다. 또 표본 쌍의 시각은 리더 표본 시각이고 팔로워 표본은 0.5초 뒤에 뜬다. W4-fix에서는 되돌리기 명령을 부른 21:26:21 뒤, 명령이 돌아오기 직전 리더 표본을 뜬 쌍 하나(리더 `(5, 1069)`, 팔로워 `(5, 1070)`)가 창에 들어와 이 축이 거짓이었다. 하트비트 간격을 줄여 경보가 풀린 뒤 미러가 0.4초 안에 다시 쓰이게 된 것이 이 경계를 드러냈다. 실행기(`s6-relay-outage.ps1`)가 되돌리기 명령을 부른 시각을 `restoreAt`으로 남기고, 판정기는 창의 끝을 그 시각에서 0.5초 앞당긴다(`alarm_samples_end`, 시험 한 사례). 판정 규칙과 다른 창은 그대로다. W4를 `W4-fix2`로 다시 잰다.

W4-fix의 다른 FAIL 축 `append_p99_le_1_5x`(경보 동안 17.8ms, 창 앞 9.8ms, 1.82배)는 시간 축이다. 경보 동안 호스트 CPU 표본이 28~51%로 창 앞(20~44%)보다 높았다. 위 규칙대로 같은 조건으로 다시 잰다(`W4-fix2`). 첫 이미지 W4에서는 8.1ms·7.2ms(1.12배)였다.

## 수정 이미지 주행의 남은 FAIL과 둘째 수정 (21:55)

수정 이미지(`sha256:d5981fc5…`)로 돈 결과는 W0-fix·W1-fix·W3-fix가 PASS, W2-fix·W4-fix·W5-fix가 FAIL이었다. W4-fix는 위 5의 도구 결함과 시간 축이다. 나머지 둘은 이렇다.

6. W2-fix `connected_back_within_15s`(브로커 시작 뒤 두 노드 네 연결이 모두 붙기까지 16.5초). 앱 로그로 보면 `docker start`가 돌아온 12:44:03 뒤 RabbitMQ가 STOMP를 받기까지 약 8.5초가 걸렸고(첫 연결 12:44:11.7), api-2의 발행 연결은 12:44:05.7에 거절된 뒤 재연결 간격 10초(지터 0.8~1.2배)를 기다려 12:44:17.1에 붙었다. 재연결 상한 10초로는 브로커가 되살아난 뒤에도 최대 12초를 더 기다릴 수 있어, 브로커 기동 시간과 프로메테우스 2초 표본을 더하면 15초를 구조적으로 보장하지 못한다. 첫 이미지의 W2-r2(14.4초)는 재연결 시각이 맞아떨어진 것이다. 그 사이 팔로워의 WS는 닫혀 있으므로 판정을 늘리지 않고 재연결 상한 기본값을 3초로 줄였다(`WsRelayProperties.Reconnect`, 새 시험 `WsRelayPropertiesTest`). 설정 키와 판정 값은 그대로다.
7. W5-fix `KRW-XRP_epoch_rise_once_within_rto_plus_5s`. 세 마켓 모두 epoch가 한 번(5 -> 6) 올랐고 오른 뒤 결번은 0, 살아남은 관찰자의 재연결 0, 중계 불능 0이었다. KRW-BTC·KRW-ETH의 새 epoch 첫 메시지는 kill 뒤 5.1초에 왔지만 KRW-XRP는 14.7초에 왔다(한도 RTO 5.05초 + 5초). E4 실험 스크립트가 첫 체결(RTO)을 확인한 0.45초 뒤 드레인을 위해 봇을 멈추고, 판정이 끝난 뒤 봇을 다시 띄우기 때문이다. 그 0.45초 사이에 XRP 주문이 없어 새 리더가 XRP를 게시할 일이 없었고, 첫 XRP 메시지는 봇이 다시 뜬 12:48:20.9에 왔다. 중계 결함이 아니라 판정 창 동안 부하가 있어야 한다는 전제가 실험 절차에서 깨진 것이다. 판정 값은 그대로 두고, 중계 주행의 E4에서만 `e4-leader-kill.ps1`이 드레인 전에 부하를 6초 더 두게 했다(`CHAOS_E4_HOLD_LOAD_SEC`, `adr0022-stage5-chaos.ps1 -Relay`가 준다, 기본 0이라 다른 관문은 종전과 같다).

재연결 상한을 바꿨으므로 이미지를 다시 굽고 W0~W5를 모두 다시 잰다(폴더 `<실험>-fix2`). 수정 이미지의 결과는 기록으로 남긴다.

8. 재연결 상한을 3초로 줄인 판의 서버 빌드에서 `RelayDegradedGateTest`가 실패했다(22:05). 브로커 애플리케이션을 멈춘 뒤 팔로워가 15초 안에 `link_down`이 되지 않았다. 원인은 `BrokerLink`의 닫힘 처리다. TCP만 붙고 STOMP `CONNECTED`를 받기 전에 닫힌 연결 시도도 '끊김'으로 알려, 구독 쪽(`OwnerBeats`)이 끊긴 시각을 시도마다 새로 적었다. 시험의 브로커는 Docker Desktop 포트 전달을 거치므로 브로커 애플리케이션이 멈춰도 TCP 연결은 받아 준다. 재연결 간격이 5초보다 길던 판에서는 그 틈에 5초가 차서 `link_down`이 켜졌고, 3초로 줄이자 끝내 켜지지 않았다. 브로커 앞에 TCP를 받아 주는 장치(TCP 로드밸런서)가 있거나 기동 중인 브로커가 `CONNECTED` 전에 닫으면 운영에서도 같은 일이 난다. 끊김은 `CONNECTED`까지 받은 연결이 닫혔을 때만 알리게 고쳤다(`BrokerLink.linkClosed`). 새 시험 `BrokerLinkAbortTest.closeBeforeConnectedIsNotReportedAsADisconnect`(TCP를 받고 곧바로 닫는 가짜 상대 앞에서 다섯 번 시도해도 끊김 알림 0, 대조군으로 `CONNECTED`를 받은 연결의 닫힘은 한 번씩)를 더했고, 고치기 전 조건으로 되돌리는 변이에서 이 시험이 실패하는 것을 보고 되돌렸다(SHA-256 같음). 관련 아홉 클래스(43개)가 통과했다.

## 결과 (23:14 종료)

**S6 관문의 스택 판정 W0~W5를 셋째 이미지(`sha256:40a87f4a…`, 참조 태그 `exchange-chaos-app:s6ref`)에서 모두 PASS로 통과했다(로컬 판정).** 그 이미지는 이 주행에서 찾은 코드 결함 셋을 고친 판이다. 첫 이미지(`267fc6de…`, 태그 `s6r0ref`)와 둘째 이미지(`d5981fc5…`, 태그 `s6r1ref`)의 FAIL은 아래 이력에 남긴다. 어느 실험에서도 자산·원장 불변식 위반, 구 epoch 쓰기, 세 원천의 주문 `500`, 리더 강등, 리더 5xx는 없었다(공통 판정). W6·W7은 P3가 필요해 S7 관문이다(N/A).

| 실험(셋째 이미지) | 판정 | 값 |
|---|---|---|
| W0 기본 모드 회귀(`W0-fix2`) | PASS | E4 RTO 5.547초, epoch 5 -> 6, 합류 12.9초. E7 RTO 10.040초. 두 실험 뒤 팔로워 api-1의 호가·티커 각 10회 모두 `seq: 0`·리스 epoch 6, 직접 핸드셰이크 `503`·`Retry-After: 5`, 리더 `101`, LB `101`(그 핸드셰이크 앞뒤 팔로워 `relay_required` 거절 +1), 팔로워 `gate.open` 0, 리더 1. 공통·파티션 축 참 |
| W1 중계 기본(`W1-fix2`) | PASS | 600초. 세 관찰자 모두 KRW-BTC·ETH·XRP 호가 실시간 7,160·5,812·4,556건, 체결 5,421·2,892·3,178건, 호가·티커 결번 0, epoch 하나(5). 두 노드 관찰자 호가 집합 차이 0.0, 중계 유실 0(계열 12), 연결 내내 1, 중계 불능 내내 0, 중계 지연 p99 30.7ms, 신선도 600쌍 모두 참(팔로워 `seq`≠0 100%) |
| W2 브로커 정지(`W2-fix2`) | PASS | 접수율 0.994, 리더 5xx 0, 강등 0, append p99 8.3ms(창 앞 12.2ms). 정지 2.0초 뒤 연결 0, 팔로워 관찰자 1013은 5.3초 뒤(중계 불능 게이지는 2초 표본으로 8.0초), 거절 `503` 10회. 브로커 시작 8.4초 뒤 네 연결 1, 8.4초 뒤 모든 관찰자 재수신. 리더 관찰자 끊김 0. 신선도 122쌍 모두 참 |
| W3 브로커 멈춤(`W3-fix2`) | PASS | 접수율 1.015, 5xx 0, 강등 0, p99 6.8ms(창 앞 8.5ms). 팔로워 중계 불능 16.0초, 1013 13.6초, 앱이 브로커 하트비트 부재로 연결을 끊은 것은 18.0초(표본). 재개 3.9초 뒤 연결 1. 힙 최댓값 67.8MB(창 앞 69.9MB). 신선도 100쌍 모두 참 |
| W4 메모리 경보(`W4-fix2`) | PASS | 접수율 0.972, 5xx 0, 강등 0, p99 6.9ms(창 앞 8.4ms). 경보 동안 구독 연결 1 유지, 발행 `write_timeout` 6·`disconnected` 3,668. 팔로워 `owner_silent` 16.0초(주입 명령 시작 기준, 명령이 약 2초 걸림), 1013 15.6초. 경보 8초 뒤부터 끝까지 팔로워 호가 24쌍 모두 `seq: 0`, `fallback{owner_silent}` +403. 되돌린 뒤 2.2초에 연결 1, 팔로워 게이트 다시 열림, 되돌린 뒤 60초 팔로워 지연 최댓값 0.065초. 신선도 94쌍 모두 참 |
| W5 리더 kill 중계(`W5-fix2`) | PASS | RTO 5.153초, epoch 5 -> 6, 합류 12.7초. 살아남은 api-2 관찰자 재연결 0, 팔로워였던 동안 중계 불능 0. 새 epoch 첫 메시지 BTC·ETH·XRP가 kill 뒤 5.2·5.6·5.2초(한도 10.15초), 오른 뒤 결번 0. 합류한 api-1 게이트 열림, 합류 뒤 신선도 27쌍 모두 참. 두 노드 `gate.open` 1·`101`, LB `101`. 공통·파티션 축 참 |

### 주행 이력

| 이미지 | 실험 | 판정 | 요지 |
|---|---|---|---|
| 첫째 `267fc6de` | W0 | FAIL(도구) | E4의 `lb_log_503_then_101`만 거짓(보정 1), E4 RTO 6.302초. E7은 PASS(RTO 11.467초) |
| | W2 | FAIL(도구) | 관찰자 하트비트 누락과 종료 코드, 빈 p99(보정 3·4) |
| | W2-r2 | PASS | 도구 보정 뒤. 재연결 14.4초 |
| | W1 | PASS | 결번 0, 지연 p99 31.5ms, 신선도 600쌍 |
| | W3 | FAIL(코드) | 멈춤 1.2초 뒤 팔로워가 리더보다 뒤처진 `seq` 675를 냄(결함 1) |
| | W4 | FAIL(코드) | 경보 2.1초 뒤 같은 모양(`seq` 705 < 716, 결함 1) |
| | W5 | PASS | RTO 5.254초 |
| | W0-r2 | PASS | 보정 1 뒤. E4 5.467초, E7 10.114초 |
| 둘째 `d5981fc5` | W0-fix·W1-fix·W3-fix | PASS | 결함 1을 고친 판. W3-fix의 LB 관찰자에 결번 1건(아래 '결번 관찰') |
| | W2-fix | FAIL(코드) | 브로커 시작 뒤 연결까지 16.5초(결함 2) |
| | W4-fix | FAIL(도구·시간) | 경보 창 끝의 표본 하나(보정 5), append p99 1.82배 |
| | W5-fix | FAIL(절차) | XRP 새 epoch 첫 메시지 14.7초(보정 6·7) |
| 셋째 `40a87f4a` | W0~W5 `-fix2` | 모두 PASS | 위 표 |


### 코드 결함 셋

1. 발행이 조용히 멈춘 소유 노드의 미러(위 '코드 결함과 수정'). 비소유 노드는 가장 최근 소유 노드 하트비트가 400ms 안일 때만 미러를 쓰고, 하트비트 간격 기본값을 200ms로 줄였다.
2. 재연결 상한(보정 6). 기본값 10초를 3초로 줄였다.
3. 연결 전 닫힘을 끊김으로 알림(보정 8). `CONNECTED`까지 받은 연결의 닫힘만 알린다.

셋 다 자산 보존·주문 멱등·펜싱 경로를 바꾸지 않는다. 바뀐 것은 비소유 노드의 호가·티커 REST와 구독 스냅샷의 미러 사용 조건, 중계 연결의 재연결 간격과 끊김 판정이다. 셋째 이미지 판에서 `server`의 `.\gradlew.bat build`가 22분 45초에 통과했다. app 200개 클래스 994개 시험(병합 트리의 989개에 이 주행에서 더한 다섯)이 실패·오류·건너뜀 없이 통과했고, engine 67개는 바뀌지 않아 UP-TO-DATE였다. 빌드 로그의 `localhost:5432`·`55432` JDBC 주소는 0건이다. 결함 1의 판(둘째 이미지)도 같은 빌드를 23분 20초에 통과했다(app 991개).

### 결번 관찰

S5 첫 주행의 k6 WebSocket orderbook 결번(여덟 번 가운데 한 번, 4건, 역순 폐기 0)의 원인을 이 주행에서 관찰했다. 세 이미지의 관찰자 주행 16번(WS 연결 74회)에서 호가 실시간 315,176건과 티커 실시간 197,413건을 받았고 결번은 1건, 역순 폐기는 0건이었다. 중계를 거친 팔로워 관찰자와 리더 관찰자가 받은 호가 집합은 W1에서 같았다(차이 0.0과 0.006%). 중계 경로의 유실은 보이지 않았다.

결번 1건은 둘째 이미지 W3-fix의 LB 관찰자에서 났다. 이 관찰자는 팔로워(api-2)에 붙어 있다가 브로커 멈춤으로 1013을 받고 12:20:55에 LB를 거쳐 리더(api-1)로 다시 붙었다. 다시 붙은 직후 구독 즉시 스냅샷으로 KRW-BTC 호가 `seq` 831을 받고, 다음 실시간으로 833을 받았다. 832는 같은 시각 api-1 관찰자가 받았다. 서버 코드를 보면 구독 즉시 스냅샷(`OrderbookSnapshotOnSubscribe`)은 STOMP `SUBSCRIBE`의 `SessionSubscribeEvent`에서 나간다. Spring은 이 사건을 `SUBSCRIBE` 메시지를 수신 채널(스레드 풀)에 넘긴 직후 내고, 내장 브로커의 구독 등록은 그 풀의 다른 스레드에서 비동기로 끝난다. 스냅샷이 카운터를 읽은 뒤(831), 구독 등록이 끝나기 전에 게시된 832는 그 세션에 전달되지 않고, 등록 뒤의 833부터 온다. S5 첫 주행의 결번도 역순 폐기 없이 났으므로 같은 경합으로 보는 것이 맞다(추정, 그 주행은 결번의 seq를 남기지 않았다). 이 경합은 S6 이전부터 있던 동작이고 모드와 무관하다. 호스트가 바쁠수록 등록이 늦어 창이 넓어진다(S5 첫 주행 직전에는 다른 작업 트리의 빌드와 Testcontainers 60여 개가 돌았다). 계약상 클라이언트는 결번을 보면 REST로 다시 맞추므로 상태가 틀어지지는 않는다. 다만 k6 `ws_seq_gap{phase:steady,dest:orderbook}==0` 같은 판정을 드물게 깨고, W1·W5처럼 구독 시작 뒤 결번 0을 요구하는 축을 흔들 수 있다. 고치려면 스냅샷을 내장 브로커의 구독 등록 뒤에 읽어 보내야 한다(예: 수신 채널의 `afterMessageHandled`에서 `SimpleBrokerMessageHandler`가 처리한 `SUBSCRIBE`에만 스냅샷을 보낸다). 관문을 막지 않았고 S6의 변경이 아니어서 이 주행에서는 고치지 않았다.

### 조건과 정리

같은 PC에서 Patroni 실험(프로젝트 `exchange-patroni`)이 돌았다 멈췄다 했다. 단계 시작 표본에서 `patroni-*` 컨테이너는 0~19개였고, 단계 동안 5초 간격 호스트 전체 CPU 평균은 15~30%, 최댓값은 72.3%(W2-fix2)였다(`cpu-<단계>.csv`, `host-<단계>.json`). 셋째 이미지 판의 판정 실험은 시간 축에서 FAIL한 것이 없어 잡음 재측정은 하지 않았다. 보존 볼륨 `exchange-chaos_chaos-pgdata`(생성 2026-09-13T11:42:01Z)와 `failstop-keep-chaos-pgdata-20260924`(2026-09-23T15:47:36Z)의 생성 시각은 시작·끝과 모든 단계 앞뒤에서 같았다([volumes.txt](volumes.txt)). Final 단계(23:14)에서 `-p exchange-pml down -v --remove-orphans` 뒤 남은 `chaos-*`와 관찰자 컨테이너, `exchange-pml` 볼륨·네트워크는 0이다([docker-ps-after.txt](docker-ps-after.txt), [volume-ls-end.txt](volume-ls-end.txt)). `exchange-chaos` 프로젝트는 올리거나 내리지 않았다. `:local`(`611169b8`)과 `s5ref`(`1696fdb0`)는 그대로다. 컨테이너 로그 원문은 저장소 밖 `%TEMP%\pml-s6-logs\<단계>`에 있고 해시는 각 `container_logs.json`에 있다.

### 한계

- 로컬 Docker Desktop 단일 호스트의 P2 결과다. 파티션은 `matching-engine` 하나이고 AWS나 P3를 대신하지 않는다. 브로커와 앱은 같은 `chaos-net`에 있다.
- 판정 값(접수율 90%, 복구 15·20·30초, 지연 p99 250ms, 신선도 여유 500ms)과 이 주행에서 바꾼 기본값(하트비트 200ms, 미러 나이 400ms, 재연결 상한 3초), 도구 보정 여덟은 AI가 정했고 사용자 확인 대상이다. 보정 가운데 판정 규칙의 뜻을 바꾼 것은 없다고 판단하지만, 보정 1(W0 LB 증거)·7(W5 부하 유지)은 증거와 절차를 바꿨다.
- 중계 불능·재연결 시각은 프로메테우스 2초 표본으로 읽어 2초 안팎의 오차가 있다. W4의 '경보 시작'은 주입 명령을 부른 시각이고 명령은 약 2초 걸렸다.
- 구독 즉시 스냅샷의 경합(위 '결번 관찰')은 고치지 않았다.
- 세 수정은 `server` 전체 빌드와 관련 시험으로 확인했다. 앞 관문(S4b·S5)의 스택 실험은 셋째 이미지로 다시 돌리지 않았고, 셋째 이미지에서 기본 모드로 돈 것은 W0의 E4·E7이다. 웹 코드는 바꾸지 않았다.
