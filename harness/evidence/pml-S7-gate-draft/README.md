# ADR 0034 S7 관문 스택 실험 초안 (Q0~Q16)

상태: **모두 NOT RUN**. 이 폴더는 S7 코드 단계(2026-09-25)에서 준비한 실행기와 판정 규칙의 초안이다. 실행 결과, 판정 파일, 측정값은 없다. S6 관문이 끝난 뒤 메인 세션이 돌리고, 돌릴 때 이 폴더를 `harness/evidence/pml-S7-<YYYYMMDD-HHMM>/`로 옮겨 적는다. 설계는 [S7 설계](../../../docs/reference/architecture/마켓분할_리더_S7_설계.md) 5절이고, 판정 값은 설계를 맡은 AI가 정했으며 사용자 확인 대상이다. 서버 관문 시험(설계 6-2)은 이 폴더와 별개로 Testcontainers 일회용 DB에서 돌았다.

## 안전 수칙

설계 5-0을 그대로 따른다. 실험 스택은 compose 프로젝트 `exchange-pml`, 앱 이미지 태그 `exchange-chaos-app:pml`로만 띄우고 `exchange-chaos-app:local`을 덮어쓰지 않는다. 보존 볼륨 `exchange-chaos_chaos-pgdata`와 백업 볼륨 `failstop-keep-*`는 마운트·삭제·수정하지 않는다. 프로젝트 `exchange-chaos`에는 `down -v`, `volume rm`, `up`을 하지 않고, `down -v`는 `-p exchange-pml`이 붙은 명령(`lib.ps1`의 `Invoke-ComposeQuiet`)으로만 한다. 비밀 파일(`harness/.env.chaos`, `harness/load/secrets/*.json`)은 경로로만 넘긴다. Q8은 두 부하 루프의 키를 저장소 밖 임시 폴더에 쓰고 끝나면 지운다. S6 관문 실험이 같은 이름의 컨테이너(`chaos-*`)를 쓰므로 시작 전에 `docker ps -a --filter "name=chaos-"`가 비어 있어야 하고, 실행기도 이것을 확인한다.

## 준비

설계 5-1의 환경 변수를 둔 뒤 `up.ps1 -P3 -Obs`로 이미지를 한 번 굽는다. `-P3`는 `CHAOS_COMPOSE_OVERLAYS`를 `harness/docker-compose.relay.yml;harness/docker-compose.p3.yml`로 채운다(중계 오버레이 뒤에 P3 오버레이. 순서가 바뀌면 `chaos-lb`가 `nginx.relay.conf`로 뜬다).

```powershell
$env:CHAOS_PROJECT = 'exchange-pml'
$env:COMPOSE_PROJECT_NAME = 'exchange-pml'
$env:CHAOS_APP_IMAGE = 'exchange-chaos-app:pml'
$env:CHAOS_SPRING_PROFILES = 'dev,chaos-monkey,prod-timeouts'
$ev = 'C:\dev\exchange-pml7\harness\evidence\pml-S7-<YYYYMMDD-HHMM>'
$env:CHAOS_EVIDENCE_DIR = "$ev\chaos-runs"
Remove-Item Env:CHAOS_COMPOSE_OVERLAYS -ErrorAction SilentlyContinue
pwsh -NoProfile -File harness/scripts/up.ps1 -P3 -Obs
# 실행기는 chaos- 컨테이너가 없어야 시작한다. 이미지를 구운 스택을 내린다. down -v 는 -p exchange-pml 을 붙인 명령으로만 한다
docker compose -p exchange-pml -f harness/docker-compose.chaos.yml -f harness/docker-compose.relay.yml -f harness/docker-compose.p3.yml --env-file harness/.env.chaos --profile obs down -v
Remove-Item Env:CHAOS_COMPOSE_OVERLAYS -ErrorAction SilentlyContinue
pwsh -NoProfile -File harness/evidence/pml-S7-gate-draft/run-gate.ps1 -EvidenceDir $ev
```

