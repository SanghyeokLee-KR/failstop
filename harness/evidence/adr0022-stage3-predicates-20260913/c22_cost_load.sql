-- 합성 행은 커트오버 뒤 배치로 넣는다. 마켓마다 대역 잎(seq = market_id * band_span + 지역 번호)에 들어가고
-- created_at 은 마켓을 돌아가며 1ms 씩 늘어 잎마다 시간 순 묶음이 서로 엇갈린다. 한 잎에 몰아 넣으면
-- 정렬 입력이 이미 (created_at, seq) 순서라 최선의 경우를 잰다.
WITH b AS (
    SELECT (SELECT band_span FROM journal_band_spec WHERE name = 'command_seq') AS span,
           (SELECT max(fencing_token) FROM command_journal) AS epoch,
           (SELECT max(created_at) FROM command_journal) AS max_ts,
           (SELECT array_agg(id ORDER BY id) FROM markets) AS ids
)
INSERT INTO command_journal (seq, market_id, command_type, user_id, client_order_id, assigned_order_id,
                             engine_ts, tie_break, payload, fencing_token, checksum, created_at)
SELECT b.ids[1 + (g - 1) % cardinality(b.ids)] * b.span + (g - 1) / cardinality(b.ids) + 1,
       b.ids[1 + (g - 1) % cardinality(b.ids)], 'CANCEL', 1, NULL, g, 0, 0, '{}',
       b.epoch, 0, b.max_ts + g * interval '1 millisecond'
FROM b CROSS JOIN generate_series(1, 1000000) g;
ANALYZE command_journal;
