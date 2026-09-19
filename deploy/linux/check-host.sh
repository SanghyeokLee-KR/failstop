#!/usr/bin/env bash
set -uo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
mode=${1:---inspect}
[[ $# -le 1 && ( $mode == --inspect || $mode == --verify ) ]] || { printf 'Usage: check-host.sh --inspect|--verify\n' >&2; exit 1; }
failed=0
check() {
    local label=$1
    shift
    printf '\n[%s]\n' "$label"
    if "$@"; then printf 'status=OK\n'; else printf 'status=ERROR\n'; failed=1; fi
}
printf 'collected_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
check os sh -c '. /etc/os-release; printf "%s %s\n" "$ID" "$VERSION_ID"; uname -m; cat /proc/1/comm'
check mounts findmnt -r -o TARGET,SOURCE,FSTYPE,OPTIONS -t ext4,xfs,btrfs
check disk_bytes df -h -l -x tmpfs -x devtmpfs
check disk_inodes df -i -l -x tmpfs -x devtmpfs
check block_devices lsblk -o NAME,TYPE,SIZE,FSTYPE,MOUNTPOINTS
check memory free -h
check io iostat -dx 1 2
check network ip -brief address
check route ip route show
check listening ss -lnt
check ssh_service systemctl is-active failstop-ssh.service
check diagnostic_timer systemctl is-active failstop-host-check.timer

if [[ $mode == --verify ]]; then
    if [[ $(id -u) != 0 ]]; then printf 'verify requires root\n' >&2; exit 1; fi
    check ssh_syntax /usr/sbin/sshd -t -f /etc/failstop/sshd_config
    check ssh_enabled systemctl is-enabled failstop-ssh.service
    check timer_enabled systemctl is-enabled failstop-host-check.timer
    check journal_storage test -d /var/log/journal
    check journal_usage journalctl --disk-usage
    check logrotate logrotate --debug /etc/logrotate.d/failstop
    if [[ -f /etc/failstop/operator ]]; then
        operator=$(cat /etc/failstop/operator)
        check operator id "$operator"
        check operator_home stat -c '%U %G %a %n' "/home/$operator"
        check operator_sudo sudo -l -U "$operator"
        for group in $(id -nG "$operator"); do
            case "$group" in root|sudo|wheel|docker|lxd|incus-admin|adm|systemd-journal)
                printf 'privileged_operator_group=%s status=ERROR\n' "$group"; failed=1 ;;
            esac
        done
        # 0600 시험 파일이 실제로 읽히지 않는지 확인한다. 운영 비밀 값은 열지 않는다.
        probe=$(mktemp /etc/failstop/.permission-probe.XXXXXX)
        chmod 0600 "$probe"
        if runuser -u "$operator" -- test -r "$probe"; then
            printf 'secret_read_denied=ERROR\n'; failed=1
        else
            printf 'secret_read_denied=OK\n'
        fi
        rm -f -- "$probe"
        if sudo -n -u "$operator" sudo -n /usr/bin/true 2>/dev/null; then
            printf 'arbitrary_sudo_denied=ERROR\n'; failed=1
        else
            printf 'arbitrary_sudo_denied=OK\n'
        fi
    else
        printf 'operator=UNCONFIGURED status=ERROR\n'; failed=1
    fi
    check service_account getent passwd failstop
    if [[ $(getent passwd failstop | cut -d: -f7) != */nologin ]]; then
        printf 'service_account_login=ERROR\n'; failed=1
    fi
    if [[ -f /etc/failstop/firewall.nft ]]; then
        check firewall_active systemctl is-active failstop-firewall.service
        check firewall_rules nft list table inet failstop
    else
        printf 'firewall=UNCONFIGURED status=ERROR\n'; failed=1
    fi
    for env_file in /etc/failstop/*.env; do
        [[ -f $env_file ]] || continue
        check env_permissions stat -c '%U %G %a %n' "$env_file"
        expected=0:0:600
        # The reporting service is an unprivileged process with its own read-only group.
        # Only its exact environment filename may use the documented 0640 permission.
        if [[ $env_file == /etc/failstop/reporting.env ]]; then
            report_gid=$(getent group failstop-report | cut -d: -f3)
            expected="0:${report_gid}:640"
        fi
        if [[ -L $env_file || $(stat -c '%u:%g:%a' "$env_file") != "$expected" ]]; then
            printf 'env_permissions=ERROR\n'; failed=1
        fi
    done
fi
printf '\ncollection_success=%s\n' "$((1 - failed))"
exit "$failed"
