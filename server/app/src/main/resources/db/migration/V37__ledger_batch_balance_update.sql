-- 원장 게시의 잔고 갱신을 한 문장으로 묶는다.
--
-- 왜 하는가. 정본 5.7.5 가 다음 상한을 왕복 횟수로 특정했다. 명령 하나의 직렬 시간이
-- 21.36밀리초인데 매칭이 0.02 이고 행 잠금 대기가 3.8퍼센트뿐이라 경합이 아니었다.
-- 남은 설명이 문장 수였다.
--
-- 이 함수의 몫을 실측했다(2026-09-10, 12마켓 240/s, 정본 5.7.7).
--
--   ledger_transactions 삽입 1.133 / 명령    곧 이 함수 호출이 명령당 1.133회
--   balances            갱신 2.783 / 명령    곧 호출 하나에 계좌가 평균 2.46개
--
-- 그 2.46개를 FOR 루프가 계좌마다 UPDATE 한 문장씩 보냈다. 오름차순 잠금 한 문장과
-- 일괄 UPDATE 한 문장으로 묶으면 호출당 2.46 에서 2 로, 명령당으로는 약 0.5 문장 준다.
-- 큰 몫은 아니다. 이 판의 큰 몫은 같은 회차의 정산 머리말 병합(왕복 셋을 하나로)이고
-- 둘을 합쳐 명령당 직렬 시간이 21.83 에서 19.72밀리초로 줄었다.
--
-- 기준은 V27 이다. 이 함수는 V1 이후 V13 과 V27 에서 두 번 개정됐다. V27 이 입력에서
-- 직접 검증하도록 재조회 세 건을 없앴고 V13 이 원가 프로젝션 호출을 넣었다. 처음에
-- V1 본문을 베껴 오는 실수를 했고 그 결과 apply_position_cost 호출이 사라져 원가
-- 프로젝션 시험 열두 건이 깨졌다. 최신 본문 위에서 이 루프만 바꾼다.
--
-- 바꾸지 않는 것. 검증 내용, 헤더 FOR UPDATE, 원가 프로젝션 호출, 트랜잭션 경계, 스키마다.
--
-- 바뀌는 것 하나를 명시한다. 예외 메시지 여섯 중 다섯은 글자까지 같고 'balances 행 없음'
-- 하나만 계좌 지목에서 개수 대조로 바뀐다. 루프가 사라져 어느 계좌인지 그 자리에서 알 수
-- 없기 때문이다. 진단이 그만큼 나빠지므로 아래 예외 분기에서 빠진 계좌를 다시 뽑아 찍는다.
-- 발생 순서도 하나 바뀐다. 종전에는 작은 계좌부터 하나씩 갱신하다 없는 계좌를 만나면 그
-- 자리에서 멈췄고, 지금은 전부 갱신한 뒤 개수를 본다. 곧 다른 계좌의 잔고 음수 위반이
-- 먼저 터질 수 있다. 둘 다 같은 트랜잭션을 롤백시키므로 결과는 같다.

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
    -- 따라서 이 문장의 훑는 순서는 교착과 무관하다.
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
