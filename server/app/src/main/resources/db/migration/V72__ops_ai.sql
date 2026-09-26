-- 관제센터 AI 판단과 조치(DB 설계 §10.4). AI 신원, AI 한도와 변경 이력, AI 모드와 정지, 계획 작업 창,
-- 판단 기록, 조치 요청이다.
--
-- 무엇이 일어났는지(판단 기록, 한도 변경 이력)는 append-only 로, 무엇을 할 차례인지(모드, 조치 요청)는
-- 작업 표로 나눈다(V21 의 원칙). 앱 계정은 과거를 지우거나 한도의 하드 범위를 넓힐 수 없다.
-- 원장·저널·주문 표와는 어떤 외래키나 트리거로도 잇지 않는다.

-- ----------------------------------------------------------------
-- 1. AI 신원. 세션을 받을 수 없고(앱이 검사) 역할을 가질 수 없다(아래 트리거).
-- ----------------------------------------------------------------
ALTER TABLE admin_operators ADD COLUMN kind VARCHAR(8) NOT NULL DEFAULT 'HUMAN'
    CHECK (kind IN ('HUMAN','AI'));
-- 같은 이름의 사람 운영자가 이미 있으면 여기서 실패한다. AI 신원이 사람 행을 대신하지 않게 한다.
INSERT INTO admin_operators (operator_id, display_name, status, kind)
VALUES ('ai-ops', 'AI 운영', 'ACTIVE', 'AI');

CREATE FUNCTION forbid_ai_operator_role() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM admin_operators WHERE operator_id = NEW.operator_id AND kind = 'AI') THEN
        RAISE EXCEPTION 'AI 운영자 신원에는 역할을 줄 수 없다: %', NEW.operator_id;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_operator_roles_no_ai BEFORE INSERT OR UPDATE ON operator_roles
    FOR EACH ROW EXECUTE FUNCTION forbid_ai_operator_role();

CREATE FUNCTION forbid_operator_kind_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.kind IS DISTINCT FROM OLD.kind THEN
        RAISE EXCEPTION '운영자 종류는 바꿀 수 없다: %', OLD.operator_id;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_admin_operators_kind BEFORE UPDATE OF kind ON admin_operators
    FOR EACH ROW EXECUTE FUNCTION forbid_operator_kind_change();

-- ----------------------------------------------------------------
-- 2. AI 한도(ADR 0035). 값은 사람이 ops.ai.limits 티켓으로만 바꾼다. 하드 범위는 앱과 DB 가 함께 건다.
-- ----------------------------------------------------------------
CREATE TABLE ops_ai_limits (
    key         VARCHAR(32)  PRIMARY KEY CHECK (key IN
                    ('confidence_pct','auto_cap','threshold_per_item','bot_cooldown_min','backup_disk_free_pct')),
    value       SMALLINT     NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 1 CHECK (version >= 1),
    updated_by  VARCHAR(64)  NOT NULL,
    approval_id BIGINT       REFERENCES admin_approvals(id),
    reason      VARCHAR(500) NOT NULL DEFAULT '',
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_ops_ai_limit_range CHECK (CASE key
        WHEN 'confidence_pct'       THEN value BETWEEN 70 AND 95
        WHEN 'auto_cap'             THEN value BETWEEN 1 AND 10
        WHEN 'threshold_per_item'   THEN value BETWEEN 1 AND 3
        WHEN 'bot_cooldown_min'     THEN value BETWEEN 5 AND 60
        WHEN 'backup_disk_free_pct' THEN value BETWEEN 20 AND 50
        ELSE false END)
);

CREATE TABLE ops_ai_limit_changes (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    key         VARCHAR(32)  NOT NULL REFERENCES ops_ai_limits(key),
    from_value  SMALLINT,
    to_value    SMALLINT     NOT NULL,
    -- 이 변경 뒤의 판 번호
    version     BIGINT       NOT NULL,
    changed_by  VARCHAR(64)  NOT NULL,
    approval_id BIGINT       REFERENCES admin_approvals(id),
    reason      VARCHAR(500) NOT NULL DEFAULT '',
    changed_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_ops_ai_limit_change_version UNIQUE (key, version)
);
CREATE INDEX idx_ops_ai_limit_changes_keyset ON ops_ai_limit_changes (changed_at DESC, id DESC);
CREATE TRIGGER trg_ops_ai_limit_changes_noupd BEFORE UPDATE OR DELETE ON ops_ai_limit_changes
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_ops_ai_limit_changes_notrunc BEFORE TRUNCATE ON ops_ai_limit_changes
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

