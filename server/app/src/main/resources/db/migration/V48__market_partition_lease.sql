-- 매칭 리더를 마켓 파티션 두 개로 나눌 수 있게 DB 를 준비한다(ADR 0034 S2). 모드는 P1 그대로이고 동작은 V47 까지와 같다.
-- 계약과 설계는 docs/reference/contracts/백엔드_DB_설계.md §3.4·§5.4-b, docs/reference/architecture/마켓분할_리더_S2_설계.md 다.
-- 본문은 §3.4 의 V48 SQL 블록과 같다. 권한 회수(V49)는 이 파일에 넣지 않는다. S2 이미지에는 V48 만 들어간다.
--
-- 되돌리기. 프로토콜이 1 인 동안만 한다. 새 표·함수·트리거와 파티션 행 둘(대응 표를 먼저 지운다)을 지우고, 교체한 함수를
-- V44·V41·V33·V1 본문과 호출자 권한으로 되돌리는 새 마이그레이션을 더한다. 대응 표와 이력은 추가 전용이라 표째 DROP 한다.

-- V48__market_partition_lease.sql (ADR 0034 S2). 쓰기를 멈춘 창에서 적용한다.
-- 운영 풀의 statement_timeout·lock_timeout 을 Flyway 가 물려받으면 외래키·트리거 생성이 막힌다(V41 과 같은 근거).
SET LOCAL statement_timeout = 0;
SET LOCAL lock_timeout = 0;

