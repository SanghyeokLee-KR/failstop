"""디스크 부족이면 봇만 멈춘다. DB·거래 기록은 변경하지 않는다."""
import argparse
import csv
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import subprocess
from urllib.parse import urlencode
from urllib.request import urlopen

NODES = {'app-1', 'bot-1', 'db-primary', 'db-standby'}
METRICS = ('node_filesystem_avail_bytes', 'node_filesystem_size_bytes',
           'node_filesystem_files_free', 'node_filesystem_files')
SELECTOR = ('{__name__=~"' + '|'.join(METRICS) + '",mountpoint="/",'
            'job=~"linux|linux-host",node=~"app-1|bot-1|db-primary|db-standby"}')
AGE_SELECTOR = ('node_filesystem_avail_bytes{mountpoint="/",job=~"linux|linux-host",'
                'node=~"app-1|bot-1|db-primary|db-standby"}')
STATE = Path('/var/lib/exchange-bot-guard')


def query(expression):
    url = 'http://127.0.0.1:9090/api/v1/query?' + urlencode({'query': expression})
    with urlopen(url, timeout=5) as response:
        body = json.load(response)
    if body.get('status') != 'success' or body['data']['resultType'] != 'vector':
        raise ValueError('Prometheus query failed')
    return body['data']['result']


def inspect(rows, ages):
    values = {}
    for row in rows:
        key = (row['metric']['node'], row['metric']['__name__'])
        value = float(row['value'][1])
        if key in values or not math.isfinite(value) or value < 0:
            raise ValueError('duplicate or invalid filesystem sample')
        values[key] = value
    if len(ages) != len(NODES) or {r['metric']['node'] for r in ages} != NODES:
        raise ValueError('missing sample timestamps')
    if any(not math.isfinite(float(r['value'][1])) or
           not 0 <= float(r['value'][1]) <= 90 for r in ages):
        raise ValueError('stale filesystem sample')
    expected = {(node, metric) for node in NODES for metric in METRICS}
    if values.keys() != expected:
        raise ValueError('missing filesystem samples')
    reasons, snapshot = [], {}
    for node in sorted(NODES):
        avail, size, free_files, files = (values[(node, m)] for m in METRICS)
        if size <= 0 or files <= 0 or avail > size or free_files > files:
            raise ValueError('invalid filesystem capacity')
        snapshot[node] = dict(available_bytes=int(avail), total_bytes=int(size),
                              free_inodes=int(free_files), total_inodes=int(files))
        if avail / size <= .20 or avail <= 2 * 1024 ** 3 or free_files / files <= .10:
            reasons.append(node + ': disk or inode reserve reached')
    return reasons, snapshot


def decision(rows, ages, previous_failures=0):
    try:
        reasons, snapshot = inspect(rows, ages)
        return {'stop': bool(reasons), 'failures': 0, 'reasons': reasons, 'nodes': snapshot}
    except (ValueError, KeyError, TypeError, OverflowError):
        failures = previous_failures + 1
        return {'stop': failures >= 3, 'failures': failures,
                'reasons': ['filesystem telemetry unavailable'], 'nodes': {}}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--enforce', action='store_true')
    args = parser.parse_args()
    STATE.mkdir(mode=0o750, exist_ok=True)
    path = STATE / 'state.json'
    try:
        previous = json.loads(path.read_text()) if path.exists() else {}
    except (ValueError, OSError):
        previous = {'failures': 2}
    try:
        # 역할 라벨 변경 뒤 남는 과거 시계열은 제외한다. 현재 노드 누락은 아래에서 실패 처리한다.
        rows = query(SELECTOR + ' and (time() - timestamp(' + AGE_SELECTOR + ') <= 90)')
        ages = query('(time() - timestamp(' + AGE_SELECTOR + ')) <= 90')
        result = decision(rows, ages, previous.get('failures', 0))
    except (OSError, ValueError, KeyError, TypeError):
        result = decision([], [], previous.get('failures', 0))
    # 기록 디스크의 문제가 중지 동작을 막지 않도록 먼저 봇을 멈춘다.
    if result['stop'] and args.enforce:
        subprocess.run(['systemctl', 'stop', 'exchange-bot.service'], check=True, timeout=75)
        print('Market bot stopped; inspect capacity and resume manually.')
    now = datetime.now(timezone.utc)
    result['checked_at'] = now.isoformat()
    result['sample_hour'] = previous.get('sample_hour')
    hour = now.strftime('%Y-%m-%dT%H')
    if result['nodes'] and hour != previous.get('sample_hour'):
        with (STATE / 'hourly-capacity.csv').open('a', newline='') as file:
            writer = csv.writer(file)
            for node, sample in result['nodes'].items():
                writer.writerow([now.isoformat(), node, sample['available_bytes'],
                                 sample['total_bytes'], sample['free_inodes'], sample['total_inodes']])
        result['sample_hour'] = hour
    temporary = STATE / 'state.json.tmp'
    temporary.write_text(json.dumps(result, indent=2) + '\n')
    temporary.replace(path)
    print(json.dumps(result))
    return 1 if result['reasons'] else 0


if __name__ == '__main__':
    raise SystemExit(main())
