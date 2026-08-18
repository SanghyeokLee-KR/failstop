-- ============================================================================
-- summary.sql
-- invariants.sql 카탈로그를 건별 위반 카운트로 집계한 1화면 기계 판정본.
-- 각 행 violated_rows = 0 이어야 정상. verdict 열은 심각도별 라벨.
--
-- 용도: CI/실험 러너가 `psql -At -f summary.sql`로 파싱해 exit code로 환원하거나,
--       발표 데모 대시보드가 pg-proof 데이터소스로 직조회한다.
-- 판정 규약: CRITICAL·HIGH 위반 1건이라도 존재 → 실험 FAIL. WARN(C15·C24·C25)은 게이트에서
--       빠진다. §4-3이 WARN에 거래 정지를 금지하고 조치를 파티션 유지보수·프로젝션 재구축으로
--       한정하기 때문이다. 판정이 아니라 표시다. 등급 경계는 lib.ps1 실험 판정 술어와 같다.
--       (CRITICAL만 세면 C09~C12 고아·C14 과다락·C20 수수료 드리프트가 게이트를 그냥 지난다.)
--
-- 실행: psql -U exchange -d exchange -q -f summary.sql
--       -q를 붙인다. 없으면 아래 \pset 알림이 stdout 첫 줄에 섞여 -At 파싱을 깬다.
-- 게이트: 이 파일의 출력을 파싱한다. 본문에 psql 메타명령(\if/\else/\endif)이 있어 UNION을
--       -c 인라인으로 재조립할 수 없다. 뷰 부재는 파스타임 실패라 CASE로는 피할 수 없고
--       \if가 유일한 수단이다. 판정: 등급 CRITICAL·HIGH이고 verdict=FAIL인 행이 0.
--         psql -Atq -f summary.sql |
--           Where-Object { $_ -match '^(?!C13A\||C16\||C19\|).*\|(CRITICAL|HIGH)\|.*\|FAIL$' }
--       C13A·C16·C19는 등급과 무관하게 면제한다(lib.ps1 CHAOS_GATING_EXEMPT_CHECKS와 같은
--       목록, 같은 사유). C22·C23은 CONDITIONAL이지만 면제가 아니라 게이팅 대상이다. 빼면
--       fencing 역행·채번 재사용이 권위 run을 그대로 통과한다.
-- 범위: C16(실험 한정)·C13a·C19(정지 시점 한정)·C22·C23·C24·C25(권위 run 한정)는 권위 run/
--       정지 상태에서만 유효. 상시 스케줄 판정에서는 scope='CONDITIONAL' 행을 제외한다.
-- C20~C25의 술어 정본은 앱 InvariantCheck enum이다. 윈도·조인 조건을 바꾸면 두 경로가 갈리므로
-- 축어 이식만 한다. C24·C25는 V13 검증 뷰를 그대로 센다(사본 SQL 금지).
-- C08·C09·C11·C12·C18의 90일 윈도 미정합은 invariants.sql 헤더 기록 참조(detach 도입 전까지 무해).
-- 미평가 표기: violated_rows IS NULL = SKIP이다. 0(무결)과 구분하지 않으면 미평가가 무결로
--       읽혀 거짓 통과가 된다. 게이트가 verdict='FAIL'만 세므로 SKIP은 통과가 아니라 판정
--       대상 밖이며, 판독자가 SKIP 행을 보고 그 사유를 해소해야 한다.
--       이 표기는 WARN 행 한정이다. CRITICAL·HIGH 행을 SKIP으로 낼 일이 생기면 미평가가 게이트를
--       조용히 지나므로, 그때는 SKIP을 FAIL로 접거나 게이트를 verdict<>'PASS'로 바꿔야 한다.
-- ============================================================================
\pset pager off

-- V13 미적용 DB에는 원가 검증 뷰가 없다. 무조건 참조하면 단일 문장이라 25개 판정이 통째로
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
    UNION ALL SELECT 'C15','DEFAULT_PARTITION','WARN','ALWAYS',
           (SELECT count(*) FROM ledger_entries_default)
           + (SELECT count(*) FROM command_journal_default)
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
           (SELECT count(*) FROM (
                SELECT seq, fencing_token,
                       lag(fencing_token) OVER (ORDER BY seq) AS prev
                FROM command_journal
            ) t
            WHERE t.prev > t.fencing_token)
    UNION ALL SELECT 'C23','COUNTER_SEQ_CONSISTENCY','CRITICAL','CONDITIONAL',
           (SELECT count(*) FROM journal_counters c
            WHERE (c.name = 'command_seq'
                     AND c.next_value <= (SELECT COALESCE(max(seq), 0) FROM command_journal))
               OR (c.name = 'event_seq'
                     AND c.next_value <= (SELECT COALESCE(max(event_seq), 0) FROM event_journal)))
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
