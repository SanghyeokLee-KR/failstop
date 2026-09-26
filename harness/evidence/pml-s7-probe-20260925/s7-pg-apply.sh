#!/bin/sh
# 컨테이너 안(s7-pg-probe.sh 가 부른다). 저장소의 실제 V0~V54 를 파일마다 한 트랜잭션으로 올리고, 옛 자산·계정을 흉내 낸 뒤
# V55 SQL(/c/v55.sql)을 올려 ADR 0034 S7 설계 3절 P7-0~P7-6 을 확인한다. C22'·C30 은 /c/c22p.sql·/c/c30.sql 을 읽는다.
# 로그인: appu(app_role 소속 앱 로그인), hr(harness_role 소속 조회 로그인). 소유자는 postgres.
V='\set VERBOSITY verbose
\set SHOW_CONTEXT never'
su() { psql -X -U postgres -d probe -At -v ON_ERROR_STOP=0 "$@" 2>&1; }
app() { psql -X -U appu -d probe -At -v ON_ERROR_STOP=0 "$@" 2>&1; }
hr() { psql -X -U hr -d probe -At -v ON_ERROR_STOP=0 "$@" 2>&1; }
blockers() { su -c "SELECT '  blocker ' || blocker || ' | ' || detail FROM leader_protocol_blockers($1::smallint)"; echo "  (blockers($1) 끝)"; }
switch() { # switch <target> [true]
  su <<EOF
$V
SELECT 'retired ' || out_resource || ' ' || out_retired_epoch || ' P' || out_protocol FROM set_leader_protocol($1::smallint${2:+, $2});
EOF
}
state() { su -c "SELECT 'protocol=' || (SELECT protocol FROM leader_protocol) || ' lease=' || (SELECT string_agg(resource || ':' || holder || ':' || epoch || ':' || (lease_until > clock_timestamp()), ' ' ORDER BY resource) FROM leader_lease)"; }
c22() { su -c "SELECT '  C22(옛) time=' || (SELECT count(*) FROM probe.c22_old WHERE arm = 'time') || ' market=' || (SELECT count(*) FROM probe.c22_old WHERE arm = 'market') || ' | C22'' time=' || (SELECT count(*) FROM probe.c22p WHERE arm = 'time') || ' market=' || (SELECT count(*) FROM probe.c22p WHERE arm = 'market') || ' boundary=' || (SELECT count(*) FROM probe.c22p WHERE arm = 'boundary') || ' | C30=' || (SELECT count(*) FROM probe.c30) || coalesce(' (' || (SELECT string_agg(tbl || ':' || n, ',' ORDER BY tbl) FROM (SELECT tbl, count(*) n FROM probe.c30 GROUP BY tbl) x) || ')', '') || ' | rows=' || (SELECT count(*) FROM command_journal)"; }

# ── P7-0 적용 ────────────────────────────────────────────────────────
for f in $(ls /c/migration/V*.sql | sed -E 's|.*/V([0-9]+)__.*|\1 &|' | sort -n | cut -d' ' -f2); do
  psql -X -U postgres -d probe -q -1 -v ON_ERROR_STOP=1 -f "$f" >/dev/null 2>/tmp/mig.err \
    || { echo "MIGRATION FAILED: $f"; cat /tmp/mig.err; exit 1; }
