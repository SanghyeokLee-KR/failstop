#!/usr/bin/env bash
# 최초 관리자만 만든다. 비밀번호는 운영자가 터미널에서 직접 입력한다.
set -euo pipefail
test "$(id -u)" -eq 0 || { echo 'sudo로 실행하세요.'; exit 1; }

config=/etc/grafana/grafana.ini
database=/var/lib/grafana/grafana.db
count=$(python3 -c 'import sqlite3; c=sqlite3.connect("file:/var/lib/grafana/grafana.db?mode=ro",uri=True); print(c.execute("SELECT count(*) FROM user").fetchone()[0])')
test "$count" = 0 || { echo '기존 사용자가 있어 최초 관리자 생성을 중단합니다.'; exit 1; }

read -r -p '관리자 아이디 [sanghyuk]: ' admin_user
admin_user=${admin_user:-sanghyuk}
[[ "$admin_user" =~ ^[a-zA-Z0-9._-]{3,40}$ ]] || { echo '아이디는 영문·숫자·점·밑줄·하이픈 3~40자로 입력하세요.'; exit 1; }
read -r -s -p '새 비밀번호 (12자 이상, 화면에 표시되지 않음): ' first
echo
read -r -s -p '새 비밀번호 다시 입력: ' second
echo
test "$first" = "$second" || { echo '비밀번호가 다릅니다. 변경하지 않았습니다.'; exit 1; }
test "${#first}" -ge 12 || { echo '비밀번호는 12자 이상이어야 합니다.'; exit 1; }
[[ "$first" != [[:space:]]* && "$first" != *[[:space:]] ]] || { echo '비밀번호 앞뒤에는 공백을 쓰지 마세요.'; exit 1; }

backup=$(mktemp /etc/grafana/bootstrap-config.XXXXXX)
secret=$(mktemp /etc/grafana/bootstrap-password.XXXXXX)
cp -- "$config" "$backup"
chmod 600 "$backup"
cleanup() {
    cp -- "$backup" "$config"
    rm -f -- "$backup" "$secret"
    systemctl restart grafana-server
}
trap cleanup EXIT
printf '%s' "$first" > "$secret"
unset first second
chown root:grafana "$secret"
chmod 640 "$secret"
python3 - "$config" "$secret" "$admin_user" <<'PY'
import configparser
import sys
path, secret, username = sys.argv[1:]
config = configparser.ConfigParser(interpolation=None)
config.read(path)
config['security']['disable_initial_admin_creation'] = 'false'
config['security']['admin_user'] = username
config['security']['admin_password'] = '$__file{' + secret + '}'
with open(path, 'w', encoding='utf-8') as handle:
    config.write(handle)
PY
systemctl restart grafana-server
for attempt in {1..30}; do
    if curl -fsS --max-time 2 http://127.0.0.1:3000/api/health >/dev/null; then
        break
    fi
    sleep 1
done
python3 - "$admin_user" <<'PY'
import sqlite3
import sys
database = sqlite3.connect('file:/var/lib/grafana/grafana.db?mode=ro', uri=True)
row = database.execute('SELECT login,is_admin FROM user WHERE login=?', (sys.argv[1],)).fetchone()
if not row or row[1] != 1:
    raise SystemExit('관리자 생성 확인 실패. 서비스 로그를 확인하세요.')
print('관리자 생성 확인:', row[0])
PY
echo '기존 접근 설정을 복원하고 임시 비밀번호 파일을 제거합니다.'
