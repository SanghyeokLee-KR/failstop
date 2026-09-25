#!/bin/sh
# ADR 0034 S5 설계 3절 N5 확인의 클라이언트(curlimages/curl 컨테이너 안). nginx-s5-probe.sh 가 사례 이름으로 부른다.
# 사용: sh nginx-s5-client.sh <사례> <초안 nginx 호스트> <비교 nginx 호스트> <가짜 노드 A> <가짜 노드 B>
# 줄마다 사례 시작 뒤 초, 이름표, HTTP 코드, 응답 바디(노드·판정·바디 길이·받은 힌트)를 적는다. 재시도 여부는 실행기가 뒤에
# 붙이는 LB 접근 로그의 ust(시도한 업스트림 상태 목록)로 본다. 요청마다 ?r=<번호> 를 붙여 로그 줄과 맞춘다.
CASE=$1; N=$2; C=$3; A=$4; B=$5
T0=0; R=0
up() { cut -d' ' -f1 /proc/uptime; }
start_case() { echo; echo "== $1"; T0=$(up); }
el() { awk -v a="$(up)" -v b="$T0" 'BEGIN{printf "%.2f", a-b}'; }
mode() { curl -s -X POST "http://$1:8080/mode?owns=$2" >/dev/null; }
# req <호스트> <메서드> <경로> <이름표> <바디 마켓 또는 -> [헤더...]
req() {
    host=$1; method=$2; path=$3; label=$4; bodymk=$5; shift 5
    R=$((R + 1))
    sep='?'; case "$path" in *\?*) sep='&';; esac
    url="http://$host:8080$path${sep}r=$R"
    if [ "$bodymk" = "-" ]; then
        out=$(curl -s -m 15 -X "$method" "$url" "$@" -w ' http=%{http_code} ust=%header{x-upstream-status}')
    else
        body=$(printf '{"client_order_id":"p%s","market":"%s","pad":"%0250d"}' "$R" "$bodymk" 0)
        out=$(curl -s -m 15 -X "$method" "$url" -H 'Content-Type: application/json' --data "$body" "$@" -w ' http=%{http_code} ust=%header{x-upstream-status}')
    fi
    echo "t=$(el) r=$R $label $out"
}
loop() { # loop <횟수> <간격> <req 인자...>
    n=$1; gap=$2; shift 2; i=0
    while [ $i -lt "$n" ]; do req "$@"; sleep "$gap"; i=$((i + 1)); done
}
HB='X-Exchange-Market: KRW-BTC'; HE='X-Exchange-Market: KRW-ETH'
OID=00000000-0000-4000-8000-000000000001

case "$CASE" in
routing)
    start_case "R1 P3 흉내: A 가 p0, B 가 p1 소유. 힌트 있는 쓰기는 그 파티션 1차로 곧바로 간다"
    mode "$A" p0; mode "$B" p1
    req "$N" POST /api/v1/orders "R1-btc-hint" KRW-BTC -H "$HB"
    req "$N" POST /api/v1/orders "R1-xrp-hint" KRW-XRP -H 'X-Exchange-Market: KRW-XRP'
    req "$N" POST /api/v1/orders "R1-eth-hint" KRW-ETH -H "$HE"
    req "$N" POST /api/v1/orders "R1-sol-hint" KRW-SOL -H 'X-Exchange-Market: KRW-SOL'
    req "$N" POST /api/v1/orders/batch "R1-batch-eth-hint" KRW-ETH -H "$HE"
    req "$N" DELETE /api/v1/orders/$OID "R1-cancel-eth-hint" - -H "$HE"
    req "$N" DELETE "/api/v1/orders?market=KRW-BTC" "R1-cancelall-btc-hint" - -H "$HB"
    req "$N" GET /api/v1/orders/open "R1-get-open-eth-hint(기본 경로)" - -H "$HE"
    req "$N" GET /api/v1/trades "R1-get-trades(조회 경로)" -
    sleep 3
    start_case "R2 P3 흉내: 힌트 없는 쓰기는 기본 쓰기 경로(exchange_write, A 1차). A 가 소유하지 않는 마켓이면 503 뒤 B"
    req "$N" POST /api/v1/orders "R2-eth-nohint" KRW-ETH
    req "$N" POST /api/v1/orders "R2-btc-nohint-right-after" KRW-BTC
    req "$N" POST /api/v1/orders "R2-btc-hint-isolated" KRW-BTC -H "$HB"
    req "$N" POST /api/v1/orders "R2-eth-hint-isolated" KRW-ETH -H "$HE"
    sleep 2.5
    req "$N" POST /api/v1/orders "R2-btc-nohint-after-fail-timeout" KRW-BTC
    sleep 3
    start_case "R3 힌트 모양: 소문자, 앞뒤 공백, 같은 이름 헤더 둘"
    req "$N" POST /api/v1/orders "R3-lowercase" KRW-ETH -H 'X-Exchange-Market: krw-eth'
    req "$N" POST /api/v1/orders "R3-spaces" KRW-ETH -H 'X-Exchange-Market:   KRW-ETH  '
    req "$N" POST /api/v1/orders "R3-duplicate" KRW-ETH -H "$HE" -H "$HB"
    ;;
p2)
    start_case "R4 P2 흉내: A 가 모든 파티션 소유. p1 힌트 쓰기는 1차 B(비소유) 503 뒤 백업 A. 0.25초 간격 24회"
    mode "$A" p0,p1; mode "$B" ""
    loop 24 0.25 "$N" POST /api/v1/orders "R4-eth-hint" KRW-ETH -H "$HE"
    req "$N" POST /api/v1/orders "R4-btc-hint" KRW-BTC -H "$HB"
    ;;
