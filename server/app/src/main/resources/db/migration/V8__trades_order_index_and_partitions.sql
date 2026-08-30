-- ============================================================================
-- V8__trades_order_index_and_partitions.sql
-- 원천: docs/contracts/백엔드_DB_설계.md v2.16 §3.1(trades 인덱스), §3.2(파티션 유지관리).
-- 목적:
--   (1) trades order_id 축 조회 인덱스. OrderQueryService.feeStats가
--       maker_order_id/taker_order_id로 집계하나 해당 축 인덱스가 없어 전 파티션
--       seq scan이 발생했다(감사 High). 파티션드 부모에 건 인덱스는 전 자식과
--       향후 자식에 전파된다.
--   (2) 시간 파티션 사전생성(2026-09~2027-08, 12개월). 외부 확장(pg_partman/pg_cron)
--       없이 빈 PG17 단독 적용을 유지하려고 월 파티션을 수동 선생성한다(감사 Medium).
--       미선생성 시 2026-09 이후 전 행이 *_default로 유입되어 프루닝이 무력화된다.
-- ============================================================================

-- 이 마이그레이션은 CREATE INDEX(파티션드 부모→전 자식)와 DEFAULT 재배치 이동을 수행해
-- 데이터량에 비례한다. prod Hikari 풀의 connection-init-sql이 심은 statement_timeout=3s/
-- lock_timeout=2s를 Flyway가 상속하면 적재 DB에서 57014로 취소돼 부팅이 막힌다. SET LOCAL로
-- 이 트랜잭션에 한해 상한을 해제한다(트랜잭션 종료 시 원복, 풀 무영향).
SET LOCAL statement_timeout = 0;
SET LOCAL lock_timeout = 0;

-- 세션 TZ를 UTC로 고정한다. 연결 계층(connection-init-sql)이 이미 UTC를 고정하나,
-- 마이그레이션 순서·배포 zone과 무관하게 이중 방어한다. 아래 파티션 경계 리터럴은 추가로
-- 명시 오프셋(+00)을 박아 세션 TZ에 전혀 의존하지 않는다.
SET LOCAL timezone = 'UTC';

-- DEFAULT 파티션에 신규 범위(2026-09+) 행이 있으면 CREATE ... PARTITION OF가 DEFAULT를
-- 스캔해 "updated partition constraint for default partition would be violated"로 중단된다
-- (증분·실배포 경로). 각 DEFAULT를 DETACH한 뒤 신규 월 파티션을 만들고, DEFAULT에 잘못 든
-- 행을 정규 파티션으로 옮긴 뒤 재ATTACH한다. 신선 적용(빈 DEFAULT)에서는 이동 0행 no-op.
--
-- 트리거 우회는 DISABLE TRIGGER USER로 한다. 이동은 이미 검증·균형된 행의 재배치라 신규
-- 쓰기용 가드(trades 레지스트리 트리거, ledger 봉인 가드·커밋 시점 제약 트리거)가 재발화하면
-- 중복 등록·봉인 위반 오류로 실패한다. session_replication_role='replica'와 달리 owner 권한으로
-- 충분해 마이그레이션 주체에 superuser를 요구하지 않는다. FK 내부 트리거는 USER 범위 밖이라
-- 계속 발화하나 재배치 행의 참조가 전부 유효해 통과한다. DISABLE은 부모에서 전 파티션으로
-- 전파되며, 재배치 직후 같은 트랜잭션 안에서 ENABLE로 원복한다.

