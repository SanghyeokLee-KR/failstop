-- C: 합성 대량 삽입 대리 측정. 정산 경로가 아니다.
-- 한 EXECUTE = 한 트랜잭션(autocommit) = 500 행. 명령당 이벤트 2건(ordinal 0 ACCEPTED, 1 TRADE),
-- 마켓은 bench.mkt 분포, market_seq 는 next_market_journal_value 로 마켓별 카운터를 이어 받는다.
-- $1 = 이 회차 source_cmd_seq 시작, $2 = 배치 오프셋(0, 500, ...).
PREPARE ins(bigint, int) AS
WITH ev AS (
    SELECT $1 + i / 2 AS src, i % 2 AS ord, bench.mkt($1 + i / 2) AS m, i
    FROM generate_series($2, $2 + 499) i
), blk AS (
    SELECT m, next_market_journal_value(m, 'event_seq', count(*)) AS start
    FROM ev GROUP BY m
), num AS (
    SELECT ev.src, ev.ord, ev.m, ev.i,
           blk.start - 1 + row_number() OVER (PARTITION BY ev.m ORDER BY ev.i) AS mseq
    FROM ev JOIN blk ON blk.m = ev.m
)
INSERT INTO event_journal (event_seq, source_cmd_seq, event_ordinal, event_type, payload,
                           payload_schema_version, fencing_epoch, created_at, market_id, market_seq)
SELECT m * 10000000::bigint + mseq, src, ord,
       CASE WHEN ord = 0 THEN 'ACCEPTED' ELSE 'TRADE' END,
       CASE WHEN ord = 0 THEN jsonb_build_object('orderId', src)
            ELSE jsonb_build_object('price', (50000000 + (src % 1000) * 1000)::text, 'qty', '0.00410000',
                                    'makerOid', src - 1, 'takerOid', src,
                                    'makerFee', '10.25', 'takerFee', '10.25') END,
       1, 1, timestamptz '2026-09-01 00:00:00+00' + src * interval '10 milliseconds',
       m, mseq
FROM num
ORDER BY i;
