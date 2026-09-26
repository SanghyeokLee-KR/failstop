"""S4 관문 X6 접수 경합 실험의 구동기(마켓분할_리더_S4_설계 7-2 X6, API 설계 §2-5-b). harness/scripts/s4-intake-contention.ps1 이 부른다.

부하 계정 둘(L 잠글 계정, M 다른 계정)을 새로 발급해(POST /harness/v1/loadtest-keys) 키를 메모리에만 둔다. 키와 시크릿은 파일과
로그에 남기지 않는다. 잠금은 docker exec chaos-pg psql 세션이 L 의 KRW AVAILABLE 잔고 행을 SELECT … FOR UPDATE 로 쥐고 정해진
초 동안 잔다. L 의 주문은 모두 KRW 를 예약하는 매수라 그 행을 기다린다. 가격은 시세 아래라 체결되지 않는다(정산 재시도가 생기지
않는다).

변형
  a  기본. 잠금 동안 L 과 M 이 같은 마켓에 주문을 하나씩 넣고, 잠금이 풀린 뒤 L 의 주문을 같은 키로 다시 보낸다.
  b  줄 머리 막힘. 잠금 동안 L 이 서로 다른 키로 주문 셋을 동시에 넣고 M 이 200ms 간격으로 다섯을 넣는다.
  c  같은 키와 재생. 잠그기 전 L 이 R 을 넣어 201 을 받는다. 잠금 동안 (i) 새 키 K 의 같은 주문 둘을 동시에, (ii) 다른 키 셋으로
     접수 락을 붙잡은 사이 R 을 같은 키·같은 바디로 다시 보낸다. 잠금이 풀린 뒤 K 로 다시 보낸다.
  d  저트래픽 장기 잠금. 잠금 70초 동안 L 이 1초마다 같은 주문을 보낸다(429 마다 같은 키). 두 층 좀비 판정 계수를 표본으로 뜬다.

판정은 변형마다 축을 참·거짓으로 적고 JSON 으로 남긴다. 종료코드: 0 전 축 통과, 1 실패, 2 준비 실패.

사용: python harness/scale/s4_intake_contention.py --variant a --iterations 30 --out <json>
"""
import argparse
import base64
import concurrent.futures as cf
import hashlib
import hmac
import json
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

LB = 'http://localhost:58080'
NODES = (('chaos-api-1', 18080), ('chaos-api-2', 28080))
MARKET = 'KRW-BTC'
# 시세(약 8,400만) 아래라 체결되지 않는다. 매수라 KRW 를 예약한다.
PRICE = '49900000'
VOLUME = '0.0010'
PSQL = ['docker', 'exec', '-i', 'chaos-pg', 'psql', '-U', 'exchange', '-d', 'exchange', '-X', '-q', '-t', '-A']
M_LATENCY_CAP_MS = 1500
CONTENTION_LINE = re.compile(r'^exchange_order_intake_contention_total\{([^}]*)\}\s+(\S+)')


def b64url(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b'=').decode('ascii')


def sign(method, path, body, access_key, secret):
    """API 설계 §3-3 서명(harness/load/lib/sign.js 와 같은 규약)."""
    canonical = method + '\n' + path + '\n' + (body or '')
    payload = {'access_key': access_key, 'nonce': 'x6-' + uuid.uuid4().hex, 'timestamp': int(time.time() * 1000),
               'query_hash': hashlib.sha512(canonical.encode('utf-8')).hexdigest(), 'query_hash_alg': 'SHA512'}
    header = {'alg': 'HS256', 'typ': 'JWT'}
    signing_input = b64url(json.dumps(header, separators=(',', ':')).encode()) + '.' + \
        b64url(json.dumps(payload, separators=(',', ':')).encode())
    sig = hmac.new(secret.encode('utf-8'), signing_input.encode('ascii'), hashlib.sha256).digest()
    return 'Bearer ' + signing_input + '.' + b64url(sig)


def order_body(coid):
    # JCS 정규형(키 오름차순, 공백 없음). 서버 재정규화와 바이트가 같다.
    return ('{"client_order_id":"%s","market":"%s","ord_type":"limit","price":"%s","side":"bid",'
            '"time_in_force":"GTC","volume":"%s"}') % (coid, MARKET, PRICE, VOLUME)


