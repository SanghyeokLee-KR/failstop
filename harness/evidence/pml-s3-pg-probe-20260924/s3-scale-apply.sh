#!/bin/sh
# 컨테이너 안(s3-scale-probe.sh 가 부른다). 저장소 V0~V48, 시드, 문서의 V49·V50 계약 SQL 을 올리고 P2 로 전환한 뒤
# 합성 주문 생성 명령 N 건을 넣는다. 명령마다 예약 행, SEALED RESERVE 거래와 분개 둘을 두고, 마지막 PENDING 건을 뺀 나머지는
# ACCEPTED 이벤트·레지스트리·orders(FILLED)로 정산된 것처럼 둔다. 넣을 때만 트리거를 끈다(시간 측정용 합성 행이다).
# 그다음 문장마다 EXPLAIN (ANALYZE, BUFFERS)를 두 번 돌려 둘째 회차를 남긴다.
set -eu
: "${N:?}" "${PENDING:?}"
PSQL="psql -X -U postgres -d scale -At -v ON_ERROR_STOP=1"

for f in $(ls /c/migration/V*.sql | sed -E 's|.*/V([0-9]+)__.*|\1 &|' | sort -n | cut -d' ' -f2); do
  $PSQL -q -1 -f "$f" >/dev/null 2>/tmp/mig.err || { echo "MIGRATION FAILED: $f"; cat /tmp/mig.err; exit 1; }
done
$PSQL -q -1 -f /c/s3-seed.sql >/dev/null
$PSQL -q -1 -f /c/v49.sql >/dev/null
$PSQL -q -1 -f /c/v50.sql >/dev/null
echo "== 적용: 저장소 $(ls /c/migration/V*.sql | wc -l)개 파일(V0..V48), 시드, 계약 V49·V50"

P2EPOCH=$($PSQL <<'EOF'
UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' WHERE resource = 'matching-engine';
SELECT try_acquire_lease('matching-engine', 'scale-p1', interval '1 hour', 2::smallint) IS NOT NULL;
SELECT count(*) FROM set_leader_protocol(2::smallint);
SELECT try_acquire_lease('matching-engine', 'scale-p2', interval '1 hour', 2::smallint);
EOF
)
P2EPOCH=$(echo "$P2EPOCH" | tail -1)
echo "== P2 전환 뒤 획득 epoch=$P2EPOCH"

T0=$(date +%s)
$PSQL -v n="$N" -v pending="$PENDING" -v ep="$P2EPOCH" <<'EOF'
SET session_replication_role = replica;
SET synchronous_commit = off;
SET maintenance_work_mem = '256MB';
CREATE UNLOGGED TABLE syn AS
SELECT s.*, av.id AS avail, lk.id AS locked
FROM (SELECT g,
             1000 + g AS seq,
             ((g % 3) + 1)::smallint AS market_id,
             ((g % 2) + 1)::bigint AS user_id,
             (CASE (g % 3) + 1 WHEN 2 THEN 3 ELSE 2 END)::smallint AS asset_id,
             10000000 + g AS order_id,
             jsonb_build_object('side', 'SELL', 'ord_type', 'LIMIT', 'time_in_force', 'GTC', 'price', '100', 'qty', '0.001',
                                'quote_order_qty', NULL, 'external_id', md5(g::text),
                                'maker_fee_rate', '0.0005', 'taker_fee_rate', '0.0005') AS payload,
             now() - ((:n - g) * interval '1 millisecond') AS created_at
        FROM generate_series(1, :n) g) s
JOIN accounts av ON av.owner_type = 'USER' AND av.owner_id = s.user_id AND av.purpose = 'AVAILABLE'
                AND av.asset_id = s.asset_id AND av.shard = 0
JOIN accounts lk ON lk.owner_type = 'USER' AND lk.owner_id = s.user_id AND lk.purpose = 'LOCKED'
                AND lk.asset_id = s.asset_id AND lk.shard = 0;
INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id, assigned_order_id, engine_ts,
                             tie_break, payload, fencing_token, checksum, created_at)
SELECT seq, market_id, 'NEW', user_id, 'syn-' || g, order_id, g, g, payload, :ep, 0, created_at FROM syn;
INSERT INTO ledger_transactions (id, business_event_type, business_event_id, status, created_at, sealed_at)
OVERRIDING SYSTEM VALUE
SELECT 100000000 + g, 'RESERVE', order_id, 'SEALED', created_at, created_at FROM syn;
INSERT INTO ledger_entries (transaction_id, account_id, asset_id, amount, entry_type, created_at)
SELECT 100000000 + g, avail, asset_id, -0.001, 'RESERVE', created_at FROM syn
UNION ALL
SELECT 100000000 + g, locked, asset_id, 0.001, 'RESERVE', created_at FROM syn;
INSERT INTO order_reservations (command_seq, order_id, user_id, market_id, asset_id, available_account_id, locked_account_id,
                                amount, ledger_txn_id, payload_sha256, created_at)
