"""조회 노드(app-3) 분산 확인 실행기와 판정기. 폐기용 스택(compose 프로젝트 exchange-pml, 이미지 exchange-chaos-app:pml)에서만 돈다.

사용자가 확정한 앱 계층 모델(2026-09-26)은 app-1·app-2 가 늘 켜진 매칭 리더(조회도 처리)이고 app-3 은 조회 전용(read 프로파일)이며
AWS 에서는 ASG 가 0~1대로 늘리고 줄인다. S5~S7 에서 LB 설정이 파티션·중계용으로 바뀌었으므로 최종 조합(P3 + nginx.partition-relay.conf)
에서 네 가지를 본다. (1) 조회 GET 이 api-3 으로 실제로 나뉘는가, (2) api-3 이 쓰기(주문·취소·출금)를 받지 않고, 직접 받으면 계약대로
404 로 거절하며 아무것도 쓰지 않는가, (3) api-3 을 멈췄다 다시 올려도(0대<->1대 모사) 조회가 오류 없이 api-1·api-2 로 넘어가고 돌아오는가,
(4) api-3 의 WS 방침(S6 설계 4-15 '조회 노드 WS 제외')이 지켜지는가. 판정 기준은 주행 전에 증거 폴더 README 에 적었다
(harness/evidence/pml-app3-read-20260926-*/README.md). 9/23 리허설(harness/evidence/scale-rehearsal-20260923/)과 같은 지표(노드별 조회
요청 비율, 오류율, p95)를 쓴다. 노드별 비율은 두 원천으로 센다. LB 접근 로그(요청마다 User-Agent 에 단계 표식을 싣고 ups 의 마지막
주소로 처리 노드를 가른다)가 판정 원천이고, 9/23 의 readsplit3.py 방식(노드별 http_server_requests_seconds_count{uri} 증가분)은 기록이다.

하위 명령.
  run    스택이 선 뒤(P3 전환과 Q0 준비, LB 사본 마운트, api-3 컨테이너 생성까지는 실행기 run-app3.ps1 이 한다) 단계를 돌리고 facts.json 을 쓴다.
  judge  facts.json 과 실행기가 뜬 사후 권위 run(post_run.json)으로 verdict.json 을 쓴다.

회원 토큰·비밀번호와 조회 계정 비밀번호는 출력하지 않는다. 금액은 Decimal 문자열로만 다룬다.
"""
import argparse
import base64
import collections
import datetime as dt
import gzip
import hashlib
import http.client
import json
import math
import os
import re
import secrets
import socket
import subprocess
import sys
import threading
import time
import uuid
from decimal import ROUND_UP, Decimal
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / 'asg'))

LB_PORT = 58080
API3 = 'chaos-api-3'
NODES = ('chaos-api-1', 'chaos-api-2', API3)
APP_PORTS = {'chaos-api-1': 18080, 'chaos-api-2': 28080, API3: 38080}
METRICS_PORTS = {'chaos-api-1': 18080, 'chaos-api-2': 28080, API3: 38081}
LB_CONTAINER = 'chaos-lb'
NETWORK = 'exchange-pml_chaos-net'
UA_PREFIX = 'pml-app3-probe/'
HINT = 'X-Exchange-Market'
WS_ORIGIN = 'http://localhost:3000'

# read-load.py 와 같은 세 경로(DB 에서 완결되는 조회). 9/23 의 조회 부하와 같다.
LOAD_PATHS = ('/api/v1/trades?market=KRW-BTC&count=50', '/api/v1/candles/1m?market=KRW-BTC&count=100', '/api/v1/markets')
# readsplit3.py 와 같은 세 창(순차 호출, 조회 부하 없음).
SPLIT_WINDOWS = (
    ('markets', '/api/v1/markets', '/api/v1/markets', 90),
    ('trades', '/api/v1/trades', '/api/v1/trades?market=KRW-BTC&count=3', 90),
    ('orderbook', '/api/v1/orderbook', '/api/v1/orderbook?market=KRW-BTC', 60),
)
READ_URI = re.compile(r'^/api/v1/(markets|trades|candles)')
READ_METHODS = ('GET', 'HEAD', 'OPTIONS')

# 판정 문턱(README '판정 규칙'과 같은 값. 주행 뒤 바꾸지 않는다).
SPLIT_SHARE_MIN = 0.25
LOAD_SHARE_MIN = 0.15
R2_MIN_REQUESTS = 5000
R2B_MIN_REQUESTS = 1000
C_MIN_REQUESTS = 1000
C_OUT_MIN_LINES = 200
C_POST_MIN_LINES = 500
WRITE_MIN = {'POST /api/v1/orders': 50, 'DELETE /api/v1/orders': 10, 'POST /api/v1/transfers': 10, 'DELETE /api/v1/transfers': 10}
PROBE_KIND_MIN = 10
WS_HANDSHAKES = 30
HEALTH_BUDGET_S = 180

LB_LINE = re.compile(
    r'^(?P<addr>\S+) - \S+ \[(?P<time>[^\]]+)\] "(?P<method>[A-Z]+) (?P<uri>\S+) [^"]*" (?P<status>\d{3}) \d+ '
    r'"[^"]*" "(?P<ua>[^"]*)" ups="(?P<ups>[^"]*)" ust="(?P<ust>[^"]*)" hint="[^"]*" route="[^"]*" rt=(?P<rt>\S+)')


# ---------------------------------------------------------------- 순수 함수(test_read_node_probe.py)

def parse_lb_line(line):
    """파티션 형식 접근 로그 한 줄. 형식이 아니면 None 이다."""
    m = LB_LINE.match(line.strip())
    if not m:
        return None
    d = m.groupdict()
    try:
        d['ts'] = dt.datetime.strptime(d['time'], '%d/%b/%Y:%H:%M:%S %z').timestamp()
    except ValueError:
        return None
    d['status'] = int(d['status'])
    d['path'] = d['uri'].split('?', 1)[0]
    d['tried'] = upstream_addrs(d['ups'])
    return d


def upstream_addrs(ups):
    """ups 값의 주소 목록. 재시도는 ', ', 내부 이동은 ' : ' 로 잇는다. '-' 는 업스트림 없음이다."""
    out = []
    for part in re.split(r',\s*|\s+:\s+', ups or ''):
        part = part.strip()
        if part and part != '-':
            out.append(part)
    return out


def node_of(addr, ipmap):
    """'172.x.x.x:8080' 을 컨테이너 이름으로. 모르거나 여러 이름이 겹치면 그 사실을 이름으로 적는다."""
    ip = addr.rsplit(':', 1)[0]
    names = ipmap.get(ip)
    if not names:
        return 'unknown:' + ip
    if len(names) > 1:
        return 'ambiguous:' + ip
    return next(iter(names))


