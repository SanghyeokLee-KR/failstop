# S3 관문 폐기용 스택 재주행 (2026-09-24 13:51~15:36 KST)

[S3 설계](../../../docs/reference/architecture/마켓분할_리더_S3_설계.md) 7절의 실험(X1~X7)을 compose 프로젝트 `exchange-pml`의 폐기용 스택에서 다시 돌린 기록이다. 앞 주행([pml-s3-gate-20260924-0830](../pml-s3-gate-20260924-0830/README.md))은 09:38에 구운 이미지(`sha256:b14db943…`)로 돌았고, 그 뒤 구현 검토 라운드 1·2에서 V50, `OrderGateway`, `SettlementService`, `EngineConfig`, `LeaderProtocolBoot`, `ReservationBacklogMonitor`와 하네스 러너가 바뀌었다. 그래서 현재 작업 트리로 이미지를 다시 굽고 전 실험을 새로 돌렸다.

절차는 앞 주행의 실행기를 그대로 옮긴 [run-s3-gate.ps1](run-s3-gate.ps1)이고, 단계별 시각과 종료 코드는 [commands.log](commands.log), 보존 볼륨 확인은 [volumes.txt](volumes.txt)와 시작·끝의 [volumes-start.txt](volumes-start.txt)·[volumes-end.txt](volumes-end.txt)다. 문장 시간 측정용 SQL은 [extract_sql.py](extract_sql.py)로 현재 코드에서 다시 뽑았고([sql/sql-sha256.json](sql/sql-sha256.json)), 해시는 앞 주행과 같다.

## 결론

| 실험 | 판정 | 요지 |
|---|---|---|
| X1 P2 봇 부하(앱 역할 로그인, V49 관문 겸함) | PASS | 판정 축 17개 모두 참. P2 epoch NEW = 예약 행 16,659, 리더 epoch 6 유지 |
| X2 E4 리더 kill | PASS | RTO 6.157초, epoch 5 -> 6, rpo_records 0 |
| X3 E7 리더 pause | PASS | RTO 10.155초, epoch 5 -> 6, rpo_records 0, 구 리더 재개 뒤 갱신 거부로 리더십 상실 |
| X4 E2 DB 절단 | PASS | RTO 10.488초 |
| X4 E5 네트워크 분리 (첫 회) | FAIL(rpo_records) | 사후 권위 run이 부하 중에 떠 C13A 1. 같은 실험의 드레인 뒤 `summary.sql` C13A 0 |
| X4 E5 네트워크 분리 (재주행, 드레인 뒤 사후 run) | PASS | RTO 5.942초, rpo_records 0 |
| X4 E6 부하 급증 중 kill | PASS | RTO 6.978초 |
| X5 (i) 12마켓 600/s | PASS | P1 101.1/s, P2 117.5/s, L = -0.162, n = 0.068 |
| X5 (ii) 단일 마켓 300/s 과포화 | PASS | P1 123.6/s, P2 175.1/s, L = -0.417, n = 0.014, 모든 칸 과포화 |
| 결정 5(두 측정 모두 PASS) | PASS | 마켓당 손실 20% 이하. 두 측정 모두 P2가 더 많이 받았다 |
| X6 R1 스택 판 | NOT RUN | 이미지 태그 규칙. `ProtocolRollbackR1Test`가 일부를 대신한다(앞 주행과 같음) |
| X7 감사 문장 누적 행 비용 | PASS | 100만 건 C14' JIT 켬 533ms, C31 앱 판 JIT 끔 625ms, 게이지 JIT 끔 12ms |

모든 실험에서 자산·원장 불변식(권위 run과 드레인 뒤 `summary.sql`의 CRITICAL·HIGH)은 드레인 뒤 전부 PASS였고, 정산 23514, 잠금 계획 이탈, 접수 500, 접수 40P01, `pg_stat_database.deadlocks` 증가, 정산 일시 오류 재시도가 모두 0이었다. 예외는 X4 E5 첫 회의 사후 권위 run(부하 중)에 잡힌 C13A 1건이고, 원인과 처리는 아래 X4 절에 적었다.

