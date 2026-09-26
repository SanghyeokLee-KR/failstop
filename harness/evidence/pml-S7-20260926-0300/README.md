# S7 관문 폐기용 스택 실험 Q0~Q16: P3 파티션 리더 전환 (2026-09-26 03:00 KST~)

[S7 설계](../../../docs/reference/architecture/마켓분할_리더_S7_설계.md) 5절의 스택 실험을 S5·S6·S7을 병합한 트리(`C:\dev\exchange-pml`, 브랜치 `claude/per-market-leader`, HEAD `58469ef9`, 구독 스냅샷 결번 경합 수정까지)에서 돈 기록이다. 코드 단계가 남긴 초안 `harness/evidence/pml-S7-gate-draft/`(README와 `run-gate.ps1`)를 이 폴더로 옮겨 적었다. 이 절부터 '안전 수칙과 조건'까지는 주행 전에 고정한 조건과 판정 규칙이고, 실행 결과와 판정 표는 주행 뒤 '결과' 절에 적는다.

## 주행 전에 고정한 판정 규칙

축 이름은 판정 파일의 키다. 축마다 참·거짓이고 하나라도 거짓이면 그 실험은 FAIL이다. 읽지 못한 값(프로메테우스 무응답, 파일 없음, 빈 결과)은 참으로 세지 않는다. 양성 축(판정력의 전제, 예: 표본 수 하한)이 모자라면 NOT RUN이다. 실험 스크립트가 실행 전 조건(Q0 준비)을 채우지 못하면 BLOCKED이고, 돌리지 않은 실험은 NOT RUN이다. BLOCKED·NOT RUN·RECORD는 통과에 합산하지 않는다. 판정 값은 설계를 맡은 AI가 2026-09-25~26에 정했고 사용자 확인 대상이다(설계 10절, 12-2).

**공통 판정(모든 P3 실험, 설계 5-2, 하네스 검증설계 §3 P3 실험 문단).**

- 영향 파티션 RTO 30초 미만(T5를 그 파티션 마켓의 첫 체결로 한정).
- 희생 epoch 집합(주입 직전 `-Resource` 행 holder가 쥔 파티션 epoch 전부)의 구 쓰기 증분 0(`victim_writes.json`).
- 구간 경계 증분 0(`segment.json`. 전환 직후 표본과 실험 끝 표본에서 이력 행마다 네 표의 옛 구간 epoch 행 수가 같다).
- 부하를 멈추고 비운 뒤 사후 권위 run과 `summary.sql`의 CRITICAL·HIGH PASS. 여기에 C01·C02·C05·C13A·C14'·C22'·C29·C30·C31 위반 0이 들어간다.
- 정산 23514 0(두 노드 `exchange_settlement_insufficient_funds_total` 증분).
- 생존 파티션 마켓의 ACK 간격(`command_journal` NEW 행 `created_at` 이웃 간격) 최대가 주입 전 120초 기준선 최대 + 1초 이하(`ack_interval.json`). 판정 창은 주입부터 영향 파티션 첫 체결 + 10초, 양성 축은 창 안 생존 마켓마다 NEW 10건 이상이다. 희생 노드가 두 파티션을 쥐었으면(Q3b) 생존 파티션이 없으므로 적기만 하고 다른 파티션의 RTO(`rto_matching-p0.json`)를 본다.
- S4b~S6 공통 판정(`s3_common_axes.py judge --protocol 3`: 세 원천 주문 `500` 0, `recovery_runs` 불일치 0, 로그 문구, 창 밖 경보 0, 접수 경합 상한).

**Q0 준비(판정 전제, `p3-preflight.json`).** `transition_ok`, `segment_sampled_after_cutover`, `leader_protocol_3`, `api1_holds_p0`, `api2_holds_p1`(90초 안, 재배치 여부 기록), `broker_healthy`, `apps_broker_mode`, `lb_partition_relay_conf`, `preferred_settings`, `jdbc_toxiproxy_and_application_name`, `fee_income_shard1_equals_shard0`, `protocol_max_3_chaos-api-1`·`protocol_max_3_chaos-api-2`, `pre_authoritative_run_clean`. 사전 권위 run은 봇 부하 중이라 실험 판정과 같은 게이팅 술어(`Test-ExperimentIntegrity`)로 보고 원 판정을 함께 적는다(설계 12-2의 2). 하나라도 거짓이면 그 실험은 BLOCKED다.

**실험별 축(판정 중요 순서).**

