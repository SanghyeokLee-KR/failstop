"""이 폴더의 실험 결과 요약(기록 도구). relay_axes.json·s3_common.json·watch.json 에서 판정 표에 옮길 값을 뽑는다.
사용: python harness/evidence/pml-S7-20260926-0300/summarize.py <실험 폴더>
"""
import datetime as dt
import json
import sys
from pathlib import Path


def t(ms):
    return dt.datetime.fromtimestamp(ms / 1000, dt.timezone.utc).strftime('%H:%M:%S.%f')[:-3]


def main(ev):
    ev = Path(ev)
    for name in ('relay_axes.json', 's3_common.json'):
        p = ev / name
        if p.exists():
            r = json.loads(p.read_text(encoding='utf-8'))
            print(name, 'ok=', r.get('ok'), 'failed=', [k for k, v in (r.get('axes') or {}).items() if not v])
            if name == 'relay_axes.json':
                for k, v in (r.get('facts') or {}).items():
                    print('  ', k, json.dumps(v, ensure_ascii=False)[:300])
    p = ev / 'watch.json'
    if p.exists():
        w = json.loads(p.read_text(encoding='utf-8'))
        for u, x in w.items():
            st = x['latest']
            print(u, 'opens', st['opens'], 'connects', st['connects'], 'hsErr', st['handshakeErrors'], 'stompErr', st['stompErrors'],
                  'closes', [(t(c['at']), c['code']) for c in st['closes']][:6])
            for m, mm in st['markets'].items():
                print('   ', m, 'ob', mm['orderbook']['realtime'], 'gap', mm['orderbook']['gapCount'], 'stale', mm['orderbook']['stale'],
                      'ep', mm['orderbook']['epochs'], '| tk', mm['ticker']['realtime'], 'gap', mm['ticker']['gapCount'],
                      '| tr', mm['trade']['messages'])


if __name__ == '__main__':
    for a in sys.argv[1:]:
        print('==', a)
        main(a)

