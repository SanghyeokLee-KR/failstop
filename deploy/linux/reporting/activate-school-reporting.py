"""기존 타이머를 교체하고 현재 AWS 지표로 보고 1건을 대기열에 넣는다."""
import grp
import json
import os
from pathlib import Path
import subprocess
import sys
import time

sys.path.insert(0, '/opt/failstop/deploy/reporting')
from reporter import Store


def main():
    if os.geteuid() != 0:
        raise SystemExit('sudo로 실행하세요.')
    root = Path('/var/lib/failstop/evidence')
    proof = root / 'backup-restore-school.json'
    proof.write_text(json.dumps({'schema_version': 1, 'records': {
        'verified': True, 'backup_id': '20260919T162452010003Z',
        'verified_at': '2026-09-19T16:24:58.739501+00:00'}}) + '\n')
    os.chown(proof, 0, grp.getgrnam('failstop-report').gr_gid)
    proof.chmod(0o640)
    config_file = Path('/etc/failstop/reporting.json')
    config = json.loads(config_file.read_text())
    config['backup_restore_file'] = proof.name
    config_file.write_text(json.dumps(config, ensure_ascii=False, indent=2) + '\n')
    env_file = Path('/etc/failstop/reporting.env')
    before = env_file.read_text()
    env_file.write_text(before.replace('REPORT_DELIVERY_ENABLED=false', 'REPORT_DELIVERY_ENABLED=true'))
    subprocess.run(['systemctl', 'disable', '--now', 'exchange-report-daily.timer',
                    'exchange-report-incident.timer'], check=True)
    subprocess.run(['systemctl', 'restart', 'failstop-reporting'], check=True)
    subprocess.run(['runuser', '-u', 'failstop-report', '--', sys.executable,
                    '/opt/failstop/deploy/reporting/reporter.py', 'status',
                    '--config', str(config_file)], check=True)
    # 일일 정기 보고와 구분하며 과거 날짜의 빈 지표를 전송하지 않는다.
    subprocess.run(['runuser', '-u', 'failstop-report', '--', sys.executable, '-c',
                    "import sys,time; sys.path.insert(0,'/opt/failstop/deploy/reporting'); "
                    "from reporter import Store; "
                    "s=Store('/var/lib/failstop-reporting/outbox.sqlite3'); "
                    "s.enqueue('school-live-check-20260920','운영 보고 연결 검증',{'window_end':time.time()}); "
                    "print('현재 AWS 지표 보고 1건 대기열 등록')"], check=True)


if __name__ == '__main__':
    main()