Q2·Q13의 웹 관찰은 `web/node_modules`가 있어야 하고 3000번 포트가 비어 있어야 한다. 조건이 맞지 않으면 실행기가 사유를 적고 W6·W7의 웹 축을 NOT RUN(웹 관찰)으로 둔다. 실행기는 `NEXT_PUBLIC_DATASOURCE=http`, `NEXT_PUBLIC_API_BASE=http://localhost:18080`, `NEXT_PUBLIC_WS_BASE=ws://localhost:18080`으로 웹을 한 번 빌드한다. 산출물 `web/out`은 `harness/scale/web_out_server.py`가 3000번 루프백에서 낸다. `web/next.config.ts`가 `output: "export"`라 `next start`는 기동하지 않는다. 포트가 3000인 이유는 dev 프로파일의 REST 교차 출처 허용이 `http://localhost:3000` 하나이기 때문이다. 기동 로그는 실험 폴더의 `web_build.log`, `web_serve.err`다. 관찰기 `s6-web-resync.mjs`는 playwright가 내려받은 chromium이 없으면 설치된 Chrome, Edge 순으로 쓰고, 쓴 브라우저를 `web_resync.json`의 `browser`에 적는다. 2026-09-26 이 PC에는 내려받은 chromium이 없고 Chrome이 있었다.

## 실험과 실행 경로

`run-gate.ps1`은 실험마다 `harness/scripts/s7-p3-gate.ps1 -Experiment <Q>`를 부른다. 실험마다 새 스택을 세우고 Q0 준비를 한 뒤 실험하고, 봇을 멈춰 비운 뒤 사후 판정을 뜨고 내린다. 결과는 실험 폴더의 `gate.json`이다.

| 실험 | 실행 경로 | 판정 파일 |
|---|---|---|
| Q0 준비 | 전환 도구 `protocol_latch.py transition --target 3 --latch-band`, `lib.ps1 Invoke-P3Preflight` | `protocol.json`, `p3-preflight.json` |
| Q1 E12 이중 지출 | `s7_double_spend.py --rounds 1000` → `s7_axes.py double-spend` | `double_spend.json`, `double_spend_verdict.json` |
| Q2 E4 + W6 | `adr0022-stage5-chaos.ps1 -Experiments E4 -Protocol 3 -P3 -Resource matching-p1 -PartitionAxes -WsGateAxes -SurvivorLoadOps 20 -WebObserveUrl ...` | `runner/E4/` 아래 `verdict.json`, `p3_axes.json`, `relay_axes.json`(W6), `rejoin.json`, `ws_gate.json`, `web_resync.json` |
| Q3a E7 p1 소유자 pause | 실행기 `-Experiments E7 -Resource matching-p1` | `runner/E7/` |
| Q3b E7 두 파티션 소유자 pause | 실행기 `-Experiments E7 -Resource matching-p1 -ConsolidateOn chaos-api-1` | `runner/E7/consolidate.json`, `rto_matching-p0.json` |
| Q4 되돌리기와 재진입 | 봇 부하 5분 → `transition --target 2 --allow-downgrade` → P2 봇 부하 5분 → 사후 판정 → `transition --target 3` → 사후 판정 | `protocol_down.json`, `protocol_reentry.json`, `segment.json`, `reentry/` |
| Q5 E5 분리 | 실행기 `-Experiments E5 -Resource matching-p1` | `runner/E5/` |
| Q6 E2 DB 절단 | 실행기 `-Experiments E2 -Resource matching-p1` | `runner/E2/` |
| Q7 E6 폭주 중 kill | 실행기 `-Experiments E6 -Resource matching-p1` | `runner/E6/` |
| Q8 수수료 행 결합 | `s7_fee_coupling.py --pause-s 3 --latency-ms 100` → `s7_axes.py fee-coupling` | `fee.json`, `fee_verdict.json` |
| Q8c 대조 칸(기록) | `s7_fee_coupling.py --control` | `fee.json` |
| Q9 부하 중 수동 인계 | `s7_axes.py relocate` | `relocate.json` |
| Q10 한 마켓 치명 | `s7_market_fatal.py --market KRW-ETH` → `s7_axes.py market-fatal` | `fatal.json`, `fatal_verdict.json` |
| Q11 두 노드 콜드 재시작 | `s7_axes.py cold-assign` | `cold.json` |
| Q12 처리량(기록) | `roundtrip_probe.py --rate 600 --protocol-before-traffic 2·3`, 칸 P2a, P3a, P2b, P3b | `P2a.json` 등 |
| Q13 W7 두 파티션 수신 | api-1 하나에 붙은 k6 관찰자 + 웹 관찰 10분 → `s6_relay_axes.py judge --experiment W7` | `watch.json`, `web_resync.json`, `w7.json` |
| Q14 C22' 비용 | `s7_c22_cost.py --rows 1000000`(폐기용 PostgreSQL `pml-probe-s7q14-*`) | `c22_cost.json` |
| Q15 힌트 없는 쓰기(기록) | 12마켓 k6 `MARKET_HINT=0` 3분 → `s7_axes.py lb-hintless` | `hintless.json` |
| Q16 두 파티션 인계 | `s5_route_probe.py q16` → `s7_axes.py handoff` | `q16.json`, `handoff.json` |

