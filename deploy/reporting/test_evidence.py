import json
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch
import urllib.parse

import evidence


CONFIG = json.loads(Path(__file__).with_name("config.example.json").read_text(encoding="utf-8"))


class EvidenceSemanticsTest(unittest.TestCase):
    def test_customer_alert_filters_reach_alertmanager_as_separate_matchers(self):
        config = {**CONFIG, "queries": [], "evidence_root": None,
                  "alert_matchers": ['customer="exchange"', 'component!="reporting"', 'role!="bot"']}
        with patch.object(evidence, "json_request", return_value=[]) as request:
            result = evidence.collect(config, 100000)
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(request.call_args.args[0]).query)
        self.assertEqual(query["filter"], config["alert_matchers"])
        self.assertEqual(result["facts"][0]["status"], "ok")

    def test_database_identity_and_time_coverage_survive_summary(self):
        query = next(item for item in CONFIG["queries"] if item["id"] == "db_connections")
        response = {"status": "success", "data": {"result": [
            {"metric": {"instance": "primary:9187", "datname": "exchange"},
             "values": [[1000, "8"], [1060, "9"]]},
            {"metric": {"instance": "standby:9187", "datname": "exchange"},
             "values": [[1000, "2"], [1060, "2"]]}
        ]}}
        with patch.object(evidence, "json_request", return_value=response):
            result = evidence.metric_fact("http://prometheus", query, 1120, 900, 5)
        self.assertEqual(len(result["samples"]), 2)
        self.assertEqual([sample["last"] for sample in result["samples"]], [9, 2])
        self.assertEqual([sample["labels"]["datname"] for sample in result["samples"]], ["exchange", "exchange"])
        self.assertEqual(result["samples"][0]["count"], 2)
        self.assertEqual(result["samples"][0]["last_observed_at"], 1060)
        self.assertEqual(result["samples"][0]["points"], [[1000, 8], [1060, 9]])
        self.assertEqual(result["source"]["window_end"], 1120)
        self.assertEqual(result["source"]["unit"], "connections")

    def test_missing_histogram_has_reason_and_never_becomes_zero(self):
        query = next(item for item in CONFIG["queries"] if item["id"] == "latency_p95")
        with patch.object(evidence, "json_request", return_value={"status": "success", "data": {"result": []}}):
            result = evidence.metric_fact("http://prometheus", query, 1000, 900, 5)
        self.assertEqual(result["status"], "missing")
        self.assertEqual(result["samples"], [])
        self.assertIn("histogram", result["missing_reason"])
        self.assertEqual(result["source"]["aggregation"], "histogram_quantile_across_api_instances")

    def test_host_identity_survives_ip_redaction_without_extra_labels(self):
        query = next(item for item in CONFIG["queries"] if item["id"] == "target_up")
        response = {"status": "success", "data": {"result": [
            {"metric": {"instance": f"10.20.0.{index}:9100", "node": f"i-example-{index}",
                        "role": "read", "service": "node-exporter", "customer": "person@example.test",
                        "api_key": "never-preserved"}, "values": [[1000, "1"]]}
            for index in (1, 2)
        ]}}
        with patch.object(evidence, "json_request", return_value=response):
            result = evidence.metric_fact("http://prometheus", query, 1000, 900, 5)
        labels = [sample["labels"] for sample in result["samples"]]
        self.assertEqual([label["node"] for label in labels], ["i-example-1", "i-example-2"])
        self.assertTrue(all(label["instance"] == "[IP REDACTED]:9100" for label in labels))
        self.assertTrue(all(label["role"] == "read" and label["service"] == "node-exporter" for label in labels))
        self.assertTrue(all("customer" not in label and "api_key" not in label for label in labels))

    def test_incident_uses_fifteen_minutes_and_daily_keeps_twenty_four_hours(self):
        config = {**CONFIG, "queries": [CONFIG["queries"][0]], "evidence_root": None}
        requested = []

        def response(url, **unused):
            if "/api/v2/alerts" in url:
                return []
            requested.append(urllib.parse.parse_qs(urllib.parse.urlsplit(url).query))
            return {"status": "success", "data": {"result": []}}

        incident = [{"status": "resolved", "labels": {"alertname": "Example"}}]
        with patch.object(evidence, "json_request", side_effect=response):
            daily = evidence.collect(config, 100000)
            current = evidence.collect(config, 100000, incident)
        self.assertEqual(daily["window_seconds"], 86400)
        self.assertEqual(current["window_seconds"], 900)
        self.assertEqual([query["start"] for query in requested], [["13600"], ["99100"]])
        self.assertEqual([query["step"] for query in requested], [["720"], ["60"]])
        trigger = next(fact for fact in current["facts"] if fact["id"] == "trigger")
        self.assertEqual(trigger["records"][0]["status"], "resolved")

    def test_invalid_incident_window_is_rejected_before_network_access(self):
        for window in (0, 90000, True, "900"):
            with self.subTest(window=window), patch.object(evidence, "json_request") as request:
                with self.assertRaises(ValueError):
                    evidence.collect({**CONFIG, "incident_window_seconds": window}, 100000, [])
                request.assert_not_called()

    def test_chart_points_preserve_full_day_and_reject_ambiguous_order(self):
        query = CONFIG["queries"][0]
        points = [[1000 + index * 720, str(index % 2)] for index in range(121)]
        response = {"status": "success", "data": {"result": [{"metric": {}, "values": points}]}}
        with patch.object(evidence, "json_request", return_value=response):
            fact = evidence.metric_fact("http://prometheus", query, 87400, 86400, 5)
        self.assertEqual(len(fact["samples"][0]["points"]), 121)
        self.assertEqual(fact["samples"][0]["points"][-1], [87400, 0])
        for values in ([[1000, "1"], [1000, "0"]], [[1060, "1"], [1000, "0"]]):
            response["data"]["result"][0]["values"] = values
            with patch.object(evidence, "json_request", return_value=response):
                fact = evidence.metric_fact("http://prometheus", query, 1120, 900, 5)
            self.assertEqual(fact["status"], "error")
            self.assertEqual(fact["samples"], [])

    def test_reviewed_backup_evidence_only_accepts_explicit_boolean_and_allowed_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "review.json"
            records = {"enabled": True, "checked_at": "2026-09-18T02:42:00Z",
                       "next_run_at": "2026-09-19T00:00:00Z", "secret": "excluded"}
            path.write_text(json.dumps({"schema_version": 1, "records": records}), encoding="utf-8")
            fact = evidence.file_evidence(directory, "review.json", "backup_schedule")
            self.assertEqual(fact["status"], "ok")
            self.assertNotIn("secret", fact["records"])
            records["enabled"] = "true"
            path.write_text(json.dumps({"schema_version": 1, "records": records}), encoding="utf-8")
            self.assertEqual(evidence.file_evidence(directory, "review.json", "backup_schedule")["status"], "error")
            self.assertEqual(evidence.file_evidence(directory, "../outside.json", "backup_restore")["status"], "error")
        self.assertEqual(evidence.file_evidence(None, None, "backup_restore")["status"], "missing")


if __name__ == "__main__":
    unittest.main()