transient)
    start_case "R5 P3 흉내, 초안(exchange_p0 1차 max_fails=2): 소유자 A 가 p0 요청 한 건에만 503"
    mode "$A" p0; mode "$B" p1
    req "$N" POST /api/v1/orders "R5-btc-forced-503" KRW-BTC -H "$HB" -H "X-Want-$A: 503"
    loop 12 0.2 "$N" POST /api/v1/orders "R5-btc-follow" KRW-BTC -H "$HB"
    req "$N" POST /api/v1/orders "R5-eth-during" KRW-ETH -H "$HE"
    sleep 3
    start_case "R6 같은 배치에서 소유자 A 가 잇달아 두 건 503"
    req "$N" POST /api/v1/orders "R6-btc-forced-503-1" KRW-BTC -H "$HB" -H "X-Want-$A: 503"
    req "$N" POST /api/v1/orders "R6-btc-forced-503-2" KRW-BTC -H "$HB" -H "X-Want-$A: 503"
    loop 18 0.2 "$N" POST /api/v1/orders "R6-btc-follow" KRW-BTC -H "$HB"
    req "$N" POST /api/v1/orders "R6-eth-during" KRW-ETH -H "$HE"
    ;;
compare)
    for v in mf1 mf2 mf3 inv rr; do
        sleep 3
        start_case "C-$v 한 건 503: P3 흉내(A 가 p0 소유), 첫 요청 뒤 A 가 한 건에만 503, 이어 0.15초 간격 14회"
        mode "$A" p0; mode "$B" p1
        req "$C" POST /$v/order "C-$v-warm" KRW-BTC -H "$HB"
        req "$C" POST /$v/order "C-$v-forced-503" KRW-BTC -H "$HB" -H "X-Want-$A: 503"
        loop 14 0.15 "$C" POST /$v/order "C-$v-follow" KRW-BTC -H "$HB"
        sleep 3
        start_case "C-$v 두 건 503"
        req "$C" POST /$v/order "C-$v-warm2" KRW-BTC -H "$HB"
        req "$C" POST /$v/order "C-$v-forced-503-1" KRW-BTC -H "$HB" -H "X-Want-$A: 503"
        req "$C" POST /$v/order "C-$v-forced-503-2" KRW-BTC -H "$HB" -H "X-Want-$A: 503"
        loop 18 0.15 "$C" POST /$v/order "C-$v-follow2" KRW-BTC -H "$HB"
    done
    sleep 3
    start_case "C-mf2-spread 503 한 건, 1.2초 동안 성공 여섯, 다시 503 한 건(실패 계수가 성공에 지워지는가)"
    mode "$A" p0; mode "$B" p1
    req "$C" POST /mf2/order "S-forced-503-a" KRW-BTC -H "$HB" -H "X-Want-$A: 503"
    loop 6 0.2 "$C" POST /mf2/order "S-ok" KRW-BTC -H "$HB"
    req "$C" POST /mf2/order "S-forced-503-b" KRW-BTC -H "$HB" -H "X-Want-$A: 503"
    loop 12 0.2 "$C" POST /mf2/order "S-follow" KRW-BTC -H "$HB"
    ;;
accel)
    start_case "X1 대안: 비소유 A 가 421 + X-Accel-Redirect(@accel_other). 메서드·바디 길이 보존과 A 퇴출 여부"
    mode "$A" p0; mode "$B" p1
    loop 3 0.2 "$C" POST /accel/order "X1-eth-accel" KRW-ETH -H "$HE" -H 'X-Probe-Accel: @accel_other'
    req "$C" DELETE /accel/order/$OID "X1-eth-accel-delete" - -H "$HE" -H 'X-Probe-Accel: @accel_other'
    req "$C" POST /accel/order "X1-btc-owner" KRW-BTC -H "$HB"
    ;;
fo-pre)
    start_case "F0 장애 흉내 준비: P3 흉내(A p0, B p1)"
    mode "$A" p0; mode "$B" p1
    req "$N" POST /api/v1/orders "F0-btc" KRW-BTC -H "$HB"
    req "$N" POST /api/v1/orders "F0-eth" KRW-ETH -H "$HE"
    ;;
fo-loop)
    start_case "F1 A 정지 직후부터 0.5초 간격으로 BTC·ETH 번갈아 40회(실행기가 약 6초에 B 를 p0·p1 소유로 바꾼다)"
    i=0
    while [ $i -lt 20 ]; do
        req "$N" POST /api/v1/orders "F1-btc" KRW-BTC -H "$HB"
        req "$N" POST /api/v1/orders "F1-eth" KRW-ETH -H "$HE"
        sleep 0.5; i=$((i + 1))
    done
    ;;
fo-back)
    start_case "F2 A 가 소유 없이 돌아온 뒤(팔로워 흉내) p0 요청 0.3초 간격 16회. 1차 A 비소유 503 과 백업 B 로의 재시도"
    loop 16 0.3 "$N" POST /api/v1/orders "F2-btc" KRW-BTC -H "$HB"
    ;;
counts)
    echo; echo "== counts"
    curl -s "http://$A:8080/count"; echo
    curl -s "http://$B:8080/count"; echo
    ;;
*) echo "unknown case $CASE"; exit 2;;
esac