- **Q1 E12 교차 노드 이중 지출**(`s7_double_spend.py --rounds 1000` → `s7_axes.py double-spend`). `all_rounds_ran`, `exactly_one_success_every_round`, `insufficient_rejections_gt_0`, `order_insufficient_gt_0`(주문 `400 insufficient_funds` > 0), `settlement_23514_delta_0`, `fatal_demotions_0`, `server_errors_0`, `balance_restore_failures_0`, `post_C01_0`·`post_C14_0`·`post_C31_0`. 여기에 공통 사후 판정(권위 run, `summary.sql`, 구간 경계 증분)을 붙인다.
- **Q2 E4 p1 소유자 kill + W6**(`adr0022-stage5-chaos.ps1 -Experiments E4 -Protocol 3 -P3 -Resource matching-p1 -PartitionAxes -WsGateAxes -SurvivorLoadOps 20 -WebObserveUrl ...`). 실행기 분리 판정(E4 판정, P3 epoch 규칙, 공통 판정, 파티션 축, W6, 합류 P3 판, WS 게이트)에 `p1_rto_le_p2_max_plus_ttl`(10.507초 + 6초 = 16.507초 이하, 설계 결정 11), `p0_epoch_unchanged`, `p3_epoch_rule`을 더한다. W6은 `s6_relay_axes.py judge --experiment W6`(기준은 S6 설계 7-2)이고 웹 관찰 파일이 없으면 NOT RUN(웹 관찰)이다.
- **Q3a E7 p1 소유자 pause**(api-2 45초 `docker pause`). 공통 판정에 `api2_p1_demoted`, `api2_p0_not_demoted`. **Q3b E7 두 파티션 소유자 pause**(두 파티션을 api-1로 모은 뒤 api-1 45초). `api1_both_partitions_demoted`, `preferred_p1_before_p0`(`takeover_after_inject_s`), `p0_rto_lt_30s`.
- **Q4 되돌리기(P3->P2)와 재진입**(P3 봇 부하 300초 → `transition --target 2 --allow-downgrade` → P2 봇 부하 300초 → 사후 판정 → `transition --target 3` → 사후 판정). `rollback_ok`, `history_last_is_p2`, `api1_engine_leader`, `api2_follower`, `protocol_boot_2_*`, `shard1_unchanged_after_rollback`, `new_fees_on_shard0`, `post_rollback_integrity_and_segment`, `c22_c30_zero`, `reentry_ok`, `reentry_assignment_ok`, `post_reentry_integrity_and_segment`.
- **Q5 E5 분리**(api-2 망 분리 60초). 공통 판정에 `api2_p1_demoted`. **Q6 E2 DB 절단**(`pg-api2` 단절 20초·블랙홀 20초). 공통 판정과 E2 종전 판정. **Q7 E6 폭주 중 kill**. 공통 판정과 E6 종전 판정.
- **Q8 수수료 행 결합**(`s7_fee_coupling.py --pause-s 3 --latency-ms 100` → `s7_axes.py fee-coupling`). 양성 축 `fee_row_held_by_paused_session`·`p1_trades_during_pause_ge_1`, 판정 축 `krw_shard1_increased`, `api2_transient_exhausted_delta_0`, `api2_fatal_demotions_0`, `p1_settle_max_le_baseline_plus_1s`, `no_lease_handover`. 다섯 번 모두 잠금을 못 잡으면 NOT RUN이다. Q8c(같은 사용자 집합 대조 칸)는 기록이다.
- **Q9 부하 중 수동 인계**(`s7_axes.py relocate`). `two_moves`, 인계마다 `move<i>_rto_lt_30s`·`move<i>_old_epoch_writes_0`·`move<i>_to_expected_node`, `p0_ack_interval_ok`, `p0_ack_measured`(양성 축), `final_assignment_ok`, `no_automatic_failback`.
- **Q10 한 마켓 치명**(`s7_market_fatal.py --market KRW-ETH` → `s7_axes.py market-fatal`). `injected_once`, `p1_writer_fatal_once`, `p0_no_demotion`, `breaker_not_open`, `p1_leader_again_within_30s`, `p0_ack_interval_ok`, `post_invariants_pass`.
- **Q11 두 노드 콜드 재시작**(`s7_axes.py cold-assign`). `preferred_assignment_without_relocation`, `matching-p0_first_trade_le_30s`·`matching-p1_first_trade_le_30s`, `non_preferred_acquire_in_2ttl_0`.
- **Q16 두 파티션 인계**(`s5_route_probe.py q16` → `s7_axes.py handoff`). 요청 시점에 p1을 유효하게 쥔 노드가 api-2 밖이면 그 회차는 NOT RUN이다.
- **Q13 W7 두 파티션 수신**(api-1 하나에 붙은 k6 관찰자와 웹 관찰 600초 → `s6_relay_axes.py judge --experiment W7`). 한 연결(`opens` 1)로 두 파티션 마켓 모두 호가 실시간 100건 이상·체결 10건 이상, 두 파티션 epoch가 다름, 창 안 epoch 상승 0, 조밀 결번 0, 웹 관찰이 있으면 첫 적재 뒤 REST 0. 결번 관찰은 아래 '결번 재측정'을 따른다.
- **Q15 힌트 없는 쓰기**(k6 `MARKET_HINT=0` 12마켓 180초 → `s7_axes.py lb-hintless`). 기록이다.
- **Q12 처리량**(`roundtrip_probe.py --rate 600`, P2a, P3a, P2b, P3b). 칸 값은 기록이다. 판정은 무결성 축(주문 `400`·`429`·`500`의 사유별 수, 불변식)이다.
- **Q14 C22' 비용**(`s7_c22_cost.py --rows 1000000`, 폐기용 PostgreSQL `pml-probe-s7q14-*`). 양성 축 `rows_ge_1m`·`runs_measured`, 판정 축 `all_runs_le_1500ms`.

**우선순위.** 시간이 모자라면 사용자가 정한 순서로 돈다. Q1(E12) → Q2(E4) → Q3a(E7) → Q4(되돌리기) → Q5(E5) → Q6(E2) → Q7(E6) → Q8(수수료 행 결합) → Q9(부하 중 수동 인계) → Q10(한 마켓 치명) → Q11(콜드 재시작) → Q12(P2·P3 처리량, 짧은 판) → Q13(WebSocket 중계와 결번). 그 뒤 Q3b, Q16, Q15, Q14. 돌리지 못한 실험은 NOT RUN으로 적는다.

