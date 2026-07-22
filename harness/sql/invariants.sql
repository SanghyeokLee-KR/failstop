-- ============================================================================
-- invariants.sql
-- 불변식 검증 카탈로그 C01~C19의 psql 직조회본. 검증설계 §4-1 정본을 실제 DDL
-- 컬럼명(server V1__init.sql, trades.id = event_seq)에 맞춰 옮긴다.
--
-- 용도: ① 발표 데모 §7의 "DB 원본에 SQL 즉석 실행 → 0행"(검증기 UI보다 강한 증거)
--       ② 앱 InvariantVerifier(enum 부분집합)가 놓치는 pg_cron 분담 체크(C03·C04·
--          C09~C12·C15)의 수동 감사 경로.
-- 판정: 각 쿼리는 위반 행만 반환한다. 전 쿼리 0행 = 무결. summary.sql은 같은 술어를
--       건별 카운트로 집계한 기계 판정용 1화면 요약이다.
--
-- 실행: psql -U exchange -d exchange -f invariants.sql
-- 주의: C16(실험 한정)·C19/C13a(정지 시점 한정)는 라이브 프로젝션 지연·스테일
--       loadtest_acks로 오탐할 수 있어 상시 스케줄이 아닌 권위 run/정지 상태에서만
--       유효하다(§4-2 ④, InvariantCheck.Scope.AUTHORITATIVE_ONLY와 동일 규율).
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

\echo '== C03 TXN_UNSEALED [WARN] 1분 초과 미봉인 거래 =='
SELECT id, business_event_type, business_event_id, created_at
FROM ledger_transactions
WHERE status = 'OPEN' AND created_at < now() - interval '1 min';

\echo '== C04 TXN_SEALED_UNBALANCED [CRITICAL] 봉인인데 불균형 =='
SELECT l.transaction_id, l.asset_id, SUM(l.amount) AS net
FROM ledger_entries l
JOIN ledger_transactions t ON t.id = l.transaction_id AND t.status = 'SEALED'
GROUP BY l.transaction_id, l.asset_id HAVING SUM(l.amount) <> 0;

\echo '== C05 CMD_JOURNAL_GAPLESS [CRITICAL] 명령 저널 무결(유실 0 / RPO=0) =='
-- 0이 아니면 gap. COALESCE로 빈 테이블(max/min NULL)을 0으로 접는다.
SELECT COALESCE((max(seq) - min(seq) + 1) - count(*), 0) AS gaps FROM command_journal
HAVING COALESCE((max(seq) - min(seq) + 1) - count(*), 0) <> 0;

\echo '== C06 EVENT_JOURNAL_GAPLESS [CRITICAL] 이벤트 저널 무결 =='
SELECT COALESCE((max(event_seq) - min(event_seq) + 1) - count(*), 0) AS gaps FROM event_journal
HAVING COALESCE((max(event_seq) - min(event_seq) + 1) - count(*), 0) <> 0;

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
-- 유입 행은 파티션 경계 소진 전조(가용성). 무결성 위반은 아니다.
SELECT 'ledger_entries_default' AS partition, count(*) AS rows FROM ledger_entries_default HAVING count(*) > 0
UNION ALL
SELECT 'command_journal_default', count(*) FROM command_journal_default HAVING count(*) > 0
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