def merge_ipmap(snapshots):
    """주소 표본들을 ip -> 이름 집합으로 합친다. 재기동으로 주소가 바뀌어도 옛 주소를 잃지 않는다."""
    out = collections.defaultdict(set)
    for snap in snapshots:
        for ip, name in (snap.get('map') or {}).items():
            out[ip].add(name)
    return out


def percentile(values, q):
    """가장 가까운 순위(nearest-rank) 백분위. 빈 목록은 None 이다."""
    if not values:
        return None
    s = sorted(values)
    k = max(0, math.ceil(q / 100.0 * len(s)) - 1)
    return s[k]


def summarize_client(records):
    """클라이언트 기록 [(t, phase, idx, status, ms, err)] 의 요약. 200 밖(전송 실패 포함)이 오류다."""
    n = len(records)
    ok = sum(1 for r in records if r[3] == 200)
    by = collections.Counter(str(r[3]) if r[3] else ('transport:' + (r[5] or '?')) for r in records if r[3] != 200)
    ms = [r[4] for r in records]
    span = (max(r[0] for r in records) - min(r[0] for r in records)) if n > 1 else 0.0
    return {
        'requests': n, 'ok_200': ok, 'errors': n - ok, 'error_rate': (round((n - ok) / n, 6) if n else None),
        'errors_by': dict(by), 'p50_ms': percentile(ms, 50), 'p95_ms': percentile(ms, 95), 'p99_ms': percentile(ms, 99),
        'max_ms': (max(ms) if ms else None), 'rps': (round(n / span, 1) if span > 0 else None),
    }


def lb_split(rows, ipmap):
    """LB 줄들의 처리 노드별 수(ups 마지막 주소), 시도에 api-3 이 든 줄 수, api-3 이 처리한 5xx 수."""
    served = collections.Counter()
    tried_api3 = 0
    api3_5xx = 0
    for r in rows:
        tried = [node_of(a, ipmap) for a in r['tried']]
        last = tried[-1] if tried else 'none'
        served[last] += 1
        if API3 in tried:
            tried_api3 += 1
        if last == API3 and r['status'] >= 500:
            api3_5xx += 1
    total = sum(served.values())
    return {'lines': total, 'served': dict(served), 'tried_api3': tried_api3, 'api3_5xx': api3_5xx,
            'api3_share': (round(served.get(API3, 0) / total, 4) if total else None)}


def phase_of(ua):
    return ua[len(UA_PREFIX):] if ua.startswith(UA_PREFIX) else None


def write_key(r):
    """쓰기 줄의 종류 이름. 조회 location 밖의 GET·HEAD·OPTIONS 가 아닌 줄만 쓰기로 센다."""
    if r['method'] in READ_METHODS or READ_URI.match(r['path']):
        return None
    for prefix in ('/api/v1/orders', '/api/v1/transfers'):
        if r['path'] == prefix or r['path'].startswith(prefix + '/'):
            return '%s %s' % (r['method'], prefix)
    return '%s other' % r['method']


def axis(value):
    """판정 축 값. None(읽지 못함)은 거짓이다."""
    return bool(value) if value is not None else False


def group_verdict(axes, positive):
    """축 모음의 판정. 양성 축이 하나라도 거짓이면 NOT RUN, 아니면 모든 축이 참일 때만 PASS."""
    if any(not axis(v) for v in positive.values()):
        return 'NOT RUN'
    return 'PASS' if all(axis(v) for v in axes.values()) else 'FAIL'