**잡음.** 실험마다 앞뒤로 호스트 CPU(5초 평균)와 다른 프로젝트 컨테이너 수를 `host-<단계>.json`에, 주행 동안 5초 간격 CPU를 `cpu-<단계>.csv`에 남긴다. 시간 축(RTO, ACK 간격, 정산 지연)에서만 FAIL하고 그 시각에 호스트 CPU가 높았으면 같은 조건으로 한 번 더 잰다. 다시 잰 결과는 첫 결과를 지우지 않고 나란히 적는다(폴더 `<실험>-r2`).

**결번 재측정.** 구독 즉시 스냅샷이 내장 브로커의 구독 등록보다 먼저 seq를 읽던 경합을 `58469ef9`가 고쳤다. Q2·Q13의 관찰자 호가·티커 결번(`gaps`)을 노드·마켓별로 모으고, 결번이 나면 같은 시각의 다른 관찰자, 중계 유실 계수(`exchange_ws_relay_dropped_total{reason}`), 앱 로그를 대조해 원인이 게시 쪽인지 중계 쪽인지 WS 세션 쪽인지 적는다.

**결함 처리.** 코드 결함으로 FAIL이면 원인을 좁힌다. 자산 보존·멱등·펜싱에 영향이 있거나 관문을 막는 결함이면 고친 뒤 시험을 더하고 관련 시험과 `server` 전체 빌드를 돌려 이미지를 다시 굽고 해당 실험을 다시 잰다(폴더 `<실험>-fix`, 참조 태그 `s7ref-fix`). 도구(판정기·실행기) 결함이면 고친 뒤 다시 재고, 고치기 전 결과는 최신 통과 증거로 쓰지 않는다. 판정 값은 낮추지 않는다.

## 안전 수칙과 조건

설계 5-0을 따른다. compose 프로젝트 `exchange-pml`, 앱 이미지 `exchange-chaos-app:pml`(이 트리에서 새로 굽고 참조 태그 `exchange-chaos-app:s7ref`를 함께 붙인다)에서만 돈다. 보존 볼륨 `exchange-chaos_chaos-pgdata`와 `failstop-keep-*`는 생성 시각만 읽는다(시작·끝과 단계마다 `volumes.txt`, 첫 기록과 다르면 실행기가 멈춘다). 프로젝트 `exchange-chaos`에는 `up`·`down`·`volume rm`을 하지 않고, `down -v`는 `-p exchange-pml`이 붙은 호출로만 한다. 비밀 파일(`harness/.env.chaos`, `harness/load/secrets/k6_keys.json`)은 경로로만 넘긴다. 웹 관찰(Q2·Q13)을 위해 작업 트리의 `web/node_modules`를 주 저장소의 `web/node_modules`로 잇는 정션을 두고, 끝나면 정션만 지운다. 이 주행 동안 같은 호스트에서 다른 Docker 실험은 돌지 않는다(사용자 확인).

실행기는 [run-s7-gate.ps1](run-s7-gate.ps1)이다. 초안 `run-gate.ps1`에 단계 기록(보존 볼륨, 호스트 부하, CPU 표본, 이미지 기록)을 더한 것이다. 여러 단계를 잇는 것은 [run-seq.ps1](run-seq.ps1)이고 순서와 종료 코드는 `run-seq.log`, 단계마다 실행기 출력은 `<단계>-runner.log`, 모든 명령의 시각은 [commands.log](commands.log)다. 실험 하나의 흐름은 `harness/scripts/s7-p3-gate.ps1`이 한다. 실험마다 새 스택을 세우고 Q0 준비를 한 뒤 실험하고, 봇을 멈춰 비운 뒤 사후 판정을 뜨고 내린다. 결과는 실험 폴더의 `gate.json`이다.

| 실험 | 실행 경로 | 판정 파일 |
|---|---|---|
| Q0 준비 | 전환 도구 `protocol_latch.py transition --target 3 --latch-band`, `lib.ps1 Invoke-P3Preflight` | `protocol.json`, `p3-preflight.json` |
| Q1 E12 이중 지출 | `s7_double_spend.py --rounds 1000` → `s7_axes.py double-spend` | `double_spend.json`, `double_spend_verdict.json` |
| Q2 E4 + W6 | 실행기 `-Experiments E4 -Protocol 3 -P3 -Resource matching-p1 -PartitionAxes -WsGateAxes` | `runner/E4/` 아래 `verdict.json`, `p3_axes.json`, `relay_axes.json`(W6), `rejoin.json`, `ws_gate.json`, `web_resync.json` |
| Q3a E7 p1 소유자 pause | 실행기 `-Experiments E7 -Resource matching-p1` | `runner/E7/` |
| Q3b E7 두 파티션 소유자 pause | 실행기 `-Experiments E7 -Resource matching-p1 -ConsolidateOn chaos-api-1` | `runner/E7/consolidate.json`, `rto_matching-p0.json` |
| Q4 되돌리기와 재진입 | 봇 부하 → `transition --target 2 --allow-downgrade` → P2 봇 부하 → 사후 판정 → `transition --target 3` → 사후 판정 | `protocol_down.json`, `protocol_reentry.json`, `segment.json`, `reentry/` |
| Q5 E5 분리 | 실행기 `-Experiments E5 -Resource matching-p1` | `runner/E5/` |
| Q6 E2 DB 절단 | 실행기 `-Experiments E2 -Resource matching-p1` | `runner/E2/` |
| Q7 E6 폭주 중 kill | 실행기 `-Experiments E6 -Resource matching-p1` | `runner/E6/` |
| Q8 수수료 행 결합 | `s7_fee_coupling.py --pause-s 3 --latency-ms 100` → `s7_axes.py fee-coupling` | `fee.json`, `fee_verdict.json` |
| Q9 부하 중 수동 인계 | `s7_axes.py relocate` | `relocate.json` |
| Q10 한 마켓 치명 | `s7_market_fatal.py --market KRW-ETH` → `s7_axes.py market-fatal` | `fatal.json`, `fatal_verdict.json` |
| Q11 두 노드 콜드 재시작 | `s7_axes.py cold-assign` | `cold.json` |
| Q12 처리량(기록) | `roundtrip_probe.py --rate 600 --protocol-before-traffic 2·3` | `P2a.json` 등 |
| Q13 W7 두 파티션 수신 | k6 관찰자 + 웹 관찰 → `s6_relay_axes.py judge --experiment W7` | `watch.json`, `web_resync.json`, `w7.json` |
| Q14 C22' 비용 | `s7_c22_cost.py --rows 1000000` | `c22_cost.json` |
| Q15 힌트 없는 쓰기(기록) | 12마켓 k6 `MARKET_HINT=0` → `s7_axes.py lb-hintless` | `hintless.json` |
| Q16 두 파티션 인계 | `s5_route_probe.py q16` → `s7_axes.py handoff` | `q16.json`, `handoff.json` |

