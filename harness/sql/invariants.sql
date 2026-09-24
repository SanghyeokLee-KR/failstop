-- ============================================================================
-- invariants.sql
-- 불변식 카탈로그 C01~C29·C31의 psql 직조회본. 검증설계 §4-1 정본을 실제 DDL 컬럼명에 맞춘다
-- (server V1__init.sql, trades.id = event_seq).
-- 실행: psql -U exchange -d exchange -f invariants.sql
-- 판정: 각 쿼리는 위반 행만 낸다. 전 쿼리 0행 = 무결. summary.sql이 같은 술어의 건별 카운트다.
-- C20~C25의 술어 정본은 앱 InvariantCheck enum이고 C24·C25는 V13 검증 뷰다. 사본 산식을 두면
-- 라이브와 감사가 두 벌로 갈리므로 축어 이식만 한다.
-- C16·C19·C13a·C22~C25는 권위 run/정지 상태에서만 유효하다. 라이브 프로젝션 지연·스테일
-- loadtest_acks·미재구축 원가가 오탐을 만든다(§4-2 ④, InvariantCheck.Scope.AUTHORITATIVE_ONLY).
-- C08·C09·C11·C12·C18에는 앱 enum의 90일 윈도가 없다. 전량 스캔이라 위반을 놓치지는 않지만
-- 파티션 축이 서로 달라(trades=created_at, event_journal=event_seq, command_journal=seq)
-- detach를 시작하면 C09·C12가 이미 detach된 상대를 찾으며 거짓 FAIL을 낸다.
-- detach 운용 전까지만 무해하다.
-- ============================================================================
\pset pager off
\timing off

-- 스키마 판정(ADR 0034 S3 판정 어휘, 하네스 검증설계 §4-1). V48 객체 다섯(표 넷과 lease_resource_for_market)과 V50 객체
-- 둘(order_reservations, order_reserve_amount)을 센다. C29·C31 은 대상 객체가 모두 있으면 평가하고, 모두 없으면 N/A(대상
-- 없음), 일부만 있으면 FAIL 이다(반쯤 적용됐거나 손상된 스키마). N/A 는 통과도 미평가(SKIP)도 아니다. C14 는 예약 표가
-- 있으면 정산 전 예약 항을 더한 판, 없으면 그 항 없는 판으로 돈다. 예약이 있을 수 없는 스키마라 같은 판정이다.
-- 무조건 조회하면 ON_ERROR_STOP 경로가 없는 객체에서 멈춰 뒤 판정까지 버리므로 psql 조건문으로 가른다.
SELECT (to_regclass('public.market_lease_partition') IS NOT NULL)::int
     + (to_regclass('public.leader_protocol') IS NOT NULL)::int
     + (to_regclass('public.leader_protocol_history') IS NOT NULL)::int
     + (to_regclass('public.lease_acquisitions') IS NOT NULL)::int
     + (to_regprocedure('public.lease_resource_for_market(smallint)') IS NOT NULL)::int AS v48_objects,
       (to_regclass('public.order_reservations') IS NOT NULL)::int
     + (to_regprocedure('public.order_reserve_amount(smallint,jsonb)') IS NOT NULL)::int AS v50_objects,
       to_regclass('public.order_reservations') IS NOT NULL AS has_reservation_table \gset
SELECT :v48_objects = 5 AS has_partition_lease, :v48_objects = 0 AS no_partition_lease,
       :v50_objects = 2 AS has_reservations, :v50_objects = 0 AS no_reservations \gset

\echo '== C01 ASSET_CONSERVATION [CRITICAL] 자산별 원장 총합 = 0 =='
SELECT asset_id, SUM(amount) AS net
FROM ledger_entries GROUP BY asset_id HAVING SUM(amount) <> 0;

\echo '== C02 BAL_VS_LEDGER [CRITICAL] balances 프로젝션 = 원장 재계산 =='
-- balance_ledger_check 뷰(V1)가 FULL OUTER JOIN으로 고아 계정/고아 잔고까지 드러낸다.
SELECT account_id, orphan_balance, missing_balance, balance, ledger_sum
FROM balance_ledger_check WHERE mismatch;

