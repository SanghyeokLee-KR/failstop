#!/usr/bin/env bash
# pfSense 경계형 전환 1단계. VM 안에서 sudo 로 실행한다. NIC 는 아직 떼지 않는다.
# 순서와 전후 조건은 ../00-주소계획.md 의 '경계형 전환 절차'에 있다.
# 사용: ssh ops@<host-only 주소> 'sudo bash -s' < 50-boundary-vm-network.sh        (OPS01 은 'sudo bash -s ops')
set -euo pipefail
ROLE="${1:-}"
echo "## $(hostname) $(date '+%F %T %Z')"

# 1. 관리 SSH 의 새 경로. host-only NIC 를 떼면 작업 PC(192.168.56.1)의 SSH 는 pfSense 를 지나 lab-lan(enp0s8)으로 들어온다.
f=/etc/vm-lab/firewall.d/10-pfsense-boundary.nft
{
  echo '# pfSense 경계형 전환(2026-09-26). 관리 SSH 는 pfSense(192.168.56.10)가 라우팅해 lab-lan(enp0s8)으로 들어온다.'
  echo '# 출발지는 작업 PC 192.168.56.1 하나다. pfSense 는 이 경로에서 출발지를 바꾸지 않는다.'
  echo 'add rule inet vmlab roles iifname "enp0s8" ip saddr 192.168.56.1 tcp dport 22 ct state new counter accept comment "관리 SSH(pfSense 경유)"'
  if [ "$ROLE" = ops ]; then
    echo '# pfSense 방화벽 이벤트 원격 로그. 출발지는 pfSense 의 LAN 주소 하나다.'
    echo 'add rule inet vmlab roles iifname "enp0s8" ip saddr 192.168.10.1 udp dport 514 counter accept comment "pfSense 원격 로그"'
  fi
} > "$f"
chmod 0600 "$f"; chown root:root "$f"
nft -c -f /etc/vm-lab/firewall.nft && systemctl reload vm-lab-firewall
nft list chain inet vmlab roles | grep -E 'pfSense' || true

# 2. 기본 경로와 DNS 를 pfSense 로. NAT NIC(enp0s3)는 기본 경로와 DNS 에서 뺀다(NIC 제거 전 단계).
nmcli con mod enp0s8 ipv4.gateway 192.168.10.1 ipv4.dns 192.168.10.1 ipv4.ignore-auto-dns yes
nmcli con mod enp0s3 ipv4.never-default yes ipv4.ignore-auto-dns yes
nmcli con up enp0s8 >/dev/null; nmcli con up enp0s3 >/dev/null
ip -4 route | grep default
grep ^nameserver /etc/resolv.conf

# 3. 시간 동기화를 pfSense 로.
sed -i -E 's/^(pool|server) /#&/' /etc/chrony.conf
grep -q '^server 192.168.10.1 ' /etc/chrony.conf || echo 'server 192.168.10.1 iburst' >> /etc/chrony.conf
systemctl restart chronyd

# 4. OPS01: pfSense 원격 로그 수신.
if [ "$ROLE" = ops ]; then
  install -d -m 0750 -o root -g root /var/log/pfsense
  cat > /etc/rsyslog.d/10-pfsense.conf <<'EOF'
# pfSense(192.168.10.1) 방화벽 이벤트를 lab-lan 주소의 UDP 514 로 받아 별도 파일에 쓴다.
module(load="imudp")
input(type="imudp" address="192.168.10.41" port="514" ruleset="pfsense")
ruleset(name="pfsense") { action(type="omfile" file="/var/log/pfsense/firewall.log") }
EOF
  restorecon -Rv /var/log/pfsense /etc/rsyslog.d/10-pfsense.conf >/dev/null
  rsyslogd -N1 2>&1 | tail -1
  systemctl restart rsyslog
  ss -ulnp | grep ':514 ' || echo 'UDP 514 미수신'
fi

# 확인: DNS·NTP 가 pfSense 로 가는지
sleep 3
getent hosts dl.rockylinux.org || echo 'DNS 실패'
chronyc -n sources | tail -n +3
