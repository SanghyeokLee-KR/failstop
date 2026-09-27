#!/usr/bin/env bash
# 로컬 관리 관문(chaos-admin-gw)의 준비물을 harness/demo/admin-gw.local/ 에 만든다. 처음 한 번만 돌린다.
#
# 1. 데모 운영자 인증서(server/config/admin-demo/<운영자>.p12)에서 관문용 PEM 인증서·키를 꺼낸다.
#    데모 PKI의 저장소 비밀번호는 application-admin.yml 머리 주석의 데모 값이다. 운영 PKI에 쓰지 않는다.
# 2. 로컬 로그인 비밀번호를 무작위로 만들어 htpasswd(apr1)와 login.txt에 적는다. 이미 있으면 그대로 둔다.
#    폴더 이름이 .local 로 끝나 .gitignore(*.local)에 걸리므로 커밋되지 않는다.
#
# 사용: bash harness/scripts/demo-admin-gw-init.sh [운영자 아이디, 기본 op-01]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PKI="$ROOT/server/config/admin-demo"
OUT="$ROOT/harness/demo/admin-gw.local"
OPERATOR="${1:-op-01}"
STOREPASS=changeit

mkdir -p "$OUT"
openssl pkcs12 -in "$PKI/$OPERATOR.p12" -passin "pass:$STOREPASS" -clcerts -nokeys 2>/dev/null \
  | openssl x509 -out "$OUT/gateway.crt"
openssl pkcs12 -in "$PKI/$OPERATOR.p12" -passin "pass:$STOREPASS" -nocerts -nodes 2>/dev/null \
  | openssl pkey -out "$OUT/gateway.key"
cp "$PKI/demo-ca.crt" "$OUT/ca.crt"

if [ -f "$OUT/admin.htpasswd" ]; then
  echo "로그인 정보가 이미 있다: $OUT/login.txt"
else
  PW="$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-16)"
  printf '%s:%s\n' "$OPERATOR" "$(openssl passwd -apr1 "$PW")" > "$OUT/admin.htpasswd"
  printf '로컬 시연 관리 콘솔 로그인(http://localhost:3000/admin)\n아이디 %s\n비밀번호 %s\n' "$OPERATOR" "$PW" > "$OUT/login.txt"
  echo "로그인 정보를 만들었다: $OUT/login.txt"
fi
echo "관문 인증서: $(openssl x509 -in "$OUT/gateway.crt" -noout -subject)"
