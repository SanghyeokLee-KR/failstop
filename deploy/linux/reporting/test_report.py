import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import report


class ReportTest(unittest.TestCase):
    def snapshot(self):
        values = {key: 10 for key in report.QUERIES}
        values.update(up=1, collectors_ok=1, sample_age_seconds=1)
        return {"scope": ["bot-1"], "metrics": {"bot-1": values},
                "collection_errors": [], "at": "2026-09-19T00:00:00Z", "issues": []}

    def test_missing_cpu_is_not_healthy_zero(self):
        snapshot = self.snapshot()
        del snapshot["metrics"]["bot-1"]["cpu_percent"]
        self.assertIn("bot-1:missing:cpu_percent", report.evaluate(snapshot))

    def test_stale_and_failed_collector_are_separate_from_up(self):
        snapshot = self.snapshot()
        snapshot["metrics"]["bot-1"].update(sample_age_seconds=120, collectors_ok=0)
        self.assertEqual(["bot-1:collector_failed", "bot-1:stale_metrics"], report.evaluate(snapshot))

    def test_required_service_missing_and_inactive_are_not_normal(self):
        snapshot = self.snapshot()
        snapshot["required_services"] = {"bot-1": ["grafana-server.service", "sshd.service"]}
        snapshot["services"] = {"bot-1": {"sshd.service": 0}}
        self.assertEqual(["bot-1:missing_service:grafana-server.service", "bot-1:service_inactive:sshd.service"],
                         report.evaluate(snapshot))

    def test_collector_failure_keeps_scope_and_missing_metrics(self):
        with patch.object(report, "request", side_effect=report.RequestFailure("network_error")):
            snapshot = report.collect({"prometheus_url": "http://127.0.0.1:9090", "nodes": ["bot-1"]})
        self.assertEqual({}, snapshot["metrics"]["bot-1"])
        self.assertEqual(len(report.QUERIES), len(snapshot["issues"]))

    def test_model_opinion_does_not_replace_deterministic_findings(self):
        snapshot = self.snapshot()
        snapshot["issues"] = ["bot-1:scrape_failed"]
        payload = report.card(snapshot, "모든 서비스 정상", "test")
        self.assertIn("bot-1:scrape_failed", json.dumps(payload))

    def test_unknown_delivery_blocks_duplicate_send(self):
        with tempfile.TemporaryDirectory() as directory:
            state_path = Path(directory) / "state.json"
            state = {}
            with patch.object(report, "request", side_effect=report.RequestFailure("network_error")) as send:
                with self.assertRaises(report.RequestFailure):
                    report.deliver({}, "https://test.logic.azure.com/workflow", state, state_path, "daily-1", "f")
                restored = json.loads(state_path.read_text())
                with self.assertRaisesRegex(report.RequestFailure, "needs_review"):
                    report.deliver({}, "https://test.logic.azure.com/workflow", restored, state_path, "daily-1", "f")
                self.assertEqual(1, send.call_count)

    def test_incident_does_not_erase_daily_deduplication(self):
        with tempfile.TemporaryDirectory() as directory:
            state = {}
            with patch.object(report, "request", return_value=(202, b"")):
                for report_id in ("daily-2026-09-19", "incident-1"):
                    report.deliver({}, "https://test.logic.azure.com/workflow", state,
                                   Path(directory) / "state.json", report_id, "f")
            self.assertEqual("daily-2026-09-19", state["last_daily_id"])

    def test_nonfinite_and_duplicate_metrics_are_missing(self):
        for rows in ([{"metric": {"node": "bot-1"}, "value": [1, "NaN"]}],
                     [{"metric": {"node": "bot-1"}, "value": [1, "1"]}] * 2):
            response = {"status": "success", "data": {"resultType": "vector", "result": rows}}
            with patch.object(report, "request", return_value=(200, json.dumps(response).encode())):
                snapshot = report.collect({"prometheus_url": "http://127.0.0.1:9090", "nodes": ["bot-1"]})
            self.assertEqual({}, snapshot["metrics"]["bot-1"])

    def test_disallow_unexpected_webhook_host(self):
        for url in ("http://test.logic.azure.com/x", "https://logic.azure.com.evil.example/x",
                    "https://user:pass@test.logic.azure.com/x"):
            with self.assertRaises(ValueError):
                report.validate_webhook(url)

    def test_database_query_failure_is_not_hidden_by_healthy_host(self):
        config = {"prometheus_url": "http://127.0.0.1:9090", "nodes": ["db-primary"],
                  "db_nodes": {"db-primary": "primary"}}

        def response(url):
            query = report.urllib.parse.parse_qs(report.urllib.parse.urlsplit(url).query)["query"][0]
            if query in report.DB_QUERIES.values():
                raise report.RequestFailure("network_error")
            rows = [{"metric": {"node": "db-primary"}, "value": [1, "1"]}]
            return 200, json.dumps({"status": "success", "data": {"resultType": "vector", "result": rows}}).encode()

        with patch.object(report, "request", side_effect=response):
            snapshot = report.collect(config)
        self.assertEqual(1, snapshot["metrics"]["db-primary"]["up"])
        self.assertIn("db-primary:missing:db_up", snapshot["issues"])
        self.assertNotIn("db_up", snapshot["metrics"]["db-primary"])

    def test_wrong_database_role_and_disk_warning_are_reported(self):
        snapshot = self.snapshot()
        snapshot["db_nodes"] = {"bot-1": "standby"}
        snapshot["metrics"]["bot-1"].update(in_recovery=0, disk_percent=75)
        self.assertEqual(["bot-1:unexpected_db_role", "bot-1:warning:disk_percent"], report.evaluate(snapshot))
        snapshot["metrics"]["bot-1"]["disk_percent"] = 96
        self.assertIn("bot-1:critical:disk_percent", report.evaluate(snapshot))

    def test_database_scope_cannot_add_an_unapproved_node(self):
        config = {"prometheus_url": "http://127.0.0.1:9090", "nodes": ["bot-1"],
                  "db_nodes": {"db-primary": "primary"}}
        with patch.object(report, "request") as fetch:
            with self.assertRaisesRegex(ValueError, "scope"):
                report.collect(config)
            fetch.assert_not_called()

    def test_extra_nodes_and_database_fields_do_not_enter_bot_only_report(self):
        rows = [{"metric": {"node": node}, "value": [1, "1"]} for node in ("bot-1", "db-primary")]
        response = {"status": "success", "data": {"resultType": "vector", "result": rows}}
        with patch.object(report, "request", return_value=(200, json.dumps(response).encode())):
            snapshot = report.collect({"prometheus_url": "http://127.0.0.1:9090", "nodes": ["bot-1"]})
        self.assertEqual(["bot-1"], list(snapshot["metrics"]))
        self.assertNotIn("db_up", snapshot["metrics"]["bot-1"])


if __name__ == "__main__":
    unittest.main()
