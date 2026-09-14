"""ADR 0022 6단계 판정. 같은 6단계 이미지의 B4(래치 없음)와 B6(트래픽 전 래치)를 같은 날 같은 스택에서 가른다.

근거는 ADR 0022 7절 6단계와 docs/contracts/하네스_검증설계.md 의 「ADR 0022 6단계 B4·B6 판정 규약」 문단이다.
칸 식별, 양성 축, B4 기준선, 판정 순서, 분기 기록, 종료코드는 그 문단 글자를 옮긴다. 접수 비, 제시 부하,
append 풀 대기, fsync 키, 연결 고갈 분리의 입력 해석은 4단계 규약과 같아 stage4_judge 의 입력 함수를 쓴다.

돌리는 법:
  python harness/scale/stage6_judge.py --b4 <B4.json> --b6 <B6.json> --out <verdict.json>

종료코드: 0 채번 분할 효과 또는 평평, 1 래치 무효, 2 NOT RUN, 4 증거 불완전(연결 고갈).
"""
import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pg_samplers as pgs  # noqa: E402
import stage4_judge as s4  # noqa: E402

RATE = 600
MARKET_COUNT = 12
COUNTER_SAMPLES_MIN = 5    # 이상
COUNTER_INTERVAL_S = 15
T1_FLAT = 1.15             # 초과면 채번 분할 효과

CONTAINERS = ('chaos-api-1', 'chaos-api-2', 'chaos-bots')
CONTROLS = (('전역', '카운터 행 양성 대조(전역)', 'global_row_wait', 'global_row'),
            ('마켓', '카운터 행 양성 대조(마켓)', 'market_row_wait', 'market_row'))

EXIT = {'채번 분할 효과': 0, '평평': 0, '래치 무효': 1, 'NOT RUN': 2, '증거 불완전(연결 고갈)': 4}


def is_count(v):
    return isinstance(v, int) and not isinstance(v, bool) and v >= 0


def serving_images(cell):
    per = (cell.get('메타') or {}).get('컨테이너 이미지') or {}
    return [(per.get(c) or {}).get('image_id') for c in CONTAINERS]


def latch_rows(cell):
    """journal_cutover 행 목록. 조회에 실패했으면 None 이다."""
    state = (cell.get('래치') or {}).get('상태') or {}
    rows = state.get('래치 행')
    return rows if state.get('ok') and isinstance(rows, list) else None


def k6_markets(cell):
    wanted = (cell.get('접수(DB, 마켓별)') or {}).get('k6 마켓')
    return wanted if isinstance(wanted, list) and len(wanted) == MARKET_COUNT else None


def band_rows(cell):
    """마켓 기호별 창 안 대역 행 수. 조회에 실패했거나 수가 아닌 값이 섞이면 None 이다."""
    d = cell.get('대역 행(DB, 마켓별)') or {}
    rows = d.get('마켓')
    if d.get('error') or not isinstance(rows, list):
        return None
    per = {r.get('symbol'): r.get('band_rows') for r in rows if isinstance(r, dict)}
    return per if all(is_count(v) for v in per.values()) else None


def c28(cell):
    d = cell.get('C28') or {}
    v = d.get('위반 행')
    return v if d.get('ok') and is_count(v) else None