## 결과

이미지는 `exchange-chaos-app:pml` = `sha256:267c799f…`(참조 태그 `exchange-chaos-app:s7ref`, HEAD `58469ef9`, jar SHA-256 `b70488ba…`, [images.json](images.json))이고 모든 주행이 같은 이미지다. 서버 코드는 바꾸지 않았으므로 이미지를 다시 굽지 않았다. 고친 것은 하네스 도구, 판정기, 카오스 스택 설정이다(아래 '주행 중 보정'). 보존 볼륨 두 개(`exchange-chaos_chaos-pgdata` 2026-09-13T11:42:01Z, `failstop-keep-chaos-pgdata-20260924` 2026-09-23T15:47:36Z)는 시작·끝과 단계마다 생성 시각이 그대로였다([volumes.txt](volumes.txt)). 이 주행 동안 다른 Docker 실험은 돌지 않았고, 단계 앞뒤 호스트 CPU(16논리 코어, 5초 평균)는 1.2~6.5%, 주행 중 5초 표본 평균은 9.0~25.0%, 최대는 10.5~68.9%였다(`host-<단계>.json`, `cpu-<단계>.csv`). 시간 축 FAIL은 없어서 CPU 때문에 다시 잰 회차는 없다.

Q0 준비를 거친 P3 전환(전환 도구 `transition --target 3 --latch-band`)은 이 주행에서 29번 했고 모두 한 번에 선호 배정(api-1 `matching-p0`, api-2 `matching-p1`)이 됐다. 재배치는 0번, 배정까지 기다린 시간은 매번 약 10초였다. Q4의 재진입과 Q12의 P3 두 칸도 재배치 없이 선호 배정이었다. 전환 함수 호출은 1->2가 약 18ms, 2->3이 약 25ms였다(Q1-r2). Q0 준비 판정 14축은 모든 주행에서 참이었다.

### 판정표

같은 실험을 도구를 고친 뒤 다시 잰 경우 마지막 회차가 판정이고 앞 회차는 아래 '주행 중 보정'에 적었다. RTO는 영향 파티션(p1) 마켓의 옛 epoch 뒤 첫 체결까지다. ACK 간격은 생존 파티션(p0) 여섯 마켓의 기준선 최대와 판정 창 최대 가운데 가장 큰 값이다. 사후 불변식은 드레인 뒤 권위 run의 C01·C02·C05·C13A·C14'·C22'·C29·C30·C31 위반 행이고, 모든 판정 회차에서 0이었다. `summary.sql` CRITICAL·HIGH도 모두 PASS다.

