WITH w AS MATERIALIZED (
    SELECT c.seq, c.market_id, c.user_id, c.assigned_order_id, c.fencing_token, c.payload
    FROM markets m
    CROSS JOIN LATERAL (SELECT c.seq, c.market_id, c.user_id, c.assigned_order_id, c.fencing_token, c.payload
                          FROM command_journal c
                         WHERE c.market_id = m.id AND c.command_type = 'NEW'
                         ORDER BY c.seq DESC LIMIT 5000) c
), wr AS MATERIALIZED (
    SELECT w.*, r.*
    FROM w
    LEFT JOIN LATERAL (SELECT r.command_seq AS r_seq, r.order_id AS r_order, r.user_id AS r_user,
                              r.market_id AS r_market, r.asset_id, r.available_account_id, r.locked_account_id,
                              r.amount, r.ledger_txn_id, r.payload_sha256, r.created_at AS r_created
                         FROM order_reservations r WHERE r.command_seq = w.seq LIMIT 1) r ON true
)
SELECT (SELECT count(*) FROM wr
         WHERE wr.r_seq IS NOT NULL
           AND (NOT EXISTS (SELECT 1 FROM ledger_transactions t
                             WHERE t.id = wr.ledger_txn_id AND t.business_event_type = 'RESERVE'
                               AND t.business_event_id = wr.r_order AND t.status = 'SEALED')
                OR NOT EXISTS (SELECT 1 FROM ledger_entries e
                                WHERE e.transaction_id = wr.ledger_txn_id AND e.created_at = wr.r_created
                                  AND e.account_id = wr.locked_account_id AND e.asset_id = wr.asset_id
                                  AND e.amount = wr.amount)
                OR NOT EXISTS (SELECT 1 FROM ledger_entries e
                                WHERE e.transaction_id = wr.ledger_txn_id AND e.created_at = wr.r_created
                                  AND e.account_id = wr.available_account_id AND e.asset_id = wr.asset_id
                                  AND e.amount = -wr.amount)))
     + (SELECT count(*) FROM wr JOIN lease_acquisitions a ON a.epoch = wr.fencing_token AND a.protocol >= 2
         WHERE wr.r_seq IS NULL)
     + (SELECT count(*) FROM wr
         WHERE wr.r_seq IS NOT NULL
           AND (wr.r_order <> wr.assigned_order_id OR wr.r_user <> wr.user_id OR wr.r_market <> wr.market_id
                OR wr.payload_sha256 <> sha256(convert_to(wr.payload::text, 'UTF8'))))
