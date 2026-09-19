-- 스키마 소유자 세션에서 실행한다. 환경에서 값을 받고 psql의 식별자/문자열 인용을 쓴다.
\set ON_ERROR_STOP on
\getenv backup_user BACKUP_DB_USER
\getenv backup_password BACKUP_DB_PASSWORD
SELECT :'backup_user' <> current_user
   AND length(:'backup_user') > 0 AND length(:'backup_password') > 0
   AND :'backup_user' NOT IN ('postgres', 'app_role', 'harness_role')
   AND NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname=:'backup_user'
                   AND (rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls))
   AND NOT EXISTS (SELECT 1 FROM pg_auth_members membership
                   JOIN pg_roles member_role ON member_role.oid=membership.member
                   JOIN pg_roles granted_role ON granted_role.oid=membership.roleid
                   WHERE member_role.rolname=:'backup_user' AND granted_role.rolname <> 'pg_read_all_data') AS safe_name
\gset
\if :safe_name
\else
  \echo 'backup role must be separate from administrative roles'
  \quit 3
\endif
BEGIN;
SELECT format('CREATE ROLE %I LOGIN', :'backup_user')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname=:'backup_user')
\gexec
ALTER ROLE :"backup_user" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'backup_password';
GRANT pg_read_all_data TO :"backup_user";
ALTER ROLE :"backup_user" SET default_transaction_read_only = on;
COMMIT;
