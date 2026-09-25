#!/bin/sh
# 컨테이너 안(s5r-pg-probe.sh 가 부른다). S5 설계 검토 대응(11절)으로 고친 V54 와 인계 문장을 확인한다(설계 3-3 P6).
# 저장소의 실제 V0~V53 을 파일마다 한 트랜잭션으로 올리고 고친 V54 계약 SQL 을 올린 뒤 R0~R10 을 본다.
# 로그인: appu(app_role 소속 앱 로그인), hr(harness_role 소속 조회 로그인). 소유자는 postgres.
# 리스 행은 소유자가 직접 바꿔 흉내 낸다(holder N1·N2, epoch 7·8). 같은 노드의 작업자 스레드는 holder 가 같다.
V='\set VERBOSITY verbose
\set SHOW_CONTEXT never'
su() { psql -X -U postgres -d probe -At -v ON_ERROR_STOP=0 "$@" 2>&1; }
app() { psql -X -U appu -d probe -At -v ON_ERROR_STOP=0 "$@" 2>&1; }
hr() { psql -X -U hr -d probe -At -v ON_ERROR_STOP=0 "$@" 2>&1; }

# DB 설계 §5.4-d 의 문장. 시험 편의로 점유 건수만 :lim 으로 둔다(앱은 1).
CLAIM="WITH picked AS (SELECT r.id FROM order_cancel_requests r WHERE r.resource = :'res' AND (r.state = 'PENDING' OR (r.state = 'CLAIMED' AND r.claimed_epoch < :ep)) AND r.created_at > now() - interval '5 minutes' AND EXISTS (SELECT 1 FROM leader_lease l WHERE l.resource = :'res' AND l.holder = :'who' AND l.epoch = :ep AND l.lease_until > clock_timestamp()) ORDER BY r.id LIMIT :lim FOR UPDATE OF r SKIP LOCKED) UPDATE order_cancel_requests r SET state = 'CLAIMED', claimed_by = :'who', claimed_epoch = :ep, claimed_at = now() FROM picked WHERE r.id = picked.id RETURNING r.id, r.claimed_by, r.claimed_epoch"
FINISH="UPDATE order_cancel_requests o SET state = 'DONE', finished_at = now(), result = '{\"canceled\":[],\"failed\":[],\"counts\":{}}'::jsonb WHERE o.id = :id AND o.state = 'CLAIMED' AND o.claimed_by = :'who' AND o.claimed_epoch = :ep AND EXISTS (SELECT 1 FROM leader_lease l WHERE l.resource = o.resource AND l.holder = :'who' AND l.epoch = :ep AND l.lease_until > clock_timestamp()) RETURNING 'finish', o.id, o.state"
ABANDON="UPDATE order_cancel_requests r SET state = 'ABANDONED', finished_at = now() WHERE r.request_id = :'rq' AND r.state = 'PENDING' AND NOT EXISTS (SELECT 1 FROM order_cancel_requests s WHERE s.request_id = r.request_id AND s.resource = r.resource AND s.state IN ('CLAIMED', 'DONE')) RETURNING 'abandon', r.id, r.resource, r.state"
EXPIRE="WITH stale AS (SELECT id FROM order_cancel_requests WHERE state IN ('PENDING', 'CLAIMED') AND ((state = 'PENDING' AND created_at <= now() - interval '5 minutes') OR (state = 'CLAIMED' AND claimed_at <= now() - interval '5 minutes')) ORDER BY id LIMIT :lim FOR UPDATE SKIP LOCKED), upd AS (UPDATE order_cancel_requests r SET state = CASE r.state WHEN 'PENDING' THEN 'ABANDONED' ELSE 'EXPIRED' END, finished_at = now() FROM stale WHERE r.id = stale.id RETURNING r.id, r.state) SELECT 'expire', id, state FROM upd ORDER BY id"
STATES="SELECT id, request_id, resource, state, claimed_by, claimed_epoch FROM order_cancel_requests ORDER BY id"
lease() { su -q -c "UPDATE leader_lease SET holder = '$2', epoch = $3, lease_until = clock_timestamp() + $4 WHERE resource = '$1'"; su -c "SELECT 'lease', resource, holder, epoch, lease_until > clock_timestamp() AS valid FROM leader_lease WHERE resource = '$1'"; }
wipe() { su -q -c "DELETE FROM order_cancel_requests"; }
ins() { # 요청 id, 자원, 주문 수 하나짜리 행 개수
  n=1; while [ $n -le "$3" ]; do
    app -q -c "INSERT INTO order_cancel_requests (request_id, resource, user_id, order_ids, origin) VALUES ('$1', '$2', $U, ARRAY[gen_random_uuid()], 'cancel_all')"
    n=$((n + 1))
  done
}

