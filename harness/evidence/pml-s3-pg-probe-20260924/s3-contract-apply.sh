#!/bin/sh
# 컨테이너 안(s3-contract-check.sh 가 부른다). 저장소의 실제 V0~V48 을 파일마다 한 트랜잭션으로 올리고, 시드와 DB 설계
# §3.4 의 V49 계약 SQL, §3.5 의 V50 계약 SQL 을 올린 뒤 ADR 0034 S3 설계 3절 C 절의 동작을 확인한다.
# 로그인: appu(app_role 소속 앱 로그인), rdr(app_role 이 아닌 조회 로그인). 소유자는 postgres 다.
# 절마다 필요하면 템플릿 DB 를 복제해 서로 오염시키지 않는다.
SU="psql -X -U postgres -At -v ON_ERROR_STOP=0"
V='\set VERBOSITY verbose
\set SHOW_CONTEXT never'
db() { d=$1; shift; psql -X -U postgres -d "$d" -At -v ON_ERROR_STOP=0 "$@" 2>&1; }
app() { d=$1; shift; psql -X -U appu -d "$d" -At -v ON_ERROR_STOP=0 "$@" 2>&1; }
clone() { $SU -d postgres -c "CREATE DATABASE $2 TEMPLATE $1" >/dev/null; }

# ── C0 적용 ──────────────────────────────────────────────────────────
for f in $(ls /c/migration/V*.sql | sed -E 's|.*/V([0-9]+)__.*|\1 &|' | sort -n | cut -d' ' -f2); do
  psql -X -U postgres -d contract -q -1 -v ON_ERROR_STOP=1 -f "$f" >/dev/null 2>/tmp/mig.err \
    || { echo "MIGRATION FAILED: $f"; cat /tmp/mig.err; exit 1; }
done
echo "== C0 applied repository migrations: $(ls /c/migration/V*.sql | wc -l) files (V0..V48)"
psql -X -U postgres -d contract -q -1 -v ON_ERROR_STOP=1 -f /c/s3-seed.sql >/dev/null 2>/tmp/seed.err \
  || { echo "SEED FAILED"; cat /tmp/seed.err; exit 1; }
# V48 만 적용된 상태를 따로 떠 둔다(C14 의 옛 스키마 대조, C18 의 N/A 분류 대조용).
$SU -d postgres -c "CREATE DATABASE v48only TEMPLATE contract" >/dev/null
# V47 까지만 올린 DB 도 만든다(C18). 시드는 넣지 않는다.
$SU -d postgres -c "CREATE DATABASE v47only" >/dev/null
for f in $(ls /c/migration/V*.sql | sed -E 's|.*/V([0-9]+)__.*|\1 &|' | sort -n | awk '$1 < 48' | cut -d' ' -f2); do
  psql -X -U postgres -d v47only -q -1 -v ON_ERROR_STOP=1 -f "$f" >/dev/null 2>/tmp/mig47.err \
    || { echo "V47 MIGRATION FAILED: $f"; cat /tmp/mig47.err; exit 1; }
done
psql -X -U postgres -d contract -q -1 -v ON_ERROR_STOP=1 -f /c/v49.sql >/dev/null 2>/tmp/v49.err \
  || { echo "V49 CONTRACT FAILED"; cat /tmp/v49.err; exit 1; }
echo "== C0 applied contract V49 (DB 설계 §3.4 둘째 블록)"
psql -X -U postgres -d contract -q -1 -v ON_ERROR_STOP=1 -f /c/v50.sql 2>&1
echo "v50 exit=$?"
$SU -d contract -q <<'EOF'
CREATE ROLE rdr LOGIN;
GRANT USAGE ON SCHEMA public TO rdr;
GRANT SELECT ON assets, markets, trades TO rdr;
ALTER ROLE rdr SET default_transaction_read_only = on;
CREATE ROLE appu LOGIN;
GRANT app_role TO appu;
EOF

echo
echo "== C1 권한. 열: 함수, rdr, app_role"
db contract <<'EOF'
SELECT 'exec', f, has_function_privilege('rdr', f, 'EXECUTE'), has_function_privilege('app_role', f, 'EXECUTE')
FROM unnest(ARRAY[
  'append_command_prologue(text,bigint,boolean,bigint,character varying,smallint,bigint,jsonb,smallint,numeric)',
  'lease_acquire_capped(text,text,interval,smallint)',
  'try_acquire_lease(text,text,interval,smallint)',
  'publish_leader_epoch(text,bigint,smallint)',
  'lock_balance_rows(bigint[])',
  'set_leader_protocol(smallint,boolean)',
  'order_reserve_amount(smallint,jsonb)',
  'append_command_prologue(text,bigint,boolean,bigint,character varying,smallint)',
  'append_command_journal_row(bigint,smallint,character varying,bigint,character varying,bigint,bigint,bigint,jsonb,bigint,text,bigint)',
  'try_acquire_lease(text,text,interval)',
  'publish_leader_epoch(text,bigint)']) AS f ORDER BY 2;
SELECT 'table', 'order_reservations', has_table_privilege('app_role', 'order_reservations', 'SELECT') AS app_select,
       has_table_privilege('app_role', 'order_reservations', 'INSERT') AS app_insert,
       has_table_privilege('app_role', 'command_journal', 'INSERT') AS app_journal_insert_after_v49;
EOF
echo "-- appu 의 order_reservations 직접 INSERT(거부 기대)"
app contract <<'EOF'
INSERT INTO order_reservations (command_seq, order_id, user_id, market_id, asset_id, available_account_id,
  locked_account_id, amount, ledger_txn_id, payload_sha256) VALUES (1, 1, 1, 1, 1, 1, 1, 1, 1, '\x00');
EOF
echo "-- appu 의 set_leader_protocol(거부 기대)"
app contract <<'EOF'
SELECT * FROM set_leader_protocol(2::smallint);
EOF

echo
echo "== C2 P1: 4인자 획득(최고 P2), 10인자 머리말은 예약하지 않는다"
db contract -c "UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' WHERE resource = 'matching-engine'" >/dev/null
app contract <<'EOF'
SELECT 'acquire3(S2 판, P1)', try_acquire_lease('matching-engine', 'node-s2', interval '1 hour');
EOF
db contract -c "UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' WHERE resource = 'matching-engine'" >/dev/null
app contract <<'EOF'
SELECT 'acquire4(max 2, P1)', try_acquire_lease('matching-engine', 'node-s3', interval '1 hour', 2::smallint);
SELECT holder, epoch FROM leader_lease WHERE resource = 'matching-engine' \gset
BEGIN;
SELECT out_seq, out_replayed, out_reserved FROM append_command_prologue(:'holder', :epoch, true, 1, 'p1-a', 1::smallint,
  1001, probe_payload('BUY', 'LIMIT', '50000', '0.2', NULL), 1::smallint, 10000) \gset
