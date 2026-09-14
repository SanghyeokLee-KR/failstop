-- 이벤트 저널 대역 확대. ADR 0022 9단계(3-3)다.
--
-- 왜 하는가. V36 은 event_seq 를 market_id * 10^7 + market_seq 로 앱에서 파생했다. event_journal_p0 경계 2x10^8 안에
-- 들어가려는 폭이라 마켓당 지역 번호가 10^7 에서 끝난다. 명령 축과 같은 폭으로 넓히고 잎을 같은 모양으로 둔다.
--
-- 기존 행은 건드리지 않는다. 레거시 값은 2x10^8 아래이고 대역 값은 10^12 이상이라 겹치지 않는다. market_seq 는 같은
-- 카운터에서 이어지므로 마켓 안 event_seq 가 경계를 넘어 계속 커진다.
--
-- 되돌리기. event_seq >= 10^12 인 행이 없을 때만 한다. 명세를 (10^7, 19) 로, next_market_journal_value 와
-- seed_market_journal_counters 를 V40 본문으로 되돌리고, 비어 있는 이벤트 꼬리·대역 잎과 next_market_event_seq,
-- ensure_event_band_partition 을 지우는 새 마이그레이션을 더한다. 대역 행이 있으면 되돌리지 않는다(ADR 0022 7절 9단계).

-- 잎 생성은 event_journal 부모에 ACCESS EXCLUSIVE 가 필요하고 적용 전 확인은 원장 표를 훑는다. prod 풀의 타임아웃을
-- Flyway 가 상속하면 정산이 도는 DB 에서 취소돼 부팅이 막힌다(V40 과 같은 근거). SET LOCAL 이라 트랜잭션 종료 시 원복된다.
SET LOCAL statement_timeout = 0;
SET LOCAL lock_timeout = 0;

-- ── 적용 전 확인과 명세 ──────────────────────────────────────────────
DO $$
DECLARE
    c_span CONSTANT BIGINT   := 1000000000000;
    c_max  CONSTANT SMALLINT := 63;
    v_span BIGINT;
    v_max  SMALLINT;
    v_rows BIGINT;
BEGIN
    SELECT band_span, max_band_index INTO v_span, v_max FROM journal_band_spec WHERE name = 'event_seq';
    IF v_span IS DISTINCT FROM 10000000 OR v_max IS DISTINCT FROM 19 THEN
        RAISE EXCEPTION '적용 전 확인 1 실패: event_seq 명세가 (10000000, 19) 가 아니다: (%, %)', v_span, v_max;
    END IF;

    -- DEFAULT 에 행이 든 범위는 잎을 만들 수 없다. 10^7 대역에서 마켓 id 20 이상의 이벤트가 여기에 떨어진다.
    SELECT count(*) INTO v_rows FROM event_journal_default;
    IF v_rows > 0 THEN
        RAISE EXCEPTION '적용 전 확인 2 실패: event_journal_default 에 %행이 있다', v_rows;
    END IF;

    -- 정산의 TRADE·RELEASE 는 business_event_id 로 event_seq 를 쓴다. 앞으로 배정될 대역 값과 같은 id 가 이미 있으면
    -- 그 정산이 uq_ledger_business_event 로 롤백되고 재적재가 같은 번호를 다시 내 같은 자리에서 멈춘다.
    SELECT count(*) INTO v_rows
    FROM ledger_transactions t
    WHERE t.business_event_type IN ('TRADE', 'RELEASE')
      AND t.business_event_id >= c_span
      AND t.business_event_id < (c_max + 1) * c_span
      AND t.business_event_id % c_span >= COALESCE(
            (SELECT c.next_value FROM market_journal_counters c
             WHERE c.market_id = t.business_event_id / c_span AND c.name = 'event_seq'), 1);
    IF v_rows > 0 THEN
        RAISE EXCEPTION '적용 전 확인 3 실패: 앞으로 배정될 이벤트 대역 값과 겹치는 TRADE·RELEASE business_event_id %건', v_rows;
    END IF;

    UPDATE journal_band_spec SET band_span = c_span, max_band_index = c_max WHERE name = 'event_seq';
