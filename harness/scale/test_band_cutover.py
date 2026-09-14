"""band_cutover 시험. 되돌릴 수 없는 래치를 거부하는 조건과 대역 명세 부재가 0 으로 접히지 않는지를 본다.

돌리는 법:
  python -m unittest discover -s harness/scale -p "test_*.py"
"""
import json
import unittest
from unittest import mock

import band_cutover as bc

PRE_OK = {'function': 'latch_command_band_cutover()', 'owner': 'exchange', 'current_user': 'exchange',
          'app_role_execute': False, 'journal_rows': 3}
LATCHED = {'rows': [{'name': 'command_seq', 'latched_at': '2026-09-15T10:00:01+09:00', 'latched_by': 'exchange'}]}
BAND_PREFIX = bc.BAND_ROWS_SQL.split('%s')[0]


def reply(obj, rc=0):
    return rc, json.dumps(obj), ''


class FakePsql:
    """질의가 키로 시작하면 정해 둔 응답을 낸다. 부른 질의는 calls 에 쌓인다."""

    def __init__(self, replies):
        self.replies = replies
        self.calls = []

    def __call__(self, sql, timeout=30):
        self.calls.append(sql)
        for key, value in self.replies.items():
            if sql.startswith(key):
                return value
        raise AssertionError('예상하지 않은 질의: %s' % sql[:60])


def call(fn, replies, *args):
    fake = FakePsql(replies)
    with mock.patch.object(bc.pgs, 'run_psql', fake):
        return fn(*args), fake.calls


class LatchTest(unittest.TestCase):
    def test_latch_records_row(self):
        out, calls = call(bc.latch, {bc.PRECHECK_SQL: reply(PRE_OK), bc.LATCH_SQL: (0, '', ''),
                                     bc.STATE_SQL: reply(LATCHED)})
        self.assertTrue(out['ok'])
        self.assertEqual(out['상태']['래치 행'][0]['latched_at'], LATCHED['rows'][0]['latched_at'])
        self.assertEqual(calls.count(bc.LATCH_SQL), 1)

    def test_missing_function_is_refused_before_latch(self):
        out, calls = call(bc.latch, {bc.PRECHECK_SQL: reply(dict(PRE_OK, function=None, owner=None))})
        self.assertFalse(out['ok'])
        self.assertNotIn(bc.LATCH_SQL, calls)

    def test_non_owner_is_refused_before_latch(self):
        out, calls = call(bc.latch, {bc.PRECHECK_SQL: reply(dict(PRE_OK, owner='flyway'))})
        self.assertFalse(out['ok'])
        self.assertIn('소유자', out['error'])
        self.assertNotIn(bc.LATCH_SQL, calls)

    def test_failed_precheck_is_refused_before_latch(self):
        out, calls = call(bc.latch, {bc.PRECHECK_SQL: (1, '', 'connection refused')})
        self.assertFalse(out['ok'])
        self.assertEqual(calls, [bc.PRECHECK_SQL])

    def test_latch_error(self):
        out, _ = call(bc.latch, {bc.PRECHECK_SQL: reply(PRE_OK), bc.LATCH_SQL: (1, '', 'ERROR: default 파티션 행'),
                                 bc.STATE_SQL: reply({'rows': []})})
        self.assertFalse(out['ok'])
        self.assertIn('종료코드', out['error'])

    def test_latch_without_row(self):
        out, _ = call(bc.latch, {bc.PRECHECK_SQL: reply(PRE_OK), bc.LATCH_SQL: (0, '', ''),
                                 bc.STATE_SQL: reply({'rows': []})})
        self.assertFalse(out['ok'])

    def test_unreadable_state_is_not_empty(self):
        state, _ = call(bc.latch_state, {bc.STATE_SQL: (1, '', 'relation "journal_cutover" does not exist')})
        self.assertFalse(state['ok'])
        self.assertIsNone(state['래치 행'])


class BandTest(unittest.TestCase):
    def test_missing_band_spec_is_not_zero(self):
        out, _ = call(bc.c28, {bc.C28_SQL: reply({'band_span': None, 'markets': []})})
        self.assertFalse(out['ok'])
        self.assertIsNone(out['위반 행'])
        rows, _ = call(bc.band_rows, {BAND_PREFIX: reply({'band_span': None, 'markets': [
            {'id': 1, 'symbol': 'KRW-BTC', 'band_rows': 0, 'legacy_rows': 0}]})})
        self.assertIsNotNone(rows['error'])
        self.assertIsNone(rows['마켓'])

    def test_c28_sums_markets(self):
        out, _ = call(bc.c28, {bc.C28_SQL: reply({'band_span': 10 ** 12, 'markets': [
            {'market_id': 1, 'legacy_after_band': 2}, {'market_id': 2, 'legacy_after_band': 0}]})})
        self.assertEqual((out['ok'], out['위반 행']), (True, 2))

    def test_window_without_times_does_not_query(self):
        rows, calls = call(bc.band_rows, {}, (None, '2026-09-15 10:02:00+09'))
        self.assertIsNotNone(rows['error'])
        self.assertEqual(calls, [])

    def test_window_filters_created_at(self):
        rows, calls = call(bc.band_rows, {BAND_PREFIX: reply({'band_span': 10 ** 12, 'markets': []})},
                           ('2026-09-15 10:00:00+09', '2026-09-15 10:02:00+09'))
        self.assertIsNone(rows['error'])
        self.assertIn("created_at >= '2026-09-15 10:00:00+09'", calls[0])


if __name__ == '__main__':
    unittest.main()
