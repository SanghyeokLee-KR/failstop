-- 소유자 권한(SECURITY DEFINER) 함수 열하나의 search_path 끝에 pg_temp 를 명시한다(DB 설계 §3.1 V47.2).
--
-- 왜 하는가. search_path 에 pg_temp 가 없으면 PostgreSQL 은 세션의 임시 스키마에서 표 이름을 먼저 찾는다.
-- 함수를 부르거나 트리거를 발화시키는 로그인이 같은 이름의 임시 표를 만들면 이 함수들이 소유자 권한으로 그 표를 읽고 쓴다.
-- 끝에 두면 임시 스키마는 pg_catalog 와 public 다음에만 찾는다. 함수 본문, EXECUTE 권한, 트리거는 바꾸지 않는다.
--
-- 버전 47.2 는 V47.1 과 V48 사이에 정렬된다(DB 설계 v2.51.2).

ALTER FUNCTION create_balance_for_account() SET search_path = public, pg_temp;
ALTER FUNCTION register_trade_seq() SET search_path = public, pg_temp;
ALTER FUNCTION register_event_emission() SET search_path = public, pg_temp;
ALTER FUNCTION post_ledger_transaction(VARCHAR, BIGINT, JSONB) SET search_path = public, pg_temp;
ALTER FUNCTION reserve_funds(BIGINT, BIGINT, BIGINT, SMALLINT, NUMERIC) SET search_path = public, pg_temp;
ALTER FUNCTION release_funds(BIGINT, BIGINT, BIGINT, SMALLINT, NUMERIC) SET search_path = public, pg_temp;
ALTER FUNCTION apply_position_cost(BIGINT) SET search_path = public, pg_temp;
ALTER FUNCTION rebuild_position_cost(BIGINT) SET search_path = public, pg_temp;
ALTER FUNCTION ensure_command_band_partition(SMALLINT) SET search_path = public, pg_temp;
ALTER FUNCTION ensure_event_band_partition(SMALLINT) SET search_path = public, pg_temp;
ALTER FUNCTION seed_market_journal_counters() SET search_path = public, pg_temp;

-- 위 목록에서 빠진 소유자 권한 함수가 있으면 그 상태로 기록되지 않게 멈춘다.
DO $$
DECLARE
    v_open TEXT;
BEGIN
    SELECT string_agg(oid::regprocedure::TEXT, ', ') INTO v_open
    FROM pg_proc
    WHERE prosecdef AND pronamespace = 'public'::regnamespace
      AND NOT coalesce('search_path=public, pg_temp' = ANY (proconfig), false);
    IF v_open IS NOT NULL THEN
        RAISE EXCEPTION 'search_path 에 pg_temp 가 끝에 없는 소유자 권한 함수: %', v_open;
    END IF;
END $$;
