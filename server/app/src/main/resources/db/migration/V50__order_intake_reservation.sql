-- V50__order_intake_reservation.sql (ADR 0034 S3). 쓰기를 멈춘 창에서 적용한다. V49 가 먼저 적용돼 있어야 한다.
-- 운영 풀의 statement_timeout·lock_timeout 을 Flyway 가 물려받으면 표 생성과 함수 교체가 막힌다(V41·V48 과 같은 근거).
SET LOCAL statement_timeout = 0;
SET LOCAL lock_timeout = 0;

-- ── 예약 행(추가 전용) ──────────────────────────────────────────────
-- P2 이상에서 주문 생성 명령이 저널에 들어갈 때 같은 트랜잭션이 한 행을 남긴다. 원장 RESERVE 거래(business_event_id =
-- 주문 id)와 짝이다. 정산은 이 행의 금액과 계정을 권위로 쓰고 reserve_funds 를 다시 부르지 않는다(ADR 0034 결정 5).
-- 쓰는 곳은 소유자 권한 머리말 하나다. 접수 락 안에서 마켓·계정 행에 KEY SHARE 를 더하지 않으려고 외래키를 두지 않고,
-- 연결은 하네스 C31 이 감사한다. payload_sha256 은 예약액을 계산한 payload 와 저널 행 payload 를 묶는다.
CREATE TABLE order_reservations (
    command_seq          BIGINT         PRIMARY KEY,
    order_id             BIGINT         NOT NULL,
    user_id              BIGINT         NOT NULL,
    market_id            SMALLINT       NOT NULL,
    asset_id             SMALLINT       NOT NULL,
    available_account_id BIGINT         NOT NULL,
    locked_account_id    BIGINT         NOT NULL,
    amount               NUMERIC(40,18) NOT NULL CHECK (amount > 0),
    ledger_txn_id        BIGINT         NOT NULL,
    payload_sha256       BYTEA          NOT NULL,
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_order_res_noupd BEFORE UPDATE OR DELETE ON order_reservations
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_order_res_notrunc BEFORE TRUNCATE ON order_reservations
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

-- ── 예약액 계산(ADR 0034 결정 7) ────────────────────────────────────
-- checksum 이 덮는 payload 값과 카탈로그 scale 로 계산한다. 정산(SettlementService.settleAccepted)과 게이트웨이 가드
-- (OrderGateway.ParsedOrder.reserve)의 산식과 같다. 지정가 매수는 price x qty 를 quote 자산 scale 로 올림(양수라 UP 과
-- 같다), 시장가 매수는 quote_order_qty 그대로, 매도는 qty 그대로다(§1.4 방향별 양자화 레짐). round 는 값을 바꾸지 않고
-- 표시 자릿수를 scale 로 맞춘다(Java setScale 과 글자까지 같다, S3 설계 3절 P1).
CREATE FUNCTION order_reserve_amount(p_market_id SMALLINT, p_payload JSONB,
                                     OUT asset_id SMALLINT, OUT amount NUMERIC)
LANGUAGE plpgsql STABLE AS $$
DECLARE
    v_side  TEXT := p_payload ->> 'side';
    v_type  TEXT := p_payload ->> 'ord_type';
    v_base  SMALLINT;
    v_quote SMALLINT;
    v_scale INT;
    v_raw   NUMERIC;
BEGIN
    SELECT m.base_asset_id, m.quote_asset_id, q.scale INTO v_base, v_quote, v_scale
      FROM markets m JOIN assets q ON q.id = m.quote_asset_id
     WHERE m.id = p_market_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION '예약액 계산: 마켓 % 없음', p_market_id;
    END IF;
    IF v_side = 'BUY' AND v_type = 'MARKET' THEN
        asset_id := v_quote;
        amount   := (p_payload ->> 'quote_order_qty')::NUMERIC;
    ELSIF v_side = 'BUY' THEN
        asset_id := v_quote;
        v_raw    := (p_payload ->> 'price')::NUMERIC * (p_payload ->> 'qty')::NUMERIC;
        amount   := trunc(v_raw, v_scale);
        IF amount < v_raw THEN
            amount := amount + power(10::NUMERIC, -v_scale);
        END IF;
        amount   := round(amount, v_scale);
    ELSIF v_side = 'SELL' THEN
        asset_id := v_base;
        amount   := (p_payload ->> 'qty')::NUMERIC;
    END IF;
    IF amount IS NULL OR amount <= 0 THEN
        RAISE EXCEPTION '예약액 계산: payload 로 양수 예약액을 만들 수 없다(market=%, side=%, ord_type=%)',
            p_market_id, v_side, v_type;
    END IF;
END $$;

-- ── 10인자 머리말(P1·P2 공용) ───────────────────────────────────────
-- 순서는 ADR 0034 결정 5 다. (1) 리스 행 KEY SHARE 와 판정 (2) NEW 멱등 판정(V44·V48 그대로) (3) P2 이상 NEW 의 예약액
-- 계산·대조와 reserve_funds (4) 채번(V44 래치 규칙) (5) 예약 행. P1 에서는 (3)(5)를 건너뛰어 V48 6인자 판과 같은 행을 쓴다.
-- 잔고가 모자라면 (3)의 balances CHECK(chk_balance_nonneg)가 23514 로 트랜잭션 전체를 롤백한다. 저널 행·채번·예약 행이
-- 남지 않는다. 채번보다 예약이 먼저인 이유는 잔고 행을 기다리는 동안 채번 카운터 행을 쥐지 않으려는 것이다.
-- 소유자 권한이다. 예약 행과 원장 게시는 이 함수로만 쓴다(app_role 은 order_reservations 를 읽기만 한다).
CREATE FUNCTION append_command_prologue(
    p_holder            TEXT,
    p_epoch             BIGINT,
    p_is_new            BOOLEAN,
    p_user_id           BIGINT,
    p_client_order_id   VARCHAR,
    p_market_id         SMALLINT,
    p_assigned_order_id BIGINT,
    p_payload           JSONB,
    p_check_asset       SMALLINT,
    p_check_amount      NUMERIC
) RETURNS TABLE (
    out_seq      BIGINT,
    out_replayed BOOLEAN,
    out_fencing  BIGINT,
    out_order_id BIGINT,
    out_reserved BOOLEAN
)
LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    r        RECORD;
    v_prot   SMALLINT;
    v_asset  SMALLINT;
    v_amount NUMERIC;
    v_avail  BIGINT;
    v_locked BIGINT;
    v_txn    BIGINT;
BEGIN
    -- (1) 조건 없는 KEY SHARE 뒤 판정(V41 순서). 이 잠금을 쥔 동안 전환(모든 리스 행 FOR UPDATE)은 커밋하지 못하므로
    --     아래에서 읽는 모드는 이 트랜잭션이 끝날 때까지 그대로다.
    PERFORM assert_and_lock_market_leader(p_market_id, p_holder, p_epoch);
    SELECT protocol INTO v_prot FROM leader_protocol;

    -- (2) NEW 멱등 재전송 판정. 락 키와 조회 술어에 market_id 를 넣지 않는다(ADR 0022 4-3). 재전송은 예약하지 않는다.
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
            out_seq := r.seq; out_replayed := TRUE; out_fencing := r.fencing_token;
            out_order_id := r.assigned_order_id; out_reserved := FALSE;
            RETURN NEXT;
            RETURN;
        END IF;
    END IF;

    -- (3) 예약. DB 계산이 권위이고 앱 값은 대조에만 쓴다.
    out_reserved := p_is_new AND v_prot >= 2;
    IF out_reserved THEN
        SELECT a.asset_id, a.amount INTO v_asset, v_amount FROM order_reserve_amount(p_market_id, p_payload) a;
        IF p_check_asset IS DISTINCT FROM v_asset OR p_check_amount IS NULL OR p_check_amount <> v_amount THEN
            RAISE EXCEPTION '예약액 대조 불일치: 앱(asset=%, amount=%) DB(asset=%, amount=%), order=%',
                p_check_asset, p_check_amount, v_asset, v_amount, p_assigned_order_id;
        END IF;
        SELECT max(a.id) FILTER (WHERE a.purpose = 'AVAILABLE'), max(a.id) FILTER (WHERE a.purpose = 'LOCKED')
          INTO v_avail, v_locked
          FROM accounts a
         WHERE a.owner_type = 'USER' AND a.owner_id = p_user_id AND a.asset_id = v_asset
           AND a.purpose IN ('AVAILABLE', 'LOCKED') AND a.shard = 0;
        IF v_avail IS NULL OR v_locked IS NULL THEN
            RAISE EXCEPTION '예약 계정 없음: user=%, asset=%', p_user_id, v_asset;
        END IF;
        v_txn := reserve_funds(p_assigned_order_id, v_avail, v_locked, v_asset, v_amount);
    END IF;

    -- (4) 채번. V44 와 같다.
    IF EXISTS (SELECT 1 FROM journal_cutover WHERE name = 'command_seq') THEN
        out_seq := next_market_command_seq(p_market_id);
    ELSE
        out_seq := next_journal_value('command_seq');
    END IF;

    -- (5) 예약 행
    IF out_reserved THEN
        INSERT INTO order_reservations (command_seq, order_id, user_id, market_id, asset_id,
                                        available_account_id, locked_account_id, amount, ledger_txn_id,
                                        payload_sha256)
        VALUES (out_seq, p_assigned_order_id, p_user_id, p_market_id, v_asset,
                v_avail, v_locked, v_amount, v_txn, sha256(convert_to(p_payload::text, 'UTF8')));
    END IF;
    out_replayed := FALSE; out_fencing := NULL; out_order_id := NULL;
    RETURN NEXT;
END $$;

-- ── 레거시 머리말은 P2 이상에서 예약 없는 NEW 를 받지 않는다 ────────────
-- 6인자 판. V48 본문에서 판정 뒤 한 조건만 더한다. 거부를 판정 뒤에 두어 오래된 리더는 fencing 거부로 강등된다.
CREATE OR REPLACE FUNCTION append_command_prologue(
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
    PERFORM assert_lease_holder(lease_resource_for_market(p_market_id), p_holder, p_epoch);
    IF p_is_new AND (SELECT protocol FROM leader_protocol) >= 2 THEN
        RAISE EXCEPTION 'P2 이상에서는 예약 없는 주문 생성 명령을 받지 않는다. 10인자 append_command_prologue 를 부르는 앱이어야 한다(ADR 0034 S3)'
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

-- 5인자 판. V44 본문에서 래치 거부 뒤 같은 조건을 더한다.
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
    IF p_is_new AND (SELECT protocol FROM leader_protocol) >= 2 THEN
        RAISE EXCEPTION 'P2 이상에서는 예약 없는 주문 생성 명령을 받지 않는다. 10인자 append_command_prologue 를 부르는 앱이어야 한다(ADR 0034 S3)'
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

-- ── 저널 행: P2 이상의 NEW 는 같은 트랜잭션의 예약 행과 맞아야 넣는다 ─────
-- V48 본문 뒤에 확인 하나를 더한다. 넣지 못했으면(0행, 오래된 리더) 확인하지 않고 0 을 돌려준다. 예약 행은 같은 seq·주문·
-- 사용자·마켓이고 예약액을 계산한 payload 가 이 행의 payload 와 같아야 한다. 어긋나면 트랜잭션 전체를 롤백한다.
CREATE OR REPLACE FUNCTION append_command_journal_row(
    p_seq BIGINT, p_market_id SMALLINT, p_command_type VARCHAR, p_user_id BIGINT,
    p_client_order_id VARCHAR, p_assigned_order_id BIGINT, p_engine_ts BIGINT, p_tie_break BIGINT,
    p_payload JSONB, p_checksum BIGINT, p_holder TEXT, p_epoch BIGINT
) RETURNS INTEGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE v_rows INTEGER;
BEGIN
    INSERT INTO command_journal
      (seq, market_id, command_type, user_id, client_order_id, assigned_order_id,
       engine_ts, tie_break, payload, fencing_token, checksum)
    SELECT p_seq, v.market_id, p_command_type, p_user_id, p_client_order_id, p_assigned_order_id,
           p_engine_ts, p_tie_break, p_payload, l.epoch, p_checksum
    FROM (VALUES (p_market_id)) AS v(market_id)
    JOIN leader_lease l ON l.resource = lease_resource_for_market(v.market_id)
    WHERE l.holder = p_holder AND l.epoch = p_epoch AND l.lease_until > clock_timestamp()
    FOR KEY SHARE OF l;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    IF v_rows = 1 AND p_command_type = 'NEW' AND (SELECT protocol FROM leader_protocol) >= 2
       AND NOT EXISTS (SELECT 1 FROM order_reservations r
                        WHERE r.command_seq = p_seq AND r.order_id = p_assigned_order_id
                          AND r.user_id = p_user_id AND r.market_id = p_market_id
                          AND r.payload_sha256 = sha256(convert_to(p_payload::text, 'UTF8'))) THEN
        RAISE EXCEPTION 'P2 주문 생성 명령에 맞는 예약 행이 없다(seq=%, order=%)', p_seq, p_assigned_order_id;
    END IF;
    RETURN v_rows;   -- 0 이면 오래된 리더다. 호출자가 예외로 채번·예약까지 롤백한다
END $$;

-- ── 획득·게시: 바이너리가 아는 최고 프로토콜 ──────────────────────────
-- 내부 함수. V48 lease_acquire_core 의 본문에 호출자가 아는 최고 프로토콜을 더한다. 그보다 높은 모드에서는 거부한다.
-- S2 이전 바이너리는 P2 의 예약 정산을 모르므로 P2 리더가 되면 예약 주문을 다시 예약하거나(23505) 거부 주문의 예약을
-- 풀지 않는다(S3 설계 4-8).
CREATE FUNCTION lease_acquire_capped(p_resource TEXT, p_holder TEXT, p_ttl INTERVAL, p_max_protocol SMALLINT)
RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE
    v_started  TIMESTAMPTZ := clock_timestamp();
    v_protocol SMALLINT;
    v_epoch    BIGINT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM leader_lease WHERE resource = p_resource) THEN
        RAISE EXCEPTION 'leader_lease 행 없음: resource=%', p_resource;
    END IF;
    PERFORM 1 FROM leader_lease
     WHERE resource = p_resource AND lease_until < clock_timestamp()
       FOR UPDATE;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    SELECT protocol INTO v_protocol FROM leader_protocol FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'leader_protocol 행 없음: 부트스트랩 오류';
    END IF;
    IF v_protocol > p_max_protocol THEN
        RAISE EXCEPTION '이 호출은 P% 까지만 안다. 현재 P%에서는 리스를 획득하지 않는다', p_max_protocol, v_protocol
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    IF (v_protocol < 3 AND p_resource <> 'matching-engine')
       OR (v_protocol = 3 AND p_resource NOT IN ('matching-p0', 'matching-p1')) THEN
        RAISE EXCEPTION '리스 자원 %은 P%에서 획득할 수 없다', p_resource, v_protocol
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    v_epoch := allocate_lease_epoch();
    UPDATE leader_lease
       SET holder = p_holder, epoch = v_epoch, lease_until = v_started + p_ttl
     WHERE resource = p_resource;
    INSERT INTO lease_acquisitions (epoch, resource, holder, protocol, origin)
    VALUES (v_epoch, p_resource, p_holder, v_protocol, 'ACQUIRE');
    RETURN v_epoch;
END $$;

-- S2 바이너리가 부르는 3인자 판은 P1 에서만 획득한다.
CREATE OR REPLACE FUNCTION try_acquire_lease(p_resource TEXT, p_holder TEXT, p_ttl INTERVAL) RETURNS BIGINT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN lease_acquire_capped(p_resource, p_holder, p_ttl, 1::SMALLINT);
END $$;

CREATE FUNCTION try_acquire_lease(p_resource TEXT, p_holder TEXT, p_ttl INTERVAL, p_max_protocol SMALLINT)
RETURNS BIGINT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN lease_acquire_capped(p_resource, p_holder, p_ttl, p_max_protocol);
END $$;

-- 게시. V48 본문에 최고 프로토콜 확인을 더한다. P3 은 늘 거부한다(ADR 0034 결정한 값 18).
CREATE FUNCTION publish_leader_epoch(p_holder TEXT, p_epoch BIGINT, p_max_protocol SMALLINT) RETURNS BIGINT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_epoch    BIGINT;
    v_protocol SMALLINT;
BEGIN
    UPDATE leader_lease
       SET holder = p_holder, epoch = p_epoch,
           lease_until = clock_timestamp() + interval '100 years'
     WHERE resource = 'matching-engine'
       AND (epoch < p_epoch OR (epoch = p_epoch AND holder = p_holder))
    RETURNING epoch INTO v_epoch;
    IF v_epoch IS NULL THEN
        RETURN NULL;
    END IF;
    SELECT protocol INTO v_protocol FROM leader_protocol FOR UPDATE;
    IF v_protocol = 3 THEN
        RAISE EXCEPTION '파티션 모드(P3)에서는 외부 보관소 epoch 게시를 거부한다'
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    IF v_protocol > p_max_protocol THEN
        RAISE EXCEPTION '이 호출은 P% 까지만 안다. 현재 P%에서는 epoch 를 게시하지 않는다', p_max_protocol, v_protocol
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    INSERT INTO lease_acquisitions (epoch, resource, holder, protocol, origin)
    VALUES (v_epoch, 'matching-engine', p_holder, v_protocol, 'PUBLISH')
    ON CONFLICT (epoch) DO NOTHING;
    IF NOT FOUND AND NOT EXISTS (SELECT 1 FROM lease_acquisitions
                                 WHERE epoch = v_epoch AND resource = 'matching-engine'
                                   AND holder = p_holder) THEN
        RAISE EXCEPTION '게시 epoch %가 다른 획득 이력과 겹친다', v_epoch;
    END IF;
    RETURN v_epoch;
END $$;

-- S2 이전 바이너리가 부르는 2인자 판은 P1 에서만 게시한다.
CREATE OR REPLACE FUNCTION publish_leader_epoch(p_holder TEXT, p_epoch BIGINT) RETURNS BIGINT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
BEGIN
    RETURN publish_leader_epoch(p_holder, p_epoch, 1::SMALLINT);
END $$;

-- ── 정산 잠금 계획: 배치가 게시할 잔고 행을 첫 게시 전에 한 번에 잡는다 ───────
-- post_ledger_transaction 은 호출 하나 안에서만 account_id 오름차순으로 잡는다. 정산 배치는 게시를 여러 번 하므로(P1 ACCEPTED
-- 예약, TRADE 마다 한 번, 해제) 배치 전체로는 순서가 어긋날 수 있다. P2 에서는 접수 예약(AVAILABLE 다음 LOCKED)과 순환을
-- 만든다(S3 설계 11절 R6, 폐기용 컨테이너 재현 C20). 정산은 배치의 첫 게시 직전에 이 함수로 그 배치가 게시할 수 있는 잔고
-- 행 전체(§5.0 잠금 계획)를 오름차순으로 한 번에 잡는다. 행을 바꾸지 않고 잠근 행 수를 돌려준다. 소유자 권한인 이유는
-- app_role 에 balances UPDATE 권한이 없어 행 잠금 절을 직접 쓸 수 없기 때문이다.
CREATE FUNCTION lock_balance_rows(p_account_ids BIGINT[]) RETURNS INTEGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE v_rows INTEGER;
BEGIN
    PERFORM 1
      FROM balances b
     WHERE b.account_id = ANY (p_account_ids)
     ORDER BY b.account_id
       FOR NO KEY UPDATE;
    GET DIAGNOSTICS v_rows = ROW_COUNT;
    RETURN v_rows;
END $$;

-- ── 모드 전환(소유자 전용, 정지 창) ─────────────────────────────────
-- (1) 모든 리스 행을 resource 순 FOR UPDATE 로 잡아 진행 중 쓰기(KEY SHARE)의 커밋을 기다린다 (2) 모드 행을 잡는다
-- (3) 허용 전이인지 본다 (4) 선행 조건을 잠금 뒤에 본다 (5) 모드를 바꾼다(이력 트리거가 epoch_floor 를 남긴다)
-- (6) 모든 리스 행을 새 epoch 로 퇴역시켜 이전 모드의 쓰기를 펜싱한다. 퇴역은 matching-engine 행을 마지막에 해서 그 행의
-- epoch 가 셋 가운데 가장 크게 한다. 외부 보관소 노드는 재기동 때 토큰 하한을 리스 행에서 읽으므로(FencingTokenAlignment),
-- 그 행이 가장 작으면 전환 뒤 첫 게시가 다른 행의 RETIRE 이력과 겹쳐 거부된다(S3 설계 11절 R4). 잠금 대기는 5초로 끊는다
-- (S2 설계 3절 Q3). 이 판이 받는 전이는 1→2 와, p_allow_downgrade 를 준 2→1 이다. 2↔3 은 S7 이 이 함수를 개정하며 더한다.
CREATE FUNCTION set_leader_protocol(p_target SMALLINT, p_allow_downgrade BOOLEAN DEFAULT false)
RETURNS TABLE (out_resource TEXT, out_retired_epoch BIGINT, out_protocol SMALLINT)
LANGUAGE plpgsql SET lock_timeout = '5s' AS $$
DECLARE
    v_current   SMALLINT;
    v_unsettled BIGINT;
    v_sample    TEXT;
    v_res       TEXT;
    v_epoch     BIGINT;
BEGIN
    PERFORM 1 FROM leader_lease l ORDER BY l.resource FOR UPDATE;
    SELECT p.protocol INTO v_current FROM leader_protocol p FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'leader_protocol 행 없음: 부트스트랩 오류';
    END IF;
    IF NOT ((v_current = 1 AND p_target = 2) OR (v_current = 2 AND p_target = 1)) THEN
        RAISE EXCEPTION '프로토콜 전이 P% -> P%는 이 판에서 받지 않는다(1->2, 2->1 만)', v_current, p_target
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    IF v_current = 2 AND p_target = 1 AND NOT p_allow_downgrade THEN
        RAISE EXCEPTION 'P2 -> P1 은 ADR 0034 결정 8 과 시험 R1 을 확인한 뒤 p_allow_downgrade => true 로만 한다'
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    -- 선행 조건은 잠금 뒤에 본다. 잠금 전에 보면 그 사이 커밋되는 미예약 NEW 가 새어 들어온다(단계 계획 2절 검토 1 1b).
    SELECT count(*) INTO v_unsettled
      FROM command_journal c
     WHERE c.command_type = 'NEW'
       AND NOT EXISTS (SELECT 1 FROM event_emission_registry e WHERE e.source_cmd_seq = c.seq);
    IF v_unsettled > 0 THEN
        SELECT string_agg(s.seq::text, ',') INTO v_sample
          FROM (SELECT c.seq FROM command_journal c
                 WHERE c.command_type = 'NEW'
                   AND NOT EXISTS (SELECT 1 FROM event_emission_registry e WHERE e.source_cmd_seq = c.seq)
                 ORDER BY c.seq LIMIT 10) s;
        RAISE EXCEPTION '정산되지 않은 주문 생성 명령이 %건 있다(앞 seq: %). 정지 창에서 큐를 비운 뒤 다시 부른다',
            v_unsettled, v_sample
            USING ERRCODE = 'object_not_in_prerequisite_state';
    END IF;
    UPDATE leader_protocol SET protocol = p_target;
    -- 잠금은 위에서 resource 순으로 이미 쥐었다. 퇴역 순서만 matching-engine 을 끝에 둔다(false 가 true 보다 앞선다).
    FOR v_res IN SELECT l.resource FROM leader_lease l
                 ORDER BY (l.resource = 'matching-engine'), l.resource LOOP
        v_epoch := allocate_lease_epoch();
        UPDATE leader_lease l
           SET holder = '', epoch = v_epoch, lease_until = clock_timestamp() - interval '1 second'
         WHERE l.resource = v_res;
        INSERT INTO lease_acquisitions (epoch, resource, holder, protocol, origin)
        VALUES (v_epoch, v_res, '', p_target, 'RETIRE');
        out_resource := v_res; out_retired_epoch := v_epoch; out_protocol := p_target;
        RETURN NEXT;
    END LOOP;
END $$;

-- ── 권한 ────────────────────────────────────────────────────────────
-- 소유자 권한 함수와 내부 함수, 전환 함수에서 PUBLIC EXECUTE 를 회수한다(S2 설계 3절 Q10). 전환 함수는 app_role 에도
-- 주지 않는다. 예약 행은 app_role 이 읽기만 한다.
REVOKE ALL ON FUNCTION
    append_command_prologue(TEXT, BIGINT, BOOLEAN, BIGINT, VARCHAR, SMALLINT, BIGINT, JSONB, SMALLINT, NUMERIC),
    lease_acquire_capped(TEXT, TEXT, INTERVAL, SMALLINT),
    try_acquire_lease(TEXT, TEXT, INTERVAL, SMALLINT),
    publish_leader_epoch(TEXT, BIGINT, SMALLINT),
    lock_balance_rows(BIGINT[]),
    set_leader_protocol(SMALLINT, BOOLEAN)
FROM PUBLIC;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_role') THEN
        GRANT EXECUTE ON FUNCTION
            append_command_prologue(TEXT, BIGINT, BOOLEAN, BIGINT, VARCHAR, SMALLINT, BIGINT, JSONB, SMALLINT, NUMERIC),
            try_acquire_lease(TEXT, TEXT, INTERVAL, SMALLINT),
            publish_leader_epoch(TEXT, BIGINT, SMALLINT),
            lock_balance_rows(BIGINT[]),
            order_reserve_amount(SMALLINT, JSONB)
        TO app_role;
        GRANT SELECT ON order_reservations TO app_role;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'harness_role') THEN
        GRANT SELECT ON order_reservations TO harness_role;
    END IF;
END $$;