\echo '== C03 TXN_UNSEALED [HIGH] 1분 초과 미봉인 거래 =='
SELECT id, business_event_type, business_event_id, created_at
FROM ledger_transactions
WHERE status = 'OPEN' AND created_at < now() - interval '1 min';

\echo '== C04 TXN_SEALED_UNBALANCED [CRITICAL] 봉인인데 불균형 =='
SELECT l.transaction_id, l.asset_id, SUM(l.amount) AS net
FROM ledger_entries l
JOIN ledger_transactions t ON t.id = l.transaction_id AND t.status = 'SEALED'
GROUP BY l.transaction_id, l.asset_id HAVING SUM(l.amount) <> 0;

\echo '== C05 CMD_JOURNAL_GAPLESS [CRITICAL] 명령 저널 무결(레거시 구간 전역 + 마켓 안 대역) =='
-- 행이 나오면 위반이다. 명세 부재 1행, 레거시 항 결번 1행, 대역 항은 결번 난 마켓마다 1행.
-- gaps 열 합이 InvariantCheck.C05_CMD_GAPLESS 값이다. 대역 사이 빈 구간은 결번이 아니다(ADR 0022 6-1).
WITH s AS (SELECT band_span FROM journal_band_spec WHERE name = 'command_seq')
SELECT 'band_spec_missing' AS term, NULL::smallint AS market_id, 1::bigint AS gaps
WHERE NOT EXISTS (SELECT 1 FROM s)
UNION ALL
SELECT 'legacy', NULL, COALESCE((max(c.seq) - min(c.seq) + 1) - count(*), 0)
FROM command_journal c
WHERE c.seq < (SELECT band_span FROM s)
HAVING COALESCE((max(c.seq) - min(c.seq) + 1) - count(*), 0) <> 0
UNION ALL
SELECT 'band', c.market_id, (max(c.seq) - min(c.seq) + 1) - count(*)
FROM command_journal c
WHERE c.seq >= (SELECT band_span FROM s)
GROUP BY c.market_id
HAVING (max(c.seq) - min(c.seq) + 1) - count(*) <> 0;

\echo '== C06 EVENT_JOURNAL_GAPLESS [CRITICAL] 이벤트 저널 무결 =='
-- V36 에서 축이 마켓 안으로 바뀌었다. event_seq 는 marketId * band_span + marketSeq 파생이라
-- 전역으로 보면 대역 사이가 비어 있고 그것은 결번이 아니다. 결번 없음이 요구되는 축은
-- 마켓 안이다. market_seq 가 비어 있는 V36 이전 행은 이 검사 밖이다.
SELECT COALESCE(sum(gap), 0) AS gaps FROM (
    SELECT (max(market_seq) - min(market_seq) + 1) - count(*) AS gap
    FROM event_journal WHERE market_seq IS NOT NULL GROUP BY market_id
) g
HAVING COALESCE(sum(gap), 0) <> 0;

\echo '== C07 DUP_CLIENT_ORDER [CRITICAL] 중복 주문 접수 0 (프로젝션 축) =='
SELECT user_id, client_order_id, count(*) AS n FROM orders
WHERE client_order_id IS NOT NULL GROUP BY user_id, client_order_id HAVING count(*) > 1;

\echo '== C07P DUP_CLIENT_ORDER_JOURNAL [CRITICAL] 중복 주문 접수 0 (저널 축, NEW 한정) =='
-- CANCEL 커맨드가 동일 coid를 동반할 수 있어 command_type='NEW' 필터 필수.
SELECT user_id, client_order_id, count(*) AS n FROM command_journal
WHERE command_type = 'NEW' AND client_order_id IS NOT NULL
GROUP BY user_id, client_order_id HAVING count(*) > 1;

\echo '== C08 TRADE_SEQ_DUP [CRITICAL] 중복 체결 0 (trades.id 중복 + 레지스트리 1:1) =='
-- trades의 체결 seq는 컬럼 id다(trade_id = event_seq). §4-1의 trades.seq 표기는 실제 컬럼 id.
SELECT id, count(*) AS n FROM trades GROUP BY id HAVING count(*) > 1;
SELECT t.id FROM trades t
LEFT JOIN trade_seq_registry r ON r.seq = t.id
WHERE r.seq IS NULL;

