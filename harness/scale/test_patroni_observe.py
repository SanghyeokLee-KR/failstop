"""patroni_observe 시험. 서명 canonical, 표본 해석, 출금 요청기의 같은 키 재전송을 본다. 네트워크를 쓰지 않는다.

돌리는 법:
  python -m unittest discover -s harness/scale -p "test_*.py"
"""
import base64
import hashlib
import hmac
import json
import os
import tempfile
import threading
import unittest

import patroni_observe as po


def b64decode(part):
    return base64.urlsafe_b64decode(part + '=' * (-len(part) % 4))


class SignTest(unittest.TestCase):

    def test_query_canonical_sorts_keys(self):
        self.assertEqual('GET\n/api/v1/orders/open\nlimit=5&market=KRW-BTC',
                         po.canonical('GET', '/api/v1/orders/open', {'market': 'KRW-BTC', 'limit': '5'}))
        self.assertEqual('GET\n/api/v1/accounts\n', po.canonical('GET', '/api/v1/accounts'))

    def test_signature_matches_server_convention(self):
        token = po.sign('GET', '/api/v1/orders/open', 'AK1', 'secret', query={'market': 'KRW-BTC'},
                        now_ms=1790000000000, nonce='n-1')
        self.assertTrue(token.startswith('Bearer '))
        header, payload, sig = token[len('Bearer '):].split('.')
        claims = json.loads(b64decode(payload))
        self.assertEqual('AK1', claims['access_key'])
        self.assertEqual(hashlib.sha512(b'GET\n/api/v1/orders/open\nmarket=KRW-BTC').hexdigest(), claims['query_hash'])
        expected = hmac.new(b'secret', (header + '.' + payload).encode(), hashlib.sha256).digest()
        self.assertEqual(expected, b64decode(sig))


class SampleTest(unittest.TestCase):

    def test_cluster_sample_reads_leader_sync_and_timeline(self):
        body = json.dumps({'members': [
            {'name': 'patroni-db-1', 'role': 'leader', 'state': 'running', 'timeline': 2, 'host': 'db-1-repl'},
            {'name': 'patroni-db-2', 'role': 'sync_standby', 'state': 'streaming', 'timeline': 2, 'lag': 0}]}).encode()
        s = po.cluster_sample(10.0, 200, body, '59008')
        self.assertEqual('patroni-db-1', s['leader'])
        self.assertEqual(['patroni-db-2'], s['sync'])
        self.assertEqual(2, s['timeline'])
        self.assertNotIn('leader', po.cluster_sample(11.0, 503, b'', '59008'))

    def test_status_sample(self):
        body = json.dumps({'trading_suspended': True, 'trading_state': 'SUSPENDED',
                           'reason': 'replication_unavailable', 'since': 1}).encode()
        s = po.status_sample(1.0, 'api-1', 200, body)
        self.assertTrue(s['trading_suspended'])
        self.assertEqual('replication_unavailable', s['reason'])

    def test_haproxy_sample_lists_up_servers_of_pg_write(self):
        text = '\n'.join([
            'haproxy_server_status{proxy="pg_write",server="db-1-app",state="UP"} 1',
            'haproxy_server_status{proxy="pg_write",server="db-1-app",state="DOWN"} 0',
            'haproxy_server_status{proxy="pg_write",server="db-2-app",state="UP"} 0',
            'haproxy_server_status{proxy="pg_write",server="db-2-app",state="DOWN"} 1',
            'haproxy_server_status{proxy="stats",server="x",state="UP"} 1'])
        self.assertEqual(['db-1-app'], po.haproxy_sample(1.0, 'haproxy-1', 200, text)['up'])

    def test_pool_sample(self):
        text = '\n'.join([
            'hikaricp_connections_active{application="exchange",pool="HikariPool-1",} 7.0',
            'hikaricp_connections_active{application="exchange",pool="exchange-append",} 1.0',
            'exchange_admission_in_flight{application="exchange",scope="query",} 3.0'])
        s = po.pool_sample(1.0, 'api-1', 200, text)
        self.assertEqual({'HikariPool-1': 7.0, 'exchange-append': 1.0}, s['active'])
        self.assertEqual({'query': 3.0}, s['inflight'])