SELECT seq, order_id, user_id, market_id, asset_id, avail, locked, 0.001, 100000000 + g,
       sha256(convert_to(payload::text, 'UTF8')), created_at FROM syn;
INSERT INTO event_journal (event_seq, source_cmd_seq, event_ordinal, event_type, payload, payload_schema_version,
                           fencing_epoch, created_at, market_id, market_seq)
SELECT g, seq, 0, 'ACCEPTED', '{}'::jsonb, 1, :ep, created_at, market_id, g FROM syn WHERE g <= :n - :pending;
INSERT INTO event_emission_registry (source_cmd_seq, event_ordinal, event_seq, created_at)
SELECT seq, 0, g, created_at FROM syn WHERE g <= :n - :pending;
INSERT INTO orders (id, external_id, user_id, market_id, client_order_id, side, ord_type, time_in_force, price, quantity,
                    filled_qty, remaining_qty, locked_account_id, locked_amount, status, command_seq, created_at, updated_at)
SELECT order_id, md5(g::text)::uuid, user_id, market_id, 'syn-' || g, 'SELL', 'LIMIT', 'GTC', 100, 0.001, 0.001, 0,
       locked, 0, 'FILLED', seq, created_at, created_at
  FROM syn WHERE g <= :n - :pending;
DROP TABLE syn;
SET session_replication_role = DEFAULT;
EOF
$PSQL -q -c "VACUUM (ANALYZE)" >/dev/null
T1=$(date +%s)
echo "== 합성 적재와 VACUUM ANALYZE: $((T1 - T0))초"
$PSQL <<'EOF'
SELECT 'rows', (SELECT count(*) FROM command_journal) AS command_journal, (SELECT count(*) FROM order_reservations) AS reservations,
       (SELECT count(*) FROM event_emission_registry) AS registry, (SELECT count(*) FROM ledger_entries) AS ledger_entries,
       (SELECT count(*) FROM orders) AS orders;
SELECT 'size', pg_size_pretty(pg_database_size('scale'));
EOF

cat > /tmp/q_u_full.sql <<'EOF'
SELECT count(*), sum(amt) FROM (
  SELECT r.locked_account_id AS account_id, SUM(r.amount) AS amt FROM order_reservations r
   WHERE NOT EXISTS (SELECT 1 FROM event_emission_registry e WHERE e.source_cmd_seq = r.command_seq)
   GROUP BY r.locked_account_id) u;
EOF
cat > /tmp/q_u_cursor.sql <<'EOF'
SELECT count(*), sum(amt) FROM (
  SELECT r.locked_account_id AS account_id, SUM(r.amount) AS amt
    FROM markets m
    CROSS JOIN LATERAL (SELECT max(e.source_cmd_seq) AS settled FROM event_journal e WHERE e.market_id = m.id) cur
    CROSS JOIN LATERAL (SELECT c.seq FROM command_journal c
                         WHERE c.market_id = m.id AND c.command_type = 'NEW' AND c.seq > COALESCE(cur.settled, -1)
                         ORDER BY c.seq LIMIT 100000) c
    CROSS JOIN LATERAL (SELECT r.locked_account_id, r.amount FROM order_reservations r
                         WHERE r.command_seq = c.seq
                           AND NOT EXISTS (SELECT 1 FROM event_emission_registry e WHERE e.source_cmd_seq = r.command_seq)
                         LIMIT 1) r
   GROUP BY r.locked_account_id) u;
EOF
cat > /tmp/q_precond.sql <<'EOF'
SELECT count(*) FROM command_journal c
 WHERE c.command_type = 'NEW'
   AND NOT EXISTS (SELECT 1 FROM event_emission_registry e WHERE e.source_cmd_seq = c.seq);
EOF
cat > /tmp/q_gauge.sql <<'EOF'
SELECT count(*) AS pending, COALESCE(floor(EXTRACT(EPOCH FROM clock_timestamp() - min(r.created_at))), 0) AS oldest_s
FROM markets m
CROSS JOIN LATERAL (SELECT max(e.source_cmd_seq) AS settled FROM event_journal e WHERE e.market_id = m.id) cur
CROSS JOIN LATERAL (SELECT c.seq FROM command_journal c
                     WHERE c.market_id = m.id AND c.command_type = 'NEW' AND c.seq > COALESCE(cur.settled, -1)
                     ORDER BY c.seq LIMIT 100000) c
