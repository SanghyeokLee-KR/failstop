-- ============================================================================
-- V3__observability_tables.sql
-- 관측 테이블 도입: invariant_checks, recovery_runs.
-- DB 설계 v2.10 §3 로드맵(V4 예정분)의 정본 DDL을 그대로 옮긴다. 컬럼·타입·제약을
-- 추가하거나 바꾸지 않는다. "데이터로 증명" 산출물의 1차 저장소다.
--
-- 원장·저널(append-only 계층) 무접촉. 이 두 테이블은 프로젝션이 아니라 검증 결과
-- 로그라 forbid_mutation 트리거를 걸지 않는다(정본에도 없음).
-- 빈 PG17에 V0~V3 단독 적용이 유지되어야 한다(하네스 격리 스택 부트스트랩 전제).
-- ============================================================================

-- 불변식 검증 결과. 하네스 InvariantVerifier(앱 @Scheduled)와 pg_cron(도입 시)이
-- 공통으로 append 한다. details JSONB에 {"run_id","watermark_seq","window",...} 표준 기입.
CREATE TABLE invariant_checks (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    check_name    VARCHAR(48)  NOT NULL,     -- 'C01_ASSET_CONSERVATION','C02_BAL_VS_LEDGER',...
    ok            BOOLEAN      NOT NULL,
    violated_rows BIGINT       NOT NULL DEFAULT 0,
    details       JSONB,
    checked_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_invariant_recent ON invariant_checks (check_name, checked_at DESC);

-- 재생 결정론 증거. 직전 스냅샷 S_prev→S 재생 상태 해시(fp_replay)를 동일 seq(S)의
-- 최신 스냅샷 상태 해시(fp_live)와 대조한 결과. matched=false는 재생 비결정 = 자동
-- 복구 중단 신호(하네스 C17). RTO/RPO/verdict는 여기 컬럼이 없어 failover_runs(V4)가 맡는다.
CREATE TABLE recovery_runs (
    id                BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    market_id         SMALLINT    NOT NULL,
    snapshot_id       BIGINT,
    replayed_from_seq BIGINT      NOT NULL,
    replayed_to_seq   BIGINT      NOT NULL,
    fp_replay         VARCHAR(64) NOT NULL,
    fp_live           VARCHAR(64),
    matched           BOOLEAN,
    duration_ms       INT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 권한: 앱은 검증 결과를 append 한다. dev·격리 스택에서 앱이 슈퍼유저로 접속하면
-- 무관하나, app_role 접속(운영·V3 회귀) 경로에서도 성립하도록 명시 부여한다.
-- 검증 로그라 UPDATE/DELETE는 부여하지 않는다(수정 불가 append). 두 테이블 모두
-- GENERATED ALWAYS AS IDENTITY라 INSERT에 시퀀스 USAGE가 불필요하다(DB§9 화이트리스트 GRANT).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_role') THEN
        GRANT SELECT, INSERT ON invariant_checks, recovery_runs TO app_role;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'harness_role') THEN
        GRANT SELECT ON invariant_checks, recovery_runs TO harness_role;
    END IF;
END $$;
