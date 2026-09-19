#!/bin/sh
set -eu
if [ "$(id -u)" -ne 0 ]; then
  echo "run as root" >&2
  exit 1
fi
# 운영 비밀은 이 스크립트가 생성하거나 복사하지 않는다.
if [ ! -f /etc/failstop/backup.env ] || [ -L /etc/failstop/backup.env ] || \
   [ "$(stat -c '%u:%a' /etc/failstop/backup.env)" != 0:600 ]; then
  echo "/etc/failstop/backup.env must be root-owned, mode 0600" >&2
  exit 1
fi
if [ ! -f /opt/failstop/deploy/backup/backup.py ]; then
  echo "install the repository at /opt/failstop first" >&2
  exit 1
fi
install -d -o root -g root -m 0700 /var/backups/failstop
install -d -o root -g root -m 0755 /var/lib/failstop/metrics
install -o root -g root -m 0644 /opt/failstop/deploy/backup/failstop-backup.service /etc/systemd/system/
install -o root -g root -m 0644 /opt/failstop/deploy/backup/failstop-backup.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now failstop-backup.timer
systemctl list-timers failstop-backup.timer --no-pager