## 조건

| 항목 | 값 |
|---|---|
| 이미지 | `exchange-chaos-app:pml` = `sha256:0ff1d3c13083a0aac8fc4c67f939822c33703af61e6e307ad7a5935bdd5bf43e` ([images.json](images.json)) |
| 원천 | 작업 트리 `C:\dev\exchange-pml`(브랜치 `claude/per-market-leader`, HEAD `909af89`)의 커밋하지 않은 S3 변경. 추적 파일 diff SHA-256 `12285611…`, 변경 경로 114개 |
| jar | `harness/app.jar` SHA-256 `f7454b57…`. 이미지 안 V50 파일 해시가 작업 트리 V50과 같음을 확인했다 |
| 마이그레이션 | V0~V52(`flyway_schema_history` 최대 52, 실패 0) |
| 호스트 | Windows 11, Docker Desktop 단일 호스트, 논리 CPU 16. 앱 노드는 compose 한도 1 vCPU·1 GiB |
| 보존 자원 | `exchange-chaos_chaos-pgdata`(생성 2026-09-13T11:42:01Z), `failstop-keep-chaos-pgdata-20260924`(2026-09-23T15:47:36Z). 단계마다 앞뒤로 생성 시각을 확인했고 바뀌지 않았다. `exchange-chaos-app:local`(`611169b8bf40`)은 그대로다 |

이미지는 실행기 밖에서 한 번 구웠다. 명령은 `$env:CHAOS_PROJECT='exchange-pml'; $env:COMPOSE_PROJECT_NAME='exchange-pml'; $env:CHAOS_APP_IMAGE='exchange-chaos-app:pml'; pwsh -NoProfile -File harness/scripts/up.ps1 -Obs`이고(13:51, rc 0), 그 전에 `docker compose -p exchange-pml ... config`의 프로젝트 해석값이 `exchange-pml`인지 확인했다. 시작할 때 `chaos-*` 컨테이너는 없었으므로 보존 스택을 내릴 일이 없었다([docker-ps-before.txt](docker-ps-before.txt)는 빈 파일). 빌드 뒤 Gradle 데몬을 멈춰 호스트 부하를 줄였다. 끝에 `-p exchange-pml` 스택을 `down -v --remove-orphans`로 지웠고, 남은 `chaos-*`·`pml-probe*` 컨테이너와 `exchange-pml` 볼륨은 없다([volume-ls-end.txt](volume-ls-end.txt)).

## 주행 전·중에 바꾼 하네스 절차

E5와 E7 스크립트는 E4와 공용 경로(E2·E6)와 달리 봇을 멈추지 않은 채 사후 권위 run을 떴다. 부하 중에 재면 물질화 전의 진행 중 NEW가 C13A와 rpo_records에 유실로 잡힌다. 앞 주행의 X3 반복 r1·r2가 이 이유로 FAIL이었고, S3 설계 12-3은 "다음 관문 전에 E7의 사후 run을 드레인 뒤로 옮길지 정한다"고 남겼다.

- `harness/scripts/e7-gray-pause.ps1`: 이 주행을 시작하기 전에 고쳤다. 사후 권위 run 앞에서 `Stop-BotLoad`와 `Wait-DrainZero`(상한 90초)를 하고, 끝나면 `BASE_OPS`로 봇을 되살린다. E4와 같은 모양이다. 판정 규칙(rpo_records 0, C13A 0)은 바꾸지 않았다.
- `harness/scripts/e5-net-partition.ps1`: X4 첫 회에서 E5가 FAIL(rpo_records)한 뒤 같은 방법으로 고치고 E5만 새 스택에서 다시 돌렸다(`X4-E5r`). 첫 회의 FAIL은 그대로 기록에 남긴다.

