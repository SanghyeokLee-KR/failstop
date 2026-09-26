#!/bin/sh
# 컨테이너 안(s7-pg-review-probe.sh 가 부른다). ADR 0034 S7 설계 검토 대응의 확인이다(설계 3절 R7, 11절).
#   R7-1 lease_row_missing 은 외래 키 때문에 보통 경로로는 만들 수 없고, 트리거를 끈 세션(session_replication_role = replica)에서는
#        그 사유 한 줄만 난다(검토 지적 11).
#   R7-2 고친 ⑯(C22' 목록 판, 구간 경계 팔에 snapshots)과 수 판(c22p-count-r2.sql)이 P2·P3 행에서 0 이고 행 수가 같다.
#   R7-3 2->3 뒤 새 트랜잭션이 P2 구간 epoch 로 쓴 스냅샷(V6)과 명령(V3)은 구간 경계 팔이 센다(검토 지적 2).
#   R7-4 3->2 를 가로지른 트랜잭션(전환 전에 시작, 전환 뒤 커밋, 쓰기 판정을 거치지 않은 소유자 INSERT)은 구간 경계 팔이 세지 못한다.
#        같은 마켓에 먼저 커밋된 새 구간 행이 있으면 마켓 안 팔이 센다. 구간 경계 증분(segment-old-rows.sql)은 둘 다 센다(검토 지적 9).
#   R7-5 3->2 뒤 P3 epoch 로 쓴 event_journal·trades 행은 C22'·C30 이 세지 못하고 구간 경계 증분이 센다. 같은 epoch 의 스냅샷은
#        구간 경계 팔이 센다(검토 지적 2).
# 쓰기 행은 소유자가 흉내 함수로 직접 넣는다. 쓰기 판정을 우회한 결함(감사가 잡아야 할 것)의 흉내다. 로그인은 소유자 postgres 하나다.
V='\set VERBOSITY verbose
\set SHOW_CONTEXT never'
su() { psql -X -U postgres -d probe -At -v ON_ERROR_STOP=0 "$@" 2>&1; }
blockers() { su -c "SELECT '  blocker ' || blocker || ' | ' || detail FROM leader_protocol_blockers($1::smallint)"; echo "  (blockers($1) 끝)"; }
switch() { # switch <target> [true]
  su <<EOF
$V
SELECT 'retired ' || out_resource || ' ' || out_retired_epoch || ' P' || out_protocol FROM set_leader_protocol($1::smallint${2:+, $2});
EOF
}
audit() { su -c "SELECT probe.audit('$1')"; }
val() { su -q -c "$1" | tr -d ' \r\n'; }

# ── R7-0 적용 ────────────────────────────────────────────────────────
for f in $(ls /c/migration/V*.sql | sed -E 's|.*/V([0-9]+)__.*|\1 &|' | sort -n | cut -d' ' -f2); do
  psql -X -U postgres -d probe -q -1 -v ON_ERROR_STOP=1 -f "$f" >/dev/null 2>/tmp/mig.err \
    || { echo "MIGRATION FAILED: $f"; cat /tmp/mig.err; exit 1; }
done
echo "== R7-0 applied repository migrations: $(ls /c/migration/V*.sql | wc -l) files"
# 자산 넷, 마켓 셋(1 KRW-BTC -> p0, 2 KRW-ETH -> p1, 3 KRW-XRP -> p1), 시스템 계정 셋(샤드 0).
su -q <<'EOF'
INSERT INTO assets (id, symbol, name, kind, scale) VALUES (1, 'KRW', 'KRW', 'FIAT', 0), (2, 'BTC', 'BTC', 'CRYPTO', 8),
       (3, 'ETH', 'ETH', 'CRYPTO', 18), (4, 'XRP', 'XRP', 'CRYPTO', 2);
INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard)
SELECT 'SYSTEM', 0, p.purpose, a.id, 0 FROM assets a CROSS JOIN (VALUES ('FEE_INCOME'), ('GENESIS'), ('GATEWAY')) p(purpose);
INSERT INTO markets (id, symbol, base_asset_id, quote_asset_id, tick_size, lot_size, min_notional, max_notional, max_qty)
VALUES (1, 'KRW-BTC', 2, 1, 1000, 0.00000001, 5000, 1000000000, 1000),
       (2, 'KRW-ETH', 3, 1, 1000, 0.00000001, 5000, 1000000000, 100000),
       (3, 'KRW-XRP', 4, 1, 1, 0.01, 5000, 1000000000, 100000000);