def http(method, path, key, body=None, timeout=15):
    """(상태, 바디 dict, 소요 ms). 연결 실패는 상태 0 이다."""
    data = body.encode('utf-8') if body is not None else None
    req = urllib.request.Request(LB + path, data=data, method=method)
    req.add_header('Authorization', sign(method, path, body or '', key['access_key'], key['secret']))
    if data is not None:
        req.add_header('Content-Type', 'application/json')
    t0 = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            status, raw = r.status, r.read()
    except urllib.error.HTTPError as e:
        status, raw = e.code, e.read()
    except Exception as e:
        return 0, {'transport_error': type(e).__name__}, (time.monotonic() - t0) * 1000
    try:
        parsed = json.loads(raw.decode('utf-8', 'replace')) if raw else {}
    except ValueError:
        parsed = {'raw': raw[:200].decode('utf-8', 'replace')}
    return status, parsed, (time.monotonic() - t0) * 1000


def place(key, coid):
    status, body, ms = http('POST', '/api/v1/orders', key, order_body(coid))
    err = body.get('error') if isinstance(body, dict) else None
    return {'coid': coid, 'status': status, 'name': (err or {}).get('name'),
            'bucket': ((err or {}).get('detail') or {}).get('bucket'), 'ms': round(ms, 1),
            'replay': status == 200}


def lookup(key, coid):
    status, body, _ = http('GET', '/api/v1/orders/by-client/' + coid, key)
    return {'status': status, 'id': body.get('id') if isinstance(body, dict) else None}


def psql(sql, timeout=60):
    p = subprocess.run(PSQL + ['-c', sql], capture_output=True, text=True, encoding='utf-8', errors='replace',
                       timeout=timeout)
    if p.returncode != 0:
        raise RuntimeError('psql 실패: %s' % p.stderr.strip()[-200:])
    return p.stdout.strip()


def new_rows(user_id, coid):
    return int(psql("SELECT count(*) FROM command_journal WHERE command_type = 'NEW' AND user_id = %d "
                    "AND client_order_id = '%s'" % (user_id, coid)))


class Lock:
    """L 의 KRW AVAILABLE 잔고 행을 seconds 동안 쥐는 psql 세션."""

    def __init__(self, user_id, seconds):
        sql = ("BEGIN;\n"
               "SELECT b.account_id FROM balances b JOIN accounts a ON a.id = b.account_id "
               "WHERE a.owner_type = 'USER' AND a.owner_id = %d AND a.purpose = 'AVAILABLE' "
               "AND a.asset_id = (SELECT id FROM assets WHERE symbol = 'KRW') FOR UPDATE OF b;\n"
               "SELECT pg_sleep(%s);\nROLLBACK;\n") % (user_id, seconds)
        self.proc = subprocess.Popen(PSQL, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                                     text=True, encoding='utf-8')
        self.proc.stdin.write(sql)
        self.proc.stdin.close()
        self.user_id = user_id
        self.wait_held()

    def wait_held(self, limit_s=10):
        """잠금 세션이 행 잠금을 잡을 때까지 기다린다. 잡기 전에 주문을 넣으면 경합이 아니다."""
        deadline = time.monotonic() + limit_s
        while time.monotonic() < deadline:
            n = psql("SELECT count(*) FROM pg_stat_activity WHERE datname = 'exchange' "
                     "AND query LIKE 'SELECT pg_sleep(%' AND state = 'active'")
            if int(n or 0) >= 1:
                return
            time.sleep(0.05)
        raise RuntimeError('잠금 세션이 행을 잡지 못했다')

    def wait(self, timeout=120):
        self.proc.wait(timeout=timeout)
        err = self.proc.stderr.read() if self.proc.stderr else ''
        if self.proc.returncode != 0:
            raise RuntimeError('잠금 세션 실패: %s' % err.strip()[-200:])


