#!/usr/bin/env bash
# VM 실습 공통 기반 설정. 여섯 대(edge01, app01, app02, db01, db02, ops01)에서 같은 파일을 쓴다.
#
# 무엇을 하는가
#   1. 운영에 필요한 패키지를 채운다(sysstat, tcpdump, nmap-ncat, bind-utils, logrotate, python3).
#   2. firewalld 와 배포판 nftables.service 를 disable 하고 mask 한다.
#      정책원을 vm-lab-firewall.service 한 곳으로 모은다.
#   3. journald 영구 저장과 보존 한도, /var/log/vm-lab 의 logrotate 를 건다.
#   4. nftables 기본 차단 정책을 /etc/vm-lab/firewall.nft 에 두고 vm-lab-firewall.service 로 올린다.
#   5. node_exporter 를 설치하고 그 VM의 lab-lan 주소에만 바인딩한다. 여섯 대가 같은 방식이다.
#   6. 5분 주기 호스트 점검(check-host.sh + vm-lab-host-check.timer)을 건다.
#   7. sshd 강화 설정과 sudo 파일이 계획대로 있는지 확인하고 없으면 채운다.
#
# 무엇을 하지 않는가
#   - 역할별 서비스(NGINX, Spring Boot, PostgreSQL, Prometheus, Grafana, Alertmanager)는 건드리지 않는다.
#     역할별 포트는 /etc/vm-lab/firewall.d/*.nft 로 각 역할 절차가 덧붙인다.
#   - 나가는 통신을 제한하지 않는다. 발신 제한은 pfSense 가 맡는다.
#     따라서 이 스크립트를 돌린 것만으로 '모든 통신이 방화벽을 지난다'고 적지 않는다.
#   - 네트워크 주소를 새로 잡지 않는다. kickstart 가 잡은 주소가 계획과 같은지 확인만 한다.
#
# 사용법
#   bash 00-common-setup.sh --plan                       바꿀 내용만 출력한다. root 가 아니어도 된다.
#   sudo bash 00-common-setup.sh --apply                 실제로 적용한다.
#   sudo bash 00-common-setup.sh --apply --node-exporter-tarball /tmp/node_exporter-1.12.1.linux-amd64.tar.gz
#   sudo bash 00-common-setup.sh --apply --download      공식 릴리스를 직접 받아 해시를 대조한다.
#
# 이 스크립트는 멱등하다. 같은 내용이면 파일을 건드리지 않고 서비스도 다시 시작하지 않는다.
# 실제로 바뀐 항목만 changed= 로 출력한다.
#
# 이 파일은 절차이며 검증 결과가 아니다. 여기 적힌 기대 상태는 직접 확인하기 전까지 통과로 적지 않는다.

set -euo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

# --- 입력 검사 도우미 ------------------------------------------------------
# failstop/deploy/linux/common.sh 에서 그대로 가져왔다. /0 CIDR 거절과
# 0 으로 시작하는 8진수 방어가 이미 들어 있어 같은 기준을 쓴다.
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
warn() { printf 'WARN: %s\n' "$*" >&2; }
note() { printf '%s\n' "$*"; }
require_root() { [[ $(id -u) == 0 ]] || die '이 동작은 root 로 실행한다.'; }
require_systemd() { [[ $(cat /proc/1/comm) == systemd ]] || die 'PID 1 이 systemd 여야 한다.'; }
validate_user() { [[ $1 =~ ^[a-z_][a-z0-9_-]{0,30}$ && $1 != root ]] || die '잘못된 계정 이름이다.'; }
validate_port() { [[ $1 =~ ^[1-9][0-9]{0,4}$ ]] && (( 10#$1 <= 65535 )) || die '잘못된 포트다.'; }
validate_ipv4() {
    local part
    [[ $1 =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || die "IPv4 주소가 필요하다: $1"
    local -a parts
    IFS=. read -r -a parts <<< "$1"
    for part in "${parts[@]}"; do
        [[ $part == 0 || $part != 0* ]] && (( 10#$part <= 255 )) || die "잘못된 IPv4 주소다: $1"
    done
}
validate_cidr() {
    [[ $1 == */* ]] || die "명시적인 IPv4 CIDR 이 필요하다: $1"
    validate_ipv4 "${1%/*}"
    local prefix=${1#*/}
    [[ $prefix =~ ^([1-9]|[12][0-9]|3[0-2])$ ]] || die 'CIDR 접두 길이는 1~32 다. /0 은 금지한다.'
}
require_protected_file() {
    local file=$1 mode
    [[ -f $file && ! -L $file ]] || die "일반 파일이 아니다: $file"
    [[ $(stat -c %u "$file") == 0 ]] || die "root 소유가 아니다: $file"
    mode=$(stat -c %a "$file")
    (( (8#$mode & 8#022) == 0 )) || die "다른 계정이 쓸 수 있다: $file"
}

# --- 계획값 ---------------------------------------------------------------
# build/00-주소계획.md 의 VM 배치 표를 그대로 옮겼다.
# 이 표와 주소계획이 어긋나면 주소계획을 먼저 고치고 여기를 맞춘다.
declare -A LAN_IP=(
    [edge01]=192.168.10.11
    [app01]=192.168.10.21
    [app02]=192.168.10.22
    [db01]=192.168.10.31
    [db02]=192.168.10.32
    [ops01]=192.168.10.41
)
declare -A MGMT_IP=(
    [edge01]=192.168.56.11
    [app01]=192.168.56.21
    [app02]=192.168.56.22
    [db01]=192.168.56.31
    [db02]=192.168.56.32
    [ops01]=192.168.56.41
)
LAB_LAN_CIDR=192.168.10.0/24
MGMT_CIDR=192.168.56.0/24
OPS_LAN_IP=192.168.10.41      # 지표를 수집하는 쪽. 9100 을 여는 유일한 출발지다.
PFSENSE_LAN_IP=192.168.10.1   # 사용자가 직접 구축한다. 여기서는 주소만 예약한다.
LAN_NIC=enp0s8                # NIC2. lab-lan
MGMT_NIC=enp0s9               # NIC3. host-only
NAT_NIC=enp0s3                # NIC1. 구축 단계의 패키지 설치용 NAT
OPERATOR=ops
SERVICE_ACCOUNT=vm-lab        # 진단 타이머를 돌리는 로그인 불가 계정

# node_exporter 는 failstop/docs/reports/AWS_작업.md 에 기록한 값을 그대로 쓴다.
# 파일 이름이 같다는 이유로 검증을 건너뛰지 않는다. 다시 받은 파일도 직접 대조한다.
NODE_EXPORTER_VERSION=1.12.1
NODE_EXPORTER_TARBALL=node_exporter-${NODE_EXPORTER_VERSION}.linux-amd64.tar.gz
NODE_EXPORTER_SHA256=b51d8a76aa2a9156a55d501aca6276fae09e262259a5e4e831d2c2222f084e63
NODE_EXPORTER_URL=https://github.com/prometheus/node_exporter/releases/download/v${NODE_EXPORTER_VERSION}/${NODE_EXPORTER_TARBALL}

PACKAGES=(sysstat tcpdump nmap-ncat bind-utils logrotate python3)

# --- 인자 ------------------------------------------------------------------
mode=
host=
public_key=
tarball=
download=false
skip_packages=false
nat_dhcp=true     # 구축 단계 기본값. NAT 를 내린 뒤에는 --no-nat-dhcp 로 규칙을 뺀다.
phase=build       # build 또는 operate. operate 로 바꾸면 점검이 전체 sudo 를 실패로 본다.

while (($#)); do
    case "$1" in
        --plan) mode=plan; shift ;;
        --apply) mode=apply; shift ;;
        --host) host=${2:?}; shift 2 ;;
        --public-key) public_key=${2:?}; shift 2 ;;
        --node-exporter-tarball) tarball=${2:?}; shift 2 ;;
        --download) download=true; shift ;;
        --skip-packages) skip_packages=true; shift ;;
        --nat-dhcp) nat_dhcp=true; shift ;;
        --no-nat-dhcp) nat_dhcp=false; shift ;;
        --phase) phase=${2:?}; shift 2 ;;
        -h|--help) sed -n '1,40p' "$0"; exit 0 ;;
        *) die "모르는 인자다: $1" ;;
    esac
done
[[ -n $mode ]] || die '--plan 또는 --apply 중 하나를 지정한다.'
case "$phase" in build|operate) ;; *) die '--phase 는 build 또는 operate 다.' ;; esac

