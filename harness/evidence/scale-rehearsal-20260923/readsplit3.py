# 조회 분산 실측 3차. 노드 전체 요청 수로는 봇 쓰기 트래픽이 섞여 조회 몫을 읽을 수 없었다.
# uri 라벨로 좁혀 같은 경로의 증가분만 센다.
import re
import sys
import time
import urllib.request

NODES = {'chaos-api-1': 18080, 'chaos-api-2': 28080, 'chaos-api-3': 38080}
LB = 'http://localhost:58080'


def counts(port, uri):
    """그 노드가 그 uri 로 처리한 요청 수."""
    pat = re.compile(r'^http_server_requests_seconds_count\{[^}]*uri="%s"[^}]*\}\s+([0-9.eE+]+)$'
                     % re.escape(uri), re.M)
    try:
        with urllib.request.urlopen('http://localhost:%d/actuator/prometheus' % port, timeout=4) as r:
            body = r.read().decode('utf-8', 'replace')
    except Exception:
        return None
    return round(sum(float(m) for m in pat.findall(body)), 1)


def snap(uri):
    return {n: counts(p, uri) for n, p in NODES.items()}


def hit(path, n):
    ok = 0
    for _ in range(n):
        try:
            with urllib.request.urlopen(LB + path, timeout=5) as r:
                ok += 1 if r.status == 200 else 0
        except Exception:
            pass
    return ok


def window(label, uri, path, n):
    b = snap(uri)
    t0 = time.time()
    ok = hit(path, n)
    el = round(time.time() - t0, 1)
    a = snap(uri)
    d = {k: (None if b[k] is None or a[k] is None else round(a[k] - b[k], 1)) for k in NODES}
    got = sum(v for v in d.values() if v)
    print('%s  200 %d/%d, %.1f초, 노드 합 %s' % (label, ok, n, el, got))
    for k in NODES:
        print('   %-13s %s' % (k, '미수집' if d[k] is None else d[k]))


window('조회 /api/v1/markets', '/api/v1/markets', '/api/v1/markets', 90)
window('조회 /api/v1/trades', '/api/v1/trades', '/api/v1/trades?market=KRW-BTC&count=3', 90)
window('쓰기 경로 /api/v1/orderbook', '/api/v1/orderbook', '/api/v1/orderbook?market=KRW-BTC', 60)
