"""ADR 0022 4단계 판정. 같은 날 같은 스택의 B0·B4 왕복 프로브 산출물 둘을 7절 4단계 문턱으로 가른다.

근거는 ADR 0022 7절 4단계 「판정 문턱」 과 docs/contracts/하네스_검증설계.md 의 「ADR 0022 4단계 B0·B4 판정
규약」 문단이다. 문턱 수치는 ADR 글자를 옮긴 것이고 주행 뒤 고치지 않는다. 입력 키, 반증 우선, T2 N/A 식,
분류기 양성 대조 축, 칸 식별 전제, 연결 고갈 분리는 판정 규약 문단이 정한다.

돌리는 법:
  python harness/scale/stage4_judge.py --b0 <B0.json> --b4 <B4.json> --out <verdict.json>

종료코드: 0 평평 또는 명제 성립, 1 반증, 2 NOT RUN, 4 증거 불완전(연결 고갈).
"""
import argparse
import json
import sys
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pg_samplers as pgs  # noqa: E402

RATE = 600
MARKET_COUNT = 12
B0_HOLD_OCC_MIN = 0.9      # 이상
B4_HOLD_SUM_MIN = 1.0      # 초과
COUNTER_SAMPLES_MIN = 5    # 이상
COUNTER_INTERVAL_S = 15
T1_FLAT = 1.15             # 이하면 평평
T2_BOUND = 1.2             # 이하

ADR = 'ADR 0022 7절 4단계'
RULES = '하네스_검증설계 ADR 0022 4단계 B0·B4 판정 규약'

EXIT = {'평평': 0, '명제 성립': 0, '반증': 1, 'NOT RUN': 2, '증거 불완전(연결 고갈)': 4}


def num(cell, key):
    v = cell.get(key)
    return float(v) if isinstance(v, (int, float)) and not isinstance(v, bool) else None


def run_date(cell):
    try:
        return datetime.fromisoformat((cell.get('메타') or {}).get('시작')).date().isoformat()
    except (TypeError, ValueError):
        return None


def stack_signature(cell):
    meta = cell.get('메타') or {}
    samples = (cell.get('연결') or {}).get('표본')
    conn = pgs.summarize_connection_samples(samples) if isinstance(samples, list) else {}
    return {'ENV': meta.get('ENV'), 'compose sha256': meta.get('compose sha256'), 'k6': meta.get('k6'),
            'max_connections': conn.get('max_connections')}


def image_id(cell):
    return (((cell.get('메타') or {}).get('컨테이너 이미지') or {}).get('chaos-api-1') or {}).get('image_id')


def market_series(cell):
    """마켓별 락 보유 점유. 시계열이 없으면 None 이다."""
    per = cell.get('락 보유 점유(마켓별)')
    return per if isinstance(per, dict) and per else None


def market_hold_sum(cell):
    per = market_series(cell)
    if per is None:
        return None
    tagged = [v for k, v in per.items() if k and isinstance(v, (int, float))]
    return float(sum(tagged)) if tagged else None


def market_accepts(cell):
    d = cell.get('접수(DB, 마켓별)') or {}
    rows, wanted = d.get('마켓'), d.get('k6 마켓')
    if d.get('error') or not isinstance(rows, list) or not isinstance(wanted, list):
        return None
    by_symbol = {r.get('symbol'): r.get('k6_new') for r in rows if isinstance(r, dict)}
    return {m: by_symbol.get(m, 0) for m in wanted}


def counter_summary(cell):
    d = cell.get('카운터 행 표본') or {}
    samples = d.get('표본')
    if not isinstance(samples, list):
        return None, d.get('간격(초)')
    return pgs.summarize_counter_samples(samples), d.get('간격(초)')


def control_state(cell):
    d = cell.get('카운터 행 양성 대조') or {}
    if not isinstance(d.get('표본'), list):
        return 'NOT RUN'
    return pgs.control_verdict(d['표본'], d.get('보유 세션 종료코드'), d.get('대기 세션 종료코드'))['상태']


def connection_state(cell):
    d = cell.get('연결') or {}
    if not isinstance(d.get('표본'), list):
        return {'상태': 'NOT RUN', '사유': '연결 표본 없음'}
    return pgs.connection_verdict(pgs.summarize_connection_samples(d['표본']), d.get('로그'))