-- 확정값은 사용자가 2026-09-25 AI 권장값을 채택한 값이다(ADR 0035).
INSERT INTO ops_ai_limits (key, value, updated_by, reason) VALUES
    ('confidence_pct',       80, 'migration:V72', '확정값(ADR 0035)'),
    ('auto_cap',              5, 'migration:V72', '확정값(ADR 0035)'),
    ('threshold_per_item',    2, 'migration:V72', '확정값(ADR 0035)'),
    ('bot_cooldown_min',     10, 'migration:V72', '확정값(ADR 0035)'),
    ('backup_disk_free_pct', 30, 'migration:V72', '확정값(ADR 0035)');
INSERT INTO ops_ai_limit_changes (key, from_value, to_value, version, changed_by, reason)
SELECT key, NULL, value, 1, 'migration:V72', reason FROM ops_ai_limits;

-- ----------------------------------------------------------------
-- 3. AI 모드와 정지. 단일 행이다.
-- ----------------------------------------------------------------
CREATE TABLE ops_ai_mode (
    id                SMALLINT     PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    mode              VARCHAR(8)   NOT NULL DEFAULT 'MANUAL' CHECK (mode IN ('MANUAL','AUTO')),
    enabled_by        VARCHAR(64),
    reason            VARCHAR(500),
    approval_id       BIGINT       REFERENCES admin_approvals(id),
    since             TIMESTAMPTZ,
    valid_until       TIMESTAMPTZ,
    -- 자동 모드 활성 번호. 수동에서 자동으로 켤 때만 하나 오른다(트리거). 자동 조치 횟수는 이 번호로 센다.
    activation        BIGINT       NOT NULL DEFAULT 0 CHECK (activation >= 0),
    activation_at     TIMESTAMPTZ,
    -- 활성 때의 한도. 넓히는 한도 변경은 다음 활성부터 쓰려고 잡아 둔다.
    activation_limits JSONB,
    stopped           BOOLEAN      NOT NULL DEFAULT false,
    stopped_by        VARCHAR(64),
    stopped_at        TIMESTAMPTZ,
    stop_reason       VARCHAR(200),
    changed_by        VARCHAR(64)  NOT NULL,
    changed_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version           BIGINT       NOT NULL DEFAULT 1,
    -- 자동이면 켠 사람·사유·승인·시작·끝·활성 시각·활성 한도가 모두 있다. 수동이면 모두 없다.
    CONSTRAINT chk_ops_ai_mode_auto CHECK (
        (mode = 'AUTO' AND enabled_by IS NOT NULL AND reason IS NOT NULL AND approval_id IS NOT NULL
             AND since IS NOT NULL AND valid_until IS NOT NULL
             AND activation_at IS NOT NULL AND activation_limits IS NOT NULL)
        OR (mode = 'MANUAL' AND enabled_by IS NULL AND reason IS NULL AND approval_id IS NULL
             AND since IS NULL AND valid_until IS NULL
             AND activation_at IS NULL AND activation_limits IS NULL)),
    -- ADR 0035 의 자동 모드 최대 8시간을 DB 가 한 번 더 건다.
    CONSTRAINT chk_ops_ai_mode_window CHECK (
        valid_until IS NULL OR (valid_until > since AND valid_until <= since + interval '8 hours')),
    -- 정지 중에는 자동일 수 없다.
    CONSTRAINT chk_ops_ai_mode_stop CHECK (
        (stopped AND stopped_by IS NOT NULL AND stopped_at IS NOT NULL AND mode = 'MANUAL')
        OR (NOT stopped AND stopped_by IS NULL AND stopped_at IS NULL AND stop_reason IS NULL))
);
INSERT INTO ops_ai_mode (id, mode, changed_by) VALUES (1, 'MANUAL', 'migration:V72');

