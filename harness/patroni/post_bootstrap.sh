#!/bin/sh
# Patroni 가 클러스터를 처음 만든 직후 한 번 부른다(bootstrap.post_bootstrap). 첫 인자는 superuser 연결 문자열이다.
# 계정 이름과 비밀번호는 compose 가 넘긴 실험 전용 환경 변수에서 받는다(.env.patroni). 실제 비밀이 아니다.
#
# 앱 계정은 카오스 스택과 같이 superuser 로 만든다. 마이그레이션(V0)이 CREATE ROLE 을 하고, 두 스택을 같은 조건에서
# 비교해야 한다. 운영 배치는 deploy/provision-runtime-roles.sql 의 런타임 역할을 쓴다.
set -eu

psql "$1" -v ON_ERROR_STOP=1 \
  -v app_user="$EXCHANGE_DB_USER" -v app_password="$EXCHANGE_DB_PASSWORD" \
  -v monitor_user="$EXCHANGE_MONITOR_USER" -v monitor_password="$EXCHANGE_MONITOR_PASSWORD" <<'SQL'
CREATE ROLE :"app_user" LOGIN SUPERUSER PASSWORD :'app_password';
CREATE DATABASE exchange OWNER :"app_user";
-- 수집기는 pg_monitor 라 pg_stat_activity·pg_stat_replication 의 모든 행을 본다(설계 7-1 확장 질의).
CREATE ROLE :"monitor_user" LOGIN PASSWORD :'monitor_password' IN ROLE pg_monitor;
-- DB 수준 ACK 기록기의 대상. 거래 스키마와 마이그레이션을 건드리지 않는다(설계 8-1).
CREATE DATABASE infra_probe OWNER :"app_user";
SQL
