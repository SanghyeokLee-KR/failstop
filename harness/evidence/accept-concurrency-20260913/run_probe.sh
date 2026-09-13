#!/usr/bin/env bash
# 칸 하나의 주행 묶음. 표본기 둘을 띄우고 본 도구를 돌린 뒤 양성 축과 이미지를 기록한다.
# 사용: bash run_probe.sh roundtrip <rate>   또는   bash run_probe.sh ceiling
set -u
cd /c/dev/exchange-b0
E=harness/evidence/accept-concurrency-20260913
export MSYS_NO_PATHCONV=1 PYTHONIOENCODING=utf-8 CHAOS_APP_IMAGE=exchange-chaos-app:b0-ceecaef
mode=$1
if [ "$mode" = roundtrip ]; then label="B0-$2"; else label="B0-ceiling"; fi
rm -f "$E/stop-$label" "$E/stop-host-$label"
echo "start $(date -Iseconds) label=$label image=$CHAOS_APP_IMAGE"
python "$E/lock_sampler.py" --label "$label" --outdir "$E" --max-secs 3000 &
ls_pid=$!
pwsh -NoProfile -File "$E/host_sampler.ps1" "$label" &
hs_pid=$!
if [ "$mode" = roundtrip ]; then
  echo "CMD: python harness/scale/roundtrip_probe.py --label B0 --rate $2 --out $E/B0-$2.json"
  python harness/scale/roundtrip_probe.py --label B0 --rate "$2" --out "$E/B0-$2.json"
  echo "probe_rc=$?"
  mv -f harness/evidence/k6-rt-B0.log "$E/k6-rt-B0-$2.log"
  rids="rtB0$2"
else
  echo "CMD: python $E/prep_stack.py"
  python "$E/prep_stack.py"; prc=$?
  echo "prep_rc=$prc"
  if [ $prc -eq 0 ]; then
    echo "CMD: python $E/k6_ceiling_logged.py --rungs 120,240,360,480,600 --duration 90 --out $E/k6_ceiling.json"
    python "$E/k6_ceiling_logged.py" --rungs 120,240,360,480,600 --duration 90 --out "$E/k6_ceiling.json"
    echo "ceiling_rc=$?"
  fi
  rids="k6ceil-120,k6ceil-240,k6ceil-360,k6ceil-480,k6ceil-600"
fi
echo "end $(date -Iseconds)"
touch "$E/stop-$label" "$E/stop-host-$label"
python "$E/per_market.py" --run-ids "$rids" --out "$E/per_market_$label.json"
docker exec chaos-pg psql -U exchange -d exchange -t -A -F '|' -c \
  "select (select count(*) from orders), (select count(*) from trades), pg_database_size('exchange')/1048576"
docker inspect -f '{{.Name}} {{.Image}} {{.Config.Image}}' chaos-api-1 chaos-api-2 chaos-bots
for c in chaos-api-1 chaos-api-2; do docker exec $c sha256sum /app/app.jar; done
docker exec chaos-pg psql -U exchange -d exchange -t -A -c "select holder, epoch from leader_lease where resource='matching-engine'"
wait $ls_pid $hs_pid
echo "samplers_done $(date -Iseconds)"
