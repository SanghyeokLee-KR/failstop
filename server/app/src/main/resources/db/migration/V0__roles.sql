-- ============================================================================
-- V0__roles.sql
-- 목적: 권한 경계 롤 부트스트랩(외부 검토 P0-7).
-- 원칙:
--   * app_role    : 애플리케이션 런타임 권한. NOLOGIN 그룹 롤.
--                   원장(ledger_transactions/ledger_entries)·잔고(balances) 직접 DML 금지가
--                   전제이며, 실제 GRANT/REVOKE는 테이블이 생기는 V1 말미에서 수행한다.
--                   게시는 SECURITY DEFINER 함수 post_ledger_transaction 경유가 유일 경로.
--   * harness_role: 검증 하네스용 읽기 전용 롤. GRANT는 V1 말미.
--   * 실제 LOGIN 사용자는 배포 환경 프로비저닝에서 별도 생성 후 GRANT app_role 한다.
-- 롤은 클러스터 전역 객체라 CREATE ROLE에 IF NOT EXISTS가 없다.
-- Testcontainers superuser 재적용·재사용 컨테이너에서 깨지지 않도록 존재 검사로 감싼다.
-- ============================================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_role') THEN
        CREATE ROLE app_role NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'harness_role') THEN
        CREATE ROLE harness_role NOLOGIN;
    END IF;
END $$;

-- PG15+ 기본이지만 명시: 임의 롤의 public 스키마 객체 생성 봉쇄
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
