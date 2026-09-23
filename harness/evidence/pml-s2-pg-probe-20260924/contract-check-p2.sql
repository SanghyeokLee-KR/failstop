\set VERBOSITY verbose
\echo == D1 V49 뒤 앱 역할의 직접 UPDATE, KEY SHARE, command_journal 직접 INSERT 는 거부
BEGIN;
SET LOCAL ROLE app_role;
UPDATE leader_lease SET lease_until = lease_until WHERE resource = 'matching-engine';
ROLLBACK;
BEGIN;
SET LOCAL ROLE app_role;
SELECT epoch FROM leader_lease WHERE resource = 'matching-engine' FOR KEY SHARE;
ROLLBACK;
BEGIN;
SET LOCAL ROLE app_role;
INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id, assigned_order_id,
                             engine_ts, tie_break, payload, fencing_token, checksum)
VALUES (999999, 1, 'NEW', 1, 'direct-d1', 1, 0, 0, '{}'::jsonb, 7, 0);
ROLLBACK;
\echo == D2 V49 뒤 앱 역할의 함수 경로
BEGIN;
SET LOCAL ROLE app_role;
SELECT 'renew legacy', renew_leader_lease('node-e', 51, interval '60 seconds');
SELECT 'renew', renew_lease('matching-engine', 'node-e', 51, interval '60 seconds');
SELECT out_seq AS s3 FROM append_command_prologue('node-e', 51, true, 1, 'coid-3', 1::smallint) \gset
SELECT 'append after revoke', append_command_journal_row(:s3, 1::smallint, 'NEW', 1, 'coid-3', 1004, 0, 0, '{}'::jsonb, 0, 'node-e', 51);
SELECT 'market lock after revoke', assert_and_lock_market_leader(1::smallint, 'node-e', 51);
SELECT 'legacy lock after revoke', assert_and_lock_leader('node-e', 51);
COMMIT;
BEGIN;
SET LOCAL ROLE app_role;
SELECT 'release', release_lease('matching-engine', 'node-e', 51);
SELECT 'acquire after revoke', try_acquire_lease('matching-engine', 'node-f', interval '60 seconds');
COMMIT;
\echo == D3 P3 흉내(소유자 UPDATE). 같은 값 UPDATE 는 이력을 남기지 않는다
UPDATE leader_protocol SET protocol = 3;
UPDATE leader_protocol SET protocol = 3;
SELECT 'history', protocol, prev_protocol, epoch_floor FROM leader_protocol_history ORDER BY id;
SELECT expire_leader_lease_now();
SELECT try_acquire_leader('node-z', interval '60 seconds');
SELECT try_acquire_lease('matching-engine', 'node-z', interval '60 seconds');
SELECT try_acquire_lease('matching-p0', 'node-p0', interval '60 seconds') AS e0 \gset
SELECT try_acquire_lease('matching-p1', 'node-p1', interval '60 seconds') AS e1 \gset
SELECT 'partition epochs', :e0, :e1;
BEGIN;
SET LOCAL ROLE app_role;
SELECT 'cross append rows (p0 holder, market 2)', append_command_journal_row(900001, 2::smallint, 'NEW', 1, 'x2', 2001, 0, 0, '{}'::jsonb, 0, 'node-p0', :e0);
SELECT 'own append rows (p0 holder, market 1)', append_command_journal_row(900002, 1::smallint, 'NEW', 1, 'x1', 2002, 0, 0, '{}'::jsonb, 0, 'node-p0', :e0);
COMMIT;
BEGIN;
SET LOCAL ROLE app_role;
SELECT * FROM append_command_prologue('node-p0', :e0, true, 1, 'x3', 2::smallint);
ROLLBACK;
BEGIN;
SET LOCAL ROLE app_role;
SELECT assert_and_lock_market_leader(77::smallint, 'node-p0', :e0);
ROLLBACK;
SELECT publish_leader_epoch('node-q', 999);
SELECT 'row after publish attempt', holder, epoch FROM leader_lease WHERE resource = 'matching-engine';
SELECT 'acquisitions', string_agg(epoch || ':' || resource || ':' || holder || ':' || origin || ':P' || protocol, ',' ORDER BY epoch) FROM lease_acquisitions;
\echo == D4 C29 과 양성 대조
SELECT 'C29', (SELECT count(*) FROM markets m
                WHERE NOT EXISTS (SELECT 1 FROM market_lease_partition p WHERE p.market_id = m.id))
            + (SELECT count(*) FROM (VALUES ('matching-engine'), ('matching-p0'), ('matching-p1')) AS r(resource)
                WHERE NOT EXISTS (SELECT 1 FROM leader_lease l WHERE l.resource = r.resource))
            + (SELECT CASE WHEN count(*) = 1 THEN 0 ELSE 1 END FROM leader_protocol);
SET session_replication_role = replica;
INSERT INTO markets (id, symbol, base_asset_id, quote_asset_id, tick_size, lot_size, min_notional, max_notional, max_qty)
VALUES (20, 'M20', 2, 1, 1, 0.0001, 0, 1000000000, 1000000);
SET session_replication_role = origin;
SELECT 'C29 after trigger-less insert', (SELECT count(*) FROM markets m
                WHERE NOT EXISTS (SELECT 1 FROM market_lease_partition p WHERE p.market_id = m.id))
            + (SELECT count(*) FROM (VALUES ('matching-engine'), ('matching-p0'), ('matching-p1')) AS r(resource)
                WHERE NOT EXISTS (SELECT 1 FROM leader_lease l WHERE l.resource = r.resource))
            + (SELECT CASE WHEN count(*) = 1 THEN 0 ELSE 1 END FROM leader_protocol);
