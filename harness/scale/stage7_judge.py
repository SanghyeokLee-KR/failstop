"""ADR 0022 7단계 판정. 전역 모드와 대역 모드의 장애 주행 칸마다 승격 복구가 스냅샷 경계에서 재생했는지를 가른다.

근거는 docs/contracts/하네스_검증설계.md §2-4 의 「ADR 0022 7단계 스냅샷 경계 재생 판정 규약」이다. 모드 식별, 전제, 승격 식별,
성립 조건 (a)~(h), 판정 순서, 칸과 최종 판정 집계, 종료코드는 그 문단 글자를 옮긴다. 입력은 adr0022-stage5-chaos.ps1
-SnapshotEvidence 가 모드마다 남긴 summary.json 과 실험 폴더의 band_state.json, snapshot_pre.json, snapshot_post.json 이다.
로그 줄과 지표 줄 해석은 채집기 snapshot_evidence 의 함수를 쓴다.

돌리는 법:
  python harness/scale/stage7_judge.py --global <전역 모드 증거 폴더> --band <대역 모드 증거 폴더> --out <verdict.json>

종료코드: 0 스냅샷 경계 재생 성립, 1 FAIL, 2 NOT RUN, 4 증거 불완전.
"""
import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import snapshot_evidence as se  # noqa: E402

MODES = ('global', 'band')
EXPERIMENTS = ('E2', 'E4', 'E5', 'E6', 'E7')

ESTABLISHED = '스냅샷 경계 재생 성립'
FALLBACK = '전량 재생 폴백'
MISMATCH = '경계 재생 불일치'
NOT_RUN = 'NOT RUN'
INCOMPLETE = '증거 불완전'
FAIL = 'FAIL'

# 칸 판정과 최종 판정은 이 순서로 앞선 것이다.
ORDER = (FAIL, INCOMPLETE, NOT_RUN, ESTABLISHED)
EXIT = {ESTABLISHED: 0, FAIL: 1, NOT_RUN: 2, INCOMPLETE: 4}
SNAPSHOT_STATUS = {ESTABLISHED: ESTABLISHED, FALLBACK: FAIL, MISMATCH: FAIL, NOT_RUN: NOT_RUN, INCOMPLETE: INCOMPLETE}

FALLBACK_METRIC = 'exchange_recovery_snapshot_fallback_total'
REPLAY_METRIC = 'exchange_journal_replay_commands_total'
CONNECTION_EXHAUSTED = '증거 불완전(연결 고갈)'

RULES = [
    '칸 판정은 분리 판정과 스냅샷 판정 중 FAIL, 증거 불완전, NOT RUN, 성립 순으로 앞선 것이다',
    '최종 판정은 칸 사이에서 같은 순서다. 요청한 칸이 요약에 없으면 NOT RUN 이다',
    'RTO 는 적기만 하고 판정하지 않는다',
]


def as_int(v):
    if isinstance(v, bool):
        return None
    if isinstance(v, int):
        return v
    if isinstance(v, float) and v.is_integer():
        return int(v)
    return None


def worst(statuses):
    return min(statuses, key=ORDER.index)


def split_status(split):
    """분리 판정(5·6단계 규약)을 칸 집계 상태로 접는다. NOT RUN 계열과 BLOCKED 는 NOT RUN 이다."""
    if split == 'PASS':
        return ESTABLISHED
    if isinstance(split, str) and split.startswith('FAIL'):
        return FAIL
    if isinstance(split, str) and split.startswith('증거 불완전'):
        return INCOMPLETE
    return NOT_RUN


def rows_of(capture, key):
    d = (capture or {}).get(key) or {}
    rows = d.get('행')
    return rows if d.get('ok') and isinstance(rows, list) else None


def metric_value(capture, container, name):
    node = ((capture or {}).get('지표') or {}).get(container)
    if not (isinstance(node, dict) and node.get('ok') and isinstance(node.get('줄'), list)):
        return None
    return se.metric_total(node['줄'], name)


