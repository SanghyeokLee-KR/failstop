-- ============================================================================
-- summary.sql
-- invariants.sql 카탈로그를 건별 위반 카운트로 집계한 기계 판정본. violated_rows = 0이 정상.
-- 게이트 규칙과 실행 명령은 harness/README.md 판정(기계) 절에 둔다.
--
-- 게이트가 CRITICAL만 세면 C09~C12 고아·C14 과다락·C20 수수료 드리프트가 그냥 지나고,
--   C22·C23을 CONDITIONAL이라고 빼면 fencing 역행·채번 재사용이 권위 run을 그대로 통과한다.
--   WARN(C15·C24·C25)은 §4-3이 WARN에 거래 정지를 금지하므로 게이트에서 뺀다.
-- 판정 어휘(하네스 검증설계 §4-1 '판정 어휘', ADR 0034 S3): verdict 는 PASS·FAIL·SKIP·N/A 넷이다.
--   SKIP 은 평가하지 못한 행이다(violated_rows IS NULL). 지금 SKIP 을 내는 행은 WARN 인 C24·C25(V13 부재)뿐이다.
--   N/A 는 그 판정이 읽는 마이그레이션 객체가 모두 없어 구조적으로 대상이 없는 행이다. C29(V48 객체 다섯)와
--   C31(V50 객체 둘)만 낸다. violated_rows 는 NULL 이다. 대상 객체가 일부만 있으면 FAIL 이고 violated_rows 는 없는 객체 수다.
--   이 파일을 읽는 복원·복구 점검(deploy/backup/backup.py, deploy/linux/postgresql의 두 점검,
--   deploy/lab/run.py, VM 실습망의 두 스크립트)은 CRITICAL·HIGH 행의 FAIL·SKIP 을 실패로 세고, N/A 는 통과로 세지 않고
--   not_applicable 에 따로 적는다. C29·C31 밖의 N/A 는 형식 오류다. 게이트를 verdict='FAIL'만 세도록 바꾸면 미평가 SKIP 이
--   조용히 지난다.
-- 범위: scope='CONDITIONAL' 행은 권위 run/정지 상태에서만 유효해 상시 스케줄 판정에서 제외한다.
-- C20~C25의 술어 정본은 앱 InvariantCheck enum이고 C24·C25는 V13 검증 뷰다. 축어 이식만 한다.
-- C31 은 전수 판이다. 앱 enum 은 비용 때문에 권위 run 한정·최근 창 판을 쓴다(하네스 검증설계 §4-1 C31).
-- C08·C09·C11·C12·C18의 90일 윈도 미정합은 invariants.sql 헤더 참조.
-- 본문에 psql 메타명령(\if/\else/\endif)이 있어 UNION을 -c 인라인으로 재조립할 수 없다.
--   뷰 부재는 파스타임 실패라 CASE로 피할 수 없고 \if가 유일한 수단이다.
-- ============================================================================
\pset pager off

-- V13 미적용 DB에는 원가 검증 뷰가 없다. 무조건 참조하면 단일 문장이라 32개 판정이 통째로
-- 사라진다(ON_ERROR_STOP 경로에서 exit 3). 부재 시 두 행을 SKIP으로 낸다.
SELECT (to_regclass('public.position_cost_qty_check') IS NOT NULL
        AND to_regclass('public.position_cost_sanity_check') IS NOT NULL) AS has_cost_views \gset
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
           -- V36 에서 축이 마켓 안으로 갔다. event_seq 는 marketId * band_span + market_seq 파생이라
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
\if :has_reservation_table
    UNION ALL SELECT 'C14','LOCKED_RESERVATION','HIGH','ALWAYS',
           -- C14 개정(ADR 0034 S3): 열린 주문 잔여 + 정산 전 접수 예약(정산 커서 위) + 대기 출금. InvariantCheck 축어.
           (SELECT count(*) FROM (
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
                  AND b.balance <> COALESCE(o.amt, 0) + COALESCE(u.amt, 0) + COALESCE(w.amt, 0)
            ) x)
