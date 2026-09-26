#!/bin/sh
# 시퀀스가 크래시 복구 뒤 값을 건너뛰는지 본다. postgres 사용자로 폐기용 컨테이너 안에서 initdb 부터 한다.
set -e
D=/tmp/crashdb
rm -rf "$D"
initdb -D "$D" -U postgres >/dev/null 2>&1
start() { pg_ctl -D "$D" -o "-c listen_addresses=" -l /tmp/crash.log -w start >/dev/null; }
Q() { psql -X -At -U postgres -d postgres -c "$1"; }
start
Q "CREATE SEQUENCE s CACHE 1 NO CYCLE" >/dev/null
Q "SELECT 'before crash nextval', nextval('s'), nextval('s'), nextval('s')"
Q "SELECT 'before crash state', last_value, log_cnt, is_called FROM s"
pg_ctl -D "$D" -m immediate -w stop >/dev/null
start
Q "SELECT 'after crash state', last_value, log_cnt, is_called FROM s"
Q "SELECT 'after crash nextval', nextval('s')"
pg_ctl -D "$D" -m fast -w stop >/dev/null
start
Q "SELECT 'after clean restart state', last_value, log_cnt, is_called FROM s"
Q "SELECT 'after clean restart nextval', nextval('s')"
pg_ctl -D "$D" -m fast -w stop >/dev/null
