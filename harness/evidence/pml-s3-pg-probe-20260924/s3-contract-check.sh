#!/usr/bin/env bash
# ADR 0034 S3 설계 3절 C 절의 실행기(Git Bash). DB 설계 §3.4 의 V49 계약 SQL 과 §3.5 의 V50 계약 SQL, 하네스 검증설계
# §4-1 의 C14'·C31 술어를 문서에서 그대로 뽑아 저장소의 실제 V0~V48 위에 올린다(마이그레이션 파일이 아니라 계약 문서의
# SQL 이다). 폐기용 postgres:17-alpine 컨테이너 하나를 --rm 과 127.0.0.1 무작위 포트로 띄우고 지운다. 카오스 스택
# (chaos-*), 보존 볼륨, 영속 DB 는 건드리지 않는다.
# 설계 초안 단계에서 문서에 아직 없는 SQL 을 시험하려면 S3_DRAFT_DIR 에 v50.sql·c14p.sql·c31.sql·c31app.sql 을 둔다.
# 사용: bash s3-contract-check.sh > s3-contract-check.log 2>&1
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
DB_DOC="$ROOT/docs/reference/contracts/백엔드_DB_설계.md"
H_DOC="$ROOT/docs/reference/contracts/하네스_검증설계.md"
MIG="$ROOT/server/app/src/main/resources/db/migration"
IMAGE=postgres:17-alpine
NAME="pml-probe-s3contract-${RANDOM}${RANDOM}"
WORK="$(mktemp -d)"
to_host() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
cleanup() { docker stop "$NAME" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT

# §3.4 의 둘째 sql 블록이 V49 다(S2 contract-check.sh 와 같은 추출).
awk -v out="$WORK" '
/^### 3\.4 `V48__market_partition_lease\.sql`/ { in34 = 1 }
/^### 3\.5 / || /^## 4\. / { in34 = 0 }
in34 && /^```sql$/ { blk++; inb = 1; next }
in34 && inb && /^```$/ { inb = 0; next }
in34 && inb && blk == 2 { print > (out "/v49.sql") }
' "$DB_DOC"
test -s "$WORK/v49.sql"
if [ -n "${S3_DRAFT_DIR:-}" ]; then
    cp "$S3_DRAFT_DIR/v50.sql" "$S3_DRAFT_DIR/c14p.sql" "$S3_DRAFT_DIR/c31.sql" "$S3_DRAFT_DIR/c31app.sql" "$WORK/"
    echo "source=draft($S3_DRAFT_DIR)"
else
    # §3.5 의 첫 sql 블록이 V50 이다.
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
    echo "source=documents"
fi
test -s "$WORK/v50.sql" && test -s "$WORK/c14p.sql" && test -s "$WORK/c31.sql" && test -s "$WORK/c31app.sql"
echo "v49 lines=$(wc -l < "$WORK/v49.sql") v50 lines=$(wc -l < "$WORK/v50.sql") c14p lines=$(wc -l < "$WORK/c14p.sql") c31 lines=$(wc -l < "$WORK/c31.sql") c31app lines=$(wc -l < "$WORK/c31app.sql")"
sha256sum "$WORK/v50.sql" "$WORK/c14p.sql" "$WORK/c31.sql" "$WORK/c31app.sql" | sed "s|$WORK/||"

docker run -d --rm --name "$NAME" -e POSTGRES_PASSWORD=probe -e POSTGRES_DB=contract \
    -p 127.0.0.1::5432 "$IMAGE" >/dev/null
until docker logs "$NAME" 2>&1 | grep -q 'PostgreSQL init process complete'; do sleep 1; done
until docker exec "$NAME" pg_isready -U postgres -d contract >/dev/null 2>&1; do sleep 1; done
sleep 1
echo "container=$NAME image=$IMAGE port=$(docker port "$NAME" 5432/tcp)"
docker exec "$NAME" psql -X -U postgres -d contract -At -c 'SELECT version()'

MSYS_NO_PATHCONV=1 docker exec "$NAME" mkdir -p /c
# V49 이상 파일은 저장소에 아직 없다. V0~V48 만 복사되는지 확인한다.
MSYS_NO_PATHCONV=1 docker cp "$(to_host "$MIG")" "$NAME:/c/migration"
docker exec "$NAME" sh -c 'ls /c/migration | sort -V | tail -1'
for f in v49.sql v50.sql c14p.sql c31.sql c31app.sql; do
    MSYS_NO_PATHCONV=1 docker cp "$(to_host "$WORK/$f")" "$NAME:/c/$f"
done
for f in s3-seed.sql s3-contract-apply.sh; do
    MSYS_NO_PATHCONV=1 docker cp "$(to_host "$HERE/$f")" "$NAME:/c/$f"
done
MSYS_NO_PATHCONV=1 docker exec "$NAME" sh /c/s3-contract-apply.sh
