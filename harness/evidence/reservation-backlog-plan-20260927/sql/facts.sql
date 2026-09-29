-- 2026-09-27T12:56:43Z chaos-db-1, PGOPTIONS default_transaction_read_only=on
\timing on
\pset pager off
SELECT now() AS db_now, pg_is_in_recovery() AS in_recovery, current_setting('server_version') AS ver,
       current_setting('shared_buffers') sb, current_setting('work_mem') wm, current_setting('jit') jit,
       current_setting('random_page_cost') rpc, current_setting('effective_cache_size') ecs;
SELECT calls, round(mean_exec_time::numeric,1) mean_ms, round(total_exec_time::numeric/1000,1) total_s,
       round(min_exec_time::numeric,1) min_ms, round(max_exec_time::numeric,1) max_ms, rows,
       shared_blks_hit, shared_blks_read, temp_blks_written,
       left(regexp_replace(query, '\s+', ' ', 'g'), 120) q
FROM pg_stat_statements WHERE query ILIKE '%CROSS JOIN LATERAL%order_reservations%' ORDER BY total_exec_time DESC;
SELECT stats_reset FROM pg_stat_statements_info;
SELECT relname, n_live_tup, n_dead_tup, last_autovacuum, last_autoanalyze, last_analyze
FROM pg_stat_user_tables
WHERE relname ~ '^(command_journal|event_journal|order_reservations|event_emission_registry|orders|markets)'
ORDER BY relname;
\pset pager off
SELECT table_name, string_agg(column_name || ':' || data_type || CASE WHEN is_nullable='NO' THEN '!' ELSE '' END || COALESCE('=' || left(column_default, 30), ''), ', ' ORDER BY ordinal_position)
FROM information_schema.columns WHERE table_schema='public' AND table_name IN ('command_journal','event_journal','event_emission_registry','order_reservations')
GROUP BY table_name;
SELECT indexrelid::regclass, pg_get_indexdef(indexrelid) FROM pg_index WHERE indrelid IN ('command_journal'::regclass, 'event_journal'::regclass);
SELECT tgname, tgrelid::regclass, tgenabled FROM pg_trigger WHERE tgrelid IN ('command_journal'::regclass,'event_journal'::regclass) AND NOT tgisinternal;
SELECT relname, relpages, reltuples::bigint, relallvisible FROM pg_class WHERE relname IN ('command_journal_p0','event_journal_b1','event_journal_b2','event_journal_b4','event_journal_p0');
SELECT tablename, attname, n_distinct, most_common_vals::text, correlation FROM pg_stats WHERE tablename IN ('command_journal_p0','event_journal_b1','event_journal_b2','event_journal_b4','event_journal_p0') AND attname IN ('market_id','seq','source_cmd_seq') ORDER BY 1,2;
SELECT version, description, installed_on FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;
