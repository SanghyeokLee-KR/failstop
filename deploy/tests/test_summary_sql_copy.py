"""VM 실습망 summary.sql 사본이 하네스 원본과 같은 바이트인지 본다(ADR 0034 S7, S7 설계 4-14·6-3).

VM 실습망의 복원 점검(db02-restore-check.sh)과 DB 승격 검사(31-db-standby-setup.sh, 32-db-failover.sh)는 사본을 읽는다. 원본만
고치면 C30 허용 목록을 고친 셸이 C22'·C30 없는 사본을 돈다. 서버 시험 HarnessSqlCopyTest 가 같은 대조를 Gradle 빌드에서 하고,
이 시험은 서버 빌드 없이 배포 시험 묶음에서 한다.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).parents[2]
ORIGINAL = ROOT / "harness" / "sql" / "summary.sql"
COPY = ROOT / "deploy" / "vm-lab" / "build" / "build-files" / "db" / "summary.sql"


class SummarySqlCopyTest(unittest.TestCase):
    def test_vm_lab_copy_is_byte_identical(self):
        self.assertTrue(ORIGINAL.is_file(), ORIGINAL)
        self.assertTrue(COPY.is_file(), COPY)
        self.assertEqual(ORIGINAL.read_bytes(), COPY.read_bytes(),
                         "deploy/vm-lab 사본이 harness/sql/summary.sql 과 다르다. 원본을 고쳤으면 사본도 같은 바이트로 맞춘다")

    def test_copy_carries_the_s7_rows(self):
        text = COPY.read_text(encoding="utf-8")
        # C30 은 평가 판과 N/A·FAIL 판 두 곳, N/A 표시 한 곳에 나온다. C22 는 C22' 판과 개정 전 판 두 곳이다.
        self.assertEqual(2, text.count("SELECT 'C30','FENCING_RESOURCE_MATCH'"))
        self.assertIn("UNION ALL SELECT 'C30' WHERE :v48_objects = 0", text)
        self.assertEqual(2, text.count("SELECT 'C22','FENCING_MONOTONIC'"))


if __name__ == "__main__":
    unittest.main()
