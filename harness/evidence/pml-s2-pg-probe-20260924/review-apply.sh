#!/bin/sh
# 컨테이너 안(review-check.sh 가 부른다). V0~V47 을 파일마다 한 트랜잭션으로 올리고 계약 시드와 계약 V48 을 올린 뒤
# 검토 지적을 절마다 확인한다. R 번호는 S2 설계 10절의 검토 번호와 같다.
#   rdr  : app_role 이 아닌 조회 로그인. 배포와 같이 assets·markets·trades SELECT 와 기본 읽기 전용만 준다.
#   appu : app_role 에 속한 앱 로그인.  botu : app_role 에 속한 봇 로그인(배포는 앱·봇을 같은 app_role 에 넣는다).
SU="psql -X -U postgres -At -v ON_ERROR_STOP=0"
HDR='\set VERBOSITY verbose
\set SHOW_CONTEXT never'
as_role() { psql -X -U "$1" -d "$2" -At -v ON_ERROR_STOP=0 2>&1; }

for f in $(ls /review/migration/V*.sql | sed -E 's|.*/V([0-9]+)__.*|\1 &|' | sort -n | cut -d' ' -f2); do
  psql -X -U postgres -d contract -q -1 -v ON_ERROR_STOP=1 -f "$f" >/dev/null 2>/tmp/mig.err \
    || { echo "MIGRATION FAILED: $f"; cat /tmp/mig.err; exit 1; }
done
echo "applied V0..V47: $(ls /review/migration/V*.sql | wc -l) files"
psql -X -U postgres -d contract -q -1 -v ON_ERROR_STOP=1 -f /review/contract-seed.sql >/dev/null || { echo "SEED FAILED"; exit 1; }
# R6 의 기준선으로 V48 을 올리기 전 상태(V47 + 시드)를 따로 복제해 둔다.
$SU -d postgres -c "CREATE DATABASE v47 TEMPLATE contract" >/dev/null
psql -X -U postgres -d contract -q -1 -v ON_ERROR_STOP=1 -f /review/v48.sql >/dev/null || { echo "V48 FAILED"; exit 1; }
echo "applied contract seed + contract V48 (V47 baseline copied to database v47)"
$SU -d contract -q <<'EOF'
CREATE ROLE rdr LOGIN;
GRANT USAGE ON SCHEMA public TO rdr;
GRANT SELECT ON assets, markets, trades TO rdr;
ALTER ROLE rdr SET default_transaction_read_only = on;
CREATE ROLE appu LOGIN;
CREATE ROLE botu LOGIN;
GRANT app_role TO appu, botu;
EOF
# 시나리오마다 같은 출발점(V48 직후, epoch 41)에서 새 DB 를 복제한다.
$SU -d postgres -c "CREATE DATABASE tmpl TEMPLATE contract" >/dev/null
newdb() { $SU -d postgres -c "CREATE DATABASE $1 TEMPLATE tmpl" >/dev/null; }

echo
echo "== R1a [검토 1] 소유자 권한 함수의 실행 권한(계약 V48 뒤). 열: 함수, 종류, rdr, app_role"
$SU -d contract <<'EOF'
SELECT 'secdef', p.oid::regprocedure::text,
       CASE WHEN p.prorettype = 'trigger'::regtype THEN 'trigger' ELSE 'call' END,
       has_function_privilege('rdr', p.oid, 'EXECUTE'), has_function_privilege('app_role', p.oid, 'EXECUTE')
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = 'public' AND p.prosecdef ORDER BY 2;
SELECT 'rdr TEMP on database', has_database_privilege('rdr', 'contract', 'TEMP');
EOF

echo
echo "== R1b [검토 1] 수정 전 권한 재현: seed_market_lease_partition 에 PUBLIC EXECUTE 를 잠시 되돌린다"
$SU -d contract <<'EOF'
GRANT EXECUTE ON FUNCTION seed_market_lease_partition() TO PUBLIC;
SET session_replication_role = replica;
INSERT INTO markets (id, symbol, base_asset_id, quote_asset_id, tick_size, lot_size, min_notional, max_notional, max_qty)
VALUES (4, 'M4', 5, 1, 1, 0.0001, 0, 1000000000, 1000000);
SET session_replication_role = origin;
SELECT 'market 4 mapped before', count(*) FROM market_lease_partition WHERE market_id = 4;
EOF
as_role rdr contract <<EOF
$HDR
SELECT 'rdr INSERT on mapping', has_table_privilege('market_lease_partition', 'INSERT');
SET default_transaction_read_only = off;
CREATE TEMP TABLE t (id smallint);
CREATE TRIGGER hijack AFTER INSERT ON t FOR EACH ROW EXECUTE FUNCTION public.seed_market_lease_partition();
INSERT INTO t VALUES (4);
EOF
$SU -d contract -c "SELECT 'market 4 mapped after', market_id, resource, mapped_by FROM market_lease_partition WHERE market_id = 4"

