"""Q8 수수료 행 결합 실행기(ADR 0034 S7, docs/reference/architecture/마켓분할_리더_S7_설계.md 5-2 Q8, 결정 10). 폐기용
스택(exchange-pml)에서만 돈다. 아직 스택에서 돌린 적이 없다(NOT RUN). 판정은 s7_axes.py fee-coupling 이 이 파일의 출력으로 한다.

P3 에서 p0 마켓 수수료는 KRW FEE_INCOME 샤드 0, p1 마켓 수수료는 샤드 1 에 쌓인다. p0 소유 노드(api-1)가 샤드 0 행을 쥔 채 멈춰도
p1 정산이 기다리지 않는지 본다.
  1. 봇을 멈추고 k6 개방 루프 둘(p0 마켓만, p1 마켓만, MARKET_HINT=1)을 LB 로 돌린다. 두 루프의 부하 계정은 따로 발급해 사용자
     행이 겹치지 않는다(--control 이면 한 계정 묶음을 두 루프가 같이 써 겹치게 한다. 대조 칸이고 판정하지 않는다). 키 파일은 저장소
     밖 임시 폴더에 쓰고 끝나면 지운다.
  2. pg-api1 에 지연 100ms 를 넣는다(Toxiproxy). 정산 트랜잭션이 길어져 잠금을 잡기 쉽다.
  3. DB 컨테이너 안의 psql 세션 하나가 20ms 간격(\\watch)으로 pgrowlocks('balances') 를 KRW FEE_INCOME 샤드 0 잔고 행과
     pg_stat_activity 에 이어 본다. application_name = 'chaos-api-1' 세션이 그 행을 쥔 줄을 보면 곧바로 docker pause chaos-api-1 을
     하고, 1초 뒤 한 번 더 떠서 같은 세션이 여전히 쥐고 있는지(양성 축) 본다. 쥐고 있지 않으면(배치가 먼저 끝났다) 풀고 10초 뒤 다시
     한다. 다섯 번 모두 못 잡으면 NOT RUN 이다. 잡았으면 멈춘 지 --pause-s(3초) 뒤 unpause 한다.
  4. 멈춘 창의 p1 마켓 체결 수, KRW 샤드 1 잔고의 증가, api-2 의 exchange_settlement_transient_exhausted_total 과 치명 강등 증분,
     p1 마켓 정산 지연(명령 created_at 에서 체결 이벤트 created_at 까지)의 창 최대와 기준선(멈추기 전 60초) 최대, 창 안 리스 획득
     (인계) 여부를 적는다.
3초는 리스 TTL 6초에서 갱신 주기 2초를 뺀 4초보다 짧아 멈춘 동안 p0 리스가 만료되지 않고, 잠금 대기 상한 2초보다 길다.
정리(finally): 토식 제거, k6 컨테이너 정지·삭제, 임시 키 폴더 삭제, 멈춘 노드 unpause. 봇은 멈춘 채 둔다(실행기가 스택을 내린다).

사용:
  python harness/scale/s7_fee_coupling.py --pause-s 3 --latency-ms 100 --out <json> [--control] [--rate 20]
종료코드: 0 수집 완료(판정은 s7_axes.py), 2 가드 위반이나 전제 불성립, 3 NOT RUN(다섯 번 모두 잠금을 못 잡음), 1 그 밖의 실패.
"""
import argparse
import json
import os
import queue
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pg_samplers as pgs  # noqa: E402
import protocol_latch as pl  # noqa: E402
import s7_axes as s7  # noqa: E402
import s7_double_spend as ds  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
PROJECT = os.environ.get('CHAOS_PROJECT') or 'exchange-pml'
NETWORK = PROJECT + '_chaos-net'
TOXI = os.environ.get('CHAOS_TOXI_URL') or 'http://localhost:58474'
UA = 'chaos-harness'
K6_IMAGE = 'grafana/k6:1.4.0'
ATTEMPTS = 5
LOCK_WAIT_S = 60
BASELINE_S = 60
LOCK_LINE = re.compile(r'^LOCK\|([^|]*)\|(\d+)$')
FEE0_SQL = ("SELECT a.id FROM accounts a JOIN assets s ON s.id = a.asset_id WHERE a.owner_type = 'SYSTEM' "
            "AND a.purpose = 'FEE_INCOME' AND a.shard = %d AND s.symbol = 'KRW'")


