-- DevDataSeeder와 같은 자산·마켓 기본값. 거래·회원·잔고는 초기화하지 않는다.
-- 최초 배포의 빈 카탈로그에만 실행한다. 재실행은 아래 검사에서 중단된다.
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
SET LOCAL ROLE exchange_owner;
LOCK TABLE assets, markets IN SHARE ROW EXCLUSIVE MODE;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM assets) OR EXISTS (SELECT 1 FROM markets) THEN
        RAISE EXCEPTION 'assets 또는 markets가 비어 있지 않다. 기존 값을 먼저 확인한다.';
    END IF;
END $$;

INSERT INTO assets (id, symbol, name, kind, scale) VALUES
 (1,'KRW','원화','FIAT',0), (2,'BTC','비트코인','CRYPTO',8),
 (3,'ETH','이더리움','CRYPTO',18), (4,'SOL','솔라나','CRYPTO',6),
 (5,'XRP','리플','CRYPTO',2), (6,'DOGE','도지코인','CRYPTO',2),
 (7,'ADA','에이다','CRYPTO',2), (8,'TRX','트론','CRYPTO',2),
 (9,'LINK','체인링크','CRYPTO',4), (10,'DOT','폴카닷','CRYPTO',4),
 (11,'MATIC','폴리곤','CRYPTO',2), (12,'AVAX','아발란체','CRYPTO',4),
 (13,'ATOM','코스모스','CRYPTO',4);

INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard)
SELECT 'SYSTEM', 0, p.purpose, a.id, 0
FROM assets a CROSS JOIN (VALUES ('FEE_INCOME'),('GENESIS'),('GATEWAY')) p(purpose);

INSERT INTO markets
 (id, symbol, base_asset_id, quote_asset_id, tick_size, lot_size,
  min_notional, max_notional, max_qty, maker_fee_rate, taker_fee_rate) VALUES
 (1,'KRW-BTC',2,1,1000,0.00000001,5000,1000000000,1000,0.0005,0.0005),
 (2,'KRW-ETH',3,1,1000,0.00000001,5000,1000000000,100000,0.0005,0.0005),
 (3,'KRW-SOL',4,1,50,0.000001,5000,1000000000,10000000,0.0005,0.0005),
 (4,'KRW-XRP',5,1,1,0.01,5000,1000000000,100000000,0.0005,0.0005),
 (5,'KRW-DOGE',6,1,1,0.01,5000,1000000000,1000000000,0.0005,0.0005),
 (6,'KRW-ADA',7,1,1,0.01,5000,1000000000,100000000,0.0005,0.0005),
 (7,'KRW-TRX',8,1,1,0.01,5000,1000000000,100000000,0.0005,0.0005),
 (8,'KRW-LINK',9,1,10,0.0001,5000,1000000000,10000000,0.0005,0.0005),
 (9,'KRW-DOT',10,1,5,0.0001,5000,1000000000,100000000,0.0005,0.0005),
 (10,'KRW-MATIC',11,1,1,0.01,5000,1000000000,100000000,0.0005,0.0005),
 (11,'KRW-AVAX',12,1,10,0.0001,5000,1000000000,10000000,0.0005,0.0005),
 (12,'KRW-ATOM',13,1,5,0.0001,5000,1000000000,10000000,0.0005,0.0005);
COMMIT;
SELECT (SELECT count(*) FROM assets) AS assets,
       (SELECT count(*) FROM markets) AS markets,
       (SELECT count(*) FROM accounts WHERE owner_type='SYSTEM') AS system_accounts;
