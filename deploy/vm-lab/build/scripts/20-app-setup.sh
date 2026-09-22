#!/usr/bin/env bash
# APP01/APP02를 거래 앱 노드로 만든다. 두 대의 처리는 같고 주소만 다르므로 노드 번호만 인자로 받는다.
#
# 값의 기준은 build/00-주소계획.md 다. 여기와 다르면 계획서를 먼저 고치고 이 스크립트를 맞춘다.
# 이 스크립트는 VM 안에서 root로 돈다. 작업 PC에서 실행하는 것이 아니다.
#
# 진입 오리진은 02-정본결정.md 3절이 정한 https://192.168.56.11 하나다. 작업 PC 브라우저가
# host-only 주소로 들어오므로 CORS와 WebSocket 허용 목록도 같은 값이어야 한다. lab-lan 주소인
# 192.168.10.11을 오리진으로 쓰면 Origin 헤더가 달라 CORS와 WebSocket 오리진 검사에서 막힌다.
#
# 실행 예 (APP01):
#   sudo bash 20-app-setup.sh 1 --origin https://192.168.56.11 --generate-app-secrets
# 실행 예 (APP02):
#   sudo bash 20-app-setup.sh 2 --origin https://192.168.56.11
#
# 방화벽은 02-정본결정.md 4절대로 inet vmlab 한 계통만 쓴다. 공통 기반
# scripts/00-common-setup.sh 를 먼저 적용해야 이 스크립트가 역할 규칙을 얹을 수 있다.
#
# 준비물은 --stage-dir(기본 /home/ops/stage) 아래에 있어야 한다. 작업 PC에서 scp로 올린다.
#   app.jar                                       server/app/build/libs의 부트 JAR
#   app.jar.sha256                                작업 PC에서 만든 해시. 첫 필드만 읽는다
#   node_exporter-1.12.1.linux-amd64.tar.gz       공식 배포본
#   build-files/app/exchange-app.service          저장소 원본과 바이트가 같아야 한다
#   build-files/app/app.env.vm.example            app.env 원본
#   linux/observability/node_exporter-host.service
#
# 이 스크립트는 비밀 값을 만들기는 해도 출력하지 않는다. DB 비밀번호는 DB01이 정한 값을
# 그대로 써야 하므로 여기서 만들지 않고 운영자가 직접 넣는다.
#
# 실행했다고 검증이 끝난 것이 아니다. 판정은 문서/APP_구축절차.md의 확인 절차로 한다.
set -euo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
step() { printf '\n== %s\n' "$*"; }
note() { printf '   %s\n' "$*"; }

usage() {
    cat <<'EOF'
사용법: sudo bash 20-app-setup.sh <1|2> [옵션]

  1 은 APP01(192.168.10.21), 2 는 APP02(192.168.10.22)다.

옵션
  --origin URL             EDGE01이 사용자에게 보여줄 오리진. CORS와 WebSocket 허용 목록에 쓴다.
                           기본값은 정본 진입 오리진 https://192.168.56.11 이다. 다른 값을 주면
                           경고를 내고 그 값을 그대로 쓴다. prod는 이 값이 비면 기동을 멈추고,
                           localhost나 demo.exchange.local이 들어 있어도 멈춘다.
  --db-url URL             JDBC 접속 문자열. 기본값은 DB01 192.168.10.31의 verify-full 접속이다.
  --stage-dir DIR          준비물 위치. 기본 /home/ops/stage
  --release NAME           /opt/exchange/releases 아래 이름. 기본은 JAR 해시 앞 12자리
  --jar-sha256 HEX         app.jar.sha256 대신 해시를 직접 준다
  --node-exporter-version V   기본 1.12.1
  --node-exporter-sha256 HEX  기본값은 학교 설치에서 대조한 1.12.1 배포본 해시다.
                              다른 버전을 받으면 공식 목록에서 다시 대조하고 이 값을 준다.
  --force-node-exporter    이미 설치돼 있어도 바이너리와 유닛을 다시 깐다. 기본은
                           공통 기반 00-common-setup.sh 가 올린 것을 덮어쓰지 않는다.
  --generate-app-secrets   마스터키·페퍼·세션 시드를 새로 만든다. APP01에서 한 번만 쓰고
                           그 세 값을 APP02로 옮긴다. 두 노드의 값이 다르면 세션이 노드마다 갈린다.
  --edge-health            8081 허용 목록에 EDGE01(192.168.10.11/32)을 추가한다.
                           기본은 OPS01만 허용이다. 이 옵션을 쓰면 00-주소계획.md의 표를
                           먼저 고치고 근거를 남긴다.
  --keep-env               이미 있는 /etc/exchange/app.env 를 다시 만들지 않는다
  --skip-packages          dnf 설치를 건너뛴다
  --skip-jar               JAR 배치를 건너뛴다
  --no-start               준비가 끝나도 서비스를 켜지 않는다
  -h, --help               이 도움말
EOF
}

