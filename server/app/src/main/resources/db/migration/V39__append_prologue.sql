-- 접수 저널 append 의 앞 네 문장을 한 왕복으로 묶는다.
--
-- 왜 하는가. 2026-09-11 실측에서 처리량 상한이 EngineBridge.submitLock 으로 특정됐고
-- (정본 5.7.9, 락 보유 점유 0.993) 그 보유 7.878밀리초를 문장별로 갈랐다(정본 5.7.11).
--
--   커넥션 획득과 트랜잭션 경계와 커밋   4.729밀리초
--   멱등 판정                          1.085밀리초   문장 둘
--   리더 검증                          0.745밀리초   문장 하나
--   저널 삽입                          0.693밀리초   문장 하나
--   전역 채번                          0.624밀리초   문장 하나
--
-- 같은 회차에서 문장 넷의 데이터베이스 실행 합계가 약 0.45밀리초였다. 곧 문장당 약
-- 0.5밀리초가 데이터베이스 밖 비용이고 왕복 수가 그만큼 값이다. 앞 네 문장은 서로의
-- 결과를 입력으로 쓰지만 전부 이 트랜잭션 안에서 끝나므로 한 함수에 담을 수 있다.
--
-- 왜 삽입은 안 묶는가. 체크섬 때문이다. command_journal.checksum 은 seq 와 Jackson 으로
-- 정규화한 payload 를 먹는 CRC32 이고(JournalChecksum.of) 그 정규화를 plpgsql 로 옮기면
-- 같은 계약이 두 언어에 생긴다. 이 저장소가 2026-09-10 에 같은 형태의 문서 복제로 시험
-- 열두 건을 깬 적이 있다(정본 13.3 한계 41). 그래서 채번까지만 함수가 하고 앱이 그 seq 로
-- 체크섬을 계산해 삽입을 따로 보낸다. 왕복이 다섯에서 둘로 준다.
--
-- 채번은 멱등 판정 뒤에만 한다. 순서를 바꾸면 재전송마다 seq 가 하나씩 타고 C05 결번
-- 없음이 깨진다. 종전 자바 코드가 같은 순서였고 그 순서를 함수 구조로 옮겨 못박는다.
--
-- 바꾸지 않는 것. 검증 내용과 예외 메시지와 발생 순서, 어드바이저리 락 키 문자열,
-- 멱등 조회의 술어와 정렬, 트랜잭션 경계, 스키마다.

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
LANGUAGE plpgsql AS $$
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
        ORDER BY c.seq
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