EOF
psql -X -U postgres -d probe -q -1 -v ON_ERROR_STOP=1 -f /c/v55.sql 2>&1
echo "v55 exit=$?"
su -c "SELECT 'FEE_INCOME shards: ' || string_agg(asset_id || ':' || shard, ',' ORDER BY asset_id, shard) FROM accounts WHERE purpose = 'FEE_INCOME'"
su -c "SELECT 'partition map: ' || string_agg(market_id || ':' || resource, ',' ORDER BY market_id) FROM market_lease_partition"
su -q -c "CREATE SCHEMA probe"
{ echo "CREATE VIEW probe.c22p AS"; sed -e 's/;[[:space:]]*--.*$//' -e 's/;[[:space:]]*$//' /c/c22p.sql; } > /tmp/c22p-view.sql
{ echo "CREATE VIEW probe.c30 AS"; sed -e 's/;[[:space:]]*--.*$//' -e 's/;[[:space:]]*$//' /c/c30.sql; } > /tmp/c30-view.sql
{ echo "CREATE VIEW probe.c22p_count AS SELECT ("; sed -e 's/;[[:space:]]*$//' /c/c22p-count.sql; echo ") AS n"; } > /tmp/c22pn-view.sql
{ echo "CREATE VIEW probe.seg_old AS"; sed -e 's/;[[:space:]]*$//' /c/segment-old-rows.sql; } > /tmp/seg-view.sql
for v in c22p-view c30-view c22pn-view seg-view; do
  psql -X -U postgres -d probe -q -v ON_ERROR_STOP=1 -f /tmp/$v.sql 2>&1 && echo "view $v ok"
done
su -c "SELECT 'c22p columns: ' || string_agg(attname, ',' ORDER BY attnum) FROM pg_attribute WHERE attrelid = 'probe.c22p'::regclass AND attnum > 0"
# 흉내 함수. cmd 는 created_at 을 호출 시각(clock_timestamp)으로, cmd_now 는 DEFAULT now()(트랜잭션 시작 시각)로 둔다.
su -q <<'EOF'
CREATE FUNCTION probe.cmd(p_market SMALLINT, p_epoch BIGINT) RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE v_seq BIGINT := next_market_command_seq(p_market);
BEGIN
  INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id, assigned_order_id, engine_ts, tie_break,
                               payload, fencing_token, checksum, created_at)
  VALUES (v_seq, p_market, 'CANCEL', 1, 'p' || (v_seq % 1000000000000), v_seq % 1000000000000, 0, 0, '{}'::jsonb, p_epoch, 0,
          clock_timestamp());
  PERFORM pg_sleep(0.002);
  RETURN v_seq;
END $$;
CREATE FUNCTION probe.cmd_now(p_market SMALLINT, p_epoch BIGINT) RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE v_seq BIGINT := next_market_command_seq(p_market);
BEGIN
  INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id, assigned_order_id, engine_ts, tie_break,
                               payload, fencing_token, checksum)
  VALUES (v_seq, p_market, 'CANCEL', 1, 'p' || (v_seq % 1000000000000), v_seq % 1000000000000, 0, 0, '{}'::jsonb, p_epoch, 0);
  RETURN v_seq;
END $$;
CREATE FUNCTION probe.evt(p_market SMALLINT, p_epoch BIGINT, p_src BIGINT) RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE r RECORD;
BEGIN
  SELECT * INTO r FROM next_market_event_seq(p_market);
  INSERT INTO event_journal (event_seq, source_cmd_seq, event_ordinal, event_type, payload, fencing_epoch, created_at,
                             market_id, market_seq)
  VALUES (r.out_event_seq, p_src, 0, 'ACCEPTED', '{}'::jsonb, p_epoch, clock_timestamp(), p_market, r.out_market_seq);
  RETURN r.out_event_seq;
END $$;
CREATE FUNCTION probe.snap(p_market SMALLINT, p_epoch BIGINT) RETURNS BIGINT
LANGUAGE sql AS $$
  INSERT INTO snapshots (market_id, last_applied_seq, state, state_checksum, engine_version, config_hash, format_version, fencing_epoch)
  VALUES (p_market, 0, '\x00'::bytea, 0, 'probe', 'probe', 1, p_epoch) RETURNING snapshot_id