# ── R0 적용 ──────────────────────────────────────────────────────────
for f in $(ls /c/migration/V*.sql | sed -E 's|.*/V([0-9]+)__.*|\1 &|' | sort -n | cut -d' ' -f2); do
  psql -X -U postgres -d probe -q -1 -v ON_ERROR_STOP=1 -f "$f" >/dev/null 2>/tmp/mig.err \
    || { echo "MIGRATION FAILED: $f"; cat /tmp/mig.err; exit 1; }
done
echo "== R0 applied repository migrations: $(ls /c/migration/V*.sql | wc -l) files"
psql -X -U postgres -d probe -q -1 -v ON_ERROR_STOP=1 -f /c/v54.sql 2>&1
echo "v54 exit=$?"
su -q <<'EOF'
CREATE ROLE appu LOGIN;
GRANT app_role TO appu;
CREATE ROLE hr LOGIN;
GRANT harness_role TO hr;
EOF
U=$(su -q -c "INSERT INTO users (email) VALUES ('p6@probe.local') RETURNING id")
echo "user id=$U"
su -c "SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'order_cancel_requests'::regclass AND contype = 'c' ORDER BY conname"
su -c "SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'order_cancel_requests' ORDER BY indexname"
su -c "SELECT grantee, string_agg(privilege_type, ',' ORDER BY privilege_type) FROM information_schema.role_table_grants WHERE table_name = 'order_cancel_requests' AND grantee IN ('app_role','harness_role') GROUP BY grantee ORDER BY grantee"

# ── R1 권한 ──────────────────────────────────────────────────────────
echo
echo "== R1 app 로그인은 넣고 리스 행을 읽는다. DELETE 와 조회 로그인의 쓰기는 거부"
ins 99999999-9999-4999-8999-999999999999 matching-p0 1
app -c "SELECT 'app reads lease', count(*) FROM leader_lease"
app -c "DELETE FROM order_cancel_requests"
hr -c "SELECT 'hr reads', count(*) FROM order_cancel_requests"
hr -c "INSERT INTO order_cancel_requests (request_id, resource, user_id, order_ids, origin) VALUES (gen_random_uuid(), 'matching-p0', $U, ARRAY[gen_random_uuid()], 'cancel_all')"
wipe

# ── R2 제약(만료 상태 포함) ────────────────────────────────────────────
echo
echo "== R2 제약: 점유 열 없는 EXPIRED, finished_at 없는 EXPIRED, 결과 있는 EXPIRED, 점유 열 있는 ABANDONED 는 거부. 점유 열과 finished_at 이 있는 EXPIRED 는 받음"
app <<EOF
$V
INSERT INTO order_cancel_requests (request_id, resource, user_id, order_ids, origin, state, finished_at) VALUES (gen_random_uuid(), 'matching-p0', $U, ARRAY[gen_random_uuid()], 'cancel_all', 'EXPIRED', now());
INSERT INTO order_cancel_requests (request_id, resource, user_id, order_ids, origin, state, claimed_by, claimed_epoch, claimed_at) VALUES (gen_random_uuid(), 'matching-p0', $U, ARRAY[gen_random_uuid()], 'cancel_all', 'EXPIRED', 'N1', 7, now());
INSERT INTO order_cancel_requests (request_id, resource, user_id, order_ids, origin, state, claimed_by, claimed_epoch, claimed_at, finished_at, result) VALUES (gen_random_uuid(), 'matching-p0', $U, ARRAY[gen_random_uuid()], 'cancel_all', 'EXPIRED', 'N1', 7, now(), now(), '{}'::jsonb);
INSERT INTO order_cancel_requests (request_id, resource, user_id, order_ids, origin, state, claimed_by, claimed_epoch, claimed_at, finished_at) VALUES (gen_random_uuid(), 'matching-p0', $U, ARRAY[gen_random_uuid()], 'cancel_all', 'ABANDONED', 'N1', 7, now(), now());
INSERT INTO order_cancel_requests (request_id, resource, user_id, order_ids, origin, state, claimed_by, claimed_epoch, claimed_at, finished_at) VALUES (gen_random_uuid(), 'matching-p0', $U, ARRAY[gen_random_uuid()], 'cancel_all', 'EXPIRED', 'N1', 7, now(), now()) RETURNING 'accepted', state;
EOF
wipe

