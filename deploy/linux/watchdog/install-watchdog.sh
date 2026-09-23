#!/usr/bin/env bash
set -euo pipefail

# 관측 워치독을 설치하거나 해제한다. 설치는 timer를 켜지 않으며, README의 확인 뒤 운영자가 enable로 켠다.
usage() {
    printf '%s\n' '사용: sudo bash install-watchdog.sh install app-1|bot-1' '      sudo bash install-watchdog.sh enable' \
        '      sudo bash install-watchdog.sh remove' >&2
    exit 64
}

here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
test "$#" -ge 1 || usage
if [ "$(id -u)" -ne 0 ]; then
    printf '%s\n' 'root 권한이 필요하다.' >&2
    exit 1
fi

# 파일이 심볼릭 링크가 아닌 일반 파일이고 권한·소유자가 기대값 중 하나인지 확인한다.
require_file() {
    local path=$1 actual
    shift
    if [ ! -f "$path" ] || [ -L "$path" ]; then
        printf '%s\n' "필요한 파일이 없거나 링크다: $path" >&2
        exit 1
    fi
    actual=$(stat -c '%a:%U:%G' "$path")
    for expected in "$@"; do
        if [ "$actual" = "$expected" ]; then return 0; fi
    done
    printf '%s\n' "권한·소유자가 다르다: $path ($actual, 기대 $*)" >&2
    exit 1
}

case "$1" in
install)
    test "$#" -eq 2 || usage
    profile=$2
    case "$profile" in app-1|bot-1) ;; *) usage ;; esac
    python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 9) else 1)'
    # 웹훅 URL은 운영자가 먼저 root 0600으로 만든다. 이 스크립트는 값을 읽거나 출력하지 않는다.
    require_file /etc/failstop-watchdog/teams-webhook '600:root:root'
    if [ "$profile" = bot-1 ]; then
        require_file /etc/failstop/reporter-token '600:root:root' '640:root:failstop-report'
    fi
    if ! id failstop-watchdog >/dev/null 2>&1; then
        useradd --system --user-group --no-create-home --home-dir /var/lib/failstop-watchdog \
            --shell /sbin/nologin failstop-watchdog
    fi
    test "$(id -u failstop-watchdog)" -ne 0
    install -d -o root -g root -m 0755 /opt/failstop-watchdog /etc/failstop-watchdog
    install -d -o failstop-watchdog -g failstop-watchdog -m 0755 /var/lib/failstop-watchdog \
        /var/lib/failstop-watchdog/textfile
    install -o root -g root -m 0644 "$here/failstop_watchdog.py" /opt/failstop-watchdog/failstop_watchdog.py
    # 운영자가 조정한 max_dead 등의 값을 덮어쓰지 않는다.
    if [ -e /etc/failstop-watchdog/config.json ]; then
        install -o root -g root -m 0644 "$here/config.$profile.json" /etc/failstop-watchdog/config.json.new
        printf '%s\n' '기존 config.json을 유지했다. 새 기본값은 config.json.new에 두었다.'
    else
        install -o root -g root -m 0644 "$here/config.$profile.json" /etc/failstop-watchdog/config.json
    fi
    install -o root -g root -m 0644 "$here/failstop-watchdog.service" "$here/failstop-watchdog.timer" \
        /etc/systemd/system/
    if [ "$profile" = bot-1 ]; then
        install -d -o root -g root -m 0755 /etc/systemd/system/failstop-watchdog.service.d
        install -o root -g root -m 0644 "$here/bot-1-credentials.conf" \
            /etc/systemd/system/failstop-watchdog.service.d/bot-1-credentials.conf
    fi
    systemd-analyze verify /etc/systemd/system/failstop-watchdog.service /etc/systemd/system/failstop-watchdog.timer
    systemctl daemon-reload
    # 점검 결과만 출력하고 상태 파일과 Teams는 건드리지 않는다. 종료 코드 3은 일부 점검 실패다.
    python3 /opt/failstop-watchdog/failstop_watchdog.py check --config /etc/failstop-watchdog/config.json \
        || test "$?" -eq 3
    printf '%s\n' '설치 확인 완료. timer는 켜지 않았다. README의 확인 절차 뒤 sudo bash install-watchdog.sh enable'
    ;;
enable)
    test "$#" -eq 1 || usage
    require_file /opt/failstop-watchdog/failstop_watchdog.py '644:root:root'
    require_file /etc/failstop-watchdog/config.json '644:root:root'
    # 켜는 순간 실패하고 있는 점검은 세 번째 실행, 약 2분 뒤에 실제 Teams 이상 알림이 된다.
    # 그래서 활성 점검이 모두 통과할 때만 켠다. 의도한 실패는 운영자가 config.json에서 그 점검을 끈다.
    if ! python3 /opt/failstop-watchdog/failstop_watchdog.py check --config /etc/failstop-watchdog/config.json; then
        printf '%s\n' '실패한 점검이 있어 timer를 켜지 않았다. 원인을 고치거나, 의도한 상태면 /etc/failstop-watchdog/config.json에서' \
            '그 점검의 enabled를 false로 바꾸고 note에 이유를 적은 뒤 다시 실행한다.' >&2
        exit 1
    fi
    # 서비스 계정과 LoadCredential 사본으로 한 번 실행해 비밀 전달과 상태 파일 쓰기를 확인한다.
    if ! systemctl start failstop-watchdog.service; then
        printf '%s\n' '서비스 실행이 실패해 timer를 켜지 않았다. journalctl -u failstop-watchdog.service -n 20 --no-pager로 원인을 확인한다.' >&2
        exit 1
    fi
    systemctl enable --now failstop-watchdog.timer
    printf '%s\n' 'timer를 켰다. systemctl list-timers failstop-watchdog.timer --no-pager로 다음 실행 시각을 확인한다.'
    ;;
remove)
    test "$#" -eq 1 || usage
    # 설치하지 않은 호스트에서도 같은 명령으로 정리할 수 있게 없는 unit의 오류는 넘긴다.
    systemctl disable --now failstop-watchdog.timer || true
    systemctl stop failstop-watchdog.service || true
    rm -f /etc/systemd/system/failstop-watchdog.service /etc/systemd/system/failstop-watchdog.timer \
        /etc/systemd/system/failstop-watchdog.service.d/bot-1-credentials.conf \
        /var/lib/failstop-watchdog/textfile/failstop_watchdog.prom
    if [ -d /etc/systemd/system/failstop-watchdog.service.d ]; then
        # 운영자가 따로 둔 override가 있으면 폴더를 남긴다.
        rmdir --ignore-fail-on-non-empty /etc/systemd/system/failstop-watchdog.service.d
    fi
    systemctl daemon-reload
    printf '%s\n' '해제 완료. 웹훅·설정·상태 파일은 남겼다: /etc/failstop-watchdog, /var/lib/failstop-watchdog'
    ;;
*)
    usage
    ;;
esac
