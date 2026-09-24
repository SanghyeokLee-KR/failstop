#!/usr/bin/env bash
# ADR 0034 S4 설계 3절 N 의 실행기(Git Bash). nginx 쓰기 업스트림이 리더의 일시 거부를 노드 장애로 세는지 본다.
# 폐기용 컨테이너 넷(가짜 노드 둘, nginx 1.29, curl)을 새 도커 망에 --rm 으로 띄우고 끝나면 망까지 지운다. 호스트 포트를 열지 않는다.
# 카오스 스택(chaos-*), 실험 스택(exchange-pml), 보존 볼륨은 건드리지 않는다. 새 이미지는 받지 않는다(로컬에 있는 태그만 쓴다).
# 사용: bash nginx-probe.sh > nginx-probe.log 2>&1
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TAG="${RANDOM}${RANDOM}"
NET="pml-probe-s4net-$TAG"
A="pml-probe-s4a-$TAG"
B="pml-probe-s4b-$TAG"
NG="pml-probe-s4ng-$TAG"
CL="pml-probe-s4cl-$TAG"
WORK="$(mktemp -d)"
to_host() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
cleanup() {
    docker rm -f "$A" "$B" "$NG" "$CL" >/dev/null 2>&1 || true
    docker network rm "$NET" >/dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT

for img in python:3.12-alpine nginx:1.29 curlimages/curl:8.11.0; do
    echo "image $img $(docker image inspect "$img" --format '{{.Id}}')"
done
docker network create "$NET" >/dev/null
for node in A B; do
    name="$A"; [ "$node" = B ] && name="$B"
    MSYS_NO_PATHCONV=1 docker create --name "$name" --network "$NET" -e NODE_NAME="$node" \
        python:3.12-alpine python /tmp/backend.py >/dev/null
    MSYS_NO_PATHCONV=1 docker cp "$(to_host "$HERE/nginx-backend.py")" "$name:/tmp/backend.py"
    docker start "$name" >/dev/null
done
sed -e "s/@A@/$A/g" -e "s/@B@/$B/g" "$HERE/nginx-template.conf" > "$WORK/nginx.conf"
MSYS_NO_PATHCONV=1 docker create --name "$NG" --network "$NET" nginx:1.29 >/dev/null
MSYS_NO_PATHCONV=1 docker cp "$(to_host "$WORK/nginx.conf")" "$NG:/etc/nginx/nginx.conf"
docker start "$NG" >/dev/null
sleep 2
docker exec "$NG" nginx -v 2>&1
docker exec "$NG" nginx -t 2>&1 | tail -1
MSYS_NO_PATHCONV=1 docker create --name "$CL" --network "$NET" --entrypoint sh curlimages/curl:8.11.0 \
    /tmp/client.sh "$NG" "$A" "$B" >/dev/null
MSYS_NO_PATHCONV=1 docker cp "$(to_host "$HERE/nginx-client.sh")" "$CL:/tmp/client.sh"
docker start -a "$CL"
echo
echo "== nginx error log (upstream 판정 문구만)"
docker logs "$NG" 2>&1 | grep -E "no live upstreams|upstream server temporarily disabled|while reading response header" \
    | sed -E 's/^[0-9\/: ]+//; s/client: [0-9.]+/client: -/; s/upstream: "http:\/\/[0-9.]+:8080/upstream: "http:\/\/<ip>:8080/' || true
