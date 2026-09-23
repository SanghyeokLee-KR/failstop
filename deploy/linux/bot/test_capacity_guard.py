import contextlib
import copy
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock
import capacity_guard
from capacity_guard import METRICS, NODES, decision


class CapacityGuardTest(unittest.TestCase):
    def setUp(self):
        self.rows = []
        for node in NODES:
            for metric, value in zip(METRICS, [40*1024**3, 50*1024**3, 9000, 10000]):
                self.rows.append({'metric': {'node': node, '__name__': metric}, 'value': [0, str(value)]})
        self.ages = [{'metric': {'node': node}, 'value': [0, '15']} for node in NODES]

    def test_healthy_hosts_continue(self):
        result = decision(self.rows, self.ages, 2)
        self.assertFalse(result['stop'])
        self.assertEqual(result['failures'], 0)
        self.assertEqual(set(result['nodes']), NODES)

    def test_each_host_can_stop_bots(self):
        for node in NODES:
            rows = copy.deepcopy(self.rows)
            next(r for r in rows if r['metric'] == {'node': node, '__name__': METRICS[0]})['value'][1] = str(10*1024**3)
            self.assertTrue(decision(rows, self.ages)['stop'])

    def test_inode_exhaustion_stops(self):
        next(r for r in self.rows if r['metric']['__name__'] == METRICS[2])['value'][1] = '1000'
        self.assertTrue(decision(self.rows, self.ages)['stop'])

    def test_missing_stale_nan_duplicate_fail_closed_after_three_checks(self):
        variants = [(self.rows[:-1], self.ages), (self.rows + [self.rows[0]], self.ages)]
        stale = copy.deepcopy(self.ages); stale[0]['value'][1] = '91'
        variants.append((self.rows, stale))
        invalid = copy.deepcopy(self.rows); invalid[0]['value'][1] = 'NaN'
        variants.append((invalid, self.ages))
        for rows, ages in variants:
            self.assertFalse(decision(rows, ages, 0)['stop'])
            self.assertTrue(decision(rows, ages, 2)['stop'])

    def run_main(self, rows, ages, previous=None, stop_error=None):
        # 서비스와 같은 --enforce로 실행하되 상태 디렉터리·Prometheus 조회·systemctl은 바꿔 끼운다.
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            if previous is not None:
                (state / 'state.json').write_text(json.dumps(previous))
            stop = mock.Mock(side_effect=stop_error)
            with mock.patch.object(capacity_guard, 'STATE', state), \
                    mock.patch.object(capacity_guard, 'query', side_effect=[rows, ages]), \
                    mock.patch.object(capacity_guard.subprocess, 'run', stop), \
                    mock.patch('sys.argv', ['capacity_guard.py', '--enforce']), \
                    contextlib.redirect_stdout(io.StringIO()):
                code = capacity_guard.main()
            return code, json.loads((state / 'state.json').read_text()), stop

    def full_disk_rows(self):
        rows = copy.deepcopy(self.rows)
        next(r for r in rows if r['metric']['__name__'] == METRICS[0])['value'][1] = str(5*1024**3)
        return rows

    def test_other_host_missing_samples_finish_without_unit_failure(self):
        # app-1 수집이 끊겨도 bot-1의 용량 보호 유닛은 failed가 되지 않는다. 원인은 app-1의 경보가 알린다.
        rows = [r for r in self.rows if r['metric']['node'] != 'app-1']
        ages = [r for r in self.ages if r['metric']['node'] != 'app-1']
        code, state, stop = self.run_main(rows, ages)
        self.assertEqual(code, 0)
        self.assertEqual((state['failures'], state['stop']), (1, False))
        stop.assert_not_called()

    def test_stop_decisions_finish_normally(self):
        # 세 번째 수집 누락과 용량 부족 모두 봇을 멈춘 뒤 정상 종료한다. 알림은 봇 중지 경보가 맡는다.
        missing = [r for r in self.rows if r['metric']['node'] != 'app-1']
        for rows, previous in [(missing, {'failures': 2}), (self.full_disk_rows(), None)]:
            code, state, stop = self.run_main(rows, self.ages, previous)
            self.assertEqual(code, 0)
            self.assertTrue(state['stop'])
            stop.assert_called_once_with(['systemctl', 'stop', 'exchange-bot.service'], check=True, timeout=75)

    def test_failed_bot_stop_is_unit_failure(self):
        # 봇을 멈추지 못하면 보호가 동작하지 않은 것이므로 예외로 끝나 유닛이 failed가 된다.
        error = subprocess.CalledProcessError(1, ['systemctl', 'stop', 'exchange-bot.service'])
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_main(self.full_disk_rows(), self.ages, stop_error=error)


if __name__ == '__main__':
    unittest.main()
