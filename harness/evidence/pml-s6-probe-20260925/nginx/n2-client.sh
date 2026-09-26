#!/bin/sh
# 폐기용 확인 N2 클라이언트. /ws/ 업그레이드 요청 둘(한 번은 least_conn 이 팔로워를 고르게 한다) 바로 뒤 2초 안에 조회 10건,
# 3초 쉰 뒤(fail_timeout 2초가 지난 뒤) 조회 10건을 보낸다. 줄마다 상태와 답한 노드를 적는다.
lb=http://pml6-probe-lb:8080
ws() { curl -s -o /dev/null -w "ws  %{http_code} %header{x-backend}\n" -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' "$lb/ws/v1"; }
api() { curl -s -o /dev/null -w "api %{http_code} %header{x-backend}\n" "$lb/api/v1/ticker?markets=KRW-BTC"; }
echo "== WS 핸드셰이크 뒤 곧바로 조회 $(date -u +%H:%M:%SZ)"
ws; ws
for i in 1 2 3 4 5 6 7 8 9 10; do api; done
sleep 3
echo "== 3초 뒤 조회"
for i in 1 2 3 4 5 6 7 8 9 10; do api; done
