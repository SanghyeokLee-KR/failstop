#!/usr/bin/env bash
# ADR 0034 S2 설계 확인 실행기(Git Bash). 폐기용 postgres:17-alpine 컨테이너 둘을 --rm 으로 띄우고 지운다.
# 운영·카오스 스택(chaos-pg 등)과 영속 볼륨은 건드리지 않는다. 포트는 127.0.0.1 의 무작위 포트로만 연다.
# 사용: bash run-host.sh > output.log 2>&1
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
# docker cp 는 Windows 경로를 받는다. MSYS 경로 변환을 끈 채로 넘기므로 미리 바꾼다.
if command -v cygpath >/dev/null 2>&1; then HERE="$(cygpath -w "$HERE")"; fi
IMAGE=postgres:17-alpine
NAME="pml-probe-s2-${RANDOM}${RANDOM}"
NAME2="pml-probe-s2crash-${RANDOM}${RANDOM}"

cleanup() {
    docker stop "$NAME" >/dev/null 2>&1 || true
    docker stop "$NAME2" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker run -d --rm --name "$NAME" -e POSTGRES_PASSWORD=probe -e POSTGRES_DB=probe \
    -p 127.0.0.1::5432 "$IMAGE" >/dev/null
until docker logs "$NAME" 2>&1 | grep -q 'PostgreSQL init process complete'; do sleep 1; done
until docker exec "$NAME" pg_isready -U postgres -d probe >/dev/null 2>&1; do sleep 1; done
sleep 1
echo "container=$NAME image=$IMAGE port=$(docker port "$NAME" 5432/tcp)"
docker exec "$NAME" psql -X -U postgres -d probe -At -c 'SELECT version()'

MSYS_NO_PATHCONV=1 docker exec "$NAME" mkdir -p /probe
MSYS_NO_PATHCONV=1 docker cp "$HERE\schema.sql" "$NAME:/probe/schema.sql"
MSYS_NO_PATHCONV=1 docker cp "$HERE\probe.sh" "$NAME:/probe/probe.sh"
for s in q1 q2 q3 q4 q5 q6 q7 q8 q10 q11; do
    # 시나리오는 오류 출력을 기대하는 문장으로 끝날 수 있다. 종료 코드는 기록만 하고 다음으로 간다.
    MSYS_NO_PATHCONV=1 docker exec "$NAME" sh /probe/probe.sh "$s" || echo "(scenario $s exit=$?)"
done

# 시퀀스 크래시 복구는 서버를 즉시 종료해야 하므로 별도 컨테이너에서 initdb 부터 한다.
echo "== Q9 시퀀스와 크래시 복구 (container=$NAME2)"
docker run -d --rm --name "$NAME2" -u postgres --entrypoint sleep "$IMAGE" 300 >/dev/null
MSYS_NO_PATHCONV=1 docker cp "$HERE\crash.sh" "$NAME2:/tmp/crash.sh"
MSYS_NO_PATHCONV=1 docker exec "$NAME2" sh /tmp/crash.sh
