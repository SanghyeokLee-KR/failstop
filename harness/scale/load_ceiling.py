"""실효 부하 상한 축을 찾는다. 정본 13.5 한계 19.

문제. 요청 OPS 를 40 에서 200 으로 올렸는데 실효 부하가 안 올랐다. DB CPU 도 초당 체결도
같거나 낮았다. 무엇이 막았는지 판정하지 못했다.

방법. 요청 OPS 를 계단으로 올리면서 계층별 포화도를 동시에 잰다. 한 축만 100 퍼센트에
붙고 나머지가 낮으면 그 축이 상한이다. 아무 축도 안 붙는데 실효 부하가 안 오르면
부하 발생기 자체가 상한이다.

종전 회차와 다른 점은 계측이다. 2026-09-09 에 컨테이너 자원과 데이터베이스 지표를
배선했다. 그전에는 앱이 내는 지표뿐이라 앱 밖에서 막히면 보이지 않았다.

돌리는 법:
  python harness/scale/load_ceiling.py --steps 20,50,100,200,400 --dwell 90
"""
import argparse
import json
import subprocess
import sys
import time
import urllib.parse
import urllib.request

PROM = 'http://localhost:59090'
ADMIN = 'https://localhost:8443'
PKI = 'D:/dev/failstop/server/config/admin-demo'


def q(expr):
    """프로메테우스 순간 질의. 값이 없으면 None 이다. 0 과 구분한다."""
    url = PROM + '/api/v1/query?' + urllib.parse.urlencode({'query': expr})
    try:
        with urllib.request.urlopen(url, timeout=10) as r:
            res = json.load(r)['data']['result']
        return float(res[0]['value'][1]) if res else None
    except Exception:
        return None


def admin_call(method, path, body=None):
    """관리 평면은 mTLS 다. Windows curl 이 Schannel 이라 파이썬으로 직접 붙는다."""
    import http.client
    import os
    import ssl
    cert = os.path.join(os.environ.get('TEMP', '/tmp'), 'op01_full.pem')
    if not os.path.exists(cert):
        subprocess.run(['openssl', 'pkcs12', '-in', PKI + '/op-01.p12', '-passin',
                        'pass:changeit', '-nodes', '-out', cert], check=True, capture_output=True)
    ctx = ssl.create_default_context(cafile=PKI + '/demo-ca.crt')
    ctx.load_cert_chain(cert)
    c = http.client.HTTPSConnection('localhost', 8443, context=ctx, timeout=20)
    h = {'Content-Type': 'application/json'}
    if not hasattr(admin_call, 'tok'):
        c.request('POST', '/admin/v1/session', headers=h)
        admin_call.tok = json.loads(c.getresponse().read())['token']
        c.close()
        c = http.client.HTTPSConnection('localhost', 8443, context=ctx, timeout=20)
    h['Authorization'] = 'Bearer ' + admin_call.tok
    c.request(method, path, body=json.dumps(body) if body else None, headers=h)
    r = c.getresponse()
    out = (r.status, r.read().decode('utf-8', 'replace'))
    c.close()
    return out


# 축마다 포화도를 하나의 수로 낸다. 100 이면 그 축이 꽉 찼다는 뜻이다.
#
# 절대량과 포화도를 함께 낸다. 절대량만 보면 "많다"만 알고 "꽉 찼다"를 모른다.
AXES = {
    '접수/s': 'sum(rate(http_server_requests_seconds_count{uri="/api/v1/orders",method="POST",status="201"}[1m]))',
    '취소/s': 'sum(rate(http_server_requests_seconds_count{uri=~"/api/v1/orders.*",method="DELETE"}[1m]))',
    '체결확정/s': 'sum(rate(exchange_order_latency_seconds_count[1m]))',
    '체결 rate1m': 'max(exchange:trades_per_second:rate1m)',
    '체결/s': 'sum(rate(exchange_trades_total[1m]))',
    '거절/s': 'sum(rate(exchange_admission_rejected_total[1m]))',
    '주문거절/s': 'sum(rate(exchange_orders_rejected_total[1m]))',
    'p95(ms)': 'max(exchange:order_latency:p95_1m) * 1000',
    'p99(ms)': 'max(exchange:order_latency:p99_1m) * 1000',
    '시퀀서 큐': 'max(exchange_sequencer_queue_size)',
    '봇 CPU%': 'max(container_cpu_percent{name="chaos-bots"})',
    'api1 CPU%': 'max(container_cpu_percent{name="chaos-api-1"})',
    'api2 CPU%': 'max(container_cpu_percent{name="chaos-api-2"})',
    'DB CPU%': 'max(container_cpu_percent{name="chaos-pg"})',
    'DB 활성세션': 'sum(pg_stat_activity_count{state="active"})',
    'DB 커밋/s': 'sum(rate(pg_stat_database_xact_commit[1m]))',
    'DB 잠금': 'sum(pg_locks_count)',
    '풀 활성': 'sum(hikaricp_connections_active)',
    '풀 대기': 'sum(hikaricp_connections_pending)',
}


def sample():
    return {k: q(v) for k, v in AXES.items()}


def set_ops(ops):
    st, raw = admin_call('PUT', '/admin/v1/sim/rate', {'target_ops': ops})
    if st not in (200, 202):
        print('  OPS 설정 실패 %d %s' % (st, raw[:160]))
        return False
    return True


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--steps', default='20,50,100,200,400')
    p.add_argument('--dwell', type=int, default=90, help='단계마다 머무는 초. 1분 rate 창보다 커야 한다')
    p.add_argument('--out', default='D:/dev/failstop/harness/evidence/load-ceiling.json')
    a = p.parse_args()

    steps = [int(s) for s in a.steps.split(',')]
    rows = []
    print('단계 %s, 단계당 %d초' % (steps, a.dwell))

    for ops in steps:
        if not set_ops(ops):
            break
        print('\n요청 %d OPS. %d초 대기' % (ops, a.dwell))
        time.sleep(a.dwell)
        s = sample()
        s['_requested'] = ops
        rows.append(s)
        for k in ('주문 처리/s', '체결/s', '거절/s', 'DB CPU%', '봇 CPU%', 'p99(ms)'):
            v = s.get(k)
            print('  %-14s %s' % (k, '없음' if v is None else round(v, 2)))

    with open(a.out, 'w', encoding='utf-8') as f:
        json.dump({'steps': steps, 'dwell': a.dwell, 'rows': rows}, f, ensure_ascii=False, indent=1)

    print('\n' + '=' * 92)
    keys = ['주문 처리/s', '체결/s', '거절/s', 'p99(ms)', '봇 CPU%', 'api1 CPU%', 'DB CPU%', '풀 활성', '시퀀서 큐']
    print('%-9s' % '요청OPS' + ''.join('%12s' % k for k in keys))
    for r in rows:
        line = '%-9d' % r['_requested']
        for k in keys:
            v = r.get(k)
            line += '%12s' % ('-' if v is None else round(v, 1))
        print(line)
    print('\n판정. 실효(주문 처리/s)가 요청 OPS 를 따라 오르지 않는 첫 단계를 찾고,')
    print('그 단계에서 100 에 가까운 축을 본다. 아무 축도 안 붙으면 발생기가 상한이다.')
    print('결과 파일: ' + a.out)


if __name__ == '__main__':
    sys.exit(main())
