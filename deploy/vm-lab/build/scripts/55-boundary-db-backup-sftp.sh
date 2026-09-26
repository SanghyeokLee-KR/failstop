#!/usr/bin/env bash
# pfSense 경계형: EDGE01 의 백업 보관 SFTP 를 OPS01 lab-lan 주소 기준으로 다시 연다. EDGE01 에서 sudo 로 실행한다.
# 사용: scp build-files/ops/edge01-db-backup-sshd.conf build-files/ops/edge01-db-backup-sftp.nft edge01:/tmp/
#       ssh edge01 'sudo bash -s' < scripts/55-boundary-db-backup-sftp.sh
set -euo pipefail
c=/etc/ssh/sshd_config.d/30-db-backup.conf
n=/etc/vm-lab/firewall.d/30-db-backup-sftp.nft
cp -p "$c" "$c.bak-before-pfsense"
install -o root -g root -m 0600 /tmp/edge01-db-backup-sshd.conf "$c"
install -o root -g root -m 0600 /tmp/edge01-db-backup-sftp.nft "$n"
rm -f /tmp/edge01-db-backup-sshd.conf /tmp/edge01-db-backup-sftp.nft
restorecon "$c" "$n"
sshd -t && systemctl reload sshd
nft -c -f /etc/vm-lab/firewall.nft && systemctl reload vm-lab-firewall
grep ^AllowUsers "$c"
nft list chain inet vmlab roles | grep "백업 SFTP"
