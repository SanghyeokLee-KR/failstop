"""수용 정원의 최적값을 찾는다. 정본 13.5 한계 26.

배경. 정원 12 는 접수를 초당 17.6 으로 묶고 200 은 접수를 68.6 으로 열되 체결을 2.31 로
무너뜨린다(admission-real-20260909). 체결을 최대로 하는 값이 그 사이에 있다.

최대화 대상은 체결이지 접수가 아니다. 접수는 받았다는 뜻일 뿐이고 거래소가 하는 일은
체결이다. 접수만 늘리면 큐에 쌓이고 지연만 나빠진다.

전제 확인. 설정이 실제로 적용됐는지 매 칸에서 지표로 대조한다. 종전 격자가 그 확인 없이
돌아 네 칸을 같은 설정으로 재고 잘못된 판정을 냈다(한계 31).

주의. 정원을 벌리려면 커넥션 풀도 함께 키워야 한다. 앱이 정원 합이 풀을 잠식하면 기동을
막는다. 그리고 데이터베이스의 최대 접속 수가 두 노드의 풀 합보다 커야 한다. 이 회차는
max_connections 를 500 으로 올려 두고 돈다.

돌리는 법:
  python harness/scale/admission_ladder.py --rungs 12,24,48,96,160 --duration 130
"""
import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from load_ceiling import q, admin_call  # noqa: E402
from k6_ceiling import run_k6, duration_fill  # noqa: E402

CHAOS = str(ROOT / 'harness/docker-compose.chaos.yml')
SCALE = str(ROOT / 'harness/docker-compose.scale.yml')

POOL = 256      # 전 칸 고정. 풀 자체의 효과를 상수로 묶는다
QUERY = 24
REQUERY = 8


def bring_up(order):
    env = dict(os.environ)
    env.update({
        'SCALE_POOL': str(POOL),
        'SCALE_ORDER_INFLIGHT': str(order),
        'SCALE_QUERY_INFLIGHT': str(QUERY),
        'SCALE_REQUERY_INFLIGHT': str(REQUERY),
        'SCALE_PG_CPUS': '4.0',
        'SCALE_PG_MEM': '4g',
    })
    base = ['docker', 'compose', '-f', CHAOS, '-f', SCALE,
            '--env-file', str(ROOT / 'harness/.env.chaos')]
    kw = dict(cwd=str(ROOT / 'harness'), env=env, capture_output=True, text=True, timeout=900)
    for attempt in range(3):
        code = subprocess.run(
            base + ['up', '-d', '--force-recreate', 'chaos-api-1', 'chaos-api-2'], **kw).returncode
        if code == 0:
            return 0
        print('  기동 재시도 %d/3' % (attempt + 1), flush=True)
        time.sleep(20)
    return code


def wait_ready(timeout=240):
    import urllib.request
    end = time.time() + timeout
    while time.time() < end:
        try:
            with urllib.request.urlopen(
                    'http://localhost:18080/actuator/health/readiness', timeout=5) as r:
                if b'UP' in r.read():
                    return True
        except Exception:
            pass
        time.sleep(5)
    return False


def verify_limit(expected, tries=12):
    """설정이 실제로 적용됐는지 지표로 대조한다. 한계 31 이 이 확인의 부재였다."""
    for _ in range(tries):
        v = q('max(exchange_admission_limit{scope="order"})')
        if v is not None and int(v) == expected:
            return True
        time.sleep(5)
    return False


