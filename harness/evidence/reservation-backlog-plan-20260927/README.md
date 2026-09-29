# 정산 전 예약 감시 질의의 실행 계획 (2026-09-27)

## 목적

- 로컬 시연 스택에서 부하가 없는 구간에도 주 DB CPU가 약 10초 간격으로 10%와 2코어 가득 사이를 오갔다(사용자 관찰. 이 기록의 표본은 결과 4). 사용자가 `pg_stat_activity`와 `pg_stat_statements`로 [ReservationBacklogMonitor](../../../server/app/src/main/java/kr/exchange/ledger/ReservationBacklogMonitor.java)의 게이지 질의를 원인으로 짚었다. 이 기록은 느린 부분을 실행 계획으로 확인하고, 뜻을 바꾸지 않은 새 모양의 계획과 시간을 같은 스택에서 잰 것이다.
- 같은 모양인 C14'의 정산 전 예약 항(`InvariantCheck.C14_LOCKED_RESERVATION`, 하네스 검증설계 §4-1)도 함께 쟀다.

## 조건

- compose 프로젝트 `exchange-chaos`, Patroni 엄격 동기 두 노드다. 측정 때 주 DB는 `chaos-db-1`(db-1, 타임라인 15), 동기 예비는 db-2였다(측정 전에 `pwsh -NoProfile -File C:\dev\exchange\harness\scripts\demo-db-failover.ps1 -Action status`로 확인, 리스 epoch 40, 앱 리더 chaos-api-1, [db-status.txt](db-status.txt)). DB 컨테이너는 2코어·2GiB이고 PostgreSQL 17.11, `shared_buffers` 256MB, `work_mem` 4MB, `random_page_cost` 4, `effective_cache_size` 1GB, JIT 켬이다([db-facts.txt](db-facts.txt)).
- 앱은 dev 프로필이라 기본 풀에 문장 상한이 없다. 앱과 봇은 고치기 전 이미지 그대로 돌았다. 컨테이너 재시작, 장애 주입, 쓰기, 설정 변경은 하지 않았다. 모든 조회는 `PGOPTIONS='-c default_transaction_read_only=on'`으로 연 `docker exec chaos-db-1 psql -h /var/run/postgresql -U postgres -d exchange` 세션에서 했고, 계획과 시간은 그 안의 `BEGIN READ ONLY` 트랜잭션에서 재고 `ROLLBACK`으로 닫았다.
- 데이터. 봇이 계속 주문을 내 측정(12:31~12:44 UTC) 동안 명령이 약 60만~62만 행이었다. 12:56 UTC 기록은 command_journal 616,372행(NEW 532,139), event_journal 1,077,963행, orders 532,139행, 레지스트리 1,077,963행, order_reservations 0행(P1), 마켓 12개다. 명령은 모두 옛 전역 seq 잎 `command_journal_p0`에 있고, 이벤트는 마켓 대역 잎 b1·b2·b4와 옛 잎 p0에 있다. 명령과 이벤트가 있는 마켓은 1·2·4번 셋이고 셋 다 커서 위 NEW가 0건이다. 나머지 아홉 마켓은 명령도 이벤트도 없다.
- 통계. 이벤트 대역 잎 b1·b2·b4는 `market_id` 고유값이 1이다. 가시성 지도에 올라간 쪽은 b2 3,212/3,224, b1 3,830/4,380, b4 5,127/7,213이다. `command_journal_p0`의 `market_id` 상관계수는 0.43, `seq`는 0.995다.
- `pg_stat_statements`(이 노드의 통계 초기화 12:11:41 UTC부터 12:56:44 UTC까지): 게이지 질의 396회, 평균 4,910.0ms, 최소 1,852.7ms, 최대 8,897.0ms, 합 1,944.4초. 45분 동안 이 질의 하나가 평균 약 0.72코어를 쓴 셈이다(1,944.4초 / 2,703초). C14' 전체 문장은 6회, 평균 3,459.0ms였다.

## 결과 1. 느린 곳 (EXPLAIN ANALYZE, BUFFERS, JIT 끔)

[explain-gauge-before.txt](explain-gauge-before.txt)는 실행 1,973.0ms(계획 5.4ms 별도), 공유 버퍼 적중 2,139,818·읽기 225,270이다. 비용은 두 LATERAL에서 났다.