SELECT 'p1 prologue', :out_seq, :'out_replayed', :'out_reserved';
SELECT 'p1 row', append_command_journal_row(:out_seq, 1::smallint, 'NEW', 1, 'p1-a', 1001, 1, 1,
  probe_payload('BUY', 'LIMIT', '50000', '0.2', NULL), 0, :'holder', :epoch);
COMMIT;
SELECT 'p1 reservations', count(*) FROM order_reservations;
SELECT 'p1 reserve txns', count(*) FROM ledger_transactions WHERE business_event_type = 'RESERVE';
SELECT 'p1 replay', out_seq, out_replayed, out_reserved FROM append_command_prologue(:'holder', :epoch, true, 1, 'p1-a',
  1::smallint, 1002, probe_payload('BUY', 'LIMIT', '50000', '0.2', NULL), 1::smallint, 10000);
EOF

echo
echo "== C3 1->2 전환. 정산 안 된 P1 NEW 가 있으면 거부, 정산한 뒤에는 모든 리스 행을 새 epoch 로 퇴역"
db contract <<EOF
$V
SELECT * FROM set_leader_protocol(2::smallint);
SELECT 'after refused', protocol FROM leader_protocol;
EOF
# P1 정산을 흉내 낸다. 정산 sink 와 같은 행을 소유자로 쓴다(reserve_funds, orders OPEN, event_journal ACCEPTED).
db contract <<'EOF'
SELECT reserve_funds(1001, probe_acct(1, 'AVAILABLE', 1::smallint), probe_acct(1, 'LOCKED', 1::smallint), 1::smallint, 10000);
INSERT INTO orders (id, external_id, user_id, market_id, client_order_id, side, ord_type, price, quantity,
                    remaining_qty, locked_account_id, locked_amount, status, command_seq)
SELECT 1001, gen_random_uuid(), 1, 1, 'p1-a', 'BUY', 'LIMIT', 50000, 0.2, 0.2, probe_acct(1, 'LOCKED', 1::smallint), 10000,
       'OPEN', c.seq FROM command_journal c WHERE c.assigned_order_id = 1001;
INSERT INTO event_journal (event_seq, source_cmd_seq, event_ordinal, event_type, payload, fencing_epoch, created_at, market_id, market_seq)
SELECT 700000001, c.seq, 0, 'ACCEPTED', '{}'::jsonb, c.fencing_token, now(), 1, 1 FROM command_journal c WHERE c.assigned_order_id = 1001;
SELECT 'settled p1 NEW', count(*) FROM event_emission_registry;
EOF
$SU -d postgres -c "CREATE DATABASE p1base TEMPLATE contract" >/dev/null
db contract <<'EOF'
SELECT 'transition', * FROM set_leader_protocol(2::smallint);
SELECT 'protocol', protocol FROM leader_protocol;
SELECT 'history', protocol, prev_protocol, epoch_floor FROM leader_protocol_history ORDER BY id;
SELECT 'lease', resource, holder, epoch, lease_until < clock_timestamp() AS expired FROM leader_lease ORDER BY resource;
SELECT 'acq', epoch, resource, holder, protocol, origin FROM lease_acquisitions ORDER BY epoch;
EOF

echo
echo "== C3b P1 에서는 S2 이전 바이너리의 2인자 게시가 종전대로 된다(복제 DB p1base)"
clone p1base pub1
app pub1 <<'EOF'
SELECT 'publish2 in P1', publish_leader_epoch('dyn-old', 900);
SELECT 'acq', epoch, resource, holder, protocol, origin FROM lease_acquisitions WHERE epoch = 900;
SELECT 'lease', holder, epoch FROM leader_lease WHERE resource = 'matching-engine';
EOF

echo
echo "== C4 P2 에서 S3 이전 획득·게시 거부, 4인자(최고 P2) 획득"
app contract <<EOF
$V
SELECT try_acquire_leader('old-binary', interval '1 hour');
SELECT try_acquire_lease('matching-engine', 'node-s2', interval '1 hour');
SELECT try_acquire_lease('matching-engine', 'node-x', interval '1 hour', 1::smallint);
SELECT publish_leader_epoch('dyn-old', 1000);
SELECT publish_leader_epoch('dyn-new', 1000, 1::smallint);
SELECT 'lease unchanged', holder, epoch FROM leader_lease WHERE resource = 'matching-engine';
SELECT 'acquire4(max 2, P2)', try_acquire_lease('matching-engine', 'node-p2', interval '1 hour', 2::smallint);
SELECT 'acq last', epoch, holder, protocol, origin FROM lease_acquisitions ORDER BY epoch DESC LIMIT 1;
EOF
$SU -d postgres -c "CREATE DATABASE p2base TEMPLATE contract" >/dev/null

echo
echo "== C5 P2 예약 append(앱 로그인). 지정가 매수 0.3 x 50000 = 15000 KRW"
app contract <<'EOF'
SELECT holder, epoch FROM leader_lease WHERE resource = 'matching-engine' \gset
SELECT 'before', available, locked FROM user_balances WHERE user_id = 1 AND asset_id = 1;
BEGIN;
SELECT out_seq, out_replayed, out_reserved FROM append_command_prologue(:'holder', :epoch, true, 1, 'p2-a', 1::smallint,
  2001, probe_payload('BUY', 'LIMIT', '50000', '0.3', NULL), 1::smallint, 15000) \gset
SELECT 'p2 prologue', :out_seq, :'out_replayed', :'out_reserved';
SELECT 'p2 row', append_command_journal_row(:out_seq, 1::smallint, 'NEW', 1, 'p2-a', 2001, 1, 1,
  probe_payload('BUY', 'LIMIT', '50000', '0.3', NULL), 0, :'holder', :epoch);
COMMIT;
SELECT 'reservation', command_seq, order_id, asset_id, amount, (locked_account_id = probe_acct(1, 'LOCKED', 1::smallint)) AS locked_ok
  FROM order_reservations;
SELECT 'reserve txn', t.business_event_id, t.status, e.amount
  FROM ledger_transactions t JOIN ledger_entries e ON e.transaction_id = t.id
 WHERE t.business_event_type = 'RESERVE' AND t.business_event_id = 2001 ORDER BY e.amount;
SELECT 'after', available, locked FROM user_balances WHERE user_id = 1 AND asset_id = 1;
EOF

echo
echo "== C6 같은 키 재전송은 두 번째 예약을 만들지 않는다"
app contract <<'EOF'
SELECT holder, epoch FROM leader_lease WHERE resource = 'matching-engine' \gset
SELECT 'replay', out_replayed, out_reserved, out_order_id FROM append_command_prologue(:'holder', :epoch, true, 1, 'p2-a',
  1::smallint, 2002, probe_payload('BUY', 'LIMIT', '50000', '0.3', NULL), 1::smallint, 15000);
