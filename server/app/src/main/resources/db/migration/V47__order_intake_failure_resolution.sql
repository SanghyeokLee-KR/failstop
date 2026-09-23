-- 주문 접수가 저널 append 에서 예외를 받은 뒤의 판정. 보존 태그 wip/per-market-leader-20260923 의 V47 을 시계 비교 없이
-- 다시 짰다. 태그의 V47 파일은 이 번호와 겹치므로 그 태그를 되살릴 때는 태그 쪽 V47 을 버린다.
--
-- 게이트웨이는 append 전에 idempotency_keys 행을 넣고, append 가 예외로 끝나면 그 행을 지워 다음 재시도가 잔고 가드를
-- 다시 받게 한다. 종전에는 이 삭제가 무조건이었고 in-flight 예약도 바로 풀었다. 두 경우에 틀렸다.
--
-- 첫째, 커밋 응답 유실. append 는 커밋됐는데 호출자는 예외를 받는다. 키가 지워져 같은 키 재시도가 내용 대조를 못 해
-- 409 로 끝나고, 새 키로 다시 내면 이중 주문이 된다. 예약도 바로 풀려 정산 전 창에서 다른 주문이 같은 잔고를 통과할 수
-- 있다. 그 뒤 정산 예약이 23514 로 실패하면 라이터가 fail-fast 하고 노드가 종신 강등한다.
--
-- 둘째, 같은 내용의 동시 요청. 키를 넣은 요청 A 가 멈춘 사이 요청 B 가 그 키로 내용을 대조하고 저널에 커밋한다. A 가 뒤늦게
-- 실패해 키를 지우면 B 의 주문이 키를 잃는다.
--
-- 둘을 이렇게 가른다.
--
-- (1) 자기 NEW. append 가 예외로 끝난 요청은 자기 assigned_order_id 로 저널을 본다. 주문 id 는 요청마다 새로 채번하므로
--     그 id 의 NEW 가 있으면 이 요청이 커밋한 것이다. 판정 전에 V44 머리말과 같은 어드바이저리 락을 잡으므로 아직 끝나지
--     않은 append 가 있으면 그 커밋이나 롤백이 끝난 뒤에 본다. VOLATILE 함수의 각 문장은 락을 잡은 뒤 새 스냅숏으로 돈다.
--     이때는 키를 지우지 않고 명령의 seq·마켓·fencing 을 돌려준다. 게이트웨이는 그 명령을 라이터에 회수시키고 정상 접수처럼
--     응답한다.
-- (2) 공유 표시. 기존 키로 내용을 대조한 요청은 append 전에 그 키 행의 shared_at 을 채운다. 채워진 키는 넣은 요청이
--     실패해도 지우지 않는다. 대조한 요청이 커밋했을 수 있기 때문이다. 대조한 요청마저 실패하면 키가 고아로 남는데 그것은
--     무해하다. 재시도는 그 키로 내용을 대조한 뒤 command_journal 실재로 잔고 가드 적용을 가른다.
--
-- 태그 판은 (2)를 "키보다 늦은 created_at 의 NEW 가 있으면 남긴다"로 갈랐다. 두 now() 값의 비교라 DB 서버 시계가 뒤로
-- 가면 틀린다. ADR 0022 는 그 시계가 뒤로 갈 수 있다고 적는다(프라이머리 전환이 한 경우다). 여기서는 시계를 읽지 않는다.
--
-- 키를 새로 넣은 요청의 정상 경로는 바뀌지 않는다. 공유 표시는 기존 키로 대조한 요청만 한 번 더 쓴다.
--
-- 적용 조건. idempotency_keys 에 열을 더하므로 ACCESS EXCLUSIVE 잠금을 짧게 잡는다. 기본값이 없는 널 허용 열이라 표를 다시
-- 쓰지 않는다. 접수를 멈춘 배포 창에서 적용한다.
--
-- 되돌리기. 함수를 지우고 게이트웨이를 무조건 삭제로 돌리는 새 마이그레이션을 더한다. shared_at 열은 남겨도 무해하다.

