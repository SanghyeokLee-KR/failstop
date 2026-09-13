-- A: market_id 를 SQL 리터럴로 박은 q1/q2. 커서, ordinal, limit, legacyBound 는 바인드 인자로 남긴다.
-- 인자 순서는 원문 params 에서 marketId 두 개만 뺀 것이다.
PREPARE q1_lit_m1(bigint, int, int) AS
SELECT event_seq, source_cmd_seq, event_ordinal, event_type,
       payload::text AS payload, payload_schema_version,
       fencing_epoch, created_at
FROM event_journal
WHERE market_id = 1 AND (source_cmd_seq, event_ordinal) > ($1, $2)
ORDER BY source_cmd_seq, event_ordinal
LIMIT $3;

PREPARE q1_lit_m12(bigint, int, int) AS
SELECT event_seq, source_cmd_seq, event_ordinal, event_type,
       payload::text AS payload, payload_schema_version,
       fencing_epoch, created_at
FROM event_journal
WHERE market_id = 12 AND (source_cmd_seq, event_ordinal) > ($1, $2)
ORDER BY source_cmd_seq, event_ordinal
LIMIT $3;

PREPARE q2_lit_m1(bigint, bigint, bigint, bigint, int, int, bigint, int, int, int) AS
SELECT event_seq, source_cmd_seq, event_ordinal, event_type, payload,
       payload_schema_version, fencing_epoch, created_at
FROM (
    (SELECT e.event_seq, e.source_cmd_seq, e.event_ordinal, e.event_type,
            e.payload::text AS payload, e.payload_schema_version,
            e.fencing_epoch, e.created_at
     FROM event_journal e
     JOIN command_journal c ON c.seq = e.source_cmd_seq
     WHERE e.market_id IS NULL AND c.market_id = 1
       AND e.source_cmd_seq <= $1 AND c.seq BETWEEN $2 AND $3
       AND (e.source_cmd_seq, e.event_ordinal) > ($4, $5)
     ORDER BY e.source_cmd_seq, e.event_ordinal
     LIMIT $6)
    UNION ALL
    (SELECT e.event_seq, e.source_cmd_seq, e.event_ordinal, e.event_type,
            e.payload::text AS payload, e.payload_schema_version,
            e.fencing_epoch, e.created_at
     FROM event_journal e
     WHERE e.market_id = 1 AND (e.source_cmd_seq, e.event_ordinal) > ($7, $8)
     ORDER BY e.source_cmd_seq, e.event_ordinal
     LIMIT $9)
) u
ORDER BY source_cmd_seq, event_ordinal
LIMIT $10;

PREPARE q2_lit_m12(bigint, bigint, bigint, bigint, int, int, bigint, int, int, int) AS
SELECT event_seq, source_cmd_seq, event_ordinal, event_type, payload,
       payload_schema_version, fencing_epoch, created_at
FROM (
    (SELECT e.event_seq, e.source_cmd_seq, e.event_ordinal, e.event_type,
            e.payload::text AS payload, e.payload_schema_version,
            e.fencing_epoch, e.created_at
     FROM event_journal e
     JOIN command_journal c ON c.seq = e.source_cmd_seq
     WHERE e.market_id IS NULL AND c.market_id = 12
       AND e.source_cmd_seq <= $1 AND c.seq BETWEEN $2 AND $3
       AND (e.source_cmd_seq, e.event_ordinal) > ($4, $5)
     ORDER BY e.source_cmd_seq, e.event_ordinal
     LIMIT $6)
    UNION ALL
    (SELECT e.event_seq, e.source_cmd_seq, e.event_ordinal, e.event_type,
            e.payload::text AS payload, e.payload_schema_version,
            e.fencing_epoch, e.created_at
     FROM event_journal e
     WHERE e.market_id = 12 AND (e.source_cmd_seq, e.event_ordinal) > ($7, $8)
     ORDER BY e.source_cmd_seq, e.event_ordinal
     LIMIT $9)
) u
ORDER BY source_cmd_seq, event_ordinal
LIMIT $10;