def counter_delta(pre, post, container, name):
    before, after = metric_value(pre, container, name), metric_value(post, container, name)
    delta = after - before if before is not None and after is not None else None
    return {'주입 전': before, '사후': after, '증분': delta}


def mode_reason(mode, summary, row, band_state):
    """모드 미성립 사유. None 이면 그 모드가 섰다."""
    if not isinstance(summary, dict) or summary.get('snapshotEvidence') is not True:
        return '모드 미성립: 요약이 스냅샷 채집 주행이 아니다'
    latched = summary.get('latchBeforeTraffic')
    if mode == 'band':
        if latched is not True:
            return '모드 미성립: 대역 모드 요약이 트래픽 전 래치 주행이 아니다'
        if (row.get('cutover') or {}).get('대역 모드 성립') is not True:
            return '모드 미성립: 대역 모드 성립이 아니다'
        return None
    if latched is not False:
        return '모드 미성립: 전역 모드 요약이 트래픽 전 래치 주행이다'
    latch = (band_state or {}).get('래치') or {}
    band = (band_state or {}).get('대역 행') or {}
    if not latch.get('ok') or latch.get('래치 행') != []:
        return '모드 미성립: 전역 모드 래치 행 %s' % latch.get('래치 행')
    markets = band.get('마켓')
    if band.get('error') is not None or not isinstance(markets, list) or not markets:
        return '모드 미성립: 전역 모드 대역 행 조회 실패(%s)' % band.get('error')
    # 수가 아닌 값도 0 으로 접지 않고 미성립으로 본다.
    banded = [r.get('symbol', r.get('id')) for r in markets if as_int(r.get('band_rows')) != 0]
    if banded:
        return '모드 미성립: 전역 모드에 대역 행이 0 이 아닌 마켓 %s' % banded
    return None


def precondition(pre):
    """주입 전 활성 마켓과 전제 미충족 사유. 사유가 None 이면 전제가 섰다."""
    if not isinstance(pre, dict):
        return None, '스냅샷 전제 미확인: snapshot_pre.json 이 없다'
    act = pre.get('활성 마켓') or {}
    markets = act.get('마켓')
    if not (act.get('ok') and isinstance(markets, list)):
        return None, '스냅샷 전제 미확인: 활성 마켓 조회 실패(%s)' % act.get('error')
    snaps = rows_of(pre, '스냅샷')
    if snaps is None:
        return None, '스냅샷 전제 미확인: 주입 전 snapshots 조회 실패'
    active = sorted({as_int(r.get('market_id')) for r in markets if (as_int(r.get('rows')) or 0) > 0} - {None})
    if not active:
        return active, '스냅샷 전제 미충족: 활성 마켓이 없다'
    covered = {as_int(r.get('market_id')) for r in snaps if (as_int(r.get('last_applied_seq')) or 0) > 0}
    missing = [m for m in active if m not in covered]
    if missing:
        return active, '스냅샷 전제 미충족: 스냅샷 없는 활성 마켓 %s' % missing
    return active, None


def window_lines(pre, post):
    """사후 로그 줄에서 주입 전 줄을 뺀 주행 창 줄. 컨테이너별로 로그 순서를 지켜 [{'container', 'ts', 'text'}]."""
    before, after = (pre or {}).get('복구 로그'), (post or {}).get('복구 로그')
    if not isinstance(before, dict) or not isinstance(after, dict) or not after:
        return None, '복구 로그 채집 결과가 없다'
    out = []
    for container in sorted(set(before) | set(after)):
        for label, node in (('주입 전', before.get(container)), ('사후', after.get(container))):
            if not (isinstance(node, dict) and node.get('ok') and isinstance(node.get('줄'), list)):
                return None, '%s %s 복구 로그 채집 실패' % (container, label)
        seen = {(line.get('ts'), line.get('text')) for line in before[container]['줄']}
        out.extend({'container': container, 'ts': line.get('ts'), 'text': line.get('text') or ''}
                   for line in after[container]['줄'] if (line.get('ts'), line.get('text')) not in seen)
    return out, None