class FakeHttp:
    def __init__(self, codes):
        self.codes = list(codes)
        self.calls = []

    def __call__(self, method, url, headers=None, body=None, timeout=None):
        self.calls.append((method, url, dict(headers or {}), body))
        code = self.codes.pop(0) if self.codes else 201
        raw = b'{"error":{"name":"trading_suspended"}}' if code == 423 else b'{}'
        return code, raw, 1.0, {}


class WithdrawTest(unittest.TestCase):

    def test_uncertain_keys_are_held_and_resent_with_the_same_key(self):
        with tempfile.TemporaryDirectory() as d:
            journal = po.Journal(os.path.join(d, 'o.jsonl'))
            keys = iter('k%d' % i for i in range(100))
            # 201, 423, 0(시간 초과), 201(새 키가 게이트를 지남), 그 뒤 재전송 둘 201
            http = FakeHttp([201, 423, 0, 201, 201, 201])
            req = po.WithdrawRequester(journal, threading.Event(), 'token', http=http, new_key=lambda: next(keys))
            for _ in range(5):
                req.tick()
            journal.close()
            sent = [(c[2]['Idempotency-Key']) for c in http.calls]
        self.assertEqual(['k0', 'k1', 'k2', 'k3', 'k1', 'k2'], sent, '423·시간 초과 키는 같은 키로 다시 낸다')
        self.assertEqual([], req.pending)
        self.assertTrue(all(c[2]['Authorization'] == 'Bearer token' for c in http.calls))

    def test_new_requests_continue_while_suspended_and_keys_accumulate(self):
        with tempfile.TemporaryDirectory() as d:
            journal = po.Journal(os.path.join(d, 'o.jsonl'))
            keys = iter('k%d' % i for i in range(100))
            http = FakeHttp([423, 423, 503])
            req = po.WithdrawRequester(journal, threading.Event(), 'token', http=http, new_key=lambda: next(keys))
            for _ in range(3):
                req.tick()
            req.finish()
            journal.close()
            with open(os.path.join(d, 'o.jsonl'), encoding='utf-8') as fh:
                end = [json.loads(line) for line in fh][-1]
        self.assertEqual(['k0', 'k1', 'k2'], req.pending)
        self.assertEqual(['k0', 'k1', 'k2'], end['pending'])

    def test_rate_limited_response_is_not_taken_as_resume(self):
        with tempfile.TemporaryDirectory() as d:
            journal = po.Journal(os.path.join(d, 'o.jsonl'))
            keys = iter('k%d' % i for i in range(100))
            http = FakeHttp([423, 429, 201, 201])
            req = po.WithdrawRequester(journal, threading.Event(), 'token', http=http, new_key=lambda: next(keys))
            for _ in range(4):
                req.tick()
            journal.close()
        self.assertEqual(['k0', 'k1', 'k2', 'k0'], [c[2]['Idempotency-Key'] for c in http.calls])


class LoopTest(unittest.TestCase):

    def test_every_survives_errors(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, 'o.jsonl')
            journal = po.Journal(path)
            stop = threading.Event()
            calls = []

            def boom():
                calls.append(1)
                if len(calls) >= 3:
                    stop.set()
                raise RuntimeError('관측 실패')

            obs = po.Observer(journal, stop)
            obs.every(0.01, boom)
            journal.close()
            with open(path, encoding='utf-8') as fh:
                errors = [json.loads(line) for line in fh]
        self.assertEqual(3, len(calls))
        self.assertEqual({'error'}, {e['kind'] for e in errors})


if __name__ == '__main__':
    unittest.main()
