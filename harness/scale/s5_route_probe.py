"""S5 관문 폐기용 스택 확인 도구(docs/reference/architecture/마켓분할_리더_S5_설계.md 7-1 의 5, 7-2 Z1~Z4).

harness/evidence/pml-S5-<시각>/run-s5-gate.ps1 이 부른다. 폐기용 스택(compose 프로젝트 exchange-pml, 앱 이미지
exchange-chaos-app:pml)에서만 돈다. 가드는 protocol_latch 와 같다.

하위 명령
  keys     Z1 k6 주행용 부하 계정을 다시 발급해 harness/load/secrets/k6_keys.json 에 쓴다. clean_suite.reissue_keys 와 같은
           길이다. 키 파일은 경로로만 다루고 개수만 남긴다.
  z2       선검사 무부작용. 봇을 멈춘 스택에서 팔로워에 직접 주문 쓰기 다섯 모양(힌트 있는 생성, 힌트 없는 생성, 배치, 첫
           원소에 market 이 없는 배치, 마켓을 준 일괄 취소)을 보낸다. 같은 client_order_id 의 새 서명 요청을 리더에 보내 201 을
           받고, 끝으로 chaos-api-3 에 같은 모양을 보낸다.
  z3       힌트·배치 거절. 리더에 직접 힌트≠바디 생성, 소문자 힌트, 같은 이름 헤더 둘, 쉼표로 이은 힌트 한 줄, 마켓이 섞인
           배치, 힌트≠대상 주문 마켓의 취소를 보낸다.
  z4       인계. (a) 팔로워의 마켓 없는 일괄 취소 (b) chaos-api-3 의 같은 요청 (c) chaos-admin 의 회원 폐쇄 (d) 두 노드의
           참여를 끄고 리더가 게이트를 닫은 뒤 폐쇄, 참여를 켜고 새 리더가 선 뒤 다시 폐쇄.
  lbsplit  Z1 LB 접근 로그(파티션 형식)를 힌트, 첫 시도 업스트림, 상태 목록으로 나눈다.
  q16      ADR 0034 S7 Q16 두 파티션 인계(S7 설계 5-2 Q16, 하네스 검증설계 §3 P3 실험 Q16). P3 스택에서만 돈다. (1) 회원 A 가
           p0·p1 마켓에 네 주문을 두고 api-1 에 마켓 없는 일괄 취소를 보낸다(p1 주문은 인계 행으로 api-2 가 취소). (2) api-1 의
           matching-p1 참여를 끄고 회원 C 가 네 주문을 둔 뒤 api-2 를 docker pause 한 채 api-1 에 일괄 취소, 관리 평면에 회원 폐쇄를
           보낸다. 요청마다 시각과 p1 리스 행(holder·만료)을 적는다. (3) api-2 를 재개하고 인계 행이 끝나는지 본 뒤 참여를 켠다.
           판정은 harness/scale/s7_axes.py handoff 가 이 출력으로 한다. 요청 시점에 p1 을 유효하게 쥔 노드가 api-2 밖이면 NOT RUN 이다.
  zroute   Z1d(설계 밖 보충). 봇을 멈춘 파티션 LB 스택에서 LB 로 여섯 요청(힌트 있는 ETH·BTC 생성, 힌트 없는 ETH 생성, 맵에
           없는 힌트, 힌트 없는 일괄 취소, 힌트 실은 조회)을 보내 첫 시도 업스트림을 LB 로그로 본다. 힌트 없는 요청과 맵에 없는
           힌트가 종전 쓰기 경로(1차 리더)로 가는지, 조회는 힌트로 가르지 않는지 실제 요청으로 확인한다.

키와 시크릿은 새로 발급해 메모리에만 둔다(S4 X6 도구와 같다). 출력과 JSON 에 남기지 않는다. 판정에 쓰는 DB 조회는 소유자
로그인 psql 의 읽기 전용 질의다. 예외는 Z4 가 부하 계정 둘(B, C)을 회원 폐쇄 대상으로 쓰려고 users.kind 를 'HUMAN' 으로
바꾸는 UPDATE 두 건이다. 폐쇄는 kind='HUMAN' 계정만 받고 부하 계정은 'BOT' 으로 발급되기 때문이다.
관리 평면 호출은 chaos-admin 컨테이너 안의 curl 이 데모 PKI 운영자 인증서(/pki/op-01.p12, server/config/admin-demo 를
compose 가 마운트)로 한다. 세션 토큰과 승인 토큰은 표준 입력으로 넘기고 어디에도 남기지 않는다.

종료코드: 0 판정 통과, 1 판정 실패, 2 가드 위반이나 준비 실패.

사용:
  python harness/scale/s5_route_probe.py keys --markets KRW-BTC,KRW-ETH,KRW-XRP --count 4 --out <json>
  python harness/scale/s5_route_probe.py z2 --out <json>
  python harness/scale/s5_route_probe.py z3 --out <json>
  python harness/scale/s5_route_probe.py z4 --out <json>
  python harness/scale/s5_route_probe.py q16 --out <json>
  python harness/scale/s5_route_probe.py lbsplit --log <파일> --from <ISO> --to <ISO> --out <json>
"""
import argparse
import base64
import datetime as dt
import hashlib
import hmac
import http.client
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))
import protocol_latch as pl  # noqa: E402

API = {'chaos-api-1': 18080, 'chaos-api-2': 28080}
API3 = ('chaos-api-3', 38080)
LB_PORT = 58080
HINT = 'X-Exchange-Market'
NETWORK = 'exchange-pml_chaos-net'
PARTITION_CONF = ROOT / 'harness' / 'nginx' / 'nginx.partition.conf'
PSQL = ['docker', 'exec', '-i', 'chaos-pg', 'psql', '-U', 'exchange', '-d', 'exchange', '-X', '-q', '-t', '-A']
# 시세 아래 매수라 체결되지 않고 호가로 남는다. 가격과 수량은 harness/load/order_flow.js 의 마켓 규칙과 같다.
RULES = {'KRW-BTC': ('49900000', '0.0010'), 'KRW-ETH': ('4400000', '0.0100'), 'KRW-XRP': ('700', '20.0000')}
ADMIN = 'chaos-admin'
ADMIN_CERT = ['--cert-type', 'P12', '--cert', '/pki/op-01.p12:changeit']
OPERATOR = 'op-01'


def now_iso():
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec='milliseconds')


def b64url(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b'=').decode('ascii')


def sign(method, path, payload, key):
    """API 설계 §3-3 서명(harness/load/lib/sign.js 와 같은 규약). payload 는 바디나 정렬된 쿼리 문자열이다."""
    canonical = method + '\n' + path + '\n' + (payload or '')
    claims = {'access_key': key['access_key'], 'nonce': 's5-' + uuid.uuid4().hex, 'timestamp': int(time.time() * 1000),
              'query_hash': hashlib.sha512(canonical.encode('utf-8')).hexdigest(), 'query_hash_alg': 'SHA512'}
    header = {'alg': 'HS256', 'typ': 'JWT'}
    signing_input = b64url(json.dumps(header, separators=(',', ':')).encode()) + '.' + \
        b64url(json.dumps(claims, separators=(',', ':')).encode())
    sig = hmac.new(key['secret'].encode('utf-8'), signing_input.encode('ascii'), hashlib.sha256).digest()
    return 'Bearer ' + signing_input + '.' + b64url(sig)


def canonical_json(obj):
    """JCS 정규형(키 오름차순, 공백 없음). 서버 재정규화와 바이트가 같다(키가 모두 ASCII 다)."""
    return json.dumps(obj, separators=(',', ':'), sort_keys=True, ensure_ascii=False)


