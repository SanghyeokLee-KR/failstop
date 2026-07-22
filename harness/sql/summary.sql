-- ============================================================================
-- summary.sql
-- invariants.sql 카탈로그를 건별 위반 카운트로 집계한 1화면 기계 판정본.
-- 각 행 violated_rows = 0 이어야 정상. verdict 열은 심각도별 라벨.
--
-- 용도: CI/실험 러너가 `psql -At -f summary.sql`로 파싱해 exit code로 환원하거나,
--       발표 데모 대시보드가 pg-proof 데이터소스로 직조회한다.
-- 판정 규약: CRITICAL 위반 1건이라도 존재 → 실험 FAIL(§4-3 자동 조치 상한 진입).
--
-- 실행: psql -U exchange -d exchange -f summary.sql
--       게이트: psql -At -c "SELECT count(*) FROM (<이 UNION>) v WHERE severity='CRITICAL' AND violated_rows>0"
-- 범위: C16(실험 한정)·C13a·C19(정지 시점 한정)는 권위 run/정지 상태에서만 유효.
--       상시 스케줄 판정에서는 scope='CONDITIONAL' 행을 제외한다.
-- ============================================================================
\pset pager off

WITH v(code, check_name, severity, scope, violated_rows) AS (
    SELECT 'C01','ASSET_CONSERVATION','CRITICAL','ALWAYS',
           (SELECT count(*) FROM (SELECT asset_id FROM ledger_entries
                GROUP BY asset_id HAVING SUM(amount) <> 0) x)
    UNION ALL SELECT 'C02','BAL_VS_LEDGER','CRITICAL','ALWAYS',
           (SELECT count(*) FROM balance_ledger_check WHERE mismatch)
    UNION ALL SELECT 'C03','TXN_UNSEALED','WARN','ALWAYS',
           (SELECT count(*) FROM ledger_transactions
                WHERE status='OPEN' AND created_at < now() - interval '1 min')
    UNION ALL SELECT 'C04','TXN_SEALED_UNBALANCED','CRITICAL','ALWAYS',
           (SELECT count(*) FROM (
                SELECT l.transaction_id, l.asset_id FROM ledger_entries l
                JOIN ledger_transactions t ON t.id = l.transaction_id AND t.status='SEALED'
                GROUP BY l.transaction_id, l.asset_id HAVING SUM(l.amount) <> 0) x)
    UNION ALL SELECT 'C05','CMD_JOURNAL_GAPLESS','CRITICAL','ALWAYS',
           (SELECT COALESCE((max(seq)-min(seq)+1)-count(*),0) FROM command_journal)
    UNION ALL SELECT 'C06','EVENT_JOURNAL_GAPLESS','CRITICAL','ALWAYS',
           (SELECT COALESCE((max(event_seq)-min(event_seq)+1)-count(*),0) FROM event_journal)
    UNION ALL SELECT 'C07','DUP_CLIENT_ORDER','CRITICAL','ALWAYS',
           (SELECT count(*) FROM (SELECT user_id, client_order_id FROM orders
                WHERE client_order_id IS NOT NULL
                GROUP BY user_id, client_order_id HAVING count(*) > 1) x)
    UNION ALL SELECT 'C07P','DUP_CLIENT_ORDER_JOURNAL','CRITICAL','ALWAYS',
           (SELECT count(*) FROM (SELECT user_id, client_order_id FROM command_journal
                WHERE command_type='NEW' AND client_order_id IS NOT NULL
                GROUP BY user_id, client_order_id HAVING count(*) > 1) x)
    UNION ALL SELECT 'C08','TRADE_SEQ_DUP','CRITICAL','ALWAYS',
           (SELECT count(*) FROM (SELECT id FROM trades GROUP BY id HAVING count(*) > 1) d)
           + (SELECT count(*) FROM trades t
                LEFT JOIN trade_seq_registry r ON r.seq = t.id WHERE r.seq IS NULL)
    UNION ALL SELECT 'C09','TRADE_SEQ_EVENT_MATCH','HIGH','ALWAYS',
           (SELECT count(*) FROM trades t
                LEFT JOIN event_journal e ON e.event_seq = t.id AND e.event_type='TRADE'
                WHERE e.event_seq IS NULL)
    UNION ALL SELECT 'C10','ORDER_FILL_AGG','HIGH','ALWAYS',
           (SELECT count(*) FROM (
                SELECT o.id FROM orders o
                LEFT JOIN trades t ON t.maker_order_id = o.id OR t.taker_order_id = o.id
                WHERE o.quantity IS NOT NULL AND o.created_at >= now() - interval '90 days'
                GROUP BY o.id, o.filled_qty
                HAVING o.filled_qty <> COALESCE(SUM(t.quantity),0)) x)
    UNION ALL SELECT 'C11','TRADE_ORPHAN','HIGH','ALWAYS',
           (SELECT count(*) FROM trades t
                WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.id = t.maker_order_id)
                   OR NOT EXISTS (SELECT 1 FROM orders o WHERE o.id = t.taker_order_id))
    UNION ALL SELECT 'C12','EVENT_ORPHAN','HIGH','ALWAYS',
           (SELECT count(*) FROM event_journal e
                WHERE NOT EXISTS (SELECT 1 FROM command_journal c WHERE c.seq = e.source_cmd_seq))
    UNION ALL SELECT 'C13A','EVENT_ORDER_MATERIALIZED','CRITICAL','CONDITIONAL',
           (SELECT count(*) FROM command_journal c
                WHERE c.command_type='NEW'
                  AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.id = c.assigned_order_id))
    UNION ALL SELECT 'C13B','ORDER_JOURNALED','CRITICAL','ALWAYS',
           (SELECT count(*) FROM orders o
                WHERE o.command_seq IS NULL
                   OR NOT EXISTS (SELECT 1 FROM command_journal c WHERE c.seq = o.command_seq))
    UNION ALL SELECT 'C14','LOCKED_RESERVATION','HIGH','ALWAYS',
           (SELECT count(*) FROM (
                SELECT b.account_id FROM accounts a
                JOIN balances b ON b.account_id = a.id
                LEFT JOIN orders o ON o.locked_account_id = a.id
                     AND o.status IN ('PENDING','OPEN','PARTIAL')
                WHERE a.purpose='LOCKED'
                GROUP BY b.account_id, b.balance
                HAVING b.balance <> COALESCE(SUM(o.locked_amount),0)) x)
    UNION ALL SELECT 'C16','BOT_ACK_SUBSET','CRITICAL','CONDITIONAL',
           (SELECT count(*) FROM harness.loadtest_acks a
                LEFT JOIN command_journal c
                     ON c.user_id = a.user_id AND c.client_order_id = a.client_order_id
                WHERE c.seq IS NULL)
    UNION ALL SELECT 'C17','REPLAY_DETERMINISM','CRITICAL','ALWAYS',
           (SELECT count(*) FROM recovery_runs WHERE matched = false)
    UNION ALL SELECT 'C18','FILL_PRICE_MAKER','CRITICAL','ALWAYS',
           (SELECT count(*) FROM trades t
                JOIN orders m ON m.id = t.maker_order_id
                WHERE m.price IS NOT NULL AND t.price <> m.price)
    UNION ALL SELECT 'C19','ORDERBOOK_NONCROSS','HIGH','CONDITIONAL',
           (SELECT count(*) FROM (
                SELECT market_id FROM orders
                WHERE status IN ('OPEN','PARTIAL') AND price IS NOT NULL
                GROUP BY market_id
                HAVING max(price) FILTER (WHERE side='BUY')
                       >= min(price) FILTER (WHERE side='SELL')) x)
)
SELECT code, check_name, severity, scope, violated_rows,
       CASE WHEN violated_rows = 0 THEN 'PASS' ELSE 'FAIL' END AS verdict
FROM v
ORDER BY (violated_rows > 0) DESC, severity, code;
