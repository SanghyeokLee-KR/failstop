#!/usr/bin/env bash
# 22:00 안전 종료. 사용자가 지시한 무인 종료다.
# 21:55 에 VM 상태를 저장하고 22:00 에 PC 를 끈다. VM 은 savestate 라 다음에 이어서 쓸 수 있다.
set -uo pipefail
VBM="/c/Program Files/Oracle/VirtualBox/VBoxManage.exe"
LOG="/c/Users/kopo26/Desktop/vm-lab/캡처/마감종료.txt"
VMS=(EDGE01 APP01 APP02 DB01 DB02 OPS01)

wait_until() { # HH:MM
  local target
  target=$(date -d "today $1" +%s 2>/dev/null) || return 1
  while (( $(date +%s) < target )); do sleep 20; done
}

echo "마감 종료 대기 시작 $(date '+%H:%M:%S')" | tee "$LOG"
wait_until "21:55"

echo "=== 21:55 VM 상태 저장 $(date '+%H:%M:%S')" | tee -a "$LOG"
for v in "${VMS[@]}"; do
  # VM 이 없으면 grep 이 1 을 돌려주고 pipefail 이 그 값을 쓴다. 빈 값으로 받아 뒤에서 판단한다.
  st=$("$VBM" showvminfo "$v" --machinereadable 2>/dev/null | grep '^VMState=' | cut -d'"' -f2) || st=''
  if [[ $st == running || $st == paused ]]; then
    "$VBM" controlvm "$v" savestate >/dev/null 2>&1 && echo "  $v savestate 완료" | tee -a "$LOG" \
      || echo "  $v savestate 실패" | tee -a "$LOG"
  else
    echo "  $v 상태 $st (조치 없음)" | tee -a "$LOG"
  fi
done

echo "=== 22:00 PC 종료 예약" | tee -a "$LOG"
wait_until "21:59"
echo "종료 실행 $(date '+%H:%M:%S')" | tee -a "$LOG"
shutdown.exe /s /t 60 /c "vm-lab 작업 마감. 60초 후 종료한다. 취소는 shutdown /a"