실행기 실험(Q2·Q3a·Q3b·Q5·Q6·Q7)은 `adr0022-stage5-chaos.ps1`이 스택, Q0 준비, 공통 판정(`s3_common_axes.py judge --protocol 3 --resource matching-p1`, 파티션 축), P3 공통 축(`p3_axes.json`), 합류(E4), 내리기를 한다. 그 밖의 실험은 `s7-p3-gate.ps1`이 사후 판정(`post_run.json`, `summary.json`, `segment.json`)을 직접 뜬다.

## 판정 규칙

축 이름은 판정 파일의 키다. 축마다 참·거짓이고 읽지 못한 값은 참으로 세지 않는다. 양성 축(판정력의 전제)이 모자라면 NOT RUN이다.

- **공통(모든 P3 실험).** 영향 파티션 RTO 30초 미만(T5를 그 파티션 마켓으로 한정), 희생 epoch 집합의 구 쓰기 증분 0(`victim_writes.json`, 주입 직전 `-Resource` 행 holder가 쥔 파티션 epoch 전부), 구간 경계 증분 0(`segment.json`, 전환 직후 표본과 실험 끝 표본이 이력 행마다 같음), 드레인 뒤 `summary.sql` CRITICAL·HIGH PASS, 정산 23514 0, 생존 파티션 마켓 ACK 간격 최대가 기준선 + 1초 이하(`ack_interval.json`, 창 안 NEW 마켓마다 10건 이상이 양성 축), S4b~S6 공통 판정. 희생 노드가 두 파티션을 쥐었으면(Q3b) ACK 간격은 적기만 하고 다른 파티션의 RTO(`rto_matching-p0.json`)를 더 본다.
- **Q0.** `transition_ok`, `segment_sampled_after_cutover`, `leader_protocol_3`, `api1_holds_p0`, `api2_holds_p1`, `broker_healthy`, `apps_broker_mode`, `lb_partition_relay_conf`, `preferred_settings`, `jdbc_toxiproxy_and_application_name`, `fee_income_shard1_equals_shard0`, `protocol_max_3_chaos-api-1`·`protocol_max_3_chaos-api-2`, `pre_authoritative_run_clean`. 사전 권위 run은 봇 부하 중이라 실험 판정과 같은 게이팅 술어로 보고 원 판정을 함께 적는다.
- **Q1.** `all_rounds_ran`, `exactly_one_success_every_round`, `insufficient_rejections_gt_0`, `order_insufficient_gt_0`, `settlement_23514_delta_0`, `fatal_demotions_0`, `server_errors_0`, `balance_restore_failures_0`, `post_C01_0`·`post_C14_0`·`post_C31_0`.
- **Q2.** 실행기 분리 판정(E4 판정, P3 epoch 규칙, 공통 판정, 파티션 축, W6, 합류, WS 게이트)에 `p1_rto_le_p2_max_plus_ttl`(10.507초 + 6초), `p0_epoch_unchanged`, `p3_epoch_rule`을 더한다. W6은 `s6_relay_axes.py judge --experiment W6`이고 웹 관찰 파일이 없으면 NOT RUN(웹 관찰)이다.
- **Q3a·Q5.** `api2_p1_demoted`(Q3a는 `api2_p0_not_demoted`도). **Q3b.** `api1_both_partitions_demoted`, `preferred_p1_before_p0`(`takeover_after_inject_s`), `p0_rto_lt_30s`.
- **Q4.** `rollback_ok`, `history_last_is_p2`, `api1_engine_leader`, `api2_follower`, `protocol_boot_2_*`, `shard1_unchanged_after_rollback`, `new_fees_on_shard0`, `post_rollback_integrity_and_segment`, `c22_c30_zero`, `reentry_ok`, `reentry_assignment_ok`, `post_reentry_integrity_and_segment`.
- **Q8.** `fee_row_held_by_paused_session`와 `p1_trades_during_pause_ge_1`이 양성 축이고, `krw_shard1_increased`, `api2_transient_exhausted_delta_0`, `api2_fatal_demotions_0`, `p1_settle_max_le_baseline_plus_1s`, `no_lease_handover`. 다섯 번 모두 잠금을 못 잡으면 NOT RUN이다.
- **Q9.** `two_moves`, 인계마다 `move<i>_rto_lt_30s`·`move<i>_old_epoch_writes_0`·`move<i>_to_expected_node`, `p0_ack_interval_ok`(인계마다 잰 ACK 판정이 모두 PASS), `p0_ack_measured`(양성 축), `final_assignment_ok`, `no_automatic_failback`.
- **Q10.** `injected_once`, `p1_writer_fatal_once`, `p0_no_demotion`, `breaker_not_open`, `p1_leader_again_within_30s`(치명 전 epoch보다 큰 epoch로 다시 ACCEPTING), `p0_ack_interval_ok`, `post_invariants_pass`.
- **Q11.** `preferred_assignment_without_relocation`, `matching-p0_first_trade_le_30s`·`matching-p1_first_trade_le_30s`, `non_preferred_acquire_in_2ttl_0`.
- **Q13.** W7 판정(`s6_relay_axes.py judge --experiment W7`)의 축. 한 연결로 두 파티션 마켓 수신, 두 파티션 epoch가 다름, 창 안 epoch 상승 0, 조밀 결번 0, 웹 관찰이 있으면 첫 적재 뒤 REST 0.
- **Q14.** `rows_ge_1m`·`runs_measured`(양성 축), `all_runs_le_1500ms`(권위 run과 같은 읽기 전용 반복 읽기 트랜잭션, `SET LOCAL jit = off`, `EXPLAIN (ANALYZE, BUFFERS)` 세 번).
- **Q16.** `s7_axes.py handoff`의 축. 요청 시점에 p1을 유효하게 쥔 노드가 api-2 밖이면 NOT RUN이다.
- **Q12·Q15·Q8c.** 기록이다. `gate.json`의 판정은 RECORD다.