# 호스트명을 주지 않으면 현재 호스트명에서 가져온다. 계획에 없는 이름이면 멈춘다.
# hostname 명령에 기대지 않고 커널 값을 직접 읽는다. minimal 설치에 그 패키지가 없을 수 있다.
[[ -n $host ]] || host=$(cat /proc/sys/kernel/hostname)
host=${host%%.*}
host=${host,,}
[[ -n ${LAN_IP[$host]:-} ]] || die "계획에 없는 호스트다: $host. edge01 app01 app02 db01 db02 ops01 중 하나여야 한다."

lan_ip=${LAN_IP[$host]}
mgmt_ip=${MGMT_IP[$host]}
validate_ipv4 "$lan_ip"
validate_ipv4 "$mgmt_ip"
validate_cidr "$LAB_LAN_CIDR"
validate_cidr "$MGMT_CIDR"
validate_user "$OPERATOR"
validate_user "$SERVICE_ACCOUNT"
validate_port 22
validate_port 9100

# node_exporter 는 여섯 대 모두 lab-lan 주소에 연다. OPS01 만 루프백에 두던 분기는 없앴다.
# 근거는 build/02-정본결정.md 7절이고 Prometheus 대상도 192.168.10.x:9100 하나로 맞춘다.
# 주소는 여섯 대 모두 /etc/node_exporter/listen.env 에서 읽는다. 유닛 본문은 같고 값만 다르다.
# OPS01 이 자기 지표를 읽을 때는 출발지와 목적지가 같은 호스트라 커널이 lo 로 보낸다.
# 그 경로는 방화벽의 iif lo accept 규칙이 먼저 받는다.
exporter_bind=$lan_ip

# --- 생성할 내용 ------------------------------------------------------------
stage=$(mktemp -d)
trap 'rm -rf -- "$stage"' EXIT
chmod 0700 "$stage"

# journald 드롭인. failstop/deploy/linux/journald.conf.d/60-exchange-lab.conf 를 값 변경 없이 쓴다.
cat > "$stage/journald.conf" <<'EOF'
[Journal]
Storage=persistent
Compress=yes
SystemMaxUse=256M
SystemKeepFree=1G
RuntimeMaxUse=64M
MaxRetentionSec=7day
EOF

# logrotate. failstop/deploy/linux/logrotate.conf 에서 경로만 vm-lab 으로 바꿨다.
cat > "$stage/logrotate" <<'EOF'
/var/log/vm-lab/host-check.log {
    daily
    maxsize 10M
    rotate 7
    compress
    delaycompress
    missingok
    notifempty
    create 0600 root root
}
EOF

# 방화벽 유닛. failstop/deploy/linux/systemd/failstop-firewall.service 의 형태를 그대로 따왔다.
# Before 대상만 failstop-ssh.service 에서 배포판 기본 sshd.service 로 바꿨다.
# 중지해도 규칙을 지우지 않는다. 규칙을 내리려면 nft delete table 을 직접 실행한다.
cat > "$stage/vm-lab-firewall.service" <<'EOF'
[Unit]
Description=VM lab host firewall (nftables)
Before=sshd.service
After=local-fs.target
ConditionPathExists=/etc/vm-lab/firewall.nft

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/usr/sbin/nft -f /etc/vm-lab/firewall.nft
ExecReload=/usr/sbin/nft -f /etc/vm-lab/firewall.nft

[Install]
WantedBy=multi-user.target
EOF

# 진단 서비스와 타이머. failstop/deploy/linux/systemd 의 두 파일에서 이름과 경로만 바꿨다.
# 5분 주기, Persistent, RandomizedDelaySec, 보호 설정은 그대로 유지한다.
cat > "$stage/vm-lab-host-check.service" <<'EOF'
[Unit]
Description=VM lab host diagnostics
After=local-fs.target

[Service]
Type=oneshot
User=vm-lab
Group=vm-lab
ExecStart=/opt/vm-lab/check-host.sh --inspect
StandardOutput=append:/var/log/vm-lab/host-check.log
StandardError=journal
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
ProtectKernelTunables=yes
ProtectControlGroups=yes
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6 AF_NETLINK
UMask=0077
EOF

cat > "$stage/vm-lab-host-check.timer" <<'EOF'
[Unit]
Description=Run VM lab host diagnostics every five minutes

[Timer]
OnCalendar=*-*-* *:0/5:00
Persistent=yes
RandomizedDelaySec=15
Unit=vm-lab-host-check.service

[Install]
WantedBy=timers.target
EOF

