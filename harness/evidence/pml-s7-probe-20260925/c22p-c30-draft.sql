-- ADR 0034 S7 불변식 초안. DB 설계 §6.4 ⑯(C22')과 ⑳(C30)의 SQL 이고, 확인 기록(s7-pg-apply.sh)이 이 파일을 그대로 읽는다.
-- 구간을 epoch_floor 로 가르는 이유는 V48 주석(감사는 시계 대신 epoch 범위로 구간을 가른다)이다.

-- ⑯ fencing_epoch 단조성 감사(P0-1, v2.59 C22'): assert_current_leader 호출 규율은 앱 책임이므로 저장된 epoch 열의 사후 감사로
--    보완한다. 세 팔의 위반 행을 낸다(0행이 정상). 프로토콜 구간은 leader_protocol_history 의 epoch_floor 로 가른다. 구간 k 의
--    epoch 는 (floor_k, floor_{k+1}] 이고 첫 행(V48 시드) 앞의 epoch 는 첫 구간이다. 이력이 없는 V47 이하 DB 는 하네스 SQL 이 종전 판을 쓴다.
--    시간 축 팔: P1·P2 구간 행은 전역 하나, P3 구간 행은 그 행 마켓의 파티션별로 lag(fencing_token) OVER (ORDER BY created_at, seq).
--    마켓 안 팔: 종전 그대로 lag(fencing_token) OVER (PARTITION BY market_id ORDER BY seq).
--    구간 경계 팔: 어떤 구간의 epoch 로 기록된 행의 created_at 이 다음 전환의 changed_at 이후. 전환은 모든 리스 행을 잠가 진행 중
--    쓰기의 커밋을 기다린 뒤 모드를 바꾸므로 그 뒤에 시작한 트랜잭션이 옛 구간 epoch 로 커밋할 수 없다(DB 설계 §3.5·§5.4-e).
--    시간 축 팔과 구간 경계 팔은 DB 서버 시계 역행 뒤 거짓 양성을 낼 수 있다(ADR 0022 9-17).
WITH seg AS (
    SELECT h.protocol,
           CASE WHEN h.id = min(h.id) OVER () THEN -1 ELSE h.epoch_floor END AS lo,
           lead(h.epoch_floor) OVER (ORDER BY h.id) AS hi,
           lead(h.changed_at) OVER (ORDER BY h.id) AS ended_at
      FROM leader_protocol_history h
), j AS (
    SELECT c.seq, c.market_id, c.created_at, c.fencing_token, s.ended_at,
           CASE WHEN s.protocol = 3 THEN coalesce(m.resource, '?') ELSE '*' END AS arm_key
      FROM command_journal c
      LEFT JOIN seg s ON c.fencing_token > s.lo AND (s.hi IS NULL OR c.fencing_token <= s.hi)
      LEFT JOIN market_lease_partition m ON m.market_id = c.market_id
)
SELECT 'time' AS arm, t.seq FROM (
    SELECT j.seq, j.fencing_token,
           lag(j.fencing_token) OVER (PARTITION BY j.arm_key ORDER BY j.created_at, j.seq) AS prev
      FROM j) t
 WHERE t.prev > t.fencing_token
UNION ALL
SELECT 'market', t.seq FROM (
    SELECT c.seq, c.fencing_token, lag(c.fencing_token) OVER (PARTITION BY c.market_id ORDER BY c.seq) AS prev
      FROM command_journal c) t
 WHERE t.prev > t.fencing_token
UNION ALL
SELECT 'boundary', j.seq FROM j WHERE j.ended_at IS NOT NULL AND j.created_at >= j.ended_at;   -- 0행

-- ⑳ C30 파티션 epoch 의 자원 대조(v2.59, ADR 0034 S7). P3 구간의 epoch 로 기록된 명령·이벤트·체결·스냅샷 행 가운데 그 epoch 의
--    획득 이력이 없거나, 획득(ACQUIRE)이 아니거나, 획득 때 모드가 3 이 아니거나, 획득 자원이 그 행 마켓의 파티션과 다른 행을
--    낸다(0행이 정상). 파티션을 넘는 쓰기(split-brain)는 P3 구간의 시간 축 팔이 파티션별이라 C22' 가 보지 못하므로 이것이 센다.
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
    OR m.resource IS NULL OR m.resource <> a.resource;                                          -- 0행
