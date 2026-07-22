-- ============================================================================
-- V6__members.sql
-- 회원 인증·세션·프로필(회원_설계.md §2-2). 코어(매칭·저널·정산)와 격리한다.
-- 기존 users·api_keys·accounts는 변경하지 않고 신규 테이블만 더한다.
-- GENESIS 자금 발급은 기존 post_ledger_transaction 단일 경로만 재사용한다.
-- ============================================================================
SET timezone = 'UTC';

-- 자격 증명: users와 1:1 분리(인증 관심사 격리). 평문·가역 저장 금지.
CREATE TABLE user_credentials (
    user_id         BIGINT       PRIMARY KEY REFERENCES users(id),
    algo            VARCHAR(16)  NOT NULL DEFAULT 'ARGON2ID'
                    CHECK (algo IN ('ARGON2ID','BCRYPT')),
    -- Argon2id 인코딩 문자열(파라미터 내장). 가역 저장 금지.
    password_hash   VARCHAR(255) NOT NULL,
    pwd_updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 온라인 브루트포스 백오프 잠금(계정 단위)
    failed_attempts SMALLINT     NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    locked_until    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 프로필: 표시명 등 비민감 정보.
CREATE TABLE user_profiles (
    user_id      BIGINT       PRIMARY KEY REFERENCES users(id),
    display_name VARCHAR(32)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 리프레시 토큰 패밀리(회전·재사용 탐지). raw 토큰은 저장하지 않고 SHA-256 해시만 저장.
CREATE TABLE auth_sessions (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users(id),
    family_id   UUID         NOT NULL,          -- 로그인 1회 = 1 패밀리
    token_hash  CHAR(64)     NOT NULL UNIQUE,   -- SHA-256 hex
    prev_id     BIGINT       REFERENCES auth_sessions(id),  -- 회전 체인
    issued_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ  NOT NULL,
    used_at     TIMESTAMPTZ,                    -- 회전 시 소각 시각(단회용)
    revoked_at  TIMESTAMPTZ,                    -- 로그아웃·재사용 탐지 시 패밀리 폐기
    user_agent  VARCHAR(256),
    ip          INET,
    CONSTRAINT chk_expiry CHECK (expires_at > issued_at)
);
CREATE INDEX idx_auth_sessions_user   ON auth_sessions (user_id, expires_at);
CREATE INDEX idx_auth_sessions_family ON auth_sessions (family_id);

-- 가입 시 GENESIS 자금 발급의 멱등 기록. 재시도·복구 시 중복 발급 방지.
-- genesis_event_id는 시드 GENESIS와 서로소인 예약 네임스페이스(§2-4)에서 배정한다.
CREATE TABLE user_provisioning (
    user_id          BIGINT      PRIMARY KEY REFERENCES users(id),
    status           VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING','DONE')),
    -- ledger_transactions.business_event_id로 사용. PROVISION_GENESIS_BASE + user_id.
    genesis_event_id BIGINT      NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ,
    UNIQUE (genesis_event_id)
);

-- 권한 경계: app_role은 위 4개 테이블에 SELECT·INSERT·UPDATE를 갖되 잔고·원장 직접 DML은 없다.
-- 자금 이동은 여전히 post_ledger_transaction EXECUTE 유일 경로다(V1 §7 경계 유지).
-- auth_sessions.id는 GENERATED ALWAYS AS IDENTITY라 INSERT 권한만으로 채번된다. 별도 시퀀스
-- USAGE는 불요이고, public 전체 시퀀스로 USAGE를 넓히면 위 4개 테이블 경계를 넘으므로 부여하지 않는다.
-- harness_role은 상시 게이트 불변식 검증(§5: 회원 다수 동시 거래)용 읽기만 갖는다(V5 admin 패턴 일치).
-- 빈 PG17 단독 적용성을 위해 롤 존재 검사로 감싼다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_role') THEN
        GRANT SELECT, INSERT, UPDATE
            ON user_credentials, user_profiles, auth_sessions, user_provisioning TO app_role;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'harness_role') THEN
        GRANT SELECT
            ON user_credentials, user_profiles, auth_sessions, user_provisioning TO harness_role;
    END IF;
END $$;