SELECT 'reservations', count(*) FROM order_reservations;
SELECT 'reserve txns', count(*) FROM ledger_transactions WHERE business_event_type = 'RESERVE';
EOF

echo
echo "== C7 잔고 부족: 23514(chk_balance_nonneg)로 트랜잭션 전체 롤백. 저널·카운터·예약·원장 불변"
app contract <<EOF
SELECT holder, epoch FROM leader_lease WHERE resource = 'matching-engine' \gset
SELECT 'before', (SELECT count(*) FROM command_journal) AS journal, (SELECT next_value FROM journal_counters WHERE name = 'command_seq') AS counter,
       (SELECT count(*) FROM order_reservations) AS res, (SELECT count(*) FROM ledger_transactions) AS txns;
$V
BEGIN;
SELECT * FROM append_command_prologue(:'holder', :epoch, true, 1, 'p2-big', 1::smallint,
  2003, probe_payload('BUY', 'LIMIT', '5000000', '1', NULL), 1::smallint, 5000000);
ROLLBACK;
SELECT 'after', (SELECT count(*) FROM command_journal) AS journal, (SELECT next_value FROM journal_counters WHERE name = 'command_seq') AS counter,
       (SELECT count(*) FROM order_reservations) AS res, (SELECT count(*) FROM ledger_transactions) AS txns;
EOF

echo
echo "== C8 오래된 epoch: 머리말은 예약 전에 fencing 거부, INSERT 0행이면 호출자 롤백으로 예약까지 사라진다"
app contract <<EOF
SELECT holder, epoch FROM leader_lease WHERE resource = 'matching-engine' \gset
$V
SELECT * FROM append_command_prologue(:'holder', :epoch - 1, true, 1, 'p2-stale', 1::smallint,
  2004, probe_payload('BUY', 'LIMIT', '50000', '0.1', NULL), 1::smallint, 5000);
\set VERBOSITY default
BEGIN;
SELECT out_seq, out_reserved FROM append_command_prologue(:'holder', :epoch, true, 1, 'p2-stale2', 1::smallint,
  2005, probe_payload('BUY', 'LIMIT', '50000', '0.1', NULL), 1::smallint, 5000) \gset
SELECT 'in-tx reservations', count(*) FROM order_reservations;
SELECT 'stale row', append_command_journal_row(:out_seq, 1::smallint, 'NEW', 1, 'p2-stale2', 2005, 1, 1,
  probe_payload('BUY', 'LIMIT', '50000', '0.1', NULL), 0, :'holder', :epoch - 1);
ROLLBACK;
SELECT 'after rollback reservations', count(*) FROM order_reservations;
SELECT 'after rollback reserve txn 2005', count(*) FROM ledger_transactions WHERE business_event_type = 'RESERVE' AND business_event_id = 2005;
EOF

echo
echo "== C9 앱 예약액·자산과 DB 계산이 다르면 거부"
app contract <<EOF
SELECT holder, epoch FROM leader_lease WHERE resource = 'matching-engine' \gset
$V
SELECT * FROM append_command_prologue(:'holder', :epoch, true, 1, 'p2-mis', 1::smallint,
  2006, probe_payload('BUY', 'LIMIT', '50000', '0.3', NULL), 1::smallint, 14999);
SELECT * FROM append_command_prologue(:'holder', :epoch, true, 1, 'p2-mis2', 1::smallint,
  2007, probe_payload('BUY', 'LIMIT', '50000', '0.3', NULL), 2::smallint, 15000);
SELECT * FROM append_command_prologue(:'holder', :epoch, true, 1, 'p2-mis3', 1::smallint,
  2008, probe_payload('BUY', 'LIMIT', '50000', '0.3', NULL), NULL, NULL);
SELECT 'reservations', count(*) FROM order_reservations;
EOF

echo
echo "== C10 저널 행이 예약 행과 묶인다. payload 가 다르거나 예약 없이 P2 NEW 를 넣으면 거부"
app contract <<EOF
SELECT holder, epoch FROM leader_lease WHERE resource = 'matching-engine' \gset
BEGIN;
SELECT out_seq FROM append_command_prologue(:'holder', :epoch, true, 1, 'p2-bind', 1::smallint,
  2009, probe_payload('BUY', 'LIMIT', '50000', '0.1', NULL), 1::smallint, 5000) \gset
$V
SELECT append_command_journal_row(:out_seq, 1::smallint, 'NEW', 1, 'p2-bind', 2009, 1, 1,
  probe_payload('BUY', 'LIMIT', '40000', '0.1', NULL), 0, :'holder', :epoch);
ROLLBACK;
BEGIN;
SELECT next_journal_value('command_seq') AS s \gset
SELECT append_command_journal_row(:s, 1::smallint, 'NEW', 1, 'p2-nores', 2010, 1, 1,
  probe_payload('BUY', 'LIMIT', '40000', '0.1', NULL), 0, :'holder', :epoch);
ROLLBACK;
\set VERBOSITY default
SELECT 'journal NEW rows', count(*) FROM command_journal WHERE command_type = 'NEW';
EOF

echo
echo "== C11 레거시 6인자 머리말은 P2 에서 NEW 를 거부하고 CANCEL 은 받는다(현재 P2 리더로 호출)"
app contract <<EOF
SELECT holder, epoch FROM leader_lease WHERE resource = 'matching-engine' \gset
$V
SELECT * FROM append_command_prologue(:'holder', :epoch, true, 1, 'p2-legacy', 1::smallint);
\set VERBOSITY default
BEGIN;
SELECT 'legacy cancel', out_seq IS NOT NULL, out_replayed FROM append_command_prologue(:'holder', :epoch, false, 1, NULL, 1::smallint);
ROLLBACK;
EOF

echo
echo "== C12 같은 사용자, 두 마켓, 동시 예약. 뒤의 것은 잔고 행을 기다렸다가 23514"
AV=$(db contract -c "SELECT available::bigint FROM user_balances WHERE user_id = 1 AND asset_id = 1")
echo "alice available KRW = $AV (두 주문이 각각 전액을 요구한다)"
( app contract <<EOF
SELECT holder, epoch FROM leader_lease WHERE resource = 'matching-engine' \gset
BEGIN;
SELECT 'A start', to_char(clock_timestamp(), 'HH24:MI:SS.MS');
SELECT out_seq FROM append_command_prologue(:'holder', :epoch, true, 1, 'race-a', 1::smallint,
  3001, probe_payload('BUY', 'LIMIT', '$AV', '1', NULL), 1::smallint, $AV) \gset
SELECT 'A row', append_command_journal_row(:out_seq, 1::smallint, 'NEW', 1, 'race-a', 3001, 1, 1,
  probe_payload('BUY', 'LIMIT', '$AV', '1', NULL), 0, :'holder', :epoch);
SELECT pg_sleep(3);
COMMIT;
SELECT 'A committed', to_char(clock_timestamp(), 'HH24:MI:SS.MS');
EOF
) > /tmp/c12a.out &
sleep 1
app contract <<EOF
SELECT holder, epoch FROM leader_lease WHERE resource = 'matching-engine' \gset
SELECT 'B start', to_char(clock_timestamp(), 'HH24:MI:SS.MS');
$V
SELECT * FROM append_command_prologue(:'holder', :epoch, true, 1, 'race-b', 2::smallint,
  3002, probe_payload('BUY', 'LIMIT', '$AV', '1', NULL), 1::smallint, $AV);
