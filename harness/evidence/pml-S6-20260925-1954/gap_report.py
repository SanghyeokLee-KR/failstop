"""S6 관문 주행의 WebSocket 결번 관찰(이 폴더 README '결번 관찰'). 판정에 쓰지 않는 기록 도구다.

실험 폴더의 watch.json(k6 관찰자 상태를 노드별로 모은 것)에서 호가·티커 결번을 모두 꺼내, 결번마다 그 채널의 첫 수신 시각과의 거리,
같은 (마켓, 채널, epoch, 빠진 seq) 를 다른 관찰자도 받았는지를 적는다. 두 노드 관찰자가 모두 같은 seq 를 받지 못했으면 게시 쪽,
팔로워 관찰자만 못 받았으면 중계 쪽, 한 관찰자만이면 그 WS 세션 쪽으로 가른다(분류는 추정의 출발점이지 원인 확정이 아니다).

사용: python harness/evidence/pml-S6-20260925-1954/gap_report.py <실험 폴더> [<실험 폴더> ...]
"""
import json
import sys
from pathlib import Path


def in_ranges(ranges, seq):
    return any(a <= seq <= b for a, b in ranges or [])


def node_key(url):
    if 'chaos-lb' in url:
        return 'lb'
    if 'chaos-api-1' in url:
        return 'api-1'
    if 'chaos-api-2' in url:
        return 'api-2'
    return url


def report(ev):
    p = Path(ev) / 'watch.json'
    if not p.exists():
        return {'dir': str(ev), 'error': 'watch.json 없음'}
    watch = json.loads(p.read_text(encoding='utf-8'))
    states = {node_key(u): x['latest'] for u, x in watch.items()}
    out = {'dir': str(ev), 'nodes': {}, 'gaps': []}
    for node, st in states.items():
        summary = {'opens': st.get('opens'), 'closes': len(st.get('closes') or []), 'handshakeErrors': st.get('handshakeErrors'),
                   'markets': {}}
        for market, m in (st.get('markets') or {}).items():
            summary['markets'][market] = {k: {'realtime': (m.get(k) or {}).get('realtime'), 'gapCount': (m.get(k) or {}).get('gapCount'),
                                              'stale': (m.get(k) or {}).get('stale'), 'zero': (m.get(k) or {}).get('zero'),
                                              'epochs': (m.get(k) or {}).get('epochs')}
                                          for k in ('orderbook', 'ticker')}
            for kind in ('orderbook', 'ticker'):
                ch = m.get(kind) or {}
                for g in ch.get('gaps') or []:
                    missing = list(range(g['from'] + 1, g['to']))
                    row = {'node': node, 'market': market, 'kind': kind, 'at': g['at'], 'epoch': g['epoch'], 'from': g['from'],
                           'to': g['to'], 'missing': len(missing),
                           'ms_after_first': (g['at'] - ch['firstAt']) if ch.get('firstAt') else None}
                    if kind == 'orderbook':
                        seen = {}
                        for other, ost in states.items():
                            if other == node:
                                continue
                            rng = ((((ost.get('markets') or {}).get(market) or {}).get('orderbook') or {}).get('ranges') or {}).get(str(g['epoch']))
                            seen[other] = sum(1 for s in missing if in_ranges(rng, s))
                        row['others_received_of_missing'] = seen
                    out['gaps'].append(row)
        out['nodes'][node] = summary
    return out


def main(argv):
    res = [report(ev) for ev in argv[1:]]
    print(json.dumps(res, ensure_ascii=False, indent=1))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