def seed_keys():
    """부하 계정 둘(L, M)을 발급한다. 키는 메모리에만 둔다."""
    req = urllib.request.Request('http://localhost:18080/harness/v1/loadtest-keys?market=' + MARKET +
                                 '&count=2&quote=1000000000&base=100', method='POST')
    last = None
    for _ in range(6):
        try:
            with urllib.request.urlopen(req, timeout=90) as r:
                keys = json.load(r)
            if len(keys) == 2:
                break
        except Exception as e:
            last = e
            time.sleep(5)
    else:
        raise RuntimeError('부하 계정 발급 실패: %s' % type(last).__name__)
    out = []
    for k in keys:
        uid = psql("SELECT user_id FROM harness.loadtest_accounts WHERE access_key = '%s'" % k['access_key'])
        out.append({'access_key': k['access_key'], 'secret': k['secret'], 'user_id': int(uid)})
    return out[0], out[1]


def scrape(port):
    try:
        with urllib.request.urlopen('http://localhost:%d/actuator/prometheus' % port, timeout=10) as r:
            return r.read().decode('utf-8', 'replace')
    except Exception:
        return None


def contention_counts():
    """두 노드 합의 {cause|response: 값}."""
    out = {}
    for _, port in NODES:
        text = scrape(port)
        if text is None:
            continue
        for line in text.splitlines():
            m = CONTENTION_LINE.match(line)
            if not m:
                continue
            cause = re.search(r'cause="([^"]+)"', m.group(1))
            resp = re.search(r'response="([^"]+)"', m.group(1))
            key = '%s|%s' % (cause.group(1) if cause else '?', resp.group(1) if resp else '?')
            out[key] = out.get(key, 0.0) + float(m.group(2))
    return out


def admission_rejects():
    """두 노드 합의 주문 수용 거절 누계(exchange_admission_rejected_total{scope="order"}). 긁지 못한 노드는 뺀다."""
    total = 0.0
    for _, port in NODES:
        text = scrape(port)
        m = re.search(r'^exchange_admission_rejected_total\{[^}]*scope="order"[^}]*\}\s+(\S+)', text or '', re.M)
        if m:
            total += float(m.group(1))
    return total


def delta(before, after):
    return {k: after.get(k, 0.0) - before.get(k, 0.0) for k in set(before) | set(after) if after.get(k, 0.0) != before.get(k, 0.0)}


def metric(text, name):
    m = re.search(r'^' + re.escape(name) + r'(?:\{[^}]*\})?\s+(\S+)', text or '', re.M)
    return float(m.group(1)) if m else None


def leader_state():
    """(리더 노드 이름, epoch, 강등 합), 두 노드의 serviceable 게이지와 쓰기 완료율 게이지(좀비 판정 두 층의 판정 창 값)."""
    lead = None
    epoch = None
    for name, port in NODES:
        try:
            with urllib.request.urlopen('http://localhost:%d/actuator/partitions' % port, timeout=5) as r:
                parts = json.load(r).get('partitions') or []
        except Exception:
            continue
        for p in parts:
            if p.get('state') == 'ACCEPTING':
                lead, epoch = name, p.get('epoch')
    demotions = 0.0
    serviceable = {}
    completion = {}
    for name, port in NODES:
        text = scrape(port)
        if text is None:
            continue
        for line in text.splitlines():
            if line.startswith('exchange_partition_demotions_total'):
                demotions += float(line.rsplit(' ', 1)[1])
        serviceable[name] = {'node': metric(text, 'exchange_node_serviceable'),
                             'partition': metric(text, 'exchange_partition_serviceable')}
        completion[name] = {'node': metric(text, 'exchange_node_write_completion_ratio'),
                            'partition': metric(text, 'exchange_partition_write_completion_ratio')}
    return lead, epoch, demotions, serviceable, completion


def coid(prefix):
    return ('x6%s-%s' % (prefix, uuid.uuid4().hex[:20]))[:36]


