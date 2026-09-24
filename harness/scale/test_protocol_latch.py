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


if __name__ == '__main__':
    unittest.main()
