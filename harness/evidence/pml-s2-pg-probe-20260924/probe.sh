#!/bin/sh
# ADR 0034 S2 설계의 PostgreSQL 동작 확인. 폐기용 컨테이너 안에서 돈다(run-host.sh 가 부른다).
# 시나리오마다 schema.sql 로 스키마를 새로 만든다. 운영·카오스 DB 에는 쓰지 않는다.
P="psql -X -U postgres -d probe -At -v ON_ERROR_STOP=0"
OUT=/probe/out
mkdir -p $OUT

q() { $P -c "$1" 2>&1; }
reset() { $P -q -f /probe/schema.sql >/dev/null 2>&1 || echo "schema load failed"; }
now() { q "SELECT to_char(clock_timestamp(),'HH24:MI:SS.MS')"; }
row() { q "SELECT 'row', resource, holder, epoch, lease_until > clock_timestamp() AS alive FROM leader_lease ORDER BY resource"; }
rl() { q "SELECT 'rowlocks', l.resource, r.multi, r.modes FROM pgrowlocks('leader_lease') r JOIN leader_lease l ON l.ctid = r.locked_row ORDER BY 2"; }
seqv() { q "SELECT 'probe_seq', last_value, is_called FROM probe_seq"; }

# 이름표를 단 세션 하나를 연다. $1 이름, $2 출력 파일, 표준입력 SQL.
sess() { $P > "$2" 2>&1; }

APPEND_OK="INSERT INTO command_journal (seq, market_id, payload, fencing_token)
SELECT 101, v.market_id, 'x', l.epoch
FROM (VALUES (1::smallint)) AS v(market_id)
JOIN leader_lease l ON l.resource = lease_resource_for_market(v.market_id)
WHERE l.holder = 'node-a' AND l.epoch = 5 AND l.lease_until > clock_timestamp()
FOR KEY SHARE OF l"

