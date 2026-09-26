#!/usr/bin/env bash
# ADR 0034 S4 설계 3절 G 의 실행기(Git Bash). 저장소의 실제 V0~V52 를 폐기용 postgres:17-alpine 컨테이너에 파일마다 한 트랜잭션으로
# 올리고, DB 설계 §3.8 의 V53 계약 SQL 을 문서에서 그대로 뽑아 올린 뒤 G1~G5 를 확인한다. 컨테이너는 --rm, 127.0.0.1 무작위 포트,
# 이름 pml-probe-s4pg-* 이고 끝나면 지운다. 카오스 스택(chaos-*), 실험 스택(exchange-pml), 보존 볼륨, 영속 DB 는 건드리지 않는다.
# 설계 초안 단계에서 문서에 아직 없는 V53 을 시험하려면 S4_V53_DRAFT 에 파일 경로를 준다.
# 사용: bash s4-pg-probe.sh > s4-pg-probe.log 2>&1
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
DB_DOC="$ROOT/docs/reference/contracts/백엔드_DB_설계.md"
MIG="$ROOT/server/app/src/main/resources/db/migration"
IMAGE=postgres:17-alpine
NAME="pml-probe-s4pg-${RANDOM}${RANDOM}"
WORK="$(mktemp -d)"
to_host() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
cleanup() { docker stop "$NAME" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT

if [ -n "${S4_V53_DRAFT:-}" ]; then
    cp "$S4_V53_DRAFT" "$WORK/v53.sql"
    echo "v53 source=draft($(basename "$S4_V53_DRAFT"))"
else
    # §3.8 의 첫 sql 블록이 V53 이다.
    awk -v out="$WORK" '
    /^### 3\.8 `V53__/ { in38 = 1; next }
    /^### / || /^## / { in38 = 0 }
    in38 && /^```sql$/ { blk++; inb = 1; next }
    in38 && inb && /^```$/ { inb = 0; next }
    in38 && inb && blk == 1 { print > (out "/v53.sql") }
    ' "$DB_DOC"
    echo "v53 source=documents(DB 설계 §3.8)"
fi
test -s "$WORK/v53.sql"
echo "v53 lines=$(wc -l < "$WORK/v53.sql") sha256=$(sha256sum "$WORK/v53.sql" | cut -c1-16)"
ls "$MIG" | sed -E 's/V([0-9]+)__.*/\1/' | sort -n | tail -1 | sed 's/^/repository max migration=V/'

docker run -d --rm --name "$NAME" -e POSTGRES_PASSWORD=probe -e POSTGRES_DB=probe \
    -p 127.0.0.1::5432 "$IMAGE" >/dev/null
until docker logs "$NAME" 2>&1 | grep -q 'PostgreSQL init process complete'; do sleep 1; done
until docker exec "$NAME" pg_isready -U postgres -d probe >/dev/null 2>&1; do sleep 1; done
sleep 1
echo "container=$NAME image=$IMAGE port=$(docker port "$NAME" 5432/tcp)"
docker exec "$NAME" psql -X -U postgres -d probe -At -c 'SELECT version()'
MSYS_NO_PATHCONV=1 docker exec "$NAME" mkdir -p /c
MSYS_NO_PATHCONV=1 docker cp "$(to_host "$MIG")" "$NAME:/c/migration"
MSYS_NO_PATHCONV=1 docker cp "$(to_host "$WORK/v53.sql")" "$NAME:/c/v53.sql"
MSYS_NO_PATHCONV=1 docker cp "$(to_host "$HERE/s4-pg-apply.sh")" "$NAME:/c/s4-pg-apply.sh"
MSYS_NO_PATHCONV=1 docker exec "$NAME" sh /c/s4-pg-apply.sh
