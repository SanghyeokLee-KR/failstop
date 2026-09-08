#!/usr/bin/env python3
"""analyze.py. 복제 실험 표본을 창별로 접는다.

`repl_sampler.py` 가 남긴 JSON Lines 와 사건 표시 파일을 받아 두 가지를 낸다.
사건 사이 구간의 평균과 사건 직후의 초 단위 전개다.

창을 사건으로 가르는 이유가 있다. 한 부하 안에서 정상과 장애와 복구를 이어 재므로 전체
평균은 셋을 섞는다. 사건 시각으로 잘라야 각 구간의 값이 나온다.

앞 몇 초를 버린다. 부하 도구가 램프업하는 동안의 값이 정상 구간에 섞이면 그 구간이
실제보다 낮게 나온다.

사용:
  python harness/replication/analyze.py --samples run.jsonl --marks marks.txt
  python harness/replication/analyze.py --samples run.jsonl --marks marks.txt --around T0_KILL
  python harness/replication/analyze.py --samples run.jsonl --marks marks.txt       --window 04:03:50,04:05:05,2-A --window 04:05:07,04:06:30,2-B

`--window` 를 쓰는 이유가 있다. 사건 사이로 자른 구간은 마지막 사건과 부하 종료 사이에
부하가 이미 멈춘 구간을 포함한다. 부하 도구는 235초를 돌고 표시는 251초에 찍히므로 그
17초가 평균을 끌어내린다. 회복 구간의 값을 그 상태로 읽으면 회복이 덜 된 것처럼 보인다.
비교할 구간은 손으로 자르지 말고 이 인자로 남겨야 회차마다 같은 정의가 된다.
"""

import argparse
import json
import io


def load(path):
    rows = []
    for line in io.open(path, encoding='utf-8'):
        line = line.strip()
        if not line:
            continue
        try:
            rows.append(json.loads(line))
        except ValueError:
            continue
    return rows


def load_marks(path):
    out = []
    for line in io.open(path, encoding='utf-8'):
        p = line.split()
        if len(p) == 2:
            out.append((p[0], p[1]))
    return out


def secs(hms):
    h, m, s = hms.split(':')
    return int(h) * 3600 + int(m) * 60 + float(s)


def orders(r, code):
    return (r.get('orders_per_s') or {}).get(code, 0.0)


def ok(r):
    """접수 성공. 201 이 접수이고 200 은 멱등 재생이다."""
    return orders(r, '201') + orders(r, '200')


def rejected(r):
    """수용 정원 거절. 상태코드로 세지 않는다.

    거절은 컨트롤러 앞 필터에서 나가므로 요청이 주문 경로 라벨을 얻지 못하고
    `uri="UNKNOWN"` 으로 계상된다. 주문 URI 로만 세면 거절이 0 으로 보이고, 그러면
    "요청이 그냥 매달린다" 는 잘못된 결론이 난다. 실제로 이 실험 1 회차에서 그렇게 읽었다.
    `exchange_admission_rejected_total{scope="order"}` 이 원천이다.
    """
    v = r.get('adm_reject_per_s')
    return v if v is not None else orders(r, '429')


def server_error(r):
    o = r.get('orders_per_s') or {}
    return sum(v for k, v in o.items() if k.startswith('5'))


SYNC_LETTER = {'sync': 's', 'quorum': 'q', 'potential': 'p', 'async': 'a'}


def sync_tag(r):
    """복제 세션들의 sync_state 를 한 글자씩 잇는다.

    standby 가 둘이 되면 대수만으로는 부족하다. `ANY 1` 정족수에서는 둘 다 quorum 이고
    단일 동기에서는 하나가 sync 다. 어느 쪽으로 잡혔는지가 곧 이 실험의 조건이므로
    초 단위 표에 함께 실어야 조건이 흔들린 회차를 나중에 가려낼 수 있다.
    """
    rep = r.get('repl') or []
    if not rep:
        return '-'
    return ''.join(SYNC_LETTER.get(x.get('sync', ''), '?') for x in sorted(
        rep, key=lambda x: x.get('app', '')))


def sync_states(sel):
    """구간 안에서 관측된 sync 조합을 빈도순으로 적는다. 조건이 흔들리면 여기서 드러난다."""
    cnt = {}
    for r in sel:
        t = sync_tag(r)
        cnt[t] = cnt.get(t, 0) + 1
    return ' '.join('%s x%d' % (k, v) for k, v in
                    sorted(cnt.items(), key=lambda kv: -kv[1]))


