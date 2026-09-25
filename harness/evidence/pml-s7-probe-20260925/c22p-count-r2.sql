-- C22' 수 판, 설계 검토 대응 판(S7 설계 4-7·11절). cost-queries/q_6_c22p_arrays.sql 에 구간 경계 팔의 snapshots 항을 더했다.
-- 앱 enum(InvariantCheck C22_FENCING_MONOTONIC)과 하네스 SQL 두 벌이 이 술어를 수로 센다. 행 수는 DB 설계 §6.4 ⑯ 목록 판과 같아야 한다.
-- 구간은 width_bucket 으로 한 번 찾고 대응 표는 마켓 id 위치의 배열로 읽는다. 구간 경계는 command_journal·snapshots 의 created_at
-- (DB 서버의 트랜잭션 시작 시각)만 본다. event_journal·trades 의 created_at 은 engine_ts 파생이라 넣지 않는다.
WITH seg AS (
    SELECT array_agg(CASE WHEN x.rn = 1 THEN 0 ELSE x.epoch_floor + 1 END ORDER BY x.id) AS lb,
           array_agg(x.protocol ORDER BY x.id) AS prot,
           array_agg(x.ended_at ORDER BY x.id) AS ended,
           (SELECT array_agg(CASE p.resource WHEN 'matching-p0' THEN 1 WHEN 'matching-p1' THEN 2 ELSE 9 END ORDER BY m.id)
              FROM generate_series(1, (SELECT coalesce(max(id), 0) FROM markets)) m(id)
              LEFT JOIN market_lease_partition p ON p.market_id = m.id) AS part
      FROM (SELECT h.id, h.protocol, h.epoch_floor, row_number() OVER (ORDER BY h.id) AS rn,
                   lead(h.changed_at) OVER (ORDER BY h.id) AS ended_at
              FROM leader_protocol_history h) x
), j AS (
    SELECT c.seq, c.fencing_token, c.created_at, c.market_id, s.prot, s.ended, s.part,
           greatest(width_bucket(c.fencing_token, s.lb), 1) AS b
      FROM command_journal c CROSS JOIN seg s
)
SELECT (SELECT count(*) FILTER (WHERE t.prev > t.fencing_token) + count(*) FILTER (WHERE t.bnd)
          FROM (SELECT j.fencing_token,
                       lag(j.fencing_token) OVER (PARTITION BY CASE WHEN j.prot[j.b] = 3 THEN coalesce(j.part[j.market_id], 9) ELSE 0 END
                                                  ORDER BY j.created_at, j.seq) AS prev,
                       j.created_at >= j.ended[j.b] AS bnd
                  FROM j) t)
     + (SELECT count(*) FROM (
            SELECT c.fencing_token, lag(c.fencing_token) OVER (PARTITION BY c.market_id ORDER BY c.seq) AS prev
              FROM command_journal c) t
         WHERE t.prev > t.fencing_token)
     + (SELECT count(*) FROM snapshots n CROSS JOIN seg s
         WHERE n.created_at >= s.ended[greatest(width_bucket(n.fencing_epoch, s.lb), 1)]);
