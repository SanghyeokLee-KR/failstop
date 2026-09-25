#!/bin/sh
# 컨테이너 안(s5-pg-probe.sh 가 부른다). 저장소의 실제 V0~V53 을 파일마다 한 트랜잭션으로 올리고 V54 계약 SQL 을 올린 뒤
# ADR 0034 S5 설계 3절 P5 를 확인한다. 로그인: appu(app_role 소속 앱 로그인), hr(harness_role 소속 조회 로그인). 소유자는 postgres.
V='\set VERBOSITY verbose
\set SHOW_CONTEXT never'
su() { psql -X -U postgres -d probe -At -v ON_ERROR_STOP=0 "$@" 2>&1; }
app() { psql -X -U appu -d probe -At -v ON_ERROR_STOP=0 "$@" 2>&1; }
hr() { psql -X -U hr -d probe -At -v ON_ERROR_STOP=0 "$@" 2>&1; }
CLAIM="WITH picked AS (SELECT id FROM order_cancel_requests WHERE resource = :'res' AND (state = 'PENDING' OR (state = 'CLAIMED' AND claimed_epoch < :ep)) AND created_at > now() - interval '5 minutes' ORDER BY id LIMIT :lim FOR UPDATE SKIP LOCKED) UPDATE order_cancel_requests r SET state = 'CLAIMED', claimed_by = :'who', claimed_epoch = :ep, claimed_at = now() FROM picked WHERE r.id = picked.id RETURNING r.id, r.claimed_by, r.claimed_epoch"

# ── P5-0 적용 ────────────────────────────────────────────────────────
for f in $(ls /c/migration/V*.sql | sed -E 's|.*/V([0-9]+)__.*|\1 &|' | sort -n | cut -d' ' -f2); do
  psql -X -U postgres -d probe -q -1 -v ON_ERROR_STOP=1 -f "$f" >/dev/null 2>/tmp/mig.err \
    || { echo "MIGRATION FAILED: $f"; cat /tmp/mig.err; exit 1; }
done
echo "== P5-0 applied repository migrations: $(ls /c/migration/V*.sql | wc -l) files"
psql -X -U postgres -d probe -q -1 -v ON_ERROR_STOP=1 -f /c/v54.sql 2>&1
echo "v54 exit=$?"
su -q <<'EOF'
CREATE ROLE appu LOGIN;
GRANT app_role TO appu;
CREATE ROLE hr LOGIN;
GRANT harness_role TO hr;
EOF
U=$(su -q -c "INSERT INTO users (email) VALUES ('p5@probe.local') RETURNING id")
echo "user id=$U"
su -c "SELECT column_name, data_type, is_nullable FROM information_schema.columns WHERE table_name = 'order_cancel_requests' ORDER BY ordinal_position"
su -c "SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'order_cancel_requests' ORDER BY indexname"
su -c "SELECT grantee, string_agg(privilege_type, ',' ORDER BY privilege_type) FROM information_schema.role_table_grants WHERE table_name = 'order_cancel_requests' AND grantee IN ('app_role','harness_role') GROUP BY grantee ORDER BY grantee"

# ── P5-1 앱 로그인의 넣기·점유·끝내기, 권한 밖 동작 ─────────────────
echo
echo "== P5-1 app 로그인: 넣기(식별자 열), 읽기, 점유, 끝내기. DELETE 와 조회 로그인의 쓰기는 거부"
app <<EOF
$V
INSERT INTO order_cancel_requests (request_id, resource, user_id, order_ids, origin)
VALUES ('11111111-1111-4111-8111-111111111111', 'matching-p1', $U, ARRAY['a0000000-0000-4000-8000-000000000001','a0000000-0000-4000-8000-000000000002']::uuid[], 'cancel_all'),
       ('11111111-1111-4111-8111-111111111111', 'matching-p1', $U, ARRAY['a0000000-0000-4000-8000-000000000003']::uuid[], 'cancel_all'),
       ('11111111-1111-4111-8111-111111111111', 'matching-p1', $U, ARRAY['a0000000-0000-4000-8000-000000000004']::uuid[], 'cancel_all'),
       ('11111111-1111-4111-8111-111111111111', 'matching-p1', $U, ARRAY['a0000000-0000-4000-8000-000000000005']::uuid[], 'cancel_all'),
       ('22222222-2222-4222-8222-222222222222', 'matching-p0', $U, ARRAY['b0000000-0000-4000-8000-000000000001']::uuid[], 'member_close')
RETURNING id, resource, state;
DELETE FROM order_cancel_requests WHERE id = 5;
EOF
hr -c "SELECT 'hr reads', count(*) FROM order_cancel_requests"
hr -c "INSERT INTO order_cancel_requests (request_id, resource, user_id, order_ids, origin) VALUES (gen_random_uuid(), 'matching-p0', $U, ARRAY[gen_random_uuid()], 'cancel_all')"

