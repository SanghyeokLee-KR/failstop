"""수용 정원이 적정한지 잰다. 정본 13.5 한계 26.

문제. 개방 루프 부하에서 요청 초당 20 부터 거절이 난다(2026-09-09, k6-ceiling). 그것이
의도한 보호인지 과보호인지 모른다. 정원을 넓히면 처리량이 오르는가, 아니면 지연만
나빠지고 처리량은 그대로인가.

설계의 함정. 정원은 풀에서 유도된다. 쓰기 0.40, 조회 0.40, 재조회 0.10, 예비 0.10 이다.
그래서 "정원을 넓힌다" 가 곧 "풀을 키운다" 가 되어 두 축이 섞인다. 풀을 키우면 유휴
커넥션이 늘어 그 자체로 지연이 나빠진다는 것을 이미 실측했다(pool-db-matrix).

그래서 명시 설정으로 두 축을 가른다. `exchange.admission.{order,query,requery}-in-flight`
가 0 보다 크면 유도를 건너뛰고 그 값을 쓴다. 다만 합이 풀을 잠식하면 기동을 멈추는
가드가 있으므로 넓은 정원은 풀도 함께 키워야 한다. 그 몫을 P 칸이 갈라 낸다.

| 칸 | 풀 | 쓰기 | 조회 | 재조회 | 예비 | 무엇을 보는가 |
|---|---|---|---|---|---|---|
| N | 32 | 6 | 6 | 2 | 18 | 정원을 좁히면 |
| B | 32 | 12 | 12 | 3 | 5 | 현행(유도값) |
| P | 64 | 12 | 12 | 3 | 37 | 풀만 키우면 |
| W | 64 | 24 | 24 | 6 | 10 | 정원과 풀을 함께 키우면 |

W 대 P 가 정원의 몫이고 P 대 B 가 풀의 몫이다. 둘을 빼야 정원 효과가 나온다.

각 칸에서 k6 사다리를 짧게 돌린다. 봇 기저는 켜 둔다.

돌리는 법:
  python harness/scale/admission_matrix.py --rungs 20,60,150 --duration 100
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

COMPOSE = str(ROOT / 'harness/docker-compose.chaos.yml')

CELLS = [
    ('N', 32, 6, 6, 2),
    ('B', 32, 12, 12, 3),
    ('P', 64, 12, 12, 3),
    ('W', 64, 24, 24, 6),
]


def compose(*args, env=None):
    e = dict(os.environ)
    if env:
        e.update(env)
    return subprocess.run(['docker', 'compose', '-f', COMPOSE,
                           '--env-file', str(ROOT / 'harness/.env.chaos')] + list(args),
                          cwd=str(ROOT / 'harness'), env=e,
                          capture_output=True, text=True, timeout=600).returncode


def bring_up(pool, w, qy, rq):
    """앱 두 노드만 다시 띄운다. DB 와 관측은 그대로 둬 표본 연속성을 지킨다."""
    env = {
        'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE': str(pool),
        'EXCHANGE_ADMISSION_ORDER_IN_FLIGHT': str(w),
        'EXCHANGE_ADMISSION_QUERY_IN_FLIGHT': str(qy),
        'EXCHANGE_ADMISSION_REQUERY_IN_FLIGHT': str(rq),
    }
    return compose('up', '-d', '--force-recreate', 'chaos-api-1', 'chaos-api-2', env=env)


def wait_ready(timeout=180):
    end = time.time() + timeout
    while time.time() < end:
        try:
            import urllib.request
            with urllib.request.urlopen('http://localhost:18080/actuator/health/readiness',
                                        timeout=5) as r:
                if b'UP' in r.read():
                    return True
        except Exception:
            pass
        time.sleep(5)
    return False


def measure():
    return {
        '접수/s': q('sum(rate(http_server_requests_seconds_count{uri="/api/v1/orders",method="POST",status="201"}[1m]))'),
        '취소/s': q('sum(rate(http_server_requests_seconds_count{uri=~"/api/v1/orders.*",method="DELETE"}[1m]))'),
        '체결확정/s': q('sum(rate(exchange_order_latency_seconds_count[1m]))'),
        '체결/s': q('max(exchange:trades_per_second:rate1m)'),
        '수용거절/s': q('sum(rate(exchange_admission_rejected_total[1m]))'),
        'p95(ms)': (lambda v: v * 1000 if v else v)(q('max(exchange:order_latency:p95_1m)')),
        'p99(ms)': (lambda v: v * 1000 if v else v)(q('max(exchange:order_latency:p99_1m)')),
        'api1 CPU%': q('max(container_cpu_percent{name="chaos-api-1"})'),
        'DB CPU%': q('max(container_cpu_percent{name="chaos-pg"})'),
        '풀 활성': q('sum(hikaricp_connections_active)'),
        '풀 대기': q('sum(hikaricp_connections_pending)'),
        '시퀀서 큐': q('max(exchange_sequencer_queue_size)'),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--rungs', default='20,60,150')
    ap.add_argument('--duration', type=int, default=100)
    ap.add_argument('--bots-ops', type=int, default=20)
    ap.add_argument('--cells', default=None, help='쉼표로 고른다. 없으면 전부')
    ap.add_argument('--out', default=str(ROOT / 'harness/evidence/admission-matrix.json'))
    a = ap.parse_args()

    rungs = [int(x) for x in a.rungs.split(',')]
    cells = [c for c in CELLS if not a.cells or c[0] in a.cells.split(',')]
    out = {'rungs': rungs, 'duration': a.duration, 'bots_ops': a.bots_ops, 'cells': []}

    for name, pool, w, qy, rq in cells:
        print('\n' + '#' * 70, flush=True)
        print('[%s] 풀 %d / 쓰기 %d / 조회 %d / 재조회 %d / 예비 %d'
              % (name, pool, w, qy, rq, pool - w - qy - rq), flush=True)
        if bring_up(pool, w, qy, rq) != 0:
            print('  기동 실패. 이 칸은 비운다', flush=True)
            out['cells'].append({'cell': name, 'error': 'compose up 실패'})
            continue
        if not wait_ready():
            print('  readiness 미도달. 이 칸은 비운다', flush=True)
            out['cells'].append({'cell': name, 'error': 'readiness 미도달'})
            continue
        admin_call('PUT', '/admin/v1/sim/rate', {'target_ops': a.bots_ops})
        time.sleep(40)

        rows = []
        for r in rungs:
            proc = run_k6(r, a.duration, '%s-%d' % (name, r))
            time.sleep(min(duration_fill(a.duration), a.duration - 5))
            s = measure()
            s['_요청'] = r
            rows.append(s)
            print('  요청 %-4d 접수 %-6s 거절 %-7s p99 %-7s 큐 %s'
                  % (r,
                     round(s['접수/s'] or 0, 1), round(s['수용거절/s'] or 0, 1),
                     round(s['p99(ms)'] or 0, 1), round(s['시퀀서 큐'] or 0, 1)), flush=True)
            try:
                proc.communicate(timeout=a.duration + 120)
            except subprocess.TimeoutExpired:
                proc.kill()
            time.sleep(20)
        out['cells'].append({'cell': name, 'pool': pool, 'write': w, 'query': qy,
                             'requery': rq, 'rows': rows})

    Path(a.out).write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding='utf-8')

    print('\n' + '=' * 96)
    print('%-5s%-7s%-8s' % ('칸', '풀', '쓰기') + ''.join(
        '%22s' % ('요청 %d' % r) for r in rungs))
    print('%-20s' % '' + ''.join('%22s' % '접수 / 거절 / p99' for _ in rungs))
    for c in out['cells']:
        if 'rows' not in c:
            continue
        line = '%-5s%-7d%-8d' % (c['cell'], c['pool'], c['write'])
        for row in c['rows']:
            line += '%22s' % ('%s / %s / %s' % (
                round(row['접수/s'] or 0, 1), round(row['수용거절/s'] or 0, 1),
                round(row['p99(ms)'] or 0)))
        print(line)
    print('\n판정. W 대 P 가 정원의 몫이고 P 대 B 가 풀의 몫이다.')
    print('정원을 넓혀 접수가 오르면 현행은 과보호다. 지연만 나빠지면 현행이 맞다.')
    print('결과 파일: ' + a.out)


if __name__ == '__main__':
    sys.exit(main())
