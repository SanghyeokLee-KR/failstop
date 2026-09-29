#!/bin/sh
# 컨테이너 안(run-host.sh 가 부른다). V0~V47.1 을 파일마다 한 트랜잭션으로 올린 DB 에서 소유자 권한 함수의
# search_path 가림과 V47.2 적용 전후를 본다.
#   botu : app_role 에 속한 봇 흉내 로그인. 함수 소유자가 아니다.
M=/probe-migration
V472=$M/V47_2__pin_security_definer_search_path.sql
SU="psql -X -U postgres -d exchange -At -v ON_ERROR_STOP=0"
HDR='\set VERBOSITY verbose
\set SHOW_CONTEXT never'
as_bot() { psql -X -U botu -d exchange -At -v ON_ERROR_STOP=0 2>&1; }

files="$(ls $M/V*__*.sql | grep -v '/V47_[0-9]__' | sed -E 's|.*/V([0-9]+)__.*|\1 &|' | sort -n | cut -d' ' -f2) $M/V47_1__revoke_trigger_function_public_execute.sql"
for f in $files; do
  psql -X -U postgres -d exchange -q -1 -v ON_ERROR_STOP=1 -f "$f" >/dev/null 2>/tmp/mig.err \
    || { echo "MIGRATION FAILED: $f"; cat /tmp/mig.err; exit 1; }
done
echo "applied V0..V47.1: $(echo $files | wc -w) files"
echo "V47_2 sha256: $(sha256sum "$V472" | cut -d' ' -f1)"
$SU -q <<'EOF'
INSERT INTO assets (id, symbol, name, kind, scale) VALUES (1, 'BTC', 'Bitcoin', 'CRYPTO', 8), (2, 'KRW', 'Korean Won', 'FIAT', 0);
INSERT INTO markets (id, symbol, base_asset_id, quote_asset_id, tick_size, lot_size, min_notional, max_notional, max_qty)
VALUES (1, 'BTC-KRW', 1, 2, 1, 0.0001, 0, 100000000000, 10000);
CREATE ROLE botu LOGIN;
GRANT app_role TO botu;
EOF

show_config() {
  $SU -c "SELECT '$1', oid::regprocedure, proconfig FROM pg_proc
          WHERE prosecdef AND pronamespace = 'public'::regnamespace ORDER BY oid::regprocedure::text"
}

# 사용자가 2026-09-24 에 재현한 경로다(revoke-trigger-fn-20260924/search-path-probe.log S1). $1 은 표지, $2 는 체결 id.
trade_shadow() {
  as_bot <<EOF
$HDR
CREATE TEMP TABLE trade_seq_registry (seq bigint, trade_created_at timestamptz, created_at timestamptz DEFAULT now());
GRANT ALL ON trade_seq_registry TO PUBLIC;
INSERT INTO trades (id, market_id, maker_order_id, taker_order_id, maker_user_id, taker_user_id, taker_side, price, quantity, fencing_epoch, executed_at, created_at)
VALUES ($2, 1, 1, 2, 11, 12, 'BUY', 100, 1, 1, now(), now());
INSERT INTO trades (id, market_id, maker_order_id, taker_order_id, maker_user_id, taker_user_id, taker_side, price, quantity, fencing_epoch, executed_at, created_at)
VALUES ($2, 1, 3, 4, 13, 14, 'SELL', 100, 1, 1, now() + interval '1 second', now() + interval '1 second');
SELECT '$1 temp trade registry rows', count(*) FROM pg_temp.trade_seq_registry;
EOF
  $SU <<EOF
SELECT '$1 trades with id $2', count(*) FROM trades WHERE id = $2;
SELECT '$1 real trade registry rows $2', count(*) FROM public.trade_seq_registry WHERE seq = $2;
EOF
}

# 같은 (source_cmd_seq, event_ordinal) 을 event_seq 만 달리해 두 번 넣는다. $2 는 source_cmd_seq.
event_shadow() {
  as_bot <<EOF
$HDR
CREATE TEMP TABLE event_emission_registry (source_cmd_seq bigint, event_ordinal int, event_seq bigint, created_at timestamptz DEFAULT now());
GRANT ALL ON event_emission_registry TO PUBLIC;
INSERT INTO event_journal (event_seq, source_cmd_seq, event_ordinal, event_type, payload, fencing_epoch, created_at)
VALUES ($2, $2, 0, 'ACCEPTED', '{}', 1, now());
INSERT INTO event_journal (event_seq, source_cmd_seq, event_ordinal, event_type, payload, fencing_epoch, created_at)
VALUES ($2 + 1, $2, 0, 'ACCEPTED', '{}', 1, now());
SELECT '$1 temp event registry rows', count(*) FROM pg_temp.event_emission_registry;
EOF
  $SU <<EOF
SELECT '$1 event_journal rows for source $2', count(*) FROM event_journal WHERE source_cmd_seq = $2;
SELECT '$1 real event registry rows $2', count(*) FROM public.event_emission_registry WHERE source_cmd_seq = $2;
EOF
}

