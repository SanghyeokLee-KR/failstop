"""검증한 새 Primary 공개 인증서와 접속 주소를 준비·적용한다."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess

p = argparse.ArgumentParser()
p.add_argument('role', choices=('app', 'bot'))
p.add_argument('action', choices=('prepare', 'apply'))
p.add_argument('--sha256', required=True)
a = p.parse_args()
expected = '172.31.2.86' if a.role == 'app' else '172.31.44.141'
if os.geteuid() != 0 or expected not in subprocess.check_output(['hostname', '-I'], text=True).split():
    raise SystemExit('대상 호스트 확인 실패')
root = Path('/etc/exchange' if a.role == 'app' else '/etc/exchange-bot')
env = root / ('app.env' if a.role == 'app' else 'bot.env')
cert = root / 'primary.crt'
staged = root / 'primary-next-20260920.crt'
if a.action == 'prepare':
    subprocess.run(['aws', 's3', 'cp', 's3://2026-ai-kopo-04-exchange-artifacts-619256964117/transfer/db-recovery-20260920/new-primary.crt', str(staged), '--region', 'ap-northeast-2', '--only-show-errors'], check=True)
if hashlib.sha256(staged.read_bytes()).hexdigest() != a.sha256:
    raise SystemExit('공개 인증서 지문 불일치')
content = env.read_text()
lines = content.splitlines()
urls = [line for line in lines if line.startswith('SPRING_DATASOURCE_URL=')]
if len(urls) != 1 or '172.31.1.229:5432' not in urls[0] or 'sslmode=verify-full' not in urls[0]:
    raise SystemExit('기존 TLS DB 연결 설정 확인 실패')
if a.action == 'apply':
    unit = 'exchange-app' if a.role == 'app' else 'exchange-bot'
    state = subprocess.check_output(['systemctl', 'show', unit, '-p', 'ActiveState', '--value'], text=True).strip()
    if state != 'inactive':
        raise SystemExit('서비스를 중지한 뒤 접속 대상을 변경하세요.')
    for path in (env, cert):
        backup = path.with_name(path.name + '.before-recovery-20260920')
        if backup.exists():
            raise SystemExit('기존 전환 백업이 있습니다. 자동 덮어쓰기를 중단합니다.')
        shutil.copy2(path, backup)
    # 비밀번호 파일과 TLS 검증 설정은 그대로 둔다.
    env.write_text(content.replace(urls[0], urls[0].replace('172.31.1.229:5432', '172.31.33.195:5432')))
    shutil.copyfile(staged, cert)
    cert.chmod(0o644)
print('PASS', a.role, a.action, 'TLS verify-full; target 172.31.33.195')
