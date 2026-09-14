#!/bin/sh
# A: 마켓 인자(param) 대 리터럴(lit). 세션마다 새 준비문.
#   auto              : EXECUTE 10회 뒤 EXPLAIN ANALYZE 3회(11~13번째 실행), 이어 generic/custom 계획 수
#   force_custom_plan : EXPLAIN ANALYZE 3회
#   force_generic_plan: EXPLAIN ANALYZE 3회
set -eu
P="psql -X -q -U postgres -d exchange -v ON_ERROR_STOP=1"
OUT=/out/followup/A
mkdir -p "$OUT"
val() { $P -tAc "SELECT $2 FROM bench.cursors WHERE name = '$1'"; }
BOUND=$($P -tAc "SELECT cmd_seq FROM journal_axis_boundary WHERE name = 'pre_v36_event'")

session() {
  f=$1; mode=$2; ex=$3; extra=${4:-}
  {
    echo '\i /bench/prep.sql'
    echo '\i /bench/followup/prep_a.sql'
    if [ -n "$extra" ]; then echo "$extra"; fi
    if [ "$mode" = auto ]; then
      echo '\o /dev/null'
      i=0; while [ $i -lt 10 ]; do printf '%s;\n' "$ex"; i=$((i + 1)); done
      echo '\o'
    else
      echo "SET plan_cache_mode = $mode;"
    fi
    for i in 1 2 3; do printf 'EXPLAIN (ANALYZE, BUFFERS) %s;\n' "$ex"; done
    echo "SELECT 'COUNTS', name, generic_plans, custom_plans FROM pg_prepared_statements WHERE generic_plans + custom_plans > 0;"
  } | $P > "$f"
}

for m in 1 12; do
  for pos in start mid near end; do
    v=$(val "q1_m${m}_$pos" v); o=$(val "q1_m${m}_$pos" o)
    for mode in auto force_custom_plan force_generic_plan; do
      session "$OUT/q1_param_m${m}_${pos}__$mode.txt" $mode "EXECUTE q1($m, $v, $o, 500)"
      session "$OUT/q1_lit_m${m}_${pos}__$mode.txt" $mode "EXECUTE q1_lit_m$m($v, $o, 500)"
    done
  done
  for pos in start mid near bound0; do
    if [ "$pos" = bound0 ]; then
      v=0; o=-1; b=0
    else
      v=$(val "q2_m${m}_$pos" v); o=$(val "q2_m${m}_$pos" o); b=$BOUND
    fi
    for mode in auto force_custom_plan force_generic_plan; do
      session "$OUT/q2_param_m${m}_${pos}__$mode.txt" $mode "EXECUTE q2($m, $b, $v, $b, $v, $o, 500, $m, $v, $o, 500, 500)"
      session "$OUT/q2_lit_m${m}_${pos}__$mode.txt" $mode "EXECUTE q2_lit_m$m($b, $v, $b, $v, $o, 500, $v, $o, 500, 500)"
    done
  done
done

# 진단(해법 아님): 정렬을 비싸게 만들면 계획기가 후보 인덱스 순서 경로를 고르는지와 그때 비용.
DIAG="SET enable_sort = off; SET enable_incremental_sort = off;"
v=$(val q2_m1_start v); o=$(val q2_m1_start o)
session "$OUT/diag_nosort_q2_lit_m1_start__force_custom_plan.txt" force_custom_plan \
  "EXECUTE q2_lit_m1($BOUND, $v, $BOUND, $v, $o, 500, $v, $o, 500, 500)" "$DIAG"
session "$OUT/diag_nosort_q2_lit_m1_bound0__force_custom_plan.txt" force_custom_plan \
  "EXECUTE q2_lit_m1(0, 0, 0, 0, -1, 500, 0, -1, 500, 500)" "$DIAG"
echo "done A"
