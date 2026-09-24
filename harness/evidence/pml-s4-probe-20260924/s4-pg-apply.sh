#!/bin/sh
# 컨테이너 안(s4-pg-probe.sh 가 부른다). 저장소의 실제 V0~V52 를 파일마다 한 트랜잭션으로 올리고 V53 계약 SQL 을 올린 뒤
# ADR 0034 S4 설계 3절 G1~G5 를 확인한다. 로그인: appu(app_role 소속 앱 로그인), hr(harness_role 소속 조회 로그인). 소유자는 postgres.
V='\set VERBOSITY verbose
\set SHOW_CONTEXT never'
su() { psql -X -U postgres -d probe -At -v ON_ERROR_STOP=0 "$@" 2>&1; }
app() { psql -X -U appu -d probe -At -v ON_ERROR_STOP=0 "$@" 2>&1; }

# ── G0 적용 ──────────────────────────────────────────────────────────
for f in $(ls /c/migration/V*.sql | sed -E 's|.*/V([0-9]+)__.*|\1 &|' | sort -n | cut -d' ' -f2); do
  psql -X -U postgres -d probe -q -1 -v ON_ERROR_STOP=1 -f "$f" >/dev/null 2>/tmp/mig.err \
    || { echo "MIGRATION FAILED: $f"; cat /tmp/mig.err; exit 1; }
done
echo "== G0 applied repository migrations: $(ls /c/migration/V*.sql | wc -l) files"
psql -X -U postgres -d probe -q -1 -v ON_ERROR_STOP=1 -f /c/v53.sql 2>&1
echo "v53 exit=$?"
su -q <<'EOF'
CREATE ROLE appu LOGIN;
GRANT app_role TO appu;
CREATE ROLE hr LOGIN;
GRANT harness_role TO hr;
EOF

# ── G1 P3 흉내와 바이너리 능력 ─────────────────────────────────────
echo
echo "== G1 P3 흉내(소유자 UPDATE). 최고 모드 2 획득, P3 의 matching-engine 획득, 최고 모드 3 의 p0 획득"
su -c "UPDATE leader_protocol SET protocol = 3"
app <<EOF
$V
SELECT try_acquire_lease('matching-p0', 'nodeA', interval '6 s', 2::smallint);
SELECT try_acquire_lease('matching-engine', 'nodeA', interval '6 s', 3::smallint);
SELECT 'p0 acquired epoch', try_acquire_lease('matching-p0', 'nodeA', interval '6 s', 3::smallint);
EOF
E0=$(su -c "SELECT epoch FROM leader_lease WHERE resource = 'matching-p0'")
echo "E0=$E0"

# ── G2 KEY SHARE 에 막힌 획득의 lock_timeout 과 다른 파티션 갱신 ─────
echo
echo "== G2 세션 X 가 p1 행 KEY SHARE 를 4초 쥔다. appu 가 lock_timeout 700ms 로 p1 을 획득한다. 그동안 p0 갱신과 p1 새 KEY SHARE"
echo "before: acquisitions=$(su -c 'SELECT count(*) FROM lease_acquisitions') p1=$(su -c "SELECT holder || '/' || epoch FROM leader_lease WHERE resource = 'matching-p1'")"
( psql -X -U postgres -d probe -At -c "BEGIN; SELECT 'X holds p1 KEY SHARE', 1 FROM leader_lease WHERE resource = 'matching-p1' FOR KEY SHARE; SELECT pg_sleep(4); COMMIT;" > /tmp/x.out 2>&1 ) &
XPID=$!
sleep 0.5
( app > /tmp/b.out <<EOF
$V
SET lock_timeout = '700ms';
\timing on
SELECT try_acquire_lease('matching-p1', 'nodeA', interval '6 s', 3::smallint);
EOF
) &
BPID=$!
sleep 0.1
( app > /tmp/r.out <<EOF
\timing on
SELECT 'renew p0', renew_lease('matching-p0', 'nodeA', $E0, interval '6 s');
SELECT pg_sleep(0.1);
SELECT 'renew p0', renew_lease('matching-p0', 'nodeA', $E0, interval '6 s');
SELECT pg_sleep(0.1);
SELECT 'renew p0', renew_lease('matching-p0', 'nodeA', $E0, interval '6 s');
SELECT pg_sleep(0.1);
SELECT 'renew p0', renew_lease('matching-p0', 'nodeA', $E0, interval '6 s');
SELECT pg_sleep(0.1);
SELECT 'renew p0', renew_lease('matching-p0', 'nodeA', $E0, interval '6 s');
EOF
) &
RPID=$!
sleep 0.1
su -c "SELECT 'waiting', usename, wait_event_type, wait_event, left(query, 60) FROM pg_stat_activity WHERE usename = 'appu' AND state = 'active' AND query LIKE '%try_acquire_lease%'"
( su > /tmp/k.out <<'EOF'
\timing on
BEGIN;
SELECT 'K new KEY SHARE on p1', 1 FROM leader_lease WHERE resource = 'matching-p1' FOR KEY SHARE;
COMMIT;
EOF
) &
KPID=$!
wait $BPID
wait $RPID
wait $KPID
echo "-- B(획득, lock_timeout 700ms)"
cat /tmp/b.out
echo "-- R(p0 갱신, 획득이 기다리는 동안)"
grep -E "renew p0|^Time" /tmp/r.out
echo "-- K(획득이 기다리는 동안 p1 에 새 KEY SHARE)"
grep -E "K new|^Time" /tmp/k.out
echo "after timeout: acquisitions=$(su -c 'SELECT count(*) FROM lease_acquisitions') p1=$(su -c "SELECT holder || '/' || epoch FROM leader_lease WHERE resource = 'matching-p1'") appu_locks=$(su -c "SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid WHERE a.usename = 'appu'")"
wait $XPID
cat /tmp/x.out

