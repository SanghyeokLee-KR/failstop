#!/usr/bin/env bash
# OPS01 에 Databasus 복원 검증 에이전트를 둔다. 2026-09-26 실제로 실행한 절차다.
# 사용: ssh -t ops01 'sudo bash -s' < 56-ops01-databasus-verification-agent.sh   (토큰 입력이 있어 -t 필요)
#
# 전제
#   - Databasus 화면 Settings > Verification agents 에서 에이전트를 먼저 만든다. 토큰은 한 번만 보인다.
#   - 임시 PostgreSQL 이미지 databasus/verification-postgres:17-v3.59.0 은 Docker Hub 에 있다. 경계형에서는
#     OPS01 의 외부 443 이 막혀 있으므로, pfSense LAN 규칙 "패키지 저장소 443 (점검 때만 켬)"을 켜고
#     docker pull 한 뒤 바로 끈다. 이 스크립트는 이미지를 받지 않는다.
#   - 토큰은 화면과 셸 기록에 남지 않게 프롬프트로만 받는다. 에이전트는 로그와 설정 파일에 앞 8자만 남긴다.
set -euo pipefail
d=/opt/databasus-agent
install -d -m 0750 -o root -g root "$d"
cd "$d"
# 바이너리는 인터넷이 아니라 같은 호스트의 Databasus 에서 받는다(루프백 4005).
curl -fsS -o verification-agent "http://127.0.0.1:4005/api/v1/system/verification-agent?arch=amd64"
chmod 0750 verification-agent
sha256sum verification-agent
docker image inspect databasus/verification-postgres:17-v3.59.0 >/dev/null \
  || { echo "검증 이미지가 없다. pfSense 규칙을 잠깐 켜고 docker pull 한 뒤 다시 실행한다."; exit 1; }
# 토큰은 한 번만 보이므로 먼저 받는다. 토큰 창을 닫은 뒤 목록에서 Agent ID 를 확인해 넣는다.
read -rsp "Token (입력해도 화면에 보이지 않음): " TOKEN; echo
read -rp "Agent ID: " AGENT_ID
# 자원 한도: 동시 1건, CPU 1, RAM 1GB, 디스크 6GB(백업+원본 크기+여유 5GB).
./verification-agent start --databasus-host=http://127.0.0.1:4005 --allow-insecure-http \
  --agent-id="$AGENT_ID" --token="$TOKEN" \
  --max-cpu=1 --max-ram-mb=1024 --max-disk-gb=6 --max-concurrent-jobs=1 \
  --skip-update --verification-pg-image-repo=databasus/verification-postgres
unset TOKEN
sleep 3
chmod 0600 databasus-verification.json databasus-verification.log databasus-verification.daemon.log 2>/dev/null || true
./verification-agent status
# 로그의 토큰 줄을 sed -i 로 고치지 않는다. 파일이 새로 만들어져 에이전트가 지운 파일에 계속 쓴다(2026-09-26 겪음).