def find_promotion(window, epoch):
    """새 epoch 의 승격 재생 완료·생략 줄과 승격 복구 구간. 없으면 None, 형식이 어긋난 승격 줄이 있으면 ValueError."""
    target = None
    for i, line in enumerate(window):
        parsed = se.parse_promotion(line['text'])
        if parsed is not None and parsed['epoch'] == epoch:
            target = (i, parsed)
    if target is None:
        return None
    i, parsed = target
    container = window[i]['container']
    start = i
    while start > 0 and window[start - 1]['container'] == container and not se.is_boundary(window[start - 1]['text']):
        start -= 1
    return dict(parsed, **{'컨테이너': container, '구간': window[start:i]})


def last_parsed(block, marker, parse, errors):
    lines = [line for line in block if marker in line['text']]
    if not lines:
        return None
    try:
        return parse(lines[-1]['text'])
    except ValueError as e:
        errors.append(str(e))
        return None


def new_recovery_rows(pre, post):
    before, after = rows_of(pre, '복구 실행'), rows_of(post, '복구 실행')
    if before is None or after is None:
        return None
    seen = {r.get('id') for r in before}
    return [r for r in after if r.get('id') not in seen]


def range_counts_of(post):
    d = (post or {}).get('저널 구간 수') or {}
    rows = d.get('구간')
    if not (d.get('ok') and isinstance(rows, list)):
        return None
    out = {}
    for r in rows:
        key = (as_int(r.get('market_id')), as_int(r.get('from')), as_int(r.get('to')))
        n = as_int(r.get('rows'))
        if None in key or n is None:
            return None
        out[key] = n
    return out


def env_overrides(*captures):
    out = {}
    for cap in captures:
        for container, node in ((cap or {}).get('스냅샷 설정 환경변수') or {}).items():
            values = (node or {}).get('값')
            if isinstance(values, dict) and values:
                out.setdefault(container, {}).update(values)
    return out


