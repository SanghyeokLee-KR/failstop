"""s3_common_axes 의 집계 함수 시험. 도커와 DB 없이 돈다.

돌리는 법: python -B -m unittest harness/scale/test_s3_common_axes.py
"""
import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import s3_common_axes as ax  # noqa: E402


class TotalTest(unittest.TestCase):

    def test_scalar_vector_and_error(self):
        self.assertEqual(0.0, ax.total(0.0))
        self.assertEqual(3.0, ax.total([{'metric': {'instance': 'a'}, 'value': 1.0},
                                        {'metric': {'instance': 'b'}, 'value': 2.0}]))
        # 질의 실패는 0 이 아니라 None 이다. 판정 축은 None 을 0 으로 읽지 않는다(실패 쪽으로 닫힌다).
        self.assertIsNone(ax.total({'error': 'URLError'}))


class SummarizeSamplesTest(unittest.TestCase):

    def test_kinds_counts_and_errors(self):
        rows = [
            {'ts': 't1', 'lock_waiters': 3, 'balance_rows': [
                {'pid': 1, 'kind': 'USER:AVAILABLE', 'waited_ms': 120},
                {'pid': 2, 'kind': 'USER:AVAILABLE', 'waited_ms': 40},
                {'pid': 3, 'kind': 'SYSTEM:FEE_INCOME', 'waited_ms': 900}]},
            {'ts': 't2', 'lock_waiters': 0, 'balance_rows': []},
            {'ts': 't3', 'error': 'psql 종료코드 2'},
        ]
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'samples.jsonl'
            path.write_text('\n'.join(json.dumps(r, ensure_ascii=False) for r in rows), encoding='utf-8')
            got = ax.summarize_samples(str(path))
        self.assertEqual(3, got['samples'])
        self.assertEqual(1, got['errors'])
        self.assertEqual(3, got['max_lock_waiters'])
        self.assertEqual({'samples_with_waiter': 1, 'max_waiters': 2, 'max_waited_ms': 120},
                         got['balance_row_waits']['USER:AVAILABLE'])
        self.assertEqual(900, got['balance_row_waits']['SYSTEM:FEE_INCOME']['max_waited_ms'])

    def test_missing_file_is_empty(self):
        got = ax.summarize_samples(None)
        self.assertEqual(0, got['samples'])
        self.assertIsNone(got['max_lock_waiters'])


class S4AxesTest(unittest.TestCase):
    """S4 설계 4-16·4-18: 리스 자원 인자와 강등 문구의 양성 대조."""

    def test_state_sql_reads_the_given_resource(self):
        self.assertIn("resource = 'matching-engine'", ax.STATE_SQL)
        self.assertIn("resource = 'matching-p1'", ax.state_sql('matching-p1'))
        self.assertNotIn("resource = 'matching-engine'", ax.state_sql('matching-p1'))
        # 드레인 술어가 그대로 들어갔다.
        self.assertIn(ax.DRAIN_SQL, ax.state_sql('matching-p0'))
        with self.assertRaises(ValueError):
            ax.state_sql("matching-engine'; DROP TABLE x; --")

    def test_log_patterns_follow_s4_phrases(self):
        # S4 코드의 문구(LeaderLeaseService·PartitionRuntime). 바뀌면 이 축이 공허하게 통과한다.
        self.assertEqual('리더십 상실', ax.LOG_PATTERNS['leadershipLost'])
        self.assertEqual('파티션 강등', ax.LOG_PATTERNS['partitionDemotion'])
        self.assertEqual('종신 강등', ax.LOG_PATTERNS['terminalDemotion'])
        self.assertEqual(('terminalDemotion', 'leadershipLost', 'partitionDemotion'), ax.DEMOTION_KEYS)

    def test_demotion_logged_looks_at_non_leader_api_nodes(self):
        logs = {'chaos-api-1': {'partitionDemotion': 2, 'leadershipLost': 1},
                'chaos-api-2': {'partitionDemotion': 0, 'leadershipLost': 0},
                'chaos-bots': {'leadershipLost': 5}}
        self.assertTrue(ax.demotion_logged(logs, 'chaos-api-2'))
        # 강등 문구가 리더에만 있거나 봇에만 있으면 옛 리더의 양성 대조가 아니다.
        self.assertFalse(ax.demotion_logged(logs, 'chaos-api-1'))
        self.assertFalse(ax.demotion_logged({'chaos-api-1': {'ok': False}}, 'chaos-api-2'))

    def test_planned_demotion_is_not_a_leader_demotion(self):
        # 참여 끄기로 스스로 내려놓은 강등(S7 Q3b 의 두 파티션 모으기)은 리더 노드 강등 축에서 뺀다. 치명 강등은 그대로 센다.
        lines = ['... LeaderLeaseService : 리더십 상실(재구성 전 재획득 없음): resource=matching-p1, epoch=9, 사유=PLANNED(PLANNED)',
                 '... PartitionRuntime : 파티션 강등: resource=matching-p1, epoch=9, 사유=PLANNED(PLANNED), 차단기 0회/닫힘']
        got = ax.count_log_lines(lines)
        self.assertEqual((0, 0, 1), (got['leadershipLost'], got['partitionDemotion'], got['plannedDemotion']))
        fatal = ['... PartitionRuntime : 파티션 강등: resource=matching-p1, epoch=9, 사유=WRITER_FATAL(x), 차단기 1회/닫힘']
        got = ax.count_log_lines(fatal)
        self.assertEqual((1, 0), (got['partitionDemotion'], got['plannedDemotion']))



