-- ============================================================================
-- V10__orders_fee_projection.sql
-- 원천: 종합 적대감사 R2 rank2(high). OrderQueryService.view가 주문 조회마다
-- trades를 maker_order_id/taker_order_id로 재집계(feeStats)했다. V8이 order_id 축
-- 인덱스를 깔았으나 목록 응답은 행마다 별도 집계 쿼리(N+1)를 돌고 집계 하한이 없어
-- 봇 부하·파티션 증가에서 악화됐다. paid_fee·trades_count를 orders에 물질화하고
-- SettlementService 체결 경로가 filled_qty·executed_quote와 같은 UPDATE에서
-- 결정론적으로 누적한다. 조회는 orders 단일 스캔으로 답한다.
-- 결정론: 두 컬럼은 정산이 재생 시 재구성하는 프로젝션이다(엔진 상태 아님).
-- filled_qty·executed_quote와 동일 트랜잭션·동일 누적 규약이라 재생·크래시 창
-- 정합이 기존 집계 컬럼과 같다.
-- 백필: 이미 체결된 주문은 attach된 trades 파티션 합으로 채운다. detach된(90일
-- 경과) 파티션은 종전 feeStats도 못 보던 범위라 종전 응답과 동치다.
-- paid_fee는 주문 자기 측 수수료다: 주문이 maker면 maker_fee, taker면 taker_fee.
-- 한 주문은 한 측이라 단일 자산으로 누적된다(매수=base, 매도=quote).
-- ============================================================================
SET timezone = 'UTC';

ALTER TABLE orders
    ADD COLUMN paid_fee     NUMERIC(40,18) NOT NULL DEFAULT 0 CHECK (paid_fee >= 0),
    ADD COLUMN trades_count BIGINT         NOT NULL DEFAULT 0 CHECK (trades_count >= 0);

UPDATE orders o
SET paid_fee = agg.fee,
    trades_count = agg.cnt
FROM (
    SELECT oid, SUM(fee) AS fee, COUNT(*) AS cnt
    FROM (
        SELECT maker_order_id AS oid, maker_fee AS fee FROM trades
        UNION ALL
        SELECT taker_order_id AS oid, taker_fee AS fee FROM trades
    ) legs
    GROUP BY oid
) agg
WHERE o.id = agg.oid;
