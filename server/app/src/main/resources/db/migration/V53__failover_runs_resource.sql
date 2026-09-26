-- V53__failover_runs_resource.sql (ADR 0034 S4). 페일오버 측정 이력에 그 사건의 리스 자원을 남긴다.
-- 파티션 모드(S7)에서는 한 사건이 파티션 하나만 옮기므로 old_epoch·new_epoch 만으로는 어느 파티션의 사건인지 가를 수 없다.
-- 널 허용 열이고 기본값이 없어 표를 다시 쓰지 않는다. 이 열 이전의 행은 NULL 이고 단일 자원(matching-engine) 시절의 사건이다.
-- 리스 행을 참조하는 외래키를 두지 않는다. 외래키 검사는 적재 때 리스 행에 KEY SHARE 를 잡아 획득과 줄을 서게 한다.
-- 표 수준 권한(V4 의 app_role INSERT·SELECT, harness_role SELECT)이 새 열에도 걸리므로 GRANT 를 다시 하지 않는다.
SET LOCAL statement_timeout = 0;
SET LOCAL lock_timeout = 0;
ALTER TABLE harness.failover_runs ADD COLUMN resource TEXT
    CONSTRAINT chk_failover_runs_resource
    CHECK (resource IS NULL OR resource IN ('matching-engine', 'matching-p0', 'matching-p1'));
COMMENT ON COLUMN harness.failover_runs.resource IS
    '그 사건이 옮긴 리스 자원(ADR 0034 S4). NULL 은 이 열을 더하기 전의 행이다';
