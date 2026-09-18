#!/usr/bin/env bash
set -euo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
[[ $# == 2 && $(id -u) == 0 ]] || { printf 'Usage: sudo failstop-control status|restart ROLE\n' >&2; exit 1; }
action=$1
role=$2
case "$role" in app|reader|data|standby|bot|observability) ;; *) exit 1 ;; esac
case "$action" in
    status) exec systemctl --no-pager show "failstop@$role.service" --property=ActiveState,SubState,Result,ExecMainStatus ;;
    restart) exec systemctl restart "failstop@$role.service" ;;
    *) exit 1 ;;
esac
