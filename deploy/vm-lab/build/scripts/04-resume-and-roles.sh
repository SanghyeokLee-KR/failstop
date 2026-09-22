#!/usr/bin/env bash
# DB01, DB02, APP01 이 올라오면 정지해 둔 APP02, OPS01 을 재개한다.
# 그다음 다섯 대 공통 설치가 끝나기를 기다렸다가 역할 설치로 넘어간다.
set -uo pipefail
VBM="/c/Program Files/Oracle/VirtualBox/VBoxManage.exe"
LOG="/c/Users/kopo26/Desktop/vm-lab/캡처/진행.txt"
up() { timeout 4 bash -c "</dev/tcp/$1/22" 2>/dev/null; }

echo "감시 시작 $(date '+%H:%M:%S')" | tee "$LOG"

# 1단계. 먼저 도는 세 대를 기다린다.
for ((i=0; i<90; i++)); do
  a=0; up 192.168.56.31 && a=$((a+1)); up 192.168.56.32 && a=$((a+1)); up 192.168.56.21 && a=$((a+1))
  echo "$(date '+%H:%M:%S') 선행 3대 중 $a 대 준비" | tee -a "$LOG"
  (( a >= 3 )) && break
  sleep 20
done

# 2단계. 경합이 줄었으니 정지한 두 대를 재개한다.
for v in APP02 OPS01; do
  # VM 이 없으면 grep 이 1 을 돌려주고 pipefail 이 그 값을 쓴다. 빈 값으로 받아 뒤에서 판단한다.
  st=$("$VBM" showvminfo "$v" --machinereadable 2>/dev/null | grep '^VMState=' | cut -d'"' -f2) || st=''
  if [[ $st == paused ]]; then
    "$VBM" controlvm "$v" resume >/dev/null 2>&1 && echo "$v 재개" | tee -a "$LOG"
  else
    echo "$v 상태 $st" | tee -a "$LOG"
  fi
done

# 3단계. 공통 설치 자동화가 다섯 대를 마칠 때까지 기다린다.
for ((i=0; i<120; i++)); do
  if grep -q '다섯 대 공통 설치 절차 종료' "/c/Users/kopo26/AppData/Local/Temp/claude/C--Users-kopo26-Desktop-failstop/9779e47b-0c27-4c1a-94b1-d8b85ca0cb2c/tasks/b7vwgj5au.output" 2>/dev/null; then
    echo "공통 설치 완료 감지 $(date '+%H:%M:%S')" | tee -a "$LOG"; break
  fi
  sleep 20
done

# 4단계. 역할 설치. 스크립트 안에 21:45 마감 가드가 있다.
echo "역할 설치 시작 $(date '+%H:%M:%S')" | tee -a "$LOG"
bash /c/Users/kopo26/Desktop/vm-lab/build/scripts/03-roles-all.sh 2>&1 | tail -40
echo "전체 절차 종료 $(date '+%H:%M:%S')" | tee -a "$LOG"
