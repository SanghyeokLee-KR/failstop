#!/usr/bin/env bash
# 호스트를 바꾸지 않는 검증. 적용 결과와 패킷 차단 검증은 별도로 실행한다.
set -euo pipefail
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
temporary=$(mktemp -d)
trap 'rm -rf -- "$temporary"' EXIT
for script in "$here/"*.sh "$here/tests/"*.sh; do bash -n "$script"; done
python3 -m unittest discover -s "$here/tests" -p test_runtime_files.py
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
reject() {
    if "$@" >"$temporary/rejected.stdout" 2>"$temporary/rejected.stderr"; then fail 'Invalid input was accepted.'; fi
}
command -v ssh-keygen >/dev/null || fail 'ssh-keygen is required.'
ssh-keygen -q -t ed25519 -N '' -f "$temporary/operator"
bash "$here/bootstrap-host.sh" --plan --public-key "$temporary/operator.pub" --admin-cidr 127.0.0.1/32 > "$temporary/plan"
grep -q 'mode=plan' "$temporary/plan" || fail 'Bootstrap must default to preview.'
reject bash "$here/bootstrap-host.sh" --plan --public-key "$temporary/operator" --admin-cidr 127.0.0.1/32
reject bash "$here/bootstrap-host.sh" --plan --public-key "$temporary/operator.pub" --admin-cidr 0.0.0.0/0
reject bash "$here/bootstrap-host.sh" --plan --public-key "$temporary/operator.pub" --admin-cidr '127.0.0.1/32;id'
reject bash "$here/bootstrap-host.sh" --plan --public-key "$temporary/operator.pub" --admin-cidr 127.0.0.1/32 --ssh-port 22
reject bash "$here/bootstrap-host.sh" --plan --public-key "$temporary/operator.pub" --admin-cidr 127.0.0.1/32 --operator root
reject bash "$here/bootstrap-host.sh" --plan --public-key "$temporary/operator.pub" --admin-cidr 127.0.0.1/32 --ssh-address 999.1.1.1

for role in app reader data standby bot observability; do
    bash "$here/firewall-host.sh" --plan --role "$role" --admin-cidr 10.0.1.0/24 \
        --monitor-cidr 10.0.2.0/24 --client-cidr 10.0.3.0/24 --replica-cidr 10.0.4.0/24 > "$temporary/$role.nft"
    grep -q 'hook input' "$temporary/$role.nft" || fail "Missing host filter: $role"
    grep -q 'hook forward' "$temporary/$role.nft" || fail "Missing Docker filter: $role"
    grep -q 'ct original proto-dst' "$temporary/$role.nft" || fail "Missing original published port: $role"
    if grep -q 'flush ruleset\|policy drop\|dport 22 ' "$temporary/$role.nft"; then fail 'Unrelated host firewall policy was changed.'; fi
done
grep -q 'ip saddr 10.0.3.0/24 tcp dport 8080 counter accept' "$temporary/reader.nft" || fail 'Reader API source rule missing.'
grep -q 'ip saddr 10.0.2.0/24 tcp dport 8081 counter accept' "$temporary/reader.nft" || fail 'Reader management source rule missing.'
grep -q 'ip saddr 10.0.4.0/24 tcp dport 5432 counter accept' "$temporary/data.nft" || fail 'Replica source rule missing.'
grep -q 'ct original proto-dst 5432 counter drop' "$temporary/data.nft" || fail 'DB Docker deny rule missing.'
grep -q 'ip saddr 10.0.2.0/24 tcp dport 9187 counter accept' "$temporary/data.nft" || fail 'DB exporter source rule missing.'
grep -q 'ct original proto-dst 9187 counter drop' "$temporary/data.nft" || fail 'DB exporter deny rule missing.'
grep -q 'ip saddr 10.0.2.0/24 tcp dport 9088 counter accept' "$temporary/bot.nft" || fail 'Reporter source rule missing.'
grep -q 'ct original proto-dst 9088 counter drop' "$temporary/bot.nft" || fail 'Reporter deny rule missing.'
bash "$here/firewall-host.sh" --plan --role reader --admin-cidr 127.0.0.1/32 \
    --monitor-cidr 10.42.11.40/32 --monitor-cidr 172.30.50.0/24 --client-cidr 10.42.0.0/16 \
    --health-cidr 10.42.0.0/24 --health-cidr 10.42.1.0/24 > "$temporary/health.nft"
for source in 10.42.0.0/24 10.42.1.0/24 10.42.11.40/32; do
    grep -q "ip saddr $source tcp dport 8081 counter accept" "$temporary/health.nft" || fail 'ALB health or monitor input missing.'
    grep -q "ip saddr $source meta l4proto tcp ct original proto-dst 8081 counter accept" "$temporary/health.nft" || fail 'ALB health or monitor DNAT missing.'
done
grep -q 'ip saddr 172.30.50.0/24 tcp dport 9100 counter accept' "$temporary/health.nft" || fail 'Second monitor CIDR missing.'
if grep -q 'ip saddr 10.42.0.0/24 tcp dport 9100 counter accept' "$temporary/health.nft"; then fail 'Health CIDR must not grant host exporter access.'; fi
reject bash "$here/firewall-host.sh" --plan --role data --admin-cidr 10.0.1.0/24 --monitor-cidr 10.0.2.0/24 --client-cidr 10.0.3.0/24
reject bash "$here/firewall-host.sh" --plan --role unknown --admin-cidr 10.0.1.0/24 --monitor-cidr 10.0.2.0/24
reject bash "$here/firewall-host.sh" --plan --role bot --admin-cidr 10.0.1.0/24 --monitor-cidr 10.0.2.0/24 --exporter-port 70000
reject bash "$here/firewall-host.sh" --plan --role app --admin-cidr 10.0.1.0/24 --monitor-cidr 10.0.2.0/24 --client-cidr 10.0.3.0/24 --ssh-port 8080
reject bash "$here/operator-control.sh" status 'app;id'
reject bash "$here/operator-control.sh" shell app
reject bash "$here/operator-control.sh" status app extra
printf 'PASS: shell syntax, previews, input rejection, role routes, Docker DNAT denial, and command allowlist.\n'
