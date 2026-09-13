#!/bin/sh
# 한 준비문을 12 마켓이 돌아가며 쓰는 순서 실험(auto). 커넥션 하나가 기동 직후 (b) 한 바퀴를 돈 뒤
# 정상 상태 (a) 로 넘어가는 경우와 처음부터 (a) 만 도는 경우를 비교한다.
#   b_then_a: (b) 12회, (a) 24회 실행 뒤 (a) 12 마켓 EXPLAIN ANALYZE
#   a_only  : (a) 36회 실행 뒤 (a) 12 마켓 EXPLAIN ANALYZE
set -eu
P="psql -X -q -U postgres -d exchange -v ON_ERROR_STOP=1"
OUT=/out/followup2/seq
mkdir -p "$OUT"
BOUND=$($P -tAc "SELECT cmd_seq FROM journal_axis_boundary WHERE name = 'pre_v36_event'")
J=$($P -tAc "SELECT j FROM bench.cursors WHERE name = 'q3_near'")
near() { $P -tAc "SELECT ('$J'::jsonb ->> '$1')"; }

sweep() {  # sweep <floor-case> <prefix>
  m=1
  while [ $m -le 12 ]; do
    if [ "$1" = a ]; then fl=$(near $m); else fl=$BOUND; fi
    printf '%sEXECUTE pm(%s, %s, %s, %s);\n' "$2" "$m" "$fl" "$m" "$fl"
    m=$((m + 1))
  done
}

for scen in b_then_a a_only; do
  {
    echo '\i /bench/followup2/prep_pm.sql'
    echo '\o /dev/null'
    if [ "$scen" = b_then_a ]; then sweep b ''; sweep a ''; sweep a ''
    else sweep a ''; sweep a ''; sweep a ''; fi
    echo '\o'
    sweep a 'EXPLAIN (ANALYZE, BUFFERS) '
    echo "SELECT 'COUNTS', name, generic_plans, custom_plans FROM pg_prepared_statements WHERE generic_plans + custom_plans > 0;"
  } | $P > "$OUT/$scen.txt"
  sum=$(grep 'Execution Time' "$OUT/$scen.txt" | sed 's/.*: \([0-9.]*\) ms/\1/' | awk '{s += $1} END {printf "%.3f", s}')
  jit=$(grep -c 'JIT:' "$OUT/$scen.txt" || true)
  counts=$(grep COUNTS "$OUT/$scen.txt" | tr -s ' ')
  echo "SEQ $scen sum_exec_ms_12_markets=$sum jit_plans=$jit $counts"
done
