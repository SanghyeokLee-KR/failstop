"""s6_relay_axes 의 판정 함수 시험. 도커·DB·HTTP 없이 돈다.

돌리는 법: python -B -m unittest harness/scale/test_s6_relay_axes.py
"""
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import s6_relay_axes as ax  # noqa: E402


def state(node, markets, **extra):
    s = {'node': node, 'at': extra.pop('at', 1000), 'markets': markets}
    s.update(extra)
    return s


class MetricsAndHandshakeTest(unittest.TestCase):

    def test_metric_value_sums_matching_tags_and_is_none_when_absent(self):
        text = '\n'.join([
            '# HELP exchange_ws_gate_open x',
            'exchange_ws_gate_open 0.0',
            'exchange_ws_relay_handshake_refused_total{reason="relay_required",} 3.0',
            'exchange_ws_relay_handshake_refused_total{reason="relay_unavailable",} 1.0',
            'jvm_threads_live_threads 55.0',
        ])
        m = ax.parse_metrics(text)
        self.assertEqual(0.0, ax.metric_value(m, 'exchange_ws_gate_open'))
        self.assertEqual(3.0, ax.metric_value(m, 'exchange_ws_relay_handshake_refused_total', reason='relay_required'))
        self.assertEqual(4.0, ax.metric_value(m, 'exchange_ws_relay_handshake_refused_total'))
        self.assertIsNone(ax.metric_value(m, 'exchange_ws_relay_degraded'), '없는 계열을 0 으로 읽지 않는다')
        self.assertNotIn('jvm_threads_live_threads', m)

    def test_parse_handshake_reads_status_and_retry_after(self):
        raw = b'HTTP/1.1 503 \r\nRetry-After: 5\r\nContent-Length: 0\r\n\r\n'
        self.assertEqual({'status': 503, 'retry_after': '5'}, ax.parse_handshake(raw))
        up = b'HTTP/1.1 101 \r\nUpgrade: websocket\r\nConnection: upgrade\r\n\r\n'
        self.assertEqual({'status': 101, 'retry_after': None}, ax.parse_handshake(up))
        self.assertEqual({'status': None, 'retry_after': None}, ax.parse_handshake(b''))


