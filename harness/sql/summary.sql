-- ============================================================================
-- summary.sql
-- invariants.sql 카탈로그를 건별 위반 카운트로 집계한 기계 판정본. violated_rows = 0이 정상.
-- 게이트 규칙과 실행 명령은 harness/README.md 판정(기계) 절에 둔다.
--
-- 게이트가 CRITICAL만 세면 C09~C12 고아·C14 과다락·C20 수수료 드리프트가 그냥 지나고,
--   C22·C23을 CONDITIONAL이라고 빼면 fencing 역행·채번 재사용이 권위 run을 그대로 통과한다.
--   WARN(C15·C24·C25)은 §4-3이 WARN에 거래 정지를 금지하므로 게이트에서 뺀다.
-- 미평가 표기: violated_rows IS NULL = SKIP이다. 0(무결)과 구분하지 않으면 미평가가 무결로
--   읽혀 거짓 통과가 된다. 게이트가 verdict='FAIL'만 세므로 SKIP은 판정 대상 밖이며, 이 표기는
--   WARN 행 한정이다. CRITICAL·HIGH 행을 SKIP으로 낼 일이 생기면 미평가가 게이트를 조용히
--   지나므로 그때는 SKIP을 FAIL로 접거나 게이트를 verdict<>'PASS'로 바꿔야 한다.
-- 범위: scope='CONDITIONAL' 행은 권위 run/정지 상태에서만 유효해 상시 스케줄 판정에서 제외한다.
-- C20~C25의 술어 정본은 앱 InvariantCheck enum이고 C24·C25는 V13 검증 뷰다. 축어 이식만 한다.
-- C08·C09·C11·C12·C18의 90일 윈도 미정합은 invariants.sql 헤더 참조.
-- 본문에 psql 메타명령(\if/\else/\endif)이 있어 UNION을 -c 인라인으로 재조립할 수 없다.
--   뷰 부재는 파스타임 실패라 CASE로 피할 수 없고 \if가 유일한 수단이다.
-- ============================================================================
\pset pager off

-- V13 미적용 DB에는 원가 검증 뷰가 없다. 무조건 참조하면 단일 문장이라 29개 판정이 통째로
-- 사라진다(ON_ERROR_STOP 경로에서 exit 3). 부재 시 두 행을 SKIP으로 낸다.
SELECT (to_regclass('public.position_cost_qty_check') IS NOT NULL
        AND to_regclass('public.position_cost_sanity_check') IS NOT NULL) AS has_cost_views \gset

