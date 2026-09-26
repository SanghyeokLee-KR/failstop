#!/usr/bin/env bash
# pfSense 경계형: EDGE01 의 진입 주소를 host-only(192.168.56.11)에서 pfSense 포워딩 주소(192.168.56.10)로 옮긴다.
# 사용자는 https://192.168.56.10 으로 들어오고 pfSense 가 192.168.10.11:443 으로 넘긴다. Nginx 는 lab-lan 주소에만 바인딩한다.
# EDGE01 에서 sudo 로 실행한다. 끝나면 새 인증서를 OPS01 blackbox_exporter 에도 넣어야 합성 프로브가 검증을 통과한다.
set -euo pipefail
c=/etc/nginx/nginx.conf
cp -p "$c" "$c.bak-before-pfsense"
sed -i -E '/^\s*listen 192\.168\.56\.11:(80|443)/d' "$c"
sed -i -E 's/server_name 192\.168\.56\.11 192\.168\.10\.11;/server_name 192.168.56.10 192.168.10.11;/' "$c"
sed -i -E 's/(proxy_set_header (Host|X-Forwarded-Host)) 192\.168\.56\.11;/\1 192.168.56.10;/' "$c"
sed -i -E 's/^(#\s+ENTRY_HOST\s+=\s+)192\.168\.56\.11/\1192.168.56.10/' "$c"

# 인증서: 같은 키로 다시 발급하고 SAN 을 새 진입 주소와 lab-lan 주소로 바꾼다.
crt=/etc/pki/tls/certs/edge01.crt; key=/etc/pki/tls/private/edge01.key
cp -p "$crt" "$crt.bak-before-pfsense"
openssl req -x509 -new -key "$key" -subj /CN=edge01 -days 825 -sha256 \
  -addext "subjectAltName=IP:192.168.56.10,IP:192.168.10.11" -out "$crt.new" 2>/dev/null
install -m 0644 -o root -g root "$crt.new" "$crt"; rm -f "$crt.new"; restorecon "$crt"

nginx -t 2>&1 | tail -1
systemctl reload nginx
echo "== 바인딩"; ss -lnt | grep -E ':(80|443) '
echo "== Host 헤더"; grep -nE 'server_name 192|proxy_set_header (Host|X-Forwarded-Host)' "$c"
echo "== SAN"; openssl x509 -in "$crt" -noout -ext subjectAltName
