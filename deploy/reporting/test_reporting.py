import concurrent.futures
import contextlib
import datetime as dt
import json
import io
import os
import socket
import sqlite3
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

from evidence import collect, file_evidence, metric_fact
from reporter import (KST, ReportServer, Store, analyze, card, daily_key, drain_one,
                      delivery_enabled, incident_payload, load_config, main, retry_delay, validate_analysis)
from transport import RequestFailure, json_request, redact, request, restricted_https, teams_secret


class MockHandler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def respond(self, status, value, headers=None):
        data = json.dumps(value).encode()
        self.send_response(status)
        for key, item in (headers or {}).items():
            self.send_header(key, item)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        try:
            self.wfile.write(data)
        except ConnectionError:
            pass

    def do_GET(self):
        self.server.paths.append(self.path)
        if self.path == "/redirect":
            self.respond(302, {}, {"Location": self.server.base + "/api/v2/alerts"})
        elif self.path == "/api/v2/alerts":
            self.respond(200, [])
        else:
            if self.server.metrics_error:
                self.respond(503, {})
            else:
                samples = [] if self.server.missing else [{"metric": {"job": "node", "instance": "10.0.0.1", "email": "private@example.org"},
                                                          "value": [time.time(), self.server.value]}]
                self.respond(200, {"status": "success", "data": {"result": samples}})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if self.path == "/ai":
            time.sleep(self.server.ai_delay)
            self.respond(200, self.server.ai_result)
        else:
            self.server.deliveries.append(body)
            self.respond(self.server.delivery_status, {}, {"Retry-After": "120"})


