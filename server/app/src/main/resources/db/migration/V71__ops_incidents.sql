-- 관제센터 경보 사건과 타임라인(DB 설계 §10.3).
--
-- 사건 표는 폴러가 열고 닫는 작업 표이고, 타임라인은 무엇이 일어났는지 적는 append-only 기록이다.
-- 사건의 신원은 (지문, 시작 시각)이다. 해제된 경보가 다시 나면 지문은 같고 시작 시각이 달라 새 행이 된다.
-- 원장·저널·주문 표와는 어떤 외래키나 트리거로도 잇지 않는다.

CREATE TABLE ops_incidents (
    id             BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- Alertmanager 지문(라벨 묶음 해시, 16자리 16진)
    fingerprint    VARCHAR(16)  NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{16}$'),
    alertname      VARCHAR(128) NOT NULL,
    -- 정규화한 등급. severity 라벨이 없거나 모르는 값(예: none)이면 other 이고 원래 값은 labels 에 남는다.
    -- 모르는 값을 거부하면 그 경보 하나 때문에 쓰기가 실패한다. 생존 신호 경보(Watchdog)는 이 표에 넣지 않는다.
    severity       VARCHAR(8)   NOT NULL CHECK (severity IN ('critical','warning','info','other')),
    -- 보류 매처를 만들기 위한 라벨 묶음 전체. API 응답과 AI 근거에는 허용한 라벨만 낸다.
    labels         JSONB        NOT NULL,
    summary        VARCHAR(500) NOT NULL DEFAULT '',
    started_at     TIMESTAMPTZ  NOT NULL,
    first_seen_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    resolved_at    TIMESTAMPTZ,
    status         VARCHAR(16)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','RESOLVED')),
    acked_by       VARCHAR(64),
    acked_at       TIMESTAMPTZ,
    silence_id     VARCHAR(64),
    silenced_until TIMESTAMPTZ,
    silenced_by    VARCHAR(64),
    silence_actor  VARCHAR(8)   CHECK (silence_actor IN ('OPERATOR','AI')),
    CONSTRAINT uq_ops_incident_episode UNIQUE (fingerprint, started_at),
    CONSTRAINT chk_ops_incident_resolved CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL)),
    CONSTRAINT chk_ops_incident_ack CHECK ((acked_by IS NULL) = (acked_at IS NULL)),
    CONSTRAINT chk_ops_incident_silence CHECK (
        (silence_id IS NULL AND silenced_until IS NULL AND silenced_by IS NULL AND silence_actor IS NULL)
        OR (silence_id IS NOT NULL AND silenced_until IS NOT NULL AND silenced_by IS NOT NULL AND silence_actor IS NOT NULL))
);
-- 열린 사건은 지문당 하나다. 같은 지문이 새 시작 시각으로 오면 폴러가 이전 사건을 같은 트랜잭션에서 먼저 닫는다.
CREATE UNIQUE INDEX uq_ops_incident_open ON ops_incidents (fingerprint) WHERE status = 'OPEN';
CREATE INDEX idx_ops_incidents_keyset ON ops_incidents (started_at DESC, id DESC);

CREATE TABLE ops_incident_events (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- NULL 은 사건에 속하지 않는 기록(모드 전환 등)
    incident_id BIGINT       REFERENCES ops_incidents(id),
    kind        VARCHAR(16)  NOT NULL CHECK (kind IN (
                    'OPENED','AI_JUDGED','APPROVED','REJECTED','DEFERRED','ACTED','AUTO_ACTED',
                    'AUTO_REVERTED','REVERTED','MODE','NOTIFIED','ACKED','SILENCED','UNSILENCED','RESOLVED')),
    actor_type  VARCHAR(8)   NOT NULL CHECK (actor_type IN ('SYSTEM','AI','OPERATOR')),
    actor_id    VARCHAR(64)  NOT NULL,
    text        VARCHAR(500) NOT NULL,
    -- 관련 id(decision_id, action_id, threshold_change_id, approval_id, silence_id)
    ref         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_ops_incident_events_incident ON ops_incident_events (incident_id, at, id);
CREATE INDEX idx_ops_incident_events_keyset ON ops_incident_events (at DESC, id DESC);
CREATE TRIGGER trg_ops_incident_events_noupd BEFORE UPDATE OR DELETE ON ops_incident_events
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_ops_incident_events_notrunc BEFORE TRUNCATE ON ops_incident_events
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

GRANT SELECT, INSERT, UPDATE ON ops_incidents TO app_role;
GRANT SELECT, INSERT ON ops_incident_events TO app_role;
GRANT SELECT ON ops_incidents, ops_incident_events TO harness_role;