def order(coid, market, with_market=True):
    price, volume = RULES[market]
    o = {'client_order_id': coid, 'ord_type': 'limit', 'price': price, 'side': 'bid', 'time_in_force': 'GTC',
         'volume': volume}
    if with_market:
        o['market'] = market
    return o


def call(port, method, path, key=None, body=None, query=None, hints=(), timeout=30):
    """직접 호출. hints 는 X-Exchange-Market 값 목록이고 둘 이상이면 같은 이름 헤더를 여러 줄로 보낸다."""
    target = path + ('?' + query if query else '')
    conn = http.client.HTTPConnection('localhost', port, timeout=timeout)
    t0 = time.monotonic()
    try:
        conn.putrequest(method, target, skip_accept_encoding=True)
        if key is not None:
            conn.putheader('Authorization', sign(method, path, body if body is not None else (query or ''), key))
        for h in hints:
            conn.putheader(HINT, h)
        data = None
        if body is not None:
            data = body.encode('utf-8')
            conn.putheader('Content-Type', 'application/json')
            conn.putheader('Content-Length', str(len(data)))
        conn.endheaders(data)
        resp = conn.getresponse()
        raw = resp.read()
        status = resp.status
        headers = {k.lower(): v for k, v in resp.getheaders()}
    except Exception as e:
        return {'status': 0, 'transport_error': type(e).__name__, 'ms': round((time.monotonic() - t0) * 1000, 1)}
    finally:
        conn.close()
    try:
        parsed = json.loads(raw.decode('utf-8', 'replace')) if raw else {}
    except ValueError:
        parsed = {'raw': raw[:200].decode('utf-8', 'replace')}
    err = parsed.get('error') if isinstance(parsed, dict) and isinstance(parsed.get('error'), dict) else {}
    detail = err.get('detail') if isinstance(err.get('detail'), dict) else {}
    out = {'status': status, 'ms': round((time.monotonic() - t0) * 1000, 1),
           'retry_after': headers.get('retry-after'), 'route_header': headers.get('x-exchange-route'),
           'ratelimit_account_header': 'x-ratelimit-remaining-account' in headers,
           'error': err.get('name'), 'constraint': detail.get('constraint'), 'detail_route': detail.get('route')}
    if isinstance(parsed, dict):
        for k in ('id', 'status', 'canceled_count', 'failed', 'client_order_id'):
            if k in parsed:
                out['body_' + k] = parsed[k]
    if isinstance(parsed, list):
        out['body_items'] = [{'status': x.get('status'), 'error': (x.get('error') or {}).get('name')
                              if isinstance(x, dict) and isinstance(x.get('error'), dict) else None}
                             for x in parsed if isinstance(x, dict)]
    return out


def not_owner_shape(r):
    return (r.get('status') == 503 and r.get('retry_after') == '1' and r.get('route_header') == 'not-owner'
            and r.get('detail_route') == 'not_owner' and not r.get('ratelimit_account_header'))


def psql(sql, timeout=60):
    p = subprocess.run(PSQL + ['-c', sql], capture_output=True, text=True, encoding='utf-8', errors='replace',
                       timeout=timeout)
    if p.returncode != 0:
        raise RuntimeError('psql 실패: %s' % p.stderr.strip()[-200:])
    return p.stdout.strip()


def count(sql):
    return int(psql(sql) or 0)


def quoted(values):
    return ','.join("'" + v.replace("'", "''") + "'" for v in values)


def issue_keys(markets, n):
    """부하 계정 n 개를 발급한다(POST /harness/v1/loadtest-keys). 키는 메모리에만 둔다."""
    url = ('http://localhost:18080/harness/v1/loadtest-keys?market=' + urllib.parse.quote(markets)
           + '&count=%d&quote=1000000000&base=100' % n)
    last = None
    for _ in range(6):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, method='POST'), timeout=90) as r:
                keys = json.load(r)
            if len(keys) == n:
                break
        except Exception as e:
            last = e
            time.sleep(5)
    else:
        raise RuntimeError('부하 계정 발급 실패: %s' % type(last).__name__)
    out = []
    for k in keys:
        uid = psql("SELECT user_id FROM harness.loadtest_accounts WHERE access_key = '%s'" % k['access_key'])
        ext = psql('SELECT external_id FROM users WHERE id = %d' % int(uid))
        out.append({'access_key': k['access_key'], 'secret': k['secret'], 'user_id': int(uid), 'external_id': ext})
    return out


def scrape(port):
    try:
        with urllib.request.urlopen('http://localhost:%d/actuator/prometheus' % port, timeout=10) as r:
            return r.read().decode('utf-8', 'replace')
    except Exception:
        return None


METRIC_LINE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{(.*)\})?\s+(\S+)$')
S5_METRICS = ('exchange_routing_precheck_total', 'exchange_routing_cancel_not_owner_total',
              'exchange_routing_hint_rejected_total', 'exchange_db_read_retry_total',
              'exchange_order_cancel_handoff_requests_total', 'exchange_order_cancel_handoff_worker_total')


def s5_counters(port):
    """S5 계수기 여섯의 {이름{라벨}: 값}. 긁지 못하면 None 이다."""
    text = scrape(port)
    if text is None:
        return None
    out = {}
    for line in text.splitlines():
        m = METRIC_LINE.match(line)
        if not m or m.group(1) not in S5_METRICS:
            continue
        labels = ','.join('%s=%s' % kv for kv in sorted(re.findall(r'(\w+)="([^"]*)"', m.group(3) or ''))
                          if kv[0] not in ('application', 'instance', 'job'))
        out['%s{%s}' % (m.group(1), labels)] = out.get('%s{%s}' % (m.group(1), labels), 0.0) + float(m.group(4))
    return out


def delta(before, after):
    if before is None or after is None:
        return None
    return {k: after[k] - before.get(k, 0.0) for k in after if after[k] - before.get(k, 0.0) != 0}


def partitions(port):
    try:
        with urllib.request.urlopen('http://localhost:%d/actuator/partitions' % port, timeout=10) as r:
            return json.load(r)
    except Exception:
        return None


def roles():
    """P2 의 리더(게이트를 연 노드)와 팔로워. 판별하지 못하면 예외다."""
    states = {}
    for name, port in API.items():
        p = partitions(port)
        rows = (p or {}).get('partitions') or []
        states[name] = rows[0].get('state') if rows else None
    leaders = [n for n, s in states.items() if s == 'ACCEPTING']
    if len(leaders) != 1:
        raise RuntimeError('리더 판별 실패: %s' % states)
    leader = leaders[0]
    follower = [n for n in API if n != leader][0]
    return leader, follower, states


def lease():
    raw = psql("SELECT json_build_object('holder', holder, 'epoch', epoch, 'alive', lease_until > clock_timestamp()) "
               "FROM leader_lease WHERE resource = 'matching-engine'")
    return json.loads(raw) if raw else None


def guard():
    g = pl.guard()
    extra = {}
    for name in (API3[0], ADMIN):
        extra[name] = {'project': pl.inspect(name, '{{index .Config.Labels "com.docker.compose.project"}}'),
                       'image': pl.inspect(name, '{{.Image}}')}
        if extra[name]['project'] not in (None, pl.PROJECT):
            g['problems'].append('%s 의 compose 프로젝트가 %r 이다' % (name, extra[name]['project']))
        if extra[name]['image'] not in (None, g.get('pml_image_id')):
            g['problems'].append('%s 의 이미지가 %s 의 id 와 다르다' % (name, pl.IMAGE))
    g['extra'] = extra
    g['ok'] = not g['problems']
    return g


