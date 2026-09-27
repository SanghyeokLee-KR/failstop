"""DB 장애 회차의 쓰기 중단 구간 두 가지를 원자료에서 뽑는다(harness/scripts/demo-db-failover.ps1 이 부른다).

  write_outage  관측기(patroni_observe.py) 의 인증 없는 주문 POST 표본. 401 은 쓰기 게이트를 지나 인증에서 거절됐다는 뜻이라
                거래가 쓰기를 받는 상태(ACTIVE)다. 423(거래 일시 중단)·503(DB 불통)·502(LB)·0(연결 실패)은 쓰기를 받지 않는다.
                T0-1초 뒤 첫 '401 아님' 표본부터, 그 뒤 끝까지 401 만 이어지는 첫 표본까지를 중단 구간으로 본다. 끝까지 401 로
                돌아오지 않으면 back 이 없다(재개 미관찰).
  ack_gap       DB 수준 ACK 기록기(ack_recorder.py)의 확인된 쓰기. T0-5초 뒤 확인된 쓰기의 끝 시각을 늘어놓았을 때 가장 긴 빈틈이다.
                T0 는 docker kill 을 보내기 직전 시각이라 그 뒤 몇백 ms 동안 확인이 더 올 수 있다. 그래서 'T0 뒤 첫 확인'이 아니라
                가장 긴 빈틈을 쓰기 공백으로 본다(2026-09-27 첫 회차에서 T0 뒤 첫 확인까지 0.003초로 잘못 잰 뒤 바꿨다).

    python harness/scale/failover_gaps.py --observe <observe.jsonl> --ackrec <ackrec.jsonl> --t0 <epoch 초>
결과는 JSON 한 줄이다. 파일이 없으면 그 항목은 null 이다.

시험:
  python -m unittest discover -s harness/scale -p "test_failover_gaps.py"
"""
import argparse
import json
import os
import sys

WRITE_OK = 401


def load_jsonl(path):
    if not path or not os.path.exists(path):
        return None
    out = []
    with open(path, encoding='utf-8') as fh:
        for line in fh:
            line = line.strip()
            if line:
                out.append(json.loads(line))
    return out


def write_outage(samples, t0):
    writes = sorted((s for s in samples if s.get('kind') == 'write' and s.get('t', 0) >= t0 - 1), key=lambda s: s['t'])
    first_bad = next((s for s in writes if s.get('code') != WRITE_OK), None)
    if first_bad is None:
        return {'first_bad': None, 'back': None, 'outage_s': 0.0, 'codes': {}, 'samples': len(writes)}
    back = None
    for s in reversed(writes):
        if s.get('code') != WRITE_OK:
            break
        back = s['t']
    if back is not None and back < first_bad['t']:
        back = None
    codes = {}
    for s in writes:
        if s['t'] >= first_bad['t'] and (back is None or s['t'] < back):
            key = str(s.get('code'))
            codes[key] = codes.get(key, 0) + 1
    outage = None if back is None else round(back - first_bad['t'], 3)
    return {'first_bad': first_bad['t'], 'back': back, 'outage_s': outage, 'codes': codes, 'samples': len(writes)}


def ack_gap(records, t0, lead_s=5.0):
    acked = sorted(r['t_end'] for r in records if r.get('type') == 'attempt' and r.get('result') == 'acked')
    window = [t for t in acked if t >= t0 - lead_s]
    best = None
    for a, b in zip(window, window[1:]):
        if best is None or b - a > best[1] - best[0]:
            best = (a, b)
    if best is None or not any(t > t0 for t in window):
        # T0 뒤 확인이 하나도 없으면 공백이 끝나지 않았다(재개 미관찰).
        return {'gap_from': window[-1] if window else None, 'gap_to': None, 'gap_s': None, 'acked': len(acked)}
    return {'gap_from': best[0], 'gap_to': best[1], 'gap_s': round(best[1] - best[0], 3), 'acked': len(acked)}


def main(argv=None):
    p = argparse.ArgumentParser(description='DB 장애 회차의 쓰기 중단 구간')
    p.add_argument('--observe')
    p.add_argument('--ackrec')
    p.add_argument('--t0', type=float, required=True)
    a = p.parse_args(argv)
    observe = load_jsonl(a.observe)
    records = load_jsonl(a.ackrec)
    out = {'write_outage': None if observe is None else write_outage(observe, a.t0),
           'ack_gap': None if records is None else ack_gap(records, a.t0)}
    print(json.dumps(out, ensure_ascii=False))
    return 0


if __name__ == '__main__':
    sys.exit(main())
