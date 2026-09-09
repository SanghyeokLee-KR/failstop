"""칸마다 초기화하고 도는 실험 묶음. 정본 13.5 한계 32와 33.

왜 다시 만드는가. 2026-09-09 실험 여덟 회차 중 넷이 무효였고 그중 셋의 사유가 같다.
칸을 순서대로 돌면서 데이터가 쌓여 축의 효과와 시간의 효과가 섞였다. 하루 부하로
데이터베이스가 2,361메가바이트가 됐고 같은 부하에서 체결이 초당 14.0에서 2.7로 떨어졌다.
곧 뒤 칸일수록 불리한 조건에서 재고 있었다.

대책은 칸마다 볼륨을 파기하고 다시 세우는 것이다. 비용이 크다. 칸당 초기화와 기동과
마이그레이션과 워밍업에 3분쯤 더 든다. 그래도 그 비용을 내지 않으면 결과를 못 쓴다.

같이 고친 것. 매 칸에서 데이터 상태를 함께 기록한다. 크기와 행 수를 남기면 나중에
회차 간 비교가 가능한지 판정할 수 있다. 오늘은 그 기록이 없어 판정이 사후에 뒤집혔다.

돌리는 법:
  python harness/scale/clean_suite.py --suite pool
  python harness/scale/clean_suite.py --suite ceiling
  python harness/scale/clean_suite.py --suite dbspec
"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from load_ceiling import q, admin_call  # noqa: E402
from k6_ceiling import run_k6, duration_fill  # noqa: E402

CHAOS = str(ROOT / 'harness/docker-compose.chaos.yml')
SCALE = str(ROOT / 'harness/docker-compose.scale.yml')
ENVF = str(ROOT / 'harness/.env.chaos')
WRITE_SHARE = 0.40


def compose(args, env=None, timeout=900):
    e = dict(os.environ)
    if env:
        e.update(env)
    return subprocess.run(
        ['docker', 'compose', '-f', CHAOS, '-f', SCALE, '--env-file', ENVF] + args,
        cwd=str(ROOT / 'harness'), env=e, capture_output=True, text=True, timeout=timeout).returncode


def reset_stack(env):
    """볼륨까지 파기하고 다시 세운다. 이 함수가 이 러너의 존재 이유다."""
    compose(['--profile', 'obs', '--profile', 'admin', '--profile', 'load',
             'down', '-v', '--remove-orphans'], env)
    time.sleep(8)
    # 관리 평면도 함께 올린다. 봇 목표 처리량을 그쪽 API 로 거는데 obs 만 올리면
    # chaos-admin 이 없어 연결이 거부된다(2026-09-09 실측).
    if compose(['--profile', 'obs', '--profile', 'admin', 'up', '-d'], env) != 0:
        return False
    if not wait_ready():
        return False
    return wait_admin() and wait_prom()


def wait_admin(timeout=180):
    """관리 평면이 mTLS 세션을 낼 때까지 기다린다."""
    end = time.time() + timeout
    while time.time() < end:
        try:
            if admin_call('POST', '/admin/v1/session')[0] == 200:
                return True
        except Exception:
            pass
        time.sleep(6)
    return False


def wait_prom(timeout=180):
    """프로메테우스가 첫 스크레이프를 마칠 때까지 기다린다. 그전에는 질의가 빈다."""
    end = time.time() + timeout
    while time.time() < end:
        if q('count(up)') is not None:
            return True
        time.sleep(6)
    return False


def wait_ready(timeout=300):
    end = time.time() + timeout
    while time.time() < end:
        try:
            with urllib.request.urlopen(
                    'http://localhost:18080/actuator/health/readiness', timeout=5) as r:
                if b'UP' in r.read():
                    return True
        except Exception:
            pass
        time.sleep(6)
    return False


def reissue_keys():
    """볼륨을 파기했으므로 부하 계정이 사라진다. 이 재발급이 없으면 k6 가 전량 401 이다."""
    for _ in range(6):
        try:
            req = urllib.request.Request(
                'http://localhost:18080/harness/v1/loadtest-keys?market=KRW-BTC&count=4',
                method='POST')
            with urllib.request.urlopen(req, timeout=90) as r:
                keys = json.load(r)
            (ROOT / 'harness/load/secrets/k6_keys.json').write_text(
                json.dumps(keys, ensure_ascii=False), encoding='utf-8')
            return len(keys)
        except Exception:
            time.sleep(10)
    return 0


def db_state():
    """데이터 상태를 기록한다. 회차 간 비교 가능성을 나중에 판정하려면 이 값이 있어야 한다."""
    p = subprocess.run(
        ['docker', 'exec', 'chaos-pg', 'psql', '-U', 'exchange', '-d', 'exchange', '-t', '-A',
         '-F', '|', '-c',
         "select (select count(*) from orders), (select count(*) from trades), "
         "pg_database_size('exchange')/1048576;"],
        capture_output=True, text=True, timeout=60)
    try:
        o, t, mb = p.stdout.strip().split('|')
        return {'주문': int(o), '체결': int(t), 'DB(MB)': int(mb)}
    except Exception:
        return {'주문': None, '체결': None, 'DB(MB)': None}


def measure():
    return {
        '접수/s': q('sum(rate(http_server_requests_seconds_count{uri="/api/v1/orders",method="POST",status="201"}[1m]))'),
        '체결/s': q('sum(rate(exchange_trades_total[1m]))'),
        '수용거절/s': q('sum(rate(exchange_admission_rejected_total{scope="order"}[1m]))'),
        'p95(ms)': (lambda v: v * 1000 if v else v)(q('max(exchange:order_latency:p95_1m)')),
        'p99(ms)': (lambda v: v * 1000 if v else v)(q('max(exchange:order_latency:p99_1m)')),
        '시퀀서 큐': q('max(exchange_sequencer_queue_size)'),
        '풀 대기': q('sum(hikaricp_connections_pending)'),
        'DB CPU%': q('max(container_cpu_percent{name="chaos-pg"})'),
        '읽은튜플/s': q('sum(rate(pg_stat_database_tup_returned{datname="exchange"}[1m]))+sum(rate(pg_stat_database_tup_fetched{datname="exchange"}[1m]))'),
        '쓴튜플/s': q('sum(rate(pg_stat_database_tup_inserted{datname="exchange"}[1m]))+sum(rate(pg_stat_database_tup_updated{datname="exchange"}[1m]))+sum(rate(pg_stat_database_tup_deleted{datname="exchange"}[1m]))'),
    }


# 묶음 정의. 각 칸은 (라벨, 환경, 기대 검증) 셋이다.
SUITES = {
    # 풀이 축이다. 정원은 유도값에 맡긴다.
    'pool': [(str(p), {'SCALE_POOL': str(p), 'SCALE_ORDER_INFLIGHT': '0',
                       'SCALE_QUERY_INFLIGHT': '0', 'SCALE_REQUERY_INFLIGHT': '0',
                       'SCALE_PG_CPUS': '4.0', 'SCALE_PG_MEM': '4g'},
              ('limit', max(4, int(p * WRITE_SHARE)))) for p in (16, 32, 64, 128)],
    # 데이터베이스 규격이 축이다.
    'dbspec': [('%s코어' % c, {'SCALE_POOL': '32', 'SCALE_ORDER_INFLIGHT': '0',
                             'SCALE_QUERY_INFLIGHT': '0', 'SCALE_REQUERY_INFLIGHT': '0',
                             'SCALE_PG_CPUS': c, 'SCALE_PG_MEM': m}, None)
               for c, m in (('1.0', '1g'), ('2.0', '2g'), ('4.0', '4g'), ('8.0', '8g'))],
    # 부하율이 축이다. 설정은 기본으로 고정한다.
    'ceiling': [(str(r), {'SCALE_POOL': '32', 'SCALE_ORDER_INFLIGHT': '0',
                          'SCALE_QUERY_INFLIGHT': '0', 'SCALE_REQUERY_INFLIGHT': '0',
                          'SCALE_PG_CPUS': '4.0', 'SCALE_PG_MEM': '4g'}, ('rate', r))
                for r in (20, 60, 120, 240)],
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--suite', required=True, choices=sorted(SUITES))
    ap.add_argument('--rate', type=int, default=150)
    ap.add_argument('--duration', type=int, default=130)
    ap.add_argument('--bots-ops', type=int, default=20)
    ap.add_argument('--out', default=None)
    a = ap.parse_args()

    cells = SUITES[a.suite]
    out_path = a.out or str(ROOT / ('harness/evidence/clean-%s.json' % a.suite))
    out = {'suite': a.suite, 'duration': a.duration, 'bots_ops': a.bots_ops, 'rows': []}
    print('묶음 %s, 칸 %d개. 칸마다 볼륨을 파기하고 다시 세운다' % (a.suite, len(cells)), flush=True)

    for label, env, check in cells:
        rate = check[1] if (check and check[0] == 'rate') else a.rate
        print('\n' + '#' * 64, flush=True)
        print('[%s] 부하 %d/s' % (label, rate), flush=True)
        if not reset_stack(env):
            print('  초기화 실패', flush=True)
            out['rows'].append({'cell': label, 'error': '초기화 실패'})
            continue
        n = reissue_keys()
        if n == 0:
            print('  키 재발급 실패', flush=True)
            out['rows'].append({'cell': label, 'error': '키 재발급 실패'})
            continue
        if check and check[0] == 'limit':
            got = None
            for _ in range(14):
                got = q('max(exchange_admission_limit{scope="order"})')
                if got is not None and int(got) == check[1]:
                    break
                time.sleep(5)
            if got is None or int(got) != check[1]:
                print('  설정 미적용. 기대 %s 실제 %s' % (check[1], got), flush=True)
                out['rows'].append({'cell': label, 'error': '설정 미적용(%s)' % got})
                continue
        before = db_state()
        print('  초기 상태 %s' % before, flush=True)
        admin_call('PUT', '/admin/v1/sim/rate', {'target_ops': a.bots_ops})
        time.sleep(40)

        proc = run_k6(rate, a.duration, 'clean-%s-%s' % (a.suite, label))
        time.sleep(min(duration_fill(a.duration), a.duration - 5))
        s = measure()
        s['cell'] = label
        s['rate'] = rate
        s['db_before'] = before
        try:
            proc.communicate(timeout=a.duration + 120)
        except subprocess.TimeoutExpired:
            proc.kill()
        s['db_after'] = db_state()
        out['rows'].append(s)
        for k in ('접수/s', '체결/s', '수용거절/s', 'p99(ms)', '시퀀서 큐', 'DB CPU%'):
            v = s.get(k)
            print('  %-12s %s' % (k, '-' if v is None else round(v, 2)), flush=True)
        print('  종료 상태 %s' % s['db_after'], flush=True)

    Path(out_path).write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding='utf-8')

    keys = ['접수/s', '체결/s', '수용거절/s', 'p95(ms)', 'p99(ms)', '시퀀서 큐',
            '풀 대기', 'DB CPU%', '읽은튜플/s', '쓴튜플/s']
    print('\n' + '=' * 116)
    print('%-9s%-7s' % ('칸', '부하') + ''.join('%11s' % k[:10] for k in keys))
    for r in out['rows']:
        if 'error' in r:
            print('%-9s%s' % (r['cell'], r['error']))
            continue
        line = '%-9s%-7d' % (r['cell'], r['rate'])
        for k in keys:
            v = r.get(k)
            line += '%11s' % ('-' if v is None else round(v, 1))
        print(line)
    print('\n칸마다 초기화했으므로 칸 사이 비교가 성립한다. 각 칸의 시작 데이터가 같다.')
    print('결과 파일: ' + out_path)


if __name__ == '__main__':
    sys.exit(main())