# ---------------------------------------------------------------- 인자 해석
node=
origin=
db_url=
stage_dir=/home/ops/stage
release=
jar_sha256=
ne_version=1.12.1
# 학교 설치(AWS_작업.md 268행)에서 공식 목록과 대조한 1.12.1 linux-amd64 배포본 해시다.
# 같은 버전을 받을 때만 재사용한다. 다른 버전이면 공식 다운로드 목록에서 다시 대조한다.
ne_sha256=b51d8a76aa2a9156a55d501aca6276fae09e262259a5e4e831d2c2222f084e63
force_node_exporter=false
generate_secrets=false
edge_health=false
keep_env=false
skip_packages=false
skip_jar=false
no_start=false

[[ $# -gt 0 ]] || { usage; exit 1; }
case "$1" in
    -h|--help) usage; exit 0 ;;
    1|2) node=$1; shift ;;
    *) usage; die "첫 인자는 노드 번호 1 또는 2 다." ;;
esac
while (($#)); do
    case "$1" in
        --origin) origin=${2:?}; shift 2 ;;
        --db-url) db_url=${2:?}; shift 2 ;;
        --stage-dir) stage_dir=${2:?}; shift 2 ;;
        --release) release=${2:?}; shift 2 ;;
        --jar-sha256) jar_sha256=${2:?}; shift 2 ;;
        --node-exporter-version) ne_version=${2:?}; shift 2 ;;
        --node-exporter-sha256) ne_sha256=${2:?}; shift 2 ;;
        --force-node-exporter) force_node_exporter=true; shift ;;
        --generate-app-secrets) generate_secrets=true; shift ;;
        --edge-health) edge_health=true; shift ;;
        --keep-env) keep_env=true; shift ;;
        --skip-packages) skip_packages=true; shift ;;
        --skip-jar) skip_jar=true; shift ;;
        --no-start) no_start=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) usage; die "모르는 인자: $1" ;;
    esac
done

# 02-정본결정.md 3절이 정한 정본 진입 오리진이다. 10-edge01-setup.sh 의 ENTRY_ORIGIN 과 같은
# 값이어야 한다. 이 값이 브라우저가 보내는 Origin 헤더이며 CORS와 WebSocket 검사 기준이 된다.
entry_origin=https://192.168.56.11

# 00-주소계획.md의 VM 배치 표에서 온 값이다.
name="app0${node}"
lan_ip="192.168.10.2${node}"
mgmt_ip="192.168.56.2${node}"
edge_cidr=192.168.10.11/32
ops_cidr=192.168.10.41/32
db_host=192.168.10.31
db_cert=/etc/exchange/db01.crt
# EDGE01 주소를 자바 정규식으로 적는다. RemoteIpValve의 internalProxies에 그대로 들어간다.
trusted_proxy='192\.168\.10\.11'
: "${db_url:=jdbc:postgresql://${db_host}:5432/exchange?sslmode=verify-full&sslrootcert=${db_cert}}"

secret_env=/etc/exchange/app-secret.env
app_env=/etc/exchange/app.env
# 저장소 deploy/linux/app/exchange-app.service 의 해시다. 유닛을 고치지 않고 그대로 쓴다는
# 것을 설치 시점에 확인한다. 값이 다르면 원본이 바뀌었거나 전송 중에 깨진 것이다.
unit_sha256=5ee44fb22544d1f51a1ff2cdec145b19996a565632b73a120da2cf01afbb1d0d

# ---------------------------------------------------------------- 1. 사전 확인
step "1. 대상 노드 확인"
[[ $(id -u) == 0 ]] || die "root로 실행한다. sudo bash $0 $node ..."
[[ $(cat /proc/1/comm) == systemd ]] || die "PID 1이 systemd가 아니다."

if [[ -f /etc/vm-lab-node ]]; then
    # kickstart가 남긴 역할 표식이다. 엉뚱한 VM에서 앱을 설치하는 사고를 막는다.
    declared_node=$(awk -F= '$1 == "NODE" { print $2 }' /etc/vm-lab-node)
    [[ $declared_node == "$name" ]] || die "이 호스트는 $declared_node 다. $name 에서 실행한다."
else
    # 02-정본결정.md 2절대로 설치는 01-create-vms.ps1 + ks/<이름>.ks 하나로만 한다.
    # 그 kickstart 는 이 표식을 반드시 남기므로, 없다면 다른 경로로 만든 VM이다.
    note "경고: /etc/vm-lab-node 가 없다. 정본 kickstart(ks/app0${node}.ks)로 만든 VM이 아니다."
    note "      역할 표식을 확인하지 못했으므로 아래 주소 확인만으로 대상을 판단한다."
    note "      어느 경로로 설치했는지 증거본에 적는다."
fi
ip -4 -o addr show | awk '{print $4}' | cut -d/ -f1 | grep -qx "$lan_ip" \
    || die "lab-lan 주소 $lan_ip 가 이 호스트에 없다. 대상 VM을 확인한다."
ip -4 -o addr show | awk '{print $4}' | cut -d/ -f1 | grep -qx "$mgmt_ip" \
    || die "host-only 주소 $mgmt_ip 가 이 호스트에 없다."
note "호스트 $name / lab-lan $lan_ip / host-only $mgmt_ip"

# --origin 을 생략하면 정본 진입 오리진을 쓴다. 값을 손으로 적다가 lab-lan 주소를 넣는 사고가
# 이 단계에서 가장 흔했으므로 기본값을 두고, 다른 값을 주면 이유를 묻는 경고를 낸다.
if ! $keep_env && [[ -z $origin ]]; then
    origin=$entry_origin
    note "--origin 을 주지 않았다. 정본 진입 오리진 $origin 을 쓴다."
