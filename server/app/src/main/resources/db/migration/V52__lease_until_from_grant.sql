-- V52__lease_until_from_grant.sql (ADR 0034 S3 관문 X3). 리스 획득이 lease_until 을 행 잠금을 얻은 뒤의 시각에서 센다.
-- 종전 판은 기다리기 전 시각(v_started)에서 세어, 멈춘 옛 리더의 KEY SHARE 를 TTL 보다 오래 기다린 획득이 이미 만료된 리스를
-- 돌려줬다. 새 리더는 첫 쓰기에서 리스 만료 fencing 거부를 받고 종신 강등했다. 판정·epoch 배정·잠금 순서·서명·속성은 그대로다.
CREATE OR REPLACE FUNCTION lease_acquire_core(p_resource TEXT, p_holder TEXT, p_ttl INTERVAL, p_legacy BOOLEAN)
RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE
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
    -- (3) 배정과 기록. 만료 시각은 잠금을 얻은 뒤의 시각에서 센다(V52).
    v_epoch := allocate_lease_epoch();
    UPDATE leader_lease
       SET holder = p_holder, epoch = v_epoch, lease_until = clock_timestamp() + p_ttl
     WHERE resource = p_resource;
    INSERT INTO lease_acquisitions (epoch, resource, holder, protocol, origin)
    VALUES (v_epoch, p_resource, p_holder, v_protocol, 'ACQUIRE');
    RETURN v_epoch;
END $$;

CREATE OR REPLACE FUNCTION lease_acquire_capped(p_resource TEXT, p_holder TEXT, p_ttl INTERVAL, p_max_protocol SMALLINT)
RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE
    v_protocol SMALLINT;
    v_epoch    BIGINT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM leader_lease WHERE resource = p_resource) THEN
        RAISE EXCEPTION 'leader_lease 행 없음: resource=%', p_resource;
    END IF;
    PERFORM 1 FROM leader_lease
     WHERE resource = p_resource AND lease_until < clock_timestamp()
       FOR UPDATE;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    SELECT protocol INTO v_protocol FROM leader_protocol FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'leader_protocol 행 없음: 부트스트랩 오류';
    END IF;
    IF v_protocol > p_max_protocol THEN
        RAISE EXCEPTION '이 호출은 P% 까지만 안다. 현재 P%에서는 리스를 획득하지 않는다', p_max_protocol, v_protocol
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    IF (v_protocol < 3 AND p_resource <> 'matching-engine')
       OR (v_protocol = 3 AND p_resource NOT IN ('matching-p0', 'matching-p1')) THEN
        RAISE EXCEPTION '리스 자원 %은 P%에서 획득할 수 없다', p_resource, v_protocol
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    -- 만료 시각은 잠금을 얻은 뒤의 시각에서 센다(V52).
    v_epoch := allocate_lease_epoch();
    UPDATE leader_lease
       SET holder = p_holder, epoch = v_epoch, lease_until = clock_timestamp() + p_ttl
     WHERE resource = p_resource;
    INSERT INTO lease_acquisitions (epoch, resource, holder, protocol, origin)
    VALUES (v_epoch, p_resource, p_holder, v_protocol, 'ACQUIRE');
    RETURN v_epoch;
END $$;
