#!/usr/bin/env bash
# ADR 0034 S7 설계 3절 N7 의 실행기(Git Bash). 두 LB 파일을 합친 초안(nginx-p3-relay-draft.conf)을 폐기용 컨테이너(가짜 노드 둘,
# nginx 1.29 하나, curl 하나)로 확인하고 끝나면 망까지 지운다. 컨테이너 이름은 pml-probe-s7*-<난수>이고 호스트 포트를 열지 않는다.
# 카오스 스택(chaos-*), 실험 스택(exchange-pml), 보존 볼륨은 건드리지 않는다. 새 이미지는 받지 않는다(로컬 태그만 쓴다).
# 초안의 chaos-api-1·2·3 은 가짜 노드 A·B 와 없는 이름 C 로 바꿔 띄우고, 바꾸지 않은 초안 원문으로 nginx -t 를 따로 돌린다.
# 사용: bash nginx-s7-probe.sh > nginx-s7-probe.log 2>&1
set -euo pipefail
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "$0")" && pwd)"
TAG="${RANDOM}${RANDOM}"
NET="pml-probe-s7net-$TAG"
A="pml-probe-s7a-$TAG"
B="pml-probe-s7b-$TAG"
C="pml-probe-s7c-$TAG"          # 만들지 않는다. 초안의 chaos-api-3(down) 자리
NG="pml-probe-s7ng-$TAG"
NGT="pml-probe-s7ngt-$TAG"
CL="pml-probe-s7cl-$TAG"
WORK="$(mktemp -d)"
to_host() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
cleanup() {
    docker rm -f "$A" "$B" "$NG" "$NGT" "$CL" >/dev/null 2>&1 || true
    docker network rm "$NET" >/dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT INT TERM
client() { docker exec "$CL" sh /tmp/client.sh "$1" "$NG" "$A" "$B"; }

echo "start $(date -u +%Y-%m-%dT%H:%M:%SZ) tag=$TAG"
for img in python:3.12-alpine nginx:1.29 curlimages/curl:8.11.0; do
    echo "image $img $(docker image inspect "$img" --format '{{.Id}}')"
done
echo "draft sha256=$(sha256sum "$HERE/nginx-p3-relay-draft.conf" | cut -c1-16)"
docker network create "$NET" >/dev/null

start_node() { # start_node <이름> <초기 소유>
    docker create --name "$1" --network "$NET" -e NODE_NAME="$1" -e OWNS="$2" python:3.12-alpine python /tmp/backend.py >/dev/null
    docker cp "$(to_host "$HERE/nginx-s7-backend.py")" "$1:/tmp/backend.py"
    docker start "$1" >/dev/null
}
start_node "$A" p0
start_node "$B" p1

echo
echo "== N7-0 초안 원문(이름 바꾸지 않음) nginx -t"
docker create --name "$NGT" --network "$NET" nginx:1.29 nginx -t >/dev/null
docker cp "$(to_host "$HERE/nginx-p3-relay-draft.conf")" "$NGT:/etc/nginx/nginx.conf"
docker start -a "$NGT" 2>&1 | sed -E 's/^[0-9\/: ]+//'
docker rm -f "$NGT" >/dev/null

sed -e "s/chaos-api-1/$A/g" -e "s/chaos-api-2/$B/g" -e "s/chaos-api-3/$C/g" "$HERE/nginx-p3-relay-draft.conf" > "$WORK/ng.conf"
docker create --name "$NG" --network "$NET" nginx:1.29 >/dev/null
docker cp "$(to_host "$WORK/ng.conf")" "$NG:/etc/nginx/nginx.conf"
docker start "$NG" >/dev/null
sleep 2
docker exec "$NG" nginx -v 2>&1
echo "ng (초안, 이름 치환) $(docker exec "$NG" nginx -t 2>&1 | tail -1)"
docker create --name "$CL" --network "$NET" --entrypoint sh curlimages/curl:8.11.0 -c 'sleep 100000' >/dev/null
docker cp "$(to_host "$HERE/nginx-s7-client.sh")" "$CL:/tmp/client.sh"
docker start "$CL" >/dev/null

for c in steady wsrefuse failover unhinted; do
    echo
    client "$c"
    sleep 3
done

echo
echo "== 접근 로그(파티션 형식: ups=시도한 업스트림, ust=상태 목록). 노드 주소는 A·B 로 바꿔 적는다"
AIP=$(docker inspect -f "{{(index .NetworkSettings.Networks \"$NET\").IPAddress}}" "$A")
BIP=$(docker inspect -f "{{(index .NetworkSettings.Networks \"$NET\").IPAddress}}" "$B")
# 공식 nginx 이미지는 access.log 를 /dev/stdout 에 잇는다. 그래서 접근 줄은 docker logs 의 표준 출력에서 읽는다.
docker logs "$NG" 2>/dev/null | grep ' ups=' \
  | sed -E "s/$AIP:8080/A/g; s/$BIP:8080/B/g; s/^[0-9.]+ - - \[[^]]*\] //; s/\"-\" \"curl\/[^\"]*\" //" || true
echo
echo "== 오류 로그(업스트림 빼기)"
docker logs "$NG" 2>&1 | grep -E 'upstream server temporarily disabled|no live upstreams' | sed -E "s/$AIP:8080/A/g; s/$BIP:8080/B/g; s/^[0-9\/: ]+//; s/, client: .*request: /, request: /" || true
echo
echo "== 가짜 노드가 받은 요청"
docker exec "$CL" sh -c "curl -s http://$A:8080/count; echo; curl -s http://$B:8080/count; echo" | sed "s/$A/A/;s/$B/B/"
echo "end $(date -u +%Y-%m-%dT%H:%M:%SZ)"
