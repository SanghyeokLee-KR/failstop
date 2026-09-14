-- 마켓당 한 문장 경계 판독. 인자: $1 market_id, $2 floor, $3 market_id, $4 floor.
PREPARE pm(smallint, bigint, smallint, bigint) AS
SELECT COALESCE(
  (SELECT c.seq - 1 FROM command_journal c
    WHERE c.market_id = $1 AND c.seq > $2
      AND NOT EXISTS (SELECT 1 FROM event_journal e WHERE e.source_cmd_seq = c.seq)
    ORDER BY c.seq LIMIT 1),
  GREATEST((SELECT max(c2.seq) FROM command_journal c2 WHERE c2.market_id = $3), $4));

-- 앞서 도는 마켓 집합 판독(CommandJournalReader.MARKET_IDS_CTE + maxSeqByMarket 본문).
PREPARE ms AS
WITH RECURSIVE market_ids(id) AS (
    SELECT min(market_id) FROM command_journal
    UNION ALL
    SELECT (SELECT min(c.market_id) FROM command_journal c WHERE c.market_id > m.id)
    FROM market_ids m
    WHERE m.id IS NOT NULL
)
SELECT m.id AS market_id,
       (SELECT max(c.seq) FROM command_journal c WHERE c.market_id = m.id) AS head
FROM market_ids m
WHERE m.id IS NOT NULL;
