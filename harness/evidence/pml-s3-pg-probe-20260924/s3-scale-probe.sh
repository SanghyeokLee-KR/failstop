#!/usr/bin/env bash
# ADR 0034 S3 설계 11절 검토 대응 R5 의 예비 측정(Git Bash). 문서에서 뽑은 V49·V50 계약 SQL 과 C14'·C31 술어를 저장소
# V0~V48 위에 올리고, 소유자 권한으로 합성 행을 넣어 C14'(커서 판과 개정 전 전수 판), C31, 전환 선행 조건, 게이지 질의,
# 기존 C05 의 문장 시간을 잰다. 폐기용 postgres:17-alpine 컨테이너 하나를 --rm 과 127.0.0.1 무작위 포트로 띄우고 지운다.
# 카오스 스택(chaos-*), 보존 볼륨, 영속 DB 는 건드리지 않는다. 합성 행은 트리거를 끄고(session_replication_role = replica)
# 넣으므로 원장 균형·잔고 정합은 성립하지 않는다. 시간만 본다. 판정(S3 설계 7-3 X7)은 이 예비 측정이 아니라 X7 이 한다.
# 사용: N=1000000 bash s3-scale-probe.sh > s3-scale-probe-1m.log 2>&1
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
DB_DOC="$ROOT/docs/reference/contracts/백엔드_DB_설계.md"
H_DOC="$ROOT/docs/reference/contracts/하네스_검증설계.md"
MIG="$ROOT/server/app/src/main/resources/db/migration"
IMAGE=postgres:17-alpine
NAME="pml-probe-s3scale-${RANDOM}${RANDOM}"
N="${N:-1000000}"
PENDING="${PENDING:-300}"
WORK="$(mktemp -d)"
to_host() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
cleanup() { docker stop "$NAME" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT

awk -v out="$WORK" '
/^### 3\.4 `V48__market_partition_lease\.sql`/ { in34 = 1 }
/^### 3\.5 / || /^## 4\. / { in34 = 0 }
in34 && /^```sql$/ { blk++; inb = 1; next }
in34 && inb && /^```$/ { inb = 0; next }
in34 && inb && blk == 2 { print > (out "/v49.sql") }
' "$DB_DOC"
awk -v out="$WORK" '
/^### 3\.5 `V50__/ { in35 = 1; next }
/^### / || /^## / { in35 = 0 }
in35 && /^```sql$/ { blk++; inb = 1; next }
in35 && inb && /^```$/ { inb = 0; next }
in35 && inb && blk == 1 { print > (out "/v50.sql") }
' "$DB_DOC"
awk '/^-- ══ C14 /{p = 1} p {print} p && /-- 0행/ {exit}' "$H_DOC" > "$WORK/c14p.sql"
awk '/^-- ══ C31 /{p = 1} p {print} p && /-- 0행$/ {exit}' "$H_DOC" > "$WORK/c31.sql"
awk '/^-- ── C31 앱 판/{p = 1} p {print} p && /-- 0 기대$/ {exit}' "$H_DOC" > "$WORK/c31app.sql"
test -s "$WORK/v49.sql" && test -s "$WORK/v50.sql" && test -s "$WORK/c14p.sql" && test -s "$WORK/c31.sql" && test -s "$WORK/c31app.sql"
echo "N=$N PENDING=$PENDING"
sha256sum "$WORK/v50.sql" "$WORK/c14p.sql" "$WORK/c31.sql" "$WORK/c31app.sql" | sed "s|$WORK/||"

docker run -d --rm --name "$NAME" -e POSTGRES_PASSWORD=probe -e POSTGRES_DB=scale \
    -p 127.0.0.1::5432 "$IMAGE" >/dev/null
until docker logs "$NAME" 2>&1 | grep -q 'PostgreSQL init process complete'; do sleep 1; done
until docker exec "$NAME" pg_isready -U postgres -d scale >/dev/null 2>&1; do sleep 1; done
sleep 1
echo "container=$NAME image=$IMAGE port=$(docker port "$NAME" 5432/tcp)"
docker exec "$NAME" sh -c 'echo "nproc=$(nproc)"; grep -E "MemTotal" /proc/meminfo'
docker exec "$NAME" psql -X -U postgres -d scale -At -c "SELECT version()" \
  -c "SELECT name || '=' || setting || coalesce(unit, '') FROM pg_settings WHERE name IN ('shared_buffers','work_mem','max_parallel_workers_per_gather','jit') ORDER BY name"

MSYS_NO_PATHCONV=1 docker exec "$NAME" mkdir -p /c
MSYS_NO_PATHCONV=1 docker cp "$(to_host "$MIG")" "$NAME:/c/migration"
for f in v49.sql v50.sql c14p.sql c31.sql c31app.sql; do
    MSYS_NO_PATHCONV=1 docker cp "$(to_host "$WORK/$f")" "$NAME:/c/$f"
done
for f in s3-seed.sql s3-scale-apply.sh; do
    MSYS_NO_PATHCONV=1 docker cp "$(to_host "$HERE/$f")" "$NAME:/c/$f"
done
MSYS_NO_PATHCONV=1 docker exec -e N="$N" -e PENDING="$PENDING" "$NAME" sh /c/s3-scale-apply.sh
