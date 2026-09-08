#!/bin/bash
# 복제 장애 실험 드라이버. 회차 이름을 인자로 받아 산출물을 분리한다.
#
#   OUT_DIR=... bash run.sh <회차이름> <kill대상들> [<재기동대상들>] [<표본대상들>]
#
# 예)
#   bash run.sh p1  ex-pg2            ex-pg2  ex-pg2          단일 standby kill 뒤 복구
#   bash run.sh p2b ex-pg2            ex-pg2  ex-pg2,ex-pg3   ANY 1 에서 standby_1 만
#   bash run.sh p2e "ex-pg2 ex-pg3"   ""      ex-pg2,ex-pg3   둘 다 죽이고 복구 없음
#
# 한 부하 안에서 정상·장애·복구를 잇는다. 회차를 나누면 조건이 흔들린다. 같은 부하 발생기와
# 같은 인자를 모든 회차가 공유하므로 회차 사이의 차이는 kill 대상뿐이다.
#
# 시간표: 표본 285초. 부하 235초. kill 은 부하 시작 +85초. 재기동은 +170초.
# +85 를 고른 이유는 램프업이 끝나고 정상 구간 표본이 60개 이상 쌓이기 때문이다.
set -u
REPO="${REPO:-$(cd "$(dirname "$0")/../../.." && pwd)}"
OUT_DIR="${OUT_DIR:-$REPO/harness/evidence/replication-fault-20260908/samples}"
cd "$REPO"
mkdir -p "$OUT_DIR"

RUN="$1"
KILL_TARGETS="$2"
RESTART_TARGETS="${3:-}"
STANDBYS="${4:-ex-pg2}"

SAMPLES="$OUT_DIR/${RUN}.jsonl"
MARK="$OUT_DIR/${RUN}_marks.txt"
rm -f "$SAMPLES" "$MARK"
: > "$MARK"
mark() { echo "$1 $(date -u +%H:%M:%S)" >> "$MARK"; }

python harness/replication/repl_sampler.py --out "$SAMPLES" --duration 285 \
  --standbys "$STANDBYS" > "$OUT_DIR/${RUN}_sampler.log" 2>&1 &
SAMP=$!
sleep 6

mark LOAD_START
MSYS_NO_PATHCONV=1 docker run --rm -d --name "ex-k6-$RUN" --network exnet \
  -v "$REPO/harness/load:/load" -v "$REPO/harness/load/artifacts:/artifacts" \
  -w /load -e BASE_URL=http://ex-lb:80 -e RUN_ID="$RUN" -e BASE_OPS=100 -e CAL_DURATION=235s \
  -e MARKET=KRW-BTC -e K6_KEYS=./secrets/k6_keys.json -e CAL_OUT="/artifacts/${RUN}.json" \
  grafana/k6:1.4.0 run calibrate.js > /dev/null 2>&1

sleep 85
mark T0_KILL
for c in $KILL_TARGETS; do docker kill "$c" > /dev/null 2>&1; done

sleep 85
if [ -n "$RESTART_TARGETS" ]; then
  mark T_RESTART
  for c in $RESTART_TARGETS; do docker start "$c" > /dev/null 2>&1; done
else
  mark T_NO_RESTART
fi

sleep 80
mark LOAD_END
docker rm -f "ex-k6-$RUN" > /dev/null 2>&1
wait $SAMP
mark DONE
