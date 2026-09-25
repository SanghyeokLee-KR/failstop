"""s4_partition_axes 의 집계 함수 시험. 도커와 DB 없이 돈다.

돌리는 법: python -B -m unittest harness/scale/test_s4_partition_axes.py
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import s4_partition_axes as ax  # noqa: E402


class ParseMetricsTest(unittest.TestCase):

    def test_keeps_only_partition_lease_and_contention_series(self):
        text = '\n'.join([
            '# HELP exchange_partition_state x',
            'exchange_partition_state{partition="matching-engine",} 2.0',
            'exchange_partition_demotions_total{partition="matching-engine",reason="STALL",} 1.0',
            'exchange_leader_lease_acquire_lock_timeouts_total{partition="matching-engine",} 3.0',
            'exchange_order_intake_contention_total{cause="55P03",response="429",} 7.0',
            'exchange_journal_append_seconds_count 1234.0',
            'jvm_threads_live_threads 55.0',
            'exchange_order_intake_contention_total{cause="lock_wait",response="409",} NaN',
        ])
        got = ax.parse_metrics(text)
        self.assertEqual(2.0, got['exchange_partition_state{partition="matching-engine",}'])
        self.assertEqual(3.0, got['exchange_leader_lease_acquire_lock_timeouts_total{partition="matching-engine",}'])
        self.assertEqual(1234.0, got['exchange_journal_append_seconds_count'])
        self.assertNotIn('jvm_threads_live_threads', got)
        # NaN 도 값이다(계수기가 아직 표본을 못 받은 게이지). 이름·태그째 남긴다.
        self.assertEqual(6, len(got))


class WindowsTest(unittest.TestCase):

    def test_no_fault_window_means_the_whole_run_is_outside(self):
        outside, inside = ax.windows(100.0, None, None, 500.0, 30.0)
        self.assertEqual([(100.0, 500.0)], outside)
        self.assertIsNone(inside)

    def test_fault_window_splits_the_run_and_settles(self):
        outside, inside = ax.windows(100.0, 200.0, 260.0, 500.0, 30.0)
        self.assertEqual([(100.0, 200.0), (290.0, 500.0)], outside)
        self.assertEqual((200.0, 290.0), inside)

    def test_settle_past_now_leaves_one_outside_interval(self):
        outside, inside = ax.windows(100.0, 200.0, 260.0, 270.0, 30.0)
        self.assertEqual([(100.0, 200.0)], outside)
        self.assertEqual((200.0, 270.0), inside)


class ContentionCapTest(unittest.TestCase):

    def test_cap_is_a_tenth_of_a_percent_of_appends(self):
        self.assertTrue(ax.contention_cap(0.0, 0.0))
        self.assertTrue(ax.contention_cap(1.0, 1000.0))
        self.assertFalse(ax.contention_cap(2.0, 1000.0))

    def test_unread_values_fail_closed(self):
        self.assertFalse(ax.contention_cap(None, 1000.0))
        self.assertFalse(ax.contention_cap(0.0, None))


class LeaderAndDemotionsTest(unittest.TestCase):

    def test_leader_node_is_the_single_accepting_node(self):
        post = {'nodes': {
            'chaos-api-1': {'partitions': {'partitions': [{'resource': 'matching-engine', 'state': 'FOLLOWER'}]}},
            'chaos-api-2': {'partitions': {'partitions': [{'resource': 'matching-engine', 'state': 'ACCEPTING'}]}}}}
        self.assertEqual('chaos-api-2', ax.leader_node(post))
        both = {'nodes': {n: {'partitions': {'partitions': [{'state': 'ACCEPTING'}]}} for n in ('a', 'b')}}
        self.assertIsNone(ax.leader_node(both))
        self.assertIsNone(ax.leader_node({'nodes': {'a': {'partitions': {'error': 'URLError'}}}}))

    def test_demotions_sum_by_partition_and_reason(self):
        node = {'metrics': {
            'exchange_partition_demotions_total{partition="matching-engine",reason="STALL",}': 1.0,
            'exchange_partition_demotions_total{partition="matching-engine",reason="WRITER_FATAL",}': 2.0,
            'exchange_partition_state{partition="matching-engine",}': 0.0}}
        got = ax.demotions(node)
        self.assertEqual(3.0, got['total'])
        self.assertEqual({'matching-engine/STALL': 1.0, 'matching-engine/WRITER_FATAL': 2.0}, got['by'])
        self.assertIsNone(ax.demotions({'metrics': {'error': 'URLError'}}))

    def test_planned_demotion_is_left_out_of_the_total(self):
        # 참여 끄기의 계획 강등은 운영자 조작이다(S7 Q3b 의 두 파티션 모으기). 리더 노드 강등 합에서 빼고 따로 적는다.
        node = {'metrics': {
            'exchange_partition_demotions_total{partition="matching-p1",reason="PLANNED",}': 1.0,
            'exchange_partition_demotions_total{partition="matching-p0",reason="LEASE_LOST_WHILE_ACCEPTING",}': 1.0}}
        got = ax.demotions(node)
        self.assertEqual((1.0, 1.0), (got['total'], got['planned']))


class AlertsAndLbTest(unittest.TestCase):

    def test_firing_samples_inside_the_fault_window_are_excluded(self):
        samples = [{'state': 'firing', 't': 150.0}, {'state': 'firing', 't': 250.0}, {'state': 'pending', 't': 50.0}]
        self.assertEqual(1, ax.alerts_outside(samples, 200.0, 300.0))
        self.assertEqual(2, ax.alerts_outside(samples, None, None))

    def test_lb_line_counts(self):
        text = '\n'.join([
            '2026/09/24 10:00:00 [error] 12#12: *3 no live upstreams while connecting to upstream',
            '172.18.0.1 - - [24/Sep/2026:10:00:01 +0000] "POST /api/v1/orders HTTP/1.1" 502 157 "-"',
            '172.18.0.1 - - [24/Sep/2026:10:00:02 +0000] "POST /api/v1/orders HTTP/1.1" 201 512 "-"',
            # 응답 바이트 수가 502 인 정상 응답은 세지 않는다(combined 형식에서 상태 뒤 필드).
            '172.18.0.1 - - [24/Sep/2026:10:00:03 +0000] "GET /api/v1/orders/7 HTTP/1.1" 200 502 "-"',
        ])
        self.assertEqual({'no_live_upstreams': 1, 'status_502': 1}, ax.count_lb_lines(text))


if __name__ == '__main__':
    unittest.main()
