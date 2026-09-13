-- 측정 커서를 데이터에서 뽑아 bench.cursors 에 둔다. 측정 대상 마켓: 1(편중 40%), 12(소형 1%).
\set ON_ERROR_STOP 1
SELECT cmd_seq AS bound FROM journal_axis_boundary WHERE name = 'pre_v36_event' \gset
CREATE TABLE bench.cursors (name text PRIMARY KEY, v bigint, o int, j text, note text);

-- 쿼리 1(모던 분기). 마켓의 모던 이벤트 순번 기준 시작(1번째)·중간·끝에서 200 앞·끝.
INSERT INTO bench.cursors (name, v, o, note)
SELECT 'q1_m' || market_id || '_' || p.pos, source_cmd_seq, event_ordinal,
       'rank ' || rn || ' of ' || n
FROM (SELECT market_id, source_cmd_seq, event_ordinal,
             row_number() OVER (PARTITION BY market_id ORDER BY source_cmd_seq, event_ordinal) AS rn,
             count(*) OVER (PARTITION BY market_id) AS n
      FROM event_journal WHERE market_id IN (1, 12)) t
CROSS JOIN LATERAL (VALUES ('start', 1::bigint), ('mid', n / 2), ('near', n - 200), ('end', n)) p(pos, at)
WHERE t.rn = p.at;

-- 쿼리 2(레거시 분기). 시작 (0,-1), 레거시 이벤트 중간, 레거시 끝에서 200 앞(배치가 경계를 넘는다).
INSERT INTO bench.cursors (name, v, o, note)
SELECT 'q2_m' || m || '_start', 0, -1, 'journal start' FROM (VALUES (1), (12)) x(m);
INSERT INTO bench.cursors (name, v, o, note)
SELECT 'q2_m' || market_id || '_' || p.pos, source_cmd_seq, event_ordinal,
       'legacy rank ' || rn || ' of ' || n
FROM (SELECT c.market_id, e.source_cmd_seq, e.event_ordinal,
             row_number() OVER (PARTITION BY c.market_id ORDER BY e.source_cmd_seq, e.event_ordinal) AS rn,
             count(*) OVER (PARTITION BY c.market_id) AS n
      FROM event_journal e JOIN command_journal c ON c.seq = e.source_cmd_seq
      WHERE e.market_id IS NULL AND c.market_id IN (1, 12)) t
CROSS JOIN LATERAL (VALUES ('mid', n / 2), ('near', n - 200)) p(pos, at)
WHERE t.rn = p.at;

-- 명령 판독. lastAppliedSeq 시작 0, 마켓 명령 중간, 끝에서 200 앞. upTo 는 head(m).
INSERT INTO bench.cursors (name, v, note)
SELECT 'cmd_m' || m || '_start', 0, 'journal start' FROM (VALUES (1), (12)) x(m);
INSERT INTO bench.cursors (name, v, note)
SELECT 'cmd_m' || market_id || '_' || p.pos, seq, 'rank ' || rn || ' of ' || n
FROM (SELECT market_id, seq,
             row_number() OVER (PARTITION BY market_id ORDER BY seq) AS rn,
             count(*) OVER (PARTITION BY market_id) AS n
      FROM command_journal WHERE market_id IN (1, 12)) t
CROSS JOIN LATERAL (VALUES ('mid', n / 2), ('near', n - 200)) p(pos, at)
WHERE t.rn = p.at;
INSERT INTO bench.cursors (name, v, note)
SELECT 'head_m' || market_id, max(seq), 'head' FROM command_journal WHERE market_id IN (1, 12) GROUP BY market_id;

-- 쿼리 3 (a). 마켓마다 커서 = 그 마켓 명령 끝에서 100번째 seq.
INSERT INTO bench.cursors (name, j, note)
SELECT 'q3_near', jsonb_object_agg(market_id::text, seq)::text, '100th from head per market'
FROM (SELECT market_id, seq, row_number() OVER (PARTITION BY market_id ORDER BY seq DESC) AS rn
      FROM command_journal) t
WHERE rn = 100;

SELECT name, v, o, note FROM bench.cursors WHERE j IS NULL ORDER BY name;
SELECT j FROM bench.cursors WHERE name = 'q3_near';