done
echo "== P7-0 applied repository migrations: $(ls /c/migration/V*.sql | wc -l) files (max $(ls /c/migration | sed -E 's/V([0-9]+)__.*/\1/' | sort -n | tail -1))"
# V55 전의 DB 를 흉내 낸다. 자산 셋, 마켓 둘(1 KRW-BTC -> p0, 2 KRW-ETH -> p1), 시스템 계정 셋(샤드 0), 사용자 하나.
su -q <<'EOF'
INSERT INTO assets (id, symbol, name, kind, scale) VALUES (1, 'KRW', 'KRW', 'FIAT', 0), (2, 'BTC', 'BTC', 'CRYPTO', 8), (3, 'ETH', 'ETH', 'CRYPTO', 18);
INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard)
SELECT 'SYSTEM', 0, p.purpose, a.id, 0 FROM assets a CROSS JOIN (VALUES ('FEE_INCOME'), ('GENESIS'), ('GATEWAY')) p(purpose);
INSERT INTO markets (id, symbol, base_asset_id, quote_asset_id, tick_size, lot_size, min_notional, max_notional, max_qty)
VALUES (1, 'KRW-BTC', 2, 1, 1000, 0.00000001, 5000, 1000000000, 1000),
       (2, 'KRW-ETH', 3, 1, 1000, 0.00000001, 5000, 1000000000, 100000);
INSERT INTO users (email) VALUES ('s7@probe.local');
CREATE ROLE appu LOGIN;
GRANT app_role TO appu;
CREATE ROLE hr LOGIN;
GRANT harness_role TO hr;
EOF
su -c "SELECT 'before V55 FEE_INCOME shards: ' || string_agg(asset_id || ':' || shard, ',' ORDER BY asset_id, shard) FROM accounts WHERE purpose = 'FEE_INCOME'"
psql -X -U postgres -d probe -q -1 -v ON_ERROR_STOP=1 -f /c/v55.sql 2>&1
echo "v55 exit=$?"
su -c "SELECT 'after V55 FEE_INCOME shards: ' || string_agg(a.asset_id || ':' || a.shard || ':bal=' || b.balance, ',' ORDER BY a.asset_id, a.shard) FROM accounts a JOIN balances b ON b.account_id = a.id WHERE a.purpose = 'FEE_INCOME'"
su -c "SELECT 'GENESIS/GATEWAY shard 1 rows: ' || count(*) FROM accounts WHERE purpose IN ('GENESIS', 'GATEWAY') AND shard = 1"
su -c "SELECT 'trigger: ' || tgname || ' ' || pg_get_triggerdef(t.oid) FROM pg_trigger t WHERE tgname = 'trg_accounts_fee_income_shard'"
su -c "SELECT 'function ' || p.proname || ' secdef=' || p.prosecdef || ' acl=' || coalesce(p.proacl::text, '(default)') FROM pg_proc p WHERE p.proname IN ('seed_fee_income_shard', 'leader_protocol_blockers', 'set_leader_protocol') ORDER BY p.proname"
su -q -c "CREATE SCHEMA probe"
# 옛 C22(DB 설계 §6.4 ⑯ 종전 판)와 초안 C22'·C30 을 뷰로 둔다.
su -q <<'EOF'
CREATE VIEW probe.c22_old AS
SELECT 'time' AS arm, seq FROM (
  SELECT seq, fencing_token, lag(fencing_token) OVER (ORDER BY created_at, seq) AS prev FROM command_journal) t
WHERE prev > fencing_token
UNION ALL
SELECT 'market', seq FROM (
  SELECT seq, fencing_token, lag(fencing_token) OVER (PARTITION BY market_id ORDER BY seq) AS prev FROM command_journal) t
WHERE prev > fencing_token;
EOF
{ echo "CREATE VIEW probe.c22p AS"; sed -e 's/;[[:space:]]*--.*$//' -e 's/;[[:space:]]*$//' /c/c22p.sql; } > /tmp/c22p-view.sql
{ echo "CREATE VIEW probe.c30 AS"; sed -e 's/;[[:space:]]*--.*$//' -e 's/;[[:space:]]*$//' /c/c30.sql; } > /tmp/c30-view.sql
psql -X -U postgres -d probe -q -v ON_ERROR_STOP=1 -f /tmp/c22p-view.sql 2>&1 && echo "view c22p ok"
psql -X -U postgres -d probe -q -v ON_ERROR_STOP=1 -f /tmp/c30-view.sql 2>&1 && echo "view c30 ok"
# 명령·이벤트·스냅샷·체결 행을 소유자로 직접 넣는 흉내 함수. created_at 은 호출 때의 시각이다.
su -q <<'EOF'
CREATE FUNCTION probe.cmd(p_market SMALLINT, p_epoch BIGINT, p_type TEXT DEFAULT 'CANCEL') RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE v_seq BIGINT := next_market_command_seq(p_market);
BEGIN
  INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id, assigned_order_id, engine_ts, tie_break,
                               payload, fencing_token, checksum, created_at)
  VALUES (v_seq, p_market, p_type, 1, 'p' || (v_seq % 1000000000000), v_seq % 1000000000000, 0, 0, '{}'::jsonb, p_epoch, 0,
          clock_timestamp());
  PERFORM pg_sleep(0.002);
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
EOF

