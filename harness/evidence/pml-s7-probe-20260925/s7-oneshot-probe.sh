#!/usr/bin/env bash
# ADR 0034 S7 설계 3절 P7-9 의 실행기(Git Bash). 한 마켓 치명 실험(Q10)의 주입 방식을 확인한다. 폐기용 postgres:17-alpine
# 컨테이너 하나(--rm, 호스트 포트 없음, 이름 pml-probe-s7os-*)에서 (1) 사용자 정의 SQLSTATE S7F01 로 RAISE 할 수 있는지,
# (2) 비트랜잭션 시퀀스의 nextval 이 롤백 뒤에도 남아 트리거가 첫 호출만 실패시키는지, (3) 다른 마켓 행은 처음부터 통과하는지를 본다.
# 카오스 스택(chaos-*), 실험 스택(exchange-pml), 보존 볼륨은 건드리지 않는다.
# 사용: bash s7-oneshot-probe.sh > s7-oneshot-probe.log 2>&1
set -euo pipefail
export MSYS_NO_PATHCONV=1
IMAGE=postgres:17-alpine
NAME="pml-probe-s7os-${RANDOM}${RANDOM}"
cleanup() { docker stop "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT INT TERM
docker run -d --rm --name "$NAME" -e POSTGRES_PASSWORD=probe -e POSTGRES_DB=probe "$IMAGE" >/dev/null
until docker logs "$NAME" 2>&1 | grep -q 'PostgreSQL init process complete'; do sleep 1; done
until docker exec "$NAME" pg_isready -U postgres -d probe >/dev/null 2>&1; do sleep 1; done
sleep 1
q() { docker exec "$NAME" psql -X -U postgres -d probe -At -v VERBOSITY=verbose -c "$1" 2>&1 | sed -E 's/^psql:[^E]*//'; }
echo "start $(date -u +%Y-%m-%dT%H:%M:%SZ) container=$NAME image=$IMAGE"
q 'SELECT version()'
docker exec -i "$NAME" psql -X -U postgres -d probe -q -v ON_ERROR_STOP=1 <<'SQL'
CREATE TABLE trades (id BIGINT PRIMARY KEY, market_id SMALLINT NOT NULL);
CREATE SEQUENCE s7_fatal_once;
CREATE FUNCTION s7_fatal_once() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.market_id = 2 AND nextval('s7_fatal_once') = 1 THEN
        RAISE EXCEPTION 'S7 Q10 한 번 치명 주입(market %)', NEW.market_id USING ERRCODE = 'S7F01';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_s7_fatal_once BEFORE INSERT ON trades FOR EACH ROW EXECUTE FUNCTION s7_fatal_once();
SQL
echo "== P7-9 첫 KRW-ETH(마켓 2) 행은 S7F01 로 실패하고, 롤백 뒤에도 시퀀스가 남아 두 번째는 통과한다. 마켓 1 은 처음부터 통과한다"
echo "market 1 first : $(q "INSERT INTO trades VALUES (1, 1)")"
echo "market 2 first : $(q "INSERT INTO trades VALUES (2, 2)" | grep -E 'ERROR|INSERT' | head -1)"
echo "sequence after rollback: $(q "SELECT last_value || ' called=' || is_called FROM s7_fatal_once")"
echo "market 2 second: $(q "INSERT INTO trades VALUES (3, 2)")"
echo "rows: $(q "SELECT string_agg(id || ':' || market_id, ' ' ORDER BY id) FROM trades")"
q "DROP TRIGGER trg_s7_fatal_once ON trades" >/dev/null
echo "trigger dropped: $(q "SELECT count(*) FROM pg_trigger WHERE tgname = 'trg_s7_fatal_once'")"
echo "end $(date -u +%Y-%m-%dT%H:%M:%SZ)"