fi
if [[ -n $origin ]]; then
    case "$origin" in
        *localhost*|*demo.exchange.local*)
            die "운영 오리진에 localhost나 demo.exchange.local을 쓸 수 없다. WebSocketConfig가 기동을 막는다." ;;
    esac
    if [[ $origin != "$entry_origin" ]]; then
        note "경고: 오리진이 정본과 다르다. 준 값 $origin / 정본 $entry_origin"
        note "      02-정본결정.md 3절은 https://192.168.56.11 하나를 정본으로 정했다."
        note "      브라우저가 보내는 Origin 이 이 값과 다르면 CORS와 WebSocket 검사에서 막힌다."
        note "      일부러 다른 값을 쓴다면 그 이유를 증거본에 적는다."
    else
        note "진입 오리진: $origin (정본과 같다)"
    fi
fi

enforce=$(getenforce 2>/dev/null || echo Unknown)
note "SELinux: $enforce"
[[ $enforce == Enforcing ]] || note "경고: Enforcing이 아니다. 끄지 말고 원인을 확인한다."
if systemctl is-active --quiet chronyd; then
    note "시간 동기: $(chronyc tracking 2>/dev/null | awk -F': *' '$1 ~ /^Leap status/ { print $2 }')"
else
    note "경고: chronyd가 꺼져 있다. 리스 만료 판단과 지표 시각 대조가 어긋난다."
fi

# ---------------------------------------------------------------- 2. 패키지
step "2. 패키지 설치"
if $skip_packages; then
    note "--skip-packages 로 건너뛴다."
else
    # curl은 적지 않는다. Rocky 10 minimal에는 curl-minimal이 이미 있고 /usr/bin/curl을 제공한다.
    # 여기서 curl을 요구하면 두 패키지가 서로 밀어내며 의존성 해결이 실패할 수 있다.
    #   java-21-openjdk-headless  앱 JAR 실행 런타임. build.gradle.kts의 toolchain 21과 맞춘다
    #   nftables                  inet vmlab 테이블에 역할 규칙을 얹고 검사하는 nft 명령
    #   policycoreutils-python-utils  semanage로 SELinux 포트·문맥을 확인하고 필요한 것만 추가
    #   unzip                     JAR에서 Flyway 라이브러리와 db/migration을 꺼낸다
    #   postgresql                psql 클라이언트. DB01 접속과 권한 확인용
    #   openssl                   비밀 값 생성
    dnf install -y \
        java-21-openjdk-headless nftables policycoreutils-python-utils \
        chrony iproute unzip tar postgresql python3 openssl
fi
command -v java >/dev/null || die "java가 없다."
note "java: $(java -version 2>&1 | head -1)"
for cmd in nft unzip psql openssl sha256sum; do
    command -v "$cmd" >/dev/null || die "$cmd 가 없다. 패키지 설치를 다시 확인한다."
done

# ---------------------------------------------------------------- 3. 방화벽 경로 정리
step "3. firewalld와 배포판 nftables 정리"
# 방화벽은 02-정본결정.md 4절대로 inet vmlab 한 계통으로만 운영한다. firewalld가 같이 돌면
# 어느 규칙이 막았는지 설명할 수 없고, 배포판 nftables.service는 /etc/sysconfig/nftables.conf 를
# 읽어 별도 테이블을 만든다. 둘 다 정책원에서 빼고 그 사실을 기록한다.
if systemctl list-unit-files --no-legend firewalld.service 2>/dev/null | grep -q firewalld; then
    systemctl disable --now firewalld.service >/dev/null 2>&1 || true
    note "firewalld: $(systemctl is-enabled firewalld.service 2>&1) / $(systemctl is-active firewalld.service 2>&1)"
else
    note "firewalld는 설치돼 있지 않다."
fi
# disable 로 다음 부팅에서 올라오지 않게 하고 mask 로 다른 유닛이 끌어올리지 못하게 막는다.
# --now 로 중지하지 않는 이유가 있다. 일부 버전의 nftables.service 는 ExecStop 에서 ruleset
# 전체를 비우므로, 지금 중지하면 vm-lab-firewall.service 가 세운 inet vmlab 규칙까지 같이
# 내려가 22번을 포함한 모든 허용이 사라진다. 이미 떠 있으면 재부팅 때 정리한다.
systemctl disable nftables.service >/dev/null 2>&1 || true
systemctl mask nftables.service >/dev/null 2>&1 || true
# is-active 는 비활성이면 3 을 낸다. bare assignment 라 그 값이 set -e 에 걸린다.
nft_state="$(systemctl is-enabled nftables.service 2>&1 || true) / $(systemctl is-active nftables.service 2>&1 || true)"
note "nftables.service: $nft_state (규칙은 vm-lab-firewall.service가 넣는다)"
if systemctl is-active --quiet nftables.service; then
    note "경고: nftables.service 가 아직 떠 있다. 지금 중지하면 ruleset 전체가 비워질 수 있으므로"
    note "      여기서는 중지하지 않는다. 재부팅한 뒤 systemctl is-enabled nftables.service 가"
    note "      masked 인지, nft list ruleset 에 inet vmlab 만 있는지 확인한다."
