"""운영자가 터미널에 직접 입력한 비밀번호를 bcrypt 해시로 저장한다."""
import getpass
import os
from pathlib import Path
import shutil
import subprocess

target = Path('/etc/nginx/admin.htpasswd')
if os.geteuid() != 0 or target.exists():
    raise SystemExit('root 권한으로 실행하며 기존 계정 파일은 덮어쓰지 않는다.')
os.umask(0o077)
print('봇 운영자 아이디: dltkdgur (Grafana와 별도 계정)')
password = getpass.getpass('새 비밀번호 (12자 이상): ')
confirm = getpass.getpass('새 비밀번호 다시 입력: ')
if password != confirm or len(password) < 12 or len(password.encode()) > 72:
    raise SystemExit('입력 불일치 또는 길이 오류. 12자 이상, UTF-8 72바이트 이하로 다시 실행한다.')
result = subprocess.run(['htpasswd', '-niB', '-C', '12', 'dltkdgur'],
                        input=(password + '\n').encode(), capture_output=True)
if result.returncode:
    raise SystemExit('계정 해시 생성 실패. 파일을 변경하지 않았다.')
with target.open('xb') as file:
    file.write(result.stdout)
shutil.chown(target, user='root', group='nginx')
target.chmod(0o640)
print('봇 운영자 계정 생성 완료. 비밀번호와 해시는 출력하지 않았다.')
