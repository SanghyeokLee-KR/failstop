# 마켓 판독 SQL 실행 계획 실측 (2026-09-13)

## 목적

- ADR 0022(`docs/adr/0022-market-partitioned-command-journal.md`) 작업 분해 WP6 의 착수 조건이다. WP 번호는 ADR 본문에 없는 작업 분해 명칭이다.
- 대상은 `EventJournalReader` 의 `readAfterForMarket` 모던 분기(q1)와 레거시 분기(q2), `materializedThroughByMarket`(q3), `CommandJournalReader.readAfterForMarket`(qc)이다.
- 판정 대상은 셋이다. `(market_id, source_cmd_seq, event_ordinal)` 후보 인덱스의 필요 여부, 반복 실행에서의 generic 계획 전환, 경계 SQL 의 JIT 발화.

## 조건

- PostgreSQL 17.10(`postgres:17-alpine`) 버린 컨테이너, 기본 설정이다. shared_buffers 128MB, work_mem 4MB, random_page_cost 4, jit on, jit_above_cost 100000, 인라인·최적화 임계 500000(`SHOW`, `current_setting` 조회. `results/round1/sizes_noidx.txt`, `results/round2/B_jit_settings.txt`).
- 마이그레이션은 V0~V35 적용, 레거시 적재, V36~V41 적용 순서다. 파일마다 `psql -1` 트랜잭션 하나, 트리거는 켠 채로 넣었다.
- 규모는 `count(*)` 와 `pg_relation_size` 로 쟀다(`sizes_noidx.txt`).
  - command_journal 400,000행. 경계 이하 100,000, 초과 300,000. 잎 16개 중 행은 전부 `command_journal_p0`(146MB).
  - event_journal 740,266행. market_id NULL 185,032, 모던 555,234. 잎 2개 중 행은 전부 `event_journal_p0`(125MB). 명령당 이벤트 1~3건.
- 마켓 12개, 결정론 해시 분포(`bench.mkt`)이고 레거시 구간도 같은 분포다. 마켓 1(편중)은 명령 159,634(39.9%)·이벤트 295,232, 마켓 12(소형)는 명령 4,105(1.0%)·이벤트 7,565, 마켓 2~11 은 명령 각 약 5.9%다.
- 구멍(이벤트 없는 명령)은 마켓 1 마지막 3건(399990~399994)과 마켓 5 마지막 5건(399938~399983)이다. 경계 아래 구멍은 0건이다.
- 레거시 경계는 `journal_axis_boundary.pre_v36_event = 100000` 이다(V40 이 박제, 구멍 검사 통과).
- 채번 정합: 마켓별 카운터와 max(market_seq)가 12마켓 전부 같고, `event_seq = market_id*10^7 + market_seq` 불일치는 0건이다.
- 적재 뒤 `VACUUM (ANALYZE)` 를 돌렸다.
- 측정 방식: 조합마다 psql 세션 하나에서 원문 SQL 을 PREPARE 하고(인자 타입은 JDBC 바인딩과 같게) `EXPLAIN (ANALYZE, BUFFERS)` 3회 Execution Time 중앙값을 적는다. custom 은 `force_custom_plan`, generic 은 `force_generic_plan`, auto 는 같은 인자 EXECUTE 10회 뒤 11~13회차다. g/c 는 `pg_prepared_statements` 의 generic_plans/custom_plans 다.
- 후보 인덱스 생성은 347.7ms(1회차), 366.6ms(재실행)였다(`\timing`). 크기는 29MB(`pg_relation_size`).

## 결과 1. q1·q2·qc 후보 인덱스 유무 (1회차)

| 조합(마켓·커서) | 없음 custom | 있음 custom | 없음 generic | 있음 generic |
|---|---:|---:|---:|---:|
| q1 m12 시작 | 4.265 | 0.344 | 4.474 | 4.232 |
| q1 m12 중간 | 3.969 | 0.317 | 4.086 | 4.175 |
| q1 m1 시작 | 0.392 | 0.512 | 0.432 | 0.393 |
| q2 m12 시작(0,-1) | 15.470 | 1.111 | 24.485 | 23.844 |
| q2 m12 bound=0 (현행 첫 호출) | 17.663 | 0.401 | 18.488 | 17.792 |
| q2 m1 시작(0,-1) | 14.176 | 14.251 | 14.648 | 14.721 |
| qc m12 시작 | 0.589 | 0.520 | 6.627 | 6.552 |