-- ── 획득 이력(추가 전용) ────────────────────────────────────────────
-- epoch 는 모든 리스 행을 통틀어 한 번씩만 쓴다. 감사(ADR 0034 C30)가 이 표로 epoch 의 자원을 증명한다.
CREATE TABLE lease_acquisitions (
    epoch       BIGINT      PRIMARY KEY,
    resource    TEXT        NOT NULL REFERENCES leader_lease (resource),
    holder      TEXT        NOT NULL,
    protocol    SMALLINT    NOT NULL CHECK (protocol IN (1, 2, 3)),
    -- ACQUIRE: DB 획득, PUBLISH: 외부 보관소 게시(P1·P2), RETIRE: 전환 함수의 퇴역(S3·S7, V48 코드는 쓰지 않는다)
    origin      TEXT        NOT NULL CHECK (origin IN ('ACQUIRE', 'PUBLISH', 'RETIRE')),
    acquired_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER trg_lease_acq_noupd BEFORE UPDATE OR DELETE ON lease_acquisitions
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_lease_acq_notrunc BEFORE TRUNCATE ON lease_acquisitions
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

-- ── 모드 래치(한 행)와 이력(추가 전용) ──────────────────────────────
CREATE TABLE leader_protocol (
    singleton  BOOLEAN     PRIMARY KEY DEFAULT true CHECK (singleton),
    protocol   SMALLINT    NOT NULL CHECK (protocol IN (1, 2, 3)),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    changed_by TEXT        NOT NULL DEFAULT session_user
);
CREATE TABLE leader_protocol_history (
    id            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    protocol      SMALLINT    NOT NULL CHECK (protocol IN (1, 2, 3)),
    prev_protocol SMALLINT    CHECK (prev_protocol IN (1, 2, 3)),
    -- 변경 시점의 max(리스 행 epoch, 획득 이력 epoch). 변경 트랜잭션이 프로토콜 행을 쥐므로 그 뒤 배정되는
    -- epoch 는 모두 이 값보다 크다. 감사가 시계 대신 epoch 범위로 프로토콜 구간을 가른다(ADR 0022 9-17).
    epoch_floor   BIGINT      NOT NULL,
    changed_at    TIMESTAMPTZ NOT NULL,
    changed_by    TEXT        NOT NULL
);
-- leader_protocol 은 지우거나 비울 수 없다. UPDATE 는 소유자만 한다(app_role 은 SELECT).
CREATE TRIGGER trg_leader_protocol_nodel BEFORE DELETE ON leader_protocol
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_leader_protocol_notrunc BEFORE TRUNCATE ON leader_protocol
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_protocol_hist_noupd BEFORE UPDATE OR DELETE ON leader_protocol_history
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_protocol_hist_notrunc BEFORE TRUNCATE ON leader_protocol_history
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

CREATE FUNCTION stamp_leader_protocol() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.changed_at := clock_timestamp();
    NEW.changed_by := session_user;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_leader_protocol_stamp BEFORE UPDATE ON leader_protocol
    FOR EACH ROW EXECUTE FUNCTION stamp_leader_protocol();

-- INSERT(시드)와 protocol 이 바뀐 UPDATE 만 기록한다. 같은 값 UPDATE 는 남기지 않는다.
CREATE FUNCTION record_leader_protocol_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.protocol = OLD.protocol THEN
        RETURN NULL;
    END IF;
    INSERT INTO leader_protocol_history (protocol, prev_protocol, epoch_floor, changed_at, changed_by)
    VALUES (NEW.protocol,
            CASE WHEN TG_OP = 'UPDATE' THEN OLD.protocol END,
            GREATEST(COALESCE((SELECT max(epoch) FROM leader_lease), 0),
                     COALESCE((SELECT max(epoch) FROM lease_acquisitions), 0)),
            NEW.changed_at, NEW.changed_by);
    RETURN NULL;
END $$;
CREATE TRIGGER trg_leader_protocol_history AFTER INSERT OR UPDATE ON leader_protocol
    FOR EACH ROW EXECUTE FUNCTION record_leader_protocol_change();

INSERT INTO leader_protocol (protocol) VALUES (1);

-- ── 파티션 리스 행 ──────────────────────────────────────────────────
-- V1 시드와 같은 모양(보유자 없음, epoch 0, 만료). P1·P2 에서는 아무도 획득하지 않는다.
INSERT INTO leader_lease (resource, holder, epoch, lease_until) VALUES
    ('matching-p0', '', 0, now() - interval '1 s'),
    ('matching-p1', '', 0, now() - interval '1 s');

-- ── 마켓 대응(추가 전용) ────────────────────────────────────────────
CREATE TABLE market_lease_partition (
    market_id SMALLINT    PRIMARY KEY REFERENCES markets (id),
    resource  TEXT        NOT NULL REFERENCES leader_lease (resource)
              CONSTRAINT chk_mlp_partition_resource CHECK (resource IN ('matching-p0', 'matching-p1')),
    mapped_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    mapped_by TEXT        NOT NULL DEFAULT session_user
);
-- 이 판에서 마켓은 다른 파티션으로 옮기지 않는다(ADR 0034 결정 1). journal_cutover(V44)와 같은 추가 전용 강제다.
CREATE TRIGGER trg_mlp_noupd BEFORE UPDATE OR DELETE ON market_lease_partition
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_mlp_notrunc BEFORE TRUNCATE ON market_lease_partition
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
CREATE FUNCTION default_lease_partition(p_market_id SMALLINT) RETURNS TEXT
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN p_market_id IN (1, 4, 5, 7, 9, 11)  THEN 'matching-p0'   -- ADR 0034 결정한 값 3
        WHEN p_market_id IN (2, 3, 6, 8, 10, 12) THEN 'matching-p1'
        WHEN p_market_id % 2 = 1                 THEN 'matching-p0'   -- 13 이상은 홀수 p0, 짝수 p1
        ELSE 'matching-p1'
    END
$$;
INSERT INTO market_lease_partition (market_id, resource)
SELECT id, default_lease_partition(id) FROM markets;
-- 마켓은 Flyway 뒤 앱·시드 도구가 넣는다. 트리거가 대응 행을 같은 트랜잭션에 넣는다. 트리거 발화는 EXECUTE 권한을
-- 보지 않지만 CREATE TRIGGER 는 본다. 아래 권한 절이 PUBLIC EXECUTE 를 회수해, 다른 역할이 자기 임시 표에 이 함수를
-- 걸어 소유자 권한으로 대응 행을 넣지 못하게 한다(S2 설계 10절 검토 1).
CREATE FUNCTION seed_market_lease_partition() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    INSERT INTO market_lease_partition (market_id, resource)
    VALUES (NEW.id, default_lease_partition(NEW.id))
    ON CONFLICT (market_id) DO NOTHING;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_markets_seed_lease_partition AFTER INSERT ON markets
    FOR EACH ROW EXECUTE FUNCTION seed_market_lease_partition();

-- ── 도출 ────────────────────────────────────────────────────────────
-- P1·P2 는 markets·대응 표를 읽지 않는다. 마켓 행 없이 쓰는 경로가 종전처럼 돈다.
CREATE FUNCTION lease_resource_for_market(p_market_id SMALLINT) RETURNS TEXT
LANGUAGE plpgsql STABLE AS $$
DECLARE
    v_protocol SMALLINT;
    v_resource TEXT;
BEGIN
    SELECT protocol INTO v_protocol FROM leader_protocol;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'leader_protocol 행 없음: 부트스트랩 오류';
    END IF;
    IF v_protocol < 3 THEN
        RETURN 'matching-engine';
    END IF;
    SELECT resource INTO v_resource FROM market_lease_partition WHERE market_id = p_market_id;
    IF NOT FOUND THEN
        -- 강등 분류 문구(fencing 위반)를 쓰지 않는다. 설정 결함이지 오래된 리더가 아니다.
        RAISE EXCEPTION '마켓 %의 리스 파티션 대응이 없어 쓰기를 거부한다(market_lease_partition)', p_market_id
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    RETURN v_resource;
END $$;

-- ── 판정(유일한 규칙) ───────────────────────────────────────────────
-- V1 assert_current_leader 본문을 자원 인자로 옮긴다. matching-engine 이면 문구가 V1 과 바이트 단위로 같다.
CREATE FUNCTION assert_lease_holder(p_resource TEXT, p_holder TEXT, p_epoch BIGINT) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    r        RECORD;
    v_suffix TEXT := CASE WHEN p_resource = 'matching-engine' THEN '' ELSE ' resource=' || p_resource END;
BEGIN
    SELECT holder, epoch, lease_until INTO r FROM leader_lease WHERE resource = p_resource;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'leader_lease 시드 행 없음: 부트스트랩 오류%', v_suffix;
    END IF;
    IF r.holder <> p_holder OR r.epoch <> p_epoch THEN
        RAISE EXCEPTION 'fencing 위반: 현재 리더(holder=%, epoch=%)가 아님(요청 holder=%, epoch=%)%',
            r.holder, r.epoch, p_holder, p_epoch, v_suffix;
    END IF;
    IF r.lease_until < clock_timestamp() THEN
        RAISE EXCEPTION 'fencing 위반: 리스 만료(lease_until=%, now=%)%',
            r.lease_until, clock_timestamp(), v_suffix;
    END IF;
END $$;

-- 2인자 판정은 위임으로 바꾼다. 옛 경로(V44 5인자 머리말, 2인자 assert_and_lock_leader)도 같은 규칙을 탄다.
CREATE OR REPLACE FUNCTION assert_current_leader(p_holder TEXT, p_epoch BIGINT) RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM assert_lease_holder('matching-engine', p_holder, p_epoch);
END $$;

-- 잠금 뒤 판정(V41 순서). 조건 없이 잡아야 불일치 때 판정이 거부 사유를 잃지 않는다.
CREATE FUNCTION assert_and_lock_market_leader(p_market_id SMALLINT, p_holder TEXT, p_epoch BIGINT)
RETURNS BIGINT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_resource TEXT := lease_resource_for_market(p_market_id);
    v_epoch    BIGINT;
BEGIN
    SELECT epoch INTO v_epoch FROM leader_lease WHERE resource = v_resource FOR KEY SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'leader_lease 시드 행 없음: 부트스트랩 오류';
    END IF;
    PERFORM assert_lease_holder(v_resource, p_holder, p_epoch);
    RETURN v_epoch;
END $$;

-- ── 명령 저널 append ────────────────────────────────────────────────
-- market_id 를 한 번 받아 행의 market_id 와 리스 도출에 함께 쓴다. 체크섬은 seq 를 받은 뒤 앱이 계산한다(V39 근거).
CREATE FUNCTION append_command_journal_row(
    p_seq BIGINT, p_market_id SMALLINT, p_command_type VARCHAR, p_user_id BIGINT,
    p_client_order_id VARCHAR, p_assigned_order_id BIGINT, p_engine_ts BIGINT, p_tie_break BIGINT,
    p_payload JSONB, p_checksum BIGINT, p_holder TEXT, p_epoch BIGINT
) RETURNS INTEGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE v_rows INTEGER;
BEGIN
    INSERT INTO command_journal
      (seq, market_id, command_type, user_id, client_order_id, assigned_order_id,
       engine_ts, tie_break, payload, fencing_token, checksum)
    SELECT p_seq, v.market_id, p_command_type, p_user_id, p_client_order_id, p_assigned_order_id,
           p_engine_ts, p_tie_break, p_payload, l.epoch, p_checksum
    FROM (VALUES (p_market_id)) AS v(market_id)
    JOIN leader_lease l ON l.resource = lease_resource_for_market(v.market_id)
    WHERE l.holder = p_holder AND l.epoch = p_epoch AND l.lease_until > clock_timestamp()
    FOR KEY SHARE OF l;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    RETURN v_rows;   -- 0 이면 오래된 리더다. 호출자가 예외로 채번까지 롤백한다
END $$;

-- 6인자 머리말. V44 본문에서 (1) 판정 한 줄만 바꾼다. 멱등 판정, 어드바이저리 락 키, 래치 채번, VOLATILE,
-- 호출자 권한은 V44 그대로다. 잠그지 않는 판정이고 잠금은 append_command_journal_row 가 잡는다.
CREATE OR REPLACE FUNCTION append_command_prologue(
    p_holder          TEXT,
    p_epoch           BIGINT,
    p_is_new          BOOLEAN,
    p_user_id         BIGINT,
    p_client_order_id VARCHAR,
    p_market_id       SMALLINT
) RETURNS TABLE (
    out_seq        BIGINT,
    out_replayed   BOOLEAN,
    out_fencing    BIGINT,
    out_order_id   BIGINT
)
LANGUAGE plpgsql VOLATILE AS $$
DECLARE
    r RECORD;
BEGIN
    -- (1) 커밋 전 리더 검증. 자원은 이 명령의 마켓에서 도출한다(P1·P2 는 matching-engine).
    PERFORM assert_lease_holder(lease_resource_for_market(p_market_id), p_holder, p_epoch);

    -- (2) NEW 멱등 재전송 판정. 락 키와 조회 술어에 market_id 를 넣지 않는다(ADR 0022 4-3).
    IF p_is_new AND p_client_order_id IS NOT NULL THEN
        PERFORM pg_advisory_xact_lock(
            hashtextextended('cmd-new:' || p_user_id::text || ':' || p_client_order_id, 0));

        SELECT c.seq, c.fencing_token, c.assigned_order_id INTO r
        FROM command_journal c
        WHERE c.command_type = 'NEW'
          AND c.user_id = p_user_id
          AND c.client_order_id = p_client_order_id
        ORDER BY c.created_at, c.seq
        LIMIT 1;

        IF FOUND THEN
            out_seq      := r.seq;
            out_replayed := TRUE;
            out_fencing  := r.fencing_token;
            out_order_id := r.assigned_order_id;
            RETURN NEXT;
            RETURN;
        END IF;
    END IF;

    -- (3) 채번. V44 와 같다.
    IF EXISTS (SELECT 1 FROM journal_cutover WHERE name = 'command_seq') THEN
        out_seq := next_market_command_seq(p_market_id);
    ELSE
        out_seq := next_journal_value('command_seq');
    END IF;
    out_replayed := FALSE;
    out_fencing  := NULL;
    out_order_id := NULL;
    RETURN NEXT;
END $$;

-- ── epoch 배정과 획득 ───────────────────────────────────────────────
-- 내부 함수. 호출자가 leader_protocol 행을 FOR UPDATE 로 쥔 뒤 READ COMMITTED 에서만 부른다.
-- 그 행이 배정을 한 줄로 세우므로 다음 배정은 앞 배정의 커밋을 본다. 롤백하면 배정도 사라진다.
CREATE FUNCTION allocate_lease_epoch() RETURNS BIGINT
LANGUAGE plpgsql VOLATILE AS $$
BEGIN
    RETURN GREATEST(COALESCE((SELECT max(epoch) FROM leader_lease), 0),
                    COALESCE((SELECT max(epoch) FROM lease_acquisitions), 0)) + 1;
END $$;

-- 내부 함수. 획득 본문 하나를 두 공개 함수가 부른다.
CREATE FUNCTION lease_acquire_core(p_resource TEXT, p_holder TEXT, p_ttl INTERVAL, p_legacy BOOLEAN)
RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE
    -- V47 까지의 한 문장 UPDATE 는 lease_until 을 행 잠금 대기 전에 계산했다. P1 동등성을 위해 같은 기준을 쓴다.
    v_started  TIMESTAMPTZ := clock_timestamp();
    v_protocol SMALLINT;
    v_epoch    BIGINT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM leader_lease WHERE resource = p_resource) THEN
        RAISE EXCEPTION 'leader_lease 행 없음: resource=%', p_resource;
    END IF;
    -- (1) 만료된 행만 잠근다. FOR UPDATE 는 진행 중 쓰기의 KEY SHARE 커밋을 기다리고, 경합에서 진 쪽은
    --     재평가에서 조건이 거짓이 되어 아무것도 쓰지 않는다.
    PERFORM 1 FROM leader_lease
     WHERE resource = p_resource AND lease_until < clock_timestamp()
       FOR UPDATE;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    -- (2) 획득·게시·전환을 한 줄로 세우고 이 트랜잭션 동안 모드를 고정한다. 잠금 순서는 리스 행 다음 프로토콜 행이다.
    SELECT protocol INTO v_protocol FROM leader_protocol FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'leader_protocol 행 없음: 부트스트랩 오류';
    END IF;
    IF p_legacy AND v_protocol <> 1 THEN
        RAISE EXCEPTION '2인자 try_acquire_leader 는 P1 에서만 획득한다(현재 P%)', v_protocol
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    IF (v_protocol < 3 AND p_resource <> 'matching-engine')
       OR (v_protocol = 3 AND p_resource NOT IN ('matching-p0', 'matching-p1')) THEN
        RAISE EXCEPTION '리스 자원 %은 P%에서 획득할 수 없다', p_resource, v_protocol
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    -- (3) 배정과 기록
    v_epoch := allocate_lease_epoch();
    UPDATE leader_lease
       SET holder = p_holder, epoch = v_epoch, lease_until = v_started + p_ttl
     WHERE resource = p_resource;
    INSERT INTO lease_acquisitions (epoch, resource, holder, protocol, origin)
    VALUES (v_epoch, p_resource, p_holder, v_protocol, 'ACQUIRE');
    RETURN v_epoch;
