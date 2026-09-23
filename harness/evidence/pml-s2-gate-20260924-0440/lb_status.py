"""chaos-lb 접근 로그에서 클라이언트가 받은 상태 코드를 센다(S2 관문 6-4 A/B 의 '5xx 증가 없음' 보조 근거).

접근 로그 원문은 요청 주소와 컨테이너 주소를 담아 저장소 밖에 두고, 이 스크립트가 낸 집계만 증거로 남긴다.
창은 roundtrip_probe 가 적은 k6 주행 창(DB 시각, UTC)이다. 사용자 에이전트로 k6 와 봇을 가른다.

사용: python lb_status.py --log <lb-A2.log> --probe <A2.json> --label A2 --out <lb_status_A2.json>
"""
import argparse
import datetime as dt
import json
import re
from collections import Counter

LINE = re.compile(r'^\S+ - \S+ \[(?P<ts>[^\]]+)\] "(?P<method>[A-Z]+) (?P<path>\S+) [^"]*" (?P<status>\d{3}) \S+ "[^"]*" "(?P<ua>[^"]*)"')


def parse_db(ts):
    ts = ts.strip().replace(' ', 'T')
    if ts.endswith('+00'):
        ts = ts[:-3] + '+00:00'
    return dt.datetime.fromisoformat(ts)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--log', required=True)
    ap.add_argument('--probe', required=True)
    ap.add_argument('--label', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    probe = json.load(open(a.probe, encoding='utf-8'))
    win = probe['접수(DB, 마켓별)']['창']
    t0, t1 = parse_db(win['from']), parse_db(win['to'])
    counts = {'k6': Counter(), 'bots': Counter(), 'other': Counter()}
    orders = {'k6': Counter(), 'bots': Counter(), 'other': Counter()}
    parsed = skipped = 0
    for line in open(a.log, encoding='utf-8', errors='replace'):
        m = LINE.match(line)
        if not m:
            skipped += 1
            continue
        t = dt.datetime.strptime(m['ts'], '%d/%b/%Y:%H:%M:%S %z')
        # 접근 로그는 초 단위다. 창 양 끝 초를 포함한다.
        if t < t0.replace(microsecond=0) or t > t1:
            continue
        parsed += 1
        who = 'k6' if 'k6' in m['ua'].lower() else ('bots' if 'java-http-client' in m['ua'].lower() else 'other')
        counts[who][m['status']] += 1
        if m['method'] == 'POST' and m['path'].split('?')[0] == '/api/v1/orders':
            orders[who][m['status']] += 1
    fivexx = {w: sum(v for s, v in c.items() if s.startswith('5')) for w, c in counts.items()}
    out = {'label': a.label, 'window': win, 'lines_in_window': parsed, 'unparsed_lines': skipped,
           'status_all': {w: dict(sorted(c.items())) for w, c in counts.items()},
           'status_post_orders': {w: dict(sorted(c.items())) for w, c in orders.items()},
           'client_5xx': fivexx}
    json.dump(out, open(a.out, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
    print(json.dumps({'label': a.label, 'client_5xx': fivexx, 'post_orders': out['status_post_orders']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