def measure():
    return {
        '접수/s': q('sum(rate(http_server_requests_seconds_count{uri="/api/v1/orders",method="POST",status="201"}[1m]))'),
        '체결/s': q('sum(rate(exchange_trades_total[1m]))'),
        '수용거절/s': q('sum(rate(exchange_admission_rejected_total{scope="order"}[1m]))'),
        'p95(ms)': (lambda v: v * 1000 if v else v)(q('max(exchange:order_latency:p95_1m)')),
        'p99(ms)': (lambda v: v * 1000 if v else v)(q('max(exchange:order_latency:p99_1m)')),
        '시퀀서 큐': q('max(exchange_sequencer_queue_size)'),
        '풀 대기': q('sum(hikaricp_connections_pending)'),
        '풀 활성': q('sum(hikaricp_connections_active)'),
        'DB CPU%': q('max(container_cpu_percent{name="chaos-pg"})'),
        'api1 CPU%': q('max(container_cpu_percent{name="chaos-api-1"})'),
        'DB 활성세션': q('sum(pg_stat_activity_count{state="active"})'),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--rungs', default='12,24,48,96,160')
    ap.add_argument('--rate', type=int, default=150)
    ap.add_argument('--duration', type=int, default=130)
    ap.add_argument('--bots-ops', type=int, default=20)
    ap.add_argument('--out', default=str(ROOT / 'harness/evidence/admission-ladder.json'))
    a = ap.parse_args()

    rungs = [int(x) for x in a.rungs.split(',')]
    print('정원 사다리 %s (풀 %d 고정, 조회 %d, 재조회 %d)' % (rungs, POOL, QUERY, REQUERY), flush=True)
    out = {'rungs': rungs, 'pool': POOL, 'rate': a.rate, 'duration': a.duration, 'rows': []}

    for order in rungs:
        print('\n' + '#' * 62, flush=True)
        print('[정원 %d] 예비 %d' % (order, POOL - order - QUERY - REQUERY), flush=True)
        if bring_up(order) != 0 or not wait_ready():
            print('  기동 실패', flush=True)
            out['rows'].append({'order': order, 'error': '기동 실패'})
            continue
        if not verify_limit(order):
            got = q('max(exchange_admission_limit{scope="order"})')
            print('  설정 미적용. 기대 %d 실제 %s. 이 칸은 버린다' % (order, got), flush=True)
            out['rows'].append({'order': order, 'error': '설정 미적용(실제 %s)' % got})
            continue
        print('  정원 적용 확인', flush=True)
        admin_call('PUT', '/admin/v1/sim/rate', {'target_ops': a.bots_ops})
        time.sleep(35)

        proc = run_k6(a.rate, a.duration, 'al-%d' % order)
        time.sleep(min(duration_fill(a.duration), a.duration - 5))
        s = measure()
        s['order'] = order
        try:
            proc.communicate(timeout=a.duration + 120)
        except subprocess.TimeoutExpired:
            proc.kill()
        out['rows'].append(s)
        for k in ('접수/s', '체결/s', '수용거절/s', 'p99(ms)', '시퀀서 큐', 'DB CPU%'):
            v = s.get(k)
            print('  %-12s %s' % (k, '-' if v is None else round(v, 2)), flush=True)
        time.sleep(20)

    Path(a.out).write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding='utf-8')

    keys = ['접수/s', '체결/s', '수용거절/s', 'p95(ms)', 'p99(ms)', '시퀀서 큐',
            '풀 대기', 'DB CPU%', 'DB 활성세션']
    print('\n' + '=' * 104)
    print('%-8s' % '정원' + ''.join('%11s' % k[:10] for k in keys))
    best = None
    for r in out['rows']:
        if 'error' in r:
            print('%-8d%s' % (r['order'], r['error']))
            continue
        line = '%-8d' % r['order']
        for k in keys:
            v = r.get(k)
            line += '%11s' % ('-' if v is None else round(v, 1))
        print(line)
        if r.get('체결/s') is not None and (best is None or r['체결/s'] > best['체결/s']):
            best = r
    if best:
        print('\n체결 최대: 정원 %d 에서 초당 %.2f (p99 %.0f밀리초)'
              % (best['order'], best['체결/s'], best.get('p99(ms)') or 0))
    print('\n판정. 최대화 대상은 체결이다. 접수만 오르고 체결이 주는 칸은 과개방이다.')
    print('결과 파일: ' + a.out)


if __name__ == '__main__':
    sys.exit(main())