echo
echo "== A0 적용 전 public 소유자 권한 함수의 설정"
show_config A0
echo
echo "== A1 적용 전: botu 가 임시 trade_seq_registry 로 가리고 같은 체결 id 를 created_at 만 달리해 두 번 넣는다"
trade_shadow A1 888001
echo
echo "== A2 적용 전: botu 가 임시 event_emission_registry 로 가리고 같은 방출 키를 두 번 넣는다"
event_shadow A2 777001
echo
echo "== A3 함수 이름은 임시 스키마에서 찾지 않는다. 한정하지 않은 호출은 42883, pg_temp 로 한정하면 불린다"
as_bot <<EOF
$HDR
CREATE FUNCTION pg_temp.sp_probe_fn() RETURNS int LANGUAGE sql AS 'SELECT 1';
SELECT 'A3 unqualified', sp_probe_fn();
SELECT 'A3 qualified', pg_temp.sp_probe_fn();
EOF

echo
echo "== B 소유자가 아닌 botu 가 V47_2 를 적용한다"
psql -X -U botu -d exchange -At -1 -v ON_ERROR_STOP=1 -f "$V472" 2>&1
echo "B1 V47_2 file exit=$?"
$SU -c "SELECT 'B2 pinned functions', count(*) FROM pg_proc WHERE prosecdef AND pronamespace = 'public'::regnamespace
        AND 'search_path=public, pg_temp' = ANY (proconfig)"

echo
echo "== C 소유자가 V47_2 를 적용한다"
psql -X -U postgres -d exchange -At -1 -v ON_ERROR_STOP=1 -f "$V472" 2>&1
echo "C0 V47_2 file exit=$?"
show_config C1

echo
echo "== D 적용 뒤 A1 과 같은 가림"
trade_shadow D1 888002
echo
echo "== E 적용 뒤 A2 와 같은 가림"
event_shadow E1 777101

echo
echo "== F 적용 뒤 원장 게시: botu 가 accounts, balances, ledger_transactions, ledger_entries 와 같은 이름의 임시 표를 두고 예약·해제를 부른다"
$SU -q <<'EOF'
INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard) VALUES
    ('SYSTEM', 0, 'GENESIS', 1, 9), ('USER', 201, 'AVAILABLE', 1, 0), ('USER', 201, 'LOCKED', 1, 0);
SELECT post_ledger_transaction('GENESIS', 6001, jsonb_build_array(
    jsonb_build_object('account_id', (SELECT id FROM accounts WHERE purpose = 'GENESIS' AND shard = 9), 'asset_id', 1, 'amount', -10, 'entry_type', 'GENESIS'),
    jsonb_build_object('account_id', (SELECT id FROM accounts WHERE owner_id = 201 AND purpose = 'AVAILABLE'), 'asset_id', 1, 'amount', 10, 'entry_type', 'GENESIS')));
EOF
as_bot <<EOF
$HDR
CREATE TEMP TABLE accounts (LIKE public.accounts INCLUDING ALL);
CREATE TEMP TABLE balances (LIKE public.balances INCLUDING ALL);
CREATE TEMP TABLE ledger_transactions (LIKE public.ledger_transactions INCLUDING ALL);
CREATE TEMP TABLE ledger_entries (LIKE public.ledger_entries INCLUDING ALL);
GRANT ALL ON accounts, balances, ledger_transactions, ledger_entries TO PUBLIC;
SELECT 'F1 reserve txn', reserve_funds(6002,
    (SELECT id FROM public.accounts WHERE owner_id = 201 AND purpose = 'AVAILABLE'),
    (SELECT id FROM public.accounts WHERE owner_id = 201 AND purpose = 'LOCKED'), 1::smallint, 4) > 0;
SELECT 'F1 release txn', release_funds(6003,
    (SELECT id FROM public.accounts WHERE owner_id = 201 AND purpose = 'LOCKED'),
    (SELECT id FROM public.accounts WHERE owner_id = 201 AND purpose = 'AVAILABLE'), 1::smallint, 1) > 0;
SELECT 'F1 temp rows', 'accounts', count(*) FROM pg_temp.accounts;
SELECT 'F1 temp rows', 'balances', count(*) FROM pg_temp.balances;
SELECT 'F1 temp rows', 'ledger_transactions', count(*) FROM pg_temp.ledger_transactions;
SELECT 'F1 temp rows', 'ledger_entries', count(*) FROM pg_temp.ledger_entries;
EOF
$SU <<'EOF'
SELECT 'F2 real sealed txns', business_event_type, business_event_id, status FROM ledger_transactions
WHERE business_event_id IN (6002, 6003) ORDER BY business_event_id;
SELECT 'F2 real entries', count(*) FROM ledger_entries le JOIN ledger_transactions t ON t.id = le.transaction_id
WHERE t.business_event_id IN (6002, 6003);
SELECT 'F2 real balance', a.purpose, b.balance FROM balances b JOIN accounts a ON a.id = b.account_id
WHERE a.owner_id = 201 ORDER BY a.purpose;
SELECT 'F2 mismatch rows', count(*) FROM balance_ledger_check WHERE mismatch;
EOF

echo
echo "== G 설정 없이 CREATE OR REPLACE 하면 설정이 사라진다(롤백)"
$SU <<'EOF'
BEGIN;
CREATE OR REPLACE FUNCTION register_trade_seq() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER AS $$
BEGIN
    INSERT INTO trade_seq_registry (seq, trade_created_at) VALUES (NEW.id, NEW.created_at);
    RETURN NEW;
END $$;
SELECT 'G proconfig', proconfig IS NULL FROM pg_proc WHERE proname = 'register_trade_seq';
ROLLBACK;
SELECT 'G after rollback', proconfig FROM pg_proc WHERE proname = 'register_trade_seq';
EOF
