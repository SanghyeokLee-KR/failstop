#!/bin/bash
# 장애 회차 드라이버. 한 부하 안에서 정상과 장애와 복구를 잇는다.
#
#   OUT_DIR=... APPS=... bash harness/replication/run.sh <회차> <kill대상> [<재기동대상>] [<표본대상>]
#
# 예)
#   bash harness/replication/run.sh p3e "ex-pg2 ex-pg3" "ex-pg2 ex-pg3" ex-pg2,ex-pg3
#   APPS="http://localhost:8080 http://localhost:8081" \
#     DURATION=150 LOAD=120 KILL_AT=40 RESTART_AT=90 \
#     bash harness/replication/run.sh f1 ex-app ex-app ""
#
# 회차를 나누지 않는 이유. 정상과 장애를 따로 돌리면 조건이 흔들린다. 같은 부하 안에서 이어야
# 구간 사이의 차가 kill 하나로 설명된다.
#
# 시간표는 환경변수로 바꾼다. 복제 장애는 stall 이 길어 285/235/85/170 이 맞고, 리더 페일오버는
# TTL 10초 안에 끝나므로 훨씬 짧아야 승격 구간이 평균에 묻히지 않는다.
#
# 이 파일은 harness/evidence/replication-fault-20260908/run.sh 를 공용 자리로 옮기고 다중 노드
# 표본을 더한 것이다. 그 사본은 그 회차가 실제로 돌린 것이므로 손대지 않는다.
set -u
REPO="${REPO:-$(cd "$(dirname "$0")/../.." && pwd)}"
OUT_DIR="${OUT_DIR:-$REPO/harness/replication/out}"
cd "$REPO"
mkdir -p "$OUT_DIR"

RUN="$1"
KILL_TARGETS="$2"
RESTART_TARGETS="${3:-}"
STANDBYS="${4:-}"

DURATION="${DURATION:-285}"     # 표본 총 길이
LOAD="${LOAD:-235}"             # 부하 길이
KILL_AT="${KILL_AT:-85}"        # 부하 시작 이후 kill 까지
RESTART_AT="${RESTART_AT:-170}" # 부하 시작 이후 재기동까지
BASE_OPS="${BASE_OPS:-100}"

# 표본 대상 앱. 리더 페일오버에서는 카운터가 노드 사이를 옮겨가므로 둘 다 찍어야 한다.
APPS="${APPS:-http://localhost:8080}"
APP_ARGS=""
for u in $APPS; do APP_ARGS="$APP_ARGS --app-url $u"; done

SAMPLES="$OUT_DIR/${RUN}.jsonl"
MARK="$OUT_DIR/${RUN}_marks.txt"
rm -f "$SAMPLES" "$MARK"
: > "$MARK"
mark() { echo "$1 $(date -u +%H:%M:%S)" >> "$MARK"; }

STANDBY_ARG=""
[ -n "$STANDBYS" ] && STANDBY_ARG="--standbys $STANDBYS"
# 표본기 추가 인자. 리더 페일오버는 초 단위 해상도가 필요해 복제 조회와 docker stats 를 성기게 한다.
SAMPLER_ARGS="${SAMPLER_ARGS:-}"
# shellcheck disable=SC2086
python harness/replication/repl_sampler.py --out "$SAMPLES" --duration "$DURATION" \
  $STANDBY_ARG $APP_ARGS $SAMPLER_ARGS > "$OUT_DIR/${RUN}_sampler.log" 2>&1 &
SAMP=$!
sleep 6

mark LOAD_START
MSYS_NO_PATHCONV=1 docker run --rm -d --name "ex-k6-$RUN" --network exnet \
  -v "$REPO/harness/load:/load" -v "$REPO/harness/load/artifacts:/artifacts" \
  -w /load -e BASE_URL=http://ex-lb:80 -e RUN_ID="$RUN" -e BASE_OPS="$BASE_OPS" \
  -e CAL_DURATION="${LOAD}s" -e MARKET=KRW-BTC -e K6_KEYS=./secrets/k6_keys.json \
  -e CAL_OUT="/artifacts/${RUN}.json" \
  grafana/k6:1.4.0 run calibrate.js > /dev/null 2>&1

sleep "$KILL_AT"
mark T0_KILL
for c in $KILL_TARGETS; do docker kill "$c" > /dev/null 2>&1; done

sleep $((RESTART_AT - KILL_AT))
if [ -n "$RESTART_TARGETS" ]; then
  mark T_RESTART
  for c in $RESTART_TARGETS; do docker start "$c" > /dev/null 2>&1; done
else
  mark T_NO_RESTART
fi

sleep $((LOAD - RESTART_AT))
mark LOAD_END
docker rm -f "ex-k6-$RUN" > /dev/null 2>&1
wait $SAMP
mark DONE
