"""기존 로컬 실습의 조회 제한과 DB 변화를 측정한다. 데이터와 설정은 수정하지 않는다."""
import json
import os
from pathlib import Path
import subprocess
import time
from datetime import datetime, timezone
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / 'artifacts/operations-20260918/load-review'
DOCKER = os.environ.get('DOCKER_BIN', 'docker')
DB = 'failstop-ops-lab-standby-1'
APP = 'failstop-ops-lab-app-1'
SQL = """
SELECT json_build_object(
 'time', clock_timestamp(), 'recovery', pg_is_in_recovery(),
 'db', (SELECT row_to_json(d) FROM (SELECT xact_commit, xact_rollback,
   tup_returned, tup_fetched, tup_inserted, tup_updated, tup_deleted,
   blks_read, blks_hit, temp_bytes, deadlocks, numbackends
   FROM pg_stat_database WHERE datname='exchange') d),
 'wal_bytes', (SELECT wal_bytes FROM pg_stat_wal),
 'sessions', (SELECT json_agg(s) FROM (SELECT state, wait_event_type,
   count(*) AS count FROM pg_stat_activity WHERE datname='exchange'
   AND backend_type='client backend' AND pid<>pg_backend_pid()
   GROUP BY state, wait_event_type) s),
 'replication', (SELECT json_agg(r) FROM (SELECT state, sync_state,
   pg_wal_lsn_diff(sent_lsn,replay_lsn) AS replay_gap_bytes
   FROM pg_stat_replication) r));
"""


def command(*args):
    return subprocess.check_output([DOCKER, *args], text=True, encoding='utf-8', timeout=20).strip()


def snapshot():
    return json.loads(command('exec', DB, 'psql', '-U', 'ops_admin', '-d', 'exchange', '-At', '-c', SQL))


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    if (OUTPUT / 'k6-summary.json').exists():
        raise SystemExit('기존 실행 증거가 있습니다. 덮어쓰지 않습니다.')
    initial = snapshot()
    if initial['recovery']:
        raise SystemExit('현재 승격된 primary를 확인하지 못했습니다.')
    context = {
        'started_at': datetime.now(timezone.utc).isoformat(),
        'scope': 'single-source read-only local API; no rate-limit changes',
        'rates_rps': [10, 18, 30, 60, 120], 'stage_seconds': 60,
        'steady_seconds': 40, 'settling_seconds': 20,
        'data': json.loads(command('exec', DB, 'psql', '-U', 'ops_admin', '-d', 'exchange', '-At', '-c',
            "SELECT json_build_object('orders',(SELECT count(*) FROM orders),'trades',(SELECT count(*) FROM trades));")),
        'docker': json.loads(command('info', '--format', '{"cpus":{{.NCPU}},"memory":{{.MemTotal}}}')),
        'app': json.loads(command('inspect', APP, '--format',
            '{"memory_limit":{{.HostConfig.Memory}},"cpu_quota":{{.HostConfig.NanoCpus}},"restarts":{{.RestartCount}}}')),
        'samples': [initial], 'resources': [],
    }
    with (OUTPUT / 'k6.log').open('w', encoding='utf-8') as log:
        process = subprocess.Popen([DOCKER, 'run', '--rm', '--name', 'failstop-read-assessment',
            '--network', 'failstop-ops-lab_default', '--cpus', '2', '--memory', '512m',
            '-v', f'{ROOT / "deploy/lab/read-capacity.js"}:/test.js:ro',
            '-v', f'{OUTPUT}:/evidence', 'grafana/k6:1.4.0', 'run', '--quiet', '/test.js'],
            stdout=log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 340
        try:
            while process.poll() is None:
                if time.monotonic() >= deadline:
                    raise TimeoutError('5분 시험의 종료 한도를 초과했습니다.')
                time.sleep(10)
                context['samples'].append(snapshot())
                context['resources'].append({
                    'time': datetime.now(timezone.utc).isoformat(),
                    'containers': [json.loads(line) for line in command('stats', '--no-stream',
                        '--format', '{{json .}}', APP, DB, 'failstop-ops-lab-rejoined-1').splitlines()],
                })
                elapsed = (datetime.now(timezone.utc) - datetime.fromisoformat(context['started_at'])).total_seconds()
                print(f'조회 부하 측정 {elapsed:.0f}s / 300s', flush=True)
        finally:
            if process.poll() is None:
                subprocess.run([DOCKER, 'stop', '-t', '3', 'failstop-read-assessment'], timeout=15, check=False)
            context['exit_code'] = process.wait(timeout=15)
            context['finished_at'] = datetime.now(timezone.utc).isoformat()
            context['samples'].append(snapshot())
            context['app_after'] = json.loads(command('inspect', APP, '--format',
                '{"restarts":{{.RestartCount}},"health":"{{.State.Health.Status}}"}'))
            (OUTPUT / 'context.json').write_text(json.dumps(context, ensure_ascii=False, indent=2), encoding='utf-8')
    with urlopen('http://127.0.0.1:58180/actuator/health/readiness', timeout=5) as response:
        health = json.load(response)
    (OUTPUT / 'health-after.json').write_text(json.dumps(health, indent=2), encoding='utf-8')
    print(json.dumps({'exit_code': context['exit_code'], 'health': health}, ensure_ascii=False), flush=True)
    if context['exit_code']:
        raise SystemExit(context['exit_code'])


if __name__ == '__main__':
    main()