def variant_a(L, M, iterations, lock_s):
    rows = []
    for i in range(iterations):
        kl, km = coid('aL'), coid('aM')
        lock = Lock(L['user_id'], lock_s)
        with cf.ThreadPoolExecutor(2) as ex:
            fl = ex.submit(place, L, kl)
            fm = ex.submit(place, M, km)
            rl, rm = fl.result(), fm.result()
        rows_before = new_rows(L['user_id'], kl)
        lock.wait()
        retry = place(L, kl)
        rows.append({'i': i, 'L': rl, 'M': rm, 'L_rows_before_retry': rows_before, 'L_retry': retry,
                     'L_rows_after_retry': new_rows(L['user_id'], kl)})
    axes = {
        'L_429_market': all(r['L']['status'] == 429 and r['L']['bucket'] == 'market' for r in rows),
        'L_no_journal_row_before_retry': all(r['L_rows_before_retry'] == 0 for r in rows),
        'L_retry_201_one_row': all(r['L_retry']['status'] == 201 and r['L_rows_after_retry'] == 1 for r in rows),
        'M_201': all(r['M']['status'] == 201 for r in rows),
        'M_latency_le_1500ms': max(r['M']['ms'] for r in rows) <= M_LATENCY_CAP_MS,
    }
    return rows, axes, {'M_latency_max_ms': max(r['M']['ms'] for r in rows)}


def variant_b(L, M, iterations, lock_s):
    rows = []
    for i in range(iterations):
        keys = [coid('bL') for _ in range(3)]
        lock = Lock(L['user_id'], lock_s)
        with cf.ThreadPoolExecutor(8) as ex:
            fl = [ex.submit(place, L, k) for k in keys]
            fm = []
            for _ in range(5):
                fm.append(ex.submit(place, M, coid('bM')))
                time.sleep(0.2)
            rl = [f.result() for f in fl]
            rm = [f.result() for f in fm]
        journal = sum(new_rows(L['user_id'], k) for k in keys)
        lock.wait()
        rows.append({'i': i, 'L': rl, 'M': rm, 'L_rows': journal})
    m_all = [x for r in rows for x in r['M']]
    # M 은 201 이거나 확정 미접수 429 다. 429 는 마켓 접수 경합(bucket market)과, 접수 락 뒤에 줄 선 요청이 노드의 주문 수용
    # 한도를 채워 즉시 거절된 것(bucket node, API 설계 §2-5-a)이 있다. 2026-09-24 첫 주행에서 node 가 나와 기준에 더했다.
    axes = {
        'L_all_429': all(x['status'] == 429 for r in rows for x in r['L']),
        'L_no_journal_rows': all(r['L_rows'] == 0 for r in rows),
        'M_201_or_429_market_or_node': all(x['status'] == 201 or (x['status'] == 429 and x['bucket'] in ('market', 'node'))
                                          for x in m_all),
        'M_no_5xx': all(x['status'] < 500 and x['status'] != 0 for x in m_all),
    }
    ok201 = [x['ms'] for x in m_all if x['status'] == 201]
    by_bucket = {}
    for x in m_all:
        if x['status'] == 429:
            by_bucket[x['bucket'] or '?'] = by_bucket.get(x['bucket'] or '?', 0) + 1
    return rows, axes, {'M_429': sum(1 for x in m_all if x['status'] == 429), 'M_429_by_bucket': by_bucket,
                        'M_201': len(ok201), 'M_201_latency_max_ms': max(ok201) if ok201 else None}


def variant_c(L, M, iterations, lock_s):
    rows = []
    for i in range(iterations):
        r_key = coid('cR')
        first = place(L, r_key)
        k = coid('cK')
        lock = Lock(L['user_id'], lock_s)
        with cf.ThreadPoolExecutor(8) as ex:
            same = [ex.submit(place, L, k), ex.submit(place, L, k)]
            hold = [ex.submit(place, L, coid('cH')) for _ in range(3)]
            time.sleep(0.2)
            replay = ex.submit(place, L, r_key)
            rs = [f.result() for f in same]
            rr = replay.result()
            for f in hold:
                f.result()
        look = lookup(L, r_key)
        lock.wait()
        retry = place(L, k)
        rows.append({'i': i, 'R_first': first, 'same_key': rs, 'R_resend': rr, 'R_lookup': look, 'K_retry': retry,
                     'K_rows': new_rows(L['user_id'], k)})
    axes = {
        'R_first_201': all(r['R_first']['status'] == 201 for r in rows),
        'same_key_both_409_in_progress': all(all(x['status'] == 409 and x['name'] == 'order_in_progress'
                                                 for x in r['same_key']) for r in rows),
        'K_retry_201_one_row': all(r['K_retry']['status'] == 201 and r['K_rows'] == 1 for r in rows),
        'R_resend_409_or_200_not_503': all((r['R_resend']['status'] == 409 and r['R_resend']['name'] == 'order_in_progress')
                                           or r['R_resend']['status'] == 200 for r in rows),
        'R_lookup_200': all(r['R_lookup']['status'] == 200 for r in rows),
    }
    return rows, axes, {}


