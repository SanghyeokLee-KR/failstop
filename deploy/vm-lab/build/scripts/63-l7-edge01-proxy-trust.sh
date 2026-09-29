#!/usr/bin/env bash
# L7 입구 추가: 투명 방화벽(pfsense-tp)의 HAProxy 가 웹 진입 서버 인증서로 TLS 를 풀어 검사한 뒤, 다시 HTTPS 로 이 서버(192.168.10.11:443)에 보낸다.
# HAProxy 가 서버로 여는 연결의 출발지는 투명 방화벽의 브리지 관리 주소 192.168.10.2 다. 이 서버에서 바꾸는 것은 두 가지뿐이다.
#   1. nftables: 192.168.10.2 → 443 허용.
#   2. Nginx real_ip: 192.168.10.2 에서 온 요청에 한해 HAProxy 가 붙인 X-Forwarded-For 를 믿어 $remote_addr 를 원래 사용자 주소로 되돌린다.
#      이 서버는 앱에 X-Forwarded-For 를 $remote_addr 로 덮어써 넘기고, 앱은 getRemoteAddr() 하나로 IP별 요청 제한을 건다.
#      real_ip 가 없으면 L7 입구를 지난 모든 사용자가 192.168.10.2 하나로 묶여 한 사람처럼 제한된다.
# 서버 IP, 인증서, 앱 설정은 바꾸지 않는다. EDGE01 에서 sudo 로 실행한다.
set -euo pipefail
PROXY=192.168.10.2

# 1. nftables: 출발지는 HAProxy 의 주소 하나다.
f=/etc/vm-lab/firewall.d/12-l7-proxy.nft
cat > "$f" <<EOF
# L7 입구(투명 방화벽 HAProxy)가 서버로 여는 HTTPS 연결(2026-09-28). 출발지는 브리지 관리 주소 하나다.
add rule inet vmlab roles iifname "enp0s8" ip saddr ${PROXY} tcp dport 443 ct state new counter accept comment "L7 입구 HAProxy"
EOF
chmod 0600 "$f"; chown root:root "$f"
nft -c -f /etc/vm-lab/firewall.nft && systemctl reload vm-lab-firewall
nft list chain inet vmlab roles | grep -F 'L7 입구'

# 2. Nginx: http 블록의 upstream include 바로 뒤에서 프록시 신뢰 설정을 불러온다.
c=/etc/nginx/nginx.conf
p=/etc/nginx/exchange-l7-proxy.conf
cat > "$p" <<EOF
# L7 입구(투명 방화벽 HAProxy, ${PROXY})가 붙인 X-Forwarded-For 만 믿는다. 다른 출발지의 X-Forwarded-For 는 무시한다.
# HAProxy 는 사용자가 보낸 X-Forwarded-For 를 지운 뒤 자기가 본 주소 하나만 넣는다.
set_real_ip_from ${PROXY};
real_ip_header X-Forwarded-For;
real_ip_recursive off;
EOF
chmod 0644 "$p"
if ! grep -qF "include ${p};" "$c"; then
  cp -p "$c" "$c.bak-before-l7"
  sed -i -E "s#^(\s*)include /etc/nginx/exchange-upstream.conf;#&\n\1include ${p};#" "$c"
fi
grep -nF 'include /etc/nginx/exchange-' "$c"
restorecon -v "$p" >/dev/null 2>&1 || true
nginx -t 2>&1 | tail -1
systemctl reload nginx
systemctl is-active nginx
