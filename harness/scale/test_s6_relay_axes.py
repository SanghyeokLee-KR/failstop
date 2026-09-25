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
        self.assertFalse(axes['lb_follower_503_then_101'], 'LB 로그 줄도 거절 계수 증가도 없으면 거짓이다')
        axes = ax.gate_axes('simple', self.nodes(), {'status': 101}, 'chaos-api-2', 7, '', lb_refused_delta=1.0)
        self.assertTrue(axes['lb_follower_503_then_101'], '기본 nginx.conf 는 [warn] 줄을 남기지 않아 거절 계수 증가로 본다')
        axes = ax.gate_axes('simple', self.nodes(), {'status': 101}, 'chaos-api-2', 7, '', lb_refused_delta=0.0)
        self.assertFalse(axes['lb_follower_503_then_101'], '팔로워를 거치지 않은 101 은 넘김의 증거가 아니다')
        axes = ax.gate_axes('simple', self.nodes(), {'status': 503}, 'chaos-api-2', 7, '', lb_refused_delta=1.0)
        self.assertFalse(axes['lb_follower_503_then_101'])

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


    def test_w5_freshness_window_starts_at_rejoin(self):
        # 2026-09-25 관문 주행 전 보정. kill 뒤 RTO 부터 잡으면 옛 리더가 멈춘 동안의 무응답 표본이 들어와 늘 거짓이 된다
        pairs = [{'t': 100, 'leader': {'status': 200, 'epoch': 6, 'seq': 10}, 'follower': {'status': None, 'epoch': None, 'seq': None}},
                 {'t': 130, 'leader': {'status': 200, 'epoch': 6, 'seq': 20}, 'follower': {'status': 200, 'epoch': 6, 'seq': 0}}]
        self.assertFalse(ax.freshness_summary(pairs, 90)['all_ok'], '멈춘 동안의 표본을 넣으면 거짓이다')
        start = ax.w5_freshness_from(120)
        self.assertTrue(ax.freshness_summary(pairs, start)['all_ok'])
        self.assertIsNone(ax.w5_freshness_from(None), '합류하지 못했으면 창이 없다')


    def test_w4_alarm_window_ends_before_restore_was_issued(self):
        # 2026-09-25 관문 W4-fix: 되돌리기 명령을 부른 뒤 경보가 풀리고 명령이 돌아오기 전에 뜬 팔로워 표본이 '경보 동안'에 들어왔다
        self.assertAlmostEqual(129.5, ax.alarm_samples_end(133.2, 130.0))
        self.assertAlmostEqual(132.7, ax.alarm_samples_end(133.2))
        pairs = [{'t': 125.0, 'leader': {'status': 200, 'epoch': 5, 'seq': 1056}, 'follower': {'status': 200, 'epoch': 5, 'seq': 0}},
                 {'t': 133.16, 'leader': {'status': 200, 'epoch': 5, 'seq': 1069}, 'follower': {'status': 200, 'epoch': 5, 'seq': 1070}}]
        self.assertFalse(ax.freshness_summary(pairs, 108, 133.2)['follower_all_seq0'])
        self.assertTrue(ax.freshness_summary(pairs, 108, ax.alarm_samples_end(133.2, 130.0))['follower_all_seq0'])


