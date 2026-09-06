-- 견적 자산 전용 거래의 원가 프로젝션 호출 제거.
--
-- apply_position_cost 는 호출의 99.2% 가 아무 행도 만들지 않는다(2026-09-06 실측: 호출
-- 13,099 중 루프 본문 102). 비용은 거의 전부 드라이빙 쿼리다. 직렬 벤치마크 3,000회에서
-- 함수 전체가 회당 0.5192ms, 그 드라이빙 쿼리만이 0.4794ms 로 92% 다. 빈 함수 호출은
-- 0.0010ms 라 호출 오버헤드는 문제가 아니다.
--
-- 그래서 SQL 안에서 거르는 대신 부르지 않는다. V28 초안은 함수 앞에 EXISTS 사전 검사를
-- 두었으나 HAVING 이 붙은 EXISTS 는 그룹핑을 끝내야 판정되어 조기 종료가 성립하지 않았다.
-- 회당 0.3221ms 를 더 쓰고 TPS 가 120.8 에서 106.3 으로 내려가 폐기했다.
--
-- 이 판정은 accounts 를 보지 않는다. 입력 JSONB 의 asset_id 와 cost_quote_assets 만 본다.
-- 드라이빙 쿼리의 최종 필터가 비견적 자산만 남기므로, 분개가 전부 견적 자산이면 결과가
-- 반드시 비어 사전 판정과 함수 판정이 일치한다.
--
-- 걸러지지 않는 나머지는 비견적 자산이 낀 RESERVE 다. 같은 사용자의 두 계정 사이 이동이라
-- 합이 0 이지만, reserve_funds 가 두 계정의 소유자 일치를 검사하지 않아 자산만으로는 그것을
-- 증명할 수 없다. 그 절반은 소유자 보장을 먼저 세워야 걷을 수 있다.
--
-- 본문의 나머지는 V27 과 같다.

CREATE OR REPLACE FUNCTION post_ledger_transaction(
    p_business_event_type VARCHAR,
    p_business_event_id   BIGINT,
    p_entries             JSONB
) RETURNS BIGINT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    v_txn      BIGINT;
    v_expected INT;
    v_inserted INT;
    r          RECORD;
BEGIN
    IF p_entries IS NULL OR jsonb_typeof(p_entries) <> 'array'
       OR jsonb_array_length(p_entries) = 0 THEN
        RAISE EXCEPTION '빈 거래 게시 금지: entries가 비어 있음(event=% %)',
            p_business_event_type, p_business_event_id;
    END IF;
    v_expected := jsonb_array_length(p_entries);

    -- 멱등 키 UNIQUE가 이중 게시를 23505로 차단한다.
    INSERT INTO ledger_transactions (business_event_type, business_event_id)
    VALUES (p_business_event_type, p_business_event_id)
    RETURNING id INTO v_txn;

    -- 헤더 잠금. 자체 생성 행이라 이미 배타적이지만 게시 경로의 잠금 패턴을 고정한다.
    PERFORM 1 FROM ledger_transactions WHERE id = v_txn FOR UPDATE;

    INSERT INTO ledger_entries (transaction_id, account_id, asset_id, amount, entry_type)
    SELECT v_txn, e.account_id, e.asset_id, e.amount, e.entry_type
    FROM jsonb_to_recordset(p_entries)
         AS e(account_id BIGINT, asset_id SMALLINT, amount NUMERIC, entry_type VARCHAR);
    GET DIAGNOSTICS v_inserted = ROW_COUNT;
    IF v_inserted <> v_expected THEN
        RAISE EXCEPTION 'entry 수 불일치: 기대 % 실제 % (txn=%)', v_expected, v_inserted, v_txn;
    END IF;

    -- 양자화 검증(P0-3): 자산 scale을 넘는 소수는 게시 자체를 거부한다.
    IF EXISTS (
        SELECT 1
        FROM jsonb_to_recordset(p_entries)
             AS e(account_id BIGINT, asset_id SMALLINT, amount NUMERIC, entry_type VARCHAR)
        JOIN assets a ON a.id = e.asset_id
        WHERE e.amount::NUMERIC(40,18) <> round(e.amount::NUMERIC(40,18), a.scale::int)
    ) THEN
        RAISE EXCEPTION '양자화 위반: 자산 scale 초과 소수 존재(txn=%)', v_txn;
    END IF;

    -- 잔고 갱신. account_id 오름차순 UPDATE로 행 배타 잠금을 항상 같은 순서로 획득한다.
    -- balances CHECK(>=0)가 이중지출/과다락을 커밋 전에 차단한다.
    FOR r IN
        SELECT e.account_id, SUM(e.amount::NUMERIC(40,18)) AS delta
        FROM jsonb_to_recordset(p_entries)
             AS e(account_id BIGINT, asset_id SMALLINT, amount NUMERIC, entry_type VARCHAR)
        GROUP BY e.account_id
        ORDER BY e.account_id
    LOOP
        UPDATE balances
        SET balance = balance + r.delta, version = version + 1
        WHERE account_id = r.account_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'balances 행 없음: account=% (txn=%)', r.account_id, v_txn;
        END IF;
    END LOOP;

    -- 자산별 합=0 최종 검증(자산 보존)
    IF EXISTS (
        SELECT 1
        FROM jsonb_to_recordset(p_entries)
             AS e(account_id BIGINT, asset_id SMALLINT, amount NUMERIC, entry_type VARCHAR)
        GROUP BY e.asset_id HAVING SUM(e.amount::NUMERIC(40,18)) <> 0
    ) THEN
        RAISE EXCEPTION '원장 불균형: txn=% 자산별 합이 0이 아님(자산 보존 위반)', v_txn;
    END IF;

    -- clock_timestamp(): 장기 트랜잭션에서도 실제 봉인 시각을 기록한다.
    UPDATE ledger_transactions
    SET status = 'SEALED', sealed_at = clock_timestamp()
    WHERE id = v_txn AND status = 'OPEN';
    IF NOT FOUND THEN
        RAISE EXCEPTION '봉인 실패: txn=% OPEN 상태 아님', v_txn;
    END IF;

    -- 원가 프로젝션(V13). 봉인 뒤라야 분개 집합이 확정된다. 입력 계열 실패는 게시 결과를
    -- 바꾸지 않는다. 잠금·취소·데드락은 올라와 이 게시를 롤백시킨다(no-RAISE 계약의 범위).
    --
    -- 견적 자산만 오간 거래는 부르지 않는다. apply_position_cost 의 드라이빙 쿼리가 마지막에
    -- 비견적 자산만 남기므로, 분개가 전부 견적 자산이면 그 결과는 반드시 비어 있다. 곧 이
    -- 판정은 함수의 결과를 바꾸지 않고 무효 호출만 걷어낸다.
    IF EXISTS (
        SELECT 1
        FROM jsonb_to_recordset(p_entries)
             AS e(account_id BIGINT, asset_id SMALLINT, amount NUMERIC, entry_type VARCHAR)
        WHERE NOT EXISTS (SELECT 1 FROM cost_quote_assets q WHERE q.asset_id = e.asset_id)
    ) THEN
        PERFORM apply_position_cost(v_txn);
    END IF;

    RETURN v_txn;
END $$;