\else
    UNION ALL SELECT 'C14','LOCKED_RESERVATION','HIGH','ALWAYS',
           -- 예약 표 없는 스키마(V49 이하)의 같은 판정. 정산 전 예약 항이 없다.
           (SELECT count(*) FROM (
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
                  AND b.balance <> COALESCE(o.amt, 0) + COALESCE(w.amt, 0)
            ) x)
\endif
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
    UNION ALL SELECT 'C26','CMD_BAND_HEADROOM','HIGH','ALWAYS',
           -- 명령 대역 잔여 10퍼센트 미만 마켓 수. InvariantCheck 축어(ADR 0022 6-4).
           (SELECT count(*) FROM v_journal_band_headroom
                WHERE name = 'command_seq' AND remaining_ratio < 0.1)
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
\if :has_partition_lease
    UNION ALL SELECT 'C29','MARKET_PARTITION_MAP','CRITICAL','ALWAYS',
           -- 대응 없는 마켓 + 없는 리스 행 + 한 행이 아닌 leader_protocol. InvariantCheck 축어(ADR 0034 S2, V48).
           (SELECT (SELECT count(*) FROM markets m
                    WHERE NOT EXISTS (SELECT 1 FROM market_lease_partition p WHERE p.market_id = m.id))
                 + (SELECT count(*) FROM (VALUES ('matching-engine'), ('matching-p0'), ('matching-p1')) AS r(resource)
                    WHERE NOT EXISTS (SELECT 1 FROM leader_lease l WHERE l.resource = r.resource))
                 + (SELECT CASE WHEN count(*) = 1 THEN 0 ELSE 1 END FROM leader_protocol))
\else
    -- 모두 없으면 NULL(아래 na 가 N/A 로 적는다), 일부만 있으면 없는 객체 수(FAIL).
    UNION ALL SELECT 'C29','MARKET_PARTITION_MAP','CRITICAL','ALWAYS', NULLIF(5 - :v48_objects, 5)
\endif
\if :has_reservations
    UNION ALL SELECT 'C31','RESERVATION_LINK','CRITICAL','ALWAYS',
           -- 전수 판 세 항의 행 수 합(하네스 검증설계 §4-1 C31). 앱 enum 은 권위 run 한정·최근 창 판이다.
           (SELECT count(*) FROM (
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
                                     AND sha256(convert_to(c.payload::text, 'UTF8')) = r.payload_sha256)
            ) x)
\else
    UNION ALL SELECT 'C31','RESERVATION_LINK','CRITICAL','ALWAYS', NULLIF(2 - :v50_objects, 2)
\endif
\if :has_cost_views
    UNION ALL SELECT 'C24','POSITION_COST_QTY','WARN','CONDITIONAL',
           (SELECT count(*) FROM position_cost_qty_check)
    UNION ALL SELECT 'C25','POSITION_COST_SANITY','WARN','CONDITIONAL',
           (SELECT count(*) FROM position_cost_sanity_check)
\else
    UNION ALL SELECT 'C24','POSITION_COST_QTY','WARN','CONDITIONAL', NULL
    UNION ALL SELECT 'C25','POSITION_COST_SANITY','WARN','CONDITIONAL', NULL
\endif
), na(code) AS (
    -- N/A 표시. 대상 객체가 모두 없는 C29·C31 만 여기 든다. 출력 열 머리는 그대로다.
    SELECT 'C29' WHERE :v48_objects = 0
    UNION ALL SELECT 'C31' WHERE :v50_objects = 0
)
SELECT code, check_name, severity, scope, violated_rows,
       CASE WHEN code IN (SELECT code FROM na) THEN 'N/A'
            WHEN violated_rows IS NULL THEN 'SKIP'
            WHEN violated_rows = 0     THEN 'PASS'
            ELSE 'FAIL' END AS verdict
FROM v
-- NULLS FIRST(DESC 기본)로 SKIP·N/A 가 위반 행과 같이 맨 위에 온다. 미평가와 대상 없음을 화면 끝에 묻지 않는다.
ORDER BY (violated_rows > 0) DESC, severity, code;