- 명령 조회. `command_journal_p0`에서 `(market_id, seq)` 색인이 아니라 PK(`seq`)를 골라 `seq > 커서`로 훑으며 `market_id`와 `command_type`을 필터로 걸렀다. 명령이 없는 아홉 마켓은 커서가 -1이라 p0 전체를 훑었다. 반복당 평균 452,064행을 버렸고(12번 반복) 버퍼 1,571,940을 썼다.
- 커서 조회. 한 마켓만 담긴 이벤트 대역 잎 b1·b4에서 `source_cmd_seq` 단일 색인(`idx_evt_src`)을 역순으로 훑으며 `market_id`를 필터로 걸렀다. 그 잎의 마켓이 아닌 11개 마켓마다 잎 전체를 훑었다(b1 반복당 평균 212,120행, b4 306,100행을 버림). p0·b2는 `(market_id, source_cmd_seq, event_ordinal)` 색인을 썼다.
- 원인. 두 조회의 `market_id = m.id`는 계획 시점에 값을 모르는 인자다. 계획기는 이를 상수로 보고 정렬 키에서 `market_id`를 지우므로, `seq`(또는 `source_cmd_seq`) 단일 색인도 같은 순서를 준다고 판단한다. 고유값이 1인 잎에서는 마켓 조건이 모든 행에 맞는다고 추정해 단일 색인 경로가 싸 보인다. b2만 복합 색인을 고른 것은 가시성 지도 비율이 높아 인덱스 전용 스캔이 싸게 추정된 탓으로 본다(추론). VACUUM 시점에 따라 계획이 바뀌는 모양이다.
- 가설 확인. '이벤트가 없는 마켓의 NEW를 10만 건까지 훑는다'는 이 스택에 해당하지 않는다. 이벤트가 없는 아홉 마켓은 명령도 없다. 훑은 것은 그 마켓의 명령이 아니라 다른 마켓의 명령과 이벤트다. '(market_id, command_type, seq) 색인 부재'도 원인이 아니다. `(market_id, seq)` 색인이 이미 있는데 계획기가 쓰지 않았다.

## 결과 2. 새 모양

마켓 조건을 `BETWEEN m.id AND m.id`로 쓰고 `(market_id, …)` 순서로 정렬했다. 커서는 `ORDER BY e.market_id DESC, e.source_cmd_seq DESC LIMIT 1`을 `LEFT JOIN LATERAL … ON true`로 읽고, 명령은 `ORDER BY c.market_id, c.seq LIMIT 100000`으로 읽는다. 값은 같다. 범위의 양끝이 같아 행 집합이 등호와 같고, 한 마켓 안의 `(market_id, seq)` 순서는 `seq` 순서와 같으며, 이벤트가 없으면 커서가 NULL이다(`source_cmd_seq`는 NOT NULL). 범위 조건은 등가 클래스를 만들지 않으므로 이 순서를 주는 색인은 `idx_cmd_market`과 `idx_evt_market_cmd`뿐이다. 다른 경로는 걸러 낸 행 전체를 정렬해야 한다. 또 양끝이 인자인 범위의 선택도는 잎 통계와 무관한 기본값(0.005)이라, 고유값이 1인 잎에서도 마켓 조건이 좁게 추정된다. 등호는 1/고유값으로 잡혀 그 잎에서 모든 행이 맞는다고 추정됐다. 새 색인이나 마이그레이션은 없다. SQL 원문은 [sql/](sql/)에 있다.

[explain-gauge-after.txt](explain-gauge-after.txt)는 실행 1.130ms(계획 6.5ms 별도), 공유 버퍼 적중 445·읽기 7이다. 커서는 모든 잎에서 `idx_evt_market_cmd` 역순 인덱스 전용 스캔, 명령은 `idx_cmd_market` 비트맵 스캔이다.

[plans-after-cost-settings.txt](plans-after-cost-settings.txt)는 설정을 트랜잭션 안에서 바꿔 계획만 본 것이다. `random_page_cost` 1.1·4·20, `enable_bitmapscan` 끔, `enable_incremental_sort` 끔에서 커서는 늘 `idx_evt_market_cmd`, 명령은 `idx_cmd_market`(색인 또는 비트맵)이었다. 명령 PK와 `idx_evt_src`는 한 번도 나오지 않았다. 현실적이지 않은 `random_page_cost` 100에서만 p0 순차 스캔으로 바뀌었고, 그 계획이면 빈 마켓마다 p0를 통째로 읽는 모양이 돌아온다. 기본 설정의 명령 조회는 비트맵 스캔 뒤 정렬이라 LIMIT가 결과 행 수는 묶어도 읽는 양은 그 마켓의 커서 위 NEW 수에 비례한다(이 스택은 0건).