def preconditions(b0, b4):
    rows = []

    def row(name, ok, value, source=ADR):
        rows.append({'항목': name, '충족': bool(ok), '값': value, '출처': source})

    row('제시 부하 600/s', b0.get('rate') == RATE and b4.get('rate') == RATE, [b0.get('rate'), b4.get('rate')])
    d0, d4 = run_date(b0), run_date(b4)
    row('같은 날', d0 is not None and d0 == d4, [d0, d4])
    s0, s4 = stack_signature(b0), stack_signature(b4)
    diff = sorted(k for k in s0 if s0[k] != s4[k] or s0[k] is None)
    row('같은 스택과 같은 부하 설정', not diff, {'다른 항목': diff})
    per0 = market_series(b0)
    # 같은 코드끼리 비교하면 비가 1 근처로 나와 공허하게 평평이 된다.
    row('B0 는 전역 접수 락이다(보유 점유에 market 태그 없음)',
        per0 is not None and not any(per0.keys()), per0, RULES)
    i0, i4 = image_id(b0), image_id(b4)
    row('B0 와 B4 의 서빙 이미지가 다르다', bool(i0 and i4 and i0 != i4), [i0, i4], RULES)
    return rows


def positive_axes(b0, b4):
    axes = []

    def axis(name, cell, ok, value, source=ADR):
        axes.append({'축': name, '칸': cell, '충족': bool(ok), '값': value, '출처': source})

    occ0 = num(b0, '락 보유 점유')
    axis('B0 접수 락 보유 점유 0.9 이상', 'B0', occ0 is not None and occ0 >= B0_HOLD_OCC_MIN, occ0)
    hold_sum = market_hold_sum(b4)
    axis('B4 마켓별 접수 락 보유 점유 합 1.0 초과', 'B4', hold_sum is not None and hold_sum > B4_HOLD_SUM_MIN,
         hold_sum)
    summary, interval = counter_summary(b4)
    n_ok = summary['채집 성공'] if summary else None
    axis('B4 카운터 행 잠금 표본 채집 5회 이상(15초 간격)', 'B4',
         interval == COUNTER_INTERVAL_S and n_ok is not None and n_ok >= COUNTER_SAMPLES_MIN,
         {'채집 성공': n_ok, '간격(초)': interval, '채집 실패': summary['채집 실패'] if summary else None})
    # 양성 대조 전의 대기 0 은 분류기가 대기를 못 잡는 경우와 갈리지 않아 반증으로 쓸 수 없다.
    ctl = control_state(b4)
    axis('B4 카운터 행 분류기 양성 대조 PASS', 'B4', ctl == 'PASS', ctl, RULES)
    for label, cell in (('B0', b0), ('B4', b4)):
        per = market_accepts(cell)
        axis('12마켓 각 접수 0 초과', label,
             per is not None and len(per) == MARKET_COUNT
             and all(isinstance(v, int) and v > 0 for v in per.values()), per)
        rc = cell.get('k6_rc')
        axis('k6_rc 0', label, isinstance(rc, int) and not isinstance(rc, bool) and rc == 0, rc)
        f1, f2 = num(cell, 'fsync fdatasync/s'), num(cell, 'fsync 건당(ms)')
        axis('fsync 키 존재', label, f1 is not None and f2 is not None, [f1, f2])
        p_now, p_max = num(cell, 'append 풀 대기'), num(cell, 'append 풀 대기 최대(1m)')
        axis('hikaricp_connections_pending{pool="exchange-append"} 0', label, p_now == 0 and p_max == 0,
             {'순간': p_now, '1분 최대': p_max})
    return axes


def t2(cell):
    """커밋 동시성이 1.2 이하여도 제시 부하 x 커밋 1회가 1.2 이하면 넘을 수가 없어 판정력이 없다(N/A)."""
    conc, acc, rej, cmt = num(cell, '커밋 동시성'), num(cell, '접수/s'), num(cell, '수용거절/s'), num(cell, '  커밋(ms)')
    offered = acc + rej if acc is not None and rej is not None else None
    cap = offered * cmt / 1000.0 if offered is not None and cmt is not None else None
    if conc is None:
        state = 'NOT RUN'
    elif conc > T2_BOUND:
        state = '초과'
    elif cap is None:
        state = 'NOT RUN'
    elif cap <= T2_BOUND:
        state = 'N/A'
    else:
        state = 'PASS'
    return {'커밋 동시성': conc, '제시 부하/s': offered, '커밋 1회(ms)': cmt, '산술 상한': cap, '상태': state}


