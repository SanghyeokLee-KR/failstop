#!/usr/bin/env bash
# ADR 0034 S7 설계 3절 C7 의 실행기(Git Bash). compose 오버레이 셋(기본, 중계, P3 초안)을 겹친 설정을 docker compose config 로
# 해석만 한다. 컨테이너·망·볼륨을 만들지 않는다. 비밀 파일 대신 가짜 값의 env 파일(임시 폴더)을 쓰고, 해석 결과는 확인할
# 필드만 뽑아 적는다. 프로젝트 이름은 pml-probe-s7cfg 로 준다(해석만 하므로 어떤 스택에도 닿지 않는다).
# 사용: bash compose-s7-config.sh > compose-s7-config.log 2>&1  (검토 대응 판은 compose-s7-config-r2.log)
set -euo pipefail
export MSYS_NO_PATHCONV=1
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
to_host() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }
cat > "$WORK/fake.env" <<'ENV'
CHAOS_APP_IMAGE=exchange-chaos-app:pml
CHAOS_MASTER_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
CHAOS_RELAY_PASSCODE=fake-not-a-secret
CHAOS_LEASE_TTL=6s
CHAOS_LEASE_TICK=2s
ENV
H="$ROOT/harness"
render() { # render <이름> <파일...>
  local name="$1"; shift
  local args=()
  for f in "$@"; do args+=(-f "$(to_host "$f")"); done
  docker compose -p pml-probe-s7cfg "${args[@]}" --env-file "$(to_host "$WORK/fake.env")" config --format json > "$WORK/$name.json"
  python - "$(to_host "$WORK/$name.json")" "$name" <<'PY'
import json, sys
c = json.load(open(sys.argv[1], encoding='utf-8'))
s = c['services']
lb = [v.get('source', '').replace(chr(92), '/').split('/harness/')[-1] + ' -> ' + v.get('target', '') for v in s['chaos-lb'].get('volumes', [])]
def env(n, k):
    return s[n].get('environment', {}).get(k)
print('%s: project=%s' % (sys.argv[2], c.get('name')))
print('  chaos-lb volumes: %s' % lb)
for n in ('chaos-api-1', 'chaos-api-2', 'chaos-bots'):
    if n in s:
        print('  %s preferred=%s relay_mode=%s lease_provider=%s' % (n, env(n, 'EXCHANGE_LEADER_PARTITION_PREFERRED'),
              env(n, 'EXCHANGE_WS_RELAY_MODE'), env(n, 'EXCHANGE_LEADER_LEASE_PROVIDER')))
# 2026-09-25 검토 대응: JDBC 주소(비밀 값이 없다)를 함께 적는다. P3 오버레이가 Toxiproxy 주소를 지키고 ApplicationName 만 붙였는지 본다.
for n in ('chaos-api-1', 'chaos-api-2', 'chaos-api-3'):
    if n in s:
        print('  %s datasource_url=%s' % (n, env(n, 'SPRING_DATASOURCE_URL')))
print('  broker=%s' % ('chaos-broker' in s))
PY
}
echo "start $(date -u +%Y-%m-%dT%H:%M:%SZ) compose=$(docker compose version --short)"
render base "$H/docker-compose.chaos.yml"
render relay "$H/docker-compose.chaos.yml" "$H/docker-compose.relay.yml"
render relay_p3 "$H/docker-compose.chaos.yml" "$H/docker-compose.relay.yml" "$HERE/docker-compose.p3-draft.yml"
render p3_relay_reversed "$H/docker-compose.chaos.yml" "$HERE/docker-compose.p3-draft.yml" "$H/docker-compose.relay.yml"
CHAOS_LB_CONF=./nginx/nginx.partition.conf render relay_with_lbconf "$H/docker-compose.chaos.yml" "$H/docker-compose.relay.yml"
echo "end $(date -u +%Y-%m-%dT%H:%M:%SZ)"
