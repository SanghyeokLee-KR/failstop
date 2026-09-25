"""s5_route_probe 의 순수 함수 시험(ADR 0034 S5, docs/reference/architecture/마켓분할_리더_S5_설계.md 7-2 Z1).

LB 접근 로그(파티션 형식) 분해가 힌트별 첫 시도 업스트림과 '503 뒤 리더' 모양을 바르게 세는지, 창 밖 줄과 조회 줄을 빼는지,
no live upstreams 와 502 를 세는지 본다. 선검사 거절 모양 판정도 본다. docker 와 네트워크는 부르지 않는다.

돌리는 법:
  python -B -m unittest discover -s harness/scale -p "test_*.py"
"""
import datetime as dt
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import s5_route_probe as p  # noqa: E402

CONF = open(p.PARTITION_CONF, encoding='utf-8').read()
IPS = {'10.0.0.11': 'chaos-api-1', '10.0.0.12': 'chaos-api-2'}


def line(t, method, path, status, ups, ust, hint, rt='0.010'):
    return ('10.0.0.5 - - [%s] "%s %s HTTP/1.1" %s 120 "-" "k6/1.4.0" ups="%s" ust="%s" hint="%s" route="-" rt=%s'
            % (t, method, path, status, ups, ust, hint, rt))


T_FROM = dt.datetime(2026, 9, 25, 6, 0, 0, tzinfo=dt.timezone.utc)
T_TO = dt.datetime(2026, 9, 25, 6, 10, 0, tzinfo=dt.timezone.utc)
IN = '25/Sep/2026:06:05:00 +0000'
OUT = '25/Sep/2026:06:20:00 +0000'


class SplitTest(unittest.TestCase):

    def test_leader_primary_partition_and_follower_retry_shape(self):
        lines = [
            line(IN, 'POST', '/api/v1/orders', 201, '10.0.0.11:8080', '201', 'KRW-BTC'),
            line(IN, 'DELETE', '/api/v1/orders/abc', 200, '10.0.0.11:8080', '200', 'KRW-XRP'),
            line(IN, 'POST', '/api/v1/orders', 201, '10.0.0.12:8080, 10.0.0.11:8080', '503, 201', 'KRW-ETH', '0.021'),
            line(IN, 'POST', '/api/v1/orders', 429, '10.0.0.12:8080, 10.0.0.11:8080', '503, 429', 'KRW-ETH'),
            # 팔로워가 fail_timeout 동안 빠져 백업(리더)이 첫 시도다.
            line(IN, 'POST', '/api/v1/orders', 201, '10.0.0.11:8080', '201', 'KRW-ETH'),
            # 모양이 틀린 줄: 팔로워 503 뒤 다시 팔로워
            line(IN, 'POST', '/api/v1/orders', 503, '10.0.0.12:8080', '503', 'KRW-ETH'),
            # 조회와 창 밖 줄은 세지 않는다
            line(IN, 'GET', '/api/v1/orders/by-client/x', 200, '10.0.0.11:8080', '200', '-'),
            line(OUT, 'POST', '/api/v1/orders', 201, '10.0.0.12:8080, 10.0.0.11:8080', '503, 201', 'KRW-ETH'),
            line(IN, 'POST', '/api/v1/orders', 502, 'exchange_p1', '502', 'KRW-SOL'),
            '2026/09/25 06:05:01 [error] 29#29: *1 no live upstreams while connecting to upstream',
            '2026/09/25 06:25:01 [error] 29#29: *1 no live upstreams while connecting to upstream',
        ]
        res = p.split_lb(lines, T_FROM, T_TO, IPS, 'chaos-api-1', CONF)
        p0, p1 = res['partitions']['exchange_p0'], res['partitions']['exchange_p1']
        self.assertEqual('chaos-api-1', p0['primary'])
        self.assertEqual(2, p0['lines'])
        self.assertEqual(2, p0['first_leader'])
        self.assertEqual(0, p0['first_follower'])
        self.assertEqual(5, p1['lines'])
        self.assertEqual(3, p1['first_follower'])
        self.assertEqual(3, p1['first_follower_503'])
        self.assertEqual(2, p1['first_follower_503_then_leader'])
        self.assertEqual(1, p1['bad_shape'])
        self.assertEqual(1, p1['first_leader'])
        self.assertEqual(1, p1['first_unknown'])
        self.assertEqual(2, p1['two_attempts'])
        self.assertAlmostEqual(0.021, p1['max_rt'])
        self.assertEqual(3, res['total']['order_write_first_follower_503'])
        self.assertEqual(7, res['total']['order_write_lines'])
        self.assertEqual(1, res['total']['status_502'])
        self.assertEqual(1, res['total']['no_live_upstreams'])

    def test_unmapped_hint_goes_to_the_other_bucket(self):
        lines = [line(IN, 'POST', '/api/v1/orders', 400, '10.0.0.11:8080', '400', 'krw-btc'),
                 line(IN, 'DELETE', '/api/v1/orders?market=KRW-BTC', 200, '10.0.0.11:8080', '200', '-')]
        res = p.split_lb(lines, T_FROM, T_TO, IPS, 'chaos-api-1', CONF)
        self.assertEqual(2, res['unhinted_or_unmapped']['lines'])
        self.assertEqual(0, res['partitions']['exchange_p0']['lines'])
        self.assertEqual({'krw-btc': 1, '-': 1}, res['total']['hint_values'])


class ShapeTest(unittest.TestCase):

    def test_not_owner_shape_needs_every_marker(self):
        good = {'status': 503, 'retry_after': '1', 'route_header': 'not-owner', 'detail_route': 'not_owner',
                'ratelimit_account_header': False}
        self.assertTrue(p.not_owner_shape(good))
        for k, v in (('status', 502), ('retry_after', None), ('route_header', None), ('detail_route', None),
                     ('ratelimit_account_header', True)):
            bad = dict(good)
            bad[k] = v
            self.assertFalse(p.not_owner_shape(bad), k)

    def test_canonical_json_sorts_keys_without_spaces(self):
        body = p.canonical_json(p.order('c1', 'KRW-BTC'))
        self.assertEqual('{"client_order_id":"c1","market":"KRW-BTC","ord_type":"limit","price":"49900000",'
                         '"side":"bid","time_in_force":"GTC","volume":"0.0010"}', body)
        self.assertNotIn('market', p.canonical_json(p.order('c2', 'KRW-BTC', with_market=False)))


if __name__ == '__main__':
    unittest.main()