## 결과 3. 전후 시간 (같은 스택, 12:43:12~12:44:03 UTC)

`EXPLAIN (ANALYZE, BUFFERS, TIMING OFF)`를 문장마다 다섯 번 돌린 `Execution Time`이다. 행마다 시계를 읽는 부담을 빼려고 `TIMING OFF`로 쟀다. 원자료는 [timing/](timing/)이다.

| 문장 | 고치기 전(ms) | 고친 뒤(ms) | 공유 버퍼(첫 회, 전 → 후) |
|---|---|---|---|
| 게이지, JIT 끔(감시 빈과 같은 조건) | 2,754.9 / 5,619.8 / 2,344.1 / 2,311.4 / 2,366.6 | 0.866 / 0.339 / 0.481 / 0.317 / 0.337 | 2,677,880 → 452 |
| C14' 전체, JIT 켬(상시 run과 같은 조건) | 2,808.0 / 2,667.5 / 5,141.2 / 3,779.6 / 2,724.8 | 58.9 / 42.7 / 39.3 / 40.1 / 41.4 | 2,679,064 → 598 |
| C14' 전체, JIT 끔(권위 run과 같은 조건) | 2,531.3 / 2,284.4 / 2,246.1 / 2,667.6 / 5,727.7 | 2.156 / 1.016 / 0.852 / 0.825 / 0.781 | 2,679,405 → 662 |

- 고치기 전 값이 2.2~5.7초로 흔들린 것은 같은 DB에서 앱 노드들의 게이지 질의가 겹쳐 돈 탓으로 본다(추론).
- C14'를 JIT 켬으로 돌리면 고친 뒤에도 40ms 안팎이다. 대부분 JIT 컴파일이고 실행은 1ms 안팎이다.
- 운영 프로필(prod)은 기본 풀에 `statement_timeout` 3초를 건다(`application.yml`). 감시 빈과 상시 C14'가 그 풀을 쓰므로, 같은 데이터라면 고치기 전 문장은 자주 취소돼 게이지가 NaN이 되고 C14' 상시 판정이 실패했을 것이다(설정과 위 시간에서 낸 추론이며 prod 프로필에서 재지 않았다).

## 결과 4. 배포 전 DB CPU

[db-cpu-before-deploy.txt](db-cpu-before-deploy.txt)는 12:58:06~12:59:38 UTC에 `docker stats --no-stream`을 30회 연달아 부른 표본이다. 이 동안 다른 질의는 보내지 않았다. 앱은 고치기 전 이미지라 이 값은 고치기 전 상태다. `chaos-db-1`은 평균 62.9%(2코어 기준 최대 200%)였고 30회 가운데 7회가 150%를 넘었다. 고친 코드를 스택에 올린 뒤의 CPU는 재지 않았다(NOT RUN). 앱 재배포가 필요하고 이 작업의 범위 밖이다.

## 결과 5. 재현 시험

`ReservationBacklogMonitorTest.backlogQueriesDoNotScanJournalsWhenMostMarketsAreIdle`는 이 스택의 모양을 Testcontainers DB에 합성한다. 12개 마켓, 옛 전역 seq 잎에 세 마켓을 섞은 명령 20만 건, 마켓 대역 잎에 한 마켓씩 둔 이벤트, 이벤트가 없는 마켓과 커서 위의 정산 전 예약, 커서 아래에서 정산된 예약이다. 게이지 값(대기 2건, 가장 오래된 예약 90초 이상)과 C14' 값(1계정)을 양성 증거로 먼저 확인한 뒤, 게이지와 C14' 문장의 공유 버퍼 접근이 `command_journal_p0` 잎 쪽수의 절반보다 적은지 두 문장 모두 본다.