# ── P7-1 수수료 샤드 트리거 ───────────────────────────────────────────
echo
echo "== P7-1 앱 로그인이 자산 4 와 그 FEE_INCOME 샤드 0 을 넣으면 샤드 1 이 같이 생긴다. 같은 행 재삽입은 ON CONFLICT 로 넘긴다"
app <<'EOF'
\set VERBOSITY verbose
\set SHOW_CONTEXT never
INSERT INTO assets (id, symbol, name, kind, scale) VALUES (4, 'XRP', 'XRP', 'CRYPTO', 2);
INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard) VALUES ('SYSTEM', 0, 'FEE_INCOME', 4, 0), ('SYSTEM', 0, 'GENESIS', 4, 0);
INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard) VALUES ('SYSTEM', 0, 'FEE_INCOME', 4, 0)
ON CONFLICT (owner_type, owner_id, purpose, asset_id, shard) DO NOTHING;
SELECT 'asset 4 system accounts: ' || string_agg(purpose || ':' || shard, ',' ORDER BY purpose, shard) FROM accounts WHERE asset_id = 4;
EOF
echo "-- 앱 로그인이 트리거 함수를 자기 임시 표에 걸 수 없다(EXECUTE 회수)"
app <<'EOF'
\set VERBOSITY verbose
\set SHOW_CONTEXT never
CREATE TEMP TABLE t_probe (owner_type text, purpose text, asset_id smallint, shard smallint);
CREATE TRIGGER t_probe_trg AFTER INSERT ON t_probe FOR EACH ROW EXECUTE FUNCTION seed_fee_income_shard();
EOF
echo "-- 조회 로그인은 선행 조건 함수를 부르고 전환 함수는 부르지 못한다. 앱 로그인은 둘 다 부르지 못한다"
hr -c "SELECT 'hr blockers(3) rows=' || count(*) FROM leader_protocol_blockers(3::smallint)"
hr -c "SELECT * FROM set_leader_protocol(2::smallint)"
app -c "SELECT count(*) FROM leader_protocol_blockers(3::smallint)"
app -c "SELECT * FROM set_leader_protocol(2::smallint)"