- 단위는 ms 다. 전 조합 값은 `results/round1/summary.csv` 에 있다.
- 인덱스 없는 q1 은 source_cmd_seq 인덱스 범위 스캔 뒤 market_id 를 Filter 로 거르고 Incremental Sort 를 붙인다. m12 시작에서 52,203행을 버리고 버퍼 1,645를 쓴다.
- 인덱스 있는 custom m12 는 후보 인덱스 Index Cond 로 Sort 없이 버퍼 274다. m1 은 두 상태 모두 source 인덱스 경로이고 776행을 버린다.
- generic 계획은 두 상태 모두 후보 인덱스를 쓰지 않는다.
- q2 m1 은 모던 하위질의가 경계 아래 레거시 185,069행을 걸러낸다. 인덱스 유무와 무관하다.
- qc m12 generic 은 pkey 스캔에 market_id Filter 를 걸어 47,272행을 버린다.
- 부분 인덱스 `idx_evt_market_seq` 는 1회차 두 실행의 어느 계획에도 나오지 않는다(원문 grep 0건).
- 같은 스크립트로 새 컨테이너에서 재실행했다(`rerun_summary.csv`). 96조합 총 버퍼 수가 전부 같았다. 중앙값은 대체로 ±20% 안이고 generic 일부는 최대 1.8배까지 흔들렸다.

## 결과 2. market_id 리터럴 대 인자, auto 전환 (2회차 A)

| 조합 | 인자 auto: g/c, ms, 버린 행 | 리터럴 auto: g/c, ms, 버린 행 |
|---|---|---|
| q1 m12 시작 | 8/5, 3.963, 52,203 | 8/5, 0.295, 0(후보 인덱스) |
| q1 m12 중간 | 8/5, 3.924, 49,575 | 8/5, 0.299, 0 |
| q1 m1 시작 | 0/13, 0.406, 776 | 0/13, 0.409, 776 |
| q2 m12 시작 | 8/5, 23.991, 190,347 | 8/5, 1.189, 0 |
| q2 m12 bound=0 | 8/5, 17.561, 237,812 | 8/5, 0.647, 0 |
| q2 m1 시작 | 8/5, 20.096, 185,069 | 0/13, 14.354, 185,069 |
| q2 m1 bound=0 | 0/13, 13.426, 185,807 | 0/13, 13.573, 185,807 |

- 전 조합(auto, custom, generic)은 `results/round2/A_summary.txt` 에 있다.
- 1회차 psql 세션 실험(`auto_summary.txt`): m12 를 10회 실행한 뒤 generic 6/custom 5로 전환해 4.37ms 다(`results/round1/auto_summary.txt`, 재주행 `rerun_auto_summary.txt` 는 4.00ms). m1 을 10회 실행한 뒤의 m12 는 custom 을 유지해 0.8~1.0ms 다.
- 진단(해법 아님): q2 m1 에 정렬 비활성과 jit off 를 걸면 모던 하위질의가 후보 인덱스를 쓴다. bound=0 은 0.513ms 지만, 시작 커서는 레거시 하위질의가 Hash Join 으로 185,032건을 읽어 83.8ms 로 느려진다.

## 결과 3. 경계 SQL 단일 문장, 커서 전달 형태와 JIT (2회차 B, custom)

| 형태 | 커서 | 커서 추정 | floors 추정 | 최상위 비용 | JIT ms | 실행 ms |
|---|---|---:|---:|---:|---:|---:|
| v1 jsonb_each_text | (a) head 근처 | 100 | 200 | 2,002,674 | 344.8 | 348.1 |
| v1 jsonb_each_text | (b) 빈 입력 | 100 | 200 | 2,002,674 | 349.8 | 608.5 |
| v2 unnest 인라인 | (a) | 12 | 112 | 1,121,615 | 307.0 | 310.2 |
| v2 unnest 인라인 | (b) | 1 | 101 | 1,011,483 | 304.8 | 549.0 |
| v3 unnest CTE | (a) | 12 | 112 | 1,121,615 | 316.6 | 319.7 |
| v3 unnest CTE | (b) | 1 | 101 | 1,011,483 | 317.4 | 581.1 |

