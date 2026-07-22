-- ============================================================================
-- V1__init.sql
-- 원천: docs/백엔드_DB_설계.md v2.6 §3.1.
-- 전제: PostgreSQL 17+(파티션드 테이블 IDENTITY 필요), 확장 pgcrypto, 세션 TZ=UTC.
--       빈 PG17에서 단독 적용 가능해야 한다. 외부 확장(pg_partman/pg_cron) 의존 금지.
-- 범위: assets, markets, users, api_keys, accounts, balances, orders, trades,
--       trade_seq_registry, ledger_transactions, ledger_entries, command_journal,
--       event_journal, event_emission_registry, snapshots, journal_counters,
--       outbox, processed_events, idempotency_keys, leader_lease와 함수/트리거.
-- 제외: transfer_requests, candles, audit_log, invariant_checks, recovery_runs,
--       shedlock. 롤 생성은 V0__roles.sql 소관(권한 GRANT/REVOKE는 이 파일 말미).
-- V2 예정(파티션 자동화) 주석:
--   * pg_partman은 반드시 CREATE SCHEMA partman; 후
--     CREATE EXTENSION pg_partman SCHEMA partman; 으로 스키마를 고정해 설치한다.
--   * pg_cron은 shared_preload_libraries='pg_cron' 사전 설정(재시작 필요)이 전제다.
--   * 본 V1의 초기 파티션 명명은 partman 규칙(_pYYYY_MM, _p<start>)에 맞춘 부트스트랩.
-- ============================================================================
SET timezone = 'UTC';
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ----------------------------------------------------------------
-- 0. 참조/메타데이터
-- ----------------------------------------------------------------
CREATE TABLE assets (
    id          SMALLINT     PRIMARY KEY,
    symbol      VARCHAR(16)  NOT NULL UNIQUE,
    name        VARCHAR(64)  NOT NULL,
    kind        VARCHAR(8)   NOT NULL CHECK (kind IN ('CRYPTO','FIAT')),
    scale       SMALLINT     NOT NULL CHECK (scale BETWEEN 0 AND 18),
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE','HALTED','DELISTED')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
COMMENT ON COLUMN assets.scale IS '정산/표시 소수 자리(BTC=8,KRW=0,ETH=18). 저장은 NUMERIC(40,18) 통일, 입력은 이 scale로 양자화';

CREATE TABLE markets (
    id             SMALLINT     PRIMARY KEY,
    symbol         VARCHAR(24)  NOT NULL UNIQUE,
    base_asset_id  SMALLINT     NOT NULL REFERENCES assets(id),
    quote_asset_id SMALLINT     NOT NULL REFERENCES assets(id),
    tick_size      NUMERIC(40,18) NOT NULL CHECK (tick_size > 0),
    lot_size       NUMERIC(40,18) NOT NULL CHECK (lot_size  > 0),
    min_notional   NUMERIC(40,18) NOT NULL CHECK (min_notional >= 0),
    -- max_notional/max_qty: 곱셈 오버플로 상한값. 주문별 강제 주체는 앱 가드다(DB 설계 §1.4).
    max_notional   NUMERIC(40,18) NOT NULL CHECK (max_notional > 0),
    max_qty        NUMERIC(40,18) NOT NULL CHECK (max_qty > 0),
    maker_fee_rate NUMERIC(10,8)  NOT NULL DEFAULT 0.0005
                   CHECK (maker_fee_rate BETWEEN 0 AND 0.1),
    taker_fee_rate NUMERIC(10,8)  NOT NULL DEFAULT 0.0010
                   CHECK (taker_fee_rate BETWEEN 0 AND 0.1),
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
                   CHECK (status IN ('ACTIVE','HALTED','DELISTED')),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_market_distinct_assets CHECK (base_asset_id <> quote_asset_id),
    CONSTRAINT chk_market_notional_order  CHECK (max_notional >= min_notional)
);

-- ----------------------------------------------------------------
-- 1. 사용자 / 인증
-- ----------------------------------------------------------------
CREATE TABLE users (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    external_id UUID         NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    email       VARCHAR(255) NOT NULL UNIQUE,
    kind        VARCHAR(8)   NOT NULL DEFAULT 'HUMAN' CHECK (kind IN ('HUMAN','BOT')),
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE','SUSPENDED','CLOSED')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE api_keys (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users(id),
    access_key  VARCHAR(64)  NOT NULL UNIQUE,
    -- AES-256-GCM 암호문 저장. HMAC 재계산에 복호화가 필요하므로 비가역 해시 저장 금지(API v2.6 §3-2)
    enc_secret  VARCHAR(255) NOT NULL,
    label       VARCHAR(64),
    scope       VARCHAR(32)  NOT NULL DEFAULT 'TRADE'
                CHECK (scope IN ('READ','TRADE','WITHDRAW')),
    allow_ips   INET[]       NOT NULL DEFAULT '{}',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_apikey_active ON api_keys (user_id, label) WHERE deleted_at IS NULL;

-- ----------------------------------------------------------------
-- 2. 계정 / 잔고. 잔고 키를 account_id 하나로 통일한다(DB 설계 §1.2).
-- ----------------------------------------------------------------
-- 시스템 소유자는 owner_id=0 고정(NOT NULL). NULL<>NULL로 인한 중복 생성을 봉쇄한다.
CREATE TABLE accounts (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_type  VARCHAR(8)   NOT NULL CHECK (owner_type IN ('USER','SYSTEM')),
    owner_id    BIGINT       NOT NULL DEFAULT 0,
    purpose     VARCHAR(16)  NOT NULL
                CHECK (purpose IN ('AVAILABLE','LOCKED','FEE_INCOME','GENESIS','GATEWAY')),
    asset_id    SMALLINT     NOT NULL REFERENCES assets(id),
    shard       SMALLINT     NOT NULL DEFAULT 0,
    -- 소스계정(GENESIS/GATEWAY)은 자산 총합=0 불변식상 음수 잔고가 정상이다.
    -- purpose 파생 STORED 생성열이 balances 행에 복제되어 CHECK 완화의 근거가 된다.
    allow_negative BOOLEAN   GENERATED ALWAYS AS (purpose IN ('GENESIS','GATEWAY')) STORED,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (owner_type, owner_id, purpose, asset_id, shard),
    -- ledger_entries 복합 FK 대상. 계정과 분개의 자산 라벨 정합을 물리 강제한다.
    UNIQUE (id, asset_id),
    CONSTRAINT chk_user_purpose  CHECK (owner_type <> 'USER'   OR purpose IN ('AVAILABLE','LOCKED')),
    CONSTRAINT chk_sys_owner     CHECK (owner_type <> 'SYSTEM' OR owner_id = 0)
);

-- balance>=0 이 이중지출/과다락의 최종 방어선(P0-3). 소스계정만 allow_negative로 완화.
CREATE TABLE balances (
    account_id     BIGINT       PRIMARY KEY REFERENCES accounts(id),
    balance        NUMERIC(40,18) NOT NULL DEFAULT 0,
    allow_negative BOOLEAN      NOT NULL DEFAULT false,
    version        BIGINT       NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_balance_nonneg CHECK (balance >= 0 OR allow_negative)
);

-- 계정과 잔고의 1:1을 물리 강제. 차감/가산 UPDATE가 항상 대상 행을 만나 CHECK가 발동한다.
-- SECURITY DEFINER: app_role은 balances 직접 DML이 없으므로 트리거가 소유자 권한으로 삽입한다.
CREATE OR REPLACE FUNCTION create_balance_for_account() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
    INSERT INTO balances (account_id, allow_negative)
    VALUES (NEW.id, NEW.allow_negative);
    RETURN NEW;
END $$;
CREATE TRIGGER trg_account_balance AFTER INSERT ON accounts
    FOR EACH ROW EXECUTE FUNCTION create_balance_for_account();

-- 사용자 available/locked 피벗 뷰 (조회 편의)
CREATE VIEW user_balances AS
SELECT a.owner_id AS user_id, a.asset_id,
       SUM(CASE WHEN a.purpose='AVAILABLE' THEN b.balance ELSE 0 END) AS available,
       SUM(CASE WHEN a.purpose='LOCKED'    THEN b.balance ELSE 0 END) AS locked
FROM accounts a JOIN balances b ON b.account_id = a.id
WHERE a.owner_type='USER'
GROUP BY a.owner_id, a.asset_id;

-- ----------------------------------------------------------------
-- 3. 주문 / 체결
-- ----------------------------------------------------------------
CREATE TABLE orders (
    -- 주문 id는 항상 시퀀서 사전채번 값(command_journal.assigned_order_id)을 명시 삽입한다.
    -- IDENTITY를 두면 명시 삽입 계약이 모호해지고 자동채번 오삽입이 무음 통과하므로
    -- 시퀀스 자체를 제거한다(P1). orders.id = assigned_order_id = trades.maker/taker_order_id.
    id              BIGINT       PRIMARY KEY,
    -- 사전 발급 규약(P0-4): 명령 접수 전 API 계층이 발급한 UUID를 command payload에 박제하고
    -- 그 값을 명시 삽입한다. DB 자동 발급(DEFAULT)을 두면 재생 시 매번 달라져 결정론이 깨진다.
    external_id     UUID         NOT NULL UNIQUE,
    user_id         BIGINT       NOT NULL REFERENCES users(id),
    market_id       SMALLINT     NOT NULL REFERENCES markets(id),
    client_order_id VARCHAR(36),
    side            VARCHAR(4)   NOT NULL CHECK (side IN ('BUY','SELL')),
    ord_type        VARCHAR(8)   NOT NULL CHECK (ord_type IN ('LIMIT','MARKET','BEST')),
    time_in_force   VARCHAR(10)  NOT NULL DEFAULT 'GTC'
                    CHECK (time_in_force IN ('GTC','IOC','FOK','POST_ONLY')),
    price           NUMERIC(40,18) CHECK (price IS NULL OR price > 0),
    quantity        NUMERIC(40,18) CHECK (quantity IS NULL OR quantity > 0),
    quote_order_qty NUMERIC(40,18) CHECK (quote_order_qty IS NULL OR quote_order_qty > 0),
    filled_qty      NUMERIC(40,18) NOT NULL DEFAULT 0 CHECK (filled_qty >= 0),
    remaining_qty   NUMERIC(40,18) CHECK (remaining_qty IS NULL OR remaining_qty >= 0),
    -- STP Decrement & Cancel 부분 감액 누계(DB 설계 v2.8). 체결 없이 제거된 수량이 여기
    -- 쌓여야 열린 주문에서도 보존식 CHECK가 성립한다. 금액기반(notional) 주문은 사용 안 함.
    canceled_qty    NUMERIC(40,18) NOT NULL DEFAULT 0 CHECK (canceled_qty >= 0),
    executed_quote  NUMERIC(40,18) NOT NULL DEFAULT 0 CHECK (executed_quote >= 0),
    locked_account_id BIGINT     REFERENCES accounts(id),
    -- 과다 환급/음수 락 방지 최종 방어선(P0-3). 부분 체결의 마지막 체결은 계산값이 아니라
    -- 이 실잔여를 전부 소비 또는 환급해 잔차를 남기지 않는다(정산 계층 규약).
    locked_amount   NUMERIC(40,18) NOT NULL DEFAULT 0 CHECK (locked_amount >= 0),
    -- 정본 경로는 disposition 확정 후 OPEN 또는 REJECTED를 항상 명시 삽입한다.
    -- PENDING은 status를 누락한 비정본 INSERT가 열린 주문으로 오인되지 않게 하는 방어적 기본값(v2.5).
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING','OPEN','PARTIAL','FILLED','CANCELED','REJECTED')),
    reject_reason   VARCHAR(48),
    version         BIGINT       NOT NULL DEFAULT 0,
    command_seq     BIGINT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 종결상태(CANCELED/REJECTED)는 remaining_qty=0으로 정리되므로 보존식에서 면제한다.
    -- 면제가 없으면 취소 시 remaining_qty=0 대입이 CHECK 위반(23514)으로 롤백된다.
    -- canceled_qty 항은 STP 부분 감액이 열린 주문의 보존식을 깨지 않게 한다(v2.8).
    CONSTRAINT chk_qty_conservation CHECK (
        status IN ('CANCELED','REJECTED')
        OR quantity IS NULL OR remaining_qty IS NULL
        OR filled_qty + remaining_qty + canceled_qty = quantity),
    CONSTRAINT chk_limit_price CHECK (ord_type <> 'LIMIT' OR price IS NOT NULL),
    CONSTRAINT chk_order_shape CHECK (
        (ord_type = 'MARKET' AND side = 'BUY'  AND quote_order_qty IS NOT NULL)
        OR (quantity IS NOT NULL)
    ),
    -- P1 상태 보강. FILLED 종결이면 잔량 0이어야 하고, MARKET은 POST_ONLY와 양립 불가.
    CONSTRAINT chk_filled_no_remaining CHECK (
        status <> 'FILLED' OR remaining_qty = 0),
    CONSTRAINT chk_market_no_postonly CHECK (
        ord_type <> 'MARKET' OR time_in_force <> 'POST_ONLY'),
    -- MARKET BUY는 quantity와 quote_order_qty 중 정확히 하나만 지정한다.
    CONSTRAINT chk_market_buy_qty_xor CHECK (
        NOT (ord_type = 'MARKET' AND side = 'BUY')
        OR ((quantity IS NULL) <> (quote_order_qty IS NULL))),
    -- quote_order_qty는 MARKET 전용. LIMIT/BEST에 섞이면 정산 해석이 갈린다.
    CONSTRAINT chk_quote_qty_market_only CHECK (
        ord_type = 'MARKET' OR quote_order_qty IS NULL)
);
CREATE UNIQUE INDEX uq_orders_client ON orders (user_id, client_order_id)
    WHERE client_order_id IS NOT NULL;
CREATE INDEX idx_orderbook_open ON orders (market_id, side, price, created_at)
    INCLUDE (remaining_qty, user_id) WHERE status IN ('OPEN','PARTIAL');
CREATE INDEX idx_orders_user ON orders (user_id, status, created_at DESC);

-- 체결. 시간 range 파티션 + DEFAULT 파티션(소진 시 흡수).
-- trade_id = event_seq 규약(P0-4): id는 자동채번이 아니라 event_journal.event_seq를 명시 삽입한다.
-- executed_at/created_at은 현재시각이 아니라 command.engine_ts 파생값을 명시 삽입한다(재생 결정론).
CREATE TABLE trades (
    id             BIGINT       NOT NULL,       -- = event_journal.event_seq
    market_id      SMALLINT     NOT NULL REFERENCES markets(id),
    maker_order_id BIGINT       NOT NULL,      -- FK 생략(파티션/핫패스). 고아 탐지 쿼리로 보증
    taker_order_id BIGINT       NOT NULL,
    maker_user_id  BIGINT       NOT NULL,
    taker_user_id  BIGINT       NOT NULL,
    taker_side     VARCHAR(4)   NOT NULL CHECK (taker_side IN ('BUY','SELL')),
    price          NUMERIC(40,18) NOT NULL CHECK (price > 0),
    quantity       NUMERIC(40,18) NOT NULL CHECK (quantity > 0),
    maker_fee      NUMERIC(40,18) NOT NULL DEFAULT 0 CHECK (maker_fee >= 0),
    taker_fee      NUMERIC(40,18) NOT NULL DEFAULT 0 CHECK (taker_fee >= 0),
    fencing_epoch  BIGINT       NOT NULL,       -- 기록 시점 리더 epoch(P0-1)
    executed_at    TIMESTAMPTZ  NOT NULL,       -- engine_ts 파생. DB now() 금지
    created_at     TIMESTAMPTZ  NOT NULL,       -- engine_ts 파생(파티션 키). DEFAULT 없음
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);
CREATE INDEX idx_trades_market_time ON trades (market_id, created_at DESC);
CREATE INDEX idx_trades_taker_time  ON trades (taker_user_id, created_at DESC);
CREATE INDEX idx_trades_maker_time  ON trades (maker_user_id, created_at DESC);
-- 초기 파티션은 partman 명명 규칙(_pYYYY_MM)에 맞춘 부트스트랩. V2의 create_parent가 채택한다.
CREATE TABLE trades_p2026_07 PARTITION OF trades FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE trades_p2026_08 PARTITION OF trades FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE trades_default PARTITION OF trades DEFAULT;

-- 파티션드 UNIQUE는 파티션 키 포함이 강제라 id(=event_seq) 전역 UNIQUE 불가.
-- 아래 trades INSERT 트리거가 이 비파티션 레지스트리에 자동 기입해 PK로 중복을 차단한다.
-- 앱 규율이 아니라 트리거 강제라, created_at이 다른 동일 id 재삽입도 23505로 막힌다.
CREATE TABLE trade_seq_registry (
    seq              BIGINT      PRIMARY KEY,   -- = trades.id = event_seq
    trade_created_at TIMESTAMPTZ NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- SECURITY DEFINER: app_role은 레지스트리 직접 INSERT가 없다(선점 삽입으로 정상 기록 차단 방지).
CREATE OR REPLACE FUNCTION register_trade_seq() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
    INSERT INTO trade_seq_registry (seq, trade_created_at)
    VALUES (NEW.id, NEW.created_at);
    RETURN NEW;
END $$;
CREATE TRIGGER trg_trades_register AFTER INSERT ON trades
    FOR EACH ROW EXECUTE FUNCTION register_trade_seq();  -- PG13+: 파티션 전파

-- ----------------------------------------------------------------
-- 4. 복식부기 원장 (게시 일원화 + 파티션 + 봉인)
-- ----------------------------------------------------------------
-- 게시 경로는 post_ledger_transaction() 하나다(P0-2). 앱 롤은 이 세 테이블에 직접 DML이 없다.
-- 반올림 규약(P0-3, 정산 계층 계약):
--   * 모든 금액은 자산 scale로 먼저 양자화한 값만 entries로 전달한다(아래 함수가 재검증).
--   * 마지막 분개(leg)는 계산값이 아니라 나머지 분개의 음수 합으로 만들어
--     양자화 잔차를 FEE_INCOME 계정이 흡수한다. 자산별 합=0은 DB가 강제한다.
CREATE TABLE ledger_transactions (
    id                  BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    business_event_type VARCHAR(16)  NOT NULL
                        CHECK (business_event_type IN
                               ('TRADE','RESERVE','RELEASE','TRANSFER','GENESIS','REVERSAL')),
    business_event_id   BIGINT       NOT NULL,
    status              VARCHAR(8)   NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','SEALED')),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sealed_at           TIMESTAMPTZ,
    -- 멱등 키(P1): 동일 비즈니스 이벤트의 이중 게시를 물리 차단한다.
    CONSTRAINT uq_ledger_business_event UNIQUE (business_event_type, business_event_id),
    -- 봉인 시각과 상태의 일관성(P0-2)
    CONSTRAINT chk_sealed_at_consistency CHECK ((status = 'SEALED') = (sealed_at IS NOT NULL))
);

CREATE TABLE ledger_entries (
    id             BIGINT       GENERATED ALWAYS AS IDENTITY,
    transaction_id BIGINT       NOT NULL REFERENCES ledger_transactions(id),
    account_id     BIGINT       NOT NULL REFERENCES accounts(id),
    asset_id       SMALLINT     NOT NULL REFERENCES assets(id),
    amount         NUMERIC(40,18) NOT NULL CHECK (amount <> 0),   -- +입금/-출금
    entry_type     VARCHAR(24)  NOT NULL
                   CHECK (entry_type IN ('DEPOSIT','WITHDRAW','TRADE','FEE',
                                         'RESERVE','RELEASE','GENESIS','REVERSAL')),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at),
    -- 오표기 자산 분개는 페어로 넣어도 합=0이 유지되어 합계 검증으로 탐지 불가하다.
    -- 자산 라벨 무결성은 이 복합 FK가 유일한 방어선이다(v2.2).
    FOREIGN KEY (account_id, asset_id) REFERENCES accounts (id, asset_id)
) PARTITION BY RANGE (created_at);
CREATE INDEX idx_ledger_account_time ON ledger_entries (account_id, created_at DESC)
    INCLUDE (amount, asset_id, entry_type);
CREATE INDEX idx_ledger_txn ON ledger_entries (transaction_id);
CREATE TABLE ledger_entries_p2026_07 PARTITION OF ledger_entries FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE ledger_entries_p2026_08 PARTITION OF ledger_entries FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE ledger_entries_default PARTITION OF ledger_entries DEFAULT;

-- 봉인된 txn에는 추가 append 금지. 정본 게시는 post_ledger_transaction 단일 경로라
-- seal과 삽입 사이 경합 자체가 없지만, 하네스/수퍼유저 직접 삽입에 대한 백스톱으로 유지한다.
-- FOR SHARE: 일반 SELECT는 READ COMMITTED에서 seal UPDATE와 교차해 봉인 후 사후 삽입이
-- 성립할 수 있으므로, 헤더 행 공유 잠금으로 seal(배타 잠금)과 직렬화한다(P0-2).
CREATE OR REPLACE FUNCTION guard_sealed_txn() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE st TEXT;
BEGIN
    SELECT status INTO st FROM ledger_transactions WHERE id = NEW.transaction_id FOR SHARE;
    IF st IS NULL THEN
        RAISE EXCEPTION '존재하지 않는 거래(txn=%)에 분개 삽입 불가', NEW.transaction_id;
    END IF;
    IF st = 'SEALED' THEN
        RAISE EXCEPTION '봉인된 거래(txn=%)에 원장 분개 추가 불가', NEW.transaction_id;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_guard_sealed BEFORE INSERT ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION guard_sealed_txn();  -- PG13+: 파티션 전파

-- SEALED에서 OPEN으로의 역전이와 헤더 불변 컬럼 변경을 물리 차단한다(v2.4).
-- 역전이가 가능하면 봉인된 과거 거래에 균형 잡힌 페어를 사후삽입하고 재봉인하는 우회가 성립한다.
-- 빈 거래 봉인 금지(P0-2): 분개 0건인 txn의 SEALED 전환을 거부한다.
CREATE OR REPLACE FUNCTION guard_txn_transition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'SEALED' THEN
        RAISE EXCEPTION '봉인된 거래(txn=%)는 어떤 UPDATE도 불가(SEALED는 종결 상태, 역전이 금지)', OLD.id;
    END IF;
    IF NEW.business_event_type <> OLD.business_event_type
       OR NEW.business_event_id <> OLD.business_event_id
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION '거래 헤더 불변 컬럼(business_event_type/business_event_id/created_at) 변경 불가: txn=%', OLD.id;
    END IF;
    IF NEW.status = 'SEALED'
       AND NOT EXISTS (SELECT 1 FROM ledger_entries WHERE transaction_id = OLD.id) THEN
        RAISE EXCEPTION '빈 거래(txn=%) 봉인 불가: 분개 없는 SEALED 금지', OLD.id;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_guard_txn_transition BEFORE UPDATE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION guard_txn_transition();

-- 커밋 시점 백스톱: 손댄 txn 전부가 봉인+균형인지 DEFERRED로 최종 강제.
-- 위반 케이스가 COMMIT에서 롤백되는지는 통합테스트로 증명해야 한다(하네스 V3).
CREATE OR REPLACE FUNCTION assert_txn_sealed_balanced() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE st TEXT; bad INT;
BEGIN
    SELECT status INTO st FROM ledger_transactions WHERE id = NEW.transaction_id;
    IF st <> 'SEALED' THEN
        RAISE EXCEPTION '커밋 시점 미봉인 txn=%: post_ledger_transaction 경유 누락', NEW.transaction_id;
    END IF;
    SELECT count(*) INTO bad FROM (
        SELECT asset_id FROM ledger_entries WHERE transaction_id = NEW.transaction_id
        GROUP BY asset_id HAVING SUM(amount) <> 0
    ) t;
    IF bad > 0 THEN
        RAISE EXCEPTION '커밋 시점 불균형 txn=%', NEW.transaction_id;
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER trg_ledger_committed
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_txn_sealed_balanced();

-- append-only 강제: RULE의 무음 무시 대신 명시적 예외를 던진다.
CREATE OR REPLACE FUNCTION forbid_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'append-only 위반: %.%', TG_TABLE_NAME, TG_OP; END $$;

CREATE TRIGGER trg_ledger_noupd BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_ledger_notrunc BEFORE TRUNCATE ON ledger_entries
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
-- 헤더는 seal(OPEN에서 SEALED로)에 UPDATE가 필요하므로 남기되 단방향 전이만 허용.
-- DELETE/TRUNCATE는 전면 금지.
CREATE TRIGGER trg_ledger_txn_nodel BEFORE DELETE ON ledger_transactions
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_ledger_txn_notrunc BEFORE TRUNCATE ON ledger_transactions
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

-- ----------------------------------------------------------------
-- 4b. 원장 게시 단일 경로 (P0-2)
-- ----------------------------------------------------------------
-- 유일한 원장 게시 함수. 앱 롤은 이 함수 EXECUTE 권한만 가진다(직접 DML 회수).
-- 잠금 순서 규약(전역, 데드락 방지):
--   1) ledger_transactions 헤더 행(자체 생성분) FOR UPDATE
--   2) balances를 account_id 오름차순으로 행 잠금
-- 예약/취소/체결 등 원장을 만지는 모든 경로가 이 함수를 경유하므로 규약이 물리 강제된다.
-- 검증: entry 수 일치, 자산 scale 양자화(P0-3), 자산별 합=0, 빈 거래 금지.
-- 잔고 델타는 앱 전달값이 아니라 삽입된 entry에서 DB가 직접 집계한다.
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
        SELECT 1 FROM ledger_entries le JOIN assets a ON a.id = le.asset_id
        WHERE le.transaction_id = v_txn
          AND le.amount <> round(le.amount, a.scale::int)
    ) THEN
        RAISE EXCEPTION '양자화 위반: 자산 scale 초과 소수 존재(txn=%)', v_txn;
    END IF;

    -- 잔고 갱신. account_id 오름차순 UPDATE로 행 배타 잠금을 항상 같은 순서로 획득한다.
    -- balances CHECK(>=0)가 이중지출/과다락을 커밋 전에 차단한다.
    FOR r IN
        SELECT le.account_id, SUM(le.amount) AS delta
        FROM ledger_entries le
        WHERE le.transaction_id = v_txn
        GROUP BY le.account_id
        ORDER BY le.account_id
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
        SELECT 1 FROM ledger_entries
        WHERE transaction_id = v_txn
        GROUP BY asset_id HAVING SUM(amount) <> 0
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

    RETURN v_txn;
