-- V47 까지 적용한 DB 에 기존 데이터(마켓 셋, 리스 epoch 41)를 흉내 낸다.
INSERT INTO assets (id, symbol, name, kind, scale) VALUES
  (1, 'KRW', 'KRW', 'FIAT', 0), (2, 'A2', 'A2', 'CRYPTO', 8), (3, 'A3', 'A3', 'CRYPTO', 8),
  (4, 'A4', 'A4', 'CRYPTO', 8), (5, 'A5', 'A5', 'CRYPTO', 8);
INSERT INTO markets (id, symbol, base_asset_id, quote_asset_id, tick_size, lot_size, min_notional, max_notional, max_qty)
VALUES (1, 'M1', 2, 1, 1, 0.0001, 0, 1000000000, 1000000),
       (2, 'M2', 3, 1, 1, 0.0001, 0, 1000000000, 1000000),
       (3, 'M3', 4, 1, 1, 0.0001, 0, 1000000000, 1000000);
UPDATE leader_lease SET holder = 'old-node', epoch = 41, lease_until = clock_timestamp() + interval '1 hour'
 WHERE resource = 'matching-engine';
