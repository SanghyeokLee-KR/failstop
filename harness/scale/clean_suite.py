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
import io
import json
import urllib.parse
import re
import os
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from load_ceiling import q  # noqa: E402
from k6_ceiling import run_k6, duration_fill, markets  # noqa: E402

CHAOS = str(ROOT / 'harness/docker-compose.chaos.yml')
SCALE = str(ROOT / 'harness/docker-compose.scale.yml')
ENVF = str(ROOT / 'harness/.env.chaos')
WRITE_SHARE = 0.40


# 마지막 compose 실패의 출력. 종료코드만 남기면 '초기화 실패' 네 글자로 끝나
# 원인을 못 찾는다(2026-09-10 실측: 도커 데몬이 죽었는데 그 사실이 안 보였다).
LAST_ERR = ['']


def compose(args, env=None, timeout=900):
    e = dict(os.environ)
    if env:
        e.update(env)
    p = subprocess.run(
        ['docker', 'compose', '-f', CHAOS, '-f', SCALE, '--env-file', ENVF] + args,
        cwd=str(ROOT / 'harness'), env=e, capture_output=True, text=True, timeout=timeout)
    if p.returncode != 0:
        LAST_ERR[0] = ((p.stderr or '') + (p.stdout or ''))[-700:]
    return p.returncode


def daemon_alive():
    """데몬 자체가 죽었으면 칸을 더 돌아 봐야 전부 같은 실패다. 먼저 가른다."""
    return subprocess.run(['docker', 'info'], capture_output=True,
                          text=True, timeout=60).returncode == 0


def reset_stack(env):
    """볼륨까지 파기하고 다시 세운다. 이 함수가 이 러너의 존재 이유다."""
    compose(['--profile', 'obs', '--profile', 'admin', '--profile', 'load',
             'down', '-v', '--remove-orphans'], env)
    # 반복 파기 뒤 데몬이 지운 컨테이너를 참조하는 상태가 된다(2026-09-09 실측:
    # `No such container`). 그러면 다음 기동이 통째로 실패한다. 매 칸 정리한다.
    #
    # TRUNCATE 로 대신할 수 없다. 원장 표에 append-only 트리거가 걸려 있어
    # `append-only 위반: ledger_entries.TRUNCATE` 로 막힌다. 그 트리거는 계약이므로
    # 우회하지 않는다. 초기화 수단은 볼륨 파기뿐이다.
    subprocess.run(['docker', 'container', 'prune', '-f'],
                   capture_output=True, text=True, timeout=120)
    time.sleep(10)
    if compose(['--profile', 'obs', 'up', '-d'], env) != 0:
        return False
    if not wait_ready():
        return False
    return wait_prom()


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
                # 부하를 여러 마켓에 흩으면 그 마켓들의 자산 계정이 있어야 한다.
                # 없으면 매도 주문이 접수를 통과한 뒤 정산에서 라이터를 죽인다(한계 40).
                'http://localhost:18080/harness/v1/loadtest-keys?market='
                + urllib.parse.quote(markets())
                + '&count=4',
                method='POST')
            with urllib.request.urlopen(req, timeout=90) as r:
                keys = json.load(r)
            (ROOT / 'harness/load/secrets/k6_keys.json').write_text(
                json.dumps(keys, ensure_ascii=False), encoding='utf-8')
            return len(keys)
        except Exception:
            time.sleep(10)
    return 0


