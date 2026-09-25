-- C22' 수 판(앱 enum·하네스 SQL 후보). 구간은 width_bucket 으로 한 번에 찾고, 시간 축 팔과 구간 경계 팔을 한 번의 정렬로 센다.
WITH seg AS (
    SELECT array_agg(CASE WHEN x.rn = 1 THEN 0 ELSE x.epoch_floor + 1 END ORDER BY x.id) AS lb,
           array_agg(x.protocol ORDER BY x.id) AS prot,
           array_agg(x.ended_at ORDER BY x.id) AS ended
      FROM (SELECT h.id, h.protocol, h.epoch_floor, row_number() OVER (ORDER BY h.id) AS rn,
                   lead(h.changed_at) OVER (ORDER BY h.id) AS ended_at
              FROM leader_protocol_history h) x
), part AS (
    SELECT market_id, CASE resource WHEN 'matching-p0' THEN 1 WHEN 'matching-p1' THEN 2 ELSE 9 END AS k
      FROM market_lease_partition
), j AS (
    SELECT c.seq, c.fencing_token, c.created_at, c.market_id,
           greatest(width_bucket(c.fencing_token, s.lb), 1) AS b, s.prot, s.ended
      FROM command_journal c CROSS JOIN seg s
)
SELECT (SELECT count(*) FILTER (WHERE t.prev > t.fencing_token) + count(*) FILTER (WHERE t.bnd)
          FROM (SELECT j.fencing_token,
                       lag(j.fencing_token) OVER (PARTITION BY CASE WHEN j.prot[j.b] = 3 THEN coalesce(p.k, 9) ELSE 0 END
                                                  ORDER BY j.created_at, j.seq) AS prev,
                       j.created_at >= j.ended[j.b] AS bnd
                  FROM j LEFT JOIN part p ON p.market_id = j.market_id) t)
     + (SELECT count(*) FROM (
            SELECT c.fencing_token, lag(c.fencing_token) OVER (PARTITION BY c.market_id ORDER BY c.seq) AS prev
              FROM command_journal c) t
         WHERE t.prev > t.fencing_token);
