-- fencing 검증과 행 잠금을 한 왕복으로 합친다.
--
-- 종전에는 저널 적재와 정산이 같은 트랜잭션에서 두 문장을 연달아 보냈다.
--   (1) SELECT assert_current_leader(holder, epoch)
--       holder·epoch 불일치와 리스 만료를 서로 다른 메시지로 RAISE 한다. 자바가 그 메시지로
--       강등 사유를 분류한다(FencingErrors).
--   (2) SELECT epoch FROM leader_lease WHERE ... FOR SHARE
--       커밋까지 epoch 불변을 강제한다. 0행이면 stale 확정이다.
--
-- 둘은 겹치지 않는다. (1)은 왜 거부됐는지를 주고 (2)는 커밋까지의 보장을 준다. 그래서 하나를
-- 지우는 것으로는 합칠 수 없고, 둘 다 하는 함수가 필요하다.
--
-- 2026-09-07 실측(pg_stat_statements): assert_current_leader 가 명령당 2.73 회, FOR SHARE 가
-- 1 회다. 서버 실행 시간은 건당 0.04ms 로 작고 비용은 왕복 자체다. 이 함수는 그 왕복을 줄인다.
--
-- 검증을 여기에 복사하지 않고 assert_current_leader 를 그대로 부른다. 이유가 둘이다.
--
-- 첫째, 판정 규칙이 두 곳에 있으면 반드시 갈라진다. 거부 메시지 문구까지 같아야 자바의 강등
-- 분류(FencingErrors.rejectionDetail)가 유지되는데, 복사본은 한쪽만 고쳐지기 쉽다.
--
-- 둘째, fencing 거부를 주입하는 검증이 이 함수를 갈아끼워 동작한다
-- (PromotionStandDownIntegrationTest.installFencingFault). 검증을 복사하면 그 주입이 정산과
-- 이벤트 적재 경로를 비껴가고, split-brain 을 막는 시험이 조용히 무력해진다. 실제로 복사본으로
-- 먼저 구현했다가 그 시험이 깨져서 알았다.
--
-- 순서가 중요하다. 잠금을 먼저 잡고 판정한다. 판정을 먼저 하면 판정과 잠금 사이에 다른 노드가
-- 승격할 수 있다.

CREATE OR REPLACE FUNCTION assert_and_lock_leader(p_holder TEXT, p_epoch BIGINT)
RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE v_epoch BIGINT;
BEGIN
    -- FOR SHARE 는 이 행의 UPDATE 를 막는다. 승격(try_acquire_leader)과 리스 갱신이 그 UPDATE 다.
    -- 종전 (2) 도 같은 잠금을 잡았으므로 잠금 양상은 바뀌지 않는다. 바뀌는 것은 왕복 수뿐이다.
    --
    -- 여기서는 조건 없이 행만 잡는다. 조건 판정은 아래 assert_current_leader 가 한다. 조건을
    -- 여기에 걸면 불일치 시 0행이 되어 그 함수가 볼 행이 사라지고, 거부 사유를 잃는다.
    SELECT epoch INTO v_epoch
    FROM leader_lease WHERE resource = 'matching-engine'
    FOR SHARE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'leader_lease 시드 행 없음: 부트스트랩 오류';
    END IF;

    -- 판정과 거부 메시지는 전적으로 이 함수가 정한다. 여기서 예외가 나면 호출자의
    -- FencingErrors 분류가 종전과 똑같이 동작한다.
    PERFORM assert_current_leader(p_holder, p_epoch);

    -- 위 검증이 holder·epoch 일치를 보장했으므로 이 값은 p_epoch 과 같다. 호출자가
    -- fencing_epoch 컬럼에 쓰는 값이고 종전 (2) 가 돌려주던 것과 같다.
    RETURN v_epoch;
END $$;

COMMENT ON FUNCTION assert_and_lock_leader(TEXT, BIGINT) IS
'leader_lease 를 FOR SHARE 로 잡고 assert_current_leader 로 판정한 뒤 epoch 을 돌려준다(V30). 검증 규칙과 거부 메시지는 그 함수가 단독으로 정한다.';
