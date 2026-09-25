"""s7_axes 의 판정 함수 시험(ADR 0034 S7, S7 설계 6-3). 도커·DB·HTTP 없이 돈다.

돌리는 법: python -B -m unittest harness/scale/test_s7_axes.py
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import s7_axes as s7  # noqa: E402


def parts(p0_node, p1_node, p0_state='ACCEPTING', p1_state='ACCEPTING'):
    out = {'chaos-api-1': {'partitions': []}, 'chaos-api-2': {'partitions': []}}
    for node in out:
        out[node]['partitions'].append({'resource': 'matching-p0',
                                        'state': p0_state if node == p0_node else 'FOLLOWER', 'epoch': 40})
        out[node]['partitions'].append({'resource': 'matching-p1',
                                        'state': p1_state if node == p1_node else 'FOLLOWER', 'epoch': 41})
    return out


class VerdictTest(unittest.TestCase):

    def test_positive_axes_decide_not_run(self):
        self.assertEqual('PASS', s7.verdict_of({'a': True, 'b': True}, ['a']))
        self.assertEqual(s7.NOT_RUN, s7.verdict_of({'a': False, 'b': True}, ['a']))
        self.assertEqual('FAIL', s7.verdict_of({'a': True, 'b': False}, ['a']))
        self.assertEqual('FAIL', s7.verdict_of({}), '축이 없으면 통과가 아니다')
        self.assertEqual(3, s7.exit_code(s7.NOT_RUN))
        self.assertEqual(1, s7.exit_code('FAIL'))


class AssignmentTest(unittest.TestCase):

    def test_preferred_ok_wait_and_relocate(self):
        self.assertEqual(('ok', []), s7.assignment(parts('chaos-api-1', 'chaos-api-2')))
        self.assertEqual(('relocate', [('chaos-api-1', 'matching-p1')]),
                         s7.assignment(parts('chaos-api-1', 'chaos-api-1')),
                         'api-1 이 두 파티션을 쥐면 api-1 의 p1 참여를 끈다')
        self.assertEqual(('relocate', [('chaos-api-2', 'matching-p0'), ('chaos-api-1', 'matching-p1')]),
                         s7.assignment(parts('chaos-api-2', 'chaos-api-1')))
        self.assertEqual('wait', s7.assignment(parts('chaos-api-1', 'chaos-api-2', p1_state='PROMOTING'))[0])
        self.assertEqual('chaos-api-2', s7.owner_of(parts('chaos-api-1', 'chaos-api-2'), 'matching-p1'))
        self.assertEqual(41, s7.reported_epoch(parts('chaos-api-1', 'chaos-api-2')['chaos-api-2'], 'matching-p1'))
        self.assertIsNone(s7.reported_epoch(parts('chaos-api-1', 'chaos-api-2')['chaos-api-1'], 'matching-p1'))


class AckIntervalTest(unittest.TestCase):

    INJECT = 1_000.0

    def rows(self, window_gap=0.5, window_n=20, base_gap=0.8):
        base = sorted(self.INJECT - 0.1 - i * base_gap for i in range(int(99 / base_gap)))
        window = [self.INJECT + 0.2 + i * window_gap for i in range(window_n)]
        return base + window

    def test_pass_within_baseline_plus_one_second(self):
        facts, axes, positive = s7.ack_interval_judge({'KRW-BTC': self.rows(), 'KRW-XRP': self.rows()}, self.INJECT,
                                                      self.INJECT + 30)
        self.assertEqual('PASS', s7.verdict_of(axes, positive), axes)

    def test_gap_over_baseline_plus_one_fails(self):
        rows = self.rows()
        rows.append(self.INJECT + 12.0)
        rows.append(self.INJECT + 14.5)  # 2.5초 간격, 기준선 0.8 + 1 을 넘는다
        _, axes, positive = s7.ack_interval_judge({'KRW-BTC': sorted(rows)}, self.INJECT, self.INJECT + 30)
        self.assertEqual('FAIL', s7.verdict_of(axes, positive))

    def test_stall_right_after_injection_counts_the_boundary_gap(self):
        base = [self.INJECT - 100 + i for i in range(100)]
        window = [self.INJECT + 5.0 + i * 0.5 for i in range(20)]
        _, axes, positive = s7.ack_interval_judge({'KRW-BTC': base + window}, self.INJECT, self.INJECT + 30)
        self.assertFalse(axes['KRW-BTC_ack_gap_le_baseline_plus_1s'], '주입 직전 행과 창 첫 행 사이 멈춤을 놓쳤다')

    def test_baseline_from_cuts_off_the_cutover_stop_window(self):
        # 주입 60초 전 20초 멈춤(모드 전환의 정지 창)이 기준선에 들면 창의 3초 멈춤을 놓친다. baseline_from 이 그 창을 뺀다.
        base = [self.INJECT - 100 + i * 0.5 for i in range(40)] + [self.INJECT - 60 + i * 0.5 for i in range(119)]
        window = [self.INJECT + 0.2 + i * 0.5 for i in range(10)] + [self.INJECT + 8.0 + i * 0.5 for i in range(10)]
        rows = {'KRW-BTC': sorted(base + window)}
        _, axes, positive = s7.ack_interval_judge(rows, self.INJECT, self.INJECT + 30)
        self.assertEqual('PASS', s7.verdict_of(axes, positive), '대조: 정지 창이 기준선이면 3초 멈춤이 통과한다')
        facts, axes, positive = s7.ack_interval_judge(rows, self.INJECT, self.INJECT + 30, baseline_from=self.INJECT - 55)
        self.assertEqual('FAIL', s7.verdict_of(axes, positive), axes)
        self.assertEqual(self.INJECT - 55, facts['KRW-BTC']['baseline_from_s'])

    def test_too_few_rows_is_not_run(self):
        _, axes, positive = s7.ack_interval_judge({'KRW-BTC': self.rows(window_n=9)}, self.INJECT, self.INJECT + 30)
        self.assertEqual(s7.NOT_RUN, s7.verdict_of(axes, positive), '창 안 NEW 가 10건 미만이면 판정하지 않는다')
        _, axes, positive = s7.ack_interval_judge({}, self.INJECT, self.INJECT + 30)
        self.assertEqual(s7.NOT_RUN, s7.verdict_of(axes, positive))


class P3EpochTest(unittest.TestCase):

    def test_exactly_one_acquire_by_survivor(self):
        rows = [{'epoch': 57, 'resource': 'matching-p1', 'origin': 'ACQUIRE', 'protocol': 3}]
        _, axes = s7.p3_epoch_judge(rows, 'matching-p1', 41, 57)
        self.assertTrue(all(axes.values()), axes)

    def test_new_epoch_need_not_be_old_plus_one(self):
        rows = [{'epoch': 90, 'resource': 'matching-p1', 'origin': 'ACQUIRE', 'protocol': 3}]
        _, axes = s7.p3_epoch_judge(rows, 'matching-p1', 41, 90)
        self.assertTrue(all(axes.values()), '다른 파티션 epoch 가 커서 +1 이 아니어도 된다')

    def test_two_acquires_wrong_holder_or_mode_fail(self):
        two = [{'epoch': 57, 'resource': 'matching-p1', 'origin': 'ACQUIRE', 'protocol': 3},
               {'epoch': 60, 'resource': 'matching-p1', 'origin': 'ACQUIRE', 'protocol': 3}]
        self.assertFalse(s7.p3_epoch_judge(two, 'matching-p1', 41, 60)[1]['exactly_one_acquire_after_old_epoch'])
        one = [{'epoch': 57, 'resource': 'matching-p1', 'origin': 'ACQUIRE', 'protocol': 3}]
        self.assertFalse(s7.p3_epoch_judge(one, 'matching-p1', 41, 58)[1]['holder_is_survivor'])
        self.assertFalse(s7.p3_epoch_judge(one, 'matching-p1', 41, None)[1]['holder_is_survivor'])
        p2 = [{'epoch': 57, 'resource': 'matching-p1', 'origin': 'ACQUIRE', 'protocol': 2}]
        self.assertFalse(s7.p3_epoch_judge(p2, 'matching-p1', 41, 57)[1]['acquired_in_p3'])
        retire = [{'epoch': 57, 'resource': 'matching-p1', 'origin': 'RETIRE', 'protocol': 3}]
        self.assertFalse(s7.p3_epoch_judge(retire, 'matching-p1', 41, 57)[1]['exactly_one_acquire_after_old_epoch'])

    def test_acquisitions_sql_bounds_by_t5(self):
        sql = s7.acquisitions_sql('matching-p1', 41, 1_700_000_000.25)
        self.assertIn("resource = 'matching-p1' AND epoch > 41", sql)
        self.assertIn('acquired_at <= to_timestamp(1700000000.250)', sql, '하네스 검증설계 §5-1 의 acquired_at <= :T5')
        self.assertNotIn('acquired_at <=', s7.acquisitions_sql('matching-p1', 41))
        with self.assertRaises(ValueError):
            s7.acquisitions_sql("matching-p1' OR true --", 41)

    def test_partition_rto(self):
        self.assertEqual(({'rto_s': 12.5}, {'rto_lt_30s': True}), s7.rto_judge(1012.5, 1000.0))
        self.assertFalse(s7.rto_judge(1030.0, 1000.0)[1]['rto_lt_30s'], '30초는 통과가 아니다(< 30)')
        self.assertFalse(s7.rto_judge(None, 1000.0)[1]['rto_lt_30s'], '첫 체결이 없으면 거짓이다')
        sql = s7.first_partition_trade_sql('matching-p0', 41, 1000.0)
        self.assertIn('c.fencing_token > 41', sql, 'command_journal.fencing_token 축(§5-1 T5)')
        self.assertIn("WHERE resource = 'matching-p0'", sql)
        with self.assertRaises(ValueError):
            s7.first_partition_trade_sql('matching-engine', 41, 1000.0)

    def test_hintless_lb_summary(self):
        def line(sec, status, ups, method='POST', path='/api/v1/orders'):
            return ('172.18.0.9 - - [25/Sep/2026:12:00:%02d +0000] "%s %s HTTP/1.1" %d 120 "-" "k6" ups="%s" ust="%s" '
                    'hint="" route="" rt=0.01' % (sec, method, path, status, ups, status))
        start = s7.ts('2026-09-25T12:00:00Z')
        lines = [line(1, 201, '172.18.0.4:8080'), line(2, 502, 'exchange_write'), line(3, 502, '172.18.0.5:8080'),
                 line(9, 502, 'exchange_write'), line(10, 200, '172.18.0.4:8080', 'GET', '/api/v1/orders/open'),
                 line(12, 503, '172.18.0.4:8080, 172.18.0.5:8080'), 'garbage']
        got = s7.hintless_summary(lines, start, start + 60)
        self.assertEqual(5, got['order_lines'], 'GET 조회는 주문 쓰기가 아니다')
        self.assertEqual(3, got['status_502'])
        self.assertEqual(2, got['windows'], '1초 넘게 떨어진 502 는 다른 창이다')
        self.assertEqual([1.0, 0.0], got['window_lengths_s'])
        self.assertEqual(2, got['ups_exchange_write_lines'])
        self.assertEqual(0, s7.hintless_summary(lines, start + 30, start + 60)['order_lines'], '창 밖 줄은 세지 않는다')
        kst = line(1, 502, 'x').replace('+0000', '+0900').replace('12:00:01', '21:00:01')
        self.assertEqual(start + 1, s7.lb_order_line(kst)[0], '시간대 오프셋을 뺀다')

    def test_relocate_ack_combination(self):
        self.assertEqual((True, True), s7.combine_ack(['PASS', 'PASS']))
        self.assertEqual((False, True), s7.combine_ack(['PASS', 'FAIL']))
        self.assertEqual((False, False), s7.combine_ack(['PASS', s7.NOT_RUN]), '양성 축이 모자란 인계가 있으면 판정하지 않는다')
        self.assertEqual((False, False), s7.combine_ack([]))

    def test_epoch_counts_sql_covers_four_tables(self):
        sql = s7.epoch_counts_sql({21, 20, 21})
        for t in ('command_journal WHERE fencing_token = e', 'event_journal WHERE fencing_epoch = e',
                  'trades WHERE fencing_epoch = e', 'snapshots WHERE fencing_epoch = e'):
            self.assertIn(t, sql)
        self.assertIn('ARRAY[20,21]', sql)


class SegmentTest(unittest.TestCase):

    PRE = [{'id': 2, 'protocol': 2, 'epoch_floor': 10, 'old_rows': 5}, {'id': 3, 'protocol': 3, 'epoch_floor': 20,
                                                                        'old_rows': 40}]

    def test_equal_passes(self):
        post = [dict(r) for r in self.PRE] + [{'id': 4, 'protocol': 2, 'epoch_floor': 30, 'old_rows': 90}]
        _, axes = s7.segment_judge(self.PRE, post)
        self.assertEqual('PASS', s7.verdict_of(axes, ['pre_has_rows']), '뒤 표본에만 있는 이력 행은 비교하지 않는다')

    def test_increase_or_missing_row_fails(self):
        post = [dict(self.PRE[0]), dict(self.PRE[1], old_rows=41)]
        self.assertFalse(s7.segment_judge(self.PRE, post)[1]['history_3_old_rows_unchanged'], '하나라도 늘면 거짓이다')
        self.assertFalse(s7.segment_judge(self.PRE, [dict(self.PRE[0])])[1]['history_3_old_rows_unchanged'],
                         '뒤 표본에 앞 이력 행이 없으면 거짓이다')
        _, axes = s7.segment_judge([], self.PRE)
        self.assertEqual(s7.NOT_RUN, s7.verdict_of(axes, ['pre_has_rows']), '앞 표본이 없으면 판정하지 않는다')

    def test_segment_sql_is_the_harness_file(self):
        sql = s7.segment_sql()
        self.assertIn('FROM leader_protocol_history h', sql)
        self.assertIn('json_agg(row_to_json(x) ORDER BY x.id)', sql)
        self.assertNotIn('--', sql, '주석은 psql -c 한 문장에 싣지 않는다')
        self.assertFalse(sql.rstrip().endswith(';'))

    def test_rows_from_segment_count_or_transition_output(self):
        self.assertEqual(self.PRE, s7.segment_rows({'ok': True, 'rows': self.PRE}))
        self.assertEqual(self.PRE, s7.segment_rows({'ok': True, 'segment_after_cutover': {'ok': True, 'rows': self.PRE}}),
                         '전환 도구 출력(protocol.json)의 전환 직후 표본을 그대로 앞 표본으로 쓴다')
        self.assertIsNone(s7.segment_rows({'ok': False}))
        self.assertIsNone(s7.segment_rows(None))

    def test_several_pre_samples_are_merged(self):
        later = [dict(self.PRE[1]), {'id': 4, 'protocol': 2, 'epoch_floor': 30, 'old_rows': 90}]
        rows, consistent = s7.merge_pre([self.PRE, later])
        self.assertTrue(consistent)
        self.assertEqual([2, 3, 4], [r['id'] for r in rows], 'Q4 는 2->3, 3->2, 재진입 세 표본을 합친다')
        rows, consistent = s7.merge_pre([self.PRE, [dict(self.PRE[1], old_rows=41)]])
        self.assertFalse(consistent, '같은 이력 행의 앞 표본끼리 값이 다르면 그 사이에 옛 구간 쓰기가 있었다')


def epoch_sample(t, leases, counts):
    return {'t': t, 'leases': [{'resource': r, 'holder': h, 'epoch': e, 'alive': True} for r, h, e in leases],
            'counts': {str(k): v for k, v in counts.items()}}


class VictimWritesTest(unittest.TestCase):
    """희생 epoch 집합의 구 쓰기 증분(하네스 검증설계 §3 P3 실험 공통 판정)."""

    INJECT = 1_000.0
    SPLIT = [('matching-p0', 'h1', 20), ('matching-p1', 'h2', 21)]
    TAKEN = [('matching-p0', 'h1', 20), ('matching-p1', 'h1', 30)]

    def samples(self, p1_after=500, both=False):
        leases = [('matching-p0', 'h2', 20), ('matching-p1', 'h2', 21)] if both else self.SPLIT
        return [epoch_sample(990.0, leases, {20: 100, 21: 480}),
                epoch_sample(999.5, leases, {20: 110, 21: 500}),
                epoch_sample(1000.4, leases, {20: 111, 21: 500}),
                epoch_sample(1030.0, self.TAKEN, {20: 200, 21: p1_after, 30: 90})]

    def test_no_old_epoch_writes_after_injection_passes(self):
        facts, axes, positive = s7.victim_writes_judge(self.samples(), self.INJECT, 'matching-p1')
        self.assertEqual('PASS', s7.verdict_of(axes, positive), axes)
        self.assertEqual(['matching-p1'], facts['victim_resources'], 'p0 는 희생 노드가 쥐지 않았다')

    def test_old_epoch_write_after_injection_fails(self):
        facts, axes, positive = s7.victim_writes_judge(self.samples(p1_after=503), self.INJECT, 'matching-p1')
        self.assertEqual('FAIL', s7.verdict_of(axes, positive))
        self.assertEqual(3, facts['deltas']['21'])

    def test_victim_holding_both_partitions_covers_both_epochs(self):
        samples = self.samples(both=True)
        samples[-1]['counts']['20'] = samples[2]['counts']['20'] + 1
        facts, axes, positive = s7.victim_writes_judge(samples, self.INJECT, 'matching-p1')
        self.assertEqual(['matching-p0', 'matching-p1'], facts['victim_resources'])
        self.assertFalse(axes['victim_epoch_20_writes_0'], '희생 노드가 쥔 다른 파티션 epoch 도 센다(Q3b)')

    def test_takeover_times_per_victim_partition(self):
        facts, _, _ = s7.victim_writes_judge(self.samples(), self.INJECT, 'matching-p1')
        self.assertEqual({'matching-p1': 30.0}, facts['takeover_after_inject_s'], '다른 holder 가 처음 쥔 표본의 주입 뒤 초')
        samples = self.samples(both=True)
        samples.insert(3, epoch_sample(1012.0, [('matching-p0', 'h2', 20), ('matching-p1', 'h1', 30)], {20: 111, 21: 500}))
        facts, _, _ = s7.victim_writes_judge(samples, self.INJECT, 'matching-p1')
        self.assertEqual({'matching-p1': 12.0, 'matching-p0': 30.0}, facts['takeover_after_inject_s'],
                         'Q3b: 선호 p1 을 먼저, p0 를 유예 뒤 얻는다')

    def test_missing_samples_are_not_run(self):
        _, axes, positive = s7.victim_writes_judge(self.samples()[2:], self.INJECT, 'matching-p1')
        self.assertEqual(s7.NOT_RUN, s7.verdict_of(axes, positive), '주입 전 표본이 없으면 희생 노드를 모른다')
        _, axes, positive = s7.victim_writes_judge(self.samples()[:2], self.INJECT, 'matching-p1')
        self.assertEqual(s7.NOT_RUN, s7.verdict_of(axes, positive), '주입 뒤 표본이 없으면 판정하지 않는다')


class DoubleSpendTest(unittest.TestCase):

    def good(self):
        return {'requested_rounds': 3, 'rounds': [{'successes': 1}] * 3, 'order_insufficient': 2,
                'withdraw_insufficient': 1, 'settlement_insufficient_delta': 0, 'fatal_demotions_delta': 0,
                'server_errors': 0, 'restore_failures': 0, 'post_invariants': {'C01': 0, 'C14': 0, 'C31': 0},
                'status_counts': {'order:201': 2, 'order:400': 2, 'withdraw:201': 1, 'withdraw:422': 1}}

    def test_pass(self):
        _, axes, positive = s7.double_spend_judge(self.good())
        self.assertEqual('PASS', s7.verdict_of(axes, positive), axes)

    def test_boundaries(self):
        for key, value, verdict in (('rounds', [{'successes': 1}, {'successes': 2}, {'successes': 1}], 'FAIL'),
                                    ('settlement_insufficient_delta', 1, 'FAIL'), ('server_errors', 1, 'FAIL'),
                                    ('fatal_demotions_delta', 1, 'FAIL'), ('order_insufficient', 0, s7.NOT_RUN),
                                    ('rounds', [{'successes': 1}] * 2, s7.NOT_RUN),
                                    # 한쪽 노드가 회원 토큰을 401 로 거절하면 경합이 성립하지 않는다(2026-09-26 첫 주행)
                                    ('status_counts', {'order:201': 2, 'order:401': 1, 'withdraw:201': 1}, s7.NOT_RUN),
                                    ('status_counts', None, s7.NOT_RUN)):
            res = dict(self.good(), **{key: value})
            _, axes, positive = s7.double_spend_judge(res)
            self.assertEqual(verdict, s7.verdict_of(axes, positive), key)
        bad_inv = dict(self.good(), post_invariants={'C01': 0, 'C14': 1, 'C31': 0})
        self.assertEqual('FAIL', s7.verdict_of(*s7.double_spend_judge(bad_inv)[1:]))


class FeeCouplingTest(unittest.TestCase):

    def good(self):
        return {'lock_seen_after_pause': True, 'p1_trades_during': 3, 'krw_shard1_increased': True,
                'api2_transient_exhausted_delta': 0, 'api2_fatal_demotions_delta': 0,
                'p1_settle_baseline_max_s': 0.4, 'p1_settle_during_max_s': 1.2, 'lease_handover': False}

    def test_pass_and_boundaries(self):
        self.assertEqual('PASS', s7.verdict_of(*s7.fee_coupling_judge(self.good())[1:]))
        slow = dict(self.good(), p1_settle_during_max_s=1.5)
        self.assertEqual('FAIL', s7.verdict_of(*s7.fee_coupling_judge(slow)[1:]), '기준선 + 1초를 넘었다')
        missed = dict(self.good(), lock_seen_after_pause=False)
        self.assertEqual(s7.NOT_RUN, s7.verdict_of(*s7.fee_coupling_judge(missed)[1:]), '잠금을 잡지 못하면 NOT RUN')
        handover = dict(self.good(), lease_handover=True)
        self.assertEqual('FAIL', s7.verdict_of(*s7.fee_coupling_judge(handover)[1:]))


class MarketFatalTest(unittest.TestCase):

    def good(self):
        return {'injected_failures': 1, 'demotions': {'matching-p1': {'WRITER_FATAL': 1}, 'matching-p0': {}},
                'breaker_open': False, 'p1_reacquired_s': 9.5, 'p0_ack_ok': True, 'post_invariants_pass': True}

    def test_pass_and_boundaries(self):
        self.assertEqual('PASS', s7.verdict_of(*s7.market_fatal_judge(self.good())[1:]))
        p0 = dict(self.good(), demotions={'matching-p1': {'WRITER_FATAL': 1}, 'matching-p0': {'STALL': 1}})
        self.assertEqual('FAIL', s7.verdict_of(*s7.market_fatal_judge(p0)[1:]), 'p0 도 강등됐다')
        twice = dict(self.good(), demotions={'matching-p1': {'WRITER_FATAL': 2}})
        self.assertEqual('FAIL', s7.verdict_of(*s7.market_fatal_judge(twice)[1:]))
        slow = dict(self.good(), p1_reacquired_s=31)
        self.assertEqual('FAIL', s7.verdict_of(*s7.market_fatal_judge(slow)[1:]))
        none = dict(self.good(), injected_failures=0)
        self.assertEqual(s7.NOT_RUN, s7.verdict_of(*s7.market_fatal_judge(none)[1:]), '주입이 안 됐으면 NOT RUN')
        unmeasured = dict(self.good(), p0_ack_ok=False, p0_ack={'verdict': s7.NOT_RUN})
        self.assertEqual(s7.NOT_RUN, s7.verdict_of(*s7.market_fatal_judge(unmeasured)[1:]), 'p0 ACK 를 재지 못했으면 NOT RUN')
        slow_ack = dict(self.good(), p0_ack_ok=False, p0_ack={'verdict': 'FAIL'})
        self.assertEqual('FAIL', s7.verdict_of(*s7.market_fatal_judge(slow_ack)[1:]))


class ColdAssignAndRelocateTest(unittest.TestCase):

    def test_cold_assign(self):
        good = {'assignment': 'ok', 'relocated': False, 'non_preferred_acquires_in_2ttl': 0,
                'first_trade_s': {'matching-p0': 18.0, 'matching-p1': 21.0}}
        self.assertEqual('PASS', s7.verdict_of(s7.cold_assign_judge(good)[1]))
        for key, value in (('assignment', 'relocate'), ('relocated', True), ('non_preferred_acquires_in_2ttl', 1),
                           ('first_trade_s', {'matching-p0': 18.0, 'matching-p1': 31.0}),
                           ('first_trade_s', {'matching-p0': 18.0})):
            self.assertEqual('FAIL', s7.verdict_of(s7.cold_assign_judge(dict(good, **{key: value}))[1]), key)

    def test_relocate(self):
        move = {'rto_s': 12.0, 'old_epoch_writes_delta': 0, 'new_owner': 'chaos-api-1', 'expected_owner': 'chaos-api-1'}
        back = dict(move, new_owner='chaos-api-2', expected_owner='chaos-api-2', rto_s=7.0)
        good = {'moves': [move, back], 'p0_ack_ok': True, 'final_assignment': 'ok'}
        self.assertEqual('PASS', s7.verdict_of(s7.relocate_judge(good)[1]))
        slow = dict(good, moves=[dict(move, rto_s=30.0), back])
        self.assertEqual('FAIL', s7.verdict_of(s7.relocate_judge(slow)[1]), 'RTO 30초는 통과가 아니다')
        stale = dict(good, moves=[move, dict(back, old_epoch_writes_delta=1)])
        self.assertEqual('FAIL', s7.verdict_of(s7.relocate_judge(stale)[1]))
        self.assertEqual('FAIL', s7.verdict_of(s7.relocate_judge(dict(good, final_assignment='relocate'))[1]))


class HandoffTest(unittest.TestCase):

    def good(self):
        return {
            'api1_p1_participate_off': '200', 'api1_p1_participate_on': '200',
            'live': {'response': {'status': 200, 'body_canceled_count': 4, 'body_failed': []},
                     'order_states': {'a': 'CANCELED', 'b': 'CANCELED', 'c': 'CANCELED', 'd': 'CANCELED'},
                     'handoff_rows': [{'resource': 'matching-p1', 'state': 'DONE', 'origin': 'cancel_all', 'orders': 2}]},
            'paused_requests': [
                {'kind': 'cancel_all', 'p1_holder_node': 'chaos-api-2', 'status': 200, 'canceled_count': 2,
                 'failed': [{'id': 'x', 'reason': 'partition_not_started'}, {'id': 'y', 'reason': 'partition_not_started'}]},
                {'kind': 'member_close', 'p1_holder_node': None, 'status': 409, 'error': 'orders_cancel_incomplete',
                 'pending_reasons': {'partition_not_started': 2}}],
            'after_resume': {'open_rows': 0},
        }

    def test_pass(self):
        self.assertEqual('PASS', s7.verdict_of(*s7.handoff_judge(self.good())[1:]))

    def test_request_while_api1_holds_p1_is_not_run(self):
        res = self.good()
        res['paused_requests'][1]['p1_holder_node'] = 'chaos-api-1'
        self.assertEqual(s7.NOT_RUN, s7.verdict_of(*s7.handoff_judge(res)[1:]),
                         '요청 시점 p1 holder 가 api-2 밖이면 NOT RUN 이다')
        off = dict(self.good(), api1_p1_participate_off='403')
        self.assertEqual(s7.NOT_RUN, s7.verdict_of(*s7.handoff_judge(off)[1:]))

    def test_boundaries(self):
        res = self.good()
        res['paused_requests'][1]['status'] = 200
        self.assertEqual('FAIL', s7.verdict_of(*s7.handoff_judge(res)[1:]), '멈춘 동안 폐쇄가 200 이면 거짓이다')
        res = self.good()
        res['paused_requests'][0]['failed'] = [{'id': 'x', 'reason': 'service_unavailable'},
                                               {'id': 'y', 'reason': 'partition_pending'}]
        self.assertEqual('FAIL', s7.verdict_of(*s7.handoff_judge(res)[1:]))
        res = dict(self.good(), after_resume={'open_rows': 1})
        self.assertEqual('FAIL', s7.verdict_of(*s7.handoff_judge(res)[1:]), '재개 뒤 끝나지 않은 인계 행이 남았다')


if __name__ == '__main__':
    unittest.main()
