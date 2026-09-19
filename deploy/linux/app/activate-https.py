"""인증서 발급 후 Nginx와 앱 오리진을 함께 전환한다."""
from pathlib import Path
import shutil
import subprocess

config = Path('/etc/nginx/nginx.conf')
env = Path('/etc/exchange/app.env')
for path in (config, env):
    backup = Path(str(path) + '.before-https')
    if backup.exists():
        raise SystemExit(f'기존 백업을 먼저 확인한다: {backup}')
for path in (config, env):
    shutil.copy2(path, str(path) + '.before-https')

candidate = Path('/tmp/nginx-https.conf')
text = env.read_text()
keys = ('EXCHANGE_CORS_ALLOWED_ORIGINS', 'APP_WS_ALLOWED_ORIGINS')
for key in keys:
    if sum(line.startswith(key + '=') for line in text.splitlines()) != 1:
        raise SystemExit(f'설정 키가 하나가 아니다: {key}')
lines = [line.split('=', 1)[0] + '=https://www.sang.it.kr'
         if any(line.startswith(key + '=') for key in keys) else line
         for line in text.splitlines()]
try:
    shutil.copyfile(candidate, config)
    subprocess.run(['nginx', '-t'], check=True)
    env.write_text('\n'.join(lines) + '\n')
    subprocess.run(['systemctl', 'restart', 'exchange-app'], check=True)
    subprocess.run(['systemctl', 'reload', 'nginx'], check=True)
except subprocess.CalledProcessError:
    for path in (config, env):
        shutil.copy2(str(path) + '.before-https', path)
    subprocess.run(['systemctl', 'restart', 'exchange-app'], check=False)
    subprocess.run(['systemctl', 'reload', 'nginx'], check=False)
    raise
print('HTTPS 설정 적용. readiness와 외부 접속을 별도로 확인한다.')
