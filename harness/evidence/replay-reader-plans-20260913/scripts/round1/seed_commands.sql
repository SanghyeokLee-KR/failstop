-- 사용: psql -v n=<명령 수> -f seed_commands.sql
-- 전역 채번 함수로 블록을 받아 그 구간을 조밀하게 쓴다. 삽입 순서는 seq 순서(append).
\set ON_ERROR_STOP 1
\timing on
SELECT next_journal_value('command_seq', :n) AS cmd_start \gset
\echo cmd_start=:cmd_start
INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id,
                             assigned_order_id, engine_ts, tie_break, payload,
                             fencing_token, checksum, created_at)
SELECT s,
       bench.mkt(s),
       CASE WHEN bench.is_cancel(s) THEN 'CANCEL' ELSE 'NEW' END,
       1000 + (((hashint8extended(s, 5) % 5000) + 5000) % 5000),
       CASE WHEN bench.is_cancel(s) THEN NULL ELSE md5('coid' || s)::uuid::text END,
       s,
       1785000000000 + s * 3,
       s,
       jsonb_build_object('side', CASE WHEN s % 2 = 0 THEN 'BUY' ELSE 'SELL' END,
                          'orderType', 'LIMIT',
                          'price', (50000000 + (s % 1000) * 1000)::text,
                          'qty', '0.01230000', 'tif', 'GTC', 'marketState', 'ACTIVE',
                          'makerFeeRate', '0.0005', 'takerFeeRate', '0.0005',
                          'externalId', md5('ext' || s)::uuid::text),
       1,
       hashint8extended(s, 6) & 4294967295,
       timestamptz '2026-08-01 00:00:00+00' + s * interval '10 milliseconds'
FROM generate_series(:cmd_start::bigint, :cmd_start::bigint + :n - 1) s;
