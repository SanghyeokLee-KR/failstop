#!/usr/bin/env bash
# S3 설계 3절 B 절의 분해(Git Bash). s3-bench.sh 와 같은 준비 뒤 한 클라이언트로 네 가지를 잰다.
#   p1_append : P1 의 10인자 머리말 + 저널 행 함수 + 커밋
#   p2_append : P2 의 같은 두 문장(예약 포함) + 커밋
#   reserve   : reserve_funds 한 번 + 커밋(다른 문장 없음)
#   p2_nores  : P2 DB 에서 저널 행 없이 머리말까지만 부르고 롤백(커밋 fsync 를 뺀 머리말 실행 시간)
# 각 칸을 synchronous_commit on 과 off 로 한 번씩 돈다. off 는 커밋의 WAL 플러시 대기를 뺀 값이다.
# DB 안 비용이라 결정 5 의 판정 근거가 아니다. 폐기용 컨테이너를 --rm 과 127.0.0.1 무작위 포트로 띄우고 지운다.
# 사용: bash s3-bench-breakdown.sh > s3-bench-breakdown.log 2>&1
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
DB_DOC="$ROOT/docs/reference/contracts/백엔드_DB_설계.md"
MIG="$ROOT/server/app/src/main/resources/db/migration"
IMAGE=postgres:17-alpine
NAME="pml-probe-s3bench2-${RANDOM}${RANDOM}"
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

cat > "$WORK/append.sql" <<'EOF'
\set oid random(1, 900000000000)
BEGIN;
SELECT out_seq AS s FROM append_command_prologue('bench-node', :ep, true, 1, 'b' || :oid, 1::smallint, :oid, probe_payload('BUY', 'LIMIT', '50000', '0.0001', NULL), 1::smallint, 5) \gset
SELECT append_command_journal_row(:s, 1::smallint, 'NEW', 1, 'b' || :oid, :oid, 1, 1, probe_payload('BUY', 'LIMIT', '50000', '0.0001', NULL), 0, 'bench-node', :ep);
COMMIT;
EOF
cat > "$WORK/reserve.sql" <<'EOF'
\set oid random(1, 900000000000)
BEGIN;
SELECT reserve_funds(:oid, :avail, :locked, 1::smallint, 5);
COMMIT;
EOF
cat > "$WORK/prologue_only.sql" <<'EOF'
\set oid random(1, 900000000000)
BEGIN;
SELECT out_seq FROM append_command_prologue('bench-node', :ep, true, 1, 'b' || :oid, 1::smallint, :oid, probe_payload('BUY', 'LIMIT', '50000', '0.0001', NULL), 1::smallint, 5);
ROLLBACK;
EOF

docker run -d --rm --name "$NAME" -e POSTGRES_PASSWORD=probe -e POSTGRES_DB=base \
    -p 127.0.0.1::5432 "$IMAGE" >/dev/null
until docker logs "$NAME" 2>&1 | grep -q 'PostgreSQL init process complete'; do sleep 1; done
until docker exec "$NAME" pg_isready -U postgres -d base >/dev/null 2>&1; do sleep 1; done
sleep 1
echo "container=$NAME image=$IMAGE port=$(docker port "$NAME" 5432/tcp)"
MSYS_NO_PATHCONV=1 docker exec "$NAME" mkdir -p /b
MSYS_NO_PATHCONV=1 docker cp "$(to_host "$MIG")" "$NAME:/b/migration"
for f in v49.sql v50.sql append.sql reserve.sql prologue_only.sql; do
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
$P -d base -q -c "SELECT probe_genesis(101, 1, 1::smallint, 1000000000000)" >/dev/null
$P -d base -q -c "UPDATE leader_lease SET lease_until = clock_timestamp() - interval '1 second' WHERE resource = 'matching-engine'" >/dev/null
$P -d base -q -c "SELECT try_acquire_lease('matching-engine', 'bench-node', interval '1 hour', 2::smallint)" >/dev/null
EP1=$($P -d base -c "SELECT epoch FROM leader_lease WHERE resource = 'matching-engine'")
AV=$($P -d base -c "SELECT probe_acct(1, 'AVAILABLE', 1::smallint)")
LK=$($P -d base -c "SELECT probe_acct(1, 'LOCKED', 1::smallint)")
$P -d postgres -c "CREATE DATABASE p1 TEMPLATE base" >/dev/null
$P -d postgres -c "CREATE DATABASE p2 TEMPLATE base" >/dev/null
$P -d p2 -q -c "SELECT * FROM set_leader_protocol(2::smallint)" >/dev/null
$P -d p2 -q -c "SELECT try_acquire_lease('matching-engine', 'bench-node', interval '1 hour', 2::smallint)" >/dev/null
EP2=$($P -d p2 -c "SELECT epoch FROM leader_lease WHERE resource = 'matching-engine'")
echo "epoch p1=$EP1 p2=$EP2 avail=$AV locked=$LK"
lat() {  # $1 label, $2 db, $3 script, $4 sync, $5 tx, 나머지 -D
  label=$1; db=$2; sc=$3; sync=$4; n=$5; shift 5
  PGOPTIONS="-c synchronous_commit=$sync" pgbench -U postgres -n -f "/b/$sc" "$@" -c 1 -j 1 -t "$n" "$db" 2>&1 \
    | grep -E 'latency average' | sed "s|^|$label sync=$sync |"
}
for round in 1 2 3; do
  echo "-- round $round"
  for sync in on off; do
    lat p1_append p1 append.sql $sync 2000 -D ep="$EP1"
    lat p2_append p2 append.sql $sync 2000 -D ep="$EP2"
    lat reserve p1 reserve.sql $sync 2000 -D avail="$AV" -D locked="$LK"
    lat p2_prologue_rollback p2 prologue_only.sql $sync 2000 -D ep="$EP2"
    lat p1_prologue_rollback p1 prologue_only.sql $sync 2000 -D ep="$EP1"
  done
done
echo "-- WAL 바이트/트랜잭션(P1 append 대 P2 append, 각 500건)"
for d in p1 p2; do
  E=$([ $d = p1 ] && echo $EP1 || echo $EP2)
  B=$($P -d $d -c "SELECT pg_current_wal_lsn()")
  pgbench -U postgres -n -f /b/append.sql -D ep="$E" -c 1 -t 500 $d >/dev/null 2>&1
  A=$($P -d $d -c "SELECT pg_current_wal_lsn()")
  $P -d $d -c "SELECT '$d wal_bytes_per_tx', round(pg_wal_lsn_diff('$A', '$B') / 500.0)"
done
IN
