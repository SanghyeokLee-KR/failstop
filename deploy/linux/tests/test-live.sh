#!/usr/bin/env bash
# 이미 bootstrap한 로컬 WSL 실습 환경에서 접속·거절·재시작을 확인한다.
set -euo pipefail
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
source "$here/common.sh"
apply=false
private_key=
network_test=false
while (($#)); do
    case "$1" in
        --apply) apply=true; shift ;;
        --private-key) private_key=${2:?}; shift 2 ;;
        --network-test) network_test=true; shift ;;
        *) die "Unknown argument: $1" ;;
    esac
done
$apply || die 'Use --apply to test the configured local lab.'
require_root
require_systemd
grep -qi microsoft /proc/sys/kernel/osrelease || die 'This live test is limited to the local WSL lab.'
[[ -f $private_key && ! -L $private_key ]] || die 'Provide the operator private-key path.'
require_protected_file /etc/failstop/sshd_config
grep -qx 'ListenAddress 127.0.0.1' /etc/failstop/sshd_config || die 'The lab SSH listener must be loopback only.'
grep -qx 'Port 2222' /etc/failstop/sshd_config || die 'The lab SSH port must be 2222.'
operator=$(cat /etc/failstop/operator)
validate_user "$operator"
temporary=$(mktemp -d)
probe=$(mktemp /etc/failstop/.live-permission-probe.XXXXXX)
trap 'rm -rf -- "$temporary"; rm -f -- "$probe"' EXIT
printf 'permission-test\n' > "$probe"
chmod 0600 "$probe"
read -r key_type key_data _ < /etc/ssh/ssh_host_ed25519_key.pub
printf '[127.0.0.1]:2222 %s %s\n' "$key_type" "$key_data" > "$temporary/known_hosts"
chmod 0600 "$temporary/known_hosts"
export LC_ALL=C
ssh_options=(-F /dev/null -p 2222 -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -o "UserKnownHostsFile=$temporary/known_hosts" -o GlobalKnownHostsFile=/dev/null -o ConnectTimeout=5)
operator_ssh=(ssh "${ssh_options[@]}" -i "$private_key" "$operator@127.0.0.1")
[[ $("${operator_ssh[@]}" id -un) == "$operator" ]] || die 'Operator key login failed.'
printf 'PASS operator_key_login\n'
deny_authentication() {
    local label=$1 result=0
    shift
    "$@" > "$temporary/auth.stdout" 2> "$temporary/auth.stderr" || result=$?
    [[ $result == 255 ]] && grep -q 'Permission denied' "$temporary/auth.stderr" || die "Expected authentication denial: $label"
    printf 'PASS %s\n' "$label"
}
deny_authentication root_login ssh "${ssh_options[@]}" -i "$private_key" root@127.0.0.1 true
ssh-keygen -q -t ed25519 -N '' -f "$temporary/wrong-key"
deny_authentication wrong_key ssh "${ssh_options[@]}" -i "$temporary/wrong-key" "$operator@127.0.0.1" true
deny_authentication password_authentication ssh "${ssh_options[@]}" -o PubkeyAuthentication=no -o PreferredAuthentications=password "$operator@127.0.0.1" true
sshd -T -f /etc/failstop/sshd_config > "$temporary/sshd-effective"
grep -qx 'passwordauthentication no' "$temporary/sshd-effective"
grep -qx 'authenticationmethods publickey' "$temporary/sshd-effective"
grep -qx 'permitrootlogin no' "$temporary/sshd-effective"

"${operator_ssh[@]}" sudo -n /usr/local/sbin/failstop-control status app > "$temporary/allowed-status"
grep -q '^ActiveState=' "$temporary/allowed-status" || die 'Authorized status command did not run.'
printf 'PASS allowed_service_status\n'
result=0
"${operator_ssh[@]}" sudo -n /usr/bin/true > "$temporary/sudo.stdout" 2> "$temporary/sudo.stderr" || result=$?
[[ $result != 0 && $result != 255 ]] || die 'Arbitrary sudo was accepted or the SSH connection failed.'
printf 'PASS arbitrary_sudo_denied\n'
result=0
"${operator_ssh[@]}" cat "$probe" > "$temporary/read.stdout" 2> "$temporary/read.stderr" || result=$?
[[ $result != 0 && $result != 255 && ! -s $temporary/read.stdout ]] || die 'Root-only file was readable or the SSH connection failed.'
grep -q 'Permission denied' "$temporary/read.stderr" || die 'Root-only file denial was not demonstrated.'
printf 'PASS root_file_read_denied\n'
[[ $("${operator_ssh[@]}" id -un) == "$operator" ]]

bash "$here/firewall-host.sh" --apply --role app --admin-cidr 127.0.0.1/32 \
    --monitor-cidr 127.0.0.1/32 --client-cidr 127.0.0.1/32
systemctl restart failstop-ssh.service failstop-host-check.timer
systemctl start failstop-host-check.service
for unit in failstop-ssh.service failstop-host-check.timer failstop-firewall.service; do
    systemctl is-enabled --quiet "$unit"
    systemctl is-active --quiet "$unit"
done
[[ $("${operator_ssh[@]}" id -un) == "$operator" ]] || die 'Operator login failed after restart.'
[[ $(systemctl show failstop-host-check.service --property=ExecMainStatus --value) == 0 ]]
[[ $(tail -n 1 /var/log/failstop/host-check.log) == collection_success=1 ]]
journalctl --flush
[[ -d /var/log/journal ]]
logrotate --debug /etc/logrotate.d/failstop > "$temporary/logrotate.stdout" 2> "$temporary/logrotate.stderr"
printf 'PASS enabled_services_restart_timer_and_logs\n'
bash "$here/check-host.sh" --verify > "$temporary/host-verify"
printf 'PASS root_host_verification\n'
if $network_test; then
    bash "$here/tests/test-firewall-network.sh" --apply
else
    printf 'NOT RUN isolated_network_denial (use --network-test)\n'
fi
printf 'PASS local WSL live checks; AWS and host reboot validation remain separate.\n'
