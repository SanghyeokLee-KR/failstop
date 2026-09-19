#!/bin/sh
# standby 초기화와 기동. PGDATA 가 비어 있으면 primary 에서 베이스백업을 받고, 있으면 그대로 뜬다.
#
# 왜 entrypoint 를 따로 두는가. postgres 공식 이미지의 초기화는 initdb 경로라 빈 클러스터를
# 만든다. standby 는 primary 의 복사본이어야 하므로 그 경로를 탈 수 없다. compose 의 command
# 만으로는 "비었으면 basebackup, 아니면 기동" 분기를 표현할 수 없다.
#
# 권한. 이 스크립트는 root 로 시작해 볼륨 소유권을 맞춘 뒤 gosu 로 postgres 에 넘긴다.
# PostgreSQL 은 root 실행을 거부한다. 공식 이미지가 쓰는 것과 같은 도구다.
#
# 재기동 안전. 두 번째 기동부터는 PGDATA 가 차 있으므로 basebackup 을 건너뛴다. 받아 둔 WAL 을
# 이어서 재생한다. 이 성질이 없으면 재기동마다 전량 복사가 돌아 복구가 느려진다.
set -e
umask 077

: "${PRIMARY_HOST:?PRIMARY_HOST 가 필요하다}"
: "${PRIMARY_PORT:=5432}"
: "${REPL_USER:?REPL_USER 가 필요하다}"
: "${REPL_PASSWORD:?REPL_PASSWORD 가 필요하다}"
: "${PGDATA:=/var/lib/postgresql/data}"

# 이 스크립트가 초기화할 수 있는 경로는 전용 데이터 볼륨 한 곳뿐이다.
# 오타나 심볼릭 링크가 다른 디렉터리의 삭제로 이어지지 않게 한다.
if [ "$PGDATA" != /var/lib/postgresql/data ] || [ -L "$PGDATA" ]; then
  echo "PGDATA must be /var/lib/postgresql/data and not a symlink" >&2
  exit 1
fi

export PGPASSWORD="$REPL_PASSWORD"

mkdir -p "$PGDATA"
if [ "$(readlink -f "$PGDATA")" != /var/lib/postgresql/data ]; then
  echo "PGDATA resolved outside the data volume" >&2
  exit 1
fi
chown -R postgres:postgres "$PGDATA"

if [ ! -s "$PGDATA/PG_VERSION" ]; then
  echo "standby: PGDATA 가 비었다. $PRIMARY_HOST:$PRIMARY_PORT 에서 베이스백업을 받는다"
  # primary 가 아직 복제를 받을 준비가 안 됐을 수 있다. 기동 순서에 기대지 않고 기다린다.
  until gosu postgres pg_isready -h "$PRIMARY_HOST" -p "$PRIMARY_PORT" -U "$REPL_USER" >/dev/null 2>&1; do
    echo "standby: primary 대기"
    sleep 2
  done
  # 부분 복사가 남아 있으면 PG_VERSION 검사를 통과하지 못한 채 basebackup 이 거부된다.
  rm -rf "${PGDATA:?}"/* "${PGDATA:?}"/.[!.]* 2>/dev/null || true
  # -R 이 standby.signal 과 primary_conninfo 를 함께 써 준다. 손으로 만들지 않는다.
  # -X stream 이 백업 중 생긴 WAL 을 같이 받아 백업 직후 결번을 없앤다.
  gosu postgres pg_basebackup -h "$PRIMARY_HOST" -p "$PRIMARY_PORT" -U "$REPL_USER" \
    -D "$PGDATA" -Fp -Xs -P -R -S "${REPL_SLOT:-standby_1}"
  chmod 0700 "$PGDATA"
  echo "standby: 베이스백업 완료"
else
  echo "standby: PGDATA 가 있다. 이어서 재생한다"
fi

# hot_standby 는 기본이 on 이지만 명시한다. 이 값이 off 면 standby 가 조회를 받지 못해
# 읽기 오프로드 여지가 사라진다.
exec gosu postgres postgres -c hot_standby=on "$@"
