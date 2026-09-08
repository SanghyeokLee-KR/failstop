#!/bin/bash
# PHASE 5. 커밋이 멈춘 상태에서 리더를 죽인다. kill 이 두 단계라 공용 드라이버로는 표현되지 않는다.
#
#   1) 부하 시작
#   2) +45초  동기 standby 두 대 kill  -> PostgreSQL 커밋 stall
#   3) +80초  현재 리더 앱 kill        -> 팔로워가 승격을 시도한다
#   4) 관측   리스는 얻는가. epoch 게시는 되는가. 어디서 막히는가
set -u
REPO=/d/dev/failstop
OUT="$1"
LEADER="$2"
cd "$REPO"
mkdir -p "$OUT"
MARK="$OUT/f6_marks.txt"
: > "$MARK"
mark() { echo "$1 $(date -u +%H:%M:%S)" >> "$MARK"; }

python harness/replication/repl_sampler.py --out "$OUT/f6.jsonl" --duration 200 \
  --standbys ex-pg2,ex-pg3 --pg-every 2 --stat-every 10 --node-timeout 0.5 \
  --app-url http://localhost:8080 --app-url http://localhost:8081 \
  > "$OUT/f6_sampler.log" 2>&1 &
SAMP=$!
sleep 6

mark LOAD_START
MSYS_NO_PATHCONV=1 docker run --rm -d --name ex-k6-f6 --network exnet \
  -v "$REPO/harness/load:/load" -v "$REPO/harness/load/artifacts:/artifacts" \
  -w /load -e BASE_URL=http://ex-lb:80 -e RUN_ID=f6 -e BASE_OPS=100 -e CAL_DURATION=170s \
  -e MARKET=KRW-BTC -e K6_KEYS=./secrets/k6_keys.json -e CAL_OUT=/artifacts/f6.json \
  grafana/k6:1.4.0 run calibrate.js > /dev/null 2>&1

sleep 45
mark T0_STANDBY_KILL
docker kill ex-pg2 ex-pg3 > /dev/null 2>&1

sleep 35
mark T1_LEADER_KILL
docker kill "$LEADER" > /dev/null 2>&1

sleep 110
mark LOAD_END
docker rm -f ex-k6-f6 > /dev/null 2>&1
wait $SAMP
mark DONE
