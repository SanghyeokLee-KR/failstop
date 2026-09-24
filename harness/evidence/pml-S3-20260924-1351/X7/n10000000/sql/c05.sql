WITH s AS (SELECT band_span FROM journal_band_spec WHERE name = 'command_seq')
SELECT (SELECT CASE WHEN count(*) = 1 THEN 0 ELSE 1 END FROM s)
     + (SELECT COALESCE((max(c.seq) - min(c.seq) + 1) - count(*), 0)
        FROM command_journal c
        WHERE c.seq < (SELECT band_span FROM s))
     + (SELECT COALESCE(sum(b.gap), 0) FROM (
            SELECT (max(c.seq) - min(c.seq) + 1) - count(*) AS gap
            FROM command_journal c
            WHERE c.seq >= (SELECT band_span FROM s)
            GROUP BY c.market_id
        ) b)
