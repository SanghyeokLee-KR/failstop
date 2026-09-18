import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import artifacts
from console_api import LINK_TTL_SECONDS, list_reports, parse_query
from reporter import ReportServer, Store

QUERY = "/console/reports?customer=exchange&environment=local-lab"


def incident(customer="exchange", environment="local-lab", state="firing"):
    return {"labels": {"alertname": "DatabaseConnectionFailed", "customer": customer,
                       "environment": environment, "role": "standby", "node": "db-standby", "severity": "critical"},
            "annotations": {"summary": "RAW_ANNOTATION_MUST_STAY_PRIVATE"},
            "startsAt": "2026-09-18T04:59:00Z", "endsAt": "2026-09-18T05:00:00Z" if state == "resolved" else None,
            "status": state}


class ConsoleApiTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.store = Store(self.root / "outbox.sqlite3")
        self.config = {"customer": "exchange", "environment": "local-lab", "artifacts": {
            "enabled": True, "directory": str(self.root / "reports"),
            "public_base_url": "http://127.0.0.1:59188/reporter", "font": "unused.ttf"}}
        self.secret = b"console-artifact-signing-test-key-value"
        key_file = self.root / "key"
        key_file.write_bytes(self.secret)
        environment = patch.dict(os.environ, {"REPORT_ARTIFACT_KEY_FILE": str(key_file)})
        environment.start()
        self.addCleanup(environment.stop)
        self.token = "console-authentication-test-token-value"

    def add_report(self, identity="incident:first", customer="exchange", environment="local-lab",
                   state="firing", scope=True):
        event = incident(customer, environment, state)
        report = {"id": identity, "kind": "incident", "evidence": {
            "collected_at": "2026-09-18T05:00:10Z", "facts": [{"id": "trigger", "status": "ok", "records": [event]}]},
            "analysis": {"mode": "gemini", "summary": "PRIVATE_AI_RESPONSE", "hypotheses": []}}
        if scope:
            report["evidence"]["scope"] = {"customer": customer, "environment": environment}
        self.store.enqueue(identity, "incident", {"incident": [event]})
        with self.store.connect() as db:
            db.execute("UPDATE jobs SET report=?,status='sent',sent=created+2 WHERE id=?", (json.dumps(report), identity))
        return report

    def save(self, report):
        with self.store.connect() as db:
            db.execute("UPDATE jobs SET report=? WHERE id=?", (json.dumps(report), report["id"]))

    def files(self, identity="incident:first"):
        folder = Path(self.config["artifacts"]["directory"]) / hashlib.sha256(identity.encode()).hexdigest()
        folder.mkdir(parents=True)
        (folder / "report.pdf").write_bytes(b"%PDF-console-test")
        (folder / "report.html").write_text("<title>Report</title>", encoding="utf-8")
        self.store.artifact_status(identity, "ready")
        return folder

    def start_server(self):
        server = ReportServer(("127.0.0.1", 0), self.store, self.token, self.config)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(thread.join)
        self.addCleanup(server.shutdown)
        return f"http://127.0.0.1:{server.server_port}"

    def test_requires_bearer_before_reading_scope_and_does_not_cache_metadata(self):
        self.add_report()
        base = self.start_server()
        for authorization in (None, "Bearer wrong-token"):
            headers = {"Authorization": authorization} if authorization else {}
            with self.assertRaises(HTTPError) as rejected:
                urlopen(Request(base + QUERY, headers=headers))
            self.assertEqual(rejected.exception.code, 401)
            rejected.exception.close()
        with urlopen(Request(base + QUERY, headers={"Authorization": "Bearer " + self.token})) as response:
            result = json.load(response)
            self.assertEqual(response.headers["Cache-Control"], "private, no-store")
            self.assertEqual(response.headers["X-Content-Type-Options"], "nosniff")
        self.assertEqual(len(result["reports"]), 1)
        self.assertEqual(result["reports"][0]["title"], "Standby DB 접속 장애 발생")
        self.assertEqual(result["reports"][0]["severity"], "P1")
        self.assertEqual(result["reports"][0]["alertState"], "firing")

    def test_rejects_unknown_duplicate_blank_and_unbounded_queries(self):
        self.assertEqual(parse_query(QUERY), ("exchange", "local-lab", 50))
        self.assertEqual(parse_query(QUERY + "&limit=100")[-1], 100)
        for path in ("/console/reports", QUERY + "&customer=other", QUERY + "&limit=101", QUERY + "&limit=0",
                     QUERY + "&limit=-1", QUERY + "&limit=1.0", QUERY + "&limit=", QUERY + "&cursor=anything",
                     QUERY + "&url=http://external", QUERY + "&", QUERY + "#fragment", QUERY.replace("exchange", "*"),
                     QUERY.replace("exchange", ""), QUERY.replace("exchange", "%00"), QUERY + "/extra"):
            with self.subTest(path=path), self.assertRaises(ValueError):
                parse_query(path)
        base = self.start_server()
        for path, status in ((QUERY + "&limit=101", 400), ("/console/reports/anything?customer=exchange", 404)):
            with self.assertRaises(HTTPError) as rejected:
                urlopen(Request(base + path, headers={"Authorization": "Bearer " + self.token}))
            self.assertEqual(rejected.exception.code, status)
            rejected.exception.close()

    def test_filters_both_customer_and_environment_without_runtime_scope_fallback(self):
        self.add_report()
        self.add_report("incident:other", customer="other")
        self.add_report("incident:aws", environment="aws")
        self.store.enqueue("daily:pending", "daily", {"window_end": time.time()})
        self.store.enqueue("test:unscoped", "test", {})
        result = list_reports(self.store, self.config, QUERY)
        self.assertEqual([row["id"] for row in result["reports"]], ["incident:first"])
        other = list_reports(self.store, self.config, QUERY.replace("exchange", "other"))
        self.assertEqual([row["id"] for row in other["reports"]], ["incident:other"])

    def test_excludes_conflicting_trigger_ambient_and_metric_scopes(self):
        report = self.add_report()
        mutations = []
        mixed_trigger = copy.deepcopy(report)
        mixed_trigger["evidence"]["facts"][0]["records"].append(incident("other"))
        mutations.append(mixed_trigger)
        mixed_alerts = copy.deepcopy(report)
        mixed_alerts["evidence"]["facts"].append({"id": "alerts", "records": [incident(environment="aws")]})
        mutations.append(mixed_alerts)
        unscoped_trigger = copy.deepcopy(report)
        del unscoped_trigger["evidence"]["facts"][0]["records"][0]["labels"]["customer"]
        mutations.append(unscoped_trigger)
        mixed_metric = copy.deepcopy(report)
        mixed_metric["evidence"]["facts"].append({"id": "db_connections", "samples": [
            {"labels": {"environment": "aws"}, "last": 2}]})
        mutations.append(mixed_metric)
        for changed in mutations:
            self.save(changed)
            self.assertEqual(list_reports(self.store, self.config, QUERY)["reports"], [])

    def test_legacy_reports_require_fully_scoped_triggers_and_pending_incidents_are_identified(self):
        self.add_report(scope=False, state="resolved")
        self.store.enqueue("incident:pending", "incident", {"incident": [incident()], "window_end": 1789707610})
        self.store.enqueue("incident:unscoped", "incident", {"incident": [{"labels": {"node": "unknown"}}]})
        reports = list_reports(self.store, self.config, QUERY)["reports"]
        self.assertEqual(len(reports), 2)
        pending = next(row for row in reports if row["status"] == "pending")
        self.assertIsNone(pending["sent"])
        self.assertEqual(pending["links"], {"pdf": None, "html": None})
        self.assertEqual(next(row for row in reports if row["status"] == "sent")["alertState"], "resolved")

    def test_projects_only_safe_metadata_without_analysis_annotations_payload_or_runtime_secrets(self):
        report = self.add_report()
        report["evidence"]["facts"].append({"id": "journal", "records": ["PRIVATE_LOG_BODY"]})
        self.save(report)
        result = list_reports(self.store, self.config, QUERY)
        encoded = json.dumps(result)
        for secret in ("RAW_ANNOTATION_MUST_STAY_PRIVATE", "PRIVATE_AI_RESPONSE", "PRIVATE_LOG_BODY",
                       self.token, self.secret.decode(), "public_base_url", "annotations", "analysis", "payload"):
            self.assertNotIn(secret, encoded)
        self.assertEqual(set(result["reports"][0]), {
            "id", "kind", "created", "sent", "status", "title", "severity", "summary", "alertState",
            "targets", "customer", "environment", "links"})
        self.assertEqual(result["reports"][0]["targets"], ["db-standby"])

    def test_renews_only_existing_registered_files_and_never_renders_or_sends(self):
        self.add_report()
        folder = self.files()
        now = 1789707600
        with patch("artifacts.render_files", side_effect=AssertionError("rendering forbidden")), \
                patch("reporter.analyze", side_effect=AssertionError("AI forbidden")), \
                patch("reporter.request", side_effect=AssertionError("delivery forbidden")):
            first = list_reports(self.store, self.config, QUERY, now)["reports"][0]
            second = list_reports(self.store, self.config, QUERY, now + 600)["reports"][0]
        self.assertNotEqual(first["links"]["pdf"], second["links"]["pdf"])
        self.assertEqual(artifacts.read_file(first["links"]["pdf"], self.config, now)[0], b"%PDF-console-test")
        with self.assertRaises(PermissionError):
            artifacts.read_file(first["links"]["pdf"], self.config, now + LINK_TTL_SECONDS)
        with self.assertRaises(PermissionError):
            artifacts.read_file(first["links"]["pdf"].replace("report.pdf", "report.html"), self.config, now)
        (folder / "report.pdf").unlink()
        remaining = list_reports(self.store, self.config, QUERY, now)["reports"][0]["links"]
        self.assertIsNone(remaining["pdf"])
        self.assertIsNotNone(remaining["html"])
        self.store.artifact_status("incident:first", "failed")
        self.assertEqual(list_reports(self.store, self.config, QUERY)["reports"][0]["links"], {"pdf": None, "html": None})

    def test_invalid_artifact_files_and_missing_signing_key_do_not_receive_links(self):
        self.add_report()
        folder = self.files()
        (folder / "report.pdf").write_bytes(b"")
        (folder / "report.html").unlink()
        (folder / "report.html").mkdir()
        self.assertEqual(list_reports(self.store, self.config, QUERY)["reports"][0]["links"], {"pdf": None, "html": None})
        (folder / "report.pdf").write_bytes(b"%PDF-console-test")
        with patch.dict(os.environ, {"REPORT_ARTIFACT_KEY_FILE": str(self.root / "missing-key")}):
            self.assertIsNone(list_reports(self.store, self.config, QUERY)["reports"][0]["links"]["pdf"])

    def test_limits_matching_reports_after_scope_filtering_and_preserves_newest_order(self):
        for index in range(4):
            identity = f"incident:{index}"
            self.add_report(identity, customer="other" if index == 3 else "exchange")
            with self.store.connect() as db:
                db.execute("UPDATE jobs SET created=? WHERE id=?", (1000 + index, identity))
        reports = list_reports(self.store, self.config, QUERY + "&limit=2")["reports"]
        self.assertEqual([row["id"] for row in reports], ["incident:2", "incident:1"])


if __name__ == "__main__":
    unittest.main()