fi

# ---------------------------------------------------------------- 4. 계정과 경로
step "4. 서비스 계정과 경로"
if ! getent group exchange >/dev/null; then groupadd --system exchange; fi
if ! id exchange >/dev/null 2>&1; then
    # 로그인 불가 서비스 계정이다. 이 계정으로 SSH에 들어가지 않는다.
    useradd --system --gid exchange --no-create-home --shell /sbin/nologin exchange
fi
[[ $(getent passwd exchange | cut -d: -f7) == */nologin ]] || die "exchange 계정이 로그인 가능하다."
# /etc/exchange 와 /opt/exchange 는 root가 쓰고 exchange가 읽는다.
# /var/lib/exchange 는 유닛의 ReadWritePaths 이므로 exchange가 쓴다.
install -d -o root -g exchange -m 0750 /opt/exchange /opt/exchange/releases /etc/exchange
install -d -o exchange -g exchange -m 0750 /var/lib/exchange
note "$(ls -ld /opt/exchange /etc/exchange /var/lib/exchange | tr '\n' ' ')"

# ---------------------------------------------------------------- 5. 앱 JAR
step "5. 앱 JAR 배치"
if $skip_jar; then
    note "--skip-jar 로 건너뛴다."
    [[ -e /opt/exchange/current/app.jar ]] || die "건너뛰었는데 /opt/exchange/current/app.jar 가 없다."
else
    jar_src="$stage_dir/app.jar"
    [[ -f $jar_src ]] || die "JAR이 없다: $jar_src"
    if [[ -z $jar_sha256 && -f "$jar_src.sha256" ]]; then
        jar_sha256=$(awk 'NR == 1 { print $1 }' "$jar_src.sha256")
    fi
    [[ -n $jar_sha256 ]] || die "JAR 해시를 모른다. app.jar.sha256 을 올리거나 --jar-sha256 을 준다."
    # PowerShell의 Get-FileHash 는 대문자로 낸다. 대소문자 때문에 불일치로 보이지 않게 맞춘다.
    jar_sha256=${jar_sha256,,}
    actual=$(sha256sum "$jar_src" | awk '{print $1}')
    [[ $actual == "$jar_sha256" ]] || die "JAR 해시 불일치. 기대 $jar_sha256 / 실제 $actual"
    note "JAR SHA-256 일치: $actual"
    : "${release:=${actual:0:12}}"
    install -d -o root -g exchange -m 0750 "/opt/exchange/releases/$release"
    install -o root -g root -m 0644 "$jar_src" "/opt/exchange/releases/$release/app.jar"
    # -n 을 붙여 기존 current 링크를 따라 들어가지 않고 링크 자체를 바꾼다.
    ln -sfn "/opt/exchange/releases/$release" /opt/exchange/current
    note "배치: /opt/exchange/releases/$release/app.jar (current -> $release)"
fi
restorecon -R /opt/exchange >/dev/null 2>&1 || true

# ---------------------------------------------------------------- 6. app.env
step "6. /etc/exchange/app.env"
if $keep_env && [[ -f $app_env ]]; then
    note "--keep-env 로 기존 파일을 둔다."
else
    template="$stage_dir/build-files/app/app.env.vm.example"
    [[ -f $template ]] || die "원본이 없다: $template"
    # 치환값에 &, |, 역슬래시가 들어간다. JDBC URL의 &와 EXCHANGE_TRUSTED_PROXIES의
    # 자바 정규식이 그렇다. sed의 치환문과 bash 5.2 이후의 ${변수//찾기/바꾸기}는 둘 다
    # 바꿀 문자열 안의 &를 "찾은 문자열"로 해석한다. 그래서 문자 그대로 바꾸는 awk를 쓴다.
    # 값은 ENVIRON으로 넘긴다. awk -v 는 값 안의 역슬래시를 escape로 해석해 버린다.
    tmp_env=$(mktemp)
    LAN_IP="$lan_ip" DB_URL="$db_url" ORIGIN="$origin" TRUSTED_PROXY="$trusted_proxy" \
    awk '
    function rep(s, from, to,   out, p) {
        while ((p = index(s, from)) > 0) {
            out = out substr(s, 1, p - 1) to
            s = substr(s, p + length(from))
        }
        return out s
    }
    {
        line = $0
        line = rep(line, "__LAN_IP__", ENVIRON["LAN_IP"])
        line = rep(line, "__DB_URL__", ENVIRON["DB_URL"])
        line = rep(line, "__ORIGIN__", ENVIRON["ORIGIN"])
        line = rep(line, "__TRUSTED_PROXY__", ENVIRON["TRUSTED_PROXY"])
        print line
    }' "$template" > "$tmp_env"
    if grep -n '__[A-Z_]\+__' "$tmp_env"; then
        rm -f "$tmp_env"
        die "치환하지 않은 자리표시자가 남았다. 위 줄을 확인한다."
    fi
    install -o root -g exchange -m 0640 "$tmp_env" "$app_env"
    rm -f "$tmp_env"
    note "생성: $app_env (root:exchange 0640)"
