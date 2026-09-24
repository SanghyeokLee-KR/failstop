-- V49__revoke_lease_update_journal_insert.sql (ADR 0034 결정한 값 17). S2 이미지에는 넣지 않는다.
-- S2 이미지가 관문(앱 역할 종단 주행과 되돌림 주행 포함)을 통과한 뒤의 다음 이미지에 넣는다. 그래야 V48 만
-- 적용된 구간이 생겨 S2 이미지를 S2 이전 이미지로 권한 조치 없이 되돌릴 수 있다. 이 파일 뒤에 S2 이전 이미지로
-- 되돌리려면 소유자가 먼저 두 권한을 다시 준다(GRANT UPDATE ON leader_lease, GRANT INSERT ON command_journal).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_role') THEN
        -- 리스 행을 바꾸거나 행 잠금을 거는 일은 V48 의 소유자 권한 함수로만 한다.
        REVOKE UPDATE ON leader_lease FROM app_role;
        -- 명령 저널은 리스 판정과 KEY SHARE 를 싣는 append_command_journal_row 로만 쓴다.
        -- 파티션 잎에는 app_role 권한이 없으므로(잎은 부모 권한을 물려받지 않는다) 부모에서 회수하면 닫힌다.
        REVOKE INSERT ON command_journal FROM app_role;
    END IF;
END $$;