# ── R3 같은 노드의 두 작업자 스레드(SKIP LOCKED) ───────────────────────
echo
echo "== R3 p1 리스를 N1·7 로 두고, N1·7 의 스레드 X 가 한 행을 쥔 채 멈춘 동안 같은 기간의 스레드 Y 가 나머지를 점유한다"
lease matching-p1 N1 7 "interval '1 hour'"
ins 11111111-1111-4111-8111-111111111111 matching-p1 3
( app > /tmp/x.out <<EOF
\set res matching-p1
\set ep 7
\set lim 1
\set who N1
BEGIN;
$CLAIM;
SELECT pg_sleep(2);
COMMIT;
EOF
) &
XPID=$!
sleep 0.7
app <<EOF
\set res matching-p1
\set ep 7
\set lim 5
\set who N1
\timing on
$CLAIM;
EOF
wait $XPID
echo "-- X"
cat /tmp/x.out
wipe

# ── R4 포기 규칙: 시작한 요청은 그 파티션의 PENDING 을 포기하지 않는다 ───────
echo
echo "== R4a 요청 A(p1 세 행)의 첫 행을 N1·7 이 점유해 커밋한 뒤 포기하면 0행이다(그 파티션 몫이 시작됨)"
ins aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa matching-p1 3
app <<EOF
\set res matching-p1
\set ep 7
\set lim 1
\set who N1
$CLAIM;
\set rq aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa
$ABANDON;
EOF
su -c "$STATES"
wipe
echo
echo "== R4b 요청 B 가 p0 한 행, p1 두 행이다. p1 한 행만 점유된 뒤 포기하면 p0 행만 ABANDONED 다"
lease matching-p0 N2 8 "interval '1 hour'"
ins bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb matching-p0 1
ins bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb matching-p1 2
app <<EOF
\set res matching-p1
\set ep 7
\set lim 1
\set who N1
$CLAIM;
\set rq bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb
$ABANDON;
EOF
su -c "$STATES"
wipe
echo
echo "== R4c 경합: X 가 요청 C(p1 세 행)의 첫 행을 점유하고 아직 커밋하지 않은 동안 요청자가 포기한다"
ins cccccccc-cccc-4ccc-8ccc-cccccccccccc matching-p1 3
( app > /tmp/x.out <<EOF
\set res matching-p1
\set ep 7
\set lim 1
\set who N1
BEGIN;
$CLAIM;
SELECT pg_sleep(2);
COMMIT;
EOF
) &
XPID=$!
sleep 0.7
app <<EOF
\timing on
\set rq cccccccc-cccc-4ccc-8ccc-cccccccccccc
$ABANDON;
EOF
wait $XPID
echo "-- X"
cat /tmp/x.out
su -c "$STATES"
echo "-- 포기된 행은 뒤에 점유되지 않는다"
app <<EOF
\set res matching-p1
\set ep 7
\set lim 5
\set who N1
$CLAIM;
EOF
wipe
echo
echo "== R4d 아무도 가져가지 않은 요청 D(p1 두 행)는 모두 포기된다"
ins dddddddd-dddd-4ddd-8ddd-dddddddddddd matching-p1 2
app <<EOF
\set rq dddddddd-dddd-4ddd-8ddd-dddddddddddd
$ABANDON;
EOF
wipe

# ── R5 리스 확인이 붙은 점유·끝내기와 재점유 ────────────────────────────
echo
echo "== R5 N1·7 이 p1 두 행을 점유한 뒤 리스가 N2·8 로 넘어간다(N1 은 아직 게이트를 연 좀비)"
ins eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee matching-p1 2
ins ffffffff-ffff-4fff-8fff-ffffffffffff matching-p1 1
app <<EOF
\set res matching-p1
\set ep 7
\set lim 2
\set who N1
$CLAIM;
EOF
lease matching-p1 N2 8 "interval '1 hour'"
ID1=$(su -q -c "SELECT min(id) FROM order_cancel_requests WHERE claimed_by = 'N1'")
echo "-- 좀비 N1·7 의 끝내기(점유자·epoch 는 맞음)는 리스 확인으로 0행, 새 PENDING 점유도 0행"
app <<EOF
\set id $ID1
\set ep 7
\set who N1
$FINISH;
\set res matching-p1
\set lim 5
$CLAIM;
EOF
echo "-- 새 소유자 N2·8 은 N1 의 두 행과 PENDING 한 행을 점유하고 끝낸다"
app <<EOF
\set res matching-p1
\set ep 8
\set lim 5
\set who N2
$CLAIM;
\set id $ID1
$FINISH;
EOF
echo "-- 리스가 만료되면(같은 holder·epoch) 점유와 끝내기가 모두 0행이다"
su -q -c "UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' WHERE resource = 'matching-p1'"
ins 12121212-1212-4121-8121-121212121212 matching-p1 1
ID2=$(su -q -c "SELECT max(id) FROM order_cancel_requests WHERE state = 'CLAIMED'")
app <<EOF
\set res matching-p1
\set ep 8
\set lim 5
\set who N2
$CLAIM;
\set id $ID2
$FINISH;
EOF
su -c "$STATES"
wipe

