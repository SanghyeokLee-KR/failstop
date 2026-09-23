#!/usr/bin/env bash
# EDGE01 (192.168.10.11 / 192.168.56.11) 구축 스크립트.
#
# 역할은 Nginx 웹 진입이다. 정적 웹을 내보내고 /api/ 와 /ws/v1 을 APP01·APP02 로 넘긴다.
# 값의 기준은 build/00-주소계획.md 다. 여기와 다른 값을 만들지 않는다.
#
# 이 스크립트는 "실행하면 이렇게 구성된다" 는 절차다. 결과 보고가 아니다.
# verify 하위 명령을 실제로 돌려 나온 출력을 증거본에 남긴다.
#
# 선행 조건: scripts/00-common-setup.sh 를 먼저 적용한다. 여섯 대 공통인 계정, SSH,
# 기본 차단 방화벽(inet vmlab), node_exporter, 진단 타이머가 그 스크립트의 몫이다.
# 이 스크립트는 EDGE01 역할만 맡는다. 같은 일을 두 곳에서 하지 않는다.
# 공통 기반이 없으면 preview 가 방화벽 단계 앞에서 멈춘다. 대체 방화벽 경로를 두지 않는다.
# 방화벽은 inet vmlab 한 계통만 쓴다(02-정본결정.md 4절).
#
# 실행 위치: EDGE01 안에서 root 권한으로 실행한다.
#   sudo ./10-edge01-setup.sh preview        # 1~4, 8 단계. 패키지, 인증서, HTTP 미리보기, 역할 방화벽
#   sudo ./10-edge01-setup.sh install-web /tmp/web-out.tar.gz
#   sudo ./10-edge01-setup.sh https          # 6~7, 10 단계. SELinux, 업스트림, HTTPS, 수집기 확인
#   sudo ./10-edge01-setup.sh verify         # 11 단계. 확인 명령을 순서대로 실행
#   sudo ./10-edge01-setup.sh swap-upstream  # 장애 복구용. primary 와 backup 을 맞바꾼다
#
# 필요한 파일: build-files/edge01/nginx-vm.conf
#   기본 탐색 경로는 이 스크립트의 ../build-files/edge01 이다.
#   다른 곳에 두었으면 EDGE01_FILES 환경변수로 지정한다.
#
# 비밀 값은 이 파일에 넣지 않는다. 인증서 개인키는 VM 안에서 만들고 0600 으로 둔다.

set -euo pipefail

# --- 주소계획에서 가져온 값 -------------------------------------------------
LAN_IP=192.168.10.11          # lab-lan. 앱·관측과 통신하는 주소
MGMT_IP=192.168.56.11         # host-only. 작업 PC 에서 붙는 주소
ENTRY_CLIENT=192.168.56.1     # 작업 PC 의 host-only 어댑터 주소. 443/80 의 실습 사용자다
PROBE_SOURCE=192.168.10.41    # OPS01 의 lab-lan 주소. blackbox_exporter 가 주기 조회로 붙는다
# 왜 프로브를 443 에 들여보내는가
#   MSP 화면의 '서비스 경로 · 계층별 상태' canvas 가 edge 계층을 probe_success 로 판정한다.
#   프로브가 없으면 그 칸이 영원히 '미수집' 이고 진입 경로가 살아 있는지도 알 수 없다.
#   덤으로 앱까지 도달하는 주기 요청이 생겨 P95 응답시간 패널이 표본을 얻는다. 이 실습망에는
#   시장 봇이 없어 그 전에는 여섯 시간에 요청이 한 건이었다.
#   출발지는 관리 대역이 아니라 lab-lan 주소 하나다. 인증서 SAN 에 192.168.10.11 이 함께 들어 있어
#   검증을 끄지 않고 서비스망으로 붙는다. 00-주소계획.md 의 443 행에 이 출발지를 올린 뒤 넣었다.
APP1_IP=192.168.10.21         # APP01. 현재 리더로 쓰는 primary 업스트림
APP2_IP=192.168.10.22         # APP02. 대기. backup 업스트림
APP_PORT=8080
PFSENSE_IP=192.168.10.1       # pfSense. lab-lan 쪽에서 443/80 을 전달하는 경우의 출발지
STATUS_PORT=8090              # stub_status. 루프백에만 연다. 00-주소계획.md 포트 표와 같은 값이다

# 관리 대역 192.168.56.0/24 는 SSH 22 의 출발지이며 그 규칙은 00-common-setup.sh 가 만든다.
# 웹 443/80 의 출발지로는 쓰지 않는다. 그 대역에는 VM 여섯 대의 host-only 주소가 함께 들어 있어
# DB01 에서 EDGE01 의 443 으로 붙는 경로까지 열리고, 접근제어도의 '사용자 -> 앱·DB 직접 접근 차단'과 어긋난다.
# 9100 도 공통 기반이 OPS01 한 주소만 열어 둔다. 이 스크립트는 다시 열지 않는다.

# 정본 진입 주소. 인증서 SAN, 웹 번들 오리진, 앱 CORS/WS 오리진, 프록시 Host 를 모두 이 값에 맞춘다.
# 값이 어긋나면 브라우저에서 CORS 와 WS 오리진 검사에 막힌다.
ENTRY_HOST=$MGMT_IP
ENTRY_ORIGIN="https://${ENTRY_HOST}"

# pfSense 포트 포워딩의 출발지를 443/80 허용 목록에 넣을지 정한다. 기본값은 0 이다.
# 00-주소계획.md 포트 표는 EDGE01 443/80 의 허용 출발지를 실습 사용자 하나로만 정했고
# pfSense 포워딩은 아직 그 표에 없다. pfSense 도 사용자가 아직 구축하지 않았다.
# 포워딩을 쓰기로 정하면 먼저 표에 그 출발지를 올리고 그 다음에 1 로 실행한다.
ALLOW_PFSENSE_FORWARD=${ALLOW_PFSENSE_FORWARD:-0}

CERT=/etc/pki/tls/certs/edge01.crt
KEY=/etc/pki/tls/private/edge01.key
CERT_DAYS=825                 # 갱신 자동화가 없다. 만료일을 운영 점검 항목으로 남긴다

WEB_ROOT=/opt/exchange-web
WEB_CURRENT=$WEB_ROOT/current
NGINX_CONF=/etc/nginx/nginx.conf
UPSTREAM_CONF=/etc/nginx/exchange-upstream.conf
# 방화벽 파일 경로(ROLE_NFT, LEGACY_NFT)는 8 단계 구간에서 함께 정의한다.