ALTER TABLE idempotency_keys ADD COLUMN shared_at TIMESTAMPTZ;

COMMENT ON COLUMN idempotency_keys.shared_at IS
    '이 키로 내용을 대조한 다른 요청이 저널 append 전에 채운다. 값이 있으면 키를 넣은 요청이 저널 전에 실패해도 지우지 않는다(V47).';

CREATE FUNCTION resolve_failed_order_intake(
    p_user_id           BIGINT,
    p_idem_key          VARCHAR,
    p_request_hash      VARCHAR,
    p_assigned_order_id BIGINT,
    p_discard_key       BOOLEAN
) RETURNS TABLE (
    out_outcome   TEXT,
    out_seq       BIGINT,
    out_market_id SMALLINT,
    out_fencing   BIGINT
)
LANGUAGE plpgsql VOLATILE AS $$
DECLARE
    r RECORD;
BEGIN
    PERFORM pg_advisory_xact_lock(
        hashtextextended('cmd-new:' || p_user_id::text || ':' || p_idem_key, 0));

    -- (1) 이 요청이 채번한 주문 id 로 커밋된 NEW 가 있으면 커밋 응답만 잃은 것이다.
    SELECT c.seq, c.market_id, c.fencing_token INTO r
    FROM command_journal c
    WHERE c.command_type = 'NEW'
      AND c.user_id = p_user_id
      AND c.client_order_id = p_idem_key
      AND c.assigned_order_id = p_assigned_order_id;
    IF FOUND THEN
        RETURN QUERY SELECT 'JOURNALED'::TEXT, r.seq, r.market_id, r.fencing_token;
        RETURN;
    END IF;

    IF NOT p_discard_key THEN
        RETURN QUERY SELECT 'NOT_JOURNALED'::TEXT, NULL::BIGINT, NULL::SMALLINT, NULL::BIGINT;
        RETURN;
    END IF;

    -- (2) 이 요청이 넣은 해시의 키를, 다른 요청이 대조해 공유 표시를 채우지 않았을 때만 지운다.
    DELETE FROM idempotency_keys k
    WHERE k.user_id = p_user_id
      AND k.idem_key = p_idem_key
      AND k.request_hash = p_request_hash
      AND k.shared_at IS NULL;
    IF FOUND THEN
        RETURN QUERY SELECT 'DISCARDED'::TEXT, NULL::BIGINT, NULL::SMALLINT, NULL::BIGINT;
    ELSIF EXISTS (SELECT 1 FROM idempotency_keys k
                  WHERE k.user_id = p_user_id
                    AND k.idem_key = p_idem_key
                    AND k.request_hash = p_request_hash) THEN
        RETURN QUERY SELECT 'KEPT'::TEXT, NULL::BIGINT, NULL::SMALLINT, NULL::BIGINT;
    ELSE
        RETURN QUERY SELECT 'ABSENT'::TEXT, NULL::BIGINT, NULL::SMALLINT, NULL::BIGINT;
    END IF;
END $$;

COMMENT ON FUNCTION resolve_failed_order_intake(BIGINT, VARCHAR, VARCHAR, BIGINT, BOOLEAN) IS
'주문 접수의 append 가 예외로 끝난 뒤 V44 머리말과 같은 어드바이저리 락 아래에서 판정한다. 이 요청의 주문 id 로 커밋된 NEW 가 있으면 JOURNALED 와 그 seq·market_id·fencing_token 을, 없으면 p_discard_key 일 때 공유 표시가 없는 이 해시의 키를 지우고 DISCARDED·KEPT·ABSENT 를, 아니면 NOT_JOURNALED 를 돌려준다(V47).';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_role') THEN
        GRANT EXECUTE ON FUNCTION resolve_failed_order_intake(BIGINT, VARCHAR, VARCHAR, BIGINT, BOOLEAN) TO app_role;
    END IF;
END $$;