END $$;

-- 예약/해제 헬퍼(P1). 자금 이동은 전부 post_ledger_transaction 경유이므로
-- 오름차순 잠금 규약이 자동 적용된다. 앱은 직접 balances를 만지지 않는다.
-- p_business_event_id: 예약은 order_id, 해제는 해제를 유발한 이벤트의 식별자.
CREATE OR REPLACE FUNCTION reserve_funds(
    p_business_event_id BIGINT,
    p_from_account      BIGINT,   -- AVAILABLE 계정
    p_to_account        BIGINT,   -- LOCKED 계정
    p_asset             SMALLINT,
    p_amount            NUMERIC
) RETURNS BIGINT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
    IF p_amount <= 0 THEN RAISE EXCEPTION '예약 금액은 양수: %', p_amount; END IF;
    IF p_from_account = p_to_account THEN RAISE EXCEPTION '동일 계정 예약 불가'; END IF;
    RETURN post_ledger_transaction('RESERVE', p_business_event_id, jsonb_build_array(
        jsonb_build_object('account_id', p_from_account, 'asset_id', p_asset,
                           'amount', -p_amount, 'entry_type', 'RESERVE'),
        jsonb_build_object('account_id', p_to_account, 'asset_id', p_asset,
                           'amount',  p_amount, 'entry_type', 'RESERVE')));
