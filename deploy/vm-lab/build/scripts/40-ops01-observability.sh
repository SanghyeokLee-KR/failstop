#!/usr/bin/env bash
#
# OPS01 관측 구축 스크립트. Rocky Linux 10.2 minimal 에서 root 로 실행한다.
#
#   sudo /opt/vm-lab/scripts/40-ops01-observability.sh preflight
#   sudo /opt/vm-lab/scripts/40-ops01-observability.sh all
#
# 하는 일은 네 가지다.
#   1. Prometheus 3.13.3, Alertmanager 0.31.1, node_exporter 1.12.1 을 공식 릴리스 tar 로 받고
#      SHA-256 을 대조한 뒤 /usr/local/bin 에 넣는다. Grafana 는 공식 RPM 저장소에서 설치한다.
#   2. 설정 파일을 배치하고 promtool·amtool·nft 로 구문을 먼저 검사한다.
#   3. systemd 서비스 네 개를 기동하고 부팅 시 자동 시작으로 둔다.
#   4. 바인딩 주소, 방화벽, 수집 대상 상태를 확인한다.
#
# 하지 않는 일도 분명히 해 둔다.
#   - Grafana 관리자 계정을 만들지 않는다. 비밀번호 입력을 이 스크립트의 로그에 남기지 않기 위해
#     build-files/ops/bootstrap-grafana-admin.sh 를 사용자가 따로 실행한다.
#   - Teams 웹훅과 보고 토큰 값을 만들지 않는다. 값은 사용자가 OPS01 에서 직접 넣는다.
#   - 다른 VM 의 node_exporter, DB 의 postgres_exporter, 앱의 Actuator 설정을 건드리지 않는다.
#     그 대상들이 준비되기 전까지 수집 상태가 down 인 것은 예상된 상태다.
#
# 이 스크립트는 절차이며 검증 결과가 아니다. 아직 OPS01 에서 실행한 적이 없다.
# 실행한 뒤에는 출력 그대로를 증거로 남긴다. 기대 출력과 다르면 그 자리에서 멈추고 원인을 적는다.
#
# 설정과 주소의 근거는 build/00-주소계획.md 와
# 아키텍쳐/교수님_보고본/06_VM_운영환경 의 구성도·접근제어도다.

set -euo pipefail

# ---------------------------------------------------------------------------
# 기본값. 주소는 00-주소계획.md 의 OPS01 행에서만 가져온다.
# ---------------------------------------------------------------------------
SCRIPT_NAME=$(basename -- "${BASH_SOURCE[0]}")
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
BUILD_ROOT=$(cd -- "$SCRIPT_DIR/.." && pwd)
# 설치 원본은 build-files/ops 를 먼저 보고, 없으면 설계 단계 사본인 scripts/ops01 을 본다.
OPS_FILES="$BUILD_ROOT/build-files/ops"
SPEC_FILES="$BUILD_ROOT/scripts/ops01"

LAN_ADDRESS=192.168.10.41       # enp0s8. 지표 수집이 오가는 주소
MGMT_ADDRESS=192.168.56.41      # enp0s9. 관리 SSH 와 화면 터널이 들어오는 주소
LAN_INTERFACE=enp0s8
MGMT_INTERFACE=enp0s9
ADMIN_CIDR=192.168.56.0/24      # 관리 대역. 방화벽의 SSH 허용 출발지

PROMETHEUS_VERSION=3.13.3
ALERTMANAGER_VERSION=0.31.1
NODE_EXPORTER_VERSION=1.12.1
# node_exporter 만 저장소에 기록된 값이 있다(docs/reports/AWS_작업.md 의 SHA-256 표).
# 공식 목록의 값이 이 값과 다르면 파일이 바뀐 것이므로 그 자리에서 멈춘다.
NODE_EXPORTER_SHA256=b51d8a76aa2a9156a55d501aca6276fae09e262259a5e4e831d2c2222f084e63
# Prometheus 와 Alertmanager 는 저장소에 기록한 해시가 없다. 공식 sha256sums.txt 대조까지만 한다.
PROMETHEUS_SHA256=
ALERTMANAGER_SHA256=

PROMETHEUS_URL_BASE=https://github.com/prometheus/prometheus/releases/download
ALERTMANAGER_URL_BASE=https://github.com/prometheus/alertmanager/releases/download
NODE_EXPORTER_URL_BASE=https://github.com/prometheus/node_exporter/releases/download

LOG_DIR=/var/log/vm-lab
SECRET_WEBHOOK=/etc/failstop/teams-webhook
SECRET_TOKEN=/etc/failstop/reporter-token

# 설치에 쓰는 패키지. 이미 들어 있는 것도 있으므로 설치 전후를 구분해 남긴다.
PACKAGES=(nftables chrony tar gzip python3 policycoreutils-python-utils audit)

FAILURES=0

# ---------------------------------------------------------------------------
# 출력 도우미. 색을 쓰지 않는다. 증거 화면과 로그에서 같은 모양으로 읽히게 한다.
# ---------------------------------------------------------------------------
step()  { printf '\n=== %s ===\n' "$*"; }
info()  { printf '  %s\n' "$*"; }
ok()    { printf '  [확인] %s\n' "$*"; }
warn()  { printf '  [주의] %s\n' "$*"; }
fail()  { printf '  [실패] %s\n' "$*"; FAILURES=$((FAILURES + 1)); }
die()   { printf '\n[중단] %s\n' "$*" >&2; exit 1; }

usage() {
    cat <<'EOF'
사용법: sudo 40-ops01-observability.sh <단계>

단계
  preflight      OS, SELinux, 시각, 주소, 자원, 설치 원본 파일을 확인한다. 아무것도 바꾸지 않는다.
  packages       dnf 로 필요한 패키지를 설치한다.
  accounts       prometheus, node_exporter, alertmanager 서비스 계정을 만든다(로그인 불가).
  prometheus     Prometheus 와 promtool 설치, 수집 설정·경보 규칙 배치, 기동.
  exporter       node_exporter 설치와 기동. 주소는 192.168.10.41:9100.
  firewall       nftables 규칙 검사 후 적용. SSH 가 끊길 수 있으므로 콘솔을 열어 두고 실행한다.
  grafana        Grafana RPM 설치, 설정·데이터원본·화면 프로비저닝, 기동.
  alertmanager   Alertmanager 설치. 처음에는 전송 설정이 없는 hold 설정으로 기동한다.
  all            위 여덟 단계를 순서대로 실행한다.
  connect        비밀 파일 두 개를 확인하고 Alertmanager 를 최종 설정으로 바꾼다.
  verify         기동 상태, 바인딩, 수집 대상, 방화벽, 메모리, SELinux 거부를 확인한다.

이 스크립트가 하지 않는 두 가지
  Grafana 관리자 생성   sudo bash BUILD/build-files/ops/bootstrap-grafana-admin.sh 를 직접 실행한다.
  비밀 값 입력          /etc/failstop/teams-webhook 과 /etc/failstop/reporter-token 을 직접 채운다.

출력은 /var/log/vm-lab/40-ops01-<단계>.log 에도 남는다.
EOF
}

# ---------------------------------------------------------------------------
# 공통 확인 도우미
# ---------------------------------------------------------------------------
require_root() {
    [[ $(id -u) -eq 0 ]] || die "root 로 실행한다. sudo $SCRIPT_NAME <단계>"
}

