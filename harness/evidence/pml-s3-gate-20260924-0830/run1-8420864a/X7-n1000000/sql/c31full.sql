SELECT count(*) FROM (
SELECT 'reserve_ledger_mismatch' AS term, r.command_seq AS subject
FROM order_reservations r
WHERE r.created_at >= now() - interval '90 days'
  AND (NOT EXISTS (SELECT 1 FROM ledger_transactions t
                    WHERE t.id = r.ledger_txn_id AND t.business_event_type = 'RESERVE'
                      AND t.business_event_id = r.order_id AND t.status = 'SEALED')
       OR NOT EXISTS (SELECT 1 FROM ledger_entries e
                       WHERE e.transaction_id = r.ledger_txn_id AND e.created_at = r.created_at
                         AND e.account_id = r.locked_account_id AND e.asset_id = r.asset_id AND e.amount = r.amount)
       OR NOT EXISTS (SELECT 1 FROM ledger_entries e
                       WHERE e.transaction_id = r.ledger_txn_id AND e.created_at = r.created_at
                         AND e.account_id = r.available_account_id AND e.asset_id = r.asset_id AND e.amount = -r.amount))
UNION ALL
SELECT 'unreserved_new', c.seq
FROM command_journal c
JOIN lease_acquisitions a ON a.epoch = c.fencing_token AND a.protocol >= 2
WHERE c.command_type = 'NEW'
  AND NOT EXISTS (SELECT 1 FROM order_reservations r WHERE r.command_seq = c.seq)
UNION ALL
SELECT 'reservation_journal_mismatch', r.command_seq
FROM order_reservations r
WHERE NOT EXISTS (SELECT 1 FROM command_journal c
                   WHERE c.seq = r.command_seq AND c.command_type = 'NEW'
                     AND c.assigned_order_id = r.order_id AND c.user_id = r.user_id
                     AND c.market_id = r.market_id
                     AND sha256(convert_to(c.payload::text, 'UTF8')) = r.payload_sha256)
) x
