-- C22' 수 판, 한 번의 표 읽기. 구간은 width_bucket, 파티션 키는 정수, 두 창 함수를 같은 행 집합에 건다.
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
           CASE WHEN s.prot[greatest(width_bucket(c.fencing_token, s.lb), 1)] = 3 THEN coalesce(p.k, 9) ELSE 0 END AS arm_key,
           c.created_at >= s.ended[greatest(width_bucket(c.fencing_token, s.lb), 1)] AS bnd
      FROM command_journal c CROSS JOIN seg s LEFT JOIN part p ON p.market_id = c.market_id
)
SELECT count(*) FILTER (WHERE t.prev_time > t.fencing_token)
     + count(*) FILTER (WHERE t.prev_market > t.fencing_token)
     + count(*) FILTER (WHERE t.bnd)
  FROM (SELECT j.fencing_token, j.bnd,
               lag(j.fencing_token) OVER (PARTITION BY j.arm_key ORDER BY j.created_at, j.seq) AS prev_time,
               lag(j.fencing_token) OVER (PARTITION BY j.market_id ORDER BY j.seq) AS prev_market
          FROM j) t;
