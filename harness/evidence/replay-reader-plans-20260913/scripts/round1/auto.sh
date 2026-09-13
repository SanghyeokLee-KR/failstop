#!/bin/sh
# 기본 plan_cache_mode(auto) 에서 반복 실행 뒤 어떤 계획이 쓰이는지 본다. 후보 인덱스가 있는 상태에서 돌린다.
# JDBC 는 prepareThreshold(기본 5) 이후 서버측 준비문을 쓰고, 그 준비문은 커넥션에 남아 마켓을 넘어 재사용된다.
set -eu
P="psql -X -q -U postgres -d exchange -v ON_ERROR_STOP=1"
OUT=/out/idx
PS="SELECT name, generic_plans, custom_plans FROM pg_prepared_statements WHERE name = 'q1';"

{ echo '\i /bench/prep.sql'; echo '\o /dev/null'
  for i in 1 2 3; do echo "EXECUTE q1(12, 100503, 0, 500);"; done
  echo '\o'; echo "EXPLAIN (ANALYZE, BUFFERS) EXECUTE q1(12, 100503, 0, 500);"; echo "$PS"
} | $P > $OUT/auto__m12_4th.txt

{ echo '\i /bench/prep.sql'; echo '\o /dev/null'
  for i in 1 2 3 4 5 6 7 8 9 10; do echo "EXECUTE q1(12, 100503, 0, 500);"; done
  echo '\o'; echo "EXPLAIN (ANALYZE, BUFFERS) EXECUTE q1(12, 100503, 0, 500);"; echo "$PS"
} | $P > $OUT/auto__m12_after_m12.txt

{ echo '\i /bench/prep.sql'; echo '\o /dev/null'
  for i in 1 2 3 4 5 6 7 8 9 10; do echo "EXECUTE q1(1, 100001, 0, 500);"; done
  echo '\o'; echo "EXPLAIN (ANALYZE, BUFFERS) EXECUTE q1(12, 100503, 0, 500);"
  echo "EXPLAIN (ANALYZE, BUFFERS) EXECUTE q1(12, 248695, 2, 500);"; echo "$PS"
} | $P > $OUT/auto__m12_after_m1.txt

for f in $OUT/auto__*.txt; do
  echo "## $f"
  grep -E "Index Scan using .*on event_journal_p0 |Rows Removed|Execution Time| q1 " "$f" || true
done
