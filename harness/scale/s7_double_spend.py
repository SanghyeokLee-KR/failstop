"""Q1(E12) 교차 노드 이중 지출 실행기(ADR 0034 S7, docs/reference/architecture/마켓분할_리더_S7_설계.md 5-2 Q1, 하네스
검증설계 §3 P3 실험). 폐기용 스택(compose 프로젝트 exchange-pml, 이미지 exchange-chaos-app:pml)에서만 돈다. 첫 관문 주행 기록은
harness/evidence/pml-S7-20260926-0300/ 이다. 판정은 s7_axes.py double-spend 가 이 파일의 출력으로 한다.

회원 하나를 가입시키고(선택으로 KRW 를 더 입금하고) 가용 KRW X 를 읽는다. 회마다 장벽(threading.Barrier)으로 두 행위를 동시에
푼다. 짝수 회는 KRW-BTC 지정가 매수(p0 소유 노드 api-1, 18080 직접)와 KRW-ETH 지정가 매수(p1 소유 노드 api-2, 28080 직접)이고,
홀수 회는 한 마켓 매수와 KRW 출금(POST /api/v1/transfers, 다른 노드)이다. 홀수 회의 마켓은 번갈아 고른다. 두 행위 모두 X 의 80% 를
쓰므로 둘 다 성공할 수 없다. 매수 가격은 그 마켓 호가 단위의 1000 배라 시세보다 훨씬 낮아 체결되지 않는다. 회 끝에 성공한 쪽을
취소하고(주문은 그 마켓 소유 노드에, 출금은 출금을 받은 노드에) 가용 KRW 가 X 로, 잠금이 처음 값으로 돌아온 것을 본 뒤 다음 회로
간다. 끝에 두 노드의 정산 23514 계수(exchange_settlement_insufficient_funds_total)와 파티션 치명 강등(WRITER_FATAL·NODE_FATAL)
증분을 적고, 봇을 멈추고 비운 뒤 사후 권위 run 의 C01·C14·C31 위반 행을 적는다. 봇은 멈춘 채 둔다(실행기가 스택을 내린다).

금액은 문자열 십진수(Decimal)로만 다룬다. 회원 세션 토큰과 비밀번호는 출력하지 않는다.

사용:
  python harness/scale/s7_double_spend.py --rounds 1000 --out <json> [--deposit-krw 0]
종료코드: 0 수집 완료(판정은 s7_axes.py), 2 가드 위반이나 전제 불성립, 1 그 밖의 실패.
"""
import argparse
import datetime as dt
import http.client
import json
import re
import secrets
import subprocess
import sys
import threading
import time
import uuid
from decimal import ROUND_DOWN, Decimal
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pg_samplers as pgs  # noqa: E402
import protocol_latch as pl  # noqa: E402
import s7_axes as s7  # noqa: E402

PORTS = {'chaos-api-1': 18080, 'chaos-api-2': 28080}
# 마켓과 소유 노드(선호 배정, S7 설계 4-4). 실행 전에 실제 배정이 이와 같은지 본다.
MARKET_NODE = {'KRW-BTC': 'chaos-api-1', 'KRW-ETH': 'chaos-api-2'}
OTHER = {'chaos-api-1': 'chaos-api-2', 'chaos-api-2': 'chaos-api-1'}
HINT = 'X-Exchange-Market'
SPEND_RATIO = Decimal('0.8')
PRICE_TICKS = 1000
TOKEN_REFRESH_S = 600
FATAL_REASONS = ('WRITER_FATAL', 'NODE_FATAL')
POST_CHECKS = {'C01': 'C01_ASSET_CONSERVATION', 'C14': 'C14_LOCKED_RESERVATION', 'C31': 'C31_RESERVATION_LINK'}
DEMOTION_LINE = re.compile(r'^exchange_partition_demotions_total\{([^}]*)\}\s+(\S+)', re.M)
INSUFFICIENT_LINE = re.compile(r'^exchange_settlement_insufficient_funds_total(?:\{[^}]*\})?\s+(\S+)', re.M)


