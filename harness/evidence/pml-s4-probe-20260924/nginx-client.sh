#!/bin/sh
# ADR 0034 S4 설계 3절 N 확인의 클라이언트(curlimages/curl 컨테이너 안). nginx-probe.sh 가 부른다.
# 사용: sh nginx-client.sh <nginx 호스트> <가짜 노드 A 호스트> <가짜 노드 B 호스트>
# 각 사례는 nginx 의 fail_timeout(2초)이 지나도록 3초 쉰 뒤 시작한다. 시각은 사례 시작부터의 초다.
N=$1; A=$2; B=$3
T0=0
up() { cut -d' ' -f1 /proc/uptime; }
start_case() { sleep 3; echo; echo "== $1"; T0=$(up); }
el() { awk -v a="$(up)" -v b="$T0" 'BEGIN{printf "%.2f", a-b}'; }
mode() { curl -s -X POST "http://$1:8080/mode/$2" >/dev/null; }
req() {
    if [ -n "$3" ]; then
        out=$(curl -s -X POST "http://$N:8080/$1/orders" -H 'Content-Type: application/json' --data '{}' -H "$3" \
            -w ' http=%{http_code} upstream_addr=%header{x-upstream-addr} upstream_status=%header{x-upstream-status}')
    else
        out=$(curl -s -X POST "http://$N:8080/$1/orders" -H 'Content-Type: application/json' --data '{}' \
            -w ' http=%{http_code} upstream_addr=%header{x-upstream-addr} upstream_status=%header{x-upstream-status}')
    fi
    echo "t=$(el) $2 $out"
}
burst() { i=0; while [ $i -lt "$3" ]; do sleep 0.2; req "$1" "$2"; i=$((i + 1)); done; }

echo "A=$A B=$B nginx=$N"

start_case "C1 harness_write: 1차 A 가 리더(200), 백업 B 가 팔로워(503). A 가 한 요청에만 503"
mode "$A" 200; mode "$B" 503
req harness "C1-transient-503" "X-Want-A: 503"
burst harness "C1-follow" 6
sleep 2.2
req harness "C1-after-fail-timeout"

start_case "C2 harness_write: 같은 배치. A 가 한 요청에만 429"
mode "$A" 200; mode "$B" 503
req harness "C2-transient-429" "X-Want-A: 429"
burst harness "C2-follow" 6

start_case "C3 harness_write: 1차 A 가 팔로워(503), 백업 B 가 리더(200)"
mode "$A" 503; mode "$B" 200
req harness "C3-first"
burst harness "C3-follow" 4
sleep 2.2
req harness "C3-after-fail-timeout"

start_case "C4 edge_write: A 리더(200), B 팔로워(503), 라운드로빈. 평시 네 번 뒤 A 가 한 요청에만 503"
mode "$A" 200; mode "$B" 503
burst edge "C4-steady" 4
req edge "C4-transient-503" "X-Want-A: 503"
burst edge "C4-follow" 6
sleep 2.2
burst edge "C4-after-fail-timeout" 2

start_case "C5 edge_write: 같은 배치. A 가 한 요청에만 429"
mode "$A" 200; mode "$B" 503
burst edge "C5-steady" 2
req edge "C5-transient-429" "X-Want-A: 429"
burst edge "C5-follow" 6

echo
echo "== counts"
curl -s "http://$A:8080/count"; echo
curl -s "http://$B:8080/count"; echo