| 실험 | 판정 | 핵심 수치 | 폴더 |
|---|---|---|---|
| Q1 E12 교차 노드 이중 지출 | PASS | 1,000회 모두 성공 정확히 하나. 주문 `400 insufficient_funds` 866, 출금 `422 insufficient_funds` 134(합 1,000 > 0), 성공 주문 634·출금 366, `500` 0, 인증 거절 0, 정산 23514 증분 0, 파티션 치명 강등 0, 회 사이 잔고 복귀 실패 0(최대 0.155초), 사후 C01·C14·C31 0 | [Q1-r2](Q1-r2/) |
| Q2 E4 p1 소유자 kill + W6 | PASS | p1 RTO 12.866초(상한 16.507초), p1 epoch 8->10(ACQUIRE 한 번, 모드 3, 생존 노드), p0 epoch 9 불변, ACK 간격 기준선 1.002초·창 0.901초(창 NEW 마켓별 74건 이상), 희생 epoch 8 구 쓰기 증분 0, 구간 경계 증분 0, W6 22축 참(생존 관찰자 재연결 0, 비영향 KRW-BTC·KRW-XRP epoch 상승 0과 결번 0, 영향 KRW-ETH epoch 한 번 상승, 웹 관찰의 티커 재동기화 요청이 KRW-ETH만), 합류 12.9초 뒤 두 파티션 FOLLOWER·리스 불변·승격 문구 0 | [Q2-r3](Q2-r3/) |
| Q3a E7 p1 소유자 pause 45초 | PASS | p1 RTO 13.378초, epoch 9->10, api-2 p1 강등 1·p0 강등 0, 좀비 stale 쓰기 0, ACK 간격 기준선 0.909초·창 0.900초, 희생 epoch 구 쓰기 0 | [Q3a](Q3a/) |
| Q3b E7 두 파티션 소유자 pause | PASS | 두 파티션을 api-1로 모은 뒤 45초 pause. api-2가 선호 p1을 6.47초, p0를 12.70초에 얻음(선호 먼저, 비선호는 유예 뒤), p1 RTO 10.662초, p0 RTO PASS(< 30초), api-1 두 파티션 모두 강등, 희생 epoch 9·10 구 쓰기 0. 생존 파티션이 없어 ACK 간격은 적지 않음 | [Q3b-r3](Q3b-r3/) |
| Q4 되돌리기(P3->P2)와 재진입 | PASS | 이력 P1->P2->P3->P2->P3, 3->2 호출 19.6ms(퇴역 p0 10·p1 11·engine 12), 되돌린 뒤 api-1 `matching-engine` epoch 13(10초 안), 두 노드 기동 모드 2, 샤드 1 수수료 17,444 그대로, 샤드 0 55,363->124,070(P2 봇 300초), 사후 C22'·C30 0, 세 전환 이력 행의 구간 경계 증분 0, 재진입 뒤 선호 배정(재배치 0)과 사후 무결성 | [Q4](Q4/) |
| Q5 E5 api-2 망 분리 60초 | PASS | p1 RTO 11.125초, epoch 8->10, api-2 p1 강등, ACK 간격 기준선 1.001초·창 1.001초, 희생 epoch 구 쓰기 0 | [Q5](Q5/) |
| Q6 E2 `pg-api2` 절단 | PASS | p1 RTO 13.472초, epoch 9->10, epoch 역행 0, ACK 간격 기준선 0.801초·창 0.801초, 희생 epoch 구 쓰기 0 | [Q6](Q6/) |
| Q7 E6 폭주 중 kill | PASS | p1 RTO 11.373초, epoch 8->10, 폭주 스캔 32회 위반 0, ACK 간격 기준선 1.001초·창 1.001초 | [Q7](Q7/) |
| Q8 수수료 행 결합 | PASS | api-1 세션(pid 419)이 KRW FEE_INCOME 샤드 0 행을 쥔 순간 3.11초 pause, 1초 뒤에도 같은 세션이 쥠(양성 축), 그 창에 p1 체결 30건과 샤드 1 잔고 6,990->7,319, p1 정산 지연 최대 기준선 0.021초·창 0.005초, api-2 재시도 소진·치명 강등 0, 리스 인계 0 | [Q8-r4](Q8-r4/) |
| Q8c 대조 칸(같은 사용자 집합) | 기록 | 같은 방식으로 잡아 멈춘 창의 p1 정산 지연 최대가 기준선 4.15초에서 6.97초로 늘었다(+2.8초, 멈춤 길이 가까이). 사용자 행을 나누지 않으면 결합이 드러나고, Q8의 정산 지연 표본이 멈춤에 민감하다는 대조다 | [Q8c](Q8c/) |
| Q9 부하 중 수동 인계 | PASS | api-2 p1 참여 끔 -> api-1이 10.29초에 얻음, 다시 켜도 돌아가지 않음, api-1 참여 끔 -> api-2가 8.25초에 되찾음. 두 인계 모두 옛 epoch 쓰기 0, p0 ACK 간격 기준선 0.652초·창 0.652초(창 NEW 59건 이상), 끝 배정 선호 | [Q9-r2](Q9-r2/) |
| Q10 한 마켓 치명(KRW-ETH, `S7F01`) | PASS | 주입 1회, p1만 `WRITER_FATAL` 강등 1회, 차단기 닫힘, 5.26초에 더 큰 epoch로 다시 ACCEPTING, p0 강등 0, p0 ACK 간격 기준선 0.902초·창 0.902초, 사후 게이팅 불변식 통과 | [Q10-r2](Q10-r2/) |
| Q11 두 노드 콜드 재시작 | PASS | 함께 시작해 10.64초에 선호 배정(재배치 없음), 첫 체결 p0 15.1초·p1 15.8초(< 30초), 기동 뒤 2×TTL 동안 비선호 획득 0 | [Q11-r2](Q11-r2/) |
| Q12 P2·P3 처리량(짧은 판) | 기록(무결성 축 판정 불가) | 12마켓 k6 600/s, 봇 예열 240초, 부하 90초, 칸마다 새 스택. 접수/s P2a 229.9, P3a 376.2, P2b 228.8, P3b 359.7(체결/s 9.6·22.4·11.6·22.6, 수용 거절/s 389.7·242.1·390.6·258.4, p99 432.6·584.3·343.1·577.2ms). P3 칸은 명령 대역 래치가 걸려 명령 채번이 마켓 카운터만 쓰고, P2 칸은 래치 없이 전역 카운터도 갱신한다(명령당 `journal_counters` 갱신 1.0 대 0). 두 모드의 차이에 래치의 차이가 섞여 있다. 교착 0, 드레인 뒤 정산 전 예약 0, C28 0. `roundtrip_probe.py`는 주문 응답의 사유별 수와 권위 run을 남기지 않아 설계의 무결성 축은 재지 못했다 | [Q12-r2](Q12-r2/) |
| Q13 W7 두 파티션 수신 | PASS(재판정, 주행 판정은 웹 REST 축 하나로 FAIL) | 아래 설명. 관찰자 연결 하나(`opens` 1)로 600초 동안 KRW-BTC 호가 2,128·체결 1,275, KRW-XRP 1,584·1,024, KRW-ETH 1,952·971, 두 파티션 epoch 9·8로 다름, 창 안 epoch 상승 0, 결번 0, 웹 관찰의 두 파티션 epoch 다름. 주행 판정의 거짓 축은 `web_rest_after_first_load_0` 하나(첫 적재 뒤 REST 39건). 설정 폴링을 뺀 재판정 `w7-rejudge-20260926.json`은 모든 축이 참 | [Q13](Q13/) |
| Q14 C22' 비용 | PASS | 합성 명령 1,000,000행(대역 잎 16), 권위 run과 같은 트랜잭션에서 1,483.5·1,442.2·1,448.9ms(문턱 1,500ms), PostgreSQL 17.10 폐기용 컨테이너 | [Q14](Q14/) |
| Q15 힌트 없는 쓰기 | 기록 | 12마켓 k6 `MARKET_HINT=0` 180초. LB 주문 쓰기 9,034줄 가운데 `502` 3,538줄(39%), 창 1개 180초(부하 내내), `502` 줄은 모두 `exchange_write` 경유 | [Q15](Q15/) |
| Q16 두 파티션 인계 | PASS | 두 노드가 살아 있을 때 마켓 없는 일괄 취소 `200`·4건 취소·p1 인계 행 DONE. api-1 p1 참여 끔 + api-2 pause 동안 일괄 취소의 p1 주문 두 건 `partition_not_started`, 회원 폐쇄 `409 orders_cancel_incomplete`(요청 시점 p1 holder api-2), 재개 뒤 열린 인계 행 0, 참여 복구 | [Q16-r2](Q16-r2/) |

