-- 소유자 권한 트리거 함수 넷의 PUBLIC 기본 EXECUTE 를 회수한다(DB 설계 §3.1 V47.1).
--
-- 왜 하는가. PostgreSQL 은 트리거 함수의 EXECUTE 를 CREATE TRIGGER 때만 보고 TEMP 권한은 기본으로 PUBLIC 에 있다.
-- 대상 표에 INSERT 권한이 없는 로그인도 자기 임시 표에 이 함수를 걸면 소유자 권한으로 레지스트리·카운터 행과 저널 잎이 생긴다.
-- 트리거 발화는 EXECUTE 를 보지 않으므로 계정 생성, 체결·이벤트 적재, 마켓 생성은 그대로다. app_role 에도 주지 않는다.
--
-- 버전 47.1 은 V47 과 V48 사이에 정렬된다(DB 설계 v2.51.1).

REVOKE ALL ON FUNCTION create_balance_for_account() FROM PUBLIC;
REVOKE ALL ON FUNCTION register_trade_seq() FROM PUBLIC;
REVOKE ALL ON FUNCTION register_event_emission() FROM PUBLIC;
REVOKE ALL ON FUNCTION seed_market_journal_counters() FROM PUBLIC;

-- 함수에 권한은 있지만 소유자가 아닌 역할의 REVOKE 는 경고만 내고 아무것도 바꾸지 않는다. 그 상태로 기록되지 않게 멈춘다.
DO $$
DECLARE
    v_open TEXT;
BEGIN
    SELECT string_agg(f::TEXT, ', ') INTO v_open
    FROM unnest(ARRAY['create_balance_for_account()'::regprocedure,
                      'register_trade_seq()'::regprocedure,
                      'register_event_emission()'::regprocedure,
                      'seed_market_journal_counters()'::regprocedure]) AS f
    WHERE has_function_privilege('public', f::oid, 'EXECUTE');
    IF v_open IS NOT NULL THEN
        RAISE EXCEPTION 'PUBLIC EXECUTE 회수 실패: %. 함수 소유자로 적용한다', v_open;
    END IF;
END $$;