두 변경 모두 AI가 정했고 사용자 확인 대상이다. 커밋하지 않았다.

## X1 P2 봇 부하와 불변식 전체(앱 역할 로그인, PASS)

프로필 `dev,chaos-monkey,prod-timeouts`로 소유자 로그인 스택을 세워 api-1이 V1~V52를 적용했다. 배포용 [provision-runtime-roles.sql](../../../deploy/provision-runtime-roles.sql)로 런타임 로그인 `pml_app`·`pml_bot`·`pml_read`를 만들고(비밀번호는 실행마다 무작위, 기록하지 않음) S2 관문의 [앱 로그인 오버레이](../pml-s2-gate-20260924-0440/override-app-login.yml)로 api·봇을 다시 만들었다. P1 리더(epoch 2)가 선 뒤 [protocol_latch.py](../../scale/protocol_latch.py)로 P2로 전환하고 봇 목표 300 OPS로 10분(04:54:40~05:04:52 UTC) 돌렸다. 원자료는 [X1/result.json](X1/result.json)·[X1/samples.json](X1/samples.json)·[X1/protocol.json](X1/protocol.json)이다.

| 판정 축 | 결과 |
|---|---|
| 전환 | 퇴역 `matching-p0` 3 < `matching-p1` 4 < `matching-engine` 5, 전환 호출 18.2ms, 드레인 2.3초, 새 리더 epoch 6(= 마지막 퇴역 + 1), 두 노드 기동 모드 2·in-flight 0 |
| P2 epoch NEW = 예약 행 | 16,659 = 16,659 (>= 1,000) |
| 리더 epoch | 표본 40개 모두 6 |
| 로그인 | api 연결 `pml_app`만, 봇 `pml_bot`만 |
| 권한·함수 오류 문구 | 세 컨테이너 로그에 0줄 |
| V49 권한 상태 | `app_role`의 `leader_lease` UPDATE 거짓, `command_journal` INSERT 거짓, `order_reservations` INSERT 거짓, 전환 함수 EXECUTE 거짓 |
| 스냅샷 | 새 epoch로 3마켓 |
| 공통 판정 | 권위 run(앱 로그인) PASS(C31 앱 판 포함), `summary.sql` CRITICAL·HIGH 29행 PASS(C29·C31 PASS), 정산 23514 0, 잠금 계획 이탈 0(계획 잡음 12,254회), 접수 500 0, 접수 40P01 0, 교착 증가 0, 드레인 뒤 정산 전 예약 0, 라이터 정지·강등 문구 0 |

기록 값. 봇이 실제로 낸 주문은 16,631건(약 27.7/s)이고 400은 0건, 422(도메인 거부)는 26건이다. 게이지 최댓값은 수 1, 나이 0초였다. 잔고 행 대기 표본 40개 가운데 대기가 보인 표본은 둘(사용자 AVAILABLE 1, 수수료 행 1)이다. 문장 시간(실제 X1 DB, 약 1만 9천 명령, 둘째 회차 ms, JIT 켬/끔)은 C14' 533/32, C31 앱 판 1,043/190, 게이지 336/24, C31 전수 판 234/155, 전환 선행 조건 9/9, C05 3/3이다([X1/timings.json](X1/timings.json)). 판정은 X7이 한다. 주행 동안 `EdgeProbeDown`·`EdgeProbeSlow` 경보가 떴다. 판정 축이 아니고 앞 주행에서도 같았다.

## X2~X4 장애 실험 (P2, prod-timeouts)

