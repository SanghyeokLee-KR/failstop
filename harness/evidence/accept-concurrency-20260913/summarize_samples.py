"""표본 요약. k6 컨테이너가 떠 있던 표본만 부하 구간으로 센다.

돌리는 법:
  python summarize_samples.py B0-240 B0-600 B0-ceiling
"""
import csv
import json
import statistics
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent


def num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def summarize(label):
    res = {'label': label}
    pg = list(csv.DictReader(open(HERE / ('pg_lock_samples_%s.csv' % label), encoding='utf-8')))
    load = [r for r in pg if r['k6'] == '1']
    ok = [r for r in load if r['ok'] == '1']
    res['pg 표본(부하 구간)'] = len(load)
    res['pg 표본 성공'] = len(ok)
    for col in ('prologue_active', 'prologue_lock_wait', 'advisory_wait', 'counter_row_wait', 'db_active'):
        vals = [int(r[col]) for r in ok]
        if vals:
            res[col] = {'평균': round(statistics.mean(vals), 3), '최대': max(vals),
                        '0 초과 표본': sum(1 for v in vals if v > 0)}
    pr = list(csv.DictReader(open(HERE / ('lock_samples_%s.csv' % label), encoding='utf-8')))
    pl = [r for r in pr if r['k6'] == '1']
    res['prom 표본(부하 구간)'] = len(pl)
    cols = [c for c in pr[0].keys() if c not in ('ts', 'k6')] if pr else []
    for c in cols:
        vals = [num(r[c]) for r in pl if num(r[c]) is not None]
        if vals:
            res['prom ' + c] = {'중앙값': round(statistics.median(vals), 4),
                                '최소': round(min(vals), 4), '최대': round(max(vals), 4), 'n': len(vals)}
    host = HERE / ('host_samples_%s.csv' % label)
    if host.exists():
        agg = {}
        for r in csv.DictReader(open(host, encoding='utf-8-sig')):
            v = num(r.get('cpu_pct_of_16'))
            if v is None:
                continue
            agg.setdefault(r['process'], []).append(v)
        top = sorted(((p, statistics.mean(v), len(v)) for p, v in agg.items()),
                     key=lambda x: -x[1])[:8]
        res['호스트 상위 프로세스(16코어 대비 평균%, 등장 표본)'] = [[p, round(m, 1), n] for p, m, n in top]
    return res


if __name__ == '__main__':
    out = [summarize(lb) for lb in sys.argv[1:]]
    print(json.dumps(out, ensure_ascii=False, indent=1))