END $$;

CREATE OR REPLACE FUNCTION release_funds(
    p_business_event_id BIGINT,
    p_from_account      BIGINT,   -- LOCKED 계정
    p_to_account        BIGINT,   -- AVAILABLE 계정
    p_asset             SMALLINT,
    p_amount            NUMERIC
) RETURNS BIGINT
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
    IF p_amount <= 0 THEN RAISE EXCEPTION '해제 금액은 양수: %', p_amount; END IF;
    IF p_from_account = p_to_account THEN RAISE EXCEPTION '동일 계정 해제 불가'; END IF;
    RETURN post_ledger_transaction('RELEASE', p_business_event_id, jsonb_build_array(
        jsonb_build_object('account_id', p_from_account, 'asset_id', p_asset,
                           'amount', -p_amount, 'entry_type', 'RELEASE'),
        jsonb_build_object('account_id', p_to_account, 'asset_id', p_asset,
                           'amount',  p_amount, 'entry_type', 'RELEASE')));
END $$;

-- 잔고 정합 검증 뷰(P1). FULL OUTER JOIN으로 고아 계정/고아 잔고까지 드러낸다.
-- mismatch=true 행이 하나라도 있으면 불변식 위반이다(하네스 감사 대상).
CREATE VIEW balance_ledger_check AS
SELECT COALESCE(a.id, b.account_id)                          AS account_id,
       (a.id IS NULL)                                        AS orphan_balance,
       (b.account_id IS NULL)                                AS missing_balance,
       b.balance,
       COALESCE(l.ledger_sum, 0)                             AS ledger_sum,
       (a.id IS NULL OR b.account_id IS NULL
        OR b.balance IS DISTINCT FROM COALESCE(l.ledger_sum, 0)) AS mismatch