\set VERBOSITY default
SELECT 'B end', to_char(clock_timestamp(), 'HH24:MI:SS.MS');
EOF
wait
cat /tmp/c12a.out
echo "-- C12b 같은 경합에 lock_timeout 500ms 를 건 쪽은 55P03"
( app contract <<EOF
SELECT holder, epoch FROM leader_lease WHERE resource = 'matching-engine' \gset
BEGIN;
SELECT out_seq FROM append_command_prologue(:'holder', :epoch, true, 2, 'race-c', 2::smallint,
  3003, probe_payload('BUY', 'LIMIT', '100000', '1', NULL), 1::smallint, 100000) \gset
SELECT pg_sleep(2);
ROLLBACK;
EOF
) > /tmp/c12c.out &
sleep 1
app contract <<EOF
SELECT holder, epoch FROM leader_lease WHERE resource = 'matching-engine' \gset
$V
BEGIN;
SELECT set_config('lock_timeout', '500ms', true);
SELECT * FROM append_command_prologue(:'holder', :epoch, true, 2, 'race-d', 1::smallint,
  3004, probe_payload('BUY', 'LIMIT', '100000', '1', NULL), 1::smallint, 100000);
ROLLBACK;
EOF
wait
cat /tmp/c12c.out

echo
echo "== C13 출금과 주문의 같은 잔고 경합(TransferService 의 reserve_funds 경로). 뒤의 것이 23514"
BV=$(db contract -c "SELECT available::bigint FROM user_balances WHERE user_id = 2 AND asset_id = 1")
echo "bob available KRW = $BV"
( app contract <<EOF
SELECT holder, epoch FROM leader_lease WHERE resource = 'matching-engine' \gset
BEGIN;
SELECT out_seq FROM append_command_prologue(:'holder', :epoch, true, 2, 'wd-race', 2::smallint,
  3005, probe_payload('BUY', 'LIMIT', '$BV', '1', NULL), 1::smallint, $BV) \gset
SELECT 'order row', append_command_journal_row(:out_seq, 2::smallint, 'NEW', 2, 'wd-race', 3005, 1, 1,
  probe_payload('BUY', 'LIMIT', '$BV', '1', NULL), 0, :'holder', :epoch);
SELECT pg_sleep(2);
COMMIT;
SELECT 'order committed', to_char(clock_timestamp(), 'HH24:MI:SS.MS');
EOF
) > /tmp/c13a.out &
sleep 1
app contract <<EOF
$V
BEGIN;
INSERT INTO transfer_requests (user_id, asset_id, direction, amount, idem_key) VALUES (2, 1, 'WITHDRAW', $BV, 'wd-1')
RETURNING id AS tid \gset
SELECT reserve_funds(2000000000000 + :tid, probe_acct(2, 'AVAILABLE', 1::smallint), probe_acct(2, 'LOCKED', 1::smallint), 1::smallint, $BV);
ROLLBACK;
\set VERBOSITY default
SELECT 'withdraw end', to_char(clock_timestamp(), 'HH24:MI:SS.MS');
EOF
wait
cat /tmp/c13a.out

echo
echo "== C14 대기 출금(결정한 값 16). V48 DB 에서 지금 C14 는 대기 출금을 위반으로 세고, 출금 항을 더한 옛 스키마 판 C14' 는 세지 않는다"
clone v48only wdtest
db wdtest <<'EOF'
SELECT reserve_funds(2000000000000 + 99, probe_acct(1, 'AVAILABLE', 2::smallint), probe_acct(1, 'LOCKED', 2::smallint), 2::smallint, 1);
INSERT INTO transfer_requests (user_id, asset_id, direction, amount, idem_key) VALUES (1, 2, 'WITHDRAW', 1, 'wd-pending');
SELECT 'current C14', count(*) FROM (
    SELECT b.account_id FROM accounts a JOIN balances b ON b.account_id = a.id
    LEFT JOIN orders o ON o.locked_account_id = a.id AND o.status IN ('PENDING','OPEN','PARTIAL')
    WHERE a.purpose = 'LOCKED' GROUP BY b.account_id, b.balance
    HAVING b.balance <> COALESCE(SUM(o.locked_amount), 0)) x;
SELECT 'C14p without reservation term', count(*) FROM (
    SELECT a.id FROM accounts a JOIN balances b ON b.account_id = a.id
    LEFT JOIN (SELECT locked_account_id AS account_id, SUM(locked_amount) AS amt FROM orders
               WHERE status IN ('PENDING','OPEN','PARTIAL') GROUP BY locked_account_id) o ON o.account_id = a.id
    LEFT JOIN (SELECT la.id AS account_id, SUM(t.amount) AS amt FROM transfer_requests t
               JOIN accounts la ON la.owner_type = 'USER' AND la.owner_id = t.user_id AND la.purpose = 'LOCKED'
                               AND la.asset_id = t.asset_id AND la.shard = 0
               WHERE t.direction = 'WITHDRAW' AND t.status = 'REQUESTED' GROUP BY la.id) w ON w.account_id = a.id
    WHERE a.purpose = 'LOCKED' AND b.balance <> COALESCE(o.amt, 0) + COALESCE(w.amt, 0)) x;
EOF
echo "-- 계약 DB 에도 대기 출금 하나를 둔다(C14' 출금 항의 양성 축)"
db contract <<'EOF'
SELECT reserve_funds(2000000000000 + 99, probe_acct(1, 'AVAILABLE', 2::smallint), probe_acct(1, 'LOCKED', 2::smallint), 2::smallint, 1);
INSERT INTO transfer_requests (user_id, asset_id, direction, amount, idem_key) VALUES (1, 2, 'WITHDRAW', 1, 'wd-pending');
EOF
echo "-- C14' 술어(문서 원문). 정산 전 예약 셋(2001·3001·3005), P1 주문 1001, 대기 출금 하나. 0행 기대"
db contract < /c/c14p.sql