\echo '== C09 TRADE_SEQ_EVENT_MATCH [HIGH] trades.id ⊆ event_journal(TRADE) =='
SELECT t.id FROM trades t
LEFT JOIN event_journal e ON e.event_seq = t.id AND e.event_type = 'TRADE'
WHERE e.event_seq IS NULL;

\echo '== C10 ORDER_FILL_AGG [HIGH] 체결 합 = filled_qty (90일 지평) =='
SELECT o.id FROM orders o
LEFT JOIN trades t ON t.maker_order_id = o.id OR t.taker_order_id = o.id
WHERE o.quantity IS NOT NULL AND o.created_at >= now() - interval '90 days'
GROUP BY o.id, o.filled_qty
HAVING o.filled_qty <> COALESCE(SUM(t.quantity), 0);

\echo '== C11 TRADE_ORPHAN [HIGH] 고아 체결 (maker/taker 주문 부재) =='
SELECT t.id FROM trades t
WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.id = t.maker_order_id)
   OR NOT EXISTS (SELECT 1 FROM orders o WHERE o.id = t.taker_order_id);

\echo '== C12 EVENT_ORPHAN [HIGH] 고아 이벤트 (source_cmd_seq 부재) =='
SELECT e.event_seq FROM event_journal e
WHERE NOT EXISTS (SELECT 1 FROM command_journal c WHERE c.seq = e.source_cmd_seq);

\echo '== C13a EVENT_ORDER_MATERIALIZED [CRITICAL, 정지 시점] NEW 명령 → orders 물질화 =='
-- REJECTED도 orders에 남는다(거절 경로). 부재 = 유실. append와 정산 커밋 사이 비동기
-- 창이 라이브에서 오탐하므로 큐 드레인된 권위 run/정지 상태에서만 유효.
SELECT c.seq FROM command_journal c
WHERE c.command_type = 'NEW'
  AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.id = c.assigned_order_id);

\echo '== C13b ORDER_JOURNALED [CRITICAL] orders → 저널 존재 (저널 우회 쓰기 탐지) =='
SELECT o.id FROM orders o
WHERE o.command_seq IS NULL
   OR NOT EXISTS (SELECT 1 FROM command_journal c WHERE c.seq = o.command_seq);

\if :has_reservation_table
\echo '== C14 LOCKED_RESERVATION [HIGH] LOCKED 잔고 = 열린 주문 잔여 예약 + 정산 전 접수 예약 + 대기 출금 (C14 개정) =='
-- 정산 전 예약 항은 마켓별 정산 커서 위만 본다. 커서 아래 정산 틈과 한 마켓 대기 10만 건 초과 몫은 초과 LOCKED 로
-- 드러난다(보수 쪽). 술어 정본은 하네스 검증설계 §4-1 C14 이고 InvariantCheck.C14_LOCKED_RESERVATION 은 위반 계정 수다.
SELECT a.id AS account_id, b.balance,
       COALESCE(o.amt, 0) AS order_locked, COALESCE(u.amt, 0) AS unsettled_reserved, COALESCE(w.amt, 0) AS pending_withdraw
FROM accounts a
JOIN balances b ON b.account_id = a.id
LEFT JOIN (SELECT locked_account_id AS account_id, SUM(locked_amount) AS amt
           FROM orders WHERE status IN ('PENDING','OPEN','PARTIAL')
           GROUP BY locked_account_id) o ON o.account_id = a.id
LEFT JOIN (SELECT r.locked_account_id AS account_id, SUM(r.amount) AS amt
           FROM markets m
           CROSS JOIN LATERAL (SELECT max(e.source_cmd_seq) AS settled
                                 FROM event_journal e WHERE e.market_id = m.id) cur
           CROSS JOIN LATERAL (SELECT c.seq FROM command_journal c
                                WHERE c.market_id = m.id AND c.command_type = 'NEW'
                                  AND c.seq > COALESCE(cur.settled, -1)
                                ORDER BY c.seq LIMIT 100000) c
           CROSS JOIN LATERAL (SELECT r.locked_account_id, r.amount FROM order_reservations r
                                WHERE r.command_seq = c.seq
                                  AND NOT EXISTS (SELECT 1 FROM event_emission_registry e
                                                   WHERE e.source_cmd_seq = r.command_seq)
                                LIMIT 1) r
           GROUP BY r.locked_account_id) u ON u.account_id = a.id