END $$;

CREATE FUNCTION try_acquire_lease(p_resource TEXT, p_holder TEXT, p_ttl INTERVAL) RETURNS BIGINT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN lease_acquire_core(p_resource, p_holder, p_ttl, false);
END $$;

-- 매개변수 이름은 V1 과 같게 둔다(CREATE OR REPLACE 는 입력 매개변수 이름을 바꾸지 못한다).
CREATE OR REPLACE FUNCTION try_acquire_leader(p_holder TEXT, p_ttl INTERVAL) RETURNS BIGINT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN lease_acquire_core('matching-engine', p_holder, p_ttl, true);
END $$;

-- ── 갱신·반납 ───────────────────────────────────────────────────────
-- lease_until 만 바꾸므로 FOR NO KEY UPDATE 이고 쓰기의 KEY SHARE 를 기다리지 않는다(V41).
CREATE FUNCTION renew_lease(p_resource TEXT, p_holder TEXT, p_epoch BIGINT, p_ttl INTERVAL) RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    UPDATE leader_lease
       SET lease_until = clock_timestamp() + p_ttl
     WHERE resource = p_resource AND holder = p_holder AND epoch = p_epoch
       AND lease_until >= clock_timestamp();
    RETURN FOUND;
END $$;

CREATE OR REPLACE FUNCTION renew_leader_lease(p_holder TEXT, p_epoch BIGINT, p_ttl INTERVAL) RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN renew_lease('matching-engine', p_holder, p_epoch, p_ttl);
END $$;

