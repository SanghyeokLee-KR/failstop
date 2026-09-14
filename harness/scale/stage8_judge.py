"""ADR 0022 8단계 판정. 같은 이미지·같은 날·같은 스택의 대역 모드 칸 여덟으로 commit_delay·commit_siblings 격자를 가른다.

근거는 ADR 0022 7절 8단계와 docs/contracts/하네스_검증설계.md 의 「ADR 0022 8단계 commit_delay 격자 판정 규약」
문단이다. 격자와 순서, 칸 식별, 양성 축, 전제, 드리프트, 범주 문턱, 판정 순서, 종료코드는 그 문단 글자를 옮기고
주행 뒤 고치지 않는다. 도커 저장소 fsync 가 날마다 달라 이 판정은 참고치이고 채택 판단이 아니다. 접수, 연결, 래치,
대역 행 입력 해석은 4·6단계 판정기 함수를 쓴다. 절차는 --print-grid 로 격자를 받는다.

돌리는 법:
  python harness/scale/stage8_judge.py --dir <증거 폴더> --out <verdict.json>
  python harness/scale/stage8_judge.py --print-grid

종료코드: 0 이득 있음 또는 이득 없음(도커 참고), 1 묶음 무효, 2 NOT RUN, 4 증거 불완전(연결 고갈).
"""
import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import commit_delay as cd  # noqa: E402
import stage4_judge as s4  # noqa: E402
import stage6_judge as s6  # noqa: E402

RATE = 600
MARKET_COUNT = 12
# (칸, commit_delay 마이크로초, commit_siblings). 이 순서가 주행 순서다.
GRID = (('base-1', 0, 5), ('d3000-s1', 3000, 1), ('d250-s5', 250, 5), ('d1000-s5', 1000, 5),
        ('d1000-s1', 1000, 1), ('d250-s1', 250, 1), ('d3000-s5', 3000, 5), ('base-2', 0, 5))
BASELINES = ('base-1', 'base-2')
DRIFT_MAX = 0.05          # 이하
GAIN = 1.10               # 초과
LOSS = 0.90               # 미만
BATCH_UP = 1.10           # 초과
WAL_WINDOW_MIN_S = 50     # 이상

GAIN_FOUND = '이득 있음(도커 참고)'
NO_GAIN = '이득 없음(도커 참고)'
BATCH_INVALID = '묶음 무효'
EXHAUSTED = '증거 불완전(연결 고갈)'
EXIT = {GAIN_FOUND: 0, NO_GAIN: 0, BATCH_INVALID: 1, 'NOT RUN': 2, EXHAUSTED: 4}

BASE_KEYS = ('접수/s', '묶음 비', 'p95(ms)', '커밋 1회(ms)')
DRIFT_RECORDED = ('묶음 비', 'p95(ms)', '커밋 1회(ms)', '커밋 동시성', 'fsync 건당(ms)')


def load_cells(directory):
    """격자 칸마다 <칸>.json 을 읽는다. 없거나 읽지 못한 칸은 None 이다."""
    cells = {}
    for label, _, _ in GRID:
        try:
            cell = json.loads((Path(directory) / ('%s.json' % label)).read_text(encoding='utf-8'))
        except (OSError, ValueError):
            cell = None
        cells[label] = cell if isinstance(cell, dict) else None
    return cells


def commit_settings(cell, key):
    return (cell.get('커밋 설정') or {}).get(key) or {}


def same_request(label, cell, delay, siblings):
    return (cell.get('label') == label
            and commit_settings(cell, '적용').get('요청') == {'commit_delay': delay, 'commit_siblings': siblings})


def setting_applied(cell, delay, siblings):
    block = commit_settings(cell, '적용')
    return block.get('ok') is True and cd.in_effect(block.get('확인'), delay, siblings)


def latched_before_traffic(cell):
    latch = cell.get('래치') or {}
    return (latch.get('트래픽 전 래치') is True and (latch.get('호출') or {}).get('ok') is True
            and bool(s6.latch_rows(cell)))


