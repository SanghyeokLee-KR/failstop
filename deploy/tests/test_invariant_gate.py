"""복원·복구 점검 넷의 summary.sql 판정(하네스 검증설계 §4-1 '판정 어휘', ADR 0034 S3).

네 파일은 따로 배포되는 스크립트라 판정 함수를 같은 이름(judge_invariant_rows)으로 각자 둔다. 이 시험은 넷이 같은 픽스처에서
같은 결과를 내는지 본다. N/A 는 C29·C30·C31 만 낼 수 있고 통과로도 실패로도 세지 않으며 not_applicable 에 따로 적는다.
C30 은 ADR 0034 S7 이 더했다. CRITICAL·HIGH 행의 FAIL·SKIP 은 실패다. C29·C30·C31 밖의 N/A 와 모르는 verdict 는 형식 오류다.
VM 실습망 셸 둘의 내장 파이썬은 VM 을 다시 쓸 때 실행 시험을 한다(ADR 0029 보류). 두 셸의 허용 코드는 원문 대조로 본다.
"""
import importlib.util
from pathlib import Path
import sys
import types
import unittest

DEPLOY = Path(__file__).parents[1]


def load(name, relative):
    # backup-restore-check.py 는 리눅스 전용 pwd 를 가져온다. 판정 함수만 부르므로 빈 모듈로 채운다.
    sys.modules.setdefault("pwd", types.ModuleType("pwd"))
    spec = importlib.util.spec_from_file_location(name, DEPLOY / relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


CONSUMERS = [
    load("backup_gate", "backup/backup.py"),
    load("restore_check_gate", "linux/postgresql/backup-restore-check.py"),
    load("recovery_gate", "linux/postgresql/verify-db-recovery.py"),
    load("lab_gate", "lab/run.py"),
]


def rows(overrides):
    """C01~C28 과 C29·C30·C31 을 PASS 로 둔 카탈로그에 덮어쓸 행을 얹는다."""
    base = {}
    for n in range(1, 29):
        code = "C%02d" % n
        base[code] = {"code": code, "severity": "CRITICAL", "verdict": "PASS"}
    base["C15"] = {"code": "C15", "severity": "WARN", "verdict": "PASS"}
    base["C29"] = {"code": "C29", "severity": "CRITICAL", "verdict": "PASS"}
    base["C30"] = {"code": "C30", "severity": "CRITICAL", "verdict": "PASS"}
    base["C31"] = {"code": "C31", "severity": "CRITICAL", "verdict": "PASS"}
    base["C24"] = {"code": "C24", "severity": "WARN", "verdict": "SKIP"}
    for row in overrides:
        base[row["code"]] = row
    return list(base.values())


class InvariantGateTest(unittest.TestCase):
    def judge_all(self, catalogue):
        results = [consumer.judge_invariant_rows(catalogue) for consumer in CONSUMERS]
        for result in results[1:]:
            self.assertEqual(results[0], result)
        return results[0]

    def test_not_applicable_c29_c30_c31_pass_but_are_listed(self):
        result = self.judge_all(rows([{"code": "C29", "severity": "CRITICAL", "verdict": "N/A"},
                                      {"code": "C30", "severity": "CRITICAL", "verdict": "N/A"},
                                      {"code": "C31", "severity": "CRITICAL", "verdict": "N/A"}]))
        self.assertEqual([], result["failed"])
        self.assertEqual(["C29", "C30", "C31"], result["not_applicable"])

    def test_c30_fail_and_partial_schema_fail(self):
        # V48 객체가 일부만 있는 스키마에서 summary.sql 은 C29·C30 을 FAIL 로 낸다(violated_rows 는 없는 객체 수).
        result = self.judge_all(rows([{"code": "C29", "severity": "CRITICAL", "verdict": "FAIL"},
                                      {"code": "C30", "severity": "CRITICAL", "verdict": "FAIL"}]))
        self.assertEqual(["C29", "C30"], result["failed"])
        self.assertEqual([], result["not_applicable"])

    def test_fail_and_skip_of_required_rows_fail(self):
        result = self.judge_all(rows([{"code": "C14", "severity": "HIGH", "verdict": "FAIL"},
                                      {"code": "C31", "severity": "CRITICAL", "verdict": "SKIP"},
                                      {"code": "C29", "severity": "CRITICAL", "verdict": "N/A"}]))
        self.assertEqual(["C14", "C31"], result["failed"])
        self.assertEqual(["C29"], result["not_applicable"])

    def test_warn_skip_does_not_fail(self):
        result = self.judge_all(rows([{"code": "C25", "severity": "WARN", "verdict": "SKIP"}]))
        self.assertEqual([], result["failed"])

    def test_not_applicable_outside_c29_c30_c31_is_a_format_error(self):
        for consumer in CONSUMERS:
            for code in ("C05", "C22", "C28"):
                with self.assertRaisesRegex(ValueError, "N/A"):
                    consumer.judge_invariant_rows(rows([{"code": code, "severity": "CRITICAL", "verdict": "N/A"}]))

    def test_vm_lab_shell_gates_allow_the_same_codes(self):
        # VM 실습망 셸 둘의 내장 파이썬은 실행하지 않는다(ADR 0029 보류). 허용 코드 줄이 파이썬 소비자와 같은지만 원문으로 본다.
        expected = "and r.get('code') not in ('C29', 'C30', 'C31')]"
        for relative in ("vm-lab/build/build-files/db/db02-restore-check.sh", "vm-lab/build/scripts/32-db-failover.sh"):
            text = (DEPLOY / relative).read_text(encoding="utf-8")
            self.assertIn(expected, text, relative)
            self.assertNotIn("not in ('C29', 'C31')", text, relative)
        for consumer in CONSUMERS:
            self.assertEqual(("C29", "C30", "C31"), consumer.NOT_APPLICABLE_CODES)

    def test_unknown_verdict_and_short_catalogue_are_format_errors(self):
        for consumer in CONSUMERS:
            with self.assertRaises(ValueError):
                consumer.judge_invariant_rows(rows([{"code": "C01", "severity": "CRITICAL", "verdict": "OK"}]))
            with self.assertRaises(ValueError):
                consumer.judge_invariant_rows(rows([])[:10])


if __name__ == "__main__":
    unittest.main()
