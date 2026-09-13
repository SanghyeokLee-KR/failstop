#!/bin/sh
# usage: jitoff.sh <state>   쿼리 3 을 jit=off 로 3회. JIT 컴파일 비용과 실행 비용을 가른다.
set -eu
P="psql -X -q -U postgres -d exchange -v ON_ERROR_STOP=1"
OUT=/out/$1
BOUND=$($P -tAc "SELECT cmd_seq FROM journal_axis_boundary WHERE name = 'pre_v36_event'")
J=$($P -tAc "SELECT j FROM bench.cursors WHERE name = 'q3_near'")
for name in q3_a_near q3_b_empty; do
  if [ "$name" = q3_a_near ]; then js=$J; else js='{}'; fi
  {
    echo "SET jit = off;"
    echo '\i /bench/prep.sql'
    for i in 1 2 3; do printf "EXPLAIN (ANALYZE, BUFFERS) EXECUTE q3('%s', %s);\n" "$js" "$BOUND"; done
  } | $P > "$OUT/${name}__jitoff.txt"
done
echo "done jitoff $1"