# ── Q1 INSERT…SELECT 의 FOR KEY SHARE OF l 과 파생 표 ────────────────────
q1() {
  echo "== Q1a VALUES 파생 표 + FOR KEY SHARE OF l"
  reset
  $P 2>&1 <<EOF
BEGIN;
$APPEND_OK;
SELECT 'Q1a inserted', seq, market_id, fencing_token FROM command_journal;
SELECT 'Q1a rowlocks', l.resource, r.modes FROM pgrowlocks('leader_lease') r JOIN leader_lease l ON l.ctid = r.locked_row;
EXPLAIN (COSTS OFF) INSERT INTO command_journal (seq, market_id, payload, fencing_token)
SELECT 102, v.market_id, 'x', l.epoch
FROM (VALUES (1::smallint)) AS v(market_id)
JOIN leader_lease l ON l.resource = lease_resource_for_market(v.market_id)
WHERE l.holder = 'node-a' AND l.epoch = 5 AND l.lease_until > clock_timestamp()
FOR KEY SHARE OF l;
ROLLBACK;
EOF
  echo "== Q1b SELECT 부분질의 파생 표 + FOR KEY SHARE OF l"
  $P 2>&1 <<EOF
BEGIN;
INSERT INTO command_journal (seq, market_id, payload, fencing_token)
SELECT 103, v.market_id, 'x', l.epoch
FROM (SELECT 1::smallint AS market_id) AS v
JOIN leader_lease l ON l.resource = lease_resource_for_market(v.market_id)
WHERE l.holder = 'node-a' AND l.epoch = 5 AND l.lease_until > clock_timestamp()
FOR KEY SHARE OF l;
SELECT 'Q1b rowlocks', l.resource, r.modes FROM pgrowlocks('leader_lease') r JOIN leader_lease l ON l.ctid = r.locked_row;
ROLLBACK;
EOF
  echo "== Q1c OF 없이 FOR KEY SHARE: 한 행 VALUES"
  $P 2>&1 <<EOF
BEGIN;
INSERT INTO command_journal (seq, market_id, payload, fencing_token)
SELECT 104, v.market_id, 'x', l.epoch FROM (VALUES (1::smallint)) AS v(market_id)
JOIN leader_lease l ON l.resource = lease_resource_for_market(v.market_id)
WHERE l.holder = 'node-a' AND l.epoch = 5 FOR KEY SHARE;
ROLLBACK;
EOF
  echo "== Q1c2 OF 없이 FOR KEY SHARE: 두 행 VALUES"
  $P 2>&1 <<EOF
BEGIN;
INSERT INTO command_journal (seq, market_id, payload, fencing_token)
SELECT 110 + v.market_id, v.market_id, 'x', l.epoch FROM (VALUES (1::smallint), (2::smallint)) AS v(market_id)
JOIN leader_lease l ON l.resource = lease_resource_for_market(v.market_id)
WHERE l.holder = 'node-a' AND l.epoch = 5 FOR KEY SHARE;
ROLLBACK;
EOF
  echo "== Q1d 판정 불일치면 0행이고 잠금도 남지 않는다"
  $P 2>&1 <<EOF
BEGIN;
INSERT INTO command_journal (seq, market_id, payload, fencing_token)
SELECT 105, v.market_id, 'x', l.epoch
FROM (VALUES (1::smallint)) AS v(market_id)
JOIN leader_lease l ON l.resource = lease_resource_for_market(v.market_id)
WHERE l.holder = 'node-a' AND l.epoch = 4 AND l.lease_until > clock_timestamp()
FOR KEY SHARE OF l;
SELECT 'Q1d rowlocks count', count(*) FROM pgrowlocks('leader_lease');
ROLLBACK;
EOF

  echo "== Q1e 파생 표 append 가 쥔 KEY SHARE: 키 변경(승격)은 기다리고 갱신은 안 기다린다"
  reset
  sess A $OUT/q1e_a.log <<EOF &
BEGIN;
$APPEND_OK;
SELECT 'A appended', to_char(clock_timestamp(),'HH24:MI:SS.MS');
SELECT pg_sleep(3);
COMMIT;
SELECT 'A committed', to_char(clock_timestamp(),'HH24:MI:SS.MS');
EOF
  sleep 0.7
  $P 2>&1 <<EOF
\timing on
SELECT 'C renew start', to_char(clock_timestamp(),'HH24:MI:SS.MS');
UPDATE leader_lease SET lease_until = clock_timestamp() + interval '1 hour' WHERE resource = 'matching-engine';
SELECT 'B promote start', to_char(clock_timestamp(),'HH24:MI:SS.MS');
UPDATE leader_lease SET holder = 'node-b', epoch = 6 WHERE resource = 'matching-engine';
SELECT 'B promote done', to_char(clock_timestamp(),'HH24:MI:SS.MS');
EOF
  wait; cat $OUT/q1e_a.log; row

  echo "== Q1f 승격이 먼저 행을 쥐고 있으면 append 가 기다렸다가 새 행으로 다시 판정한다(EvalPlanQual)"
  reset
  sess B $OUT/q1f_b.log <<EOF &
BEGIN;
UPDATE leader_lease SET holder = 'node-b', epoch = 6 WHERE resource = 'matching-engine';
SELECT 'B promoted uncommitted', to_char(clock_timestamp(),'HH24:MI:SS.MS');
SELECT pg_sleep(2);
COMMIT;
SELECT 'B committed', to_char(clock_timestamp(),'HH24:MI:SS.MS');
EOF
  sleep 0.5
  $P 2>&1 <<EOF
\timing on
SELECT 'A old-epoch append start', to_char(clock_timestamp(),'HH24:MI:SS.MS');
$APPEND_OK;
SELECT 'A old-epoch append end', to_char(clock_timestamp(),'HH24:MI:SS.MS');
EOF
  wait; cat $OUT/q1f_b.log
  q "SELECT 'Q1f journal rows', count(*) FROM command_journal"
  reset
  sess B $OUT/q1f2_b.log <<EOF &
BEGIN;
UPDATE leader_lease SET holder = 'node-b', epoch = 6 WHERE resource = 'matching-engine';
SELECT pg_sleep(2);
COMMIT;
EOF
  sleep 0.5
  $P 2>&1 <<EOF
INSERT INTO command_journal (seq, market_id, payload, fencing_token)
SELECT 106, v.market_id, 'x', l.epoch
FROM (VALUES (1::smallint)) AS v(market_id)
JOIN leader_lease l ON l.resource = lease_resource_for_market(v.market_id)
WHERE l.holder = 'node-b' AND l.epoch = 6 AND l.lease_until > clock_timestamp()
FOR KEY SHARE OF l;
SELECT 'Q1f2 new-epoch append rows', count(*), max(fencing_token) FROM command_journal;
EOF
  wait
}

