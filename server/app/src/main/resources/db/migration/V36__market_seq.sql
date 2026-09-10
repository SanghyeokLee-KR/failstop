-- 마켓별 순서 축 도입. 전역 순서 원장을 마켓별 순서 원장으로 옮기는 1단계다.
--
-- 왜 하는가. 2026-09-10 실측에서 정산 트랜잭션이 event_seq 를 journal_counters 한 행에서
-- 채번하고 그 UPDATE 행 잠금을 커밋까지 쥐는 것이 확인됐다. 채번이 원장 게시 루프보다
-- 앞에 있어 정산 전 구간이 그 한 행에서 직렬화된다. 일회용 컨테이너에 같은 표와 함수를
-- 복제해 라이터 N개로 재니 12개에서 초당 43.5 였다. 마켓별 카운터로 가르니 514.5 였다.
-- 곧 마켓별 라이터를 두어도 이 행을 그대로 두면 처리량이 1.10 배에 그친다.
--
-- 무엇을 하지 않는가. 이 마이그레이션은 되돌릴 수 없는 변경을 하나도 하지 않는다.
--
--   - event_seq 컬럼과 그 제약을 건드리지 않는다. event_journal 의 PK(event_seq),
--     event_emission_registry 의 event_seq UNIQUE, trade_seq_registry 의 seq PK,
--     trades.id 는 전부 그대로다. event_seq 는 계속 전역 유일이다.
--   - 기존 행을 갱신하지 않는다. event_journal 에 append-only 트리거(trg_ej_noupd)가
--     걸려 있어 백필 UPDATE 는 애초에 막힌다. 새 컬럼은 nullable 이고 신규 행만 채운다.
--   - next_journal_value 를 건드리지 않는다. command_seq 와 order_id 가 계속 쓴다.
--     그 함수의 WHERE 절이 name 단독이라, journal_counters 에 마켓 행을 더하면
--     한 번의 호출이 여러 행을 UPDATE 하게 된다. 그래서 카운터를 아예 다른 표에 둔다.
--
-- 역할 분리가 이 판의 요점이다. 종전에는 event_seq 하나가 네 일을 겸했다. 전역 유일
-- 식별자, 명령 순서, gapless 불변식 대상, 파티션 키다. 이제 둘로 가른다.
--
--   event_seq   전역 유일 식별자.   하류 계약(trades.id, 외부 trade_id)이 그대로 쓴다.
--   market_seq  마켓 안의 순서 번호. 재생과 체크포인트와 스냅샷이 이 축을 쓴다.
--
-- 마켓 안에는 라이터가 여전히 하나이므로 "market_seq 가 명령 순서대로 결번 없이
-- 증가한다" 가 성립한다. 재생 대조가 요구하는 것이 정확히 그것이고, 전역 순서가
-- 아니다. 그래서 축만 바꾸면 되고 새 알고리즘이 필요하지 않다.

-- ── 마켓별 카운터 ────────────────────────────────────────────────────
-- journal_counters 와 따로 두는 이유는 위에 적었다. next_journal_value 의 WHERE 가
-- name 단독이라 같은 표에 마켓 행을 더하면 기존 채번이 조용히 망가진다.
-- markets 로의 외래키를 걸지 않는다. command_journal.market_id 와 같은 판단이다.
-- 저널이 마켓 행의 실재를 요구하지 않는데 그 저널의 채번이 더 엄격하면 저널을 쓸 수 있는
-- 상태에서 채번이 막힌다. 실제로 통합 시험 여럿이 markets 를 비운 채 주문을 넣는다.
CREATE TABLE market_journal_counters (
    market_id  SMALLINT NOT NULL,
    name       TEXT     NOT NULL,
    next_value BIGINT   NOT NULL DEFAULT 1 CHECK (next_value >= 1),
    PRIMARY KEY (market_id, name)
);
COMMENT ON TABLE market_journal_counters IS
    '마켓별 채번 카운터. 전역 journal_counters 와 분리한다. 라이터가 마켓마다 자기 행만 잡으므로 마켓 사이 경합이 없다.';