# 설정 파일 소유 그룹으로 쓰는 계정이 있어야 배치가 된다. 단계를 따로 실행할 때를 위한 확인이다.
require_account() {
    local account=$1
    id "$account" >/dev/null 2>&1 \
        || die "$account 계정이 없다. 먼저 sudo $SCRIPT_NAME accounts 를 실행한다."
}

# 설치 원본 파일을 찾는다. build-files/ops 를 먼저 보고 scripts/ops01 을 나중에 본다.
find_file() {
    local name=$1 dir
    for dir in "$OPS_FILES" "$SPEC_FILES"; do
        if [[ -f "$dir/$name" ]]; then
            printf '%s\n' "$dir/$name"
            return 0
        fi
    done
    die "설치 원본을 찾지 못했다: $name (찾은 곳: $OPS_FILES, $SPEC_FILES)"
}

# 경보 규칙 파일은 이름이 두 가지다. 보고 역할이 만든 alerts.vm.yml 이 있으면 그것이 정본이고,
# 없으면 관측 역할의 vm-alerts.yml 을 쓴다. 둘 다 /etc/prometheus/vm-alerts.yml 로 설치된다.
find_rules_file() {
    if [[ -f "$OPS_FILES/alerts.vm.yml" ]]; then
        printf '%s\n' "$OPS_FILES/alerts.vm.yml"
    else
        find_file vm-alerts.yml
    fi
}

# HTTP 응답을 기다린다. 기동 직후에는 몇 초가 걸린다.
wait_for_http() {
    local url=$1 tries=${2:-30} attempt
    for ((attempt = 1; attempt <= tries; attempt++)); do
        if curl -fsS --max-time 2 "$url" >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    return 1
}

# Prometheus 질의. 식에 특수문자가 많으므로 URL 인코딩은 curl 에 맡긴다.
promquery() {
    curl -fsS --max-time 5 -G http://127.0.0.1:9090/api/v1/query --data-urlencode "query=$1"
}

# 질의 결과에서 첫 값 하나만 꺼낸다. jq 는 minimal 환경에 없으므로 python3 을 쓴다.
promvalue() {
    promquery "$1" | python3 -c '
import json, sys
result = json.load(sys.stdin)["data"]["result"]
print(result[0]["value"][1] if result else "")
'
}

# 공식 릴리스를 받아 SHA-256 을 대조하고 풀어 놓은 경로를 출력한다.
# 파일 이름이 같다는 이유로 검사를 건너뛰지 않는다.
fetch_release() {
    local project=$1 version=$2 url_base=$3 pinned=$4
    local asset="${project}-${version}.linux-amd64.tar.gz"
    local base="${url_base}/v${version}"
    local work="$WORKDIR/$project"

    install -d -m 0700 "$work"
    info "내려받기 $base/$asset" >&2
    curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 \
        "$base/$asset" -o "$work/$asset"
    curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 \
        "$base/sha256sums.txt" -o "$work/sha256sums.txt"

    grep -F " $asset" "$work/sha256sums.txt" > "$work/checksum" \
        || die "공식 목록에서 $asset 줄을 찾지 못했다."
    [[ $(wc -l < "$work/checksum") -eq 1 ]] \
        || die "공식 목록에서 $asset 줄이 하나가 아니다. 목록을 직접 확인한다."

    if [[ -n $pinned ]]; then
        local listed
        listed=$(awk '{print $1}' "$work/checksum")
        [[ $listed == "$pinned" ]] \
            || die "공식 목록의 SHA-256 이 저장소 기록과 다르다. 기록 $pinned, 목록 $listed"
    fi

    ( cd "$work" && sha256sum -c checksum ) >&2
    tar -xzf "$work/$asset" -C "$work"
    printf '%s\n' "$work/${project}-${version}.linux-amd64"
}

# 이미 같은 버전이 들어 있으면 다시 받지 않는다. 다시 받으려면 FORCE_DOWNLOAD=1 로 실행한다.
binary_version_matches() {
    local path=$1 version=$2
    [[ -x $path ]] || return 1
    [[ ${FORCE_DOWNLOAD:-0} != 1 ]] || return 1
    "$path" --version 2>&1 | grep -qF "$version"
}

