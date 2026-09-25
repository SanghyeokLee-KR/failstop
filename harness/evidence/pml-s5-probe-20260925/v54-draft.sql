-- V54__order_cancel_requests.sql (ADR 0034 S5, DB 설계 §3.9·§5.4-d). 파티션 밖 취소를 그 파티션의 소유 노드에 넘기는 요청 표.
-- 이 노드가 접수 게이트를 열지 않은 파티션의 미체결 주문(마켓 없는 일괄 취소, 회원 폐쇄)을 요청자가 파티션마다 한 행으로 넣고
-- 기다린다. 소유 노드의 작업자는 그 파티션 기간의 epoch 로 행을 점유하고 보통의 취소 경로(저널 append 와 DB fencing)로 취소한
-- 뒤 결과를 적는다. 표는 작업 지시일 뿐 쓰기 권한 판정에 쓰이지 않는다. 판정은 종전처럼 DB 가 기록할 행의 마켓에서 도출한다.
-- 리스 행 외래키는 두지 않는다(V53 과 같은 이유. 외래키 검사가 리스 행에 KEY SHARE 를 잡아 획득과 줄을 서게 한다).
SET LOCAL statement_timeout = 0;
SET LOCAL lock_timeout = 0;

CREATE TABLE order_cancel_requests (
    id            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 한 요청(일괄 취소 한 번, 폐쇄 한 번)이 넣은 파티션 행들을 묶는다. 요청자가 이 값으로 자기 행을 모아 읽는다.
    request_id    UUID        NOT NULL,
    resource      TEXT        NOT NULL
                  CONSTRAINT chk_ocr_resource CHECK (resource IN ('matching-engine', 'matching-p0', 'matching-p1')),
    user_id       BIGINT      NOT NULL REFERENCES users(id),
    -- 요청 시점에 열려 있던 그 파티션 마켓의 주문 external_id. 작업자는 이 목록만 취소하고 뒤에 들어온 주문은 건드리지 않는다.
    order_ids     UUID[]      NOT NULL
                  CONSTRAINT chk_ocr_order_ids CHECK (cardinality(order_ids) BETWEEN 1 AND 500
                                                     AND array_position(order_ids, NULL) IS NULL),
    origin        VARCHAR(16) NOT NULL
                  CONSTRAINT chk_ocr_origin CHECK (origin IN ('cancel_all', 'member_close')),
    state         VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                  CONSTRAINT chk_ocr_state CHECK (state IN ('PENDING', 'CLAIMED', 'DONE', 'ABANDONED')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 점유한 노드의 holder 와 그 파티션 기간의 epoch. 더 큰 epoch 의 작업자만 끝나지 않은 점유를 다시 점유한다.
    claimed_by    VARCHAR(64),
    claimed_epoch BIGINT,
    claimed_at    TIMESTAMPTZ,
    finished_at   TIMESTAMPTZ,
    -- 작업자의 처리 결과(취소한 id, 원소별 실패 사유, 사유별 수). DONE 에만 있다.
    result        JSONB,
    CONSTRAINT chk_ocr_claim CHECK ((state IN ('CLAIMED', 'DONE'))
        = (claimed_by IS NOT NULL AND claimed_epoch IS NOT NULL AND claimed_at IS NOT NULL)),
    CONSTRAINT chk_ocr_finished CHECK ((state IN ('DONE', 'ABANDONED')) = (finished_at IS NOT NULL)),
    CONSTRAINT chk_ocr_result CHECK ((state = 'DONE') = (result IS NOT NULL))
);

-- 작업자의 점유 경로. 끝나지 않은 행만 파티션별로 오래된 순서로 집는다.
CREATE INDEX idx_ocr_open ON order_cancel_requests (resource, id) WHERE state IN ('PENDING', 'CLAIMED');
-- 요청자가 자기 요청의 행을 모아 읽고 포기하는 경로.
CREATE INDEX idx_ocr_request ON order_cancel_requests (request_id);

COMMENT ON TABLE order_cancel_requests IS
    '파티션 밖 취소 인계(ADR 0034 S5). 요청자가 넣고 그 파티션의 소유 노드가 점유해 처리한다. 쓰기 권한 판정과 무관하다';
COMMENT ON COLUMN order_cancel_requests.claimed_epoch IS
    '점유한 작업자 기간의 epoch. 끝나지 않은 점유는 더 큰 epoch 의 작업자만 다시 점유한다';

-- app_role 은 SELECT·INSERT·UPDATE 만 갖는다. 행을 지우지 않는다(끝난 행은 DONE·ABANDONED 로 남고 보존은 §3.2-a 갈래 C 다).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_role') THEN
        GRANT SELECT, INSERT, UPDATE ON order_cancel_requests TO app_role;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'harness_role') THEN
        GRANT SELECT ON order_cancel_requests TO harness_role;
    END IF;
END $$;