# ── R6 유효 시간 ───────────────────────────────────────────────────
echo
echo "== R6 넣은 지 6분 된 PENDING 행은 유효한 리스로도 점유되지 않는다"
lease matching-p0 N2 8 "interval '1 hour'"
su -c "INSERT INTO order_cancel_requests (request_id, resource, user_id, order_ids, origin, created_at) VALUES (gen_random_uuid(), 'matching-p0', $U, ARRAY[gen_random_uuid()], 'cancel_all', now() - interval '6 minutes') RETURNING id"
app <<EOF
\set res matching-p0
\set ep 8
\set lim 5
\set who N2
$CLAIM;
EOF
wipe

# ── R7 점유·끝내기 트랜잭션은 리스 행을 잠그지 않는다 ─────────────────────
echo
echo "== R7 N2·8 의 점유 트랜잭션이 열린 동안 리스 갱신(renew_lease)과 리스 행 FOR UPDATE NOWAIT 가 곧바로 된다"
lease matching-p1 N2 8 "interval '1 hour'"
ins 13131313-1313-4131-8131-131313131313 matching-p1 2
( app > /tmp/x.out <<EOF
\set res matching-p1
\set ep 8
\set lim 1
\set who N2
BEGIN;
$CLAIM;
SELECT pg_sleep(2);
COMMIT;
EOF
) &
XPID=$!
sleep 0.7
app <<EOF
\timing on
SELECT 'renew', renew_lease('matching-p1', 'N2', 8, interval '1 hour');
EOF
su <<EOF
\timing on
BEGIN;
SELECT 'lock', resource FROM leader_lease WHERE resource = 'matching-p1' FOR UPDATE NOWAIT;
ROLLBACK;
EOF
wait $XPID
echo "-- X"
cat /tmp/x.out
ID3=$(su -q -c "SELECT min(id) FROM order_cancel_requests WHERE state = 'CLAIMED'")
echo "-- 끝내기 트랜잭션이 열린 동안에도 같다"
( app > /tmp/x.out <<EOF
\set id $ID3
\set ep 8
\set who N2
BEGIN;
$FINISH;
SELECT pg_sleep(2);
COMMIT;
EOF
) &
XPID=$!
sleep 0.7
app <<EOF
\timing on
SELECT 'renew', renew_lease('matching-p1', 'N2', 8, interval '1 hour');
EOF
su <<EOF
\timing on
BEGIN;
SELECT 'lock', resource FROM leader_lease WHERE resource = 'matching-p1' FOR UPDATE NOWAIT;
ROLLBACK;
EOF
wait $XPID
echo "-- X"
cat /tmp/x.out
wipe