# ── Q2 EvalPlanQual 재평가에서 SET 절 nextval 이 다시 계산되는가 ─────────
Q2_UPDATE="UPDATE leader_lease SET holder = 'node-c',
    epoch = GREATEST(nextval('probe_seq'), epoch + 1),
    lease_until = clock_timestamp() + interval '6 seconds'
WHERE resource = 'matching-engine' AND lease_until < clock_timestamp()
RETURNING 'A result epoch', epoch"

q2_prep() {
  reset
  q "UPDATE leader_lease SET holder = 'old', epoch = 5, lease_until = clock_timestamp() - interval '1 second' WHERE resource = 'matching-engine'" >/dev/null
  q "SELECT setval('probe_seq', 100)" >/dev/null
}

q2() {
  echo "== Q2a 막던 트랜잭션이 행을 바꿨고(epoch 9) 조건은 여전히 참"
  q2_prep
  sess B $OUT/q2a_b.log <<EOF &
BEGIN;
UPDATE leader_lease SET epoch = 9 WHERE resource = 'matching-engine';
SELECT pg_sleep(2);
COMMIT;
EOF
  sleep 0.5
  $P 2>&1 <<EOF
$Q2_UPDATE;
EOF
  wait; seqv; row

  echo "== Q2b 막던 트랜잭션이 KEY SHARE 로 잠그기만 했다"
  q2_prep
  sess B $OUT/q2b_b.log <<EOF &
BEGIN;
SELECT epoch FROM leader_lease WHERE resource = 'matching-engine' FOR KEY SHARE;
SELECT pg_sleep(2);
COMMIT;
EOF
  sleep 0.5
  $P 2>&1 <<EOF
$Q2_UPDATE;
EOF
  wait; seqv; row

  echo "== Q2c 막던 트랜잭션이 조건을 거짓으로 바꿨다(다른 노드가 먼저 승격)"
  q2_prep
  sess B $OUT/q2c_b.log <<EOF &
BEGIN;
UPDATE leader_lease SET holder = 'node-b', epoch = 6, lease_until = clock_timestamp() + interval '1 hour' WHERE resource = 'matching-engine';
SELECT pg_sleep(2);
COMMIT;
EOF
  sleep 0.5
  $P 2>&1 <<EOF
$Q2_UPDATE;
EOF
  wait; seqv; row

  echo "== Q2d 잠금 먼저 방식: 먼저 승격한 쪽이 있으면 번호를 뽑지 않는다"
  q2_prep
  sess B $OUT/q2d_b.log <<EOF &
BEGIN;
UPDATE leader_lease SET holder = 'node-b', epoch = 6, lease_until = clock_timestamp() + interval '1 hour' WHERE resource = 'matching-engine';
SELECT pg_sleep(2);
COMMIT;
EOF
  sleep 0.5
  q "SELECT 'A lock-first result', acquire_lock_first('matching-engine', 'node-c')"
  wait; seqv; row

  echo "== Q2e 잠금 먼저 방식: 막던 트랜잭션이 epoch 만 바꾸면 새 epoch 위에서 번호를 한 번 뽑는다"
  q2_prep
  sess B $OUT/q2e_b.log <<EOF &
BEGIN;
UPDATE leader_lease SET epoch = 9 WHERE resource = 'matching-engine';
SELECT pg_sleep(2);
COMMIT;
EOF
  sleep 0.5
  q "SELECT 'A lock-first result', acquire_lock_first('matching-engine', 'node-c')"
  wait; seqv; row

  echo "== Q2f 시퀀스는 롤백돼도 되돌아가지 않는다"
  q2_prep
  $P 2>&1 <<EOF
BEGIN;
SELECT 'rolled back nextval', nextval('probe_seq');
ROLLBACK;
SELECT 'next nextval', nextval('probe_seq');
EOF
}