# ---------------------------------------------------------------- 순수 함수(test_s7_executors.py)

def plan_round(i):
    """회 i 의 두 행위. 행위는 ('order', 마켓, 노드) 나 ('withdraw', None, 노드)다."""
    if i % 2 == 0:
        return [('order', 'KRW-BTC', MARKET_NODE['KRW-BTC']), ('order', 'KRW-ETH', MARKET_NODE['KRW-ETH'])]
    market = 'KRW-BTC' if (i // 2) % 2 == 0 else 'KRW-ETH'
    node = MARKET_NODE[market]
    return [('order', market, node), ('withdraw', None, OTHER[node])]


def spend_of(available):
    """X 의 80% 를 KRW 정수로 내린다."""
    return (Decimal(available) * SPEND_RATIO).quantize(Decimal('1'), rounding=ROUND_DOWN)


def order_terms(spend, tick_size, qty_scale):
    """체결되지 않을 지정가 매수의 가격과 수량. 가격은 호가 단위의 PRICE_TICKS 배, 수량은 spend / 가격을 qty_scale 로 내린다.
    반환은 (가격 문자열, 수량 문자열)이다."""
    price = Decimal(tick_size) * PRICE_TICKS
    quantum = Decimal(1).scaleb(-int(qty_scale))
    volume = (Decimal(spend) / price).quantize(quantum, rounding=ROUND_DOWN)
    if volume <= 0:
        raise ValueError('수량이 0 이하다(spend %s, price %s)' % (spend, price))
    return format(price.normalize(), 'f'), format(volume.normalize(), 'f')


def classify(kind, status, body):
    """응답 하나를 success·insufficient·other 로 가른다(API 설계 §2-3, §4-E)."""
    err = body.get('error') if isinstance(body, dict) and isinstance(body.get('error'), dict) else {}
    name = err.get('name')
    if kind == 'order':
        if status in (200, 201) and isinstance(body, dict) and body.get('id'):
            return 'success'
        if status == 400 and name == 'insufficient_funds':
            return 'insufficient'
    else:
        if status in (200, 201) and isinstance(body, dict) and body.get('status') == 'REQUESTED':
            return 'success'
        if status == 422 and name == 'insufficient_funds':
            return 'insufficient'
    return 'other'


def parse_node_counters(text):
    """정산 23514 계수 합과 파티션 강등 계수(자원·사유별)."""
    insufficient = sum(float(v) for v in INSUFFICIENT_LINE.findall(text or ''))
    demotions = {}
    for labels, value in DEMOTION_LINE.findall(text or ''):
        part = re.search(r'partition="([^"]+)"', labels)
        reason = re.search(r'reason="([^"]+)"', labels)
        if part and reason:
            key = '%s|%s' % (part.group(1), reason.group(1))
            demotions[key] = demotions.get(key, 0.0) + float(value)
    return {'insufficient': insufficient, 'demotions': demotions}


def fatal_delta(before, after):
    """두 표본 사이 치명 강등(WRITER_FATAL·NODE_FATAL) 증분 합. 표본이 없으면 None 이다."""
    if before is None or after is None:
        return None
    total = 0.0
    for key, v in after.items():
        if key.split('|')[1] in FATAL_REASONS:
            total += v - before.get(key, 0.0)
    return total


def summarize(rounds):
    """회 기록을 판정기 입력 모양으로 모은다."""
    out = {'order_insufficient': 0, 'withdraw_insufficient': 0, 'server_errors': 0, 'restore_failures': 0,
           'status_counts': {}}
    for r in rounds:
        for x in r['results']:
            key = '%s:%s' % (x['kind'], x['status'])
            out['status_counts'][key] = out['status_counts'].get(key, 0) + 1
            if x['class'] == 'insufficient':
                out['order_insufficient' if x['kind'] == 'order' else 'withdraw_insufficient'] += 1
            if x['status'] == 500:
                out['server_errors'] += 1
        if not r.get('restored'):
            out['restore_failures'] += 1
    return out


# ---------------------------------------------------------------- HTTP

def call(port, method, path, token=None, body=None, headers=None, timeout=20):
    conn = http.client.HTTPConnection('localhost', port, timeout=timeout)
    t0 = time.monotonic()
    try:
        data = json.dumps(body, separators=(',', ':')).encode('utf-8') if body is not None else None
        hdrs = {'Accept': 'application/json'}
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
        return 0, {'transport_error': type(e).__name__}, round((time.monotonic() - t0) * 1000, 1)
    finally:
        conn.close()
    try:
        parsed = json.loads(raw.decode('utf-8', 'replace')) if raw else {}
    except ValueError:
        parsed = {}
    return status, parsed, round((time.monotonic() - t0) * 1000, 1)


class Member:
    """가입한 회원 하나의 세션. 토큰은 메모리에만 둔다."""

    def __init__(self):
        self.email = 's7e12-%s@example.test' % uuid.uuid4().hex[:12]
        self.password = secrets.token_urlsafe(18)
        self.token = None
        self.at = 0.0

    def signup(self):
        status, body, _ = call(PORTS['chaos-api-1'], 'POST', '/api/v1/auth/signup',
                               body={'email': self.email, 'password': self.password, 'display_name': 's7-e12'})
        if status != 201 or not body.get('access_token'):
            raise RuntimeError('가입 실패(status %s, %s)' % (status, (body.get('error') or {}).get('name')))
        self.token, self.at = body['access_token'], time.monotonic()

    def fresh(self):
        """토큰 수명(900초)보다 먼저 다시 로그인한다."""
        if time.monotonic() - self.at > TOKEN_REFRESH_S:
            status, body, _ = call(PORTS['chaos-api-1'], 'POST', '/api/v1/auth/login',
                                   body={'email': self.email, 'password': self.password})
            if status != 200 or not body.get('access_token'):
                raise RuntimeError('다시 로그인 실패(status %s)' % status)
            self.token, self.at = body['access_token'], time.monotonic()
        return self.token


def krw(member, port):
    status, body, _ = call(port, 'GET', '/api/v1/accounts', token=member.fresh())
    if status != 200:
        return None
    for a in body.get('data') or []:
        if a.get('currency') == 'KRW':
            return {'balance': a.get('balance'), 'locked': a.get('locked')}
    return None


def market_rules():
    status, body, _ = call(PORTS['chaos-api-1'], 'GET', '/api/v1/markets')
    if status != 200:
        raise RuntimeError('마켓 목록 조회 실패(status %s)' % status)
    return {m['market']: m for m in body.get('data') or []}


def scrape(port):
    try:
        import urllib.request
        with urllib.request.urlopen('http://localhost:%d/actuator/prometheus' % port, timeout=10) as r:
            return parse_node_counters(r.read().decode('utf-8', 'replace'))
    except Exception:  # noqa: BLE001
        return None


def act(member, kind, market, node, terms, spend, tag):
    port = PORTS[node]
    if kind == 'order':
        price, volume = terms[market]
        body = {'market': market, 'side': 'bid', 'ord_type': 'limit', 'price': price, 'volume': volume,
                'time_in_force': 'GTC', 'client_order_id': tag}
        status, resp, ms = call(port, 'POST', '/api/v1/orders', token=member.fresh(), body=body, headers={HINT: market})
        ref = resp.get('id') if isinstance(resp, dict) else None
    else:
        body = {'direction': 'WITHDRAW', 'asset': 'KRW', 'amount': format(spend, 'f')}
        status, resp, ms = call(port, 'POST', '/api/v1/transfers', token=member.fresh(), body=body,
                                headers={'Idempotency-Key': tag})
        ref = resp.get('external_id') if isinstance(resp, dict) else None
    err = resp.get('error') if isinstance(resp, dict) and isinstance(resp.get('error'), dict) else {}
    return {'kind': kind, 'market': market, 'node': node, 'status': status, 'error': err.get('name'), 'ms': ms,
            'class': classify(kind, status, resp), 'ref': ref}


def undo(member, x):
    """성공한 행위를 되돌린다. 주문은 그 마켓 소유 노드에 취소를, 출금은 받은 노드에 취소를 보낸다."""
    port = PORTS[x['node']]
    if x['kind'] == 'order':
        status, _, _ = call(port, 'DELETE', '/api/v1/orders/%s' % x['ref'], token=member.fresh(),
                            headers={HINT: x['market']})
    else:
        status, _, _ = call(port, 'DELETE', '/api/v1/transfers/%s' % x['ref'], token=member.fresh())
    return status


def wait_restored(member, want, limit_s):
    start = time.monotonic()
    k = 0
    while time.monotonic() - start < limit_s:
        now = krw(member, PORTS['chaos-api-1' if k % 2 == 0 else 'chaos-api-2'])
        k += 1
        if now and Decimal(now['balance']) == Decimal(want['balance']) and Decimal(now['locked']) == Decimal(want['locked']):
            return True, round(time.monotonic() - start, 3)
        time.sleep(0.1)
    return False, round(time.monotonic() - start, 3)


def run_round(member, i, terms, spend, run_tag, start_balance, restore_s):
    plan = plan_round(i)
    barrier = threading.Barrier(len(plan))
    results = [None] * len(plan)

    def worker(k, step):
        kind, market, node = step
        tag = '%sr%dk%d' % (run_tag, i, k)
        try:
            barrier.wait(timeout=30)
        except threading.BrokenBarrierError:
            results[k] = {'kind': kind, 'market': market, 'node': node, 'status': 0, 'error': 'barrier', 'ms': None,
                          'class': 'other', 'ref': None}
            return
        results[k] = act(member, kind, market, node, terms, spend, tag)

    threads = [threading.Thread(target=worker, args=(k, step)) for k, step in enumerate(plan)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=90)
    successes = [x for x in results if x and x['class'] == 'success']
    undo_status = [undo(member, x) for x in successes if x.get('ref')]
    restored, waited = wait_restored(member, start_balance, restore_s)
    return {'i': i, 'results': [dict(x, ref=None) if x else {'kind': '?', 'status': 0, 'class': 'other'} for x in results],
            'successes': len(successes), 'undo_status': undo_status, 'restored': restored, 'restore_s': waited}


def post_run(run_id):
    """봇을 멈추고 정산 전 NEW 가 0 이 된 뒤 권위 run 의 C01·C14·C31 을 읽는다."""
    stop_rc = subprocess.run(['docker', 'stop', 'chaos-bots'], capture_output=True, timeout=120).returncode
    drained = False
    start = time.monotonic()
    while time.monotonic() - start < 120:
        rc, out, _ = pgs.run_psql("SELECT count(*) FROM command_journal c WHERE c.command_type = 'NEW' AND NOT EXISTS "
                                  "(SELECT 1 FROM event_emission_registry e WHERE e.source_cmd_seq = c.seq)")
        if rc == 0 and out.strip() == '0':
            drained = True
            break
        time.sleep(2)
    status, body, _ = call(PORTS['chaos-api-1'], 'POST', '/harness/v1/invariants/run?runId=%s&window=post' % run_id,
                           timeout=120)
    rows = {}
    if status == 200:
        for c in body.get('checks') or []:
            rows[c.get('checkName')] = c.get('violatedRows')
    return {'bots_stop_rc': stop_rc, 'drained': drained, 'run_status': status,
            'checks': {k: rows.get(v) for k, v in POST_CHECKS.items()},
            'all_checks': body.get('checks') if status == 200 else None}


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument('--rounds', type=int, default=1000)
    ap.add_argument('--deposit-krw', default='0', help='가입 자금에 더할 KRW 입금(정수 문자열)')
    ap.add_argument('--restore-sec', type=float, default=15.0)
    ap.add_argument('--out', required=True)
    a = ap.parse_args(argv)
    res = {'started': pgs.now_iso(), 'requested_rounds': a.rounds, 'rounds': [], 'error': None}

    def save():
        res['finished'] = pgs.now_iso()
        Path(a.out).write_text(json.dumps(res, ensure_ascii=False, indent=2, default=str), encoding='utf-8')

    g = pl.guard()
    if not g['ok']:
        res['error'] = '가드 위반: ' + '; '.join(g['problems'])
        save()
        return 2
    assigned = s7.assignment(s7.all_partitions())[0]
    res['assignment'] = assigned
    if assigned != 'ok':
        res['error'] = '선호 배정이 아니다(%s). Q0 준비를 먼저 한다' % assigned
        save()
        return 2
    try:
        rules = market_rules()
        member = Member()
        member.signup()
        if Decimal(a.deposit_krw) > 0:
            status, _, _ = call(PORTS['chaos-api-1'], 'POST', '/api/v1/transfers', token=member.fresh(),
                                body={'direction': 'DEPOSIT', 'asset': 'KRW', 'amount': a.deposit_krw},
                                headers={'Idempotency-Key': 's7e12-deposit-%s' % uuid.uuid4().hex[:8]})
            res['deposit_status'] = status
        start_balance = krw(member, PORTS['chaos-api-1'])
        if not start_balance:
            raise RuntimeError('KRW 잔고를 읽지 못했다')
        # 두 노드가 같은 회원 토큰을 받아야 교차 노드 경합이 성립한다. 세션 서명 시드가 노드마다 다르면 api-2 가 401 을 내고
        # 한쪽 행위가 인증에서 끝나 '성공 하나'가 경합 없이 채워진다(2026-09-26 첫 주행). 그러면 전제 불성립으로 멈춘다.
        other = krw(member, PORTS['chaos-api-2'])
        res['token_accepted_by'] = {'chaos-api-1': True, 'chaos-api-2': other is not None}
        if other is None:
            res['error'] = 'api-2 가 api-1 이 낸 회원 토큰을 받지 않는다(세션 서명 시드 불일치). 전제 불성립'
            save()
            return 2
        spend = spend_of(start_balance['balance'])
        terms = {m: order_terms(spend, rules[m]['tick_size'], rules[m]['qty_scale']) for m in MARKET_NODE}
        res.update(x_krw=start_balance['balance'], locked_start=start_balance['locked'], spend=format(spend, 'f'),
                   terms=terms)
        before = {n: scrape(p) for n, p in PORTS.items()}
        run_tag = 's7e' + uuid.uuid4().hex[:6]
        for i in range(a.rounds):
            r = run_round(member, i, terms, spend, run_tag, start_balance, a.restore_sec)
            res['rounds'].append(r)
            if i % 50 == 0:
                print(json.dumps({'round': i, 'successes': r['successes'], 'restored': r['restored']}), flush=True)
            if len(res['rounds']) >= 3 and not any(x['restored'] for x in res['rounds'][-3:]):
                res['error'] = '가용 잔고가 세 회 연속 돌아오지 않아 멈췄다'
                break
        after = {n: scrape(p) for n, p in PORTS.items()}
        res['nodes_before'], res['nodes_after'] = before, after
        ok_nodes = all(before[n] is not None and after[n] is not None for n in PORTS)
        res['settlement_insufficient_delta'] = (sum(after[n]['insufficient'] - before[n]['insufficient'] for n in PORTS)
                                                if ok_nodes else None)
        deltas = [fatal_delta(before[n]['demotions'] if before[n] else None,
                              after[n]['demotions'] if after[n] else None) for n in PORTS]
        res['fatal_demotions_delta'] = None if None in deltas else sum(deltas)
        res.update(summarize(res['rounds']))
        post = post_run('Q1-%s' % dt.datetime.now(dt.timezone.utc).strftime('%Y%m%d-%H%M%S'))
        res['post'] = post
        res['post_invariants'] = post['checks']
    except Exception as e:  # noqa: BLE001 - 수집 실패도 파일에 남긴다
        res['error'] = '%s: %s' % (type(e).__name__, e)
        save()
        return 1
    save()
    print(json.dumps({k: res.get(k) for k in ('requested_rounds', 'order_insufficient', 'withdraw_insufficient',
                                              'server_errors', 'restore_failures', 'error')}, ensure_ascii=False))
    return 0


if __name__ == '__main__':
    sys.exit(main())
