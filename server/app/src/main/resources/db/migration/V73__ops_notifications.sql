-- 관제센터 통지 보관함과 AI 판단 주기의 단일 실행 임대(DB 설계 §10.5).
--
-- 통지는 관리 JVM 이 Teams 에 직접 보내지 않는다. 조치가 커밋된 뒤 서버가 만든 값만으로 보관함에 쓰고, 전용 작업이
-- 거래 서버와 분리된 보고 서비스(deploy/reporting)의 수신 경로로 넘긴다. 웹훅 주소와 토큰을 싣는 열은 없다.
-- 원장·저널·주문 표와는 어떤 외래키나 트리거로도 잇지 않는다.

-- ----------------------------------------------------------------
-- 1. 통지 보관함. 상태는 Teams 전송이 아니라 보고 서비스로 넘긴 상태다.
-- ----------------------------------------------------------------
CREATE TABLE ops_notifications (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 보고 서비스가 같은 사건을 한 번만 받는 키다. 응답을 잃은 넘김을 다시 보내도 카드는 한 장이다.
    event_id        UUID         NOT NULL UNIQUE,
    kind            VARCHAR(32)  NOT NULL CHECK (kind IN
                        ('AUTO_EXECUTED','AUTO_REVERTED','MODE_CHANGED','CAP_REACHED','OBSERVE_FAILED',
                         'EXECUTION_UNKNOWN','MODEL_FAILING','STOPPED','RESUMED')),
    -- 보고 서비스로 넘기는 사건 본문. 서버가 만든 값만 담고 모델의 자유 문장은 담지 않는다.
    payload         JSONB        NOT NULL CHECK (octet_length(payload::text) <= 16384),
    state           VARCHAR(16)  NOT NULL DEFAULT 'PENDING' CHECK (state IN
                        ('PENDING','SENDING','ACCEPTED','FAILED','SKIPPED')),
    attempts        SMALLINT     NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 100),
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    claimed_at      TIMESTAMPTZ,
    -- 오류 분류 코드만. 응답 본문과 주소는 남기지 않는다.
    last_error      VARCHAR(64),
    report_id       VARCHAR(128),
    -- 접수되면 이 사건의 타임라인에 NOTIFIED 를 남긴다. 사건과 무관한 통지(모드 전환)는 비어 있다.
    incident_id     BIGINT       REFERENCES ops_incidents(id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    accepted_at     TIMESTAMPTZ,
    CONSTRAINT chk_ops_notifications_accepted CHECK ((state = 'ACCEPTED') = (accepted_at IS NOT NULL))
);
CREATE INDEX idx_ops_notifications_due ON ops_notifications (next_attempt_at, id)
    WHERE state IN ('PENDING','SENDING');
CREATE INDEX idx_ops_notifications_keyset ON ops_notifications (created_at DESC, id DESC);

-- ----------------------------------------------------------------
-- 2. 여러 관리 JVM 가운데 하나만 AI 판단 주기를 돌린다. 매칭 리더(leader_lease)와 무관한 임대이고,
--    커넥션을 붙잡지 않도록 세션 락 대신 만료 시각을 쓴다.
-- ----------------------------------------------------------------
CREATE TABLE ops_runner_lease (
    name       VARCHAR(32)  PRIMARY KEY CHECK (name IN ('ai_loop')),
    holder     VARCHAR(64),
    expires_at TIMESTAMPTZ,
    renewed_at TIMESTAMPTZ,
    CONSTRAINT chk_ops_runner_lease_pair CHECK ((holder IS NULL) = (expires_at IS NULL))
);
INSERT INTO ops_runner_lease (name) VALUES ('ai_loop');

-- 앱은 과거 통지를 지울 수 없고 임대 행을 늘리거나 지울 수 없다.
GRANT SELECT, INSERT, UPDATE ON ops_notifications TO app_role;
GRANT SELECT ON ops_runner_lease TO app_role;
GRANT UPDATE (holder, expires_at, renewed_at) ON ops_runner_lease TO app_role;
GRANT SELECT ON ops_notifications, ops_runner_lease TO harness_role;
