#!/usr/bin/env bash
# S3 설계 3절 P7 실행기(Git Bash). 폐기용 postgres:17-alpine 컨테이너 하나를 --rm 과 127.0.0.1 무작위 포트로 띄우고,
# 앱이 쓰는 Flyway 11.7.2 jar(Gradle 캐시)로 번호 순서 동작을 본다. 저장소 마이그레이션은 쓰지 않는다.
# 사용: bash run-flyway.sh > flyway.log 2>&1
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
IMAGE=postgres:17-alpine
NAME="pml-probe-s3flyway-${RANDOM}${RANDOM}"
WORK="$(mktemp -d)"
to_host() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
cleanup() { docker stop "$NAME" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT

C="$HOME/.gradle/caches/modules-2/files-2.1"
jar() { find "$C/$1" -name "$2" | head -1; }
CP=""
for j in "org.flywaydb/flyway-core flyway-core-11.7.2.jar" \
         "org.flywaydb/flyway-database-postgresql flyway-database-postgresql-11.7.2.jar" \
         "org.postgresql/postgresql postgresql-42.7.11.jar" \
         "com.fasterxml.jackson.core/jackson-databind jackson-databind-2.21.4.jar" \
         "com.fasterxml.jackson.core/jackson-core jackson-core-2.21.4.jar" \
         "com.fasterxml.jackson.core/jackson-annotations jackson-annotations-2.21.jar" \
         "com.fasterxml.jackson.dataformat/jackson-dataformat-toml jackson-dataformat-toml-2.21.4.jar"; do
    set -- $j
    p="$(jar "$1" "$2")"
    test -n "$p" || { echo "jar 없음: $2"; exit 1; }
    CP="${CP:+$CP;}$(to_host "$p")"
done

docker run -d --rm --name "$NAME" -e POSTGRES_PASSWORD=probe -e POSTGRES_DB=probe \
    -p 127.0.0.1::5432 "$IMAGE" >/dev/null
until docker logs "$NAME" 2>&1 | grep -q 'PostgreSQL init process complete'; do sleep 1; done
until docker exec "$NAME" pg_isready -U postgres -d probe >/dev/null 2>&1; do sleep 1; done
sleep 1
PORT="$(docker port "$NAME" 5432/tcp | head -1 | sed 's/.*://')"
echo "container=$NAME image=$IMAGE port=127.0.0.1:$PORT flyway=11.7.2"
java -Duser.language=en -Duser.country=US -cp "$CP" "$(to_host "$HERE/FlywayOrder.java")" "jdbc:postgresql://127.0.0.1:$PORT/probe" postgres probe \
    "$(to_host "$WORK/mig")" 2>&1 | grep -v '^SLF4J\|^[A-Z][a-z][a-z] [0-9]' || true