def judge(facts, post_run=None):
    """facts.json 과 사후 권위 run 으로 판정한다. 반환은 verdict dict 다."""
    out = {'groups': {}, 'records': {}}
    pre = facts.get('preconditions') or {}
    if not pre or not all(axis(v) for v in pre.values()):
        out['verdict'] = 'BLOCKED'
        out['blocked_by'] = sorted(k for k, v in pre.items() if not axis(v)) or ['preconditions 없음']
        return out
    lb = facts.get('lb') or {}
    ph = lb.get('phases') or {}
    cl = facts.get('client') or {}

    def split_of(name):
        return ph.get(name) or {}

    # (1) 분산
    a_axes, a_pos = {}, {}
    for w in ('markets', 'trades'):
        for run in ('R1', 'R3'):
            s = split_of('%s-%s' % (run, w))
            c = cl.get('%s-%s' % (run, w)) or {}
            a_pos['%s_%s_lines_eq_calls' % (run, w)] = s.get('lines') == c.get('requests') and (c.get('requests') or 0) > 0
            a_axes['%s_%s_api3_share_ge_%s' % (run, w, SPLIT_SHARE_MIN)] = (s.get('api3_share') is not None
                                                                           and s['api3_share'] >= SPLIT_SHARE_MIN)
            a_axes['%s_%s_all_200' % (run, w)] = c.get('errors') == 0
            a_axes['%s_%s_three_nodes_served' % (run, w)] = all((s.get('served') or {}).get(n, 0) > 0 for n in NODES)
    ob = split_of('R1-orderbook')
    a_pos['R1_orderbook_lines_gt_0'] = (ob.get('lines') or 0) > 0
    a_axes['R1_orderbook_api3_tried_0'] = ob.get('tried_api3') == 0 if ob else None
    for run, lo in (('R2', R2_MIN_REQUESTS), ('R2b', R2B_MIN_REQUESTS)):
        s, c = split_of(run), cl.get(run) or {}
        a_pos['%s_requests_ge_%d' % (run, lo)] = (c.get('requests') or 0) >= lo
        a_axes['%s_errors_0' % run] = c.get('errors') == 0
        a_axes['%s_api3_share_ge_%s' % (run, LOAD_SHARE_MIN)] = s.get('api3_share') is not None and s['api3_share'] >= LOAD_SHARE_MIN
    out['groups']['A_read_split'] = {'axes': a_axes, 'positive': a_pos, 'verdict': group_verdict(a_axes, a_pos)}

    # (2) 쓰기
    w = lb.get('writes') or {}
    b_axes, b_pos = {}, {}
    for k, lo in WRITE_MIN.items():
        b_pos['lb_%s_ge_%d' % (k.replace(' ', '_'), lo)] = (w.get('by_kind') or {}).get(k, 0) >= lo
    b_axes['lb_writes_tried_api3_0'] = w.get('tried_api3') == 0 if w else None
    probe = facts.get('write_probe') or {}
    for kind in ('order_create', 'order_cancel', 'withdraw_create', 'withdraw_cancel'):
        b_pos['probe_%s_2xx_ge_%d' % (kind, PROBE_KIND_MIN)] = (probe.get(kind) or {}).get('2xx', 0) >= PROBE_KIND_MIN
    b_axes['probe_no_404_5xx_transport'] = (bool(probe) and all(
        (probe.get(k) or {}).get(s, 0) == 0 for k in ('order_create', 'order_cancel', 'withdraw_create', 'withdraw_cancel')
        for s in ('404', '5xx', 'transport')))
    b_axes['probe_no_exceptions'] = probe.get('errors') == [] if probe else None
    d = facts.get('direct_api3') or {}
    for k in ('order_create', 'order_cancel', 'withdraw_create', 'withdraw_cancel'):
        x = (d.get('writes') or {}).get(k) or {}
        b_axes['direct_%s_404_not_found' % k] = x.get('status') == 404 and x.get('error') == 'not_found'
    b_axes['direct_writes_no_side_effect'] = d.get('side_effect_unchanged')
    b_pos['direct_targets_prepared'] = d.get('targets_prepared')
    out['groups']['B_writes'] = {'axes': b_axes, 'positive': b_pos, 'verdict': group_verdict(b_axes, b_pos)}

    # (3) 멈춤과 복귀
    c_axes, c_pos = {}, {}
    cyc = facts.get('cycles') or {}
    for cy in ('C1', 'C2'):
        info = cyc.get(cy) or {}
        c_pos['%s_ran' % cy] = info.get('ok') is True
        allc = cl.get(cy) or {}
        c_pos['%s_requests_ge_%d' % (cy, C_MIN_REQUESTS)] = (allc.get('requests') or 0) >= C_MIN_REQUESTS
        c_axes['%s_errors_0' % cy] = allc.get('errors') == 0
        out_s, post_s = split_of('%s-out' % cy), split_of('%s-post' % cy)
        c_pos['%s_out_lines_ge_%d' % (cy, C_OUT_MIN_LINES)] = (out_s.get('lines') or 0) >= C_OUT_MIN_LINES
        c_axes['%s_out_api3_served_0' % cy] = (out_s.get('served') or {}).get(API3, 0) == 0 if out_s else None
        c_pos['%s_post_lines_ge_%d' % (cy, C_POST_MIN_LINES)] = (post_s.get('lines') or 0) >= C_POST_MIN_LINES
        c_axes['%s_post_api3_share_ge_%s' % (cy, LOAD_SHARE_MIN)] = (post_s.get('api3_share') is not None
                                                                      and post_s['api3_share'] >= LOAD_SHARE_MIN)
        c_axes['%s_api3_healthy_again' % cy] = info.get('health_s') is not None
    out['groups']['C_stop_return'] = {'axes': c_axes, 'positive': c_pos, 'verdict': group_verdict(c_axes, c_pos)}

    # (4) WS
    ws = facts.get('ws') or {}
    d_axes, d_pos = {}, {}
    st = ws.get('lb_static') or {}
    d_axes['lb_ws_upstream_is_api1_api2'] = st.get('exchange_ws') == ['chaos-api-1', 'chaos-api-2']
    d_axes['lb_orderbook_ticker_upstream_is_api1_api2'] = st.get('exchange_any') == ['chaos-api-1', 'chaos-api-2']
    d_axes['lb_api3_only_in_exchange_read'] = st.get('api3_blocks') == ['exchange_read']
    d_axes['lb_ws_location_to_exchange_ws'] = st.get('ws_location_upstream') == 'exchange_ws'
    for k in ('ws_upgrade', 'orderbook', 'ticker'):
        d_axes['direct_api3_%s_404' % k] = ((d.get('reads') or {}).get(k) or {}).get('status') == 404
    hs = ws.get('handshakes') or []
    d_pos['lb_handshakes_eq_%d' % WS_HANDSHAKES] = len(hs) == WS_HANDSHAKES
    d_axes['lb_handshakes_all_101'] = bool(hs) and all(h.get('status') == 101 for h in hs)
    wsl = lb.get('ws') or {}
    d_pos['lb_ws_lines_ge_%d' % WS_HANDSHAKES] = (wsl.get('lines') or 0) >= WS_HANDSHAKES
    d_axes['lb_ws_tried_api3_0'] = wsl.get('tried_api3') == 0 if wsl else None
    obt = lb.get('orderbook_ticker') or {}
    d_axes['lb_orderbook_ticker_tried_api3_0'] = obt.get('tried_api3') == 0 if obt else None
    out['groups']['D_ws'] = {'axes': d_axes, 'positive': d_pos, 'verdict': group_verdict(d_axes, d_pos)}

    # 무결성·안전
    e_axes, e_pos = {}, {}
    e_axes['lb_api3_5xx_0'] = lb.get('api3_5xx_total') == 0 if 'api3_5xx_total' in lb else None
    e_pos['post_run_present'] = post_run is not None
    e_axes['post_run_gated_clean'] = (post_run or {}).get('gated_clean')
    e_axes['post_summary_ok'] = (post_run or {}).get('summary_ok')
    out['groups']['E_integrity'] = {'axes': e_axes, 'positive': e_pos, 'verdict': group_verdict(e_axes, e_pos)}

    verdicts = [g['verdict'] for g in out['groups'].values()]
    if 'FAIL' in verdicts:
        out['verdict'] = 'FAIL'
    elif 'NOT RUN' in verdicts:
        out['verdict'] = 'NOT RUN'
    else:
        out['verdict'] = 'PASS'
    out['failed_axes'] = sorted('%s.%s' % (g, k) for g, v in out['groups'].items() for k, x in v['axes'].items() if not axis(x))
    out['missing_positive'] = sorted('%s.%s' % (g, k) for g, v in out['groups'].items()
                                     for k, x in v['positive'].items() if not axis(x))
    return out


def parse_nginx_upstreams(text):
    """nginx -T 출력에서 upstream 블록 이름 -> 서버 컨테이너 이름 목록(down 표시 포함 여부는 'name down')."""
    blocks = {}
    for m in re.finditer(r'upstream\s+(\w+)\s*\{(.*?)\}', text, re.S):
        names = []
        for line in m.group(2).splitlines():
            line = line.split('#', 1)[0].strip()
            sm = re.match(r'server\s+([\w.-]+):\d+', line)
            if sm:
                names.append(sm.group(1))
        blocks[m.group(1)] = names
    return blocks


def ws_location_upstream(text):
    m = re.search(r'location\s+/ws/\s*\{(.*?)\}', text, re.S)
    if not m:
        return None
    p = re.search(r'proxy_pass\s+http://(\w+)\s*;', m.group(1))
    return p.group(1) if p else None


# ---------------------------------------------------------------- 실행(스택이 있어야 한다)

def now_iso():
    return dt.datetime.now().astimezone().isoformat(timespec='milliseconds')


def docker(args, timeout=120):
    try:
        p = subprocess.run(['docker'] + args, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=timeout)
        return p.returncode, p.stdout, p.stderr
    except (subprocess.SubprocessError, OSError) as e:
        return None, '', type(e).__name__


def ip_snapshot(label):
    rc, out, _ = docker(['network', 'inspect', NETWORK, '-f',
                         '{{range .Containers}}{{.Name}} {{.IPv4Address}}{{"\\n"}}{{end}}'], timeout=30)
    m = {}
    for line in (out or '').splitlines():
        parts = line.split()
        if len(parts) == 2:
            m[parts[1].split('/')[0]] = parts[0]
    return {'label': label, 'at': now_iso(), 'rc': rc, 'map': m}