실행기의 Chaos 단계가 실험마다 `-p exchange-pml` 스택을 지운 뒤 `harness/scripts/adr0022-stage5-chaos.ps1 -Image exchange-chaos-app:pml -Experiments <E> -Protocol 2`를 불렀다. 그 스크립트가 스택을 세우고, 실험 스크립트 전에 P2로 전환하고(epoch 1 -> 퇴역 2·3·4 -> P2 리더 5), 끝나면 봇을 멈추고 드레인한 뒤 [s3_common_axes.py](../../scale/s3_common_axes.py)로 공통 판정 축을 모은다. 폴더마다 `summary.json`(실험·분리 판정), `<E>/s3_common.json`(공통 판정), `<E>/verdict.json`이 있다. 컨테이너 로그 원문은 저장소 밖 `%TEMP%\pml-s3-logs`에 두고 해시만 `container_logs.json`에 남겼다.

| 실험 | RTO(초) | epoch | rpo_records | 사후 run 위반 | C22 두 팔 | 예약 행 | 승격 뒤 정산된 예약 명령 | 공통 판정 | 분리 판정 |
|---|---:|---|---:|---|---|---:|---:|---|---|
| X2 E4 | 6.157 | 5 -> 6 | 0 | 없음 | 0, 0 | 909 | 0 | PASS | PASS |
| X3 E7 | 10.155 | 5 -> 6 | 0 | 없음 | 0, 0 | 1,732 | 0 | PASS | PASS |
| X4 E2 | 10.488 | 5 -> 6 | 0 | 없음 | 0, 0 | 1,593 | 1 | PASS | PASS |
| X4 E5 첫 회 | 6.088 | 5 -> 6 | 1 | C13A 1 | 0, 0 | 2,579 | 0 | PASS | FAIL(rpo_records) |
| X4 E6 | 6.978 | 5 -> 6 | 0 | 없음 | 0, 0 | 1,593 | 0 | PASS | PASS |
| X4 E5 재주행 | 5.942 | 5 -> 6 | 0 | 없음 | 0, 0 | 2,480 | 0 | PASS | PASS |

공통 판정은 모든 실험에서 참이었다. 드레인 뒤 `summary.sql` CRITICAL·HIGH가 PASS이고, 정산 23514, 잠금 계획 이탈, 접수 500, 접수 40P01, 교착 증가, 정산 일시 오류 재시도(SQLSTATE 넷)가 0, 드레인 뒤 정산 전 예약 0, 실험 끝 리더 노드의 정지·강등 문구 0, 장애 창 밖 `ReservationUnsettledStale` 발화 0이다. 구 epoch 쓰기 증분(stale writes)은 모든 실험에서 0이다. 연결 판정은 모두 "고갈 없음"이다.

X4 E5 첫 회의 FAIL. 사후 권위 run(05:18:05 UTC)이 봇 부하 중에 돌아 C13A 1(물질화되지 않은 NEW 1건)을 셌고, 실행기가 `-Protocol 2`의 분리 판정에 건 rpo 축(rpo_records 0, 사후 C13A 0)에 걸렸다. 같은 실험 뒤 `s3_common_axes.py`가 봇을 멈추자 드레인이 2.2초에 끝났고, 드레인 뒤 `summary.sql`의 C13A는 0, 나머지 CRITICAL·HIGH도 PASS였다. 드레인 술어가 C13A와 같은 anti-join이므로 그 NEW는 2.2초 안에 물질화됐다. 그래서 잃은 주문은 없고 원인은 사후 run의 시점으로 좁혔다. E5 스크립트를 E4와 같이 드레인 뒤에 사후 run을 뜨게 고치고(위 절차 변경) 새 스택에서 E5를 다시 돌려 PASS였다. 첫 회의 판정은 바꾸지 않는다.

X3 E7 기록. 리더 api-1을 45초 멈췄다. 멈춘 리더의 트랜잭션 하나가 `idle_in_transaction_session_timeout`(10초)으로 05:10:28.852에 끊겼고, 새 리더는 그 직후(05:10:28.896) 승격했다. 승격 획득이 멈춘 리더의 세션을 약 10초 기다린 것으로 보인다(pg 로그와 승격 시각 대조, 추론). V52 이후라 받은 리스는 유효했다. 재개된 구 리더는 05:11:03에 "리더십 상실(갱신 거부)"로 물러났고 좀비 강등 관찰도 참이다. 2초 간격 잔고 행 대기 표본 43개에서 잠금 대기 백엔드는 최대 1이고 잔고 행 tuple 대기는 보이지 않았다. 표본 간격보다 짧은 대기는 재지 못했다.

