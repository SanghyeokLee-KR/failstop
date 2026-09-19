#!/usr/bin/env bash
set -euo pipefail
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$here/common.sh"
mode=plan
role=
env_file=
start=false
image_mode=production
while (($#)); do
    case "$1" in
        --plan|--apply) mode=${1#--}; shift ;;
        --role) role=${2:?}; shift 2 ;;
        --env-file) env_file=${2:?}; shift 2 ;;
        --start) start=true; shift ;;
        --local-image) image_mode=local; shift ;;
        *) die "Unknown argument: $1" ;;
    esac
done
validate_role "$role"
[[ -f $env_file && ! -L $env_file ]] || die 'Provide a regular environment file.'
printf 'mode=%s role=%s start=%s image_mode=%s env=provided (values hidden)\n' "$mode" "$role" "$start" "$image_mode"
[[ $mode == apply ]] || exit 0
require_root
require_systemd
[[ -f /etc/failstop/operator ]] || die 'Run bootstrap-host.sh first.'
deploy_source=$(dirname "$here")
install -d -o root -g root -m 0755 /opt/failstop/deploy
# AWS release와 같은 허용 목록으로 숨김 상태·env·JSON 보고·DB 덤프를 제외한다.
python3 "$here/runtime-files.py" --root "$deploy_source/.." --target /opt/failstop
if [[ $(realpath "$env_file") != "/etc/failstop/$role.env" ]]; then
    install -o root -g root -m 0600 "$env_file" "/etc/failstop/$role.env"
fi
printf '%s\n' "$image_mode" > "/etc/failstop/$role.image-mode"
chown root:root "/etc/failstop/$role.image-mode"
chmod 0600 "/etc/failstop/$role.image-mode"
/opt/failstop/linux/compose-runner.sh check "$role"
systemctl daemon-reload
systemd-analyze verify "/etc/systemd/system/failstop@.service"
systemctl enable "failstop@$role.service"
if $start; then
    systemctl restart "failstop@$role.service"
fi
printf 'Role installed. Container health and application readiness require separate checks.\n'
