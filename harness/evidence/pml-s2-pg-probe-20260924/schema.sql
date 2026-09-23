-- ADR 0034 S2 설계 확인용 사본 스키마. 실제 마이그레이션이 아니다.
-- leader_lease 와 잠금 인덱스는 V1:697-706, V41:27 과 같은 모양이고, 나머지는 S2 설계안의 표와 함수를
-- 줄여 옮긴 것이다. 폐기용 컨테이너에서만 적용한다.
DROP SCHEMA IF EXISTS public CASCADE;
CREATE SCHEMA public;
CREATE EXTENSION IF NOT EXISTS pgrowlocks;

CREATE FUNCTION forbid_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'append-only 위반: %.%', TG_TABLE_NAME, TG_OP; END $$;

CREATE TABLE leader_lease (
    resource    TEXT PRIMARY KEY,
    holder      TEXT NOT NULL,
    epoch       BIGINT NOT NULL DEFAULT 0,
    lease_until TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX uq_leader_lease_fencing_key ON leader_lease (resource, holder, epoch);
INSERT INTO leader_lease VALUES
    ('matching-engine', 'node-a', 5, clock_timestamp() + interval '1 hour'),
    ('matching-p0', '', 0, now() - interval '1 second'),
    ('matching-p1', '', 0, now() - interval '1 second');

CREATE TABLE markets (id SMALLINT PRIMARY KEY, symbol TEXT NOT NULL);
INSERT INTO markets SELECT g, 'M' || g FROM generate_series(1, 12) g;

CREATE TABLE leader_protocol (
    singleton BOOLEAN  PRIMARY KEY DEFAULT true CHECK (singleton),
    protocol  SMALLINT NOT NULL CHECK (protocol IN (1, 2, 3))
);
INSERT INTO leader_protocol VALUES (true, 1);

CREATE TABLE market_lease_partition (
    market_id SMALLINT PRIMARY KEY REFERENCES markets (id),
    resource  TEXT NOT NULL REFERENCES leader_lease (resource)
              CHECK (resource IN ('matching-p0', 'matching-p1'))
);
CREATE TRIGGER trg_mlp_noupd BEFORE UPDATE OR DELETE ON market_lease_partition
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_mlp_notrunc BEFORE TRUNCATE ON market_lease_partition
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

CREATE FUNCTION default_lease_partition(p_market_id SMALLINT) RETURNS TEXT
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN p_market_id IN (1, 4, 5, 7, 9, 11) THEN 'matching-p0'
        WHEN p_market_id IN (2, 3, 6, 8, 10, 12) THEN 'matching-p1'
        WHEN p_market_id % 2 = 1 THEN 'matching-p0'
        ELSE 'matching-p1'
    END
$$;

INSERT INTO market_lease_partition (market_id, resource)
SELECT id, default_lease_partition(id) FROM markets;

CREATE FUNCTION seed_market_lease_partition() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
    INSERT INTO market_lease_partition (market_id, resource)
    VALUES (NEW.id, default_lease_partition(NEW.id))
    ON CONFLICT (market_id) DO NOTHING;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_markets_seed_lease_partition AFTER INSERT ON markets
    FOR EACH ROW EXECUTE FUNCTION seed_market_lease_partition();

CREATE FUNCTION lease_resource_for_market(p_market_id SMALLINT) RETURNS TEXT
LANGUAGE plpgsql STABLE AS $$
DECLARE
    v_protocol SMALLINT;
    v_resource TEXT;
BEGIN
    SELECT protocol INTO v_protocol FROM leader_protocol;
    IF v_protocol < 3 THEN
        RETURN 'matching-engine';
    END IF;
    SELECT resource INTO v_resource FROM market_lease_partition WHERE market_id = p_market_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION '마켓 %의 리스 파티션 대응이 없다', p_market_id
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    RETURN v_resource;
END $$;

CREATE TABLE command_journal (
    seq           BIGINT PRIMARY KEY,
    market_id     SMALLINT NOT NULL,
    payload       TEXT,
    fencing_token BIGINT NOT NULL
);

CREATE SEQUENCE probe_seq CACHE 1 NO CYCLE;

-- SECURITY DEFINER 안에서 잡은 행 잠금이 호출 트랜잭션 끝까지 남는지 보려는 함수.
CREATE FUNCTION lock_lease_key_share(p_market_id SMALLINT) RETURNS BIGINT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE v_epoch BIGINT;
BEGIN
    SELECT epoch INTO v_epoch FROM leader_lease
    WHERE resource = lease_resource_for_market(p_market_id)
    FOR KEY SHARE;
    RETURN v_epoch;
END $$;

-- 잠금 먼저 방식의 획득. 만료 조건을 잠금과 함께 판정하고 잠근 뒤에만 번호를 뽑는다.
CREATE FUNCTION acquire_lock_first(p_resource TEXT, p_holder TEXT) RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE
    v_old   BIGINT;
    v_epoch BIGINT;
BEGIN
    SELECT epoch INTO v_old FROM leader_lease
    WHERE resource = p_resource AND lease_until < clock_timestamp()
    FOR UPDATE;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    v_epoch := GREATEST(nextval('probe_seq'), v_old + 1);
    UPDATE leader_lease SET holder = p_holder, epoch = v_epoch,
                            lease_until = clock_timestamp() + interval '6 seconds'
    WHERE resource = p_resource;
    RETURN v_epoch;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'probe_app') THEN
        CREATE ROLE probe_app NOLOGIN;
    END IF;
END $$;
GRANT USAGE ON SCHEMA public TO probe_app;
GRANT SELECT ON leader_lease, leader_protocol, market_lease_partition TO probe_app;
GRANT SELECT, INSERT ON command_journal, markets TO probe_app;
GRANT EXECUTE ON FUNCTION lock_lease_key_share(SMALLINT), lease_resource_for_market(SMALLINT) TO probe_app;
