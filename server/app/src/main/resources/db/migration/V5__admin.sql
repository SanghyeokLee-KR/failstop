-- ============================================================================
-- V5__admin.sql
-- 관리 평면(exchange-admin) 스키마. 관리자 설계 v1.1 §1-5 정본 확정분.
--   * api_keys.scope 어휘에 ADMIN 추가 + operator_id 1:1 바인딩 컬럼
--   * admin_operators        운영자 레지스트리(mTLS CN 유래 신원, 고객 users와 물리 분리)
--   * operator_roles         5역할 효력(VIEWER/BOT_OPS/CHAOS_OPS/MEMBER_MOD/SUPER)
--   * admin_sessions         mTLS 신원 유래 단기 세션 원장(발급·만료·폐기)
--   * admin_approvals        2인 승인 토큰 원장(requester <> approver 물리 강제)
--   * admin_audit_log        관리 액션 append-only 감사(forbid_mutation 재사용)
--   * system_config, notices 마켓 halt·수수료·공지 운영 상태
-- 빈 PG17에 V0~V5 단독 적용이 유지되어야 한다. 게시판(board_*) 테이블에는
-- 물리 FK를 만들지 않는다(관리자 설계 §7-5 격리).
-- 운영자 시드는 dev 런타임(AdminOperatorSeeder)·통합테스트가 담당한다. 마이그레이션은
-- 프로파일 무관 적용이라 데모 신원을 넣지 않는다(운영 배포에 데모 운영자 유입 금지).
-- ============================================================================

-- ----------------------------------------------------------------
-- 0. api_keys scope 어휘 확장 + operator 바인딩(관리자 설계 §7-2, API §3-2 정합)
-- ----------------------------------------------------------------
-- V1 인라인 CHECK는 자동명 api_keys_scope_check로 생성된다. 어휘만 확장한다.
ALTER TABLE api_keys DROP CONSTRAINT api_keys_scope_check;
ALTER TABLE api_keys ADD CONSTRAINT api_keys_scope_check
    CHECK (scope IN ('READ','TRADE','WITHDRAW','ADMIN'));

