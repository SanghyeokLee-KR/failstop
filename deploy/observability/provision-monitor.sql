\set ON_ERROR_STOP on
\getenv monitor_password PG_MONITOR_PASSWORD
BEGIN;
SELECT current_user <> 'failstop_monitor' AS separate_administrator
\gset
\if :separate_administrator
\else
  \echo 'monitor login cannot provision itself'
  \quit 3
\endif
SELECT 'CREATE ROLE failstop_monitor LOGIN'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='failstop_monitor')
\gexec
SELECT NOT EXISTS (
  SELECT 1 FROM pg_auth_members membership
  JOIN pg_roles parent ON parent.oid=membership.roleid
  JOIN pg_roles child ON child.oid=membership.member
  WHERE child.rolname='failstop_monitor' AND parent.rolname <> 'pg_monitor'
) AS isolated_role
\gset
\if :isolated_role
\else
  \echo 'failstop_monitor has unexpected membership; inspect before provisioning'
  \quit 3
\endif
ALTER ROLE failstop_monitor LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 4 PASSWORD :'monitor_password';
GRANT pg_monitor TO failstop_monitor;
GRANT CONNECT ON DATABASE :"DBNAME" TO failstop_monitor;
ALTER ROLE failstop_monitor SET default_transaction_read_only=on;
ALTER ROLE failstop_monitor SET statement_timeout='5s';
ALTER ROLE failstop_monitor SET idle_in_transaction_session_timeout='5s';
COMMIT;