# ── P5-2 제약 ────────────────────────────────────────────────────────
echo
echo "== P5-2 제약: 목록 밖 자원, 빈 목록, NULL 원소, 501 개, 없는 회원, 결과 없는 DONE, 점유 없는 CLAIMED"
app <<EOF
$V
INSERT INTO order_cancel_requests (request_id, resource, user_id, order_ids, origin) VALUES (gen_random_uuid(), 'matching-p9', $U, ARRAY[gen_random_uuid()], 'cancel_all');
INSERT INTO order_cancel_requests (request_id, resource, user_id, order_ids, origin) VALUES (gen_random_uuid(), 'matching-p0', $U, ARRAY[]::uuid[], 'cancel_all');
INSERT INTO order_cancel_requests (request_id, resource, user_id, order_ids, origin) VALUES (gen_random_uuid(), 'matching-p0', $U, ARRAY[gen_random_uuid(), NULL], 'cancel_all');
INSERT INTO order_cancel_requests (request_id, resource, user_id, order_ids, origin) SELECT gen_random_uuid(), 'matching-p0', $U, array_agg(gen_random_uuid()), 'cancel_all' FROM generate_series(1, 501);
INSERT INTO order_cancel_requests (request_id, resource, user_id, order_ids, origin) VALUES (gen_random_uuid(), 'matching-p0', 999999, ARRAY[gen_random_uuid()], 'cancel_all');
UPDATE order_cancel_requests SET state = 'DONE', finished_at = now() WHERE id = 5;
UPDATE order_cancel_requests SET state = 'CLAIMED' WHERE id = 5;
EOF

# ── P5-3 두 작업자의 점유는 겹치지 않는다(SKIP LOCKED) ───────────────
echo
echo "== P5-3 X 가 트랜잭션 안에서 p1 한 행을 점유한 채 멈춘 동안 Y 가 p1 을 점유한다"
( app > /tmp/x.out <<EOF
\set res matching-p1
\set ep 7
\set lim 1
\set who X
BEGIN;
$CLAIM;
SELECT pg_sleep(3);
COMMIT;
EOF
) &
XPID=$!
sleep 0.7
app <<EOF
\set res matching-p1
\set ep 7
\set lim 2
\set who Y
\timing on
$CLAIM;
EOF

# ── P5-4 포기와 점유의 경합 ─────────────────────────────────────────
echo
echo "== P5-4 X 가 아직 커밋하지 않은 동안 요청자 Z 가 요청 11111111 의 PENDING 행을 포기한다(X 의 행은 기다린 뒤 건너뛴다)"
app <<EOF
\timing on
UPDATE order_cancel_requests SET state = 'ABANDONED', finished_at = now()
 WHERE request_id = '11111111-1111-4111-8111-111111111111' AND state = 'PENDING' RETURNING id, state;
EOF
wait $XPID
echo "-- X"
cat /tmp/x.out
su -c "SELECT id, state, claimed_by, claimed_epoch FROM order_cancel_requests ORDER BY id"

# ── P5-5 더 큰 epoch 의 재점유와 같은 기간의 비재점유, 끝내기 조건 ───────
echo
echo "== P5-5 epoch 7 의 점유(행 1)를 epoch 7 작업자는 다시 잡지 않고 epoch 8 작업자는 잡는다. 끝내기는 점유자·epoch 가 맞을 때만"
app <<EOF
\set res matching-p1
\set lim 10
\set ep 7
\set who X2
$CLAIM;
\set ep 8
\set who W8
$CLAIM;
UPDATE order_cancel_requests SET state = 'DONE', finished_at = now(), result = '{"canceled":[],"failed":[]}'::jsonb
 WHERE id = 1 AND state = 'CLAIMED' AND claimed_by = 'X' AND claimed_epoch = 7 RETURNING 'stale-complete', id;
UPDATE order_cancel_requests SET state = 'DONE', finished_at = now(), result = '{"canceled":["a0000000-0000-4000-8000-000000000001"],"failed":[]}'::jsonb
 WHERE id = 1 AND state = 'CLAIMED' AND claimed_by = 'W8' AND claimed_epoch = 8 RETURNING 'complete', id, state;
EOF

# ── P5-6 오래된 행은 점유하지 않는다 ─────────────────────────────────
echo
echo "== P5-6 created_at 이 6분 전인 PENDING 행은 점유되지 않는다"
su -c "INSERT INTO order_cancel_requests (request_id, resource, user_id, order_ids, origin, created_at) VALUES ('33333333-3333-4333-8333-333333333333', 'matching-p0', $U, ARRAY[gen_random_uuid()], 'cancel_all', now() - interval '6 minutes') RETURNING id"
app <<EOF
\set res matching-p0
\set ep 9
\set lim 10
\set who P0
$CLAIM;
EOF

# ── P5-7 점유 경로의 색인 ───────────────────────────────────────────
echo
echo "== P5-7 끝난 행 2만 개를 더한 뒤 점유 질의 계획(ANALYZE 뒤)"
su -q -c "INSERT INTO order_cancel_requests (request_id, resource, user_id, order_ids, origin, state, finished_at) SELECT gen_random_uuid(), CASE WHEN g % 2 = 0 THEN 'matching-p0' ELSE 'matching-p1' END, $U, ARRAY[gen_random_uuid()], 'cancel_all', 'ABANDONED', now() FROM generate_series(1, 20000) g"
su -q -c "ANALYZE order_cancel_requests"
su <<EOF
EXPLAIN (COSTS OFF) SELECT id FROM order_cancel_requests WHERE resource = 'matching-p1' AND (state = 'PENDING' OR (state = 'CLAIMED' AND claimed_epoch < 10)) AND created_at > now() - interval '5 minutes' ORDER BY id LIMIT 10 FOR UPDATE SKIP LOCKED;
EXPLAIN (COSTS OFF) SELECT id, resource, state, order_ids, result FROM order_cancel_requests WHERE request_id = '11111111-1111-4111-8111-111111111111' ORDER BY id;
EOF

# ── P5-8 외래키 잠금과 회원 상태 전이 ────────────────────────────────
echo
echo "== P5-8 요청 행을 넣는 트랜잭션이 열린 동안 같은 회원의 상태 전이(폐쇄 UPDATE)는 기다리지 않는다"
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