LEFT JOIN (SELECT la.id AS account_id, SUM(t.amount) AS amt
           FROM transfer_requests t
           JOIN accounts la ON la.owner_type = 'USER' AND la.owner_id = t.user_id AND la.purpose = 'LOCKED'
                           AND la.asset_id = t.asset_id AND la.shard = 0
           WHERE t.direction = 'WITHDRAW' AND t.status = 'REQUESTED'
           GROUP BY la.id) w ON w.account_id = a.id
WHERE a.purpose = 'LOCKED'
  AND b.balance <> COALESCE(o.amt, 0) + COALESCE(u.amt, 0) + COALESCE(w.amt, 0);
\else
\echo '== C14 LOCKED_RESERVATION [HIGH] LOCKED 잔고 = 열린 주문 잔여 예약 + 대기 출금 (C14 개정, 예약 표 없는 스키마) =='
-- V49 이하 스키마에는 접수 예약이 있을 수 없어 예약 항 없이 같은 판정이다. N/A 가 아니다.
SELECT a.id AS account_id, b.balance,
       COALESCE(o.amt, 0) AS order_locked, COALESCE(w.amt, 0) AS pending_withdraw
FROM accounts a
JOIN balances b ON b.account_id = a.id
LEFT JOIN (SELECT locked_account_id AS account_id, SUM(locked_amount) AS amt
           FROM orders WHERE status IN ('PENDING','OPEN','PARTIAL')
           GROUP BY locked_account_id) o ON o.account_id = a.id
LEFT JOIN (SELECT la.id AS account_id, SUM(t.amount) AS amt
           FROM transfer_requests t
           JOIN accounts la ON la.owner_type = 'USER' AND la.owner_id = t.user_id AND la.purpose = 'LOCKED'
                           AND la.asset_id = t.asset_id AND la.shard = 0
           WHERE t.direction = 'WITHDRAW' AND t.status = 'REQUESTED'
           GROUP BY la.id) w ON w.account_id = a.id
WHERE a.purpose = 'LOCKED'
  AND b.balance <> COALESCE(o.amt, 0) + COALESCE(w.amt, 0);
\endif

\echo '== C15 DEFAULT_PARTITION [WARN] DEFAULT 파티션 유입 0 =='
-- 파생 파티션(_pYYYY_MM / _p<start>)이 정상 흡수하면 DEFAULT는 항상 비어야 한다.
-- 유입 행은 파티션 경계 소진 전조(가용성). 무결성 위반은 아니다. command_journal_default 는 C27.
SELECT 'ledger_entries_default' AS partition, count(*) AS rows FROM ledger_entries_default HAVING count(*) > 0
UNION ALL
SELECT 'event_journal_default', count(*) FROM event_journal_default HAVING count(*) > 0
UNION ALL
SELECT 'trades_default', count(*) FROM trades_default HAVING count(*) > 0
UNION ALL
SELECT 'outbox_default', count(*) FROM outbox_default HAVING count(*) > 0;

\echo '== C16 BOT_ACK_SUBSET [CRITICAL, 실험 한정] ACK 원장 ⊆ command_journal (RPO=0 외부 증거) =='
-- 원천 harness.loadtest_acks. 실험 시작 훅의 TRUNCATE 선행이 전제(스테일 오탐 차단).
SELECT a.user_id, a.client_order_id, a.source FROM harness.loadtest_acks a
LEFT JOIN command_journal c ON c.user_id = a.user_id AND c.client_order_id = a.client_order_id
WHERE c.seq IS NULL;

\echo '== C17 REPLAY_DETERMINISM [CRITICAL] 최근 복구 재생 결정론 (matched=true) =='
SELECT id, market_id, replayed_from_seq, replayed_to_seq, fp_replay, fp_live
FROM recovery_runs WHERE matched = false;