def http_call(port, method, path, token=None, body=None, headers=None, ua=None, timeout=10):
    conn = http.client.HTTPConnection('localhost', port, timeout=timeout)
    t0 = time.monotonic()
    try:
        data = json.dumps(body, separators=(',', ':')).encode('utf-8') if body is not None else None
        hdrs = {'Accept': 'application/json', 'User-Agent': ua or (UA_PREFIX + 'misc')}
        if token:
            hdrs['Authorization'] = 'Bearer ' + token
        if data is not None:
            hdrs['Content-Type'] = 'application/json'
        hdrs.update(headers or {})
        conn.request(method, path, body=data, headers=hdrs)
        resp = conn.getresponse()
        raw = resp.read()
        status = resp.status
    except Exception as e:  # noqa: BLE001 - 전송 실패도 값으로 남긴다
        return 0, {'transport_error': type(e).__name__}, round((time.monotonic() - t0) * 1000, 2)
    finally:
        conn.close()
    try:
        parsed = json.loads(raw.decode('utf-8', 'replace')) if raw else {}
    except ValueError:
        parsed = {}
    return status, parsed, round((time.monotonic() - t0) * 1000, 2)


def err_name(body):
    e = body.get('error') if isinstance(body, dict) else None
    return e.get('name') if isinstance(e, dict) else None


class KeepAlive:
    """작업자 하나의 지속 연결(HTTP/1.1 keep-alive). 요청마다 새 연결을 열면 이 PC(Windows, 동적 포트 16,384개)에서 초당 수백 건이
    TIME_WAIT 로 쌓여 포트가 바닥난다(2026-09-26 첫 시도, WSAEADDRINUSE). 전송 실패 때만 연결을 닫고 다음 요청에서 새로 연다.
    nginx 에서 앱으로 가는 연결은 요청마다 새로 열리므로(업스트림 keepalive 없음) least_conn 분배는 바뀌지 않는다."""

    def __init__(self, port, timeout):
        self.port, self.timeout, self.conn = port, timeout, None

    def get(self, path, ua):
        t0 = time.monotonic()
        try:
            if self.conn is None:
                self.conn = http.client.HTTPConnection('localhost', self.port, timeout=self.timeout)
            self.conn.request('GET', path, headers={'Accept': 'application/json', 'User-Agent': ua})
            resp = self.conn.getresponse()
            resp.read()
            status = resp.status
            if resp.getheader('Connection', '').lower() == 'close':
                self.close()
            return status, None, round((time.monotonic() - t0) * 1000, 2)
        except Exception as e:  # noqa: BLE001 - 전송 실패도 값으로 남긴다
            self.close()
            return 0, type(e).__name__, round((time.monotonic() - t0) * 1000, 2)

    def close(self):
        if self.conn is not None:
            try:
                self.conn.close()
            except Exception:  # noqa: BLE001
                pass
        self.conn = None


class LoadGen:
    """닫힌 루프 조회 부하. read-load.py 와 같은 세 경로를 돈다. 활성 작업자 수와 단계 표식을 실행 중에 바꾼다."""

    def __init__(self, max_workers, timeout=8.0):
        self.max_workers = max_workers
        self.active = 0
        self.phase = None
        self.timeout = timeout
        self.records = []
        self.stop = threading.Event()
        self.threads = []

    def _worker(self, k):
        i = k
        client = KeepAlive(LB_PORT, self.timeout)
        while not self.stop.is_set():
            ph = self.phase
            if ph is None or k >= self.active:
                time.sleep(0.02)
                continue
            path = LOAD_PATHS[i % len(LOAD_PATHS)]
            t = time.time()
            status, err, ms = client.get(path, UA_PREFIX + ph)
            self.records.append((t, ph, i % len(LOAD_PATHS), status, ms, err))
            i += 1
        client.close()

    def start(self):
        for k in range(self.max_workers):
            th = threading.Thread(target=self._worker, args=(k,), daemon=True)
            th.start()
            self.threads.append(th)

    def set(self, phase, workers):
        self.active = workers
        self.phase = phase

    def pause(self):
        self.phase = None
        time.sleep(self.timeout + 0.5)

    def close(self):
        self.stop.set()
        for th in self.threads:
            th.join(timeout=self.timeout + 2)


def prom_count(port, uri):
    pat = re.compile(r'^http_server_requests_seconds_count\{[^}]*uri="%s"[^}]*\}\s+([0-9.eE+]+)$' % re.escape(uri), re.M)
    try:
        conn = http.client.HTTPConnection('localhost', port, timeout=5)
        conn.request('GET', '/actuator/prometheus')
        body = conn.getresponse().read().decode('utf-8', 'replace')
        conn.close()
    except Exception:  # noqa: BLE001
        return None
    return round(sum(float(x) for x in pat.findall(body)), 1)


def split_window(run, name, uri, path, n):
    """readsplit3.py 의 창 하나. 순차 호출과 노드별 uri 계수 증가분(기록)."""
    before = {node: prom_count(METRICS_PORTS[node], uri) for node in NODES}
    recs = []
    ua = UA_PREFIX + '%s-%s' % (run, name)
    client = KeepAlive(LB_PORT, 8)
    for _ in range(n):
        t = time.time()
        status, err, ms = client.get(path, ua)
        recs.append((t, '%s-%s' % (run, name), 0, status, ms, err))
    client.close()
    after = {node: prom_count(METRICS_PORTS[node], uri) for node in NODES}
    delta = {node: (None if before[node] is None or after[node] is None else round(after[node] - before[node], 1)) for node in NODES}
    return recs, {'uri': uri, 'path': path, 'calls': n, 'prom_delta': delta}


def read_health(timeout=3):
    status, _, _ = http_call(METRICS_PORTS[API3], 'GET', '/actuator/health/read', timeout=timeout)
    return status == 200


def wait_read_health(budget=HEALTH_BUDGET_S):
    t0 = time.monotonic()
    while time.monotonic() - t0 < budget:
        if read_health():
            return round(time.monotonic() - t0, 2)
        time.sleep(0.5)
    return None


class Member:
    """LB 로 가입한 회원 하나. 토큰은 메모리에만 둔다."""

    def __init__(self):
        self.email = 'app3probe-%s@example.test' % uuid.uuid4().hex[:12]
        self.password = secrets.token_urlsafe(18)
        self.token = None
        self.at = 0.0

    def signup(self):
        status, body, _ = http_call(LB_PORT, 'POST', '/api/v1/auth/signup', ua=UA_PREFIX + 'W-signup',
                                    body={'email': self.email, 'password': self.password, 'display_name': 'app3-probe'})
        if status != 201 or not body.get('access_token'):
            raise RuntimeError('가입 실패(status %s, %s)' % (status, err_name(body)))
        self.token, self.at = body['access_token'], time.monotonic()

    def fresh(self):
        if time.monotonic() - self.at > 600:
            status, body, _ = http_call(LB_PORT, 'POST', '/api/v1/auth/login', ua=UA_PREFIX + 'W-login',
                                        body={'email': self.email, 'password': self.password})
            if status != 200 or not body.get('access_token'):
                raise RuntimeError('다시 로그인 실패(status %s)' % status)
            self.token, self.at = body['access_token'], time.monotonic()
        return self.token