class ReportingTests(unittest.TestCase):
    def test_incident_identity_ignores_label_key_order(self):
        first = {"status": "firing", "alerts": [{"labels": {"severity": "info", "alertname": "Check", "instance": "lab"},
                                                "startsAt": "2026-09-18T01:26:45Z"}]}
        second = json.loads(json.dumps(first))
        second["alerts"][0]["labels"] = dict(sorted(second["alerts"][0]["labels"].items()))
        self.assertEqual(incident_payload(first)[0], incident_payload(second)[0])
        second["alerts"][0]["labels"]["instance"] = "another"
        self.assertNotEqual(incident_payload(first)[0], incident_payload(second)[0])

    @classmethod
    def setUpClass(cls):
        cls.http = ThreadingHTTPServer(("127.0.0.1", 0), MockHandler)
        cls.http.base = f"http://127.0.0.1:{cls.http.server_port}"
        cls.thread = threading.Thread(target=cls.http.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.http.shutdown()
        cls.http.server_close()
        cls.thread.join()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.store = Store(Path(self.temp.name) / "outbox.sqlite3")
        self.http.paths, self.http.deliveries = [], []
        self.http.metrics_error, self.http.missing = False, False
        self.http.value, self.http.delivery_status, self.http.ai_delay = "0", 200, 0
        self.http.ai_result = {"summary": "관측 필요", "observations": [{"text": "자료 상태 확인", "evidence": ["up"]}], "hypotheses": [], "next_checks": []}
        self.config = {"prometheus_url": self.http.base, "alertmanager_url": self.http.base, "analyzer": "mock",
                       "http_timeout": 1, "queries": [{"id": "up", "description": "target", "expr": "up"}]}

    def facts(self):
        return collect(self.config, time.time())

    def sender(self, payload):
        return request(self.http.base + "/teams", payload=payload)

    def row(self, key="test"):
        with self.store.connect() as db:
            return dict(db.execute("SELECT * FROM jobs WHERE id=?", (key,)).fetchone())

    def test_zero_missing_failure_are_distinct(self):
        zero = self.facts()["facts"][0]
        self.http.missing = True
        missing = self.facts()["facts"][0]
        self.http.metrics_error = True
        failure = self.facts()["facts"][0]
        self.assertEqual((zero["status"], zero["samples"][0]["last"]), ("ok", 0))
        self.assertEqual((missing["status"], missing["samples"]), ("missing", []))
        self.assertEqual((failure["status"], failure["samples"]), ("error", []))

    def test_nonfinite_metric_is_error(self):
        self.http.value = "NaN"
        self.assertEqual(self.facts()["facts"][0]["status"], "error")

    def test_query_range_and_alertmanager_are_called(self):
        self.config["queries"][0]["range"] = True
        self.facts()
        self.assertTrue(any(path.startswith("/api/v1/query_range?") for path in self.http.paths))
        self.assertIn("/api/v2/alerts", self.http.paths)

    def test_redirect_does_not_follow(self):
        with self.assertRaises(RequestFailure) as caught:
            request(self.http.base + "/redirect")
        self.assertEqual(caught.exception.status, 302)
        self.assertEqual(self.http.paths, ["/redirect"])

    def test_redaction_removes_credentials_urls_and_pii(self):
        source = {"Authorization": "Bearer sensitive", "password": "secret", "MESSAGE":
                  "password=hidden token=another user@example.org https://example.org/?sig=value 10.2.3.4 Bearer credential"}
        clean = json.dumps(redact(source))
        for secret in ("sensitive", "hidden", "another", "user@example.org", "sig=value", "10.2.3.4", "Bearer credential"):
            self.assertNotIn(secret, clean)

    def test_quoted_log_credentials_are_removed_as_complete_values(self):
        message = json.dumps({"password": 'fixture password with "quotes", comma; tail',
                              "token": "fixture-token", "api_key": "fixture-key",
                              "unit": "reporter.service", "state": "failed"})
        cleaned = redact({"MESSAGE": message})["MESSAGE"]
        for secret in ("fixture", "quotes", "comma", "tail"):
            self.assertNotIn(secret, cleaned)
        self.assertEqual(cleaned.count("[SECRET REDACTED]"), 3)
        self.assertIn("reporter.service", cleaned)
        self.assertIn("failed", cleaned)
        single_quoted = redact("'token': 'fixture token with spaces'; unit=reporter.service")
        self.assertNotIn("fixture", single_quoted)
        self.assertNotIn("spaces", single_quoted)
        self.assertIn("unit=reporter.service", single_quoted)

    def test_truncated_quoted_credentials_do_not_expose_partial_values(self):
        message = 'unit=reporter.service {"password": "' + "fixture value " * 300 + '"}'
        cleaned = redact(message)
        self.assertIn("[SECRET REDACTED]", cleaned)
        self.assertNotIn("fixture", cleaned)
        self.assertNotIn("value", cleaned)
        self.assertIn("unit=reporter.service", cleaned)
        prefix = '{"token": "'
        message = prefix + "fixture " + "x" * (1999 - len(prefix) - len("fixture ")) + "\\"
        cleaned = redact(message)
        self.assertIn("[SECRET REDACTED]", cleaned)
        self.assertNotIn("fixture", cleaned)
        self.assertNotIn("xxx", cleaned)

    def test_credential_words_without_values_remain_useful_log_text(self):
        message = "password rotation pending; token count unavailable; credential validation failed"
        self.assertEqual(redact(message), message)

    def test_files_are_bounded_and_confined_to_evidence_root(self):
        base = Path(self.temp.name) / "evidence"
        base.mkdir()
        (base / "journal.jsonl").write_text(json.dumps({"MESSAGE": "token=sensitive", "_SYSTEMD_UNIT": "example.service", "secret": "hidden"}), encoding="utf-8")
        result = file_evidence(base, "journal.jsonl", "journal")
        self.assertEqual(result["status"], "ok")
        self.assertNotIn("sensitive", json.dumps(result))
        self.assertNotIn("hidden", json.dumps(result))
        self.assertEqual(file_evidence(base, "../outbox.sqlite3", "journal")["status"], "error")
        (base / "large").write_bytes(b"x" * 65537)
        self.assertEqual(file_evidence(base, "large", "journal")["status"], "error")

    def test_mock_is_explicit_and_has_no_hypotheses(self):
        result = analyze(self.facts(), self.config)
        self.assertEqual(result["mode"], "mock")
        self.assertEqual(result["hypotheses"], [])

    def test_host_source_failure_is_not_ok(self):
        base = Path(self.temp.name)
        document = {"schema_version": 1, "collected_at": "2026-09-18T00:00:00Z", "status": "partial",
                    "sources": {"filesystems": {"ok": True, "output": "available"},
                                "network": {"ok": False, "error": "permission_denied"},
                                "unapproved": {"ok": True, "output": "omit-this"}}}
        (base / "host.json").write_text(json.dumps(document), encoding="utf-8")
        result = file_evidence(base, "host.json", "host")
        self.assertEqual(result["status"], "error")
        self.assertNotIn("unapproved", result["records"])
        self.assertEqual(result["collected_at"], document["collected_at"])

    def test_ai_unknown_evidence_and_tool_fields_rejected(self):
        facts = self.facts()
        result = dict(self.http.ai_result)
        result["observations"] = [{"text": "unsupported", "evidence": ["invented"]}]
        with self.assertRaises(ValueError):
            validate_analysis(result, facts)
        result = dict(self.http.ai_result, tool_call={"restart": "db"})
        with self.assertRaises(ValueError):
            validate_analysis(result, facts)

    def test_remote_ai_network_response_is_validated(self):
        config = {**self.config, "analyzer": "remote", "ai_timeout": 1}
        with patch.dict(os.environ, {"REPORT_AI_URL": self.http.base + "/ai", "REPORT_AI_KEY": "test-key"}), patch("reporter.restricted_https", side_effect=lambda url, allowed: url):
            result = analyze(self.facts(), config)
        self.assertEqual(result["mode"], "remote")

    def test_ai_timeout_falls_back_without_blocking_delivery(self):
        self.http.ai_delay = 0.2
        config = {**self.config, "analyzer": "remote", "ai_timeout": 0.04}
        with patch.dict(os.environ, {"REPORT_AI_URL": self.http.base + "/ai", "REPORT_AI_KEY": "test-key"}), patch("reporter.restricted_https", side_effect=lambda url, allowed: url):
            result = analyze(self.facts(), config)
        self.assertEqual(result["mode"], "facts-only")

    def test_daily_jobs_are_idempotent_and_kst_aligned(self):
        key, end = daily_key("2026-09-18")
        self.assertEqual(dt.datetime.fromtimestamp(end, KST).hour, 9)
        self.assertTrue(self.store.enqueue(key, "daily", {"window_end": end}))
        self.assertFalse(self.store.enqueue(key, "daily", {"window_end": end}))

    def test_incident_duplicates_and_resolved_transition(self):
        body = {"status": "firing", "alerts": [{"labels": {"alertname": "HostDown"}, "startsAt": "2026-09-18T00:00:00Z"}]}
        key, payload = incident_payload(body)
        self.assertTrue(self.store.enqueue(key, "incident", payload))
        self.assertFalse(self.store.enqueue(*[key, "incident", payload]))
        body["status"] = "resolved"
        self.assertNotEqual(key, incident_payload(body)[0])

    def test_concurrent_workers_deliver_one_job_once(self):
        self.store.enqueue("test", "daily", {})
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
            list(executor.map(lambda _: drain_one(self.store, self.config, self.sender), range(8)))
        self.assertEqual(len(self.http.deliveries), 1)
        self.assertEqual(self.row()["status"], "sent")

    def test_restart_recovers_expired_lease(self):
        self.store.enqueue("test", "daily", {})
        self.store.claim()
        with self.store.connect() as db:
            db.execute("UPDATE jobs SET available=?", (time.time() - 1,))
        restarted = Store(self.store.path)
        drain_one(restarted, self.config, self.sender)
        self.assertEqual(self.row()["status"], "sent")

    def test_saved_report_survives_retry_without_reanalysis(self):
        self.store.enqueue("test", "daily", {})
        self.http.delivery_status = 503
        drain_one(self.store, self.config, self.sender)
        saved = self.row()["report"]
        self.assertIsNotNone(saved)
        with self.store.connect() as db:
            db.execute("UPDATE jobs SET available=?", (time.time() - 1,))
        self.http.delivery_status = 200
        with patch("reporter.collect", side_effect=AssertionError("must reuse saved evidence")):
            drain_one(Store(self.store.path), self.config, self.sender)
        self.assertEqual(self.row()["report"], saved)
        self.assertEqual(self.row()["status"], "sent")
        with self.store.connect() as db:
            history = [tuple(row) for row in db.execute("SELECT attempt,status,error FROM job_attempts WHERE job_id='test' ORDER BY attempt")]
        self.assertEqual(history, [(1, "queued", "http_error_503"), (2, "sent", None)])

    def test_429_retry_after_and_permanent_failure(self):
        self.store.enqueue("test", "daily", {})
        self.http.delivery_status = 429
        before = time.time()
        drain_one(self.store, self.config, self.sender)
        row = self.row()
        self.assertEqual(row["status"], "queued")
        self.assertGreaterEqual(row["available"], before + 120)
        with self.store.connect() as db:
            db.execute("UPDATE jobs SET available=?", (time.time() - 1,))
        self.http.delivery_status = 401
        drain_one(self.store, self.config, self.sender)
        self.assertEqual(self.row()["status"], "dead")
        self.assertNotIn("http://", self.row()["error"])

    def test_card_falls_back_to_facts_when_model_analysis_breaks_card(self):
        self.store.enqueue("test", "daily", {})
        real_card = card
        calls = []

        def flaky_card(report):
            calls.append(report["analysis"].get("mode"))
            if report["analysis"].get("mode") != "facts-only":
                raise TypeError("model output shape")
            return real_card(report)
        with patch("reporter.card", side_effect=flaky_card), \
                patch("reporter.analyze", return_value={"mode": "ollama", "summary": "x", "observations": [], "hypotheses": [], "next_checks": [None]}):
            drain_one(self.store, self.config, self.sender)
        self.assertEqual(calls, ["ollama", "facts-only"])
        self.assertEqual(self.row()["status"], "sent")

    def test_generation_error_retries_then_dead_letter_with_type_only(self):
        self.store.enqueue("test", "daily", {})
        with patch("reporter.collect", side_effect=KeyError("secret-path")):
            drain_one(self.store, self.config, self.sender)
            self.assertEqual(self.row()["status"], "queued")
            self.assertEqual(self.row()["error"], "report_generation_failed_KeyError")
            for _ in range(2):
                with self.store.connect() as db:
                    db.execute("UPDATE jobs SET available=?", (time.time() - 1,))
                drain_one(self.store, self.config, self.sender)
        self.assertEqual(self.row()["status"], "dead")
        self.assertNotIn("secret", self.row()["error"])

    def test_retry_count_is_bounded(self):
        self.store.enqueue("test", "daily", {})
        with self.store.connect() as db:
            db.execute("UPDATE jobs SET attempts=5")
        self.http.delivery_status = 503
        drain_one(self.store, self.config, self.sender)
        self.assertEqual(self.row()["status"], "dead")
        self.assertEqual(retry_delay("999999", 0), 3600)

    def test_teams_exact_host_allowlist_and_raw_secret_file(self):
        url = "https://prod-01.test.logic.azure.com/workflows/test?sig=not-a-real-key"
        file = Path(self.temp.name) / ".env"
        file.write_text(url, encoding="utf-8")
        with patch.dict(os.environ, {"TEAMS_WEBHOOK_URL": "", "REPORT_TEAMS_ALLOWED_HOSTS": "prod-01.test.logic.azure.com"}):
            self.assertEqual(teams_secret(file), url)
        with self.assertRaises(ValueError):
            restricted_https(url, "different.logic.azure.com", True)
        with self.assertRaises(ValueError):
            restricted_https("https://evil.example/path", "evil.example", True)
        with self.assertRaises(ValueError):
            restricted_https("https://prod-01.test.logic.azure.com.evil.example/path", "prod-01.test.logic.azure.com.evil.example", True)

    def test_card_has_evidence_and_no_actions(self):
        facts = self.facts()
        payload = card({"id": "test", "kind": "daily", "evidence": facts, "analysis": analyze(facts, self.config)})
        encoded = json.dumps(payload, ensure_ascii=False)
        self.assertIn("모의 분석", encoded)
        self.assertIn("근거", encoded)
        self.assertNotIn('"actions"', encoded)
        self.assertLess(len(encoded.encode()), 28000)

    def test_ingestion_auth_body_limit_duplicates_and_metrics(self):
        token = "a" * 40
        server = ReportServer(("127.0.0.1", 0), self.store, token)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = f"http://127.0.0.1:{server.server_port}"
        try:
            self.assertEqual(json_request(base + "/health")["status"], "ok")
            body = {"status": "firing", "alerts": [{"labels": {"alertname": "HostDown"}, "startsAt": "2026-09-18T00:00:00Z"}]}
            with self.assertRaises(RequestFailure) as unauthorized:
                request(base + "/alerts", payload=body)
            self.assertEqual(unauthorized.exception.status, 401)
            headers = {"Authorization": "Bearer " + token}
            self.assertFalse(json_request(base + "/alerts", payload=body, headers=headers)["duplicate"])
            self.assertTrue(json_request(base + "/alerts", payload=body, headers=headers)["duplicate"])
            with self.assertRaises(RequestFailure) as oversized:
                request(base + "/alerts", payload={"padding": "x" * 66000}, headers=headers)
            self.assertEqual(oversized.exception.status, 413)
            metrics = request(base + "/metrics", headers=headers).decode()
            self.assertIn('report_jobs{status="pending"} 1', metrics)
            with self.assertRaises(RequestFailure) as forbidden:
                request(base + "/metrics")
            self.assertEqual(forbidden.exception.status, 401)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_config_query_limits(self):
        config = load_config(Path(__file__).with_name("config.example.json"))
        self.assertEqual(config["analyzer"], "mock")

    def test_delivery_requires_explicit_enable(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertFalse(delivery_enabled())
        with patch.dict(os.environ, {"REPORT_DELIVERY_ENABLED": "false"}):
            self.assertFalse(delivery_enabled())
        with patch.dict(os.environ, {"REPORT_DELIVERY_ENABLED": "true"}):
            self.assertTrue(delivery_enabled())

    def test_paused_drain_does_not_read_secret(self):
        config = Path(self.temp.name) / "config.json"
        config.write_text(json.dumps(self.config), encoding="utf-8")
        with patch.dict(os.environ, {"REPORT_DELIVERY_ENABLED": "false"}), patch("sys.argv", ["reporter", "drain", "--config", str(config), "--state", self.store.path]), patch("reporter.teams_secret") as secret, contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(SystemExit) as stopped:
                main()
        self.assertEqual(stopped.exception.code, 2)
        secret.assert_not_called()


if __name__ == "__main__":
    unittest.main()
