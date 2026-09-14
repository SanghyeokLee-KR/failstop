-- V41 이후. 레거시 경계 위 명령의 이벤트. event_seq = market_id * band_span + market_seq,
-- market_seq 는 next_market_journal_value 로 마켓마다 블록을 받는다(V36/V40 채번 규약).
-- 마켓 1 은 마지막 3건, 마켓 5 는 마지막 5건 명령에 이벤트가 없다(미정산 꼬리 = 구멍).
\set ON_ERROR_STOP 1
\timing on
SELECT cmd_seq AS bound FROM journal_axis_boundary WHERE name = 'pre_v36_event' \gset
\echo bound=:bound
CREATE TABLE bench.holes AS
SELECT seq, market_id FROM (
  SELECT seq, market_id, row_number() OVER (PARTITION BY market_id ORDER BY seq DESC) AS rn
  FROM command_journal WHERE seq > :bound) t
WHERE (market_id = 1 AND rn <= 3) OR (market_id = 5 AND rn <= 5);

CREATE TABLE bench.evt_block AS
SELECT m.market_id, m.n, next_market_journal_value(m.market_id, 'event_seq', m.n) AS start
FROM (SELECT c.market_id, sum(bench.nevt(c.seq))::bigint AS n
      FROM command_journal c
      WHERE c.seq > :bound AND NOT EXISTS (SELECT 1 FROM bench.holes h WHERE h.seq = c.seq)
      GROUP BY c.market_id) m;

INSERT INTO event_journal (event_seq, source_cmd_seq, event_ordinal, event_type, payload,
                           payload_schema_version, fencing_epoch, created_at, market_id, market_seq)
SELECT c.market_id * sp.band_span + b.start - 1
           + row_number() OVER (PARTITION BY c.market_id ORDER BY c.seq, o),
       c.seq, o,
       CASE WHEN c.command_type = 'CANCEL' THEN 'CANCELED'
            WHEN o = 0 THEN 'ACCEPTED' ELSE 'TRADE' END,
       CASE WHEN c.command_type = 'CANCEL'
                 THEN jsonb_build_object('orderId', c.assigned_order_id, 'qty', '0.01230000', 'reason', 'USER')
            WHEN o = 0 THEN jsonb_build_object('orderId', c.assigned_order_id)
            ELSE jsonb_build_object('price', c.payload->>'price', 'qty', '0.00410000',
                                    'makerOid', c.assigned_order_id - o, 'takerOid', c.assigned_order_id,
                                    'makerFee', '10.25', 'takerFee', '10.25') END,
       1, 1, c.created_at,
       c.market_id,
       b.start - 1 + row_number() OVER (PARTITION BY c.market_id ORDER BY c.seq, o)
FROM command_journal c
JOIN bench.evt_block b ON b.market_id = c.market_id
CROSS JOIN (SELECT band_span FROM journal_band_spec WHERE name = 'event_seq') sp
CROSS JOIN LATERAL generate_series(0, bench.nevt(c.seq) - 1) o
WHERE c.seq > :bound AND NOT EXISTS (SELECT 1 FROM bench.holes h WHERE h.seq = c.seq)
ORDER BY c.seq, o;