# ── G3 KEY SHARE 가 풀린 뒤의 획득 ─────────────────────────────────
echo
echo "== G3 X 커밋 뒤 lock_timeout 없는 획득"
app <<EOF
$V
SELECT 'p1 acquired epoch', try_acquire_lease('matching-p1', 'nodeB', interval '6 s', 3::smallint);
EOF
su -c "SELECT resource, holder, epoch, round(extract(epoch FROM lease_until - clock_timestamp())::numeric, 1) AS remain_s FROM leader_lease ORDER BY resource"
su -c "SELECT epoch, resource, holder, protocol, origin FROM lease_acquisitions ORDER BY epoch"

# ── G4 V53 열과 권한 ────────────────────────────────────────────────
echo
echo "== G4 V53 harness.failover_runs.resource"
su -c "SELECT column_name, data_type, is_nullable, coalesce(column_default, '-') FROM information_schema.columns WHERE table_schema = 'harness' AND table_name = 'failover_runs' AND column_name = 'resource'"
su -c "SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'harness.failover_runs'::regclass AND contype = 'c'"
su -c "SELECT col_description('harness.failover_runs'::regclass, (SELECT attnum FROM pg_attribute WHERE attrelid = 'harness.failover_runs'::regclass AND attname = 'resource'))"
app <<EOF
$V
INSERT INTO harness.failover_runs (experiment_id, verdict, resource) VALUES ('E4', 'PASS', 'matching-p1') RETURNING experiment_id, resource;
INSERT INTO harness.failover_runs (experiment_id, verdict, resource) VALUES ('E4', 'PASS', 'matching-p9');
INSERT INTO harness.failover_runs (experiment_id, verdict) VALUES ('E2', 'PASS') RETURNING experiment_id, resource IS NULL AS legacy_null;
EOF
psql -X -U hr -d probe -At -c "SELECT 'hr reads', count(*), count(resource) FROM harness.failover_runs" 2>&1

# ── G5 파티션 대응과 T5 술어 ────────────────────────────────────────
echo
echo "== G5 마켓 둘의 대응, 모드별 도출, T5 술어(파티션 한정) 실행"
su <<'EOF'
INSERT INTO assets (id, symbol, name, kind, scale) VALUES (1, 'KRW', 'Won', 'FIAT', 0), (2, 'BTC', 'Bitcoin', 'CRYPTO', 8), (3, 'ETH', 'Ether', 'CRYPTO', 8);
INSERT INTO markets (id, symbol, base_asset_id, quote_asset_id, tick_size, lot_size, min_notional, max_notional, max_qty,
                     maker_fee_rate, taker_fee_rate)
VALUES (1, 'KRW-BTC', 2, 1, 1, 0.00000001, 0, 1000000000000, 1000000000000, 0, 0),
       (2, 'KRW-ETH', 3, 1, 1, 0.00000001, 0, 1000000000000, 1000000000000, 0, 0);
SELECT 'map', market_id, resource FROM market_lease_partition ORDER BY market_id;
SELECT 'P3 derive', lease_resource_for_market(1::smallint), lease_resource_for_market(2::smallint);
SELECT 'T5', coalesce((min(e.created_at) AT TIME ZONE 'UTC')::text, 'NULL') FROM event_journal e JOIN command_journal c ON c.seq = e.source_cmd_seq
 WHERE e.event_type = 'TRADE' AND c.fencing_token > 0 AND e.created_at > '2026-09-24T00:00:00.000Z'
   AND c.market_id IN (SELECT m.market_id FROM market_lease_partition m WHERE m.resource = 'matching-p1');
UPDATE leader_protocol SET protocol = 2;
SELECT 'P2 derive', lease_resource_for_market(1::smallint), lease_resource_for_market(2::smallint);
EOF