# ── P7-2 선행 조건과 2->3 ─────────────────────────────────────────────
echo
echo "== P7-2 P1 에서 1->3 은 전이 거부. 1->2 뒤 선행 조건을 하나씩 어기고 푼다"
state
blockers 3
switch 3
switch 2
state
blockers 3
echo "-- 래치 없이 2->3"
switch 3
su -c "SELECT 'latched_at ' || latch_command_band_cutover()"
blockers 3
echo "-- 정산되지 않은 NEW 하나(P2 리더 N1 의 epoch 로)"
su -c "SELECT 'me epoch(P2)=' || try_acquire_lease('matching-engine', 'N1', interval '60 seconds', 2::smallint)"
su -q -c "SELECT probe.cmd(1::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-engine'), 'NEW')" > /tmp/newseq
NEWSEQ=$(tr -d ' \r\n' < /tmp/newseq)
echo "  NEW seq=$NEWSEQ"
blockers 3
blockers 2
su -q -c "SELECT probe.evt(1::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-engine'), $NEWSEQ)" >/dev/null
echo "  (ACCEPTED 이벤트로 정산 흉내)"
blockers 3
echo "-- 수수료 샤드 1 이 없는 자산(트리거를 잠시 끄고 자산 5·마켓 3 을 넣음)"
su -q <<'EOF'
ALTER TABLE accounts DISABLE TRIGGER trg_accounts_fee_income_shard;
INSERT INTO assets (id, symbol, name, kind, scale) VALUES (5, 'SOL', 'SOL', 'CRYPTO', 6);
INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard) VALUES ('SYSTEM', 0, 'FEE_INCOME', 5, 0);
ALTER TABLE accounts ENABLE TRIGGER trg_accounts_fee_income_shard;
INSERT INTO markets (id, symbol, base_asset_id, quote_asset_id, tick_size, lot_size, min_notional, max_notional, max_qty)
VALUES (3, 'KRW-SOL', 5, 1, 50, 0.000001, 5000, 1000000000, 10000000);
EOF
blockers 3
su -q -c "INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard) VALUES ('SYSTEM', 0, 'FEE_INCOME', 5, 1)"
echo "-- 대응·카운터·대역 잎이 없는 마켓(시드 트리거를 끄고 마켓 6 을 넣음)"
su -q <<'EOF'
ALTER TABLE markets DISABLE TRIGGER trg_markets_seed_lease_partition;
ALTER TABLE markets DISABLE TRIGGER trg_markets_seed_counters;
INSERT INTO markets (id, symbol, base_asset_id, quote_asset_id, tick_size, lot_size, min_notional, max_notional, max_qty)
VALUES (6, 'KRW-ADA', 4, 1, 1, 0.01, 5000, 1000000000, 100000000);
ALTER TABLE markets ENABLE TRIGGER trg_markets_seed_lease_partition;
ALTER TABLE markets ENABLE TRIGGER trg_markets_seed_counters;
EOF
blockers 3
su -q <<'EOF'
INSERT INTO market_lease_partition (market_id, resource) VALUES (6, default_lease_partition(6::smallint));
INSERT INTO market_journal_counters (market_id, name, next_value) VALUES (6, 'command_seq', 1), (6, 'event_seq', 1);
SELECT ensure_command_band_partition(6::smallint);
SELECT ensure_event_band_partition(6::smallint);
EOF
blockers 3
echo "-- app_role 에 leader_lease UPDATE(표 전체, 열 하나)와 command_journal INSERT 를 되돌려 준 경우"
su -q -c "GRANT UPDATE ON leader_lease TO app_role"
blockers 3
su -q -c "REVOKE UPDATE ON leader_lease FROM app_role"
su -q -c "GRANT UPDATE (holder) ON leader_lease TO app_role"
blockers 3
su -q -c "REVOKE UPDATE (holder) ON leader_lease FROM app_role"
su -q -c "GRANT INSERT ON command_journal TO app_role"
blockers 3
su -q -c "REVOKE INSERT ON command_journal FROM app_role"
blockers 3
echo "-- 모두 풀린 뒤 2->3"
su -c "SELECT 'history before: ' || string_agg(id || ':P' || protocol || ':floor=' || epoch_floor, ' ' ORDER BY id) FROM leader_protocol_history"
switch 3
state
su -c "SELECT 'history after: ' || string_agg(id || ':P' || protocol || ':floor=' || epoch_floor, ' ' ORDER BY id) FROM leader_protocol_history"
su -c "SELECT 'resource for market 1=' || lease_resource_for_market(1::smallint) || ' 2=' || lease_resource_for_market(2::smallint) || ' 6=' || lease_resource_for_market(6::smallint)"