class GateAxesTest(unittest.TestCase):

    def nodes(self, follower_seq=0, follower_epoch=7, follower_status=503, retry='5', gate_open=0.0, refused=1.0):
        zero = [{'status': 200, 'epoch': follower_epoch, 'seq': follower_seq}] * 3
        return {
            'chaos-api-1': {'book': zero, 'ticker': zero,
                            'handshake': {'status': follower_status, 'retry_after': retry},
                            'metrics': {'exchange_ws_gate_open': gate_open,
                                        'exchange_ws_relay_handshake_refused_total{reason="relay_required",}': refused}},
            'chaos-api-2': {'book': [{'status': 200, 'epoch': 7, 'seq': 55}], 'ticker': [],
                            'handshake': {'status': 101, 'retry_after': None},
                            'metrics': {'exchange_ws_gate_open': 1.0}},
        }

    LB_LOG = '\n'.join([
        '2026/09/25 10:00:00 [warn] 29#29: *7 upstream server temporarily disabled while reading response header from '
        'upstream, client: 172.18.0.1, server: , request: "GET /ws/v1 HTTP/1.1", upstream: "http://172.18.0.5:8080/ws/v1"',
        '172.18.0.1 - - [25/Sep/2026:10:00:00 +0000] "GET /ws/v1 HTTP/1.1" 101 0 "-" "-"',
    ])

    def test_simple_mode_follower_refuses_and_leader_accepts(self):
        axes = ax.gate_axes('simple', self.nodes(), {'status': 101}, 'chaos-api-2', 7, self.LB_LOG, 'chaos-api-1')
        self.assertTrue(all(axes.values()), [k for k, v in axes.items() if not v])

    def test_simple_mode_fails_on_counter_or_old_epoch_follower_values(self):
        axes = ax.gate_axes('simple', self.nodes(follower_seq=55), {'status': 101}, 'chaos-api-2', 7, self.LB_LOG)
        self.assertFalse(axes['follower_orderbook_seq0_lease_epoch'], '팔로워가 자기 카운터를 내면 거짓이다')
        axes = ax.gate_axes('simple', self.nodes(follower_epoch=6), {'status': 101}, 'chaos-api-2', 7, self.LB_LOG)
        self.assertFalse(axes['follower_orderbook_seq0_lease_epoch'], '옛 epoch 는 리스 epoch 가 아니다')
        axes = ax.gate_axes('simple', self.nodes(), {'status': 101}, 'chaos-api-2', None, self.LB_LOG)
        self.assertFalse(axes['follower_ticker_seq0_lease_epoch'], '리스 epoch 를 못 읽으면 거짓이다')

    def test_simple_mode_needs_503_with_retry_after_and_closed_gate(self):
        axes = ax.gate_axes('simple', self.nodes(retry=None), {'status': 101}, 'chaos-api-2', 7, self.LB_LOG)
        self.assertFalse(axes['follower_handshake_503_retry_after_5'])
        axes = ax.gate_axes('simple', self.nodes(gate_open=1.0, refused=0.0), {'status': 101}, 'chaos-api-2', 7, '')
        self.assertFalse(axes['follower_gate_closed'])
        self.assertFalse(axes['follower_refused_relay_required_ge_1'])
        self.assertFalse(axes['lb_log_503_then_101'])

    def test_no_leader_fails_closed(self):
        axes = ax.gate_axes('simple', self.nodes(), {'status': 101}, None, 7, self.LB_LOG)
        self.assertFalse(axes['leader_found'])

    def test_broker_mode_needs_both_nodes_open(self):
        n = self.nodes(follower_status=101, gate_open=1.0)
        axes = ax.gate_axes('broker', n, {'status': 101}, 'chaos-api-2', 7, '')
        self.assertTrue(all(axes.values()), axes)
        n = self.nodes()
        axes = ax.gate_axes('broker', n, {'status': 101}, 'chaos-api-2', 7, '')
        self.assertFalse(axes['chaos-api-1_gate_open'])
        self.assertFalse(axes['chaos-api-1_handshake_101'])


class LbLogTest(unittest.TestCase):

    def test_relay_format_upstream_status_list_counts(self):
        line = ('172.18.0.1 - - [25/Sep/2026:10:00:01 +0000] "GET /ws/v1 HTTP/1.1" 101 0 "-" "k6" '
                'up=172.18.0.5:8080, 172.18.0.6:8080 ups=503, 101')
        self.assertTrue(ax.lb_ws_retry_seen(line))

    def test_warn_on_post_or_101_before_warn_does_not_count(self):
        text = '\n'.join([
            '172.18.0.1 - - [25/Sep/2026:10:00:00 +0000] "GET /ws/v1 HTTP/1.1" 101 0 "-" "-"',
            '2026/09/25 10:00:01 [warn] 29#29: *9 upstream server temporarily disabled while reading response header '
            'from upstream, client: 172.18.0.9, server: , request: "POST /api/v1/orders HTTP/1.1"',
        ])
        self.assertFalse(ax.lb_ws_retry_seen(text), '다른 경로의 경고나 경고 앞의 101 은 증거가 아니다')