echo
echo "== C15 정산 흉내: 예약 주문 ACCEPTED(orders 가 예약액을 넘겨받음), REJECTED(해제), 해제 누락 음성 대조"
db contract <<'EOF'
-- ACCEPTED: 정산은 reserve_funds 를 부르지 않고 예약 행의 금액·계정으로 orders 를 만든다.
INSERT INTO orders (id, external_id, user_id, market_id, client_order_id, side, ord_type, price, quantity,
                    remaining_qty, locked_account_id, locked_amount, status, command_seq)
SELECT r.order_id, gen_random_uuid(), r.user_id, r.market_id, c.client_order_id, 'BUY', 'LIMIT',
       (c.payload ->> 'price')::numeric, (c.payload ->> 'qty')::numeric, (c.payload ->> 'qty')::numeric,
       r.locked_account_id, r.amount, 'OPEN', r.command_seq
  FROM order_reservations r JOIN command_journal c ON c.seq = r.command_seq
 WHERE r.order_id IN (2001, 3001, 3005);
INSERT INTO event_journal (event_seq, source_cmd_seq, event_ordinal, event_type, payload, fencing_epoch, created_at, market_id, market_seq)
SELECT 700000100 + row_number() OVER (ORDER BY r.command_seq), r.command_seq, 0, 'ACCEPTED', '{}'::jsonb, c.fencing_token, now(),
       r.market_id, 100 + row_number() OVER (ORDER BY r.command_seq)
  FROM order_reservations r JOIN command_journal c ON c.seq = r.command_seq
 WHERE r.order_id IN (2001, 3001, 3005);
EOF
echo "-- C14' 와 C31 (ACCEPTED 정산 뒤)"
db contract < /c/c14p.sql
db contract < /c/c31.sql
# 새 예약 주문 둘을 만든다(POST_ONLY 거부 흉내). 하나는 해제하고 하나는 해제를 빠뜨린다.
bash_like_reserve() {
app contract <<EOF
SELECT holder, epoch FROM leader_lease WHERE resource = 'matching-engine' \gset
BEGIN;
SELECT out_seq FROM append_command_prologue(:'holder', :epoch, true, 2, '$1', 2::smallint,
  $2, probe_payload('SELL', 'LIMIT', '100', '0.5', NULL), 3::smallint, 0.5) \gset
SELECT '$1 row', append_command_journal_row(:out_seq, 2::smallint, 'NEW', 2, '$1', $2, 1, 1,
  probe_payload('SELL', 'LIMIT', '100', '0.5', NULL), 0, :'holder', :epoch);
COMMIT;
EOF
}
bash_like_reserve rej-release 4001
bash_like_reserve rej-leak 4002
db contract <<'EOF'
-- REJECTED 정산: 이벤트 적재 + 예약 해제(business_event_id = 그 이벤트의 event_seq) + orders REJECTED
INSERT INTO event_journal (event_seq, source_cmd_seq, event_ordinal, event_type, payload, fencing_epoch, created_at, market_id, market_seq)
SELECT 700000200, r.command_seq, 0, 'REJECTED', '{}'::jsonb, 0, now(), r.market_id, 200 FROM order_reservations r WHERE r.order_id = 4001;
SELECT release_funds(700000200, r.locked_account_id, r.available_account_id, r.asset_id, r.amount)
  FROM order_reservations r WHERE r.order_id = 4001;
INSERT INTO orders (id, external_id, user_id, market_id, client_order_id, side, ord_type, price, quantity,
                    remaining_qty, locked_amount, status, reject_reason, command_seq)
SELECT r.order_id, gen_random_uuid(), r.user_id, r.market_id, 'rej-release', 'SELL', 'LIMIT', 100, 0.5, 0, 0, 'REJECTED',
       'post_only_would_take', r.command_seq FROM order_reservations r WHERE r.order_id = 4001;
-- 해제 누락(음성 대조): 이벤트와 REJECTED 행만 남기고 release_funds 를 부르지 않는다.
INSERT INTO event_journal (event_seq, source_cmd_seq, event_ordinal, event_type, payload, fencing_epoch, created_at, market_id, market_seq)
SELECT 700000201, r.command_seq, 0, 'REJECTED', '{}'::jsonb, 0, now(), r.market_id, 201 FROM order_reservations r WHERE r.order_id = 4002;
INSERT INTO orders (id, external_id, user_id, market_id, client_order_id, side, ord_type, price, quantity,
                    remaining_qty, locked_amount, status, reject_reason, command_seq)
SELECT r.order_id, gen_random_uuid(), r.user_id, r.market_id, 'rej-leak', 'SELL', 'LIMIT', 100, 0.5, 0, 0, 'REJECTED',
       'post_only_would_take', r.command_seq FROM order_reservations r WHERE r.order_id = 4002;
EOF
echo "-- C14' (해제 누락 한 건이 남아 위반 1행 기대)"
db contract < /c/c14p.sql
echo "-- 누락분을 해제한 뒤 C14'"
db contract -c "SELECT release_funds(700000299, r.locked_account_id, r.available_account_id, r.asset_id, r.amount) FROM order_reservations r WHERE r.order_id = 4002" >/dev/null
db contract < /c/c14p.sql
echo "-- C31 (정상 상태 0행 기대)"
db contract < /c/c31.sql
echo "-- C31 양성 대조(복제 DB): 원장 없는 예약 행, 예약 없는 P2 NEW"
clone contract c31pos
db c31pos <<'EOF'
INSERT INTO order_reservations (command_seq, order_id, user_id, market_id, asset_id, available_account_id,
  locked_account_id, amount, ledger_txn_id, payload_sha256)
VALUES (999999999, 9999, 1, 1, 1, probe_acct(1, 'AVAILABLE', 1::smallint), probe_acct(1, 'LOCKED', 1::smallint), 1, 1, '\x00');
INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id, assigned_order_id, engine_ts,
  tie_break, payload, fencing_token, checksum)
SELECT next_journal_value('command_seq'), 1, 'NEW', 1, 'bypass', 9998, 1, 1, '{}'::jsonb, l.epoch, 0
  FROM leader_lease l WHERE l.resource = 'matching-engine';
EOF
db c31pos < /c/c31.sql
echo "-- C31 앱 판(마켓마다 최근 5,000건 창의 세 항 수). 정상 DB 0, 양성 DB 는 창 안의 예약 없는 P2 NEW 하나((b))를 센다."
echo "-- 저널에 없는 예약 행(고아)은 앱 판이 보지 못하고 위 전수 판만 센다(계약대로)"
db contract < /c/c31app.sql | sed 's/^/c31app contract: /'
db c31pos < /c/c31app.sql | sed 's/^/c31app c31pos: /'