def band_in_all_markets(cell):
    wanted, per = s6.k6_markets(cell), s6.band_rows(cell)
    return wanted is not None and per is not None and all(s6.is_count(per.get(m)) and per.get(m) > 0 for m in wanted)


def identify(cells):
    rows = []

    def row(name, failed, value=None):
        rows.append({'항목': name, '충족': not failed, '미충족 칸': failed, '값': value})

    row('격자 칸 여덟 존재', [label for label, _, _ in GRID if cells.get(label) is None])
    present = [(label, delay, siblings, cells[label]) for label, delay, siblings in GRID if cells.get(label) is not None]
    if not present:
        return rows
    ref = present[0][3]
    row('요청 부하 600/s', [label for label, _, _, c in present if c.get('rate') != RATE])
    dates = {label: s4.run_date(c) for label, _, _, c in present}
    row('같은 날', [label for label, d in dates.items() if d is None or d != s4.run_date(ref)], dates)
    ref_sig = s4.stack_signature(ref)
    row('같은 스택과 같은 부하 설정',
        [label for label, _, _, c in present
         if any(v is None or v != ref_sig[k] for k, v in s4.stack_signature(c).items())])
    images = {label: s6.serving_images(c) for label, _, _, c in present}
    ref_id = s6.serving_images(ref)[0]
    row('같은 이미지(칸마다 세 컨테이너의 서빙 이미지 id 가 하나)',
        [label for label, ids in images.items() if not ref_id or any(i != ref_id for i in ids)], images)
    row('칸 이름과 요청 값이 격자와 같음',
        [label for label, d, n, c in present if not same_request(label, c, d, n)])
    row('설정 적용 확인(SHOW 값, postgresql.auto.conf 출처, 재기동 대기 없음)',
        [label for label, d, n, c in present if not setting_applied(c, d, n)])
    row('k6 뒤 설정 유지 확인',
        [label for label, d, n, c in present if not cd.in_effect(commit_settings(c, '유지'), d, n)])
    row('트래픽 전 래치 호출 성공과 래치 행', [label for label, _, _, c in present if not latched_before_traffic(c)])
    row('k6 12마켓 모두 대역 행 > 0', [label for label, _, _, c in present if not band_in_all_markets(c)])
    row('C28 0', [label for label, _, _, c in present if s6.c28(c) != 0])
    return rows


def positive_axes(cells):
    axes = []

    def axis(label, name, ok, value):
        axes.append({'축': name, '칸': label, '충족': bool(ok), '값': value})

    for label, _, _ in GRID:
        cell = cells.get(label)
        if cell is None:
            continue
        per = s4.market_accepts(cell)
        axis(label, '12마켓 각 접수 0 초과',
             per is not None and len(per) == MARKET_COUNT and all(s6.is_count(v) and v > 0 for v in per.values()), per)
        rc = cell.get('k6_rc')
        axis(label, 'k6_rc 0', s6.is_count(rc) and rc == 0, rc)
        p_now, p_max = s4.num(cell, 'append 풀 대기'), s4.num(cell, 'append 풀 대기 최대(1m)')
        axis(label, 'append 풀 대기(순간과 1분 최대) 0', p_now == 0 and p_max == 0, {'순간': p_now, '1분 최대': p_max})
        f1, f2 = s4.num(cell, 'fsync fdatasync/s'), s4.num(cell, 'fsync 건당(ms)')
        axis(label, 'fsync 키 존재', f1 is not None and f2 is not None, [f1, f2])
        accept = s4.num(cell, '접수/s')
        axis(label, '접수/s 0 초과', accept is not None and accept > 0, accept)
        w = cell.get('WAL 창') or {}
        secs, syncs, xids = s4.num(w, '초'), w.get('wal_sync 증가'), w.get('쓰기 트랜잭션 증가')
        axis(label, 'WAL 창 50초 이상, wal_sync 증가와 쓰기 트랜잭션 증가 0 초과',
             secs is not None and secs >= WAL_WINDOW_MIN_S and s6.is_count(syncs) and syncs > 0
             and s6.is_count(xids) and xids > 0 and s4.num(w, '묶음 비') is not None,
             {'초': secs, 'wal_sync 증가': syncs, '쓰기 트랜잭션 증가': xids, 'error': w.get('error')})
    return axes


