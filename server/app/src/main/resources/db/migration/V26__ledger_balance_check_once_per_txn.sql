-- 원장 커밋 검증의 N+1 제거.
--
-- trg_ledger_committed 는 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW 라 커밋 시점에
-- 삽입된 분개 행 수만큼 발화한다. 그 함수가 매번 같은 거래의 분개 전체를 다시 스캔하므로
-- 분개 N 건이면 스캔 N 회이고 각 스캔이 N 행을 읽는다. O(N^2) 다.
--
-- 2026-09-04 실측(pg_stat_statements, 90초 창):
--   SELECT count(*) FROM (SELECT asset_id FROM ledger_entries WHERE transaction_id = ...)
--   호출 13781 회. 전체 쿼리 호출의 4.4% 로 상위 4위다.
--
-- 검증 내용은 바꾸지 않는다. 봉인 확인도, 자산별 합 0 확인도 그대로다. 바꾸는 것은
-- 같은 거래에 대해 두 번째 이후의 반복을 건너뛰는 것뿐이다. 트랜잭션 지역 GUC 를 메모로
-- 쓴다. set_config 의 세 번째 인자 true 가 트랜잭션 지역이라 커밋·롤백에서 자동 소멸한다.
--
-- 왜 FOR EACH STATEMENT 로 바꾸지 않는가. 이 트리거의 목적은 post_ledger_transaction 을
-- 우회한 삽입을 잡는 2차 방어다(함수는 자체 검증을 이미 한다). 우회 경로가 분개를 여러
-- 문장에 나눠 넣을 수 있으므로 문장 단위로는 중간 상태를 불균형으로 오판한다. 커밋 시점
-- 판정이라는 성질을 유지해야 하고, 제약 트리거는 FOR EACH STATEMENT 를 지원하지 않는다.
--
-- 왜 봉인 확인까지 건너뛰지 않는가. 그것은 단일 행 조회라 비용이 작고, 행마다 확인해도
-- 같은 결과다. 메모 뒤에 두어도 되지만 검증 강도를 낮추지 않으려고 스캔만 건너뛴다.

CREATE OR REPLACE FUNCTION assert_txn_sealed_balanced() RETURNS TRIGGER AS $$
DECLARE st TEXT; bad INT; memo TEXT; memo_key TEXT;
BEGIN
    SELECT status INTO st FROM ledger_transactions WHERE id = NEW.transaction_id;
    IF st <> 'SEALED' THEN
        RAISE EXCEPTION '커밋 시점 미봉인 txn=%: post_ledger_transaction 경유 누락', NEW.transaction_id;
    END IF;

    -- 같은 거래를 이미 검사했으면 스캔을 건너뛴다. 검사 결과가 행마다 같기 때문이다.
    memo_key := 'failstop.ledger_balanced_' || NEW.transaction_id::TEXT;
    memo := current_setting(memo_key, true);
    IF memo = '1' THEN
        RETURN NULL;
    END IF;

    SELECT count(*) INTO bad FROM (
        SELECT asset_id FROM ledger_entries WHERE transaction_id = NEW.transaction_id
        GROUP BY asset_id HAVING SUM(amount) <> 0
    ) t;
    IF bad > 0 THEN
        RAISE EXCEPTION '커밋 시점 불균형 txn=%', NEW.transaction_id;
    END IF;

    PERFORM set_config(memo_key, '1', true);
    RETURN NULL;
END $$ LANGUAGE plpgsql;

COMMENT ON FUNCTION assert_txn_sealed_balanced() IS
'원장 커밋 검증. 봉인 여부는 분개마다, 자산별 합 0 은 거래마다 한 번 확인한다(V26 N+1 제거).';
