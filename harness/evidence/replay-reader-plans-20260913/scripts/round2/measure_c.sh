#!/bin/sh
# C: 후보 인덱스의 쓰기 비용 대리 측정(합성 대량 삽입, 정산 경로 아님).
# 회차마다 200,000 행 = 500 행 x 400 트랜잭션. 인덱스 없음/있음을 교대로 두 번씩(드리프트 상쇄).
set -eu
P="psql -X -q -U postgres -d exchange -v ON_ERROR_STOP=1"
OUT=/out/followup/C
mkdir -p "$OUT"
IDX=event_journal_market_id_source_cmd_seq_event_ordinal_idx

run() {
  label=$1; base=$2
  have=$($P -tAc "SELECT count(*) FROM pg_indexes WHERE tablename IN ('event_journal_p0', 'event_journal_default') AND indexdef LIKE '%(market_id, source_cmd_seq, event_ordinal)%'")
  before=$($P -tAc "SELECT count(*) FROM event_journal")
  $P -c "CHECKPOINT"
  {
    echo '\i /bench/followup/prep_c.sql'
    echo 'SELECT clock_timestamp() AS t0 \gset'
    b=0
    while [ $b -lt 400 ]; do echo "EXECUTE ins($base, $((b * 500)));"; b=$((b + 1)); done
    echo "SELECT round((extract(epoch FROM clock_timestamp() - :'t0'::timestamptz) * 1000)::numeric, 1) AS total_ms \\gset"
    echo "SELECT count(*) AS inserted FROM event_journal WHERE source_cmd_seq BETWEEN $base AND $base + 99999 \\gset"
    echo "SELECT round(:inserted / (:total_ms / 1000.0), 1) AS rows_per_s \\gset"
    echo "\\echo RESULT label=$label cand_index_leaves=$have rows_before=$before total_ms=:total_ms inserted=:inserted rows_per_s=:rows_per_s"
  } | $P > "$OUT/$label.txt"
  grep RESULT "$OUT/$label.txt"
}

$P -c "DROP INDEX IF EXISTS $IDX"
run noidx_r1 1000000
$P -c "CREATE INDEX $IDX ON event_journal (market_id, source_cmd_seq, event_ordinal)"
run idx_r1 2000000
$P -c "DROP INDEX $IDX"
run noidx_r2 3000000
$P -c "CREATE INDEX $IDX ON event_journal (market_id, source_cmd_seq, event_ordinal)"
run idx_r2 4000000
$P -tAc "SELECT 'counter_check', market_id, next_value - 1, (SELECT max(market_seq) FROM event_journal e WHERE e.market_id = k.market_id) FROM market_journal_counters k WHERE name = 'event_seq' ORDER BY market_id" > "$OUT/counter_check.txt"
$P -tAc "SELECT 'derivation_mismatch', count(*) FROM event_journal WHERE market_id IS NOT NULL AND event_seq <> market_id * 10000000 + market_seq" >> "$OUT/counter_check.txt"
cat "$OUT/counter_check.txt"
echo "done C"