class Orders500Test(unittest.TestCase):
    """S4b 설계 4-7: orders_500_0 의 세 원천. 하나라도 읽지 못하거나 0 이 아니면 거짓이다."""

    WINDOW = '2026-09-25T01:00:00+00:00'
    OK_LB = ['10.0.0.9 - - [25/Sep/2026:01:00:05 +0000] "POST /api/v1/orders HTTP/1.1" 201 512 "-" "k6"']

    def lb(self, lines):
        return dict(ax.lb_orders_500(lines, self.WINDOW), ok=True)

    def test_all_sources_zero_is_true(self):
        got = ax.judge_orders_500(self.lb(self.OK_LB), 0.0, 12.0, {'chaos-api-1': 0.0, 'chaos-api-2': 0.0})
        self.assertTrue(got['ok'])

    def test_max_over_time_catches_what_increase_missed(self):
        # 첫 500 에서야 생긴 시계열은 increase() 가 0 을 준다. max_over_time 은 창 안 최댓값 1 을 준다.
        got = ax.judge_orders_500(self.lb(self.OK_LB), 1.0, 12.0, {'chaos-api-1': 0.0, 'chaos-api-2': 0.0})
        self.assertFalse(got['ok'])
        self.assertFalse(got['zero']['prom'])

    def test_lb_500_inside_the_window_is_false_and_outside_is_ignored(self):
        inside = self.OK_LB + ['10.0.0.9 - - [25/Sep/2026:01:02:00 +0000] "DELETE /api/v1/orders/abc HTTP/1.1" 500 90 "-" "bot"']
        self.assertEqual(1, ax.lb_orders_500(inside, self.WINDOW)['orders_500'])
        self.assertFalse(ax.judge_orders_500(self.lb(inside), 0.0, 12.0, {'a': 0.0})['ok'])
        before = self.OK_LB + ['10.0.0.9 - - [25/Sep/2026:00:59:59 +0000] "POST /api/v1/orders HTTP/1.1" 500 90 "-" "k6"']
        self.assertEqual({'access_lines': 1, 'orders_500': 0}, ax.lb_orders_500(before, self.WINDOW))

    def test_response_bytes_of_500_are_not_a_500_status(self):
        lines = ['10.0.0.9 - - [25/Sep/2026:01:00:07 +0000] "POST /api/v1/orders HTTP/1.1" 200 500 "-" "k6"',
                 '10.0.0.9 - - [25/Sep/2026:01:00:08 +0000] "GET /api/v1/orders/open HTTP/1.1" 500 60 "-" "web"']
        self.assertEqual({'access_lines': 2, 'orders_500': 0}, ax.lb_orders_500(lines, self.WINDOW))

    def test_node_total_counts_unknown_uri_post_and_orders_delete(self):
        text = '\n'.join([
            '# TYPE http_server_requests_seconds_count counter',
            'http_server_requests_seconds_count{error="none",exception="none",method="POST",outcome="SERVER_ERROR",'
            'status="500",uri="UNKNOWN"} 1.0',
            'http_server_requests_seconds_count{method="DELETE",outcome="SERVER_ERROR",status="500",'
            'uri="/api/v1/orders/{id}"} 2.0',
            'http_server_requests_seconds_count{method="GET",status="500",uri="UNKNOWN"} 5.0',
            'http_server_requests_seconds_count{method="POST",status="500",uri="/api/v1/community/posts"} 7.0',
            'http_server_requests_seconds_count{method="POST",status="201",uri="/api/v1/orders"} 90.0'])
        self.assertEqual(3.0, ax.node_orders_500(text))
        got = ax.judge_orders_500(self.lb(self.OK_LB), 0.0, 12.0, {'chaos-api-1': 3.0, 'chaos-api-2': 0.0})
        self.assertFalse(got['ok'])
        self.assertFalse(got['zero']['nodes'])

    def test_unreadable_or_empty_sources_are_false(self):
        nodes = {'chaos-api-1': 0.0, 'chaos-api-2': 0.0}
        # LB 로그를 읽지 못했다.
        self.assertFalse(ax.judge_orders_500({'ok': False, 'access_lines': 0, 'orders_500': 0}, 0.0, 12.0, nodes)['ok'])
        # 창 안 LB 접근 줄이 하나도 없다(로그 수집이 멈춘 창의 공허한 0).
        self.assertFalse(ax.judge_orders_500(self.lb([]), 0.0, 12.0, nodes)['ok'])
        # 프로메테우스 질의 실패와 창 안 요청 계수 시계열 0.
        self.assertFalse(ax.judge_orders_500(self.lb(self.OK_LB), None, 12.0, nodes)['ok'])
        self.assertFalse(ax.judge_orders_500(self.lb(self.OK_LB), 0.0, 0.0, nodes)['ok'])
        # 노드 하나를 긁지 못했다.
        self.assertFalse(ax.judge_orders_500(self.lb(self.OK_LB), 0.0, 12.0, {'chaos-api-1': 0.0, 'chaos-api-2': None})['ok'])
        self.assertIsNone(ax.node_orders_500('# 요청 계수 없음\njvm_threads_live_threads 30.0'))

    def test_node_stopped_by_the_experiment_is_left_to_the_other_sources(self):
        # E6 처럼 실험이 kill 한 채 두는 노드는 누계를 읽을 수 없다. 멈춘 노드는 빼고 도는 노드만 센다(S4b 설계 4-7).
        stopped = {'chaos-api-1': ax.STOPPED, 'chaos-api-2': 0.0}
        got = ax.judge_orders_500(self.lb(self.OK_LB), 0.0, 12.0, stopped)
        self.assertTrue(got['ok'])
        self.assertEqual(['chaos-api-1'], got['stopped'])
        # 도는 노드의 500 은 여전히 거짓이다.
        self.assertFalse(ax.judge_orders_500(self.lb(self.OK_LB), 0.0, 12.0,
                                             {'chaos-api-1': ax.STOPPED, 'chaos-api-2': 1.0})['ok'])
        # 멈춘 노드가 멈추기 전에 낸 500 은 프로메테우스와 LB 로그가 잡는다.
        self.assertFalse(ax.judge_orders_500(self.lb(self.OK_LB), 1.0, 12.0, stopped)['ok'])
        killed = self.OK_LB + ['10.0.0.9 - - [25/Sep/2026:01:01:00 +0000] "POST /api/v1/orders HTTP/1.1" 500 90 "-" "k6"']
        self.assertFalse(ax.judge_orders_500(self.lb(killed), 0.0, 12.0, stopped)['ok'])

    def test_stopped_nodes_do_not_make_an_unreadable_node_source_readable(self):
        # 도는 노드를 읽지 못했으면 멈춘 노드가 있어도 읽지 못한 것이다.
        got = ax.judge_orders_500(self.lb(self.OK_LB), 0.0, 12.0, {'chaos-api-1': ax.STOPPED, 'chaos-api-2': None})
        self.assertFalse(got['ok'])
        self.assertFalse(got['readable']['nodes'])
        # 두 노드가 모두 멈췄으면 노드 누계는 공허하다.
        got = ax.judge_orders_500(self.lb(self.OK_LB), 0.0, 12.0, {'chaos-api-1': ax.STOPPED, 'chaos-api-2': ax.STOPPED})
        self.assertFalse(got['ok'])
        self.assertFalse(got['readable']['nodes'])

    def test_unscraped_node_is_stopped_only_when_its_container_is_not_running(self):
        # 긁기에 실패한 노드는 컨테이너가 돌지 않을 때만 멈춘 노드다. 돌거나 docker 로 확인하지 못하면 읽지 못한 것이다.
        self.assertEqual(ax.STOPPED, ax.unscraped_node(False))
        self.assertIsNone(ax.unscraped_node(True))
        self.assertIsNone(ax.unscraped_node(None))