END $$;

-- ── 꼬리 잎 ──────────────────────────────────────────────────────────
-- 이 구간에는 채번이 가지 않는다. 두 저널의 정규 잎 사슬을 같은 경계로 맞춰 2x10^8 에서 첫 대역 잎까지 비지 않게 한다.
CREATE TABLE event_journal_p200000000 PARTITION OF event_journal
    FOR VALUES FROM (200000000) TO (1000000000000);

-- ── 대역 잎 ──────────────────────────────────────────────────────────
-- 마켓이 있는 대역만 잎을 만든다. 마켓별 판독이 파티션 키 술어 없이 잎 전부를 훑어 잎 수가 곧 비용이다(ADR 0022 0단계 (2)).
-- 기존 마켓 시드와 마켓 생성 트리거가 같이 부른다.
CREATE FUNCTION ensure_event_band_partition(p_market_id SMALLINT) RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    v_span BIGINT;
    v_max  SMALLINT;
BEGIN
    SELECT band_span, max_band_index INTO v_span, v_max
    FROM journal_band_spec WHERE name = 'event_seq';
    IF p_market_id < 1 OR p_market_id > v_max THEN
        RAISE EXCEPTION '이벤트 대역 밖 마켓 id: % (허용 1..%)', p_market_id, v_max;
    END IF;
    EXECUTE format('CREATE TABLE IF NOT EXISTS %I PARTITION OF event_journal FOR VALUES FROM (%s) TO (%s)',
                   'event_journal_b' || p_market_id,
                   p_market_id * v_span,
                   (p_market_id + 1) * v_span);
END $$;

REVOKE ALL ON FUNCTION ensure_event_band_partition(SMALLINT) FROM PUBLIC;

-- ── 마켓 생성 트리거 확장 ────────────────────────────────────────────
-- 마켓 행과 두 카운터와 두 저널 대역 잎을 한 트랜잭션에서 만든다. 나머지 본문은 V40 과 같다.
CREATE OR REPLACE FUNCTION seed_market_journal_counters() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
    INSERT INTO market_journal_counters (market_id, name, next_value)
    VALUES (NEW.id, 'event_seq', 1), (NEW.id, 'command_seq', 1)
    ON CONFLICT (market_id, name) DO NOTHING;
    PERFORM ensure_command_band_partition(NEW.id);
    PERFORM ensure_event_band_partition(NEW.id);
    RETURN NEW;
END $$;

-- 이미 있는 마켓용. 앞으로 생길 마켓은 위 트리거가 맡는다.
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN SELECT id FROM markets ORDER BY id LOOP
        PERFORM ensure_event_band_partition(r.id);
    END LOOP;
END $$;

-- ── 채번 ─────────────────────────────────────────────────────────────
-- 앱에서 대역을 곱하던 판을 거부한다. 확대 뒤 그 판이 정산하면 마켓 안 event_seq 가 대역 행 아래로 되돌아간다.
-- 거부는 정산 트랜잭션 안이라 그 노드 라이터가 fail-fast 로 강등된다. 강등 분류 문구를 쓰지 않는다. 나머지 본문은 V40 과 같다.
CREATE OR REPLACE FUNCTION next_market_journal_value(
    p_market_id SMALLINT,
    p_name      TEXT,
    p_count     BIGINT DEFAULT 1
) RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE
    v_start BIGINT;
    v_span  BIGINT;
