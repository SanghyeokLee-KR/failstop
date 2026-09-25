#!/usr/bin/env bash
# 스모크 관찰기. 1초마다 두 앱의 상태 API, 쓰기 게이트 응답(무인증 POST: ACTIVE 면 401, SUSPENDED 면 423, UNREACHABLE 면 503),
# LB 조회 응답, Patroni /cluster 의 역할과 동기 Standby, 리더 epoch 를 한 줄로 적는다.
# 사용: smoke_observe.sh <초> <출력 파일>
secs=$1
out=$2
for ((i = 0; i < secs; i++)); do
  t=$(date -u +%H:%M:%S)
  s1=$(curl -s -m 2 http://127.0.0.1:59181/api/v1/status | python -c "import sys,json;d=json.load(sys.stdin);print(d['trading_state']+'/'+str(d['reason']))" 2>/dev/null || echo ERR)
  s2=$(curl -s -m 2 http://127.0.0.1:59182/api/v1/status | python -c "import sys,json;d=json.load(sys.stdin);print(d['trading_state']+'/'+str(d['reason']))" 2>/dev/null || echo ERR)
  w=$(curl -s -m 3 -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -d '{}' http://127.0.0.1:59180/api/v1/orders)
  r=$(curl -s -m 3 -o /dev/null -w '%{http_code}' http://127.0.0.1:59180/api/v1/markets)
  c=$(curl -s -m 2 http://127.0.0.1:59008/cluster 2>/dev/null || curl -s -m 2 http://127.0.0.1:59009/cluster 2>/dev/null)
  roles=$(echo "$c" | python -c "import sys,json;d=json.load(sys.stdin);print(','.join(m['name'][-1]+':'+m['role']+':'+m['state']+':tl'+str(m.get('timeline','-')) for m in d['members']))" 2>/dev/null || echo ERR)
  ep=$(curl -s -m 2 http://127.0.0.1:59181/harness/v1/status | python -c "import sys,json;d=json.load(sys.stdin);print(str(d['epoch'])+':'+d['holder'][:8])" 2>/dev/null || echo ERR)
  echo "$t api1=$s1 api2=$s2 write=$w read=$r cluster=$roles lease=$ep" >> "$out"
  sleep 1
done
