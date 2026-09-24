#!/bin/sh
# 컨테이너 안(run-host.sh 가 부른다). V0~V47 을 파일마다 한 트랜잭션으로 올린 DB 에서 V47.1 의 판단 근거를 확인한다.
#   rdr  : app_role 이 아닌 조회 로그인. 배포와 같이 assets·markets·trades SELECT 와 기본 읽기 전용만 준다.
#   botu : app_role 에 속한 봇 로그인. 함수 소유자가 아니다.
SU="psql -X -U postgres -d exchange -At -v ON_ERROR_STOP=0"
HDR='\set VERBOSITY verbose
\set SHOW_CONTEXT never'
as_role() { psql -X -U "$1" -d exchange -At -v ON_ERROR_STOP=0 2>&1; }
M=/probe-migration
V471=$M/V47_1__revoke_trigger_function_public_execute.sql
FNS="ARRAY['create_balance_for_account','register_trade_seq','register_event_emission','seed_market_journal_counters']"

for f in $(ls $M/V*__*.sql | grep -v '/V47_1__' | sed -E 's|.*/V([0-9]+)__.*|\1 &|' | sort -n | cut -d' ' -f2); do
  psql -X -U postgres -d exchange -q -1 -v ON_ERROR_STOP=1 -f "$f" >/dev/null 2>/tmp/mig.err \
    || { echo "MIGRATION FAILED: $f"; cat /tmp/mig.err; exit 1; }
done
echo "applied V0..V47: $(ls $M/V*__*.sql | grep -vc '/V47_1__') files"
echo "V47_1 sha256: $(sha256sum "$V471" | cut -d' ' -f1)"
$SU -q <<'EOF'
INSERT INTO assets (id, symbol, name, kind, scale) VALUES (1, 'BTC', 'Bitcoin', 'CRYPTO', 8), (2, 'KRW', 'Korean Won', 'FIAT', 0);
CREATE ROLE rdr LOGIN;
GRANT USAGE ON SCHEMA public TO rdr;
GRANT SELECT ON assets, markets, trades TO rdr;
ALTER ROLE rdr SET default_transaction_read_only = on;
CREATE ROLE botu LOGIN;
GRANT app_role TO botu;
EOF

echo
echo "== A0 회수 전 EXECUTE. 열: 함수, rdr, app_role, public"
$SU -c "SELECT 'A0', fn, has_function_privilege('rdr', fn || '()', 'EXECUTE'), has_function_privilege('app_role', fn || '()', 'EXECUTE'), has_function_privilege('public', fn || '()', 'EXECUTE') FROM unnest($FNS) AS fn"

echo
echo "== A1 회수 전: rdr 이 기본 읽기 전용을 끄고 임시 표 트리거로 네 함수를 소유자 권한으로 돌린다"
as_role rdr <<EOF
$HDR
SET default_transaction_read_only = off;
SELECT 'A1 rdr INSERT privilege', t, has_table_privilege(t, 'INSERT')
FROM unnest(ARRAY['event_emission_registry','trade_seq_registry','market_journal_counters','balances']) AS t;
CREATE TEMP TABLE e (source_cmd_seq bigint, event_ordinal int, event_seq bigint);
CREATE TRIGGER plant AFTER INSERT ON e FOR EACH ROW EXECUTE FUNCTION public.register_event_emission();
INSERT INTO e VALUES (777001, 0, 777001);
CREATE TEMP TABLE t (id bigint, created_at timestamptz);
CREATE TRIGGER plant AFTER INSERT ON t FOR EACH ROW EXECUTE FUNCTION public.register_trade_seq();
INSERT INTO t VALUES (777002, now());
CREATE TEMP TABLE m (id smallint);
CREATE TRIGGER plant AFTER INSERT ON m FOR EACH ROW EXECUTE FUNCTION public.seed_market_journal_counters();
INSERT INTO m VALUES (50);
CREATE TEMP TABLE a (id bigint, allow_negative boolean);
CREATE TRIGGER plant AFTER INSERT ON a FOR EACH ROW EXECUTE FUNCTION public.create_balance_for_account();
INSERT INTO a VALUES (999999, true);
EOF
$SU <<'EOF'
SELECT 'A2 planted event registry', count(*) FROM event_emission_registry WHERE source_cmd_seq = 777001;
SELECT 'A2 planted trade registry', count(*) FROM trade_seq_registry WHERE seq = 777002;
SELECT 'A2 market 50 in markets', count(*) FROM markets WHERE id = 50;
SELECT 'A2 market 50 counters', count(*) FROM market_journal_counters WHERE market_id = 50;
SELECT 'A2 market 50 leaves', to_regclass('command_journal_b50') IS NOT NULL, to_regclass('event_journal_b50') IS NOT NULL;
SELECT 'A2 balance 999999', count(*) FROM balances WHERE account_id = 999999;
EOF