# ── Q3 대기 중인 FOR UPDATE 앞에서 KEY SHARE 를 주는 순서 ───────────────
q3() {
  echo "== Q3 A 가 KEY SHARE 보유, B 가 FOR UPDATE 대기, 그 뒤 C 가 KEY SHARE, D 가 갱신"
  reset
  sess A $OUT/q3_a.log <<EOF &
BEGIN;
SELECT 'A key share', to_char(clock_timestamp(),'HH24:MI:SS.MS') FROM leader_lease WHERE resource = 'matching-engine' FOR KEY SHARE;
SELECT pg_sleep(3);
COMMIT;
SELECT 'A committed', to_char(clock_timestamp(),'HH24:MI:SS.MS');
EOF
  sleep 0.5
  sess B $OUT/q3_b.log <<EOF &
SELECT 'B for update start', to_char(clock_timestamp(),'HH24:MI:SS.MS');
BEGIN;
SELECT 'B for update got', to_char(clock_timestamp(),'HH24:MI:SS.MS') FROM leader_lease WHERE resource = 'matching-engine' FOR UPDATE;
COMMIT;
EOF
  sleep 0.5
  sess C $OUT/q3_c.log <<EOF &
SELECT 'C key share start', to_char(clock_timestamp(),'HH24:MI:SS.MS');
BEGIN;
SELECT 'C key share got', to_char(clock_timestamp(),'HH24:MI:SS.MS') FROM leader_lease WHERE resource = 'matching-engine' FOR KEY SHARE;
SELECT pg_sleep(4);
COMMIT;
SELECT 'C committed', to_char(clock_timestamp(),'HH24:MI:SS.MS');
EOF
  sleep 0.3
  sess D $OUT/q3_d.log <<EOF &
SELECT 'D renew start', to_char(clock_timestamp(),'HH24:MI:SS.MS');
UPDATE leader_lease SET lease_until = clock_timestamp() + interval '1 hour' WHERE resource = 'matching-engine';
SELECT 'D renew done', to_char(clock_timestamp(),'HH24:MI:SS.MS');
EOF
  sleep 0.5
  q "SELECT 'waiter', pid, wait_event_type, wait_event, pg_blocking_pids(pid), left(regexp_replace(query,'\s+',' ','g'),50) FROM pg_stat_activity WHERE datname = 'probe' AND wait_event_type = 'Lock'"
  wait
  cat $OUT/q3_a.log $OUT/q3_b.log $OUT/q3_c.log $OUT/q3_d.log
}