echo
echo "== C16 전환 경합(검토 1 1b). 복제 DB p1base(P1, 정산 끝남, 리더 node-s3)"
clone p1base race1
echo "-- C16a 진행 중 P1 NEW(KEY SHARE 보유)가 전환 직전에 커밋되면 전환이 선행 조건에서 거부된다"
( app race1 <<'EOF'
SELECT holder, epoch FROM leader_lease WHERE resource = 'matching-engine' \gset
BEGIN;
SELECT out_seq FROM append_command_prologue(:'holder', :epoch, true, 1, 'race-p1', 1::smallint,
  5001, probe_payload('BUY', 'LIMIT', '50000', '0.1', NULL), 1::smallint, 5000) \gset
SELECT 'A row', append_command_journal_row(:out_seq, 1::smallint, 'NEW', 1, 'race-p1', 5001, 1, 1,
  probe_payload('BUY', 'LIMIT', '50000', '0.1', NULL), 0, :'holder', :epoch);
SELECT pg_sleep(2);
COMMIT;
SELECT 'A committed', to_char(clock_timestamp(), 'HH24:MI:SS.MS');
EOF
) > /tmp/c16a.out &
sleep 1
db race1 <<EOF
SELECT 'T start', to_char(clock_timestamp(), 'HH24:MI:SS.MS');
$V
SELECT * FROM set_leader_protocol(2::smallint);
\set VERBOSITY default
SELECT 'T end', to_char(clock_timestamp(), 'HH24:MI:SS.MS'), (SELECT protocol FROM leader_protocol);
EOF
wait
cat /tmp/c16a.out
clone p1base race2
echo "-- C16b 전환이 잠금을 쥔 동안 시작한 10인자 append 는 KEY SHARE 에서 기다렸다가 퇴역 epoch 로 fencing 거부"
( db race2 <<'EOF'
BEGIN;
SELECT 'T', * FROM set_leader_protocol(2::smallint);
SELECT pg_sleep(2);
COMMIT;
SELECT 'T committed', to_char(clock_timestamp(), 'HH24:MI:SS.MS');
EOF
) > /tmp/c16b.out &
sleep 1
app race2 <<EOF
SELECT holder, epoch FROM leader_lease WHERE resource = 'matching-engine' \gset
SELECT 'A start', to_char(clock_timestamp(), 'HH24:MI:SS.MS');
$V
SELECT * FROM append_command_prologue(:'holder', :epoch, true, 1, 'late-10', 1::smallint,
  5002, probe_payload('BUY', 'LIMIT', '50000', '0.1', NULL), 1::smallint, 5000);
\set VERBOSITY default
SELECT 'A end', to_char(clock_timestamp(), 'HH24:MI:SS.MS');
EOF
wait
cat /tmp/c16b.out
db race2 -c "SELECT 'race2 NEW 5002 rows', count(*) FROM command_journal WHERE assigned_order_id = 5002"
clone p1base race3
echo "-- C16c 같은 상황의 S2 경로(6인자 머리말은 잠그지 않고 판정, 행 함수가 KEY SHARE). 행 함수가 0행"
( db race3 <<'EOF'
BEGIN;
SELECT 'T', * FROM set_leader_protocol(2::smallint);
SELECT pg_sleep(2);
COMMIT;
SELECT 'T committed', to_char(clock_timestamp(), 'HH24:MI:SS.MS');
EOF
) > /tmp/c16c.out &
sleep 1
app race3 <<EOF
SELECT holder, epoch FROM leader_lease WHERE resource = 'matching-engine' \gset
BEGIN;
SELECT out_seq FROM append_command_prologue(:'holder', :epoch, true, 1, 'late-6', 1::smallint) \gset
SELECT 'S2 prologue passed', :out_seq, to_char(clock_timestamp(), 'HH24:MI:SS.MS');
SELECT 'S2 row', append_command_journal_row(:out_seq, 1::smallint, 'NEW', 1, 'late-6', 5003, 1, 1,
  probe_payload('BUY', 'LIMIT', '50000', '0.1', NULL), 0, :'holder', :epoch), to_char(clock_timestamp(), 'HH24:MI:SS.MS');
ROLLBACK;
EOF
wait
cat /tmp/c16c.out

echo
echo "== C17 2->1 되돌리기. 확인 인자 없으면 거부, 있으면 전환하고 S2 판 3인자 획득이 다시 된다"
clone p2base down1
db down1 <<EOF
$V
SELECT * FROM set_leader_protocol(1::smallint);
\set VERBOSITY default
SELECT 'down', * FROM set_leader_protocol(1::smallint, true);
SELECT 'protocol', protocol FROM leader_protocol;
EOF
app down1 <<'EOF'
SELECT 'acquire3 after downgrade', try_acquire_lease('matching-engine', 'node-s2', interval '1 hour');
EOF
db down1 -c "SELECT 'history', protocol, prev_protocol, epoch_floor FROM leader_protocol_history ORDER BY id"

echo
echo "== C18 하네스 N/A 분류식(하네스 검증설계 §4-1 C29·C31 주석). 열: DB, V48 객체 수(5면 평가), V50 객체 수(2면 평가)"
clone v48only v48partial
db v48partial -c "DROP TABLE leader_protocol_history" >/dev/null
for d in v47only v48only v48partial contract; do
db $d <<EOF
SELECT '$d',
       (to_regclass('public.market_lease_partition') IS NOT NULL)::int
     + (to_regclass('public.leader_protocol') IS NOT NULL)::int
     + (to_regclass('public.leader_protocol_history') IS NOT NULL)::int
     + (to_regclass('public.lease_acquisitions') IS NOT NULL)::int
     + (to_regprocedure('public.lease_resource_for_market(smallint)') IS NOT NULL)::int AS v48_objects,
       (to_regclass('public.order_reservations') IS NOT NULL)::int
     + (to_regprocedure('public.order_reserve_amount(smallint,jsonb)') IS NOT NULL)::int AS v50_objects;
EOF
done

# ── 2026-09-24 설계 검토 대응(S3 설계 11절). 아래 C19~C22 는 검토 뒤 계약 SQL 로 더한 확인이다 ─────────────
echo
echo "== C19 전환 퇴역 순서와 외부 보관소 첫 게시(검토 대응 R4). 복제 DB p1base(P1, 정산 끝남)"
clone p1base dyn1
echo "-- P1 에서 외부 보관소 노드가 epoch 100 을 게시해 둔 상태(3인자 게시, 최고 P2)"
app dyn1 -c "SELECT 'publish in P1', publish_leader_epoch('dyn-a', 100, 2::smallint)"
db dyn1 <<'EOF'
SELECT 'transition', * FROM set_leader_protocol(2::smallint);
SELECT 'lease', resource, epoch FROM leader_lease ORDER BY epoch;
SELECT 'floor(matching-engine row)', epoch FROM leader_lease WHERE resource = 'matching-engine';
SELECT 'floor(max lease, acquisitions)', GREATEST((SELECT max(epoch) FROM leader_lease),
                                                  (SELECT COALESCE(max(epoch), 0) FROM lease_acquisitions));
