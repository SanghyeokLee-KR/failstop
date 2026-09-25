"""Patroni 실험 관측기(Patroni 설계 8-1 관측 주기, 8-2 시각). 실행기가 주입 전부터 끝까지 띄워 두는 호스트 프로세스다.

표본은 JSONL 한 줄에 하나다. 모든 시각은 호스트 벽시계 UTC(epoch 초)다.
  cluster   0.5초  Patroni REST /cluster(두 노드 가운데 답하는 쪽). T_promote·T_sync 의 원천
  status    1초    노드별 공개 상태 API. LB 경유 상태는 2초(상태 API 는 IP 당 분당 120회 상한이다)
  read      1초    조회 대표 경로(시장, 호가, 체결, 계좌, 미체결 주문)를 LB 로. T_reads 와 조회 비율의 원천
  write     1초    인증 없는 주문 POST 를 LB 로. 게이트가 인증(order 10)보다 앞(order 4)이라 ACTIVE 면 401, SUSPENDED 면
                   423, UNREACHABLE 이면 503 이다. 쓰기의 429 는 게이트 위치가 틀렸다는 신호다(검토 R6)
  haproxy   1초    앱 노드 HAProxy 의 pg_write 서버 상태. T_down 의 원천
  pool      1초    (--pool) 앱 노드의 풀 활성 연결 수와 수용 in-flight(E-P2a·E-P5)
  withdraw  1초    (--withdraw) 출금 요청기. 423·5xx·시간 초과를 받은 Idempotency-Key 는 버리지 않고, 게이트를 지난 응답을 본 뒤
                   같은 키로 다시 낸다. 새 키로 바꿔 내지 않는다(API 설계 §2-5-d)

돌리는 법(실행기 ep-lib.ps1 이 부른다):
  python harness/scale/patroni_observe.py --out <dir>/observe.jsonl --stop-file <dir>/observe.stop --keys <dir>/keys.json
시험:
  python -m unittest discover -s harness/scale -p "test_*.py"
"""
import argparse
import base64
import hashlib
import hmac
import json
import os
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

LB = 'http://127.0.0.1:59180'
NODES = (('api-1', 'http://127.0.0.1:59181'), ('api-2', 'http://127.0.0.1:59182'))
PATRONI = ('http://127.0.0.1:59008', 'http://127.0.0.1:59009')
HAPROXY = (('haproxy-1', 'http://127.0.0.1:59404'), ('haproxy-2', 'http://127.0.0.1:59405'))
MARKET = 'KRW-BTC'
TIMEOUT_S = 2.0
PUBLIC_READS = (('markets', '/api/v1/markets'), ('orderbook', '/api/v1/orderbook?market=' + MARKET),
                ('trades', '/api/v1/trades?market=' + MARKET + '&count=20'))
SIGNED_READS = (('accounts', '/api/v1/accounts', {}), ('open_orders', '/api/v1/orders/open', {'market': MARKET}))
HAPROXY_UP = re.compile(r'^haproxy_server_status\{([^}]*)\}\s+(\S+)', re.M)
METRIC_LINE = re.compile(r'^(hikaricp_connections_active|exchange_admission_in_flight)\{([^}]*)\}\s+(\S+)', re.M)
LABEL = re.compile(r'(\w+)="([^"]*)"')
# 출금 요청기. 부하가 아니라 멱등 규약의 표본이다. 금액은 작게 둔다(가입 지급액 안에서 주행 내내 낼 수 있게).
WITHDRAW_AMOUNT = '1000'


class Journal:
    def __init__(self, path):
        self._lock = threading.Lock()
        self._fh = open(path, 'a', encoding='utf-8', buffering=1)

    def write(self, record):
        line = json.dumps(record, ensure_ascii=False, sort_keys=True)
        with self._lock:
            self._fh.write(line + '\n')

    def close(self):
        with self._lock:
            self._fh.close()


def b64url(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b'=').decode('ascii')


def canonical(method, path, query=None, body=''):
    """API 설계 §3-3. 쿼리는 디코딩한 값을 키 오름차순으로, 바디는 이미 정규형인 문자열 그대로."""
    if query:
        return method + '\n' + path + '\n' + '&'.join(f'{k}={query[k]}' for k in sorted(query))
    return method + '\n' + path + '\n' + (body or '')