# ---------------------------------------------------------------------------
# 1. 사전 확인. 이 단계는 시스템을 바꾸지 않는다.
# ---------------------------------------------------------------------------
stage_preflight() {
    step "사전 확인"

    if [[ -r /etc/rocky-release ]]; then
        info "$(cat /etc/rocky-release)"
    else
        warn "/etc/rocky-release 가 없다. Rocky Linux 가 아닌 곳에서 실행하는지 확인한다."
    fi
    info "호스트명 $(hostname)"

    # 역할이 다른 VM 에 관측 도구를 올리지 않는다. 구성도의 역할 분리를 임의로 합치지 않는다.
    if [[ -r /etc/vm-lab/role ]]; then
        local role
        role=$(tr -d '[:space:]' < /etc/vm-lab/role)
        if [[ $role == ops01 ]]; then
            ok "역할 표시 /etc/vm-lab/role = ops01"
        elif [[ ${OPS01_ALLOW_ROLE_MISMATCH:-0} == 1 ]]; then
            warn "역할 표시가 $role 인데 OPS01_ALLOW_ROLE_MISMATCH=1 로 계속한다."
        else
            die "이 VM 의 역할 표시는 $role 이다. OPS01 이 아닌 곳에 관측 도구를 올리지 않는다."
        fi
    else
        warn "/etc/vm-lab/role 이 없다. ops01.ks 로 설치한 VM 인지 직접 확인한다."
    fi

    # SELinux 는 끄지 않는다. 막히는 동작은 문맥·포트를 더해 푼다.
    local selinux_mode
    selinux_mode=$(getenforce 2>/dev/null || echo unknown)
    case "$selinux_mode" in
        Enforcing) ok "SELinux Enforcing" ;;
        Permissive) warn "SELinux 가 Permissive 다. 거부가 로그에만 남고 차단되지 않는다." ;;
        *) die "SELinux 상태가 $selinux_mode 다. Enforcing 으로 두고 다시 실행한다." ;;
    esac

    # 수집 시각이 어긋나면 지표와 경보 시각을 믿을 수 없다. 도구를 올리기 전에 맞춘다.
    if systemctl is-active --quiet chronyd; then
        ok "chronyd 실행 중"
        chronyc tracking 2>/dev/null | sed -n '1,4p' | sed 's/^/    /' || true
    else
        die "chronyd 가 꺼져 있다. sudo systemctl enable --now chronyd 후 다시 실행한다."
    fi

    step "주소와 자원"
    ip -brief address show | sed 's/^/  /'
    if ip -brief address show "$LAN_INTERFACE" 2>/dev/null | grep -q "$LAN_ADDRESS/"; then
        ok "$LAN_INTERFACE = $LAN_ADDRESS"
    else
        fail "$LAN_INTERFACE 에 $LAN_ADDRESS 가 없다. 이 주소로 지표를 수집한다."
    fi
    if ip -brief address show "$MGMT_INTERFACE" 2>/dev/null | grep -q "$MGMT_ADDRESS/"; then
        ok "$MGMT_INTERFACE = $MGMT_ADDRESS"
    else
        fail "$MGMT_INTERFACE 에 $MGMT_ADDRESS 가 없다. SSH 와 화면 터널이 이 주소로 들어온다."
    fi
    free -m | sed 's/^/  /'
    df -h / /var/log /var/lib 2>/dev/null | sed 's/^/  /'

    step "설치 원본 확인"
    local name path
    for name in prometheus.vm.yml prometheus.service node_exporter.ops01.service \
                ops01-firewall.nft vm-lab-firewall.service \
                grafana.repo grafana.ini grafana-limits.conf grafana-datasource.yml \
                grafana-dashboards.vm.yml grafana-msp-dashboards.vm.yml \
                linux-dashboard.vm.json bootstrap-grafana-admin.sh \
                alertmanager.hold.yml alertmanager.vm.yml alertmanager.vm.service; do
        path=$(find_file "$name")
        info "$name <- $path"
    done
    path=$(find_rules_file)
    info "경보 규칙 <- $path"
    local dashboards
    dashboards=$(find "$SPEC_FILES/msp-dashboards-vm" -maxdepth 1 -name '*.json' 2>/dev/null | wc -l)
    if [[ $dashboards -eq 7 ]]; then
        ok "MSP 화면 JSON 7개"
    else
        fail "MSP 화면 JSON 이 ${dashboards}개다. 7개여야 한다. make-vm-dashboards.py 로 다시 만든다."
    fi

    # 02-정본결정.md 8절. 패널 쿼리의 role 정규식에 edge 와 observability 가 들어 있어야
    # EDGE01 과 OPS01 이 화면에 나온다. 옛 사본을 그대로 설치하면 두 대가 어느 패널에도 없다.
    local stale_dashboards
    # grep 은 일치가 없으면 1 을 돌려주고 pipefail 이 그 값을 파이프라인의 값으로 삼는다.
    # 대입에 그대로 두면 set -e 가 '옛 정규식이 하나도 없다' 는 정상 상태에서 스크립트를 죽인다.
    stale_dashboards=$(grep -rl 'role=~\\"app|reader|data|standby|probe\\"' \
        "$SPEC_FILES/msp-dashboards-vm" 2>/dev/null | wc -l) || stale_dashboards=0
    if [[ ${stale_dashboards:-0} -eq 0 ]]; then
        ok "MSP 화면의 role 정규식에 edge·observability 포함"
    else
        fail "MSP 화면 ${stale_dashboards}개에 옛 role 정규식이 남아 있다. EDGE01 과 OPS01 이 나오지 않는다."
        warn "scripts/ops01/make-vm-dashboards.py 로 7개를 다시 만든 뒤 이 단계를 다시 실행한다."
    fi

    # 같은 내용이 두 곳에 있으면 한쪽만 고치는 사고가 난다. 차이가 있으면 알린다.
    # 주석을 뺀 본문의 해시를 비교한다. diffutils 가 없는 minimal 환경도 있어 coreutils 만 쓴다.
    same_content() {
        local left=$1 right=$2
        [[ $(grep -v '^[[:space:]]*#' "$left" | sha256sum | awk '{print $1}') \
            == $(grep -v '^[[:space:]]*#' "$right" | sha256sum | awk '{print $1}') ]]
    }
    if [[ -f "$OPS_FILES/prometheus.vm.yml" && -f "$SPEC_FILES/prometheus.yml" ]]; then
        if same_content "$OPS_FILES/prometheus.vm.yml" "$SPEC_FILES/prometheus.yml"; then
            ok "prometheus 설정 사본 두 개의 내용이 같다."
        else
            warn "build-files/ops/prometheus.vm.yml 과 scripts/ops01/prometheus.yml 의 내용이 다르다."
            warn "설치에 쓰는 것은 build-files/ops/prometheus.vm.yml 이다. 두 파일을 맞춘다."
        fi
    fi
    if [[ -f "$OPS_FILES/alerts.vm.yml" && -f "$SPEC_FILES/vm-alerts.yml" ]]; then
        if same_content "$OPS_FILES/alerts.vm.yml" "$SPEC_FILES/vm-alerts.yml"; then
            ok "경보 규칙 사본 두 개의 내용이 같다."
        else
            warn "build-files/ops/alerts.vm.yml 과 scripts/ops01/vm-alerts.yml 의 내용이 다르다."
            warn "설치에 쓰는 것은 build-files/ops/alerts.vm.yml 이다. 규칙을 대조한다."
        fi
    fi
    # Alertmanager 설정 사본도 같은 방식으로 본다. 비교 대상에서 빠져 있으면 조용히 갈라진다.
    if [[ -f "$OPS_FILES/alertmanager.vm.yml" && -f "$SPEC_FILES/alertmanager.vm.yml" ]]; then
        if same_content "$OPS_FILES/alertmanager.vm.yml" "$SPEC_FILES/alertmanager.vm.yml"; then
            ok "Alertmanager 설정 사본 두 개의 내용이 같다."
        else
            warn "build-files/ops/alertmanager.vm.yml 과 scripts/ops01/alertmanager.vm.yml 의 내용이 다르다."
            warn "설치에 쓰는 것은 build-files/ops/alertmanager.vm.yml 이다. 수신자와 경로를 대조한다."
        fi
    fi

    # 02-정본결정.md 4절대로 방화벽 계통은 inet vmlab 하나다.
    # 설정은 /etc/vm-lab/firewall.nft, 유닛은 vm-lab-firewall.service, 기본 정책은 drop 이다.
    step "방화벽 정책원 확인"
    if systemctl is-enabled --quiet nftables.service 2>/dev/null; then
        warn "nftables.service 가 켜져 있다. 이 서비스는 정지할 때 ruleset 을 비운다."
        warn "firewall 단계에서 이 서비스를 끄고 vm-lab-firewall.service 하나만 남긴다."
    else
        ok "nftables.service 꺼짐. 규칙은 vm-lab-firewall.service 가 넣는다."
    fi
    if nft list table inet failstop >/dev/null 2>&1; then
        warn "예전 계통 table inet failstop 이 남아 있다. 정책원이 둘이면 어느 테이블이 막았는지 읽기 어렵다."
        warn "확인 뒤 sudo nft delete table inet failstop 으로 지우고 inet vmlab 하나만 남긴다."
    fi
    if [[ -f /etc/vm-lab/firewall.nft ]]; then
        ok "공통 절차 00-common-setup.sh 의 기본 차단 정책이 있다. firewall 단계는 규칙을 더하지 않는다."
    else
        warn "공통 절차의 기본 차단 정책이 없다. firewall 단계가 이 스크립트의 ops01-firewall.nft 를 같은 자리에 넣는다."
        warn "그 파일도 table inet vmlab 에 policy drop 이지만, 공통 절차를 먼저 적용하는 쪽이 여섯 대의 규칙을 한 곳에서 관리한다."
    fi
    if [[ -f /etc/systemd/system/node_exporter.service ]]; then
        info "기존 node_exporter 유닛이 있다. exporter 단계는 이 유닛을 그대로 둔다."
        grep -m1 '^ExecStart=' /etc/systemd/system/node_exporter.service | sed 's/^/    /'
    else
        info "node_exporter 유닛이 없다. exporter 단계가 $LAN_ADDRESS:9100 으로 올린다."
    fi
    # 02-정본결정.md 7절. 여섯 대 모두 lab-lan 주소에 바인딩한다. OPS01 만 루프백으로 두던 분기는 없앴다.
    if [[ -f /etc/node_exporter/listen.env ]]; then
        if grep -q "^NODE_EXPORTER_ADDRESS=$LAN_ADDRESS$" /etc/node_exporter/listen.env; then
            ok "listen.env NODE_EXPORTER_ADDRESS=$LAN_ADDRESS"
        else
            warn "listen.env 의 주소가 $LAN_ADDRESS 가 아니다. 7절은 여섯 대 모두 lab-lan 바인딩으로 정했다."
            sed 's/^/    /' /etc/node_exporter/listen.env
        fi
    fi
    if systemctl is-active --quiet firewalld 2>/dev/null; then
        fail "firewalld 가 돌고 있다. 정책원을 둘로 두지 않는다. disable 후 다시 실행한다."
    else
        ok "firewalld 비활성"
    fi

    step "사전 확인 결과"
    if [[ $FAILURES -eq 0 ]]; then
        info "실패 항목 없음. 다음 단계로 간다."
    else
        die "실패 $FAILURES 건. 위 항목을 먼저 고친다."
    fi
}

