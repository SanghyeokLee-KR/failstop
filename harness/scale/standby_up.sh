#!/usr/bin/env bash
# 카오스 스택 primary 옆에 동기 standby 를 세운다. 읽기 분산 실험 전용이다.
#
# 기준 컴포즈를 고치지 않는다. 카오스 실험 9종이 이 스택을 권위로 쓰므로 서비스를
# 늘리면 그쪽 판정이 다른 토폴로지를 보게 된다. 그래서 docker run 으로 옆에 세우고
# 실험이 끝나면 standby_down.sh 로 걷는다.
#
# 복제 슬롯을 반드시 만든다(-C -S). 슬롯 없이 pg_basebackup 을 하면 베이스백업과
# 스트리밍 사이에 WAL 세그먼트가 재활용돼 "WAL segment already removed" 로 죽는다.
# 2026-09-08 회차에서 실제로 그렇게 깨졌다.
set -euo pipefail

PRIMARY=chaos-pg
STANDBY=chaos-pg-standby
NET=exchange-chaos_chaos-net
PGUSER=${CHAOS_PG_USER:-exchange}
PGPASS=${CHAOS_PG_PASSWORD:-exchange}
SLOT=standby_1

echo "1/5 복제 사용자와 슬롯 준비"
docker exec "$PRIMARY" psql -U "$PGUSER" -d postgres -q -c \
  "DO \$\$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='repl') THEN
     CREATE ROLE repl WITH REPLICATION LOGIN PASSWORD '${PGPASS}'; END IF; END \$\$;"

# pg_hba 에 복제 항목을 넣는다. 기본 이미지는 replication 을 127.0.0.1 과 ::1 로만 연다.
# 컨테이너 망에서 오는 pg_basebackup 이 "no pg_hba.conf entry for replication connection"
# 으로 죽는다(2026-09-09 실측). 도커 사설 대역만 연다. 같은 줄이 이미 있으면 넣지 않는다.
HBA_LINE="host replication all 172.16.0.0/12 scram-sha-256"
docker exec "$PRIMARY" sh -c 'grep -qF "$0" "$PGDATA/pg_hba.conf" || echo "$0" >> "$PGDATA/pg_hba.conf"' "$HBA_LINE"
docker exec "$PRIMARY" psql -U "$PGUSER" -d postgres -q -c "SELECT pg_reload_conf();" >/dev/null

echo "2/5 기존 standby 정리"
docker rm -f "$STANDBY" >/dev/null 2>&1 || true
docker exec "$PRIMARY" psql -U "$PGUSER" -d postgres -q -c \
  "SELECT pg_drop_replication_slot('${SLOT}') WHERE EXISTS
     (SELECT 1 FROM pg_replication_slots WHERE slot_name='${SLOT}');" || true

echo "3/5 베이스백업"
docker volume rm -f chaos-standby-data >/dev/null 2>&1 || true
docker run --rm --network "$NET" -v chaos-standby-data:/var/lib/postgresql/data \
  -e PGPASSWORD="$PGPASS" postgres:17-alpine \
  sh -c "rm -rf /var/lib/postgresql/data/* && \
         pg_basebackup -h ${PRIMARY} -p 5432 -U repl -D /var/lib/postgresql/data \
           -R -X stream -C -S ${SLOT} -c fast && \
         chown -R postgres:postgres /var/lib/postgresql/data && \
         chmod 700 /var/lib/postgresql/data"

echo "4/5 standby 기동"
docker run -d --name "$STANDBY" --network "$NET" \
  -v chaos-standby-data:/var/lib/postgresql/data \
  -e POSTGRES_USER="$PGUSER" -e POSTGRES_PASSWORD="$PGPASS" \
  postgres:17-alpine >/dev/null

for _ in $(seq 1 40); do
  if docker exec "$STANDBY" pg_isready -U "$PGUSER" >/dev/null 2>&1; then break; fi
  sleep 2
done

echo "5/5 primary 를 동기 복제로 전환"
# ALTER SYSTEM 과 pg_reload_conf 를 한 호출에 묶지 않는다. 트랜잭션 블록 안에서
# ALTER SYSTEM 이 거부된다(실측 2026-09-08).
docker exec "$PRIMARY" psql -U "$PGUSER" -d postgres -q -c \
  "ALTER SYSTEM SET synchronous_standby_names = 'ANY 1 (${SLOT})';"
docker exec "$PRIMARY" psql -U "$PGUSER" -d postgres -q -c "SELECT pg_reload_conf();"

sleep 3
docker exec "$PRIMARY" psql -U "$PGUSER" -d postgres -t -A -c \
  "SELECT application_name, state, sync_state FROM pg_stat_replication;"
