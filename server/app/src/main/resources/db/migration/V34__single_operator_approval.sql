-- 1인 승인으로 바꾼다. 승인 티켓 자체는 남기고 "발급자와 사용자가 달라야 한다" 는 조건만 뺀다.
--
-- 종전 판은 반결탁(2인 승인)을 걸었다. 그 전제가 이 운영 규모에서 성립하지 않는다.
-- 정본 8.2 가 이미 그 사실을 적어 두었다. "실제로 한 일은 야간 단독 운영 시간대에 정지
-- 명령을 봉쇄한 것" 이 그 문장이다. 곧 이 제약은 결탁을 막은 것이 아니라 1인 운영에서
-- 정지 명령 자체를 봉쇄했다. 막아야 할 것을 막지 못하면서 필요한 것을 막는 통제다.
--
-- 남는 방어는 일곱이다(정본 8.2). 승인 티켓 발급이라는 별도 단계, TTL 5분,
-- 단회 소각(CONSUMED), 액션·대상 바인딩, 요청자 바인딩, 역할 게이트,
-- append-only 감사다.
-- 사라지는 것은 "다른 사람의 승인" 한 겹뿐이고 그 사실을 ADR 0019 에 적었다.
--
-- 감사 이력은 건드리지 않는다. 이미 발급·소각된 행의 approver_operator_id 는 그대로 둔다.
-- 제약만 떼므로 과거 행의 의미가 바뀌지 않는다.

ALTER TABLE admin_approvals DROP CONSTRAINT IF EXISTS chk_approval_two_person;

COMMENT ON COLUMN admin_approvals.approver_operator_id IS
    '승인 발급 주체. 1인 승인이므로 requester_operator_id 와 같을 수 있다(V34, ADR 0019).';
