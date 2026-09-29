#!/usr/bin/env bash
# 투명 방화벽 전환: OPS01 이 투명 방화벽(pfsense-tp, 브리지 관리 주소 192.168.10.2)의 로그도 UDP 514 로 받는다.
# 기존 라우터(pfsense-fw, 192.168.10.1)의 방화벽 이벤트는 지금처럼 firewall.log 에 쓴다. 투명 방화벽에서 온 것은 보낸 프로그램으로 나눈다.
#   haproxy   L7 입구의 HTTP 요청 기록(복호화한 요청의 메서드·경로·상태·사용자 주소) → haproxy.log
#   suricata  IDS 경보                                                             → suricata.log
#   filterlog 방화벽 이벤트                                                         → transparent.log
#   그 밖     시스템 이벤트(서비스 기동·설정 적용 등)                                → transparent-system.log
# 모든 파일을 날마다 돌려 14개까지만 남긴다. 50-boundary-vm-network.sh 가 만든 수신 설정에는 로테이션이 없어 파일이 계속 커졌다.
# 다시 실행해도 된다. 처음 실행 때 뜬 원본 백업은 덮어쓰지 않는다. OPS01 에서 sudo 로 실행한다.
set -euo pipefail

# 1. nftables: 출발지는 투명 방화벽의 브리지 관리 주소 하나다. 서버 NIC 는 lab-ops 에 붙은 enp0s8 하나뿐이다.
f=/etc/vm-lab/firewall.d/11-pfsense-transparent.nft
cat > "$f" <<'EOF'
# 투명 방화벽(pfsense-tp) 방화벽 이벤트 원격 로그(2026-09-28). 출발지는 브리지 관리 주소 192.168.10.2 하나다.
add rule inet vmlab roles iifname "enp0s8" ip saddr 192.168.10.2 udp dport 514 counter accept comment "투명 방화벽 원격 로그"
EOF
chmod 0600 "$f"; chown root:root "$f"
nft -c -f /etc/vm-lab/firewall.nft && systemctl reload vm-lab-firewall
nft list chain inet vmlab roles | grep -E 'udp dport 514'

# 2. rsyslog: 보낸 주소로 파일을 나눈다. 목록에 없는 출발지는 nftables 에서 이미 막히지만 여기서도 버린다.
c=/etc/rsyslog.d/10-pfsense.conf
[ -e "$c.bak-before-transparent" ] || cp -p "$c" "$c.bak-before-transparent"
cat > "$c" <<'EOF'
# pfSense 두 대의 로그를 lab-ops 주소의 UDP 514 로 받아 보낸 쪽과 프로그램마다 별도 파일에 쓴다.
#   192.168.10.1  기존 라우터(pfsense-fw)                 → /var/log/pfsense/firewall.log
#   192.168.10.2  투명 방화벽(pfsense-tp) haproxy         → /var/log/pfsense/haproxy.log
#   192.168.10.2  투명 방화벽(pfsense-tp) suricata        → /var/log/pfsense/suricata.log
#   192.168.10.2  투명 방화벽(pfsense-tp) filterlog       → /var/log/pfsense/transparent.log
#   192.168.10.2  투명 방화벽(pfsense-tp) 그 밖           → /var/log/pfsense/transparent-system.log
module(load="imudp")
input(type="imudp" address="192.168.10.41" port="514" ruleset="pfsense")
ruleset(name="pfsense") {
  if $fromhost-ip == "192.168.10.1" then { action(type="omfile" file="/var/log/pfsense/firewall.log") stop }
  if $fromhost-ip == "192.168.10.2" then {
    if $programname == "haproxy" then { action(type="omfile" file="/var/log/pfsense/haproxy.log") stop }
    if $programname startswith "suricata" then { action(type="omfile" file="/var/log/pfsense/suricata.log") stop }
    if $programname == "filterlog" then { action(type="omfile" file="/var/log/pfsense/transparent.log") stop }
    action(type="omfile" file="/var/log/pfsense/transparent-system.log") stop
  }
  stop
}
EOF
rsyslogd -N1 2>&1 | tail -1
systemctl restart rsyslog
ss -ulnp | grep ':514 ' || echo 'UDP 514 미수신'

# 3. 로테이션: 날마다, 14개 보관, 압축. rsyslog 가 새 파일을 열도록 HUP 을 보낸다.
cat > /etc/logrotate.d/pfsense-remote <<'EOF'
/var/log/pfsense/firewall.log /var/log/pfsense/transparent.log /var/log/pfsense/transparent-system.log /var/log/pfsense/haproxy.log /var/log/pfsense/suricata.log {
    daily
    rotate 14
    compress
    delaycompress
    missingok
    notifempty
    create 0600 root root
    sharedscripts
    postrotate
        /usr/bin/systemctl kill -s HUP rsyslog.service >/dev/null 2>&1 || true
    endscript
}
EOF
restorecon -Rv /var/log/pfsense /etc/rsyslog.d/10-pfsense.conf /etc/logrotate.d/pfsense-remote >/dev/null
logrotate -d /etc/logrotate.d/pfsense-remote 2>&1 | grep -E 'considering log|log does not need|rotating pattern' | head -4
