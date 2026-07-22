-- ============================================================================
-- V4__harness_schema.sql
-- 하네스 전용 객체. 계약 스키마(public) 오염을 피하려 harness 스키마로 격리한다.
-- 하네스 검증설계 §2-5 H1__harness_tables.sql + §4-3 failover_runs를 편입한다.
--
-- 정본 설계는 별도 Flyway location(db/harness)을 권했으나, 앱은 단일 location
-- (classpath:db/migration)만 마이그레이션하므로 V 체인에 편입하되 스키마로 격리한다.
-- 원장·저널 무접촉. 빈 PG17에 V0~V4 단독 적용이 유지된다.
--
-- harness 스키마는 실험 리셋을 위해 TRUNCATE를 허용한다(스테일 loadtest_acks로 인한
-- C16 오탐 차단, 실험 시작 훅과 한 세트). 계약 append-only 규율은 public에만 적용된다.
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS harness;

-- k6/봇 부하 계정 매핑. 키 발급 시 적재. C16 조인 키(access_key→user_id)의 원천.
CREATE TABLE harness.loadtest_accounts (
    access_key VARCHAR(64) PRIMARY KEY,
    user_id    BIGINT      NOT NULL
);

-- C16 원천: ACK(2xx) 수신 원장. 실험 종료 시 봇/k6 로컬 append를 COPY로 일괄 적재.
-- source: 'bot' | 'k6'. 실험 시작 훅이 TRUNCATE 선행(스테일 오탐 차단).
CREATE TABLE harness.loadtest_acks (
    user_id         BIGINT      NOT NULL,
    client_order_id VARCHAR(36) NOT NULL,
    order_id        BIGINT,
    acked_at        TIMESTAMPTZ NOT NULL,
    source          VARCHAR(8)  NOT NULL DEFAULT 'bot',
    PRIMARY KEY (user_id, client_order_id)
);

-- C01 증분 검증용 마감 파티션 합 캐시(§4-2 성능 규율).
CREATE TABLE harness.invariant_partition_sums (
    partition_name VARCHAR(64)    NOT NULL,
    asset_id       SMALLINT       NOT NULL,
    amount_sum     NUMERIC(40,18) NOT NULL,
    sealed_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    PRIMARY KEY (partition_name, asset_id)
);

-- §2-4 캘리브레이션 결과 기록. 발표 자료 실험 조건 각주의 원천.
CREATE TABLE harness.calibration_runs (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    base_ops   INT    NOT NULL,
    spike_cap  INT    NOT NULL,
    k6_host    VARCHAR(64),
    details    JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── failover 측정 이력(하네스 검증설계 §4-3, DB v2.6 권고 failover_runs의 선반영) ──
-- recovery_runs에는 rto_seconds/rpo_records/verdict 컬럼이 없다. 재생 결정론 증거는
-- recovery_runs, 페일오버 판정(RTO/RPO/verdict)은 여기로 원천을 분리한다(3중 산출물 서사).
-- 관측 확장이라 harness 스키마에 둔다(계약 public 무접촉).
CREATE TABLE harness.failover_runs (
    id                BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    experiment_id     VARCHAR(32) NOT NULL,           -- 'E1','E2','E3',...
    old_leader        TEXT,
    new_leader        TEXT,
    old_epoch         BIGINT,
    new_epoch         BIGINT,
    -- T1 최종 심박 = 승격 트랜잭션이 선캡처한 구 lease_until - 리스 간격(§5-1 T1)
    last_heartbeat_ts TIMESTAMPTZ,
    -- T2 승격 커밋 벽시계
    takeover_ts       TIMESTAMPTZ,
    -- T5 페일오버 후 최초 신규 TRADE
    first_trade_ts    TIMESTAMPTZ,
    rto_seconds       NUMERIC(10,3),                  -- RTO = T5 - T0(체결 재개 기준)
    downtime_seconds  NUMERIC(10,3),                  -- 내부 게이지 = T2 - T1
    rpo_records       BIGINT,                         -- C16 결과 행 수(ACK 유실)
    verdict           VARCHAR(8),                     -- 'PASS' | 'FAIL'
    details           JSONB,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_failover_recent ON harness.failover_runs (experiment_id, created_at DESC);

-- 권한: app_role은 harness 스키마에 append + 실험 리셋 TRUNCATE.
-- harness_role은 읽기 전용(대시보드·검증 조회).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_role') THEN
        GRANT USAGE ON SCHEMA harness TO app_role;
        GRANT USAGE ON ALL SEQUENCES IN SCHEMA harness TO app_role;
        GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE
            ON harness.loadtest_accounts, harness.loadtest_acks,
               harness.invariant_partition_sums, harness.calibration_runs,
               harness.failover_runs
            TO app_role;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'harness_role') THEN
        GRANT USAGE ON SCHEMA harness TO harness_role;
        GRANT SELECT ON ALL TABLES IN SCHEMA harness TO harness_role;
    END IF;
END $$;
