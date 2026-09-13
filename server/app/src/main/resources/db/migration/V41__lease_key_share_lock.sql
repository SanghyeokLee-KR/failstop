-- 쓰기 경로의 leader_lease 행 잠금을 FOR KEY SHARE 로 낮춘다(ADR 0026).
--
-- 왜 하는가. V30 의 FOR SHARE 는 이 행의 모든 UPDATE 와 충돌한다. lease_until 만 바꾸는
-- renew_leader_lease 도 막히고, 마켓별 라이터들의 공유 잠금이 교대로 이어지면 갱신이 TTL 안에
-- 잠금을 얻지 못한다(harness/evidence/e1-lease-starvation-20260913/).
--
-- 무엇이 성립하나. PostgreSQL 은 부분·식이 아닌 유일 인덱스의 열을 키 열로 본다. 키 열을 바꾸는
-- UPDATE 는 FOR UPDATE, 나머지는 FOR NO KEY UPDATE 를 잡고 FOR KEY SHARE 는 앞과만 충돌한다.
--   try_acquire_leader, publish_leader_epoch(holder·epoch 변경): 진행 중 쓰기 트랜잭션 커밋을 기다린다.
--   renew_leader_lease, expire_leader_lease_now(lease_until 만 변경): 기다리지 않는다.
-- 판정과 커밋 사이에 holder·epoch 이 바뀌지 않는다는 보장은 그대로다. lease_until 은 보장 대상이 아니다.
--
-- 인덱스가 그 보장의 전제다. 없거나 부분·식·INCLUDE 인덱스면 holder·epoch 가 키 열에서 빠져 승격이
-- 쓰기를 기다리지 않는다. 그래서 같은 트랜잭션에서 인덱스를 먼저 만든다. CONCURRENTLY 로 가르지 않는다.
--
-- 앱의 command_journal append(CommandJournalAppender) 도 같은 행을 FOR KEY SHARE 로 잡는다. 두 지점 중
-- 하나라도 FOR SHARE 로 남으면 갱신과 다시 충돌한다.
--
-- 되돌리기. 앱의 append 를 FOR SHARE 로 먼저 되돌린 뒤 함수를 V30 본문과 COMMENT 로, 마지막에 인덱스
-- DROP. 앱이 FOR KEY SHARE 인 채로 인덱스만 지우면 stale write 가 열린다.

-- CREATE INDEX 가 leader_lease 에 SHARE 잠금을 잡아 갱신 UPDATE 와 충돌한다. prod 풀의
-- statement_timeout=3s/lock_timeout=2s 를 Flyway 가 상속하면 부팅이 막힌다(V12, V40 과 같은 근거).
SET LOCAL statement_timeout = 0;
SET LOCAL lock_timeout = 0;

CREATE UNIQUE INDEX uq_leader_lease_fencing_key ON leader_lease (resource, holder, epoch);

COMMENT ON INDEX uq_leader_lease_fencing_key IS
'유일성 제약이 목적이 아니다. holder·epoch 를 키 열로 만들어 쓰기 경로의 FOR KEY SHARE 가 승격·게시를 막고 갱신은 막지 않게 한다(V41, ADR 0026). 지우거나 부분·식·INCLUDE 인덱스로 바꾸면 stale write 가 열린다.';

CREATE OR REPLACE FUNCTION assert_and_lock_leader(p_holder TEXT, p_epoch BIGINT)
RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE v_epoch BIGINT;
BEGIN
    -- FOR KEY SHARE 는 holder·epoch 를 바꾸는 UPDATE(승격, 게시)만 막고 lease_until 갱신은 막지 않는다.
    -- 그 구분의 근거가 uq_leader_lease_fencing_key 다.
    -- 조건 없이 행만 잡는다. 조건을 걸면 불일치 시 0행이 되어 아래 함수가 거부 사유를 잃는다.
    SELECT epoch INTO v_epoch
    FROM leader_lease WHERE resource = 'matching-engine'
    FOR KEY SHARE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'leader_lease 시드 행 없음: 부트스트랩 오류';
    END IF;

    -- 판정과 거부 메시지는 이 함수가 단독으로 정한다(FencingErrors 분류가 그 메시지에 기댄다).
    PERFORM assert_current_leader(p_holder, p_epoch);

    RETURN v_epoch;
END $$;

COMMENT ON FUNCTION assert_and_lock_leader(TEXT, BIGINT) IS
'leader_lease 를 FOR KEY SHARE 로 잡고 assert_current_leader 로 판정한 뒤 epoch 을 돌려준다(V30, 잠금 모드 V41). 승격과 게시는 커밋까지 기다리고 갱신은 기다리지 않는다. 검증 규칙과 거부 메시지는 assert_current_leader 가 단독으로 정한다.';
