"""commit_delay 시험. 적용 확인이 SHOW 값과 postgresql.auto.conf 출처를 함께 요구하는지, 복구가 실패를 숨기지 않는지 본다.

돌리는 법:
  python -m unittest discover -s harness/scale -p "test_*.py"
"""
import json
import re
import unittest
from unittest import mock

import commit_delay as cd

AUTO = '/var/lib/postgresql/data/postgresql.auto.conf'
DEFAULTS = {'commit_delay': 0, 'commit_siblings': 5}


class FakeServer:
    """ALTER SYSTEM 은 파일 값만 바꾼다. 재적재 뒤 새 세션 조회가 lag 번 지나야 파일 값이 보인다."""

    def __init__(self, lag=0, fail=(), command_line=None, pending=False):
        self.file, self.visible = {}, {}
        self.lag, self.wait = lag, None
        self.fail, self.command_line, self.pending = fail, command_line or {}, pending
        self.calls = []

    def current(self, name):
        if name in self.command_line:
            return self.command_line[name], 'command line', None
        if name in self.visible:
            return self.visible[name], 'configuration file', AUTO
        return DEFAULTS[name], 'default', None

    def __call__(self, sql, timeout=30):
        self.calls.append(sql)
        if sql.startswith(self.fail):
            return 1, '', 'ERROR:  permission denied'
        m = re.fullmatch(r'ALTER SYSTEM SET (\w+) = (\d+)', sql)
        if m:
            self.file[m.group(1)] = int(m.group(2))
            return 0, 'ALTER SYSTEM\n', ''
        m = re.fullmatch(r'ALTER SYSTEM RESET (\w+)', sql)
        if m:
            self.file.pop(m.group(1), None)
            return 0, 'ALTER SYSTEM\n', ''
        if sql == cd.RELOAD_SQL:
            self.wait = self.lag
            return 0, 't\n', ''
        if sql == 'SHOW commit_delay' and self.wait is not None:
            if self.wait == 0:
                self.visible, self.wait = dict(self.file), None
            else:
                self.wait -= 1
        m = re.fullmatch(r'SHOW (\w+)', sql)
        if m:
            return 0, '%d\n' % self.current(m.group(1))[0], ''
        if sql == cd.SETTINGS_SQL:
            rows = []
            for name in cd.NAMES:
                value, source, sourcefile = self.current(name)
                rows.append({'name': name, 'setting': str(value), 'source': source, 'sourcefile': sourcefile,
                             'pending_restart': self.pending})
            return 0, json.dumps(rows), ''
        raise AssertionError('예상하지 않은 질의: %s' % sql)


def call(fn, server, *args):
    with mock.patch.object(cd.pgs, 'run_psql', server), mock.patch.object(cd.time, 'sleep') as sleep:
        return fn(*args), sleep


