#!/bin/sh
# 컨테이너 안(s3-scale-probe-12m.sh 가 부른다). 저장소 V0~V50 을 올리고 12마켓 시드 뒤 P2 로 전환한 다음 합성 주문 생성 명령
# N 건을 12마켓에 흩어 넣는다. 명령마다 예약 행, SEALED RESERVE 거래와 분개 둘을 두고, 마켓마다 마지막 PENDING 건을 뺀
# 나머지는 ACCEPTED 이벤트·레지스트리·orders(FILLED)로 정산된 것처럼 둔다. 넣을 때만 트리거를 끈다(시간 측정용 합성 행이다).
# 예비 측정(harness/evidence/pml-s3-pg-probe-20260924/s3-scale-apply.sh)과 다른 점은 셋이다. 마이그레이션이 문서 사본이 아니라
# 저장소 V49·V50 이다. 시드가 3마켓이 아니라 12마켓이다. 정산 전 대기가 전체 300건이 아니라 마켓마다 300건이다.
set -eu
: "${N:?}" "${PENDING:?}"
PSQL="psql -X -U postgres -d scale -At -v ON_ERROR_STOP=1"
TOTAL_PENDING=$((PENDING * 12))

for f in $(ls /c/migration/V*.sql | sed -E 's|.*/V([0-9]+)__.*|\1 &|' | sort -n | cut -d' ' -f2); do
  $PSQL -q -1 -f "$f" >/dev/null 2>/tmp/mig.err || { echo "MIGRATION FAILED: $f"; cat /tmp/mig.err; exit 1; }
done
echo "== 적용: 저장소 $(ls /c/migration/V*.sql | wc -l)개 파일(V0..V50)"
$PSQL -q -1 -f /c/seed-12m.sql >/dev/null

P2EPOCH=$($PSQL <<'EOF'
SELECT try_acquire_lease('matching-engine', 'scale-p1', interval '1 hour', 2::smallint) IS NOT NULL;
SELECT count(*) FROM set_leader_protocol(2::smallint);
SELECT try_acquire_lease('matching-engine', 'scale-p2', interval '1 hour', 2::smallint);
EOF
)
P2EPOCH=$(echo "$P2EPOCH" | tail -1)
echo "== P2 전환 뒤 획득 epoch=$P2EPOCH"

T0=$(date +%s)
$PSQL -v n="$N" -v pending="$TOTAL_PENDING" -v ep="$P2EPOCH" <<'EOF'
SET session_replication_role = replica;
SET synchronous_commit = off;
SET maintenance_work_mem = '512MB';
CREATE UNLOGGED TABLE syn AS
SELECT s.*, av.id AS avail, lk.id AS locked
FROM (SELECT g,
             1000 + g AS seq,
             ((g % 12) + 1)::smallint AS market_id,
             ((g % 2) + 1)::bigint AS user_id,
             ((g % 12) + 2)::smallint AS asset_id,
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
       (SELECT count(*) FROM orders) AS orders, (SELECT count(*) FROM markets) AS markets;
SELECT 'pending_by_market', string_agg(m || ':' || n, ',' ORDER BY m) FROM (
  SELECT c.market_id AS m, count(*) AS n FROM command_journal c
   WHERE NOT EXISTS (SELECT 1 FROM event_emission_registry e WHERE e.source_cmd_seq = c.seq) GROUP BY 1) t;
SELECT 'leaves', t.rel, count(*) FROM (VALUES ('command_journal'), ('event_journal'), ('ledger_entries'),
       ('ledger_transactions'), ('orders'), ('trades')) AS t(rel)
  CROSS JOIN LATERAL pg_partition_tree(t.rel::regclass) p WHERE p.isleaf GROUP BY t.rel ORDER BY t.rel;
SELECT 'size', pg_size_pretty(pg_database_size('scale'));
EOF
echo "== 끝(적재)"
