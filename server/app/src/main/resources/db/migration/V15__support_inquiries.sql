-- ============================================================================
-- V15__support_inquiries.sql
-- 원천: 백엔드_API_설계.md §4-G 문의 API.
-- 목적: 회원이 운영자에게 보내는 문의와 그 답변. 한 행이 문답 한 쌍을 들고 있다.
--
-- 스레드를 만들지 않는 이유는 답변이 컬럼으로 충분하기 때문이다. 별도 메시지 테이블을
-- 두면 정렬·읽음·알림이 따라붙고, 그 셋은 문답 한 번으로 끝나는 사례를 위해 지불하기에
-- 비싸다. 답변이 불충분하면 새 문의를 등록한다.
--
-- 코어 격리: 이 테이블은 원장·매칭·저널 어디에도 닿지 않는다. 자금이 움직이지 않으므로
-- post_ledger_transaction 경로와 무관하고, 손실돼도 코어 복구 RTO에 기여하지 않는다.
-- ============================================================================
SET timezone = 'UTC';

CREATE TABLE support_inquiries (
    id           BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    external_id  UUID         NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    user_id      BIGINT       NOT NULL REFERENCES users(id),
    category     VARCHAR(16)  NOT NULL
                 CHECK (category IN ('TRADE','TRANSFER','ACCOUNT','ETC')),
    title        VARCHAR(120) NOT NULL,
    body         TEXT         NOT NULL,
    -- OPEN과 ANSWERED뿐이다. 사용자 관점에서 답이 왔는지 아닌지 말고 알아야 할 상태가 없다.
    -- 운영자 내부 진행 상태를 노출해도 기다림의 성격이 바뀌지 않는다.
    status       VARCHAR(12)  NOT NULL DEFAULT 'OPEN'
                 CHECK (status IN ('OPEN','ANSWERED')),
    answer       TEXT,
    answered_by  VARCHAR(64),
    answered_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 답변 3필드는 함께 있거나 함께 없다. 셋 중 하나만 남은 행은 화면에서 해석 불가다
    CONSTRAINT chk_support_answer CHECK (
        (status = 'ANSWERED') = (answer IS NOT NULL)
        AND (answer IS NULL) = (answered_at IS NULL)
        AND (answer IS NULL) = (answered_by IS NULL)
    )
);

-- 운영자 대기 큐. 부분 인덱스라 답변 완료분이 인덱스를 키우지 않는다
CREATE INDEX idx_support_open ON support_inquiries (created_at)
    WHERE status = 'OPEN';
-- 사용자 내역 조회. id DESC 단일 축
CREATE INDEX idx_support_user ON support_inquiries (user_id, id DESC);

-- ----------------------------------------------------------------
-- 권한 경계
-- ----------------------------------------------------------------
-- DELETE를 주지 않는다. 문의 철회를 삭제로 처리하면 "물어본 적이 있다"는 사실이 사라져
-- 같은 질문이 반복될 때 운영자가 이력을 못 본다. 현재 철회 기능 자체가 없다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_role') THEN
        GRANT SELECT, INSERT, UPDATE ON support_inquiries TO app_role;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'harness_role') THEN
        GRANT SELECT ON support_inquiries TO harness_role;
    END IF;
END $$;
