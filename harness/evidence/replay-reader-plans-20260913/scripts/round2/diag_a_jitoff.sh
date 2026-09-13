#!/bin/sh
# A 진단의 jit=off 판. 정렬 비활성은 비용에 disable_cost 를 더해 JIT 임계를 넘기므로
# 실행 시간에서 JIT 컴파일을 떼어 보려고 따로 돈다. 해법이 아니라 경로 비용 확인용이다.
set -eu
P="psql -X -q -U postgres -d exchange -v ON_ERROR_STOP=1"
OUT=/out/followup/A
BOUND=$($P -tAc "SELECT cmd_seq FROM journal_axis_boundary WHERE name = 'pre_v36_event'")
v=$($P -tAc "SELECT v FROM bench.cursors WHERE name = 'q2_m1_start'")
o=$($P -tAc "SELECT o FROM bench.cursors WHERE name = 'q2_m1_start'")

diag() {
  f=$1; ex=$2
  {
    echo '\i /bench/followup/prep_a.sql'
    echo 'SET plan_cache_mode = force_custom_plan;'
    echo 'SET jit = off; SET enable_sort = off; SET enable_incremental_sort = off;'
    for i in 1 2 3; do printf 'EXPLAIN (ANALYZE, BUFFERS) %s;\n' "$ex"; done
    echo "SELECT 'COUNTS', name, generic_plans, custom_plans FROM pg_prepared_statements WHERE generic_plans + custom_plans > 0;"
  } | $P > "$f"
}
diag "$OUT/diag_nosort_jitoff_q2_lit_m1_start__force_custom_plan.txt" \
  "EXECUTE q2_lit_m1($BOUND, $v, $BOUND, $v, $o, 500, $v, $o, 500, 500)"
diag "$OUT/diag_nosort_jitoff_q2_lit_m1_bound0__force_custom_plan.txt" \
  "EXECUTE q2_lit_m1(0, 0, 0, 0, -1, 500, 0, -1, 500, 500)"
echo "done diag jitoff"
