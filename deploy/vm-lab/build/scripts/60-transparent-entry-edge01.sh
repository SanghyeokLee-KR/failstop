#!/usr/bin/env bash
# 투명 방화벽 전환: 서비스 경로의 NAT(pfSense 포트포워딩 192.168.56.10 → 192.168.10.11)를 빼고, 사용자가 웹 진입 서버의
# 자기 주소 https://192.168.10.11 로 바로 들어오게 한다. 기존 라우터(pfsense-fw)는 주소를 바꾸지 않고 라우팅만 한다.
# 52-boundary-edge01.sh 가 NAT 진입 주소로 옮긴 Host 헤더를 되돌린다. 인증서 SAN 에는 이미 192.168.10.11 이 있어 재발급하지 않는다.
# EDGE01 에서 sudo 로 실행한다.
set -euo pipefail
c=/etc/nginx/nginx.conf
cp -p "$c" "$c.bak-before-transparent"
sed -i -E 's/(proxy_set_header (Host|X-Forwarded-Host)) 192\.168\.56\.10;/\1 192.168.10.11;/' "$c"
sed -i -E 's/^(#\s+ENTRY_HOST\s+=\s+)192\.168\.56\.10/\1192.168.10.11/' "$c"

nginx -t 2>&1 | tail -1
systemctl reload nginx
echo "== 바인딩"; ss -lnt | grep -E ':(80|443) '
echo "== Host 헤더"; grep -nE 'server_name 192|proxy_set_header (Host|X-Forwarded-Host)' "$c"
echo "== SAN"; openssl x509 -in /etc/pki/tls/certs/edge01.crt -noout -ext subjectAltName
