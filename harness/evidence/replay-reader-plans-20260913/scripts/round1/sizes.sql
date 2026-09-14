\set ON_ERROR_STOP 1
\pset footer off
SELECT name, cmd_seq FROM journal_axis_boundary;
SELECT 'holes' AS k, market_id, count(*), min(seq), max(seq) FROM bench.holes GROUP BY market_id ORDER BY market_id;

-- 잎별 행 수(행이 있는 잎만)와 잎 수
SELECT 'command_journal' AS parent, count(*) AS leaves FROM pg_inherits WHERE inhparent = 'command_journal'::regclass
UNION ALL
SELECT 'event_journal', count(*) FROM pg_inherits WHERE inhparent = 'event_journal'::regclass;
SELECT tableoid::regclass AS leaf, count(*) FROM command_journal GROUP BY 1 ORDER BY 1;
SELECT tableoid::regclass AS leaf, count(*) FROM event_journal GROUP BY 1 ORDER BY 1;

-- 마켓별 규모. 레거시 이벤트는 명령 조인으로 마켓을 짝짓는다.
SELECT c.market_id,
       count(*) FILTER (WHERE c.seq <= b.cmd_seq) AS legacy_cmds,
       count(*) FILTER (WHERE c.seq > b.cmd_seq)  AS modern_cmds,
       max(c.seq) AS head
FROM command_journal c, journal_axis_boundary b
WHERE b.name = 'pre_v36_event'
GROUP BY c.market_id ORDER BY c.market_id;
SELECT coalesce(e.market_id, c.market_id) AS market_id,
       count(*) FILTER (WHERE e.market_id IS NULL) AS legacy_events,
       count(*) FILTER (WHERE e.market_id IS NOT NULL) AS modern_events
FROM event_journal e JOIN command_journal c ON c.seq = e.source_cmd_seq
GROUP BY 1 ORDER BY 1;

-- 채번 정합: 카운터 next_value - 1 = 마켓별 max(market_seq), event_seq = market_id*span + market_seq
SELECT k.market_id, k.next_value - 1 AS counter_last, max(e.market_seq) AS max_market_seq,
       count(*) FILTER (WHERE e.event_seq <> e.market_id * 10000000 + e.market_seq) AS bad_derivation
FROM market_journal_counters k
LEFT JOIN event_journal e ON e.market_id = k.market_id
WHERE k.name = 'event_seq'
GROUP BY k.market_id, k.next_value ORDER BY k.market_id;
SELECT name, next_value FROM journal_counters ORDER BY name;
SELECT count(*) AS registry_rows FROM event_emission_registry;

-- 이벤트 없는 명령(구멍)은 레거시 경계 위 bench.holes 뿐이어야 한다
SELECT count(*) AS cmds_without_events,
       count(*) FILTER (WHERE seq <= (SELECT cmd_seq FROM journal_axis_boundary WHERE name = 'pre_v36_event')) AS below_bound
FROM command_journal c WHERE NOT EXISTS (SELECT 1 FROM event_journal e WHERE e.source_cmd_seq = c.seq);

-- 크기
SELECT c.relname, c.relkind, pg_size_pretty(pg_relation_size(c.oid)) AS size, c.reltuples::bigint
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public'
  AND (c.relname LIKE 'event_journal%' OR c.relname LIKE 'command_journal%' OR c.relname LIKE 'idx_evt%'
       OR c.relname LIKE 'idx_cmd%' OR c.relname LIKE 'event_journal_p0%')
  AND pg_relation_size(c.oid) > 0
ORDER BY c.relname;
SELECT indexrelid::regclass AS index, indrelid::regclass AS tbl, pg_size_pretty(pg_relation_size(indexrelid)) AS size
FROM pg_index WHERE indrelid IN ('event_journal_p0'::regclass, 'command_journal_p0'::regclass) ORDER BY 2, 1;
SHOW shared_buffers;
SHOW work_mem;
SHOW random_page_cost;
SELECT version();
