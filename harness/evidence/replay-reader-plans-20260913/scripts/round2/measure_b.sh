#!/bin/sh
# B: 경계 SQL 세 형태 x 커서 (a) head 근처 / (b) 빈 입력 x 계획 모드. jit 는 기본값(on).
set -eu
P="psql -X -q -U postgres -d exchange -v ON_ERROR_STOP=1"
OUT=/out/followup/B
mkdir -p "$OUT"
BOUND=$($P -tAc "SELECT cmd_seq FROM journal_axis_boundary WHERE name = 'pre_v36_event'")
J=$($P -tAc "SELECT j FROM bench.cursors WHERE name = 'q3_near'")
IDS=$($P -tAc "SELECT '{' || string_agg(key, ',' ORDER BY key::int) || '}' FROM jsonb_each_text((SELECT j FROM bench.cursors WHERE name = 'q3_near')::jsonb)")
SEQS=$($P -tAc "SELECT '{' || string_agg(value, ',' ORDER BY key::int) || '}' FROM jsonb_each_text((SELECT j FROM bench.cursors WHERE name = 'q3_near')::jsonb)")
$P -tAc "SELECT 'jit=' || current_setting('jit') || ' jit_above_cost=' || current_setting('jit_above_cost') || ' jit_inline_above_cost=' || current_setting('jit_inline_above_cost') || ' jit_optimize_above_cost=' || current_setting('jit_optimize_above_cost')" > "$OUT/jit_settings.txt"
echo "ids=$IDS seqs=$SEQS" >> "$OUT/jit_settings.txt"

session() {
  f=$1; mode=$2; ex=$3
  {
    echo '\i /bench/prep.sql'
    echo '\i /bench/followup/prep_b.sql'
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

for cs in a b; do
  if [ "$cs" = a ]; then js=$J; ids=$IDS; seqs=$SEQS; else js='{}'; ids='{}'; seqs='{}'; fi
  for mode in force_custom_plan auto force_generic_plan; do
    session "$OUT/v1_${cs}__$mode.txt" $mode "EXECUTE q3('$js', $BOUND)"
    session "$OUT/v2_${cs}__$mode.txt" $mode "EXECUTE q3v2('$ids', '$seqs', $BOUND)"
    session "$OUT/v3_${cs}__$mode.txt" $mode "EXECUTE q3v3('$ids', '$seqs', $BOUND)"
  done
done
echo "done B"
