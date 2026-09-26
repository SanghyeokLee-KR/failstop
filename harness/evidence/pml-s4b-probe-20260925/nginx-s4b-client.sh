#!/bin/sh
# ADR 0034 S4b 설계 3절 N2 확인의 클라이언트(curlimages/curl 컨테이너 안). nginx-s4b-probe.sh 가 부른다.
# 가짜 노드와 nginx 설정은 S4 확인(../pml-s4-probe-20260924/)의 것을 그대로 쓴다. 사례마다 fail_timeout(2초)이 지나도록 3초 쉰 뒤
# 시작하고, 시각은 사례 시작부터의 초다. 요청은 0.1초 간격이다.
# 사용: sh nginx-s4b-client.sh <nginx 호스트> <가짜 노드 A 호스트> <가짜 노드 B 호스트>
N=$1; A=$2; B=$3
T0=0
up() { cut -d' ' -f1 /proc/uptime; }
start_case() { sleep 3; echo; echo "== $1"; T0=$(up); }
el() { awk -v a="$(up)" -v b="$T0" 'BEGIN{printf "%.2f", a-b}'; }
mode() { curl -s -X POST "http://$1:8080/mode/$2" >/dev/null; }
req() {
    out=$(curl -s -o /dev/null -X POST "http://$N:8080/$1/orders" -H 'Content-Type: application/json' --data '{}' \
        -w 'http=%{http_code} upstream_addr=%header{x-upstream-addr} upstream_status=%header{x-upstream-status}')
    echo "t=$(el) $2 $out"
}
burst() { i=0; while [ $i -lt "$3" ]; do sleep 0.1; req "$1" "$2"; i=$((i + 1)); done; }
count() { curl -s "http://$1:8080/count"; }

echo "A=$A B=$B nginx=$N"

start_case "D1 harness_write: 1차 A 가 DB 불통인 옛 리더(503), 백업 B 가 새 리더(200). 2초 동안"
mode "$A" 503; mode "$B" 200
burst harness "D1" 20
echo "counts A=$(count "$A") B=$(count "$B")"

start_case "D1' harness_write: 같은 배치에서 A 가 종전처럼 500"
mode "$A" 500; mode "$B" 200
burst harness "D1'" 20
echo "counts A=$(count "$A") B=$(count "$B")"

start_case "D2 harness_write: 1차 A 리더가 DB 불통(503), 백업 B 팔로워(503). 3초 뒤 A 복구(200)"
mode "$A" 503; mode "$B" 503
burst harness "D2-outage" 30
mode "$A" 200
echo "t=$(el) D2-A-recovered"
burst harness "D2-after" 40

start_case "D2' harness_write: 같은 배치에서 A 가 종전처럼 500. 3초 뒤 A 복구(200)"
mode "$A" 500; mode "$B" 503
burst harness "D2'-outage" 30
mode "$A" 200
echo "t=$(el) D2'-A-recovered"
burst harness "D2'-after" 20

start_case "D3 edge_write: A 가 DB 불통인 옛 리더(503), B 가 새 리더(200). 2초 동안"
mode "$A" 503; mode "$B" 200
burst edge "D3" 20

start_case "D4 edge_write: A 리더가 DB 불통(503), B 팔로워(503). 3초 뒤 A 복구(200)"
mode "$A" 503; mode "$B" 503
burst edge "D4-outage" 30
mode "$A" 200
echo "t=$(el) D4-A-recovered"
burst edge "D4-after" 40

start_case "D4' edge_write: 같은 배치에서 A 가 종전처럼 500. 3초 뒤 A 복구(200)"
mode "$A" 500; mode "$B" 503
burst edge "D4'-outage" 30
mode "$A" 200
echo "t=$(el) D4'-A-recovered"
burst edge "D4'-after" 20

echo
echo "== counts"
count "$A"; echo
count "$B"; echo
