#!/bin/bash
# 2 vCPU 용량 계단. 게이트 OFF. WS 없음(calibrate.js 는 주문+조회만 낸다).
SP="C:/Users/kopo26/AppData/Local/Temp/claude/D--dev-----/838ae1ec-3fb0-44d0-acb4-dc81e68f64db/scratchpad"
OUT="$SP/run3_stair.log"
: > "$OUT"
say(){ echo "$(date +%H:%M:%S) $*" | tee -a "$OUT"; }
probe(){ curl -s -o /dev/null -m 5 -w "%{http_code}" http://localhost:8090/actuator/health/readiness 2>/dev/null || echo 000; }
metric(){ curl -s -m 5 http://localhost:8090/actuator/prometheus 2>/dev/null | grep -E "^$1" | head -1 | awk '{print $NF}'; }
for OPS in 4 8 12 16 20 26 32; do
  RC=$(probe)
  if [ "$RC" != "200" ]; then say "STOP: readiness=$RC before step $OPS"; break; fi
  say "--- step BASE_OPS=$OPS reads=$(python -c "print(round($OPS*1.667))") ---"
  MSYS_NO_PATHCONV=1 docker run --rm --name k6-cal-$OPS \
    -v "D:/dev/failstop/harness/load:/load" -v "D:/dev/failstop/harness/load/artifacts:/artifacts" -w /load \
    -e BASE_URL=http://host.docker.internal:8090 -e RUN_ID=cal${OPS} -e BASE_OPS=$OPS -e CAL_DURATION=90s \
    -e MARKET=KRW-BTC -e K6_KEYS=./secrets/k6_keys.json -e CAL_OUT=/artifacts/cal_${OPS}.json \
    grafana/k6:1.4.0 run calibrate.js > "$SP/k6_cal_${OPS}.log" 2>&1
  say "step $OPS k6 exit=$? readiness=$(probe) pending=$(metric 'hikaricp_connections_pending{pool="HikariPool-1"}') active=$(metric 'hikaricp_connections_active{pool="HikariPool-1"}') timeout_total=$(metric 'hikaricp_connections_timeout_total{pool="HikariPool-1"}') queue=$(metric 'exchange_sequencer_queue_size')"
  ACK=$(grep -c 'msg="ACK' "$SP/k6_cal_${OPS}.log" 2>/dev/null || echo 0)
  FAIL=$(grep -c 'Request Failed' "$SP/k6_cal_${OPS}.log" 2>/dev/null || echo 0)
  say "step $OPS ACK=$ACK requestFailed=$FAIL"
  sleep 25
  say "step $OPS after 25s idle: readiness=$(probe) pending=$(metric 'hikaricp_connections_pending{pool=\"HikariPool-1\"}')"
done
say "=== staircase done ==="
