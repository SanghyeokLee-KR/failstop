-- ----------------------------------------------------------------
-- 카오스 명령 큐 (DB 설계 v2.36, API 설계 §6-B, 하네스 CICD §6-2)
--
-- 승인된 주입 명령이 실제 주입으로 이어지려면 그 명령을 집어 가는 소비기가 있어야 한다.
-- 지금까지는 admin_audit_log만 남고 아무도 읽지 않았다.
--
-- 감사 로그를 큐로 쓰지 않는 이유가 둘이다.
--   1. admin_audit_log는 append-only다. V5의 forbid_mutation 트리거가 UPDATE·DELETE를
--      막으므로 "처리됨" 표시를 남길 자리가 없다.
--   2. 표시할 자리가 없으면 소비기 재시작이 같은 행을 다시 읽어 리더를 두 번 죽인다.
--      기록(무엇이 일어났나)과 작업 지시(무엇을 할 차례인가)는 다른 것이다.
--
-- at-most-once는 애플리케이션이 아니라 이 테이블이 보장한다. 소비기는
--   UPDATE ... WHERE id = (SELECT id ... FOR UPDATE SKIP LOCKED LIMIT 1)
-- 로 한 건을 점유한다. 소비기가 둘이어도 같은 행을 두 번 집지 못한다.
-- ----------------------------------------------------------------

CREATE TABLE chaos_commands (
    id            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 주입 응답이 돌려준 값. 콘솔·감사와 이 행을 잇는 유일한 열쇠라 유니크다.
    experiment_id UUID        NOT NULL UNIQUE,
    -- 승인 바인딩 action을 그대로 쓴다(chaos.kill_node·chaos.network·chaos.app·chaos.network.clear).
    action        VARCHAR(32) NOT NULL,
    -- 자원 식별자. kill_node는 컨테이너명, network는 toxic명, app은 assault명.
    -- 소비기가 셸 문자열로 보간해 docker 명령을 만든다. 메타문자가 들어오면 소비기 호스트에서
    -- 임의 명령이 실행되므로 표가 형태를 강제한다. 앱 검증이 빠져도 여기서 막힌다.
    target        VARCHAR(128) NOT NULL
                  CHECK (target ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$'),
    -- 실험 카탈로그 식별자(E1~E9). 소비기가 어떤 주입 스크립트를 부를지 여기서 가른다.
    experiment    VARCHAR(8),
    params        JSONB       NOT NULL DEFAULT '{}'::jsonb,
    requested_by  VARCHAR(64) NOT NULL,
    approval_id   BIGINT      REFERENCES admin_approvals(id),
    state         VARCHAR(16) NOT NULL DEFAULT 'PENDING'
                  CHECK (state IN ('PENDING','CLAIMED','EXECUTED','FAILED','SKIPPED')),
    claimed_by    VARCHAR(64),
    claimed_at    TIMESTAMPTZ,
    finished_at   TIMESTAMPTZ,
    -- 실행 증빙. 데모 대본이 요구하는 Docker event 상관관계가 이 값으로 성립한다
    -- (컨테이너 id, 실제 kill 시각, docker 출력 요약).
    result        TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- 종결 상태는 끝난 시각이 있어야 한다. 미종결에 finished_at이 있으면 그 반대도 막는다.
    CONSTRAINT chk_chaos_finished CHECK (
        (state IN ('EXECUTED','FAILED','SKIPPED')) = (finished_at IS NOT NULL))
);

-- 소비기 폴링 경로. 대기분만 오래된 순으로 집는다.
CREATE INDEX idx_chaos_commands_pending
    ON chaos_commands (created_at, id) WHERE state = 'PENDING';

-- 콘솔 이력 조회 축(V19 규약: 시각 DESC + id 보조 축).
CREATE INDEX idx_chaos_commands_keyset ON chaos_commands (created_at DESC, id DESC);

-- 관리 평면과 하네스 소비기가 같은 app_role로 붙는다. 감사 로그와 달리 UPDATE가 필요하다
-- (점유·종결 표시). append-only가 아니므로 forbid_mutation 트리거를 걸지 않는다.
GRANT SELECT, INSERT, UPDATE ON chaos_commands TO app_role;
