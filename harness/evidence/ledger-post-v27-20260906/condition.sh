#!/usr/bin/env bash
# V27 한 조건분 전량. 전후가 같은 절차를 타도록 하나로 묶는다.
# 앱 2 vCPU, DB 4 vCPU 로 묶는다. 증거 22·24회차와 같은 배치다.
set -u
LABEL="$1"
S="C:/Users/DEV-PC/AppData/Local/Temp/claude/C--Users-DEV-PC-Desktop-----/8a354c13-3cf3-45b9-9e21-f7f86867fc9a/scratchpad"
cd "C:/dev/exchange"

docker compose -f compose.yaml -f "$S/pgstat.override.yml" --env-file harness/.env.chaos \
  --profile full down -v > /dev/null 2>&1
docker compose -f compose.yaml -f "$S/pgstat.override.yml" --env-file harness/.env.chaos \
  --profile full up -d --wait > /dev/null 2>&1
echo "stack up exit=$?"

docker update --cpus=2 chaos-api-1 > /dev/null 2>&1
docker update --cpus=4 chaos-pg     > /dev/null 2>&1
docker stop chaos-bots > /dev/null 2>&1
docker exec chaos-pg psql -U exchange -d exchange -c \
  "CREATE EXTENSION IF NOT EXISTS pg_stat_statements;" > /dev/null 2>&1
docker exec chaos-api-1 curl -sS -X POST \
  "http://localhost:8080/harness/v1/loadtest-keys?market=KRW-BTC&count=4" \
  2>/dev/null > harness/load/secrets/k6_keys.json
echo "prepared cpus=app2/db4 bots=off keys=$(python -c "import json;print(len(json.load(open('harness/load/secrets/k6_keys.json',encoding='utf-8'))))" 2>/dev/null)"

bash "$S/measure.sh" "$LABEL" 30 90
bash "$S/ladder.sh"  "$LABEL"
echo "CONDITION $LABEL complete"
