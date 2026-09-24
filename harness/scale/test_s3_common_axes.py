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


if __name__ == '__main__':
    unittest.main()
