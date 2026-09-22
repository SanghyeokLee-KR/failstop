#!/usr/bin/env bash
# 역할별 설치를 순서대로 돌린다. 마감 시각에 도달하면 새 단계를 시작하지 않는다.
# 순서 근거: DB 가 서야 앱이 스키마를 적용하고, 관측은 대상이 선 뒤에 붙는다.
set -uo pipefail
DEADLINE_EPOCH=$(date -d "today 21:45" +%s 2>/dev/null || echo 0)
KEY="$HOME/.ssh/vm-lab_ed25519"
LOG="/c/Users/kopo26/Desktop/vm-lab/캡처/역할설치"
STAGE="/c/Users/kopo26/Desktop/vm-lab/stage"
KH="/c/Users/kopo26/Desktop/vm-lab/캡처/공통설치/known_hosts"
mkdir -p "$LOG"
OPT=(-i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=accept-new
     -o UserKnownHostsFile="$KH" -o ConnectTimeout=20)
clean() { grep -viE 'post-quantum|store now|may need to be|^\*\*$'; }
left() { echo $(( DEADLINE_EPOCH - $(date +%s) )); }
guard() { # 남은 시간이 부족하면 단계를 시작하지 않는다
  local need=$1 name=$2
  if (( $(left) < need )); then
    echo "!! 시간 부족으로 건너뜀: $name (남은 $(left)초, 필요 ${need}초)" | tee -a "$LOG/00-진행.txt"
    return 1
  fi
  return 0
}
run() { # $1=VM ip, $2=로그이름, 나머지=원격 명령
  local ip=$1 tag=$2; shift 2
  echo "----- $tag $(date '+%H:%M:%S')" | tee -a "$LOG/00-진행.txt"
  ssh "${OPT[@]}" "ops@$ip" "$@" 2>&1 | clean | tee -a "$LOG/${tag}.txt" | tail -12
}

echo "===== 역할 설치 시작 $(date '+%H:%M:%S') 마감 21:45" | tee "$LOG/00-진행.txt"

# --- DB01 Primary ---
if guard 600 "DB01 역할"; then
  run 192.168.56.31 "DB01" 'cd /home/ops/build && sudo bash scripts/30-db-primary-setup.sh all 2>&1 | tail -60'
fi
# --- DB02 Standby ---
if guard 600 "DB02 역할"; then
  run 192.168.56.32 "DB02" 'cd /home/ops/build && sudo bash scripts/31-db-standby-setup.sh all 2>&1 | tail -60'
fi
# --- APP01 (JAR 전송 후) ---
if guard 600 "APP01 역할"; then
  scp "${OPT[@]}" "$STAGE/app-0.1.0-SNAPSHOT.jar" ops@192.168.56.21:/home/ops/ 2>&1 | clean
  run 192.168.56.21 "APP01" 'cd /home/ops/build && sudo bash scripts/20-app-setup.sh 1 --jar /home/ops/app-0.1.0-SNAPSHOT.jar --origin https://192.168.56.11 2>&1 | tail -50'
fi
# --- APP02 ---
if guard 600 "APP02 역할"; then
  scp "${OPT[@]}" "$STAGE/app-0.1.0-SNAPSHOT.jar" ops@192.168.56.22:/home/ops/ 2>&1 | clean
  run 192.168.56.22 "APP02" 'cd /home/ops/build && sudo bash scripts/20-app-setup.sh 2 --jar /home/ops/app-0.1.0-SNAPSHOT.jar --origin https://192.168.56.11 2>&1 | tail -50'
fi
# --- EDGE01 ---
# all 에는 install-web 이 없다. 웹 번들을 따로 올리고 배치한 뒤 나머지를 돌린다.
if guard 480 "EDGE01 역할"; then
  scp "${OPT[@]}" "$STAGE/web-out.tar.gz" ops@192.168.56.11:/home/ops/ 2>&1 | clean
  run 192.168.56.11 "EDGE01" 'cd /home/ops/build && sudo bash scripts/10-edge01-setup.sh all 2>&1 | tail -40'
  run 192.168.56.11 "EDGE01-웹" 'cd /home/ops/build && sudo bash scripts/10-edge01-setup.sh install-web /home/ops/web-out.tar.gz 2>&1 | tail -20'
  run 192.168.56.11 "EDGE01-검증" 'cd /home/ops/build && sudo bash scripts/10-edge01-setup.sh verify 2>&1 | tail -25'
fi
# --- OPS01 관측 + 보고 ---
if guard 600 "OPS01 관측"; then
  run 192.168.56.41 "OPS01-관측" 'cd /home/ops/build && sudo bash scripts/40-ops01-observability.sh all 2>&1 | tail -50'
fi
if guard 300 "OPS01 보고"; then
  run 192.168.56.41 "OPS01-보고" 'cd /home/ops/build && sudo bash scripts/41-ops01-reporting.sh all 2>&1 | tail -40'
fi
echo "===== 역할 설치 종료 $(date '+%H:%M:%S')" | tee -a "$LOG/00-진행.txt"
