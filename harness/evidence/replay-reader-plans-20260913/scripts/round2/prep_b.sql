-- B: 경계 SQL 커서 전달 형태. v1 은 /bench/prep.sql 의 q3(jsonb_each_text) 그대로다.
-- v2: jsonb CTE 를 없애고 unnest(두 배열)를 floors 의 두 사용처에 직접 둔다.
-- v3: cursors CTE 를 unnest 로만 바꾸고 floors/ids UNION 본문은 원문 그대로 둔다.
PREPARE q3v2(smallint[], bigint[], bigint) AS
WITH RECURSIVE market_ids(id) AS (
    SELECT min(market_id) FROM command_journal
    UNION ALL
    SELECT (SELECT min(c.market_id) FROM command_journal c WHERE c.market_id > m.id)
    FROM market_ids m
    WHERE m.id IS NOT NULL
)
, floors AS (
    SELECT ids.id, GREATEST(COALESCE(cur.seq, 0), $3::bigint) AS floor_seq
    FROM (SELECT id FROM market_ids WHERE id IS NOT NULL
          UNION
          SELECT c.market_id FROM unnest($1::smallint[], $2::bigint[]) AS c(market_id, seq)) ids
    LEFT JOIN unnest($1::smallint[], $2::bigint[]) AS cur(market_id, seq) ON cur.market_id = ids.id
)
SELECT f.id AS market_id,
       COALESCE(
         (SELECT c.seq - 1 FROM command_journal c
           WHERE c.market_id = f.id AND c.seq > f.floor_seq
             AND NOT EXISTS (SELECT 1 FROM event_journal e
                              WHERE e.source_cmd_seq = c.seq)
           ORDER BY c.seq
           LIMIT 1),
         GREATEST((SELECT max(c2.seq) FROM command_journal c2
                    WHERE c2.market_id = f.id),
                  f.floor_seq)) AS bound
FROM floors f;

PREPARE q3v3(smallint[], bigint[], bigint) AS
WITH RECURSIVE market_ids(id) AS (
    SELECT min(market_id) FROM command_journal
    UNION ALL
    SELECT (SELECT min(c.market_id) FROM command_journal c WHERE c.market_id > m.id)
    FROM market_ids m
    WHERE m.id IS NOT NULL
)
, cursors AS (
    SELECT * FROM unnest($1::smallint[], $2::bigint[]) AS c(market_id, seq)
),
floors AS (
    SELECT ids.id, GREATEST(COALESCE(cur.seq, 0), $3::bigint) AS floor_seq
    FROM (SELECT id FROM market_ids WHERE id IS NOT NULL
          UNION
          SELECT market_id FROM cursors) ids
    LEFT JOIN cursors cur ON cur.market_id = ids.id
)
SELECT f.id AS market_id,
       COALESCE(
         (SELECT c.seq - 1 FROM command_journal c
           WHERE c.market_id = f.id AND c.seq > f.floor_seq
             AND NOT EXISTS (SELECT 1 FROM event_journal e
                              WHERE e.source_cmd_seq = c.seq)
           ORDER BY c.seq
           LIMIT 1),
         GREATEST((SELECT max(c2.seq) FROM command_journal c2
                    WHERE c2.market_id = f.id),
                  f.floor_seq)) AS bound
FROM floors f;