BEGIN
    IF p_name = 'event_seq' THEN
        RAISE EXCEPTION 'event_seq 는 next_market_event_seq 로 채번한다. 이 호출은 이벤트 대역을 앱에서 파생하는 V46 이전 판이다(ADR 0022 9단계)'
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    IF p_count < 1 THEN RAISE EXCEPTION '채번 개수는 1 이상: %', p_count; END IF;
    SELECT band_span INTO v_span FROM journal_band_spec WHERE name = p_name;
    IF v_span IS NULL THEN
        RAISE EXCEPTION '대역 명세 없는 카운터: %', p_name;
    END IF;
    INSERT INTO market_journal_counters (market_id, name, next_value)
    VALUES (p_market_id, p_name, 1 + p_count)
    ON CONFLICT (market_id, name) DO UPDATE
        SET next_value = market_journal_counters.next_value + p_count
    RETURNING next_value - p_count INTO v_start;
    -- 지역 번호가 대역을 넘으면 다음 마켓 대역을 침범해 전역 유일성이 깨진다.
    IF v_start + p_count > v_span THEN
        RAISE EXCEPTION '마켓 순서 대역 초과: market=% name=% start=% count=%. 대역을 늘리거나 저널을 잘라야 한다',
            p_market_id, p_name, v_start, p_count;
    END IF;
    RETURN v_start;
END $$;

-- 지역 번호 시작과 그 파생값을 함께 돌려준다. 호출자는 방출 순번만 더하고 대역 폭을 모른다.
-- 잎 없는 마켓을 거부하지 않는다. 이 예외는 노드 강등까지 번져 한 마켓의 잎 누락이 전 마켓 정산을 세운다. 그 행은
-- event_journal_default 로 가고 C15 가 본다. 마켓 id 범위 밖은 거부한다. 그 위 값 공간은 업무 이벤트 id 예약이다.
CREATE FUNCTION next_market_event_seq(
    p_market_id SMALLINT,
    p_count     BIGINT DEFAULT 1
) RETURNS TABLE (
    out_market_seq BIGINT,
    out_event_seq  BIGINT
)
LANGUAGE plpgsql AS $$
DECLARE
    v_span  BIGINT;
    v_max   SMALLINT;
    v_start BIGINT;
BEGIN
    IF p_count < 1 THEN RAISE EXCEPTION '채번 개수는 1 이상: %', p_count; END IF;
    SELECT band_span, max_band_index INTO v_span, v_max
    FROM journal_band_spec WHERE name = 'event_seq';
    IF v_span IS NULL THEN
        RAISE EXCEPTION '대역 명세 없는 카운터: event_seq';
    END IF;
    IF p_market_id < 1 OR p_market_id > v_max THEN
        RAISE EXCEPTION '이벤트 대역 밖 마켓 id: % (허용 1..%)', p_market_id, v_max;
    END IF;
    INSERT INTO market_journal_counters (market_id, name, next_value)
    VALUES (p_market_id, 'event_seq', 1 + p_count)
    ON CONFLICT (market_id, name) DO UPDATE
        SET next_value = market_journal_counters.next_value + p_count
    RETURNING next_value - p_count INTO v_start;
    IF v_start + p_count > v_span THEN
        RAISE EXCEPTION '마켓 순서 대역 초과: market=% name=event_seq start=% count=%. 대역을 늘리거나 저널을 잘라야 한다',
            p_market_id, v_start, p_count;
    END IF;
    out_market_seq := v_start;
    out_event_seq  := p_market_id * v_span + v_start;
    RETURN NEXT;
END $$;

COMMENT ON FUNCTION next_market_event_seq(SMALLINT, BIGINT) IS
'이벤트 채번. 마켓 안 번호 시작과 market_id * band_span + 시작을 함께 돌려준다(V46, ADR 0022 9단계).';

REVOKE ALL ON FUNCTION next_market_event_seq(SMALLINT, BIGINT) FROM PUBLIC;

-- ── 권한 ─────────────────────────────────────────────────────────────
-- 정산은 app_role 로 이 함수를 부른다. 호출자 권한으로 돌므로 카운터 쓰기와 명세 읽기는 V36·V40 이 준 권한을 쓴다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_role') THEN
        GRANT EXECUTE ON FUNCTION next_market_event_seq(SMALLINT, BIGINT) TO app_role;
    END IF;
END $$;
