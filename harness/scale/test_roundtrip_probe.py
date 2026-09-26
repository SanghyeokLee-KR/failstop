"""roundtrip_probe 의 칸 값 계산 시험. 도커와 DB 없이 돈다.

돌리는 법: python -B -m unittest harness/scale/test_roundtrip_probe.py
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import roundtrip_probe as rp  # noqa: E402


class JournalAcceptRateTest(unittest.TestCase):
    """S4b 설계 4-7: X7 칸 값은 k6 주행 창의 k6 NEW 행 합을 주행 초로 나눈 값이다."""

    def test_sums_k6_rows_over_the_run_seconds(self):
        accepts = {'마켓': [{'id': 1, 'symbol': 'KRW-BTC', 'k6_new': 1300, 'all_commands': 2100},
                            {'id': 2, 'symbol': 'KRW-ETH', 'k6_new': 1300, 'all_commands': 1900}], 'error': None}
        self.assertAlmostEqual(20.0, rp.journal_accept_rate(accepts, 130))

    def test_bot_rows_are_not_counted(self):
        # 봇과 취소 명령은 all_commands 에만 든다. k6 행이 없는 마켓은 0 이다.
        accepts = {'마켓': [{'id': 1, 'symbol': 'KRW-BTC', 'k6_new': 0, 'all_commands': 900}], 'error': None}
        self.assertEqual(0.0, rp.journal_accept_rate(accepts, 130))

    def test_no_window_or_failed_query_has_no_value(self):
        self.assertIsNone(rp.journal_accept_rate({'마켓': None, 'error': '주행 창 시각이나 주행 식별자가 없다'}, 130))
        self.assertIsNone(rp.journal_accept_rate({'마켓': None, 'error': 'psql 종료코드 2'}, 130))
        self.assertIsNone(rp.journal_accept_rate({'마켓': [], 'error': None}, 0))


if __name__ == '__main__':
    unittest.main()