def write(out, res):
    Path(out).write_text(json.dumps(res, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'ok': res.get('ok'), 'failed_axes': [k for k, v in (res.get('axes') or {}).items() if not v],
                      'error': res.get('error')}, ensure_ascii=False), flush=True)
    return 0 if res.get('ok') else 1


def side_effects(user_id, coids):
    """그 회원의 그 client_order_id 들이 남긴 흔적. 멱등 키, 저널 행, 예약 행."""
    return {'idempotency_keys': count('SELECT count(*) FROM idempotency_keys WHERE user_id = %d AND idem_key IN (%s)'
                                      % (user_id, quoted(coids))),
            'journal_rows': count('SELECT count(*) FROM command_journal WHERE user_id = %d AND client_order_id IN (%s)'
                                  % (user_id, quoted(coids))),
            'reservations': count('SELECT count(*) FROM order_reservations WHERE user_id = %d' % user_id)}


def misrouted_shapes(port, key, tag):
    """주문 쓰기 다섯 모양을 한 노드에 보낸다. 돌려주는 것은 (결과 목록, 쓴 client_order_id 목록)이다."""
    c = ['%s-%s-%d' % (tag, uuid.uuid4().hex[:8], i) for i in range(6)]
    rows = []
    rows.append(('create_hinted', c[:1],
                 call(port, 'POST', '/api/v1/orders', key, canonical_json(order(c[0], 'KRW-BTC')), hints=['KRW-BTC'])))
    rows.append(('create_unhinted', c[1:2],
                 call(port, 'POST', '/api/v1/orders', key, canonical_json(order(c[1], 'KRW-BTC')))))
    rows.append(('batch', c[2:4], call(port, 'POST', '/api/v1/orders/batch', key,
                                       canonical_json([order(c[2], 'KRW-BTC'), order(c[3], 'KRW-BTC')]))))
    rows.append(('batch_first_without_market', c[4:6], call(port, 'POST', '/api/v1/orders/batch', key, canonical_json(
        [order(c[4], 'KRW-BTC', with_market=False), order(c[5], 'KRW-BTC')]))))
    rows.append(('cancel_all_with_market', [], call(port, 'DELETE', '/api/v1/orders', key, query='market=KRW-BTC')))
    return [{'shape': s, 'coids': k, 'response': r} for s, k, r in rows], c


def precheck_not_owner(counters):
    if counters is None:
        return None
    return sum(v for k, v in counters.items()
               if k.startswith('exchange_routing_precheck_total{') and 'result=not_owner' in k)


def run_z2(a):
    g = guard()
    if not g['ok']:
        return write(a.out, {'phase': 'Z2', 'ok': False, 'error': 'guard', 'guard': g})
    try:
        leader, follower, states = roles()
        key = issue_keys('KRW-BTC', 1)[0]
    except Exception as e:
        return write(a.out, {'phase': 'Z2', 'ok': False, 'error': str(e)})
    lease0 = lease()
    f_port, l_port = API[follower], API[leader]
    before = {n: s5_counters(p) for n, p in API.items()}
    rows, coids = misrouted_shapes(f_port, key, 'z2f')
    after = {n: s5_counters(p) for n, p in API.items()}
    effects = side_effects(key['user_id'], coids)
    # 같은 client_order_id 의 새 서명 요청을 리더에 보낸다. 닫힌 노드가 멱등 키·nonce 를 남겼다면 200 이나 401 이다.
    retry = call(l_port, 'POST', '/api/v1/orders', key, canonical_json(order(coids[0], 'KRW-BTC')), hints=['KRW-BTC'])
    new_rows = count("SELECT count(*) FROM command_journal WHERE user_id = %d AND client_order_id = '%s' "
                     "AND command_type = 'NEW'" % (key['user_id'], coids[0]))
    # 뒤 판정(드레인, 불변식)에 남지 않게 그 주문을 취소한다.
    cleanup = call(l_port, 'DELETE', '/api/v1/orders/' + str(retry.get('body_id')), key, hints=['KRW-BTC']) \
        if retry.get('body_id') else None
    api3 = None
    api3_before = s5_counters(API3[1])
    if api3_before is not None:
        rows3, coids3 = misrouted_shapes(API3[1], key, 'z2r')
        api3_after = s5_counters(API3[1])
        api3 = {'rows': rows3, 'side_effects': side_effects(key['user_id'], coids3),
                'not_owner_delta': precheck_not_owner(api3_after) - precheck_not_owner(api3_before),
                'counters_delta': delta(api3_before, api3_after)}
    f_delta = (precheck_not_owner(after[follower]) or 0) - (precheck_not_owner(before[follower]) or 0)
    lease1 = lease()
    axes = {
        'follower_all_503_not_owner_shape': all(not_owner_shape(r['response']) for r in rows),
        'no_idempotency_journal_reservation': effects == {'idempotency_keys': 0, 'journal_rows': 0, 'reservations': 0},
        'follower_not_owner_plus_5': f_delta == 5,
        'leader_same_key_201_one_new': retry.get('status') == 201 and new_rows == 1,
        'api3_all_503_not_owner_shape': bool(api3) and all(not_owner_shape(r['response']) for r in api3['rows']),
        'api3_no_side_effects': bool(api3) and api3['side_effects']['idempotency_keys'] == 0
        and api3['side_effects']['journal_rows'] == 0,
        'lease_unchanged': bool(lease0 and lease1) and lease0['holder'] == lease1['holder']
        and lease0['epoch'] == lease1['epoch'] and lease1['alive'],
    }
    res = {'phase': 'Z2', 'at': now_iso(), 'leader': leader, 'follower': follower, 'states': states,
           'user_id': key['user_id'], 'rows': rows, 'side_effects': effects, 'follower_not_owner_delta': f_delta,
           'counters_delta': {n: delta(before[n], after[n]) for n in API}, 'leader_retry': retry,
           'leader_new_rows': new_rows, 'cleanup': cleanup, 'api3': api3, 'lease': {'before': lease0, 'after': lease1},
           'axes': axes, 'ok': all(axes.values())}
    return write(a.out, res)


def hint_rejected(counters):
    if counters is None:
        return {}
    out = {}
    for k, v in counters.items():
        m = re.match(r'exchange_routing_hint_rejected_total\{.*reason=([a-z_]+)', k)
        if m:
            out[m.group(1)] = out.get(m.group(1), 0.0) + v
    return out


