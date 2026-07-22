-- ============================================================================
-- V8__trades_order_index_and_partitions.sql
-- 원천: docs/백엔드_DB_설계.md v2.13 §3.1(trades 인덱스), §3.2(파티션 유지관리).
-- 목적:
--   (1) trades order_id 축 조회 인덱스. OrderQueryService.feeStats가
--       maker_order_id/taker_order_id로 집계하나 해당 축 인덱스가 없어 전 파티션
--       seq scan이 발생했다(감사 High). 파티션드 부모에 건 인덱스는 전 자식과
--       향후 자식에 전파된다.
--   (2) 시간 파티션 사전생성(2026-09~2027-08, 12개월). 외부 확장(pg_partman/pg_cron)
--       없이 빈 PG17 단독 적용을 유지하려고 월 파티션을 수동 선생성한다(감사 Medium).
--       미선생성 시 2026-09 이후 전 행이 *_default로 유입되어 프루닝이 무력화된다.
--       partman 자동화는 확장 전제를 갖춘 환경의 선택 경로다(§3.2).
-- 트랜잭션 정합: CREATE INDEX는 CONCURRENTLY 없이 Flyway 트랜잭션 내에서 실행한다.
--   라이브 인스턴스는 다음 부팅 시 적용되며 부모 테이블에 짧은 락을 감수한다.
-- ============================================================================

-- (2) 시간 파티션 사전생성. 아래 부모 CREATE INDEX 전에 자식을 만들어 두면
-- 인덱스가 신규 자식까지 한 번에 덮는다. 명명은 partman 규칙(_pYYYY_MM)을 잇는다.
CREATE TABLE trades_p2026_09 PARTITION OF trades FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE trades_p2026_10 PARTITION OF trades FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE trades_p2026_11 PARTITION OF trades FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE trades_p2026_12 PARTITION OF trades FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');
CREATE TABLE trades_p2027_01 PARTITION OF trades FOR VALUES FROM ('2027-01-01') TO ('2027-02-01');
CREATE TABLE trades_p2027_02 PARTITION OF trades FOR VALUES FROM ('2027-02-01') TO ('2027-03-01');
CREATE TABLE trades_p2027_03 PARTITION OF trades FOR VALUES FROM ('2027-03-01') TO ('2027-04-01');
CREATE TABLE trades_p2027_04 PARTITION OF trades FOR VALUES FROM ('2027-04-01') TO ('2027-05-01');
CREATE TABLE trades_p2027_05 PARTITION OF trades FOR VALUES FROM ('2027-05-01') TO ('2027-06-01');
CREATE TABLE trades_p2027_06 PARTITION OF trades FOR VALUES FROM ('2027-06-01') TO ('2027-07-01');
CREATE TABLE trades_p2027_07 PARTITION OF trades FOR VALUES FROM ('2027-07-01') TO ('2027-08-01');
CREATE TABLE trades_p2027_08 PARTITION OF trades FOR VALUES FROM ('2027-08-01') TO ('2027-09-01');

CREATE TABLE ledger_entries_p2026_09 PARTITION OF ledger_entries FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE ledger_entries_p2026_10 PARTITION OF ledger_entries FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE ledger_entries_p2026_11 PARTITION OF ledger_entries FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE ledger_entries_p2026_12 PARTITION OF ledger_entries FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');
CREATE TABLE ledger_entries_p2027_01 PARTITION OF ledger_entries FOR VALUES FROM ('2027-01-01') TO ('2027-02-01');
CREATE TABLE ledger_entries_p2027_02 PARTITION OF ledger_entries FOR VALUES FROM ('2027-02-01') TO ('2027-03-01');
CREATE TABLE ledger_entries_p2027_03 PARTITION OF ledger_entries FOR VALUES FROM ('2027-03-01') TO ('2027-04-01');
CREATE TABLE ledger_entries_p2027_04 PARTITION OF ledger_entries FOR VALUES FROM ('2027-04-01') TO ('2027-05-01');
CREATE TABLE ledger_entries_p2027_05 PARTITION OF ledger_entries FOR VALUES FROM ('2027-05-01') TO ('2027-06-01');
CREATE TABLE ledger_entries_p2027_06 PARTITION OF ledger_entries FOR VALUES FROM ('2027-06-01') TO ('2027-07-01');
CREATE TABLE ledger_entries_p2027_07 PARTITION OF ledger_entries FOR VALUES FROM ('2027-07-01') TO ('2027-08-01');
CREATE TABLE ledger_entries_p2027_08 PARTITION OF ledger_entries FOR VALUES FROM ('2027-08-01') TO ('2027-09-01');

CREATE TABLE outbox_p2026_09 PARTITION OF outbox FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE outbox_p2026_10 PARTITION OF outbox FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE outbox_p2026_11 PARTITION OF outbox FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE outbox_p2026_12 PARTITION OF outbox FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');
CREATE TABLE outbox_p2027_01 PARTITION OF outbox FOR VALUES FROM ('2027-01-01') TO ('2027-02-01');
CREATE TABLE outbox_p2027_02 PARTITION OF outbox FOR VALUES FROM ('2027-02-01') TO ('2027-03-01');
CREATE TABLE outbox_p2027_03 PARTITION OF outbox FOR VALUES FROM ('2027-03-01') TO ('2027-04-01');
CREATE TABLE outbox_p2027_04 PARTITION OF outbox FOR VALUES FROM ('2027-04-01') TO ('2027-05-01');
CREATE TABLE outbox_p2027_05 PARTITION OF outbox FOR VALUES FROM ('2027-05-01') TO ('2027-06-01');
CREATE TABLE outbox_p2027_06 PARTITION OF outbox FOR VALUES FROM ('2027-06-01') TO ('2027-07-01');
CREATE TABLE outbox_p2027_07 PARTITION OF outbox FOR VALUES FROM ('2027-07-01') TO ('2027-08-01');
CREATE TABLE outbox_p2027_08 PARTITION OF outbox FOR VALUES FROM ('2027-08-01') TO ('2027-09-01');

-- (1) trades order_id 축 인덱스. maker/taker를 분리 단일열 인덱스로 두어
-- WHERE maker_order_id = ? OR taker_order_id = ? 를 BitmapOr로 처리한다.
-- created_at 하한을 걸지 않는 이유: trades.created_at은 engine_ts(명령 채번 시각) 파생이고
-- orders.created_at은 정산 시각 now()라 trade.created_at < order.created_at이 정상이다.
-- order.created_at을 하한으로 쓰면 주문 자신의 체결을 누락한다. 프루닝은 이 인덱스가 담당.
CREATE INDEX idx_trades_maker_order ON trades (maker_order_id);
CREATE INDEX idx_trades_taker_order ON trades (taker_order_id);
