# V27 원장 게시 재조회 제거 전후 대조 (2026-09-06)

`post_ledger_transaction` 이 분개를 넣은 뒤 같은 거래를 다시 읽던 세 쿼리를 입력 JSONB 계산으로
옮겼다. 검증 내용은 바꾸지 않았다.

## 조건

전후가 같다. `condition.sh` 하나가 전 절차를 돌린다.

| 항목 | 값 |
|---|---|
| 스택 | 카오스 full 프로파일, 볼륨 초기화 후 기동 |
| CPU | 앱 2 vCPU, DB 4 vCPU (`docker update`) |
| 봇 | 정지 |
| 고정 부하 | k6 `order_flow.js` smoke, 주문 30/s, 조회 20/s, WS 1 |
| 계단 | 주문 40 / 60 / 80 / 100 |
| 계측 | `pg_stat_statements`(주행 전 reset), `docker stats` 3초 간격 |

## 주문당 DB 시간

| 항목 | before (V26) | after (V27) | 변화 |
|---|---:|---:|---:|
| 주문 접수 | 9,585 | 10,275 | +7.2% |
| DB 총 시간 | 57,195.0ms | 47,459.5ms | -17.0% |
| **주문당 DB 시간** | **5.967ms** | **4.619ms** | **-22.6%** |

## 함수별

| 함수 | before 평균 | after 평균 | before 누적 | after 누적 |
|---|---:|---:|---:|---:|
| `reserve_funds` | 2.435ms | 1.444ms | 23,335.3ms | 14,839.7ms |
| `apply_position_cost` | 0.593ms | 0.589ms | 5,721.4ms | 6,087.6ms |
| `post_ledger_transaction('TRADE')` | 3.245ms | 2.099ms | 227.1ms | 111.3ms |

`apply_position_cost` 는 이번 회차에서 건드리지 않았고 평균이 0.593 에서 0.589 로 사실상
불변이다. 대조군 역할을 한다. TRADE 게시는 호출이 70 건과 53 건뿐이라 표본이 얇다.

## 제거된 SQL 호출

before 에서 게시마다 돌던 세 쿼리가 after 에서는 `pg_stat_statements` 에 나타나지 않는다.

| 쿼리 | before 호출 | before 누적 | after |
|---|---:|---:|---|
| 양자화 검증(`ledger_entries JOIN assets`) | 9,655 | 1,155.4ms | 없음 |
| 잔고 델타 집계(`GROUP BY account_id`) | 9,655 | 632.8ms | 없음 |
| 자산별 합 0 검증(`GROUP BY asset_id`) | 9,655 | 570.7ms | 없음 |

주행당 28,965 호출이 사라졌다.

세 쿼리의 직접 비용 합은 2,358.9ms 인데 DB 총 시간은 9,735.5ms 줄었다. 차이의 원인은 이
회차가 가르지 않는다. 함수 안 중첩 쿼리라 `pg_stat_statements` 가 바깥 호출과 안쪽 쿼리에
시간을 이중 계상하는 것이 한 가능성이고, 인덱스와 힙 접근이 사라지면서 생긴 이차 효과가 다른
가능성이다. 확정은 미측정이다.

## CPU

| 컨테이너 | before 평균 | before 최대 | after 평균 | after 최대 |
|---|---:|---:|---:|---:|
| `chaos-pg` | 40.2% | 80.3% | 35.2% | 77.8% |
| `chaos-api-1` | 32.0% | 85.7% | 31.7% | 99.0% |

## 계단과 지연

| 주입 주문/s | before 접수 | after 접수 | before p95 | after p95 |
|---|---:|---:|---:|---:|
| 40 | 10,383 | 11,558 | 117.00ms | 93.15ms |
| 60 | 11,519 | 13,261 | 115.46ms | 96.33ms |
| 80 | 12,020 | 13,886 | 119.84ms | 98.89ms |
| 100 | 12,452 | 14,501 | 124.99ms | 100.06ms |

**최대 TPS 는 이 회차가 판정하지 못한다.** 주입 100/s 에서도 p95 가 100ms 대라 전후 어느
조건에서도 천장에 닿지 않았다. `docker update --cpus` 가 gateoff 회차들의 2 vCPU 고정과 같은
제약을 만들지 못한 것으로 보인다. 같은 주입률에서 접수량이 11~17% 늘고 p95 가 20% 안팎
낮아진 것까지가 이 회차가 말할 수 있는 범위다.

## 무결성

음성 대조를 함수 직접 호출로 수행했다. 괄호 안은 발생 지점이다.

| 시험 | 결과 |
|---|---|
| 정상 분개(KRW scale 0, 합 0) | 통과. txn 발급 |
| 자산별 합 0 아님 | 거부. `원장 불균형`(line 67) |
| 양자화 위반(KRW scale 0 에 소수) | 거부. `양자화 위반`(line 40) |
| 양자화 위반(BTC scale 8 에 9자리) | 거부. `양자화 위반`(line 40) |
| BTC scale 8 에 8자리 | 통과 |
| ETH scale 18 에 정확히 18자리 | 통과 |
| ETH scale 18 에 20자리 | 통과. 저장값 `0.100000000000000000` |
| 없는 account | 거부. FK `ledger_entries_account_id_asset_id_fkey` |
| 없는 asset | 거부. 같은 FK |
| 빈 entries | 거부. `빈 거래 게시 금지` |

마지막 두 자리가 이번 변경의 핵심 경계다. `amount` 가 `NUMERIC(40,18)` 이라 INSERT 가 18
자리로 반올림하므로, 입력 원값으로 검사하면 20자리 입력이 거부되어 종전과 판정이 달라진다.
검사 전에 같은 캐스팅을 걸어 저장값과 동일한 값을 보게 했고, 위 시험이 종전 동작을 유지함을
확인한다.

라이브 DB 전수 검사(고정 부하와 계단, 음성 대조를 모두 거친 상태).

| 검사 | 결과 |
|---|---|
| `balance_ledger_check` mismatch | 0 |
| 자산별 합 0 아닌 자산 | 0 |
| 미봉인 거래 | 0 |
| `position_cost_qty_check` 불일치 | 0 |
| 판정 대상 | 분개 129,026 · 주문 63,536 · 체결 79 |

`gradlew build` 전량 BUILD SUCCESSFUL. 66클래스 438테스트 실패 0 스킵 0.

## 파일

| 파일 | 내용 |
|---|---|
| `condition.sh` | 한 조건분 전 절차. 전후가 같은 경로를 타게 하는 단일 원천 |
| `measure.sh` | 고정 부하와 `pg_stat_statements` 수집 |
| `ladder.sh` | 계단 |
| `before_*`, `after_*` | 각 조건의 수집 결과 |

`pg_stat_statements` 는 정본 compose 에 없다. 측정 전용 오버레이로 켰고 그 파일은 저장소에
두지 않았다. 재현하려면 `chaos-pg` 의 command 에 `shared_preload_libraries=pg_stat_statements`
를 더한다.
