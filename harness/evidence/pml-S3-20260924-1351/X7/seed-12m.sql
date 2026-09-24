-- X7 합성 DB 시드(S3 설계 7-3 X7). 12마켓 운영 분포를 흉내 낸다. 자산은 KRW(1)과 기준 자산 2~13, 마켓 m 은 기준 자산 m+1 을
-- 원화로 거래한다. 사용자는 둘이고 모든 자산에 AVAILABLE·LOCKED 계정을 둔다(잔고 행은 계정 트리거가 만든다). 합성 행은 트리거를
-- 끄고 넣으므로 원장 균형과 잔고 정합은 성립하지 않고 시간만 본다.
INSERT INTO assets (id, symbol, name, kind, scale)
SELECT 1, 'KRW', 'KRW', 'FIAT', 0
UNION ALL SELECT a, 'X' || a, 'X' || a, 'CRYPTO', 8 FROM generate_series(2, 13) a;
INSERT INTO markets (id, symbol, base_asset_id, quote_asset_id, tick_size, lot_size, min_notional, max_notional, max_qty)
SELECT m, 'KRW-X' || (m + 1), m + 1, 1, 1, 0.0001, 0, 1000000000, 1000000 FROM generate_series(1, 12) m;
INSERT INTO users (email) VALUES ('alice@s3.x7'), ('bob@s3.x7');
INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard)
SELECT 'SYSTEM', 0, 'GENESIS', a.id, 0 FROM assets a;
INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard)
SELECT 'USER', u.id, p.purpose, a.id, 0
FROM users u CROSS JOIN assets a CROSS JOIN (VALUES ('AVAILABLE'), ('LOCKED')) AS p(purpose);
UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' WHERE resource = 'matching-engine';
