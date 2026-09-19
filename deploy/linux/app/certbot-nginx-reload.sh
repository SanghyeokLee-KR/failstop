#!/bin/sh
# 갱신한 인증서를 적용하기 전에 전체 Nginx 구문을 검사한다.
set -eu
/usr/sbin/nginx -t
/usr/bin/systemctl reload nginx
