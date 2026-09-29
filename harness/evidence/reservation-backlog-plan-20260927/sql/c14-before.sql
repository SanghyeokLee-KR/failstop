            SELECT count(*) FROM (
                SELECT a.id
                FROM accounts a
                JOIN balances b ON b.account_id = a.id
                LEFT JOIN (SELECT locked_account_id AS account_id, SUM(locked_amount) AS amt
                           FROM orders WHERE status IN ('PENDING','OPEN','PARTIAL')
                           GROUP BY locked_account_id) o ON o.account_id = a.id
                LEFT JOIN (SELECT r.locked_account_id AS account_id, SUM(r.amount) AS amt
                           FROM markets m
                           CROSS JOIN LATERAL (SELECT max(e.source_cmd_seq) AS settled
                                                 FROM event_journal e WHERE e.market_id = m.id) cur
                           CROSS JOIN LATERAL (SELECT c.seq FROM command_journal c
                                                WHERE c.market_id = m.id AND c.command_type = 'NEW'
                                                  AND c.seq > COALESCE(cur.settled, -1)
                                                ORDER BY c.seq LIMIT 100000) c
                           CROSS JOIN LATERAL (SELECT r.locked_account_id, r.amount FROM order_reservations r
                                                WHERE r.command_seq = c.seq
                                                  AND NOT EXISTS (SELECT 1 FROM event_emission_registry e
                                                                   WHERE e.source_cmd_seq = r.command_seq)
                                                LIMIT 1) r
                           GROUP BY r.locked_account_id) u ON u.account_id = a.id
                LEFT JOIN (SELECT la.id AS account_id, SUM(t.amount) AS amt
                           FROM transfer_requests t
                           JOIN accounts la ON la.owner_type = 'USER' AND la.owner_id = t.user_id
                                           AND la.purpose = 'LOCKED' AND la.asset_id = t.asset_id AND la.shard = 0
                           WHERE t.direction = 'WITHDRAW' AND t.status = 'REQUESTED'
                           GROUP BY la.id) w ON w.account_id = a.id
                WHERE a.purpose = 'LOCKED'
                  AND b.balance <> COALESCE(o.amt, 0) + COALESCE(u.amt, 0) + COALESCE(w.amt, 0)
            ) x