def variant_d(L, M, iterations, lock_s):
    lead0, epoch0, dem0, _, _ = leader_state()
    key = coid('dL')
    lock = Lock(L['user_id'], lock_s)
    t_end = time.monotonic() + lock_s
    attempts, samples = [], []
    while time.monotonic() < t_end - 1:
        attempts.append(place(L, key))
        _, _, _, serviceable, completion = leader_state()
        samples.append({'t': round(lock_s - (t_end - time.monotonic()), 1), 'serviceable': serviceable,
                        'completion_ratio': completion})
        time.sleep(max(0.0, 1.0 - (attempts[-1]['ms'] / 1000.0)))
    lock.wait(timeout=lock_s + 60)
    final = place(L, key)
    lead1, epoch1, dem1, _, _ = leader_state()
    rows = [{'attempts': attempts, 'samples': samples, 'final': final, 'leader_before': [lead0, epoch0],
             'leader_after': [lead1, epoch1], 'demotions_before': dem0, 'demotions_after': dem1}]
    serviceable_all = [v for s in samples for n in s['serviceable'].values() for v in n.values() if v is not None]
    axes = {
        'attempts_429_market': len(attempts) > 30 and all(a['status'] == 429 and a['bucket'] == 'market' for a in attempts),
        'no_demotion': dem1 == dem0,
        'leader_epoch_unchanged': lead0 is not None and (lead0, epoch0) == (lead1, epoch1),
        'serviceable_always_1': len(serviceable_all) > 0 and all(v == 1.0 for v in serviceable_all),
        'final_201': final['status'] == 201,
    }
    return rows, axes, {'attempts': len(attempts)}


VARIANTS = {'a': variant_a, 'b': variant_b, 'c': variant_c, 'd': variant_d}


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--variant', required=True, choices=sorted(VARIANTS))
    ap.add_argument('--iterations', type=int, default=None)
    ap.add_argument('--lock-sec', type=float, default=None)
    ap.add_argument('--out', required=True)
    a = ap.parse_args(argv)
    iterations = a.iterations or {'a': 30, 'b': 10, 'c': 10, 'd': 1}[a.variant]
    lock_s = a.lock_sec or (70.0 if a.variant == 'd' else 5.0)
    started = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())
    try:
        L, M = seed_keys()
    except Exception as e:
        Path(a.out).write_text(json.dumps({'variant': a.variant, 'error': str(e)}, ensure_ascii=False), encoding='utf-8')
        print(json.dumps({'variant': a.variant, 'ok': False, 'error': str(e)}, ensure_ascii=False), flush=True)
        return 2
    before = contention_counts()
    admission_before = admission_rejects()
    rows, axes, record = VARIANTS[a.variant](L, M, iterations, lock_s)
    after = contention_counts()
    contention = delta(before, after)
    # 봇 주문까지 포함한 노드 수용 거절 증가(기록). 접수 락 뒤에 줄 선 요청이 노드의 주문 수용 한도를 채우면 다른 마켓 주문도
    # 거절된다.
    record['admission_rejected_order_delta'] = admission_rejects() - admission_before
    if a.variant == 'a':
        axes['contention_55P03_429_ge_iterations'] = contention.get('55P03|429', 0.0) >= iterations
    res = {'variant': a.variant, 'iterations': iterations, 'lock_sec': lock_s, 'started': started,
           'finished': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
           'accounts': {'L_user_id': L['user_id'], 'M_user_id': M['user_id']},
           'contention_delta': contention, 'record': record, 'axes': axes, 'ok': all(axes.values()), 'rows': rows}
    Path(a.out).write_text(json.dumps(res, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'variant': a.variant, 'ok': res['ok'], 'failed': [k for k, v in axes.items() if not v],
                      'record': record, 'contention_delta': contention}, ensure_ascii=False), flush=True)
    return 0 if res['ok'] else 1


if __name__ == '__main__':
    sys.exit(main())