fi
# URL 안에 = 가 또 있으므로 첫 = 뒤 전부를 값으로 읽는다.
# 주석 줄에도 키 이름이 나오므로 줄 앞에서만 찾는다.
note "DB 접속: $(awk '/^SPRING_DATASOURCE_URL=/ { sub(/^[^=]*=/, ""); print }' "$app_env")"

# ---------------------------------------------------------------- 7. 비밀 값
step "7. /etc/exchange/app-secret.env"
# 값은 만들거나 확인만 하고 절대 출력하지 않는다. 화면 캡처도 이 구간은 남기지 않는다.
if [[ ! -f $secret_env ]]; then
    # mktemp 는 0600으로 만든다. 그 상태로 채운 뒤 install 로 권한을 지정해 옮긴다.
    tmp_secret=$(mktemp)
    cat > "$tmp_secret" <<'EOF'
# 운영 비밀 값. root:root 0600 으로 둔다. 내용을 문서·로그·증거본에 남기지 않는다.
# SPRING_DATASOURCE_PASSWORD 는 DB01이 exchange_app 계정에 정한 값과 같아야 한다.
# 아래 세 값은 APP01과 APP02가 같아야 한다. 다르면 회원 세션과 비밀번호 검증이 노드마다 갈린다.
# prod는 세 값 중 하나라도 비면 StartupSecretValidator가 기동을 중단한다.
SPRING_DATASOURCE_PASSWORD=
EXCHANGE_AUTH_MASTER_KEY_BASE64=
EXCHANGE_MEMBER_PASSWORD_PEPPER_BASE64=
EXCHANGE_MEMBER_SESSION_SEED_BASE64=
EOF
    install -o root -g root -m 0600 "$tmp_secret" "$secret_env"
    rm -f "$tmp_secret"
    note "빈 뼈대를 만들었다. 값은 운영자가 채운다."
fi
chown root:root "$secret_env"
chmod 0600 "$secret_env"

if $generate_secrets; then
    # openssl rand -base64 32 로 256비트 값을 만든다. 표준출력으로 내보내지 않는다.
    for key in EXCHANGE_AUTH_MASTER_KEY_BASE64 EXCHANGE_MEMBER_PASSWORD_PEPPER_BASE64 EXCHANGE_MEMBER_SESSION_SEED_BASE64; do
        if grep -q "^${key}=.\+$" "$secret_env"; then
            note "$key 는 이미 값이 있다. 덮어쓰지 않는다."
            continue
        fi
        value=$(openssl rand -base64 32)
        # sed 치환 대신 파일을 다시 써서 값이 프로세스 인자에 남지 않게 한다.
        tmp_secret=$(mktemp); chmod 0600 "$tmp_secret"
        while IFS= read -r line; do
            if [[ $line == "${key}="* ]]; then printf '%s=%s\n' "$key" "$value"; else printf '%s\n' "$line"; fi
        done < "$secret_env" > "$tmp_secret"
        cat "$tmp_secret" > "$secret_env"
        rm -f "$tmp_secret"
        unset value
        note "$key 를 새로 만들었다. 값은 출력하지 않는다."
    done
    note "APP02에도 같은 세 값을 넣는다. 파일을 직접 옮기거나 값을 손으로 복사한다."
fi

secrets_ready=true
for key in SPRING_DATASOURCE_PASSWORD EXCHANGE_AUTH_MASTER_KEY_BASE64 \
           EXCHANGE_MEMBER_PASSWORD_PEPPER_BASE64 EXCHANGE_MEMBER_SESSION_SEED_BASE64; do
    if ! grep -q "^${key}=.\+$" "$secret_env"; then
        secrets_ready=false
        note "미입력: $key"
    fi
done
if $secrets_ready; then note "네 값이 모두 채워져 있다. 값은 출력하지 않는다."; fi

# ---------------------------------------------------------------- 8. systemd 유닛
step "8. exchange-app.service 설치"
unit_src="$stage_dir/build-files/app/exchange-app.service"
[[ -f $unit_src ]] || die "유닛 원본이 없다: $unit_src"
unit_actual=$(sha256sum "$unit_src" | awk '{print $1}')
if [[ $unit_actual != "$unit_sha256" ]]; then
    die "유닛 해시 불일치. 저장소 원본을 고치지 않고 그대로 쓴다. 기대 $unit_sha256 / 실제 $unit_actual"
fi
install -o root -g root -m 0644 "$unit_src" /etc/systemd/system/exchange-app.service
note "설치: /etc/systemd/system/exchange-app.service (저장소 원본과 동일)"

# ---------------------------------------------------------------- 9. node_exporter
step "9. node_exporter $ne_version"
ne_tar="$stage_dir/node_exporter-${ne_version}.linux-amd64.tar.gz"
# 공통 기반 scripts/00-common-setup.sh 가 node_exporter 를 이미 올렸을 수 있다. 그 경우
# 유닛과 리슨 주소를 덮어쓰지 않는다. 같은 자리를 두 스크립트가 서로 다른 내용으로 쓰면
# 어느 쪽이 적용됐는지 설명할 수 없다. 주소만 계획과 같은지 확인한다.
ne_managed_here=true
if ! $force_node_exporter && [[ -x /usr/local/bin/node_exporter && -f /etc/systemd/system/node_exporter.service && -f /etc/node_exporter/listen.env ]]; then
    ne_managed_here=false
    note "이미 설치돼 있다. 공통 기반이 올린 것으로 보고 유닛을 덮어쓰지 않는다."
    ne_bind=$(awk -F= '/^NODE_EXPORTER_ADDRESS=/ { print $2 }' /etc/node_exporter/listen.env)
    if [[ $ne_bind == "$lan_ip" ]]; then
        note "리슨 주소 $ne_bind 가 계획과 같다."
    else
        note "경고: 리슨 주소가 $ne_bind 다. 계획은 $lan_ip 이다. 어느 쪽이 맞는지 정하고 고친다."
    fi
    note "다시 깔려면 --force-node-exporter 를 준다."