def sign(method, path, access_key, secret, query=None, body='', now_ms=None, nonce=None):
    payload = {'access_key': access_key, 'nonce': nonce or ('obs-' + uuid.uuid4().hex),
               'timestamp': now_ms if now_ms is not None else int(time.time() * 1000),
               'query_hash': hashlib.sha512(canonical(method, path, query, body).encode('utf-8')).hexdigest(),
               'query_hash_alg': 'SHA512'}
    header = {'alg': 'HS256', 'typ': 'JWT'}
    signing_input = b64url(json.dumps(header, separators=(',', ':')).encode()) + '.' + \
        b64url(json.dumps(payload, separators=(',', ':')).encode())
    sig = hmac.new(secret.encode('utf-8'), signing_input.encode('ascii'), hashlib.sha256).digest()
    return 'Bearer ' + signing_input + '.' + b64url(sig)


def request(method, url, headers=None, body=None, timeout=TIMEOUT_S, opener=urllib.request.urlopen):
    """(상태, 바디 bytes, 소요 ms, 헤더). 연결 실패·시간 초과는 상태 0 이다."""
    data = body.encode('utf-8') if isinstance(body, str) else body
    req = urllib.request.Request(url, data=data, method=method)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    t0 = time.monotonic()
    try:
        with opener(req, timeout=timeout) as r:
            return r.status, r.read(), (time.monotonic() - t0) * 1000, dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read(), (time.monotonic() - t0) * 1000, dict(e.headers or {})
    except Exception as e:
        return 0, type(e).__name__.encode(), (time.monotonic() - t0) * 1000, {}


def parse_json(raw):
    try:
        return json.loads(raw.decode('utf-8', 'replace')) if raw else None
    except ValueError:
        return None


def error_name(raw):
    body = parse_json(raw)
    if isinstance(body, dict) and isinstance(body.get('error'), dict):
        return body['error'].get('name')
    return None


def cluster_sample(t, code, raw, src):
    rec = {'kind': 'cluster', 't': t, 'src': src, 'code': code}
    body = parse_json(raw) if code == 200 else None
    if not isinstance(body, dict):
        return rec
    members = []
    for m in body.get('members', []):
        members.append({k: m.get(k) for k in ('name', 'role', 'state', 'timeline', 'lag', 'host')})
    rec['members'] = members
    rec['leader'] = next((m['name'] for m in members if m.get('role') in ('leader', 'standby_leader')), None)
    rec['sync'] = sorted(m['name'] for m in members if m.get('role') in ('sync_standby', 'quorum_standby'))
    timelines = [m.get('timeline') for m in members if m.get('role') == 'leader' and m.get('timeline') is not None]
    rec['timeline'] = max(timelines) if timelines else None
    return rec


def status_sample(t, node, code, raw):
    rec = {'kind': 'status', 't': t, 'node': node, 'code': code}
    body = parse_json(raw) if code == 200 else None
    if isinstance(body, dict):
        rec.update(trading_suspended=body.get('trading_suspended'), state=body.get('trading_state'),
                   reason=body.get('reason'), since=body.get('since'))
    return rec


def haproxy_sample(t, node, code, text):
    """pg_write 백엔드에서 UP 인 서버 목록."""
    rec = {'kind': 'haproxy', 't': t, 'node': node, 'code': code}
    if code != 200:
        return rec
    up = []
    for labels, value in HAPROXY_UP.findall(text):
        lab = dict(LABEL.findall(labels))
        if lab.get('proxy') == 'pg_write' and lab.get('state') == 'UP' and float(value) == 1.0:
            up.append(lab.get('server'))
    rec['up'] = sorted(up)
    return rec


def pool_sample(t, node, code, text):
    rec = {'kind': 'pool', 't': t, 'node': node, 'code': code}
    if code != 200:
        return rec
    active, inflight = {}, {}
    for name, labels, value in METRIC_LINE.findall(text):
        lab = dict(LABEL.findall(labels))
        if name == 'hikaricp_connections_active':
            active[lab.get('pool', '?')] = float(value)
        else:
            inflight[lab.get('scope', '?')] = float(value)
    rec.update(active=active, inflight=inflight)
    return rec