echo
echo "== R1c [검토 1] 수정 뒤(계약대로 PUBLIC 회수): 임시 표 트리거는 42501, app_role 의 마켓 생성은 대응을 만든다"
$SU -d contract <<'EOF'
REVOKE EXECUTE ON FUNCTION seed_market_lease_partition() FROM PUBLIC;
SET session_replication_role = replica;
INSERT INTO markets (id, symbol, base_asset_id, quote_asset_id, tick_size, lot_size, min_notional, max_notional, max_qty)
VALUES (5, 'M5', 5, 1, 1, 0.0001, 0, 1000000000, 1000000);
SET session_replication_role = origin;
EOF
as_role rdr contract <<EOF
$HDR
SET default_transaction_read_only = off;
CREATE TEMP TABLE t (id smallint);
CREATE TRIGGER hijack AFTER INSERT ON t FOR EACH ROW EXECUTE FUNCTION public.seed_market_lease_partition();
EOF
as_role botu contract <<EOF
$HDR
CREATE TEMP TABLE t (id smallint);
CREATE TRIGGER hijack AFTER INSERT ON t FOR EACH ROW EXECUTE FUNCTION public.seed_market_lease_partition();
EOF
as_role appu contract <<EOF
$HDR
INSERT INTO markets (id, symbol, base_asset_id, quote_asset_id, tick_size, lot_size, min_notional, max_notional, max_qty)
VALUES (6, 'M6', 5, 1, 1, 0.0001, 0, 1000000000, 1000000);
EOF
$SU -d contract -c "SELECT 'markets 5,6 mapping', coalesce(string_agg(market_id || ':' || resource || ':' || mapped_by, ',' ORDER BY market_id), '(none)') FROM market_lease_partition WHERE market_id IN (5, 6)"

echo
echo "== R1d [검토 1 부수, S2 범위 밖] 기존 소유자 권한 트리거 함수(V1 register_event_emission)의 같은 경로: rdr, botu"
as_role rdr contract <<EOF
$HDR
SET default_transaction_read_only = off;
CREATE TEMP TABLE e (source_cmd_seq bigint, event_ordinal int, event_seq bigint);
CREATE TRIGGER plant AFTER INSERT ON e FOR EACH ROW EXECUTE FUNCTION public.register_event_emission();
INSERT INTO e VALUES (987654321, 0, 987654321);
EOF
as_role botu contract <<EOF
$HDR
SELECT 'botu INSERT on registry', has_table_privilege('event_emission_registry', 'INSERT');
CREATE TEMP TABLE e (source_cmd_seq bigint, event_ordinal int, event_seq bigint);
CREATE TRIGGER plant AFTER INSERT ON e FOR EACH ROW EXECUTE FUNCTION public.register_event_emission();
INSERT INTO e VALUES (987654322, 0, 987654322);
EOF
$SU -d contract -c "SELECT 'planted registry rows', source_cmd_seq, event_ordinal FROM event_emission_registry WHERE source_cmd_seq >= 987654321 ORDER BY 2"

echo
echo "== R2a [검토 2] 명령 저널 잎의 app_role INSERT 권한(잎은 부모 권한을 물려받지 않는다)"
$SU -d contract -c "SELECT 'leaf', c.relname, has_table_privilege('app_role', c.oid, 'INSERT') FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid WHERE i.inhparent = 'command_journal'::regclass ORDER BY 2"

echo
echo "== R2b [검토 2] V48 뒤 V49 앞: app_role 로그인의 command_journal 직접 INSERT(롤백)"
as_role botu contract <<EOF
$HDR
BEGIN;
INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id, assigned_order_id,
                             engine_ts, tie_break, payload, fencing_token, checksum)