**정산 23514와 치명 강등.** 실행기 실험(Q2·Q3a·Q3b·Q5·Q6·Q7)은 공통 판정(`s3_common.json`)이 두 노드 `exchange_settlement_insufficient_funds_total` 증분 0을 봤다. 관문 실행기 실험(Q4·Q8~Q11·Q13·Q15·Q16)의 판정 파일은 이 계수를 따로 세지 않으므로, 스택을 내리기 전에 저장한 두 앱 로그(저장소 밖, 해시는 `container_logs.json`)에서 `chk_balance_nonneg`·`23514`를 찾아 모두 0줄임을 봤다([log-scan.txt](log-scan.txt)). 같은 검사에서 치명 문구는 Q10의 주입한 `WRITER_FATAL` 두 줄(강등과 재구성 로그) 밖에 없었다.

**Q13의 주행 판정과 재판정.** 첫 적재(15초) 뒤 웹이 낸 REST 39건은 모두 `/api/v1/exchange-config`이고 15초 간격이다. 웹이 효과 강도 설정을 계약대로 폴링하는 호출(API 설계 §4-A-6, `web/lib/store.ts`의 `EFFECT_POLL_MS = 15_000`)이며 호가·티커·계좌 재동기화 REST는 0건이었다. S6 설계 7-2의 W7 문장은 '웹 관찰의 정상 창 REST는 첫 적재 뒤 0'이라 이 폴링도 센다. 주행 뒤에 판정을 좁히면 기준을 낮추는 일이 되므로 판정기를 고치지 않고 FAIL로 적는다. 나머지 W7 축과 결번 0은 위와 같다. 사용자가 2026-09-26 계약된 비시세 폴링은 재동기화가 아니므로 이 폴링을 W7의 'REST 0'에서 빼기로 확정했다. 판정기가 첫 적재 뒤 `/api/v1/exchange-config`만 빼고 세도록 고친 뒤(`s6_relay_axes.py`의 `web_rest_after_settle`), 주행 때와 같은 창·인자로 이 원자료를 다시 판정해 PASS를 얻었다([w7-rejudge-20260926.json](Q13/w7-rejudge-20260926.json), 센 REST 0, 뺀 폴링 39). 주행 때의 `w7.json`(FAIL)은 그대로 둔다.

**결번 재측정.** `58469ef9`(구독 스냅샷을 내장 브로커 등록 뒤에 읽음) 이미지로 Q2 관찰자 셋(api-1, api-2, LB) x 세 마켓과 Q13 관찰자 하나 x 세 마켓의 호가·티커 결번은 모두 0이었다. 호가 실시간 수는 Q2가 마켓·관찰자마다 275~1,007건, Q13이 1,584~2,128건이다. 웹 관찰의 비영향 마켓 결번도 0이다. 한 주행이라 결번이 사라졌다고 단정하지는 않는다(S6 관문의 결번은 315,176건 가운데 1건이었다).

**희생 epoch 집합의 구 쓰기.** 실행기 실험마다 1초 표본으로 주입 직전 희생 노드가 쥔 파티션 epoch의 네 표 행 수를 봤고, 주입 뒤 첫 표본과 마지막 표본 사이 증분은 모두 0이었다(Q3b는 두 epoch). 주입과 첫 표본 사이 1초 이하 구간은 이 표본이 보지 못하고, E5·E7 스크립트의 주입 시각 기준선이 `-Resource` 파티션만 본다.

### 발표 재현 확인(Demo)

