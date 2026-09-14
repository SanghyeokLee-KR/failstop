-- ============================================================================
-- invariants.sql
-- 불변식 카탈로그 C01~C25, C27, C28의 psql 직조회본. 검증설계 §4-1 정본을 실제 DDL 컬럼명에 맞춘다
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
-- V36 에서 축이 마켓 안으로 바뀌었다. event_seq 는 marketId * 10^7 + marketSeq 파생이라
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

\echo '== C14 LOCKED_RESERVATION [HIGH] LOCKED 잔고 = 열린 주문 잔여 예약 합 =='
SELECT b.account_id, b.balance, COALESCE(SUM(o.locked_amount), 0) AS order_locked
FROM accounts a
JOIN balances b ON b.account_id = a.id
LEFT JOIN orders o ON o.locked_account_id = a.id AND o.status IN ('PENDING','OPEN','PARTIAL')
WHERE a.purpose = 'LOCKED'
GROUP BY b.account_id, b.balance
HAVING b.balance <> COALESCE(SUM(o.locked_amount), 0);

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

-- V13 미적용 DB에서는 아래 두 뷰가 없다. 무조건 조회하면 ON_ERROR_STOP 경로가 C24 이전
-- 27개 판정까지 통째로 버린다. 부재를 통과로 접지도 않는다. 미평가를 무결로 읽으면 그것이
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