-- 마켓별 채번. next_journal_value 와 본문이 같고 WHERE 에 market_id 가 더 붙는다.
-- 잠금은 여전히 커밋까지 유지되지만 잡는 행이 마켓마다 다르므로 서로 기다리지 않는다.
CREATE OR REPLACE FUNCTION next_market_journal_value(
    p_market_id SMALLINT,
    p_name      TEXT,
    p_count     BIGINT DEFAULT 1
) RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE v_start BIGINT;
BEGIN
    IF p_count < 1 THEN RAISE EXCEPTION '채번 개수는 1 이상: %', p_count; END IF;
    -- 없으면 만들고 있으면 올린다. 한 문장이라 원자적이고 경합에서도 한 줄만 남는다.
    -- 종전 판은 UPDATE 만 하고 없으면 예외를 던졌다. 그러면 마켓 행 없이 도는 경로가
    -- 전부 막힌다. 저널이 마켓 실재를 요구하지 않으므로 채번도 요구하지 않는다.
    INSERT INTO market_journal_counters (market_id, name, next_value)
    VALUES (p_market_id, p_name, 1 + p_count)
    ON CONFLICT (market_id, name) DO UPDATE
        SET next_value = market_journal_counters.next_value + p_count
    RETURNING next_value - p_count INTO v_start;
    -- 대역 상한. event_seq 를 market_id * 10^7 + market_seq 로 파생하므로 지역 번호가
    -- 대역을 넘으면 다음 마켓 대역을 침범해 전역 유일성이 깨진다. 조용히 넘기지 않는다.
    IF v_start + p_count > 10000000 THEN
        RAISE EXCEPTION '마켓 순서 대역 초과: market=% start=% count=%. 대역을 늘리거나 저널을 잘라야 한다',
            p_market_id, v_start, p_count;
    END IF;
    RETURN v_start;
END $$;

REVOKE ALL ON FUNCTION next_market_journal_value(SMALLINT, TEXT, BIGINT) FROM PUBLIC;

-- 마켓이 생기면 카운터도 미리 만든다. 채번 함수가 없으면 만들어 주므로 필수는 아니고,
-- 카운터 표를 조회했을 때 마켓이 전부 보이게 하려는 것이다. 불변식 C23 이 이 표를
-- 마켓별 최대 market_seq 와 대조하므로 행이 미리 있으면 대조가 마켓 전체를 덮는다.
CREATE OR REPLACE FUNCTION seed_market_journal_counters() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
    INSERT INTO market_journal_counters (market_id, name, next_value)
    VALUES (NEW.id, 'event_seq', 1)
    ON CONFLICT (market_id, name) DO NOTHING;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_markets_seed_counters
    AFTER INSERT ON markets
    FOR EACH ROW EXECUTE FUNCTION seed_market_journal_counters();

-- 이미 있는 마켓용 시드. 앞으로 생길 마켓은 위 트리거가 맡는다.
INSERT INTO market_journal_counters (market_id, name, next_value)
SELECT id, 'event_seq', 1 FROM markets
ON CONFLICT (market_id, name) DO NOTHING;

-- ── 이벤트 저널의 마켓 축 ────────────────────────────────────────────
-- 둘 다 nullable 이다. 백필하지 않는다. 이 마이그레이션 이전 행은 market_seq 가 비고,
-- 그 행들은 전역 축으로만 해석한다. 신규 행만 두 축을 함께 갖는다.
ALTER TABLE event_journal ADD COLUMN market_id  SMALLINT;
ALTER TABLE event_journal ADD COLUMN market_seq BIGINT;

COMMENT ON COLUMN event_journal.market_seq IS
    '마켓 안의 순서 번호. 재생과 체크포인트의 축이다. V36 이전 행은 비어 있다.';

-- 마켓별 재생 읽기 경로가 이 인덱스 하나로 끝나도록 market_id 를 앞에 둔다.
CREATE INDEX idx_evt_market_seq ON event_journal (market_id, market_seq)
    WHERE market_seq IS NOT NULL;

-- ── 권한 ─────────────────────────────────────────────────────────────
-- V1 의 권한 블록과 같은 모양이다. 역할이 없는 환경(테스트컨테이너)에서도 도는 조건부다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_role') THEN
        -- 채번은 이 표의 UPDATE 로 이뤄진다. journal_counters 와 같은 권한 모양이다.
        -- INSERT 가 필요하다. 채번 함수가 없으면 만들기 때문이다.
        GRANT SELECT, INSERT, UPDATE ON market_journal_counters TO app_role;
        GRANT EXECUTE ON FUNCTION next_market_journal_value(SMALLINT, TEXT, BIGINT) TO app_role;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'harness_role') THEN
        GRANT SELECT ON market_journal_counters TO harness_role;
    END IF;
END $$;
