-- 명령 저널 대역 채번의 기반. ADR 0022 1단계다.
--
-- 왜 하는가. 접수 처리량 상한이 전역 접수 락과 전역 command_seq 채번이다(ADR 0022 재개 근거).
-- 락을 마켓별로 가르려면 채번도 마켓별이어야 하고, 그 값이 command_journal 의 PK(seq) 공간에서
-- 겹치지 않도록 마켓마다 대역을 준다. seq = market_id * band_span + 마켓 안 지역 번호다.
--
-- 이 마이그레이션은 채번 경로를 바꾸지 않는다. 앱은 계속 append_command_prologue(V39) 안에서
-- next_journal_value('command_seq') 를 부른다. 여기서 만든 함수와 대역 잎은 6단계 커트오버
-- 전까지 한 번도 쓰이지 않으므로 전부 되돌릴 수 있다.
--
-- 되돌리기. 대역 잎과 꼬리 잎 DROP(비어 있다), next_market_journal_value 와
-- seed_market_journal_counters 를 V36 본문으로, command_seq 시드 행 DELETE, 새 함수 셋과 표 둘 DROP.

-- 꼬리·대역 잎 생성은 command_journal 부모에 ACCESS EXCLUSIVE 가 필요하고 축 경계 확인은 저널 전체를
-- 훑는다. prod 풀의 statement_timeout=3s/lock_timeout=2s 를 Flyway 가 상속하면 정산이 도는 DB 에서
-- 취소돼 부팅이 막힌다(V12 와 같은 근거). SET LOCAL 이라 트랜잭션 종료 시 원복된다.
SET LOCAL statement_timeout = 0;
SET LOCAL lock_timeout = 0;

-- ── 대역 명세 ────────────────────────────────────────────────────────
-- 대역 상수를 코드와 마이그레이션과 감사 세 곳에 복제하지 않기 위한 단일 원천이다.
-- event_seq 행은 V36 이 채번 함수에 박아 둔 값을 그대로 옮긴 것이라 의미가 바뀌지 않는다.
CREATE TABLE journal_band_spec (
    name           TEXT     PRIMARY KEY,
    band_span      BIGINT   NOT NULL CHECK (band_span > 0),
    max_band_index SMALLINT NOT NULL CHECK (max_band_index >= 1)
);
COMMENT ON TABLE journal_band_spec IS
    '마켓별 채번 대역 명세. 파생값은 market_id * band_span + 지역 번호이고 market_id 는 1..max_band_index 다.';

INSERT INTO journal_band_spec (name, band_span, max_band_index) VALUES
    ('event_seq',   10000000,      19),
    ('command_seq', 1000000000000, 63);

-- ── 축 경계 ──────────────────────────────────────────────────────────
-- V36 이전 event_journal 행은 market_id 가 비어 마켓별 체크포인트에 들어오지 않는다. 그 구간의
-- 상한은 다시 늘지 않으므로 여기서 한 번 박제한다. 이것이 없으면 V36 이후 이벤트가 없는 조용한
-- 마켓의 체크포인트가 0 이 되어 그 마켓의 레거시 명령 전부가 크래시 창으로 오판된다.
-- 새로 만든 DB 에서는 0 이다. 빈 DB 에 V36 까지 한 번에 적용되기 때문이다.
CREATE TABLE journal_axis_boundary (
    name    TEXT   PRIMARY KEY,
    cmd_seq BIGINT NOT NULL CHECK (cmd_seq >= 0)
);
COMMENT ON TABLE journal_axis_boundary IS
    '재생 축 경계 상수. pre_v36_event 는 market_id 없는 이벤트 구간의 명령 상한이다(ADR 0022 5-2).';

INSERT INTO journal_axis_boundary (name, cmd_seq)
SELECT 'pre_v36_event', COALESCE(max(source_cmd_seq), 0)
FROM event_journal
WHERE market_id IS NULL;

-- 박제한 경계가 믿을 만한지 한 번 확인한다. 경계 이하 명령은 전부 이벤트를 가져야 한다.
-- 이벤트 없는 명령이 있으면 V36 이전 구간이 단일 라이터였다는 전제가 틀린 것이라 멈춘다.
DO $$
DECLARE
    v_bound BIGINT;
    v_holes BIGINT;
BEGIN
    SELECT cmd_seq INTO v_bound FROM journal_axis_boundary WHERE name = 'pre_v36_event';
    IF v_bound > 0 THEN
        SELECT count(*) INTO v_holes
        FROM command_journal c
        WHERE c.seq <= v_bound
          AND NOT EXISTS (SELECT 1 FROM event_journal e WHERE e.source_cmd_seq = c.seq);
        IF v_holes > 0 THEN
            RAISE EXCEPTION 'V36 이전 구간에 이벤트 없는 명령 %건(경계 %). 단일 라이터 전제가 깨졌다(ADR 0022 9-12)',
                v_holes, v_bound;
        END IF;
    END IF;
END $$;

-- ── 꼬리 잎 ──────────────────────────────────────────────────────────
-- 현재 정규 잎이 2x10^8 에서 끝난다. 그 뒤로는 DEFAULT 에 떨어지고, DEFAULT 에 한 행이라도 들어간
-- 구간은 PostgreSQL 이 잎 생성을 거부하므로 영영 되돌릴 수 없다. 대역 판을 접어도 이 잎은 남긴다.
-- DEFAULT 에 이 범위 행이 이미 있으면 여기서 실패한다. 그것이 맞다.
CREATE TABLE command_journal_p200000000 PARTITION OF command_journal
    FOR VALUES FROM (200000000) TO (1000000000000);

