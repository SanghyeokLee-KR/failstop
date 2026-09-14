-- 멱등 조회가 같은 (user_id, client_order_id) 의 NEW 행 중 먼저 접수된 것을 고르게 한다(ADR 0022 4-3).
--
-- 왜 하는가. 대역 채번(ADR 0022 6단계) 뒤에는 seq 가 marketId 대역에서 파생되므로 ORDER BY c.seq 가
-- 가장 이른 명령이 아니라 가장 작은 marketId 의 명령을 고른다. created_at 은 append 트랜잭션의 시작
-- 시각이라 대역 전후로 '먼저 접수된 것이 이긴다' 는 뜻이 같고, 같은 시각은 seq 가 가른다. 이 정렬이
-- 값을 가르는 것은 같은 키의 NEW 행이 둘 이상일 때뿐이다. 어드바이저리 락이 그 상태를 막고 C07P 가 센다.
--
-- VOLATILE 을 적는 이유. 어드바이저리 락을 기다린 뒤의 멱등 조회가 앞선 트랜잭션의 커밋을 보려면
-- 문장마다 새 스냅숏을 잡아야 하고, READ COMMITTED 와 VOLATILE 이 그 전제다. 선언이 없어도 기본값이
-- VOLATILE 이라 동작은 같고, 선언은 그 전제를 본문에 못박는다.
--
-- 바꾸지 않는 것. 어드바이저리 락 키와 멱등 조회 WHERE 에 market_id 를 넣지 않는다. 접수 락이 마켓별이라
-- 서로 다른 마켓의 같은 키 동시 재전송을 직렬화하는 것이 이 락뿐이다. 위 두 줄 밖의 함수 본문과 COMMENT,
-- GRANT 는 V39 와 글자까지 같다.
--
-- 되돌리기. V39 의 함수 본문을 CREATE OR REPLACE 로 다시 적용하는 새 마이그레이션을 더한다.

CREATE OR REPLACE FUNCTION append_command_prologue(
    p_holder          TEXT,
    p_epoch           BIGINT,
    p_is_new          BOOLEAN,
    p_user_id         BIGINT,
    p_client_order_id VARCHAR
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
    -- (1) 커밋 전 리더 검증 계약(P0-1). 불일치면 여기서 예외다. 메시지와 SQLSTATE 를
    -- 이 함수가 정하지 않고 assert_current_leader 가 그대로 정하므로 FencingErrors 의
    -- 강등 분류가 바뀌지 않는다.
    PERFORM assert_current_leader(p_holder, p_epoch);

    -- (2) NEW 멱등 재전송 판정. command_journal 은 seq 파티션이라 (user_id, client_order_id)
    -- 유니크를 물리 강제할 수 없어, 동일 키 동시 append 를 트랜잭션 어드바이저리 락으로
    -- 직렬화한 뒤 기존 명령을 조회한다. 락 키 문자열은 종전 자바 코드와 글자까지 같다.
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

    -- (3) 전역 채번. 재전송이 아닐 때만 한다. 롤백되면 이 증가도 함께 되돌아간다(P0-5).
    out_seq      := next_journal_value('command_seq');
    out_replayed := FALSE;
    out_fencing  := NULL;
    out_order_id := NULL;
    RETURN NEXT;
END $$;

COMMENT ON FUNCTION append_command_prologue(TEXT, BIGINT, BOOLEAN, BIGINT, VARCHAR) IS
'접수 저널 append 의 리더 검증과 NEW 멱등 판정과 전역 채번을 한 왕복으로 묶는다(V39). 삽입은 체크섬 계약 때문에 앱이 따로 보낸다.';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_role') THEN
        GRANT EXECUTE ON FUNCTION
            append_command_prologue(TEXT, BIGINT, BOOLEAN, BIGINT, VARCHAR) TO app_role;
    END IF;
END $$;