def snapshot_verdict(mode, summary, row, band_state, pre, post):
    """칸 하나의 스냅샷 판정. 규약의 판정 순서대로 처음 걸린 범주, 사유, 측정값을 돌려준다."""
    m = {}
    split = row.get('분리 판정')
    if not (split == 'PASS' or (isinstance(split, str) and split.startswith('FAIL')) or split == CONNECTION_EXHAUSTED):
        return NOT_RUN, ['실험 미성립: 분리 판정 %s' % split], m
    conn = row.get('연결 판정')
    if conn == CONNECTION_EXHAUSTED:
        return INCOMPLETE, ['연결 고갈'], m
    if conn != '고갈 없음':
        return NOT_RUN, ['연결 고갈 여부 미확인: 연결 판정 %s' % conn], m
    reason = mode_reason(mode, summary, row, band_state)
    if reason:
        return NOT_RUN, [reason], m
    active, reason = precondition(pre)
    m['활성 마켓'] = active
    if reason:
        return NOT_RUN, [reason], m
    overrides = env_overrides(pre, post)
    if overrides:
        m['주'] = '스냅샷 주기 환경변수가 있어 기본값과 다른 주행이다: %s' % overrides

    reaction = (row.get('criteria') or {}).get('2 계약된 반응') or {}
    old_epoch, new_epoch = as_int(reaction.get('oldEpoch')), as_int(reaction.get('newEpoch'))
    if old_epoch is None or new_epoch is None or new_epoch <= old_epoch:
        return NOT_RUN, ['새 epoch 없음: %s 에서 %s' % (reaction.get('oldEpoch'), reaction.get('newEpoch'))], m
    window, reason = window_lines(pre, post)
    if reason:
        return INCOMPLETE, [reason], m
    m['주행 창 로그 줄'] = len(window)
    try:
        promo = find_promotion(window, new_epoch)
    except ValueError as e:
        return INCOMPLETE, ['로그 형식 해석 실패: %s' % e], m
    if promo is None:
        return INCOMPLETE, ['epoch %d 의 승격 재생 완료·생략 줄이 주행 창에 없다' % new_epoch], m
    container = promo['컨테이너']
    m['승격'] = {k: promo[k] for k in ('종류', 'epoch', '컨테이너', '마켓 커서', 'head')}
    if promo['종류'] == '생략':
        return NOT_RUN, ['승격 재생 생략(epoch %d, %s)' % (new_epoch, container)], m
    block = promo['구간']

    fallback_lines = [line for line in window if se.is_fallback(line['text'])]
    fb = counter_delta(pre, post, container, FALLBACK_METRIC)
    replay = counter_delta(pre, post, container, REPLAY_METRIC)
    m['폴백 로그 줄'] = [{'container': line['container'], 'ts': line['ts']} for line in fallback_lines]
    m['폴백 계수'] = fb
    m['재생 명령 지표'] = replay
    parse_errors = []
    adopted = last_parsed(block, se.ADOPTED, se.parse_adopted, parse_errors)
    applied = last_parsed(block, se.APPLIED, se.parse_applied, parse_errors)
    m['채택'] = adopted
    m['재생 적용'] = applied
    new_rows = new_recovery_rows(pre, post)
    m['주행 창 recovery_runs 행'] = new_rows

    fallback = []
    if fallback_lines:
        fallback.append('주행 창 폴백 로그 %d줄(%s)' % (
            len(fallback_lines), ', '.join(sorted({line['container'] for line in fallback_lines}))))
    if fb['증분'] is not None and fb['증분'] > 0:
        fallback.append('승격 노드 %s 폴백 계수 증분 %g' % (container, fb['증분']))
    if not any(se.ADOPTED in line['text'] for line in block):
        fallback.append('승격 복구 구간에 스냅샷 채택 줄이 없다')
    if adopted is not None:
        unadopted = [x for x in active if x not in adopted]
        if unadopted:
            fallback.append('채택되지 않은 활성 마켓 %s' % unadopted)
    if applied is not None:
        from_zero = [x for x in active if x in applied and applied[x][0] == 0]
        if from_zero:
            fallback.append('시작 커서 0 에서 재생한 활성 마켓 %s' % from_zero)
    if new_rows is not None:
        full = [r for r in new_rows if r.get('matched') is True and as_int(r.get('replayed_from_seq')) == 0]
        if full:
            fallback.append('주행 창에 replayed_from_seq 0 인 matched recovery_runs 행 %d' % len(full))
    if fallback:
        return FALLBACK, fallback, m

    incomplete = ['로그 형식 해석 실패: %s' % e for e in parse_errors]
    if not any(se.APPLIED in line['text'] for line in block):
        incomplete.append('승격 복구 구간에 재생 적용 줄이 없다')
    snaps = rows_of(post, '스냅샷')
    rr_post = rows_of(post, '복구 실행')
    counts = range_counts_of(post)
    if snaps is None:
        incomplete.append('사후 snapshots 조회 실패')
    if rr_post is None or new_rows is None:
        incomplete.append('recovery_runs 조회 실패')
    if counts is None:
        incomplete.append('저널 구간 행 수 조회 실패')
    for label, d in (('폴백 계수', fb), ('재생 명령 지표', replay)):
        if d['증분'] is None:
            incomplete.append('승격 노드 %s 에서 %s 값을 읽지 못했다' % (container, label))
        elif d['증분'] < 0:
            incomplete.append('승격 노드 %s 에서 %s 값이 줄었다(%g 에서 %g)' % (container, label, d['주입 전'], d['사후']))
    if not incomplete:
        expected_from = max(adopted.values())
        expected_to = max([end for _, end, _ in applied.values()] + list(adopted.values()))
        promo_rows = [r for r in new_rows if as_int(r.get('replayed_from_seq')) == expected_from
                      and as_int(r.get('replayed_to_seq')) == expected_to]
        m['승격 recovery_runs 행'] = promo_rows
        if not promo_rows:
            incomplete.append('replayed_from_seq %d, replayed_to_seq %d 인 새 recovery_runs 행이 없다'
                              % (expected_from, expected_to))
        uncounted = sorted((mk, start, end) for mk, (start, end, _) in applied.items() if (mk, start, end) not in counts)
        if uncounted:
            incomplete.append('저널 구간 행 수가 없는 구간 %s' % uncounted)
    if incomplete:
        return INCOMPLETE, incomplete, m

    mismatch = []
    snap_keys = {(as_int(r.get('market_id')), as_int(r.get('last_applied_seq'))) for r in snaps}
    for mk, seq in sorted(adopted.items()):
        if seq <= 0:
            mismatch.append('마켓 %d 채택 seq 가 0 이하다' % mk)
        if (mk, seq) not in snap_keys:
            mismatch.append('채택 %d@%d 와 같은 snapshots 행이 없다' % (mk, seq))
        if mk not in applied:
            mismatch.append('채택 마켓 %d 의 재생 적용 항목이 없다' % mk)
        elif applied[mk][0] != seq:
            mismatch.append('마켓 %d 시작 커서 %d 가 채택 seq %d 와 다르다' % (mk, applied[mk][0], seq))
    for mk, (start, end, n) in sorted(applied.items()):
        rows = counts[(mk, start, end)]
        if rows != n:
            mismatch.append('마켓 %d 구간 %d..%d 적용 %d건, 저널 %d행' % (mk, start, end, n, rows))
    for mk, head in sorted(promo['head'].items()):
        end = applied[mk][1] if mk in applied else adopted.get(mk, 0)
        if end < head:
            mismatch.append('마켓 %d 종료 커서 %d 가 승격 head %d 아래다' % (mk, end, head))
    unmatched = [r for r in rr_post if r.get('matched') is not True]
    if unmatched:
        mismatch.append('recovery_runs matched=false 행 %d(C17)' % len(unmatched))
    node_applied = [line for line in window if line['container'] == container and se.APPLIED in line['text']]
    total = sum(n for _, _, n in applied.values())
    if len(node_applied) == 1:
        m['재생 지표 비교'] = '비교함'
        if replay['증분'] != total:
            mismatch.append('승격 노드 재생 명령 지표 증분 %g 가 적용 합 %d 와 다르다' % (replay['증분'], total))
    else:
        m['재생 지표 비교'] = '생략(주행 창 재생 적용 줄 %d)' % len(node_applied)
    if mismatch:
        return MISMATCH, mismatch, m

    tail = sum(applied[mk][2] for mk in adopted if mk in applied)
    m['꼬리 재생 합'] = tail
    if tail <= 0:
        return NOT_RUN, ['채택 마켓의 재생 적용 합이 0 이다(꼬리 재생 없음)'], m
    return ESTABLISHED, ['채택 마켓 %d개, 꼬리 재생 %d건, 승격 노드 %s' % (len(adopted), tail, container)], m


