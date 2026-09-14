-- 명령 저널 대역 채번의 단방향 래치. ADR 0022 6단계 6-a 다.
--
-- 왜 앱 설정이 아니라 DB 래치인가. 모드를 앱 속성으로 두면 낡은 설정으로 뜬 노드가 승격한 순간 대역 행 뒤에 전역
-- seq 를 쓰고, 그 값은 그 마켓 커서보다 낮아 엔진이 예외 없이 버린다. 래치는 모드를 채번 지점 한 곳에서 정한다.
--
-- 이 마이그레이션은 래치 행을 넣지 않는다. 행이 없는 동안 6인자 판도 전역 채번이라 채번이 오늘과 같다. 돌아올 수
-- 없는 지점은 소유자가 latch_command_band_cutover() 를 불러 래치 행이 커밋되는 순간이다(6-b).
--
-- 되돌리기. 래치 행이 없을 때만 한다. 6인자 판과 latch_command_band_cutover 와 journal_cutover 를 지우고 5인자 판을
-- V43 본문으로 다시 적용하는 새 마이그레이션을 더한다. 래치 행이 있으면 되돌리지 않는다.

-- ── 래치 표 ──────────────────────────────────────────────────────────
-- 행을 지우거나 고치면 전역 채번이 대역 값 아래나 위에서 이어져 마켓 커서 아래 무음 폐기나 영구 결번이 된다.
CREATE TABLE journal_cutover (
    name       TEXT        PRIMARY KEY,
    latched_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    latched_by TEXT        NOT NULL DEFAULT session_user
);
COMMENT ON TABLE journal_cutover IS
    '저널 채번 커트오버 래치. 삽입 전용이다. name = ''command_seq'' 행이 있으면 명령 채번이 마켓 대역이다(ADR 0022 6단계).';

CREATE TRIGGER trg_cutover_noupd BEFORE UPDATE OR DELETE ON journal_cutover
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_cutover_notrunc BEFORE TRUNCATE ON journal_cutover
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

-- ── 6인자 머리말 ─────────────────────────────────────────────────────
-- 리더 검증, 어드바이저리 락 키, 멱등 조회의 술어와 정렬, VOLATILE 은 V43 과 같다. 다른 곳은 (3) 채번뿐이다.
CREATE FUNCTION append_command_prologue(
    p_holder          TEXT,
    p_epoch           BIGINT,
    p_is_new          BOOLEAN,
    p_user_id         BIGINT,
    p_client_order_id VARCHAR,
    p_market_id       SMALLINT
) RETURNS TABLE (
    out_seq        BIGINT,
    out_replayed   BOOLEAN,
    out_fencing    BIGINT,
    out_order_id   BIGINT
)
LANGUAGE plpgsql VOLATILE AS $$
DECLARE
    r RECORD;
BEGIN
    -- (1) 커밋 전 리더 검증 계약(P0-1). 메시지와 SQLSTATE 를 assert_current_leader 가 정하므로
    -- FencingErrors 의 강등 분류가 바뀌지 않는다.
    PERFORM assert_current_leader(p_holder, p_epoch);

    -- (2) NEW 멱등 재전송 판정. 락 키와 조회 술어에 market_id 를 넣지 않는다(ADR 0022 4-3).
    IF p_is_new AND p_client_order_id IS NOT NULL THEN
        PERFORM pg_advisory_xact_lock(
            hashtextextended('cmd-new:' || p_user_id::text || ':' || p_client_order_id, 0));

        SELECT c.seq, c.fencing_token, c.assigned_order_id INTO r
        FROM command_journal c
        WHERE c.command_type = 'NEW'
          AND c.user_id = p_user_id
          AND c.client_order_id = p_client_order_id
        ORDER BY c.created_at, c.seq
        LIMIT 1;

        IF FOUND THEN
            out_seq      := r.seq;
            out_replayed := TRUE;
            out_fencing  := r.fencing_token;
            out_order_id := r.assigned_order_id;
            RETURN NEXT;
            RETURN;
        END IF;
    END IF;

    -- (3) 채번. 재전송이 아닐 때만 한다. 래치는 채번 직전에 이 트랜잭션에서 읽고 호출자는 마켓 접수 락을 커밋까지
    -- 쥔다. 그래서 한 마켓 안에서는 래치 전 행이 모두 먼저 커밋되고 대역 행이 뒤따른다(ADR 0022 6-a).
    IF EXISTS (SELECT 1 FROM journal_cutover WHERE name = 'command_seq') THEN
        out_seq := next_market_command_seq(p_market_id);
    ELSE
        out_seq := next_journal_value('command_seq');
    END IF;
    out_replayed := FALSE;
    out_fencing  := NULL;
    out_order_id := NULL;
    RETURN NEXT;
END $$;

COMMENT ON FUNCTION append_command_prologue(TEXT, BIGINT, BOOLEAN, BIGINT, VARCHAR, SMALLINT) IS
'접수 저널 append 의 리더 검증과 NEW 멱등 판정과 채번을 한 왕복으로 묶는다. 채번 축은 journal_cutover 래치가 정한다(V44).';

-- ── 5인자 머리말 ─────────────────────────────────────────────────────
-- 래치 뒤에는 거부한다. 거부를 리더 검증 뒤에 두어 구 리더는 fencing 거부로 강등되고 현 리더는 접수만 닫힌다.
-- 그래서 메시지에 FencingErrors 의 강등 문구를 넣지 않는다(ADR 0022 6-a). 나머지 본문은 V43 과 같다.
CREATE OR REPLACE FUNCTION append_command_prologue(
    p_holder          TEXT,
    p_epoch           BIGINT,
    p_is_new          BOOLEAN,
    p_user_id         BIGINT,
    p_client_order_id VARCHAR
) RETURNS TABLE (
    out_seq        BIGINT,
    out_replayed   BOOLEAN,
    out_fencing    BIGINT,
    out_order_id   BIGINT
)
LANGUAGE plpgsql VOLATILE AS $$
DECLARE
    r RECORD;
