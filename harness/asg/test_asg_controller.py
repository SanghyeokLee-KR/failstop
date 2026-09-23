"""asg_controller 시험. 로드밸런서 토글이 조회 업스트림 안에서만 일어나는지, reload 가 실패하면
설정 파일을 되돌리는지 본다. docker 는 부르지 않는다.

돌리는 법:
  python -B -m unittest discover -s harness/asg -p "test_*.py"
"""
import os
import shutil
import tempfile
import unittest
from unittest import mock

import asg_controller as ctl

HERE = os.path.dirname(os.path.abspath(__file__))
CHAOS_CONF = os.path.join(HERE, '..', 'nginx', 'nginx.conf')
# 2026-09-08 ex 스택 실측이 쓴 설정이다. asg_controller 는 이 형태를 계속 다뤄야 한다.
EX_CONF = os.path.join(HERE, '..', 'evidence', 'asg-scenario-20260908', 'lb_nginx.conf')


def upstream_line(text, upstream, name):
    block = text.split('upstream %s' % upstream, 1)[1].split('}', 1)[0]
    return next(line for line in block.splitlines() if 'server %s:8080' % name in line)


class BalancerTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.conf = os.path.join(self.dir, 'nginx.conf')
        shutil.copyfile(CHAOS_CONF, self.conf)
        self.lb = ctl.Balancer(self.conf, 'chaos-lb')

    def tearDown(self):
        shutil.rmtree(self.dir)

    def read(self):
        with open(self.conf, encoding='utf-8') as f:
            return f.read()

    def test_read_line_goes_down_without_touching_write_backup(self):
        backup = upstream_line(self.read(), 'exchange_write', 'chaos-api-2')

        self.assertTrue(self.lb.set_down('chaos-api-2', True))

        text = self.read()
        self.assertEqual(backup, upstream_line(text, 'exchange_write', 'chaos-api-2'))
        self.assertTrue(upstream_line(text, 'exchange_read', 'chaos-api-2').endswith(' down;'))
        self.assertFalse(self.lb.active('chaos-api-2'))

    def test_active_follows_read_upstream(self):
        self.assertTrue(self.lb.active('chaos-api-1'))
        self.assertTrue(self.lb.active('chaos-api-2'))
        self.assertFalse(self.lb.active('chaos-api-3'))

    def test_same_state_is_not_rewritten(self):
        self.assertFalse(self.lb.set_down('chaos-api-3', True))
        self.assertTrue(self.lb.set_down('chaos-api-3', False))
        self.assertFalse(self.lb.set_down('chaos-api-3', False))
        self.assertTrue(self.lb.active('chaos-api-3'))

    def test_write_members_are_nodes_outside_read_upstream(self):
        self.assertEqual({'chaos-api-1', 'chaos-api-2'}, self.lb.write_members())

    def test_missing_read_upstream_is_an_error(self):
        with open(self.conf, 'w', encoding='utf-8') as f:
            f.write('http {\n    upstream exchange_write {\n        server chaos-api-1:8080;\n    }\n}\n')
        with self.assertRaises(RuntimeError):
            self.lb.active('chaos-api-1')

    def test_failed_config_test_restores_file(self):
        original = self.read()

        def sh(args, check=True, timeout=120):
            if args[-1] == '-t':
                raise RuntimeError('명령 실패: nginx -t')
            return ''

        with mock.patch.object(ctl, 'sh', side_effect=sh):
            self.lb.set_down('chaos-api-3', False)
            with self.assertRaises(RuntimeError):
                self.lb.reload()

        self.assertEqual(original, self.read())
        self.assertFalse(self.lb.active('chaos-api-3'))

    def test_failed_reload_signal_restores_file(self):
        original = self.read()

        def sh(args, check=True, timeout=120):
            if args[-1] == 'reload':
                raise RuntimeError('명령 실패: nginx -s reload')
            return ''

        with mock.patch.object(ctl, 'sh', side_effect=sh):
            self.lb.set_down('chaos-api-2', True)
            with self.assertRaises(RuntimeError):
                self.lb.reload()

        self.assertEqual(original, self.read())
        self.assertTrue(self.lb.active('chaos-api-2'))

    def test_applied_change_is_kept_when_a_later_reload_fails(self):
        with mock.patch.object(ctl, 'sh', return_value=''):
            self.lb.set_down('chaos-api-3', False)
            self.lb.reload()
        applied = self.read()

        with mock.patch.object(ctl, 'sh', side_effect=RuntimeError('nginx 컨테이너 없음')):
            with self.assertRaises(RuntimeError):
                self.lb.reload()

        self.assertEqual(applied, self.read())
        self.assertTrue(self.lb.active('chaos-api-3'))


class ExStackConfTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.conf = os.path.join(self.dir, 'lb_nginx.conf')
        shutil.copyfile(EX_CONF, self.conf)
        self.lb = ctl.Balancer(self.conf, 'ex-lb')

    def tearDown(self):
        shutil.rmtree(self.dir)

    def test_ex_pool_toggles_in_read_upstream(self):
        self.assertFalse(self.lb.active('ex-app3'))
        self.assertTrue(self.lb.set_down('ex-app3', False))
        self.assertTrue(self.lb.active('ex-app3'))
        self.assertEqual({'ex-app'}, self.lb.write_members())


if __name__ == '__main__':
    unittest.main()