elif [[ -x /usr/local/bin/node_exporter ]] && ! [[ -f $ne_tar ]]; then
    note "배포본이 없고 바이너리는 이미 있다. 설치를 건너뛴다."
else
    [[ -f $ne_tar ]] || die "배포본이 없다: $ne_tar"
    ne_actual=$(sha256sum "$ne_tar" | awk '{print $1}')
    ne_sha256=${ne_sha256,,}
    [[ $ne_actual == "$ne_sha256" ]] \
        || die "node_exporter 해시 불일치. 공식 다운로드 목록에서 다시 대조한다. 기대 $ne_sha256 / 실제 $ne_actual"
    note "배포본 SHA-256 일치: $ne_actual"
    ne_tmp=$(mktemp -d)
    tar -xzf "$ne_tar" -C "$ne_tmp"
    install -o root -g root -m 0755 "$ne_tmp/node_exporter-${ne_version}.linux-amd64/node_exporter" /usr/local/bin/node_exporter
    rm -rf "$ne_tmp"
    restorecon /usr/local/bin/node_exporter >/dev/null 2>&1 || true
fi
if $ne_managed_here; then
    if ! getent group node_exporter >/dev/null; then groupadd --system node_exporter; fi
    if ! id node_exporter >/dev/null 2>&1; then
        useradd --system --gid node_exporter --no-create-home --shell /sbin/nologin node_exporter
    fi
    install -d -o root -g root -m 0755 /etc/node_exporter
    # 유닛이 EnvironmentFile로 읽는 리슨 주소다. lab-lan에만 열고 접근은 nftables가 OPS01로 제한한다.
    printf 'NODE_EXPORTER_ADDRESS=%s\n' "$lan_ip" > /etc/node_exporter/listen.env
    chown root:root /etc/node_exporter/listen.env
    chmod 0644 /etc/node_exporter/listen.env
    ne_unit="$stage_dir/linux/observability/node_exporter-host.service"
    [[ -f $ne_unit ]] || die "유닛 원본이 없다: $ne_unit"
    # 원본의 systemd 수집 대상 목록에 exchange-app이 이미 들어 있어 앱 노드에서는 고칠 것이 없다.
    install -o root -g root -m 0644 "$ne_unit" /etc/systemd/system/node_exporter.service
    note "리슨 주소 $lan_ip:9100, 유닛 설치 완료"
fi

# ---------------------------------------------------------------- 10. 방화벽
step "10. nftables 규칙"
# 02-정본결정.md 4절대로 방화벽 계통은 하나다. scripts/00-common-setup.sh 가 만드는
# inet vmlab 테이블이 유일한 정책원이고, input 체인의 기본 정책은 drop 이며 22 와 9100 은
# 거기서 연다. 앱이 더 열 것은 8080 과 8081 둘뿐이므로 /etc/vm-lab/firewall.d/ 에 역할
# 파일 하나만 덧붙이고 table 과 chain 을 다시 선언하지 않는다.
#
# inet failstop 을 단독으로 만드는 예전 대체 경로는 지웠다. 그 경로는 정책이 accept 라
# 기본 차단이 아니었고, 두 계통이 함께 남으면 무엇이 막았는지 설명할 수 없다.
if [[ ! -f /etc/vm-lab/firewall.nft || ! -d /etc/vm-lab/firewall.d ]]; then
    die "공통 기반이 없다. 먼저 sudo bash 00-common-setup.sh 를 실행해 inet vmlab 테이블을 만든다.
     이 스크립트는 기본 차단 위에 8080·8081 역할 규칙만 얹으며 별도 테이블을 만들지 않는다."
