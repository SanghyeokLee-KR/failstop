#!/usr/bin/env python3
"""동기 standby 에 읽기를 얹었을 때 primary 커밋이 느려지는지 잰다.

앱에는 읽기 전용 데이터소스가 없다. `dataSource` 와 `leaseDataSource` 둘뿐이라
앱을 통해 standby 로 읽기를 보내려면 데이터소스 추가와 트랜잭션 라우팅과 복제 지연
계약이 함께 붙는다. 그것은 업무 로직 변경이라 이 실험의 범위 밖이다.

대신 답해야 할 질문은 DB 계층에서 그대로 잴 수 있다. standby 에 읽기를 얹으면
동기 복제 확인이 늦어져 primary 커밋이 느려지는가. 그 되먹임이 없으면 읽기 분산이
값을 갖고, 있으면 그 대가를 알고 결정해야 한다.

구간 셋을 같은 부하에서 잰다.
  1) base      standby 없음. 비동기와 같은 조건
  2) sync      동기 standby 1대. 읽기 부하 없음
  3) sync+read 같은 standby 에 읽기 부하를 얹는다

재는 것은 primary 의 커밋 지연(앱 p95·p99)과 복제 지연, 그리고 양쪽 CPU 다.

사용:
  python harness/scale/standby_read.py --ops 60 --window 90
"""
import argparse
import json
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROM = 'http://localhost:59090/api/v1/query'
PRIMARY = 'chaos-pg'
STANDBY = 'chaos-pg-standby'
PGUSER = 'exchange'
PGDB = 'exchange'


def sh(args, timeout=300, **kw):
    return subprocess.run(args, capture_output=True, text=True, encoding='utf-8',
                          errors='replace', timeout=timeout, **kw)


def prom(expr):
    url = PROM + '?query=' + urllib.parse.quote(expr)
    try:
        with urllib.request.urlopen(url, timeout=8) as f:
            rows = json.load(f)['data']['result']
    except Exception:
        return None
    if not rows:
        return None
    try:
        return float(rows[0]['value'][1])
    except (KeyError, IndexError, ValueError):
        return None


def stats(names):
    r = sh(['docker', 'stats', '--no-stream', '--format', '{{.Name}}\t{{.CPUPerc}}'] + names)
    got = {}
    for line in (r.stdout or '').strip().splitlines():
        p = line.split('\t')
        if len(p) == 2:
            got[p[0]] = float(p[1].rstrip('%'))
    return got


def psql(container, sql, timeout=60):
    return sh(['docker', 'exec', container, 'psql', '-U', PGUSER, '-d', PGDB,
               '-t', '-A', '-c', sql], timeout=timeout)


def repl_lag():
    """동기 standby 의 write/flush/replay 지연. 행이 없으면 None."""
    r = psql(PRIMARY, "SELECT coalesce(round(extract(epoch FROM replay_lag)*1000)::text,'0') "
                      "FROM pg_stat_replication LIMIT 1;")
    out = (r.stdout or '').strip()
    if r.returncode != 0 or not out:
        return None
    try:
        return float(out)
    except ValueError:
        return None


def sample(seconds, interval=5, containers=None):
    containers = containers or [PRIMARY]
    rows = []
    end = time.time() + seconds
    while time.time() < end:
        row = {'t': datetime.now(timezone.utc).isoformat()}
        for name, cpu in stats(containers).items():
            row[name + '_cpu'] = cpu
        row['p95_ms'] = (lambda v: v * 1000 if v is not None else None)(
            prom('exchange:order_latency:p95_1m'))
        row['p99_ms'] = (lambda v: v * 1000 if v is not None else None)(
            prom('exchange:order_latency:p99_1m'))
        row['trades_per_sec'] = prom('exchange:trades_per_second:rate1m')
        row['repl_lag_ms'] = repl_lag()
        rows.append(row)
        time.sleep(interval)
    return rows


