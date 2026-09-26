#!/usr/bin/env bash
# ADR 0034 S4b 설계 3절 J 의 실행기(Git Bash). 앱 JAR(harness/app.jar)에 든 라이브러리 판으로 DbErrorProbe.java 를 돌린다.
# 폐기용 PostgreSQL(postgres:17-alpine)과 toxiproxy(ghcr.io/shopify/toxiproxy:2.12.0)를 새 도커 망에 --rm 으로 띄우고, 호스트에는
# 127.0.0.1 무작위 포트만 연다. 끝나면 컨테이너와 망을 지운다. 카오스 스택(chaos-*), 실험 스택(exchange-pml), 보존 볼륨은 건드리지 않는다.
# 새 이미지는 받지 않는다(로컬에 있는 태그만 쓴다). 비밀번호는 실행마다 무작위로 만들고 어디에도 적지 않는다.
# 사용: bash spring-probe.sh > spring-probe.log 2>&1
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
TAG="${RANDOM}${RANDOM}"
NET="pml-probe-s4bnet-$TAG"
PG="pml-probe-s4bpg-$TAG"
TX="pml-probe-s4btx-$TAG"
WORK="$(mktemp -d)"
to_win() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
cleanup() {
    docker rm -f "$PG" "$TX" >/dev/null 2>&1 || true
    docker network rm "$NET" >/dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT

for img in postgres:17-alpine ghcr.io/shopify/toxiproxy:2.12.0; do
    echo "image $img $(docker image inspect "$img" --format '{{.Id}}')"
done
echo "app.jar sha256 $(sha256sum "$REPO/harness/app.jar" | cut -c1-16)"
mkdir -p "$WORK/lib"
(cd "$WORK/lib" && unzip -o -j -q "$REPO/harness/app.jar" \
    'BOOT-INF/lib/postgresql-*.jar' 'BOOT-INF/lib/HikariCP-*.jar' 'BOOT-INF/lib/spring-jdbc-*.jar' \
    'BOOT-INF/lib/spring-tx-*.jar' 'BOOT-INF/lib/spring-core-*.jar' 'BOOT-INF/lib/spring-beans-*.jar' \
    'BOOT-INF/lib/spring-jcl-*.jar' 'BOOT-INF/lib/spring-context-*.jar' 'BOOT-INF/lib/slf4j-api-*.jar' \
    'BOOT-INF/lib/micrometer-commons-*.jar')
echo "libs $(ls "$WORK/lib" | tr '\n' ' ')"
export PGPASSWORD="p$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')"
docker network create "$NET" >/dev/null
docker run -d --rm --name "$PG" --network "$NET" -e POSTGRES_PASSWORD="$PGPASSWORD" -p 127.0.0.1::5432 \
    postgres:17-alpine >/dev/null
docker run -d --rm --name "$TX" --network "$NET" -p 127.0.0.1::8474 -p 127.0.0.1::15432 \
    ghcr.io/shopify/toxiproxy:2.12.0 >/dev/null
for i in $(seq 1 60); do
    if docker exec "$PG" pg_isready -U postgres -h 127.0.0.1 >/dev/null 2>&1; then break; fi
    sleep 1
done
sleep 2
PG_PORT="$(docker port "$PG" 5432/tcp | head -1 | sed 's/.*://')"
PX_PORT="$(docker port "$TX" 15432/tcp | head -1 | sed 's/.*://')"
API_PORT="$(docker port "$TX" 8474/tcp | head -1 | sed 's/.*://')"
echo "ports pg=127.0.0.1:(무작위) proxy=127.0.0.1:(무작위) api=127.0.0.1:(무작위)"
java -version 2>&1 | head -1
PG_HOST="$PG" java -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dfile.encoding=UTF-8 -cp "$(to_win "$WORK/lib")\\*" "$(to_win "$HERE/DbErrorProbe.java")" "$PG_PORT" "$PX_PORT" "$API_PORT" 2>&1 \
    | grep -v -E '^(SLF4J|WARNING)' || true
