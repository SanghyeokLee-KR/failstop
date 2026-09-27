"""capacity_reconciler 시험. 헬스를 통과하지 못한 노드를 다음 주기에도 등록하지 않는지, 쓰기 백업을
축소 자리에 두는 설정과 compose 노드를 지우는 run 모드를 거부하는지, 멈춘 이유를 남기는지 본다.
docker 와 DB 는 부르지 않는다.

돌리는 법:
  python -B -m unittest discover -s harness/asg -p "test_*.py"
"""
import argparse
import contextlib
import io
import json
import os
import shutil
import sys
import tempfile
import unittest
from unittest import mock

import asg_controller as ctl
import capacity_reconciler as rc

CHAOS_CONF = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'nginx', 'nginx.conf')
CHAOS = rc.STACKS['chaos']


class ChaosStackTest(unittest.TestCase):
    """chaos 스택 규격 그대로 조정기를 돌린다. 컨테이너 상태는 사전으로 흉내 낸다."""

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.conf = os.path.join(self.dir, 'nginx.conf')
        shutil.copyfile(CHAOS_CONF, self.conf)
        self.log = os.path.join(self.dir, 'recon.jsonl')
        self.running = {'chaos-api-1': True, 'chaos-api-2': True, 'chaos-api-3': False}
        self.desired = 2
        self.commands = []
        self.healthy = mock.Mock(return_value=True)
        self.reconciler = None

        patches = [
            mock.patch.multiple(ctl, LEADER=CHAOS['leader'], POOL=list(CHAOS['pool']),
                                HOST_PORT=dict(CHAOS['host_port'])),
            mock.patch.object(ctl, 'running', side_effect=lambda n: self.running[n]),
            mock.patch.object(ctl, 'sh', side_effect=self.sh),
            mock.patch.object(ctl, 'wait_healthy', self.healthy),
            mock.patch.object(rc, 'exists', return_value=True),
            mock.patch.object(rc, 'desired_from_db', side_effect=lambda *a: self.desired),
            # emit 이 사람용 한 줄을 stdout 에도 찍는다. 판정은 JSONL 로 한다.
            contextlib.redirect_stdout(io.StringIO()),
        ]
        for p in patches:
            self.enterContext(p)

    def tearDown(self):
        if self.reconciler is not None:
            self.reconciler.log_f.close()
        shutil.rmtree(self.dir)

    def sh(self, args, check=True, timeout=120):
        self.commands.append(args)
        if args[:2] == ['docker', 'start']:
            self.running[args[2]] = True
        if args[:2] == ['docker', 'stop']:
            self.running[args[2]] = False
        return ''

    def make(self, **overrides):
        a = argparse.Namespace(
            stack='chaos', lb_conf=self.conf, lb_container='chaos-lb', pg_container='chaos-pg',
            database='exchange', user='exchange', log=self.log, interval=0, duration=0,
            drain=0, launch_budget=1, start_mode='start', min_pool=1, node_cpus=1.0, once=True)
        for k, v in overrides.items():
            setattr(a, k, v)
        self.reconciler = rc.Reconciler(a)
        return self.reconciler

    def events(self):
        self.reconciler.log_f.flush()
        with open(self.log, encoding='utf-8') as f:
            return [json.loads(line) for line in f]

    def reloads(self):
        return [c for c in self.commands if c[-1] == 'reload']

    def test_node_that_missed_health_budget_is_not_registered_next_cycle(self):
        self.desired = 3
        self.healthy.return_value = False
        r = self.make()

        r.step()
        r.step()

        self.assertEqual(['add.launch', 'add.timeout', 'add.timeout'],
                         [e['kind'] for e in self.events()])
        self.assertEqual(2, self.healthy.call_count)
        self.assertFalse(r.lb.active('chaos-api-3'))
        self.assertEqual([], self.reloads())

    def test_node_is_registered_once_it_passes_health(self):
        self.desired = 3
        self.healthy.side_effect = [False, True]
        r = self.make()

        r.step()
        r.step()

        kinds = [e['kind'] for e in self.events()]
        self.assertEqual(['add.launch', 'add.timeout', 'add.done'], kinds)
        self.assertTrue(r.lb.active('chaos-api-3'))
        self.assertEqual(1, len(self.reloads()))

    def test_scale_in_takes_read_node_out_without_touching_write_backup(self):
        self.running['chaos-api-3'] = True
        r = self.make()
        with mock.patch.object(ctl, 'sh', return_value=''):
            r.lb.set_down('chaos-api-3', False)
            r.lb.reload()
        with open(self.conf, encoding='utf-8') as f:
            write_block = f.read().split('upstream exchange_read', 1)[0]

        r.step()

        with open(self.conf, encoding='utf-8') as f:
            text = f.read()
        self.assertEqual(write_block, text.split('upstream exchange_read', 1)[0])
        self.assertFalse(r.lb.active('chaos-api-3'))
        self.assertIn(['docker', 'stop', 'chaos-api-3'], self.commands)
        self.assertNotIn(['docker', 'stop', 'chaos-api-2'], self.commands)

    def sd(self):
        with open(os.path.join(self.dir, 'sd', 'reader.json'), encoding='utf-8') as f:
            return json.load(f)

    def test_sd_file_lists_read_node_only_after_it_is_registered(self):
        self.desired = 3
        self.healthy.side_effect = [False, True]
        r = self.make(sd_file=os.path.join(self.dir, 'sd', 'reader.json'), sd_port=8080)

        r.step()
        self.assertEqual([], self.sd())       # 기동은 했지만 헬스를 통과하지 못했다. 대상이 아니다
        r.step()

        self.assertEqual([{'targets': ['chaos-api-3:8080']}], self.sd())
        # 쓰기 백업(api-2)은 min_pool 앞머리라 정적 수집 대상이다. 파일에 적지 않는다
        self.assertNotIn('chaos-api-2', json.dumps(self.sd()))

    def test_sd_file_drops_read_node_before_it_is_stopped(self):
        self.running['chaos-api-3'] = True
        sd_path = os.path.join(self.dir, 'sd', 'reader.json')
        r = self.make(sd_file=sd_path, sd_port=8080)
        with mock.patch.object(ctl, 'sh', return_value=''):
            r.lb.set_down('chaos-api-3', False)
            r.lb.reload()
        r.sync_sd()
        self.assertEqual([{'targets': ['chaos-api-3:8080']}], self.sd())
        seen_at_stop = []
        original = self.sh

        def sh(args, check=True, timeout=120):
            if args[:2] == ['docker', 'stop']:
                seen_at_stop.append(self.sd())
            return original(args, check, timeout)

        with mock.patch.object(ctl, 'sh', side_effect=sh):
            r.step()

        self.assertEqual([[]], seen_at_stop)
        self.assertEqual([], self.sd())

    def test_sd_file_is_not_rewritten_when_unchanged(self):
        sd_path = os.path.join(self.dir, 'sd', 'reader.json')
        r = self.make(sd_file=sd_path, sd_port=8080)
        r.step()
        r.step()
        writes = [e for e in self.events() if e['kind'] == 'sd.write']
        self.assertEqual(1, len(writes))
        self.assertEqual([], self.sd())

    def test_no_sd_file_writes_nothing(self):
        r = self.make()
        r.step()
        self.assertFalse(os.path.exists(os.path.join(self.dir, 'sd')))
        self.assertNotIn('sd.write', [e['kind'] for e in self.events()])

    def test_stop_records_that_duration_ran_out(self):
        r = self.make(once=False, duration=0)
        with mock.patch.object(r, 'step'):
            r.run()
        stop = self.events()[-1]
        self.assertEqual('stop', stop['kind'])
        self.assertEqual('duration', stop['reason'])

    def test_stop_records_single_cycle(self):
        r = self.make(once=True, duration=3600)
        with mock.patch.object(r, 'step'):
            r.run()
        self.assertEqual('once', self.events()[-1]['reason'])


class MainArgumentTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.conf = os.path.join(self.dir, 'nginx.conf')
        shutil.copyfile(CHAOS_CONF, self.conf)
        # main 이 asg_controller 전역을 갈아끼운다. 다른 시험에 새지 않게 되돌린다.
        p = mock.patch.multiple(ctl, LEADER=ctl.LEADER, POOL=list(ctl.POOL), NETWORK=ctl.NETWORK,
                                IMAGE=ctl.IMAGE, CPUS_PER_NODE=ctl.CPUS_PER_NODE,
                                HOST_PORT=dict(ctl.HOST_PORT))
        p.start()
        self.addCleanup(p.stop)

    def tearDown(self):
        shutil.rmtree(self.dir)

    def main(self, *argv):
        argv = ['capacity_reconciler.py', '--lb-conf', self.conf,
                '--log', os.path.join(self.dir, 'recon.jsonl')] + list(argv)
        err = io.StringIO()
        with mock.patch.object(sys, 'argv', argv), \
                mock.patch.object(rc, 'Reconciler') as reconciler, \
                contextlib.redirect_stderr(err):
            try:
                rc.main()
            except SystemExit as e:
                return None, e.code, err.getvalue()
        return reconciler, 0, err.getvalue()

    def test_chaos_defaults_are_accepted(self):
        reconciler, code, _ = self.main('--stack', 'chaos')
        self.assertEqual(0, code)
        reconciler.assert_called_once()
        self.assertEqual(['chaos-api-2', 'chaos-api-3'], ctl.POOL)

    def test_run_mode_on_compose_stack_is_rejected(self):
        _, code, err = self.main('--stack', 'chaos', '--start-mode', 'run')
        self.assertEqual(2, code)
        self.assertIn('run', err)

    def test_write_backup_in_removable_slot_is_rejected(self):
        _, code, err = self.main('--stack', 'chaos', '--pool', 'chaos-api-3,chaos-api-2')
        self.assertEqual(2, code)
        self.assertIn('chaos-api-2', err)

    def test_min_pool_zero_exposes_write_backup(self):
        _, code, err = self.main('--stack', 'chaos', '--min-pool', '0')
        self.assertEqual(2, code)
        self.assertIn('chaos-api-2', err)


if __name__ == '__main__':
    unittest.main()
