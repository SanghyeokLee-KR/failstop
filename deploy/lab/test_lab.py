"""Negative evidence must not be accepted as a successful recovery drill."""
import csv
import io
import unittest
from unittest.mock import patch
import run as lab


class EvidenceTests(unittest.TestCase):
    def test_empty_transactions_fail(self):
        for field in ("commands", "events", "trades", "ledger_entries", "orders"):
            values = dict.fromkeys(("commands", "events", "trades", "ledger_entries", "orders"), 1)
            values[field] = 0
            with self.assertRaises(RuntimeError):
                lab.assert_positive(values)

    def catalogue(self, verdict="PASS", severity="HIGH", count=28):
        output = io.StringIO()
        writer = csv.writer(output)
        writer.writerow(("code", "severity", "verdict"))
        for index in range(count):
            writer.writerow((f"C{index:02}", severity, verdict if index == 0 else "PASS"))
        return output.getvalue()

    def test_missing_catalogue_and_high_failure_are_rejected(self):
        for output in ("", self.catalogue(count=27), self.catalogue("FAIL")):
            with patch.object(lab, "compose", return_value=output), self.assertRaises(RuntimeError):
                lab.invariant_summary("restored")

    def test_critical_missing_evidence_is_rejected(self):
        with patch.object(lab, "compose", return_value=self.catalogue("NO_DATA", "CRITICAL")):
            with self.assertRaises(RuntimeError):
                lab.invariant_summary("restored")


if __name__ == "__main__":
    unittest.main()