class Observer:
    def __init__(self, journal, stop, keys=None, lb=LB, nodes=NODES, patroni=PATRONI, haproxy=HAPROXY,
                 http=request, wall=time.time, sleep=None):
        self.journal = journal
        self.stop = stop
        self.keys = keys or []
        self.lb, self.nodes, self.patroni, self.haproxy = lb, nodes, patroni, haproxy
        self.http = http
        self.wall = wall
        self.sleep = sleep or stop.wait

    def every(self, period, fn):
        """period 마다 fn 을 부른다. 한 번이 길어도 다음 시각을 밀어 겹치지 않게 한다."""
        next_at = time.monotonic()
        while not self.stop.is_set():
            try:
                fn()
            except Exception as e:  # 관측기는 멈추지 않는다. 표본 공백으로 남긴다
                self.journal.write({'kind': 'error', 't': round(self.wall(), 3), 'where': fn.__name__,
                                    'error': repr(e)[:200]})
            next_at += period
            delay = next_at - time.monotonic()
            if delay < 0:
                next_at = time.monotonic()
                delay = 0
            self.sleep(delay)

    def cluster(self):
        for base in self.patroni:
            t = round(self.wall(), 3)
            code, raw, _, _ = self.http('GET', base + '/cluster')
            if code == 200:
                self.journal.write(cluster_sample(t, code, raw, base[-5:]))
                return
        self.journal.write({'kind': 'cluster', 't': t, 'src': None, 'code': code})

    def status(self):
        for node, base in self.nodes:
            t = round(self.wall(), 3)
            code, raw, _, _ = self.http('GET', base + '/api/v1/status')
            self.journal.write(status_sample(t, node, code, raw))

    def status_lb(self):
        t = round(self.wall(), 3)
        code, raw, _, _ = self.http('GET', self.lb + '/api/v1/status')
        self.journal.write(status_sample(t, 'lb', code, raw))

    def reads(self):
        for name, path in PUBLIC_READS:
            t = round(self.wall(), 3)
            code, _, ms, _ = self.http('GET', self.lb + path)
            self.journal.write({'kind': 'read', 't': t, 'path': name, 'code': code, 'ms': round(ms, 1)})
        if not self.keys:
            return
        key = self.keys[0]
        for name, path, query in SIGNED_READS:
            t = round(self.wall(), 3)
            url = self.lb + path + (('?' + urllib.parse.urlencode(query)) if query else '')
            auth = sign('GET', path, key['access_key'], key['secret'], query=query)
            code, _, ms, _ = self.http('GET', url, headers={'Authorization': auth})
            self.journal.write({'kind': 'read', 't': t, 'path': name, 'code': code, 'ms': round(ms, 1)})

    def write(self):
        t = round(self.wall(), 3)
        code, raw, ms, _ = self.http('POST', self.lb + '/api/v1/orders', headers={'Content-Type': 'application/json'},
                                     body='{}')
        self.journal.write({'kind': 'write', 't': t, 'code': code, 'name': error_name(raw), 'ms': round(ms, 1)})

    def haproxy_state(self):
        for node, base in self.haproxy:
            t = round(self.wall(), 3)
            code, raw, _, _ = self.http('GET', base + '/metrics')
            self.journal.write(haproxy_sample(t, node, code, raw.decode('utf-8', 'replace') if code == 200 else ''))

    def pools(self):
        for node, base in self.nodes:
            t = round(self.wall(), 3)
            code, raw, _, _ = self.http('GET', base + '/actuator/prometheus')
            self.journal.write(pool_sample(t, node, code, raw.decode('utf-8', 'replace') if code == 200 else ''))


class WithdrawRequester:
    """출금 요청기(설계 8-1). 초당 한 건. 423·5xx·시간 초과를 받은 키는 들고 있다가 게이트를 지난 응답을 본 뒤 같은 키로 다시 낸다."""

    def __init__(self, journal, stop, token, lb=LB, http=request, wall=time.time, new_key=None):
        self.journal = journal
        self.stop = stop
        self.token = token
        self.lb = lb
        self.http = http
        self.wall = wall
        self.new_key = new_key or (lambda: 'wd-' + uuid.uuid4().hex)
        self.pending = []
        self.writable = True

    def send(self, key, resend):
        body = json.dumps({'direction': 'WITHDRAW', 'asset': 'KRW', 'amount': WITHDRAW_AMOUNT}, separators=(',', ':'))
        t = round(self.wall(), 3)
        code, raw, ms, headers = self.http('POST', self.lb + '/api/v1/transfers', body=body, headers={
            'Content-Type': 'application/json', 'Authorization': 'Bearer ' + self.token, 'Idempotency-Key': key})
        replay = str(headers.get('X-Idempotent-Replay', '')).lower() == 'true'
        self.journal.write({'kind': 'withdraw', 't': t, 'key': key, 'resend': resend, 'code': code,
                            'name': error_name(raw), 'replay': replay, 'ms': round(ms, 1)})
        return code

    @staticmethod
    def uncertain(code):
        """결과를 모르거나 이 시도가 쓰이지 않은 응답. 같은 키로 다시 내야 한다."""
        return code == 423 or code == 0 or code >= 500

    RESEND_PER_TICK = 5

    def tick(self):
        # 게이트를 지난 응답을 봤으면 쥔 키부터 같은 키로 다시 낸다. 한 틱에 몇 개씩 내고, 그 틱에는 새 요청을 내지 않는다.
        if self.pending and self.writable:
            for _ in range(min(self.RESEND_PER_TICK, len(self.pending))):
                key = self.pending.pop(0)
                code = self.send(key, True)
                if self.uncertain(code):
                    self.pending.insert(0, key)
                    self.writable = False
                    break
            return
        key = self.new_key()
        code = self.send(key, False)
        if self.uncertain(code):
            self.pending.append(key)
            self.writable = False
        elif code != 429:
            self.writable = True

    def finish(self):
        self.journal.write({'kind': 'withdraw_end', 't': round(self.wall(), 3), 'pending': list(self.pending)})