# ── Q4 권한: FOR KEY SHARE 에 UPDATE 권한이 필요한가, SECURITY DEFINER 잠금은 호출 트랜잭션에 남는가 ─
q4() {
  echo "== Q4a UPDATE 권한 없는 역할의 FOR KEY SHARE"
  reset
  $P 2>&1 <<EOF
SET ROLE probe_app;
SELECT 'plain select', epoch FROM leader_lease WHERE resource = 'matching-engine';
SELECT 'key share', epoch FROM leader_lease WHERE resource = 'matching-engine' FOR KEY SHARE;
$APPEND_OK;
RESET ROLE;
EOF
  echo "== Q4b 열 하나의 UPDATE 권한만 주면"
  $P 2>&1 <<EOF
GRANT UPDATE (lease_until) ON leader_lease TO probe_app;
SET ROLE probe_app;
SELECT 'key share with column grant', epoch FROM leader_lease WHERE resource = 'matching-engine' FOR KEY SHARE;
RESET ROLE;
REVOKE UPDATE (lease_until) ON leader_lease FROM probe_app;
EOF
  echo "== Q4c SECURITY DEFINER 함수가 잡은 KEY SHARE 가 호출 트랜잭션 끝까지 남는다"
  reset
  sess A $OUT/q4c_a.log <<EOF &
BEGIN;
SET LOCAL ROLE probe_app;
SELECT 'A definer lock', lock_lease_key_share(1::smallint), current_user, to_char(clock_timestamp(),'HH24:MI:SS.MS');
SELECT pg_sleep(2.5);
COMMIT;
SELECT 'A committed', to_char(clock_timestamp(),'HH24:MI:SS.MS');
EOF
  sleep 0.7
  rl
  $P 2>&1 <<EOF
\timing on
SELECT 'B promote start', to_char(clock_timestamp(),'HH24:MI:SS.MS');
UPDATE leader_lease SET holder = 'node-b', epoch = 6 WHERE resource = 'matching-engine';
SELECT 'B promote done', to_char(clock_timestamp(),'HH24:MI:SS.MS');
EOF
  wait; cat $OUT/q4c_a.log
}

# ── Q5 마켓 추가 트리거와 추가 전용 대응 표 ─────────────────────────────
q5() {
  echo "== Q5a 마이그레이션 시드 배정"
  reset
  q "SELECT 'seed', resource, string_agg(market_id::text, ',' ORDER BY market_id) FROM market_lease_partition GROUP BY resource ORDER BY resource"
  echo "== Q5b 대응 표 권한 없는 역할이 마켓을 넣으면 SECURITY DEFINER 트리거가 대응 행을 넣는다"
  $P 2>&1 <<EOF
SET ROLE probe_app;
INSERT INTO markets VALUES (13, 'M13'), (14, 'M14');
SELECT 'new markets', market_id, resource FROM market_lease_partition WHERE market_id >= 13 ORDER BY 1;
INSERT INTO market_lease_partition VALUES (15, 'matching-p0');
RESET ROLE;
EOF
  echo "== Q5c 소유자도 고치거나 지우거나 비우지 못한다. 같은 행 재삽입은 조용히 넘어간다"
  q "UPDATE market_lease_partition SET resource = 'matching-p1' WHERE market_id = 1"
  q "DELETE FROM market_lease_partition WHERE market_id = 1"
  q "TRUNCATE market_lease_partition"
  q "INSERT INTO market_lease_partition VALUES (1, 'matching-p1') ON CONFLICT (market_id) DO NOTHING"
  q "SELECT 'market 1 still', resource FROM market_lease_partition WHERE market_id = 1"
  q "INSERT INTO market_lease_partition VALUES (99, 'matching-engine')"
}

# ── Q6 프로토콜별 도출 ─────────────────────────────────────────────────
q6() {
  echo "== Q6a P1 에서는 markets 에 없는 마켓도 matching-engine"
  reset
  q "SELECT 'P1', lease_resource_for_market(1::smallint), lease_resource_for_market(77::smallint)"
  echo "== Q6b P3 에서는 대응 값, 대응이 없으면 55000"
  q "UPDATE leader_protocol SET protocol = 3" >/dev/null
  q "SELECT 'P3', lease_resource_for_market(1::smallint), lease_resource_for_market(2::smallint)"
  $P 2>&1 <<EOF
\set VERBOSITY verbose
SELECT 'P3 unmapped', lease_resource_for_market(77::smallint);
EOF
  echo "== Q6c P3 에서 마켓 1 리스(p0)로 마켓 2(p1) 행을 쓰려는 append 는 0행"
  q "UPDATE leader_lease SET holder = 'node-a', epoch = 7, lease_until = clock_timestamp() + interval '1 hour' WHERE resource = 'matching-p0'" >/dev/null
  $P 2>&1 <<EOF
INSERT INTO command_journal (seq, market_id, payload, fencing_token)
SELECT 201, v.market_id, 'x', l.epoch
FROM (VALUES (2::smallint)) AS v(market_id)
JOIN leader_lease l ON l.resource = lease_resource_for_market(v.market_id)
WHERE l.holder = 'node-a' AND l.epoch = 7 AND l.lease_until > clock_timestamp()
FOR KEY SHARE OF l;
INSERT INTO command_journal (seq, market_id, payload, fencing_token)
SELECT 202, v.market_id, 'x', l.epoch
FROM (VALUES (1::smallint)) AS v(market_id)
JOIN leader_lease l ON l.resource = lease_resource_for_market(v.market_id)
WHERE l.holder = 'node-a' AND l.epoch = 7 AND l.lease_until > clock_timestamp()
FOR KEY SHARE OF l;
SELECT 'Q6c rows', seq, market_id, fencing_token FROM command_journal ORDER BY seq;
EOF
}