def run_z3(a):
    g = guard()
    if not g['ok']:
        return write(a.out, {'phase': 'Z3', 'ok': False, 'error': 'guard', 'guard': g})
    try:
        leader, follower, states = roles()
        key = issue_keys('KRW-BTC,KRW-ETH', 1)[0]
    except Exception as e:
        return write(a.out, {'phase': 'Z3', 'ok': False, 'error': str(e)})
    lease0 = lease()
    port = API[leader]
    c = ['z3-%s-%d' % (uuid.uuid4().hex[:8], i) for i in range(8)]
    before = s5_counters(port)
    rows = []

    def add(case, coids, r, want_status, want_constraint, pre_auth=False):
        ok = r.get('status') == want_status and r.get('constraint') == want_constraint
        if pre_auth or want_constraint == 'batch_single_market':
            ok = ok and not r.get('ratelimit_account_header')
        rows.append({'case': case, 'coids': coids, 'response': r, 'ok': ok})

    add('create_hint_other_market', c[:1], call(port, 'POST', '/api/v1/orders', key,
                                                canonical_json(order(c[0], 'KRW-BTC')), hints=['KRW-ETH']),
        400, 'market_hint_mismatch')
    add('create_lowercase_hint', c[1:2], call(port, 'POST', '/api/v1/orders', key,
                                              canonical_json(order(c[1], 'KRW-BTC')), hints=['krw-btc']),
        400, 'market_hint_mismatch')
    add('create_two_hint_headers', c[2:3], call(port, 'POST', '/api/v1/orders', key,
                                                canonical_json(order(c[2], 'KRW-BTC')), hints=['KRW-BTC', 'KRW-BTC']),
        400, 'market_hint_duplicate', pre_auth=True)
    add('create_comma_joined_hint', c[3:4], call(port, 'POST', '/api/v1/orders', key,
                                                 canonical_json(order(c[3], 'KRW-BTC')), hints=['KRW-BTC,KRW-ETH']),
        400, 'market_hint_duplicate', pre_auth=True)
    add('batch_mixed_markets', c[4:6], call(port, 'POST', '/api/v1/orders/batch', key,
                                            canonical_json([order(c[4], 'KRW-BTC'), order(c[5], 'KRW-ETH')])),
        400, 'batch_single_market')
    placed = call(port, 'POST', '/api/v1/orders', key, canonical_json(order(c[6], 'KRW-BTC')), hints=['KRW-BTC'])
    oid = placed.get('body_id')
    mismatch_cancel = call(port, 'DELETE', '/api/v1/orders/%s' % oid, key, hints=['KRW-ETH']) if oid else {'status': 0}
    add('cancel_hint_other_market', [], mismatch_cancel, 400, 'market_hint_mismatch')
    still_open = psql("SELECT status FROM orders WHERE external_id = '%s'::uuid" % oid) if oid else None
    cleanup = call(port, 'DELETE', '/api/v1/orders/%s' % oid, key, hints=['KRW-BTC']) if oid else None
    after = s5_counters(port)
    rejected = {k: hint_rejected(after).get(k, 0.0) - hint_rejected(before).get(k, 0.0)
                for k in ('market_hint_mismatch', 'market_hint_duplicate', 'batch_single_market')}
    effects = side_effects(key['user_id'], c[:6])
    lease1 = lease()
    axes = {
        'each_case_expected_400': all(r['ok'] for r in rows),
        'no_keys_no_new_for_rejected': effects['idempotency_keys'] == 0 and effects['journal_rows'] == 0,
        'placed_then_open_after_mismatch_cancel': placed.get('status') == 201 and still_open in ('OPEN', 'PARTIAL'),
        'cleanup_cancel_200': bool(cleanup) and cleanup.get('status') == 200,
        'counter_mismatch_eq_3': rejected['market_hint_mismatch'] == 3,
        'counter_duplicate_eq_2': rejected['market_hint_duplicate'] == 2,
        'counter_batch_eq_1': rejected['batch_single_market'] == 1,
        'lease_unchanged': bool(lease0 and lease1) and lease0['holder'] == lease1['holder']
        and lease0['epoch'] == lease1['epoch'] and lease1['alive'],
    }
    res = {'phase': 'Z3', 'at': now_iso(), 'leader': leader, 'follower': follower, 'states': states,
           'user_id': key['user_id'], 'rows': rows, 'placed': placed, 'order_status_after_mismatch': still_open,
           'cleanup': cleanup, 'hint_rejected_delta': rejected, 'side_effects_rejected': effects,
           'counters_delta': delta(before, after), 'lease': {'before': lease0, 'after': lease1},
           'axes': axes, 'ok': all(axes.values())}
    return write(a.out, res)


def place_four(key):
    """LB 로 BTC 둘, ETH 둘을 힌트와 함께 넣는다. 돌려주는 것은 (주문 id 목록, 응답 목록)이다."""
    ids, rows = [], []
    for i, market in enumerate(('KRW-BTC', 'KRW-BTC', 'KRW-ETH', 'KRW-ETH')):
        coid = 'z4-%s-%d' % (uuid.uuid4().hex[:8], i)
        r = call(LB_PORT, 'POST', '/api/v1/orders', key, canonical_json(order(coid, market)), hints=[market])
        rows.append({'market': market, 'status': r.get('status'), 'error': r.get('error')})
        if r.get('status') == 201 and r.get('body_id'):
            ids.append(str(r['body_id']))
    return ids, rows


def order_states(ids):
    if not ids:
        return {}
    raw = psql("SELECT coalesce(json_object_agg(external_id, status), '{}'::json) FROM orders "
               "WHERE external_id IN (%s)" % ','.join("'%s'::uuid" % i for i in ids))
    return json.loads(raw) if raw else {}


def krw_balance(user_id):
    raw = psql("SELECT json_build_object('available', available::text, 'locked', locked::text) FROM user_balances "
               "WHERE user_id = %d AND asset_id = (SELECT id FROM assets WHERE symbol = 'KRW')" % user_id)
    return json.loads(raw) if raw else None


def handoff_rows(user_id):
    raw = psql("SELECT coalesce(json_agg(json_build_object('resource', resource, 'state', state, 'origin', origin, "
               "'orders', cardinality(order_ids)) ORDER BY id), '[]'::json) FROM order_cancel_requests "
               "WHERE user_id = %d" % user_id)
    return json.loads(raw) if raw else []


def wait_open(ids, limit_s=20):
    """넣은 주문이 모두 OPEN 으로 투영될 때까지 기다린다."""
    deadline = time.monotonic() + limit_s
    while time.monotonic() < deadline:
        s = order_states(ids)
        if len(s) == len(ids) and all(v == 'OPEN' for v in s.values()):
            return s
        time.sleep(0.2)
    return order_states(ids)


def handoff_case(port, key, label):
    """(a)(b). 회원의 네 주문을 넣고 그 노드에 마켓 없는 일괄 취소를 보낸다."""
    bal0 = krw_balance(key['user_id'])
    ids, placed = place_four(key)
    opened = wait_open(ids)
    bal_open = krw_balance(key['user_id'])
    t0 = time.monotonic()
    r = call(port, 'DELETE', '/api/v1/orders', key, timeout=60)
    elapsed = round((time.monotonic() - t0) * 1000, 1)
    time.sleep(0.5)
    states = order_states(ids)
    bal1 = krw_balance(key['user_id'])
    rows = handoff_rows(key['user_id'])
    axes = {
        '%s_placed_4' % label: len(ids) == 4 and all(v == 'OPEN' for v in opened.values()),
        '%s_200_canceled_4_no_failed' % label: r.get('status') == 200 and r.get('body_canceled_count') == 4
        and not r.get('body_failed'),
        '%s_orders_canceled' % label: len(states) == 4 and all(v == 'CANCELED' for v in states.values()),
        '%s_one_handoff_row_done' % label: len(rows) == 1 and rows[0]['state'] == 'DONE'
        and rows[0]['resource'] == 'matching-engine' and rows[0]['origin'] == 'cancel_all' and rows[0]['orders'] == 4,
        '%s_locked_back_to_before' % label: bool(bal0 and bal1) and bal1['locked'] == bal0['locked']
        and bal1['available'] == bal0['available'],
    }
    return {'label': label, 'port': port, 'user_id': key['user_id'], 'placed': placed, 'response': r,
            'elapsed_ms': elapsed, 'order_states': states, 'handoff_rows': rows,
            'krw': {'before': bal0, 'while_open': bal_open, 'after': bal1}}, axes