class ApplyTest(unittest.TestCase):
    def test_apply_sets_reloads_and_confirms(self):
        server = FakeServer()
        out, sleep = call(cd.apply, server, 1000, 1)
        self.assertTrue(out['ok'])
        self.assertEqual(server.calls[:3], ['ALTER SYSTEM SET commit_delay = 1000',
                                            'ALTER SYSTEM SET commit_siblings = 1', cd.RELOAD_SQL])
        self.assertEqual((out['확인']['commit_delay'], out['확인']['commit_siblings'], out['확인']['시도']),
                         (1000, 1, 1))
        sleep.assert_not_called()

    def test_baseline_values_are_written_and_come_from_auto_conf(self):
        server = FakeServer()
        out, _ = call(cd.apply, server, 0, 5)
        self.assertTrue(out['ok'])
        self.assertIn('ALTER SYSTEM SET commit_delay = 0', server.calls)
        self.assertTrue(all(r['sourcefile'] == AUTO for r in out['확인']['출처']))

    def test_reload_lag_is_seen_again(self):
        out, sleep = call(cd.apply, FakeServer(lag=2), 3000, 5)
        self.assertTrue(out['ok'])
        self.assertEqual(out['확인']['시도'], 3)
        self.assertEqual(sleep.call_count, 2)

    def test_command_line_value_is_not_applied(self):
        out, _ = call(cd.apply, FakeServer(command_line={'commit_delay': 0}), 1000, 5)
        self.assertFalse(out['ok'])
        self.assertEqual((out['확인']['commit_delay'], out['확인']['시도']), (0, cd.CONFIRM_ATTEMPTS))

    def test_pending_restart_is_not_applied(self):
        self.assertFalse(call(cd.apply, FakeServer(pending=True), 250, 1)[0]['ok'])

    def test_out_of_range_values_send_no_sql(self):
        for delay, siblings in ((100001, 5), (-1, 5), (True, 5), ('1000', 5), (1000, 1001), (1000, None)):
            server = FakeServer()
            out, _ = call(cd.apply, server, delay, siblings)
            self.assertFalse(out['ok'])
            self.assertEqual(server.calls, [])

    def test_failed_alter_stops_before_reload(self):
        server = FakeServer(fail=('ALTER SYSTEM SET commit_siblings',))
        out, _ = call(cd.apply, server, 1000, 1)
        self.assertFalse(out['ok'])
        self.assertNotIn(cd.RELOAD_SQL, server.calls)
        self.assertIsNone(out['확인'])


class InEffectTest(unittest.TestCase):
    def state(self, delay=1000, siblings=1, sourcefile=AUTO, pending=False, names=cd.NAMES):
        return {'ok': True, 'commit_delay': delay, 'commit_siblings': siblings,
                '출처': [{'name': n, 'sourcefile': sourcefile, 'pending_restart': pending} for n in names]}

    def test_needs_values_source_and_both_rows(self):
        self.assertTrue(cd.in_effect(self.state(), 1000, 1))
        self.assertFalse(cd.in_effect(self.state(siblings=5), 1000, 1))
        self.assertFalse(cd.in_effect(self.state(sourcefile='/var/lib/postgresql/data/postgresql.conf'), 1000, 1))
        self.assertFalse(cd.in_effect(self.state(sourcefile=None), 1000, 1))
        self.assertFalse(cd.in_effect(self.state(names=('commit_delay',)), 1000, 1))
        self.assertFalse(cd.in_effect(dict(self.state(), ok=False), 1000, 1))
        self.assertFalse(cd.in_effect(None, 1000, 1))

    def test_pending_restart_must_be_false(self):
        self.assertFalse(cd.in_effect(self.state(pending=None), 1000, 1))


class ResetTest(unittest.TestCase):
    def applied(self):
        server = FakeServer()
        self.assertTrue(call(cd.apply, server, 3000, 1)[0]['ok'])
        server.calls.clear()
        return server

    def test_reset_clears_auto_conf(self):
        server = self.applied()
        out, _ = call(cd.reset, server)
        self.assertTrue(out['ok'])
        self.assertEqual(server.calls[:3], list(cd.RESET_SQLS))
        self.assertEqual((out['확인']['commit_delay'], out['확인']['commit_siblings']), (0, 5))

    def test_failed_reset_command_still_reloads(self):
        server = self.applied()
        server.fail = ('ALTER SYSTEM RESET commit_delay',)
        out, _ = call(cd.reset, server)
        self.assertFalse(out['ok'])
        self.assertIn('ALTER SYSTEM RESET commit_delay', out['error'])
        self.assertEqual(server.calls[:3], list(cd.RESET_SQLS))
        self.assertFalse(cd.cleared(out['확인']))

    def test_unreadable_settings_are_not_cleared(self):
        out, sleep = call(cd.reset, FakeServer(fail=('SHOW',)))
        self.assertFalse(out['ok'])
        self.assertFalse(cd.cleared(out['확인']))
        self.assertEqual(sleep.call_count, cd.CONFIRM_ATTEMPTS - 1)


if __name__ == '__main__':
    unittest.main()