def member_session(lb=LB, http=request, email=None):
    """출금 요청기용 회원. 가입 응답의 access_token(15분)을 쓴다. 주행은 그보다 짧다."""
    email = email or ('ep-withdraw-%s@demo.test' % uuid.uuid4().hex[:10])
    body = json.dumps({'email': email, 'password': 'ep-withdraw-1', 'display_name': 'EP 출금'})
    code, raw, _, _ = http('POST', lb + '/api/v1/auth/signup', body=body, headers={'Content-Type': 'application/json'},
                           timeout=10)
    data = parse_json(raw)
    if code != 201 or not isinstance(data, dict) or not data.get('access_token'):
        raise RuntimeError('출금 요청기 가입 실패: %s %s' % (code, (raw or b'')[:200]))
    return data['access_token'], email


def main(argv=None):
    parser = argparse.ArgumentParser(description='Patroni 실험 관측기')
    parser.add_argument('--out', required=True)
    parser.add_argument('--stop-file', required=True)
    parser.add_argument('--keys', help='loadtest-keys 응답 JSON. 서명 조회(계좌, 미체결 주문)에 첫 키를 쓴다')
    parser.add_argument('--pool', action='store_true')
    parser.add_argument('--withdraw', action='store_true')
    parser.add_argument('--withdraw-stop-file', help='이 파일이 생기면 출금 요청기만 멈춘다(사후 run 전 쓰기 정지)')
    args = parser.parse_args(argv)
    keys = []
    if args.keys:
        with open(args.keys, encoding='utf-8') as fh:
            keys = json.load(fh)
    journal = Journal(args.out)
    stop = threading.Event()
    obs = Observer(journal, stop, keys=keys)
    journal.write({'kind': 'start', 't': round(time.time(), 3), 'pool': args.pool, 'withdraw': args.withdraw,
                   'signed_reads': bool(keys)})
    loops = [(0.5, obs.cluster), (1.0, obs.status), (2.0, obs.status_lb), (1.0, obs.reads), (1.0, obs.write),
             (1.0, obs.haproxy_state)]
    if args.pool:
        loops.append((1.0, obs.pools))
    threads = [threading.Thread(target=obs.every, args=(p, fn), name='obs-' + fn.__name__, daemon=True)
               for p, fn in loops]
    wd_stop = threading.Event()
    requester = None
    if args.withdraw:
        token, email = member_session()
        journal.write({'kind': 'withdraw_start', 't': round(time.time(), 3), 'email': email})
        requester = WithdrawRequester(journal, wd_stop, token)
        wd_obs = Observer(journal, wd_stop)
        threads.append(threading.Thread(target=wd_obs.every, args=(1.0, requester.tick), name='obs-withdraw',
                                        daemon=True))
    for th in threads:
        th.start()
    while not stop.is_set():
        if args.withdraw_stop_file and os.path.exists(args.withdraw_stop_file) and not wd_stop.is_set():
            wd_stop.set()
            if requester:
                requester.finish()
        if os.path.exists(args.stop_file):
            break
        stop.wait(0.2)
    stop.set()
    if not wd_stop.is_set():
        wd_stop.set()
        if requester:
            requester.finish()
    for th in threads:
        th.join(timeout=5)
    journal.write({'kind': 'stop', 't': round(time.time(), 3)})
    journal.close()
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