def admin_exec(script, stdin_lines, timeout=120):
    """chaos-admin 컨테이너 안에서 sh 스크립트를 돌린다. 토큰은 표준 입력으로만 넘긴다. (종료코드, 표준 출력).

    표준 입력은 바이트로 넘긴다. Windows 의 텍스트 모드 파이프는 줄바꿈을 CRLF 로 바꿔, read 가 받은 토큰 끝에 CR 이 붙고
    Tomcat 이 그 헤더를 거절한다(첫 Z4 주행에서 'Error parsing HTTP request header').
    """
    data = ('\n'.join(stdin_lines) + '\n').encode('utf-8')
    p = subprocess.run(['docker', 'exec', '-i', ADMIN, 'sh', '-c', script], input=data, capture_output=True,
                       timeout=timeout)
    return p.returncode, p.stdout.decode('utf-8', 'replace')


def admin_session():
    rc, out = admin_exec('curl -sk %s -X POST https://localhost:8443/admin/v1/session' % ' '.join(ADMIN_CERT), [])
    try:
        return json.loads(out)['token']
    except Exception:
        raise RuntimeError('관리 세션 발급 실패(rc=%s)' % rc)


def admin_close(token, external_id):
    """승인을 발급받아 폐쇄를 부른다. (상태 코드, 응답 요약)."""
    approve = ('read -r T; curl -sk %s -X POST -H "Authorization: Bearer $T" -H "Content-Type: application/json" '
               '-d \'{"requester_operator_id":"%s","action":"members.close","target":"%s"}\' '
               'https://localhost:8443/admin/v1/approvals') % (' '.join(ADMIN_CERT), OPERATOR, external_id)
    rc, out = admin_exec(approve, [token])
    try:
        approval = json.loads(out)['approval_token']
    except Exception:
        raise RuntimeError('승인 발급 실패(rc=%s)' % rc)
    close = ('read -r T; read -r A; curl -sk %s -X POST -H "Authorization: Bearer $T" '
             '-H "Content-Type: application/json" -d "{\\"approval_token\\":\\"$A\\"}" -w "\\n%%{http_code}" '
             'https://localhost:8443/admin/v1/members/%s/close') % (' '.join(ADMIN_CERT), external_id)
    t0 = time.monotonic()
    rc, out = admin_exec(close, [token, approval])
    elapsed = round((time.monotonic() - t0) * 1000, 1)
    body, _, code = out.rpartition('\n')
    try:
        parsed = json.loads(body)
    except ValueError:
        parsed = {}
    err = parsed.get('error') if isinstance(parsed.get('error'), dict) else {}
    detail = err.get('detail') if isinstance(err.get('detail'), dict) else {}
    summary = {'status': int(code) if code.strip().isdigit() else 0, 'elapsed_ms': elapsed,
               'error': err.get('name'), 'canceled_orders': parsed.get('canceled_orders', detail.get('canceled_orders')),
               'pending_orders': detail.get('pending_orders'), 'pending_reasons': detail.get('pending_reasons'),
               'member_status': parsed.get('status', detail.get('status'))}
    return summary


def epoch_writes(epoch):
    """그 epoch 로 쓰인 저널 행 수(명령, 이벤트). lib.ps1 Get-EpochWriteCounts 와 같은 술어다."""
    return {'cmd': count('SELECT count(*) FROM command_journal WHERE fencing_token = %d' % epoch),
            'evt': count('SELECT count(*) FROM event_journal WHERE fencing_epoch = %d' % epoch)}


def audit_rows(external_id):
    return count("SELECT count(*) FROM admin_audit_log WHERE action = 'members.close' AND target = '%s'" % external_id)


def set_participate(container, value, resource='matching-engine'):
    """POST /actuator/partitions(loopback 전용)를 그 컨테이너 안에서 부른다. P3 에서는 자원을 준다(S7 설계 4-13, Q9·Q16)."""
    body = json.dumps({'resource': resource, 'participate': value})
    p = subprocess.run(['docker', 'exec', container, 'curl', '-s', '-o', '/dev/null', '-w', '%{http_code}', '-X', 'POST',
                        '-H', 'Content-Type: application/json', '-d', body, 'http://localhost:8080/actuator/partitions'],
                       capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=60)
    return p.stdout.strip()


def wait_states(pred, limit_s=90):
    deadline = time.monotonic() + limit_s
    last = None
    while time.monotonic() < deadline:
        last = {n: ((partitions(p) or {}).get('partitions') or [{}])[0].get('state') for n, p in API.items()}
        if pred(last):
            return True, last
        time.sleep(1)
    return False, last