X4 E2에서 멈추기 전 리더가 저널에 적은 예약 명령 1건을 새 리더가 승격 재생에서 정산했다(`reserved_settled_by_later_epoch` 1). 드레인 뒤 정산 전 예약 0, C31·C14' 0이다. 다른 실험에는 그런 명령이 없어 그 축을 주장하지 않는다.

## X5 처리량과 결정 5 (PASS)

[roundtrip_probe.py](../../scale/roundtrip_probe.py)의 독립 칸을 P1a·P2a·P1b·P2b 순서로 측정마다 돌렸다. 칸마다 러너가 `-p exchange-pml` 스택과 볼륨을 지우고 다시 세우고, P2 칸은 예열 전에 전환하고(`--protocol-before-traffic 2`), 부하 계정 16개를 재발급하고(`SCALE_LOAD_KEYS=16`), 봇 20 ops로 예열한 뒤 k6를 130초 돌렸다. 프로필은 `dev,chaos-monkey`이고 append 분포는 S2 관문의 [계측 오버레이](../pml-s2-gate-20260924-0440/override-append-histogram.yml)로 켰다. 원자료는 [X5/](X5/)의 칸 파일, 판정은 [judge_x5.py](judge_x5.py)가 낸 [X5/judge.json](X5/judge.json)이다. 모든 칸에서 400·429·500은 0건이고, 드레인 뒤 `summary.sql` CRITICAL·HIGH PASS, 교착 증가·정산 재시도·잠금 계획 이탈·정산 23514가 0이다.

(i) 12마켓 600/s. 판정값은 k6 주행 중 1분 창의 접수 201/s다.

| 칸 | 접수 201/s | 명령당 접수 락 보유(ms) | 머리말(ms) | 저널 삽입(ms) | 커밋(ms) | append p95/p99(ms) | 라이터 직렬(ms/명령) | 팔로워 503 | 예약 행 |
|---|---:|---:|---:|---:|---:|---|---:|---:|---:|
| P1a | 104.5 | 9.49 | 10.60 | 1.73 | 2.74 | 53.9/84.0 | 30.29 | 3,811 | 0 |
| P2a | 95.0 | 19.89 | 28.84 | 2.00 | 3.88 | 84.4/109.1 | 19.08 | 4,305 | 22,778 |
| P1b | 97.6 | 8.74 | 10.84 | 1.85 | 2.75 | 55.4/84.5 | 32.05 | 4,670 | 0 |
| P2b | 140.0 | 26.93 | 26.29 | 1.82 | 3.56 | 82.6/110.0 | 16.70 | 1,574 | 25,365 |

평균 P1 101.1/s, P2 117.5/s, 손실 L = -0.162, 잡음 n = |104.5 - 97.6| / 101.1 = 0.068이라 PASS다. 구간 [L - n, L + n] = [-0.231, -0.094]는 0.20을 걸치지 않는다. 다만 잡음 규칙은 P1 칸만 보는데 P2 두 칸은 95.0과 140.0으로 크게 벌어졌다(범위/평균 0.38). 가장 불리하게 짝지어 P2의 낮은 칸(95.0)과 P1의 높은 칸(104.5)을 비교해도 손실은 0.092로 0.20 아래다. 락 보유 비 1 - 보유(P1)/보유(P2)는 0.61이다. P2의 명령당 접수 락 보유가 P1의 두 배를 넘고 대부분 머리말이며, 라이터 직렬 시간은 P2가 짧다. 앞 주행과 같은 구조다.

(ii) 단일 마켓(KRW-BTC) 300/s 과포화. 판정값은 주행 창의 KRW-BTC k6 NEW 수 / 130초다.