FROM accounts a
FULL OUTER JOIN balances b ON b.account_id = a.id
LEFT JOIN (
    SELECT account_id, SUM(amount) AS ledger_sum
    FROM ledger_entries GROUP BY account_id
) l ON l.account_id = COALESCE(a.id, b.account_id);

-- ----------------------------------------------------------------
-- 5. HA / 복구
-- ----------------------------------------------------------------
-- gapless 채번(P0-5): 인메모리 카운터는 동시 in-flight 실패 시 영구 결번을 만든다.
-- 단일 행 UPDATE ... RETURNING으로 번호 확보와 저널 INSERT를 동일 DB 트랜잭션에 묶어
-- 할당과 커밋의 원자성을 보장한다. 실패하면 카운터 증가도 함께 롤백되어 결번이 없다.
CREATE TABLE journal_counters (
    name       TEXT   PRIMARY KEY,
    next_value BIGINT NOT NULL CHECK (next_value > 0)
);
INSERT INTO journal_counters (name, next_value) VALUES
    ('command_seq', 1),
    ('event_seq',   1);

-- 블록 채번 헬퍼. 반환값은 확보 블록의 시작 seq(연속 p_count개 사용 가능).
-- 행 배타 잠금이 채번을 직렬화하므로 별도 어드바이저리 락이 필요 없다.
CREATE OR REPLACE FUNCTION next_journal_value(p_name TEXT, p_count BIGINT DEFAULT 1)
RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE v_start BIGINT;
BEGIN
    IF p_count < 1 THEN RAISE EXCEPTION '채번 개수는 1 이상: %', p_count; END IF;
    UPDATE journal_counters
    SET next_value = next_value + p_count
    WHERE name = p_name
    RETURNING next_value - p_count INTO v_start;
    IF v_start IS NULL THEN
        RAISE EXCEPTION '미등록 카운터: %', p_name;
    END IF;
    RETURN v_start;
