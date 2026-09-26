#!/usr/bin/env bash
# ADR 0034 S7 설계 3절 P7-8 의 실행기(Git Bash). 수수료 행 결합 실험(Q8)의 양성 축 도구를 확인한다. 폐기용 postgres:17-alpine
# 컨테이너 하나(--rm, 호스트 포트 없음, 이름 pml-probe-s7rl-*)에서 (1) pgrowlocks 확장이 이 이미지에 있고 행을 쥔 세션의 pid 와
# application_name 을 돌려주는지, (2) 트랜잭션 도중 클라이언트를 멈추면(SIGSTOP, docker pause 흉내) 그 행이 잠긴 채 남고 다른 행은
# 막히지 않는지, (3) idle_in_transaction_session_timeout 이 멈춘 클라이언트의 세션을 끊어 잠금을 푸는지를 본다. 시한은 확인을 빨리
# 끝내려고 5초로 준다(카오스 스택은 10초). 카오스 스택(chaos-*), 실험 스택(exchange-pml), 보존 볼륨은 건드리지 않는다.
# 사용: bash s7-rowlock-probe.sh > s7-rowlock-probe.log 2>&1
set -euo pipefail
export MSYS_NO_PATHCONV=1
IMAGE=postgres:17-alpine
NAME="pml-probe-s7rl-${RANDOM}${RANDOM}"
cleanup() { docker stop "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT INT TERM
docker run -d --rm --name "$NAME" -e POSTGRES_PASSWORD=probe -e POSTGRES_DB=probe "$IMAGE" \
    -c idle_in_transaction_session_timeout=5s >/dev/null
until docker logs "$NAME" 2>&1 | grep -q 'PostgreSQL init process complete'; do sleep 1; done
until docker exec "$NAME" pg_isready -U postgres -d probe >/dev/null 2>&1; do sleep 1; done
sleep 1
q() { docker exec "$NAME" psql -X -U postgres -d probe -At -v ON_ERROR_STOP=1 -c "$1" 2>&1; }
now() { date -u +%H:%M:%S.%N | cut -c1-12; }
echo "start $(date -u +%Y-%m-%dT%H:%M:%SZ) container=$NAME image=$IMAGE"
q 'SELECT version()'
q "SHOW idle_in_transaction_session_timeout"
q "CREATE EXTENSION pgrowlocks" && q "SELECT extversion FROM pg_extension WHERE extname = 'pgrowlocks'"
q "CREATE TABLE balances (account_id BIGINT PRIMARY KEY, balance NUMERIC NOT NULL); INSERT INTO balances SELECT g, 0 FROM generate_series(1, 3) g"
LOCKS="SELECT coalesce(string_agg(r.locked_row::text || ' ' || array_to_string(r.modes, '+') || ' app=' || coalesce(a.application_name, '?') || ' state=' || coalesce(a.state, '?'), '; '), '(none)') FROM pgrowlocks('balances') r LEFT JOIN LATERAL unnest(r.pids) p(pid) ON true LEFT JOIN pg_stat_activity a ON a.pid = p.pid"

echo
echo "== P7-8a 세션 A(application_name=chaos-api-1)가 행 1 을 UPDATE 하고 커밋 전에 머문다"
docker exec -d "$NAME" sh -c "(echo \"SET application_name = 'chaos-api-1'; BEGIN; UPDATE balances SET balance = balance + 1 WHERE account_id = 1; SELECT 'held';\"; sleep 30; echo 'COMMIT;') | psql -X -U postgres -d probe > /tmp/a.log 2>&1"
sleep 1
IDLE0=$(q "SELECT extract(epoch FROM state_change) FROM pg_stat_activity WHERE application_name = 'chaos-api-1'")
echo "$(now) pgrowlocks: $(q "$LOCKS")"

echo
echo "== P7-8b 세션 A 의 psql 을 SIGSTOP 으로 멈춘다(docker pause 흉내). 행 1 은 잠긴 채이고 행 2 는 곧바로 바뀐다"
PID=$(docker exec "$NAME" sh -c "pidof psql" | tr ' ' '\n' | head -1)
docker exec "$NAME" kill -STOP "$PID"
T0=$(date +%s.%N)
echo "$(now) stopped psql pid=$PID"
echo "$(now) pgrowlocks: $(q "$LOCKS")"
echo "$(now) row 2 update: $(q "SET lock_timeout = '1s'; UPDATE balances SET balance = balance + 1 WHERE account_id = 2")"
echo "$(now) row 1 update (lock_timeout 1s): $(q "SET lock_timeout = '1s'; UPDATE balances SET balance = balance + 1 WHERE account_id = 1" | grep -E '^(ERROR|UPDATE)' | head -1)"

echo
echo "== P7-8c 시한(5초)이 멈춘 세션을 끊어 행 1 잠금이 풀리는 시각(세션 A 가 idle in transaction 이 된 시각부터 잰다)"
for i in $(seq 1 20); do
    L=$(q "$LOCKS")
    if [ "$L" = "(none)" ]; then
        echo "$(now) pgrowlocks empty after $(awk -v a="$T0" -v b="$(date +%s.%N)" 'BEGIN { printf "%.1f", b - a }')s from stop, $(awk -v a="$IDLE0" -v b="$(date +%s.%N)" 'BEGIN { printf "%.1f", b - a }')s from idle-in-transaction start"
        break
    fi
    sleep 0.5
done
echo "$(now) row 1 update after release: $(q "SET lock_timeout = '1s'; UPDATE balances SET balance = balance + 1 WHERE account_id = 1")"
echo "$(now) balances: $(q "SELECT string_agg(account_id || '=' || balance, ' ' ORDER BY account_id) FROM balances")"
docker exec "$NAME" kill -CONT "$PID" 2>/dev/null || true
echo "server log:"
docker logs "$NAME" 2>&1 | grep -E 'idle-in-transaction|terminating connection' | sed -E 's/^[0-9-]+ [0-9:.]+ UTC //' || true
echo "end $(date -u +%Y-%m-%dT%H:%M:%SZ)"
