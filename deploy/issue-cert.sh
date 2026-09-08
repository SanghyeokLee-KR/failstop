#!/bin/sh
# Let's Encrypt 최초 발급. 엣지 노드에서 한 번만 돌린다.
#
# 왜 별도 파일인가. compose.node-edge.yml 의 certbot 서비스는 `certbot renew` 만 돈다.
# renew 는 갱신 대상이 이미 있을 때만 동작하고 빈 /etc/letsencrypt 에서는 아무것도 하지
# 않으면서 종료 코드 0 을 낸다. 곧 최초 발급 경로가 저장소에 없었다(2026-09-08 확인).
# 무동작이 성공으로 보이는 자리라 별도 명령으로 못박는다.
#
# 순서가 중요하다.
#   1. edge 가 떠 있어야 한다. HTTP-01 은 80 의 /.well-known/acme-challenge/ 로 온다.
#      edge-bootstrap-cert.sh 가 임시 인증서를 깔아 그 기동을 가능하게 해 둔다.
#   2. 임시본을 지운다. certbot 은 live/<도메인> 이 이미 있으면 <도메인>-0001 로 새로
#      만들고, 그러면 nginx 는 계속 임시본을 가리킨다.
#   3. 발급 후 nginx 를 reload 한다. 재기동이 아니라 reload 라 연결이 끊기지 않는다.
#
# 사용:
#   cd deploy && sh issue-cert.sh                실제 발급
#   cd deploy && STAGING=1 sh issue-cert.sh      스테이징. 실패해도 발급 한도를 안 먹는다
#
# 처음에는 STAGING=1 로 한 번 돌려 경로를 확인한다. Let's Encrypt 는 도메인당 주당 5 회
# 실패 한도가 있고, 그 한도를 먹으면 그 주 안에 다시 시도할 수 없다.
set -e

cd "$(dirname "$0")"

# .env 는 커밋 대상이 아니다(.env.example 이 카탈로그다).
if [ -f .env ]; then
  # shellcheck disable=SC1091
  . ./.env
fi

DOMAIN="${PUBLIC_DOMAIN:?PUBLIC_DOMAIN 미주입. deploy/.env 를 확인한다}"
EMAIL="${LETSENCRYPT_EMAIL:?LETSENCRYPT_EMAIL 미주입. 만료 통지를 받을 주소다}"
COMPOSE="docker compose -f compose.node-edge.yml"

STAGING_FLAG=""
if [ -n "${STAGING}" ]; then
  STAGING_FLAG="--staging"
  echo "[issue-cert] 스테이징 모드. 발급되는 인증서는 브라우저가 신뢰하지 않는다."
fi

echo "[issue-cert] 1/4 edge 기동 확인"
$COMPOSE up -d edge
# 80 이 실제로 응답해야 HTTP-01 이 성립한다. 301 도 성공으로 본다(curl -f 는 4xx 부터 실패).
$COMPOSE exec -T edge curl -fsS -o /dev/null "http://127.0.0.1/" \
  || { echo "[issue-cert] 중단. edge 의 80 이 응답하지 않는다. docker compose logs edge 를 본다."; exit 1; }

echo "[issue-cert] 2/4 임시 인증서 제거"
$COMPOSE run --rm --entrypoint sh certbot -c "
  if [ -f /etc/letsencrypt/live/${DOMAIN}/BOOTSTRAP ]; then
    rm -rf /etc/letsencrypt/live/${DOMAIN} \
           /etc/letsencrypt/archive/${DOMAIN} \
           /etc/letsencrypt/renewal/${DOMAIN}.conf
    echo '  임시본 제거'
  else
    echo '  임시본 아님. 이미 발급된 인증서다. 제거하지 않는다'
  fi
"

echo "[issue-cert] 3/4 발급"
# shellcheck disable=SC2086
$COMPOSE run --rm --entrypoint certbot certbot certonly \
  --webroot -w /var/www/certbot \
  -d "${DOMAIN}" \
  --email "${EMAIL}" \
  --agree-tos --no-eff-email --non-interactive \
  ${STAGING_FLAG}

echo "[issue-cert] 4/4 nginx reload"
$COMPOSE exec -T edge nginx -s reload

echo "[issue-cert] 완료. 확인: curl -sI https://${DOMAIN} | head -1"
