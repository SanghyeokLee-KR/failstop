"""학교 bot-1에 보고·경보를 설치한다. 설치 직후 외부 전송은 비활성이다."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request

HERE = Path(__file__).resolve().parent
PYTHON = '/opt/exchange-report-runtime/bin/python'


def run(*args):
    subprocess.run(args, check=True)


def main():
    if os.geteuid() != 0:
        raise SystemExit('sudo로 실행하세요.')
    stage = HERE / 'reporting'
    for unit in (stage / 'systemd').glob('*.service'):
        unit.write_text(unit.read_text().replace('/usr/bin/python3', PYTHON))
    fonts = Path('/usr/share/fonts/nanum')
    fonts.mkdir(parents=True, exist_ok=True)
    for name in ('NanumGothic.ttf', 'NanumGothicBold.ttf', 'OFL.txt'):
        shutil.copyfile(HERE / name, fonts / name)
        (fonts / name).chmod(0o644)
    run(PYTHON, str(stage / 'install.py'), '--apply', '--environment', 'aws',
        '--bind', '172.31.44.141', '--prometheus-url', 'http://127.0.0.1:9090',
        '--alertmanager-url', 'http://127.0.0.1:9093', '--analyzer', 'gemini',
        '--secret-file', '/etc/exchange-report/teams_webhook_url',
        '--ai-secret-file', '/etc/exchange-report/gemini_api_key',
        '--artifact-base-url', 'https://www.sang.it.kr/operations',
        '--pdf-font', str(fonts / 'NanumGothic.ttf'),
        '--pdf-font-bold', str(fonts / 'NanumGothicBold.ttf'))
    version = '0.31.1'
    filename = f'alertmanager-{version}.linux-amd64.tar.gz'
    source = f'https://github.com/prometheus/alertmanager/releases/download/v{version}/'
    archive = HERE / filename
    urllib.request.urlretrieve(source + filename, archive)
    checksums = urllib.request.urlopen(source + 'sha256sums.txt', timeout=30).read().decode()
    expected = next(line.split()[0] for line in checksums.splitlines() if line.split()[-1].lstrip('*') == filename)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
        raise RuntimeError('Alertmanager SHA-256 불일치')
    # 실행 파일 두 개만 추출하며 archive 안의 경로는 사용하지 않는다.
    with tarfile.open(archive) as package:
        for name in ('alertmanager', 'amtool'):
            member = package.getmember(f'alertmanager-{version}.linux-amd64/{name}')
            with package.extractfile(member) as src, open('/usr/local/bin/' + name, 'wb') as dest:
                shutil.copyfileobj(src, dest)
            os.chmod('/usr/local/bin/' + name, 0o755)
    shutil.copyfile(HERE / 'alertmanager.school.yml', '/etc/failstop/alertmanager.yml')
    os.chmod('/etc/failstop/alertmanager.yml', 0o644)
    shutil.copyfile(HERE / 'alertmanager.service', '/etc/systemd/system/alertmanager.service')
    run('/usr/local/bin/amtool', 'check-config', '/etc/failstop/alertmanager.yml')
    run('systemctl', 'daemon-reload')
    run('systemctl', 'enable', '--now', 'alertmanager.service')
    print('설치 완료. 전송은 비활성. Prometheus·HTTPS 검증 후 기존 timer를 교체하세요.')


if __name__ == '__main__':
    main()
