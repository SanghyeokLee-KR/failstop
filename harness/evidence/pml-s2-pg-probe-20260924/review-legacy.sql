-- S2 이전 바이너리가 보내는 SQL(2026-09-24 작업 트리의 코드에서 옮긴 문자열)을 app_role 로그인으로 실행한다.
-- 호출자가 :ep(이번 획득에서 기대하는 epoch)를 \set 으로 넘긴다. 문장마다 자동 커밋이라 앞 문장의 오류가 뒤를 막지 않는다.
-- 그래서 INSERT 가 거부된 회차에도 머리말이 뽑은 번호는 소비된 채 남는다. 앱은 머리말과 INSERT 를 한 트랜잭션에서
-- 보내므로 INSERT 가 거부되면 번호도 함께 롤백된다. 이 파일이 보는 것은 권한과 반환값이다.
--   PostgresLeaderLeaseStore.acquire·renew·release, CommandJournalAppender.PROLOGUE_SQL·INSERT_SQL,
--   SettlementService.PROLOGUE_SQL, SnapshotStore·EventJournalAppender.ASSERT_AND_LOCK_SQL, PostgresFencingEpochPublisher
\set VERBOSITY verbose
\set SHOW_CONTEXT never
SELECT 'legacy acquire', try_acquire_leader('legacy', 60000 * interval '1 millisecond');
SELECT 'legacy renew', renew_leader_lease('legacy', :ep, 60000 * interval '1 millisecond');
SELECT out_seq AS s FROM append_command_prologue('legacy', :ep, true, 1, 'L' || :ep, 1::smallint) \gset
INSERT INTO command_journal
  (seq, market_id, command_type, user_id, client_order_id, assigned_order_id,
   engine_ts, tie_break, payload, fencing_token, checksum)
SELECT :s, 1, 'NEW', 1, 'L' || :ep, 5000 + :ep, 0, 0, '{}'::jsonb, l.epoch, 0
FROM leader_lease l
WHERE l.resource = 'matching-engine'
  AND l.holder = 'legacy' AND l.epoch = :ep AND l.lease_until > clock_timestamp()
FOR KEY SHARE OF l;
INSERT INTO command_journal
  (seq, market_id, command_type, user_id, client_order_id, assigned_order_id,
   engine_ts, tie_break, payload, fencing_token, checksum)
SELECT :s + 100000, 1, 'NEW', 1, 'S' || :ep, 6000 + :ep, 0, 0, '{}'::jsonb, l.epoch, 0
FROM leader_lease l
WHERE l.resource = 'matching-engine'
  AND l.holder = 'legacy' AND l.epoch = :ep - 1 AND l.lease_until > clock_timestamp()
FOR KEY SHARE OF l;
SELECT 'legacy settle prologue', assert_and_lock_leader('legacy', :ep) AS epoch,
       (SELECT count(*) FROM event_emission_registry WHERE source_cmd_seq = :s) AS registered,
       seq, market_id, fencing_token
FROM (VALUES (1)) AS one(x) LEFT JOIN command_journal ON seq = :s;
SELECT 'legacy snapshot lock', assert_and_lock_leader('legacy', :ep);
SELECT 'legacy stale lock (fencing text)', assert_and_lock_leader('legacy', :ep - 1);
UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second'
WHERE resource = 'matching-engine' AND holder = 'legacy' AND epoch = :ep;
SELECT 'lease after legacy release', holder, epoch, lease_until < clock_timestamp() AS expired
FROM leader_lease WHERE resource = 'matching-engine';