# ---------------------------------------------------------------------------
# 2. 패키지
# ---------------------------------------------------------------------------
stage_packages() {
    step "패키지 설치"
    local package
    info "설치 전 상태"
    for package in "${PACKAGES[@]}"; do
        if rpm -q "$package" >/dev/null 2>&1; then
            info "  이미 있음 $(rpm -q "$package")"
        else
            info "  없음      $package"
        fi
    done

    dnf install -y "${PACKAGES[@]}"

    info "설치 후 상태"
    for package in "${PACKAGES[@]}"; do
        info "  $(rpm -q "$package")"
    done
    # curl 은 minimal 환경의 curl-minimal 이 제공한다. 따로 설치하면 충돌한다.
    info "curl 제공 패키지 $(rpm -qf "$(command -v curl)" 2>/dev/null || echo '확인 실패')"
}

# ---------------------------------------------------------------------------
# 3. 서비스 계정. 세 계정 모두 로그인할 수 없다. Grafana 계정은 RPM 이 만든다.
# ---------------------------------------------------------------------------
stage_accounts() {
    step "서비스 계정"
    local account
    for account in prometheus node_exporter alertmanager; do
        if id "$account" >/dev/null 2>&1; then
            info "이미 있음 $(id "$account")"
        else
            useradd --system --no-create-home --shell /sbin/nologin "$account"
            ok "생성 $(id "$account")"
        fi
        # 로그인 셸이 nologin 인지 확인한다. 기존 계정이 있었다면 용도를 먼저 확인한다.
        if ! getent passwd "$account" | grep -q '/sbin/nologin'; then
            warn "$account 의 셸이 /sbin/nologin 이 아니다. 용도를 확인한다."
        fi
    done
}

# ---------------------------------------------------------------------------
# 4. Prometheus
# ---------------------------------------------------------------------------
stage_prometheus() {
    require_account prometheus
    step "Prometheus $PROMETHEUS_VERSION 설치"
    if binary_version_matches /usr/local/bin/prometheus "$PROMETHEUS_VERSION"; then
        info "같은 버전이 이미 있다. 내려받기를 건너뛴다. 다시 받으려면 FORCE_DOWNLOAD=1 로 실행한다."
    else
        local unpacked
        unpacked=$(fetch_release prometheus "$PROMETHEUS_VERSION" "$PROMETHEUS_URL_BASE" "$PROMETHEUS_SHA256")
        install -o root -g root -m 0755 "$unpacked/prometheus" /usr/local/bin/prometheus
        install -o root -g root -m 0755 "$unpacked/promtool" /usr/local/bin/promtool
        # /usr/local/bin 의 파일은 bin_t 문맥을 받아야 서비스가 실행할 수 있다.
        restorecon -v /usr/local/bin/prometheus /usr/local/bin/promtool || true
    fi
    /usr/local/bin/prometheus --version 2>&1 | sed 's/^/  /'

    step "수집 설정과 경보 규칙"
    local config rules
    config=$(find_file prometheus.vm.yml)
    rules=$(find_rules_file)
    install -d -o root -g prometheus -m 0750 /etc/prometheus
    install -o root -g prometheus -m 0640 "$config" /etc/prometheus/prometheus.yml
    install -o root -g prometheus -m 0640 "$rules" /etc/prometheus/vm-alerts.yml
    info "설정 <- $config"
    info "규칙 <- $rules"

    # 구문이 틀린 설정으로 기동하지 않는다. 검사를 통과해야 다음으로 간다.
    /usr/local/bin/promtool check config /etc/prometheus/prometheus.yml
    /usr/local/bin/promtool check rules /etc/prometheus/vm-alerts.yml

    step "Prometheus 기동"
    install -o root -g root -m 0644 "$(find_file prometheus.service)" \
        /etc/systemd/system/prometheus.service
    systemctl daemon-reload
    systemctl enable --now prometheus
    if wait_for_http http://127.0.0.1:9090/-/ready 60; then
        ok "$(curl -fsS http://127.0.0.1:9090/-/ready)"
    else
        fail "Prometheus 가 준비 상태가 되지 않았다. journalctl -u prometheus -n 50 을 본다."
    fi
    # 보존은 7일과 2GiB 중 먼저 닿는 조건이 이긴다. 7일 전부를 보관한다는 뜻이 아니다.
    info "보존 7일·2GiB, 메모리 상한 512M (prometheus.service 의 값 그대로)"
    info "이 시점에는 Alertmanager 가 아직 없어 알림 대상이 down 으로 보인다. 정상이다."
}

# ---------------------------------------------------------------------------
# 5. node_exporter. OPS01 자신의 호스트 지표를 다른 VM 과 같은 모양으로 낸다.
# ---------------------------------------------------------------------------
stage_exporter() {
    require_account node_exporter
    step "node_exporter $NODE_EXPORTER_VERSION 설치"
    if binary_version_matches /usr/local/bin/node_exporter "$NODE_EXPORTER_VERSION"; then
        info "같은 버전이 이미 있다. 내려받기를 건너뛴다."
    else
        local unpacked
        unpacked=$(fetch_release node_exporter "$NODE_EXPORTER_VERSION" \
            "$NODE_EXPORTER_URL_BASE" "$NODE_EXPORTER_SHA256")
        install -o root -g root -m 0755 "$unpacked/node_exporter" /usr/local/bin/node_exporter
        restorecon -v /usr/local/bin/node_exporter || true
    fi
    /usr/local/bin/node_exporter --version 2>&1 | sed -n '1p' | sed 's/^/  /'

    # 공통 절차 00-common-setup.sh 가 이미 node_exporter 를 올렸을 수 있다.
    # 02-정본결정.md 7절대로 두 절차의 바인딩은 같다. 여섯 대 모두 lab-lan 주소이며
    # 주소는 /etc/node_exporter/listen.env 에서 읽는다. OPS01 만 루프백으로 두던 분기는 없앴다.
    # 그래서 기존 유닛이 있으면 그대로 두고, 아래에서 실제 바인딩만 확인한다.
    if [[ -f /etc/systemd/system/node_exporter.service && ${OPS01_REPLACE_NODE_EXPORTER:-0} != 1 ]]; then
        info "이미 node_exporter 유닛이 있다. 그대로 둔다."
        grep -m1 '^ExecStart=' /etc/systemd/system/node_exporter.service | sed 's/^/    /'
        info "이 유닛을 이 스크립트의 것으로 바꾸려면 OPS01_REPLACE_NODE_EXPORTER=1 로 실행한다."
        systemctl enable --now node_exporter
    else
        # 주소를 서비스 파일에 박지 않고 환경 파일로 뺀다. 다른 VM 은 이 값만 바꾼다.
        install -d -o root -g root -m 0755 /etc/node_exporter
        printf 'NODE_EXPORTER_ADDRESS=%s\n' "$LAN_ADDRESS" > /etc/node_exporter/listen.env
        chmod 0644 /etc/node_exporter/listen.env
        info "listen.env NODE_EXPORTER_ADDRESS=$LAN_ADDRESS"

        install -o root -g root -m 0644 "$(find_file node_exporter.ops01.service)" \
            /etc/systemd/system/node_exporter.service
        systemctl daemon-reload
        systemctl enable --now node_exporter
    fi

    # 실제로 어느 주소에 붙었는지 확인하고, 수집 설정의 자기 대상과 맞춘다.
    local endpoint
    endpoint=$(exporter_endpoint)
    if [[ -z $endpoint ]]; then
        fail "9100 을 듣는 프로세스가 없다. journalctl -u node_exporter -n 50 을 본다."
        return 0
    fi
    if wait_for_http "http://$endpoint/metrics" 20; then
        ok "node_exporter 응답 $endpoint"
    else
        fail "node_exporter 가 $endpoint 에서 응답하지 않는다."
    fi
    check_self_target "$endpoint"
}

