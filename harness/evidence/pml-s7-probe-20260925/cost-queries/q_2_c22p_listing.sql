-- C22' 초안(목록 판)의 행 수
SELECT count(*) FROM (
-- ⑯ C22' fencing_token 단조. 세 팔의 위반 행을 낸다(0행이 정상).
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
SELECT 'boundary', j.seq FROM j WHERE j.ended_at IS NOT NULL AND j.created_at >= j.ended_at

) x;
