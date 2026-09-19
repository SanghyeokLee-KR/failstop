#!/usr/bin/env bash
set -euo pipefail
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$here/common.sh"
mode=plan
role=
admin_cidr=
monitor_cidrs=()
health_cidrs=()
client_cidr=
replica_cidr=
ssh_port=2222
api_port=8080
management_port=8081
db_port=5432
exporter_port=9100
grafana_port=53001
prometheus_port=59090
alertmanager_port=59093
reporter_port=9088
postgres_exporter_port=9187
while (($#)); do
    case "$1" in
        --plan|--apply) mode=${1#--}; shift ;;
        --role) role=${2:?}; shift 2 ;;
        --admin-cidr) admin_cidr=${2:?}; shift 2 ;;
        --monitor-cidr) monitor_cidrs+=("${2:?}"); shift 2 ;;
        --health-cidr) health_cidrs+=("${2:?}"); shift 2 ;;
        --client-cidr) client_cidr=${2:?}; shift 2 ;;
        --replica-cidr) replica_cidr=${2:?}; shift 2 ;;
        --ssh-port) ssh_port=${2:?}; shift 2 ;;
        --api-port) api_port=${2:?}; shift 2 ;;
        --management-port) management_port=${2:?}; shift 2 ;;
        --db-port) db_port=${2:?}; shift 2 ;;
        --exporter-port) exporter_port=${2:?}; shift 2 ;;
        --grafana-port) grafana_port=${2:?}; shift 2 ;;
        --prometheus-port) prometheus_port=${2:?}; shift 2 ;;
        --alertmanager-port) alertmanager_port=${2:?}; shift 2 ;;
        --reporter-port) reporter_port=${2:?}; shift 2 ;;
        --postgres-exporter-port) postgres_exporter_port=${2:?}; shift 2 ;;
        *) die "Unknown argument: $1" ;;
    esac
done
validate_role "$role"
validate_cidr "$admin_cidr"
(( ${#monitor_cidrs[@]} > 0 )) || die 'At least one monitor CIDR is required.'
for cidr in "${monitor_cidrs[@]}" "${health_cidrs[@]}"; do validate_cidr "$cidr"; done
monitor_cidr="${monitor_cidrs[*]}"
for port in "$ssh_port" "$api_port" "$management_port" "$db_port" "$exporter_port" "$reporter_port" "$postgres_exporter_port"; do validate_port "$port"; done
[[ $ssh_port != 22 ]] || die 'This rule set preserves the existing port 22 management path.'
[[ $(printf '%s\n' "$ssh_port" "$api_port" "$management_port" "$db_port" "$exporter_port" "$reporter_port" "$postgres_exporter_port" | sort -u | wc -l) == 7 ]] || die 'Service ports must not overlap.'
if [[ $role == bot || $role == observability ]]; then
    for port in "$grafana_port" "$prometheus_port" "$alertmanager_port"; do
        validate_port "$port"
    done
    [[ $(printf '%s\n' "$ssh_port" "$api_port" "$exporter_port" "$reporter_port" "$grafana_port" "$prometheus_port" "$alertmanager_port" | sort -u | wc -l) == 7 ]] || die 'A role port overlaps an observability port.'
fi
declare -A sources
sources[$ssh_port]=$admin_cidr
sources[$exporter_port]=$monitor_cidr
case "$role" in
    app|reader)
        validate_cidr "$client_cidr"
        sources[$api_port]=$client_cidr
        sources[$management_port]="$monitor_cidr ${health_cidrs[*]}" ;;
    data|standby)
        validate_cidr "$client_cidr"
        validate_cidr "$replica_cidr"
        sources[$db_port]="$client_cidr $replica_cidr"
        sources[$postgres_exporter_port]=$monitor_cidr ;;
    bot|observability)
        # 봇과 관측 도구는 같은 운영 노드에 배치한다.
        sources[$api_port]=$monitor_cidr
        sources[$reporter_port]=$monitor_cidr
        sources[$grafana_port]=$admin_cidr
        sources[$prometheus_port]=$admin_cidr
        sources[$alertmanager_port]=$admin_cidr ;;
esac
ports=$(printf '%s\n' "${!sources[@]}" | sort -n)

render_rules() {
    local port cidr
    # add와 flush를 같은 nft 트랜잭션으로 적용해 갱신 중 차단 공백을 만들지 않는다.
    # 다른 테이블과 호스트 기본 정책은 건드리지 않는다.
    printf 'add table inet failstop\nflush table inet failstop\n'
    printf 'table inet failstop {\n chain input {\n  type filter hook input priority -10; policy accept;\n'
    printf '  iifname "lo" accept\n  ct state established,related accept\n'
    for port in $ports; do
        for cidr in ${sources[$port]}; do
            printf '  ip saddr %s tcp dport %s counter accept\n' "$cidr" "$port"
        done
        printf '  tcp dport %s counter drop\n' "$port"
    done
    printf ' }\n chain forward {\n  type filter hook forward priority -10; policy accept;\n'
    printf '  ct state established,related accept\n'
    for port in $ports; do
        for cidr in ${sources[$port]}; do
            printf '  ct status dnat ct direction original ip saddr %s meta l4proto tcp ct original proto-dst %s counter accept\n' "$cidr" "$port"
        done
        # IP 계열을 제한하지 않아 같은 포트의 IPv6 DNAT 우회도 차단한다.
        printf '  ct status dnat ct direction original meta l4proto tcp ct original proto-dst %s counter drop\n' "$port"
    done
    printf ' }\n}\n'
}
if [[ $mode == plan ]]; then
    render_rules
    exit 0
fi
require_root
require_systemd
command -v nft >/dev/null || die 'Install nftables first.'
[[ -f /etc/systemd/system/failstop-firewall.service ]] || die 'Run bootstrap-host.sh first.'
temporary=$(mktemp)
trap 'rm -f -- "$temporary"' EXIT
render_rules > "$temporary"
nft --check --file "$temporary"
install -o root -g root -m 0600 "$temporary" /etc/failstop/firewall.nft
systemctl daemon-reload
systemctl enable failstop-firewall.service
systemctl restart failstop-firewall.service
printf 'Applied role=%s. Test permitted and rejected traffic from separate source addresses.\n' "$role"