\echo '== C18 FILL_PRICE_MAKER [CRITICAL] 체결가 = maker 지정가 =='
-- maker = 오더북에 앉은 LIMIT(price NOT NULL). price improvement는 taker 차익이므로
-- 체결가는 항상 maker 지정가와 정확히 일치.
SELECT t.id, t.price AS trade_price, m.price AS maker_price
FROM trades t
JOIN orders m ON m.id = t.maker_order_id
WHERE m.price IS NOT NULL AND t.price <> m.price;

\echo '== C19 ORDERBOOK_NONCROSS [HIGH, 정지 시점] bestBid < bestAsk =='
-- 라이브 프로젝션 지연이 순간 교차처럼 보일 수 있어 정지 상태에서만 평가.
SELECT market_id,
       max(price) FILTER (WHERE side = 'BUY')  AS best_bid,
       min(price) FILTER (WHERE side = 'SELL') AS best_ask
FROM orders
WHERE status IN ('OPEN','PARTIAL') AND price IS NOT NULL
GROUP BY market_id
HAVING max(price) FILTER (WHERE side = 'BUY')
       >= min(price) FILTER (WHERE side = 'SELL');

\echo '== C20 ORDER_FEE_MATERIALIZED [HIGH] orders 수수료 물질화 = trades 자기측 fee leg 합 =='
-- paid_fee·trades_count는 trade INSERT와 동일 정산 트랜잭션의 누적 프로젝션(V10). 자기측 fee는
-- maker면 maker_fee, taker면 taker_fee. 90일 윈도는 §4-2 ③ 규약(주문 생성이 체결보다 앞서므로
-- 90일 내 주문의 leg는 전부 attached 파티션에 있다).
SELECT o.id
FROM orders o
LEFT JOIN (
    SELECT maker_order_id AS oid, maker_fee AS fee FROM trades
    WHERE created_at >= now() - interval '90 days'
    UNION ALL
    SELECT taker_order_id AS oid, taker_fee AS fee FROM trades
    WHERE created_at >= now() - interval '90 days'
) legs ON legs.oid = o.id
WHERE o.created_at >= now() - interval '90 days'
GROUP BY o.id, o.paid_fee, o.trades_count
HAVING o.paid_fee <> COALESCE(SUM(legs.fee), 0)
    OR o.trades_count <> COUNT(legs.oid);

\echo '== C21 EVENT_EMISSION_REGISTRY [CRITICAL] event_journal 대 방출 레지스트리 1:1 =='
-- 양방향 anti-join. 트리거가 방출 시점 정방향만 강제하므로 트리거 실패·저널 우회 삽입이 만든
-- 유실·유령 이벤트는 C06 gapless로 못 잡는다. 윈도를 event_journal.created_at 한쪽에만 걸어
-- registry.created_at(DEFAULT now())과 어긋나도 경계 오탐이 생기지 않게 한다.
SELECT e.event_seq FROM event_journal e
LEFT JOIN event_emission_registry r ON r.event_seq = e.event_seq
WHERE e.created_at >= now() - interval '90 days' AND r.event_seq IS NULL;
SELECT r.event_seq FROM event_emission_registry r
LEFT JOIN event_journal e ON e.event_seq = r.event_seq
WHERE r.created_at >= now() - interval '90 days' AND e.event_seq IS NULL;

\echo '== C22 FENCING_MONOTONIC [CRITICAL, 권위 run 한정] fencing_token 비감소(시간 축 팔 + 마켓 안 팔) =='
-- 행 수가 InvariantCheck.C22_FENCING_MONOTONIC 값이다. 시간 축 팔은 마켓 사이 epoch 역행까지 본다.
-- 마켓 안 팔은 시계를 읽지 않아 시계 역행이 가린 마켓 안 역행을 잡는다(ADR 0022 6-2).
-- 시간 축 팔은 DB 서버 시계 역행(PostgreSQL 프라이머리 전환 포함) 뒤 split-brain 없이도 행을 낼 수 있다(9-17).
SELECT 'time' AS arm, t.seq, t.market_id, t.created_at, t.prev, t.fencing_token FROM (
    SELECT seq, market_id, created_at, fencing_token,
           lag(fencing_token) OVER (ORDER BY created_at, seq) AS prev
    FROM command_journal
) t
WHERE t.prev > t.fencing_token
UNION ALL
SELECT 'market', t.seq, t.market_id, t.created_at, t.prev, t.fencing_token FROM (
    SELECT seq, market_id, created_at, fencing_token,
           lag(fencing_token) OVER (PARTITION BY market_id ORDER BY seq) AS prev
    FROM command_journal
) t
WHERE t.prev > t.fencing_token;