class S4bAxesTest(unittest.TestCase):

    def test_recovery_runs_axes(self):
        self.assertEqual({'recovery_runs_mismatch_0': True, 'recovery_runs_success_gt_0': True},
                         ax.recovery_axes({'recovery_runs_mismatch': 0, 'recovery_runs_success': 3}))
        self.assertFalse(ax.recovery_axes({'recovery_runs_mismatch': 1, 'recovery_runs_success': 3})['recovery_runs_mismatch_0'])
        # 성공 행이 없으면 기록 경로가 배선되지 않은 것이라 불일치 0 이 공허하다.
        self.assertFalse(ax.recovery_axes({'recovery_runs_mismatch': 0, 'recovery_runs_success': 0})['recovery_runs_success_gt_0'])
        # 상태 질의가 실패하면(키 없음) 둘 다 거짓이다.
        self.assertEqual({'recovery_runs_mismatch_0': False, 'recovery_runs_success_gt_0': False}, ax.recovery_axes({}))
        self.assertIn('recovery_runs_mismatch', ax.STATE_SQL)

    def test_api_log_axis(self):
        clean = {'ok': True, 'unhandledException': 0, 'authFailure500': 0}
        self.assertTrue(ax.api_log_axis({'chaos-api-1': clean, 'chaos-api-2': clean, 'chaos-bots': {'ok': False}}))
        self.assertFalse(ax.api_log_axis({'chaos-api-1': clean, 'chaos-api-2': dict(clean, unhandledException=1)}))
        self.assertFalse(ax.api_log_axis({'chaos-api-1': clean, 'chaos-api-2': dict(clean, authFailure500=2)}))
        self.assertFalse(ax.api_log_axis({'chaos-api-1': clean, 'chaos-api-2': {'ok': False}}))
        # 문구는 S4b 코드(ApiExceptionHandler·ApiAuthFilter·EngineRecovery)와 같아야 한다.
        self.assertEqual('처리되지 않은 예외', ax.LOG_PATTERNS['unhandledException'])
        self.assertEqual('인증 처리 실패로 500', ax.LOG_PATTERNS['authFailure500'])
        self.assertEqual('재생이 판정 없이 끝났다', ax.LOG_PATTERNS['replayNoVerdict'])