## 알려진 한계

- 모든 실행 경로는 코드 단계에서 구문 분석과 순수 함수 시험(`harness/scale/test_s7_axes.py`, `test_s7_executors.py`, `test_stage5_runner_args.py`)만 거쳤다. 스택에서 돈 적이 없어 도커·HTTP·psql 배선의 오류는 첫 주행에서 드러날 수 있다.
- Q8의 pgrowlocks 표본이 20ms 간격(`\watch 0.02`)으로 수수료 행 잠금을 실제로 잡는지, Q10의 S7F01이 스택의 정산에서 치명으로 가는지(분류는 서버 시험 `InjectedMarketFaultClassificationTest`가 고정했다), 두 노드 동시 기동에서 재배치가 얼마나 자주 필요한지는 확인하지 않았다(설계 9절).
- Q12는 `roundtrip_probe.py`가 칸마다 `CHAOS_EXTRA_COMPOSE`로 스택을 세운다. 규모 오버레이(`docker-compose.scale.yml`)와 중계·P3 오버레이를 함께 쓴 적은 없다.
- 희생 epoch 집합의 구 쓰기 증분은 1초 간격 표본(`s7_axes.py epoch-sampler`)의 주입 뒤 첫 표본을 기준으로 삼는다. 주입과 첫 표본 사이(1초 이하)에 커밋된 옛 epoch 쓰기는 기준에 들어가 세지 않는다. E5·E7 스크립트의 주입 시각 기준선이 `-Resource` 파티션을 따로 본다.
- Q2의 웹 관찰은 실험 스크립트보다 먼저 띄워 300초 동안 돈다. kill이 그보다 늦으면 웹 축이 거짓이 된다.
