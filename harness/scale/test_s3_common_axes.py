"""s3_common_axes 의 집계 함수 시험. 도커와 DB 없이 돈다.

돌리는 법: python -B -m unittest harness/scale/test_s3_common_axes.py
"""
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


if __name__ == '__main__':
    unittest.main()