END $$;

-- 명령 저널. seq range 파티션, PK=seq(전역유일 gapless), fencing 결합.
CREATE TABLE command_journal (
    seq               BIGINT      NOT NULL,     -- journal_counters('command_seq') 채번
    market_id         SMALLINT    NOT NULL,
    -- AMEND는 정산/이벤트/재생 의미론이 미정의라 1차 범위에서 제외(v2.4).
    command_type      VARCHAR(16) NOT NULL CHECK (command_type IN ('NEW','CANCEL')),
    user_id           BIGINT      NOT NULL,
    client_order_id   VARCHAR(36),
    assigned_order_id BIGINT      NOT NULL,     -- 시퀀서 사전채번. 재생 결정론의 조인 키
    engine_ts         BIGINT      NOT NULL,     -- 시간 박제. 엔진 내부 now() 금지
    tie_break         BIGINT      NOT NULL,
    -- payload에 price,qty,side,tif,market_state,적용 수수료율,사전 발급 external_id 전부 박제(P0-4)
    payload           JSONB       NOT NULL,
    fencing_token     BIGINT      NOT NULL,     -- 기록 시점 리더 epoch
    checksum          BIGINT      NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (seq)                            -- 파티션 키 seq가 PK에 포함되어 전역유일 성립
) PARTITION BY RANGE (seq);
CREATE INDEX idx_cmd_market ON command_journal (market_id, seq);
-- NEW 멱등 재전송 판정과 by-client 재조회의 (user_id, client_order_id) 탐색용.
-- 없으면 신규 키(매치 0건)마다 전 파티션 스캔이 되어 append 핫패스가 저널 크기에 비례한다.
-- UNIQUE는 파티션 키(seq) 미포함이라 불가. 유일성 완결 강제는 orders uq_orders_client 담당.
CREATE INDEX idx_cmd_client_new ON command_journal (user_id, client_order_id)
    WHERE command_type = 'NEW';