EOF
echo "-- 외부 보관소 노드의 전환 뒤 첫 게시는 하한 + 1 이다(앱 로그인, 3인자 게시)"
app dyn1 <<EOF
$V
SELECT GREATEST((SELECT max(epoch) FROM leader_lease), (SELECT COALESCE(max(epoch), 0) FROM lease_acquisitions)) AS floor \gset
SELECT 'first publish after 1->2', :floor + 1 AS token, publish_leader_epoch('dyn-b', :floor + 1, 2::smallint);
EOF
echo "-- 2->1(확인 인자) 뒤에도 matching-engine 이 마지막 퇴역이고 첫 게시가 된다"
db dyn1 -c "SELECT 'down', * FROM set_leader_protocol(1::smallint, true)"
app dyn1 <<EOF
$V
SELECT GREATEST((SELECT max(epoch) FROM leader_lease), (SELECT COALESCE(max(epoch), 0) FROM lease_acquisitions)) AS floor \gset
SELECT 'first publish after 2->1', :floor + 1 AS token, publish_leader_epoch('dyn-c', :floor + 1, 2::smallint);
SELECT 'acq tail', epoch, resource, holder, protocol, origin FROM lease_acquisitions ORDER BY epoch DESC LIMIT 5;
EOF

echo
echo "== C20 정산 배치와 P2 접수의 교착(검토 대응 R6). p2base(P2, 리더 node-p2)에서 복제"
clone p2base dlbase
db dlbase <<'EOF'
-- 준비: alice 의 A2 0.5 를 LOCKED 로 옮겨 둔다(열린 매도 주문의 예약 흉내). 두 복제 DB 가 같은 상태에서 출발한다.
SELECT reserve_funds(9001, probe_acct(1, 'AVAILABLE', 2::smallint), probe_acct(1, 'LOCKED', 2::smallint), 2::smallint, 0.5) > 0;
SELECT 'accounts', probe_acct(1, 'AVAILABLE', 2::smallint) AS alice_avail_a2, probe_acct(1, 'LOCKED', 2::smallint) AS alice_locked_a2,
       probe_acct(2, 'AVAILABLE', 2::smallint) AS bob_avail_a2;
SELECT 'alice A2 before', available, locked FROM user_balances WHERE user_id = 1 AND asset_id = 2;
EOF
clone dlbase dl1
clone dlbase dl2
# 정산 흉내(세션 A). TRADE 게시는 alice LOCKED A2 와 bob AVAILABLE A2 만 잡는다(시장가·IOC 매도의 체결 분개에서 alice
# AVAILABLE A2 쪽 분개가 없는 모양). 3초 뒤 잔여 해제가 더 낮은 id 의 alice AVAILABLE A2 를 요구한다. 둘째 인자가 plan 이면
# 첫 게시 전에 잠금 계획(배치가 게시할 수 있는 행 전체)을 오름차순으로 먼저 잡는다.
dl_settle() {
  if [ "$2" = plan ]; then
    PLAN="SELECT 'A plan locked', lock_balance_rows(ARRAY[probe_acct(1, 'AVAILABLE', 2::smallint), probe_acct(1, 'LOCKED', 2::smallint),
                                                 probe_acct(2, 'AVAILABLE', 2::smallint), probe_acct(2, 'LOCKED', 2::smallint)]),
                 to_char(clock_timestamp(), 'HH24:MI:SS.MS');"
  else
    PLAN="SELECT 'A no plan';"
  fi
  app "$1" <<EOF
$V
BEGIN;
SELECT 'A start', to_char(clock_timestamp(), 'HH24:MI:SS.MS');
$PLAN
SELECT 'A trade', post_ledger_transaction('TRADE', 800000001, jsonb_build_array(
    jsonb_build_object('account_id', probe_acct(1, 'LOCKED', 2::smallint), 'asset_id', 2, 'amount', -0.1, 'entry_type', 'TRADE'),
    jsonb_build_object('account_id', probe_acct(2, 'AVAILABLE', 2::smallint), 'asset_id', 2, 'amount', 0.1, 'entry_type', 'TRADE'))) > 0,
  to_char(clock_timestamp(), 'HH24:MI:SS.MS');
SELECT pg_sleep(3);
SELECT 'A release', release_funds(800000002, probe_acct(1, 'LOCKED', 2::smallint), probe_acct(1, 'AVAILABLE', 2::smallint),
                                  2::smallint, 0.4) > 0,
  to_char(clock_timestamp(), 'HH24:MI:SS.MS');
COMMIT;
SELECT 'A end', to_char(clock_timestamp(), 'HH24:MI:SS.MS');
EOF
}
# 같은 사용자의 다른 마켓(3 = A2/Q18) P2 매도 접수(세션 B). 10인자 머리말이 alice AVAILABLE A2 -> LOCKED A2 로 예약한다.
dl_intake() {
  app "$1" <<EOF
$V
SELECT holder, epoch FROM leader_lease WHERE resource = 'matching-engine' \gset
BEGIN;
SELECT 'B start', to_char(clock_timestamp(), 'HH24:MI:SS.MS');
SELECT out_seq FROM append_command_prologue(:'holder', :epoch, true, 1, 'dl-sell', 3::smallint,
  6001, probe_payload('SELL', 'LIMIT', '100', '0.5', NULL), 2::smallint, 0.5) \gset
SELECT 'B row', append_command_journal_row(:out_seq, 3::smallint, 'NEW', 1, 'dl-sell', 6001, 1, 1,
  probe_payload('SELL', 'LIMIT', '100', '0.5', NULL), 0, :'holder', :epoch), to_char(clock_timestamp(), 'HH24:MI:SS.MS');
COMMIT;
SELECT 'B end', to_char(clock_timestamp(), 'HH24:MI:SS.MS');
EOF
}
dl_count() { db "$1" -c "SELECT 'deadlocks($1)', deadlocks FROM pg_stat_database WHERE datname = '$1'"; }
echo "-- D1 잠금 계획 없이(음성 대조, 개정 전 정산 모양). 교착 기대"
dl_count dl1
( dl_settle dl1 none ) > /tmp/c20a.out 2>&1 &
sleep 1
dl_intake dl1
wait
cat /tmp/c20a.out
sleep 1
dl_count dl1
echo "-- D2 잠금 계획을 먼저 잡은 정산. 접수는 정산 커밋까지 기다린 뒤 예약한다. 교착 없음 기대"
dl_count dl2
( dl_settle dl2 plan ) > /tmp/c20b.out 2>&1 &
sleep 1
dl_intake dl2
wait
cat /tmp/c20b.out
sleep 1
dl_count dl2
db dl2 -c "SELECT 'alice A2 after D2', available, locked FROM user_balances WHERE user_id = 1 AND asset_id = 2"
db dl2 -c "SELECT 'D2 reservations', count(*) FROM order_reservations WHERE order_id = 6001"

