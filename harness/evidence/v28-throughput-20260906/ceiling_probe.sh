#!/usr/bin/env bash
# 천장이 무엇인지 가른다. v28measure.sh 와 같은 조건에서 두 축만 따로 움직인다.
#   $1 라벨  $2 앱 vCPU  $3 요청 풀(쓰기 in-flight = 풀 x 0.40)
# TPS 가 앱 CPU 에 반응하면 CPU 가 천장이고, 풀에 반응하면 수용 상한이 천장이다.
set -u
LABEL="$1"; APPCPU="${2:-2}"; POOL="${3:-32}"; RATE="${4:-200}"
S="C:/Users/DEV-PC/AppData/Local/Temp/claude/C--Users-DEV-PC-Desktop-----/8a354c13-3cf3-45b9-9e21-f7f86867fc9a/scratchpad"
OUT="$S/cap_$LABEL"; mkdir -p "$OUT"
cd "C:/dev/exchange"
export PROBE_POOL="$POOL"
CF=(-f compose.yaml -f "$S/pool.override.yml")
PSQL() { docker exec chaos-pg psql -U exchange -d exchange -tAc "$1" 2>/dev/null; }

docker compose "${CF[@]}" --env-file harness/.env.chaos --profile full down -v > /dev/null 2>&1
docker compose "${CF[@]}" --env-file harness/.env.chaos --profile full up -d --wait > /dev/null 2>&1
docker update --cpus="$APPCPU" chaos-api-1 > /dev/null 2>&1
docker update --cpus=4 chaos-pg > /dev/null 2>&1
docker stop chaos-bots > /dev/null 2>&1
PSQL "CREATE EXTENSION IF NOT EXISTS pg_stat_statements;" > /dev/null
docker exec chaos-api-1 curl -sS -X POST \
  "http://localhost:8080/harness/v1/loadtest-keys?market=KRW-BTC&count=4" \
  2>/dev/null > harness/load/secrets/k6_keys.json
# 실제로 적용된 상한을 기록한다. 의도가 아니라 적용값으로 판정한다.
docker logs chaos-api-1 2>&1 | grep -i "수용\|admission" | head -3 >> "$OUT/applied.txt"
curl -s "http://localhost:18080/actuator/metrics/hikaricp.connections.max" 2>/dev/null >> "$OUT/applied.txt"
PSQL "SELECT pg_stat_statements_reset();" > /dev/null
B=$(PSQL "SELECT count(*) FROM orders;")

( i=0; while [ $i -lt 150 ]; do
    docker stats --no-stream --format '{{.Name}} {{.CPUPerc}}' chaos-pg chaos-api-1 2>/dev/null \
      | sed "s/^/$i /" >> "$OUT/cpu.txt"
    for m in hikaricp.connections.active hikaricp.connections.idle hikaricp.connections.pending exchange.sequencer.queue.size; do
      curl -s --max-time 3 "http://localhost:18080/actuator/metrics/$m" 2>/dev/null \
        | python -c "import sys,json;d=json.load(sys.stdin);print('$i','$m'.split('.')[-1],[x['value'] for x in d['measurements'] if x['statistic']=='VALUE'][0])" 2>/dev/null >> "$OUT/pool.txt"
    done
    i=$((i+5)); sleep 5; done ) &
SP=$!

MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*" docker run --rm --network chaos-net \
  -v "C:/dev/exchange/harness/load:/load" -w /load \
  -e BASE_URL="http://chaos-api-1:8080" -e RUN_ID="cap${LABEL:0:4}$(date +%H%M%S)" -e PROFILE=smoke \
  -e STEADY_RATE="$RATE" -e SPIKE_RATE="$((RATE*2))" -e READ_RATE="$((RATE*6))" \
  -e WS_VUS=1 -e MARKET=KRW-BTC \
  grafana/k6:1.4.0 run --quiet --no-color \
  --env K6_KEYS=./secrets/k6_keys.json order_flow.js > "$OUT/k6.txt" 2>&1
wait $SP 2>/dev/null

A=$(PSQL "SELECT count(*) FROM orders;")
{
  echo "label=$LABEL appcpu=$APPCPU pool=$POOL rate=$RATE"
  echo "accepted=$((A-B))"
  echo "tps=$(python -c "print(round($((A-B))/120.0, 1))")"
  echo "p95=$(grep -oP 'accepted_req_duration.*p\(95\)=\K[0-9.]+m?s' "$OUT/k6.txt" | head -1)"
  echo "accepted_total=$(grep -oP 'accepted_total[.\s]*:\s*\K[0-9]+' "$OUT/k6.txt" | head -1)"
  echo "rate_limited=$(grep -oP 'rate_limited_total[.\s]*:\s*\K[0-9]+' "$OUT/k6.txt" | head -1)"
  echo "http_req_failed=$(grep -oP 'http_req_failed[.\s]*:\s*\K[0-9.]+' "$OUT/k6.txt" | head -1)%"
  echo "checks_failed=$(grep -oP 'checks_failed[.\s]*:\s*\K[0-9.]+' "$OUT/k6.txt" | head -1)%"
  echo "db_total_ms=$(PSQL "SELECT round(sum(total_exec_time)::numeric,0) FROM pg_stat_statements;")"
  echo "cpu_app_run=$(awk '$2=="chaos-api-1" && $1+0>=20 && $1+0<=80 {gsub(/%/,"",$3); s+=$3; n++} END{if(n)printf "%.1f", s/n}' "$OUT/cpu.txt")"
  echo "cpu_pg_run=$(awk '$2=="chaos-pg" && $1+0>=20 && $1+0<=80 {gsub(/%/,"",$3); s+=$3; n++} END{if(n)printf "%.1f", s/n}' "$OUT/cpu.txt")"
  echo "queue_max=$(awk '$2=="size"{if($3>m)m=$3} END{print m+0}' "$OUT/pool.txt")"
  echo "queue_avg=$(awk '$2=="size" && $1+0>=20 && $1+0<=80 {s+=$3;n++} END{if(n)printf "%.1f", s/n}' "$OUT/pool.txt")"
  echo "pool_active_max=$(awk '$2=="active"{if($3>m)m=$3} END{print m+0}' "$OUT/pool.txt")"
  echo "pool_pending_max=$(awk '$2=="pending"{if($3>m)m=$3} END{print m+0}' "$OUT/pool.txt")"
} > "$OUT/summary.txt"
{
  echo "balance_mismatch=$(PSQL "SELECT count(*) FROM balance_ledger_check WHERE mismatch;")"
  echo "asset_sum_nonzero=$(PSQL "SELECT count(*) FROM (SELECT asset_id FROM ledger_entries GROUP BY asset_id HAVING SUM(amount) <> 0) t;")"
  echo "unsealed=$(PSQL "SELECT count(*) FROM ledger_transactions WHERE status <> 'SEALED';")"
  echo "poscost_qty_mismatch=$(PSQL "SELECT count(*) FROM position_cost_qty_check WHERE cost_qty + unknown_qty IS DISTINCT FROM held_qty;")"
  echo "ledger_entries=$(PSQL "SELECT count(*) FROM ledger_entries;")"
} > "$OUT/invariants.txt"
cat "$OUT/summary.txt"; echo "CAPPROBE $LABEL done"
