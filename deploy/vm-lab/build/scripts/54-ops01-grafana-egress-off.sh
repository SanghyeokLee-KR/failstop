#!/usr/bin/env bash
# OPS01 Grafana 의 grafana.com 발신을 끈다. 기존 줄(secret_key 포함)은 건드리지 않고 없는 키만 해당 절에 넣는다.
# 경계형에서는 OPS01 의 외부 443 이 막혀 업데이트·플러그인 확인이 계속 실패하고 차단 로그를 채운다.
# 새로 구축하는 OPS01 은 build-files/ops/grafana.ini 에 같은 값이 들어 있어 이 스크립트가 필요 없다.
set -euo pipefail
f=/etc/grafana/grafana.ini
cp -p "$f" "$f.before-egress-off"
put() { # put 절 키 값
  local sec=$1 key=$2 val=$3
  if ! grep -q "^\[$sec\]" "$f"; then printf '\n[%s]\n' "$sec" >> "$f"; fi
  if awk -v s="[$sec]" -v k="$key" '$0==s{on=1;next} /^\[/{on=0} on && $1==k{found=1} END{exit !found}' "$f"; then
    sed -i -E "/^\[$sec\]/,/^\[/ s|^$key *=.*|$key = $val|" "$f"
  else
    sed -i "/^\[$sec\]/a $key = $val" "$f"
  fi
}
put security disable_gravatar true
put analytics check_for_plugin_updates false
put news news_feed_enabled false
put plugins preinstall_disabled true
put plugins public_key_retrieval_disabled true
chown root:grafana "$f"; chmod 0640 "$f"; restorecon "$f"
echo "== 바뀐 줄(비밀 값 제외)"; diff "$f.before-egress-off" "$f" | grep -v secret_key || true
systemctl restart grafana-server
for i in $(seq 1 30); do curl -fsS http://127.0.0.1:3000/api/health >/dev/null 2>&1 && break; sleep 2; done
curl -fsS http://127.0.0.1:3000/api/health; echo
