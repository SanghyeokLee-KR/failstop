#!/usr/bin/env bash
# 데모 관리 평면 mTLS PKI 생성(관리자 설계 v1.1 §1-6). dev/demo 전용.
# 운영 인증서는 운영 PKI에서 발급하며 이 산출물과 섞지 않는다.
#
# 산출물(OUT_DIR):
#   demo-ca.crt        데모 루트 CA 공개 인증서(서버 신뢰 앵커)
#   truststore.p12     CA 인증서만 담은 트러스트스토어(서버가 클라이언트 검증에 사용)
#   admin-server.p12   서버 키+체인(CN=admin.exchange.local, SAN localhost)
#   op-01.p12/op-02.p12 운영자 클라이언트 키+체인(CN=op-01/op-02, 브라우저 임포트용)
#
# 반결탁 정합(§1-6): 운영자별로 서로 다른 클라이언트 인증서를 발급해야 2인 승인이 성립한다.
# 단일 인증서만으로는 approvals 발급 주체와 사용 주체가 같아져 파괴 액션이 반려된다.
set -euo pipefail

# Git Bash(MSYS)의 POSIX 경로 자동 변환이 openssl -subj의 '/O=...'를 Windows 경로로 망가뜨린다.
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

OUT_DIR="${1:-$(cd "$(dirname "$0")/.." && pwd)/config/admin-demo}"
STOREPASS="${ADMIN_DEMO_STOREPASS:-changeit}"
DAYS=3650
mkdir -p "$OUT_DIR"
cd "$OUT_DIR"
rm -f ./*.p12 ./*.crt ./*.key ./*.csr ./*.srl ./*.ext

# 1. 데모 루트 CA
openssl req -x509 -newkey rsa:2048 -nodes -days "$DAYS" \
    -keyout demo-ca.key -out demo-ca.crt \
    -subj "/O=exchange/OU=demo/CN=exchange-admin-demo-ca"

# CA 인증서만 담은 트러스트스토어(사설키 미포함). PKCS12 신뢰스토어.
keytool -importcert -noprompt -alias demo-ca -file demo-ca.crt \
    -keystore truststore.p12 -storetype PKCS12 -storepass "$STOREPASS"

# CA 서명 헬퍼: 엔티티 키·CSR 생성 → CA 서명 → 키+체인 PKCS12 패키징.
issue() {
    local name="$1" cn="$2" ext="$3"
    openssl req -newkey rsa:2048 -nodes -keyout "$name.key" -out "$name.csr" \
        -subj "/O=exchange/OU=demo/CN=$cn"
    printf '%s\n' "$ext" > "$name.ext"
    openssl x509 -req -in "$name.csr" -CA demo-ca.crt -CAkey demo-ca.key \
        -CAcreateserial -days "$DAYS" -sha256 -extfile "$name.ext" -out "$name.crt"
    openssl pkcs12 -export -inkey "$name.key" -in "$name.crt" -certfile demo-ca.crt \
        -name "$name" -out "$name.p12" -passout "pass:$STOREPASS"
    rm -f "$name.key" "$name.csr" "$name.ext" "$name.crt"
}

# 2. 서버 인증서(CN=admin.exchange.local, SAN에 localhost 병기로 로컬 데모 접속 허용)
issue admin-server admin.exchange.local \
    "subjectAltName=DNS:admin.exchange.local,DNS:localhost,IP:127.0.0.1
extendedKeyUsage=serverAuth"

# 3. 운영자 클라이언트 인증서(CN=operator_id). 데모는 로컬 브라우저 임포트를 위해 .p12 배포.
issue op-01 op-01 "extendedKeyUsage=clientAuth"
issue op-02 op-02 "extendedKeyUsage=clientAuth"

rm -f demo-ca.key demo-ca.srl
echo "생성 완료: $OUT_DIR (storepass=$STOREPASS)"
ls -1 "$OUT_DIR"
