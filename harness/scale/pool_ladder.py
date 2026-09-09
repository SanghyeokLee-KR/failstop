"""커넥션 풀의 최적값을 찾는다. 정본 13.5 한계 26.

축을 정한 근거. 처음에는 수용 정원을 축으로 잡았으나 틀렸다. 풀 256 고정에 정원만
12에서 160으로 열세 배 벌린 사다리에서 체결이 4.2에서 2.9 사이로 붙어 사실상 움직이지
않았다. 반면 같은 정원 12에서 풀만 32에서 256으로 바꾸니 체결이 13에서 19 사이에서
4.2로 무너졌다. 지배 변수는 정원이 아니라 풀이다.

정원은 풀의 40퍼센트로 유도되는 종속 변수다. 곧 실제 손잡이는 풀 하나이므로 풀을 축으로
놓고 정원은 유도값에 맡긴다. 명시 정원을 함께 주면 두 축이 섞여 무엇이 효과인지 못 가린다.

최대화 대상은 체결이지 접수가 아니다. 접수는 받았다는 뜻일 뿐이고 거래소가 하는 일은
체결이다. 접수만 늘리면 큐에 쌓이고 지연만 나빠진다. 실제로 풀 256에서 접수가 65로
오르는데 체결은 4.2로 무너진다.

전제 확인 둘. 유도 정원이 실제로 적용됐는지 지표로 대조하고, 풀 설정이 닿았는지도 함께
본다. 종전 격자가 그 확인 없이 돌아 네 칸을 같은 설정으로 재고 잘못된 판정을 냈다(한계 31).

주의. 데이터베이스의 최대 접속 수가 두 노드의 풀 합보다 커야 한다. 이 회차는
max_connections 를 500 으로 올려 두고 돈다. 풀 256 이면 두 노드 합이 512 라 그 값도 모자란다.

돌리는 법:
  python harness/scale/pool_ladder.py --rungs 8,16,32,48,64,96,128 --duration 130
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

WRITE_SHARE = 0.40   # AdmissionLimits 의 배분율
MIN_LIMIT = 4


def bring_up(pool):
    """풀만 바꾼다. 정원을 0 으로 두어 앱이 풀에서 유도하게 한다."""
    env = dict(os.environ)
    env.update({
        'SCALE_POOL': str(pool),
        'SCALE_ORDER_INFLIGHT': '0',
        'SCALE_QUERY_INFLIGHT': '0',
        'SCALE_REQUERY_INFLIGHT': '0',
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


def verify(pool, expected, tries=14):
    """풀과 유도 정원이 둘 다 실제로 적용됐는지 지표로 대조한다(한계 31)."""
    for _ in range(tries):
        lim = q('max(exchange_admission_limit{scope="order"})')
        mx = q('max(hikaricp_connections_max)')
        if lim is not None and mx is not None and int(lim) == expected and int(mx) == pool:
            return True, None
        time.sleep(5)
    return False, '정원 기대 %d 실제 %s / 풀 기대 %d 실제 %s' % (
        expected, q('max(exchange_admission_limit{scope="order"})'),
        pool, q('max(hikaricp_connections_max)'))


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
        'DB 활성세션': q('sum(pg_stat_activity_count{state="active"})'),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--rungs', default='8,16,32,48,64,96,128', help='커넥션 풀 크기')
    ap.add_argument('--rate', type=int, default=150)
    ap.add_argument('--duration', type=int, default=130)
    ap.add_argument('--bots-ops', type=int, default=20)
    ap.add_argument('--out', default=str(ROOT / 'harness/evidence/pool-ladder.json'))
    a = ap.parse_args()

    rungs = [int(x) for x in a.rungs.split(',')]
    print('풀 사다리 %s. 정원은 유도값이고 체결을 최대화 대상으로 본다' % rungs, flush=True)
    out = {'rungs': rungs, 'axis': 'pool', 'rate': a.rate,
           'duration': a.duration, 'rows': []}

    for pool in rungs:
        expected = max(MIN_LIMIT, int(pool * WRITE_SHARE))
        print('\n' + '#' * 62, flush=True)
        print('[풀 %d] 유도 쓰기 정원 %d' % (pool, expected), flush=True)
        if bring_up(pool) != 0 or not wait_ready():
            print('  기동 실패', flush=True)
            out['rows'].append({'pool': pool, 'error': '기동 실패'})
            continue
        ok, why = verify(pool, expected)
        if not ok:
            print('  설정 미적용. %s. 이 칸은 버린다' % why, flush=True)
            out['rows'].append({'pool': pool, 'error': why})
            continue
        print('  풀과 유도 정원 적용 확인', flush=True)
        admin_call('PUT', '/admin/v1/sim/rate', {'target_ops': a.bots_ops})
        time.sleep(35)

        proc = run_k6(a.rate, a.duration, 'pool-%d' % pool)
        time.sleep(min(duration_fill(a.duration), a.duration - 5))
        s = measure()
        s['pool'] = pool
        s['order_limit'] = expected
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
    print('\n' + '=' * 110)
    print('%-7s%-9s' % ('풀', '쓰기정원') + ''.join('%11s' % k[:10] for k in keys))
    best = None
    for r in out['rows']:
        if 'error' in r:
            print('%-7d%s' % (r['pool'], r['error']))
            continue
        line = '%-7d%-9d' % (r['pool'], r['order_limit'])
        for k in keys:
            v = r.get(k)
            line += '%11s' % ('-' if v is None else round(v, 1))
        print(line)
        if r.get('체결/s') is not None and (best is None or r['체결/s'] > best['체결/s']):
            best = r
    if best:
        print('\n체결 최대: 풀 %d (유도 쓰기 정원 %d) 에서 초당 %.2f, p99 %.0f밀리초'
              % (best['pool'], best['order_limit'], best['체결/s'], best.get('p99(ms)') or 0))
    print('\n판정. 체결이 정점을 찍는 풀이 답이다. 접수만 오르고 체결이 주는 칸은 과개방이다.')
    print('결과 파일: ' + a.out)


if __name__ == '__main__':
    sys.exit(main())