def order_terms(rule):
    """체결되지 않을 지정가 매수. 가격은 호가 단위의 1000 배, 금액은 최소 주문 금액의 2 배를 올림한 수량."""
    price = Decimal(rule['tick_size']) * 1000
    quantum = Decimal(1).scaleb(-int(rule['qty_scale']))
    volume = (Decimal(rule['min_order_amount']) * 2 / price).quantize(quantum, rounding=ROUND_UP)
    return format(price.normalize(), 'f'), format(volume.normalize(), 'f')


def place_order(member, market, terms, port=LB_PORT, ua='W-order'):
    price, volume = terms
    body = {'market': market, 'side': 'bid', 'ord_type': 'limit', 'price': price, 'volume': volume, 'time_in_force': 'GTC',
            'client_order_id': 'app3-' + uuid.uuid4().hex[:20]}
    return http_call(port, 'POST', '/api/v1/orders', token=member.fresh(), body=body, headers={HINT: market}, ua=UA_PREFIX + ua)


def cancel_order(member, market, order_id, port=LB_PORT, ua='W-order-cancel'):
    return http_call(port, 'DELETE', '/api/v1/orders/%s' % order_id, token=member.fresh(), headers={HINT: market}, ua=UA_PREFIX + ua)


def withdraw(member, amount, port=LB_PORT, ua='W-withdraw'):
    return http_call(port, 'POST', '/api/v1/transfers', token=member.fresh(),
                     body={'direction': 'WITHDRAW', 'asset': 'KRW', 'amount': amount},
                     headers={'Idempotency-Key': 'app3-' + uuid.uuid4().hex[:20]}, ua=UA_PREFIX + ua)


def cancel_withdraw(member, external_id, port=LB_PORT, ua='W-withdraw-cancel'):
    return http_call(port, 'DELETE', '/api/v1/transfers/%s' % external_id, token=member.fresh(), ua=UA_PREFIX + ua)


def status_class(status):
    if status == 0:
        return 'transport'
    if status == 404:
        return '404'
    if 200 <= status < 300:
        return '2xx'
    if status >= 500:
        return '5xx'
    return str(status)


class WriteProbe:
    """LB 로 주문·취소·출금·출금 취소를 주기적으로 보낸다. 모두 api-1·api-2 에서 끝나야 한다."""

    def __init__(self, member, market, terms, interval=4.0):
        self.member, self.market, self.terms, self.interval = member, market, terms, interval
        self.counts = {k: collections.Counter() for k in ('order_create', 'order_cancel', 'withdraw_create', 'withdraw_cancel')}
        self.stop = threading.Event()
        self.thread = None
        self.errors = []

    def _cycle(self):
        s, b, _ = place_order(self.member, self.market, self.terms)
        self.counts['order_create'][status_class(s)] += 1
        oid = b.get('id') if isinstance(b, dict) else None
        if oid:
            s, _, _ = cancel_order(self.member, self.market, oid)
            self.counts['order_cancel'][status_class(s)] += 1
        s, b, _ = withdraw(self.member, '10000')
        self.counts['withdraw_create'][status_class(s)] += 1
        ext = b.get('external_id') if isinstance(b, dict) else None
        if ext:
            s, _, _ = cancel_withdraw(self.member, ext)
            self.counts['withdraw_cancel'][status_class(s)] += 1

    def _run(self):
        while not self.stop.is_set():
            try:
                self._cycle()
            except Exception as e:  # noqa: BLE001
                self.errors.append('%s: %s' % (type(e).__name__, str(e)[:120]))
            self.stop.wait(self.interval)

    def start(self):
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def close(self):
        self.stop.set()
        if self.thread:
            self.thread.join(timeout=30)
        return {k: dict(v) for k, v in self.counts.items()} | {'errors': self.errors[:20]}


def ws_handshake(port, ua, origin=WS_ORIGIN, path='/ws/v1'):
    """WebSocket 업그레이드 요청 하나. 상태 줄만 읽고 닫는다."""
    key = base64.b64encode(os.urandom(16)).decode('ascii')
    req = ('GET %s HTTP/1.1\r\nHost: localhost:%d\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: %s\r\n'
           'Sec-WebSocket-Version: 13\r\nOrigin: %s\r\nUser-Agent: %s\r\n\r\n') % (path, port, key, origin, ua)
    t0 = time.monotonic()
    try:
        with socket.create_connection(('localhost', port), timeout=5) as s:
            s.sendall(req.encode('ascii'))
            buf = b''
            while b'\r\n\r\n' not in buf and len(buf) < 65536:
                chunk = s.recv(4096)
                if not chunk:
                    break
                buf += chunk
        line = buf.split(b'\r\n', 1)[0].decode('latin-1')
        m = re.match(r'HTTP/1\.[01] (\d{3})', line)
        return {'status': int(m.group(1)) if m else None, 'ms': round((time.monotonic() - t0) * 1000, 2)}
    except Exception as e:  # noqa: BLE001
        return {'status': None, 'error': type(e).__name__}


def psql_json(sql, timeout=60):
    rc, out, err = docker(['exec', 'chaos-pg', 'psql', '-U', 'exchange', '-d', 'exchange', '-q', '-t', '-A', '-c', sql], timeout=timeout)
    if rc != 0:
        return None
    try:
        return json.loads((out or '').strip())
    except ValueError:
        return None


def member_state(email, order_id, transfer_ext):
    sql = ("SELECT json_build_object('orders', (SELECT count(*) FROM orders o WHERE o.user_id = u.id), "
           "'transfers', (SELECT count(*) FROM transfer_requests t WHERE t.user_id = u.id), "
           "'journal', (SELECT count(*) FROM command_journal c WHERE c.user_id = u.id), "
           "'order_status', (SELECT status FROM orders WHERE id = %d), "
           "'transfer_status', (SELECT status FROM transfer_requests WHERE external_id = '%s'::uuid)) "
           "FROM users u WHERE u.email = '%s'") % (int(order_id), str(uuid.UUID(str(transfer_ext))), email.replace("'", "''"))
    return psql_json(sql)


def krw_of(member, port=LB_PORT):
    status, body, _ = http_call(port, 'GET', '/api/v1/accounts', token=member.fresh(), ua=UA_PREFIX + 'W-accounts')
    if status != 200:
        return None
    for a in body.get('data') or []:
        if a.get('currency') == 'KRW':
            return {'balance': a.get('balance'), 'locked': a.get('locked')}
    return None