# ── Q7 전환이 모든 리스 행을 잠그고 퇴역시키는 동안 진행 중인 append ─────
q7() {
  echo "== Q7 전환이 FOR UPDATE 를 기다리고, 그 뒤 낡은 도출로 들어온 append 는 0행"
  reset
  sess W $OUT/q7_w.log <<EOF &
BEGIN;
$APPEND_OK;
SELECT 'W in-flight append', to_char(clock_timestamp(),'HH24:MI:SS.MS');
SELECT pg_sleep(1.5);
COMMIT;
SELECT 'W committed', to_char(clock_timestamp(),'HH24:MI:SS.MS');
EOF
  sleep 0.3
  sess S $OUT/q7_s.log <<EOF &
SELECT 'S switch start', to_char(clock_timestamp(),'HH24:MI:SS.MS');
BEGIN;
SELECT 'S locked', string_agg(resource, ',' ORDER BY resource), to_char(clock_timestamp(),'HH24:MI:SS.MS')
  FROM (SELECT resource FROM leader_lease ORDER BY resource FOR UPDATE) t;
UPDATE leader_protocol SET protocol = 3;
UPDATE leader_lease SET holder = '', epoch = epoch + 100, lease_until = clock_timestamp() - interval '1 second';
SELECT pg_sleep(1.5);
COMMIT;
SELECT 'S committed', to_char(clock_timestamp(),'HH24:MI:SS.MS');
EOF
  sleep 2.0
  $P 2>&1 <<EOF
\timing on
SELECT 'late append start', to_char(clock_timestamp(),'HH24:MI:SS.MS');
INSERT INTO command_journal (seq, market_id, payload, fencing_token)
SELECT 301, v.market_id, 'x', l.epoch
FROM (VALUES (1::smallint)) AS v(market_id)
JOIN leader_lease l ON l.resource = lease_resource_for_market(v.market_id)
WHERE l.holder = 'node-a' AND l.epoch = 5 AND l.lease_until > clock_timestamp()
FOR KEY SHARE OF l;
SELECT 'late append end', to_char(clock_timestamp(),'HH24:MI:SS.MS');
EOF
  wait
  cat $OUT/q7_w.log $OUT/q7_s.log
  q "SELECT 'Q7 journal', seq, fencing_token FROM command_journal ORDER BY seq"
  q "SELECT 'Q7 protocol', protocol FROM leader_protocol"
}

# ── Q8 같은 트랜잭션의 같은 행 재잠금 ───────────────────────────────────
q8() {
  echo "== Q8 한 트랜잭션이 같은 행을 KEY SHARE 로 여러 번 잡아도 잠금은 하나다"
  reset
  $P 2>&1 <<EOF
BEGIN;
SELECT lock_lease_key_share(1::smallint);
SELECT lock_lease_key_share(2::smallint);
SELECT lock_lease_key_share(3::smallint);
SELECT 'Q8 rowlocks', l.resource, r.multi, r.modes FROM pgrowlocks('leader_lease') r JOIN leader_lease l ON l.ctid = r.locked_row;
COMMIT;
EOF
}

