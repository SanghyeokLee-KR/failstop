-- V35 적용 직후. 자산 13, 마켓 12. V36 시드와 V40 대역 잎이 기존 마켓 기준으로 생기게 먼저 넣는다.
\set ON_ERROR_STOP 1
INSERT INTO assets (id, symbol, name, kind, scale) VALUES (1, 'KRW', 'Won', 'FIAT', 0);
INSERT INTO assets (id, symbol, name, kind, scale)
SELECT i + 1, 'C' || i, 'Coin ' || i, 'CRYPTO', 8 FROM generate_series(1, 12) i;
INSERT INTO markets (id, symbol, base_asset_id, quote_asset_id, tick_size, lot_size,
                     min_notional, max_notional, max_qty)
SELECT i, 'C' || i || '-KRW', i + 1, 1, 1, 0.00000001, 5000, 1000000000000, 1000000
FROM generate_series(1, 12) i;

-- 합성 분포 함수. 결정론 해시(hashint8extended)라 재실행해도 같은 데이터가 나온다.
-- 마켓 1 = 40%, 마켓 12 = 1%, 마켓 2..11 = 각 5.9%.
CREATE SCHEMA bench;
CREATE FUNCTION bench.mkt(s bigint) RETURNS smallint IMMUTABLE LANGUAGE sql AS $$
  SELECT (CASE WHEN h < 400 THEN 1
               WHEN h < 410 THEN 12
               ELSE 2 + (((hashint8extended(s, 2) % 10) + 10) % 10) END)::smallint
  FROM (SELECT ((hashint8extended(s, 1) % 1000) + 1000) % 1000 AS h) x
$$;
-- NEW 85%, CANCEL 15%
CREATE FUNCTION bench.is_cancel(s bigint) RETURNS boolean IMMUTABLE LANGUAGE sql AS $$
  SELECT ((hashint8extended(s, 4) % 100) + 100) % 100 >= 85
$$;
-- NEW 명령의 이벤트 수 1..3 (ACCEPTED + TRADE 0..2). CANCEL 은 1(CANCELED).
CREATE FUNCTION bench.nevt(s bigint) RETURNS int IMMUTABLE LANGUAGE sql AS $$
  SELECT CASE WHEN bench.is_cancel(s) THEN 1
              ELSE ((((hashint8extended(s, 3) % 3) + 3) % 3)::int + 1) END
$$;