def direct_api3_checks(member, market, terms):
    """api-3 에 직접 쓰기·호가·티커·WS 를 보낸다. 쓰기의 대상(열린 주문 하나와 출금 요청 하나)은 LB 로 먼저 만든다."""
    res = {'writes': {}, 'reads': {}, 'targets_prepared': False}
    s, b, _ = place_order(member, market, terms, ua='B3-target-order')
    oid = b.get('id') if isinstance(b, dict) else None
    s2, b2, _ = withdraw(member, '10000', ua='B3-target-withdraw')
    ext = b2.get('external_id') if isinstance(b2, dict) else None
    res['target_status'] = {'order': s, 'withdraw': s2}
    if not oid or not ext:
        return res
    res['targets_prepared'] = True
    before = {'db': member_state(member.email, oid, ext), 'krw': krw_of(member)}
    p = APP_PORTS[API3]
    for k, fn in (('order_create', lambda: place_order(member, market, terms, port=p, ua='B3-direct')),
                  ('order_cancel', lambda: cancel_order(member, market, oid, port=p, ua='B3-direct')),
                  ('withdraw_create', lambda: withdraw(member, '10000', port=p, ua='B3-direct')),
                  ('withdraw_cancel', lambda: cancel_withdraw(member, ext, port=p, ua='B3-direct'))):
        st, bd, ms = fn()
        res['writes'][k] = {'status': st, 'error': err_name(bd), 'ms': ms}
    time.sleep(2)
    after = {'db': member_state(member.email, oid, ext), 'krw': krw_of(member)}
    res['before'], res['after'] = before, after
    res['side_effect_unchanged'] = (before['db'] is not None and before['krw'] is not None and before == after)
    for k, path in (('orderbook', '/api/v1/orderbook?market=%s' % market), ('ticker', '/api/v1/ticker?markets=%s' % market),
                    ('accounts', '/api/v1/accounts')):
        st, bd, _ = http_call(p, 'GET', path, token=member.fresh() if k == 'accounts' else None, ua=UA_PREFIX + 'D2-direct')
        res['reads'][k] = {'status': st, 'error': err_name(bd)}
    res['reads']['ws_upgrade'] = ws_handshake(p, UA_PREFIX + 'D2-direct')
    # 목표물 정리(LB 로)
    cs, _, _ = cancel_order(member, market, oid, ua='B3-target-cleanup')
    ws_, _, _ = cancel_withdraw(member, ext, ua='B3-target-cleanup')
    res['cleanup'] = {'order_cancel': cs, 'withdraw_cancel': ws_}
    return res


def lb_static_facts():
    rc, out, err = docker(['exec', LB_CONTAINER, 'nginx', '-T'], timeout=30)
    text = (out or '') + (err or '')
    blocks = parse_nginx_upstreams(text)
    api3_blocks = sorted(b for b, names in blocks.items() if API3 in names)
    m = re.search(r'upstream\s+exchange_read\s*\{(.*?)\}', text, re.S)
    api3_line = next((ln.strip() for ln in (m.group(1).splitlines() if m else []) if ln.strip().startswith('server %s:' % API3)), None)
    return {'rc': rc, 'api3_read_line': api3_line, 'api3_down_in_running_conf': (api3_line is None or api3_line.rstrip(';').endswith(' down')),
            'exchange_ws': blocks.get('exchange_ws'), 'exchange_any': blocks.get('exchange_any'),
            'exchange_read': blocks.get('exchange_read'), 'api3_blocks': api3_blocks,
            'ws_location_upstream': ws_location_upstream(text),
            'sha256': hashlib.sha256(text.encode('utf-8')).hexdigest()}


def container_env(name, key):
    tmpl = '{{range .Config.Env}}{{if eq (index (split . `=`) 0) `%s`}}{{println .}}{{end}}{{end}}' % key
    rc, out, _ = docker(['inspect', '-f', tmpl, name], timeout=30)
    lines = [x for x in (out or '').splitlines() if x.strip()]
    return lines[0].split('=', 1)[1] if rc == 0 and len(lines) == 1 else None


def api3_sessions():
    return psql_json("SELECT coalesce(json_agg(json_build_object('usename', usename, 'n', n)), '[]'::json) FROM "
                     "(SELECT usename, count(*) AS n FROM pg_stat_activity WHERE application_name = 'chaos-api-3' GROUP BY usename) s")


class StatsSampler:
    """docker stats 를 주기적으로 떠 둔다(컨테이너별 CPU·메모리). 호스트 잡음과 api-3 부담을 읽는 기록이다."""

    def __init__(self, interval=15.0):
        self.interval = interval
        self.samples = []
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self._run, daemon=True)

    def _run(self):
        while not self.stop.is_set():
            rc, out, _ = docker(['stats', '--no-stream', '--format', '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}'], timeout=60)
            if rc == 0:
                self.samples.append({'at': now_iso(), 'rows': [x.split('|') for x in (out or '').splitlines() if x.strip()]})
            self.stop.wait(self.interval)

    def start(self):
        self.thread.start()

    def close(self):
        self.stop.set()
        self.thread.join(timeout=70)
        return self.samples