def a_bound(b0, b4):
    hold, occ = num(b0, '락 보유 1회(ms)'), num(b0, '락 보유 점유')
    ins, cmt = num(b4, '  저널삽입 1회(ms)'), num(b4, '  커밋(ms)')
    inputs = {'B0 락 보유 1회(ms)': hold, 'B0 락 보유 점유': occ, 'B4 저널삽입 1회(ms)': ins, 'B4 커밋 1회(ms)': cmt}
    if None in inputs.values() or (ins + cmt) * occ <= 0:
        return None, inputs
    return hold / ((ins + cmt) * occ), inputs


def decide(ratio, a, wait_sum):
    """반증을 T1 보다 먼저 본다. 600/s 칸의 반증은 대기 표본 0 하나가 맡고(ADR 참고 항), 비가 A 를 넘으면
    명제의 상한을 넘은 것이라 평평보다 앞선다."""
    if ratio > a or wait_sum == 0:
        return '반증'
    if ratio <= T1_FLAT:
        return '평평'
    return '명제 성립'


def judge(b0, b4):
    b0 = b0 if isinstance(b0, dict) else {}
    b4 = b4 if isinstance(b4, dict) else {}
    pre = preconditions(b0, b4)
    conn = {'B0': connection_state(b0), 'B4': connection_state(b4)}
    axes = positive_axes(b0, b4)
    acc0, acc4 = num(b0, '접수/s'), num(b4, '접수/s')
    ratio = acc4 / acc0 if acc0 and acc4 is not None else None
    a, a_inputs = a_bound(b0, b4)
    summary, _ = counter_summary(b4)
    wait_sum = summary['대기 합'] if summary else None

    reasons = []
    if not all(p['충족'] for p in pre):
        final = 'NOT RUN'
        reasons = ['전제 미충족: ' + p['항목'] for p in pre if not p['충족']]
    elif any(c['상태'] == '증거 불완전(연결 고갈)' for c in conn.values()):
        final = '증거 불완전(연결 고갈)'
        reasons = ['%s 연결 고갈' % k for k, c in conn.items() if c['상태'] == '증거 불완전(연결 고갈)']
    elif any(c['상태'] != '고갈 없음' for c in conn.values()):
        final = 'NOT RUN'
        reasons = ['%s 연결 고갈 여부를 확인하지 못했다' % k for k, c in conn.items() if c['상태'] != '고갈 없음']
    elif not all(x['충족'] for x in axes):
        final = 'NOT RUN'
        reasons = ['양성 축 미충족: %s %s' % (x['칸'], x['축']) for x in axes if not x['충족']]
    elif ratio is None or a is None or wait_sum is None:
        final = 'NOT RUN'
        reasons = ['판정 입력 없음: 접수 비 %s, A %s, 카운터 행 대기 합 %s' % (ratio, a, wait_sum)]
    else:
        final = decide(ratio, a, wait_sum)
        reasons = ['비 %.4f, A %.4f, 카운터 행 대기 합 %s' % (ratio, a, wait_sum)]

    return {
        '최종 판정': final,
        '사유': reasons,
        '전제': pre,
        '연결': conn,
        '양성 축': axes,
        'T1': {'B0 접수/s': acc0, 'B4 접수/s': acc4, '비': ratio,
               '평평(1.15 이하)': (ratio <= T1_FLAT) if ratio is not None else None},
        'A': {'값': a, '입력': a_inputs, '비가 A 초과': (ratio > a) if ratio is not None and a is not None else None},
        '카운터 행 대기(B4)': {'요약': summary, '분류기 양성 대조': control_state(b4), '규칙': pgs.COUNTER_ROW_RULE},
        'T2': {'B0': t2(b0), 'B4': t2(b4)},
        '주': ['T2 는 값과 상태만 적고 최종 판정을 바꾸지 않는다',
              '반증 조건(대기 표본 0 또는 비 > A)은 T1 보다 먼저 본다'],
    }


def load(path):
    try:
        return json.loads(Path(path).read_text(encoding='utf-8'))
    except (OSError, ValueError) as e:
        print('입력을 읽지 못했다(%s): %s' % (path, e), flush=True)
        return {}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--b0', required=True)
    ap.add_argument('--b4', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    result = judge(load(a.b0), load(a.b4))
    result['입력'] = {'b0': a.b0, 'b4': a.b4}
    Path(a.out).write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding='utf-8')
    print('최종 판정: %s' % result['최종 판정'], flush=True)
    for r in result['사유']:
        print('  %s' % r, flush=True)
    print('  T2 B0 %s, B4 %s' % (result['T2']['B0']['상태'], result['T2']['B4']['상태']), flush=True)
    return EXIT[result['최종 판정']]


if __name__ == '__main__':
    sys.exit(main())
