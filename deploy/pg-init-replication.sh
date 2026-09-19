#!/bin/sh
# primary 첫 기동에만 도는 초기화. 복제 롤과 pg_hba 항목과 슬롯을 만든다.
#
# 공식 이미지는 /docker-entrypoint-initdb.d 를 initdb 직후 한 번만 실행한다. 곧 이미 초기화된
# 볼륨에서는 이 파일이 무시되므로, 운영 중인 primary 에 복제를 붙일 때는 아래 SQL 을 손으로
# 한 번 돌려야 한다. 그 절차는 docs/operations/ 의 수동 페일오버 문서에 있다.
#
# 비밀번호를 파일에 적지 않는다. REPL_PASSWORD 환경변수로 받고 그 값은 노드별 .env 또는 SSM 이
# 소유한다(18절).
set -e
umask 077

: "${REPL_USER:=replicator}"
: "${REPL_PASSWORD:?REPL_PASSWORD 가 필요하다. deploy/.env 에 넣는다}"
: "${REPL_SLOT:=standby_1}"
: "${REPL_CIDR:=10.0.0.0/16}"

# pg_hba.conf에는 SQL 인용을 적용할 수 없으므로 단순 역할명과 CIDR만 허용한다.
case "$REPL_USER" in ''|[0-9]*|*[!a-zA-Z0-9_]*) echo "invalid REPL_USER" >&2; exit 1;; esac
case "$REPL_SLOT" in ''|*[!a-z0-9_]*) echo "invalid REPL_SLOT" >&2; exit 1;; esac
case "$REPL_CIDR" in ''|*[!a-fA-F0-9.:/]*) echo "invalid REPL_CIDR" >&2; exit 1;; esac
export REPL_USER REPL_SLOT REPL_PASSWORD

# LOGIN REPLICATION 만 준다. 이 롤로 데이터를 읽거나 쓸 이유가 없다(최소 권한, 18절).
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
\getenv repl_user REPL_USER
\getenv repl_password REPL_PASSWORD
\getenv repl_slot REPL_SLOT
CREATE ROLE :"repl_user" WITH LOGIN REPLICATION PASSWORD :'repl_password';
SELECT pg_create_physical_replication_slot(:'repl_slot');
SQL

# 복제 접속 허용. 기본 pg_hba 는 replication 을 로컬로만 연다. 대역은 VPC 안이고 그 밖은
# 보안그룹이 막는다. trust 를 쓰지 않는다.
cat >> "$PGDATA/pg_hba.conf" <<HBA

# 복제 접속(ADR 0024). scram-sha-256 만 허용한다.
host    replication     $REPL_USER      $REPL_CIDR       scram-sha-256
HBA

echo "replication: 롤 $REPL_USER, 슬롯 $REPL_SLOT, pg_hba 항목을 만들었다"