fi
note "공통 기반(inet vmlab)을 찾았다. 역할 규칙만 덧붙인다."
role_nft=/etc/vm-lab/firewall.d/21-${name}.nft
{
    printf '%s\n' "# ${name} 역할 규칙. 생성: scripts/20-app-setup.sh"
    printf '%s\n' '# 기본 차단은 /etc/vm-lab/firewall.nft 가 이미 걸었다. 22 와 9100 도 거기서 연다.'
    printf '%s\n' '# 여기서는 8080 과 8081 만 연다. 출발지는 00-주소계획.md 의 포트 표에서 왔다.'
    printf '%s\n' "#   8080 API      $edge_cidr (EDGE01)"
    printf '%s\n' "#   8081 Actuator $ops_cidr (OPS01)$( $edge_health && printf '와 EDGE01' || true )"
    printf '%s\n' '#'
    printf '%s\n' '# 나가는 통신은 여기서 제한하지 않는다. 발신 제한은 pfSense 경계가 맡는다는 것이'
    printf '%s\n' '# 02-정본결정.md 4절의 결정이므로, 이 파일만 보고 아웃바운드를 확인했다고 적지 않는다.'
    printf '%s\n' '#'
    printf '%s\n' '# nftables 규칙 comment 는 128바이트 제한이 있어 짧게 쓴다. 긴 설명은 이 주석에만 적는다.'
    printf 'add rule inet vmlab roles ip saddr %s tcp dport 8080 ct state new counter accept comment "%s"\n' \
        "$edge_cidr" "APP API"
    printf 'add rule inet vmlab roles ip saddr %s tcp dport 8081 ct state new counter accept comment "%s"\n' \
        "$ops_cidr" "APP actuator"
    if $edge_health; then
        printf 'add rule inet vmlab roles ip saddr %s tcp dport 8081 ct state new counter accept comment "%s"\n' \
            "$edge_cidr" "EDGE readiness"
    fi
} > "$role_nft"
chown root:root "$role_nft"
chmod 0600 "$role_nft"
# add rule 은 테이블이 이미 있어야 하므로 역할 파일만 따로 검사할 수 없다.
# 공통 파일 전체를 검사한 뒤 reload 한다. 검사에 실패하면 역할 파일을 지우고 멈춘다.
if ! nft --check -f /etc/vm-lab/firewall.nft; then
    rm -f -- "$role_nft"
    die "규칙 검사 실패. 역할 파일을 지웠다. 위 출력을 보고 고친다."
fi
systemctl reload vm-lab-firewall.service
note "역할 파일: $role_nft (inet vmlab, 기본 차단 위)"
if $edge_health; then
    note "--edge-health: 8081 허용 목록에 EDGE01을 넣었다. 00-주소계획.md의 표도 고쳐야 한다."
fi
nft list table inet vmlab | sed 's/^/     /'
firewall_mode=vmlab

# ---------------------------------------------------------------- 11. 기동
step "11. 서비스 기동 준비"
note "방화벽 경로: $firewall_mode"
systemctl daemon-reload
if $ne_managed_here; then
    systemctl enable node_exporter.service >/dev/null
    systemctl restart node_exporter.service
fi
note "node_exporter: $(systemctl is-active node_exporter.service 2>&1)"

cert_ready=true
cert_path=$(awk -F'sslrootcert=' '/^SPRING_DATASOURCE_URL=/ && NF > 1 { print $2 }' "$app_env" | cut -d'&' -f1)
if [[ -n $cert_path ]]; then
    if [[ -f $cert_path ]]; then
        note "DB 인증서: $cert_path ($(sha256sum "$cert_path" | awk '{print substr($1,1,16)}')...)"
    else
        cert_ready=false
        note "없음: $cert_path . DB01의 서버 공개 인증서를 받아 지문을 대조하고 root:exchange 0644로 둔다."
    fi
fi