-- 엔진의 강등 경로는 반납하지 않는다(ADR 0002:56). 운영·시험의 즉시 비우기 전용이다.
CREATE FUNCTION release_lease(p_resource TEXT, p_holder TEXT, p_epoch BIGINT) RETURNS BOOLEAN
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second'
     WHERE resource = p_resource AND holder = p_holder AND epoch = p_epoch;
    RETURN FOUND;
END $$;

-- ── 외부 보관소 게시(P1·P2 전용) ────────────────────────────────────
-- V33 단조 UPDATE 를 그대로 두고 모드 확인과 획득 이력을 더한다. P3 거부는 ADR 0034 결정한 값 18 이다.
CREATE OR REPLACE FUNCTION publish_leader_epoch(p_holder TEXT, p_epoch BIGINT) RETURNS BIGINT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_epoch    BIGINT;
    v_protocol SMALLINT;
BEGIN
    UPDATE leader_lease
       SET holder = p_holder, epoch = p_epoch,
           lease_until = clock_timestamp() + interval '100 years'
     WHERE resource = 'matching-engine'
       AND (epoch < p_epoch OR (epoch = p_epoch AND holder = p_holder))
    RETURNING epoch INTO v_epoch;
    IF v_epoch IS NULL THEN
        RETURN NULL;
    END IF;
    SELECT protocol INTO v_protocol FROM leader_protocol FOR UPDATE;
    IF v_protocol = 3 THEN
        RAISE EXCEPTION '파티션 모드(P3)에서는 외부 보관소 epoch 게시를 거부한다'
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    -- 같은 보유자의 재게시는 정상 경로다(V33). 같은 epoch 가 다른 자원·보유자로 이미 있으면 거부한다.
    INSERT INTO lease_acquisitions (epoch, resource, holder, protocol, origin)
    VALUES (v_epoch, 'matching-engine', p_holder, v_protocol, 'PUBLISH')
    ON CONFLICT (epoch) DO NOTHING;
    IF NOT FOUND AND NOT EXISTS (SELECT 1 FROM lease_acquisitions
                                 WHERE epoch = v_epoch AND resource = 'matching-engine'
                                   AND holder = p_holder) THEN
        RAISE EXCEPTION '게시 epoch %가 다른 획득 이력과 겹친다', v_epoch;
    END IF;
    RETURN v_epoch;
