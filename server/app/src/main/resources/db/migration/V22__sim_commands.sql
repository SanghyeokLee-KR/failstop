-- 시뮬레이션 제어 명령 큐. 관리 평면이 봇 플릿을 제어하는 유일한 경로다.
--
-- 왜 필요한가. 봇 거버너(OpsGovernor·BotFleetGovernor)는 bots 프로파일 JVM의 인메모리
-- 상태다. 관리 평면은 별도 컨테이너로 물리 분리돼 있어(관리자 설계 §0) 그 빈이 없다.
-- 그래서 콘솔에서 시나리오 프리셋을 활성화하면 감사 로그와 fleet_present:false만 남고
-- 봇은 그대로다. 실측(2026-08-29): spike 프리셋이 반영되지 않았다.
--
-- 세 선택지 중 이것을 고른 이유.
--   관리·봇 JVM 합치기: 관리 평면 물리 분리 계약을 깬다. 관리 노드는 엔진도 꺼서
--     리더 경쟁에서 빠져 있는데, 합치면 페일오버 관찰이 오염된다.
--   거버너 상태 공유(Redis 등): 공유 저장소가 배선돼 있지 않다(E3이 SKIP인 이유).
--   명령 큐: 같은 저장소에서 같은 문제를 이미 이렇게 풀었다(V21 chaos_commands).
--     관리 평면은 의도를 쓰고 실행 주체가 폴링해 적용한다. 평면 분리를 지키면서
--     콘솔 조작이 실제 효과를 낸다.
--
-- chaos_commands와 다른 점. 카오스 주입은 호스트 docker 접근이 필요해 외부 소비기가
-- 집지만, 시뮬레이션 제어는 봇 JVM 자신이 대상이라 그 프로세스가 직접 폴링한다.
-- 별도 프로세스를 두면 봇 상태를 그 프로세스가 알 수 없다.
CREATE TABLE sim_commands (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 'scenario' | 'target_ops'. 확장 시 CHECK를 넓힌다.
    action      VARCHAR(32)  NOT NULL,
    -- scenario면 프리셋 이름, target_ops면 숫자 문자열.
    payload     VARCHAR(64)  NOT NULL,
    requested_by VARCHAR(64) NOT NULL,
    state       VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    applied_by  VARCHAR(64),
    applied_at  TIMESTAMPTZ,
    result      TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_sim_action CHECK (action IN ('scenario', 'target_ops')),
    CONSTRAINT chk_sim_state CHECK (state IN ('PENDING', 'APPLIED', 'FAILED')),
    -- 종결 상태와 시각을 묶는다. 한쪽만 있는 행은 소비기 중단 흔적이라 판정을 흐린다.
    CONSTRAINT chk_sim_finished CHECK ((state IN ('APPLIED', 'FAILED')) = (applied_at IS NOT NULL))
);

-- 폴링이 PENDING만 훑는다. 처리분이 쌓여도 스캔 비용이 늘지 않는다.
CREATE INDEX idx_sim_commands_pending ON sim_commands (id) WHERE state = 'PENDING';
