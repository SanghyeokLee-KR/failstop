-- V35 상태(event_journal 에 market_id 열 없음). 레거시 명령 전부에 이벤트. event_seq 는
-- 전역 채번 함수 블록을 (source_cmd_seq, event_ordinal) 순서로 조밀하게 쓴다.
-- trg_ej_register 가 켜진 채로 넣는다.
\set ON_ERROR_STOP 1
\timing on
SELECT count(*) AS n_evt
FROM command_journal c
CROSS JOIN LATERAL generate_series(0, bench.nevt(c.seq) - 1) o \gset
\echo n_evt=:n_evt
SELECT next_journal_value('event_seq', :n_evt) AS evt_start \gset
\echo evt_start=:evt_start
INSERT INTO event_journal (event_seq, source_cmd_seq, event_ordinal, event_type, payload,
                           payload_schema_version, fencing_epoch, created_at)
SELECT :evt_start::bigint - 1 + row_number() OVER (ORDER BY c.seq, o),
       c.seq, o,
       CASE WHEN c.command_type = 'CANCEL' THEN 'CANCELED'
            WHEN o = 0 THEN 'ACCEPTED' ELSE 'TRADE' END,
       CASE WHEN c.command_type = 'CANCEL'
                 THEN jsonb_build_object('orderId', c.assigned_order_id, 'qty', '0.01230000', 'reason', 'USER')
            WHEN o = 0 THEN jsonb_build_object('orderId', c.assigned_order_id)
            ELSE jsonb_build_object('price', c.payload->>'price', 'qty', '0.00410000',
                                    'makerOid', c.assigned_order_id - o, 'takerOid', c.assigned_order_id,
                                    'makerFee', '10.25', 'takerFee', '10.25') END,
       1, 1, c.created_at
FROM command_journal c
CROSS JOIN LATERAL generate_series(0, bench.nevt(c.seq) - 1) o
ORDER BY c.seq, o;
