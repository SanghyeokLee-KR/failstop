#!/usr/bin/env bash
# pfSense 경계형: sshd 를 host-only 주소 대신 lab-lan 주소에 바인딩한다. 관리 SSH 는 pfSense 를 지나 lab-lan 으로 들어온다.
# NIC 를 떼기 전에 host-only 로 접속해 여섯 대 모두 실행한다. 빠뜨리면 NIC 제거 뒤 SSH 가 열리지 않는다(2026-09-26 실제로 겪음).
set -euo pipefail
lan=$(ip -4 -o addr show enp0s8 | awk '{print $4}' | cut -d/ -f1)
f=$(grep -rl '^ListenAddress 192\.168\.56\.' /etc/ssh/sshd_config /etc/ssh/sshd_config.d/ 2>/dev/null | head -1)
[ -n "$f" ] || { echo "ListenAddress 192.168.56.x 없음"; exit 0; }
cp -p "$f" "$f.bak-before-pfsense"
sed -i -E "s/^ListenAddress 192\.168\.56\.[0-9]+/ListenAddress $lan/" "$f"
sshd -t && echo "$(hostname): $f → $(grep ^ListenAddress "$f")"