BEGIN
    PERFORM assert_current_leader(p_holder, p_epoch);

    IF EXISTS (SELECT 1 FROM journal_cutover WHERE name = 'command_seq') THEN
        RAISE EXCEPTION '명령 대역 커트오버 래치 뒤에는 5인자 append_command_prologue 로 접수할 수 없다. 6인자 판을 부르는 앱이어야 한다(ADR 0022 6단계)'
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;

    IF p_is_new AND p_client_order_id IS NOT NULL THEN
        PERFORM pg_advisory_xact_lock(
            hashtextextended('cmd-new:' || p_user_id::text || ':' || p_client_order_id, 0));

        SELECT c.seq, c.fencing_token, c.assigned_order_id INTO r
        FROM command_journal c
        WHERE c.command_type = 'NEW'
          AND c.user_id = p_user_id
          AND c.client_order_id = p_client_order_id
        ORDER BY c.created_at, c.seq
        LIMIT 1;

        IF FOUND THEN
            out_seq      := r.seq;
            out_replayed := TRUE;
            out_fencing  := r.fencing_token;
            out_order_id := r.assigned_order_id;
            RETURN NEXT;
            RETURN;
        END IF;
    END IF;

    out_seq      := next_journal_value('command_seq');
    out_replayed := FALSE;
    out_fencing  := NULL;
    out_order_id := NULL;
    RETURN NEXT;
END $$;

COMMENT ON FUNCTION append_command_prologue(TEXT, BIGINT, BOOLEAN, BIGINT, VARCHAR) IS
'래치 전 전역 채번 머리말(V39). journal_cutover 래치가 있으면 55000 으로 거부한다(V44).';

-- ── 래치 전환 ────────────────────────────────────────────────────────
-- 소유자가 부른다. 검사는 ADR 0022 6-b 래치 전 확인 1~3 이고 나머지는 절차가 확인한다. 두 번째 호출은 예외다.
-- 이미 래치된 DB 에서 조용히 성공하면 운영자가 전환 시각을 잘못 기록한다. 동시에 두 번 부르면 늦은 쪽이 PK 23505 로 선다.
CREATE FUNCTION latch_command_band_cutover() RETURNS TIMESTAMPTZ
LANGUAGE plpgsql AS $$
DECLARE
    v_latched_at TIMESTAMPTZ;
    v_latched_by TEXT;
    v_markets    TEXT;
    v_rows       BIGINT;
BEGIN
    SELECT latched_at, latched_by INTO v_latched_at, v_latched_by
    FROM journal_cutover WHERE name = 'command_seq';
    IF FOUND THEN
        RAISE EXCEPTION '명령 대역 커트오버 래치가 이미 있다: latched_at=%, latched_by=%', v_latched_at, v_latched_by
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;

    -- (1) 잎 없는 대역 값은 DEFAULT 로 떨어지고 행이 든 구간의 잎은 다시 만들 수 없다.
    SELECT string_agg(m.id::text, ',' ORDER BY m.id) INTO v_markets
    FROM markets m
    WHERE NOT EXISTS (SELECT 1 FROM pg_inherits i
                      WHERE i.inhparent = 'public.command_journal'::regclass
                        AND i.inhrelid = to_regclass('public.command_journal_b' || m.id));
    IF v_markets IS NOT NULL THEN
        RAISE EXCEPTION '래치 전 확인 1 실패: 대역 잎이 없는 마켓 %', v_markets
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_inherits i
                   WHERE i.inhparent = 'public.command_journal'::regclass
                     AND i.inhrelid = to_regclass('public.command_journal_p200000000')) THEN
        RAISE EXCEPTION '래치 전 확인 1 실패: 꼬리 잎 command_journal_p200000000 이 없다'
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;

    -- (2)
    SELECT string_agg(m.id::text, ',' ORDER BY m.id) INTO v_markets
    FROM markets m
    WHERE NOT EXISTS (SELECT 1 FROM market_journal_counters c
                      WHERE c.market_id = m.id AND c.name = 'command_seq');
    IF v_markets IS NOT NULL THEN
        RAISE EXCEPTION '래치 전 확인 2 실패: command_seq 카운터가 없는 마켓 %', v_markets
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;

    -- (3)
    SELECT count(*) INTO v_rows FROM command_journal_default;
    IF v_rows > 0 THEN
        RAISE EXCEPTION '래치 전 확인 3 실패: command_journal_default 에 %행이 있다', v_rows
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;

    INSERT INTO journal_cutover (name) VALUES ('command_seq') RETURNING latched_at INTO v_latched_at;
    RETURN v_latched_at;
END $$;

-- ── 권한 ─────────────────────────────────────────────────────────────
-- 래치 전환은 소유자만 한다. 6인자 판은 호출자 권한으로 돌므로 래치 표 읽기 권한이 빠지면 접수가 전부 멈춘다.
REVOKE ALL ON FUNCTION latch_command_band_cutover() FROM PUBLIC;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_role') THEN
        REVOKE ALL ON FUNCTION latch_command_band_cutover() FROM app_role;
        GRANT SELECT ON journal_cutover TO app_role;
        GRANT EXECUTE ON FUNCTION
            append_command_prologue(TEXT, BIGINT, BOOLEAN, BIGINT, VARCHAR, SMALLINT) TO app_role;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'harness_role') THEN
        GRANT SELECT ON journal_cutover TO harness_role;
    END IF;
END $$;
