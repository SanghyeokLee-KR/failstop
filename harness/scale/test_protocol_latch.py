"""protocol_latch 의 판정 함수 시험. 도커와 DB 없이 돈다.

돌리는 법: python -B -m unittest harness/scale/test_protocol_latch.py
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import protocol_latch as pl  # noqa: E402

GOOD_ENV = {'CHAOS_PROJECT': 'exchange-pml', 'CHAOS_APP_IMAGE': 'exchange-chaos-app:pml'}
LABELS = {'chaos-pg': 'exchange-pml', 'chaos-api-1': 'exchange-pml', 'chaos-api-2': 'exchange-pml',
          'chaos-bots': 'exchange-pml'}
IDS = {'chaos-api-1': 'sha256:aa', 'chaos-api-2': 'sha256:aa', 'chaos-bots': 'sha256:aa'}


class PlanTransitionTest(unittest.TestCase):

    def test_same_mode_is_already(self):
        self.assertEqual('already', pl.plan_transition(2, 2, False))
        self.assertEqual('already', pl.plan_transition(1, 1, True))

    def test_allowed_start_modes(self):
        self.assertEqual('call', pl.plan_transition(1, 2, False))
        self.assertEqual('call', pl.plan_transition(2, 1, True))

    def test_downgrade_needs_flag_and_other_modes_refused(self):
        self.assertEqual('refuse', pl.plan_transition(2, 1, False))
        self.assertEqual('refuse', pl.plan_transition(3, 2, False))
        self.assertEqual('refuse', pl.plan_transition(None, 2, False))


class GuardTest(unittest.TestCase):

    def test_disposable_stack_passes(self):
        self.assertEqual([], pl.guard_problems(GOOD_ENV, LABELS, IDS, 'sha256:aa'))

    def test_default_project_and_local_image_refused(self):
        problems = pl.guard_problems({}, LABELS, IDS, 'sha256:aa')
        self.assertEqual(2, len(problems))
        problems = pl.guard_problems({'CHAOS_PROJECT': 'exchange-chaos', 'CHAOS_APP_IMAGE': 'exchange-chaos-app:local'},
                                     LABELS, IDS, 'sha256:aa')
        self.assertEqual(2, len(problems))

    def test_preserved_stack_containers_refused(self):
        labels = dict(LABELS, **{'chaos-pg': 'exchange-chaos'})
        problems = pl.guard_problems(GOOD_ENV, labels, IDS, 'sha256:aa')
        self.assertEqual(1, len(problems))
        self.assertIn('chaos-pg', problems[0])

    def test_missing_pg_and_other_image_refused(self):
        labels = dict(LABELS, **{'chaos-pg': None})
        ids = dict(IDS, **{'chaos-api-2': 'sha256:bb'})
        problems = pl.guard_problems(GOOD_ENV, labels, ids, 'sha256:aa')
        self.assertEqual(2, len(problems))
        # 태그가 없으면 떠 있는 컨테이너 셋의 이미지도 그 id 와 같다고 볼 수 없다
        problems = pl.guard_problems(GOOD_ENV, LABELS, IDS, None)
        self.assertEqual(4, len(problems))
        self.assertTrue(any('이미지가 없다' in p for p in problems))


class PartitionPlanTest(unittest.TestCase):
    """S7 설계 4-4·6-3. 새 전이 표와 래치·사전 확인 판정."""

    def test_steps_table(self):
        self.assertEqual([3], pl.plan_steps(2, 3, False))
        self.assertEqual([2, 3], pl.plan_steps(1, 3, False), '1->3 은 1->2 를 먼저 부르는 두 단계다')
        self.assertEqual([2], pl.plan_steps(3, 2, True))
        self.assertIsNone(pl.plan_steps(3, 2, False), '3->2 는 확인 인자가 있어야 한다')
        self.assertIsNone(pl.plan_steps(3, 1, True), '3->1 은 받지 않는다(먼저 3->2)')
        self.assertEqual([], pl.plan_steps(3, 3, False))
        self.assertEqual('call', pl.plan_transition(2, 3, False))
        self.assertEqual('call', pl.plan_transition(3, 2, True))
        self.assertEqual('refuse', pl.plan_transition(3, 1, True))

    def test_latch_decision(self):
        self.assertEqual('present', pl.latch_decision(True, False))
        self.assertEqual('latch', pl.latch_decision(False, True))
        self.assertEqual('blocked', pl.latch_decision(False, False), '래치가 없고 --latch-band 도 없으면 BLOCKED')

    def gauges(self, max1=3.0, max2=3.0):
        return {'chaos-api-1': {'ok': True, 'values': {'exchange_leader_protocol_max': max1}},
                'chaos-api-2': {'ok': True, 'values': {'exchange_leader_protocol_max': max2}}}

    ENV = {'chaos-api-1': 'matching-p0', 'chaos-api-2': 'matching-p1'}

    def test_precheck(self):
        allowed = [{'blocker': 'unsettled_new', 'detail': '3건'}, {'blocker': 'band_latch_missing', 'detail': '-'}]
        self.assertEqual([], pl.precheck_problems(self.gauges(), allowed, self.ENV))
        self.assertEqual(1, len(pl.precheck_problems(self.gauges(max2=2.0), [], self.ENV)),
                         'S6 이전 바이너리(최고 모드 2)가 떠 있으면 막는다')
        self.assertEqual(1, len(pl.precheck_problems(self.gauges(), [], dict(self.ENV, **{'chaos-api-2': None}))),
                         'P3 오버레이 없이 뜬 노드를 막는다')
        blocked = pl.precheck_problems(self.gauges(), [{'blocker': 'fee_shard_missing', 'detail': '자산 5'}], self.ENV)
        self.assertEqual(1, len(blocked))
        self.assertIn('fee_shard_missing', blocked[0])
        self.assertEqual(1, len(pl.precheck_problems(self.gauges(), None, self.ENV)))
        unread = dict(self.gauges(), **{'chaos-api-1': {'ok': False, 'error': 'URLError'}})
        self.assertEqual(1, len(pl.precheck_problems(unread, [], self.ENV)), '게이지를 읽지 못하면 3 으로 세지 않는다')


def partitions(p0_node, p1_node):
    out = {}
    for node in ('chaos-api-1', 'chaos-api-2'):
        out[node] = {'partitions': [
            {'resource': 'matching-p0', 'state': 'ACCEPTING' if node == p0_node else 'FOLLOWER', 'epoch': 20},
            {'resource': 'matching-p1', 'state': 'ACCEPTING' if node == p1_node else 'FOLLOWER', 'epoch': 21}]}
    return out


class FakeOps:
    """정지 창의 부작용을 기록하는 가짜. 전환 함수는 호출마다 세 행을 퇴역시킨다."""

    def __init__(self, protocol=2, latched=True, holders=('chaos-api-1', 'chaos-api-2')):
        self.log = []
        self.protocol = protocol
        self.latched = latched
        self.holders = list(holders)
        self.epoch = 10
        self.participation = {}

    def guard(self):
        return {'ok': True, 'problems': []}

    def state(self):
        leases = [{'resource': r, 'epoch': self.epoch + 5} for r in ('matching-engine', 'matching-p0', 'matching-p1')]
        return {'ok': True, 'protocol': self.protocol, 'latched': self.latched, 'lease': leases,
                'history': [{'protocol': self.protocol}]}

    def blockers(self, target):
        return [] if self.latched else [{'blocker': 'band_latch_missing', 'detail': '-'}]

    def gauges(self, port):
        return {'ok': True, 'values': {'exchange_leader_protocol_max': 3.0,
                                       'exchange_leader_protocol_boot': float(self.protocol),
                                       'exchange_order_inflight_enabled': 0.0}}

    def preferred_env(self, name):
        return pl.PREFERRED[name]

    def running(self, name):
        return True

    def docker(self, args):
        self.log.append(' '.join(args))
        return 0

    def drain(self):
        self.log.append('drain')
        return {'ok': True}

    def latch(self):
        self.log.append('latch')
        self.latched = True
        return {'ok': True}

    def call(self, target, allow_downgrade):
        self.log.append('call %d %s' % (target, allow_downgrade))
        self.protocol = target
        rows = []
        for r in ('matching-p0', 'matching-p1', 'matching-engine'):
            self.epoch += 1
            rows.append({'resource': r, 'retired_epoch': self.epoch, 'protocol': target})
        return {'rc': 0, 'rows': rows}

    def segment(self):
        self.log.append('segment')
        return {'ok': True, 'rows': [{'id': 2, 'old_rows': 3}]}

    def partitions(self):
        p0, p1 = self.holders
        if self.participation.get((p1, 'matching-p1')) is False:
            p1 = 'chaos-api-2' if p1 == 'chaos-api-1' else 'chaos-api-1'
            self.holders[1] = p1
        return partitions(p0, p1)

    def set_participate(self, node, resource, value):
        self.log.append('participate %s %s %s' % (node, resource, value))
        self.participation[(node, resource)] = value
        return '200'

    def leader(self, resource):
        return {'alive': True, 'epoch': self.epoch + 5}

    def http_ok(self, port, path):
        return True

    def c29(self):
        return 0

    def sleep(self, seconds):
        self.log.append('sleep')


class PartitionTransitionOrderTest(unittest.TestCase):
    """정지 창의 순서(S7 설계 4-4·4-5, 6-3). 구간 경계 표본은 전환 함수 직후·노드 시작 전이다."""

    def before_starts(self, log, item):
        first_start = min(i for i, x in enumerate(log) if x.startswith('start chaos-api'))
        return log.index(item) < first_start

    def test_up_from_p1_calls_two_steps_latches_between_and_samples_before_start(self):
        ops = FakeOps(protocol=1, latched=False)
        out = pl.transition_partition(3, latch_band=True, ops=ops)
        self.assertTrue(out['ok'], out.get('error'))
        log = [x for x in ops.log if x != 'sleep']
        self.assertEqual(['stop chaos-bots', 'drain', 'stop chaos-api-2', 'stop chaos-api-1', 'call 2 False', 'latch',
                          'call 3 False', 'segment', 'start chaos-api-1', 'start chaos-api-2', 'start chaos-bots'], log)
        self.assertTrue(self.before_starts(log, 'segment'))

    def test_missing_latch_without_flag_is_blocked_before_the_window(self):
        ops = FakeOps(protocol=2, latched=False)
        out = pl.transition_partition(3, ops=ops)
        self.assertEqual(pl.EXIT_BLOCKED, out['code'])
        self.assertEqual([], ops.log, '정지 창에 들어가지 않았어야 한다')

    def test_one_node_holding_both_is_relocated(self):
        ops = FakeOps(protocol=2, holders=('chaos-api-1', 'chaos-api-1'))
        out = pl.transition_partition(3, ops=ops)
        self.assertTrue(out['ok'], out.get('error'))
        self.assertEqual('relocate', out['assignment_first']['state'])
        moves = [x for x in ops.log if x.startswith('participate')]
        self.assertEqual(['participate chaos-api-1 matching-p1 False', 'participate chaos-api-1 matching-p1 True'],
                         moves, '비선호 노드의 참여를 끄고, 선호 노드가 얻은 뒤 다시 켠다')
        self.assertEqual('ok', out['assignment'])

    def test_rollback_samples_then_starts_api1_before_api2(self):
        ops = FakeOps(protocol=3)
        out = pl.transition_partition(2, allow_downgrade=True, ops=ops)
        self.assertTrue(out['ok'], out.get('error'))
        log = [x for x in ops.log if x != 'sleep']
        self.assertEqual(['stop chaos-bots', 'drain', 'stop chaos-api-2', 'stop chaos-api-1', 'call 2 True', 'segment',
                          'start chaos-api-1', 'start chaos-api-2', 'start chaos-bots'], log)
        self.assertFalse(any(x.startswith('latch') for x in log), '되돌리기는 래치를 건드리지 않는다')

    def test_rollback_without_confirmation_is_refused(self):
        ops = FakeOps(protocol=3)
        out = pl.transition_partition(2, allow_downgrade=False, ops=ops)
        self.assertEqual(pl.EXIT_MODE, out['code'])
        self.assertEqual([], ops.log)


if __name__ == '__main__':
    unittest.main()