\echo '== C23 COUNTER_SEQ_CONSISTENCY [CRITICAL, 권위 run 한정] 채번 카운터 > 그 축의 저널 최대값 =='
-- next_value가 저널 최대값 이하이면 다음 채번이 기존 값과 충돌한다(재사용). 네 문장의 행 수 합이
-- InvariantCheck.C23_COUNTER_SEQ_CONSISTENCY 값이다. 명세 부재, 명령 레거시 팔, 명령 대역 팔, 이벤트 팔 순(ADR 0022 6-3).
SELECT 'band_spec_missing' AS arm
WHERE NOT EXISTS (SELECT 1 FROM journal_band_spec WHERE name = 'command_seq');

WITH s AS (SELECT band_span FROM journal_band_spec WHERE name = 'command_seq')
SELECT 'legacy' AS arm, c.name, c.next_value, x.journal_max
FROM journal_counters c
CROSS JOIN LATERAL (SELECT COALESCE(max(j.seq), 0) AS journal_max
                    FROM command_journal j
                    WHERE j.seq < (SELECT band_span FROM s)) x
WHERE c.name = 'command_seq' AND c.next_value <= x.journal_max;

WITH s AS (SELECT band_span FROM journal_band_spec WHERE name = 'command_seq')
SELECT 'band' AS arm, m.market_id, m.name, m.next_value, x.local_max
FROM market_journal_counters m
CROSS JOIN LATERAL (SELECT COALESCE(max(j.seq - j.market_id * (SELECT band_span FROM s)), 0) AS local_max
                    FROM command_journal j
                    WHERE j.market_id = m.market_id AND j.seq >= (SELECT band_span FROM s)) x
WHERE m.name = 'command_seq' AND m.next_value <= x.local_max;

SELECT 'event' AS arm, m.market_id, m.name, m.next_value FROM market_journal_counters m
WHERE m.name = 'event_seq'
  AND m.next_value <= (SELECT COALESCE(max(e.market_seq), 0) FROM event_journal e
                       WHERE e.market_id = m.market_id AND e.market_seq IS NOT NULL);

\echo '== C26 CMD_BAND_HEADROOM [HIGH] 명령 대역 잔여 10퍼센트 미만 마켓 0 =='
-- 행 수가 InvariantCheck.C26_CMD_BAND_HEADROOM 값이다. 소진은 되돌릴 수 없어 미리 센다(ADR 0022 6-4, V45 뷰).
-- 10^12 대역에서는 사실상 공허하다. 명세 부재는 C05·C23 이 센다. 아래 V13 조건 블록 밖에 둔다.
SELECT market_id, next_value, remaining, remaining_ratio
FROM v_journal_band_headroom
WHERE name = 'command_seq' AND remaining_ratio < 0.1
ORDER BY market_id;

\echo '== C27 CMD_DEFAULT_PARTITION [CRITICAL] command_journal DEFAULT 유입 0 =='
-- 행이 있으면 어느 잎에도 속하지 않는 seq 로 들어간 명령이고 그 구간 잎은 다시 만들 수 없다(V40, ADR 0022 6-4).
-- 아래 V13 조건 블록 안에 두면 V13 미적용 DB 에서 이 판정이 빠진다.
SELECT 'command_journal_default' AS partition, count(*) AS rows
FROM command_journal_default HAVING count(*) > 0;

