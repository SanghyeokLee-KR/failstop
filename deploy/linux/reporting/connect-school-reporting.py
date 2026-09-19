"""검증 후 관측·HTTPS 경로를 연결한다. 기존 파일은 한 번 백업한다."""
import argparse
from pathlib import Path
import shutil
import subprocess


def run(*args):
    subprocess.run(args, check=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('host', choices=('app', 'bot'))
    args = parser.parse_args()
    if args.host == 'app':
        target = Path('/etc/nginx/nginx.conf')
        old = target.read_text()
        include = '        include /etc/nginx/exchange-reports.conf;'
        if include not in old:
            marker = '        include /etc/nginx/exchange-grafana.conf;'
            if old.count(marker) != 1:
                raise RuntimeError('기존 HTTPS 설정 위치를 확인해야 합니다.')
            backup = target.with_name('nginx.conf.before-reports-20260920')
            if not backup.exists():
                shutil.copy2(target, backup)
            shutil.copyfile('/tmp/nginx-reports.conf', '/etc/nginx/exchange-reports.conf')
            target.write_text(old.replace(marker, marker + '\n' + include))
            try:
                run('nginx', '-t')
            except Exception:
                target.write_text(old)
                raise
        run('systemctl', 'reload', 'nginx')
    else:
        target = Path('/etc/prometheus/prometheus.yml')
        old = target.read_text()
        backup = target.with_name('prometheus.yml.before-reporting-20260920')
        if not backup.exists():
            shutil.copy2(target, backup)
        shutil.copyfile('/tmp/school-alerts.yml', '/etc/prometheus/school-alerts.yml')
        Path('/etc/prometheus/school-alerts.yml').chmod(0o644)
        if 'school-alerts.yml' not in old:
            if 'rule_files:' in old or 'alerting:' in old:
                raise RuntimeError('기존 경보 설정과 수동 병합이 필요합니다.')
            target.write_text(old + '\nrule_files:\n  - /etc/prometheus/school-alerts.yml\n'
                              'alerting:\n  alertmanagers:\n    - static_configs:\n'
                              '        - targets: ["127.0.0.1:9093"]\n')
        try:
            run('/usr/local/bin/promtool', 'check', 'config', str(target))
        except Exception:
            target.write_text(old)
            raise
        run('systemctl', 'restart', 'prometheus')
    print('PASS: ' + args.host + ' 보고 경로 설정 검사·반영')


if __name__ == '__main__':
    main()