def metrics(cell):
    w = cell.get('WAL 창') or {}
    return {
        '접수/s': s4.num(cell, '접수/s'), '수용거절/s': s4.num(cell, '수용거절/s'), 'p95(ms)': s4.num(cell, 'p95(ms)'),
        '커밋 1회(ms)': s4.num(cell, '  커밋(ms)'), '커밋 동시성': s4.num(cell, '커밋 동시성'),
        '락 보유 1회(ms)': s4.num(cell, '락 보유 1회(ms)'), 'DB CPU%': s4.num(cell, 'DB CPU%'),
        'WAL fsync/s': s4.num(w, 'WAL fsync/s'), '묶음 비': s4.num(w, '묶음 비'),
        '묶음 비(xact_commit)': s4.num(w, '묶음 비(xact_commit)'), 'WAL 바이트/fsync': s4.num(w, 'WAL 바이트/fsync'),
        'WAL fsync 1회(ms)': s4.num(w, 'WAL fsync 1회(ms)'), 'fsync 건당(ms)': s4.num(cell, 'fsync 건당(ms)'),
    }


def mean(a, b):
    return (a + b) / 2 if a is not None and b is not None else None


def ratio(value, base):
    return value / base if value is not None and base else None


def relative_gap(a, b):
    return abs(b - a) / ((a + b) / 2) if a is not None and b is not None and a + b > 0 else None


def drift(by_label):
    first, last = (by_label[label] for label in BASELINES)
    gap = relative_gap(first['접수/s'], last['접수/s'])
    return {'접수/s': {BASELINES[0]: first['접수/s'], BASELINES[1]: last['접수/s'], '차 비': gap},
            '허용': DRIFT_MAX, '충족': gap is not None and gap <= DRIFT_MAX,
            '기록만': {k: {BASELINES[0]: first[k], BASELINES[1]: last[k], '차 비': relative_gap(first[k], last[k])}
                    for k in DRIFT_RECORDED}}


def classify(accept_ratio, batch_ratio):
    if accept_ratio < LOSS:
        return '손해'
    if accept_ratio > GAIN:
        return '이득' if batch_ratio > BATCH_UP else '묶음 없는 증가'
    return '평평'


def grid_rows(by_label, base):
    rows = []
    for label, delay, siblings in GRID:
        if label in BASELINES:
            continue
        m = by_label[label]
        accept, batch = ratio(m['접수/s'], base['접수/s']), ratio(m['묶음 비'], base['묶음 비'])
        row = {'칸': label, 'commit_delay': delay, 'commit_siblings': siblings}
        row.update(m)
        row.update({'접수 비': accept, '묶음 비 비': batch, 'p95 비': ratio(m['p95(ms)'], base['p95(ms)']),
                    '커밋 1회 비': ratio(m['커밋 1회(ms)'], base['커밋 1회(ms)']),
                    '범주': classify(accept, batch) if accept is not None and batch is not None else None})
        rows.append(row)
    return rows


def decide(rows):
    """묶음 무효를 범주보다 먼저 본다. 지연을 건 칸 어디서도 묶음 비가 오르지 않았다면 접수 변화를 commit_delay 의
    몫으로 읽을 수 없다."""
    if not any(r['묶음 비 비'] > BATCH_UP for r in rows):
        return BATCH_INVALID
    if any(r['범주'] == '이득' for r in rows):
        return GAIN_FOUND
    return NO_GAIN


