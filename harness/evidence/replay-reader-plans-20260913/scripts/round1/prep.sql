-- EventJournalReader / CommandJournalReader 원문 SQL. ? 를 $n 으로, 인자 타입은 JDBC 바인딩
-- (int -> int4, long -> int8, String -> varchar) 과 같게 둔다.
PREPARE q1(int, bigint, int, int) AS
SELECT event_seq, source_cmd_seq, event_ordinal, event_type,
       payload::text AS payload, payload_schema_version,
       fencing_epoch, created_at
FROM event_journal
WHERE market_id = $1 AND (source_cmd_seq, event_ordinal) > ($2, $3)
ORDER BY source_cmd_seq, event_ordinal
LIMIT $4;

PREPARE q2(int, bigint, bigint, bigint, bigint, int, int, int, bigint, int, int, int) AS
SELECT event_seq, source_cmd_seq, event_ordinal, event_type, payload,
       payload_schema_version, fencing_epoch, created_at
FROM (
    (SELECT e.event_seq, e.source_cmd_seq, e.event_ordinal, e.event_type,
            e.payload::text AS payload, e.payload_schema_version,
            e.fencing_epoch, e.created_at
     FROM event_journal e
     JOIN command_journal c ON c.seq = e.source_cmd_seq
     WHERE e.market_id IS NULL AND c.market_id = $1
       AND e.source_cmd_seq <= $2 AND c.seq BETWEEN $3 AND $4
       AND (e.source_cmd_seq, e.event_ordinal) > ($5, $6)
     ORDER BY e.source_cmd_seq, e.event_ordinal
     LIMIT $7)
    UNION ALL
    (SELECT e.event_seq, e.source_cmd_seq, e.event_ordinal, e.event_type,
            e.payload::text AS payload, e.payload_schema_version,
            e.fencing_epoch, e.created_at
     FROM event_journal e
     WHERE e.market_id = $8 AND (e.source_cmd_seq, e.event_ordinal) > ($9, $10)
     ORDER BY e.source_cmd_seq, e.event_ordinal
     LIMIT $11)
) u
ORDER BY source_cmd_seq, event_ordinal
LIMIT $12;

PREPARE q3(varchar, bigint) AS
WITH RECURSIVE market_ids(id) AS (
    SELECT min(market_id) FROM command_journal
    UNION ALL
    SELECT (SELECT min(c.market_id) FROM command_journal c WHERE c.market_id > m.id)
    FROM market_ids m
    WHERE m.id IS NOT NULL
)
, cursors AS (
    SELECT key::smallint AS market_id, value::bigint AS seq
    FROM jsonb_each_text($1::jsonb)
),
floors AS (
    SELECT ids.id, GREATEST(COALESCE(cur.seq, 0), $2::bigint) AS floor_seq
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

PREPARE qc(bigint, int, bigint, int) AS
SELECT seq, market_id, command_type, user_id, client_order_id,
       assigned_order_id, engine_ts, tie_break, payload::text AS payload,
       fencing_token, checksum, created_at
FROM command_journal
WHERE seq > $1 AND market_id = $2 AND seq <= $3 ORDER BY seq LIMIT $4;
