#!/usr/bin/env bash
# V28 단계별 고정 측정. 단계마다 같은 조건을 타야 하므로 인자는 라벨 하나뿐이다.
# 주입 200 은 천장 평탄부다(2026-09-06 천장 탐색). 포화 직전을 재야 개선이 드러난다.
set -u
LABEL="$1"; RATE="${2:-200}"
S="C:/Users/DEV-PC/AppData/Local/Temp/claude/C--Users-DEV-PC-Desktop-----/8a354c13-3cf3-45b9-9e21-f7f86867fc9a/scratchpad"
OUT="$S/v28_$LABEL"; mkdir -p "$OUT"
cd "C:/dev/exchange"
PSQL() { docker exec chaos-pg psql -U exchange -d exchange -tAc "$1" 2>/dev/null; }

# 깨끗한 DB 에서 시작한다. 미체결 잔여가 천장을 깎는 것이 20·21회차에서 확인됐다.
docker compose -f compose.yaml -f "$S/pgstat.override.yml" --env-file harness/.env.chaos \
  --profile full down -v > /dev/null 2>&1
docker compose -f compose.yaml -f "$S/pgstat.override.yml" --env-file harness/.env.chaos \
  --profile full up -d --wait > /dev/null 2>&1
docker update --cpus=2 chaos-api-1 > /dev/null 2>&1
docker update --cpus=4 chaos-pg     > /dev/null 2>&1
docker stop chaos-bots > /dev/null 2>&1
PSQL "CREATE EXTENSION IF NOT EXISTS pg_stat_statements;" > /dev/null
docker exec chaos-api-1 curl -sS -X POST \
  "http://localhost:8080/harness/v1/loadtest-keys?market=KRW-BTC&count=4" \
  2>/dev/null > harness/load/secrets/k6_keys.json
PSQL "SELECT pg_stat_statements_reset();" > /dev/null

B=$(PSQL "SELECT count(*) FROM orders;")

# 자원과 커넥션 풀을 주행 중 연속 표본으로 잡는다. 단계 종료 후 점표본은 봉우리를 놓친다(24회차).
( i=0; while [ $i -lt 150 ]; do
    docker stats --no-stream --format '{{.Name}} {{.CPUPerc}}' chaos-pg chaos-api-1 2>/dev/null \
      | sed "s/^/$i /" >> "$OUT/cpu.txt"
    curl -s "http://localhost:18080/actuator/metrics/hikaricp.connections.active" 2>/dev/null \
      | python -c "import sys,json;d=json.load(sys.stdin);print('$i active',[m['value'] for m in d['measurements'] if m['statistic']=='VALUE'][0])" 2>/dev/null >> "$OUT/pool.txt"
    curl -s "http://localhost:18080/actuator/metrics/hikaricp.connections.idle" 2>/dev/null \
      | python -c "import sys,json;d=json.load(sys.stdin);print('$i idle',[m['value'] for m in d['measurements'] if m['statistic']=='VALUE'][0])" 2>/dev/null >> "$OUT/pool.txt"
    curl -s "http://localhost:18080/actuator/metrics/hikaricp.connections.pending" 2>/dev/null \
      | python -c "import sys,json;d=json.load(sys.stdin);print('$i pending',[m['value'] for m in d['measurements'] if m['statistic']=='VALUE'][0])" 2>/dev/null >> "$OUT/pool.txt"
    i=$((i+5)); sleep 5; done ) &
SP=$!

docker run --rm --network chaos-net \
  -v "C:/dev/exchange/harness/load:/load" -w /load \
  -e BASE_URL="http://chaos-api-1:8080" -e RUN_ID="v28${LABEL:0:4}$(date +%H%M%S)" -e PROFILE=smoke \
  -e STEADY_RATE="$RATE" -e SPIKE_RATE="$((RATE*2))" -e READ_RATE="$((RATE*6))" \
  -e WS_VUS=1 -e MARKET=KRW-BTC \
  grafana/k6:1.4.0 run --quiet --no-color \
  --env K6_KEYS=./secrets/k6_keys.json order_flow.js > "$OUT/k6.txt" 2>&1
wait $SP 2>/dev/null

A=$(PSQL "SELECT count(*) FROM orders;")
{
  echo "accepted=$((A-B))"
  echo "tps=$(python -c "print(round($((A-B))/120.0, 1))")"
  echo "p95=$(grep -oP 'accepted_req_duration.*p\(95\)=\K[0-9.]+m?s' "$OUT/k6.txt" | head -1)"
  echo "checks_failed=$(grep -oP 'checks_failed[.\s]*:\s*\K[0-9.]+' "$OUT/k6.txt" | head -1)%"
  echo "http_req_failed=$(grep -oP 'http_req_failed[.\s]*:\s*\K[0-9.]+' "$OUT/k6.txt" | head -1)%"
  echo "db_total_ms=$(PSQL "SELECT round(sum(total_exec_time)::numeric,0) FROM pg_stat_statements;")"
  echo "market_calls=$(PSQL "SELECT coalesce(sum(calls),0) FROM pg_stat_statements WHERE query ILIKE '%base_asset_id%' AND query ILIKE '%FROM markets%';")"
  echo "orderbook_calls=$(PSQL "SELECT coalesce(sum(calls),0) FROM pg_stat_statements WHERE query ILIKE '%remaining_qty%' AND query ILIKE '%market_id%';")"
  echo "orderbook_ms=$(PSQL "SELECT coalesce(round(sum(total_exec_time)::numeric,0),0) FROM pg_stat_statements WHERE query ILIKE '%remaining_qty%' AND query ILIKE '%market_id%';")"
  echo "poscost_calls=$(PSQL "SELECT coalesce(sum(calls),0) FROM pg_stat_statements WHERE query ILIKE '%apply_position_cost%';")"
  echo "poscost_ms=$(PSQL "SELECT coalesce(round(sum(total_exec_time)::numeric,0),0) FROM pg_stat_statements WHERE query ILIKE '%apply_position_cost%';")"
} > "$OUT/summary.txt"

PSQL "SELECT calls, round(total_exec_time::numeric,0) AS ms, round(mean_exec_time::numeric,3) AS mean, left(query,100)
      FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 12;" > "$OUT/top.txt"

# 불변식. 개선이 정합성을 깨지 않았는지 같은 주행에서 판정한다.
{
  echo "balance_mismatch=$(PSQL "SELECT count(*) FROM balance_ledger_check WHERE mismatch;")"
  echo "asset_sum_nonzero=$(PSQL "SELECT count(*) FROM (SELECT asset_id FROM ledger_entries GROUP BY asset_id HAVING SUM(amount) <> 0) t;")"
  echo "unsealed=$(PSQL "SELECT count(*) FROM ledger_transactions WHERE status <> 'SEALED';")"
  echo "poscost_qty_mismatch=$(PSQL "SELECT count(*) FROM position_cost_qty_check WHERE cost_qty + unknown_qty IS DISTINCT FROM held_qty;")"
  echo "ledger_entries=$(PSQL "SELECT count(*) FROM ledger_entries;")"
} > "$OUT/invariants.txt"

cat "$OUT/summary.txt"
echo "V28MEASURE $LABEL done"
