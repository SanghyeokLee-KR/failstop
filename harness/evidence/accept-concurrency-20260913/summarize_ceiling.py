"""사다리 구간별 표본 요약. 구간 창은 k6 로그 파일의 생성 시각에서 수정 시각까지다.

프로메테우스 표본은 1분 rate 창이 주행으로 찬 뒤(시작 65초 이후)만 쓴다. k6_ceiling.measure 와 같은 기준이다.

돌리는 법:
  python summarize_ceiling.py
"""
import csv
import datetime
import json
import os
import statistics
from pathlib import Path

HERE = Path(__file__).resolve().parent
RUNGS = (120, 240, 360, 480, 600)


def secs(hms):
    h, m, s = (int(x) for x in hms.split(':'))
    return h * 3600 + m * 60 + s


def local_secs(ts):
    t = datetime.datetime.fromtimestamp(ts)
    return t.hour * 3600 + t.minute * 60 + t.second


def main():
    pg = list(csv.DictReader(open(HERE / 'pg_lock_samples_B0-ceiling.csv', encoding='utf-8')))
    pr = list(csv.DictReader(open(HERE / 'lock_samples_B0-ceiling.csv', encoding='utf-8')))
    out = []
    for r in RUNGS:
        st = os.stat(HERE / ('k6-k6ceil-%d.log' % r))
        start, end = local_secs(st.st_ctime), local_secs(st.st_mtime)
        row = {'요청': r, '창': [datetime.datetime.fromtimestamp(st.st_ctime).strftime('%H:%M:%S'),
                                datetime.datetime.fromtimestamp(st.st_mtime).strftime('%H:%M:%S')]}
        g = [x for x in pg if x['ok'] == '1' and start <= secs(x['ts']) <= end]
        row['pg 표본'] = len(g)
        for c in ('prologue_active', 'prologue_lock_wait', 'advisory_wait', 'counter_row_wait'):
            v = [int(x[c]) for x in g]
            row[c] = {'평균': round(statistics.mean(v), 3) if v else None, '최대': max(v) if v else None,
                      '0 초과 표본': sum(1 for y in v if y > 0)}
        p = [x for x in pr if start + 65 <= secs(x['ts']) <= end]
        row['prom 표본'] = len(p)
        for c in ('hold_occ', 'wait_occ', 'submit_s', 'commit_conc', 'inflight_hold_occ', 'catchup_s',
                  'apply_s', 'sink_occ', 'append_pending', 'accepts_s', 'reject_s'):
            v = [float(x[c]) for x in p if x[c] != '']
            row[c] = [round(y, 4) for y in v]
        row['catchup 비'] = [round(float(x['catchup_s']) / float(x['apply_s']), 4)
                            for x in p if x['catchup_s'] and x['apply_s'] and float(x['apply_s']) > 0]
        row['정산 1회(ms)'] = [round(float(x['sink_occ']) / float(x['apply_s']) * 1e3, 3)
                             for x in p if x['sink_occ'] and x['apply_s'] and float(x['apply_s']) > 0]
        out.append(row)
    print(json.dumps(out, ensure_ascii=False, indent=1))


if __name__ == '__main__':
    main()
