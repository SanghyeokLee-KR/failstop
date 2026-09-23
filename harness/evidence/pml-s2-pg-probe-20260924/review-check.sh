#!/usr/bin/env bash
# S2 설계 검토(2026-09-24)의 지적을 재현하고 수정한 계약과 대조한다(Git Bash). S2 설계 10절의 근거다.
# DB 설계 §3.4 의 V48·V49 계약 SQL 을 문서에서 그대로 뽑아 저장소의 실제 V0~V47 위에 올린다. 수정 전 상태는
# 계약이 회수한 권한을 스크립트가 잠시 되돌려 재현하고 표시한다. 폐기용 postgres:17-alpine 컨테이너 하나를
# --rm 과 127.0.0.1 무작위 포트로 띄우고 지운다. 운영·카오스 DB 와 영속 볼륨은 건드리지 않는다.
# 사용: bash review-check.sh > review-check.log 2>&1
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
DOC="$ROOT/docs/reference/contracts/백엔드_DB_설계.md"
MIG="$ROOT/server/app/src/main/resources/db/migration"
IMAGE=postgres:17-alpine
NAME="pml-probe-s2review-${RANDOM}${RANDOM}"
WORK="$(mktemp -d)"

cleanup() {
    docker stop "$NAME" >/dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT

# §3.4 의 첫 sql 블록이 V48, 둘째가 V49 다(contract-check.sh 와 같은 추출).
awk -v out="$WORK" '
/^### 3\.4 `V48__market_partition_lease\.sql`/ { in34 = 1 }
/^## 4\. / { in34 = 0 }
in34 && /^```sql$/ { blk++; inb = 1; next }
in34 && inb && /^```$/ { inb = 0; next }
in34 && inb { print > (out "/v4" (7 + blk) ".sql") }
' "$DOC"
test -s "$WORK/v48.sql" && test -s "$WORK/v49.sql"

to_host() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

docker run -d --rm --name "$NAME" -e POSTGRES_PASSWORD=probe -e POSTGRES_DB=contract \
    -p 127.0.0.1::5432 "$IMAGE" >/dev/null
until docker logs "$NAME" 2>&1 | grep -q 'PostgreSQL init process complete'; do sleep 1; done
until docker exec "$NAME" pg_isready -U postgres -d contract >/dev/null 2>&1; do sleep 1; done
sleep 1
echo "container=$NAME image=$IMAGE port=$(docker port "$NAME" 5432/tcp)"
docker exec "$NAME" psql -X -U postgres -d contract -At -c 'SELECT version()'

MSYS_NO_PATHCONV=1 docker exec "$NAME" mkdir -p /review
MSYS_NO_PATHCONV=1 docker cp "$(to_host "$MIG")" "$NAME:/review/migration"
for f in v48.sql v49.sql; do
    MSYS_NO_PATHCONV=1 docker cp "$(to_host "$WORK/$f")" "$NAME:/review/$f"
done
for f in contract-seed.sql review-apply.sh review-legacy.sql; do
    MSYS_NO_PATHCONV=1 docker cp "$(to_host "$HERE/$f")" "$NAME:/review/$f"
done
MSYS_NO_PATHCONV=1 docker exec "$NAME" sh /review/review-apply.sh