# 로컬 node_exporter 가 실제로 듣고 있는 주소를 돌려준다.
# 02-정본결정.md 7절대로 정본은 lab-lan 주소 하나다. 루프백이 나오면 바인딩이 잘못된 것이다.
exporter_endpoint() {
    ss -lntH 2>/dev/null | awk '{print $4}' | grep -E ':9100$' | head -1
}

# 수집 설정의 OPS01 자기 대상과 실제 바인딩이 같은지 본다.
# 02-정본결정.md 7절이 여섯 대 모두 lab-lan 바인딩으로 정했으므로 정본 값은 하나다.
# 예전에는 바인딩이 루프백이면 설치본 prometheus.yml 을 루프백으로 고쳐 넘어갔다.
# 그 처리는 잘못된 바인딩을 수집 설정으로 덮어 감추므로 없앴다. 여기서는 사실만 알리고
# 고치는 자리는 listen.env 와 node_exporter 유닛으로 되돌린다.
check_self_target() {
    local endpoint=$1 config=/etc/prometheus/prometheus.yml
    local lan_target="$LAN_ADDRESS:9100"

    if [[ $endpoint != "$lan_target" ]]; then
        fail "node_exporter 가 $endpoint 에 붙어 있다. 7절이 정한 정본은 $lan_target 이다."
        warn "고치는 곳은 수집 설정이 아니라 바인딩이다."
        warn "  echo NODE_EXPORTER_ADDRESS=$LAN_ADDRESS | sudo tee /etc/node_exporter/listen.env"
        warn "  sudo systemctl restart node_exporter"
        return 0
    fi
    ok "node_exporter 바인딩이 7절의 정본 $lan_target 과 같다"

    [[ -f $config ]] || return 0
    if grep -q "'$lan_target'" "$config"; then
        ok "수집 설정의 ops01 대상도 $lan_target 이다"
    else
        fail "설치된 $config 에 ops01 대상 $lan_target 이 없다."
        warn "build-files/ops/prometheus.vm.yml 의 linux-host 작업을 확인하고 config 단계를 다시 실행한다."
    fi
}

# ---------------------------------------------------------------------------
# 6. 방화벽. SSH 가 끊길 수 있으므로 VirtualBox 콘솔을 열어 둔 채로 실행한다.
# ---------------------------------------------------------------------------
stage_firewall() {
    step "방화벽 규칙"

    # 02-정본결정.md 4절대로 계통은 하나다. table inet vmlab, /etc/vm-lab/firewall.nft,
    # vm-lab-firewall.service, input 기본 정책 drop, 역할별 규칙은 /etc/vm-lab/firewall.d/*.nft 다.
    # 공통 절차 00-common-setup.sh 가 이미 그 정책을 넣었는지 먼저 본다.
    # OPS01 은 기본 차단만으로 충분하므로 공통 기반이 있으면 아무것도 더하지 않는다.
    #   - 관제 포트 3000·9090·9093 은 루프백 바인딩이고 기본 정책이 drop 이다.
    #   - 자기 수집기 9100 도 같은 이유로 밖에서 닿지 않는다.
    #   - Prometheus 가 다른 VM 을 읽는 것은 나가는 연결이라 응답이 established 로 돌아온다.
    if [[ -f /etc/vm-lab/firewall.nft && ${OPS01_FORCE_OWN_FIREWALL:-0} != 1 ]]; then
        info "공통 절차의 기본 차단 정책이 이미 있다. 이 스크립트는 규칙을 더하지 않는다."
        systemctl is-active vm-lab-firewall.service >/dev/null 2>&1 \
            && ok "vm-lab-firewall.service active" \
            || warn "vm-lab-firewall.service 가 active 가 아니다. 공통 절차를 먼저 확인한다."
        nft list table inet vmlab 2>/dev/null | sed 's/^/  /' \
            || warn "table inet vmlab 을 읽지 못했다."
        info "OPS01 에 더 열 포트는 없다. 필요하면 /etc/vm-lab/firewall.d/ 에 add rule 로 더한다."
        info "이 스크립트의 규칙을 굳이 쓰려면 OPS01_FORCE_OWN_FIREWALL=1 로 실행한다."
        return 0
    fi

    local rules
    rules=$(find_file ops01-firewall.nft)

    # 적용 전에 관리 SSH 허용 줄이 있는지 확인한다. 이 줄이 없으면 스스로를 잠글 수 있다.
    if ! grep -q "ip saddr $ADMIN_CIDR tcp dport 22" "$rules"; then
        die "규칙 파일에 관리 대역 $ADMIN_CIDR 의 22번 허용 줄이 없다. 적용하지 않는다."
    fi
    ok "관리 SSH 허용 줄 확인 ($ADMIN_CIDR -> 22)"

    # 02-정본결정.md 4절의 형식인지 본다. 테이블 이름이나 기본 정책이 다르면 계통이 갈라진다.
    if ! grep -q '^table inet vmlab {' "$rules"; then
        die "규칙 파일이 table inet vmlab 형식이 아니다. 4절이 정한 계통 하나만 쓴다."
    fi
    if ! grep -q 'hook input priority 0; policy drop;' "$rules"; then
        die "규칙 파일의 input 기본 정책이 drop 이 아니다. 4절대로 기본 차단이어야 한다."
    fi
    ok "table inet vmlab · input policy drop 확인"

    # 구문 검사만 하고 적용하지 않는 단계다. 여기서 틀리면 서비스 기동에서 전부 실패한다.
    # include 대상 디렉터리가 없으면 구문 검사에서 걸리므로 먼저 만든다.
    install -d -o root -g root -m 0755 /etc/vm-lab /etc/vm-lab/firewall.d
    nft --check --file "$rules"
    ok "nft --check 통과"

    # 4절이 정한 자리에 넣는다. 공통 절차가 쓰는 경로와 같으므로 나중에 공통 절차를 적용해도
    # 같은 파일과 같은 유닛이 갱신될 뿐 계통이 둘로 갈라지지 않는다.
    install -o root -g root -m 0600 "$rules" /etc/vm-lab/firewall.nft
    install -o root -g root -m 0644 "$(find_file vm-lab-firewall.service)" \
        /etc/systemd/system/vm-lab-firewall.service

    # 정책원을 하나로 둔다. nftables.service 는 정지할 때 ruleset 전체를 비우므로 함께 쓰지 않는다.
    if systemctl is-enabled --quiet nftables.service 2>/dev/null; then
        systemctl disable --now nftables.service || true
        systemctl mask nftables.service || true
        warn "nftables.service 를 끄고 mask 했다. 규칙은 vm-lab-firewall.service 가 넣는다."
    fi
    # 예전 계통이 남아 있으면 같은 hook 에 테이블이 둘 선다. 적용 전에 지운다.
    if nft list table inet failstop >/dev/null 2>&1; then
        nft delete table inet failstop || true
        warn "예전 계통 table inet failstop 을 지웠다. 4절대로 inet vmlab 하나만 남긴다."
    fi

    warn "지금 규칙을 적용한다. 관리 대역 밖에서 접속 중이면 세션이 끊긴다."
    systemctl daemon-reload
    systemctl enable vm-lab-firewall.service
    systemctl restart vm-lab-firewall.service
    nft list table inet vmlab | sed 's/^/  /'
}