def identify(b4, b6):
    rows = []

    def row(name, ok, value):
        rows.append({'항목': name, '충족': bool(ok), '값': value})

    row('요청 부하 600/s', b4.get('rate') == RATE and b6.get('rate') == RATE, [b4.get('rate'), b6.get('rate')])
    d4, d6 = s4.run_date(b4), s4.run_date(b6)
    row('같은 날', d4 is not None and d4 == d6, [d4, d6])
    sig4, sig6 = s4.stack_signature(b4), s4.stack_signature(b6)
    diff = sorted(k for k in sig4 if sig4[k] != sig6[k] or sig4[k] is None)
    row('같은 스택과 같은 부하 설정', not diff, {'다른 항목': diff})
    ids = serving_images(b4) + serving_images(b6)
    row('같은 이미지(두 칸 세 컨테이너의 서빙 이미지 id 가 하나)', all(ids) and len(set(ids)) == 1, ids)

    l4, l6 = latch_rows(b4), latch_rows(b6)
    row('B4 래치 행 없음', l4 == [], l4)
    m4, per4 = k6_markets(b4), band_rows(b4)
    # 마켓 행이 비어 합이 0 이 되는 공허 식별을 막으려고 k6 마켓이 모두 조회에 있어야 한다.
    row('B4 대역 행 0', m4 is not None and per4 is not None and set(m4) <= set(per4) and sum(per4.values()) == 0,
        per4)
    row('B6 래치 행 있음', bool(l6), l6)
    m6, per6 = k6_markets(b6), band_rows(b6)
    k6_band = {m: per6.get(m) for m in m6} if m6 is not None and per6 is not None else None
    row('B6 12마켓 모두 대역 행 > 0',
        k6_band is not None and all(is_count(v) and v > 0 for v in k6_band.values()), k6_band)
    v28 = c28(b6)
    row('B6 C28 0', v28 == 0, v28)
    return rows


def split_summary(cell):
    d = cell.get('카운터 행 표본(전역·마켓)') or {}
    samples = d.get('표본')
    if not isinstance(samples, list):
        return {'전역': None, '마켓': None, '간격(초)': d.get('간격(초)')}
    return {'전역': pgs.summarize_counter_samples(samples, 'global_row_wait'),
            '마켓': pgs.summarize_counter_samples(samples, 'market_row_wait'), '간격(초)': d.get('간격(초)')}


def control_state(cell, key, count_key, flag):
    d = cell.get(key) or {}
    if not isinstance(d.get('표본'), list):
        return 'NOT RUN'
    return pgs.control_verdict(d['표본'], d.get('보유 세션 종료코드'), d.get('대기 세션 종료코드'),
                               count_key, flag)['상태']


def positive_axes(b4, b6):
    axes = []

    def axis(name, label, ok, value):
        axes.append({'축': name, '칸': label, '충족': bool(ok), '값': value})

    for label, cell in (('B4', b4), ('B6', b6)):
        per = s4.market_accepts(cell)
        axis('12마켓 각 접수 0 초과', label,
             per is not None and len(per) == MARKET_COUNT and all(is_count(v) and v > 0 for v in per.values()), per)
        rc = cell.get('k6_rc')
        axis('k6_rc 0', label, is_count(rc) and rc == 0, rc)
        p_now, p_max = s4.num(cell, 'append 풀 대기'), s4.num(cell, 'append 풀 대기 최대(1m)')
        axis('append 풀 대기(순간과 1분 최대) 0', label, p_now == 0 and p_max == 0,
             {'순간': p_now, '1분 최대': p_max})
        f1, f2 = s4.num(cell, 'fsync fdatasync/s'), s4.num(cell, 'fsync 건당(ms)')
        axis('fsync 키 존재', label, f1 is not None and f2 is not None, [f1, f2])
        split = split_summary(cell)
        n_ok = split['전역']['채집 성공'] if split['전역'] else None
        axis('카운터 행 표본 15초 간격 성공 5회 이상', label,
             split['간격(초)'] == COUNTER_INTERVAL_S and n_ok is not None and n_ok >= COUNTER_SAMPLES_MIN,
             {'채집 성공': n_ok, '간격(초)': split['간격(초)']})
        for target, key, count_key, flag in CONTROLS:
            state = control_state(cell, key, count_key, flag)
            axis('양성 대조 PASS(%s)' % target, label, state == 'PASS', state)
    return axes


def branch(b6):
    """B6 커밋 동시성의 분기 기록. 1.2 문턱과 N/A 식은 4단계 T2 와 같고 최종 판정을 바꾸지 않는다."""
    t = s4.t2(b6)
    t['분기'] = {'초과': '분기 B(동시 커밋 확장)', 'PASS': '분기 A(저장소 직렬)'}.get(t['상태'], t['상태'])
    return t