VALUES (999999, 1, 'NEW', 1, 'direct-before', 1, 0, 0, '{}'::jsonb, 7, 0);
ROLLBACK;
EOF
psql -X -U postgres -d contract -q -1 -v ON_ERROR_STOP=1 -f /review/v49.sql >/dev/null && echo "applied contract V49"

echo
echo "== R2c [검토 2] V49 뒤: 명령 저널 직접 INSERT 는 42501. 봇 로그인에 남는 경로"
$SU -d contract -c "SELECT 'app_role INSERT after V49', t, has_table_privilege('app_role', t, 'INSERT') FROM unnest(ARRAY['command_journal', 'event_journal', 'snapshots', 'orders', 'trades']) AS t"
$SU -d contract -c "SELECT 'lease before', holder, epoch, lease_until > clock_timestamp() AS live FROM leader_lease WHERE resource = 'matching-engine'"
as_role botu contract <<EOF
$HDR
INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id, assigned_order_id,
                             engine_ts, tie_break, payload, fencing_token, checksum)
VALUES (999998, 1, 'NEW', 1, 'direct-after', 2, 0, 0, '{}'::jsonb, 7, 0);
BEGIN;
INSERT INTO snapshots (market_id, last_applied_seq, state, state_checksum, engine_version, config_hash, format_version, fencing_epoch)
VALUES (1, 0, '\x00', 0, 'bot', 'bot', 1, 7);
SELECT 'snapshots direct insert (rolled back)', count(*) FROM snapshots WHERE engine_version = 'bot';
ROLLBACK;
SELECT holder AS h, epoch AS e FROM leader_lease WHERE resource = 'matching-engine' \gset
SELECT 'append as current leader', append_command_journal_row(999997, 1::smallint, 'NEW', 1, 'bot-as-leader', 3, 0, 0, '{}'::jsonb, 0, :'h', :e);
SELECT 'renew current holder 1000y', renew_lease('matching-engine', :'h', :e, interval '1000 years');
SELECT 'publish over live lease', publish_leader_epoch('bot-login', 1000);
EOF
$SU -d contract <<'EOF'
SELECT 'lease after publish', holder, epoch, lease_until > clock_timestamp() + interval '50 years' AS pushed_100y FROM leader_lease WHERE resource = 'matching-engine';
SELECT 'pg store takeover blocked', try_acquire_leader('standby', interval '10 seconds') IS NULL;
EOF
as_role botu contract <<EOF
$HDR
SELECT 'bot expires lease', expire_leader_lease_now();
SELECT 'bot acquires for 100y', try_acquire_lease('matching-engine', 'bot-login', interval '100 years');
EOF
$SU -d contract <<'EOF'
SELECT 'journal rows', seq, client_order_id, fencing_token FROM command_journal WHERE seq IN (999997, 999998, 999999) ORDER BY seq;
SELECT 'acquisitions (audit)', epoch, resource, holder, origin FROM lease_acquisitions ORDER BY epoch;
EOF

echo
echo "== R4 [검토 4] 파티션 자원을 획득한 DB 를 P1 로 되돌리면 matching-engine 다음 epoch 가 뛴다"
newdb s4
$SU -d s4 <<'EOF'
SELECT expire_leader_lease_now();
SELECT 'P1 acquire', try_acquire_leader('a', interval '60 seconds');
UPDATE leader_protocol SET protocol = 3;
SELECT 'P3 p0', try_acquire_lease('matching-p0', 'x0', interval '60 seconds');
SELECT 'P3 p1', try_acquire_lease('matching-p1', 'x1', interval '60 seconds');
UPDATE leader_protocol SET protocol = 1;
SELECT expire_leader_lease_now();
SELECT 'P1 acquire after P3', try_acquire_leader('b', interval '60 seconds');
EOF