$$;
CREATE FUNCTION probe.trade(p_market SMALLINT, p_epoch BIGINT) RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE r RECORD;
BEGIN
  SELECT * INTO r FROM next_market_event_seq(p_market);
  INSERT INTO trades (id, market_id, maker_order_id, taker_order_id, maker_user_id, taker_user_id, taker_side, price, quantity,
                      fencing_epoch, executed_at, created_at)
  VALUES (r.out_event_seq, p_market, 1, 2, 1, 1, 'BUY', 1, 1, p_epoch, clock_timestamp(), clock_timestamp());
  RETURN r.out_event_seq;
END $$;
CREATE FUNCTION probe.audit(p_label TEXT) RETURNS TEXT
LANGUAGE sql AS $$
  SELECT '  ' || p_label || ': C22p time=' || (SELECT count(*) FROM probe.c22p WHERE arm = 'time')
      || ' market=' || (SELECT count(*) FROM probe.c22p WHERE arm = 'market')
      || ' boundary(cmd)=' || (SELECT count(*) FROM probe.c22p WHERE arm = 'boundary' AND tbl = 'command_journal')
      || ' boundary(snap)=' || (SELECT count(*) FROM probe.c22p WHERE arm = 'boundary' AND tbl = 'snapshots')
      || ' | 수 판=' || (SELECT n FROM probe.c22p_count)
      || ' 같음=' || ((SELECT count(*) FROM probe.c22p) = (SELECT n FROM probe.c22p_count))
      || ' | C30=' || (SELECT count(*) FROM probe.c30)
      || ' | old_rows=' || coalesce((SELECT string_agg(id || ':P' || protocol || ':' || old_rows, ',' ORDER BY id) FROM probe.seg_old), '-')
$$;
EOF

# ── R7-1 lease_row_missing ───────────────────────────────────────────
echo
echo "== R7-1 1->2, 래치 뒤 선행 조건이 없다. 파티션 리스 행은 보통 경로로 지울 수 없고, 트리거를 끈 세션에서 지우면 lease_row_missing 한 줄만 난다"
switch 2
su -c "SELECT 'latched_at ' || latch_command_band_cutover()"
blockers 3
echo "-- 보통 세션의 DELETE(롤백)"
su <<EOF
$V
BEGIN;
DELETE FROM leader_lease WHERE resource = 'matching-p1';
ROLLBACK;
EOF
echo "-- session_replication_role = replica 세션(복원 도구의 트리거 끄기 흉내)의 DELETE 뒤 blockers(3), 롤백"
su <<'EOF'
BEGIN;
SET LOCAL session_replication_role = replica;
DELETE FROM leader_lease WHERE resource = 'matching-p1';
SELECT '  blocker ' || blocker || ' | ' || detail FROM leader_protocol_blockers(3::smallint);
SELECT '  (blockers(3) 끝, 남은 파티션 대응 행 ' || count(*) || ')' FROM market_lease_partition;
ROLLBACK;
EOF
su -c "SELECT 'lease rows after rollback: ' || string_agg(resource, ',' ORDER BY resource) FROM leader_lease"