class FreshnessTest(unittest.TestCase):

    def pair(self, t, lead, fol):
        return {'t': t, 'leader': {'status': 200, 'epoch': lead[0], 'seq': lead[1]},
                'follower': {'status': 200, 'epoch': fol[0], 'seq': fol[1]}}

    def test_rule(self):
        self.assertTrue(ax.freshness_ok(self.pair(1, (5, 10), (5, 0))), 'seq 0 은 기준점이 아니라 통과다')
        self.assertTrue(ax.freshness_ok(self.pair(1, (5, 10), (5, 10))))
        self.assertTrue(ax.freshness_ok(self.pair(1, (5, 10), (6, 1))), '더 새 기간이면 통과다')
        self.assertFalse(ax.freshness_ok(self.pair(1, (5, 10), (5, 9))), '뒤처진 미러가 seq≠0 으로 나갔다')
        self.assertFalse(ax.freshness_ok(self.pair(1, (6, 3), (5, 90))), '옛 기간의 큰 seq 는 뒤처진 것이다')
        bad = self.pair(1, (5, 10), (5, 11))
        bad['follower']['status'] = 503
        self.assertFalse(ax.freshness_ok(bad), '응답을 못 받은 표본은 판정을 채우지 못한다')

    def test_summary_window_and_ratios(self):
        pairs = [self.pair(t, (5, t), (5, t)) for t in range(1, 11)] + [self.pair(11, (5, 11), (5, 0))]
        s = ax.freshness_summary(pairs)
        self.assertEqual(11, s['pairs'])
        self.assertTrue(s['all_ok'])
        self.assertAlmostEqual(10 / 11, s['follower_nonzero_ratio'])
        self.assertFalse(s['follower_all_seq0'])
        only_zero = ax.freshness_summary(pairs, 11, 11)
        self.assertTrue(only_zero['follower_all_seq0'])
        self.assertFalse(ax.freshness_summary([], 0, 1)['all_ok'], '표본이 없으면 통과가 아니다')


class WatchTest(unittest.TestCase):

    def test_parse_json_log_lines_and_keep_series(self):
        s1 = state('ws://chaos-api-1:8080/ws/v1', {'KRW-BTC': {'orderbook': {'realtime': 5}, 'trade': {'messages': 1},
                                                              'ticker': {'realtime': 2}}}, at=1000)
        s2 = state('ws://chaos-api-1:8080/ws/v1', {'KRW-BTC': {'orderbook': {'realtime': 9}, 'trade': {'messages': 1},
                                                              'ticker': {'realtime': 3}}}, at=11000, final=True)
        lines = [json.dumps({'level': 'info', 'msg': 'WATCH ' + json.dumps(s2), 'source': 'console'}),
                 'garbage line',
                 json.dumps({'level': 'info', 'msg': 'WATCH ' + json.dumps(s1), 'source': 'console'}),
                 'WATCH ' + json.dumps(state('ws://chaos-lb:8080/ws/v1', {}, at=5))]
        got = ax.parse_watch_log('\n'.join(lines))
        self.assertEqual({'ws://chaos-api-1:8080/ws/v1', 'ws://chaos-lb:8080/ws/v1'}, set(got))
        api1 = got['ws://chaos-api-1:8080/ws/v1']
        self.assertTrue(api1['latest']['final'], '시각 순으로 마지막 상태를 고른다')
        self.assertEqual([[5, 1, 2], [9, 1, 3]], [p['markets']['KRW-BTC'] for p in api1['series']])
        self.assertEqual(11000, ax.first_increase_after(api1['series'], 'KRW-BTC', 1000))
        self.assertIsNone(ax.first_increase_after(api1['series'], 'KRW-BTC', 11000))
        self.assertIsNone(ax.first_increase_after(api1['series'], 'KRW-ETH', 0))