-- ── trades ──────────────────────────────────────────────────────────────
ALTER TABLE trades DETACH PARTITION trades_default;
CREATE TABLE trades_p2026_09 PARTITION OF trades FOR VALUES FROM (TIMESTAMPTZ '2026-09-01 00:00:00+00') TO (TIMESTAMPTZ '2026-10-01 00:00:00+00');
CREATE TABLE trades_p2026_10 PARTITION OF trades FOR VALUES FROM (TIMESTAMPTZ '2026-10-01 00:00:00+00') TO (TIMESTAMPTZ '2026-11-01 00:00:00+00');
CREATE TABLE trades_p2026_11 PARTITION OF trades FOR VALUES FROM (TIMESTAMPTZ '2026-11-01 00:00:00+00') TO (TIMESTAMPTZ '2026-12-01 00:00:00+00');
CREATE TABLE trades_p2026_12 PARTITION OF trades FOR VALUES FROM (TIMESTAMPTZ '2026-12-01 00:00:00+00') TO (TIMESTAMPTZ '2027-01-01 00:00:00+00');
CREATE TABLE trades_p2027_01 PARTITION OF trades FOR VALUES FROM (TIMESTAMPTZ '2027-01-01 00:00:00+00') TO (TIMESTAMPTZ '2027-02-01 00:00:00+00');
CREATE TABLE trades_p2027_02 PARTITION OF trades FOR VALUES FROM (TIMESTAMPTZ '2027-02-01 00:00:00+00') TO (TIMESTAMPTZ '2027-03-01 00:00:00+00');
CREATE TABLE trades_p2027_03 PARTITION OF trades FOR VALUES FROM (TIMESTAMPTZ '2027-03-01 00:00:00+00') TO (TIMESTAMPTZ '2027-04-01 00:00:00+00');
CREATE TABLE trades_p2027_04 PARTITION OF trades FOR VALUES FROM (TIMESTAMPTZ '2027-04-01 00:00:00+00') TO (TIMESTAMPTZ '2027-05-01 00:00:00+00');
CREATE TABLE trades_p2027_05 PARTITION OF trades FOR VALUES FROM (TIMESTAMPTZ '2027-05-01 00:00:00+00') TO (TIMESTAMPTZ '2027-06-01 00:00:00+00');
CREATE TABLE trades_p2027_06 PARTITION OF trades FOR VALUES FROM (TIMESTAMPTZ '2027-06-01 00:00:00+00') TO (TIMESTAMPTZ '2027-07-01 00:00:00+00');
CREATE TABLE trades_p2027_07 PARTITION OF trades FOR VALUES FROM (TIMESTAMPTZ '2027-07-01 00:00:00+00') TO (TIMESTAMPTZ '2027-08-01 00:00:00+00');
CREATE TABLE trades_p2027_08 PARTITION OF trades FOR VALUES FROM (TIMESTAMPTZ '2027-08-01 00:00:00+00') TO (TIMESTAMPTZ '2027-09-01 00:00:00+00');
-- 레지스트리 AFTER INSERT 트리거(register_trade_seq)가 재발화하면 trade_seq_registry PK 중복
-- (23505)으로 실패한다. 재배치 구간만 사용자 트리거를 끈다.
ALTER TABLE trades DISABLE TRIGGER USER;
INSERT INTO trades (id, market_id, maker_order_id, taker_order_id, maker_user_id, taker_user_id,
                    taker_side, price, quantity, maker_fee, taker_fee, fencing_epoch,
                    executed_at, created_at)
    SELECT id, market_id, maker_order_id, taker_order_id, maker_user_id, taker_user_id,
           taker_side, price, quantity, maker_fee, taker_fee, fencing_epoch,
           executed_at, created_at
    FROM trades_default
    WHERE created_at >= TIMESTAMPTZ '2026-09-01 00:00:00+00'
      AND created_at <  TIMESTAMPTZ '2027-09-01 00:00:00+00';
DELETE FROM trades_default
    WHERE created_at >= TIMESTAMPTZ '2026-09-01 00:00:00+00'
      AND created_at <  TIMESTAMPTZ '2027-09-01 00:00:00+00';
ALTER TABLE trades ATTACH PARTITION trades_default DEFAULT;
ALTER TABLE trades ENABLE TRIGGER USER;