-- 자동 창과 활성은 DB 가 정한다. 자동으로 바꾸거나 승인·창·활성을 바꾸는 갱신에서 시작은 커밋 트랜잭션
-- 시각, 끝은 거기에 티켓 대상 AUTO:{h} 의 시간을 더한 값이다. 티켓은 이 트랜잭션에서 소각한 ops.ai.auto
-- 이고 그 사용자가 켠 사람이어야 한다. 수동에서 켤 때만 활성 번호를 올리고 그때의 한도를 잡아 두며,
-- 자동인 동안 새 티켓으로 다시 켜면 창만 새로 열고 활성 번호와 한도는 이어 쓴다.
CREATE FUNCTION check_ops_ai_mode_auto() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_action    TEXT;
    v_state     TEXT;
    v_target    TEXT;
    v_requester TEXT;
    v_consumed  TIMESTAMPTZ;
BEGIN
    IF NEW.mode <> 'AUTO' THEN
        RETURN NEW;
    END IF;
    IF OLD.mode = 'AUTO' AND NEW.approval_id IS NOT DISTINCT FROM OLD.approval_id
       AND NEW.since IS NOT DISTINCT FROM OLD.since
       AND NEW.valid_until IS NOT DISTINCT FROM OLD.valid_until
       AND NEW.activation IS NOT DISTINCT FROM OLD.activation
       AND NEW.activation_at IS NOT DISTINCT FROM OLD.activation_at
       AND NEW.activation_limits IS NOT DISTINCT FROM OLD.activation_limits THEN
        RETURN NEW;
    END IF;
    SELECT action, state, target, requester_operator_id, consumed_at
      INTO v_action, v_state, v_target, v_requester, v_consumed
      FROM admin_approvals WHERE id = NEW.approval_id;
    IF NOT FOUND OR v_action <> 'ops.ai.auto' OR v_state <> 'CONSUMED'
       OR v_consumed IS DISTINCT FROM now()
       OR v_requester IS DISTINCT FROM NEW.enabled_by
       OR v_target !~ '^AUTO:(1|2|4|8)$' THEN
        RAISE EXCEPTION '자동 모드에는 이 트랜잭션에서 소각한 ops.ai.auto 티켓이 필요하다: %', NEW.approval_id;
    END IF;
    NEW.since := now();
    NEW.valid_until := now() + make_interval(hours => substring(v_target FROM 6)::INT);
    IF OLD.mode = 'AUTO' THEN
        NEW.activation := OLD.activation;
        NEW.activation_at := OLD.activation_at;
        NEW.activation_limits := OLD.activation_limits;
    ELSE
        NEW.activation := OLD.activation + 1;
        NEW.activation_at := now();
        NEW.activation_limits := (SELECT jsonb_object_agg(key, value) FROM ops_ai_limits);
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_ops_ai_mode_auto BEFORE UPDATE ON ops_ai_mode
    FOR EACH ROW EXECUTE FUNCTION check_ops_ai_mode_auto();

-- ----------------------------------------------------------------
-- 4. 계획 작업 창. AI 의 경보 보류는 이 창과 겹치는 warning 경보에만 허용한다(정책 S6).
-- ----------------------------------------------------------------
CREATE TABLE ops_maintenance_windows (
    id         BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    starts_at  TIMESTAMPTZ  NOT NULL,
    ends_at    TIMESTAMPTZ  NOT NULL,
    -- 대상. 경보명과 라벨이 모두 맞아야 한다. 둘 다 비면 모든 경보가 되므로 막는다.
    alertname  VARCHAR(128),
    labels     JSONB        NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(labels) = 'object'),
    reason     VARCHAR(200) NOT NULL CHECK (length(reason) > 0),
    created_by VARCHAR(64)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ended_by   VARCHAR(64),
    ended_at   TIMESTAMPTZ,
    CONSTRAINT chk_ops_window_span CHECK (ends_at > starts_at AND ends_at <= starts_at + interval '24 hours'),
    CONSTRAINT chk_ops_window_target CHECK (alertname IS NOT NULL OR labels <> '{}'::jsonb),
    CONSTRAINT chk_ops_window_end CHECK ((ended_by IS NULL) = (ended_at IS NULL))
);
CREATE INDEX idx_ops_windows_ends ON ops_maintenance_windows (ends_at DESC, id DESC);