class P3PartitionNodeTest(unittest.TestCase):
    """S7 설계 4-13·6-3. P3 에서는 모든 파티션을 쥔 노드가 없어 신선도 표본기가 자원을 받아 소유 노드를 고른다."""

    P3 = {'chaos-api-1': {'partitions': [{'resource': 'matching-p0', 'state': 'ACCEPTING'},
                                         {'resource': 'matching-p1', 'state': 'FOLLOWER'}]},
          'chaos-api-2': {'partitions': [{'resource': 'matching-p0', 'state': 'FOLLOWER'},
                                         {'resource': 'matching-p1', 'state': 'ACCEPTING'}]}}

    def test_owner_of_picks_the_single_accepting_node(self):
        self.assertEqual('chaos-api-1', ax.owner_of(self.P3, 'matching-p0'))
        self.assertEqual('chaos-api-2', ax.owner_of(self.P3, 'matching-p1'))
        self.assertIsNone(ax.leader_from_partitions(self.P3), 'P3 에는 모든 파티션을 쥔 노드가 없다')
        both = {'chaos-api-1': {'partitions': [{'resource': 'matching-p0', 'state': 'ACCEPTING'}]},
                'chaos-api-2': {'partitions': [{'resource': 'matching-p0', 'state': 'ACCEPTING'}]}}
        self.assertIsNone(ax.owner_of(both, 'matching-p0'), '둘이 쥐었으면 고르지 않는다')
        none = {'chaos-api-1': {'partitions': [{'resource': 'matching-p0', 'state': 'PROMOTING'}]},
                'chaos-api-2': {'error': 503}}
        self.assertIsNone(ax.owner_of(none, 'matching-p0'))

    def test_pair_nodes_with_and_without_resource(self):
        self.assertEqual(('chaos-api-2', 'chaos-api-1'), ax.pair_nodes(self.P3, 'matching-p1'))
        self.assertEqual(('chaos-api-1', 'chaos-api-2'), ax.pair_nodes(self.P3, 'matching-p0'))
        self.assertEqual((None, None), ax.pair_nodes(self.P3), '자원 없이는 P3 에서 짝을 짓지 못한다(종전 동작)')
        p2 = {'chaos-api-1': {'partitions': [{'resource': 'matching-engine', 'state': 'ACCEPTING'}]},
              'chaos-api-2': {'partitions': [{'resource': 'matching-engine', 'state': 'FOLLOWER'}]}}
        self.assertEqual(('chaos-api-1', 'chaos-api-2'), ax.pair_nodes(p2))

    def test_partition_format_ust_counts_as_ws_retry(self):
        line = ('172.18.0.1 - - [25/Sep/2026:10:00:01 +0000] "GET /ws/v1 HTTP/1.1" 101 0 "-" "k6" '
                'ups="172.18.0.5:8080, 172.18.0.6:8080" ust="503, 101" hint="-" route="-" rt=0.012')
        self.assertTrue(ax.lb_ws_retry_seen(line))
        other = line.replace('"GET /ws/v1', '"GET /api/v1/ticker')
        self.assertFalse(ax.lb_ws_retry_seen(other), '/ws/ 가 아닌 요청은 증거가 아니다')
        plain = line.replace('ust="503, 101"', 'ust="101"')
        self.assertFalse(ax.lb_ws_retry_seen(plain))


def w6_state(opens=1, rises=None, gaps=None):
    markets = {m: {'orderbook': {'epochs': [5] if m != 'KRW-ETH' else [6, 9], 'gaps': list((gaps or {}).get(m, []))},
                   'trade': {'epochs': [5] if m != 'KRW-ETH' else [6, 9]}, 'ticker': {'epochs': []}}
               for m in ('KRW-BTC', 'KRW-XRP', 'KRW-ETH')}
    return state('ws://chaos-api-1:8080/ws/v1', markets, opens=opens,
                 epochRises=rises if rises is not None else [{'at': 20_000, 'market': 'KRW-ETH', 'epoch': 9}])