def run_z4(a):
    g = guard()
    if not g['ok']:
        return write(a.out, {'phase': 'Z4', 'ok': False, 'error': 'guard', 'guard': g})
    try:
        leader, follower, states = roles()
        keys = issue_keys('KRW-BTC,KRW-ETH', 4)
    except Exception as e:
        return write(a.out, {'phase': 'Z4', 'ok': False, 'error': str(e)})
    A, A2, B, C = keys
    for k in (B, C):
        psql("UPDATE users SET kind = 'HUMAN' WHERE id = %d" % k['user_id'])
    lease0 = lease()
    before = {n: s5_counters(p) for n, p in API.items()}
    res = {'phase': 'Z4', 'at': now_iso(), 'leader': leader, 'follower': follower, 'states': states,
           'users': {'A': A['user_id'], 'A2': A2['user_id'], 'B': B['user_id'], 'C': C['user_id']}}
    axes = {}
    res['a'], ax = handoff_case(API[follower], A, 'a')
    axes.update(ax)
    if s5_counters(API3[1]) is not None:
        res['b'], ax = handoff_case(API3[1], A2, 'b')
        axes.update(ax)
    else:
        axes['b_api3_reachable'] = False
    # (c) 관리 평면 폐쇄
    try:
        token = admin_session()
        ids_b, placed_b = place_four(B)
        opened_b = wait_open(ids_b)
        close_b = admin_close(token, B['external_id'])
        time.sleep(0.5)
        states_b = order_states(ids_b)
        res['c'] = {'user_id': B['user_id'], 'placed': placed_b, 'close': close_b, 'order_states': states_b,
                    'audit_rows': audit_rows(B['external_id']), 'handoff_rows': handoff_rows(B['user_id'])}
        axes['c_placed_4'] = len(ids_b) == 4 and all(v == 'OPEN' for v in opened_b.values())
        axes['c_close_200_canceled_4'] = close_b['status'] == 200 and close_b['canceled_orders'] == 4
        axes['c_orders_canceled'] = len(states_b) == 4 and all(v == 'CANCELED' for v in states_b.values())
        axes['c_audit_row'] = res['c']['audit_rows'] >= 1
        rows_b = res['c']['handoff_rows']
        axes['c_handoff_row_done_member_close'] = len(rows_b) == 1 and rows_b[0]['state'] == 'DONE' \
            and rows_b[0]['origin'] == 'member_close'
    except Exception as e:
        res['c'] = {'error': str(e)}
        axes['c_admin'] = False
        token = None
    mid = {n: s5_counters(p) for n, p in API.items()}
    lease_mid = lease()
    # (d) 두 노드의 참여를 끄고 리더가 계획 강등으로 게이트를 닫은 뒤 폐쇄한다.
    d = {'user_id': C['user_id']}
    try:
        ids_c, placed_c = place_four(C)
        opened_c = wait_open(ids_c)
        d['placed'] = placed_c
        axes['d_placed_4'] = len(ids_c) == 4 and all(v == 'OPEN' for v in opened_c.values())
        d['participate_off'] = {n: set_participate(n, False) for n in API}
        closed, seen = wait_states(lambda s: all(v != 'ACCEPTING' for v in s.values()))
        d['gates_closed'] = {'ok': closed, 'states': seen}
        if token is None:
            token = admin_session()
        close_c1 = admin_close(token, C['external_id'])
        d['close_first'] = close_c1
        d['handoff_rows_first'] = handoff_rows(C['user_id'])
        axes['d_first_close_409_not_started_4'] = close_c1['status'] == 409 \
            and close_c1['error'] == 'orders_cancel_incomplete' \
            and close_c1['pending_reasons'] == {'partition_not_started': 4}
        axes['d_first_row_abandoned'] = [r['state'] for r in d['handoff_rows_first']] == ['ABANDONED']
        e_before = lease()
        # 게이트를 닫은 옛 기간의 쓰기 기준선. 새 리더가 선 뒤 이 epoch 로 더 쓰인 행이 없어야 한다(구 epoch 쓰기 증분 0).
        old_epoch = int(e_before['epoch']) if e_before else None
        stale0 = epoch_writes(old_epoch) if old_epoch is not None else None
        d['participate_on'] = {n: set_participate(n, True) for n in API}
        up, seen = wait_states(lambda s: list(s.values()).count('ACCEPTING') == 1, limit_s=120)
        d['new_leader'] = {'ok': up, 'states': seen, 'lease_before': e_before, 'lease_after': lease()}
        time.sleep(10)
        d['after_10s'] = order_states(ids_c)
        axes['d_new_leader_up'] = up
        axes['d_orders_still_open_10s_after_new_leader'] = len(d['after_10s']) == 4 \
            and all(v == 'OPEN' for v in d['after_10s'].values())
        close_c2 = admin_close(token, C['external_id'])
        time.sleep(0.5)
        d['close_second'] = close_c2
        d['order_states_final'] = order_states(ids_c)
        d['handoff_rows_final'] = handoff_rows(C['user_id'])
        axes['d_second_close_200_canceled_4'] = close_c2['status'] == 200 and close_c2['canceled_orders'] == 4
        axes['d_orders_canceled_final'] = len(d['order_states_final']) == 4 \
            and all(v == 'CANCELED' for v in d['order_states_final'].values())
        stale1 = epoch_writes(old_epoch) if old_epoch is not None else None
        d['old_epoch_writes'] = {'epoch': old_epoch, 'before_new_leader': stale0, 'end': stale1}
        new_epoch = (d['new_leader']['lease_after'] or {}).get('epoch')
        axes['d_epoch_advanced'] = old_epoch is not None and new_epoch is not None and int(new_epoch) > old_epoch
        axes['d_old_epoch_writes_delta_0'] = stale0 is not None and stale0 == stale1
    except Exception as e:
        d['error'] = str(e)
        axes['d_run'] = False
    finally:
        # 도중에 끝나도 두 노드의 참여를 켜 둔다. 켜지 않으면 뒤 판정(드레인, 불변식)이 리더 없는 스택에서 돈다.
        if 'participate_off' in d and 'participate_on' not in d:
            d['participate_on_after_error'] = {n: set_participate(n, True) for n in API}
    res['d'] = d
    after = {n: s5_counters(p) for n, p in API.items()}
    # 리스가 그대로인 (a)~(c) 구간에서 리더의 선검사 not_owner 증가 0(설계 7-2 공통, 11절 R6).
    res['counters_delta_abc'] = {n: delta(before[n], mid[n]) for n in API}
    res['counters_delta_all'] = {n: delta(before[n], after[n]) for n in API}
    axes['abc_leader_not_owner_0'] = (precheck_not_owner(mid[leader]) or 0) - (precheck_not_owner(before[leader]) or 0) == 0
    axes['abc_lease_unchanged'] = bool(lease0 and lease_mid) and lease0['holder'] == lease_mid['holder'] \
        and lease0['epoch'] == lease_mid['epoch']
    res['lease'] = {'before': lease0, 'mid': lease_mid, 'end': lease()}
    res['axes'] = axes
    res['ok'] = all(axes.values())
    return write(a.out, res)


def partition_lease(resource):
    raw = psql("SELECT json_build_object('holder', holder, 'epoch', epoch, 'alive', lease_until > clock_timestamp(), "
               "'lease_until', lease_until, 'db_now', clock_timestamp()) FROM leader_lease WHERE resource = '%s'" % resource)
    return json.loads(raw) if raw else None


def partition_epoch(port, resource):
    rows = ((partitions(port) or {}).get('partitions')) or []
    for row in rows:
        if row.get('resource') == resource and row.get('state') == 'ACCEPTING':
            return row.get('epoch')
    return None


def holder_node(lease_row, holders):
    """p1 리스 행을 유효하게 쥔 노드. holders 는 {holder: 노드}이고 만료됐으면 None 이다."""
    if not lease_row or not lease_row.get('alive'):
        return None
    return holders.get(lease_row.get('holder'), 'unknown')


def run_q16(a):
    """S7 Q16. 두 파티션 인계와 멈춘 소유 노드(설계 5-2 Q16). 판정은 s7_axes.py handoff 다."""
    g = guard()
    if not g['ok']:
        return write(a.out, {'phase': 'Q16', 'ok': False, 'error': 'guard', 'guard': g})
    res = {'phase': 'Q16', 'at': now_iso()}
    holders = {}
    for node, port in API.items():
        for resource in ('matching-p0', 'matching-p1'):
            epoch = partition_epoch(port, resource)
            row = partition_lease(resource)
            if epoch is not None and row and int(row['epoch']) == int(epoch):
                holders[row['holder']] = node
    res['holders'] = {v: k[:8] for k, v in holders.items()}
    if partition_epoch(API['chaos-api-2'], 'matching-p1') is None or partition_epoch(API['chaos-api-1'], 'matching-p0') is None:
        res.update(ok=False, error='시작 배정이 선호(api-1 p0, api-2 p1)가 아니다')
        return write(a.out, res)
    try:
        keys = issue_keys('KRW-BTC,KRW-ETH', 2)
    except Exception as e:
        res.update(ok=False, error=str(e))
        return write(a.out, res)
    A, C = keys
    psql("UPDATE users SET kind = 'HUMAN' WHERE id = %d" % C['user_id'])
    res['users'] = {'A': A['user_id'], 'C': C['user_id']}
    # (1) 두 노드가 살아 있을 때. api-1 은 p0 주문을 직접, p1 주문은 인계 행으로 넘긴다.
    ids_a, placed_a = place_four(A)
    wait_open(ids_a)
    t0 = time.monotonic()
    live = call(API['chaos-api-1'], 'DELETE', '/api/v1/orders', A, timeout=60)
    res['live'] = {'placed': placed_a, 'response': live, 'elapsed_ms': round((time.monotonic() - t0) * 1000, 1)}
    time.sleep(0.5)
    res['live']['order_states'] = order_states(ids_a)
    res['live']['handoff_rows'] = handoff_rows(A['user_id'])
    # (2) api-1 의 p1 참여를 끈다. 끄지 않으면 api-1 이 p1 만료 뒤 1×TTL 에 넘겨받아 두 요청을 직접 처리한다(설계 11절 지적 4).
    res['api1_p1_participate_off'] = set_participate('chaos-api-1', False, 'matching-p1')
    ids_c, placed_c = place_four(C)
    wait_open(ids_c)
    res['paused_placed'] = placed_c
    paused = []
    token = None
    try:
        token = admin_session()
        res['pause_rc'] = pl.docker(['pause', 'chaos-api-2'])[0]
        lease_row = partition_lease('matching-p1')
        r = call(API['chaos-api-1'], 'DELETE', '/api/v1/orders', C, timeout=60)
        paused.append({'kind': 'cancel_all', 'at': now_iso(), 'p1_lease': lease_row,
                       'p1_holder_node': holder_node(lease_row, holders), 'status': r.get('status'),
                       'canceled_count': r.get('body_canceled_count'), 'failed': r.get('body_failed')})
        lease_row = partition_lease('matching-p1')
        close = admin_close(token, C['external_id'])
        paused.append({'kind': 'member_close', 'at': now_iso(), 'p1_lease': lease_row,
                       'p1_holder_node': holder_node(lease_row, holders), 'status': close.get('status'),
                       'error': close.get('error'), 'pending_reasons': close.get('pending_reasons')})
    except Exception as e:
        res['paused_error'] = str(e)
    finally:
        res['unpause_rc'] = pl.docker(['unpause', 'chaos-api-2'])[0]
    res['paused_requests'] = paused
    # (3) 재개 뒤 인계 행이 소유 노드에서 끝나거나 요청자가 포기했는지 본다. 끝나지 않은 행(PENDING·CLAIMED)이 0 이 될 때까지.
    deadline = time.monotonic() + 90
    rows = handoff_rows(C['user_id'])
    while time.monotonic() < deadline and any(x['state'] in ('PENDING', 'CLAIMED') for x in rows):
        time.sleep(1)
        rows = handoff_rows(C['user_id'])
    res['after_resume'] = {'handoff_rows': rows, 'order_states': order_states(ids_c),
                           'open_rows': sum(1 for x in rows if x['state'] in ('PENDING', 'CLAIMED'))}
    res['api1_p1_participate_on'] = set_participate('chaos-api-1', True, 'matching-p1')
    res['ok'] = True
    return write(a.out, res)


