-- 원장 게시 경로의 재조회 제거.
--
-- post_ledger_transaction 은 ledger_entries 에 분개를 넣은 뒤 같은 거래의 행을 세 번 다시
-- 읽는다. 양자화 검증, 잔고 델타 집계, 자산별 합 0 검증이다. 세 값 모두 입력 p_entries 에
-- 이미 있고, INSERT 는 jsonb_to_recordset 결과를 그대로 넣는 단순 통과라 테이블을 다시 읽을
-- 이유가 없다.
--
-- 2026-09-06 실측(pg_stat_statements, 주문 9619 건):
--   양자화 검증    호출 9761, 950.8ms
--   잔고 델타 집계 호출 9761, 620.4ms
--   자산별 합 0    호출 9761, 578.3ms
--
-- 검증 내용은 하나도 바꾸지 않는다. 읽는 곳만 테이블에서 입력으로 옮긴다. 예외 메시지와
-- 발생 순서도 그대로다.
--
-- 캐스팅이 필요한 이유. ledger_entries.amount 는 NUMERIC(40,18) 이라 INSERT 가 18 자리로
-- 반올림한다. 입력 원값으로 검사하면 소수 19 자리 이상 입력에서 판정이 달라진다. ETH 는
-- scale 이 18 이므로 실제로 도달 가능한 경계다. 그래서 검사 전에 같은 캐스팅을 걸어
-- 저장값과 동일한 값을 본다.
--
-- INSERT 를 먼저 두는 순서도 그대로다. NOT NULL, CHECK (amount <> 0), 외래키, 봉인 가드
-- 트리거가 종전과 같은 시점에 같은 순서로 발화해야 하기 때문이다.
--
-- 이번 회차에서 바꾸지 않는 것. 헤더 FOR UPDATE, 외래키, 원가 프로젝션, 트랜잭션 경계,
-- 스키마. apply_position_cost 의 드라이빙 쿼리도 그대로 둔다(호출 9761, 1805.0ms).

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
    PERFORM apply_position_cost(v_txn);

    RETURN v_txn;
END $$;

COMMENT ON FUNCTION post_ledger_transaction(VARCHAR, BIGINT, JSONB) IS
'원장 거래 게시. 양자화·잔고 델타·자산별 합 0 검증을 입력 JSONB 에서 계산한다(V27 재조회 제거).';