# ── P7-3 P3 획득과 전이 규칙 ──────────────────────────────────────────
echo
echo "== P7-3 P3 에서 최고 모드 2 획득과 matching-engine 획득은 거부, 최고 모드 3 은 두 파티션을 얻는다. 3->2 는 확인 인자가 필요하다"
su <<'EOF'
\set VERBOSITY verbose
\set SHOW_CONTEXT never
SELECT try_acquire_lease('matching-p1', 'N2', interval '30 seconds', 2::smallint);
SELECT try_acquire_lease('matching-engine', 'N1', interval '30 seconds', 3::smallint);
SELECT 'p0 epoch=' || try_acquire_lease('matching-p0', 'N1', interval '60 seconds', 3::smallint);
SELECT 'p1 epoch=' || try_acquire_lease('matching-p1', 'N2', interval '60 seconds', 3::smallint);
SELECT 'acq ' || epoch || ' ' || resource || ' ' || holder || ' P' || protocol || ' ' || origin FROM lease_acquisitions ORDER BY epoch DESC LIMIT 5;
EOF
state
echo "-- 시장 도출 fencing: p0 의 holder·epoch 로 p1 마켓(2) 행을 넣으면 0행"
su -c "SELECT 'append p1 market with p0 lease rows=' || append_command_journal_row(next_market_command_seq(2::smallint), 2::smallint, 'CANCEL', 1, NULL, 1, 0, 0, '{}'::jsonb, 0, 'N1', (SELECT epoch FROM leader_lease WHERE resource = 'matching-p0'))"
su -c "SELECT 'append p0 market with p0 lease rows=' || append_command_journal_row(next_market_command_seq(1::smallint), 1::smallint, 'CANCEL', 1, NULL, 1, 0, 0, '{}'::jsonb, 0, 'N1', (SELECT epoch FROM leader_lease WHERE resource = 'matching-p0'))"
switch 1 true
switch 3
switch 2

# ── P7-4 전환은 진행 중 쓰기를 기다리고 5초에 멈춘다 ───────────────────
echo
echo "== P7-4 p1 행에 KEY SHARE 를 쥔 세션이 7초 머무는 동안 3->2 는 5초 뒤 55P03 으로 끝나고 아무것도 바꾸지 않는다"
( su > /tmp/ks.out <<'EOF'
BEGIN;
SELECT 'ks epoch=' || epoch FROM leader_lease WHERE resource = 'matching-p1' FOR KEY SHARE;
SELECT pg_sleep(7);
COMMIT;
EOF
) &
KS=$!
sleep 0.7
su <<'EOF'
\set VERBOSITY verbose
\set SHOW_CONTEXT never
\timing on
SELECT * FROM set_leader_protocol(2::smallint, true);
EOF
wait $KS
cat /tmp/ks.out
state

