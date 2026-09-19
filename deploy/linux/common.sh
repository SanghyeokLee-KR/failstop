#!/usr/bin/env bash
# 설치 스크립트가 같은 입력 검사를 사용한다. 환경 파일은 셸로 실행하지 않는다.
set -euo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
require_root() { [[ $(id -u) == 0 ]] || die 'Run this action as root.'; }
require_systemd() { [[ $(cat /proc/1/comm) == systemd ]] || die 'PID 1 must be systemd.'; }
validate_user() { [[ $1 =~ ^[a-z_][a-z0-9_-]{0,30}$ && $1 != root ]] || die 'Invalid operator name.'; }
validate_port() { [[ $1 =~ ^[1-9][0-9]{0,4}$ ]] && (( 10#$1 <= 65535 )) || die 'Invalid port.'; }
validate_role() {
    case "$1" in app|reader|data|standby|bot|observability) ;; *) die 'Unknown node role.' ;; esac
}
validate_ipv4() {
    local part
    [[ $1 =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || die 'An IPv4 address is required.'
    local -a parts
    IFS=. read -r -a parts <<< "$1"
    for part in "${parts[@]}"; do
        [[ $part == 0 || $part != 0* ]] && (( 10#$part <= 255 )) || die 'Invalid IPv4 address.'
    done
}
validate_cidr() {
    [[ $1 == */* ]] || die 'An explicit IPv4 CIDR is required.'
    validate_ipv4 "${1%/*}"
    local prefix=${1#*/}
    [[ $prefix =~ ^([1-9]|[12][0-9]|3[0-2])$ ]] || die 'CIDR prefix must be 1 through 32; /0 is forbidden.'
}
require_protected_file() {
    local file=$1 mode
    [[ -f $file && ! -L $file ]] || die "Missing regular file: $file"
    [[ $(stat -c %u "$file") == 0 ]] || die "File must be owned by root: $file"
    mode=$(stat -c %a "$file")
    (( (8#$mode & 8#022) == 0 )) || die "File is writable by another account: $file"
}
