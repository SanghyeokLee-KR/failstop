BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
EXPLAIN (ANALYZE, BUFFERS)
            SELECT (
                SELECT count(*) FROM (
                    SELECT fencing_token,
                           lag(fencing_token) OVER (ORDER BY created_at, seq) AS prev
                    FROM command_journal
                ) t
                WHERE t.prev > t.fencing_token
            ) + (
                SELECT count(*) FROM (
                    SELECT fencing_token,
                           lag(fencing_token) OVER (PARTITION BY market_id ORDER BY seq) AS prev
                    FROM command_journal
                ) t
                WHERE t.prev > t.fencing_token
            );
ROLLBACK;