# ── P7-5 C22'·C30 ────────────────────────────────────────────────────
echo
echo "== P7-5 C22'·C30. P3 두 파티션 행을 번갈아 넣으면 옛 C22 시간 축 팔은 거짓 위반을 내고 C22' 는 0 이다"
c22
su -q <<'EOF'
SELECT probe.cmd(1::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-p0')) FROM generate_series(1, 1);
SELECT probe.cmd(2::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-p1')) FROM generate_series(1, 1);
SELECT probe.cmd(1::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-p0')) FROM generate_series(1, 1);
SELECT probe.cmd(2::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-p1')) FROM generate_series(1, 1);
SELECT probe.cmd(1::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-p0')) FROM generate_series(1, 1);
SELECT probe.evt(1::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-p0'), 1);
SELECT probe.evt(2::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-p1'), 2);
SELECT probe.snap(1::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-p0'));
SELECT probe.trade(2::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-p1'));
EOF
c22
echo "-- 주입 V1: p0 를 N2 가 새 epoch 로 넘겨받은 뒤 옛 p0 epoch 로 쓴 행(같은 파티션의 역행)"
su -q -c "SELECT epoch FROM leader_lease WHERE resource = 'matching-p0'" > /tmp/oldp0
OLDP0=$(tr -d ' \r\n' < /tmp/oldp0)
su -q -c "SELECT release_lease('matching-p0', 'N1', $OLDP0)" >/dev/null
su -c "SELECT 'p0 epoch(N2)=' || try_acquire_lease('matching-p0', 'N2', interval '60 seconds', 3::smallint)"
su -q -c "SELECT probe.cmd(1::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-p0'))" >/dev/null
c22
su -q <<EOF
BEGIN;
SELECT probe.cmd(1::smallint, $OLDP0);
\o /tmp/v1.out
SELECT 'V1 ' || arm || ' ' || count(*) FROM probe.c22p GROUP BY arm;
\o
ROLLBACK;
EOF
cat /tmp/v1.out
echo "-- 주입 V2: p1 마켓(2) 행을 p0 epoch 로(명령·이벤트·스냅샷·체결 넷)"
su -q <<'EOF'
BEGIN;
SELECT probe.cmd(2::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-p0'));
SELECT probe.evt(2::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-p0'), 3);
SELECT probe.snap(2::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-p0'));
SELECT probe.trade(2::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-p0'));
\o /tmp/v2.out
SELECT 'V2 C30 ' || tbl || ' ' || count(*) FROM probe.c30 GROUP BY tbl ORDER BY tbl;
SELECT 'V2 C22p ' || arm || ' ' || count(*) FROM probe.c22p GROUP BY arm ORDER BY arm;
\o
ROLLBACK;
EOF
cat /tmp/v2.out
echo "-- 주입 V3: P2 구간 epoch(퇴역 전 matching-engine 획득)로 P3 에서 쓴 행(구간 경계)"
su -q -c "SELECT max(epoch) FROM lease_acquisitions WHERE resource = 'matching-engine' AND protocol = 2 AND origin = 'ACQUIRE'" > /tmp/p2e
P2E=$(tr -d ' \r\n' < /tmp/p2e)
echo "  P2 ACQUIRE epoch=$P2E (없으면 아래에서 P2 로 내려 만든다)"
if [ -z "$P2E" ]; then
  su -q -c "SELECT release_lease('matching-p0', 'N2', (SELECT epoch FROM leader_lease WHERE resource = 'matching-p0'))" >/dev/null
  su -q -c "SELECT release_lease('matching-p1', 'N2', (SELECT epoch FROM leader_lease WHERE resource = 'matching-p1'))" >/dev/null
  switch 2 true
  su -c "SELECT 'me epoch(P2)=' || try_acquire_lease('matching-engine', 'N1', interval '60 seconds', 2::smallint)"
  su -q -c "SELECT probe.cmd(1::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-engine'))" >/dev/null
  su -q -c "SELECT probe.cmd(2::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-engine'))" >/dev/null
  su -q -c "SELECT release_lease('matching-engine', 'N1', (SELECT epoch FROM leader_lease WHERE resource = 'matching-engine'))" >/dev/null
  switch 3
  su -c "SELECT 'p0 epoch=' || try_acquire_lease('matching-p0', 'N1', interval '60 seconds', 3::smallint) || ' p1 epoch=' || try_acquire_lease('matching-p1', 'N2', interval '60 seconds', 3::smallint)"
  su -q -c "SELECT probe.cmd(1::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-p0'))" >/dev/null
  su -q -c "SELECT probe.cmd(2::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-p1'))" >/dev/null
  su -q -c "SELECT max(epoch) FROM lease_acquisitions WHERE resource = 'matching-engine' AND protocol = 2 AND origin = 'ACQUIRE'" > /tmp/p2e
  P2E=$(tr -d ' \r\n' < /tmp/p2e)
  echo "  P2 ACQUIRE epoch=$P2E"
fi
c22
echo "  (마켓 3 에는 행이 없다. 마켓 안 팔이 볼 앞 행이 없어 구간 경계 팔만 이 행을 잡는다)"
su -q <<EOF
BEGIN;
SELECT probe.cmd(3::smallint, $P2E);
\o /tmp/v3.out
SELECT 'V3 C22p ' || arm || ' ' || count(*) FROM probe.c22p GROUP BY arm ORDER BY arm;
SELECT 'V3 C30 ' || count(*) FROM probe.c30;
SELECT 'V3 C22old ' || arm || ' ' || count(*) FROM probe.c22_old GROUP BY arm ORDER BY arm;
\o
ROLLBACK;
EOF
cat /tmp/v3.out
echo "-- 주입 V4·V5: P3 퇴역 epoch 로 쓴 행, 획득 이력이 없는 P3 범위 epoch 로 쓴 행"
su -q <<'EOF'
BEGIN;
SELECT probe.cmd(1::smallint, (SELECT max(epoch) FROM lease_acquisitions WHERE resource = 'matching-p0' AND origin = 'RETIRE' AND protocol = 3));
\o /tmp/v4.out
SELECT 'V4 C30 ' || count(*) FROM probe.c30;
\o
ROLLBACK;
BEGIN;
SELECT probe.cmd(1::smallint, (SELECT max(epoch) + 1000 FROM lease_acquisitions));
\o /tmp/v5.out
SELECT 'V5 C30 ' || count(*) FROM probe.c30;
SELECT 'V5 C22p ' || arm || ' ' || count(*) FROM probe.c22p GROUP BY arm ORDER BY arm;
\o
ROLLBACK;
EOF
cat /tmp/v4.out /tmp/v5.out
echo "-- 되돌리기(3->2, 확인 인자) 뒤 P2 행을 더해도 C22'·C30 은 0 이다"
su -q -c "SELECT release_lease(resource, holder, epoch) FROM leader_lease WHERE resource IN ('matching-p0', 'matching-p1')" >/dev/null
switch 2 true
su -c "SELECT 'me epoch(P2)=' || try_acquire_lease('matching-engine', 'N1', interval '60 seconds', 2::smallint)"
su -q <<'EOF'
SELECT probe.cmd(1::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-engine'));
SELECT probe.cmd(2::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-engine'));
SELECT probe.cmd(1::smallint, (SELECT epoch FROM leader_lease WHERE resource = 'matching-engine'));
EOF
c22
su -c "SELECT 'history: ' || string_agg(id || ':P' || protocol || ':floor=' || epoch_floor, ' ' ORDER BY id) FROM leader_protocol_history"
su -c "SELECT 'fee shards (resource of shard): ' || string_agg(asset_id || ':' || shard, ',' ORDER BY asset_id, shard) FROM accounts WHERE purpose = 'FEE_INCOME'"

# ── P7-6 비용 표본(판정 아님) ─────────────────────────────────────────
echo
echo "== P7-6 비용 표본: 한 트랜잭션에 명령 20만 행을 더해 옛 C22, C22', C30 문장의 실행 시간을 잰다(롤백, 1,000,000행 판정은 NOT RUN)"
su -q <<'EOF'
BEGIN;
INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id, assigned_order_id, engine_ts, tie_break,
                             payload, fencing_token, checksum, created_at)
SELECT (1 + g % 2) * 1000000000000 + 500000 + g, 1 + g % 2, 'CANCEL', 1, NULL, g, 0, 0, '{}'::jsonb,
       (SELECT epoch FROM leader_lease WHERE resource = 'matching-engine'), 0, clock_timestamp()
  FROM generate_series(1, 200000) g;
ANALYZE command_journal;
\o /tmp/cost.out
EXPLAIN (ANALYZE, BUFFERS OFF, TIMING OFF, SUMMARY ON) SELECT count(*) FROM probe.c22_old;
EXPLAIN (ANALYZE, BUFFERS OFF, TIMING OFF, SUMMARY ON) SELECT count(*) FROM probe.c22p;
EXPLAIN (ANALYZE, BUFFERS OFF, TIMING OFF, SUMMARY ON) SELECT count(*) FROM probe.c30;
\o
ROLLBACK;
EOF
grep -E "Execution Time|^ *->.*(Join|Scan).*rows=" /tmp/cost.out | grep -E "Execution Time" | sed 's/^/  /'
echo "== done $(date -u +%Y-%m-%dT%H:%M:%SZ)"