def lb_lines_since(t_from):
    """chaos-lb 접근 로그에서 t_from(초 단위 절삭) 뒤의 파티션 형식 줄."""
    rc, out, _ = pl.docker(['logs', '--since', str(int(t_from.timestamp()) - 1), 'chaos-lb'], timeout=60)
    rows = []
    for line in (out or '').splitlines():
        m = LB_LINE.match(line)
        if m:
            rows.append(m.groupdict())
    return rows


def run_zroute(a):
    """Z1d(설계 밖 보충): 봇을 멈춘 파티션 LB 스택에서 LB 로 여섯 요청을 보내 힌트 맵과 기본 업스트림을 실제 요청으로 본다."""
    g = guard()
    if not g['ok']:
        return write(a.out, {'phase': 'Z1d', 'ok': False, 'error': 'guard', 'guard': g})
    try:
        leader, follower, states = roles()
        key = issue_keys('KRW-BTC,KRW-ETH', 1)[0]
    except Exception as e:
        return write(a.out, {'phase': 'Z1d', 'ok': False, 'error': str(e)})
    ips = ip_map()
    by_name = {v: k for k, v in ips.items()}
    lease0 = lease()
    before = {n: s5_counters(p) for n, p in API.items()}
    # 앞선 봇 부하로 팔로워가 파티션 업스트림에서 빠져 있었다면 fail_timeout(2초)이 지나도록 기다린다.
    time.sleep(4)
    t0 = dt.datetime.now(dt.timezone.utc)
    c = ['z1d-%s-%d' % (uuid.uuid4().hex[:8], i) for i in range(4)]
    plan = [
        ('hinted_eth_create', 'POST', '/api/v1/orders', canonical_json(order(c[0], 'KRW-ETH')), None, ['KRW-ETH'], 'KRW-ETH'),
        ('unhinted_eth_create', 'POST', '/api/v1/orders', canonical_json(order(c[1], 'KRW-ETH')), None, [], '-'),
        ('unknown_hint_eth_create', 'POST', '/api/v1/orders', canonical_json(order(c[2], 'KRW-ETH')), None, ['KRW-FOO'],
         'KRW-FOO'),
        ('hinted_btc_create', 'POST', '/api/v1/orders', canonical_json(order(c[3], 'KRW-BTC')), None, ['KRW-BTC'], 'KRW-BTC'),
        ('unhinted_cancel_all', 'DELETE', '/api/v1/orders', None, None, [], '-'),
        ('hinted_get_open', 'GET', '/api/v1/orders/open', None, 'market=KRW-ETH', ['KRW-ETH'], 'KRW-ETH'),
    ]
    rows = []
    for name, method, path, body, query, hints, hint_label in plan:
        r = call(LB_PORT, method, path, key, body=body, query=query, hints=hints)
        rows.append({'case': name, 'method': method, 'path': path, 'hint': hint_label, 'status': r.get('status'),
                     'error': r.get('error'), 'constraint': r.get('constraint')})
        time.sleep(1.2)
    time.sleep(1.5)
    lines = lb_lines_since(t0)
    for row in rows:
        want_path = row['path']
        hit = [x for x in lines if x['m'] == row['method'] and x['u'].split('?')[0] == want_path and x['hint'] == row['hint']]
        x = hit[-1] if hit else None
        row['lb_lines'] = len(hit)
        if x:
            addrs = [s.strip() for s in x['ups'].split(',') if s.strip()]
            row['lb'] = {'status': x['s'], 'ups': [ips.get(s.split(':')[0], s) for s in addrs], 'ust': x['ust']}
    after = {n: s5_counters(p) for n, p in API.items()}
    f_delta = (precheck_not_owner(after[follower]) or 0) - (precheck_not_owner(before[follower]) or 0)

    def first(row):
        return ((row.get('lb') or {}).get('ups') or [None])[0]

    r = {row['case']: row for row in rows}
    axes = {
        'each_request_has_one_lb_line': all(row['lb_lines'] == 1 for row in rows),
        'hinted_eth_first_follower_503_then_leader_201': first(r['hinted_eth_create']) == follower
        and r['hinted_eth_create'].get('lb', {}).get('ups') == [follower, leader]
        and r['hinted_eth_create'].get('lb', {}).get('ust') == '503, 201',
        'unhinted_eth_default_upstream_leader_once': r['unhinted_eth_create'].get('lb', {}).get('ups') == [leader]
        and r['unhinted_eth_create']['status'] == 201,
        'unknown_hint_default_upstream_then_400': r['unknown_hint_eth_create'].get('lb', {}).get('ups') == [leader]
        and r['unknown_hint_eth_create']['status'] == 400
        and r['unknown_hint_eth_create']['constraint'] == 'market_hint_mismatch',
        'hinted_btc_p0_leader_once': r['hinted_btc_create'].get('lb', {}).get('ups') == [leader]
        and r['hinted_btc_create']['status'] == 201,
        'unhinted_cancel_all_default_upstream_leader': r['unhinted_cancel_all'].get('lb', {}).get('ups') == [leader]
        and r['unhinted_cancel_all']['status'] == 200,
        'get_not_split_by_hint': r['hinted_get_open'].get('lb', {}).get('ups') == [leader]
        and r['hinted_get_open']['status'] == 200,
        'follower_not_owner_plus_1': f_delta == 1,
    }
    res = {'phase': 'Z1d', 'at': now_iso(), 'leader': leader, 'follower': follower, 'states': states, 'ip_map': ips,
           'user_id': key['user_id'], 'rows': rows, 'follower_not_owner_delta': f_delta,
           'counters_delta': {n: delta(before[n], after[n]) for n in API}, 'lease': {'before': lease0, 'after': lease()},
           'axes': axes, 'ok': all(axes.values())}
    return write(a.out, res)


