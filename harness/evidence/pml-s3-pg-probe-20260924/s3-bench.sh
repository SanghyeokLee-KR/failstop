#!/usr/bin/env bash
# S3 설계 3절 B 절 실행기(Git Bash). 접수 트랜잭션의 DB 안 비용만 P1 과 P2 로 나눠 잰다. 앱·네트워크·접수 락이 없는
# pgbench 측정이라 결정 5(마켓당 접수 천장 손실)의 판정 근거가 아니고, 폐기용 스택 실험(S3 설계 7절 X5)의 예고치다.
# DB 설계 §3.4 V49·§3.5 V50 계약 SQL 을 문서에서 뽑아 저장소 V0~V48 위에 올린 폐기용 postgres:17-alpine 컨테이너
# 하나를 --rm 과 127.0.0.1 무작위 포트로 띄우고 지운다. 카오스 스택과 보존 볼륨은 건드리지 않는다.
# 사용: bash s3-bench.sh > s3-bench.log 2>&1
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
DB_DOC="$ROOT/docs/reference/contracts/백엔드_DB_설계.md"
MIG="$ROOT/server/app/src/main/resources/db/migration"
IMAGE=postgres:17-alpine
NAME="pml-probe-s3bench-${RANDOM}${RANDOM}"
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
test -s "$WORK/v49.sql" && test -s "$WORK/v50.sql"

# pgbench 한 트랜잭션 = 10인자 머리말 + 저널 행 함수 + 커밋. 앱의 append 와 같은 두 문장이다.
# uid 는 SAME=1 이면 모두 1(같은 사용자 잔고 행 경합), 아니면 클라이언트마다 다른 사용자다.
cat > "$WORK/append.sql" <<'EOF'
\set oid random(1, 900000000000)
\set uid 1 + (:client_id * :spread)
BEGIN;
SELECT out_seq AS s FROM append_command_prologue('bench-node', :ep, true, :uid, 'b' || :oid, 1::smallint, :oid, probe_payload('BUY', 'LIMIT', '50000', '0.0001', NULL), 1::smallint, 5) \gset
SELECT append_command_journal_row(:s, 1::smallint, 'NEW', :uid, 'b' || :oid, :oid, 1, 1, probe_payload('BUY', 'LIMIT', '50000', '0.0001', NULL), 0, 'bench-node', :ep);
COMMIT;
EOF

docker run -d --rm --name "$NAME" -e POSTGRES_PASSWORD=probe -e POSTGRES_DB=base \
    -p 127.0.0.1::5432 "$IMAGE" >/dev/null
until docker logs "$NAME" 2>&1 | grep -q 'PostgreSQL init process complete'; do sleep 1; done
until docker exec "$NAME" pg_isready -U postgres -d base >/dev/null 2>&1; do sleep 1; done
sleep 1
echo "container=$NAME image=$IMAGE port=$(docker port "$NAME" 5432/tcp)"
docker exec "$NAME" psql -X -U postgres -d base -At -c "SELECT version(), current_setting('fsync'), current_setting('synchronous_commit')"
docker exec "$NAME" sh -c 'nproc; grep MemTotal /proc/meminfo'
MSYS_NO_PATHCONV=1 docker exec "$NAME" mkdir -p /b
MSYS_NO_PATHCONV=1 docker cp "$(to_host "$MIG")" "$NAME:/b/migration"
for f in v49.sql v50.sql append.sql; do
    MSYS_NO_PATHCONV=1 docker cp "$(to_host "$WORK/$f")" "$NAME:/b/$f"
done
MSYS_NO_PATHCONV=1 docker cp "$(to_host "$HERE/s3-seed.sql")" "$NAME:/b/s3-seed.sql"

MSYS_NO_PATHCONV=1 docker exec -i "$NAME" sh <<'IN'
set -e
P="psql -X -U postgres -At -v ON_ERROR_STOP=1"
for f in $(ls /b/migration/V*.sql | sed -E 's|.*/V([0-9]+)__.*|\1 &|' | sort -n | cut -d' ' -f2); do
  $P -d base -q -1 -f "$f" >/dev/null
done
$P -d base -q -1 -f /b/s3-seed.sql >/dev/null
$P -d base -q -1 -f /b/v49.sql >/dev/null
$P -d base -q -1 -f /b/v50.sql >/dev/null
# 부하 사용자 여덟(1~8). 모두 KRW 를 넉넉히 받는다. 리스는 bench-node 가 1시간 쥔다.
$P -d base -q <<'EOF'
INSERT INTO users (email) SELECT 'bench' || g || '@s3.probe' FROM generate_series(3, 8) g;
INSERT INTO accounts (owner_type, owner_id, purpose, asset_id, shard)
SELECT 'USER', u.id, p.purpose, 1, 0 FROM users u CROSS JOIN (VALUES ('AVAILABLE'), ('LOCKED')) AS p(purpose)
 WHERE u.id >= 3;
SELECT probe_genesis(100 + u.id, u.id, 1::smallint, 1000000000000) FROM users u;
UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' WHERE resource = 'matching-engine';
SELECT try_acquire_lease('matching-engine', 'bench-node', interval '1 hour', 2::smallint);
EOF
EP1=$($P -d base -c "SELECT epoch FROM leader_lease WHERE resource = 'matching-engine'")
$P -d postgres -c "CREATE DATABASE p1 TEMPLATE base" >/dev/null
$P -d postgres -c "CREATE DATABASE p2 TEMPLATE base" >/dev/null
$P -d p2 -q -c "SELECT * FROM set_leader_protocol(2::smallint)" >/dev/null
$P -d p2 -q -c "SELECT try_acquire_lease('matching-engine', 'bench-node', interval '1 hour', 2::smallint)" >/dev/null
EP2=$($P -d p2 -c "SELECT epoch FROM leader_lease WHERE resource = 'matching-engine'")
echo "epoch p1=$EP1 p2=$EP2"
run() {  # $1 db, $2 epoch, $3 clients, $4 spread(0=같은 사용자, 1=클라이언트마다 다른 사용자), $5 transactions
  pgbench -U postgres -n -f /b/append.sql -D ep="$2" -D spread="$4" -c "$3" -j "$3" -t "$5" "$1" 2>&1 \
    | grep -E 'number of transactions actually processed|latency average|tps =' | sed "s|^|$1 c=$3 spread=$4 |"
}
for round in 1 2; do
  echo "-- round $round"
  run p1 "$EP1" 1 1 3000
  run p2 "$EP2" 1 1 3000
  run p1 "$EP1" 4 1 2000
  run p2 "$EP2" 4 1 2000
  run p1 "$EP1" 4 0 2000
  run p2 "$EP2" 4 0 2000
done
echo "-- 결과 행 수와 예약 행 수(P1 은 예약 0 이어야 한다)"
for d in p1 p2; do
  $P -d $d -c "SELECT '$d', (SELECT count(*) FROM command_journal), (SELECT count(*) FROM order_reservations), (SELECT count(*) FROM ledger_transactions WHERE business_event_type = 'RESERVE')"
done
IN