def decide(ratio, b6_global_wait):
    """래치 무효를 접수 비보다 먼저 본다. 대역 칸의 머리말이 전역 카운터 행을 기다렸다면 비와 무관하게
    채번이 갈리지 않았다."""
    if b6_global_wait > 0:
        return '래치 무효'
    if ratio > T1_FLAT:
        return '채번 분할 효과'
    return '평평'


def judge(b4, b6):
    b4 = b4 if isinstance(b4, dict) else {}
    b6 = b6 if isinstance(b6, dict) else {}
    ident = identify(b4, b6)
    conn = {'B4': s4.connection_state(b4), 'B6': s4.connection_state(b6)}
    axes = positive_axes(b4, b6)
    acc4, acc6 = s4.num(b4, '접수/s'), s4.num(b6, '접수/s')
    ratio = acc6 / acc4 if acc4 and acc6 is not None else None
    split4, split6 = split_summary(b4), split_summary(b6)
    wait4 = split4['전역']['대기 합'] if split4['전역'] else None
    wait6 = split6['전역']['대기 합'] if split6['전역'] else None

    if not all(r['충족'] for r in ident):
        final = 'NOT RUN'
        reasons = ['칸 식별 미충족: ' + r['항목'] for r in ident if not r['충족']]
    elif any(c['상태'] == '증거 불완전(연결 고갈)' for c in conn.values()):
        final = '증거 불완전(연결 고갈)'
        reasons = ['%s 연결 고갈' % k for k, c in conn.items() if c['상태'] == final]
    elif any(c['상태'] != '고갈 없음' for c in conn.values()):
        final = 'NOT RUN'
        reasons = ['%s 연결 고갈 여부를 확인하지 못했다' % k for k, c in conn.items() if c['상태'] != '고갈 없음']
    elif not all(x['충족'] for x in axes):
        final = 'NOT RUN'
        reasons = ['양성 축 미충족: %s %s' % (x['칸'], x['축']) for x in axes if not x['충족']]
    elif ratio is None or wait4 is None or wait6 is None:
        final = 'NOT RUN'
        reasons = ['판정 입력 없음: 접수 비 %s, B4 전역 대기 합 %s, B6 전역 대기 합 %s' % (ratio, wait4, wait6)]
    elif wait4 == 0:
        final = 'NOT RUN'
        reasons = ['재직렬화 기준선 없음: B4 전역 카운터 행 대기 합 0']
    else:
        final = decide(ratio, wait6)
        reasons = ['비 %.4f, B6 전역 카운터 행 대기 합 %s, B4 전역 카운터 행 대기 합 %s' % (ratio, wait6, wait4)]

    return {
        '최종 판정': final,
        '사유': reasons,
        '칸 식별': ident,
        '연결': conn,
        '양성 축': axes,
        '접수 비': {'B4 접수/s': acc4, 'B6 접수/s': acc6, '비': ratio,
                  '1.15 초과': (ratio > T1_FLAT) if ratio is not None else None},
        '카운터 행 대기': {'B4': split4, 'B6': split6, '규칙': pgs.COUNTER_SPLIT_RULE},
        '분기(B6)': branch(b6),
        '주': ['분기는 기록만 하고 최종 판정을 바꾸지 않는다',
              '래치 무효(B6 전역 카운터 행 대기 합 > 0)를 접수 비보다 먼저 본다'],
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--b4', required=True)
    ap.add_argument('--b6', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    result = judge(s4.load(a.b4), s4.load(a.b6))
    result['입력'] = {'b4': a.b4, 'b6': a.b6}
    Path(a.out).write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding='utf-8')
    print('최종 판정: %s' % result['최종 판정'], flush=True)
    for r in result['사유']:
        print('  %s' % r, flush=True)
    print('  %s' % result['분기(B6)']['분기'], flush=True)
    return EXIT[result['최종 판정']]


if __name__ == '__main__':
    sys.exit(main())