# node_exporter 유닛. failstop/deploy/linux/observability 의 두 파일을 기반으로 한다.
# 바꾼 곳은 --collector.systemd.unit-include 정규식 하나다.
# VM 배치에 봇 노드가 없어 exchange-bots 를 빼고, 실제로 돌릴 nginx,
# alertmanager, postgres_exporter 를 넣었다.
# 실제 정책을 세우는 유닛은 vm-lab-firewall.service 이므로 그 이름을 목록에 넣는다.
# 배포판 nftables.service 는 아래 4절에서 mask 하지만, 되살아나면 바로 보이도록 목록에 남긴다.
# 2026-09-22: --collector.pressure 를 뺐다. 이 커널에는 PSI 가 없다.
# /proc/pressure 가 없고 /proc/cmdline 에 psi= 도 없어 이 수집기는 매 수집마다 실패했다.
# 그 결과 LinuxCollectorFailed 경보가 여섯 대에서 상시 발화했고, 이 관제 시스템이 내는
# 유일한 경보가 영원히 풀리지 않는 오탐이 됐다. 오탐이 상주하면 진짜 경보를 가린다.
# node_pressure_* 를 쓰는 대시보드나 경보 규칙은 없으므로 빼도 잃는 화면이 없다.
# PSI 지표를 쓰기로 하면 여섯 대의 부팅 인자에 psi=1 을 넣고(ks/*.ks 의 bootloader --append 포함)
# /proc/pressure/cpu 가 값을 내는 것을 확인한 뒤 이 인자를 되돌린다.
# 학교 AWS 의 Amazon Linux 2023 은 PSI 기본값이 다를 수 있으므로
# failstop/deploy/linux/observability 의 유닛은 그쪽에서 확인한 뒤 따로 판단한다.
# 2026-09-22: textfile 수집기를 켠다. 백업 같은 배치 작업이 .prom 파일을 써 두면
# node_exporter 가 그 값을 함께 내보내는 경로다. 15-msp-db-replication 의
# '백업 성공 · 경과 시간' 패널 두 개가 failstop_backup_last_success_timestamp_seconds 를
# 조회하는데 이 수집기가 없으면 지표가 올라올 길 자체가 없었다.
# 켠다고 값이 생기지는 않는다. 쓰는 쪽(백업 작업)이 VM 에 아직 배포돼 있지 않다.
# 디렉터리는 비어 있어도 되고 없으면 node_exporter 가 경고만 낸다.
# ProtectSystem=strict 이지만 읽기는 막지 않으므로 별도 ReadWritePaths 가 필요 없다.
node_exporter_collectors='--collector.disable-defaults --collector.cpu --collector.meminfo --collector.filesystem --collector.diskstats --collector.netdev --collector.netstat --collector.sockstat --collector.loadavg --collector.stat --collector.time --collector.uname --collector.vmstat --collector.filefd --collector.textfile --collector.textfile.directory=/var/lib/node_exporter/textfile --collector.systemd --collector.systemd.unit-include=(sshd|chronyd|nftables|vm-lab-firewall|postgresql|exchange-app|nginx|prometheus|grafana-server|alertmanager|postgres_exporter|node_exporter)[.]service'

# 주소를 환경 파일에서 읽는다. VM마다 바인딩 값만 다르고 유닛 본문은 여섯 대가 같다.
cat > "$stage/node_exporter.service" <<EOF
[Unit]
Description=Linux host metrics
After=network-online.target
Wants=network-online.target

[Service]
User=node_exporter
Group=node_exporter
EnvironmentFile=/etc/node_exporter/listen.env
ExecStart=/usr/local/bin/node_exporter --web.listen-address=\${NODE_EXPORTER_ADDRESS}:9100 $node_exporter_collectors
Restart=on-failure
RestartSec=5
MemoryMax=128M
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=read-only
ProtectKernelTunables=true
ProtectControlGroups=true
RestrictSUIDSGID=true

[Install]
WantedBy=multi-user.target
EOF
printf 'NODE_EXPORTER_ADDRESS=%s\n' "$exporter_bind" > "$stage/listen.env"

# --- nftables 기본 정책 ------------------------------------------------------
# failstop/deploy/linux/firewall-host.sh 를 그대로 실행할 수 없다. 이유는 두 가지다.
#   1. 그 스크립트는 policy accept 에 지정 포트만 개별 drop 하는 설계다.
#      접근제어도의 '허용하지 않은 새 인바운드 연결은 차단'을 만족하려면 기본 차단이어야 한다.
#   2. 그 스크립트는 ssh_port 22 를 명시적으로 거절한다. VM 은 주소계획대로 22 를 쓴다.
# 그래서 규칙 파일을 따로 만든다. 가져온 것은 방식이다.
#   - add table 과 flush table 을 한 파일에 담아 갱신 중 차단 공백을 만들지 않는다.
#   - nft --check 를 통과하기 전에는 적용하지 않는다.
#   - 0600 으로 설치한다.
#
# 규칙에 붙는 comment 는 짧게 쓴다. nftables 의 규칙 주석은 128바이트 제한이 있고
# 한글은 한 글자가 3바이트다. 긴 설명은 # 주석으로 파일에만 남긴다.
render_firewall() {
    printf '%s\n' \
'# VM 실습 호스트 방화벽. 00-common-setup.sh 가 생성한다. 직접 고치지 않는다.' \
'# 역할별 포트는 /etc/vm-lab/firewall.d/*.nft 에 add rule 형태로 덧붙인다.' \
'#' \
'# 나가는 통신은 여기서 제한하지 않는다. DNS 53, NTP 123, 패키지 저장소 443,' \
'# OPS01 의 Teams 와 AI API 443 같은 발신 허용목록은 pfSense 가 맡는다.' \
'# 이 파일만으로 발신 허용목록을 적용했다고 적지 않는다.' \
'#' \
'# add table 과 flush table 을 한 파일에 담아 갱신 중 차단 공백을 만들지 않는다.' \
"add table inet vmlab" \
"flush table inet vmlab" \
"table inet vmlab {" \
" chain input {" \
'  # 허용하지 않은 새 인바운드 연결은 여기서 끝난다. 접근제어도의 기본 차단이다.' \
"  type filter hook input priority 0; policy drop;" \
'  # 루프백. OPS01 의 Grafana, Prometheus, Alertmanager 통신이 이 줄에 의존한다.' \
'  iif lo counter accept comment "loopback"' \
'  ct state established,related counter accept comment "응답만 통과"' \
'  ct state invalid counter drop comment "상태 불명 차단"'
    # 관리 SSH. 인터페이스까지 묶어 lab-lan 쪽에서 출발지를 위조해 들어오는 경로를 막는다.
    printf '%s\n' '  # 관리 SSH. host-only NIC 로 들어온 관리 대역만 받는다. 인터페이스를 묶어 위조를 막는다.'
    printf '  iifname "%s" ip saddr %s tcp dport 22 ct state new counter accept comment "%s"\n' \
        "$MGMT_NIC" "$MGMT_CIDR" "관리 SSH"
    # node_exporter. 여섯 대 모두 같은 규칙을 쓴다. OPS01 만 빼던 분기는 없앴다.
    # OPS01 이 자기 지표를 읽는 경로는 커널이 lo 로 보내므로 위의 lo accept 가 먼저 받는다.
    # 그래도 같은 규칙을 남겨 여섯 대의 규칙 모양과 확인 절차를 하나로 둔다.
    printf '%s\n' '  # node_exporter. OPS01 의 lab-lan 주소 한 개만 허용한다. /24 전체를 열지 않는다.'
    printf '  ip saddr %s tcp dport 9100 ct state new counter accept comment "%s"\n' \
        "$OPS_LAN_IP" "node_exporter"
    printf '%s\n' '  # 경로 확인과 PMTU. 실습 두 대역만 허용한다.'
    printf '  ip saddr { %s, %s } icmp type { echo-request, destination-unreachable, time-exceeded } counter accept comment "%s"\n' \
        "$LAB_LAN_CIDR" "$MGMT_CIDR" "실습 대역 ICMP"
    if $nat_dhcp; then
        printf '%s\n' '  # 구축 단계 한정. NAT 의 DHCP 응답은 새 연결이라 established 로 잡히지 않는다.'
        printf '%s\n' '  # NAT 를 내릴 때 --no-nat-dhcp 로 다시 만들어 이 줄을 없앤다.'
        printf '  iifname "%s" udp sport 67 udp dport 68 counter accept comment "%s"\n' \
            "$NAT_NIC" "구축용 DHCP"
    fi
    printf '%s\n' \
'  # 역할별 규칙은 별도 체인에서 받는다. 로그와 카운터보다 먼저 판정해야' \
'  # 허용된 통신이 차단 증거로 기록되지 않는다.' \
'  jump roles' \
'  # 차단 증거. 기본 차단에 닿은 연결을 커널 로그에 남긴다. 접두사는 vmlab-drop 이다.' \
'  # 로그 폭주를 막으려고 분당 5건으로 제한하므로 이 줄의 건수를 차단 총량으로 읽지 않는다.' \
'  # 총량은 아래 counter 로 본다. 확인은 journalctl -k --grep vmlab-drop 이다.' \
'  limit rate 5/minute burst 5 packets log prefix "vmlab-drop: " level info' \
'  # 여기까지 온 새 인바운드 연결은 기본 정책이 버린다. 이 카운터로 차단량을 본다.' \
'  counter comment "기본 차단 도달"' \
" }" \
" chain roles {" \
'  # 역할별 허용 규칙이 여기에 붙는다. 비어 있으면 input 으로 돌아가 기본 차단에 닿는다.' \
" }" \
" chain forward {" \
'  # VM 은 라우터가 아니다. 전달은 pfSense 가 한다.' \
"  type filter hook forward priority 0; policy drop;" \
'  counter comment "전달 금지"' \
" }" \
" chain output {" \
'  # 발신 제한은 pfSense 몫이다. 호스트에서 이중으로 막지 않는다.' \
"  type filter hook output priority 0; policy accept;" \
" }" \
"}" \
'' \
'# 역할별 규칙. 자리표시자만 있으면 아무 규칙도 붙지 않는다.' \
'include "/etc/vm-lab/firewall.d/*.nft"'
}
render_firewall > "$stage/firewall.nft"

