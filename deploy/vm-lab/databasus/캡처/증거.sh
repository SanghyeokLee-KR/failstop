#!/usr/bin/env bash
# Databasus 예행 증거 수집 도우미.
#   증거.sh <번호_이름> <대상호스트IP> <원격에서 실행할 명령>
# 파일 첫 줄에 원격의 UTC 시각과 호스트 이름을 넣고, 실행한 명령과 결과를 같은 파일에 남긴다.
# 시각과 이름을 원격에서 찍는 이유는 이 파일이 어느 서버의 출력인지 스스로 증명하게 하기 위해서다.
set -uo pipefail
NAME=${1:?번호_이름}; IP=${2:?대상IP}; shift 2
CMD="$*"
DIR="/c/Users/kopo26/Desktop/vm-lab/databasus/캡처/예행-2026-09-22"
LOGF="$DIR/Linux_명령실행.log"
KEY="$HOME/.ssh/vm-lab_ed25519"
OPT=(-i "$KEY" -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=no
     -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -o ConnectTimeout=15)
mkdir -p "$DIR"
out="$DIR/${NAME}.txt"
{
  ssh "${OPT[@]}" "ops@$IP" 'printf "%s  %s\n" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$(hostname)"' 2>/dev/null
  printf '\n# 실행 대상 %s\n# 실행 명령\n%s\n\n' "$IP" "$CMD"
  ssh "${OPT[@]}" "ops@$IP" "$CMD" 2>&1
} > "$out"
{ printf '\n%s  %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$NAME"
  printf '%s\n' "ssh ops@$IP '$CMD'"; } >> "$LOGF"
echo "저장: ${NAME}.txt ($(wc -l < "$out")행)"