-- ── ledger_entries (id는 GENERATED ALWAYS AS IDENTITY라 OVERRIDING SYSTEM VALUE 필요) ──
ALTER TABLE ledger_entries DETACH PARTITION ledger_entries_default;
CREATE TABLE ledger_entries_p2026_09 PARTITION OF ledger_entries FOR VALUES FROM (TIMESTAMPTZ '2026-09-01 00:00:00+00') TO (TIMESTAMPTZ '2026-10-01 00:00:00+00');
CREATE TABLE ledger_entries_p2026_10 PARTITION OF ledger_entries FOR VALUES FROM (TIMESTAMPTZ '2026-10-01 00:00:00+00') TO (TIMESTAMPTZ '2026-11-01 00:00:00+00');
CREATE TABLE ledger_entries_p2026_11 PARTITION OF ledger_entries FOR VALUES FROM (TIMESTAMPTZ '2026-11-01 00:00:00+00') TO (TIMESTAMPTZ '2026-12-01 00:00:00+00');
CREATE TABLE ledger_entries_p2026_12 PARTITION OF ledger_entries FOR VALUES FROM (TIMESTAMPTZ '2026-12-01 00:00:00+00') TO (TIMESTAMPTZ '2027-01-01 00:00:00+00');
CREATE TABLE ledger_entries_p2027_01 PARTITION OF ledger_entries FOR VALUES FROM (TIMESTAMPTZ '2027-01-01 00:00:00+00') TO (TIMESTAMPTZ '2027-02-01 00:00:00+00');
CREATE TABLE ledger_entries_p2027_02 PARTITION OF ledger_entries FOR VALUES FROM (TIMESTAMPTZ '2027-02-01 00:00:00+00') TO (TIMESTAMPTZ '2027-03-01 00:00:00+00');
CREATE TABLE ledger_entries_p2027_03 PARTITION OF ledger_entries FOR VALUES FROM (TIMESTAMPTZ '2027-03-01 00:00:00+00') TO (TIMESTAMPTZ '2027-04-01 00:00:00+00');
CREATE TABLE ledger_entries_p2027_04 PARTITION OF ledger_entries FOR VALUES FROM (TIMESTAMPTZ '2027-04-01 00:00:00+00') TO (TIMESTAMPTZ '2027-05-01 00:00:00+00');
CREATE TABLE ledger_entries_p2027_05 PARTITION OF ledger_entries FOR VALUES FROM (TIMESTAMPTZ '2027-05-01 00:00:00+00') TO (TIMESTAMPTZ '2027-06-01 00:00:00+00');
CREATE TABLE ledger_entries_p2027_06 PARTITION OF ledger_entries FOR VALUES FROM (TIMESTAMPTZ '2027-06-01 00:00:00+00') TO (TIMESTAMPTZ '2027-07-01 00:00:00+00');
CREATE TABLE ledger_entries_p2027_07 PARTITION OF ledger_entries FOR VALUES FROM (TIMESTAMPTZ '2027-07-01 00:00:00+00') TO (TIMESTAMPTZ '2027-08-01 00:00:00+00');
CREATE TABLE ledger_entries_p2027_08 PARTITION OF ledger_entries FOR VALUES FROM (TIMESTAMPTZ '2027-08-01 00:00:00+00') TO (TIMESTAMPTZ '2027-09-01 00:00:00+00');
-- 봉인 가드(trg_guard_sealed)는 SEALED 거래의 분개 삽입을 거부하고, 무변경 가드
-- (trg_ledger_noupd)는 DELETE를 거부한다. 재배치는 봉인된 분개의 물리 이동이므로 둘 다
-- 재배치 구간만 끈다. DETACH된 default에는 부모 유래 클론 트리거가 남지 않지만, PG 버전별
-- 클론 잔존 차이에 걸리지 않게 명시로 끈다(트리거 없으면 no-op).
ALTER TABLE ledger_entries DISABLE TRIGGER USER;
ALTER TABLE ledger_entries_default DISABLE TRIGGER USER;
INSERT INTO ledger_entries (id, transaction_id, account_id, asset_id, amount, entry_type,
                            created_at) OVERRIDING SYSTEM VALUE
    SELECT id, transaction_id, account_id, asset_id, amount, entry_type, created_at
    FROM ledger_entries_default
    WHERE created_at >= TIMESTAMPTZ '2026-09-01 00:00:00+00'
      AND created_at <  TIMESTAMPTZ '2027-09-01 00:00:00+00';
DELETE FROM ledger_entries_default
    WHERE created_at >= TIMESTAMPTZ '2026-09-01 00:00:00+00'
      AND created_at <  TIMESTAMPTZ '2027-09-01 00:00:00+00';
ALTER TABLE ledger_entries_default ENABLE TRIGGER USER;
ALTER TABLE ledger_entries ATTACH PARTITION ledger_entries_default DEFAULT;
ALTER TABLE ledger_entries ENABLE TRIGGER USER;