-- ── 대역 잎 ──────────────────────────────────────────────────────────
-- 마켓이 있는 대역만 잎을 만든다. 멱등 조회가 파티션 키 술어 없이 잎 전부를 훑어 잎 수가 곧
-- 접수 락 안의 비용이다. 잎 67개면 2.94밀리초, 15개면 0.26밀리초가 더 붙는다(ADR 0022 0단계 (2)).
-- 기존 마켓 시드와 마켓 생성 트리거가 같이 부른다.
CREATE OR REPLACE FUNCTION ensure_command_band_partition(p_market_id SMALLINT) RETURNS VOID
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE
    v_span BIGINT;
    v_max  SMALLINT;
BEGIN
    SELECT band_span, max_band_index INTO v_span, v_max
    FROM journal_band_spec WHERE name = 'command_seq';
    IF p_market_id < 1 OR p_market_id > v_max THEN
        RAISE EXCEPTION '명령 대역 밖 마켓 id: % (허용 1..%)', p_market_id, v_max;
    END IF;
    EXECUTE format('CREATE TABLE IF NOT EXISTS %I PARTITION OF command_journal FOR VALUES FROM (%s) TO (%s)',
                   'command_journal_b' || p_market_id,
                   p_market_id * v_span,
                   (p_market_id + 1) * v_span);
END $$;

REVOKE ALL ON FUNCTION ensure_command_band_partition(SMALLINT) FROM PUBLIC;

-- ── 채번 함수 일반화 ─────────────────────────────────────────────────
-- 대역 상한을 명세표에서 읽는다. event_seq 에서는 V36 과 같은 값, 같은 비교, 같은 순서다.
-- 명세 없는 이름은 상한을 모르는 채번이라 조용히 넘기지 않는다.
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

-- ── 명령 대역 채번 ───────────────────────────────────────────────────
-- 파생을 DB 에 두어 앱이 대역 폭을 모르게 한다. 6단계 커트오버 전까지 호출자가 없다.
CREATE OR REPLACE FUNCTION next_market_command_seq(p_market_id SMALLINT) RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE
    v_span  BIGINT;
    v_max   SMALLINT;
    v_local BIGINT;
BEGIN
    SELECT band_span, max_band_index INTO v_span, v_max
    FROM journal_band_spec WHERE name = 'command_seq';
    IF p_market_id < 1 OR p_market_id > v_max THEN
        RAISE EXCEPTION '명령 대역 밖 마켓 id: % (허용 1..%)', p_market_id, v_max;
    END IF;
    -- 잎 없는 대역 값은 DEFAULT 로 떨어지고 그 구간 잎은 다시 만들 수 없다. 채번 전에 끊는다.
    IF to_regclass('public.command_journal_b' || p_market_id) IS NULL THEN
        RAISE EXCEPTION '명령 대역 잎 없음: command_journal_b%. 마켓 행을 먼저 만든다', p_market_id;
    END IF;
    v_local := next_market_journal_value(p_market_id, 'command_seq', 1);
    RETURN p_market_id * v_span + v_local;
END $$;

REVOKE ALL ON FUNCTION next_market_command_seq(SMALLINT) FROM PUBLIC;

-- ── 마켓 생성 트리거 확장 ────────────────────────────────────────────
-- 마켓 행과 두 카운터와 대역 잎을 한 트랜잭션에서 만든다. 잎 없는 마켓이 생기지 않는다.
-- 대가는 이 순간 command_journal 부모에 걸리는 ACCESS EXCLUSIVE 잠금이고 그동안 접수 append 가
-- 기다린다. 마켓 생성은 관리자 조작이라 드물다(ADR 0022 3-1(6)).
CREATE OR REPLACE FUNCTION seed_market_journal_counters() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
    INSERT INTO market_journal_counters (market_id, name, next_value)
    VALUES (NEW.id, 'event_seq', 1), (NEW.id, 'command_seq', 1)
    ON CONFLICT (market_id, name) DO NOTHING;
    PERFORM ensure_command_band_partition(NEW.id);
    RETURN NEW;
END $$;

-- 이미 있는 마켓용. 앞으로 생길 마켓은 위 트리거가 맡는다.
INSERT INTO market_journal_counters (market_id, name, next_value)
SELECT id, 'command_seq', 1 FROM markets
ON CONFLICT (market_id, name) DO NOTHING;

DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN SELECT id FROM markets ORDER BY id LOOP
        PERFORM ensure_command_band_partition(r.id);
    END LOOP;
END $$;

-- ── 권한 ─────────────────────────────────────────────────────────────
-- next_market_journal_value 는 호출자 권한으로 돌고 정산 핫패스에서 app_role 이 부른다. 이제
-- 명세표를 읽으므로 그 읽기 권한이 빠지면 정산이 전부 멈춘다. 같은 마이그레이션에서 준다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_role') THEN
        GRANT SELECT ON journal_band_spec, journal_axis_boundary TO app_role;
        GRANT EXECUTE ON FUNCTION next_market_command_seq(SMALLINT) TO app_role;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'harness_role') THEN
        GRANT SELECT ON journal_band_spec, journal_axis_boundary TO harness_role;
    END IF;
END $$;
