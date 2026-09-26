#!/usr/bin/env bash
# pfSense 경계형: 앱의 CORS·WebSocket 허용 오리진을 새 진입 주소 https://192.168.56.10 으로 바꾼다.
# APP01·APP02 에서 sudo 로 실행한다. app.env 의 다른 줄(비밀 값)은 읽거나 출력하지 않는다.
set -euo pipefail
f=/etc/exchange/app.env
cp -p "$f" "$f.bak-before-pfsense"
sed -i -E 's#^(EXCHANGE_CORS_ALLOWED_ORIGINS|APP_WS_ALLOWED_ORIGINS)=https://192\.168\.56\.11$#\1=https://192.168.56.10#' "$f"
grep -E '^(EXCHANGE_CORS_ALLOWED_ORIGINS|APP_WS_ALLOWED_ORIGINS)=' "$f"
systemctl restart exchange-app
systemctl is-active exchange-app