def summarize(rows, keys):
    out = {}
    for k in keys:
        v = [r[k] for r in rows if isinstance(r.get(k), (int, float))]
        if not v:
            out[k] = None
            continue
        v.sort()
        out[k] = {'n': len(v), 'avg': round(sum(v) / len(v), 2),
                  'p50': round(v[len(v) // 2], 2), 'max': round(v[-1], 2)}
    return out


def read_load_start(seconds):
    """standby 에 읽기 부하를 건다. pgbench 대신 반복 조회로 건다.

    pgbench -S 는 자기 스키마(pgbench_*)를 요구하고 그 초기화가 primary 에 쓰기를
    만든다. 읽기만 얹는 것이 목적이므로 실재 테이블을 읽는다."""
    sql = ("SELECT count(*) FROM orders WHERE created_at > now() - interval '1 hour';"
           "SELECT count(*) FROM trades WHERE created_at > now() - interval '1 hour';")
    script = ("end=$(( $(date +%%s) + %d )); "
              "while [ $(date +%%s) -lt $end ]; do "
              "psql -U %s -d %s -q -c \"%s\" >/dev/null 2>&1; done" % (seconds, PGUSER, PGDB, sql))
    procs = []
    for _ in range(8):  # 동시 8 세션. 한 세션으로는 standby 를 재우지 못한다
        procs.append(subprocess.Popen(
            ['docker', 'exec', STANDBY, 'sh', '-c', script],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
    return procs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--ops', type=int, default=60)
    ap.add_argument('--warmup', type=int, default=45)
    ap.add_argument('--window', type=int, default=90)
    ap.add_argument('--out', default=None)
    a = ap.parse_args()

    out_dir = Path(a.out) if a.out else ROOT / 'harness/evidence' / (
        'standby-read-' + datetime.now().strftime('%Y%m%d-%H%M%S'))
    out_dir.mkdir(parents=True, exist_ok=True)
    result = {'ops': a.ops, 'window_sec': a.window, 'phases': []}

    def record(name, rows, note=''):
        keys = [PRIMARY + '_cpu', STANDBY + '_cpu', 'p95_ms', 'p99_ms',
                'trades_per_sec', 'repl_lag_ms']
        (out_dir / ('phase-%s.jsonl' % name)).write_text(
            '\n'.join(json.dumps(r, ensure_ascii=False) for r in rows) + '\n', encoding='utf-8')
        s = summarize(rows, keys)
        result['phases'].append({'phase': name, 'note': note, 'samples': len(rows),
                                 'summary': s})
        print('  p95 %s / p99 %s / 복제지연 %s / primary CPU %s / standby CPU %s'
              % (s.get('p95_ms'), s.get('p99_ms'), s.get('repl_lag_ms'),
                 s.get(PRIMARY + '_cpu'), s.get(STANDBY + '_cpu')), flush=True)

    print('[1] standby 없음(기준). 워밍업 %ds' % a.warmup, flush=True)
    time.sleep(a.warmup)
    record('base', sample(a.window, containers=[PRIMARY]), 'standby 없음')

    print('[2] 동기 standby 기동. 읽기 부하 없음', flush=True)
    code = subprocess.run(['sh', str(ROOT / 'harness/scale/standby_up.sh')],
                          capture_output=True, text=True, encoding='utf-8',
                          errors='replace', timeout=900)
    if code.returncode != 0:
        print('  standby 기동 실패: ' + (code.stderr or code.stdout or '')[-500:], flush=True)
        result['phases'].append({'phase': 'sync', 'error': 'standby 기동 실패'})
        (out_dir / 'standby-read.json').write_text(
            json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        return 1
    time.sleep(a.warmup)
    record('sync', sample(a.window, containers=[PRIMARY, STANDBY]), '동기 standby, 읽기 없음')

    print('[3] standby 에 읽기 부하 8 세션', flush=True)
    procs = read_load_start(a.window + a.warmup + 30)
    time.sleep(a.warmup)
    rows = sample(a.window, containers=[PRIMARY, STANDBY])
    for p in procs:
        p.terminate()
    record('sync_read', rows, '동기 standby + standby 읽기 8 세션')

    (out_dir / 'standby-read.json').write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print('산출: %s' % out_dir, flush=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
