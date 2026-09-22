#!/usr/bin/env bash
# VM 한 대의 SSH 를 기다렸다가 자료 전송과 공통 설치, 검증까지 한다.
# 사용법: 05-node-common.sh <이름> <관리IP>
set -uo pipefail
N=${1:?이름}; IP=${2:?관리IP}
KEY="$HOME/.ssh/vm-lab_ed25519"
B="/c/Users/kopo26/Desktop/vm-lab/build"
LOG="/c/Users/kopo26/Desktop/vm-lab/캡처/공통설치"
KH="$LOG/known_hosts"; mkdir -p "$LOG"
OPT=(-i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=accept-new
     -o UserKnownHostsFile="$KH" -o ConnectTimeout=20)
clean() { grep -viE 'post-quantum|store now|may need to be|^\*\*$'; }
out="$LOG/${N}_공통설치.txt"

echo "===== $N ($IP) SSH 대기 $(date '+%H:%M:%S')" | tee "$out"
for ((i=0; i<90; i++)); do
  timeout 5 bash -c "</dev/tcp/$IP/22" 2>/dev/null && break
  sleep 20
done
timeout 5 bash -c "</dev/tcp/$IP/22" 2>/dev/null || { echo "SSH 안 열림. 중단." | tee -a "$out"; exit 1; }
echo "SSH 열림 $(date '+%H:%M:%S')" | tee -a "$out"

{
  echo "########## 설치 직후 상태"
  ssh "${OPT[@]}" "ops@$IP" 'hostnamectl --static; ip -br addr | grep -v " lo "; lsblk -o NAME,SIZE,FSTYPE,MOUNTPOINT; getenforce; echo "실패 서비스 $(systemctl --failed --no-legend | wc -l)개"' 2>&1 | clean

  echo "########## 자료 전송 (keys 제외) 후 /opt/vm-lab 로 배치"
  ssh "${OPT[@]}" "ops@$IP" 'rm -rf /home/ops/build && mkdir -p /home/ops/build' 2>&1 | clean
  scp "${OPT[@]}" -r "$B/scripts" "$B/build-files" "$B/ks" "$B/00-주소계획.md" "$B/02-정본결정.md" "ops@$IP:/home/ops/build/" 2>&1 | clean
  # /home/ops 는 0700 이라 서비스 계정이 못 읽는다. 실행은 /opt/vm-lab 에서 한다.
  ssh "${OPT[@]}" "ops@$IP" 'sudo mkdir -p /opt/vm-lab && sudo rsync -a --exclude keys/ /home/ops/build/ /opt/vm-lab/ && sudo chmod -R a+rX /opt/vm-lab && echo "개인키 유입 $(sudo find /opt/vm-lab -name "*ed25519*" -not -name "*.pub" | wc -l)건"' 2>&1 | clean

  echo "########## 공통 설치"
  ssh "${OPT[@]}" "ops@$IP" 'cd /opt/vm-lab && sudo bash scripts/00-common-setup.sh --apply --download 2>&1 | grep -E "^(changed=|applied|ERROR|WARN)"' 2>&1 | clean

  echo "########## 검증"
  ssh "${OPT[@]}" "ops@$IP" 'bash -s' <<'V' 2>&1 | clean
LAN=$(ip -4 -br addr show enp0s8 | awk '{print $3}' | cut -d/ -f1)
sudo ss -lntp | awk '/:22|:9100/'
sudo nft list table inet vmlab | grep -E 'chain (input|roles)|policy drop|jump roles'
echo "SELinux=$(getenforce) AVC=$(sudo ausearch -m AVC -ts boot 2>&1 | grep -c '^type=AVC') 실패서비스=$(systemctl --failed --no-legend | wc -l)개"
# sshd 는 절대 경로로 실행해야 -T 가 동작한다. 'sshd' 만 쓰면 재실행 경로를 못 찾아
# 오류로 끝나고, 그 뒤 검증 줄들이 출력에서 통째로 빠진다.
sudo /usr/sbin/sshd -T | grep -Ei '^(permitrootlogin|passwordauthentication|listenaddress)'
echo "유닛: $(systemctl is-enabled sshd chronyd node_exporter vm-lab-firewall vm-lab-host-check.timer | tr '\n' ' ')"
echo "정책원: $(systemctl is-enabled firewalld nftables 2>&1 | tr '\n' ' ')"
echo "지표: node $(curl -s --max-time 5 "http://$LAN:9100/metrics" | grep -c '^node_')건"
bash /opt/vm-lab/check-host.sh --inspect 2>&1 | tail -1
V
} >> "$out" 2>&1
echo "완료 -> $out $(date '+%H:%M:%S')"
tail -20 "$out"