`run-s7-gate.ps1 -Phase Demo`로 `harness/scripts/s7-p3-demo.ps1 -NoBuild -Down -EvidenceDir <이 폴더>\Demo`를 한 번 돌렸다([Demo/demo.json](Demo/demo.json)). P3 스택을 세우고 전환한 뒤 Q0 준비가 참이었고, 봇과 12마켓 k6(힌트) 부하 60초 뒤 api-2를 kill했다. 화면에는 kill 전 두 노드의 파티션 표(api-1 p0 epoch 9, api-2 p1 epoch 8), kill 뒤 표(api-1이 p1 epoch 10까지 ACCEPTING, api-2 응답 없음), p1 RTO 10.982초, p0 ACK 간격 PASS, 권위 run PASS(게이팅 위반 0)가 나왔고 스택을 내렸다. 판정기가 Q2와 같아서 녹화 화면의 수치 정의가 관문과 같다.

사용법은 이렇다. 앞의 준비(환경 변수 넷, `harness/.env.chaos`의 세 비밀 값 `CHAOS_MASTER_KEY_BASE64`·`CHAOS_RELAY_PASSCODE`·`CHAOS_MEMBER_SESSION_SEED_BASE64`와 `CHAOS_MEMBER_PASSWORD_PEPPER_BASE64`, 떠 있는 `chaos-*` 없음)를 한 뒤 `pwsh -NoProfile -File harness/scripts/s7-p3-demo.ps1`을 부르면 이미지를 굽고(bootJar와 `docker build`) 스택을 세운다. 이미지가 이미 있으면 `-NoBuild`로 굽지 않는다. `-KillAfterSec`(기본 60)이 부하를 켠 뒤 kill까지 기다리는 시간이고, 녹화하는 사람은 이 동안 Grafana 파티션 패널(`http://localhost:53001`), 관리 화면의 리스 표, 웹 거래 화면 두 마켓(KRW-BTC p0, KRW-ETH p1)을 띄운다. `-Down`을 주지 않으면 녹화를 위해 스택을 남기고 내리는 명령을 화면에 적는다. 결과는 `-EvidenceDir`(기본 `harness/evidence/pml-s7-demo-<시각>`)의 `demo.json`, `protocol.json`, `p3-preflight.json`, `rto_p1.json`, `ack_p0.json`이다.

### 주행 중 보정

판정 값(RTO 30초, 16.507초, 기준선 + 1초, 1,500ms, 1,000회 등)은 바꾸지 않았다. 서버 코드 결함은 나오지 않았고, 도구가 판정 규칙이 뜻하는 표본을 뜨지 못한 곳과 카오스 스택 설정의 빈틈을 고쳤다. 고치기 전 결과는 통과 증거로 쓰지 않고 다시 쟀다.