-- ----------------------------------------------------------------
-- 1. 운영자 레지스트리. operator_id는 mTLS 인증서 CN 유래 값이며 자가신고가 아니다(§3-6).
-- ----------------------------------------------------------------
CREATE TABLE admin_operators (
    id           BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    operator_id  VARCHAR(64)  NOT NULL UNIQUE,       -- 인증서 CN(예: 'op-01')
    external_id  UUID         NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    display_name VARCHAR(64)  NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
                 CHECK (status IN ('ACTIVE','DISABLED')),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 관리 access_key는 정확히 하나의 operator에 1:1 등록된다(API §7-1). 비관리 키는 NULL.
ALTER TABLE api_keys ADD COLUMN operator_id VARCHAR(64) REFERENCES admin_operators(operator_id);
CREATE UNIQUE INDEX uq_apikey_operator ON api_keys (operator_id) WHERE operator_id IS NOT NULL;
-- ADMIN scope 키는 operator 바인딩 필수, 그 외 scope는 바인딩 금지.
ALTER TABLE api_keys ADD CONSTRAINT chk_apikey_admin_operator
    CHECK ((scope = 'ADMIN') = (operator_id IS NOT NULL));

-- ----------------------------------------------------------------
-- 2. 역할 효력 + 이력. 효력은 operator_roles, 부여·회수 사유는 admin_audit_log가 보존한다.
-- ----------------------------------------------------------------
CREATE TABLE operator_roles (
    operator_id VARCHAR(64) NOT NULL REFERENCES admin_operators(operator_id),
    role        VARCHAR(16) NOT NULL
                CHECK (role IN ('VIEWER','BOT_OPS','CHAOS_OPS','MEMBER_MOD','SUPER')),
    granted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (operator_id, role)
);

-- ----------------------------------------------------------------
-- 3. mTLS 신원 유래 단기 관리자 세션. 검증은 서명+exp가 권위이나 폐기·감사를 위해 적재한다.
-- ----------------------------------------------------------------
CREATE TABLE admin_sessions (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    jti         UUID         NOT NULL UNIQUE,
    operator_id VARCHAR(64)  NOT NULL REFERENCES admin_operators(operator_id),
    issued_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ  NOT NULL,
    revoked_at  TIMESTAMPTZ
);
CREATE INDEX idx_admin_session_operator ON admin_sessions (operator_id, expires_at DESC);

-- ----------------------------------------------------------------
-- 4. 2인 승인 토큰. requester <> approver를 물리 강제해 단일 신원 자가승인을 차단한다(§3-6).
-- ----------------------------------------------------------------
CREATE TABLE admin_approvals (
    id                    BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    approval_token        VARCHAR(64)  NOT NULL UNIQUE,
    action                VARCHAR(64)  NOT NULL,
    target                VARCHAR(128) NOT NULL DEFAULT '',
    requester_operator_id VARCHAR(64)  NOT NULL REFERENCES admin_operators(operator_id),
    approver_operator_id  VARCHAR(64)  NOT NULL REFERENCES admin_operators(operator_id),
    state                 VARCHAR(16)  NOT NULL DEFAULT 'ISSUED'
                          CHECK (state IN ('ISSUED','CONSUMED','EXPIRED','REJECTED')),
    issued_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at            TIMESTAMPTZ  NOT NULL,
    consumed_at           TIMESTAMPTZ,
    CONSTRAINT chk_approval_two_person CHECK (requester_operator_id <> approver_operator_id)
);
CREATE INDEX idx_approval_open ON admin_approvals (action, target, state);

-- ----------------------------------------------------------------
-- 5. append-only 감사. operator_id는 인증 주체 유래 값을 적재한다(§3-6). 수정·삭제 봉쇄.
-- ----------------------------------------------------------------
CREATE TABLE admin_audit_log (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    operator_id VARCHAR(64),                          -- 인증 실패 감사는 NULL 허용
    action      VARCHAR(64)  NOT NULL,
    target      VARCHAR(128),
    request_id  VARCHAR(64),
    approval_id BIGINT       REFERENCES admin_approvals(id),
    result      VARCHAR(16)  NOT NULL DEFAULT 'OK'
                CHECK (result IN ('OK','DENIED','ERROR')),
    detail      JSONB        NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_operator ON admin_audit_log (operator_id, created_at DESC);
CREATE INDEX idx_audit_action ON admin_audit_log (action, created_at DESC);

CREATE TRIGGER trg_admin_audit_noupd BEFORE UPDATE OR DELETE ON admin_audit_log
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_admin_audit_notrunc BEFORE TRUNCATE ON admin_audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

-- ----------------------------------------------------------------
-- 6. 운영 구성·공지. 마켓 halt/수수료는 markets 컬럼(status·*_fee_rate)이 정본이고,
--    전역 스위치·공지는 여기에 둔다. value는 문자열/JSON로 다룬다(float 금지 규약).
-- ----------------------------------------------------------------
CREATE TABLE system_config (
    config_key VARCHAR(96)  PRIMARY KEY,
    value      JSONB        NOT NULL,
    updated_by VARCHAR(64),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE notices (
    id           BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title        VARCHAR(200) NOT NULL,
    body         TEXT         NOT NULL DEFAULT '',
    severity     VARCHAR(16)  NOT NULL DEFAULT 'INFO'
                 CHECK (severity IN ('INFO','WARNING','CRITICAL')),
    active       BOOLEAN      NOT NULL DEFAULT true,
    published_by VARCHAR(64),
    published_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_notice_active ON notices (active, published_at DESC);

-- ----------------------------------------------------------------
-- 7. 권한: 관리 평면 앱 롤 접근. dev·격리 스택은 슈퍼유저 접속이라 무관하나 운영 경로
--    (app_role)에서도 성립하도록 명시 부여한다. 감사는 append(수정·삭제는 트리거가 봉쇄).
-- ----------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_role') THEN
        GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO app_role;
        GRANT SELECT, INSERT, UPDATE ON admin_operators, operator_roles, admin_sessions,
              admin_approvals, system_config, notices TO app_role;
        GRANT SELECT, INSERT ON admin_audit_log TO app_role;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'harness_role') THEN
        GRANT SELECT ON admin_operators, operator_roles, admin_sessions, admin_approvals,
              admin_audit_log, system_config, notices TO harness_role;
    END IF;
END $$;
