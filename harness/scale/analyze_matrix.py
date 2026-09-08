#!/usr/bin/env python3
"""pool_db_matrix 산출을 표로 정리한다.

CPU 단위에 주의한다. docker stats 의 CPUPerc 는 코어 대비 백분율이라 100 이 1 코어다.
한도를 바꾸면 분모가 아니라 천장이 바뀐다. 곧 A(1 코어 한도)에서 100 근처는 포화이고
B(4 코어 한도)에서 100 근처는 여유다. 같은 100 이 다른 뜻이므로 한도를 함께 읽는다.

사용:
  python harness/scale/analyze_matrix.py harness/evidence/pool-db-matrix-*/matrix.json
"""
import glob
import json
import sys
from pathlib import Path


def cell_rows(path):
    d = json.loads(Path(path).read_text(encoding='utf-8'))
    rows = []
    for c in d['cells']:
        s = c.get('summary') or {}

        def g(k, f='avg'):
            v = s.get(k)
            return v[f] if isinstance(v, dict) else None

        rows.append({
            'cell': c['cell'],
            'pool': c.get('pool'),
            'slots': c.get('write_slots'),
            'cpus': c.get('pg_cpus'),
            'error': c.get('error'),
            'pg_cpu': g('chaos-pg_cpu'),
            'pg_cpu_max': g('chaos-pg_cpu', 'max'),
            'api1_cpu': g('chaos-api-1_cpu'),
            'trades': g('trades_per_sec'),
            'rejected': g('admission_rejected_per_sec'),
            # 레코딩 룰이 초로 낸다. 표에는 ms 로 적는다.
            'p95': (g('p95_ms') * 1000) if g('p95_ms') is not None else None,
            'p99': (g('p99_ms') * 1000) if g('p99_ms') is not None else None,
            'pool_active': g('pool_active'),
            'pool_pending': g('pool_pending'),
        })
    return d, rows


def fmt(v, nd=1):
    if v is None:
        return '-'
    return ('%.' + str(nd) + 'f') % v


def main():
    args = sys.argv[1:]
    if not args:
        found = sorted(glob.glob('harness/evidence/pool-db-matrix-*/matrix.json'))
        if not found:
            print('matrix.json 을 찾지 못했다')
            return 1
        args = [found[-1]]
    d, rows = cell_rows(args[0])

    print('부하 봇 %d OPS 고정 · 워밍업 %ds · 표집 %ds · DB 메모리 %s'
          % (d['ops'], d['warmup_sec'], d['window_sec'], d['pg_mem']))
    print()
    print('| 회차 | 풀 | 쓰기 정원 | DB 코어 한도 | DB CPU 평균 | DB CPU 최고 | 앱 CPU | 체결/s | 거절/s | p95 ms | p99 ms | 풀 대기 |')
    print('|---|---|---|---|---|---|---|---|---|---|---|---|')
    for r in rows:
        if r['error']:
            print('| %s | %s | %s | %s | %s |' % (r['cell'], r['pool'], r['slots'],
                                                  r['cpus'], r['error']))
            continue
        print('| %s | %d | %d | %s 코어 | %s | %s | %s | %s | %s | %s | %s | %s |' % (
            r['cell'], r['pool'], r['slots'], r['cpus'],
            fmt(r['pg_cpu']), fmt(r['pg_cpu_max']), fmt(r['api1_cpu']),
            fmt(r['trades'], 2), fmt(r['rejected'], 2), fmt(r['p95']), fmt(r['p99']),
            fmt(r['pool_pending'], 2)))

    print()
    ok = {r['cell']: r for r in rows if not r['error']}
    if 'A' in ok and 'B' in ok and ok['A']['pg_cpu'] is not None and ok['B']['pg_cpu'] is not None:
        d1 = ok['B']['pg_cpu'] - ok['A']['pg_cpu']
        print('풀 고정(16)에서 DB 코어 1 -> 4: DB CPU %s -> %s (%+.1f)'
              % (fmt(ok['A']['pg_cpu']), fmt(ok['B']['pg_cpu']), d1))
    if 'C' in ok and 'D' in ok and ok['C']['pg_cpu'] is not None and ok['D']['pg_cpu'] is not None:
        d2 = ok['D']['pg_cpu'] - ok['C']['pg_cpu']
        print('풀 상향(48)에서 DB 코어 1 -> 4: DB CPU %s -> %s (%+.1f)'
              % (fmt(ok['C']['pg_cpu']), fmt(ok['D']['pg_cpu']), d2))
    if 'A' in ok and 'C' in ok and ok['A']['pg_cpu'] is not None and ok['C']['pg_cpu'] is not None:
        d3 = ok['C']['pg_cpu'] - ok['A']['pg_cpu']
        print('DB 코어 고정(1)에서 풀 16 -> 48: DB CPU %s -> %s (%+.1f)'
              % (fmt(ok['A']['pg_cpu']), fmt(ok['C']['pg_cpu']), d3))
    return 0


if __name__ == '__main__':
    sys.exit(main())