# ── R8 만료 ────────────────────────────────────────────────────────
echo
echo "== R8 만료: 6분 된 PENDING -> ABANDONED, 6분 전 점유한 CLAIMED -> EXPIRED. 새 PENDING, 1분 전 다시 점유한 옛 행, 끝난 행은 그대로"
su -q <<EOF
INSERT INTO order_cancel_requests (request_id, resource, user_id, order_ids, origin, created_at)
VALUES ('14141414-1414-4141-8141-141414141414', 'matching-p0', $U, ARRAY[gen_random_uuid()], 'cancel_all', now() - interval '6 minutes');
INSERT INTO order_cancel_requests (request_id, resource, user_id, order_ids, origin, state, created_at, claimed_by, claimed_epoch, claimed_at)
VALUES ('14141414-1414-4141-8141-141414141414', 'matching-p1', $U, ARRAY[gen_random_uuid()], 'cancel_all', 'CLAIMED', now() - interval '7 minutes', 'N1', 7, now() - interval '6 minutes');
INSERT INTO order_cancel_requests (request_id, resource, user_id, order_ids, origin)
VALUES ('15151515-1515-4151-8151-151515151515', 'matching-p0', $U, ARRAY[gen_random_uuid()], 'cancel_all');
INSERT INTO order_cancel_requests (request_id, resource, user_id, order_ids, origin, state, created_at, claimed_by, claimed_epoch, claimed_at)
VALUES ('15151515-1515-4151-8151-151515151515', 'matching-p1', $U, ARRAY[gen_random_uuid()], 'cancel_all', 'CLAIMED', now() - interval '6 minutes', 'N2', 8, now() - interval '1 minute');
INSERT INTO order_cancel_requests (request_id, resource, user_id, order_ids, origin, state, created_at, claimed_by, claimed_epoch, claimed_at, finished_at, result)
VALUES ('16161616-1616-4161-8161-161616161616', 'matching-engine', $U, ARRAY[gen_random_uuid()], 'cancel_all', 'DONE', now() - interval '1 day', 'N0', 3, now() - interval '1 day', now() - interval '1 day', '{}'::jsonb);
EOF
app <<EOF
\set lim 1000
$EXPIRE;
EOF
su -c "SELECT id, resource, state, claimed_by, finished_at IS NOT NULL AS finished FROM order_cancel_requests ORDER BY id"
wipe

# ── R9 오래된 열린 행이 쌓였을 때와 만료 뒤의 점유 질의 ──────────────────
echo
echo "== R9 하루 지난 PENDING 5만 행과 CLAIMED 5만 행(두 파티션 반씩), 새 PENDING 한 행. 만료 전·후 점유 질의 계획과 시간"
lease matching-p0 N2 8 "interval '1 hour'"
su -q -c "INSERT INTO order_cancel_requests (request_id, resource, user_id, order_ids, origin, created_at) SELECT gen_random_uuid(), CASE WHEN g % 2 = 0 THEN 'matching-p0' ELSE 'matching-p1' END, $U, ARRAY[gen_random_uuid()], 'cancel_all', now() - interval '1 day' FROM generate_series(1, 50000) g"
su -q -c "INSERT INTO order_cancel_requests (request_id, resource, user_id, order_ids, origin, state, created_at, claimed_by, claimed_epoch, claimed_at) SELECT gen_random_uuid(), CASE WHEN g % 2 = 0 THEN 'matching-p0' ELSE 'matching-p1' END, $U, ARRAY[gen_random_uuid()], 'cancel_all', 'CLAIMED', now() - interval '1 day', 'OLD', 1, now() - interval '1 day' FROM generate_series(1, 50000) g"
ins 17171717-1717-4171-8171-171717171717 matching-p0 1
su -q -c "ANALYZE order_cancel_requests"
echo "-- 만료 전: 새 행 하나가 있는 점유(ROLLBACK), 새 행이 없는 파티션의 poll"
su <<EOF
BEGIN;
EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF, SUMMARY ON) WITH picked AS (SELECT r.id FROM order_cancel_requests r WHERE r.resource = 'matching-p0' AND (r.state = 'PENDING' OR (r.state = 'CLAIMED' AND r.claimed_epoch < 8)) AND r.created_at > now() - interval '5 minutes' AND EXISTS (SELECT 1 FROM leader_lease l WHERE l.resource = 'matching-p0' AND l.holder = 'N2' AND l.epoch = 8 AND l.lease_until > clock_timestamp()) ORDER BY r.id LIMIT 1 FOR UPDATE OF r SKIP LOCKED) UPDATE order_cancel_requests r SET state = 'CLAIMED', claimed_by = 'N2', claimed_epoch = 8, claimed_at = now() FROM picked WHERE r.id = picked.id RETURNING r.id;
ROLLBACK;
EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF, SUMMARY ON) SELECT r.id FROM order_cancel_requests r WHERE r.resource = 'matching-p1' AND (r.state = 'PENDING' OR (r.state = 'CLAIMED' AND r.claimed_epoch < 8)) AND r.created_at > now() - interval '5 minutes' ORDER BY r.id LIMIT 1 FOR UPDATE OF r SKIP LOCKED;
EOF
echo "-- 만료: 한 문장 최대 1000행, 0행이 될 때까지(각 문장 따로 커밋)"
su -q <<'EOF'
CREATE PROCEDURE probe_expire_all(p_lim int) LANGUAGE plpgsql AS $$
DECLARE
    n int;
    total int := 0;
    batches int := 0;
    t0 timestamptz := clock_timestamp();
    t1 timestamptz;
    worst numeric := 0;
