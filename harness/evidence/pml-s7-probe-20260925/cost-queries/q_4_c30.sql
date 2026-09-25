-- C30 초안의 행 수
SELECT count(*) FROM (
-- ⑳ C30 파티션 epoch 의 자원 대조. P3 구간의 epoch 로 기록된 명령·이벤트·체결·스냅샷 행 가운데 그 epoch 의 획득 이력이 없거나,
--    획득(ACQUIRE)이 아니거나, 획득 때 모드가 3 이 아니거나, 획득 자원이 그 행 마켓의 파티션과 다른 행을 낸다(0행이 정상).
WITH seg AS (
    SELECT h.protocol,
           CASE WHEN h.id = min(h.id) OVER () THEN -1 ELSE h.epoch_floor END AS lo,
           lead(h.epoch_floor) OVER (ORDER BY h.id) AS hi
      FROM leader_protocol_history h
), p3 AS (
    SELECT lo, hi FROM seg WHERE protocol = 3
), w AS (
    SELECT 'command_journal' AS tbl, c.seq AS subject, c.market_id, c.fencing_token AS epoch FROM command_journal c
    UNION ALL SELECT 'event_journal', e.event_seq, e.market_id, e.fencing_epoch FROM event_journal e
    UNION ALL SELECT 'trades', t.id, t.market_id, t.fencing_epoch FROM trades t
    UNION ALL SELECT 'snapshots', s.snapshot_id, s.market_id, s.fencing_epoch FROM snapshots s
)
SELECT w.tbl, w.subject
  FROM w
  JOIN p3 ON w.epoch > p3.lo AND (p3.hi IS NULL OR w.epoch <= p3.hi)
  LEFT JOIN lease_acquisitions a ON a.epoch = w.epoch
  LEFT JOIN market_lease_partition m ON m.market_id = w.market_id
 WHERE a.epoch IS NULL OR a.origin <> 'ACQUIRE' OR a.protocol <> 3
    OR m.resource IS NULL OR m.resource <> a.resource
) x;
