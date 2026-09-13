#!/bin/sh
# 마켓당 경계 문장 x 12 마켓 x floor (a) 엔진 커서 head 근처 (b) legacyBound (c) 0 x 계획 모드. jit 기본값.
#   force_custom_plan : EXPLAIN ANALYZE 3회
#   auto              : 같은 인자로 EXECUTE 10회 뒤 EXPLAIN ANALYZE 3회, 이어 generic/custom 계획 수
#   force_generic_plan: EXPLAIN ANALYZE 3회
set -eu
P="psql -X -q -U postgres -d exchange -v ON_ERROR_STOP=1"
OUT=/out/followup2/runs
mkdir -p "$OUT"
BOUND=$($P -tAc "SELECT cmd_seq FROM journal_axis_boundary WHERE name = 'pre_v36_event'")
$P -tAc "SELECT 'jit=' || current_setting('jit') || ' jit_above_cost=' || current_setting('jit_above_cost') || ' jit_inline_above_cost=' || current_setting('jit_inline_above_cost') || ' jit_optimize_above_cost=' || current_setting('jit_optimize_above_cost') || ' legacyBound=$BOUND'" > /out/followup2/settings.txt
$P -tAc "SELECT j FROM bench.cursors WHERE name = 'q3_near'" >> /out/followup2/settings.txt

session() {
  f=$1; mode=$2; ex=$3
  {
    echo '\i /bench/followup2/prep_pm.sql'
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

m=1
while [ $m -le 12 ]; do
  near=$($P -tAc "SELECT (j::jsonb ->> '$m') FROM bench.cursors WHERE name = 'q3_near'")
  for cs in a b c; do
    case $cs in a) fl=$near ;; b) fl=$BOUND ;; c) fl=0 ;; esac
    for mode in force_custom_plan auto force_generic_plan; do
      session "$OUT/pm_m${m}_${cs}__$mode.txt" $mode "EXECUTE pm($m, $fl, $m, $fl)"
    done
  done
  m=$((m + 1))
done
for mode in force_custom_plan auto force_generic_plan; do
  session "$OUT/ms__$mode.txt" $mode "EXECUTE ms"
done
echo "done followup2"