class W6Test(unittest.TestCase):
    """S7 설계 6-3 W6 판정의 경계. kill 10초, RTO 12초라 영향 마켓의 새 epoch 는 27초 안이어야 한다."""

    KILL = 10_000
    RTO = 12.0
    AFFECTED = ['KRW-ETH']
    UNAFFECTED = ['KRW-BTC', 'KRW-XRP']

    def axes(self, st):
        return ax.w6_watch_axes(st, self.KILL, self.RTO, self.AFFECTED, self.UNAFFECTED)[1]

    def test_good_run_passes(self):
        axes = self.axes(w6_state())
        self.assertTrue(all(axes.values()), axes)

    def test_unaffected_epoch_rise_fails(self):
        rises = [{'at': 20_000, 'market': 'KRW-ETH', 'epoch': 9}, {'at': 21_000, 'market': 'KRW-BTC', 'epoch': 11}]
        axes = self.axes(w6_state(rises=rises))
        self.assertFalse(axes['KRW-BTC_unaffected_epoch_rise_0'], '비영향 마켓 epoch 상승 1 이면 거짓이다')
        self.assertTrue(axes['KRW-XRP_unaffected_epoch_rise_0'])

    def test_affected_rise_zero_or_twice_fails(self):
        self.assertFalse(self.axes(w6_state(rises=[]))['KRW-ETH_affected_epoch_rise_once_within_rto_plus_5s'])
        twice = w6_state(rises=[{'at': 20_000, 'market': 'KRW-ETH', 'epoch': 9},
                                {'at': 22_000, 'market': 'KRW-ETH', 'epoch': 10}])
        twice['markets']['KRW-ETH']['orderbook']['epochs'] = [6, 9, 10]
        self.assertFalse(self.axes(twice)['KRW-ETH_affected_epoch_rise_once_within_rto_plus_5s'])
        late = w6_state(rises=[{'at': 40_000, 'market': 'KRW-ETH', 'epoch': 9}])
        self.assertFalse(self.axes(late)['KRW-ETH_affected_epoch_rise_once_within_rto_plus_5s'], 'RTO + 5초 밖')

    def test_survivor_reconnect_and_gaps_fail(self):
        self.assertFalse(self.axes(w6_state(opens=2))['survivor_watch_reconnect_0'], '생존 노드 재연결 1 이면 거짓이다')
        gaps = self.axes(w6_state(gaps={'KRW-BTC': [{'at': 15_000}], 'KRW-ETH': [{'at': 25_000}]}))
        self.assertFalse(gaps['KRW-BTC_unaffected_dense_gaps_0'])
        self.assertFalse(gaps['KRW-ETH_affected_dense_gaps_after_rise_0'])
        before = self.axes(w6_state(gaps={'KRW-BTC': [{'at': 5_000}]}))
        self.assertTrue(before['KRW-BTC_unaffected_dense_gaps_0'], 'kill 앞의 결번은 이 판정 밖이다')

    def test_swapped_victim_and_survivor_change_the_result(self):
        # api-2 에 붙은 관찰자는 p1 소유자가 죽을 때 연결이 끊겨 다시 열린다(opens 2). 인자를 뒤집으면 그 관찰자를 보게 된다.
        watch = {'chaos-api-1': {'latest': w6_state()}, 'chaos-api-2': {'latest': w6_state(opens=2)}}
        good = ax.w6_watch_axes(watch['chaos-api-1']['latest'], self.KILL, self.RTO, self.AFFECTED, self.UNAFFECTED)[1]
        bad = ax.w6_watch_axes(watch['chaos-api-2']['latest'], self.KILL, self.RTO, self.AFFECTED, self.UNAFFECTED)[1]
        self.assertTrue(all(good.values()))
        self.assertFalse(all(bad.values()))

    def test_web_axes(self):
        kill = self.KILL
        web = {'requests': [{'at': kill - 5_000, 'path': '/api/v1/ticker', 'query': {'markets': 'KRW-BTC,KRW-ETH,KRW-XRP'}},
                            {'at': kill + 13_000, 'path': '/api/v1/ticker', 'query': {'markets': 'KRW-ETH'}},
                            {'at': kill + 13_100, 'path': '/api/v1/accounts', 'query': {}},
                            {'at': kill + 13_200, 'path': '/api/v1/orders/open', 'query': {}}],
               'ws': {'markets': {'KRW-BTC|orderbook': {'epochs': {'5': kill - 9_000}, 'gaps': 0},
                                  'KRW-XRP|ticker': {'epochs': {'5': kill - 9_000}, 'gaps': 0},
                                  'KRW-ETH|ticker': {'epochs': {'6': kill - 9_000, '9': kill + 12_000}, 'gaps': 0}}}}
        axes = ax.web_w6_axes(web, kill, self.AFFECTED, self.UNAFFECTED)
        self.assertTrue(all(axes.values()), axes)
        extra = json.loads(json.dumps(web))
        extra['requests'][1]['query']['markets'] = 'KRW-ETH,KRW-BTC'
        self.assertFalse(ax.web_w6_axes(extra, kill, self.AFFECTED, self.UNAFFECTED)['web_ticker_resync_only_affected'])
        twice = json.loads(json.dumps(web))
        twice['requests'].append({'at': kill + 14_000, 'path': '/api/v1/accounts', 'query': {}})
        self.assertFalse(ax.web_w6_axes(twice, kill, self.AFFECTED, self.UNAFFECTED)['web_accounts_rest_once'])
        moved = json.loads(json.dumps(web))
        moved['ws']['markets']['KRW-BTC|orderbook']['epochs']['7'] = kill + 12_000
        self.assertFalse(ax.web_w6_axes(moved, kill, self.AFFECTED, self.UNAFFECTED)['web_KRW-BTC_epoch_kept'])

    def test_verdict_without_web_is_not_run(self):
        core = {'survivor_watch_reconnect_0': True, 'web_observed': False}
        self.assertEqual(ax.NOT_RUN_WEB, ax.verdict_with_web(core, False), '웹 관찰 파일이 없으면 NOT RUN(웹 관찰)')
        self.assertEqual('FAIL', ax.verdict_with_web(dict(core, survivor_watch_reconnect_0=False), False),
                         '웹 밖 축이 거짓이면 웹 관찰과 무관하게 FAIL')
        self.assertEqual('PASS', ax.verdict_with_web({'x': True, 'web_observed': True, 'web_y': True}, True))
        self.assertEqual('FAIL', ax.verdict_with_web({'x': True, 'web_observed': True, 'web_y': False}, True))