\echo '== C28 CMD_LEGACY_AFTER_CUTOVER [CRITICAL] 마켓 안 첫 대역 행보다 늦은 레거시 행 0 =='
-- 행 수가 InvariantCheck.C28_CMD_LEGACY_AFTER_CUTOVER 값이다. 그 레거시 행은 그 마켓 커서 아래로 버려진다.
-- latched_at 이 아니라 마켓 첫 대역 행과 비교한다(ADR 0022 6단계). 시계 역행 뒤 거짓 양성 한계는 9-17.
WITH s AS (SELECT band_span FROM journal_band_spec WHERE name = 'command_seq'),
     b AS (SELECT c.market_id, min(c.created_at) AS first_band_at
           FROM command_journal c, s
           WHERE c.seq >= s.band_span
           GROUP BY c.market_id)
SELECT c.market_id, c.seq, c.created_at, b.first_band_at
FROM command_journal c
JOIN b ON b.market_id = c.market_id
CROSS JOIN s
WHERE c.seq < s.band_span
  AND c.created_at > b.first_band_at
ORDER BY c.market_id, c.seq;

-- C29·C31 은 파일 머리의 스키마 판정(v48_objects·v50_objects)으로 평가·N/A·FAIL 을 가른다.

\if :has_partition_lease
\echo '== C29 MARKET_PARTITION_MAP [CRITICAL] 마켓 파티션 대응·리스 행·모드 래치 누락 0 =='
-- violations 열 합이 InvariantCheck.C29_MARKET_PARTITION_MAP 값이다. 대응 없는 마켓, 없는 리스 행, 한 행이 아닌
-- leader_protocol 을 한 행씩 낸다. 대응 행은 추가 전용이라 조치는 빠진 행을 소유자가 넣는 것이다(ADR 0034 S2, V48).
SELECT 'unmapped_market' AS term, m.id::text AS subject, 1 AS violations
FROM markets m
WHERE NOT EXISTS (SELECT 1 FROM market_lease_partition p WHERE p.market_id = m.id)
UNION ALL
SELECT 'missing_lease', r.resource, 1
FROM (VALUES ('matching-engine'), ('matching-p0'), ('matching-p1')) AS r(resource)
WHERE NOT EXISTS (SELECT 1 FROM leader_lease l WHERE l.resource = r.resource)
UNION ALL
SELECT 'protocol_rows', count(*)::text, 1
FROM leader_protocol
HAVING count(*) <> 1
ORDER BY 1, 2;
\elif :no_partition_lease
\echo '== C29 MARKET_PARTITION_MAP [CRITICAL] N/A: V48 객체 없음(V47 이하 스키마) =='
\echo '   대상 객체(market_lease_partition·leader_protocol·leader_protocol_history·lease_acquisitions·lease_resource_for_market)가 모두 없어 판정 대상이 없다. 통과로 세지 않는다.'
\else
\echo '== C29 MARKET_PARTITION_MAP [CRITICAL] FAIL: V48 객체 일부 누락(반쯤 적용됐거나 손상된 스키마) =='
SELECT 'missing_v48_objects' AS term, (5 - :v48_objects)::text AS subject, 5 - :v48_objects AS violations;
\endif

\if :has_reservations
\echo '== C31 RESERVATION_LINK [CRITICAL] 접수 예약 연결 무결(전수 판) =='
-- 세 항의 합이다. (a) 예약 행과 원장 RESERVE 거래의 봉인·계정·금액 불일치(90일 창), (b) P2 이상 epoch 로 기록된 예약 없는
-- 주문 생성 명령, (c) 저널에 없거나 주문·사용자·마켓·payload 해시가 다른 예약 행. 앱 InvariantCheck 는 비용 때문에 권위 run
-- 한정·최근 창 판이고 이것이 전수 판이다(하네스 검증설계 §4-1 C31, ADR 0034 S3).
SELECT 'reserve_ledger_mismatch' AS term, r.command_seq AS subject
FROM order_reservations r
WHERE r.created_at >= now() - interval '90 days'
  AND (NOT EXISTS (SELECT 1 FROM ledger_transactions t
                    WHERE t.id = r.ledger_txn_id AND t.business_event_type = 'RESERVE'
                      AND t.business_event_id = r.order_id AND t.status = 'SEALED')
       OR NOT EXISTS (SELECT 1 FROM ledger_entries e
                       WHERE e.transaction_id = r.ledger_txn_id AND e.created_at = r.created_at
                         AND e.account_id = r.locked_account_id AND e.asset_id = r.asset_id AND e.amount = r.amount)
       OR NOT EXISTS (SELECT 1 FROM ledger_entries e
                       WHERE e.transaction_id = r.ledger_txn_id AND e.created_at = r.created_at
                         AND e.account_id = r.available_account_id AND e.asset_id = r.asset_id AND e.amount = -r.amount))