class ProtocolAxisTest(unittest.TestCase):
    """S7 설계 4-13. 공통 판정의 모드 축은 실행기의 -Protocol 값(2 또는 3)을 본다."""

    def test_protocol_axis_names_the_expected_mode(self):
        self.assertEqual(('protocol_2', True), ax.protocol_axis({'protocol': 2}, 2))
        self.assertEqual(('protocol_3', True), ax.protocol_axis({'protocol': 3}, 3))
        self.assertEqual(('protocol_3', False), ax.protocol_axis({'protocol': 2}, 3))
        self.assertEqual(('protocol_2', False), ax.protocol_axis({}, 2), '상태를 읽지 못하면 참으로 세지 않는다')

    def test_cli_accepts_protocol_3_and_partition_resources(self):
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
            ax.main(['judge', '--window-from', 'x', '--fault-from', 'x', '--fault-to', 'x', '--protocol', '4',
                     '--out', 'x'])
        self.assertIn('matching-p1', ax.LEASE_RESOURCES)


class S5CountersTest(unittest.TestCase):

    def test_s5_counters_keep_every_label_but_the_application_tag(self):
        text = '\n'.join([
            '# HELP exchange_routing_precheck_total',
            'exchange_routing_precheck_total{application="exchange",result="not_owner"} 3.0',
            'exchange_routing_precheck_total{application="exchange",result="accepted"} 10.0',
            'exchange_db_read_retry_total{application="exchange",outcome="recovered",site="auth"} 1.0',
            'exchange_order_cancel_handoff_worker_total{application="exchange",outcome="claimed"} 2.0',
            'exchange_order_intake_unavailable_total{application="exchange",cause="fencing",response="503"} 4.0',
            'exchange_order_append_transient_total{application="exchange",sqlstate="40P01"} 1.0',
            'exchange_admission_rejected_total{application="exchange",scope="order"} 7.0',
            'jvm_threads_live_threads{application="exchange"} 50.0',
        ])
        self.assertEqual({
            'exchange_routing_precheck_total{result=not_owner}': 3.0,
            'exchange_routing_precheck_total{result=accepted}': 10.0,
            'exchange_db_read_retry_total{outcome=recovered,site=auth}': 1.0,
            'exchange_order_cancel_handoff_worker_total{outcome=claimed}': 2.0,
            'exchange_order_intake_unavailable_total{fencing|503}': 4.0,
            'exchange_order_append_transient_total{sqlstate=40P01}': 1.0,
            'exchange_admission_rejected_total{scope=order}': 7.0,
        }, ax.parse_counters(text))


if __name__ == '__main__':
    unittest.main()
