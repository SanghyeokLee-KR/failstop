\set VERBOSITY verbose
\echo == C1 V48 적용 직후
SELECT 'lease rows', string_agg(resource || ':' || holder || ':' || epoch, ',' ORDER BY resource) FROM leader_lease;
SELECT 'protocol', protocol FROM leader_protocol;
SELECT 'history', protocol, prev_protocol, epoch_floor FROM leader_protocol_history ORDER BY id;
SELECT 'mapping', string_agg(market_id || ':' || resource, ',' ORDER BY market_id) FROM market_lease_partition;
SELECT 'acquisitions', count(*) FROM lease_acquisitions;
\echo == C2 V49 전에는 앱 역할이 종전처럼 leader_lease 를 직접 UPDATE 하고 command_journal 에 직접 INSERT 한다
BEGIN;
SET LOCAL ROLE app_role;
UPDATE leader_lease SET lease_until = lease_until WHERE resource = 'matching-engine';
INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id, assigned_order_id,
                             engine_ts, tie_break, payload, fencing_token, checksum)
VALUES (999999, 1, 'NEW', 1, 'direct-c2', 1, 0, 0, '{}'::jsonb, 7, 0);
ROLLBACK;
\echo == C3 P1 2인자 획득은 기존 epoch 41 에서 정확히 +1
SELECT expire_leader_lease_now();
SELECT 'legacy acquire', try_acquire_leader('node-a', interval '60 seconds');
\echo == C4 만료 전 3인자 획득은 NULL, 만료 뒤 +1
SELECT 'not expired is null', try_acquire_lease('matching-engine', 'node-b', interval '60 seconds') IS NULL;
SELECT expire_leader_lease_now();
SELECT '3-arg acquire', try_acquire_lease('matching-engine', 'node-b', interval '60 seconds');
\echo == C5 P1 에서 파티션 자원 획득은 거부
SELECT try_acquire_lease('matching-p0', 'node-x', interval '60 seconds');
\echo == C6 롤백한 획득은 epoch 를 남기지 않는다
BEGIN;
SELECT expire_leader_lease_now();
SELECT 'in-tx acquire', try_acquire_lease('matching-engine', 'node-r', interval '60 seconds');
ROLLBACK;
SELECT expire_leader_lease_now();
SELECT 'acquire after rollback', try_acquire_lease('matching-engine', 'node-c', interval '60 seconds');
\echo == C7 P1 게시, 같은 보유자 재게시, 뒤이은 DB 획득
SELECT 'publish', publish_leader_epoch('node-d', 50);
SELECT 'republish', publish_leader_epoch('node-d', 50);
SELECT 'publish rows', count(*) FROM lease_acquisitions WHERE origin = 'PUBLISH';
SELECT expire_leader_lease_now();
SELECT 'acquire after publish', try_acquire_lease('matching-engine', 'node-e', interval '60 seconds');
SELECT 'acquisitions', string_agg(epoch || ':' || resource || ':' || holder || ':' || origin, ',' ORDER BY epoch) FROM lease_acquisitions;
\echo == C8 앱 역할 쓰기 경로(P1). markets 에 없는 마켓 77 도 쓴다
BEGIN;
SET LOCAL ROLE app_role;
SELECT out_seq AS s1 FROM append_command_prologue('node-e', 51, true, 1, 'coid-1', 1::smallint) \gset
SELECT 'append m1', append_command_journal_row(:s1, 1::smallint, 'NEW', 1, 'coid-1', 1001, 0, 0, '{}'::jsonb, 0, 'node-e', 51);
SELECT out_seq AS s2 FROM append_command_prologue('node-e', 51, true, 1, 'coid-77', 77::smallint) \gset
SELECT 'append m77', append_command_journal_row(:s2, 77::smallint, 'NEW', 1, 'coid-77', 1002, 0, 0, '{}'::jsonb, 0, 'node-e', 51);
SELECT 'stale append rows', append_command_journal_row(:s2 + 1000, 1::smallint, 'NEW', 1, 'coid-x', 1003, 0, 0, '{}'::jsonb, 0, 'node-e', 50);
SELECT 'market lock', assert_and_lock_market_leader(1::smallint, 'node-e', 51);
SELECT 'legacy lock', assert_and_lock_leader('node-e', 51);
COMMIT;
SELECT 'journal', string_agg(market_id || ':' || fencing_token, ',' ORDER BY seq) FROM command_journal;
\echo == C9 판정 문구(P1 은 V1 과 같다)
BEGIN;
SET LOCAL ROLE app_role;
SELECT assert_and_lock_market_leader(1::smallint, 'node-e', 50);
ROLLBACK;
\echo == C10 함수 실행 권한(app_role 이 아닌 역할과 app_role)
DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'probe_reader') THEN CREATE ROLE probe_reader NOLOGIN; END IF; END $$;
SELECT 'exec', f, has_function_privilege('probe_reader', f, 'EXECUTE') AS reader, has_function_privilege('app_role', f, 'EXECUTE') AS app
FROM unnest(ARRAY[
  'try_acquire_leader(text,interval)', 'try_acquire_lease(text,text,interval)',
  'renew_leader_lease(text,bigint,interval)', 'renew_lease(text,text,bigint,interval)',
  'release_lease(text,text,bigint)', 'publish_leader_epoch(text,bigint)', 'expire_leader_lease_now()',
  'assert_and_lock_leader(text,bigint)', 'assert_and_lock_market_leader(smallint,text,bigint)',
  'append_command_journal_row(bigint,smallint,character varying,bigint,character varying,bigint,bigint,bigint,jsonb,bigint,text,bigint)',
  'lease_acquire_core(text,text,interval,boolean)', 'allocate_lease_epoch()',
  'seed_market_lease_partition()',
  'lease_resource_for_market(smallint)', 'assert_lease_holder(text,text,bigint)']) AS f;
\echo == C11 앱 역할의 마켓 추가와 대응 표·프로토콜 표의 추가 전용
BEGIN;
SET LOCAL ROLE app_role;
INSERT INTO markets (id, symbol, base_asset_id, quote_asset_id, tick_size, lot_size, min_notional, max_notional, max_qty)
VALUES (4, 'M4', 5, 1, 1, 0.0001, 0, 1000000000, 1000000),
       (13, 'M13', 3, 1, 1, 0.0001, 0, 1000000000, 1000000),
       (14, 'M14', 4, 1, 1, 0.0001, 0, 1000000000, 1000000);
COMMIT;
SELECT 'mapping after insert', string_agg(market_id || ':' || resource, ',' ORDER BY market_id) FROM market_lease_partition;
UPDATE market_lease_partition SET resource = 'matching-p1' WHERE market_id = 1;
DELETE FROM market_lease_partition WHERE market_id = 1;
TRUNCATE market_lease_partition;
DELETE FROM leader_protocol;
UPDATE leader_protocol_history SET protocol = 2;