# ---------------------------------------------------------------- 순수 함수(test_s7_executors.py)

def parse_lock_line(line):
    """표본 줄 'LOCK|<application_name>|<pid>' 를 (이름, pid) 로. 다른 줄은 None 이다."""
    m = LOCK_LINE.match((line or '').strip())
    return (m.group(1), int(m.group(2))) if m else None


def lock_query(account_id):
    """KRW FEE_INCOME 샤드 0 잔고 행을 쥔 세션. 표본마다 'TICK' 한 줄을 더해 표본기가 살아 있음을 알린다."""
    return ("SELECT 'LOCK|' || coalesce(a.application_name, '') || '|' || a.pid FROM pgrowlocks('balances') l "
            "JOIN balances b ON b.ctid = l.locked_row JOIN pg_stat_activity a ON a.pid = ANY (l.pids) "
            "WHERE b.account_id = %d UNION ALL SELECT 'TICK|' || to_char(clock_timestamp(), 'HH24:MI:SS.MS')" % int(account_id))


def settle_max(rows):
    """[{'lag_s': 초}] 의 최대. 비었으면 None 이다."""
    vals = [float(r['lag_s']) for r in rows or [] if r.get('lag_s') is not None]
    return max(vals) if vals else None


# ---------------------------------------------------------------- 채집

def psql_json(sql):
    parsed, err = pgs.parse_json_row(*pgs.run_psql(sql, timeout=60))
    return parsed, err


def scalar(sql):
    rc, out, _ = pgs.run_psql(sql, timeout=60)
    return out.strip() if rc == 0 else None


def issue_keys(markets, count, path):
    """부하 계정을 그 마켓으로 발급해 path 에 쓴다(저장소 밖 임시 폴더). 반환은 계정 수다. 키는 출력하지 않는다."""
    url = ('http://localhost:18080/harness/v1/loadtest-keys?market=' + urllib.parse.quote(markets)
           + '&count=%d&base=1000000&quote=1000000000' % count)
    for _ in range(6):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, method='POST'), timeout=90) as r:
                keys = json.load(r)
            Path(path).write_text(json.dumps(keys), encoding='utf-8')
            return len(keys)
        except Exception:  # noqa: BLE001
            time.sleep(5)
    return 0


def start_loop(name, markets, key_dir, key_file, rate, duration_s, run_id):
    load = str(ROOT / 'harness' / 'load')
    subprocess.run(['docker', 'rm', '-f', name], capture_output=True, timeout=60)
    args = ['docker', 'run', '-d', '--name', name, '--network', NETWORK, '-v', load + ':/load:ro', '-v', key_dir + ':/keys:ro',
            '-e', 'BASE_OPS=%d' % rate, '-e', 'MARKETS=' + markets, '-e', 'MARKET_HINT=1',
            '-e', 'CAL_DURATION=%ds' % duration_s, '-e', 'BASE_URL=http://chaos-lb:8080',
            '-e', 'K6_KEYS=/keys/' + key_file, '-e', 'CAL_OUT=/tmp/calibration.json', '-e', 'RUN_ID=' + run_id,
            K6_IMAGE, 'run', '--quiet', '--no-color', '/load/calibrate.js']
    return subprocess.run(args, capture_output=True, timeout=120).returncode


def stop_loop(name):
    subprocess.run(['docker', 'stop', '-t', '10', name], capture_output=True, timeout=60)
    subprocess.run(['docker', 'rm', '-f', name], capture_output=True, timeout=60)


