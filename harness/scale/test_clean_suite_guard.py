"""clean_suite 의 보존 스택 가드 시험. 도커와 DB 없이 돈다.

칸마다 down -v 를 하는 러너(clean_suite, roundtrip_probe, reject_reasons)가 공유하는 판정이다(하네스 검증설계 §2-4 보존 스택
가드, 마켓분할_리더_S3_설계 7-0). 보존 스택(CHAOS_PROJECT 미설정 또는 exchange-chaos)은 늘 거부하고, 이미지 태그는 ADR 0034
S3 모드 전환 경로에서만 묶는다. ADR 0022 단계 절차가 자기 태그로 도는 경우가 막히지 않는지도 본다(S3 구현 검토).

돌리는 법: python -B -m unittest harness/scale/test_clean_suite_guard.py
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import clean_suite as cs  # noqa: E402

STAGE7_TAG = 'exchange-chaos-app:adr0022-stage7-abc1234'


class PreservedStackGuardTest(unittest.TestCase):

    def test_unset_and_preserved_project_are_refused(self):
        for env in ({}, {'CHAOS_PROJECT': ''}, {'CHAOS_PROJECT': 'exchange-chaos'},
                    {'CHAOS_PROJECT': 'exchange-chaos', 'CHAOS_APP_IMAGE': 'exchange-chaos-app:pml'}):
            problems = cs.disposable_problems(env)
            self.assertEqual(1, len(problems), env)
            self.assertIn('exchange-chaos', problems[0])

    def test_adr0022_procedures_run_with_their_own_tag_on_a_disposable_project(self):
        # 7단계 절차는 -Ref 로 구운 자기 태그를 넘긴다. 4·6·8단계는 CHAOS_APP_IMAGE 를 자기 태그로 둔다.
        for image in (STAGE7_TAG, 'exchange-chaos-app:adr0022-stage6-1234567', None):
            env = {'CHAOS_PROJECT': 'exchange-pml'}
            if image:
                env['CHAOS_APP_IMAGE'] = image
            self.assertEqual([], cs.disposable_problems(env), env)
        self.assertEqual([], cs.disposable_problems({'CHAOS_PROJECT': 'exchange-adr0022', 'CHAOS_APP_IMAGE': STAGE7_TAG}))

    def test_protocol_path_needs_the_s3_project_and_tag(self):
        good = {'CHAOS_PROJECT': 'exchange-pml', 'CHAOS_APP_IMAGE': 'exchange-chaos-app:pml'}
        self.assertEqual([], cs.disposable_problems(good, protocol=True))
        self.assertEqual(1, len(cs.disposable_problems(dict(good, CHAOS_APP_IMAGE=STAGE7_TAG), protocol=True)))
        self.assertEqual(1, len(cs.disposable_problems(dict(good, CHAOS_PROJECT='exchange-adr0022'), protocol=True)))
        # 보존 스택은 한 번만 적는다. 이미지가 맞아도 거부다.
        problems = cs.disposable_problems({'CHAOS_APP_IMAGE': 'exchange-chaos-app:pml'}, protocol=True)
        self.assertEqual(1, len(problems))
        self.assertIn('exchange-chaos', problems[0])


if __name__ == '__main__':
    unittest.main()
