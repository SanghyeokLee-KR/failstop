#!/usr/bin/env bash
# ADR 0034 S7 설계 3절 P7-7 의 실행기(Git Bash). C22'·C30 문장 비용 표본이다. 판정이 아니다. 폐기용 postgres:17-alpine 컨테이너
# 하나(--rm, CPU 1, 127.0.0.1 무작위 포트, 이름 pml-probe-s7cost-*)에 V0~V54 와 V55 초안을 올리고, 합성 명령 행 N 개로
# cost-queries/ 의 문장마다 세 번 잰다. 같은 시각에 다른 작업의 폐기용 스택이 돌고 있으면 그 표본은 잡음을 탄다.
# 사용: bash s7-cost-probe.sh 200000 > s7-cost-probe-200k.log 2>&1
set -euo pipefail
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
MIG="$ROOT/server/app/src/main/resources/db/migration"
N="${1:-200000}"
IMAGE=postgres:17-alpine
NAME="pml-probe-s7cost-${RANDOM}${RANDOM}"
to_host() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
cleanup() { docker stop "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT
docker run -d --rm --name "$NAME" --cpus 1 --shm-size 256m -e POSTGRES_PASSWORD=probe -e POSTGRES_DB=probe \
    -p 127.0.0.1::5432 "$IMAGE" -c jit=off >/dev/null
until docker logs "$NAME" 2>&1 | grep -q 'PostgreSQL init process complete'; do sleep 1; done
until docker exec "$NAME" pg_isready -U postgres -d probe >/dev/null 2>&1; do sleep 1; done
sleep 1
echo "start $(date -u +%Y-%m-%dT%H:%M:%SZ) container=$NAME image=$IMAGE cpus=1 N=$N"
docker exec "$NAME" psql -X -U postgres -d probe -At -c 'SELECT version()'
docker exec "$NAME" mkdir -p /c
docker cp "$(to_host "$MIG")" "$NAME:/c/migration"
docker cp "$(to_host "$HERE/v55-draft.sql")" "$NAME:/c/v55.sql"
for q in "$HERE"/cost-queries/${S7_COST_ONLY:-q_}*.sql; do docker cp "$(to_host "$q")" "$NAME:/c/$(basename "$q")"; done
docker cp "$(to_host "$HERE/s7-cost-apply.sh")" "$NAME:/c/s7-cost-apply.sh"
docker exec "$NAME" sh /c/s7-cost-apply.sh "$N"
echo "done $(date -u +%Y-%m-%dT%H:%M:%SZ)"