def run_id_for(suite, label):
    """주행 이름을 만든다. order_flow.js 가 [A-Za-z0-9_-] 1~16자만 받는다.

    그 제한은 client_order_id 가 서버 검증(OrderGateway [A-Za-z0-9_-]{1,36})에
    걸리지 않게 하려는 것이다. 벗어나면 k6 가 기동 즉시 script exception 으로 죽는다.

    2026-09-10 실측. 종전에는 'clean-%s-%s' % (suite, label) 로 만들었고 그 결과
    열두 칸 중 여섯 칸이 죽었다. clean-ceiling-20 은 16자라 살고 clean-ceiling-120 은
    17자라 죽었다. clean-dbspec-1.0코어 는 한글까지 섞여 죽었다. 칸의 유효와 무효가
    부하율이 아니라 이름 길이로 갈렸다. order_flow.js 는 그 사유를 정확히 적어 냈으나
    러너가 출력을 파이프로 받고 읽지 않아 그 문장이 버려졌다.
    """
    body = re.sub(r'[^A-Za-z0-9_-]', '', '%s%s' % (suite[:4], label))
    rid = body[:16]
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,16}', rid):
        raise ValueError('주행 이름을 만들 수 없다: %r -> %r' % (label, rid))
    return rid


def keys_match_db():
    """발급한 키가 실제로 데이터베이스에 있는지 본다.

    볼륨을 파기하면 부하 계정이 사라진다. 재발급이 200 을 돌려줘도 그 뒤 스택이
    다시 서면 어긋난다. 어긋나면 k6 가 전량 401 인데, 인증 실패는 Micrometer
    필터 앞에서 끊겨 지표에 안 잡힌다(2026-09-09 실측: 그래서 여섯 칸이 조용한
    시스템으로 보였다). 그러니 부하를 넣기 전에 여기서 가른다.

    키 값은 찍지 않는다. 개수만 본다.
    """
    try:
        keys = json.loads((ROOT / 'harness/load/secrets/k6_keys.json')
                          .read_text(encoding='utf-8'))
        acc = [k['access_key'] for k in keys]
    except Exception:
        return 0, 0
    lst = ','.join("'" + a.replace("'", "''") + "'" for a in acc)
    p = subprocess.run(
        ['docker', 'exec', 'chaos-pg', 'psql', '-U', 'exchange', '-d', 'exchange',
         '-t', '-A', '-c', 'select count(*) from api_keys where access_key in (%s)' % lst],
        capture_output=True, text=True, timeout=60)
    try:
        return int(p.stdout.strip()), len(acc)
    except Exception:
        return 0, len(acc)


def set_bot_ops(ops):
    """봇 목표 처리량을 명령 큐에 직접 넣는다. 봇 노드가 그 큐를 소비한다.

    관리 평면을 쓰지 않는 이유는 의존을 줄이기 위해서다. chaos-admin 은 루트 compose.yaml
    에만 있고 이 러너는 harness 쪽 파일을 쓴다. 프로파일을 줘도 그 서비스가 안 뜬다
    (2026-09-09 실측). 큐는 어느 평면에서든 같은 데이터베이스에 있다.
    """
    subprocess.run(
        ['docker', 'exec', 'chaos-pg', 'psql', '-U', 'exchange', '-d', 'exchange', '-q', '-c',
         "INSERT INTO sim_commands (action, payload, requested_by) "
         "VALUES ('target_ops', '%s', 'clean_suite');" % ops],
        capture_output=True, text=True, timeout=60)


def wait_bots_steady(timeout, need=3, tol=0.35):
    """봇 체결이 안정 구간에 들 때까지 기다린다.

    고정 초를 세지 않는 이유가 있다. 2026-09-09 ceiling 회차에서 칸마다 봇 워밍업 상태가
    달라 값이 단조롭지 않았다. 요청 20 칸이 4.4, 60 칸이 55.7, 120 칸이 16.6 이었다.
    축 효과가 아니라 표본 시점 차이일 가능성이 컸다.

    판정은 연속 표본 셋의 체결률이 서로 tol 안에 들고 0 이 아닌 것이다. 그러면 봇이
    붙었고 값이 흔들리지 않는다는 뜻이다.
    """
    end = time.time() + timeout
    hist = []
    while time.time() < end:
        v = q('sum(rate(exchange_trades_total[1m]))')
        hist.append(v if v is not None else 0.0)
        hist = hist[-need:]
        if len(hist) == need and min(hist) > 0.5:
            lo, hi = min(hist), max(hist)
            if (hi - lo) / hi <= tol:
                print('  봇 안정 확인 %s' % [round(x, 1) for x in hist], flush=True)
                return True
        time.sleep(15)
    print('  봇 안정 미도달 %s' % [round(x, 1) for x in hist], flush=True)
    return False


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


