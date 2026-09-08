#!/bin/sh
# 엣지 nginx 기동 전에 자체 서명 임시 인증서를 만든다.
#
# 왜 필요한가. 갓 세운 노드에는 인증서가 없고, nginx 443 블록은 ssl_certificate 파일이
# 없으면 설정 파싱 단계에서 죽는다. nginx 가 안 뜨면 80 의
# /.well-known/acme-challenge/ 가 서비스되지 않고, 그러면 Let's Encrypt HTTP-01 검증이
# 불가능하다. 인증서가 없어서 못 뜨고 못 떠서 인증서를 못 받는 교착이다.
#
# 그래서 파일이 없을 때만 자체 서명본을 깔아 nginx 를 띄운다. 브라우저에는 경고가 뜨지만
# 80 이 열리므로 그 상태에서 deploy/issue-cert.sh 가 진짜 인증서를 받아 간다.
#
# 이 파일이 있다는 것이 곧 임시본이라는 표시다: live/<도메인>/BOOTSTRAP
# issue-cert.sh 가 그 표시를 보고 임시본을 지운 뒤 발급한다. 표시가 없으면 진짜 인증서로
# 보고 건드리지 않는다.
#
# 실행 방식. /docker-entrypoint.d/ 에 넣지 않는다. 그 경로는 실행 비트가 있어야 돌고
# Windows 바인드 마운트에서 그 비트가 보장되지 않는다. compose 가 entrypoint 로
# `sh <이 파일>` 을 직접 부른다.
set -e

DOMAIN="${PUBLIC_DOMAIN:?PUBLIC_DOMAIN 미주입. deploy/.env 를 확인한다}"
LIVE="/etc/letsencrypt/live/${DOMAIN}"

if [ -f "${LIVE}/fullchain.pem" ] && [ -f "${LIVE}/privkey.pem" ]; then
  if [ -f "${LIVE}/BOOTSTRAP" ]; then
    echo "[bootstrap-cert] 임시 인증서 존재. 발급 전 상태다. deploy/issue-cert.sh 를 돌린다."
  else
    echo "[bootstrap-cert] 발급된 인증서 존재. 건드리지 않는다."
  fi
else
  echo "[bootstrap-cert] 인증서 없음. 자체 서명 임시본을 만든다. 도메인 ${DOMAIN}"
  mkdir -p "${LIVE}"
  # 유효기간을 1 일로 둔다. 임시본이 오래 남으면 진짜 인증서로 착각한다.
  openssl req -x509 -nodes -newkey rsa:2048 -days 1 \
    -keyout "${LIVE}/privkey.pem" \
    -out "${LIVE}/fullchain.pem" \
    -subj "/CN=${DOMAIN}" >/dev/null 2>&1
  touch "${LIVE}/BOOTSTRAP"
  echo "[bootstrap-cert] 임시본 생성 완료. 이 상태로는 TLS 경고가 뜬다."
fi

exec /docker-entrypoint.sh "$@"
