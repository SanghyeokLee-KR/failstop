-- ============================================================================
-- V14__transfer_requests.sql
-- 원천: 백엔드_DB_설계.md §5 입출금 요청 수명주기(V1 범위 외로 유보돼 있던 DDL의 승격).
-- 목적: 원화 입출금 요청의 상태 기계와 멱등 키. 자금 이동 자체는 여기서 하지 않고
--       기존 post_ledger_transaction·reserve_funds·release_funds 단일 경로가 담당한다.
--       이 테이블은 "누가 무엇을 요청했고 지금 어느 단계인가"만 들고 있다.
--
-- 상태 전이(API 설계 §4-E):
--   입금  REQUESTED -> COMPLETED            (즉시 확정, 한 트랜잭션)
--   출금  REQUESTED -> COMPLETED            (운영자 승인, LOCKED to GATEWAY)
--         REQUESTED -> CANCELED             (운영자 거절 또는 본인 취소, 잠금 해제)
--   PENDING·FAILED는 스키마에 남기되 V14 흐름에서는 만들지 않는다. 외부 게이트웨이가
--   붙는 단계에서 쓸 자리다. 지금 지우면 그때 CHECK를 다시 고쳐야 한다.
--
-- 자산 제한: 구현은 KRW만 받는다. 스키마로 막지 않는 이유는 코인 입출금이 영구 금지가
--   아니라 정산 직렬화 설계가 선행돼야 하는 유보이기 때문이다(V13 position_cost 계층
--   논증, DB 설계 §3 입출금 자산 제한 절). 스키마에 CHECK로 박으면 그 설계가 끝났을 때
--   마이그레이션을 또 만들어야 한다. 제한은 앱 계층(TransferService)이 강제한다.
-- ============================================================================
SET timezone = 'UTC';

CREATE TABLE transfer_requests (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    external_id   UUID         NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    user_id       BIGINT       NOT NULL REFERENCES users(id),
    asset_id      SMALLINT     NOT NULL REFERENCES assets(id),
    direction     VARCHAR(8)   NOT NULL CHECK (direction IN ('DEPOSIT','WITHDRAW')),
    amount        NUMERIC(40,18) NOT NULL CHECK (amount > 0),
    status        VARCHAR(12)  NOT NULL DEFAULT 'REQUESTED'
                  CHECK (status IN ('REQUESTED','PENDING','COMPLETED','FAILED','CANCELED')),
    idem_key      VARCHAR(64)  NOT NULL,
    ledger_txn_id BIGINT       REFERENCES ledger_transactions(id),  -- 확정 시 연결
    -- 거절 사유. 운영자가 남기며 사용자 내역 화면에 그대로 노출된다
    reason        VARCHAR(200),
    -- 처리한 운영자. 감사 추적용이며 사용자에게는 노출하지 않는다
    decided_by    VARCHAR(64),
    decided_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (user_id, idem_key),   -- 재시도 중복 요청 차단
    -- 처리자가 있으면 처리 시각도 있어야 한다. 역은 강제하지 않는다. 본인 취소와 입금 자동
    -- 확정은 운영자가 없는 정상 전이라 decided_by가 비고 시각만 남는다. 양방향으로 묶으면
    -- 그 두 경로가 23514로 막힌다. 해석 불가한 조합은 "처리자는 있는데 언제인지 모른다" 쪽뿐이다
    CONSTRAINT chk_transfer_decided CHECK (decided_by IS NULL OR decided_at IS NOT NULL)
);

-- 운영자 승인 큐 조회. 부분 인덱스라 확정된 과거 요청이 인덱스를 키우지 않는다
CREATE INDEX idx_transfer_pending ON transfer_requests (status, created_at)
    WHERE status IN ('REQUESTED','PENDING');
-- 사용자 내역 조회(커서 페이지네이션 §2-6). id DESC 단일 축이라 정렬 키가 유일하다
CREATE INDEX idx_transfer_user ON transfer_requests (user_id, id DESC);

-- ----------------------------------------------------------------
-- 권한 경계
-- ----------------------------------------------------------------
-- app_role은 이 테이블에 SELECT·INSERT·UPDATE만 갖는다. 자금 이동은 여전히
-- post_ledger_transaction EXECUTE 유일 경로다(V1 §7 경계 유지).
-- DELETE를 주지 않는 이유는 취소가 상태 전이(CANCELED)이지 행 삭제가 아니기 때문이다.
-- 삭제로 처리하면 감사에서 "요청이 있었다"는 사실 자체가 사라진다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_role') THEN
        GRANT SELECT, INSERT, UPDATE ON transfer_requests TO app_role;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'harness_role') THEN
        GRANT SELECT ON transfer_requests TO harness_role;
    END IF;
END $$;

-- ----------------------------------------------------------------
-- 불변식 검증 뷰
-- ----------------------------------------------------------------
-- 확정된 요청은 원장 트랜잭션과 1:1로 묶여야 한다. 끊어진 행은 "돈은 움직였는데 기록이
-- 없다" 또는 "기록은 있는데 돈이 안 움직였다" 둘 중 하나이며, 자산별 원장 합=0으로는
-- 잡히지 않는다(합은 맞고 귀속만 틀린 상태). 상시 게이트가 이 뷰를 0행으로 확인한다.
CREATE OR REPLACE VIEW transfer_settlement_check AS
SELECT id, external_id, user_id, direction, status, ledger_txn_id
FROM transfer_requests
WHERE (status = 'COMPLETED' AND ledger_txn_id IS NULL)
   OR (status IN ('REQUESTED','CANCELED') AND ledger_txn_id IS NOT NULL);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'harness_role') THEN
        GRANT SELECT ON transfer_settlement_check TO harness_role;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_role') THEN
        GRANT SELECT ON transfer_settlement_check TO app_role;
    END IF;
END $$;