-- ── outbox (id는 GENERATED ALWAYS AS IDENTITY라 OVERRIDING SYSTEM VALUE 필요) ──
-- outbox에는 사용자 트리거가 없어 트리거 우회가 불필요하다.
ALTER TABLE outbox DETACH PARTITION outbox_default;
CREATE TABLE outbox_p2026_09 PARTITION OF outbox FOR VALUES FROM (TIMESTAMPTZ '2026-09-01 00:00:00+00') TO (TIMESTAMPTZ '2026-10-01 00:00:00+00');
CREATE TABLE outbox_p2026_10 PARTITION OF outbox FOR VALUES FROM (TIMESTAMPTZ '2026-10-01 00:00:00+00') TO (TIMESTAMPTZ '2026-11-01 00:00:00+00');
CREATE TABLE outbox_p2026_11 PARTITION OF outbox FOR VALUES FROM (TIMESTAMPTZ '2026-11-01 00:00:00+00') TO (TIMESTAMPTZ '2026-12-01 00:00:00+00');
CREATE TABLE outbox_p2026_12 PARTITION OF outbox FOR VALUES FROM (TIMESTAMPTZ '2026-12-01 00:00:00+00') TO (TIMESTAMPTZ '2027-01-01 00:00:00+00');
CREATE TABLE outbox_p2027_01 PARTITION OF outbox FOR VALUES FROM (TIMESTAMPTZ '2027-01-01 00:00:00+00') TO (TIMESTAMPTZ '2027-02-01 00:00:00+00');
CREATE TABLE outbox_p2027_02 PARTITION OF outbox FOR VALUES FROM (TIMESTAMPTZ '2027-02-01 00:00:00+00') TO (TIMESTAMPTZ '2027-03-01 00:00:00+00');
CREATE TABLE outbox_p2027_03 PARTITION OF outbox FOR VALUES FROM (TIMESTAMPTZ '2027-03-01 00:00:00+00') TO (TIMESTAMPTZ '2027-04-01 00:00:00+00');
CREATE TABLE outbox_p2027_04 PARTITION OF outbox FOR VALUES FROM (TIMESTAMPTZ '2027-04-01 00:00:00+00') TO (TIMESTAMPTZ '2027-05-01 00:00:00+00');
CREATE TABLE outbox_p2027_05 PARTITION OF outbox FOR VALUES FROM (TIMESTAMPTZ '2027-05-01 00:00:00+00') TO (TIMESTAMPTZ '2027-06-01 00:00:00+00');
CREATE TABLE outbox_p2027_06 PARTITION OF outbox FOR VALUES FROM (TIMESTAMPTZ '2027-06-01 00:00:00+00') TO (TIMESTAMPTZ '2027-07-01 00:00:00+00');
CREATE TABLE outbox_p2027_07 PARTITION OF outbox FOR VALUES FROM (TIMESTAMPTZ '2027-07-01 00:00:00+00') TO (TIMESTAMPTZ '2027-08-01 00:00:00+00');
CREATE TABLE outbox_p2027_08 PARTITION OF outbox FOR VALUES FROM (TIMESTAMPTZ '2027-08-01 00:00:00+00') TO (TIMESTAMPTZ '2027-09-01 00:00:00+00');
INSERT INTO outbox (id, event_id, aggregate_type, aggregate_id, event_type, payload,
                    published_at, created_at) OVERRIDING SYSTEM VALUE
    SELECT id, event_id, aggregate_type, aggregate_id, event_type, payload,
           published_at, created_at
    FROM outbox_default
    WHERE created_at >= TIMESTAMPTZ '2026-09-01 00:00:00+00'
      AND created_at <  TIMESTAMPTZ '2027-09-01 00:00:00+00';
DELETE FROM outbox_default
    WHERE created_at >= TIMESTAMPTZ '2026-09-01 00:00:00+00'
      AND created_at <  TIMESTAMPTZ '2027-09-01 00:00:00+00';
ALTER TABLE outbox ATTACH PARTITION outbox_default DEFAULT;

-- (1) trades order_id 축 인덱스. maker/taker를 분리 단일열 인덱스로 두어
-- WHERE maker_order_id = ? OR taker_order_id = ? 를 BitmapOr로 처리한다.
-- created_at 하한을 걸지 않는 이유: trades.created_at은 engine_ts(명령 채번 시각) 파생이고
-- orders.created_at은 정산 시각 now()라 trade.created_at < order.created_at이 정상이다.
-- order.created_at을 하한으로 쓰면 주문 자신의 체결을 누락한다. 프루닝은 이 인덱스가 담당.
CREATE INDEX idx_trades_maker_order ON trades (maker_order_id);
CREATE INDEX idx_trades_taker_order ON trades (taker_order_id);