- JIT 는 전 변형, 전 회차(3/3)에서 인라인·최적화까지 켜졌다. auto 와 generic 도 같다(`B_summary.txt`).
- v1 을 jit off 로 돌린 1회차 실측: (a) 3.064ms, (b) 219.339ms.
  - (b) 비용은 모던 명령 300,000건의 anti join 탐색이고 버퍼 hit 는 901,944다.
- 최상위 비용은 모든 변형에서 floors 추정 × 약 10,014다(측정 비율). market_ids 재귀 CTE 추정 101행이 커서 형태와 무관한 하한이다.
- 추정: JIT 가 꺼지려면 floors 추정이 약 10행 미만이어야 한다.

## 결과 4. 마켓당 경계 문장, 12마켓 합, generic 고착 (3회차)

| floor | custom | auto | 강제 generic | 단일 문장 jit on / off (1회차) |
|---|---:|---:|---:|---:|
| (a) 엔진 커서 head 근처 | 7.560 | 6.696 | 112.133 | 320 / 3.06 |
| (b) legacyBound | 191.438 | 176.707 | 180.235 | 547 / 219 |
| (c) 0 | 218.095 | 204.715 | 214.067 | 미측정 |

- 합은 12마켓 마켓당 문장 중앙값의 합에 마켓 집합 판독 중앙값(1.03~1.10ms)을 더한 값이다(`totals.txt`).
- JIT 는 108세션 × 3회와 마켓 집합 판독 어디에서도 켜지지 않았다. 최대 최상위 비용은 26,430(m1 (c) custom)이다.
- m1 (a): custom 0.305ms, generic 9.055ms. generic 은 워커 2개 병렬 Gather 와 Sort 이고 실제로는 99행만 읽는다.
- m1 (b) custom 은 54.232ms 다. Parallel Hash Right Anti Join 이 이벤트 인덱스 246,755행 × 3을 읽는다. auto 는 generic(8/5)으로 넘어가 40.077ms 다.
- generic 고착(준비문 하나를 12마켓이 공유, `seq_summary.txt` 는 원문에서 파생): (b) 12회와 (a) 24회 뒤 generic 43/custom 5 이고 (a) 12마켓 합 107.796ms 다. (a)만 36회 뒤에는 generic 0/custom 48 이고 7.168ms 다.
- 단일 문장 비교 수치는 1회차의 다른 컨테이너 값이다. 데이터는 같은 스크립트로 만들었다.
- 추정: 마켓별 왕복 11회 추가분 약 6ms(V39 주석의 문장당 약 0.5ms)는 합에 들어 있지 않다.

## 결과 5. 후보 인덱스 쓰기 비용 대리 측정 (2회차 C)

| 회차 | 후보 인덱스 | 시작 행 수 | 총 ms | rows/s |
|---|---|---:|---:|---:|
| noidx_r1 | 없음 | 740,266 | 3,376.6 | 59,231 |
| idx_r1 | 있음 | 940,266 | 3,202.6 | 62,449 |
| noidx_r2 | 없음 | 1,140,266 | 2,899.9 | 68,968 |
| idx_r2 | 있음 | 1,340,266 | 3,270.4 | 61,155 |

- 합성 대량 삽입 대리 측정이다. 정산 경로가 아니다.
- 회차마다 500행씩 400트랜잭션으로 넣었다. 총 시간은 `clock_timestamp` 차이다. 회차 전마다 CHECKPOINT 를 걸었고 인덱스 유무를 교대했다.
- 평균은 없음 3,138ms, 있음 3,237ms 로 차이는 +3.1%다. 인덱스 없음 두 회차끼리의 편차가 16%라 2회로는 차이를 판정하지 못한다.
- 양성 증거: 회차마다 200,000행이 들어갔고 후보 인덱스 잎 수가 0/2였다. 카운터와 max(market_seq)가 같았고 파생식 불일치는 0건이다(`results/round2/C/`).

## 결론

측정이 뒷받침하는 결정은 셋이다.