def run_keys(a):
    import clean_suite as cs
    os.environ['SCALE_MARKETS'] = a.markets
    os.environ['SCALE_LOAD_KEYS'] = str(a.count)
    n = cs.reissue_keys()
    hit, want = cs.keys_match_db()
    res = {'phase': 'keys', 'markets': a.markets, 'issued': n, 'db_match': hit, 'file_keys': want,
           'ok': n == a.count and hit == want == a.count}
    Path(a.out).write_text(json.dumps(res, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(res, ensure_ascii=False), flush=True)
    return 0 if res['ok'] else 2


LB_LINE = re.compile(r'^\S+ - \S+ \[(?P<t>[^\]]+)\] "(?P<m>[A-Z]+) (?P<u>\S+)[^"]*" (?P<s>\d{3}) \d+ "[^"]*" "[^"]*" '
                     r'ups="(?P<ups>[^"]*)" ust="(?P<ust>[^"]*)" hint="(?P<hint>[^"]*)" route="(?P<route>[^"]*)" '
                     r'rt=(?P<rt>[0-9.]+)')
NO_LIVE = re.compile(r'^(\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}) \[error\].*no live upstreams')


def hint_map(conf_text):
    m = re.search(r'map \$http_x_exchange_market \$exchange_market_upstream \{(.*?)\n    \}', conf_text, re.S)
    return dict(re.findall(r'^\s+(KRW-[A-Z]+)\s+(exchange_p[01]);', m.group(1), re.M)) if m else {}


def primaries(conf_text):
    out = {}
    for name in ('exchange_p0', 'exchange_p1'):
        m = re.search(r'upstream %s \{(.*?)\n    \}' % name, conf_text, re.S)
        first = re.search(r'server (chaos-api-\d):8080 resolve max_fails', m.group(1)) if m else None
        out[name] = first.group(1) if first else None
    return out


def ip_map():
    out = {}
    for name in API:
        rc, raw, _ = pl.docker(['inspect', '-f', '{{json .NetworkSettings.Networks}}', name], timeout=30)
        try:
            ip = json.loads(raw)[NETWORK]['IPAddress'] if rc == 0 else None
        except (ValueError, KeyError, TypeError):
            ip = None
        if ip:
            out[ip] = name
    return out


def parse_time(text):
    return dt.datetime.strptime(text, '%d/%b/%Y:%H:%M:%S %z')


def split_lb(lines, t_from, t_to, ips, leader, conf_text):
    hints = hint_map(conf_text)
    prim = primaries(conf_text)
    follower = [n for n in API if n != leader][0]
    parts = {p: {'primary': prim.get(p), 'markets': sorted(k for k, v in hints.items() if v == p), 'lines': 0,
                 'first_leader': 0, 'first_follower': 0, 'first_follower_503': 0, 'first_follower_503_then_leader': 0,
                 'bad_shape': 0, 'two_attempts': 0, 'final': {}, 'max_rt': 0.0, 'first_unknown': 0}
             for p in ('exchange_p0', 'exchange_p1')}
    other = {'lines': 0, 'first_follower': 0, 'first_follower_503': 0, 'final': {}}
    total = {'order_write_lines': 0, 'order_write_first_follower_503': 0, 'status_502': 0, 'no_live_upstreams': 0,
             'hint_values': {}}
    for line in lines:
        nl = NO_LIVE.match(line)
        if nl:
            ts = dt.datetime.strptime(nl.group(1) + ' +0000', '%Y/%m/%d %H:%M:%S %z')
            if t_from <= ts <= t_to:
                total['no_live_upstreams'] += 1
            continue
        m = LB_LINE.match(line)
        if not m:
            continue
        ts = parse_time(m.group('t'))
        if ts < t_from or ts > t_to:
            continue
        if m.group('s') == '502':
            total['status_502'] += 1
        path = m.group('u').split('?')[0]
        if m.group('m') not in ('POST', 'DELETE') or not re.match(r'^/api/v1/orders(/|$)', path):
            continue
        total['order_write_lines'] += 1
        hint = m.group('hint')
        total['hint_values'][hint] = total['hint_values'].get(hint, 0) + 1
        addrs = [x.strip() for x in m.group('ups').split(',') if x.strip()]
        codes = [x.strip() for x in m.group('ust').split(',') if x.strip()]
        first = ips.get(addrs[0].split(':')[0]) if addrs else None
        first_code = codes[0] if codes else None
        if first == follower and first_code == '503':
            total['order_write_first_follower_503'] += 1
        part = hints.get(hint)
        bucket = parts[part] if part else other
        bucket['lines'] += 1
        bucket['final'][m.group('s')] = bucket['final'].get(m.group('s'), 0) + 1
        if part is None:
            if first == follower:
                bucket['first_follower'] += 1
                if first_code == '503':
                    bucket['first_follower_503'] += 1
            continue
        bucket['max_rt'] = max(bucket['max_rt'], float(m.group('rt')))
        if len(addrs) >= 2:
            bucket['two_attempts'] += 1
        if first == leader:
            bucket['first_leader'] += 1
        elif first == follower:
            bucket['first_follower'] += 1
            second = ips.get(addrs[1].split(':')[0]) if len(addrs) >= 2 else None
            second_code = codes[1] if len(codes) >= 2 else ''
            if first_code == '503':
                bucket['first_follower_503'] += 1
            if first_code == '503' and second == leader and re.fullmatch(r'[24]\d\d', second_code or ''):
                bucket['first_follower_503_then_leader'] += 1
            else:
                bucket['bad_shape'] += 1
        else:
            bucket['first_unknown'] += 1
    return {'leader': leader, 'follower': follower, 'ip_map': ips, 'partitions': parts, 'unhinted_or_unmapped': other,
            'total': total}


def run_lbsplit(a):
    lines = Path(a.log).read_text(encoding='utf-8', errors='replace').splitlines()
    t_from = dt.datetime.fromisoformat(a.window_from)
    t_to = dt.datetime.fromisoformat(a.window_to)
    conf = PARTITION_CONF.read_text(encoding='utf-8')
    ips = json.loads(a.ip_map) if a.ip_map else ip_map()
    res = split_lb(lines, t_from, t_to, ips, a.leader, conf)
    res.update({'phase': 'lbsplit', 'window': {'from': a.window_from, 'to': a.window_to}, 'log_lines': len(lines)})
    Path(a.out).write_text(json.dumps(res, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'lines': res['total']['order_write_lines'],
                      'first_follower_503': res['total']['order_write_first_follower_503']}, ensure_ascii=False),
          flush=True)
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest='cmd', required=True)
    k = sub.add_parser('keys')
    k.add_argument('--markets', required=True)
    k.add_argument('--count', type=int, default=4)
    k.add_argument('--out', required=True)
    for name in ('z2', 'z3', 'z4', 'zroute', 'q16'):
        p = sub.add_parser(name)
        p.add_argument('--out', required=True)
    s = sub.add_parser('lbsplit')
    s.add_argument('--log', required=True)
    s.add_argument('--from', dest='window_from', required=True)
    s.add_argument('--to', dest='window_to', required=True)
    s.add_argument('--leader', required=True)
    s.add_argument('--ip-map', default='')
    s.add_argument('--out', required=True)
    a = ap.parse_args(argv)
    return {'keys': run_keys, 'z2': run_z2, 'z3': run_z3, 'z4': run_z4, 'zroute': run_zroute,
            'lbsplit': run_lbsplit, 'q16': run_q16}[a.cmd](a)


if __name__ == '__main__':
    sys.exit(main())
