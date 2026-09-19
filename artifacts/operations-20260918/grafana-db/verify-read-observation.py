"""Fixed local read probe: validate observed HTTP route labels, not maximum capacity."""
import concurrent.futures
from collections import Counter
import json
from pathlib import Path
import time
from datetime import datetime, timezone
import urllib.error
import urllib.request

BASE = 'http://127.0.0.1:58180'
OUT = Path(__file__).with_name('read-observation.json')
ROUTES = [('orderbook','/api/v1/orderbook?market=KRW-BTC'),
          ('trades','/api/v1/trades?market=KRW-BTC&count=100'),
          ('candles','/api/v1/candles/1m?market=KRW-BTC&count=100')]

def metrics():
    with urllib.request.urlopen(BASE + '/actuator/prometheus', timeout=5) as response:
        lines = response.read().decode().splitlines()
    return [line for line in lines if line.startswith('http_server_requests_seconds_count{')]

def get(path):
    try:
        with urllib.request.urlopen(BASE + path, timeout=3) as response:
            body = json.load(response)
            if body.get('schema_version') != '1.0':
                return 'invalid_schema'
            return response.status
    except urllib.error.HTTPError as error:
        status = error.code
        error.close()
        return status

if OUT.exists():
    raise SystemExit('Existing evidence retained; choose a new output directory.')
evidence = {'started_at': datetime.now(timezone.utc).isoformat(),
            'purpose':'HTTP 429 route-label and SQL collection verification; not a capacity benchmark',
            'target_rps':60, 'seconds_per_route':10, 'workers':8,
            'writes':0, 'rate_limit_changed':False, 'before':metrics(), 'routes':[]}
try:
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        for name, path in ROUTES:
            start = time.monotonic()
            futures = []
            for i in range(600):
                due = start + i / 60
                time.sleep(max(0, due - time.monotonic()))
                futures.append(pool.submit(get, path))
            counts = Counter(f.result() for f in futures)
            evidence['routes'].append({'name':name, 'responses':dict(counts),
                                       'elapsed_seconds':time.monotonic()-start})
            print(name, dict(counts), flush=True)
            if any(code not in (200,429) for code in counts):
                raise RuntimeError('Unexpected response; stopped remaining probe.')
finally:
    evidence['after'] = metrics()
    evidence['finished_at'] = datetime.now(timezone.utc).isoformat()
    OUT.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
