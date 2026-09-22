#!/usr/bin/env bash
# DB02 Standby 구성 중 비밀번호가 필요 없는 단계를 돌린다.
# passfile 단계는 대화형이라 사용자가 직접 한다. 이 스크립트는 그 앞까지만 한다.
set -uo pipefail
KEY="$HOME/.ssh/vm-lab_ed25519"
KH="/c/Users/kopo26/Desktop/vm-lab/캡처/공통설치/known_hosts"
L="/c/Users/kopo26/Desktop/vm-lab/캡처/역할설치"; mkdir -p "$L"
SP="/c/Users/kopo26/AppData/Local/Temp/claude/C--Users-kopo26-Desktop-failstop/9779e47b-0c27-4c1a-94b1-d8b85ca0cb2c/scratchpad"
OPT=(-i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=accept-new
     -o UserKnownHostsFile="$KH" -o ConnectTimeout=20)
clean() { grep -viE 'post-quantum|store now|may need to be|^\*\*$'; }
D1=ops@192.168.56.31
D2=ops@192.168.56.32
out="$L/DB02_역할.txt"

echo "===== DB02 Standby 구성 $(date '+%H:%M:%S')" | tee "$out"

echo "--- 1) 비밀번호가 필요 없는 단계" | tee -a "$out"
ssh "${OPT[@]}" "$D2" 'cd /opt/vm-lab && sudo bash scripts/31-db-standby-setup.sh preflight disk packages config tls 2>&1 | tail -40' 2>&1 | clean | tee -a "$out" | tail -18

echo "--- 2) DB01 공개 인증서를 가져온다" | tee -a "$out"
# 인증서 경로는 postgresql.conf 의 ssl_cert_file 에서 읽는다. 추정하지 않는다.
ssh "${OPT[@]}" "$D1" 'sudo cat "$(sudo -u postgres psql -Xqt -c "show ssl_cert_file;" | tr -d " ")"' > "$SP/db01-server.crt" 2>/dev/null
if [[ -s "$SP/db01-server.crt" ]] && head -1 "$SP/db01-server.crt" | grep -q 'BEGIN CERTIFICATE'; then
  echo "인증서 확보: $(wc -l < "$SP/db01-server.crt")줄" | tee -a "$out"
else
  echo "!! DB01 인증서를 찾지 못했다. 경로를 확인한다." | tee -a "$out"; exit 1
fi

echo "--- 3) DB01 인증서 지문 (대조용)" | tee -a "$out"
FP=$(openssl x509 -in "$SP/db01-server.crt" -noout -fingerprint -sha256 2>/dev/null | cut -d= -f2)
echo "지문: $FP" | tee -a "$out"

echo "--- 4) DB02 에 배치하고 대조" | tee -a "$out"
scp "${OPT[@]}" "$SP/db01-server.crt" "$D2:/tmp/db01-server.crt" 2>&1 | clean
ssh "${OPT[@]}" "$D2" "cd /opt/vm-lab && sudo bash scripts/31-db-standby-setup.sh primary-cert /tmp/db01-server.crt 2>&1 | tail -12" 2>&1 | clean | tee -a "$out"
ssh "${OPT[@]}" "$D2" "cd /opt/vm-lab && sudo bash scripts/31-db-standby-setup.sh confirm-primary '$FP' 2>&1 | tail -12" 2>&1 | clean | tee -a "$out"

echo "" | tee -a "$out"
echo "===== 여기까지 자동. 다음은 사용자가 직접 한다 =====" | tee -a "$out"
echo "  ssh -i C:\Users\kopo26\.ssh\vm-lab_ed25519 ops@192.168.56.32" | tee -a "$out"
echo "  cd /opt/vm-lab && sudo bash scripts/31-db-standby-setup.sh passfile" | tee -a "$out"
echo "  (DB01 에서 정한 replicator 비밀번호를 넣는다)" | tee -a "$out"
