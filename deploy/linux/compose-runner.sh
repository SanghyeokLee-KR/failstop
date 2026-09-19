#!/usr/bin/env bash
set -euo pipefail
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$here/common.sh"
require_root
[[ $# == 2 ]] || die 'Usage: compose-runner.sh start|stop|check|status ROLE'
action=$1
role=$2
validate_role "$role"
case "$action" in start|stop|check|status) ;; *) die 'Unknown action.' ;; esac
case "$role" in
    app) file=compose.node-app.yml; service=app ;;
    reader) file=compose.node-read.yml; service=read ;;
    data) file=compose.node-data.yml; service=db ;;
    standby) file=compose.node-data.yml; service=db-standby ;;
    bot) file=compose.node-bot.yml; service=bot ;;
    observability) file=compose.node-observability.yml; service= ;;
esac
env_file="/etc/failstop/$role.env"
require_protected_file "$env_file"
[[ $(stat -c %a "$env_file") == 600 ]] || die 'Role environment must have mode 0600.'
require_protected_file "/opt/failstop/deploy/$file"
[[ $(stat -c %u /opt/failstop/deploy) == 0 ]] || die 'Deployment directory must be owned by root.'
[[ -z $(find /opt/failstop/deploy -type l -print -quit) ]] || die 'Deployment symlinks are not allowed.'
[[ -z $(find /opt/failstop/deploy -perm /022 -print -quit) ]] || die 'Deployment is writable by another account.'
if [[ $role == observability ]]; then
    [[ -d /opt/failstop/harness ]] || die 'Observability assets are missing.'
    [[ -z $(find /opt/failstop/harness -type l -print -quit) ]] || die 'Observability asset symlinks are not allowed.'
    [[ -z $(find /opt/failstop/harness -perm /022 -print -quit) ]] || die 'Observability assets are writable by another account.'
fi
command -v docker >/dev/null || die 'Install Docker Engine and Compose before enabling a role.'
docker compose version >/dev/null
if [[ ( $action == start || $action == check ) && $role != observability ]]; then
    require_protected_file /opt/failstop/deploy/check-config.py
    check_role=$role
    [[ $role != reader ]] || check_role=read
    validation=(python3 /opt/failstop/deploy/check-config.py --env "$env_file" --role "$check_role")
    image_mode="/etc/failstop/$role.image-mode"
    if [[ -f $image_mode ]]; then
        require_protected_file "$image_mode"
        case "$(cat "$image_mode")" in
            local) validation+=(--local-image) ;;
            production) ;;
            *) die 'Invalid image verification mode.' ;;
        esac
    fi
    "${validation[@]}"
fi
compose=(docker compose --project-name "failstop-$role" --project-directory /opt/failstop/deploy --env-file "$env_file" -f "/opt/failstop/deploy/$file")
[[ $role != standby ]] || compose+=(--profile standby)
services=()
[[ -z $service ]] || services+=("$service")

# dotenv 파일을 source하지 않고 Compose에 전달한다. config 본문은 비밀 값이므로 출력하지 않는다.
case "$action" in
    check) "${compose[@]}" config --quiet ;;
    start) "${compose[@]}" config --quiet; exec "${compose[@]}" up -d --wait --wait-timeout 180 "${services[@]}" ;;
    stop) exec "${compose[@]}" stop --timeout 60 "${services[@]}" ;;
    status) exec "${compose[@]}" ps --format json "${services[@]}" ;;
esac