END $$;

-- V33 본문 그대로 소유자 권한으로 바꾼다. 외부 보관소에서 PostgreSQL 보관소로 되돌릴 때 쓴다.
CREATE OR REPLACE FUNCTION expire_leader_lease_now() RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    UPDATE leader_lease
       SET lease_until = clock_timestamp() - interval '1 second'
     WHERE resource = 'matching-engine';
END $$;

-- V41 본문 그대로 소유자 권한으로 바꾼다. S2 코드는 부르지 않고 V48 이전 바이너리의 정산·스냅샷 경로가 부른다.
-- 소유자 권한이라 V49 뒤에도 그 경로는 잠글 수 있다. 판정은 위임된 assert_current_leader 를 거친다.
CREATE OR REPLACE FUNCTION assert_and_lock_leader(p_holder TEXT, p_epoch BIGINT) RETURNS BIGINT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE v_epoch BIGINT;
BEGIN
    SELECT epoch INTO v_epoch
    FROM leader_lease WHERE resource = 'matching-engine'
    FOR KEY SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'leader_lease 시드 행 없음: 부트스트랩 오류';
    END IF;
    PERFORM assert_current_leader(p_holder, p_epoch);
    RETURN v_epoch;
END $$;

-- ── 권한 ────────────────────────────────────────────────────────────
-- 호출자 권한 함수를 CREATE OR REPLACE 로 소유자 권한으로 바꾸면 PUBLIC 의 기본 EXECUTE 가 남아 표 권한이 없는
-- 역할도 리스를 바꿀 수 있다(S2 설계 3절 Q10). 리스를 잠그거나 바꾸는 함수와 내부 함수에서 모두 회수한다.
-- 소유자 권한 트리거 함수도 회수한다. CREATE TRIGGER 가 EXECUTE 를 검사하므로 남겨 두면 임시 표를 만들 수 있는
-- 모든 역할이 이 함수를 자기 표에 걸어 소유자 권한으로 돌린다. 발화는 EXECUTE 를 보지 않아 마켓 생성은 그대로다.
REVOKE ALL ON FUNCTION
    seed_market_lease_partition(),
    allocate_lease_epoch(),
    lease_acquire_core(TEXT, TEXT, INTERVAL, BOOLEAN),
    try_acquire_lease(TEXT, TEXT, INTERVAL),
    try_acquire_leader(TEXT, INTERVAL),
    renew_lease(TEXT, TEXT, BIGINT, INTERVAL),
    renew_leader_lease(TEXT, BIGINT, INTERVAL),
    release_lease(TEXT, TEXT, BIGINT),
    publish_leader_epoch(TEXT, BIGINT),
    expire_leader_lease_now(),
    assert_and_lock_leader(TEXT, BIGINT),
    assert_and_lock_market_leader(SMALLINT, TEXT, BIGINT),
    append_command_journal_row(BIGINT, SMALLINT, VARCHAR, BIGINT, VARCHAR, BIGINT, BIGINT, BIGINT,
                               JSONB, BIGINT, TEXT, BIGINT)