-- ----------------------------------------------------------------
-- 5. 판단 기록. append-only 다.
-- ----------------------------------------------------------------
CREATE TABLE ops_ai_decisions (
    id               BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    decided_at       TIMESTAMPTZ   NOT NULL,
    mode             VARCHAR(8)    NOT NULL CHECK (mode IN ('MANUAL','AUTO')),
    outcome          VARCHAR(16)   NOT NULL CHECK (outcome IN
                         ('PROPOSED','NO_ACTION','INVALID_OUTPUT','MODEL_ERROR','EVIDENCE_STALE')),
    -- MODEL_ERROR·INVALID_OUTPUT 의 분류 코드. 응답 본문이나 주소는 싣지 않는다.
    error            VARCHAR(32),
    model            VARCHAR(128)  NOT NULL,
    prompt_version   VARCHAR(32)   NOT NULL,
    evidence         JSONB         NOT NULL,
    -- 모델에 보낸 근거 바이트의 해시. JSONB 는 키 순서를 바꾸므로 보낸 원문으로 계산한다.
    evidence_sha256  CHAR(64)      NOT NULL,
    oldest_sample_at TIMESTAMPTZ,
    -- 모델 출력 원문. 16 KiB 를 넘으면 잘라 두고 해시는 자르기 전 전체로 계산한다.
    output_raw       TEXT          CHECK (output_raw IS NULL OR octet_length(output_raw) <= 16384),
    output_sha256    CHAR(64),
    output_truncated BOOLEAN       NOT NULL DEFAULT false,
    situation        VARCHAR(1000),
    reason           VARCHAR(1000),
    confidence       NUMERIC(4,3)  CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1),
    -- 이 판단이 낸 조치 요청의 identity_key. 판단과 요청 행을 잇는다. 조치가 없으면 NULL 이다.
    action_key       VARCHAR(200),
    dropped          JSONB         NOT NULL DEFAULT '[]'::jsonb,
    latency_ms       INTEGER,
    incident_id      BIGINT        REFERENCES ops_incidents(id),
    CONSTRAINT chk_ops_ai_output_hash CHECK ((output_raw IS NULL) = (output_sha256 IS NULL))
);
CREATE INDEX idx_ops_ai_decisions_keyset ON ops_ai_decisions (decided_at DESC, id DESC);
CREATE TRIGGER trg_ops_ai_decisions_noupd BEFORE UPDATE OR DELETE ON ops_ai_decisions
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_ops_ai_decisions_notrunc BEFORE TRUNCATE ON ops_ai_decisions
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

-- ----------------------------------------------------------------
-- 6. 조치 요청. 상태가 바뀌는 작업 표다. 상태가 바뀔 때마다 감사와 사건 이벤트에 append-only 행을 남긴다.
-- ----------------------------------------------------------------
CREATE TABLE ops_ai_actions (
    id                BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    first_decision_id BIGINT        NOT NULL REFERENCES ops_ai_decisions(id),
    last_decision_id  BIGINT        NOT NULL REFERENCES ops_ai_decisions(id),
    -- 같은 조치 요청이 이어진 판단 수. 자동 실행은 2 이상이어야 한다.
    consecutive       SMALLINT      NOT NULL DEFAULT 1 CHECK (consecutive >= 1),
    kind              VARCHAR(16)   NOT NULL CHECK (kind IN
                          ('READ_NODES','THRESHOLD','SILENCE','BOT_LOAD','BACKUP_NOW')),
    -- 같은 판단인지 가르는 정규화 키. 예: READ_NODES:+1, THRESHOLD:read.scale_out_cpu:0.55
    identity_key      VARCHAR(200)  NOT NULL,
    params            JSONB         NOT NULL,
    target            VARCHAR(128)  NOT NULL,
    confidence        NUMERIC(4,3)  NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    -- 모델이 쓴 이유(평문). 화면은 링크로 그리지 않고 통지에는 싣지 않는다.
    reason            VARCHAR(500)  NOT NULL DEFAULT '',
    status            VARCHAR(16)   NOT NULL DEFAULT 'PROPOSED' CHECK (status IN
                          ('PROPOSED','DEFERRED','EXECUTING','APPROVED','AUTO_EXECUTED','FAILED','UNKNOWN',
                           'REJECTED','DENIED','WITHDRAWN','EXPIRED','REVERTED','AUTO_REVERTED')),
    -- 정책 엔진이 막은 규칙 id 와 사유. DENIED 는 제안 때, 그 밖은 자동 실행이나 정지 때의 사유다.
    hold_rule         VARCHAR(32),
    hold_reason       VARCHAR(200),
    -- 나중에 시각. expires_at 을 넘지 않는다. 나중에로 둔 요청은 자동 실행하지 않고,
    -- 같은 identity_key 의 새 요청도 이 시각까지 자동 실행하지 않는다(앱의 정책 A9).
    deferred_until    TIMESTAMPTZ,
    decided_by        VARCHAR(64),
    decided_at        TIMESTAMPTZ,
    approval_id       BIGINT        REFERENCES admin_approvals(id),
    executed_by       VARCHAR(64),
    executed_at       TIMESTAMPTZ,
    -- 자동 실행한 자동 모드 활성 번호. 사람 승인 실행은 NULL 이다. 활성당 상한은 이 값으로 센다.
    activation        BIGINT,
    result            JSONB,
    baseline          JSONB,
    observe_until     TIMESTAMPTZ,
    observe_outcome   VARCHAR(16)   CHECK (observe_outcome IN
                          ('KEPT','REVERTED','UNOBSERVABLE','REVERT_BLOCKED','NOT_REVERTIBLE')),
    revert_info       JSONB,
    reverted_by       VARCHAR(64),
    reverted_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ   NOT NULL,
    expires_at        TIMESTAMPTZ   NOT NULL,
    CONSTRAINT chk_ops_ai_action_defer CHECK (deferred_until IS NULL OR deferred_until <= expires_at)
);
-- 같은 조치의 열린 요청은 하나다. 판단마다 새 행을 만들지 않고 연속 횟수를 올린다.
CREATE UNIQUE INDEX uq_ops_ai_action_open ON ops_ai_actions (identity_key)
    WHERE status IN ('PROPOSED','DEFERRED','EXECUTING');