1. **회원 세션 서명 시드(Q1, 스택 설정).** 첫 Q1([Q1](Q1/))은 1,000회 모두 성공 하나였지만 실패 쪽이 모두 `401`이었다(주문 `401` 750, 출금 `401` 250). 카오스 compose가 회원 세션 서명 시드와 비밀번호 페퍼를 넘기지 않아 두 노드가 프로세스마다 임의 키를 썼고, api-1이 낸 회원 토큰을 api-2가 거절했다. 잔고 경합 없이 '성공 하나'가 채워진 것이라 양성 축(주문 `insufficient_funds` > 0)이 거짓이어서 판정은 NOT RUN이었다. `harness/docker-compose.chaos.yml`의 앱 공통 환경에 `EXCHANGE_MEMBER_SESSION_SEED_BASE64`·`EXCHANGE_MEMBER_PASSWORD_PEPPER_BASE64`를 `.env.chaos`의 값으로(비우면 종전 동작) 넘기게 하고, 이 PC의 `.env.chaos`에 무작위 값 둘을 더했다(출력하지 않음). `s7_double_spend.py`는 시작 때 api-2가 같은 토큰을 받는지 보고, 판정기는 인증 거절 0을 양성 축(`auth_rejections_0`)으로 더했다. 운영 배포(`StartupSecretValidator`)는 세 비밀 값을 요구하므로 이 결함은 카오스 스택에만 있었다.
2. **ACK 기준선(Q2).** 첫 Q2([Q2](Q2/))는 PASS였으나 KRW-BTC·KRW-XRP의 기준선 최대가 21.3·21.8초였다. 주입 전 120초 안에 P3 전환의 정지 창(두 노드 재시작)이 들어가 기준선이 부풀었고, 그러면 '기준선 + 1초'가 헐거워진다. `s7_axes.py ack-interval`에 `--baseline-from`을 더해 실행기와 데모가 Q0 준비 시각 뒤로 기준선을 자르게 했다(시험 한 사례). 둘째 회차([Q2-r2](Q2-r2/))는 실행기가 그 시각을 문화권 날짜 문자열로 넘겨 판정기가 읽지 못했고(FAIL, ACK 판정 파일 없음), ISO로 넘기게 고친 셋째 회차가 판정이다.
3. **Q8 부하와 표본.** 첫 두 회차([Q8](Q8/), [Q8-r2](Q8-r2/))는 다섯 번 모두 잠금을 잡지 못해 NOT RUN이었다. 봇을 멈추면 k6 `order_flow.js`는 호가만 쌓고 체결하지 않아 정산이 없었다(첫 회차). 부하 계정 이메일이 번호로 고정이라 p0·p1 루프의 키를 따로 발급하면 앞 루프의 키가 다시 발급돼 p0 주문이 모두 `401`이었다(둘째 회차). `ORDER_CROSS=1`(k6 주문이 서로 체결)과 여덟 계정 한 번 발급 뒤 넷씩 나누기로 고친 셋째 회차([Q8-r3](Q8-r3/))는 PASS였지만 p1 정산 지연이 기준선·창 모두 -0.0002초였다. 지연을 `event_journal.created_at`(명령 engine_ts 파생)으로 재서 정산이 늦어도 늘지 않는 표본이었다. 첫 방출 등록 시각(`event_emission_registry.created_at`, 정산 트랜잭션의 DB 시각)으로 바꾼 넷째 회차가 판정이고, 대조 칸 Q8c가 이 표본이 멈춤에 반응함을 보였다.
4. **생존 파티션 부하(Q9·Q10).** 첫 Q9·Q10은 봇 부하만 있어 p0 마켓 여섯 가운데 넷의 판정 창 NEW가 0건이었다. Q9는 NOT RUN, Q10은 판정기가 ACK를 재지 못한 것을 FAIL로 읽었다. 관문 실행기가 두 실험 동안 실행기 실험과 같은 생존 파티션 k6 부하(20/s, 힌트)를 켜고, Q10 실행기는 사후 드레인 전에 그 부하를 멈추며, 판정기는 ACK를 재지 못하면 NOT RUN으로 읽게 했다(`p0_ack_measured`, 시험 두 사례). 첫 Q10의 나머지 축(주입 1회, p1 `WRITER_FATAL` 1회, 5.78초 재획득, 차단기 닫힘, 사후 불변식)은 참이었다.
5. **Q11 첫 체결.** 첫 Q11은 배정 직후 첫 체결을 읽어 두 파티션 모두 값이 없어 FAIL이었다. 기동 뒤 60초까지 첫 체결을 기다리게 했다(30초 문턱은 그대로).
6. **Q3b 계획 강등.** 첫 두 Q3b는 공통 판정의 `leader_node_no_fatal_or_demotion`(로그)과 파티션 축의 `leader_node_demotions_0`(계수)이 거짓이었다. 두 파티션을 api-1로 모으려고 api-2의 p1 참여를 끈 준비 단계의 강등(`사유=PLANNED`)을 생존 노드의 강등으로 셌다. 두 판정기가 계획 강등을 따로 적고 강등 축에서 빼게 했다(치명·리스 상실·정지 강등은 그대로, 시험 두 사례).
7. **Q16 관리 평면.** 첫 Q16은 회원 폐쇄를 부를 관리 평면(`chaos-admin`)이 P3 스택에 없어 NOT RUN이었다. 관문 실행기가 S5 설계의 명령(`docker compose -p exchange-pml -f compose.yaml --env-file harness/.env.chaos --profile admin up -d --no-deps chaos-admin`)으로 띄우게 했다.
8. **Q12 준비.** 첫 Q12([Q12](Q12/), 짧은 판, 예열 60초)는 P2 두 칸이 봇 예열 미달, P3 두 칸이 api-2가 아직 뜨는 중이라 전환 도구의 사전 확인이 최고 모드 게이지를 읽지 못해 BLOCKED였다(NOT RUN). 예열은 기본값(240초)으로 되돌리고 부하 시간만 90초로 줄였으며, `roundtrip_probe.py`가 P3 전환 전에 두 노드 게이지를 최대 180초 기다리게 했다. 관문 실행기에는 `-Q12WarmupSec`·`-Q12DurationSec`을 더했다.

### 정리

`run-s7-gate.ps1 -Phase Final`이 `docker compose -p exchange-pml … --profile obs down -v --remove-orphans`로 내린 뒤 남은 `chaos-*` 컨테이너 0, `pml-*` 컨테이너 0, `exchange-pml` 라벨 볼륨 0·네트워크 0을 확인했다(05:47). 보존 볼륨 두 개의 생성 시각은 처음과 같았다. 이미지 태그는 `exchange-chaos-app:pml`·`s7ref`가 `sha256:267c799f…`, `s6ref`·`local`은 주행 전과 같다([docker-ps-after.txt](docker-ps-after.txt), [volume-ls-end.txt](volume-ls-end.txt)). 웹 관찰용 `web/node_modules` 정션은 링크만 지웠다.

### 한계

- 로컬 Docker 단일 호스트(16논리 코어)의 결과이고 AWS의 장애나 처리량을 대신하지 않는다. 실험마다 한 회차다.
- Q13은 사용자 확정에 따른 재판정으로 PASS다(위). Q15는 판정이 아닌 기록이지만, 힌트를 싣지 않는 쓰기는 P3에서 부하 내내 39%가 `502`였다. 설계가 예상한 '2~3초 창'보다 넓다(S7 설계 4-9, 9절).
- 사후 감사가 세지 못하는 전환 경계(전환을 가로지른 트랜잭션, 옛 구간 epoch의 이벤트·체결)는 구간 경계 증분으로만 봤고 모든 판정 회차에서 0이었다.
- 관문 실행기 실험의 정산 23514는 판정 파일이 아니라 로그 검사로 봤다.
- 첫 회차가 NOT RUN·FAIL이었던 실험은 도구를 고친 뒤 한 번 다시 쟀다. 판정 값은 그대로지만 고친 도구와 판정기(표본 방식, 계획 강등 제외, 기준선 자르기)는 AI가 정했고 사용자 확인 대상이다.
