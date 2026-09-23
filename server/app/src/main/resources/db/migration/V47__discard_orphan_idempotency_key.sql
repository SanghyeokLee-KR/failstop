-- 접수 실패 뒤 고아 멱등 키의 조건부 삭제(ADR 0034 S1).
--
-- 게이트웨이는 저널 기록 전에 idempotency_keys 행을 넣고, 저널에 닿지 못한 실패면 그 행을 지워 다음 재시도가 잔고
-- 가드를 다시 받게 한다. 그런데 키를 넣은 요청이 멈춘 사이 같은 키·같은 내용의 다른 요청이 그 행을 보고 저널에
-- 커밋할 수 있다. 원 요청이 뒤늦게 실패해 행을 지우면 커밋된 주문의 키가 사라지고, 그 주문의 정당한 재시도가
-- 내용 대조를 못 해 409 로 끝난다. 응답을 잃은 커밋(요청은 실패로 보았으나 저널 행은 커밋됨)도 같은 모양이다.
--
-- 그래서 키를 넣은 뒤에 저널에 들어온 그 키의 NEW 가 있으면 지우지 않는다. 판정 전에 V44 머리말과 같은 어드바이저리
-- 락을 잡으므로 진행 중인 append 가 있으면 그 커밋이 끝난 뒤에 판정한다. VOLATILE 함수의 DELETE 는 락을 잡은 뒤 새
-- 스냅샷으로 돌아 그 사이 커밋된 저널 행을 본다.
--
-- "키를 넣은 뒤"를 created_at 으로 가르는 이유. 키보다 먼저 저널에 있던 NEW 는 이 키의 주인이 아니다. 보존 기간이
-- 지나 키가 지워진 옛 주문이나, 종전 게이트웨이의 무조건 삭제로 키를 잃은 주문이 그렇다. 그 NEW 를 주인으로 보고
-- 키를 남기면 내용이 다른 요청의 해시가 그 주문에 붙는다. 다음 재시도가 그 해시로 내용 대조에 성공하고 저널 멱등
-- 재생으로 남의 주문을 200 으로 돌려받는다. 정당한 주인은 늘 이 키의 INSERT 가 커밋된 뒤에 append 트랜잭션을 연다.
-- 같은 요청의 응답을 잃은 커밋은 키를 넣은 뒤 append 를 시작했고, 같은 내용의 다른 요청은 이 키를 읽은 뒤
-- append 를 시작했다. 두 열 모두 DB 의 now() 기본값이라(V1 command_journal·idempotency_keys, 앱이 값을 넣지
-- 않는다) 같은 시계로 비교된다. V43 이 같은 created_at 을 "먼저 접수된 NEW" 판정 축으로 쓴다.
--
-- 되돌리기. 이 함수를 지우고 게이트웨이를 무조건 삭제로 돌리는 새 마이그레이션을 더한다. 데이터는 바뀌지 않는다.

CREATE FUNCTION discard_orphan_idempotency_key(
    p_user_id      BIGINT,
    p_idem_key     VARCHAR,
    p_request_hash VARCHAR
) RETURNS BOOLEAN
LANGUAGE plpgsql VOLATILE AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(
        hashtextextended('cmd-new:' || p_user_id::text || ':' || p_idem_key, 0));

    -- 키를 넣은 뒤 저널에 들어온 이 키의 NEW 가 있으면 그 주문이 키의 주인이다. 요청 해시가 다르면 다른 요청이 넣은
    -- 행이라 지우지 않는다.
    DELETE FROM idempotency_keys k
    WHERE k.user_id = p_user_id
      AND k.idem_key = p_idem_key
      AND k.request_hash = p_request_hash
      AND NOT EXISTS (SELECT 1 FROM command_journal c
                      WHERE c.command_type = 'NEW'
                        AND c.user_id = p_user_id
                        AND c.client_order_id = p_idem_key
                        AND c.created_at >= k.created_at);
    RETURN FOUND;
END $$;

COMMENT ON FUNCTION discard_orphan_idempotency_key(BIGINT, VARCHAR, VARCHAR) IS
'접수 실패 뒤 고아 멱등 키를, 키를 넣은 뒤 저널에 들어온 그 키의 NEW 가 없을 때만 지운다. append 머리말과 같은 어드바이저리 락 아래에서 판정한다(V47). 지웠으면 참이다.';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_role') THEN
        GRANT EXECUTE ON FUNCTION discard_orphan_idempotency_key(BIGINT, VARCHAR, VARCHAR) TO app_role;
    END IF;
END $$;
