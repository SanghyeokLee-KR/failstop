-- 관제센터 임계값 레지스트리(DB 설계 §10.2). 경보 규칙과 용량 권고가 쓰는 임계를 승인된 변경으로 바꾼다.
--
-- 항목·단위·허용 범위·AI 1회 폭은 이 마이그레이션만 정한다. 앱 계정은 현재값과 판 번호만 바꿀 수 있어
-- 범위를 넓히거나 항목을 늘릴 수 없다. 변경 이력은 append-only 로 따로 둔다(V21 의 기록·작업 분리 원칙).
--
-- 번호가 V70 인 것은 V48~V69 를 마켓별 리더 작업이 쓰기 때문이다. 공백은 Flyway 에 문제가 없다.

CREATE TABLE ops_thresholds (
    key           VARCHAR(64)   PRIMARY KEY
                  CHECK (key ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'),
    unit          VARCHAR(16)   NOT NULL CHECK (unit IN ('ratio','seconds','credits')),
    -- NULL 은 미설정이다. 경보 규칙과 AI 가 쓰지 않는다.
    value         NUMERIC(12,4),
    default_value NUMERIC(12,4),
    min_value     NUMERIC(12,4) NOT NULL,
    max_value     NUMERIC(12,4) NOT NULL,
    ai_max_step   NUMERIC(12,4) NOT NULL CHECK (ai_max_step > 0),
    version       BIGINT        NOT NULL DEFAULT 1 CHECK (version >= 1),
    updated_by    VARCHAR(64)   NOT NULL,
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_ops_threshold_bounds  CHECK (min_value < max_value),
    CONSTRAINT chk_ops_threshold_value   CHECK (value IS NULL OR value BETWEEN min_value AND max_value),
    CONSTRAINT chk_ops_threshold_default CHECK (default_value IS NULL OR default_value BETWEEN min_value AND max_value),
    -- 초기값이 있는 항목은 미설정이 될 수 없다. 대체값을 둔 경보 규칙이 NaN 을 받아 꺼지지 않게 한다.
    CONSTRAINT chk_ops_threshold_seeded_set CHECK (default_value IS NULL OR value IS NOT NULL)
);

-- 축소 임계는 증설 임계보다 0.15 이상 낮아야 한다(정책 T3). 두 행에 걸치므로 커밋 때 다시 본다.
-- 앱도 쓰기 전에 같은 규칙을 검사한다. 이 트리거는 되돌리기·설정 적용처럼 앱이 놓칠 수 있는 경로의 마지막 선이다.
CREATE FUNCTION check_ops_read_threshold_gap() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_out NUMERIC;
    v_in  NUMERIC;
BEGIN
    SELECT value INTO v_out FROM ops_thresholds WHERE key = 'read.scale_out_cpu';
    SELECT value INTO v_in  FROM ops_thresholds WHERE key = 'read.scale_in_cpu';
    IF v_out IS NOT NULL AND v_in IS NOT NULL AND v_in > v_out - 0.15 THEN
        RAISE EXCEPTION '조회 축소 임계 %는 증설 임계 %보다 0.15 이상 낮아야 한다', v_in, v_out
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER trg_ops_read_threshold_gap
    AFTER UPDATE ON ops_thresholds DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.key IN ('read.scale_out_cpu','read.scale_in_cpu'))
    EXECUTE FUNCTION check_ops_read_threshold_gap();

CREATE TABLE ops_threshold_changes (
    id           BIGINT        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    key          VARCHAR(64)   NOT NULL REFERENCES ops_thresholds(key),
    from_value   NUMERIC(12,4),
    to_value     NUMERIC(12,4),
    -- 이 변경 뒤의 판 번호
    version      BIGINT        NOT NULL,
    via          VARCHAR(16)   NOT NULL
                 CHECK (via IN ('SEED','CONFIG','MANUAL','AI_APPROVED','AI_AUTO','REVERT','AUTO_REVERT')),
    changed_by   VARCHAR(64)   NOT NULL,
    approval_id  BIGINT        REFERENCES admin_approvals(id),
    -- 외래키는 ops_ai_actions 를 만드는 V72 가 건다.
    ai_action_id BIGINT,
    revert_of    BIGINT        REFERENCES ops_threshold_changes(id),
    reason       VARCHAR(500)  NOT NULL DEFAULT '',
    changed_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_ops_threshold_change_version UNIQUE (key, version)
);
CREATE INDEX idx_ops_threshold_changes_keyset ON ops_threshold_changes (changed_at DESC, id DESC);
CREATE INDEX idx_ops_threshold_changes_key ON ops_threshold_changes (key, changed_at DESC);
CREATE TRIGGER trg_ops_threshold_changes_noupd BEFORE UPDATE OR DELETE ON ops_threshold_changes
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_ops_threshold_changes_notrunc BEFORE TRUNCATE ON ops_threshold_changes
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

-- 초기값은 지금 경보 규칙의 리터럴이 있는 항목만 그 값이다. 나머지는 미설정으로 시작한다.
-- 조회 임계는 API 설계 §6-A-a 의 계약 기본값이고, 하한은 로컬 admin-chaos 의 0.25·0.10 이 범위 안에 들게 잡았다.
INSERT INTO ops_thresholds (key, unit, value, default_value, min_value, max_value, ai_max_step, updated_by) VALUES
    ('read.scale_out_cpu',       'ratio',   0.60,  0.60,  0.20,  0.85,   0.05,  'migration:V70'),
    ('read.scale_in_cpu',        'ratio',   0.30,  0.30,  0.05,  0.50,   0.05,  'migration:V70'),
    ('host.cpu_alert',           'ratio',   NULL,  NULL,  0.60,  0.95,   0.05,  'migration:V70'),
    ('host.mem_alert',           'ratio',   NULL,  NULL,  0.70,  0.95,   0.05,  'migration:V70'),
    ('host.disk_free_alert',     'ratio',   0.15,  0.15,  0.10,  0.40,   0.05,  'migration:V70'),
    ('db.replication_lag_alert', 'seconds', NULL,  NULL,  5,     300,    10,    'migration:V70'),
    ('backup.delay_alert',       'seconds', 93600, 93600, 43200, 259200, 21600, 'migration:V70'),
    ('bot.capacity_guard',       'ratio',   NULL,  NULL,  0.50,  0.90,   0.05,  'migration:V70'),
    ('host.cpu_credit_alert',    'credits', NULL,  NULL,  10,    200,    20,    'migration:V70');
INSERT INTO ops_threshold_changes (key, from_value, to_value, version, via, changed_by, reason)
SELECT key, NULL, value, 1, 'SEED', 'migration:V70', '초기값' FROM ops_thresholds;

GRANT SELECT ON ops_thresholds TO app_role;
GRANT UPDATE (value, version, updated_by, updated_at) ON ops_thresholds TO app_role;
GRANT SELECT, INSERT ON ops_threshold_changes TO app_role;
GRANT SELECT ON ops_thresholds, ops_threshold_changes TO harness_role;
