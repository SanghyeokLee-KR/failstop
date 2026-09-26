"""X5 판정(S3 설계 7-3 X5, ADR 0034 결정 5). run-s3-gate.ps1 -Phase X5 가 남긴 칸 파일을 읽어 손실과 잡음을 낸다.

측정 (i) 12마켓 600/s: 칸 값은 roundtrip_probe 의 '접수/s'(k6 주행 중 1분 창의 201/s)다. S2 관문 A/B 와 같은 값이다.
측정 (ii) KRW-BTC 과포화: 칸 값은 '단일 마켓'.'접수율(/s)'(주행 창의 KRW-BTC k6 NEW 수 / 주행 초)이다.
규칙: 400 비율이 1퍼센트를 넘는 칸은 NOT RUN. 잡음 n = |P1a - P1b| / 평균(P1)(세 칸이면 범위/중앙값)이 0.10 을 넘으면
P1c·P2c 를 더 돌아야 한다. 손실 L = 1 - 평균(P2)/평균(P1)(세 칸이면 중앙값)이 0.20 이하면 PASS. [L - n, L + n] 이 0.20 을
걸치면 경계로 적는다. (ii)는 모든 칸의 접수율이 요청률의 80퍼센트 이하여야 과포화로 본다.

사용: python judge_x5.py X5 --out X5/judge.json
"""
import argparse
import json
import re
import statistics
from pathlib import Path


def ratio400(cell):
    rows = (cell.get('prom') or {}).get('ordersByInstanceStatus')
    if not isinstance(rows, list):
        return None
    tot = sum(r['value'] for r in rows)
    bad = sum(r['value'] for r in rows if (r.get('metric') or {}).get('status') == '400')
    return (bad / tot) if tot else None


def load(dirpath, measure, hot_rate):
    want = re.compile(r'^(P[12][a-z])-%s$' % ('600' if measure == '600' else 'hot%d' % hot_rate))
    out = {}
    for f in sorted(Path(dirpath).glob('P*.json')):
        name = f.stem
        m = want.match(name)
        if not m:
            continue
        cell = m.group(1)
        probe = json.loads(f.read_text(encoding='utf-8'))
        meta = Path(dirpath) / ('cell-%s.json' % name)
        cellinfo = json.loads(meta.read_text(encoding='utf-8-sig')) if meta.exists() else {}
        if measure == '600':
            value = probe.get('접수/s')
        else:
            value = (probe.get('단일 마켓') or {}).get('접수율(/s)')
        sm = probe.get('단일 마켓') or {}
        out[cell] = {
            'file': f.name, 'rate': probe.get('rate'), 'value': value,
            'k6_rc': probe.get('k6_rc'),
            'status400_ratio': ratio400(cellinfo),
            'oversaturated': sm.get('과포화(요청률의 80% 이하)') if measure == 'hot' else None,
            'lock_hold_ms_per_cmd': sm.get('명령당 락 보유(ms)') if measure == 'hot' else probe.get('락 보유 1회(ms)'),
            'serial_ms_per_cmd': probe.get('1건당 직렬(ms)'),
            'append_ms': probe.get('append 1회(ms)'),
            'prologue_ms': probe.get('  머리말 1회(ms)'),
            'insert_ms': probe.get('  저널삽입 1회(ms)'),
            'commit_ms': probe.get('  커밋(ms)'),
            'deadlocks': probe.get('deadlocks 증가'),
            'api1_cpu': probe.get('api1 CPU%'), 'db_cpu': probe.get('DB CPU%'),
            'mode_after': ((probe.get('모드') or {}).get('주행 뒤 상태') or {}).get('protocol'),
            'reservations': ((probe.get('모드') or {}).get('주행 뒤 상태') or {}).get('reservations'),
            'summary_ok': (cellinfo.get('summary') or {}).get('ok'),
            'summary_notPass': (cellinfo.get('summary') or {}).get('notPass'),
        }
    return out


def judge(cells):
    p1 = {k: v for k, v in cells.items() if k.startswith('P1')}
    p2 = {k: v for k, v in cells.items() if k.startswith('P2')}
    notrun = [k for k, v in cells.items() if v['value'] is None or (v['status400_ratio'] or 0) > 0.01]
    res = {'cells': cells, 'not_run_cells': notrun}
    v1 = [v['value'] for k, v in sorted(p1.items()) if k not in notrun]
    v2 = [v['value'] for k, v in sorted(p2.items()) if k not in notrun]
    if len(v1) < 2 or len(v2) < 2:
        res['verdict'] = 'NOT RUN'
        res['reason'] = '유효 칸이 모드마다 둘 이상이어야 한다'
        return res
    if len(v1) == 2:
        center1, noise = statistics.mean(v1), abs(v1[0] - v1[1]) / statistics.mean(v1)
        center2 = statistics.mean(v2)
    else:
        center1 = statistics.median(v1)
        noise = (max(v1) - min(v1)) / center1
        center2 = statistics.median(v2)
    loss = 1 - center2 / center1
    res.update({'P1': v1, 'P2': v2, 'center_P1': center1, 'center_P2': center2, 'noise_n': noise, 'loss_L': loss,
                'interval': [loss - noise, loss + noise]})
    if noise > 0.10:
        res['verdict'] = 'NEED_MORE' if len(v1) == 2 else 'NOT RUN'
        res['reason'] = '잡음 n 이 0.10 을 넘는다'
    else:
        res['verdict'] = 'PASS' if loss <= 0.20 else 'FAIL'
    res['boundary'] = (loss - noise) <= 0.20 <= (loss + noise)
    holds1 = [v['lock_hold_ms_per_cmd'] for k, v in sorted(p1.items()) if v['lock_hold_ms_per_cmd']]
    holds2 = [v['lock_hold_ms_per_cmd'] for k, v in sorted(p2.items()) if v['lock_hold_ms_per_cmd']]
    if holds1 and holds2:
        res['lock_hold_ratio'] = 1 - statistics.mean(holds1) / statistics.mean(holds2)
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('dir')
    ap.add_argument('--out', required=True)
    ap.add_argument('--hot-rate', type=int, default=300)
    a = ap.parse_args()
    out = {'hot_rate': a.hot_rate}
    for measure in ('600', 'hot'):
        cells = load(a.dir, measure, a.hot_rate)
        if not cells:
            out[measure] = {'verdict': 'NOT RUN', 'reason': '칸 파일 없음'}
            continue
        r = judge(cells)
        if measure == 'hot':
            over = [k for k, v in cells.items() if v['oversaturated'] is False]
            r['not_oversaturated_cells'] = over
            if over and r.get('verdict') in ('PASS', 'FAIL'):
                r['verdict'] = 'RERUN_450'
                r['reason'] = '접수율이 요청률의 80퍼센트를 넘은 칸이 있어 과포화가 아니다'
        out[measure] = r
    both = [out[m].get('verdict') for m in ('600', 'hot')]
    out['decision5'] = ('PASS' if all(v == 'PASS' for v in both)
                        else 'FAIL' if any(v == 'FAIL' for v in both) else 'NOT JUDGED')
    Path(a.out).write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({m: {k: out[m].get(k) for k in ('verdict', 'center_P1', 'center_P2', 'loss_L', 'noise_n',
                                                        'boundary', 'not_run_cells')} for m in ('600', 'hot')},
                     ensure_ascii=False, indent=1))
    print('decision5', out['decision5'])


if __name__ == '__main__':
    main()