echo
echo "== R6 [검토 6·3] S2 이전 SQL 을 app_role 로그인으로: V47 기준선, V48 DB, V49 DB, 되돌리기 GRANT 뒤"
echo "-- (0) V47 DB(기준선). 기대 epoch 42"
$SU -d v47 -c "SELECT expire_leader_lease_now()" >/dev/null
( echo '\set ep 42'; cat /review/review-legacy.sql ) | as_role appu v47
newdb s6
echo "-- (a) V48 만 적용한 DB. 기대 epoch 42"
$SU -d s6 -c "SELECT expire_leader_lease_now()" >/dev/null
( echo '\set ep 42'; cat /review/review-legacy.sql ) | as_role appu s6
psql -X -U postgres -d s6 -q -1 -v ON_ERROR_STOP=1 -f /review/v49.sql >/dev/null && echo "-- (b) 계약 V49 적용. 기대 epoch 43"
( echo '\set ep 43'; cat /review/review-legacy.sql ) | as_role appu s6
echo "-- (c) 되돌리기 GRANT 두 줄 뒤. 기대 epoch 44"
$SU -d s6 <<'EOF'
GRANT UPDATE ON leader_lease TO app_role;
GRANT INSERT ON command_journal TO app_role;
SELECT expire_leader_lease_now();
EOF
( echo '\set ep 44'; cat /review/review-legacy.sql ) | as_role appu s6
$SU -d s6 -c "SELECT 'legacy journal rows', seq, client_order_id, fencing_token FROM command_journal ORDER BY seq"

echo
echo "== R7 [검토 7] 동시 배정. 앞 획득이 커밋 전 프로토콜 행을 쥐면 뒤 획득은 기다렸다가 롤백이면 같은 번호, 커밋이면 +1"
newdb s7
$SU -d s7 -c "UPDATE leader_protocol SET protocol = 3" >/dev/null
$SU -d s7 -c "SELECT 'max epoch before', GREATEST((SELECT max(epoch) FROM leader_lease), COALESCE((SELECT max(epoch) FROM lease_acquisitions), 0))"
$SU -d s7 > /tmp/r7a.log 2>&1 <<'EOF' &
BEGIN;
SELECT 'A p0 (uncommitted)', try_acquire_lease('matching-p0', 'a', interval '60 seconds'), to_char(clock_timestamp(), 'HH24:MI:SS.MS');
SELECT pg_sleep(3);
ROLLBACK;
SELECT 'A rolled back', to_char(clock_timestamp(), 'HH24:MI:SS.MS');
EOF
sleep 0.8
observe() {
  # $1 이름표, $2 보유자. 기다리는 획득 세션의 대기 사유와 튜플 잠금을 잡은 표를 본다.
  $SU -d s7 > "/tmp/r7obs_$1.log" 2>&1 <<EOF &
SELECT pg_sleep(0.6);
SELECT '$1 waits', a.wait_event_type, a.wait_event,
       (SELECT string_agg(c.relname, ',') FROM pg_locks l JOIN pg_class c ON c.oid = l.relation
         WHERE l.pid = a.pid AND l.locktype = 'tuple') AS tuple_lock_on,
       cardinality(pg_blocking_pids(a.pid)) AS blockers
FROM pg_stat_activity a
WHERE a.pid <> pg_backend_pid() AND a.query LIKE '%''matching-p1'', ''$2''%';
EOF
}
observe B b
$SU -d s7 <<'EOF'
SELECT 'B start', to_char(clock_timestamp(), 'HH24:MI:SS.MS');
SELECT 'B p1', try_acquire_lease('matching-p1', 'b', interval '60 seconds'), to_char(clock_timestamp(), 'HH24:MI:SS.MS');
EOF
wait; cat /tmp/r7a.log; cat /tmp/r7obs_B.log
$SU -d s7 -c "UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' WHERE resource = 'matching-p1'" >/dev/null
$SU -d s7 > /tmp/r7b.log 2>&1 <<'EOF' &
BEGIN;
SELECT 'C p0 (uncommitted)', try_acquire_lease('matching-p0', 'c', interval '60 seconds'), to_char(clock_timestamp(), 'HH24:MI:SS.MS');
SELECT pg_sleep(3);
COMMIT;
SELECT 'C committed', to_char(clock_timestamp(), 'HH24:MI:SS.MS');
EOF
sleep 0.8
observe D d
$SU -d s7 <<'EOF'
SELECT 'D start', to_char(clock_timestamp(), 'HH24:MI:SS.MS');
SELECT 'D p1', try_acquire_lease('matching-p1', 'd', interval '60 seconds'), to_char(clock_timestamp(), 'HH24:MI:SS.MS');
EOF
wait; cat /tmp/r7b.log; cat /tmp/r7obs_D.log
$SU -d s7 -c "SELECT 'acquisitions', epoch, resource, holder FROM lease_acquisitions ORDER BY epoch"
echo
echo "== done"
