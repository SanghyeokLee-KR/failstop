#!/usr/bin/env bash
# ADR 0034 S4b 설계 3절 N2 의 실행기(Git Bash). DB 연결 장애를 503 으로 낼 때 쓰기 업스트림이 옛 리더·리더를 어떻게 빼고 되돌리는지 본다.
# 가짜 노드(nginx-backend.py)와 nginx 설정 틀(nginx-template.conf)은 S4 확인 폴더의 것을 그대로 쓴다(하네스 exchange_write 와 엣지
# exchange_write 와 같은 줄). 폐기용 컨테이너(가짜 노드 둘, nginx 1.29, curl)를 새 도커 망에 --rm 으로 띄우고 끝나면 망까지 지운다.
# 호스트 포트를 열지 않는다. 카오스 스택(chaos-*), 실험 스택(exchange-pml), 보존 볼륨은 건드리지 않는다. 새 이미지는 받지 않는다.
# 사용: bash nginx-s4b-probe.sh > nginx-s4b-probe.log 2>&1
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
S4="$HERE/../pml-s4-probe-20260924"
TAG="${RANDOM}${RANDOM}"
NET="pml-probe-s4bn-$TAG"
A="pml-probe-s4bna-$TAG"
B="pml-probe-s4bnb-$TAG"
NG="pml-probe-s4bng-$TAG"
CL="pml-probe-s4bcl-$TAG"
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
echo "backend sha256 $(sha256sum "$S4/nginx-backend.py" | cut -c1-16) template sha256 $(sha256sum "$S4/nginx-template.conf" | cut -c1-16)"
docker network create "$NET" >/dev/null
for node in A B; do
    name="$A"; [ "$node" = B ] && name="$B"
    MSYS_NO_PATHCONV=1 docker create --name "$name" --network "$NET" -e NODE_NAME="$node" \
        python:3.12-alpine python /tmp/backend.py >/dev/null
    MSYS_NO_PATHCONV=1 docker cp "$(to_host "$S4/nginx-backend.py")" "$name:/tmp/backend.py"
    docker start "$name" >/dev/null
done
sed -e "s/@A@/$A/g" -e "s/@B@/$B/g" "$S4/nginx-template.conf" > "$WORK/nginx.conf"
MSYS_NO_PATHCONV=1 docker create --name "$NG" --network "$NET" nginx:1.29 >/dev/null
MSYS_NO_PATHCONV=1 docker cp "$(to_host "$WORK/nginx.conf")" "$NG:/etc/nginx/nginx.conf"
docker start "$NG" >/dev/null
sleep 2
docker exec "$NG" nginx -v 2>&1
docker exec "$NG" nginx -t 2>&1 | tail -1
MSYS_NO_PATHCONV=1 docker create --name "$CL" --network "$NET" --entrypoint sh curlimages/curl:8.11.0 \
    /tmp/client.sh "$NG" "$A" "$B" >/dev/null
MSYS_NO_PATHCONV=1 docker cp "$(to_host "$HERE/nginx-s4b-client.sh")" "$CL:/tmp/client.sh"
docker start -a "$CL"
echo
echo "== nginx error log (upstream 판정 문구만, 줄 수)"
docker logs "$NG" 2>&1 | grep -E "no live upstreams|upstream server temporarily disabled" \
    | sed -E 's/^.*\[(error|warn)\] [0-9#*]+: \*[0-9]+ //; s/, client: .*//' | sort | uniq -c || true