echo
echo "== B 소유자가 아닌 botu 가 회수를 시도한다. REVOKE 네 줄만 돌리면 경고만 내고 끝나고, V47_1 파일은 끝 확인에서 멈춘다"
{ echo "$HDR"; grep '^REVOKE' "$V471"; } | psql -X -U botu -d exchange -At -v ON_ERROR_STOP=1 2>&1
echo "B1 revoke lines exit=$? (ON_ERROR_STOP=1)"
psql -X -U botu -d exchange -At -1 -v ON_ERROR_STOP=1 -f "$V471" 2>&1
echo "B2 V47_1 file exit=$?"
$SU -c "SELECT 'B3 public still EXECUTE', fn, has_function_privilege('public', fn || '()', 'EXECUTE') FROM unnest($FNS) AS fn"

echo
echo "== C 소유자가 V47_1 을 적용한다"
psql -X -U postgres -d exchange -At -1 -v ON_ERROR_STOP=1 -f "$V471" 2>&1
echo "C0 V47_1 file exit=$?"
$SU -c "SELECT 'C1', fn, has_function_privilege('rdr', fn || '()', 'EXECUTE'), has_function_privilege('app_role', fn || '()', 'EXECUTE'), has_function_privilege('public', fn || '()', 'EXECUTE') FROM unnest($FNS) AS fn"

for r in rdr botu; do
  echo
  echo "== C2 회수 뒤 $r 의 임시 표 트리거"
  as_role $r <<EOF
$HDR
SET default_transaction_read_only = off;
CREATE TEMP TABLE p (id smallint, source_cmd_seq bigint, event_ordinal int, event_seq bigint);
CREATE TRIGGER p1 AFTER INSERT ON p FOR EACH ROW EXECUTE FUNCTION public.create_balance_for_account();
CREATE TRIGGER p2 AFTER INSERT ON p FOR EACH ROW EXECUTE FUNCTION public.register_trade_seq();
CREATE TRIGGER p3 AFTER INSERT ON p FOR EACH ROW EXECUTE FUNCTION public.register_event_emission();
CREATE TRIGGER p4 AFTER INSERT ON p FOR EACH ROW EXECUTE FUNCTION public.seed_market_journal_counters();
EOF
done

echo
echo "== C3 회수 뒤 botu 의 정상 쓰기. 트리거는 EXECUTE 없이 발화한다"
as_role botu <<'EOF'
\set VERBOSITY verbose
\set SHOW_CONTEXT never
INSERT INTO markets (id, symbol, base_asset_id, quote_asset_id, tick_size, lot_size, min_notional, max_notional, max_qty)
VALUES (51, 'PROBE-KRW', 1, 2, 1, 0.0001, 0, 100000000000, 10000);
INSERT INTO accounts (owner_type, owner_id, purpose, asset_id) VALUES ('USER', 991, 'AVAILABLE', 1);
INSERT INTO trades (id, market_id, maker_order_id, taker_order_id, maker_user_id, taker_user_id, taker_side, price, quantity,
                    fencing_epoch, executed_at, created_at)
VALUES (777101, 51, 1, 2, 11, 12, 'BUY', 100, 1, 1, now(), now());
INSERT INTO event_journal (event_seq, source_cmd_seq, event_ordinal, event_type, payload, fencing_epoch, created_at)
VALUES (777102, 777102, 0, 'ACCEPTED', '{}', 1, now());
EOF
$SU <<'EOF'
SELECT 'C4 market 51 counters', count(*) FROM market_journal_counters WHERE market_id = 51;
SELECT 'C4 market 51 leaves', to_regclass('command_journal_b51') IS NOT NULL, to_regclass('event_journal_b51') IS NOT NULL;
SELECT 'C4 balance rows for owner 991', count(*) FROM balances b JOIN accounts a ON a.id = b.account_id WHERE a.owner_id = 991;
SELECT 'C4 trade registry', count(*) FROM trade_seq_registry WHERE seq = 777101;
SELECT 'C4 event registry', count(*) FROM event_emission_registry WHERE source_cmd_seq = 777102;
EOF