def cmd_run(a):
    import asg_controller as ctl  # noqa: E402 - harness/asg
    import protocol_latch as pl  # noqa: E402

    ev = Path(a.evidence_dir)
    ev.mkdir(parents=True, exist_ok=True)
    facts = {'started': now_iso(), 'args': {k: v for k, v in vars(a).items() if k != 'func'}, 'marks': [], 'ip_snapshots': []}
    _STATE.update(facts=facts, path=ev / 'facts.json')
    t_start_utc = dt.datetime.now(dt.timezone.utc)

    def mark(name, **kw):
        rec = {'name': name, 'at': now_iso(), 't': round(time.time(), 3)}
        rec.update(kw)
        facts['marks'].append(rec)
        print('[%s] %s %s' % (rec['at'][11:23], name, ' '.join('%s=%s' % kv for kv in kw.items())), flush=True)

    def save():
        (ev / 'facts.json').write_text(json.dumps(facts, ensure_ascii=False, indent=2, default=str), encoding='utf-8')

    g = pl.guard()
    pre = {'guard_ok': g['ok']}
    facts['guard'] = g
    try:
        q0 = json.loads(Path(a.preflight).read_text(encoding='utf-8-sig'))
    except (OSError, ValueError):
        q0 = None
    facts['q0_preflight'] = {'ok': (q0 or {}).get('ok'), 'failed': (q0 or {}).get('failed')}
    pre['q0_preflight_ok'] = bool(q0 and q0.get('ok') is True)
    copy_hash = hashlib.sha256(Path(a.lb_conf).read_bytes()).hexdigest()
    tracked_hash = hashlib.sha256(Path(a.lb_tracked).read_bytes()).hexdigest()
    facts['lb_conf'] = {'copy': str(a.lb_conf), 'tracked': str(a.lb_tracked), 'copy_sha256_start': copy_hash,
                        'tracked_sha256': tracked_hash}
    pre['lb_copy_identical_to_tracked_at_start'] = copy_hash == tracked_hash
    rc, out, _ = docker(['inspect', '-f', '{{range .Mounts}}{{if eq .Destination "/etc/nginx/nginx.conf"}}{{.Source}}{{end}}{{end}}',
                         LB_CONTAINER], timeout=30)
    facts['lb_conf']['mount_source'] = (out or '').strip()
    src = (out or '').strip().replace('\\', '/').lower()
    pre['lb_mount_is_copy'] = rc == 0 and src.endswith(Path(a.lb_conf).resolve().as_posix().lower().split(':', 1)[-1])
    lb = ctl.Balancer(a.lb_conf, LB_CONTAINER)
    pre['lb_copy_api3_down_at_start'] = not lb.active(API3)
    facts['api3_profile'] = container_env(API3, 'SPRING_PROFILES_ACTIVE')
    facts['api3_role'] = container_env(API3, 'EXCHANGE_NODE_ROLE')
    rc, api3_image, _ = docker(['inspect', '-f', '{{.Image}}', API3], timeout=30)
    facts['api3_image'] = (api3_image or '').strip()
    pre['api3_image_is_pml'] = rc == 0 and bool(g.get('pml_image_id')) and facts['api3_image'] == g.get('pml_image_id')
    pre['api3_profile_prod_read'] = facts['api3_profile'] == 'prod,read'
    pre['api3_role_read'] = facts['api3_role'] == 'read'
    facts['preconditions'] = pre
    if not all(pre.values()):
        save()
        print('전제 불성립: %s' % pre)
        return 2
    facts['ip_snapshots'].append(ip_snapshot('start'))

    stats = StatsSampler()
    stats.start()
    load = LoadGen(max_workers=20)
    load.start()

    # R0: api-3 없이 두 노드(9/23 의 2대 상태와 같은 LB 규칙). 20 작업자 120초, 5 작업자 60초.
    mark('R0-start')
    load.set('R0', 20)
    time.sleep(a.r_load_s)
    load.set('R0b', 5)
    time.sleep(a.r_small_s)
    load.pause()
    mark('R0-end')

    # 첫 등록(0대 -> 1대). docker start -> read 헬스 -> LB 등록.
    mark('scale-out-1-start')
    rc, _, err = docker(['start', API3], timeout=60)
    health = wait_read_health()
    facts['first_start'] = {'rc': rc, 'health_s': health}
    pre['api3_first_read_health'] = health is not None
    if health is None:
        load.close()
        save()
        print('api-3 read 헬스가 %d초 안에 오지 않았다' % HEALTH_BUDGET_S)
        return 2
    lb.set_down(API3, False)
    lb.reload()
    mark('scale-out-1-registered', health_s=health)
    facts['ip_snapshots'].append(ip_snapshot('api3-up-1'))
    facts['api3_sessions_after_start'] = api3_sessions()
    rows = facts['api3_sessions_after_start'] or []
    pre['api3_db_login_is_read_user'] = bool(rows) and all(r['usename'] == a.read_user for r in rows)
    facts['ws'] = {'lb_static': lb_static_facts()}
    pre['lb_registration_visible_in_nginx'] = facts['ws']['lb_static']['api3_down_in_running_conf'] is False
    time.sleep(3)

    # R1: 9/23 readsplit3 창(순차).
    client = {}
    split_records = []
    facts['split_prom'] = {}
    for name, uri, path, n in SPLIT_WINDOWS:
        recs, info = split_window('R1', name, uri, path, n)
        split_records += recs
        facts['split_prom']['R1-' + name] = info
    mark('R1-end')

    # 회원과 LB 쓰기 표본, api-3 직접 확인.
    member = Member()
    member.signup()
    status, body, _ = http_call(LB_PORT, 'GET', '/api/v1/markets', ua=UA_PREFIX + 'W-markets')
    rules = {m['market']: m for m in (body.get('data') or [])}
    market = 'KRW-ETH'
    terms = order_terms(rules[market])
    facts['member_krw_start'] = krw_of(member)
    facts['direct_api3'] = direct_api3_checks(member, market, terms)
    mark('direct-checks-end')
    # 조회 location 에 온 조회 밖 메서드(기록). 쓰기가 아니며 받은 노드에 따라 404(api-3)·405 가 갈린다.
    w4 = collections.Counter()
    for _ in range(30):
        s, _, _ = http_call(LB_PORT, 'POST', '/api/v1/markets', body={}, ua=UA_PREFIX + 'W4-post-read-path')
        w4[str(s)] += 1
    facts['w4_post_read_path'] = dict(w4)

    probe = WriteProbe(member, market, terms, interval=a.probe_interval_s)
    probe.start()

    # R2: 3대 20 작업자, 같은 창에 WS 핸드셰이크 30회(LB).
    mark('R2-start')
    load.set('R2', 20)
    hs = []
    t_end = time.time() + a.r_load_s
    gap = a.r_load_s / (WS_HANDSHAKES + 1)
    for _ in range(WS_HANDSHAKES):
        time.sleep(gap)
        hs.append(ws_handshake(LB_PORT, UA_PREFIX + 'D3-ws'))
    time.sleep(max(0.0, t_end - time.time()))
    facts['ws']['handshakes'] = hs
    facts['api3_sessions_under_load'] = api3_sessions()
    load.set('R2b', 5)
    time.sleep(a.r_small_s)
    mark('R2-end')

    # C1: 계획된 축소·증설(ASG 축소의 등록 해제·드레이닝과 증설의 헬스 뒤 등록을 모사). 5 작업자 연속.
    cycles = {}
    load.set('C1-pre', 5)
    time.sleep(a.c_pre_s)
    mark('C1-drain')
    load.set('C1-drain', 5)
    lb.set_down(API3, True)
    lb.reload()
    time.sleep(a.drain_s)
    rc_stop, _, _ = docker(['stop', API3], timeout=90)
    mark('C1-stopped', rc=rc_stop)
    load.set('C1-out', 5)
    time.sleep(a.c_out_s)
    rc_start, _, _ = docker(['start', API3], timeout=60)
    mark('C1-started', rc=rc_start)
    h1 = wait_read_health()
    if h1 is not None:
        lb.set_down(API3, False)
        lb.reload()
    mark('C1-registered', health_s=h1)
    facts['ip_snapshots'].append(ip_snapshot('api3-up-2'))
    load.set('C1-post', 5)
    time.sleep(a.c_post_s)
    cycles['C1'] = {'ok': rc_stop == 0 and rc_start == 0 and h1 is not None, 'health_s': h1}

    # C2: 예고 없는 손실(업스트림에 둔 채 docker kill). LB 설정은 건드리지 않는다.
    load.set('C2-pre', 5)
    time.sleep(a.c_pre_s)
    rc_kill, _, _ = docker(['kill', API3], timeout=60)
    mark('C2-killed', rc=rc_kill)
    load.set('C2-out', 5)
    time.sleep(a.c_out_s)
    rc_start2, _, _ = docker(['start', API3], timeout=60)
    mark('C2-started', rc=rc_start2)
    load.set('C2-return', 5)
    h2 = wait_read_health()
    mark('C2-healthy', health_s=h2)
    facts['ip_snapshots'].append(ip_snapshot('api3-up-3'))
    load.set('C2-post', 5)
    time.sleep(a.c_post_s)
    cycles['C2'] = {'ok': rc_kill == 0 and rc_start2 == 0 and h2 is not None, 'health_s': h2}
    load.pause()
    facts['cycles'] = cycles

    # R3: 두 주기 뒤 순차 분산 다시(9/23 방식).
    for name, uri, path, n in SPLIT_WINDOWS:
        recs, info = split_window('R3', name, uri, path, n)
        split_records += recs
        facts['split_prom']['R3-' + name] = info
    mark('R3-end')
    facts['write_probe'] = probe.close()
    load.close()
    facts['docker_stats'] = stats.close()
    facts['member_krw_end'] = krw_of(member)
    facts['ip_snapshots'].append(ip_snapshot('end'))
    facts['lb_conf_final_api3_active'] = lb.active(API3)

    # 클라이언트 요약
    allrec = load.records + split_records
    by_phase = collections.defaultdict(list)
    for r in allrec:
        by_phase[r[1]].append(r)
    for ph, recs in by_phase.items():
        client[ph] = summarize_client(recs)
    for cy in ('C1', 'C2'):
        recs = [r for r in allrec if r[1].startswith(cy + '-')]
        if recs:
            client[cy] = summarize_client(recs)
    facts['client'] = client
    per_sec = collections.defaultdict(list)
    for r in allrec:
        per_sec[(r[1], int(r[0]))].append(r)
    with gzip.open(ev / 'client_requests.csv.gz', 'wt', encoding='utf-8', newline='') as f:
        f.write('t,phase,path_idx,status,ms,err\n')
        for r in sorted(allrec):
            f.write('%.3f,%s,%d,%d,%.2f,%s\n' % (r[0], r[1], r[2], r[3], r[4], r[5] or ''))

    # LB 로그
    rc, out, err = docker(['logs', '--since', t_start_utc.strftime('%Y-%m-%dT%H:%M:%SZ'), LB_CONTAINER], timeout=180)
    raw = (out or '') + (err or '')
    raw_path = Path(a.raw_dir) / 'lb-access.log.gz'
    raw_path.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(raw_path, 'wt', encoding='utf-8') as f:
        f.write(raw)
    rows = [x for x in (parse_lb_line(line) for line in raw.splitlines()) if x]
    ipmap = merge_ipmap(facts['ip_snapshots'])
    facts['ipmap'] = {ip: sorted(n) for ip, n in ipmap.items()}
    reg_t = next(m['t'] for m in facts['marks'] if m['name'] == 'scale-out-1-registered')
    lbf = {'raw_lines': len(raw.splitlines()), 'parsed': len(rows), 'raw_sha256': hashlib.sha256(raw.encode('utf-8')).hexdigest(),
           'raw_path': str(raw_path), 'phases': {}}
    tagged = collections.defaultdict(list)
    for r in rows:
        p = phase_of(r['ua'])
        if p:
            tagged[p].append(r)
    for p, rs in tagged.items():
        lbf['phases'][p] = lb_split(rs, ipmap)
    after_reg = [r for r in rows if r['ts'] >= math.floor(reg_t)]
    writes = [r for r in after_reg if write_key(r)]
    wsplit = lb_split(writes, ipmap)
    wsplit['by_kind'] = dict(collections.Counter(write_key(r) for r in writes))
    lbf['writes'] = wsplit
    lbf['ws'] = lb_split([r for r in after_reg if r['path'].startswith('/ws/')], ipmap)
    lbf['orderbook_ticker'] = lb_split([r for r in after_reg if r['path'] in ('/api/v1/orderbook', '/api/v1/ticker')], ipmap)
    lbf['api3_5xx_total'] = lb_split(after_reg, ipmap)['api3_5xx']
    lbf['all_after_registration'] = lb_split(after_reg, ipmap)
    unknown = collections.Counter(n for r in rows for n in (node_of(x, ipmap) for x in r['tried']) if ':' in n)
    lbf['unknown_or_ambiguous'] = dict(unknown)
    facts['lb'] = lbf
    facts['finished'] = now_iso()
    save()
    print('facts.json 을 썼다: %s' % (ev / 'facts.json'))
    return 0


