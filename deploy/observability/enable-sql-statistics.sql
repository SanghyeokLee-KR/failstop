\set ON_ERROR_STOP on
-- 관리자 전용 단계. shared_preload_libraries 적용과 재기동 후 primary에서 한 번 실행한다.
-- 통계 초기화나 기존 Flyway 수정은 수행하지 않는다.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
SELECT current_database() AS database,
       current_setting('shared_preload_libraries') AS preload,
       current_setting('compute_query_id') AS query_identifier,
       current_setting('pg_stat_statements.track') AS statement_scope;