cat > "$stage/firewall.d-placeholder" <<'EOF'
# 역할별 nftables 규칙을 두는 자리다. 00-common-setup.sh 가 만든 자리표시자이며 규칙이 없다.
#
# 역할 절차는 이 디렉터리에 파일을 하나 더 놓는다. 형식은 add rule 이다.
#   add rule inet vmlab roles ip saddr 192.168.10.11 tcp dport 8080 ct state new counter accept
# table 과 chain 을 다시 선언하지 않는다. 기본 정책은 이미 drop 이다.
#
# 적용은 sudo systemctl reload vm-lab-firewall.service 다.
# /etc/vm-lab/firewall.nft 가 flush 후 이 디렉터리를 include 하므로 한 번에 다시 선다.
EOF

# --- 호스트 점검 스크립트 ----------------------------------------------------
# failstop/deploy/linux/check-host.sh 의 진단 항목을 그대로 쓴다.
# 바꾼 곳은 다음과 같다.
#   - ssh_service 대상을 failstop-ssh.service 에서 배포판 기본 sshd.service 로 바꿨다.
#   - 타이머 대상을 vm-lab-host-check.timer 로 바꿨다.
#   - ssh_syntax 를 /etc/failstop/sshd_config 지정 대신 sshd -t 로 바꿨다.
#   - /etc/failstop 경로와 failstop 계정 검사를 /etc/vm-lab 과 vm-lab 계정으로 바꿨다.
#   - 원본은 운영 계정의 wheel 소속과 임의 sudo 를 무조건 실패로 본다.
#     VM 구축 단계의 ops 는 의도적으로 wheel 이고 전체 sudo 를 가진다.
#     그래서 /etc/vm-lab/phase 를 읽어 build 면 상태로만 남기고 operate 면 실패로 본다.
#     이 판정을 없앤 것이 아니라 시점을 나눈 것이다. 좁히기 전에는 어디에도 제한된 sudo 라고 적지 않는다.
cat > "$stage/check-host.sh" <<'CHECKEOF'
#!/usr/bin/env bash
set -uo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
mode=${1:---inspect}
[[ $# -le 1 && ( $mode == --inspect || $mode == --verify ) ]] || { printf 'Usage: check-host.sh --inspect|--verify\n' >&2; exit 1; }
failed=0
check() {
    local label=$1
    shift
    printf '\n[%s]\n' "$label"
    if "$@"; then printf 'status=OK\n'; else printf 'status=ERROR\n'; failed=1; fi
}
phase=build
[[ -f /etc/vm-lab/phase ]] && phase=$(cat /etc/vm-lab/phase)

printf 'collected_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf 'host=%s phase=%s\n' "$(hostname -s)" "$phase"
check os sh -c '. /etc/os-release; printf "%s %s\n" "$ID" "$VERSION_ID"; uname -m; cat /proc/1/comm'
check mounts findmnt -r -o TARGET,SOURCE,FSTYPE,OPTIONS -t ext4,xfs,btrfs
check disk_bytes df -h -l -x tmpfs -x devtmpfs
check disk_inodes df -i -l -x tmpfs -x devtmpfs
check block_devices lsblk -o NAME,TYPE,SIZE,FSTYPE,MOUNTPOINTS
check memory free -h
check io iostat -dx 1 2
check network ip -brief address
check route ip route show
check listening ss -lnt
check ssh_service systemctl is-active sshd.service
check diagnostic_timer systemctl is-active vm-lab-host-check.timer

if [[ $mode == --verify ]]; then
    if [[ $(id -u) != 0 ]]; then printf 'verify requires root\n' >&2; exit 1; fi
    check ssh_syntax /usr/sbin/sshd -t
    check ssh_enabled systemctl is-enabled sshd.service
    check timer_enabled systemctl is-enabled vm-lab-host-check.timer
    check journal_storage test -d /var/log/journal
    check journal_usage journalctl --disk-usage
    check logrotate logrotate --debug /etc/logrotate.d/vm-lab
    # firewalld 나 배포판 nftables.service 가 되살아나면 정책원이 둘이 된다. masked 상태를 확인한다.
    check firewalld_masked sh -c '[ "$(systemctl is-enabled firewalld.service 2>/dev/null)" = masked ]'
    check nftables_unit_masked sh -c '[ "$(systemctl is-enabled nftables.service 2>/dev/null)" = masked ]'
    if [[ -f /etc/vm-lab/operator ]]; then
        operator=$(cat /etc/vm-lab/operator)
        check operator id "$operator"
        check operator_home stat -c '%U %G %a %n' "/home/$operator"
        check operator_sudo sudo -l -U "$operator"
        for group in $(id -nG "$operator"); do
            case "$group" in root|sudo|wheel|docker|lxd|incus-admin|adm|systemd-journal)
                if [[ $phase == build ]]; then
                    # 구축 단계에서는 wheel 이 의도된 상태다. 실패로 보지 않고 기록만 남긴다.
                    printf 'privileged_operator_group=%s status=BUILD_PHASE\n' "$group"
                else
                    printf 'privileged_operator_group=%s status=ERROR\n' "$group"; failed=1
                fi ;;
            esac
        done
        # 0600 시험 파일이 실제로 읽히지 않는지 확인한다. 운영 비밀 값은 열지 않는다.
        probe=$(mktemp /etc/vm-lab/.permission-probe.XXXXXX)
        chmod 0600 "$probe"
        if runuser -u "$operator" -- test -r "$probe"; then
            printf 'secret_read_denied=ERROR\n'; failed=1
        else
            printf 'secret_read_denied=OK\n'
        fi
        rm -f -- "$probe"
        if sudo -n -u "$operator" sudo -n /usr/bin/true 2>/dev/null; then
            if [[ $phase == build ]]; then
                printf 'arbitrary_sudo_denied=BUILD_PHASE\n'
            else
                printf 'arbitrary_sudo_denied=ERROR\n'; failed=1
            fi
        else
            printf 'arbitrary_sudo_denied=OK\n'
        fi
    else
        printf 'operator=UNCONFIGURED status=ERROR\n'; failed=1
    fi
    check service_account getent passwd vm-lab
    if [[ $(getent passwd vm-lab | cut -d: -f7) != */nologin ]]; then
        printf 'service_account_login=ERROR\n'; failed=1
    fi
    if [[ -f /etc/vm-lab/firewall.nft ]]; then
        check firewall_active systemctl is-active vm-lab-firewall.service
        check firewall_rules nft list table inet vmlab
        # 기본 차단이 실제로 서 있는지 본다. policy accept 로 돌아가면 접근제어도와 어긋난다.
        check firewall_default_drop sh -c 'nft -a list chain inet vmlab input | grep -q "policy drop"'
    else
        printf 'firewall=UNCONFIGURED status=ERROR\n'; failed=1
    fi
    for env_file in /etc/vm-lab/*.env; do
        [[ -f $env_file ]] || continue
        check env_permissions stat -c '%U %G %a %n' "$env_file"
        if [[ -L $env_file || $(stat -c '%u:%g:%a' "$env_file") != 0:0:600 ]]; then
            printf 'env_permissions=ERROR\n'; failed=1
        fi
    done
fi
printf '\ncollection_success=%s\n' "$((1 - failed))"
exit "$failed"
CHECKEOF

# sshd 강화 설정. kickstart 가 이미 같은 내용을 넣었다. 여기서는 없거나 달라졌을 때만 다시 넣는다.
{
    cat <<'EOF'
# VM 실습 관리 접속 정책. 키 인증만 허용하고 host-only 주소에만 붙는다.
PermitRootLogin no
PasswordAuthentication no
KbdInteractiveAuthentication no
AuthenticationMethods publickey
PubkeyAuthentication yes
PermitEmptyPasswords no
StrictModes yes
LoginGraceTime 30
MaxAuthTries 3
ClientAliveInterval 60
ClientAliveCountMax 3
LogLevel VERBOSE
X11Forwarding no
AllowAgentForwarding no
PermitTunnel no
AllowTcpForwarding no
PermitUserEnvironment no
AddressFamily inet
EOF
    printf 'AllowUsers %s@%s\n' "$OPERATOR" "$MGMT_CIDR"
    printf 'ListenAddress %s\n' "$mgmt_ip"
} > "$stage/20-vm-lab.conf"

cat > "$stage/10-wait-network.conf" <<'EOF'
[Unit]
# ListenAddress 가 고정 주소라 인터페이스가 올라온 뒤에 기동해야 한다.
After=network-online.target
Wants=network-online.target
EOF

cat > "$stage/sudoers" <<'EOF'
# 구축 단계 한정. 종료 후 필요한 명령만 남기도록 좁힌다.
ops ALL=(ALL) NOPASSWD: ALL
EOF

# --- plan ------------------------------------------------------------------
if [[ $mode == plan ]]; then
    cat <<EOF
mode=plan host=$host phase=$phase
lab-lan($LAN_NIC)=$lan_ip  host-only($MGMT_NIC)=$mgmt_ip  NAT($NAT_NIC)=DHCP
sshd 22 바인딩=$mgmt_ip
node_exporter 9100 바인딩=$exporter_bind (여섯 대 모두 lab-lan 주소. listen.env 에서 읽는다)
9100 허용 출발지=$OPS_LAN_IP
구축 단계 DHCP 허용 규칙=$nat_dhcp
설치할 패키지=${PACKAGES[*]}$( $skip_packages && echo ' (건너뜀)' )
pfSense 예약 주소=$PFSENSE_LAN_IP (사용자가 직접 구축한다. 이 스크립트는 건드리지 않는다)

설치할 파일
  /etc/systemd/journald.conf.d/60-exchange-lab.conf   0644
  /etc/logrotate.d/vm-lab                             0644
  /opt/vm-lab/check-host.sh                           0755
  /etc/systemd/system/vm-lab-firewall.service         0644
  /etc/systemd/system/vm-lab-host-check.service       0644
  /etc/systemd/system/vm-lab-host-check.timer         0644
  /etc/systemd/system/node_exporter.service           0644
  /etc/systemd/system/sshd.service.d/10-wait-network.conf 0644
  /etc/ssh/sshd_config.d/20-vm-lab.conf               0600
  /etc/sudoers.d/90-vm-lab-ops                        0440
  /etc/vm-lab/firewall.nft                            0600
  /etc/vm-lab/firewall.d/00-placeholder.nft           0600
  /etc/node_exporter/listen.env                       0644

바꿀 서비스 상태
  enable  sshd chronyd vm-lab-firewall node_exporter vm-lab-host-check.timer
  disable + mask  firewalld nftables

적용할 nftables 규칙
EOF
    sed 's/^/  /' "$stage/firewall.nft"
    cat <<EOF

node_exporter 바이너리
  기대 파일 $NODE_EXPORTER_TARBALL
  SHA-256   $NODE_EXPORTER_SHA256
  --node-exporter-tarball 로 파일을 주거나 --download 로 받는다. 해시가 다르면 멈춘다.
  받는 명령: curl -fL -o /tmp/$NODE_EXPORTER_TARBALL $NODE_EXPORTER_URL

이 출력은 적용 예정 내용이다. 실제 상태가 아니다.
EOF
    exit 0
fi

# --- apply -----------------------------------------------------------------
require_root
require_systemd

# 대상 OS 를 확인한다. 다른 배포판에서 이 절차를 그대로 쓰지 않는다.
# shellcheck disable=SC1091
. /etc/os-release
[[ ${ID:-} == rocky ]] || die "Rocky Linux 대상이다. 지금 ID=${ID:-알수없음}"
[[ ${VERSION_ID:-} == 10* ]] || warn "Rocky 10 을 전제로 만든 절차다. 지금 VERSION_ID=${VERSION_ID:-알수없음}"

changed=()
record() { changed+=("$1"); printf 'changed=%s\n' "$1"; }

# 내용과 소유, 권한이 모두 같으면 건드리지 않는다. 바뀐 경우에만 0 을 돌려준다.
install_if_different() {
    local mode=$1 src=$2 dest=$3 cur
    if [[ -f $dest && ! -L $dest ]] && cmp -s "$src" "$dest"; then
        cur=$(stat -c %a "$dest")
        if [[ $(stat -c %u:%g "$dest") == 0:0 ]] && (( 8#$cur == 8#$mode )); then
            return 1
        fi
    fi
    install -o root -g root -m "$mode" "$src" "$dest"
    return 0
}

# --- 1. 패키지 --------------------------------------------------------------
# 구축 단계에는 NAT 로 외부에 나간다. NAT 를 내린 뒤에는 설치가 막히므로 먼저 채운다.
if $skip_packages; then
    note 'skip=packages'
else
    missing=()
    for pkg in "${PACKAGES[@]}"; do
        rpm -q "$pkg" >/dev/null 2>&1 || missing+=("$pkg")
    done
    if ((${#missing[@]})); then
        dnf install -y "${missing[@]}"
        record "packages(${missing[*]})"
    fi
fi
for command in nft visudo logrotate iostat sshd; do
    command -v "$command" >/dev/null || die "$command 가 없다. 패키지를 먼저 설치한다."
done

# --- 2. 주소 확인 ------------------------------------------------------------
# 여기서 주소를 새로 잡지 않는다. kickstart 가 잡은 값이 계획과 같은지 본다.
# 다르면 NIC 순서가 어긋난 것이므로 멈추고 사람이 확인한다.
check_address() {
    local nic=$1 want=$2
    ip -4 -brief address show dev "$nic" 2>/dev/null | grep -qw "$want/24" \
        || die "$nic 에 $want/24 가 없다. VM 의 NIC 순서(NAT, lab-lan, host-only)를 확인한다. 고치는 명령은 nmcli con mod <연결이름> ipv4.addresses $want/24 ipv4.method manual ipv4.never-default yes 다."
}
check_address "$LAN_NIC" "$lan_ip"
check_address "$MGMT_NIC" "$mgmt_ip"
default_routes=$(ip -4 route show default | wc -l)
(( default_routes == 1 )) || warn "기본 경로가 ${default_routes}개다. 구축 중에는 NAT 하나만 남긴다."

# --- 3. 계정과 디렉터리 -------------------------------------------------------
id "$SERVICE_ACCOUNT" >/dev/null 2>&1 || {
    useradd --system --user-group --home-dir /var/lib/vm-lab --shell /sbin/nologin "$SERVICE_ACCOUNT"
    record "user($SERVICE_ACCOUNT)"
}
usermod --lock "$SERVICE_ACCOUNT"
id node_exporter >/dev/null 2>&1 || {
    useradd --system --no-create-home --shell /sbin/nologin node_exporter
    record 'user(node_exporter)'
}
install -d -o root -g root -m 0755 /etc/vm-lab /etc/vm-lab/firewall.d /opt/vm-lab /var/log/vm-lab
install -d -o root -g root -m 0755 /var/lib/vm-lab /etc/node_exporter
install -d -o root -g root -m 0755 /etc/systemd/journald.conf.d /etc/systemd/system/sshd.service.d

printf '%s\n' "$OPERATOR" > /etc/vm-lab/operator
chmod 0644 /etc/vm-lab/operator
printf '%s\n' "$phase" > /etc/vm-lab/phase
chmod 0644 /etc/vm-lab/phase

# 운영자 공개키를 다시 넣어야 할 때만 쓴다. 기본 경로는 kickstart 의 sshkey 다.
if [[ -n $public_key ]]; then
    [[ -f $public_key && ! -L $public_key ]] || die '공개키는 일반 파일이어야 한다.'
    [[ $(awk 'NF { n++ } END { print n+0 }' "$public_key") == 1 ]] || die '공개키는 한 줄이어야 한다.'
    [[ $(awk '{print $1}' "$public_key") == ssh-ed25519 ]] || die 'Ed25519 공개키만 받는다.'
    ssh-keygen -l -f "$public_key" >/dev/null || die '공개키를 읽지 못했다.'
    home=$(getent passwd "$OPERATOR" | cut -d: -f6 || true)
    install -d -o "$OPERATOR" -g "$OPERATOR" -m 0700 "$home/.ssh"
    touch "$home/.ssh/authorized_keys"
    if ! grep -qxF "$(cat "$public_key")" "$home/.ssh/authorized_keys"; then
        cat "$public_key" >> "$home/.ssh/authorized_keys"
        record 'authorized_keys'
    fi
    chown "$OPERATOR:$OPERATOR" "$home/.ssh/authorized_keys"
    chmod 0600 "$home/.ssh/authorized_keys"
fi

# --- 4. firewalld 와 배포판 nftables.service 비활성 ---------------------------------
# 지우지 않고 끈다. 설치 여부와 상태를 증거에 남길 수 있게 둔다.
if [[ $(systemctl is-enabled firewalld.service 2>/dev/null || true) != masked ]]; then
    systemctl disable --now firewalld.service || true
    systemctl mask firewalld.service
    record 'firewalld(disable+mask)'
fi

# 배포판 nftables.service 도 같은 방식으로 끈다. 근거는 build/02-정본결정.md 4절이다.
# 정책원은 vm-lab-firewall.service 하나이며 nftables.service 를 두 번째 정책원으로 살려 두지 않는다.
#
# 여기서는 자동 기동만 끊고 중지는 하지 않는다. 일부 버전의 nftables.service 는 중지할 때
# ruleset 전체를 비우기 때문이다. 지금 중지하면 10절까지 오는 동안 이 스크립트가 멈출 경우
# 호스트가 규칙 없는 상태로 남는다. 실제 중지는 10절에서 새 규칙을 세우기 직전에 한다.
note "nftables.service ExecStop=$(systemctl show -p ExecStop --value nftables.service 2>/dev/null || echo '확인불가')"
nftables_unit_running=false
if systemctl is-active --quiet nftables.service; then
    nftables_unit_running=true
fi
if [[ $(systemctl is-enabled nftables.service 2>/dev/null || true) != masked ]]; then
    systemctl disable nftables.service 2>/dev/null || true
    systemctl mask nftables.service
    record 'nftables.service(disable+mask)'
fi

# nftables.service 가 읽는 파일이다. 유닛을 mask 했으므로 이 파일은 더 이상 적용되지 않는다.
# 그래도 내용이 남아 있으면 다음 사람이 정책원으로 오해하므로 경고와 함께 정리 대상을 알린다.
# mask 가 실제로 서지 않았다면 정책원이 둘이 되는 상태이므로 멈춘다.
if [[ -f /etc/sysconfig/nftables.conf ]] && grep -Eqv '^\s*(#|$)' /etc/sysconfig/nftables.conf; then
    if [[ $(systemctl is-enabled nftables.service 2>/dev/null || true) == masked ]]; then
        warn '/etc/sysconfig/nftables.conf 에 활성 지시자가 있다. nftables.service 를 mask 해서 지금은 적용되지 않는다.'
        warn '남은 include 줄과 그 줄이 가리키는 규칙 파일을 지운다. 정책은 /etc/vm-lab/firewall.d/*.nft 로 옮긴다.'
    else
        die '/etc/sysconfig/nftables.conf 에 활성 지시자가 있는데 nftables.service 가 mask 되지 않았다. 정책원이 둘이다. systemctl mask nftables.service 를 먼저 확인한다.'
    fi
fi

# --- 5. 로그 보존 --------------------------------------------------------------
journal_changed=false
install_if_different 0644 "$stage/journald.conf" /etc/systemd/journald.conf.d/60-exchange-lab.conf && {
    journal_changed=true; record 'journald.conf'
}
install_if_different 0644 "$stage/logrotate" /etc/logrotate.d/vm-lab && record 'logrotate'
if $journal_changed || [[ ! -d /var/log/journal ]]; then
    # 디렉터리를 만든 뒤 journald 를 재시작하고 런타임 로그를 옮긴다. 순서를 지킨다.
    systemd-tmpfiles --create --prefix /var/log/journal
    systemctl restart systemd-journald
    journalctl --flush
    record 'journal(persistent)'
fi

# --- 6. 점검 스크립트와 유닛 ------------------------------------------------------
install_if_different 0755 "$stage/check-host.sh" /opt/vm-lab/check-host.sh && record 'check-host.sh'
bash -n /opt/vm-lab/check-host.sh || die 'check-host.sh 구문 검사에 실패했다.'

unit_changed=false
for unit in vm-lab-firewall.service vm-lab-host-check.service vm-lab-host-check.timer; do
    install_if_different 0644 "$stage/$unit" "/etc/systemd/system/$unit" && { unit_changed=true; record "unit($unit)"; }
done
install_if_different 0644 "$stage/node_exporter.service" /etc/systemd/system/node_exporter.service && {
    unit_changed=true; record 'unit(node_exporter.service)'
}
install_if_different 0644 "$stage/10-wait-network.conf" /etc/systemd/system/sshd.service.d/10-wait-network.conf && {
    unit_changed=true; record 'unit(sshd wait-network)'
}
$unit_changed && systemctl daemon-reload

# --- 7. sudo 와 sshd -----------------------------------------------------------
visudo -cf "$stage/sudoers" >/dev/null || die 'sudoers 구문 검사에 실패했다.'
install_if_different 0440 "$stage/sudoers" /etc/sudoers.d/90-vm-lab-ops && record 'sudoers'

# sshd 는 설정 하나만 따로 검사할 수 없고 /etc/ssh/sshd_config 전체를 읽는다.
# 그래서 기존 파일을 먼저 백업하고, 설치한 뒤 sshd -t 가 실패하면 되돌린다.
# 문법이 깨진 설정을 남겨 둔 채 멈추면 다음 재부팅에서 sshd 가 뜨지 않는다.
sshd_changed=false
sshd_backup=
if [[ -f /etc/ssh/sshd_config.d/20-vm-lab.conf ]]; then
    sshd_backup="$stage/20-vm-lab.conf.bak"
    cp -p /etc/ssh/sshd_config.d/20-vm-lab.conf "$sshd_backup"
fi
install_if_different 0600 "$stage/20-vm-lab.conf" /etc/ssh/sshd_config.d/20-vm-lab.conf && {
    sshd_changed=true; record 'sshd_config.d/20-vm-lab.conf'
}
require_protected_file /etc/ssh/sshd_config.d/20-vm-lab.conf
if ! sshd -t; then
    if [[ -n $sshd_backup ]]; then
        install -o root -g root -m 0600 "$sshd_backup" /etc/ssh/sshd_config.d/20-vm-lab.conf
        warn '이전 sshd 설정으로 되돌렸다.'
    else
        rm -f /etc/ssh/sshd_config.d/20-vm-lab.conf
        warn '설치한 sshd 설정을 지웠다.'
    fi
    die 'sshd 설정 구문 검사에 실패했다. 재시작하지 않는다.'
fi
if $sshd_changed; then
    warn 'sshd 설정이 바뀌어 재시작한다. VirtualBox 콘솔을 열어 둔 상태에서 실행한다.'
    systemctl restart sshd.service
    record 'sshd(restart)'
fi
systemctl enable sshd.service chronyd.service >/dev/null

# --- 8. 시간 -------------------------------------------------------------------
if [[ $(timedatectl show -p Timezone --value) != Asia/Seoul ]]; then
    timedatectl set-timezone Asia/Seoul
    record 'timezone(Asia/Seoul)'
fi
systemctl enable --now chronyd.service >/dev/null

# 2026-09-22 DB01 에서 겪은 문제를 막는다. VM 을 savestate 로 몇 시간 멈췄다 깨우면
# 시계가 크게 벌어지는데 배포판 기본값 makestep 1.0 3 은 부팅 뒤 세 번만 보정한다.
# 그 뒤에는 느리게 맞추다 못 따라잡아 11 시간 어긋난 채로 남았다. 게다가 기본 풀에서
# 고른 서버 하나가 틀린 시각을 주었고 chrony 가 그 값에 고정됐다. 다른 두 소스가
# -39627s 로 경고했지만 소수라 배제됐다. 증거의 시각이 통째로 틀어지므로 둘 다 고친다.
# 이 지점은 plan 모드가 이미 끝난 뒤라 별도의 적용 조건을 두지 않는다.
if [[ -f /etc/chrony.conf ]]; then
    chrony_changed=false
    if grep -q '^makestep 1.0 3$' /etc/chrony.conf; then
        cp -a /etc/chrony.conf "/etc/chrony.conf.bak-$(date +%Y%m%d%H%M%S)"
        sed -i 's/^makestep 1.0 3$/makestep 1.0 -1/' /etc/chrony.conf
        chrony_changed=true
    fi
    # pfSense 로 시각원을 옮긴 뒤에는 외부 서버를 다시 넣지 않는다. 넣으면 prefer 가 pfSense 를 이긴다.
    # 주소를 정규식에 넣으면 점이 아무 문자와 일치하므로 awk 로 필드를 문자열 그대로 비교한다.
    if awk -v ip="$PFSENSE_LAN_IP" '$1 == "server" && $2 == ip { found = 1 } END { exit !found }' /etc/chrony.conf; then
        note "chrony 시각원이 pfSense(${PFSENSE_LAN_IP})다. 외부 서버를 넣지 않는다."
    elif ! grep -q '^server time.google.com' /etc/chrony.conf; then
        sed -i '1i server time.google.com iburst prefer' /etc/chrony.conf
        sed -i '2i server time.cloudflare.com iburst' /etc/chrony.conf
        chrony_changed=true
    fi
    if $chrony_changed; then
        record 'chrony(makestep 무제한, 검증된 소스 우선)'
        systemctl restart chronyd.service
        sleep 5
        chronyc makestep >/dev/null 2>&1 || true
    fi
    note "chrony 선택 소스=$(chronyc sources 2>/dev/null | awk '/\^\*/{print $2; exit}' || echo '미선택')"
    note "시각 확인: VM $(date -Is). 작업 PC 와 외부 응답 헤더로 대조한다."
fi

# pfSense 전환 시 /etc/chrony.conf 의 server 를 192.168.10.1 로 바꾼다. 여기서는 바꾸지 않고,
# 그 줄이 있으면 위 단계가 외부 서버를 다시 넣지 않는다.
note "chrony 원본=$(awk '/^(server|pool)/ {printf "%s ", $2}' /etc/chrony.conf 2>/dev/null || echo '확인불가')"

# --- 9. node_exporter -----------------------------------------------------------
install_node_exporter() {
    local src=$1 work
    work=$(mktemp -d -p "$stage")
    # 해시를 먼저 대조한다. 이름이 같다는 이유로 검증을 건너뛰지 않는다.
    printf '%s  %s\n' "$NODE_EXPORTER_SHA256" "$src" | sha256sum -c - >/dev/null \
        || die "node_exporter 묶음의 SHA-256 이 기록값과 다르다: $src"
    tar -xzf "$src" -C "$work"
    local binary="$work/node_exporter-${NODE_EXPORTER_VERSION}.linux-amd64/node_exporter"
    [[ -f $binary ]] || die '묶음 안에서 node_exporter 바이너리를 찾지 못했다.'
    if install_if_different 0755 "$binary" /usr/local/bin/node_exporter; then
        record 'node_exporter(binary)'
        return 0
    fi
    return 1
}

exporter_changed=false
if [[ -n $tarball ]]; then
    [[ -f $tarball && ! -L $tarball ]] || die "묶음 파일이 없다: $tarball"
    install_node_exporter "$tarball" && exporter_changed=true
elif $download; then
    command -v curl >/dev/null || die 'curl 이 없다.'
    dl="$stage/$NODE_EXPORTER_TARBALL"
    curl -fL --proto '=https' --tlsv1.2 -o "$dl" "$NODE_EXPORTER_URL"
    install_node_exporter "$dl" && exporter_changed=true
elif [[ ! -x /usr/local/bin/node_exporter ]]; then
    # 예전에는 여기서 die 했다. 그러면 기본 차단 방화벽과 진단 타이머를 세우기 전에 끝나
    # 규칙 없는 상태로 남는다. 지표가 빠지는 것보다 그 편이 위험하다.
    warn "node_exporter 바이너리가 없다. 지표 수집은 서지 않는다. 받는 곳: $NODE_EXPORTER_URL"
    warn '나중에 --download 나 --node-exporter-tarball 로 다시 실행한다.'
fi

# SELinux 문맥을 기본값으로 되돌린다. /usr/local/bin 은 bin_t 다.
restorecon -F /usr/local/bin/node_exporter /opt/vm-lab/check-host.sh 2>/dev/null || true

# 여섯 대 모두 환경 파일로 주소를 준다. OPS01 만 유닛에 주소를 박던 갈래는 없앴다.
install_if_different 0644 "$stage/listen.env" /etc/node_exporter/listen.env && {
    exporter_changed=true; record 'node_exporter(listen.env)'
}
$unit_changed && exporter_changed=true
systemctl enable node_exporter.service >/dev/null
if $exporter_changed || ! systemctl is-active --quiet node_exporter.service; then
    systemctl restart node_exporter.service
    record 'node_exporter(restart)'
fi

# --- 10. 방화벽 ------------------------------------------------------------------
install_if_different 0600 "$stage/firewall.d-placeholder" /etc/vm-lab/firewall.d/00-placeholder.nft \
    && record 'firewall.d placeholder'
# 적용 전에 반드시 구문을 본다. 통과하지 못하면 기존 규칙을 그대로 둔다.
nft --check --file "$stage/firewall.nft" || die 'nftables 규칙 구문 검사에 실패했다. 적용하지 않는다.'
firewall_changed=false
install_if_different 0600 "$stage/firewall.nft" /etc/vm-lab/firewall.nft && { firewall_changed=true; record 'firewall.nft'; }
require_protected_file /etc/vm-lab/firewall.nft
systemctl enable vm-lab-firewall.service >/dev/null

# 4절에서 mask 만 하고 남겨 둔 배포판 nftables.service 를 여기서 중지한다.
# 그 ExecStop 이 ruleset 전체를 비울 수 있으므로 바로 아래에서 규칙을 다시 세운다.
# vm-lab-firewall.service 는 RemainAfterExit 라 규칙이 지워져도 active 로 보인다.
# 그래서 유닛 상태만 믿지 않고 중지 사실을 조건에 넣어 무조건 다시 적용한다.
if $nftables_unit_running; then
    warn '배포판 nftables.service 를 중지한다. 이 중지가 ruleset 을 비울 수 있어 곧바로 규칙을 다시 세운다.'
    systemctl stop nftables.service || true
    record 'nftables.service(stop)'
    firewall_changed=true
fi
if $firewall_changed || ! systemctl is-active --quiet vm-lab-firewall.service; then
    warn '방화벽 규칙을 적용한다. SSH 가 끊길 수 있으므로 VirtualBox 콘솔을 열어 둔다.'
    systemctl restart vm-lab-firewall.service
    record 'firewall(apply)'
fi

# --- 11. 진단 타이머 ---------------------------------------------------------------
chown root:root /var/log/vm-lab
chmod 0755 /var/log/vm-lab
touch /var/log/vm-lab/host-check.log
chown root:root /var/log/vm-lab/host-check.log
chmod 0644 /var/log/vm-lab/host-check.log
systemd-analyze verify /etc/systemd/system/vm-lab-firewall.service \
    /etc/systemd/system/vm-lab-host-check.service \
    /etc/systemd/system/vm-lab-host-check.timer \
    /etc/systemd/system/node_exporter.service || warn 'systemd-analyze verify 가 경고를 냈다. 위 출력을 확인한다.'
systemctl enable --now vm-lab-host-check.timer >/dev/null
systemctl start vm-lab-host-check.service || warn '첫 진단 실행이 실패했다. journalctl -u vm-lab-host-check.service 를 본다.'

# --- 결과 --------------------------------------------------------------------------
printf '\n'
if ((${#changed[@]})); then
    printf 'applied host=%s changed=%d\n' "$host" "${#changed[@]}"
else
    printf 'applied host=%s changed=0 (이미 같은 상태였다)\n' "$host"
fi
cat <<EOF

다음으로 직접 확인한다. 아래를 실행해 결과를 보기 전에는 통과로 적지 않는다.
  ss -lntp                       22 는 $mgmt_ip, 9100 은 $exporter_bind 에만 보인다. 0.0.0.0 이 없다.
  sudo nft list table inet vmlab input 과 forward 가 policy drop 이고 counter 가 붙어 있다.
  getenforce                     Enforcing 이다.
  sudo ausearch -m AVC -ts boot  부팅 이후 AVC 를 본다. 나오면 끄지 말고 원인을 기록한다.
  sudo sshd -T | grep -Ei '^(permitrootlogin|passwordauthentication|authenticationmethods|listenaddress|allowusers|allowtcpforwarding|maxauthtries)'
  systemctl is-enabled sshd chronyd node_exporter vm-lab-firewall vm-lab-host-check.timer
  systemctl is-enabled firewalld nftables 둘 다 masked 다. 정책원은 vm-lab-firewall 하나다.
  curl -s http://$exporter_bind:9100/metrics | head -1   OPS01 을 포함한 여섯 대에서 lab-lan 주소로 응답한다.
  journalctl -k --since '-10min' --grep 'vmlab-drop'      차단 로그를 본다. 분당 5건으로 제한한 표본이다.
  bash /opt/vm-lab/check-host.sh --inspect   마지막 줄이 collection_success=1 이다.
  sudo bash /opt/vm-lab/check-host.sh --verify
  sudo systemctl reboot 후 위 확인을 다시 실행한다.
EOF