def judge(inputs, modes=MODES, experiments=EXPERIMENTS):
    """inputs 는 {모드: {'summary': dict 또는 None, 'cells': {실험: {'row', 'band_state', 'pre', 'post'}}}} 다."""
    summaries = [(inputs.get(mode) or {}).get('summary') for mode in modes]
    image_ids = [s.get('imageId') if isinstance(s, dict) else None for s in summaries]
    present = [s for s in summaries if isinstance(s, dict)]
    ident = []
    if present and (not all(s.get('imageId') for s in present) or len({s.get('imageId') for s in present}) > 1):
        ident.append('모드 사이 이미지 id 가 하나가 아니다: %s' % image_ids)

    cells = []
    for mode in modes:
        entry = inputs.get(mode) or {}
        for exp in experiments:
            cell = (entry.get('cells') or {}).get(exp)
            if cell is None:
                cells.append({'모드': mode, '실험': exp, '분리 판정': None, '실험 판정': None, '스냅샷 판정': NOT_RUN,
                              '사유': ['요약에 칸이 없다'], '칸 판정': NOT_RUN, 'RTO(초)': None, '측정': {}})
                continue
            row = cell.get('row') or {}
            category, reasons, measures = snapshot_verdict(mode, entry.get('summary'), row, cell.get('band_state'),
                                                           cell.get('pre'), cell.get('post'))
            split = row.get('분리 판정')
            status = NOT_RUN if ident else worst([split_status(split), SNAPSHOT_STATUS[category]])
            cells.append({'모드': mode, '실험': exp, '분리 판정': split, '실험 판정': row.get('실험 판정'),
                          '스냅샷 판정': category, '사유': reasons, '칸 판정': status,
                          'RTO(초)': ((row.get('criteria') or {}).get('3 복구 시간') or {}).get('rtoSeconds'),
                          '측정': measures})

    final = worst([c['칸 판정'] for c in cells]) if cells else NOT_RUN
    reasons = ident + ['%s %s: %s(분리 판정 %s, 스냅샷 판정 %s) %s' % (
        c['모드'], c['실험'], c['칸 판정'], c['분리 판정'], c['스냅샷 판정'], '; '.join(c['사유']))
        for c in cells if c['칸 판정'] != ESTABLISHED]
    return {'최종 판정': final, '사유': reasons, '식별': {'이미지 id': image_ids, '미충족': ident},
            '칸': cells, '규칙': RULES}