# ---------------------------------------------------------------------------
# 7. Grafana. 화면은 127.0.0.1:3000 에만 뜨고 SSH 터널로만 연다.
# ---------------------------------------------------------------------------
stage_grafana() {
    step "Grafana 설치"
    # 저장소 정의는 GPG 서명 검사를 켠 상태다. 검사를 끄고 설치하지 않는다.
    install -o root -g root -m 0644 "$(find_file grafana.repo)" /etc/yum.repos.d/grafana.repo
    dnf install -y grafana
    info "$(rpm -q grafana)"

    # RPM 이 만든 기본 설정을 남겨 둔다. 무엇을 바꿨는지 나중에 대조할 수 있다.
    if [[ -f /etc/grafana/grafana.ini && ! -f /etc/grafana/grafana.ini.rpm-original ]]; then
        cp -a /etc/grafana/grafana.ini /etc/grafana/grafana.ini.rpm-original
        info "기본 설정을 /etc/grafana/grafana.ini.rpm-original 로 보관했다."
    fi

    install -o root -g grafana -m 0640 "$(find_file grafana.ini)" /etc/grafana/grafana.ini
    # 학교에서 겪은 OOM 과 회수 지연의 최종값이다. GOMEMLIMIT 512MiB, MemoryHigh 768M, MemoryMax 896M.
    install -d -o root -g root -m 0755 /etc/systemd/system/grafana-server.service.d
    install -o root -g root -m 0644 "$(find_file grafana-limits.conf)" \
        /etc/systemd/system/grafana-server.service.d/limits.conf

    step "데이터 원본과 화면 프로비저닝"
    install -d -o root -g grafana -m 0750 /etc/grafana/provisioning/datasources \
        /etc/grafana/provisioning/dashboards
    install -o root -g grafana -m 0640 "$(find_file grafana-datasource.yml)" \
        /etc/grafana/provisioning/datasources/prometheus.yml
    install -o root -g grafana -m 0640 "$(find_file grafana-dashboards.vm.yml)" \
        /etc/grafana/provisioning/dashboards/vm-linux.yml
    install -o root -g grafana -m 0640 "$(find_file grafana-msp-dashboards.vm.yml)" \
        /etc/grafana/provisioning/dashboards/vm-msp.yml

    install -d -o root -g grafana -m 0750 /var/lib/grafana/dashboards /var/lib/grafana/msp-dashboards
    # Linux 화면은 원본이 아니라 VM 사본을 넣는다. 원본은 모든 식이 job="linux" 를 고르는데
    # VM 은 여섯 대를 job="linux-host" 로 수집하므로 원본을 그대로 넣으면 패널이 전부 빈다.
    # 사본을 만드는 방법은 build-files/ops/make-vm-linux-dashboard.py 의 머리말에 있다.
    install -o root -g grafana -m 0640 "$(find_file linux-dashboard.vm.json)" \
        /var/lib/grafana/dashboards/linux-dashboard.json

    # MSP 화면 7개는 environment 저장값을 vm-lab 으로 바꾼 변환본이다.
    # 원본(aws)을 그대로 두면 화면을 열었을 때 대상이 하나도 잡히지 않는다.
    local dashboard count=0
    for dashboard in "$SPEC_FILES"/msp-dashboards-vm/*.json; do
        [[ -f $dashboard ]] || die "MSP 화면 JSON 을 찾지 못했다: $SPEC_FILES/msp-dashboards-vm"
        install -o root -g grafana -m 0640 "$dashboard" /var/lib/grafana/msp-dashboards/
        count=$((count + 1))
    done
    ok "MSP 화면 ${count}개 배치"
    # 파일을 직접 만들어 넣었으므로 문맥을 되돌린다. enforcing 에서 읽기 실패를 막는다.
    restorecon -R /etc/grafana /var/lib/grafana || true

    step "Grafana 기동"
    systemctl daemon-reload
    systemctl enable --now grafana-server
    if wait_for_http http://127.0.0.1:3000/api/health 60; then
        curl -fsS http://127.0.0.1:3000/api/health | sed 's/^/  /'
    else
        fail "Grafana 가 응답하지 않는다. journalctl -u grafana-server -n 50 을 본다."
    fi
    info "관리자 계정은 이 스크립트가 만들지 않는다. 비밀번호 입력을 로그에 남기지 않기 위해서다."
    info "  sudo bash $OPS_FILES/bootstrap-grafana-admin.sh"
}

# ---------------------------------------------------------------------------
# 8. Alertmanager. 처음에는 밖으로 아무것도 보내지 않는 hold 설정으로 올린다.
# ---------------------------------------------------------------------------
stage_alertmanager() {
    require_account alertmanager
    step "Alertmanager $ALERTMANAGER_VERSION 설치"
    if binary_version_matches /usr/local/bin/alertmanager "$ALERTMANAGER_VERSION"; then
        info "같은 버전이 이미 있다. 내려받기를 건너뛴다."
    else
        local unpacked
        unpacked=$(fetch_release alertmanager "$ALERTMANAGER_VERSION" \
            "$ALERTMANAGER_URL_BASE" "$ALERTMANAGER_SHA256")
        install -o root -g root -m 0755 "$unpacked/alertmanager" /usr/local/bin/alertmanager
        install -o root -g root -m 0755 "$unpacked/amtool" /usr/local/bin/amtool
        restorecon -v /usr/local/bin/alertmanager /usr/local/bin/amtool || true
    fi
    /usr/local/bin/alertmanager --version 2>&1 | sed -n '1p' | sed 's/^/  /'

    install -d -o root -g root -m 0755 /etc/failstop
    if [[ -f /etc/failstop/alertmanager.yml ]] && \
       ! grep -q 'name: hold' /etc/failstop/alertmanager.yml; then
        # 이미 최종 설정이 들어 있으면 hold 로 되돌리지 않는다.
        info "기존 /etc/failstop/alertmanager.yml 을 그대로 둔다. hold 설정으로 덮지 않는다."
    else
        install -o root -g alertmanager -m 0640 "$(find_file alertmanager.hold.yml)" \
            /etc/failstop/alertmanager.yml
        info "전송 설정이 없는 hold 설정을 배치했다. 경보가 밖으로 나가지 않는다."
    fi
    /usr/local/bin/amtool check-config /etc/failstop/alertmanager.yml

    install -o root -g root -m 0644 "$(find_file alertmanager.vm.service)" \
        /etc/systemd/system/alertmanager.service
    systemctl daemon-reload
    systemctl enable --now alertmanager
    if wait_for_http http://127.0.0.1:9093/-/ready 30; then
        ok "Alertmanager 준비됨 127.0.0.1:9093"
    else
        fail "Alertmanager 가 준비되지 않았다. journalctl -u alertmanager -n 50 을 본다."
    fi
    info "Teams 전송을 붙이려면 비밀 파일 두 개를 만든 뒤 connect 단계를 실행한다."
}

# ---------------------------------------------------------------------------
# 9. 전송 설정 연결. 비밀 값은 만들지 않고 존재와 권한만 확인한다.
# ---------------------------------------------------------------------------
stage_connect() {
    step "Alertmanager 전송 설정 연결"
    local secret
    for secret in "$SECRET_WEBHOOK" "$SECRET_TOKEN"; do
        if [[ ! -s $secret ]]; then
            cat <<EOF

[중단] $secret 이 없거나 비어 있다. 값은 이 스크립트가 만들지 않는다.
OPS01 에서 직접 넣는다. 값 자체는 화면 캡처와 로그에 남기지 않는다.

  sudo install -o root -g alertmanager -m 0640 /dev/null $secret
  sudo tee $secret >/dev/null   # 값을 붙여 넣고 Ctrl-D

teams-webhook 은 Teams 채널의 수신 커넥터 주소, reporter-token 은 보고 서비스의 Bearer 토큰이다.
EOF
            exit 1
        fi
        # 값은 읽지 않고 소유자와 권한만 맞춘다.
        chown root:alertmanager "$secret"
        chmod 0640 "$secret"
        ok "$(stat -c '%n %U:%G %a' "$secret")"
    done

    # 보고 서비스가 아직 없으면 evidence-reporter 수신자는 계속 실패한다. 기본 Teams 알림은 나간다.
    if ss -lntH 2>/dev/null | awk '{print $4}' | grep -qx '127.0.0.1:9088'; then
        ok "보고 서비스가 127.0.0.1:9088 에서 듣고 있다."
    else
        warn "127.0.0.1:9088 이 열려 있지 않다. 보고 역할을 설치하기 전까지 그 수신자는 실패한다."
        warn "보고 역할의 실제 바인딩 주소와 포트를 alertmanager.vm.yml 과 대조한다."
    fi

    install -o root -g alertmanager -m 0640 "$(find_file alertmanager.vm.yml)" \
        /etc/failstop/alertmanager.yml
    /usr/local/bin/amtool check-config /etc/failstop/alertmanager.yml
    systemctl restart alertmanager
    if wait_for_http http://127.0.0.1:9093/-/ready 30; then
        ok "최종 설정으로 다시 기동했다."
    else
        fail "기동에 실패했다. journalctl -u alertmanager -n 50 을 본다."
    fi
}

# ---------------------------------------------------------------------------
# 10. 검증. 기대 결과와 다르면 실패로 센다.
# ---------------------------------------------------------------------------
check_listen() {
    local endpoint=$1 label=$2
    if ss -lntH | awk '{print $4}' | grep -qx "$endpoint"; then
        ok "$label $endpoint"
    else
        fail "$label 가 $endpoint 에 없다."
    fi
}

stage_verify() {
    step "서비스 상태"
    local unit
    for unit in prometheus grafana-server alertmanager node_exporter; do
        if systemctl is-active --quiet "$unit"; then
            ok "$unit active"
        else
            fail "$unit 이 active 가 아니다. systemctl status $unit 을 본다."
        fi
    done

    step "바인딩 주소"
    ss -lntp | sed 's/^/  /'
    check_listen 127.0.0.1:9090 Prometheus
    check_listen 127.0.0.1:3000 Grafana
    check_listen 127.0.0.1:9093 Alertmanager
    # node_exporter 주소는 02-정본결정.md 7절대로 lab-lan 하나다. 여섯 대가 같다.
    # 루프백 바인딩은 통과가 아니라 고칠 대상이다. 수집 설정도 같은 주소를 봐야 한다.
    local endpoint
    endpoint=$(exporter_endpoint)
    case "$endpoint" in
        "$LAN_ADDRESS:9100") ok "node_exporter $endpoint" ;;
        '') fail "9100 을 듣는 프로세스가 없다." ;;
        127.0.0.1:9100)
            fail "node_exporter 가 루프백에 붙어 있다. 7절이 정한 정본은 $LAN_ADDRESS:9100 이다."
            warn "/etc/node_exporter/listen.env 의 NODE_EXPORTER_ADDRESS 를 $LAN_ADDRESS 로 고치고 다시 시작한다."
            ;;
        *) fail "node_exporter 가 예상 밖의 주소 $endpoint 에 붙어 있다." ;;
    esac
    if ! grep -q "'$LAN_ADDRESS:9100'" /etc/prometheus/prometheus.yml; then
        fail "수집 설정에 ops01 대상 $LAN_ADDRESS:9100 이 없다. config 단계를 다시 실행해 맞춘다."
    fi
    # 관제 포트가 모든 주소로 열리면 SSH 터널 전제가 깨진다.
    if ss -lntH | awk '{print $4}' | grep -qE '^(0\.0\.0\.0|\*|\[::\]):(3000|9090|9093|9100)$'; then
        fail "관측 포트 중 하나가 모든 주소에 열려 있다. 위 ss 출력에서 해당 줄을 찾는다."
    else
        ok "0.0.0.0 바인딩 없음"
    fi

    step "설정 구문"
    if /usr/local/bin/promtool check config /etc/prometheus/prometheus.yml >/dev/null 2>&1; then
        ok "promtool check config SUCCESS"
    else
        fail "promtool check config 실패"
    fi
    if /usr/local/bin/amtool check-config /etc/failstop/alertmanager.yml >/dev/null 2>&1; then
        ok "amtool check-config 통과"
    else
        fail "amtool check-config 실패"
    fi

    step "수집 상태"
    if curl -fsS --max-time 3 http://127.0.0.1:9090/-/ready >/dev/null; then
        ok "$(curl -fsS http://127.0.0.1:9090/-/ready)"
    else
        fail "Prometheus /-/ready 실패"
    fi
    # count(up) 은 down 대상도 센다. 응답한 대상(up == 1)을 설치본에 실제로 걸린 대상 수와 비교한다.
    local expected healthy
    expected=$(curl -fsS --max-time 5 'http://127.0.0.1:9090/api/v1/targets?state=active' | python3 -c '
import json, sys
print(len(json.load(sys.stdin)["data"]["activeTargets"]))
' 2>/dev/null || true)
    healthy=$(promvalue 'count(up == 1)' || true)
    healthy=${healthy:-0}
    if [[ -z $expected || $expected == 0 ]]; then
        fail "수집 대상 목록을 읽지 못했다. /api/v1/targets 를 직접 본다."
    elif [[ $healthy == "$expected" ]]; then
        ok "수집 대상 ${expected}개 모두 응답 (up == 1)"
    else
        warn "응답한 대상 ${healthy}/${expected}. 아래 목록의 down 대상을 본다. 여섯 VM 이 모두 켜져 있어야 전부 응답한다."
    fi
    info "응답하지 않는 대상"
    promquery 'up == 0' | python3 -c '
import json, sys
result = json.load(sys.stdin)["data"]["result"]
if not result:
    print("    없음")
for series in result:
    metric = series["metric"]
    print("    down", metric.get("job", "?"), metric.get("instance", "?"), metric.get("node", ""))
' || warn "질의 실패"
    info "노드별 수집기 성공 여부 (HTTP 수집 성공과 수집기별 성공은 다르다)"
    promquery 'min by(node) (node_scrape_collector_success{job="linux-host"})' | python3 -c '
import json, sys
result = json.load(sys.stdin)["data"]["result"]
if not result:
    print("    결과 없음")
for series in result:
    print("   ", series["metric"].get("node", "?"), series["value"][1])
' || warn "질의 실패"

    step "Grafana"
    if curl -fsS --max-time 3 http://127.0.0.1:3000/api/health | grep -q '"database": *"ok"'; then
        ok "api/health database ok"
    else
        fail "Grafana health 확인 실패"
    fi
    local memory_max
    memory_max=$(systemctl show grafana-server -p MemoryMax --value || true)
    if [[ $memory_max == 939524096 ]]; then
        ok "MemoryMax=$memory_max (896M)"
    else
        fail "MemoryMax 가 $memory_max 다. limits.conf 드롭인이 적용되지 않았다."
    fi
    info "현재 사용량 $(systemctl show grafana-server -p MemoryCurrent --value)"

    step "방화벽"
    # 02-정본결정.md 4절대로 계통은 table inet vmlab 하나다.
    # 공통 절차가 넣었는지 이 스크립트의 firewall 단계가 넣었는지와 무관하게 이름과 경로가 같다.
    if nft list table inet vmlab >/dev/null 2>&1; then
        info "방화벽 테이블 (table inet vmlab)"
        nft list table inet vmlab | sed 's/^/  /'
        if nft list chain inet vmlab input | grep -q 'policy drop'; then
            ok "input 기본 정책 drop"
        else
            fail "input 기본 정책이 drop 이 아니다. 4절대로 기본 차단이어야 한다."
        fi
    else
        fail "table inet vmlab 이 없다. 공통 절차 또는 firewall 단계를 실행한다."
    fi
    # 예전 계통이 같이 서 있으면 어느 테이블이 막았는지 읽기 어렵다.
    if nft list table inet failstop >/dev/null 2>&1; then
        fail "예전 계통 table inet failstop 이 아직 있다. sudo nft delete table inet failstop 으로 지운다."
    else
        ok "예전 계통 table inet failstop 없음"
    fi
    if [[ $(systemctl is-enabled nftables.service 2>/dev/null) == enabled ]]; then
        fail "배포판 nftables.service 가 켜져 있다. 두 번째 정책원이 된다. disable 후 mask 한다."
    else
        ok "배포판 nftables.service 비활성 ($(systemctl is-enabled nftables.service 2>&1 | head -1))"
    fi

    step "커널 메모리 회수와 SELinux"
    local oom
    oom=$(journalctl -k --since -1h 2>/dev/null | grep -i -E 'oom|killed process' || true)
    if [[ -z $oom ]]; then
        ok "최근 1시간 OOM 기록 없음"
    else
        fail "OOM 기록이 있다"
        printf '%s\n' "$oom" | sed 's/^/    /'
    fi
    local denials
    denials=$(ausearch -m AVC -ts recent 2>/dev/null | grep -c 'type=AVC' || true)
    if [[ ${denials:-0} -eq 0 ]]; then
        ok "최근 SELinux 거부 없음"
    else
        fail "SELinux 거부 ${denials}건. ausearch -m AVC -ts recent 로 내용을 본다."
    fi

    step "이 스크립트가 확인하지 못하는 것"
    cat <<EOF
  1. 다른 VM 에서의 차단 확인. EDGE01 같은 곳에서 아래 두 명령이 모두 실패해야 한다.
       curl --max-time 3 http://$LAN_ADDRESS:9090/-/ready
       curl --max-time 3 http://$LAN_ADDRESS:9100/metrics
  2. 재부팅 후 상태. sudo reboot 뒤에 이 verify 를 다시 실행한다. enable 만으로 끝내지 않는다.
  3. 경보 경로. EDGE01 에서 node_exporter 를 멈추고 1분 뒤 아래를 확인한 다음 되돌린다.
       curl -fsS http://127.0.0.1:9090/api/v1/alerts
       curl -fsS http://127.0.0.1:9093/api/v2/alerts
  4. 화면 7개의 패널 값. 거래가 없어 비는 패널은 비어 있는 그대로 둔다.
     02-정본결정.md 8절대로 role 정규식에 edge 와 observability 를 넣었으므로
     호스트 목록 패널에 EDGE01 과 OPS01 도 나와야 한다. 두 대가 없으면 화면이 옛 사본이다.
     다시 만드는 명령은 scripts/ops01/make-vm-dashboards.py 의 머리말에 있다.
EOF

    step "검증 결과"
    if [[ $FAILURES -eq 0 ]]; then
        info "실패 항목 없음. 위 네 가지는 따로 확인한다."
    else
        printf '  실패 %s 건. 위 [실패] 줄을 확인한다.\n' "$FAILURES"
        return 1
    fi
}

# ---------------------------------------------------------------------------
# 진입점
# ---------------------------------------------------------------------------
main() {
    local stage=${1:-}
    case "$stage" in
        ''|-h|--help|help) usage; exit 0 ;;
    esac
    require_root

    # 작업 기록을 남긴다. 비밀 값은 어느 단계에서도 출력하지 않는다.
    install -d -o root -g root -m 0755 "$LOG_DIR"
    local log_file="$LOG_DIR/40-ops01-${stage}.log"
    exec > >(tee -a "$log_file") 2>&1
    printf '=== %s %s 시작 %s ===\n' "$SCRIPT_NAME" "$stage" "$(date -Is)"

    # 내려받기와 압축 풀기에 쓸 작업 공간. 끝나면 지운다.
    WORKDIR=$(mktemp -d /tmp/ops01-observability.XXXXXX)
    trap 'rm -rf -- "$WORKDIR"' EXIT

    case "$stage" in
        preflight)    stage_preflight ;;
        packages)     stage_packages ;;
        accounts)     stage_accounts ;;
        prometheus)   stage_prometheus ;;
        exporter)     stage_exporter ;;
        firewall)     stage_firewall ;;
        grafana)      stage_grafana ;;
        alertmanager) stage_alertmanager ;;
        connect)      stage_connect ;;
        # verify 는 실패를 세고 1 을 돌려준다. 여기서 바로 끝내지 않고 아래 요약까지 출력한다.
        verify)       stage_verify || true ;;
        all)
            stage_preflight
            stage_packages
            stage_accounts
            stage_prometheus
            stage_exporter
            stage_firewall
            stage_grafana
            stage_alertmanager
            step "남은 수동 작업"
            cat <<EOF
  1. Grafana 관리자 1명 생성. 아이디와 비밀번호는 직접 입력하고 캡처하지 않는다.
       sudo bash $OPS_FILES/bootstrap-grafana-admin.sh
  2. 비밀 파일 두 개를 만든 뒤 전송 설정 연결.
       sudo $SCRIPT_NAME connect
  3. 작업 PC 에서 터널을 열고 화면 확인.
       ssh -N -L 3000:127.0.0.1:3000 -L 9090:127.0.0.1:9090 ops@$MGMT_ADDRESS
  4. 검증과 재부팅 검증.
       sudo $SCRIPT_NAME verify
EOF
            ;;
        *) usage; die "모르는 단계: $stage" ;;
    esac

    printf '\n=== %s %s 끝 %s (실패 %s 건) ===\n' \
        "$SCRIPT_NAME" "$stage" "$(date -Is)" "$FAILURES"
    printf '기록: %s\n' "$log_file"
    [[ $FAILURES -eq 0 ]]
}

main "$@"
