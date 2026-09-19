-- Primary에서만 적용한다. 역할은 WAL로 Standby에 복제된다.
-- 로컬 peer 인증을 사용하므로 비밀번호와 TCP HBA 규칙을 만들지 않는다.
BEGIN;
CREATE ROLE failstop_monitor LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
  NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 3;
GRANT CONNECT ON DATABASE exchange TO failstop_monitor;
GRANT pg_read_all_stats, pg_read_all_settings TO failstop_monitor;
GRANT EXECUTE ON FUNCTION pg_catalog.pg_ls_waldir() TO failstop_monitor;
ALTER ROLE failstop_monitor SET default_transaction_read_only = on;
ALTER ROLE failstop_monitor SET statement_timeout = '3s';
ALTER ROLE failstop_monitor SET lock_timeout = '500ms';
COMMIT;