# ── Q10 CREATE OR REPLACE 로 SECURITY DEFINER 로 바꾸면 PUBLIC 의 EXECUTE 가 남는다 ─────
q10() {
  echo "== Q10 호출자 권한 함수를 SECURITY DEFINER 로 바꿔도 기존 EXECUTE(PUBLIC 포함)는 그대로다"
  reset
  $P 2>&1 <<EOF
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'probe_reader') THEN CREATE ROLE probe_reader NOLOGIN; END IF;
END \$\$;
GRANT USAGE ON SCHEMA public TO probe_reader;
CREATE FUNCTION grab_lease(p_holder TEXT) RETURNS BIGINT LANGUAGE plpgsql AS \$f\$
DECLARE v BIGINT;
BEGIN
  UPDATE leader_lease SET holder = p_holder, epoch = epoch + 1 WHERE resource = 'matching-engine' RETURNING epoch INTO v;
  RETURN v;
END \$f\$;
SELECT 'invoker public execute', has_function_privilege('probe_reader', 'grab_lease(text)', 'EXECUTE');
SET ROLE probe_reader;
SELECT 'invoker call by reader', grab_lease('reader');
RESET ROLE;
CREATE OR REPLACE FUNCTION grab_lease(p_holder TEXT) RETURNS BIGINT LANGUAGE plpgsql
SECURITY DEFINER SET search_path = public, pg_temp AS \$f\$
DECLARE v BIGINT;
BEGIN
  UPDATE leader_lease SET holder = p_holder, epoch = epoch + 1 WHERE resource = 'matching-engine' RETURNING epoch INTO v;
  RETURN v;
END \$f\$;
SELECT 'definer public execute', has_function_privilege('probe_reader', 'grab_lease(text)', 'EXECUTE');
SET ROLE probe_reader;
SELECT 'definer call by reader', grab_lease('reader');
RESET ROLE;
REVOKE ALL ON FUNCTION grab_lease(text) FROM PUBLIC;
SELECT 'after revoke public execute', has_function_privilege('probe_reader', 'grab_lease(text)', 'EXECUTE');
SET ROLE probe_reader;
SELECT 'after revoke call by reader', grab_lease('reader2');
RESET ROLE;
SELECT 'row', holder, epoch FROM leader_lease WHERE resource = 'matching-engine';
EOF
}

# ── Q11 CREATE OR REPLACE 가 바꿀 수 있는 것: 매개변수 이름은 못 바꾸고 언어·보안 속성은 바꾼다 ──
q11() {
  echo "== Q11 CREATE OR REPLACE 의 매개변수 이름·언어·보안 속성"
  reset
  $P 2>&1 <<EOF
CREATE FUNCTION f11(p_holder TEXT, p_ttl INTERVAL) RETURNS BIGINT LANGUAGE plpgsql AS \$f\$ BEGIN RETURN 1; END \$f\$;
CREATE OR REPLACE FUNCTION f11(p_other TEXT, p_ttl INTERVAL) RETURNS BIGINT LANGUAGE plpgsql AS \$f\$ BEGIN RETURN 2; END \$f\$;
CREATE OR REPLACE FUNCTION f11(p_holder TEXT, p_ttl INTERVAL) RETURNS BIGINT LANGUAGE sql
SECURITY DEFINER SET search_path = public, pg_temp AS \$f\$ SELECT 3::bigint \$f\$;
SELECT 'after replace', f11('x', '1 s'), p.prosecdef, l.lanname, p.proconfig
FROM pg_proc p JOIN pg_language l ON l.oid = p.prolang WHERE p.proname = 'f11';
EOF
}

case "$1" in
  q1) q1 ;; q2) q2 ;; q3) q3 ;; q4) q4 ;; q5) q5 ;; q6) q6 ;; q7) q7 ;; q8) q8 ;; q10) q10 ;; q11) q11 ;;
  all) q1; q2; q3; q4; q5; q6; q7; q8; q10; q11 ;;
  *) echo "usage: probe.sh q1..q8|q10|q11|all"; exit 2 ;;
esac
