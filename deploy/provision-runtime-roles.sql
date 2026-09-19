-- Flyway 완료 후 관리자 계정으로 실행한다. 값은 psql 환경에서 읽고 출력하지 않는다.
\set ON_ERROR_STOP on
\getenv app_user DB_USER
\getenv app_password DB_PASSWORD
\getenv read_user READ_DB_USER
\getenv read_password READ_DB_PASSWORD
\getenv bot_user BOT_DB_USER
\getenv bot_password BOT_DB_PASSWORD
SELECT count(DISTINCT name) = 3
       AND bool_and(name <> current_user AND name NOT IN ('app_role', 'harness_role', 'postgres')) AS safe_names
FROM (VALUES (:'app_user'), (:'read_user'), (:'bot_user')) AS requested(name)
\gset
\if :safe_names
\else
  \echo 'runtime roles must be distinct from each other and the schema owner'
  \quit 3
\endif
BEGIN;
SELECT NOT EXISTS (
    SELECT 1 FROM pg_roles
    WHERE rolname IN (:'app_user', :'read_user', :'bot_user')
      AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)
) AS safe_existing_roles
\gset
\if :safe_existing_roles
\else
  \echo 'existing privileged role cannot be repurposed as a runtime login'
  \quit 3
\endif
SELECT format('CREATE ROLE %I LOGIN', name)
FROM (VALUES (:'app_user'), (:'read_user'), (:'bot_user')) AS requested(name)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = name)
\gexec
ALTER ROLE :"app_user" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'app_password';
ALTER ROLE :"bot_user" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'bot_password';
ALTER ROLE :"read_user" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'read_password';
GRANT app_role TO :"app_user", :"bot_user";
SELECT NOT EXISTS (
    SELECT 1 FROM pg_auth_members membership
    JOIN pg_roles member_role ON member_role.oid = membership.member
    WHERE member_role.rolname = :'read_user'
) AS isolated_reader
\gset
\if :isolated_reader
\else
  \echo 'reader login has unexpected group memberships'
  \quit 3
\endif
GRANT USAGE ON SCHEMA public TO :"read_user";
GRANT SELECT ON assets, markets, trades TO :"read_user";
ALTER ROLE :"read_user" SET default_transaction_read_only = on;
ALTER ROLE :"app_user" SET timezone = 'UTC';
ALTER ROLE :"bot_user" SET timezone = 'UTC';
ALTER ROLE :"read_user" SET timezone = 'UTC';
COMMIT;
