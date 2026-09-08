#!/usr/bin/env bash
# 읽기 분산 실험용 standby 를 걷는다. primary 를 비동기로 되돌리는 것이 먼저다.
#
# 순서가 계약이다. standby 를 먼저 지우면 primary 가 동기 대기에 걸린 채 남아
# 커밋이 전부 멈춘다. 2026-09-08 복제 결함 실측이 그 상태를 그대로 재현했다.
set -euo pipefail

PRIMARY=chaos-pg
STANDBY=chaos-pg-standby
PGUSER=${CHAOS_PG_USER:-exchange}
SLOT=standby_1

echo "1/3 primary 를 비동기로 되돌린다"
docker exec "$PRIMARY" psql -U "$PGUSER" -d postgres -q -c \
  "ALTER SYSTEM SET synchronous_standby_names = '';" || true
docker exec "$PRIMARY" psql -U "$PGUSER" -d postgres -q -c "SELECT pg_reload_conf();" || true

echo "2/3 standby 정지"
docker rm -f "$STANDBY" >/dev/null 2>&1 || true

echo "3/3 복제 슬롯과 볼륨 정리"
docker exec "$PRIMARY" psql -U "$PGUSER" -d postgres -q -c \
  "SELECT pg_drop_replication_slot('${SLOT}') WHERE EXISTS
     (SELECT 1 FROM pg_replication_slots WHERE slot_name='${SLOT}');" || true
docker volume rm -f chaos-standby-data >/dev/null 2>&1 || true

docker exec "$PRIMARY" psql -U "$PGUSER" -d postgres -t -A -c \
  "SHOW synchronous_standby_names;"
