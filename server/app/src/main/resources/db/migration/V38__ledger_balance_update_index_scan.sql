-- 원장 게시의 일괄 잔고 갱신이 기본키 인덱스를 타게 한다.
--
-- 왜 하는가. V37 이 계좌별 UPDATE 루프를 잠금 한 문장과 일괄 UPDATE 한 문장으로 묶었다.
-- 그 두 문장의 실행 계획을 2026-09-11 에 확인하니 갱신 쪽이 balances 를 순차 스캔했다.
--
--   Update on balances b
--     -> Hash Join
--          -> Seq Scan on balances b
--          -> CTE Scan on s
--
-- 원인은 jsonb_to_recordset 이다. 집합 함수라 통계가 없어 계획기가 항상 100행으로 본다.
-- 실제 계좌는 두셋이다. 바깥이 100행이면 해시 조인에 순차 스캔이 최저 비용으로 뽑힌다.
-- 곧 게시 하나가 balances 크기에 비례해 느려진다. 계좌 수에 비례해야 맞다.
--
-- 같은 이유로 V37 의 잠금 문장은 이미 = ANY(배열) 형태다. 그쪽은 인덱스를 탄다.
-- 갱신 문장에만 같은 조건을 한 번 더 걸어 범위를 좁힌다. 확인한 계획이다.
--
--   Update on balances b
--     -> Hash Join
--          -> CTE Scan on s
--          -> Index Scan using balances_pkey on balances b
--              Index Cond: (account_id = ANY ((InitPlan 2).col1))
--
-- 왜 V37 을 고치지 않는가. 이미 적용된 마이그레이션이라 Flyway 체크섬이 깨진다.
--
-- 바꾸지 않는 것. 검증 내용, 예외 메시지, 발생 순서, 잠금 순서, 헤더 FOR UPDATE,
-- 원가 프로젝션 호출, 트랜잭션 경계, 스키마다. 조건 하나가 더 붙을 뿐이고 그 조건은
-- 바로 위 줄의 조인 조건과 같은 집합을 가리킨다.

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
    v_accounts INT;
    v_updated  INT;
    v_missing  TEXT;
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

    -- 잔고 갱신. 종전에는 계좌마다 UPDATE 를 한 번씩 돌았다. 원장 거래 하나에 계좌가
    -- 평균 2.46개라 그만큼 문장을 보냈다. 두 문장으로 묶는다.
    --
    -- (1) 잠글 행을 account_id 오름차순으로 먼저 잡는다. 잠금 순서 규약을 여기서 지킨다.
    -- 왜 갱신 문장에 맡기지 않는가. UPDATE ... FROM 은 조인 대상을 어느 순서로 훑을지
    -- 계획기가 정하므로 서브쿼리에 ORDER BY 를 써도 잠금 순서가 보장되지 않는다. 반면
    -- LockRows 는 정렬 위에 놓이므로 ORDER BY 를 단 SELECT ... FOR ... 는 그 순서로 잠근다.
    --
    -- 이 규약이 지금 실제로 필요하다. ADR 0021 로 마켓별 라이터가 열둘 동시에 돌고,
    -- 수수료 수입 계정은 같은 정산화폐를 쓰는 모든 마켓이 함께 건드린다. 계좌 집합이
    -- 겹치는 동시 게시가 상시로 일어나므로 순서가 어긋나면 교착이 난다.
    --
    -- 잠금 세기는 종전과 같은 FOR NO KEY UPDATE 다. UPDATE 가 키가 아닌 열만 바꿀 때
    -- 잡는 것과 같은 세기라, 외래키 검사와의 충돌 양상이 바뀌지 않는다.
    -- IN (서브질의) 가 아니라 = ANY(배열) 로 쓴다. jsonb_to_recordset 은 통계가 없어
    -- 계획기가 항상 100행으로 본다. 실제 계좌는 서너 개다. IN 은 세미조인으로 풀려서
    -- 바깥이 100행이면 balances 를 통째로 훑는 계획이 뽑힐 수 있다. 배열 비교는 기본키
    -- 인덱스를 타므로 계좌 수에 비례한다. 잠금 순서는 어느 쪽이든 정렬 위의 잠금 노드가
    -- 정하므로 바뀌지 않는다.
    PERFORM 1
    FROM balances b
    WHERE b.account_id = ANY (ARRAY(
        SELECT DISTINCT e.account_id
        FROM jsonb_to_recordset(p_entries)
             AS e(account_id BIGINT, asset_id SMALLINT, amount NUMERIC, entry_type VARCHAR)
    ))
    ORDER BY b.account_id
    FOR NO KEY UPDATE;

    -- (2) 갱신을 한 문장으로 묶는다. 위에서 이미 잠갔으므로 여기서 새로 잡는 잠금이 없고
    -- 따라서 이 문장의 훑는 순서는 교착과 무관하다. 다만 훑는 범위는 상관있다.
    -- balances CHECK(>=0)가 이중지출·과다락을 커밋 전에 차단하는 것은 그대로다.
    WITH s AS (
        SELECT e.account_id, SUM(e.amount::NUMERIC(40,18)) AS delta
        FROM jsonb_to_recordset(p_entries)
             AS e(account_id BIGINT, asset_id SMALLINT, amount NUMERIC, entry_type VARCHAR)
        GROUP BY e.account_id
    ), u AS (
        UPDATE balances b
        SET balance = b.balance + s.delta, version = b.version + 1
        FROM s
        WHERE b.account_id = s.account_id
          -- 같은 뜻의 조건을 배열 형태로 한 번 더 건다. 조인만 두면 계획기가 s 를 100행으로
          -- 보고 balances 를 통째로 훑는다. 이 조건이 있어야 기본키 인덱스를 탄다.
          AND b.account_id = ANY (ARRAY(SELECT account_id FROM s))
        RETURNING 1
    )
    SELECT (SELECT count(*) FROM s), (SELECT count(*) FROM u)
    INTO v_accounts, v_updated;

    -- 종전 루프의 NOT FOUND 를 대신한다. 계좌 행이 없는데 조용히 넘어가면 잔고가 반영되지
    -- 않은 채 봉인되고 원장과 잔고가 갈린다.
    --
    -- 빠진 계좌를 여기서 다시 뽑는다. 루프판은 걸린 계좌를 바로 찍었는데 개수만 남기면
    -- 운영자가 입력과 balances 를 손으로 대조해야 한다. 이 질의는 예외 경로에서만 돌므로
    -- 정상 경로의 왕복을 늘리지 않는다.
    IF v_updated <> v_accounts THEN
        SELECT string_agg(DISTINCT e.account_id::TEXT, ',' ORDER BY e.account_id::TEXT)
        INTO v_missing
        FROM jsonb_to_recordset(p_entries)
             AS e(account_id BIGINT, asset_id SMALLINT, amount NUMERIC, entry_type VARCHAR)
        WHERE NOT EXISTS (SELECT 1 FROM balances b WHERE b.account_id = e.account_id);
        RAISE EXCEPTION 'balances 행 없음: account=% (기대 % 실제 %, txn=%)',
            COALESCE(v_missing, '(불명)'), v_accounts, v_updated, v_txn;
    END IF;

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
'원장 거래 게시. 검증을 입력 JSONB 에서 계산하고(V27 재조회 제거), 잔고를 account_id 오름차순 잠금 뒤 한 문장으로 갱신한다(V37 루프 제거).';