here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
FILES_DIR=${EDGE01_FILES:-$here/../build-files/edge01}

log()  { printf '\n== %s\n' "$*"; }
info() { printf '   %s\n' "$*"; }
die()  { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

require_root() { [[ $EUID -eq 0 ]] || die '루트 권한으로 실행한다. sudo 를 붙인다.'; }

# 주소가 실제로 붙어 있는지 본다. 없으면 kickstart 의 network 설정부터 고친다.
require_addresses() {
    local ip
    for ip in "$LAN_IP" "$MGMT_IP"; do
        ip -o -4 addr show | grep -qw "$ip" \
            || die "주소 $ip 가 이 호스트에 없다. nmcli 로 인터페이스를 먼저 확인한다."
    done
}

# ---------------------------------------------------------------------------
# 1~2 단계: 패키지
# ---------------------------------------------------------------------------
step_packages() {
    log '패키지 설치'
    # kickstart 에서 이미 설치했으면 그대로 넘어간다. 재실행해도 상태가 바뀌지 않는다.
    # nftables 패키지는 nft 명령 때문에 필요하다. 배포판 nftables.service 는 쓰지 않는다.
    # 규칙을 올리는 유닛은 00-common-setup.sh 가 만든 vm-lab-firewall.service 하나다.
    dnf -y install nginx openssl policycoreutils-python-utils nftables tar chrony python3
    info "nginx 버전과 모듈:"
    nginx -v 2>&1 | sed 's/^/     /'
    # 443 구성은 http_ssl_module 이 있어야 기동한다. 없으면 여기서 멈춘다.
    nginx -V 2>&1 | tr ' ' '\n' | grep -q -- '--with-http_ssl_module' \
        || die 'nginx 에 http_ssl_module 이 없다. 443 구성을 올릴 수 없다.'
    info 'http_ssl_module 확인'
    command -v curl >/dev/null || info '참고: curl 이 없다. verify 단계 전에 curl-minimal 을 확인한다.'
    systemctl enable --now chronyd
}

# ---------------------------------------------------------------------------
# 3 단계: 자체 서명 인증서
# ---------------------------------------------------------------------------
step_certificate() {
    log '자체 서명 인증서'
    if [[ -f $CERT && -f $KEY ]]; then
        info '인증서가 이미 있다. 다시 만들지 않는다.'
    else
        # OpenSSL 3 계열은 -noenc 를 쓴다. 옛 이름 -nodes 도 아직 받지만 있는 쪽을 골라 쓴다.
        local noenc='-nodes'
        if openssl req -help 2>&1 | grep -q -- '-noenc'; then noenc='-noenc'; fi
        # SAN 에 두 진입 주소를 모두 넣는다. CA:TRUE 로 만들어 작업 PC 의 신뢰 저장소에
        # 이 파일 하나를 넣으면 검증이 성립하게 한다.
        openssl req -x509 -newkey rsa:2048 -sha256 -days "$CERT_DAYS" "$noenc" \
            -keyout "$KEY" -out "$CERT" \
            -subj '/CN=edge01' \
            -addext "subjectAltName=IP:${MGMT_IP},IP:${LAN_IP}" \
            -addext 'basicConstraints=critical,CA:TRUE' \
            -addext 'keyUsage=critical,digitalSignature,keyEncipherment,keyCertSign' \
            -addext 'extendedKeyUsage=serverAuth'
    fi
    # 개인키는 root 만 읽는다. 저장소와 증거본에 복사하지 않는다.
    chown root:root "$KEY" "$CERT"
    chmod 0600 "$KEY"
    chmod 0644 "$CERT"
    restorecon -F "$KEY" "$CERT"
    openssl x509 -in "$CERT" -noout -subject -dates -ext subjectAltName | sed 's/^/     /'
    info "만료일을 운영 점검 항목으로 적는다. 갱신 자동화는 없다."
}

# ---------------------------------------------------------------------------
# 정적 웹 디렉터리와 SELinux 문맥
# ---------------------------------------------------------------------------
step_web_dirs() {
    log '정적 웹 디렉터리'
    install -d -m 0755 -o root -g root "$WEB_ROOT" "$WEB_ROOT/releases"
    if [[ ! -e $WEB_CURRENT ]]; then
        # 아직 번들이 없다. 디렉터리와 권한, SELinux 문맥, Nginx 경로를 먼저 확인하려고
        # 자리표시 페이지를 둔다. install-web 이 current 를 실제 번들로 바꾼다.
        install -d -m 0755 -o root -g root "$WEB_ROOT/releases/placeholder"
        cat > "$WEB_ROOT/releases/placeholder/index.html" <<'EOF'
<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><title>EDGE01</title></head>
<body><h1>EDGE01 자리표시 페이지</h1>
<p>웹 번들을 아직 올리지 않았다. install-web 으로 실제 번들을 배치한다.</p></body></html>
EOF
        chmod 0644 "$WEB_ROOT/releases/placeholder/index.html"
        ln -sfn "$WEB_ROOT/releases/placeholder" "$WEB_CURRENT"
        info '자리표시 페이지를 두고 current 를 연결했다.'
    else
        info "current -> $(readlink -f "$WEB_CURRENT")"
    fi
}

step_selinux() {
    log 'SELinux 문맥과 불리언'
    # SELinux 는 끄지 않는다. 필요한 문맥과 포트만 추가한다.
    if ! semanage fcontext -l 2>/dev/null | grep -qF '/opt/exchange-web(/.*)?'; then
        semanage fcontext -a -t httpd_sys_content_t '/opt/exchange-web(/.*)?'
        info '정적 파일 문맥 규칙을 추가했다.'
    else
        info '정적 파일 문맥 규칙이 이미 있다.'
    fi
    restorecon -Rv "$WEB_ROOT" | sed 's/^/     /' || true

    # nginx 가 APP01·APP02 의 8080 으로 프록시하려면 이 불리언이 켜져 있어야 한다.
    setsebool -P httpd_can_network_connect on
    info "httpd_can_network_connect = $(getsebool httpd_can_network_connect | awk '{print $3}')"

    # stub_status 를 8090 에 연다. 기본 http_port_t 에 8090 이 없으면 bind 에 실패한다.
    if semanage port -l | awk '$1=="http_port_t"' | grep -qw "$STATUS_PORT"; then
        info "http_port_t 에 ${STATUS_PORT} 이 이미 있다."
    else
        semanage port -a -t http_port_t -p tcp "$STATUS_PORT"
        info "http_port_t 에 ${STATUS_PORT} 을 추가했다."
    fi
}

# ---------------------------------------------------------------------------
# nginx.service 부팅 순서
# ---------------------------------------------------------------------------
step_nginx_dropin() {
    log 'nginx 부팅 순서 드롭인'
    # 특정 IP 에 바인딩하므로 인터페이스가 올라오기 전에 기동하면 bind 에 실패한다.
    install -d -m 0755 /etc/systemd/system/nginx.service.d
    cat > /etc/systemd/system/nginx.service.d/10-wait-network.conf <<'EOF'
# EDGE01 은 192.168.10.11 과 192.168.56.11 에만 바인딩한다.
# 인터페이스가 올라오기 전에 기동하면 "bind() ... failed (99: Cannot assign requested address)" 로 멈춘다.
[Unit]
After=network-online.target
Wants=network-online.target
EOF
    systemctl enable NetworkManager-wait-online.service
    systemctl daemon-reload
}

# ---------------------------------------------------------------------------
# 4 단계: HTTP 미리보기 구성
# ---------------------------------------------------------------------------
step_preview_conf() {
    log 'HTTP 미리보기 구성 배치'
    # 원본은 failstop/deploy/linux/app/nginx-preview.conf 다.
    # 인증서와 앱이 붙기 전에 정적 화면만 확인하는 단계용이다.
    # 바꾼 것은 프록시 대상(127.0.0.1:8080 -> APP01·APP02)과 바인딩 주소뿐이다.
    if [[ -f $NGINX_CONF && ! -f $NGINX_CONF.dist ]]; then
        cp -a "$NGINX_CONF" "$NGINX_CONF.dist"
        info "배포판 기본 설정을 ${NGINX_CONF}.dist 로 보관했다."
    fi
    cat > "$NGINX_CONF" <<EOF
# EDGE01 구축 중간 점검용 HTTP 구성. 원본은 deploy/linux/app/nginx-preview.conf 다.
# 화면이 뜨는 것을 확인한 뒤 https 단계에서 nginx-vm.conf 로 교체한다.
# 이 구성은 GET 과 HEAD 만 받고 계정·주문 경로를 열지 않는다.
user nginx;
worker_processes 1;
error_log /var/log/nginx/error.log warn;
pid /run/nginx.pid;

events { worker_connections 512; }

http {
    include /etc/nginx/mime.types;
    default_type application/octet-stream;
    sendfile on;
    server_tokens off;
    access_log /var/log/nginx/access.log;

    server {
        listen ${LAN_IP}:80 default_server;
        listen ${MGMT_IP}:80 default_server;
        server_name _;
        root ${WEB_CURRENT};
        index index.html;
        add_header X-Content-Type-Options nosniff always;
        add_header X-Frame-Options DENY always;

        # HTTP 화면 확인 단계에서는 계정 정보와 주문을 받지 않는다.
        if (\$request_method !~ ^(GET|HEAD)\$) { return 405; }

        location ^~ /api/admin { return 403; }
        location ^~ /admin { return 403; }
        location ^~ /actuator { return 403; }
        location ^~ /ws { return 403; }
        location ^~ /auth { return 403; }
        location ~ /\\. { return 404; }

        location /api/ {
            proxy_pass http://${APP1_IP}:${APP_PORT};
            proxy_set_header Host \$host;
            proxy_set_header X-Forwarded-For \$remote_addr;
            proxy_connect_timeout 3s;
            proxy_read_timeout 15s;
        }

        location / {
            try_files \$uri \$uri.html \$uri/index.html =404;
        }
    }
}
EOF
    chown root:root "$NGINX_CONF"
    chmod 0644 "$NGINX_CONF"
    nginx -t
    systemctl enable --now nginx
    systemctl reload nginx
    info "확인: curl -sI http://${MGMT_IP}/"
}

# ---------------------------------------------------------------------------
# 8 단계: 역할 방화벽 규칙
# ---------------------------------------------------------------------------
# 기본 차단 정책은 scripts/00-common-setup.sh 가 만든다. 그 스크립트가 하는 일은
#   - inet vmlab 테이블을 만들고 input 정책을 drop 으로 둔다
#   - lo, 응답 연결, 관리 SSH 22, OPS01 이 읽는 9100, 실습 대역 ICMP 를 연다
#   - /etc/vm-lab/firewall.nft 가 /etc/vm-lab/firewall.d/*.nft 를 include 한다
# 이다. EDGE01 이 더 열 것은 443 과 80 둘뿐이므로 역할 파일 하나만 덧붙인다.
# 8090 stub_status 는 127.0.0.1 에만 바인딩하므로 공통 규칙의 lo accept 로 통과한다.
# 9100 도 공통 규칙이 이미 OPS01 한 주소만 열어 두었다. 여기서 다시 열지 않는다.
#
# 대체 방화벽 경로는 두지 않는다. 이전 판은 공통 기반이 없을 때 inet failstop 테이블을
# 따로 만들고 배포판 nftables.service 로 올렸다. 그 구성은 정책이 accept 였고 정책원이
# 둘이 되어 접근제어도의 기본 차단과 어긋났다. 02-정본결정.md 4절이 계통을 하나로 정했으므로
# 그 경로를 없애고, 이미 적용된 흔적은 cleanup_standalone_firewall 이 지운다.
ROLE_NFT=/etc/vm-lab/firewall.d/20-edge01.nft
LEGACY_NFT=/etc/nftables/edge01.nft   # 이전 판이 만들던 단독 구성 파일. 지우기만 한다.

# 80 의 허용 출발지 목록. 00-주소계획.md 포트 표의 'EDGE01 TCP 80 = 실습 사용자' 를 옮긴 값이다.
redirect_sources() {
    printf '%s/32\n' "$ENTRY_CLIENT"
    if [[ $ALLOW_PFSENSE_FORWARD == 1 ]]; then
        printf '%s/32\n' "$PFSENSE_IP"
    fi
}

# 443 의 허용 출발지 목록. 80 의 출발지에 합성 프로브를 더한다. 프로브는 https 로만 조회한다.
web_sources() {
    redirect_sources
    printf '%s/32\n' "$PROBE_SOURCE"
}

# 공통 기반 위에 역할 규칙만 얹는다. add rule 형태로만 덧붙이고 table 과 chain 을 다시 선언하지 않는다.
role_firewall_common() {
    log '역할 방화벽 규칙 (inet vmlab)'
    local src
    {
        printf '%s\n' '# EDGE01 역할 규칙. 생성: scripts/10-edge01-setup.sh'
        printf '%s\n' '# 기본 차단은 /etc/vm-lab/firewall.nft 가 이미 걸었다. 여기서는 443 과 80 만 연다.'
        printf '%s\n' '# 출발지는 00-주소계획.md 포트 표의 EDGE01 443 행과 80 행을 옮긴 값이다.'
        printf '%s\n' "#   ${ENTRY_CLIENT}/32   작업 PC 의 host-only 어댑터. 실습 사용자는 이 주소 하나다. 443 과 80."
        printf '%s\n' "#   ${PROBE_SOURCE}/32   OPS01 의 lab-lan 주소. blackbox_exporter 의 합성 프로브다. 443 만."
        printf '%s\n' '#   관리 대역 /24 전체를 열지 않는다. 그 대역에는 다른 VM 의 host-only 주소도 들어 있다.'
        if [[ $ALLOW_PFSENSE_FORWARD == 1 ]]; then
            printf '%s\n' "#   ${PFSENSE_IP}/32   pfSense 포트 포워딩을 거쳐 들어오는 경우. 표에 올린 뒤 켰다."
        else
            printf '%s\n' "#   ${PFSENSE_IP}/32 은 넣지 않았다. pfSense 포워딩은 아직 주소계획 표에 없다."
        fi
        printf '%s\n' '#'
        printf '%s\n' '# 나가는 통신은 여기서 제한하지 않는다. 발신 허용목록은 pfSense 경계가 맡는다.'
        printf '%s\n' '# 이 파일만으로 "나가는 통신을 통제했다" 고 적지 않는다.'
        printf '%s\n' '#'
        printf '%s\n' '# nftables 규칙 주석은 128바이트 제한이 있고 한글은 한 글자가 3바이트다.'
        printf '%s\n' '# 긴 설명은 규칙 comment 가 아니라 이 # 주석에만 적는다.'
        while read -r src; do
            printf 'add rule inet vmlab roles ip saddr %s tcp dport 443 ct state new counter accept comment "%s"\n' "$src" 'EDGE01 웹'
        done < <(web_sources)
        while read -r src; do
            printf 'add rule inet vmlab roles ip saddr %s tcp dport 80 ct state new counter accept comment "%s"\n' "$src" 'EDGE01 전환'
        done < <(redirect_sources)
    } > "$ROLE_NFT"
    chown root:root "$ROLE_NFT"
    chmod 0600 "$ROLE_NFT"

    # 역할 파일만 따로 검사할 수는 없다. add rule 은 테이블이 이미 있어야 하기 때문이다.
    # 그래서 공통 파일 전체를 검사한 뒤 reload 한다. 검사에 실패하면 적용하지 않는다.
    if ! nft --check -f /etc/vm-lab/firewall.nft; then
        rm -f -- "$ROLE_NFT"
        die '규칙 검사 실패. 역할 파일을 지웠다. 위 출력을 보고 고친다.'
    fi
    # reload 만 쓰면 유닛이 정지 상태일 때 실패한다. 앞 단계에서 배포판 nftables.service 를
    # 중지하며 ruleset 이 통째로 비워졌을 수도 있으므로, 어느 쪽이든 규칙이 다시 서게 한다.
    systemctl reload-or-restart vm-lab-firewall.service
    nft list table inet vmlab | sed 's/^/     /'
    info "역할 파일: $ROLE_NFT"
}

# 두 번째 정책원을 지운다. EDGE01 에는 이전 판이 남긴 흔적이 세 가지 있을 수 있다.
#   1. 배포판 nftables.service. ks/edge01.ks 가 services --enabled 에 넣어 두면 부팅 때 뜬다.
#   2. /etc/sysconfig/nftables.conf 의 edge01.nft include 줄.
#   3. inet failstop 테이블과 /etc/nftables/edge01.nft 규칙 파일.
# 셋을 그대로 두면 정책원이 둘이 되고, nftables.service 를 중지할 때 ruleset 전체가
# 비워지는 버전에서는 inet vmlab 규칙까지 함께 사라진다. 그래서 이 단계를
# role_firewall_common 앞에 두고, 바로 뒤의 reload 로 vmlab 규칙을 다시 세운다.
cleanup_standalone_firewall() {
    log '두 번째 정책원 정리'
    local changed=0

    if [[ $(systemctl is-enabled nftables.service 2>/dev/null || true) != masked ]]; then
        systemctl disable --now nftables.service >/dev/null 2>&1 || true
        systemctl mask nftables.service >/dev/null 2>&1 || true
        info 'nftables.service 를 내리고 mask 했다. 정책원은 vm-lab-firewall.service 하나다.'
        changed=1
    fi

    if [[ -f /etc/sysconfig/nftables.conf ]] && grep -q 'edge01\.nft' /etc/sysconfig/nftables.conf; then
        sed -i '/edge01\.nft/d' /etc/sysconfig/nftables.conf
        info '/etc/sysconfig/nftables.conf 의 edge01.nft include 줄을 지웠다.'
        changed=1
    fi

    if nft list table inet failstop >/dev/null 2>&1; then
        nft delete table inet failstop || true
        info 'inet failstop 테이블을 지웠다.'
        changed=1
    fi

    if [[ -f $LEGACY_NFT ]]; then
        rm -f -- "$LEGACY_NFT"
        info "이전 단독 구성 규칙 파일을 지웠다: $LEGACY_NFT"
        changed=1
    fi

    if [[ $changed -eq 0 ]]; then
        info '지울 두 번째 정책원이 없다.'
    fi
}

# 공통 기반이 있어야 이 스크립트를 진행할 수 있다. 없으면 여기서 멈춘다.
require_common_firewall() {
    [[ -f /etc/vm-lab/firewall.nft && -d /etc/vm-lab/firewall.d ]] || die \
'공통 기반 방화벽이 없다. 먼저 00-common-setup.sh --apply 를 실행한다.
       기대 경로: /etc/vm-lab/firewall.nft 와 /etc/vm-lab/firewall.d/
       대체 방화벽 경로는 두지 않는다. 방화벽은 inet vmlab 한 계통만 쓴다.'
}

step_firewall() {
    # 정책원이 겹치면 어느 쪽이 적용됐는지 판단할 수 없다. firewalld 도 배포판 nftables 도 내린다.
    systemctl mask firewalld.service 2>/dev/null || true
    require_common_firewall
    cleanup_standalone_firewall
    role_firewall_common
}

# ---------------------------------------------------------------------------
# 5 단계: 웹 번들 배치
# ---------------------------------------------------------------------------
step_install_web() {
    local tarball=${1:-}
    [[ -n $tarball ]] || die '사용법: install-web <web-out.tar.gz>'
    [[ -f $tarball ]] || die "번들을 찾을 수 없다: $tarball"
    log "웹 번들 배치: $tarball"

    local release_id release_dir
    release_id=$(date +%Y%m%d-%H%M)
    release_dir=$WEB_ROOT/releases/$release_id
    install -d -m 0755 -o root -g root "$release_dir"
    # 작업 PC 에서 web/out 의 내용을 묶었다고 본다.
    #   tar -C web/out -czf web-out.tar.gz .
    tar -xzf "$tarball" -C "$release_dir"

    [[ -f $release_dir/index.html ]] \
        || die "번들에 index.html 이 없다. tar -C web/out -czf 로 다시 묶는다: $release_dir"

    # 디렉터리 0755, 파일 0644, 소유자 root:root. nginx 는 읽기만 한다.
    chown -R root:root "$release_dir"
    find "$release_dir" -type d -exec chmod 0755 {} +
    find "$release_dir" -type f -exec chmod 0644 {} +
    restorecon -Rv "$release_dir" >/dev/null || true

    ln -sfn "$release_dir" "$WEB_CURRENT"
    info "current -> $release_dir"

    # 번들에 로컬 주소가 남았는지 본다. build-web.ps1 이 작업 PC 에서 이미 검사하지만
    # 실제로 올라간 파일을 한 번 더 본다. 남아 있으면 브라우저가 localhost 로 API 를 부른다.
    if grep -rIl -E '(https?|wss?)://(localhost|127\.0\.0\.1)(:|/)' "$release_dir" >/dev/null 2>&1; then
        die "번들에 로컬 서버 주소가 남아 있다. build-web.ps1 -Origin ${ENTRY_ORIGIN} 로 다시 만든다."
    fi
    info "로컬 주소 잔존 없음"
}

# ---------------------------------------------------------------------------
# 7 단계: 업스트림과 HTTPS 구성
# ---------------------------------------------------------------------------
write_upstream() {
    local primary=$1 backup=$2 use_zone=${3:-1} zone_line=''
    if [[ $use_zone == 1 ]]; then
        # zone 을 쓰면 실패 표시를 워커가 공유한다. 이 빌드가 받지 않으면 아래에서 지운다.
        zone_line='    zone exchange_app 64k;'
    fi
    cat > "$UPSTREAM_CONF" <<EOF
# EDGE01 의 업스트림. 생성: scripts/10-edge01-setup.sh
#
# 주소계획이 APP02 를 "대기" 로 정의하므로 부하 분산이 아니라 primary/backup 으로 둔다.
# API 와 WebSocket 이 같은 upstream 을 써서 장애 표시를 공유한다.
# WebSocket 게시가 단일 노드 SimpleBroker 라 리더가 아닌 노드에 붙으면 시세가 오지 않는다.
# 그래서 WS 를 분산하지 않고 primary 로 고정한다.
#
# 장애 복구로 APP02 가 리더를 가져간 뒤에는 이 파일의 primary 와 backup 을 손으로 맞바꾼다.
#   sudo ./10-edge01-setup.sh swap-upstream
# ADR 0017 의 재승격 금지 규칙 때문에 APP01 이 살아나도 리더는 APP02 가 계속 갖는다.
upstream exchange_app {
${zone_line}
    server ${primary}:${APP_PORT} max_fails=2 fail_timeout=10s;
    server ${backup}:${APP_PORT} backup;
}
EOF
    chown root:root "$UPSTREAM_CONF"
    chmod 0644 "$UPSTREAM_CONF"
}

step_https_conf() {
    log 'HTTPS 구성 배치'
    local source_conf=$FILES_DIR/nginx-vm.conf
    [[ -f $source_conf ]] || die "nginx-vm.conf 를 찾을 수 없다: $source_conf (EDGE01_FILES 로 경로를 지정한다)"
    [[ -f $CERT && -f $KEY ]] || die '인증서가 없다. preview 단계를 먼저 실행한다.'

    # activate-https.py 의 방식이다. 교체 전에 백업하고 nginx -t 실패 시 되돌린다.
    local backup=$NGINX_CONF.before-https
    if [[ -f $NGINX_CONF && ! -f $backup ]]; then
        cp -a "$NGINX_CONF" "$backup"
        info "이전 구성을 ${backup} 로 백업했다."
    fi

    # nginx-vm.conf 는 주소를 자리표시자로 둔 원본이다. 값은 이 스크립트에서만 온다.
    # 이렇게 해야 ENTRY_HOST 를 바꿨을 때 프록시 Host 헤더가 옛 주소로 남지 않는다.
    local rendered
    rendered=$(mktemp)
    sed -e "s/__LAN_IP__/${LAN_IP}/g" \
        -e "s/__MGMT_IP__/${MGMT_IP}/g" \
        -e "s/__ENTRY_HOST__/${ENTRY_HOST}/g" \
        -e "s/__STATUS_PORT__/${STATUS_PORT}/g" \
        "$source_conf" > "$rendered"
    if grep -nE '__[A-Z_]+__' "$rendered"; then
        rm -f -- "$rendered"
        die "nginx-vm.conf 에 치환되지 않은 자리표시자가 남았다. 위에 찍힌 줄을 고친다."
    fi

    # 업스트림 파일이 이미 있으면 지금의 primary/backup 순서를 지킨다.
    # 2026-09-22 에 swap-upstream 으로 APP02 를 primary 로 돌린 뒤 이 단계를 다시 돌렸더니
    # APP01 primary 로 조용히 되돌아갔다. 그 사이 리더는 APP02 였으므로 EDGE01 이 한 시간 넘게
    # 팔로워로 요청을 보냈다. WebSocket 시세는 리더에서만 나오므로 그동안 시세가 끊겨 있었다.
    # 순서는 리더를 따라 사람이 swap-upstream 으로 정하는 값이다. 설정 재배치가 그 판단을 덮지 않는다.
    local up_primary=$APP1_IP up_backup=$APP2_IP
    if [[ -f $UPSTREAM_CONF ]]; then
        local cur_p cur_b
        cur_p=$(grep -E '^\s*server .*max_fails' "$UPSTREAM_CONF" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+' | head -1 || true)
        cur_b=$(grep -E '^\s*server .* backup;' "$UPSTREAM_CONF" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+' | head -1 || true)
        if [[ -n $cur_p && -n $cur_b ]]; then
            up_primary=$cur_p
            up_backup=$cur_b
            info "기존 업스트림 순서를 유지한다. primary=$up_primary backup=$up_backup"
        fi
    fi
    write_upstream "$up_primary" "$up_backup" 1
    install -o root -g root -m 0644 "$rendered" "$NGINX_CONF"
    rm -f -- "$rendered"

    if ! nginx -t 2>/dev/null; then
        # upstream 의 zone 지시자를 이 빌드가 받지 않는 경우다. 지우고 다시 본다.
        # 그러면 실패 표시가 워커별로 남지만 worker_processes 1 이라 워커가 하나뿐이다.
        info 'zone 지시자를 뺀 업스트림으로 다시 시도한다.'
        write_upstream "$up_primary" "$up_backup" 0
    fi

    if ! nginx -t; then
        info '설정 검사 실패. 이전 구성으로 되돌린다.'
        if [[ -f $backup ]]; then cp -a "$backup" "$NGINX_CONF"; fi
        nginx -t || true
        systemctl reload nginx || true
        die 'nginx -t 실패. 위 출력을 보고 nginx-vm.conf 를 고친다.'
    fi

    if systemctl is-active --quiet nginx; then
        systemctl reload nginx
    else
        systemctl enable --now nginx
    fi
    info "적용했다. 진입 주소는 ${ENTRY_ORIGIN} 다."
}

swap_upstream() {
    log '업스트림 primary/backup 교체'
    [[ -f $UPSTREAM_CONF ]] || die "업스트림 파일이 없다: $UPSTREAM_CONF"
    # 현재 primary 가 누구인지 파일에서 읽는다. backup 표시가 없는 줄이 primary 다.
    local current_primary current_backup
    current_primary=$(grep -E '^\s*server .*max_fails' "$UPSTREAM_CONF" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+' | head -1 || true)
    current_backup=$(grep -E '^\s*server .* backup;' "$UPSTREAM_CONF" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+' | head -1 || true)
    [[ -n $current_primary && -n $current_backup ]] || die '업스트림 파일에서 주소를 읽지 못했다. 직접 확인한다.'
    info "현재 primary=$current_primary backup=$current_backup"

    local use_zone=0
    if grep -q 'zone exchange_app' "$UPSTREAM_CONF"; then use_zone=1; fi
    cp -a "$UPSTREAM_CONF" "$UPSTREAM_CONF.before-swap"
    write_upstream "$current_backup" "$current_primary" "$use_zone"

    if ! nginx -t; then
        cp -a "$UPSTREAM_CONF.before-swap" "$UPSTREAM_CONF"
        die '교체 후 nginx -t 실패. 되돌렸다.'
    fi
    systemctl reload nginx
    info "교체했다. primary=$current_backup backup=$current_primary"
    info '이 교체는 수동이다. 리더가 다시 바뀌면 같은 명령을 다시 실행한다.'
}

# ---------------------------------------------------------------------------
# 10 단계: node_exporter 확인
# ---------------------------------------------------------------------------
# node_exporter 는 이 스크립트가 설치하지 않는다. scripts/00-common-setup.sh 가
# 여섯 대에 같은 방식으로 올리고 각 VM 의 lab-lan 주소에만 바인딩한다.
# 그 스크립트의 --collector.systemd.unit-include 목록에는 nginx 가 이미 들어 있다.
# 여기서는 EDGE01 에서 실제로 떠 있는지만 본다. 설치 경로를 둘로 만들지 않는다.
step_node_exporter_check() {
    log 'node_exporter 상태 확인'
    if ! systemctl list-unit-files node_exporter.service >/dev/null 2>&1 \
        || ! systemctl cat node_exporter.service >/dev/null 2>&1; then
        info 'node_exporter 유닛이 없다. 00-common-setup.sh 를 먼저 적용한다.'
        info "  sudo bash 00-common-setup.sh --apply --node-exporter-tarball /tmp/node_exporter-<버전>.linux-amd64.tar.gz"
        return 0
    fi
    if systemctl is-active --quiet node_exporter; then
        info "활성. 확인: curl -s http://${LAN_IP}:9100/metrics | head -1"
    else
        info '유닛은 있으나 정지 상태다. journalctl -u node_exporter 로 원인을 본다.'
    fi
    if [[ -f /etc/node_exporter/listen.env ]]; then
        local bound
        bound=$(grep -E '^NODE_EXPORTER_ADDRESS=' /etc/node_exporter/listen.env | cut -d= -f2 || true)
        if [[ $bound == "$LAN_IP" ]]; then
            info "바인딩 주소 $bound. 주소계획과 같다."
        else
            info "바인딩 주소가 $bound 다. 주소계획은 $LAN_IP 를 쓴다. 00-common-setup.sh 쪽을 확인한다."
        fi
    fi
}

# ---------------------------------------------------------------------------
# 11 단계: 확인
# ---------------------------------------------------------------------------
pass_count=0
fail_count=0
skip_count=0
ok()   { printf '  [OK]   %s\n' "$*"; pass_count=$((pass_count + 1)); }
ng()   { printf '  [FAIL] %s\n' "$*"; fail_count=$((fail_count + 1)); }
skip() { printf '  [SKIP] %s\n' "$*"; skip_count=$((skip_count + 1)); }

http_code() {
    # 접속 자체가 실패하면 curl 이 000 을 찍고 0 이 아닌 값으로 끝난다. 그 값을 삼킨다.
    curl -k -s -o /dev/null -w '%{http_code}' --max-time 10 "$1" 2>/dev/null || true
}

step_verify() {
    # --with-app 을 붙이면 앱과 DB 가 올라온 상태를 전제하고 API·WS 판정도 실패로 센다.
    local with_app=0
    if [[ ${1:-} == --with-app ]]; then with_app=1; fi

    log '설정 검사'
    # 파이프로 넘기면 sed 의 종료 상태를 보게 된다. nginx -t 의 상태를 따로 잡는다.
    local conf_log conf_rc=0
    conf_log=$(mktemp)
    nginx -t > "$conf_log" 2>&1 || conf_rc=$?
    sed 's/^/     /' "$conf_log"
    rm -f -- "$conf_log"
    if [[ $conf_rc -eq 0 ]]; then ok 'nginx -t'; else ng 'nginx -t'; fi

    log '수신 소켓'
    ss -lntp | sed 's/^/     /'
    # 열 정렬 때문에 공백 개수가 일정하지 않다. 로컬 주소 열만 뽑아 정확히 비교한다.
    local listening sock
    listening=$(ss -lntH | awk '{print $4}')
    for sock in "${LAN_IP}:443" "${MGMT_IP}:443" "${LAN_IP}:80" "${MGMT_IP}:80" "127.0.0.1:${STATUS_PORT}"; do
        if grep -qxF "$sock" <<<"$listening"; then ok "수신 $sock"; else ng "수신 없음 $sock"; fi
    done
    if grep -qxF "${LAN_IP}:9100" <<<"$listening"; then
        ok "수신 ${LAN_IP}:9100"
    else
        skip "수신 없음 ${LAN_IP}:9100 (node_exporter 미설치)"
    fi
    # 전체 주소 바인딩은 주소계획 위반이다.
    if grep -qE '^(0\.0\.0\.0|\[::\]):(80|443)$' <<<"$listening"; then
        ng '0.0.0.0 에 80 또는 443 이 열려 있다. 바인딩을 잘못했다.'
    else
        ok '전체 주소 바인딩 없음'
    fi

    log 'HTTPS 전환'
    local head
    head=$(curl -sI --max-time 10 "http://${ENTRY_HOST}/" 2>/dev/null || true)
    if grep -q '308' <<<"$head" && grep -qi "Location: ${ENTRY_ORIGIN}/" <<<"$head"; then
        ok "80 -> 308 ${ENTRY_ORIGIN}/"
    else
        ng "80 전환 실패. 응답: $(head -1 <<<"$head")"
    fi

    log '정적 웹과 보안 헤더'
    head=$(curl -k -sI --max-time 10 "${ENTRY_ORIGIN}/" 2>/dev/null || true)
    if grep -q ' 200' <<<"$head"; then ok '443 정적 200'; else ng "443 정적 실패: $(head -1 <<<"$head")"; fi
    grep -qi 'X-Content-Type-Options: nosniff' <<<"$head" && ok 'X-Content-Type-Options' || ng 'X-Content-Type-Options 없음'
    grep -qi 'X-Frame-Options: DENY'          <<<"$head" && ok 'X-Frame-Options'        || ng 'X-Frame-Options 없음'
    grep -qi 'Server: nginx/'                 <<<"$head" && ng 'server_tokens 가 켜져 있다' || ok 'server_tokens off'

    log '인증서'
    openssl s_client -connect "${ENTRY_HOST}:443" </dev/null 2>/dev/null \
        | openssl x509 -noout -subject -dates -ext subjectAltName | sed 's/^/     /'

    log '차단 경로'
    local path code
    for path in /actuator/health /api/v1/admin /api/admin/health /admin /admin.html; do
        code=$(http_code "${ENTRY_ORIGIN}${path}")
        [[ $code == 403 ]] && ok "$path = 403" || ng "$path = $code (403 이어야 한다)"
    done
    code=$(http_code "${ENTRY_ORIGIN}/.env")
    [[ $code == 404 ]] && ok '/.env = 404' || ng "/.env = $code"

    log '앱 연동 (APP01·APP02 와 DB 가 올라와 있어야 한다)'
    code=$(http_code "${ENTRY_ORIGIN}/api/v1/accounts")
    if [[ $code == 401 ]]; then
        ok '/api/v1/accounts = 401'
    elif [[ $with_app == 1 ]]; then
        ng "/api/v1/accounts = $code (401 이어야 한다)"
    else
        skip "/api/v1/accounts = $code. 앱이 없으면 502 가 정상이다"
    fi

    local markets
    markets=$(curl -k -s --max-time 10 "${ENTRY_ORIGIN}/api/v1/markets" 2>/dev/null \
        | python3 -c 'import sys,json; print(len(json.load(sys.stdin)["data"]))' 2>/dev/null || echo '-')
    if [[ $markets == 12 ]]; then
        ok '마켓 12개'
    elif [[ $with_app == 1 ]]; then
        ng "마켓 수 $markets (12 여야 한다)"
    else
        skip "마켓 조회 결과 $markets. 앱과 DB 가 올라와 있어야 한다"
    fi

    log 'WebSocket 핸드셰이크'
    local ws
    ws=$(curl -k -s -i -N --max-time 10 \
        -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' \
        -H "Sec-WebSocket-Key: $(openssl rand -base64 16)" \
        -H "Origin: ${ENTRY_ORIGIN}" "${ENTRY_ORIGIN}/ws/v1" 2>/dev/null | head -1 || true)
    if grep -q '101' <<<"$ws"; then
        ok "/ws/v1 = 101"
        info '핸드셰이크 성공은 게시 도착을 뜻하지 않는다. 화면에서 시세가 흐르는지 직접 본다.'
    elif [[ $with_app == 1 ]]; then
        ng "/ws/v1 = $ws (101 이어야 한다. Upgrade 전달이나 앱 오리진 설정을 본다)"
    else
        skip "/ws/v1 = $ws"
    fi

    log 'SELinux 거부 기록'
    if command -v ausearch >/dev/null 2>&1; then
        if ausearch -m AVC -ts recent 2>/dev/null | grep -q 'denied'; then
            ng 'AVC 거부가 있다. 아래를 보고 문맥이나 불리언을 고친다. SELinux 를 끄지 않는다.'
            ausearch -m AVC -ts recent 2>/dev/null | tail -20 | sed 's/^/     /'
        else
            ok 'AVC 거부 없음'
        fi
    else
        skip 'ausearch 가 없다 (audit 패키지 미설치)'
    fi

    log '방화벽 규칙'
    # 계통은 inet vmlab 하나다. 다른 테이블이 함께 서 있으면 그 자체가 실패다.
    if nft list table inet vmlab >/dev/null 2>&1; then
        nft list table inet vmlab | sed 's/^/     /'
        ok 'inet vmlab 테이블 적용'
        if nft list chain inet vmlab input | grep -q 'policy drop'; then
            ok '기본 정책 drop'
        else
            ng '기본 정책이 drop 이 아니다'
        fi
        if [[ -f $ROLE_NFT ]]; then ok "역할 파일 $ROLE_NFT"; else ng "역할 파일이 없다: $ROLE_NFT"; fi
        # 역할 규칙은 input 이 아니라 roles 체인에 들어간다. input 은 공통 기반이 만들고
        # 그 끝에서 roles 로 jump 한다. 검사도 두 체인을 함께 읽어야 실제 허용 여부와 맞는다.
        local entry_rules
        entry_rules="$(nft list chain inet vmlab input 2>/dev/null; nft list chain inet vmlab roles 2>/dev/null)"
        local port
        for port in 443 80; do
            if grep -q "dport ${port} " <<<"$entry_rules"; then
                ok "${port} 허용 규칙 있음"
            else
                ng "${port} 허용 규칙이 없다. preview 를 다시 실행한다."
            fi
        done
        # 443/80 출발지는 실습 단말 하나여야 한다. 관리 대역 전체가 열려 있으면 실패다.
        if grep -qE 'saddr 192\.168\.56\.0/24 tcp dport (443|80) ' <<<"$entry_rules"; then
            ng '443 또는 80 이 관리 대역 전체에 열려 있다. web_sources 를 확인한다.'
        else
            ok '443·80 출발지에 관리 대역 전체 없음'
        fi
        # 합성 프로브는 https 로만 조회하므로 80 에는 열지 않는다(00-주소계획.md 80 행).
        # 주소의 점이 정규식에서 아무 문자와 일치하지 않도록 고정 문자열로 찾는다.
        if grep -qF "saddr ${PROBE_SOURCE} tcp dport 80 " <<<"$entry_rules"; then
            ng "80 이 ${PROBE_SOURCE} 에도 열려 있다. 주소계획은 80 을 실습 단말에만 연다. preview 를 다시 실행한다."
        else
            ok "80 출발지에 합성 프로브 주소 없음"
        fi
    else
        ng '방화벽 테이블이 없다. 00-common-setup.sh 와 preview 를 확인한다.'
    fi
    # 두 번째 정책원 확인. 이전 판의 단독 구성이 남아 있으면 정책이 둘로 갈린다.
    if nft list table inet failstop >/dev/null 2>&1; then
        ng 'inet failstop 테이블이 남아 있다. preview 를 다시 실행해 정리한다.'
    else
        ok 'inet failstop 테이블 없음'
    fi
    case "$(systemctl is-enabled nftables.service 2>/dev/null || true)" in
        masked|disabled) ok "배포판 nftables.service 비활성" ;;
        '')              ok '배포판 nftables.service 없음' ;;
        *)               ng '배포판 nftables.service 가 살아 있다. 정책원이 둘이 된다.' ;;
    esac
    if [[ -f /etc/sysconfig/nftables.conf ]] && grep -Eq '^\s*include' /etc/sysconfig/nftables.conf; then
        ng '/etc/sysconfig/nftables.conf 에 include 지시자가 남아 있다.'
    else
        ok '/etc/sysconfig/nftables.conf 에 활성 지시자 없음'
    fi
    if [[ -f $LEGACY_NFT ]]; then
        ng "이전 단독 구성 파일이 남아 있다: $LEGACY_NFT"
    else
        ok '이전 단독 구성 파일 없음'
    fi

    log '부팅 자동 기동'
    local unit
    # 방화벽 유닛은 vm-lab-firewall.service 하나다. 배포판 nftables.service 는 위에서 따로 본다.
    for unit in nginx vm-lab-firewall sshd chronyd; do
        if systemctl is-enabled --quiet "$unit" 2>/dev/null; then ok "$unit enabled"; else ng "$unit 자동 기동 꺼짐"; fi
        if systemctl is-active  --quiet "$unit" 2>/dev/null; then ok "$unit active";  else ng "$unit 정지"; fi
    done
    if systemctl is-enabled --quiet node_exporter 2>/dev/null; then
        ok 'node_exporter enabled'
    else
        skip 'node_exporter 없음. 00-common-setup.sh 가 설치한다'
    fi
    # firewalld 가 되살아나면 정책원이 둘이 된다.
    if [[ $(systemctl is-enabled firewalld.service 2>/dev/null || true) == masked ]]; then
        ok 'firewalld masked'
    else
        ng 'firewalld 가 masked 가 아니다. 정책원이 둘이 된다.'
    fi

    printf '\n== 판정 요약: 통과 %d / 실패 %d / 건너뜀 %d\n' "$pass_count" "$fail_count" "$skip_count"
    printf '   이 출력은 실행한 시점의 결과다. 다른 환경의 결과로 옮겨 적지 않는다.\n'
    [[ $fail_count -eq 0 ]]
}

usage() {
    cat <<'EOF'
사용법: sudo ./10-edge01-setup.sh <하위 명령>

  preview               패키지, 인증서, 디렉터리, SELinux, HTTP 미리보기 구성, 역할 방화벽까지 올린다
  install-web <tar.gz>  작업 PC 에서 만든 web/out 번들을 배치하고 current 를 바꾼다
  https                 업스트림과 HTTPS 구성을 적용하고 node_exporter 상태를 확인한다
  verify [--with-app]   확인 명령을 순서대로 실행한다. --with-app 은 앱 연동 판정까지 실패로 센다
  swap-upstream         primary 와 backup 을 맞바꾼다. APP02 가 리더를 가져간 뒤에 쓴다

선행: scripts/00-common-setup.sh 를 먼저 적용한다. 계정, SSH, 기본 차단 방화벽,
      node_exporter, 진단 타이머가 그 스크립트의 몫이다. 이 스크립트는 EDGE01 역할만 맡는다.
      /etc/vm-lab/firewall.nft 가 없으면 preview 가 첫 단계에서 멈춘다. 대체 경로는 없다.
방화벽: inet vmlab 한 계통만 쓴다. 역할 규칙은 /etc/vm-lab/firewall.d/20-edge01.nft 이고
      적용은 vm-lab-firewall.service 다. 배포판 nftables.service 는 mask 한다.
      443·80 의 허용 출발지는 실습 단말 192.168.56.1 하나다. 관리 대역 전체를 열지 않는다.
순서: 00-common-setup.sh -> preview -> (작업 PC 에서 build-web.ps1) -> install-web -> https -> verify
EOF
}

main() {
    case "${1:-}" in
        preview)
            require_root; require_addresses
            # 방화벽 단계에서야 멈추면 앞 단계가 이미 적용된 뒤다. 선행 조건을 먼저 본다.
            require_common_firewall
            step_packages
            step_certificate
            step_web_dirs
            step_selinux
            step_nginx_dropin
            step_preview_conf
            step_firewall
            log '미리보기 단계 완료'
            info "브라우저나 curl 로 http://${MGMT_IP}/ 를 확인한 뒤 웹 번들을 올린다."
            ;;
        install-web)
            require_root
            step_install_web "${2:-}"
            ;;
        https)
            require_root; require_addresses
            step_web_dirs
            step_selinux
            step_https_conf
            step_node_exporter_check
            log 'HTTPS 단계 완료'
            info "APP01·APP02 의 EXCHANGE_CORS_ALLOWED_ORIGINS 와 APP_WS_ALLOWED_ORIGINS 가"
            info "${ENTRY_ORIGIN} 인지 확인한 뒤 verify 를 실행한다."
            ;;
        verify)
            require_root
            step_verify "${2:-}"
            ;;
        swap-upstream)
            require_root
            swap_upstream
            ;;
        ''|-h|--help|help)
            usage
            ;;
        *)
            usage
            die "알 수 없는 하위 명령: $1"
            ;;
    esac
}

main "$@"