| 칸 | 접수율(/s) | 과포화(<= 240/s) | 명령당 접수 락 보유(ms) | 머리말(ms) | 커밋(ms) | append p95/p99(ms) | 라이터 직렬(ms/명령) | 예약 행 |
|---|---:|---|---:|---:|---:|---|---:|---:|
| P1a | 124.4 | 예 | 2.39 | 1.07 | 0.86 | 2.8/4.0 | 7.89 | 0 |
| P2a | 173.9 | 예 | 5.57 | 3.50 | 1.54 | 6.7/9.0 | 5.80 | 26,734 |
| P1b | 122.7 | 예 | 2.39 | 1.06 | 0.86 | 2.8/4.1 | 8.27 | 0 |
| P2b | 176.2 | 예 | 5.39 | 3.35 | 1.51 | 6.7/8.8 | 5.42 | 26,801 |

평균 P1 123.6/s, P2 175.1/s, L = -0.417, n = 0.014로 PASS다. 락 보유 비는 0.56이다. P1은 라이터(명령당 직렬 7.9~8.3ms, 초당 약 125건 한계)에서 막히고, P2는 정산이 예약을 다시 하지 않아 라이터가 5.4~5.8ms로 줄어 더 받는다. 앞 주행(P1 134.9, P2 186.1, L = -0.380)과 같은 방향이다.

두 측정이 모두 PASS라 결정 5(마켓당 손실 20% 이하)는 이 호스트에서 통과다. 앞 주행에서 12마켓 측정이 잡음으로 NOT RUN이었던 것을 이번 주행이 채웠다. 12마켓 값은 앞 주행(P1 86.7~153.6/s)보다 낮고, 같은 호스트에서도 주행 사이 변동이 크다. 잡음이 P2 칸에서 컸다는 점과 함께 한계에 적는다.

기록 값. 잔고 행 잠금 대기 표본(15초 간격, k6 주행 중 9개)에서 12마켓 칸의 잠금 대기 백엔드 평균은 P1a 0.44, P1b 2.0, P2a 2.0, P2b 2.8이었다. 잔고 행 tuple 대기는 P1b에 사용자 AVAILABLE 행 최대 5, P2b에 사용자 AVAILABLE 행 최대 1이 보였고 수수료 행 대기는 표본에 없었다. 교착 계수는 모든 칸에서 증가 0이다. api-1 CPU 평균(Prometheus)은 12마켓 칸에서 74~77%였고 P1b만 22.9%로 낮았다. 같은 칸의 러너 표본도 4.1%로 낮았는데, 접수율은 다른 P1 칸과 비슷해 계측 쪽 문제로 보이지만 원인은 확인하지 않았다.

## X6 R1 스택 판 (NOT RUN)

설계 7-3 X6은 S2 이미지를 이미지 id로 띄워야 한다. 이 단계는 실험 스택을 `exchange-chaos-app:pml` 태그로만 띄우고, 그 태그는 S3 이미지로 옮겨졌다. 앞 주행과 같은 이유로 돌리지 않았다. `ProtocolRollbackR1Test`가 Testcontainers 일회용 DB에서 일부를 대신한다(정산 본체는 S3 바이너리의 P1 정산이라 부분 대체).

## X7 감사 문장의 누적 행 비용 (PASS)

스택 없이 폐기용 `postgres:17-alpine` 컨테이너 하나(`--rm`, 127.0.0.1 무작위 포트, 이름 `pml-probe-s3scale-x7-*`)에서 [X7/s3-scale-probe-12m.sh](X7/s3-scale-probe-12m.sh)를 돌렸다. 스크립트는 앞 주행 것을 그대로 옮겼다. 저장소 V0~V52를 올리고 12마켓 시드 위에 P2 epoch 합성 주문 생성 명령을 넣었으며, 마켓마다 마지막 300건만 정산 전으로 두었다. 트리거를 끄고 넣은 합성 행이라 C14' 값 12는 원장 불일치의 결과이고 시간만 본다. 권위 run과 같은 `BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY` 안에서 `EXPLAIN (ANALYZE, BUFFERS)`를 두 번 돌려 둘째 `Execution Time`을 썼다.