-- 한 번에 한 조치. 실행 중인 요청은 표 전체에서 하나뿐이다.
CREATE UNIQUE INDEX uq_ops_ai_action_one_executing ON ops_ai_actions (status)
    WHERE status = 'EXECUTING';
CREATE INDEX idx_ops_ai_actions_keyset ON ops_ai_actions (created_at DESC, id DESC);
CREATE INDEX idx_ops_ai_actions_activation ON ops_ai_actions (activation) WHERE activation IS NOT NULL;

ALTER TABLE ops_threshold_changes
    ADD CONSTRAINT fk_ops_threshold_change_action FOREIGN KEY (ai_action_id) REFERENCES ops_ai_actions(id);

-- ----------------------------------------------------------------
-- 7. 사건 타임라인의 종류를 넓힌다. 정지·재개, 한도 변경, 계획 작업 창, 정책 거부, 관찰 창의 끝이다.
-- ----------------------------------------------------------------
ALTER TABLE ops_incident_events DROP CONSTRAINT ops_incident_events_kind_check;
ALTER TABLE ops_incident_events ADD CONSTRAINT ops_incident_events_kind_check CHECK (kind IN (
    'OPENED','AI_JUDGED','APPROVED','REJECTED','DEFERRED','ACTED','AUTO_ACTED',
    'AUTO_REVERTED','REVERTED','MODE','NOTIFIED','ACKED','SILENCED','UNSILENCED','RESOLVED',
    'STOPPED','RESUMED','LIMITS','WINDOW','DENIED','OBSERVED'));

-- ----------------------------------------------------------------
-- 8. 권한. 앱은 한도의 하드 범위와 항목을 바꿀 수 없고 기록 표를 지울 수 없다.
-- ----------------------------------------------------------------
GRANT SELECT ON ops_ai_limits TO app_role;
GRANT UPDATE (value, version, updated_by, approval_id, reason, updated_at) ON ops_ai_limits TO app_role;
GRANT SELECT, INSERT ON ops_ai_limit_changes TO app_role;
GRANT SELECT, UPDATE ON ops_ai_mode TO app_role;
GRANT SELECT, INSERT ON ops_maintenance_windows TO app_role;
GRANT UPDATE (ended_by, ended_at) ON ops_maintenance_windows TO app_role;
GRANT SELECT, INSERT ON ops_ai_decisions TO app_role;
GRANT SELECT, INSERT, UPDATE ON ops_ai_actions TO app_role;
GRANT SELECT ON ops_ai_limits, ops_ai_limit_changes, ops_ai_mode, ops_maintenance_windows, ops_ai_decisions,
    ops_ai_actions TO harness_role;
