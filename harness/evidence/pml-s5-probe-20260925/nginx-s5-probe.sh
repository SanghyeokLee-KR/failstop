#!/usr/bin/env bash
# ADR 0034 S5 설계 3절 N5 의 실행기(Git Bash). 파티션 LB 설정 초안과 비교 설정을 폐기용 컨테이너(가짜 노드 둘, nginx 1.29 둘,
# curl 하나)로 확인하고 끝나면 망까지 지운다. 컨테이너 이름은 pml-probe-s5*-<난수>이고 호스트 포트를 열지 않는다.
# 카오스 스택(chaos-*), 실험 스택(exchange-pml), 보존 볼륨은 건드리지 않는다. 새 이미지는 받지 않는다(로컬 태그만 쓴다).
# 초안의 chaos-api-1·2·3 은 가짜 노드 A·B 와 없는 이름 C 로 바꿔 띄우고, 바꾸지 않은 초안 원문으로 nginx -t 도 따로 돌린다.
# 사용: bash nginx-s5-probe.sh > nginx-s5-probe.log 2>&1
set -euo pipefail
# Git Bash 가 /tmp 같은 컨테이너 안 경로를 Windows 경로로 바꾸지 않게 한다. 호스트 경로는 to_host 로 따로 바꾼다.
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "$0")" && pwd)"
TAG="${RANDOM}${RANDOM}"
NET="pml-probe-s5net-$TAG"
A="pml-probe-s5a-$TAG"
B="pml-probe-s5b-$TAG"
C="pml-probe-s5c-$TAG"          # 만들지 않는다. 초안의 chaos-api-3(down) 자리
NG1="pml-probe-s5ng1-$TAG"      # 초안
NG2="pml-probe-s5ng2-$TAG"      # 비교 설정
NGT="pml-probe-s5ngt-$TAG"      # 초안 원문 nginx -t
CL="pml-probe-s5cl-$TAG"
WORK="$(mktemp -d)"
to_host() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
cleanup() {
    docker rm -f "$A" "$B" "$NG1" "$NG2" "$NGT" "$CL" >/dev/null 2>&1 || true
    docker network rm "$NET" >/dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT
now() { date -u +%H:%M:%S.%N | cut -c1-12; }
client() { docker exec "$CL" sh /tmp/client.sh "$1" "$NG1" "$NG2" "$A" "$B"; }

echo "start $(date -u +%Y-%m-%dT%H:%M:%SZ) tag=$TAG"
for img in python:3.12-alpine nginx:1.29 curlimages/curl:8.11.0; do
    echo "image $img $(docker image inspect "$img" --format '{{.Id}}')"
done
echo "draft sha256=$(sha256sum "$HERE/nginx-partition-draft.conf" | cut -c1-16) compare sha256=$(sha256sum "$HERE/nginx-s5-compare.conf" | cut -c1-16)"
docker network create "$NET" >/dev/null

start_node() { # start_node <이름> <표시 이름> <초기 소유>
    MSYS_NO_PATHCONV=1 docker create --name "$1" --network "$NET" -e NODE_NAME="$1" -e OWNS="$3" \
        python:3.12-alpine python /tmp/backend.py >/dev/null
    MSYS_NO_PATHCONV=1 docker cp "$(to_host "$HERE/nginx-s5-backend.py")" "$1:/tmp/backend.py"
    docker start "$1" >/dev/null
}
start_node "$A" A p0
start_node "$B" B p1

echo
echo "== N5-0 초안 원문(이름 바꾸지 않음) nginx -t"
MSYS_NO_PATHCONV=1 docker create --name "$NGT" --network "$NET" nginx:1.29 nginx -t >/dev/null
MSYS_NO_PATHCONV=1 docker cp "$(to_host "$HERE/nginx-partition-draft.conf")" "$NGT:/etc/nginx/nginx.conf"
docker start -a "$NGT" 2>&1 | sed -E 's/^[0-9\/: ]+//'
docker rm -f "$NGT" >/dev/null

sed -e "s/chaos-api-1/$A/g" -e "s/chaos-api-2/$B/g" -e "s/chaos-api-3/$C/g" "$HERE/nginx-partition-draft.conf" > "$WORK/ng1.conf"
sed -e "s/@A@/$A/g" -e "s/@B@/$B/g" "$HERE/nginx-s5-compare.conf" > "$WORK/ng2.conf"
for pair in "$NG1:ng1.conf" "$NG2:ng2.conf"; do
    name="${pair%%:*}"; conf="${pair##*:}"
    MSYS_NO_PATHCONV=1 docker create --name "$name" --network "$NET" nginx:1.29 >/dev/null
    MSYS_NO_PATHCONV=1 docker cp "$(to_host "$WORK/$conf")" "$name:/etc/nginx/nginx.conf"
    docker start "$name" >/dev/null
done
sleep 2
docker exec "$NG1" nginx -v 2>&1
echo "ng1 (초안, 이름 치환) $(docker exec "$NG1" nginx -t 2>&1 | tail -1)"
echo "ng2 (비교) $(docker exec "$NG2" nginx -t 2>&1 | tail -1)"
MSYS_NO_PATHCONV=1 docker create --name "$CL" --network "$NET" --entrypoint sh curlimages/curl:8.11.0 -c 'sleep 100000' >/dev/null
MSYS_NO_PATHCONV=1 docker cp "$(to_host "$HERE/nginx-s5-client.sh")" "$CL:/tmp/client.sh"
docker start "$CL" >/dev/null
echo "A=$A B=$B ng1=$NG1 ng2=$NG2"

client routing
client p2
client transient
client compare
client accel

echo
echo "== F 장애 흉내(E4 모양): A 정지 -> 약 6초 뒤 B 가 두 파티션 소유 -> A 가 소유 없이 복귀"
client fo-pre
echo "$(now) docker stop A"
docker stop -t 0 "$A" >/dev/null
docker exec -d "$CL" sh -c "sh /tmp/client.sh fo-loop $NG1 $NG2 $A $B > /tmp/fo-loop.out 2>&1"
sleep 6
echo "$(now) B -> owns p0,p1"
docker exec "$CL" curl -s -X POST "http://$B:8080/mode?owns=p0,p1"; echo
for i in $(seq 1 60); do
    if docker exec "$CL" sh -c 'grep -c "F1-" /tmp/fo-loop.out 2>/dev/null' | grep -qx 40; then break; fi
    sleep 1
done
docker exec "$CL" cat /tmp/fo-loop.out
echo "$(now) docker start A (소유 없음, OWNS 환경 변수를 비워 다시 띄운다)"
docker rm -f "$A" >/dev/null
start_node "$A" A ""
sleep 12
client fo-back
client counts

echo
echo "== ng1 접근 로그(초안). ust 는 시도한 업스트림 상태 목록이다"
docker logs "$NG1" 2>/dev/null | sed -E 's/^[0-9.]+ - - \[[^]]*\] //' | sed -E "s/$A/A/g; s/$B/B/g" \
    | sed -E 's/[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+:8080/<ip>:8080/g'
echo
echo "== ng2 접근 로그(비교)"
docker logs "$NG2" 2>/dev/null | sed -E 's/[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+:8080/<ip>:8080/g'
echo
echo "== 오류 로그(업스트림 판정 문구만)"
for name in "$NG1" "$NG2"; do
    echo "-- $([ "$name" = "$NG1" ] && echo ng1 || echo ng2)"
    docker logs "$name" 2>&1 >/dev/null | grep -E "no live upstreams|temporarily disabled|could not be resolved|upstream timed out|connect\(\) failed|No route" \
        | sed -E 's/^[0-9\/: ]+//; s/client: [0-9.]+/client: -/; s/[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+:8080/<ip>:8080/g' \
        | sed -E "s/$A/A/g; s/$B/B/g; s/$C/C/g" | sed -E 's/, server: .*//' || true
done
echo "end $(date -u +%Y-%m-%dT%H:%M:%SZ)"