-- partman 수치 파티션 명명 규칙: <table>_p<start_value>
CREATE TABLE command_journal_p0         PARTITION OF command_journal FOR VALUES FROM (0) TO (100000000);
CREATE TABLE command_journal_p100000000 PARTITION OF command_journal FOR VALUES FROM (100000000) TO (200000000);
CREATE TABLE command_journal_default PARTITION OF command_journal DEFAULT;
CREATE TRIGGER trg_cj_noupd BEFORE UPDATE OR DELETE ON command_journal
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_cj_notrunc BEFORE TRUNCATE ON command_journal
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

-- 이벤트 저널. event_seq 전역유일 gapless, trades.id의 진실 원천(trade_id=event_seq).
-- 적재 규칙(v2.2): 이벤트를 낳은 정산/상태변경 DB 트랜잭션과 동일 트랜잭션으로 INSERT한다.
-- 분리하면 크래시 창에서 gapless와 trades 대조가 깨진다.
CREATE TABLE event_journal (
    event_seq              BIGINT      NOT NULL, -- journal_counters('event_seq') 채번
    source_cmd_seq         BIGINT      NOT NULL, -- 논리참조(command_journal.seq)
    event_ordinal          INT         NOT NULL CHECK (event_ordinal >= 0),
    -- EXPIRED는 CANCELED로 일원화(v2.4). 별도 유형은 정산/상태 매핑 규칙이 없어 재생 해석이 갈린다.
    event_type             VARCHAR(16) NOT NULL
                           CHECK (event_type IN ('TRADE','ACCEPTED','REJECTED','CANCELED')),
    payload                JSONB       NOT NULL,
    payload_schema_version SMALLINT    NOT NULL DEFAULT 1,  -- 재생기 호환성 판별(P0-4)
    fencing_epoch          BIGINT      NOT NULL,            -- 기록 시점 리더 epoch(P0-1)
    created_at             TIMESTAMPTZ NOT NULL,            -- engine_ts 파생. DB now() 금지(P0-4)
    PRIMARY KEY (event_seq)
) PARTITION BY RANGE (event_seq);
CREATE INDEX idx_evt_src ON event_journal (source_cmd_seq);   -- 고아 event 탐지
CREATE TABLE event_journal_p0 PARTITION OF event_journal FOR VALUES FROM (0) TO (200000000);
CREATE TABLE event_journal_default PARTITION OF event_journal DEFAULT;
CREATE TRIGGER trg_ej_noupd BEFORE UPDATE OR DELETE ON event_journal
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_ej_notrunc BEFORE TRUNCATE ON event_journal
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

