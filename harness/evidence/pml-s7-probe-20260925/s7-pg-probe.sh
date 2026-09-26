#!/usr/bin/env bash
# ADR 0034 S7 설계 3절 P7 의 실행기(Git Bash). 저장소의 실제 V0~V54 를 폐기용 postgres:17-alpine 컨테이너에 파일마다 한 트랜잭션으로
# 올리고, V55 SQL 과 C22'·C30 SQL 을 올려 P7-0~P7-6 을 확인한다. 원천은 둘이다. S7_SOURCE=draft(기본)는 이 폴더의 초안
# (v55-draft.sql, c22p-c30-draft.sql), S7_SOURCE=doc 은 DB 설계 §3.10 의 첫 sql 블록(V55)과 §6.4 의 ⑯·⑳ 항목이다.
# 컨테이너는 --rm, 127.0.0.1 무작위 포트, 이름 pml-probe-s7pg-* 이고 끝나면 지운다. 카오스 스택(chaos-*), 실험 스택(exchange-pml),
# 보존 볼륨, 영속 DB 는 건드리지 않는다. 비밀번호는 이 컨테이너 안에서만 쓰는 고정 값이다.
# 사용: bash s7-pg-probe.sh > s7-pg-probe-draft.log 2>&1 ;  S7_SOURCE=doc bash s7-pg-probe.sh > s7-pg-probe.log 2>&1
set -euo pipefail
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
DB_DOC="$ROOT/docs/reference/contracts/백엔드_DB_설계.md"
MIG="$ROOT/server/app/src/main/resources/db/migration"
IMAGE=postgres:17-alpine
NAME="pml-probe-s7pg-${RANDOM}${RANDOM}"
WORK="$(mktemp -d)"
SOURCE="${S7_SOURCE:-draft}"
to_host() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
cleanup() { docker stop "$NAME" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT

# ⑯ 와 ⑳ 항목을 한 파일(초안이나 §6.4 블록)에서 뽑는다. ⑯ 은 ⑰·⑳·코드 끝 앞까지, ⑳ 은 다음 항목·코드 끝 앞까지다.
split_invariants() { # split_invariants <입력>
    awk -v out="$WORK" '
    /^-- ⑯/ { mode = "c22p" }
    /^-- ⑰/ || /^-- ⑱/ || /^-- ⑲/ || /^```/ { if (mode == "c22p" || mode == "c30") mode = "" }
    /^-- ⑳/ { mode = "c30" }
    mode == "c22p" { print > (out "/c22p.sql") }
    mode == "c30" { print > (out "/c30.sql") }
    ' "$1"
}

if [ "$SOURCE" = doc ]; then
    awk -v out="$WORK" '
    /^### 3\.10 `V55__/ { in310 = 1; next }
    /^### / || /^## / { in310 = 0 }
    in310 && /^```sql$/ { blk++; inb = 1; next }
    in310 && inb && /^```$/ { inb = 0; next }
    in310 && inb && blk == 1 { print > (out "/v55.sql") }
    ' "$DB_DOC"
    awk '/^### 6\.4 /{ s = 1 } /^### 6\.5 /{ s = 0 } s' "$DB_DOC" > "$WORK/s64.md"
    split_invariants "$WORK/s64.md"
    echo "source=documents(DB 설계 §3.10 V55, §6.4 ⑯·⑳)"
else
    cp "$HERE/v55-draft.sql" "$WORK/v55.sql"
    split_invariants "$HERE/c22p-c30-draft.sql"
    echo "source=draft(v55-draft.sql, c22p-c30-draft.sql)"
fi
for f in v55 c22p c30; do
    test -s "$WORK/$f.sql" || { echo "missing $f"; exit 1; }
    echo "$f lines=$(wc -l < "$WORK/$f.sql") sha256=$(tr -d '\r' < "$WORK/$f.sql" | sha256sum | cut -c1-16)"
done
ls "$MIG" | sed -E 's/V([0-9]+)__.*/\1/' | sort -n | tail -1 | sed 's/^/repository max migration=V/'

docker run -d --rm --name "$NAME" -e POSTGRES_PASSWORD=probe -e POSTGRES_DB=probe \
    -p 127.0.0.1::5432 "$IMAGE" >/dev/null
until docker logs "$NAME" 2>&1 | grep -q 'PostgreSQL init process complete'; do sleep 1; done
until docker exec "$NAME" pg_isready -U postgres -d probe >/dev/null 2>&1; do sleep 1; done
sleep 1
echo "start $(date -u +%Y-%m-%dT%H:%M:%SZ) container=$NAME image=$IMAGE port=$(docker port "$NAME" 5432/tcp)"
docker exec "$NAME" psql -X -U postgres -d probe -At -c 'SELECT version()'
docker exec "$NAME" mkdir -p /c
docker cp "$(to_host "$MIG")" "$NAME:/c/migration"
for f in v55 c22p c30; do docker cp "$(to_host "$WORK/$f.sql")" "$NAME:/c/$f.sql"; done
docker cp "$(to_host "$HERE/s7-pg-apply.sh")" "$NAME:/c/s7-pg-apply.sh"
docker exec "$NAME" sh /c/s7-pg-apply.sh
