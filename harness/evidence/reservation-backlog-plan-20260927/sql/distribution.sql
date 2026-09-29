\timing on
\pset pager off
SET statement_timeout = '120s';
SELECT (SELECT count(*) FROM command_journal) cmd_rows,
       (SELECT count(*) FROM command_journal WHERE command_type='NEW') cmd_new,
       (SELECT count(*) FROM event_journal) evt_rows,
       (SELECT count(*) FROM event_journal WHERE market_id IS NULL) evt_null_market,
       (SELECT count(*) FROM orders) orders_rows,
       (SELECT count(*) FROM order_reservations) res_rows,
       (SELECT count(*) FROM event_emission_registry) reg_rows,
       (SELECT count(*) FROM markets) markets;
SELECT m.id, m.symbol, cur.settled,
       (SELECT count(*) FROM command_journal c WHERE c.market_id = m.id AND c.command_type='NEW') new_total,
       (SELECT count(*) FROM command_journal c WHERE c.market_id = m.id AND c.command_type='NEW' AND c.seq > COALESCE(cur.settled,-1)) new_above,
       (SELECT count(*) FROM command_journal c WHERE c.market_id = m.id AND c.command_type='CANCEL' AND c.seq > COALESCE(cur.settled,-1)) cancel_above,
       (SELECT min(c.seq) FROM command_journal c WHERE c.market_id = m.id) min_seq,
       (SELECT max(c.seq) FROM command_journal c WHERE c.market_id = m.id) max_seq,
       (SELECT count(*) FROM order_reservations r WHERE r.market_id = m.id) res_total
FROM markets m
CROSS JOIN LATERAL (SELECT max(e.source_cmd_seq) AS settled FROM event_journal e WHERE e.market_id = m.id) cur
ORDER BY m.id;