class RangesAndEpochsTest(unittest.TestCase):

    def test_book_set_diff_ratio(self):
        a = state('a', {'KRW-BTC': {'orderbook': {'ranges': {'5': [[1, 100]]}}}})
        b = state('b', {'KRW-BTC': {'orderbook': {'ranges': {'5': [[1, 50], [52, 100]]}}}})
        self.assertAlmostEqual(1 / 100, ax.book_set_diff_ratio(a, b))
        self.assertEqual(0.0, ax.book_set_diff_ratio(a, a))
        c = state('c', {'KRW-BTC': {'orderbook': {'ranges': {'6': [[1, 100]]}}}})
        self.assertEqual(1.0, ax.book_set_diff_ratio(a, c), '다른 기간의 같은 seq 는 다른 원소다')
        self.assertIsNone(ax.book_set_diff_ratio(state('x', {}), state('y', {})), '둘 다 못 받았으면 판정하지 않는다')

    def test_epochs_gaps_closes_and_refusals(self):
        st = state('n', {'KRW-BTC': {'orderbook': {'epochs': [5, 6], 'gaps': [{'at': 100}, {'at': 900}]},
                                     'trade': {'epochs': [6]}, 'ticker': {'epochs': [5], 'gaps': [{'at': 950}]}}},
                   closes=[{'at': 500, 'code': 1013}, {'at': 600, 'code': 1006}],
                   errors=[{'at': 510, 'handshake': True}, {'at': 520, 'handshake': False}],
                   refusals=[{'at': 515, 'status': 503}, {'at': 530, 'status': 200}])
        self.assertEqual([5, 6], ax.market_epochs(st, 'KRW-BTC'))
        self.assertEqual(3, ax.dense_gaps(st, 'KRW-BTC'))
        self.assertEqual(2, ax.dense_gaps(st, 'KRW-BTC', 800))
        self.assertEqual(1, ax.closes_with(st, 1013, 400))
        self.assertEqual(0, ax.closes_with(st, 1013, 550))
        self.assertEqual({'handshake_errors': 1, 'status_503': 1}, ax.refusals(st, 500))

    def test_epoch_rise_once(self):
        st = state('n', {'KRW-BTC': {'orderbook': {'epochs': [5, 6]}}},
                   epochRises=[{'at': 20_000, 'market': 'KRW-BTC', 'epoch': 6}])
        ok, first = ax.epoch_rise_once(st, 'KRW-BTC', 10_000, 15_000)
        self.assertTrue(ok)
        self.assertEqual(20_000, first)
        late, _ = ax.epoch_rise_once(st, 'KRW-BTC', 10_000, 5_000)
        self.assertFalse(late, 'RTO + 5초 밖의 첫 메시지는 거짓이다')
        twice = state('n', {'KRW-BTC': {'orderbook': {'epochs': [5, 6, 7]}}},
                      epochRises=[{'at': 20_000, 'market': 'KRW-BTC', 'epoch': 6},
                                  {'at': 21_000, 'market': 'KRW-BTC', 'epoch': 7}])
        self.assertFalse(ax.epoch_rise_once(twice, 'KRW-BTC', 10_000, 15_000)[0], '두 번 오르면 거짓이다')
        none = state('n', {'KRW-BTC': {'orderbook': {'epochs': [5]}}})
        self.assertFalse(ax.epoch_rise_once(none, 'KRW-BTC', 10_000, 15_000)[0])


class PromHelpersTest(unittest.TestCase):

    def test_first_time_all_requires_every_series_at_the_same_time(self):
        series = [{'metric': {'link': 'pub'}, 'values': [(10.0, 0.0), (12.0, 1.0), (14.0, 1.0)]},
                  {'metric': {'link': 'sub'}, 'values': [(10.0, 0.0), (12.0, 0.0), (14.0, 1.0)]}]
        self.assertEqual(14.0, ax.first_time_all(series, lambda v: v == 1, 10.0))
        self.assertEqual(10.0, ax.first_time_all(series, lambda v: v == 0, 0.0))
        self.assertIsNone(ax.first_time_all([], lambda v: True, 0.0))
        self.assertIsNone(ax.first_time_all(None, lambda v: True, 0.0))

    def test_accept_ratio_fails_closed(self):
        self.assertAlmostEqual(0.95, ax.accept_ratio(9.5, 10.0))
        self.assertIsNone(ax.accept_ratio(None, 10.0))
        self.assertIsNone(ax.accept_ratio(9.0, 0.0))


if __name__ == '__main__':
    unittest.main()