-- UNIQUE(source_cmd_seq, event_ordinal)의 물리 강제(P0-4).
-- event_journal은 event_seq range 파티션이라 파티션 키를 포함하지 않는 UNIQUE를 걸 수 없다.
-- 아래 event_journal INSERT 트리거가 이 비파티션 레지스트리에 자동 기입해
-- PK로 동일 명령 내 중복 방출을, event_seq UNIQUE로 seq 재사용을 차단한다.
CREATE TABLE event_emission_registry (
    source_cmd_seq BIGINT NOT NULL,
    event_ordinal  INT    NOT NULL CHECK (event_ordinal >= 0),
    event_seq      BIGINT NOT NULL UNIQUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (source_cmd_seq, event_ordinal)
);

-- SECURITY DEFINER: app_role은 레지스트리 직접 INSERT가 없다(선점 삽입으로 정상 방출 차단 방지).
CREATE OR REPLACE FUNCTION register_event_emission() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
    INSERT INTO event_emission_registry (source_cmd_seq, event_ordinal, event_seq)
    VALUES (NEW.source_cmd_seq, NEW.event_ordinal, NEW.event_seq);
    RETURN NEW;
END $$;
CREATE TRIGGER trg_ej_register AFTER INSERT ON event_journal
    FOR EACH ROW EXECUTE FUNCTION register_event_emission();  -- PG13+: 파티션 전파

-- 스냅샷. 복구 원천 계층이므로 append-only. 손상/구식 스냅샷은 삭제가 아니라 무시한다.
-- engine_version/config_hash/format_version(P0-4): 재생기가 스냅샷 채택 가능 여부를
-- 데이터만으로 판별한다. 버전/설정 불일치 스냅샷은 무시하고 저널 재생으로 폴백한다.
CREATE TABLE snapshots (
    snapshot_id      BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    market_id        SMALLINT    NOT NULL,
    last_applied_seq BIGINT      NOT NULL,
    state            BYTEA       NOT NULL,
    state_checksum   BIGINT      NOT NULL,
    engine_version   VARCHAR(64) NOT NULL,
    config_hash      VARCHAR(64) NOT NULL,
    format_version   SMALLINT    NOT NULL,
    fencing_epoch    BIGINT      NOT NULL,      -- 기록 시점 리더 epoch(P0-1)
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_snapshots_market ON snapshots (market_id, last_applied_seq DESC);
CREATE TRIGGER trg_snap_noupd BEFORE UPDATE OR DELETE ON snapshots
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_snap_notrunc BEFORE TRUNCATE ON snapshots
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

-- 트랜잭셔널 아웃박스. published_at UPDATE가 필요해 append-only 대상이 아니다.
CREATE TABLE outbox (
    id             BIGINT       GENERATED ALWAYS AS IDENTITY,
    event_id       UUID         NOT NULL DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(32)  NOT NULL,
    aggregate_id   VARCHAR(64)  NOT NULL,
    event_type     VARCHAR(48)  NOT NULL,
    payload        JSONB        NOT NULL,
    published_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);
CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
CREATE UNIQUE INDEX uq_outbox_event ON outbox (event_id, created_at);
CREATE TABLE outbox_p2026_07 PARTITION OF outbox FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE outbox_default PARTITION OF outbox DEFAULT;

-- 소비자 멱등 dedup
CREATE TABLE processed_events (
    consumer_group TEXT        NOT NULL,
    event_id       UUID        NOT NULL,
    processed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, event_id)
);

-- 리더 리스 + fencing. resource는 전 마켓 공용 단일 값 'matching-engine' 하나만 사용한다.
-- 마켓별 리스 분할은 전역 seq 채번 권위를 분열시키므로 금지(v2.2).
-- holder(P0-1): 호스트명이 아니라 프로세스 기동마다 새로 발급하는 UUID 문자열.
-- 같은 호스트에서 재기동한 구/신 프로세스를 구분하지 못하면 fencing이 무력화된다.
CREATE TABLE leader_lease (
    resource    TEXT        PRIMARY KEY,
    holder      TEXT        NOT NULL,
    epoch       BIGINT      NOT NULL DEFAULT 0,
    lease_until TIMESTAMPTZ NOT NULL
);
-- 부트스트랩 시드(v2.4): 승격이 조건부 UPDATE 단독이라 행이 선재해야 한다.
-- 빈 테이블이면 UPDATE가 항상 0행이라 최초 리더 선출이 영구 불가능해진다.
INSERT INTO leader_lease (resource, holder, epoch, lease_until)
VALUES ('matching-engine', '', 0, now() - interval '1 s');

-- fencing 공통 검증(P0-1). 자산을 바꾸는 모든 엔진 기원 트랜잭션(주문 접수/취소/체결 정산/
-- 이벤트 catch-up/스냅샷 저장)은 커밋 전 이 함수를 호출해 자신이 여전히 리더인지 확인한다.
-- clock_timestamp(): now()는 트랜잭션 시작 시각으로 고정되어 장기 트랜잭션에서
-- 만료된 리스를 유효로 오판한다. 실제 벽시계와 비교해야 한다.
-- 리스 행을 잠그지 않는 이유: 모든 정산 트랜잭션이 단일 행 잠금에 직렬화되고
-- 리스 갱신 UPDATE의 liveness가 깨진다. epoch 불일치 검출 + 저장된 fencing_epoch 컬럼
-- 감사가 잠금 없는 읽기의 보완이다.
CREATE OR REPLACE FUNCTION assert_current_leader(p_holder TEXT, p_epoch BIGINT)
RETURNS void
LANGUAGE plpgsql AS $$
DECLARE r RECORD;
BEGIN
    SELECT holder, epoch, lease_until INTO r
    FROM leader_lease WHERE resource = 'matching-engine';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'leader_lease 시드 행 없음: 부트스트랩 오류';
    END IF;
    IF r.holder <> p_holder OR r.epoch <> p_epoch THEN
        RAISE EXCEPTION 'fencing 위반: 현재 리더(holder=%, epoch=%)가 아님(요청 holder=%, epoch=%)',
            r.holder, r.epoch, p_holder, p_epoch;
    END IF;
    IF r.lease_until < clock_timestamp() THEN
        RAISE EXCEPTION 'fencing 위반: 리스 만료(lease_until=%, now=%)',
            r.lease_until, clock_timestamp();
    END IF;