echo
echo "== C21 C14' 정산 전 예약 항의 커서 경계(검토 대응 R5). contract 에서 복제한 c21"
clone contract c21
# 개정 전 판(표 전체 반조인)의 정산 전 예약 항. 대조 기준이고 판정에는 쓰지 않는다.
cat > /tmp/c14p_full.sql <<'EOF'
SELECT a.id AS account_id, b.balance, COALESCE(o.amt, 0) AS order_locked, COALESCE(u.amt, 0) AS unsettled_reserved,
       COALESCE(w.amt, 0) AS pending_withdraw
FROM accounts a
JOIN balances b ON b.account_id = a.id
LEFT JOIN (SELECT locked_account_id AS account_id, SUM(locked_amount) AS amt FROM orders
           WHERE status IN ('PENDING','OPEN','PARTIAL') GROUP BY locked_account_id) o ON o.account_id = a.id
LEFT JOIN (SELECT r.locked_account_id AS account_id, SUM(r.amount) AS amt FROM order_reservations r
           WHERE NOT EXISTS (SELECT 1 FROM event_emission_registry e WHERE e.source_cmd_seq = r.command_seq)
           GROUP BY r.locked_account_id) u ON u.account_id = a.id
LEFT JOIN (SELECT la.id AS account_id, SUM(t.amount) AS amt FROM transfer_requests t
           JOIN accounts la ON la.owner_type = 'USER' AND la.owner_id = t.user_id AND la.purpose = 'LOCKED'
                           AND la.asset_id = t.asset_id AND la.shard = 0
           WHERE t.direction = 'WITHDRAW' AND t.status = 'REQUESTED' GROUP BY la.id) w ON w.account_id = a.id
WHERE a.purpose = 'LOCKED' AND b.balance <> COALESCE(o.amt, 0) + COALESCE(u.amt, 0) + COALESCE(w.amt, 0);
EOF
# 게이지 질의(S3 설계 4-16). 커서 위 정산 전 예약 가운데 가장 오래된 것의 나이와 수.
cat > /tmp/gauge.sql <<'EOF'
SELECT 'gauge', count(*) AS pending, COALESCE(floor(EXTRACT(EPOCH FROM clock_timestamp() - min(r.created_at))), 0) AS oldest_s
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
c21_reserve() {  # 1 사용자, 2 키, 3 마켓, 4 주문 id, 5 side, 6 price, 7 qty, 8 자산, 9 금액
app c21 <<EOF
SELECT holder, epoch FROM leader_lease WHERE resource = 'matching-engine' \gset
BEGIN;
SELECT out_seq FROM append_command_prologue(:'holder', :epoch, true, $1, '$2', $3::smallint,
  $4, probe_payload('$5', 'LIMIT', '$6', '$7', NULL), $8::smallint, $9) \gset
SELECT '$2 row', :out_seq, append_command_journal_row(:out_seq, $3::smallint, 'NEW', $1, '$2', $4, 1, 1,
  probe_payload('$5', 'LIMIT', '$6', '$7', NULL), 0, :'holder', :epoch);
COMMIT;
EOF
}
c21_settle() {  # 1 주문 id, 2 event_seq, 3 market_seq. ACCEPTED 정산 흉내(예약 행의 금액·계정으로 orders OPEN)
db c21 <<EOF
INSERT INTO orders (id, external_id, user_id, market_id, client_order_id, side, ord_type, price, quantity,
                    remaining_qty, locked_account_id, locked_amount, status, command_seq)
SELECT r.order_id, gen_random_uuid(), r.user_id, r.market_id, c.client_order_id, c.payload ->> 'side', 'LIMIT',
       (c.payload ->> 'price')::numeric, (c.payload ->> 'qty')::numeric, (c.payload ->> 'qty')::numeric,
       r.locked_account_id, r.amount, 'OPEN', r.command_seq
  FROM order_reservations r JOIN command_journal c ON c.seq = r.command_seq WHERE r.order_id = $1;
INSERT INTO event_journal (event_seq, source_cmd_seq, event_ordinal, event_type, payload, fencing_epoch, created_at, market_id, market_seq)
SELECT $2, r.command_seq, 0, 'ACCEPTED', '{}'::jsonb, c.fencing_token, now(), r.market_id, $3
  FROM order_reservations r JOIN command_journal c ON c.seq = r.command_seq WHERE r.order_id = $1;
EOF
}
echo "-- 커서 위 정산 전 예약 하나(마켓 3, alice 매도 A2 0.1). 두 판 모두 0행, 게이지 pending 1·나이 2초 이상 기대"
c21_reserve 1 c21-pend 3 7001 SELL 100 0.1 2 0.1
echo "[커서 판]"; db c21 < /c/c14p.sql
echo "[전수 판]"; db c21 < /tmp/c14p_full.sql
sleep 2
db c21 < /tmp/gauge.sql
echo "-- 정산 틈(음성 대조): 마켓 2 에서 bob 매도 둘을 예약하고 뒤의 것만 정산한다. 앞의 것은 커서 아래에 정산 전으로 남는다"
c21_reserve 2 c21-gap 2 7002 SELL 100 0.1 3 0.1
c21_reserve 2 c21-next 2 7003 SELL 100 0.1 3 0.1
c21_settle 7003 700000400 400
echo "-- 커서 판(문서 원문): 초과 LOCKED 1행 기대(틈이 드러난다). 전수 판: 0행(틈을 정산 전 예약으로 센다)"
echo "[커서 판]"; db c21 < /c/c14p.sql
echo "[전수 판]"; db c21 < /tmp/c14p_full.sql
echo "-- 틈과 커서 위 예약을 정산한 뒤 두 판 모두 0행, 게이지 pending 0"
c21_settle 7002 700000401 401
c21_settle 7001 700000402 402
echo "[커서 판]"; db c21 < /c/c14p.sql
echo "[전수 판]"; db c21 < /tmp/c14p_full.sql
db c21 < /tmp/gauge.sql

echo
echo "== C22 잠금 계획 함수의 권한과 반환(앱 로그인은 실행, 조회 로그인은 거부). 잠근 행 수를 돌려준다(중복·없는 id 는 세지 않는다)"
app contract <<'EOF'
BEGIN;
SELECT 'lock_balance_rows(app)', lock_balance_rows(ARRAY[probe_acct(1, 'AVAILABLE', 1::smallint), probe_acct(1, 'LOCKED', 1::smallint),
                                                        probe_acct(1, 'LOCKED', 1::smallint), -1]);
ROLLBACK;
EOF
psql -X -U rdr -d contract -At -v ON_ERROR_STOP=0 -c "SELECT lock_balance_rows(ARRAY[1::bigint])" 2>&1 || true
echo
echo "== 끝"