def avg(vals):
    vals = [v for v in vals if v is not None]
    return sum(vals) / len(vals) if vals else None


def fmt(v, nd=1):
    return '-' if v is None else ('%.*f' % (nd, v))


def window(rows, t0, t1, skip=8):
    sel = [r for r in rows if t0 <= secs(r['ts']) < t1]
    sel = sel[skip:] if len(sel) > skip * 2 else sel
    if not sel:
        return None
    return {
        'n': len(sel),
        'orders_s': avg([ok(r) for r in sel]),
        'rejected_s': avg([rejected(r) for r in sel]),
        'err_s': avg([server_error(r) for r in sel]),
        'mean_ms': avg([r.get('order_mean_ms') for r in sel]),
        'max_ms': max([r.get('order_max_ms') or 0 for r in sel]) or None,
        'journal_ms': avg([r.get('journal_mean_ms') for r in sel]),
        'hikari_active': avg([(r.get('hikari') or {}).get('active') for r in sel]),
        'hikari_pending': avg([(r.get('hikari') or {}).get('pending') for r in sel]),
        'adm_inflight': avg([(r.get('adm') or {}).get('in_flight') for r in sel]),
        'adm_reject_s': avg([r.get('adm_reject_per_s') for r in sel]),
        'repl_sessions': avg([float(r.get('repl_sessions', 0)) for r in sel]),
        'flush_min_ms': avg([min(x.get('flush_lag') or 0 for x in r['repl']) * 1000
                             for r in sel if r.get('repl')]),
        'flush_max_ms': avg([max(x.get('flush_lag') or 0 for x in r['repl']) * 1000
                             for r in sel if r.get('repl')]),
        'sync_states': sync_states(sel),
        'app_up_pct': 100.0 * sum(1 for r in sel if r.get('app_up')) / len(sel),
        # 리스 축(2026-09-08). 갱신이 PostgreSQL 밖으로 나가면 이 세 값이 '리더십이 살아 있었나' 의
        # 유일한 원천이다. ready_pct 는 이 실험의 성공 판정 그 자체다.
        'lease_ok_s': avg([r.get('lease_ok_per_s') for r in sel]),
        'lease_rej_s': avg([r.get('lease_rejected_per_s') for r in sel]),
        'lease_err_s': avg([r.get('lease_error_per_s') for r in sel]),
        'lease_age': avg([(r.get('lease') or {}).get('age') for r in sel]),
        'lease_token': avg([(r.get('lease') or {}).get('token') for r in sel]),
        'is_leader': avg([(r.get('lease') or {}).get('is_leader') for r in sel]),
        'ready_pct': 100.0 * sum(1 for r in sel if r.get('readiness') == 200) / len(sel),
    }


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--samples', required=True)
    p.add_argument('--marks', required=True)
    p.add_argument('--around', help='이 사건 전후를 초 단위로 펼친다')
    p.add_argument('--before', type=int, default=5)
    p.add_argument('--after', type=int, default=25)
    p.add_argument('--window', action='append', default=[],
                   metavar='시작,끝,이름', help='HH:MM:SS,HH:MM:SS,이름. 여러 번 줄 수 있다')
    a = p.parse_args()

    rows = load(a.samples)
    marks = load_marks(a.marks)
    if not rows or not marks:
        print('표본이나 사건 표시가 비었다')
        return

    print('표본 %d개, 사건 %d개' % (len(rows), len(marks)))
    print()
    print('=== 사건 ===')
    base = secs(marks[0][1])
    for name, t in marks:
        print('  %-22s %s  (+%.0f초)' % (name, t, secs(t) - base))

    print()
    print('=== 구간별 평균 ===')
    hdr = ('구간', '표본', '접수/s', '거절/s', '5xx/s', '평균ms', '최대ms',
           '저널ms', 'Hikari활성', 'Hikari대기', '복제세션', 'flush최소ms', 'flush최대ms')
    print('%-22s %5s %8s %8s %7s %8s %9s %8s %10s %10s %9s %11s %11s' % hdr)
    print('-' * 142)
    for i in range(len(marks) - 1):
        t0, t1 = secs(marks[i][1]), secs(marks[i + 1][1])
        w = window(rows, t0, t1)
        if not w:
            continue
        print('%-22s %5d %8s %8s %7s %8s %9s %8s %10s %10s %9s %11s %11s' % (
            marks[i][0], w['n'], fmt(w['orders_s']), fmt(w['rejected_s']), fmt(w['err_s']),
            fmt(w['mean_ms']), fmt(w['max_ms']), fmt(w['journal_ms']),
            fmt(w['hikari_active'], 2), fmt(w['hikari_pending'], 2),
            fmt(w['repl_sessions'], 2), fmt(w['flush_min_ms'], 2),
            fmt(w['flush_max_ms'], 2)))
        print('%-22s   sync 조합: %s' % ('', w['sync_states']))
        print('%-22s   리스: 갱신 %s/s, 거부 %s/s, 오류 %s/s, 마지막갱신 %s초 전, 토큰 %s, 리더 %s, readiness200 %s%%' % (
            '', fmt(w['lease_ok_s'], 2), fmt(w['lease_rej_s'], 2), fmt(w['lease_err_s'], 2),
            fmt(w['lease_age'], 1), fmt(w['lease_token'], 0), fmt(w['is_leader'], 2),
            fmt(w['ready_pct'], 0)))

    if a.window:
        print()
        print('=== 지정 구간 ===')
        print('%-22s %5s %8s %8s %7s %8s %9s %8s %10s %10s %9s %11s %11s' % hdr)
        print('-' * 142)
        for spec in a.window:
            parts = spec.split(',')
            if len(parts) != 3:
                print('구간 형식이 아니다: %s' % spec)
                continue
            t0, t1, label = secs(parts[0]), secs(parts[1]), parts[2]
            w = window(rows, t0, t1, skip=0)
            if not w:
                print('%-22s 표본 없음' % label)
                continue
            print('%-22s %5d %8s %8s %7s %8s %9s %8s %10s %10s %9s %11s %11s' % (
                label, w['n'], fmt(w['orders_s']), fmt(w['rejected_s']), fmt(w['err_s']),
                fmt(w['mean_ms']), fmt(w['max_ms']), fmt(w['journal_ms']),
                fmt(w['hikari_active'], 2), fmt(w['hikari_pending'], 2),
                fmt(w['repl_sessions'], 2), fmt(w['flush_min_ms'], 2),
                fmt(w['flush_max_ms'], 2)))
            print('%-22s   sync 조합: %s' % ('', w['sync_states']))
            print('%-22s   리스: 갱신 %s/s, 거부 %s/s, 오류 %s/s, 마지막갱신 %s초 전, 토큰 %s, 리더 %s, readiness200 %s%%' % (
                '', fmt(w['lease_ok_s'], 2), fmt(w['lease_rej_s'], 2), fmt(w['lease_err_s'], 2),
                fmt(w['lease_age'], 1), fmt(w['lease_token'], 0), fmt(w['is_leader'], 2),
                fmt(w['ready_pct'], 0)))

    if a.around:
        target = next((t for n, t in marks if n == a.around), None)
        if target is None:
            print('\n사건 %s 없음' % a.around)
            return
        c = secs(target)
        print()
        print('=== %s 전후 초 단위 ===' % a.around)
        print('%7s %8s %8s %7s %9s %9s %8s %8s %8s %6s %6s %6s %10s %8s' % (
            '경과', '접수/s', '거절/s', '5xx/s', '평균ms', '최대ms',
            '정원', 'Hik활성', 'Hik대기', '복제', 'sync', 'ready', '리스갱신/s', '리스나이'))
        print('-' * 132)
        for r in rows:
            d = secs(r['ts']) - c
            if -a.before <= d <= a.after:
                h = r.get('hikari') or {}
                adm = r.get('adm') or {}
                cap = '%s/%s' % (fmt(adm.get('in_flight'), 0), fmt(adm.get('limit'), 0))
                lease = r.get('lease') or {}
                print('%+7.0f %8s %8s %7s %9s %9s %8s %8s %8s %6d %6s %6s %10s %8s' % (
                    d, fmt(ok(r)), fmt(rejected(r)), fmt(server_error(r)),
                    fmt(r.get('order_mean_ms')), fmt(r.get('order_max_ms')), cap,
                    fmt(h.get('active'), 0), fmt(h.get('pending'), 0),
                    r.get('repl_sessions', 0), sync_tag(r),
                    fmt(r.get('readiness'), 0), fmt(r.get('lease_ok_per_s'), 2),
                    fmt(lease.get('age'), 1)))


if __name__ == '__main__':
    main()