END $$;

-- 승격: 만료된 리스만 탈취 가능. epoch 단조 증가가 fencing 토큰의 원천이다.
-- 반환값은 획득한 epoch, 실패 시 NULL.
CREATE OR REPLACE FUNCTION try_acquire_leader(p_holder TEXT, p_ttl INTERVAL)
RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE v_epoch BIGINT;
BEGIN
    UPDATE leader_lease
    SET holder = p_holder, epoch = epoch + 1,
        lease_until = clock_timestamp() + p_ttl
    WHERE resource = 'matching-engine'
      AND lease_until < clock_timestamp()
    RETURNING epoch INTO v_epoch;
    RETURN v_epoch;
END $$;

-- 갱신: holder+epoch 일치하고 아직 만료 전인 경우만. 만료 후 갱신은 재승격 경로로만 가능.
CREATE OR REPLACE FUNCTION renew_leader_lease(p_holder TEXT, p_epoch BIGINT, p_ttl INTERVAL)
RETURNS BOOLEAN
LANGUAGE plpgsql AS $$
BEGIN
    UPDATE leader_lease
    SET lease_until = clock_timestamp() + p_ttl
    WHERE resource = 'matching-engine'
      AND holder = p_holder AND epoch = p_epoch
      AND lease_until >= clock_timestamp();
    RETURN FOUND;
END $$;

-- API 멱등성 키
CREATE TABLE idempotency_keys (
    user_id         BIGINT       NOT NULL,
    idem_key        VARCHAR(64)  NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    response_status INT,
    response_body   JSONB,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, idem_key)
);

-- ----------------------------------------------------------------
-- 6. updated_at 자동 갱신
-- ----------------------------------------------------------------
CREATE OR REPLACE FUNCTION touch_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN NEW.updated_at = now(); RETURN NEW; END $$;
CREATE TRIGGER trg_touch_users     BEFORE UPDATE ON users     FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE TRIGGER trg_touch_orders    BEFORE UPDATE ON orders    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE TRIGGER trg_touch_markets   BEFORE UPDATE ON markets   FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE TRIGGER trg_touch_assets    BEFORE UPDATE ON assets    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
CREATE TRIGGER trg_touch_balances  BEFORE UPDATE ON balances  FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

-- ----------------------------------------------------------------
-- 7. 권한 경계 (P0-2, P0-7. 롤 생성은 V0__roles.sql)
-- ----------------------------------------------------------------
-- 존재 검사로 감싸 빈 PG17 단독 적용성을 지킨다(정상 번들에서는 V0가 선행 생성).
-- 핵심: app_role은 ledger_transactions/ledger_entries/balances에 DML이 없다.
-- 원장 게시와 잔고 갱신은 SECURITY DEFINER post_ledger_transaction 경유가 유일 경로다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_role') THEN
        GRANT USAGE ON SCHEMA public TO app_role;
        GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO app_role;

        -- 운영 테이블: 필요한 동사만
        GRANT SELECT, INSERT, UPDATE ON assets, markets, users, api_keys, orders,
                                        outbox, idempotency_keys TO app_role;
        GRANT SELECT, INSERT ON accounts, trades, command_journal, event_journal,
                                snapshots, processed_events TO app_role;
        -- 레지스트리는 트리거 자동 기입 전용. 직접 INSERT는 seq 선점 우회가 되므로 읽기만 부여
        GRANT SELECT ON trade_seq_registry, event_emission_registry TO app_role;
        GRANT SELECT, UPDATE ON leader_lease, journal_counters TO app_role;
        GRANT SELECT ON user_balances, balance_ledger_check TO app_role;

        -- 원장/잔고: 읽기만. 직접 DML 회수(게시 일원화의 물리 강제)
        GRANT SELECT ON ledger_transactions, ledger_entries, balances TO app_role;
        REVOKE INSERT, UPDATE, DELETE, TRUNCATE
            ON ledger_transactions, ledger_entries, balances FROM app_role;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'harness_role') THEN
        GRANT USAGE ON SCHEMA public TO harness_role;
        GRANT SELECT ON ALL TABLES IN SCHEMA public TO harness_role;
    END IF;
END $$;

-- SECURITY DEFINER 함수는 PUBLIC 기본 EXECUTE를 회수하고 명시 부여한다.
REVOKE ALL ON FUNCTION post_ledger_transaction(VARCHAR, BIGINT, JSONB) FROM PUBLIC;
REVOKE ALL ON FUNCTION reserve_funds(BIGINT, BIGINT, BIGINT, SMALLINT, NUMERIC) FROM PUBLIC;
REVOKE ALL ON FUNCTION release_funds(BIGINT, BIGINT, BIGINT, SMALLINT, NUMERIC) FROM PUBLIC;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_role') THEN
        GRANT EXECUTE ON FUNCTION
            post_ledger_transaction(VARCHAR, BIGINT, JSONB),
            reserve_funds(BIGINT, BIGINT, BIGINT, SMALLINT, NUMERIC),
            release_funds(BIGINT, BIGINT, BIGINT, SMALLINT, NUMERIC),
            assert_current_leader(TEXT, BIGINT),
            try_acquire_leader(TEXT, INTERVAL),
            renew_leader_lease(TEXT, BIGINT, INTERVAL),
            next_journal_value(TEXT, BIGINT)
        TO app_role;
    END IF;
END $$;
