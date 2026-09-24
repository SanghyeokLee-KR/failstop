#!/bin/sh
# 컨테이너 안(search-path-run.sh 가 부른다). V47.1 까지 올린 DB 에서, V47.1 과 별개로 남은 search_path 가림을 확인한다.
# 소유자 권한 함수의 search_path 가 public 뿐이라 pg_temp 가 먼저 찾아진다. app_role 에 속한 봇 흉내 로그인(botu)이 같은 이름의
# 임시 표로 trade_seq_registry 를 가리면 register_trade_seq 가 실제 레지스트리 대신 임시 표에 쓴다.
M=/probe-migration
for f in $(ls $M/V*__*.sql | grep -v '/V47_1__' | sed -E 's|.*/V([0-9]+)__.*|\1 &|' | sort -n | cut -d' ' -f2) $M/V47_1__revoke_trigger_function_public_execute.sql; do
  psql -X -U postgres -d exchange -q -1 -v ON_ERROR_STOP=1 -f "$f" >/dev/null 2>/tmp/mig.err || { echo "MIGRATION FAILED: $f"; cat /tmp/mig.err; exit 1; }
done
echo "applied V0..V47.1"
psql -X -U postgres -d exchange -q -At <<'SQL'
INSERT INTO assets (id, symbol, name, kind, scale) VALUES (1, 'BTC', 'Bitcoin', 'CRYPTO', 8), (2, 'KRW', 'Korean Won', 'FIAT', 0);
INSERT INTO markets (id, symbol, base_asset_id, quote_asset_id, tick_size, lot_size, min_notional, max_notional, max_qty)
VALUES (1, 'BTC-KRW', 1, 2, 1, 0.0001, 0, 100000000000, 10000);
CREATE ROLE botu LOGIN; GRANT app_role TO botu;
SELECT 'search_path of register_trade_seq', proconfig FROM pg_proc WHERE proname = 'register_trade_seq';
SQL
echo "== S1 botu: 임시 trade_seq_registry 로 실제 레지스트리를 가리고 같은 체결 id 를 두 번 넣는다"
psql -X -U botu -d exchange -At -v ON_ERROR_STOP=0 2>&1 <<'SQL'
\set VERBOSITY verbose
\set SHOW_CONTEXT never
CREATE TEMP TABLE trade_seq_registry (seq bigint, trade_created_at timestamptz, created_at timestamptz DEFAULT now());
GRANT ALL ON trade_seq_registry TO PUBLIC;
INSERT INTO trades (id, market_id, maker_order_id, taker_order_id, maker_user_id, taker_user_id, taker_side, price, quantity, fencing_epoch, executed_at, created_at)
VALUES (888001, 1, 1, 2, 11, 12, 'BUY', 100, 1, 1, now(), now());
INSERT INTO trades (id, market_id, maker_order_id, taker_order_id, maker_user_id, taker_user_id, taker_side, price, quantity, fencing_epoch, executed_at, created_at)
VALUES (888001, 1, 3, 4, 13, 14, 'SELL', 100, 1, 1, now() + interval '1 second', now() + interval '1 second');
SELECT 'S1 temp registry rows', count(*) FROM pg_temp.trade_seq_registry;
SQL
psql -X -U postgres -d exchange -At <<'SQL'
SELECT 'S2 trades with id 888001', count(*) FROM trades WHERE id = 888001;
SELECT 'S2 real registry rows 888001', count(*) FROM public.trade_seq_registry WHERE seq = 888001;
SQL