# ── R7-2 P2 행, 2->3, P3 행 ───────────────────────────────────────────
echo
echo "== R7-2 고친 C22' 목록 판과 수 판은 P2·P3 행에서 0 이고 같다. 구간 경계 증분 표본(old_rows)은 전환 뒤 늘지 않는다"
su -c "SELECT 'me epoch(P2)=' || try_acquire_lease('matching-engine', 'N1', interval '60 seconds', 3::smallint)"
su -q <<'EOF'
SELECT probe.cmd(1::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-engine'));
SELECT probe.cmd(2::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-engine'));
SELECT probe.evt(1::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-engine'), 101);
SELECT probe.snap(1::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-engine'));
SELECT probe.trade(2::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-engine'));
EOF
P2E=$(val "SELECT epoch FROM leader_lease WHERE resource = 'matching-engine'")
echo "  P2 epoch=$P2E"
audit "P2 행 뒤"
su -q -c "SELECT release_lease('matching-engine', 'N1', $P2E)" >/dev/null
switch 3
audit "2->3 직후(노드 시작 전)"
su -c "SELECT 'p0 epoch=' || try_acquire_lease('matching-p0', 'N1', interval '60 seconds', 3::smallint) || ' p1 epoch=' || try_acquire_lease('matching-p1', 'N2', interval '60 seconds', 3::smallint)"
su -q <<'EOF'
SELECT probe.cmd(1::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-p0'));
SELECT probe.cmd(2::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-p1'));
SELECT probe.cmd(1::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-p0'));
SELECT probe.cmd(2::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-p1'));
SELECT probe.evt(1::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-p0'), 102);
SELECT probe.snap(1::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-p0'));
SELECT probe.snap(2::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-p1'));
SELECT probe.trade(2::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-p1'));
EOF
P0E=$(val "SELECT epoch FROM leader_lease WHERE resource = 'matching-p0'")
P1E=$(val "SELECT epoch FROM leader_lease WHERE resource = 'matching-p1'")
echo "  P3 epochs p0=$P0E p1=$P1E"
audit "P3 행 뒤"

# ── R7-3 2->3 뒤 P2 구간 epoch 쓰기 ────────────────────────────────────
echo
echo "== R7-3 2->3 뒤 새 트랜잭션이 P2 구간 epoch 로 쓴 스냅샷(V6)과 행이 없던 마켓 3 의 명령(V3)은 구간 경계 팔이 센다(각각 롤백)"
su -q <<EOF
BEGIN;
SELECT probe.snap(1::smallint, $P2E);
\o /tmp/v6.out
SELECT probe.audit('V6 스냅샷');
\o
ROLLBACK;
BEGIN;
SELECT probe.cmd(3::smallint, $P2E);
\o /tmp/v3.out
SELECT probe.audit('V3 명령');
\o
ROLLBACK;
EOF
cat /tmp/v6.out /tmp/v3.out

# ── R7-4 3->2 를 가로지른 트랜잭션 ─────────────────────────────────────
echo
echo "== R7-4 A 는 3->2 전에 트랜잭션을 열고 6초 뒤 옛 P3 epoch 로 마켓 3(새 구간 행 없음)과 마켓 1(새 구간 행 먼저 커밋)에 쓴다"
( psql -X -U postgres -d probe -At -v ON_ERROR_STOP=1 <<EOF
BEGIN;
SELECT 'A tx_start ' || now();
SELECT pg_sleep(6);
SELECT 'A market3 seq ' || probe.cmd_now(3::smallint, $P1E);
SELECT 'A market1 seq ' || probe.cmd_now(1::smallint, $P0E);
COMMIT;
SELECT 'A committed ' || clock_timestamp();
EOF
) > /tmp/straddle.out 2>&1 &
APID=$!
sleep 2
switch 2 true
su -c "SELECT 'changed_at(3->2) ' || changed_at FROM leader_protocol"
audit "3->2 직후(A 커밋 전)"
su -c "SELECT 'me epoch(P2 되돌린 뒤)=' || try_acquire_lease('matching-engine', 'N1', interval '60 seconds', 3::smallint)"
su -c "SELECT 'new-segment market1 seq ' || probe.cmd(1::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-engine'))"
wait "$APID" || echo "A exit=$?"
cat /tmp/straddle.out
audit "A 커밋 뒤"
su -c "SELECT '  last rows m' || market_id || ' seq=' || seq || ' epoch=' || fencing_token || ' created_before_3to2=' || (created_at < (SELECT changed_at FROM leader_protocol)) FROM (SELECT c.*, row_number() OVER (PARTITION BY c.market_id ORDER BY c.seq DESC) AS rn FROM command_journal c WHERE c.market_id IN (1, 3)) x WHERE rn <= 2 ORDER BY market_id, seq"

# ── R7-5 3->2 뒤 P3 epoch 의 이벤트·체결·스냅샷 ───────────────────────
echo
echo "== R7-5 3->2 뒤 새 트랜잭션이 P3 epoch 로 쓴 이벤트·체결은 C22'·C30 이 세지 못하고 구간 경계 증분이 센다. 스냅샷은 구간 경계 팔이 센다(롤백)"
su -q <<EOF
BEGIN;
SELECT probe.evt(2::smallint, $P1E, 901);
SELECT probe.trade(1::smallint, $P0E);
\o /tmp/v8.out
SELECT probe.audit('V8 이벤트·체결');
\o
SELECT probe.snap(2::smallint, $P1E);
\o /tmp/v9.out
SELECT probe.audit('V9 + 스냅샷');
\o
ROLLBACK;
EOF
cat /tmp/v8.out /tmp/v9.out
audit "끝(롤백 뒤)"
su -c "SELECT 'history: ' || string_agg(id || ':P' || protocol || ':floor=' || epoch_floor, ' ' ORDER BY id) FROM leader_protocol_history"