def load_json(path):
    try:
        return json.loads(Path(path).read_text(encoding='utf-8-sig'))
    except (OSError, ValueError):
        return None


def load_mode(directory):
    base = Path(directory)
    summary = load_json(base / 'summary.json')
    if not isinstance(summary, dict):
        return {'summary': None, 'cells': {}}
    cells = {}
    rows = summary.get('experiments')
    for row in rows if isinstance(rows, list) else []:
        exp = row.get('experiment') if isinstance(row, dict) else None
        if isinstance(exp, str) and exp:
            folder = base / exp
            cells[exp] = {'row': row, 'band_state': load_json(folder / 'band_state.json'),
                          'pre': load_json(folder / 'snapshot_pre.json'),
                          'post': load_json(folder / 'snapshot_post.json')}
    return {'summary': summary, 'cells': cells}


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--global', dest='global_dir')
    ap.add_argument('--band', dest='band_dir')
    ap.add_argument('--modes', default=','.join(MODES))
    ap.add_argument('--experiments', default=','.join(EXPERIMENTS))
    ap.add_argument('--out', required=True)
    a = ap.parse_args(argv)
    modes = [x.strip() for x in a.modes.split(',') if x.strip()]
    experiments = [x.strip() for x in a.experiments.split(',') if x.strip()]
    if not modes or any(x not in MODES for x in modes):
        ap.error('모드는 global, band 다: %s' % a.modes)
    dirs = {'global': a.global_dir, 'band': a.band_dir}
    inputs = {x: load_mode(dirs[x]) if dirs[x] else {'summary': None, 'cells': {}} for x in modes}
    result = judge(inputs, modes, experiments)
    result['입력'] = {x: dirs[x] for x in modes}
    Path(a.out).write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding='utf-8')
    print('최종 판정: %s' % result['최종 판정'], flush=True)
    for c in result['칸']:
        print('  %s %s: %s(분리 판정 %s, 스냅샷 판정 %s)' % (c['모드'], c['실험'], c['칸 판정'], c['분리 판정'],
                                                      c['스냅샷 판정']), flush=True)
    return EXIT[result['최종 판정']]


if __name__ == '__main__':
    sys.exit(main())