FROM PUBLIC;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_role') THEN
        GRANT EXECUTE ON FUNCTION
            try_acquire_lease(TEXT, TEXT, INTERVAL),
            try_acquire_leader(TEXT, INTERVAL),
            renew_lease(TEXT, TEXT, BIGINT, INTERVAL),
            renew_leader_lease(TEXT, BIGINT, INTERVAL),
            release_lease(TEXT, TEXT, BIGINT),
            publish_leader_epoch(TEXT, BIGINT),
            expire_leader_lease_now(),
            assert_and_lock_leader(TEXT, BIGINT),
            assert_and_lock_market_leader(SMALLINT, TEXT, BIGINT),
            append_command_journal_row(BIGINT, SMALLINT, VARCHAR, BIGINT, VARCHAR, BIGINT, BIGINT, BIGINT,
                                       JSONB, BIGINT, TEXT, BIGINT),
            lease_resource_for_market(SMALLINT),
            assert_lease_holder(TEXT, TEXT, BIGINT)
        TO app_role;
        GRANT SELECT ON leader_protocol, leader_protocol_history, lease_acquisitions, market_lease_partition
            TO app_role;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'harness_role') THEN
        GRANT SELECT ON leader_protocol, leader_protocol_history, lease_acquisitions, market_lease_partition
            TO harness_role;
    END IF;
END $$;
