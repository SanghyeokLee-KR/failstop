-- V48 까지 적용한 DB 에 기존 데이터를 흉내 낸다(ADR 0034 S3 설계 3절 계약 확인).
-- 자산: KRW(scale 0), A2·A3(scale 8), Q18(scale 18). 마켓: 1 = A2/KRW, 2 = A3/KRW, 3 = A2/Q18.
-- 사용자: alice, bob. alice 는 KRW 1,000,000 과 A2 5, bob 은 KRW 500,000 과 A3 5 를 GENESIS 로 받는다.
-- 리스: matching-engine 을 old-node 가 epoch 41 로 쥐고 있다(S2 계약 확인과 같은 출발점).
INSERT INTO assets (id, symbol, name, kind, scale) VALUES
  (1, 'KRW', 'KRW', 'FIAT', 0), (2, 'A2', 'A2', 'CRYPTO', 8), (3, 'A3', 'A3', 'CRYPTO', 8),
  (4, 'Q18', 'Q18', 'CRYPTO', 18);
INSERT INTO markets (id, symbol, base_asset_id, quote_asset_id, tick_size, lot_size, min_notional, max_notional, max_qty)
VALUES (1, 'M1', 2, 1, 1, 0.0001, 0, 1000000000, 1000000),
       (2, 'M2', 3, 1, 1, 0.0001, 0, 1000000000, 1000000),
       (3, 'M3', 2, 4, 0.000000000000000001, 0.00000001, 0, 1000000000, 1000000);
INSERT INTO users (email) VALUES ('alice@s3.probe'), ('bob@s3.probe');
INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard)
SELECT 'SYSTEM', 0, 'GENESIS', a.id, 0 FROM assets a;
INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard)
SELECT 'USER', u.id, p.purpose, a.id, 0
FROM users u CROSS JOIN assets a CROSS JOIN (VALUES ('AVAILABLE'), ('LOCKED')) AS p(purpose);

CREATE FUNCTION probe_acct(p_user BIGINT, p_purpose TEXT, p_asset SMALLINT) RETURNS BIGINT LANGUAGE sql STABLE AS $$
    SELECT id FROM accounts WHERE owner_type = CASE WHEN p_user = 0 THEN 'SYSTEM' ELSE 'USER' END
      AND owner_id = p_user AND purpose = p_purpose AND asset_id = p_asset AND shard = 0
$$;
CREATE FUNCTION probe_genesis(p_event BIGINT, p_user BIGINT, p_asset SMALLINT, p_amount NUMERIC) RETURNS BIGINT
LANGUAGE sql AS $$
    SELECT post_ledger_transaction('GENESIS', p_event, jsonb_build_array(
        jsonb_build_object('account_id', probe_acct(0, 'GENESIS', p_asset), 'asset_id', p_asset,
                           'amount', -p_amount, 'entry_type', 'GENESIS'),
        jsonb_build_object('account_id', probe_acct(p_user, 'AVAILABLE', p_asset), 'asset_id', p_asset,
                           'amount', p_amount, 'entry_type', 'GENESIS')))
$$;
SELECT probe_genesis(1, 1, 1::smallint, 1000000), probe_genesis(2, 1, 2::smallint, 5),
       probe_genesis(3, 2, 1::smallint, 500000), probe_genesis(4, 2, 3::smallint, 5);

-- 주문 생성 명령 payload. 게이트웨이(CommandPayloadMapper.toPayloadJson)와 같은 키를 쓴다.
CREATE FUNCTION probe_payload(p_side TEXT, p_type TEXT, p_price TEXT, p_qty TEXT, p_quote TEXT) RETURNS JSONB
LANGUAGE sql IMMUTABLE AS $$
    SELECT jsonb_build_object('side', p_side, 'ord_type', p_type, 'time_in_force', 'GTC',
                              'price', p_price, 'qty', p_qty, 'quote_order_qty', p_quote,
                              'external_id', md5(coalesce(p_price, '') || coalesce(p_qty, '') || coalesce(p_quote, '')),
                              'maker_fee_rate', '0.0005', 'taker_fee_rate', '0.0005')
$$;
UPDATE leader_lease SET holder = 'old-node', epoch = 41, lease_until = clock_timestamp() + interval '1 hour'
 WHERE resource = 'matching-engine';
