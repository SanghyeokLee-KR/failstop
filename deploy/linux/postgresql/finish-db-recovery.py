"""복구 시연 뒤 관측 역할과 Java 서비스의 정상 종료 코드를 맞춘다."""
import argparse
from pathlib import Path
import shutil
import subprocess

p = argparse.ArgumentParser()
p.add_argument('host', choices=('app', 'bot'))
a = p.parse_args()
unit = 'exchange-app' if a.host == 'app' else 'exchange-bot'
dropin = Path('/etc/systemd/system') / (unit + '.service.d')
dropin.mkdir(exist_ok=True)
(dropin / '40-java-shutdown.conf').write_text('[Service]\nSuccessExitStatus=143\n')
subprocess.run(['systemctl', 'daemon-reload'], check=True)
if a.host == 'bot':
    config = Path('/etc/prometheus/prometheus.yml')
    backup = config.with_name('prometheus.yml.before-db-recovery-20260920')
    if backup.exists():
        raise SystemExit('관측 전환 기록이 있습니다. 중복 전환하지 않습니다.')
    old = config.read_text()
    if old.count('172.31.1.229') != 2 or old.count('172.31.33.195') != 2:
        raise SystemExit('DB 관측 대상 수 확인 필요')
    shutil.copy2(config, backup)
    config.write_text(old.replace('172.31.1.229', 'TEMP_DB_HOST').replace('172.31.33.195', '172.31.1.229').replace('TEMP_DB_HOST', '172.31.33.195'))
    try:
        subprocess.run(['/usr/local/bin/promtool', 'check', 'config', str(config)], check=True)
    except Exception:
        config.write_text(old)
        raise
    subprocess.run(['systemctl', 'restart', 'prometheus'], check=True)
print('PASS: 설정 반영. 앱·봇 프로세스는 재시작하지 않음.')