- **후보 인덱스를 둔다.** 소형 마켓 custom 에서 q1 이 4.27ms 에서 0.34ms, q2 bound=0 이 17.66ms 에서 0.40ms 로 준다. 쓰기 비용은 대리 측정의 잡음 폭 안이다.
- **market_id 를 리터럴로 넣는다.** 소형 마켓이 generic 으로 넘어가도 후보 인덱스를 쓴다. q1 은 3.96ms 에서 0.30ms, q2 는 24.0ms 에서 1.19ms 다.
- **경계 판독을 마켓당 문장으로 나누고 값을 리터럴로 넣어 custom 계획으로 고정한다.** JIT 가 켜지지 않고 (a) 합이 320ms 에서 6.7~7.6ms 로 준다. 고정하지 않고 준비문을 같이 쓰면 generic 에 고착돼 107.8ms 가 된다. 값 리터럴 문장이 force_custom_plan 과 같은 상수 계획을 받는다는 것은 추정이다(리터럴 문장 자체는 미측정).
- 앱 경로에 적용한 뒤의 재측정은 NOT RUN 이다.

## 한계

- 합성 분포다. 해시 분포, 합성 payload 를 썼고 레거시 구간에도 같은 편중을 줬다.
- 점유율 0.1% 이하 마켓과, 커서가 head 에서 먼 조용한 마켓의 마지막 0행 판독은 재지 않았다.
- 편중 마켓 경계 아래 약 14ms(q2 m1 시작, 185,069행 버림)는 풀리지 않았다. 리터럴로도 후보 인덱스로도 바뀌지 않는다.
- 단일 컨테이너, 로컬 소켓, psql 세션에서 쟀다. JDBC 서버측 준비문 거동은 psql PREPARE 로 대신했다(추정).
- 원문 EXPLAIN 433파일(10,379,572바이트)은 저장소 밖에 둔다. 1회차 noidx·idx 와 재실행 202파일, 2회차 A 100·B 18파일, 3회차 runs 111·seq 2파일이다.
- `results/round1/create_index.txt` 의 ERROR 줄은 크기 조회 문장의 셸 인용 실수다. 인덱스 생성은 성공했고 크기는 별도 조회로 29MB 다.
- NOT RUN: 정산 경로 쓰기 비용, 앱 경로의 `SET LOCAL jit = off` 적용, 2회차 드라이버 전 단계 한 번 실행(단계를 나눠 돌렸고 정리는 수동), 3회차 드라이버에 `seq.sh` 를 넣은 판의 전 단계 재실행, 스테이징 때 경로를 고친 드라이버 셋의 재실행.

## 재현

- 요건은 pwsh 7, Docker, 로컬 `postgres:17-alpine` 이다. 저장소 안에서 돌리면 마이그레이션 위치를 git 최상위에서 찾고, 저장소 밖이면 `-Migrations` 로 준다.
- `scripts/round1/run_all.ps1` 순서: 컨테이너 기동, `apply.sh 0 35`, `seed_markets.sql`, `seed_commands.sql`(n=100000), `seed_legacy_events.sql`, `apply.sh 36 41`, `seed_commands.sql`(n=300000), `seed_modern_events.sql`, `VACUUM (ANALYZE)`, `cursors.sql`, `sizes.sql`, `measure.sh noidx`, 후보 인덱스 생성, `measure.sh idx`, `jitoff.sh`, `auto.sh`, `summarize.sh`. 스테이징 전 판으로 끝까지 한 번 재실행해 exit 0 을 확인했다.
- `scripts/round2/run_followup.ps1 -Phases setup,A,B,C,collect,cleanup`
  - A: `prep_a.sql`, `measure_a.sh`, `diag_a_jitoff.sh`, `plan_a.awk`
  - B: `prep_b.sql`, `measure_b.sh`, `plan_b.awk`
  - C: `prep_c.sql`, `measure_c.sh`
  - 적재는 round1 스크립트를 쓴다.
- `scripts/round3/run_followup2.ps1 -Phases setup,M,collect,cleanup`: `prep_pm.sql`, `measure.sh`, `seq.sh`, `plan_pm.awk`, `sum.awk`
- 스테이징 때 바꾼 것은 둘이다.
  - 드라이버 셋의 `-Migrations` 기본값(개발 머신 경로)을 git 최상위 기준 해석으로 바꿨다.
  - round2·round3 가 round1 스크립트를 찾는 경로를 옆 `round1` 디렉터리로 바꿨다.