# 봇 기저. set_bot_ops 로 고정하는 값에서 나오는 HTTP 주문율이다. 칸마다 재기보다
# 상수로 두고, 어긋나면 k6추정이 음수로 튀어 눈에 띄게 한다.
BOT_BASE = 17.0


def measure():
    return {
        '접수/s': q('sum(rate(http_server_requests_seconds_count{uri="/api/v1/orders",method="POST",status="201"}[1m]))'),
        '체결/s': q('sum(rate(exchange_trades_total[1m]))'),
        '수용거절/s': q('sum(rate(exchange_admission_rejected_total{scope="order"}[1m]))'),
        # 접수만 세면 요청이 사라진 칸을 포화로 오독한다(2026-09-09 실측: 요청 120/s
        # 에서 접수 16.5/s, 거부 0.0, p99 74ms). 포화면 지연이 오르는데 내렸다.
        # 즉 201 도 거부도 아닌 응답으로 빠진 것이다. 그 몫을 따로 센다.
        '비201/s': q('sum(rate(http_server_requests_seconds_count{uri="/api/v1/orders",method="POST",status!="201"}[1m]))'),
        '전체요청/s': q('sum(rate(http_server_requests_seconds_count{uri="/api/v1/orders",method="POST"}[1m]))'),
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
    # 예행용. 한 칸만 돌려 배선을 확인한다.
    'smoke': [('smoke', {'SCALE_POOL': '32', 'SCALE_ORDER_INFLIGHT': '0',
                         'SCALE_QUERY_INFLIGHT': '0', 'SCALE_REQUERY_INFLIGHT': '0',
                         'SCALE_PG_CPUS': '4.0', 'SCALE_PG_MEM': '4g'}, ('limit', 12))],
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
    # 수용 정원이 축이다. 풀을 64로 고정하고 쓰기 정원만 벌린다.
    #
    # 왜 풀을 크게 고정하는가. AdmissionLimits 가 명시 설정에서 예비분을 검사한다.
    # pool - write - query - requery > 0 이 아니면 기동을 멈춘다. 풀 32 에서는
    # 쓰기 정원을 24 위로 올릴 자리가 없다. 그리고 풀 사다리가 풀 자체는 접수에
    # 영향이 없음을 이미 보였으므로(16 에서 128 까지 접수 불변, 풀 대기 0)
    # 풀을 키워도 축이 오염되지 않는다.
    #
    # 왜 조회와 재조회를 12 와 3 으로 고정하는가. 풀 32 의 유도값이다. 기준선과
    # 같은 조회 조건을 두어야 쓰기 축만 남는다.
    #
    # 데이터베이스는 4코어로 둔다. 2026-09-10 실측에서 규격이 접수를 6퍼센트만
    # 움직였으므로 이 축의 교란 요인이 아니다.
    'admission': [(str(w), {'SCALE_POOL': '64', 'SCALE_ORDER_INFLIGHT': str(w),
                            'SCALE_QUERY_INFLIGHT': '12', 'SCALE_REQUERY_INFLIGHT': '3',
                            'SCALE_PG_CPUS': '4.0', 'SCALE_PG_MEM': '4g'},
                   ('limit', w)) for w in (12, 24, 36, 48)],
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
    ap.add_argument('--warmup', type=int, default=240,
                    help='봇 안정화 대기 상한 초. 고정 대기가 아니라 상한이다')
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
            if not daemon_alive():
                print('  도커 데몬이 죽었다. 남은 칸을 버리고 멈춘다', flush=True)
                out['rows'].append({'cell': label, 'error': '도커 데몬 정지'})
                break
            print('  초기화 실패: %s' % LAST_ERR[0].strip()[-300:], flush=True)
            out['rows'].append({'cell': label,
                                'error': '초기화 실패',
                                'stderr': LAST_ERR[0]})
            continue
        n = reissue_keys()
        if n == 0:
            print('  키 재발급 실패', flush=True)
            out['rows'].append({'cell': label, 'error': '키 재발급 실패'})
            continue
        hit, want = keys_match_db()
        if hit < want:
            print('  키가 데이터베이스와 어긋난다 (%d/%d). 이 칸은 버린다'
                  % (hit, want), flush=True)
            out['rows'].append({'cell': label,
                                'error': '키 불일치(%d/%d)' % (hit, want)})
            continue
        print('  키 %d개 대조 통과' % hit, flush=True)
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
        set_bot_ops(a.bots_ops)
        if not wait_bots_steady(a.warmup):
            print('  봇 안정화 실패. 이 칸은 버린다', flush=True)
            out['rows'].append({'cell': label, 'error': '봇 안정화 실패'})
            continue

        k6log = str(ROOT / ('harness/evidence/k6-%s-%s.log' % (a.suite, label)))
        proc = run_k6(rate, a.duration, run_id_for(a.suite, label), log_path=k6log)
        time.sleep(min(duration_fill(a.duration), a.duration - 5))
        s = measure()
        s['cell'] = label
        s['rate'] = rate
        s['db_before'] = before
        try:
            proc.communicate(timeout=a.duration + 120)
        except subprocess.TimeoutExpired:
            proc.kill()
        try:
            proc._log_fh.close()
        except Exception:
            pass
        try:
            k6out = io.open(k6log, encoding='utf-8', errors='replace').read()
        except Exception:
            k6out = ''
        s['db_after'] = db_state()

        # k6 의 종료코드와 출력을 버리면 죽은 발생기를 조용한 시스템으로 오독한다.
        # 2026-09-09 회차에서 12칸 중 6칸이 그렇게 무효였다. 거절과 인증 실패는
        # Micrometer 필터 앞에서 끊기므로 http_server_requests 에 안 잡힌다.
        # 그래서 k6 가 전량 401 이어도 표에는 '조용한 시스템' 으로 보인다.
        s['k6_rc'] = proc.returncode
        s['k6_tail'] = (k6out or '')[-600:]
        offered = (s.get('접수/s') or 0) + (s.get('수용거절/s') or 0)
        s['제공/s'] = round(offered, 2)
        # 봇은 앱 안 vthread 가 아니라 BotApiClient 로 HTTP 를 친다. 그래서 접수에
        # 봇 몫이 섞인다. 기저를 빼야 k6 가 실제로 얼마를 넣었는지 나온다.
        s['k6추정/s'] = round(offered - (s.get('봇기저/s') or BOT_BASE), 2)
        if s['k6추정/s'] < rate * 0.5:
            s['error'] = 'k6 미주입(제공 %.1f/s, 요청 %d/s, rc=%s)' % (
                offered, rate, proc.returncode)
            print('  %s' % s['error'], flush=True)
            errs = [l for l in k6out.splitlines()
                    if 'level=error' in l or 'panic' in l.lower()]
            if errs:
                print('  k6 에러: %s' % errs[0][:300], flush=True)
                s['k6_error'] = errs[0][:600]
            elif k6out:
                print('  k6 꼬리: %s' % k6out.strip().splitlines()[-2:], flush=True)
            else:
                print('  k6 출력이 비었다. 원문: %s' % k6log, flush=True)
        out['rows'].append(s)
        for k in ('접수/s', '체결/s', '수용거절/s', 'p99(ms)', '시퀀서 큐', 'DB CPU%'):
            v = s.get(k)
            print('  %-12s %s' % (k, '-' if v is None else round(v, 2)), flush=True)
        print('  종료 상태 %s' % s['db_after'], flush=True)

    Path(out_path).write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding='utf-8')

    keys = ['제공/s', 'k6추정/s', '전체요청/s', '접수/s', '비201/s', '체결/s', '수용거절/s',
            'p95(ms)', 'p99(ms)', '시퀀서 큐',
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
