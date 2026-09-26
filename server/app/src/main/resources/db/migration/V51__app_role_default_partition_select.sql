-- V51__app_role_default_partition_select.sql (ADR 0034 S3 관문 X1). 불변식 검증기의 C15·C27 은 DEFAULT 파티션 잎을 직접 센다.
-- 잎은 부모의 권한을 물려받지 않아 앱 역할 로그인 검증기가 permission denied 로 멈췄다. 읽기만 준다. 쓰기는 부모 표를 거치고
-- 명령 저널은 append_command_journal_row 로만 쓴다(V49). harness_role 은 V1 의 GRANT SELECT ON ALL TABLES 가 이미 덮는다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_role') THEN
        GRANT SELECT ON ledger_entries_default, event_journal_default, trades_default, outbox_default,
                        command_journal_default TO app_role;
    END IF;
END $$;
