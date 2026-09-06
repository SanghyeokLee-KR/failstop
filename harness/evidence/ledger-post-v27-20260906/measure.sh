#!/usr/bin/env bash
# V27 전후 대조용 고정 측정. 인자는 라벨 하나. 전후 조건을 같게 하려고 스크립트로 고정한다.
# 사용: measure.sh <before|after> [주문/s] [초]
set -u
LABEL="$1"; RATE="${2:-30}"; SECS="${3:-90}"
S="C:/Users/DEV-PC/AppData/Local/Temp/claude/C--Users-DEV-PC-Desktop-----/8a354c13-3cf3-45b9-9e21-f7f86867fc9a/scratchpad"
OUT="$S/v27_$LABEL"; mkdir -p "$OUT"
PSQL() { docker exec chaos-pg psql -U exchange -d exchange -tAc "$1" 2>/dev/null; }

PSQL "SELECT pg_stat_statements_reset();" > /dev/null
BEFORE_ORDERS=$(PSQL "SELECT count(*) FROM orders;")

( i=0; while [ $i -lt $((SECS+20)) ]; do
    docker stats --no-stream --format '{{.Name}} {{.CPUPerc}}' chaos-pg chaos-api-1 2>/dev/null \
      | sed "s/^/$i /" >> "$OUT/cpu.txt"
    i=$((i+3)); sleep 3
  done ) &
SAMPLER=$!

RUN_ID="v27${LABEL:0:3}$(date +%H%M%S)"
docker run --rm --network chaos-net \
  -v "C:/dev/exchange/harness/load:/load" -w /load \
  -e BASE_URL="http://chaos-api-1:8080" -e RUN_ID="$RUN_ID" -e PROFILE=smoke \
  -e STEADY_RATE="$RATE" -e READ_RATE=20 -e WS_VUS=1 -e MARKET=KRW-BTC \
  grafana/k6:1.4.0 run --quiet --no-color \
  --env K6_KEYS=./secrets/k6_keys.json order_flow.js > "$OUT/k6.txt" 2>&1
wait $SAMPLER 2>/dev/null

AFTER_ORDERS=$(PSQL "SELECT count(*) FROM orders;")
echo "orders_delta=$((AFTER_ORDERS-BEFORE_ORDERS))" > "$OUT/summary.txt"

# 함수·쿼리별 수행시간. 이번 회차의 판정 대상만 뽑는다.
PSQL "SELECT calls, round(total_exec_time::numeric,1) AS total_ms,
             round(mean_exec_time::numeric,3) AS mean_ms, left(query, 90) AS q
      FROM pg_stat_statements
      WHERE query ILIKE '%post_ledger_transaction%'
         OR query ILIKE '%reserve_funds%'
         OR query ILIKE '%apply_position_cost%'
      ORDER BY total_exec_time DESC LIMIT 10;" > "$OUT/funcs.txt"

# 제거 대상 재조회 셋의 호출 수
PSQL "SELECT calls, round(total_exec_time::numeric,1) AS total_ms, left(query,110) AS q
      FROM pg_stat_statements
      WHERE query ILIKE '%ledger_entries%' AND query ILIKE '%transaction_id%'
      ORDER BY calls DESC LIMIT 12;" > "$OUT/rereads.txt"

# DB 총 시간과 주문당 DB 시간
PSQL "SELECT round(sum(total_exec_time)::numeric,1) FROM pg_stat_statements;" > "$OUT/db_total_ms.txt"

echo "LABEL=$LABEL RATE=$RATE orders=$((AFTER_ORDERS-BEFORE_ORDERS)) done"
