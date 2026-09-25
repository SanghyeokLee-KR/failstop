#!/usr/bin/env bash
# ADR 0034 S7 설계 검토 대응(설계 3절 R7, 11절)의 실행기(Git Bash). 저장소의 실제 V0~V54 를 폐기용 postgres:17-alpine 컨테이너에
# 파일마다 한 트랜잭션으로 올리고, DB 설계 §3.10 의 V55 블록과 §6.4 의 고친 ⑯(C22' 목록 판)·⑳(C30)을 문서에서 뽑아 올린다.
# 수 판(c22p-count-r2.sql)과 구간 경계 증분 표본(segment-old-rows.sql)은 이 폴더의 파일이다. 확인 항목은 s7-pg-review-apply.sh 머리에 있다.
# 컨테이너는 --rm, CPU 0.5, 호스트 포트를 열지 않고, 이름 pml-probe-s7rv-* 이며 끝나면 지운다. 같은 호스트에서 도는 S6 관문 실험을
# 흔들지 않도록 CPU 를 묶었다. 카오스 스택(chaos-*), 실험 스택(exchange-pml), 보존 볼륨, 영속 DB 는 건드리지 않는다.
# 비밀번호는 이 컨테이너 안에서만 쓰는 고정 값이다.
# 사용: bash s7-pg-review-probe.sh > s7-pg-review-probe.log 2>&1
set -euo pipefail
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
DB_DOC="$ROOT/docs/reference/contracts/백엔드_DB_설계.md"
MIG="$ROOT/server/app/src/main/resources/db/migration"
IMAGE=postgres:17-alpine
NAME="pml-probe-s7rv-${RANDOM}${RANDOM}"
WORK="$(mktemp -d)"
to_host() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
cleanup() { docker stop "$NAME" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT

awk -v out="$WORK" '
/^### 3\.10 `V55__/ { in310 = 1; next }
/^### / || /^## / { in310 = 0 }
in310 && /^```sql$/ { blk++; inb = 1; next }
in310 && inb && /^```$/ { inb = 0; next }
in310 && inb && blk == 1 { print > (out "/v55.sql") }
' "$DB_DOC"
awk '/^### 6\.4 /{ s = 1 } /^### 6\.5 /{ s = 0 } s' "$DB_DOC" > "$WORK/s64.md"
awk -v out="$WORK" '
/^-- ⑯/ { mode = "c22p" }
/^-- ⑰/ || /^-- ⑱/ || /^-- ⑲/ || /^```/ { if (mode == "c22p" || mode == "c30") mode = "" }
/^-- ⑳/ { mode = "c30" }
mode == "c22p" { print > (out "/c22p.sql") }
mode == "c30" { print > (out "/c30.sql") }
' "$WORK/s64.md"
cp "$HERE/c22p-count-r2.sql" "$WORK/c22p-count.sql"
cp "$HERE/segment-old-rows.sql" "$WORK/segment-old-rows.sql"
echo "source=documents(DB 설계 §3.10 V55, §6.4 ⑯·⑳) + c22p-count-r2.sql + segment-old-rows.sql"
for f in v55 c22p c30 c22p-count segment-old-rows; do
    test -s "$WORK/$f.sql" || { echo "missing $f"; exit 1; }
    echo "$f lines=$(wc -l < "$WORK/$f.sql") sha256=$(tr -d '\r' < "$WORK/$f.sql" | sha256sum | cut -c1-16)"
done
ls "$MIG" | sed -E 's/V([0-9]+)__.*/\1/' | sort -n | tail -1 | sed 's/^/repository max migration=V/'

docker run -d --rm --name "$NAME" --cpus 0.5 -e POSTGRES_PASSWORD=probe -e POSTGRES_DB=probe "$IMAGE" >/dev/null
until docker logs "$NAME" 2>&1 | grep -q 'PostgreSQL init process complete'; do sleep 1; done
until docker exec "$NAME" pg_isready -U postgres -d probe >/dev/null 2>&1; do sleep 1; done
sleep 1
echo "start $(date -u +%Y-%m-%dT%H:%M:%SZ) container=$NAME image=$IMAGE digest=$(docker image inspect "$IMAGE" --format '{{index .RepoDigests 0}}' 2>/dev/null || true)"
docker exec "$NAME" psql -X -U postgres -d probe -At -c 'SELECT version()'
docker exec "$NAME" mkdir -p /c
docker cp "$(to_host "$MIG")" "$NAME:/c/migration"
for f in v55 c22p c30 c22p-count segment-old-rows; do docker cp "$(to_host "$WORK/$f.sql")" "$NAME:/c/$f.sql"; done
docker cp "$(to_host "$HERE/s7-pg-review-apply.sh")" "$NAME:/c/s7-pg-review-apply.sh"
docker exec "$NAME" sh /c/s7-pg-review-apply.sh
echo "end $(date -u +%Y-%m-%dT%H:%M:%SZ)"
