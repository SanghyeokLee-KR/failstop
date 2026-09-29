SELECT count(*) AS pending,
       COALESCE(floor(EXTRACT(EPOCH FROM clock_timestamp() - min(r.created_at))), 0) AS oldest_s
FROM markets m
LEFT JOIN LATERAL (SELECT e.source_cmd_seq AS settled FROM event_journal e
                    WHERE e.market_id BETWEEN m.id AND m.id
                    ORDER BY e.market_id DESC, e.source_cmd_seq DESC LIMIT 1) cur ON true
CROSS JOIN LATERAL (SELECT c.seq FROM command_journal c
                     WHERE c.market_id BETWEEN m.id AND m.id AND c.command_type = 'NEW'
                       AND c.seq > COALESCE(cur.settled, -1)
                     ORDER BY c.market_id, c.seq LIMIT 100000) c
CROSS JOIN LATERAL (SELECT r.created_at FROM order_reservations r
                     WHERE r.command_seq = c.seq
                       AND NOT EXISTS (SELECT 1 FROM event_emission_registry e WHERE e.source_cmd_seq = r.command_seq)
                     LIMIT 1) r
