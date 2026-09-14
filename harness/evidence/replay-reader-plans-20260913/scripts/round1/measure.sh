#!/bin/sh
# usage: measure.sh <state>   (state = noidx | idx)
# 조합마다 plan_cache_mode 두 가지(custom = 값으로 계획, generic = JDBC 서버측 준비문이
# 반복 호출 뒤 고를 수 있는 일반 계획)로, 한 세션에서 EXPLAIN (ANALYZE, BUFFERS) 3회.
set -eu
STATE=$1
OUT=/out/$STATE
mkdir -p "$OUT"
P="psql -X -q -U postgres -d exchange -v ON_ERROR_STOP=1"
val() { $P -tAc "SELECT $2 FROM bench.cursors WHERE name = '$1'"; }
BOUND=$($P -tAc "SELECT cmd_seq FROM journal_axis_boundary WHERE name = 'pre_v36_event'")

run() {
  name=$1
  ex=$2
  for mode in force_custom_plan force_generic_plan; do
    f="$OUT/${name}__${mode}.txt"
    {
      echo "SET plan_cache_mode = $mode;"
      echo '\i /bench/prep.sql'
      echo "\\echo $ex"
      for i in 1 2 3; do printf 'EXPLAIN (ANALYZE, BUFFERS) %s;\n' "$ex"; done
    } | $P > "$f"
  done
}

for m in 1 12; do
  for pos in start mid near end; do
    v=$(val "q1_m${m}_$pos" v); o=$(val "q1_m${m}_$pos" o)
    run "q1_m${m}_$pos" "EXECUTE q1($m, $v, $o, 500)"
  done
  for pos in start mid near; do
    v=$(val "q2_m${m}_$pos" v); o=$(val "q2_m${m}_$pos" o)
    run "q2_m${m}_$pos" "EXECUTE q2($m, $BOUND, $v, $BOUND, $v, $o, 500, $m, $v, $o, 500, 500)"
  done
  # 현행 ReplayService 첫 호출: 4인자 오버로드가 legacyBound=0, 커서 (0,-1) 로 레거시 분기에 들어간다.
  run "q2_m${m}_bound0" "EXECUTE q2($m, 0, 0, 0, 0, -1, 500, $m, 0, -1, 500, 500)"
  head=$(val "head_m$m" v)
  for pos in start mid near; do
    v=$(val "cmd_m${m}_$pos" v)
    run "qc_m${m}_$pos" "EXECUTE qc($v, $m, $head, 500)"
  done
done
J=$(val q3_near j)
run "q3_a_near" "EXECUTE q3('$J', $BOUND)"
run "q3_b_empty" "EXECUTE q3('{}', $BOUND)"
echo "done $STATE"
