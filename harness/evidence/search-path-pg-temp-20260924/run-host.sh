#!/bin/sh
# 폐기용 postgres:17-alpine 컨테이너에 저장소 마이그레이션과 probe.sh 를 복사해 돌리고 컨테이너를 지운다.
# 출력은 같은 폴더의 probe.log 다. 로컬 영속 DB(chaos-pgdata)와 카오스 스택은 쓰지 않는다.
# Windows Git Bash 에서는 한글 경로 대신 ASCII 정션에서 돌린다.
set -eu
export MSYS_NO_PATHCONV=1
here=$(cd "$(dirname "$0")" && { pwd -W 2>/dev/null || pwd; })
repo=$(cd "$here/../../.." && { pwd -W 2>/dev/null || pwd; })
name="sp-pgtemp-probe-$$"
docker run -d --name "$name" -e POSTGRES_PASSWORD=probe -e POSTGRES_DB=exchange postgres:17-alpine >/dev/null
trap 'docker rm -f "$name" >/dev/null' EXIT
until docker exec "$name" pg_isready -U postgres -h 127.0.0.1 >/dev/null 2>&1; do sleep 1; done
docker cp "$repo/server/app/src/main/resources/db/migration" "$name:/probe-migration"
docker cp "$here/probe.sh" "$name:/probe.sh"
{
  echo "container=$name image=postgres:17-alpine"
  docker exec "$name" psql -X -U postgres -At -c "SELECT version()"
  docker exec "$name" sh /probe.sh
} > "$here/probe.log" 2>&1