def toxic(method, path, body=None):
    data = json.dumps(body).encode('utf-8') if body is not None else None
    req = urllib.request.Request(TOXI + path, data=data, method=method, headers={'User-Agent': UA,
                                                                                'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status
    except Exception as e:  # noqa: BLE001
        return type(e).__name__


class LockSampler:
    """DB 컨테이너 안에서 도는 psql 한 세션(\\watch 0.02). 줄마다 (호스트 초, 줄) 을 큐에 넣는다."""

    def __init__(self, account_id):
        self.q = queue.Queue()
        self.p = subprocess.Popen(['docker', 'exec', '-i', 'chaos-pg', 'psql', '-U', 'exchange', '-d', 'exchange', '-X', '-q',
                                   '-t', '-A'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                  text=True, encoding='utf-8', errors='replace', bufsize=1)
        self.p.stdin.write(lock_query(account_id) + ' \\watch 0.02\n')
        self.p.stdin.flush()
        self.t = threading.Thread(target=self._read, daemon=True)
        self.t.start()

    def _read(self):
        for line in self.p.stdout:
            self.q.put((time.time(), line.rstrip('\n')))

    def wait_lock(self, app, limit_s):
        """app 세션이 행을 쥔 첫 줄을 기다린다. 반환은 (호스트 초, pid, 본 표본 수) 나 None 이다."""
        end = time.monotonic() + limit_s
        ticks = 0
        while time.monotonic() < end:
            try:
                at, line = self.q.get(timeout=0.5)
            except queue.Empty:
                continue
            if line.startswith('TICK|'):
                ticks += 1
            got = parse_lock_line(line)
            if got and got[0] == app:
                return at, got[1], ticks
        return None

    def close(self):
        try:
            self.p.kill()
        except OSError:
            pass


def lock_holders(account_id):
    rc, out, _ = pgs.run_psql(lock_query(account_id), timeout=20)
    return [x for x in (parse_lock_line(line) for line in out.splitlines()) if x] if rc == 0 else None


def partition_symbols():
    rows = s7.partition_markets() or {}
    return {r: list(rows.get(r) or []) for r in s7.RESOURCES}


def settle_rows(markets, since_s, until_s):
    names = ','.join("'%s'" % m for m in markets)
    sql = ("SELECT coalesce(json_agg(json_build_object('lag_s', EXTRACT(EPOCH FROM (e.created_at - c.created_at)))), '[]'::json) "
           "FROM event_journal e JOIN command_journal c ON c.seq = e.source_cmd_seq JOIN markets m ON m.id = e.market_id "
           "WHERE e.event_type = 'TRADE' AND m.symbol IN (%s) AND c.created_at >= to_timestamp(%.3f) "
           "AND c.created_at <= to_timestamp(%.3f)" % (names, since_s, until_s))
    rows, _ = psql_json(sql)
    return rows


def trades_between(markets, since_s, until_s):
    names = ','.join("'%s'" % m for m in markets)
    v = scalar("SELECT count(*) FROM event_journal e JOIN markets m ON m.id = e.market_id WHERE e.event_type = 'TRADE' "
               "AND m.symbol IN (%s) AND e.created_at >= to_timestamp(%.3f) AND e.created_at <= to_timestamp(%.3f)"
               % (names, since_s, until_s))
    return int(v) if v not in (None, '') else None


def acquires_between(since_s, until_s):
    v = scalar("SELECT count(*) FROM lease_acquisitions WHERE origin = 'ACQUIRE' AND acquired_at >= to_timestamp(%.3f) "
               "AND acquired_at <= to_timestamp(%.3f)" % (since_s, until_s))
    return int(v) if v not in (None, '') else None


def api2_counters():
    try:
        with urllib.request.urlopen('http://localhost:28080/actuator/prometheus', timeout=10) as r:
            text = r.read().decode('utf-8', 'replace')
    except Exception:  # noqa: BLE001
        return None
    exhausted = sum(float(v) for v in re.findall(r'^exchange_settlement_transient_exhausted_total(?:\{[^}]*\})?\s+(\S+)',
                                                  text, re.M))
    return {'exhausted': exhausted, 'demotions': ds.parse_node_counters(text)['demotions']}


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--pause-s', type=float, default=3.0)
    ap.add_argument('--latency-ms', type=int, default=100)
    ap.add_argument('--rate', type=int, default=20, help='루프마다 주문 도착률(/s)')
    ap.add_argument('--control', action='store_true', help='대조 칸. 두 루프가 한 계정 묶음을 같이 쓴다(판정하지 않는다)')
    ap.add_argument('--out', required=True)
    a = ap.parse_args(argv)
    res = {'started': pgs.now_iso(), 'control': a.control, 'pause_s': a.pause_s, 'latency_ms': a.latency_ms,
           'attempts': 0, 'error': None}
    code = 1
    key_dir = None
    loops = []
    paused = False
    try:
        g = pl.guard()
        if not g['ok']:
            res['error'] = '가드 위반: ' + '; '.join(g['problems'])
            return 2
        if s7.assignment(s7.all_partitions())[0] != 'ok':
            res['error'] = '선호 배정이 아니다. Q0 준비를 먼저 한다'
            return 2
        parts = partition_symbols()
        fee0 = scalar(FEE0_SQL % 0)
        fee1 = scalar(FEE0_SQL % 1)
        if not fee0 or not fee1:
            res['error'] = 'KRW FEE_INCOME 샤드 0·1 계정을 찾지 못했다'
            return 2
        res['partition_markets'] = parts
        res['bots_stop_rc'] = subprocess.run(['docker', 'stop', 'chaos-bots'], capture_output=True, timeout=120).returncode
        pgs.run_psql('CREATE EXTENSION IF NOT EXISTS pgrowlocks', timeout=60)
        key_dir = tempfile.mkdtemp(prefix='s7fee-')
        p0, p1 = ','.join(parts['matching-p0']), ','.join(parts['matching-p1'])
        if a.control:
            n = issue_keys(p0 + ',' + p1, 4, os.path.join(key_dir, 'shared.json'))
            files = ('shared.json', 'shared.json')
        else:
            n = issue_keys(p0, 4, os.path.join(key_dir, 'p0.json')) and issue_keys(p1, 4, os.path.join(key_dir, 'p1.json'))
            files = ('p0.json', 'p1.json')
        if not n:
            res['error'] = '부하 계정 발급 실패'
            return 1
        duration = int(ATTEMPTS * (LOCK_WAIT_S + 15) + BASELINE_S + 120)
        for name, markets, key_file, rid in (('pml-s7-fee-p0', p0, files[0], 's7feep0'),
                                             ('pml-s7-fee-p1', p1, files[1], 's7feep1')):
            rc = start_loop(name, markets, key_dir, key_file, a.rate, duration, rid)
            loops.append(name)
            if rc != 0:
                res['error'] = 'k6 루프 기동 실패(%s rc=%s)' % (name, rc)
                return 1
        res['toxic_add'] = toxic('POST', '/proxies/pg-api1/toxics', {'name': 's7-fee-latency', 'type': 'latency',
                                                                     'stream': 'downstream',
                                                                     'attributes': {'latency': a.latency_ms}})
        before = api2_counters()
        time.sleep(BASELINE_S)
        caught = None
        for attempt in range(1, ATTEMPTS + 1):
            res['attempts'] = attempt
            sampler = LockSampler(fee0)
            got = sampler.wait_lock('chaos-api-1', LOCK_WAIT_S)
            if not got:
                sampler.close()
                res.setdefault('misses', []).append({'attempt': attempt, 'reason': '%d초 안에 잠금 표본 없음' % LOCK_WAIT_S})
                continue
            seen_at, pid, ticks = got
            subprocess.run(['docker', 'pause', 'chaos-api-1'], capture_output=True, timeout=30)
            pause_at = time.time()
            paused = True
            sampler.close()
            shard1_at_pause = scalar('SELECT balance FROM balances WHERE account_id = %s' % fee1)
            time.sleep(1.0)
            holders = lock_holders(fee0)
            held = holders is not None and ('chaos-api-1', pid) in holders
            record = {'attempt': attempt, 'seen_at': seen_at, 'pause_at': pause_at, 'pid': pid, 'ticks_before': ticks,
                      'holders_after_1s': holders, 'held': held}
            if not held:
                subprocess.run(['docker', 'unpause', 'chaos-api-1'], capture_output=True, timeout=30)
                paused = False
                res.setdefault('misses', []).append(record)
                time.sleep(10)
                continue
            time.sleep(max(0.0, pause_at + a.pause_s - time.time()))
            shard1_at_unpause = scalar('SELECT balance FROM balances WHERE account_id = %s' % fee1)
            unpause_at = time.time()
            subprocess.run(['docker', 'unpause', 'chaos-api-1'], capture_output=True, timeout=30)
            paused = False
            record.update(unpause_at=unpause_at, shard1_at_pause=shard1_at_pause, shard1_at_unpause=shard1_at_unpause)
            caught = record
            break
        res['caught'] = caught
        res['lock_seen_after_pause'] = bool(caught)
        if not caught:
            res['error'] = 'NOT RUN: %d번 모두 멈춘 세션의 수수료 행 잠금을 잡지 못했다' % ATTEMPTS
            code = 3
            return code
        time.sleep(10)
        after = api2_counters()
        pause_at, unpause_at = caught['pause_at'], caught['unpause_at']
        p1_markets = parts['matching-p1']
        res['p1_trades_during'] = trades_between(p1_markets, pause_at, unpause_at)
        s_before, s_after = caught.get('shard1_at_pause'), caught.get('shard1_at_unpause')
        res['krw_shard1_increased'] = (s_before not in (None, '') and s_after not in (None, '')
                                       and float(s_after) > float(s_before))
        res['krw_shard1'] = {'at_pause': s_before, 'at_unpause': s_after}
        res['api2_transient_exhausted_delta'] = (after['exhausted'] - before['exhausted']) if before and after else None
        res['api2_fatal_demotions_delta'] = ds.fatal_delta(before['demotions'] if before else None,
                                                           after['demotions'] if after else None)
        res['p1_settle_baseline_max_s'] = settle_max(settle_rows(p1_markets, pause_at - BASELINE_S, pause_at))
        res['p1_settle_during_max_s'] = settle_max(settle_rows(p1_markets, pause_at, unpause_at))
        acquires = acquires_between(pause_at, unpause_at + 10)
        res['lease_acquires_in_window'] = acquires
        res['lease_handover'] = None if acquires is None else acquires > 0
        code = 0
        return code
    except Exception as e:  # noqa: BLE001 - 수집 실패도 파일에 남긴다
        res['error'] = '%s: %s' % (type(e).__name__, e)
        return 1
    finally:
        if paused:
            subprocess.run(['docker', 'unpause', 'chaos-api-1'], capture_output=True, timeout=30)
        res['toxic_remove'] = toxic('DELETE', '/proxies/pg-api1/toxics/s7-fee-latency')
        for name in loops:
            stop_loop(name)
        if key_dir:
            shutil.rmtree(key_dir, ignore_errors=True)
        res['finished'] = pgs.now_iso()
        Path(a.out).write_text(json.dumps(res, ensure_ascii=False, indent=2, default=str), encoding='utf-8')
        print(json.dumps({k: res.get(k) for k in ('attempts', 'lock_seen_after_pause', 'p1_trades_during',
                                                  'lease_handover', 'error')}, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    sys.exit(main())
