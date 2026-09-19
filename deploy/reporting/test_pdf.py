import importlib.util
import datetime as dt
import json
from pathlib import Path
import re
import tempfile
import unittest

from render_pdf import build_bindings, render_pdf
from test_presentation import event, metric, report


FONT = next((path for path in (Path("C:/Windows/Fonts/malgun.ttf"),
                              Path("/usr/share/fonts/truetype/nanum/NanumGothic.ttf")) if path.is_file()), None)


class PdfBindingTests(unittest.TestCase):
    def test_daily_scope_comes_from_report_not_an_incident_trigger(self):
        saved = report()
        saved["kind"] = "daily"
        saved["evidence"]["scope"] = {"customer": "exchange", "environment": "aws"}
        saved["evidence"]["facts"] = [metric("target_up", 1)]
        binding = build_bindings(saved)
        self.assertEqual(binding["document"]["environment"], "AWS")
        self.assertEqual(binding["document"]["customer"], "exchange")
        self.assertEqual(binding["decision"]["code"], "verify")
        self.assertEqual(binding["template"]["pages"], 2)

    @unittest.skipUnless(FONT and importlib.util.find_spec("reportlab"), "PDF runtime and Korean font required")
    def test_maximum_metric_form_renders_two_pages_without_clipped_fields(self):
        incident = event("DatabaseConnectionFailed", "firing", "db-standby-1")
        incident["labels"].update(role="standby", customer="exchange", environment="aws")
        saved = report([incident])
        saved["evidence"]["facts"] += [metric(name, value) for name, value in (
            ("target_up", 1), ("inode_free", .8), ("io_time", .1), ("network_errors", 0),
            ("db_connections", 11), ("db_replication_lag", 0), ("request_rate", 23.0), ("latency_p95", .012))]
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "report.pdf"
            binding = Path(directory) / "binding.json"
            before = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
            render_pdf(saved, output, FONT, bindings_output=binding)
            after = dt.datetime.now(dt.timezone.utc)
            data = output.read_bytes()
            self.assertEqual(len(re.findall(rb"/Type\s*/Page\b", data)), 2)
            raw_date = re.search(rb"/CreationDate\s*\((D:[0-9]{14}Z)\)", data)
            self.assertIsNotNone(raw_date, "PDF creation time must use an explicit UTC suffix")
            created = dt.datetime.strptime(raw_date.group(1).decode(), "D:%Y%m%d%H%M%SZ").replace(tzinfo=dt.timezone.utc)
            self.assertLessEqual(before, created)
            self.assertLessEqual(created, after)
            result = json.loads(binding.read_text(encoding="utf-8"))
            self.assertEqual(result["rendering"]["truncated_fields"], [])
            self.assertEqual(result["document"]["title"], "Standby DB 접속 장애 발생")


if __name__ == "__main__":
    unittest.main()
