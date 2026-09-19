"""기존 HTTP 미리보기의 PC 제한을 유지하면서 인증서 검증 경로를 준비한다."""
from pathlib import Path
import shutil
import subprocess

config = Path('/etc/nginx/nginx.conf')
backup = Path('/etc/nginx/nginx.conf.before-acme')
if backup.exists():
    raise SystemExit('기존 백업이 있다. 적용 상태를 확인한 뒤 진행한다.')
text = config.read_text()
if text.count('server_name _;') != 1 or 'listen 80 default_server;' not in text:
    raise SystemExit('예상한 HTTP 미리보기 설정이 아니다.')
shutil.copy2(config, backup)
text = text.replace('server_name _;', '''server_name www.sang.it.kr;
        allow 175.210.93.41;
        deny all;
        location ^~ /.well-known/acme-challenge/ {
            allow all;
            root /var/lib/letsencrypt;
            default_type text/plain;
            try_files $uri =404;
        }''')
Path('/var/lib/letsencrypt/.well-known/acme-challenge').mkdir(parents=True, exist_ok=True)
config.write_text(text)
try:
    subprocess.run(['nginx', '-t'], check=True)
    subprocess.run(['systemctl', 'reload', 'nginx'], check=True)
except subprocess.CalledProcessError:
    shutil.copy2(backup, config)
    raise
print('ACME 경로 준비 완료. 기존 앱 미리보기는 PC IP만 허용한다.')
