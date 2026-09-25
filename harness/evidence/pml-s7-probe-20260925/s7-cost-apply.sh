#!/bin/sh
# 컨테이너 안(s7-cost-probe.sh 가 부른다). V0~V54 와 V55 를 올리고 P1 -> P2 -> 래치 -> P3 -> P2 -> P3 이력과 세 구간의 획득을
# 만든 뒤, 명령 저널에 합성 행 N 개를 구간 epoch 와 마켓 12 개에 나눠 넣고 옛 C22, C22' 판 둘, C30 문장의 실행 시간을 잰다.
# 합성 행은 소유자가 직접 넣는다(채번 함수를 거치지 않아 카운터와 어긋나지만 비용 표본에는 상관없다). 판정이 아니라 표본이다.
N="${1:-200000}"
su() { psql -X -U postgres -d probe -At -v ON_ERROR_STOP=1 "$@" 2>&1; }
for f in $(ls /c/migration/V*.sql | sed -E 's|.*/V([0-9]+)__.*|\1 &|' | sort -n | cut -d' ' -f2); do
  psql -X -U postgres -d probe -q -1 -v ON_ERROR_STOP=1 -f "$f" >/dev/null 2>/tmp/mig.err \
    || { echo "MIGRATION FAILED: $f"; cat /tmp/mig.err; exit 1; }
done
psql -X -U postgres -d probe -q -1 -v ON_ERROR_STOP=1 -f /c/v55.sql >/dev/null
echo "applied V0~V55"
su -q <<'EOF'
INSERT INTO assets (id, symbol, name, kind, scale) SELECT g, 'A' || g, 'A' || g, CASE WHEN g = 1 THEN 'FIAT' ELSE 'CRYPTO' END, 8 FROM generate_series(1, 13) g;
INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard) SELECT 'SYSTEM', 0, 'FEE_INCOME', g, 0 FROM generate_series(1, 13) g;
INSERT INTO markets (id, symbol, base_asset_id, quote_asset_id, tick_size, lot_size, min_notional, max_notional, max_qty)
SELECT g, 'KRW-A' || (g + 1), g + 1, 1, 1, 0.00000001, 0, 1000000000, 1000000 FROM generate_series(1, 12) g;
SELECT count(*) FROM set_leader_protocol(2::smallint);
SELECT try_acquire_lease('matching-engine', 'N1', interval '1 hour', 2::smallint);
SELECT latch_command_band_cutover();
SELECT count(*) FROM set_leader_protocol(3::smallint);
SELECT try_acquire_lease('matching-p0', 'N1', interval '1 hour', 3::smallint);
SELECT try_acquire_lease('matching-p1', 'N2', interval '1 hour', 3::smallint);
EOF
su -c "SELECT 'history ' || string_agg(id || ':P' || protocol || ':' || epoch_floor, ' ' ORDER BY id) FROM leader_protocol_history"
su -c "SELECT 'leases ' || string_agg(resource || ':' || epoch, ' ' ORDER BY resource) FROM leader_lease"
# P2 구간 행 절반(epoch = P2 의 matching-engine 획득), P3 구간 행 절반(그 마켓 파티션의 획득 epoch). created_at 은 구간 순서를 지킨다.
su -q <<EOF
INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id, assigned_order_id, engine_ts, tie_break,
                             payload, fencing_token, checksum, created_at)
SELECT m * 1000000000000 + (g / 12) + 1, m, 'CANCEL', 1, NULL, g, 0, 0, '{}'::jsonb,
       CASE WHEN g < $N / 2 THEN (SELECT max(epoch) FROM lease_acquisitions WHERE protocol = 2 AND origin = 'ACQUIRE')
            WHEN m IN (1, 4, 5, 7, 9, 11) THEN (SELECT epoch FROM leader_lease WHERE resource = 'matching-p0')
            ELSE (SELECT epoch FROM leader_lease WHERE resource = 'matching-p1') END,
       0,
       CASE WHEN g < $N / 2 THEN (SELECT changed_at FROM leader_protocol_history WHERE protocol = 3) - interval '1 hour' + g * interval '1 ms'
            ELSE (SELECT changed_at FROM leader_protocol_history WHERE protocol = 3) + g * interval '1 ms' END
  FROM generate_series(0, $N - 1) g CROSS JOIN LATERAL (SELECT (g % 12 + 1)::smallint AS m) mm;
ANALYZE command_journal;
EOF
su -c "SELECT 'rows=' || count(*) || ' leaves=' || (SELECT count(*) FROM pg_inherits WHERE inhparent = 'command_journal'::regclass) FROM command_journal"
for q in /c/q_*.sql; do
  name=$(basename "$q" .sql)
  su -f "$q" > /tmp/out.txt
  echo "$name result=$(head -1 /tmp/out.txt)"
  for i in 1 2 3; do
    { echo 'SET jit = off;'; echo 'EXPLAIN (ANALYZE, BUFFERS OFF, TIMING OFF, SUMMARY ON)'; cat "$q"; } > /tmp/e.sql
    su -f /tmp/e.sql | grep 'Execution Time' | sed "s/^/  $name run$i /"
  done
done