| 문장 | 100만 건 JIT 켬 | 100만 건 JIT 끔 | 1,000만 건 JIT 켬 | 1,000만 건 JIT 끔 | 판정 문턱(100만 건) |
|---|---:|---:|---:|---:|---|
| C14'(상시) | 533 | 14 | 537 | 13 | JIT 켬 1,500ms 이하, PASS |
| C31 앱 판(권위 run) | 1,684 | 625 | 1,528 | 509 | JIT 끔 1,500ms 이하, PASS |
| 게이지 | 352 | 12 | 328 | 12 | JIT 끔 100ms 이하, PASS |
| C31 전수 판(하네스 SQL) | 7,491 | 6,433 | 64,144 | 62,923 | 기록 |
| 전환 선행 조건 | 188 | 183 | 1,932 | 1,880 | 기록 |
| C05 | 59 | 59 | 813 | 704 | 기록 |

적재와 `VACUUM ANALYZE`는 100만 건 40초·1,903MB, 1,000만 건 548초·18GB였다. 잎 수는 두 DB 모두 `command_journal` 16, `event_journal`·`ledger_entries`·`trades` 15다. 원자료는 [X7/n1000000](X7/n1000000/)과 [X7/n10000000](X7/n10000000/)의 `probe.log`·`timings.json`·`plans/`다. 커서로 경계를 둔 C14'·게이지와 창을 둔 C31 앱 판은 행 수가 열 배가 돼도 거의 그대로였고, 전수 판 셋(C31 전수, 전환 선행 조건, C05)은 행 수에 비례했다. 앞 주행과 같은 결론이다.

## 한계

- 칸·실험마다 1회다(E5만 첫 회와 재주행). 로컬 Docker Desktop 단일 호스트라 CPU와 디스크를 스택 전체가 나눠 쓴다. 주행 동안 호스트에서 게임 클라이언트 등 사용자 프로그램이 함께 돌고 있었다. 이 기록은 AWS의 장애나 처리량을 대신하지 않는다.
- X5 (i)의 잡음 규칙은 P1 칸만 본다. P2 두 칸의 차이(95.0, 140.0)는 규칙 밖이라 판정에 쓰지 않았고, 가장 불리한 짝으로도 손실이 0.20 아래임을 따로 보였다. 칸 서너 개로는 통계적 신뢰구간이 아니다.
- E5와 E7 스크립트의 사후 run 시점 변경은 이 관문에서 AI가 정했다. E7은 주행 전, E5는 첫 회 FAIL 뒤에 고쳤다. E5 재주행 PASS는 고친 절차의 결과이고 첫 회의 판정을 대신하지 않는다.
- X1의 봇은 목표 300 OPS에 못 미쳤다(실제 약 27.7/s). 원인은 재지 않았다. 앞 주행과 같다.
- E7의 잔고 행 최대 대기는 2초 간격 표본이라 짧은 대기를 놓칠 수 있다. 승격 획득이 멈춘 리더의 세션을 기다린 시간은 pg 로그와 승격 시각을 대조한 추론이다.
- 경보 `EdgeProbeDown`·`EdgeProbeSlow`는 스택을 세운 직후부터 떴다. 판정 축이 아니어서 원인은 보지 않았다.
- k6 로그는 `harness/evidence/k6-rt-*.log`(Git 무시 대상)에, 컨테이너 로그 원문은 `%TEMP%\pml-s3-logs`에 있다. 러너가 칸 이름으로 파일을 정하므로 앞 주행의 `k6-rt-P1a·P2a·P1b·P2b`와 그 `h` 판 로그는 이번 주행이 덮어썼다(`P1c`·`P2c`는 앞 주행 것이다).