CROSS JOIN LATERAL (SELECT r.created_at FROM order_reservations r
                     WHERE r.command_seq = c.seq
                       AND NOT EXISTS (SELECT 1 FROM event_emission_registry e WHERE e.source_cmd_seq = r.command_seq)
                     LIMIT 1) r;
EOF
cat > /tmp/q_c05.sql <<'EOF'
WITH s AS (SELECT band_span FROM journal_band_spec WHERE name = 'command_seq')
SELECT (SELECT CASE WHEN count(*) = 1 THEN 0 ELSE 1 END FROM s)
     + (SELECT COALESCE((max(c.seq) - min(c.seq) + 1) - count(*), 0) FROM command_journal c WHERE c.seq < (SELECT band_span FROM s))
     + (SELECT COALESCE(sum(b.gap), 0) FROM (SELECT (max(c.seq) - min(c.seq) + 1) - count(*) AS gap FROM command_journal c
                                             WHERE c.seq >= (SELECT band_span FROM s) GROUP BY c.market_id) b);
EOF

explain() {
  echo
  echo "== $1"
  { echo "EXPLAIN (ANALYZE, BUFFERS)"; cat "$2"; } | $PSQL | grep -E 'Execution Time' | sed 's/^/1회차 /'
  { echo "EXPLAIN (ANALYZE, BUFFERS)"; cat "$2"; } | $PSQL
}
# JIT 을 끈 실행 시간만 남긴다(계획 비용 추정이 커서 JIT 컴파일이 실행보다 오래 걸리는 문장을 가르려는 것이다).
nojit() {
  { echo "SET jit = off;"; echo "EXPLAIN (ANALYZE, BUFFERS)"; cat "$2"; } | $PSQL | grep -E 'Execution Time' | sed "s/^/$1 jit=off /"
}
sed 's/LIMIT 5000/LIMIT 20000/' /c/c31app.sql > /tmp/c31app60k.sql
echo
echo "== 결과값(판정용이 아니라 두 판이 같은 값을 내는지 본다)"
$PSQL < /tmp/q_u_full.sql | sed 's/^/u full: /'
$PSQL < /tmp/q_u_cursor.sql | sed 's/^/u cursor: /'
$PSQL < /tmp/q_precond.sql | sed 's/^/precondition unsettled NEW: /'
$PSQL < /tmp/q_gauge.sql | sed 's/^/gauge pending|oldest_s: /'
$PSQL < /c/c31app.sql | sed 's/^/C31 app window(5000 x 3 markets): /'
explain "C14' 정산 전 예약 항, 개정 전 전수 판" /tmp/q_u_full.sql
explain "C14' 정산 전 예약 항, 커서 판" /tmp/q_u_cursor.sql
explain "C14' 전체 문장(문서 원문, 커서 판)" /c/c14p.sql
explain "C31 전체 문장(문서 원문, 전수)" /c/c31.sql
explain "C31 앱 판(문서 원문, 마켓마다 최근 5,000건 x 3마켓 = 15,000행)" /c/c31app.sql
explain "C31 앱 판을 마켓마다 20,000건으로 늘린 판(3마켓 x 20,000 = 60,000행, 12마켓 x 5,000 의 대리)" /tmp/c31app60k.sql
explain "전환 선행 조건(정산 안 된 NEW 수, 전수)" /tmp/q_precond.sql
explain "게이지 질의(커서 판)" /tmp/q_gauge.sql
explain "참고: 기존 C05(앱 enum 원문, 전수)" /tmp/q_c05.sql
echo
echo "== JIT 을 끈 실행 시간(2회차 값만)"
nojit "C14' 정산 전 예약 항, 개정 전 전수 판" /tmp/q_u_full.sql
nojit "C14' 정산 전 예약 항, 커서 판" /tmp/q_u_cursor.sql
nojit "C14' 전체 문장(커서 판)" /c/c14p.sql
nojit "C31 전체 문장(전수)" /c/c31.sql
nojit "C31 앱 판(15,000행)" /c/c31app.sql
nojit "C31 앱 판(60,000행, 12마켓 대리)" /tmp/c31app60k.sql
nojit "전환 선행 조건" /tmp/q_precond.sql
nojit "게이지 질의" /tmp/q_gauge.sql
nojit "기존 C05" /tmp/q_c05.sql
echo
echo "== 끝"