UNION ALL
SELECT 'unreserved_new', c.seq
FROM command_journal c
JOIN lease_acquisitions a ON a.epoch = c.fencing_token AND a.protocol >= 2
WHERE c.command_type = 'NEW'
  AND NOT EXISTS (SELECT 1 FROM order_reservations r WHERE r.command_seq = c.seq)
UNION ALL
SELECT 'reservation_journal_mismatch', r.command_seq
FROM order_reservations r
WHERE NOT EXISTS (SELECT 1 FROM command_journal c
                   WHERE c.seq = r.command_seq AND c.command_type = 'NEW'
                     AND c.assigned_order_id = r.order_id AND c.user_id = r.user_id
                     AND c.market_id = r.market_id
                     AND sha256(convert_to(c.payload::text, 'UTF8')) = r.payload_sha256);
\elif :no_reservations
\echo '== C31 RESERVATION_LINK [CRITICAL] N/A: V50 객체 없음(V49 이하 스키마) =='
\echo '   대상 객체(order_reservations·order_reserve_amount)가 모두 없어 판정 대상이 없다. 통과로 세지 않는다.'
\else
\echo '== C31 RESERVATION_LINK [CRITICAL] FAIL: V50 객체 일부 누락(반쯤 적용됐거나 손상된 스키마) =='
SELECT 'missing_v50_objects' AS term, (2 - :v50_objects)::text AS subject, 2 - :v50_objects AS violations;
\endif

-- V13 미적용 DB에서는 아래 두 뷰가 없다. 무조건 조회하면 ON_ERROR_STOP 경로가 C24 이전
-- 29개 판정까지 통째로 버린다. 부재를 통과로 접지도 않는다. 미평가를 무결로 읽으면 그것이
-- 거짓 통과다. 부재 시 SKIP을 크게 찍고 종료 상태를 남긴다.
SELECT (to_regclass('public.position_cost_qty_check') IS NOT NULL
        AND to_regclass('public.position_cost_sanity_check') IS NOT NULL) AS has_cost_views \gset

\if :has_cost_views
\echo '== C24 POSITION_COST_QTY [WARN, 권위 run 한정] 원가 수량 보존 =='
-- 항등(cost_qty + unknown_qty = available + locked) 위반 행. 술어 정본은 V13 뷰다.
-- WARN 등급인 이유는 계층 ③ 파생이라 자금·원장이 무손상이고 조치가 rebuild_position_cost()
-- 재구축이기 때문이다. CRITICAL로 올리면 §4-3 자동 조치인 거래 정지까지 유발한다.
-- 주입 창에서는 재구축 전 미반영이 위반으로 보여 권위 run에서만 유효하다.
SELECT * FROM position_cost_qty_check;

\echo '== C25 POSITION_COST_SANITY [WARN, 권위 run 한정] 원가 누산기 정합 =='
-- 뷰가 분류하는 위반 5종: negative_accumulator·cost_pair_broken·watermark_ahead·
-- quote_asset_position·cost_currency_invalid. 테이블 CHECK로 이미 막히는 항목까지 두는 것은
-- SECURITY DEFINER 우회·수퍼유저 직접 DML의 회귀 안전망이다.
SELECT * FROM position_cost_sanity_check;
\else
\echo '== C24 POSITION_COST_QTY [WARN] SKIP: V13 미적용(position_cost 검증 뷰 부재) =='
\echo '== C25 POSITION_COST_SANITY [WARN] SKIP: V13 미적용(position_cost 검증 뷰 부재) =='
\echo '   원가 프로젝션 미평가. 이 DB에 V13을 적용한 뒤 재실행해야 C24·C25 판정이 성립한다.'
\endif
