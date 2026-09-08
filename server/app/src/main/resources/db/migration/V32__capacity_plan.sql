-- 앱 계층의 희망 용량을 적재한다. 관리자가 버튼으로 바꾸는 값이 여기 남는다.
--
-- 왜 DB 인가.
--
-- 이 값은 계약이지 관측이 아니다. "몇 대가 있어야 하는가" 이고 "몇 대가 있는가" 가 아니다.
-- 둘을 나란히 놓아야 어긋남이 보인다. 계약 3 대에 관측 2 대면 증설이 실패한 것이고,
-- 계약 2 대에 관측 3 대면 축소가 실패해 비용이 새는 중이다. 관측만 보면 둘 다 안 보인다.
--
-- 메모리에 두지 않는 이유는 셋이다. 재기동에 사라지고, 노드마다 값이 갈리고, 누가 언제 바꿨는지가
-- 남지 않는다. 마지막이 특히 중요하다. 용량 변경은 비용을 쓰는 조치이므로 감사 대상이다.
-- 감사 자체는 admin_audit_log 가 지고 이 표는 현재 계약값만 든다.
--
-- 왜 tier 로 나누는가.
--
-- 확장 축이 하나가 아니기 때문이다. 다만 지금 늘릴 수 있는 것은 조회를 받는 앱 계층뿐이다.
-- 쓰기는 매칭엔진이 단일 라이터라 노드를 늘려도 천장이 오르지 않고(2026-09-07 실측: 1 대에서
-- 2 대로 늘려도 56.8/s 에서 54.3/s), DB 는 primary 가 하나여야 한다. 그래서 지금은 행이 하나다.
-- 표를 tier 로 연 것은 나중에 축이 늘 때 스키마를 안 바꾸기 위해서다.
--
-- 대수의 뜻.
--
-- 앱 노드 전체 대수다. 리더 1 대를 포함한다. 관리자가 화면에서 보는 수와 같아야 하므로
-- 조회 전용 노드 수가 아니라 총 대수로 센다. 리더는 항상 1 대이므로 조회 전용 노드 수는
-- 이 값에서 1 을 뺀 값이다.

CREATE TABLE IF NOT EXISTS capacity_plan (
    tier          TEXT        PRIMARY KEY,
    min_nodes     INT         NOT NULL,
    desired_nodes INT         NOT NULL,
    max_nodes     INT         NOT NULL,
    updated_by    TEXT,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- 경계를 DB 가 강제한다. 화면과 컨트롤러가 각각 검사하면 한쪽만 고쳐질 때 갈린다.
    -- 최소가 2 이상인 것은 이중화가 계약이기 때문이다. 1 대로 내려가면 노드 하나를 잃는 순간
    -- 조회가 통째로 멈춘다.
    CONSTRAINT chk_capacity_bounds
        CHECK (min_nodes >= 2 AND min_nodes <= desired_nodes AND desired_nodes <= max_nodes)
);

COMMENT ON TABLE capacity_plan IS
'앱 계층 희망 용량(V32). 계약값이며 관측값이 아니다. 변경 감사는 admin_audit_log 가 진다.';
COMMENT ON COLUMN capacity_plan.desired_nodes IS
'리더를 포함한 총 앱 노드 수. 조회 전용 노드 수는 이 값에서 1 을 뺀 값이다.';

-- 초기값은 정본 6.5 노드 사이징의 상시 구성이다. app-1 과 app-2 두 대다.
-- 최대 3 은 ADR 0015 가 정한 운영값이고, 세 번째 노드는 부하 구간에만 존재한다.
INSERT INTO capacity_plan (tier, min_nodes, desired_nodes, max_nodes, updated_by)
VALUES ('app', 2, 2, 3, 'migration:V32')
ON CONFLICT (tier) DO NOTHING;

GRANT SELECT, UPDATE ON capacity_plan TO app_role;
