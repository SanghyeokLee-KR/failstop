"""k6 원 로그에서 주행별 접수 요약을 뽑아 이 디렉터리의 k6_runs_summary.json 을 만든다.

원 로그는 ACK 줄마다 부하 계정 접근 키 식별자를 담아 저장소 밖에 둔다. 로그 원문 줄과 그 식별자를 출력하거나 기록하지 않는다.

돌리는 법:
  python k6_runs_summary.py <k6 원 로그 디렉터리>
"""
import collections
import datetime
import hashlib
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
TIME = re.compile(r'^time="([^"]+)" level=(\w+)')
# ACK JSON 의 coid 필드 값만 읽는다. 뒤따르는 oid(UUID)까지 먹으면 식별자가 갈라진다.
COID = re.compile(r'\\"coid\\":\\"([^\\"]*)\\"')
RUN_ID = re.compile(r'^k6-(.+)-\d+-\d+$')


def kst(t):
    return (datetime.datetime.strptime(t, '%Y-%m-%dT%H:%M:%SZ') + datetime.timedelta(hours=9)).strftime('%H:%M:%S')


def summarize(path, journal):
    ack = no_prefix = non_ack = 0
    coids = set()
    rids = collections.Counter()
    levels = collections.Counter()
    first = last = None
    h = hashlib.sha256()
    with open(path, 'rb') as fh:
        for raw in fh:
            h.update(raw)
            ln = raw.decode('utf-8', 'replace')
            m = TIME.match(ln)
            if m:
                levels[m.group(2)] += 1
                first = first or m.group(1)
                last = m.group(1)
            else:
                no_prefix += 1
            if 'msg="ACK|' not in ln:
                non_ack += 1
                continue
            ack += 1
            c = COID.search(ln)
            if c:
                coids.add(c.group(1))
                r = RUN_ID.match(c.group(1))
                if r:
                    rids[r.group(1)] += 1
    rid = rids.most_common(1)[0][0] if rids else None
    jn = journal.get(rid, {}).get('journal_new_total')
    return {
        '파일': path.name,
        '크기(바이트)': path.stat().st_size,
        'sha256': h.hexdigest(),
        '주행 식별자': rid,
        '주행 식별자 종류 수': len(rids),
        '주행 식별자 일치 줄': sum(rids.values()),
        'ACK 줄': ack,
        'client_order_id 고유 수': len(coids),
        'ACK 외 줄': non_ack,
        '시각 접두 없는 줄': no_prefix,
        '로그 수준별 줄': dict(levels),
        'warn 줄': levels.get('warn', 0),
        'error 줄': levels.get('error', 0),
        '첫 줄 시각(KST)': kst(first) if first else None,
        '끝 줄 시각(KST)': kst(last) if last else None,
        '저널 NEW 합(per_market)': jn,
        'ACK 줄 == 저널 NEW 합': (ack == jn) if jn is not None else None,
    }


def main():
    logs = Path(sys.argv[1])
    journal = {}
    for f in sorted(HERE.glob('per_market_B0-*.json')):
        if 'invalid' not in f.name:
            journal.update(json.load(open(f, encoding='utf-8')))
    runs = [summarize(f, journal) for f in sorted(logs.glob('k6-*.log'))]
    out = {
        '원자료': 'k6-rt-B0-*.log, k6-k6ceil-*.log 와 per_market_B0-240.json, per_market_B0-600.json, per_market_B0-ceiling.json',
        '생성': 'python k6_runs_summary.py <k6 원 로그 디렉터리>',
        '셈법': ('ACK 줄은 msg="ACK| 를 담은 줄 수. client_order_id 고유 수는 ACK 줄 JSON 의 coid 필드 값 집합 크기. '
                 '주행 식별자는 coid 값 k6-<식별자>-<vu>-<iter> 의 <식별자> 이고, 종류 수는 한 로그 안에서 나온 서로 다른 '
                 '<식별자> 수다. 주행 식별자 일치 줄이 ACK 줄과 같고 종류 수가 1 이면 그 로그의 ACK 줄이 한 주행 것뿐이다. 시각은 k6 time 필드(UTC)에 9시간을 더한 값. '
                 '로그 수준은 level 필드. 원 로그의 접근 키 식별자와 주문 식별자는 옮기지 않는다. '
                 'sha256 은 원 로그 바이트 그대로의 해시다.'),
        '주행': runs,
    }
    with open(HERE / 'k6_runs_summary.json', 'w', encoding='utf-8', newline='\n') as fh:
        json.dump(out, fh, ensure_ascii=False, indent=1)
        fh.write('\n')
    for r in runs:
        print(r['파일'], r['주행 식별자'], r['주행 식별자 종류 수'], r['ACK 줄'], r['client_order_id 고유 수'],
              r['ACK 줄 == 저널 NEW 합'])


if __name__ == '__main__':
    main()