고치기 전 코드에서 이 시험은 게이지 문장에서 실패했다. 그때 시험은 문장을 차례로 보다 첫 실패에서 멈췄고, 단언 메시지는 '저널 잎을 훑었다(공유 버퍼 71013, p0 잎 2899쪽)'였다. 값 단언 셋은 통과했다. 검토 지적에 따라 두 문장을 모두 잰 뒤 함께 판정하게 바꿨고, 고친 코드에서 통과한다. 실행은 `C:\dev\exchange-backlog\server`(이 작업 트리의 연결 경로)에서 `.\gradlew.bat :app:test --tests "kr.exchange.ledger.ReservationBacklogMonitorTest"`로 했다.

통과 여유와 C14' 쪽 음성 대조는 [test-db-blocks.txt](test-db-blocks.txt)가 보인다. 같은 합성 데이터에서 옛 문장 둘(`sql/*-before.sql`)과 새 문장 둘의 공유 버퍼를 한 번에 잰 일회성 측정이다. 측정용 시험 클래스는 시험 트리에 두고 한 번 돌린 뒤 지웠고, 원문은 [test-db-blocks-probe.java](test-db-blocks-probe.java)에 그대로 남겼다(시험 트리 밖이라 빌드되지 않는다. 다시 재려면 `server/app/src/test/java/kr/exchange/ledger/`에 되돌려 `--tests` 로 돌린다). 명령 잎 2,899쪽, 문턱 1,449에서 게이지는 71,013에서 446, C14'는 71,015에서 448이었다. 값은 옛·새 모두 게이지 2건·92초, C14' 1계정이었다.

## 결과 6. 같은 모양의 다른 문장 (이번에 고치지 않음)

C31 앱 판(`InvariantCheck.C31_RESERVATION_LINK`, 권위 run 전용, 마켓마다 최근 NEW 5,000건)도 `c.market_id = m.id … ORDER BY c.seq DESC LIMIT 5000` 모양이다. 13:56:55 UTC에 `docker exec chaos-db-1`로 권위 run 조건(`REPEATABLE READ READ ONLY`, JIT 끔)에서 한 번 쟀다([explain-c31-app-unchanged.txt](explain-c31-app-unchanged.txt)). 그 사이 다른 세션의 시연 녹화에서 DB 전환이 두 번 있었고, 직전 상태 조회에서 주 DB는 다시 db-1(`chaos-db-1`), 타임라인 17이었다([db-status.txt](db-status.txt)). 데이터도 앞 측정보다 늘었다. 실행 4,335.9ms, 공유 버퍼 적중 1,681,813·읽기 142,375였다. 명령 옛 잎을 PK 역순으로 훑으며 반복당 평균 500,346행을 버렸다(12번 반복). 운영 풀의 문장 상한 3초를 넘으므로 운영 프로필이면 권위 run의 이 판정이 취소될 수 있다(추론). 사용자 요청 범위 밖이라 고치지 않았다.

## 파일

| 파일 | 내용 |
|---|---|
| [db-facts.txt](db-facts.txt) | 설정, `pg_stat_statements`, 표 통계, 마켓별 커서와 명령 분포, 열·색인·트리거, 가시성 지도와 열 통계(12:56 UTC) |
| [explain-gauge-before.txt](explain-gauge-before.txt), [explain-gauge-after.txt](explain-gauge-after.txt) | 게이지 전후 `EXPLAIN (ANALYZE, BUFFERS)`, JIT 끔 |
| [plans-after-cost-settings.txt](plans-after-cost-settings.txt) | 비용 설정별 새 게이지 계획(`COSTS OFF`, 실행 없음) |
| [timing/](timing/) | 결과 3의 여섯 조합 원자료 |
| [sql/](sql/) | 전후 게이지와 C14' 문장, 사실 조회와 분포 조회 SQL |
| [db-cpu-before-deploy.txt](db-cpu-before-deploy.txt) | 결과 4의 CPU 표본 |
| [test-db-blocks.txt](test-db-blocks.txt), [test-db-blocks-probe.java](test-db-blocks-probe.java) | 결과 5의 합성 데이터 측정 출력(옛·새 문장의 공유 버퍼와 값)과 측정에 쓴 시험 원문 |
| [db-status.txt](db-status.txt) | 두 측정 직전의 Patroni 상태 조회 출력(주 DB, 타임라인) |
| [explain-c31-app-unchanged.txt](explain-c31-app-unchanged.txt) | 결과 6의 C31 앱 판 계획(고치지 않음) |
