#!/usr/bin/env bash
# 최대 TPS 계단. 전후 조건을 같게 하려고 주입률과 시간을 고정한다.
set -u
LABEL="$1"
S="C:/Users/DEV-PC/AppData/Local/Temp/claude/C--Users-DEV-PC-Desktop-----/8a354c13-3cf3-45b9-9e21-f7f86867fc9a/scratchpad"
OUT="$S/v27_$LABEL"; mkdir -p "$OUT"
: > "$OUT/ladder.txt"
PSQL() { docker exec chaos-pg psql -U exchange -d exchange -tAc "$1" 2>/dev/null; }

for RATE in 40 60 80 100; do
  B=$(PSQL "SELECT count(*) FROM orders;")
  RUN_ID="ld${LABEL:0:3}${RATE}$(date +%H%M%S)"
  docker run --rm --network chaos-net \
    -v "C:/dev/exchange/harness/load:/load" -w /load \
    -e BASE_URL="http://chaos-api-1:8080" -e RUN_ID="$RUN_ID" -e PROFILE=smoke \
    -e STEADY_RATE="$RATE" -e READ_RATE=20 -e WS_VUS=1 -e MARKET=KRW-BTC \
    grafana/k6:1.4.0 run --quiet --no-color \
    --env K6_KEYS=./secrets/k6_keys.json order_flow.js > "$OUT/k6_$RATE.txt" 2>&1
  A=$(PSQL "SELECT count(*) FROM orders;")
  P95=$(grep -oP 'accepted_req_duration.*p\(95\)=\K[0-9.]+m?s' "$OUT/k6_$RATE.txt" | head -1)
  Q=$(PSQL "SELECT coalesce(max(queue_depth),0) FROM (SELECT 0 AS queue_depth) t;")
  echo "rate=$RATE accepted=$((A-B)) p95=${P95:-NA}" | tee -a "$OUT/ladder.txt"
done
echo "LADDER $LABEL done"
