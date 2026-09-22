#!/usr/bin/env bash
# DB02 공통 설치가 끝나면 Standby 구성을 이어서 돌린다.
# passfile 은 대화형이라 그 앞까지만 한다.
set -uo pipefail
KEY="$HOME/.ssh/vm-lab_ed25519"
KH="/c/Users/kopo26/Desktop/vm-lab/캡처/공통설치/known_hosts"
LOG="/c/Users/kopo26/Desktop/vm-lab/캡처/진행-db02.txt"
OPT=(-i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=accept-new
     -o UserKnownHostsFile="$KH" -o ConnectTimeout=15)
IP=192.168.56.32

echo "체인 시작 $(date '+%H:%M:%S')" | tee "$LOG"

# 1) SSH 를 기다린다(최대 40분).
for ((i=0; i<120; i++)); do
  timeout 5 bash -c "</dev/tcp/$IP/22" 2>/dev/null && break
  sleep 20
done
timeout 5 bash -c "</dev/tcp/$IP/22" 2>/dev/null || { echo "SSH 안 열림. 중단" | tee -a "$LOG"; exit 1; }
echo "SSH 열림 $(date '+%H:%M:%S')" | tee -a "$LOG"

# 2) 공통 설치가 끝났는지 산출물로 확인한다(최대 25분).
#    05-node-common.sh 가 만드는 /etc/vm-lab/firewall.nft 와 node_exporter 유닛을 본다.
for ((i=0; i<75; i++)); do
  if ssh "${OPT[@]}" "ops@$IP" 'test -f /etc/vm-lab/firewall.nft && systemctl is-enabled vm-lab-firewall >/dev/null 2>&1' 2>/dev/null; then
    echo "공통 설치 완료 감지 $(date '+%H:%M:%S')" | tee -a "$LOG"; break
  fi
  sleep 20
done
ssh "${OPT[@]}" "ops@$IP" 'test -f /etc/vm-lab/firewall.nft' 2>/dev/null \
  || { echo "공통 설치가 끝나지 않았다. Standby 구성을 시작하지 않는다." | tee -a "$LOG"; exit 1; }

# 3) Standby 구성. 비밀번호가 필요 없는 단계까지.
echo "Standby 구성 시작 $(date '+%H:%M:%S')" | tee -a "$LOG"
bash /c/Users/kopo26/Desktop/vm-lab/build/scripts/06-db02-standby.sh 2>&1 | tail -45
echo "체인 종료 $(date '+%H:%M:%S')" | tee -a "$LOG"
