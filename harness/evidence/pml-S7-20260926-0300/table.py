"""이 폴더의 판정 표 값 뽑기(기록 도구, 판정하지 않는다). 실험 폴더의 gate.json 과 하위 판정 파일에서 README 표에 옮길 값을 적는다.
사용: python harness/evidence/pml-S7-20260926-0300/table.py Q1-r2 Q2-r3 ...
"""
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
GATED = ('C01_', 'C02_', 'C05_', 'C13A_', 'C14_', 'C22_', 'C29_', 'C30_', 'C31_')


def load(p):
    try:
        return json.loads(Path(p).read_text(encoding='utf-8-sig'))
    except (OSError, ValueError):
        return None


def post_checks(run):
    if not run:
        return None
    return {c['checkName'].split('_')[0]: c.get('violatedRows') for c in run.get('checks') or []
            if c.get('checkName', '').startswith(GATED)}


def ack(path):
    a = load(path)
    if not a:
        return None
    rows = {k: v for k, v in (a.get('facts') or {}).items() if k.startswith('KRW-')}
    if not rows:
        return {'verdict': a.get('verdict')}
    return {'verdict': a.get('verdict'),
            'baseline_max_s': round(max(v['baseline_max_gap_s'] or 0 for v in rows.values()), 3),
            'window_max_s': round(max(v['window_max_gap_s'] or 0 for v in rows.values()), 3),
            'window_rows_min': min(v['window_rows'] for v in rows.values())}


def runner_facts(exp_dir):
    out = {}
    v = load(exp_dir / 'verdict.json') or {}
    f = v.get('failover') or {}
    out['rto_s'] = f.get('rtoSeconds')
    out['epoch'] = '%s->%s' % (f.get('oldEpoch'), f.get('newEpoch'))
    out['stale_writes'] = (v.get('metrics') or {}).get('staleWrites')
    out['ack'] = ack(exp_dir / 'ack_interval.json')
    vw = load(exp_dir / 'victim_writes.json') or {}
    out['victim_deltas'] = (vw.get('facts') or {}).get('deltas')
    out['takeover_s'] = (vw.get('facts') or {}).get('takeover_after_inject_s')
    s3 = load(exp_dir / 's3_common.json') or {}
    out['s3_common_ok'] = s3.get('ok')
    out['s3_failed'] = [k for k, x in (s3.get('axes') or {}).items() if not x]
    out['segment'] = (load(exp_dir / 'segment.json') or {}).get('verdict')
    rows = load(exp_dir / 'invariant_checks.json') or []
    out['post'] = {r['check_name'].split('_')[0]: r.get('violated_rows') for r in rows
                   if (r.get('details') or {}).get('window') == 'post' and r.get('check_name', '').startswith(GATED)} or None
    p3 = load(exp_dir / 'p3_axes.json') or {}
    out['p3_axes'] = p3.get('files')
    ra = load(exp_dir / 'relay_axes.json')
    if ra:
        out['relay'] = ra.get('verdict')
    return out


def main(names):
    for n in names:
        d = HERE / n
        g = load(d / 'gate.json') or {}
        r = g.get('result') or {}
        row = {'dir': n, 'verdict': g.get('verdict'), 'started': g.get('started'), 'finished': g.get('finished')}
        h = load(HERE / ('host-%s.json' % n)) or {}
        row['cpu_before_after'] = [(h.get('before') or {}).get('cpuAvg'), (h.get('after') or {}).get('cpuAvg')]
        csv = HERE / ('cpu-%s.csv' % n)
        if csv.exists():
            vals = []
            for line in csv.read_text(encoding='utf-8', errors='replace').splitlines()[1:]:
                try:
                    vals.append(float(line.split(',')[1].strip('"')))
                except (IndexError, ValueError):
                    pass
            if vals:
                row['cpu_run_avg_max'] = [round(sum(vals) / len(vals), 1), round(max(vals), 1)]
        if r.get('runner'):
            row['axes'] = r.get('axes')
            row.update(runner_facts(d / 'runner' / r['experiment']))
        else:
            row['judge'] = r.get('judge')
            post = r.get('post') or {}
            row['post'] = {k: post.get(k) for k in ('drained', 'integrity', 'summaryOk', 'segment', 'ok')}
            row['post_checks'] = post_checks(load(d / 'post_run.json'))
        print(json.dumps(row, ensure_ascii=False, default=str))


if __name__ == '__main__':
    main(sys.argv[1:])