def cmd_judge(a):
    facts = json.loads(Path(a.facts).read_text(encoding='utf-8'))
    post = json.loads(Path(a.post).read_text(encoding='utf-8')) if a.post and Path(a.post).exists() else None
    v = judge(facts, post)
    Path(a.out).write_text(json.dumps(v, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'verdict': v['verdict'], 'groups': {k: g['verdict'] for k, g in v['groups'].items()},
                      'failed_axes': v.get('failed_axes'), 'missing_positive': v.get('missing_positive')}, ensure_ascii=False))
    return 0


_STATE = {}


def main(argv=None):
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest='cmd', required=True)
    r = sub.add_parser('run')
    r.add_argument('--evidence-dir', required=True)
    r.add_argument('--raw-dir', required=True, help='저장소 밖 원자료(LB 로그 전체) 폴더')
    r.add_argument('--lb-conf', required=True, help='chaos-lb 에 마운트한 LB 설정 사본(호스트 경로)')
    r.add_argument('--lb-tracked', default=str(HERE.parent / 'nginx' / 'nginx.partition-relay.conf'),
                   help='사본의 원본(시작 때 바이트가 같아야 한다)')
    r.add_argument('--preflight', required=True, help='Q0 준비 판정 파일(p3-preflight.json)')
    r.add_argument('--read-user', required=True)
    r.add_argument('--r-load-s', type=float, default=120)
    r.add_argument('--r-small-s', type=float, default=60)
    r.add_argument('--c-pre-s', type=float, default=30)
    r.add_argument('--c-out-s', type=float, default=45)
    r.add_argument('--c-post-s', type=float, default=60)
    r.add_argument('--drain-s', type=float, default=10)
    r.add_argument('--probe-interval-s', type=float, default=4)
    r.set_defaults(func=cmd_run)
    j = sub.add_parser('judge')
    j.add_argument('--facts', required=True)
    j.add_argument('--post')
    j.add_argument('--out', required=True)
    j.set_defaults(func=cmd_judge)
    a = ap.parse_args(argv)
    try:
        return a.func(a)
    except Exception:  # noqa: BLE001 - 중간에 멈춰도 그때까지의 사실을 남긴다
        import traceback
        if _STATE.get('facts') is not None:
            _STATE['facts']['error'] = traceback.format_exc()[-2000:]
            _STATE['path'].write_text(json.dumps(_STATE['facts'], ensure_ascii=False, indent=2, default=str), encoding='utf-8')
        raise


if __name__ == '__main__':
    sys.exit(main())
