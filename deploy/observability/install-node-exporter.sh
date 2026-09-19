#!/usr/bin/env bash
# 호스트 커널과 systemd를 읽으므로 Docker 컨테이너가 아니라 호스트에 설치한다.
set -euo pipefail
version=1.10.2
listen=127.0.0.1:9100
evidence_group=root
profile=linux
apply=false
while (($#)); do
  case "$1" in
    --apply) apply=true; shift ;;
    --listen) listen=$2; shift 2 ;;
    --evidence-group) evidence_group=$2; shift 2 ;;
    --profile) profile=$2; shift 2 ;;
    *) echo 'Usage: install-node-exporter.sh [--apply] [--listen private-address:9100] [--evidence-group existing-group] [--profile linux|wsl]' >&2; exit 2 ;;
  esac
done
[[ $listen =~ ^[0-9.]+:[0-9]+$ ]] || { echo 'IPv4 address:port required' >&2; exit 2; }
[[ $evidence_group =~ ^[a-z_][a-z0-9_-]*$ ]] || { echo 'Invalid evidence group' >&2; exit 2; }
case "$profile" in
  linux) collectors='' ;;
  wsl) collectors='--collector.disable-defaults --collector.cpu --collector.meminfo --collector.loadavg --collector.diskstats --collector.filesystem --collector.netdev --collector.netstat --collector.sockstat --collector.stat --collector.time --collector.uname --collector.vmstat' ;;
  *) echo 'Invalid collector profile' >&2; exit 2 ;;
esac
if ! $apply; then
  printf 'Install node_exporter %s; listen %s; profile %s; collect systemd and host operations evidence.\n' "$version" "$listen" "$profile"
  exit 0
fi
[[ $EUID == 0 ]] || { echo 'root required' >&2; exit 1; }
case $(uname -m) in x86_64) arch=amd64 ;; aarch64) arch=arm64 ;; *) echo 'Unsupported architecture' >&2; exit 1 ;; esac
for program in curl tar sha256sum systemctl python3; do command -v "$program" >/dev/null; done
getent group "$evidence_group" >/dev/null || { echo 'Evidence group must already exist' >&2; exit 1; }
temporary=$(mktemp -d)
trap 'rm -rf -- "$temporary"' EXIT
asset="node_exporter-${version}.linux-${arch}.tar.gz"
base="https://github.com/prometheus/node_exporter/releases/download/v${version}"
curl --fail --silent --show-error --location --proto '=https' "$base/$asset" -o "$temporary/$asset"
curl --fail --silent --show-error --location --proto '=https' "$base/sha256sums.txt" -o "$temporary/sha256sums.txt"
(cd "$temporary"; grep -F " $asset" sha256sums.txt > checksum; [[ $(wc -l < checksum) == 1 ]]; sha256sum -c checksum)
tar -xzf "$temporary/$asset" -C "$temporary"
getent passwd failstop-metrics >/dev/null || useradd --system --no-create-home --shell /usr/sbin/nologin failstop-metrics
install -m 0755 "$temporary/node_exporter-${version}.linux-${arch}/node_exporter" /usr/local/bin/node_exporter
install -d -o root -g root -m 0755 /var/lib/failstop/metrics /usr/local/lib/failstop
install -d -o root -g "$evidence_group" -m 0750 /var/lib/failstop/evidence
install -m 0755 "$(dirname "$0")/collect-host.py" /usr/local/lib/failstop/collect-host.py
cat > /etc/systemd/system/failstop-node-exporter.service <<EOF
[Unit]
Description=Failstop Linux metrics
After=network.target
[Service]
User=failstop-metrics
Group=failstop-metrics
ExecStart=/usr/local/bin/node_exporter --web.listen-address=$listen $collectors --collector.systemd --collector.textfile --collector.systemd.unit-include=failstop.* --collector.textfile.directory=/var/lib/failstop/metrics --collector.filesystem.mount-points-exclude=^/(dev|proc|sys|run/user|run/credentials|var/lib/docker|var/lib/containers/storage)($|/)
Restart=on-failure
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
[Install]
WantedBy=multi-user.target
EOF
cat > /etc/systemd/system/failstop-host-evidence.service <<EOF
[Unit]
Description=Collect bounded Linux operating evidence
[Service]
Type=oneshot
ExecStart=/usr/bin/python3 /usr/local/lib/failstop/collect-host.py --output /var/lib/failstop/metrics --evidence /var/lib/failstop/evidence --group $evidence_group
UMask=0027
ProtectSystem=strict
ReadWritePaths=/var/lib/failstop
ProtectHome=yes
PrivateTmp=yes
NoNewPrivileges=yes
EOF
cat > /etc/systemd/system/failstop-host-evidence.timer <<'EOF'
[Unit]
Description=Collect Linux evidence every minute
[Timer]
OnBootSec=30s
OnUnitActiveSec=60s
Persistent=true
[Install]
WantedBy=timers.target
EOF
systemctl daemon-reload
systemctl enable --now failstop-node-exporter.service failstop-host-evidence.timer
systemctl restart failstop-node-exporter.service
systemctl start failstop-host-evidence.service
systemctl is-active failstop-node-exporter.service failstop-host-evidence.timer
