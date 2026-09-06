-- 봇 플릿 상태 발행 표. 봇 노드가 자기 상태를 적고 관리 평면이 읽는다.
--
-- 왜 필요한가. V22가 관리 평면에서 봇으로 가는 제어 방향만 이었다. 반대 방향이 없어
-- GET /admin/v1/sim/status가 인메모리 거버너 빈만 보고, 관리 평면에는 그 빈이 없으므로
-- 상시 running:false, target_ops:"0", markets:[]를 낸다. 실측(2026-09-06): 봇이 초당
-- 8.3건을 발행하는 동안 콘솔이 정지·활성 0대를 표시했다. 콘솔이 없는 사실을 단정한다.
--
-- 왜 표인가. V22가 명령 큐를 고른 이유가 그대로 적용된다. 관리·봇 JVM을 합치면 관리
-- 평면 물리 분리 계약이 깨지고, 공유 캐시는 배선돼 있지 않다. 같은 저장소에서 반대
-- 방향을 이으면 제어와 관측이 같은 규약을 쓴다.
--
-- 왜 갱신형인가. sim_commands는 이력이라 누적하지만 이 표는 현재 상태다. 이력을 쌓으면
-- 초당 한 행씩 늘고 판정에 쓰이지 않는다. node_id로 갱신해 노드당 한 행만 둔다.
--
-- 알려진 한계. node_id는 봇 JVM이 기동마다 새로 만드는 임의값이라 재기동이 행을 하나씩
-- 남긴다. 읽는 쪽이 observed_at 최신 행을 고르므로 판정은 흔들리지 않는다. 안정 식별자를
-- 도입하기 전까지는 재기동 횟수만큼 죽은 행이 남는다.
CREATE TABLE sim_fleet_state (
    node_id     VARCHAR(64)  PRIMARY KEY,
    running     BOOLEAN      NOT NULL,
    paused      BOOLEAN      NOT NULL,
    -- 거버너의 목표 OPS. 인메모리 원값이 double이라 소수를 보존한다.
    target_ops  NUMERIC(12,3) NOT NULL,
    -- 마켓별 스냅샷 원값. 서식은 관리 평면이 입힌다. 여기에 서식을 넣으면 로컬 경로와
    -- 폴백 경로가 서로 다른 자릿수를 낼 수 있다.
    markets     JSONB        NOT NULL,
    -- 신선도 판정 기준. 봇 노드가 죽어도 행은 남으므로 이 값 없이는 죽은 상태를 현재로 읽는다.
    observed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_sim_fleet_markets_array CHECK (jsonb_typeof(markets) = 'array')
);

COMMENT ON TABLE sim_fleet_state IS
    '봇 노드가 주기 발행하는 플릿 현재 상태. 관리 평면의 GET /admin/v1/sim/status 폴백 원천.';

-- DELETE는 주지 않는다. sim_commands·chaos_commands와 같은 규율이고, 이 표는 갱신형이라
-- 지울 이유가 없다. 봇 노드는 INSERT ... ON CONFLICT DO UPDATE 한 문장만 쓴다.
GRANT SELECT, INSERT, UPDATE ON sim_fleet_state TO app_role;