BEGIN
    LOOP
        t1 := clock_timestamp();
        WITH stale AS (
            SELECT id FROM order_cancel_requests
             WHERE state IN ('PENDING', 'CLAIMED')
               AND ((state = 'PENDING' AND created_at <= now() - interval '5 minutes')
                 OR (state = 'CLAIMED' AND claimed_at <= now() - interval '5 minutes'))
             ORDER BY id LIMIT p_lim FOR UPDATE SKIP LOCKED)
        UPDATE order_cancel_requests r
           SET state = CASE r.state WHEN 'PENDING' THEN 'ABANDONED' ELSE 'EXPIRED' END, finished_at = now()
          FROM stale WHERE r.id = stale.id;
        GET DIAGNOSTICS n = ROW_COUNT;
        worst := greatest(worst, extract(epoch FROM clock_timestamp() - t1) * 1000);
        total := total + n;
        batches := batches + 1;
        COMMIT;
        EXIT WHEN n < p_lim;
    END LOOP;
    RAISE NOTICE 'expired rows=% statements=% elapsed_ms=% slowest_statement_ms=%', total, batches,
        round(extract(epoch FROM clock_timestamp() - t0) * 1000), round(worst);
END $$;
EOF
su -c "CALL probe_expire_all(1000)"
su -c "SELECT state, count(*) FROM order_cancel_requests GROUP BY state ORDER BY state"
su -q -c "ANALYZE order_cancel_requests"
echo "-- 만료 뒤: 같은 두 질의"
su <<EOF
BEGIN;
EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF, SUMMARY ON) WITH picked AS (SELECT r.id FROM order_cancel_requests r WHERE r.resource = 'matching-p0' AND (r.state = 'PENDING' OR (r.state = 'CLAIMED' AND r.claimed_epoch < 8)) AND r.created_at > now() - interval '5 minutes' AND EXISTS (SELECT 1 FROM leader_lease l WHERE l.resource = 'matching-p0' AND l.holder = 'N2' AND l.epoch = 8 AND l.lease_until > clock_timestamp()) ORDER BY r.id LIMIT 1 FOR UPDATE OF r SKIP LOCKED) UPDATE order_cancel_requests r SET state = 'CLAIMED', claimed_by = 'N2', claimed_epoch = 8, claimed_at = now() FROM picked WHERE r.id = picked.id RETURNING r.id;
ROLLBACK;
EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF, SUMMARY ON) SELECT r.id FROM order_cancel_requests r WHERE r.resource = 'matching-p1' AND (r.state = 'PENDING' OR (r.state = 'CLAIMED' AND r.claimed_epoch < 8)) AND r.created_at > now() - interval '5 minutes' ORDER BY r.id LIMIT 1 FOR UPDATE OF r SKIP LOCKED;
EOF
echo "-- 만료 뒤 열린 행이 없을 때 만료 문장 한 번"
su <<EOF
EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF, SUMMARY ON) WITH stale AS (SELECT id FROM order_cancel_requests WHERE state IN ('PENDING', 'CLAIMED') AND ((state = 'PENDING' AND created_at <= now() - interval '5 minutes') OR (state = 'CLAIMED' AND claimed_at <= now() - interval '5 minutes')) ORDER BY id LIMIT 1000 FOR UPDATE SKIP LOCKED) UPDATE order_cancel_requests r SET state = CASE r.state WHEN 'PENDING' THEN 'ABANDONED' ELSE 'EXPIRED' END, finished_at = now() FROM stale WHERE r.id = stale.id;
EOF
wipe

# ── R10 외래키 잠금과 회원 상태 전이(P5-8 다시) ───────────────────────────
echo
echo "== R10 요청 행을 넣는 트랜잭션이 열린 동안 같은 회원의 상태 전이(폐쇄 UPDATE)는 기다리지 않는다"
( app > /tmp/i.out <<EOF
BEGIN;
INSERT INTO order_cancel_requests (request_id, resource, user_id, order_ids, origin) VALUES (gen_random_uuid(), 'matching-p0', $U, ARRAY[gen_random_uuid()], 'member_close');
SELECT pg_sleep(2);
COMMIT;
EOF
) &
IPID=$!
sleep 0.5
su <<EOF
\timing on
UPDATE users SET status = 'CLOSED' WHERE id = $U RETURNING status;
EOF
wait $IPID
cat /tmp/i.out
