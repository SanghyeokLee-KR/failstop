#!/usr/bin/env bash
# S3 설계 7-3 X7(검토 R5): 감사 문장의 누적 행 비용 판정(Git Bash). 스택 없이 폐기용 postgres:17-alpine 컨테이너 하나를
# --rm 과 127.0.0.1 무작위 포트로 띄우고 지운다. 카오스 스택(chaos-*), 보존 볼륨, 영속 DB 는 건드리지 않는다.
# 저장소 V0~V50 을 올리고 12마켓 시드 위에 합성 주문 생성 명령 N 건을 넣은 뒤, 코드에서 뽑은 문장(../extract_sql.py)의 시간을
# 권위 run 과 같은 REPEATABLE READ READ ONLY 트랜잭션 안에서 JIT 켬·끔으로 두 번씩 재 둘째 값을 쓴다(../stmt_timing.py).
# 판정 문턱(1,000,000건): C14' JIT 켬 1,500ms, C31 앱 판 JIT 끔 1,500ms, 게이지 JIT 끔 100ms. 10,000,000건은 기록만 한다.
# 사용: N=1000000 bash s3-scale-probe-12m.sh <출력 폴더>
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
GATE="$(cd "$HERE/.." && pwd)"
ROOT="$(cd "$HERE/../../../.." && pwd)"
MIG="$ROOT/server/app/src/main/resources/db/migration"
IMAGE=postgres:17-alpine
NAME="pml-probe-s3scale-x7-${RANDOM}${RANDOM}"
N="${N:-1000000}"
PENDING="${PENDING:-300}"
OUT="${1:?출력 폴더}"
mkdir -p "$OUT"
to_host() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
cleanup() { docker stop "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

python "$(to_host "$GATE/extract_sql.py")" "$(to_host "$OUT/sql")" > "$OUT/sql-sha256.txt"
echo "N=$N PENDING(마켓당)=$PENDING"
echo "migrations: $(ls "$MIG"/V*.sql | wc -l) files, V50 sha256 $(sha256sum "$MIG/V50__order_intake_reservation.sql" | cut -c1-16)"

docker run -d --rm --name "$NAME" -e POSTGRES_PASSWORD=probe -e POSTGRES_DB=scale --shm-size=256m \
    -p 127.0.0.1::5432 "$IMAGE" >/dev/null
until docker logs "$NAME" 2>&1 | grep -q 'PostgreSQL init process complete'; do sleep 1; done
until docker exec "$NAME" pg_isready -U postgres -d scale >/dev/null 2>&1; do sleep 1; done
sleep 1
echo "container=$NAME image=$IMAGE port=$(docker port "$NAME" 5432/tcp)"
docker exec "$NAME" sh -c 'echo "nproc=$(nproc)"; grep -E "MemTotal" /proc/meminfo'
docker exec "$NAME" psql -X -U postgres -d scale -At -c "SELECT version()" \
  -c "SELECT name || '=' || setting || coalesce(unit, '') FROM pg_settings WHERE name IN ('shared_buffers','work_mem','max_parallel_workers_per_gather','jit','effective_cache_size') ORDER BY name"

MSYS_NO_PATHCONV=1 docker exec "$NAME" mkdir -p /c
MSYS_NO_PATHCONV=1 docker cp "$(to_host "$MIG")" "$NAME:/c/migration"
for f in seed-12m.sql s3-scale-apply-12m.sh; do
    MSYS_NO_PATHCONV=1 docker cp "$(to_host "$HERE/$f")" "$NAME:/c/$f"
done
MSYS_NO_PATHCONV=1 docker exec -e N="$N" -e PENDING="$PENDING" "$NAME" sh /c/s3-scale-apply-12m.sh

echo
echo "== 문장 시간(둘째 회차, ms)"
python "$(to_host "$GATE/stmt_timing.py")" --container "$NAME" --user postgres --db scale \
    --sql-dir "$(to_host "$OUT/sql")" --out "$(to_host "$OUT/timings.json")" --plans "$(to_host "$OUT/plans")"
echo "== 끝"