class W7Test(unittest.TestCase):
    """S7 설계 6-3 W7 판정의 경계."""

    def good(self):
        markets = {}
        for m, epoch in (('KRW-BTC', 5), ('KRW-XRP', 5), ('KRW-ETH', 6)):
            markets[m] = {'orderbook': {'realtime': 150, 'epochs': [epoch], 'gaps': []}, 'trade': {'messages': 12,
                                                                                                 'epochs': [epoch]},
                          'ticker': {'realtime': 40, 'epochs': [epoch]}}
        return state('ws://chaos-api-1:8080/ws/v1', markets, opens=1, epochRises=[])

    def axes(self, st):
        return ax.w7_watch_axes(st, 1_000, 600_000, ['KRW-BTC', 'KRW-XRP'], ['KRW-ETH'])[1]

    def test_good_run_passes(self):
        axes = self.axes(self.good())
        self.assertTrue(all(axes.values()), axes)

    def test_same_epoch_on_both_partitions_fails(self):
        st = self.good()
        for kind in ('orderbook', 'trade', 'ticker'):
            st['markets']['KRW-ETH'][kind]['epochs'] = [5]
        self.assertFalse(self.axes(st)['partition_epochs_differ'], '두 파티션 epoch 가 같으면 거짓이다')

    def test_rise_in_window_and_low_volume_fail(self):
        st = self.good()
        st['epochRises'] = [{'at': 300_000, 'market': 'KRW-ETH', 'epoch': 8}]
        self.assertFalse(self.axes(st)['KRW-ETH_epoch_rise_0'], '창 안 상승 1 이면 거짓이다')
        low = self.good()
        low['markets']['KRW-ETH']['orderbook']['realtime'] = 99
        self.assertFalse(self.axes(low)['KRW-ETH_book_ge_100'], '한 파티션 마켓 수신이 문턱 아래면 거짓이다')
        self.assertFalse(self.axes(dict(self.good(), opens=2))['one_connection'])

    def test_web_axes(self):
        web = {'restAfterSettle': 0, 'ws': {'markets': {'KRW-BTC|orderbook': {'epochs': {'5': 1}},
                                                        'KRW-ETH|ticker': {'epochs': {'6': 1}}}}}
        self.assertTrue(all(ax.web_w7_axes(web, ['KRW-BTC', 'KRW-XRP'], ['KRW-ETH']).values()))
        self.assertFalse(ax.web_w7_axes(dict(web, restAfterSettle=2), ['KRW-BTC'], ['KRW-ETH'])[
            'web_rest_after_first_load_0'])


class JudgeCliTest(unittest.TestCase):

    def test_judge_accepts_w6_and_w7(self):
        import contextlib
        import io
        sink = io.StringIO()
        for exp in ('W6', 'W7'):
            with self.assertRaises(SystemExit) as cm, contextlib.redirect_stdout(sink):
                ax.main(['judge', '--experiment', exp, '--help'])
            self.assertEqual(0, cm.exception.code, exp)
        with self.assertRaises(SystemExit) as cm, contextlib.redirect_stderr(sink):
            ax.main(['judge', '--experiment', 'W8', '--evidence', '.', '--window-from', '2026-09-25T00:00:00Z',
                     '--out', 'x'])
        self.assertEqual(2, cm.exception.code, 'W8 은 받지 않는다')


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