WITH v(code, check_name, severity, scope, violated_rows) AS (
    SELECT 'C01','ASSET_CONSERVATION','CRITICAL','ALWAYS',
           (SELECT count(*) FROM (SELECT asset_id FROM ledger_entries
                GROUP BY asset_id HAVING SUM(amount) <> 0) x)
    UNION ALL SELECT 'C02','BAL_VS_LEDGER','CRITICAL','ALWAYS',
           (SELECT count(*) FROM balance_ledger_check WHERE mismatch)
    UNION ALL SELECT 'C03','TXN_UNSEALED','HIGH','ALWAYS',
           (SELECT count(*) FROM ledger_transactions
                WHERE status='OPEN' AND created_at < now() - interval '1 min')
    UNION ALL SELECT 'C04','TXN_SEALED_UNBALANCED','CRITICAL','ALWAYS',
           (SELECT count(*) FROM (
                SELECT l.transaction_id, l.asset_id FROM ledger_entries l
                JOIN ledger_transactions t ON t.id = l.transaction_id AND t.status='SEALED'
                GROUP BY l.transaction_id, l.asset_id HAVING SUM(l.amount) <> 0) x)
    UNION ALL SELECT 'C05','CMD_JOURNAL_GAPLESS','CRITICAL','ALWAYS',
           -- 명세 부재 + 레거시 구간 전역 결번 + 마켓 안 대역 결번. InvariantCheck 축어(ADR 0022 6-1).
           (WITH s AS (SELECT band_span FROM journal_band_spec WHERE name = 'command_seq')
            SELECT (SELECT CASE WHEN count(*) = 1 THEN 0 ELSE 1 END FROM s)
                 + (SELECT COALESCE((max(c.seq) - min(c.seq) + 1) - count(*), 0)
                    FROM command_journal c
                    WHERE c.seq < (SELECT band_span FROM s))
                 + (SELECT COALESCE(sum(b.gap), 0) FROM (
                        SELECT (max(c.seq) - min(c.seq) + 1) - count(*) AS gap
                        FROM command_journal c
                        WHERE c.seq >= (SELECT band_span FROM s)
                        GROUP BY c.market_id
                    ) b))
    UNION ALL SELECT 'C06','EVENT_JOURNAL_GAPLESS','CRITICAL','ALWAYS',
           -- V36 에서 축이 마켓 안으로 갔다. event_seq 는 marketId * 10^7 + market_seq 파생이라
           -- 전역으로 세면 마켓 대역 사이의 빈 구간이 전부 결번으로 잡힌다. 결번 없음이 성립하는
           -- 곳은 마켓 안이다. market_seq 가 빈 V36 이전 행은 이 검사 밖이다.
           (SELECT COALESCE(sum(gap), 0) FROM (
                SELECT (max(market_seq) - min(market_seq) + 1) - count(*) AS gap
                FROM event_journal WHERE market_seq IS NOT NULL GROUP BY market_id) g)
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
    UNION ALL SELECT 'C15','DEFAULT_PARTITION','WARN','ALWAYS',
           (SELECT count(*) FROM ledger_entries_default)
           + (SELECT count(*) FROM event_journal_default)
           + (SELECT count(*) FROM trades_default)
           + (SELECT count(*) FROM outbox_default)
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
    UNION ALL SELECT 'C20','ORDER_FEE_MATERIALIZED','HIGH','ALWAYS',
           (SELECT count(*) FROM (
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
                    OR o.trades_count <> COUNT(legs.oid)
            ) x)
    UNION ALL SELECT 'C21','EVENT_EMISSION_REGISTRY','CRITICAL','ALWAYS',
           (SELECT count(*) FROM event_journal e
                LEFT JOIN event_emission_registry r ON r.event_seq = e.event_seq
                WHERE e.created_at >= now() - interval '90 days' AND r.event_seq IS NULL)
           + (SELECT count(*) FROM event_emission_registry r
                LEFT JOIN event_journal e ON e.event_seq = r.event_seq
                WHERE r.created_at >= now() - interval '90 days' AND e.event_seq IS NULL)
    UNION ALL SELECT 'C22','FENCING_MONOTONIC','CRITICAL','CONDITIONAL',
           -- 시간 축 팔 + 마켓 안 팔. InvariantCheck 축어(ADR 0022 6-2). 시계 역행 뒤 거짓 양성 한계는 9-17.
           (SELECT (
                SELECT count(*) FROM (
                    SELECT fencing_token,
                           lag(fencing_token) OVER (ORDER BY created_at, seq) AS prev
                    FROM command_journal
                ) t
                WHERE t.prev > t.fencing_token
            ) + (
                SELECT count(*) FROM (
                    SELECT fencing_token,
                           lag(fencing_token) OVER (PARTITION BY market_id ORDER BY seq) AS prev
                    FROM command_journal
                ) t
                WHERE t.prev > t.fencing_token
            ))
    UNION ALL SELECT 'C23','COUNTER_SEQ_CONSISTENCY','CRITICAL','CONDITIONAL',
           -- 명세 부재 + 명령 레거시 팔 + 명령 대역 팔 + 이벤트 팔. InvariantCheck 축어(ADR 0022 6-3).
           (WITH s AS (SELECT band_span FROM journal_band_spec WHERE name = 'command_seq')
            SELECT (SELECT CASE WHEN count(*) = 1 THEN 0 ELSE 1 END FROM s)
                 + (SELECT count(*) FROM journal_counters c
                    WHERE c.name = 'command_seq'
                      AND c.next_value <= (SELECT COALESCE(max(j.seq), 0)
                                           FROM command_journal j
                                           WHERE j.seq < (SELECT band_span FROM s)))
                 + (SELECT count(*) FROM market_journal_counters m
                    WHERE m.name = 'command_seq'
                      AND m.next_value <= (SELECT COALESCE(max(j.seq - j.market_id * (SELECT band_span FROM s)), 0)
                                           FROM command_journal j
                                           WHERE j.market_id = m.market_id
                                             AND j.seq >= (SELECT band_span FROM s)))
                 + (SELECT count(*) FROM market_journal_counters m
                    WHERE m.name = 'event_seq'
                      AND m.next_value <= (SELECT COALESCE(max(e.market_seq), 0)
                                           FROM event_journal e
                                           WHERE e.market_id = m.market_id
                                             AND e.market_seq IS NOT NULL)))
    UNION ALL SELECT 'C27','CMD_DEFAULT_PARTITION','CRITICAL','ALWAYS',
           (SELECT count(*) FROM command_journal_default)
    UNION ALL SELECT 'C28','CMD_LEGACY_AFTER_CUTOVER','CRITICAL','ALWAYS',
           -- 마켓 안 첫 대역 행보다 늦은 레거시 행 수. latched_at 과 비교하지 않는다. InvariantCheck 축어(ADR 0022 6단계).
           (WITH s AS (SELECT band_span FROM journal_band_spec WHERE name = 'command_seq'),
                 b AS (SELECT c.market_id, min(c.created_at) AS first_band_at
                       FROM command_journal c, s
                       WHERE c.seq >= s.band_span
                       GROUP BY c.market_id)
            SELECT count(*)
            FROM command_journal c
            JOIN b ON b.market_id = c.market_id
            CROSS JOIN s
            WHERE c.seq < s.band_span
              AND c.created_at > b.first_band_at)
\if :has_cost_views
    UNION ALL SELECT 'C24','POSITION_COST_QTY','WARN','CONDITIONAL',
           (SELECT count(*) FROM position_cost_qty_check)
    UNION ALL SELECT 'C25','POSITION_COST_SANITY','WARN','CONDITIONAL',
           (SELECT count(*) FROM position_cost_sanity_check)
\else
    UNION ALL SELECT 'C24','POSITION_COST_QTY','WARN','CONDITIONAL', NULL
    UNION ALL SELECT 'C25','POSITION_COST_SANITY','WARN','CONDITIONAL', NULL
\endif
)
SELECT code, check_name, severity, scope, violated_rows,
       CASE WHEN violated_rows IS NULL THEN 'SKIP'
            WHEN violated_rows = 0     THEN 'PASS'
            ELSE 'FAIL' END AS verdict
FROM v
-- NULLS FIRST(DESC 기본)로 SKIP이 위반 행과 같이 맨 위에 온다. 미평가를 화면 끝에 묻지 않는다.
ORDER BY (violated_rows > 0) DESC, severity, code;
