#!/usr/bin/env bash
# 다섯 대가 올라오는 대로 자료를 보내고 공통 설치를 돌린 뒤 검증까지 한다.
# EDGE01 은 이미 끝났으므로 대상이 아니다. 각 VM 의 출력은 증거로 파일에 남긴다.
set -uo pipefail

KEY="$HOME/.ssh/vm-lab_ed25519"
B="/c/Users/kopo26/Desktop/vm-lab/build"
LOG="/c/Users/kopo26/Desktop/vm-lab/캡처/공통설치"
KH="$LOG/known_hosts"
OPT=(-i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=accept-new
     -o UserKnownHostsFile="$KH" -o ConnectTimeout=20)
clean() { grep -viE 'post-quantum|store now|may need to be|^\*\*$'; }

declare -A IP=( [APP01]=192.168.56.21 [APP02]=192.168.56.22 [DB01]=192.168.56.31
                [DB02]=192.168.56.32 [OPS01]=192.168.56.41 )
ORDER=(DB01 DB02 APP01 APP02 OPS01)

wait_ssh() {  # $1=ip, 최대 40분
  local ip=$1 i
  for ((i=0; i<120; i++)); do
    if timeout 5 bash -c "</dev/tcp/$ip/22" 2>/dev/null; then return 0; fi
    sleep 20
  done
  return 1
}

for n in "${ORDER[@]}"; do
  ip=${IP[$n]}
  out="$LOG/${n}_공통설치.txt"
  echo "===== $n ($ip) 대기" | tee "$out"
  if ! wait_ssh "$ip"; then echo "  SSH 열리지 않음. 건너뛴다." | tee -a "$out"; continue; fi
  echo "  SSH 열림 $(date '+%H:%M:%S')" | tee -a "$out"

  {
    echo "########## 설치 직후 상태"
    ssh "${OPT[@]}" "ops@$ip" 'hostnamectl --static; ip -br addr | grep -v " lo "; lsblk -o NAME,SIZE,MOUNTPOINT | head -14; getenforce; echo "실패 서비스 $(systemctl --failed --no-legend | wc -l)개"' 2>&1 | clean

    echo "########## 자료 전송 (keys 제외)"
    ssh "${OPT[@]}" "ops@$ip" 'rm -rf /home/ops/build && mkdir -p /home/ops/build' 2>&1 | clean
    scp "${OPT[@]}" -r "$B/scripts" "$B/build-files" "$B/ks" "$B/00-주소계획.md" "$B/02-정본결정.md" "ops@$ip:/home/ops/build/" 2>&1 | clean
    ssh "${OPT[@]}" "ops@$ip" 'echo "개인키 유입: $(find /home/ops/build -name "*ed25519*" -not -name "*.pub" | wc -l)건"' 2>&1 | clean

    echo "########## --plan"
    ssh "${OPT[@]}" "ops@$ip" 'cd /home/ops/build && bash scripts/00-common-setup.sh --plan' 2>&1 | clean | head -32

    echo "########## --apply --download"
    ssh "${OPT[@]}" "ops@$ip" 'cd /home/ops/build && sudo bash scripts/00-common-setup.sh --apply --download 2>&1 | grep -E "^(changed=|applied|ERROR|WARN)"' 2>&1 | clean

    echo "########## 검증"
    ssh "${OPT[@]}" "ops@$ip" 'bash -s' <<'V' 2>&1 | clean
sudo ss -lntp | awk '/:22|:9100/'
sudo nft list table inet vmlab | grep -E 'chain (input|roles)|policy drop|jump roles'
echo "SELinux=$(getenforce)  AVC=$(sudo ausearch -m AVC -ts boot 2>&1 | grep -c '^type=AVC')"
sudo sshd -T | grep -Ei '^(permitrootlogin|passwordauthentication|listenaddress|allowtcpforwarding)'
echo "유닛: $(systemctl is-enabled sshd chronyd node_exporter vm-lab-firewall vm-lab-host-check.timer | tr '\n' ' ')"
echo "정책원: firewalld/nftables = $(systemctl is-enabled firewalld nftables 2>&1 | tr '\n' ' ')"
echo "지표: $(curl -s --max-time 5 "http://$(ip -4 -br addr show enp0s8 | awk '{print $3}' | cut -d/ -f1):9100/metrics" | head -1 | cut -c1-40)"
bash /opt/vm-lab/check-host.sh --inspect 2>&1 | tail -1
V
  } >> "$out" 2>&1
  echo "  완료 -> $out"
done
echo "===== 다섯 대 공통 설치 절차 종료 $(date '+%H:%M:%S')"
