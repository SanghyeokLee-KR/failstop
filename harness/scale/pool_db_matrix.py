#!/usr/bin/env python3
"""풀·정원과 DB 규격을 교차해 재는 실측 runner.

묻는 것은 하나다. 정본 6.10 이 "DB 를 올려도 정원 6 칸이 그대로라 천장이 움직이지
않습니다" 로 적었는데, 풀과 정원을 함께 올리면 DB 규격 상향이 값을 갖는가.

손잡이가 둘이다.
  - SCALE_POOL   Hikari maximumPoolSize. 정원은 여기서 유도된다(floor(pool x 0.40)).
  - SCALE_PG_CPUS chaos-pg 컨테이너 CPU 한도.

부하는 봇 플릿으로 고정한다(EXCHANGE_SIM_TARGET_OPS). 천장을 때리지 않는다.
같은 수요를 네 조건에 똑같이 넣고 그 아래에서 무엇이 달라지는지 본다.

판정 축은 DB CPU 다. DB 를 키웠을 때 같은 수요에서 CPU 포화가 풀리는가,
그리고 그것이 접수·지연으로 이어지는가.

사용:
  python harness/scale/pool_db_matrix.py --ops 40 --warmup 60 --window 120
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
COMPOSE = [
    'docker', 'compose',
    '-f', str(ROOT / 'harness/docker-compose.chaos.yml'),
    '-f', str(ROOT / 'harness/docker-compose.scale.yml'),
    '--env-file', str(ROOT / 'harness/.env.chaos'),
]
PROM = 'http://localhost:59090/api/v1/query'
LB = 'http://localhost:58080'

# 재는 것. 앞의 셋은 레코딩 룰이고 마지막은 원본 카운터다.
QUERIES = {
    'trades_per_sec': 'exchange:trades_per_second:rate1m',
    'p95_ms': 'exchange:order_latency:p95_1m',
    'p99_ms': 'exchange:order_latency:p99_1m',
    'rejected_per_sec': 'sum(exchange:orders_rejected:rate1m)',
    'admission_rejected_per_sec': 'sum(rate(exchange_admission_rejected_total[1m]))',
    'pool_active': 'sum(hikaricp_connections_active{pool="HikariPool-1"})',
    'pool_pending': 'sum(hikaricp_connections_pending{pool="HikariPool-1"})',
}


def sh(args, timeout=600):
    r = subprocess.run(args, capture_output=True, text=True, encoding='utf-8',
                       errors='replace', timeout=timeout)
    return r.returncode, (r.stdout or '') + (r.stderr or '')


def prom(expr):
    """Prometheus 즉시 질의. 결과가 없으면 None 을 돌려 0 과 구분한다."""
    url = PROM + '?query=' + urllib.parse.quote(expr)
    try:
        with urllib.request.urlopen(url, timeout=8) as f:
            d = json.load(f)
    except Exception as e:
        return None, str(e)
    rows = d.get('data', {}).get('result', [])
    if not rows:
        return None, 'empty'
    try:
        return float(rows[0]['value'][1]), None
    except (KeyError, IndexError, ValueError) as e:
        return None, str(e)


def stats(names):
    """docker stats 한 번. CPU 는 한도 대비 백분율이라 한도를 바꾸면 분모가 바뀐다."""
    code, out = sh(['docker', 'stats', '--no-stream', '--format',
                    '{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}'] + list(names))
    got = {}
    if code != 0:
        return got
    for line in out.strip().splitlines():
        parts = line.split('\t')
        if len(parts) < 3:
            continue
        got[parts[0]] = {'cpu_pct': float(parts[1].rstrip('%')), 'mem': parts[2]}
    return got


def wait_ready(timeout=180):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(LB + '/actuator/health/readiness', timeout=4) as f:
                if f.status == 200:
                    return True
        except Exception:
            pass
        time.sleep(3)
    return False


def apply_cell(pool, cpus, mem, ops, env):
    env = dict(env)
    env['SCALE_POOL'] = str(pool)
    env['SCALE_PG_CPUS'] = str(cpus)
    env['SCALE_PG_MEM'] = mem
    env['CHAOS_BOTS_OPS'] = str(ops)
    r = subprocess.run(
        COMPOSE + ['up', '-d', '--force-recreate',
                   'chaos-pg', 'chaos-api-1', 'chaos-api-2', 'chaos-bots'],
        capture_output=True, text=True, encoding='utf-8', errors='replace',
        env=env, timeout=900)
    if r.returncode != 0:
        print('  compose 출력: ' + (r.stderr or r.stdout or '')[-400:], flush=True)
    return r.returncode


def sample_window(seconds, interval=5):
    """창 동안 반복 표집. 평균이 아니라 표본 전량을 남긴다. 평균만 남기면 포화 구간이 묻힌다."""
    rows = []
    end = time.time() + seconds
    while time.time() < end:
        row = {'t': datetime.now(timezone.utc).isoformat()}
        s = stats(['chaos-pg', 'chaos-api-1', 'chaos-api-2'])
        for name, v in s.items():
            row[name + '_cpu'] = v['cpu_pct']
            row[name + '_mem'] = v['mem']
        for key, expr in QUERIES.items():
            val, err = prom(expr)
            row[key] = val
            if err and err != 'empty':
                row.setdefault('errors', []).append(key + ':' + err)
        rows.append(row)
        time.sleep(interval)
    return rows


def summarize(rows, keys):
    out = {}
    for k in keys:
        vals = [r[k] for r in rows if isinstance(r.get(k), (int, float))]
        if not vals:
            out[k] = None
            continue
        vals.sort()
        out[k] = {
            'n': len(vals),
            'avg': round(sum(vals) / len(vals), 3),
            'p50': round(vals[len(vals) // 2], 3),
            'max': round(vals[-1], 3),
        }
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--ops', type=int, default=40, help='봇 목표 OPS. 천장을 때리지 않는 값')
    ap.add_argument('--warmup', type=int, default=60)
    ap.add_argument('--window', type=int, default=120)
    ap.add_argument('--pg-mem', default='2g')
    ap.add_argument('--out', default=None)
    ap.add_argument('--cells', default=None,
                    help='이름:풀:코어 를 쉼표로. 예 E:48:1.0,F:48:4.0')
    a = ap.parse_args()

    import os
    env = dict(os.environ)

    if a.cells:
        cells = []
        for spec in a.cells.split(','):
            name, pool, cpus = spec.split(':')
            cells.append((name, int(pool), cpus))
    else:
        cells = [
            ('A', 16, '1.0'),
            ('B', 16, '4.0'),
            ('C', 48, '1.0'),
            ('D', 48, '4.0'),
        ]
    out_dir = Path(a.out) if a.out else ROOT / 'harness/evidence' / (
        'pool-db-matrix-' + datetime.now().strftime('%Y%m%d-%H%M%S'))
    out_dir.mkdir(parents=True, exist_ok=True)

    result = {
        'ops': a.ops, 'warmup_sec': a.warmup, 'window_sec': a.window,
        'pg_mem': a.pg_mem, 'cells': [],
    }

    for name, pool, cpus in cells:
        write_slots = int(pool * 0.40)
        print('[%s] 풀 %d (쓰기 정원 %d) / DB cpus %s / 봇 %d OPS'
              % (name, pool, write_slots, cpus, a.ops), flush=True)
        code = apply_cell(pool, cpus, a.pg_mem, a.ops, env)
        if code != 0:
            print('  기동 실패 종료코드 %d. 이 칸은 비운다' % code, flush=True)
            result['cells'].append({'cell': name, 'pool': pool, 'pg_cpus': cpus,
                                    'error': 'compose up 종료코드 %d' % code})
            continue
        if not wait_ready():
            print('  readiness 미도달. 이 칸은 비운다', flush=True)
            result['cells'].append({'cell': name, 'pool': pool, 'pg_cpus': cpus,
                                    'error': 'readiness 미도달'})
            continue
        print('  워밍업 %ds' % a.warmup, flush=True)
        time.sleep(a.warmup)
        print('  표집 %ds' % a.window, flush=True)
        rows = sample_window(a.window)
        (out_dir / ('cell-%s.jsonl' % name)).write_text(
            '\n'.join(json.dumps(r, ensure_ascii=False) for r in rows) + '\n',
            encoding='utf-8')
        keys = ['chaos-pg_cpu', 'chaos-api-1_cpu', 'chaos-api-2_cpu'] + list(QUERIES)
        summary = summarize(rows, keys)
        result['cells'].append({
            'cell': name, 'pool': pool, 'write_slots': write_slots,
            'pg_cpus': cpus, 'samples': len(rows), 'summary': summary,
        })
        print('  DB CPU 평균 %s / 접수 %s / 거절 %s'
              % (summary.get('chaos-pg_cpu'), summary.get('trades_per_sec'),
                 summary.get('admission_rejected_per_sec')), flush=True)

    (out_dir / 'matrix.json').write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print('산출: %s' % out_dir, flush=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
