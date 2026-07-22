-- ============================================================================
-- V9__operator_roles_delete_grant.sql
-- 원천: reviewer #36 3차 high. OperatorsController.changeRole의 REVOKE는
-- operator_roles에 하드 DELETE를 실행하나 V5:142는 app_role에 SELECT/INSERT/UPDATE만
-- 부여했다. 운영 롤 경로에서 REVOKE가 42501로 상시 실패한다(Testcontainers 슈퍼유저
-- 접속이라 baseline에서 가려졌다). operator_roles는 부여·회수 사유를 admin_audit_log가
-- 보존하는 순수 효과 테이블이라 하드 DELETE가 정상 의미다(소프트 삭제 아님).
-- 조치: operator_roles DELETE를 app_role에 부여(V7:115 post_likes·comment_likes DELETE
-- 선례 동형).
-- 함께: idempotency_keys 하드 DELETE(OrderGateway.deleteOrphanKey)도 V1:800이 DELETE를
-- 빠뜨려 같은 결함을 갖는다. 멱등키는 소프트 상태 컬럼이 없는 효과 테이블이고 gc-idem cron이
-- 하드 DELETE로 정리하는 대상이라 동일 원칙으로 DELETE를 부여한다(런타임 정리 경로와 부여
-- 권한 정합).
-- 원장·잔고 직접 DML 경계(V1 §7)는 불변. 빈 PG17 단독 적용성 위해 롤 존재 검사로 감싼다.
-- ============================================================================
SET timezone = 'UTC';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_role') THEN
        GRANT DELETE ON operator_roles, idempotency_keys TO app_role;
    END IF;
END $$;