# JDBC 접속 문자열을 psql 이 쓰는 값으로 나눈다. app.env 의 SPRING_DATASOURCE_URL 은
#   jdbc:postgresql://호스트:포트/DB?sslmode=...&sslrootcert=...
# 형식이다. 결과는 아래 다섯 변수에 담는다. 형식이 다르면 1을 낸다.
pg_host=; pg_port=; pg_db=; pg_sslmode=; pg_sslrootcert=
parse_jdbc_url() {
    local url=$1 body query hostport kv
    [[ $url == jdbc:postgresql://* ]] || return 1
    body=${url#jdbc:postgresql://}
    query=""
    if [[ $body == *\?* ]]; then
        query=${body#*\?}
        body=${body%%\?*}
    fi
    [[ $body == */* ]] || return 1
    hostport=${body%%/*}
    pg_db=${body#*/}
    pg_host=${hostport%%:*}
    pg_port=5432
    if [[ $hostport == *:* ]]; then pg_port=${hostport##*:}; fi
    pg_sslmode=""
    pg_sslrootcert=""
    local old_ifs=$IFS
    IFS='&'
    for kv in $query; do
        case "$kv" in
            sslmode=*) pg_sslmode=${kv#sslmode=} ;;
            sslrootcert=*) pg_sslrootcert=${kv#sslrootcert=} ;;
        esac
    done
    IFS=$old_ifs
    [[ -n $pg_host && -n $pg_db ]] || return 1
    return 0
}

# DB01 에 직접 질의해 스키마가 적용됐는지 본다. 02-정본결정.md 9절의 결정이다.
# 21-app-migrate.sh 가 남기는 /etc/exchange/.migrated 표식은 APP01 안에만 있어서 APP02 에서는
# 볼 수 없다. 표식 대신 DB 를 보면 두 노드가 같은 사실을 같은 방법으로 확인한다.
# to_regclass 는 테이블 SELECT 권한이 없어도 존재 여부를 내므로 exchange_app 으로 확인할 수 있다.
# 반환값 0 적용됨 / 1 적용 안 됨 / 2 확인하지 못함
schema_detail=""
schema_state() {
    local pw out rc=0
    if ! command -v psql >/dev/null; then
        schema_detail="psql 이 없어 질의할 수 없다."
        return 2
    fi
    if ! parse_jdbc_url "$db_url"; then
        schema_detail="DB 접속 문자열을 해석하지 못했다: $db_url"
        return 2
    fi
    pw=$(awk '/^SPRING_DATASOURCE_PASSWORD=/ { sub(/^[^=]*=/, ""); print; exit }' "$secret_env")
    if [[ -z $pw ]]; then
        schema_detail="$secret_env 의 SPRING_DATASOURCE_PASSWORD 가 비어 있어 질의할 수 없다."
        return 2
    fi
    # 비밀번호는 환경변수로만 넘긴다. 인자로 주면 ps 출력에 남는다. 값은 출력하지 않는다.
    out=$(
        export PGPASSWORD="$pw" PGCONNECT_TIMEOUT=5
        if [[ -n $pg_sslmode ]]; then export PGSSLMODE="$pg_sslmode"; fi
        if [[ -n $pg_sslrootcert ]]; then export PGSSLROOTCERT="$pg_sslrootcert"; fi
        psql -h "$pg_host" -p "$pg_port" -d "$pg_db" -U exchange_app -w -Atq \
             -c "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL" 2>&1
    ) || rc=$?
    unset pw
    if [[ $rc != 0 ]]; then
        schema_detail="DB 질의 실패: $(printf '%s' "$out" | head -1)"
        return 2
    fi
    case "$out" in
        t) schema_detail="flyway_schema_history 가 있다."; return 0 ;;
        f) schema_detail="flyway_schema_history 가 없다. 스키마를 아직 적용하지 않았다."; return 1 ;;
        *) schema_detail="예상하지 못한 질의 결과: $out"; return 2 ;;
    esac
}

# enable을 미리 걸지 않는다. 비밀 값이나 인증서가 없는 상태로 부팅하면 prod가 기동을 중단하고
# StartLimitBurst 3에 걸려 서비스가 실패로 남는다. 준비가 끝난 뒤에 enable과 start를 함께 한다.
# 미완료 항목은 배열에 모아 전부 출력한다. 하나만 알려 주면 고친 뒤 또 다른 항목에 걸린다.
pending=()
if ! $secrets_ready; then
    pending+=("$secret_env 의 빈 값을 채운다. 지금 켜면 prod가 기동을 중단한다.")
fi
if ! $cert_ready; then
    pending+=("DB01의 서버 공개 인증서를 $cert_path 에 놓는다.")
fi

# 스키마 확인은 두 노드 모두 한다. APP02 를 APP01 보다 먼저 구축해도 스키마가 없는 DB 에
# 앱이 붙어 기동에 실패하는 일이 없게 한다.
step "11-1. 스키마 적용 확인 (APP01·APP02 공통)"
schema_rc=0
schema_state || schema_rc=$?
case $schema_rc in
    0) note "스키마 확인: $schema_detail" ;;
    1)
        note "스키마 미적용: $schema_detail"
        pending+=("APP01에서 스키마를 먼저 적용한다. DB01에서 30-db-primary-setup.sh migrate-open 으로 창을 연 뒤 APP01에서 21-app-migrate.sh migrate 를 실행하고, 끝나면 migrate-close 로 닫는다.")
        ;;
    *)
        note "스키마 확인 불가: $schema_detail"
        note "확인하지 못한 상태를 적용됨으로 보지 않는다. 기동은 사람이 확인한 뒤에 한다."
        pending+=("스키마 적용 여부를 확인하지 못했다. 위 사유를 해결하고 다시 실행하거나, DB01에서 psql -d exchange -c \"\\dt flyway_schema_history\" 로 직접 확인한다.")
        ;;
esac
if [[ $node == 1 ]]; then
    if [[ -f /etc/exchange/.migrated ]]; then
        note "참고: 이 노드에 /etc/exchange/.migrated 표식이 있다. 판정 기준은 위 DB 질의다."
    else
        note "참고: 이 노드에 /etc/exchange/.migrated 표식이 없다. 판정 기준은 위 DB 질의다."
    fi
fi

if ((${#pending[@]} > 0)); then
    for item in "${pending[@]}"; do
        note "[미완료] $item"
    done
    note "끝나면: sudo systemctl enable --now exchange-app"
elif $no_start; then
    systemctl enable exchange-app.service >/dev/null
    note "--no-start 로 enable만 했다. 켤 때는 sudo systemctl start exchange-app"
else
    systemctl enable exchange-app.service >/dev/null
    systemctl restart exchange-app.service
    sleep 5
    note "exchange-app: $(systemctl is-active exchange-app.service)"
    note "기동 로그: sudo journalctl -u exchange-app -n 50 --no-pager"
fi

step "끝. 다음을 직접 확인한다"
cat <<EOF
   ss -ltnp | grep -E ':(8080|8081|9100|22)\b'
   sudo nft list table inet vmlab
   sudo ausearch -m avc -ts recent
   sudo grep -i thread $app_env
   sudo systemctl is-enabled exchange-app node_exporter vm-lab-firewall
   sudo systemctl is-enabled nftables.service   (masked 여야 한다)
   sudo grep -E '^(EXCHANGE_CORS_ALLOWED_ORIGINS|APP_WS_ALLOWED_ORIGINS)=' $app_env
EOF
if [[ $node == 2 ]]; then
    note "APP02는 리더가 아니면 readiness가 503이어야 정상이다. 두 대가 동시에 200이면 안 된다."
fi