def judge(cells):
    cells = {label: cells.get(label) if isinstance(cells.get(label), dict) else None for label, _, _ in GRID}
    ident = identify(cells)
    conn = {label: s4.connection_state(cell) for label, cell in cells.items() if cell is not None}
    axes = positive_axes(cells)
    premise = {label: s6.branch(cells[label] or {}) for label in BASELINES}
    by_label = {label: metrics(cell or {}) for label, cell in cells.items()}
    dr = drift(by_label)
    base = {k: mean(by_label[BASELINES[0]][k], by_label[BASELINES[1]][k]) for k in BASE_KEYS}
    rows = grid_rows(by_label, base)
    exhausted = [label for label, c in conn.items() if c['상태'] == EXHAUSTED]
    unknown = [label for label, c in conn.items() if c['상태'] != '고갈 없음']

    if not all(r['충족'] for r in ident):
        final = 'NOT RUN'
        reasons = ['칸 식별 미충족: %s (%s)' % (r['항목'], ', '.join(r['미충족 칸'])) for r in ident if not r['충족']]
    elif exhausted:
        final = EXHAUSTED
        reasons = ['%s 연결 고갈' % label for label in exhausted]
    elif unknown:
        final = 'NOT RUN'
        reasons = ['%s 연결 고갈 여부를 확인하지 못했다' % label for label in unknown]
    elif not all(x['충족'] for x in axes):
        final = 'NOT RUN'
        reasons = ['양성 축 미충족: %s %s' % (x['칸'], x['축']) for x in axes if not x['충족']]
    elif any(t['상태'] != '초과' for t in premise.values()):
        final = 'NOT RUN'
        reasons = ['전제 미성립: %s %s(커밋 동시성 %s)' % (label, t['분기'], t['커밋 동시성'])
                   for label, t in premise.items() if t['상태'] != '초과']
    elif not dr['충족']:
        final = 'NOT RUN'
        reasons = ['드리프트 초과: 기준 칸 접수/s 차 비 %s 가 %s 초과' % (dr['접수/s']['차 비'], DRIFT_MAX)]
    else:
        final = decide(rows)
        reasons = ['%s: 접수 비 %.4f, 묶음 비 비 %.4f, 범주 %s' % (r['칸'], r['접수 비'], r['묶음 비 비'], r['범주'])
                   for r in rows]

    return {
        '최종 판정': final,
        '사유': reasons,
        '격자': [{'칸': label, 'commit_delay': d, 'commit_siblings': n} for label, d, n in GRID],
        '칸 식별': ident,
        '연결': conn,
        '양성 축': axes,
        '전제(기준 칸 분기 B)': premise,
        '드리프트': dr,
        '기준값': base,
        '기준 칸': {label: by_label[label] for label in BASELINES},
        '칸별': rows,
        '문턱': {'드리프트 이하': DRIFT_MAX, '이득 접수 비 초과': GAIN, '손해 접수 비 미만': LOSS,
               '묶음 비 비 초과': BATCH_UP, 'WAL 창 최소(초)': WAL_WINDOW_MIN_S},
        '주': ['도커 저장소 fsync 가 날마다 달라 참고치이고 채택 판단이 아니다. 채택은 클라우드 저장소 재측정 뒤 정한다',
              '범주는 접수 비와 묶음 비 비로만 정하고 p95 와 커밋 1회는 기록만 한다',
              '묶음 무효(지연 칸 어디서도 묶음 비 비 1.10 초과 없음)를 범주보다 먼저 본다'],
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dir')
    ap.add_argument('--out')
    ap.add_argument('--print-grid', action='store_true', help='절차가 읽는 격자를 JSON 으로 낸다')
    a = ap.parse_args()
    if a.print_grid:
        print(json.dumps([{'label': label, 'commit_delay': d, 'commit_siblings': n} for label, d, n in GRID]),
              flush=True)
        return 0
    if not (a.dir and a.out):
        ap.error('--dir 와 --out 이 필요하다')
    result = judge(load_cells(a.dir))
    result['입력'] = {'dir': a.dir, '칸 파일': ['%s.json' % label for label, _, _ in GRID]}
    Path(a.out).write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding='utf-8')
    print('최종 판정: %s' % result['최종 판정'], flush=True)
    for r in result['사유']:
        print('  %s' % r, flush=True)
    return EXIT[result['최종 판정']]


if __name__ == '__main__':
    sys.exit(main())
