#!/usr/bin/env bash
# 투명 방화벽 전환: 앱의 CORS·WebSocket 허용 오리진을 웹 진입 서버의 자기 주소 https://192.168.10.11 로 바꾼다
# (53-boundary-app-origin.sh 가 NAT 진입 주소로 옮긴 것을 되돌린다).
# APP01·APP02 에서 sudo 로 실행한다. app.env 의 다른 줄(비밀 값)은 읽거나 출력하지 않는다.
set -euo pipefail
f=/etc/exchange/app.env
cp -p "$f" "$f.bak-before-transparent"
sed -i -E 's#^(EXCHANGE_CORS_ALLOWED_ORIGINS|APP_WS_ALLOWED_ORIGINS)=https://192\.168\.56\.10$#\1=https://192.168.10.11#' "$f"
grep -E '^(EXCHANGE_CORS_ALLOWED_ORIGINS|APP_WS_ALLOWED_ORIGINS)=' "$f"
systemctl restart exchange-app
systemctl is-active exchange-app
