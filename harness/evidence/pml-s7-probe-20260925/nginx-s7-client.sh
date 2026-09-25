#!/bin/sh
# ADR 0034 S7 설계 3절 N7 의 요청 쪽(curl 컨테이너 안). nginx-s7-probe.sh 가 부른다. 인자: <사례> <nginx 이름> <A> <B>
CASE="$1"; NG="$2"; A="$3"; B="$4"
LB="http://$NG:8080"
t() { date -u +%H:%M:%S.%N | cut -c1-12; }
req() { # req <표시> <메서드> <경로> [힌트] [바디 마켓]
  label="$1"; method="$2"; path="$3"; hint="$4"; bm="${5:-KRW-ETH}"
  if [ -n "$hint" ]; then
    out=$(curl -s -o /tmp/b -w '%{http_code}' -X "$method" -H "X-Exchange-Market: $hint" -H 'Content-Type: application/json' \
          --data '{}' --max-time 5 "$LB$path")
  elif [ "$method" = GET ]; then
    out=$(curl -s -o /tmp/b -w '%{http_code}' --max-time 5 "$LB$path")
  else
    out=$(curl -s -o /tmp/b -w '%{http_code}' -X "$method" -H 'Content-Type: application/json' --data "{\"market\":\"$bm\"}" \
          --max-time 5 "$LB$path")
  fi
  node=$(sed -n 's/.*"node": "\([^"]*\)".*/\1/p' /tmp/b | sed "s/$A/A/;s/$B/B/")
  echo "$(t) $label $method $path hint=${hint:--} body=${hint:+-}${hint:-$bm} -> $out ${node:+node=$node}"
}
ws() { # ws <표시>
  code=$(curl -s -o /dev/null -w '%{http_code}' --http1.1 -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
         -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' --max-time 2 "$LB/ws/v1")
  echo "$(t) $1 GET /ws/v1 upgrade -> $code"
}
mode() { curl -s -X POST "http://$1:8080/mode?$2" >/dev/null; echo "$(t) mode $(echo "$1" | sed "s/$A/A/;s/$B/B/") $2"; }
case "$CASE" in
  steady)
    echo "== N7-1 P3 평상시(A 가 p0, B 가 p1): 힌트 쓰기는 그 파티션 1차로, 호가·티커는 exchange_any, 조회는 exchange_read, WS 는 exchange_ws"
    req 'p0 생성' POST /api/v1/orders KRW-BTC
    req 'p1 생성' POST /api/v1/orders KRW-ETH
    req 'p1 배치' POST /api/v1/orders/batch KRW-ETH
    req 'p0 단건 취소' DELETE /api/v1/orders/abc KRW-XRP
    req 'p1 일괄 취소' DELETE '/api/v1/orders?market=KRW-SOL' KRW-SOL
    req '주문 조회(힌트)' GET '/api/v1/orders?market=KRW-ETH' KRW-ETH
    for i in 1 2 3 4; do req "호가 $i" GET '/api/v1/orderbook?market=KRW-ETH'; done
    for i in 1 2; do req "티커 $i" GET '/api/v1/ticker?markets=KRW-BTC'; done
    for i in 1 2; do req "마켓 $i" GET /api/v1/markets; done
    for i in 1 2 3 4; do ws "WS $i"; done
    ;;
  wsrefuse)
    echo "== N7-2 A 가 WS 를 거절(업그레이드 전 503)하는 동안 WS 는 B 로 넘어가고, 같은 순간 p0 쓰기와 호가는 A 를 계속 쓴다"
    mode "$A" 'ws=refuse'
    for i in 1 2 3 4; do ws "WS $i"; done
    req 'p0 생성(직후)' POST /api/v1/orders KRW-BTC
    req 'p0 생성(직후)' POST /api/v1/orders KRW-DOGE
    for i in 1 2 3 4; do req "호가 $i" GET '/api/v1/orderbook?market=KRW-BTC'; done
    mode "$A" 'ws=accept'
    ;;
  failover)
    echo "== N7-3 B 가 p1 을 잃고 A 가 두 파티션을 쥔 모양(E4 뒤): p1 힌트 쓰기는 B 의 503 뒤 A 로 넘어가고 두 번째 실패 뒤 B 를 건너뛴다"
    mode "$B" 'owns='
    mode "$A" 'owns=p0,p1'
    for i in 1 2 3 4; do req "p1 생성 $i" POST /api/v1/orders KRW-ETH; done
    req 'p0 생성' POST /api/v1/orders KRW-BTC
    sleep 2.5
    req 'p1 생성(fail_timeout 뒤)' POST /api/v1/orders KRW-ETH
    mode "$B" 'owns=p1'
    mode "$A" 'owns=p0'
    ;;
  unhinted)
    echo "== N7-4 힌트 없는 쓰기(P3): 종전 쓰기 경로 1차 A 가 소유하지 않는 p1 주문을 503 으로 돌려보낸 뒤 fail_timeout 동안 A 가 빠진다"
    sleep 2.5
    req '힌트 없는 ETH 생성' POST /api/v1/orders '' KRW-ETH
    req '힌트 없는 BTC 생성' POST /api/v1/orders '' KRW-BTC
    req '힌트 있는 BTC 생성' POST /api/v1/orders KRW-BTC
    sleep 2.5
    req '힌트 없는 BTC 생성(fail_timeout 뒤)' POST /api/v1/orders '' KRW-BTC
    ;;
esac
