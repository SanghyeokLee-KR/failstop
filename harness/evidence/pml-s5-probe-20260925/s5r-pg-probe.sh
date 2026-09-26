#!/usr/bin/env bash
# ADR 0034 S5 설계 검토 대응(설계 11절)의 PostgreSQL 확인 실행기(Git Bash, 설계 3-3 P6). 저장소의 실제 V0~V53 을 폐기용
# postgres:17-alpine 컨테이너에 파일마다 한 트랜잭션으로 올리고, DB 설계 §3.9 의 고친 V54 계약 SQL 을 문서에서 그대로 뽑아
# 올린 뒤 §5.4-d 의 점유·끝내기(리스 확인), 포기(시작한 요청 제외), 만료 문장을 확인한다. 컨테이너는 --rm, 127.0.0.1 무작위
# 포트, 이름 pml-probe-s5r-* 이고 끝나면 지운다. 카오스 스택(chaos-*), 실험 스택(exchange-pml), 보존 볼륨, 영속 DB 는 건드리지
# 않는다. 문서에 넣기 전의 초안을 시험하려면 S5R_V54_DRAFT 에 파일 경로를 준다.
# 사용: bash s5r-pg-probe.sh > s5r-pg-probe.log 2>&1
set -euo pipefail
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
DB_DOC="$ROOT/docs/reference/contracts/백엔드_DB_설계.md"
MIG="$ROOT/server/app/src/main/resources/db/migration"
IMAGE=postgres:17-alpine
NAME="pml-probe-s5r-${RANDOM}${RANDOM}"
WORK="$(mktemp -d)"
to_host() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
cleanup() { docker stop "$NAME" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT

if [ -n "${S5R_V54_DRAFT:-}" ]; then
    cp "$S5R_V54_DRAFT" "$WORK/v54.sql"
    echo "v54 source=draft($(basename "$S5R_V54_DRAFT"))"
else
    # §3.9 의 첫 sql 블록이 V54 다.
    awk -v out="$WORK" '
    /^### 3\.9 `V54__/ { in39 = 1; next }
    /^### / || /^## / { in39 = 0 }
    in39 && /^```sql$/ { blk++; inb = 1; next }
    in39 && inb && /^```$/ { inb = 0; next }
    in39 && inb && blk == 1 { print > (out "/v54.sql") }
    ' "$DB_DOC"
    echo "v54 source=documents(DB 설계 §3.9)"
fi
test -s "$WORK/v54.sql"
echo "v54 lines=$(wc -l < "$WORK/v54.sql") sha256=$(sha256sum "$WORK/v54.sql" | cut -c1-16)"
ls "$MIG" | sed -E 's/V([0-9]+)__.*/\1/' | sort -n | tail -1 | sed 's/^/repository max migration=V/'

docker run -d --rm --name "$NAME" -e POSTGRES_PASSWORD=probe -e POSTGRES_DB=probe \
    -p 127.0.0.1::5432 "$IMAGE" >/dev/null
until docker logs "$NAME" 2>&1 | grep -q 'PostgreSQL init process complete'; do sleep 1; done
until docker exec "$NAME" pg_isready -U postgres -d probe >/dev/null 2>&1; do sleep 1; done
sleep 1
echo "container=$NAME image=$IMAGE port=$(docker port "$NAME" 5432/tcp)"
docker exec "$NAME" psql -X -U postgres -d probe -At -c 'SELECT version()'
docker exec "$NAME" mkdir -p /c
docker cp "$(to_host "$MIG")" "$NAME:/c/migration"
docker cp "$(to_host "$WORK/v54.sql")" "$NAME:/c/v54.sql"
docker cp "$(to_host "$HERE/s5r-pg-apply.sh")" "$NAME:/c/s5r-pg-apply.sh"
docker exec "$NAME" sh /c/s5r-pg-apply.sh
